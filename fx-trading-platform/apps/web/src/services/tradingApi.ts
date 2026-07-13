import { apiGet, apiPatch, apiPost } from './apiClient.ts'
import type {
  AdjustPositionMarginRequest,
  AdjustPositionMarginResponse,
  AccountTransferPageResponse,
  AccountTransferResponse,
  ClosePositionRequest,
  CreateProtectionRequest,
  FundingSettlement,
  FundingSettlementPageResponse,
  OcoOrderGroupResponse,
  OrderPageResponse,
  PositionMode,
  PositionPageResponse,
  TradingSettingsResponse,
  Trade,
  TradePageResponse,
  UpdateSymbolSettingsRequest
} from '@fx-platform/shared-types'
import type { OcoOrderPayload, OrderEventResponse, OrderPayload, UpdateOrderPayload, UpdatePositionProtectionPayload } from '../types/trading'
import type { OrderResponse, PositionResponse } from '../components/tables/types'

type OrderPage = Omit<OrderPageResponse, 'items'> & { items?: OrderResponse[] }
type PositionPage = Omit<PositionPageResponse, 'items'> & { items?: PositionResponse[] }
type TradePage = Omit<TradePageResponse, 'items'> & { items?: Trade[] }
type FundingPage = Omit<FundingSettlementPageResponse, 'items'> & { items?: FundingSettlement[] }
type TransferPage = Omit<AccountTransferPageResponse, 'items'> & { items?: AccountTransferResponse[] }

export function createOrder(payload: OrderPayload, token?: string) {
  return apiPost<OrderResponse>('/api/trading/orders', payload, token)
}

export function createOcoOrder(payload: OcoOrderPayload, token?: string) {
  return apiPost<OcoOrderGroupResponse>('/api/trading/oco', payload, token)
}

export function getOrders(accountId: string, token: string) {
  return apiGet<OrderPage>(`/api/trading/orders?accountId=${encodeURIComponent(accountId)}`, token)
    .then((page) => page.items ?? [])
}

export function getOrderEvents(orderId: string, token: string) {
  return apiGet<OrderEventResponse[]>(`/api/trading/orders/${orderId}/events`, token)
}

export function cancelOrder(orderId: string, token: string) {
  return apiPost<OrderResponse>(`/api/trading/orders/${orderId}/cancel`, {}, token)
}

export function modifyOrder(orderId: string, payload: UpdateOrderPayload, token: string) {
  return apiPatch<OrderResponse>(`/api/trading/orders/${orderId}`, payload, token)
}

export function getPositions(accountId: string, token: string) {
  return apiGet<PositionPage>(`/api/trading/positions?accountId=${encodeURIComponent(accountId)}`, token)
    .then((page) => page.items ?? [])
}

export function getPositionHistory(accountId: string, token: string) {
  return apiGet<PositionPage>(`/api/trading/positions/history?accountId=${encodeURIComponent(accountId)}`, token)
    .then((page) => page.items ?? [])
}

export function getTrades(accountId: string, token: string) {
  return apiGet<TradePage>(`/api/trading/trades?accountId=${encodeURIComponent(accountId)}`, token)
    .then((page) => page.items ?? [])
}

export function getFundingSettlements(accountId: string, token: string) {
  return apiGet<FundingPage>(
    `/api/trading/funding/settlements?accountId=${encodeURIComponent(accountId)}`,
    token
  ).then((page) => page.items ?? [])
}

export function getAccountTransfers(accountId: string, token: string) {
  return apiGet<TransferPage>(`/api/accounts/${encodeURIComponent(accountId)}/transfers`, token)
    .then((page) => page.items ?? [])
}

export function closePosition(
  accountId: string,
  positionId: string,
  token: string,
  payload: ClosePositionRequest | undefined = undefined
) {
  return apiPost<PositionResponse>(
    `/api/trading/positions/${positionId}/close?accountId=${encodeURIComponent(accountId)}`,
    payload,
    token
  )
}

export function adjustPositionMargin(
  positionId: string,
  payload: AdjustPositionMarginRequest,
  token: string
) {
  return apiPost<AdjustPositionMarginResponse>(`/api/trading/positions/${positionId}/margin`, payload, token)
}

export function createPositionProtection(
  positionId: string,
  payload: CreateProtectionRequest,
  token: string
) {
  return apiPost<OrderResponse>(`/api/trading/positions/${positionId}/protections`, payload, token)
}

export function updatePositionProtection(
  accountId: string,
  positionId: string,
  payload: UpdatePositionProtectionPayload,
  token: string
) {
  return apiPatch<PositionResponse>(
    `/api/trading/positions/${positionId}/protection?accountId=${encodeURIComponent(accountId)}`,
    payload,
    token
  )
}

export function getTradingSettings(accountId: string, token?: string) {
  return apiGet<TradingSettingsResponse>(`/api/accounts/${accountId}/trading-settings`, token)
}

export function updateTradingPositionMode(accountId: string, positionMode: PositionMode, token?: string) {
  return apiPatch<TradingSettingsResponse>(`/api/accounts/${accountId}/position-mode`, { positionMode }, token)
}

export function updateTradingSymbolSettings(
  accountId: string,
  symbol: string,
  payload: UpdateSymbolSettingsRequest,
  token?: string
) {
  return apiPatch<TradingSettingsResponse>(
    `/api/accounts/${accountId}/symbols/${encodeURIComponent(symbol)}/settings`,
    payload,
    token
  )
}
