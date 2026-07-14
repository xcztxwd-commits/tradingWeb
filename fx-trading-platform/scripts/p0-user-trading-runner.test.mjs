import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  existsSync,
  linkSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  utimesSync,
  writeFileSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, sep } from 'node:path'
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

const persistenceDigest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

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

test('persistence rejects unsupported root evidence atomically', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-unsupported-root-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const baseline = '{"id":"AUTH-01","status":"PASS"}\n'
  writeFileSync(resultPath, baseline)

  for (const [scenario, value] of [
    ['undefined', undefined],
    ['function', () => {}],
    ['symbol', Symbol('root')],
    ['array', ['unsupported-root']]
  ]) {
    assert.throws(
      () => writeCaseResultAtomic(resultPath, value),
      TypeError,
      scenario
    )
    assert.equal(readFileSync(resultPath, 'utf8'), baseline, scenario)
    assert.deepEqual(readdirSync(directory), ['result.json'], scenario)
  }

  const ordinaryPath = join(directory, 'ordinary.json')
  writeCaseResultAtomic(ordinaryPath, {
    id: 'AUTH-02',
    nested: { kept: 'safe', discarded: () => {} },
    values: ['safe', undefined, Symbol('nested')]
  })
  assert.deepEqual(JSON.parse(readFileSync(ordinaryPath, 'utf8')), {
    id: 'AUTH-02',
    nested: { kept: persistenceDigest('safe') },
    values: [persistenceDigest('safe')]
  })
})

test('atomic writer uses a unique adjacent temp without touching a foreign fixed temp', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-unique-temp-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const foreignTempPath = `${resultPath}.tmp`
  const foreignMarker = 'FOREIGN_TEMP_OWNER_4f8c'
  writeFileSync(resultPath, '{"status":"OLD"}\n')
  writeFileSync(foreignTempPath, foreignMarker)

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'PASS' })

  assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), {
    id: 'AUTH-01',
    status: 'PASS'
  })
  assert.equal(existsSync(foreignTempPath), true)
  assert.equal(readFileSync(foreignTempPath, 'utf8'), foreignMarker)
  assert.deepEqual(readdirSync(directory).toSorted(), ['result.json', 'result.json.tmp'])
})

test('persistence serialization ignores toJSON hooks and non-JSON values', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-inert-json-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const rootPath = join(directory, 'root.json')
  const nestedPath = join(directory, 'nested.json')
  const rootMarker = 'ROOT_TO_JSON_BYPASS_1c42'
  const nestedMarker = 'NESTED_TO_JSON_BYPASS_7e31'
  let callbackCalls = 0

  writeCaseResultAtomic(rootPath, {
    id: 'AUTH-01',
    safe: 'root-safe',
    toJSON() {
      callbackCalls += 1
      return {
        rawRequest: { method: 'POST', url: `/api/orders/${rootMarker}`, body: rootMarker },
        password: rootMarker
      }
    }
  })
  writeCaseResultAtomic(nestedPath, {
    id: 'AUTH-02',
    safe: 'outer-safe',
    nested: {
      safe: 'inner-safe',
      toJSON() {
        callbackCalls += 1
        return {
          rawRequest: {
            method: 'POST',
            url: `/api/orders/${nestedMarker}`,
            body: nestedMarker
          },
          accessToken: nestedMarker
        }
      },
      functionValue() {},
      symbolValue: Symbol('nested-symbol'),
      undefinedValue: undefined
    },
    values: [
      'kept',
      () => {},
      Symbol('array-symbol'),
      undefined,
      { safe: 'deep-safe', functionValue() {}, symbolValue: Symbol('deep-symbol') }
    ],
    functionValue() {},
    symbolValue: Symbol('root-symbol'),
    undefinedValue: undefined
  })

  const rootSource = readFileSync(rootPath, 'utf8')
  const nestedSource = readFileSync(nestedPath, 'utf8')
  assert.equal(callbackCalls, 0)
  for (const source of [rootSource, nestedSource]) {
    assert.equal(source.includes(rootMarker), false)
    assert.equal(source.includes(nestedMarker), false)
    assert.equal(source.includes('toJSON'), false)
  }
  assert.deepEqual(JSON.parse(rootSource), {
    id: 'AUTH-01',
    safe: persistenceDigest('root-safe')
  })
  assert.deepEqual(JSON.parse(nestedSource), {
    id: 'AUTH-02',
    safe: persistenceDigest('outer-safe'),
    nested: { safe: persistenceDigest('inner-safe') },
    values: [persistenceDigest('kept'), { safe: persistenceDigest('deep-safe') }]
  })
})

for (const scenario of [
  'root-toJSON-getter',
  'nested-toJSON-getter',
  'root-ordinary-getter',
  'nested-ordinary-getter',
  'mutating-getter'
]) {
  test(`persistence input safety rejects accessors without executing: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-accessor-input-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    const marker = `FORBIDDEN_ACCESSOR_${scenario}`
    let calls = 0
    let input

    if (scenario === 'mutating-getter') {
      input = { id: 'AUTH-01' }
      Object.defineProperty(input, 'mutator', {
        enumerable: true,
        get() {
          calls += 1
          input.later = marker
          return 'safe'
        }
      })
      input.later = 'unchanged'
    } else {
      const nested = scenario.startsWith('nested')
      const target = {}
      Object.defineProperty(target, scenario.includes('toJSON') ? 'toJSON' : 'evidence', {
        enumerable: true,
        get() {
          calls += 1
          return marker
        }
      })
      input = nested ? { id: 'AUTH-01', nested: target } : { id: 'AUTH-01' }
      if (!nested) Object.defineProperties(input, Object.getOwnPropertyDescriptors(target))
    }

    writeCaseResultAtomic(resultPath, baseline)
    let failure
    try {
      writeCaseResultAtomic(resultPath, input)
    } catch (error) {
      failure = error
    }

    assert.equal(calls, 0)
    assert.ok(failure instanceof TypeError)
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
    assert.equal(readFileSync(resultPath, 'utf8').includes(marker), false)
    if (scenario === 'mutating-getter') assert.equal(input.later, 'unchanged')
  })
}

for (const scenario of ['root-proxy', 'nested-proxy']) {
  test(`persistence input safety rejects proxies without executing traps: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-proxy-input-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    let traps = 0
    const proxy = new Proxy({ evidence: 'safe' }, {
      get(target, key, receiver) {
        traps += 1
        return Reflect.get(target, key, receiver)
      },
      getOwnPropertyDescriptor(target, key) {
        traps += 1
        return Reflect.getOwnPropertyDescriptor(target, key)
      },
      getPrototypeOf(target) {
        traps += 1
        return Reflect.getPrototypeOf(target)
      },
      ownKeys(target) {
        traps += 1
        return Reflect.ownKeys(target)
      }
    })
    const input = scenario === 'root-proxy'
      ? proxy
      : { id: 'AUTH-01', nested: proxy }

    writeCaseResultAtomic(resultPath, baseline)
    let failure
    try {
      writeCaseResultAtomic(resultPath, input)
    } catch (error) {
      failure = error
    }

    assert.equal(traps, 0)
    assert.ok(failure instanceof TypeError)
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
  })
}

