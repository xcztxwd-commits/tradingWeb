import { Settings } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { TradingWorkspace } from '../../../components/layout/TradingWorkspace'
import { formatMarketPrice } from '../../../features/market/tradingModels'
import { TradePanel } from '../../../features/trading/components/TradePanel'
import { BottomAccountPanel } from './BottomAccountPanel'
import { ChartWorkspace } from './ChartWorkspace'
import { MarketSidebar } from './MarketSidebar'
import { RightTradingPanel } from './RightTradingPanel'
import { SymbolHeader } from './SymbolHeader'
import styles from '../TradingPage.module.css'
import { shouldAllowChartMockFallback, type TradingTerminalViewProps } from '../tradingPageViewModels'

export function TradingDesktopView({
  accountId,
  accountPanel,
  balances,
  chartCallbacks,
  chartSettings,
  chartThemeMode,
  chartTitle,
  indicators,
  loginRequired,
  market,
  markets,
  onLoginRequired,
  onOpenMarkets,
  onOpenQuote,
  onOpenSettings,
  onRetrySession,
  onSelectPrice,
  onSelectSymbol,
  quote,
  quotes,
  sessionError,
  sessionReady,
  submitOrder,
  symbol,
  terminalLoading,
  token,
  tradeMinOrderAmount,
  tradePanelSessionMode,
  tradePricePrecision,
  tradeQuantityPrecision,
  tradePricePrefill,
  workspaceLayoutControls
}: TradingTerminalViewProps) {
  const { t } = useTranslation()

  return (
    <div className={`${styles.layout} ${styles.desktopTerminal}`}>
      <div className={styles.terminalTicker} aria-label={t('markets.overview')}>
        <strong>{market.symbol}</strong>
        <span>{formatMarketPrice(market.symbol, quote.mid)}</span>
        <em>{quote.changePercent.toFixed(2)}%</em>
        <span>{t('markets.high24h')} {formatMarketPrice(market.symbol, quote.high24h)}</span>
        <span>{t('markets.low24h')} {formatMarketPrice(market.symbol, quote.low24h)}</span>
        <span>{t('markets.volume24h')} {quote.volume}</span>
      </div>
      <div className={styles.binanceGrid}>
        <TradingWorkspace
          layoutControls={workspaceLayoutControls}
          watchlist={<MarketSidebar markets={markets} quotes={quotes} selectedSymbol={symbol} onSelect={onSelectSymbol} />}
          header={
            <div className={styles.headerRow}>
              <SymbolHeader market={market} quote={quote} onOpenMarkets={onOpenMarkets} onOpenQuote={onOpenQuote} />
              <button
                type="button"
                className={styles.settingsButton}
                title={t('trading.tradingSettings')}
                aria-label={t('trading.openTradingSettings')}
                onClick={onOpenSettings}
              >
                <Settings size={18} aria-hidden="true" />
              </button>
            </div>
          }
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
          chartTitle={chartTitle}
          market={<RightTradingPanel symbol={symbol} token={token} loading={terminalLoading} onSelectPrice={onSelectPrice} />}
          trade={
            <TradePanel
              accountId={accountId}
              balances={balances}
              minOrderAmount={tradeMinOrderAmount}
              pricePrecision={tradePricePrecision}
              quantityPrecision={tradeQuantityPrecision}
              pricePrefill={tradePricePrefill}
              sessionReady={sessionReady}
              sessionMode={tradePanelSessionMode}
              sessionError={sessionError}
              loginRequired={loginRequired}
              symbol={symbol}
              onLoginRequired={onLoginRequired}
              onSubmitOrder={submitOrder}
              onRetrySession={onRetrySession}
            />
          }
          bottom={
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
      </div>
    </div>
  )
}
