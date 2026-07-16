import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import { ApiClientError, apiDelete, apiGet, parseApiErrorPayload } from './apiClient.ts'

const originalFetch = globalThis.fetch
const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalLocalStorage) {
    Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  } else {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
})

describe('api client error model', () => {
  it('keeps status, code, message, requestId', () => {
    const error = new ApiClientError({
      status: 409,
      code: 'ORDER_REJECTED',
      message: 'Order rejected',
      requestId: 'req-1'
    })

    assert.equal(error.status, 409)
    assert.equal(error.code, 'ORDER_REJECTED')
    assert.equal(error.message, 'Order rejected')
    assert.equal(error.requestId, 'req-1')
  })

  it('parses wrapped backend failure payloads into an API error shape', () => {
    const error = parseApiErrorPayload(
      {
        success: false,
        code: 'VALIDATION_ERROR',
        message: 'Invalid quantity',
        requestId: 'payload-req-1'
      },
      422,
      'header-req-1'
    )

    assert.deepEqual(error, {
      status: 422,
      code: 'VALIDATION_ERROR',
      message: 'Invalid quantity',
      requestId: 'header-req-1'
    })
  })

  it('refreshes a stored session and replays the failed request with the rotated access token', async () => {
    const storage = createStorage({
      'fx-platform-auth-token': 'old-access-token',
      'fx-platform-auth-refresh-token': 'old-refresh-token'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const calls: Array<{ url: string; authorization: string | null; body?: string | null }> = []
    globalThis.fetch = (async (url, init) => {
      const headers = new Headers(init?.headers)
      calls.push({
        url: String(url),
        authorization: headers.get('Authorization'),
        body: typeof init?.body === 'string' ? init.body : null
      })
      if (calls.length === 1) {
        return jsonResponse(401, { success: false, code: 'AUTH_TOKEN_EXPIRED', message: 'expired', data: null })
      }
      if (calls.length === 2) {
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { accessToken: 'new-access-token', refreshToken: 'new-refresh-token' }
        })
      }
      return jsonResponse(200, { success: true, code: 'OK', message: 'OK', data: { ok: true } })
    }) as typeof fetch

    const data = await apiGet<{ ok: boolean }>('/api/accounts', 'old-access-token')

    assert.deepEqual(data, { ok: true })
    assert.equal(calls.length, 3)
    assert.deepEqual(calls.map((call) => call.url), ['/api/accounts', '/api/auth/refresh', '/api/accounts'])
    assert.equal(calls[0].authorization, 'Bearer old-access-token')
    assert.equal(calls[1].authorization, null)
    assert.equal(calls[1].body, '{"refreshToken":"old-refresh-token"}')
    assert.equal(calls[2].authorization, 'Bearer new-access-token')
    assert.equal(storage.getItem('fx-platform-auth-token'), 'new-access-token')
    assert.equal(storage.getItem('fx-platform-auth-refresh-token'), 'new-refresh-token')
  })

  it('sends DELETE through the shared authenticated response and error pipeline without a body', async () => {
    let captured: { method?: string; body?: BodyInit | null; authorization: string | null } | undefined
    globalThis.fetch = (async (_url, init) => {
      captured = {
        method: init?.method,
        body: init?.body,
        authorization: new Headers(init?.headers).get('Authorization')
      }
      return jsonResponse(200, { success: true, code: 'OK', message: 'OK', data: { id: 'order-1' } })
    }) as typeof fetch

    assert.deepEqual(await apiDelete('/api/trading/protections/order-1', 'token_1'), { id: 'order-1' })
    assert.deepEqual(captured, { method: 'DELETE', body: undefined, authorization: 'Bearer token_1' })
  })
})

function jsonResponse(status: number, payload: unknown) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function createStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem(key: string) {
      return values.get(key) ?? null
    },
    setItem(key: string, value: string) {
      values.set(key, value)
    },
    removeItem(key: string) {
      values.delete(key)
    }
  } satisfies Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
}
