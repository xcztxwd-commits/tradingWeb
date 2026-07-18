import type { AppNavItem } from '../navigation'

export type ShellChromeModel = {
  authenticated: boolean
  email: string | null
  isAuthRoute: boolean
  navItems: AppNavItem[]
  pathname: string
  themeColorScheme: 'dark' | 'light'
  onLogout: () => void
  onToggleTheme: () => void
}
