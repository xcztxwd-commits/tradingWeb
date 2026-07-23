import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'App.tsx'), 'utf8')
const mainSource = readFileSync(join(currentDir, '..', 'main.tsx'), 'utf8')
const appShellControllerSource = readFileSync(join(currentDir, 'AppShell.tsx'), 'utf8')
const pcShellSource = readFileSync(join(currentDir, '..', 'pc', 'shell', 'PcShellChrome.tsx'), 'utf8')
const mobileShellSource = readFileSync(join(currentDir, '..', 'mobile', 'shell', 'MobileShellChrome.tsx'), 'utf8')
const shellRouteModelSource = readFileSync(join(currentDir, 'shell', 'shellRouteModel.ts'), 'utf8')
const appShellSource = [appShellControllerSource, shellRouteModelSource, pcShellSource, mobileShellSource].join('\n')
const baseStyles = readFileSync(join(currentDir, '..', 'styles.css'), 'utf8')
const styles = baseStyles
const appShellStyles = readFileSync(join(currentDir, 'AppShell.module.css'), 'utf8')
const pcShellStyles = readFileSync(join(currentDir, '..', 'pc', 'shell', 'PcShellChrome.module.css'), 'utf8')
const mobileShellStyles = readFileSync(join(currentDir, '..', 'mobile', 'shell', 'MobileShellChrome.module.css'), 'utf8')
const tradingMenuStyles = readFileSync(join(currentDir, 'components', 'TradingNavMenu.module.css'), 'utf8')
const accountMenuStyles = readFileSync(join(currentDir, 'components', 'AccountUserMenu.module.css'), 'utf8')
const languageSwitcherStyles = readFileSync(join(currentDir, '..', 'components', 'LanguageSwitcher.module.css'), 'utf8')
const topbarToolIconStyles = readFileSync(join(currentDir, '..', 'components', 'TopbarToolIcon.module.css'), 'utf8')
const ownedShellStyles = [
  appShellStyles,
  pcShellStyles,
  mobileShellStyles,
  tradingMenuStyles,
  accountMenuStyles,
  languageSwitcherStyles,
  topbarToolIconStyles
].join('\n')
const ownedShellSources = [
  appShellControllerSource,
  pcShellSource,
  mobileShellSource,
  readFileSync(join(currentDir, 'components', 'TradingNavMenu.tsx'), 'utf8'),
  readFileSync(join(currentDir, 'components', 'AccountUserMenu.tsx'), 'utf8'),
  readFileSync(join(currentDir, '..', 'components', 'LanguageSwitcher.tsx'), 'utf8'),
  readFileSync(join(currentDir, '..', 'components', 'TopbarToolIcon.tsx'), 'utf8')
].join('\n')
const navigationPath = join(currentDir, 'navigation.ts')
const navigationSource = existsSync(navigationPath) ? readFileSync(navigationPath, 'utf8') : ''
const mobileTerminalStyles = readFileSync(
  join(currentDir, '..', 'mobile', 'pages', 'trading', 'shell', 'MobileTradingTerminal.module.css'),
  'utf8'
)
const enLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'en-US.ts'), 'utf8')
const zhLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'zh-CN.ts'), 'utf8')
const jaLocale = readFileSync(join(currentDir, '..', 'i18n', 'locales', 'ja-JP.ts'), 'utf8')

