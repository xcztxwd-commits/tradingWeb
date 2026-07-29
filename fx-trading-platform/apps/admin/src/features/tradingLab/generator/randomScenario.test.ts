import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canonicalJson,
  normalizeScenario,
  scenarioFingerprint,
} from '../model/normalization.ts'
import type {
  MarketPathDefinition,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from '../model/types.ts'
import { validateScenario } from '../model/validation.ts'
import { compare, decimal } from '../oracle/decimal.ts'
import { calculateScenario } from '../oracle/runOracle.ts'
import { generateMarketTicks } from './marketPath.ts'
import {
  generateRandomScenario,
  type RandomScenarioInput,
} from './randomScenario.ts'

const VIRTUAL_START = '2026-07-25T00:00:00.000Z'
const SPOT_SYMBOL = 'XBT-USDT-LAB'
const PERPETUAL_SYMBOL = 'ETH-USDT-LAB'

function instrument(
  symbol: string,
  productType: 'CRYPTO_SPOT' | 'LINEAR_PERP',
  overrides: Partial<TradingLabInstrumentConfig> = {},
): TradingLabInstrumentConfig {
  const spot = productType === 'CRYPTO_SPOT'
  return {
    symbol,
    productType,
    baseAsset: spot ? 'XBT' : 'ETH',
    quoteAsset: 'USDT',
    tickSize: spot ? '0.01' : '0.1',
    stepSize: spot ? '0.1' : '0.01',
    pricePrecision: spot ? 2 : 1,
    quantityPrecision: spot ? 1 : 2,
    minQty: spot ? '0.1' : '0.01',
    maxQty: '100',
    minNotional: '1',
    maxNotional: '1000000',
    initialMarginRate: spot ? '1' : '0.1',
    maintenanceMarginRate: spot ? '0' : '0.05',
    liquidationFeeRate: '0.002',
    fixedFundingRate: spot ? '0' : '0.0001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: spot ? 'quote_mid' : 'provider_mark',
    contractSize: '1',
    maxLeverage: spot ? 1 : 20,
    defaultLeverage: spot ? 1 : 5,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'TIER_1',
    ...overrides,
  }
}

function initialPath(): MarketPathDefinition {
  return {
    virtualStart: VIRTUAL_START,
    realistic: false,
    instruments: [
      {
        mode: 'SIMPLE',
        productType: 'CRYPTO_SPOT',
        symbol: SPOT_SYMBOL,
        seed: 'base-spot-path',
        last: {
          start: '100',
          segments: [{
            target: '101',
            durationSeconds: 10,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        spreadSteps: 2,
        indexOffsetSteps: 0,
        basisSteps: 0,
      },
      {
        mode: 'SIMPLE',
        productType: 'LINEAR_PERP',
        symbol: PERPETUAL_SYMBOL,
        seed: 'base-perpetual-path',
        last: {
          start: '100',
          segments: [{
            target: '102',
            durationSeconds: 10,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        spreadSteps: 2,
        indexOffsetSteps: 0,
        basisSteps: 1,
        fundingRate: '0.0001',
      },
    ],
  }
}

function baseScenario(): TradingLabScenario {
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.0002',
    takerFeeRate: '0.0005',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  return {
    id: 'random-scenario-fixture',
    name: 'Random scenario fixture',
    description: '',
    negativeMode: false,
    seed: 'base-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments: [
        instrument(SPOT_SYMBOL, 'CRYPTO_SPOT'),
        instrument(PERPETUAL_SYMBOL, 'LINEAR_PERP'),
      ],
    },
    configSnapshotHash: 'd'.repeat(64),
    executionPolicy,
    initialBalances: {
      USDT: '10000',
      XBT: '10',
    },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 5,
    },
    symbols: [
      { symbol: SPOT_SYMBOL, productType: 'CRYPTO_SPOT' },
      { symbol: PERPETUAL_SYMBOL, productType: 'LINEAR_PERP' },
    ],
    marketPath: initialPath(),
    timeline: [],
  }
}

function input(
  seed = 'random-seed-a',
  negativeMode = false,
  base = baseScenario(),
): RandomScenarioInput {
  return {
    baseScenario: base,
    seed,
    actionCount: 6,
    durationSeconds: 12,
    realistic: true,
    negativeMode,
    priceRange: { min: '100', max: '200' },
    leverageRange: { min: 2, max: 10 },
    fundingRateRange: { min: '-0.001', max: '0.001' },
    feeRateRange: { min: '0.0001', max: '0.001' },
    offsetRangeSteps: { min: 0, max: 2 },
    volatilitySteps: { min: 1, max: 3 },
  }
}

function spotOnlyScenario(
  instrumentOverrides: Partial<TradingLabInstrumentConfig> = {},
  policyOverrides: Partial<TradingLabScenario['executionPolicy']> = {},
): TradingLabScenario {
  const base = structuredClone(baseScenario())
  base.symbols = [{ symbol: SPOT_SYMBOL, productType: 'CRYPTO_SPOT' }]
  base.configSnapshot.instruments = base.configSnapshot.instruments.map(
    (candidate) => candidate.productType === 'CRYPTO_SPOT'
      ? instrument(SPOT_SYMBOL, 'CRYPTO_SPOT', instrumentOverrides)
      : candidate,
  )
  base.executionPolicy = {
    ...base.executionPolicy,
    ...policyOverrides,
  }
  base.defaults.leverage = 1
  base.marketPath = {
    ...base.marketPath,
    instruments: base.marketPath.instruments.filter(
      (candidate) => candidate.productType === 'CRYPTO_SPOT',
    ),
  }
  return base
}

function perpetualOnlyScenario(
  instrumentOverrides: Partial<TradingLabInstrumentConfig> = {},
  policyOverrides: Partial<TradingLabScenario['executionPolicy']> = {},
): TradingLabScenario {
  const base = structuredClone(baseScenario())
  base.symbols = [{
    symbol: PERPETUAL_SYMBOL,
    productType: 'LINEAR_PERP',
  }]
  base.configSnapshot.instruments = base.configSnapshot.instruments.map(
    (candidate) => candidate.productType === 'LINEAR_PERP'
      ? instrument(PERPETUAL_SYMBOL, 'LINEAR_PERP', instrumentOverrides)
      : candidate,
  )
  base.executionPolicy = {
    ...base.executionPolicy,
    ...policyOverrides,
  }
  base.defaults.leverage = 1
  base.marketPath = {
    ...base.marketPath,
    instruments: base.marketPath.instruments.filter(
      (candidate) => candidate.productType === 'LINEAR_PERP',
    ),
  }
  delete base.initialBalances.XBT
  return base
}

function spotOnlyInput(
  seed: string,
  base: TradingLabScenario,
  negativeMode = false,
): RandomScenarioInput {
  return {
    ...input(seed, negativeMode, base),
    actionCount: 1,
    durationSeconds: 4,
    realistic: false,
    priceRange: { min: '10', max: '20' },
    leverageRange: { min: 1, max: 3 },
    feeRateRange: { min: '0.001', max: '0.001' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  }
}

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value)
    for (const child of Object.values(value)) {
      deepFreeze(child)
    }
  }
  return value
}

function inDecimalRange(value: string, min: string, max: string): boolean {
  return compare(decimal(value), decimal(min)) >= 0
    && compare(decimal(value), decimal(max)) <= 0
}

test('rebuilds byte-identical canonical scenarios without mutating frozen input', () => {
  const frozen = deepFreeze(input())
  const before = canonicalJson(frozen)

  const first = generateRandomScenario(frozen)
  const second = generateRandomScenario(frozen)

  assert.equal(
    canonicalJson(normalizeScenario(first)),
    canonicalJson(normalizeScenario(second)),
  )
  assert.equal(canonicalJson(frozen), before)
  assert.equal(first.seed, frozen.seed)
  assert.equal(first.configSnapshotHash, frozen.baseScenario.configSnapshotHash)
})

test('keeps maximum-length public seeds valid across internal random domains', () => {
  for (const seed of ['s'.repeat(256), '😀'.repeat(128)]) {
    const first = generateRandomScenario(input(seed))
    const replay = generateRandomScenario(input(seed))

    assert.equal(first.seed, seed)
    assert.equal(canonicalJson(first), canonicalJson(replay))
    assert.equal(
      first.marketPath.instruments.every(
        (path) => path.seed.length > 0 && path.seed.length <= 256,
      ),
      true,
    )
  }
})

test('selected non-colliding seeds change an action and a persisted path target', () => {
  const first = generateRandomScenario(input('random-seed-a'))
  const second = generateRandomScenario(input('random-seed-b'))

  assert.notEqual(
    canonicalJson(first.timeline),
    canonicalJson(second.timeline),
  )
  assert.notEqual(
    canonicalJson(first.marketPath.instruments.map((path) =>
      path.mode === 'SIMPLE'
        ? path.last.segments.map((segment) => segment.target)
        : path.prices)),
    canonicalJson(second.marketPath.instruments.map((path) =>
      path.mode === 'SIMPLE'
        ? path.last.segments.map((segment) => segment.target)
        : path.prices)),
  )
})

test('same random seed keeps the whole-scenario fingerprint and a new seed changes it', async () => {
  const first = generateRandomScenario(input('fingerprint-seed-a'))
  const replay = generateRandomScenario(input('fingerprint-seed-a'))
  const rerandomized = generateRandomScenario(input('fingerprint-seed-b'))

  assert.equal(
    await scenarioFingerprint(first),
    await scenarioFingerprint(replay),
  )
  assert.notEqual(
    await scenarioFingerprint(first),
    await scenarioFingerprint(rerandomized),
  )
})

test('keeps every generated SIMPLE price lane inside the requested price range', () => {
  const request: RandomScenarioInput = {
    ...input('range-edge-26'),
    realistic: false,
    priceRange: { min: '100', max: '102' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  }
  const scenario = generateRandomScenario(request)
  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })

  for (const tick of ticks) {
    for (const market of tick.instruments) {
      const prices = market.productType === 'LINEAR_PERP'
        ? [
            market.bid,
            market.ask,
            market.last,
            market.mark,
            market.index,
          ]
        : [market.bid, market.ask, market.last]
      for (const price of prices) {
        assert.equal(
          inDecimalRange(
            price,
            request.priceRange.min,
            request.priceRange.max,
          ),
          true,
          `${market.productType}/${market.symbol} emitted ${price}`,
        )
      }
    }
  }
})

test('uses fee, funding and leverage ranges and produces a legal calculable scenario', () => {
  const request = input()
  const scenario = generateRandomScenario(request)
  const errors = validateScenario(scenario)
    .filter((issue) => issue.severity === 'ERROR')

  assert.deepEqual(errors, [])
  assert.equal(
    inDecimalRange(
      scenario.executionPolicy.makerFeeRate,
      request.feeRateRange.min,
      request.feeRateRange.max,
    ),
    true,
  )
  assert.equal(
    inDecimalRange(
      scenario.executionPolicy.takerFeeRate,
      request.feeRateRange.min,
      request.feeRateRange.max,
    ),
    true,
  )
  assert.equal(
    scenario.defaults.leverage >= request.leverageRange.min
      && scenario.defaults.leverage <= request.leverageRange.max,
    true,
  )
  for (const path of scenario.marketPath.instruments) {
    if (path.productType === 'LINEAR_PERP') {
      if (path.fundingRate === undefined) {
        assert.fail(`${path.symbol} is missing its generated funding rate`)
      }
      assert.equal(
        inDecimalRange(
          path.fundingRate,
          request.fundingRateRange.min,
          request.fundingRateRange.max,
        ),
        true,
      )
    }
  }

  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  assert.equal(ticks.length, request.durationSeconds)
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })
  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('bounds legal Spot QUOTE order quantities to the public NUMERIC(24,8) request shape', () => {
  const scenario = generateRandomScenario({
    ...spotOnlyInput(
      'phase4-browser-core',
      spotOnlyScenario({
        tickSize: '0.000001',
        stepSize: '0.001',
        pricePrecision: 6,
        quantityPrecision: 3,
        minQty: '0.001',
      }),
    ),
    actionCount: 8,
    durationSeconds: 30,
    realistic: true,
    priceRange: { min: '100', max: '200' },
    feeRateRange: { min: '0.0001', max: '0.001' },
    offsetRangeSteps: { min: 0, max: 2 },
    volatilitySteps: { min: 1, max: 3 },
  })
  const quoteBuys = scenario.timeline.filter((action) => (
    action.productType === 'CRYPTO_SPOT'
    && action.type === 'PLACE_ORDER'
    && action.parameters.side === 'BUY'
    && action.parameters.quantityUnit === 'QUOTE'
  ))

  assert.ok(quoteBuys.length > 0)
  for (const action of quoteBuys) {
    const quantity = decimal(action.parameters.quantity as string)
    const integerDigits = Math.max(
      1,
      (quantity.coefficient < 0n
        ? -quantity.coefficient
        : quantity.coefficient
      ).toString().length - quantity.scale,
    )
    assert.equal(
      quantity.scale <= 8,
      true,
      `${action.id} emitted ${action.parameters.quantity}`,
    )
    assert.equal(
      integerDigits <= 16,
      true,
      `${action.id} emitted ${action.parameters.quantity}`,
    )
  }

  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks: generateMarketTicks({
        path: scenario.marketPath,
        configSnapshot: scenario.configSnapshot,
      }),
    },
  })
  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('covers backend component HALF_UP rounding in a Spot quote budget', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '0.00001',
      stepSize: '0.0001',
      pricePrecision: 5,
      quantityPrecision: 4,
      minQty: '0.0001',
      maxQty: '1',
      minNotional: null,
      maxNotional: null,
    },
    {
      slippageRate: '0',
      maxFillQuantityPerTick: '1',
    },
  )
  const scenario = generateRandomScenario({
    ...spotOnlyInput('tie-8', base),
    actionCount: 1,
    durationSeconds: 4,
    realistic: false,
    priceRange: { min: '0.00019', max: '0.00029' },
    leverageRange: { min: 1, max: 1 },
    fundingRateRange: { min: '0', max: '0' },
    feeRateRange: { min: '0.2', max: '0.2' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  })
  const [quoteBuy] = scenario.timeline
  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  const actionTick = quoteBuy?.trigger.type === 'VIRTUAL_TIME'
    ? ticks[quoteBuy.trigger.atSecond - 1]
    : undefined
  const spotTick = actionTick?.instruments[0]

  assert.equal(quoteBuy?.trigger.type, 'VIRTUAL_TIME')
  assert.equal(spotTick?.productType, 'CRYPTO_SPOT')
  assert.equal(
    spotTick?.productType === 'CRYPTO_SPOT' ? spotTick.ask : undefined,
    '0.00025',
  )
  assert.equal(quoteBuy?.parameters.quantity, '0.00000004')
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })
  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('fails closed when NUMERIC(24,8) granularity expands a Spot quote buy', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '0.00000001',
      stepSize: '0.00000001',
      pricePrecision: 8,
      quantityPrecision: 8,
      minQty: '0.00000001',
      maxQty: '0.00000001',
      minNotional: null,
      maxNotional: null,
    },
    {
      maxFillQuantityPerTick: '0.00000001',
    },
  )
  const request = {
    ...spotOnlyInput('numeric-granularity', base),
    priceRange: { min: '0.000001', max: '0.000002' },
    feeRateRange: { min: '0', max: '0' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  } satisfies RandomScenarioInput

  assert.throws(
    () => generateRandomScenario(request),
    /NUMERIC\(24,8\)|granularity|quote budget/i,
  )
})

