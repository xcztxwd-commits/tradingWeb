import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://localhost:5173'
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const runId = safeName(process.env.VISUAL_QA_RUN_ID ?? new Date().toISOString().replace(/[:.]/g, '-'))
const artifactRoot = resolve(process.env.VISUAL_QA_ARTIFACT_ROOT ?? join(tmpdir(), 'fx-trading-visual-qa'), runId)
const screenshotsDir = join(artifactRoot, 'screenshots')
const reportPath = join(artifactRoot, 'report.json')
const runtimeResizeWidths = [901, 900, 899, 901]

const routes = [
  { path: '/', ready: 'home', checksMobileBottomAction: true },
  { path: '/trade', expectedPath: '/trade/spot/BTCUSDT', name: 'trade-redirect', ready: 'trading', trading: true, checksMobileBottomAction: false },
  { path: '/trading', expectedPath: '/trade/spot/BTCUSDT', name: 'trading-redirect', ready: 'trading', trading: true, checksMobileBottomAction: false },
  { path: '/trade/invalid/BTCUSDT', expectedPath: '/trade/spot/BTCUSDT', name: 'trade-invalid-redirect', ready: 'trading', trading: true, checksMobileBottomAction: false },
  { path: '/trade/spot/BTCUSDT', name: 'spot-guest', ready: 'trading', trading: true, checksMobileBottomAction: false, allViewports: true },
  { path: '/trade/spot/BTCUSDT', name: 'spot-auth', ready: 'trading', trading: true, auth: true, checksMobileBottomAction: false, allViewports: true },
  { path: '/trade/perpetual/BTCUSDT-PERP', name: 'perpetual-guest', ready: 'trading', trading: true, checksMobileBottomAction: false, allViewports: true },
  { path: '/trade/perpetual/BTCUSDT-PERP', name: 'perpetual-auth', ready: 'trading', trading: true, auth: true, checksMobileBottomAction: false, allViewports: true },
  { path: '/markets', ready: 'markets', checksMobileBottomAction: true },
  { path: '/orders', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/orders', name: 'orders-auth', ready: 'orders-auth', auth: true, checksMobileBottomAction: true },
  { path: '/positions', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/positions', name: 'positions-auth', ready: 'positions-auth', auth: true, checksMobileBottomAction: true },
  { path: '/wallet', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/wallet', name: 'wallet-auth', ready: 'wallet-auth', auth: true, checksMobileBottomAction: true },
  { path: '/dashboard', name: 'dashboard-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/dashboard', name: 'dashboard-auth', ready: 'user-page', auth: true, checksMobileBottomAction: false },
  { path: '/account', expectedPath: '/account/overview', name: 'account-redirect', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/overview', name: 'account-overview-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/overview', name: 'account-overview-auth', ready: 'user-page', auth: true, checksMobileBottomAction: true },
  { path: '/account/assets', name: 'account-assets-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/assets', name: 'account-assets-auth', ready: 'user-page', auth: true, checksMobileBottomAction: false },
  { path: '/account/orders/funding', name: 'account-funding-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/orders/funding', name: 'account-funding-auth', ready: 'user-page', auth: true, checksMobileBottomAction: false },
  { path: '/account/orders/trades', name: 'account-trades-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/orders/trades', name: 'account-trades-auth', ready: 'user-page', auth: true, checksMobileBottomAction: true },
  { path: '/account/security/kyc', name: 'account-kyc-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/security/kyc', name: 'account-kyc-auth', ready: 'user-page', auth: true, checksMobileBottomAction: false },
  { path: '/account/settings', name: 'account-settings-guest', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/account/settings', name: 'account-settings-auth', ready: 'user-page', auth: true, checksMobileBottomAction: true },
  { path: '/settings', ready: 'user-page', checksMobileBottomAction: true },
  { path: '/security', ready: 'user-page', checksMobileBottomAction: true },
  { path: '/login', ready: 'auth-form', checksMobileBottomAction: false },
  { path: '/register', ready: 'auth-form', checksMobileBottomAction: false },
  { path: '/forgot-password', ready: 'auth-support', checksMobileBottomAction: false },
  { path: '/two-factor-help', ready: 'auth-support', checksMobileBottomAction: false },
  { path: '/visual-qa-unknown', expectedPath: '/', name: 'fallback-redirect', ready: 'home', checksMobileBottomAction: true }
]

const viewports = [
  { name: 'desktop-1440x900', width: 1440, height: 900, deviceScaleFactor: 1, mobile: false },
  { name: 'mobile-390x844', width: 390, height: 844, deviceScaleFactor: 2, mobile: true },
  { name: 'mobile-375x667', width: 375, height: 667, deviceScaleFactor: 2, mobile: true, tradingOnly: true },
  { name: 'mobile-412x915', width: 412, height: 915, deviceScaleFactor: 2, mobile: true, tradingOnly: true },
  { name: 'landscape-844x390', width: 844, height: 390, deviceScaleFactor: 2, mobile: true, tradingOnly: true },
  { name: 'landscape-915x412', width: 915, height: 412, deviceScaleFactor: 2, mobile: true, tradingOnly: true },
  { name: 'boundary-899x844', width: 899, height: 844, deviceScaleFactor: 1, mobile: false },
  { name: 'boundary-900x844', width: 900, height: 844, deviceScaleFactor: 1, mobile: false },
  { name: 'boundary-901x844', width: 901, height: 844, deviceScaleFactor: 1, mobile: false }
]

const processes = []
const results = []
const screenshotPaths = []

await mkdir(screenshotsDir, { recursive: true })

try {
  await ensureWebServer()
  console.log('[visual-qa] web server ready')

  const chrome = await launchChrome()
  console.log('[visual-qa] headless browser ready')
  const page = await createCdpPage(chrome.port)
  await page.send('Page.enable')
  await page.send('Network.enable')
  await page.send('Runtime.enable')
  await page.send('Fetch.enable', { patterns: [{ urlPattern: '*' }] })
  await installBrowserShims(page)
  installApiMocks(page)
  console.log('[visual-qa] browser instrumentation ready')

  for (const viewport of viewports) {
    await setViewport(page, viewport)
    for (const route of routes) {
      if (viewport.tradingOnly && !route.allViewports) continue
      console.log(`[visual-qa] ${viewport.name} ${route.name ?? route.path}`)
      results.push(await runRouteCheck(page, route, viewport))
    }
  }
  console.log('[visual-qa] runtime resize continuity')
  results.push(await runRuntimeResizeCheck(page))
  await page.close()
} finally {
  for (const child of processes.reverse()) {
    killProcessTree(child)
  }
}

const failures = results.filter((result) => result.status === 'FAIL')
const report = {
  runId,
  webBaseUrl,
  status: failures.length === 0 ? 'PASS' : 'FAIL',
  generatedAt: new Date().toISOString(),
  artifactRoot,
  reportPath,
  screenshots: screenshotPaths,
  summary: {
    routes: routes.length,
    viewports: viewports.map(({ name, width, height }) => ({ name, width, height })),
    checks: results.length,
    passed: results.length - failures.length,
    failed: failures.length
  },
  results
}

await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')

console.log(
  JSON.stringify(
    {
      runId,
      webBaseUrl,
      status: report.status,
      reportPath,
      screenshots: screenshotPaths,
      summary: report.summary
    },
    null,
    2
  )
)

if (failures.length > 0) {
  process.exit(1)
}

process.exit(0)

async function runRouteCheck(page, route, viewport) {
  const startedAt = Date.now()
  const routeName = safeName(route.name ?? (route.path.replace(/^\//, '') || 'root'))
  const screenshotPath = join(screenshotsDir, `${viewport.name}-${routeName}.png`)
  const errors = []

  const diagnostics = collectRuntimeErrors(page)
  try {
    await clearBrowserSession(page)
    if (route.auth) {
      await page.evaluate(() => {
        localStorage.setItem('fx-platform-auth-token', 'visual-qa-token')
        localStorage.setItem('fx-platform-user-email', 'visual-qa@example.com')
      })
    }
    await page.navigate(buildRouteUrl(route.path))
    await waitForRouteReady(page, route)
    const marketOverview =
      route.ready === 'markets'
        ? await openMarketsOverview(page, viewport)
        : { skipped: true, reason: 'route is not the markets page' }
    await sleep(350)

    const nonEmpty = await assertPageNonEmpty(page, route)
    const platformView = await assertPlatformView(page, viewport)
    const positionAlgorithmSamples =
      route.ready === 'positions-auth'
        ? await assertPositionAlgorithmSamples(page)
        : { skipped: true, reason: 'route does not render authenticated positions' }
    const overlay = await assertNoFrameworkOverlay(page)
    const horizontalScroll = await assertNoBodyHorizontalScroll(page)
    const mobileBottomAction =
      viewport.width <= 900
        ? await assertMobileBottomActionClearance(page, route)
        : { skipped: true, reason: 'desktop viewport has no mobile bottom bar requirement' }
    const tradingTerminal =
      route.trading
        ? await assertTradingTerminalContract(page, viewport)
        : { skipped: true, reason: 'route is not the trading terminal' }
    const tradingInteractions =
      (route.name === 'spot-guest' || route.name === 'spot-auth') && viewport.width === 390 && viewport.height === 844
        ? await assertTradingInteractions(page, route, viewport)
        : { skipped: true, reason: 'interactive trading smoke runs at 390x844' }
    const appearanceMatrix =
      route.name === 'spot-guest' && viewport.width === 390 && viewport.height === 844
        ? await assertTradingAppearanceMatrix(page, route)
        : { skipped: true, reason: 'theme, locale, and text-scale checks run on the guest 390x844 terminal' }
    const accountActions =
      viewport.width === 390 && viewport.height === 844 && (route.ready === 'orders-auth' || route.ready === 'positions-auth')
        ? await assertAccountActionPaths(page, route)
        : { skipped: true, reason: 'authenticated account action smoke runs at 390x844' }

    await resetViewportScroll(page)
    if (route.ready === 'markets') await focusMarketsCollection(page)
    const consoleHealth = assertNoConsoleErrors(diagnostics.errors)
    await captureScreenshot(page, screenshotPath)
    screenshotPaths.push(screenshotPath)

    return {
      route: route.path,
      viewport: viewport.name,
      status: 'PASS',
      durationMs: Date.now() - startedAt,
      screenshotPath,
      checks: {
        nonEmpty,
        platformView,
        marketOverview,
        consoleHealth,
        positionAlgorithmSamples,
        overlay,
        horizontalScroll,
        mobileBottomAction,
        tradingTerminal,
        tradingInteractions,
        appearanceMatrix,
        accountActions
      }
    }
  } catch (error) {
    errors.push(error instanceof Error ? error.message : String(error))
    await captureScreenshot(page, screenshotPath).catch(() => undefined)
    if (existsSync(screenshotPath)) screenshotPaths.push(screenshotPath)

    return {
      route: route.path,
      viewport: viewport.name,
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      screenshotPath: existsSync(screenshotPath) ? screenshotPath : null,
      errors,
      consoleErrors: diagnostics.errors
    }
  } finally {
    diagnostics.dispose()
  }
}

async function runRuntimeResizeCheck(page) {
  const startedAt = Date.now()
  const screenshotPath = join(screenshotsDir, 'runtime-resize-901-900-899-901.png')
  const diagnostics = collectRuntimeErrors(page)

  try {
    await setViewport(page, { width: 901, height: 844, deviceScaleFactor: 1, mobile: false })
    await page.navigate(buildRouteUrl('/login'))
    await sleep(120)
    await clearBrowserSession(page)
    await page.evaluate(() => {
      localStorage.setItem('fx-platform-auth-token', 'visual-qa-token')
      localStorage.setItem('fx-platform-user-email', 'visual-qa@example.com')
    })

    const route = {
      path: '/trade/spot/BTCUSDT',
      name: 'runtime-resize',
      ready: 'trading',
      trading: true,
      auth: true,
      checksMobileBottomAction: false
    }
    await page.navigate(buildRouteUrl(route.path))
    await waitForRouteReady(page, route)
    await sleep(250)

    const steps = []
    let controllerSentinel = null
    for (const width of runtimeResizeWidths) {
      await setViewport(page, { width, height: 844, deviceScaleFactor: 1, mobile: false })
      const expectedPlatform = width <= 900 ? 'mobile' : 'pc'
      await page.waitForFunction(
        (expected) => {
          const opposite = expected === 'mobile' ? 'pc' : 'mobile'
          return document.querySelectorAll(`[data-platform-view="${expected}"]`).length > 0 &&
            document.querySelectorAll(`[data-platform-view="${opposite}"]`).length === 0 &&
            Boolean(document.querySelector('[data-controller-sentinel] canvas'))
        },
        `runtime resize ${width}px platform ready`,
        expectedPlatform
      )
      await sleep(140)

      const platformView = await assertPlatformView(page, { width })
      const horizontalScroll = await assertNoBodyHorizontalScroll(page)
      const consoleHealth = assertNoConsoleErrors(diagnostics.errors)
      assert(platformView.controllerSentinel, `Resize step ${width}px lost controller sentinel`)
      if (controllerSentinel === null) controllerSentinel = platformView.controllerSentinel
      assert(
        platformView.controllerSentinel === controllerSentinel,
        `Resize step ${width}px rebuilt the trading controller: ${JSON.stringify({ controllerSentinel, platformView })}`
      )
      steps.push({ width, platformView, horizontalScroll, consoleHealth })

      if (width === 900) {
        const opened = await page.evaluate(() => {
          const visible = (element) => {
            if (!(element instanceof HTMLElement)) return false
            const style = window.getComputedStyle(element)
            const rect = element.getBoundingClientRect()
            return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
          }
          const mobileTerminal = [...document.querySelectorAll('[data-platform-view="mobile"]')]
            .find((element) => visible(element) && element.querySelector('canvas'))
          const actionBar = [...(mobileTerminal?.querySelectorAll('nav[aria-label]') ?? [])]
            .find((element) => visible(element) &&
              window.getComputedStyle(element).position === 'fixed' &&
              element.querySelectorAll(':scope > button').length === 3)
          const tradeButton = actionBar?.querySelector(':scope > button:nth-of-type(2)')
          if (!(tradeButton instanceof HTMLButtonElement)) return false
          tradeButton.click()
          return true
        })
        assert(opened, 'Runtime resize could not open the mobile trade sheet')
        await page.waitForFunction(
          () => {
            const visible = (element) => {
              if (!(element instanceof HTMLElement)) return false
              const style = window.getComputedStyle(element)
              const rect = element.getBoundingClientRect()
              return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
            }
            const input = [...document.querySelectorAll('[data-platform-view="mobile"] section[data-price-precision] input:not(:disabled)')]
              .find((element) => visible(element))
            if (!(input instanceof HTMLInputElement) || input.closest('[aria-hidden="true"], [inert]')) return false
            const dialog = input.closest('[role="dialog"][aria-modal="true"][aria-labelledby]')
            if (!(dialog instanceof HTMLElement) || !visible(dialog)) return false
            const labelledBy = dialog.getAttribute('aria-labelledby')
            const label = labelledBy ? document.getElementById(labelledBy) : null
            return Boolean(label && dialog.contains(label))
          },
          'runtime resize mobile trade sheet ready'
        )
        const inputValue = await page.evaluate(() => {
          const visible = (element) => {
            if (!(element instanceof HTMLElement)) return false
            const style = window.getComputedStyle(element)
            const rect = element.getBoundingClientRect()
            return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
          }
          const input = [...document.querySelectorAll('[data-platform-view="mobile"] section[data-price-precision] input:not(:disabled)')]
            .find((element) => visible(element))
          if (!(input instanceof HTMLInputElement) || input.closest('[aria-hidden="true"], [inert]')) return null
          const dialog = input.closest('[role="dialog"][aria-modal="true"][aria-labelledby]')
          if (!(dialog instanceof HTMLElement) || !visible(dialog)) return null
          const labelledBy = dialog.getAttribute('aria-labelledby')
          const label = labelledBy ? document.getElementById(labelledBy) : null
          if (!label || !dialog.contains(label)) return null
          const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
          valueSetter?.call(input, '0.123')
          input.dispatchEvent(new Event('input', { bubbles: true }))
          return input.value
        })
        assert(inputValue === '0.123', `Runtime resize trade value was not accepted: ${inputValue}`)
      }
    }

    const retainedTradeValue = await page.evaluate(() => {
      const visibleInputs = [...document.querySelectorAll('[data-platform-view="pc"] section[data-price-precision] input')]
        .filter((element) => element instanceof HTMLInputElement && element.getBoundingClientRect().height > 0)
      return visibleInputs.some((input) => input.value === '0.123')
    })
    assert(retainedTradeValue, 'Trading form state did not survive mobile -> PC resize')
    await captureScreenshot(page, screenshotPath)
    screenshotPaths.push(screenshotPath)

    return {
      route: route.path,
      viewport: 'runtime-901-900-899-901',
      status: 'PASS',
      durationMs: Date.now() - startedAt,
      screenshotPath,
      checks: { controllerSentinel, retainedTradeValue, steps }
    }
  } catch (error) {
    await captureScreenshot(page, screenshotPath).catch(() => undefined)
    if (existsSync(screenshotPath)) screenshotPaths.push(screenshotPath)
    return {
      route: '/trade/spot/BTCUSDT',
      viewport: 'runtime-901-900-899-901',
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      screenshotPath: existsSync(screenshotPath) ? screenshotPath : null,
      errors: [error instanceof Error ? error.message : String(error)],
      consoleErrors: diagnostics.errors
    }
  } finally {
    diagnostics.dispose()
  }
}

async function resetViewportScroll(page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0)
    const main = document.querySelector('main')
    if (main instanceof HTMLElement) main.scrollTop = 0
  })
  await sleep(100)
}

function buildRouteUrl(path) {
  const url = new URL(path, webBaseUrl)
  url.searchParams.set('visualQaRun', runId)
  return url.href
}

async function waitForRouteReady(page, route) {
  await page.waitForFunction(
    (expectedPath, readyKind) => {
      if (window.location.pathname !== expectedPath) return false
      if (document.querySelector('vite-error-overlay')) return true

      if (readyKind === 'login-required') {
        return Boolean(document.querySelector('[data-state-variant="login"] button'))
      }

      const textLength = (document.body.textContent ?? '').trim().length
      if (textLength < 40) return false

      if (readyKind === 'markets') {
        const marketPage = document.getElementById('markets-title')?.closest('section')
        const marketPageText = (marketPage?.textContent ?? '').replace(/\s+/g, '').trim()
        return !marketPage?.querySelector('[data-state-variant="loading"], [data-state-variant="error"]') &&
          marketPageText.length > 40 &&
          Boolean(marketPage?.querySelector('[role="tablist"] button[aria-selected="true"]'))
      }
      if (readyKind === 'home') {
        const main = document.querySelector('main')
        return Boolean(document.querySelector('#home-hero-title')) &&
          Boolean(main?.querySelector('a[href="/markets"]')) &&
          Boolean(main?.querySelector('a[href="/trading"]'))
      }
      if (readyKind === 'trading') {
        return Boolean(document.querySelector('[data-controller-sentinel] canvas'))
      }
      if (readyKind === 'wallet-auth') {
        const assets = document.querySelector('#wallet-assets')
        return Boolean(assets?.querySelector('tbody tr, [role="list"] > [role="listitem"]'))
      }
      if (readyKind === 'orders-auth') {
        return Boolean(document.querySelector('main [role="tablist"]')) &&
          Boolean(document.querySelector('main input[placeholder="BTCUSDT"]')) &&
          Boolean(document.querySelector('[data-order-id]'))
      }
      if (readyKind === 'positions-auth') {
        return Boolean(document.querySelector('main [role="tablist"]')) &&
          Boolean(document.querySelector('main input[placeholder="EURUSD"]')) &&
          Boolean(document.querySelector('[data-position-id]'))
      }
      if (readyKind === 'auth-form') {
        return Boolean(document.querySelector('form button[type="submit"]'))
      }
      if (readyKind === 'auth-support') {
        const titleId = `${expectedPath.slice(1)}-title`
        return Boolean(document.getElementById(titleId)) &&
          Boolean(document.querySelector('main a[href="/login"]')) &&
          Boolean(document.querySelector('form button[type="submit"]'))
      }
      return Boolean(document.querySelector('main h1'))
    },
    `route ready ${route.path}`,
    route.expectedPath ?? route.path,
    route.ready
  )
}

async function openMarketsOverview(page, viewport) {
  await page.evaluate(() => {
    const overview = document.querySelector('[data-market-page-tab="overview"]')
    if (!(overview instanceof HTMLButtonElement)) throw new Error('Markets overview tab is missing')
    overview.click()
  })

  const expectedCollection = viewport.width <= 900 ? 'mobile-list' : 'pc-table'
  await page.waitForFunction(
    (expectedCollection) => {
      const collection = document.querySelector(`[data-market-collection="${expectedCollection}"]`)
      return collection instanceof HTMLElement && collection.getBoundingClientRect().height > 0
    },
    `markets overview ${expectedCollection} ready`,
    expectedCollection
  )

  return { expectedCollection, rendered: true }
}

async function focusMarketsCollection(page) {
  await page.evaluate(() => {
    const collection = document.querySelector('[data-market-collection]')
    if (!(collection instanceof HTMLElement)) throw new Error('Markets collection is missing before capture')
    collection.scrollIntoView({ block: 'start' })
  })
  await sleep(100)
}

async function assertPageNonEmpty(page, route) {
  const result = await page.evaluate(() => {
    const root = document.querySelector('#root') ?? document.body
    const rect = root.getBoundingClientRect()
    const visibleElementCount = [...document.querySelectorAll('main, section, form, table, button, a, input, select')]
      .filter((element) => {
        const style = window.getComputedStyle(element)
        const box = element.getBoundingClientRect()
        return style.visibility !== 'hidden' && style.display !== 'none' && box.width > 0 && box.height > 0
      }).length

    return {
      textLength: (root.textContent ?? '').replace(/\s+/g, '').length,
      width: rect.width,
      height: rect.height,
      visibleElementCount,
      loginStateReady: Boolean(root.querySelector('[data-state-variant="login"] button'))
    }
  })

  assert(
    result.textLength > 40 || (route.ready === 'login-required' && result.loginStateReady),
    `Expected meaningful page text, got ${result.textLength} characters`
  )
  assert(result.width > 0 && result.height > 0, `Expected visible root dimensions, got ${result.width}x${result.height}`)
  assert(result.visibleElementCount > 3, `Expected visible UI elements, got ${result.visibleElementCount}`)
  return result
}

async function assertPositionAlgorithmSamples(page) {
  const text = await page.evaluate(() => (document.body.textContent ?? '').replace(/\s+/g, ' ').trim())
  const expected = [
    'EURUSD',
    'BTCUSDT',
    'BTCUSD',
    '1.10002',
    '1.10100',
    '98.00',
    '91.00',
    '978.011',
    '4953.00',
    '0.01799091',
    '0.02000000'
  ]
  const missing = expected.filter((item) => !text.includes(item))
  assert(missing.length === 0, `Missing position algorithm samples: ${missing.join(', ')}`)
  return { matched: expected.length, expected }
}

async function assertNoFrameworkOverlay(page) {
  const result = await page.evaluate(() => {
    const selectors = [
      'vite-error-overlay',
      'nextjs-portal',
      '[data-nextjs-dialog-overlay]',
      'webpack-dev-server-client-overlay',
      'iframe#webpack-dev-server-client-overlay'
    ]
    const matches = selectors.flatMap((selector) =>
      [...document.querySelectorAll(selector)].map((element) => ({
        selector,
        text: element.textContent?.slice(0, 240) ?? ''
      }))
    )
    return { matches }
  })

  assert(result.matches.length === 0, `Framework overlay detected: ${JSON.stringify(result.matches)}`)
  return { detected: false }
}

async function assertNoBodyHorizontalScroll(page) {
  const result = await page.evaluate(() => {
    const doc = document.documentElement
    const body = document.body
    const viewportWidth = window.innerWidth
    const docOverflow = Math.max(0, doc.scrollWidth - Math.max(doc.clientWidth, viewportWidth))
    const bodyOverflow = Math.max(0, body.scrollWidth - Math.max(body.clientWidth, viewportWidth))
    return {
      viewportWidth,
      documentScrollWidth: doc.scrollWidth,
      bodyScrollWidth: body.scrollWidth,
      documentClientWidth: doc.clientWidth,
      bodyClientWidth: body.clientWidth,
      overflowPx: Math.max(docOverflow, bodyOverflow)
    }
  })

  assert(result.overflowPx <= 1, `Body has horizontal overflow: ${JSON.stringify(result)}`)
  return result
}

async function assertTradingTerminalContract(page, viewport) {
  await page.waitForFunction(
    () => Boolean(document.querySelector('[data-controller-sentinel] canvas')),
    'trading route controller and chart ready'
  )
  const result = await page.evaluate((expectedMobile) => {
    const isVisible = (element) => {
      if (!(element instanceof HTMLElement)) return false
      const style = window.getComputedStyle(element)
      const rect = element.getBoundingClientRect()
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
    }
    const expectedPlatform = expectedMobile ? 'mobile' : 'pc'
    const oppositePlatform = expectedMobile ? 'pc' : 'mobile'
    const platformRoots = [...document.querySelectorAll(`[data-platform-view="${expectedPlatform}"]`)]
    const terminal = platformRoots.find((element) => element.querySelector('canvas')) ?? null
    const oppositeTerminalCount = [...document.querySelectorAll(`[data-platform-view="${oppositePlatform}"]`)]
      .filter((element) => element.querySelector('canvas')).length
    const chart = terminal?.querySelector('canvas') ?? null
    const fixedNavigation = [...(terminal?.querySelectorAll('nav') ?? [])]
      .find((element) => isVisible(element) && window.getComputedStyle(element).position === 'fixed') ?? null
    const terminalShell = fixedNavigation?.closest('section[aria-label]') ?? null
    const globalNavigation = document.querySelector('nav[aria-label="Mobile navigation"]')
    const tradePanel = [...(terminal?.querySelectorAll('section[aria-label]') ?? [])]
      .find((element) => isVisible(element) && Boolean(element.querySelector('section[data-price-precision]'))) ?? null
    const actionButtons = fixedNavigation?.querySelectorAll('button') ?? []
    const chartRect = chart instanceof HTMLElement ? chart.getBoundingClientRect() : null
    const barRect = fixedNavigation instanceof HTMLElement ? fixedNavigation.getBoundingClientRect() : null
    const globalNavigationRect = globalNavigation instanceof HTMLElement && isVisible(globalNavigation)
      ? globalNavigation.getBoundingClientRect()
      : null
    const route = document.querySelector('[data-controller-sentinel]')
    const pathname = window.location.pathname
    const expectedSymbol = pathname.includes('/perpetual/') ? 'BTCUSDT-PERP' : 'BTCUSDT'

    return {
      expectedMobile,
      platform: expectedPlatform,
      platformRootCount: platformRoots.length,
      oppositeTerminalCount,
      controllerSentinel: route?.getAttribute('data-controller-sentinel') ?? null,
      selectedSymbolVisible: (terminal?.textContent ?? '').includes(expectedSymbol),
      expectedSymbol,
      fixedNavigationCount: fixedNavigation ? 1 : 0,
      tradePanelVisible: Boolean(tradePanel),
      actionButtonCount: actionButtons.length,
      chartRect: chartRect && { top: chartRect.top, bottom: chartRect.bottom, width: chartRect.width, height: chartRect.height },
      barRect: barRect && { top: barRect.top, bottom: barRect.bottom, height: barRect.height },
      globalNavigationRect: globalNavigationRect && {
        top: globalNavigationRect.top,
        bottom: globalNavigationRect.bottom,
        height: globalNavigationRect.height
      },
      terminalPaddingBottom: terminalShell instanceof HTMLElement
        ? Number.parseFloat(window.getComputedStyle(terminalShell).paddingBottom)
        : 0,
      viewportHeight: window.innerHeight,
      viewportWidth: window.innerWidth,
      documentScrollWidth: document.documentElement.scrollWidth
    }
  }, viewport.width <= 900)

  assert(
    result.documentScrollWidth <= result.viewportWidth + 1,
    `Trading terminal has horizontal overflow: ${JSON.stringify(result)}`
  )
  assert(result.controllerSentinel, `Trading controller sentinel is missing: ${JSON.stringify(result)}`)
  assert(result.platformRootCount > 0, `Trading platform root is missing: ${JSON.stringify(result)}`)
  assert(result.oppositeTerminalCount === 0, `Opposite trading platform is mounted: ${JSON.stringify(result)}`)
  assert(result.selectedSymbolVisible, `Selected trading symbol is not visible: ${JSON.stringify(result)}`)
  assert(result.chartRect?.width > 0 && result.chartRect?.height > 0, `Trading chart is not visible: ${JSON.stringify(result)}`)
  if (result.expectedMobile) {
    assert(result.fixedNavigationCount === 1, `Expected exactly one mobile trading bar: ${JSON.stringify(result)}`)
    assert(result.actionButtonCount === 3, `Expected Markets / Trade / Depth actions: ${JSON.stringify(result)}`)
    assert(result.chartRect?.height >= 300, `Expected an actionable chart at least 300px tall: ${JSON.stringify(result)}`)
    assert(result.chartRect?.top < result.viewportHeight, `Chart must enter the first viewport: ${JSON.stringify(result)}`)
    assert(result.barRect?.height >= 48, `Trading bar must meet the mobile height contract: ${JSON.stringify(result)}`)
    assert(
      result.terminalPaddingBottom >= result.viewportHeight - result.barRect.top,
      `Trading terminal does not reserve enough scroll space for the fixed bar: ${JSON.stringify(result)}`
    )
    assert(result.globalNavigationRect, `Global mobile navigation is missing: ${JSON.stringify(result)}`)
    assert(
      result.barRect.bottom <= result.globalNavigationRect.top + 1,
      `Trading bar overlaps global mobile navigation: ${JSON.stringify(result)}`
    )
  } else {
    assert(result.fixedNavigationCount === 0, `Desktop terminal must not expose a mobile trading bar: ${JSON.stringify(result)}`)
    assert(result.tradePanelVisible, `Desktop trade panel should be visible: ${JSON.stringify(result)}`)
  }
  return result
}

async function assertPlatformView(page, viewport) {
  const expectedPlatform = viewport.width <= 900 ? 'mobile' : 'pc'
  const result = await page.evaluate((expected) => {
    const opposite = expected === 'mobile' ? 'pc' : 'mobile'
    return {
      expected,
      expectedCount: document.querySelectorAll(`[data-platform-view="${expected}"]`).length,
      oppositeCount: document.querySelectorAll(`[data-platform-view="${opposite}"]`).length,
      controllerSentinel: document.querySelector('[data-controller-sentinel]')?.getAttribute('data-controller-sentinel') ?? null
    }
  }, expectedPlatform)

  assert(result.expectedCount > 0, `Expected mounted ${expectedPlatform} view: ${JSON.stringify(result)}`)
  assert(result.oppositeCount === 0, `Opposite platform view must not be mounted: ${JSON.stringify(result)}`)
  return result
}

async function assertTradingInteractions(page, route, viewport) {
  const targetSymbol = 'ETHUSDT'
  const expectedPath = '/trade/spot/ETHUSDT'

  const openQuickAction = async (index) => {
    const clicked = await page.evaluate((actionIndex) => {
      const visible = (element) => {
        if (!(element instanceof HTMLElement)) return false
        const style = window.getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
      }
      const navigation = [...document.querySelectorAll('main nav')]
        .find((element) => visible(element) && window.getComputedStyle(element).position === 'fixed')
      const button = navigation?.querySelectorAll('button')[actionIndex]
      if (!(button instanceof HTMLButtonElement)) return false
      button.click()
      return true
    }, index)
    assert(clicked, `Trading quick action ${index} could not be opened`)
  }

  const closeOpenDialog = async () => {
    const closed = await page.evaluate(() => {
      const dialogs = [...document.querySelectorAll('[role="dialog"]')]
      const dialog = dialogs.find((element) => {
        const rect = element.getBoundingClientRect()
        const style = window.getComputedStyle(element)
        return !element.closest('[aria-hidden="true"], [inert]') &&
          rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'
      })
      const closeButton = dialog?.querySelector('header button, button[aria-label]')
      if (!(closeButton instanceof HTMLButtonElement)) return false
      closeButton.click()
      return true
    })
    assert(closed, 'Open mobile dialog did not expose a close button')
    await page.waitForFunction(
      () => ![...document.querySelectorAll('[role="dialog"]')].some((element) => {
        const rect = element.getBoundingClientRect()
        const style = window.getComputedStyle(element)
        return !element.closest('[aria-hidden="true"], [inert]') &&
          rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'
      }),
      'mobile dialog closed'
    )
  }

  await openQuickAction(0)
  await page.waitForFunction(
    () => [...document.querySelectorAll('[role="dialog"] input[placeholder="Search symbol"]')].some((element) =>
      element instanceof HTMLInputElement && !element.closest('[aria-hidden="true"], [inert]') && element.getBoundingClientRect().height > 0
    ),
    'markets drawer search ready'
  )
  const marketDrawer = await page.evaluate(() => {
    const input = [...document.querySelectorAll('[role="dialog"] input[placeholder="Search symbol"]')]
      .find((element) => element instanceof HTMLInputElement && !element.closest('[aria-hidden="true"], [inert]') && element.getBoundingClientRect().height > 0)
    if (!(input instanceof HTMLInputElement)) return null
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    valueSetter?.call(input, 'ETH')
    input.dispatchEvent(new Event('input', { bubbles: true }))
    const rect = input.getBoundingClientRect()
    return { inputHeight: rect.height, fontSize: Number.parseFloat(window.getComputedStyle(input).fontSize) }
  })
  assert(marketDrawer?.inputHeight >= 44, `Markets search target is too small: ${JSON.stringify(marketDrawer)}`)
  assert(marketDrawer?.fontSize >= 16, `Markets search font must avoid mobile zoom: ${JSON.stringify(marketDrawer)}`)
  await page.waitForFunction(
    (selectedSymbol) => {
      const input = [...document.querySelectorAll('[role="dialog"] input[placeholder="Search symbol"]')]
        .find((element) => element instanceof HTMLInputElement && !element.closest('[aria-hidden="true"], [inert]'))
      return (input?.closest('[role="dialog"]')?.textContent ?? '').includes(selectedSymbol)
    },
    'markets drawer alternate search result',
    targetSymbol
  )
  const selectedMarket = await page.evaluate((selectedSymbol) => {
    const input = [...document.querySelectorAll('[role="dialog"] input[placeholder="Search symbol"]')]
      .find((element) => element instanceof HTMLInputElement && !element.closest('[aria-hidden="true"], [inert]'))
    const dialog = input?.closest('[role="dialog"]')
    const button = [...(dialog?.querySelectorAll('button') ?? [])]
      .find((button) => !button.hasAttribute('aria-label') &&
        [...button.querySelectorAll('span')].some((candidate) => candidate.textContent?.trim() === selectedSymbol))
    if (!(button instanceof HTMLButtonElement)) return null
    const previousPath = window.location.pathname
    button.click()
    return { previousPath }
  }, targetSymbol)
  assert(selectedMarket && selectedMarket.previousPath !== expectedPath, `Markets drawer did not start from a different market: ${JSON.stringify(selectedMarket)}`)
  await page.waitForFunction(
    (expectedPath, selectedSymbol) => {
      const searchInput = document.querySelector('input[placeholder="Search symbol"]')
      const drawer = searchInput?.closest('[data-open]')
      const terminal = [...document.querySelectorAll('[data-platform-view="mobile"]')]
        .find((element) => element.querySelector('canvas'))
      const selected = [...(terminal?.querySelectorAll('strong') ?? [])]
        .some((candidate) => candidate.textContent?.trim() === selectedSymbol)
      return drawer?.getAttribute('data-open') === 'false' &&
        drawer.getAttribute('aria-hidden') === 'true' &&
        window.location.pathname === expectedPath &&
        selected
    },
    'markets drawer selection closed with route and model updated',
    expectedPath,
    targetSymbol
  )

  await openQuickAction(2)
  await page.waitForFunction(
    (selectedSymbol) => {
      const dialog = [...document.querySelectorAll('[role="dialog"]')]
        .find((element) => !element.closest('[aria-hidden="true"], [inert]') &&
          element.getBoundingClientRect().height > 0 && (element.textContent ?? '').includes(selectedSymbol))
      return Boolean(dialog) && [...dialog.querySelectorAll('button')].filter((button) => /\d/.test(button.textContent ?? '')).length > 4
    },
    'quote drawer live depth ready',
    targetSymbol
  )
  const quoteDrawer = await page.evaluate((selectedSymbol) => {
    const dialog = [...document.querySelectorAll('[role="dialog"]')]
      .find((element) => !element.closest('[aria-hidden="true"], [inert]') &&
        element.getBoundingClientRect().height > 0 && (element.textContent ?? '').includes(selectedSymbol))
    if (!(dialog instanceof HTMLElement)) return null
    const numericButtons = [...dialog.querySelectorAll('button')].filter((button) => /\d/.test(button.textContent ?? ''))
    const rect = dialog.getBoundingClientRect()
    return { numericRows: numericButtons.length, width: rect.width, height: rect.height }
  }, targetSymbol)
  assert(quoteDrawer?.numericRows > 4, `Quote drawer did not render readable real depth rows: ${JSON.stringify(quoteDrawer)}`)
  await closeOpenDialog()

  await openQuickAction(1)
  if (!route.auth) {
    await page.waitForFunction(
      () => Boolean(document.querySelector('[role="dialog"] #trading-login-title')),
      'guest trade login gate'
    )
    const guestGate = await page.evaluate(() => {
      const dialog = document.getElementById('trading-login-title')?.closest('[role="dialog"]')
      const tradeForms = dialog?.querySelectorAll('section[data-price-precision]').length ?? 0
      return {
        loginActions: tradeForms === 0 ? dialog?.querySelectorAll('button').length ?? 0 : 0,
        tradeForms
      }
    })
    assert(guestGate.loginActions >= 2, `Guest trade action did not expose login guidance: ${JSON.stringify(guestGate)}`)
    await closeOpenDialog()
    return { marketSearch: true, selectedMarket: targetSymbol, quoteDepth: true, guestLoginGate: true, authenticatedTrade: false }
  }

  await page.waitForFunction(
    () => Boolean(document.querySelector('[role="dialog"] section[data-price-precision] > button[aria-label]:not(:disabled)')),
    'authenticated trade sheet ready'
  )
  const switched = await page.evaluate(() => {
    const panel = [...document.querySelectorAll('[role="dialog"] section[aria-label]')]
      .find((element) => element.querySelector('section[data-price-precision]'))
    const tablists = panel?.querySelectorAll('[role="tablist"]') ?? []
    const orderTabs = tablists[0]?.querySelectorAll('button') ?? []
    const sideTabs = tablists[1]?.querySelectorAll('button') ?? []
    if (!(orderTabs[1] instanceof HTMLButtonElement) || !(sideTabs[1] instanceof HTMLButtonElement)) return false
    orderTabs[1].click()
    sideTabs[1].click()
    return true
  })
  assert(switched, 'Trade sheet could not switch to sell market order')
  await page.waitForFunction(
    () => [...document.querySelectorAll('[role="dialog"] section[data-price-precision]')].some((section) => {
      const rect = section.getBoundingClientRect()
      return rect.width > 0 && rect.height > 0 && Boolean(section.querySelector('input:not(:disabled)'))
    }),
    'sell market amount field'
  )
  const inputMetrics = await page.evaluate(() => {
    const visible = (element) => element instanceof HTMLElement && element.getBoundingClientRect().height > 0
    const side = [...document.querySelectorAll('[role="dialog"] section[data-price-precision]')].find(visible)
    const input = [...(side?.querySelectorAll('input:not(:disabled)') ?? [])].find(visible)
    if (!(input instanceof HTMLInputElement)) return null
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    valueSetter?.call(input, '0.01')
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.focus()
    const rect = input.getBoundingClientRect()
    return { value: input.value, height: rect.height, fontSize: Number.parseFloat(window.getComputedStyle(input).fontSize) }
  })
  assert(inputMetrics?.value === '0.01', `Trade input did not accept quantity: ${JSON.stringify(inputMetrics)}`)
  assert(inputMetrics?.height >= 44 && inputMetrics?.fontSize >= 16, `Trade input violates mobile sizing: ${JSON.stringify(inputMetrics)}`)

  await setViewport(page, { ...viewport, height: 500 })
  await sleep(350)
  const keyboardViewport = await page.evaluate(() => {
    const visibleInViewport = (element) => {
      if (!(element instanceof HTMLElement)) return false
      const rect = element.getBoundingClientRect()
      const style = window.getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0 && rect.bottom <= window.innerHeight + 1 && rect.top >= -1
    }
    const side = [...document.querySelectorAll('[role="dialog"] section[data-price-precision]')]
      .find((element) => element.getBoundingClientRect().height > 0)
    const input = [...(side?.querySelectorAll('input:not(:disabled)') ?? [])].find(visibleInViewport)
    const submit = [...(side?.querySelectorAll(':scope > button[aria-label]') ?? [])].find((element) => {
      if (!(element instanceof HTMLElement)) return false
      const rect = element.getBoundingClientRect()
      const style = window.getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
    })
    let scrollContainer = side?.parentElement ?? null
    while (scrollContainer) {
      const style = window.getComputedStyle(scrollContainer)
      if (/auto|scroll/u.test(style.overflowY) && scrollContainer.scrollHeight > scrollContainer.clientHeight + 1) break
      scrollContainer = scrollContainer.parentElement
    }
    return {
      viewportHeight: window.innerHeight,
      inputVisible: visibleInViewport(input),
      submitPresent: submit instanceof HTMLElement,
      scrollable: scrollContainer instanceof HTMLElement
    }
  })
  assert(keyboardViewport.inputVisible, `Focused field disappeared in keyboard-sized viewport: ${JSON.stringify(keyboardViewport)}`)
  assert(keyboardViewport.submitPresent, `Submit action is missing from keyboard-sized trade form: ${JSON.stringify(keyboardViewport)}`)
  assert(keyboardViewport.scrollable, `Keyboard-sized trade form is not scrollable: ${JSON.stringify(keyboardViewport)}`)

  const scrolledToSubmit = await page.evaluate(() => {
    const side = [...document.querySelectorAll('[role="dialog"] section[data-price-precision]')]
      .find((element) => element.getBoundingClientRect().height > 0)
    const submit = side?.querySelector(':scope > button[aria-label]')
    let scrollContainer = side?.parentElement ?? null
    while (scrollContainer) {
      const style = window.getComputedStyle(scrollContainer)
      if (/auto|scroll/u.test(style.overflowY) && scrollContainer.scrollHeight > scrollContainer.clientHeight + 1) break
      scrollContainer = scrollContainer.parentElement
    }
    if (!(submit instanceof HTMLElement) || !(scrollContainer instanceof HTMLElement)) return false
    scrollContainer.scrollTop = scrollContainer.scrollHeight
    submit.scrollIntoView({ block: 'end' })
    return true
  })
  assert(scrolledToSubmit, 'Keyboard-sized trade form could not scroll to its submit action')
  await sleep(120)

  const keyboardSubmit = await page.evaluate(() => {
    const visibleInViewport = (element) => {
      if (!(element instanceof HTMLElement)) return false
      const rect = element.getBoundingClientRect()
      const style = window.getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0 && rect.bottom <= window.innerHeight + 1 && rect.top >= -1
    }
    const side = [...document.querySelectorAll('[role="dialog"] section[data-price-precision]')]
      .find((element) => element.getBoundingClientRect().height > 0)
    const input = side?.querySelector('input:not(:disabled)')
    const submit = side?.querySelector(':scope > button[aria-label]')
    return {
      submitVisible: visibleInViewport(submit),
      inputValue: input instanceof HTMLInputElement ? input.value : null
    }
  })
  assert(keyboardSubmit.submitVisible, `Submit action is unreachable in keyboard-sized viewport: ${JSON.stringify(keyboardSubmit)}`)
  assert(keyboardSubmit.inputValue === '0.01', `Trade quantity changed while reaching submit: ${JSON.stringify(keyboardSubmit)}`)

  await setViewport(page, viewport)
  await resetViewportScroll(page)
  await sleep(200)

  const submitClicked = await page.evaluate(() => {
    const submit = [...document.querySelectorAll('[role="dialog"] section[data-price-precision] > button[aria-label]')].find((button) => {
      const rect = button.getBoundingClientRect()
      return rect.width > 0 && rect.height > 0 && !button.disabled
    })
    if (!(submit instanceof HTMLButtonElement)) return false
    submit.click()
    return true
  })
  assert(submitClicked, 'Authenticated market order could not reach validation/confirmation')
  await page.waitForFunction(
    () => Boolean(document.querySelector('[role="dialog"][aria-label]')),
    'order confirmation dialog'
  )
  const confirmed = await page.evaluate(() => {
    const button = document.querySelector('[role="dialog"][aria-label] footer button:last-of-type')
    if (!(button instanceof HTMLButtonElement) || button.disabled) return false
    button.click()
    return true
  })
  assert(confirmed, 'Order confirmation did not expose an enabled confirm action')
  await page.waitForFunction(
    () => !document.querySelector('[role="dialog"][aria-label]') &&
      Boolean(document.querySelector('[role="dialog"][aria-modal="true"][aria-labelledby] [role="status"]')),
    'mocked order submission result'
  )
  await closeOpenDialog()
  return {
    marketSearch: true,
    selectedMarket: targetSymbol,
    quoteDepth: true,
    guestLoginGate: false,
    authenticatedTrade: true,
    keyboardViewport
  }
}

async function assertTradingAppearanceMatrix(page, route) {
  const languages = ['zh-CN', 'en-US', 'ja-JP']
  const themes = ['binance-inspired', 'minimal-white']
  const expectedActions = {
    'zh-CN': ['行情', '交易', '深度'],
    'en-US': ['Markets', 'Trade', 'Depth'],
    'ja-JP': ['相場', '取引', '板深度']
  }
  const cases = []

  for (const language of languages) {
    for (const theme of themes) {
      await page.evaluate((nextLanguage, nextTheme) => {
        localStorage.setItem('fx-trader-language', nextLanguage)
        localStorage.setItem('fx-trading-theme-mode', nextTheme)
      }, language, theme)

      const url = new URL(buildRouteUrl(route.path))
      url.searchParams.set('appearanceCase', `${language}-${theme}`)
      await page.navigate(url.href)
      await waitForRouteReady(page, route)
      const expectedColorScheme = theme === 'minimal-white' ? 'light' : 'dark'
      await page.waitForFunction(
        (nextLanguage, nextTheme, expectedColorScheme) =>
          document.documentElement.lang === nextLanguage &&
          document.documentElement.dataset.theme === nextTheme &&
          document.documentElement.dataset.colorScheme === expectedColorScheme,
        'appearance document state',
        language,
        theme,
        expectedColorScheme
      )

      const metrics = await page.evaluate(() => {
        const visible = (element) => {
          if (!(element instanceof HTMLElement)) return false
          const rect = element.getBoundingClientRect()
          const style = window.getComputedStyle(element)
          return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'
        }
        const fixedNavigation = [...document.querySelectorAll('main nav')]
          .find((element) => visible(element) && window.getComputedStyle(element).position === 'fixed')
        const rootStyle = window.getComputedStyle(document.documentElement)
        return {
          language: document.documentElement.lang,
          theme: document.documentElement.dataset.theme,
          colorScheme: document.documentElement.dataset.colorScheme,
          actionLabels: [...(fixedNavigation?.querySelectorAll('button') ?? [])].map((button) => button.textContent?.trim() ?? ''),
          fixedNavigationCount: [...document.querySelectorAll('main nav')]
            .filter((element) => visible(element) && window.getComputedStyle(element).position === 'fixed').length,
          globalTabsCount: [...document.querySelectorAll('nav[aria-label="Mobile navigation"]')].filter(visible).length,
          documentWidth: document.documentElement.scrollWidth,
          viewportWidth: window.innerWidth,
          background: rootStyle.getPropertyValue('--theme-background').trim(),
          surface: rootStyle.getPropertyValue('--theme-surface').trim(),
          text: rootStyle.getPropertyValue('--theme-text-primary').trim(),
          overlay: Boolean(document.querySelector('vite-error-overlay'))
        }
      })

      assert(metrics.language === language, `Locale did not apply: ${JSON.stringify({ language, theme, metrics })}`)
      assert(metrics.theme === theme, `Theme did not apply: ${JSON.stringify({ language, theme, metrics })}`)
      assert(metrics.colorScheme === expectedColorScheme, `Theme color scheme mismatch: ${JSON.stringify(metrics)}`)
      assert(JSON.stringify(metrics.actionLabels) === JSON.stringify(expectedActions[language]), `Localized action labels are mixed or incomplete: ${JSON.stringify({ language, metrics })}`)
      assert(metrics.fixedNavigationCount === 1 && metrics.globalTabsCount === 1, `Trading navigation layers regressed: ${JSON.stringify(metrics)}`)
      assert(metrics.documentWidth <= metrics.viewportWidth, `Appearance case overflowed horizontally: ${JSON.stringify(metrics)}`)
      assert(metrics.background && metrics.surface && metrics.text && metrics.background !== metrics.text, `Theme surfaces are incomplete: ${JSON.stringify(metrics)}`)
      assert(!metrics.overlay, `Appearance case rendered a framework overlay: ${JSON.stringify(metrics)}`)
      cases.push({ language, theme, metrics })
    }
  }

  await page.evaluate(() => {
    localStorage.setItem('fx-trader-language', 'zh-CN')
    localStorage.setItem('fx-trading-theme-mode', 'binance-inspired')
  })
  await page.navigate(buildRouteUrl(route.path))
  await waitForRouteReady(page, route)
  await page.waitForFunction(
    () => document.documentElement.lang === 'zh-CN' &&
      document.documentElement.dataset.theme === 'binance-inspired' &&
      document.documentElement.dataset.colorScheme === 'dark',
    'text scale baseline appearance'
  )

  const textScales = []
  for (const textScale of [125, 150]) {
    await page.evaluate((scale) => {
      document.documentElement.style.setProperty('-webkit-text-size-adjust', `${scale}%`)
      document.documentElement.style.setProperty('text-size-adjust', `${scale}%`)
    }, textScale)
    await sleep(160)

    const metrics = await page.evaluate(() => {
      const visible = (element) => {
        if (!(element instanceof HTMLElement)) return false
        const rect = element.getBoundingClientRect()
        const style = window.getComputedStyle(element)
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'
      }
      const fixedNavigation = [...document.querySelectorAll('main nav')]
        .find((element) => visible(element) && window.getComputedStyle(element).position === 'fixed')
      const controls = [...(fixedNavigation?.querySelectorAll('button') ?? [])]
      const clippedControls = controls
        .filter((element) => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
        .map((element) => element.textContent?.trim() ?? element.getAttribute('aria-label') ?? element.tagName)
      return {
        appliedTextSizeAdjust: window.getComputedStyle(document.documentElement).webkitTextSizeAdjust,
        clippedControls,
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: window.innerWidth,
        actionHeights: controls.map((element) => element.getBoundingClientRect().height)
      }
    })

    assert(metrics.appliedTextSizeAdjust === `${textScale}%`, `Text scale did not apply: ${JSON.stringify({ textScale, metrics })}`)
    assert(metrics.documentWidth <= metrics.viewportWidth, `Text scale overflowed horizontally: ${JSON.stringify({ textScale, metrics })}`)
    assert(metrics.clippedControls.length === 0, `Text scale clipped a trading action: ${JSON.stringify({ textScale, metrics })}`)
    assert(metrics.actionHeights.length === 3 && metrics.actionHeights.every((height) => height >= 44), `Text scale reduced a touch target: ${JSON.stringify({ textScale, metrics })}`)
    textScales.push({ textScale, metrics })
  }

  await page.evaluate(() => {
    document.documentElement.style.removeProperty('-webkit-text-size-adjust')
    document.documentElement.style.removeProperty('text-size-adjust')
  })

  return { cases, textScales }
}

async function assertAccountActionPaths(page, route) {
  if (route.ready === 'orders-auth') {
    const actions = await page.evaluate(() => {
      const row = document.querySelector('[data-order-id="order-btc-working"]')
      const buttons = row?.querySelectorAll(':scope > button') ?? []
      const modify = buttons[1]
      const cancel = buttons[2]
      return {
        modifyDisabled: modify instanceof HTMLButtonElement && modify.disabled,
        modifyReason: modify instanceof HTMLButtonElement ? modify.title.trim() : '',
        cancelEnabled: cancel instanceof HTMLButtonElement && !cancel.disabled
      }
    })
    assert(
      actions.modifyDisabled && actions.modifyReason && actions.cancelEnabled,
      `Current order action policy mismatch: ${JSON.stringify(actions)}`
    )
    await page.evaluate(() => document.querySelector('[data-order-id="order-btc-working"]')?.querySelectorAll(':scope > button')[2]?.click())
    await page.waitForFunction(
      () => Boolean(document.querySelector('[role="dialog"][aria-labelledby="order-cancel-title"]')),
      'cancel order confirmation'
    )
    await page.evaluate(() => document.querySelector('[role="dialog"][aria-labelledby="order-cancel-title"] button')?.click())
    await page.waitForFunction(
      () => !document.querySelector('[role="dialog"][aria-labelledby="order-cancel-title"]') &&
        ['\u8ba2\u5355\u5df2\u64a4\u9500', 'Order canceled', '\u6ce8\u6587\u3092\u53d6\u6d88\u3057\u307e\u3057\u305f']
          .some((message) => (document.body.textContent ?? '').includes(message)),
      'cancel order result'
    )
    return { modifyPath: false, cancelPath: true }
  }

  const closeAction = await page.evaluate(() => {
    const button = document.querySelector('[data-position-id="position-eurusd-long"]')?.querySelectorAll(':scope > button')[1]
    if (!(button instanceof HTMLButtonElement) || button.disabled) return false
    button.click()
    return true
  })
  assert(closeAction, 'Current position lacks an enabled close path')
  await page.waitForFunction(
    () => {
      const dialog = document.querySelector('[role="dialog"][aria-label]')
      return Boolean(dialog) && (dialog?.querySelectorAll('p').length ?? 0) >= 2
    },
    'close position confirmation'
  )
  await page.evaluate(() => document.querySelector('[role="dialog"][aria-label] button')?.click())
  await page.waitForFunction(
    () => !document.querySelector('[role="dialog"][aria-label]') &&
      ['\u6301\u4ed3\u5df2\u5e73\u4ed3', 'Position closed', '\u30dd\u30b8\u30b7\u30e7\u30f3\u3092\u6c7a\u6e08\u3057\u307e\u3057\u305f']
        .some((message) => (document.body.textContent ?? '').includes(message)),
    'close position result'
  )
  return { closeConfirmation: true, closePath: true }
}

function assertNoConsoleErrors(errors) {
  assert(errors.length === 0, `Console errors detected: ${JSON.stringify(errors)}`)
  return { errors: 0 }
}

async function assertMobileBottomActionClearance(page, route) {
  if (!route.checksMobileBottomAction) {
    return { skipped: true, reason: 'route does not require main-content action clearance' }
  }

  await page.evaluate(() => {
    window.scrollTo(0, document.documentElement.scrollHeight)
    const main = document.querySelector('main')
    if (main) main.scrollTop = main.scrollHeight
  })
  await sleep(150)

  const result = await page.evaluate(() => {
    const bottomBar = document.querySelector('nav[aria-label="Mobile navigation"]')
    if (!(bottomBar instanceof HTMLElement)) {
      return { skipped: false, ok: false, reason: 'mobile bottom bar was not found' }
    }

    const bottomStyle = window.getComputedStyle(bottomBar)
    const bottomRect = bottomBar.getBoundingClientRect()
    if (bottomStyle.display === 'none' || bottomRect.height <= 0) {
      return { skipped: false, ok: false, reason: 'mobile bottom bar is hidden' }
    }

    const main = document.querySelector('main') ?? document.body
    const actions = [...main.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
      .filter((element) => {
        if (bottomBar.contains(element) || element.closest('aside')) return false
        if (element.closest('details:not([open])')) return false
        for (let current = element.parentElement; current && current !== main; current = current.parentElement) {
          const position = window.getComputedStyle(current).position
          if (position === 'fixed' || position === 'sticky') return false
        }
        const style = window.getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        const visibleInViewport = rect.bottom > 0 && rect.top < window.innerHeight
        const visibleText = (element.textContent ?? '').replace(/\s+/g, '').trim()
        const ariaText = (element.getAttribute('aria-label') ?? '').replace(/\s+/g, '').trim()
        if (visibleText && !/\p{L}/u.test(visibleText)) return false
        const labelText = ariaText || visibleText
        const hasReadableLabel = /\p{L}/u.test(labelText)
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width >= 4 && rect.height >= 4 && visibleInViewport && hasReadableLabel
      })
      .map((element) => {
        const rect = element.getBoundingClientRect()
        return {
          tagName: element.tagName.toLowerCase(),
          text: (element.textContent ?? element.getAttribute('aria-label') ?? '').replace(/\s+/g, ' ').trim().slice(0, 120),
          href: element instanceof HTMLAnchorElement ? element.getAttribute('href') : null,
          rect: {
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom,
            left: rect.left,
            width: rect.width,
            height: rect.height
          },
          documentTop: rect.top + window.scrollY + (main instanceof HTMLElement ? main.scrollTop : 0)
        }
      })
      .sort((left, right) => left.documentTop - right.documentTop || left.rect.left - right.rect.left)

    const lastAction = actions.at(-1)
    if (!lastAction) {
      return { skipped: false, ok: false, reason: 'no key action found in main content' }
    }

    const clearancePx = bottomRect.top - lastAction.rect.bottom
    return {
      skipped: false,
      ok: clearancePx >= 4,
      clearancePx,
      bottomBarTop: bottomRect.top,
      bottomBarHeight: bottomRect.height,
      lastAction
    }
  })

  assert(result.ok, `Mobile bottom action clearance failed: ${JSON.stringify(result)}`)
  return result
}

function collectRuntimeErrors(page) {
  const errors = []
  const onConsole = (event) => {
    if (event.type !== 'error') return
    errors.push({
      source: 'console',
      text: event.args?.map(formatRemoteObject).filter(Boolean).join(' ') || 'console.error'
    })
  }
  const onException = (event) => {
    errors.push({
      source: 'exception',
      text: event.exceptionDetails?.exception?.description ?? event.exceptionDetails?.text ?? 'Runtime exception'
    })
  }

  page.on('Runtime.consoleAPICalled', onConsole)
  page.on('Runtime.exceptionThrown', onException)

  return {
    errors,
    dispose() {
      page.off('Runtime.consoleAPICalled', onConsole)
      page.off('Runtime.exceptionThrown', onException)
    }
  }
}

function formatRemoteObject(object) {
  if (!object) return ''
  if (typeof object.value !== 'undefined') return String(object.value)
  return object.description ?? object.type ?? ''
}

async function captureScreenshot(page, screenshotPath) {
  const result = await page.send('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
    captureBeyondViewport: false
  })
  await writeFile(screenshotPath, Buffer.from(result.data, 'base64'))
}

async function clearBrowserSession(page) {
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  }).catch(() => undefined)
}

async function setViewport(page, viewport) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: viewport.deviceScaleFactor,
    mobile: viewport.mobile
  })
  await page.send('Emulation.setTouchEmulationEnabled', {
    enabled: viewport.mobile
  })
}

async function installBrowserShims(page) {
  await page.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `
      (() => {
        class SmokeWebSocket extends EventTarget {
          static CONNECTING = 0
          static OPEN = 1
          static CLOSING = 2
          static CLOSED = 3

          constructor(url, protocols) {
            super()
            this.url = String(url)
            this.protocol = Array.isArray(protocols) ? protocols[0] ?? '' : protocols ?? ''
            this.readyState = SmokeWebSocket.CONNECTING
            this.bufferedAmount = 0
            this.extensions = ''
            this.binaryType = 'blob'
            setTimeout(() => {
              if (this.readyState !== SmokeWebSocket.CONNECTING) return
              this.readyState = SmokeWebSocket.OPEN
              const event = new Event('open')
              this.onopen?.(event)
              this.dispatchEvent(event)
            }, 0)
          }

          send() {}

          close(code = 1000, reason = '') {
            if (this.readyState === SmokeWebSocket.CLOSED) return
            this.readyState = SmokeWebSocket.CLOSED
            const event = typeof CloseEvent === 'function'
              ? new CloseEvent('close', { code, reason, wasClean: true })
              : new Event('close')
            this.onclose?.(event)
            this.dispatchEvent(event)
          }
        }

        Object.assign(SmokeWebSocket.prototype, {
          CONNECTING: SmokeWebSocket.CONNECTING,
          OPEN: SmokeWebSocket.OPEN,
          CLOSING: SmokeWebSocket.CLOSING,
          CLOSED: SmokeWebSocket.CLOSED
        })

        window.WebSocket = SmokeWebSocket
      })()
    `
  })
}

