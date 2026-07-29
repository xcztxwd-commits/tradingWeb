import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'
import { afterEach } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const clientSource = readFileSync(join(currentDir, 'apiClient.ts'), 'utf8')
const authApiSource = readFileSync(join(currentDir, 'authApi.ts'), 'utf8')
const openapiSource = readFileSync(join(currentDir, '..', '..', '..', '..', 'packages/shared-types/src/generated/openapi.ts'), 'utf8')
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

describe('admin api client error handling', () => {
  it('handles empty auth failures without surfacing JSON parser errors', () => {
    assert.match(clientSource, /response\.text\(\)/)
    assert.doesNotMatch(clientSource, /await response\.json\(\)/)
    assert.match(clientSource, /status === 401 \|\| status === 403/)
    assert.match(clientSource, /登录已过期或无管理员权限，请重新登录/)
  })

  it('keeps status and code on thrown API errors', () => {
    assert.match(clientSource, /class ApiClientError extends Error/)
    assert.match(clientSource, /readonly status: number/)
    assert.match(clientSource, /readonly code: string/)
    assert.match(clientSource, /FORBIDDEN/)
    assert.match(clientSource, /AUTH_TOKEN_EXPIRED/)
  })

  it('assigns a canonical request ID unless the caller already supplied one', async () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: createStorage({})
    })
    const observed = []
    globalThis.fetch = async (_url, init) => {
      observed.push(new Headers(init?.headers).get('X-Request-Id'))
      return jsonResponse(200, {
        success: true,
        code: 'OK',
        message: 'OK',
        data: { ok: true }
      })
    }
    const { apiGet, apiRaw } = await importFreshApiClient('request-id')
    const supplied = '00000000-0000-4000-8000-000000000099'

    await apiGet('/api/admin/trading-lab/scenarios')
    await apiRaw('/api/admin/trading-lab/environment', {
      headers: { 'X-Request-Id': supplied }
    })

    assert.match(
      observed[0],
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
    assert.equal(observed[1], supplied)
  })

  it('refreshes expired admin sessions and replays the failed request once', () => {
    assert.match(openapiSource, /refreshToken: string/)
    assert.match(openapiSource, /authorities: string\[\]/)
    assert.match(authApiSource, /\/api\/auth\/refresh/)
    assert.match(authApiSource, /\/api\/auth\/logout/)
    assert.match(clientSource, /getAdminRefreshToken/)
    assert.match(clientSource, /setAdminAuthTokens/)
    assert.match(clientSource, /response\.status === 401/)
    assert.match(clientSource, /retryOnAuthFailure:\s*false/)
    assert.match(clientSource, /return request<T>\(path,\s*init,\s*refreshedToken/)
  })

  it('executes refresh and replays the failed admin request with stored authorities', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const calls = []
    globalThis.fetch = async (url, init) => {
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
          data: {
            accessToken: 'new-admin-access-token',
            refreshToken: 'new-admin-refresh-token',
            authorities: ['ROLE_ADMIN', 'user:force-logout']
          }
        })
      }
      return jsonResponse(200, { success: true, code: 'OK', message: 'OK', data: { ok: true } })
    }
    const moduleUrl = new URL('./apiClient.ts', import.meta.url)
    moduleUrl.search = `behavior=${Date.now()}`
    const { apiGet } = await import(moduleUrl.href)

    const data = await apiGet('/api/admin/users', 'old-admin-access-token')

    assert.deepEqual(data, { ok: true })
    assert.equal(calls.length, 3)
    assert.deepEqual(calls.map((call) => call.url), ['/api/admin/users', '/api/auth/refresh', '/api/admin/users'])
    assert.equal(calls[0].authorization, 'Bearer old-admin-access-token')
    assert.equal(calls[1].authorization, null)
    assert.equal(calls[1].body, '{"refreshToken":"old-admin-refresh-token"}')
    assert.equal(calls[2].authorization, 'Bearer new-admin-access-token')
    assert.equal(storage.getItem('fx-platform-admin-token'), 'new-admin-access-token')
    assert.equal(storage.getItem('fx-platform-admin-refresh-token'), 'new-admin-refresh-token')
    assert.equal(storage.getItem('fx-platform-admin-authorities'), '["ROLE_ADMIN","user:force-logout"]')
  })

  it('shares one refresh across concurrent JSON and raw requests', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const bothInitialRequestsStarted = deferred()
    const rawResponse = new Response('stream-ready', {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' }
    })
    const calls = []
    let initialRequestCount = 0
    let refreshCount = 0
    globalThis.fetch = async (url, init) => {
      const requestUrl = String(url)
      const authorization = new Headers(init?.headers).get('Authorization')
      calls.push({ url: requestUrl, authorization })
      if (requestUrl === '/api/auth/refresh') {
        refreshCount += 1
        await bothInitialRequestsStarted.promise
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            accessToken: 'new-admin-access-token',
            refreshToken: 'new-admin-refresh-token',
            authorities: ['TRADING_LAB_VIEW']
          }
        })
      }
      if (authorization === 'Bearer old-admin-access-token') {
        initialRequestCount += 1
        if (initialRequestCount === 2) {
          bothInitialRequestsStarted.resolve()
        }
        return jsonResponse(401, {
          success: false,
          code: 'AUTH_TOKEN_EXPIRED',
          message: 'expired',
          data: null
        })
      }
      if (requestUrl === '/api/admin/trading-lab/scenarios') {
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { items: [] }
        })
      }
      if (requestUrl === '/api/admin/trading-lab/runs/run-1/events') {
        return rawResponse
      }
      throw new Error(`Unexpected fetch: ${requestUrl}`)
    }
    const { apiGet, apiRaw } = await importFreshApiClient('shared-refresh')
    assert.equal(typeof apiRaw, 'function')

    const [jsonData, streamedResponse] = await Promise.all([
      apiGet('/api/admin/trading-lab/scenarios', 'old-admin-access-token'),
      apiRaw('/api/admin/trading-lab/runs/run-1/events')
    ])

    assert.deepEqual(jsonData, { items: [] })
    assert.equal(streamedResponse, rawResponse)
    assert.equal(refreshCount, 1)
    assert.equal(initialRequestCount, 2)
    assert.equal(
      calls.filter((call) => call.authorization === 'Bearer new-admin-access-token').length,
      2
    )
  })

  it('replays apiRaw with the same AbortSignal and leaves the raw body unread', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const controller = new AbortController()
    const unauthorizedResponse = new Response('expired', { status: 401 })
    const rawResponse = new Response(
      new ReadableStream({
        start(streamController) {
          streamController.enqueue(new TextEncoder().encode('event: progress\n\n'))
          streamController.close()
        }
      }),
      {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' }
      }
    )
    let unauthorizedTextCalls = 0
    let rawTextCalls = 0
    Object.defineProperty(unauthorizedResponse, 'text', {
      configurable: true,
      value: async () => {
        unauthorizedTextCalls += 1
        throw new Error('apiRaw must not parse the unauthorized body')
      }
    })
    Object.defineProperty(rawResponse, 'text', {
      configurable: true,
      value: async () => {
        rawTextCalls += 1
        throw new Error('apiRaw must not parse the stream body')
      }
    })
    const rawRequestSignals = []
    const rawRequestHeaders = []
    let rawRequestCount = 0
    globalThis.fetch = async (url, init) => {
      const requestUrl = String(url)
      if (requestUrl === '/api/auth/refresh') {
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            accessToken: 'new-admin-access-token',
            refreshToken: 'new-admin-refresh-token',
            authorities: ['TRADING_LAB_VIEW']
          }
        })
      }
      rawRequestCount += 1
      rawRequestSignals.push(init?.signal)
      rawRequestHeaders.push(new Headers(init?.headers))
      return rawRequestCount === 1 ? unauthorizedResponse : rawResponse
    }
    const { apiRaw } = await importFreshApiClient('raw-signal')

    const response = await apiRaw('/api/admin/trading-lab/runs/run-1/events', {
      headers: { 'X-Trading-Lab-Test': 'stream' },
      signal: controller.signal
    })

    assert.equal(response, rawResponse)
    assert.deepEqual(rawRequestSignals, [controller.signal, controller.signal])
    assert.equal(rawRequestHeaders[0].get('Authorization'), 'Bearer old-admin-access-token')
    assert.equal(rawRequestHeaders[1].get('Authorization'), 'Bearer new-admin-access-token')
    assert.equal(rawRequestHeaders[0].get('X-Trading-Lab-Test'), 'stream')
    assert.equal(rawRequestHeaders[1].get('X-Trading-Lab-Test'), 'stream')
    assert.equal(unauthorizedTextCalls, 0)
    assert.equal(rawTextCalls, 0)
    assert.equal(response.bodyUsed, false)

    const firstChunk = await response.body?.getReader().read()
    assert.equal(new TextDecoder().decode(firstChunk?.value), 'event: progress\n\n')
  })

  it('cancels the unconsumed apiRaw 401 body before refreshing and replaying', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const events = []
    const unauthorizedResponse = new Response(
      new ReadableStream({
        cancel() {
          events.push('unauthorized-body:cancel')
        }
      }),
      { status: 401 }
    )
    const rawResponse = new Response('stream-ready', {
      status: 200,
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' }
    })
    let rawCalls = 0
    globalThis.fetch = async (url) => {
      if (String(url) === '/api/auth/refresh') {
        events.push('refresh')
        assert.deepEqual(events, [
          'unauthorized-body:cancel',
          'refresh'
        ])
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            accessToken: 'new-admin-access-token',
            refreshToken: 'new-admin-refresh-token',
            authorities: ['TRADING_LAB_VIEW']
          }
        })
      }
      rawCalls += 1
      return rawCalls === 1 ? unauthorizedResponse : rawResponse
    }
    const { apiRaw } = await importFreshApiClient('raw-cancel-401')

    assert.equal(
      await apiRaw('/api/admin/trading-lab/reports/report-id/print'),
      rawResponse
    )
    assert.deepEqual(events, [
      'unauthorized-body:cancel',
      'refresh'
    ])
    assert.equal(rawCalls, 2)
  })

  it('leaves a final apiRaw 401 body untouched when no refresh replay is possible', async () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: createStorage()
    })
    const unauthorizedResponse = new Response('final unauthorized', {
      status: 401
    })
    globalThis.fetch = async () => unauthorizedResponse
    const { apiRaw } = await importFreshApiClient('raw-final-401')

    const response = await apiRaw('/api/admin/trading-lab/reports/report-id/print')

    assert.equal(response, unauthorizedResponse)
    assert.equal(response.bodyUsed, false)
    assert.equal(await response.text(), 'final unauthorized')
  })

  it('clears one admin session when a shared refresh fails', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token',
      'fx-platform-admin-authorities': '["TRADING_LAB_VIEW"]'
    })
    const removedKeys = []
    const removeItem = storage.removeItem.bind(storage)
    storage.removeItem = (key) => {
      removedKeys.push(key)
      removeItem(key)
    }
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const bothInitialRequestsStarted = deferred()
    let initialRequestCount = 0
    let refreshCount = 0
    globalThis.fetch = async (url) => {
      if (String(url) === '/api/auth/refresh') {
        refreshCount += 1
        await bothInitialRequestsStarted.promise
        return jsonResponse(401, {
          success: false,
          code: 'AUTH_TOKEN_EXPIRED',
          message: 'refresh expired',
          data: null
        })
      }
      initialRequestCount += 1
      if (initialRequestCount === 2) {
        bothInitialRequestsStarted.resolve()
      }
      return jsonResponse(401, {
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'expired',
        data: null
      })
    }
    const { apiGet } = await importFreshApiClient('failed-refresh')

    const results = await Promise.allSettled([
      apiGet('/api/admin/trading-lab/scenarios/one', 'old-admin-access-token'),
      apiGet('/api/admin/trading-lab/scenarios/two', 'old-admin-access-token')
    ])

    assert.equal(refreshCount, 1)
    assert.equal(initialRequestCount, 2)
    assert.deepEqual(results.map((result) => result.status), ['rejected', 'rejected'])
    assert.deepEqual(removedKeys.sort(), [
      'fx-platform-admin-authorities',
      'fx-platform-admin-refresh-token',
      'fx-platform-admin-token'
    ])
  })

  it('does not clear a replacement session when an older refresh fails', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token',
      'fx-platform-admin-authorities': '["OLD"]'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const refreshStarted = deferred()
    const releaseRefresh = deferred()
    globalThis.fetch = async (url) => {
      if (String(url) === '/api/auth/refresh') {
        refreshStarted.resolve()
        await releaseRefresh.promise
        return jsonResponse(401, {
          success: false,
          code: 'AUTH_TOKEN_EXPIRED',
          message: 'old refresh expired',
          data: null
        })
      }
      return jsonResponse(401, {
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'old access expired',
        data: null
      })
    }
    const { apiGet } = await importFreshApiClient('failed-refresh-session-fence')

    const oldRequest = apiGet('/api/admin/trading-lab/scenarios', 'old-admin-access-token')
    await refreshStarted.promise
    storage.setItem('fx-platform-admin-token', 'replacement-access-token')
    storage.setItem('fx-platform-admin-refresh-token', 'replacement-refresh-token')
    storage.setItem('fx-platform-admin-authorities', '["TRADING_LAB_VIEW"]')
    releaseRefresh.resolve()
    await assert.rejects(oldRequest)

    assert.equal(storage.getItem('fx-platform-admin-token'), 'replacement-access-token')
    assert.equal(storage.getItem('fx-platform-admin-refresh-token'), 'replacement-refresh-token')
    assert.equal(storage.getItem('fx-platform-admin-authorities'), '["TRADING_LAB_VIEW"]')
  })

  it('does not resurrect a logged-out session when an older refresh succeeds', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'old-admin-access-token',
      'fx-platform-admin-refresh-token': 'old-admin-refresh-token',
      'fx-platform-admin-authorities': '["OLD"]'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const refreshStarted = deferred()
    const releaseRefresh = deferred()
    globalThis.fetch = async (url, init) => {
      if (String(url) === '/api/auth/refresh') {
        refreshStarted.resolve()
        await releaseRefresh.promise
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            accessToken: 'resurrected-access-token',
            refreshToken: 'resurrected-refresh-token',
            authorities: ['OLD']
          }
        })
      }
      const authorization = new Headers(init?.headers).get('Authorization')
      if (authorization === 'Bearer resurrected-access-token') {
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { shouldNotReplay: true }
        })
      }
      return jsonResponse(401, {
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'old access expired',
        data: null
      })
    }
    const { apiGet } = await importFreshApiClient('successful-refresh-session-fence')

    const oldRequest = apiGet('/api/admin/trading-lab/scenarios', 'old-admin-access-token')
    await refreshStarted.promise
    storage.removeItem('fx-platform-admin-token')
    storage.removeItem('fx-platform-admin-refresh-token')
    storage.removeItem('fx-platform-admin-authorities')
    releaseRefresh.resolve()
    await oldRequest.catch(() => undefined)

    assert.equal(storage.getItem('fx-platform-admin-token'), null)
    assert.equal(storage.getItem('fx-platform-admin-refresh-token'), null)
    assert.equal(storage.getItem('fx-platform-admin-authorities'), null)
  })

  it('does not replay an older request body through a replacement session', async () => {
    const storage = createStorage({
      'fx-platform-admin-token': 'access-A',
      'fx-platform-admin-refresh-token': 'refresh-A',
      'fx-platform-admin-authorities': '["SESSION_A"]'
    })
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const requestStarted = deferred()
    const releaseUnauthorized = deferred()
    const dangerCalls = []
    let refreshCount = 0
    globalThis.fetch = async (url, init) => {
      const requestUrl = String(url)
      if (requestUrl === '/api/auth/refresh') {
        refreshCount += 1
        return jsonResponse(200, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            accessToken: 'access-B2',
            refreshToken: 'refresh-B2',
            authorities: ['SESSION_B']
          }
        })
      }
      dangerCalls.push({
        authorization: new Headers(init?.headers).get('Authorization'),
        body: init?.body
      })
      if (dangerCalls.length === 1) {
        requestStarted.resolve()
        await releaseUnauthorized.promise
        return jsonResponse(401, {
          success: false,
          code: 'AUTH_TOKEN_EXPIRED',
          message: 'session A expired',
          data: null
        })
      }
      return jsonResponse(200, {
        success: true,
        code: 'OK',
        message: 'OK',
        data: { executedAs: 'SESSION_B' }
      })
    }
    const { apiPost } = await importFreshApiClient('request-session-fence')

    const oldRequest = apiPost(
      '/api/admin/danger',
      { target: 'from-A' },
      'access-A'
    )
    await requestStarted.promise
    storage.setItem('fx-platform-admin-token', 'access-B')
    storage.setItem('fx-platform-admin-refresh-token', 'refresh-B')
    storage.setItem('fx-platform-admin-authorities', '["SESSION_B"]')
    releaseUnauthorized.resolve()
    await oldRequest.catch(() => undefined)

    assert.equal(refreshCount, 0)
    assert.deepEqual(dangerCalls, [{
      authorization: 'Bearer access-A',
      body: '{"target":"from-A"}'
    }])
    assert.equal(storage.getItem('fx-platform-admin-token'), 'access-B')
    assert.equal(storage.getItem('fx-platform-admin-refresh-token'), 'refresh-B')
    assert.equal(storage.getItem('fx-platform-admin-authorities'), '["SESSION_B"]')
  })
})

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem(key) {
      return values.get(key) ?? null
    },
    setItem(key, value) {
      values.set(key, value)
    },
    removeItem(key) {
      values.delete(key)
    }
  }
}

function deferred() {
  let resolve
  const promise = new Promise((next) => {
    resolve = next
  })
  return {
    promise,
    resolve: () => resolve()
  }
}

function importFreshApiClient(testName) {
  const moduleUrl = new URL('./apiClient.ts', import.meta.url)
  moduleUrl.search = `${testName}-${Date.now()}-${Math.random()}`
  return import(moduleUrl.href)
}
