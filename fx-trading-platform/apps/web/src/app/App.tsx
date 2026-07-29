import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { ExchangeLoading } from '../components/loading/ExchangeLoading'
import { AccountRoute } from '../routes/account/AccountRoutes'
import { AuthRoute } from '../routes/auth/AuthRoute'
import { HomeRoute } from '../routes/home/HomeRoute'
import { MarketsRoute } from '../routes/markets/MarketsRoute'
import { MessagesRoute } from '../routes/messages/MessagesRoute'
import { OrdersRoute } from '../routes/orders/OrdersRoute'
import { PositionsRoute } from '../routes/positions/PositionsRoute'
import { WalletRoute } from '../routes/wallet/WalletRoute'
import { AppShell } from './AppShell'
import { LegacyTradingRedirect } from './LegacyTradingRedirect'
import { resolveSafeTradingPath } from './tradingRoutes'

const TradingRoute = lazy(() =>
  import('../routes/trading/TradingRoute').then((module) => ({ default: module.TradingRoute }))
)

export function App() {
  return (
    <AppShell>
      <Suspense fallback={<ExchangeLoading />}>
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route path="/trade" element={<LegacyTradingRedirect />} />
          <Route path="/trading" element={<LegacyTradingRedirect />} />
          <Route path="/trade/spot/:symbol?" element={<TradingRoute product="spot" />} />
          <Route path="/trade/perpetual/:symbol?" element={<TradingRoute product="perpetual" />} />
          <Route path="/trade/:product/:symbol?" element={<Navigate to={resolveSafeTradingPath(null)} replace />} />
          <Route path="/login" element={<AuthRoute mode="login" />} />
          <Route path="/register" element={<AuthRoute mode="register" />} />
          <Route path="/forgot-password" element={<AuthRoute mode="forgot-password" />} />
          <Route path="/two-factor-help" element={<AuthRoute mode="two-factor-help" />} />
          <Route path="/dashboard" element={<AccountRoute mode="dashboard" />} />
          <Route path="/markets" element={<MarketsRoute />} />
          <Route path="/messages" element={<MessagesRoute />} />
          <Route path="/orders" element={<OrdersRoute />} />
          <Route path="/positions" element={<PositionsRoute />} />
          <Route path="/wallet" element={<WalletRoute />} />
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
