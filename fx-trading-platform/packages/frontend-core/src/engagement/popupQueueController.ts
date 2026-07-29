import { ApiClientError } from '../api/apiClient.ts'
import type { PopupSurface } from './engagementApi.ts'
import type { EngagementUpdateEvent } from './engagementStream.ts'

export type PopupQueueClaim = Readonly<{
  queueSessionId: string
  deliveryId: string
  campaignId: string
  revisionId: string
  deliveryToken: string
  expiresAt: string
}>

export type PopupQueueOutcome = Readonly<{
  accepted: boolean
  status: string
  queueDirective: 'KEEP_CURRENT' | 'CONTINUE' | 'TERMINATE'
}>

export type PopupQueueSnapshot<TClaim extends PopupQueueClaim> = Readonly<{
  current: TClaim | null
  shown: boolean
  blocked: boolean
}>

export type PopupQueueSession = Readonly<{
  accessToken: string
  pageKey: string
  deviceClass: 'PC' | 'MOBILE'
}>

export type PopupQueueApi<TClaim extends PopupQueueClaim> = Readonly<{
  startQueue(input: { accessToken: string; surface: PopupSurface }): Promise<TClaim | null>
  nextPopup(input: {
    accessToken: string
    queueSessionId: string
    surface: PopupSurface
  }): Promise<TClaim | null>
  markShown(input: { accessToken: string; deliveryToken: string }): Promise<PopupQueueOutcome>
  closePopup(input: { accessToken: string; deliveryToken: string }): Promise<PopupQueueOutcome>
  clickPopup(input: { accessToken: string; deliveryToken: string }): Promise<PopupQueueOutcome>
  optOutPopup(input: { accessToken: string; deliveryToken: string }): Promise<PopupQueueOutcome>
}>

export type EngagementUpdateSubscription = Readonly<{
  subscribe(
    accessToken: string,
    listener: {
      onUpdate: (event: EngagementUpdateEvent) => void | Promise<void>
      onReconnect: () => void | Promise<void>
    }
  ): () => void
}>

