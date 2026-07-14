import { randomUUID } from 'node:crypto'
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { dirname } from 'node:path'
import { pathToFileURL } from 'node:url'
import { types } from 'node:util'

import {
  P0_CASES,
  P0_REGISTRY_FINGERPRINT,
  registryFingerprint
} from './p0-user-trading-cases.mjs'

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
  'rawpayload',
  'payload'
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
const SAFE_REFERENCE_SCALARS = new Set([
  'fingerprint',
  'requestid',
  'requestfingerprint',
  'requestref'
])
const RAW_HTTP_REQUEST_DETAIL_FIELDS = new Set(['headers', 'body', 'postdata', 'payload'])
const NETWORK_REQUEST_BODY_FIELDS = new Set(['body', 'postdata', 'payload'])
// Filesystem timestamp rounding can put a freshly written report slightly ahead of wall time.
const SUREFIRE_FUTURE_MTIME_TOLERANCE_MS = 2_000
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
  if (value.length === 0 || !value.split('&').every((field) => (
    /^[A-Za-z_][A-Za-z\d_.-]*=/.test(field)
  ))) return false
  const params = new URLSearchParams(value)
  const entries = [...params]
  return params.toString() === value
    && !(entries.length === 1 && entries[0][1] === '')
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
  const semanticKey = normalizedKey(key)
  if (/headers(?:text|blob)$/.test(semanticKey)) return undefined
  if (SENSITIVE_KEY.test(key)) return REDACTED
  if (typeof value === 'string' && key.toLowerCase() === 'url') return redactUrl(value)
  if (semanticKey.endsWith('headers')) return sanitizeHeaderEvidence(value)
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
  return redactValue(inertJsonValue(entry))
}

function normalizedKey(key) {
  return key.replaceAll(/[^a-z\d]/gi, '').toLowerCase()
}

function persistenceScalar(value) {
  return value === null || ['string', 'number', 'boolean'].includes(typeof value)
}

function sanitizeReferenceScalar(value) {
  if (typeof value !== 'string') return persistenceScalar(value) ? value : undefined
  return /^[A-Za-z\d][A-Za-z\d._:/-]*$/.test(value) && !SENSITIVE_KEY.test(value)
    ? value
    : undefined
}

