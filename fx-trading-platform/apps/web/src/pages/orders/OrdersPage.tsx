import { FormEvent, useMemo, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'

import { AssetMark } from '../../components/asset/AssetMark'
import { DataTable, type DataTableColumn } from '../../components/user-page/DataTable'
import { ApiErrorState, LoadingState, LoginRequiredState } from '../../components/user-page/PageState'
import { filterByStatus, formatApiError } from '../../components/user-page/userPageModels'
import { useTradingSession } from '../../features/trading-session/useTradingSession'
import { cancelOrder, cancelProtection, getOrderEvents, modifyOrder, updateProtection } from '../../services/tradingApi'
import type { OrderResponse } from '../../components/tables/types'
import type { OrderEventResponse } from '../../types/trading'
import { formatOrderActionReason, getOrderActionPolicy, isCurrentOrderStatus } from './orderActionPolicy'
import { buildNormalOrderUpdatePayload, buildProtectionUpdatePayload } from './orderActionPayloads'

type OrderView = 'CURRENT' | 'HISTORY' | 'TRADES' | 'EVENTS'

const viewOptions: Array<{ value: OrderView; labelKey: string; emptyKey: string }> = [
  { value: 'CURRENT', labelKey: 'orders.current', emptyKey: 'orders.emptyCurrent' },
  { value: 'HISTORY', labelKey: 'orders.history', emptyKey: 'orders.emptyHistory' },
  { value: 'TRADES', labelKey: 'orders.trades', emptyKey: 'orders.emptyTrades' },
  { value: 'EVENTS', labelKey: 'orders.actions', emptyKey: 'orders.emptyActions' }
]

const statusOptions = [
  'ALL', 'RECEIVED', 'VALIDATING', 'PENDING', 'PENDING_ACTIVATION', 'ACCEPTED', 'WORKING',
  'PARTIALLY_FILLED', 'CANCEL_PENDING', 'FILLED', 'CANCELED', 'REJECTED'
]

export function OrdersPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const { token, orders, sessionMode, sessionError, loginRequired, retrySession } = useTradingSession()
  const [view, setView] = useState<OrderView>('CURRENT')
  const [status, setStatus] = useState('ALL')
  const [symbol, setSymbol] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null)
  const [events, setEvents] = useState<OrderEventResponse[]>([])
  const [editingOrder, setEditingOrder] = useState<OrderResponse | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ReturnType<typeof formatApiError> | null>(null)

  const hasActiveFilters = status !== 'ALL' || symbol.trim() !== '' || fromDate !== '' || toDate !== ''
  const visibleOrders = useMemo(() => {
    const baseRows = orders.filter((order) => {
      if (view === 'CURRENT' && !isCurrentOrderStatus(order.status)) return false
      if (view === 'HISTORY' && isCurrentOrderStatus(order.status)) return false
      if (view === 'TRADES' && !['FILLED', 'PARTIALLY_FILLED'].includes(order.status)) return false
      return true
    })
    return filterByStatus(baseRows, status)
      .filter((order) => matchesSymbol(order, symbol))
      .filter((order) => matchesDateRange(order.createdAt, fromDate, toDate))
  }, [fromDate, orders, status, symbol, toDate, view])
  const emptyMessage = getOrderEmptyMessage(view, status, symbol, fromDate, toDate, t)
  const orderEmptyAction = hasActiveFilters
    ? { label: t('common.clearFilters'), onClick: clearFilters }
    : { label: t('markets.goTrading'), href: '/trading' }

  const handleCancel = async (order: OrderResponse) => {
    if (!token) return
    const policy = getOrderActionPolicy(order)
    if (!policy.canCancel) {
      setNotice(formatOrderActionReason(policy, 'cancel', t) ?? t('orders.cancelUnavailable'))
      return
    }
    setBusyOrderId(order.id)
    clearMessages()
    try {
      if (policy.cancelVia === 'PROTECTION') await cancelProtection(order.id, token)
      else await cancelOrder(order.id, token)
      await retrySession()
      setNotice(t('orders.cancelSuccess'))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyOrderId(null)
    }
  }

  const startModify = (order: OrderResponse, policy = getOrderActionPolicy(order)) => {
    if (!policy.canModify) {
      setNotice(formatOrderActionReason(policy, 'modify', t) ?? t('orders.modifyUnavailable'))
      return
    }
    setEditingOrder(order)
  }

  const handleEvents = async (order: OrderResponse) => {
    if (!token) return
    setBusyOrderId(order.id)
    clearMessages()
    try {
      setEvents(await getOrderEvents(order.id, token))
      setView('EVENTS')
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyOrderId(null)
    }
  }

  const handleModify = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!token || !editingOrder) return
    const form = new FormData(event.currentTarget)
    const fields = {
      quantity: form.get('quantity'),
      price: form.get('price'),
      triggerPrice: form.get('triggerPrice'),
      triggerExecutionType: form.get('triggerExecutionType')
    }
    const policy = getOrderActionPolicy(editingOrder)
    setBusyOrderId(editingOrder.id)
    clearMessages()
    try {
      if (policy.modifyVia === 'PROTECTION') {
        const payload = buildProtectionUpdatePayload(editingOrder, fields)
        if (!payload) {
          setNotice(t('orders.actionReasons.versionUnavailable'))
          return
        }
        await updateProtection(editingOrder.id, payload, token)
      } else {
        const payload = buildNormalOrderUpdatePayload(fields)
        await modifyOrder(editingOrder.id, payload, token)
      }
      setEditingOrder(null)
      await retrySession()
      setNotice(t('orders.modifySuccess'))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyOrderId(null)
    }
  }

  if (loginRequired) {
    return (
      <section className="user-page">
        <LoginRequiredState message={t('orders.loginMessage')} onLogin={() => navigate('/login?redirect=/orders')} />
      </section>
    )
  }

  return (
    <section className="user-page">
      <header className="user-page__header">
        <div>
          <h1>{t('orders.center')}</h1>
          <p>{t('orders.centerSummary')}</p>
        </div>
        <button type="button" className="table-action table-action--secondary" onClick={() => void retrySession()}>
          {t('common.refresh')}
        </button>
      </header>

      <div className="user-page__tabs" role="tablist" aria-label={t('orders.viewAria')}>
        {viewOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            className={view === option.value ? 'active' : ''}
            onClick={() => setView(option.value)}
          >
            {t(option.labelKey)}
          </button>
        ))}
      </div>

      <div className="user-page__toolbar" aria-label={t('orders.filterAria')}>
        <label>
          <span>{t('common.status')}</span>
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            {statusOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>Symbol</span>
          <input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTCUSDT" />
        </label>
        <label>
          <span>{t('assets.startTime')}</span>
          <input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
        </label>
        <label>
          <span>{t('assets.endTime')}</span>
          <input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} />
        </label>
        <button type="button" className="table-action table-action--secondary" onClick={clearFilters} disabled={!hasActiveFilters}>
          {t('common.clearFilters')}
        </button>
      </div>

      {sessionMode === 'loading' ? <LoadingState message={t('orders.loading')} /> : null}
      {sessionMode === 'error' ? (
        <ApiErrorState error={{ title: 'ORDER_LOAD_FAILED', message: sessionError ?? t('orders.unavailable') }} onAction={() => void retrySession()} />
      ) : null}
      {apiError ? <ApiErrorState error={apiError} onAction={() => void retrySession()} /> : null}
      {notice ? <div className="user-page__notice">{notice}</div> : null}

      {view === 'EVENTS' ? (
        <OrderEventsTable events={events} />
      ) : (
        <DataTable
          rows={visibleOrders}
          columns={orderColumns(view, busyOrderId, handleEvents, handleCancel, startModify, t)}
          rowKey={(order) => order.id}
          emptyMessage={emptyMessage}
          emptyAction={orderEmptyAction}
        />
      )}

      {editingOrder ? (
        <section className="user-page__events" aria-label={t('orders.modifyOrder')}>
          <h2>{t('orders.modifyOrder')}</h2>
          <form className="user-page__form" onSubmit={handleModify}>
            <label>
              <span>{t('common.quantity')}</span>
              <input name="quantity" defaultValue={String(editingOrder.quantity ?? editingOrder.lots ?? '')} />
            </label>
            <label>
              <span>{t('common.price')}</span>
              <input name="price" defaultValue={String(editingOrder.price ?? '')} />
            </label>
            {getOrderActionPolicy(editingOrder).modifyVia === 'PROTECTION' ? (
              <>
                <label>
                  <span>{t('trading.triggerPrice')}</span>
                  <input name="triggerPrice" defaultValue={String(editingOrder.triggerPrice ?? '')} />
                </label>
                <label>
                  <span>{t('orders.triggerExecutionType')}</span>
                  <select name="triggerExecutionType" defaultValue={editingOrder.triggerExecutionType ?? 'MARKET'}>
                    <option value="MARKET">MARKET</option>
                    <option value="LIMIT">LIMIT</option>
                  </select>
                </label>
              </>
            ) : null}
            <button type="submit" className="table-action table-action--primary" disabled={busyOrderId === editingOrder.id}>
              {t('common.save')}
            </button>
            <button type="button" className="table-action table-action--secondary" onClick={() => setEditingOrder(null)}>
              {t('common.cancel')}
            </button>
          </form>
        </section>
      ) : null}
    </section>
  )

  function clearMessages() {
    setNotice(null)
    setApiError(null)
  }

  function clearFilters() {
    setStatus('ALL')
    setSymbol('')
    setFromDate('')
    setToDate('')
  }
}

