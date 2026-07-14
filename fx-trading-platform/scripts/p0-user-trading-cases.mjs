import { createHash } from 'node:crypto'
import { types } from 'node:util'

const sequence = (start, end) => Array.from(
  { length: end - start + 1 },
  (_, index) => start + index
)

const PROFILE_BY_PHASE = {
  'ui-core': ['UI_CORE'],
  'order-trigger': ['ORDER_TRIGGER'],
  funding: ['FUNDING_ONLY'],
  liquidation: ['LIQUIDATION_ONLY']
}

const PROFILE_OVERRIDES = {
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

export async function runCase(definition, context, handlers) {
  const descriptor = handlers && typeof handlers === 'object'
    ? Object.getOwnPropertyDescriptor(handlers, definition.handlerId)
    : undefined
  if (!descriptor || !('value' in descriptor) || typeof descriptor.value !== 'function') {
    throw new Error(`INCOMPLETE_MATRIX: ${definition.id}`)
  }
  return descriptor.value(context)
}
