import type { Trade } from '@fx-platform/shared-types'
import { useTranslation } from 'react-i18next'

import { EmptyState } from './BottomAccountEmptyState'
import { formatTime, formatValue } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function TradesGrid({ trades, emptyLabel }: { trades: Trade[]; emptyLabel: string }) {
  const { t } = useTranslation()

  if (trades.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('trading.symbol')}</th>
          <th>{t('trading.side')}</th>
          <th>{t('positions.positionSize')}</th>
          <th>{t('common.price')}</th>
          <th>{t('orders.fee')}</th>
          <th>{t('trading.liquidityRole')}</th>
          <th>{t('trading.marketSource')}</th>
          <th>{t('common.time')}</th>
        </tr>
      </thead>
      <tbody>
        {trades.map((trade, index) => (
          <tr key={trade.id ?? `${trade.orderId ?? 'trade'}-${trade.executedAt ?? index}`}>
            <td>{formatValue(trade.symbol) || '-'}</td>
            <td>{formatValue(trade.side) || '-'}</td>
            <td>{formatValue(trade.lots) || '-'}</td>
            <td>{formatValue(trade.price) || '-'}</td>
            <td>{formatValue(trade.fee) || '-'} {formatValue(trade.feeAsset)}</td>
            <td>{formatValue(trade.liquidityRole) || '-'}</td>
            <td>{formatValue(trade.providerCode ?? trade.sourceMode) || '-'}</td>
            <td>{trade.executedAt ? formatTime(trade.executedAt) : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
