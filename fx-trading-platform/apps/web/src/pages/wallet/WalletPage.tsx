import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { ArrowDownToLine, ArrowUpFromLine, BookUser, RefreshCcw, ShieldCheck, SlidersHorizontal, WalletCards } from 'lucide-react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'

import { AssetMark } from '../../shared-widgets/asset/AssetMark'
import { DataTable, type DataTableColumn } from '../../components/user-page/DataTable'
import { ApiErrorState, LoadingState, LoginRequiredState } from '../../components/user-page/PageState'
import { formatApiError } from '../../components/user-page/userPageModels'
import { translateCoreMessage } from '../../routes/shared/translateCoreMessage'
import {
  createFundOrder,
  filterByStatus,
  getFundOrders,
  toNumber,
  useWalletController,
  type Amount,
  type AssetLedgerEntry,
  type FundOrder,
  type LedgerEntry,
  type WalletBalance
} from '@fx-platform/frontend-core'
import { DemoResetDialog } from './DemoResetDialog'
import { TransferDialog } from './TransferDialog'

const fundOrderStatuses = ['ALL', 'PENDING_REVIEW', 'PENDING', 'APPROVED', 'REJECTED']
const fundOrderTypes = ['ALL', 'RECHARGE', 'WITHDRAWAL']

type AssetRow = {
  key: string
  walletType: string
  currency: string
  balance: Amount
  available: Amount
  frozen: Amount
  activityCount: number
}

