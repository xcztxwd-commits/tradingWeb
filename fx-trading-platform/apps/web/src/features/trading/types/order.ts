import type { TradingInstrumentRules } from '@fx-platform/frontend-core'
import type {
  CreateOrderRequest,
  MarginMode,
  PositionSide,
  QuantityUnit
} from '@fx-platform/shared-types'

export type TradeSide = 'buy' | 'sell'

export type TradeProductType = 'FX_MARGIN' | 'CRYPTO_SPOT' | 'LINEAR_PERP' | 'INVERSE_PERP'

export type PrimaryOrderType = 'limit' | 'market'

export type TradeInstrumentRules = TradingInstrumentRules

export type StrategyType =
  | 'none'
  | 'tp_sl'
  | 'trailing_tp_sl'
  | 'trigger'
  | 'oco'
  | 'advanced_limit'
  | 'split_order'
  | 'iceberg'
  | 'twap'

export type AdvancedLimitMode = 'normal' | 'post_only' | 'fok' | 'ioc'

export type TpSlMode = 'take_profit' | 'stop_loss' | 'both'

export type TimeInForce = 'gtc' | 'post_only' | 'fok' | 'ioc'

export type TradeMarket = {
  symbol: string
  lastPrice: number
  bestBid: number
  bestAsk: number
  baseAsset: string
  quoteAsset: string
  unitSize?: number
  quantityMode?: 'quantity' | 'quote-budget' | 'contracts'
  leverage?: number
  productType?: TradeProductType
  quoteTimestamp?: number
  tradable?: boolean
  rules?: TradeInstrumentRules
}

export type TradeBalances = Record<string, number>

export type TradeFormState = {
  symbol: string
  side: TradeSide
  orderType: PrimaryOrderType
  strategyType: StrategyType
  price: string
  amount: string
  total: string
  percent: number
  tpSlEnabled: boolean
  tpSlMode: TpSlMode
  takeProfitEnabled: boolean
  stopLossEnabled: boolean
  takeProfitTriggerPrice: string
  takeProfitOrderPrice: string
  stopLossTriggerPrice: string
  stopLossOrderPrice: string
  trailingCallbackRatio: string
  trailingActivationPrice: string
  triggerPrice: string
  advancedLimitMode: AdvancedLimitMode
  timeInForce: TimeInForce
  clientOrderId: string
  positionSide: PositionSide
  marginMode: MarginMode
  quantityUnit: QuantityUnit
  reduceOnly: boolean
  attachedProtections: NonNullable<CreateOrderRequest['attachedProtections']>
}

export type TradeField = keyof TradeFormState

export type OrderValidationErrorKey =
  | 'price'
  | 'amount'
  | 'minAmount'
  | 'minNotional'
  | 'quoteBalance'
  | 'baseBalance'
  | 'marketStale'
  | 'takeProfitTriggerPrice'
  | 'stopLossTriggerPrice'
  | 'trailingCallbackRatio'
  | 'triggerPrice'
  | 'attachedProtections'

export type OrderValidationResult = {
  errors: OrderValidationErrorKey[]
  fieldErrors: Partial<Record<OrderValidationErrorKey, string>>
  canSubmit: boolean
}

export type MockOrderPayload = TradeFormState & {
  marketPrice: number
  submittedAt: string
}

export type MockOrderResponse = {
  success: true
  orderId: string
  status: 'submitted'
  payload: MockOrderPayload
}

export type StrategyOption = {
  value: Exclude<StrategyType, 'none'>
  labelKey: string
  available: boolean
}

export const strategyOptions: StrategyOption[] = [
  { value: 'trigger', labelKey: 'trading.strategy.trigger', available: true },
  { value: 'oco', labelKey: 'trading.strategy.oco', available: true }
]

export const advancedLimitModes: Array<{ value: AdvancedLimitMode; labelKey: string }> = [
  { value: 'normal', labelKey: 'trading.advancedLimitModes.normal' },
  { value: 'post_only', labelKey: 'trading.advancedLimitModes.postOnly' },
  { value: 'fok', labelKey: 'trading.advancedLimitModes.fok' },
  { value: 'ioc', labelKey: 'trading.advancedLimitModes.ioc' }
]
