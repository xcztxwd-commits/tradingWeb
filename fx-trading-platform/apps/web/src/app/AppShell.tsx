import { useCallback, useEffect, useLayoutEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  getCriticalDialogOpen,
  subscribeDialogOverlay,
  useTheme
} from '@fx-platform/ui'

import {
  authSessionChangedEvent,
  clearStoredAuthToken,
  createEngagementApiClient,
  engagementUpdateSubscription,
  getSessionStatus,
  readStoredAuthToken
} from '@fx-platform/frontend-core'
import { createAppEngagementRuntime } from '../engagement/appEngagementRuntime'
import { resolveEngagementPageKey } from '../engagement/engagementNavigation'
import { MobileShellChrome } from '../mobile/shell/MobileShellChrome'
import { PcShellChrome } from '../pc/shell/PcShellChrome'
import { MessageCenterRuntimeProvider } from '../routes/messages/MessageCenterRuntime'
import { EngagementPopup } from '../shared-widgets/engagement/EngagementPopup'
import { useDeviceClass } from './device/DeviceClassProvider'
import { PlatformView } from './platform/PlatformView'
import { authenticatedNavItems, guestNavItems } from './navigation'
import type { ShellChromeModel } from './shell/shellChromeModel'
import { getShellRouteFlags } from './shell/shellRouteModel'
import styles from './AppShell.module.css'

type AppShellProps = {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const deviceClass = useDeviceClass()
  const { isTerminalRoute, isAuthRoute } = getShellRouteFlags(location.pathname)
  const { currentTheme, toggleTheme } = useTheme()
  const [accessToken, setAccessToken] = useState(() => readStoredAuthToken())
  const [session, setSession] = useState(() => ({
    authenticated: Boolean(accessToken),
    email: null as string | null
  }))
  const [engagement] = useState(() => createAppEngagementRuntime({
    api: createEngagementApiClient(),
    updates: engagementUpdateSubscription,
    onUnauthorized: () => {
      clearStoredAuthToken()
      setAccessToken(null)
      setSession({ authenticated: false, email: null })
    }
  }))
  const popupSnapshot = useSyncExternalStore(
    engagement.popup.subscribe,
    engagement.popup.getSnapshot,
    engagement.popup.getSnapshot
  )
  const messageSnapshot = useSyncExternalStore(
    engagement.messages.subscribe,
    engagement.messages.getSnapshot,
    engagement.messages.getSnapshot
  )
  const criticalDialogOpen = useSyncExternalStore(
    subscribeDialogOverlay,
    getCriticalDialogOpen,
    () => false
  )
  const pageKey = resolveEngagementPageKey(location.pathname)
  const engagementEnabled = Boolean(
    accessToken && session.authenticated && pageKey && !isAuthRoute
  )
  const engagementToken = engagementEnabled ? accessToken : null

  useEffect(() => {
    let active = true
    const refreshSession = () => {
      const token = readStoredAuthToken()
      setAccessToken(token)
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
  }, [])

  useLayoutEffect(() => {
    void engagement.criticalModalChanged(engagementEnabled && getCriticalDialogOpen())
  }, [criticalDialogOpen, engagement, engagementEnabled])

  useEffect(() => {
    if (!engagementToken || !pageKey) {
      engagement.logout()
      return undefined
    }
    return engagement.connect({
      accessToken: engagementToken,
      pageKey,
      deviceClass: deviceClass === 'pc' ? 'PC' : 'MOBILE'
    })
    // Route and device changes have independent effects below; this lease follows authentication only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engagement, engagementToken])

  useEffect(() => {
    if (engagementToken && pageKey) void engagement.routeChanged(pageKey)
  }, [engagement, engagementToken, pageKey])

  useEffect(() => {
    if (engagementToken) engagement.resize(deviceClass === 'pc' ? 'PC' : 'MOBILE')
  }, [deviceClass, engagement, engagementToken])

  useEffect(() => {
    if (!engagementToken) return undefined
    const handleFocus = () => { void engagement.windowFocused() }
    window.addEventListener('focus', handleFocus)
    return () => window.removeEventListener('focus', handleFocus)
  }, [engagement, engagementToken])

  const handleLogout = useCallback(() => {
    setAccessToken(null)
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
    unreadCount: messageSnapshot.unreadCount,
    recentMessages: messageSnapshot.recent,
    messageSummaryLoading: messageSnapshot.summaryLoading,
    navItems,
    pathname: location.pathname,
    themeColorScheme: currentTheme.colorScheme,
    onLogout: handleLogout,
    onToggleTheme: toggleTheme
  }

  return (
    <MessageCenterRuntimeProvider
      runtime={engagement.messages}
      authenticated={session.authenticated}
    >
      <div className={[styles.root, isTerminalRoute && styles.terminal, isAuthRoute && styles.auth].filter(Boolean).join(' ')}>
        <PlatformView
          model={chromeModel}
          pc={PcShellChrome}
          mobile={MobileShellChrome}
          fallback={null}
        />
        <main className={styles.mainRegion}>{children}</main>
        {engagementEnabled && <EngagementPopup
          snapshot={popupSnapshot}
          actions={engagement.popup}
          labels={{ close: 'Close', optOut: "Don't show again" }}
          navigate={navigate}
        />}
      </div>
    </MessageCenterRuntimeProvider>
  )
}
