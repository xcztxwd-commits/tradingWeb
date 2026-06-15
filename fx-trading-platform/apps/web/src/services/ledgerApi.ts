import { apiGet } from './apiClient'
import type { LedgerEntry } from '../types/trading'

export function getLedgerEntries(accountId: string, token: string) {
  return apiGet<LedgerEntry[]>(`/api/ledger?accountId=${accountId}`, token)
}
