import type { TradingQuote } from './tradingModels.ts'
import { mockTradingMarkets } from './mockTradingData.ts'
import type { MarketDataSnapshot, OrderBookLevel, TradeItem } from './marketDataTypes'

const levelCount = 42

export function createQuoteMarketDataSnapshot(quote: TradingQuote): MarketDataSnapshot {
  const tickSize = getTickSize(quote)

  return {
    asks: createLevels(quote.ask, tickSize, 'ask'),
    bids: createLevels(quote.bid, tickSize, 'bid'),
    lastPrice: quote.mid,
    lastPriceDirection: 'flat',
    recentTrades: [createTrade(quote)]
  }
}

export function createFallbackMarketDataSnapshot(symbol: string, timestamp = Date.now()): MarketDataSnapshot {
  const market = mockTradingMarkets.find((item) => item.symbol === symbol)
  const last = market?.last && market.last > 0 ? market.last : 1
  const spread = market?.spread && market.spread > 0 ? market.spread : inferFallbackSpread(symbol, last)

  return createQuoteMarketDataSnapshot({
    symbol,
    bid: last - spread / 2,
    ask: last + spread / 2,
    mid: last,
    spread,
    changePercent: market?.changePercent ?? 0,
    high24h: market?.high24h ?? last,
    low24h: market?.low24h ?? last,
    volume: market?.volume ?? 'Simulated',
    source: market?.source ?? 'markets.sources.mock',
    timestamp
  })
}

function createLevels(bestPrice: number, tickSize: number, side: 'ask' | 'bid'): OrderBookLevel[] {
  return Array.from({ length: levelCount }, (_, index) => ({
    price: roundPrice(bestPrice + tickSize * index * (side === 'ask' ? 1 : -1), tickSize),
    amount: Number((1 + index * 0.2).toFixed(8))
  }))
}

function createTrade(quote: TradingQuote): TradeItem {
  return {
    id: `${quote.symbol}-${quote.timestamp}`,
    price: quote.mid,
    amount: 1,
    side: quote.mid >= quote.bid ? 'buy' : 'sell',
    time: quote.timestamp
  }
}

function getTickSize(quote: TradingQuote) {
  if (quote.spread > 0) return roundTick(quote.spread / 4)
  if (quote.symbol.endsWith('JPY')) return 0.001
  if (quote.symbol.includes('BTC') || quote.symbol.includes('ETH')) return 0.1
  return 0.00001
}

function inferFallbackSpread(symbol: string, last: number) {
  if (symbol.endsWith('JPY')) return 0.01
  if (symbol.includes('BTC')) return Math.max(1, last * 0.00006)
  if (symbol.includes('ETH')) return Math.max(0.1, last * 0.00008)
  return 0.00004
}

function roundTick(value: number) {
  if (value >= 1) return 1
  if (value >= 0.1) return 0.1
  if (value >= 0.01) return 0.01
  if (value >= 0.001) return 0.001
  return 0.00001
}

function roundPrice(price: number, tickSize: number) {
  const precision = String(tickSize).includes('.') ? String(tickSize).split('.')[1].length : 0
  return Number(price.toFixed(precision))
}