export function WalletPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const {
    token,
    account,
    accountId,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances,
    sessionMode,
    sessionError: sessionErrorMessage,
    loginRequired,
    retrySession,
    transferDirection,
    transferAmount,
    transferRequestId,
    transferPending,
    transferError: transferErrorMessage,
    resetRequestId,
    resetPending,
    resetError: resetErrorMessage,
    notice: walletNotice,
    beginTransfer,
    changeTransferDirection,
    changeTransferAmount,
    submitTransfer,
    beginReset,
    submitReset
  } = useWalletController()
  const sessionError = translateCoreMessage(sessionErrorMessage, t)
  const transferError = translateCoreMessage(transferErrorMessage, t)
  const resetError = translateCoreMessage(resetErrorMessage, t)
  const translatedWalletNotice = translateCoreMessage(walletNotice, t)
  const [fundOrders, setFundOrders] = useState<FundOrder[]>([])
  const [fundOrdersLoading, setFundOrdersLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [ledgerTypeFilter, setLedgerTypeFilter] = useState('ALL')
  const [ledgerWalletTypeFilter, setLedgerWalletTypeFilter] = useState('ALL')
  const [ledgerCurrencyFilter, setLedgerCurrencyFilter] = useState('ALL')
  const [ledgerFromDate, setLedgerFromDate] = useState('')
  const [ledgerToDate, setLedgerToDate] = useState('')
  const [selectedAsset, setSelectedAsset] = useState<string | null>(null)
  const [fundOrderType, setFundOrderType] = useState<'RECHARGE' | 'WITHDRAWAL'>('RECHARGE')
  const [transferOpen, setTransferOpen] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ReturnType<typeof formatApiError> | null>(null)

  const currency = account?.baseCurrency ?? 'USDT'
  const pendingWithdrawalAmount = useMemo(
    () =>
      fundOrders
        .filter((order) => order.orderType === 'WITHDRAWAL')
        .filter((order) => order.status === 'PENDING_REVIEW' || order.status === 'PENDING')
        .reduce((total, order) => total + (toNumber(order.amount) ?? 0), 0),
    [fundOrders]
  )
  const frozenAmount = (toNumber(account?.usedMargin) ?? 0) + pendingWithdrawalAmount
  const spotAvailable =
    walletBalances.find((balance) => balance.walletType === 'SPOT' && balance.asset === 'USDT')?.available ?? 0
  const perpAvailable = account?.freeMargin ?? 0
  const transferAvailable = transferDirection === 'SPOT_TO_PERP' ? spotAvailable : perpAvailable
  const visibleFundOrders = useMemo(() => {
    return filterByStatus(fundOrders, statusFilter).filter((order) => typeFilter === 'ALL' || order.orderType === typeFilter)
  }, [fundOrders, statusFilter, typeFilter])
  const assetRows = useMemo(
    () => getAssetRows(account, walletBalances, assetLedgerEntries, frozenAmount),
    [account, assetLedgerEntries, frozenAmount, walletBalances]
  )
  const selectedAssetRow = useMemo(
    () => assetRows.find((asset) => asset.key === selectedAsset) ?? assetRows[0],
    [assetRows, selectedAsset]
  )
  const ledgerTypeOptions = useMemo(() => getUniqueValues(ledgerEntries.map((entry) => entry.entryType)), [ledgerEntries])
  const ledgerWalletTypeOptions = useMemo(
    () => getUniqueValues(ledgerEntries.map((entry) => entry.walletType).filter((walletType): walletType is string => Boolean(walletType))),
    [ledgerEntries]
  )
  const ledgerCurrencyOptions = useMemo(() => getUniqueValues(ledgerEntries.map((entry) => entry.currency)), [ledgerEntries])
  const visibleLedgerEntries = useMemo(
    () =>
      ledgerEntries
        .filter((entry) => ledgerTypeFilter === 'ALL' || entry.entryType === ledgerTypeFilter)
        .filter((entry) => ledgerWalletTypeFilter === 'ALL' || entry.walletType === ledgerWalletTypeFilter)
        .filter((entry) => ledgerCurrencyFilter === 'ALL' || entry.currency === ledgerCurrencyFilter)
        .filter((entry) => matchesDateRange(entry.createdAt, ledgerFromDate, ledgerToDate)),
    [ledgerCurrencyFilter, ledgerEntries, ledgerFromDate, ledgerToDate, ledgerTypeFilter, ledgerWalletTypeFilter]
  )
  const fundOrderColumns = useMemo(() => createFundOrderColumns(t), [t])
  const assetTableColumns = useMemo(() => createAssetColumns(setSelectedAsset, t), [t])
  const ledgerTableColumns = useMemo(() => createLedgerColumns(t), [t])

  const loadFundOrders = async () => {
    if (!token || !accountId) return
    setFundOrdersLoading(true)
    setApiError(null)
    try {
      setFundOrders(await getFundOrders(accountId, token))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setFundOrdersLoading(false)
    }
  }

  useEffect(() => {
    void loadFundOrders()
  }, [token, accountId])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!token || !accountId) return
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    setNotice(null)
    setApiError(null)
    try {
      const createdFundOrder = await createFundOrder(
        {
          accountId,
          orderType: form.get('orderType') === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'RECHARGE',
          amount: String(form.get('amount') ?? ''),
          currency: String(form.get('currency') ?? currency),
          note: String(form.get('note') ?? '')
        },
        token
      )
      setFundOrders((current) => [createdFundOrder, ...current.filter((order) => order.id !== createdFundOrder.id)])
      formElement.reset()
      setFundOrderType('RECHARGE')
      setNotice(t('assets.requestSubmitted'))
      await loadFundOrders()
    } catch (error) {
      setApiError(formatApiError(error))
    }
  }

  const openTransfer = () => {
    setNotice(null)
    beginTransfer()
    setTransferOpen(true)
  }

  const handleTransfer = async () => {
    try {
      const transfer = await submitTransfer(transferAvailable)
      if (transfer) setTransferOpen(false)
    } catch {
      // The core controller preserves the API error as a message descriptor.
    }
  }

  const openReset = () => {
    setNotice(null)
    beginReset()
    setResetOpen(true)
  }

  const handleReset = async () => {
    try {
      const result = await submitReset()
      if (result) setResetOpen(false)
    } catch {
      // The core controller preserves the API error as a message descriptor.
    }
  }

  if (loginRequired) {
    return (
      <section className="user-page wallet-page wallet-login-gate">
        <LoginRequiredState
          message={t('assets.loginMessage')}
          onLogin={() => navigate('/login?redirect=/wallet')}
        />
      </section>
    )
  }

  return (
    <section className="user-page wallet-page">
      <header className="user-page__header wallet-page__hero">
        <div>
          <h1>{t('assets.walletTitle')}</h1>
          <p>{t('assets.walletSummary')}</p>
        </div>
        <button type="button" className="table-action table-action--secondary" onClick={() => void Promise.all([retrySession(), loadFundOrders()])}>
          <RefreshCcw size={15} aria-hidden="true" />
          {t('common.refresh')}
        </button>
      </header>

      <div className="wallet-workbench">
        <aside className="wallet-sidebar" aria-label={t('assets.walletTitle')}>
          <nav>
            <a href="#wallet-overview">{t('assets.overview')}</a>
            <a href="#wallet-assets">{t('assets.asset')}</a>
            <a href="#wallet-funding">{t('assets.fundOrdersTitle')}</a>
            <a href="#wallet-ledger">{t('assets.ledgerTitle')}</a>
            <a href="#wallet-address-book">{t('assets.addressBookTitle')}</a>
          </nav>
        </aside>

        <div className="wallet-workbench__main">
      {sessionMode === 'loading' ? <LoadingState message={t('assets.loadingAccount')} /> : null}
      {sessionMode === 'error' ? (
        <ApiErrorState
          error={formatApiError(new Error(sessionError ?? t('assets.unavailable')))}
          onAction={() => void retrySession()}
        />
      ) : null}
      {apiError ? <ApiErrorState error={apiError} onAction={() => void loadFundOrders()} /> : null}
      {notice || translatedWalletNotice ? <div className="user-page__notice">{notice ?? translatedWalletNotice}</div> : null}

      <section id="wallet-overview" className="wallet-hero-grid" aria-label={t('assets.overview')}>
        <article className="wallet-balance-card">
          <span>{t('assets.balance')}</span>
          <strong>{account?.balance ?? '-'}</strong>
          <small>
            {currency} · {t('assets.available')} {account?.freeMargin ?? '-'}
          </small>
          <div className="wallet-balance-card__actions">
            <button type="button" className="table-action table-action--primary" onClick={() => setFundOrderType('RECHARGE')}>
              {t('assets.deposit')}
            </button>
            <button type="button" className="table-action table-action--secondary" onClick={() => setFundOrderType('WITHDRAWAL')}>
              {t('assets.withdraw')}
            </button>
          </div>
        </article>

        <div className="wallet-account-grid">
          <Metric label={t('assets.available')} value={account?.freeMargin} />
          <Metric label={t('assets.frozen')} value={`${frozenAmount.toFixed(2)} ${currency}`} />
          <Metric label={t('assets.usedMargin')} value={account?.usedMargin} />
          <Metric label={t('assets.pendingWithdrawal')} value={`${pendingWithdrawalAmount.toFixed(2)} ${currency}`} />
        </div>

        <article className="wallet-risk-card">
          <ShieldCheck size={22} aria-hidden="true" />
          <div>
            <strong>{t('assets.whitelist')}</strong>
            <span>{t('assets.whitelistDescription')}</span>
          </div>
          <Link className="table-action table-action--secondary" to="/account/security/kyc">
            {t('assets.viewSecurityCenter')}
          </Link>
        </article>
      </section>

      <div className="wallet-simulation-note" role="note">
        <strong>{t('assets.simulationTitle')}</strong>
        <span>{t('assets.simulationDescription')}</span>
      </div>

      <section id="wallet-assets" className="user-page__events wallet-assets-panel">
        <div className="settings-section-head">
          <div>
            <h2>{t('assets.overview')}</h2>
            <p>{t('assets.overviewDescription')}</p>
          </div>
          <WalletCards size={20} aria-hidden="true" />
        </div>
        <div className="user-page__metrics">
          <Metric label={t('assets.balance')} value={account?.balance} />
          <Metric label={t('assets.available')} value={account?.freeMargin} />
          <Metric label={t('assets.frozen')} value={`${frozenAmount.toFixed(2)} ${currency}`} />
          <Metric label={t('assets.usedMargin')} value={account?.usedMargin} />
          <Metric label={t('assets.pendingWithdrawal')} value={`${pendingWithdrawalAmount.toFixed(2)} ${currency}`} />
          <Metric label={t('common.currency')} value={currency} />
        </div>
        <DataTable
          rows={assetRows}
          columns={assetTableColumns}
          rowKey={(asset) => asset.key}
          emptyMessage={t('assets.emptyAssets')}
          emptyAction={{ label: t('assets.goDeposit'), href: '/wallet' }}
          pageSize={5}
        />
        <div className="user-page__actions">
          <button
            type="button"
            className="table-action table-action--primary"
            disabled={!token || !accountId}
            onClick={openTransfer}
          >
            Transfer Spot / Perpetual
          </button>
          <button
            type="button"
            className="table-action table-action--danger"
            disabled={!token || !accountId}
            onClick={openReset}
          >
            Reset Demo account
          </button>
        </div>
        {selectedAssetRow ? (
          <div className="wallet-asset-detail" aria-label={t('assets.singleAssetDetail')}>
            <div>
              <strong>{selectedAssetRow.walletType} {selectedAssetRow.currency} {t('assets.singleAssetDetail')}</strong>
              <span>
                {t('assets.singleAssetSummary', {
                  balance: selectedAssetRow.balance,
                  available: selectedAssetRow.available,
                  frozen: selectedAssetRow.frozen
                })}
              </span>
            </div>
            <button type="button" className="table-action table-action--secondary" onClick={() => setFundOrderType('RECHARGE')}>
              {t('assets.deposit')}
            </button>
            <button type="button" className="table-action table-action--danger" onClick={() => setFundOrderType('WITHDRAWAL')}>
              {t('assets.withdraw')}
            </button>
          </div>
        ) : null}
      </section>

      <section className="wallet-action-grid" aria-label={t('assets.quickEntries')}>
        <button className="wallet-action-card" type="button" onClick={() => setFundOrderType('RECHARGE')}>
          <ArrowDownToLine size={20} aria-hidden="true" />
          <strong>{t('assets.depositEntry')}</strong>
          <span>{t('assets.depositDescription')}</span>
        </button>
        <button className="wallet-action-card wallet-action-card--danger" type="button" onClick={() => setFundOrderType('WITHDRAWAL')}>
          <ArrowUpFromLine size={20} aria-hidden="true" />
          <strong>{t('assets.withdrawEntry')}</strong>
          <span>{t('assets.withdrawDescription')}</span>
        </button>
        <Link className="wallet-action-card" to="/security">
          <ShieldCheck size={20} aria-hidden="true" />
          <strong>{t('assets.whitelist')}</strong>
          <span>{t('assets.whitelistDescription')}</span>
        </Link>
        <Link className="wallet-action-card" to="/settings">
          <SlidersHorizontal size={20} aria-hidden="true" />
          <strong>{t('assets.preferences')}</strong>
          <span>{t('assets.preferencesDescription')}</span>
        </Link>
        <button className="wallet-action-card" type="button" disabled>
          <BookUser size={20} aria-hidden="true" />
          <strong>{t('assets.addressBookEntry')}</strong>
          <span>{t('assets.addressBookDescription')}</span>
        </button>
      </section>

      <section id="wallet-funding" className="user-page__events">
        <div className="settings-section-head">
          <div>
            <h2>{t('assets.fundOrdersTitle')}</h2>
            <p>{t('assets.fundOrdersDescription')}</p>
          </div>
        </div>
        <form className="user-page__form" onSubmit={handleSubmit}>
          <label>
            <span>{t('common.type')}</span>
            <select name="orderType" value={fundOrderType} onChange={(event) => setFundOrderType(event.target.value === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'RECHARGE')}>
              <option value="RECHARGE">RECHARGE</option>
              <option value="WITHDRAWAL">WITHDRAWAL</option>
            </select>
          </label>
          <label>
            <span>{t('common.amount')}</span>
            <input name="amount" type="number" min="0.01" step="0.01" required />
          </label>
          <label>
            <span>{t('common.currency')}</span>
            <select name="currency" defaultValue={currency}>
              <option value={currency}>{currency}</option>
              <option value="USDT">USDT</option>
              <option value="USD">USD</option>
            </select>
          </label>
          <label>
            <span>{t('common.note')}</span>
            <input name="note" placeholder={t('assets.notePlaceholder')} />
          </label>
          <button
            type="submit"
            className={`table-action ${fundOrderType === 'WITHDRAWAL' ? 'table-action--danger' : 'table-action--primary'}`}
            disabled={!token || !accountId}
          >
            {fundOrderType === 'WITHDRAWAL' ? t('assets.submitWithdrawal') : t('assets.submitDeposit')}
          </button>
        </form>

        <div className="user-page__toolbar" aria-label={t('assets.fundOrderFilter')}>
          <label>
            <span>{t('common.status')}</span>
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
              {fundOrderStatuses.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>{t('common.type')}</span>
            <select value={typeFilter} onChange={(event) => setTypeFilter(event.target.value)}>
              {fundOrderTypes.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </label>
        </div>

        {fundOrdersLoading ? <LoadingState message={t('assets.loadingFundOrders')} /> : null}
        <DataTable
          rows={visibleFundOrders}
          columns={fundOrderColumns}
          rowKey={(order) => order.id}
          emptyMessage={t('assets.emptyFundOrders')}
          emptyAction={{ label: t('assets.goDeposit'), href: '/wallet' }}
        />
      </section>

      <section id="wallet-ledger" className="user-page__events">
        <div className="settings-section-head">
          <div>
            <h2>{t('assets.ledgerTitle')}</h2>
            <p>{t('assets.ledgerDescription')}</p>
          </div>
        </div>
        <div className="user-page__toolbar" aria-label={t('assets.ledgerFilter')}>
          <label>
            <span>{t('common.type')}</span>
            <select value={ledgerTypeFilter} onChange={(event) => setLedgerTypeFilter(event.target.value)}>
              <option value="ALL">ALL</option>
              {ledgerTypeOptions.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>Wallet</span>
            <select value={ledgerWalletTypeFilter} onChange={(event) => setLedgerWalletTypeFilter(event.target.value)}>
              <option value="ALL">ALL</option>
              {ledgerWalletTypeOptions.map((walletType) => (
                <option key={walletType} value={walletType}>
                  {walletType}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>{t('common.currency')}</span>
            <select value={ledgerCurrencyFilter} onChange={(event) => setLedgerCurrencyFilter(event.target.value)}>
              <option value="ALL">ALL</option>
              {ledgerCurrencyOptions.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>{t('assets.startTime')}</span>
            <input type="date" value={ledgerFromDate} onChange={(event) => setLedgerFromDate(event.target.value)} />
          </label>
          <label>
            <span>{t('assets.endTime')}</span>
            <input type="date" value={ledgerToDate} onChange={(event) => setLedgerToDate(event.target.value)} />
          </label>
          <button type="button" className="table-action table-action--secondary" onClick={clearLedgerFilters}>
            {t('common.clearFilters')}
          </button>
        </div>
        <DataTable
          rows={visibleLedgerEntries}
          columns={ledgerTableColumns}
          rowKey={(entry) => entry.id}
          emptyMessage={t('assets.emptyLedger')}
          emptyAction={{ label: t('assets.goDeposit'), href: '/wallet' }}
        />
      </section>

      <section id="wallet-address-book" className="user-page__events">
        <div className="settings-section-head">
          <div>
            <h2>{t('assets.addressBookTitle')}</h2>
            <p>{t('assets.addressBookIntro')}</p>
          </div>
          <BookUser size={20} aria-hidden="true" />
        </div>
        <div className="wallet-address-book">
          <div>
            <strong>{t('assets.savedWithdrawAddress')}</strong>
            <span>{t('common.soon')}</span>
          </div>
          <p>{t('assets.addressBookFuture')}</p>
          <Link className="table-action table-action--secondary" to="/security">
            {t('assets.viewSecurityCenter')}
          </Link>
        </div>
      </section>
        </div>
      </div>
      <TransferDialog
        open={transferOpen}
        direction={transferDirection}
        amount={transferAmount}
        available={transferAvailable}
        requestId={transferRequestId}
        pending={transferPending}
        error={transferError}
        onDirectionChange={changeTransferDirection}
        onAmountChange={changeTransferAmount}
        onClose={() => setTransferOpen(false)}
        onConfirm={() => void handleTransfer()}
      />
      <DemoResetDialog
        open={resetOpen}
        requestId={resetRequestId}
        pending={resetPending}
        error={resetError}
        onClose={() => setResetOpen(false)}
        onConfirm={() => void handleReset()}
      />
    </section>
  )

  function clearLedgerFilters() {
    setLedgerTypeFilter('ALL')
    setLedgerWalletTypeFilter('ALL')
    setLedgerCurrencyFilter('ALL')
    setLedgerFromDate('')
    setLedgerToDate('')
  }
}

function Metric({ label, value }: { label: string; value: Amount | string | number | null | undefined }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value ?? '-'}</strong>
    </div>
  )
}

function createFundOrderColumns(t: TFunction): Array<DataTableColumn<FundOrder>> {
  return [
    { key: 'createdAt', label: t('common.time'), sortable: true, render: (order) => formatTime(order.createdAt) },
    { key: 'orderType', label: t('common.type'), sortable: true },
    { key: 'amount', label: t('common.amount'), sortable: true },
    { key: 'currency', label: t('common.currency'), sortable: true },
    { key: 'status', label: t('common.status'), sortable: true, render: (order) => <StatusChip status={order.status} /> },
    { key: 'reviewReason', label: t('assets.reviewReason'), render: (order) => order.reviewReason ?? '-' },
    { key: 'note', label: t('common.note'), render: (order) => order.note ?? '-' }
  ]
}

function createAssetColumns(setSelectedAsset: (currency: string) => void, t: TFunction): Array<DataTableColumn<AssetRow>> {
  return [
    { key: 'walletType', label: 'Wallet', sortable: true },
    {
      key: 'currency',
      label: t('assets.asset'),
      sortable: true,
      render: (asset) => (
        <span className="asset-symbol-cell">
          <AssetMark symbol={asset.currency} size="sm" />
          <strong>{asset.currency}</strong>
        </span>
      )
    },
    { key: 'balance', label: t('assets.balance'), sortable: true },
    { key: 'available', label: t('assets.available'), sortable: true },
    { key: 'frozen', label: t('assets.frozen'), sortable: true },
    { key: 'activityCount', label: t('assets.activityCount'), sortable: true },
    {
      key: 'actions',
      label: t('common.action'),
      render: (asset) => (
        <button type="button" className="table-action table-action--secondary" onClick={() => setSelectedAsset(asset.key)}>
          {t('common.viewDetails')}
        </button>
      )
    }
  ]
}

function createLedgerColumns(t: TFunction): Array<DataTableColumn<LedgerEntry>> {
  return [
    { key: 'createdAt', label: t('common.time'), sortable: true, render: (entry) => formatTime(entry.createdAt) },
    { key: 'walletType', label: 'Wallet', sortable: true, render: (entry) => entry.walletType ?? '-' },
    { key: 'entryType', label: t('common.type'), sortable: true },
    { key: 'amount', label: t('common.amount'), sortable: true },
    { key: 'balanceAfter', label: t('assets.balance'), sortable: true },
    { key: 'currency', label: t('common.currency'), sortable: true },
    { key: 'description', label: t('assets.description'), render: (entry) => entry.description ?? '-' }
  ]
}

function StatusChip({ status }: { status: string }) {
  const tone =
    status === 'APPROVED' ? 'positive' : status === 'PENDING_REVIEW' || status === 'PENDING' ? 'warning' : status === 'REJECTED' ? 'negative' : ''
  return <span className={`status-chip${tone ? ` status-chip--${tone}` : ''}`}>{status}</span>
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '-'
}

function getAssetRows(
  account: { baseCurrency: string; balance: Amount; freeMargin: Amount } | null | undefined,
  balances: WalletBalance[],
  entries: AssetLedgerEntry[],
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
      activityCount: entries.filter(
        (entry) => entry.walletType === balance.walletType && entry.asset === balance.asset
      ).length
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
      activityCount: entries.filter(
        (entry) => entry.walletType === 'FX_MARGIN' && entry.asset === account.baseCurrency
      ).length
    })
  }

  entries.forEach((entry) => {
    const key = walletKey(entry.walletType, entry.asset)
    if (rows.has(key)) return
    rows.set(key, {
      key,
      walletType: entry.walletType ?? 'UNKNOWN',
      currency: entry.asset,
      balance: '-',
      available: '-',
      frozen: '-',
      activityCount: entries.filter(
        (item) => item.walletType === entry.walletType && item.asset === entry.asset
      ).length
    })
  })

  return Array.from(rows.values())
}

function getUniqueValues(values: string[]) {
  return Array.from(new Set(values.filter(Boolean))).sort()
}

function matchesDateRange(createdAt: string | null | undefined, fromDate: string, toDate: string) {
  const day = createdAt?.slice(0, 10)
  if (!day) return !fromDate && !toDate
  if (fromDate && day < fromDate) return false
  if (toDate && day > toDate) return false
  return true
}

function walletKey(walletType: string | undefined, asset: string) {
  return `${walletType || 'UNKNOWN'}:${asset}`
}
