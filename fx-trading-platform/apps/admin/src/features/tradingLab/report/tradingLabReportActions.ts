export type TradingLabReportActionReport = Readonly<{
  id: string
  runId: string
  scenarioId: string
  status: string
  permanent: boolean
  readonly [key: string]: unknown
}>

export type TradingLabReportActionRun = Readonly<{
  id: string
  scenarioId: string
  reportId: string | null
  state: string
  totalTicks: number
  pauseRequested: boolean
  cancelRequested: boolean
  version: number
  readonly [key: string]: unknown
}>

export type TradingLabReportActionDependencies = Readonly<{
  deleteReport(reportId: string, token: string): Promise<void>
  getReport(
    reportId: string,
    token: string,
  ): Promise<TradingLabReportActionReport>
  getRun(runId: string, token: string): Promise<TradingLabReportActionRun>
  setPermanent(
    reportId: string,
    permanent: boolean,
    token: string,
  ): Promise<unknown>
  isNotFound(error: unknown): boolean
}>

export type TradingLabReportDeletionResult =
  | Readonly<{
      kind: 'DELETED'
      run: TradingLabReportActionRun
    }>
  | Readonly<{
      kind: 'PRESENT'
      report: TradingLabReportActionReport
    }>
  | Readonly<{ kind: 'UNKNOWN' }>

export type TradingLabReportPermanentResult =
  | Readonly<{
      kind: 'CONFIRMED'
      report: TradingLabReportActionReport
    }>
  | Readonly<{
      kind: 'NOT_APPLIED'
      report: TradingLabReportActionReport
    }>
  | Readonly<{ kind: 'UNKNOWN' }>

const TERMINAL_RUN_STATES = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
])

type DeleteRequest = Readonly<{
  reportId: string
  runId: string
  scenarioId: string
  runState: string
  token: string
}>

type PermanentRequest = Readonly<{
  reportId: string
  runId: string
  scenarioId: string
  runState: string
  permanent: boolean
  token: string
}>

export function currentTradingLabReport<
  Report extends TradingLabReportActionReport,
>(
  run: TradingLabReportActionRun | null,
  report: Report | null,
): Report | null {
  return (
    run !== null
    && report !== null
    && TERMINAL_RUN_STATES.has(run.state)
    && run.reportId === report.id
    && run.id === report.runId
    && run.scenarioId === report.scenarioId
    && run.state === report.status
  )
    ? report
    : null
}

/**
 * A DELETE response is not authoritative: the connection can disappear after
 * the transaction commits. Probe detail exactly once, then verify the run-side
 * foreign key before reporting deletion.
 */
export async function reconcileTradingLabReportDeletion(
  request: DeleteRequest,
  dependencies: TradingLabReportActionDependencies,
): Promise<TradingLabReportDeletionResult> {
  try {
    await dependencies.deleteReport(request.reportId, request.token)
  } catch {
    // Reconcile the possibly committed mutation below; never retry DELETE here.
  }

  try {
    const current = await dependencies.getReport(
      request.reportId,
      request.token,
    )
    return reportMatchesRun(request, current)
      ? { kind: 'PRESENT', report: current }
      : { kind: 'UNKNOWN' }
  } catch (error) {
    if (!dependencies.isNotFound(error)) {
      return { kind: 'UNKNOWN' }
    }
  }

  try {
    const run = await dependencies.getRun(request.runId, request.token)
    return (
      run.id === request.runId
      && run.scenarioId === request.scenarioId
      && run.state === request.runState
      && run.reportId === null
    )
      ? { kind: 'DELETED', run }
      : { kind: 'UNKNOWN' }
  } catch {
    return { kind: 'UNKNOWN' }
  }
}

/**
 * Retention mutation results are reconciled from a fresh report detail read.
 * This makes response loss and optimistic-conflict outcomes explicit without
 * issuing an automatic second mutation.
 */
export async function reconcileTradingLabReportPermanent(
  request: PermanentRequest,
  dependencies: TradingLabReportActionDependencies,
): Promise<TradingLabReportPermanentResult> {
  try {
    await dependencies.setPermanent(
      request.reportId,
      request.permanent,
      request.token,
    )
  } catch {
    // The authoritative detail read below decides whether the write committed.
  }

  try {
    const current = await dependencies.getReport(
      request.reportId,
      request.token,
    )
    if (!reportMatchesRun(request, current)) {
      return { kind: 'UNKNOWN' }
    }
    return current.permanent === request.permanent
      ? { kind: 'CONFIRMED', report: current }
      : { kind: 'NOT_APPLIED', report: current }
  } catch {
    return { kind: 'UNKNOWN' }
  }
}

function reportMatchesRun(
  request: Readonly<{
    reportId: string
    runId: string
    scenarioId: string
    runState: string
  }>,
  report: TradingLabReportActionReport,
): boolean {
  return (
    report.id === request.reportId
    && report.runId === request.runId
    && report.scenarioId === request.scenarioId
    && report.status === request.runState
  )
}
