import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  createFundOrder,
  filterByStatus,
  getFundOrders,
  type FundOrder,
  type FundOrderPayload
} from '@fx-platform/frontend-core'

import { formatApiError, type ApiErrorView } from '../../components/user-page/userPageModels'
import { useTranslatedAccountData } from '../shared/useTranslatedAccountData'
import type { AccountRouteModel, TradeOrderTab } from './accountRoute.types'
import { getAccountRoutePath, needsAccountSession, type AccountRouteMode } from './accountRouteModel'
import {
  loadSettingsPreferences,
  saveSettingsPreferences,
  type SettingsPreferences
} from './settingsPreferences'

export function useAccountRouteController(mode: AccountRouteMode): AccountRouteModel {
  const navigate = useNavigate()
  const accountData = useTranslatedAccountData(needsAccountSession(mode))
  const [fundOrders, setFundOrders] = useState<FundOrder[]>([])
  const [fundOrdersLoading, setFundOrdersLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [fundOrderType, setFundOrderType] = useState<'RECHARGE' | 'WITHDRAWAL'>('RECHARGE')
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ApiErrorView | null>(null)
  const submissionRef = useRef(false)
  const [tradeOrderTab, setTradeOrderTab] = useState<TradeOrderTab>('OPEN')
  const [sideFilter, setSideFilter] = useState('ALL')
  const [query, setQuery] = useState('')
  const [preferences, setPreferences] = useState(() => loadSettingsPreferences())
  const [saveNoticeId, setSaveNoticeId] = useState(0)

  const loadFundOrders = useCallback(async () => {
    if (mode !== 'funding-records' || !accountData.token || !accountData.accountId) return
    setFundOrdersLoading(true)
    setApiError(null)
    try {
      setFundOrders(await getFundOrders(accountData.accountId, accountData.token))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setFundOrdersLoading(false)
    }
  }, [accountData.accountId, accountData.token, mode])

  useEffect(() => {
    if (mode === 'funding-records') void loadFundOrders()
  }, [loadFundOrders, mode])

  const submitFundOrder = useCallback(async (input: Omit<FundOrderPayload, 'accountId'>) => {
    if (submissionRef.current || !accountData.token || !accountData.accountId) return false
    submissionRef.current = true
    setNotice(null)
    setApiError(null)
    try {
      const created = await createFundOrder({ ...input, accountId: accountData.accountId }, accountData.token)
      setFundOrders((current) => [created, ...current.filter((order) => order.id !== created.id)])
      setFundOrderType('RECHARGE')
      setNotice('Funding request submitted.')
      await loadFundOrders()
      return true
    } catch (error) {
      setApiError(formatApiError(error))
      return false
    } finally {
      submissionRef.current = false
    }
  }, [accountData.accountId, accountData.token, loadFundOrders])

  const visibleFundOrders = useMemo(
    () => filterByStatus(fundOrders, statusFilter).filter(
      (order) => typeFilter === 'ALL' || order.orderType === typeFilter
    ),
    [fundOrders, statusFilter, typeFilter]
  )
  const visibleTradeOrders = useMemo(
    () => accountData.orders
      .filter((order) => matchesTradeTab(order.status, tradeOrderTab))
      .filter((order) => sideFilter === 'ALL' || order.side === sideFilter)
      .filter((order) => !query.trim() || order.symbol.toLowerCase().includes(query.trim().toLowerCase())),
    [accountData.orders, query, sideFilter, tradeOrderTab]
  )

  const updatePreference = <Key extends keyof SettingsPreferences>(key: Key, value: SettingsPreferences[Key]) => {
    setPreferences((current) => {
      const nextPreferences = { ...current, [key]: value } as SettingsPreferences
      saveSettingsPreferences(nextPreferences)
      return nextPreferences
    })
    setSaveNoticeId((current) => current + 1)
  }

  return {
    mode,
    loginPath: `/login?redirect=${getAccountRoutePath(mode)}`,
    accountData,
    navigateTo: (path) => navigate(path),
    funding: {
      orders: fundOrders,
      visibleOrders: visibleFundOrders,
      loading: fundOrdersLoading,
      statusFilter,
      typeFilter,
      orderType: fundOrderType,
      notice,
      apiError,
      currency: accountData.account?.baseCurrency ?? 'USDT',
      setStatusFilter,
      setTypeFilter,
      setOrderType: setFundOrderType,
      load: loadFundOrders,
      submit: submitFundOrder
    },
    trades: {
      activeTab: tradeOrderTab,
      sideFilter,
      query,
      visibleOrders: visibleTradeOrders,
      setActiveTab: setTradeOrderTab,
      setSideFilter,
      setQuery
    },
    settings: { preferences, saveNoticeId, updatePreference }
  }
}

function matchesTradeTab(status: string, activeTab: TradeOrderTab) {
  if (activeTab === 'OPEN') return !['FILLED', 'CANCELED', 'REJECTED'].includes(status)
  if (activeTab === 'FILLED') return status === 'FILLED'
  return ['FILLED', 'CANCELED', 'REJECTED'].includes(status)
}
