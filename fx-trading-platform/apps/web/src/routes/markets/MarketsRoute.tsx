import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { useMarketsRouteController } from './useMarketsRouteController'

const PcMarketsPage = lazy(() =>
  import('../../pc/pages/markets/PcMarketsPage').then((module) => ({ default: module.PcMarketsPage }))
)
const MobileMarketsPage = lazy(() =>
  import('../../mobile/pages/markets/MobileMarketsPage').then((module) => ({ default: module.MobileMarketsPage }))
)

export function MarketsRoute() {
  const model = useMarketsRouteController()
  return <PlatformView model={model} pc={PcMarketsPage} mobile={MobileMarketsPage} fallback={<ExchangeLoading />} />
}
