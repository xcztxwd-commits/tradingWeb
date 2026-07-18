import { useCallback, useState } from 'react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'

import {
  cancelAllTradingOrders,
  closeAllTradingPositions,
  firstOrCreatedAccount,
  isAuthSessionFailure,
  loadTradingAccountData,
  mutateTradingPosition,
  runTradingBatchAction,
  submitTradingOco,
  submitTradingOrder,
  updateTradingPositionProtection
} from './tradingSession'
import type { PositionMutation } from './tradingSession'
import {
  createAccountRefreshCoordinator,
  createLatestSingleFlightRefreshGate
} from './accountRefreshCoordinator'
import { subscribeTradingSessionEvents } from '@fx-platform/frontend-core'
import type { AccountTransferResponse, FundingSettlement, Trade } from '@fx-platform/shared-types'
import {
  clearStoredAuthToken,
  getSessionStatus,
  readStoredAuthToken,
  type AccountSummary,
  type AssetLedgerEntry,
  type LedgerEntry,
  type OcoOrderPayload,
  type OrderPayload,
  type OrderResponse,
  type PositionResponse,
  type SessionAuthStatus,
  type UpdatePositionProtectionPayload,
  type WalletBalance
} from '@fx-platform/frontend-core'

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
  const [token, setToken] = useState<string | null>(null)
  const [account, setAccount] = useState<AccountSummary>()
  const [orders, setOrders] = useState<OrderResponse[]>([])
  const [trades, setTrades] = useState<Trade[]>([])
  const [positions, setPositions] = useState<PositionResponse[]>([])
  const [positionHistory, setPositionHistory] = useState<PositionResponse[]>([])
  const [fundingSettlements, setFundingSettlements] = useState<FundingSettlement[]>([])
  const [transfers, setTransfers] = useState<AccountTransferResponse[]>([])
  const [ledgerEntries, setLedgerEntries] = useState<LedgerEntry[]>([])
  const [assetLedgerEntries, setAssetLedgerEntries] = useState<AssetLedgerEntry[]>([])
  const [walletBalances, setWalletBalances] = useState<WalletBalance[]>([])
  const [sessionReady, setSessionReady] = useState(false)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const [sessionAuthStatus, setSessionAuthStatus] = useState<SessionAuthStatus>('guest')
  const [lastOrderError, setLastOrderError] = useState<unknown>(null)
  const [loginRequired, setLoginRequired] = useState(false)
  const [refreshGate] = useState(() =>
    createLatestSingleFlightRefreshGate<Awaited<ReturnType<typeof loadTradingAccountData>>>()
  )
  const accountId = account?.id

  const markSessionError = useCallback((error: unknown) => {
    setSessionError(formatTradingSessionError(error, t))
    setSessionReady(false)
  }, [t])

  const applyAccountData = useCallback((data: Awaited<ReturnType<typeof loadTradingAccountData>>) => {
    setAccount(data.account)
    setOrders(data.orders)
    setTrades(data.trades)
    setPositions(data.positions)
    setPositionHistory(data.positionHistory)
    setFundingSettlements(data.fundingSettlements)
    setTransfers(data.transfers)
    setLedgerEntries(data.ledgerEntries)
    setAssetLedgerEntries(data.assetLedgerEntries)
    setWalletBalances(data.walletBalances)
    setSessionError(null)
  }, [])

  const refreshAccountData = useCallback(
    async (accessToken = token, currentAccountId = accountId, isActive: () => boolean = () => true) => {
      if (!accessToken || !currentAccountId) return
      await refreshGate.request(
        () => loadTradingAccountData(accessToken, currentAccountId),
        (data) => {
          if (isActive()) applyAccountData(data)
        }
      )
    },
    [accountId, applyAccountData, refreshGate, token]
  )

  const clearSessionSnapshot = useCallback(() => {
    refreshGate.invalidate()
    setToken(null)
    setAccount(undefined)
    setOrders([])
    setTrades([])
    setPositions([])
    setPositionHistory([])
    setFundingSettlements([])
    setTransfers([])
    setLedgerEntries([])
    setAssetLedgerEntries([])
    setWalletBalances([])
    setSessionReady(false)
  }, [refreshGate])

  const requireLogin = useCallback((authStatus: SessionAuthStatus = 'guest') => {
    clearStoredAuthToken()
    clearSessionSnapshot()
    setSessionError(null)
    setSessionAuthStatus(authStatus)
    setLoginRequired(true)
  }, [clearSessionSnapshot])

  const loadSessionSnapshot = useCallback(
    async (isActive: () => boolean = () => true) => {
      refreshGate.invalidate()
      setSessionError(null)
      setSessionReady(false)
      setLoginRequired(false)

      const savedToken = readStoredAuthToken()
      const status = await getSessionStatus(savedToken)
      if (status.status === 'invalid_token') {
        if (isActive()) requireLogin('invalid_token')
        return
      }
      if (!savedToken || status.status === 'guest' || !status.authenticated) {
        if (isActive()) requireLogin('guest')
        return
      }

      const account = await firstOrCreatedAccount(savedToken)
      if (!isActive()) return

      setToken(savedToken)
      setSessionAuthStatus('valid_token')
      setAccount(account)
      await refreshAccountData(savedToken, account.id, isActive)
      if (isActive()) setSessionReady(true)
    },
    [refreshAccountData, refreshGate, requireLogin]
  )

  const retrySession = useCallback(async () => {
    try {
      await loadSessionSnapshot()
    } catch (error) {
      if (isAuthSessionFailure(error)) {
        requireLogin()
        return
      }
      markSessionError(error)
    }
  }, [loadSessionSnapshot, markSessionError, requireLogin])

  useEffect(() => {
    return () => refreshGate.invalidate()
  }, [refreshGate])

  useEffect(() => {
    let active = true

    async function bootSession() {
      try {
        await loadSessionSnapshot(() => active)
      } catch (error) {
        if (!active) return
        if (isAuthSessionFailure(error)) {
          requireLogin()
          return
        }
        markSessionError(error)
      }
    }

    void bootSession()
    return () => {
      active = false
    }
  }, [loadSessionSnapshot, markSessionError, requireLogin])

  useEffect(() => {
    if (!token || !accountId || !sessionReady) return
    let active = true

    const coordinator = createAccountRefreshCoordinator({
      refresh: () => refreshAccountData(token, accountId, () => active),
      pollMs: refreshMs,
      onError: (error) => {
        if (!active) return
        if (isAuthSessionFailure(error)) {
          requireLogin()
          return
        }
        markSessionError(error)
      }
    })
    coordinator.start()
    const unsubscribe = subscribeTradingSessionEvents(
      token,
      () => coordinator.notifyEvent(),
      () => coordinator.notifyReconnect()
    )

    return () => {
      active = false
      unsubscribe()
      coordinator.dispose()
    }
  }, [accountId, markSessionError, refreshAccountData, refreshMs, requireLogin, sessionReady, token])

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

        setOrders((current) => [response, ...current.filter((order) => order.id !== response.id)])

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
