import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { ExchangeLoading } from '../components/loading/ExchangeLoading'
import { AuthRoute } from '../routes/auth/AuthRoute'
import { HomeRoute } from '../routes/home/HomeRoute'
import { AppShell } from './AppShell'
import { LegacyTradingRedirect } from './LegacyTradingRedirect'
import { resolveSafeTradingPath } from './tradingRoutes'

const TradingPage = lazy(() => import('../pages/trading/TradingPage').then((module) => ({ default: module.TradingPage })))
const SettingsPage = lazy(() => import('../pages/settings/SettingsPage').then((module) => ({ default: module.SettingsPage })))
const SecurityCenterPage = lazy(() => import('../pages/security/SecurityCenterPage').then((module) => ({ default: module.SecurityCenterPage })))
const OrdersPage = lazy(() => import('../pages/orders/OrdersPage').then((module) => ({ default: module.OrdersPage })))
const PositionsPage = lazy(() => import('../pages/positions/PositionsPage').then((module) => ({ default: module.PositionsPage })))
const DashboardPage = lazy(() => import('../pages/dashboard/DashboardPage').then((module) => ({ default: module.DashboardPage })))
const MarketsPage = lazy(() => import('../pages/markets/MarketsPage').then((module) => ({ default: module.MarketsPage })))
const WalletPage = lazy(() => import('../pages/wallet/WalletPage').then((module) => ({ default: module.WalletPage })))
const AccountOverviewPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.AccountOverviewPage })))
const AccountAssetsPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.AccountAssetsPage })))
const FundingRecordsPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.FundingRecordsPage })))
const TradeOrdersPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.TradeOrdersPage })))
const KycPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.KycPage })))
const AccountSettingsPage = lazy(() => import('../pages/account/AccountPages').then((module) => ({ default: module.AccountSettingsPage })))

export function App() {
  return (
    <AppShell>
      <Suspense fallback={<ExchangeLoading />}>
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route path="/trade" element={<LegacyTradingRedirect />} />
          <Route path="/trading" element={<LegacyTradingRedirect />} />
          <Route path="/trade/spot/:symbol?" element={<TradingPage product="spot" />} />
          <Route path="/trade/perpetual/:symbol?" element={<TradingPage product="perpetual" />} />
          <Route path="/trade/:product/:symbol?" element={<Navigate to={resolveSafeTradingPath(null)} replace />} />
          <Route path="/login" element={<AuthRoute mode="login" />} />
          <Route path="/register" element={<AuthRoute mode="register" />} />
          <Route path="/forgot-password" element={<AuthRoute mode="forgot-password" />} />
          <Route path="/two-factor-help" element={<AuthRoute mode="two-factor-help" />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/markets" element={<MarketsPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/positions" element={<PositionsPage />} />
          <Route path="/wallet" element={<WalletPage />} />
          <Route path="/account" element={<Navigate to="/account/overview" replace />} />
          <Route path="/account/overview" element={<AccountOverviewPage />} />
          <Route path="/account/assets" element={<AccountAssetsPage />} />
          <Route path="/account/orders/funding" element={<FundingRecordsPage />} />
          <Route path="/account/orders/trades" element={<TradeOrdersPage />} />
          <Route path="/account/security/kyc" element={<KycPage />} />
          <Route path="/account/settings" element={<AccountSettingsPage />} />
          <Route path="/security" element={<SecurityCenterPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AppShell>
  )
}
