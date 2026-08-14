import type { BackendSchema } from './apiTypes.ts'

export const CANONICAL_TRADING_SYMBOLS = [
  'BTCUSDT',
  'ETHUSDT',
  'BNBUSDT',
  'SOLUSDT',
  'XRPUSDT',
  'BTCUSDT-PERP',
  'ETHUSDT-PERP',
  'BNBUSDT-PERP',
  'SOLUSDT-PERP',
  'XRPUSDT-PERP'
] as const

export type CanonicalTradingSymbol = (typeof CANONICAL_TRADING_SYMBOLS)[number]
export type ProductType = 'FX_MARGIN' | 'CRYPTO_SPOT' | 'LINEAR_PERP' | 'INVERSE_PERP'
export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP_MARKET' | 'STOP_LIMIT' | 'TRAILING_STOP_MARKET'
export type OrderStatus =
  | 'RECEIVED'
  | 'VALIDATING'
  | 'ACCEPTED'
  | 'WORKING'
  | 'PARTIALLY_FILLED'
  | 'PENDING_ACTIVATION'
  | 'PENDING'
  | 'FILLED'
  | 'CANCEL_PENDING'
  | 'CANCELED'
  | 'CANCELLED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'FAILED'
export type OrderOrigin =
  | 'USER'
  | 'PROTECTIVE'
  | 'LIQUIDATION'
  | 'ADMIN_FORCE_CLOSE'
  | 'BATCH_CLOSE'
  | 'OCO'
export type PositionMode = 'ONE_WAY' | 'HEDGE'
export type PositionSide = 'BOTH' | 'LONG' | 'SHORT'
export type PositionStatus = 'OPEN' | 'CLOSED'
export type MarginMode = 'CASH' | 'CROSS' | 'ISOLATED'
export type QuantityUnit = 'BASE' | 'QUOTE' | 'CONTRACTS'
export type TimeInForce = 'GTC' | 'IOC' | 'FOK'
export type ProtectionType = 'TAKE_PROFIT' | 'STOP_LOSS'
export type TriggerPriceType = 'LAST_PRICE' | 'MARK_PRICE'
export type TriggerExecutionType = 'MARKET' | 'LIMIT'
export type LiquidityRole = 'MAKER' | 'TAKER'
export type MarketSourceMode = 'PUBLIC_EXTERNAL' | 'LOCAL_SIMULATED'
export type FundingSource = 'BINANCE' | 'OKX' | 'FIXED'
export type AccountTransferDirection = 'SPOT_TO_PERP' | 'PERP_TO_SPOT'
export type PositionMarginAction = 'ADD' | 'REDUCE'

