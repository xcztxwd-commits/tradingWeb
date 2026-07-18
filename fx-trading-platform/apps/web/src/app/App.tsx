import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { ExchangeLoading } from '../components/loading/ExchangeLoading'
import { AccountRoute } from '../routes/account/AccountRoutes'
import { AuthRoute } from '../routes/auth/AuthRoute'
import { HomeRoute } from '../routes/home/HomeRoute'
import { AppShell } from './AppShell'
import { LegacyTradingRedirect } from './LegacyTradingRedirect'
import { resolveSafeTradingPath } from './tradingRoutes'

const TradingPage = lazy(() => import('../pages/trading/TradingPage').then((module) => ({ default: module.TradingPage })))
const OrdersPage = lazy(() => import('../pages/orders/OrdersPage').then((module) => ({ default: module.OrdersPage })))
const PositionsPage = lazy(() => import('../pages/positions/PositionsPage').then((module) => ({ default: module.PositionsPage })))
const MarketsPage = lazy(() => import('../pages/markets/MarketsPage').then((module) => ({ default: module.MarketsPage })))
const WalletPage = lazy(() => import('../pages/wallet/WalletPage').then((module) => ({ default: module.WalletPage })))

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
          <Route path="/dashboard" element={<AccountRoute mode="dashboard" />} />
          <Route path="/markets" element={<MarketsPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/positions" element={<PositionsPage />} />
          <Route path="/wallet" element={<WalletPage />} />
          <Route path="/account" element={<Navigate to="/account/overview" replace />} />
          <Route path="/account/overview" element={<AccountRoute mode="overview" />} />
          <Route path="/account/assets" element={<AccountRoute mode="assets" />} />
          <Route path="/account/orders/funding" element={<AccountRoute mode="funding-records" />} />
          <Route path="/account/orders/trades" element={<AccountRoute mode="trade-records" />} />
          <Route path="/account/security/kyc" element={<AccountRoute mode="kyc" />} />
          <Route path="/account/settings" element={<AccountRoute mode="account-settings" />} />
          <Route path="/security" element={<AccountRoute mode="security" />} />
          <Route path="/settings" element={<AccountRoute mode="settings" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AppShell>
  )
}
