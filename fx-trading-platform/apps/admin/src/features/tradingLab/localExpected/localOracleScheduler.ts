import type { TradingLabScenario } from '../model/types.ts'
import type {
  MarketTick,
  OracleCalculationOutcome,
} from '../oracle/types.ts'
import {
  DEFAULT_VISIBLE_TICK_LIMIT,
  selectVisibleTickWindow,
  type VisibleTickWindow,
} from '../chart/visibleTickWindow.ts'
import {
  calculateLocalExpectedWorkspace,
  type LocalExpectedWorkspaceCalculation,
} from './calculateLocalExpectedScenario.ts'
import type {
  LocalOracleWorkerRequest,
  LocalOracleWorkerResponse,
} from './localOracleWorker.ts'

export type CalculatedOutcome = Extract<
  OracleCalculationOutcome,
  { status: 'CALCULATED' }
>

export type BlockedOutcome = Extract<
  OracleCalculationOutcome,
  { status: 'BLOCKED' }
>

export type LocalExpectedState =
  | Readonly<{ status: 'IDLE' }>
  | Readonly<{ status: 'CALCULATING'; generation: number }>
  | Readonly<{
      status: 'CALCULATED'
      generation: number
      outcome: CalculatedOutcome
      localTickWindow: VisibleTickWindow
    }>
  | Readonly<{
      status: 'BLOCKED'
      generation: number
      outcome: BlockedOutcome
      localTickWindow: VisibleTickWindow
    }>
  | Readonly<{
      status: 'FAILED'
      generation: number
      message: string
    }>

export interface LocalOracleScheduler {
  schedule(scenario: TradingLabScenario | null): void
  subscribe(listener: () => void): () => void
  getSnapshot(): LocalExpectedState
  dispose(): void
}

export interface LocalOracleWorkerAdapter {
  onmessage: ((event: MessageEvent<unknown>) => void) | null
  onerror: ((event: ErrorEvent) => void) | null
  onmessageerror: ((event: MessageEvent<unknown>) => void) | null
  postMessage(message: unknown): void
  terminate(): void
}

export type LocalOracleSchedulerDependencies = Readonly<{
  calculate: typeof calculateLocalExpectedWorkspace
  createWorker: () => LocalOracleWorkerAdapter
  setTimer: (callback: () => void, delay: number) => unknown
  clearTimer: (handle: unknown) => void
}>

const DEBOUNCE_MILLISECONDS = 300
const MAIN_THREAD_WORK_UNIT_LIMIT = 25_000
const MAX_MESSAGE_LENGTH = 240

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function addSafe(left: number, right: number): number | null {
  const result = left + right
  return Number.isSafeInteger(result) ? result : null
}

function scalarDuration(value: unknown): number | null {
  if (!isRecord(value) || !Array.isArray(value.segments) || value.segments.length === 0) {
    return null
  }
  let duration = 0
  for (const segment of value.segments) {
    if (
      !isRecord(segment)
      || !Number.isSafeInteger(segment.durationSeconds)
      || (segment.durationSeconds as number) <= 0
    ) {
      return null
    }
    const next = addSafe(duration, segment.durationSeconds as number)
    if (next === null) {
      return null
    }
    duration = next
  }
  return duration
}

function sameKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const actual = Object.keys(value).sort()
  const sortedExpected = [...expected].sort()
  return (
    actual.length === sortedExpected.length
    && actual.every((key, index) => key === sortedExpected[index])
  )
}

export function estimateLocalExpectedWorkUnits(
  scenario: TradingLabScenario,
): number | null {
  if (
    !isRecord(scenario)
    || !Array.isArray(scenario.timeline)
    || !isRecord(scenario.marketPath)
    || !Array.isArray(scenario.marketPath.instruments)
    || scenario.marketPath.instruments.length === 0
  ) {
    return null
  }
  const actionUnits = scenario.timeline.length * 10
  if (!Number.isSafeInteger(actionUnits)) {
    return null
  }
  let total = actionUnits

  for (const instrument of scenario.marketPath.instruments) {
    if (!isRecord(instrument)) {
      return null
    }
    if (instrument.mode === 'SIMPLE') {
      const duration = scalarDuration(instrument.last)
      if (duration === null) {
        return null
      }
      const next = addSafe(total, duration)
      if (next === null) {
        return null
      }
      total = next
      continue
    }
    if (instrument.mode !== 'ADVANCED' || !isRecord(instrument.prices)) {
      return null
    }
    const expectedLanes = instrument.productType === 'CRYPTO_SPOT'
      ? ['bid', 'ask', 'last']
      : instrument.productType === 'LINEAR_PERP'
        ? ['bid', 'ask', 'last', 'mark', 'index']
        : null
    if (expectedLanes === null || !sameKeys(instrument.prices, expectedLanes)) {
      return null
    }
    for (const lane of expectedLanes) {
      const duration = scalarDuration(instrument.prices[lane])
      if (duration === null) {
        return null
      }
      const next = addSafe(total, duration)
      if (next === null) {
        return null
      }
      total = next
    }
  }
  return total
}

