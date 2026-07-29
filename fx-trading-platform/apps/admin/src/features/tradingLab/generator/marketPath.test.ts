import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import type {
  AdvancedPerpetualPath,
  AdvancedSpotPath,
  MarketPathDefinition,
  SimpleInstrumentPath,
  TradingLabConfigSnapshot,
  TradingLabInstrumentConfig,
} from '../model/types.ts'
import { MarketPathError, generateMarketTicks } from './marketPath.ts'

function instrument(
  productType: 'CRYPTO_SPOT' | 'LINEAR_PERP',
  symbol: string,
  tickSize = '1',
): TradingLabInstrumentConfig {
  const baseAsset = symbol.startsWith('ETH') ? 'ETH' : 'BTC'
  return {
    symbol,
    productType,
    baseAsset,
    quoteAsset: 'USDT',
    tickSize,
    stepSize: '0.001',
    pricePrecision: 8,
    quantityPrecision: 3,
    minQty: '0.001',
    maxQty: '1000',
    minNotional: '1',
    maxNotional: '10000000',
    initialMarginRate: '0.1',
    maintenanceMarginRate: '0.005',
    liquidationFeeRate: '0.002',
    fixedFundingRate: '0.0001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: 'MARK',
    contractSize: '1',
    maxLeverage: 100,
    defaultLeverage: 10,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'LAB',
  }
}

function configSnapshot(
  ...instruments: TradingLabInstrumentConfig[]
): TradingLabConfigSnapshot {
  return {
    modelVersion: 'trading-lab-v1',
    symbolConfigVersion: 'symbols-v1',
    codeVersion: 'code-v1',
    executionPolicy: {
      matchingMode: 'SIMPLE',
      makerFeeRate: '0.001',
      takerFeeRate: '0.001',
      liquidationFeeRate: '0.002',
      slippageRate: '0',
      maxFillQuantityPerTick: '1000',
    },
    instruments,
  }
}

function segment(
  target: string,
  durationSeconds: number,
  overrides: Partial<{
    offsetRangeSteps: number
    volatilitySteps: number
    maxStepPerSecond: number
  }> = {},
) {
  return {
    target,
    durationSeconds,
    offsetRangeSteps: overrides.offsetRangeSteps ?? 0,
    volatilitySteps: overrides.volatilitySteps ?? 0,
    maxStepPerSecond: overrides.maxStepPerSecond ?? 1000,
  }
}

function simpleSpot(
  symbol = 'BTC-USDT-LAB',
  overrides: Partial<SimpleInstrumentPath> = {},
): SimpleInstrumentPath {
  return {
    mode: 'SIMPLE',
    productType: 'CRYPTO_SPOT',
    symbol,
    seed: 'spot-seed',
    last: {
      start: '100',
      segments: [segment('100', 5)],
    },
    spreadSteps: 2,
    indexOffsetSteps: 0,
    basisSteps: 0,
    ...overrides,
  }
}

function simplePerpetual(
  symbol = 'ETH-USDT-LAB',
  overrides: Partial<SimpleInstrumentPath> = {},
): SimpleInstrumentPath {
  return {
    mode: 'SIMPLE',
    productType: 'LINEAR_PERP',
    symbol,
    seed: 'perpetual-seed',
    last: {
      start: '200',
      segments: [segment('200', 5)],
    },
    spreadSteps: 2,
    indexOffsetSteps: 1,
    basisSteps: -2,
    fundingRate: '0.00025',
    ...overrides,
  }
}

function path(
  instruments: MarketPathDefinition['instruments'],
  realistic = false,
): MarketPathDefinition {
  return {
    virtualStart: '2026-07-25T00:00:00Z',
    realistic,
    instruments,
  }
}

function expectCode(
  code: MarketPathError['code'],
  operation: () => unknown,
): void {
  assert.throws(
    operation,
    (error: unknown) => error instanceof MarketPathError && error.code === code,
  )
}

