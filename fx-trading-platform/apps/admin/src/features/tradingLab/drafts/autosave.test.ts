import assert from 'node:assert/strict'
import { setImmediate as nextTurn } from 'node:timers/promises'
import test from 'node:test'

import {
  createTradingLabDraftAutosave,
  type TradingLabAutosaveTimers,
} from './autosave.ts'
import type { TradingLabDraftStore } from './draftStore.ts'
import type { TradingLabScenario } from '../model/types.ts'

class ManualTimers implements TradingLabAutosaveTimers {
  readonly requestedDelays: number[] = []
  readonly clearedHandles: unknown[] = []

  private readonly callbacks = new Map<number, () => void>()
  private nextHandle = 1

  setTimeout(callback: () => void, delayMs: number): unknown {
    const handle = this.nextHandle
    this.nextHandle += 1
    this.callbacks.set(handle, callback)
    this.requestedDelays.push(delayMs)
    return handle
  }

  clearTimeout(handle: unknown): void {
    this.clearedHandles.push(handle)
    this.callbacks.delete(handle as number)
  }

  get pendingCount(): number {
    return this.callbacks.size
  }

  fireNext(): void {
    const next = this.callbacks.entries().next()
    assert.equal(next.done, false, 'expected a pending timer')
    if (next.done) {
      return
    }

    const [handle, callback] = next.value
    this.callbacks.delete(handle)
    callback()
  }
}

type Deferred = Readonly<{
  promise: Promise<void>
  resolve(): void
  reject(error: unknown): void
}>

