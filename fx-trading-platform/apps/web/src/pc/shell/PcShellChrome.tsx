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
    <header className={styles.root} data-platform-view="pc">
      <Link className={styles.brand} to="/" aria-label="FX Trader 首页">
        <span className={styles.brandMark}>FX</span>
        <span>FX Trader</span>
      </Link>

      <nav className={styles.nav} aria-label="Primary navigation">
        {model.navItems.map((item) => (
          <TopNavLink key={item.to} item={item} pathname={model.pathname} />
        ))}
        <TradingNavMenu triggerClassName={styles.link} />
      </nav>

      <div className={styles.actions}>
        <div className={styles.utilityCluster} aria-label="Quick tools">
          <button type="button" className={styles.icon} aria-label="Search">
            <TopbarToolIcon name="search" />
          </button>
          {model.authenticated ? (
            <>
              <AccountUserMenu email={model.email} onLogout={model.onLogout} triggerClassName={styles.icon} />
              <Link className={styles.icon} to="/wallet" aria-label="Wallet">
                <TopbarToolIcon name="wallet" />
              </Link>
            </>
          ) : (
            <>
              <Link className={styles.ghost} to="/login">登录</Link>
              <Link className={styles.primary} to="/register">注册</Link>
            </>
          )}
          <button type="button" className={styles.icon} aria-label="Notifications">
            <TopbarToolIcon name="bell" />
          </button>
          <button type="button" className={styles.icon} aria-label="Customer support">
            <TopbarToolIcon name="support" />
          </button>
          <button type="button" className={styles.icon} aria-label="Download">
            <TopbarToolIcon name="download" />
          </button>
          <LanguageSwitcher compact />
          <button
            type="button"
            className={styles.icon}
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
      className={({ isActive }) => [styles.link, (isActive || isConfiguredNavPathActive(item, pathname)) && styles.active].filter(Boolean).join(' ')}
    >
      <span>{t(item.labelKey)}</span>
    </NavLink>
  )
}
