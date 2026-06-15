import type { TradeMarket } from '../types/order'
import { parseSymbolAssets } from '../utils/symbols.ts'

type Snapshot = {
  bids: Array<{ price: number }>
  asks: Array<{ price: number }>
  lastPrice: number
}

export function createPanelMarket(symbol: string, snapshot: Snapshot): TradeMarket {
  const { baseAsset, quoteAsset } = parseSymbolAssets(symbol)
  const normalizedSymbol = `${baseAsset}-${quoteAsset}`

  const bestBid = snapshot.bids[0]?.price ?? 0
  const bestAsk = snapshot.asks[0]?.price ?? 0
  const lastPrice = snapshot.lastPrice || (bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2 : 0)

  return {
    symbol: normalizedSymbol,
    lastPrice,
    bestBid,
    bestAsk,
    baseAsset,
    quoteAsset
  }
}
