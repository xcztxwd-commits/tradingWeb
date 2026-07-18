import { createTradingQuoteFromMarket, isFreshSource, type TradingMarket, type TradingQuote } from '@fx-platform/frontend-core'
import { tradingProductSymbols } from '../../app/tradingRoutes.ts'

const p0Symbols = new Set<string>([...tradingProductSymbols.spot, ...tradingProductSymbols.perpetual])

export function reconcileQuoteMap(
  markets: TradingMarket[],
  current: Record<string, TradingQuote> = {}
): Record<string, TradingQuote> {
  return Object.fromEntries(
    markets.map((market) => {
      const existing = current[market.symbol]
      if (existing && isQuoteUsable(existing, market.symbol)) return [market.symbol, existing]
      if (p0Symbols.has(market.symbol)) return [market.symbol, createUnavailableTradingQuote(market.symbol)]
      return [market.symbol, createTradingQuoteFromMarket(market)]
    })
  )
}

export function createUnavailableTradingQuote(symbol: string, source = 'unavailable'): TradingQuote {
  return {
    symbol,
    bid: 0,
    ask: 0,
    mid: 0,
    spread: 0,
    changePercent: 0,
    high24h: 0,
    low24h: 0,
    volume: '0',
    source,
    timestamp: 0,
    tradable: false
  }
}

export function expireTradingQuote(quote: TradingQuote): TradingQuote {
  return {
    ...quote,
    bid: 0,
    ask: 0,
    mid: 0,
    spread: 0,
    high24h: 0,
    low24h: 0,
    timestamp: 0,
    tradable: false
  }
}

function isQuoteUsable(quote: TradingQuote, symbol: string) {
  if (!p0Symbols.has(symbol)) return true
  return quote.tradable === true && Boolean(quote.marketSource && isFreshSource(quote.marketSource))
}
