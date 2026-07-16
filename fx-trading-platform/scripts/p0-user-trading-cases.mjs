import { createHash, randomUUID } from 'node:crypto'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { types } from 'node:util'

export const P0_PHASES = [
  'preflight',
  'canonical',
  'authority',
  'ui-core',
  'order-trigger',
  'funding',
  'liquidation',
  'source',
  'resilience',
  'ui',
  'selected',
  'report',
  'cleanup',
  'all'
]

export const P0_PROFILES = [
  'UI_CORE',
  'ORDER_TRIGGER',
  'FUNDING_ONLY',
  'LIQUIDATION_ONLY'
]

const P0_RUN_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/
const P0_VIEWPORTS = ['desktop', 'mobile', 'all']
const P0_MODES = ['discovery', 'certification']
const P0_SUITES = ['canonical', 'p0']
const P0_CLI_OPTIONS = new Set([
  'suite',
  'mode',
  'phase',
  'run-id',
  'resume',
  'case',
  'viewport',
  'profile'
])

const sequence = (start, end) => Array.from(
  { length: end - start + 1 },
  (_, index) => start + index
)

const PROFILE_BY_PHASE = {
  __proto__: null,
  'ui-core': ['UI_CORE'],
  'order-trigger': ['ORDER_TRIGGER'],
  funding: ['FUNDING_ONLY'],
  liquidation: ['LIQUIDATION_ONLY']
}

const PROFILE_OVERRIDES = {
  __proto__: null,
  'SOURCE-01': ['UI_CORE'],
  'SOURCE-02': ['UI_CORE', 'ORDER_TRIGGER'],
  'SOURCE-03': ['UI_CORE', 'ORDER_TRIGGER', 'FUNDING_ONLY', 'LIQUIDATION_ONLY'],
  'SOURCE-04': ['ORDER_TRIGGER'],
  'RES-01': ['UI_CORE', 'ORDER_TRIGGER'],
  'RES-02': ['ORDER_TRIGGER', 'LIQUIDATION_ONLY'],
  'RES-03': ['ORDER_TRIGGER', 'FUNDING_ONLY', 'LIQUIDATION_ONLY'],
  'RES-04': ['UI_CORE'],
  'UI-01': ['ORDER_TRIGGER'],
  'UI-02': ['UI_CORE', 'ORDER_TRIGGER']
}

