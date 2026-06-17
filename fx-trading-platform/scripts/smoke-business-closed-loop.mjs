import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { createRequire } from 'node:module'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://localhost:5173'
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const runId = process.env.BUSINESS_CLOSED_LOOP_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.BUSINESS_CLOSED_LOOP_SMOKE_EMAIL ?? `closed-loop+${runId}@example.com`
const userPassword = process.env.BUSINESS_CLOSED_LOOP_SMOKE_PASSWORD ?? 'Password123!'
const browserAttempts = positiveIntegerEnv('BUSINESS_CLOSED_LOOP_BROWSER_ATTEMPTS', 2)
const account = {}
const symbol = {}
const openPosition = {}
const results = []
const context = { runId, userEmail }
const processes = []

let userAccessToken

try {
  await step('backend health is up', async () => {
    const health = await rawJson('/actuator/health')
    assert(health.status === 'UP', `Expected actuator health UP, got ${JSON.stringify(health)}`)
    return { status: health.status }
  })

  await step('user can register and then log in', async () => {
    const registered = await api('/api/auth/register', {
      method: 'POST',
      body: { email: userEmail, phone: null, password: userPassword }
    })
    assert(registered.accessToken, 'Register must return an accessToken')

    const loggedIn = await api('/api/auth/login', {
      method: 'POST',
      body: { email: userEmail, password: userPassword }
    })
    assert(loggedIn.accessToken, 'Login must return an accessToken')
    assert(loggedIn.userId === registered.userId, 'Login must authenticate the registered user')
    userAccessToken = loggedIn.accessToken
    context.userId = loggedIn.userId
    return { userId: loggedIn.userId, role: loggedIn.role, tokenIssued: true }
  })

  await step('wallet balance is available', async () => {
    const accounts = await api('/api/accounts', { token: userAccessToken })
    assert(Array.isArray(accounts) && accounts.length > 0, 'Registered user must have a trading account')
    const selected = accounts[0]
    account.accountId = selected.id

    const summary = await api(`/api/accounts/${encodeURIComponent(account.accountId)}/summary`, {
      token: userAccessToken
    })
    const walletBalances = await api(`/api/accounts/${encodeURIComponent(account.accountId)}/wallet-balances`, {
      token: userAccessToken
    })
    assert(Number(summary.balance) > 0, `Account balance must be positive, got ${summary.balance}`)
    assert(Array.isArray(walletBalances), 'Wallet balances endpoint must return an array')
    assert(
      walletBalances.length === 0 || walletBalances.some((balance) => Number(balance.total) > 0 || Number(balance.available) > 0),
      'Wallet balances must be empty only when wallet service is disabled, otherwise include positive funds'
    )
    context.accountId = account.accountId
    return {
      accountId: account.accountId,
      balance: summary.balance,
      walletBalances: walletBalances.length
    }
  })

  await step('market order opens a current position', async () => {
    const symbols = await api('/api/market/symbols')
    const selectedSymbol = symbols.find(
      (item) => item.enabled && item.tradable && item.quoteEnabled && item.productType === 'FX_MARGIN'
    )
    assert(selectedSymbol?.symbol, 'Market symbols must include an enabled tradable FX_MARGIN symbol with quote capability')
    symbol.symbol = selectedSymbol.symbol

    const quote = await api(`/api/market/quotes/${encodeURIComponent(symbol.symbol)}`)
    assert(Number(quote.bid) > 0 && Number(quote.ask) > 0, 'Selected symbol must expose a positive quote')

    const order = await api('/api/trading/orders', {
      method: 'POST',
      token: userAccessToken,
      body: {
        accountId: account.accountId,
        symbol: symbol.symbol,
        side: 'BUY',
        orderType: 'MARKET',
        lots: '0.01',
        quantity: '0.01',
        clientOrderId: `closed-loop-market-${runId}`,
        idempotencyKey: `closed-loop-market-${runId}`
      }
    })
    assert(['FILLED', 'PARTIALLY_FILLED'].includes(order.status), `Market order must fill, got ${order.status}`)

    const currentPositions = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, {
      token: userAccessToken
    })
    const current = currentPositions.find((position) => position.symbol === symbol.symbol && position.status === 'OPEN')
    assert(current, 'Market order must create a current open position')
    openPosition.orderId = order.id
    openPosition.positionId = current.id
    context.symbol = symbol.symbol
    context.orderId = order.id
    context.positionId = current.id
    return {
      symbol: symbol.symbol,
      orderId: order.id,
      orderStatus: order.status,
      positionId: current.id
    }
  })

  await step('current position closes into history', async () => {
    const closed = await api(
      `/api/trading/positions/${encodeURIComponent(openPosition.positionId)}/close?accountId=${encodeURIComponent(account.accountId)}`,
      { method: 'POST', token: userAccessToken, body: {} }
    )
    assert(closed.status === 'CLOSED', `Close endpoint must return CLOSED, got ${closed.status}`)

    const [currentPositions, history] = await Promise.all([
      api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, { token: userAccessToken }),
      api(`/api/trading/positions/history?accountId=${encodeURIComponent(account.accountId)}`, { token: userAccessToken })
    ])
    assert(!currentPositions.some((position) => position.id === openPosition.positionId), 'Closed position must leave current positions')
    assert(
      history.some((position) => position.id === openPosition.positionId && position.status === 'CLOSED'),
      'Closed position must appear in position history'
    )
    return { positionId: openPosition.positionId, status: closed.status, historyCount: history.length }
  })

  await step('ledger records hold release and pnl', async () => {
    const ledger = await api(`/api/ledger?accountId=${encodeURIComponent(account.accountId)}`, {
      token: userAccessToken
    })
    assert(ledger.some((entry) => entry.entryType === 'MARGIN_HOLD'), 'Opening the market order must write MARGIN_HOLD')
    assert(
      ledger.some((entry) => entry.entryType === 'MARGIN_RELEASE' && entry.referenceId === openPosition.positionId),
      'Closing the position must write MARGIN_RELEASE'
    )
    assert(
      ledger.some((entry) => entry.entryType === 'TRADE_PNL' && entry.referenceId === openPosition.positionId),
      'Closing the position must write TRADE_PNL'
    )
    return { ledgerTypes: [...new Set(ledger.map((entry) => entry.entryType))] }
  })

  await step('trading page renders a KLine marker for the traded order', async () => {
    await ensureWebServer()
    return withBrowserPage('KLine marker browser verification', async (page) => {
      await page.send('Network.enable')
      await page.send('Page.enable')
      await page.send('Runtime.enable')
      const network = collectNetwork(page)
      await setAuthToken(page, userAccessToken)
      await page.navigate(`${webBaseUrl}/trading?symbol=${encodeURIComponent(symbol.symbol)}&closedLoop=${runId}`)
      await waitForPageReady(page, '/trading')
      const markerTelemetry = await waitForTradeMarkerOverlay(page)
      assertNetwork(network, ['/api/auth/session', '/api/accounts', '/api/trading/orders', '/api/trading/positions', '/api/ledger'])
      return {
        ...markerTelemetry,
        apiRequests: network.requests.filter((url) => url.includes('/api/')).length
      }
    })
  })

  console.log(JSON.stringify({ apiBaseUrl, webBaseUrl, context, results }, null, 2))
} finally {
  for (const child of processes.reverse()) {
    killProcessTree(child)
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
    console.error(JSON.stringify({ apiBaseUrl, webBaseUrl, context, results }, null, 2))
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
  if (await canFetch(`${webBaseUrl}/trading`)) return

  const webUrl = new URL(webBaseUrl)
  const webHost = webUrl.hostname || '127.0.0.1'
  const webPort = webUrl.port || '5173'
  const viteBin = join(dirname(require.resolve('vite/package.json')), 'bin', 'vite.js')
  const command = process.execPath
  const args = [viteBin, '--host', webHost, '--port', webPort, '--strictPort']
  const child = spawn(command, args, {
    cwd: join(projectRoot, 'apps', 'web'),
    env: { ...process.env, VITE_API_BASE_URL: apiBaseUrl },
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
    return canFetch(`${webBaseUrl}/trading`)
  }, 'Vite dev server ready', 30000)
}

async function withBrowserPage(label, fn) {
  const candidates = browserCandidates()
  if (candidates.length === 0) {
    throw new Error('Chrome or Edge executable was not found. Set SMOKE_BROWSER_PATH or CHROME_PATH to run this smoke test.')
  }

  const failures = []
  for (const executable of candidates) {
    for (let attempt = 1; attempt <= browserAttempts; attempt += 1) {
      let browser
      let page
      try {
        browser = await launchChrome(executable)
        page = await createCdpPage(browser.port)
        return await fn(page)
      } catch (error) {
        failures.push(formatBrowserFailure(executable, attempt, error))
      } finally {
        if (page) await page.close().catch(() => undefined)
        if (browser) await browser.close()
      }
    }
  }

  throw new Error(`${label}: browser candidates failed\n${failures.join('\n')}`)
}

async function launchChrome(executable) {
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-business-closed-loop-smoke-'))
  let stdout = ''
  let stderr = ''
  let spawnError
  const child = spawn(
    executable,
    [
      '--headless=new',
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--disable-features=RendererCodeIntegrity',
      '--remote-allow-origins=*',
      '--no-first-run',
      '--no-default-browser-check',
      '--window-size=1440,1000',
      'about:blank'
    ],
    { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }
  )
  processes.push(child)

  child.stdout.on('data', (chunk) => {
    stdout = appendTail(stdout, chunk)
  })
  child.stderr.on('data', (chunk) => {
    stderr = appendTail(stderr, chunk)
  })
  child.on('error', (error) => {
    spawnError = error
  })
  child.on('exit', () => {
    setTimeout(() => {
      void rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
    }, 500)
  })

  try {
    await waitFor(async () => {
      if (spawnError) throw fatalError(`Headless browser failed to start (executable=${executable}): ${spawnError.message}`)
      if (child.exitCode !== null) throw fatalError(formatBrowserExit(executable, child, stdout, stderr))
      return canFetch(`http://127.0.0.1:${port}/json/version`)
    }, 'Chrome DevTools endpoint ready', 20000)
  } catch (error) {
    killProcessTree(child)
    await rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
    throw error
  }

  return {
    port,
    close: async () => {
      killProcessTree(child)
      await rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
    }
  }
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

function collectNetwork(page) {
  const requests = []
  page.on('Network.requestWillBeSent', (event) => {
    requests.push(event.request.url)
  })
  return { requests }
}

function assertNetwork(network, expectedPaths) {
  for (const expected of expectedPaths) {
    assert(network.requests.some((url) => url.includes(expected)), `Expected browser request for ${expected}`)
  }
}

async function setAuthToken(page, token) {
  await page.navigate(`${webBaseUrl}/markets?auth=${runId}`)
  await page.evaluate((value) => {
    localStorage.setItem('fx-platform-auth-token', value)
  }, token)
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

async function waitForTradeMarkerOverlay(page) {
  await page.waitForFunction(() => {
    const panel = document.querySelector('[data-trade-marker-overlay-count]')
    return Number(panel?.getAttribute('data-trade-marker-overlay-count')) > 0
  }, 'KLine trade marker overlay')

  return page.evaluate(() => {
    const panel = document.querySelector('[data-trade-marker-overlay-count]')
    if (!(panel instanceof HTMLElement)) throw new Error('KLine marker telemetry element not found')
    return {
      markerCount: Number(panel.dataset.tradeMarkerCount ?? '0'),
      overlayCount: Number(panel.dataset.tradeMarkerOverlayCount ?? '0')
    }
  })
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
      if (error?.fatal) throw error
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

function browserCandidates() {
  const candidates = [
    process.env.SMOKE_BROWSER_PATH,
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)

  const seen = new Set()
  return candidates.filter((candidate) => {
    const normalized = candidate.toLowerCase()
    if (seen.has(normalized) || !existsSync(candidate)) return false
    seen.add(normalized)
    return true
  })
}

function positiveIntegerEnv(name, fallback) {
  const value = Number(process.env[name] ?? fallback)
  return Number.isInteger(value) && value > 0 ? value : fallback
}

function formatBrowserFailure(executable, attempt, error) {
  const message = error instanceof Error ? error.message : String(error)
  return `- ${basename(executable)} attempt ${attempt}/${browserAttempts}: ${message}`
}

function formatBrowserExit(executable, child, stdout, stderr) {
  const stderrText = stderr.trim() || '<empty>'
  const stdoutText = stdout.trim() || '<empty>'
  return `Headless browser exited before CDP became ready (executable=${executable}, exitCode=${child.exitCode}, signal=${child.signalCode ?? 'none'}, stderr=${stderrText}, stdout=${stdoutText})`
}

function appendTail(current, chunk, maxLength = 4000) {
  return `${current}${chunk.toString()}`.slice(-maxLength)
}

function fatalError(message) {
  const error = new Error(message)
  error.fatal = true
  return error
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
