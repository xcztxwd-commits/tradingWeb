import {
  createPopupDisplayModel,
  createPopupQueueController,
  type EngagementUpdateSubscription,
  type PopupDisplayModel,
  type PopupQueueOutcome,
  type PopupQueueSession,
  type PopupSurface
} from '@fx-platform/frontend-core'

import { createMessageCenterController } from './messageCenterController.ts'
import { prepareEngagementHtml } from './engagementNavigation.ts'

type RawEngagementApi = Readonly<{
  startQueue(input: { accessToken: string; surface: PopupSurface }): Promise<unknown>
  nextPopup(input: {
    accessToken: string
    queueSessionId: string
    surface: PopupSurface
  }): Promise<unknown>
  markShown(input: AuthDelivery): Promise<PopupQueueOutcome>
  closePopup(input: AuthDelivery): Promise<PopupQueueOutcome>
  clickPopup(input: AuthDelivery): Promise<PopupQueueOutcome>
  optOutPopup(input: AuthDelivery): Promise<PopupQueueOutcome>
  getUnreadCount(input: Auth): Promise<unknown>
  listMessages(input: Auth & { page: number; size: number; unreadOnly: boolean }): Promise<unknown>
  markMessageRead(input: AuthPublication): Promise<unknown>
  markMessageUnread(input: AuthPublication): Promise<unknown>
  markAllMessagesRead(input: Auth): Promise<unknown>
  hideMessage(input: AuthPublication): Promise<unknown>
}>

type Auth = { accessToken: string }
type AuthDelivery = Auth & { deliveryToken: string }
type AuthPublication = Auth & { publicationId: string }

export function createAppEngagementRuntime(options: {
  api: RawEngagementApi
  updates: EngagementUpdateSubscription
  onError?: (error: unknown) => void
  onUnauthorized?: () => void
}) {
  let activeSession: PopupQueueSession | null = null
  let releaseVersion = 0
  let unsubscribeMessages: (() => void) | null = null
  let lastShownDeliveryId: string | null = null
  let handlingUnauthorized = false

  const reportError = (error: unknown) => {
    try {
      options.onError?.(error)
    } catch {
      // Runtime observers are advisory.
    }
  }

  const displayApi = Object.freeze({
    async startQueue(input: { accessToken: string; surface: PopupSurface }) {
      const wire = await options.api.startQueue(input)
      return wire === null ? null : createSafePopupDisplay(wire, input.surface.deviceClass)
    },
    async nextPopup(input: { accessToken: string; queueSessionId: string; surface: PopupSurface }) {
      const wire = await options.api.nextPopup(input)
      return wire === null ? null : createSafePopupDisplay(wire, input.surface.deviceClass)
    },
    markShown: options.api.markShown,
    closePopup: options.api.closePopup,
    clickPopup: options.api.clickPopup,
    optOutPopup: options.api.optOutPopup
  })

  const failClosed = () => {
    if (handlingUnauthorized) return
    handlingUnauthorized = true
    closeActiveSession()
    try {
      options.onUnauthorized?.()
    } catch {
      // Authorization observers run after local cleanup.
    } finally {
      handlingUnauthorized = false
    }
  }

  const messages = createMessageCenterController({
    api: options.api,
    onError: reportError,
    onUnauthorized: failClosed
  })
  const popup = createPopupQueueController<PopupDisplayModel>({
    api: displayApi,
    updates: options.updates,
    onError: reportError,
    onUnauthorized: failClosed
  })

  popup.subscribe(() => {
    const snapshot = popup.getSnapshot()
    const shownDeliveryId = snapshot.shown ? snapshot.current?.deliveryId ?? null : null
    if (!shownDeliveryId || shownDeliveryId === lastShownDeliveryId) return
    lastShownDeliveryId = shownDeliveryId
    void messages.refreshAll()
  })

  function closeActiveSession() {
    activeSession = null
    lastShownDeliveryId = null
    const unsubscribe = unsubscribeMessages
    unsubscribeMessages = null
    try {
      unsubscribe?.()
    } catch {
      // Local state is already disconnected.
    }
    popup.logout()
    messages.stop()
  }

  function startSession(nextSession: PopupQueueSession) {
    closeActiveSession()
    activeSession = Object.freeze({ ...nextSession })
    const popupLogin = popup.login(activeSession)
    try {
      unsubscribeMessages = options.updates.subscribe(activeSession.accessToken, {
        onUpdate: () => messages.handleWakeup(),
        onReconnect: () => messages.handleWakeup()
      })
    } catch (error) {
      reportError(error)
    }
    const messageStart = messages.start(activeSession.accessToken)
    void Promise.all([popupLogin, messageStart])
  }

  return Object.freeze({
    popup,
    messages,

    connect(nextSession: PopupQueueSession) {
      const lease = ++releaseVersion
      if (activeSession?.accessToken !== nextSession.accessToken) {
        startSession(nextSession)
      } else {
        if (activeSession.pageKey !== nextSession.pageKey) {
          activeSession = Object.freeze({ ...activeSession, pageKey: nextSession.pageKey })
          void popup.routeChanged(nextSession.pageKey)
        }
        if (activeSession.deviceClass !== nextSession.deviceClass) {
          activeSession = Object.freeze({ ...activeSession, deviceClass: nextSession.deviceClass })
          popup.resize(nextSession.deviceClass)
        }
      }

      let released = false
      return () => {
        if (released) return
        released = true
        queueMicrotask(() => {
          if (releaseVersion !== lease) return
          releaseVersion += 1
          closeActiveSession()
        })
      }
    },

    logout() {
      releaseVersion += 1
      closeActiveSession()
    },

    routeChanged(pageKey: string) {
      if (activeSession && activeSession.pageKey !== pageKey) {
        activeSession = Object.freeze({ ...activeSession, pageKey })
      }
      return popup.routeChanged(pageKey)
    },

    resize(deviceClass: 'PC' | 'MOBILE') {
      if (activeSession && activeSession.deviceClass !== deviceClass) {
        activeSession = Object.freeze({ ...activeSession, deviceClass })
      }
      popup.resize(deviceClass)
    },

    windowFocused() {
      return Promise.all([popup.windowFocused(), messages.handleWakeup()]).then(() => undefined)
    },

    criticalModalChanged(blocked: boolean) {
      return popup.criticalModalChanged(blocked)
    }
  })
}

export type AppEngagementRuntime = ReturnType<typeof createAppEngagementRuntime>

function createSafePopupDisplay(input: unknown, surface: 'PC' | 'MOBILE') {
  const display = createPopupDisplayModel(input, surface)
  if (!prepareEngagementHtml(display.content.sanitizedHtml)) {
    throw new TypeError('Popup content is not safe to render')
  }
  return display
}
