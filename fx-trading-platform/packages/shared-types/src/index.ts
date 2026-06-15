export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP'
export type OrderStatus = 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED'
export type PositionStatus = 'OPEN' | 'CLOSED'

export type WsEventType = 'quote' | 'candle' | 'order_update' | 'position_update' | 'account_update'
