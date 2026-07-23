import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Dialog, type DataViewColumn } from '@fx-platform/ui'
import type { OrderEventResponse, OrderResponse } from '@fx-platform/frontend-core'

import { ApiErrorState, LoadingState, LoginRequiredState } from '../data/PageState'
import type { OrdersRouteModel, OrderView } from '../../routes/orders/ordersRoute.types'
import { formatOrderActionReason, getOrderActionPolicy } from '../../routes/orders/orderActionPolicy'
import { AssetMark } from '../asset/AssetMark'
import { cssModuleClasses as css } from '../data/cssModuleClasses'
import type { RouteDataCollectionRenderer } from '../data/RouteDataCollection'
import surfaceStyles from '../data/UserPageSurface.module.css'
import routeStyles from './OrdersRouteContent.module.css'

const styles = { ...surfaceStyles, ...routeStyles }

const viewOptions: Array<{ value: OrderView; labelKey: string }> = [
  { value: 'CURRENT', labelKey: 'orders.current' },
  { value: 'HISTORY', labelKey: 'orders.history' },
  { value: 'TRADES', labelKey: 'orders.trades' },
  { value: 'EVENTS', labelKey: 'orders.actions' }
]
const statusOptions = [
  'ALL', 'RECEIVED', 'VALIDATING', 'PENDING', 'PENDING_ACTIVATION', 'ACCEPTED', 'WORKING',
  'PARTIALLY_FILLED', 'CANCEL_PENDING', 'FILLED', 'CANCELED', 'REJECTED'
]

export function OrdersRouteContent({
  model,
  renderDataCollection
}: {
  model: OrdersRouteModel
  renderDataCollection: RouteDataCollectionRenderer
}) {
  const { t } = useTranslation()

  if (model.loginRequired) {
    return (
      <section className={css(styles, "user-page")}>
        <LoginRequiredState message={t('orders.loginMessage')} onLogin={model.openLogin} />
      </section>
    )
  }

  const emptyAction = model.hasActiveFilters
    ? { label: t('common.clearFilters'), onClick: model.clearFilters }
    : { label: t('markets.goTrading'), href: '/trading' }

  return (
    <section className={css(styles, "user-page", "orders-page")}>
      <header className={css(styles, "user-page__header")}>
        <div>
          <h1>{t('orders.center')}</h1>
          <p>{t('orders.centerSummary')}</p>
        </div>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={() => void model.refresh()}>
          {t('common.refresh')}
        </button>
      </header>

      <div className={css(styles, "user-page__tabs")} role="tablist" aria-label={t('orders.viewAria')}>
        {viewOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            className={model.view === option.value ? styles.active : undefined}
            onClick={() => model.setView(option.value)}
          >
            {t(option.labelKey)}
          </button>
        ))}
      </div>

      <div className={css(styles, "user-page__toolbar")} aria-label={t('orders.filterAria')}>
        <label>
          <span>{t('common.status')}</span>
          <select value={model.status} onChange={(event) => model.setStatus(event.target.value)}>
            {statusOptions.map((option) => <option key={option} value={option}>{option}</option>)}
          </select>
        </label>
        <label>
          <span>Symbol</span>
          <input value={model.symbol} onChange={(event) => model.setSymbol(event.target.value)} placeholder="BTCUSDT" />
        </label>
        <label>
          <span>{t('assets.startTime')}</span>
          <input type="date" value={model.fromDate} onChange={(event) => model.setFromDate(event.target.value)} />
        </label>
        <label>
          <span>{t('assets.endTime')}</span>
          <input type="date" value={model.toDate} onChange={(event) => model.setToDate(event.target.value)} />
        </label>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={model.clearFilters} disabled={!model.hasActiveFilters}>
          {t('common.clearFilters')}
        </button>
      </div>

      {model.sessionMode === 'loading' ? <LoadingState message={t('orders.loading')} /> : null}
      {model.sessionMode === 'error' ? (
        <ApiErrorState
          error={{ title: 'ORDER_LOAD_FAILED', message: model.sessionError ?? t('orders.unavailable') }}
          onAction={() => void model.refresh()}
        />
      ) : null}
      {model.apiError ? <ApiErrorState error={model.apiError} onAction={() => void model.refresh()} /> : null}
      {model.notice ? <div className={css(styles, "user-page__notice")}>{model.notice}</div> : null}

      {model.view === 'EVENTS'
        ? renderDataCollection<OrderEventResponse>({
            rows: model.events,
            columns: orderEventColumns(t),
            rowKey: (event) => event.id,
            emptyMessage: t('orders.emptyEventsTimeline'),
            emptyAction: { label: t('orders.viewOrders'), href: '/orders' }
          })
        : renderDataCollection<OrderResponse>({
            rows: model.visibleOrders,
            columns: orderColumns(model, t),
            rowKey: (order) => order.id,
            emptyMessage: model.emptyMessage,
            emptyAction
          })}

      {model.editingOrder ? <OrderModifyForm model={model} /> : null}
      <Dialog
        open={Boolean(model.pendingCancelOrder)}
        onClose={model.dismissCancel}
        labelledBy="order-cancel-title"
        closeLabel={t('common.cancel')}
        pending={Boolean(model.pendingCancelOrder && model.busyOrderId === model.pendingCancelOrder.id)}
        panelClassName={css(styles, "confirm-dialog")}
      >
        <div className={css(styles, "confirm-dialog__panel")}>
          <h2 id="order-cancel-title">{t('orders.cancelConfirm')}</h2>
          <p>{t('orders.cancelConfirmBody', { symbol: model.pendingCancelOrder?.symbol ?? '' })}</p>
          <div className={css(styles, "user-page__actions")}>
            <button type="button" className={css(styles, "table-action", "table-action--danger")} onClick={() => void model.confirmCancel()} disabled={Boolean(model.pendingCancelOrder && model.busyOrderId === model.pendingCancelOrder.id)}>{t('orders.cancel')}</button>
            <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={model.dismissCancel} disabled={Boolean(model.pendingCancelOrder && model.busyOrderId === model.pendingCancelOrder.id)}>{t('common.cancel')}</button>
          </div>
        </div>
      </Dialog>
    </section>
  )
}

