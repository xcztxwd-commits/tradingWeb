import { useTranslation } from 'react-i18next'

import type { AggregationStep, TradeItem } from './types'
import { formatAmount, formatPrice, formatTradeTime } from './utils'
import styles from './MarketSidePanel.module.css'

type Props = {
  aggregationStep: AggregationStep
  trade: TradeItem
  onSelectPrice?: (price: number) => void
}

export function TradeRow({ aggregationStep, trade, onSelectPrice }: Props) {
  const { t } = useTranslation()

  return (
    <button
      type="button"
      className={`${styles.marketRow} ${styles.clickableMarketRow}`}
      aria-label={t('trading.fillTradePrice', { price: formatPrice(trade.price, aggregationStep) })}
      onClick={() => onSelectPrice?.(trade.price)}
    >
      <span className={trade.side === 'buy' ? styles.bid : styles.ask}>{formatPrice(trade.price, aggregationStep)}</span>
      <span>{formatAmount(trade.amount)}</span>
      <span>{formatTradeTime(trade.time)}</span>
    </button>
  )
}
