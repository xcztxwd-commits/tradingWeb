import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { useWalletRouteController } from './useWalletRouteController'

const PcWalletPage = lazy(() =>
  import('../../pc/pages/wallet/PcWalletPage').then((module) => ({ default: module.PcWalletPage }))
)
const MobileWalletPage = lazy(() =>
  import('../../mobile/pages/wallet/MobileWalletPage').then((module) => ({ default: module.MobileWalletPage }))
)

export function WalletRoute() {
  const model = useWalletRouteController()
  return <PlatformView model={model} pc={PcWalletPage} mobile={MobileWalletPage} fallback={<ExchangeLoading />} />
}