function createModuleWorker(): LocalOracleWorkerAdapter {
  if (typeof Worker !== 'function') {
    throw new Error('当前浏览器不支持本地 Oracle Worker')
  }
  return new Worker(
    new URL('./localOracleWorker.ts', import.meta.url),
    { type: 'module', name: 'trading-lab-local-oracle' },
  )
}

function defaultSetTimer(callback: () => void, delay: number): unknown {
  return globalThis.setTimeout(callback, delay)
}

function defaultClearTimer(handle: unknown): void {
  globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>)
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

const PLAIN_DECIMAL = /^-?\d+(?:\.\d+)?$/u
const UTC_Z_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/u

function hasExactKeys(
  value: unknown,
  expected: readonly string[],
): value is Record<string, unknown> {
  return isRecord(value) && sameKeys(value, expected)
}

function hasStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  return fields.every((field) => typeof value[field] === 'string')
}

function hasNonEmptyStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  return fields.every((field) => (
    typeof value[field] === 'string'
    && (value[field] as string).trim().length > 0
  ))
}

function hasDecimalFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  return fields.every((field) => (
    typeof value[field] === 'string'
    && PLAIN_DECIMAL.test(value[field] as string)
  ))
}

function isNullableDecimal(value: unknown): boolean {
  return (
    value === null
    || (typeof value === 'string' && PLAIN_DECIMAL.test(value))
  )
}

function isPositiveSafeInteger(value: unknown): boolean {
  return (
    typeof value === 'number'
    && Number.isSafeInteger(value)
    && value > 0
  )
}

function isCanonicalUtcInstant(value: unknown): boolean {
  if (typeof value !== 'string') {
    return false
  }
  const match = value.match(UTC_Z_INSTANT)
  if (match === null) {
    return false
  }
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) {
    return false
  }
  const fraction = (match[7] ?? '').padEnd(3, '0') || '000'
  const canonical = value.replace(
    /(?:\.\d{1,3})?Z$/u,
    `.${fraction}Z`,
  )
  return new Date(milliseconds).toISOString() === canonical
}

function isDenseList(
  value: unknown,
  isItem: (item: unknown) => boolean,
): boolean {
  if (
    !Array.isArray(value)
    || Object.keys(value).length !== value.length
  ) {
    return false
  }
  for (let index = 0; index < value.length; index += 1) {
    if (
      !Object.hasOwn(value, index)
      || !isItem(value[index])
    ) {
      return false
    }
  }
  return true
}

function isProductType(value: unknown): boolean {
  return value === 'CRYPTO_SPOT' || value === 'LINEAR_PERP'
}

function isInstrumentTick(value: unknown): boolean {
  if (!isRecord(value) || !hasNonEmptyStringFields(value, ['symbol'])) {
    return false
  }
  if (value.productType === 'CRYPTO_SPOT') {
    return (
      sameKeys(value, ['productType', 'symbol', 'bid', 'ask', 'last'])
      && hasDecimalFields(value, ['bid', 'ask', 'last'])
    )
  }
  if (value.productType === 'LINEAR_PERP') {
    return (
      sameKeys(
        value,
        ['productType', 'symbol', 'bid', 'ask', 'last', 'mark', 'index'],
      )
      && hasDecimalFields(value, ['bid', 'ask', 'last', 'mark', 'index'])
    )
  }
  return false
}

function isFundingRateTick(value: unknown): boolean {
  return (
    hasExactKeys(value, ['symbol', 'rate'])
    && hasNonEmptyStringFields(value, ['symbol'])
    && hasDecimalFields(value, ['rate'])
  )
}

