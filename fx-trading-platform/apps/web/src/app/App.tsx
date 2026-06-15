import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { ExchangeLoading } from '../components/loading/ExchangeLoading'
import { AppShell } from './AppShell'

const HomePage = lazy(() => import('../pages/home/HomePage').then((module) => ({ default: module.HomePage })))
const TradingPage = lazy(() => import('../pages/trading/TradingPage').then((module) => ({ default: module.TradingPage })))
const LoginPage = lazy(() => import('../pages/login/LoginPage').then((module) => ({ default: module.LoginPage })))
const RegisterPage = lazy(() => import('../pages/login/AuthSupportPage').then((module) => ({ default: module.RegisterPage })))
const ForgotPasswordPage = lazy(() => import('../pages/login/AuthSupportPage').then((module) => ({ default: module.ForgotPasswordPage })))
const TwoFactorHelpPage = lazy(() => import('../pages/login/AuthSupportPage').then((module) => ({ default: module.TwoFactorHelpPage })))
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
          <Route path="/" element={<HomePage />} />
          <Route path="/trade" element={<Navigate to="/trading" replace />} />
          <Route path="/trading" element={<TradingPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/two-factor-help" element={<TwoFactorHelpPage />} />
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
