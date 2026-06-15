import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import { paginateRows, sortRows, type SortDirection } from './userPageModels'

export type DataTableColumn<T extends object> = {
  key: keyof T | string
  label: string
  sortable?: boolean
  render?: (row: T) => ReactNode
}

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
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDirection, setSortDirection] = useState<SortDirection>('desc')
  const [page, setPage] = useState(1)

  const sortedRows = useMemo(() => {
    return sortRows(rows, sortKey as keyof T, sortDirection)
  }, [rows, sortDirection, sortKey])
  const paged = useMemo(() => paginateRows(sortedRows, page, pageSize), [page, pageSize, sortedRows])

  const changeSort = (key: string) => {
    const nextDirection = sortKey === key && sortDirection === 'asc' ? 'desc' : 'asc'
    setSortKey(key)
    setSortDirection(nextDirection)
    setPage(1)
  }
  const isEmpty = paged.items.length === 0

  return (
    <div className="data-table">
      <div className="user-page__table">
        <table>
          <thead>
            <tr>
              {columns.map((column) => {
                const columnKey = String(column.key)
                const activeSort = sortKey === columnKey
                const nextSortDirection = activeSort && sortDirection === 'asc' ? 'desc' : 'asc'
                const ariaSort: 'ascending' | 'descending' | undefined = activeSort
                  ? (sortDirection === 'asc' ? 'ascending' : 'descending')
                  : undefined

                return (
                  <th key={columnKey} aria-sort={ariaSort}>
                    {column.sortable ? (
                      <button
                        type="button"
                        className="table-sort"
                        aria-label={t('common.tableSort', {
                          label: column.label,
                          direction: t(nextSortDirection === 'asc' ? 'common.sortAsc' : 'common.sortDesc')
                        })}
                        onClick={() => changeSort(columnKey)}
                      >
                        {column.label}
                        {activeSort ? <span aria-hidden="true">{sortDirection === 'asc' ? ' ↑' : ' ↓'}</span> : null}
                      </button>
                    ) : (
                      column.label
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {isEmpty ? (
              <tr>
                <td colSpan={columns.length}>
                  <TableEmptyState message={emptyMessage} action={emptyAction} />
                </td>
              </tr>
            ) : (
              paged.items.map((row) => (
                <tr key={rowKey(row)}>
                  {columns.map((column) => (
                    <td key={String(column.key)}>
                      {column.render ? column.render(row) : formatCell((row as Record<string, unknown>)[String(column.key)])}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <div className="data-table__cards" aria-label={t('common.mobileTableList')}>
        {isEmpty ? (
          <TableEmptyState message={emptyMessage} action={emptyAction} />
        ) : (
          paged.items.map((row) => (
            <article key={rowKey(row)} className="data-table__card">
              {columns.map((column) => (
                <div key={String(column.key)} className="data-table__card-row">
                  <span className="data-table__card-label">{column.label}</span>
                  <div className="data-table__card-value">
                    {column.render ? column.render(row) : formatCell((row as Record<string, unknown>)[String(column.key)])}
                  </div>
                </div>
              ))}
            </article>
          ))
        )}
      </div>
      <div className="table-pagination">
        <span>
          {t('common.pageSummary', { total: paged.total, page: paged.page, totalPages: paged.totalPages })}
        </span>
        <div>
          <button type="button" className="table-action table-action--secondary" disabled={paged.page <= 1} onClick={() => setPage((value) => value - 1)}>
            {t('common.previousPage')}
          </button>
          <button
            type="button"
            className="table-action table-action--secondary"
            disabled={paged.page >= paged.totalPages}
            onClick={() => setPage((value) => value + 1)}
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
    <div className="data-table__empty">
      <strong>{message}</strong>
      <span>{t('common.continueActionHint')}</span>
      {action ? (
        action.href ? (
          <a className="data-table__empty-action" href={action.href}>
            {action.label}
          </a>
        ) : (
          <button type="button" className="data-table__empty-action" onClick={action.onClick}>
            {action.label}
          </button>
        )
      ) : null}
    </div>
  )
}

function formatCell(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}
