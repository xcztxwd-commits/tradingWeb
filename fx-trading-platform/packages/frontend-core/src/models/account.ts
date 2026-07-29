import type { Amount } from './trading.ts'
import type {
  OrderOrigin,
  OrderResponse as GeneratedOrderResponse,
  PositionResponse as GeneratedPositionResponse,
  ProductType,
  ProtectionType,
  QuantityUnit,
  TriggerExecutionType,
  TriggerPriceType
} from '@fx-platform/shared-types'

export type OrderResponse = {
  id: string
  accountId?: string
  clientOrderId?: GeneratedOrderResponse['clientOrderId'] | null
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
  productType?: ProductType | null
  origin?: OrderOrigin | null
  orderOrigin?: OrderOrigin | null
  protectionType?: ProtectionType | null
  parentOrderId?: string | null
  parentPositionId?: string | null
  contingencyGroupId?: string | null
  triggerPrice?: Amount | null
  triggerPriceType?: TriggerPriceType | null
  triggerExecutionType?: TriggerExecutionType | null
  quantityUnit?: QuantityUnit | null
  version?: number | null
}

type PositionResponseOverrides = {
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
  version?: number | null
}

export type PositionResponse = Omit<GeneratedPositionResponse, keyof PositionResponseOverrides> & PositionResponseOverrides
