import type {
  LocalExpectedState,
} from '../localExpected/localOracleScheduler.ts'

export type LocalExpectedPanelProps = {
  state: LocalExpectedState
}

type DisplayIssue = Readonly<{
  path: string
  code: string
  message: string
  severity?: 'ERROR' | 'WARNING'
}>

function IssueList({
  issues,
}: {
  issues: readonly DisplayIssue[]
}) {
  if (issues.length === 0) {
    return null
  }
  return (
    <ul className="trading-lab-local-issues">
      {issues.map((issue, index) => (
        <li key={`${issue.path}:${issue.code}:${index}`}>
          <strong>{issue.code}</strong>
          {issue.severity === undefined ? null : ` · ${issue.severity}`}
          {' — '}
          {issue.message}
          {issue.path.length === 0 ? null : `（${issue.path}）`}
        </li>
      ))}
    </ul>
  )
}

function CalculatedPanel({
  state,
}: {
  state: Extract<LocalExpectedState, { status: 'CALCULATED' }>
}) {
  const { result, runnerIssues } = state.outcome
  const finalSnapshot = result.snapshots[result.snapshots.length - 1]

  return (
    <>
      <p>本地预期已计算（第 {state.generation} 代）。</p>
      <IssueList issues={runnerIssues} />
      {finalSnapshot === undefined ? (
        <p>场景未生成动作快照。</p>
      ) : (
        <>
          <h4>最终快照摘要</h4>
          <dl>
            <dt>动作</dt>
            <dd>{finalSnapshot.actionId}</dd>
            <dt>虚拟时间</dt>
            <dd>{finalSnapshot.virtualTime}</dd>
            <dt>订单 / 成交</dt>
            <dd>
              {finalSnapshot.orders.length} / {finalSnapshot.trades.length}
            </dd>
            <dt>现货 / 永续持仓</dt>
            <dd>
              {finalSnapshot.spotPositions.length}
              {' / '}
              {finalSnapshot.perpetualPositions.length}
            </dd>
          </dl>
          <h4>账户汇总</h4>
          <dl>
            <dt>钱包余额（USDT）</dt>
            <dd>{finalSnapshot.accountSummary.totalWalletBalanceUsdt}</dd>
            <dt>可用余额（USDT）</dt>
            <dd>{finalSnapshot.accountSummary.availableBalanceUsdt}</dd>
            <dt>未实现盈亏（USDT）</dt>
            <dd>{finalSnapshot.accountSummary.totalUnrealizedPnlUsdt}</dd>
            <dt>权益（USDT）</dt>
            <dd>{finalSnapshot.accountSummary.equityUsdt}</dd>
          </dl>
          <h4>风险</h4>
          <dl>
            <dt>维持保证金（USDT）</dt>
            <dd>{finalSnapshot.risk.maintenanceMarginUsdt}</dd>
            <dt>保证金率</dt>
            <dd>{finalSnapshot.risk.marginRatio ?? '不适用'}</dd>
            <dt>触发强平</dt>
            <dd>{finalSnapshot.risk.liquidationTriggered ? '是' : '否'}</dd>
          </dl>
          <h4>风险与警告</h4>
          {finalSnapshot.warnings.length === 0 ? (
            <p>无本地警告。</p>
          ) : (
            <ul>
              {finalSnapshot.warnings.map((warning, index) => (
                <li key={`${warning.code}:${index}`}>
                  <strong>{warning.code}</strong>
                  {' — '}
                  {warning.message}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  )
}

export function LocalExpectedPanel({
  state,
}: LocalExpectedPanelProps) {
  let content
  switch (state.status) {
    case 'IDLE':
      content = <p>等待导入或打开本地场景。</p>
      break
    case 'CALCULATING':
      content = <p>正在计算本地预期（第 {state.generation} 代）…</p>
      break
    case 'CALCULATED':
      content = <CalculatedPanel state={state} />
      break
    case 'BLOCKED':
      content = (
        <>
          <p>本地计算受阻；该状态不代表负向场景成功。</p>
          <IssueList issues={state.outcome.issues} />
          <IssueList issues={state.outcome.runnerIssues} />
        </>
      )
      break
    case 'FAILED':
      content = (
        <>
          <p>本地计算失败（第 {state.generation} 代）。</p>
          <p role="alert">{state.message}</p>
        </>
      )
      break
  }

  return (
    <section
      className="trading-lab-local-expected"
      aria-labelledby="trading-lab-local-expected-title"
    >
      <h3 id="trading-lab-local-expected-title">本地预期</h3>
      {content}
    </section>
  )
}