test('generates exactly 300 one-based Ticks on one shared clock and derives SIMPLE prices', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB', '0.5')
  const perpetual = instrument('LINEAR_PERP', 'ETH-USDT-LAB', '0.25')
  const input = {
    path: path([
      simplePerpetual(perpetual.symbol, {
        last: {
          start: '200',
          segments: [segment('275', 300, { maxStepPerSecond: 1 })],
        },
      }),
      simpleSpot(spot.symbol, {
        last: {
          start: '100',
          segments: [segment('250', 300, { maxStepPerSecond: 1 })],
        },
      }),
    ]),
    configSnapshot: configSnapshot(perpetual, spot),
  } as const
  const before = structuredClone(input)

  const ticks = generateMarketTicks(input)

  assert.equal(ticks.length, 300)
  assert.equal(ticks[0]?.sequence, 1)
  assert.equal(ticks[0]?.virtualTime, '2026-07-25T00:00:01.000Z')
  assert.equal(ticks[299]?.sequence, 300)
  assert.equal(ticks[299]?.virtualTime, '2026-07-25T00:05:00.000Z')
  assert.deepEqual(
    ticks[0]?.instruments.map(({ productType, symbol }) => [productType, symbol]),
    [
      ['CRYPTO_SPOT', 'BTC-USDT-LAB'],
      ['LINEAR_PERP', 'ETH-USDT-LAB'],
    ],
  )
  assert.deepEqual(ticks[0]?.instruments[0], {
    productType: 'CRYPTO_SPOT',
    symbol: 'BTC-USDT-LAB',
    bid: '100',
    ask: '101',
    last: '100.5',
  })
  assert.deepEqual(ticks[0]?.instruments[1], {
    productType: 'LINEAR_PERP',
    symbol: 'ETH-USDT-LAB',
    bid: '200',
    ask: '200.5',
    last: '200.25',
    index: '200.5',
    mark: '200',
  })
  assert.deepEqual(ticks[0]?.fundingRates, [
    { symbol: 'ETH-USDT-LAB', rate: '0.00025' },
  ])
  assert.deepEqual(ticks[299]?.instruments[0], {
    productType: 'CRYPTO_SPOT',
    symbol: 'BTC-USDT-LAB',
    bid: '249.5',
    ask: '250.5',
    last: '250',
  })
  assert.equal(ticks.every((tick) => tick.instruments.length === 2), true)
  assert.equal(ticks.every((tick) => tick.fundingRates.length === 1), true)
  assert.deepEqual(input, before)
  assert.notStrictEqual(ticks, input.path.instruments)
})

test('uses segment-start integer interpolation for remainders, both directions, and endpoints', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  const upward = generateMarketTicks({
    path: path([
      simpleSpot(spot.symbol, {
        last: {
          start: '10',
          segments: [segment('13', 5, { maxStepPerSecond: 1 })],
        },
        spreadSteps: 1,
      }),
    ]),
    configSnapshot: configSnapshot(spot),
  })
  assert.deepEqual(
    upward.map((tick) => tick.instruments[0]?.last),
    ['10', '11', '11', '12', '13'],
  )

  const downward = generateMarketTicks({
    path: path([
      simpleSpot(spot.symbol, {
        last: {
          start: '13',
          segments: [segment('10', 5, { maxStepPerSecond: 1 })],
        },
        spreadSteps: 1,
      }),
    ]),
    configSnapshot: configSnapshot(spot),
  })
  assert.deepEqual(
    downward.map((tick) => tick.instruments[0]?.last),
    ['13', '12', '12', '11', '10'],
  )

  const multiSegment = generateMarketTicks({
    path: path([
      simpleSpot(spot.symbol, {
        last: {
          start: '10',
          segments: [
            segment('13', 3, { maxStepPerSecond: 1 }),
            segment('11', 2, { maxStepPerSecond: 1 }),
          ],
        },
        spreadSteps: 1,
      }),
    ]),
    configSnapshot: configSnapshot(spot),
  })
  assert.deepEqual(
    multiSegment.map((tick) => tick.instruments[0]?.last),
    ['11', '12', '13', '12', '11'],
  )
})

