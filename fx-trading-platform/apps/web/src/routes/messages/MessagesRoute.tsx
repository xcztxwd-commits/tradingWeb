import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import { useMessagesRouteController } from './useMessagesRouteController.ts'

const PcMessagesPage = lazy(() =>
  import('./PcMessagesPage.tsx').then((module) => ({ default: module.PcMessagesPage }))
)
const MobileMessagesPage = lazy(() =>
  import('./MobileMessagesPage.tsx').then((module) => ({ default: module.MobileMessagesPage }))
)

export function MessagesRoute() {
  const model = useMessagesRouteController()
  return (
    <PlatformView
      model={model}
      pc={PcMessagesPage}
      mobile={MobileMessagesPage}
      fallback={<ExchangeLoading />}
    />
  )
}
