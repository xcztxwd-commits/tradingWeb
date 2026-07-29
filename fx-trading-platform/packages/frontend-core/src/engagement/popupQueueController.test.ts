import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '../api/apiClient.ts'
import { createPopupQueueController } from './popupQueueController.ts'

describe('popup queue controller', () => {
  it('claims on login and clears pending work and subscriptions on logout', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    const pending = deferred<PopupClaim | null>()
    api.startResults.push(pending.promise)
    const controller = createPopupQueueController({ api, updates })

    const login = controller.login(session('token-a'))
    assert.deepEqual(api.startCalls, [{
      accessToken: 'token-a',
      surface: { triggerType: 'LOGIN', pageKey: 'DASHBOARD', deviceClass: 'PC' }
    }])
    assert.equal(updates.subscriptionCount, 1)

    controller.logout()
    pending.resolve(popup('campaign-stale', 'queue-stale'))
    await login

    assert.equal(controller.getSnapshot().current, null)
    assert.equal(updates.subscriptionCount, 0)
  })

  it('keeps a pending claim on its original surface and uses the new route only after that queue drains', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    const pending = deferred<PopupClaim | null>()
    api.startResults.push(pending.promise, popup('campaign-orders', 'queue-orders'))
    api.nextResults.push(null)
    const controller = createPopupQueueController({ api, updates })

    const login = controller.login(session('token-a'))
    const routeChange = controller.routeChanged('ORDERS')
    pending.resolve(popup('campaign-dashboard', 'queue-dashboard'))
    await Promise.all([login, routeChange])

    assert.equal(api.startCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-dashboard')

    await controller.popupMounted()
    await controller.closeCurrent()
    assert.deepEqual(api.nextCalls[0], {
      accessToken: 'token-a',
      queueSessionId: 'queue-dashboard',
      surface: { triggerType: 'LOGIN', pageKey: 'DASHBOARD', deviceClass: 'PC' }
    })

    await controller.windowFocused()
    assert.deepEqual(api.startCalls[1].surface, {
      triggerType: 'WINDOW_FOCUS', pageKey: 'ORDERS', deviceClass: 'PC'
    })
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-orders')
  })

  it('starts a fresh queue on route change, realtime wakeup, focus and critical-modal release', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    api.startResults.push(null, null, null, null, popup('campaign-5', 'queue-5'))
    const controller = createPopupQueueController({ api, updates })

    await controller.login(session('token-a'))
    await controller.routeChanged('ORDERS')
    await updates.emit({ updateType: 'CAMPAIGN_UPDATED', aggregateId: 'campaign-2', occurredAt: '2026-07-20T00:00:00Z' })
    await controller.windowFocused()
    controller.criticalModalChanged(true)
    assert.equal(api.startCalls.length, 4)
    await controller.criticalModalChanged(false)

    assert.deepEqual(api.startCalls.map((call) => call.surface), [
      { triggerType: 'LOGIN', pageKey: 'DASHBOARD', deviceClass: 'PC' },
      { triggerType: 'ROUTE_CHANGE', pageKey: 'ORDERS', deviceClass: 'PC' },
      { triggerType: 'REALTIME', pageKey: 'ORDERS', deviceClass: 'PC' },
      { triggerType: 'WINDOW_FOCUS', pageKey: 'ORDERS', deviceClass: 'PC' },
      { triggerType: 'CRITICAL_MODAL_RELEASED', pageKey: 'ORDERS', deviceClass: 'PC' }
    ])
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-5')
  })

  it('does not claim while a critical business modal is open', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    const controller = createPopupQueueController({ api, updates })

    controller.criticalModalChanged(true)
    await controller.login(session('token-a'))
    await controller.routeChanged('ORDERS')
    await controller.windowFocused()
    await updates.emit({ updateType: 'CAMPAIGN_UPDATED', aggregateId: 'campaign-1', occurredAt: '2026-07-20T00:00:00Z' })

    assert.equal(api.startCalls.length, 0)
    assert.equal(controller.getSnapshot().current, null)
  })

  it('does not record a claim as shown while a critical modal wins a pending-claim race', async () => {
    const api = new FakePopupApi()
    const pending = deferred<PopupClaim | null>()
    api.startResults.push(pending.promise)
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    const login = controller.login(session('token-a'))
    await controller.criticalModalChanged(true)
    pending.resolve(popup('campaign-1', 'queue-1'))
    await login
    await controller.popupMounted()

    assert.equal(controller.getSnapshot().blocked, true)
    assert.equal(controller.getSnapshot().shown, false)
    assert.equal(api.shownCalls.length, 0)

    await controller.criticalModalChanged(false)
    await controller.popupMounted()
    assert.equal(controller.getSnapshot().shown, true)
    assert.equal(api.shownCalls.length, 1)
  })

  it('marks a popup locally shown only after the shown request succeeds', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    const pending = deferred<PopupOutcome>()
    api.shownResults.push(pending.promise)
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    const mounted = controller.popupMounted()
    assert.equal(controller.getSnapshot().shown, false)

    pending.resolve(outcome('SHOWN', 'KEEP_CURRENT'))
    await mounted

    assert.equal(controller.getSnapshot().shown, true)
    assert.deepEqual(api.shownCalls, [{ accessToken: 'token-a', deliveryToken: 'token-campaign-1' }])
  })

  it('retries a transient failed shown request on focus without duplicating a successful impression', async () => {
    const api = new FakePopupApi()
    const errors: unknown[] = []
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(new TypeError('offline'), outcome('SHOWN', 'KEEP_CURRENT'))
    const controller = createPopupQueueController({
      api,
      updates: new FakeEngagementUpdates(),
      onError: (error) => errors.push(error)
    })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    assert.equal(controller.getSnapshot().shown, false)

    await controller.windowFocused()
    assert.equal(controller.getSnapshot().shown, true)
    assert.equal(api.shownCalls.length, 2)
    assert.equal(errors.length, 1)

    await controller.windowFocused()
    assert.equal(api.shownCalls.length, 2)
  })

  it('retries shown before a close so a transient impression failure cannot trap the modal', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(new TypeError('offline'), outcome('SHOWN', 'KEEP_CURRENT'))
    api.closeResults.push(outcome('CLOSED', 'TERMINATE'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    const directive = await controller.closeCurrent()

    assert.equal(directive, 'TERMINATE')
    assert.equal(api.shownCalls.length, 2)
    assert.equal(api.closeCalls.length, 1)
    assert.equal(controller.getSnapshot().current, null)
  })

  it('ordinary close records the outcome and requests the next popup in the same queue', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    api.closeResults.push(outcome('CLOSED', 'CONTINUE'))
    api.nextResults.push(popup('campaign-2', 'queue-1'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    await controller.closeCurrent()

    assert.deepEqual(api.closeCalls, [{ accessToken: 'token-a', deliveryToken: 'token-campaign-1' }])
    assert.deepEqual(api.nextCalls, [{
      accessToken: 'token-a',
      queueSessionId: 'queue-1',
      surface: { triggerType: 'LOGIN', pageKey: 'DASHBOARD', deviceClass: 'PC' }
    }])
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-2')
    assert.equal(controller.getSnapshot().shown, false)
  })

  it('CTA records click and terminates without requesting next', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    api.clickResults.push(outcome('CLICKED', 'TERMINATE'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    const directive = await controller.clickCurrent()

    assert.equal(directive, 'TERMINATE')
    assert.deepEqual(api.clickCalls, [{ accessToken: 'token-a', deliveryToken: 'token-campaign-1' }])
    assert.equal(api.nextCalls.length, 0)
    assert.equal(controller.getSnapshot().current, null)
  })

  it('CTA invalidation follows CONTINUE and requests the next popup', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    api.clickResults.push({ accepted: false, status: 'INVALIDATED', queueDirective: 'CONTINUE' })
    api.nextResults.push(popup('campaign-2', 'queue-1'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    const directive = await controller.clickCurrent()

    assert.equal(directive, 'CONTINUE')
    assert.equal(api.nextCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-2')
  })

  it('opt-out records the outcome and continues the same queue', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    api.optOutResults.push(outcome('CLOSED', 'CONTINUE'))
    api.nextResults.push(popup('campaign-2', 'queue-1'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    await controller.optOutCurrent()

    assert.deepEqual(api.optOutCalls, [{ accessToken: 'token-a', deliveryToken: 'token-campaign-1' }])
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-2')
  })

  it('returns null when CTA has no current popup or its request fails', async () => {
    const api = new FakePopupApi()
    const errors: unknown[] = []
    const controller = createPopupQueueController({
      api,
      updates: new FakeEngagementUpdates(),
      onError: (error) => errors.push(error)
    })

    assert.equal(await controller.clickCurrent(), null)

    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.clickResults.push(new TypeError('offline'))
    await controller.login(session('token-a'))

    assert.equal(await controller.clickCurrent(), null)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-1')
    assert.equal(errors.length, 1)
  })

  it('coalesces concurrent CTA actions and shares their directive', async () => {
    const api = new FakePopupApi()
    const pending = deferred<PopupOutcome>()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.clickResults.push(pending.promise)
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    const first = controller.clickCurrent()
    const second = controller.clickCurrent()

    assert.equal(first, second)
    assert.equal(api.clickCalls.length, 1)
    pending.resolve(outcome('CLICKED', 'TERMINATE'))
    assert.deepEqual(await Promise.all([first, second]), ['TERMINATE', 'TERMINATE'])
  })

  it('skips an invalidated delivery and continues the same queue', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-invalid', 'queue-1'))
    api.shownResults.push({ accepted: false, status: 'INVALIDATED', queueDirective: 'CONTINUE' })
    api.nextResults.push(popup('campaign-valid', 'queue-1'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    await controller.popupMounted()

    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-valid')
    assert.equal(controller.getSnapshot().shown, false)
    assert.equal(api.nextCalls.length, 1)
  })

  it('records an open popup invalidated by realtime pause/delete and continues the queue', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    api.closeResults.push({ accepted: false, status: 'INVALIDATED', queueDirective: 'CONTINUE' })
    api.nextResults.push(popup('campaign-2', 'queue-1'))
    const controller = createPopupQueueController({ api, updates })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    await updates.emit({ updateType: 'CAMPAIGN_INVALIDATED', aggregateId: 'campaign-1', occurredAt: '2026-07-20T00:00:00Z' })

    assert.equal(api.closeCalls.length, 1)
    assert.equal(api.nextCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-2')
  })

  it('ignores message wakeups and keeps an already-open revision stable on campaign updates', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    const controller = createPopupQueueController({ api, updates })

    await controller.login(session('token-a'))
    await updates.emit({ updateType: 'MESSAGE_UPDATED', aggregateId: 'message-1', occurredAt: '2026-07-20T00:00:00Z' })
    await updates.emit({ updateType: 'CAMPAIGN_UPDATED', aggregateId: 'campaign-1', occurredAt: '2026-07-20T00:01:00Z' })

    assert.equal(api.startCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.revisionId, 'revision-campaign-1')
  })

  it('fails closed on 401/403 without leaking a popup', async () => {
    for (const status of [401, 403]) {
      const api = new FakePopupApi()
      const errors: unknown[] = []
      let unauthorized = 0
      api.startResults.push(new ApiClientError({ status, code: `HTTP_${status}`, message: 'denied' }))
      const controller = createPopupQueueController({
        api,
        updates: new FakeEngagementUpdates(),
        onError: (error) => errors.push(error),
        onUnauthorized: () => { unauthorized += 1 }
      })

      await controller.login(session('token-a'))

      assert.equal(controller.getSnapshot().current, null)
      assert.equal(errors.length, 1)
      assert.equal(unauthorized, status === 401 ? 1 : 0)
    }
  })

  it('fails closed and unsubscribes when shown returns 401/403 for an open popup', async () => {
    for (const status of [401, 403]) {
      const api = new FakePopupApi()
      const updates = new FakeEngagementUpdates()
      const observedCleanup: Array<[unknown, number]> = []
      let unauthorized = 0
      let controller!: ReturnType<typeof createPopupQueueController<PopupClaim>>
      api.startResults.push(popup('campaign-1', 'queue-1'))
      api.shownResults.push(new ApiClientError({ status, code: `HTTP_${status}`, message: 'denied' }))
      controller = createPopupQueueController({
        api,
        updates,
        onError: () => observedCleanup.push([controller.getSnapshot().current, updates.subscriptionCount]),
        onUnauthorized: () => { unauthorized += 1 }
      })

      await controller.login(session('token-a'))
      await controller.popupMounted()

      assert.deepEqual(observedCleanup, [[null, 0]])
      assert.equal(controller.getSnapshot().current, null)
      assert.equal(unauthorized, status === 401 ? 1 : 0)
    }
  })

  it('fails closed when a terminal action returns 401/403', async () => {
    for (const status of [401, 403]) {
      const api = new FakePopupApi()
      const updates = new FakeEngagementUpdates()
      let unauthorized = 0
      api.startResults.push(popup('campaign-1', 'queue-1'))
      api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
      api.clickResults.push(new ApiClientError({ status, code: `HTTP_${status}`, message: 'denied' }))
      const controller = createPopupQueueController({
        api,
        updates,
        onUnauthorized: () => { unauthorized += 1 }
      })

      await controller.login(session('token-a'))
      await controller.popupMounted()
      const directive = await controller.clickCurrent()

      assert.equal(directive, null)
      assert.equal(controller.getSnapshot().current, null)
      assert.equal(updates.subscriptionCount, 0)
      assert.equal(unauthorized, status === 401 ? 1 : 0)
    }
  })

  it('isolates throwing auth observers after fail-closed cleanup', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(new ApiClientError({ status: 401, code: 'HTTP_401', message: 'denied' }))
    const controller = createPopupQueueController({
      api,
      updates,
      onError: () => { throw new Error('observer failure') },
      onUnauthorized: () => { throw new Error('unauthorized observer failure') }
    })

    await assert.doesNotReject(controller.login(session('token-a')))
    await assert.doesNotReject(controller.popupMounted())
    await settle()

    assert.equal(controller.getSnapshot().current, null)
    assert.equal(updates.subscriptionCount, 0)
  })

  it('recovers from offline and timeout claim failures on later natural triggers', async () => {
    for (const failure of [new TypeError('offline'), new DOMException('timed out', 'TimeoutError')]) {
      const api = new FakePopupApi()
      api.startResults.push(failure, popup('campaign-recovered', 'queue-2'))
      const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

      await controller.login(session('token-a'))
      assert.equal(controller.getSnapshot().current, null)
      await controller.windowFocused()

      assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-recovered')
      assert.equal(api.startCalls.length, 2)
    }
  })

  it('deduplicates repeated realtime events and coalesces concurrent wakeups', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    const pending = deferred<PopupClaim | null>()
    api.startResults.push(null, pending.promise)
    const controller = createPopupQueueController({ api, updates })
    const event = { updateType: 'CAMPAIGN_UPDATED', aggregateId: 'campaign-1', occurredAt: '2026-07-20T00:00:00Z' }

    await controller.login(session('token-a'))
    const first = updates.emit(event)
    const duplicate = updates.emit(event)
    assert.equal(api.startCalls.length, 2)

    pending.resolve(null)
    await Promise.all([first, duplicate])
    await updates.emit(event)

    assert.equal(api.startCalls.length, 2)
  })

  it('uses reconnect only as a wakeup and never repeats shown for the open popup', async () => {
    const api = new FakePopupApi()
    const updates = new FakeEngagementUpdates()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    api.shownResults.push(outcome('SHOWN', 'KEEP_CURRENT'))
    const controller = createPopupQueueController({ api, updates })

    await controller.login(session('token-a'))
    await controller.popupMounted()
    await updates.reconnect()

    assert.equal(api.shownCalls.length, 1)
    assert.equal(api.startCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-1')
  })

  it('updates the shared surface on resize without claiming or replacing the controller', async () => {
    const api = new FakePopupApi()
    api.startResults.push(popup('campaign-1', 'queue-1'))
    const controller = createPopupQueueController({ api, updates: new FakeEngagementUpdates() })

    await controller.login(session('token-a'))
    const sameController = controller
    controller.resize('MOBILE')

    assert.equal(controller, sameController)
    assert.equal(api.startCalls.length, 1)
    assert.equal(controller.getSnapshot().current?.campaignId, 'campaign-1')
  })
})

type DeviceClass = 'PC' | 'MOBILE'
type QueueDirective = 'KEEP_CURRENT' | 'CONTINUE' | 'TERMINATE'
type PopupOutcome = {
  accepted: boolean
  status: 'SHOWN' | 'CLOSED' | 'CLICKED' | 'INVALIDATED'
  queueDirective: QueueDirective
}
type PopupClaim = ReturnType<typeof popup>

class FakePopupApi {
  readonly startCalls: unknown[] = []
  readonly nextCalls: unknown[] = []
  readonly shownCalls: unknown[] = []
  readonly closeCalls: unknown[] = []
  readonly clickCalls: unknown[] = []
  readonly optOutCalls: unknown[] = []
  readonly startResults: Array<PopupClaim | null | Error | Promise<PopupClaim | null>> = []
  readonly nextResults: Array<PopupClaim | null | Error | Promise<PopupClaim | null>> = []
  readonly shownResults: Array<PopupOutcome | Error | Promise<PopupOutcome>> = []
  readonly closeResults: Array<PopupOutcome | Error | Promise<PopupOutcome>> = []
  readonly clickResults: Array<PopupOutcome | Error | Promise<PopupOutcome>> = []
  readonly optOutResults: Array<PopupOutcome | Error | Promise<PopupOutcome>> = []

  startQueue(input: unknown) {
    this.startCalls.push(input)
    return take(this.startResults, null)
  }

  nextPopup(input: unknown) {
    this.nextCalls.push(input)
    return take(this.nextResults, null)
  }

  markShown(input: unknown) {
    this.shownCalls.push(input)
    return take(this.shownResults, outcome('SHOWN', 'KEEP_CURRENT'))
  }

  closePopup(input: unknown) {
    this.closeCalls.push(input)
    return take(this.closeResults, outcome('CLOSED', 'CONTINUE'))
  }

  clickPopup(input: unknown) {
    this.clickCalls.push(input)
    return take(this.clickResults, outcome('CLICKED', 'TERMINATE'))
  }

  optOutPopup(input: unknown) {
    this.optOutCalls.push(input)
    return take(this.optOutResults, outcome('CLOSED', 'CONTINUE'))
  }
}

class FakeEngagementUpdates {
  private listeners = new Set<{
    onUpdate: (event: unknown) => unknown
    onReconnect: () => unknown
  }>()

  subscribe(_accessToken: string, listener: { onUpdate: (event: unknown) => unknown; onReconnect: () => unknown }) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  async emit(event: unknown) {
    await Promise.all([...this.listeners].map((listener) => listener.onUpdate(event)))
  }

  async reconnect() {
    await Promise.all([...this.listeners].map((listener) => listener.onReconnect()))
  }

  get subscriptionCount() {
    return this.listeners.size
  }
}

function session(accessToken: string) {
  return { accessToken, pageKey: 'DASHBOARD', deviceClass: 'PC' as DeviceClass }
}

function popup(campaignId: string, queueSessionId: string) {
  return {
    queueSessionId,
    deliveryId: `delivery-${campaignId}`,
    campaignId,
    revisionId: `revision-${campaignId}`,
    deliveryToken: `token-${campaignId}`,
    expiresAt: '2026-07-20T01:00:00Z',
    content: {
      title: `Title ${campaignId}`,
      sanitizedHtml: `<p>${campaignId}</p>`,
      templateSize: 'MEDIUM' as const,
      coverAssetId: null,
      cta: { label: '查看', routeKey: 'DASHBOARD', params: {} }
    }
  }
}

function outcome(status: PopupOutcome['status'], queueDirective: QueueDirective): PopupOutcome {
  return { accepted: true, status, queueDirective }
}

async function take<T>(values: Array<T | Error | Promise<T>>, fallback: T): Promise<T> {
  const result = values.shift() ?? fallback
  if (result instanceof Error) throw result
  return result
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
}
