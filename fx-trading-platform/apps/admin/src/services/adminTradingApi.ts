import type {
  AdminAccountCleanupRequest,
  AdminDemoResetRequest,
  AdminFundingConfigRequest,
  AdminFundingConfigResponse,
  AssetLedgerEntryResponse,
  BatchActionResponse,
  DemoResetResponse,
  FundingSettlement,
  WalletBalanceResponse,
  components
} from '@fx-platform/shared-types'

import type { AdminPage } from '../types.ts'
import { apiGet, apiPost, apiPut } from './apiClient.ts'

export type AdminOrder = components['schemas']['AdminOrderResponse']
export type AdminPosition = components['schemas']['AdminPositionResponse']
export type AdminTrade = components['schemas']['AdminTradeResponse']
export type AdminLedgerEntry = components['schemas']['LedgerEntryResponse']
export type AdminAuditLog = components['schemas']['AdminAuditLogResponse']

export type AdminTransferRecord = {
  accountId: string
  transferId: string
  direction: 'SPOT_TO_PERP' | 'PERP_TO_SPOT'
  amount: number
  spotBalanceAfter: number
  perpBalanceAfter: number
  createdAt?: string
}

export type AdminAccountDetail = {
  walletBalances: WalletBalanceResponse[]
  orders: AdminOrder[]
  positions: AdminPosition[]
  trades: AdminTrade[]
  fundingSettlements: FundingSettlement[]
  assetLedger: AssetLedgerEntryResponse[]
  ledger: AdminLedgerEntry[]
  transfers: AdminTransferRecord[]
}

export type AdminAssetLedgerFilters = {
  walletType?: string
  asset?: string
  entryType?: string
  referenceId?: string
  from?: string
  to?: string
}

type RequiredHighRiskFields = {
  reason: string
  requestId: string
  confirmationText: string
}

type CleanupPayloadContract = AdminAccountCleanupRequest extends RequiredHighRiskFields ? true : never
type ResetPayloadContract = AdminDemoResetRequest extends RequiredHighRiskFields ? true : never

const cleanupPayloadContract: CleanupPayloadContract = true
const resetPayloadContract: ResetPayloadContract = true
void cleanupPayloadContract
void resetPayloadContract

export function getAccountWalletBalances(accountId: string, token: string) {
  return apiGet<WalletBalanceResponse[]>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/wallet-balances`,
    token
  )
}

export function getAccountAssetLedger(
  accountId: string,
  token: string,
  filters: AdminAssetLedgerFilters = {}
) {
  const searchParams = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value) searchParams.set(key, value)
  })
  const query = searchParams.toString()
  return apiGet<AssetLedgerEntryResponse[]>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/asset-ledger${query ? `?${query}` : ''}`,
    token
  )
}

export function getAccountLedger(accountId: string, token: string) {
  return apiGet<AdminLedgerEntry[]>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/ledger`,
    token
  )
}

export function getAccountOrders(accountId: string, token: string, page = 0, size = 100) {
  return apiGet<AdminPage<AdminOrder>>(
    `/api/admin/trading/orders?page=${encodeURIComponent(String(page))}&size=${encodeURIComponent(String(size))}&filter.accountId=${encodeURIComponent(accountId)}`,
    token
  )
}

export function getAccountPositions(accountId: string, token: string, page = 0, size = 100) {
  return apiGet<AdminPage<AdminPosition>>(
    `/api/admin/trading/positions?page=${encodeURIComponent(String(page))}&size=${encodeURIComponent(String(size))}&filter.accountId=${encodeURIComponent(accountId)}`,
    token
  )
}

export function getAccountTrades(accountId: string, token: string, page = 0, size = 100) {
  return apiGet<AdminPage<AdminTrade>>(
    `/api/admin/trading/trades?page=${encodeURIComponent(String(page))}&size=${encodeURIComponent(String(size))}&filter.accountId=${encodeURIComponent(accountId)}`,
    token
  )
}

export function getAccountFundingSettlements(accountId: string, token: string) {
  return apiGet<FundingSettlement[]>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/funding-settlements`,
    token
  )
}

export async function loadAllAdminPages<T>(
  loadPage: (page: number, size: number) => Promise<AdminPage<T>>
) {
  const firstPage = await loadPage(0, 100)
  const totalPages = Math.max(1, firstPage.totalPages)
  const remainingPages = Array.from({ length: totalPages - 1 }, (_, index) => index + 1)
  const rest = await Promise.all(remainingPages.map((page) => loadPage(page, 100)))
  return [firstPage, ...rest].flatMap((page) => page.items)
}

