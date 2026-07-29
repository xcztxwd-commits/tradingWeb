import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createTradingLabDesktopWidthSource,
  TRADING_LAB_DESKTOP_MEDIA_QUERY,
  type TradingLabMediaQueryList,
} from './desktopWidth.ts'

type ChangeListener = (event: MediaQueryListEvent) => void

class FakeMediaQueryList implements TradingLabMediaQueryList {
  readonly media = TRADING_LAB_DESKTOP_MEDIA_QUERY

  private readonly listeners = new Set<ChangeListener>()
  private width: number

  constructor(width: number) {
    this.width = width
  }

  get matches(): boolean {
    return this.width >= 1280
  }

  get listenerCount(): number {
    return this.listeners.size
  }

  addEventListener(type: 'change', listener: ChangeListener): void {
    assert.equal(type, 'change')
    this.listeners.add(listener)
  }

  removeEventListener(type: 'change', listener: ChangeListener): void {
    assert.equal(type, 'change')
    this.listeners.delete(listener)
  }

  setWidth(width: number): void {
    this.width = width
    const event = {
      matches: this.matches,
      media: this.media,
    } as MediaQueryListEvent
    for (const listener of this.listeners) {
      listener(event)
    }
  }
}

test('uses the exact desktop media query and reads 1279/1280 initial state', () => {
  for (const [width, expected] of [
    [1279, false],
    [1280, true],
  ] as const) {
    const mediaQueryList = new FakeMediaQueryList(width)
    const queries: string[] = []
    const source = createTradingLabDesktopWidthSource((query) => {
      queries.push(query)
      return mediaQueryList
    })

    assert.equal(source.getSnapshot(), expected)
    assert.deepEqual(queries, ['(min-width: 1280px)'])
    assert.equal(TRADING_LAB_DESKTOP_MEDIA_QUERY, '(min-width: 1280px)')
  }
})

test('notifies on media-query changes and removes the same listener on cleanup', () => {
  const mediaQueryList = new FakeMediaQueryList(1279)
  const source = createTradingLabDesktopWidthSource(() => mediaQueryList)
  let notifications = 0

  const unsubscribe = source.subscribe(() => {
    notifications += 1
  })

  assert.equal(mediaQueryList.listenerCount, 1)
  mediaQueryList.setWidth(1280)
  assert.equal(notifications, 1)
  assert.equal(source.getSnapshot(), true)

  unsubscribe()
  assert.equal(mediaQueryList.listenerCount, 0)
  mediaQueryList.setWidth(1279)
  assert.equal(notifications, 1)
  assert.equal(source.getSnapshot(), false)
})