test('adds deterministic bounded realistic variation, stays positive, and lands exactly', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  const realisticPath = path([
    simpleSpot(spot.symbol, {
      seed: 'realistic-seed',
      last: {
        start: '5',
        segments: [
          segment('5', 20, {
            offsetRangeSteps: 5,
            volatilitySteps: 8,
            maxStepPerSecond: 1,
          }),
        ],
      },
      spreadSteps: 1,
    }),
  ], true)
  const first = generateMarketTicks({
    path: realisticPath,
    configSnapshot: configSnapshot(spot),
  })
  const replay = generateMarketTicks({
    path: realisticPath,
    configSnapshot: configSnapshot(spot),
  })
  const nonRealistic = generateMarketTicks({
    path: { ...realisticPath, realistic: false },
    configSnapshot: configSnapshot(spot),
  })

  assert.deepEqual(first, replay)
  assert.equal(first.at(-1)?.instruments[0]?.last, '5')
  assert.equal(
    first.every((tick) => BigInt(tick.instruments[0]?.last ?? '0') >= 1n),
    true,
  )
  assert.equal(first.slice(0, -1).some((tick) => tick.instruments[0]?.last !== '5'), true)
  assert.equal(nonRealistic.every((tick) => tick.instruments[0]?.last === '5'), true)
})

test('accepts maximum-length path seeds while domain separation stays deterministic and lands', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  for (const seed of ['a'.repeat(256), '😀'.repeat(128)]) {
    assert.equal(seed.length, 256)
    const marketPath = path([
      simpleSpot(spot.symbol, {
        seed,
        last: {
          start: '10',
          segments: [
            segment('11', 8, {
              offsetRangeSteps: 2,
              volatilitySteps: 2,
              maxStepPerSecond: 1,
            }),
          ],
        },
        spreadSteps: 1,
      }),
    ], true)
    const first = generateMarketTicks({
      path: marketPath,
      configSnapshot: configSnapshot(spot),
    })
    const replay = generateMarketTicks({
      path: marketPath,
      configSnapshot: configSnapshot(spot),
    })

    assert.deepEqual(first, replay)
    assert.equal(first.length, 8)
    assert.equal(first.at(-1)?.instruments[0]?.last, '11')
  }
})

test('allows an authority config superset and emits only path-selected identities', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  const unselectedPerpetual = instrument('LINEAR_PERP', 'ETH-USDT-LAB')
  const ticks = generateMarketTicks({
    path: path([simpleSpot(spot.symbol)]),
    configSnapshot: configSnapshot(unselectedPerpetual, spot),
  })

  assert.equal(ticks.length, 5)
  assert.equal(ticks.every((tick) => tick.instruments.length === 1), true)
  assert.equal(ticks.every((tick) => tick.fundingRates.length === 0), true)
  assert.deepEqual(ticks[0]?.instruments[0], {
    productType: 'CRYPTO_SPOT',
    symbol: spot.symbol,
    bid: '99',
    ask: '101',
    last: '100',
  })
})

