import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const providerSource = readFileSync(resolve(currentDir, 'ThemeProvider.tsx'), 'utf8')
const themeIndexSource = readFileSync(resolve(currentDir, 'index.ts'), 'utf8')
const rootIndexSource = readFileSync(resolve(currentDir, '../index.ts'), 'utf8')

describe('ThemeProvider package contract', () => {
  it('preserves storage fallback, document datasets and every CSS variable write', () => {
    assert.match(providerSource, /const themeStorageKey = 'fx-trading-theme-mode'/u)
    assert.match(providerSource, /window\.localStorage\.getItem\(themeStorageKey\)/u)
    assert.match(providerSource, /storedThemeId === 'minimal-white' \? storedThemeId : defaultThemeId/u)
    assert.match(providerSource, /window\.localStorage\.setItem\(themeStorageKey,\s*themeId\)/u)
    assert.match(providerSource, /document\.documentElement\.dataset\.theme = theme\.id/u)
    assert.match(providerSource, /document\.documentElement\.dataset\.colorScheme = theme\.colorScheme/u)
    assert.match(providerSource, /document\.documentElement\.style\.colorScheme = theme\.colorScheme/u)
    assert.match(providerSource, /document\.documentElement\.style\.setProperty\(cssVariableName, theme\.tokens\[tokenName\]\)/u)
    for (const variable of [
      '--theme-background',
      '--theme-surface',
      '--theme-surface-elevated',
      '--theme-border',
      '--theme-text-primary',
      '--theme-text-secondary',
      '--theme-text-muted',
      '--theme-primary',
      '--theme-primary-hover',
      '--theme-accent',
      '--theme-success',
      '--theme-buy',
      '--theme-danger',
      '--theme-sell',
      '--theme-warning',
      '--theme-chart-grid',
      '--theme-chart-candle-up',
      '--theme-chart-candle-down',
      '--theme-order-book-bid-bg',
      '--theme-order-book-ask-bg'
    ]) {
      assert.match(providerSource, new RegExp(`'${variable}'`, 'u'))
    }
  })

  it('exports the provider, document adapter, hook and theme types from public barrels', () => {
    assert.match(providerSource, /export function applyThemeToDocument/u)
    assert.match(providerSource, /export function ThemeProvider/u)
    assert.match(providerSource, /export function useTheme/u)
    assert.match(themeIndexSource, /export type \{ TradingTheme, TradingThemeId, TradingThemeTokens \}/u)
    assert.match(themeIndexSource, /export \{ defaultThemeId, getTradingTheme, tradingThemes \}/u)
    assert.match(themeIndexSource, /export \{ ThemeProvider, applyThemeToDocument, useTheme \}/u)
    assert.match(rootIndexSource, /export \* from '\.\/theme'/u)
  })

})