test('fails closed below the backend storage-compatible Spot quantity step', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '1',
      stepSize: '0.00000001',
      pricePrecision: 0,
      quantityPrecision: 8,
      minQty: '0.00000001',
      maxQty: '0.00000001',
      minNotional: null,
      maxNotional: null,
    },
    {
      maxFillQuantityPerTick: '0.00000001',
    },
  )
  const request = {
    ...spotOnlyInput('storage-compatible-step', base),
    priceRange: { min: '100', max: '200' },
    feeRateRange: { min: '0', max: '0' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  } satisfies RandomScenarioInput

  assert.throws(
    () => generateRandomScenario(request),
    /persistence|storage-compatible|quote budget/i,
  )
})

test('fails closed when max-fill leaves no legal generated quantity', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '1',
      stepSize: '1',
      pricePrecision: 0,
      quantityPrecision: 0,
      minQty: '1',
      maxQty: '10',
      minNotional: '1',
      maxNotional: '1000',
    },
    { maxFillQuantityPerTick: '0.5' },
  )

  assert.throws(
    () => generateRandomScenario(spotOnlyInput('max-fill-empty', base)),
    /maxFill|legal generated quantity/i,
  )
})

test('generates from quantity bounds when both notional bounds are absent', () => {
  const base = spotOnlyScenario({
    tickSize: '1',
    stepSize: '1',
    pricePrecision: 0,
    quantityPrecision: 0,
    minQty: '1',
    maxQty: '2',
    minNotional: null,
    maxNotional: null,
  })

  const scenario = generateRandomScenario(
    spotOnlyInput('absent-notional-bounds', base),
  )

  assert.deepEqual(
    validateScenario(scenario).filter((issue) => issue.severity === 'ERROR'),
    [],
  )
  assert.equal(scenario.timeline.length, 1)
})

