import type { ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { LanguageSwitcher } from '../components/LanguageSwitcher'
import { TopbarToolIcon } from '../components/TopbarToolIcon'
import { useTheme } from '../design-system/theme/ThemeProvider'
import { readStoredAuthToken } from '../features/trading-session/tradingSessionStorage'
import { getSessionStatus } from '../services/authApi'
import { AccountUserMenu } from './components/AccountUserMenu'
import { TradingNavMenu } from './components/TradingNavMenu'
import { authRoutes, authenticatedNavItems, guestNavItems, mobileNavItems, type AppNavItem } from './navigation'

type AppShellProps = {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const isTerminalRoute = location.pathname === '/trading'
  const isAuthRoute = authRoutes.includes(location.pathname as (typeof authRoutes)[number])
  const { currentTheme, toggleTheme } = useTheme()
  const [session, setSession] = useState(() => ({
    authenticated: Boolean(readStoredAuthToken()),
    email: null as string | null
  }))

  useEffect(() => {
    const token = readStoredAuthToken()
    if (!token) {
      setSession({ authenticated: false, email: null })
      return
    }

    let active = true
    setSession((current) => ({ ...current, authenticated: true }))
    getSessionStatus(token)
      .then((status) => {
        if (!active) return
        setSession({ authenticated: status.authenticated, email: status.email })
      })
      .catch(() => {
        if (active) setSession((current) => ({ ...current, authenticated: true }))
      })
    return () => {
      active = false
    }
  }, [location.pathname])

  const navItems = useMemo(() => (session.authenticated ? authenticatedNavItems : guestNavItems), [session.authenticated])

  return (
    <div className={`app-shell${isTerminalRoute ? ' app-shell--terminal' : ''}${isAuthRoute ? ' app-shell--auth' : ''}`}>
      <header className="app-topbar">
        <Link className="app-brand" to="/" aria-label="FX Trader 首页">
          <span className="app-brand__mark">FX</span>
          <span>FX Trader</span>
        </Link>

        <nav className="app-topbar__nav" aria-label="Primary navigation">
          {navItems.map((item) => (
            <TopNavLink key={item.to} item={item} pathname={location.pathname} />
          ))}
          <TradingNavMenu />
        </nav>

        <div className="app-topbar__actions">
          <div className="app-topbar__utility-cluster" aria-label="Quick tools">
            <button type="button" className="app-topbar__icon" aria-label="Search">
              <TopbarToolIcon name="search" />
            </button>
            {session.authenticated ? (
              <>
                <AccountUserMenu email={session.email} />
                <Link className="app-topbar__icon" to="/wallet" aria-label="Wallet">
                  <TopbarToolIcon name="wallet" />
                </Link>
              </>
            ) : (
              <>
                <Link className="app-topbar__ghost" to="/login">
                  登录
                </Link>
                <Link className="app-topbar__primary" to="/register">
                  注册
                </Link>
              </>
            )}
            <button type="button" className="app-topbar__icon" aria-label="Notifications">
              <TopbarToolIcon name="bell" />
            </button>
            <button type="button" className="app-topbar__icon" aria-label="Customer support">
              <TopbarToolIcon name="support" />
            </button>
            <button type="button" className="app-topbar__icon" aria-label="Download">
              <TopbarToolIcon name="download" />
            </button>
            <LanguageSwitcher compact />
            <button
              type="button"
              className="app-topbar__icon app-topbar__theme"
              aria-label="Switch theme"
              aria-pressed={currentTheme.colorScheme === 'light'}
              title={currentTheme.colorScheme === 'light' ? 'Switch to dark style' : 'Switch to minimal white style'}
              onClick={toggleTheme}
            >
              <TopbarToolIcon name="moon" />
            </button>
          </div>
        </div>
      </header>

      <main className="main-region">{children}</main>

      <nav className="mobile-tabs" aria-label="Mobile navigation">
        {mobileNavItems.map((item) => (
          <MobileNavLink key={item.to} item={item} pathname={location.pathname} />
        ))}
      </nav>
    </div>
  )
}

function TopNavLink({ item, pathname }: { item: AppNavItem; pathname: string }) {
  const { t } = useTranslation()

  return (
    <NavLink
      end={item.to === '/'}
      to={item.to}
      className={({ isActive }) => `app-topbar__link${isActive || item.activePaths?.includes(pathname) ? ' active' : ''}`}
    >
      <span>{t(item.labelKey)}</span>
    </NavLink>
  )
}

function MobileNavLink({ item, pathname }: { item: AppNavItem; pathname: string }) {
  const { t } = useTranslation()
  const tradeClassName = item.to === '/trading' ? ' mobile-tab--trade' : ''

  return (
    <NavLink
      end={item.to === '/'}
      to={item.to}
      className={({ isActive }) => `mobile-tab${tradeClassName}${isActive || item.activePaths?.includes(pathname) ? ' active' : ''}`}
    >
      <item.icon size={19} aria-hidden="true" />
      <span>{t(item.labelKey)}</span>
    </NavLink>
  )
}