function orderColumns(
  view: OrderView,
  busyOrderId: string | null,
  handleEvents: (order: OrderResponse) => Promise<void>,
  handleCancel: (order: OrderResponse) => Promise<void>,
  startModify: (order: OrderResponse, policy: ReturnType<typeof getOrderActionPolicy>) => void,
  t: TFunction
): Array<DataTableColumn<OrderResponse>> {
  const base: Array<DataTableColumn<OrderResponse>> = [
    {
      key: 'symbol',
      label: 'Symbol',
      sortable: true,
      render: (order) => (
        <span className="asset-symbol-cell">
          <AssetMark symbol={order.symbol} size="sm" />
          <strong>{order.symbol}</strong>
        </span>
      )
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

  if (view !== 'TRADES') {
    base.push({ key: 'holdAmount', label: 'Hold', sortable: true })
  }

  base.push({
    key: 'actions',
    label: t('common.action'),
    render: (order) => {
      const policy = getOrderActionPolicy(order)
      const modifyReasonId = `order-${order.id}-modify-reason`
      const cancelReasonId = `order-${order.id}-cancel-reason`
      const modifyReason = formatOrderActionReason(policy, 'modify', t)
      const cancelReason = formatOrderActionReason(policy, 'cancel', t)

      return (
        <div className="user-page__actions" data-order-id={order.id} data-order-status={order.status}>
          <button type="button" className="table-action table-action--secondary" onClick={() => void handleEvents(order)} disabled={busyOrderId === order.id}>
            {t('orders.events')}
          </button>
          <button
            type="button"
            className="table-action table-action--secondary"
            onClick={() => startModify(order, policy)}
            disabled={!policy.canModify || busyOrderId === order.id}
            aria-describedby={modifyReason ? modifyReasonId : undefined}
            title={modifyReason}
          >
            {t('orders.modify')}
          </button>
          <button
            type="button"
            className="table-action table-action--danger"
            onClick={() => void handleCancel(order)}
            disabled={!policy.canCancel || busyOrderId === order.id}
            aria-describedby={cancelReason ? cancelReasonId : undefined}
            title={cancelReason}
          >
            {t('orders.cancel')}
          </button>
          {modifyReason ? (
            <small id={modifyReasonId} className="user-page__action-reason">
              {modifyReason}
            </small>
          ) : null}
          {cancelReason ? (
            <small id={cancelReasonId} className="user-page__action-reason">
              {cancelReason}
            </small>
          ) : null}
        </div>
      )
    }
  })
  return base
}

function OrderEventsTable({ events }: { events: OrderEventResponse[] }) {
  const { t } = useTranslation()
  const rows = events.map((event) => ({ ...event, status: event.toStatus }))
  return (
    <DataTable
      rows={rows}
      columns={[
        { key: 'createdAt', label: t('common.time'), sortable: true, render: (event) => formatTime(event.createdAt) },
        { key: 'eventType', label: t('orders.events'), sortable: true },
        { key: 'fromStatus', label: 'From' },
        { key: 'toStatus', label: 'To' },
        { key: 'message', label: 'Message', render: (event) => event.message ?? event.reasonCode ?? '-' }
      ]}
      rowKey={(event) => event.id}
      emptyMessage={t('orders.emptyEventsTimeline')}
      emptyAction={{ label: t('orders.viewOrders'), href: '/orders' }}
    />
  )
}

function StatusChip({ status }: { status: string }) {
  const tone = status === 'FILLED' ? 'positive' : status === 'PENDING' ? 'warning' : status === 'REJECTED' || status === 'CANCELED' ? 'negative' : ''
  return <span className={`status-chip${tone ? ` status-chip--${tone}` : ''}`}>{status}</span>
}

function matchesSymbol(order: OrderResponse, symbol: string) {
  const normalized = symbol.trim().toUpperCase()
  return !normalized || order.symbol.toUpperCase().includes(normalized)
}

function matchesDateRange(createdAt: string, fromDate: string, toDate: string) {
  const day = createdAt.slice(0, 10)
  if (fromDate && day < fromDate) return false
  if (toDate && day > toDate) return false
  return true
}

function getOrderEmptyMessage(view: OrderView, status: string, symbol: string, fromDate: string, toDate: string, t: TFunction) {
  const selectedView = viewOptions.find((option) => option.value === view)
  const viewLabel = selectedView ? t(selectedView.labelKey) : t('orders.title')
  const filters: string[] = []
  const normalizedSymbol = symbol.trim().toUpperCase()

  if (normalizedSymbol) filters.push(t('orders.filterSymbolContains', { symbol: normalizedSymbol }))
  if (status !== 'ALL') filters.push(t('orders.filterStatusIs', { status }))
  if (fromDate || toDate) filters.push(t('orders.filterDateRange', { fromDate: fromDate || t('orders.noLimit'), toDate: toDate || t('orders.noLimit') }))

  if (filters.length === 0) return selectedView ? t(selectedView.emptyKey) : t('orders.emptyHistory')
  return t('orders.emptyFiltered', { view: viewLabel, filters: filters.join(t('orders.filterSeparator')) })
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '-'
}

function formatFilledPercent(order: OrderResponse) {
  const quantity = toNumber(order.quantity ?? order.lots)
  const filledQuantity = toNumber(order.filledQuantity)
  if (quantity <= 0) return order.status === 'FILLED' ? '100.00%' : '-'
  if (order.status === 'FILLED' && filledQuantity <= 0) return '100.00%'
  return `${Math.min(100, (filledQuantity / quantity) * 100).toFixed(2)}%`
}

function formatOrderTotal(order: OrderResponse) {
  const price = toNumber(order.avgFillPrice ?? order.executionPrice ?? order.price)
  const quantity = toNumber(order.quantity ?? order.lots)
  const total = price * quantity
  if (Number.isFinite(total) && total > 0) return formatNumber(total)
  return formatOptionalAmount(order.holdAmount)
}

function formatOptionalAmount(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  const numeric = toNumber(value)
  return numeric > 0 ? formatNumber(numeric) : String(value)
}

function formatTriggerCondition(order: OrderResponse) {
  const record = order as Record<string, unknown>
  const condition =
    record.triggerCondition ??
    record.triggerPrice ??
    record.stopPrice ??
    record.stopLoss ??
    record.takeProfit ??
    null
  return condition === null || condition === undefined || condition === '' ? '-' : String(condition)
}

function toNumber(value: unknown) {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

function formatNumber(value: number) {
  return value.toLocaleString(undefined, { maximumFractionDigits: 8 })
}
