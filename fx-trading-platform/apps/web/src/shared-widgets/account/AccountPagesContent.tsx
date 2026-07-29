import { FormEvent, useMemo } from 'react'
import { ArrowDownCircle, ArrowUpCircle, Bell, CheckCircle2, CircleDollarSign, Settings, ShieldCheck, UserRound } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, NavLink } from 'react-router-dom'
import type { DataViewColumn } from '@fx-platform/ui'

import { AssetMark } from '../asset/AssetMark'
import { ApiErrorState, LoadingState, LoginRequiredState } from '../data/PageState'
import { cssModuleClasses as css } from '../data/cssModuleClasses'
import { formatApiError } from '../data/userPageModels'
import type { AccountRouteModel } from '../../routes/account/accountRoute.types'
import type { AccountDataCollectionComponent } from './dataCollection.types'
import {
  toNumber,
  type AccountDataStatus,
  type AccountSummary,
  type Amount,
  type FundOrder,
  type LedgerEntry,
  type OrderResponse,
  type PositionResponse,
  type WalletBalance
} from '@fx-platform/frontend-core'
import surfaceStyles from '../data/UserPageSurface.module.css'
import accountStyles from './AccountPagesContent.module.css'

const styles = { ...surfaceStyles, ...accountStyles }

const accountNav = [
  { to: '/account/overview', label: 'Overview' },
  { to: '/account/assets', label: 'Assets' },
  { to: '/account/orders/funding', label: 'Funding records', nativeLabel: '资金流水' },
  { to: '/account/orders/trades', label: 'Trade orders', nativeLabel: '交易订单' },
  { to: '/account/security/kyc', label: 'Identity verification', nativeLabel: '身份认证' },
  { to: '/account/settings', label: 'Settings' }
] as const

const fundOrderStatuses = ['ALL', 'PENDING_REVIEW', 'PENDING', 'APPROVED', 'REJECTED']
const fundOrderTypes = ['ALL', 'RECHARGE', 'WITHDRAWAL']
const tradeOrderTabs = [
  { value: 'OPEN', label: 'Open orders' },
  { value: 'HISTORY', label: 'Order history' },
  { value: 'FILLED', label: 'Fill history' }
] as const
type TradeOrderTab = (typeof tradeOrderTabs)[number]['value']

type AccountContentProps = {
  model: AccountRouteModel
  DataCollection: AccountDataCollectionComponent
}

type AssetRow = {
  key: string
  walletType: string
  currency: string
  balance: Amount
  available: Amount
  frozen: Amount
  activityCount: number
}

type AccountStateProps = {
  sessionMode: AccountDataStatus
  sessionError: string | null
  loginRequired: boolean
  retrySession: () => Promise<void>
  onLogin: () => void
}

export function AccountOverviewContent({ model }: AccountContentProps) {
  const {
    account,
    ledgerEntries,
    loginRequired,
    orders,
    positions,
    retrySession,
    sessionError,
    sessionMode,
    walletBalances
  } = model.accountData

  return (
    <AccountShell
      account={account}
      title="Overview"
      summary="Live account, asset, risk and recent activity from the backend session."
    >
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      <div className={css(styles, "account-dashboard-layout")}>
        <AccountProfileSummary account={account} positions={positions} />
        <AccountOnboardingSteps account={account} />
        <AccountAssetActionPanel account={account} walletBalances={walletBalances} />
        <AccountDashboardInsights account={account} ledgerEntries={ledgerEntries} orders={orders} positions={positions} />
      </div>
      <RecentLedgerPanel entries={ledgerEntries.slice(0, 5)} />
    </AccountShell>
  )
}

export function AccountAssetsContent({ model, DataCollection }: AccountContentProps) {
  const {
    account,
    ledgerEntries,
    loginRequired,
    retrySession,
    sessionError,
    sessionMode,
    walletBalances
  } = model.accountData
  const frozenAmount = toNumber(account?.usedMargin) ?? 0
  const assetRows = useMemo(
    () => getAssetRows(account, walletBalances, ledgerEntries, frozenAmount),
    [account, frozenAmount, ledgerEntries, walletBalances]
  )
  const assetColumns = useMemo(() => createAssetColumns(), [])

  return (
    <AccountShell
      account={account}
      title="Assets"
      summary="Wallet balances, available funds, locked amounts and recent ledger movement."
    >
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      <div className={css(styles, "account-split-grid")}>
        <AssetAccountCards account={account} rows={assetRows} />
        <RecentLedgerPanel entries={ledgerEntries.slice(0, 6)} />
      </div>
      <DemoWalletOperationsLink account={account} walletBalances={walletBalances} />
      <DataCollection
        rows={assetRows}
        columns={assetColumns}
        rowKey={(asset) => asset.key}
        emptyMessage="No wallet balances"
        emptyAction={{ label: 'Create funding request', href: '/account/orders/funding' }}
        pageSize={8}
      />
    </AccountShell>
  )
}

