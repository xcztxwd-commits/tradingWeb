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

function redactUrl(value) {
  const absolute = /^[a-z][a-z\d+.-]*:/i.test(value)
  const parsed = new URL(value, 'https://redaction.invalid')
  for (const key of parsed.searchParams.keys()) {
    if (SENSITIVE_KEY.test(key)) parsed.searchParams.set(key, REDACTED)
  }
  return absolute ? parsed.toString() : `${parsed.pathname}${parsed.search}${parsed.hash}`
}

function redactBody(value) {
  try {
    return JSON.stringify(redactValue(JSON.parse(value)))
  } catch {
    const params = new URLSearchParams(value)
    if (![...params.keys()].some((key) => SENSITIVE_KEY.test(key))) return value
    for (const key of params.keys()) {
      if (SENSITIVE_KEY.test(key)) params.set(key, REDACTED)
    }
    return params.toString()
  }
}

function redactValue(value, key = '') {
  if (SENSITIVE_KEY.test(key)) return REDACTED
  if (typeof value === 'string' && key.toLowerCase() === 'url') return redactUrl(value)
  if (typeof value === 'string' && /^(body|postdata)$/i.test(key)) return redactBody(value)
  if (Array.isArray(value)) return value.map((item) => redactValue(item))
  if (!value || typeof value !== 'object') return value

  const redacted = Object.fromEntries(
    Object.entries(value).map(([entryKey, entryValue]) => [
      entryKey,
      redactValue(entryValue, entryKey)
    ])
  )
  if (typeof value.name === 'string' && SENSITIVE_KEY.test(value.name) && 'value' in value) {
    redacted.value = REDACTED
  }
  return redacted
}

export function redactNetworkEntry(entry) {
  return redactValue(entry)
}

export function writeCaseResultAtomic(path, result) {
  const serialized = `${JSON.stringify(redactValue(result), null, 2)}\n`
  const temporaryPath = `${path}.tmp`
  mkdirSync(dirname(path), { recursive: true })

  try {
    writeFileSync(temporaryPath, serialized, 'utf8')
    renameSync(temporaryPath, path)
  } finally {
    if (existsSync(temporaryPath)) rmSync(temporaryPath)
  }
}

export function loadOrCreateRunState(options) {
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
  for (const field of [
    'commit',
    'worktreeFingerprint',
    'schemaVersion',
    'registryFingerprint'
  ]) {
    if (state[field] !== options[field]) throw new Error(`RESUME_MISMATCH: ${field}`)
  }
  return state
}

function hasSelection(selection, key, value) {
  return !selection[key]?.length || selection[key].includes(value)
}

function coversRequiredSubruns(result, definition) {
  if (result?.status !== 'PASS' || result.scopeComplete !== true) return false
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
        complete: !filtered && coversRequiredSubruns(result, definition)
      }
    })
    .filter(Boolean)

  const rerunGroups = new Set(
    entries.filter((entry) => !entry.complete).map((entry) => entry.executionGroup)
  )
  const planned = entries.map((entry) => {
    const groupRerun = rerunGroups.has(entry.executionGroup)
    let reason = 'COMPLETE'
    if (filtered) reason = 'FILTERED_SCOPE'
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
  const definitions = state.definitions.filter((definition) => (
    hasSelection(selection, 'caseIds', definition.id)
    && hasSelection(selection, 'phases', definition.phase)
    && selectedSubruns(definition, selection).length > 0
  ))
  const expectedIds = new Set(definitions.map(({ id }) => id))
  const byId = Map.groupBy(results, ({ id }) => id)
  const counts = { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 }
  const issues = []

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
    if (!(result.status in counts) || result.status === 'MISSING') {
      issues.push(`INVALID_STATUS: ${definition.id}/${result.status}`)
      continue
    }
    counts[result.status] += 1
    if (result.status === 'INVALID_TEST') issues.push(`INVALID_TEST: ${definition.id}`)
    if (result.status !== 'PASS') continue

    const requiredSubruns = selectedSubruns(definition, selection)
    if (!subrunsPass(result, requiredSubruns)
      || (!filtered && result.scopeComplete !== true)
      || (filtered && result.scopeComplete === true)) {
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

function xmlAttributes(source) {
  return Object.fromEntries(
    [...source.matchAll(/([\w:-]+)\s*=\s*["']([^"']*)["']/g)]
      .map((match) => [match[1], match[2]])
  )
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
    for (const match of source.matchAll(/<testsuite\b([^>]*)>/g)) {
      const attributes = xmlAttributes(match[1])
      const className = attributes.name?.split('.').at(-1)
      if (!expected.has(className)) continue
      found.push({
        className,
        suiteName: attributes.name,
        file: name,
        modifiedAt: statSync(path).mtime.toISOString(),
        tests: Number(attributes.tests),
        skipped: Number(attributes.skipped),
        failures: Number(attributes.failures),
        errors: Number(attributes.errors)
      })
    }
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
