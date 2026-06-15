import { makeMockCandles } from '../../features/market/tradingModels.ts'
import type { TradingCandle, TradingPeriod } from '../../features/market/tradingModels.ts'

type CandleFetcher = (symbol: string, period: TradingPeriod) => Promise<TradingCandle[]>
type LoadChartCandlesOptions = {
  allowMockFallback?: boolean
}

async function fetchBackendCandles(symbol: string, period: TradingPeriod) {
  const { fetchMarketCandles } = await import('../../features/market/tradingMarketApi.ts')
  return fetchMarketCandles(symbol, period)
}

export async function loadChartCandles(
  symbol: string,
  period: TradingPeriod,
  fetcher: CandleFetcher = fetchBackendCandles,
  endTime = Date.now(),
  options: LoadChartCandlesOptions = {}
) {
  try {
    const candles = await fetcher(symbol, period)
    if (candles.length > 0 && hasUsableCandleScale(symbol, candles)) return candles
  } catch {
    // Keep the local terminal visually useful when the backend is offline.
  }

  if (options.allowMockFallback === false) {
    return []
  }
  return makeMockCandles(symbol, period, 180, endTime)
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
