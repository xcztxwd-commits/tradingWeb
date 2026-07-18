import {
  DataCardList,
  DataTable as UiDataTable,
  getNextDataSort,
  getNextPage,
  type DataSortDirection,
  type DataViewColumn
} from '@fx-platform/ui'
import { paginateRows, sortRows } from '@fx-platform/frontend-core'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

export type DataTableColumn<T extends object> = DataViewColumn<T>

type Props<T extends object> = {
  rows: T[]
  columns: DataTableColumn<T>[]
  rowKey: (row: T) => string
  emptyMessage: string
  emptyAction?: {
    label: string
    href?: string
    onClick?: () => void
  }
  pageSize?: number
}

export function DataTable<T extends object>({
  rows,
  columns,
  rowKey,
  emptyMessage,
  emptyAction,
  pageSize = 10
}: Props<T>) {
  const { t } = useTranslation()
  const [sortKey, setSortKey] = useState<string | null>(null)
  const [sortDirection, setSortDirection] = useState<DataSortDirection>('desc')
  const [page, setPage] = useState(1)

  const sortedRows = useMemo(() => {
    return sortRows(rows, (sortKey ?? '') as keyof T, sortDirection)
  }, [rows, sortDirection, sortKey])
  const paged = useMemo(() => paginateRows(sortedRows, page, pageSize), [page, pageSize, sortedRows])

  const changeSort = (key: string) => {
    const nextSort = getNextDataSort({ key: sortKey, direction: sortDirection }, key)
    setSortKey(nextSort.key)
    setSortDirection(nextSort.direction)
    setPage(1)
  }
  const empty = <TableEmptyState message={emptyMessage} action={emptyAction} />
  const getSortLabel = (label: string, direction: DataSortDirection) => t('common.tableSort', {
    label,
    direction: t(direction === 'asc' ? 'common.sortAsc' : 'common.sortDesc')
  })

  return (
    <div className="data-table">
      <div className="user-page__table">
        <UiDataTable
          rows={paged.items}
          columns={columns}
          rowKey={rowKey}
          sortKey={sortKey}
          sortDirection={sortDirection}
          onSort={changeSort}
          getSortLabel={getSortLabel}
          empty={empty}
        />
      </div>
      <div className="data-table__cards">
        <DataCardList
          rows={paged.items}
          columns={columns}
          rowKey={rowKey}
          ariaLabel={t('common.mobileTableList')}
          empty={empty}
        />
      </div>
      <div className="table-pagination">
        <span>{t('common.pageSummary', { total: paged.total, page: paged.page, totalPages: paged.totalPages })}</span>
        <div>
          <button
            type="button"
            className="table-action table-action--secondary"
            disabled={paged.page <= 1}
            onClick={() => setPage((value) => getNextPage(value, -1, paged.totalPages))}
          >
            {t('common.previousPage')}
          </button>
          <button
            type="button"
            className="table-action table-action--secondary"
            disabled={paged.page >= paged.totalPages}
            onClick={() => setPage((value) => getNextPage(value, 1, paged.totalPages))}
          >
            {t('common.nextPage')}
          </button>
        </div>
      </div>
    </div>
  )
}

function TableEmptyState({
  message,
  action
}: {
  message: string
  action?: {
    label: string
    href?: string
    onClick?: () => void
  }
}) {
  const { t } = useTranslation()

  return (
    <>
      <strong>{message}</strong>
      <span>{t('common.continueActionHint')}</span>
      {action ? (
        action.href ? (
          <a className="table-action table-action--primary" href={action.href}>
            {action.label}
          </a>
        ) : (
          <button type="button" className="table-action table-action--primary" onClick={action.onClick}>
            {action.label}
          </button>
        )
      ) : null}
    </>
  )
}
