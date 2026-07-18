import type { AccountRouteModel } from '../../routes/account/accountRoute.types'
import type { AccountRouteMode } from '../../routes/account/accountRouteModel'
import { AccountSettingsContent, AccountAssetsContent, AccountOverviewContent, FundingRecordsContent, KycContent, TradeOrdersContent } from './AccountPagesContent'
import { DashboardContent } from './DashboardContent'
import { SecurityContent } from './SecurityContent'
import { SettingsContent } from './SettingsContent'
import type { AccountDataCollectionComponent } from './dataCollection.types'

type AccountRouteContentProps = {
  model: AccountRouteModel
  platform: 'pc' | 'mobile'
  expectedMode: AccountRouteMode
  renderDataCollection: AccountDataCollectionComponent
}

export function AccountRouteContent({
  model,
  platform,
  expectedMode,
  renderDataCollection: DataCollection
}: AccountRouteContentProps) {
  if (model.mode !== expectedMode) return null

  const content = (() => {
    switch (model.mode) {
      case 'dashboard':
        return <DashboardContent model={model} DataCollection={DataCollection} />
      case 'overview':
        return <AccountOverviewContent model={model} DataCollection={DataCollection} />
      case 'assets':
        return <AccountAssetsContent model={model} DataCollection={DataCollection} />
      case 'funding-records':
        return <FundingRecordsContent model={model} DataCollection={DataCollection} />
      case 'trade-records':
        return <TradeOrdersContent model={model} DataCollection={DataCollection} />
      case 'kyc':
        return <KycContent model={model} DataCollection={DataCollection} />
      case 'account-settings':
        return <AccountSettingsContent model={model} DataCollection={DataCollection} />
      case 'security':
        return <SecurityContent />
      case 'settings':
        return <SettingsContent model={model} />
    }
  })()

  return <div data-account-platform={platform}>{content}</div>
}
