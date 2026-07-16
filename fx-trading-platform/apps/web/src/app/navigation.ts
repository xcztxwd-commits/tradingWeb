import { BarChart3, Briefcase, Home, ListOrdered, UserRound, Wallet } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export type AppNavItem = {
  to: string
  labelKey: string
  icon: LucideIcon
  activePaths?: string[]
}

export const authRoutes = ['/login', '/register', '/forgot-password', '/two-factor-help'] as const

export const guestNavItems: AppNavItem[] = [
  { to: '/markets', labelKey: 'nav.markets', icon: Briefcase }
]

export const authenticatedNavItems: AppNavItem[] = [
  { to: '/markets', labelKey: 'nav.markets', icon: Briefcase },
  { to: '/account/assets', labelKey: 'nav.wallet', icon: Wallet, activePaths: ['/account/assets'] }
]

export const mobileNavItems: AppNavItem[] = [
  { to: '/', labelKey: 'nav.home', icon: Home },
  { to: '/markets', labelKey: 'nav.markets', icon: Briefcase },
  {
    to: '/trade/spot/BTCUSDT',
    labelKey: 'nav.trading',
    icon: BarChart3,
    activePaths: ['/trade/spot', '/trade/perpetual']
  },
  { to: '/account/orders/trades', labelKey: 'nav.orders', icon: ListOrdered, activePaths: ['/account/orders/trades'] },
  {
    to: '/account/overview',
    labelKey: 'nav.account',
    icon: UserRound,
    activePaths: [
      '/account',
      '/account/overview',
      '/account/assets',
      '/account/orders/funding',
      '/account/orders/trades',
      '/account/security/kyc',
      '/account/settings'
    ]
  }
]
