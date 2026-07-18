import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useTheme } from '@fx-platform/ui'

import {
  authSessionChangedEvent,
  getSessionStatus,
  readStoredAuthToken
} from '@fx-platform/frontend-core'
import { MobileShellChrome } from '../mobile/shell/MobileShellChrome'
import { PcShellChrome } from '../pc/shell/PcShellChrome'
import { PlatformView } from './platform/PlatformView'
import { authenticatedNavItems, guestNavItems } from './navigation'
import type { ShellChromeModel } from './shell/shellChromeModel'
import { getShellRouteFlags } from './shell/shellRouteModel'

type AppShellProps = {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const { isTerminalRoute, isAuthRoute } = getShellRouteFlags(location.pathname)
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
          if (active && readStoredAuthToken() === token) {
            setSession((current) => ({ ...current, authenticated: true }))
          }
        })
    }

    refreshSession()
    window.addEventListener(authSessionChangedEvent, refreshSession)
    return () => {
      active = false
      window.removeEventListener(authSessionChangedEvent, refreshSession)
    }
  }, [location.pathname])

  const handleLogout = useCallback(() => {
    setSession({ authenticated: false, email: null })
  }, [])
  const navItems = useMemo(
    () => session.authenticated ? authenticatedNavItems : guestNavItems,
    [session.authenticated]
  )
  const chromeModel: ShellChromeModel = {
    authenticated: session.authenticated,
    email: session.email,
    isAuthRoute,
    navItems,
    pathname: location.pathname,
    themeColorScheme: currentTheme.colorScheme,
    onLogout: handleLogout,
    onToggleTheme: toggleTheme
  }

  return (
    <div className={`app-shell${isTerminalRoute ? ' app-shell--terminal' : ''}${isAuthRoute ? ' app-shell--auth' : ''}`}>
      <PlatformView
        model={chromeModel}
        pc={PcShellChrome}
        mobile={MobileShellChrome}
        fallback={null}
      />
      <main className="main-region">{children}</main>
    </div>
  )
}
