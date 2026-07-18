import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import {
  authSessionChangedEvent,
  clearStoredAuthToken,
  readStoredAuthToken,
  readStoredRefreshToken,
  writeStoredAuthToken,
  writeStoredAuthTokens
} from './sessionStorage.ts'

const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

afterEach(() => {
  if (originalLocalStorage) Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
})

describe('auth session storage', () => {
  it('keeps the exact durable keys and session change event', () => {
    assert.equal(authSessionChangedEvent, 'fx-platform-auth-session-changed')
    const { storage, values } = createStorage()
    writeStoredAuthTokens('access-1', 'refresh-1', storage)
    assert.equal(values.get('fx-platform-auth-token'), 'access-1')
    assert.equal(values.get('fx-platform-auth-refresh-token'), 'refresh-1')
    assert.equal(readStoredAuthToken(storage), 'access-1')
    assert.equal(readStoredRefreshToken(storage), 'refresh-1')
  })

  it('migrates the legacy demo token and prefers the formal token when both exist', () => {
    const legacy = createStorage([['fx-platform-demo-token', 'legacy-token']])
    assert.equal(readStoredAuthToken(legacy.storage), 'legacy-token')
    assert.equal(legacy.values.get('fx-platform-auth-token'), 'legacy-token')
    assert.equal(legacy.values.has('fx-platform-demo-token'), false)

    const both = createStorage([
      ['fx-platform-demo-token', 'legacy-token'],
      ['fx-platform-auth-token', 'formal-token']
    ])
    assert.equal(readStoredAuthToken(both.storage), 'formal-token')
    assert.equal(both.values.has('fx-platform-demo-token'), false)
  })

  it('accepts injected storage and tolerates missing or blocked localStorage', () => {
    assert.equal(readStoredAuthToken(undefined), null)
    assert.doesNotThrow(() => writeStoredAuthToken('token-1', undefined))
    assert.doesNotThrow(() => clearStoredAuthToken(undefined))

    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('blocked')
      }
    })
    assert.equal(readStoredAuthToken(), null)
    assert.doesNotThrow(() => writeStoredAuthToken('token-2'))
    assert.doesNotThrow(() => clearStoredAuthToken())
  })

  it('clears formal, refresh and legacy keys together', () => {
    const { storage, values } = createStorage([
      ['fx-platform-auth-token', 'access'],
      ['fx-platform-auth-refresh-token', 'refresh'],
      ['fx-platform-demo-token', 'legacy']
    ])
    clearStoredAuthToken(storage)
    assert.deepEqual([...values], [])
  })
})

function createStorage(entries: Array<[string, string]> = []) {
  const values = new Map(entries)
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key)
  }
  return { storage, values }
}
