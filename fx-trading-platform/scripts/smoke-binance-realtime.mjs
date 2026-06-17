import { pathToFileURL } from 'node:url'

const DEFAULT_BASE_URL = 'http://127.0.0.1:18090'
const DEFAULT_SYMBOL = 'BTCUSDT'

export async function runSmoke({ fetchImpl = fetch, env = process.env } = {}) {
  const baseUrl = env.API_BASE_URL ?? DEFAULT_BASE_URL
  const symbol = env.MARKET_REALTIME_SMOKE_SYMBOL ?? DEFAULT_SYMBOL
  const adminEmail = env.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
  const adminPassword = env.ADMIN_SMOKE_PASSWORD ?? 'Password123!'
  const expectedDesiredStreamCount = Number(env.MARKET_REALTIME_EXPECTED_STREAM_COUNT ?? 11)
  const quoteTimeoutMs = Number(env.MARKET_REALTIME_SMOKE_QUOTE_TIMEOUT_MS ?? 30000)
  const quotePollMs = Number(env.MARKET_REALTIME_SMOKE_QUOTE_POLL_MS ?? 1000)
  const results = []

  await step(results, 'actuator health is UP', async () => {
    const health = await rawJson(fetchImpl, baseUrl, '/actuator/health')
    assert(health.status === 'UP', 'Actuator health must be UP')
    return { status: health.status }
  })

  const adminToken = await step(results, 'admin can login', async () => {
    const auth = await api(fetchImpl, baseUrl, '/api/auth/login', {
      method: 'POST',
      body: { email: adminEmail, password: adminPassword }
    })
    assert(auth.accessToken, 'Admin login must return access token')
    assert(auth.role === 'ADMIN', 'Admin login must return ADMIN role')
    return auth.accessToken
  })

  await step(results, 'realtime admin status is enabled for symbol', async () => {
    const status = await api(fetchImpl, baseUrl, '/api/admin/market/realtime/status', { token: adminToken })
    assert(status.enabled === true, 'Realtime status must be enabled')
    assert(status.provider === 'binance', 'Realtime provider must be binance')
    assert(status.connected === true, 'Realtime status must be connected')
    assert(status.activeSymbols?.includes(symbol), `Active symbols must include ${symbol}`)
    assert(
      status.desiredStreamCount === expectedDesiredStreamCount,
      `Desired stream count must be ${expectedDesiredStreamCount}`
    )
    return {
      connected: status.connected,
      activeSymbols: status.activeSymbols,
      desiredStreamCount: status.desiredStreamCount
    }
  })

  await step(results, 'binance websocket quote is fresh through REST', async () => {
    const quote = await waitForLiveQuote(fetchImpl, baseUrl, symbol, quoteTimeoutMs, quotePollMs)
    return { source: quote.source, bid: quote.bid, ask: quote.ask, mid: quote.mid }
  })

  let overrideCreated = false
  try {
    await step(results, 'test-control override publishes deterministic quote through REST', async () => {
    const override = await api(fetchImpl, baseUrl, '/api/admin/market/test-control/overrides', {
      method: 'POST',
      token: adminToken,
      body: { symbol, bid: '100.00', ask: '102.00', ttl: 'PT1M' }
    })
    overrideCreated = true
    assert(override.source === 'test-control', 'Override response must use test-control source')

    const quote = await api(fetchImpl, baseUrl, `/api/market/quotes/${encodeURIComponent(symbol)}`)
    assert(quote.symbol === symbol, `Quote symbol must be ${symbol}`)
    assert(quote.source === 'test-control', 'Quote source must be test-control while override is active')
    assert(Number(quote.bid) > 0 && Number(quote.ask) > 0 && Number(quote.mid) > 0, 'Quote prices must be positive')
    return { source: quote.source, bid: quote.bid, ask: quote.ask, mid: quote.mid }
    })
  } catch (error) {
    if (overrideCreated) {
      await clearOverride(fetchImpl, baseUrl, adminToken, symbol)
      overrideCreated = false
    }
    throw error
  }

  await step(results, 'ending test-control override succeeds', async () => {
    await clearOverride(fetchImpl, baseUrl, adminToken, symbol)
    overrideCreated = false
    return { symbol }
  })

  return { baseUrl, symbol, results }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  runSmoke()
    .then((result) => {
      console.log(JSON.stringify(result, null, 2))
    })
    .catch((error) => {
      console.error(error instanceof Error ? error.message : String(error))
      process.exitCode = 1
    })
}

async function step(results, name, fn) {
  const startedAt = Date.now()
  try {
    const details = await fn()
    const result = { name, status: 'PASS', durationMs: Date.now() - startedAt, details }
    results.push(result)
    return details
  } catch (error) {
    results.push({
      name,
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error)
    })
    throw error
  }
}

async function api(fetchImpl, baseUrl, path, options = {}) {
  const response = await fetchImpl(`${baseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  })
  const payload = await parseJsonResponse(response)
  if (!response.ok || !payload.success) {
    throw new Error(payload.message ?? `Request failed: ${response.status}`)
  }
  return payload.data
}

async function rawJson(fetchImpl, baseUrl, path) {
  const response = await fetchImpl(`${baseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  return parseJsonResponse(response)
}

async function parseJsonResponse(response) {
  const text = await response.text()
  try {
    return text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`Expected JSON response from ${response.url}, got: ${text.slice(0, 120)}`)
  }
}

async function clearOverride(fetchImpl, baseUrl, adminToken, symbol) {
  await api(fetchImpl, baseUrl, `/api/admin/market/test-control/overrides/${encodeURIComponent(symbol)}`, {
    method: 'DELETE',
    token: adminToken
  })
}

async function waitForLiveQuote(fetchImpl, baseUrl, symbol, timeoutMs, pollMs) {
  const deadline = Date.now() + timeoutMs
  let lastQuote
  do {
    lastQuote = await api(fetchImpl, baseUrl, `/api/market/quotes/${encodeURIComponent(symbol)}`)
    if (
      lastQuote.symbol === symbol &&
      lastQuote.source === 'binance-ws-bookTicker' &&
      Number(lastQuote.bid) > 0 &&
      Number(lastQuote.ask) > 0 &&
      Number(lastQuote.mid) > 0
    ) {
      return lastQuote
    }
    await sleep(pollMs)
  } while (Date.now() < deadline)
  throw new Error(`Quote source must be binance-ws-bookTicker, got ${lastQuote?.source ?? 'missing'}`)
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}