for (const [scenario, value] of [
  ['NaN', Number.NaN],
  ['positive-Infinity', Number.POSITIVE_INFINITY],
  ['negative-Infinity', Number.NEGATIVE_INFINITY]
]) {
  test(`persistence input safety rejects non-finite numbers atomically: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-non-finite-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    writeCaseResultAtomic(resultPath, baseline)

    assert.throws(
      () => writeCaseResultAtomic(resultPath, { id: 'AUTH-01', metrics: { value } }),
      TypeError
    )
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
  })
}

test('network evidence redacts secrets recursively without mutating live replay data', () => {
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
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
  assert.equal(redacted.headers['X-Trace-Id'], digest('safe-trace'))
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
    postData: JSON.stringify({ symbol: 'ETHUSDT', status: 'ACCEPTED' })
  })
  assert.equal(new URL(form.url).searchParams.get('token'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).get('password'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).has('email'), false)
  assert.deepEqual(JSON.parse(form.postData), { symbol: 'ETHUSDT', status: 'ACCEPTED' })

  const plainText = redactNetworkEntry({
    body: 'opaque credential words',
    postData: JSON.stringify({ symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' })
  })
  assert.equal('body' in plainText, false)
  assert.deepEqual(JSON.parse(plainText.postData), {
    symbol: 'SOLUSDT-PERP',
    sourceMode: 'LOCAL_SIMULATED'
  })
})

test('public and persistence share a context-aware safe evidence boundary', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-context-safe-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    nested: 'ITEM2_NESTED_CREDENTIAL_7b1a',
    array: 'ITEM2_ARRAY_CREDENTIAL_2c2b',
    failure: 'ITEM2_FAILURE_CREDENTIAL_3d3c',
    blocker: 'ITEM2_BLOCKER_CREDENTIAL_4e4d',
    checkpoint: 'ITEM2_CHECKPOINT_CREDENTIAL_5f5e',
    dynamicKey: 'ITEM2_DYNAMIC_KEY_CREDENTIAL_6a6f',
    rawEndpoint: 'ITEM2_RAW_ENDPOINT_CREDENTIAL_7b70',
    rawData: 'ITEM2_RAW_DATA_CREDENTIAL_8c81',
    rawResponse: 'ITEM2_RAW_RESPONSE_CREDENTIAL_9d92',
    urlUserinfo: 'ITEM2_URL_USERINFO_CREDENTIAL_ae03',
    urlQuery: 'ITEM2_URL_QUERY_CREDENTIAL_bf14',
    urlBody: 'ITEM2_URL_BODY_CREDENTIAL_c025',
    bearer: 'ITEM2_BEARER_CREDENTIAL_d136',
    cookie: 'ITEM2_COOKIE_CREDENTIAL_e247',
    session: 'ITEM2_SESSION_CREDENTIAL_f358',
    password: 'ITEM2_PASSWORD_CREDENTIAL_0469',
    token: 'ITEM2_TOKEN_CREDENTIAL_157a',
    jwt: 'eyJhbGciOiJIUzI1NiJ9.SVRFTTJfSldUX0NSRURFTlRJQUxfMjY4Yg.signature268b',
    base64: 'SVRFTTJfQkFTRTY0X0NSRURFTlRJQUxfMzc5Yw==',
    aws: 'AKIAITEM2CRED48AD90E',
    opaque: 'mQ9ITEM2opaqueCredential59be+/='
  }
  const usefulOpaque = 'ordinary-correlation-item2-6acf'
  const expectedOpaque = `sha256:${createHash('sha256').update(usefulOpaque).digest('hex')}`
  const evidence = {
    id: 'AUTH-01',
    status: 'PASS',
    method: 'GET',
    symbol: 'BTCUSDT',
    flag: true,
    count: 7,
    empty: null,
    note: usefulOpaque,
    ordinary: {
      nestedNote: `Bearer ${markers.nested}`,
      list: [`token=${markers.array}`]
    },
    failure: { message: `password=${markers.failure}` },
    blocker: { message: `session=${markers.blocker}` },
    checkpoint: { message: `Cookie: session=${markers.checkpoint}` },
    [`password-${markers.dynamicKey}`]: 'redact-me',
    rawRequestAlias: {
      method: 'POST',
      endpoint: `/api/${markers.rawEndpoint}`,
      data: { session: `session=${markers.rawData}` },
      response: { note: `Bearer ${markers.rawResponse}` }
    },
    url: {
      userinfo: `password=${markers.urlUserinfo}`,
      query: { token: markers.urlQuery },
      body: [`Bearer ${markers.urlBody}`]
    },
    credentialSamples: [
      `Bearer ${markers.bearer}`,
      `Cookie: session=${markers.cookie}`,
      `session=${markers.session}`,
      `password=${markers.password}`,
      `token=${markers.token}`,
      markers.jwt,
      markers.base64,
      markers.aws,
      markers.opaque
    ]
  }
  const original = structuredClone(evidence)

  const redacted = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)

  assert.deepEqual(evidence, original)
  const publicSource = JSON.stringify(redacted)
  const persistedSource = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) {
    assert.equal(publicSource.includes(marker), false, `public ${marker}`)
    assert.equal(persistedSource.includes(marker), false, `persisted ${marker}`)
  }
  const persisted = JSON.parse(persistedSource)
  for (const safe of [redacted, persisted]) {
    assert.equal(safe.id, 'AUTH-01')
    assert.equal(safe.status, 'PASS')
    assert.equal(safe.method, 'GET')
    assert.equal(safe.symbol, 'BTCUSDT')
    assert.equal(safe.flag, true)
    assert.equal(safe.count, 7)
    assert.equal(safe.empty, null)
    assert.equal(safe.note, expectedOpaque)
    assert.equal('url' in safe, false)
    assert.equal('rawRequestAlias' in safe, false)
  }
})

test('persistence preserves the real P0 typed evidence contract', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-domain-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const symbols = [
    'BTCUSDT',
    'ETHUSDT',
    'BNBUSDT',
    'SOLUSDT',
    'XRPUSDT',
    'BTCUSDT-PERP',
    'ETHUSDT-PERP',
    'BNBUSDT-PERP',
    'SOLUSDT-PERP',
    'XRPUSDT-PERP'
  ]
  const orderStatuses = [
    'RECEIVED',
    'VALIDATING',
    'ACCEPTED',
    'WORKING',
    'PARTIALLY_FILLED',
    'PENDING_ACTIVATION',
    'PENDING',
    'FILLED',
    'CANCEL_PENDING',
    'CANCELED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'FAILED'
  ]
  const tradingEventTypes = [
    'ORDER_ACCEPTED',
    'ORDER_PENDING',
    'ORDER_FILLED',
    'ORDER_CANCELED',
    'ORDER_REJECTED',
    'ORDER_EXPIRED',
    'ORDER_MODIFIED',
    'TRADE_CREATED',
    'BALANCE_UPDATED',
    'POSITION_UPDATED',
    'POSITION_CLOSED',
    'PROTECTION_CREATED',
    'PROTECTION_UPDATED',
    'PROTECTION_ACTIVATED',
    'PROTECTION_TRIGGERED',
    'PROTECTION_RESIZED',
    'PROTECTION_CANCELED',
    'PROTECTION_EXPIRED',
    'FUNDING_SETTLED',
    'MARGIN_ADJUSTED',
    'TRANSFER_COMPLETED',
    'LIQUIDATION',
    'DEMO_RESET',
    'MARKET_SOURCE_CHANGED'
  ]
  const ledgerEntryTypes = [
    'DEMO_INIT',
    'DEMO_RESET',
    'TRANSFER_IN',
    'TRANSFER_OUT',
    'DEMO_DEPOSIT',
    'ORDER_HOLD',
    'ORDER_RELEASE',
    'MARGIN_HOLD',
    'MARGIN_RELEASE',
    'TRADE_FEE',
    'TRADE_PNL',
    'FUNDING_FEE',
    'FINANCING',
    'CONVERSION_FEE',
    'LIQUIDATION_FEE',
    'BANKRUPTCY_SHORTFALL',
    'FORCED_CLOSE',
    'ADMIN_ADJUSTMENT',
    'CREDIT_AVAILABLE',
    'DEBIT_AVAILABLE',
    'LOCK_AVAILABLE',
    'RELEASE_LOCKED',
    'DEBIT_LOCKED'
  ]
  const snapshots = {
    catalog: symbols.map((symbol) => ({ symbol })),
    market: {
      symbol: 'ETHUSDT',
      bid: '3450.10',
      ask: '3450.20',
      last: '3450.15',
      mark: '3450.12',
      index: '3450.08',
      provider: 'binance',
      providerCode: 'binance',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-14T00:00:00.000Z',
      expiresAt: '2026-07-14T00:00:05.000Z',
      stale: false
    },
    order: {
      id: '11111111-1111-4111-8111-111111111111',
      clientOrderId: '22222222-2222-4222-8222-222222222222',
      symbol: 'BTCUSDT',
      status: 'FILLED',
      origin: 'USER',
      side: 'BUY',
      type: 'LIMIT',
      orderType: 'LIMIT',
      quantity: '0.25000000',
      unit: 'BASE',
      quantityUnit: 'BASE',
      baseQuantity: '0.25000000',
      fill: '0.25000000',
      filledQuantity: '0.25000000',
      price: '68000.50',
      fee: '8.50006250',
      feeAsset: 'USDT',
      liquidityRole: 'TAKER',
      realizedPnl: '12.25',
      reduceOnly: false,
      version: 3,
      createdAt: '2026-07-14T00:01:00.000Z'
    },
    trade: {
      id: '33333333-3333-4333-8333-333333333333',
      orderId: '11111111-1111-4111-8111-111111111111',
      symbol: 'BTCUSDT-PERP',
      side: 'SELL',
      price: '68125.75',
      quantity: '2',
      fee: '0.75',
      feeAsset: 'USDT',
      liquidityRole: 'MAKER',
      realizedPnl: '-3.50',
      providerCode: 'binance-usdm',
      sourceMode: 'PUBLIC_EXTERNAL',
      executedAt: '2026-07-14T00:02:00.000Z'
    },
    position: {
      id: '44444444-4444-4444-8444-444444444444',
      symbol: 'SOLUSDT-PERP',
      slot: 1,
      side: 'LONG',
      positionSide: 'LONG',
      quantity: '12.5',
      entry: '145.20',
      mark: '146.10',
      upl: '11.25',
      realizedPnl: '3.75',
      margin: '90.75',
      leverage: 20,
      maintenance: '18.15',
      liquidation: '132.40',
      marginMode: 'ISOLATED',
      status: 'OPEN',
      version: 9,
      openedAt: '2026-07-14T00:03:00.000Z'
    },
    account: {
      balance: '100000.00',
      equity: '100015.00',
      usedMargin: '90.75',
      freeMargin: '99924.25',
      available: '99924.25',
      locked: '75.00',
      total: '100000.00'
    },
    wallet: {
      walletType: 'USDT_PERP',
      asset: 'USDT',
      available: '9975.00',
      locked: '25.00',
      total: '10000.00'
    },
    ledger: {
      operation: 'ORDER_HOLD',
      operationType: 'ORDER_HOLD',
      entryType: 'TRADE_FEE',
      eventType: 'ORDER_FILLED',
      referenceType: 'ORDER',
      amount: '-8.50006250',
      referenceId: '11111111-1111-4111-8111-111111111111',
      balanceAfter: '99991.49993750',
      resourceType: 'ORDER',
      resourceId: '11111111-1111-4111-8111-111111111111',
      createdAt: '2026-07-14T00:04:00.000Z'
    },
    enums: {
      orderStatuses: orderStatuses.map((status) => ({ status })),
      orderTypes: ['MARKET', 'LIMIT', 'STOP', 'STOP_MARKET'].map((orderType) => ({ orderType })),
      sides: ['BUY', 'SELL'].map((side) => ({ side })),
      positionSides: ['BOTH', 'LONG', 'SHORT'].map((positionSide) => ({ positionSide })),
      origins: [
        'USER',
        'PROTECTIVE',
        'LIQUIDATION',
        'ADMIN_FORCE_CLOSE',
        'BATCH_CLOSE',
        'OCO'
      ].map((origin) => ({ origin })),
      units: ['BASE', 'QUOTE', 'CONTRACTS'].map((quantityUnit) => ({ quantityUnit })),
      marginModes: ['CASH', 'CROSS', 'ISOLATED'].map((marginMode) => ({ marginMode })),
      liquidityRoles: ['MAKER', 'TAKER'].map((liquidityRole) => ({ liquidityRole })),
      sourceModes: ['PUBLIC_EXTERNAL', 'LOCAL_SIMULATED'].map((sourceMode) => ({ sourceMode })),
      providers: [
        'binance',
        'okx',
        'local-spot',
        'binance-usdm',
        'okx-swap',
        'local-perp'
      ].map((providerCode) => ({ providerCode })),
      eventTypes: tradingEventTypes.map((eventType) => ({ eventType })),
      ledgerEntryTypes: ledgerEntryTypes.map((entryType) => ({ entryType }))
    }
  }
  const marker = 'ITEM3_TYPED_FIELD_CREDENTIAL_7bd1'
  const credentialFields = [
    'symbol',
    'bid',
    'provider',
    'providerCode',
    'sourceMode',
    'asOf',
    'status',
    'origin',
    'side',
    'orderType',
    'quantity',
    'quantityUnit',
    'feeAsset',
    'liquidityRole',
    'positionSide',
    'marginMode',
    'walletType',
    'asset',
    'operationType',
    'entryType',
    'eventType',
    'referenceType',
    'resourceType',
    'createdAt'
  ]
  const result = {
    id: 'AUTH-01',
    status: 'PASS',
    snapshots,
    rejected: Object.fromEntries(
      credentialFields.map((field) => [field, `Bearer ${marker}-${field}`])
    ),
    invalidTypes: {
      bid: '01.0',
      quantity: '1e3',
      leverage: 20.5,
      version: -1,
      stale: 'false',
      asOf: '2026-07-14T08:00:00+08:00'
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.snapshots, snapshots)
  assert.deepEqual(persisted.rejected, {})
  assert.deepEqual(persisted.invalidTypes, {})
})

test('network redaction sanitizes URL userinfo and explicit header representations', () => {
  const unknownMarker = 'UNKNOWN_HEADER_STRUCTURE_8ad4'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const entry = {
    url: 'https://trader:plain-password@example.invalid/orders?token=query-secret&symbol=BTCUSDT#access_token=fragment-secret&tab=open',
    headers: {
      Authorization: 'Bearer object-secret',
      Cookie: 'session=object-secret',
      'X-Trace-Id': 'safe-trace'
    },
    responseHeaders: [
      { name: 'Set-Cookie', value: 'session=name-value-secret' },
      { name: 'Content-Type', value: 'application/json' },
      ['Authorization', 'Bearer tuple-secret'],
      ['X-Request-Id', 'safe-request'],
      'Cookie: string-secret',
      'X-Region: safe-region',
      { label: 'Authorization', content: unknownMarker },
      ['Set-Cookie', unknownMarker, 'unexpected'],
      42
    ]
  }
  const original = JSON.parse(JSON.stringify(entry))

  const redacted = redactNetworkEntry(entry)

  assert.deepEqual(entry, original)
  const url = new URL(redacted.url)
  assert.equal(url.username, '')
  assert.equal(url.password, '')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  assert.equal(url.searchParams.get('symbol'), 'BTCUSDT')
  assert.equal(url.hash, '')
  assert.deepEqual(redacted.headers, {
    Authorization: '[REDACTED]',
    Cookie: '[REDACTED]',
    'X-Trace-Id': digest('safe-trace')
  })
  assert.deepEqual(redacted.responseHeaders, [
    { name: 'Set-Cookie', value: '[REDACTED]' },
    { name: 'Content-Type', value: 'application/json' },
    ['Authorization', '[REDACTED]'],
    ['X-Request-Id', digest('safe-request')],
    'Cookie: [REDACTED]',
    `X-Region: ${digest('safe-region')}`
  ])
  assert.equal(JSON.stringify(redacted).includes(unknownMarker), false)
})

test('header evidence hashes bounded unknown values and rejects credential-shaped values', () => {
  const opaqueHeader = 'opaque-marker'
  const credentialHeader = 'token=HEADER_CREDENTIAL_SECRET_4f8a'
  const accessKeySubtype = 'application/AKIAIOSFODNN7EXAMPLE'
  const secretCharset = `application/json; charset=${credentialHeader}`
  const entry = {
    headers: {
      'X-Node': opaqueHeader,
      'X-Region': credentialHeader,
      'Content-Type': accessKeySubtype
    },
    mimeType: secretCharset,
    responseHeaders: [
      ['X-Node', 'safe-node'],
      ['X-Region', 'safe-region'],
      ['Content-Type', 'application/json']
    ]
  }
  const original = structuredClone(entry)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  const redacted = redactNetworkEntry(entry)

  assert.deepEqual(entry, original)
  const serialized = JSON.stringify(redacted)
  assert.equal(serialized.includes(opaqueHeader), false)
  assert.equal(serialized.includes(credentialHeader), false)
  assert.equal(serialized.includes(accessKeySubtype), false)
  assert.deepEqual(redacted, {
    headers: { 'X-Node': digest(opaqueHeader) },
    responseHeaders: [
      ['X-Node', digest('safe-node')],
      ['X-Region', digest('safe-region')],
      ['Content-Type', 'application/json']
    ]
  })
})

test('persistence drops raw header text blobs without mutating safe network metadata', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-header-text-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const requestMarker = 'RAW_AUTHORIZATION_HEADER_TEXT_f271'
  const responseMarker = 'RAW_SET_COOKIE_HEADER_TEXT_88d4'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?symbol=BTCUSDT',
      status: 200,
      mimeType: 'application/json',
      headersText: `Authorization: Bearer ${requestMarker}\r\nX-Trace-Id: safe-trace`,
      responseHeadersText: `Set-Cookie: session=${responseMarker}\r\nContent-Type: application/json`,
      headers: { 'X-Trace-Id': 'safe-trace' }
    }]
  }
  const original = JSON.parse(JSON.stringify(result))

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(requestMarker), false)
  assert.equal(source.includes(responseMarker), false)
  const evidence = JSON.parse(source).networkEvidence[0]
  assert.equal('headersText' in evidence, false)
  assert.equal('responseHeadersText' in evidence, false)
  assert.deepEqual(evidence, {
    method: 'GET',
    url: '/api/orders?symbol=BTCUSDT',
    status: 200,
    mimeType: 'application/json',
    headers: { 'X-Trace-Id': digest('safe-trace') }
  })
})

test('header evidence rejects control injection and raw aliases', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-header-injection-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    map: 'INJECTED_MAP_AUTH_5f2a',
    tuple: 'INJECTED_TUPLE_AUTH_91c4',
    object: 'INJECTED_OBJECT_COOKIE_3d7b',
    string: 'INJECTED_STRING_AUTH_8a6e',
    fieldName: 'INVALID_FIELD_NAME_2e5c',
    raw: 'RAW_RESPONSE_HEADERS_f8b1',
    text: 'RAW_HEADER_TEXT_14d9',
    blob: 'RAW_HEADER_BLOB_7a30',
    stringAlias: 'RAW_HEADERS_STRING_6c42',
    block: 'RAW_HEADERS_BLOCK_0bd7'
  }
  const result = {
    id: 'AUTH-01',
    networkEvidence: [{
      headers: {
        'X-Trace-Id': 'safe-trace',
        Authorization: 'Bearer ordinary-secret',
        'X-Map': `safe\r\nAuthorization: Bearer ${markers.map}`,
        'Bad Header': markers.fieldName
      },
      requestHeaders: { 'X-Request-Id': 'safe-request' },
      responseHeaders: [
        ['X-Tuple', `safe\r\nAuthorization: Bearer ${markers.tuple}`],
        { name: 'X-Object', value: `safe\nSet-Cookie: session=${markers.object}` },
        `X-String: safe\u0000${markers.string}`,
        ['Bad Header', markers.fieldName],
        { name: 'Content-Type', value: 'application/json' },
        ['X-Region', 'safe-region'],
        'X-Node: safe-node'
      ],
      responseHeadersRaw: `Authorization: Bearer ${markers.raw}`,
      headerText: `Set-Cookie: session=${markers.text}`,
      responseHeaderBlob: `Authorization: Bearer ${markers.blob}`,
      headersString: `Set-Cookie: session=${markers.stringAlias}`,
      headersBlock: `Authorization: Bearer ${markers.block}`
    }]
  }
  const original = structuredClone(result)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  assert.deepEqual(JSON.parse(source).networkEvidence[0], {
    headers: {
      'X-Trace-Id': digest('safe-trace'),
      Authorization: '[REDACTED]'
    },
    requestHeaders: { 'X-Request-Id': digest('safe-request') },
    responseHeaders: [
      { name: 'Content-Type', value: 'application/json' },
      ['X-Region', digest('safe-region')],
      `X-Node: ${digest('safe-node')}`
    ]
  })
})

test('persistence routes header and body evidence by positive schema', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-positive-routing-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    headerLines: 'RAW_HEADER_LINES_2f8a',
    headersList: 'RAW_HEADERS_LIST_47bd',
    extraHeaders: 'RAW_EXTRA_HEADERS_6c31',
    headersDump: 'RAW_HEADERS_DUMP_9a52',
    responseHeadersRaw: 'RAW_RESPONSE_HEADERS_f1e4',
    bodyAlias: 'RAW_BODY_ALIAS_b4d7',
    payloadAlias: 'RAW_PAYLOAD_ALIAS_83ac'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    headerLines: `Authorization: Bearer ${markers.headerLines}`,
    headersList: [['Authorization', `Bearer ${markers.headersList}`]],
    extraHeaders: { Authorization: `Bearer ${markers.extraHeaders}` },
    headersDump: { name: 'Set-Cookie', value: `session=${markers.headersDump}` },
    responseHeadersRaw: `Set-Cookie: session=${markers.responseHeadersRaw}`,
    bodyDump: `password=${markers.bodyAlias}`,
    responseBodyLines: JSON.stringify({ token: markers.bodyAlias }),
    payloadList: [markers.payloadAlias],
    extraPayload: { password: markers.payloadAlias },
    headers: { Authorization: 'Bearer request-secret', 'X-Trace-Id': 'safe-trace' },
    requestHeaders: [['X-Request-Id', 'safe-request']],
    responseHeaders: [{ name: 'Set-Cookie', value: 'session=response-secret' }],
    Bo_Dy: JSON.stringify({
      password: 'request-json-secret',
      symbol: 'ETHUSDT',
      status: 'ACCEPTED'
    }),
    POST_DATA: 'password=request-form-secret&symbol=BNBUSDT',
    response_body: JSON.stringify({
      accessToken: 'response-json-secret',
      symbol: 'SOLUSDT-PERP',
      sourceMode: 'LOCAL_SIMULATED'
    }),
    'Response-Payload': 'password=response-form-secret&status=FILLED'
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  for (const alias of [
    'headerLines',
    'headersList',
    'extraHeaders',
    'headersDump',
    'responseHeadersRaw',
    'bodyDump',
    'responseBodyLines',
    'payloadList',
    'extraPayload'
  ]) assert.equal(alias in persisted, false, alias)
  assert.deepEqual(persisted.headers, {
    Authorization: '[REDACTED]',
    'X-Trace-Id': digest('safe-trace')
  })
  assert.deepEqual(persisted.requestHeaders, [['X-Request-Id', digest('safe-request')]])
  assert.deepEqual(persisted.responseHeaders, [{ name: 'Set-Cookie', value: '[REDACTED]' }])
  assert.deepEqual(JSON.parse(persisted.Bo_Dy), {
    password: '[REDACTED]',
    symbol: 'ETHUSDT',
    status: 'ACCEPTED'
  })
  assert.equal(new URLSearchParams(persisted.POST_DATA).get('password'), '[REDACTED]')
  assert.equal(new URLSearchParams(persisted.POST_DATA).get('symbol'), 'BNBUSDT')
  assert.deepEqual(JSON.parse(persisted.response_body), {
    accessToken: '[REDACTED]',
    symbol: 'SOLUSDT-PERP',
    sourceMode: 'LOCAL_SIMULATED'
  })
  assert.equal(
    new URLSearchParams(persisted['Response-Payload']).get('password'),
    '[REDACTED]'
  )
  assert.equal(new URLSearchParams(persisted['Response-Payload']).get('status'), 'FILLED')
})

test('persistence applies positive header body URL and network schemas', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-positive-network-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    requestData: 'REQUEST_BODY_UNKNOWN_DATA_Review8_a1',
    responseData: 'RESPONSE_BODY_UNKNOWN_DATA_Review8_b2',
    formData: 'FORM_UNKNOWN_DATA_Review8_c3',
    unknownHeader: 'UNKNOWN_X_SESSION_HEADER_Review8_d4',
    correlationHeader: 'Opaque+HeaderCorrelation==Review8E5',
    userinfo: 'URL_USERINFO_CREDENTIAL_Review8_f6',
    path: 'URL_PATH_CREDENTIAL_Review8_g7',
    query: 'URL_UNKNOWN_QUERY_Review8_h8',
    tokenQuery: 'URL_TOKEN_QUERY_Review8_i9',
    fragment: 'URL_FRAGMENT_CREDENTIAL_Review8_j0',
    networkAlias: 'NETWORK_UNKNOWN_ALIAS_Review8_k1',
    rawL8: 'RAW_L8_ENDPOINT_DATA_Review8_l2'
  }
  const result = {
    id: 'AUTH-01',
    body: JSON.stringify({
      data: markers.requestData,
      symbol: 'BNBUSDT',
      status: 'ACCEPTED',
      amount: '10',
      password: 'request-password-secret'
    }),
    arbitraryEvidence: [{
      method: 'POST',
      endpoint: '/api/orders',
      data: markers.rawL8
    }],
    networkEvidence: [{
      method: 'POST',
      url: `https://trader:${markers.userinfo}@example.invalid/api/orders/${markers.path}?symbol=BTCUSDT&sessionHint=${markers.query}&token=${markers.tokenQuery}#${markers.fragment}`,
      status: 200,
      mimeType: 'application/json',
      headers: {
        Authorization: 'Bearer request-secret',
        'Content-Type': 'application/json',
        'X-Trace-Id': markers.correlationHeader,
        'X-Session': markers.unknownHeader
      },
      responseHeaders: [
        ['X-Request-Id', markers.correlationHeader],
        ['Content-Type', 'application/json'],
        ['X-Session', markers.unknownHeader]
      ],
      responseBody: JSON.stringify({
        data: markers.responseData,
        market: {
          symbol: 'XRPUSDT',
          sourceMode: 'PUBLIC_EXTERNAL',
          unknown: markers.responseData
        },
        status: 'FILLED',
        accessToken: 'response-token-secret'
      }),
      responsePayload: new URLSearchParams({
        data: markers.formData,
        symbol: 'XRPUSDT',
        status: 'FILLED',
        password: 'form-password-secret'
      }).toString(),
      requestData: markers.networkAlias,
      endpoint: `/api/orders/${markers.networkAlias}`,
      data: markers.networkAlias,
      unknownEvidence: markers.networkAlias,
      response: { status: 201, data: markers.networkAlias }
    }]
  }
  const original = structuredClone(result)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  assert.deepEqual(JSON.parse(persisted.body), {
    symbol: 'BNBUSDT',
    status: 'ACCEPTED',
    amount: '10',
    password: '[REDACTED]'
  })
  assert.deepEqual(persisted.arbitraryEvidence, [])
  const network = persisted.networkEvidence[0]
  assert.deepEqual(Object.keys(network).sort(), [
    'headers',
    'method',
    'mimeType',
    'response',
    'responseBody',
    'responseHeaders',
    'responsePayload',
    'status',
    'url'
  ])
  const url = new URL(network.url)
  assert.equal(url.username, '')
  assert.equal(url.password, '')
  assert.equal(url.hash, '')
  assert.equal(url.pathname.startsWith('/api/orders/'), true)
  assert.equal(url.pathname.includes(markers.path), false)
  assert.equal(url.searchParams.get('symbol'), 'BTCUSDT')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  assert.equal(url.searchParams.has('sessionHint'), false)
  assert.deepEqual(network.headers, {
    Authorization: '[REDACTED]',
    'Content-Type': 'application/json',
    'X-Trace-Id': digest(markers.correlationHeader)
  })
  assert.deepEqual(network.responseHeaders, [
    ['X-Request-Id', digest(markers.correlationHeader)],
    ['Content-Type', 'application/json']
  ])
  assert.deepEqual(JSON.parse(network.responseBody), {
    market: { symbol: 'XRPUSDT', sourceMode: 'PUBLIC_EXTERNAL' },
    status: 'FILLED',
    accessToken: '[REDACTED]'
  })
  const form = new URLSearchParams(network.responsePayload)
  assert.equal(form.has('data'), false)
  assert.equal(form.get('symbol'), 'XRPUSDT')
  assert.equal(form.get('status'), 'FILLED')
  assert.equal(form.get('password'), '[REDACTED]')
  assert.deepEqual(network.response, { status: 201 })
})