function installApiMocks(page) {
  page.on('Fetch.requestPaused', async (event) => {
    try {
      const url = new URL(event.request.url)
      if (url.pathname === '/api/auth/session') {
        const authenticated = requestHasAuthToken(event.request)
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            status: authenticated ? 'valid_token' : 'guest',
            authenticated,
            userId: authenticated ? 'visual-user' : null,
            email: authenticated ? 'visual-qa@example.com' : null,
            role: authenticated ? 'USER' : null,
            loginPath: '/login'
          }
        })
        return
      }

      if (url.pathname === '/api/accounts') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: [accountSummary()]
        })
        return
      }

      if (url.pathname === '/api/accounts/visual-account/summary') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: accountSummary()
        })
        return
      }

      if (url.pathname === '/api/public/home-counters') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            users: 148320,
            activeTraders: 18240,
            dailyTrades: 932810,
            metricCards: []
          }
        })
        return
      }

      if (url.pathname === '/api/market/binance/overview-source') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualBinanceOverviewSource()
        })
        return
      }

      if (url.pathname === '/api/market/binance/futures-dashboard-source') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualBinanceFuturesSource()
        })
        return
      }

      if (url.pathname === '/api/market/favorites' || /^\/api\/market\/favorites\/[^/]+$/.test(url.pathname)) {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: []
        })
        return
      }

      if (url.pathname === '/api/accounts/visual-account/wallet-balances') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualWalletBalances()
        })
        return
      }

      if (url.pathname === '/api/accounts/visual-account/asset-ledger') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualAssetLedgerEntries()
        })
        return
      }

      if (url.pathname === '/api/accounts/visual-account/trading-settings') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualTradingSettings()
        })
        return
      }

      if (url.pathname === '/api/accounts/visual-account/position-mode') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualTradingSettings()
        })
        return
      }

      if (/^\/api\/accounts\/visual-account\/symbols\/[^/]+\/settings$/.test(url.pathname)) {
        const symbol = decodeURIComponent(url.pathname.split('/')[5])
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            ...visualTradingSettings(),
            symbols: [visualSymbolSettings(symbol)]
          }
        })
        return
      }

      if (/^\/api\/accounts\/visual-account\/transfers$/.test(url.pathname) && url.searchParams.has('page')) {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPage([])
        })
        return
      }

      if (url.pathname === '/api/trading/orders') {
        const submitted = event.request.method === 'POST'
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: submitted ? visualSubmittedOrder() : visualPage(visualOrders())
        })
        return
      }

      if (/^\/api\/trading\/orders\/[^/]+\/cancel$/.test(url.pathname)) {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { ...visualOrders()[0], id: decodeURIComponent(url.pathname.split('/')[4]), status: 'CANCELED' }
        })
        return
      }

      if (/^\/api\/trading\/orders\/[^/]+$/.test(url.pathname) && event.request.method === 'PATCH') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { ...visualOrders()[0], id: decodeURIComponent(basename(url.pathname)), updatedAt: new Date().toISOString() }
        })
        return
      }

      if (url.pathname === '/api/trading/positions') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPage(visualPositions())
        })
        return
      }

      if (url.pathname === '/api/trading/positions/history') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPage(visualPositionHistory())
        })
        return
      }

      if (url.pathname === '/api/trading/trades' || url.pathname === '/api/trading/funding/settlements') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPage([])
        })
        return
      }

      if (/^\/api\/market\/perpetuals\/[^/]+\/reference$/.test(url.pathname)) {
        const symbol = decodeURIComponent(url.pathname.split('/')[4])
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPerpetualReference(symbol)
        })
        return
      }

      if (/^\/api\/trading\/positions\/[^/]+\/close$/.test(url.pathname)) {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { ...visualPositions()[0], id: decodeURIComponent(url.pathname.split('/')[4]), status: 'CLOSED' }
        })
        return
      }

      if (/^\/api\/trading\/positions\/[^/]+\/protection$/.test(url.pathname)) {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: { ...visualPositions()[0], id: decodeURIComponent(url.pathname.split('/')[4]) }
        })
        return
      }

      if (url.pathname === '/api/ledger') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualLedgerEntries()
        })
        return
      }

      if (url.pathname === '/api/finance/fund-orders') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualFundOrders()
        })
        return
      }

      if (url.pathname === '/api/market/symbols') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: marketSymbols()
        })
        return
      }

      if (/^\/api\/market\/symbols\/[^/]+\/rules$/.test(url.pathname)) {
        const symbol = decodeURIComponent(url.pathname.split('/')[4])
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualMarketRules(symbol)
        })
        return
      }

      if (url.pathname === '/api/market/symbol-rules') {
        const symbols = (url.searchParams.get('symbols') ?? '').split(',').filter(Boolean)
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: symbols.map(visualMarketRules)
        })
        return
      }

      if (url.pathname === '/api/market/status') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: {
            massiveConfigured: false,
            redisCacheEnabled: false,
            quoteStaleMs: 15_000,
            status: 'AVAILABLE',
            demoQuotesEnabled: false,
            sourceMode: 'live',
            providerStatus: 'AVAILABLE'
          }
        })
        return
      }

      if (url.pathname === '/api/market/quotes') {
        const symbols = (url.searchParams.get('symbols') ?? '').split(',').filter(Boolean)
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: Object.fromEntries(symbols.map((symbol) => [symbol, marketQuote(symbol)]))
        })
        return
      }

      if (url.pathname.startsWith('/api/market/quotes/')) {
        const symbol = decodeURIComponent(basename(url.pathname))
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: marketQuote(symbol)
        })
        return
      }

      if (url.pathname === '/api/chart/candles') {
        const symbol = url.searchParams.get('symbol') ?? 'BTCUSDT'
        const timeframe = url.searchParams.get('timeframe') ?? '1m'
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualCandles(symbol, timeframe)
        })
        return
      }

      if (url.pathname.startsWith('/api/market/order-book/')) {
        const symbol = decodeURIComponent(basename(url.pathname))
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualOrderBook(symbol)
        })
        return
      }

      if (url.pathname.startsWith('/api/market/trades/')) {
        const symbol = decodeURIComponent(basename(url.pathname))
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualRecentTrades(symbol)
        })
        return
      }

      await page.send('Fetch.continueRequest', { requestId: event.requestId })
    } catch {
      await page.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => undefined)
    }
  })
}

