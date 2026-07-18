import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'

import {
  isConfiguredNavPathActive,
  mobileNavItems,
  type AppNavItem
} from '../../app/navigation'
import { resolveMobileTradingPath } from '../../app/tradingRoutes'
import type { ShellChromeModel } from '../../app/shell/shellChromeModel'
import styles from './MobileShellChrome.module.css'

export function MobileShellChrome({ model }: { model: ShellChromeModel }) {
  if (model.isAuthRoute) return null

  return (
    <nav className={`${styles.root} mobile-tabs`} aria-label="Mobile navigation" data-platform-view="mobile">
      {mobileNavItems.map((item) => (
        <MobileNavLink key={item.to} item={item} pathname={model.pathname} />
      ))}
    </nav>
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
