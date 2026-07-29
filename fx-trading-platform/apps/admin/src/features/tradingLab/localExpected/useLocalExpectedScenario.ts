import {
  useEffect,
  useLayoutEffect,
  useRef,
  useSyncExternalStore,
} from 'react'

import type { TradingLabScenario } from '../model/types.ts'
import {
  createLocalOracleScheduler,
  type LocalExpectedState,
  type LocalOracleScheduler,
} from './localOracleScheduler.ts'

export function useLocalExpectedScenario(
  scenario: TradingLabScenario | null,
): LocalExpectedState {
  const schedulerRef = useRef<LocalOracleScheduler | null>(null)
  const lifecycleRef = useRef(0)
  if (schedulerRef.current === null) {
    schedulerRef.current = createLocalOracleScheduler()
  }
  const scheduler = schedulerRef.current
  const state = useSyncExternalStore(
    scheduler.subscribe,
    scheduler.getSnapshot,
    scheduler.getSnapshot,
  )

  useLayoutEffect(() => {
    scheduler.schedule(scenario)
  }, [scenario, scheduler])

  useEffect(() => {
    lifecycleRef.current += 1
    const lifecycle = lifecycleRef.current
    return () => {
      queueMicrotask(() => {
        if (lifecycleRef.current === lifecycle) {
          scheduler.dispose()
        }
      })
    }
  }, [scheduler])

  return state
}
