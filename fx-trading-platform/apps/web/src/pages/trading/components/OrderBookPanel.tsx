import { useTranslation } from 'react-i18next'

import { mockDepthLevels } from '../../../features/market/mockTradingData'
import { formatMarketPrice } from '../../../features/market/tradingModels'
import { useMockQuote } from '../useMockQuote'
import styles from './OrderBookPanel.module.css'

type Props = {
  symbol: string
}

export function OrderBookPanel({ symbol }: Props) {
  const { t } = useTranslation()
  const quote = useMockQuote(symbol, 500)
  const unit = Math.max(quote.spread, quote.mid * 0.00002)

  return (
    <section className={styles.panel}>
      <header className={styles.header}>
        <div>
          <h2>{t('trading.mockOrderBook')}</h2>
          <span>{t('trading.waitingDepth')}</span>
        </div>
      </header>
      <div className={styles.rows}>
        {mockDepthLevels.map((level) => {
          const price = quote.mid + level.priceOffset * unit
          const sideClass = level.side === 'ask' ? styles.ask : styles.bid

          return (
            <div key={`${level.side}-${level.priceOffset}`} className={styles.row}>
              <span className={sideClass}>{formatMarketPrice(symbol, price)}</span>
              <strong>{level.size}</strong>
            </div>
          )
        })}
      </div>
    </section>
  )
}
