import type { Quote } from '../models/trading.ts'
import { resolveStompBrokerUrl, subscribeStompTopic } from '../realtime/stompStream.ts'
import { normalizePlatformMarketSymbol } from './marketSymbol.ts'

export type TradingSessionStreamEvent = {
  type: 'ORDER_EVENT' | 'WALLET_EVENT' | string
  accountId?: string
  orderId?: string
  eventId?: string
  eventType?: string
  status?: string
  createdAt?: string
}

export type MarketSourceChangedEvent = {
  type: 'MARKET_SOURCE_CHANGED'
  symbol: string
  previousProviderCode?: string | null
  previousSourceMode?: 'PUBLIC_EXTERNAL' | 'LOCAL_SIMULATED' | null
  providerCode: string
  sourceMode: 'PUBLIC_EXTERNAL' | 'LOCAL_SIMULATED'
  changedAt?: string
  asOf?: string
  expiresAt?: string
  stale?: boolean
}

export function resolveMarketStreamBrokerUrl(
  apiBaseUrl: string | undefined,
  location: Pick<Location, 'protocol' | 'host'>
) {
  return resolveStompBrokerUrl(apiBaseUrl, location)
}

export function subscribeQuote<T = Quote>(symbol: string, token: string | null, onQuote: (quote: T) => void) {
  return subscribeMarketTopic(`/topic/market/quotes/${normalizeMarketStreamSymbol(symbol)}`, token, onQuote)
}

export function subscribeOrderBook<T>(symbol: string, token: string | null, onOrderBook: (orderBook: T) => void) {
  return subscribeMarketTopic(`/topic/market/order-book/${normalizeMarketStreamSymbol(symbol)}`, token, onOrderBook)
}

export function subscribeRecentTrades<T>(symbol: string, token: string | null, onTrades: (trades: T) => void) {
  return subscribeMarketTopic(`/topic/market/trades/${normalizeMarketStreamSymbol(symbol)}`, token, onTrades)
}

export function subscribeMarketSourceChanges(
  symbol: string,
  token: string | null,
  onSourceChange: (event: MarketSourceChangedEvent) => void
) {
  return subscribeMarketTopic(
    `/topic/market/source-changes/${normalizeMarketStreamSymbol(symbol)}`,
    token,
    onSourceChange
  )
}

export function subscribeTradingSessionEvents(
  token: string,
  onEvent: (event: TradingSessionStreamEvent) => void,
  onReconnect?: () => void
) {
  return subscribeMarketTopic('/user/queue/trading-events', token, onEvent, onReconnect)
}

function subscribeMarketTopic<T>(
  topic: string,
  token: string | null,
  onMessage: (message: T) => void,
  onReconnect?: () => void
) {
  return subscribeStompTopic(topic, token, onMessage, onReconnect)
}

function normalizeMarketStreamSymbol(symbol: string) {
  return normalizePlatformMarketSymbol(symbol)
}