function requestHasAuthToken(request) {
  const headers = request.headers ?? {}
  const authorization = headers.Authorization ?? headers.authorization ?? ''
  return /^Bearer\s+visual-qa-token$/i.test(String(authorization))
}

async function fulfillJson(page, requestId, body, responseCode = 200) {
  await page.send('Fetch.fulfillRequest', {
    requestId,
    responseCode,
    responseHeaders: [
      { name: 'Content-Type', value: 'application/json; charset=utf-8' },
      { name: 'Cache-Control', value: 'no-store' }
    ],
    body: Buffer.from(JSON.stringify(body), 'utf8').toString('base64')
  })
}

function visualPage(items) {
  return {
    items,
    page: 0,
    size: 100,
    total: items.length,
    totalPages: 1
  }
}

function visualTradingSettings() {
  return {
    accountId: 'visual-account',
    positionMode: 'ONE_WAY',
    symbols: [visualSymbolSettings('BTCUSDT-PERP')]
  }
}

function visualSymbolSettings(symbol) {
  return {
    symbol: normalizeVisualSymbol(symbol),
    leverage: 10,
    marginMode: 'CROSS',
    quantityUnit: 'BASE',
    version: 1,
    maxLeverage: 20
  }
}

function visualPerpetualReference(symbol) {
  const normalizedSymbol = normalizeVisualSymbol(symbol)
  const last = marketPrice(normalizedSymbol)
  return {
    symbol: normalizedSymbol,
    providerSymbol: normalizedSymbol.replace('-PERP', ''),
    providerCode: 'visual-qa',
    sourceMode: 'PUBLIC_EXTERNAL',
    bid: last - 0.6,
    ask: last + 0.6,
    last,
    mark: last - 1.1,
    index: last - 2.4,
    asOf: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    stale: false,
    fundingRate: 0.0001,
    fundingTime: new Date().toISOString(),
    nextFundingTime: new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString(),
    fundingSource: 'visual-qa'
  }
}