test('body and URL schemas accept only contract-owned raw strings', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-body-url-public-values-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const credentialPhrase = 'secret-token'
  const credentialEmail = `${accessKey}@credential.invalid`
  const result = {
    id: 'AUTH-01',
    checks: [{
      body: JSON.stringify({
        symbol: accessKey,
        safe: credentialPhrase,
        state: credentialPhrase,
        displayName: accessKey,
        email: credentialEmail
      })
    }, {
      body: JSON.stringify({
        symbol: 'ETHUSDT-PERP',
        status: 'FILLED',
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '1.25',
        sourceMode: 'PUBLIC_EXTERNAL'
      })
    }],
    networkEvidence: [{
      method: 'GET',
      url: `https://${accessKey}.example.invalid/api/orders?symbol=${accessKey}`,
      status: 200
    }, {
      method: 'GET',
      url: 'https://example.invalid/api/orders?symbol=BTCUSDT',
      status: 200
    }]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(accessKey), false)
  assert.equal(source.includes(credentialPhrase), false)
  assert.equal(source.includes(credentialEmail), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(JSON.parse(persisted.checks[0].body), {})
  assert.deepEqual(JSON.parse(persisted.checks[1].body), {
    symbol: 'ETHUSDT-PERP',
    status: 'FILLED',
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '1.25',
    sourceMode: 'PUBLIC_EXTERNAL'
  })
  const unsafeUrl = new URL(persisted.networkEvidence[0].url)
  assert.equal(unsafeUrl.hostname, 'redacted.invalid')
  assert.equal(unsafeUrl.searchParams.has('symbol'), false)
  const safeUrl = new URL(persisted.networkEvidence[1].url)
  assert.equal(safeUrl.hostname, 'example.invalid')
  assert.equal(safeUrl.searchParams.get('symbol'), 'BTCUSDT')
})

test('network evidence accepts only typed plain entry objects', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-network-entry-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const arrayPath = join(directory, 'array.json')
  const scalarPath = join(directory, 'scalar.json')
  const markers = {
    scalarEntry: 'NETWORK_SCALAR_ENTRY_Review8_m3',
    arrayEntry: 'NETWORK_ARRAY_ENTRY_Review8_n4',
    scalarResponse: 'NETWORK_SCALAR_RESPONSE_Review8_o5',
    topLevel: 'NETWORK_TOP_LEVEL_SCALAR_Review8_p6'
  }
  const arrayResult = {
    id: 'AUTH-01',
    networkEvidence: [
      markers.scalarEntry,
      [markers.arrayEntry],
      {
        method: 'GET',
        url: '/api/orders',
        status: 200,
        response: markers.scalarResponse
      }
    ]
  }
  const scalarResult = {
    id: 'AUTH-01',
    networkEvidence: markers.topLevel
  }
  const originalArray = structuredClone(arrayResult)
  const originalScalar = structuredClone(scalarResult)

  writeCaseResultAtomic(arrayPath, arrayResult)
  writeCaseResultAtomic(scalarPath, scalarResult)

  assert.deepEqual(arrayResult, originalArray)
  assert.deepEqual(scalarResult, originalScalar)
  const arraySource = readFileSync(arrayPath, 'utf8')
  const scalarSource = readFileSync(scalarPath, 'utf8')
  for (const marker of Object.values(markers)) {
    assert.equal(arraySource.includes(marker), false, marker)
    assert.equal(scalarSource.includes(marker), false, marker)
  }
  assert.deepEqual(JSON.parse(arraySource).networkEvidence, [{
    method: 'GET',
    url: '/api/orders',
    status: 200
  }])
  assert.deepEqual(JSON.parse(scalarSource).networkEvidence, [])
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
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

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
      fingerprint: `sha256:${'1'.repeat(64)}`,
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
    id: digest('replay-1'),
    referenceId: digest('order-1'),
    fingerprint: `sha256:${'1'.repeat(64)}`,
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
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

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
      fingerprint: `sha256:${'2'.repeat(64)}`,
      requestFingerprint: `sha256:${'3'.repeat(64)}`,
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
    id: digest('replay-safe'),
    referenceId: digest('order-safe'),
    requestId: digest('request-safe'),
    clientOrderId: digest('client-safe'),
    fingerprint: `sha256:${'2'.repeat(64)}`,
    requestFingerprint: `sha256:${'3'.repeat(64)}`,
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.networkEvidence[0].method, 'GET')
  assert.equal(persisted.networkEvidence[0].status, 200)
  assert.equal(
    new URL(persisted.networkEvidence[0].url, 'https://contract.invalid').searchParams.get('symbol'),
    'BTCUSDT'
  )
})

test('persistence drops structurally raw HTTP requests outside sanitized network evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-structural-request-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    replay: 'RAW_REPLAY_PAYLOAD_51ad',
    arbitrary: 'ARBITRARY_HTTP_CONTAINER_18f7',
    networkBody: 'NETWORK_REQUEST_BODY_a0c2',
    networkPostData: 'NETWORK_REQUEST_POST_DATA_3e64',
    networkPayload: 'NETWORK_REQUEST_PAYLOAD_972b'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    rawReplayPayload: {
      method: 'POST',
      url: `/api/orders/${markers.replay}`,
      body: markers.replay
    },
    arbitraryEvidence: [
      { id: 'safe-observation', status: 'OBSERVED' },
      {
        method: 'PUT',
        url: `/api/orders/${markers.arbitrary}`,
        headers: { 'X-Marker': markers.arbitrary },
        payload: markers.arbitrary
      }
    ],
    networkEvidence: [{
      method: 'POST',
      url: '/api/orders?token=network-only-secret&symbol=BTCUSDT',
      status: 201,
      body: markers.networkBody,
      postData: markers.networkPostData,
      payload: markers.networkPayload,
      response: { status: 201 }
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  assert.equal(serialized.includes('"rawReplayPayload"'), false)
  const persisted = JSON.parse(serialized)
  assert.deepEqual(persisted.arbitraryEvidence, [
    { id: digest('safe-observation'), status: 'OBSERVED' }
  ])
  assert.deepEqual(persisted.networkEvidence, [{
    method: 'POST',
    url: '/api/orders?token=%5BREDACTED%5D&symbol=BTCUSDT',
    status: 201,
    response: { status: 201 }
  }])
})

test('persistence keeps only scalar user action requestRef evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-request-ref-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const requestRef = 'request-ref-safe-78b1'
  const attackMarker = 'REQUEST_REF_OBJECT_ATTACK_d497'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    userActions: [
      { action: 'submit-order', requestRef },
      {
        action: 'non-scalar-attack',
        requestRef: {
          method: 'POST',
          url: `/api/orders/${attackMarker}`,
          body: attackMarker
        }
      }
    ]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  assert.equal(serialized.includes(attackMarker), false)
  assert.deepEqual(JSON.parse(serialized).userActions, [
    { action: digest('submit-order'), requestRef: digest(requestRef) },
    { action: digest('non-scalar-attack') }
  ])
})

test('persistence sanitizes scalar reference and replay fields and drops standalone payloads', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-scalar-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    requestRef: 'UNSAFE_REQUEST_REF_91ac',
    requestId: 'UNSAFE_REQUEST_ID_77e2',
    requestFingerprint: 'UNSAFE_REQUEST_FINGERPRINT_a62d',
    fingerprint: 'UNSAFE_FINGERPRINT_04b8',
    replayId: 'UNSAFE_REPLAY_ID_103a',
    replayCaseId: 'UNSAFE_REPLAY_CASE_ID_82cf',
    replaySubrunId: 'UNSAFE_REPLAY_SUBRUN_ID_11f0',
    replayReferenceId: 'UNSAFE_REPLAY_REFERENCE_ID_43b1',
    replayRequestId: 'UNSAFE_REPLAY_REQUEST_ID_5d9e',
    replayClientOrderId: 'UNSAFE_REPLAY_CLIENT_ORDER_ID_b447',
    replayFingerprint: 'UNSAFE_REPLAY_FINGERPRINT_3f20',
    replayRequestFingerprint: 'UNSAFE_REPLAY_REQUEST_FINGERPRINT_8c62',
    replayStatus: 'UNSAFE_REPLAY_STATUS_54d3',
    replayErrorCode: 'UNSAFE_REPLAY_ERROR_CODE_65ae',
    outcomeStatus: 'UNSAFE_OUTCOME_STATUS_706c',
    outcomeErrorCode: 'UNSAFE_OUTCOME_ERROR_CODE_0f19',
    payload: 'UNSAFE_STANDALONE_PAYLOAD_9f5c',
    requestPayload: 'UNSAFE_STANDALONE_REQUEST_PAYLOAD_f43b'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const unsafeReplay = {
    id: `Bearer ${markers.replayId}`,
    caseId: `session=${markers.replayCaseId}`,
    subrunId: `password=${markers.replaySubrunId}`,
    referenceId: `Cookie: ${markers.replayReferenceId}`,
    requestId: `request\r\n${markers.replayRequestId}`,
    clientOrderId: `client\u0000${markers.replayClientOrderId}`,
    fingerprint: `token=${markers.replayFingerprint}`,
    requestFingerprint: `secret=${markers.replayRequestFingerprint}`,
    status: `PASS ${markers.replayStatus}`,
    errorCode: `ERROR=${markers.replayErrorCode}`,
    outcome: {
      status: `FAIL\r\n${markers.outcomeStatus}`,
      errorCode: `PASSWORD=${markers.outcomeErrorCode}`
    }
  }
  const safeReplay = {
    id: 'replay-1',
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: 'order/123',
    requestId: '1234.56',
    clientOrderId: 'client_order-1',
    fingerprint: `sha256:${'a'.repeat(64)}`,
    requestFingerprint: `sha256:${'b'.repeat(64)}`,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }
  const persistedSafeReplay = {
    ...safeReplay,
    id: digest(safeReplay.id),
    referenceId: digest(safeReplay.referenceId),
    clientOrderId: digest(safeReplay.clientOrderId)
  }
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'unsafe-ref', requestRef: `Bearer ${markers.requestRef}` },
      { action: 'safe-ref', requestRef: 'request-ref-safe-78b1' },
      { action: 'unsafe-id', requestId: `Cookie: ${markers.requestId}` },
      { action: 'safe-id', requestId: '1234.56' },
      {
        action: 'unsafe-request-fingerprint',
        requestFingerprint: `password=${markers.requestFingerprint}`
      },
      { action: 'safe-request-fingerprint', requestFingerprint: `sha256:${'c'.repeat(64)}` },
      { action: 'unsafe-fingerprint', fingerprint: `token=${markers.fingerprint}` },
      { action: 'safe-fingerprint', fingerprint: `sha256:${'d'.repeat(64)}` }
    ],
    replayProbes: [unsafeReplay, safeReplay],
    payload: `password=${markers.payload}`,
    requestPayload: `secret=${markers.requestPayload}`
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('unsafe-ref') },
    { action: digest('safe-ref'), requestRef: digest('request-ref-safe-78b1') },
    { action: digest('unsafe-id') },
    { action: digest('safe-id'), requestId: '1234.56' },
    {
      action: digest('unsafe-request-fingerprint'),
      requestFingerprint: digest(`password=${markers.requestFingerprint}`)
    },
    {
      action: digest('safe-request-fingerprint'),
      requestFingerprint: `sha256:${'c'.repeat(64)}`
    },
    {
      action: digest('unsafe-fingerprint'),
      fingerprint: digest(`token=${markers.fingerprint}`)
    },
    { action: digest('safe-fingerprint'), fingerprint: `sha256:${'d'.repeat(64)}` }
  ])
  assert.deepEqual(persisted.replayProbes, [{
    fingerprint: digest(`token=${markers.replayFingerprint}`),
    requestFingerprint: digest(`secret=${markers.replayRequestFingerprint}`)
  }, persistedSafeReplay])
  assert.equal('payload' in persisted, false)
  assert.equal('requestPayload' in persisted, false)
})

