import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

import {
  cancelAllTradingOrders,
  closeAllTradingPositions,
  mutateTradingPosition,
  runTradingBatchAction,
  submitTradingOco,
  submitTradingOrder,
  updateTradingPositionProtection
} from './tradingSession'
import type { PositionMutation } from './tradingSession'
import {
  useAccountData,
  type OcoOrderPayload,
  type OrderPayload,
  type OrderResponse,
  type PositionResponse,
  type UpdatePositionProtectionPayload
} from '@fx-platform/frontend-core'
import { translateCoreMessage } from '../../routes/shared/translateCoreMessage'

type Options = {
  refreshMs?: number
}

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => {
  if (key === 'trading.backendSessionFailed') return 'Backend session connection failed. Try again later.'
  if (key === 'trading.loginBeforeOrder') return 'Log in before placing an order.'
  return key
}

export type TradingSessionMode = 'loading' | 'ready' | 'login-required' | 'error'

type TradingSessionModeInput = {
  sessionReady: boolean
  token?: string | null
  sessionError?: string | null
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

export function formatTradingSessionError(error: unknown, t: Translate = defaultTranslate) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return t('trading.backendSessionFailed')
}

export function useTradingSession({ refreshMs = 15_000 }: Options = {}) {
  const { t } = useTranslation()
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
  const sessionError = translateCoreMessage(accountData.sessionError, t)
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
          throw new Error(t('trading.loginBeforeOrder'))
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
    [accountId, loginRequired, refreshAccountData, t, token]
  )

  const submitOco = useCallback(
    async (payload: OcoOrderPayload) => {
      setLastOrderError(null)
      try {
        if (loginRequired) throw new Error(t('trading.loginBeforeOrder'))
        if (!token || !accountId) throw new Error('Trading account is not ready')
        const response = await submitTradingOco({ ...payload, accountId }, token)
        await refreshAccountData(token, accountId).catch(() => undefined)
        return response
      } catch (error) {
        setLastOrderError(error)
        throw error
      }
    },
    [accountId, loginRequired, refreshAccountData, t, token]
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
