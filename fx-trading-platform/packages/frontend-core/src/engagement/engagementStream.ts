import { subscribeStompTopic } from '../realtime/stompStream.ts'
import type { EngagementUpdateSubscription } from './popupQueueController.ts'

export type EngagementUpdateEvent = {
  updateType: 'CAMPAIGN_UPDATED' | 'CAMPAIGN_INVALIDATED' | 'MESSAGE_UPDATED'
  aggregateId: string
  occurredAt: string
}

export function subscribeEngagementUpdates(
  token: string,
  onEvent: (event: EngagementUpdateEvent) => void,
  onReconnect?: () => void
) {
  const deliver = (message: unknown) => {
    if (isEngagementUpdateEvent(message)) onEvent(message)
  }
  const unsubscribeTopic = subscribeStompTopic('/topic/engagement/updates', token, deliver, onReconnect)
  const unsubscribeUserQueue = subscribeStompTopic('/user/queue/engagement-updates', token, deliver)
  let disposed = false

  return () => {
    if (disposed) return
    disposed = true
    unsubscribeTopic()
    unsubscribeUserQueue()
  }
}

export const engagementUpdateSubscription: EngagementUpdateSubscription = Object.freeze({
  subscribe(accessToken, listener) {
    return subscribeEngagementUpdates(accessToken, listener.onUpdate, listener.onReconnect)
  }
})

function isEngagementUpdateEvent(message: unknown): message is EngagementUpdateEvent {
  if (!message || typeof message !== 'object') return false
  const event = message as Partial<EngagementUpdateEvent>
  return (
    (event.updateType === 'CAMPAIGN_UPDATED' ||
      event.updateType === 'CAMPAIGN_INVALIDATED' ||
      event.updateType === 'MESSAGE_UPDATED') &&
    typeof event.aggregateId === 'string' &&
    typeof event.occurredAt === 'string'
  )
}