export async function loadAccountTradingDetail(
  accountId: string,
  token: string
): Promise<AdminAccountDetail> {
  const [
    walletBalances,
    orders,
    positions,
    trades,
    fundingSettlements,
    assetLedger,
    ledger
  ] = await Promise.all([
    getAccountWalletBalances(accountId, token),
    loadAllAdminPages((page, size) => getAccountOrders(accountId, token, page, size)),
    loadAllAdminPages((page, size) => getAccountPositions(accountId, token, page, size)),
    loadAllAdminPages((page, size) => getAccountTrades(accountId, token, page, size)),
    getAccountFundingSettlements(accountId, token),
    getAccountAssetLedger(accountId, token),
    getAccountLedger(accountId, token)
  ])

  return {
    walletBalances,
    orders,
    positions,
    trades,
    fundingSettlements,
    assetLedger,
    ledger,
    transfers: aggregateAccountTransfers(assetLedger, ledger)
  }
}

export function aggregateAccountTransfers(
  assetLedger: AssetLedgerEntryResponse[],
  ledger: AdminLedgerEntry[]
): AdminTransferRecord[] {
  const pairedLedgerEntries = new Map<string, AssetLedgerEntryResponse>()
  for (const entry of assetLedger) {
    if (entry.referenceType !== 'TRANSFER' || !entry.referenceId) continue
    pairedLedgerEntries.set(entry.referenceId, entry)
  }

  return ledger.flatMap((cashEntry): AdminTransferRecord[] => {
    if (cashEntry.referenceType !== 'TRANSFER' || !cashEntry.referenceId) return []
    const assetEntry = pairedLedgerEntries.get(cashEntry.referenceId)
    if (!assetEntry || cashEntry.amount === undefined || assetEntry.amount === undefined) return []

    const direction = transferDirection(cashEntry.entryType, assetEntry.entryType)
    if (!direction || Math.abs(cashEntry.amount) !== Math.abs(assetEntry.amount)) return []
    if (
      !cashEntry.accountId ||
      cashEntry.balanceAfter === undefined ||
      assetEntry.balanceAfter === undefined
    ) {
      return []
    }

    return [{
      accountId: cashEntry.accountId,
      transferId: cashEntry.referenceId,
      direction,
      amount: Math.abs(cashEntry.amount),
      spotBalanceAfter: assetEntry.balanceAfter,
      perpBalanceAfter: cashEntry.balanceAfter,
      createdAt: cashEntry.createdAt ?? assetEntry.createdAt
    }]
  }).sort((left, right) => (right.createdAt ?? '').localeCompare(left.createdAt ?? ''))
}

function transferDirection(cashEntryType?: string, assetEntryType?: string) {
  if (cashEntryType === 'TRANSFER_IN' && assetEntryType === 'TRANSFER_OUT') {
    return 'SPOT_TO_PERP' as const
  }
  if (cashEntryType === 'TRANSFER_OUT' && assetEntryType === 'TRANSFER_IN') {
    return 'PERP_TO_SPOT' as const
  }
  return null
}

export function getFundingConfig(symbolId: string, token: string) {
  return apiGet<AdminFundingConfigResponse>(
    `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/funding-config`,
    token
  )
}

export function updateFundingConfig(
  symbolId: string,
  payload: AdminFundingConfigRequest,
  token: string
) {
  return apiPut<AdminFundingConfigResponse>(
    `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/funding-config`,
    payload,
    token
  )
}

export function forceCleanupAccount(
  accountId: string,
  payload: AdminAccountCleanupRequest,
  token: string
) {
  return apiPost<BatchActionResponse>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/force-cleanup`,
    payload,
    token
  )
}

export function resetDemoAccountAsAdmin(
  accountId: string,
  payload: AdminDemoResetRequest,
  token: string
) {
  return apiPost<DemoResetResponse>(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/demo-reset`,
    payload,
    token
  )
}

export async function getAuditIdByRequestId(requestId: string, token: string) {
  const page = await apiGet<AdminPage<AdminAuditLog>>('/api/admin/audit-logs?page=0&size=100', token)
  const match = page.items.find((item) => item.requestId === requestId)
  if (!match?.id) {
    throw new Error(`No audit record was found for request ${requestId}`)
  }
  return match.id
}
