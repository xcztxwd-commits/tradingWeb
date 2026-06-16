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
const artifactRoot = resolve(projectRoot, 'test-results', 'visual-qa-smoke', runId)
const screenshotsDir = join(artifactRoot, 'screenshots')
const reportPath = join(artifactRoot, 'report.json')

const routes = [
  { path: '/', ready: 'home', checksMobileBottomAction: true },
  { path: '/markets', ready: 'markets', checksMobileBottomAction: true },
  { path: '/trading', ready: 'trading', checksMobileBottomAction: true },
  { path: '/orders', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/orders', name: 'orders-auth', ready: 'orders-auth', auth: true, checksMobileBottomAction: true },
  { path: '/positions', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/positions', name: 'positions-auth', ready: 'positions-auth', auth: true, checksMobileBottomAction: true },
  { path: '/wallet', ready: 'login-required', checksMobileBottomAction: true },
  { path: '/wallet', name: 'wallet-auth', ready: 'wallet-auth', auth: true, checksMobileBottomAction: true },
  { path: '/settings', ready: 'user-page', checksMobileBottomAction: true },
  { path: '/security', ready: 'user-page', checksMobileBottomAction: true },
  { path: '/login', ready: 'auth-form', checksMobileBottomAction: false },
  { path: '/register', ready: 'auth-form', checksMobileBottomAction: false },
  { path: '/forgot-password', ready: 'auth-form', checksMobileBottomAction: false },
  { path: '/two-factor-help', ready: 'auth-form', checksMobileBottomAction: false }
]

const viewports = [
  { name: 'desktop', width: 1440, height: 900, deviceScaleFactor: 1, mobile: false },
  { name: 'mobile', width: 390, height: 844, deviceScaleFactor: 3, mobile: true }
]

const processes = []
const results = []
const screenshotPaths = []

await mkdir(screenshotsDir, { recursive: true })

try {
  await ensureWebServer()

  const chrome = await launchChrome()
  const page = await createCdpPage(chrome.port)
  await page.send('Page.enable')
  await page.send('Network.enable')
  await page.send('Runtime.enable')
  await page.send('Fetch.enable', { patterns: [{ urlPattern: '*' }] })
  await installBrowserShims(page)
  installApiMocks(page)

  for (const viewport of viewports) {
    await setViewport(page, viewport)
    for (const route of routes) {
      results.push(await runRouteCheck(page, route, viewport))
    }
  }

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
    await sleep(350)

    const nonEmpty = await assertPageNonEmpty(page)
    const positionAlgorithmSamples =
      route.ready === 'positions-auth'
        ? await assertPositionAlgorithmSamples(page)
        : { skipped: true, reason: 'route does not render authenticated positions' }
    const overlay = await assertNoFrameworkOverlay(page)
    const horizontalScroll = await assertNoBodyHorizontalScroll(page)
    const consoleHealth = assertNoConsoleErrors(diagnostics.errors)
    const mobileBottomAction =
      viewport.name === 'mobile'
        ? await assertMobileBottomActionClearance(page, route)
        : { skipped: true, reason: 'desktop viewport has no mobile bottom bar requirement' }

    await resetViewportScroll(page)
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
        consoleHealth,
        positionAlgorithmSamples,
        overlay,
        horizontalScroll,
        mobileBottomAction
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

async function resetViewportScroll(page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0)
    const main = document.querySelector('.main-region')
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

      const textLength = (document.body.textContent ?? '').trim().length
      if (textLength < 40) return false

      if (readyKind === 'login-required') {
        return Boolean(document.querySelector('.state-panel--login .table-action'))
      }
      if (readyKind === 'markets') {
        const loading = document.querySelector('.state-panel--loading')
        const marketGrid = document.querySelector('.market-rank-grid, .market-ranking-preview-grid, .market-data-dashboard')
        const marketGridText = (marketGrid?.textContent ?? '').replace(/\s+/g, '').trim()
        return !loading &&
          Boolean(marketGrid) &&
          (marketGridText.length > 40 || Boolean(document.querySelector('tbody tr, .data-table__card')))
      }
      if (readyKind === 'home') {
        const main = document.querySelector('main')
        return Boolean(document.querySelector('#home-hero-title')) &&
          Boolean(main?.querySelector('a[href="/markets"]')) &&
          Boolean(main?.querySelector('a[href="/trading"]'))
      }
      if (readyKind === 'trading') {
        const terminalSection = [...document.querySelectorAll('section[aria-label]')].some((section) => section.querySelector('button'))
        return terminalSection && Boolean(document.querySelector('.trade-panel, button, canvas'))
      }
      if (readyKind === 'wallet-auth') {
        return Boolean(document.querySelector('.wallet-workbench')) &&
          Boolean(document.querySelector('#wallet-assets')) &&
          Boolean(document.querySelector('tbody tr, .data-table__card'))
      }
      if (readyKind === 'orders-auth') {
        return Boolean(document.querySelector('.user-page__tabs')) &&
          Boolean(document.querySelector('.user-page__toolbar')) &&
          Boolean(document.querySelector('tbody tr, .data-table__card'))
      }
      if (readyKind === 'positions-auth') {
        return Boolean(document.querySelector('.user-page__tabs')) &&
          Boolean(document.querySelector('.user-page__toolbar')) &&
          Boolean(document.querySelector('tbody tr, .data-table__card'))
      }
      if (readyKind === 'auth-form') {
        return Boolean(document.querySelector('form button[type="submit"]'))
      }
      return Boolean(document.querySelector('.user-page'))
    },
    `route ready ${route.path}`,
    route.path,
    route.ready
  )
}

