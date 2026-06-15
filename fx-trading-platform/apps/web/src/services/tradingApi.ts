import { apiGet, apiPatch, apiPost } from './apiClient'
import type { OrderEventResponse, OrderPayload, UpdateOrderPayload, UpdatePositionProtectionPayload } from '../types/trading'
import type { OrderResponse, PositionResponse } from '../components/tables/types'

export function createOrder(payload: OrderPayload, token?: string) {
  return apiPost<OrderResponse>('/api/trading/orders', payload, token)
}

export function getOrders(token: string) {
  return apiGet<OrderResponse[]>('/api/trading/orders', token)
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
  return apiGet<PositionResponse[]>(`/api/trading/positions?accountId=${accountId}`, token)
}

export function getPositionHistory(accountId: string, token: string) {
  return apiGet<PositionResponse[]>(`/api/trading/positions/history?accountId=${accountId}`, token)
}

export function closePosition(accountId: string, positionId: string, token: string) {
  return apiPost<PositionResponse>(`/api/trading/positions/${positionId}/close?accountId=${accountId}`, {}, token)
}

export function updatePositionProtection(
  accountId: string,
  positionId: string,
  payload: UpdatePositionProtectionPayload,
  token: string
) {
  return apiPatch<PositionResponse>(`/api/trading/positions/${positionId}/protection?accountId=${accountId}`, payload, token)
}
