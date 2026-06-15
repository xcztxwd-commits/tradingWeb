import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'ExchangeLoading.tsx')
const stylesPath = join(currentDir, 'ExchangeLoading.module.css')

describe('ExchangeLoading', () => {
  it('renders an accessible exchange-style loading status', () => {
    assert.equal(existsSync(componentPath), true, 'ExchangeLoading.tsx should exist')

    const source = readFileSync(componentPath, 'utf8')
    assert.doesNotMatch(source, /fx-trading-theme-mode/)
    assert.doesNotMatch(source, /data-theme=\{themeMode\}/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
    assert.match(source, /useTranslation/)
    assert.match(source, /loading\.terminalAria/)
    assert.match(source, /loading\.terminalTitle/)
    assert.match(source, /BTC\/USDT/)
    assert.doesNotMatch(source, /业务入口已预留/)
  })

  it('keeps motion controlled and tokenized in CSS', () => {
    assert.equal(existsSync(stylesPath), true, 'ExchangeLoading.module.css should exist')

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--loading-buy/)
    assert.match(styles, /--loading-sell/)
    assert.match(styles, /animation:/)
  })

  it('uses global theme variables before the terminal page loads', () => {
    assert.equal(existsSync(stylesPath), true, 'ExchangeLoading.module.css should exist')

    const styles = readFileSync(stylesPath, 'utf8')
    assert.doesNotMatch(styles, /\.shell\[data-theme='light'\]/)
    assert.match(styles, /--loading-bg:\s*var\(--theme-background\)/)
    assert.match(styles, /--loading-text:\s*var\(--theme-text-primary\)/)
  })
})
