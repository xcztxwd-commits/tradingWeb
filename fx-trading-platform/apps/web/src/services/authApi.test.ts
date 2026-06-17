import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./authApi.ts', import.meta.url), 'utf8')
const apiClientSource = readFileSync(new URL('./apiClient.ts', import.meta.url), 'utf8')

describe('auth api endpoint contracts', () => {
  it('only exposes auth endpoints implemented by the backend for the main web app', () => {
    assert.match(source, /\/api\/auth\/register/)
    assert.match(source, /\/api\/auth\/login/)
    assert.match(source, /\/api\/auth\/session/)
    assert.match(source, /\/api\/auth\/refresh/)
    assert.match(source, /\/api\/auth\/logout/)
    assert.doesNotMatch(source, /\/api\/auth\/identity-check/)
    assert.doesNotMatch(source, /\/api\/auth\/verification-code/)
  })

  it('refreshes the stored session on auth failures and replays the original request once', () => {
    assert.match(apiClientSource, /readStoredRefreshToken/)
    assert.match(apiClientSource, /writeStoredAuthTokens/)
    assert.match(apiClientSource, /refreshStoredAuthSession/)
    assert.match(apiClientSource, /response\.status === 401/)
    assert.match(apiClientSource, /retryOnAuthFailure:\s*false/)
    assert.match(apiClientSource, /return request<T>\(path,\s*init,\s*refreshedToken/)
  })
})
