import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  canRunScenario,
  isScenarioLocked,
  validateScenario,
} from './validation.ts'
import type {
  MarketPathDefinition,
  ScenarioValidationIssue,
  TradingLabInstrumentConfig,
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from './types.ts'

function config(productType: 'CRYPTO_SPOT' | 'LINEAR_PERP' = 'CRYPTO_SPOT'): TradingLabConfigSnapshot {
  return {
    modelVersion: 'model-v1',
    symbolConfigVersion: 'symbols-v1',
    codeVersion: 'code-v1',
    executionPolicy: {
      matchingMode: 'SIMPLE',
      makerFeeRate: '0.001',
      takerFeeRate: '0.002',
      liquidationFeeRate: '0.003',
      slippageRate: '0',
      maxFillQuantityPerTick: '100',
    },
    instruments: [{
      symbol: productType === 'CRYPTO_SPOT' ? 'BTCUSDT' : 'BTCUSDT-PERP',
      productType,
      baseAsset: 'BTC',
      quoteAsset: 'USDT',
      tickSize: '0.01',
      stepSize: '0.001',
      pricePrecision: 2,
      quantityPrecision: 3,
      minQty: '0.001',
      maxQty: '100',
      minNotional: '10',
      maxNotional: '1000000',
      initialMarginRate: '0.05',
      maintenanceMarginRate: '0.025',
      liquidationFeeRate: '0.003',
      fixedFundingRate: '0.0001',
      fixedFundingIntervalMinutes: 480,
      markPriceSource: productType === 'CRYPTO_SPOT' ? 'quote_mid' : 'provider_mark',
      contractSize: '1',
      maxLeverage: productType === 'CRYPTO_SPOT' ? 1 : 20,
      defaultLeverage: productType === 'CRYPTO_SPOT' ? 1 : 10,
      marginAsset: 'USDT',
      settlementAsset: 'USDT',
      riskTier: 'TIER_1',
    }],
  }
}

function simplePath(
  instrument: TradingLabInstrumentConfig,
  durationSeconds = 300,
): MarketPathDefinition['instruments'][number] {
  return {
    mode: 'SIMPLE',
    productType: instrument.productType,
    symbol: instrument.symbol,
    seed: `path-${instrument.productType}-${instrument.symbol}`,
    last: {
      start: '60000',
      segments: [{
        target: '60100',
        durationSeconds,
        offsetRangeSteps: 0,
        volatilitySteps: 0,
        maxStepPerSecond: 100,
      }],
    },
    spreadSteps: 2,
    indexOffsetSteps: 0,
    basisSteps: 0,
    ...(instrument.productType === 'LINEAR_PERP'
      ? { fundingRate: '0.0001' }
      : {}),
  }
}

function advancedSpotPath(
  symbol: string,
  input: Readonly<{
    bidTarget?: string
    askTarget?: string
    bidDuration?: number
    askDuration?: number
    lastDuration?: number
  }> = {},
): MarketPathDefinition['instruments'][number] {
  const lane = (
    start: string,
    target: string,
    durationSeconds: number,
  ) => ({
    start,
    segments: [{
      target,
      durationSeconds,
      offsetRangeSteps: 0,
      volatilitySteps: 0,
      maxStepPerSecond: 100,
    }],
  })
  return {
    mode: 'ADVANCED',
    productType: 'CRYPTO_SPOT',
    symbol,
    seed: `advanced-${symbol}`,
    prices: {
      bid: lane('59999', input.bidTarget ?? '60099', input.bidDuration ?? 300),
      ask: lane('60001', input.askTarget ?? '60101', input.askDuration ?? 300),
      last: lane('60000', '60100', input.lastDuration ?? 300),
    },
  }
}

function scenario(productType: 'CRYPTO_SPOT' | 'LINEAR_PERP' = 'CRYPTO_SPOT'): TradingLabScenario {
  const snapshot = config(productType)
  const instrument = snapshot.instruments[0]
  return {
    id: 'scenario-1',
    name: '有效场景',
    description: '',
    negativeMode: false,
    seed: 'seed-1',
    modelVersion: snapshot.modelVersion,
    configSnapshot: snapshot,
    configSnapshotHash: 'a'.repeat(64),
    executionPolicy: { ...snapshot.executionPolicy },
    marketPath: {
      virtualStart: '2026-01-01T00:00:00.000Z',
      realistic: false,
      instruments: [simplePath(instrument)],
    },
    initialBalances: { USDT: '100000' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: instrument.defaultLeverage,
    },
    symbols: [{
      symbol: instrument.symbol,
      productType,
    }],
    timeline: [{
      id: 'action-1',
      sequence: 1,
      type: 'PLACE_ORDER',
      symbol: instrument.symbol,
      productType,
      trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
      parameters: {
        side: 'BUY',
        orderType: 'LIMIT',
        quantity: '0.01',
        price: '60000',
      },
    }],
  }
}

function codes(issues: readonly ScenarioValidationIssue[]): string[] {
  return issues.map((issue) => issue.code)
}

test('accepts a valid scenario with no duplicated component-side rules', () => {
  const issues = validateScenario(scenario())
  assert.deepEqual(issues, [])
  assert.equal(canRunScenario(issues), true)
})

test('rejects old scenario payloads missing the required execution policy or market path', () => {
  const missingPolicy = scenario() as unknown as Record<string, unknown>
  delete missingPolicy.executionPolicy
  assert.equal(
    codes(validateScenario(missingPolicy as TradingLabScenario))
      .includes('EXECUTION_POLICY_REQUIRED'),
    true,
  )

  const missingPath = scenario() as unknown as Record<string, unknown>
  delete missingPath.marketPath
  assert.equal(
    codes(validateScenario(missingPath as TradingLabScenario))
      .includes('MARKET_PATH_REQUIRED'),
    true,
  )
})

test('validates scenario execution-policy decimal ranges independently from config authority', () => {
  const input = scenario()
  input.executionPolicy = {
    ...input.executionPolicy,
    matchingMode: 'DEPTH',
    makerFeeRate: '1',
    takerFeeRate: '-0.01',
    liquidationFeeRate: '1.01',
    slippageRate: 'NaN',
    maxFillQuantityPerTick: '0',
  }

  assert.deepEqual(codes(validateScenario(input)).filter((code) =>
    code.startsWith('EXECUTION_POLICY_')), [
    'EXECUTION_POLICY_MAKER_FEE_RATE_INVALID',
    'EXECUTION_POLICY_TAKER_FEE_RATE_INVALID',
    'EXECUTION_POLICY_LIQUIDATION_FEE_RATE_INVALID',
    'EXECUTION_POLICY_SLIPPAGE_RATE_INVALID',
    'EXECUTION_POLICY_MAX_FILL_QUANTITY_INVALID',
  ])
})

test('accepts an explicitly absent max-fill cap in both persisted policies', () => {
  const input = scenario()
  input.executionPolicy.maxFillQuantityPerTick = null
  input.configSnapshot.executionPolicy.maxFillQuantityPerTick = null

  assert.deepEqual(codes(validateScenario(input)), [])
})

test('requires market paths to form the exact selected product-plus-symbol identity set', () => {
  const duplicate = scenario()
  duplicate.marketPath = {
    ...duplicate.marketPath,
    instruments: [
      ...duplicate.marketPath.instruments,
      structuredClone(duplicate.marketPath.instruments[0]),
    ],
  }
  assert.equal(
    codes(validateScenario(duplicate)).includes('MARKET_PATH_IDENTITY_DUPLICATE'),
    true,
  )

  const missing = scenario()
  missing.marketPath = {
    ...missing.marketPath,
    instruments: [],
  }
  const missingCodes = codes(validateScenario(missing))
  assert.equal(missingCodes.includes('MARKET_PATH_INSTRUMENTS_REQUIRED'), true)
  assert.equal(missingCodes.includes('MARKET_PATH_IDENTITY_MISSING'), true)

  const unsupported = scenario()
  unsupported.marketPath = {
    ...unsupported.marketPath,
    instruments: [{
      ...unsupported.marketPath.instruments[0],
      symbol: 'ETHUSDT',
    }],
  }
  const unsupportedCodes = codes(validateScenario(unsupported))
  assert.equal(unsupportedCodes.includes('MARKET_PATH_IDENTITY_UNSUPPORTED'), true)
  assert.equal(unsupportedCodes.includes('MARKET_PATH_IDENTITY_UNSELECTED'), true)
  assert.equal(unsupportedCodes.includes('MARKET_PATH_IDENTITY_MISSING'), true)
})

test('rejects malformed lanes, duration mismatch, tick misalignment, and unreachable speed', () => {
  const malformed = scenario()
  malformed.marketPath = {
    ...malformed.marketPath,
    instruments: [{
      mode: 'ADVANCED',
      productType: 'CRYPTO_SPOT',
      symbol: 'BTCUSDT',
      seed: 'malformed-lanes',
      prices: {
        bid: {
          start: '59999',
          segments: [{
            target: '60099',
            durationSeconds: 300,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        ask: {
          start: '60001',
          segments: [{
            target: '60101',
            durationSeconds: 300,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        last: {
          start: '60000',
          segments: [{
            target: '60100',
            durationSeconds: 300,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        mark: {
          start: '60000',
          segments: [],
        },
      },
    } as never],
  }
  assert.equal(
    codes(validateScenario(malformed)).includes('MARKET_PATH_STRUCTURE_INVALID'),
    true,
  )

  const durationMismatch = scenario()
  durationMismatch.marketPath = {
    ...durationMismatch.marketPath,
    instruments: [advancedSpotPath('BTCUSDT', { askDuration: 299 })],
  }
  assert.equal(
    codes(validateScenario(durationMismatch)).includes('MARKET_PATH_DURATION_MISMATCH'),
    true,
  )

  const tickMismatch = scenario()
  const tickPath = structuredClone(tickMismatch.marketPath)
  const tickInstrument = tickPath.instruments[0]
  assert.equal(tickInstrument.mode, 'SIMPLE')
  if (tickInstrument.mode === 'SIMPLE') {
    ;(tickInstrument.last as { start: string }).start = '60000.001'
  }
  tickMismatch.marketPath = tickPath
  assert.equal(
    codes(validateScenario(tickMismatch)).includes('MARKET_PATH_PRICE_TICK_MISMATCH'),
    true,
  )

  const unreachable = scenario()
  const unreachablePath = structuredClone(unreachable.marketPath)
  const unreachableInstrument = unreachablePath.instruments[0]
  assert.equal(unreachableInstrument.mode, 'SIMPLE')
  if (unreachableInstrument.mode === 'SIMPLE') {
    ;(unreachableInstrument.last as unknown as {
      segments: Array<{
        target: string
        durationSeconds: number
        offsetRangeSteps: number
        volatilitySteps: number
        maxStepPerSecond: number
      }>
    }).segments = [{
      target: '60100',
      durationSeconds: 1,
      offsetRangeSteps: 0,
      volatilitySteps: 0,
      maxStepPerSecond: 1,
    }]
  }
  unreachable.marketPath = unreachablePath
  assert.equal(
    codes(validateScenario(unreachable)).includes('MARKET_PATH_SPEED_UNREACHABLE'),
    true,
  )
})

test('rejects an advanced path whose configured final bid is not below final ask', () => {
  const input = scenario()
  input.marketPath = {
    ...input.marketPath,
    instruments: [advancedSpotPath('BTCUSDT', {
      bidTarget: '60101',
      askTarget: '60100',
    })],
  }

  assert.equal(
    codes(validateScenario(input)).includes('MARKET_PATH_FINAL_SPREAD_INVALID'),
    true,
  )
})

test('requires explicit base and quote assets without deriving them from symbol', () => {
  const missingBase = scenario()
  delete (missingBase.configSnapshot.instruments[0] as unknown as Record<string, unknown>).baseAsset
  assert.deepEqual(codes(validateScenario(missingBase)), [
    'INSTRUMENT_BASE_ASSET_REQUIRED',
  ])

  const blankQuote = scenario()
  blankQuote.configSnapshot.instruments[0].quoteAsset = ' '
  assert.deepEqual(codes(validateScenario(blankQuote)), [
    'INSTRUMENT_QUOTE_ASSET_REQUIRED',
  ])

  const source = readFileSync(new URL('./types.ts', import.meta.url), 'utf8')
  assert.match(source, /\bbaseAsset:\s*string\b/)
  assert.match(source, /\bquoteAsset:\s*string\b/)
})

test('requires VIRTUAL_TIME to target the one-based Tick sequence', () => {
  const input = scenario()
  input.timeline[0].trigger = { type: 'VIRTUAL_TIME', atSecond: 0 }

  assert.deepEqual(codes(validateScenario(input)), ['TRIGGER_SECOND_INVALID'])
})

test('uses product type plus symbol as instrument identity', () => {
  const input = scenario()
  const perpetual = config('LINEAR_PERP').instruments[0]
  perpetual.symbol = input.configSnapshot.instruments[0].symbol
  input.configSnapshot.instruments.push(perpetual)
  input.symbols.push({
    symbol: perpetual.symbol,
    productType: 'LINEAR_PERP',
  })
  input.marketPath = {
    ...input.marketPath,
    instruments: [
      ...input.marketPath.instruments,
      simplePath(perpetual),
    ],
  }

  const identityCodes = codes(validateScenario(input))
  assert.equal(identityCodes.includes('INSTRUMENT_SYMBOL_DUPLICATE'), false)
  assert.equal(identityCodes.includes('SCENARIO_SYMBOL_PRODUCT_MISMATCH'), false)
})

test('rejects blank and overlong string seeds before the backend boundary', () => {
  for (const seed of ['   ', 's'.repeat(257)]) {
    const input = scenario()
    input.seed = seed
    const issues = validateScenario(input)

    assert.deepEqual(codes(issues), ['SCENARIO_SEED_REQUIRED'])
    assert.equal(canRunScenario(issues), false)
  }
})

test('reports precision, step, quantity, and notional configuration errors by field', () => {
  const input = scenario()
  const instrument = input.configSnapshot.instruments[0]
  instrument.pricePrecision = 1
  instrument.stepSize = '0'
  instrument.minQty = '0'
  instrument.maxQty = '-1'
  instrument.minNotional = '0'
  instrument.maxNotional = '-1'

  const issues = validateScenario(input)
  assert.deepEqual(codes(issues).slice(0, 6), [
    'PRICE_PRECISION_MISMATCH',
    'STEP_SIZE_INVALID',
    'MIN_QUANTITY_INVALID',
    'MAX_QUANTITY_INVALID',
    'MIN_NOTIONAL_INVALID',
    'MAX_NOTIONAL_INVALID',
  ])
  assert.equal(issues.every((issue) => /[\u3400-\u9fff]/u.test(issue.message)), true)
  assert.equal(canRunScenario(issues), false)
})

test('accepts absent notional bounds and enforces each present bound independently', () => {
  const withoutBounds = scenario()
  const unbounded = withoutBounds.configSnapshot.instruments[0]
  unbounded.minNotional = null
  unbounded.maxNotional = null
  assert.deepEqual(codes(validateScenario(withoutBounds)), [])

  const maximumOnly = scenario()
  const maximumAuthority = maximumOnly.configSnapshot.instruments[0]
  maximumAuthority.minNotional = null
  maximumAuthority.maxNotional = '100'
  assert.deepEqual(codes(validateScenario(maximumOnly)), [
    'NOTIONAL_ABOVE_MAXIMUM',
  ])

  const minimumOnly = scenario()
  const minimumAuthority = minimumOnly.configSnapshot.instruments[0]
  minimumAuthority.minNotional = '1000'
  minimumAuthority.maxNotional = null
  assert.deepEqual(codes(validateScenario(minimumOnly)), [
    'NOTIONAL_BELOW_MINIMUM',
  ])
})

test('rejects misaligned and below-range orders plus Spot Reduce Only', () => {
  const input = scenario()
  input.timeline[0].parameters.quantity = '0.00005'
  input.timeline[0].parameters.price = '60000.011'
  input.timeline[0].parameters.reduceOnly = true

  const issues = validateScenario(input)
  assert.deepEqual(codes(issues), [
    'QUANTITY_STEP_MISMATCH',
    'QUANTITY_BELOW_MINIMUM',
    'PRICE_TICK_MISMATCH',
    'NOTIONAL_BELOW_MINIMUM',
    'REDUCE_ONLY_PRODUCT_INVALID',
  ])
  assert.equal(canRunScenario(issues), false)
})

test('turns a negative order quantity into issues instead of throwing from step alignment', () => {
  const input = scenario()
  input.timeline[0].parameters.quantity = '-0.01'

  let issues: ScenarioValidationIssue[] = []
  assert.doesNotThrow(() => {
    issues = validateScenario(input)
  })
  assert.equal(codes(issues).includes('QUANTITY_BELOW_MINIMUM'), true)
  assert.equal(canRunScenario(issues), false)
})

test('validates default and action leverage plus Perpetual-only margin mode', () => {
  const input = scenario('LINEAR_PERP')
  input.defaults.leverage = 0
  input.timeline[0].parameters.leverage = 21
  input.timeline[0].parameters.marginMode = 'CASH'

  const issues = validateScenario(input)
  assert.deepEqual(codes(issues), [
    'DEFAULT_LEVERAGE_OUT_OF_RANGE',
    'LEVERAGE_OUT_OF_RANGE',
    'MARGIN_MODE_INVALID',
  ])
})

test('default leverage must fit every selected Perpetual instrument', () => {
  const input = scenario('LINEAR_PERP')
  const second = {
    ...input.configSnapshot.instruments[0],
    symbol: 'ETHUSDT-PERP',
    maxLeverage: 5,
    defaultLeverage: 5,
  }
  input.configSnapshot.instruments.push(second)
  input.symbols.push({
    symbol: second.symbol,
    productType: 'LINEAR_PERP',
  })
  input.marketPath = {
    ...input.marketPath,
    instruments: [
      ...input.marketPath.instruments,
      simplePath(second),
    ],
  }
  input.defaults.leverage = 10

  assert.equal(
    codes(validateScenario(input)).includes('DEFAULT_LEVERAGE_OUT_OF_RANGE'),
    true,
  )
})

test('normal mode never turns an invalid action into an expected success', () => {
  const input = scenario()
  input.timeline[0].parameters.quantity = '0'
  input.timeline[0].expectedError = { status: 400, code: 'MIN_QUANTITY' }

  const issues = validateScenario(input)
  assert.equal(codes(issues).includes('EXPECTED_ERROR_REQUIRES_NEGATIVE_MODE'), true)
  assert.equal(issues.some((issue) => issue.code === 'QUANTITY_BELOW_MINIMUM' && issue.severity === 'ERROR'), true)
  assert.equal(canRunScenario(issues), false)
})

test('negative mode requires exact expected HTTP metadata for an intentionally invalid action', () => {
  const missing = scenario()
  missing.negativeMode = true
  missing.timeline[0].parameters.quantity = '0'

  const missingIssues = validateScenario(missing)
  assert.equal(codes(missingIssues).includes('EXPECTED_ERROR_REQUIRED'), true)
  assert.equal(canRunScenario(missingIssues), false)

  const invalidMetadata = scenario()
  invalidMetadata.negativeMode = true
  invalidMetadata.timeline[0].parameters.quantity = '0'
  invalidMetadata.timeline[0].expectedError = { status: 500, code: 'bad-code' }
  const invalidMetadataIssues = validateScenario(invalidMetadata)
  assert.equal(codes(invalidMetadataIssues).includes('EXPECTED_ERROR_STATUS_INVALID'), true)
  assert.equal(codes(invalidMetadataIssues).includes('EXPECTED_ERROR_CODE_INVALID'), true)
  assert.equal(canRunScenario(invalidMetadataIssues), false)
})

test('negative mode downgrades covered business violations to warnings and permits run', () => {
  const input = scenario()
  input.negativeMode = true
  input.timeline[0].parameters.quantity = '0'
  input.timeline[0].expectedError = { status: 400, code: 'MIN_QUANTITY' }

  const issues = validateScenario(input)
  assert.equal(issues.length > 0, true)
  assert.equal(issues.every((issue) => issue.severity === 'WARNING'), true)
  assert.equal(canRunScenario(issues), true)
})

test('negative mode cannot downgrade payload-schema failures that occur before public HTTP', () => {
  const input = scenario() as unknown as TradingLabScenario
  input.negativeMode = true
  input.timeline[0].parameters = null as unknown as Record<string, unknown>
  input.timeline[0].expectedError = { status: 400, code: 'VALIDATION_ACTION_INVALID' }

  const issues = validateScenario(input)
  assert.equal(issues.some((issue) =>
    issue.code === 'ACTION_PARAMETERS_INVALID' && issue.severity === 'ERROR'), true)
  assert.equal(canRunScenario(issues), false)
})

test('validates the current six-action parameter schema and decimal-string boundary', () => {
  const cancel = scenario()
  cancel.timeline[0].type = 'CANCEL_ORDER'
  cancel.timeline[0].parameters = {}
  assert.equal(
    codes(validateScenario(cancel)).includes('ACTION_PARAMETER_REQUIRED'),
    true,
  )

  const numericMoney = scenario()
  numericMoney.timeline[0].parameters.triggerPrice = 59999.5
  numericMoney.timeline[0].parameters.unexpected = 'escape'
  const numericIssues = validateScenario(numericMoney)
  assert.equal(codes(numericIssues).includes('ACTION_DECIMAL_STRING_REQUIRED'), true)
  assert.equal(codes(numericIssues).includes('ACTION_PARAMETER_UNKNOWN'), true)

  const unsupported = scenario()
  unsupported.timeline[0].type = 'ADD_MARGIN'
  assert.equal(
    codes(validateScenario(unsupported)).includes('ACTION_REQUIRES_RUNNER_SUPPORT'),
    true,
  )
})

test('validates position-mode and advanced-order cross-field contracts', () => {
  const positionMode = scenario('LINEAR_PERP')
  positionMode.timeline[0].type = 'SET_POSITION_MODE'
  positionMode.timeline[0].parameters = { positionMode: 'BANANAS' }
  assert.equal(
    codes(validateScenario(positionMode)).includes('POSITION_MODE_INVALID'),
    true,
  )

  const stop = scenario('LINEAR_PERP')
  stop.timeline[0].parameters.orderType = 'STOP_MARKET'
  delete stop.timeline[0].parameters.price
  assert.equal(
    codes(validateScenario(stop)).includes('TRIGGER_PRICE_REQUIRED'),
    true,
  )

  const trailing = scenario('LINEAR_PERP')
  trailing.timeline[0].parameters = {
    side: 'SELL',
    orderType: 'TRAILING_STOP_MARKET',
    quantity: '0.01',
    reduceOnly: true,
    timeInForce: 'GTC',
    trailingRate: '0.01',
  }
  assert.deepEqual(validateScenario(trailing), [])
})

test('validates public order enums, post-only, stop, trailing, and protection contracts', () => {
  const invalidEnums = scenario('LINEAR_PERP')
  invalidEnums.timeline[0].parameters = {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    positionSide: 'MIDDLE',
    quantityUnit: 'COINS',
    triggerPriceType: 'MID_PRICE',
    timeInForce: 'DAY',
    attachedProtections: [{
      protectionType: 'PANIC',
      triggerPrice: '59000',
      triggerPriceType: 'MID_PRICE',
      triggerExecutionType: 'DELAYED',
      quantity: '0.01',
      quantityUnit: 'COINS',
    }],
  }
  const invalidEnumCodes = codes(validateScenario(invalidEnums))
  for (const code of [
    'POSITION_SIDE_INVALID',
    'QUANTITY_UNIT_INVALID',
    'TRIGGER_PRICE_TYPE_INVALID',
    'TIME_IN_FORCE_INVALID',
    'ATTACHED_PROTECTION_TYPE_INVALID',
    'ATTACHED_PROTECTION_EXECUTION_INVALID',
    'ATTACHED_PROTECTION_TRIGGER_TYPE_INVALID',
    'ATTACHED_PROTECTION_QUANTITY_UNIT_INVALID',
  ]) {
    assert.equal(invalidEnumCodes.includes(code), true, code)
  }

  const postOnly = scenario('LINEAR_PERP')
  postOnly.timeline[0].parameters = {
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '0.01',
    postOnly: true,
  }
  assert.equal(
    codes(validateScenario(postOnly)).includes('POST_ONLY_CONTRACT_INVALID'),
    true,
  )

  const stop = scenario('LINEAR_PERP')
  stop.timeline[0].parameters = {
    side: 'SELL',
    orderType: 'STOP_LIMIT',
    quantity: '0.01',
    price: '59000',
    triggerPrice: '59000.001',
    triggerPriceType: 'LAST_PRICE',
    timeInForce: 'IOC',
  }
  const stopCodes = codes(validateScenario(stop))
  assert.equal(stopCodes.includes('TRIGGER_PRICE_TICK_MISMATCH'), true)
  assert.equal(stopCodes.includes('STOP_ORDER_CONTRACT_INVALID'), true)

  const ordinary = scenario('LINEAR_PERP')
  ordinary.timeline[0].parameters.trailingDelta = '10'
  assert.equal(
    codes(validateScenario(ordinary)).includes('TRAILING_FIELDS_INVALID'),
    true,
  )

  const invalidTrailing = scenario('LINEAR_PERP')
  invalidTrailing.timeline[0].parameters = {
    side: 'SELL',
    orderType: 'TRAILING_STOP_MARKET',
    quantity: '0.01',
    reduceOnly: true,
    timeInForce: 'GTC',
    trailingDelta: '10',
    trailingRate: '0.01',
  }
  assert.equal(
    codes(validateScenario(invalidTrailing)).includes('TRAILING_CALLBACK_INVALID'),
    true,
  )
})

test('blocks public order shapes that the current backend contract rejects', () => {
  const issueFor = (
    productType: 'CRYPTO_SPOT' | 'LINEAR_PERP',
    parameters: Record<string, unknown>,
    code: string,
  ) => {
    const input = scenario(productType)
    input.timeline[0].parameters = parameters
    assert.equal(codes(validateScenario(input)).includes(code), true, code)
  }

  issueFor('LINEAR_PERP', {
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '0.01',
    price: '60000',
  }, 'ORDER_FIELDS_INVALID')
  issueFor('LINEAR_PERP', {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    triggerPrice: '59000',
  }, 'ORDER_FIELDS_INVALID')
  issueFor('CRYPTO_SPOT', {
    side: 'SELL',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    positionSide: 'LONG',
  }, 'SPOT_POSITION_SIDE_INVALID')
  issueFor('CRYPTO_SPOT', {
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '100',
    quantityUnit: 'BASE',
  }, 'SPOT_QUANTITY_UNIT_INVALID')
  issueFor('CRYPTO_SPOT', {
    side: 'SELL',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    attachedProtections: [{
      protectionType: 'STOP_LOSS',
      triggerPrice: '59000',
      triggerExecutionType: 'MARKET',
    }],
  }, 'SPOT_PROTECTION_INVALID')
  issueFor('LINEAR_PERP', {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    stopLoss: '59000',
  }, 'PERPETUAL_LEGACY_PROTECTION_INVALID')
  issueFor('LINEAR_PERP', {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    attachedProtections: [{
      protectionType: 'STOP_LOSS',
      triggerPrice: '59000',
      triggerExecutionType: 'LIMIT',
    }],
  }, 'ATTACHED_PROTECTION_PRICE_REQUIRED')
  issueFor('LINEAR_PERP', {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    attachedProtections: [{
      protectionType: 'STOP_LOSS',
      triggerPrice: '-1',
      triggerExecutionType: 'MARKET',
    }],
  }, 'ATTACHED_PROTECTION_TRIGGER_INVALID')
})

test('validates attached-parent and integer-contract rules as business issues', () => {
  const reduceOnlyParent = scenario('LINEAR_PERP')
  reduceOnlyParent.timeline[0].parameters = {
    side: 'SELL',
    orderType: 'LIMIT',
    quantity: '0.01',
    price: '60000',
    reduceOnly: true,
    attachedProtections: [{
      protectionType: 'STOP_LOSS',
      triggerPrice: '59000',
      triggerExecutionType: 'MARKET',
    }],
  }
  assert.equal(
    codes(validateScenario(reduceOnlyParent)).includes('ATTACHED_PROTECTION_PARENT_INVALID'),
    true,
  )

  const fractionalContracts = scenario('LINEAR_PERP')
  fractionalContracts.timeline[0].parameters.quantityUnit = 'CONTRACTS'
  fractionalContracts.timeline[0].parameters.quantity = '1.5'
  assert.equal(
    codes(validateScenario(fractionalContracts)).includes('CONTRACT_QUANTITY_INTEGER_REQUIRED'),
    true,
  )

  const fractionalProtection = scenario('LINEAR_PERP')
  fractionalProtection.timeline[0].parameters.attachedProtections = [{
    protectionType: 'STOP_LOSS',
    triggerPrice: '59000',
    triggerExecutionType: 'MARKET',
    quantity: '1.5',
    quantityUnit: 'CONTRACTS',
  }]
  assert.equal(
    codes(validateScenario(fractionalProtection)).includes('CONTRACT_QUANTITY_INTEGER_REQUIRED'),
    true,
  )

  const negativeEnum = scenario('LINEAR_PERP')
  negativeEnum.negativeMode = true
  negativeEnum.timeline[0].parameters.attachedProtections = [{
    protectionType: 'WRONG',
    triggerPrice: '59000',
    triggerExecutionType: 'MARKET',
  }]
  negativeEnum.timeline[0].expectedError = {
    status: 400,
    code: 'PROTECTION_REQUEST_INVALID',
  }
  const negativeIssues = validateScenario(negativeEnum)
  assert.equal(
    negativeIssues.some((issue) =>
      issue.code === 'ATTACHED_PROTECTION_TYPE_INVALID'
      && issue.severity === 'WARNING'),
    true,
  )
  assert.equal(canRunScenario(negativeIssues), true)
})

test('validates CONTRACTS structurally and leaves canonical BASE boundaries to runtime conversion', () => {
  const contracts = scenario('LINEAR_PERP')
  const instrument = contracts.configSnapshot.instruments[0]
  instrument.contractSize = '0.01'
  instrument.maxQty = '2'
  contracts.timeline[0].parameters.quantityUnit = 'CONTRACTS'
  contracts.timeline[0].parameters.quantity = '100'

  assert.deepEqual(codes(validateScenario(contracts)), [])

  const fractionalContracts = structuredClone(contracts)
  fractionalContracts.timeline[0].parameters.quantity = '100.5'
  assert.equal(
    codes(validateScenario(fractionalContracts))
      .includes('CONTRACT_QUANTITY_INTEGER_REQUIRED'),
    true,
  )

  const base = structuredClone(contracts)
  base.timeline[0].parameters.quantityUnit = 'BASE'
  assert.equal(
    codes(validateScenario(base)).includes('QUANTITY_ABOVE_MAXIMUM'),
    true,
  )
  assert.equal(
    codes(validateScenario(base)).includes('NOTIONAL_ABOVE_MAXIMUM'),
    true,
  )
})

test('a non-boolean negative-mode value cannot unlock expected-error handling', () => {
  const input = scenario() as unknown as Omit<TradingLabScenario, 'negativeMode'> & {
    negativeMode: unknown
  }
  input.negativeMode = 'true'
  input.timeline[0].parameters.quantity = '0'
  input.timeline[0].expectedError = { status: 400, code: 'MIN_QUANTITY' }

  const issues = validateScenario(input as TradingLabScenario)
  assert.equal(codes(issues).includes('NEGATIVE_MODE_INVALID'), true)
  assert.equal(codes(issues).includes('EXPECTED_ERROR_REQUIRES_NEGATIVE_MODE'), true)
  assert.equal(issues.some((issue) => issue.code === 'QUANTITY_BELOW_MINIMUM' && issue.severity === 'ERROR'), true)
  assert.equal(canRunScenario(issues), false)
})

test('rejects duplicate or non-increasing action identity and sequence deterministically', () => {
  const input = scenario()
  input.timeline.push({
    ...input.timeline[0],
    parameters: { ...input.timeline[0].parameters },
  })

  const first = validateScenario(input)
  const second = validateScenario(input)
  assert.deepEqual(first, second)
  assert.deepEqual(codes(first), [
    'ACTION_ID_DUPLICATE',
    'ACTION_SEQUENCE_DUPLICATE',
    'ACTION_SEQUENCE_NOT_INCREASING',
  ])
})

test('actions must use selected symbols and AFTER_ACTION must reference an earlier action', () => {
  const input = scenario()
  input.symbols = []
  const unselectedCodes = codes(validateScenario(input))
  assert.equal(unselectedCodes.includes('ACTION_SYMBOL_NOT_SELECTED'), true)

  const ordered = scenario()
  ordered.timeline.push({
    id: 'action-2',
    sequence: 2,
    type: 'CANCEL_ALL',
    symbol: 'BTCUSDT',
    productType: 'CRYPTO_SPOT',
    trigger: {
      type: 'AFTER_ACTION',
      actionId: 'action-2',
      delaySeconds: 0,
    },
    parameters: {},
  })
  assert.equal(
    codes(validateScenario(ordered)).includes('TRIGGER_ACTION_ORDER_INVALID'),
    true,
  )
})

test('fails closed on corrupt imported instrument and action rows without throwing', () => {
  const corruptInstrument = scenario() as unknown as {
    configSnapshot: { instruments: unknown[] }
  }
  corruptInstrument.configSnapshot.instruments = [null]
  let instrumentIssues: ScenarioValidationIssue[] = []
  assert.doesNotThrow(() => {
    instrumentIssues = validateScenario(corruptInstrument as unknown as TradingLabScenario)
  })
  assert.equal(codes(instrumentIssues).includes('INSTRUMENT_INVALID'), true)

  const corruptAction = scenario() as unknown as { timeline: unknown[] }
  corruptAction.timeline = [null]
  let actionIssues: ScenarioValidationIssue[] = []
  assert.doesNotThrow(() => {
    actionIssues = validateScenario(corruptAction as unknown as TradingLabScenario)
  })
  assert.equal(codes(actionIssues).includes('ACTION_INVALID'), true)

  const corruptSymbol = scenario() as unknown as { symbols: unknown[] }
  corruptSymbol.symbols = [null]
  let symbolIssues: ScenarioValidationIssue[] = []
  assert.doesNotThrow(() => {
    symbolIssues = validateScenario(corruptSymbol as unknown as TradingLabScenario)
  })
  assert.equal(codes(symbolIssues).includes('SCENARIO_SYMBOL_INVALID'), true)
})

test('initial balances match the validation-run USDT and NUMERIC(24,8) contract', () => {
  const missingUsdt = scenario()
  missingUsdt.initialBalances = { BTC: '1' }
  assert.equal(
    codes(validateScenario(missingUsdt)).includes('INITIAL_BALANCE_USDT_REQUIRED'),
    true,
  )

  const excessScale = scenario()
  excessScale.initialBalances = { USDT: '0.000000001' }
  assert.equal(
    codes(validateScenario(excessScale)).includes('INITIAL_BALANCE_NUMERIC_INVALID'),
    true,
  )

  const duplicateCanonicalAsset = scenario()
  duplicateCanonicalAsset.initialBalances = { USDT: '1', usdt: '2' }
  const balanceCodes = codes(validateScenario(duplicateCanonicalAsset))
  assert.equal(balanceCodes.includes('INITIAL_BALANCE_ASSET_INVALID'), true)
  assert.equal(balanceCodes.includes('INITIAL_BALANCE_ASSET_DUPLICATE'), true)
})

test('fails closed for frozen, running, and unknown scenario states', () => {
  assert.equal(isScenarioLocked('LOCAL_DRAFT'), false)
  assert.equal(isScenarioLocked('DRAFT'), false)
  assert.equal(isScenarioLocked('FROZEN'), true)
  assert.equal(isScenarioLocked('VALIDATING'), true)
  assert.equal(isScenarioLocked('RUNNING'), true)
  assert.equal(isScenarioLocked('unexpected'), true)
})

test('validation source uses the decimal seam without floating or backend escape hatches', () => {
  const source = readFileSync(new URL('./validation.ts', import.meta.url), 'utf8')
  for (const token of [
    'parseFloat',
    'parseInt',
    'Number(',
    'Math.round',
    'toExponential',
    'BigDecimal',
    '/backend/',
    '\\backend\\',
  ]) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.match(source, /from '\.\.\/oracle\/decimal\.ts'/)
})
