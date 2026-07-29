import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-trading-login-gate.mjs')
const orderSubmitButtonPath = join(projectRoot, 'apps', 'web', 'src', 'shared-widgets', 'trading', 'order-form', 'OrderSubmitButton.tsx')
const loginPromptDialogPath = join(projectRoot, 'apps', 'web', 'src', 'shared-widgets', 'trading', 'components', 'LoginPromptDialog.tsx')
const mobileTerminalPath = join(projectRoot, 'apps', 'web', 'src', 'mobile', 'pages', 'trading', 'shell', 'MobileTradingTerminal.tsx')

describe('trading login gate smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:trading-login-gate'], 'node scripts/smoke-trading-login-gate.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('starts Vite on the configured web base URL instead of hard-coding the default port', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const webUrl = new URL\(webBaseUrl\)/)
    assert.match(source, /const webHost = webUrl\.hostname \|\| '127\.0\.0\.1'/)
    assert.match(source, /const webPort = webUrl\.port \|\| '5173'/)
    assert.doesNotMatch(source, /--port 5173/)
  })

  it('points the spawned frontend at the same backend API used for session probes', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /VITE_API_BASE_URL: apiBaseUrl/)
  })

  it('uses stable semantic hooks instead of CSS class names for browser actions', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const orderSubmitButton = readFileSync(orderSubmitButtonPath, 'utf8')
    const loginPromptDialog = readFileSync(loginPromptDialogPath, 'utf8')
    const mobileTerminal = readFileSync(mobileTerminalPath, 'utf8')

    assert.match(orderSubmitButton, /data-trading-action=\{loginRequired \? 'login-required' : 'submit-order'\}/)
    assert.match(loginPromptDialog, /data-trading-action="close-login-prompt"/)
    assert.match(loginPromptDialog, /data-trading-action="go-to-login"/)
    assert.match(mobileTerminal, /data-testid="mobile-trade-action"/)
    assert.match(source, /\[data-trading-action="login-required"\]/)
    assert.match(source, /\[data-trading-action="close-login-prompt"\]/)
    assert.match(source, /\[data-trading-action="go-to-login"\]/)
    assert.match(source, /\[data-testid="mobile-trade-action"\]/)
    assert.match(source, /\[data-platform-view="pc"\] \[data-panel-id="chart"\]/)
    assert.match(source, /\[data-platform-view="pc"\] \[data-panel-id="market"\]/)
    assert.match(source, /getElementById\('trading-login-title'\)/)
    assert.match(source, /\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby\]/)
    assert.match(source, /orderSheet\.getBoundingClientRect\(\)/)
    assert.doesNotMatch(source, /mobile-order-sheet-title/)
    assert.doesNotMatch(source, /orderSheet\.querySelector\('section\[aria-label\]'\)/)
    assert.doesNotMatch(source, /classList\.contains\('trade-panel__submit--login'\)/)
    assert.doesNotMatch(source, /className\)\.includes\('tradeAction'\)/)
    assert.doesNotMatch(source, /\.trade-panel\.trade-panel--compact/)
    assert.doesNotMatch(source, /canSeeGuestStatus/)
    assert.doesNotMatch(source, /const canSeeLoginOrderButton = [^\r\n]*\|\|/)
    assert.doesNotMatch(source, /const loginButton = [^\r\n]*\?\?/)
    assert.doesNotMatch(source, /const tradeButton = [^\r\n]*\?\?/)
    assert.doesNotMatch(source, /sheetTitle\?\.textContent/)
    assert.doesNotMatch(source, /const dialogOpen = \[\.\.\.document\.querySelectorAll\('\[role="dialog"\]'\)\]/)
    assert.doesNotMatch(source, /document\.querySelector\('\[role="dialog"\]'\) !== null/)
    assert.doesNotMatch(source, /text\.includes\('K线图'\)/)
  })

  it('uses the canonical Spot terminal and preserves it through login redirect', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const tradingUrl = `\$\{webBaseUrl\}\/trade\/spot\/BTCUSDT`/)
    assert.match(source, /redirect === '\/trade\/spot\/BTCUSDT'/)
    assert.doesNotMatch(source, /const tradingUrl = `\$\{webBaseUrl\}\/trading`/)
  })

  it('does not select localized login-prompt actions', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /getElementById\('trading-login-title'\)/)
    assert.match(source, /EURUSD/)
    assert.doesNotMatch(source, /Log in to enable trading execution/)
    assert.doesNotMatch(source, /button\.textContent\?\.trim\(\) ===/)
    assert.doesNotMatch(source, /document\.querySelector\('button\[aria-label=/)
    assert.doesNotMatch(source, /\.find\(\(button\) => button\.textContent\?\.includes/)
  })
})
