import type { TradeBalances, TradeMarket } from '../types/order'

export const mockMarket: TradeMarket = {
  symbol: 'BTC-USDT',
  lastPrice: 60733.3,
  bestBid: 60732.2,
  bestAsk: 60736.3,
  baseAsset: 'BTC',
  quoteAsset: 'USDT',
  unitSize: 1,
  quantityMode: 'quote-budget',
  leverage: 1
}

export const mockBalances: TradeBalances = {
  USDT: 10000,
  BTC: 0.25
}

export function useMockBalances() {
  return mockBalances
}
