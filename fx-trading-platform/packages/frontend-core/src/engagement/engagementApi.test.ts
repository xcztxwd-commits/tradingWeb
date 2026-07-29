import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '../api/apiClient.ts'
import { createEngagementApiClient } from './engagementApi.ts'

describe('engagement API client', () => {
  it('uses the six popup routes with Bearer auth and never sends a client userId', async () => {
    const calls = installJsonFetch()
    const client = createEngagementApiClient()
    const surface = {
      triggerType: 'ROUTE_CHANGE',
      pageKey: 'ORDERS?filter=open',
      deviceClass: 'PC',
      userId: 'victim-user'
    }

    try {
      await client.startQueue({ accessToken: 'access-token', surface })
      await client.nextPopup({ accessToken: 'access-token', queueSessionId: 'queue/id ?', surface })
      await client.markShown({ accessToken: 'access-token', deliveryToken: 'delivery/id ?' })
      await client.closePopup({ accessToken: 'access-token', deliveryToken: 'delivery/id ?' })
      await client.optOutPopup({ accessToken: 'access-token', deliveryToken: 'delivery/id ?' })
      await client.clickPopup({ accessToken: 'access-token', deliveryToken: 'delivery/id ?' })

      assert.deepEqual(calls.map(({ path, method, authorization }) => ({ path, method, authorization })), [
        { path: '/api/me/engagement/popup-queues', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/engagement/popup-queues/queue%2Fid%20%3F/next', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/engagement/popup-deliveries/delivery%2Fid%20%3F/shown', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/engagement/popup-deliveries/delivery%2Fid%20%3F/close', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/engagement/popup-deliveries/delivery%2Fid%20%3F/opt-out', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/engagement/popup-deliveries/delivery%2Fid%20%3F/click', method: 'POST', authorization: 'Bearer access-token' }
      ])
      assert.deepEqual(JSON.parse(calls[0].body ?? ''), {
        triggerType: 'ROUTE_CHANGE',
        pageKey: 'ORDERS?filter=open',
        deviceClass: 'PC'
      })
      assert.equal(calls.every((call) => !call.path.includes('victim-user') && !call.body?.includes('victim-user')), true)
    } finally {
      calls.restore()
    }
  })

  it('uses the six message routes and encodes list query and publication IDs', async () => {
    const calls = installJsonFetch()
    const client = createEngagementApiClient()

    try {
      await client.listMessages({ accessToken: 'access-token', page: 2, size: 25, unreadOnly: true })
      await client.getUnreadCount({ accessToken: 'access-token' })
      await client.markMessageRead({ accessToken: 'access-token', publicationId: 'publication/id ?' })
      await client.markMessageUnread({ accessToken: 'access-token', publicationId: 'publication/id ?' })
      await client.markAllMessagesRead({ accessToken: 'access-token' })
      await client.hideMessage({ accessToken: 'access-token', publicationId: 'publication/id ?' })

      assert.deepEqual(calls.map(({ path, method, authorization }) => ({ path, method, authorization })), [
        { path: '/api/me/messages?page=2&size=25&unreadOnly=true', method: 'GET', authorization: 'Bearer access-token' },
        { path: '/api/me/messages/unread-count', method: 'GET', authorization: 'Bearer access-token' },
        { path: '/api/me/messages/publication%2Fid%20%3F/read', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/messages/publication%2Fid%20%3F/unread', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/messages/read-all', method: 'POST', authorization: 'Bearer access-token' },
        { path: '/api/me/messages/publication%2Fid%20%3F/hide', method: 'POST', authorization: 'Bearer access-token' }
      ])
      assert.equal(calls.every((call) => call.body == null), true)
    } finally {
      calls.restore()
    }
  })

  it('passes an AbortSignal and maps request timeouts to the shared typed API error', async () => {
    const originalFetch = globalThis.fetch
    let capturedSignal: AbortSignal | null = null
    globalThis.fetch = async (_input, init) => {
      capturedSignal = init?.signal ?? null
      return await new Promise<Response>((_resolve, reject) => {
        if (capturedSignal?.aborted) {
          reject(capturedSignal.reason)
          return
        }
        capturedSignal?.addEventListener('abort', () => reject(capturedSignal?.reason), { once: true })
      })
    }

    try {
      await assert.rejects(
        createEngagementApiClient({ timeoutMs: 10 }).getUnreadCount({ accessToken: 'access-token' }),
        (error: unknown) => {
          assert.ok(error instanceof ApiClientError)
          assert.equal(error.status, 0)
          assert.equal(error.code, 'REQUEST_TIMEOUT')
          return true
        }
      )
      assert.ok(capturedSignal instanceof AbortSignal)
      assert.equal(capturedSignal.aborted, true)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('preserves backend failures as ApiClientError instead of throwing response-shaped objects', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = async () => new Response(JSON.stringify({
      success: false,
      code: 'ENGAGEMENT_DELIVERY_INVALID',
      message: 'Delivery is no longer valid',
      requestId: 'request-42',
      data: null
    }), {
      status: 409,
      headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'request-42' }
    })

    try {
      await assert.rejects(
        createEngagementApiClient().markShown({ accessToken: 'access-token', deliveryToken: 'delivery-1' }),
        (error: unknown) => {
          assert.ok(error instanceof ApiClientError)
          assert.equal(error.status, 409)
          assert.equal(error.code, 'ENGAGEMENT_DELIVERY_INVALID')
          assert.equal(error.requestId, 'request-42')
          return true
        }
      )
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

type CapturedCall = {
  path: string
  method: string
  authorization: string | null
  body: string | null
}

function installJsonFetch() {
  const originalFetch = globalThis.fetch
  const calls = [] as CapturedCall[] & { restore: () => void }
  calls.restore = () => { globalThis.fetch = originalFetch }
  globalThis.fetch = async (input, init) => {
    calls.push({
      path: String(input),
      method: init?.method ?? 'GET',
      authorization: new Headers(init?.headers).get('Authorization'),
      body: typeof init?.body === 'string' ? init.body : null
    })
    return new Response(JSON.stringify({ success: true, code: 'OK', message: 'ok', data: null }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  }
  return calls
}
