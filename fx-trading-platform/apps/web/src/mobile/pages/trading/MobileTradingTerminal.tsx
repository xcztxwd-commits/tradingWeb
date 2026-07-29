import type { TradingRouteModel } from '../../../routes/trading/tradingRoute.types'
import { BottomAccountPanel } from '../../../shared-widgets/trading/components/BottomAccountPanel'
import { ChartWorkspace } from '../../../shared-widgets/trading/components/ChartWorkspace'
import { MarketSidebar } from '../../../shared-widgets/trading/components/MarketSidebar'
import { RightTradingPanel } from '../../../shared-widgets/trading/components/RightTradingPanel'
import { MobileDrawer } from './MobilePanels'
import { MobileTradingTerminal as MobileTerminalShell } from './shell/MobileTradingTerminal'
import { TradingOrderSheet } from './TradingOrderSheet'
import styles from './MobileTradingTerminal.module.css'

export function MobileTradingTerminal({ model }: { model: TradingRouteModel }) {
  return (
    <div className={styles.terminal} data-platform-view="mobile">
      <MobileTerminalShell
        market={model.market}
        marketDataStatusView={model.marketDataStatusView}
        quote={model.quote}
        sessionStatusLabel={model.sessionStatusLabel}
        sessionStatusText={model.sessionStatusText}
        onOpenMarkets={model.onOpenMarkets}
        onOpenQuote={model.onOpenQuote}
        onOpenTrade={model.onOpenTrade}
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
        accountPanel={
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
      <MobileDrawer open={model.marketDrawerOpen} side="left" title="Markets" onClose={model.closeMarketDrawer}>
        <MarketSidebar
          markets={model.markets}
          quotes={model.quotes}
          favorites={model.favorites}
          selectedSymbol={model.symbol}
          onSelect={model.onSelectSymbol}
          onFavorite={model.onFavorite}
        />
      </MobileDrawer>
      <MobileDrawer open={model.quoteDrawerOpen} side="right" title="Quote" onClose={model.closeQuoteDrawer}>
        <RightTradingPanel
          symbol={model.symbol}
          loading={model.terminalLoading}
          onSelectPrice={model.onSelectPrice}
        />
      </MobileDrawer>
      <TradingOrderSheet
        open={model.orderSheetOpen}
        onClose={model.closeOrderSheet}
        tradePanel={model.tradePanel}
        perpetualControls={model.perpetualControls}
      />
    </div>
  )
}
