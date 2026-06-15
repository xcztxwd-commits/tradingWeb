import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { OrderBookSkeleton } from '../loading/TerminalSkeleton'
import { useMarketDataSnapshot } from '../../features/market/marketDataStore'
import { startQuoteMarketDataAdapter } from '../../features/market/quoteMarketDataAdapter'
import { OrderBook } from './OrderBook'
import { OrderBookSettingsPopover } from './OrderBookSettingsPopover'
import { OrderBookToolbar } from './OrderBookToolbar'
import { RecentTrades } from './RecentTrades'
import type { AggregationStep, MarketSidePanelTab, OrderBookDisplayMode, OrderBookSettings } from './types'
import { loadOrderBookSettings, saveOrderBookSettings } from './utils'
import styles from './MarketSidePanel.module.css'

type Props = {
  symbol: string
  token?: string | null
  loading?: boolean
  onSelectPrice?: (price: number) => void
}

export function MarketSidePanel({ symbol, token = null, loading = false, onSelectPrice }: Props) {
  const { t } = useTranslation()
  const snapshot = useMarketDataSnapshot()
  const [activeTab, setActiveTab] = useState<MarketSidePanelTab>('orderbook')
  const [displayMode, setDisplayMode] = useState<OrderBookDisplayMode>('both')
  const [aggregationStep, setAggregationStep] = useState<AggregationStep>(0.1)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settings, setSettings] = useState<OrderBookSettings>(() => loadOrderBookSettings())
  const isInitialMarketSnapshot =
    snapshot.lastPrice <= 0 &&
    snapshot.asks.length === 0 &&
    snapshot.bids.length === 0 &&
    snapshot.recentTrades.length === 0

  useEffect(() => startQuoteMarketDataAdapter(symbol, token), [symbol, token])

  useEffect(() => {
    saveOrderBookSettings(settings)
  }, [settings])

  const updateSettings = useCallback((nextSettings: OrderBookSettings) => {
    setSettings(nextSettings)
    if (nextSettings.layoutMode === 'orderbook') setActiveTab('orderbook')
    if (nextSettings.layoutMode === 'trades') setActiveTab('trades')
  }, [])

  return (
    <section className={styles.panel} aria-label={`${symbol} market side panel`}>
      <header className={styles.header}>
        <div className={styles.tabs} role="tablist" aria-label="Market side panel tabs">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'orderbook'}
            className={activeTab === 'orderbook' ? styles.activeTab : undefined}
            onClick={() => setActiveTab('orderbook')}
          >
            {t('trading.orderBook')}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'trades'}
            className={activeTab === 'trades' ? styles.activeTab : undefined}
            onClick={() => setActiveTab('trades')}
          >
            {t('trading.recentTrades')}
          </button>
        </div>
        <OrderBookToolbar
          aggregationStep={aggregationStep}
          displayMode={displayMode}
          settingsOpen={settingsOpen}
          onAggregationStepChange={setAggregationStep}
          onDisplayModeChange={setDisplayMode}
          onSettingsToggle={() => setSettingsOpen((open) => !open)}
        />
        {settingsOpen ? (
          <OrderBookSettingsPopover
            settings={settings}
            onChange={updateSettings}
            onClose={() => setSettingsOpen(false)}
          />
        ) : null}
      </header>

      <div className={styles.content}>
        {activeTab === 'orderbook' && (loading || isInitialMarketSnapshot) ? (
          <OrderBookSkeleton />
        ) : activeTab === 'orderbook' ? (
          <OrderBook
            aggregationStep={aggregationStep}
            displayMode={displayMode}
            settings={settings}
            snapshot={snapshot}
            symbol={symbol}
            onSelectPrice={onSelectPrice}
          />
        ) : (
          <RecentTrades aggregationStep={aggregationStep} snapshot={snapshot} symbol={symbol} onSelectPrice={onSelectPrice} />
        )}
      </div>

      <span className={styles.symbolBadge}>{symbol}</span>
    </section>
  )
}
