import type { OrderBookLevel, OrderBookSide } from '../../features/market/marketDataTypes'

export type {
  LastPriceDirection,
  MarketDataSnapshot,
  OrderBookLevel,
  OrderBookSide,
  OrderBookState,
  TradeItem
} from '../../features/market/marketDataTypes'

export type OrderBookDisplayMode = 'both' | 'bids' | 'asks'
export type MarketSidePanelTab = 'orderbook' | 'trades'
export type OrderBookLayoutMode = 'orderbook' | 'trades' | 'split'
export type AggregationStep = 0.1 | 1 | 10 | 100

export const aggregationSteps: AggregationStep[] = [0.1, 1, 10, 100]

export type AggregatedOrderBookRow = OrderBookLevel & {
  side: OrderBookSide
  cumulativeTotal: number
}

export type BuiltOrderBookRows = {
  asks: AggregatedOrderBookRow[]
  bids: AggregatedOrderBookRow[]
  maxCumulativeTotal: number
}

export type OrderBookSettings = {
  showAverageAndTotal: boolean
  showBidAskRatio: boolean
  showDepthBars: boolean
  layoutMode: OrderBookLayoutMode
}