function OrderModifyForm({ model }: { model: OrdersRouteModel }) {
  const { t } = useTranslation()
  const order = model.editingOrder
  if (!order) return null
  return (
    <section className={css(styles, "user-page__events")} aria-label={t('orders.modifyOrder')}>
      <h2>{t('orders.modifyOrder')}</h2>
      <form className={css(styles, "user-page__form")} onSubmit={(event) => { event.preventDefault(); void model.submitModify() }}>
        <label>
          <span>{t('common.quantity')}</span>
          <input value={model.modifyFields.quantity} onChange={(event) => model.setModifyField('quantity', event.target.value)} />
        </label>
        <label>
          <span>{t('common.price')}</span>
          <input value={model.modifyFields.price} onChange={(event) => model.setModifyField('price', event.target.value)} />
        </label>
        {getOrderActionPolicy(order).modifyVia === 'PROTECTION' ? (
          <>
            <label>
              <span>{t('trading.triggerPrice')}</span>
              <input value={model.modifyFields.triggerPrice} onChange={(event) => model.setModifyField('triggerPrice', event.target.value)} />
            </label>
            <label>
              <span>{t('orders.triggerExecutionType')}</span>
              <select value={model.modifyFields.triggerExecutionType} onChange={(event) => model.setModifyField('triggerExecutionType', event.target.value)}>
                <option value="MARKET">MARKET</option>
                <option value="LIMIT">LIMIT</option>
              </select>
            </label>
          </>
        ) : null}
        <button type="submit" className={css(styles, "table-action", "table-action--primary")} disabled={model.busyOrderId === order.id}>
          {t('common.save')}
        </button>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={model.dismissModify}>
          {t('common.cancel')}
        </button>
      </form>
    </section>
  )
}

