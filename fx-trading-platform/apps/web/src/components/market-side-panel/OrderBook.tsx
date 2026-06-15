import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'

import { OrderBookRow } from './OrderBookRow'
import type { AggregationStep, MarketDataSnapshot, OrderBookDisplayMode, OrderBookSettings } from './types'
import { buildOrderBookRows, formatAmount, formatLastPrice, getBaseAsset, getQuoteAsset } from './utils'
import styles from './MarketSidePanel.module.css'

type Props = {
  aggregationStep: AggregationStep
  displayMode: OrderBookDisplayMode
  settings: OrderBookSettings
  snapshot: MarketDataSnapshot
  symbol: string
  onSelectPrice?: (price: number) => void
}

export function OrderBook({ aggregationStep, displayMode, settings, snapshot, symbol, onSelectPrice }: Props) {
  const { t } = useTranslation()
  const rows = useMemo(() => buildOrderBookRows(snapshot, aggregationStep, displayMode), [aggregationStep, displayMode, snapshot])
  const askRows = rows.asks
  const bidRows = rows.bids
  const baseAsset = getBaseAsset(symbol)
  const quoteAsset = getQuoteAsset(symbol)
  const bidTotal = bidRows.reduce((total, row) => total + row.amount, 0)
  const askTotal = askRows.reduce((total, row) => total + row.amount, 0)
  const total = bidTotal + askTotal
  const bidRatio = total > 0 ? (bidTotal / total) * 100 : 50
  const averagePrice = getAveragePrice([...askRows, ...bidRows])
  const bestAsk = askRows.at(-1)?.price ?? snapshot.asks[0]?.price ?? 0
  const bestBid = bidRows[0]?.price ?? snapshot.bids[0]?.price ?? 0
  const spread = bestAsk > 0 && bestBid > 0 ? Math.max(bestAsk - bestBid, 0) : 0
  const spreadValue = spread > 0 ? formatLastPrice(spread) : '--'
  const lastPriceClass =
    snapshot.lastPriceDirection === 'up'
      ? styles.lastPriceUp
      : snapshot.lastPriceDirection === 'down'
        ? styles.lastPriceDown
        : styles.lastPriceFlat
  const scrollerClassName =
    displayMode === 'both' ? `${styles.bookScroller} ${styles.bookScrollerSplit}` : `${styles.bookScroller} ${styles.bookScrollerSingle}`

  return (
    <div className={styles.orderBook}>
      <div className={styles.columnHeader}>
        <span>{t('trading.priceWithAsset', { asset: quoteAsset })}</span>
        <span>{t('trading.quantityWithAsset', { asset: baseAsset })}</span>
        <span>{t('trading.totalWithAsset', { asset: baseAsset })}</span>
      </div>

      <div className={scrollerClassName}>
        {displayMode !== 'bids' ? (
          <div className={`${styles.sideRows} ${styles.askRows}`}>
            {askRows.map((row) => (
              <OrderBookRow
                key={`ask-${row.price}`}
                aggregationStep={aggregationStep}
                maxCumulativeTotal={rows.maxCumulativeTotal}
                row={row}
                showDepthBars={settings.showDepthBars}
                onSelectPrice={onSelectPrice}
              />
            ))}
          </div>
        ) : null}

        {displayMode === 'both' ? (
          <div className={`${styles.lastPrice} ${lastPriceClass}`}>
            <strong>{formatLastPrice(snapshot.lastPrice)}</strong>
            <span>{snapshot.lastPriceDirection === 'up' ? '↑' : snapshot.lastPriceDirection === 'down' ? '↓' : '→'}</span>
          </div>
        ) : null}

        {displayMode !== 'asks' ? (
          <div className={`${styles.sideRows} ${styles.bidRows}`}>
            {bidRows.map((row) => (
              <OrderBookRow
                key={`bid-${row.price}`}
                aggregationStep={aggregationStep}
                maxCumulativeTotal={rows.maxCumulativeTotal}
                row={row}
                showDepthBars={settings.showDepthBars}
                onSelectPrice={onSelectPrice}
              />
            ))}
          </div>
        ) : null}
      </div>

      {settings.showAverageAndTotal || settings.showBidAskRatio ? (
        <div className={styles.bookStats}>
          {settings.showAverageAndTotal ? (
            <>
              <div className={styles.spreadLine}>
                <span>Spread</span>
                <strong>{spreadValue}</strong>
                <span>{formatSpreadPercent(spread, bestAsk, bestBid)}</span>
              </div>
              <div className={styles.statLine}>
                <span>{t('trading.averagePriceValue', { price: formatLastPrice(averagePrice) })}</span>
                <span>{t('trading.totalAmountValue', { amount: formatAmount(total) })}</span>
              </div>
            </>
          ) : null}
          {settings.showBidAskRatio ? (
            <>
              <div className={styles.ratioTrack} aria-label={t('trading.bidAskRatio')}>
                <span className={styles.bidRatio} style={{ width: `${bidRatio}%` }} />
                <span className={styles.askRatio} style={{ width: `${100 - bidRatio}%` }} />
              </div>
              <div className={styles.pressureLabels}>
                <span>{t('trading.bidPressure', { percent: bidRatio.toFixed(0) })}</span>
                <span>{t('trading.askPressure', { percent: (100 - bidRatio).toFixed(0) })}</span>
              </div>
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

function getAveragePrice(rows: Array<{ price: number; amount: number }>) {
  const totalAmount = rows.reduce((total, row) => total + row.amount, 0)
  if (totalAmount <= 0) return 0
  return rows.reduce((total, row) => total + row.price * row.amount, 0) / totalAmount
}

function formatSpreadPercent(spread: number, bestAsk: number, bestBid: number) {
  const midpoint = bestAsk > 0 && bestBid > 0 ? (bestAsk + bestBid) / 2 : 0
  if (spread <= 0 || midpoint <= 0) return '--'
  return `${((spread / midpoint) * 100).toFixed(3)}%`
}
