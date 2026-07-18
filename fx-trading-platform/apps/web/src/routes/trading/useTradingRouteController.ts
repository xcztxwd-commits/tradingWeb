import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router-dom'
import { useTheme } from '@fx-platform/ui'

import {
  createTradingMarketPlaceholder,
  createTradingQuoteFromMarket,
  deriveTradingBalances,
  fetchMarketSymbolRules,
  fetchMarketSymbols,
  getRealtimeQuoteMarkets,
  hydrateMarketFavorites,
  startQuoteMarketDataAdapter,
  useMarketFavorites,
  useTradingSession,
  type TradingInstrumentRules,
  type TradingMarket
} from '@fx-platform/frontend-core'
import { resolveTradingPath, writeLastTradingSymbol } from '../../app/hooks/useLastTradingSymbol'
import {
  defaultTradingSymbols,
  normalizeTradingProductSymbol,
  type TradingProduct
} from '../../app/tradingRoutes'
import {
  formatTradingChartTitle,
  getTradingMarketsForProduct,
  mergeWithLocalTradingMarkets
} from '../../pages/trading/tradingPageMarketSelection'
import {
  getTradingSessionStatusLabel,
  getTradingSessionStatusText
} from '../../pages/trading/tradingPageSessionStatus'
import {
  getTradeMinOrderAmount,
  getTradePricePrecision,
  getTradeQuantityPrecision,
  mergeMarketRules
} from '../../pages/trading/tradingPageTradeRules'
import { usePerpetualTradingControls } from '../../pages/trading/usePerpetualTradingControls'
import { useTradingChartSettings } from '../../pages/trading/useTradingChartSettings'
import { useTradingMarketDataStatus } from '../../pages/trading/useTradingMarketDataStatus'
import { useTradingQuoteMap } from '../../pages/trading/useTradingQuotes'
import { translateCoreMessage } from '../shared/translateCoreMessage'
import type { TradingRouteModel } from './tradingRoute.types'

type TradingRouteControllerOptions = {
  product: TradingProduct
}