function orderColumns(model: OrdersRouteModel, t: TFunction): Array<DataViewColumn<OrderResponse>> {
  const columns: Array<DataViewColumn<OrderResponse>> = [
    {
      key: 'symbol',
      label: 'Symbol',
      sortable: true,
      render: (order) => <span className={css(styles, "asset-symbol-cell")}><AssetMark symbol={order.symbol} size="sm" /><strong>{order.symbol}</strong></span>
    },
    { key: 'side', label: 'Side', sortable: true },
    { key: 'orderType', label: t('common.type'), sortable: true },
    { key: 'quantity', label: 'Qty', render: (order) => order.quantity ?? order.lots },
    { key: 'status', label: t('common.status'), sortable: true, render: (order) => <StatusChip status={order.status} /> },
    { key: 'price', label: t('common.price'), render: (order) => order.avgFillPrice ?? order.executionPrice ?? order.price ?? '-' },
    { key: 'filledPercent', label: 'Filled %', render: formatFilledPercent },
    { key: 'total', label: 'Total', render: formatOrderTotal },
    { key: 'fee', label: 'Fee', sortable: true, render: (order) => formatOptionalAmount(order.fee) },
    { key: 'triggerCondition', label: 'Trigger condition', render: formatTriggerCondition },
    { key: 'createdAt', label: 'Created time', sortable: true, render: (order) => formatTime(order.createdAt) },
    { key: 'updatedAt', label: 'Updated time', sortable: true, render: (order) => formatTime(order.updatedAt) },
    { key: 'slippage', label: 'Slippage', sortable: true },
    { key: 'rejectMessage', label: t('orders.reason'), render: (order) => order.rejectMessage ?? order.rejectCode ?? '-' }
  ]
  if (model.view !== 'TRADES') columns.push({ key: 'holdAmount', label: 'Hold', sortable: true })
  columns.push({
    key: 'actions',
    label: t('common.action'),
    render: (order) => {
      const policy = getOrderActionPolicy(order)
      const modifyReason = formatOrderActionReason(policy, 'modify', t)
      const cancelReason = formatOrderActionReason(policy, 'cancel', t)
      return (
        <div className={css(styles, "user-page__actions")} data-order-id={order.id} data-order-status={order.status}>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={() => void model.showEvents(order)} disabled={model.busyOrderId === order.id}>{t('orders.events')}</button>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={() => model.startModify(order)} disabled={!policy.canModify || model.busyOrderId === order.id} title={modifyReason}>{t('orders.modify')}</button>
          <button type="button" className={css(styles, "table-action", "table-action--danger")} onClick={() => model.requestCancel(order)} disabled={!policy.canCancel || model.busyOrderId === order.id} title={cancelReason}>{t('orders.cancel')}</button>
          {modifyReason ? <small className={css(styles, "user-page__action-reason")}>{modifyReason}</small> : null}
          {cancelReason ? <small className={css(styles, "user-page__action-reason")}>{cancelReason}</small> : null}
        </div>
      )
    }
  })
  return columns
}

function orderEventColumns(t: TFunction): Array<DataViewColumn<OrderEventResponse>> {
  return [
    { key: 'createdAt', label: t('common.time'), sortable: true, render: (event) => formatTime(event.createdAt) },
    { key: 'eventType', label: t('orders.events'), sortable: true },
    { key: 'fromStatus', label: 'From' },
    { key: 'toStatus', label: 'To' },
    { key: 'message', label: 'Message', render: (event) => event.message ?? event.reasonCode ?? '-' }
  ]
}

function StatusChip({ status }: { status: string }) {
  const tone = status === 'FILLED' ? 'positive' : status === 'PENDING' ? 'warning' : status === 'REJECTED' || status === 'CANCELED' ? 'negative' : ''
  return <span className={css(styles, 'status-chip', tone && `status-chip--${tone}`)}>{status}</span>
}

function formatTime(value: string | null | undefined) { return value ? new Date(value).toLocaleString() : '-' }
function toNumber(value: unknown) {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}
function formatNumber(value: number) { return value.toLocaleString(undefined, { maximumFractionDigits: 8 }) }
function formatOptionalAmount(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  const numeric = toNumber(value)
  return numeric > 0 ? formatNumber(numeric) : String(value)
}
function formatFilledPercent(order: OrderResponse) {
  const quantity = toNumber(order.quantity ?? order.lots)
  const filled = toNumber(order.filledQuantity)
  if (quantity <= 0) return order.status === 'FILLED' ? '100.00%' : '-'
  if (order.status === 'FILLED' && filled <= 0) return '100.00%'
  return `${Math.min(100, (filled / quantity) * 100).toFixed(2)}%`
}
function formatOrderTotal(order: OrderResponse) {
  const total = toNumber(order.avgFillPrice ?? order.executionPrice ?? order.price) * toNumber(order.quantity ?? order.lots)
  return Number.isFinite(total) && total > 0 ? formatNumber(total) : formatOptionalAmount(order.holdAmount)
}
function formatTriggerCondition(order: OrderResponse) {
  const record = order as Record<string, unknown>
  const value = record.triggerCondition ?? record.triggerPrice ?? record.stopPrice ?? record.stopLoss ?? record.takeProfit
  return value === null || value === undefined || value === '' ? '-' : String(value)
}
