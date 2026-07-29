import { apiGet, apiPost } from './apiClient.ts'
import type {
  AccountTransferRequest,
  AccountTransferResponse,
  DemoResetRequest,
  DemoResetResponse
} from '@fx-platform/shared-types'
import type { AccountSummary, AssetConversionPayload, AssetConversionResponse, AssetLedgerEntry, WalletBalance } from '../models/trading.ts'

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

export function transferDemoFunds(
  accountId: string,
  payload: AccountTransferRequest,
  token: string
) {
  return apiPost<AccountTransferResponse>(`/api/accounts/${accountId}/transfers`, payload, token)
}

export function resetDemoAccount(accountId: string, payload: DemoResetRequest, token: string) {
  return apiPost<DemoResetResponse>(`/api/accounts/${accountId}/demo-reset`, payload, token)
}
