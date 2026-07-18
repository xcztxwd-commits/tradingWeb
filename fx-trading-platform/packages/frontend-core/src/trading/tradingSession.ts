import {
  adjustPositionMargin,
  cancelAllOrders,
  closePosition,
  closeAllPositions,
  createOcoOrder,
  createOrder,
  createPositionProtection,
  updatePositionProtection
} from '../api/tradingApi.ts'
import { firstOrCreatedAccount, loadAccountData } from '../account/accountOperations.ts'
import { isAuthSessionFailure } from '../account/accountErrors.ts'
import {
  deriveTradingBalances,
  type AccountSessionData,
  type TradingBalances
} from '../account/accountSessionModels.ts'
import type {
  AccountSummary,
  OcoOrderPayload,
  OrderPayload,
  UpdatePositionProtectionPayload
} from '../models/index.ts'
import type {
  AdjustPositionMarginRequest,
  BatchActionRequest,
  BatchActionResponse,
  ClosePositionRequest,
  CreateProtectionRequest
} from '@fx-platform/shared-types'
export { deriveTradingBalances, firstOrCreatedAccount, isAuthSessionFailure }
export type { TradingBalances }

export type DemoTradingSession = {
  token: string
  account: AccountSummary
}

export type TradingAccountData = AccountSessionData

export type PositionMutation =
  | { type: 'FULL_CLOSE' }
  | { type: 'PARTIAL_CLOSE'; payload: ClosePositionRequest }
  | { type: 'ADJUST_MARGIN'; payload: AdjustPositionMarginRequest }
  | { type: 'CREATE_PROTECTIONS'; payloads: CreateProtectionRequest[] }

export const loadTradingAccountData = loadAccountData

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

export function cancelAllTradingOrders(payload: BatchActionRequest, token: string) {
  return cancelAllOrders(payload, token)
}

export function closeAllTradingPositions(payload: BatchActionRequest, token: string) {
  return closeAllPositions(payload, token)
}

type TradingBatchAction<T> = (payload: BatchActionRequest, token: string) => Promise<T>

export async function runTradingBatchAction<T = BatchActionResponse>(
  accountId: string,
  token: string,
  action: TradingBatchAction<T>,
  refresh: () => Promise<unknown>
) {
  const payload = { accountId, requestId: globalThis.crypto.randomUUID() }
  try {
    return await action(payload, token)
  } finally {
    await refresh().catch(() => undefined)
  }
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