export function useTradingRouteController({ product }: TradingRouteControllerOptions) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { symbol: routeSymbol } = useParams<{ symbol?: string }>()
  const routedSymbol = normalizeTradingProductSymbol(product, routeSymbol) ?? defaultTradingSymbols[product]
  const { currentTheme } = useTheme()
  const [selectedSymbol, setSelectedSymbol] = useState(routedSymbol)
  const [markets, setMarkets] = useState<TradingMarket[]>([])
  const [marketDrawerOpen, setMarketDrawerOpen] = useState(false)
  const [quoteDrawerOpen, setQuoteDrawerOpen] = useState(false)
  const [orderSheetOpen, setOrderSheetOpen] = useState(false)
  const [loginPromptRequested, setLoginPromptRequested] = useState(false)
  const [pendingTradeOpen, setPendingTradeOpen] = useState(false)
  const [tradePricePrefill, setTradePricePrefill] = useState<{ id: number; price: number } | null>(null)
  const [selectedRules, setSelectedRules] = useState<TradingInstrumentRules | null>(null)
  const {
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
    walletBalances,
    sessionReady,
    sessionMode,
    sessionError: sessionErrorMessage,
    sessionAuthStatus,
    loginRequired,
    retrySession,
    submitOrder,
    submitOco,
    cancelAllOrders,
    closeAllPositions,
    closePosition
  } = useTradingSession()
  const sessionError = sessionErrorMessage ? translateCoreMessage(sessionErrorMessage, t) : null
  const { favorites: favoriteSymbols, toggleFavorite } = useMarketFavorites(token)
  const { getStatusView, handleQuoteStatus } = useTradingMarketDataStatus()
  const favoriteHydratedMarkets = useMemo(
    () => hydrateMarketFavorites(markets, favoriteSymbols),
    [favoriteSymbols, markets]
  )
  const selectedMarket = useMemo(
    () => favoriteHydratedMarkets.find((market) => market.symbol === selectedSymbol)
      ?? createTradingMarketPlaceholder(selectedSymbol),
    [favoriteHydratedMarkets, selectedSymbol]
  )
  const selectedMarketWithRules = useMemo(
    () => mergeMarketRules(selectedMarket, selectedRules),
    [selectedMarket, selectedRules]
  )
  const visibleMarkets = useMemo(() => {
    const nextMarkets = favoriteHydratedMarkets.length > 0
      ? favoriteHydratedMarkets
      : [selectedMarketWithRules]
    return nextMarkets.map((market) => market.symbol === selectedSymbol ? selectedMarketWithRules : market)
  }, [favoriteHydratedMarkets, selectedMarketWithRules, selectedSymbol])
  const quoteMarkets = useMemo(
    () => getRealtimeQuoteMarkets(visibleMarkets, selectedSymbol),
    [selectedSymbol, visibleMarkets]
  )
  const { quotes, sourceNotice } = useTradingQuoteMap(quoteMarkets, token, handleQuoteStatus)
  const selectedQuote = quotes[selectedSymbol] ?? createTradingQuoteFromMarket(selectedMarketWithRules)
  const marketDataStatusView = getStatusView(selectedSymbol, selectedQuote.source)
  const { chartCallbacks, chartSettings, chartThemeMode, indicators } = useTradingChartSettings(
    selectedSymbol,
    currentTheme.colorScheme
  )
  const terminalLoading = sessionMode === 'loading'
  const sessionStatusLabel = getTradingSessionStatusLabel(sessionMode, sessionAuthStatus, t)
  const sessionStatusText = getTradingSessionStatusText({
    sessionMode,
    sessionAuthStatus,
    sessionError,
    loginRequired,
    t
  })
  const balances = useMemo(
    () => deriveTradingBalances(product, account, positions, selectedSymbol, walletBalances),
    [account, positions, product, selectedSymbol, walletBalances]
  )
  const perpetualControls = usePerpetualTradingControls({
    accountId,
    token,
    market: selectedMarketWithRules,
    enabled: product === 'perpetual',
    expectedSource: selectedQuote.marketSource
  })
  const tradeMinOrderAmount = getTradeMinOrderAmount(selectedMarketWithRules)
  const tradePricePrecision = getTradePricePrecision(selectedMarketWithRules)
  const tradeQuantityPrecision = getTradeQuantityPrecision(selectedMarketWithRules, tradeMinOrderAmount)

  useEffect(() => startQuoteMarketDataAdapter(selectedSymbol, token), [selectedSymbol, token])
  useEffect(() => {
    setTradePricePrefill(null)
  }, [selectedSymbol])

  useEffect(() => {
    if (routeSymbol !== undefined && normalizeTradingProductSymbol(product, routeSymbol) === null) {
      navigate(resolveTradingPath(product), { replace: true })
      return
    }
    writeLastTradingSymbol(product, routedSymbol)
    setSelectedSymbol(routedSymbol)
  }, [navigate, product, routeSymbol, routedSymbol])

  useEffect(() => {
    let active = true

    void fetchMarketSymbols()
      .then((nextMarkets) => {
        if (!active) return
        const mergedMarkets = mergeWithLocalTradingMarkets(nextMarkets)
        const productMarkets = getTradingMarketsForProduct(mergedMarkets, product)
        setMarkets(productMarkets)
        setSelectedSymbol((current) => productMarkets.some((market) => market.symbol === current)
          ? current
          : routedSymbol)
      })
      .catch(() => {
        if (active) setMarkets(getTradingMarketsForProduct(mergeWithLocalTradingMarkets([]), product))
      })

    return () => {
      active = false
    }
  }, [product, routedSymbol])

  useEffect(() => {
    let active = true
    setSelectedRules(null)

    void fetchMarketSymbolRules(selectedSymbol)
      .then((rules) => {
        if (active) setSelectedRules(rules)
      })
      .catch(() => {
        if (active) setSelectedRules(null)
      })

    return () => {
      active = false
    }
  }, [selectedSymbol])

  useEffect(() => {
    if (!loginRequired) setLoginPromptRequested(false)
  }, [loginRequired])

  const selectSymbol = useCallback((symbol: string) => {
    const normalized = normalizeTradingProductSymbol(product, symbol)
    if (!normalized) return
    setSelectedSymbol(normalized)
    writeLastTradingSymbol(product, normalized)
    navigate(resolveTradingPath(product, normalized))
    setMarketDrawerOpen(false)
  }, [navigate, product])

  const handleLoginRedirect = useCallback(() => {
    navigate(`/login?redirect=${encodeURIComponent(resolveTradingPath(product, selectedSymbol))}`)
  }, [navigate, product, selectedSymbol])

  const handleTradeLoginRequired = useCallback(() => {
    setOrderSheetOpen(false)
    setLoginPromptRequested(true)
  }, [])

  useEffect(() => {
    if (!pendingTradeOpen || sessionMode === 'loading') return
    setPendingTradeOpen(false)
    if (loginRequired) handleTradeLoginRequired()
    else setOrderSheetOpen(true)
  }, [handleTradeLoginRequired, loginRequired, pendingTradeOpen, sessionMode])

  const handleOpenTrade = useCallback(() => {
    if (sessionMode === 'loading') {
      setPendingTradeOpen(true)
      return
    }
    if (loginRequired) {
      handleTradeLoginRequired()
      return
    }
    setOrderSheetOpen(true)
  }, [handleTradeLoginRequired, loginRequired, sessionMode])

  const handleSelectPrice = useCallback((price: number) => {
    if (!Number.isFinite(price) || price <= 0) return
    setTradePricePrefill({ id: Date.now(), price })
  }, [])

  const accountPanel = {
    account,
    ledgerEntries,
    loading: terminalLoading,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    sessionReady,
    onCancelAllOrders: cancelAllOrders,
    onCloseAllPositions: closeAllPositions,
    onClosePosition: closePosition
  }
  const routeModel: TradingRouteModel = {
    accountPanel,
    accountId,
    balances,
    chartCallbacks,
    chartSettings,
    chartThemeMode,
    chartTitle: formatTradingChartTitle(selectedMarketWithRules, t),
    indicators,
    loginRequired,
    market: selectedMarketWithRules,
    favorites: favoriteSymbols,
    marketDataStatusView: marketDataStatusView,
    markets: visibleMarkets,
    onLoginRequired: handleTradeLoginRequired,
    onOpenMarkets: () => setMarketDrawerOpen(true),
    onOpenQuote: () => setQuoteDrawerOpen(true),
    onOpenTrade: handleOpenTrade,
    onSelectPrice: handleSelectPrice,
    onRetrySession: retrySession,
    onSelectSymbol: selectSymbol,
    onFavorite: toggleFavorite,
    product,
    quote: selectedQuote,
    quotes,
    sessionError,
    sessionReady,
    sessionStatusLabel,
    sessionStatusText,
    submitOrder,
    submitOco,
    perpetualControls,
    symbol: selectedSymbol,
    tradeMinOrderAmount,
    tradePricePrecision,
    tradeQuantityPrecision,
    tradePricePrefill,
    terminalLoading,
    token,
    tradePanelSessionMode: sessionMode
  }

  return {
    model: routeModel,
    sourceNotice,
    marketDrawerOpen,
    quoteDrawerOpen,
    orderSheetOpen,
    loginPromptOpen: loginRequired && loginPromptRequested,
    closeMarketDrawer: () => setMarketDrawerOpen(false),
    closeQuoteDrawer: () => setQuoteDrawerOpen(false),
    closeOrderSheet: () => setOrderSheetOpen(false),
    closeLoginPrompt: () => setLoginPromptRequested(false),
    login: handleLoginRedirect
  }
}
