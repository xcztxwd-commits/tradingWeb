import type { AccountTransferResponse } from '@fx-platform/shared-types'
import { useTranslation } from 'react-i18next'

import { EmptyState } from './BottomAccountEmptyState'
import { formatTime, formatValue } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function TransfersGrid({ transfers, emptyLabel }: { transfers: AccountTransferResponse[]; emptyLabel: string }) {
  const { t } = useTranslation()

  if (transfers.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('trading.transferDirection')}</th>
          <th>{t('common.amount')}</th>
          <th>{t('trading.spotAvailable')}</th>
          <th>{t('trading.perpBalance')}</th>
          <th>{t('trading.replayed')}</th>
          <th>{t('common.time')}</th>
        </tr>
      </thead>
      <tbody>
        {transfers.map((transfer, index) => (
          <tr key={transfer.transferId ?? `${transfer.direction ?? 'transfer'}-${transfer.createdAt ?? index}`}>
            <td>{formatValue(transfer.direction) || '-'}</td>
            <td>{formatValue(transfer.amount) || '-'}</td>
            <td>{formatValue(transfer.spotAvailable) || '-'}</td>
            <td>{formatValue(transfer.perpBalance) || '-'}</td>
            <td>{transfer.replayed === undefined ? '-' : String(transfer.replayed)}</td>
            <td>{transfer.createdAt ? formatTime(transfer.createdAt) : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