function isMarketTick(value: unknown): boolean {
  return (
    hasExactKeys(
      value,
      ['sequence', 'virtualTime', 'instruments', 'fundingRates'],
    )
    && isPositiveSafeInteger(value.sequence)
    && isCanonicalUtcInstant(value.virtualTime)
    && isDenseList(value.instruments, isInstrumentTick)
    && isDenseList(value.fundingRates, isFundingRateTick)
  )
}

function isSide(value: unknown): boolean {
  return value === 'BUY' || value === 'SELL'
}

function isLocalIssue(value: unknown): boolean {
  return (
    hasExactKeys(value, ['path', 'code', 'message'])
    && hasStringFields(value, ['path', 'code', 'message'])
  )
}

function isRunnerIssue(value: unknown): boolean {
  return (
    hasExactKeys(value, ['path', 'code', 'message', 'severity'])
    && hasStringFields(value, ['path', 'code', 'message'])
    && (value.severity === 'ERROR' || value.severity === 'WARNING')
  )
}

function isLocalWarning(value: unknown): boolean {
  return (
    hasExactKeys(value, ['code', 'message'])
    && hasStringFields(value, ['code', 'message'])
  )
}

function isLocalOrder(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'id',
      'actionId',
      'symbol',
      'productType',
      'side',
      'status',
      'quantity',
      'price',
      'feeUsdt',
    ])
    && hasNonEmptyStringFields(value, ['id', 'actionId', 'symbol'])
    && isProductType(value.productType)
    && isSide(value.side)
    && (value.status === 'FILLED' || value.status === 'REJECTED')
    && hasDecimalFields(value, ['quantity', 'price', 'feeUsdt'])
  )
}

function isLocalTrade(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'id',
      'orderId',
      'actionId',
      'symbol',
      'productType',
      'side',
      'quantity',
      'price',
      'notionalUsdt',
      'feeUsdt',
    ])
    && hasNonEmptyStringFields(
      value,
      ['id', 'orderId', 'actionId', 'symbol'],
    )
    && isProductType(value.productType)
    && isSide(value.side)
    && hasDecimalFields(value, [
      'quantity',
      'price',
      'notionalUsdt',
      'feeUsdt',
    ])
  )
}

function isSpotPosition(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'symbol',
      'baseAsset',
      'quoteAsset',
      'status',
      'quantity',
      'averageCost',
      'grossQuoteCost',
      'feeCostUsdt',
      'netInvestedUsdt',
      'breakEvenPrice',
      'realizedGrossPnl',
      'realizedNetPnl',
    ])
    && hasNonEmptyStringFields(
      value,
      ['symbol', 'baseAsset', 'quoteAsset'],
    )
    && (value.status === 'OPEN' || value.status === 'CLOSED')
    && hasDecimalFields(value, [
      'quantity',
      'grossQuoteCost',
      'feeCostUsdt',
      'netInvestedUsdt',
      'realizedGrossPnl',
      'realizedNetPnl',
    ])
    && isNullableDecimal(value.averageCost)
    && isNullableDecimal(value.breakEvenPrice)
  )
}

function isPerpetualPosition(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'symbol',
      'positionSide',
      'direction',
      'marginMode',
      'status',
      'quantity',
      'entryPrice',
      'markPrice',
      'leverage',
      'initialMargin',
      'isolatedMargin',
      'maintenanceMargin',
      'unrealizedGrossPnl',
      'realizedGrossPnl',
      'tradingFeeUsdt',
      'fundingPnlUsdt',
      'liquidationFeeUsdt',
      'realizedNetPnl',
      'estimatedLiquidationPrice',
    ])
    && hasNonEmptyStringFields(value, ['symbol'])
    && (
      value.positionSide === 'BOTH'
      || value.positionSide === 'LONG'
      || value.positionSide === 'SHORT'
    )
    && (value.direction === 'LONG' || value.direction === 'SHORT')
    && (value.marginMode === 'CROSS' || value.marginMode === 'ISOLATED')
    && (
      value.status === 'OPEN'
      || value.status === 'CLOSED'
      || value.status === 'LIQUIDATED'
    )
    && isPositiveSafeInteger(value.leverage)
    && hasDecimalFields(value, [
      'quantity',
      'entryPrice',
      'markPrice',
      'initialMargin',
      'isolatedMargin',
      'maintenanceMargin',
      'unrealizedGrossPnl',
      'realizedGrossPnl',
      'tradingFeeUsdt',
      'fundingPnlUsdt',
      'liquidationFeeUsdt',
      'realizedNetPnl',
    ])
    && isNullableDecimal(value.estimatedLiquidationPrice)
  )
}

