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
  onOpenMarkets,
  onOpenQuote,
  onOpenSettings,
  onOpenTrade,
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
          quote={quote}
          sessionStatusLabel={sessionStatusLabel}
          sessionStatusText={sessionStatusText}
          onOpenMarkets={onOpenMarkets}
          onOpenQuote={onOpenQuote}
          onOpenTrade={onOpenTrade}
          onOpenSettings={onOpenSettings}
          chart={
            <ChartWorkspace
              indicators={indicators}
              settings={chartSettings}
              symbol={symbol}
              themeMode={chartThemeMode}
              token={token}
              allowMockFallback={shouldAllowChartMockFallback(market)}
              onChartTypeChange={chartCallbacks.onChartTypeChange}
              onDrawingMagnetModeChange={chartCallbacks.onDrawingMagnetModeChange}
              onDrawingToolChange={chartCallbacks.onDrawingToolChange}
              onIndicatorSettingsChange={chartCallbacks.onIndicatorSettingsChange}
              onIndicatorToggle={chartCallbacks.onIndicatorToggle}
              onPeriodChange={chartCallbacks.onPeriodChange}
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
