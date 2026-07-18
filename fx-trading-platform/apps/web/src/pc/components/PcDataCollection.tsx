import { DataTable } from '@fx-platform/ui'
import { useTranslation } from 'react-i18next'

import {
  RouteDataCollectionEmpty,
  RouteDataCollectionPagination,
  useRouteDataCollection,
  type RouteDataCollectionProps
} from '../../shared-widgets/data/RouteDataCollection'

export function PcDataCollection<T extends object>(props: RouteDataCollectionProps<T>) {
  const { t } = useTranslation()
  const state = useRouteDataCollection(props.rows, props.pageSize)
  return (
    <div className="route-data-collection route-data-collection--pc">
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
      <RouteDataCollectionPagination {...state.paged} onPage={state.changePage} />
    </div>
  )
}