function isWallet(value: unknown): boolean {
  return (
    hasExactKeys(value, ['asset', 'available', 'locked', 'total'])
    && hasNonEmptyStringFields(value, ['asset'])
    && hasDecimalFields(value, ['available', 'locked', 'total'])
  )
}

function isAccountSummary(value: unknown): boolean {
  const fields = [
    'totalWalletBalanceUsdt',
    'availableBalanceUsdt',
    'totalUnrealizedPnlUsdt',
    'equityUsdt',
    'totalMaintenanceMarginUsdt',
  ] as const
  return hasExactKeys(value, fields) && hasDecimalFields(value, fields)
}

function isLedgerProjection(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'actionId',
      'asset',
      'type',
      'amount',
      'balanceAfter',
    ])
    && hasNonEmptyStringFields(value, ['actionId', 'asset', 'type'])
    && hasDecimalFields(value, ['amount', 'balanceAfter'])
  )
}

function isRisk(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'equityUsdt',
      'availableBalanceUsdt',
      'maintenanceMarginUsdt',
      'marginRatio',
      'liquidationTriggered',
    ])
    && hasDecimalFields(value, [
      'equityUsdt',
      'availableBalanceUsdt',
      'maintenanceMarginUsdt',
    ])
    && isNullableDecimal(value.marginRatio)
    && typeof value.liquidationTriggered === 'boolean'
  )
}

function isLocalIssueList(value: unknown): boolean {
  return isDenseList(value, isLocalIssue)
}

function isRunnerIssueList(value: unknown): boolean {
  return isDenseList(value, isRunnerIssue)
}

function isConsumableSnapshot(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'actionId',
      'tickSequence',
      'virtualTime',
      'orders',
      'trades',
      'spotPositions',
      'perpetualPositions',
      'wallets',
      'accountSummary',
      'ledgerProjection',
      'risk',
      'warnings',
    ])
    && hasNonEmptyStringFields(value, ['actionId'])
    && isPositiveSafeInteger(value.tickSequence)
    && isCanonicalUtcInstant(value.virtualTime)
    && isDenseList(value.orders, isLocalOrder)
    && isDenseList(value.trades, isLocalTrade)
    && isDenseList(value.spotPositions, isSpotPosition)
    && isDenseList(value.perpetualPositions, isPerpetualPosition)
    && isDenseList(value.wallets, isWallet)
    && isAccountSummary(value.accountSummary)
    && isDenseList(value.ledgerProjection, isLedgerProjection)
    && isRisk(value.risk)
    && isDenseList(value.warnings, isLocalWarning)
  )
}

function isCalculatedResult(value: unknown): boolean {
  return (
    hasExactKeys(value, [
      'modelVersion',
      'configSnapshotHash',
      'assumptions',
      'snapshots',
    ])
    && hasNonEmptyStringFields(value, ['modelVersion'])
    && typeof value.configSnapshotHash === 'string'
    && /^[0-9a-f]{64}$/u.test(value.configSnapshotHash)
    && isDenseList(
      value.assumptions,
      (assumption) => typeof assumption === 'string',
    )
    && isDenseList(value.snapshots, isConsumableSnapshot)
  )
}

function isOutcome(value: unknown): value is OracleCalculationOutcome {
  if (!isRecord(value)) {
    return false
  }
  if (value.status === 'CALCULATED') {
    return (
      sameKeys(value, ['status', 'result', 'runnerIssues'])
      && isCalculatedResult(value.result)
      && isRunnerIssueList(value.runnerIssues)
    )
  }
  if (value.status === 'BLOCKED') {
    return (
      sameKeys(value, ['status', 'issues', 'runnerIssues'])
      && isLocalIssueList(value.issues)
      && isRunnerIssueList(value.runnerIssues)
    )
  }
  return false
}

