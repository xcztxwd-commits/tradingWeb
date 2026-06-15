import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const themesPath = join(currentDir, 'themes.ts')
const cssPath = join(currentDir, 'theme.css')

const themeIds = ['binance-inspired', 'minimal-white']
const requiredTokens = [
  'background',
  'surface',
  'surfaceElevated',
  'border',
  'textPrimary',
  'textSecondary',
  'textMuted',
  'primary',
  'primaryHover',
  'accent',
  'success',
  'buy',
  'danger',
  'sell',
  'warning',
  'chartGrid',
  'chartCandleUp',
  'chartCandleDown',
  'orderBookBidBg',
  'orderBookAskBg'
]

describe('trading theme tokens', () => {
  it('defines the default dark theme and the global minimal white theme', () => {
    assert.equal(existsSync(themesPath), true, 'themes.ts should exist')
    const source = readFileSync(themesPath, 'utf8')

    assert.match(source, /export const tradingThemes/)
    for (const themeId of themeIds) {
      assert.match(source, new RegExp(`id:\\s*'${themeId}'`), `${themeId} should be defined`)
    }
    for (const removedThemeId of ['midnight-pro', 'okx-inspired', 'deep-blue-quant', 'light-institutional']) {
      assert.doesNotMatch(source, new RegExp(`id:\\s*'${removedThemeId}'`), `${removedThemeId} should not be defined`)
    }
    assert.match(source, /export const defaultThemeId: TradingThemeId = 'binance-inspired'/)
    assert.match(source, /primary:\s*'#f0b90b'/)
    assert.match(source, /primaryHover:\s*'#fcd535'/)
    assert.match(source, /id:\s*'minimal-white'/)
    assert.match(source, /colorScheme:\s*'light'/)
    assert.match(source, /background:\s*'#f6f8fb'/)
    assert.match(source, /surface:\s*'#ffffff'/)
    assert.match(source, /primary:\s*'#111827'/)
    assert.match(source, /chartGrid:\s*'#e8edf4'/)
  })

  it('gives every theme the required token contract', () => {
    assert.equal(existsSync(themesPath), true, 'themes.ts should exist')
    const source = readFileSync(themesPath, 'utf8')

    for (const token of requiredTokens) {
      const matches = source.match(new RegExp(`${token}:\\s*['"]`, 'g')) ?? []
      assert.equal(matches.length, themeIds.length, `${token} should exist on every theme`)
    }
  })

  it('bridges semantic tokens to existing trading CSS variables', () => {
    assert.equal(existsSync(cssPath), true, 'theme.css should exist')
    const css = readFileSync(cssPath, 'utf8')

    assert.match(css, /--trading-page-bg:\s*var\(--theme-background\)/)
    assert.match(css, /--trading-surface:\s*var\(--theme-surface\)/)
    assert.match(css, /--trading-buy:\s*var\(--theme-buy\)/)
    assert.match(css, /--trading-sell:\s*var\(--theme-sell\)/)
    assert.match(css, /--trading-chart-grid-horizontal:\s*var\(--theme-chart-grid\)/)
    assert.match(css, /--loading-bg:\s*var\(--theme-background\)/)
  })

  it('sets the default dark palette and minimal white CSS fallback with the font contract', () => {
    assert.equal(existsSync(cssPath), true, 'theme.css should exist')
    const css = readFileSync(cssPath, 'utf8')

    for (const token of [
      '--theme-background: #181a20',
      '--theme-surface: #202630',
      '--theme-surface-elevated: #29313d',
      '--theme-border: #333b47',
      '--theme-text-primary: #eaecef',
      '--theme-text-secondary: #929aa5',
      '--theme-text-muted: #707a8a',
      '--theme-primary: #f0b90b',
      '--theme-primary-hover: #fcd535',
      '--theme-accent: #fcd535',
      '--theme-success: #2ebd85',
      '--theme-buy: #2ebd85',
      '--theme-danger: #f6465d',
      '--theme-sell: #f6465d',
      '--theme-chart-grid: #333b47'
    ]) {
      assert.match(css, new RegExp(escapeRegExp(token)))
    }

    assert.match(css, /--font-ui:\s*BinanceNova,\s*Arial/)
    assert.match(css, /:root\[data-theme='minimal-white'\]/)
    assert.match(css, /--theme-background:\s*#f6f8fb/)
    assert.match(css, /--theme-surface:\s*#ffffff/)
    assert.match(css, /--theme-text-primary:\s*#111827/)
    assert.match(css, /--theme-primary:\s*#111827/)
    assert.match(css, /--theme-chart-grid:\s*#e8edf4/)
    assert.doesNotMatch(css, /\bInter\b/)
  })
})

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
