import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import type { PriceType, ProductType } from '../model/types.ts'
import type {
  InstrumentTick,
  MarketTick,
  PerpetualInstrumentTick,
  SpotInstrumentTick,
} from '../oracle/types.ts'
import {
  TradingLabChartModelError,
  buildTradingLabChartModel,
  tradingLabChartSeriesKey,
  type TradingLabChartModelInput,
} from './chartModel.ts'

const EPOCH = Date.parse('2026-07-25T00:00:00.000Z')

function utc(second: number): string {
  return new Date(EPOCH + second * 1000).toISOString()
}

function spot(
  symbol: string,
  prices: Partial<Pick<SpotInstrumentTick, 'bid' | 'ask' | 'last'>> = {},
): SpotInstrumentTick {
  return {
    productType: 'CRYPTO_SPOT',
    symbol,
    bid: prices.bid ?? '99.90',
    ask: prices.ask ?? '100.10',
    last: prices.last ?? '100.00',
  }
}

function perpetual(
  symbol: string,
  prices: Partial<
    Pick<PerpetualInstrumentTick, 'bid' | 'ask' | 'last' | 'mark' | 'index'>
  > = {},
): PerpetualInstrumentTick {
  return {
    productType: 'LINEAR_PERP',
    symbol,
    bid: prices.bid ?? '199.90',
    ask: prices.ask ?? '200.10',
    last: prices.last ?? '200.00',
    mark: prices.mark ?? '200.05',
    index: prices.index ?? '200.02',
  }
}

function tick(
  sequence: number,
  second: number,
  instruments: readonly InstrumentTick[],
): MarketTick {
  return {
    sequence,
    virtualTime: utc(second),
    instruments,
    fundingRates: [],
  }
}

function input(
  overrides: Partial<TradingLabChartModelInput> = {},
): TradingLabChartModelInput {
  return {
    source: 'LOCAL',
    productType: 'CRYPTO_SPOT',
    symbol: 'BTC-USDT-LAB',
    pricePrecision: 2,
    ticks: [
      tick(1, 0, [spot('BTC-USDT-LAB', { last: '100.00' })]),
      tick(2, 1, [spot('BTC-USDT-LAB', { last: '100.25' })]),
    ],
    period: '1m',
    mode: 'KLINE',
    visiblePrices: new Set<PriceType>(['LAST']),
    virtualTime: utc(1),
    ...overrides,
  }
}

function expectCode(code: string, operation: () => unknown): void {
  assert.throws(
    operation,
    (error: unknown) => (
      error instanceof TradingLabChartModelError
      && error.code === code
    ),
  )
}

