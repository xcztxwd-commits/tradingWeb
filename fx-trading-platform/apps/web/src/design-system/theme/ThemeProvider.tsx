import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { defaultThemeId, getTradingTheme, type TradingTheme, type TradingThemeId, type TradingThemeTokens } from './themes'

type ThemeContextValue = {
  currentTheme: TradingTheme
  setThemeId: (themeId: TradingThemeId) => void
  toggleTheme: () => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

const tokenCssVariableNames: Record<keyof TradingThemeTokens, string> = {
  background: '--theme-background',
  surface: '--theme-surface',
  surfaceElevated: '--theme-surface-elevated',
  border: '--theme-border',
  textPrimary: '--theme-text-primary',
  textSecondary: '--theme-text-secondary',
  textMuted: '--theme-text-muted',
  primary: '--theme-primary',
  primaryHover: '--theme-primary-hover',
  accent: '--theme-accent',
  success: '--theme-success',
  buy: '--theme-buy',
  danger: '--theme-danger',
  sell: '--theme-sell',
  warning: '--theme-warning',
  chartGrid: '--theme-chart-grid',
  chartCandleUp: '--theme-chart-candle-up',
  chartCandleDown: '--theme-chart-candle-down',
  orderBookBidBg: '--theme-order-book-bid-bg',
  orderBookAskBg: '--theme-order-book-ask-bg'
}

const themeStorageKey = 'fx-trading-theme-mode'

export function applyThemeToDocument(theme: TradingTheme) {
  if (typeof document === 'undefined') return

  document.documentElement.dataset.theme = theme.id
  document.documentElement.dataset.colorScheme = theme.colorScheme
  document.documentElement.style.colorScheme = theme.colorScheme

  for (const [tokenName, cssVariableName] of Object.entries(tokenCssVariableNames) as Array<[keyof TradingThemeTokens, string]>) {
    document.documentElement.style.setProperty(cssVariableName, theme.tokens[tokenName])
  }
}

function loadStoredThemeId(): TradingThemeId {
  if (typeof window === 'undefined') return defaultThemeId

  try {
    const storedThemeId = window.localStorage.getItem(themeStorageKey)
    return storedThemeId === 'minimal-white' ? storedThemeId : defaultThemeId
  } catch {
    return defaultThemeId
  }
}

function saveThemeId(themeId: TradingThemeId) {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(themeStorageKey, themeId)
  } catch {
    // Theme persistence is optional; document tokens still update for this session.
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [themeId, setThemeIdState] = useState<TradingThemeId>(() => loadStoredThemeId())
  const currentTheme = useMemo(() => getTradingTheme(themeId), [themeId])

  useEffect(() => {
    applyThemeToDocument(currentTheme)
  }, [currentTheme])

  const setThemeId = useCallback((nextThemeId: TradingThemeId) => {
    setThemeIdState(nextThemeId)
    saveThemeId(nextThemeId)
  }, [])

  const toggleTheme = useCallback(() => {
    setThemeId(currentTheme.colorScheme === 'light' ? defaultThemeId : 'minimal-white')
  }, [currentTheme.colorScheme, setThemeId])

  const value = useMemo<ThemeContextValue>(
    () => ({
      currentTheme,
      setThemeId,
      toggleTheme
    }),
    [currentTheme, setThemeId, toggleTheme]
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used inside ThemeProvider')
  return context
}
