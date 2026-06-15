import { Navigate, Route, Routes } from 'react-router-dom'

import { AdminLayout } from './AdminLayout'
import { RequireAdmin } from './RequireAdmin'
import { AccountsPage } from '../pages/AccountsPage'
import { ArticlesPage } from '../pages/ArticlesPage'
import { AuditLogsPage } from '../pages/AuditLogsPage'
import { DashboardPage } from '../pages/DashboardPage'
import { DictionariesPage } from '../pages/DictionariesPage'
import { FeatureCrudPage } from '../pages/FeatureCrudPage'
import { LedgerPage } from '../pages/LedgerPage'
import { LoginPage } from '../pages/LoginPage'
import { MarketStatusPage } from '../pages/MarketStatusPage'
import { MessagesPage } from '../pages/MessagesPage'
import { OrdersPage } from '../pages/OrdersPage'
import { PaymentMethodsPage } from '../pages/PaymentMethodsPage'
import { PositionsPage } from '../pages/PositionsPage'
import { RiskPage } from '../pages/RiskPage'
import { SettingsPage } from '../pages/SettingsPage'
import { SymbolsPage } from '../pages/SymbolsPage'
import { TradesPage } from '../pages/TradesPage'
import { UsersPage } from '../pages/UsersPage'

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
          <Route path="/content/member-notices" element={<FeatureCrudPage pageKey="member-notices" />} />
          <Route path="/config/settings/site" element={<FeatureCrudPage pageKey="settings-site" />} />
          <Route path="/config/settings/upload" element={<FeatureCrudPage pageKey="settings-upload" />} />
          <Route path="/config/settings/sms" element={<FeatureCrudPage pageKey="settings-sms" />} />
          <Route path="/config/settings/email" element={<FeatureCrudPage pageKey="settings-email" />} />
          <Route path="/config/settings/footer" element={<FeatureCrudPage pageKey="settings-footer" />} />

          <Route path="/users" element={<Navigate to="/system/users" replace />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/trading/orders" element={<OrdersPage />} />
          <Route path="/trading/positions" element={<PositionsPage />} />
          <Route path="/trading/trades" element={<TradesPage />} />
          <Route path="/legacy/finance/ledger" element={<LedgerPage />} />
          <Route path="/legacy/finance/payment-methods" element={<PaymentMethodsPage />} />
          <Route path="/market/symbols" element={<SymbolsPage />} />
          <Route path="/market/status" element={<MarketStatusPage />} />
          <Route path="/risk" element={<RiskPage />} />
          <Route path="/content/messages" element={<MessagesPage />} />
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
