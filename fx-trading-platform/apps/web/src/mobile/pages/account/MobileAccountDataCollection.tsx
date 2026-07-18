import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { DataCardList, getNextPage } from '@fx-platform/ui'
import { paginateRows } from '@fx-platform/frontend-core'

import type { AccountDataCollectionProps } from '../../../shared-widgets/account/dataCollection.types'
import styles from './MobileAccountPages.module.css'

export function MobileAccountDataCollection<T extends object>({
  rows,
  columns,
  rowKey,
  emptyMessage,
  emptyAction,
  pageSize = 10,
  heading
}: AccountDataCollectionProps<T>) {
  const { t } = useTranslation()
  const [page, setPage] = useState(1)
  const paged = useMemo(() => paginateRows(rows, page, pageSize), [page, pageSize, rows])

  return (
    <section className={styles.dataCollection}>
      {heading}
      <DataCardList
        rows={paged.items}
        columns={columns}
        rowKey={rowKey}
        ariaLabel={t('common.mobileTableList')}
        empty={<EmptyState message={emptyMessage} action={emptyAction} />}
      />
      <div className={styles.pagination}>
        <span>{t('common.pageSummary', { total: paged.total, page: paged.page, totalPages: paged.totalPages })}</span>
        <div>
          <button type="button" className="table-action table-action--secondary" disabled={paged.page <= 1} onClick={() => setPage((value) => getNextPage(value, -1, paged.totalPages))}>{t('common.previousPage')}</button>
          <button type="button" className="table-action table-action--secondary" disabled={paged.page >= paged.totalPages} onClick={() => setPage((value) => getNextPage(value, 1, paged.totalPages))}>{t('common.nextPage')}</button>
        </div>
      </div>
    </section>
  )
}

function EmptyState({ message, action }: { message: string; action?: AccountDataCollectionProps['emptyAction'] }) {
  const { t } = useTranslation()
  return (
    <>
      <strong>{message}</strong>
      <span>{t('common.continueActionHint')}</span>
      {action ? action.href ? (
        <a className="table-action table-action--primary" href={action.href}>{action.label}</a>
      ) : (
        <button type="button" className="table-action table-action--primary" onClick={action.onClick}>{action.label}</button>
      ) : null}
    </>
  )
}
