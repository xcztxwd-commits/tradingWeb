import { Client } from '@stomp/stompjs'
import type { IMessage, StompSubscription } from '@stomp/stompjs'

type MessageHandler = (message: unknown) => void
type ReconnectHandler = () => void
type StreamLocation = Pick<Location, 'protocol' | 'host'>

interface TopicSubscription {
  handlers: Set<MessageHandler>
  reconnectHandlers: Set<ReconnectHandler>
  stompSubscription?: StompSubscription
}

const sessionsByWindow = new WeakMap<object, Map<string | null, StompStreamSession>>()

class StompStreamSession {
  private readonly client: Client
  private readonly onIdle: () => void
  private readonly subscriptions = new Map<string, TopicSubscription>()
  private connected = false
  private hasConnected = false
  private reconnectPending = false
  private disposed = false

  constructor(token: string | null, onIdle: () => void) {
    this.onIdle = onIdle
    this.client = new Client({
      brokerURL: resolveStompBrokerUrl(getApiBaseUrl(), window.location),
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 5000,
      onConnect: () => {
        const reconnected = this.reconnectPending
        this.connected = true
        this.hasConnected = true
        this.reconnectPending = false
        this.resubscribeTopics()
        if (reconnected) this.notifyReconnect()
      },
      onDisconnect: () => {
        this.connected = false
        if (this.hasConnected) this.reconnectPending = true
      },
      onWebSocketClose: () => {
        this.connected = false
        if (this.hasConnected) this.reconnectPending = true
        for (const subscription of this.subscriptions.values()) {
          subscription.stompSubscription = undefined
        }
      }
    })
    this.client.activate()
  }

  subscribe<T>(topic: string, handler: (message: T) => void, onReconnect?: ReconnectHandler) {
    let subscription = this.subscriptions.get(topic)
    if (!subscription) {
      subscription = { handlers: new Set<MessageHandler>(), reconnectHandlers: new Set<ReconnectHandler>() }
      this.subscriptions.set(topic, subscription)
    }

    subscription.handlers.add(handler as MessageHandler)
    if (onReconnect) subscription.reconnectHandlers.add(onReconnect)
    if (this.connected && !subscription.stompSubscription) this.subscribeStompTopic(topic, subscription)
  }

  unsubscribe<T>(topic: string, handler: (message: T) => void, onReconnect?: ReconnectHandler) {
    const subscription = this.subscriptions.get(topic)
    if (!subscription) return

    subscription.handlers.delete(handler as MessageHandler)
    if (onReconnect) subscription.reconnectHandlers.delete(onReconnect)
    if (subscription.handlers.size > 0) return

    subscription.stompSubscription?.unsubscribe()
    this.subscriptions.delete(topic)
    if (this.subscriptions.size === 0) this.dispose()
  }

  private dispose() {
    if (this.disposed) return
    this.disposed = true
    this.connected = false
    this.onIdle()
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

  private notifyReconnect() {
    if (this.disposed) return
    for (const subscription of this.subscriptions.values()) {
      for (const handler of subscription.reconnectHandlers) handler()
    }
  }

  private subscribeStompTopic(topic: string, subscription: TopicSubscription) {
    subscription.stompSubscription = this.client.subscribe(topic, (message: IMessage) => {
      let parsedMessage: unknown
      try {
        parsedMessage = JSON.parse(message.body)
      } catch (error) {
        console.warn('Failed to parse realtime stream message', { topic, error })
        return
      }

      for (const handler of subscription.handlers) {
        try {
          handler(parsedMessage)
        } catch (error) {
          console.warn('Failed to deliver realtime stream message', { topic, error })
        }
      }
    })
  }
}

export function subscribeStompTopic<T>(
  topic: string,
  token: string | null,
  onMessage: (message: T) => void,
  onReconnect?: ReconnectHandler
) {
  let disposed = false
  const session = ensureStompStreamSession(token)
  const messageLease = (message: T) => onMessage(message)
  const reconnectLease = onReconnect ? () => onReconnect() : undefined
  session.subscribe(topic, messageLease, reconnectLease)

  return () => {
    if (disposed) return
    disposed = true
    session.unsubscribe(topic, messageLease, reconnectLease)
  }
}

export function resolveStompBrokerUrl(apiBaseUrl: string | undefined, location: StreamLocation) {
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

function ensureStompStreamSession(token: string | null) {
  const owner = window as object
  let sessions = sessionsByWindow.get(owner)
  if (!sessions) {
    sessions = new Map<string | null, StompStreamSession>()
    sessionsByWindow.set(owner, sessions)
  }

  const existing = sessions.get(token)
  if (existing) return existing

  const session = new StompStreamSession(token, () => sessions.delete(token))
  sessions.set(token, session)
  return session
}

function getApiBaseUrl() {
  return (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL
}
