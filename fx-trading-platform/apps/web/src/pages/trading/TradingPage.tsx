import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { formatTradingChartTitle, initialTradingSymbol, mergeWithMockMarkets, normalizeTradingSymbol } from './tradingPageMarketSelection'
import { getTradingSessionStatusLabel, getTradingSessionStatusText } from './tradingPageSessionStatus'
import { getTradeMinOrderAmount, getTradePricePrecision, getTradeQuantityPrecision, mergeMarketRules } from './tradingPageTradeRules'
import type { TradingTerminalViewProps } from './tradingPageViewModels'
import { useTradingMarketDataStatus } from './useTradingMarketDataStatus'
import { useMobileTerminalViewport } from './useMobileTerminalViewport'
import { useTradingChartSettings } from './useTradingChartSettings'
import { useResizableLayout } from '../../hooks/useResizableLayout'
import { MarketSidebar } from './components/MarketSidebar'
import { MobileDrawer, MobileOrderSheet } from './components/MobilePanels'
import { LoginPromptDialog } from './components/LoginPromptDialog'
import { RightTradingPanel } from './components/RightTradingPanel'
import { TradingDesktopView } from './components/TradingDesktopView'
import { TradingMobileView } from './components/TradingMobileView'
import { mockTradingMarkets } from '../../features/market/mockTradingData'
import { hydrateMarketFavorites } from '../../features/market/marketFavorites'
import { createTradingMarketPlaceholder, createTradingQuoteFromMarket } from '../../features/market/tradingMarketAdapters'
import { fetchMarketSymbolRules, fetchMarketSymbols } from '../../features/market/tradingMarketApi'
import { getRealtimeQuoteMarkets } from '../../features/market/tradingModels'
import type { TradingInstrumentRules, TradingMarket } from '../../features/market/tradingModels'
import { useMarketFavorites } from '../../features/market/useMarketFavorites'
import { useTradingQuoteMap } from './useTradingQuotes'
import { TradePanel } from '../../features/trading/components/TradePanel'
import { deriveTradingBalances } from '../../features/trading-session/tradingSession'
import { useTradingSession } from '../../features/trading-session/useTradingSession'
import type { TradingSessionMode } from '../../features/trading-session/useTradingSession'
import { useTheme } from '../../design-system/theme/ThemeProvider'
import { normalizeTradingCategory, writeLastTradingSymbol } from '../../app/hooks/useLastTradingSymbol'
import styles from './TradingPage.module.css'

export function TradingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const querySymbol = searchParams.get('symbol')
  const queryCategory = normalizeTradingCategory(searchParams.get('category'))
  const { currentTheme } = useTheme()
  const [selectedSymbol, setSelectedSymbol] = useState(normalizeTradingSymbol(querySymbol) ?? initialTradingSymbol)
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
    positions,
    positionHistory,
    ledgerEntries,
    walletBalances,
    sessionReady,
    sessionMode,
    sessionError,
    sessionAuthStatus,
    loginRequired,
    retrySession,
    submitOrder,
    closePosition
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
  const quotes = useTradingQuoteMap(quoteMarkets, token, handleQuoteStatus)
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
    () => deriveTradingBalances(account, positions, selectedSymbol, walletBalances),
    [account, positions, selectedSymbol, walletBalances]
  )
  const tradeMinOrderAmount = getTradeMinOrderAmount(selectedMarketWithRules)
  const tradePricePrecision = getTradePricePrecision(selectedMarketWithRules)
  const tradeQuantityPrecision = getTradeQuantityPrecision(selectedMarketWithRules, tradeMinOrderAmount)
  useEffect(() => {
    setTradePricePrefill(null)
  }, [selectedSymbol])

  useEffect(() => {
    const normalizedQuerySymbol = normalizeTradingSymbol(querySymbol)
    writeLastTradingSymbol(queryCategory, normalizedQuerySymbol ?? selectedSymbol)
    if (normalizedQuerySymbol) setSelectedSymbol(normalizedQuerySymbol)
  }, [queryCategory, querySymbol, selectedSymbol])

  useEffect(() => {
    let active = true

    void fetchMarketSymbols()
      .then((nextMarkets) => {
        if (!active) return
        const mergedMarkets = mergeWithMockMarkets(nextMarkets)
        const normalizedQuerySymbol = normalizeTradingSymbol(querySymbol)
        setMarkets(mergedMarkets)
        setSelectedSymbol((current) =>
          mergedMarkets.some((market) => market.symbol === current) ? current : normalizedQuerySymbol ?? initialTradingSymbol
        )
      })
      .catch(() => {
        if (active) setMarkets(mockTradingMarkets)
      })

    return () => {
      active = false
    }
  }, [querySymbol])

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
    setSelectedSymbol(symbol)
    setMarketDrawerOpen(false)
  }, [])

  const handleLoginRedirect = useCallback(() => {
    navigate(`/login?redirect=${encodeURIComponent('/trading')}`)
  }, [navigate])

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
    positions,
    positionHistory,
    sessionReady,
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
    quote: selectedQuote,
    quotes,
    sessionError,
    sessionReady,
    sessionStatusLabel,
    sessionStatusText,
    submitOrder,
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

      <MobileOrderSheet open={orderSheetOpen} title="Trade" onClose={() => setOrderSheetOpen(false)}>
        <TradePanel
          compact
          accountId={accountId}
          balances={balances}
          category={selectedMarketWithRules.category}
          productType={selectedMarketWithRules.productType}
          sessionReady={sessionReady}
          sessionMode={tradePanelSessionMode}
          sessionError={sessionError}
          loginRequired={loginRequired}
          leverage={selectedMarketWithRules.leverage}
          rules={selectedMarketWithRules.rules}
          minOrderAmount={tradeMinOrderAmount}
          pricePrecision={tradePricePrecision}
          quantityPrecision={tradeQuantityPrecision}
          pricePrefill={tradePricePrefill}
          symbol={selectedSymbol}
          onLoginRequired={handleTradeLoginRequired}
          onSubmitOrder={submitOrder}
          onRetrySession={retrySession}
        />
      </MobileOrderSheet>

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