function visualBinanceOverviewSource() {
  return {
    products: [
      { s: 'BTCUSDT', st: 'TRADING', b: 'BTC', q: 'USDT', an: 'Bitcoin', qn: 'Tether', o: '67580', h: '69210', l: '67120', c: '68412.4', v: '28450', qv: '1949000000', cs: '19800000' },
      { s: 'ETHUSDT', st: 'TRADING', b: 'ETH', q: 'USDT', an: 'Ethereum', qn: 'Tether', o: '3370', h: '3480', l: '3335', c: '3420', v: '410000', qv: '1400000000', cs: '120000000' },
      { s: 'BNBUSDT', st: 'TRADING', b: 'BNB', q: 'USDT', an: 'BNB', qn: 'Tether', o: '588', h: '607', l: '582', c: '602', v: '920000', qv: '548000000', cs: '145000000' }
    ],
    fearGreed: {
      value: 68,
      label: 'Greed',
      updatedAt: Date.now(),
      source: 'visual-qa'
    }
  }
}

function visualBinanceFuturesSource() {
  const now = Date.now()
  const points = Array.from({ length: 24 }, (_, index) => ({
    timestamp: now - (23 - index) * 300_000,
    ratio: 1.05 + index * 0.002,
    price: 68120 + index * 12
  }))
  return {
    ticker: {
      symbol: 'BTCUSDT',
      highPrice: '69210',
      lastPrice: '68408.2',
      lowPrice: '67120',
      priceChangePercent: '1.24',
      quoteVolume: '1949000000',
      volume: '28450',
      closeTime: now
    },
    openInterest: points.map((point, index) => ({
      sumOpenInterest: String(84000 + index * 120),
      sumOpenInterestValue: String(5_700_000_000 + index * 12_000_000),
      CMCCirculatingSupply: '19800000',
      timestamp: point.timestamp
    })),
    topAccountRatio: points.map((point) => ({ longShortRatio: String(point.ratio), longAccount: '0.52', shortAccount: '0.48', timestamp: point.timestamp })),
    topPositionRatio: points.map((point) => ({ longShortRatio: String(point.ratio + 0.04), longAccount: '0.54', shortAccount: '0.46', timestamp: point.timestamp })),
    globalLongShortRatio: points.map((point) => ({ longShortRatio: String(point.ratio - 0.03), longAccount: '0.51', shortAccount: '0.49', timestamp: point.timestamp })),
    takerBuySell: points.map((point, index) => ({ buySellRatio: String(point.ratio), buyVol: String(980 + index * 8), sellVol: String(920 + index * 7), timestamp: point.timestamp })),
    basis: points.map((point) => ({ futuresPrice: String(point.price), indexPrice: String(point.price - 2.4), basis: '2.4', basisRate: '0.0035', timestamp: point.timestamp })),
    fundingRates: points.slice(-12).map((point) => ({ fundingRate: '0.0001', fundingTime: point.timestamp }))
  }
}

