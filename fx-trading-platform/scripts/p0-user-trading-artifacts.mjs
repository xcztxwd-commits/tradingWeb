import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { dirname } from 'node:path'
import { pathToFileURL } from 'node:url'

const REDACTED = '[REDACTED]'
const SENSITIVE_KEY = /authorization|cookie|token|password|secret|api[-_]?key/i
const TERMINAL_STATUSES = new Set(['PASS', 'FAIL', 'BLOCKED', 'INVALID_TEST'])
const RAW_REQUEST_CONTAINERS = new Set([
  'request',
  'rawrequest',
  'requestpayload',
  'replayrequest',
  'rawreplayrequest',
  'originalrequest',
  'capturedrequest',
  'rawrequestbody',
  'rawpayload'
])
const SAFE_REPLAY_FIELDS = new Set([
  'id',
  'caseId',
  'subrunId',
  'referenceId',
  'requestId',
  'clientOrderId',
  'fingerprint',
  'requestFingerprint',
  'status',
  'errorCode'
])
const SAFE_REPLAY_OUTCOME_FIELDS = new Set(['status', 'errorCode'])
const SAFE_REQUEST_SCALARS = new Set(['requestid', 'requestfingerprint', 'requestref'])
const RAW_HTTP_REQUEST_DETAIL_FIELDS = new Set(['headers', 'body', 'postdata', 'payload'])
const NETWORK_REQUEST_BODY_FIELDS = new Set(['body', 'postdata', 'payload'])
const RUN_STATE_IDENTITY_FIELDS = [
  'commit',
  'worktreeFingerprint',
  'schemaVersion',
  'registryFingerprint'
]

function redactUrl(value) {
  const absolute = /^[a-z][a-z\d+.-]*:/i.test(value)
  const parsed = new URL(value, 'https://redaction.invalid')
  parsed.username = ''
  parsed.password = ''
  for (const key of parsed.searchParams.keys()) {
    if (SENSITIVE_KEY.test(key)) parsed.searchParams.set(key, REDACTED)
  }
  parsed.hash = ''
  return absolute ? parsed.toString() : `${parsed.pathname}${parsed.search}${parsed.hash}`
}

function isFormBody(value) {
  return value.length > 0 && value.split('&').every((field) => (
    /^[A-Za-z_][A-Za-z\d_.-]*=/.test(field)
  )) && new URLSearchParams(value).toString() === value
}

function redactBody(value, dropOpaque = false) {
  try {
    return JSON.stringify(redactValue(JSON.parse(value)))
  } catch {
    if (dropOpaque && !isFormBody(value)) return undefined
    const params = new URLSearchParams(value)
    if (![...params.keys()].some((key) => SENSITIVE_KEY.test(key))) return value
    for (const key of params.keys()) {
      if (SENSITIVE_KEY.test(key)) params.set(key, REDACTED)
    }
    return params.toString()
  }
}

function sanitizeHeaderValue(name, value) {
  return SENSITIVE_KEY.test(name) ? REDACTED : value
}

