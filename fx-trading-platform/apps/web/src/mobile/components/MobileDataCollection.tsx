import { DataCardList } from '@fx-platform/ui'
import { useTranslation } from 'react-i18next'

import {
  RouteDataCollectionEmpty,
  RouteDataCollectionPagination,
  useRouteDataCollection,
  type RouteDataCollectionProps
} from '../../shared-widgets/data/RouteDataCollection'

export function MobileDataCollection<T extends object>(props: RouteDataCollectionProps<T>) {
  const { t } = useTranslation()
  const state = useRouteDataCollection(props.rows, props.pageSize)
  return (
    <div>
      <DataCardList
        rows={state.paged.items}
        columns={props.columns}
        rowKey={props.rowKey}
        ariaLabel={t('common.mobileTableList')}
        empty={<RouteDataCollectionEmpty message={props.emptyMessage} action={props.emptyAction} />}
      />
      <RouteDataCollectionPagination {...state.paged} onPage={state.changePage} />
    </div>
  )
}
