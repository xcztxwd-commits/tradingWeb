import { apiGet, apiPost } from './apiClient'
import type { AccountSummary, AssetConversionPayload, AssetConversionResponse, AssetLedgerEntry, WalletBalance } from '../types/trading'

export type AssetLedgerFilters = {
  walletType?: string
  asset?: string
  entryType?: string
  referenceId?: string
  from?: string
  to?: string
}

export function getAccounts(token: string) {
  return apiGet<AccountSummary[]>('/api/accounts', token)
}

export function getAccountSummary(accountId: string, token: string) {
  return apiGet<AccountSummary>(`/api/accounts/${accountId}/summary`, token)
}

export function getWalletBalances(accountId: string, token: string) {
  return apiGet<WalletBalance[]>(`/api/accounts/${accountId}/wallet-balances`, token)
}

export function getAssetLedger(accountId: string, token: string, filters: AssetLedgerFilters = {}) {
  const searchParams = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value) searchParams.set(key, value)
  })
  const query = searchParams.toString()
  return apiGet<AssetLedgerEntry[]>(`/api/accounts/${accountId}/asset-ledger${query ? `?${query}` : ''}`, token)
}

export function convertAsset(accountId: string, payload: AssetConversionPayload, token: string) {
  return apiPost<AssetConversionResponse>(`/api/accounts/${accountId}/asset-conversions`, payload, token)
}

export function createDemoAccount(token: string) {
  return apiPost<AccountSummary>('/api/accounts/demo', {}, token)
}
