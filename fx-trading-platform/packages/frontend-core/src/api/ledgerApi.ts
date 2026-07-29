import { apiGet } from './apiClient.ts'
import type { LedgerEntry } from '../models/trading.ts'

export function getLedgerEntries(accountId: string, token: string) {
  return apiGet<LedgerEntry[]>(`/api/ledger?accountId=${accountId}`, token)
}
