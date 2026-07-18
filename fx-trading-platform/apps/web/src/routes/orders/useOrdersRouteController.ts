import { useMemo, useRef, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  cancelOrder,
  cancelProtection,
  filterByStatus,
  getOrderEvents,
  modifyOrder,
  updateProtection,
  type OrderResponse
} from '@fx-platform/frontend-core'

import { formatApiError, type ApiErrorView } from '../../components/user-page/userPageModels'
import { useTranslatedAccountData } from '../shared/useTranslatedAccountData'
import { createPendingOperationGuard } from '../shared/pendingOperationGuard'
import { buildNormalOrderUpdatePayload, buildProtectionUpdatePayload } from './orderActionPayloads'
import { formatOrderActionReason, getOrderActionPolicy, isCurrentOrderStatus } from './orderActionPolicy'
import type { OrderModifyFields, OrdersRouteModel, OrderView } from './ordersRoute.types'

const emptyModifyFields: OrderModifyFields = {
  quantity: '',
  price: '',
  triggerPrice: '',
  triggerExecutionType: 'MARKET'
}

export function useOrdersRouteController(): OrdersRouteModel {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const { token, orders, sessionMode, sessionError, loginRequired, retrySession } = useTranslatedAccountData()
  const operationGuard = useRef(createPendingOperationGuard()).current
  const [view, setView] = useState<OrderView>('CURRENT')
  const [status, setStatus] = useState('ALL')
  const [symbol, setSymbol] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null)
  const [events, setEvents] = useState<OrdersRouteModel['events']>([])
  const [pendingCancelOrder, setPendingCancelOrder] = useState<OrderResponse | null>(null)
  const [editingOrder, setEditingOrder] = useState<OrderResponse | null>(null)
  const [modifyFields, setModifyFields] = useState<OrderModifyFields>(emptyModifyFields)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ApiErrorView | null>(null)
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

  const clearMessages = () => {
    setNotice(null)
    setApiError(null)
  }

  const handleCancel = (order: OrderResponse) => operationGuard.run(`order:cancel:${order.id}`, async () => {
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
      setPendingCancelOrder(null)
      setNotice(t('orders.cancelSuccess'))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyOrderId(null)
    }
  })

  const showEvents = (order: OrderResponse) => operationGuard.run(`order:events:${order.id}`, async () => {
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
  })

  const startModify = (order: OrderResponse) => {
    const policy = getOrderActionPolicy(order)
    if (!policy.canModify) {
      setNotice(formatOrderActionReason(policy, 'modify', t) ?? t('orders.modifyUnavailable'))
      return
    }
    setModifyFields({
      quantity: String(order.quantity ?? order.lots ?? ''),
      price: String(order.price ?? ''),
      triggerPrice: String(order.triggerPrice ?? ''),
      triggerExecutionType: order.triggerExecutionType ?? 'MARKET'
    })
    setEditingOrder(order)
  }

  const requestCancel = (order: OrderResponse) => {
    const policy = getOrderActionPolicy(order)
    if (!policy.canCancel) {
      setNotice(formatOrderActionReason(policy, 'cancel', t) ?? t('orders.cancelUnavailable'))
      return
    }
    setPendingCancelOrder(order)
  }

  const submitModify = async () => {
    if (!token || !editingOrder) return
    return operationGuard.run(`order:modify:${editingOrder.id}`, async () => {
      const policy = getOrderActionPolicy(editingOrder)
      setBusyOrderId(editingOrder.id)
      clearMessages()
      try {
        if (policy.modifyVia === 'PROTECTION') {
          const payload = buildProtectionUpdatePayload(editingOrder, modifyFields)
          if (!payload) {
            setNotice(t('orders.actionReasons.versionUnavailable'))
            return
          }
          await updateProtection(editingOrder.id, payload, token)
        } else {
          await modifyOrder(editingOrder.id, buildNormalOrderUpdatePayload(modifyFields), token)
        }
        setEditingOrder(null)
        await retrySession()
        setNotice(t('orders.modifySuccess'))
      } catch (error) {
        setApiError(formatApiError(error))
      } finally {
        setBusyOrderId(null)
      }
    })
  }

  return {
    visibleOrders,
    events,
    view,
    status,
    symbol,
    fromDate,
    toDate,
    hasActiveFilters,
    emptyMessage: getOrderEmptyMessage(view, status, symbol, fromDate, toDate, t),
    sessionMode,
    sessionError,
    loginRequired,
    busyOrderId,
    pendingCancelOrder,
    editingOrder,
    modifyFields,
    notice,
    apiError,
    setView,
    setStatus,
    setSymbol,
    setFromDate,
    setToDate,
    setModifyField: (field, value) => setModifyFields((current) => ({ ...current, [field]: value })),
    clearFilters() {
      setStatus('ALL')
      setSymbol('')
      setFromDate('')
      setToDate('')
    },
    refresh: retrySession,
    requestCancel,
    dismissCancel: () => setPendingCancelOrder(null),
    confirmCancel: async () => {
      if (pendingCancelOrder) await handleCancel(pendingCancelOrder)
    },
    showEvents,
    startModify,
    dismissModify: () => setEditingOrder(null),
    submitModify,
    openLogin: () => navigate('/login?redirect=/orders')
  }
}

const orderViewLabels: Record<OrderView, { label: string; empty: string }> = {
  CURRENT: { label: 'orders.current', empty: 'orders.emptyCurrent' },
  HISTORY: { label: 'orders.history', empty: 'orders.emptyHistory' },
  TRADES: { label: 'orders.trades', empty: 'orders.emptyTrades' },
  EVENTS: { label: 'orders.actions', empty: 'orders.emptyActions' }
}

function getOrderEmptyMessage(view: OrderView, status: string, symbol: string, fromDate: string, toDate: string, t: TFunction) {
  const selectedView = orderViewLabels[view]
  const filters: string[] = []
  const normalizedSymbol = symbol.trim().toUpperCase()
  if (normalizedSymbol) filters.push(t('orders.filterSymbolContains', { symbol: normalizedSymbol }))
  if (status !== 'ALL') filters.push(t('orders.filterStatusIs', { status }))
  if (fromDate || toDate) filters.push(t('orders.filterDateRange', {
    fromDate: fromDate || t('orders.noLimit'),
    toDate: toDate || t('orders.noLimit')
  }))
  if (filters.length === 0) return t(selectedView.empty)
  return t('orders.emptyFiltered', { view: t(selectedView.label), filters: filters.join(t('orders.filterSeparator')) })
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
