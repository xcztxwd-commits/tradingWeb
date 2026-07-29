import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { AdminLayout } from './AdminLayout'
import { RequireAdmin } from './RequireAdmin'
import { hasAdminAuthority } from '../services/adminToken'
import { AccountsPage } from '../pages/AccountsPage'
import { AccountDetailPage } from '../pages/AccountDetailPage'
import { ArticlesPage } from '../pages/ArticlesPage'
import { AuditLogsPage } from '../pages/AuditLogsPage'
import { DashboardPage } from '../pages/DashboardPage'
import { DataProvidersPage } from '../pages/DataProvidersPage'
import { DictionariesPage } from '../pages/DictionariesPage'
import { FeatureCrudPage } from '../pages/FeatureCrudPage'
import { FundingConfigPage } from '../pages/FundingConfigPage'
import { FundingSettlementsPage } from '../pages/FundingSettlementsPage'
import { LedgerPage } from '../pages/LedgerPage'
import { LoginPage } from '../pages/LoginPage'
import { MarketStatusPage } from '../pages/MarketStatusPage'
import { MemberNoticeEditorPage } from '../pages/MemberNoticeEditorPage'
import { MemberNoticePage } from '../pages/MemberNoticePage'
import { OrdersPage } from '../pages/OrdersPage'
import { PaymentMethodsPage } from '../pages/PaymentMethodsPage'
import { PositionsPage } from '../pages/PositionsPage'
import { PopupCampaignEditorPage } from '../pages/PopupCampaignEditorPage'
import { PopupCampaignPage } from '../pages/PopupCampaignPage'
import { PopupCampaignPolicyPage } from '../pages/PopupCampaignPolicyPage'
import { PopupCampaignStatsPage } from '../pages/PopupCampaignStatsPage'
import { ProviderInstrumentsPage } from '../pages/ProviderInstrumentsPage'
import { RiskPage } from '../pages/RiskPage'
import { SettingsPage } from '../pages/SettingsPage'
import { SymbolDataBindingsPage } from '../pages/SymbolDataBindingsPage'
import { SymbolsPage } from '../pages/SymbolsPage'
import { TradesPage } from '../pages/TradesPage'
import { UsersPage } from '../pages/UsersPage'

const TradingLabPage = lazy(() =>
  import('../features/tradingLab').then((module) => ({ default: module.TradingLabPage }))
)

export function AdminApp() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAdmin />}>
        <Route element={<AdminLayout />}>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />

          <Route path="/system/users" element={<FeatureCrudPage pageKey="system-users" />} />
          <Route path="/system/roles" element={<FeatureCrudPage pageKey="system-roles" />} />
          <Route path="/system/departments" element={<FeatureCrudPage pageKey="system-departments" />} />
          <Route path="/system/menus" element={<FeatureCrudPage pageKey="system-menus" />} />
          <Route path="/system/posts" element={<FeatureCrudPage pageKey="system-posts" />} />

          <Route path="/products/list" element={<FeatureCrudPage pageKey="products" />} />
          <Route path="/products/categories" element={<FeatureCrudPage pageKey="product-categories" />} />
          <Route path="/products/price-schedules" element={<FeatureCrudPage pageKey="price-schedules" />} />
          <Route path="/products/data-providers" element={<DataProvidersPage />} />
          <Route path="/products/provider-instruments" element={<ProviderInstrumentsPage />} />
          <Route path="/products/symbol-bindings" element={<SymbolDataBindingsPage />} />

          <Route path="/finance/ledger" element={<FeatureCrudPage pageKey="finance-ledger" />} />
          <Route path="/finance/recharge-orders" element={<FeatureCrudPage pageKey="recharge-orders" />} />
          <Route path="/finance/withdrawal-orders" element={<FeatureCrudPage pageKey="withdrawal-orders" />} />
          <Route path="/finance/payment-methods" element={<FeatureCrudPage pageKey="payment-methods" />} />

          <Route path="/members/list" element={<FeatureCrudPage pageKey="members" />} />
          <Route path="/members/payment-accounts" element={<FeatureCrudPage pageKey="member-payment-accounts" />} />
          <Route path="/orders/history" element={<FeatureCrudPage pageKey="order-history" />} />
          <Route path="/logs/verification-codes" element={<FeatureCrudPage pageKey="verification-codes" />} />
          <Route path="/logs/request-logs" element={<FeatureCrudPage pageKey="request-logs" />} />
          <Route path="/content/notices" element={<FeatureCrudPage pageKey="notices" />} />
          <Route path="/content/news" element={<FeatureCrudPage pageKey="news" />} />
          <Route path="/content/member-notices" element={<MemberNoticePage />} />
          <Route path="/content/member-notices/new" element={<MemberNoticeEditorPage />} />
          <Route path="/content/member-notices/:id/edit" element={<MemberNoticeEditorPage />} />
          <Route path="/content/popup-campaigns" element={<PopupCampaignPage />} />
          <Route path="/content/popup-campaigns/new" element={<PopupCampaignEditorPage />} />
          <Route path="/content/popup-campaigns/:id/edit" element={<PopupCampaignEditorPage />} />
          <Route path="/content/popup-campaigns/policy" element={<PopupCampaignPolicyPage />} />
          <Route path="/content/popup-campaigns/:id/stats" element={<PopupCampaignStatsPage />} />
          <Route path="/content/popup-campaigns/:id/users" element={<PopupCampaignStatsPage userDetail />} />
          <Route path="/config/settings/site" element={<FeatureCrudPage pageKey="settings-site" />} />
          <Route path="/config/settings/upload" element={<FeatureCrudPage pageKey="settings-upload" />} />
          <Route path="/config/settings/sms" element={<FeatureCrudPage pageKey="settings-sms" />} />
          <Route path="/config/settings/email" element={<FeatureCrudPage pageKey="settings-email" />} />
          <Route path="/config/settings/footer" element={<FeatureCrudPage pageKey="settings-footer" />} />

          <Route path="/users" element={<Navigate to="/system/users" replace />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          <Route path="/trading/orders" element={<OrdersPage />} />
          <Route path="/trading/positions" element={<PositionsPage />} />
          <Route path="/trading/trades" element={<TradesPage />} />
          <Route path="/trading/funding-settlements" element={<FundingSettlementsPage />} />
          <Route path="/trading/lab" element={<TradingLabRoute />} />
          <Route path="/legacy/finance/ledger" element={<LedgerPage />} />
          <Route path="/legacy/finance/payment-methods" element={<PaymentMethodsPage />} />
          <Route path="/market/symbols" element={<SymbolsPage />} />
          <Route path="/market/status" element={<MarketStatusPage />} />
          <Route path="/market/funding-config" element={<FundingConfigPage />} />
          <Route path="/risk" element={<RiskPage />} />
          <Route path="/content/messages" element={<Navigate to="/content/member-notices" replace />} />
          <Route path="/legacy/content/articles" element={<ArticlesPage />} />
          <Route path="/config/dictionaries" element={<DictionariesPage />} />
          <Route path="/legacy/config/settings" element={<SettingsPage />} />
          <Route path="/audit-logs" element={<AuditLogsPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}

function TradingLabRoute() {
  if (!hasAdminAuthority('TRADING_LAB_VIEW')) {
    return <Navigate to="/dashboard" replace />
  }
  return (
    <Suspense fallback={<div className="state-block loading">正在加载交易路径实验室...</div>}>
      <TradingLabPage />
    </Suspense>
  )
}