export function FundingRecordsContent({ model, DataCollection }: AccountContentProps) {
  const { account, accountId, ledgerEntries, loginRequired, retrySession, sessionError, sessionMode, token } = model.accountData
  const {
    apiError,
    currency,
    loading: fundOrdersLoading,
    notice,
    orderType: fundOrderType,
    setOrderType: setFundOrderType,
    setStatusFilter,
    setTypeFilter,
    statusFilter,
    submit,
    typeFilter,
    visibleOrders: visibleFundOrders
  } = model.funding
  const fundOrderColumns = useMemo(() => createFundOrderColumns(), [])
  const ledgerColumns = useMemo(() => createLedgerColumns(), [])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!token || !accountId) return
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    const submitted = await submit({
      orderType: form.get('orderType') === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'RECHARGE',
      amount: String(form.get('amount') ?? ''),
      currency: String(form.get('currency') ?? currency),
      paymentMethodId: nullableString(form.get('paymentMethodId')),
      note: String(form.get('note') ?? '')
    })
    if (submitted) {
      formElement.reset()
    }
  }

  return (
    <AccountShell
      account={account}
      title="Funding records"
      summary="Deposit and withdrawal requests are read from the finance backend."
    >
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      {apiError ? <ApiErrorState error={apiError} onAction={() => void model.funding.load()} /> : null}
      {notice ? <div className={css(styles, "user-page__notice")}>{notice}</div> : null}

      <form className={css(styles, "user-page__form")} onSubmit={handleSubmit}>
        <label>
          <span>Type</span>
          <select name="orderType" value={fundOrderType} onChange={(event) => setFundOrderType(event.target.value === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'RECHARGE')}>
            <option value="RECHARGE">RECHARGE</option>
            <option value="WITHDRAWAL">WITHDRAWAL</option>
          </select>
        </label>
        <label>
          <span>Amount</span>
          <input name="amount" type="number" min="0.01" step="0.01" required />
        </label>
        <label>
          <span>Currency</span>
          <select name="currency" defaultValue={currency}>
            <option value={currency}>{currency}</option>
            <option value="USDT">USDT</option>
            <option value="USD">USD</option>
          </select>
        </label>
        <label>
          <span>Payment method ID</span>
          <input name="paymentMethodId" placeholder="Optional" />
        </label>
        <label>
          <span>Note</span>
          <input name="note" placeholder="Optional request note" />
        </label>
        <button
          className={css(styles, 'table-action', fundOrderType === 'WITHDRAWAL' ? 'table-action--danger' : 'table-action--primary')}
          disabled={!token || !accountId}
          type="submit"
        >
          {fundOrderType === 'WITHDRAWAL' ? 'Submit withdrawal' : 'Submit deposit'}
        </button>
      </form>

      <div className={css(styles, "account-filter-row")}>
        {fundOrderTypes.map((type) => (
          <button key={type} type="button" aria-pressed={typeFilter === type} onClick={() => setTypeFilter(type)}>
            {type}
          </button>
        ))}
        <select aria-label="Funding status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
          {fundOrderStatuses.map((status) => (
            <option key={status} value={status}>
              {status}
            </option>
          ))}
        </select>
      </div>

      {fundOrdersLoading ? <LoadingState message="Loading funding records." /> : null}
      <DataCollection
        rows={visibleFundOrders}
        columns={fundOrderColumns}
        rowKey={(order) => order.id}
        emptyMessage="No funding records"
        emptyAction={{ label: 'Create funding request', href: '/account/orders/funding' }}
      />
      <DataCollection
        rows={ledgerEntries}
        columns={ledgerColumns}
        rowKey={(entry) => entry.id}
        emptyMessage="No ledger entries"
        pageSize={6}
      />
    </AccountShell>
  )
}

