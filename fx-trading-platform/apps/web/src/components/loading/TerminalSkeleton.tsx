import { Skeleton } from '@fx-platform/ui'
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
        <Skeleton shape="line" />
        <Skeleton shape="line" />
        <Skeleton shape="line" />
      </div>
      <div className={styles.rows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <Skeleton key={index} shape="block" className={index % 2 === 0 ? styles.askRow : styles.bidRow} />
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
        <Skeleton shape="line" />
        <Skeleton shape="line" />
        <Skeleton shape="line" />
        <Skeleton shape="line" />
      </div>
      <div className={styles.tableRows} aria-hidden="true">
        {Array.from({ length: rows }, (_, index) => (
          <Skeleton key={index} shape="block" />
        ))}
      </div>
    </div>
  )
}
