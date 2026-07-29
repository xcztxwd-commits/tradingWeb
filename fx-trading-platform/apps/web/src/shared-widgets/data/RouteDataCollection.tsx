import { useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import {
  getNextDataSort,
  getNextPage,
  type DataSortDirection,
  type DataViewColumn
} from '@fx-platform/ui'
import { paginateRows, sortRows } from '@fx-platform/frontend-core'

import { cssModuleClasses as css } from './cssModuleClasses'
import styles from './UserPageSurface.module.css'

export type RouteDataCollectionProps<T extends object> = {
  rows: T[]
  columns: Array<DataViewColumn<T>>
  rowKey: (row: T) => string
  emptyMessage: string
  emptyAction?: {
    label: string
    href?: string
    onClick?: () => void
  }
  pageSize?: number
}

export type RouteDataCollectionRenderer = <T extends object>(props: RouteDataCollectionProps<T>) => ReactNode

export function useRouteDataCollection<T extends object>(rows: T[], pageSize = 10) {
  const [sortKey, setSortKey] = useState<string | null>(null)
  const [sortDirection, setSortDirection] = useState<DataSortDirection>('desc')
  const [page, setPage] = useState(1)
  const sortedRows = useMemo(
    () => sortRows(rows, (sortKey ?? '') as keyof T, sortDirection),
    [rows, sortDirection, sortKey]
  )
  const paged = useMemo(() => paginateRows(sortedRows, page, pageSize), [page, pageSize, sortedRows])

  return {
    paged,
    sortKey,
    sortDirection,
    changeSort(key: string) {
      const next = getNextDataSort({ key: sortKey, direction: sortDirection }, key)
      setSortKey(next.key)
      setSortDirection(next.direction)
      setPage(1)
    },
    changePage(delta: -1 | 1) {
      setPage((current) => getNextPage(current, delta, paged.totalPages))
    }
  }
}

export function RouteDataCollectionEmpty({
  message,
  action
}: {
  message: string
  action?: RouteDataCollectionProps<object>['emptyAction']
}) {
  const { t } = useTranslation()
  return (
    <>
      <strong>{message}</strong>
      <span>{t('common.continueActionHint')}</span>
      {action ? (
        action.href ? (
          <a className={css(styles, "table-action", "table-action--primary")} href={action.href}>{action.label}</a>
        ) : (
          <button type="button" className={css(styles, "table-action", "table-action--primary")} onClick={action.onClick}>{action.label}</button>
        )
      ) : null}
    </>
  )
}

export function RouteDataCollectionPagination({
  total,
  page,
  totalPages,
  onPage
}: {
  total: number
  page: number
  totalPages: number
  onPage(delta: -1 | 1): void
}) {
  const { t } = useTranslation()
  return (
    <div className={css(styles, "table-pagination")}>
      <span>{t('common.pageSummary', { total, page, totalPages })}</span>
      <div>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} disabled={page <= 1} onClick={() => onPage(-1)}>
          {t('common.previousPage')}
        </button>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} disabled={page >= totalPages} onClick={() => onPage(1)}>
          {t('common.nextPage')}
        </button>
      </div>
    </div>
  )
}
