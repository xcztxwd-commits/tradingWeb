import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import type {
  MarketPathDefinition,
  TimelineAction,
  TimelineTrigger,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from '../model/types.ts'
import { calculateScenario } from './runOracle.ts'
import type {
  FundingRateTick,
  InstrumentTick,
  MarketTick,
} from './types.ts'

const VIRTUAL_START = '2026-01-01T00:00:00.000Z'

type MutableMarketTick = {
  sequence: number
  virtualTime: string
  instruments: InstrumentTick[]
  fundingRates: FundingRateTick[]
}

function instrument(
  productType: 'CRYPTO_SPOT' | 'LINEAR_PERP' = 'CRYPTO_SPOT',
  symbol = productType === 'CRYPTO_SPOT' ? 'XBT-USDT-LAB' : 'XBT-USDT-LAB-PERP',
): TradingLabInstrumentConfig {
  return {
    symbol,
    productType,
    baseAsset: 'XBT',
    quoteAsset: 'USDT',
    tickSize: '0.01',
    stepSize: '0.001',
    pricePrecision: 2,
    quantityPrecision: 3,
    minQty: '0.001',
    maxQty: '100',
    minNotional: '1',
    maxNotional: '1000000',
    initialMarginRate: productType === 'CRYPTO_SPOT' ? '1' : '0.1',
    maintenanceMarginRate: productType === 'CRYPTO_SPOT' ? '0' : '0.05',
    liquidationFeeRate: '0.002',
    fixedFundingRate: '0.0001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: productType === 'CRYPTO_SPOT' ? 'quote_mid' : 'provider_mark',
    contractSize: '1',
    maxLeverage: productType === 'CRYPTO_SPOT' ? 1 : 20,
    defaultLeverage: productType === 'CRYPTO_SPOT' ? 1 : 10,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'TIER_1',
  }
}

function simplePath(
  selected: TradingLabInstrumentConfig,
): MarketPathDefinition['instruments'][number] {
  return {
    mode: 'SIMPLE',
    productType: selected.productType,
    symbol: selected.symbol,
    seed: `oracle-path-${selected.productType}-${selected.symbol}`,
    last: {
      start: '100',
      segments: [{
        target: '100',
        durationSeconds: 60,
        offsetRangeSteps: 0,
        volatilitySteps: 0,
        maxStepPerSecond: 1,
      }],
    },
    spreadSteps: 2,
    indexOffsetSteps: 0,
    basisSteps: 0,
    ...(selected.productType === 'LINEAR_PERP'
      ? { fundingRate: selected.fixedFundingRate }
      : {}),
  }
}

function scenario(): TradingLabScenario {
  const selected = instrument()
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: '0.001',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  return {
    id: 'oracle-compiler',
    name: '显式 Tick compiler',
    description: '',
    negativeMode: false,
    seed: 'compiler-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments: [selected],
    },
    configSnapshotHash: 'a'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: VIRTUAL_START,
      realistic: false,
      instruments: [simplePath(selected)],
    },
    initialBalances: { USDT: '1000', XBT: '0' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: selected.symbol,
      productType: selected.productType,
    }],
    timeline: [],
  }
}

function tick(
  sequence: number,
  values: Partial<Record<'bid' | 'ask' | 'last', string>> = {},
): MutableMarketTick {
  const second = String(sequence).padStart(2, '0')
  return {
    sequence,
    virtualTime: `2026-01-01T00:00:${second}.000Z`,
    instruments: [{
      productType: 'CRYPTO_SPOT',
      symbol: 'XBT-USDT-LAB',
      bid: values.bid ?? String(99 + sequence),
      ask: values.ask ?? String(100 + sequence),
      last: values.last ?? String(100 + sequence),
    }],
    fundingRates: [],
  }
}

function marketOrder(
  id: string,
  sequence: number,
  trigger: TimelineTrigger,
): TimelineAction {
  return {
    id,
    sequence,
    type: 'PLACE_ORDER',
    symbol: 'XBT-USDT-LAB',
    productType: 'CRYPTO_SPOT',
    trigger,
    parameters: {
      side: 'BUY',
      orderType: 'MARKET',
      quantity: '10.01',
      quantityUnit: 'QUOTE',
    },
  }
}

