import { ApiClientError, apiGet, apiPost } from '../api/apiClient.ts'
import type {
  MessagePageWire,
  PopupClaimWire,
  PopupOutcomeWire,
  PopupSurfaceWire,
  UnreadCountWire
} from './engagementTypes.ts'

export type PopupSurface = PopupSurfaceWire

export function createEngagementApiClient(options: { timeoutMs?: number } = {}) {
  const timeoutMs = options.timeoutMs ?? 10_000
  const post = <T>(path: string, body: unknown, accessToken: string) =>
    timed(() => apiPost<T>(path, body, accessToken, { signal: AbortSignal.timeout(timeoutMs) }))
  const get = <T>(path: string, accessToken: string) =>
    timed(() => apiGet<T>(path, accessToken, { signal: AbortSignal.timeout(timeoutMs) }))
  const surfaceBody = (surface: PopupSurface) => ({
    triggerType: surface.triggerType,
    pageKey: surface.pageKey,
    deviceClass: surface.deviceClass
  })
  const delivery = (deliveryToken: string, outcome: string, accessToken: string) =>
    post<PopupOutcomeWire>(
      `/api/me/engagement/popup-deliveries/${encodeURIComponent(deliveryToken)}/${outcome}`,
      undefined,
      accessToken
    )
  const publication = (publicationId: string, action: string, accessToken: string) =>
    post<void>(`/api/me/messages/${encodeURIComponent(publicationId)}/${action}`, undefined, accessToken)

  return Object.freeze({
    startQueue: ({ accessToken, surface }: { accessToken: string; surface: PopupSurface }) =>
      post<PopupClaimWire | null>('/api/me/engagement/popup-queues', surfaceBody(surface), accessToken),
    nextPopup: ({ accessToken, queueSessionId, surface }: {
      accessToken: string
      queueSessionId: string
      surface: PopupSurface
    }) => post<PopupClaimWire | null>(
      `/api/me/engagement/popup-queues/${encodeURIComponent(queueSessionId)}/next`,
      surfaceBody(surface),
      accessToken
    ),
    markShown: ({ accessToken, deliveryToken }: AuthDelivery) => delivery(deliveryToken, 'shown', accessToken),
    closePopup: ({ accessToken, deliveryToken }: AuthDelivery) => delivery(deliveryToken, 'close', accessToken),
    optOutPopup: ({ accessToken, deliveryToken }: AuthDelivery) => delivery(deliveryToken, 'opt-out', accessToken),
    clickPopup: ({ accessToken, deliveryToken }: AuthDelivery) => delivery(deliveryToken, 'click', accessToken),
    listMessages: ({ accessToken, page, size, unreadOnly }: MessageQuery) => get<MessagePageWire>(
      `/api/me/messages?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}&unreadOnly=${encodeURIComponent(unreadOnly)}`,
      accessToken
    ),
    getUnreadCount: ({ accessToken }: Auth) => get<UnreadCountWire>('/api/me/messages/unread-count', accessToken),
    markMessageRead: ({ accessToken, publicationId }: AuthPublication) => publication(publicationId, 'read', accessToken),
    markMessageUnread: ({ accessToken, publicationId }: AuthPublication) => publication(publicationId, 'unread', accessToken),
    markAllMessagesRead: ({ accessToken }: Auth) => post<void>('/api/me/messages/read-all', undefined, accessToken),
    hideMessage: ({ accessToken, publicationId }: AuthPublication) => publication(publicationId, 'hide', accessToken)
  })
}

type Auth = { accessToken: string }
type AuthDelivery = Auth & { deliveryToken: string }
type AuthPublication = Auth & { publicationId: string }
type MessageQuery = Auth & { page: number; size: number; unreadOnly: boolean }

async function timed<T>(request: () => Promise<T>): Promise<T> {
  try {
    return await request()
  } catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new ApiClientError({ status: 0, code: 'REQUEST_TIMEOUT', message: 'Request timed out' })
    }
    throw error
  }
}
