import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  filterMarkets,
  getRealtimeQuoteMarkets,
  getNextPriceValue,
  getPercentageStep,
  makeMockCandles,
  toKLinePeriod
} from './tradingModels.ts'
import { mockTradingMarkets } from './mockTradingData.ts'

describe('trading page models', () => {
  it('maps terminal periods to KLineCharts periods', () => {
    assert.deepEqual(toKLinePeriod('1s'), { type: 'second', span: 1 })
    assert.deepEqual(toKLinePeriod('15m'), { type: 'minute', span: 15 })
    assert.deepEqual(toKLinePeriod('4h'), { type: 'hour', span: 4 })
    assert.deepEqual(toKLinePeriod('1d'), { type: 'day', span: 1 })
    assert.deepEqual(toKLinePeriod('1w'), { type: 'week', span: 1 })
    assert.deepEqual(toKLinePeriod('3M'), { type: 'month', span: 3 })
  })

  it('generates candle timestamps using the selected period interval', () => {
    const candles = makeMockCandles('EURUSD', '5m', 3, 1_700_000_000_000)

    assert.equal(candles.length, 3)
    assert.equal(candles[1].timestamp - candles[0].timestamp, 300_000)
    assert.equal(candles[2].timestamp, 1_700_000_000_000)
    assert.ok(candles.every((item) => item.high >= Math.max(item.open, item.close)))
    assert.ok(candles.every((item) => item.low <= Math.min(item.open, item.close)))
  })

  it('scales mock candle movement to high-value crypto symbols', () => {
    const candles = makeMockCandles('BTCUSDT', '1m', 120, 1_700_000_000_000)
    const closes = candles.map((item) => item.close)
    const range = Math.max(...closes) - Math.min(...closes)

    assert.ok(range > 900)
    assert.ok(candles.some((item) => item.close > item.open))
    assert.ok(candles.some((item) => item.close < item.open))
  })

  it('injects irregular rally and selloff shocks into mock candles', () => {
    const candles = makeMockCandles('BTCUSDT', '1m', 180, 1_700_000_000_000)
    const bodyMoves = candles.map((item) => (item.close - item.open) / item.open)

    assert.ok(Math.max(...bodyMoves) > 0.018)
    assert.ok(Math.min(...bodyMoves) < -0.018)
    assert.ok(candles.every((item) => item.high >= Math.max(item.open, item.close)))
    assert.ok(candles.every((item) => item.low <= Math.min(item.open, item.close)))
  })

  it('keeps mainstream crypto extension slots in the local market fallback list', () => {
    assert.deepEqual(
      mockTradingMarkets.filter((market) => market.category === 'crypto').map((market) => market.symbol),
      ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT']
    )
  })

  it('filters markets by category, query and favorites', () => {
    const markets = [
      { symbol: 'EURUSD', base: 'EUR', quote: 'USD', name: 'Euro / US Dollar', category: 'fx', favorite: true },
      { symbol: 'BTCUSDT', base: 'BTC', quote: 'USDT', name: 'Bitcoin / Tether', category: 'crypto', favorite: false }
    ]

    assert.deepEqual(filterMarkets(markets, 'eur', 'all').map((item) => item.symbol), ['EURUSD'])
    assert.deepEqual(filterMarkets(markets, '', 'crypto').map((item) => item.symbol), ['BTCUSDT'])
    assert.deepEqual(filterMarkets(markets, '', 'favorites').map((item) => item.symbol), ['EURUSD'])
  })

  it('limits trading page realtime quote subscriptions to the selected market', () => {
    const markets = Array.from({ length: 10 }, (_, index) => ({
      symbol: `SYM${index}USD`,
      base: `SYM${index}`,
      quote: 'USD',
      name: `Symbol ${index}`,
      category: 'fx' as const,
      favorite: index === 8
    }))

    assert.deepEqual(
      getRealtimeQuoteMarkets(markets, 'SYM9USD').map((market) => market.symbol),
      ['SYM9USD']
    )
  })

  it('skips markets whose runtime quote capability is disabled', () => {
    const markets = [
      { symbol: 'EURUSD', base: 'EUR', quote: 'USD', name: 'Euro / US Dollar', category: 'fx' as const, favorite: false, quoteEnabled: false },
      { symbol: 'BTCUSDT', base: 'BTC', quote: 'USDT', name: 'Bitcoin / Tether', category: 'crypto' as const, favorite: false, quoteEnabled: true },
      { symbol: 'ETHUSDT', base: 'ETH', quote: 'USDT', name: 'Ethereum / Tether', category: 'crypto' as const, favorite: true, quoteEnabled: true },
      { symbol: 'XAUUSD', base: 'XAU', quote: 'USD', name: 'Gold / US Dollar', category: 'metals' as const, favorite: true, quoteEnabled: false }
    ]

    assert.deepEqual(
      getRealtimeQuoteMarkets(markets, 'EURUSD').map((market) => market.symbol),
      []
    )
  })

  it('snaps slider values to the supported percentage steps', () => {
    assert.equal(getPercentageStep(0), 0)
    assert.equal(getPercentageStep(38), 50)
    assert.equal(getPercentageStep(87), 75)
    assert.equal(getPercentageStep(100), 100)
  })

  it('does not overwrite a price input while the user is editing', () => {
    assert.equal(getNextPriceValue('1.08310', '1.08340', true, false), '1.08310')
    assert.equal(getNextPriceValue('1.08310', '1.08340', false, true), '1.08310')
    assert.equal(getNextPriceValue('', '1.08340', false, false), '1.08340')
  })
})
