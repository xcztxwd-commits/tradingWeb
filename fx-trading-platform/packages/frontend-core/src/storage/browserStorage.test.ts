import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import { getBrowserStorage, type KeyValueStorage } from './browserStorage.ts'

const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

afterEach(() => {
  if (originalLocalStorage) Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
})

describe('browser storage boundary', () => {
  it('returns the browser storage through the shared narrow interface', () => {
    const storage: KeyValueStorage = {
      getItem: () => null,
      setItem: () => undefined,
      removeItem: () => undefined
    }
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
    assert.equal(getBrowserStorage(), storage)
  })

  it('returns undefined when browser storage access is blocked', () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('blocked')
      }
    })
    assert.equal(getBrowserStorage(), undefined)
  })
})
