import type { ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { LanguageSwitcher } from '../components/LanguageSwitcher'
import { TopbarToolIcon } from '../components/TopbarToolIcon'
import { useTheme } from '../design-system/theme/ThemeProvider'
import { authSessionChangedEvent, readStoredAuthToken } from '../features/trading-session/tradingSessionStorage'
import { getSessionStatus } from '../services/authApi'
import { AccountUserMenu } from './components/AccountUserMenu'
import { TradingNavMenu } from './components/TradingNavMenu'
import { authRoutes, authenticatedNavItems, guestNavItems, mobileNavItems, type AppNavItem } from './navigation'
import { resolveMobileTradingPath } from './tradingRoutes'

type AppShellProps = {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const isTerminalRoute = /^\/trade\/(spot|perpetual)(?:\/|$)/.test(location.pathname)
  const isAuthRoute = authRoutes.includes(location.pathname as (typeof authRoutes)[number])
  const { currentTheme, toggleTheme } = useTheme()
  const [session, setSession] = useState(() => ({
    authenticated: Boolean(readStoredAuthToken()),
    email: null as string | null
  }))

  useEffect(() => {
    let active = true
    const refreshSession = () => {
      const token = readStoredAuthToken()
      if (!token) {
        setSession({ authenticated: false, email: null })
        return
      }

      setSession((current) => ({ ...current, authenticated: true }))
      getSessionStatus(token)
        .then((status) => {
          if (!active || readStoredAuthToken() !== token) return
          setSession({ authenticated: status.authenticated, email: status.email })
        })
        .catch(() => {
          if (active && readStoredAuthToken() === token) setSession((current) => ({ ...current, authenticated: true }))
        })
    }

    refreshSession()
    window.addEventListener(authSessionChangedEvent, refreshSession)
    return () => {
      active = false
      window.removeEventListener(authSessionChangedEvent, refreshSession)
    }
  }, [location.pathname])

  const handleLogout = () => {
    setSession({ authenticated: false, email: null })
  }

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
                <AccountUserMenu email={session.email} onLogout={handleLogout} />
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
      className={({ isActive }) => `app-topbar__link${isActive || isConfiguredNavPathActive(item, pathname) ? ' active' : ''}`}
    >
      <span>{t(item.labelKey)}</span>
    </NavLink>
  )
}

function MobileNavLink({ item, pathname }: { item: AppNavItem; pathname: string }) {
  const { t } = useTranslation()
  const tradeClassName = item.to.startsWith('/trade/') ? ' mobile-tab--trade' : ''
  const target = item.to.startsWith('/trade/') ? resolveMobileTradingPath(pathname) : item.to

  return (
    <NavLink
      end={target === '/'}
      to={target}
      className={({ isActive }) => `mobile-tab${tradeClassName}${isActive || isConfiguredNavPathActive(item, pathname) ? ' active' : ''}`}
    >
      <item.icon size={19} aria-hidden="true" />
      <span>{t(item.labelKey)}</span>
    </NavLink>
  )
}

function isConfiguredNavPathActive(item: AppNavItem, pathname: string) {
  return item.activePaths?.some((path) => pathname === path || pathname.startsWith(`${path}/`)) ?? false
}