test('generates exact ADVANCED Spot and Perpetual lanes and clamps only non-final crossed spreads', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  const perpetual = instrument('LINEAR_PERP', 'ETH-USDT-LAB')
  const scalar = (
    start: string,
    target: string,
    volatilitySteps: number,
  ) => ({
    start,
    segments: [
      segment(target, 20, {
        offsetRangeSteps: 0,
        volatilitySteps,
        maxStepPerSecond: 1,
      }),
    ],
  })
  const spotPath: AdvancedSpotPath = {
    mode: 'ADVANCED',
    productType: 'CRYPTO_SPOT',
    symbol: spot.symbol,
    seed: 'advanced-spot',
    prices: {
      bid: scalar('9', '9', 12),
      ask: scalar('10', '10', 12),
      last: scalar('9', '9', 3),
    },
  }
  const perpetualPath: AdvancedPerpetualPath = {
    mode: 'ADVANCED',
    productType: 'LINEAR_PERP',
    symbol: perpetual.symbol,
    seed: 'advanced-perpetual',
    fundingRate: '-0.0001',
    prices: {
      bid: scalar('19', '19', 12),
      ask: scalar('20', '20', 12),
      last: scalar('19', '19', 3),
      mark: scalar('18', '18', 3),
      index: scalar('19', '19', 3),
    },
  }

  const ticks = generateMarketTicks({
    path: path([perpetualPath, spotPath], true),
    configSnapshot: configSnapshot(perpetual, spot),
  })

  assert.equal(ticks.length, 20)
  for (const tick of ticks) {
    const spotTick = tick.instruments[0]
    const perpetualTick = tick.instruments[1]
    assert.deepEqual(Object.keys(spotTick ?? {}).sort(), [
      'ask',
      'bid',
      'last',
      'productType',
      'symbol',
    ])
    assert.deepEqual(Object.keys(perpetualTick ?? {}).sort(), [
      'ask',
      'bid',
      'index',
      'last',
      'mark',
      'productType',
      'symbol',
    ])
    assert.ok(BigInt(spotTick?.bid ?? '0') < BigInt(spotTick?.ask ?? '0'))
    assert.ok(BigInt(perpetualTick?.bid ?? '0') < BigInt(perpetualTick?.ask ?? '0'))
  }
  assert.deepEqual(ticks.at(-1)?.instruments, [
    {
      productType: 'CRYPTO_SPOT',
      symbol: spot.symbol,
      bid: '9',
      ask: '10',
      last: '9',
    },
    {
      productType: 'LINEAR_PERP',
      symbol: perpetual.symbol,
      bid: '19',
      ask: '20',
      last: '19',
      mark: '18',
      index: '19',
    },
  ])
  assert.deepEqual(ticks.at(-1)?.fundingRates, [
    { symbol: perpetual.symbol, rate: '-0.0001' },
  ])
})

