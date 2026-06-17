import type { Quote } from '../types/trading'
import { Client } from '@stomp/stompjs'
import type { IMessage, StompSubscription } from '@stomp/stompjs'

export type TradingSessionStreamEvent = {
  type: 'ORDER_EVENT' | 'WALLET_EVENT' | string
  accountId?: string
  orderId?: string
  eventId?: string
  eventType?: string
  status?: string
  createdAt?: string
}

type TopicHandler = (message: unknown) => void
type MarketStreamLocation = Pick<Location, 'protocol' | 'host'>

interface TopicSubscription {
  handlers: Set<TopicHandler>
  stompSubscription?: StompSubscription
}

class MarketStreamSession {
  token: string | null
  client: Client
  connected = false
  disposed = false
  subscriptions = new Map<string, TopicSubscription>()

  constructor(token: string | null) {
    this.token = token

    this.client = new Client({
      brokerURL: resolveMarketStreamBrokerUrl(getApiBaseUrl(), window.location),
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 5000,
      onConnect: () => {
        this.connected = true
        this.resubscribeTopics()
      },
      onDisconnect: () => {
        this.connected = false
      },
      onWebSocketClose: () => {
        this.connected = false
        for (const subscription of this.subscriptions.values()) {
          subscription.stompSubscription = undefined
        }
      }
    })

    // 市场推送只负责增量刷新，REST 仍作为首屏和断线后的兜底数据源。
    this.client.activate()
  }

  subscribe<T>(topic: string, handler: (message: T) => void) {
    let subscription = this.subscriptions.get(topic)
    if (!subscription) {
      subscription = { handlers: new Set<TopicHandler>() }
      this.subscriptions.set(topic, subscription)
    }

    subscription.handlers.add(handler as TopicHandler)
    if (this.connected && !subscription.stompSubscription) {
      this.subscribeStompTopic(topic, subscription)
    }
  }

  unsubscribe<T>(topic: string, handler: (message: T) => void) {
    const subscription = this.subscriptions.get(topic)
    if (!subscription) return

    subscription.handlers.delete(handler as TopicHandler)
    if (subscription.handlers.size > 0) return

    subscription.stompSubscription?.unsubscribe()
    this.subscriptions.delete(topic)

    if (this.subscriptions.size === 0) {
      this.dispose()
      if (activeMarketStreamSession === this) {
        activeMarketStreamSession = undefined
      }
    }
  }

  dispose() {
    if (this.disposed) return

    this.disposed = true
    this.connected = false
    for (const subscription of this.subscriptions.values()) {
      subscription.stompSubscription?.unsubscribe()
    }
    this.subscriptions.clear()
    void this.client.deactivate()
  }

  private resubscribeTopics() {
    if (this.disposed) return

    for (const [topic, subscription] of this.subscriptions) {
      if (subscription.handlers.size > 0 && !subscription.stompSubscription) {
        this.subscribeStompTopic(topic, subscription)
      }
    }
  }

  private subscribeStompTopic(topic: string, subscription: TopicSubscription) {
    subscription.stompSubscription = this.client.subscribe(topic, (message: IMessage) => {
      let parsedMessage: unknown
      try {
        parsedMessage = JSON.parse(message.body)
      } catch (error) {
        console.warn('Failed to parse market stream message', { topic, error })
        return
      }

      for (const handler of subscription.handlers) {
        try {
          handler(parsedMessage)
        } catch (error) {
          console.warn('Failed to deliver market stream message', { topic, error })
        }
      }
    })
  }
}

export function resolveMarketStreamBrokerUrl(apiBaseUrl: string | undefined, location: MarketStreamLocation) {
  if (apiBaseUrl && apiBaseUrl.trim()) {
    const url = new URL(apiBaseUrl)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.pathname = '/ws'
    url.search = ''
    url.hash = ''
    return url.toString()
  }

  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${location.host}/ws`
}

function getApiBaseUrl() {
  return (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL
}

let activeMarketStreamSession: MarketStreamSession | undefined

export function subscribeQuote(symbol: string, token: string | null, onQuote: (quote: Quote) => void) {
  return subscribeMarketTopic(`/topic/market/quotes/${normalizeMarketStreamSymbol(symbol)}`, token, onQuote)
}

export function subscribeOrderBook<T>(symbol: string, token: string | null, onOrderBook: (orderBook: T) => void) {
  return subscribeMarketTopic(`/topic/market/order-book/${normalizeMarketStreamSymbol(symbol)}`, token, onOrderBook)
}

export function subscribeRecentTrades<T>(symbol: string, token: string | null, onTrades: (trades: T) => void) {
  return subscribeMarketTopic(`/topic/market/trades/${normalizeMarketStreamSymbol(symbol)}`, token, onTrades)
}

export function subscribeTradingSessionEvents(
  accountId: string,
  token: string,
  onEvent: (event: TradingSessionStreamEvent) => void
) {
  return subscribeMarketTopic(`/topic/trading/accounts/${accountId}/events`, token, onEvent)
}

function subscribeMarketTopic<T>(topic: string, token: string | null, onMessage: (message: T) => void) {
  let disposed = false
  const session = ensureMarketStreamSession(token)

  session.subscribe(topic, onMessage)

  return () => {
    if (disposed) return

    disposed = true
    session.unsubscribe(topic, onMessage)
  }
}

function normalizeMarketStreamSymbol(symbol: string) {
  return symbol.replace(/[-_/]/g, '').toUpperCase()
}

function ensureMarketStreamSession(token: string | null) {
  if (activeMarketStreamSession && activeMarketStreamSession.token === token && !activeMarketStreamSession.disposed) {
    return activeMarketStreamSession
  }

  activeMarketStreamSession?.dispose()
  activeMarketStreamSession = new MarketStreamSession(token)
  return activeMarketStreamSession
}
