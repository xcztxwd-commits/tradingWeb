export type RefreshScheduler = {
  setTimeout(callback: () => void, delayMs: number): unknown
  clearTimeout(handle: unknown): void
  setInterval(callback: () => void, delayMs: number): unknown
  clearInterval(handle: unknown): void
}

export type RefreshVisibilitySource = {
  isVisible(): boolean
  subscribe(listener: () => void): () => void
}

type AccountRefreshCoordinatorOptions = {
  refresh: () => Promise<void>
  onError?: (error: unknown) => void
  coalesceMs?: number
  pollMs?: number
  scheduler?: RefreshScheduler
  visibility?: RefreshVisibilitySource
}

export type AccountRefreshCoordinator = {
  start(): void
  notifyEvent(): void
  notifyReconnect(): void
  refreshNow(): Promise<void>
  dispose(): void
}

type RefreshWaiter = {
  resolve(): void
  reject(error: unknown): void
}

type RefreshGateJob<T> = {
  version: number
  load(): Promise<T>
  apply(value: T): void
  waiters: RefreshWaiter[]
}

export type LatestSingleFlightRefreshGate<T> = {
  request(load: () => Promise<T>, apply: (value: T) => void): Promise<void>
  invalidate(): void
}

const defaultScheduler: RefreshScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
  setInterval: (callback, delayMs) => globalThis.setInterval(callback, delayMs),
  clearInterval: (handle) => globalThis.clearInterval(handle as ReturnType<typeof setInterval>)
}

export function createLatestSingleFlightRefreshGate<T>(): LatestSingleFlightRefreshGate<T> {
  let latestVersion = 0
  let current: RefreshGateJob<T> | undefined
  let pending: RefreshGateJob<T> | undefined

  const settle = (waiters: RefreshWaiter[], error?: unknown) => {
    for (const waiter of waiters) {
      if (error === undefined) waiter.resolve()
      else waiter.reject(error)
    }
  }

  const run = async (job: RefreshGateJob<T>) => {
    current = job
    let result: T | undefined
    let failure: unknown
    try {
      result = await job.load()
    } catch (error) {
      failure = error
    }

    const isLatest = job.version === latestVersion
    if (!isLatest && pending) {
      pending.waiters.unshift(...job.waiters)
    } else if (!isLatest) {
      settle(job.waiters)
    } else if (failure !== undefined) {
      settle(job.waiters, failure)
    } else {
      try {
        job.apply(result as T)
        settle(job.waiters)
      } catch (error) {
        settle(job.waiters, error)
      }
    }

    if (current === job) current = undefined
    const next = pending
    pending = undefined
    if (next) void run(next)
  }

  return {
    request(load, apply) {
      const version = ++latestVersion
      return new Promise<void>((resolve, reject) => {
        const waiter = { resolve, reject }
        const job = { version, load, apply, waiters: [waiter] }
        if (!current) {
          void run(job)
          return
        }

        pending = {
          ...job,
          waiters: [...(pending?.waiters ?? []), waiter]
        }
      })
    },
    invalidate() {
      latestVersion += 1
      if (pending) settle(pending.waiters)
      pending = undefined
    }
  }
}

export function createAccountRefreshCoordinator({
  refresh,
  onError,
  coalesceMs = 200,
  pollMs = 15_000,
  scheduler = defaultScheduler,
  visibility = createDocumentVisibilitySource()
}: AccountRefreshCoordinatorOptions): AccountRefreshCoordinator {
  let disposed = false
  let started = false
  let eventTimer: unknown
  let pollTimer: unknown
  let unsubscribeVisibility: (() => void) | undefined
  let running: Promise<void> | undefined
  let trailingRefresh = false

  const runRefresh = (): Promise<void> => {
    if (disposed) return Promise.resolve()
    if (running) {
      trailingRefresh = true
      return running
    }

    let refreshResult: Promise<void>
    try {
      refreshResult = refresh()
    } catch (error) {
      refreshResult = Promise.reject(error)
    }

    running = Promise.resolve(refreshResult)
      .catch((error) => {
        if (!disposed) onError?.(error)
      })
      .finally(() => {
        running = undefined
        if (!disposed && trailingRefresh) {
          trailingRefresh = false
          void runRefresh()
        }
      })
    return running
  }

  const cancelEventTimer = () => {
    if (eventTimer === undefined) return
    scheduler.clearTimeout(eventTimer)
    eventTimer = undefined
  }

  const refreshNow = () => {
    cancelEventTimer()
    return runRefresh()
  }

  return {
    start() {
      if (started || disposed) return
      started = true
      pollTimer = scheduler.setInterval(() => {
        if (visibility.isVisible()) void runRefresh()
      }, pollMs)
      unsubscribeVisibility = visibility.subscribe(() => {
        if (visibility.isVisible()) void refreshNow()
      })
    },
    notifyEvent() {
      if (disposed || eventTimer !== undefined) return
      eventTimer = scheduler.setTimeout(() => {
        eventTimer = undefined
        void runRefresh()
      }, coalesceMs)
    },
    notifyReconnect() {
      if (!disposed) void refreshNow()
    },
    refreshNow,
    dispose() {
      if (disposed) return
      disposed = true
      trailingRefresh = false
      cancelEventTimer()
      if (pollTimer !== undefined) scheduler.clearInterval(pollTimer)
      pollTimer = undefined
      unsubscribeVisibility?.()
      unsubscribeVisibility = undefined
    }
  }
}

function createDocumentVisibilitySource(): RefreshVisibilitySource {
  if (typeof document === 'undefined') {
    return { isVisible: () => true, subscribe: () => () => undefined }
  }

  return {
    isVisible: () => document.visibilityState === 'visible',
    subscribe(listener) {
      document.addEventListener('visibilitychange', listener)
      return () => document.removeEventListener('visibilitychange', listener)
    }
  }
}
