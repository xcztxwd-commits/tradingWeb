import type { TradingQuote } from './tradingModels.ts'
import type { MarketDataSnapshot, OrderBookLevel, TradeItem } from './marketDataTypes'

const levelCount = 42

export function createQuoteMarketDataSnapshot(quote: TradingQuote): MarketDataSnapshot {
  const tickSize = getTickSize(quote)

  return {
    asks: createLevels(quote.ask, tickSize, 'ask'),
    bids: createLevels(quote.bid, tickSize, 'bid'),
    lastPrice: quote.mid,
    lastPriceDirection: 'flat',
    recentTrades: [createTrade(quote)],
    updatedAt: quote.timestamp
  }
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
  if (quote.symbol.includes('XRP')) return 0.0001
  if (quote.symbol.endsWith('USDT')) return 0.1
  return 0.00001
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