test('persistence hashes opaque references with field-specific rules', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-field-specific-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc2X0pXVF9DUkVERU5USUFMIn0.signaturepart'
  const base64url = 'QWxhZGRpbk9wYXF1ZVJldmlldzZCYXNlNjRVcmxDcmVkZW50aWFsX01hcmtlcl8xMjM0NTY3ODkw'
  const opaque = 'OpaqueCredentialA7f4C9e2B6d8'
  const canonicalFingerprint = `sha256:${'e'.repeat(64)}`
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'jwt-ref', requestRef: jwt },
      { action: 'base64url-id', requestId: base64url },
      { action: 'opaque-ref', requestRef: opaque },
      { action: 'opaque-id', requestId: opaque },
      { action: 'jwt-fingerprint', requestFingerprint: jwt },
      { action: 'canonical-fingerprint', fingerprint: canonicalFingerprint },
      { action: 'cdp-request', requestId: '1234.56' },
      { action: 'uuid-request', requestRef: uuid },
      { action: 'client-request', requestRef: 'client_order-42' }
    ],
    replayProbes: [
      {
        id: jwt,
        referenceId: base64url,
        fingerprint: opaque,
        status: opaque,
        errorCode: jwt,
        outcome: { status: jwt, errorCode: base64url }
      },
      {
        id: 'replay-1',
        caseId: 'AUTH-01',
        subrunId: 'desktop-ui-core',
        referenceId: 'order/123',
        requestId: '1234.56',
        clientOrderId: uuid,
        fingerprint: canonicalFingerprint,
        requestFingerprint: canonicalFingerprint,
        status: 'REJECTED',
        errorCode: 'REQUEST_CONFLICT',
        outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
      }
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const credential of [jwt, base64url, opaque]) {
    assert.equal(source.includes(credential), false, credential)
  }
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('jwt-ref'), requestRef: digest(jwt) },
    { action: digest('base64url-id'), requestId: digest(base64url) },
    { action: digest('opaque-ref'), requestRef: digest(opaque) },
    { action: digest('opaque-id'), requestId: digest(opaque) },
    { action: digest('jwt-fingerprint'), requestFingerprint: digest(jwt) },
    { action: digest('canonical-fingerprint'), fingerprint: canonicalFingerprint },
    { action: digest('cdp-request'), requestId: '1234.56' },
    { action: digest('uuid-request'), requestRef: uuid },
    { action: digest('client-request'), requestRef: digest('client_order-42') }
  ])
  assert.deepEqual(persisted.replayProbes, [
    {
      id: digest(jwt),
      referenceId: digest(base64url),
      fingerprint: digest(opaque)
    },
    {
      id: digest('replay-1'),
      caseId: 'AUTH-01',
      subrunId: 'desktop-ui-core',
      referenceId: digest('order/123'),
      requestId: '1234.56',
      clientOrderId: uuid,
      fingerprint: canonicalFingerprint,
      requestFingerprint: canonicalFingerprint,
      status: 'REJECTED',
      errorCode: 'REQUEST_CONFLICT',
      outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
    }
  ])
  assert.equal(persisted.userActions[2].requestRef, persisted.userActions[3].requestId)
  assert.equal(persisted.userActions[2].requestRef, persisted.replayProbes[0].fingerprint)
})

test('reference fields hash bounded correlations and expose only exact public identities', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-bounded-references-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const caseId = P0_CASES[0].id
  const subrunId = P0_CASES[0].requiredSubruns[0].id
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const correlations = {
    secretary: 'secretary-correlation-123',
    tokenized: 'tokenized-order-correlation-456',
    longDigits: '1234567890123456789012345678901234567890',
    longDotted: '1234567890.1234567890.1234567890.1234567890',
    aws: 'AKIAIOSFODNN7EXAMPLE',
    jwt: 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJJVEVNNF9KV1QifQ.signaturepart',
    base64: 'SVRFTTRfQkFTRTY0X0NPVlJFTEFUSU9OX01BUktFUg==',
    opaque: 'Opaque+Item4Correlation==A7f4',
    client: 'client_order-unknown-correlation-789',
    order: 'order/unknown-correlation-987'
  }
  const credentialMarkers = {
    bearer: 'ITEM4_BEARER_CREDENTIAL_17a1',
    cookie: 'ITEM4_COOKIE_CREDENTIAL_28b2',
    session: 'ITEM4_SESSION_CREDENTIAL_39c3',
    password: 'ITEM4_PASSWORD_CREDENTIAL_40d4',
    token: 'ITEM4_TOKEN_CREDENTIAL_51e5'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: caseId,
    userActions: [
      ...Object.values(correlations).map((requestRef) => ({ requestRef })),
      { requestId: correlations.secretary },
      { referenceId: correlations.secretary },
      { requestRef: `Bearer ${credentialMarkers.bearer}` },
      { requestRef: `Cookie: session=${credentialMarkers.cookie}` },
      { requestRef: `session=${credentialMarkers.session}` },
      { requestRef: `password=${credentialMarkers.password}` },
      { requestRef: `token=${credentialMarkers.token}` }
    ],
    publicReferences: [
      { id: caseId },
      { caseId },
      { subrunId },
      { requestId: '1234.56' },
      { referenceId: '1234.56' },
      { requestRef: uuid },
      { requestId: correlations.longDigits }
    ],
    numericReferences: {
      id: 303,
      requestId: 404,
      referenceId: 505,
      caseId: 606,
      subrunId: 707
    },
    checkpoint: {
      id: correlations.opaque,
      referenceId: correlations.secretary,
      clientOrderId: correlations.client,
      requestId: correlations.tokenized
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const value of Object.values(correlations)) assert.equal(source.includes(value), false, value)
  for (const marker of Object.values(credentialMarkers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  Object.values(correlations).forEach((value, index) => {
    assert.equal(persisted.userActions[index].requestRef, digest(value), value)
  })
  assert.equal(persisted.userActions[10].requestId, digest(correlations.secretary))
  assert.equal(persisted.userActions[11].referenceId, digest(correlations.secretary))
  for (const action of persisted.userActions.slice(12)) assert.equal('requestRef' in action, false)
  assert.deepEqual(persisted.publicReferences, [
    { id: caseId },
    { caseId },
    { subrunId },
    { requestId: '1234.56' },
    { referenceId: digest('1234.56') },
    { requestRef: uuid },
    { requestId: digest(correlations.longDigits) }
  ])
  assert.deepEqual(persisted.numericReferences, {
    id: 303,
    requestId: 404,
    referenceId: 505
  })
  assert.deepEqual(persisted.checkpoint, {
    id: digest(correlations.opaque),
    referenceId: digest(correlations.secretary),
    clientOrderId: digest(correlations.client),
    requestId: digest(correlations.tokenized)
  })
  assert.equal(persisted.userActions[0].requestRef, persisted.userActions[10].requestId)
  assert.equal(persisted.userActions[0].requestRef, persisted.checkpoint.referenceId)
})

test('persistence hashes nonpublic references and allowlists status evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-strict-public-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const clientOpaque = 'client_OpaqueCredentialReview7A9f4'
  const clientOpaqueLower = 'client_opaquecredentialreview7'
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc3X0pXVCJ9.signature'
  const base64url = 'UmV2aWV3N0Jhc2U2NFVybENyZWRlbnRpYWxNYXJrZXJfMTIzNDU2Nzg5MA'
  const plusEquals = 'Opaque+Correlation==A7f4'
  const fingerprintValue = 'Fingerprint+Credential==Review7'
  const uppercaseFingerprint = `sha256:${'A'.repeat(64)}`
  const canonicalFingerprint = `sha256:${'a'.repeat(64)}`
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const safeReplay = {
    id: 'replay-1',
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: 'order/123',
    requestId: '1234.56',
    clientOrderId: 'client_order-1',
    fingerprint: canonicalFingerprint,
    requestFingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'client-opaque', requestRef: clientOpaque },
      { action: 'client-opaque-lower', requestId: clientOpaqueLower },
      { action: 'access-key', requestRef: accessKey },
      { action: 'jwt', requestRef: jwt },
      { action: 'base64url', requestId: base64url },
      { action: 'plus-equals', requestRef: plusEquals },
      { action: 'opaque-fingerprint', requestFingerprint: fingerprintValue },
      { action: 'canonical-fingerprint', fingerprint: uppercaseFingerprint },
      { action: 'cdp-id', requestId: '1234.56' },
      { action: 'uuid-id', requestRef: uuid },
      { action: 'proven-request-id', requestRef: 'request-ref-safe-78b1' }
    ],
    replayProbes: [
      {
        id: clientOpaque,
        referenceId: plusEquals,
        requestId: accessKey,
        clientOrderId: clientOpaqueLower,
        fingerprint: plusEquals,
        status: accessKey,
        errorCode: accessKey,
        outcome: { status: accessKey, errorCode: accessKey }
      },
      safeReplay
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const credential of [
    clientOpaque,
    clientOpaqueLower,
    accessKey,
    jwt,
    base64url,
    plusEquals,
    fingerprintValue,
    uppercaseFingerprint
  ]) assert.equal(source.includes(credential), false, credential)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('client-opaque'), requestRef: digest(clientOpaque) },
    { action: digest('client-opaque-lower'), requestId: digest(clientOpaqueLower) },
    { action: digest('access-key'), requestRef: digest(accessKey) },
    { action: digest('jwt'), requestRef: digest(jwt) },
    { action: digest('base64url'), requestId: digest(base64url) },
    { action: digest('plus-equals'), requestRef: digest(plusEquals) },
    { action: digest('opaque-fingerprint'), requestFingerprint: digest(fingerprintValue) },
    { action: digest('canonical-fingerprint'), fingerprint: canonicalFingerprint },
    { action: digest('cdp-id'), requestId: '1234.56' },
    { action: digest('uuid-id'), requestRef: uuid },
    { action: digest('proven-request-id'), requestRef: digest('request-ref-safe-78b1') }
  ])
  assert.deepEqual(persisted.replayProbes, [{
    id: digest(clientOpaque),
    referenceId: digest(plusEquals),
    requestId: digest(accessKey),
    clientOrderId: digest(clientOpaqueLower),
    fingerprint: digest(plusEquals)
  }, {
    id: digest('replay-1'),
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: digest('order/123'),
    requestId: '1234.56',
    clientOrderId: digest('client_order-1'),
    fingerprint: canonicalFingerprint,
    requestFingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.userActions[5].requestRef, persisted.replayProbes[0].referenceId)
  assert.equal(persisted.userActions[5].requestRef, persisted.replayProbes[0].fingerprint)
})

test('persistence applies one global evidence field schema with canonical case and subrun ids', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-global-evidence-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const opaque = 'Opaque+GlobalCorrelation==Review8A7f4'
  const fingerprintValue = 'Fingerprint+GlobalOpaque==Review8'
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc4X0dMT0JBTCJ9.signature'
  const base64url = 'UmV2aWV3OEdMT0JBTEJhc2U2NFVybFZhbHVlXzEyMzQ1Njc4OTA'
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const forgedCaseId = 'FAKE-77'
  const forgedSubrunId = 'desktop-opaque-review8'
  const canonicalCaseId = P0_CASES[0].id
  const canonicalSubrunId = P0_CASES[0].requiredSubruns[0].id
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const canonicalFingerprint = `sha256:${'a'.repeat(64)}`
  const uppercaseFingerprint = `sha256:${'A'.repeat(64)}`
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const form = new URLSearchParams({
    referenceId: opaque,
    fingerprint: fingerprintValue,
    status: accessKey,
    caseId: forgedCaseId,
    subrunId: forgedSubrunId
  }).toString()
  const result = {
    id: canonicalCaseId,
    status: 'PASS',
    checkpoint: {
      id: opaque,
      clientOrderId: base64url,
      referenceId: opaque,
      resourceId: jwt,
      requestId: accessKey,
      fingerprint: uppercaseFingerprint,
      status: accessKey,
      errorCode: 'REQUEST_CONFLICT'
    },
    metadata: {
      id: 'nested-safe',
      status: 'OBSERVED',
      requestId: '1234.56',
      referenceId: uuid,
      caseId: canonicalCaseId,
      subrunId: canonicalSubrunId
    },
    forgedMembership: { caseId: forgedCaseId, subrunId: forgedSubrunId },
    numericMembership: {
      caseId: 101,
      subrunId: 202,
      id: 303,
      requestId: 404,
      referenceId: 505
    },
    body: JSON.stringify({
      id: opaque,
      referenceId: opaque,
      fingerprint: fingerprintValue,
      status: accessKey
    }),
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?symbol=BTCUSDT',
      status: 200,
      id: opaque,
      referenceId: opaque,
      resourceId: jwt,
      requestId: '1234.56',
      fingerprint: uppercaseFingerprint,
      errorCode: 'REQUEST_CONFLICT',
      responseBody: JSON.stringify({
        id: opaque,
        referenceId: opaque,
        fingerprint: fingerprintValue,
        status: accessKey,
        caseId: forgedCaseId,
        subrunId: forgedSubrunId
      }),
      responsePayload: form
    }],
    replayProbes: [{
      id: opaque,
      caseId: forgedCaseId,
      subrunId: forgedSubrunId,
      referenceId: opaque,
      requestId: accessKey,
      clientOrderId: base64url,
      fingerprint: fingerprintValue,
      status: accessKey,
      errorCode: 'REQUEST_CONFLICT'
    }, {
      id: 'replay-1',
      caseId: canonicalCaseId,
      subrunId: canonicalSubrunId,
      referenceId: 'order/123',
      requestId: '1234.56',
      clientOrderId: 'client_order-1',
      fingerprint: canonicalFingerprint,
      status: 'REJECTED',
      errorCode: 'REQUEST_CONFLICT'
    }]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of [
    opaque,
    fingerprintValue,
    jwt,
    base64url,
    accessKey,
    forgedCaseId,
    forgedSubrunId,
    uppercaseFingerprint
  ]) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  assert.equal(persisted.id, canonicalCaseId)
  assert.equal(persisted.status, 'PASS')
  assert.deepEqual(persisted.checkpoint, {
    id: digest(opaque),
    clientOrderId: digest(base64url),
    referenceId: digest(opaque),
    resourceId: digest(jwt),
    requestId: digest(accessKey),
    fingerprint: canonicalFingerprint,
    errorCode: 'REQUEST_CONFLICT'
  })
  assert.deepEqual(persisted.metadata, {
    id: digest('nested-safe'),
    status: 'OBSERVED',
    requestId: '1234.56',
    referenceId: uuid,
    caseId: canonicalCaseId,
    subrunId: canonicalSubrunId
  })
  assert.deepEqual(persisted.forgedMembership, {
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId)
  })
  assert.deepEqual(persisted.numericMembership, {
    id: 303,
    requestId: 404,
    referenceId: 505
  })
  assert.deepEqual(JSON.parse(persisted.body), {
    id: digest(opaque),
    referenceId: digest(opaque),
    fingerprint: digest(fingerprintValue)
  })
  const network = persisted.networkEvidence[0]
  assert.equal(network.status, 200)
  assert.equal(network.id, digest(opaque))
  assert.equal(network.referenceId, digest(opaque))
  assert.equal(network.resourceId, digest(jwt))
  assert.equal(network.requestId, '1234.56')
  assert.equal(network.fingerprint, canonicalFingerprint)
  assert.equal(network.errorCode, 'REQUEST_CONFLICT')
  assert.deepEqual(JSON.parse(network.responseBody), {
    id: digest(opaque),
    referenceId: digest(opaque),
    fingerprint: digest(fingerprintValue),
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId)
  })
  const persistedForm = new URLSearchParams(network.responsePayload)
  assert.equal(persistedForm.get('referenceId'), digest(opaque))
  assert.equal(persistedForm.get('fingerprint'), digest(fingerprintValue))
  assert.equal(persistedForm.has('status'), false)
  assert.equal(persistedForm.get('caseId'), digest(forgedCaseId))
  assert.equal(persistedForm.get('subrunId'), digest(forgedSubrunId))
  assert.deepEqual(persisted.replayProbes, [{
    id: digest(opaque),
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId),
    referenceId: digest(opaque),
    requestId: digest(accessKey),
    clientOrderId: digest(base64url),
    fingerprint: digest(fingerprintValue),
    errorCode: 'REQUEST_CONFLICT'
  }, {
    id: digest('replay-1'),
    caseId: canonicalCaseId,
    subrunId: canonicalSubrunId,
    referenceId: digest('order/123'),
    requestId: '1234.56',
    clientOrderId: digest('client_order-1'),
    fingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT'
  }])
  assert.equal(persisted.checkpoint.id, network.id)
  assert.equal(network.id, JSON.parse(network.responseBody).id)
  assert.equal(network.id, persistedForm.get('referenceId'))
  assert.equal(network.id, persisted.replayProbes[0].referenceId)
})

test('persistence redacts parseable response bodies and drops opaque response payloads without mutation', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-redaction-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    accessToken: 'JSON_RESPONSE_ACCESS_TOKEN_e5c1',
    password: 'JSON_RESPONSE_PASSWORD_7a3d',
    formPassword: 'FORM_RESPONSE_PASSWORD_29bf',
    opaque: 'T1BBUVVFX1JFU1BPTlNFXzYxNmY+/=='
  }
  const result = {
    id: 'AUTH-01',
    networkEvidence: [
      {
        method: 'POST',
        url: '/api/login',
        status: 200,
        responseBody: JSON.stringify({
          accessToken: markers.accessToken,
          order: {
            password: markers.password,
            symbol: 'ETHUSDT',
            status: 'FILLED'
          }
        })
      },
      {
        method: 'POST',
        url: '/api/session',
        status: 200,
        responsePayload: `password=${markers.formPassword}&symbol=BNBUSDT`
      },
      {
        method: 'GET',
        url: '/api/binary',
        status: 200,
        responseBody: markers.opaque
      }
    ]
  }
  const original = JSON.parse(JSON.stringify(result))

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  const persisted = JSON.parse(serialized)
  assert.deepEqual(JSON.parse(persisted.networkEvidence[0].responseBody), {
    accessToken: '[REDACTED]',
    order: { password: '[REDACTED]', symbol: 'ETHUSDT', status: 'FILLED' }
  })
  const form = new URLSearchParams(persisted.networkEvidence[1].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'BNBUSDT')
  assert.equal('responseBody' in persisted.networkEvidence[2], false)
})