export function createPopupQueueController<TClaim extends PopupQueueClaim>(options: {
  api: PopupQueueApi<TClaim>
  updates: EngagementUpdateSubscription
  onError?: (error: unknown) => void
  onUnauthorized?: () => void
}) {
  let generation = 0
  let session: PopupQueueSession | null = null
  let queueSurface: PopupSurface | null = null
  let current: TClaim | null = null
  let shown = false
  let blocked = false
  let unsubscribeUpdates: (() => void) | null = null
  let claimFlight: Promise<void> | null = null
  let shownFlight: Promise<void> | null = null
  let actionFlight: Promise<'CONTINUE' | 'TERMINATE' | null> | null = null
  let snapshot: PopupQueueSnapshot<TClaim> = Object.freeze({ current, shown, blocked })
  const listeners = new Set<() => void>()
  const seenUpdates = new Set<string>()

  const publish = () => {
    snapshot = Object.freeze({ current, shown, blocked })
    for (const listener of listeners) {
      try {
        listener()
      } catch {
        // Observers cannot own controller state transitions.
      }
    }
  }

  const clearCurrent = (keepQueueSurface = false) => {
    current = null
    shown = false
    if (!keepQueueSurface) queueSurface = null
    publish()
  }

  const stopSession = () => {
    generation += 1
    session = null
    queueSurface = null
    current = null
    shown = false
    claimFlight = null
    shownFlight = null
    actionFlight = null
    seenUpdates.clear()
    const unsubscribe = unsubscribeUpdates
    unsubscribeUpdates = null
    try {
      unsubscribe?.()
    } catch {
      // Local state is already fail-closed even if an adapter cleanup fails.
    }
    publish()
  }

  const reportError = (error: unknown) => {
    const authDenied = error instanceof ApiClientError && (error.status === 401 || error.status === 403)
    const unauthorized = authDenied && error.status === 401
    if (authDenied) stopSession()
    try {
      options.onError?.(error)
    } catch {
      // Error observers are advisory and must not interrupt cleanup.
    }
    if (unauthorized) {
      try {
        options.onUnauthorized?.()
      } catch {
        // Authorization observers are advisory and run after cleanup.
      }
    }
  }

  const applyClaim = (claim: TClaim | null, surface: PopupSurface, expectedGeneration: number) => {
    if (generation !== expectedGeneration || session === null) return
    current = claim
    shown = false
    queueSurface = claim === null ? null : surface
    publish()
  }

  const startFreshQueue = (triggerType: string): Promise<void> => {
    if (claimFlight) return claimFlight
    if (!session || blocked || current) return Promise.resolve()

    const expectedGeneration = generation
    const activeSession = session
    const surface: PopupSurface = {
      triggerType,
      pageKey: activeSession.pageKey,
      deviceClass: activeSession.deviceClass
    }
    const flight = (async () => {
      try {
        const claim = await options.api.startQueue({
          accessToken: activeSession.accessToken,
          surface
        })
        applyClaim(claim, surface, expectedGeneration)
      } catch (error) {
        if (generation === expectedGeneration) reportError(error)
      }
    })()
    claimFlight = flight
    const cleanup = () => {
      if (claimFlight === flight) claimFlight = null
    }
    void flight.then(cleanup, cleanup)
    return flight
  }

  const continueQueue = (claim: TClaim, expectedGeneration: number): Promise<void> => {
    if (claimFlight) return claimFlight
    const activeSession = session
    const surface = queueSurface
    if (!activeSession || !surface || generation !== expectedGeneration) return Promise.resolve()

    const flight = (async () => {
      try {
        const next = await options.api.nextPopup({
          accessToken: activeSession.accessToken,
          queueSessionId: claim.queueSessionId,
          surface
        })
        applyClaim(next, surface, expectedGeneration)
      } catch (error) {
        if (generation === expectedGeneration) {
          queueSurface = null
          reportError(error)
        }
      }
    })()
    claimFlight = flight
    const cleanup = () => {
      if (claimFlight === flight) claimFlight = null
    }
    void flight.then(cleanup, cleanup)
    return flight
  }

  const markCurrentShown = (): Promise<void> => {
    if (blocked || shown || shownFlight || !session || !current) {
      return shownFlight ?? Promise.resolve()
    }
    const expectedGeneration = generation
    const claim = current
    const activeSession = session
    const flight = (async () => {
      try {
        const result = await options.api.markShown({
          accessToken: activeSession.accessToken,
          deliveryToken: claim.deliveryToken
        })
        if (generation !== expectedGeneration || current !== claim) return
        if (result.accepted && result.status === 'SHOWN') {
          shown = true
          publish()
        } else if (result.queueDirective === 'CONTINUE') {
          clearCurrent(true)
          await continueQueue(claim, expectedGeneration)
        } else if (result.queueDirective === 'TERMINATE') {
          clearCurrent()
        }
      } catch (error) {
        if (generation === expectedGeneration && current === claim) reportError(error)
      }
    })()
    shownFlight = flight
    const cleanup = () => {
      if (shownFlight === flight) shownFlight = null
    }
    void flight.then(cleanup, cleanup)
    return flight
  }

  const resumeCurrentOrStart = (triggerType: string) => {
    if (current && !shown) return markCurrentShown()
    return startFreshQueue(triggerType)
  }

  const runTerminalAction = (
    kind: 'close' | 'click' | 'opt-out'
  ): Promise<'CONTINUE' | 'TERMINATE' | null> => {
    if (actionFlight) return actionFlight
    if (!session || !current) return Promise.resolve(null)

    const expectedGeneration = generation
    const claim = current
    const activeSession = session
    const flight = (async () => {
      if (shownFlight) await shownFlight
      if (generation !== expectedGeneration || current !== claim || !session) return null
      if (!shown) await markCurrentShown()
      if (generation !== expectedGeneration || current !== claim || !session || !shown) return null

      try {
        const input = {
          accessToken: activeSession.accessToken,
          deliveryToken: claim.deliveryToken
        }
        const result = kind === 'close'
          ? await options.api.closePopup(input)
          : kind === 'click'
            ? await options.api.clickPopup(input)
            : await options.api.optOutPopup(input)
        if (generation !== expectedGeneration || current !== claim) return null

        if (result.queueDirective === 'CONTINUE') {
          clearCurrent(true)
          await continueQueue(claim, expectedGeneration)
          return 'CONTINUE'
        } else if (result.queueDirective === 'TERMINATE') {
          clearCurrent()
          return 'TERMINATE'
        }
        return null
      } catch (error) {
        if (generation === expectedGeneration && current === claim) reportError(error)
        return null
      }
    })()
    actionFlight = flight
    const cleanup = () => {
      if (actionFlight === flight) actionFlight = null
    }
    void flight.then(cleanup, cleanup)
    return flight
  }

  const rememberUpdate = (event: EngagementUpdateEvent) => {
    const key = `${event.updateType}\u0000${event.aggregateId}\u0000${event.occurredAt}`
    if (seenUpdates.has(key)) return false
    seenUpdates.add(key)
    if (seenUpdates.size > 128) seenUpdates.delete(seenUpdates.values().next().value as string)
    return true
  }

  const handleUpdate = async (event: EngagementUpdateEvent) => {
    if (!session || event.updateType === 'MESSAGE_UPDATED' || !rememberUpdate(event)) return
    if (event.updateType === 'CAMPAIGN_INVALIDATED' && current?.campaignId === event.aggregateId) {
      await runTerminalAction('close')
      return
    }
    if (current === null) await startFreshQueue('REALTIME')
  }

  return Object.freeze({
    async login(nextSession: PopupQueueSession) {
      stopSession()
      session = Object.freeze({ ...nextSession })
      const expectedGeneration = generation
      try {
        unsubscribeUpdates = options.updates.subscribe(session.accessToken, {
          onUpdate: (event) => generation === expectedGeneration ? handleUpdate(event) : undefined,
          onReconnect: () => generation === expectedGeneration ? resumeCurrentOrStart('RECONNECT') : undefined
        })
      } catch (error) {
        reportError(error)
      }
      await startFreshQueue('LOGIN')
    },

    logout() {
      stopSession()
    },

    routeChanged(pageKey: string) {
      if (!session) return Promise.resolve()
      session = Object.freeze({ ...session, pageKey })
      return startFreshQueue('ROUTE_CHANGE')
    },

    windowFocused() {
      return resumeCurrentOrStart('WINDOW_FOCUS')
    },

    criticalModalChanged(nextBlocked: boolean) {
      if (blocked === nextBlocked) return Promise.resolve()
      blocked = nextBlocked
      publish()
      return nextBlocked ? Promise.resolve() : startFreshQueue('CRITICAL_MODAL_RELEASED')
    },

    resize(deviceClass: 'PC' | 'MOBILE') {
      if (session) session = Object.freeze({ ...session, deviceClass })
    },

    popupMounted(): Promise<void> {
      return markCurrentShown()
    },

    closeCurrent() {
      return runTerminalAction('close')
    },

    clickCurrent() {
      return runTerminalAction('click')
    },

    optOutCurrent() {
      return runTerminalAction('opt-out')
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
