import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { loadChartCandles } from './chartCandleData.ts'
import type { TradingCandle } from '../../features/market/tradingModels.ts'

describe('chart candle data loading', () => {
  it('uses backend candles when the request returns rows', async () => {
    const backendCandles: TradingCandle[] = [
      { timestamp: 1_780_000_000_000, open: 67100, high: 67240, low: 67080, close: 67220, volume: 1200 }
    ]

    const candles = await loadChartCandles('BTCUSDT', '1m', async () => backendCandles, 1_780_000_060_000)

    assert.equal(candles, backendCandles)
  })

  it('falls back to local mock candles when the backend request fails', async () => {
    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async () => {
        throw new Error('backend offline')
      },
      1_780_000_060_000
    )

    assert.equal(candles.length, 180)
    assert.equal(candles.at(-1)?.timestamp, 1_780_000_060_000)
    assert.equal(candles.every((candle, index) => index === 0 || candle.timestamp > candles[index - 1].timestamp), true)
    assert.equal(candles.every((candle) => candle.high >= candle.open && candle.high >= candle.close), true)
    assert.equal(candles.every((candle) => candle.low <= candle.open && candle.low <= candle.close), true)
  })

  it('falls back to local mock candles when the backend returns an empty series', async () => {
    const candles = await loadChartCandles('BTCUSDT', '1m', async () => [], 1_780_000_060_000)

    assert.equal(candles.length, 180)
    assert.equal(candles.at(-1)?.timestamp, 1_780_000_060_000)
  })

  it('returns an empty chart series instead of mock candles when fallback is disabled', async () => {
    const candles = await loadChartCandles('EURUSD', '1m', async () => [], 1_780_000_060_000, {
      allowMockFallback: false
    })

    assert.deepEqual(candles, [])
  })

  it('falls back when backend candles are on the wrong price scale for the selected symbol', async () => {
    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async () => [
        { timestamp: 1_780_000_000_000, open: 1.08, high: 1.09, low: 1.07, close: 1.09, volume: 1200 }
      ],
      1_780_000_060_000
    )

    assert.equal(candles.length, 180)
    assert.ok((candles.at(-1)?.close ?? 0) > 60_000)
  })

  it('uses backend candles for high-price forex crosses', async () => {
    const backendCandles: TradingCandle[] = [
      {
        timestamp: 1_780_000_000_000,
        open: 22_300,
        high: 22_450,
        low: 22_100,
        close: 22_241.2787,
        volume: 29_730
      }
    ]

    const candles = await loadChartCandles('CHFIDR', '1h', async () => backendCandles, 1_780_000_060_000, {
      allowMockFallback: false
    })

    assert.equal(candles, backendCandles)
  })
})
