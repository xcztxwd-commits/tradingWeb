import type { TradingLabScenario } from '../model/types.ts'
import { MAX_VISIBLE_TICK_LIMIT } from '../chart/visibleTickWindow.ts'
import {
  calculateLocalExpectedScenario,
  calculateLocalExpectedWorkspace,
} from './calculateLocalExpectedScenario.ts'

type CalculationOutcome = ReturnType<typeof calculateLocalExpectedScenario>
type LocalCalculator = (
  scenario: TradingLabScenario,
) => CalculationOutcome
type WorkspaceCalculation = ReturnType<typeof calculateLocalExpectedWorkspace>
type LocalWorkspaceCalculator = (
  scenario: TradingLabScenario,
) => WorkspaceCalculation

export type LocalOracleWorkerRequest =
  | Readonly<{
      type: 'CALCULATE'
      generation: number
      scenario: TradingLabScenario
    }>
  | Readonly<{
      type: 'CALCULATE_WORKSPACE'
      generation: number
      scenario: TradingLabScenario
    }>

export type LocalOracleWorkerResponse =
  | Readonly<{
      type: 'RESULT'
      generation: number
      outcome: CalculationOutcome
    }>
  | Readonly<{
      type: 'WORKSPACE_RESULT'
      generation: number
      outcome: CalculationOutcome
      localTickWindow: WorkspaceCalculation['localTickWindow']
    }>
  | Readonly<{
      type: 'FAILED'
      generation: number
      message: string
    }>

const MAX_MESSAGE_LENGTH = 240

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function boundedMessage(value: unknown, fallback: string): string {
  const raw = value instanceof Error
    ? value.message
    : typeof value === 'string'
      ? value
      : fallback
  const plain = raw.replace(/^Error:\s*/u, '').trim()
  return (plain.length === 0 ? fallback : plain).slice(0, MAX_MESSAGE_LENGTH)
}

function requestGeneration(value: unknown): number {
  if (
    isRecord(value)
    && Number.isSafeInteger(value.generation)
    && (value.generation as number) > 0
  ) {
    return value.generation as number
  }
  return 0
}

function isRequest(value: unknown): value is LocalOracleWorkerRequest {
  return (
    isRecord(value)
    && (
      value.type === 'CALCULATE'
      || value.type === 'CALCULATE_WORKSPACE'
    )
    && Number.isSafeInteger(value.generation)
    && (value.generation as number) > 0
    && isRecord(value.scenario)
  )
}

export function handleLocalOracleWorkerRequest(
  value: unknown,
  calculate: LocalCalculator = calculateLocalExpectedScenario,
  calculateWorkspace: LocalWorkspaceCalculator = calculateLocalExpectedWorkspace,
): LocalOracleWorkerResponse {
  const generation = requestGeneration(value)
  if (!isRequest(value)) {
    return {
      type: 'FAILED',
      generation,
      message: '本地 Oracle Worker 请求无效',
    }
  }
  try {
    if (value.type === 'CALCULATE_WORKSPACE') {
      const calculation = calculateWorkspace(value.scenario)
      if (
        !Array.isArray(calculation.localTickWindow.ticks)
        || calculation.localTickWindow.ticks.length > MAX_VISIBLE_TICK_LIMIT
        || !Number.isSafeInteger(calculation.localTickWindow.limit)
        || calculation.localTickWindow.limit <= 0
        || calculation.localTickWindow.limit > MAX_VISIBLE_TICK_LIMIT
        || calculation.localTickWindow.ticks.length
          > calculation.localTickWindow.limit
      ) {
        throw new Error('本地 Tick 可见窗口超出 Worker 传输上限')
      }
      return {
        type: 'WORKSPACE_RESULT',
        generation,
        outcome: calculation.outcome,
        localTickWindow: calculation.localTickWindow,
      }
    }
    return {
      type: 'RESULT',
      generation,
      outcome: calculate(value.scenario),
    }
  } catch (error) {
    return {
      type: 'FAILED',
      generation,
      message: boundedMessage(error, '本地 Oracle Worker 计算失败'),
    }
  }
}

type WorkerScope = {
  document?: unknown
  addEventListener?: (
    type: 'message',
    listener: (event: MessageEvent<unknown>) => void,
  ) => void
  postMessage?: (message: LocalOracleWorkerResponse) => void
}

const workerScope = globalThis as unknown as WorkerScope
if (
  workerScope.document === undefined
  && typeof workerScope.addEventListener === 'function'
  && typeof workerScope.postMessage === 'function'
) {
  workerScope.addEventListener('message', (event) => {
    workerScope.postMessage?.(handleLocalOracleWorkerRequest(event.data))
  })
}
