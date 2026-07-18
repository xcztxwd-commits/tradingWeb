import type { TradingCandle, TradingPeriod } from '@fx-platform/frontend-core'

export const historicalCandleBatchSize = 220

type HistoricalLoadType = 'init' | 'forward' | 'backward' | 'update'
type CandleFetcherOptions = {
  endTime: number
  count?: number
}
type CandleFetcher = (symbol: string, period: TradingPeriod, options?: CandleFetcherOptions) => Promise<TradingCandle[]>
type LoadChartCandlesOptions = {
  count?: number
}

async function fetchBackendCandles(symbol: string, period: TradingPeriod, options?: CandleFetcherOptions) {
  const { fetchMarketCandles } = await import('@fx-platform/frontend-core')
  return fetchMarketCandles(symbol, period, options)
}

export async function loadChartCandles(
  symbol: string,
  period: TradingPeriod,
  fetcher: CandleFetcher = fetchBackendCandles,
  endTime = Date.now(),
  options: LoadChartCandlesOptions = {}
) {
  try {
    const candles = await fetcher(symbol, period, { endTime, count: options.count })
    if (candles.length > 0 && hasUsableCandleScale(symbol, candles)) return candles
  } catch {
    // Provider failures remain explicit; never synthesize a front-end market series.
  }
  return []
}

export function resolveHistoricalCandleEndTime(type: HistoricalLoadType, timestamp: number | null, now = Date.now()) {
  if (type === 'forward' && typeof timestamp === 'number') return timestamp - 1
  return now
}

export function hasMoreHistoricalCandles(candles: TradingCandle[], count = historicalCandleBatchSize) {
  return candles.length >= count
}

function hasUsableCandleScale(symbol: string, candles: TradingCandle[]) {
  const close = candles.at(-1)?.close
  if (typeof close !== 'number' || !Number.isFinite(close) || close <= 0) return false

  if (symbol.includes('BTC')) return close >= 10_000 && close <= 200_000
  if (symbol.includes('ETH')) return close >= 500 && close <= 20_000
  if (isForexCross(symbol)) return close >= 0.000001 && close <= 20_000_000
  if (symbol.includes('XAU')) return close >= 500 && close <= 10_000
  if (symbol === 'US100') return close >= 5_000 && close <= 100_000
  if (symbol.endsWith('JPY')) return close >= 50 && close <= 300
  return close >= 0.2 && close <= 2.5
}

function isForexCross(symbol: string) {
  return /^[A-Z]{6}$/.test(symbol) && !symbol.endsWith('USDT')
}