test('JSON body sanitizer does not form-fallback after parse', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-json-body-fail-closed-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const marker = 'JSON_SANITIZER_FALLBACK_PASSWORD_6e2a'
  const result = {
    id: 'AUTH-01',
    parsedFailure: {
      body: JSON.stringify({
        nested: { url: 'http://[', password: marker }
      })
    },
    canonicalForm: {
      body: 'password=form-secret&symbol=BNBUSDT'
    },
    validJson: {
      body: JSON.stringify({
        url: '/api/orders?access_token=query-secret&symbol=BTCUSDT',
        password: 'json-secret',
        status: 'ACCEPTED'
      })
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  assert.equal('body' in persisted.parsedFailure, false)
  const form = new URLSearchParams(persisted.canonicalForm.body)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'BNBUSDT')
  assert.deepEqual(JSON.parse(persisted.validJson.body), {
    url: '/api/orders?access_token=%5BREDACTED%5D&symbol=BTCUSDT',
    password: '[REDACTED]',
    status: 'ACCEPTED'
  })
})

test('response evidence drops padded base64 but keeps canonical credential form', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-form-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const paddedBase64 = [
    'YQ==',
    'c2VjcmV0X3Rva2VuPQ==',
    'YWI=',
    'c2VjcmV0X3Rva2VuPWE='
  ]

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    networkEvidence: [
      { method: 'GET', url: '/api/short', status: 200, responseBody: paddedBase64[0] },
      { method: 'GET', url: '/api/token', status: 200, responsePayload: paddedBase64[1] },
      { method: 'GET', url: '/api/single-padding', status: 200, responseBody: paddedBase64[2] },
      {
        method: 'GET',
        url: '/api/token-single-padding',
        status: 200,
        responsePayload: paddedBase64[3]
      },
      {
        method: 'POST',
        url: '/api/login',
        status: 200,
        responsePayload: 'password=canonical-secret&status=FILLED'
      }
    ]
  })

  const source = readFileSync(resultPath, 'utf8')
  for (const opaque of paddedBase64) assert.equal(source.includes(opaque), false)
  const evidence = JSON.parse(source).networkEvidence
  assert.equal('responseBody' in evidence[0], false)
  assert.equal('responsePayload' in evidence[1], false)
  assert.equal('responseBody' in evidence[2], false)
  assert.equal('responsePayload' in evidence[3], false)
  const form = new URLSearchParams(evidence[4].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('status'), 'FILLED')
})

test('response evidence drops opaque JSON and empty form leaves', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-opaque-leaves-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVzdX0pTT05fU0NBTEFSIn0.signature'
  const base64url = 'QmFzZTY0VXJsUmVzcG9uc2VMZWFmQ3JlZGVudGlhbF9NYXJrZXJfMTIzNDU2Nzg5MA'
  const opaque = 'OpaqueResponseCredentialA7f4C9e2'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    networkEvidence: [
      { method: 'GET', url: '/api/jwt', status: 200, responseBody: JSON.stringify(jwt) },
      {
        method: 'GET',
        url: '/api/base64url',
        status: 200,
        responsePayload: JSON.stringify(base64url)
      },
      { method: 'GET', url: '/api/opaque', status: 200, responseBody: JSON.stringify(opaque) },
      {
        method: 'GET',
        url: '/api/array',
        status: 200,
        responseBody: JSON.stringify([
          jwt,
          [base64url, { id: 'nested-safe', accessToken: 'nested-secret' }],
          opaque,
          { id: 'safe-observation', status: 'OBSERVED', password: 'object-secret' }
        ])
      },
      { method: 'GET', url: '/api/empty-form', status: 200, responsePayload: 'YWI=&YWI=' },
      {
        method: 'GET',
        url: '/api/form',
        status: 200,
        responsePayload: 'password=form-secret&symbol=XRPUSDT'
      },
      {
        method: 'GET',
        url: '/api/object',
        status: 200,
        responseBody: JSON.stringify({
          id: 'response-1',
          accessToken: 'object-token',
          market: { symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' }
        })
      }
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of [jwt, base64url, opaque]) assert.equal(source.includes(marker), false)
  const evidence = JSON.parse(source).networkEvidence
  assert.equal('responseBody' in evidence[0], false)
  assert.equal('responsePayload' in evidence[1], false)
  assert.equal('responseBody' in evidence[2], false)
  assert.deepEqual(JSON.parse(evidence[3].responseBody), [
    [{ id: digest('nested-safe'), accessToken: '[REDACTED]' }],
    { id: digest('safe-observation'), status: 'OBSERVED', password: '[REDACTED]' }
  ])
  assert.equal('responsePayload' in evidence[4], false)
  const form = new URLSearchParams(evidence[5].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'XRPUSDT')
  assert.deepEqual(JSON.parse(evidence[6].responseBody), {
    id: digest('response-1'),
    accessToken: '[REDACTED]',
    market: { symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' }
  })
})

const RUN_STATE_COMMIT_A = 'a'.repeat(40)
const RUN_STATE_COMMIT_B = 'b'.repeat(40)
const RUN_STATE_TREE_A = 'c'.repeat(64)
const RUN_STATE_TREE_B = 'd'.repeat(64)
const runStateDigest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

test('run state is created atomically and resumes only an identical evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const options = {
    path: statePath,
    runId: 'discovery-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  }

  const created = loadOrCreateRunState(options)
  assert.equal(created.runId, runStateDigest('discovery-contract'))
  assert.deepEqual(created.cases, {})
  assert.equal(existsSync(`${statePath}.tmp`), false)
  created.cases['AUTH-01'] = { status: 'PASS', scopeComplete: true }
  writeCaseResultAtomic(statePath, created)
  assert.deepEqual(loadOrCreateRunState(options), created)

  for (const [field, value] of [
    ['commit', RUN_STATE_COMMIT_B],
    ['worktreeFingerprint', RUN_STATE_TREE_B],
    ['schemaVersion', 2]
  ]) {
    assert.throws(
      () => loadOrCreateRunState({ ...options, [field]: value }),
      new RegExp(`^Error: RESUME_MISMATCH: ${field}$`)
    )
  }
  assert.throws(
    () => loadOrCreateRunState({
      ...options,
      registryFingerprint: 'e'.repeat(64)
    }),
    /^Error: INVALID_RUN_STATE_IDENTITY: options\.registryFingerprint$/
  )
})

