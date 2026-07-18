import type { AccountRouteModel } from '../../../routes/account/accountRoute.types'
import type { AccountRouteMode } from '../../../routes/account/accountRouteModel'
import { AccountRouteContent } from '../../../shared-widgets/account/AccountRouteContent'
import { PcAccountDataCollection } from './PcAccountDataCollection'

export function PcAccountPageFrame({ model, expectedMode }: { model: AccountRouteModel; expectedMode: AccountRouteMode }) {
  return (
    <AccountRouteContent
      model={model}
      platform="pc"
      expectedMode={expectedMode}
      renderDataCollection={PcAccountDataCollection}
    />
  )
}
