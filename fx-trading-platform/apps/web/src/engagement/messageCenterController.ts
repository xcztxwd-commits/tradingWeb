import {
  ApiClientError,
  createMessagePageModel,
  createUnreadCountModel,
  type MessageModel,
  type MessagePageModel
} from '@fx-platform/frontend-core'

export type MessageFilter = 'ALL' | 'UNREAD'

export type MessageCenterSnapshot = Readonly<{
  unreadCount: number
  recent: readonly MessageModel[]
  page: MessagePageModel | null
  filter: MessageFilter
  summaryLoading: boolean
  pageLoading: boolean
  pendingActions: readonly string[]
  error: string | null
}>

type MessageCenterApi = Readonly<{
  getUnreadCount(input: { accessToken: string }): Promise<unknown>
  listMessages(input: {
    accessToken: string
    page: number
    size: number
    unreadOnly: boolean
  }): Promise<unknown>
  markMessageRead(input: { accessToken: string; publicationId: string }): Promise<unknown>
  markMessageUnread(input: { accessToken: string; publicationId: string }): Promise<unknown>
  markAllMessagesRead(input: { accessToken: string }): Promise<unknown>
  hideMessage(input: { accessToken: string; publicationId: string }): Promise<unknown>
}>

type PageRequest = Readonly<{ filter: MessageFilter; page: number; size: number }>

