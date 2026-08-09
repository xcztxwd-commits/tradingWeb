import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const source = readFileSync(fileURLToPath(new URL('./smoke-visual-qa.mjs', import.meta.url)), 'utf8')

describe('visual QA acceptance matrix', () => {
  it('covers every route contract entry and redirect target', () => {
    const requiredPaths = [
      '/',
      '/trade',
      '/trading',
      '/trade/invalid/BTCUSDT',
      '/trade/spot/BTCUSDT',
      '/trade/perpetual/BTCUSDT-PERP',
      '/login',
      '/register',
      '/forgot-password',
      '/two-factor-help',
      '/dashboard',
      '/markets',
      '/orders',
      '/positions',
      '/wallet',
      '/account',
      '/account/overview',
      '/account/assets',
      '/account/orders/funding',
      '/account/orders/trades',
      '/account/security/kyc',
      '/account/settings',
      '/security',
      '/settings',
      '/visual-qa-unknown'
    ]

    for (const path of requiredPaths) {
      assert.match(source, new RegExp(`path:\\s*['\"]${escapeRegExp(path)}['\"]`, 'u'), `missing route ${path}`)
    }
    assert.match(source, /expectedPath:\s*['"]\/trade\/spot\/BTCUSDT['"]/u)
    assert.match(source, /expectedPath:\s*['"]\/account\/overview['"]/u)
    assert.match(source, /expectedPath:\s*['"]\/['"]/u)
  })

  it('preserves current viewports and adds the 899/900/901 boundary matrix', () => {
    const requiredViewports = [
      ['desktop-1440x900', 1440, 900],
      ['mobile-390x844', 390, 844],
      ['mobile-375x667', 375, 667],
      ['mobile-412x915', 412, 915],
      ['landscape-844x390', 844, 390],
      ['landscape-915x412', 915, 412],
      ['boundary-899x844', 899, 844],
      ['boundary-900x844', 900, 844],
      ['boundary-901x844', 901, 844]
    ]

    for (const [name, width, height] of requiredViewports) {
      assert.match(
        source,
        new RegExp(`name:\\s*['\"]${name}['\"][^}]*width:\\s*${width}[^}]*height:\\s*${height}`, 'u'),
        `missing viewport ${name}`
      )
    }
  })

  it('runs every route contract at each 899/900/901 boundary viewport', () => {
    for (const name of ['boundary-899x844', 'boundary-900x844', 'boundary-901x844']) {
      const viewport = source.match(new RegExp(`\\{[^}]*name:\\s*['"]${name}['"][^}]*\\}`, 'u'))?.[0]
      assert.ok(viewport, `missing viewport ${name}`)
      assert.doesNotMatch(viewport, /tradingOnly/u, `${name} must run every route contract`)
    }

    const routeMatrix = source.slice(
      source.indexOf('for (const viewport of viewports)'),
      source.indexOf("console.log('[visual-qa] runtime resize continuity')")
    )
    assert.match(routeMatrix, /for \(const route of routes\)/u)
  })

  it('checks same-page 901 -> 900 -> 899 -> 901 continuity', () => {
    assert.match(source, /const runtimeResizeWidths = \[901, 900, 899, 901\]/u)
    assert.match(source, /runRuntimeResizeCheck/u)
    assert.match(source, /data-platform-view/u)
    assert.match(source, /data-controller-sentinel/u)
    assert.match(source, /controllerSentinel/u)
    assert.match(source, /assertNoBodyHorizontalScroll/u)
    assert.match(source, /assertNoConsoleErrors/u)
  })

  it('samples console errors after all route interactions', () => {
    const routeCheckSource = source.slice(
      source.indexOf('async function runRouteCheck'),
      source.indexOf('async function runRuntimeResizeCheck')
    )
    const consoleHealthIndex = routeCheckSource.indexOf('const consoleHealth')

    for (const interaction of [
      'assertTradingInteractions(page',
      'assertTradingAppearanceMatrix(page',
      'assertAccountActionPaths(page',
      'focusMarketsCollection(page'
    ]) {
      const interactionIndex = routeCheckSource.indexOf(interaction)
      assert.notEqual(interactionIndex, -1, `missing interaction ${interaction}`)
      assert.ok(consoleHealthIndex > interactionIndex, `console errors must be sampled after ${interaction}`)
    }
  })

  it('keeps the fixed trading bar clear of global navigation and reserves scroll space', () => {
    const terminalSource = source.slice(
      source.indexOf('async function assertTradingTerminalContract'),
      source.indexOf('async function assertPlatformView')
    )

    assert.match(terminalSource, /terminalPaddingBottom/u)
    assert.match(terminalSource, /globalNavigationRect/u)
    assert.match(terminalSource, /result\.terminalPaddingBottom >= result\.viewportHeight - result\.barRect\.top/u)
    assert.match(terminalSource, /result\.barRect\.bottom <= result\.globalNavigationRect\.top \+ 1/u)
    assert.doesNotMatch(terminalSource, /result\.chartRect\?\.bottom <= result\.barRect\?\.top \+ 1/u)
  })

  it('fails closed when a required mobile bottom bar or key action is absent', () => {
    const bottomActionSource = source.slice(
      source.indexOf('async function assertMobileBottomActionClearance'),
      source.indexOf('function collectRuntimeErrors')
    )
    const applicabilityGuard = bottomActionSource.slice(0, bottomActionSource.indexOf('await page.evaluate'))
    const requiredCheck = bottomActionSource.slice(bottomActionSource.indexOf('await page.evaluate'))

    assert.match(applicabilityGuard, /if \(!route\.checksMobileBottomAction\)[\s\S]*skipped:\s*true/u)
    assert.doesNotMatch(requiredCheck, /skipped:\s*true/u)
    assert.match(requiredCheck, /assert\(result\.ok,/u)
  })

  it('finds an offscreen key action before scrolling it clear of mobile navigation', () => {
    const bottomActionSource = source.slice(
      source.indexOf('async function assertMobileBottomActionClearance'),
      source.indexOf('function collectRuntimeErrors')
    )
    const candidateFilter = bottomActionSource.slice(
      bottomActionSource.indexOf('const actions ='),
      bottomActionSource.indexOf('const lastActionElement')
    )

    assert.doesNotMatch(candidateFilter, /visibleInViewport/u)
    assert.match(bottomActionSource, /const lastActionElement = actions\.at\(-1\)\?\.element/u)
    assert.match(bottomActionSource, /lastActionElement\.scrollIntoView\(\{ block: 'end' \}\)/u)
  })

  it('does not invent a key-action requirement for read-only authenticated views', () => {
    for (const name of ['dashboard-auth', 'account-assets-auth', 'account-funding-auth', 'account-kyc-auth']) {
      const route = source.match(new RegExp(`\\{[^}]*name:\\s*['"]${name}['"][^}]*\\}`, 'u'))?.[0]
      assert.ok(route, `missing route ${name}`)
      assert.match(route, /checksMobileBottomAction:\s*false/u)
    }
  })

  it('locates migrated surfaces by semantics instead of CSS module class names', () => {
    const queriedSelectors = [...source.matchAll(/\.(?:querySelector(?:All)?|closest|matches)\(\s*(['"`])([\s\S]*?)\1/gu)]
      .map((match) => match[2])
    const forbiddenFragments = [
      '.trade-panel',
      '.mobile-tabs',
      '.main-region',
      '.market-',
      '.user-page',
      '.wallet-',
      '.data-table__card',
      '.table-action',
      '.confirm-dialog',
      '.sidebar'
    ]

    for (const fragment of forbiddenFragments) {
      assert.equal(
        queriedSelectors.some((selector) => selector.includes(fragment)),
        false,
        `visual QA must not query CSS ownership class ${fragment}`
      )
    }
  })

  it('treats the selected markets view and compact login gate as semantic readiness', () => {
    assert.match(source, /const marketPage = document\.getElementById\('markets-title'\)\?\.closest\('section'\)/u)
    assert.match(source, /marketPage\?\.querySelector\('\[role="tablist"\] button\[aria-selected="true"\]'\)/u)

    const loginReadyIndex = source.indexOf("if (readyKind === 'login-required')")
    const minimumTextIndex = source.indexOf('if (textLength < 40)')
    assert.ok(loginReadyIndex >= 0 && loginReadyIndex < minimumTextIndex)
    assert.match(source, /assertPageNonEmpty\(page, route\)/u)
    assert.match(source, /route\.ready === 'login-required' && result\.loginStateReady/u)
  })

  it('opens the markets overview and proves the active platform collection before capture', () => {
    const routeCheckSource = source.slice(
      source.indexOf('async function runRouteCheck'),
      source.indexOf('async function runRuntimeResizeCheck')
    )
    const overviewSource = source.slice(
      source.indexOf('async function openMarketsOverview'),
      source.indexOf('async function assertPageNonEmpty')
    )

    assert.match(routeCheckSource, /route\.ready === 'markets'\s*\? await openMarketsOverview\(page, viewport\)/u)
    assert.match(routeCheckSource, /marketOverview,/u)
    assert.match(overviewSource, /\[data-market-page-tab="overview"\]/u)
    assert.match(overviewSource, /viewport\.width <= 900 \? 'mobile-list' : 'pc-table'/u)
    assert.match(overviewSource, /\[data-market-collection="\$\{expectedCollection\}"\]/u)
    assert.match(routeCheckSource, /await resetViewportScroll\(page\)[\s\S]*route\.ready === 'markets'[\s\S]*await focusMarketsCollection\(page\)/u)
    assert.match(overviewSource, /collection\.scrollIntoView\(\{ block: 'start' \}\)/u)
  })

  it('keeps policy-disabled order modification safe while exercising cancellation', () => {
    assert.match(source, /modifyDisabled: modify instanceof HTMLButtonElement && modify\.disabled/u)
    assert.match(source, /assert\(\s*actions\.modifyDisabled && actions\.modifyReason && actions\.cancelEnabled/u)
    assert.doesNotMatch(source, /Current order lacks real actions/u)
    assert.match(source, /return \{ modifyPath: false, cancelPath: true \}/u)
  })

  it('opens the trade sheet from the rendered mobile terminal and waits for its visible aria contract', () => {
    const runtimeSource = source.slice(
      source.indexOf('async function runRuntimeResizeCheck'),
      source.indexOf('async function resetViewportScroll')
    )

    assert.match(runtimeSource, /document\.querySelectorAll\('\[data-platform-view="mobile"\]'\)/u)
    assert.match(runtimeSource, /element\.querySelector\('canvas'\)/u)
    assert.match(runtimeSource, /querySelectorAll\(':scope > button'\)\.length === 3/u)
    assert.match(runtimeSource, /input\.closest\('\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby\]'\)/u)
    assert.match(runtimeSource, /dialog\.getAttribute\('aria-labelledby'\)/u)
    assert.match(runtimeSource, /document\.getElementById\(labelledBy\)/u)
    assert.match(runtimeSource, /input\.closest\('\[aria-hidden="true"\], \[inert\]'\)/u)
    assert.doesNotMatch(runtimeSource, /mobile-order-sheet-title/u)
  })

  it('waits for the submitted order result through the live dialog aria contract', () => {
    const interactionSource = source.slice(
      source.indexOf('async function assertTradingInteractions'),
      source.indexOf('async function assertTradingAppearanceMatrix')
    )

    assert.match(interactionSource, /\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby\] \[role="status"\]/u)
    assert.doesNotMatch(interactionSource, /mobile-order-sheet-title/u)
  })

  it('selects a different routable market and verifies drawer, route and model state', () => {
    const interactionSource = source.slice(
      source.indexOf('async function assertTradingInteractions'),
      source.indexOf('async function assertTradingAppearanceMatrix')
    )
    const marketMockSource = source.slice(
      source.indexOf('function marketSymbols'),
      source.indexOf('function symbolPayload')
    )

    assert.match(interactionSource, /const targetSymbol = 'ETHUSDT'/u)
    assert.match(interactionSource, /const expectedPath = '\/trade\/spot\/ETHUSDT'/u)
    assert.match(interactionSource, /window\.location\.pathname === expectedPath/u)
    assert.match(interactionSource, /getAttribute\('data-open'\) === 'false'/u)
    assert.match(interactionSource, /candidate\.textContent\?\.trim\(\) === selectedSymbol/u)
    assert.match(interactionSource, /selectedMarket: targetSymbol/u)
    assert.doesNotMatch(interactionSource, /selectedBtc|Markets drawer could not select BTCUSDT|markets drawer BTC search result/u)

    assert.match(marketMockSource, /symbolPayload\('ETHUSDT', 'Ethereum \/ Tether', 'crypto', 'CRYPTO_SPOT'/u)
    assert.match(source, /normalized\.endsWith\('-PERP'\)[\s\S]*'LINEAR_PERP'[\s\S]*normalized\.endsWith\('USDT'\)[\s\S]*'CRYPTO_SPOT'/u)
    assert.match(source, /asset: 'ETH',[\s\S]*available: '2\.50'/u)
  })

  it('serves coherent authoritative depth for the alternate spot market', () => {
    const interactionSource = source.slice(
      source.indexOf('async function assertTradingInteractions'),
      source.indexOf('async function assertTradingAppearanceMatrix')
    )
    const marketSource = source.slice(
      source.indexOf('function visualMarketSource'),
      source.indexOf('function marketQuote')
    )
    const quoteSource = source.slice(
      source.indexOf('function marketQuote'),
      source.indexOf('function visualCandles')
    )
    const orderBookSource = source.slice(
      source.indexOf('function visualOrderBook'),
      source.indexOf('function visualRecentTrades')
    )
    const tradesSource = source.slice(
      source.indexOf('function visualRecentTrades'),
      source.indexOf('function marketPrice')
    )

    assert.match(interactionSource, /!element\.closest\('\[aria-hidden="true"\], \[inert\]'\)/u)
    assert.match(interactionSource, /filter\(\(button\) => \/\\d\/\.test\(button\.textContent \?\? ''\)\)\.length > 4/u)
    assert.match(interactionSource, /quoteDrawer\?\.numericRows > 4/u)
    assert.match(marketSource, /providerCode: 'visual-qa'[\s\S]*providerSymbol:[\s\S]*sourceMode: 'PUBLIC_EXTERNAL'/u)
    assert.match(marketSource, /asOf:[\s\S]*expiresAt:[\s\S]*stale: false/u)
    assert.match(quoteSource, /\.\.\.visualMarketSource\(normalizedSymbol\)/u)
    assert.match(orderBookSource, /\.\.\.visualMarketSource\(normalizedSymbol\)/u)
    assert.match(tradesSource, /const marketSource = visualMarketSource\(normalizedSymbol\)/u)
    assert.match(tradesSource, /\.\.\.marketSource/u)
    assert.match(orderBookSource, /normalizedSymbol\.includes\('USDT'\) \? 0\.1/u)
  })

  it('uses the real theme ids and waits for the document appearance contract', () => {
    const appearanceSource = source.slice(
      source.indexOf('async function assertTradingAppearanceMatrix'),
      source.indexOf('async function assertAccountActionPaths')
    )

    assert.match(appearanceSource, /const themes = \['binance-inspired', 'minimal-white'\]/u)
    assert.doesNotMatch(appearanceSource, /terminal-pro/u)
    assert.match(appearanceSource, /document\.documentElement\.lang === nextLanguage/u)
    assert.match(appearanceSource, /document\.documentElement\.dataset\.theme === nextTheme/u)
    assert.match(appearanceSource, /document\.documentElement\.dataset\.colorScheme === expectedColorScheme/u)
    assert.match(appearanceSource, /localStorage\.setItem\('fx-trading-theme-mode', 'binance-inspired'\)/u)
    assert.match(appearanceSource, /metrics\.fixedNavigationCount === 1 && metrics\.globalTabsCount === 1/u)
    assert.doesNotMatch(appearanceSource, /metrics\.globalTabsCount === 0/u)
  })

  it('keeps the keyboard-sized trade form scrollable through its submit action', () => {
    const interactionSource = source.slice(
      source.indexOf('async function assertTradingInteractions'),
      source.indexOf('async function assertTradingAppearanceMatrix')
    )

    assert.match(interactionSource, /keyboardViewport\.submitPresent/u)
    assert.match(interactionSource, /keyboardViewport\.scrollable/u)
    assert.match(interactionSource, /scrollContainer\.scrollTop = scrollContainer\.scrollHeight/u)
    assert.match(interactionSource, /submit\.scrollIntoView\(\{ block: 'end' \}\)/u)
    assert.match(interactionSource, /keyboardSubmit\.submitVisible/u)
    assert.match(interactionSource, /keyboardSubmit\.inputValue === '0\.01'/u)
    assert.doesNotMatch(interactionSource, /assert\(keyboardViewport\.submitVisible/u)
  })
})

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}
