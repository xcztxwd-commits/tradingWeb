import type { TradingLabScenario } from '../model/types.ts'
import {
  TradingLabRunChartEvidenceError,
  createTradingLabRunChartEvidence,
  reduceTradingLabRunChartEvidence,
  type TradingLabRunChartEvidence,
} from '../chart/runChartEvidence.ts'
import {
  createTradingLabActualStateUnavailable,
  projectTradingLabActualState,
  type TradingLabActualStateResult,
} from './tradingLabActualState.ts'

const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u
const CANONICAL_DURABLE_ID = /^(?:0|[1-9]\d*)$/u
const UTC_Z_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/u
const MAX_DURABLE_ID = 9_223_372_036_854_775_807n
const MAX_EVIDENCE = 200
const MAX_SUMMARY_TEXT = 240

const MAIN_RUN_STATES = new Set([
  'DRAFT',
  'VALIDATING',
  'QUEUED',
  'RESETTING',
  'RUNNING',
  'PAUSED',
  'CANCELLING',
  'CANCELLED',
  'FAILED',
  'COMPLETED',
  'CLEANING',
])

const TERMINAL_STATES = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
])

const DURABLE_EVENT_NAMES = new Set([
  'state',
  'progress',
  'tick',
  'checkpoint',
  'api-trace',
  'warning',
  'error',
])

export type TradingLabRunSessionConnection =
  | 'IDLE'
  | 'CONNECTING'
  | 'OPEN'
  | 'RETRY_WAIT'
  | 'FINAL_REFRESH_REQUIRED'
  | 'BLOCKED'
  | 'CLOSED'

/**
 * The durable session deliberately retains only the authoritative run fields
 * required by controls, progress, and terminal report discovery. A guarded
 * API DTO with additional fields is structurally assignable to this interface.
 */
export type TradingLabRunSessionRun = Readonly<{
  id: string
  scenarioId: string
  reportId: string | null
  state: string
  totalTicks: number
  pauseRequested: boolean
  cancelRequested: boolean
  version: number
}>

export type TradingLabStreamSessionEventName =
  | 'state'
  | 'progress'
  | 'tick'
  | 'checkpoint'
  | 'api-trace'
  | 'warning'
  | 'error'
  | 'complete'

export type TradingLabStreamSessionEvent = Readonly<{
  id: string | null
  name: TradingLabStreamSessionEventName
  data: unknown
}>

export type TradingLabPublicEvent = Readonly<{
  id: string
  name: Exclude<TradingLabStreamSessionEventName, 'complete'>
  virtualTime: string | null
  correlationId: string | null
  kind: string | null
  state: string | null
  reason: string | null
  tickSequence: number | null
  highWatermark: number | null
  phase: string | null
  method: string | null
  operation: string | null
  outcome: string | null
  httpStatus: number | null
  code: string | null
  message: string | null
}>

export type TradingLabRunSessionIssue = Readonly<{
  code: string
  message: string
  retryable: boolean
}>

export type TradingLabRunSession = Readonly<{
  runId: string
  ownerGeneration: number
  run: TradingLabRunSessionRun | null
  scenario: TradingLabScenario | null
  validationState: string | null
  highestDurableEventId: string | null
  highestObservedTick: number | null
  highestCompletedCheckpoint: number | null
  lastVirtualTime: string | null
  connection: TradingLabRunSessionConnection
  evidence: readonly TradingLabPublicEvent[]
  chartEvidence: TradingLabRunChartEvidence
  actualState: TradingLabActualStateResult
  pendingTerminalState: 'COMPLETED' | 'FAILED' | 'CANCELLED' | null
  transportIssue: TradingLabRunSessionIssue | null
  productErrorCount: number
}>

type ScopedAction = Readonly<{
  ownerGeneration: number
  runId: string
}>

