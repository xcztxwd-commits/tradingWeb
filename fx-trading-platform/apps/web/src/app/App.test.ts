import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'App.tsx'), 'utf8')
const appShellSource = readFileSync(join(currentDir, 'AppShell.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, '..', 'styles.css'), 'utf8')
const navigationPath = join(currentDir, 'navigation.ts')
const navigationSource = existsSync(navigationPath) ? readFileSync(navigationPath, 'utf8') : ''
const mobileTerminalStyles = readFileSync(
  join(currentDir, '..', 'pages', 'trading', 'mobile', 'MobileTradingTerminal.module.css'),
  'utf8'
)
const enLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'en-US.ts'), 'utf8')
const zhLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'zh-CN.ts'), 'utf8')
const jaLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'ja-JP.ts'), 'utf8')

describe('prototype-driven app shell and routes', () => {
  it('keeps the homepage at root and preserves the legacy trade redirect', () => {
    assert.match(source, /const HomePage = lazy\(/)
    assert.match(source, /<Route path="\/" element=\{<HomePage \/>\} \/>/)
    assert.match(source, /<Route path="\/trade" element=\{<Navigate to="\/trading" replace \/>\} \/>/)
    assert.doesNotMatch(source, /path="\/admin"/)
  })

  it('adds the prototype account routes without exposing dead top-level user pages as primary IA', () => {
    const expectedRoutes = [
      '/account/overview',
      '/account/assets',
      '/account/orders/funding',
      '/account/orders/trades',
      '/account/security/kyc',
      '/account/settings'
    ]

    for (const route of expectedRoutes) {
      assert.match(source, new RegExp(`path="${escapeRegExp(route)}"`), `missing ${route}`)
    }

    assert.match(source, /<Route path="\/account" element=\{<Navigate to="\/account\/overview" replace \/>\} \/>/)
    assert.match(source, /AccountOverviewPage/)
    assert.match(source, /AccountAssetsPage/)
    assert.match(source, /FundingRecordsPage/)
    assert.match(source, /TradeOrdersPage/)
    assert.match(source, /KycPage/)
    assert.match(source, /AccountSettingsPage/)
  })

  it('uses login-aware navigation and dedicated dropdown menus', () => {
    assert.equal(existsSync(navigationPath), true)
    assert.match(appShellSource, /readStoredAuthToken/)
    assert.match(appShellSource, /guestNavItems/)
    assert.match(appShellSource, /authenticatedNavItems/)
    assert.match(appShellSource, /TradingNavMenu/)
    assert.match(appShellSource, /AccountUserMenu/)
    assert.match(appShellSource, /to="\/login"/)
    assert.match(appShellSource, /to="\/register"/)
    assert.match(navigationSource, /export const guestNavItems/)
    assert.match(navigationSource, /export const authenticatedNavItems/)
    assert.doesNotMatch(getArraySource(navigationSource, 'guestNavItems'), /\/wallet|\/orders|\/positions|\/security|\/settings/)
  })

  it('keeps desktop IA focused on home, markets, trading, wallet and account', () => {
    const guestNavSource = getArraySource(navigationSource, 'guestNavItems')
    const authNavSource = getArraySource(navigationSource, 'authenticatedNavItems')

    for (const route of ['/', '/markets']) {
      assert.match(guestNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
      assert.match(authNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
    }

    assert.match(authNavSource, /to:\s*'\/account\/assets'/)
    assert.match(authNavSource, /labelKey:\s*'nav\.wallet'/)
    assert.doesNotMatch(authNavSource, /\/dashboard|\/orders'|\/positions|\/security'|\/settings'/)
  })

  it('keeps mobile navigation capped to five implemented entries', () => {
    const mobileNavSource = getArraySource(navigationSource, 'mobileNavItems')
    const expectedRoutes = ['/', '/markets', '/trading', '/account/orders/trades', '/account/overview']

    for (const route of expectedRoutes) {
      assert.match(mobileNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
    }

    assert.equal([...mobileNavSource.matchAll(/to:\s*'/g)].length, expectedRoutes.length)
    assert.doesNotMatch(mobileNavSource, /to:\s*'\/admin'|to:\s*'\/wallet'|to:\s*'\/orders'|to:\s*'\/positions'|to:\s*'\/security'|to:\s*'\/settings'/)
  })

  it('uses Binance-like shell tokens with safe-area mobile tabs', () => {
    assert.match(styles, /--app-top-nav-height:\s*64px/)
    assert.match(styles, /--bn-bg:\s*#181a20/)
    assert.match(styles, /--bn-terminal-bg:\s*#0b0e11/)
    assert.match(styles, /--bn-surface:\s*#202630/)
    assert.match(styles, /--bn-surface-2:\s*#29313d/)
    assert.match(styles, /--bn-line:\s*#333b47/)
    assert.match(styles, /--bn-text:\s*#eaecef/)
    assert.match(styles, /--bn-muted:\s*#929aa5/)
    assert.match(styles, /--color-PrimaryYellow:\s*#f0b90b/)
    assert.match(styles, /--color-BtnBg:\s*#fcd535/)
    assert.match(styles, /--color-BasicBg:\s*#ffffff/)
    assert.match(styles, /--color-Buy:\s*#2ebd85/)
    assert.match(styles, /--color-Sell:\s*#f6465d/)
    assert.match(styles, /font-family:\s*BinanceNova,\s*Arial/)
    assert.doesNotMatch(styles, /\bInter\b/)
    assert.match(styles, /\.app-shell\s*{[\s\S]*background:\s*var\(--bn-bg\)/)
    assert.match(styles, /\.app-shell--terminal\s*{[\s\S]*background:\s*var\(--bn-terminal-bg\)/)
    assert.match(styles, /\.app-topbar\s*{[\s\S]*background:\s*var\(--bn-bg\)/)
    assert.match(styles, /\.app-topbar__utility-cluster/)
    assert.match(styles, /\.app-topbar__primary\s*{[\s\S]*background:\s*var\(--color-BtnBg\)/)
    assert.match(styles, /\.mobile-tabs\s*{[\s\S]*position:\s*fixed/)
    assert.match(styles, /\.mobile-tabs\s*{[\s\S]*grid-template-columns:\s*repeat\(5,\s*1fr\)/)
    assert.match(styles, /\.mobile-tabs\s*{[\s\S]*padding-bottom:\s*env\(safe-area-inset-bottom\)/)
    assert.match(styles, /\.mobile-tab--trade\s*{[\s\S]*border-radius:\s*50%/)
    assert.doesNotMatch(appShellSource, /<item\.icon size=\{17\}/)
    assert.match(appShellSource, /app-topbar__utility-cluster/)
  })

  it('keeps auth pages chrome-free and terminal route compatible with mobile trading actions', () => {
    assert.match(styles, /\.app-shell--auth\s*{[\s\S]*grid-template-rows:\s*minmax\(0,\s*1fr\)/)
    assert.match(styles, /\.app-shell--auth\s+\.app-topbar\s*{[\s\S]*display:\s*none/)
    assert.match(styles, /\.app-shell--terminal\s+\.main-region\s*{[\s\S]*padding:\s*0/)
    assert.match(
      mobileTerminalStyles,
      /\.actionBar\s*{[\s\S]*bottom:\s*calc\(env\(safe-area-inset-bottom\) \+ var\(--mobile-tabs-height\) \+ var\(--space-2\)\)/
    )
  })

  it('defines translated home navigation labels for all configured locales', () => {
    assert.match(enLocale, /home:\s*'Home'/)
    assert.match(zhLocale, /home:\s*'首页'/)
    assert.match(jaLocale, /home:\s*'ホーム'/)
  })
})

function getArraySource(sourceText: string, name: string) {
  const start = sourceText.indexOf(`export const ${name}`)
  assert.notEqual(start, -1, `${name} should be exported`)
  const nextExport = sourceText.indexOf('\nexport const ', start + 1)
  return sourceText.slice(start, nextExport === -1 ? sourceText.length : nextExport)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