test('uses the persisted scenario execution policy without changing config authority', () => {
  const input = scenario()
  input.configSnapshot.executionPolicy.takerFeeRate = '0.2'
  input.executionPolicy = {
    ...input.executionPolicy,
    takerFeeRate: '0.001',
  }
  input.timeline = [marketOrder(
    'action-1',
    1,
    { type: 'VIRTUAL_TIME', atSecond: 1 },
  )]

  const outcome = calculateScenario({
    scenario: input,
    market: { virtualStart: VIRTUAL_START, ticks: [tick(1)] },
  })

  assert.equal(outcome.status, 'CALCULATED')
  if (outcome.status === 'CALCULATED') {
    assert.equal(outcome.result.snapshots[0].trades[0].feeUsdt, '0.009999')
    assert.equal(input.configSnapshot.executionPolicy.takerFeeRate, '0.2')
  }
})

test('accepts equivalent UTC Z instants with zero to three fractional digits', () => {
  for (const virtualStart of [
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00.0Z',
    '2026-01-01T00:00:00.00Z',
    '2026-01-01T00:00:00.000Z',
  ]) {
    const input = scenario()
    input.marketPath = {
      ...input.marketPath,
      virtualStart,
    }
    input.timeline = [marketOrder(
      'action-1',
      1,
      { type: 'VIRTUAL_TIME', atSecond: 1 },
    )]

    const outcome = calculateScenario({
      scenario: input,
      market: {
        virtualStart,
        ticks: [tick(1)],
      },
    })

    assert.equal(
      outcome.status,
      'CALCULATED',
      outcome.status === 'BLOCKED'
        ? `${virtualStart}: ${JSON.stringify(outcome.issues)}`
        : undefined,
    )
  }
})

test('rejects non-Z invalid offset over-precision and misaligned Tick instants', () => {
  for (const virtualStart of [
    '2026-01-01T00:00:00',
    '2026-01-01T08:00:00+08:00',
    '2026-02-30T00:00:00Z',
    '2026-01-01T00:00:00.0000Z',
  ]) {
    const outcome = calculateScenario({
      scenario: scenario(),
      market: {
        virtualStart,
        ticks: [tick(1)],
      },
    })
    assert.equal(outcome.status, 'BLOCKED')
    if (outcome.status === 'BLOCKED') {
      assert.equal(
        outcome.issues.some((candidate) =>
          candidate.code === 'ORACLE_VIRTUAL_START_INVALID'),
        true,
        virtualStart,
      )
    }
  }

  for (const virtualTime of [
    '2026-01-01T00:00:01',
    '2026-01-01T08:00:01+08:00',
    '2026-02-30T00:00:01Z',
    '2026-01-01T00:00:01.0000Z',
    '2026-01-01T00:00:02.000Z',
  ]) {
    const invalidTick = tick(1)
    invalidTick.virtualTime = virtualTime
    const outcome = calculateScenario({
      scenario: scenario(),
      market: {
        virtualStart: VIRTUAL_START,
        ticks: [invalidTick],
      },
    })
    assert.equal(outcome.status, 'BLOCKED')
    if (outcome.status === 'BLOCKED') {
      assert.equal(
        outcome.issues.some((candidate) =>
          candidate.code === 'ORACLE_TICK_TIME_INVALID'),
        true,
        virtualTime,
      )
    }
  }
})