test('run state rejects missing, null, blank or invalid evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-identity-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const valid = {
    runId: 'identity-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  }
  const invalidValues = {
    commit: [undefined, null, '', '   ', 42, {}],
    worktreeFingerprint: [undefined, null, '', '   ', 42, {}],
    schemaVersion: [undefined, null, '', '   ', 0, -1, 1.5, {}],
    registryFingerprint: [undefined, null, '', '   ', 'e'.repeat(64), 42, {}]
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

test('run state snapshots inert options before path and registry access', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-options-snapshot-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const validOptions = (path) => ({
    path,
    runId: 'snapshot-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  })

  const victimPath = join(directory, 'victim.json')
  const absentPath = join(directory, 'absent.json')
  const victimBaseline = '{"status":"PASS","owner":"victim"}\n'
  writeFileSync(victimPath, victimBaseline)
  let pathGetterCalls = 0
  const changingPath = validOptions(absentPath)
  Object.defineProperty(changingPath, 'path', {
    enumerable: true,
    get() {
      pathGetterCalls += 1
      return pathGetterCalls === 1 ? absentPath : victimPath
    }
  })

  assert.throws(
    () => loadOrCreateRunState(changingPath),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(pathGetterCalls, 0)
  assert.equal(existsSync(absentPath), false)
  assert.equal(readFileSync(victimPath, 'utf8'), victimBaseline)

  let definitionsGetterCalls = 0
  const definitionsPath = join(directory, 'definitions.json')
  const changingDefinitions = validOptions(definitionsPath)
  Object.defineProperty(changingDefinitions, 'definitions', {
    enumerable: true,
    get() {
      definitionsGetterCalls += 1
      return P0_CASES
    }
  })
  assert.throws(
    () => loadOrCreateRunState(changingDefinitions),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(definitionsGetterCalls, 0)
  assert.equal(existsSync(definitionsPath), false)

  let nestedGetterCalls = 0
  const nestedDefinitions = JSON.parse(JSON.stringify(P0_CASES))
  Object.defineProperty(nestedDefinitions[0], 'executionGroup', {
    enumerable: true,
    get() {
      nestedGetterCalls += 1
      return P0_CASES[0].executionGroup
    }
  })
  const nestedPath = join(directory, 'nested.json')
  assert.throws(
    () => loadOrCreateRunState({
      ...validOptions(nestedPath),
      definitions: nestedDefinitions
    }),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(nestedGetterCalls, 0)
  assert.equal(existsSync(nestedPath), false)

  let proxyTrapCalls = 0
  const proxyPath = join(directory, 'proxy.json')
  const proxiedOptions = new Proxy(validOptions(proxyPath), {
    get(target, field, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, field, receiver)
    }
  })
  assert.throws(
    () => loadOrCreateRunState(proxiedOptions),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: Proxy$/
  )
  assert.equal(proxyTrapCalls, 0)
  assert.equal(existsSync(proxyPath), false)

  for (const path of [undefined, null, '', 42, {}]) {
    assert.throws(
      () => loadOrCreateRunState({ ...validOptions(path) }),
      /^TypeError: INVALID_RUN_STATE_PATH$/
    )
  }

  const frozenPath = join(directory, 'frozen.json')
  const frozenOptions = Object.freeze({
    ...validOptions(frozenPath),
    selection: Object.freeze({})
  })
  const created = loadOrCreateRunState(frozenOptions)
  assert.equal(created.runId, runStateDigest('snapshot-contract'))
  assert.deepEqual(loadOrCreateRunState(frozenOptions), created)
})

function passingCase(definition) {
  return {
    id: definition.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: definition.requiredSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
}

function registryFingerprint(definitions) {
  return createHash('sha256').update(JSON.stringify(definitions)).digest('hex')
}

function uniquePassingCases(definitions) {
  return [...new Map(definitions.map((definition) => [
    definition.id,
    passingCase(definition)
  ])).values()]
}

const CANONICAL_REGISTRY_FINGERPRINT = registryFingerprint(P0_CASES)

function aggregateState(selection = {}) {
  return {
    definitions: P0_CASES,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection
  }
}

function resumeState(cases = {}) {
  return { ...aggregateState(), cases }
}

const INVALID_AGGREGATE_REGISTRIES = {
  'one-case': P0_CASES.slice(0, 1),
  '59-cases': P0_CASES.slice(0, 59),
  'replacement-id': [
    ...P0_CASES.slice(0, 59),
    { ...P0_CASES[59], id: 'UNKNOWN-99' }
  ],
  'duplicate-id': [...P0_CASES.slice(0, 59), P0_CASES[0]],
  'metadata-drift': [
    { ...P0_CASES[0], executionGroup: 'drifted-auth-session' },
    ...P0_CASES.slice(1)
  ]
}

for (const [scenario, definitions] of Object.entries(INVALID_AGGREGATE_REGISTRIES)) {
  test(`canonical registry rejects aggregate universe: ${scenario}`, () => {
    const report = aggregateReport({
      definitions,
      registryFingerprint: registryFingerprint(definitions),
      selection: {}
    }, uniquePassingCases(definitions))

    assert.equal(report.verdict, 'FAIL')
    assert.equal(report.scopeComplete, false)
    assert.ok(report.issues.includes('INVALID_REGISTRY'))
  })
}

test('canonical registry rejects a forged caller fingerprint', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-forged-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))

  assert.throws(
    () => loadOrCreateRunState({
      path: join(directory, 'run-state.json'),
      runId: 'registry-contract',
      mode: 'DISCOVERY',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A,
      schemaVersion: 1,
      registryFingerprint: 'forged-registry-fingerprint',
      definitions: P0_CASES,
      selection: {}
    }),
    /^Error: REGISTRY_FINGERPRINT_MISMATCH: options$/
  )
})

test('canonical registry rejects persisted definitions with a copied fingerprint', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-persisted-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const identity = {
    runId: 'registry-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }
  writeCaseResultAtomic(statePath, {
    ...identity,
    definitions: P0_CASES.slice(0, 59),
    cases: {},
    createdAt: '2026-07-14T00:00:00.000Z'
  })

  assert.throws(
    () => loadOrCreateRunState({
      ...identity,
      path: statePath,
      definitions: P0_CASES
    }),
    /^Error: INVALID_REGISTRY: state$/
  )
})

test('canonical registry reaches terminal PASS only with all exact results', () => {
  const report = aggregateReport({
    definitions: P0_CASES,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }, P0_CASES.map(passingCase))

  assert.equal(report.verdict, 'PASS')
  assert.equal(report.scopeComplete, true)
  assert.equal(report.counts.PASS, 60)
  assert.deepEqual(report.issues, [])
})

test('aggregate snapshots immutable evidence and rejects malformed identity arrays', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-aggregate-identity-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const definition = P0_CASES[0]
  const selection = { caseIds: [definition.id] }
  const result = passingCase(definition)
  const capture = (run) => {
    try {
      return { value: run() }
    } catch (error) {
      return { error: error.message }
    }
  }
  const load = (definitions, selected, label) => loadOrCreateRunState({
    path: join(directory, `${label}.json`),
    runId: `aggregate-identity-${label}`,
    mode: 'DISCOVERY',
    commit: 'commit-a',
    worktreeFingerprint: 'tree-a',
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions,
    selection: selected
  })

  let definitionsGetterCalls = 0
  const changingDefinitionsState = {
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }
  Object.defineProperty(changingDefinitionsState, 'definitions', {
    enumerable: true,
    get() {
      definitionsGetterCalls += 1
      return definitionsGetterCalls === 1 ? P0_CASES : [definition]
    }
  })
  let selectionGetterCalls = 0
  const changingSelection = {}
  Object.defineProperty(changingSelection, 'caseIds', {
    enumerable: true,
    get() {
      selectionGetterCalls += 1
      return selectionGetterCalls === 1 ? [] : [definition.id]
    }
  })
  let resultGetterCalls = 0
  const resultWithGetter = passingCase(definition)
  Object.defineProperty(resultWithGetter, 'status', {
    enumerable: true,
    get() {
      resultGetterCalls += 1
      return 'PASS'
    }
  })
  const proxyCounts = {
    state: 0,
    definitions: 0,
    selection: 0,
    results: 0,
    result: 0
  }
  const proxiedState = new Proxy(aggregateState(selection), {
    get(target, field, receiver) {
      proxyCounts.state += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedDefinitions = new Proxy(P0_CASES, {
    get(target, field, receiver) {
      proxyCounts.definitions += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedSelection = new Proxy(selection, {
    get(target, field, receiver) {
      proxyCounts.selection += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedResults = new Proxy([result], {
    get(target, field, receiver) {
      proxyCounts.results += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedResult = new Proxy(result, {
    get(target, field, receiver) {
      proxyCounts.result += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const aggregateBoundaryOutcomes = {
    'changing-definitions': capture(() => aggregateReport(
      changingDefinitionsState,
      [result]
    )),
    'changing-selection': capture(() => aggregateReport(
      aggregateState(changingSelection),
      [result]
    )),
    'result-getter': capture(() => aggregateReport(
      aggregateState(selection),
      [resultWithGetter]
    )),
    'state-proxy': capture(() => aggregateReport(proxiedState, [result])),
    'definitions-proxy': capture(() => aggregateReport({
      definitions: proxiedDefinitions,
      registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
      selection
    }, [result])),
    'selection-proxy': capture(() => aggregateReport(
      aggregateState(proxiedSelection),
      [result]
    )),
    'results-proxy': capture(() => aggregateReport(
      aggregateState(selection),
      proxiedResults
    )),
    'result-proxy': capture(() => aggregateReport(
      aggregateState(selection),
      [proxiedResult]
    ))
  }

  const malformedArray = (base, variant, proxyValue) => {
    const value = [...base]
    const counts = { getter: 0, trap: 0 }
    if (variant === 'hole') value.length += 1
    if (variant === 'undefined') value.push(undefined)
    if (variant === 'function') value.push(() => proxyValue)
    if (variant === 'symbol') value.push(Symbol('review9-identity'))
    if (variant === 'accessor') {
      Object.defineProperty(value, value.length, {
        enumerable: true,
        get() {
          counts.getter += 1
          return proxyValue
        }
      })
    }
    if (variant === 'proxy-element') {
      value.push(new Proxy(
        proxyValue && typeof proxyValue === 'object' ? proxyValue : { value: proxyValue },
        {
          get(target, field, receiver) {
            counts.trap += 1
            return Reflect.get(target, field, receiver)
          }
        }
      ))
    }
    if (variant === 'enumerable-property') {
      Object.defineProperty(value, 'review9Extra', {
        value: proxyValue,
        enumerable: true
      })
    }
    if (variant === 'nonenumerable-property') {
      Object.defineProperty(value, 'review9Extra', {
        value: proxyValue,
        enumerable: false
      })
    }
    return { value, counts }
  }
  const variants = [
    'hole',
    'undefined',
    'function',
    'symbol',
    'accessor',
    'proxy-element',
    'enumerable-property',
    'nonenumerable-property'
  ]
  const malformedOutcomes = variants.map((variant) => {
    const definitions = malformedArray(P0_CASES, variant, definition)
    const selected = malformedArray([definition.id], variant, definition.id)
    const requiredSubruns = malformedArray(
      definition.requiredSubruns,
      variant,
      definition.requiredSubruns[0]
    )
    const definitionsWithRequiredSubruns = [
      { ...definition, requiredSubruns: requiredSubruns.value },
      ...P0_CASES.slice(1)
    ]
    const results = malformedArray([result], variant, result)
    const actualSubruns = malformedArray(result.subruns, variant, result.subruns[0])
    const resultWithSubruns = { ...result, subruns: actualSubruns.value }
    return {
      variant,
      counters: [
        definitions.counts,
        selected.counts,
        requiredSubruns.counts,
        results.counts,
        actualSubruns.counts
      ],
      outcomes: {
        'load-definitions': capture(() => load(
          definitions.value,
          selection,
          `${variant}-load-definitions`
        )),
        'plan-caller-definitions': capture(() => planResume(
          resumeState(),
          definitions.value,
          selection
        )),
        'plan-state-definitions': capture(() => planResume({
          definitions: definitions.value,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          cases: {}
        }, P0_CASES, selection)),
        'aggregate-definitions': capture(() => aggregateReport({
          definitions: definitions.value,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          selection
        }, [result])),
        'load-selection': capture(() => load(
          P0_CASES,
          { caseIds: selected.value },
          `${variant}-load-selection`
        )),
        'plan-selection': capture(() => planResume(
          resumeState(),
          P0_CASES,
          { caseIds: selected.value }
        )),
        'aggregate-selection': capture(() => aggregateReport(
          aggregateState({ caseIds: selected.value }),
          [result]
        )),
        'load-required-subruns': capture(() => load(
          definitionsWithRequiredSubruns,
          selection,
          `${variant}-load-required-subruns`
        )),
        'plan-caller-required-subruns': capture(() => planResume(
          resumeState(),
          definitionsWithRequiredSubruns,
          selection
        )),
        'plan-state-required-subruns': capture(() => planResume({
          definitions: definitionsWithRequiredSubruns,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          cases: {}
        }, P0_CASES, selection)),
        'aggregate-required-subruns': capture(() => aggregateReport({
          definitions: definitionsWithRequiredSubruns,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          selection
        }, [result])),
        'aggregate-results': capture(() => aggregateReport(
          aggregateState(selection),
          results.value
        )),
        'aggregate-result-subruns': capture(() => aggregateReport(
          aggregateState(selection),
          [resultWithSubruns]
        ))
      }
    }
  })

  const incomplete = passingCase(definition)
  incomplete.subruns = []
  const incompleteReport = aggregateReport(aggregateState(selection), [incomplete])

  const assertAggregateRejected = (outcome, label) => {
    assert.equal(outcome.error, undefined, label)
    assert.equal(outcome.value.verdict, 'FAIL', label)
    assert.deepEqual(outcome.value.issues, ['INVALID_AGGREGATE_INPUT'], label)
  }
  for (const [label, outcome] of Object.entries(aggregateBoundaryOutcomes)) {
    assertAggregateRejected(outcome, label)
  }
  assert.equal(definitionsGetterCalls, 0)
  assert.equal(selectionGetterCalls, 0)
  assert.equal(resultGetterCalls, 0)
  assert.deepEqual(proxyCounts, {
    state: 0,
    definitions: 0,
    selection: 0,
    results: 0,
    result: 0
  })

  for (const { variant, counters, outcomes } of malformedOutcomes) {
    for (const [boundary, outcome] of Object.entries(outcomes)) {
      const label = `${variant}/${boundary}`
      if (boundary.startsWith('aggregate-')) {
        assertAggregateRejected(outcome, label)
      } else {
        assert.match(
          outcome.error ?? '',
          /^(?:UNSAFE_IDENTITY_ARRAY|UNSAFE_PERSISTENCE_VALUE: (?:accessor|Proxy))$/,
          label
        )
      }
    }
    for (const counts of counters) {
      assert.deepEqual(counts, { getter: 0, trap: 0 }, variant)
    }
  }
  assert.equal(incompleteReport.verdict, 'FAIL')
  assert.ok(incompleteReport.issues.includes(`INCOMPLETE_MATRIX: ${definition.id}`))
  assert.equal(incompleteReport.counts.PASS, 0)
})

test('canonical registry remains full for valid filtered case and phase runs', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-filtered-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const selections = [
    { caseIds: ['AUTH-03'] },
    { phases: ['funding'] }
  ]

  for (const [index, selection] of selections.entries()) {
    const state = loadOrCreateRunState({
      path: join(directory, `run-state-${index}.json`),
      runId: `registry-filter-${index}`,
      mode: 'DISCOVERY',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A,
      schemaVersion: 1,
      registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
      definitions: P0_CASES,
      selection
    })
    const selected = P0_CASES.filter((definition) => (
      (!selection.caseIds || selection.caseIds.includes(definition.id))
      && (!selection.phases || selection.phases.includes(definition.phase))
    ))
    const report = aggregateReport(state, selected.map(passingCase))

    assert.equal(state.definitions.length, 60)
    assert.equal(state.registryFingerprint, CANONICAL_REGISTRY_FINGERPRINT)
    assert.equal(report.verdict, 'PARTIAL_PASS')
    assert.equal(report.scopeComplete, false)
    assert.deepEqual(report.issues, [])
  }
})

function passingSelectedCase(definition, selection) {
  const subruns = definition.requiredSubruns.filter((subrun) => (
    (!selection.profiles?.length || selection.profiles.includes(subrun.profile))
    && (!selection.viewports?.length || selection.viewports.includes(subrun.viewport))
  ))
  return {
    id: definition.id,
    status: 'PASS',
    scopeComplete: !selection.profiles?.length && !selection.viewports?.length,
    subruns: subruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
}

const INVALID_SELECTOR_FIXTURES = [
  {
    label: 'mixed caseIds',
    selection: { caseIds: ['AUTH-03', 'UNKNOWN-99'] }
  },
  {
    label: 'mixed phases',
    selection: { phases: ['funding', 'unknown-phase'] }
  },
  {
    label: 'mixed profiles',
    selection: { caseIds: ['SOURCE-03'], profiles: ['FUNDING_ONLY', 'UNKNOWN_PROFILE'] }
  },
  {
    label: 'mixed viewports',
    selection: { caseIds: ['UI-02'], viewports: ['mobile', 'watch'] }
  },
  {
    label: 'profile without selected-case coverage',
    selection: { caseIds: ['AUTH-03'], profiles: ['UI_CORE', 'FUNDING_ONLY'] }
  },
  {
    label: 'viewport without selected-case coverage',
    selection: { caseIds: ['AUTH-03'], viewports: ['desktop', 'mobile'] }
  }
]

for (const { label, selection } of INVALID_SELECTOR_FIXTURES) {
  const selectedDefinitions = P0_CASES.filter((definition) => (
    (!selection.caseIds?.length || selection.caseIds.includes(definition.id))
    && (!selection.phases?.length || selection.phases.includes(definition.phase))
  )).filter((definition) => passingSelectedCase(definition, selection).subruns.length > 0)
  const results = selectedDefinitions.map((definition) => passingSelectedCase(definition, selection))

  test(`invalid selector aggregation fails explicitly: ${label}`, () => {
    const report = aggregateReport(aggregateState(selection), results)

    assert.equal(report.verdict, 'FAIL')
    assert.equal(report.scopeComplete, false)
    assert.ok(report.issues.some((issue) => issue.startsWith('INVALID_SELECTION:')))
  })

  test(`invalid selector resume planning fails explicitly: ${label}`, () => {
    const cases = Object.fromEntries(results.map((result) => [result.id, result]))

    assert.throws(
      () => planResume(resumeState(cases), P0_CASES, selection),
      /^Error: INVALID_SELECTION:/
    )
  })
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
    const plan = planResume(resumeState(cases), P0_CASES, fixture.selection)

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
      aggregateState(fixture.selection),
      fixture.definitions.map(passingCase)
    )

    assert.equal(report.verdict, 'PARTIAL_PASS')
    assert.equal(report.scopeComplete, false)
    assert.deepEqual(report.issues, [])
    assert.equal(report.counts.PASS, fixture.definitions.length)
  })
}

test('resume planning rejects noncanonical caller and state registries', () => {
  const definition = P0_CASES[0]
  const cases = { [definition.id]: passingCase(definition) }
  const selection = { caseIds: [definition.id] }
  const forgedRequiredSubruns = [
    {
      ...P0_CASES[0],
      requiredSubruns: [{
        ...P0_CASES[0].requiredSubruns[0],
        id: 'desktop-forged-review8'
      }]
    },
    ...P0_CASES.slice(1)
  ]
  const invalidCallers = {
    partial: P0_CASES.slice(0, 1),
    replacement: [
      ...P0_CASES.slice(0, -1),
      { ...P0_CASES.at(-1), id: 'UNKNOWN-99' }
    ],
    duplicate: [...P0_CASES.slice(0, -1), P0_CASES[0]],
    'metadata-drift': [
      { ...P0_CASES[0], executionGroup: 'forged-group' },
      ...P0_CASES.slice(1)
    ],
    'forged-requiredSubruns': forgedRequiredSubruns
  }

  for (const [label, definitions] of Object.entries(invalidCallers)) {
    assert.throws(
      () => planResume(resumeState(cases), definitions, selection),
      /^Error: INVALID_REGISTRY: definitions$/,
      label
    )
  }

  const partialStateDefinitions = P0_CASES.slice(0, 1)
  assert.throws(
    () => planResume({
      definitions: partialStateDefinitions,
      registryFingerprint: registryFingerprint(partialStateDefinitions),
      cases
    }, P0_CASES, selection),
    /^Error: INVALID_REGISTRY: state$/
  )
  assert.throws(
    () => planResume({
      definitions: P0_CASES,
      registryFingerprint: 'forged-registry-fingerprint',
      cases
    }, P0_CASES, selection),
    /^Error: REGISTRY_FINGERPRINT_MISMATCH: state$/
  )

  const valid = planResume(resumeState(cases), P0_CASES, selection)
  assert.equal(valid.filtered, true)
  assert.equal(valid.scopeComplete, false)
  assert.equal(valid.entries.length, 1)
  assert.equal(valid.entries[0].action, 'SKIP')
})

test('resume reruns an entire RUNNING case and every member of its execution group', () => {
  const definitions = P0_CASES.filter(({ id }) => id === 'AUTH-01' || id === 'AUTH-02')
  const state = resumeState({
      'AUTH-01': passingCase(definitions[0]),
      'AUTH-02': {
        ...passingCase(definitions[1]),
        status: 'RUNNING',
        scopeComplete: false,
        subruns: [{ ...definitions[1].requiredSubruns[0], status: 'RUNNING' }]
      }
  })

  const plan = planResume(state, P0_CASES, { caseIds: definitions.map(({ id }) => id) })
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
    resumeState({ [definition.id]: passingCase(definition) }),
    P0_CASES,
    { caseIds: [definition.id] }
  )
  assert.equal(complete.scopeComplete, false)
  assert.equal(complete.entries[0].action, 'SKIP')

  const partial = passingCase(definition)
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = planResume(
    resumeState({ [definition.id]: partial }),
    P0_CASES,
    { caseIds: [definition.id] }
  )
  assert.equal(incomplete.scopeComplete, false)
  assert.equal(incomplete.entries[0].action, 'RUN')
  assert.equal(incomplete.entries[0].reason, 'INCOMPLETE_SUBRUNS')
  assert.deepEqual(incomplete.entries[0].subruns, definition.requiredSubruns)
})

test('resume reruns PASS evidence whose result id differs from its case key', () => {
  const expected = P0_CASES.find(({ id }) => id === 'AUTH-01')
  const swapped = P0_CASES.find(({ id }) => id === 'AUTH-02')
  const plan = planResume(
    resumeState({ [expected.id]: passingCase(swapped) }),
    P0_CASES,
    { caseIds: [expected.id] }
  )

  assert.equal(plan.scopeComplete, false)
  assert.equal(plan.entries[0].action, 'RUN')
  assert.equal(plan.entries[0].reason, 'INCOMPLETE_SUBRUNS')
})

test('profile and viewport filters are never resumable as complete scope', () => {
  const source = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const funding = source.requiredSubruns.filter(({ profile }) => profile === 'FUNDING_ONLY')
  const sourcePlan = planResume(
    resumeState({
        [source.id]: {
          id: source.id,
          status: 'PASS',
          scopeComplete: false,
          subruns: funding.map((subrun) => ({ ...subrun, status: 'PASS' }))
        }
    }),
    P0_CASES,
    { caseIds: [source.id], profiles: ['FUNDING_ONLY'] }
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
    resumeState({ [ui.id]: falselyCompleteMobile }),
    P0_CASES,
    { caseIds: [ui.id], viewports: ['mobile'] }
  )
  assert.equal(uiPlan.scopeComplete, false)
  assert.equal(uiPlan.entries[0].action, 'RUN')
  assert.deepEqual(uiPlan.entries[0].subruns, mobile)
  const uiReport = aggregateReport(
    aggregateState({ caseIds: [ui.id], viewports: ['mobile'] }),
    [falselyCompleteMobile]
  )
  assert.equal(uiReport.verdict, 'FAIL')
  assert.ok(uiReport.issues.includes(`INCOMPLETE_MATRIX: ${ui.id}`))
})

test('aggregate report reaches PASS only with every required case and subrun', () => {
  const state = aggregateState()
  const completeResults = P0_CASES.map(passingCase)
  const passed = aggregateReport(state, completeResults)
  assert.equal(passed.verdict, 'PASS')
  assert.equal(passed.scopeComplete, true)
  assert.deepEqual(passed.counts, {
    PASS: 60,
    FAIL: 0,
    BLOCKED: 0,
    INVALID_TEST: 0,
    MISSING: 0
  })

  const missing = aggregateReport(
    state,
    completeResults.filter(({ id }) => id !== 'SOURCE-03')
  )
  assert.equal(missing.verdict, 'FAIL')
  assert.equal(missing.scopeComplete, false)
  assert.ok(missing.issues.includes('MISSING_CASE: SOURCE-03'))

  const partial = passingCase(P0_CASES.find(({ id }) => id === 'SOURCE-03'))
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = aggregateReport(state, completeResults.map((result) => (
    result.id === partial.id ? partial : result
  )))
  assert.equal(incomplete.verdict, 'FAIL')
  assert.ok(incomplete.issues.includes('INCOMPLETE_MATRIX: SOURCE-03'))
})

test('valid FAIL and INVALID_TEST outrank BLOCKED in the terminal verdict', () => {
  const definitions = P0_CASES.slice(0, 2)
  const state = aggregateState({ caseIds: definitions.map(({ id }) => id) })

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
    aggregateState({ caseIds: [definition.id], profiles: ['FUNDING_ONLY'] }),
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

test('cropped scope requires exact false while case selection requires true', () => {
  const croppedDefinition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const croppedSelection = {
    caseIds: [croppedDefinition.id],
    profiles: ['FUNDING_ONLY']
  }
  const croppedSubruns = croppedDefinition.requiredSubruns.filter(
    ({ profile }) => profile === 'FUNDING_ONLY'
  )
  const invalidScopes = [
    { label: 'missing' },
    { label: 'null', scopeComplete: null },
    { label: 'string', scopeComplete: 'false' },
    { label: 'number', scopeComplete: 0 },
    { label: 'true', scopeComplete: true }
  ]
  const invalidReports = invalidScopes.map((variant) => {
    const result = {
      id: croppedDefinition.id,
      status: 'PASS',
      subruns: croppedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }
    if (Object.hasOwn(variant, 'scopeComplete')) result.scopeComplete = variant.scopeComplete
    const report = aggregateReport(
      aggregateState(croppedSelection),
      [result]
    )
    return { label: variant.label, verdict: report.verdict, issues: report.issues }
  })
  const falseReport = aggregateReport(
    aggregateState(croppedSelection),
    [{
      id: croppedDefinition.id,
      status: 'PASS',
      scopeComplete: false,
      subruns: croppedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }]
  )

  const caseDefinition = P0_CASES.find(({ id }) => id === 'AUTH-03')
  const caseSelection = { caseIds: [caseDefinition.id] }
  const completeCase = passingCase(caseDefinition)
  const completeCaseReport = aggregateReport(
    aggregateState(caseSelection),
    [completeCase]
  )
  const completeCasePlan = planResume(
    resumeState({ [caseDefinition.id]: completeCase }),
    P0_CASES,
    caseSelection
  )
  const incompleteCase = { ...completeCase, scopeComplete: false }
  const incompleteCaseReport = aggregateReport(
    aggregateState(caseSelection),
    [incompleteCase]
  )
  const incompleteCasePlan = planResume(
    resumeState({ [caseDefinition.id]: incompleteCase }),
    P0_CASES,
    caseSelection
  )

  assert.deepEqual(invalidReports, invalidScopes.map(({ label }) => ({
    label,
    verdict: 'FAIL',
    issues: [`INCOMPLETE_MATRIX: ${croppedDefinition.id}`]
  })))
  assert.equal(falseReport.verdict, 'PARTIAL_PASS')
  assert.equal(falseReport.scopeComplete, false)
  assert.deepEqual(falseReport.issues, [])
  assert.equal(completeCaseReport.verdict, 'PARTIAL_PASS')
  assert.deepEqual(completeCaseReport.issues, [])
  assert.equal(completeCasePlan.entries[0].action, 'SKIP')
  assert.equal(incompleteCaseReport.verdict, 'FAIL')
  assert.ok(incompleteCaseReport.issues.includes(`INCOMPLETE_MATRIX: ${caseDefinition.id}`))
  assert.equal(incompleteCasePlan.entries[0].action, 'RUN')
})

test('empty filtered selections fail instead of producing zero-evidence partial pass', () => {
  const fixtures = [
    { label: 'unknown caseIds', selection: { caseIds: ['UNKNOWN-99'] } },
    {
      label: 'profile and viewport with no matching subrun',
      selection: { profiles: ['FUNDING_ONLY'], viewports: ['mobile'] }
    }
  ]
  const actual = fixtures.map(({ label, selection }) => {
    const report = aggregateReport(aggregateState(selection), [])
    return {
      label,
      verdict: report.verdict,
      scopeComplete: report.scopeComplete,
      pass: report.counts.PASS,
      issues: report.issues
    }
  })

  assert.deepEqual(actual, fixtures.map(({ label }) => ({
    label,
    verdict: 'FAIL',
    scopeComplete: false,
    pass: 0,
    issues: ['EMPTY_SELECTION']
  })))
})

test('corrupt, duplicate, unexpected or non-terminal evidence cannot pass', () => {
  const registryless = aggregateReport({ selection: {} }, [])
  assert.equal(registryless.verdict, 'FAIL')
  assert.deepEqual(registryless.issues, ['MISSING_REGISTRY'])

  const definition = P0_CASES[0]
  const state = aggregateState({ caseIds: [definition.id] })
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
      aggregateState({ caseIds: [definition.id] }),
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

test('Surefire API validates time classes UTF-8 and declaration before trusting reports', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-api-boundary-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const validDirectory = join(root, 'valid')
  writeSurefireSuite(validDirectory, 'TEST-boundary.xml', {
    name: 'com.fxplatform.BoundaryIT'
  })
  const missingDirectory = join(root, 'missing')
  const capture = (run) => {
    try {
      return run()
    } catch (error) {
      return error.message
    }
  }

  const invalidTimes = {
    null: null,
    number: startedAt.getTime(),
    'invalid-date': new Date(Number.NaN),
    'date-only': '2026-07-14',
    'negative-year': '-000001-01-01T00:00:00.000Z',
    'expanded-year': '+010000-01-01T00:00:00.000Z'
  }
  const timeOutcomes = Object.fromEntries(Object.entries(invalidTimes).map(([label, value]) => [
    label,
    capture(() => parseSurefireReports(missingDirectory, ['BoundaryIT'], value).status)
  ]))

  let accessorCalls = 0
  const accessorClasses = []
  Object.defineProperty(accessorClasses, 0, {
    enumerable: true,
    get() {
      accessorCalls += 1
      return 'BoundaryIT'
    }
  })
  let proxyTrapCalls = 0
  const proxyClasses = new Proxy(['BoundaryIT'], {
    get(target, field, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const sparseClasses = ['BoundaryIT']
  sparseClasses.length = 2
  const classOutcomes = {
    'empty-duplicates': capture(() => parseSurefireReports(
      missingDirectory,
      ['', ''],
      startedAt
    ).status),
    malformed: capture(() => parseSurefireReports(
      missingDirectory,
      ['Bad.Name'],
      startedAt
    ).status),
    'extra-malformed': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', 'Bad.Name'],
      startedAt
    ).status),
    'undefined-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', undefined],
      startedAt
    ).status),
    'function-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', () => 'OtherIT'],
      startedAt
    ).status),
    'symbol-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', Symbol('OtherIT')],
      startedAt
    ).status),
    'sparse-element': capture(() => parseSurefireReports(
      missingDirectory,
      sparseClasses,
      startedAt
    ).status),
    accessor: capture(() => parseSurefireReports(
      missingDirectory,
      accessorClasses,
      startedAt
    ).status),
    proxy: capture(() => parseSurefireReports(
      missingDirectory,
      proxyClasses,
      startedAt
    ).status)
  }

  const invalidUtf8Directory = join(root, 'invalid-utf8')
  mkdirSync(invalidUtf8Directory)
  const invalidUtf8Path = join(invalidUtf8Directory, 'TEST-invalid-utf8.xml')
  writeFileSync(invalidUtf8Path, Buffer.concat([
    Buffer.from('<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.fxplatform.InvalidUtf8IT" tests="1" skipped="0" failures="0" errors="0"><!--'),
    Buffer.from([0xff]),
    Buffer.from('--></testsuite>')
  ]))
  utimesSync(invalidUtf8Path, new Date(), new Date())

  const wrongEncodingDirectory = join(root, 'wrong-encoding')
  mkdirSync(wrongEncodingDirectory)
  const wrongEncodingPath = join(wrongEncodingDirectory, 'TEST-wrong-encoding.xml')
  writeFileSync(wrongEncodingPath, [
    '<?xml version="1.0" encoding="UTF-16"?>',
    '<testsuite name="com.fxplatform.WrongEncodingIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>'
  ].join('\n'))
  utimesSync(wrongEncodingPath, new Date(), new Date())

  const reportOutcomes = {
    invalidUtf8: capture(() => parseSurefireReports(
      invalidUtf8Directory,
      ['InvalidUtf8IT'],
      startedAt
    ).status),
    wrongEncoding: capture(() => parseSurefireReports(
      wrongEncodingDirectory,
      ['WrongEncodingIT'],
      startedAt
    ).status),
    validDate: capture(() => parseSurefireReports(
      validDirectory,
      ['BoundaryIT'],
      startedAt
    ).status),
    validString: capture(() => parseSurefireReports(
      validDirectory,
      ['BoundaryIT'],
      startedAt.toISOString()
    ).status)
  }

  assert.deepEqual(timeOutcomes, Object.fromEntries(
    Object.keys(invalidTimes).map((label) => [label, 'SUREFIRE_INVALID_INVOCATION_TIME'])
  ))
  assert.deepEqual(classOutcomes, {
    'empty-duplicates': 'SUREFIRE_EXPECTED_CLASSES_DUPLICATE: ',
    malformed: 'SUREFIRE_EXPECTED_CLASS_INVALID: Bad.Name',
    'extra-malformed': 'SUREFIRE_EXPECTED_CLASS_INVALID: Bad.Name',
    'undefined-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'function-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'symbol-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'sparse-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    accessor: 'UNSAFE_PERSISTENCE_VALUE: accessor',
    proxy: 'UNSAFE_PERSISTENCE_VALUE: Proxy'
  })
  assert.equal(accessorCalls, 0)
  assert.equal(proxyTrapCalls, 0)
  assert.deepEqual(reportOutcomes, {
    invalidUtf8: 'SUREFIRE_MALFORMED_XML: TEST-invalid-utf8.xml',
    wrongEncoding: 'SUREFIRE_MALFORMED_XML: TEST-wrong-encoding.xml',
    validDate: 'PASS',
    validString: 'PASS'
  })
})

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

const INVALID_XML_CHARACTER_DATA_REPORTS = {
  'bare-ampersand': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out>invalid & text</system-out>',
    '</testsuite>'
  ].join('\n'),
  'invalid-named-entity': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out>invalid &notAnXmlEntity; text</system-out>',
    '</testsuite>'
  ].join('\n'),
  'internal-xml-declaration': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<?xml version="1.0"?>',
    '</testsuite>'
  ].join('\n')
}

for (const [scenario, source] of Object.entries(INVALID_XML_CHARACTER_DATA_REPORTS)) {
  test(`Surefire parser rejects invalid XML character data or internal declaration: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-character-data-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['CharacterDataIT'],
        new Date(Date.now() - 5_000)
      ),
      new RegExp(`^Error: SUREFIRE_MALFORMED_XML: ${fileName}$`)
    )
  })
}

test('Surefire scanner rejects illegal CharData and XML whitespace', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-xml-whitespace-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const suite = (inner = '', openingWhitespace = ' ', attributeWhitespace = ' ', closingWhitespace = '') => [
    `<testsuite${openingWhitespace}name${attributeWhitespace}="com.fxplatform.XmlWhitespaceIT" tests="1" skipped="0" failures="0" errors="0">`,
    inner,
    `</testsuite${closingWhitespace}>`
  ].filter(Boolean).join('\n')
  const invalid = {
    'literal-cdata-close-in-text': suite('<system-out>illegal ]]> text</system-out>'),
    'vertical-tab-opening-whitespace': suite('', '\u000b'),
    'form-feed-attribute-whitespace': suite('', ' ', '\u000c'),
    'vertical-tab-closing-whitespace': suite('', ' ', ' ', '\u000b'),
    'invalid-codepoint-in-markup': suite('', '\u0001')
  }
  const actual = Object.entries(invalid).map(([scenario, source]) => {
    const directory = join(root, scenario)
    mkdirSync(directory, { recursive: true })
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)
    try {
      parseSurefireReports(directory, ['XmlWhitespaceIT'], startedAt)
      return { scenario, error: null }
    } catch (error) {
      return { scenario, error: error.message }
    }
  })

  const validDirectory = join(root, 'valid')
  mkdirSync(validDirectory, { recursive: true })
  writeFileSync(join(validDirectory, 'TEST-valid-whitespace.xml'), [
    '<?xml\tversion="1.0"\r\nencoding="UTF-8"?>',
    '<testsuite \tname = "com.fxplatform.ValidWhitespaceIT"\r tests = "1"\n skipped="0" failures="0" errors="0" >',
    '<!-- valid comment -->',
    '<system-out><![CDATA[safe <xml> & cdata]]></system-out>',
    '</testsuite \t\r\n>'
  ].join('\n'))
  const valid = parseSurefireReports(validDirectory, ['ValidWhitespaceIT'], startedAt)

  assert.deepEqual(actual, Object.keys(invalid).map((scenario) => ({
    scenario,
    error: `SUREFIRE_MALFORMED_XML: TEST-${scenario}.xml`
  })))
  assert.deepEqual(valid.totals, { tests: 1, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire outcome contract rejects invalid XML code points inside CDATA', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-cdata-codepoint-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const fileName = 'TEST-invalid-cdata-codepoint.xml'
  writeFileSync(join(directory, fileName), [
    '<testsuite name="com.fxplatform.CdataCodePointIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out><![CDATA[invalid \u0001 cdata]]></system-out>',
    '</testsuite>'
  ].join('\n'))

  assert.throws(
    () => parseSurefireReports(
      directory,
      ['CdataCodePointIT'],
      new Date(Date.now() - 5_000)
    ),
    /^Error: SUREFIRE_MALFORMED_XML: TEST-invalid-cdata-codepoint\.xml$/
  )
})

for (const outcome of ['failure', 'error', 'skipped']) {
  test(`Surefire outcome contract rejects zero counters with a real ${outcome} element`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-outcome-element-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    writeFileSync(join(directory, `TEST-${outcome}.xml`), [
      '<testsuite name="com.fxplatform.OutcomeElementIT" tests="1" skipped="0" failures="0" errors="0">',
      '<testcase name="contract">',
      `<${outcome}>actual outcome</${outcome}>`,
      '</testcase>',
      '</testsuite>'
    ].join('\n'))

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['OutcomeElementIT'],
        new Date(Date.now() - 5_000)
      ),
      /^Error: SUREFIRE_INVALID_SUITE: OutcomeElementIT$/
    )
  })
}

test('Surefire outcome contract ignores fake outcome text and accepts a zero-outcome suite', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-outcome-text-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  writeFileSync(join(directory, 'TEST-outcome-text.xml'), [
    '<testsuite name="com.fxplatform.OutcomeTextIT" tests="1" skipped="0" failures="0" errors="0">',
    '<!-- <failure>comment-only</failure> -->',
    '<system-out>',
    '<![CDATA[<error>cdata-only</error>]]>',
    '&lt;skipped/&gt; escaped-text-only',
    '</system-out>',
    '</testsuite>'
  ].join('\n'))

  const parsed = parseSurefireReports(
    directory,
    ['OutcomeTextIT'],
    new Date(Date.now() - 5_000)
  )

  assert.equal(parsed.status, 'PASS')
  assert.deepEqual(parsed.totals, { tests: 1, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire parser rejects malformed comments DOCTYPE and declarations', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-xml-subset-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const suite = (inner = '') => [
    '<testsuite name="com.fxplatform.XmlSubsetIT" tests="1" skipped="0" failures="0" errors="0">',
    inner,
    '</testsuite>'
  ].filter(Boolean).join('\n')
  const invalid = {
    'comment-double-hyphen': suite('<!-- illegal -- comment -->'),
    'comment-control-codepoint': suite('<!-- illegal \u0001 comment -->'),
    doctype: `<!DOCTYPE >\n${suite()}`,
    'unsupported-version': `<?xml version="2.0"?>\n${suite()}`,
    'unknown-declaration-attribute': `<?xml version="1.0" feature="unsupported"?>\n${suite()}`,
    'invalid-declaration-encoding': `<?xml version="1.0" encoding="UTF 8"?>\n${suite()}`,
    'invalid-declaration-standalone': `<?xml version="1.0" standalone="maybe"?>\n${suite()}`
  }
  const actual = Object.entries(invalid).map(([scenario, source]) => {
    const directory = join(root, scenario)
    mkdirSync(directory, { recursive: true })
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)
    try {
      parseSurefireReports(directory, ['XmlSubsetIT'], startedAt)
      return { scenario, error: null }
    } catch (error) {
      return { scenario, error: error.message }
    }
  })

  const validDirectory = join(root, 'valid')
  mkdirSync(validDirectory, { recursive: true })
  writeFileSync(join(validDirectory, 'TEST-xml-10.xml'), [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<testsuite name="com.fxplatform.ValidXml10IT" tests="1" skipped="0" failures="0" errors="0">',
    '<!-- valid-comment -->',
    '<![CDATA[safe <xml> & raw cdata]]>',
    '</testsuite>'
  ].join('\n'))
  writeFileSync(join(validDirectory, 'TEST-xml-11.xml'), [
    "<?xml version='1.1' encoding='UTF-8' standalone='no'?>",
    '<testsuite name="com.fxplatform.ValidXml11IT" tests="1" skipped="0" failures="0" errors="0"></testsuite>'
  ].join('\n'))
  const valid = parseSurefireReports(
    validDirectory,
    ['ValidXml10IT', 'ValidXml11IT'],
    startedAt
  )

  assert.deepEqual(actual, Object.keys(invalid).map((scenario) => ({
    scenario,
    error: `SUREFIRE_MALFORMED_XML: TEST-${scenario}.xml`
  })))
  assert.deepEqual(valid.totals, { tests: 2, skipped: 0, failures: 0, errors: 0 })
})

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

test('Surefire freshness rejects materially future reports but allows timestamp tolerance', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-future-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)

  const futureDir = join(root, 'future')
  writeSurefireSuite(
    futureDir,
    'TEST-future.xml',
    { name: 'com.fxplatform.FutureIT' },
    new Date(Date.now() + 60_000)
  )
  assert.throws(
    () => parseSurefireReports(futureDir, ['FutureIT'], startedAt),
    /^Error: SUREFIRE_FUTURE_REPORT: FutureIT$/
  )

  const toleranceDir = join(root, 'tolerance')
  writeSurefireSuite(
    toleranceDir,
    'TEST-tolerance.xml',
    { name: 'com.fxplatform.TimestampToleranceIT' },
    new Date(Date.now() + 1_000)
  )
  const tolerated = parseSurefireReports(
    toleranceDir,
    ['TimestampToleranceIT'],
    startedAt
  )
  assert.equal(tolerated.status, 'PASS')
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

const INVALID_SUREFIRE_COUNTER_LEXEMES = {
  'empty-zero-counters': { tests: '1', skipped: '', failures: '', errors: '' },
  'scientific-tests': { tests: '1e0', skipped: '0', failures: '0', errors: '0' }
}

for (const [scenario, counters] of Object.entries(INVALID_SUREFIRE_COUNTER_LEXEMES)) {
  test(`Surefire parser rejects non-decimal counter evidence: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-counter-lexeme-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    writeSurefireSuite(directory, `TEST-${scenario}.xml`, {
      name: 'com.fxplatform.CounterLexicalIT',
      ...counters
    })

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['CounterLexicalIT'],
        new Date(Date.now() - 5_000)
      ),
      /^Error: SUREFIRE_INVALID_SUITE: CounterLexicalIT$/
    )
  })
}

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
    error: 'SUREFIRE_STALE_REPORT'
  })
})

