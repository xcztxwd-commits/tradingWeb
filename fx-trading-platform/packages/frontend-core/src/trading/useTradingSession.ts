import { useCallback, useMemo, useState } from 'react'

import {
  cancelAllTradingOrders,
  closeAllTradingPositions,
  mutateTradingPosition,
  runTradingBatchAction,
  submitTradingOco,
  submitTradingOrder,
  updateTradingPositionProtection
} from './tradingSession.ts'
import type { PositionMutation } from './tradingSession.ts'
import { useAccountData } from '../account/useAccountData.ts'
import { ApiClientError } from '../api/apiClient.ts'
import type { CoreMessage } from '../coreMessage.ts'
import type {
  OcoOrderPayload,
  OrderPayload,
  OrderResponse,
  PositionResponse,
  UpdatePositionProtectionPayload
} from '../models/index.ts'

type Options = {
  refreshMs?: number
}

const backendSessionFallback = 'Backend session connection failed. Try again later.'
const loginRequiredFallback = 'Log in before placing an order.'

export type TradingSessionMode = 'loading' | 'ready' | 'login-required' | 'error'

type TradingSessionModeInput = {
  sessionReady: boolean
  token?: string | null
  sessionError?: CoreMessage | string | null
  loginRequired?: boolean
}

export function getTradingSessionMode({
  sessionReady,
  token,
  sessionError,
  loginRequired
}: TradingSessionModeInput): TradingSessionMode {
  if (sessionError) return 'error'
  if (loginRequired) return 'login-required'
  if (sessionReady) return 'ready'
  return 'loading'
}

export function formatTradingSessionError(error: unknown): CoreMessage {
  const message = error instanceof Error && error.message
    ? error.message
    : typeof error === 'string' && error
      ? error
      : backendSessionFallback
  const requestId = error instanceof ApiClientError
    ? error.requestId
    : getStringProperty(error, 'requestId')
  return {
    key: 'trading.backendSessionFailed',
    values: {
      message,
      ...(requestId ? { requestId } : {})
    }
  }
}

export function useTradingSession({ refreshMs = 15_000 }: Options = {}) {
  const accountData = useAccountData({ refreshMs })
  const {
    token,
    account,
    accountId,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances,
    sessionReady,
    sessionAuthStatus,
    loginRequired,
    retrySession
  } = accountData
  const sessionError = accountData.sessionError
  const [submittedOrders, setSubmittedOrders] = useState<OrderResponse[]>([])
  const [lastOrderError, setLastOrderError] = useState<unknown>(null)
  const orders = useMemo(
    () => [
      ...accountData.orders,
      ...submittedOrders.filter((submitted) => !accountData.orders.some((order) => order.id === submitted.id))
    ],
    [accountData.orders, submittedOrders]
  )

  const refreshAccountData = useCallback(
    async (_accessToken = token, _currentAccountId = accountId) => accountData.refreshAccountData(),
    [accountData.refreshAccountData, accountId, token]
  )

  const submitOrder = useCallback(
    async (payload: OrderPayload) => {
      setLastOrderError(null)
      try {
        if (loginRequired) {
          throw new Error(loginRequiredFallback)
        }
        if (token && !accountId) {
          throw new Error('Trading account is not ready')
        }
        const response = await submitTradingOrder(accountId ? { ...payload, accountId } : payload, token)

        setSubmittedOrders((current) => [response, ...current.filter((order) => order.id !== response.id)])

        if (token && accountId) {
          await refreshAccountData(token, accountId).catch(() => undefined)
        }

        return response
      } catch (error) {
        setLastOrderError(error)
        throw error
      }
    },
    [accountId, loginRequired, refreshAccountData, token]
  )

  const submitOco = useCallback(
    async (payload: OcoOrderPayload) => {
      setLastOrderError(null)
      try {
        if (loginRequired) throw new Error(loginRequiredFallback)
        if (!token || !accountId) throw new Error('Trading account is not ready')
        const response = await submitTradingOco({ ...payload, accountId }, token)
        await refreshAccountData(token, accountId).catch(() => undefined)
        return response
      } catch (error) {
        setLastOrderError(error)
        throw error
      }
    },
    [accountId, loginRequired, refreshAccountData, token]
  )

  const submitClosePosition = useCallback(
    async (position: PositionResponse, mutation: PositionMutation = { type: 'FULL_CLOSE' }) => {
      if (!token || !accountId) return
      try {
        await mutateTradingPosition(accountId, position.id, mutation, token)
      } finally {
        await refreshAccountData(token, accountId).catch(() => undefined)
      }
    },
    [accountId, refreshAccountData, token]
  )

  const submitCancelAllOrders = useCallback(async () => {
    if (!token || !accountId) throw new Error('Trading account is not ready')
    return runTradingBatchAction(
      accountId,
      token,
      cancelAllTradingOrders,
      () => refreshAccountData(token, accountId)
    )
  }, [accountId, refreshAccountData, token])

  const submitCloseAllPositions = useCallback(async () => {
    if (!token || !accountId) throw new Error('Trading account is not ready')
    return runTradingBatchAction(
      accountId,
      token,
      closeAllTradingPositions,
      () => refreshAccountData(token, accountId)
    )
  }, [accountId, refreshAccountData, token])

  const submitProtectionUpdate = useCallback(
    async (position: PositionResponse, payload: UpdatePositionProtectionPayload) => {
      if (!token || !accountId) return
      await updateTradingPositionProtection(accountId, position.id, payload, token)
      await refreshAccountData(token, accountId)
    },
    [accountId, refreshAccountData, token]
  )

  return {
    token,
    account,
    accountId,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances,
    sessionReady,
    sessionError,
    sessionAuthStatus,
    loginRequired,
    lastOrderError,
    sessionMode: getTradingSessionMode({ sessionReady, token, sessionError, loginRequired }),
    refreshAccountData,
    retrySession,
    submitOrder,
    submitOco,
    cancelAllOrders: submitCancelAllOrders,
    closeAllPositions: submitCloseAllPositions,
    closePosition: submitClosePosition,
    updatePositionProtection: submitProtectionUpdate
  }
}

function getStringProperty(value: unknown, key: string) {
  if (!value || typeof value !== 'object') return undefined
  const property = (value as Record<string, unknown>)[key]
  return typeof property === 'string' && property ? property : undefined
}