const REQUIRED_SUBRUN_OVERRIDES = {
  __proto__: null,
  'SPOT-10': [
    { id: 'desktop-limit-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-stop-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'SPOT-11': [
    { id: 'desktop-validation-stale', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-recovery-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'PERP-01': [
    { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'PERP-02': [
    { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'PERP-04': [
    { id: 'desktop-base', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-quote', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-contracts', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'PERP-07': [
    { id: 'desktop-over-reversal-replay', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-empty-reduce-only', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-oversized-reduce-only', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'PERP-10': [
    { id: 'desktop-immediate-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'PERP-11': [
    { id: 'desktop-long-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-short-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'BATCH-02': [
    { id: 'desktop-normal', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-partial-failure', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'PROT-02': [
    { id: 'desktop-long-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-long-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'PROT-03': [
    { id: 'desktop-short-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-short-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'PROT-04': [
    { id: 'desktop-immediate', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-two-stage', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-cancel-resting', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'WALLET-02': [
    { id: 'desktop-availability-replay', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-fingerprint-conflict', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'LIFE-01': [
    { id: 'desktop-pending-order', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-oco', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-perp-position-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'SOURCE-03': [
    { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ],
  'RES-01': [
    { id: 'desktop-market-order', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-partial-close', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-full-close', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-transfer', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-reset', profile: 'UI_CORE', viewport: 'desktop' }
  ],
  'RES-02': [
    { id: 'desktop-fill-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ],
  'RES-03': [
    { id: 'desktop-restart-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-restart-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-restart-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ],
  'UI-01': [
    { id: 'desktop-core', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-target', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-core', profile: 'ORDER_TRIGGER', viewport: 'mobile' },
    { id: 'mobile-target', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ]
}

const WHOLE_CASE_AUTHORITY = new Set([
  ...sequence(5, 10).map((number) => `SPOT-${String(number).padStart(2, '0')}`),
  'PERP-04',
  'PERP-05',
  'PERP-11',
  'PROT-02',
  'PROT-03',
  'PROT-04',
  'LIQ-01',
  'LIQ-02',
  'LIQ-03',
  'LIQ-04',
  'RES-02'
])

const AUTHORITY_SUBRUNS = {
  __proto__: null,
  'SPOT-11': ['desktop-recovery-trigger'],
  'PERP-01': ['desktop-target-mark'],
  'PERP-02': ['desktop-target-mark'],
  'PERP-10': ['desktop-trigger'],
  'SOURCE-03': ['desktop-trigger-order-trigger', 'desktop-liquidation'],
  'RES-03': ['desktop-restart-order-trigger', 'desktop-restart-liquidation'],
  'UI-01': ['desktop-target', 'mobile-target']
}

function defaultSubruns(profiles, viewports) {
  return viewports.flatMap((viewport) => profiles.map((profile) => ({
    id: `${viewport}-${profile.toLowerCase().replaceAll('_', '-')}`,
    profile,
    viewport
  })))
}

function descriptor(id, phase) {
  const profiles = PROFILE_OVERRIDES[id] ?? PROFILE_BY_PHASE[phase]
  const viewports = id === 'UI-01' || id === 'UI-02' || id === 'SOURCE-04'
    ? ['desktop', 'mobile']
    : ['desktop']
  const authoritySubruns = AUTHORITY_SUBRUNS[id]

  return {
    id,
    phase,
    profiles,
    viewports,
    executionGroup: id === 'AUTH-01' || id === 'AUTH-02' ? 'auth-session' : id.toLowerCase(),
    authority: authoritySubruns
      ? { mode: 'subruns', subruns: authoritySubruns }
      : { mode: WHOLE_CASE_AUTHORITY.has(id) ? 'whole-case' : 'none', subruns: [] },
    requiredSubruns: REQUIRED_SUBRUN_OVERRIDES[id] ?? defaultSubruns(profiles, viewports),
    handlerId: `run${id.split('-').map((part) => (
      part[0] + part.slice(1).toLowerCase()
    )).join('')}`
  }
}

const cases = (prefix, numbers, phase) => numbers.map((number) => {
  const id = `${prefix}-${String(number).padStart(2, '0')}`
  return descriptor(id, phase)
})

export const P0_CASES = [
  ...cases('AUTH', sequence(1, 3), 'ui-core'),
  ...cases('CAT', sequence(1, 3), 'ui-core'),
  ...cases('SPOT', sequence(1, 3), 'ui-core'),
  ...cases('PERP', [...sequence(1, 9), 12], 'ui-core'),
  ...cases('BATCH', [2], 'ui-core'),
  ...cases('WALLET', [1], 'ui-core'),
  ...cases('LIFE', [2], 'ui-core'),
  ...cases('SPOT', sequence(4, 11), 'order-trigger'),
  ...cases('PERP', [10, 11], 'order-trigger'),
  ...cases('BATCH', [1], 'order-trigger'),
  ...cases('PROT', sequence(1, 6), 'order-trigger'),
  ...cases('WALLET', [2], 'order-trigger'),
  ...cases('LIFE', [1, 3], 'order-trigger'),
  ...cases('FUND', sequence(1, 4), 'funding'),
  ...cases('LIQ', sequence(1, 4), 'liquidation'),
  ...cases('SOURCE', sequence(1, 4), 'source'),
  ...cases('RES', sequence(1, 4), 'resilience'),
  ...cases('UI', sequence(1, 2), 'ui')
]

function canonicalOwnData(value) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value
  if (typeof value === 'number') {
    if (Number.isFinite(value)) return value
    throw new TypeError('INVALID_REGISTRY: non-finite number')
  }
  if (typeof value !== 'object') throw new TypeError('INVALID_REGISTRY: JSON value required')
  if (types.isProxy(value)) throw new TypeError('INVALID_REGISTRY: Proxy')

  const array = Array.isArray(value)
  const prototype = Object.getPrototypeOf(value)
  if ((array && prototype !== Array.prototype)
    || (!array && prototype !== Object.prototype && prototype !== null)) {
    throw new TypeError('INVALID_REGISTRY: non-plain object')
  }
  const descriptors = Object.getOwnPropertyDescriptors(value)
  const ownKeys = Reflect.ownKeys(descriptors)
  if (ownKeys.some((property) => (
    typeof property !== 'string'
    || ('get' in descriptors[property] || 'set' in descriptors[property])
    || (property !== 'length' && !descriptors[property].enumerable)
  ))) throw new TypeError('INVALID_REGISTRY: own data required')

  if (array) {
    const length = descriptors.length.value
    if (ownKeys.length !== length + 1 || ownKeys.some((property) => (
      property !== 'length'
      && (!Number.isSafeInteger(Number(property))
        || String(Number(property)) !== property
        || Number(property) < 0
        || Number(property) >= length)
    ))) throw new TypeError('INVALID_REGISTRY: dense array required')

    const snapshot = []
    for (let index = 0; index < length; index += 1) {
      Object.defineProperty(snapshot, index, {
        value: canonicalOwnData(descriptors[index].value),
        enumerable: true,
        configurable: true,
        writable: true
      })
    }
    Object.setPrototypeOf(snapshot, null)
    return snapshot
  }

  const snapshot = Object.create(null)
  for (const property of ownKeys) {
    Object.defineProperty(snapshot, property, {
      value: canonicalOwnData(descriptors[property].value),
      enumerable: true,
      configurable: true,
      writable: true
    })
  }
  return snapshot
}

export function registryFingerprint(definitions) {
  return createHash('sha256').update(JSON.stringify(canonicalOwnData(definitions))).digest('hex')
}

export const P0_REGISTRY_FINGERPRINT = registryFingerprint(P0_CASES)

function validRunId(value) {
  return typeof value === 'string' && P0_RUN_ID_PATTERN.test(value)
}

function defaultRunId(mode) {
  const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)
  return `p0-${mode}-${stamp}-${randomUUID().replaceAll('-', '').slice(0, 8)}`
}

export function resolveP0RunRoot(artifactBase, runId) {
  if (typeof artifactBase !== 'string' || artifactBase.trim().length === 0 || !validRunId(runId)) {
    throw new Error('P0_RUN_ID_INVALID')
  }
  const base = resolve(artifactBase)
  const target = resolve(base, runId)
  const pathFromBase = relative(base, target)
  if (!pathFromBase
    || pathFromBase === '..'
    || pathFromBase.startsWith(`..${sep}`)
    || isAbsolute(pathFromBase)) {
    throw new Error('P0_RUN_ID_INVALID')
  }
  return target
}

function parseCaseIds(value) {
  const ids = value.split(',')
  if (ids.some((id) => id.length === 0)
    || new Set(ids).size !== ids.length
    || ids.some((id) => !P0_CASES.some((definition) => definition.id === id))) {
    throw new Error('P0_CLI_INVALID_CASE')
  }
  return ids
}

export function parseP0Cli(argv, { generateRunId = defaultRunId } = {}) {
  if (!Array.isArray(argv) || argv.some((argument) => typeof argument !== 'string')) {
    throw new Error('P0_CLI_INVALID_ARGUMENTS')
  }
  const parsed = Object.create(null)
  let list = false
  for (const argument of argv) {
    if (argument === '--list') {
      if (list) throw new Error('P0_CLI_DUPLICATE_OPTION')
      list = true
      continue
    }
    if (!argument.startsWith('--') || !argument.includes('=')) {
      throw new Error('P0_CLI_MALFORMED_OPTION')
    }
    const separator = argument.indexOf('=')
    const name = argument.slice(2, separator)
    const value = argument.slice(separator + 1)
    if (!P0_CLI_OPTIONS.has(name)) throw new Error('P0_CLI_UNKNOWN_OPTION')
    if (Object.hasOwn(parsed, name)) throw new Error('P0_CLI_DUPLICATE_OPTION')
    if (value.length === 0) throw new Error('P0_CLI_OPTION_REQUIRED')
    parsed[name] = value
  }

  const suite = parsed.suite ?? 'canonical'
  if (!P0_SUITES.includes(suite)) throw new Error('P0_CLI_INVALID_SUITE')
  if (suite === 'canonical') {
    if (list || Object.keys(parsed).some((name) => name !== 'suite')) {
      throw new Error('P0_CLI_CANONICAL_OPTION')
    }
    return {
      suite,
      mode: 'discovery',
      phase: 'canonical',
      runId: null,
      resume: null,
      caseIds: [],
      viewport: 'all',
      profile: null,
      list: false
    }
  }

  const mode = parsed.mode ?? 'discovery'
  const phase = parsed.phase ?? 'all'
  const resume = parsed.resume ?? null
  const viewport = parsed.viewport ?? 'all'
  const profile = parsed.profile ?? null
  const caseIds = parsed.case ? parseCaseIds(parsed.case) : []
  if (!P0_MODES.includes(mode)) throw new Error('P0_CLI_INVALID_MODE')
  if (!P0_PHASES.includes(phase)) throw new Error('P0_CLI_INVALID_PHASE')
  if (!P0_VIEWPORTS.includes(viewport)) throw new Error('P0_CLI_INVALID_VIEWPORT')
  if (profile !== null && !P0_PROFILES.includes(profile)) {
    throw new Error('P0_CLI_INVALID_PROFILE')
  }
  for (const runId of [parsed['run-id'], resume].filter((value) => value !== undefined && value !== null)) {
    if (!validRunId(runId)) throw new Error('P0_CLI_INVALID_RUN_ID')
  }
  if (resume && parsed['run-id'] && resume !== parsed['run-id']) {
    throw new Error('P0_CLI_RUN_ID_MISMATCH')
  }
  if (resume && mode !== 'discovery') throw new Error('P0_CLI_RESUME_MODE')

  const filtered = caseIds.length > 0 || viewport !== 'all' || profile !== null
  if (phase === 'all' && filtered) throw new Error('P0_CLI_ALL_FILTER')
  if (phase === 'selected' && caseIds.length === 0) throw new Error('P0_CLI_SELECTED_CASE_REQUIRED')
  if (['preflight', 'canonical', 'authority', 'report', 'cleanup'].includes(phase) && filtered) {
    throw new Error('P0_CLI_PHASE_FILTER')
  }
  if (mode === 'certification' && phase !== 'all' && phase !== 'cleanup') {
    throw new Error('P0_CLI_CERTIFICATION_PHASE')
  }
  if (mode === 'certification' && (resume || filtered)) {
    throw new Error('P0_CLI_CERTIFICATION_FILTER')
  }
  if (list && Object.keys(parsed).some((name) => name !== 'suite')) {
    throw new Error('P0_CLI_LIST_OPTION')
  }

  let runId = parsed['run-id'] ?? resume
  if (phase === 'cleanup' && !runId) throw new Error('P0_CLI_CLEANUP_RUN_ID_REQUIRED')
  if (!list && !runId) {
    runId = generateRunId(mode)
    if (!validRunId(runId)) throw new Error('P0_CLI_INVALID_RUN_ID')
  }

  return {
    suite,
    mode,
    phase,
    runId: runId ?? null,
    resume,
    caseIds,
    viewport,
    profile,
    list
  }
}

function selectedDefinitions(options, definitions) {
  let selected = definitions
  if (['ui-core', 'order-trigger', 'funding', 'liquidation', 'source', 'resilience', 'ui'].includes(options.phase)) {
    selected = selected.filter(({ phase }) => phase === options.phase)
  }
  if (options.caseIds.length > 0) {
    const requested = new Set(options.caseIds)
    selected = selected.filter(({ id }) => requested.has(id))
  }
  return selected.filter(({ requiredSubruns }) => requiredSubruns.some((subrun) => (
    (options.profile === null || subrun.profile === options.profile)
      && (options.viewport === 'all' || subrun.viewport === options.viewport)
  )))
}

export function planP0Execution(options, definitions = P0_CASES) {
  if (options.suite !== 'p0') throw new Error('P0_CLI_P0_SUITE_REQUIRED')
  if (options.phase === 'cleanup') {
    return {
      scope: 'CONTROL',
      phases: ['cleanup'],
      profiles: [],
      definitions: [],
      executionEntries: [],
      includesCanonical: false,
      fullMatrix: false,
      verdict: null,
      businessMutation: false
    }
  }

  const controlOnly = ['preflight', 'canonical', 'authority', 'report'].includes(options.phase)
  const selected = controlOnly ? [] : selectedDefinitions(options, definitions)
  if (selected.length === 0 && !controlOnly) {
    throw new Error('P0_CLI_EMPTY_SELECTION')
  }
  const cropped = options.profile !== null || options.viewport !== 'all'
  const executionEntries = selected.map((definition) => ({
    id: definition.id,
    phase: definition.phase,
    definition,
    selectedSubruns: definition.requiredSubruns.filter((subrun) => (
      (options.profile === null || subrun.profile === options.profile)
        && (options.viewport === 'all' || subrun.viewport === options.viewport)
    )),
    cropped
  }))
  const profiles = P0_PROFILES.filter((profile) => executionEntries.some(({ selectedSubruns }) => (
    selectedSubruns.some((subrun) => subrun.profile === profile)
  )))
  const authorityRequired = executionEntries.some(({ definition, selectedSubruns }) => (
    definition.authority.mode === 'whole-case'
      || (definition.authority.mode === 'subruns'
        && selectedSubruns.some(({ id }) => definition.authority.subruns.includes(id)))
  ))
  let phases
  if (options.phase === 'all') {
    phases = [
      'preflight',
      'canonical',
      'authority',
      'ui-core',
      'order-trigger',
      'funding',
      'liquidation',
      'source',
      'resilience',
      'ui',
      'report',
      'cleanup'
    ]
  } else if (options.phase === 'selected') {
    phases = ['preflight', ...(authorityRequired ? ['authority'] : []), 'selected', 'report', 'cleanup']
  } else if (['preflight', 'canonical', 'authority', 'report'].includes(options.phase)) {
    phases = ['preflight']
    if (options.phase !== 'preflight') phases.push(options.phase)
    if (options.phase !== 'report') phases.push('report')
    phases.push('cleanup')
  } else {
    phases = ['preflight', ...(authorityRequired ? ['authority'] : []), options.phase, 'report', 'cleanup']
  }
  const fullMatrix = options.phase === 'all'
    && options.caseIds.length === 0
    && options.viewport === 'all'
    && options.profile === null
    && selected.length === P0_CASES.length
  return {
    scope: controlOnly ? 'CONTROL' : 'MATRIX',
    phases,
    profiles,
    definitions: selected,
    executionEntries,
    includesCanonical: phases.includes('canonical'),
    fullMatrix,
    verdict: fullMatrix ? 'PASS' : 'PARTIAL_PASS',
    businessMutation: !controlOnly && selected.length > 0
  }
}

export function countByPhase(definitions) {
  return definitions.reduce((counts, definition) => {
    const current = Object.hasOwn(counts, definition.phase)
      ? Object.getOwnPropertyDescriptor(counts, definition.phase).value
      : 0
    Object.defineProperty(counts, definition.phase, {
      value: current + 1,
      enumerable: true,
      configurable: true,
      writable: true
    })
    return counts
  }, {})
}

export async function runCase(definition, context, handlers, details = {}) {
  const descriptor = handlers && typeof handlers === 'object'
    ? Object.getOwnPropertyDescriptor(handlers, definition.handlerId)
    : undefined
  if (!descriptor || !('value' in descriptor) || typeof descriptor.value !== 'function') {
    throw new Error(`INCOMPLETE_MATRIX: ${definition.id}`)
  }
  return descriptor.value(context, definition, details)
}
