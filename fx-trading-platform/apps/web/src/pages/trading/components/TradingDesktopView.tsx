import { useTranslation } from 'react-i18next'

import { TradingWorkspace } from '../../../components/layout/TradingWorkspace'
import { formatMarketPrice } from '@fx-platform/frontend-core'
import { TradePanel } from '../../../features/trading/components/TradePanel'
import { BottomAccountPanel } from './BottomAccountPanel'
import { ChartWorkspace } from './ChartWorkspace'
import { MarketSidebar } from './MarketSidebar'
import { RightTradingPanel } from './RightTradingPanel'
import { SymbolHeader } from './SymbolHeader'
import { PerpetualTradingControls } from './PerpetualTradingControls'
import styles from '../TradingPage.module.css'
import type { TradingTerminalViewProps } from '../../../routes/trading/tradingRoute.types'

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
  favorites,
  marketDataStatusView,
  markets,
  onLoginRequired,
  onOpenMarkets,
  onOpenQuote,
  onRetrySession,
  onSelectPrice,
  onSelectSymbol,
  onFavorite,
  product,
  quote,
  quotes,
  sessionError,
  sessionReady,
  submitOrder,
  submitOco,
  perpetualControls,
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
        <span className={styles.marketDataStatus} data-tone={marketDataStatusView.tone}>
          <b>{marketDataStatusView.label}</b>
          <small>{marketDataStatusView.detail}</small>
        </span>
      </div>
      <div className={styles.binanceGrid}>
        <TradingWorkspace
          layoutControls={workspaceLayoutControls}
          watchlist={<MarketSidebar markets={markets} quotes={quotes} favorites={favorites} selectedSymbol={symbol} onSelect={onSelectSymbol} onFavorite={onFavorite} />}
          header={
            <div className={styles.headerRow}>
              <SymbolHeader market={market} marginMode={perpetualControls.marginMode} product={product} quote={quote} onOpenMarkets={onOpenMarkets} onOpenQuote={onOpenQuote} />
            </div>
          }
          chart={
            <ChartWorkspace
              indicators={indicators}
              settings={chartSettings}
              symbol={symbol}
              themeMode={chartThemeMode}
              token={token}
              orders={accountPanel.orders}
              positions={accountPanel.positions}
              onChartSettingsChange={chartCallbacks.onChartSettingsChange}
              onResetChartSettings={chartCallbacks.onResetChartSettings}
              onChartTypeChange={chartCallbacks.onChartTypeChange}
              onHighLowPriceMarksChange={chartCallbacks.onHighLowPriceMarksChange}
              onPriceScaleModeChange={chartCallbacks.onPriceScaleModeChange}
              onDrawingMagnetModeChange={chartCallbacks.onDrawingMagnetModeChange}
              onDrawingToolChange={chartCallbacks.onDrawingToolChange}
              onFavoriteIntervalToggle={chartCallbacks.onFavoriteIntervalToggle}
              onIndicatorSettingsChange={chartCallbacks.onIndicatorSettingsChange}
              onIndicatorToggle={chartCallbacks.onIndicatorToggle}
              onPeriodChange={chartCallbacks.onPeriodChange}
              onSelectPrice={onSelectPrice}
            />
          }
          chartTitle={chartTitle}
          market={<RightTradingPanel symbol={symbol} token={token} loading={terminalLoading} onSelectPrice={onSelectPrice} />}
          trade={
            <div className={styles.tradeControlsStack}>
              <PerpetualTradingControls controls={perpetualControls} />
              <TradePanel
                accountId={accountId}
                adapterSettings={perpetualControls.adapterSettings}
                settingsReady={perpetualControls.ready}
                balances={balances}
                category={market.category}
                productType={market.productType}
                minOrderAmount={tradeMinOrderAmount}
                pricePrecision={tradePricePrecision}
                quantityPrecision={tradeQuantityPrecision}
                pricePrefill={tradePricePrefill}
                sessionReady={sessionReady}
                sessionMode={tradePanelSessionMode}
                sessionError={sessionError}
                loginRequired={loginRequired}
                leverage={market.leverage}
                rules={market.rules}
                symbol={symbol}
                onLoginRequired={onLoginRequired}
                onSubmitOrder={submitOrder}
                onSubmitOco={submitOco}
                onRetrySession={onRetrySession}
              />
            </div>
          }
          bottom={
            <BottomAccountPanel
              account={accountPanel.account}
              ledgerEntries={accountPanel.ledgerEntries}
              loading={accountPanel.loading}
              orders={accountPanel.orders}
              trades={accountPanel.trades}
              positions={accountPanel.positions}
              positionHistory={accountPanel.positionHistory}
              fundingSettlements={accountPanel.fundingSettlements}
              transfers={accountPanel.transfers}
              sessionReady={accountPanel.sessionReady}
              currentSymbol={symbol}
              onCancelAllOrders={accountPanel.onCancelAllOrders}
              onCloseAllPositions={accountPanel.onCloseAllPositions}
              onClosePosition={accountPanel.onClosePosition}
            />
          }
        />
      </div>
    </div>
  )
}
