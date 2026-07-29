import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { EngagementUpdateSubscription } from '@fx-platform/frontend-core'

import { createAppEngagementRuntime } from './appEngagementRuntime.ts'

const claim = {
  queueSessionId: 'queue-1',
  deliveryId: 'delivery-1',
  campaignId: 'campaign-1',
  revisionId: 'revision-1',
  deliveryToken: 'secret-delivery-token',
  expiresAt: '2026-07-20T12:00:00Z',
  templateSize: 'MEDIUM',
  title: 'Margin update',
  sanitizedHtml: '<p>Read the updated limits.</p>',
  coverAssetId: null,
  ctaLabel: 'Review',
  ctaRouteKey: 'ACCOUNT_OVERVIEW',
  ctaParams: '{}'
}

const messagePage = { items: [], page: 0, size: 5, total: 0, totalPages: 0 }
const accepted = { accepted: true, status: 'SHOWN', queueDirective: 'KEEP_CURRENT' as const }
type UpdateListener = Parameters<EngagementUpdateSubscription['subscribe']>[1]

describe('app engagement runtime', () => {
  it('adapts popup wire data before publishing and fails closed on malformed claims', async () => {
    const fixture = createFixture()
    const runtime = createAppEngagementRuntime(fixture.options)
    const release = runtime.connect(session())
    await settle()

    assert.equal(runtime.popup.getSnapshot().current?.content.title, 'Margin update')
    assert.equal(runtime.popup.getSnapshot().current?.content.surface, 'PC')
    release()

    const malformed = createFixture({ startQueue: async () => ({ ...claim, sanitizedHtml: '<script>bad</script>' }) })
    const malformedRuntime = createAppEngagementRuntime(malformed.options)
    malformedRuntime.connect(session())
    await settle()

    assert.equal(malformedRuntime.popup.getSnapshot().current, null)
    assert.equal(malformed.errors.length, 1)
  })

  it('keeps one session across a StrictMode-style release and reacquire pair', async () => {
    const fixture = createFixture({ startQueue: async () => null })
    const runtime = createAppEngagementRuntime(fixture.options)

    const releaseFirst = runtime.connect(session())
    releaseFirst()
    const releaseSecond = runtime.connect(session())
    await settle()

    assert.equal(fixture.calls.startQueue, 1)
    assert.equal(fixture.calls.updateSubscriptions, 2)
    assert.equal(fixture.calls.updateUnsubscriptions, 0)

    releaseSecond()
    await settle()
    assert.equal(fixture.calls.updateUnsubscriptions, 2)
  })

  it('refreshes authoritative inbox state after shown, updates, reconnect and focus', async () => {
    const fixture = createFixture()
    const runtime = createAppEngagementRuntime(fixture.options)
    runtime.connect(session())
    await settle()
    assert.equal(fixture.calls.unreadCount, 1)
    await runtime.messages.loadPage({ filter: 'UNREAD', page: 0, size: 10 })
    const listsBeforeShown = fixture.calls.messageLists

    await runtime.popup.popupMounted()
    await settle()
    assert.equal(fixture.calls.unreadCount, 2)
    assert.equal(fixture.calls.messageLists, listsBeforeShown + 2)

    await fixture.messageListener?.onUpdate({
      updateType: 'CAMPAIGN_UPDATED',
      aggregateId: 'campaign-2',
      occurredAt: '2026-07-20T12:01:00Z'
    })
    await settle()
    assert.equal(fixture.calls.unreadCount, 3)

    await fixture.messageListener?.onReconnect()
    await settle()
    assert.equal(fixture.calls.unreadCount, 4)

    await runtime.windowFocused()
    assert.equal(fixture.calls.unreadCount, 5)
  })

  it('routes page, resize and critical-overlay changes into the single popup controller', async () => {
    const fixture = createFixture({ startQueue: async () => null })
    const runtime = createAppEngagementRuntime(fixture.options)
    runtime.connect(session())
    await settle()

    await runtime.criticalModalChanged(true)
    await runtime.routeChanged('MESSAGES')
    runtime.resize('MOBILE')
    await runtime.criticalModalChanged(false)

    assert.deepEqual(fixture.startSurfaces.map((surface) => surface.triggerType), [
      'LOGIN',
      'CRITICAL_MODAL_RELEASED'
    ])
    assert.deepEqual(fixture.startSurfaces.at(-1), {
      triggerType: 'CRITICAL_MODAL_RELEASED',
      pageKey: 'MESSAGES',
      deviceClass: 'MOBILE'
    })
  })
})

function session() {
  return { accessToken: 'access-1', pageKey: 'HOME', deviceClass: 'PC' as const }
}

function createFixture(overrides: Record<string, unknown> = {}) {
  const calls = {
    startQueue: 0,
    unreadCount: 0,
    messageLists: 0,
    updateSubscriptions: 0,
    updateUnsubscriptions: 0
  }
  const startSurfaces: unknown[] = []
  const errors: unknown[] = []
  let messageListener: UpdateListener | null = null
  const customStartQueue = typeof overrides.startQueue === 'function'
    ? overrides.startQueue as (input: { surface: unknown }) => Promise<unknown>
    : null
  const { startQueue: _startQueue, ...otherOverrides } = overrides
  const api = {
    async startQueue(input: { surface: unknown }) {
      calls.startQueue += 1
      startSurfaces.push(input.surface)
      return customStartQueue ? customStartQueue(input) : claim
    },
    async nextPopup() { return null },
    async markShown() { return accepted },
    async closePopup() { return { ...accepted, status: 'CLOSED', queueDirective: 'TERMINATE' as const } },
    async clickPopup() { return { ...accepted, status: 'CLICKED', queueDirective: 'TERMINATE' as const } },
    async optOutPopup() { return { ...accepted, status: 'OPTED_OUT', queueDirective: 'TERMINATE' as const } },
    async getUnreadCount() {
      calls.unreadCount += 1
      return { count: 0 }
    },
    async listMessages() {
      calls.messageLists += 1
      return messagePage
    },
    async markMessageRead() {},
    async markMessageUnread() {},
    async markAllMessagesRead() {},
    async hideMessage() {},
    ...otherOverrides
  }
  const updates = {
    subscribe(_token: string, listener: UpdateListener) {
      calls.updateSubscriptions += 1
      if (calls.updateSubscriptions === 2) messageListener = listener
      return () => { calls.updateUnsubscriptions += 1 }
    }
  }
  return {
    calls,
    errors,
    startSurfaces,
    get messageListener() { return messageListener },
    options: { api, updates, onError: (error: unknown) => errors.push(error) }
  }
}

async function settle() {
  await new Promise<void>((resolve) => queueMicrotask(resolve))
  await new Promise<void>((resolve) => queueMicrotask(resolve))
}
