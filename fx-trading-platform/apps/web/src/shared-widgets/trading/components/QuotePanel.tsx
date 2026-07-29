import { Radio } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { formatMarketPrice, type TradingQuote } from '@fx-platform/frontend-core'
import styles from './QuotePanel.module.css'

type Props = {
  symbol: string
  quote: TradingQuote
}

export function QuotePanel({ symbol, quote }: Props) {
  const { t } = useTranslation()
  const directionClass = quote.changePercent >= 0 ? styles.positive : styles.negative

  return (
    <section className={styles.panel}>
      <header className={styles.header}>
        <h2>{t('trading.quote')}</h2>
        <span>
          <Radio size={13} />
          {t(quote.source)}
        </span>
      </header>

      <div className={styles.mid}>
        <span>{t('trading.mid')}</span>
        <strong className={directionClass}>{formatMarketPrice(symbol, quote.mid)}</strong>
        <small>{quote.changePercent.toFixed(2)}%</small>
      </div>

      <div className={styles.quoteGrid}>
        <div>
          <span>{t('trading.bid')}</span>
          <strong className={styles.positive}>{formatMarketPrice(symbol, quote.bid)}</strong>
        </div>
        <div>
          <span>{t('trading.ask')}</span>
          <strong className={styles.negative}>{formatMarketPrice(symbol, quote.ask)}</strong>
        </div>
        <div>
          <span>{t('markets.spread')}</span>
          <strong>{formatMarketPrice(symbol, quote.spread)}</strong>
        </div>
        <div>
          <span>{t('trading.updated')}</span>
          <strong>{new Date(quote.timestamp).toLocaleTimeString()}</strong>
        </div>
      </div>
    </section>
  )
}
