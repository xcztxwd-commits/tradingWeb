import { useTranslation } from 'react-i18next'
import { Link, NavLink } from 'react-router-dom'

import { LanguageSwitcher } from '../../components/LanguageSwitcher'
import { TopbarToolIcon } from '../../components/TopbarToolIcon'
import { AccountUserMenu } from '../../app/components/AccountUserMenu'
import { TradingNavMenu } from '../../app/components/TradingNavMenu'
import { isConfiguredNavPathActive, type AppNavItem } from '../../app/navigation'
import type { ShellChromeModel } from '../../app/shell/shellChromeModel'
import styles from './PcShellChrome.module.css'

export function PcShellChrome({ model }: { model: ShellChromeModel }) {
  if (model.isAuthRoute) return null

  return (
    <header className={`${styles.root} app-topbar`} data-platform-view="pc">
      <Link className="app-brand" to="/" aria-label="FX Trader 首页">
        <span className="app-brand__mark">FX</span>
        <span>FX Trader</span>
      </Link>

      <nav className="app-topbar__nav" aria-label="Primary navigation">
        {model.navItems.map((item) => (
          <TopNavLink key={item.to} item={item} pathname={model.pathname} />
        ))}
        <TradingNavMenu />
      </nav>

      <div className="app-topbar__actions">
        <div className="app-topbar__utility-cluster" aria-label="Quick tools">
          <button type="button" className="app-topbar__icon" aria-label="Search">
            <TopbarToolIcon name="search" />
          </button>
          {model.authenticated ? (
            <>
              <AccountUserMenu email={model.email} onLogout={model.onLogout} />
              <Link className="app-topbar__icon" to="/wallet" aria-label="Wallet">
                <TopbarToolIcon name="wallet" />
              </Link>
            </>
          ) : (
            <>
              <Link className="app-topbar__ghost" to="/login">登录</Link>
              <Link className="app-topbar__primary" to="/register">注册</Link>
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
            aria-pressed={model.themeColorScheme === 'light'}
            title={model.themeColorScheme === 'light' ? 'Switch to dark style' : 'Switch to minimal white style'}
            onClick={model.onToggleTheme}
          >
            <TopbarToolIcon name="moon" />
          </button>
        </div>
      </div>
    </header>
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
