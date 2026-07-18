import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { useHomeRouteController } from './useHomeRouteController'

const PcHomePage = lazy(() =>
  import('../../pc/pages/home/PcHomePage').then((module) => ({ default: module.PcHomePage }))
)
const MobileHomePage = lazy(() =>
  import('../../mobile/pages/home/MobileHomePage').then((module) => ({ default: module.MobileHomePage }))
)

export function HomeRoute() {
  const model = useHomeRouteController()

  return (
    <PlatformView
      model={model}
      pc={PcHomePage}
      mobile={MobileHomePage}
      fallback={<ExchangeLoading />}
    />
  )
}
