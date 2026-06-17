import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const tokenSource = readFileSync(join(currentDir, 'adminToken.ts'), 'utf8')

describe('admin token storage', () => {
  it('clears expired or malformed stored tokens before protected routes render', () => {
    assert.match(tokenSource, /export function getValidAdminToken\(/)
    assert.match(tokenSource, /isJwtExpired\(/)
    assert.match(tokenSource, /clearAdminToken\(\)/)
    assert.match(tokenSource, /exp/)
  })

  it('stores access and refresh tokens as a single admin session', () => {
    assert.match(tokenSource, /refreshTokenStorageKey/)
    assert.match(tokenSource, /authorityStorageKey/)
    assert.match(tokenSource, /export function getAdminRefreshToken\(/)
    assert.match(tokenSource, /export function getAdminAuthorities\(/)
    assert.match(tokenSource, /export function setAdminAuthTokens\(/)
    assert.match(tokenSource, /localStorage\.setItem\(tokenStorageKey,\s*accessToken\)/)
    assert.match(tokenSource, /localStorage\.setItem\(refreshTokenStorageKey,\s*refreshToken\)/)
    assert.match(tokenSource, /JSON\.stringify\(authorities\)/)
    assert.match(tokenSource, /localStorage\.removeItem\(authorityStorageKey\)/)
    assert.match(tokenSource, /localStorage\.removeItem\(refreshTokenStorageKey\)/)
  })
})
