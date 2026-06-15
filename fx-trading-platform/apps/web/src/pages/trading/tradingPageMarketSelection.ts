import { mockTradingMarkets } from '../../features/market/mockTradingData'
import type { TradingMarket } from '../../features/market/tradingModels'

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => {
  if (key === 'chart.titleSuffix') return 'K-line chart'
  return key
}

export const initialTradingSymbol = 'EURUSD'

export function mergeWithMockMarkets(markets: TradingMarket[]) {
  const merged = new Map(mockTradingMarkets.map((market) => [market.symbol, market]))
  markets.forEach((market) => merged.set(market.symbol, market))
  return Array.from(merged.values())
}

export function normalizeTradingSymbol(symbol: string | null) {
  const normalized = symbol?.trim().replace(/[-_/]/g, '').toUpperCase()
  return normalized || null
}

export function formatTradingChartTitle(market: TradingMarket, t: Translate = defaultTranslate) {
  return `${market.base}/${market.quote} ${t('chart.titleSuffix')}`
}