test('rejects missing duplicate non-contiguous and time-shifted explicit ticks before snapshot zero', () => {
  const input = scenario()
  input.timeline = [marketOrder(
    'action-1',
    1,
    { type: 'VIRTUAL_TIME', atSecond: 1 },
  )]

  const invalidMarkets = [
    {
      ticks: [null as never],
      code: 'ORACLE_TICK_SEQUENCE_INVALID',
    },
    {
      ticks: [tick(2)],
      code: 'ORACLE_TICK_SEQUENCE_INVALID',
    },
    {
      ticks: [tick(1), tick(3)],
      code: 'ORACLE_TICK_SEQUENCE_INVALID',
    },
    {
      ticks: [{
        ...tick(1),
        virtualTime: '2026-01-01T00:00:09.000Z',
      }],
      code: 'ORACLE_TICK_TIME_INVALID',
    },
    {
      ticks: [{
        ...tick(1),
        instruments: [
          tick(1).instruments[0],
          { ...tick(1).instruments[0] },
        ],
      }],
      code: 'ORACLE_TICK_INSTRUMENT_DUPLICATE',
    },
    {
      ticks: [{
        ...tick(1),
        instruments: [],
      }],
      code: 'ORACLE_TICK_INSTRUMENT_MISSING',
    },
  ]

  for (const invalid of invalidMarkets) {
    const outcome = calculateScenario({
      scenario: input,
      market: {
        virtualStart: VIRTUAL_START,
        ticks: invalid.ticks,
      },
    })
    assert.equal(outcome.status, 'BLOCKED')
    if (outcome.status === 'BLOCKED') {
      assert.equal(outcome.issues.some((issue) => issue.code === invalid.code), true)
      assert.equal('result' in outcome, false)
    }
  }
})

test('blocks duplicate or non-increasing action identity instead of calculating around it', () => {
  const input = scenario()
  input.timeline = [
    marketOrder('duplicate', 1, { type: 'VIRTUAL_TIME', atSecond: 1 }),
    marketOrder('duplicate', 1, { type: 'VIRTUAL_TIME', atSecond: 1 }),
  ]

  const outcome = calculateScenario({
    scenario: input,
    market: { virtualStart: VIRTUAL_START, ticks: [tick(1)] },
  })

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((candidate) =>
        candidate.code === 'ORACLE_SCENARIO_VALIDATION_FAILED'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
})

test('resolves VIRTUAL_TIME PRICE AFTER_ACTION and GROUP only against supplied ticks', () => {
  const input = scenario()
  input.timeline = [
    marketOrder('action-1', 1, { type: 'VIRTUAL_TIME', atSecond: 1 }),
    marketOrder('action-2', 2, {
      type: 'PRICE',
      priceType: 'ASK',
      operator: 'GTE',
      value: '102',
    }),
    marketOrder('action-3', 3, {
      type: 'AFTER_ACTION',
      actionId: 'action-2',
      delaySeconds: 1,
    }),
    marketOrder('action-4', 4, {
      type: 'GROUP',
      operator: 'ALL',
      items: [
        { type: 'VIRTUAL_TIME', atSecond: 2 },
        {
          type: 'PRICE',
          priceType: 'LAST',
          operator: 'GTE',
          value: '103',
        },
      ],
    }),
  ]

  const outcome = calculateScenario({
    scenario: input,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, { ask: '101', last: '101' }),
        tick(2, { ask: '102', last: '102' }),
        tick(3, { ask: '103', last: '103' }),
      ],
    },
  })

  assert.equal(outcome.status, 'CALCULATED')
  if (outcome.status === 'CALCULATED') {
    assert.deepEqual(
      outcome.result.snapshots.map((snapshot) => [
        snapshot.actionId,
        snapshot.tickSequence,
      ]),
      [
        ['action-1', 1],
        ['action-2', 2],
        ['action-3', 3],
        ['action-4', 3],
      ],
    )
  }
})

test('fails closed when a later action resolves to an earlier Tick', () => {
  const input = scenario()
  input.timeline = [
    marketOrder('action-1', 1, { type: 'VIRTUAL_TIME', atSecond: 2 }),
    marketOrder('action-2', 2, { type: 'VIRTUAL_TIME', atSecond: 1 }),
  ]

  const outcome = calculateScenario({
    scenario: input,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [tick(1), tick(2)],
    },
  })

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((candidate) =>
        candidate.code === 'ORACLE_ACTION_TICK_ORDER_INVALID'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
})