test('generates within maxQty when the max-fill cap is absent', () => {
  const base = spotOnlyScenario({
    tickSize: '1',
    stepSize: '1',
    pricePrecision: 0,
    quantityPrecision: 0,
    minQty: '1',
    maxQty: '2',
    minNotional: null,
    maxNotional: null,
  })
  base.executionPolicy.maxFillQuantityPerTick = null
  base.configSnapshot.executionPolicy.maxFillQuantityPerTick = null

  const scenario = generateRandomScenario(
    spotOnlyInput('absent-max-fill-cap', base),
  )

  assert.deepEqual(
    validateScenario(scenario).filter((issue) => issue.severity === 'ERROR'),
    [],
  )
  assert.equal(scenario.timeline.length, 1)
})

test('uses final fee and slippage to create an exact legal Spot quote budget', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '1',
      stepSize: '1',
      pricePrecision: 0,
      quantityPrecision: 0,
      minQty: '1',
      maxQty: '10',
      minNotional: '1',
      maxNotional: '200',
    },
    {
      slippageRate: '0.1',
      maxFillQuantityPerTick: '1',
    },
  )
  const request = {
    ...spotOnlyInput('fee-slippage-budget', base),
    priceRange: { min: '100', max: '103' },
    feeRateRange: { min: '0.5', max: '0.5' },
  } satisfies RandomScenarioInput

  const scenario = generateRandomScenario(request)
  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })

  assert.deepEqual(
    validateScenario(scenario).filter((issue) => issue.severity === 'ERROR'),
    [],
  )
  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('funds high-fee high-slippage Perpetual action sequences conservatively', () => {
  const base = perpetualOnlyScenario(
    {
      tickSize: '1',
      stepSize: '1',
      pricePrecision: 0,
      quantityPrecision: 0,
      minQty: '1',
      maxQty: '10',
      minNotional: '1',
      maxNotional: '100000',
      maxLeverage: 1,
      defaultLeverage: 1,
    },
    {
      slippageRate: '0.99',
      maxFillQuantityPerTick: '1',
    },
  )
  const request = {
    ...input('probe-4', false, base),
    actionCount: 100,
    durationSeconds: 100,
    realistic: false,
    priceRange: { min: '100', max: '103' },
    leverageRange: { min: 1, max: 1 },
    fundingRateRange: { min: '0', max: '0' },
    feeRateRange: { min: '0.99', max: '0.99' },
    offsetRangeSteps: { min: 0, max: 0 },
    volatilitySteps: { min: 0, max: 0 },
  } satisfies RandomScenarioInput

  const scenario = generateRandomScenario(request)
  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })

  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('fails closed when fee-adjusted Spot quote budget has no legal fill', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '1',
      stepSize: '1',
      pricePrecision: 0,
      quantityPrecision: 0,
      minQty: '1',
      maxQty: '10',
      minNotional: '1',
      maxNotional: '150',
    },
    {
      slippageRate: '0.1',
      maxFillQuantityPerTick: '1',
    },
  )
  const request = {
    ...spotOnlyInput('fee-budget-empty', base),
    priceRange: { min: '100', max: '103' },
    feeRateRange: { min: '0.5', max: '0.5' },
  } satisfies RandomScenarioInput

  assert.throws(
    () => generateRandomScenario(request),
    /fee|notional|legal generated quantity/i,
  )
})

