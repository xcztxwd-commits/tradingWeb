import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { DataViewColumn } from '@fx-platform/ui'

import { ApiErrorState, LoadingState, LoginRequiredState } from '../../components/user-page/PageState'
import { formatApiError } from '../../components/user-page/userPageModels'
import type { AccountRouteModel } from '../../routes/account/accountRoute.types'
import type { AccountDataCollectionComponent } from './dataCollection.types'
import { toNumber, type AccountSummary, type Amount, type LedgerEntry, type OrderResponse, type PositionResponse } from '@fx-platform/frontend-core'

export function DashboardContent({ model, DataCollection }: { model: AccountRouteModel; DataCollection: AccountDataCollectionComponent }) {
  const navigate = model.navigateTo
  const { t } = useTranslation()
  const { account, orders, positions, ledgerEntries, sessionMode, sessionError, loginRequired, retrySession } =
    model.accountData

  const openPositions = useMemo(() => positions.filter((position) => position.status !== 'CLOSED'), [positions])
  const recentOrders = useMemo(() => orders.slice(0, 8), [orders])
  const recentLedgerEntries = useMemo(() => ledgerEntries.slice(0, 8), [ledgerEntries])
  const floatingPnl = useMemo(() => sumAmounts(openPositions.map((position) => position.floatingPnl)), [openPositions])
  const todayPnl = useMemo(() => sumTodayPnl(ledgerEntries), [ledgerEntries])
  const openPositionCount = openPositions.length
  const pendingOrderCount = useMemo(() => orders.filter((order) => order.status === 'PENDING').length, [orders])
  const assetStructure = useMemo(() => getAssetStructure(account, t), [account, t])
  const riskSummary = useMemo(
    () => getRiskSummary(account, openPositionCount, pendingOrderCount, t),
    [account, openPositionCount, pendingOrderCount, t]
  )
  const orderColumns = useMemo<Array<DataViewColumn<OrderResponse>>>(
    () => [
      { key: 'createdAt', label: t('common.time'), sortable: true, render: (order) => formatTime(order.createdAt) },
      { key: 'symbol', label: 'Symbol', sortable: true },
      { key: 'side', label: 'Side', sortable: true },
      { key: 'orderType', label: t('common.type'), sortable: true },
      { key: 'status', label: t('common.status'), sortable: true, render: (order) => <StatusChip status={order.status} /> },
      { key: 'filledQuantity', label: t('orders.filled'), render: (order) => order.filledQuantity ?? '-' }
    ],
    [t]
  )
  const ledgerColumns = useMemo<Array<DataViewColumn<LedgerEntry>>>(
    () => [
      { key: 'createdAt', label: t('common.time'), sortable: true, render: (entry) => formatTime(entry.createdAt) },
      { key: 'entryType', label: t('common.type'), sortable: true },
      { key: 'amount', label: t('common.amount'), sortable: true },
      { key: 'balanceAfter', label: t('assets.balance'), sortable: true },
      { key: 'description', label: t('assets.description'), render: (entry) => entry.description ?? '-' }
    ],
    [t]
  )
  const positionColumns = useMemo<Array<DataViewColumn<PositionResponse>>>(
    () => [
      { key: 'symbol', label: 'Symbol', sortable: true },
      { key: 'side', label: 'Side', sortable: true },
      { key: 'lots', label: 'Lots', sortable: true },
      { key: 'openPrice', label: t('positions.entryPrice'), sortable: true },
      { key: 'currentPrice', label: t('dashboard.currentPrice'), sortable: true },
      { key: 'floatingPnl', label: t('dashboard.floatingPnl'), sortable: true },
      { key: 'marginHeld', label: t('positions.margin'), sortable: true }
    ],
    [t]
  )
  const hasAccountData = Boolean(account)
  const hasRiskData = hasAccountData || openPositionCount > 0 || pendingOrderCount > 0

  if (loginRequired) {
    return (
      <section className="user-page">
        <LoginRequiredState
          message={t('dashboard.loginMessage')}
          onLogin={() => navigate('/login?redirect=/dashboard')}
        />
      </section>
    )
  }

  return (
    <section className="user-page">
      <header className="user-page__header">
        <div>
          <h1>{t('dashboard.accountOverview')}</h1>
          <p>{t('dashboard.accountOverviewSummary')}</p>
        </div>
        <button type="button" className="table-action table-action--secondary" onClick={() => void retrySession()}>
          {t('common.refresh')}
        </button>
      </header>

      {sessionMode === 'loading' ? <LoadingState message={t('dashboard.loadingOverview')} /> : null}
      {sessionMode === 'error' ? (
        <ApiErrorState
          error={formatApiError(new Error(sessionError ?? t('dashboard.unavailable')))}
          onAction={() => void retrySession()}
        />
      ) : null}

      <div className="user-page__metrics">
        <Metric label={t('dashboard.equity')} value={formatAmount(account?.equity)} />
        <Metric label={t('assets.balance')} value={formatAmount(account?.balance)} />
        <Metric label={t('dashboard.freeMargin')} value={formatAmount(account?.freeMargin)} />
        <Metric label={t('assets.usedMargin')} value={formatAmount(account?.usedMargin)} />
        <Metric label={t('dashboard.floatingPnl')} value={formatSignedAmount(floatingPnl, account)} tone={floatingPnl >= 0 ? 'positive' : 'negative'} />
        <Metric label={t('dashboard.todayPnl')} value={formatSignedAmount(todayPnl, account)} tone={todayPnl >= 0 ? 'positive' : 'negative'} />
        <Metric label={t('dashboard.openPositionCount')} value={openPositionCount} />
        <Metric label={t('dashboard.pendingOrderCount')} value={pendingOrderCount} />
      </div>

      <div className="dashboard-overview-grid">
        <section className="user-page__events dashboard-card">
          <h2>{t('dashboard.assetStructure')}</h2>
          {hasAccountData ? (
            <div className="asset-structure-list">
              {assetStructure.map((item) => (
                <div key={item.label} className="asset-structure-row">
                  <div>
                    <strong>{item.label}</strong>
                    <span>{formatAmountWithCurrency(item.value, account)}</span>
                  </div>
                  <div className="asset-structure-bar" aria-label={t('dashboard.assetPercent', { label: item.label, percent: item.percent.toFixed(0) })}>
                    <span style={{ width: `${item.percent}%` }} />
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <DashboardNextAction
              title={t('dashboard.emptyAssets')}
              message={t('dashboard.emptyAssetsMessage')}
              label={t('assets.goDeposit')}
              onClick={() => navigate('/wallet')}
            />
          )}
        </section>

        <section className="user-page__events dashboard-card">
          <h2>{t('dashboard.riskSummary')}</h2>
          <div className={`risk-summary risk-summary--${riskSummary.tone}`}>
            <strong>{riskSummary.title}</strong>
            <span>{riskSummary.message}</span>
          </div>
          <div className="dashboard-mini-grid">
            <Metric label="Margin Level" value={formatMarginLevel(account?.marginLevel)} />
            <Metric label={t('positions.current')} value={openPositionCount} />
            <Metric label={t('orders.current')} value={pendingOrderCount} />
          </div>
          {!hasRiskData ? (
            <DashboardNextAction
              title={t('dashboard.emptyRisk')}
              message={t('dashboard.emptyRiskMessage')}
              label={t('markets.goTrading')}
              onClick={() => navigate('/trading')}
            />
          ) : null}
        </section>

        <section className="user-page__events dashboard-card">
          <h2>{t('dashboard.quickActions')}</h2>
          <div className="quick-action-grid">
            <button type="button" className="table-action table-action--primary" onClick={() => navigate('/trading')}>
              {t('trading.trade')}
            </button>
            <button type="button" className="table-action table-action--secondary" onClick={() => navigate('/wallet')}>
              {t('assets.deposit')}
            </button>
            <button type="button" className="table-action table-action--secondary" onClick={() => navigate('/orders')}>
              {t('dashboard.viewOrders')}
            </button>
            <button type="button" className="table-action table-action--secondary" onClick={() => navigate('/positions')}>
              {t('dashboard.viewPositions')}
            </button>
          </div>
        </section>
      </div>

      <div className="user-page__split">
        <section className="user-page__events">
          <h2>{t('dashboard.recentOrders')}</h2>
          <DataCollection
            rows={recentOrders}
            columns={orderColumns}
            rowKey={(order) => order.id}
            emptyMessage={t('dashboard.emptyRecentOrders')}
            emptyAction={{ label: t('markets.goTrading'), href: '/trading' }}
            pageSize={5}
          />
        </section>

        <section className="user-page__events">
          <h2>{t('dashboard.recentLedger')}</h2>
          <DataCollection
            rows={recentLedgerEntries}
            columns={ledgerColumns}
            rowKey={(entry) => entry.id}
            emptyMessage={t('assets.emptyLedger')}
            emptyAction={{ label: t('assets.goDeposit'), href: '/wallet' }}
            pageSize={5}
          />
        </section>
      </div>

      <section className="user-page__events">
        <h2>{t('positions.current')}</h2>
        <DataCollection
          rows={openPositions}
          columns={positionColumns}
          rowKey={(position) => position.id}
          emptyMessage={t('positions.emptyCurrent')}
          emptyAction={{ label: t('dashboard.viewMarkets'), href: '/markets' }}
          pageSize={5}
        />
      </section>
    </section>
  )
}

function Metric({
  label,
  value,
  tone
}: {
  label: string
  value: Amount | string | number | null | undefined
  tone?: 'positive' | 'negative'
}) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong className={tone ? `metric__value--${tone}` : undefined}>{value ?? '-'}</strong>
    </div>
  )
}

function DashboardNextAction({
  title,
  message,
  label,
  onClick
}: {
  title: string
  message: string
  label: string
  onClick: () => void
}) {
  return (
    <div className="dashboard-next-action">
      <div>
        <strong>{title}</strong>
        <span>{message}</span>
      </div>
      <button type="button" className="table-action table-action--secondary" onClick={onClick}>
        {label}
      </button>
    </div>
  )
}

function StatusChip({ status }: { status: string }) {
  const tone = status === 'FILLED' || status === 'OPEN' ? 'positive' : status === 'PENDING' ? 'warning' : ''
  return <span className={`status-chip${tone ? ` status-chip--${tone}` : ''}`}>{status}</span>
}

function sumTodayPnl(entries: LedgerEntry[]) {
  const today = new Date().toISOString().slice(0, 10)
  return sumAmounts(
    entries
      .filter((entry) => entry.createdAt?.startsWith(today))
      .filter((entry) => entry.entryType === 'TRADE_PNL' || entry.entryType === 'TRADE_FEE')
      .map((entry) => entry.amount)
  )
}

function sumAmounts(values: Amount[]): number {
  return values.reduce<number>((total, value) => total + (toNumber(value) ?? 0), 0)
}

function formatAmount(value: Amount | null | undefined) {
  if (value === null || value === undefined) return '-'
  return value
}

function formatSignedAmount(value: number, account: AccountSummary | undefined) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)} ${account?.baseCurrency ?? ''}`.trim()
}

function formatAmountWithCurrency(value: number, account: AccountSummary | undefined) {
  return `${value.toFixed(2)} ${account?.baseCurrency ?? ''}`.trim()
}

function formatMarginLevel(value: Amount | null | undefined) {
  const numeric = toNumber(value)
  if (numeric === null) return '-'
  return `${numeric.toFixed(2)}%`
}

function getAssetStructure(account: AccountSummary | undefined, translate: (key: string) => string) {
  const available = toNumber(account?.freeMargin) ?? 0
  const usedMargin = toNumber(account?.usedMargin) ?? 0
  const balance = toNumber(account?.balance) ?? 0
  const equity = toNumber(account?.equity) ?? 0
  const floating = equity - balance
  const total = Math.max(available + usedMargin + Math.abs(floating), 1)
  return [
    { label: translate('dashboard.availableFunds'), value: available, percent: (available / total) * 100 },
    { label: translate('assets.usedMargin'), value: usedMargin, percent: (usedMargin / total) * 100 },
    { label: translate('dashboard.floatingPnl'), value: floating, percent: (Math.abs(floating) / total) * 100 }
  ]
}

function getRiskSummary(account: AccountSummary | undefined, openPositionCount: number, pendingOrderCount: number, translate: (key: string) => string) {
  const marginLevel = toNumber(account?.marginLevel)
  if (marginLevel !== null && marginLevel < 120) {
    return {
      tone: 'danger' as const,
      title: translate('dashboard.riskHigh'),
      message: translate('dashboard.riskHighMessage')
    }
  }
  if (openPositionCount >= 5 || pendingOrderCount >= 5) {
    return {
      tone: 'warning' as const,
      title: translate('dashboard.riskAttention'),
      message: translate('dashboard.riskAttentionMessage')
    }
  }
  return {
    tone: 'stable' as const,
    title: translate('dashboard.riskStable'),
    message: translate('dashboard.riskStableMessage')
  }
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '-'
}