export function TradeOrdersContent({ model, DataCollection }: AccountContentProps) {
  const { account, loginRequired, retrySession, sessionError, sessionMode } = model.accountData
  const {
    activeTab,
    query,
    setActiveTab,
    setQuery,
    setSideFilter,
    sideFilter,
    visibleOrders
  } = model.trades
  const tradeColumns = useMemo(() => createTradeOrderColumns(), [])

  return (
    <AccountShell account={account} title="Trade orders" summary="Current and historical orders from the trading backend.">
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      <div className={css(styles, "account-filter-row")}>
        {tradeOrderTabs.map((tab) => (
          <button key={tab.value} type="button" aria-pressed={activeTab === tab.value} onClick={() => setActiveTab(tab.value)}>
            {tab.label}
          </button>
        ))}
        <input aria-label="Symbol" placeholder="BTCUSDT" value={query} onChange={(event) => setQuery(event.target.value)} />
        <select aria-label="Side" value={sideFilter} onChange={(event) => setSideFilter(event.target.value)}>
          <option value="ALL">ALL</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
      </div>
      <DataCollection
        rows={visibleOrders}
        columns={tradeColumns}
        rowKey={(order) => order.id}
        emptyMessage="No trade orders"
        emptyAction={{ label: 'Go trading', href: '/trading' }}
      />
    </AccountShell>
  )
}

export function KycContent({ model }: AccountContentProps) {
  const { account, loginRequired, retrySession, sessionError, sessionMode } = model.accountData

  return (
    <AccountShell account={account} title="Identity verification" summary="KYC is coming soon and currently limited to internal testing.">
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      <section className={css(styles, "account-panel", "account-panel--kyc")}>
        <ShieldCheck size={28} aria-hidden="true" />
        <div>
          <h2>KYC Coming soon</h2>
          <p>Internal test only. Real identity verification is not enabled for user accounts yet.</p>
          <button className={css(styles, "table-action", "table-action--secondary")} type="button" disabled>
            Coming soon
          </button>
        </div>
      </section>
    </AccountShell>
  )
}

export function AccountSettingsContent({ model }: AccountContentProps) {
  const { account, loginRequired, retrySession, sessionError, sessionMode } = model.accountData

  return (
    <AccountShell account={account} title="Settings" summary="Basic profile, notifications and trading preferences.">
      <AccountPageState
        loginRequired={loginRequired}
        onLogin={() => model.navigateTo(model.loginPath)}
        retrySession={retrySession}
        sessionError={sessionError}
        sessionMode={sessionMode}
      />
      <div className={css(styles, "settings-list")}>
        <PreferenceRows icon={<UserRound size={19} aria-hidden="true" />} title="Nickname and avatar" value="FX member" />
        <PreferenceRows icon={<Bell size={19} aria-hidden="true" />} title="Notification language" value="Simplified Chinese" />
        <PreferenceRows icon={<Settings size={19} aria-hidden="true" />} title="Trading preferences" value="Open the latest trading symbol by default" />
      </div>
    </AccountShell>
  )
}

function AccountPageState({ loginRequired, onLogin, retrySession, sessionError, sessionMode }: AccountStateProps) {
  if (loginRequired) {
    return <LoginRequiredState message="Log in to view account data." onLogin={onLogin} />
  }
  if (sessionMode === 'loading') {
    return <LoadingState message="Loading account data." />
  }
  if (sessionMode === 'error') {
    return (
      <ApiErrorState
        error={formatApiError(new Error(sessionError ?? 'Account data unavailable'))}
        onAction={() => void retrySession()}
      />
    )
  }
  return null
}

