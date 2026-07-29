import { useTranslation } from 'react-i18next'

import type { StrategyRow } from './bottomAccountPanelData'
import { EmptyState } from './BottomAccountEmptyState'
import { formatTime } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function StrategiesGrid({ strategies, emptyLabel }: { strategies: StrategyRow[]; emptyLabel: string }) {
  const { t } = useTranslation()

  if (strategies.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('trading.strategyColumn')}</th>
          <th>{t('trading.symbol')}</th>
          <th>{t('trading.mode')}</th>
          <th>{t('common.status')}</th>
          <th>{t('trading.exposure')}</th>
          <th>PnL</th>
          <th>{t('trading.updated')}</th>
        </tr>
      </thead>
      <tbody>
        {strategies.map((strategy) => (
          <tr key={strategy.id}>
            <td>{strategy.name}</td>
            <td>{strategy.symbol}</td>
            <td>{strategy.mode}</td>
            <td>
              <span className={styles.statusPill}>{strategy.status}</span>
            </td>
            <td>{strategy.exposure}</td>
            <td className={strategy.pnl.startsWith('-') ? styles.negativeValue : styles.positiveValue}>
              {strategy.pnl}
            </td>
            <td>{formatTime(strategy.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
