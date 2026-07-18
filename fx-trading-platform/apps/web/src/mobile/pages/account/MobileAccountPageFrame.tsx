import type { AccountRouteModel } from '../../../routes/account/accountRoute.types'
import type { AccountRouteMode } from '../../../routes/account/accountRouteModel'
import { AccountRouteContent } from '../../../shared-widgets/account/AccountRouteContent'
import { MobileAccountDataCollection } from './MobileAccountDataCollection'

export function MobileAccountPageFrame({ model, expectedMode }: { model: AccountRouteModel; expectedMode: AccountRouteMode }) {
  return (
    <AccountRouteContent
      model={model}
      platform="mobile"
      expectedMode={expectedMode}
      renderDataCollection={MobileAccountDataCollection}
    />
  )
}
