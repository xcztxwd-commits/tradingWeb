export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP'
export type OrderStatus = 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED'
export type PositionStatus = 'OPEN' | 'CLOSED'

export type WsEventType = 'quote' | 'candle' | 'order_update' | 'position_update' | 'account_update'

export type { components, operations, paths } from './generated/openapi.ts'
export type { ApiResponse, AuthResponse, BackendSchemas, SessionStatus } from './apiTypes.ts'
export {
  DEFAULT_API_ERROR_MESSAGES,
  STANDARD_API_ERROR_CODES,
  friendlyApiErrorMessage
} from './errorCodes.ts'
export type { ApiErrorCode, StandardApiErrorCode } from './errorCodes.ts'
