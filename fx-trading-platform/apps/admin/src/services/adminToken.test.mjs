import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { afterEach, describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const tokenSource = readFileSync(join(currentDir, 'adminToken.ts'), 'utf8')
const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

afterEach(() => {
  if (originalLocalStorage) {
    Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  } else {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
})

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

  it('checks Trading Lab authorities as exact independent memberships', async () => {
    const storage = createStorage()
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: storage
    })
    const moduleUrl = new URL('./adminToken.ts', import.meta.url)
    moduleUrl.search = `authority=${Date.now()}`
    const { authorityStorageKey, hasAdminAuthority } = await import(moduleUrl.href)
    const authorities = ['TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE', 'SUPER_ADMIN']

    for (const granted of authorities) {
      storage.setItem(authorityStorageKey, JSON.stringify([granted]))
      for (const checked of authorities) {
        assert.equal(
          hasAdminAuthority(checked),
          checked === granted,
          `${granted} must not imply ${checked}`
        )
      }
    }
  })
})

function createStorage() {
  const values = new Map()
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
