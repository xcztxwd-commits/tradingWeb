import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  DataTable,
  getNextDataSort,
  getNextPage,
  type DataSortDirection
} from '@fx-platform/ui'
import { paginateRows, sortRows } from '@fx-platform/frontend-core'

import type { AccountDataCollectionProps } from '../../../shared-widgets/account/dataCollection.types'
import styles from './PcAccountPages.module.css'

export function PcAccountDataCollection<T extends object>({
  rows,
  columns,
  rowKey,
  emptyMessage,
  emptyAction,
  pageSize = 10,
  heading
}: AccountDataCollectionProps<T>) {
  const { t } = useTranslation()
  const [sortKey, setSortKey] = useState<string | null>(null)
  const [sortDirection, setSortDirection] = useState<DataSortDirection>('desc')
  const [page, setPage] = useState(1)
  const sortedRows = useMemo(
    () => sortRows(rows, (sortKey ?? '') as keyof T, sortDirection),
    [rows, sortDirection, sortKey]
  )
  const paged = useMemo(() => paginateRows(sortedRows, page, pageSize), [page, pageSize, sortedRows])

  const changeSort = (key: string) => {
    const nextSort = getNextDataSort({ key: sortKey, direction: sortDirection }, key)
    setSortKey(nextSort.key)
    setSortDirection(nextSort.direction)
    setPage(1)
  }

  return (
    <section className={styles.dataCollection}>
      {heading}
      <div className={styles.tableViewport}>
        <DataTable
          rows={paged.items}
          columns={columns}
          rowKey={rowKey}
          sortKey={sortKey}
          sortDirection={sortDirection}
          onSort={changeSort}
          getSortLabel={(label, direction) => t('common.tableSort', {
            label,
            direction: t(direction === 'asc' ? 'common.sortAsc' : 'common.sortDesc')
          })}
          empty={<EmptyState message={emptyMessage} action={emptyAction} />}
        />
      </div>
      <Pagination page={paged.page} total={paged.total} totalPages={paged.totalPages} setPage={setPage} />
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

function Pagination({ page, total, totalPages, setPage }: { page: number; total: number; totalPages: number; setPage: (update: (value: number) => number) => void }) {
  const { t } = useTranslation()
  return (
    <div className={styles.pagination}>
      <span>{t('common.pageSummary', { total, page, totalPages })}</span>
      <div>
        <button type="button" className="table-action table-action--secondary" disabled={page <= 1} onClick={() => setPage((value) => getNextPage(value, -1, totalPages))}>{t('common.previousPage')}</button>
        <button type="button" className="table-action table-action--secondary" disabled={page >= totalPages} onClick={() => setPage((value) => getNextPage(value, 1, totalPages))}>{t('common.nextPage')}</button>
      </div>
    </div>
  )
}
