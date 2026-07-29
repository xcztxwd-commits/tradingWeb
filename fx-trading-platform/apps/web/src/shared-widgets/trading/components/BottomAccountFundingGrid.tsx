import type { FundingSettlement } from '@fx-platform/shared-types'
import { useTranslation } from 'react-i18next'

import { EmptyState } from './BottomAccountEmptyState'
import { formatTime, formatValue } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function FundingGrid({ settlements, emptyLabel }: { settlements: FundingSettlement[]; emptyLabel: string }) {
  const { t } = useTranslation()

  if (settlements.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('trading.symbol')}</th>
          <th>{t('trading.fundingRate')}</th>
          <th>{t('common.amount')}</th>
          <th>{t('common.currency')}</th>
          <th>{t('positions.positionSide')}</th>
          <th>{t('trading.marketSource')}</th>
          <th>{t('common.time')}</th>
        </tr>
      </thead>
      <tbody>
        {settlements.map((settlement, index) => (
          <tr key={settlement.id ?? `${settlement.positionId ?? 'funding'}-${settlement.fundingTime ?? index}`}>
            <td>{formatValue(settlement.symbol) || '-'}</td>
            <td>{formatValue(settlement.fundingRate) || '-'}</td>
            <td className={Number(settlement.amount) < 0 ? styles.negativeValue : styles.positiveValue}>
              {formatValue(settlement.amount) || '-'}
            </td>
            <td>{formatValue(settlement.asset) || '-'}</td>
            <td>{formatValue(settlement.positionSide) || '-'}</td>
            <td>{formatValue(settlement.source) || '-'}</td>
            <td>{settlement.fundingTime ? formatTime(settlement.fundingTime) : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