export type CreateOrderRequest = BackendSchema<'CreateOrderRequest'>
export type UpdateOrderRequest = BackendSchema<'UpdateOrderRequest'>
export type OrderResponse = BackendSchema<'OrderResponse'>
export type OrderEventResponse = BackendSchema<'OrderEventResponse'>
export type CreateOcoOrderRequest = BackendSchema<'CreateOcoOrderRequest'>
export type OcoOrderGroupResponse = BackendSchema<'OcoOrderGroupResponse'>
export type CreateProtectionRequest = BackendSchema<'CreateProtectionRequest'>
export type UpdateProtectionRequest = BackendSchema<'UpdateProtectionRequest'>
export type ClosePositionRequest = BackendSchema<'ClosePositionRequest'>
export type PositionResponse = BackendSchema<'PositionResponse'>
export type AdjustPositionMarginRequest = BackendSchema<'AdjustPositionMarginRequest'>
export type AdjustPositionMarginResponse = BackendSchema<'AdjustPositionMarginResponse'>
export type BatchActionRequest = BackendSchema<'BatchActionRequest'>
export type CancelAllOrderRequest = BackendSchema<'CancelAllOrderRequest'>
export type BatchActionResponse = BackendSchema<'BatchActionResponse'>
export type TradingSettingsResponse = BackendSchema<'TradingSettingsResponse'>
export type UpdatePositionModeRequest = BackendSchema<'UpdatePositionModeRequest'>
export type UpdateSymbolSettingsRequest = BackendSchema<'UpdateSymbolSettingsRequest'>
export type AccountTransferRequest = BackendSchema<'AccountTransferRequest'>
export type AccountTransferResponse = BackendSchema<'AccountTransferResponse'>
export type DemoResetRequest = BackendSchema<'DemoResetRequest'>
export type DemoResetResponse = BackendSchema<'DemoResetResponse'>
export type WalletBalanceResponse = BackendSchema<'WalletBalanceResponse'>
export type AssetLedgerEntryResponse = BackendSchema<'AssetLedgerEntryResponse'>
export type Trade = BackendSchema<'TradeResponse'>
export type FundingSettlement = BackendSchema<'FundingSettlementResponse'>
export type OrderPageResponse = BackendSchema<'TradingPageResponseOrderResponse'>
export type TradePageResponse = BackendSchema<'TradingPageResponseTradeResponse'>
export type PositionPageResponse = BackendSchema<'TradingPageResponsePositionResponse'>
export type FundingSettlementPageResponse =
  BackendSchema<'TradingPageResponseFundingSettlementResponse'>
export type AccountTransferPageResponse =
  BackendSchema<'TradingPageResponseAccountTransferResponse'>
export type TradingPage<T> = Omit<TradePageResponse, 'items'> & {
  items: T[]
}
export type AdminFundingConfigRequest = BackendSchema<'AdminFundingConfigRequest'>
export type AdminFundingConfigResponse = BackendSchema<'AdminFundingConfigResponse'>
export type AdminAccountCleanupRequest = BackendSchema<'AdminAccountCleanupRequest'>
export type AdminDemoResetRequest = BackendSchema<'AdminDemoResetRequest'>
export type PerpetualReferenceResponse = BackendSchema<'PerpetualReferenceResponse'>

export type TradingEventType =
  | 'ORDER_ACCEPTED'
  | 'ORDER_PENDING'
  | 'ORDER_FILLED'
  | 'ORDER_CANCELED'
  | 'ORDER_REJECTED'
  | 'ORDER_EXPIRED'
  | 'ORDER_MODIFIED'
  | 'TRADE_CREATED'
  | 'BALANCE_UPDATED'
  | 'POSITION_UPDATED'
  | 'POSITION_CLOSED'
  | 'PROTECTION_CREATED'
  | 'PROTECTION_UPDATED'
  | 'PROTECTION_ACTIVATED'
  | 'PROTECTION_TRIGGERED'
  | 'PROTECTION_RESIZED'
  | 'PROTECTION_CANCELED'
  | 'PROTECTION_EXPIRED'
  | 'FUNDING_SETTLED'
  | 'MARGIN_ADJUSTED'
  | 'TRANSFER_COMPLETED'
  | 'LIQUIDATION'
  | 'DEMO_RESET'
  | 'MARKET_SOURCE_CHANGED'

export interface TradingSessionEvent {
  type: TradingEventType
  accountId: string
  resourceType: string
  resourceId: string
  relatedResourceId: string | null
  version: number | null
  createdAt: string
}

export interface MarketSourceChangedEvent {
  type: 'MARKET_SOURCE_CHANGED'
  symbol: string
  previousProviderCode: string
  previousSourceMode: MarketSourceMode
  providerCode: string
  sourceMode: MarketSourceMode
  changedAt: string
  asOf: string
  expiresAt: string
  stale: boolean
}

export type WsEventType =
  | 'quote'
  | 'candle'
  | 'order_update'
  | 'trade_update'
  | 'wallet_update'
  | 'position_update'
  | 'funding_update'
  | 'transfer_update'
  | 'liquidation_update'
  | 'market_source_changed'
  | 'account_update'
