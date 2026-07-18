import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { useOrdersRouteController } from './useOrdersRouteController'

const PcOrdersPage = lazy(() =>
  import('../../pc/pages/orders/PcOrdersPage').then((module) => ({ default: module.PcOrdersPage }))
)
const MobileOrdersPage = lazy(() =>
  import('../../mobile/pages/orders/MobileOrdersPage').then((module) => ({ default: module.MobileOrdersPage }))
)

export function OrdersRoute() {
  const model = useOrdersRouteController()
  return <PlatformView model={model} pc={PcOrdersPage} mobile={MobileOrdersPage} fallback={<ExchangeLoading />} />
}
