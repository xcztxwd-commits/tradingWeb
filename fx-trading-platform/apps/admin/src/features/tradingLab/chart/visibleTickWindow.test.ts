import assert from 'node:assert/strict'
import test from 'node:test'

import type { MarketTick } from '../oracle/types.ts'
import {
  DEFAULT_VISIBLE_TICK_LIMIT,
  MAX_VISIBLE_TICK_LIMIT,
  selectVisibleTickWindow,
} from './visibleTickWindow.ts'

function marketTick(
  sequence: number,
  virtualTime = new Date(
    Date.UTC(2026, 0, 1, 0, 0, sequence),
  ).toISOString(),
  last = String(100 + sequence),
): MarketTick {
  return {
    sequence,
    virtualTime,
    instruments: [{
      productType: 'CRYPTO_SPOT',
      symbol: 'XBT-USDT-LAB',
      bid: last,
      ask: last,
      last,
    }],
    fundingRates: [],
  }
}

test('default visible window retains the latest 10,000 ticks with exact tail metadata', () => {
  const source = Array.from(
    { length: 10_001 },
    (_, index) => marketTick(index + 1),
  )
  const before = structuredClone(source)

  const window = selectVisibleTickWindow(source)

  assert.equal(DEFAULT_VISIBLE_TICK_LIMIT, 10_000)
  assert.equal(MAX_VISIBLE_TICK_LIMIT, 10_000)
  assert.equal(window.limit, 10_000)
  assert.equal(window.totalCount, 10_001)
  assert.equal(window.ticks.length, 10_000)
  assert.equal(window.firstSequence, 2)
  assert.equal(window.lastSequence, 10_001)
  assert.equal(window.hasOlder, true)
  assert.equal(window.hasNewer, false)
  assert.equal(window.olderBeforeSequence, 2)
  assert.deepEqual(source, before)
})

test('beforeSequence is exclusive and returns an older bounded page', () => {
  const source = Array.from({ length: 6 }, (_, index) => marketTick(index + 1))

  const window = selectVisibleTickWindow(source, {
    limit: 2,
    beforeSequence: 5,
  })

  assert.deepEqual(
    window.ticks.map((tick) => tick.sequence),
    [3, 4],
  )
  assert.deepEqual(window, {
    ticks: window.ticks,
    limit: 2,
    totalCount: 6,
    firstSequence: 3,
    lastSequence: 4,
    hasOlder: true,
    hasNewer: true,
    olderBeforeSequence: 3,
  })
})

test('exact replay is idempotent while a same-sequence conflict fails closed', () => {
  const first = marketTick(1)
  const replay = structuredClone(first)
  const second = marketTick(2)
  const source = [first, replay, second]
  const before = structuredClone(source)

  const window = selectVisibleTickWindow(source)

  assert.deepEqual(
    window.ticks.map((tick) => tick.sequence),
    [1, 2],
  )
  assert.equal(window.totalCount, 2)
  assert.deepEqual(source, before)

  assert.throws(() => selectVisibleTickWindow([
    first,
    marketTick(1, first.virtualTime, '999'),
  ]))
})

test('invalid limits, cursors, sequence order, and UTC time fail closed', () => {
  const source = [marketTick(1)]
  for (const limit of [0, -1, 1.5, 10_001, Number.NaN]) {
    assert.throws(() => selectVisibleTickWindow(source, { limit }))
  }
  for (const beforeSequence of [0, -1, 1.5, Number.NaN]) {
    assert.throws(() => selectVisibleTickWindow(source, { beforeSequence }))
  }

  assert.throws(() => selectVisibleTickWindow([
    marketTick(2),
    marketTick(1),
  ]))
  assert.throws(() => selectVisibleTickWindow([
    marketTick(1, '2026-01-01T00:00:02.000Z'),
    marketTick(2, '2026-01-01T00:00:01.000Z'),
  ]))
  assert.throws(() => selectVisibleTickWindow([
    marketTick(1, '2026-01-01T00:00:01+00:00'),
  ]))
  assert.throws(() => selectVisibleTickWindow([
    marketTick(1, '2026-02-30T00:00:01.000Z'),
  ]))
})

test('empty source returns a stable empty tail without inventing history', () => {
  assert.deepEqual(selectVisibleTickWindow([]), {
    ticks: [],
    limit: 10_000,
    totalCount: 0,
    firstSequence: null,
    lastSequence: null,
    hasOlder: false,
    hasNewer: false,
    olderBeforeSequence: null,
  })
})
