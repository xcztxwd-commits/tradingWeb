import type { AccountRow } from '../types.ts'

export type AccountFilters = {
  accountId: string
  userId: string
  accountType: string
  status: string
}

export function filterAccounts(accounts: AccountRow[], filters: AccountFilters) {
  const accountId = normalize(filters.accountId)
  const userId = normalize(filters.userId)
  const accountType = normalize(filters.accountType)
  const status = normalize(filters.status)

  return accounts.filter((account) =>
    includes(account.id, accountId)
    && includes(account.userId, userId)
    && includes(account.accountType, accountType)
    && includes(account.status, status)
  )
}

function normalize(value: string) {
  return value.trim().toLowerCase()
}

function includes(value: string, filter: string) {
  return !filter || value.toLowerCase().includes(filter)
}
