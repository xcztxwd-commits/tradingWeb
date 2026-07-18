import type { ReactNode } from 'react'

import type { DataSortDirection } from './dataViewState'
import styles from './DataView.module.css'

export type DataViewColumn<T extends object> = {
  key: keyof T | string
  label: string
  sortable?: boolean
  render?: (row: T) => ReactNode
}

export type DataTableProps<T extends object> = {
  rows: readonly T[]
  columns: readonly DataViewColumn<T>[]
  rowKey: (row: T) => string
  sortKey?: string | null
  sortDirection?: DataSortDirection
  onSort?: (key: string) => void
  getSortLabel: (columnLabel: string, nextDirection: DataSortDirection) => string
  empty?: ReactNode
}

export function DataTable<T extends object>({
  rows,
  columns,
  rowKey,
  sortKey = null,
  sortDirection = 'asc',
  onSort,
  getSortLabel,
  empty
}: DataTableProps<T>) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          {columns.map((column) => {
            const columnKey = String(column.key)
            const activeSort = sortKey === columnKey
            const nextDirection: DataSortDirection = activeSort && sortDirection === 'asc' ? 'desc' : 'asc'
            const ariaSort: 'ascending' | 'descending' | undefined = activeSort
              ? (sortDirection === 'asc' ? 'ascending' : 'descending')
              : undefined

            return (
              <th key={columnKey} aria-sort={ariaSort}>
                {column.sortable ? (
                  <button
                    type="button"
                    className={styles.sort}
                    aria-label={getSortLabel(column.label, nextDirection)}
                    onClick={() => onSort?.(columnKey)}
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
        {rows.length === 0 ? (
          <tr>
            <td colSpan={Math.max(1, columns.length)}>
              <div className={styles.empty}>{empty}</div>
            </td>
          </tr>
        ) : (
          rows.map((row) => (
            <tr key={rowKey(row)}>
              {columns.map((column) => (
                <td key={String(column.key)}>{renderCell(row, column)}</td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  )
}

export function renderCell<T extends object>(row: T, column: DataViewColumn<T>) {
  if (column.render) return column.render(row)
  const value = (row as Record<string, unknown>)[String(column.key)]
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}