test('fails closed for unavailable price types unresolved triggers and unknown actions', () => {
  const unavailable = scenario()
  unavailable.timeline = [marketOrder('action-1', 1, {
    type: 'PRICE',
    priceType: 'MARK',
    operator: 'GTE',
    value: '1',
  })]

  const unresolved = scenario()
  unresolved.timeline = [marketOrder('action-1', 1, {
    type: 'PRICE',
    priceType: 'ASK',
    operator: 'GTE',
    value: '999',
  })]

  const unknown = scenario()
  unknown.timeline = [{
    id: 'action-1',
    sequence: 1,
    type: 'TELEPORT_BALANCE',
    symbol: 'XBT-USDT-LAB',
    productType: 'CRYPTO_SPOT',
    trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
    parameters: {},
  } as unknown as TimelineAction]

  const invalidOverride = scenario()
  invalidOverride.timeline = [marketOrder(
    'action-1',
    1,
    { type: 'VIRTUAL_TIME', atSecond: 1 },
  )]
  invalidOverride.timeline[0].overrides = 1 as never

  const hiddenActionField = scenario()
  hiddenActionField.timeline = [marketOrder(
    'action-1',
    1,
    { type: 'VIRTUAL_TIME', atSecond: 1 },
  )]
  Object.assign(hiddenActionField.timeline[0], { source: 'BACKEND' })

  const hiddenTickPrice = {
    ...tick(1),
    instruments: [{
      ...tick(1).instruments[0],
      mark: '999',
    }],
  } as unknown as MarketTick

  for (const [input, code] of [
    [unavailable, 'ORACLE_PRICE_TYPE_UNAVAILABLE'],
    [unresolved, 'ORACLE_TRIGGER_UNRESOLVED'],
    [unknown, 'ORACLE_ACTION_UNSUPPORTED'],
    [invalidOverride, 'ORACLE_ACTION_PARAMETERS_UNSUPPORTED'],
    [hiddenActionField, 'ORACLE_ACTION_FIELDS_UNSUPPORTED'],
  ] as const) {
    const outcome = calculateScenario({
      scenario: input,
      market: { virtualStart: VIRTUAL_START, ticks: [tick(1)] },
    })
    assert.equal(outcome.status, 'BLOCKED')
    if (outcome.status === 'BLOCKED') {
      assert.equal(outcome.issues.some((issue) => issue.code === code), true)
      assert.equal('result' in outcome, false)
    }
  }

  const hiddenTickOutcome = calculateScenario({
    scenario: scenario(),
    market: { virtualStart: VIRTUAL_START, ticks: [hiddenTickPrice] },
  })
  assert.equal(hiddenTickOutcome.status, 'BLOCKED')
  if (hiddenTickOutcome.status === 'BLOCKED') {
    assert.equal(
      hiddenTickOutcome.issues.some((candidate) =>
        candidate.code === 'ORACLE_TICK_INSTRUMENT_FIELDS_UNSUPPORTED'),
      true,
    )
  }
})

test('uses product plus symbol identity and never mutates inputs or earlier snapshots', () => {
  const input = scenario()
  const perp = instrument('LINEAR_PERP', 'XBT-USDT-LAB')
  input.configSnapshot.instruments.push(perp)
  input.symbols.push({
    symbol: perp.symbol,
    productType: perp.productType,
  })
  input.marketPath = {
    ...input.marketPath,
    instruments: [...input.marketPath.instruments, simplePath(perp)],
  }
  input.timeline = [marketOrder(
    'action-1',
    1,
    { type: 'VIRTUAL_TIME', atSecond: 1 },
  )]
  const explicitTick = tick(1)
  explicitTick.instruments.push({
    productType: 'LINEAR_PERP',
    symbol: 'XBT-USDT-LAB',
    bid: '100',
    ask: '101',
    last: '100.5',
    mark: '100.4',
    index: '100.3',
  } as never)
  const beforeScenario = structuredClone(input)
  const beforeTicks = structuredClone([explicitTick])

  const outcome = calculateScenario({
    scenario: input,
    market: { virtualStart: VIRTUAL_START, ticks: [explicitTick] },
  })

  assert.equal(outcome.status, 'CALCULATED')
  assert.deepEqual(input, beforeScenario)
  assert.deepEqual([explicitTick], beforeTicks)
  if (outcome.status === 'CALCULATED') {
    assert.equal(Object.isFrozen(outcome.result), true)
    assert.equal(Object.isFrozen(outcome.result.snapshots), true)
    assert.equal(Object.isFrozen(outcome.result.snapshots[0]), true)
    assert.throws(() => {
      ;(outcome.result.snapshots as unknown[]).push({})
    }, TypeError)
  }
})

