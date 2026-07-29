import { lazy, type ComponentType } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import type { AccountRouteModel } from './accountRoute.types'
import type { AccountRouteMode } from './accountRouteModel'
import { useAccountRouteController } from './useAccountRouteController'

const PcDashboardPage = lazy(() => import('../../pc/pages/account/PcDashboardPage').then((module) => ({ default: module.PcDashboardPage })))
const PcAccountOverviewPage = lazy(() => import('../../pc/pages/account/PcAccountOverviewPage').then((module) => ({ default: module.PcAccountOverviewPage })))
const PcAccountAssetsPage = lazy(() => import('../../pc/pages/account/PcAccountAssetsPage').then((module) => ({ default: module.PcAccountAssetsPage })))
const PcFundingRecordsPage = lazy(() => import('../../pc/pages/account/PcFundingRecordsPage').then((module) => ({ default: module.PcFundingRecordsPage })))
const PcTradeRecordsPage = lazy(() => import('../../pc/pages/account/PcTradeRecordsPage').then((module) => ({ default: module.PcTradeRecordsPage })))
const PcKycPage = lazy(() => import('../../pc/pages/account/PcKycPage').then((module) => ({ default: module.PcKycPage })))
const PcAccountSettingsPage = lazy(() => import('../../pc/pages/account/PcAccountSettingsPage').then((module) => ({ default: module.PcAccountSettingsPage })))
const PcSecurityCenterPage = lazy(() => import('../../pc/pages/account/PcSecurityCenterPage').then((module) => ({ default: module.PcSecurityCenterPage })))
const PcSettingsPage = lazy(() => import('../../pc/pages/account/PcSettingsPage').then((module) => ({ default: module.PcSettingsPage })))
const MobileDashboardPage = lazy(() => import('../../mobile/pages/account/MobileDashboardPage').then((module) => ({ default: module.MobileDashboardPage })))
const MobileAccountOverviewPage = lazy(() => import('../../mobile/pages/account/MobileAccountOverviewPage').then((module) => ({ default: module.MobileAccountOverviewPage })))
const MobileAccountAssetsPage = lazy(() => import('../../mobile/pages/account/MobileAccountAssetsPage').then((module) => ({ default: module.MobileAccountAssetsPage })))
const MobileFundingRecordsPage = lazy(() => import('../../mobile/pages/account/MobileFundingRecordsPage').then((module) => ({ default: module.MobileFundingRecordsPage })))
const MobileTradeRecordsPage = lazy(() => import('../../mobile/pages/account/MobileTradeRecordsPage').then((module) => ({ default: module.MobileTradeRecordsPage })))
const MobileKycPage = lazy(() => import('../../mobile/pages/account/MobileKycPage').then((module) => ({ default: module.MobileKycPage })))
const MobileAccountSettingsPage = lazy(() => import('../../mobile/pages/account/MobileAccountSettingsPage').then((module) => ({ default: module.MobileAccountSettingsPage })))
const MobileSecurityCenterPage = lazy(() => import('../../mobile/pages/account/MobileSecurityCenterPage').then((module) => ({ default: module.MobileSecurityCenterPage })))
const MobileSettingsPage = lazy(() => import('../../mobile/pages/account/MobileSettingsPage').then((module) => ({ default: module.MobileSettingsPage })))

const accountViews = {
  'dashboard': { pc: PcDashboardPage, mobile: MobileDashboardPage },
  'overview': { pc: PcAccountOverviewPage, mobile: MobileAccountOverviewPage },
  'assets': { pc: PcAccountAssetsPage, mobile: MobileAccountAssetsPage },
  'funding-records': { pc: PcFundingRecordsPage, mobile: MobileFundingRecordsPage },
  'trade-records': { pc: PcTradeRecordsPage, mobile: MobileTradeRecordsPage },
  'kyc': { pc: PcKycPage, mobile: MobileKycPage },
  'account-settings': { pc: PcAccountSettingsPage, mobile: MobileAccountSettingsPage },
  'security': { pc: PcSecurityCenterPage, mobile: MobileSecurityCenterPage },
  'settings': { pc: PcSettingsPage, mobile: MobileSettingsPage }
} satisfies Record<AccountRouteMode, { pc: ComponentType<{ model: AccountRouteModel }>; mobile: ComponentType<{ model: AccountRouteModel }> }>

export function AccountRoute({ mode }: { mode: AccountRouteMode }) {
  const model = useAccountRouteController(mode)
  const views = accountViews[mode]
  return <PlatformView model={model} pc={views.pc} mobile={views.mobile} fallback={<ExchangeLoading />} />
}
