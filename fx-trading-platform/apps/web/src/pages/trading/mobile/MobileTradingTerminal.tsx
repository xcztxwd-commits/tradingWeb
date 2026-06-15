import { ChevronDown, List, PanelRight, ShoppingBag } from 'lucide-react'
import type { CSSProperties, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import { formatMarketPrice } from '../../../features/market/tradingModels'
import type { TradingMarket, TradingQuote } from '../../../features/market/tradingModels'
import type { TradingMarketDataStatusView } from '../tradingPageMarketDataStatus'
import styles from './MobileTradingTerminal.module.css'

export type MobileTradingTerminalProps = {
  market: TradingMarket
  marketDataStatusView: TradingMarketDataStatusView
  quote: TradingQuote
  sessionStatusLabel: string
  sessionStatusText: string
  chart: ReactNode
  accountPanel: ReactNode
  onOpenMarkets: () => void
  onOpenQuote: () => void
  onOpenTrade: () => void
}

export function MobileTradingTerminal({
  market,
  marketDataStatusView,
  quote,
  sessionStatusLabel,
  sessionStatusText,
  chart,
  accountPanel,
  onOpenMarkets,
  onOpenQuote,
  onOpenTrade
}: MobileTradingTerminalProps) {
  const { t } = useTranslation()
  const positive = quote.changePercent >= 0
  const directionClassName = positive ? styles.positive : styles.negative
  const spread = Math.max(quote.ask - quote.bid, 0)
  const bidShare = positive ? 60 : 42
  const askShare = 100 - bidShare
  const bidAskStyle = { '--bid-percent': `${bidShare}%` } as CSSProperties

  return (
    <section className={styles.terminal} aria-label={t('trading.mobileTerminal')}>
      <nav className={styles.marketTabs} aria-label={t('markets.title')}>
        {['闪兑', '现货', '合约', 'DEX', '策略&跟单'].map((label) => (
          <button key={label} type="button" className={label === '合约' ? styles.activeMarketTab : undefined}>
            {label}
          </button>
        ))}
      </nav>

      <header className={styles.contractHeader}>
        <button type="button" className={styles.symbolButton} onClick={onOpenMarkets}>
          <span>
            <strong>{market.symbol}</strong>
            <small>永续</small>
          </span>
          <ChevronDown size={16} aria-hidden="true" />
        </button>
        <div className={styles.priceBlock}>
          <strong className={directionClassName}>{formatMarketPrice(market.symbol, quote.mid)}</strong>
          <span className={directionClassName}>{positive ? '+' : ''}{quote.changePercent.toFixed(2)}%</span>
        </div>
      </header>

      <dl className={styles.metricStrip} aria-label={t('trading.marketSummary24h')}>
        <div>
          <dt>{t('markets.high24h')}</dt>
          <dd>{formatMarketPrice(market.symbol, quote.high24h)}</dd>
        </div>
        <div>
          <dt>{t('markets.low24h')}</dt>
          <dd>{formatMarketPrice(market.symbol, quote.low24h)}</dd>
        </div>
        <div>
          <dt>{t('markets.volume24h')}</dt>
          <dd>{quote.volume}</dd>
        </div>
      </dl>

      <div className={styles.sessionStrip} aria-live="polite">
        <strong>{sessionStatusLabel}</strong>
        <span>{sessionStatusText}</span>
      </div>

      <div className={styles.marketDataStrip} data-tone={marketDataStatusView.tone} aria-live="polite">
        <strong>{marketDataStatusView.label}</strong>
        <span>{marketDataStatusView.detail}</span>
      </div>

      <section className={styles.orderSurface} aria-label={t('trading.trade')}>
        <div className={styles.orderControls}>
          <button type="button" className={styles.orderControlActive}>开仓</button>
          <button type="button">平仓</button>
          <button type="button">全仓</button>
          <button type="button">100x</button>
        </div>
        <button type="button" className={styles.orderTypeButton} onClick={onOpenTrade}>
          市价委托
          <ChevronDown size={15} aria-hidden="true" />
        </button>
        <div className={styles.quantityField}>
          <span>数量</span>
          <strong>张</strong>
        </div>
        <div className={styles.positionHints}>
          <span>可用</span>
          <strong>7.34 {market.quote}</strong>
        </div>
        <div className={styles.stopRow}>
          <span>止盈/止损</span>
          <button type="button" onClick={onOpenTrade}>高级</button>
        </div>
        <div className={styles.triggerGrid}>
          <span>止盈触发价</span>
          <b>{market.quote}</b>
          <span>止损触发价</span>
          <b>{market.quote}</b>
        </div>
        <button type="button" className={styles.longButton} onClick={onOpenTrade}>
          开多 100x
        </button>
        <button type="button" className={styles.shortButton} onClick={onOpenTrade}>
          开空 100x
        </button>
      </section>

      <div className={styles.bidAskRatio} style={bidAskStyle} aria-label={t('trading.depthSummary')}>
        <span>B {bidShare}%</span>
        <strong>{formatMarketPrice(market.symbol, quote.mid)}</strong>
        <span>{askShare}% S</span>
      </div>

      <section className={styles.chartSection} aria-label={t('trading.chartTitle', { symbol: market.symbol })}>
        {chart}
      </section>

      <section className={styles.depthPreview} aria-label={t('trading.depthSummary')}>
        <div className={styles.depthHead}>
          <strong>{t('trading.depth')}</strong>
          <button type="button" onClick={onOpenQuote}>{t('trading.fullOrderBook')}</button>
        </div>
        <div className={styles.depthRows}>
          <span className={styles.ask}>{t('trading.ask')}</span>
          <strong>{formatMarketPrice(market.symbol, quote.ask)}</strong>
          <span>{formatMarketPrice(market.symbol, spread)}</span>
          <span className={styles.bid}>{t('trading.bid')}</span>
          <strong>{formatMarketPrice(market.symbol, quote.bid)}</strong>
          <span>{market.quote}</span>
        </div>
      </section>

      <section className={styles.accountSection} aria-label={t('trading.ordersAndPositions')}>
        {accountPanel}
      </section>

      <nav className={styles.actionBar} aria-label={t('trading.mobileQuickActions')}>
        <button type="button" onClick={onOpenMarkets}>
          <List size={17} aria-hidden="true" />
          <span>{t('markets.title')}</span>
        </button>
        <button type="button" className={styles.tradeAction} onClick={onOpenTrade}>
          <ShoppingBag size={17} aria-hidden="true" />
          <span>{t('trading.trade')}</span>
        </button>
        <button type="button" onClick={onOpenQuote}>
          <PanelRight size={17} aria-hidden="true" />
          <span>{t('trading.depth')}</span>
        </button>
      </nav>
    </section>
  )
}
