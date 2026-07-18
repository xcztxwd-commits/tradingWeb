import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  createAccountRefreshCoordinator,
  createLatestSingleFlightRefreshGate
} from './accountRefreshCoordinator.ts'
import type { RefreshScheduler, RefreshVisibilitySource } from './accountRefreshCoordinator.ts'

describe('account refresh coordinator', () => {
  it('coalesces event bursts into one refresh after 200ms', async () => {
    const scheduler = new ManualScheduler()
    let refreshes = 0
    const coordinator = createAccountRefreshCoordinator({
      refresh: async () => {
        refreshes += 1
      },
      scheduler
    })

    coordinator.notifyEvent()
    coordinator.notifyEvent()
    scheduler.advanceBy(199)
    assert.equal(refreshes, 0)

    coordinator.notifyEvent()
    scheduler.advanceBy(1)
    await settle()

    assert.equal(refreshes, 1)
  })

  it('merges events received during a single flight into at most one trailing refresh', async () => {
    const scheduler = new ManualScheduler()
    const flights: Array<ReturnType<typeof deferred<void>>> = []
    const coordinator = createAccountRefreshCoordinator({
      refresh: () => {
        const flight = deferred<void>()
        flights.push(flight)
        return flight.promise
      },
      scheduler
    })

    void coordinator.refreshNow()
    assert.equal(flights.length, 1)

    coordinator.notifyEvent()
    coordinator.notifyEvent()
    scheduler.advanceBy(200)
    await settle()
    coordinator.notifyEvent()
    scheduler.advanceBy(200)
    await settle()
    assert.equal(flights.length, 1)

    flights[0].resolve()
    await settle()
    assert.equal(flights.length, 2)

    flights[1].resolve()
    await settle()
    assert.equal(flights.length, 2)
  })

  it('uses a 15s visible-page polling fallback and skips hidden-page ticks', async () => {
    const scheduler = new ManualScheduler()
    const visibility = new ManualVisibilitySource(true)
    let refreshes = 0
    const coordinator = createAccountRefreshCoordinator({
      refresh: async () => {
        refreshes += 1
      },
      scheduler,
      visibility
    })

    coordinator.start()
    scheduler.advanceBy(14_999)
    assert.equal(refreshes, 0)
    scheduler.advanceBy(1)
    await settle()
    assert.equal(refreshes, 1)

    visibility.setVisible(false)
    scheduler.advanceBy(15_000)
    await settle()
    assert.equal(refreshes, 1)
  })

  it('refreshes immediately after stream reconnect', async () => {
    const scheduler = new ManualScheduler()
    let refreshes = 0
    const coordinator = createAccountRefreshCoordinator({
      refresh: async () => {
        refreshes += 1
      },
      scheduler
    })

    coordinator.notifyReconnect()
    await settle()

    assert.equal(refreshes, 1)
  })

  it('cleans timers, visibility listeners and trailing work on dispose', async () => {
    const scheduler = new ManualScheduler()
    const visibility = new ManualVisibilitySource(true)
    const flight = deferred<void>()
    let refreshes = 0
    let errors = 0
    const coordinator = createAccountRefreshCoordinator({
      refresh: () => {
        refreshes += 1
        return flight.promise
      },
      onError: () => {
        errors += 1
      },
      scheduler,
      visibility
    })

    coordinator.start()
    void coordinator.refreshNow()
    coordinator.notifyEvent()
    assert.equal(visibility.listenerCount, 1)

    coordinator.dispose()
    assert.equal(visibility.listenerCount, 0)
    scheduler.advanceBy(60_000)
    flight.reject(new Error('late failure'))
    await settle()

    assert.equal(refreshes, 1)
    assert.equal(errors, 0)
    assert.equal(scheduler.pendingCount, 0)
  })
})

describe('latest single-flight refresh gate', () => {
  it('skips an obsolete result and resolves every merged caller after the latest trailing refresh applies', async () => {
    const gate = createLatestSingleFlightRefreshGate<string>()
    const first = deferred<string>()
    const latest = deferred<string>()
    const loads: string[] = []
    const applied: string[] = []

    const firstRequest = gate.request(
      () => {
        loads.push('first')
        return first.promise
      },
      (value) => applied.push(value)
    )
    const replacedRequest = gate.request(
      async () => {
        loads.push('replaced')
        return 'replaced'
      },
      (value) => applied.push(value)
    )
    const latestRequest = gate.request(
      () => {
        loads.push('latest')
        return latest.promise
      },
      (value) => applied.push(value)
    )

    assert.deepEqual(loads, ['first'])
    first.resolve('obsolete')
    await settle()
    assert.deepEqual(loads, ['first', 'latest'])
    assert.deepEqual(applied, [])

    latest.resolve('authoritative')
    await Promise.all([firstRequest, replacedRequest, latestRequest])

    assert.deepEqual(applied, ['authoritative'])
  })

  it('invalidates an in-flight result without permanently disabling later refreshes', async () => {
    const gate = createLatestSingleFlightRefreshGate<string>()
    const stale = deferred<string>()
    const applied: string[] = []

    const staleRequest = gate.request(() => stale.promise, (value) => applied.push(value))
    gate.invalidate()
    stale.resolve('stale')
    await staleRequest

    await gate.request(async () => 'fresh', (value) => applied.push(value))

    assert.deepEqual(applied, ['fresh'])
  })
})

class ManualVisibilitySource implements RefreshVisibilitySource {
  private listeners = new Set<() => void>()
  private visible: boolean

  constructor(visible: boolean) {
    this.visible = visible
  }

  isVisible() {
    return this.visible
  }

  subscribe(listener: () => void) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  setVisible(visible: boolean) {
    this.visible = visible
    for (const listener of this.listeners) listener()
  }

  get listenerCount() {
    return this.listeners.size
  }
}

class ManualScheduler implements RefreshScheduler {
  private now = 0
  private nextId = 1
  private tasks = new Map<number, { callback: () => void; dueAt: number; intervalMs?: number }>()

  setTimeout(callback: () => void, delayMs: number) {
    return this.add(callback, delayMs)
  }

  clearTimeout(handle: unknown) {
    this.tasks.delete(Number(handle))
  }

  setInterval(callback: () => void, delayMs: number) {
    return this.add(callback, delayMs, delayMs)
  }

  clearInterval(handle: unknown) {
    this.tasks.delete(Number(handle))
  }

  advanceBy(delayMs: number) {
    const target = this.now + delayMs
    while (true) {
      const next = [...this.tasks.entries()]
        .filter(([, task]) => task.dueAt <= target)
        .sort((left, right) => left[1].dueAt - right[1].dueAt || left[0] - right[0])[0]
      if (!next) break

      const [id, task] = next
      this.now = task.dueAt
      if (task.intervalMs === undefined) this.tasks.delete(id)
      else task.dueAt += task.intervalMs
      task.callback()
    }
    this.now = target
  }

  get pendingCount() {
    return this.tasks.size
  }

  private add(callback: () => void, delayMs: number, intervalMs?: number) {
    const id = this.nextId++
    this.tasks.set(id, { callback, dueAt: this.now + delayMs, intervalMs })
    return id
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
}
