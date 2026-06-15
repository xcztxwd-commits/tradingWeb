import { useTranslation } from 'react-i18next'

import styles from './TerminalSkeleton.module.css'

type SkeletonRowsProps = {
  rows?: number
}

export function OrderBookSkeleton({ rows = 14 }: SkeletonRowsProps) {
  const { t } = useTranslation()

  return (
    <div className={styles.orderBookSkeleton} role="status" aria-live="polite" aria-label={t('loading.orderBook')}>
      <div className={styles.headerRow} aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <div className={styles.rows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} className={index % 2 === 0 ? styles.askRow : styles.bidRow} />
        ))}
      </div>
    </div>
  )
}

export function TableSkeleton({ rows = 4 }: SkeletonRowsProps) {
  const { t } = useTranslation()

  return (
    <div className={styles.tableSkeleton} role="status" aria-live="polite" aria-label={t('loading.accountTable')}>
      <div className={styles.tableHeader} aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
      </div>
      <div className={styles.tableRows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <span key={index} />
        ))}
      </div>
    </div>
  )
}