test('Oracle production modules have no floating money generator or backend escape hatch', () => {
  const files = [
    './types.ts',
    './runOracle.ts',
    './spotOracle.ts',
    './perpetualOracle.ts',
    './riskOracle.ts',
  ]
  for (const file of files) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8')
    for (const token of [
      'parseFloat',
      'Number(',
      'Math.round',
      'Math.random',
      'Date.now',
      'performance.now',
      'crypto.randomUUID',
      'fetch(',
      'XMLHttpRequest',
      'import(',
      '/generator/',
      '\\generator\\',
      '/backend/',
      '\\backend\\',
      'node:crypto',
    ]) {
      assert.equal(source.includes(token), false, `${file}: ${token}`)
    }
  }
})

test('blocks Spot cash spend against current-Tick CROSS margin capacity', () => {
  const spot = instrument('CRYPTO_SPOT', 'XBT-SPOT-LAB')
  const perpetual = instrument('LINEAR_PERP', 'XBT-PERP-LAB')
  const input = scenario()
  input.configSnapshot.instruments = [spot, perpetual]
  input.initialBalances = { USDT: '100', XBT: '0' }
  input.symbols = [
    { symbol: spot.symbol, productType: spot.productType },
    { symbol: perpetual.symbol, productType: perpetual.productType },
  ]
  input.marketPath = {
    ...input.marketPath,
    instruments: [simplePath(spot), simplePath(perpetual)],
  }
  input.timeline = [
    {
      id: 'open-cross',
      sequence: 1,
      type: 'PLACE_ORDER',
      symbol: perpetual.symbol,
      productType: 'LINEAR_PERP',
      trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '1',
        quantityUnit: 'BASE',
        positionSide: 'BOTH',
        marginMode: 'CROSS',
        leverage: 10,
      },
    },
    {
      id: 'overspend-spot',
      sequence: 2,
      type: 'PLACE_ORDER',
      symbol: spot.symbol,
      productType: 'CRYPTO_SPOT',
      trigger: { type: 'VIRTUAL_TIME', atSecond: 2 },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '60',
        quantityUnit: 'QUOTE',
      },
    },
  ]
  const marketTicks: MarketTick[] = [
    {
      sequence: 1,
      virtualTime: '2026-01-01T00:00:01.000Z',
      instruments: [
        {
          productType: 'CRYPTO_SPOT',
          symbol: spot.symbol,
          bid: '99',
          ask: '100',
          last: '100',
        },
        {
          productType: 'LINEAR_PERP',
          symbol: perpetual.symbol,
          bid: '100',
          ask: '100',
          last: '100',
          mark: '100',
          index: '100',
        },
      ],
      fundingRates: [],
    },
    {
      sequence: 2,
      virtualTime: '2026-01-01T00:00:02.000Z',
      instruments: [
        {
          productType: 'CRYPTO_SPOT',
          symbol: spot.symbol,
          bid: '99',
          ask: '100',
          last: '100',
        },
        {
          productType: 'LINEAR_PERP',
          symbol: perpetual.symbol,
          bid: '60',
          ask: '60',
          last: '60',
          mark: '60',
          index: '60',
        },
      ],
      fundingRates: [],
    },
  ]
  const before = structuredClone({ input, marketTicks })

  const outcome = calculateScenario({
    scenario: input,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: marketTicks,
    },
  })

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((issue) =>
        issue.code === 'ORACLE_INSUFFICIENT_BALANCE'),
      true,
      [
        ...outcome.issues.map((issue) => issue.code),
        ...outcome.runnerIssues.map((issue) =>
          `${issue.code}:${issue.message}`),
      ].join(', '),
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual({ input, marketTicks }, before)
})
