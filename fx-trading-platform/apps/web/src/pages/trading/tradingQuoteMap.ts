import { createTradingQuoteFromMarket } from '../../features/market/tradingMarketAdapters.ts'
import type { TradingMarket, TradingQuote } from '../../features/market/tradingModels.ts'

export function reconcileQuoteMap(
  markets: TradingMarket[],
  current: Record<string, TradingQuote> = {}
): Record<string, TradingQuote> {
  return Object.fromEntries(
    markets.map((market) => [market.symbol, current[market.symbol] ?? createTradingQuoteFromMarket(market)])
  )
}