export type TradingLabRunSessionAction = ScopedAction & (
  | Readonly<{
      type: 'ATTACH_RUN'
      run: TradingLabRunSessionRun
    }>
  | Readonly<{
      type: 'RESTORE_SCENARIO'
      scenario: TradingLabScenario
    }>
  | Readonly<{ type: 'STREAM_CONNECTING' }>
  | Readonly<{ type: 'STREAM_OPEN' }>
  | Readonly<{
      type: 'STREAM_EVENT'
      event: TradingLabStreamSessionEvent
    }>
  | Readonly<{
      type: 'STREAM_RETRY_WAIT'
      issue: Readonly<{ code: string; message: string }>
    }>
  | Readonly<{
      type: 'STREAM_BLOCKED'
      issue: Readonly<{ code: string; message: string }>
    }>
  | Readonly<{
      type: 'FINAL_RUN_REFRESH'
      run: TradingLabRunSessionRun
    }>
)

export function createTradingLabRunSession(
  runId: string,
  ownerGeneration: number,
): TradingLabRunSession {
  if (!CANONICAL_UUID.test(runId)) {
    throw new TypeError('Trading Lab session runId must be a canonical lowercase UUID')
  }
  if (
    !Number.isSafeInteger(ownerGeneration)
    || ownerGeneration < 0
  ) {
    throw new RangeError(
      'Trading Lab session owner generation must be a nonnegative safe integer',
    )
  }
  return {
    runId,
    ownerGeneration,
    run: null,
    scenario: null,
    validationState: null,
    highestDurableEventId: null,
    highestObservedTick: null,
    highestCompletedCheckpoint: null,
    lastVirtualTime: null,
    connection: 'IDLE',
    evidence: [],
    chartEvidence: createTradingLabRunChartEvidence(),
    actualState: createTradingLabActualStateUnavailable('NO_CHECKPOINT'),
    pendingTerminalState: null,
    transportIssue: null,
    productErrorCount: 0,
  }
}

export function reduceTradingLabRunSession(
  session: TradingLabRunSession,
  action: TradingLabRunSessionAction,
): TradingLabRunSession {
  if (
    action.ownerGeneration !== session.ownerGeneration
    || action.runId !== session.runId
  ) {
    return session
  }

  switch (action.type) {
    case 'ATTACH_RUN':
      return attachRun(session, action.run)
    case 'RESTORE_SCENARIO':
      return restoreScenario(session, action.scenario)
    case 'STREAM_CONNECTING':
      return changeConnection(session, 'CONNECTING')
    case 'STREAM_OPEN':
      return changeConnection(session, 'OPEN')
    case 'STREAM_EVENT':
      return applyStreamEvent(session, action.event)
    case 'STREAM_RETRY_WAIT':
      return transportState(session, 'RETRY_WAIT', action.issue, true)
    case 'STREAM_BLOCKED':
      return transportState(session, 'BLOCKED', action.issue, false)
    case 'FINAL_RUN_REFRESH':
      return finalRunRefresh(session, action.run)
  }
}

function attachRun(
  session: TradingLabRunSession,
  candidate: TradingLabRunSessionRun,
): TradingLabRunSession {
  const run = boundedRun(candidate, session.runId)
  if (run === null) {
    return block(
      session,
      'TRADING_LAB_RUN_MALFORMED',
      'Authoritative Trading Lab run response is malformed',
    )
  }
  if (session.run !== null && run.version < session.run.version) {
    return session
  }
  if (
    session.run !== null
    && run.version === session.run.version
    && session.run.reportId === null
    && run.reportId !== null
  ) {
    return session
  }
  return {
    ...session,
    run,
  }
}

function restoreScenario(
  session: TradingLabRunSession,
  scenario: TradingLabScenario,
): TradingLabRunSession {
  if (
    !isRecord(scenario)
    || typeof scenario.id !== 'string'
    || scenario.id.trim().length === 0
  ) {
    return block(
      session,
      'TRADING_LAB_SCENARIO_MALFORMED',
      'Frozen Trading Lab scenario is malformed',
    )
  }
  return {
    ...session,
    scenario,
  }
}