function AccountShell({
  account,
  title,
  summary,
  children
}: {
  account?: AccountSummary
  title: string
  summary: string
  children: ReactNode
}) {
  return (
    <section className={css(styles, "user-page", "account-shell")} aria-labelledby="account-page-title">
      <aside className={css(styles, "account-sidebar")} aria-label="Account center navigation">
        <div className={css(styles, "account-sidebar__profile")}>
          <span>{account?.baseCurrency?.slice(0, 2) ?? 'FX'}</span>
          <div>
            <strong>{account?.accountType ?? 'Trading account'}</strong>
            <small>{account?.id ? `UID ${shortId(account.id)}` : 'Login required'}</small>
          </div>
        </div>
        <nav>
          {accountNav.map((item) => (
            <NavLink key={item.to} to={item.to} aria-label={'nativeLabel' in item ? `${item.label} ${item.nativeLabel}` : item.label}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className={css(styles, "account-main")}>
        <header className={css(styles, "user-page__header")}>
          <div>
            <h1 id="account-page-title">{title}</h1>
            <p>{summary}</p>
          </div>
        </header>
        {children}
      </main>
    </section>
  )
}

function AccountProfileSummary({ account, positions }: { account?: AccountSummary; positions: PositionResponse[] }) {
  return (
    <section className={css(styles, "account-profile-summary")} aria-label="Account profile summary">
      <div className={css(styles, "account-profile-summary__identity")}>
        <span>{account?.baseCurrency?.slice(0, 2) ?? 'FX'}</span>
        <div>
          <strong>{account?.accountType ?? 'Trading account'}</strong>
          <small>{account?.id ? `UID ${shortId(account.id)} · ${account.status}` : 'No active session'}</small>
        </div>
      </div>
      <div className={css(styles, "account-profile-summary__stats")}>
        <AccountProfileStat label="Equity" value={formatAmount(account?.equity, account?.baseCurrency)} />
        <AccountProfileStat label="Open positions" value={String(positions.length)} />
        <AccountProfileStat label="Margin level" value={account?.marginLevel ? `${account.marginLevel}%` : '-'} />
      </div>
    </section>
  )
}

function AccountProfileStat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function AccountOnboardingSteps({ account }: { account?: AccountSummary }) {
  const steps = [
    {
      title: 'Identity verification',
      note: account?.status === 'ACTIVE' ? 'Account is active. Keep verification details current.' : 'Complete verification to unlock funding and trading limits.',
      action: 'Verify now',
      to: '/account/security/kyc',
      active: account?.status !== 'ACTIVE'
    },
    {
      title: 'Fund account',
      note: 'Review wallet balances and create deposit or withdrawal requests.',
      action: 'View assets',
      to: '/account/assets'
    },
    {
      title: 'Start trading',
      note: 'Open the trading terminal and use the selected backend account.',
      action: 'Go trading',
      to: '/trading'
    }
  ]

  return (
    <section className={css(styles, "account-onboarding-section")}>
      <div className={css(styles, "account-panel__head")}>
        <div>
          <h2>Account workflow</h2>
          <p>Verification, funding and trading stay under the same authenticated account.</p>
        </div>
      </div>
      <div className={css(styles, "account-onboarding-grid")}>
        {steps.map((step, index) => (
          <article className={css(styles, 'account-step-card', step.active && 'account-step-card--active')} key={step.title}>
            <span>{String(index + 1).padStart(2, '0')}</span>
            <div>
              <strong>{step.title}</strong>
              <small>{step.note}</small>
            </div>
            <Link className={css(styles, 'table-action', step.active ? 'table-action--primary' : 'table-action--secondary')} to={step.to}>
              {step.action}
            </Link>
          </article>
        ))}
      </div>
    </section>
  )
}

function AccountAssetActionPanel({ account, walletBalances }: { account?: AccountSummary; walletBalances: WalletBalance[] }) {
  const totalWallet = walletBalances.reduce((total, balance) => total + (toNumber(balance.total) ?? 0), 0)
  const currency = account?.baseCurrency ?? walletBalances[0]?.asset ?? 'USDT'

  return (
    <section className={css(styles, "account-asset-panel")}>
      <div>
        <span>Total assets</span>
        <strong>{formatAmount(totalWallet || account?.balance, currency)}</strong>
        <small>Available {formatAmount(account?.freeMargin, currency)}</small>
      </div>
      <div className={css(styles, "account-action-strip")} aria-label="Asset shortcuts">
        <Link className={css(styles, "table-action", "table-action--primary")} to="/account/orders/funding">
          <ArrowDownCircle size={16} aria-hidden="true" />
          Deposit
        </Link>
        <Link className={css(styles, "table-action", "table-action--secondary")} to="/account/orders/funding">
          <ArrowUpCircle size={16} aria-hidden="true" />
          Withdraw
        </Link>
        <Link className={css(styles, "table-action", "table-action--secondary")} to="/trading">
          <CircleDollarSign size={16} aria-hidden="true" />
          Trade
        </Link>
      </div>
    </section>
  )
}

function AccountDashboardInsights({
  account,
  ledgerEntries,
  orders,
  positions
}: {
  account?: AccountSummary
  ledgerEntries: LedgerEntry[]
  orders: OrderResponse[]
  positions: PositionResponse[]
}) {
  const insights = [
    {
      icon: <ShieldCheck size={20} aria-hidden="true" />,
      title: 'Risk status',
      value: account?.warning ?? account?.status ?? '-',
      note: `Used margin ${formatAmount(account?.usedMargin, account?.baseCurrency)}`
    },
    {
      icon: <CheckCircle2 size={20} aria-hidden="true" />,
      title: 'Open exposure',
      value: formatAmount(account?.positionValue ?? positions.length, account?.positionValue ? account?.baseCurrency : undefined),
      note: `${positions.length} open positions`
    },
    {
      icon: <Bell size={20} aria-hidden="true" />,
      title: 'Recent activity',
      value: `${ledgerEntries.length} ledger rows`,
      note: `${orders.length} trade orders are available`
    }
  ]

  return (
    <div className={css(styles, "account-insight-grid")}>
      {insights.map((insight) => (
        <section className={css(styles, "account-insight-card")} key={insight.title}>
          {insight.icon}
          <div>
            <span>{insight.title}</span>
            <strong>{insight.value}</strong>
            <small>{insight.note}</small>
          </div>
        </section>
      ))}
    </div>
  )
}

function AssetAccountCards({ account, rows }: { account?: AccountSummary; rows: AssetRow[] }) {
  const cards = [
    { title: 'Cash account', value: formatAmount(account?.balance, account?.baseCurrency), note: 'Backend account balance' },
    { title: 'Wallet assets', value: String(rows.length), note: 'Assets returned from wallet balances' },
    { title: 'Available margin', value: formatAmount(account?.marginAvailable ?? account?.freeMargin, account?.baseCurrency), note: 'Available for trading' }
  ]
  return (
    <div className={css(styles, "account-overview-grid")}>
      {cards.map((card) => (
        <section key={card.title} className={css(styles, "account-card")}>
          <CheckCircle2 size={20} aria-hidden="true" />
          <div>
            <span>{card.title}</span>
            <strong>{card.value}</strong>
            <small>{card.note}</small>
          </div>
        </section>
      ))}
    </div>
  )
}

function DemoWalletOperationsLink({ account, walletBalances }: { account?: AccountSummary; walletBalances: WalletBalance[] }) {
  const spotAvailable = walletBalances.find((balance) => balance.walletType === 'SPOT' && balance.asset === 'USDT')?.available

  return (
    <section className={css(styles, "account-panel")}>
      <div className={css(styles, "account-panel__head")}>
        <div>
          <h2>Demo Spot and Perpetual</h2>
          <p>Use the shared wallet controls to transfer Demo USDT or reset the Demo account.</p>
        </div>
        <Link className={css(styles, "table-action", "table-action--primary")} to="/wallet#wallet-assets">
          Open transfer / reset
        </Link>
      </div>
      <small>Spot available: {spotAvailable ?? '-'} USDT · Perpetual available: {account?.freeMargin ?? '-'} USDT</small>
    </section>
  )
}

function RecentLedgerPanel({ entries }: { entries: LedgerEntry[] }) {
  return (
    <section className={css(styles, "account-panel")}>
      <div className={css(styles, "account-panel__head")}>
        <h2>Recent ledger</h2>
        <Link to="/account/orders/funding">All</Link>
      </div>
      <ul className={css(styles, "ledger-list")}>
        {entries.length ? (
          entries.map((entry) => (
            <li key={entry.id}>
              <span>{entry.entryType}</span>
              <strong>
                {entry.amount} {entry.currency}
              </strong>
              <small>{formatTime(entry.createdAt)}</small>
            </li>
          ))
        ) : (
          <li>
            <span>No ledger entries</span>
            <strong>-</strong>
            <small>Create a funding request or trade to generate entries.</small>
          </li>
        )}
      </ul>
    </section>
  )
}

function PreferenceRows({ icon, title, value }: { icon: ReactNode; title: string; value: string }) {
  return (
    <section className={css(styles, "preference-row")}>
      {icon}
      <div>
        <strong>{title}</strong>
        <span>{value}</span>
      </div>
      <button type="button">Edit</button>
    </section>
  )
}

function createAssetColumns(): Array<DataViewColumn<AssetRow>> {
  return [
    { key: 'walletType', label: 'Wallet', sortable: true },
    {
      key: 'currency',
      label: 'Asset',
      sortable: true,
      render: (asset) => (
        <span className={css(styles, "asset-symbol-cell")}>
          <AssetMark symbol={asset.currency} size="sm" />
          <strong>{asset.currency}</strong>
        </span>
      )
    },
    { key: 'balance', label: 'Total', sortable: true },
    { key: 'available', label: 'Available', sortable: true },
    { key: 'frozen', label: 'Locked', sortable: true },
    { key: 'activityCount', label: 'Activity', sortable: true }
  ]
}

function createFundOrderColumns(): Array<DataViewColumn<FundOrder>> {
  return [
    { key: 'createdAt', label: 'Time', sortable: true, render: (order) => formatTime(order.createdAt) },
    { key: 'orderType', label: 'Type', sortable: true },
    { key: 'amount', label: 'Amount', sortable: true },
    { key: 'currency', label: 'Currency', sortable: true },
    { key: 'status', label: 'Status', sortable: true, render: (order) => <StatusChip status={order.status} /> },
    { key: 'paymentMethodId', label: 'Payment method', render: (order) => order.paymentMethodId ?? '-' },
    { key: 'note', label: 'Note', render: (order) => order.note ?? '-' }
  ]
}

function createLedgerColumns(): Array<DataViewColumn<LedgerEntry>> {
  return [
    { key: 'createdAt', label: 'Time', sortable: true, render: (entry) => formatTime(entry.createdAt) },
    { key: 'entryType', label: 'Type', sortable: true },
    { key: 'amount', label: 'Amount', sortable: true },
    { key: 'balanceAfter', label: 'Balance after', sortable: true },
    { key: 'currency', label: 'Currency', sortable: true },
    { key: 'description', label: 'Description', render: (entry) => entry.description ?? '-' }
  ]
}

function createTradeOrderColumns(): Array<DataViewColumn<OrderResponse>> {
  return [
    { key: 'createdAt', label: 'Time', sortable: true, render: (order) => formatTime(order.createdAt) },
    { key: 'symbol', label: 'Symbol', sortable: true },
    { key: 'side', label: 'Side', sortable: true },
    { key: 'orderType', label: 'Type', sortable: true },
    { key: 'status', label: 'Status', sortable: true, render: (order) => <StatusChip status={order.status} /> },
    { key: 'price', label: 'Price', sortable: true, render: (order) => order.price ?? order.executionPrice ?? '-' },
    { key: 'quantity', label: 'Quantity', sortable: true, render: (order) => order.quantity ?? order.lots ?? '-' }
  ]
}

function StatusChip({ status }: { status: string }) {
  const tone =
    status === 'APPROVED' || status === 'FILLED'
      ? 'positive'
      : status === 'PENDING_REVIEW' || status === 'PENDING' || status === 'NEW'
        ? 'warning'
        : status === 'REJECTED' || status === 'CANCELED'
          ? 'negative'
          : ''
  return <span className={css(styles, 'status-chip', tone && `status-chip--${tone}`)}>{status}</span>
}

function getAssetRows(
  account: Pick<AccountSummary, 'baseCurrency' | 'balance' | 'freeMargin'> | null | undefined,
  balances: WalletBalance[],
  entries: LedgerEntry[],
  frozenAmount: number
): AssetRow[] {
  const rows = new Map<string, AssetRow>()

  balances.forEach((balance) => {
    const key = walletKey(balance.walletType, balance.asset)
    rows.set(key, {
      key,
      walletType: balance.walletType,
      currency: balance.asset,
      balance: balance.total,
      available: balance.available,
      frozen: balance.locked,
      activityCount: entries.filter((entry) => entry.currency === balance.asset).length
    })
  })

  const accountKey = account ? walletKey('FX_MARGIN', account.baseCurrency) : ''
  if (account && !rows.has(accountKey)) {
    rows.set(accountKey, {
      key: accountKey,
      walletType: 'FX_MARGIN',
      currency: account.baseCurrency,
      balance: account.balance,
      available: account.freeMargin,
      frozen: frozenAmount.toFixed(2),
      activityCount: entries.filter((entry) => entry.currency === account.baseCurrency).length
    })
  }

  entries.forEach((entry) => {
    const key = walletKey('UNKNOWN', entry.currency)
    if (rows.has(key)) return
    rows.set(key, {
      key,
      walletType: 'UNKNOWN',
      currency: entry.currency,
      balance: '-',
      available: '-',
      frozen: '-',
      activityCount: entries.filter((item) => item.currency === entry.currency).length
    })
  })

  return Array.from(rows.values())
}

function formatAmount(value: Amount | null | undefined, currency?: string) {
  if (value === null || value === undefined || value === '') return '-'
  return currency ? `${value} ${currency}` : String(value)
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '-'
}

function nullableString(value: FormDataEntryValue | null) {
  const stringValue = String(value ?? '').trim()
  return stringValue ? stringValue : null
}

function shortId(value: string) {
  return value.length > 8 ? value.slice(0, 8) : value
}

function walletKey(walletType: string | undefined, asset: string) {
  return `${walletType || 'UNKNOWN'}:${asset}`
}
