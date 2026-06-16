import { createDemoAccount, getAccounts, getAccountSummary, getWalletBalances } from '../../services/accountApi'
import { ApiClientError } from '../../services/apiClient'
import { getLedgerEntries } from '../../services/ledgerApi'
import {
  closePosition,
  createOrder,
  getOrders,
  getPositionHistory,
  getPositions,
  updatePositionProtection
} from '../../services/tradingApi'
import type { OrderResponse, PositionResponse } from '../../components/tables/types'
import type { AccountSummary, LedgerEntry, OrderPayload, UpdatePositionProtectionPayload, WalletBalance } from '../../types/trading'

export { deriveTradingBalances } from './tradingSessionModels'
export type { TradingBalances } from './tradingSessionModels'

export type DemoTradingSession = {
  token: string
  account: AccountSummary
}

export type TradingAccountData = {
  account: AccountSummary
  orders: OrderResponse[]
  positions: PositionResponse[]
  positionHistory: PositionResponse[]
  ledgerEntries: LedgerEntry[]
  walletBalances: WalletBalance[]
}

export function isAuthSessionFailure(error: unknown) {
  return error instanceof ApiClientError && (error.status === 401 || error.status === 403)
}

export async function loadTradingAccountData(token: string, accountId: string): Promise<TradingAccountData> {
  const [account, orders, positions, positionHistory, ledgerEntries, walletBalances] = await Promise.all([
    getAccountSummary(accountId, token),
    getOrders(token),
    getPositions(accountId, token),
    getPositionHistory(accountId, token),
    getLedgerEntries(accountId, token),
    getWalletBalances(accountId, token)
  ])

  return { account, orders, positions, positionHistory, ledgerEntries, walletBalances }
}

export async function submitTradingOrder(payload: OrderPayload, token?: string | null) {
  if (!token) {
    throw new Error('Trading session token is required')
  }
  return createOrder(payload, token)
}

export function closeTradingPosition(accountId: string, positionId: string, token: string) {
  return closePosition(accountId, positionId, token)
}

export function updateTradingPositionProtection(
  accountId: string,
  positionId: string,
  payload: UpdatePositionProtectionPayload,
  token: string
) {
  return updatePositionProtection(accountId, positionId, payload, token)
}

export async function firstOrCreatedAccount(token: string) {
  const accounts = await getAccounts(token)
  return accounts[0] ?? createDemoAccount(token)
}
