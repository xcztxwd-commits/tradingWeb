import { useTranslation } from 'react-i18next'

import type { AggregationStep, MarketDataSnapshot } from './types'
import { getBaseAsset, getQuoteAsset } from './utils'
import { TradeRow } from './TradeRow'
import styles from './MarketSidePanel.module.css'

type Props = {
  aggregationStep: AggregationStep
  snapshot: MarketDataSnapshot
  symbol: string
  onSelectPrice?: (price: number) => void
}

export function RecentTrades({ aggregationStep, snapshot, symbol, onSelectPrice }: Props) {
  const { t } = useTranslation()
  const baseAsset = getBaseAsset(symbol)
  const quoteAsset = getQuoteAsset(symbol)

  return (
    <div className={styles.recentTrades}>
      <div className={styles.columnHeader}>
        <span>{t('trading.priceWithAsset', { asset: quoteAsset })}</span>
        <span>{t('trading.quantityWithAsset', { asset: baseAsset })}</span>
        <span>{t('common.time')}</span>
      </div>
      <div className={styles.tradeRows}>
        {snapshot.recentTrades.slice(0, 40).map((trade) => (
          <TradeRow key={trade.id} aggregationStep={aggregationStep} trade={trade} onSelectPrice={onSelectPrice} />
        ))}
      </div>
    </div>
  )
}