function normalizeVisualSymbol(symbol) {
  const normalized = String(symbol).trim().toUpperCase().replace(/[\/_]/g, '-')
  if (normalized.replaceAll('-', '') === 'BTCUSDTPERP') return 'BTCUSDT-PERP'
  return normalized.replaceAll('-', '')
}

function marketSymbols() {
  return [
    symbolPayload('EURUSD', 'Euro / US Dollar', 'fx', 'FX_MARGIN', 'EUR', 'USD', '0.00001', 5),
    symbolPayload('GBPUSD', 'British Pound / US Dollar', 'fx', 'FX_MARGIN', 'GBP', 'USD', '0.00001', 5),
    symbolPayload('BTCUSDT', 'Bitcoin / Tether', 'crypto', 'CRYPTO_SPOT', 'BTC', 'USDT', '0.10', 2),
    symbolPayload('ETHUSDT', 'Ethereum / Tether', 'crypto', 'CRYPTO_SPOT', 'ETH', 'USDT', '0.10', 2),
    symbolPayload('BTCUSDT-PERP', 'Bitcoin / Tether Perpetual', 'crypto', 'LINEAR_PERP', 'BTC', 'USDT', '0.10', 2),
    symbolPayload('XAUUSD', 'Gold / US Dollar', 'metals', 'FX_MARGIN', 'XAU', 'USD', '0.01', 2),
    symbolPayload('US30USD', 'US 30 Index', 'indices', 'FX_MARGIN', 'US30', 'USD', '0.1', 1)
  ]
}

