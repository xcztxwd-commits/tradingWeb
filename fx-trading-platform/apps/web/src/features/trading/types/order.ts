export type TradeSide = 'buy' | 'sell'

export type TradeProductType = 'FX_MARGIN' | 'CRYPTO_SPOT' | 'LINEAR_PERP' | 'INVERSE_PERP'

export type PrimaryOrderType = 'limit' | 'market'

export type StrategyType =
  | 'none'
  | 'tp_sl'
  | 'trailing_tp_sl'
  | 'trigger'
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
}

export type TradeField = keyof TradeFormState

export type OrderValidationErrorKey =
  | 'price'
  | 'amount'
  | 'minAmount'
  | 'minNotional'
  | 'quoteBalance'
  | 'baseBalance'
  | 'takeProfitTriggerPrice'
  | 'stopLossTriggerPrice'
  | 'trailingCallbackRatio'
  | 'triggerPrice'

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
  { value: 'tp_sl', labelKey: 'trading.strategy.tpSl', available: true },
  { value: 'trailing_tp_sl', labelKey: 'trading.strategy.trailingTpSl', available: true },
  { value: 'trigger', labelKey: 'trading.strategy.trigger', available: true },
  { value: 'advanced_limit', labelKey: 'trading.strategy.advancedLimit', available: true },
  { value: 'split_order', labelKey: 'trading.strategy.splitOrder', available: false },
  { value: 'iceberg', labelKey: 'trading.strategy.iceberg', available: false },
  { value: 'twap', labelKey: 'trading.strategy.twap', available: false }
]

export const advancedLimitModes: Array<{ value: AdvancedLimitMode; labelKey: string }> = [
  { value: 'normal', labelKey: 'trading.advancedLimitModes.normal' },
  { value: 'post_only', labelKey: 'trading.advancedLimitModes.postOnly' },
  { value: 'fok', labelKey: 'trading.advancedLimitModes.fok' },
  { value: 'ioc', labelKey: 'trading.advancedLimitModes.ioc' }
]
