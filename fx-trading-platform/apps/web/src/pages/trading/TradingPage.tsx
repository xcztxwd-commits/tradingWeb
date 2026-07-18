import { useCallback, useMemo, useState } from 'react'

import { useResizableLayout } from '../../hooks/useResizableLayout'
import type { TradingProduct } from '../../app/tradingRoutes'
import type { TradingTerminalViewProps } from '../../routes/trading/tradingRoute.types'
import { useTradingRouteController } from '../../routes/trading/useTradingRouteController'
import { LoginPromptDialog } from './components/LoginPromptDialog'
import { MarketSidebar } from './components/MarketSidebar'
import { MarketSourceChangeNotice } from './components/MarketSourceChangeNotice'
import { MobileDrawer } from './components/MobilePanels'
import { RightTradingPanel } from './components/RightTradingPanel'
import { TradingDesktopView } from './components/TradingDesktopView'
import { TradingMobileView } from './components/TradingMobileView'
import { TradingOrderSheet } from './components/TradingOrderSheet'
import { useMobileTerminalViewport } from './useMobileTerminalViewport'
import styles from './TradingPage.module.css'

type TradingPageProps = { product: TradingProduct }

export function TradingPage({ product }: TradingPageProps) {
  const {
    model,
    sourceNotice,
    marketDrawerOpen,
    quoteDrawerOpen,
    orderSheetOpen,
    loginPromptOpen,
    closeMarketDrawer,
    closeQuoteDrawer,
    closeOrderSheet,
    closeLoginPrompt,
    login
  } = useTradingRouteController({ product })
  const isMobileTerminal = useMobileTerminalViewport()
  const [workspaceResetSignal, setWorkspaceResetSignal] = useState(0)
  const {
    layout,
    activePreset,
    applyPreset,
    beginSplitResize,
    resizeByDelta,
    endResize,
    movePanel,
    resetLayout
  } = useResizableLayout()
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
  const viewProps: TradingTerminalViewProps = {
    ...model,
    workspaceLayoutControls
  }

  return (
    <div className={styles.page}>
      <MarketSourceChangeNotice notice={sourceNotice} />
      {!isMobileTerminal ? <TradingDesktopView {...viewProps} /> : null}
      {isMobileTerminal ? <TradingMobileView {...viewProps} /> : null}
      <MobileDrawer open={marketDrawerOpen} side="left" title="Markets" onClose={closeMarketDrawer}>
        <MarketSidebar
          markets={model.markets}
          quotes={model.quotes}
          favorites={model.favorites}
          selectedSymbol={model.symbol}
          onSelect={model.onSelectSymbol}
          onFavorite={model.onFavorite}
        />
      </MobileDrawer>

      <MobileDrawer open={quoteDrawerOpen} side="right" title="Quote" onClose={closeQuoteDrawer}>
        <RightTradingPanel
          symbol={model.symbol}
          token={model.token}
          loading={model.terminalLoading}
          onSelectPrice={model.onSelectPrice}
        />
      </MobileDrawer>

      <TradingOrderSheet open={orderSheetOpen} onClose={closeOrderSheet} view={viewProps} />

      <LoginPromptDialog
        open={loginPromptOpen}
        sessionMode={model.tradePanelSessionMode}
        sessionError={model.sessionError}
        onClose={closeLoginPrompt}
        onLogin={login}
        onRetry={() => void model.onRetrySession()}
      />
    </div>
  )
}