function isVisibleTickWindow(value: unknown): value is VisibleTickWindow {
  if (
    !hasExactKeys(value, [
      'ticks',
      'limit',
      'totalCount',
      'firstSequence',
      'lastSequence',
      'hasOlder',
      'hasNewer',
      'olderBeforeSequence',
    ])
    || !Array.isArray(value.ticks)
    || !isDenseList(value.ticks, isMarketTick)
    || !Number.isSafeInteger(value.limit)
    || value.limit !== DEFAULT_VISIBLE_TICK_LIMIT
    || value.ticks.length > (value.limit as number)
    || !Number.isSafeInteger(value.totalCount)
    || (value.totalCount as number) < value.ticks.length
    || value.ticks.length !== Math.min(
      value.totalCount as number,
      value.limit as number,
    )
    || typeof value.hasOlder !== 'boolean'
    || typeof value.hasNewer !== 'boolean'
  ) {
    return false
  }
  const candidateTicks = value.ticks
  try {
    const normalized = selectVisibleTickWindow(
      candidateTicks as readonly MarketTick[],
      { limit: value.limit as number },
    )
    if (
      normalized.ticks.length !== candidateTicks.length
      || normalized.ticks.some((tick, index) =>
        tick.sequence !== (
          candidateTicks[index] as MarketTick | undefined
        )?.sequence)
    ) {
      return false
    }
  } catch {
    return false
  }

  const firstSequence = (
    candidateTicks[0] as MarketTick | undefined
  )?.sequence ?? null
  const lastSequence = (
    candidateTicks.at(-1) as MarketTick | undefined
  )?.sequence ?? null
  if (
    value.firstSequence !== firstSequence
    || value.lastSequence !== lastSequence
    || (
      value.hasOlder
        ? firstSequence === null || value.olderBeforeSequence !== firstSequence
        : value.olderBeforeSequence !== null
    )
  ) {
    return false
  }
  const hiddenCount = (value.totalCount as number) - candidateTicks.length
  return hiddenCount === 0
    ? value.hasOlder === false && value.hasNewer === false
    : value.hasOlder === true && value.hasNewer === false
}

type SchedulerWorkerResponse = Extract<
  LocalOracleWorkerResponse,
  { type: 'WORKSPACE_RESULT' | 'FAILED' }
>

function isWorkerResponse(value: unknown): value is SchedulerWorkerResponse {
  if (
    !isRecord(value)
    || !Number.isSafeInteger(value.generation)
    || (value.generation as number) <= 0
  ) {
    return false
  }
  return (
    (
      value.type === 'WORKSPACE_RESULT'
      && sameKeys(
        value,
        ['type', 'generation', 'outcome', 'localTickWindow'],
      )
      && isOutcome(value.outcome)
      && isVisibleTickWindow(value.localTickWindow)
    )
    || (
      value.type === 'FAILED'
      && sameKeys(value, ['type', 'generation', 'message'])
      && typeof value.message === 'string'
    )
  )
}

