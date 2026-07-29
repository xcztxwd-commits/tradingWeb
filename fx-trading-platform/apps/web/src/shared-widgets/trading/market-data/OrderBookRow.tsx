import { useTranslation } from 'react-i18next'

import type { AggregatedOrderBookRow, AggregationStep } from './types'
import { formatAmount, formatPrice, getDepthBarWidth } from './utils'
import styles from './MarketSidePanel.module.css'

type Props = {
  aggregationStep: AggregationStep
  maxCumulativeTotal: number
  row: AggregatedOrderBookRow
  showDepthBars: boolean
  onSelectPrice?: (price: number) => void
}

export function OrderBookRow({ aggregationStep, maxCumulativeTotal, row, showDepthBars, onSelectPrice }: Props) {
  const { t } = useTranslation()
  const depthWidth = showDepthBars ? getDepthBarWidth(row.cumulativeTotal, maxCumulativeTotal) : 0
  const sideClass = row.side === 'ask' ? styles.ask : styles.bid

  return (
    <button
      type="button"
      className={`${styles.marketRow} ${styles.clickableMarketRow} ${row.side === 'ask' ? styles.askRow : styles.bidRow}`}
      aria-label={t('trading.fillPrice', { price: formatPrice(row.price, aggregationStep) })}
      onClick={() => onSelectPrice?.(row.price)}
    >
      <span className={styles.depthBar} style={{ width: `${depthWidth}%` }} />
      <span className={sideClass}>{formatPrice(row.price, aggregationStep)}</span>
      <span>{formatAmount(row.amount)}</span>
      <span>{formatAmount(row.cumulativeTotal)}</span>
    </button>
  )
}
