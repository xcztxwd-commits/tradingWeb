import { apiDelete, apiGet, apiPatch, apiPost } from './apiClient.ts'
import type {
  AdjustPositionMarginRequest,
  AdjustPositionMarginResponse,
  AccountTransferPageResponse,
  AccountTransferResponse,
  BatchActionRequest,
  BatchActionResponse,
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
  UpdateProtectionRequest,
  UpdateSymbolSettingsRequest
} from '@fx-platform/shared-types'
import type { OcoOrderPayload, OrderEventResponse, OrderPayload, UpdateOrderPayload, UpdatePositionProtectionPayload } from '../models/trading.ts'
import type { OrderResponse, PositionResponse } from '../models/account.ts'

type OrderPage = Omit<OrderPageResponse, 'items'> & { items?: OrderResponse[] }
type PositionPage = Omit<PositionPageResponse, 'items'> & { items?: PositionResponse[] }
type TradePage = Omit<TradePageResponse, 'items'> & { items?: Trade[] }
type FundingPage = Omit<FundingSettlementPageResponse, 'items'> & { items?: FundingSettlement[] }
type TransferPage = Omit<AccountTransferPageResponse, 'items'> & { items?: AccountTransferResponse[] }
type TradingPage<T> = { items?: T[]; size?: number; total?: number; totalPages?: number }

const accountHistoryPageSize = 100

export function createOrder(payload: OrderPayload, token?: string) {
  return apiPost<OrderResponse>('/api/trading/orders', payload, token)
}

export function createOcoOrder(payload: OcoOrderPayload, token?: string) {
  return apiPost<OcoOrderGroupResponse>('/api/trading/oco', payload, token)
}

export function cancelAllOrders(payload: BatchActionRequest, token: string) {
  return apiPost<BatchActionResponse>('/api/trading/orders/cancel-all', payload, token)
}

export function closeAllPositions(payload: BatchActionRequest, token: string) {
  return apiPost<BatchActionResponse>('/api/trading/positions/close-all', payload, token)
}

function getOrderPage(accountId: string, token: string, page: number) {
  return apiGet<OrderPage>(`/api/trading/orders?accountId=${encodeURIComponent(accountId)}&page=${page}&size=${accountHistoryPageSize}`, token)
}

export async function getOrders(accountId: string, token: string) {
  return loadAllPages((page) => getOrderPage(accountId, token, page))
}

async function loadAllPages<T>(loadPage: (page: number) => Promise<TradingPage<T>>) {
  const firstPage = await loadPage(0)
  const totalPages = safePageCount(firstPage)
  const remainingPages = Array.from({ length: totalPages - 1 }, (_, index) => index + 1)
  const rest = await Promise.all(remainingPages.map(loadPage))
  return [firstPage, ...rest].flatMap((page) => page.items ?? [])
}

function safePageCount(page: TradingPage<unknown>) {
  const { size, total, totalPages } = page
  if (typeof size !== 'number' || !Number.isSafeInteger(size) || size < 1) return 1
  if (typeof total !== 'number' || !Number.isSafeInteger(total) || total < 0) return 1
  if (typeof totalPages !== 'number' || !Number.isSafeInteger(totalPages) || totalPages < 1) return 1
  return totalPages === Math.max(1, Math.ceil(total / size)) ? totalPages : 1
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

export function updateProtection(orderId: string, payload: UpdateProtectionRequest, token: string) {
  return apiPatch<OrderResponse>(`/api/trading/protections/${orderId}`, payload, token)
}

export function cancelProtection(orderId: string, token: string) {
  return apiDelete<OrderResponse>(`/api/trading/protections/${orderId}`, token)
}

export function getPositions(accountId: string, token: string) {
  return loadAllPages<PositionResponse>((page) => apiGet<PositionPage>(
    `/api/trading/positions?accountId=${encodeURIComponent(accountId)}&page=${page}&size=${accountHistoryPageSize}`,
    token
  ))
}

export function getPositionHistory(accountId: string, token: string) {
  return loadAllPages<PositionResponse>((page) => apiGet<PositionPage>(
    `/api/trading/positions/history?accountId=${encodeURIComponent(accountId)}&page=${page}&size=${accountHistoryPageSize}`,
    token
  ))
}

export function getTrades(accountId: string, token: string) {
  return loadAllPages<Trade>((page) => apiGet<TradePage>(
    `/api/trading/trades?accountId=${encodeURIComponent(accountId)}&page=${page}&size=${accountHistoryPageSize}`,
    token
  ))
}

export function getFundingSettlements(accountId: string, token: string) {
  return loadAllPages<FundingSettlement>((page) => apiGet<FundingPage>(
    `/api/trading/funding/settlements?accountId=${encodeURIComponent(accountId)}&page=${page}&size=${accountHistoryPageSize}`,
    token
  ))
}

export function getAccountTransfers(accountId: string, token: string) {
  return loadAllPages<AccountTransferResponse>((page) => apiGet<TransferPage>(
    `/api/accounts/${encodeURIComponent(accountId)}/transfers?page=${page}&size=${accountHistoryPageSize}`,
    token
  ))
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
