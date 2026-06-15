import { apiGet, apiPost } from './apiClient'
import type { AccountSummary } from '../types/trading'

export function getAccounts(token: string) {
  return apiGet<AccountSummary[]>('/api/accounts', token)
}

export function getAccountSummary(accountId: string, token: string) {
  return apiGet<AccountSummary>(`/api/accounts/${accountId}/summary`, token)
}

export function createDemoAccount(token: string) {
  return apiPost<AccountSummary>('/api/accounts/demo', {}, token)
}