describe('prototype-driven app shell and routes', () => {
  it('mounts the shared theme provider before app-owned styles', () => {
    assert.match(mainSource, /import \{ ThemeProvider \} from '@fx-platform\/ui'/)
    assert.match(mainSource, /import '@fx-platform\/ui\/theme\.css'/)
    assert.match(mainSource, /import '\.\/styles\.css'/)
    assert.ok(mainSource.indexOf('@fx-platform/ui/theme.css') < mainSource.indexOf('./styles.css'))
    assert.match(mainSource, /<ThemeProvider>[\s\S]*<App \/>[\s\S]*<\/ThemeProvider>/)
  })

  it('keeps shell and route styles owner-local without the legacy application surface', () => {
    assert.match(appShellControllerSource, /import styles from '\.\/AppShell\.module\.css'/)
    assert.doesNotMatch(appShellControllerSource, /ApplicationSurfaces\.module\.css/)
    assert.doesNotMatch(appShellControllerSource, /applicationSurfaceStyles/)
    assert.doesNotMatch(
      ownedShellSources,
      /["'`](?:[^"'`]*\s)?(?:app-shell|main-region|app-brand|app-topbar|trading-nav-menu|account-user-menu|mobile-tabs|mobile-tab|language-switcher|topbar-tool-icon-image)(?:[\s_\-"'`]|$)/
    )
    assert.doesNotMatch(ownedShellSources, /["'`] active["'`]/)
    assert.doesNotMatch(ownedShellStyles, /:global\(/)
  })

  it('keeps the homepage at root and exposes only canonical Spot and Perpetual terminals', () => {
    assert.match(source, /import \{ HomeRoute \} from '\.\.\/routes\/home\/HomeRoute'/)
    assert.match(source, /<Route path="\/" element=\{<HomeRoute \/>\} \/>/)
    assert.match(source, /<Route path="\/trade\/spot\/:symbol\?" element=\{<TradingRoute product="spot" \/>\} \/>/)
    assert.match(source, /<Route path="\/trade\/perpetual\/:symbol\?" element=\{<TradingRoute product="perpetual" \/>\} \/>/)
    assert.match(source, /<Route path="\/trading" element=\{<LegacyTradingRedirect \/>\} \/>/)
    assert.match(source, /<Route path="\/trade\/:product\/:symbol\?" element=\{<Navigate to=\{resolveSafeTradingPath\(null\)\} replace \/>\} \/>/)
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
    assert.match(source, /<AccountRoute mode="overview" \/>/)
    assert.match(source, /<AccountRoute mode="assets" \/>/)
    assert.match(source, /<AccountRoute mode="funding-records" \/>/)
    assert.match(source, /<AccountRoute mode="trade-records" \/>/)
    assert.match(source, /<AccountRoute mode="kyc" \/>/)
    assert.match(source, /<AccountRoute mode="account-settings" \/>/)
  })

  it('uses login-aware navigation and dedicated dropdown menus', () => {
    assert.equal(existsSync(navigationPath), true)
    assert.match(appShellSource, /readStoredAuthToken/)
    assert.match(appShellSource, /guestNavItems/)
    assert.match(appShellSource, /authenticatedNavItems/)
    assert.match(appShellSource, /TradingNavMenu/)
    assert.match(appShellSource, /AccountUserMenu/)
    assert.match(appShellSource, /useTheme/)
    assert.match(appShellSource, /const \{ currentTheme,\s*toggleTheme \} = useTheme\(\)/)
    assert.match(pcShellSource, /className=\{styles\.icon\}\s+aria-label="Switch theme"/)
    assert.match(pcShellSource, /aria-pressed=\{model\.themeColorScheme === 'light'\}/)
    assert.match(pcShellSource, /onClick=\{model\.onToggleTheme\}/)
    assert.match(appShellSource, /to="\/login"/)
    assert.match(appShellSource, /to="\/register"/)
    assert.match(navigationSource, /export const guestNavItems/)
    assert.match(navigationSource, /export const authenticatedNavItems/)
    assert.doesNotMatch(getArraySource(navigationSource, 'guestNavItems'), /\/wallet|\/orders|\/positions|\/security|\/settings/)
  })

  it('uses the same desktop topbar on home and markets', () => {
    assert.match(pcShellSource, /<Link className=\{styles\.brand\} to="\/" aria-label="FX Trader 首页">/)
    assert.doesNotMatch(appShellSource, /isMarketsRoute/)
    assert.doesNotMatch(appShellSource, /MarketsReferenceNav|MarketsReferenceActions/)
    assert.doesNotMatch(appShellSource, /app-topbar--reference|app-brand--reference/)
    assert.doesNotMatch(styles, /\.app-topbar--reference|\.app-brand--reference|\.market-reference-/)
  })

  it('removes the desktop home button while keeping logo and mobile home navigation', () => {
    const guestNavSource = getArraySource(navigationSource, 'guestNavItems')
    const authNavSource = getArraySource(navigationSource, 'authenticatedNavItems')
    const mobileNavSource = getArraySource(navigationSource, 'mobileNavItems')

    assert.doesNotMatch(guestNavSource, /to:\s*'\/'/)
    assert.doesNotMatch(authNavSource, /to:\s*'\/'/)
    assert.match(pcShellSource, /<Link className=\{styles\.brand\} to="\/" aria-label="FX Trader 首页">/)
    assert.match(mobileNavSource, /to:\s*'\/'/)
  })

  it('keeps the home trading dropdown wired only to Spot and Perpetual routes', () => {
    const tradingNavSource = readFileSync(join(currentDir, 'components', 'TradingNavMenu.tsx'), 'utf8')
    const topbarNavRule = getCssRule(pcShellStyles, '.nav')

    assert.match(tradingNavSource, /resolveTradingPath/)
    assert.match(tradingNavSource, /onClick=\{\(\) => openProduct\(item\.product\)\}/)
    assert.match(tradingNavSource, /role="menuitem"/)
    assert.match(tradingNavSource, /product:\s*'spot'/)
    assert.match(tradingNavSource, /product:\s*'perpetual'/)
    assert.doesNotMatch(tradingNavSource, /category:\s*'forex'|INVERSE_PERP|options/i)
    assert.match(tradingNavSource, /onClick=\{\(\) => setOpen\(true\)\}/)
    assert.match(tradingNavSource, /onPointerEnter=\{\(event\) => \{[\s\S]*event\.pointerType !== 'touch'[\s\S]*setOpen\(true\)/)
    assert.match(tradingNavSource, /onPointerLeave=\{\(event\) => \{[\s\S]*event\.pointerType !== 'touch'[\s\S]*setOpen\(false\)/)
    assert.doesNotMatch(tradingNavSource, /setOpen\(\(current\) => !current\)/)
    assert.doesNotMatch(tradingNavSource, /onFocus=\{\(\) => setOpen\(true\)\}/)
    assert.match(tradingNavSource, /event\.key === 'ArrowDown'/)
    assert.match(topbarNavRule, /overflow:\s*visible/)
    assert.doesNotMatch(topbarNavRule, /overflow-x:\s*auto/)
    assert.doesNotMatch(pcShellStyles, /\.nav::-webkit-scrollbar/)
  })

  it('bridges the hover gap between the trading trigger and dropdown panel', () => {
    const bridgeRule = getCssRule(tradingMenuStyles, '.root:hover::after')
    const tradingPanelRule = getCssRule(tradingMenuStyles, '.panel')

    assert.match(tradingPanelRule, /top:\s*calc\(100% \+ 10px\)/)
    assert.match(bridgeRule, /content:\s*''/)
    assert.match(bridgeRule, /top:\s*100%/)
    assert.match(bridgeRule, /height:\s*10px/)
  })

  it('keeps the account user menu open on click instead of focus-toggling closed', () => {
    const accountMenuSource = readFileSync(join(currentDir, 'components', 'AccountUserMenu.tsx'), 'utf8')

    assert.match(accountMenuSource, /aria-label="个人中心"/)
    assert.match(accountMenuSource, /onClick=\{\(\) => setOpen\(true\)\}/)
    assert.doesNotMatch(accountMenuSource, /setOpen\(\(current\) => !current\)/)
    assert.doesNotMatch(accountMenuSource, /onFocus=\{\(\) => setOpen\(true\)\}/)
    assert.match(accountMenuSource, /if \(event\.key === 'Escape'\) setOpen\(false\)/)
  })

  it('refreshes the shell session immediately after account logout', () => {
    const accountMenuSource = readFileSync(join(currentDir, 'components', 'AccountUserMenu.tsx'), 'utf8')

    assert.match(accountMenuSource, /onLogout:\s*\(\)\s*=>\s*void/)
    assert.match(accountMenuSource, /clearStoredAuthToken\(\)[\s\S]*onLogout\(\)/)
    assert.match(appShellControllerSource, /const handleLogout = useCallback\(\(\) => \{[\s\S]*setSession\(\{ authenticated: false, email: null \}\)/)
    assert.match(pcShellSource, /<AccountUserMenu email=\{model\.email\} onLogout=\{model\.onLogout\} triggerClassName=\{styles\.icon\} \/>/)
  })

  it('keeps the account user panel stable across the pointer gap and centered on the profile icon', () => {
    const accountMenuSource = readFileSync(join(currentDir, 'components', 'AccountUserMenu.tsx'), 'utf8')
    const accountPanelRule = getCssRuleContaining(accountMenuStyles, '.panel', /position:\s*absolute/)
    const accountMenuRule = getCssRule(accountMenuStyles, '.root')

    assert.doesNotMatch(accountMenuSource, /onMouseLeave/)
    assert.match(accountMenuSource, /document\.addEventListener\('pointerdown', handlePointer\)/)
    assert.match(accountMenuRule, /position:\s*relative/)
    assert.match(accountPanelRule, /position:\s*absolute/)
    assert.match(accountPanelRule, /top:\s*calc\(100% \+ 10px\)/)
    assert.match(accountPanelRule, /left:\s*50%/)
    assert.match(accountPanelRule, /right:\s*auto/)
    assert.match(accountPanelRule, /transform:\s*translateX\(-50%\)/)
  })

  it('keeps desktop IA focused on markets, trading, wallet and account', () => {
    const guestNavSource = getArraySource(navigationSource, 'guestNavItems')
    const authNavSource = getArraySource(navigationSource, 'authenticatedNavItems')

    for (const route of ['/markets']) {
      assert.match(guestNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
      assert.match(authNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
    }

    assert.match(authNavSource, /to:\s*'\/account\/assets'/)
    assert.match(authNavSource, /labelKey:\s*'nav\.wallet'/)
    assert.doesNotMatch(authNavSource, /\/dashboard|\/orders'|\/positions|\/security'|\/settings'/)
  })

  it('keeps mobile navigation capped to five implemented entries', () => {
    const mobileNavSource = getArraySource(navigationSource, 'mobileNavItems')
    const expectedRoutes = ['/', '/markets', '/trade/spot/BTCUSDT', '/account/orders/trades', '/account/overview']

    for (const route of expectedRoutes) {
      assert.match(mobileNavSource, new RegExp(`to:\\s*'${escapeRegExp(route)}'`))
    }

    assert.equal([...mobileNavSource.matchAll(/to:\s*'/g)].length, expectedRoutes.length)
    assert.doesNotMatch(mobileNavSource, /to:\s*'\/admin'|to:\s*'\/wallet'|to:\s*'\/orders'|to:\s*'\/positions'|to:\s*'\/security'|to:\s*'\/settings'/)
    assert.match(appShellSource, /isConfiguredNavPathActive\(item, pathname\)/)
    assert.match(navigationSource, /pathname === path \|\| pathname\.startsWith\(`\$\{path\}\/`\)/)
  })

  it('uses Binance-like shell tokens with safe-area mobile tabs', () => {
    assert.match(appShellStyles, /^\.root\s*{[^}]*min-width:\s*0[^}]*isolation:\s*isolate/m)
    assert.match(baseStyles, /--app-top-nav-height:\s*56px/)
    assert.match(styles, /--bn-bg:\s*var\(--theme-background\)/)
    assert.match(styles, /--bn-terminal-bg:\s*var\(--theme-background\)/)
    assert.match(styles, /--bn-surface:\s*var\(--theme-surface\)/)
    assert.match(styles, /--bn-surface-2:\s*color-mix\(in srgb,\s*var\(--theme-surface\) 82%,\s*var\(--theme-background\)\)/)
    assert.match(styles, /--bn-line:\s*var\(--theme-border\)/)
    assert.match(styles, /--bn-text:\s*var\(--theme-text-primary\)/)
    assert.match(styles, /--bn-muted:\s*var\(--theme-text-muted\)/)
    assert.match(styles, /--color-BtnBg:\s*var\(--theme-primary-hover\)/)
    for (const retiredToken of [
      '--color-PrimaryYellow', '--color-BasicBg', '--color-SecondaryBg', '--color-PrimaryText',
      '--color-SecondaryText', '--color-Line', '--color-Buy', '--color-Sell', '--space-2xs',
      '--space-s', '--space-mm', '--space-xl', '--radii-xl', '--bn-panel-3', '--bn-shadow-soft',
      '--binance-page-bg', '--binance-surface', '--binance-surface-elevated', '--binance-line'
    ]) {
      assert.doesNotMatch(styles, new RegExp(`${escapeRegExp(retiredToken)}\\s*:`))
    }
    assert.match(styles, /font-family:\s*BinanceNova,\s*Arial/)
    assert.doesNotMatch(styles, /\bInter\b/)
    assert.match(appShellStyles, /\.terminal\s*{[^}]*background:\s*var\(--bn-terminal-bg\)/)
    assert.match(pcShellStyles, /\.root\s*{[^}]*background:\s*var\(--bn-bg\)/)
    assert.doesNotMatch(pcShellStyles, /border-bottom\s*:/)
    assert.match(pcShellStyles, /\.utilityCluster/)
    assert.match(pcShellStyles, /\.primary\s*{[^}]*background:\s*var\(--color-BtnBg\)/)
    assert.match(mobileShellStyles, /\.root\s*{[\s\S]*position:\s*fixed/)
    assert.match(mobileShellStyles, /\.root\s*{[\s\S]*grid-template-columns:\s*repeat\(5,\s*1fr\)/)
    assert.match(mobileShellStyles, /\.root\s*{[\s\S]*padding-bottom:\s*env\(safe-area-inset-bottom\)/)
    assert.match(mobileShellStyles, /\.trade,[\s\S]*border-radius:\s*0/)
    assert.doesNotMatch(appShellSource, /<item\.icon size=\{17\}/)
    assert.match(pcShellSource, /styles\.utilityCluster/)
  })

  it('keeps auth pages chrome-free and terminal route compatible with mobile trading actions', () => {
    assert.match(appShellStyles, /\.auth\s*{[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\)/)
    assert.match(pcShellSource, /if \(model\.isAuthRoute\) return null/)
    assert.match(mobileShellSource, /if \(model\.isAuthRoute\) return null/)
    assert.match(appShellStyles, /\.terminal \.mainRegion\s*{[^}]*padding:\s*0/)
    assert.match(appShellSource, /\^\\\/trade\\\/\(spot\|perpetual\)/)
    assert.match(appShellSource, /item\.to\.startsWith\('\/trade\/'\)/)
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

function getCssRule(sourceText: string, selector: string) {
  const start = sourceText.indexOf(`${selector} {`)
  assert.notEqual(start, -1, `${selector} should exist`)
  const end = sourceText.indexOf('\n}', start)
  assert.notEqual(end, -1, `${selector} should close`)
  return sourceText.slice(start, end + 2)
}

function getCssRuleContaining(sourceText: string, selector: string, pattern: RegExp) {
  let start = sourceText.indexOf(`${selector} {`)
  while (start !== -1) {
    const end = sourceText.indexOf('\n}', start)
    assert.notEqual(end, -1, `${selector} should close`)
    const rule = sourceText.slice(start, end + 2)
    if (pattern.test(rule)) return rule
    start = sourceText.indexOf(`${selector} {`, end)
  }
  assert.fail(`${selector} rule matching ${pattern} should exist`)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