async function assertPageNonEmpty(page) {
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
      visibleElementCount
    }
  })

  assert(result.textLength > 40, `Expected meaningful page text, got ${result.textLength} characters`)
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

function assertNoConsoleErrors(errors) {
  assert(errors.length === 0, `Console errors detected: ${JSON.stringify(errors)}`)
  return { errors: 0 }
}

async function assertMobileBottomActionClearance(page, route) {
  if (!route.checksMobileBottomAction) {
    return { skipped: true, reason: 'auth route hides the app mobile bottom bar' }
  }

  await page.evaluate(() => {
    window.scrollTo(0, document.documentElement.scrollHeight)
    const main = document.querySelector('.main-region')
    if (main) main.scrollTop = main.scrollHeight
  })
  await sleep(150)

  const result = await page.evaluate(() => {
    const bottomBar = document.querySelector('.mobile-tabs')
    if (!(bottomBar instanceof HTMLElement)) {
      return { skipped: true, reason: 'mobile bottom bar was not found' }
    }

    const bottomStyle = window.getComputedStyle(bottomBar)
    const bottomRect = bottomBar.getBoundingClientRect()
    if (bottomStyle.display === 'none' || bottomRect.height <= 0) {
      return { skipped: true, reason: 'mobile bottom bar is hidden' }
    }

    const main = document.querySelector('.main-region') ?? document.body
    const actions = [...main.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
      .filter((element) => {
        if (element.closest('.mobile-tabs, .sidebar')) return false
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
      return { skipped: true, reason: 'no key action found in main content' }
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

  assert(result.skipped || result.ok, `Mobile bottom bar overlaps the last key action: ${JSON.stringify(result)}`)
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

      if (url.pathname === '/api/trading/orders') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualOrders()
        })
        return
      }

      if (url.pathname === '/api/trading/positions') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPositions()
        })
        return
      }

      if (url.pathname === '/api/trading/positions/history') {
        await fulfillJson(page, event.requestId, {
          success: true,
          code: 'OK',
          message: 'OK',
          data: visualPositionHistory()
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

function marketSymbols() {
  return [
    symbolPayload('EURUSD', 'Euro / US Dollar', 'fx', 'EUR', 'USD', '0.00001', 5),
    symbolPayload('GBPUSD', 'British Pound / US Dollar', 'fx', 'GBP', 'USD', '0.00001', 5),
    symbolPayload('BTCUSDT', 'Bitcoin / Tether', 'crypto', 'BTC', 'USDT', '0.10', 2),
    symbolPayload('XAUUSD', 'Gold / US Dollar', 'metals', 'XAU', 'USD', '0.01', 2),
    symbolPayload('US30USD', 'US 30 Index', 'indices', 'US30', 'USD', '0.1', 1)
  ]
}

function symbolPayload(symbol, displayName, assetClass, baseCurrency, quoteCurrency, tickSize, pricePrecision) {
  return {
    symbol,
    displayName,
    assetClass,
    baseCurrency,
    quoteCurrency,
    minLot: '0.01',
    tickSize,
    pricePrecision,
    quantityPrecision: 2,
    enabled: true
  }
}

function marketQuote(symbol) {
  const prices = {
    EURUSD: 1.08421,
    GBPUSD: 1.27331,
    BTCUSDT: 68412.4,
    XAUUSD: 2341.22,
    US30USD: 38912.8
  }
  const mid = prices[symbol] ?? 100
  const spread = symbol.endsWith('USDT') ? 1.2 : symbol === 'US30USD' ? 1.4 : 0.00012
  return {
    type: 'quote',
    symbol,
    bid: String(mid - spread / 2),
    ask: String(mid + spread / 2),
    mid: String(mid),
    spread: String(spread),
    source: 'visual-qa',
    timestamp: Date.now()
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
  const mid = marketPrice(symbol)
  const step = symbol.endsWith('USDT') ? 0.1 : symbol === 'US30USD' ? 0.5 : 0.0001
  return {
    symbol,
    timestamp: Date.now(),
    bids: Array.from({ length: 24 }, (_, index) => ({
      price: formatMarketNumber(mid - step * (index + 1)),
      amount: String((0.42 + index * 0.037).toFixed(6))
    })),
    asks: Array.from({ length: 24 }, (_, index) => ({
      price: formatMarketNumber(mid + step * (index + 1)),
      amount: String((0.36 + index * 0.041).toFixed(6))
    }))
  }
}

function visualRecentTrades(symbol) {
  const mid = marketPrice(symbol)
  const step = symbol.endsWith('USDT') ? 0.1 : symbol === 'US30USD' ? 0.5 : 0.0001
  return Array.from({ length: 40 }, (_, index) => {
    const side = index % 3 === 0 ? 'sell' : 'buy'
    const direction = side === 'buy' ? 1 : -1
    return {
      id: `${symbol}-${index}`,
      symbol,
      price: formatMarketNumber(mid + direction * step * ((index % 8) + 1)),
      amount: String((0.12 + index * 0.009).toFixed(6)),
      side,
      timestamp: Date.now() - index * 2400
    }
  })
}

function marketPrice(symbol) {
  const prices = {
    EURUSD: 1.08421,
    GBPUSD: 1.27331,
    BTCUSDT: 68412.4,
    ETHUSDT: 3420,
    XAUUSD: 2341.22,
    US30USD: 38912.8
  }
  return prices[symbol] ?? 100
}

function formatMarketNumber(value) {
  if (Math.abs(value) >= 1000) return value.toFixed(2)
  if (Math.abs(value) >= 10) return value.toFixed(4)
  return value.toFixed(5)
}

function accountSummary() {
  return {
    id: 'visual-account',
    accountType: 'UNIFIED',
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
      status: 'WORKING',
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

  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', reject, { once: true })
  })

  socket.addEventListener('message', (message) => {
    const payload = JSON.parse(message.data)
    if (payload.id && pending.has(payload.id)) {
      const { resolve, reject } = pending.get(payload.id)
      pending.delete(payload.id)
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
      pending.set(id, { resolve, reject })
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
  const url = `http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`
  const response = await fetch(url, { method: 'PUT' }).catch(() => null)
  const fallback = response?.ok ? response : await fetch(url)
  const target = await fallback.json()
  assert(target.webSocketDebuggerUrl, 'CDP target must expose webSocketDebuggerUrl')
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