function sanitizeHeaderRepresentation(value, allowMap = false) {
  if (typeof value === 'string') {
    const match = value.match(/^([A-Za-z\d!#$%&'*+.^_`|~-]+):[ \t]*(.*)$/)
    return match ? `${match[1]}: ${sanitizeHeaderValue(match[1], match[2])}` : undefined
  }
  if (Array.isArray(value)) {
    if (value.length !== 2 || !value.every((item) => typeof item === 'string')) return undefined
    return [value[0], sanitizeHeaderValue(value[0], value[1])]
  }
  if (!value || typeof value !== 'object') return undefined
  if (typeof value.name === 'string' && typeof value.value === 'string') {
    return { name: value.name, value: sanitizeHeaderValue(value.name, value.value) }
  }
  if (!allowMap) return undefined

  return Object.fromEntries(
    Object.entries(value)
      .filter(([, headerValue]) => typeof headerValue === 'string')
      .map(([name, headerValue]) => [name, sanitizeHeaderValue(name, headerValue)])
  )
}

function sanitizeHeaderEvidence(value) {
  if (!Array.isArray(value)) return sanitizeHeaderRepresentation(value, true)
  return value.map((item) => sanitizeHeaderRepresentation(item))
    .filter((item) => item !== undefined)
}

function redactValue(value, key = '') {
  if (SENSITIVE_KEY.test(key)) return REDACTED
  if (typeof value === 'string' && key.toLowerCase() === 'url') return redactUrl(value)
  if (normalizedKey(key).endsWith('headers')) return sanitizeHeaderEvidence(value)
  if (typeof value === 'string' && /^(body|postdata)$/i.test(key)) return redactBody(value)
  if (typeof value === 'string' && /^(responsebody|responsepayload)$/i.test(key)) {
    return redactBody(value, true)
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactValue(item)).filter((item) => item !== undefined)
  }
  if (!value || typeof value !== 'object') return value

  const redacted = Object.fromEntries(
    Object.entries(value)
      .map(([entryKey, entryValue]) => [entryKey, redactValue(entryValue, entryKey)])
      .filter(([, entryValue]) => entryValue !== undefined)
  )
  if (typeof value.name === 'string' && SENSITIVE_KEY.test(value.name) && 'value' in value) {
    redacted.value = REDACTED
  }
  return redacted
}

export function redactNetworkEntry(entry) {
  return redactValue(entry)
}

function normalizedKey(key) {
  return key.replaceAll(/[^a-z\d]/gi, '').toLowerCase()
}

function persistenceScalar(value) {
  return value === null || ['string', 'number', 'boolean'].includes(typeof value)
}

function sanitizeReplayProbe(probe) {
  if (!probe || typeof probe !== 'object' || Array.isArray(probe)) return {}
  const safe = Object.fromEntries(
    Object.entries(probe).filter(([field, value]) => (
      SAFE_REPLAY_FIELDS.has(field) && persistenceScalar(value)
    ))
  )
  const outcome = Object.fromEntries(
    Object.entries(probe.outcome ?? {}).filter(([field, value]) => (
      SAFE_REPLAY_OUTCOME_FIELDS.has(field) && persistenceScalar(value)
    ))
  )
  if (Object.keys(outcome).length > 0) safe.outcome = outcome
  return safe
}

function looksLikeRawHttpRequest(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const fields = new Set(Object.keys(value).map(normalizedKey))
  return fields.has('method') && fields.has('url')
    && [...RAW_HTTP_REQUEST_DETAIL_FIELDS].some((field) => fields.has(field))
}

function stripRawRequests(value, key = '', inNetworkEvidence = false) {
  const semanticKey = normalizedKey(key)
  if (semanticKey === 'networkevidence') return stripRawRequests(value, '', true)
  if (inNetworkEvidence && NETWORK_REQUEST_BODY_FIELDS.has(semanticKey)) return undefined
  if (SAFE_REQUEST_SCALARS.has(semanticKey)) {
    return persistenceScalar(value) ? value : undefined
  }
  if (RAW_REQUEST_CONTAINERS.has(semanticKey) || semanticKey.includes('request')) return undefined
  if (semanticKey === 'replayprobes') {
    if (!Array.isArray(value)) return []
    return value.map(sanitizeReplayProbe)
  }
  if (!inNetworkEvidence && looksLikeRawHttpRequest(value)) return undefined
  if (Array.isArray(value)) {
    return value
      .map((item) => stripRawRequests(item, '', inNetworkEvidence))
      .filter((item) => item !== undefined)
  }
  if (!value || typeof value !== 'object') return value

  return Object.fromEntries(
    Object.entries(value)
      .map(([field, fieldValue]) => [
        field,
        stripRawRequests(fieldValue, field, inNetworkEvidence)
      ])
      .filter(([, fieldValue]) => fieldValue !== undefined)
  )
}

function inertJsonValue(value, key = '') {
  if (key === 'toJSON' || value === undefined
    || typeof value === 'function' || typeof value === 'symbol') return undefined
  if (Array.isArray(value)) {
    return value.map((item) => inertJsonValue(item)).filter((item) => item !== undefined)
  }
  if (!value || typeof value !== 'object') return value

  return Object.fromEntries(
    Object.entries(value)
      .map(([field, fieldValue]) => [field, inertJsonValue(fieldValue, field)])
      .filter(([, fieldValue]) => fieldValue !== undefined)
  )
}

function sanitizeForPersistence(value) {
  return inertJsonValue(redactValue(stripRawRequests(value)))
}

export function writeCaseResultAtomic(path, result) {
  const serialized = `${JSON.stringify(sanitizeForPersistence(result), null, 2)}\n`
  const temporaryPath = `${path}.tmp`
  mkdirSync(dirname(path), { recursive: true })

  try {
    writeFileSync(temporaryPath, serialized, 'utf8')
    renameSync(temporaryPath, path)
  } finally {
    if (existsSync(temporaryPath)) rmSync(temporaryPath)
  }
}

function validRunStateIdentity(field, value) {
  if (field === 'schemaVersion') {
    return (Number.isSafeInteger(value) && value > 0)
      || (typeof value === 'string' && value.trim().length > 0)
  }
  return typeof value === 'string' && value.trim().length > 0
}

function assertRunStateIdentity(source, label) {
  for (const field of RUN_STATE_IDENTITY_FIELDS) {
    if (!validRunStateIdentity(field, source[field])) {
      throw new Error(`INVALID_RUN_STATE_IDENTITY: ${label}.${field}`)
    }
  }
}

export function loadOrCreateRunState(options) {
  assertRunStateIdentity(options, 'options')
  if (!existsSync(options.path)) {
    const state = {
      schemaVersion: options.schemaVersion,
      runId: options.runId,
      mode: options.mode,
      commit: options.commit,
      worktreeFingerprint: options.worktreeFingerprint,
      registryFingerprint: options.registryFingerprint,
      definitions: options.definitions,
      selection: options.selection ?? {},
      cases: {},
      createdAt: new Date().toISOString()
    }
    writeCaseResultAtomic(options.path, state)
    return state
  }

  const state = JSON.parse(readFileSync(options.path, 'utf8'))
  assertRunStateIdentity(state, 'state')
  for (const field of RUN_STATE_IDENTITY_FIELDS) {
    if (state[field] !== options[field]) throw new Error(`RESUME_MISMATCH: ${field}`)
  }
  return state
}

function hasSelection(selection, key, value) {
  return !selection[key]?.length || selection[key].includes(value)
}

function coversRequiredSubruns(result, definition) {
  if (result?.id !== definition.id
    || result.status !== 'PASS'
    || result.scopeComplete !== true) return false
  if (!Array.isArray(result.subruns) || result.subruns.length !== definition.requiredSubruns.length) {
    return false
  }
  return definition.requiredSubruns.every((required) => (
    result.subruns.filter((actual) => (
      actual.id === required.id
      && actual.profile === required.profile
      && actual.viewport === required.viewport
      && actual.status === 'PASS'
    )).length === 1
  ))
}

export function planResume(state, definitions, selection = {}) {
  const filtered = ['caseIds', 'phases', 'profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const entries = definitions
    .filter((definition) => (
      hasSelection(selection, 'caseIds', definition.id)
      && hasSelection(selection, 'phases', definition.phase)
    ))
    .map((definition) => {
      const subruns = definition.requiredSubruns.filter((subrun) => (
        hasSelection(selection, 'profiles', subrun.profile)
        && hasSelection(selection, 'viewports', subrun.viewport)
      ))
      if (subruns.length === 0) return null
      const result = state.cases?.[definition.id]
      return {
        id: definition.id,
        executionGroup: definition.executionGroup,
        subruns,
        result,
        complete: !subrunsFiltered && coversRequiredSubruns(result, definition)
      }
    })
    .filter(Boolean)

  const rerunGroups = new Set(
    entries.filter((entry) => !entry.complete).map((entry) => entry.executionGroup)
  )
  const planned = entries.map((entry) => {
    const groupRerun = rerunGroups.has(entry.executionGroup)
    let reason = 'COMPLETE'
    if (subrunsFiltered) reason = 'FILTERED_SCOPE'
    else if (entry.result?.status === 'RUNNING') reason = 'RUNNING'
    else if (!entry.complete) reason = entry.result ? 'INCOMPLETE_SUBRUNS' : 'NOT_STARTED'
    else if (groupRerun) reason = 'GROUP_RERUN'

    return {
      id: entry.id,
      action: groupRerun ? 'RUN' : 'SKIP',
      reason,
      scopeComplete: entry.complete && !groupRerun,
      subruns: entry.subruns
    }
  })

  return {
    filtered,
    scopeComplete: !filtered && planned.length > 0
      && planned.every((entry) => entry.action === 'SKIP'),
    entries: planned
  }
}

function selectedSubruns(definition, selection) {
  return definition.requiredSubruns.filter((subrun) => (
    hasSelection(selection, 'profiles', subrun.profile)
    && hasSelection(selection, 'viewports', subrun.viewport)
  ))
}

function subrunsPass(result, requiredSubruns) {
  if (!Array.isArray(result.subruns) || result.subruns.length !== requiredSubruns.length) {
    return false
  }
  return requiredSubruns.every((required) => (
    result.subruns.filter((actual) => (
      actual.id === required.id
      && actual.profile === required.profile
      && actual.viewport === required.viewport
      && actual.status === 'PASS'
    )).length === 1
  ))
}

export function aggregateReport(state, results) {
  const selection = state.selection ?? {}
  if (!Array.isArray(state.definitions) || state.definitions.length === 0) {
    return {
      verdict: 'FAIL',
      scopeComplete: false,
      counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
      issues: ['MISSING_REGISTRY']
    }
  }
  const filtered = ['caseIds', 'phases', 'profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const definitions = state.definitions.filter((definition) => (
    hasSelection(selection, 'caseIds', definition.id)
    && hasSelection(selection, 'phases', definition.phase)
    && selectedSubruns(definition, selection).length > 0
  ))
  const expectedIds = new Set(definitions.map(({ id }) => id))
  const byId = Map.groupBy(results, ({ id }) => id)
  const counts = { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 }
  const issues = filtered && definitions.length === 0 ? ['EMPTY_SELECTION'] : []

  for (const result of results) {
    if (!expectedIds.has(result.id)) issues.push(`UNEXPECTED_CASE: ${result.id}`)
  }
  for (const definition of definitions) {
    const matches = byId.get(definition.id) ?? []
    if (matches.length === 0) {
      counts.MISSING += 1
      issues.push(`MISSING_CASE: ${definition.id}`)
      continue
    }
    if (matches.length > 1) issues.push(`DUPLICATE_CASE: ${definition.id}`)
    const result = matches[0]
    if (!TERMINAL_STATUSES.has(result.status)) {
      issues.push(`INVALID_STATUS: ${definition.id}/${result.status}`)
      continue
    }
    counts[result.status] += 1
    if (result.status === 'INVALID_TEST') issues.push(`INVALID_TEST: ${definition.id}`)
    if (result.status !== 'PASS') continue

    const requiredSubruns = selectedSubruns(definition, selection)
    if (!subrunsPass(result, requiredSubruns)
      || (!subrunsFiltered && result.scopeComplete !== true)
      || (subrunsFiltered && result.scopeComplete !== false)) {
      issues.push(`INCOMPLETE_MATRIX: ${definition.id}`)
    }
  }

  let verdict = 'PASS'
  if (counts.FAIL > 0 || counts.INVALID_TEST > 0 || issues.length > 0) verdict = 'FAIL'
  else if (counts.BLOCKED > 0) verdict = 'BLOCKED'
  else if (filtered) verdict = 'PARTIAL_PASS'

  return {
    verdict,
    scopeComplete: verdict === 'PASS',
    counts,
    issues
  }
}

function validXmlCodePoint(codePoint) {
  return codePoint === 0x9 || codePoint === 0xa || codePoint === 0xd
    || (codePoint >= 0x20 && codePoint <= 0xd7ff)
    || (codePoint >= 0xe000 && codePoint <= 0xfffd)
    || (codePoint >= 0x10000 && codePoint <= 0x10ffff)
}

function validXmlCodePoints(value) {
  for (const character of value) {
    if (!validXmlCodePoint(character.codePointAt(0))) return false
  }
  return true
}

function validXmlCharacterData(value) {
  if (!validXmlCodePoints(value)) return false
  let cursor = 0
  while (true) {
    const start = value.indexOf('&', cursor)
    if (start === -1) return true
    const entity = value.slice(start).match(
      /^&(amp|lt|gt|apos|quot|#\d+|#x[\da-fA-F]+);/
    )?.[0]
    if (!entity) return false
    if (entity.startsWith('&#')) {
      const hexadecimal = entity.startsWith('&#x')
      const digits = entity.slice(hexadecimal ? 3 : 2, -1)
      const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10)
      if (!validXmlCodePoint(codePoint)) return false
    }
    cursor = start + entity.length
  }
}

function xmlAttributes(source) {
  const attributes = new Map()
  let cursor = 0
  while (cursor < source.length) {
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1
    if (cursor === source.length) break
    const name = source.slice(cursor).match(/^[A-Za-z_:][\w:.-]*/)?.[0]
    if (!name || attributes.has(name)) return null
    cursor += name.length
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1
    if (source[cursor] !== '=') return null
    cursor += 1
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1
    const quote = source[cursor]
    if (quote !== '"' && quote !== "'") return null
    const end = source.indexOf(quote, cursor + 1)
    if (end === -1) return null
    const value = source.slice(cursor + 1, end)
    if (value.includes('<') || !validXmlCharacterData(value)) return null
    attributes.set(name, value)
    cursor = end + 1
    if (cursor < source.length && !/\s/.test(source[cursor])) return null
  }
  return Object.fromEntries(attributes)
}

function validXmlDeclaration(attributes) {
  if (!attributes || !['1.0', '1.1'].includes(attributes.version)) return false
  const expectedFields = ['version']
  if ('encoding' in attributes) {
    if (!/^[A-Za-z][A-Za-z\d._-]*$/.test(attributes.encoding)) return false
    expectedFields.push('encoding')
  }
  if ('standalone' in attributes) {
    if (!['yes', 'no'].includes(attributes.standalone)) return false
    expectedFields.push('standalone')
  }
  return Object.keys(attributes).join(',') === expectedFields.join(',')
}

function xmlOpening(markup) {
  const selfClosing = markup.endsWith('/>')
  const inner = markup.slice(1, selfClosing ? -2 : -1)
  const name = inner.match(/^([A-Za-z_:][\w:.-]*)/)?.[1]
  if (!name) return null
  const remainder = inner.slice(name.length)
  if (remainder && !/^\s/.test(remainder)) return null
  const attributes = xmlAttributes(remainder)
  return attributes && { name, attributes, selfClosing }
}

function xmlMarkupEnd(source, start, trackSubset = false) {
  let quote = ''
  let subsetDepth = 0
  for (let index = start + 1; index < source.length; index += 1) {
    const character = source[index]
    if (quote) {
      if (character === quote) quote = ''
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
      continue
    }
    if (trackSubset && character === '[') subsetDepth += 1
    else if (trackSubset && character === ']') subsetDepth -= 1
    else if (character === '>' && subsetDepth === 0) return index
  }
  return -1
}

function surefireRootAttributes(source) {
  const stack = []
  let cursor = 0
  let rootAttributes = null
  let declarationSeen = false

  while (cursor < source.length) {
    const start = source.indexOf('<', cursor)
    const textEnd = start === -1 ? source.length : start
    const characterData = source.slice(cursor, textEnd)
    if (!validXmlCharacterData(characterData)
      || (stack.length === 0 && characterData.trim())) return null
    if (start === -1) break

    if (source.startsWith('<!--', start)) {
      const end = source.indexOf('-->', start + 4)
      if (end === -1) return null
      const comment = source.slice(start + 4, end)
      if (comment.includes('--') || comment.endsWith('-') || !validXmlCodePoints(comment)) {
        return null
      }
      cursor = end + 3
      continue
    }
    if (source.startsWith('<![CDATA[', start)) {
      if (stack.length === 0) return null
      const end = source.indexOf(']]>', start + 9)
      if (end === -1) return null
      cursor = end + 3
      continue
    }
    if (source.startsWith('<?', start)) {
      const end = source.indexOf('?>', start + 2)
      if (end === -1) return null
      if (start !== 0 || declarationSeen || rootAttributes || stack.length > 0
        || !source.startsWith('<?xml', start) || !/\s/.test(source[start + 5])) return null
      const declarationAttributes = xmlAttributes(source.slice(start + 5, end))
      if (!validXmlDeclaration(declarationAttributes)) return null
      declarationSeen = true
      cursor = end + 2
      continue
    }
    if (source.startsWith('<!DOCTYPE', start)) {
      return null
    }

    const end = xmlMarkupEnd(source, start)
    if (end === -1) return null
    const markup = source.slice(start, end + 1)
    const closing = markup.match(/^<\/([A-Za-z_:][\w:.-]*)\s*>$/)
    if (closing) {
      if (stack.pop() !== closing[1]) return null
      cursor = end + 1
      continue
    }
    const opening = xmlOpening(markup)
    if (!opening) return null
    if (stack.length === 0) {
      if (rootAttributes || opening.name !== 'testsuite') return null
      rootAttributes = opening.attributes
    }
    if (!opening.selfClosing) stack.push(opening.name)
    cursor = end + 1
  }

  return stack.length === 0 ? rootAttributes : null
}

function parseSurefireCounter(value) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const counter = Number(value)
  return Number.isSafeInteger(counter) ? counter : null
}

export function parseSurefireReports(reportDir, expectedClasses, invocationStartedAt) {
  if (!Array.isArray(expectedClasses) || expectedClasses.length === 0) {
    throw new Error('SUREFIRE_EXPECTED_CLASSES_REQUIRED')
  }
  const duplicateClass = expectedClasses.find((className, index) => (
    expectedClasses.indexOf(className) !== index
  ))
  if (duplicateClass) throw new Error(`SUREFIRE_EXPECTED_CLASSES_DUPLICATE: ${duplicateClass}`)
  const expected = new Set(expectedClasses)
  const startedAt = new Date(invocationStartedAt)
  const found = []
  for (const name of readdirSync(reportDir).filter((file) => file.endsWith('.xml')).toSorted()) {
    const path = `${reportDir}/${name}`
    const source = readFileSync(path, 'utf8')
    const attributes = surefireRootAttributes(source)
    if (!attributes) {
      throw new Error(`SUREFIRE_MALFORMED_XML: ${name}`)
    }
    const className = attributes.name?.split('.').at(-1)
    if (!expected.has(className)) continue
    found.push({
      className,
      suiteName: attributes.name,
      file: name,
      modifiedAt: statSync(path).mtime.toISOString(),
      tests: parseSurefireCounter(attributes.tests),
      skipped: parseSurefireCounter(attributes.skipped),
      failures: parseSurefireCounter(attributes.failures),
      errors: parseSurefireCounter(attributes.errors)
    })
  }
  for (const className of expectedClasses) {
    const matches = found.filter((suite) => suite.className === className)
    if (matches.length === 0) throw new Error(`SUREFIRE_MISSING_CLASS: ${className}`)
    if (matches.length > 1) throw new Error(`SUREFIRE_DUPLICATE_CLASS: ${className}`)
    if (new Date(matches[0].modifiedAt) < startedAt) {
      throw new Error(`SUREFIRE_STALE_REPORT: ${className}`)
    }
    const suite = matches[0]
    if (!Number.isSafeInteger(suite.tests) || suite.tests <= 0
      || suite.skipped !== 0 || suite.failures !== 0 || suite.errors !== 0) {
      throw new Error(`SUREFIRE_INVALID_SUITE: ${className}`)
    }
  }
  const suites = expectedClasses.map((className) => (
    found.find((suite) => suite.className === className)
  )).filter(Boolean)
  const totals = suites.reduce((sum, suite) => ({
    tests: sum.tests + suite.tests,
    skipped: sum.skipped + suite.skipped,
    failures: sum.failures + suite.failures,
    errors: sum.errors + suite.errors
  }), { tests: 0, skipped: 0, failures: 0, errors: 0 })

  return {
    status: 'PASS',
    invocationStartedAt: startedAt.toISOString(),
    expectedClasses,
    suites,
    totals
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [command, ...rawOptions] = process.argv.slice(2)
  if (command === 'verify-surefire') {
    const options = Object.fromEntries(rawOptions.map((argument) => {
      const separator = argument.indexOf('=')
      return [argument.slice(2, separator), argument.slice(separator + 1)]
    }))
    try {
      const result = parseSurefireReports(
        options.reports,
        options.classes.split(',').filter(Boolean),
        options['started-at']
      )
      writeCaseResultAtomic(options.output, result)
    } catch (error) {
      writeCaseResultAtomic(options.output, { status: 'FAIL', error: error.message })
      console.error(error.message)
      process.exitCode = 1
    }
  }
}