test('fails closed with stable classified errors for invalid authority, shape, and math', async (t) => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  const perpetual = instrument('LINEAR_PERP', 'ETH-USDT-LAB')
  const validSpot = simpleSpot(spot.symbol)
  const validPerpetual = simplePerpetual(perpetual.symbol)

  await t.test('virtualStart', () => {
    expectCode('MARKET_PATH_VIRTUAL_START_INVALID', () => generateMarketTicks({
      path: { ...path([validSpot]), virtualStart: '2026-02-30T00:00:00Z' },
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('wrong product identity has no authority', () => {
    expectCode('MARKET_PATH_INSTRUMENT_EXTRA', () => generateMarketTicks({
      path: path([simplePerpetual(spot.symbol)]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('extra identity', () => {
    expectCode('MARKET_PATH_INSTRUMENT_EXTRA', () => generateMarketTicks({
      path: path([validSpot, validPerpetual]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('duplicate identity', () => {
    expectCode('MARKET_PATH_INSTRUMENT_DUPLICATE', () => generateMarketTicks({
      path: path([validSpot, validSpot]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('duplicate unselected config authority', () => {
    expectCode('MARKET_PATH_INSTRUMENT_DUPLICATE', () => generateMarketTicks({
      path: path([validSpot]),
      configSnapshot: configSnapshot(perpetual, spot, { ...perpetual }),
    }))
  })

  await t.test('duration mismatch', () => {
    expectCode('MARKET_PATH_DURATION_MISMATCH', () => generateMarketTicks({
      path: path([
        validSpot,
        simplePerpetual(perpetual.symbol, {
          last: { start: '200', segments: [segment('200', 6)] },
        }),
      ]),
      configSnapshot: configSnapshot(spot, perpetual),
    }))
  })

  await t.test('invalid price', () => {
    expectCode('MARKET_PATH_PRICE_INVALID', () => generateMarketTicks({
      path: path([
        simpleSpot(spot.symbol, {
          last: { start: '0', segments: [segment('100', 5)] },
        }),
      ]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('tick mismatch', () => {
    expectCode('MARKET_PATH_TARGET_TICK_MISMATCH', () => generateMarketTicks({
      path: path([
        simpleSpot(spot.symbol, {
          last: { start: '100', segments: [segment('100.5', 5)] },
        }),
      ]),
      configSnapshot: configSnapshot(instrument('CRYPTO_SPOT', spot.symbol, '1')),
    }))
  })

  await t.test('unreachable target', () => {
    expectCode('MARKET_PATH_TARGET_UNREACHABLE', () => generateMarketTicks({
      path: path([
        simpleSpot(spot.symbol, {
          last: {
            start: '100',
            segments: [segment('200', 5, { maxStepPerSecond: 1 })],
          },
        }),
      ]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('invalid seed', () => {
    expectCode('MARKET_PATH_SEED_INVALID', () => generateMarketTicks({
      path: path([simpleSpot(spot.symbol, { seed: ' ' })]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('invalid shape and extra lane', () => {
    const advancedWithExtraLane = {
      mode: 'ADVANCED',
      productType: 'CRYPTO_SPOT',
      symbol: spot.symbol,
      seed: 'seed',
      prices: {
        bid: { start: '99', segments: [segment('99', 5)] },
        ask: { start: '101', segments: [segment('101', 5)] },
        last: { start: '100', segments: [segment('100', 5)] },
        mark: { start: '100', segments: [segment('100', 5)] },
      },
    }
    expectCode('MARKET_PATH_SHAPE_INVALID', () => generateMarketTicks({
      path: path([advancedWithExtraLane as AdvancedSpotPath]),
      configSnapshot: configSnapshot(spot),
    }))
  })

  await t.test('invalid funding rate', () => {
    expectCode('MARKET_PATH_FUNDING_RATE_INVALID', () => generateMarketTicks({
      path: path([
        simplePerpetual(perpetual.symbol, { fundingRate: '1e-4' }),
      ]),
      configSnapshot: configSnapshot(perpetual),
    }))
  })

  await t.test('crossed final spread', () => {
    const crossed: AdvancedSpotPath = {
      mode: 'ADVANCED',
      productType: 'CRYPTO_SPOT',
      symbol: spot.symbol,
      seed: 'seed',
      prices: {
        bid: { start: '99', segments: [segment('101', 5)] },
        ask: { start: '102', segments: [segment('100', 5)] },
        last: { start: '100', segments: [segment('100', 5)] },
      },
    }
    expectCode('MARKET_PATH_BID_ASK_INVALID', () => generateMarketTicks({
      path: path([crossed]),
      configSnapshot: configSnapshot(spot),
    }))
  })
})

test('throws before publishing any partial Tick array', () => {
  const spot = instrument('CRYPTO_SPOT', 'BTC-USDT-LAB')
  let observable: unknown = 'unchanged'
  try {
    observable = generateMarketTicks({
      path: path([
        simpleSpot(spot.symbol, {
          last: {
            start: '100',
            segments: [
              segment('101', 1),
              segment('not-a-price', 1),
            ],
          },
        }),
      ]),
      configSnapshot: configSnapshot(spot),
    })
  } catch (error) {
    assert.ok(error instanceof MarketPathError)
  }
  assert.equal(observable, 'unchanged')
})

test('production market-path source uses no floating financial math or ambient state', () => {
  const source = readFileSync(new URL('./marketPath.ts', import.meta.url), 'utf8')
  const forbidden = [
    'Math.random',
    'parseFloat',
    'parseInt',
    'toFixed',
    'Math.pow',
    'Date.now',
    'randomUUID',
    'getRandomValues',
    'node:crypto',
    '/backend/',
    '\\backend\\',
  ]

  for (const token of forbidden) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.equal(source.includes('Number('), false)
})
