export type TradingLabRunControlAction = 'pause' | 'resume' | 'cancel'

export type RunProgressRun = Readonly<{
  id: string
  scenarioId: string
  reportId: string | null
  state: string
  totalTicks: number
  pauseRequested: boolean
  cancelRequested: boolean
}>

export type RunProgressProps = Readonly<{
  run: RunProgressRun | null
  validationState: string | null
  connection: string
  highestObservedTick: number | null
  highestCompletedCheckpoint: number | null
  canExecute: boolean
  controlPending: TradingLabRunControlAction | null
  message?: string | null
  onControl(action: TradingLabRunControlAction): void
}>

const CANCELLABLE_STATES = new Set([
  'QUEUED',
  'RESETTING',
  'RUNNING',
  'PAUSED',
  'CANCELLING',
])
const CONTROL_CONNECTION_STATES = new Set([
  'CONNECTING',
  'OPEN',
  'RETRY_WAIT',
])

function checkpointText(
  completed: number | null,
  total: number | undefined,
): string {
  if (completed === null || total === undefined || total <= 0) {
    return '尚无完成 checkpoint'
  }
  const bounded = Math.min(completed, total)
  const percent = Math.floor((bounded * 10_000) / total) / 100
  return `${completed} / ${total}（${percent}%）`
}

export function RunProgress({
  run,
  validationState,
  connection,
  highestObservedTick,
  highestCompletedCheckpoint,
  canExecute,
  controlPending,
  message,
  onControl,
}: RunProgressProps) {
  const state = run?.state
  const controlConnectionReady = CONTROL_CONNECTION_STATES.has(connection)
  const pauseEnabled = (
    controlConnectionReady
    && canExecute
    && controlPending === null
    && state === 'RUNNING'
    && run?.pauseRequested === false
    && run.cancelRequested === false
  )
  const resumeEnabled = (
    controlConnectionReady
    && canExecute
    && controlPending === null
    && state === 'PAUSED'
    && run?.pauseRequested === true
    && run?.cancelRequested === false
  )
  const cancelEnabled = (
    controlConnectionReady
    && canExecute
    && controlPending === null
    && state !== undefined
    && CANCELLABLE_STATES.has(state)
    && run?.cancelRequested === false
  )

  return (
    <section className="trading-lab-run-panel" aria-labelledby="trading-lab-run-title">
      <div>
        <span className="trading-lab-eyebrow">Durable validation session</span>
        <h2 id="trading-lab-run-title">运行控制</h2>
      </div>
      <dl className="trading-lab-run-summary">
        <div>
          <dt>Scenario ID</dt>
          <dd>{run?.scenarioId ?? '未附加'}</dd>
        </div>
        <div>
          <dt>Run ID</dt>
          <dd>{run?.id ?? '未附加'}</dd>
        </div>
        <div>
          <dt>Report ID</dt>
          <dd>{run?.reportId ?? '未附加'}</dd>
        </div>
        <div>
          <dt>主 Run 状态</dt>
          <dd>{run?.state ?? '未附加'}</dd>
        </div>
        <div>
          <dt>Validation 状态</dt>
          <dd>{validationState ?? '尚未观察'}</dd>
        </div>
        <div>
          <dt>连接状态</dt>
          <dd>{connection}</dd>
        </div>
        <div>
          <dt>已观察 Tick</dt>
          <dd>{highestObservedTick ?? '尚无'}</dd>
        </div>
        <div>
          <dt>已完成 checkpoint</dt>
          <dd>
            {checkpointText(
              highestCompletedCheckpoint,
              run?.totalTicks,
            )}
          </dd>
        </div>
      </dl>
      <div className="trading-lab-run-controls">
        <button
          type="button"
          disabled={!pauseEnabled}
          onClick={() => onControl('pause')}
        >
          暂停
        </button>
        <button
          type="button"
          disabled={!resumeEnabled}
          onClick={() => onControl('resume')}
        >
          恢复
        </button>
        <button
          type="button"
          disabled={!cancelEnabled}
          onClick={() => onControl('cancel')}
        >
          取消
        </button>
      </div>
      {message ? (
        <p role="status" className="trading-lab-run-message">{message}</p>
      ) : null}
    </section>
  )
}