test('artifact CLI emits stable secret-free diagnostics for caller-controlled failures', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-safe-diagnostics-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const markers = {
    unknownCommand: 'ITEM5_UNKNOWN_COMMAND_SECRET_19ac',
    malformedOption: 'ITEM5_MALFORMED_OPTION_SECRET_2abd',
    reportsPath: 'ITEM5_REPORTS_PATH_SECRET_3bce',
    outputPath: 'ITEM5_OUTPUT_PATH_SECRET_4cdf',
    xmlFilename: 'ITEM5_XML_FILENAME_SECRET_5de0',
    writeError: 'ITEM5_WRITE_ERROR_SECRET_6ef1'
  }
  const markerValues = Object.values(markers)
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  const assertSecretFree = (value) => {
    for (const marker of markerValues) assert.equal(value.includes(marker), false, marker)
  }
  const execute = (...arguments_) => spawnSync(process.execPath, [
    artifactsScript,
    ...arguments_
  ], { encoding: 'utf8' })
  const assertFailure = (execution, code, output) => {
    assert.equal(execution.status, 1)
    assert.match(execution.stderr, new RegExp(`(?:^|\\n)${code}(?:\\n|$)`))
    assertSecretFree(execution.stderr)
    if (!output) return
    const source = readFileSync(output, 'utf8')
    assertSecretFree(source)
    assert.deepEqual(JSON.parse(source), { status: 'FAIL', error: code })
  }

  const unknownOutput = join(root, 'unknown.json')
  assertFailure(
    execute(`unknown-${markers.unknownCommand}`, `--output=${unknownOutput}`),
    'CLI_UNKNOWN_COMMAND',
    unknownOutput
  )

  const malformedOutput = join(root, 'malformed.json')
  assertFailure(
    execute('verify-surefire', `--malformed-${markers.malformedOption}`, `--output=${malformedOutput}`),
    'CLI_MALFORMED_OPTION',
    malformedOutput
  )

  const missingReports = join(root, `reports-${markers.reportsPath}`)
  const pathOutput = join(root, `output-${markers.outputPath}`, 'gate.json')
  assertFailure(execute(
    'verify-surefire',
    `--reports=${missingReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${pathOutput}`
  ), 'CLI_INTERNAL_ERROR', pathOutput)

  const malformedReports = join(root, 'malformed-reports')
  mkdirSync(malformedReports)
  writeFileSync(join(malformedReports, `TEST-${markers.xmlFilename}.xml`), '<testsuite')
  const xmlOutput = join(root, 'malformed-xml.json')
  assertFailure(execute(
    'verify-surefire',
    `--reports=${malformedReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${xmlOutput}`
  ), 'SUREFIRE_MALFORMED_XML', xmlOutput)

  const validReports = join(root, 'valid-reports')
  writeSurefireSuite(validReports, 'TEST-safe-diagnostic.xml', {
    name: 'com.fxplatform.SafeDiagnosticIT'
  })
  const blockedParent = join(root, `blocked-${markers.writeError}`)
  writeFileSync(blockedParent, 'not-a-directory')
  const blockedOutput = join(blockedParent, 'gate.json')
  const writeExecution = execute(
    'verify-surefire',
    `--reports=${validReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${blockedOutput}`
  )
  assertFailure(writeExecution, 'CLI_WRITE_FAILED')
  assert.equal(existsSync(blockedOutput), false)
})