function changeConnection(
  session: TradingLabRunSession,
  connection: 'CONNECTING' | 'OPEN',
): TradingLabRunSession {
  if (
    session.connection === 'CLOSED'
    || session.connection === 'BLOCKED'
    || session.connection === 'FINAL_REFRESH_REQUIRED'
  ) {
    return session
  }
  return {
    ...session,
    connection,
    transportIssue: null,
  }
}

function transportState(
  session: TradingLabRunSession,
  connection: 'RETRY_WAIT' | 'BLOCKED',
  issue: Readonly<{ code: string; message: string }>,
  retryable: boolean,
): TradingLabRunSession {
  if (
    session.connection === 'CLOSED'
    || session.connection === 'BLOCKED'
    || (
      session.connection === 'FINAL_REFRESH_REQUIRED'
      && connection !== 'BLOCKED'
    )
  ) {
    return session
  }
  return {
    ...session,
    connection,
    transportIssue: boundedIssue(issue, retryable),
  }
}

function applyStreamEvent(
  session: TradingLabRunSession,
  event: TradingLabStreamSessionEvent,
): TradingLabRunSession {
  if (session.connection === 'CLOSED' || session.connection === 'BLOCKED') {
    return session
  }
  if (event.name === 'complete') {
    return applyComplete(session, event)
  }
  if (session.connection === 'FINAL_REFRESH_REQUIRED') {
    return block(
      session,
      'TRADING_LAB_EVENT_AFTER_COMPLETE',
      'A durable event arrived after the terminal control frame',
    )
  }
  if (!DURABLE_EVENT_NAMES.has(event.name)) {
    return malformedEvent(session)
  }
  const id = durableId(event.id)
  if (id === null) {
    return block(
      session,
      'TRADING_LAB_DURABLE_EVENT_ID_INVALID',
      'Trading Lab durable event ID is invalid',
    )
  }
  const expected = session.highestDurableEventId === null
    ? 0n
    : BigInt(session.highestDurableEventId) + 1n
  if (id.value !== expected) {
    return block(
      session,
      'TRADING_LAB_DURABLE_EVENT_GAP',
      'Trading Lab durable events are not contiguous',
    )
  }

  const summary = projectEvent(id.text, event.name, event.data)
  if (summary === null) {
    const malformed = malformedEvent(session)
    return event.name === 'checkpoint'
      ? {
          ...malformed,
          actualState: projectTradingLabActualState({
            id: id.text,
            name: event.name,
            data: event.data,
          }),
        }
      : malformed
  }
  const evidence = [
    ...session.evidence,
    summary,
  ].slice(-MAX_EVIDENCE)
  let chartEvidence: TradingLabRunChartEvidence
  try {
    chartEvidence = reduceTradingLabRunChartEvidence(
      session.chartEvidence,
      {
        id: id.text,
        name: event.name,
        data: event.data,
      },
    )
  } catch (error) {
    return block(
      session,
      error instanceof TradingLabRunChartEvidenceError
        ? error.code
        : 'TRADING_LAB_CHART_EVIDENCE_INVALID',
      error instanceof Error
        ? error.message.slice(0, MAX_SUMMARY_TEXT)
        : 'Durable Trading Lab chart evidence is invalid',
    )
  }
  const observedTick = event.name === 'tick'
    ? maximum(session.highestObservedTick, summary.tickSequence)
    : session.highestObservedTick
  const completedCheckpoint = event.name === 'checkpoint'
    ? maximum(session.highestCompletedCheckpoint, summary.tickSequence)
    : session.highestCompletedCheckpoint
  const actualState = event.name === 'checkpoint'
    ? projectTradingLabActualState({
        id: id.text,
        name: event.name,
        data: event.data,
      })
    : session.actualState

  return {
    ...session,
    validationState: event.name === 'state'
      ? summary.state ?? session.validationState
      : session.validationState,
    highestDurableEventId: id.text,
    highestObservedTick: observedTick,
    highestCompletedCheckpoint: completedCheckpoint,
    lastVirtualTime: laterInstant(
      session.lastVirtualTime,
      summary.virtualTime,
    ),
    connection: 'OPEN',
    evidence,
    chartEvidence,
    actualState,
    transportIssue: null,
    productErrorCount: event.name === 'error'
      ? session.productErrorCount + 1
      : session.productErrorCount,
  }
}

