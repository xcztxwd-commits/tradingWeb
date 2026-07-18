import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { usePositionsRouteController } from './usePositionsRouteController'

const PcPositionsPage = lazy(() =>
  import('../../pc/pages/positions/PcPositionsPage').then((module) => ({ default: module.PcPositionsPage }))
)
const MobilePositionsPage = lazy(() =>
  import('../../mobile/pages/positions/MobilePositionsPage').then((module) => ({ default: module.MobilePositionsPage }))
)

export function PositionsRoute() {
  const model = usePositionsRouteController()
  return <PlatformView model={model} pc={PcPositionsPage} mobile={MobilePositionsPage} fallback={<ExchangeLoading />} />
}
