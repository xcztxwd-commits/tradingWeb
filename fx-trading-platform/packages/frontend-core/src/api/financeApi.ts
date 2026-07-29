import { apiGet, apiPost } from './apiClient.ts'
import type { FundOrder, FundOrderPayload } from '../models/trading.ts'

export const financeEndpoints = {
  fundOrders: (accountId: string) => `/api/finance/fund-orders?${new URLSearchParams({ accountId }).toString()}`,
  createFundOrder: '/api/finance/fund-orders'
} as const

export function getFundOrders(accountId: string, token: string) {
  return apiGet<FundOrder[]>(financeEndpoints.fundOrders(accountId), token)
}

export function createFundOrder(payload: FundOrderPayload, token: string) {
  return apiPost<FundOrder>(financeEndpoints.createFundOrder, payload, token)
}