export function createMessageCenterController(options: {
  api: MessageCenterApi
  onError?: (error: unknown) => void
  onUnauthorized?: () => void
}) {
  let generation = 0
  let accessToken: string | null = null
  let summaryFlight: Promise<void> | null = null
  let summaryDirty = false
  let pageEpoch = 0
  let pageRequest: PageRequest | null = null
  const hiddenPublicationIds = new Set<string>()
  const pendingActions = new Set<string>()
  const listeners = new Set<() => void>()
  let snapshot: MessageCenterSnapshot = emptySnapshot()

  const publish = (patch: Partial<MessageCenterSnapshot> = {}) => {
    snapshot = Object.freeze({
      ...snapshot,
      ...patch,
      recent: Object.freeze([...(patch.recent ?? snapshot.recent)]),
      pendingActions: Object.freeze([...pendingActions])
    })
    for (const listener of listeners) {
      try {
        listener()
      } catch {
        // Store observers cannot own server-state transitions.
      }
    }
  }

  const stop = () => {
    generation += 1
    accessToken = null
    summaryFlight = null
    summaryDirty = false
    pageEpoch += 1
    pageRequest = null
    hiddenPublicationIds.clear()
    pendingActions.clear()
    snapshot = emptySnapshot()
    publish()
  }

  const reportError = (error: unknown) => {
    const denied = error instanceof ApiClientError && (error.status === 401 || error.status === 403)
    const unauthorized = denied && error.status === 401
    if (denied) stop()
    else publish({ error: error instanceof Error ? error.message : 'Message center request failed' })
    try {
      options.onError?.(error)
    } catch {
      // Error observers are advisory.
    }
    if (unauthorized) {
      try {
        options.onUnauthorized?.()
      } catch {
        // Authorization observers run after fail-closed cleanup.
      }
    }
  }

  const refreshSummary = (): Promise<void> => {
    if (!accessToken) return Promise.resolve()
    if (summaryFlight) {
      summaryDirty = true
      return summaryFlight
    }

    const expectedGeneration = generation
    const flight = (async () => {
      do {
        summaryDirty = false
        const token: string | null = accessToken
        if (!token) return
        publish({ summaryLoading: true })
        try {
          const [countWire, recentWire] = await Promise.all([
            options.api.getUnreadCount({ accessToken: token }),
            options.api.listMessages({ accessToken: token, page: 0, size: 5, unreadOnly: false })
          ])
          if (generation !== expectedGeneration || accessToken !== token) return
          const recentPage = withoutHidden(createMessagePageModel(recentWire), hiddenPublicationIds)
          publish({
            unreadCount: createUnreadCountModel(countWire),
            recent: recentPage.items,
            summaryLoading: false,
            error: null
          })
        } catch (error) {
          if (generation === expectedGeneration) {
            publish({ summaryLoading: false })
            reportError(error)
          }
          return
        }
      } while (summaryDirty && generation === expectedGeneration && accessToken)
    })()
    summaryFlight = flight
    const cleanup = () => {
      if (summaryFlight === flight) summaryFlight = null
    }
    void flight.then(cleanup, cleanup)
    return flight
  }

  const loadPage = async (request: PageRequest): Promise<void> => {
    if (!accessToken) return
    const canonicalRequest = Object.freeze({
      filter: request.filter,
      page: Math.max(0, Math.trunc(request.page)),
      size: Math.min(100, Math.max(1, Math.trunc(request.size)))
    })
    pageRequest = canonicalRequest
    const expectedGeneration = generation
    const expectedEpoch = ++pageEpoch
    const token = accessToken
    publish({ filter: canonicalRequest.filter, pageLoading: true })
    try {
      const wire = await options.api.listMessages({
        accessToken: token,
        page: canonicalRequest.page,
        size: canonicalRequest.size,
        unreadOnly: canonicalRequest.filter === 'UNREAD'
      })
      if (generation !== expectedGeneration || pageEpoch !== expectedEpoch || accessToken !== token) return
      publish({
        page: withoutHidden(createMessagePageModel(wire), hiddenPublicationIds),
        filter: canonicalRequest.filter,
        pageLoading: false,
        error: null
      })
    } catch (error) {
      if (generation === expectedGeneration && pageEpoch === expectedEpoch) {
        publish({ pageLoading: false })
        reportError(error)
      }
    }
  }

  const reloadPage = () => pageRequest ? loadPage(pageRequest) : Promise.resolve()

  const refreshAll = async () => {
    await Promise.all([refreshSummary(), reloadPage()])
  }

  const mutate = async (
    actionKey: string,
    request: (token: string) => Promise<unknown>,
    afterSuccess?: () => void
  ) => {
    if (!accessToken || pendingActions.has(actionKey)) return false
    const expectedGeneration = generation
    const token = accessToken
    pendingActions.add(actionKey)
    publish({ error: null })
    try {
      await request(token)
      if (generation !== expectedGeneration || accessToken !== token) return false
      afterSuccess?.()
      await refreshAll()
      return generation === expectedGeneration && accessToken === token
    } catch (error) {
      if (generation === expectedGeneration) reportError(error)
      return false
    } finally {
      if (generation === expectedGeneration) {
        pendingActions.delete(actionKey)
        publish()
      }
    }
  }

  return Object.freeze({
    async start(token: string) {
      if (!token.trim()) {
        stop()
        return
      }
      if (accessToken !== token) {
        stop()
        accessToken = token
      }
      await refreshSummary()
    },

    stop,
    refreshSummary,
    refreshAll,
    loadPage,

    handleWakeup() {
      return refreshAll()
    },

    markRead(publicationId: string) {
      return mutate(`read:${publicationId}`, (token) => options.api.markMessageRead({
        accessToken: token,
        publicationId
      }))
    },

    markUnread(publicationId: string) {
      return mutate(`unread:${publicationId}`, (token) => options.api.markMessageUnread({
        accessToken: token,
        publicationId
      }))
    },

    markAllRead() {
      return mutate('read-all', (token) => options.api.markAllMessagesRead({ accessToken: token }))
    },

    hide(publicationId: string) {
      return mutate(
        `hide:${publicationId}`,
        (token) => options.api.hideMessage({ accessToken: token, publicationId }),
        () => {
          hiddenPublicationIds.add(publicationId)
          const unread = [...snapshot.recent, ...(snapshot.page?.items ?? [])]
            .find((message) => message.publicationId === publicationId)?.readState === 'UNREAD'
          publish({
            unreadCount: unread ? Math.max(0, snapshot.unreadCount - 1) : snapshot.unreadCount,
            recent: snapshot.recent.filter((message) => message.publicationId !== publicationId),
            page: snapshot.page ? withoutHidden(snapshot.page, hiddenPublicationIds) : null
          })
        }
      )
    },

    getSnapshot() {
      return snapshot
    },

    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    }
  })
}

function withoutHidden(page: MessagePageModel, hiddenIds: ReadonlySet<string>): MessagePageModel {
  const items = page.items.filter((message) => !hiddenIds.has(message.publicationId))
  const removed = page.items.length - items.length
  if (removed === 0) return page
  const total = Math.max(0, page.total - removed)
  return Object.freeze({
    ...page,
    items: Object.freeze(items),
    total,
    totalPages: total === 0 ? 0 : Math.ceil(total / page.size)
  })
}

function emptySnapshot(): MessageCenterSnapshot {
  return Object.freeze({
    unreadCount: 0,
    recent: Object.freeze([]),
    page: null,
    filter: 'ALL',
    summaryLoading: false,
    pageLoading: false,
    pendingActions: Object.freeze([]),
    error: null
  })
}
