import { DataTable } from '@fx-platform/ui'
import { useTranslation } from 'react-i18next'

import {
  RouteDataCollectionEmpty,
  RouteDataCollectionPagination,
  useRouteDataCollection,
  type RouteDataCollectionProps
} from '../../shared-widgets/data/RouteDataCollection'
import surfaceStyles from '../../shared-widgets/data/UserPageSurface.module.css'

export function PcDataCollection<T extends object>(props: RouteDataCollectionProps<T>) {
  const { t } = useTranslation()
  const state = useRouteDataCollection(props.rows, props.pageSize)
  return (
    <>
      <div className={surfaceStyles['user-page__table']}>
        <DataTable
          rows={state.paged.items}
          columns={props.columns}
          rowKey={props.rowKey}
          sortKey={state.sortKey}
          sortDirection={state.sortDirection}
          onSort={state.changeSort}
          getSortLabel={(label, direction) => t('common.tableSort', {
            label,
            direction: t(direction === 'asc' ? 'common.sortAsc' : 'common.sortDesc')
          })}
          empty={<RouteDataCollectionEmpty message={props.emptyMessage} action={props.emptyAction} />}
        />
      </div>
      <RouteDataCollectionPagination {...state.paged} onPage={state.changePage} />
    </>
  )
}
