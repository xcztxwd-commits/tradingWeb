import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://localhost:5173'
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const runId = process.env.USER_PAGES_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.USER_PAGES_SMOKE_EMAIL ?? `user-pages-${runId}@example.com`
const userPassword = process.env.USER_PAGES_SMOKE_PASSWORD ?? 'SmokePass123!'

const processes = []
const results = []

try {
  const health = await step('backend health is up', async () => {
    const payload = await rawJson('/actuator/health')
    assert(payload.status === 'UP', `Expected backend health UP, got ${JSON.stringify(payload)}`)
    return { status: payload.status }
  })

  const context = await step('seed authenticated user data through real APIs', async () => {
    return seedUserData()
  })

  await ensureWebServer()

  const chrome = await launchChrome()
  const page = await createCdpPage(chrome.port)
  await page.send('Network.enable')
  await page.send('Page.enable')
  await page.send('Runtime.enable')

  await step('guest login state is enforced on protected pages', async () => {
    const protectedRoutes = ['/dashboard', '/orders', '/positions', '/wallet']
    const observed = []
    for (const route of protectedRoutes) {
      await clearBrowserSession(page)
      await page.navigate(`${webBaseUrl}${route}`)
      try {
        await page.waitForFunction(() => Boolean(document.querySelector('.state-panel button.table-action')), `login state for ${route}`)
      } catch (error) {
        const diagnostics = await page.evaluate(() => ({
          href: window.location.href,
          statePanels: [...document.querySelectorAll('.state-panel')].map((panel) => ({
            className: panel.className,
            text: panel.textContent?.slice(0, 240)
          })),
          bodyText: document.body.textContent?.slice(0, 500),
          localStorageKeys: Object.keys(localStorage)
        }))
        throw new Error(`${error instanceof Error ? error.message : String(error)}; diagnostics=${JSON.stringify(diagnostics)}`)
      }
      await sleep(250)
      await page.evaluate(() => {
        const button = document.querySelector('.state-panel button.table-action')
        if (!(button instanceof HTMLButtonElement)) throw new Error('login action button not found')
        button.click()
      })
      try {
        await page.waitForFunction(
          (expectedRoute) => window.location.pathname === '/login' && new URLSearchParams(window.location.search).get('redirect') === expectedRoute,
          `login redirect for ${route}`,
          route
        )
      } catch (error) {
        const diagnostics = await page.evaluate(() => ({
          href: window.location.href,
          statePanels: [...document.querySelectorAll('.state-panel')].map((panel) => ({
            className: panel.className,
            text: panel.textContent?.slice(0, 240)
          })),
          buttons: [...document.querySelectorAll('.state-panel button.table-action')].map((button) => ({
            text: button.textContent,
            disabled: button instanceof HTMLButtonElement ? button.disabled : null
          })),
          bodyText: document.body.textContent?.slice(0, 500)
        }))
        throw new Error(`${error instanceof Error ? error.message : String(error)}; diagnostics=${JSON.stringify(diagnostics)}`)
      }
      observed.push(route)
    }
    return { protectedRoutes: observed }
  })

  await step('loading state is visible while core API requests are pending', async () => {
    const checks = [
      { route: '/dashboard', hold: '/api/auth/session' },
      { route: '/orders', hold: '/api/auth/session' },
      { route: '/positions', hold: '/api/auth/session' },
      { route: '/wallet', hold: '/api/auth/session' },
      { route: '/markets', hold: '/api/market/symbols' }
    ]
    const observed = []
    for (const check of checks) {
      await setAuthToken(page, context.accessToken)
      let heldCount = 0
      await withFetchHandler(
        page,
        async (event) => {
          if (event.request.url.includes(check.hold)) {
            heldCount += 1
            return 'hold'
          }
          return 'continue'
        },
        async (held) => {
          await page.navigate(`${webBaseUrl}${check.route}`)
          await page.waitForFunction(() => Boolean(document.querySelector('.state-panel')), `loading state for ${check.route}`)
          assert(heldCount > 0, `${check.route} must request ${check.hold}`)
          await continueHeldRequests(page, held)
          await waitForPageReady(page, check.route)
        }
      )
      observed.push(check.route)
    }
    return { routes: observed }
  })

  await step('error state is rendered when core API requests fail', async () => {
    const checks = [
      { route: '/dashboard', fail: '/api/accounts' },
      { route: '/orders', fail: '/api/accounts' },
      { route: '/positions', fail: '/api/accounts' },
      { route: '/wallet', fail: '/api/accounts' },
      { route: '/markets', fail: '/api/market/symbols' }
    ]
    const observed = []
    for (const check of checks) {
      await setAuthToken(page, context.accessToken)
      let failedCount = 0
      await withFetchHandler(
        page,
        async (event) => {
          if (event.request.url.includes(check.fail)) {
            failedCount += 1
            return {
              responseCode: 500,
              body: {
                success: false,
                code: 'SMOKE_FORCED_ERROR',
                message: `Forced smoke error for ${check.route}`,
                data: null
              }
            }
          }
          return 'continue'
        },
        async () => {
          await page.navigate(`${webBaseUrl}${check.route}?forcedError=${runId}`)
          try {
            await page.waitForFunction(() => Boolean(document.querySelector('.state-panel--error')), `error state for ${check.route}`)
          } catch (error) {
            const diagnostics = await page.evaluate(() => ({
              href: window.location.href,
              statePanels: [...document.querySelectorAll('.state-panel')].map((panel) => ({
                className: panel.className,
                text: panel.textContent?.slice(0, 240)
              })),
              bodyText: document.body.textContent?.slice(0, 500)
            }))
            throw new Error(
              `${error instanceof Error ? error.message : String(error)}; failedCount=${failedCount}; diagnostics=${JSON.stringify(diagnostics)}`
            )
          }
          assert(failedCount > 0, `${check.route} must request ${check.fail}`)
        }
      )
      observed.push(check.route)
    }
    return { routes: observed }
  })

  await step('dashboard loads real account, orders, positions and ledger APIs', async () => {
    await setAuthToken(page, context.accessToken)
    const network = collectNetwork(page)
    await page.navigate(`${webBaseUrl}/dashboard`)
    await waitForPageReady(page, '/dashboard')
    await page.waitForFunction(() => document.querySelectorAll('.metric').length >= 4, 'dashboard metrics')
    await page.waitForFunction((symbol) => document.body.textContent?.includes(symbol), 'dashboard real seeded symbol', context.symbol)
    assertNetwork(network, ['/api/auth/session', '/api/accounts', '/api/trading/orders', '/api/trading/positions', '/api/ledger'])
    return summarizeNetwork(network)
  })

  await step('markets loads real symbols and quotes, supports favorites and trading navigation', async () => {
    await clearBrowserSession(page)
    const network = collectNetwork(page)
    await page.navigate(`${webBaseUrl}/markets`)
    await waitForPageReady(page, '/markets')
    await page.waitForFunction(() => document.querySelectorAll('tbody tr button').length >= 2, 'markets real table rows')
    assertNetwork(network, ['/api/market/symbols', '/api/market/quotes/'])
    await page.evaluate(() => {
      const firstActionGroup = document.querySelector('tbody tr')
      if (!firstActionGroup) throw new Error('market row not found')
      const buttons = [...firstActionGroup.querySelectorAll('button')]
      const favorite = buttons[0]
      if (!(favorite instanceof HTMLButtonElement)) throw new Error('favorite button not found')
      favorite.click()
    })
    await page.waitForFunction(() => {
      const firstButton = document.querySelector('tbody tr button')
      return firstButton?.getAttribute('aria-pressed') === 'true'
    }, 'favorite toggled')
    await page.evaluate(() => {
      const row = document.querySelector('tbody tr')
      if (!row) throw new Error('market row not found')
      const buttons = [...row.querySelectorAll('button')]
      const trade = buttons.at(-1)
      if (!(trade instanceof HTMLButtonElement)) throw new Error('trade navigation button not found')
      trade.click()
    })
    await page.waitForFunction(() => window.location.pathname === '/trading' && window.location.search.includes('symbol='), 'markets trading navigation')
    return summarizeNetwork(network)
  })

  await step('orders page shows real orders, event timeline, modify, and cancel actions work', async () => {
    await setAuthToken(page, context.accessToken)
    const network = collectNetwork(page)
    await page.navigate(`${webBaseUrl}/orders`)
    await waitForPageReady(page, '/orders')
    await page.waitForFunction((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      return actions?.getAttribute('data-order-status') === 'PENDING' && actions.querySelectorAll('button').length >= 3
    }, 'orders real pending row', context.pendingOrderId)
    assertNetwork(network, ['/api/auth/session', '/api/trading/orders'])

    await page.evaluate((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      const modifyButton = actions?.querySelectorAll('button')[1]
      if (!(modifyButton instanceof HTMLButtonElement)) throw new Error('order modify button not found')
      modifyButton.click()
    }, context.pendingOrderId)
    await page.waitForFunction(() => Boolean(document.querySelector('form input[name="price"]')), 'order modify form')
    await page.evaluate(() => {
      const price = document.querySelector('input[name="price"]')
      const submit = document.querySelector('form button[type="submit"]')
      if (!(price instanceof HTMLInputElement)) throw new Error('order modify price input not found')
      if (!(submit instanceof HTMLButtonElement)) throw new Error('order modify submit button not found')
      price.value = '0.00002'
      price.dispatchEvent(new Event('input', { bubbles: true }))
      submit.click()
    })
    await waitForNetworkResponse(network, (response) => response.url.includes('/api/trading/orders/') && response.method === 'PATCH' && response.status < 400, 'order modify API')
    await page.waitForFunction((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      const eventButton = actions?.querySelector('button')
      return !document.querySelector('form input[name="price"]') && eventButton instanceof HTMLButtonElement && !eventButton.disabled
    }, 'order modify refresh', context.pendingOrderId)

    await page.evaluate((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      const eventButton = actions?.querySelector('button')
      if (!(eventButton instanceof HTMLButtonElement)) throw new Error('order events button not found')
      eventButton.click()
    }, context.pendingOrderId)
    await waitForNetworkResponse(network, (response) => response.url.includes('/api/trading/orders/') && response.url.includes('/events') && response.status < 400, 'order events API')
    await page.waitForFunction(() => document.querySelectorAll('tbody tr').length > 0, 'order events rows')

    await page.navigate(`${webBaseUrl}/orders?cancel=${runId}`)
    await waitForPageReady(page, '/orders')
    await page.waitForFunction((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      if (!actions || actions.getAttribute('data-order-status') !== 'PENDING') return false
      const cancel = [...actions.querySelectorAll('button')].at(-1)
      return cancel instanceof HTMLButtonElement && !cancel.disabled
    }, 'pending order row', context.pendingOrderId)
    await page.evaluate((orderId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-order-id') === orderId)
      if (!actions) throw new Error(`pending order actions not found for ${orderId}`)
      const cancel = [...actions.querySelectorAll('button')].at(-1)
      if (!(cancel instanceof HTMLButtonElement)) throw new Error('order cancel button not found')
      if (cancel.disabled) throw new Error(`order cancel button is disabled; actions=${actions.textContent?.slice(0, 240)}`)
      cancel.click()
    }, context.pendingOrderId)
    await waitForNetworkResponse(network, (response) => response.url.includes('/api/trading/orders/') && response.url.includes('/cancel') && response.status < 400, 'order cancel API')
    return summarizeNetwork(network)
  })

  await step('positions page shows real positions, updates TP/SL, and closes a position', async () => {
    await setAuthToken(page, context.accessToken)
    const network = collectNetwork(page)
    await page.navigate(`${webBaseUrl}/positions`)
    await waitForPageReady(page, '/positions')
    await page.waitForFunction((positionId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-position-id') === positionId)
      return actions?.getAttribute('data-position-status') === 'OPEN'
    }, 'open position row', context.openPositionId)
    assertNetwork(network, ['/api/auth/session', '/api/trading/positions'])

    await page.evaluate((positionId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-position-id') === positionId)
      const button = actions?.querySelector('button')
      if (!(button instanceof HTMLButtonElement)) throw new Error('TP/SL button not found')
      button.click()
    }, context.openPositionId)
    await page.waitForFunction(() => Boolean(document.querySelector('form input[name="stopLoss"]')), 'TP/SL form')
    await page.evaluate(() => {
      const stopLoss = document.querySelector('input[name="stopLoss"]')
      const takeProfit = document.querySelector('input[name="takeProfit"]')
      const submit = document.querySelector('form button[type="submit"]')
      if (!(stopLoss instanceof HTMLInputElement)) throw new Error('stopLoss input not found')
      if (!(takeProfit instanceof HTMLInputElement)) throw new Error('takeProfit input not found')
      if (!(submit instanceof HTMLButtonElement)) throw new Error('TP/SL submit not found')
      const setValue = (input, value) => {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
        if (!setter) throw new Error('HTMLInputElement value setter not found')
        setter.call(input, value)
        input.dispatchEvent(new Event('input', { bubbles: true }))
        input.dispatchEvent(new Event('change', { bubbles: true }))
      }
      setValue(stopLoss, stopLoss.placeholder && /^[0-9.]+$/.test(stopLoss.placeholder) ? stopLoss.placeholder : '0.50000')
      setValue(takeProfit, takeProfit.placeholder && /^[0-9.]+$/.test(takeProfit.placeholder) ? takeProfit.placeholder : '2.00000')
      submit.click()
    })
    try {
      await waitForNetworkResponse(
        network,
        (response) => response.url.includes('/api/trading/positions/') && response.url.includes('/protection') && response.status < 400,
        'position protection API'
      )
    } catch (error) {
      const diagnostics = await page.evaluate(() => ({
        href: window.location.href,
        formValues: {
          stopLoss: document.querySelector('input[name="stopLoss"]') instanceof HTMLInputElement ? document.querySelector('input[name="stopLoss"]')?.value : null,
          takeProfit: document.querySelector('input[name="takeProfit"]') instanceof HTMLInputElement ? document.querySelector('input[name="takeProfit"]')?.value : null
        },
        formError: document.querySelector('.protection-form__error')?.textContent,
        bodyText: document.body.textContent?.slice(0, 700)
      }))
      throw new Error(
        `${error instanceof Error ? error.message : String(error)}; protectionResponses=${JSON.stringify(
          network.responses.filter((response) => response.url.includes('/api/trading/positions/') && response.url.includes('/protection'))
        )}; diagnostics=${JSON.stringify(diagnostics)}`
      )
    }

    await page.waitForFunction((positionId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-position-id') === positionId)
      const close = [...(actions?.querySelectorAll('button') ?? [])][1]
      return close instanceof HTMLButtonElement && !close.disabled
    }, 'position actions after TP/SL', context.openPositionId)
    await page.evaluate((positionId) => {
      const actions = [...document.querySelectorAll('.user-page__actions')].find((node) => node.getAttribute('data-position-id') === positionId)
      const buttons = [...(actions?.querySelectorAll('button') ?? [])]
      const close = buttons[1]
      if (!(close instanceof HTMLButtonElement)) throw new Error('position close button not found')
      if (close.disabled) throw new Error('position close button is disabled')
      close.click()
    }, context.openPositionId)
    await page.waitForFunction(() => Boolean(document.querySelector('.confirm-dialog')), 'position close confirmation dialog')
    await page.evaluate(() => {
      const confirmButton = document.querySelector('.confirm-dialog .table-action--danger') ?? [...document.querySelectorAll('.confirm-dialog button')].find((button) =>
        button.textContent?.includes('确认平仓')
      )
      if (!(confirmButton instanceof HTMLButtonElement)) throw new Error('position close confirm button not found')
      confirmButton.click()
    })
    try {
      await waitForNetworkResponse(
        network,
        (response) => response.url.includes('/api/trading/positions/') && response.url.includes('/close') && response.status < 400,
        'position close API'
      )
    } catch (error) {
      const diagnostics = await page.evaluate(() => ({
        href: window.location.href,
        buttons: [...document.querySelectorAll('tbody tr .user-page__actions button')].map((button) => ({
          text: button.textContent,
          disabled: button instanceof HTMLButtonElement ? button.disabled : null
        })),
        bodyText: document.body.textContent?.slice(0, 700)
      }))
      throw new Error(
        `${error instanceof Error ? error.message : String(error)}; closeResponses=${JSON.stringify(
          network.responses.filter((response) => response.url.includes('/api/trading/positions/') && response.url.includes('/close'))
        )}; diagnostics=${JSON.stringify(diagnostics)}`
      )
    }
    return summarizeNetwork(network)
  })

  await step('wallet page loads real ledger/fund orders and submits a fund request', async () => {
    await setAuthToken(page, context.accessToken)
    const network = collectNetwork(page)
    await page.navigate(`${webBaseUrl}/wallet`)
    await waitForPageReady(page, '/wallet')
    await page.waitForFunction(() => document.querySelectorAll('.metric').length >= 4, 'wallet metrics')
    assertNetwork(network, ['/api/auth/session', '/api/accounts', '/api/ledger', '/api/finance/fund-orders'])

    await page.evaluate((note) => {
      const amount = document.querySelector('input[name="amount"]')
      const noteInput = document.querySelector('input[name="note"]')
      const submit = document.querySelector('form button[type="submit"]')
      if (!(amount instanceof HTMLInputElement)) throw new Error('fund order amount input not found')
      if (!(noteInput instanceof HTMLInputElement)) throw new Error('fund order note input not found')
      if (!(submit instanceof HTMLButtonElement)) throw new Error('fund order submit button not found')
      amount.value = '12.34'
      amount.dispatchEvent(new Event('input', { bubbles: true }))
      noteInput.value = note
      noteInput.dispatchEvent(new Event('input', { bubbles: true }))
      submit.click()
    }, `smoke-${runId}`)
    await waitForNetworkResponse(network, (response) => response.url.includes('/api/finance/fund-orders') && response.method === 'POST' && response.status < 400, 'fund order create API')
    await page.waitForFunction(() => document.body.textContent?.includes('PENDING_REVIEW'), 'created fund order status')
    return summarizeNetwork(network)
  })

  await page.close()

  console.log(JSON.stringify({ apiBaseUrl, webBaseUrl, runId, userEmail, backendHealth: health.status, results }, null, 2))
} finally {
  for (const child of processes.reverse()) {
    killProcessTree(child)
  }
}

