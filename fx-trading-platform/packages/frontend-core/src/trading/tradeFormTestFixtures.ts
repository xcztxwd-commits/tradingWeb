import type { TradeBalances, TradeMarket } from './orderTypes.ts'

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

export function createTestTradeMarket(
  symbol: string,
  overrides: Partial<TradeMarket> = {}
): TradeMarket {
  const normalized = symbol.trim().toUpperCase()
  const perpetual = normalized.endsWith('-PERP')
  const instrument = perpetual ? normalized.slice(0, -'-PERP'.length) : normalized
  const quoteAsset = instrument.endsWith('USDT') ? 'USDT' : instrument.slice(-3)
  const baseAsset = instrument.slice(0, -quoteAsset.length)
  return {
    symbol: normalized,
    lastPrice: 0,
    bestBid: 0,
    bestAsk: 0,
    baseAsset,
    quoteAsset,
    unitSize: 1,
    quantityMode: perpetual ? 'quantity' : 'quote-budget',
    tradable: true,
    ...overrides
  }
}
