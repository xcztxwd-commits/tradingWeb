import { useTranslation } from 'react-i18next'

import type { OrderResponse } from '@fx-platform/frontend-core'
import { getOrderActionSummary } from '../../../routes/orders/orderActionPolicy'
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
          <th>{t('orders.timeline')}</th>
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
              <td>
                <OrderTimeline order={order} />
              </td>
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

function OrderTimeline({ order }: { order: OrderResponse }) {
  const { t } = useTranslation()
  const items = getOrderTimelineItems(order)

  return (
    <ol className={styles.orderTimeline} aria-label={t('orders.timeline')}>
      {items.map((item) => (
        <li key={item.key}>
          <span>{t(item.labelKey)}</span>
          <strong>{item.status}</strong>
          <time dateTime={item.time}>{formatTime(item.time)}</time>
        </li>
      ))}
    </ol>
  )
}

function getOrderTimelineItems(order: OrderResponse) {
  const items = [
    {
      key: 'created',
      labelKey: 'orders.timelineCreated',
      status: 'NEW',
      time: order.createdAt
    }
  ]
  const terminalTime = order.filledAt ?? order.canceledAt

  if (order.updatedAt && order.updatedAt !== order.createdAt && !terminalTime) {
    items.push({
      key: 'updated',
      labelKey: 'orders.timelineUpdated',
      status: order.status,
      time: order.updatedAt
    })
  }

  if (order.filledAt) {
    items.push({
      key: 'filled',
      labelKey: 'orders.timelineFilled',
      status: 'FILLED',
      time: order.filledAt
    })
  }

  if (order.canceledAt) {
    items.push({
      key: 'canceled',
      labelKey: 'orders.timelineCanceled',
      status: 'CANCELED',
      time: order.canceledAt
    })
  }

  return items
}
