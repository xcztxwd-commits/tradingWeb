import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { formatMarketPrice } from '@fx-platform/frontend-core'
import type { TradingRouteModel } from '../../../routes/trading/tradingRoute.types'
import { BottomAccountPanel } from '../../../shared-widgets/trading/components/BottomAccountPanel'
import { ChartWorkspace } from '../../../shared-widgets/trading/components/ChartWorkspace'
import { MarketSidebar } from '../../../shared-widgets/trading/components/MarketSidebar'
import { PerpetualTradingControls } from '../../../shared-widgets/trading/components/PerpetualTradingControls'
import { RightTradingPanel } from '../../../shared-widgets/trading/components/RightTradingPanel'
import { SymbolHeader } from '../../../shared-widgets/trading/components/SymbolHeader'
import { TradePanel } from '../../../shared-widgets/trading/order-form/TradePanel'
import { TradingWorkspace } from './layout/TradingWorkspace'
import { useResizableLayout } from './layout/useResizableLayout'
import styles from './PcTradingTerminal.module.css'

export function PcTradingTerminal({ model }: { model: TradingRouteModel }) {
  const { t } = useTranslation()
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

  return (
    <div className={`${styles.layout} ${styles.terminal}`} data-platform-view="pc">
      <div className={styles.ticker} aria-label={t('markets.overview')}>
        <strong>{model.market.symbol}</strong>
        <span>{formatMarketPrice(model.market.symbol, model.quote.mid)}</span>
        <em>{model.quote.changePercent.toFixed(2)}%</em>
        <span>{t('markets.high24h')} {formatMarketPrice(model.market.symbol, model.quote.high24h)}</span>
        <span>{t('markets.low24h')} {formatMarketPrice(model.market.symbol, model.quote.low24h)}</span>
        <span>{t('markets.volume24h')} {model.quote.volume}</span>
        <span className={styles.marketDataStatus} data-tone={model.marketDataStatusView.tone}>
          <b>{model.marketDataStatusView.label}</b>
          <small>{model.marketDataStatusView.detail}</small>
        </span>
      </div>
      <div className={styles.grid}>
        <TradingWorkspace
          layoutControls={workspaceLayoutControls}
          watchlist={
            <MarketSidebar
              markets={model.markets}
              quotes={model.quotes}
              favorites={model.favorites}
              selectedSymbol={model.symbol}
              onSelect={model.onSelectSymbol}
              onFavorite={model.onFavorite}
            />
          }
          header={
            <div className={styles.headerRow}>
              <SymbolHeader
                market={model.market}
                marginMode={model.perpetualControls.marginMode}
                product={model.product}
                quote={model.quote}
                onOpenMarkets={model.onOpenMarkets}
                onOpenQuote={model.onOpenQuote}
              />
            </div>
          }
          chart={
            <ChartWorkspace
              indicators={model.indicators}
              settings={model.chartSettings}
              symbol={model.symbol}
              themeMode={model.chartThemeMode}
              token={model.token}
              orders={model.accountPanel.orders}
              positions={model.accountPanel.positions}
              onChartSettingsChange={model.chartCallbacks.onChartSettingsChange}
              onResetChartSettings={model.chartCallbacks.onResetChartSettings}
              onChartTypeChange={model.chartCallbacks.onChartTypeChange}
              onHighLowPriceMarksChange={model.chartCallbacks.onHighLowPriceMarksChange}
              onPriceScaleModeChange={model.chartCallbacks.onPriceScaleModeChange}
              onDrawingMagnetModeChange={model.chartCallbacks.onDrawingMagnetModeChange}
              onDrawingToolChange={model.chartCallbacks.onDrawingToolChange}
              onFavoriteIntervalToggle={model.chartCallbacks.onFavoriteIntervalToggle}
              onIndicatorSettingsChange={model.chartCallbacks.onIndicatorSettingsChange}
              onIndicatorToggle={model.chartCallbacks.onIndicatorToggle}
              onPeriodChange={model.chartCallbacks.onPeriodChange}
              onSelectPrice={model.onSelectPrice}
            />
          }
          market={
            <RightTradingPanel
              symbol={model.symbol}
              token={model.token}
              loading={model.terminalLoading}
              onSelectPrice={model.onSelectPrice}
            />
          }
          trade={
            <div className={styles.tradeControlsStack}>
              <PerpetualTradingControls controls={model.perpetualControls} />
              <TradePanel model={model.tradePanel} />
            </div>
          }
          bottom={
            <BottomAccountPanel
              account={model.accountPanel.account}
              ledgerEntries={model.accountPanel.ledgerEntries}
              loading={model.accountPanel.loading}
              orders={model.accountPanel.orders}
              trades={model.accountPanel.trades}
              positions={model.accountPanel.positions}
              positionHistory={model.accountPanel.positionHistory}
              fundingSettlements={model.accountPanel.fundingSettlements}
              transfers={model.accountPanel.transfers}
              sessionReady={model.accountPanel.sessionReady}
              currentSymbol={model.symbol}
              onCancelAllOrders={model.accountPanel.onCancelAllOrders}
              onCloseAllPositions={model.accountPanel.onCloseAllPositions}
              onClosePosition={model.accountPanel.onClosePosition}
            />
          }
        />
      </div>
    </div>
  )
}
