import type { AssetLedgerEntry, WalletBalance } from '@fx-platform/frontend-core'

import type { AssetRow } from './walletRoute.types'

export function getAssetRows(
  account: { baseCurrency: string; balance: AssetRow['balance']; freeMargin: AssetRow['available'] } | null | undefined,
  balances: WalletBalance[],
  entries: AssetLedgerEntry[],
  frozenAmount: number
): AssetRow[] {
  const rows = new Map<string, AssetRow>()
  balances.forEach((balance) => {
    const key = walletKey(balance.walletType, balance.asset)
    rows.set(key, {
      key,
      walletType: balance.walletType,
      currency: balance.asset,
      balance: balance.total,
      available: balance.available,
      frozen: balance.locked,
      activityCount: entries.filter((entry) => entry.walletType === balance.walletType && entry.asset === balance.asset).length
    })
  })
  const accountKey = account ? walletKey('FX_MARGIN', account.baseCurrency) : ''
  if (account && !rows.has(accountKey)) {
    rows.set(accountKey, {
      key: accountKey,
      walletType: 'FX_MARGIN',
      currency: account.baseCurrency,
      balance: account.balance,
      available: account.freeMargin,
      frozen: frozenAmount.toFixed(2),
      activityCount: entries.filter((entry) => entry.walletType === 'FX_MARGIN' && entry.asset === account.baseCurrency).length
    })
  }
  entries.forEach((entry) => {
    const key = walletKey(entry.walletType, entry.asset)
    if (rows.has(key)) return
    rows.set(key, {
      key,
      walletType: entry.walletType ?? 'UNKNOWN',
      currency: entry.asset,
      balance: '-',
      available: '-',
      frozen: '-',
      activityCount: entries.filter((item) => item.walletType === entry.walletType && item.asset === entry.asset).length
    })
  })
  return Array.from(rows.values())
}

export function getUniqueValues(values: string[]) {
  return Array.from(new Set(values.filter(Boolean))).sort()
}

export function matchesWalletDateRange(createdAt: string | null | undefined, fromDate: string, toDate: string) {
  const day = createdAt?.slice(0, 10)
  if (!day) return !fromDate && !toDate
  if (fromDate && day < fromDate) return false
  if (toDate && day > toDate) return false
  return true
}

function walletKey(walletType: string | undefined, asset: string) {
  return `${walletType || 'UNKNOWN'}:${asset}`
}
