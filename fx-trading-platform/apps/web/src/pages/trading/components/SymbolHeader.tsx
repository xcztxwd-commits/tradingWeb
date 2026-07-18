import { ChevronDown, Radio } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { TradingProduct } from '../../../app/tradingRoutes'
import { formatMarketPrice, type TradingMarket, type TradingQuote } from '@fx-platform/frontend-core'
import styles from './SymbolHeader.module.css'

type Props = {
  market: TradingMarket
  marginMode: 'CROSS' | 'ISOLATED'
  product: TradingProduct
  quote: TradingQuote
  onOpenMarkets: () => void
  onOpenQuote: () => void
}

export function SymbolHeader({ market, marginMode, product, quote, onOpenMarkets, onOpenQuote }: Props) {
  const { t } = useTranslation()
  const directionClass = quote.changePercent >= 0 ? styles.positive : styles.negative
  const perpetual = product === 'perpetual'
  const productModeKey = perpetual ? 'trading.perpetual' : 'trading.spot'
  const marginModeKey = perpetual
    ? marginMode === 'ISOLATED' ? 'trading.isolatedMargin' : 'trading.crossMargin'
    : 'trading.cash'

  return (
    <header className={styles.header}>
      <button type="button" className={styles.symbolButton} onClick={onOpenMarkets}>
        <span>
          <strong>{market.symbol}</strong>
          <small>{market.name}</small>
        </span>
        <ChevronDown size={16} />
      </button>

      <div className={styles.modeStrip} aria-label={t('trading.tradingMode')}>
        <span>{t(productModeKey)}</span>
        <span>{t(marginModeKey)}</span>
      </div>

      <dl className={styles.metrics}>
        <div>
          <dt>{t('markets.last')}</dt>
          <dd className={directionClass}>{formatMarketPrice(market.symbol, quote.mid)}</dd>
        </div>
        <div>
          <dt>{t('markets.change24h')}</dt>
          <dd className={directionClass}>{quote.changePercent.toFixed(2)}%</dd>
        </div>
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
        <div>
          <dt>{t('markets.estimatedValue')}</dt>
          <dd>{formatMarketPrice(market.symbol, quote.mid)}</dd>
        </div>
      </dl>

      <button type="button" className={styles.quoteButton} onClick={onOpenQuote}>
        <Radio size={15} />
        {t('trading.quote')}
      </button>
    </header>
  )
}