function applyComplete(
  session: TradingLabRunSession,
  event: TradingLabStreamSessionEvent,
): TradingLabRunSession {
  if (
    event.id !== null
    || !isRecord(event.data)
    || !sameKeys(event.data, ['state'])
    || !terminalState(event.data.state)
  ) {
    return malformedEvent(session)
  }
  return {
    ...session,
    connection: 'FINAL_REFRESH_REQUIRED',
    pendingTerminalState: event.data.state,
    transportIssue: null,
  }
}

function finalRunRefresh(
  session: TradingLabRunSession,
  candidate: TradingLabRunSessionRun,
): TradingLabRunSession {
  const bounded = boundedRun(candidate, session.runId)
  if (bounded === null) {
    return block(
      session,
      'TRADING_LAB_RUN_MALFORMED',
      'Final Trading Lab run response is malformed',
    )
  }
  const run = (
    session.run !== null
    && bounded.version === session.run.version
    && session.run.reportId === null
    && bounded.reportId !== null
  )
    ? { ...bounded, reportId: null }
    : bounded
  if (session.run !== null && run.version < session.run.version) {
    return block(
      session,
      'TRADING_LAB_RUN_VERSION_REGRESSION',
      'Final Trading Lab run response is older than the current run',
    )
  }
  if (
    session.connection !== 'FINAL_REFRESH_REQUIRED'
    || session.pendingTerminalState === null
    || run.state !== session.pendingTerminalState
    || !TERMINAL_STATES.has(run.state)
  ) {
    return block(
      session,
      'TRADING_LAB_FINAL_STATE_MISMATCH',
      'Final Trading Lab run state does not match durable completion',
    )
  }
  return {
    ...session,
    run,
    connection: 'CLOSED',
    pendingTerminalState: null,
    transportIssue: null,
  }
}

function projectEvent(
  id: string,
  name: Exclude<TradingLabStreamSessionEventName, 'complete'>,
  data: unknown,
): TradingLabPublicEvent | null {
  if (!isRecord(data)) {
    return null
  }
  const payload = isRecord(data.payload) ? data.payload : null
  const virtualTime = optionalInstant(data.virtualTime)
  if (
    data.virtualTime !== undefined
    && data.virtualTime !== null
    && virtualTime === null
  ) {
    return null
  }
  const tickSequence = positiveSafeInteger(payload?.tickSequence)

  if (
    (name === 'tick' || name === 'checkpoint')
    && (tickSequence === null || virtualTime === null)
  ) {
    return null
  }
  const state = name === 'state'
    ? boundedText(payload?.state)
    : null
  if (
    name === 'state'
    && (
      payload === null
      || (
        Object.prototype.hasOwnProperty.call(payload, 'state')
        && state === null
      )
    )
  ) {
    return null
  }

  return {
    id,
    name,
    virtualTime,
    correlationId: boundedText(data.correlationId),
    kind: boundedText(data.kind),
    state,
    reason: boundedText(payload?.reason),
    tickSequence,
    highWatermark: nonnegativeSafeInteger(data.highWatermark),
    phase: boundedText(data.phase),
    method: boundedText(data.method),
    operation: boundedText(payload?.operation),
    outcome: boundedText(payload?.outcome),
    httpStatus: httpStatus(data.status)
      ?? httpStatus(payload?.status),
    code: boundedText(payload?.code),
    message: boundedText(payload?.message),
  }
}

