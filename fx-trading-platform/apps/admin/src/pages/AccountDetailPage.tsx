import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'

import {
  HighRiskActionDialog,
  type HighRiskAction,
  type HighRiskActionRequest
} from '../components/HighRiskActionDialog'
import { getAccountsPage } from '../services/adminApi'
import {
  forceCleanupAccount,
  getAuditIdByRequestId,
  loadAllAdminPages,
  loadAccountTradingDetail,
  resetDemoAccountAsAdmin,
  type AdminAccountDetail
} from '../services/adminTradingApi'
import { getValidAdminToken } from '../services/adminToken'
import type { AccountRow } from '../types'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

type AccountDetailView = {
  account?: AccountRow
  detail: AdminAccountDetail
}

type HighRiskTarget = {
  action: HighRiskAction
  accountId: string
  blockers: string[]
}

export function AccountDetailPage() {
  const { accountId = '' } = useParams<{ accountId: string }>()
  const currentAccountIdRef = useRef(accountId)
  currentAccountIdRef.current = accountId
  const { data, loading, error, reload } = useAdminData(
    (token) => loadAccountDetailView(accountId, token),
    [accountId]
  )
  const detail = data?.detail
  const account = data?.account
  const spotBalances = detail?.walletBalances.filter((balance) => balance.walletType === 'SPOT') ?? []
  const cleanupBlockers = detail && account ? deriveCleanupBlockers(detail, account) : []
  const demoActionsDisabled = account?.accountType !== 'DEMO'
  const [highRiskTarget, setHighRiskTarget] = useState<HighRiskTarget | null>(null)

  useEffect(() => {
    setHighRiskTarget(null)
  }, [accountId])

  const executeHighRiskAction = async (request: HighRiskActionRequest) => {
    const target = highRiskTarget
    if (!target) throw new Error('未选择高风险操作')
    const token = getValidAdminToken()
    if (!token) throw new Error('管理员登录状态已失效，请重新登录')
    if (target.action === 'force-cleanup') {
      await forceCleanupAccount(target.accountId, {
        ...request,
        confirmationText: 'CONFIRM_FORCE_CLEANUP'
      }, token)
    } else if (target.action === 'reset') {
      await resetDemoAccountAsAdmin(target.accountId, {
        ...request,
        confirmationText: 'CONFIRM_DEMO_RESET'
      }, token)
    }
    const auditId = await getAuditIdByRequestId(request.requestId, token)
    if (target.accountId === currentAccountIdRef.current) await reload()
    return { auditId }
  }

  return (
    <>
      <PageHeader
        title={account?.accountType === 'LIVE' ? 'Live 交易账户详情（只读）' : 'Demo 交易账户详情'}
        description={`账户 ${accountId || '-'} 的真实钱包、交易、资金费与流水记录。`}
      />
      {loading ? <StateBlock tone="loading">正在并行加载账户详情</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error && !account ? <StateBlock tone="empty">暂无该账户数据</StateBlock> : null}

      {!loading && !error && account && detail ? (
        <div className="operations-page">
          <section className="operation-section high-risk-context">
            <header>
              <div>
                <h3>Demo 高风险操作</h3>
                <p>强制清理与重置是两个独立、可审计的操作。请先清理阻塞项，再执行重置。</p>
              </div>
              <div className="operation-actions">
                <button
                  className="danger-button"
                  type="button"
                  disabled={demoActionsDisabled}
                  onClick={() => setHighRiskTarget({
                    action: 'force-cleanup',
                    accountId,
                    blockers: []
                  })}
                >
                  强制清理
                </button>
                <button
                  className="danger-button"
                  type="button"
                  disabled={demoActionsDisabled || cleanupBlockers.length > 0}
                  onClick={() => setHighRiskTarget({
                    action: 'reset',
                    accountId,
                    blockers: [...cleanupBlockers]
                  })}
                >
                  重置 Demo 账户
                </button>
              </div>
            </header>
            {cleanupBlockers.length > 0 ? (
              <ul className="operation-blockers">
                {cleanupBlockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
              </ul>
            ) : <p className="page-meta">当前未发现清理阻塞项。</p>}
          </section>

          <div className="admin-grid">
          <AccountSection title="现货 / Spot 余额">
            <DataTable
              rows={spotBalances}
              emptyText="暂无 Spot 钱包余额"
              columns={[
                { title: '资产', render: (row) => display(row.asset) },
                { title: '总额', render: (row) => display(row.total) },
                { title: '可用', render: (row) => display(row.available) },
                { title: '冻结', render: (row) => display(row.locked) }
              ]}
            />
          </AccountSection>

          <AccountSection title="永续 / Perp 余额">
            <section className="detail-grid">
              <DetailMetric label="余额" value={account.balance} />
              <DetailMetric label="权益" value={account.equity} />
              <DetailMetric label="已用保证金" value={account.usedMargin} />
              <DetailMetric label="可用保证金" value={account.freeMargin} />
            </section>
          </AccountSection>

          <AccountSection title="订单">
            <DataTable
              rows={detail.orders}
              emptyText="暂无订单"
              columns={[
                { title: '品种', render: (row) => display(row.symbol) },
                { title: '方向', render: (row) => display(row.side) },
                { title: '类型', render: (row) => display(row.orderType) },
                { title: '状态', render: (row) => display(row.status) },
                { title: '数量', render: (row) => display(row.quantity ?? row.lots) },
                { title: '成交价', render: (row) => display(row.executionPrice) },
                { title: '创建时间', render: (row) => formatDateTime(row.createdAt) }
              ]}
            />
          </AccountSection>

          <AccountSection title="成交">
            <DataTable
              rows={detail.trades}
              emptyText="暂无成交"
              columns={[
                { title: '品种', render: (row) => display(row.symbol) },
                { title: '方向', render: (row) => display(row.side) },
                { title: '数量', render: (row) => display(row.lots) },
                { title: '成交价', render: (row) => display(row.price) },
                { title: '已实现盈亏', render: (row) => display(row.realizedPnl) },
                { title: '成交时间', render: (row) => formatDateTime(row.executedAt) }
              ]}
            />
          </AccountSection>

          <AccountSection title="持仓">
            <DataTable
              rows={detail.positions}
              emptyText="暂无持仓"
              columns={[
                { title: '品种', render: (row) => display(row.symbol) },
                { title: '方向', render: (row) => display(row.side) },
                { title: '数量', render: (row) => display(row.lots) },
                { title: '开仓价', render: (row) => display(row.openPrice) },
                { title: '当前价', render: (row) => display(row.currentPrice) },
                { title: '浮动盈亏', render: (row) => display(row.floatingPnl) },
                { title: '状态', render: (row) => display(row.status) }
              ]}
            />
          </AccountSection>

          <AccountSection title="资金费结算">
            <DataTable
              rows={detail.fundingSettlements}
              emptyText="暂无资金费结算"
              columns={[
                { title: '品种', render: (row) => display(row.symbol) },
                { title: '持仓方向', render: (row) => display(row.positionSide) },
                { title: '费率', render: (row) => display(row.fundingRate) },
                { title: '金额', render: (row) => display(row.amount) },
                { title: '实际来源', render: (row) => display(row.source) },
                { title: '结算时间', render: (row) => formatDateTime(row.fundingTime) }
              ]}
            />
          </AccountSection>

          <AccountSection title="账户划转">
            <DataTable
              rows={detail.transfers}
              emptyText="暂无账户划转"
              columns={[
                { title: '划转 ID', render: (row) => row.transferId },
                { title: '方向', render: (row) => row.direction },
                { title: '金额', render: (row) => display(row.amount) },
                { title: 'Spot 余额', render: (row) => display(row.spotBalanceAfter) },
                { title: 'Perp 余额', render: (row) => display(row.perpBalanceAfter) },
                { title: '时间', render: (row) => formatDateTime(row.createdAt) }
              ]}
            />
          </AccountSection>

          <AccountSection title="资产流水">
            <DataTable
              rows={detail.assetLedger}
              emptyText="暂无资产流水"
              columns={[
                { title: '钱包', render: (row) => display(row.walletType) },
                { title: '资产', render: (row) => display(row.asset) },
                { title: '类型', render: (row) => display(row.entryType) },
                { title: '金额', render: (row) => display(row.amount) },
                { title: '余额', render: (row) => display(row.balanceAfter) },
                { title: '关联类型', render: (row) => display(row.referenceType) },
                { title: '关联 ID', render: (row) => display(row.referenceId) },
                { title: '时间', render: (row) => formatDateTime(row.createdAt) }
              ]}
            />
          </AccountSection>

          <AccountSection title="资金流水">
            <DataTable
              rows={detail.ledger}
              emptyText="暂无资金流水"
              columns={[
                { title: '类型', render: (row) => display(row.entryType) },
                { title: '金额', render: (row) => display(row.amount) },
                { title: '余额', render: (row) => display(row.balanceAfter) },
                { title: '资产', render: (row) => display(row.currency) },
                { title: '关联类型', render: (row) => display(row.referenceType) },
                { title: '关联 ID', render: (row) => display(row.referenceId) },
                { title: '时间', render: (row) => formatDateTime(row.createdAt) }
              ]}
            />
          </AccountSection>
          </div>

          <HighRiskActionDialog
            key={highRiskTarget ? `${highRiskTarget.accountId}:${highRiskTarget.action}` : 'closed'}
            open={highRiskTarget !== null}
            action={highRiskTarget?.action ?? 'force-cleanup'}
            title={highRiskTarget?.action === 'reset' ? '重置 Demo 账户' : '强制清理账户'}
            description={highRiskTarget?.action === 'reset'
              ? '重置将恢复 Demo Spot 与 Perp 初始资金，并清除允许清理的 Demo 状态。'
              : '强制清理将取消活动订单并关闭可清理持仓，不会绕过后端风控。'}
            context={<span>账户 ID：{highRiskTarget?.accountId ?? accountId}</span>}
            cleanupBlockers={highRiskTarget?.action === 'reset' ? highRiskTarget.blockers : []}
            onExecute={executeHighRiskAction}
            onClose={() => setHighRiskTarget(null)}
          />
        </div>
      ) : null}
    </>
  )
}

function deriveCleanupBlockers(detail: AdminAccountDetail, account: AccountRow) {
  const activeOrderStatuses = new Set([
    'RECEIVED',
    'VALIDATING',
    'ACCEPTED',
    'PENDING_ACTIVATION',
    'PENDING',
    'WORKING',
    'PARTIALLY_FILLED',
    'CANCEL_PENDING'
  ])
  const activeOrders = detail.orders.filter((order) => activeOrderStatuses.has(String(order.status))).length
  const activePositions = detail.positions.filter((position) => position.status === 'OPEN').length
  return [
    account.accountType !== 'DEMO' ? `账户类型 ${account.accountType} 不是 Demo；高风险 Demo 操作保持只读` : null,
    account.status !== 'ACTIVE' ? `账户状态 ${account.status} 不是 ACTIVE` : null,
    activeOrders > 0 ? `${activeOrders} 个活动订单需要先清理` : null,
    activePositions > 0 ? `${activePositions} 个活动持仓需要先清理` : null
  ].filter((blocker): blocker is string => blocker !== null)
}

async function loadAccountDetailView(accountId: string, token: string): Promise<AccountDetailView> {
  if (!accountId) throw new Error('缺少账户 ID')
  const [accounts, detail] = await Promise.all([
    loadAllAdminPages((page, size) => getAccountsPage(token, page, size)),
    loadAccountTradingDetail(accountId, token)
  ])
  return {
    account: accounts.find((candidate) => candidate.id === accountId),
    detail
  }
}

function AccountSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="operation-section">
      <h3>{title}</h3>
      {children}
    </section>
  )
}

function DetailMetric({ label, value }: { label: string; value: unknown }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{display(value)}</strong>
    </div>
  )
}
