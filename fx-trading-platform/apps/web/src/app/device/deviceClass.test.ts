import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  createDeviceClassStore,
  getDeviceClass,
  mobileViewportQuery
} from './deviceClass.ts'

describe('device class runtime', () => {
  it('classifies 899 and 900 as mobile and 901 as PC through one media query', () => {
    assert.equal(mobileViewportQuery, '(max-width: 900px)')
    assert.equal(getDeviceClass(true), 'mobile')
    assert.equal(getDeviceClass(false), 'pc')

    const media = createFakeMediaQuery(899)
    const store = createDeviceClassStore(media.matchMedia)

    assert.deepEqual(media.queries, [mobileViewportQuery])
    assert.equal(store.getSnapshot(), 'mobile')
    media.setWidth(900)
    assert.equal(store.getSnapshot(), 'mobile')
    media.setWidth(901)
    assert.equal(store.getSnapshot(), 'pc')
  })

  it('shares one MediaQueryList listener and removes it after the final subscriber', () => {
    const media = createFakeMediaQuery(901)
    const store = createDeviceClassStore(media.matchMedia)
    const snapshots: string[] = []
    const firstRelease = store.subscribe(() => snapshots.push(store.getSnapshot()))
    const secondRelease = store.subscribe(() => snapshots.push(`second:${store.getSnapshot()}`))

    assert.equal(media.addCalls, 1)
    media.setWidth(900)
    assert.deepEqual(snapshots, ['mobile', 'second:mobile'])

    firstRelease()
    assert.equal(media.removeCalls, 0)
    secondRelease()
    assert.equal(media.removeCalls, 1)
    media.setWidth(901)
    assert.equal(snapshots.length, 2)
  })

  it('tracks the continuous 901 to 900 to 899 to 901 resize sequence', () => {
    const media = createFakeMediaQuery(901)
    const store = createDeviceClassStore(media.matchMedia)
    const snapshots: string[] = [store.getSnapshot()]
    const release = store.subscribe(() => snapshots.push(store.getSnapshot()))

    media.setWidth(900)
    media.setWidth(899)
    media.setWidth(901)

    assert.deepEqual(snapshots, ['pc', 'mobile', 'mobile', 'pc'])
    release()
  })

  it('uses a deterministic PC snapshot when matchMedia is unavailable', () => {
    const store = createDeviceClassStore(undefined)
    let updates = 0
    const release = store.subscribe(() => { updates += 1 })

    assert.equal(store.getSnapshot(), 'pc')
    assert.equal(store.getServerSnapshot(), 'pc')
    release()
    assert.equal(updates, 0)
  })
})

function createFakeMediaQuery(initialWidth: number) {
  let width = initialWidth
  let addCalls = 0
  let removeCalls = 0
  const queries: string[] = []
  const listeners = new Set<() => void>()
  const mediaQuery = {
    get matches() {
      return width <= 900
    },
    addEventListener(type: string, listener: () => void) {
      assert.equal(type, 'change')
      addCalls += 1
      listeners.add(listener)
    },
    removeEventListener(type: string, listener: () => void) {
      assert.equal(type, 'change')
      removeCalls += 1
      listeners.delete(listener)
    }
  }

  return {
    queries,
    get addCalls() { return addCalls },
    get removeCalls() { return removeCalls },
    matchMedia(query: string) {
      queries.push(query)
      return mediaQuery
    },
    setWidth(nextWidth: number) {
      width = nextWidth
      listeners.forEach((listener) => listener())
    }
  }
}
