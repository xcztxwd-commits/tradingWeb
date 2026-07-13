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
  'SOURCE-01': ['UI_CORE', 'ORDER_TRIGGER'],
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
  'PERP-10': [
    { id: 'desktop-immediate-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
  ],
  'SOURCE-03': [
    { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
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
  const viewports = id === 'UI-01' || id === 'UI-02'
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

export function countByPhase(definitions) {
  return definitions.reduce((counts, definition) => {
    counts[definition.phase] = (counts[definition.phase] ?? 0) + 1
    return counts
  }, {})
}

export async function runCase(definition, context, handlers) {
  const handler = handlers[definition.handlerId]
  if (!handler) throw new Error(`INCOMPLETE_MATRIX: ${definition.id}`)
  return handler(context)
}
