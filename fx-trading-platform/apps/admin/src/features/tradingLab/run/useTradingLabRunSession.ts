import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react'

import { getValidAdminToken } from '../../../services/adminToken.ts'
import {
  controlTradingLabRun,
  createTradingLabRun,
  createTradingLabScenario,
  getTradingLabRun,
  getTradingLabScenario,
  type TradingLabRunControlAction,
} from '../api/tradingLabApi.ts'
import { streamTradingLabRun } from '../api/tradingLabStream.ts'
import { normalizeTradingLabScenarioDocument } from '../drafts/scenarioDocument.ts'
import {
  createTradingLabRunSessionController,
  type TradingLabRunRequest,
  type TradingLabRunRequestState,
  type TradingLabRunSessionControllerDependencies,
} from './tradingLabRunSessionController.ts'
import type {
  TradingLabRunSession,
  TradingLabRunSessionRun,
} from './tradingLabRunSession.ts'
import {
  readTradingLabRunLocation,
  writeTradingLabRunLocation,
} from './tradingLabRunUrl.ts'

export type {
  TradingLabRunRequest,
  TradingLabRunRequestState,
} from './tradingLabRunSessionController.ts'

export type UseTradingLabRunSessionOptions = Readonly<{
  canView: boolean
  canExecute: boolean
}>

export type UseTradingLabRunSessionResult = Readonly<{
  runId: string | null
  locationError: string | null
  session: TradingLabRunSession | null
  runRequestState: TradingLabRunRequestState
  controlPending: TradingLabRunControlAction | null
  message: string | null
  requestRun(request: TradingLabRunRequest): Promise<void>
  controlRun(action: TradingLabRunControlAction): Promise<void>
  acceptAuthoritativeRun(run: TradingLabRunSessionRun): void
}>

const browserDependencies: TradingLabRunSessionControllerDependencies = {
  getToken: getValidAdminToken,
  getRun: getTradingLabRun,
  getScenario: getTradingLabScenario,
  createScenario: createTradingLabScenario,
  createRun: createTradingLabRun,
  controlRun: controlTradingLabRun,
  streamRun: streamTradingLabRun,
  normalizeScenario: normalizeTradingLabScenarioDocument,
  writeRunLocation(runId) {
    if (typeof window === 'undefined') {
      throw new Error('Trading Lab Run location requires a browser window')
    }
    const nextUrl = writeTradingLabRunLocation(window.location.href, runId)
    window.history.pushState(window.history.state, '', nextUrl)
    return readTradingLabRunLocation(window.location.search)
  },
  setTimer: (callback, milliseconds) =>
    globalThis.setTimeout(callback, milliseconds),
  clearTimer: (handle) => {
    globalThis.clearTimeout(
      handle as ReturnType<typeof globalThis.setTimeout>,
    )
  },
  enqueueMicrotask: (callback) => {
    globalThis.queueMicrotask(callback)
  },
}

export function useTradingLabRunSession({
  canView,
  canExecute,
}: UseTradingLabRunSessionOptions): UseTradingLabRunSessionResult {
  const [controller] = useState(() =>
    createTradingLabRunSessionController(browserDependencies),
  )
  const lifecycleGenerationRef = useRef(0)
  const snapshot = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
    controller.getSnapshot,
  )

  useEffect(() => {
    controller.setAccess({ canView, canExecute })
  }, [
    canExecute,
    canView,
    controller,
  ])

  useEffect(() => {
    controller.setLocation(currentRunLocation())

    const handlePopstate = () => {
      controller.setLocation(currentRunLocation())
    }
    if (typeof window !== 'undefined') {
      window.addEventListener('popstate', handlePopstate)
    }

    return () => {
      if (typeof window !== 'undefined') {
        window.removeEventListener('popstate', handlePopstate)
      }
    }
  }, [
    controller,
  ])

  useEffect(() => {
    lifecycleGenerationRef.current += 1

    return () => {
      controller.setAccess({
        canView: false,
        canExecute: false,
      })
      lifecycleGenerationRef.current += 1
      const cleanupGeneration = lifecycleGenerationRef.current
      globalThis.queueMicrotask(() => {
        if (lifecycleGenerationRef.current === cleanupGeneration) {
          controller.dispose()
        }
      })
    }
  }, [
    controller,
  ])

  const requestRun = useCallback(
    (request: TradingLabRunRequest) => controller.requestRun(request),
    [controller],
  )
  const controlRun = useCallback(
    (action: TradingLabRunControlAction) => controller.controlRun(action),
    [controller],
  )
  const acceptAuthoritativeRun = useCallback(
    (run: TradingLabRunSessionRun) => controller.acceptAuthoritativeRun(run),
    [controller],
  )

  return {
    runId: snapshot.runId,
    locationError: snapshot.locationError,
    session: snapshot.session,
    runRequestState: snapshot.runRequestState,
    controlPending: snapshot.controlPending,
    message: snapshot.session?.transportIssue?.message ?? snapshot.message,
    requestRun,
    controlRun,
    acceptAuthoritativeRun,
  }
}

function currentRunLocation() {
  return typeof window === 'undefined'
    ? { runId: null, error: null }
    : readTradingLabRunLocation(window.location.search)
}
