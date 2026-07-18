import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router-dom'
import { useTheme } from '@fx-platform/ui'
import { formatTradingChartTitle, getTradingMarketsForProduct, mergeWithLocalTradingMarkets } from './tradingPageMarketSelection'
import { getTradingSessionStatusLabel, getTradingSessionStatusText } from './tradingPageSessionStatus'
import { getTradeMinOrderAmount, getTradePricePrecision, getTradeQuantityPrecision, mergeMarketRules } from './tradingPageTradeRules'
import type { TradingTerminalViewProps } from './tradingPageViewModels'
import { useTradingMarketDataStatus } from './useTradingMarketDataStatus'
import { useMobileTerminalViewport } from './useMobileTerminalViewport'
import { useTradingChartSettings } from './useTradingChartSettings'
import { useResizableLayout } from '../../hooks/useResizableLayout'
import { MarketSidebar } from './components/MarketSidebar'
import { MobileDrawer } from './components/MobilePanels'
import { LoginPromptDialog } from './components/LoginPromptDialog'
import { RightTradingPanel } from './components/RightTradingPanel'
import { TradingDesktopView } from './components/TradingDesktopView'
import { TradingMobileView } from './components/TradingMobileView'
import { MarketSourceChangeNotice } from './components/MarketSourceChangeNotice'
import { usePerpetualTradingControls } from './usePerpetualTradingControls'
import { TradingOrderSheet } from './components/TradingOrderSheet'
import {
  createTradingMarketPlaceholder, createTradingQuoteFromMarket,
  fetchMarketSymbolRules, fetchMarketSymbols, getRealtimeQuoteMarkets,
  hydrateMarketFavorites, startQuoteMarketDataAdapter, useMarketFavorites,
  type TradingInstrumentRules, type TradingMarket
} from '@fx-platform/frontend-core'
import { useTradingQuoteMap } from './useTradingQuotes'
import { deriveTradingBalances } from '../../features/trading-session/tradingSession'
import { useTradingSession } from '../../features/trading-session/useTradingSession'
import type { TradingSessionMode } from '../../features/trading-session/useTradingSession'
import { resolveTradingPath, writeLastTradingSymbol } from '../../app/hooks/useLastTradingSymbol'
import { defaultTradingSymbols, normalizeTradingProductSymbol, type TradingProduct } from '../../app/tradingRoutes'
import styles from './TradingPage.module.css'

type TradingPageProps = { product: TradingProduct }
export function TradingPage({ product }: TradingPageProps) {
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
  const [workspaceResetSignal, setWorkspaceResetSignal] = useState(0)
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
    sessionError,
    sessionAuthStatus,
    loginRequired,
    retrySession,
    submitOrder, submitOco, cancelAllOrders, closeAllPositions, closePosition
  } = useTradingSession()
  const { favorites: favoriteSymbols, toggleFavorite } = useMarketFavorites(token)
  const { layout, activePreset, applyPreset, beginSplitResize, resizeByDelta, endResize, movePanel, resetLayout } = useResizableLayout()
  const { getStatusView, handleQuoteStatus } = useTradingMarketDataStatus()
  const favoriteHydratedMarkets = useMemo(() => hydrateMarketFavorites(markets, favoriteSymbols), [favoriteSymbols, markets])
  const selectedMarket = useMemo(
    () => favoriteHydratedMarkets.find((market) => market.symbol === selectedSymbol) ?? createTradingMarketPlaceholder(selectedSymbol),
    [favoriteHydratedMarkets, selectedSymbol]
  )
  const selectedMarketWithRules = useMemo(
    () => mergeMarketRules(selectedMarket, selectedRules),
    [selectedMarket, selectedRules]
  )
  const visibleMarkets = useMemo(() => {
    const nextMarkets = favoriteHydratedMarkets.length > 0 ? favoriteHydratedMarkets : [selectedMarketWithRules]
    return nextMarkets.map((market) => (market.symbol === selectedSymbol ? selectedMarketWithRules : market))
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
  const tradePanelSessionMode = sessionMode as TradingSessionMode
  const terminalLoading = sessionMode === 'loading'
  const isMobileTerminal = useMobileTerminalViewport()
  const sessionStatusLabel = getTradingSessionStatusLabel(sessionMode, sessionAuthStatus, t)
  const sessionStatusText = getTradingSessionStatusText({ sessionMode, sessionAuthStatus, sessionError, loginRequired, t })
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
        setSelectedSymbol((current) =>
          productMarkets.some((market) => market.symbol === current) ? current : routedSymbol
        )
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

  const handleResetWorkspaceLayout = useCallback(() => {
    resetLayout()
    setWorkspaceResetSignal((current) => current + 1)
  }, [resetLayout])

  const workspaceLayoutControls = useMemo(
    () => ({
      layout,
      activePreset,
      applyPreset,
      beginSplitResize,
      resizeByDelta,
      endResize,
      movePanel,
      resetLayout: handleResetWorkspaceLayout,
      resetSignal: workspaceResetSignal
    }),
    [
      activePreset,
      applyPreset,
      beginSplitResize,
      endResize,
      handleResetWorkspaceLayout,
      layout,
      movePanel,
      resizeByDelta,
      workspaceResetSignal
    ]
  )

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
  const viewProps: TradingTerminalViewProps = {
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
    tradePanelSessionMode,
    workspaceLayoutControls
  }

  return (
    <div className={styles.page}>
      <MarketSourceChangeNotice notice={sourceNotice} />
      {!isMobileTerminal ? <TradingDesktopView {...viewProps} /> : null}
      {isMobileTerminal ? <TradingMobileView {...viewProps} /> : null}
      <MobileDrawer open={marketDrawerOpen} side="left" title="Markets" onClose={() => setMarketDrawerOpen(false)}>
        <MarketSidebar markets={visibleMarkets} quotes={quotes} favorites={favoriteSymbols} selectedSymbol={selectedSymbol} onSelect={selectSymbol} onFavorite={toggleFavorite} />
      </MobileDrawer>

      <MobileDrawer open={quoteDrawerOpen} side="right" title="Quote" onClose={() => setQuoteDrawerOpen(false)}>
        <RightTradingPanel
          symbol={selectedSymbol}
          token={token}
          loading={terminalLoading}
          onSelectPrice={handleSelectPrice}
        />
      </MobileDrawer>

      <TradingOrderSheet open={orderSheetOpen} onClose={() => setOrderSheetOpen(false)} view={viewProps} />

      <LoginPromptDialog
        open={loginRequired && loginPromptRequested}
        sessionMode={tradePanelSessionMode}
        sessionError={sessionError}
        onClose={() => setLoginPromptRequested(false)}
        onLogin={handleLoginRedirect}
        onRetry={() => void retrySession()}
      />
    </div>
  )
}