async function seedUserData() {
  const auth = await api('/api/auth/register', {
    method: 'POST',
    body: { email: userEmail, phone: null, password: userPassword }
  })
  assert(auth.accessToken, 'register must return accessToken')

  const accounts = await api('/api/accounts', { token: auth.accessToken })
  assert(Array.isArray(accounts) && accounts.length > 0, 'registered user must have an account')
  const account = accounts[0]

  const symbols = await api('/api/market/symbols')
  const symbol = symbols.find((item) => item.enabled && item.symbol === 'EURUSD') ?? symbols.find((item) => item.enabled) ?? symbols[0]
  assert(symbol?.symbol, 'market symbol must exist')

  const marketOrder = await api('/api/trading/orders', {
    method: 'POST',
    token: auth.accessToken,
    body: orderPayload(account.id, symbol.symbol, 'MARKET', 'seed-position')
  })
  assert(['FILLED', 'PARTIALLY_FILLED'].includes(marketOrder.status), `market order must fill, got ${marketOrder.status}`)

  const pendingOrder = await api('/api/trading/orders', {
    method: 'POST',
    token: auth.accessToken,
    body: orderPayload(account.id, symbol.symbol, 'LIMIT', 'seed-pending', '0.00001')
  })
  assert(pendingOrder.status === 'PENDING', `limit order must stay PENDING, got ${pendingOrder.status}`)

  const positions = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.id)}`, { token: auth.accessToken })
  const openPosition = positions.find((position) => position.status === 'OPEN')
  assert(openPosition, 'seed market order must create an open position')

  return {
    accessToken: auth.accessToken,
    accountId: account.id,
    symbol: symbol.symbol,
    marketOrderId: marketOrder.id,
    openPositionId: openPosition.id,
    pendingOrderId: pendingOrder.id
  }
}

function orderPayload(accountId, symbol, orderType, suffix, requestedPrice) {
  const key = `user-pages-${suffix}-${runId}`
  return {
    accountId,
    symbol,
    side: 'BUY',
    orderType,
    lots: '0.01',
    quantity: '0.01',
    price: requestedPrice,
    requestedPrice,
    clientOrderId: key,
    idempotencyKey: key
  }
}

async function step(name, fn) {
  const startedAt = Date.now()
  try {
    const details = await fn()
    const result = { name, status: 'PASS', durationMs: Date.now() - startedAt, details }
    results.push(result)
    return details
  } catch (error) {
    const result = {
      name,
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error)
    }
    results.push(result)
    console.error(JSON.stringify({ apiBaseUrl, webBaseUrl, runId, userEmail, results }, null, 2))
    throw error
  }
}

async function api(path, options = {}) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || !payload?.success) {
    throw new Error(`${payload?.code ?? response.status}: ${payload?.message ?? 'Request failed'}`)
  }
  return payload.data
}

async function rawJson(path) {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  return response.json()
}

async function ensureWebServer() {
  if (await canFetch(`${webBaseUrl}/markets`)) return

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
    return canFetch(`${webBaseUrl}/markets`)
  }, 'Vite dev server ready', 30000)
}

async function launchChrome() {
  const executable = chromeExecutable()
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-user-pages-smoke-'))
  const child = spawn(
    executable,
    [
      '--headless=new',
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--window-size=1440,1000',
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
    async evaluateExpression(expression) {
      const result = await send('Runtime.evaluate', {
        expression,
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

function collectNetwork(page) {
  const requests = []
  const responses = []
  const requestMethods = new Map()
  const inflight = new Set()
  page.on('Network.requestWillBeSent', (event) => {
    requests.push(event.request.url)
    requestMethods.set(event.requestId, event.request.method)
    if (isApiRequest(event.request.url)) {
      inflight.add(event.requestId)
    }
  })
  page.on('Network.responseReceived', (event) => {
    responses.push({
      url: event.response.url,
      status: event.response.status,
      method: requestMethods.get(event.requestId) ?? 'GET'
    })
  })
  page.on('Network.loadingFinished', (event) => {
    inflight.delete(event.requestId)
  })
  page.on('Network.loadingFailed', (event) => {
    inflight.delete(event.requestId)
  })
  return { requests, responses, inflight }
}

function isApiRequest(url) {
  return url.includes('/api/')
}

function assertNetwork(network, expectedPaths) {
  for (const expected of expectedPaths) {
    assert(network.requests.some((url) => url.includes(expected)), `Expected browser request for ${expected}`)
  }
}

function summarizeNetwork(network) {
  return {
    apiRequests: network.requests.filter((url) => url.includes('/api/')).length,
    apiResponses: network.responses.filter((response) => response.url.includes('/api/')).length,
    failedApiResponses: network.responses.filter((response) => response.url.includes('/api/') && response.status >= 400)
  }
}

async function waitForNetworkResponse(network, matcher, label) {
  await waitFor(() => network.responses.some(matcher), label, 30000)
}

async function waitForNetworkIdle(network, idleMs, timeoutMs) {
  const startedAt = Date.now()
  let idleStartedAt = null
  while (Date.now() - startedAt < timeoutMs) {
    if (network.inflight.size === 0) {
      idleStartedAt ??= Date.now()
      if (Date.now() - idleStartedAt >= idleMs) return
    } else {
      idleStartedAt = null
    }
    await sleep(100)
  }
  throw new Error(`Network did not become idle within ${timeoutMs}ms`)
}

async function waitForPageReady(page, route) {
  await page.waitForFunction(
    (expectedRoute) =>
      window.location.pathname === expectedRoute &&
      !document.querySelector('.state-panel--error') &&
      document.body.textContent &&
      document.body.textContent.length > 20,
    `page ready ${route}`,
    route
  )
}

async function clearBrowserSession(page) {
  await page.navigate(`${webBaseUrl}/markets?clear=${runId}`)
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
}

async function setAuthToken(page, token) {
  await page.navigate(`${webBaseUrl}/markets?auth=${runId}`)
  await page.evaluate((value) => {
    localStorage.setItem('fx-platform-auth-token', value)
  }, token)
}

async function withFetchHandler(page, handler, action) {
  const held = []
  const paused = async (event) => {
    try {
      const decision = await handler(event)
      if (decision === 'hold') {
        held.push(event.requestId)
        return
      }
      if (decision && typeof decision === 'object') {
        await page.send('Fetch.fulfillRequest', {
          requestId: event.requestId,
          responseCode: decision.responseCode,
          responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
          body: Buffer.from(JSON.stringify(decision.body), 'utf8').toString('base64')
        })
        return
      }
      await page.send('Fetch.continueRequest', { requestId: event.requestId })
    } catch {
      await page.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => undefined)
    }
  }

  page.on('Fetch.requestPaused', paused)
  await page.send('Fetch.enable', { patterns: [{ urlPattern: '*' }] })
  try {
    await action(held)
  } finally {
    await continueHeldRequests(page, held)
    await page.send('Fetch.disable').catch(() => undefined)
    page.off('Fetch.requestPaused', paused)
  }
}

async function continueHeldRequests(page, held) {
  while (held.length) {
    const requestId = held.shift()
    await page.send('Fetch.continueRequest', { requestId }).catch(() => undefined)
  }
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

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
