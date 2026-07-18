import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  defaultThemeId,
  getTradingTheme,
  tradingThemes,
  type TradingThemeId
} from './themes.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const cssPath = resolve(currentDir, 'theme.css')

const expectedThemes = [
  {
    id: 'binance-inspired',
    name: 'Binance Inspired',
    colorScheme: 'dark',
    tokens: {
      background: '#181a20',
      surface: '#181a20',
      surfaceElevated: '#1e2329',
      border: '#2b3139',
      textPrimary: '#eaecef',
      textSecondary: '#929aa5',
      textMuted: '#707a8a',
      primary: '#f0b90b',
      primaryHover: '#fcd535',
      accent: '#fcd535',
      success: '#2ebd85',
      buy: '#2ebd85',
      danger: '#f6465d',
      sell: '#f6465d',
      warning: '#f0b90b',
      chartGrid: '#2b3139',
      chartCandleUp: '#2ebd85',
      chartCandleDown: '#f6465d',
      orderBookBidBg: 'rgba(46, 189, 133, 0.1)',
      orderBookAskBg: 'rgba(246, 70, 93, 0.1)'
    }
  },
  {
    id: 'minimal-white',
    name: 'Minimal White',
    colorScheme: 'light',
    tokens: {
      background: '#f6f8fb',
      surface: '#ffffff',
      surfaceElevated: '#f9fafc',
      border: '#e2e7ef',
      textPrimary: '#111827',
      textSecondary: '#475569',
      textMuted: '#475569',
      primary: '#111827',
      primaryHover: '#0f172a',
      accent: '#111827',
      success: '#047857',
      buy: '#047857',
      danger: '#be123c',
      sell: '#be123c',
      warning: '#9a6a00',
      chartGrid: '#e8edf4',
      chartCandleUp: '#047857',
      chartCandleDown: '#be123c',
      orderBookBidBg: 'rgba(7, 135, 90, 0.08)',
      orderBookAskBg: 'rgba(212, 61, 86, 0.08)'
    }
  }
] as const

describe('trading theme package', () => {
  it('preserves every existing theme id, scheme and token value', () => {
    assert.equal(defaultThemeId, 'binance-inspired')
    assert.deepEqual(tradingThemes, expectedThemes)
  })

  it('returns the default theme for omitted and unknown ids', () => {
    assert.equal(getTradingTheme(), tradingThemes[0])
    assert.equal(getTradingTheme('minimal-white'), tradingThemes[1])
    assert.equal(getTradingTheme('unknown' as TradingThemeId), tradingThemes[0])
  })

  it('preserves the semantic CSS bridge and both fallback palettes', () => {
    const css = readFileSync(cssPath, 'utf8')

    for (const bridge of [
      '--trading-page-bg: var(--theme-background)',
      '--trading-surface: var(--theme-surface)',
      '--trading-buy: var(--theme-buy)',
      '--trading-sell: var(--theme-sell)',
      '--trading-chart-grid-horizontal: var(--theme-chart-grid)',
      '--loading-bg: var(--theme-background)'
    ]) {
      assert.match(css, new RegExp(escapeRegExp(bridge)))
    }

    assert.match(css, /--theme-background:\s*#181a20/u)
    assert.match(css, /--font-ui:\s*BinanceNova,\s*Arial/u)
    assert.match(css, /:root\[data-theme='minimal-white'\]/u)
    assert.match(css, /--theme-background:\s*#f6f8fb/u)
    assert.match(css, /--theme-surface:\s*#ffffff/u)
    assert.match(css, /--theme-chart-grid:\s*#e8edf4/u)
    assert.doesNotMatch(css, /\bInter\b/u)
  })
})

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}