describe('Trading Lab chart model', () => {
  it('aggregates aligned :00 through :59 ticks into one exact decimal 1m candle', () => {
    const ticks = Array.from({ length: 60 }, (_, index) => {
      const last = index === 0
        ? '100.00'
        : index === 1
          ? '99.99'
          : index === 29
            ? '101.000'
            : index === 59
              ? '100.50'
              : '100.25'
      return tick(index + 1, index, [spot('BTC-USDT-LAB', { last })])
    })

    const model = buildTradingLabChartModel(input({ ticks }))

    assert.deepEqual(model.series[0]?.bars, [{
      timestamp: EPOCH,
      firstSequence: 1,
      lastSequence: 60,
      open: '100.00',
      high: '101.000',
      low: '99.99',
      close: '100.50',
    }])
  })

  it('uses UTC epoch half-open buckets instead of grouping every 60 sequences', () => {
    const ticks = Array.from({ length: 60 }, (_, index) => (
      tick(index + 1, index + 1, [spot('BTC-USDT-LAB')])
    ))

    const model = buildTradingLabChartModel(input({ ticks }))

    assert.deepEqual(
      model.series[0]?.bars.map((bar) => [
        bar.timestamp,
        bar.firstSequence,
        bar.lastSequence,
      ]),
      [
        [EPOCH, 1, 59],
        [EPOCH + 60_000, 60, 60],
      ],
    )
  })

  it('supports every frozen K-line period with an epoch-aligned boundary', () => {
    const periods = [
      ['1s', 1],
      ['1m', 60],
      ['5m', 300],
      ['15m', 900],
      ['1h', 3600],
    ] as const

    for (const [period, boundarySecond] of periods) {
      const model = buildTradingLabChartModel(input({
        period,
        ticks: [
          tick(1, 0, [spot('BTC-USDT-LAB')]),
          tick(2, boundarySecond, [spot('BTC-USDT-LAB')]),
        ],
        virtualTime: utc(boundarySecond),
      }))
      assert.equal(model.series[0]?.bars.length, 2, period)
      assert.equal(
        model.series[0]?.bars[1]?.timestamp,
        EPOCH + boundarySecond * 1000,
        period,
      )
    }
  })

  it('forces TICK mode to one-second points regardless of requested period', () => {
    const model = buildTradingLabChartModel(input({
      mode: 'TICK',
      period: '1h',
      ticks: [
        tick(1, 0, [spot('BTC-USDT-LAB', { last: '1.20' })]),
        tick(2, 1, [spot('BTC-USDT-LAB', { last: '1.21' })]),
      ],
    }))

    assert.equal(model.period, '1s')
    assert.equal(model.periodMs, 1000)
    assert.deepEqual(model.series[0]?.bars, [
      {
        timestamp: EPOCH,
        firstSequence: 1,
        lastSequence: 1,
        open: '1.20',
        high: '1.20',
        low: '1.20',
        close: '1.20',
      },
      {
        timestamp: EPOCH + 1000,
        firstSequence: 2,
        lastSequence: 2,
        open: '1.21',
        high: '1.21',
        low: '1.21',
        close: '1.21',
      },
    ])
  })

  it('keeps every strictly increasing sub-second Tick as its own point', () => {
    const first = {
      ...tick(1, 0, [spot('BTC-USDT-LAB', { last: '1.20' })]),
      virtualTime: new Date(EPOCH + 100).toISOString(),
    }
    const second = {
      fundingRates: [],
      instruments: [spot('BTC-USDT-LAB', { last: '1.21' })],
      virtualTime: new Date(EPOCH + 900).toISOString(),
      sequence: 2,
    }

    const model = buildTradingLabChartModel(input({
      mode: 'TICK',
      ticks: [first, second],
      virtualTime: second.virtualTime,
    }))

    assert.deepEqual(
      model.series[0]?.bars.map((bar) => [
        bar.timestamp,
        bar.firstSequence,
        bar.lastSequence,
        bar.close,
      ]),
      [
        [EPOCH + 100, 1, 1, '1.20'],
        [EPOCH + 900, 2, 2, '1.21'],
      ],
    )
  })

  it('keeps visible lanes source-qualified and chooses the frozen primary priority', () => {
    const visiblePrices = new Set<PriceType>([
      'ASK',
      'INDEX',
      'MARK',
      'LAST',
      'BID',
    ])
    const model = buildTradingLabChartModel(input({
      source: 'ACTUAL',
      productType: 'LINEAR_PERP',
      symbol: 'BTC-USDT-LAB',
      pricePrecision: 8,
      ticks: [tick(1, 0, [perpetual('BTC-USDT-LAB')])],
      visiblePrices,
    }))

    assert.equal(model.pricePrecision, 8)
    assert.deepEqual(
      model.series.map((series) => series.priceType),
      ['BID', 'ASK', 'LAST', 'MARK', 'INDEX'],
    )
    assert.equal(
      model.primarySeriesKey,
      tradingLabChartSeriesKey({
        source: 'ACTUAL',
        productType: 'LINEAR_PERP',
        symbol: 'BTC-USDT-LAB',
        priceType: 'LAST',
      }),
    )
    assert.ok(model.series.every((series) => series.key.startsWith(
      'ACTUAL:LINEAR_PERP:BTC-USDT-LAB:',
    )))
  })

  it('keeps Spot MARK and INDEX absent without LAST fallback', () => {
    const model = buildTradingLabChartModel(input({
      visiblePrices: new Set<PriceType>(['MARK', 'INDEX']),
    }))

    assert.deepEqual(model.series, [])
    assert.equal(model.primarySeriesKey, null)
    assert.deepEqual(
      model.issues.map((issue) => [issue.code, issue.priceType]),
      [
        ['PRICE_LANE_UNAVAILABLE', 'MARK'],
        ['PRICE_LANE_UNAVAILABLE', 'INDEX'],
        ['NO_VISIBLE_PRICE_SERIES', undefined],
      ],
    )
  })

  it('selects by productType plus symbol when the composite Tick contains both', () => {
    const composite = tick(1, 0, [
      spot('SAME', { last: '10.00' }),
      perpetual('SAME', { last: '20.00' }),
    ])
    const spotModel = buildTradingLabChartModel(input({
      productType: 'CRYPTO_SPOT',
      symbol: 'SAME',
      ticks: [composite],
    }))
    const perpModel = buildTradingLabChartModel(input({
      productType: 'LINEAR_PERP',
      symbol: 'SAME',
      ticks: [composite],
    }))

    assert.equal(spotModel.series[0]?.bars[0]?.close, '10.00')
    assert.equal(perpModel.series[0]?.bars[0]?.close, '20.00')
  })

  it('fails closed for missing or duplicate composite identity', () => {
    expectCode('CHART_INSTRUMENT_MISSING', () => buildTradingLabChartModel(input({
      symbol: 'MISSING',
    })))
    expectCode('CHART_INSTRUMENT_DUPLICATE', () => (
      buildTradingLabChartModel(input({
        ticks: [tick(1, 0, [
          spot('BTC-USDT-LAB'),
          spot('BTC-USDT-LAB'),
        ])],
      }))
    ))
  })

  it('fails closed for malformed decimals, UTC times, and precision', () => {
    expectCode('CHART_PRICE_INVALID', () => buildTradingLabChartModel(input({
      ticks: [tick(1, 0, [spot('BTC-USDT-LAB', { last: 'Infinity' })])],
    })))
    expectCode('CHART_TIME_INVALID', () => buildTradingLabChartModel(input({
      ticks: [{
        ...tick(1, 0, [spot('BTC-USDT-LAB')]),
        virtualTime: '2026-02-30T00:00:00Z',
      }],
    })))
    expectCode('CHART_PRICE_PRECISION_INVALID', () => (
      buildTradingLabChartModel(input({ pricePrecision: -1 }))
    ))
  })

  it('deduplicates exact replay and rejects conflicting or non-monotonic input', () => {
    const replay = tick(1, 0, [spot('BTC-USDT-LAB')])
    const reorderedReplay: MarketTick = {
      fundingRates: replay.fundingRates,
      instruments: replay.instruments,
      virtualTime: replay.virtualTime,
      sequence: replay.sequence,
    }
    assert.equal(
      buildTradingLabChartModel(input({ ticks: [replay, reorderedReplay] }))
        .series[0]?.bars.length,
      1,
    )
    expectCode('CHART_SEQUENCE_ORDER_INVALID', () => (
      buildTradingLabChartModel(input({
        ticks: [
          replay,
          tick(2, 1, [spot('BTC-USDT-LAB')]),
          reorderedReplay,
        ],
      }))
    ))
    expectCode('CHART_SEQUENCE_CONFLICT', () => buildTradingLabChartModel(input({
      ticks: [
        replay,
        tick(1, 0, [spot('BTC-USDT-LAB', { last: '101.00' })]),
      ],
    })))
    expectCode('CHART_SEQUENCE_ORDER_INVALID', () => (
      buildTradingLabChartModel(input({
        ticks: [
          tick(2, 0, [spot('BTC-USDT-LAB')]),
          tick(1, 1, [spot('BTC-USDT-LAB')]),
        ],
      }))
    ))
    expectCode('CHART_TIME_ORDER_INVALID', () => (
      buildTradingLabChartModel(input({
        ticks: [
          tick(1, 1, [spot('BTC-USDT-LAB')]),
          tick(2, 0, [spot('BTC-USDT-LAB')]),
        ],
      }))
    ))
  })

  it('never merges LOCAL and ACTUAL series even when data is identical', () => {
    const ticks = [tick(1, 0, [spot('BTC-USDT-LAB')])]
    const local = buildTradingLabChartModel(input({ source: 'LOCAL', ticks }))
    const actual = buildTradingLabChartModel(input({ source: 'ACTUAL', ticks }))

    assert.notEqual(local.series[0]?.key, actual.series[0]?.key)
    assert.deepEqual(local.series[0]?.bars, actual.series[0]?.bars)
  })

  it('maps the virtual cursor to the latest Tick point or current UTC candle', () => {
    const ticks = Array.from({ length: 60 }, (_, index) => (
      tick(index + 1, index, [spot('BTC-USDT-LAB')])
    ))
    const tickModel = buildTradingLabChartModel(input({
      ticks,
      mode: 'TICK',
      virtualTime: '2026-07-25T00:00:30.500Z',
    }))
    const candleModel = buildTradingLabChartModel(input({
      ticks,
      mode: 'KLINE',
      period: '1m',
      virtualTime: '2026-07-25T00:00:30.500Z',
    }))

    assert.deepEqual(tickModel.virtualCursor, {
      timestamp: EPOCH + 30_000,
      tickSequence: 31,
    })
    assert.deepEqual(candleModel.virtualCursor, {
      timestamp: EPOCH,
      tickSequence: 31,
    })
  })

  it('returns a null cursor and bounded issue outside the retained window', () => {
    const model = buildTradingLabChartModel(input({
      virtualTime: utc(10),
    }))

    assert.equal(model.virtualCursor, null)
    assert.deepEqual(
      model.issues.map((issue) => issue.code),
      ['VIRTUAL_TIME_OUTSIDE_WINDOW'],
    )
  })

  it('does not mutate Tick rows, input arrays, or the visible Set', () => {
    const ticks = [
      tick(1, 0, [spot('BTC-USDT-LAB')]),
      tick(2, 1, [spot('BTC-USDT-LAB')]),
    ]
    const visiblePrices = new Set<PriceType>(['LAST', 'BID'])
    const beforeTicks = structuredClone(ticks)
    const beforePrices = [...visiblePrices]

    buildTradingLabChartModel(input({ ticks, visiblePrices }))

    assert.deepEqual(ticks, beforeTicks)
    assert.deepEqual([...visiblePrices], beforePrices)
  })

  it('rejects unsupported runtime product and visible-price values', () => {
    expectCode('CHART_INPUT_INVALID', () => buildTradingLabChartModel(input({
      productType: 'FOREX' as ProductType,
    })))
    expectCode('CHART_INPUT_INVALID', () => buildTradingLabChartModel(input({
      visiblePrices: new Set(['MID'] as unknown as PriceType[]),
    })))
  })
})
