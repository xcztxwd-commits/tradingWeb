import type { TradingLabScenario } from '../model/types.ts'
import type { TradingLabDraftStore } from './draftStore.ts'

export interface TradingLabAutosaveTimers {
  setTimeout(callback: () => void, delayMs: number): unknown
  clearTimeout(handle: unknown): void
}

export interface TradingLabDraftAutosave {
  schedule(scenario: TradingLabScenario): void
  flush(): Promise<void>
  cancel(): void
}

const DEFAULT_DELAY_MS = 500

const DEFAULT_TIMERS: TradingLabAutosaveTimers = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => {
    globalThis.clearTimeout(
      handle as ReturnType<typeof globalThis.setTimeout>,
    )
  },
}

export function createTradingLabDraftAutosave(
  store: TradingLabDraftStore,
  options?: Readonly<{
    delayMs?: number
    timers?: TradingLabAutosaveTimers
  }>,
): TradingLabDraftAutosave {
  const delayMs = options?.delayMs ?? DEFAULT_DELAY_MS
  if (!Number.isSafeInteger(delayMs) || delayMs < 0) {
    throw new RangeError('delayMs must be a nonnegative safe integer')
  }

  const timers = options?.timers ?? DEFAULT_TIMERS
  let pendingScenario: TradingLabScenario | undefined
  let timerHandle: unknown
  let timerScheduled = false
  let writeQueue = Promise.resolve()
  let storedFailure: unknown
  let hasStoredFailure = false

  const clearPendingTimer = (): void => {
    if (!timerScheduled) {
      return
    }

    timers.clearTimeout(timerHandle)
    timerScheduled = false
  }

  const enqueue = (scenario: TradingLabScenario): void => {
    writeQueue = writeQueue.then(async () => {
      try {
        await store.put(scenario)
      } catch (error) {
        if (!hasStoredFailure) {
          storedFailure = error
          hasStoredFailure = true
        }
      }
    })
  }

  const firePending = (): void => {
    timerScheduled = false
    const scenario = pendingScenario
    pendingScenario = undefined
    if (scenario !== undefined) {
      enqueue(scenario)
    }
  }

  return {
    schedule(scenario) {
      clearPendingTimer()
      pendingScenario = scenario
      timerHandle = timers.setTimeout(firePending, delayMs)
      timerScheduled = true
    },

    async flush() {
      clearPendingTimer()
      const scenario = pendingScenario
      pendingScenario = undefined
      if (scenario !== undefined) {
        enqueue(scenario)
      }

      await writeQueue
      if (hasStoredFailure) {
        const failure = storedFailure
        storedFailure = undefined
        hasStoredFailure = false
        throw failure
      }
    },

    cancel() {
      clearPendingTimer()
      pendingScenario = undefined
    },
  }
}