test('strict artifact CLI rejects a missing command', () => {
  const execution = spawnSync(process.execPath, [artifactsScript], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.match(execution.stderr, /CLI_COMMAND_REQUIRED/)
})

test('strict artifact CLI rejects an unknown command and replaces a supplied gate', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-unknown-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const output = join(root, 'gate.json')
  writeFileSync(output, '{"status":"PASS"}\n')

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'typo-command',
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_UNKNOWN_COMMAND'
  })
})

const INVALID_CLI_OPTION_FIXTURES = [
  {
    label: 'malformed option',
    option: '--reports',
    error: 'CLI_MALFORMED_OPTION'
  },
  {
    label: 'duplicate option',
    option: '--classes=StrictCliIT',
    error: 'CLI_DUPLICATE_OPTION'
  },
  {
    label: 'unknown option',
    option: '--extra=value',
    error: 'CLI_UNKNOWN_OPTION'
  },
  {
    label: 'empty option',
    option: '--classes=',
    error: 'CLI_OPTION_REQUIRED'
  }
]

for (const fixture of INVALID_CLI_OPTION_FIXTURES) {
  test(`strict artifact CLI rejects ${fixture.label} and replaces stale PASS`, (t) => {
    const root = mkdtempSync(join(tmpdir(), 'p0-cli-options-'))
    t.after(() => rmSync(root, { recursive: true, force: true }))
    const reports = join(root, 'reports')
    const output = join(root, 'gate.json')
    const startedAt = new Date(Date.now() - 5_000).toISOString()
    writeSurefireSuite(reports, 'TEST-strict.xml', { name: 'com.fxplatform.StrictCliIT' })
    writeFileSync(output, '{"status":"PASS"}\n')
    const options = [
      `--reports=${reports}`,
      '--classes=StrictCliIT',
      `--started-at=${startedAt}`,
      `--output=${output}`,
      fixture.option
    ]
    if (fixture.option === '--reports') options.shift()
    if (fixture.option === '--classes=') options.splice(1, 1)

    const execution = spawnSync(process.execPath, [
      artifactsScript,
      'verify-surefire',
      ...options
    ], { encoding: 'utf8' })

    assert.equal(execution.status, 1)
    assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
      status: 'FAIL',
      error: fixture.error
    })
  })
}

test('strict artifact CLI writes duplicate normalized output failure only to a unique target', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-duplicate-output-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gate.json')
  const equivalentOutput = `${join(root, 'nested')}${sep}..${sep}gate.json`
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-output.xml', { name: 'com.fxplatform.OutputCliIT' })
  writeFileSync(output, '{"status":"PASS"}\n')

  const duplicateExecution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=OutputCliIT',
    `--started-at=${startedAt}`,
    `--output=${output}`,
    `--output=${equivalentOutput}`
  ], { encoding: 'utf8' })

  assert.equal(duplicateExecution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })

  const firstOutput = join(root, 'first.json')
  const secondOutput = join(root, 'second.json')
  const firstBaseline = '{"status":"PASS","target":"first"}\n'
  const secondBaseline = '{"status":"PASS","target":"second"}\n'
  writeFileSync(firstOutput, firstBaseline)
  writeFileSync(secondOutput, secondBaseline)
  const conflictingExecution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=OutputCliIT',
    `--started-at=${startedAt}`,
    `--output=${firstOutput}`,
    `--output=${secondOutput}`
  ], { encoding: 'utf8' })

  assert.equal(conflictingExecution.status, 1)
  assert.equal(readFileSync(firstOutput, 'utf8'), firstBaseline)
  assert.equal(readFileSync(secondOutput, 'utf8'), secondBaseline)
})

test('strict artifact CLI replaces every hardlink alias with duplicate output failure', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-hardlink-output-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-hardlink.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT'
  })
  const firstOutput = join(root, 'hardlink-a.json')
  const secondOutput = join(root, 'hardlink-b.json')
  const stalePass = '{"status":"PASS","stale":"hardlink"}\n'
  writeFileSync(firstOutput, stalePass)
  linkSync(firstOutput, secondOutput)

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT',
    `--started-at=${startedAt}`,
    `--output=${firstOutput}`,
    `--output=${secondOutput}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1, execution.stderr)
  for (const output of [firstOutput, secondOutput]) {
    const source = readFileSync(output, 'utf8')
    assert.notEqual(source, stalePass)
    assert.deepEqual(JSON.parse(source), {
      status: 'FAIL',
      error: 'CLI_DUPLICATE_OPTION'
    })
  }
})

test('strict artifact CLI resolves output filesystem identities without guessing targets', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-output-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-identity.xml', {
    name: 'com.fxplatform.IdentityCliIT'
  })
  const baseArguments = [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=IdentityCliIT',
    `--started-at=${startedAt}`
  ]
  const execute = (...outputArguments) => spawnSync(process.execPath, [
    ...baseArguments,
    ...outputArguments
  ], { encoding: 'utf8' })

  const identityDirectory = join(root, 'identity-target')
  const identityOutput = join(identityDirectory, 'gate.json')
  mkdirSync(identityDirectory)
  writeFileSync(identityOutput, '{"status":"PASS","stale":"identity"}\n')
  let identityAliases
  if (process.platform === 'win32') {
    identityAliases = [identityOutput, identityOutput.toUpperCase()]
  } else {
    const firstParentAlias = join(root, 'identity-parent-a')
    const secondParentAlias = join(root, 'identity-parent-b')
    symlinkSync(identityDirectory, firstParentAlias, 'dir')
    symlinkSync(identityDirectory, secondParentAlias, 'dir')
    identityAliases = [
      join(firstParentAlias, 'gate.json'),
      join(secondParentAlias, 'gate.json')
    ]
  }
  const identityExecution = execute(
    `--output=${identityAliases[0]}`,
    `--output=${identityAliases[1]}`
  )

  const symlinkDirectory = join(root, 'symlink-target')
  const symlinkTarget = join(symlinkDirectory, 'gate.json')
  mkdirSync(symlinkDirectory)
  writeFileSync(symlinkTarget, '{"status":"PASS","stale":"symlink"}\n')
  let symlinkOutput
  if (process.platform === 'win32') {
    const symlinkParent = join(root, 'symlink-parent')
    symlinkSync(symlinkDirectory, symlinkParent, 'junction')
    symlinkOutput = join(symlinkParent, 'gate.json')
  } else {
    symlinkOutput = join(root, 'gate-link.json')
    symlinkSync(symlinkTarget, symlinkOutput, 'file')
  }
  const symlinkExecution = execute(
    `--output=${symlinkOutput}`,
    `--output=${symlinkTarget}`
  )

  const missingOutputExecution = execute()
  const malformedOutputExecution = execute('--output')

  const normalDirectory = join(root, 'normal-target')
  const normalParentAlias = join(root, 'normal-parent')
  const normalTarget = join(normalDirectory, 'gate.json')
  mkdirSync(normalDirectory)
  symlinkSync(
    normalDirectory,
    normalParentAlias,
    process.platform === 'win32' ? 'junction' : 'dir'
  )
  const normalExecution = execute(`--output=${join(normalParentAlias, 'gate.json')}`)

  assert.equal(identityExecution.status, 1, identityExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(identityOutput, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })
  assert.equal(symlinkExecution.status, 1, symlinkExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(symlinkTarget, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })
  assert.equal(missingOutputExecution.status, 1)
  assert.match(missingOutputExecution.stderr, /CLI_OPTION_REQUIRED/)
  assert.equal(malformedOutputExecution.status, 1)
  assert.match(malformedOutputExecution.stderr, /CLI_MALFORMED_OPTION/)
  assert.equal(normalExecution.status, 0, normalExecution.stderr)
  assert.equal(JSON.parse(readFileSync(normalTarget, 'utf8')).status, 'PASS')
})

test('strict artifact CLI requires canonical started-at UTC instant', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-started-at-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  writeSurefireSuite(reports, 'TEST-started-at.xml', {
    name: 'com.fxplatform.StartedAtCliIT'
  })
  const invalidValues = [
    '0',
    '-000001-01-01T00:00:00.000Z',
    '+010000-01-01T00:00:00.000Z',
    '2026-07-14',
    '2026-07-14T12:34:56.789',
    '2026-07-14T12:34:56.789+08:00',
    '2026-07-14T12:34:56Z'
  ]

  for (const [index, startedAt] of invalidValues.entries()) {
    const output = join(root, `invalid-${index}.json`)
    writeFileSync(output, '{"status":"PASS"}\n')
    const execution = spawnSync(process.execPath, [
      artifactsScript,
      'verify-surefire',
      `--reports=${reports}`,
      '--classes=StartedAtCliIT',
      `--started-at=${startedAt}`,
      `--output=${output}`
    ], { encoding: 'utf8' })

    assert.equal(execution.status, 1, startedAt)
    assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
      status: 'FAIL',
      error: 'CLI_INVALID_OPTION'
    }, startedAt)
  }

  const output = join(root, 'canonical.json')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=StartedAtCliIT',
    `--started-at=${startedAt}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).status, 'PASS')
})

test('strict artifact CLI executes through a filesystem alias', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-entry-alias-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const aliasDirectory = join(root, 'scripts-alias')
  symlinkSync(
    dirname(artifactsScript),
    aliasDirectory,
    process.platform === 'win32' ? 'junction' : 'dir'
  )
  const aliasScript = join(aliasDirectory, 'p0-user-trading-artifacts.mjs')
  const startedAt = new Date(Date.now() - 5_000).toISOString()

  const invalidReports = join(root, 'invalid-reports')
  const invalidOutput = join(root, 'invalid-gate.json')
  mkdirSync(invalidReports)
  writeFileSync(join(invalidReports, 'TEST-alias-malformed.xml'), '<testsuite')
  writeFileSync(invalidOutput, '{"status":"PASS","stale":true}\n')
  const invalidExecution = spawnSync(process.execPath, [
    aliasScript,
    'verify-surefire',
    `--reports=${invalidReports}`,
    '--classes=AliasCliIT',
    `--started-at=${startedAt}`,
    `--output=${invalidOutput}`
  ], { encoding: 'utf8' })

  const validReports = join(root, 'valid-reports')
  const validOutput = join(root, 'valid-gate.json')
  writeSurefireSuite(validReports, 'TEST-alias.xml', {
    name: 'com.fxplatform.AliasCliIT'
  })
  const validExecution = spawnSync(process.execPath, [
    aliasScript,
    'verify-surefire',
    `--reports=${validReports}`,
    '--classes=AliasCliIT',
    `--started-at=${startedAt}`,
    `--output=${validOutput}`
  ], { encoding: 'utf8' })

  assert.equal(invalidExecution.status, 1, invalidExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(invalidOutput, 'utf8')), {
    status: 'FAIL',
    error: 'SUREFIRE_MALFORMED_XML'
  })
  assert.equal(validExecution.status, 0, validExecution.stderr)
  assert.equal(JSON.parse(readFileSync(validOutput, 'utf8')).status, 'PASS')
})

test('strict artifact CLI module remains import-safe', () => {
  const moduleUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `await import(${JSON.stringify(moduleUrl)})`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
  assert.equal(execution.stderr, '')
})
