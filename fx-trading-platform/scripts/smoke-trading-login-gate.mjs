import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://localhost:5173'
const webUrl = new URL(webBaseUrl)
const webHost = webUrl.hostname || '127.0.0.1'
const webPort = webUrl.port || '5173'
const tradingUrl = `${webBaseUrl}/trade/spot/BTCUSDT`
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')

const processes = []

try {
  const health = await requestJson(`${apiBaseUrl}/actuator/health`, 'backend health')
  assert(health.status === 'UP', `Backend health must be UP, got ${JSON.stringify(health)}`)

  const session = await requestJson(`${apiBaseUrl}/api/auth/session`, 'guest session')
  assert(session.success === true, 'Guest session response must be an ApiResponse success')
  assert(session.data?.status === 'guest', `Guest session must report status=guest, got ${session.data?.status}`)
  assert(session.data?.authenticated === false, 'Guest session must report authenticated=false')
  assert(session.data?.loginPath === '/login', 'Guest session must point to /login')

  const invalidSession = await requestJson(`${apiBaseUrl}/api/auth/session`, 'invalid token session', {
    headers: { Authorization: 'Bearer bad-token' }
  })
  assert(invalidSession.success === true, 'Invalid token session response must be an ApiResponse success')
  assert(
    invalidSession.data?.status === 'invalid_token',
    `Invalid token session must report status=invalid_token, got ${invalidSession.data?.status}`
  )
  assert(invalidSession.data?.authenticated === false, 'Invalid token session must report authenticated=false')

  const auth = await requestJson(`${apiBaseUrl}/api/auth/register`, 'valid token registration', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: `smoke-session-${Date.now()}@example.com`,
      phone: null,
      password: 'SmokePass123!'
    })
  })
  const validToken = auth.data?.accessToken
  assert(typeof validToken === 'string' && validToken.length > 0, 'Registration must return an access token')
  const validSession = await requestJson(`${apiBaseUrl}/api/auth/session`, 'valid token session', {
    headers: { Authorization: `Bearer ${validToken}` }
  })
  assert(validSession.success === true, 'Valid token session response must be an ApiResponse success')
  assert(
    validSession.data?.status === 'valid_token',
    `Valid token session must report status=valid_token, got ${validSession.data?.status}`
  )
  assert(validSession.data?.authenticated === true, 'Valid token session must report authenticated=true')

  await ensureWebServer()

  const chrome = await launchChrome()
  const page = await createCdpPage(chrome.port)
  let sessionProbeRequests = 0
  page.on('Network.requestWillBeSent', (event) => {
    if (event.request?.url?.includes('/api/auth/session')) {
      sessionProbeRequests += 1
    }
  })

  await page.send('Network.enable')
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.navigate(tradingUrl)

  try {
    await page.waitForFunction(() => {
      const dialogOpen = document.getElementById('trading-login-title') !== null
      const text = document.body.textContent ?? ''
      const hasTradingSymbol = text.includes('BTCUSDT') || text.includes('EURUSD')
      const canSeeTerminal = hasTradingSymbol &&
        document.querySelector('[data-platform-view="pc"] [data-panel-id="chart"]') !== null &&
        document.querySelector('[data-platform-view="pc"] [data-panel-id="market"]') !== null
      const canSeeLoginOrderButton = document.querySelector('[data-trading-action="login-required"]') !== null
      return !dialogOpen && canSeeTerminal && canSeeLoginOrderButton
    }, 'guest terminal is usable before trade action')
  } catch (error) {
    const diagnostics = await page.evaluate(() => ({
      href: window.location.href,
      lang: document.documentElement.lang,
      bodyText: document.body.textContent?.slice(0, 1200),
      buttons: [...document.querySelectorAll('button')].slice(0, 20).map((button) => ({
        className: String(button.className),
        ariaLabel: button.getAttribute('aria-label'),
        text: button.textContent?.slice(0, 120)
      })),
      dialogs: [...document.querySelectorAll('[role="dialog"]')].map((dialog) => dialog.textContent?.slice(0, 240))
    }))
    throw new Error(`${error instanceof Error ? error.message : String(error)}; diagnostics=${JSON.stringify(diagnostics)}`)
  }

  assert(sessionProbeRequests > 0, 'Trading page must call /api/auth/session during boot')

  await page.evaluate(() => {
    const loginButton = document.querySelector('[data-trading-action="login-required"]')
    if (!(loginButton instanceof HTMLButtonElement)) {
      throw new Error('Login-required order button not found')
    }
    loginButton.click()
  })

  await page.waitForFunction(() => {
    return document.getElementById('trading-login-title') !== null
  }, 'login prompt appears after trade action')

  await page.evaluate(() => {
    const closeButton = document.querySelector('[data-trading-action="close-login-prompt"]')
    if (!(closeButton instanceof HTMLButtonElement)) {
      throw new Error('Close login prompt button not found')
    }
    closeButton.click()
  })

  await page.waitForFunction(() => {
    const dialogOpen = document.getElementById('trading-login-title') !== null
    const text = document.body.textContent ?? ''
    const hasTradingSymbol = text.includes('BTCUSDT') || text.includes('EURUSD')
    const canSeeTerminal = hasTradingSymbol &&
      document.querySelector('[data-platform-view="pc"] [data-panel-id="chart"]') !== null &&
      document.querySelector('[data-platform-view="pc"] [data-panel-id="market"]') !== null
    const canSeeLoginOrderButton = document.querySelector('[data-trading-action="login-required"]') !== null
    return !dialogOpen && canSeeTerminal && canSeeLoginOrderButton
  }, 'terminal remains usable after prompt dismiss')

  await page.evaluate(() => {
    const loginButton = document.querySelector('[data-trading-action="login-required"]')
    if (!(loginButton instanceof HTMLButtonElement)) {
      throw new Error('Login-required order button not found')
    }
    loginButton.click()
  })

  await page.waitForFunction(() => {
    return document.getElementById('trading-login-title') !== null
  }, 'login prompt reopens after second trade action')

  await page.evaluate(() => {
    const loginButton = document.querySelector('[data-trading-action="go-to-login"]')
    if (!(loginButton instanceof HTMLButtonElement)) {
      throw new Error('Login prompt CTA not found')
    }
    loginButton.click()
  })

  await page.waitForFunction(() => {
    const redirect = new URLSearchParams(window.location.search).get('redirect')
    return window.location.pathname === '/login' &&
      redirect === '/trade/spot/BTCUSDT' &&
      (document.body.textContent?.includes('专业交易终端登录') || document.body.textContent?.includes('Professional trading terminal login'))
  }, 'order button navigates to login page')

  await page.evaluateExpression("localStorage.setItem('fx-platform-auth-token', 'bad-token')")
  await page.navigate(`${tradingUrl}?case=invalid-token`)
  await page.waitForFunction(() => {
    const hasLoginOrderButton = document.querySelector('[data-trading-action="login-required"]') !== null
    const invalidTokenCleared = localStorage.getItem('fx-platform-auth-token') === null
    return hasLoginOrderButton && invalidTokenCleared
  }, 'invalid token falls back to login-required trading mode')

  await page.evaluateExpression(`localStorage.setItem('fx-platform-auth-token', ${JSON.stringify(validToken)})`)
  await page.navigate(`${tradingUrl}?case=valid-token`)
  await page.waitForFunction(() => {
    return document.querySelector(
      '[data-platform-view="pc"] [data-panel-id="trade"] [data-trading-action="submit-order"]:not(:disabled)'
    ) !== null
  }, 'valid token trading session becomes ready')

  await page.close()
  await verifyMobileTradeAction({ validToken })

  console.log(JSON.stringify({
    backendHealth: health.status,
    guestSessionStatus: session.data.status,
    guestSessionAuthenticated: session.data.authenticated,
    invalidSessionStatus: invalidSession.data.status,
    validSessionStatus: validSession.data.status,
    sessionProbeRequests,
    tradingUrl,
    finalPath: '/login?redirect=/trade/spot/BTCUSDT',
    invalidTokenUi: 'login-required fallback',
    validTokenUi: 'trading session ready'
  }, null, 2))
} finally {
  for (const child of processes.reverse()) {
    killProcessTree(child)
  }
}