test('negative mode changes exactly one Spot action into an expected oversell', () => {
  const scenario = generateRandomScenario(input('negative-seed', true))
  const expected = scenario.timeline.filter(
    (action) => action.expectedError !== undefined,
  )

  assert.equal(scenario.negativeMode, true)
  assert.equal(expected.length, 1)
  assert.equal(expected[0]?.productType, 'CRYPTO_SPOT')
  assert.deepEqual(expected[0]?.expectedError, {
    status: 400,
    code: 'INSUFFICIENT_BALANCE',
  })
  assert.deepEqual(
    validateScenario(scenario).filter((issue) => issue.severity === 'ERROR'),
    [],
  )

  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })
  assert.equal(outcome.status, 'BLOCKED')
})

test('negative generation fails closed when no authority-legal oversell exists', () => {
  const base = spotOnlyScenario(
    {
      tickSize: '1',
      stepSize: '1',
      pricePrecision: 0,
      quantityPrecision: 0,
      minQty: '1',
      maxQty: '2',
      minNotional: '1',
      maxNotional: '25',
    },
    { maxFillQuantityPerTick: '2' },
  )

  assert.throws(
    () => generateRandomScenario(
      spotOnlyInput('negative-notional-boundary', base, true),
    ),
    /oversell|legal generated quantity/i,
  )
})

