import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import { historicalCandleBatchSize, loadChartCandles, resolveHistoricalCandleEndTime } from './chartCandleData.ts'
import type { TradingCandle } from '../../features/market/tradingModels.ts'

describe('chart candle data loading', () => {
  it('uses backend candles when the request returns rows', async () => {
    const backendCandles: TradingCandle[] = [
      { timestamp: 1_780_000_000_000, open: 67100, high: 67240, low: 67080, close: 67220, volume: 1200 }
    ]

    const candles = await loadChartCandles('BTCUSDT', '1m', async () => backendCandles, 1_780_000_060_000)

    assert.equal(candles, backendCandles)
  })

  it('passes historical range options to the backend candle fetcher', async () => {
    const backendCandles: TradingCandle[] = [
      { timestamp: 1_780_000_000_000, open: 67100, high: 67240, low: 67080, close: 67220, volume: 1200 }
    ]
    const requests: unknown[] = []

    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async (_symbol, _period, options) => {
        requests.push(options)
        return backendCandles
      },
      1_780_000_060_000,
      { count: historicalCandleBatchSize }
    )

    assert.equal(candles, backendCandles)
    assert.deepEqual(requests, [{ endTime: 1_780_000_060_000, count: historicalCandleBatchSize }])
  })

  it('resolves KLineCharts forward loads to the window before the first candle timestamp', () => {
    assert.equal(resolveHistoricalCandleEndTime('init', null, 1_780_000_060_000), 1_780_000_060_000)
    assert.equal(resolveHistoricalCandleEndTime('forward', 1_780_000_000_000, 1_780_000_060_000), 1_779_999_999_999)
    assert.equal(resolveHistoricalCandleEndTime('backward', 1_780_000_000_000, 1_780_000_060_000), 1_780_000_060_000)
  })

  it('returns an empty chart series when the backend request fails by default', async () => {
    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async () => {
        throw new Error('backend offline')
      },
      1_780_000_060_000
    )

    assert.deepEqual(candles, [])
  })

  it('never synthesizes a local chart series when the backend is unavailable', async () => {
    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async () => {
        throw new Error('backend offline')
      },
      1_780_000_060_000
    )

    const source = readFileSync(new URL('./chartCandleData.ts', import.meta.url), 'utf8')
    assert.deepEqual(candles, [])
    assert.doesNotMatch(source, /makeMockCandles|allowMockFallback/)
  })

  it('returns an empty chart series when the backend returns an empty series by default', async () => {
    const candles = await loadChartCandles('BTCUSDT', '1m', async () => [], 1_780_000_060_000)

    assert.deepEqual(candles, [])
  })

  it('returns an empty chart series instead of generated candles', async () => {
    const candles = await loadChartCandles('EURUSD', '1m', async () => [], 1_780_000_060_000)

    assert.deepEqual(candles, [])
  })

  it('returns an empty chart series when backend candles are on the wrong price scale by default', async () => {
    const candles = await loadChartCandles(
      'BTCUSDT',
      '1m',
      async () => [
        { timestamp: 1_780_000_000_000, open: 1.08, high: 1.09, low: 1.07, close: 1.09, volume: 1200 }
      ],
      1_780_000_060_000
    )

    assert.deepEqual(candles, [])
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

    const candles = await loadChartCandles('CHFIDR', '1h', async () => backendCandles, 1_780_000_060_000)

    assert.equal(candles, backendCandles)
  })
})
