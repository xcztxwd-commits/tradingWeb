import { Suspense, lazy } from 'react'
import { useTranslation } from 'react-i18next'

import { BottomAccountPanel } from './BottomAccountPanel'
import { ChartWorkspace } from './ChartWorkspace'
import styles from '../TradingPage.module.css'
import { shouldAllowChartMockFallback, type TradingTerminalViewProps } from '../tradingPageViewModels'

const MobileTradingTerminal = lazy(() =>
  import('../mobile/MobileTradingTerminal').then((module) => ({ default: module.MobileTradingTerminal }))
)

export function TradingMobileView({
  accountPanel,
  chartCallbacks,
  chartSettings,
  chartThemeMode,
  indicators,
  market,
  marketDataStatusView,
  onOpenMarkets,
  onOpenQuote,
  onOpenTrade,
  onSelectPrice,
  quote,
  sessionStatusLabel,
  sessionStatusText,
  symbol,
  token
}: TradingTerminalViewProps) {
  return (
    <div className={styles.mobileTerminal}>
      <Suspense fallback={<MobileTerminalFallback />}>
        <MobileTradingTerminal
          market={market}
          marketDataStatusView={marketDataStatusView}
          quote={quote}
          sessionStatusLabel={sessionStatusLabel}
          sessionStatusText={sessionStatusText}
          onOpenMarkets={onOpenMarkets}
          onOpenQuote={onOpenQuote}
          onOpenTrade={onOpenTrade}
          chart={
            <ChartWorkspace
              indicators={indicators}
              settings={chartSettings}
              symbol={symbol}
              themeMode={chartThemeMode}
              token={token}
              orders={accountPanel.orders}
              positions={accountPanel.positions}
              allowMockFallback={shouldAllowChartMockFallback(market)}
              onChartSettingsChange={chartCallbacks.onChartSettingsChange}
              onResetChartSettings={chartCallbacks.onResetChartSettings}
              onChartTypeChange={chartCallbacks.onChartTypeChange}
              onHighLowPriceMarksChange={chartCallbacks.onHighLowPriceMarksChange}
              onPriceScaleModeChange={chartCallbacks.onPriceScaleModeChange}
              onTooltipStyleChange={chartCallbacks.onTooltipStyleChange}
              onDrawingMagnetModeChange={chartCallbacks.onDrawingMagnetModeChange}
              onDrawingToolChange={chartCallbacks.onDrawingToolChange}
              onFavoriteIntervalToggle={chartCallbacks.onFavoriteIntervalToggle}
              onIndicatorSettingsChange={chartCallbacks.onIndicatorSettingsChange}
              onIndicatorToggle={chartCallbacks.onIndicatorToggle}
              onPeriodChange={chartCallbacks.onPeriodChange}
              onSelectPrice={onSelectPrice}
            />
          }
          accountPanel={
            <BottomAccountPanel
              account={accountPanel.account}
              ledgerEntries={accountPanel.ledgerEntries}
              loading={accountPanel.loading}
              orders={accountPanel.orders}
              positions={accountPanel.positions}
              positionHistory={accountPanel.positionHistory}
              sessionReady={accountPanel.sessionReady}
              currentSymbol={symbol}
              onClosePosition={accountPanel.onClosePosition}
            />
          }
        />
      </Suspense>
    </div>
  )
}

function MobileTerminalFallback() {
  const { t } = useTranslation()

  return (
    <div className={styles.mobileTerminalFallback} role="status">
      {t('loading.mobileTerminal')}
    </div>
  )
}
