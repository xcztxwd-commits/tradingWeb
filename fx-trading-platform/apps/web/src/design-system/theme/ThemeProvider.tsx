import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { defaultThemeId, getTradingTheme, isTradingThemeId, type TradingTheme, type TradingThemeId, type TradingThemeTokens } from './themes'

export const themeStorageKey = 'fx-ui-theme'

type ThemeContextValue = {
  themeId: TradingThemeId
  currentTheme: TradingTheme
  setThemeId: (themeId: TradingThemeId) => void
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

export function loadThemeId(): TradingThemeId {
  if (typeof window === 'undefined') return defaultThemeId

  try {
    const storedThemeId = window.localStorage.getItem(themeStorageKey)
    return isTradingThemeId(storedThemeId) ? storedThemeId : defaultThemeId
  } catch {
    return defaultThemeId
  }
}

export function saveThemeId(themeId: TradingThemeId) {
  try {
    window.localStorage.setItem(themeStorageKey, themeId)
  } catch {
    // Theme changes must keep working in restricted storage contexts.
  }
}

export function applyThemeToDocument(theme: TradingTheme) {
  if (typeof document === 'undefined') return

  document.documentElement.dataset.theme = theme.id
  document.documentElement.dataset.colorScheme = theme.colorScheme
  document.documentElement.style.colorScheme = theme.colorScheme

  for (const [tokenName, cssVariableName] of Object.entries(tokenCssVariableNames) as Array<[keyof TradingThemeTokens, string]>) {
    document.documentElement.style.setProperty(cssVariableName, theme.tokens[tokenName])
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [themeId, setThemeIdState] = useState<TradingThemeId>(() => loadThemeId())
  const currentTheme = useMemo(() => getTradingTheme(themeId), [themeId])

  useEffect(() => {
    applyThemeToDocument(currentTheme)
    saveThemeId(themeId)
  }, [currentTheme, themeId])

  const value = useMemo<ThemeContextValue>(
    () => ({
      themeId,
      currentTheme,
      setThemeId: setThemeIdState
    }),
    [currentTheme, themeId]
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used inside ThemeProvider')
  return context
}