function boundedRun(
  value: TradingLabRunSessionRun,
  expectedRunId: string,
): TradingLabRunSessionRun | null {
  if (
    !isRecord(value)
    || value.id !== expectedRunId
    || !CANONICAL_UUID.test(value.id)
    || typeof value.scenarioId !== 'string'
    || !CANONICAL_UUID.test(value.scenarioId)
    || (
      value.reportId !== null
      && (
        typeof value.reportId !== 'string'
        || !CANONICAL_UUID.test(value.reportId)
      )
    )
    || typeof value.state !== 'string'
    || !MAIN_RUN_STATES.has(value.state)
    || !Number.isSafeInteger(value.totalTicks)
    || value.totalTicks < 0
    || typeof value.pauseRequested !== 'boolean'
    || typeof value.cancelRequested !== 'boolean'
    || !Number.isSafeInteger(value.version)
    || value.version < 0
  ) {
    return null
  }
  return {
    id: value.id,
    scenarioId: value.scenarioId,
    reportId: value.reportId,
    state: value.state,
    totalTicks: value.totalTicks,
    pauseRequested: value.pauseRequested,
    cancelRequested: value.cancelRequested,
    version: value.version,
  }
}

function durableId(
  value: string | null,
): Readonly<{ text: string; value: bigint }> | null {
  if (
    value === null
    || !CANONICAL_DURABLE_ID.test(value)
  ) {
    return null
  }
  const parsed = BigInt(value)
  if (parsed > MAX_DURABLE_ID) {
    return null
  }
  return { text: value, value: parsed }
}

function boundedIssue(
  issue: Readonly<{ code: string; message: string }>,
  retryable: boolean,
): TradingLabRunSessionIssue {
  return {
    code: boundedText(issue.code) ?? 'TRADING_LAB_STREAM_ERROR',
    message: boundedText(issue.message) ?? 'Trading Lab stream failed',
    retryable,
  }
}

function block(
  session: TradingLabRunSession,
  code: string,
  message: string,
): TradingLabRunSession {
  return {
    ...session,
    connection: 'BLOCKED',
    transportIssue: {
      code,
      message,
      retryable: false,
    },
  }
}

function malformedEvent(
  session: TradingLabRunSession,
): TradingLabRunSession {
  return block(
    session,
    'TRADING_LAB_EVENT_MALFORMED',
    'Trading Lab public event is malformed',
  )
}

function maximum(
  current: number | null,
  candidate: number | null,
): number | null {
  if (candidate === null) {
    return current
  }
  return current === null ? candidate : Math.max(current, candidate)
}

function laterInstant(
  current: string | null,
  candidate: string | null,
): string | null {
  if (candidate === null) {
    return current
  }
  if (current === null) {
    return candidate
  }
  return Date.parse(candidate) >= Date.parse(current) ? candidate : current
}

function optionalInstant(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    return null
  }
  const match = value.match(UTC_Z_INSTANT)
  if (match === null) {
    return null
  }
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) {
    return null
  }
  const fraction = (match[7] ?? '').padEnd(3, '0') || '000'
  const canonical = value.replace(
    /(?:\.\d{1,3})?Z$/u,
    `.${fraction}Z`,
  )
  return new Date(milliseconds).toISOString() === canonical ? value : null
}

function positiveSafeInteger(value: unknown): number | null {
  return (
    typeof value === 'number'
    && Number.isSafeInteger(value)
    && value > 0
  )
    ? value
    : null
}

function nonnegativeSafeInteger(value: unknown): number | null {
  return (
    typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= 0
  )
    ? value
    : null
}

function httpStatus(value: unknown): number | null {
  return (
    typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= 100
    && value <= 599
  )
    ? value
    : null
}

function boundedText(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const normalized = value.trim()
  return normalized.length === 0
    ? null
    : normalized.slice(0, MAX_SUMMARY_TEXT)
}

function terminalState(
  value: unknown,
): value is 'COMPLETED' | 'FAILED' | 'CANCELLED' {
  return typeof value === 'string' && TERMINAL_STATES.has(value)
}

function sameKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value)
  return (
    keys.length === expected.length
    && keys.every((key) => expected.includes(key))
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