async function verifyMobileTradeAction({ validToken }) {
  const chrome = await launchChrome({ windowSize: '390,844' })
  const page = await createCdpPage(chrome.port)

  await page.send('Network.enable')
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: 390,
    height: 844,
    deviceScaleFactor: 3,
    mobile: true
  })
  await page.navigate(`${tradingUrl}?mobile=guest`)
  await page.waitForFunction(() => {
    return [...document.querySelectorAll('button')]
      .some((button) => button.matches('[data-testid="mobile-trade-action"]'))
  }, 'mobile Trade action is visible')
  await page.evaluate(() => {
    const tradeButton = document.querySelector('[data-testid="mobile-trade-action"]')
    if (!(tradeButton instanceof HTMLButtonElement)) {
      throw new Error('Mobile Trade button not found')
    }
    tradeButton.click()
  })
  await page.waitForFunction(() => {
    return document.getElementById('trading-login-title') !== null
  }, 'mobile Trade action opens login prompt')

  await page.evaluateExpression(`localStorage.setItem('fx-platform-auth-token', ${JSON.stringify(validToken)})`)
  await page.navigate(`${tradingUrl}?mobile=valid-token`)
  await page.waitForFunction(() => {
    return [...document.querySelectorAll('button')]
      .some((button) => button.matches('[data-testid="mobile-trade-action"]'))
  }, 'mobile Trade action is visible for valid session')
  await page.evaluate(() => {
    const tradeButton = document.querySelector('[data-testid="mobile-trade-action"]')
    if (!(tradeButton instanceof HTMLButtonElement)) {
      throw new Error('Mobile Trade button not found')
    }
    tradeButton.click()
  })
  await page.waitForFunction(() => {
    const loginPromptOpen = document.getElementById('trading-login-title') !== null
    const orderSheetOpen = [...document.querySelectorAll('[aria-hidden="false"]')]
      .some((element) => {
        const sheetTitle = element.querySelector('#mobile-order-sheet-title')
        return sheetTitle !== null && element.querySelector('section[aria-label]') !== null
      })
    return orderSheetOpen && !loginPromptOpen
  }, 'mobile Trade action opens order sheet')

  await page.close()
}

