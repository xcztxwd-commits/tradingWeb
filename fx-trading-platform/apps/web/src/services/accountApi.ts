import { apiGet, apiPost } from './apiClient'
import type { AccountSummary, WalletBalance } from '../types/trading'

export function getAccounts(token: string) {
  return apiGet<AccountSummary[]>('/api/accounts', token)
}

export function getAccountSummary(accountId: string, token: string) {
  return apiGet<AccountSummary>(`/api/accounts/${accountId}/summary`, token)
}

export function getWalletBalances(accountId: string, token: string) {
  return apiGet<WalletBalance[]>(`/api/accounts/${accountId}/wallet-balances`, token)
}

export function createDemoAccount(token: string) {
  return apiPost<AccountSummary>('/api/accounts/demo', {}, token)
}