export function createLocalOracleScheduler(
  overrides: Partial<LocalOracleSchedulerDependencies> = {},
): LocalOracleScheduler {
  const dependencies: LocalOracleSchedulerDependencies = {
    calculate: overrides.calculate ?? calculateLocalExpectedWorkspace,
    createWorker: overrides.createWorker ?? createModuleWorker,
    setTimer: overrides.setTimer ?? defaultSetTimer,
    clearTimer: overrides.clearTimer ?? defaultClearTimer,
  }
  const listeners = new Set<() => void>()
  let state: LocalExpectedState = { status: 'IDLE' }
  let generation = 0
  let timer: unknown = null
  let worker: LocalOracleWorkerAdapter | null = null
  let disposed = false

  const notify = (): void => {
    for (const listener of listeners) {
      listener()
    }
  }

  const update = (next: LocalExpectedState): void => {
    if (disposed) {
      return
    }
    state = next
    notify()
  }

  const clearPendingTimer = (): void => {
    if (timer !== null) {
      dependencies.clearTimer(timer)
      timer = null
    }
  }

  const terminateWorker = (): void => {
    if (worker === null) {
      return
    }
    const current = worker
    worker = null
    current.onmessage = null
    current.onerror = null
    current.onmessageerror = null
    current.terminate()
  }

  const currentGeneration = (candidate: number): boolean => (
    !disposed && generation === candidate
  )

  const complete = (
    candidate: number,
    calculation: LocalExpectedWorkspaceCalculation,
  ): void => {
    if (!currentGeneration(candidate)) {
      return
    }
    if (calculation.outcome.status === 'CALCULATED') {
      update({
        status: 'CALCULATED',
        generation: candidate,
        outcome: calculation.outcome,
        localTickWindow: calculation.localTickWindow,
      })
    } else {
      update({
        status: 'BLOCKED',
        generation: candidate,
        outcome: calculation.outcome,
        localTickWindow: calculation.localTickWindow,
      })
    }
  }

  const fail = (
    candidate: number,
    value: unknown,
    fallback: string,
  ): void => {
    if (!currentGeneration(candidate)) {
      return
    }
    update({
      status: 'FAILED',
      generation: candidate,
      message: boundedMessage(value, fallback),
    })
  }

  const calculateOnMain = (
    candidate: number,
    scenario: TradingLabScenario,
  ): void => {
    if (!currentGeneration(candidate)) {
      return
    }
    try {
      const calculation = dependencies.calculate(scenario)
      complete(candidate, calculation)
    } catch (error) {
      fail(candidate, error, '本地 Oracle 计算失败')
    }
  }

  const calculateInWorker = (
    candidate: number,
    scenario: TradingLabScenario,
  ): void => {
    if (!currentGeneration(candidate)) {
      return
    }
    terminateWorker()
    let nextWorker: LocalOracleWorkerAdapter
    try {
      nextWorker = dependencies.createWorker()
    } catch (error) {
      fail(candidate, error, '本地 Oracle Worker 不可用')
      return
    }
    worker = nextWorker
    nextWorker.onmessage = (event): void => {
      if (
        !currentGeneration(candidate)
        || worker !== nextWorker
      ) {
        return
      }
      const response = event.data
      if (
        isRecord(response)
        && Number.isSafeInteger(response.generation)
        && (response.generation as number) > 0
        && response.generation !== candidate
      ) {
        return
      }
      if (!isWorkerResponse(response)) {
        terminateWorker()
        fail(candidate, 'Worker 返回无效结果', 'Worker 返回无效结果')
        return
      }
      terminateWorker()
      if (response.type === 'FAILED') {
        fail(candidate, response.message, '本地 Oracle Worker 计算失败')
      } else {
        complete(candidate, {
          outcome: response.outcome,
          localTickWindow: response.localTickWindow,
        })
      }
    }
    nextWorker.onerror = (event): void => {
      if (!currentGeneration(candidate) || worker !== nextWorker) {
        return
      }
      event.preventDefault?.()
      const message = event.message
      terminateWorker()
      fail(candidate, message, '本地 Oracle Worker 已崩溃')
    }
    nextWorker.onmessageerror = (): void => {
      if (!currentGeneration(candidate) || worker !== nextWorker) {
        return
      }
      terminateWorker()
      fail(candidate, 'Worker 消息无法读取', 'Worker 消息无法读取')
    }
    const request: LocalOracleWorkerRequest = {
      type: 'CALCULATE_WORKSPACE',
      generation: candidate,
      scenario,
    }
    try {
      nextWorker.postMessage(request)
    } catch (error) {
      terminateWorker()
      fail(candidate, error, '无法启动本地 Oracle Worker')
    }
  }

  const run = (
    candidate: number,
    scenario: TradingLabScenario,
  ): void => {
    if (!currentGeneration(candidate)) {
      return
    }
    timer = null
    const workUnits = estimateLocalExpectedWorkUnits(scenario)
    if (
      workUnits !== null
      && workUnits <= MAIN_THREAD_WORK_UNIT_LIMIT
    ) {
      calculateOnMain(candidate, scenario)
    } else {
      calculateInWorker(candidate, scenario)
    }
  }

  const schedule = (scenario: TradingLabScenario | null): void => {
    if (disposed) {
      return
    }
    generation += 1
    const candidate = generation
    clearPendingTimer()
    terminateWorker()
    if (scenario === null) {
      update({ status: 'IDLE' })
      return
    }
    update({ status: 'CALCULATING', generation: candidate })
    timer = dependencies.setTimer(
      () => run(candidate, scenario),
      DEBOUNCE_MILLISECONDS,
    )
  }

  const subscribe = (listener: () => void): (() => void) => {
    if (disposed) {
      return () => undefined
    }
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }

  const getSnapshot = (): LocalExpectedState => state

  const dispose = (): void => {
    if (disposed) {
      return
    }
    disposed = true
    generation += 1
    clearPendingTimer()
    terminateWorker()
    listeners.clear()
  }

  return {
    schedule,
    subscribe,
    getSnapshot,
    dispose,
  }
}
