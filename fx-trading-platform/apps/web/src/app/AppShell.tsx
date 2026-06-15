import type { ReactNode } from 'react'
import { ArrowDownToLine, Bell, ChevronDown, CircleUserRound, CreditCard, Download, Globe2, MessageSquare, Moon, Search, WalletCards } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { LanguageSwitcher } from '../components/LanguageSwitcher'
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
  const isMarketsRoute = location.pathname === '/markets'
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
      <header className={`app-topbar${isMarketsRoute ? ' app-topbar--reference' : ''}`}>
        <Link className={`app-brand${isMarketsRoute ? ' app-brand--reference' : ''}`} to="/" aria-label={isMarketsRoute ? '币安 首页' : 'FX Trader 首页'}>
          <span className="app-brand__mark">{isMarketsRoute ? null : 'FX'}</span>
          <span>{isMarketsRoute ? '币安' : 'FX Trader'}</span>
        </Link>

        <nav className="app-topbar__nav" aria-label="Primary navigation">
          {isMarketsRoute ? (
            <MarketsReferenceNav />
          ) : (
            <>
              {navItems.map((item) => (
                <TopNavLink key={item.to} item={item} pathname={location.pathname} />
              ))}
              <TradingNavMenu />
            </>
          )}
        </nav>

        <div className="app-topbar__actions">
          {isMarketsRoute ? (
            <MarketsReferenceActions />
          ) : (
            <>
              <div className="app-topbar__utility-cluster" aria-label="Quick tools">
                <button type="button" className="app-topbar__icon" aria-label="Search">
                  <Search size={18} aria-hidden="true" />
                </button>
                {session.authenticated ? (
                  <Link className="app-topbar__icon" to="/wallet" aria-label="Wallet">
                    <WalletCards size={18} aria-hidden="true" />
                  </Link>
                ) : null}
                <button type="button" className="app-topbar__icon" aria-label="Notifications">
                  <Bell size={18} aria-hidden="true" />
                </button>
                <button type="button" className="app-topbar__icon" aria-label="Download">
                  <Download size={18} aria-hidden="true" />
                </button>
                <LanguageSwitcher compact />
                <button type="button" className="app-topbar__icon app-topbar__theme" aria-label="Switch theme">
                  <Moon size={17} aria-hidden="true" />
                </button>
              </div>
              <LanguageSwitcher compact />
              <button type="button" className="app-topbar__ghost app-topbar__theme" aria-label="切换主题">
                主题
              </button>
              {session.authenticated ? (
                <AccountUserMenu email={session.email} />
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
            </>
          )}
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

function MarketsReferenceNav() {
  return (
    <div className="market-reference-nav" aria-label="Binance-style market navigation">
      <Link className="market-reference-nav__link" to="/">
        一键买币
      </Link>
      <Link className="market-reference-nav__link" to="/markets">
        行情
      </Link>
      <Link className="market-reference-nav__link market-reference-nav__link--hot" to="/trading">
        交易
        <ChevronDown className="market-reference-nav__chevron" size={12} aria-hidden="true" />
      </Link>
      <Link className="market-reference-nav__link" to="/trading">
        合约
        <ChevronDown className="market-reference-nav__chevron" size={12} aria-hidden="true" />
      </Link>
      <Link className="market-reference-nav__link" to="/account/assets">
        理财
        <ChevronDown className="market-reference-nav__chevron" size={12} aria-hidden="true" />
      </Link>
      <Link className="market-reference-nav__link" to="/">
        广场
        <ChevronDown className="market-reference-nav__chevron" size={12} aria-hidden="true" />
      </Link>
      <Link className="market-reference-nav__link" to="/">
        更多
        <ChevronDown className="market-reference-nav__chevron" size={12} aria-hidden="true" />
      </Link>
    </div>
  )
}

function MarketsReferenceActions() {
  return (
    <div className="market-reference-actions" aria-label="Markets quick tools">
      <button type="button" className="app-topbar__icon" aria-label="Search">
        <Search size={22} aria-hidden="true" />
      </button>
      <Link className="app-topbar__primary market-reference-actions__deposit" to="/account/assets">
        <ArrowDownToLine size={14} aria-hidden="true" />
        充值
      </Link>
      <Link className="app-topbar__icon" to="/account/overview" aria-label="Account">
        <CircleUserRound size={21} aria-hidden="true" />
      </Link>
      <Link className="app-topbar__icon" to="/account/assets" aria-label="Wallet">
        <CreditCard size={21} aria-hidden="true" />
      </Link>
      <button type="button" className="app-topbar__icon" aria-label="Messages">
        <MessageSquare size={21} aria-hidden="true" />
      </button>
      <button type="button" className="app-topbar__icon" aria-label="Download">
        <Download size={21} aria-hidden="true" />
      </button>
      <button type="button" className="app-topbar__icon" aria-label="Language">
        <Globe2 size={21} aria-hidden="true" />
      </button>
      <button type="button" className="app-topbar__icon app-topbar__theme" aria-label="Switch theme">
        <Moon size={21} aria-hidden="true" />
      </button>
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
