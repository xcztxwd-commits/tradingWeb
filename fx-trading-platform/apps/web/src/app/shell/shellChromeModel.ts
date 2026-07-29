import type { AppNavItem } from '../navigation'
import type { MessageModel } from '@fx-platform/frontend-core'

export type ShellChromeModel = {
  authenticated: boolean
  email: string | null
  isAuthRoute: boolean
  unreadCount: number
  recentMessages: readonly MessageModel[]
  messageSummaryLoading: boolean
  navItems: AppNavItem[]
  pathname: string
  themeColorScheme: 'dark' | 'light'
  onLogout: () => void
  onToggleTheme: () => void
}
