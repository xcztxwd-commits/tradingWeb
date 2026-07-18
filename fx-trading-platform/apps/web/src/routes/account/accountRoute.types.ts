import type { FundOrder, FundOrderPayload, OrderResponse } from '@fx-platform/frontend-core'

import type { ApiErrorView } from '../../components/user-page/userPageModels'
import type { TranslatedAccountData } from '../shared/useTranslatedAccountData'
import type { AccountRouteMode } from './accountRouteModel'
import type { SettingsPreferences } from './settingsPreferences'

export type TradeOrderTab = 'OPEN' | 'HISTORY' | 'FILLED'

export type FundingRouteState = {
  orders: FundOrder[]
  visibleOrders: FundOrder[]
  loading: boolean
  statusFilter: string
  typeFilter: string
  orderType: 'RECHARGE' | 'WITHDRAWAL'
  notice: string | null
  apiError: ApiErrorView | null
  currency: string
  setStatusFilter(value: string): void
  setTypeFilter(value: string): void
  setOrderType(value: 'RECHARGE' | 'WITHDRAWAL'): void
  load(): Promise<void>
  submit(input: Omit<FundOrderPayload, 'accountId'>): Promise<boolean>
}

export type TradeRouteState = {
  activeTab: TradeOrderTab
  sideFilter: string
  query: string
  visibleOrders: OrderResponse[]
  setActiveTab(value: TradeOrderTab): void
  setSideFilter(value: string): void
  setQuery(value: string): void
}

export type SettingsRouteState = {
  preferences: SettingsPreferences
  saveNoticeId: number
  updatePreference<Key extends keyof SettingsPreferences>(key: Key, value: SettingsPreferences[Key]): void
}

export type AccountRouteModel = {
  mode: AccountRouteMode
  loginPath: string
  accountData: TranslatedAccountData
  navigateTo(path: string): void
  funding: FundingRouteState
  trades: TradeRouteState
  settings: SettingsRouteState
}