test('fails closed when preserved base metadata is structurally invalid', () => {
  const base = baseScenario()
  base.configSnapshotHash = 'not-a-valid-hash'

  assert.throws(
    () => generateRandomScenario(input('invalid-base-metadata', false, base)),
    /configSnapshotHash|generated scenario|baseScenario/i,
  )
})

test('fails closed when a Spot-only leverage range excludes model value one', () => {
  const request = spotOnlyInput(
    'spot-only-leverage',
    spotOnlyScenario(),
  )

  assert.throws(
    () => generateRandomScenario({
      ...request,
      leverageRange: { min: 2, max: 3 },
    }),
    /leverageRange|Spot-only/i,
  )
})

test('negative generation fails closed when no Spot authority is selected', () => {
  const base = structuredClone(baseScenario())
  base.symbols = base.symbols.filter(
    (symbol) => symbol.productType === 'LINEAR_PERP',
  )
  base.configSnapshot.instruments = base.configSnapshot.instruments.filter(
    (candidate) => candidate.productType === 'LINEAR_PERP',
  )
  base.marketPath = {
    ...base.marketPath,
    instruments: base.marketPath.instruments.filter(
      (candidate) => candidate.productType === 'LINEAR_PERP',
    ),
  }
  delete base.initialBalances.XBT

  assert.throws(
    () => generateRandomScenario(input('negative-no-spot', true, base)),
    /Spot/i,
  )
})