function sanitizeReplayProbe(probe) {
  if (!probe || typeof probe !== 'object' || Array.isArray(probe)) return {}
  const safe = Object.fromEntries(
    Object.entries(probe)
      .filter(([field]) => SAFE_REPLAY_FIELDS.has(field))
      .map(([field, value]) => [field, sanitizeReferenceScalar(value)])
      .filter(([, value]) => value !== undefined)
  )
  const outcome = Object.fromEntries(
    Object.entries(probe.outcome ?? {})
      .filter(([field]) => SAFE_REPLAY_OUTCOME_FIELDS.has(field))
      .map(([field, value]) => [field, sanitizeReferenceScalar(value)])
      .filter(([, value]) => value !== undefined)
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
  if (SAFE_REFERENCE_SCALARS.has(semanticKey)) {
    return sanitizeReferenceScalar(value)
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
  if (typeof value === 'number' && !Number.isFinite(value)) {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: non-finite number')
  }
  if (!value || typeof value !== 'object') return value
  if (types.isProxy(value)) throw new TypeError('UNSAFE_PERSISTENCE_VALUE: Proxy')

  const array = Array.isArray(value)
  const prototype = Object.getPrototypeOf(value)
  if ((array && prototype !== Array.prototype)
    || (!array && prototype !== Object.prototype && prototype !== null)) {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: non-plain object')
  }
  const descriptors = Object.getOwnPropertyDescriptors(value)
  for (const property of Reflect.ownKeys(descriptors)) {
    const descriptor = descriptors[property]
    if ('get' in descriptor || 'set' in descriptor) {
      throw new TypeError('UNSAFE_PERSISTENCE_VALUE: accessor')
    }
  }

  if (array) {
    const inert = []
    for (let index = 0; index < descriptors.length.value; index += 1) {
      const descriptor = descriptors[index]
      if (!descriptor) continue
      const item = inertJsonValue(descriptor.value)
      if (item !== undefined) inert.push(item)
    }
    return inert
  }

  const inert = {}
  for (const [field, descriptor] of Object.entries(descriptors)) {
    if (!descriptor.enumerable) continue
    const fieldValue = inertJsonValue(descriptor.value, field)
    if (fieldValue !== undefined) {
      Object.defineProperty(inert, field, {
        value: fieldValue,
        enumerable: true,
        configurable: true,
        writable: true
      })
    }
  }
  return inert
}

function sanitizeForPersistence(value) {
  return redactValue(stripRawRequests(inertJsonValue(value)))
}

export function writeCaseResultAtomic(path, result) {
  const serialized = `${JSON.stringify(sanitizeForPersistence(result), null, 2)}\n`
  const temporaryPath = `${path}.${process.pid}.${randomUUID()}.tmp`
  mkdirSync(dirname(path), { recursive: true })
  let descriptor
  let owned = false

  try {
    descriptor = openSync(temporaryPath, 'wx')
    owned = true
    writeFileSync(descriptor, serialized, 'utf8')
    closeSync(descriptor)
    descriptor = undefined
    renameSync(temporaryPath, path)
    owned = false
  } finally {
    if (descriptor !== undefined) closeSync(descriptor)
    if (owned) rmSync(temporaryPath, { force: true })
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

function registryIssue(definitions, fingerprint) {
  if (!Array.isArray(definitions)) return 'MISSING_REGISTRY'
  let inertDefinitions
  try {
    inertDefinitions = inertJsonValue(definitions)
  } catch {
    return 'INVALID_REGISTRY'
  }
  if (inertDefinitions.length === 0) return 'MISSING_REGISTRY'
  if (inertDefinitions.length !== P0_CASES.length
    || new Set(inertDefinitions.map((definition) => definition?.id)).size !== P0_CASES.length) {
    return 'INVALID_REGISTRY'
  }
  const computed = registryFingerprint(inertDefinitions)
  if (computed !== P0_REGISTRY_FINGERPRINT) return 'INVALID_REGISTRY'
  if (fingerprint !== computed) return 'REGISTRY_FINGERPRINT_MISMATCH'
  return null
}

function assertCanonicalRegistry(definitions, fingerprint, label) {
  const issue = registryIssue(definitions, fingerprint)
  if (issue) throw new Error(`${issue}: ${label}`)
}

export function loadOrCreateRunState(options) {
  assertRunStateIdentity(options, 'options')
  if (!existsSync(options.path)) {
    assertCanonicalRegistry(options.definitions, options.registryFingerprint, 'options')
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
  assertCanonicalRegistry(options.definitions, options.registryFingerprint, 'options')
  assertCanonicalRegistry(state.definitions, state.registryFingerprint, 'state')
  return state
}

function hasSelection(selection, key, value) {
  return !selection[key]?.length || selection[key].includes(value)
}

const SELECTION_FIELDS = ['caseIds', 'phases', 'profiles', 'viewports']

function resolveSelection(definitions, selection) {
  const filtered = SELECTION_FIELDS.some((key) => selection[key]?.length)
  const entries = definitions
    .filter((definition) => (
      hasSelection(selection, 'caseIds', definition.id)
      && hasSelection(selection, 'phases', definition.phase)
    ))
    .map((definition) => ({
      definition,
      subruns: selectedSubruns(definition, selection)
    }))
    .filter(({ subruns }) => subruns.length > 0)
  if (filtered && entries.length === 0) {
    return { filtered, entries, issues: ['EMPTY_SELECTION'] }
  }

  const coverage = {
    caseIds: new Set(entries.map(({ definition }) => definition.id)),
    phases: new Set(entries.map(({ definition }) => definition.phase)),
    profiles: new Set(entries.flatMap(({ subruns }) => subruns.map(({ profile }) => profile))),
    viewports: new Set(entries.flatMap(({ subruns }) => subruns.map(({ viewport }) => viewport)))
  }
  const issues = []
  for (const key of SELECTION_FIELDS) {
    const missing = [...new Set(selection[key] ?? [])]
      .filter((value) => !coverage[key].has(value))
    if (missing.length > 0) issues.push(`INVALID_SELECTION: ${key}=${missing.join(',')}`)
  }
  return { filtered, entries, issues }
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
  const resolved = resolveSelection(definitions, selection)
  if (resolved.issues.length > 0) throw new Error(resolved.issues[0])
  const { filtered } = resolved
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const entries = resolved.entries
    .map(({ definition, subruns }) => {
      const result = state.cases?.[definition.id]
      return {
        id: definition.id,
        executionGroup: definition.executionGroup,
        subruns,
        result,
        complete: !subrunsFiltered && coversRequiredSubruns(result, definition)
      }
    })

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
  const invalidRegistry = registryIssue(state.definitions, state.registryFingerprint)
  if (invalidRegistry) {
    return {
      verdict: 'FAIL',
      scopeComplete: false,
      counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
      issues: [invalidRegistry]
    }
  }
  const resolved = resolveSelection(state.definitions, selection)
  const { filtered } = resolved
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selection[key]?.length)
  const definitions = resolved.entries.map(({ definition }) => definition)
  const selectedById = new Map(resolved.entries.map(({ definition, subruns }) => (
    [definition.id, subruns]
  )))
  const expectedIds = new Set(definitions.map(({ id }) => id))
  const byId = Map.groupBy(results, ({ id }) => id)
  const counts = { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 }
  const issues = [...resolved.issues]

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

    const requiredSubruns = selectedById.get(definition.id)
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
  const outcomeElements = { failure: 0, error: 0, skipped: 0 }

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
      if (end === -1 || !validXmlCodePoints(source.slice(start + 9, end))) return null
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
    if (Object.hasOwn(outcomeElements, opening.name)) outcomeElements[opening.name] += 1
    if (!opening.selfClosing) stack.push(opening.name)
    cursor = end + 1
  }

  return stack.length === 0 && rootAttributes
    ? { attributes: rootAttributes, outcomeElements }
    : null
}

function parseSurefireCounter(value) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const counter = Number(value)
  return Number.isSafeInteger(counter) ? counter : null
}

export function parseSurefireReports(reportDir, expectedClasses, invocationStartedAt) {
  const verificationTime = new Date()
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
    const structure = surefireRootAttributes(source)
    if (!structure) {
      throw new Error(`SUREFIRE_MALFORMED_XML: ${name}`)
    }
    const { attributes, outcomeElements } = structure
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
      errors: parseSurefireCounter(attributes.errors),
      outcomeElements
    })
  }
  for (const className of expectedClasses) {
    const matches = found.filter((suite) => suite.className === className)
    if (matches.length === 0) throw new Error(`SUREFIRE_MISSING_CLASS: ${className}`)
    if (matches.length > 1) throw new Error(`SUREFIRE_DUPLICATE_CLASS: ${className}`)
    if (new Date(matches[0].modifiedAt) < startedAt) {
      throw new Error(`SUREFIRE_STALE_REPORT: ${className}`)
    }
    if (new Date(matches[0].modifiedAt).getTime()
      > verificationTime.getTime() + SUREFIRE_FUTURE_MTIME_TOLERANCE_MS) {
      throw new Error(`SUREFIRE_FUTURE_REPORT: ${className}`)
    }
    const suite = matches[0]
    if (!Number.isSafeInteger(suite.tests) || suite.tests <= 0
      || suite.skipped !== 0 || suite.failures !== 0 || suite.errors !== 0
      || Object.values(suite.outcomeElements).some((count) => count > 0)) {
      throw new Error(`SUREFIRE_INVALID_SUITE: ${className}`)
    }
  }
  const suites = expectedClasses.map((className) => (
    found.find((suite) => suite.className === className)
  )).filter(Boolean).map(({ outcomeElements: _, ...suite }) => suite)
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

