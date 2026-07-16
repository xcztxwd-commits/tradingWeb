import type { TradeBalances, TradeMarket } from '../types/order'

export const testMarket: TradeMarket = {
  symbol: 'BTC-USDT',
  lastPrice: 60_733.3,
  bestBid: 60_732.2,
  bestAsk: 60_736.3,
  baseAsset: 'BTC',
  quoteAsset: 'USDT',
  unitSize: 1,
  quantityMode: 'quote-budget',
  leverage: 1
}

export const testBalances: TradeBalances = {
  USDT: 10_000,
  BTC: 0.25
}
