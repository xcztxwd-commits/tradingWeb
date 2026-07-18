import { ArrowDownToLine, ArrowUpFromLine, BookUser, RefreshCcw, ShieldCheck, SlidersHorizontal, WalletCards } from 'lucide-react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import type { DataViewColumn } from '@fx-platform/ui'
import type { Amount, FundOrder, LedgerEntry } from '@fx-platform/frontend-core'

import { ApiErrorState, LoadingState, LoginRequiredState } from '../../components/user-page/PageState'
import type { AssetRow, FundOrderForm, WalletRouteModel } from '../../routes/wallet/walletRoute.types'
import { AssetMark } from '../asset/AssetMark'
import type { RouteDataCollectionRenderer } from '../data/RouteDataCollection'
import { DemoResetDialog } from './DemoResetDialog'
import { TransferDialog } from './TransferDialog'

export function WalletRouteContent({
  model,
  renderDataCollection
}: {
  model: WalletRouteModel
  renderDataCollection: RouteDataCollectionRenderer
}) {
  const { t } = useTranslation()
  if (model.loginRequired) {
    return (
      <section className="user-page wallet-page wallet-login-gate">
        <LoginRequiredState message={t('assets.loginMessage')} onLogin={model.openLogin} />
      </section>
    )
  }

  return (
    <section className="user-page wallet-page">
      <header className="user-page__header wallet-page__hero">
        <div><h1>{t('assets.walletTitle')}</h1><p>{t('assets.walletSummary')}</p></div>
        <button type="button" className="table-action table-action--secondary" onClick={() => void model.refresh()}>
          <RefreshCcw size={15} aria-hidden="true" />{t('common.refresh')}
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
          {model.sessionMode === 'loading' ? <LoadingState message={t('assets.loadingAccount')} /> : null}
          {model.sessionMode === 'error' ? (
            <ApiErrorState error={{ title: 'WALLET_LOAD_FAILED', message: model.sessionError ?? t('assets.unavailable') }} onAction={() => void model.refresh()} />
          ) : null}
          {model.apiError ? <ApiErrorState error={model.apiError} onAction={() => void model.retryFundOrders()} /> : null}
          {model.notice ? <div className="user-page__notice">{model.notice}</div> : null}

          <section id="wallet-overview" className="wallet-hero-grid" aria-label={t('assets.overview')}>
            <article className="wallet-balance-card">
              <span>{t('assets.balance')}</span>
              <strong>{model.account?.balance ?? '-'}</strong>
              <small>{model.currency} · {t('assets.available')} {model.account?.freeMargin ?? '-'}</small>
              <div className="wallet-balance-card__actions">
                <button type="button" className="table-action table-action--primary" onClick={() => model.setFundOrderField('orderType', 'RECHARGE')}>{t('assets.deposit')}</button>
                <button type="button" className="table-action table-action--secondary" onClick={() => model.setFundOrderField('orderType', 'WITHDRAWAL')}>{t('assets.withdraw')}</button>
              </div>
            </article>
            <div className="wallet-account-grid">
              <Metric label={t('assets.available')} value={model.account?.freeMargin} />
              <Metric label={t('assets.frozen')} value={`${model.frozenAmount.toFixed(2)} ${model.currency}`} />
              <Metric label={t('assets.usedMargin')} value={model.account?.usedMargin} />
              <Metric label={t('assets.pendingWithdrawal')} value={`${model.pendingWithdrawalAmount.toFixed(2)} ${model.currency}`} />
            </div>
            <article className="wallet-risk-card">
              <ShieldCheck size={22} aria-hidden="true" />
              <div><strong>{t('assets.whitelist')}</strong><span>{t('assets.whitelistDescription')}</span></div>
              <Link className="table-action table-action--secondary" to="/account/security/kyc">{t('assets.viewSecurityCenter')}</Link>
            </article>
          </section>

          <div className="wallet-simulation-note" role="note"><strong>{t('assets.simulationTitle')}</strong><span>{t('assets.simulationDescription')}</span></div>

          <section id="wallet-assets" className="user-page__events wallet-assets-panel">
            <SectionHead title={t('assets.overview')} description={t('assets.overviewDescription')} icon={<WalletCards size={20} aria-hidden="true" />} />
            <div className="user-page__metrics">
              <Metric label={t('assets.balance')} value={model.account?.balance} />
              <Metric label={t('assets.available')} value={model.account?.freeMargin} />
              <Metric label={t('assets.frozen')} value={`${model.frozenAmount.toFixed(2)} ${model.currency}`} />
              <Metric label={t('assets.usedMargin')} value={model.account?.usedMargin} />
              <Metric label={t('assets.pendingWithdrawal')} value={`${model.pendingWithdrawalAmount.toFixed(2)} ${model.currency}`} />
              <Metric label={t('common.currency')} value={model.currency} />
            </div>
            {renderDataCollection<AssetRow>({
              rows: model.assetRows,
              columns: createAssetColumns(model.setSelectedAsset, t),
              rowKey: (asset) => asset.key,
              emptyMessage: t('assets.emptyAssets'),
              emptyAction: { label: t('assets.goDeposit'), href: '/wallet' },
              pageSize: 5
            })}
            <div className="user-page__actions">
              <button type="button" className="table-action table-action--primary" disabled={!model.token || !model.accountId} onClick={model.openTransfer}>Transfer Spot / Perpetual</button>
              <button type="button" className="table-action table-action--danger" disabled={!model.token || !model.accountId} onClick={model.openReset}>Reset Demo account</button>
            </div>
            {model.selectedAssetRow ? (
              <div className="wallet-asset-detail" aria-label={t('assets.singleAssetDetail')}>
                <div>
                  <strong>{model.selectedAssetRow.walletType} {model.selectedAssetRow.currency} {t('assets.singleAssetDetail')}</strong>
                  <span>{t('assets.singleAssetSummary', {
                    balance: model.selectedAssetRow.balance,
                    available: model.selectedAssetRow.available,
                    frozen: model.selectedAssetRow.frozen
                  })}</span>
                </div>
                <button type="button" className="table-action table-action--secondary" onClick={() => model.setFundOrderField('orderType', 'RECHARGE')}>{t('assets.deposit')}</button>
                <button type="button" className="table-action table-action--danger" onClick={() => model.setFundOrderField('orderType', 'WITHDRAWAL')}>{t('assets.withdraw')}</button>
              </div>
            ) : null}
          </section>

          <section className="wallet-action-grid" aria-label={t('assets.quickEntries')}>
            <WalletAction icon={<ArrowDownToLine size={20} aria-hidden="true" />} title={t('assets.depositEntry')} description={t('assets.depositDescription')} onClick={() => model.setFundOrderField('orderType', 'RECHARGE')} />
            <WalletAction danger icon={<ArrowUpFromLine size={20} aria-hidden="true" />} title={t('assets.withdrawEntry')} description={t('assets.withdrawDescription')} onClick={() => model.setFundOrderField('orderType', 'WITHDRAWAL')} />
            <Link className="wallet-action-card" to="/security"><ShieldCheck size={20} aria-hidden="true" /><strong>{t('assets.whitelist')}</strong><span>{t('assets.whitelistDescription')}</span></Link>
            <Link className="wallet-action-card" to="/settings"><SlidersHorizontal size={20} aria-hidden="true" /><strong>{t('assets.preferences')}</strong><span>{t('assets.preferencesDescription')}</span></Link>
            <button className="wallet-action-card" type="button" disabled><BookUser size={20} aria-hidden="true" /><strong>{t('assets.addressBookEntry')}</strong><span>{t('assets.addressBookDescription')}</span></button>
          </section>

          <section id="wallet-funding" className="user-page__events">
            <SectionHead title={t('assets.fundOrdersTitle')} description={t('assets.fundOrdersDescription')} />
            <FundOrderFormView model={model} />
            <div className="user-page__toolbar" aria-label={t('assets.fundOrderFilter')}>
              <label><span>{t('common.status')}</span><select value={model.statusFilter} onChange={(event) => model.setStatusFilter(event.target.value)}>{model.fundOrderStatuses.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
              <label><span>{t('common.type')}</span><select value={model.typeFilter} onChange={(event) => model.setTypeFilter(event.target.value)}>{model.fundOrderTypes.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
            </div>
            {model.fundOrdersLoading ? <LoadingState message={t('assets.loadingFundOrders')} /> : null}
            {renderDataCollection<FundOrder>({
              rows: model.visibleFundOrders,
              columns: createFundOrderColumns(t),
              rowKey: (order) => order.id,
              emptyMessage: t('assets.emptyFundOrders'),
              emptyAction: { label: t('assets.goDeposit'), href: '/wallet' }
            })}
          </section>

          <section id="wallet-ledger" className="user-page__events">
            <SectionHead title={t('assets.ledgerTitle')} description={t('assets.ledgerDescription')} />
            <div className="user-page__toolbar" aria-label={t('assets.ledgerFilter')}>
              <FilterSelect label={t('common.type')} value={model.ledgerTypeFilter} options={model.ledgerTypeOptions} onChange={model.setLedgerTypeFilter} />
              <FilterSelect label="Wallet" value={model.ledgerWalletTypeFilter} options={model.ledgerWalletTypeOptions} onChange={model.setLedgerWalletTypeFilter} />
              <FilterSelect label={t('common.currency')} value={model.ledgerCurrencyFilter} options={model.ledgerCurrencyOptions} onChange={model.setLedgerCurrencyFilter} />
              <label><span>{t('assets.startTime')}</span><input type="date" value={model.ledgerFromDate} onChange={(event) => model.setLedgerFromDate(event.target.value)} /></label>
              <label><span>{t('assets.endTime')}</span><input type="date" value={model.ledgerToDate} onChange={(event) => model.setLedgerToDate(event.target.value)} /></label>
              <button type="button" className="table-action table-action--secondary" onClick={model.clearLedgerFilters}>{t('common.clearFilters')}</button>
            </div>
            {renderDataCollection<LedgerEntry>({
              rows: model.visibleLedgerEntries,
              columns: createLedgerColumns(t),
              rowKey: (entry) => entry.id,
              emptyMessage: t('assets.emptyLedger'),
              emptyAction: { label: t('assets.goDeposit'), href: '/wallet' }
            })}
          </section>

          <section id="wallet-address-book" className="user-page__events">
            <SectionHead title={t('assets.addressBookTitle')} description={t('assets.addressBookIntro')} icon={<BookUser size={20} aria-hidden="true" />} />
            <div className="wallet-address-book">
              <div><strong>{t('assets.savedWithdrawAddress')}</strong><span>{t('common.soon')}</span></div>
              <p>{t('assets.addressBookFuture')}</p>
              <Link className="table-action table-action--secondary" to="/security">{t('assets.viewSecurityCenter')}</Link>
            </div>
          </section>
        </div>
      </div>

      <TransferDialog
        open={model.transferOpen}
        direction={model.transferDirection}
        amount={model.transferAmount}
        available={model.transferAvailable}
        requestId={model.transferRequestId}
        pending={model.transferPending}
        error={model.transferError}
        onDirectionChange={model.changeTransferDirection}
        onAmountChange={model.changeTransferAmount}
        onClose={model.closeTransfer}
        onConfirm={() => void model.submitTransfer()}
      />
      <DemoResetDialog
        open={model.resetOpen}
        requestId={model.resetRequestId}
        pending={model.resetPending}
        error={model.resetError}
        onClose={model.closeReset}
        onConfirm={() => void model.submitReset()}
      />
    </section>
  )
}

function FundOrderFormView({ model }: { model: WalletRouteModel }) {
  const { t } = useTranslation()
  const form = model.fundOrderForm
  return (
    <form className="user-page__form" onSubmit={(event) => { event.preventDefault(); void model.submitFundOrder() }}>
      <label><span>{t('common.type')}</span><select value={form.orderType} onChange={(event) => model.setFundOrderField('orderType', event.target.value === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'RECHARGE')}><option value="RECHARGE">RECHARGE</option><option value="WITHDRAWAL">WITHDRAWAL</option></select></label>
      <label><span>{t('common.amount')}</span><input type="number" min="0.01" step="0.01" required value={form.amount} onChange={(event) => model.setFundOrderField('amount', event.target.value)} /></label>
      <label><span>{t('common.currency')}</span><select value={form.currency || model.currency} onChange={(event) => model.setFundOrderField('currency', event.target.value)}><option value={model.currency}>{model.currency}</option><option value="USDT">USDT</option><option value="USD">USD</option></select></label>
      <label><span>{t('common.note')}</span><input value={form.note} placeholder={t('assets.notePlaceholder')} onChange={(event) => model.setFundOrderField('note', event.target.value)} /></label>
      <button type="submit" className={`table-action ${form.orderType === 'WITHDRAWAL' ? 'table-action--danger' : 'table-action--primary'}`} disabled={!model.token || !model.accountId}>{form.orderType === 'WITHDRAWAL' ? t('assets.submitWithdrawal') : t('assets.submitDeposit')}</button>
    </form>
  )
}

function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange(value: string): void }) {
  return <label><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}><option value="ALL">ALL</option>{options.map((option) => <option key={option} value={option}>{option}</option>)}</select></label>
}
function SectionHead({ title, description, icon }: { title: string; description: string; icon?: React.ReactNode }) {
  return <div className="settings-section-head"><div><h2>{title}</h2><p>{description}</p></div>{icon}</div>
}
function WalletAction({ icon, title, description, onClick, danger = false }: { icon: React.ReactNode; title: string; description: string; onClick(): void; danger?: boolean }) {
  return <button className={`wallet-action-card${danger ? ' wallet-action-card--danger' : ''}`} type="button" onClick={onClick}>{icon}<strong>{title}</strong><span>{description}</span></button>
}
function Metric({ label, value }: { label: string; value: Amount | string | number | null | undefined }) { return <div className="metric"><span>{label}</span><strong>{value ?? '-'}</strong></div> }
function createFundOrderColumns(t: TFunction): Array<DataViewColumn<FundOrder>> {
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
function createAssetColumns(setSelectedAsset: (key: string) => void, t: TFunction): Array<DataViewColumn<AssetRow>> {
  return [
    { key: 'walletType', label: 'Wallet', sortable: true },
    { key: 'currency', label: t('assets.asset'), sortable: true, render: (asset) => <span className="asset-symbol-cell"><AssetMark symbol={asset.currency} size="sm" /><strong>{asset.currency}</strong></span> },
    { key: 'balance', label: t('assets.balance'), sortable: true },
    { key: 'available', label: t('assets.available'), sortable: true },
    { key: 'frozen', label: t('assets.frozen'), sortable: true },
    { key: 'activityCount', label: t('assets.activityCount'), sortable: true },
    { key: 'actions', label: t('common.action'), render: (asset) => <button type="button" className="table-action table-action--secondary" onClick={() => setSelectedAsset(asset.key)}>{t('common.viewDetails')}</button> }
  ]
}
function createLedgerColumns(t: TFunction): Array<DataViewColumn<LedgerEntry>> {
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
  const tone = status === 'APPROVED' ? 'positive' : status === 'PENDING_REVIEW' || status === 'PENDING' ? 'warning' : status === 'REJECTED' ? 'negative' : ''
  return <span className={`status-chip${tone ? ` status-chip--${tone}` : ''}`}>{status}</span>
}
function formatTime(value: string | null | undefined) { return value ? new Date(value).toLocaleString() : '-' }
