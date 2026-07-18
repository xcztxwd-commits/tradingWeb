import type {
  CreateOcoOrderRequest,
  CreateOrderRequest,
  MarginMode,
  PositionMode,
  PositionSide,
  QuantityUnit,
  TradingSettingsResponse,
  UpdateOrderRequest,
  UpdatePositionModeRequest,
  UpdateSymbolSettingsRequest
} from '@fx-platform/shared-types'

export type SymbolItem = {
  symbol: string
  displayName: string
  assetClass: string
  baseCurrency: string
  quoteCurrency: string
  minLot: string
  maxLot: string
  leverage: number
  enabled: boolean
  provider?: string | null
  providerSymbol?: string | null
  tradable?: boolean | null
}

export type Quote = {
  type: 'quote'
  symbol: string
  bid: string
  ask: string
  mid: string
  spread: string
  source: string
  timestamp: number
}

export type Candle = {
  timestamp: number
  open: string
  high: string
  low: string
  close: string
  volume: string
}

export type Amount = string | number

export type WalletType = 'FX_MARGIN' | 'SPOT' | 'USDT_PERP' | 'COIN_PERP' | 'FUNDING'

export type AccountSummary = {
  id: string
  accountType: string
  baseCurrency: string
  balance: Amount
  equity: Amount
  usedMargin: Amount
  freeMargin: Amount
  marginLevel: Amount | null
  leverage: number
  status: string
  openFloatingPnl?: Amount | null
  maintenanceMargin?: Amount | null
  positionValue?: Amount | null
  marginAvailable?: Amount | null
  lastSnapshotAt?: string | null
  warning?: string | null
}

export type WalletBalance = {
  id: string
  accountId: string
  walletType: WalletType | string
  asset: string
  total: Amount
  available: Amount
  locked: Amount
}

export type AssetLedgerEntry = {
  id: string
  accountId: string
  walletType: WalletType | string
  asset: string
  amount: Amount
  balanceAfter: Amount
  entryType: string
  referenceType: string | null
  referenceId: string | null
  description: string | null
  createdAt: string | null
}

export type AssetConversionPayload = {
  fromWalletType: WalletType | string
  fromAsset: string
  toWalletType: WalletType | string
  toAsset: string
  amount: string
  conversionId?: string
}

export type AssetConversionResponse = {
  accountId: string
  fromWalletType: WalletType | string
  fromAsset: string
  toWalletType: WalletType | string
  toAsset: string
  fromAmount: Amount
  toAmount: Amount
  rate: Amount
  conversionId: string
}

export type OrderPayload = Omit<
  Pick<
    CreateOrderRequest,
    | 'accountId'
    | 'symbol'
    | 'side'
    | 'orderType'
    | 'idempotencyKey'
    | 'clientOrderId'
    | 'quantity'
    | 'price'
    | 'leverage'
    | 'positionSide'
    | 'quantityUnit'
    | 'marginMode'
    | 'triggerPrice'
    | 'triggerPriceType'
    | 'reduceOnly'
    | 'timeInForce'
    | 'postOnly'
    | 'attachedProtections'
  >,
  'idempotencyKey' | 'clientOrderId' | 'quantity'
> & {
  idempotencyKey: string
  clientOrderId: string
  quantity: number
}

export type OcoOrderPayload = CreateOcoOrderRequest

export type TradingSettings = TradingSettingsResponse
export type PositionModeValue = PositionMode
export type PositionSideValue = PositionSide
export type MarginModeValue = MarginMode
export type QuantityUnitValue = QuantityUnit
export type PositionModePayload = UpdatePositionModeRequest
export type SymbolSettingsPayload = UpdateSymbolSettingsRequest

export type UpdateOrderPayload = Pick<UpdateOrderRequest, 'quantity' | 'price'>

export type UpdatePositionProtectionPayload = {
  stopLoss?: string
  takeProfit?: string
  allowImmediateTrigger?: boolean
}

export type OrderEventResponse = {
  id: string
  orderId: string
  eventType: string
  fromStatus: string | null
  toStatus: string
  reasonCode: string | null
  message: string | null
  createdAt: string
}

export type LedgerEntry = {
  id: string
  accountId: string
  entryType: string
  amount: Amount
  balanceAfter: Amount
  walletType?: WalletType | string
  currency: string
  referenceType: string | null
  referenceId: string | null
  description: string | null
  createdAt: string | null
}

export type FundOrder = {
  id: string
  userId: string
  accountId: string
  orderType: 'RECHARGE' | 'WITHDRAWAL' | string
  amount: Amount
  currency: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string
  paymentMethodId: string | null
  note: string | null
  reviewReason: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  fundOperationId: string | null
  createdAt: string | null
}

export type FundOrderPayload = {
  accountId: string
  orderType: 'RECHARGE' | 'WITHDRAWAL'
  amount: string
  currency: string
  paymentMethodId?: string | null
  note?: string
}
