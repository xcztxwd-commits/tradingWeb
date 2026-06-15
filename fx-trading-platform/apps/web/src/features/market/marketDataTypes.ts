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
  recentTrades: TradeItem[]
}
