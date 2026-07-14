import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  utimesSync,
  writeFileSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  aggregateReport,
  loadOrCreateRunState,
  parseSurefireReports,
  planResume,
  redactNetworkEntry,
  writeCaseResultAtomic
} from './p0-user-trading-artifacts.mjs'
import { P0_CASES, countByPhase, runCase } from './p0-user-trading-cases.mjs'
import './p0-user-trading-advanced-cases.mjs'
import './p0-user-trading-core-cases.mjs'
import './p0-user-trading-oracles.mjs'
import './p0-user-trading-order-cases.mjs'

const specPath = new URL(
  '../docs/superpowers/specs/2026-07-14-p0-user-trading-acceptance-test-design.md',
  import.meta.url
)
const artifactsScript = fileURLToPath(new URL('./p0-user-trading-artifacts.mjs', import.meta.url))

const EXPECTED_EXECUTION_MANIFEST = {
  'AUTH-01': {
    executionGroup: 'auth-session',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'AUTH-02': {
    executionGroup: 'auth-session',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'AUTH-03': {
    executionGroup: 'auth-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-01': {
    executionGroup: 'cat-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-02': {
    executionGroup: 'cat-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-03': {
    executionGroup: 'cat-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-01': {
    executionGroup: 'spot-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-02': {
    executionGroup: 'spot-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-03': {
    executionGroup: 'spot-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-04': {
    executionGroup: 'spot-04',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-05': {
    executionGroup: 'spot-05',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-06': {
    executionGroup: 'spot-06',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-07': {
    executionGroup: 'spot-07',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-08': {
    executionGroup: 'spot-08',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-09': {
    executionGroup: 'spot-09',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-10': {
    executionGroup: 'spot-10',
    requiredSubruns: [
      { id: 'desktop-limit-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-stop-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'SPOT-11': {
    executionGroup: 'spot-11',
    requiredSubruns: [
      { id: 'desktop-validation-stale', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-recovery-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-01': {
    executionGroup: 'perp-01',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-02': {
    executionGroup: 'perp-02',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-03': {
    executionGroup: 'perp-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-04': {
    executionGroup: 'perp-04',
    requiredSubruns: [
      { id: 'desktop-base', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-quote', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-contracts', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-05': {
    executionGroup: 'perp-05',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-06': {
    executionGroup: 'perp-06',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-07': {
    executionGroup: 'perp-07',
    requiredSubruns: [
      { id: 'desktop-over-reversal-replay', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-empty-reduce-only', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-oversized-reduce-only', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-08': {
    executionGroup: 'perp-08',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-09': {
    executionGroup: 'perp-09',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-10': {
    executionGroup: 'perp-10',
    requiredSubruns: [
      { id: 'desktop-immediate-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-11': {
    executionGroup: 'perp-11',
    requiredSubruns: [
      { id: 'desktop-long-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-short-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-12': {
    executionGroup: 'perp-12',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'BATCH-01': {
    executionGroup: 'batch-01',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'BATCH-02': {
    executionGroup: 'batch-02',
    requiredSubruns: [
      { id: 'desktop-normal', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-partial-failure', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PROT-01': {
    executionGroup: 'prot-01',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'PROT-02': {
    executionGroup: 'prot-02',
    requiredSubruns: [
      { id: 'desktop-long-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-long-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-03': {
    executionGroup: 'prot-03',
    requiredSubruns: [
      { id: 'desktop-short-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-short-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-04': {
    executionGroup: 'prot-04',
    requiredSubruns: [
      { id: 'desktop-immediate', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-two-stage', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-cancel-resting', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-05': {
    executionGroup: 'prot-05',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'PROT-06': {
    executionGroup: 'prot-06',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'FUND-01': {
    executionGroup: 'fund-01',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-02': {
    executionGroup: 'fund-02',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-03': {
    executionGroup: 'fund-03',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-04': {
    executionGroup: 'fund-04',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'LIQ-01': {
    executionGroup: 'liq-01',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-02': {
    executionGroup: 'liq-02',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-03': {
    executionGroup: 'liq-03',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-04': {
    executionGroup: 'liq-04',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'WALLET-01': {
    executionGroup: 'wallet-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'WALLET-02': {
    executionGroup: 'wallet-02',
    requiredSubruns: [
      { id: 'desktop-availability-replay', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-fingerprint-conflict', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'LIFE-01': {
    executionGroup: 'life-01',
    requiredSubruns: [
      { id: 'desktop-pending-order', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-oco', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-perp-position-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'LIFE-02': {
    executionGroup: 'life-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'LIFE-03': {
    executionGroup: 'life-03',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SOURCE-01': {
    executionGroup: 'source-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SOURCE-02': {
    executionGroup: 'source-02',
    requiredSubruns: [
      { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'SOURCE-03': {
    executionGroup: 'source-03',
    requiredSubruns: [
      { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
      { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'SOURCE-04': {
    executionGroup: 'source-04',
    requiredSubruns: [
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  },
  'RES-01': {
    executionGroup: 'res-01',
    requiredSubruns: [
      { id: 'desktop-market-order', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-partial-close', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-full-close', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-transfer', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-reset', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'RES-02': {
    executionGroup: 'res-02',
    requiredSubruns: [
      { id: 'desktop-fill-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-close-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-close-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'RES-03': {
    executionGroup: 'res-03',
    requiredSubruns: [
      { id: 'desktop-restart-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-restart-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
      { id: 'desktop-restart-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'RES-04': {
    executionGroup: 'res-04',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'UI-01': {
    executionGroup: 'ui-01',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-target', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-core', profile: 'ORDER_TRIGGER', viewport: 'mobile' },
      { id: 'mobile-target', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  },
  'UI-02': {
    executionGroup: 'ui-02',
    requiredSubruns: [
      { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-ui-core', profile: 'UI_CORE', viewport: 'mobile' },
      { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  }
}

test('registry matches every case heading in the approved specification', () => {
  const specSource = readFileSync(specPath, 'utf8')
  const specIds = [...specSource.matchAll(
    /^### ((?:AUTH|CAT|SPOT|PERP|BATCH|PROT|FUND|LIQ|WALLET|LIFE|SOURCE|RES|UI)-\d{2})\b/gm
  )].map((match) => match[1])

  assert.equal(new Set(P0_CASES.map((item) => item.id)).size, 60)
  assert.deepEqual(P0_CASES.map((item) => item.id).toSorted(), specIds.toSorted())
  assert.deepEqual(countByPhase(P0_CASES), {
    'ui-core': 22,
    'order-trigger': 20,
    funding: 4,
    liquidation: 4,
    source: 4,
    resilience: 4,
    ui: 2
  })
})

test('literal execution manifest locks every case group and required subrun', () => {
  const actual = Object.fromEntries(P0_CASES.map((definition) => [definition.id, {
    executionGroup: definition.executionGroup,
    requiredSubruns: definition.requiredSubruns
  }]))

  assert.deepEqual(actual, EXPECTED_EXECUTION_MANIFEST)
})

test('every descriptor locks its profiles, viewports, authority and required subruns', () => {
  const validProfiles = new Set([
    'UI_CORE',
    'ORDER_TRIGGER',
    'FUNDING_ONLY',
    'LIQUIDATION_ONLY'
  ])
  const validViewports = new Set(['desktop', 'mobile'])

  for (const definition of P0_CASES) {
    assert.deepEqual(Object.keys(definition).toSorted(), [
      'authority',
      'executionGroup',
      'handlerId',
      'id',
      'phase',
      'profiles',
      'requiredSubruns',
      'viewports'
    ])
    assert.ok(definition.profiles.length > 0, `${definition.id} profiles`)
    assert.ok(definition.profiles.every((profile) => validProfiles.has(profile)))
    assert.ok(definition.viewports.length > 0, `${definition.id} viewports`)
    assert.ok(definition.viewports.every((viewport) => validViewports.has(viewport)))
    assert.ok(definition.executionGroup)
    assert.equal(
      definition.handlerId,
      `run${definition.id.split('-').map((part) => (
        part[0] + part.slice(1).toLowerCase()
      )).join('')}`
    )
    assert.ok(definition.requiredSubruns.length > 0, `${definition.id} requiredSubruns`)
    assert.equal(
      new Set(definition.requiredSubruns.map((subrun) => subrun.id)).size,
      definition.requiredSubruns.length,
      `${definition.id} duplicate requiredSubruns`
    )
    for (const subrun of definition.requiredSubruns) {
      assert.ok(definition.profiles.includes(subrun.profile), `${definition.id}/${subrun.id} profile`)
      assert.ok(definition.viewports.includes(subrun.viewport), `${definition.id}/${subrun.id} viewport`)
    }
    assert.ok(['none', 'whole-case', 'subruns'].includes(definition.authority.mode))
    assert.ok(definition.authority.subruns.every((subrunId) => (
      definition.requiredSubruns.some((subrun) => subrun.id === subrunId)
    )))
  }
})

test('cross-profile and responsive cases enumerate every required combination', () => {
  const byId = (id) => P0_CASES.find((definition) => definition.id === id)

  assert.deepEqual(byId('SOURCE-03').requiredSubruns, [
    { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('RES-02').requiredSubruns, [
    { id: 'desktop-fill-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('RES-03').requiredSubruns, [
    { id: 'desktop-restart-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-restart-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-restart-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('UI-01').requiredSubruns, [
    { id: 'desktop-core', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-target', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-core', profile: 'ORDER_TRIGGER', viewport: 'mobile' },
    { id: 'mobile-target', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
  assert.deepEqual(byId('UI-02').requiredSubruns, [
    { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-ui-core', profile: 'UI_CORE', viewport: 'mobile' },
    { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
})

test('SOURCE-04 requires desktop and mobile source-transition evidence', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-04')

  assert.deepEqual(definition.viewports, ['desktop', 'mobile'])
  assert.deepEqual(definition.requiredSubruns, [
    { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
})

test('phase, profile, viewport and authority assignments match the approved matrix', () => {
  const ids = (prefix, numbers) => numbers.map((number) => (
    `${prefix}-${String(number).padStart(2, '0')}`
  ))
  const range = (start, end) => Array.from(
    { length: end - start + 1 },
    (_, index) => start + index
  )
  const expectedByPhase = {
    'ui-core': [
      ...ids('AUTH', range(1, 3)),
      ...ids('CAT', range(1, 3)),
      ...ids('SPOT', range(1, 3)),
      ...ids('PERP', [...range(1, 9), 12]),
      'BATCH-02',
      'WALLET-01',
      'LIFE-02'
    ],
    'order-trigger': [
      ...ids('SPOT', range(4, 11)),
      'PERP-10',
      'PERP-11',
      'BATCH-01',
      ...ids('PROT', range(1, 6)),
      'WALLET-02',
      'LIFE-01',
      'LIFE-03'
    ],
    funding: ids('FUND', range(1, 4)),
    liquidation: ids('LIQ', range(1, 4)),
    source: ids('SOURCE', range(1, 4)),
    resilience: ids('RES', range(1, 4)),
    ui: ids('UI', range(1, 2))
  }
  for (const [phase, expectedIds] of Object.entries(expectedByPhase)) {
    assert.deepEqual(
      P0_CASES.filter((definition) => definition.phase === phase).map(({ id }) => id),
      expectedIds
    )
  }

  const profileOverrides = {
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
  const profileByPhase = {
    'ui-core': ['UI_CORE'],
    'order-trigger': ['ORDER_TRIGGER'],
    funding: ['FUNDING_ONLY'],
    liquidation: ['LIQUIDATION_ONLY']
  }
  for (const definition of P0_CASES) {
    assert.deepEqual(
      definition.profiles,
      profileOverrides[definition.id] ?? profileByPhase[definition.phase]
    )
    assert.deepEqual(
      definition.viewports,
      definition.phase === 'ui' || definition.id === 'SOURCE-04'
        ? ['desktop', 'mobile']
        : ['desktop']
    )
  }

  const wholeCaseAuthority = new Set([
    ...ids('SPOT', range(5, 10)),
    'PERP-04', 'PERP-05', 'PERP-11',
    'PROT-02', 'PROT-03', 'PROT-04',
    ...ids('LIQ', range(1, 4)),
    'RES-02'
  ])
  const subrunAuthority = {
    'SPOT-11': ['desktop-recovery-trigger'],
    'PERP-01': ['desktop-target-mark'],
    'PERP-02': ['desktop-target-mark'],
    'PERP-10': ['desktop-trigger'],
    'SOURCE-03': ['desktop-trigger-order-trigger', 'desktop-liquidation'],
    'RES-03': ['desktop-restart-order-trigger', 'desktop-restart-liquidation'],
    'UI-01': ['desktop-target', 'mobile-target']
  }
  for (const definition of P0_CASES) {
    const expected = subrunAuthority[definition.id]
      ? { mode: 'subruns', subruns: subrunAuthority[definition.id] }
      : { mode: wholeCaseAuthority.has(definition.id) ? 'whole-case' : 'none', subruns: [] }
    assert.deepEqual(definition.authority, expected)
  }
  assert.equal(P0_CASES.find(({ id }) => id === 'AUTH-01').executionGroup, 'auth-session')
  assert.equal(P0_CASES.find(({ id }) => id === 'AUTH-02').executionGroup, 'auth-session')
})

test('dispatch invokes the declared handler and fails fast when it is absent', async () => {
  const definition = P0_CASES[0]
  const context = { runId: 'contract-run' }
  const expected = { status: 'PASS' }
  const handlers = {
    [definition.handlerId]: async (received) => {
      assert.equal(received, context)
      return expected
    }
  }

  assert.equal(await runCase(definition, context, handlers), expected)
  await assert.rejects(
    runCase(definition, context, {}),
    new RegExp(`^Error: INCOMPLETE_MATRIX: ${definition.id}$`)
  )
})

test('case evidence replaces atomically without leaving partial files', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-artifact-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'nested', 'result.json')

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'PASS' })
  assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), {
    id: 'AUTH-01',
    status: 'PASS'
  })
  assert.equal(existsSync(`${resultPath}.tmp`), false)

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'FAIL' })
  assert.equal(JSON.parse(readFileSync(resultPath, 'utf8')).status, 'FAIL')
  assert.throws(() => writeCaseResultAtomic(resultPath, { unsupported: 1n }), TypeError)
  assert.equal(JSON.parse(readFileSync(resultPath, 'utf8')).status, 'FAIL')
  assert.equal(existsSync(`${resultPath}.tmp`), false)
})

test('network evidence redacts secrets recursively without mutating live replay data', () => {
  const entry = {
    url: '/api/orders?access_token=query-secret&symbol=BTCUSDT',
    headers: {
      Authorization: 'Bearer header-secret',
      Cookie: 'session=cookie-secret',
      'X-Trace-Id': 'safe-trace'
    },
    postData: JSON.stringify({
      password: 'body-secret',
      nested: { refreshToken: 'refresh-secret', amount: '10' }
    }),
    responseHeaders: [
      { name: 'Set-Cookie', value: 'session=response-secret' },
      { name: 'Content-Type', value: 'application/json' }
    ]
  }

  const redacted = redactNetworkEntry(entry)
  assert.notEqual(redacted, entry)
  assert.equal(entry.headers.Authorization, 'Bearer header-secret')
  assert.equal(redacted.headers.Authorization, '[REDACTED]')
  assert.equal(redacted.headers.Cookie, '[REDACTED]')
  assert.equal(redacted.headers['X-Trace-Id'], 'safe-trace')
  assert.equal(new URL(redacted.url, 'https://contract.invalid').searchParams.get('access_token'), '[REDACTED]')
  assert.equal(new URL(redacted.url, 'https://contract.invalid').searchParams.get('symbol'), 'BTCUSDT')
  assert.deepEqual(JSON.parse(redacted.postData), {
    password: '[REDACTED]',
    nested: { refreshToken: '[REDACTED]', amount: '10' }
  })
  assert.equal(redacted.responseHeaders[0].value, '[REDACTED]')
  assert.equal(redacted.responseHeaders[1].value, 'application/json')

  const form = redactNetworkEntry({
    url: 'https://example.invalid/login?token=url-secret&next=%2Fwallet',
    body: 'password=form-secret&email=user%40example.com',
    postData: 'plain evidence'
  })
  assert.equal(new URL(form.url).searchParams.get('token'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).get('password'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).get('email'), 'user@example.com')
  assert.equal(form.postData, 'plain evidence')
})

test('atomic evidence never persists credentials', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-secret-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    networkEvidence: [{ headers: { authorization: 'Bearer process-only' } }]
  })

  const persisted = readFileSync(resultPath, 'utf8')
  assert.equal(persisted.includes('process-only'), false)
  assert.equal(JSON.parse(persisted).networkEvidence[0].headers.authorization, '[REDACTED]')
})

test('persistence strips raw replay requests but keeps sanitized replay and network evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-raw-replay-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    raw: 'RAW_REQUEST_ONLY_7af3',
    payload: 'REQUEST_PAYLOAD_ONLY_4c91',
    replay: 'REPLAY_PROBE_ONLY_8e62',
    network: 'NETWORK_SECRET_ONLY_2bd5'
  }

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    rawRequest: {
      method: `POST-${markers.raw}`,
      url: `/api/orders/${markers.raw}`,
      headers: { Authorization: `Bearer ${markers.raw}` },
      body: { clientSecret: markers.raw },
      postData: markers.raw
    },
    requestPayload: JSON.stringify({ password: markers.payload }),
    replayProbes: [{
      id: 'replay-1',
      referenceId: 'order-1',
      fingerprint: 'sha256:contract-fingerprint',
      outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' },
      method: `POST-${markers.replay}`,
      url: `/api/orders/${markers.replay}`,
      headers: { Cookie: markers.replay },
      body: markers.replay,
      postData: markers.replay,
      rawRequest: { url: markers.replay }
    }],
    networkEvidence: [{
      method: 'GET',
      url: `/api/orders?token=${markers.network}&symbol=BTCUSDT`,
      headers: { Authorization: `Bearer ${markers.network}` },
      status: 200
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  const persisted = JSON.parse(serialized)
  assert.equal('rawRequest' in persisted, false)
  assert.equal('requestPayload' in persisted, false)
  assert.deepEqual(persisted.replayProbes, [{
    id: 'replay-1',
    referenceId: 'order-1',
    fingerprint: 'sha256:contract-fingerprint',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.networkEvidence[0].method, 'GET')
  assert.equal(persisted.networkEvidence[0].status, 200)
  assert.equal(
    new URL(persisted.networkEvidence[0].url, 'https://contract.invalid').searchParams.get('symbol'),
    'BTCUSDT'
  )
  assert.equal(persisted.networkEvidence[0].headers.Authorization, '[REDACTED]')
})

test('persistence rejects semantic request containers and nested replay tuples', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-request-bypass-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    l8: 'L8_REQUEST_BYPASS_52d1',
    rawL8: 'RAW_L8_REQUEST_BYPASS_f80a',
    body: 'REQUEST_BODY_BYPASS_961c',
    tuple: 'REQUEST_TUPLE_BYPASS_a74e'
  }

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    l8Request: { method: 'POST', url: `/api/orders/${markers.l8}`, body: markers.l8 },
    rawL8Request: {
      method: 'POST',
      url: `/api/orders/${markers.rawL8}`,
      body: markers.rawL8
    },
    requestBody: { order: markers.body },
    replayProbes: [{
      id: 'replay-safe',
      referenceId: 'order-safe',
      requestId: 'request-safe',
      clientOrderId: 'client-safe',
      fingerprint: 'sha256:safe',
      requestFingerprint: 'sha256:request-safe',
      outcome: {
        status: 'REJECTED',
        errorCode: 'REQUEST_CONFLICT',
        requestTuple: {
          method: 'POST',
          url: `/api/orders/${markers.tuple}`,
          body: markers.tuple
        }
      }
    }],
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?token=ordinary-secret&symbol=BTCUSDT',
      status: 200
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  for (const container of ['l8Request', 'rawL8Request', 'requestBody', 'requestTuple']) {
    assert.equal(serialized.includes(`"${container}"`), false)
  }
  const persisted = JSON.parse(serialized)
  assert.deepEqual(persisted.replayProbes, [{
    id: 'replay-safe',
    referenceId: 'order-safe',
    requestId: 'request-safe',
    clientOrderId: 'client-safe',
    fingerprint: 'sha256:safe',
    requestFingerprint: 'sha256:request-safe',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.networkEvidence[0].method, 'GET')
  assert.equal(persisted.networkEvidence[0].status, 200)
  assert.equal(
    new URL(persisted.networkEvidence[0].url, 'https://contract.invalid').searchParams.get('symbol'),
    'BTCUSDT'
  )
})

test('run state is created atomically and resumes only an identical evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const options = {
    path: statePath,
    runId: 'discovery-contract',
    mode: 'DISCOVERY',
    commit: 'commit-a',
    worktreeFingerprint: 'tree-a',
    schemaVersion: 1,
    registryFingerprint: 'registry-a',
    definitions: P0_CASES.slice(0, 2),
    selection: {}
  }

  const created = loadOrCreateRunState(options)
  assert.equal(created.runId, 'discovery-contract')
  assert.deepEqual(created.cases, {})
  assert.equal(existsSync(`${statePath}.tmp`), false)
  created.cases['AUTH-01'] = { status: 'PASS', scopeComplete: true }
  writeCaseResultAtomic(statePath, created)
  assert.deepEqual(loadOrCreateRunState(options).cases, created.cases)

  for (const [field, value] of [
    ['commit', 'commit-b'],
    ['worktreeFingerprint', 'tree-b'],
    ['schemaVersion', 2],
    ['registryFingerprint', 'registry-b']
  ]) {
    assert.throws(
      () => loadOrCreateRunState({ ...options, [field]: value }),
      new RegExp(`^Error: RESUME_MISMATCH: ${field}$`)
    )
  }
})

test('run state rejects missing, null, blank or invalid evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-identity-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const valid = {
    runId: 'identity-contract',
    mode: 'DISCOVERY',
    commit: 'commit-a',
    worktreeFingerprint: 'tree-a',
    schemaVersion: 1,
    registryFingerprint: 'registry-a',
    definitions: P0_CASES.slice(0, 1),
    selection: {}
  }
  const invalidValues = {
    commit: [undefined, null, '', '   ', 42, {}],
    worktreeFingerprint: [undefined, null, '', '   ', 42, {}],
    schemaVersion: [undefined, null, '', '   ', 0, -1, 1.5, {}],
    registryFingerprint: [undefined, null, '', '   ', 42, {}]
  }
  let sequence = 0

  for (const [field, values] of Object.entries(invalidValues)) {
    for (const value of values) {
      const createPath = join(directory, `create-${sequence++}.json`)
      const createOptions = { ...valid, path: createPath, [field]: value }
      assert.throws(
        () => loadOrCreateRunState(createOptions),
        new RegExp(`^Error: INVALID_RUN_STATE_IDENTITY: options\\.${field}$`)
      )
      assert.equal(existsSync(createPath), false)

      const loadPath = join(directory, `load-${sequence++}.json`)
      const persistedState = {
        ...valid,
        [field]: value,
        cases: {},
        createdAt: '2026-07-14T00:00:00.000Z'
      }
      if (value === undefined) delete persistedState[field]
      writeCaseResultAtomic(loadPath, persistedState)
      assert.throws(
        () => loadOrCreateRunState({ ...valid, path: loadPath }),
        new RegExp(`^Error: INVALID_RUN_STATE_IDENTITY: state\\.${field}$`)
      )

      const invalidLoadOptionsPath = join(directory, `load-options-${sequence++}.json`)
      writeCaseResultAtomic(invalidLoadOptionsPath, {
        ...valid,
        cases: {},
        createdAt: '2026-07-14T00:00:00.000Z'
      })
      assert.throws(
        () => loadOrCreateRunState({
          ...valid,
          path: invalidLoadOptionsPath,
          [field]: value
        }),
        new RegExp(`^Error: INVALID_RUN_STATE_IDENTITY: options\\.${field}$`)
      )
    }
  }
})

function passingCase(definition) {
  return {
    id: definition.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: definition.requiredSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
}

const CASE_LEVEL_SELECTION_FIXTURES = [
  {
    label: 'caseIds',
    selection: { caseIds: ['AUTH-03'] },
    definitions: P0_CASES.filter(({ id }) => id === 'AUTH-03')
  },
  {
    label: 'phases',
    selection: { phases: ['funding'] },
    definitions: P0_CASES.filter(({ phase }) => phase === 'funding')
  }
]

for (const fixture of CASE_LEVEL_SELECTION_FIXTURES) {
  test(`case-level selection resumes complete ${fixture.label} evidence`, () => {
    const cases = Object.fromEntries(
      fixture.definitions.map((definition) => [definition.id, passingCase(definition)])
    )
    const plan = planResume({ cases }, P0_CASES, fixture.selection)

    assert.equal(plan.filtered, true)
    assert.equal(plan.scopeComplete, false)
    assert.deepEqual(
      plan.entries.map(({ id, action, reason, scopeComplete }) => ({
        id,
        action,
        reason,
        scopeComplete
      })),
      fixture.definitions.map(({ id }) => ({
        id,
        action: 'SKIP',
        reason: 'COMPLETE',
        scopeComplete: true
      }))
    )
  })

  test(`case-level selection aggregates complete ${fixture.label} evidence`, () => {
    const report = aggregateReport(
      { definitions: P0_CASES, selection: fixture.selection },
      fixture.definitions.map(passingCase)
    )

    assert.equal(report.verdict, 'PARTIAL_PASS')
    assert.equal(report.scopeComplete, false)
    assert.deepEqual(report.issues, [])
    assert.equal(report.counts.PASS, fixture.definitions.length)
  })
}

test('resume reruns an entire RUNNING case and every member of its execution group', () => {
  const definitions = P0_CASES.filter(({ id }) => id === 'AUTH-01' || id === 'AUTH-02')
  const state = {
    cases: {
      'AUTH-01': passingCase(definitions[0]),
      'AUTH-02': {
        ...passingCase(definitions[1]),
        status: 'RUNNING',
        scopeComplete: false,
        subruns: [{ ...definitions[1].requiredSubruns[0], status: 'RUNNING' }]
      }
    }
  }

  const plan = planResume(state, definitions, {})
  assert.equal(plan.scopeComplete, false)
  assert.deepEqual(plan.entries.map(({ id, action, reason }) => ({ id, action, reason })), [
    { id: 'AUTH-01', action: 'RUN', reason: 'GROUP_RERUN' },
    { id: 'AUTH-02', action: 'RUN', reason: 'RUNNING' }
  ])
  assert.deepEqual(plan.entries[1].subruns, definitions[1].requiredSubruns)
})

test('resume skips only full required-subrun coverage', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const complete = planResume(
    { cases: { [definition.id]: passingCase(definition) } },
    [definition],
    {}
  )
  assert.equal(complete.scopeComplete, true)
  assert.equal(complete.entries[0].action, 'SKIP')

  const partial = passingCase(definition)
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = planResume({ cases: { [definition.id]: partial } }, [definition], {})
  assert.equal(incomplete.scopeComplete, false)
  assert.equal(incomplete.entries[0].action, 'RUN')
  assert.equal(incomplete.entries[0].reason, 'INCOMPLETE_SUBRUNS')
  assert.deepEqual(incomplete.entries[0].subruns, definition.requiredSubruns)
})

test('resume reruns PASS evidence whose result id differs from its case key', () => {
  const expected = P0_CASES.find(({ id }) => id === 'AUTH-01')
  const swapped = P0_CASES.find(({ id }) => id === 'AUTH-02')
  const plan = planResume(
    { cases: { [expected.id]: passingCase(swapped) } },
    [expected],
    {}
  )

  assert.equal(plan.scopeComplete, false)
  assert.equal(plan.entries[0].action, 'RUN')
  assert.equal(plan.entries[0].reason, 'INCOMPLETE_SUBRUNS')
})

test('profile and viewport filters are never resumable as complete scope', () => {
  const source = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const funding = source.requiredSubruns.filter(({ profile }) => profile === 'FUNDING_ONLY')
  const sourcePlan = planResume(
    {
      cases: {
        [source.id]: {
          id: source.id,
          status: 'PASS',
          scopeComplete: false,
          subruns: funding.map((subrun) => ({ ...subrun, status: 'PASS' }))
        }
      }
    },
    [source],
    { profiles: ['FUNDING_ONLY'] }
  )
  assert.equal(sourcePlan.filtered, true)
  assert.equal(sourcePlan.scopeComplete, false)
  assert.equal(sourcePlan.entries[0].action, 'RUN')
  assert.deepEqual(sourcePlan.entries[0].subruns, funding)

  const ui = P0_CASES.find(({ id }) => id === 'UI-02')
  const mobile = ui.requiredSubruns.filter(({ viewport }) => viewport === 'mobile')
  const falselyCompleteMobile = {
    id: ui.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: mobile.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
  const uiPlan = planResume(
    { cases: { [ui.id]: falselyCompleteMobile } },
    [ui],
    { viewports: ['mobile'] }
  )
  assert.equal(uiPlan.scopeComplete, false)
  assert.equal(uiPlan.entries[0].action, 'RUN')
  assert.deepEqual(uiPlan.entries[0].subruns, mobile)
  const uiReport = aggregateReport(
    { definitions: [ui], selection: { viewports: ['mobile'] } },
    [falselyCompleteMobile]
  )
  assert.equal(uiReport.verdict, 'FAIL')
  assert.ok(uiReport.issues.includes(`INCOMPLETE_MATRIX: ${ui.id}`))
})

test('aggregate report reaches PASS only with every required case and subrun', () => {
  const definitions = [
    P0_CASES.find(({ id }) => id === 'AUTH-01'),
    P0_CASES.find(({ id }) => id === 'SOURCE-03')
  ]
  const state = { definitions, selection: {} }
  const passed = aggregateReport(state, definitions.map(passingCase))
  assert.equal(passed.verdict, 'PASS')
  assert.equal(passed.scopeComplete, true)
  assert.deepEqual(passed.counts, {
    PASS: 2,
    FAIL: 0,
    BLOCKED: 0,
    INVALID_TEST: 0,
    MISSING: 0
  })

  const missing = aggregateReport(state, [passingCase(definitions[0])])
  assert.equal(missing.verdict, 'FAIL')
  assert.equal(missing.scopeComplete, false)
  assert.ok(missing.issues.includes('MISSING_CASE: SOURCE-03'))

  const partial = passingCase(definitions[1])
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = aggregateReport(state, [passingCase(definitions[0]), partial])
  assert.equal(incomplete.verdict, 'FAIL')
  assert.ok(incomplete.issues.includes('INCOMPLETE_MATRIX: SOURCE-03'))
})

test('valid FAIL and INVALID_TEST outrank BLOCKED in the terminal verdict', () => {
  const definitions = P0_CASES.slice(0, 2)
  const state = { definitions, selection: {} }

  const failed = aggregateReport(state, [
    { id: definitions[0].id, status: 'BLOCKED', subruns: [] },
    { id: definitions[1].id, status: 'FAIL', subruns: [] }
  ])
  assert.equal(failed.verdict, 'FAIL')

  const blocked = aggregateReport(state, [
    passingCase(definitions[0]),
    { id: definitions[1].id, status: 'BLOCKED', subruns: [] }
  ])
  assert.equal(blocked.verdict, 'BLOCKED')

  const invalid = aggregateReport(state, [
    passingCase(definitions[0]),
    { id: definitions[1].id, status: 'INVALID_TEST', subruns: [] }
  ])
  assert.equal(invalid.verdict, 'FAIL')
})

test('filtered successful evidence is PARTIAL_PASS and never terminal PASS', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const selected = definition.requiredSubruns.filter(({ profile }) => profile === 'FUNDING_ONLY')
  const report = aggregateReport(
    { definitions: [definition], selection: { profiles: ['FUNDING_ONLY'] } },
    [{
      id: definition.id,
      status: 'PASS',
      scopeComplete: false,
      subruns: selected.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }]
  )

  assert.equal(report.verdict, 'PARTIAL_PASS')
  assert.equal(report.scopeComplete, false)
  assert.deepEqual(report.issues, [])
})

test('corrupt, duplicate, unexpected or non-terminal evidence cannot pass', () => {
  const registryless = aggregateReport({ selection: {} }, [])
  assert.equal(registryless.verdict, 'FAIL')
  assert.deepEqual(registryless.issues, ['MISSING_REGISTRY'])

  const definition = P0_CASES[0]
  const state = { definitions: [definition], selection: {} }
  const duplicate = aggregateReport(state, [passingCase(definition), passingCase(definition)])
  assert.equal(duplicate.verdict, 'FAIL')
  assert.ok(duplicate.issues.includes(`DUPLICATE_CASE: ${definition.id}`))

  const unexpected = aggregateReport(state, [
    passingCase(definition),
    { ...passingCase(definition), id: 'UNKNOWN-01' }
  ])
  assert.equal(unexpected.verdict, 'FAIL')
  assert.ok(unexpected.issues.includes('UNEXPECTED_CASE: UNKNOWN-01'))

  const running = aggregateReport(state, [{ id: definition.id, status: 'RUNNING' }])
  assert.equal(running.verdict, 'FAIL')
  assert.ok(running.issues.includes(`INVALID_STATUS: ${definition.id}/RUNNING`))
})

for (const corruptStatus of ['toString', 'constructor', 'ARBITRARY_STATUS']) {
  test(`aggregate rejects evidence status ${corruptStatus}`, () => {
    const definition = P0_CASES[0]
    const report = aggregateReport(
      { definitions: [definition], selection: {} },
      [{ id: definition.id, status: corruptStatus }]
    )

    assert.equal(report.verdict, 'FAIL')
    assert.ok(report.issues.includes(`INVALID_STATUS: ${definition.id}/${corruptStatus}`))
  })
}

function writeSurefireSuite(directory, fileName, attributes, modifiedAt = new Date()) {
  mkdirSync(directory, { recursive: true })
  const path = join(directory, fileName)
  const values = {
    tests: 1,
    skipped: 0,
    failures: 0,
    errors: 0,
    ...attributes
  }
  writeFileSync(path, [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="${values.name}" tests="${values.tests}" skipped="${values.skipped}" failures="${values.failures}" errors="${values.errors}">`,
    '</testsuite>'
  ].join('\n'))
  utimesSync(path, modifiedAt, modifiedAt)
  return path
}

const MALFORMED_SUREFIRE_REPORTS = {
  unclosed: [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0">'
  ].join('\n'),
  'multiple-roots': [
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    '<testsuite name="com.fxplatform.OtherIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>'
  ].join('\n'),
  'trailing-truncation': [
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    '<testcase'
  ].join('\n')
}

for (const [scenario, source] of Object.entries(MALFORMED_SUREFIRE_REPORTS)) {
  test(`Surefire parser rejects malformed XML: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-malformed-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['MalformedIT'],
        new Date(Date.now() - 5_000)
      ),
      new RegExp(`^Error: SUREFIRE_MALFORMED_XML: ${fileName}$`)
    )
  })
}

const FAKE_OR_MALFORMED_SUREFIRE_REPORTS = {
  'comment-fake-suite': {
    source: [
      '<testsuite name="com.fxplatform.RealIT" tests="1" skipped="0" failures="0" errors="0">',
      '<!-- <testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite> -->',
      '</testsuite>'
    ].join('\n'),
    error: /^Error: SUREFIRE_MISSING_CLASS: ExpectedIT$/
  },
  'cdata-fake-suite': {
    source: [
      '<testsuite name="com.fxplatform.RealIT" tests="1" skipped="0" failures="0" errors="0">',
      '<![CDATA[<testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>]]>',
      '</testsuite>'
    ].join('\n'),
    error: /^Error: SUREFIRE_MISSING_CLASS: ExpectedIT$/
  },
  'valueless-attribute': {
    source: '<testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped failures="0" errors="0"></testsuite>',
    error: /^Error: SUREFIRE_MALFORMED_XML: TEST-valueless-attribute\.xml$/
  },
  'duplicate-attribute': {
    source: '<testsuite name="com.fxplatform.RealIT" name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    error: /^Error: SUREFIRE_MALFORMED_XML: TEST-duplicate-attribute\.xml$/
  }
}

for (const [scenario, fixture] of Object.entries(FAKE_OR_MALFORMED_SUREFIRE_REPORTS)) {
  test(`Surefire parser rejects fake or malformed suite: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-structural-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), fixture.source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['ExpectedIT'],
        new Date(Date.now() - 5_000)
      ),
      fixture.error
    )
  })
}

test('Surefire parser accepts one fresh exact suite for every requested class', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  writeSurefireSuite(directory, 'TEST-db.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT',
    tests: 2
  })
  writeSurefireSuite(directory, 'TEST-fill.xml', {
    name: 'com.fxplatform.Task5PostgresFullFillIT'
  })
  writeSurefireSuite(directory, 'TEST-near-match.xml', {
    name: 'com.fxplatform.PostgresDatabaseITExtra',
    failures: 1
  })

  const parsed = parseSurefireReports(
    directory,
    ['PostgresDatabaseIT', 'Task5PostgresFullFillIT'],
    startedAt
  )
  assert.equal(parsed.status, 'PASS')
  assert.deepEqual(parsed.suites.map(({ className }) => className), [
    'PostgresDatabaseIT',
    'Task5PostgresFullFillIT'
  ])
  assert.deepEqual(parsed.totals, { tests: 3, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire parser rejects missing, duplicate and stale exact suites', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-invalid-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)

  const missingDir = join(root, 'missing')
  writeSurefireSuite(missingDir, 'TEST-near.xml', {
    name: 'com.fxplatform.MissingITExtra'
  })
  assert.throws(
    () => parseSurefireReports(missingDir, ['MissingIT'], startedAt),
    /^Error: SUREFIRE_MISSING_CLASS: MissingIT$/
  )

  const duplicateDir = join(root, 'duplicate')
  writeSurefireSuite(duplicateDir, 'TEST-one.xml', { name: 'a.DuplicateIT' })
  writeSurefireSuite(duplicateDir, 'TEST-two.xml', { name: 'b.DuplicateIT' })
  assert.throws(
    () => parseSurefireReports(duplicateDir, ['DuplicateIT'], startedAt),
    /^Error: SUREFIRE_DUPLICATE_CLASS: DuplicateIT$/
  )

  const staleDir = join(root, 'stale')
  writeSurefireSuite(
    staleDir,
    'TEST-stale.xml',
    { name: 'com.fxplatform.StaleIT' },
    new Date(startedAt.getTime() - 1_000)
  )
  assert.throws(
    () => parseSurefireReports(staleDir, ['StaleIT'], startedAt),
    /^Error: SUREFIRE_STALE_REPORT: StaleIT$/
  )
})

test('Surefire parser rejects empty, skipped, failed or errored suites', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-counts-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const invalidCounters = [
    { tests: 0 },
    { skipped: 1 },
    { failures: 1 },
    { errors: 1 }
  ]

  for (const [index, counters] of invalidCounters.entries()) {
    const directory = join(root, String(index))
    writeSurefireSuite(directory, 'TEST-invalid.xml', {
      name: 'com.fxplatform.CounterIT',
      ...counters
    })
    assert.throws(
      () => parseSurefireReports(directory, ['CounterIT'], startedAt),
      /^Error: SUREFIRE_INVALID_SUITE: CounterIT$/
    )
  }
})

test('Surefire parser requires a unique non-empty expected class list', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-classes-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  writeSurefireSuite(directory, 'TEST-duplicate.xml', { name: 'com.fxplatform.DuplicateIT' })

  assert.throws(
    () => parseSurefireReports(directory, [], startedAt),
    /^Error: SUREFIRE_EXPECTED_CLASSES_REQUIRED$/
  )
  assert.throws(
    () => parseSurefireReports(directory, ['DuplicateIT', 'DuplicateIT'], startedAt),
    /^Error: SUREFIRE_EXPECTED_CLASSES_DUPLICATE: DuplicateIT$/
  )
})

test('verify-surefire CLI writes the parser gate contract atomically', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-cli-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gates', 'surefire.json')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-db.xml', { name: 'com.fxplatform.PostgresDatabaseIT' })
  writeSurefireSuite(reports, 'TEST-fill.xml', { name: 'com.fxplatform.Task5PostgresFullFillIT' })

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT,Task5PostgresFullFillIT',
    `--started-at=${startedAt}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  const gate = JSON.parse(readFileSync(output, 'utf8'))
  assert.equal(gate.status, 'PASS')
  assert.deepEqual(gate.expectedClasses, ['PostgresDatabaseIT', 'Task5PostgresFullFillIT'])
  assert.equal(existsSync(`${output}.tmp`), false)
})

test('verify-surefire CLI exits nonzero and persists parser rejection evidence', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-cli-fail-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gates', 'surefire.json')
  const startedAt = new Date()
  writeSurefireSuite(
    reports,
    'TEST-stale.xml',
    { name: 'com.fxplatform.PostgresDatabaseIT' },
    new Date(startedAt.getTime() - 1_000)
  )

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT',
    `--started-at=${startedAt.toISOString()}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'SUREFIRE_STALE_REPORT: PostgresDatabaseIT'
  })
})
