import { useCallback, useMemo, useState } from 'react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'

import {
  firstOrCreatedAccount,
  isAuthSessionFailure,
  loadTradingAccountData,
  mutateTradingPosition,
  submitTradingOco,
  submitTradingOrder,
  updateTradingPositionProtection
} from './tradingSession'
import type { PositionMutation } from './tradingSession'
import { getSessionStatus } from '../../services/authApi'
import type { SessionAuthStatus } from '../../services/authApi'
import { subscribeTradingSessionEvents } from '../../services/marketStream'
import type { PositionResponse, OrderResponse } from '../../components/tables/types'
import type { AccountSummary, AssetLedgerEntry, LedgerEntry, OcoOrderPayload, OrderPayload, UpdatePositionProtectionPayload, WalletBalance } from '../../types/trading'
import { clearStoredAuthToken, readStoredAuthToken } from './tradingSessionStorage'

type Options = {
  refreshMs?: number
}

type Translate = (key: string, options?: Record<string, unknown>) => string
type TradingSessionVisibility = 'visible' | 'hidden' | string

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

export function getTradingSessionRefreshMs(
  refreshMs: number,
  visibilityState: TradingSessionVisibility = getDocumentVisibilityState()
) {
  return visibilityState === 'hidden' ? Math.max(refreshMs * 6, 15000) : refreshMs
}

function getDocumentVisibilityState(): TradingSessionVisibility {
  if (typeof document === 'undefined') return 'visible'
  return document.visibilityState
}

export function useTradingSession({ refreshMs = 2000 }: Options = {}) {
  const { t } = useTranslation()
  const [token, setToken] = useState<string | null>(null)
  const [account, setAccount] = useState<AccountSummary>()
  const [orders, setOrders] = useState<OrderResponse[]>([])
  const [positions, setPositions] = useState<PositionResponse[]>([])
  const [positionHistory, setPositionHistory] = useState<PositionResponse[]>([])
  const [ledgerEntries, setLedgerEntries] = useState<LedgerEntry[]>([])
  const [assetLedgerEntries, setAssetLedgerEntries] = useState<AssetLedgerEntry[]>([])
  const [walletBalances, setWalletBalances] = useState<WalletBalance[]>([])
  const [sessionReady, setSessionReady] = useState(false)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const [sessionAuthStatus, setSessionAuthStatus] = useState<SessionAuthStatus>('guest')
  const [lastOrderError, setLastOrderError] = useState<unknown>(null)
  const [loginRequired, setLoginRequired] = useState(false)
  const [visibilityState, setVisibilityState] = useState<TradingSessionVisibility>(() => getDocumentVisibilityState())
  const accountId = useMemo(() => account?.id, [account])
  const effectiveRefreshMs = getTradingSessionRefreshMs(refreshMs, visibilityState)

  const markSessionError = useCallback((error: unknown) => {
    setSessionError(formatTradingSessionError(error, t))
    setSessionReady(false)
  }, [t])

  const applyAccountData = useCallback((data: Awaited<ReturnType<typeof loadTradingAccountData>>) => {
    setAccount(data.account)
    setOrders(data.orders)
    setPositions(data.positions)
    setPositionHistory(data.positionHistory)
    setLedgerEntries(data.ledgerEntries)
    setAssetLedgerEntries(data.assetLedgerEntries)
    setWalletBalances(data.walletBalances)
    setSessionError(null)
  }, [])

  const refreshAccountData = useCallback(
    async (accessToken = token, currentAccountId = accountId, isActive: () => boolean = () => true) => {
      if (!accessToken || !currentAccountId) return
      const data = await loadTradingAccountData(accessToken, currentAccountId)
      if (isActive()) applyAccountData(data)
    },
    [accountId, applyAccountData, token]
  )

  const clearSessionSnapshot = useCallback(() => {
    setToken(null)
    setAccount(undefined)
    setOrders([])
    setPositions([])
    setPositionHistory([])
    setLedgerEntries([])
    setAssetLedgerEntries([])
    setWalletBalances([])
    setSessionReady(false)
  }, [])

  const requireLogin = useCallback((authStatus: SessionAuthStatus = 'guest') => {
    clearStoredAuthToken()
    clearSessionSnapshot()
    setSessionError(null)
    setSessionAuthStatus(authStatus)
    setLoginRequired(true)
  }, [clearSessionSnapshot])

  const loadSessionSnapshot = useCallback(
    async (isActive: () => boolean = () => true) => {
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
    [refreshAccountData, requireLogin]
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
    if (typeof document === 'undefined') return

    const syncVisibility = () => setVisibilityState(getDocumentVisibilityState())
    syncVisibility()
    document.addEventListener('visibilitychange', syncVisibility)

    return () => {
      document.removeEventListener('visibilitychange', syncVisibility)
    }
  }, [])

  useEffect(() => {
    if (visibilityState !== 'visible' || !token || !accountId || !sessionReady) return
    let active = true

    void refreshAccountData(token, accountId, () => active).catch((error) => {
      if (!active) return
      if (isAuthSessionFailure(error)) {
        requireLogin()
        return
      }
      markSessionError(error)
    })

    return () => {
      active = false
    }
  }, [accountId, markSessionError, refreshAccountData, requireLogin, sessionReady, token, visibilityState])

  useEffect(() => {
    if (!token || !accountId || !sessionReady) return
    let active = true
    const interval = window.setInterval(() => {
      void refreshAccountData(token, accountId, () => active).catch((error) => {
        if (!active) return
        if (isAuthSessionFailure(error)) {
          requireLogin()
          return
        }
        markSessionError(error)
      })
    }, effectiveRefreshMs)

    return () => {
      active = false
      window.clearInterval(interval)
    }
  }, [accountId, effectiveRefreshMs, markSessionError, refreshAccountData, requireLogin, sessionReady, token])

  useEffect(() => {
    if (!token || !accountId || !sessionReady) return
    let active = true
    const unsubscribe = subscribeTradingSessionEvents(accountId, token, () => {
      void refreshAccountData(token, accountId, () => active).catch((error) => {
        if (!active) return
        if (isAuthSessionFailure(error)) {
          requireLogin()
          return
        }
        markSessionError(error)
      })
    })

    return () => {
      active = false
      unsubscribe()
    }
  }, [accountId, markSessionError, refreshAccountData, requireLogin, sessionReady, token])

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
    positions,
    positionHistory,
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
    closePosition: submitClosePosition,
    updatePositionProtection: submitProtectionUpdate
  }
}