function symbolPayload(symbol, displayName, assetClass, productType, baseCurrency, quoteCurrency, tickSize, pricePrecision) {
  return {
    symbol,
    displayName,
    assetClass,
    productType,
    baseCurrency,
    quoteCurrency,
    minLot: '0.01',
    tickSize,
    pricePrecision,
    quantityPrecision: 2,
    enabled: true,
    tradable: true,
    quoteEnabled: true,
    chartEnabled: true,
    orderBookEnabled: true,
    provider: 'visual-qa',
    providerSymbol: symbol.replace('-PERP', ''),
    quoteSource: 'visual-qa'
  }
}

function visualMarketRules(symbol) {
  const normalized = normalizeVisualSymbol(symbol)
  const productType = normalized.endsWith('-PERP')
    ? 'LINEAR_PERP'
    : normalized.endsWith('USDT') ? 'CRYPTO_SPOT' : 'FX_MARGIN'
  const marginProduct = productType !== 'CRYPTO_SPOT'
  return {
    symbol: normalized,
    exists: true,
    enabled: true,
    tradable: true,
    quoteEnabled: true,
    chartEnabled: true,
    orderBookEnabled: true,
    orderEnabled: true,
    productType,
    tickSize: normalized.includes('USD') && !normalized.startsWith('BTC') ? '0.00001' : '0.10',
    stepSize: productType === 'CRYPTO_SPOT' ? '0.0001' : '0.01',
    minQty: productType === 'CRYPTO_SPOT' ? '0.0001' : '0.01',
    maxQty: '1000',
    minNotional: '5',
    maxNotional: '10000000',
    minLot: '0.01',
    maxLot: '1000',
    maxLeverage: marginProduct ? 20 : 1,
    defaultLeverage: marginProduct ? 20 : 1,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    contractSize: productType === 'FX_MARGIN' ? '100000' : '1',
    riskTier: 'visual-qa',
    tradingSession: '24x7',
    kycRequirement: 'STANDARD',
    userRiskLevelRestriction: 'NONE'
  }
}