function deferred(): Deferred {
  let resolve!: () => void
  let reject!: (error: unknown) => void
  const promise = new Promise<void>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function settleMicrotasks(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

function scenario(id: string): TradingLabScenario {
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0',
    takerFeeRate: '0',
    liquidationFeeRate: '0',
    slippageRate: '0',
    maxFillQuantityPerTick: '1',
  }

  return {
    id,
    name: `draft-${id}`,
    description: '',
    negativeMode: false,
    seed: 'autosave-test-seed',
    modelVersion: 'autosave-test-model',
    configSnapshot: {
      modelVersion: 'autosave-test-model',
      symbolConfigVersion: 'autosave-test-symbols',
      codeVersion: 'autosave-test-code',
      executionPolicy,
      instruments: [],
    },
    configSnapshotHash: 'autosave-test-hash',
    executionPolicy,
    marketPath: {
      virtualStart: '2026-01-01T00:00:00.000Z',
      realistic: false,
      instruments: [],
    },
    initialBalances: {},
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [],
    timeline: [],
  }
}

function storeWith(
  put: TradingLabDraftStore['put'],
): TradingLabDraftStore {
  return {
    put,
    get: async () => null,
    list: async () => [],
    delete: async () => {},
  }
}

test('uses a 500ms default and requires a nonnegative safe-integer delay', () => {
  const timers = new ManualTimers()
  const store = storeWith(async () => {})
  const autosave = createTradingLabDraftAutosave(store, { timers })

  autosave.schedule(scenario('default-delay'))
  assert.deepEqual(timers.requestedDelays, [500])
  autosave.cancel()

  for (const delayMs of [-1, 0.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => {
      createTradingLabDraftAutosave(store, { delayMs, timers })
    })
  }

  const zeroDelayTimers = new ManualTimers()
  const zeroDelayAutosave = createTradingLabDraftAutosave(store, {
    delayMs: 0,
    timers: zeroDelayTimers,
  })
  zeroDelayAutosave.schedule(scenario('zero-delay'))
  assert.deepEqual(zeroDelayTimers.requestedDelays, [0])
  zeroDelayAutosave.cancel()
})

test('every schedule resets the debounce and the latest scenario wins', async () => {
  const timers = new ManualTimers()
  const saved: TradingLabScenario[] = []
  const autosave = createTradingLabDraftAutosave(
    storeWith(async (draft) => {
      saved.push(draft)
    }),
    { delayMs: 25, timers },
  )

  autosave.schedule(scenario('first'))
  autosave.schedule(scenario('latest'))

  assert.equal(timers.pendingCount, 1)
  assert.equal(timers.clearedHandles.length, 1)
  assert.deepEqual(timers.requestedDelays, [25, 25])

  timers.fireNext()
  await autosave.flush()

  assert.deepEqual(saved.map((draft) => draft.id), ['latest'])
})

test('serializes writes after their debounce timers have fired', async () => {
  const timers = new ManualTimers()
  const firstWrite = deferred()
  const secondWrite = deferred()
  const calls: string[] = []
  const autosave = createTradingLabDraftAutosave(
    storeWith((draft) => {
      calls.push(draft.id)
      return calls.length === 1 ? firstWrite.promise : secondWrite.promise
    }),
    { timers },
  )

  autosave.schedule(scenario('first'))
  timers.fireNext()
  await settleMicrotasks()

  autosave.schedule(scenario('second'))
  timers.fireNext()
  await settleMicrotasks()

  assert.deepEqual(calls, ['first'])

  firstWrite.resolve()
  await settleMicrotasks()
  assert.deepEqual(calls, ['first', 'second'])

  secondWrite.resolve()
  await autosave.flush()
})

test('flush cancels the timer, persists the latest pending draft, and waits for the queue', async () => {
  const timers = new ManualTimers()
  const firstWrite = deferred()
  const secondWrite = deferred()
  const calls: string[] = []
  const autosave = createTradingLabDraftAutosave(
    storeWith((draft) => {
      calls.push(draft.id)
      return calls.length === 1 ? firstWrite.promise : secondWrite.promise
    }),
    { timers },
  )

  autosave.schedule(scenario('in-flight'))
  timers.fireNext()
  await settleMicrotasks()
  autosave.schedule(scenario('pending'))

  let flushFinished = false
  const flushing = autosave.flush().then(() => {
    flushFinished = true
  })
  await settleMicrotasks()

  assert.equal(timers.pendingCount, 0)
  assert.deepEqual(calls, ['in-flight'])
  assert.equal(flushFinished, false)

  firstWrite.resolve()
  await settleMicrotasks()
  assert.deepEqual(calls, ['in-flight', 'pending'])
  assert.equal(flushFinished, false)

  secondWrite.resolve()
  await flushing
  assert.equal(flushFinished, true)
})

test('cancel drops only work whose debounce timer has not fired', async () => {
  const timers = new ManualTimers()
  const inFlightWrite = deferred()
  const calls: string[] = []
  const autosave = createTradingLabDraftAutosave(
    storeWith((draft) => {
      calls.push(draft.id)
      return inFlightWrite.promise
    }),
    { timers },
  )

  autosave.schedule(scenario('not-fired'))
  autosave.cancel()
  assert.equal(timers.pendingCount, 0)
  await autosave.flush()
  assert.deepEqual(calls, [])

  autosave.schedule(scenario('already-fired'))
  timers.fireNext()
  await settleMicrotasks()
  autosave.cancel()

  assert.deepEqual(calls, ['already-fired'])

  inFlightWrite.resolve()
  await autosave.flush()
  assert.deepEqual(calls, ['already-fired'])
})

test('a timer-triggered rejection is handled and observed by the next flush', async () => {
  const timers = new ManualTimers()
  const failedWrite = deferred()
  const failure = new Error('draft write failed')
  const autosave = createTradingLabDraftAutosave(
    storeWith(() => failedWrite.promise),
    { timers },
  )
  const unhandled: unknown[] = []
  const onUnhandled = (error: unknown): void => {
    unhandled.push(error)
  }
  process.on('unhandledRejection', onUnhandled)

  try {
    autosave.schedule(scenario('business-invalid-but-typed'))
    timers.fireNext()
    await settleMicrotasks()
    failedWrite.reject(failure)
    await nextTurn()

    assert.deepEqual(unhandled, [])
    await assert.rejects(autosave.flush(), failure)
  } finally {
    process.off('unhandledRejection', onUnhandled)
  }
})
