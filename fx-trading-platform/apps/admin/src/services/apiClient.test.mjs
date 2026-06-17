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
