import { createDemoAccount, getAccounts, getAccountSummary, getAssetLedger, getWalletBalances } from '../../services/accountApi'
import { ApiClientError } from '../../services/apiClient'
import { getLedgerEntries } from '../../services/ledgerApi'
import {
  adjustPositionMargin,
  closePosition,
  createOcoOrder,
  createOrder,
  createPositionProtection,
  getAccountTransfers,
  getFundingSettlements,
  getOrders,
  getPositionHistory,
  getPositions,
  getTrades,
  updatePositionProtection
} from '../../services/tradingApi'
import type {
  AccountTransferResponse,
  AdjustPositionMarginRequest,
  ClosePositionRequest,
  CreateProtectionRequest,
  FundingSettlement,
  Trade
} from '@fx-platform/shared-types'
import type { OrderResponse, PositionResponse } from '../../components/tables/types'
import type { AccountSummary, AssetLedgerEntry, LedgerEntry, OcoOrderPayload, OrderPayload, UpdatePositionProtectionPayload, WalletBalance } from '../../types/trading'

export { deriveTradingBalances } from './tradingSessionModels'
export type { TradingBalances } from './tradingSessionModels'

export type DemoTradingSession = {
  token: string
  account: AccountSummary
}

export type TradingAccountData = {
  account: AccountSummary
  orders: OrderResponse[]
  trades: Trade[]
  positions: PositionResponse[]
  positionHistory: PositionResponse[]
  fundingSettlements: FundingSettlement[]
  transfers: AccountTransferResponse[]
  ledgerEntries: LedgerEntry[]
  assetLedgerEntries: AssetLedgerEntry[]
  walletBalances: WalletBalance[]
}

export type PositionMutation =
  | { type: 'FULL_CLOSE' }
  | { type: 'PARTIAL_CLOSE'; payload: ClosePositionRequest }
  | { type: 'ADJUST_MARGIN'; payload: AdjustPositionMarginRequest }
  | { type: 'CREATE_PROTECTIONS'; payloads: CreateProtectionRequest[] }

export function isAuthSessionFailure(error: unknown) {
  return error instanceof ApiClientError && (error.status === 401 || error.status === 403)
}

export async function loadTradingAccountData(token: string, accountId: string): Promise<TradingAccountData> {
  const [
    account,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances
  ] = await Promise.all([
    getAccountSummary(accountId, token),
    getOrders(accountId, token),
    getTrades(accountId, token),
    getPositions(accountId, token),
    getPositionHistory(accountId, token),
    getFundingSettlements(accountId, token),
    getAccountTransfers(accountId, token),
    getLedgerEntries(accountId, token),
    getAssetLedger(accountId, token),
    getWalletBalances(accountId, token)
  ])

  return {
    account,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances
  }
}

export async function submitTradingOrder(payload: OrderPayload, token?: string | null) {
  if (!token) {
    throw new Error('Trading session token is required')
  }
  return createOrder(payload, token)
}

export function closeTradingPosition(
  accountId: string,
  positionId: string,
  token: string,
  payload?: ClosePositionRequest
) {
  return closePosition(accountId, positionId, token, payload)
}

export async function submitTradingOco(payload: OcoOrderPayload, token?: string | null) {
  if (!token) throw new Error('Trading session token is required')
  return createOcoOrder(payload, token)
}

export async function mutateTradingPosition(
  accountId: string,
  positionId: string,
  mutation: PositionMutation,
  token: string
) {
  switch (mutation.type) {
    case 'FULL_CLOSE':
      return closeTradingPosition(accountId, positionId, token)
    case 'PARTIAL_CLOSE':
      return closeTradingPosition(accountId, positionId, token, mutation.payload)
    case 'ADJUST_MARGIN':
      return adjustPositionMargin(positionId, mutation.payload, token)
    case 'CREATE_PROTECTIONS': {
      const { payloads } = mutation
      const responses = []
      for (const payload of payloads) {
        responses.push(await createPositionProtection(positionId, payload, token))
      }
      return responses
    }
  }
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