const SUREFIRE_CLI_OPTIONS = new Set(['reports', 'classes', 'started-at', 'output'])
const SUREFIRE_CLI_REQUIRED_OPTIONS = ['reports', 'classes', 'started-at', 'output']

function suppliedCliOutput(rawArguments) {
  const outputs = rawArguments
    .filter((argument) => argument.startsWith('--output='))
    .map((argument) => argument.slice('--output='.length))
    .filter(Boolean)
  return outputs.length === 1 ? outputs[0] : undefined
}

function parseCliArguments(rawArguments) {
  const [command, ...rawOptions] = rawArguments
  if (!command || command.startsWith('--')) throw new Error('CLI_COMMAND_REQUIRED')
  if (command !== 'verify-surefire') throw new Error(`CLI_UNKNOWN_COMMAND: ${command}`)

  const options = {}
  for (const argument of rawOptions) {
    const match = argument.match(/^--([a-z][a-z-]*)=(.*)$/)
    if (!match) throw new Error(`CLI_MALFORMED_OPTION: ${argument}`)
    const [, name, value] = match
    if (!SUREFIRE_CLI_OPTIONS.has(name)) throw new Error(`CLI_UNKNOWN_OPTION: ${name}`)
    if (Object.hasOwn(options, name)) throw new Error(`CLI_DUPLICATE_OPTION: ${name}`)
    if (!value) throw new Error(`CLI_OPTION_REQUIRED: ${name}`)
    options[name] = value
  }
  for (const name of SUREFIRE_CLI_REQUIRED_OPTIONS) {
    if (!Object.hasOwn(options, name)) throw new Error(`CLI_OPTION_REQUIRED: ${name}`)
  }

  const classes = options.classes.split(',')
  if (classes.some((className) => !/^[A-Za-z_$][\w$]*$/.test(className))) {
    throw new Error('CLI_INVALID_OPTION: classes')
  }
  if (!Number.isFinite(new Date(options['started-at']).getTime())) {
    throw new Error('CLI_INVALID_OPTION: started-at')
  }
  return { options, classes }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const rawArguments = process.argv.slice(2)
  const output = suppliedCliOutput(rawArguments)
  try {
    const { options, classes } = parseCliArguments(rawArguments)
    const result = parseSurefireReports(
      options.reports,
      classes,
      options['started-at']
    )
    writeCaseResultAtomic(options.output, result)
  } catch (error) {
    try {
      if (output) writeCaseResultAtomic(output, { status: 'FAIL', error: error.message })
    } catch (writeError) {
      console.error(writeError.message)
    }
    console.error(error.message)
    process.exitCode = 1
  }
}