function visualMarketSource(symbol) {
  const now = Date.now()
  return {
    providerCode: 'visual-qa',
    providerSymbol: normalizeVisualSymbol(symbol).replace('-PERP', ''),
    sourceMode: 'PUBLIC_EXTERNAL',
    asOf: new Date(now).toISOString(),
    expiresAt: new Date(now + 60_000).toISOString(),
    stale: false
  }
}

function marketQuote(symbol) {
  const normalizedSymbol = normalizeVisualSymbol(symbol)
  const prices = {
    EURUSD: 1.08421,
    GBPUSD: 1.27331,
    BTCUSDT: 68412.4,
    ETHUSDT: 3420,
    'BTCUSDT-PERP': 68408.2,
    XAUUSD: 2341.22,
    US30USD: 38912.8
  }
  const mid = prices[normalizedSymbol] ?? 100
  const spread = normalizedSymbol.includes('BTCUSDT') ? 1.2 : normalizedSymbol === 'US30USD' ? 1.4 : 0.00012
  return {
    type: 'quote',
    symbol: normalizedSymbol,
    bid: String(mid - spread / 2),
    ask: String(mid + spread / 2),
    mid: String(mid),
    spread: String(spread),
    source: 'visual-qa',
    timestamp: Date.now(),
    changePercent: '1.24',
    high24h: String(mid * 1.012),
    low24h: String(mid * 0.988),
    volume24h: '128430.58',
    ...visualMarketSource(normalizedSymbol)
  }
}

function visualCandles(symbol, timeframe) {
  const basePrice = marketPrice(symbol)
  const intervalMs = timeframe === '1s' ? 1_000 : timeframe.endsWith('m') ? Number.parseInt(timeframe, 10) * 60_000 : 60_000
  const now = Date.now()
  return Array.from({ length: 180 }, (_, index) => {
    const wave = Math.sin(index / 9) * 0.006
    const drift = (index - 90) * 0.00008
    const open = basePrice * (1 + wave + drift)
    const close = basePrice * (1 + Math.sin((index + 1) / 9) * 0.006 + drift + 0.00005)
    const high = Math.max(open, close) * 1.0018
    const low = Math.min(open, close) * 0.9982
    return {
      timestamp: now - (179 - index) * intervalMs,
      open: formatMarketNumber(open),
      high: formatMarketNumber(high),
      low: formatMarketNumber(low),
      close: formatMarketNumber(close),
      volume: String(420 + (index % 18) * 12)
    }
  })
}

function visualOrderBook(symbol) {
  const normalizedSymbol = normalizeVisualSymbol(symbol)
  const mid = marketPrice(normalizedSymbol)
  const step = normalizedSymbol.includes('USDT') ? 0.1 : normalizedSymbol === 'US30USD' ? 0.5 : 0.0001
  return {
    symbol: normalizedSymbol,
    timestamp: Date.now(),
    bids: Array.from({ length: 24 }, (_, index) => ({
      price: formatMarketNumber(mid - step * (index + 1)),
      amount: String((0.42 + index * 0.037).toFixed(6))
    })),
    asks: Array.from({ length: 24 }, (_, index) => ({
      price: formatMarketNumber(mid + step * (index + 1)),
      amount: String((0.36 + index * 0.041).toFixed(6))
    })),
    ...visualMarketSource(normalizedSymbol)
  }
}

function visualRecentTrades(symbol) {
  const normalizedSymbol = normalizeVisualSymbol(symbol)
  const mid = marketPrice(normalizedSymbol)
  const step = normalizedSymbol.includes('BTCUSDT') ? 0.1 : normalizedSymbol === 'US30USD' ? 0.5 : 0.0001
  const marketSource = visualMarketSource(normalizedSymbol)
  return Array.from({ length: 40 }, (_, index) => {
    const side = index % 3 === 0 ? 'sell' : 'buy'
    const direction = side === 'buy' ? 1 : -1
    return {
      id: `${normalizedSymbol}-${index}`,
      symbol: normalizedSymbol,
      price: formatMarketNumber(mid + direction * step * ((index % 8) + 1)),
      amount: String((0.12 + index * 0.009).toFixed(6)),
      side,
      timestamp: Date.now() - index * 2400,
      ...marketSource
    }
  })
}

function marketPrice(symbol) {
  const normalizedSymbol = normalizeVisualSymbol(symbol)
  const prices = {
    EURUSD: 1.08421,
    GBPUSD: 1.27331,
    BTCUSDT: 68412.4,
    ETHUSDT: 3420,
    'BTCUSDT-PERP': 68408.2,
    XAUUSD: 2341.22,
    US30USD: 38912.8
  }
  return prices[normalizedSymbol] ?? 100
}

function formatMarketNumber(value) {
  if (Math.abs(value) >= 1000) return value.toFixed(2)
  if (Math.abs(value) >= 10) return value.toFixed(4)
  return value.toFixed(5)
}

function accountSummary() {
  return {
    id: 'visual-account',
    accountType: 'DEMO',
    baseCurrency: 'USDT',
    balance: '128430.58',
    equity: '130248.91',
    usedMargin: '18420.30',
    freeMargin: '110010.28',
    marginLevel: '707.08',
    leverage: 20,
    status: 'ACTIVE'
  }
}

function visualOrders() {
  return [
    {
      id: 'order-btc-working',
      accountId: 'visual-account',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      status: 'PENDING',
      lots: '0.45',
      quantity: '0.45',
      price: '68280.00',
      executionPrice: null,
      filledQuantity: '0.10',
      remainingQuantity: '0.35',
      avgFillPrice: '68272.40',
      fee: '1.28',
      slippage: null,
      holdAmount: '23898.00',
      holdCurrency: 'USDT',
      rejectCode: null,
      rejectMessage: null,
      createdAt: '2026-06-14T09:12:00.000Z',
      updatedAt: '2026-06-14T09:17:00.000Z',
      filledAt: null,
      canceledAt: null
    },
    {
      id: 'order-eth-filled',
      accountId: 'visual-account',
      symbol: 'ETHUSDT',
      side: 'SELL',
      orderType: 'MARKET',
      status: 'FILLED',
      lots: '2.00',
      quantity: '2.00',
      price: null,
      executionPrice: '3428.60',
      filledQuantity: '2.00',
      remainingQuantity: '0',
      avgFillPrice: '3428.60',
      fee: '4.10',
      slippage: '0.02',
      holdAmount: null,
      holdCurrency: 'USDT',
      rejectCode: null,
      rejectMessage: null,
      createdAt: '2026-06-13T16:44:00.000Z',
      updatedAt: '2026-06-13T16:44:03.000Z',
      filledAt: '2026-06-13T16:44:03.000Z',
      canceledAt: null
    },
    {
      id: 'order-xau-canceled',
      accountId: 'visual-account',
      symbol: 'XAUUSD',
      side: 'BUY',
      orderType: 'STOP',
      status: 'CANCELED',
      lots: '1.00',
      quantity: '1.00',
      price: '2338.20',
      executionPrice: null,
      filledQuantity: '0',
      remainingQuantity: '1.00',
      avgFillPrice: null,
      fee: null,
      slippage: null,
      holdAmount: '2338.20',
      holdCurrency: 'USD',
      rejectCode: null,
      rejectMessage: null,
      createdAt: '2026-06-12T11:05:00.000Z',
      updatedAt: '2026-06-12T12:20:00.000Z',
      filledAt: null,
      canceledAt: '2026-06-12T12:20:00.000Z'
    }
  ]
}

