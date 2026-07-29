import { useState } from 'react'

import {
  TRADING_LAB_ACTUAL_STATE_PAGE_SIZE,
  formatTradingLabActualStateValue,
  paginateTradingLabActualStateRows,
  type TradingLabActualStateObject,
  type TradingLabActualStateResult,
  type TradingLabActualStateValue,
} from '../run/tradingLabActualState.ts'

const SUMMARY_GROUP = 'summary'
const ARRAY_GROUPS = [
  ['walletBalances', '钱包余额'],
  ['assetLedger', '资产流水'],
  ['cashLedger', '现金流水'],
  ['orders', '订单'],
  ['trades', '成交'],
  ['positions', '持仓'],
  ['fundingSettlements', '资金费结算'],
] as const

export type ActualStatePanelProps = Readonly<{
  actualState: TradingLabActualStateResult
}>

function ActualStateObjectRows({
  value,
}: {
  value: TradingLabActualStateObject
}) {
  const entries = Object.entries(value)
  if (entries.length === 0) {
    return <p>当前组为空。</p>
  }
  return (
    <dl className="trading-lab-actual-state-object">
      {entries.map(([key, item]) => (
        <div key={key}>
          <dt>{key}</dt>
          <dd>
            <code>{formatTradingLabActualStateValue(item)}</code>
          </dd>
        </div>
      ))}
    </dl>
  )
}

function ActualStateRows({
  rows,
}: {
  rows: readonly TradingLabActualStateValue[]
}) {
  if (rows.length === 0) {
    return <p>当前页无记录。</p>
  }
  return (
    <ol className="trading-lab-actual-state-rows">
      {rows.map((row, index) => (
        <li key={index}>
          {row !== null && typeof row === 'object' && !Array.isArray(row)
            ? (
                <ActualStateObjectRows
                  value={row as TradingLabActualStateObject}
                />
              )
            : <code>{formatTradingLabActualStateValue(row)}</code>}
        </li>
      ))}
    </ol>
  )
}

function ActualStateGroup({
  name,
  label,
  rows,
}: {
  name: string
  label: string
  rows: readonly TradingLabActualStateValue[]
}) {
  const [requestedPage, setRequestedPage] = useState(1)
  const page = paginateTradingLabActualStateRows(rows, requestedPage)
  return (
    <section
      className="trading-lab-actual-state-group"
      aria-labelledby={`trading-lab-actual-state-${name}`}
    >
      <div className="trading-lab-section-heading">
        <h4 id={`trading-lab-actual-state-${name}`}>{label}</h4>
        <span>
          {page.totalRows} 条 · 每页最多
          {' '}
          {TRADING_LAB_ACTUAL_STATE_PAGE_SIZE}
        </span>
      </div>
      <ActualStateRows rows={page.rows} />
      <div className="trading-lab-report-actions">
        <button
          type="button"
          disabled={page.page <= 1}
          onClick={() => setRequestedPage(page.page - 1)}
        >
          Previous
        </button>
        <span>
          {page.page} / {page.pageCount}
        </span>
        <button
          type="button"
          disabled={page.page >= page.pageCount}
          onClick={() => setRequestedPage(page.page + 1)}
        >
          Next
        </button>
      </div>
    </section>
  )
}

export function ActualStatePanel({
  actualState,
}: ActualStatePanelProps) {
  if (actualState.availability === 'UNAVAILABLE') {
    return (
      <section
        className="trading-lab-panel"
        aria-labelledby="trading-lab-actual-state-title"
      >
        <h3 id="trading-lab-actual-state-title">权威实际状态</h3>
        <p>
          {actualState.reason === 'NO_CHECKPOINT'
            ? '尚未收到 checkpoint。'
            : '最新 checkpoint 状态不可用；旧快照已清除。'}
        </p>
      </section>
    )
  }

  const {
    sourceEventId,
    tickSequence,
    virtualTime,
    correlationId,
    state,
  } = actualState
  return (
    <section
      className="trading-lab-panel"
      aria-labelledby="trading-lab-actual-state-title"
    >
      <div className="trading-lab-section-heading">
        <div>
          <span className="trading-lab-eyebrow">Authoritative checkpoint</span>
          <h3 id="trading-lab-actual-state-title">权威实际状态</h3>
        </div>
        <span className="trading-lab-status-chip">
          Tick {tickSequence}
        </span>
      </div>
      <dl className="trading-lab-report-metadata">
        <div>
          <dt>Durable event</dt>
          <dd>{sourceEventId}</dd>
        </div>
        <div>
          <dt>虚拟时间</dt>
          <dd>{virtualTime}</dd>
        </div>
        <div>
          <dt>Correlation</dt>
          <dd>{correlationId ?? '未提供'}</dd>
        </div>
      </dl>
      <section
        className="trading-lab-actual-state-group"
        aria-labelledby="trading-lab-actual-state-summary"
      >
        <h4 id="trading-lab-actual-state-summary">账户汇总</h4>
        <ActualStateObjectRows value={state[SUMMARY_GROUP]} />
      </section>
      {ARRAY_GROUPS.map(([name, label]) => (
        <ActualStateGroup
          key={name}
          name={name}
          label={label}
          rows={state[name]}
        />
      ))}
    </section>
  )
}
