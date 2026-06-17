import type { Amount } from '../../types/trading'

export type OrderResponse = {
  id: string
  accountId?: string
  symbol: string
  side: string
  orderType: string
  leverage?: number | null
  status: string
  lots: Amount
  quantity?: Amount
  price?: Amount | null
  executionPrice: Amount | null
  filledQuantity?: Amount
  remainingQuantity?: Amount | null
  avgFillPrice?: Amount | null
  fee?: Amount | null
  slippage?: Amount | null
  holdAmount?: Amount | null
  holdCurrency?: string | null
  rejectCode?: string | null
  rejectMessage?: string | null
  createdAt: string
  updatedAt?: string | null
  filledAt?: string | null
  canceledAt?: string | null
}

export type PositionResponse = {
  id: string
  symbol: string
  side: string
  instrumentType?: string | null
  marginMode?: string | null
  leverage?: number | null
  positionUnit?: string | null
  lots: Amount
  openPrice: Amount
  markPrice?: Amount | null
  currentPrice: Amount
  notional?: Amount | null
  liquidationPrice?: Amount | null
  breakEvenPrice?: Amount | null
  stopLoss?: Amount | null
  takeProfit?: Amount | null
  floatingPnl: Amount
  floatingPnlRatio?: Amount | null
  realizedPnl: Amount
  fundingPnl?: Amount | null
  marginHeld: Amount
  maintenanceMargin?: Amount | null
  maintenanceMarginRate?: Amount | null
  adlLevel?: number | null
  status: string
  openedAt?: string | null
  closedAt?: string | null
}
