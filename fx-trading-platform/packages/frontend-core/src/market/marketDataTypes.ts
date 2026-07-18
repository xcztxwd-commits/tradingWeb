export type OrderBookLevel = {
  price: number
  amount: number
}

export type LastPriceDirection = 'up' | 'down' | 'flat'

export type OrderBookState = {
  asks: OrderBookLevel[]
  bids: OrderBookLevel[]
  lastPrice: number
  lastPriceDirection: LastPriceDirection
}

export type TradeItem = {
  id: string
  price: number
  amount: number
  side: 'buy' | 'sell'
  time: number
}

export type OrderBookSide = 'bid' | 'ask'

export type MarketDataSnapshot = OrderBookState & {
  symbol?: string
  recentTrades: TradeItem[]
  updatedAt?: number
  source?: MarketSourceMetadata
  componentSources?: {
    quote: MarketSourceMetadata
    orderBook: MarketSourceMetadata
    trades: MarketSourceMetadata
  }
  status?: 'loading' | 'ready' | 'stale' | 'source-changing' | 'unavailable'
  tradable?: boolean
}

export type MarketOrderBook = OrderBookState & {
  symbol: string
  source?: MarketSourceMetadata
}

export type MarketTradeBatch = {
  symbol: string
  recentTrades: TradeItem[]
  source?: MarketSourceMetadata
}
import type { MarketSourceMetadata } from './tradingModels.ts'
