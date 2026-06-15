import { useTranslation } from 'react-i18next'

import type { AccountSummary, LedgerEntry } from '../../../types/trading'
import { EmptyState } from './BottomAccountEmptyState'
import { formatTime, formatValue } from './bottomAccountFormatters'
import styles from './BottomAccountPanel.module.css'

export function AssetView({
  account,
  ledgerEntries,
  sessionReady
}: {
  account?: AccountSummary
  ledgerEntries: LedgerEntry[]
  sessionReady: boolean
}) {
  return (
    <div className={styles.assetView}>
      <AssetGrid account={account} sessionReady={sessionReady} />
      <LedgerGrid entries={ledgerEntries} />
    </div>
  )
}

function AssetGrid({ account, sessionReady }: { account?: AccountSummary; sessionReady: boolean }) {
  const { t } = useTranslation()
  const rows = account
    ? [
        [t('assets.balance'), account.balance],
        [t('dashboard.equity'), account.equity],
        [t('assets.usedMargin'), account.usedMargin],
        [t('dashboard.freeMargin'), account.freeMargin],
        [t('positions.leverage'), `${account.leverage}x`],
        [t('common.status'), account.status]
      ]
    : [[t('common.status'), sessionReady ? t('trading.demoAccountReady') : t('trading.demoAccountLoading')]]

  return (
    <div className={styles.metricGrid}>
      {rows.map(([label, value]) => (
        <div key={label} className={styles.metric}>
          <span>{label}</span>
          <strong>{formatValue(value)}</strong>
        </div>
      ))}
    </div>
  )
}

function LedgerGrid({ entries }: { entries: LedgerEntry[] }) {
  const { t } = useTranslation()

  if (entries.length === 0) return <EmptyState label={t('assets.emptyLedger')} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('common.type')}</th>
          <th>{t('common.amount')}</th>
          <th>{t('assets.balance')}</th>
          <th>{t('common.currency')}</th>
          <th>{t('common.time')}</th>
        </tr>
      </thead>
      <tbody>
        {entries.slice(0, 8).map((entry) => (
          <tr key={entry.id}>
            <td>{entry.entryType}</td>
            <td>{formatValue(entry.amount)}</td>
            <td>{formatValue(entry.balanceAfter)}</td>
            <td>{entry.currency}</td>
            <td>{entry.createdAt ? formatTime(entry.createdAt) : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
