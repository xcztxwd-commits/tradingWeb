import { useCallback, useMemo, useState } from 'react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'

import {
  closeTradingPosition,
  firstOrCreatedAccount,
  isAuthSessionFailure,
  loadTradingAccountData,
  submitTradingOrder,
  updateTradingPositionProtection
} from './tradingSession'
import { getSessionStatus } from '../../services/authApi'
import type { SessionAuthStatus } from '../../services/authApi'
import { subscribeQuote } from '../../services/marketStream'
import type { PositionResponse, OrderResponse } from '../../components/tables/types'
import type { AccountSummary, LedgerEntry, OrderPayload, UpdatePositionProtectionPayload } from '../../types/trading'
import { clearStoredAuthToken, readStoredAuthToken } from './tradingSessionStorage'
import { repriceOpenPositionsForQuote } from './tradingSessionPositions'

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

export function useTradingSession({ refreshMs = 2000 }: Options = {}) {
  const { t } = useTranslation()
  const [token, setToken] = useState<string | null>(null)
  const [account, setAccount] = useState<AccountSummary>()
  const [orders, setOrders] = useState<OrderResponse[]>([])
  const [positions, setPositions] = useState<PositionResponse[]>([])
  const [positionHistory, setPositionHistory] = useState<PositionResponse[]>([])
  const [ledgerEntries, setLedgerEntries] = useState<LedgerEntry[]>([])
  const [sessionReady, setSessionReady] = useState(false)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const [sessionAuthStatus, setSessionAuthStatus] = useState<SessionAuthStatus>('guest')
  const [lastOrderError, setLastOrderError] = useState<unknown>(null)
  const [loginRequired, setLoginRequired] = useState(false)
  const accountId = useMemo(() => account?.id, [account])
  const positionQuoteSymbolsKey = useMemo(() => {
    return Array.from(new Set(
      positions
        .filter((position) => position.status.toUpperCase() === 'OPEN')
        .map((position) => position.symbol)
    ))
      .sort()
      .join('|')
  }, [positions])

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
    }, refreshMs)

    return () => {
      active = false
      window.clearInterval(interval)
    }
  }, [accountId, markSessionError, refreshAccountData, refreshMs, requireLogin, sessionReady, token])

  useEffect(() => {
    if (!sessionReady || !positionQuoteSymbolsKey) return

    const unsubscribe = positionQuoteSymbolsKey
      .split('|')
      .filter(Boolean)
      .map((symbol) => subscribeQuote(symbol, token, (quote) => {
        setPositions((current) => repriceOpenPositionsForQuote(current, quote))
      }))

    return () => {
      for (const unsubscribeSymbol of unsubscribe) unsubscribeSymbol()
    }
  }, [positionQuoteSymbolsKey, sessionReady, token])

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

  const submitClosePosition = useCallback(
    async (position: PositionResponse) => {
      if (!token || !accountId) return
      await closeTradingPosition(accountId, position.id, token)
      await refreshAccountData(token, accountId)
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
    sessionReady,
    sessionError,
    sessionAuthStatus,
    loginRequired,
    lastOrderError,
    sessionMode: getTradingSessionMode({ sessionReady, token, sessionError, loginRequired }),
    refreshAccountData,
    retrySession,
    submitOrder,
    closePosition: submitClosePosition,
    updatePositionProtection: submitProtectionUpdate
  }
}