function visualSubmittedOrder() {
  return {
    ...visualOrders()[0],
    id: 'order-visual-submitted',
    status: 'ACCEPTED',
    filledQuantity: '0',
    remainingQuantity: '0.01',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
}

function visualPositions() {
  return [
    {
      id: 'position-eurusd-long',
      symbol: 'EURUSD',
      side: 'LONG',
      instrumentType: 'FOREX',
      marginMode: 'ISOLATED',
      leverage: 50,
      positionUnit: 'LOT',
      lots: '1.00',
      openPrice: '1.10002',
      markPrice: '1.10101',
      currentPrice: '1.10100',
      liquidationPrice: '1.07800',
      breakEvenPrice: '1.10009',
      stopLoss: '1.09500',
      takeProfit: '1.11200',
      floatingPnl: '98.00',
      floatingPnlRatio: '0.04454464',
      realizedPnl: '91.00',
      marginHeld: '2200.04',
      maintenanceMarginRate: '0.005',
      adlLevel: 1,
      status: 'OPEN',
      openedAt: '2026-06-14T07:32:00.000Z',
      closedAt: null
    },
    {
      id: 'position-btc-spot',
      symbol: 'BTCUSDT',
      side: 'LONG',
      instrumentType: 'SPOT',
      marginMode: 'CASH',
      leverage: null,
      positionUnit: 'BTC',
      lots: '0.1998',
      openPrice: '50050.05005',
      markPrice: '55000.00',
      currentPrice: '55000.00',
      liquidationPrice: null,
      breakEvenPrice: '50050.05005',
      stopLoss: '48000.00',
      takeProfit: '58000.00',
      floatingPnl: '978.011',
      floatingPnlRatio: null,
      realizedPnl: '978.011',
      marginHeld: '10000.00',
      maintenanceMarginRate: '0',
      adlLevel: null,
      status: 'OPEN',
      openedAt: '2026-06-14T08:00:00.000Z',
      closedAt: null
    },
    {
      id: 'position-btc-linear-long',
      symbol: 'BTCUSDT',
      side: 'LONG',
      instrumentType: 'SWAP',
      marginMode: 'CROSS',
      leverage: 10,
      positionUnit: 'BTC',
      lots: '1.00',
      openPrice: '50000.00',
      markPrice: '55000.00',
      currentPrice: '55000.00',
      liquidationPrice: '45500.00',
      breakEvenPrice: '50042.00',
      stopLoss: '48000.00',
      takeProfit: '58000.00',
      floatingPnl: '5000.00',
      floatingPnlRatio: '0.9906',
      realizedPnl: '4953.00',
      marginHeld: '5000.00',
      maintenanceMarginRate: '0.01',
      adlLevel: 2,
      status: 'OPEN',
      openedAt: '2026-06-14T08:15:00.000Z',
      closedAt: null
    },
    {
      id: 'position-btc-inverse-long',
      symbol: 'BTCUSD',
      side: 'LONG',
      instrumentType: 'SWAP',
      marginMode: 'CROSS',
      leverage: 10,
      positionUnit: 'CONTRACT',
      lots: '100',
      openPrice: '50000.00',
      markPrice: '55000.00',
      currentPrice: '55000.00',
      liquidationPrice: '45454.55',
      breakEvenPrice: '50100.00',
      stopLoss: '48000.00',
      takeProfit: '58000.00',
      floatingPnl: '0.01799091',
      floatingPnlRatio: '0.89954550',
      realizedPnl: '0.01799091',
      marginHeld: '0.02000000',
      maintenanceMarginRate: '0.005',
      adlLevel: 3,
      status: 'OPEN',
      openedAt: '2026-06-14T08:30:00.000Z',
      closedAt: null
    }
  ]
}

function visualPositionHistory() {
  return [
    {
      ...visualPositions()[2],
      id: 'position-eth-closed',
      symbol: 'ETHUSDT',
      side: 'LONG',
      positionUnit: 'ETH',
      lots: '3.00',
      openPrice: '3310.00',
      currentPrice: '3428.60',
      markPrice: '3428.60',
      floatingPnl: '355.80',
      realizedPnl: '355.80',
      marginHeld: '496.50',
      status: 'CLOSED',
      openedAt: '2026-06-10T09:00:00.000Z',
      closedAt: '2026-06-13T16:44:03.000Z'
    }
  ]
}

function visualWalletBalances() {
  return [
    {
      id: 'wallet-usdt',
      accountId: 'visual-account',
      walletType: 'SPOT',
      asset: 'USDT',
      total: '128430.58',
      available: '110010.28',
      locked: '18420.30'
    },
    {
      id: 'wallet-btc',
      accountId: 'visual-account',
      walletType: 'SPOT',
      asset: 'BTC',
      total: '0.1998',
      available: '0.1998',
      locked: '0'
    },
    {
      id: 'wallet-eth',
      accountId: 'visual-account',
      walletType: 'SPOT',
      asset: 'ETH',
      total: '2.50',
      available: '2.50',
      locked: '0'
    }
  ]
}

function visualAssetLedgerEntries() {
  return [
    {
      id: 'asset-ledger-deposit',
      accountId: 'visual-account',
      walletType: 'SPOT',
      asset: 'USDT',
      amount: '50000.00',
      balanceAfter: '128430.58',
      entryType: 'DEPOSIT',
      referenceType: 'FUND_ORDER',
      referenceId: 'fund-deposit',
      description: 'Visual QA deposit settled',
      createdAt: '2026-06-14T08:00:00.000Z'
    }
  ]
}

function visualLedgerEntries() {
  return [
    {
      id: 'ledger-deposit',
      accountId: 'visual-account',
      entryType: 'DEPOSIT',
      amount: '50000.00',
      balanceAfter: '128430.58',
      currency: 'USDT',
      referenceType: 'FUND_ORDER',
      referenceId: 'fund-deposit',
      description: 'Visual QA deposit settled',
      createdAt: '2026-06-14T08:00:00.000Z'
    },
    {
      id: 'ledger-fee',
      accountId: 'visual-account',
      entryType: 'TRADING_FEE',
      amount: '-4.10',
      balanceAfter: '78430.58',
      currency: 'USDT',
      referenceType: 'ORDER',
      referenceId: 'order-eth-filled',
      description: 'ETHUSDT market order fee',
      createdAt: '2026-06-13T16:44:03.000Z'
    },
    {
      id: 'ledger-pnl',
      accountId: 'visual-account',
      entryType: 'REALIZED_PNL',
      amount: '355.80',
      balanceAfter: '78434.68',
      currency: 'USDT',
      referenceType: 'POSITION',
      referenceId: 'position-eth-closed',
      description: 'ETHUSDT position closed',
      createdAt: '2026-06-13T16:44:03.000Z'
    }
  ]
}

function visualFundOrders() {
  return [
    {
      id: 'fund-deposit',
      userId: 'visual-user',
      accountId: 'visual-account',
      orderType: 'RECHARGE',
      amount: '50000.00',
      currency: 'USDT',
      status: 'APPROVED',
      paymentMethodId: 'bank-transfer',
      note: 'Visual QA funding record',
      reviewReason: null,
      reviewedBy: 'ops',
      reviewedAt: '2026-06-14T08:10:00.000Z',
      fundOperationId: 'fund-operation-1',
      createdAt: '2026-06-14T08:00:00.000Z'
    },
    {
      id: 'fund-withdrawal',
      userId: 'visual-user',
      accountId: 'visual-account',
      orderType: 'WITHDRAWAL',
      amount: '1200.00',
      currency: 'USDT',
      status: 'PENDING_REVIEW',
      paymentMethodId: 'wallet-address',
      note: 'Whitelist address withdrawal',
      reviewReason: null,
      reviewedBy: null,
      reviewedAt: null,
      fundOperationId: null,
      createdAt: '2026-06-13T18:22:00.000Z'
    }
  ]
}

async function ensureWebServer() {
  if (await canFetch(`${webBaseUrl}/login`)) return

  const command = process.platform === 'win32' ? 'cmd.exe' : 'npm'
  const args =
    process.platform === 'win32'
      ? ['/d', '/s', '/c', 'npm.cmd --workspace apps/web run dev -- --host 127.0.0.1 --port 5173']
      : ['--workspace', 'apps/web', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173']
  const child = spawn(command, args, {
    cwd: projectRoot,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })
  processes.push(child)

  let output = ''
  child.stdout.on('data', (chunk) => {
    output += chunk.toString()
  })
  child.stderr.on('data', (chunk) => {
    output += chunk.toString()
  })

  await waitFor(async () => {
    if (child.exitCode !== null) {
      throw new Error(`Vite dev server exited early:\n${output}`)
    }
    return canFetch(`${webBaseUrl}/login`)
  }, 'Vite dev server ready', 30000)
}

async function launchChrome() {
  const executable = chromeExecutable()
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-visual-qa-smoke-'))
  const child = spawn(
    executable,
    [
      '--headless=new',
      `--remote-debugging-port=${port}`,
      '--remote-allow-origins=*',
      `--user-data-dir=${userDataDir}`,
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--window-size=1440,900',
      'about:blank'
    ],
    { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }
  )
  processes.push(child)

  child.on('exit', () => {
    setTimeout(() => {
      void rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
    }, 500)
  })

  await waitFor(async () => {
    if (child.exitCode !== null) throw new Error('Headless browser exited before CDP became ready')
    return canFetch(`http://127.0.0.1:${port}/json/version`)
  }, 'Chrome DevTools endpoint ready', 20000)

  return { port }
}

async function createCdpPage(port) {
  const target = await createTarget(port)
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map()
  const listeners = new Map()
  let nextId = 1

  await Promise.race([
    new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true })
      socket.addEventListener('error', reject, { once: true })
      socket.addEventListener('close', () => reject(new Error('CDP WebSocket closed before opening')), { once: true })
    }),
    new Promise((_, reject) => setTimeout(() => reject(new Error('CDP WebSocket open timed out')), 10_000))
  ])
  socket.addEventListener('message', (message) => {
    const payload = JSON.parse(message.data)
    if (payload.id && pending.has(payload.id)) {
      const { resolve, reject, timer } = pending.get(payload.id)
      pending.delete(payload.id)
      clearTimeout(timer)
      if (payload.error) reject(new Error(payload.error.message))
      else resolve(payload.result)
      return
    }

    if (payload.method && listeners.has(payload.method)) {
      for (const listener of listeners.get(payload.method)) listener(payload.params ?? {})
    }
  })

  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = nextId++
      const timer = setTimeout(() => {
        pending.delete(id)
        reject(new Error(`CDP ${method} timed out`))
      }, 10_000)
      pending.set(id, { resolve, reject, timer })
      socket.send(JSON.stringify({ id, method, params }))
    })

  return {
    send,
    on(method, listener) {
      if (!listeners.has(method)) listeners.set(method, new Set())
      listeners.get(method).add(listener)
    },
    off(method, listener) {
      listeners.get(method)?.delete(listener)
    },
    async navigate(url) {
      await send('Page.navigate', { url })
      await waitFor(() => this.evaluate(() => document.readyState === 'complete'), `load ${url}`, 30000)
    },
    async evaluate(fn, ...args) {
      const result = await send('Runtime.evaluate', {
        expression: `(${fn})(${args.map((arg) => JSON.stringify(arg)).join(',')})`,
        awaitPromise: true,
        returnByValue: true
      })
      if (result.exceptionDetails) {
        throw new Error(result.exceptionDetails.exception?.description ?? 'Runtime evaluation failed')
      }
      return result.result?.value
    },
    async waitForFunction(fn, label, ...args) {
      await waitFor(() => this.evaluate(fn, ...args), label, 30000)
    },
    async close() {
      socket.close()
    }
  }
}

async function createTarget(port) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5_000)
  const response = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: controller.signal })
  assert(response.ok, `CDP target list failed with ${response.status}`)
  const targets = await Promise.race([
    response.json(),
    new Promise((_, reject) => setTimeout(() => reject(new Error('CDP target list body timed out')), 5_000))
  ])
  clearTimeout(timeout)
  const target = targets.find((candidate) => candidate.type === 'page' && candidate.webSocketDebuggerUrl)
  assert(target?.webSocketDebuggerUrl, 'CDP target must expose webSocketDebuggerUrl')
  return target
}

async function canFetch(url) {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 1200)
    const response = await fetch(url, { signal: controller.signal })
    clearTimeout(timeout)
    return response.ok
  } catch {
    return false
  }
}

async function waitFor(check, label, timeoutMs = 10000) {
  const startedAt = Date.now()
  let lastError
  while (Date.now() - startedAt < timeoutMs) {
    try {
      if (await check()) return
    } catch (error) {
      lastError = error
    }
    await sleep(250)
  }
  throw new Error(`${label} timed out${lastError ? `: ${lastError.message}` : ''}`)
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => resolve(address.port))
    })
    server.on('error', reject)
  })
}

function chromeExecutable() {
  const candidates = [
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)

  for (const candidate of candidates) {
    if (candidate && existsSync(candidate)) return candidate
  }
  throw new Error('Chrome or Edge executable was not found. Set CHROME_PATH to run this smoke test.')
}

function killProcessTree(child) {
  if (!child?.pid || child.exitCode !== null) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    return
  }
  child.kill('SIGTERM')
}

function sleep(timeoutMs) {
  return new Promise((resolve) => setTimeout(resolve, timeoutMs))
}

function safeName(value) {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'visual-qa'
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