async function ensureWebServer() {
  if (await canFetch(`${webBaseUrl}/trading`)) return

  const command = process.platform === 'win32' ? 'cmd.exe' : 'npm'
  const args = process.platform === 'win32'
    ? ['/d', '/s', '/c', `npm.cmd --workspace apps/web run dev -- --host ${webHost} --port ${webPort}`]
    : ['--workspace', 'apps/web', 'run', 'dev', '--', '--host', webHost, '--port', webPort]
  const child = spawn(command, args, {
    cwd: projectRoot,
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

async function launchChrome({ windowSize = '1280,900' } = {}) {
  const executable = chromeExecutable()
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-trading-login-gate-'))
  const child = spawn(executable, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    `--window-size=${windowSize}`,
    'about:blank'
  ], {
    stdio: ['ignore', 'pipe', 'pipe']
  })
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

  const send = (method, params = {}) => new Promise((resolve, reject) => {
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
    async evaluate(fn) {
      const result = await send('Runtime.evaluate', {
        expression: `(${fn})()`,
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
    async waitForFunction(fn, label) {
      await waitFor(() => this.evaluate(fn), label, 30000)
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

async function requestJson(url, label, init) {
  const response = await fetch(url, init)
  assert(response.ok, `${label} request failed with HTTP ${response.status}`)
  return response.json()
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

function assert(condition, message) {
  if (!condition) throw new Error(message)
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
