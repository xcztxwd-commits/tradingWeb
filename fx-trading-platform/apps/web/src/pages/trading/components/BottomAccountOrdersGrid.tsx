import { useTranslation } from 'react-i18next'

import type { OrderResponse } from '../../../components/tables/types'
import { getOrderActionSummary } from '../../orders/orderActionPolicy'
import { EmptyState } from './BottomAccountEmptyState'
import { formatTime, formatValue } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function OrdersGrid({ orders, emptyLabel }: { orders: OrderResponse[]; emptyLabel: string }) {
  const { t } = useTranslation()

  if (orders.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('trading.symbol')}</th>
          <th>{t('trading.side')}</th>
          <th>{t('common.type')}</th>
          <th>{t('positions.positionSize')}</th>
          <th>{t('common.status')}</th>
          <th>{t('orders.actionStatus')}</th>
          <th>{t('common.price')}</th>
          <th>{t('common.time')}</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((order) => {
          const actionSummary = getOrderActionSummary(order, t)

          return (
            <tr key={order.id}>
              <td>{order.symbol}</td>
              <td>{order.side}</td>
              <td>{order.orderType}</td>
              <td>{formatValue(order.quantity ?? order.lots)}</td>
              <td>{order.status}</td>
              <td className={styles.actionState} title={actionSummary}>
                {actionSummary}
              </td>
              <td>{formatValue(order.avgFillPrice ?? order.executionPrice ?? order.price) || '-'}</td>
              <td>{formatTime(order.createdAt)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
