import { spawnSync } from 'node:child_process'

const baseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const runId = process.env.TRADING_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.TRADING_SMOKE_USER_EMAIL ?? `real-loop+${runId}@example.com`
const userPassword = process.env.TRADING_SMOKE_USER_PASSWORD ?? 'Password123!'
const adminEmail = process.env.ADMIN_SMOKE_EMAIL
const adminPassword = process.env.ADMIN_SMOKE_PASSWORD
const databaseUrl = process.env.DATABASE_URL
const requireDbAssertions = process.env.REQUIRE_DB_ASSERTIONS === 'true'
const requireAdminAssertions = process.env.REQUIRE_ADMIN_ASSERTIONS === 'true'
const results = []
const context = { runId, userEmail }
let userAccessToken
let adminAccessToken

await step('backend health is up', async () => {
  const health = await rawJson('/actuator/health')
  assert(health.status === 'UP', `Expected actuator health UP, got ${JSON.stringify(health)}`)
  return { status: health.status }
})

await step('user can register and receive a token', async () => {
  const auth = await api('/api/auth/register', {
    method: 'POST',
    body: { email: userEmail, phone: null, password: userPassword }
  })
  assert(auth.accessToken, 'Register must return accessToken')
  userAccessToken = auth.accessToken
  context.userId = auth.userId
  return { userId: auth.userId, role: auth.role, tokenIssued: true }
})

const account = await step('registered user has a demo account and initial ledger', async () => {
  const accounts = await api('/api/accounts', { token: userAccessToken })
  assert(Array.isArray(accounts) && accounts.length > 0, 'Register must create at least one demo account')
  const selected = accounts[0]
  const ledger = await api(`/api/ledger?accountId=${encodeURIComponent(selected.id)}`, { token: userAccessToken })
  assert(ledger.some((entry) => entry.entryType === 'DEMO_DEPOSIT'), 'Demo account must write initial ledger')
  context.accountId = selected.id
  return { accountId: selected.id, balance: selected.balance }
})

const symbol = await step('market symbol and quote are available', async () => {
  const symbols = await api('/api/market/symbols')
  const selected = symbols.find((item) => item.enabled && item.symbol === 'EURUSD') ??
    symbols.find((item) => item.enabled) ??
    symbols[0]
  assert(selected?.symbol, 'Market symbols must include an enabled symbol')
  const quote = await api(`/api/market/quotes/${encodeURIComponent(selected.symbol)}`)
  assert(Number(quote.bid) > 0 && Number(quote.ask) > 0, 'Quote must include positive bid/ask')
  context.symbol = selected.symbol
  context.quote = quote
  return { symbol: selected.symbol, bid: quote.bid, ask: quote.ask }
})

const marketOrder = await step('market order creates order, position and ledger', async () => {
  const order = await api('/api/trading/orders', {
    method: 'POST',
    token: userAccessToken,
    body: orderPayload({
      side: 'BUY',
      orderType: 'MARKET',
      quantity: '0.01',
      takeProfit: '',
      stopLoss: '',
      suffix: 'market'
    })
  })
  assert(order.status === 'FILLED' || order.status === 'PARTIALLY_FILLED', `Market order must fill, got ${order.status}`)
  assert(Number(order.filledQuantity) > 0, 'Market order must expose filledQuantity')
  assert(order.fee !== undefined && order.slippage !== undefined, 'Market order must expose fee and slippage fields')

  const [orders, positions, ledger] = await Promise.all([
    api('/api/trading/orders', { token: userAccessToken }),
    api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, { token: userAccessToken }),
    api(`/api/ledger?accountId=${encodeURIComponent(account.accountId)}`, { token: userAccessToken })
  ])
  assert(orders.some((item) => item.id === order.id), 'Order list must include market order')
  assert(positions.some((item) => item.symbol === symbol.symbol && item.status === 'OPEN'), 'Market order must create open position')
  assert(ledger.some((entry) => entry.entryType === 'MARGIN_HOLD'), 'Market order must write MARGIN_HOLD ledger')
  context.marketOrderId = order.id
  context.marketPositionId = positions.find((item) => item.symbol === symbol.symbol && item.status === 'OPEN')?.id
  return { orderId: order.id, status: order.status, positionId: context.marketPositionId }
})

await step('optional database rows exist for market order', async () => {
  const db = await assertDatabaseRows({
    orderId: marketOrder.orderId,
    accountId: account.accountId,
    positionId: marketOrder.positionId
  })
  return db
})

const pendingOrder = await step('limit order starts as pending then demo tick executor fills it', async () => {
  const order = await api('/api/trading/orders', {
    method: 'POST',
    token: userAccessToken,
    body: orderPayload({
      side: 'BUY',
      orderType: 'LIMIT',
      quantity: '0.01',
      requestedPrice: '999999',
      suffix: 'limit-fill'
    })
  })
  assert(order.status === 'PENDING', `Limit order must start PENDING, got ${order.status}`)

  const filled = await waitForOrderStatus(order.id, ['FILLED', 'PARTIALLY_FILLED'], 10000)
  assert(filled.status === 'FILLED' || filled.status === 'PARTIALLY_FILLED', `Pending order must be filled by executor, got ${filled.status}`)
  return { orderId: order.id, initialStatus: order.status, finalStatus: filled.status }
})

await step('TP/SL executor closes a protected position and writes ledger', async () => {
  const order = await api('/api/trading/orders', {
    method: 'POST',
    token: userAccessToken,
    body: orderPayload({
      side: 'BUY',
      orderType: 'MARKET',
      quantity: '0.01',
      takeProfit: '0.00001',
      suffix: 'tp'
    })
  })
  assert(order.status === 'FILLED' || order.status === 'PARTIALLY_FILLED', 'Protected market order must fill first')
  const positionsBefore = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, {
    token: userAccessToken
  })
  const protectedPosition = positionsBefore.find((item) => item.takeProfit !== null && item.status === 'OPEN')
  assert(protectedPosition, 'Protected order must create an open position with takeProfit')

  await waitFor(async () => {
    const positions = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, {
      token: userAccessToken
    })
    return !positions.some((item) => item.id === protectedPosition.id)
  }, 'TP/SL executor closes position', 10000)

  const ledger = await api(`/api/ledger?accountId=${encodeURIComponent(account.accountId)}`, {
    token: userAccessToken
  })
  assert(
    ledger.some((entry) => entry.entryType === 'MARGIN_RELEASE' && entry.referenceId === protectedPosition.id),
    'TP/SL close must write margin release ledger'
  )
  assert(
    ledger.some((entry) => entry.entryType === 'TRADE_PNL' && entry.referenceId === protectedPosition.id),
    'TP/SL close must write PnL ledger'
  )
  return { positionId: protectedPosition.id }
})

await step('user cancel syncs pending order to canceled state', async () => {
  const order = await api('/api/trading/orders', {
    method: 'POST',
    token: userAccessToken,
    body: orderPayload({
      side: 'BUY',
      orderType: 'LIMIT',
      quantity: '0.01',
      requestedPrice: '0.00001',
      suffix: 'cancel'
    })
  })
  assert(order.status === 'PENDING', 'Cancelable order must be pending')
  const canceled = await api(`/api/trading/orders/${order.id}/cancel`, {
    method: 'POST',
    token: userAccessToken,
    body: {}
  })
  assert(canceled.status === 'CANCELED', `Cancel endpoint must return CANCELED, got ${canceled.status}`)
  const events = await api(`/api/trading/orders/${order.id}/events`, { token: userAccessToken })
  assert(events.some((event) => event.eventType === 'ORDER_CANCELED'), 'Canceled order must expose ORDER_CANCELED event')
  context.canceledOrderId = order.id
  return { orderId: order.id, status: canceled.status }
})

await step('optional admin login is available', async () => {
  if (!adminEmail || !adminPassword) {
    if (requireAdminAssertions) {
      throw new Error('ADMIN_SMOKE_EMAIL and ADMIN_SMOKE_PASSWORD are required')
    }
    return { skipped: true, reason: 'ADMIN_SMOKE_EMAIL/ADMIN_SMOKE_PASSWORD not configured' }
  }
  const auth = await api('/api/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  })
  assert(auth.role === 'ADMIN', `Admin login must return ADMIN role, got ${auth.role}`)
  adminAccessToken = auth.accessToken
  return { userId: auth.userId, role: auth.role, tokenIssued: true }
})

await step('optional admin sees user cancel state and can force close', async () => {
  if (!adminAccessToken) {
    return { skipped: true, reason: 'admin credentials not configured' }
  }
  const adminOrders = await api(`/api/admin/trading/orders?status=CANCELED&page=0&size=50`, {
    token: adminAccessToken
  })
  assert(
    adminOrders.items.some((item) => item.id === context.canceledOrderId && item.status === 'CANCELED'),
    'Admin order list must show user canceled order'
  )

  const userPositionsBefore = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, {
    token: userAccessToken
  })
  const forceCloseTarget = userPositionsBefore.find((item) => item.status === 'OPEN')
  assert(forceCloseTarget, 'A market position must be available for admin force close')

  const closed = await api(`/api/admin/trading/positions/${forceCloseTarget.id}/force-close`, {
    method: 'POST',
    token: adminAccessToken,
    body: {
      accountId: account.accountId,
      reason: 'real trading loop smoke',
      idempotencyKey: `force-close-${runId}`
    }
  })
  assert(closed.status === 'CLOSED', 'Admin force close must close the position')
  const userPositionsAfter = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, {
    token: userAccessToken
  })
  assert(!userPositionsAfter.some((item) => item.id === forceCloseTarget.id), 'Force-closed position must disappear for user')
  const ledger = await api(`/api/ledger?accountId=${encodeURIComponent(account.accountId)}`, {
    token: userAccessToken
  })
  assert(
    ledger.some((entry) => entry.entryType === 'MARGIN_RELEASE' && entry.referenceId === forceCloseTarget.id),
    'Admin force close must write margin release ledger'
  )
  assert(
    ledger.some((entry) => entry.entryType === 'TRADE_PNL' && entry.referenceId === forceCloseTarget.id),
    'Admin force close must write PnL ledger'
  )
  return { positionId: forceCloseTarget.id, status: closed.status }
})

console.log(JSON.stringify({ baseUrl, context, results }, null, 2))

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
    console.error(JSON.stringify({ baseUrl, context, results }, null, 2))
    throw error
  }
}

function orderPayload({ side, orderType, quantity, requestedPrice, stopLoss, takeProfit, suffix }) {
  const key = `real-loop-${suffix}-${runId}`
  return {
    accountId: account.accountId,
    symbol: symbol.symbol,
    side,
    orderType,
    lots: quantity,
    quantity,
    price: requestedPrice || undefined,
    requestedPrice: requestedPrice || undefined,
    stopLoss: stopLoss || undefined,
    takeProfit: takeProfit || undefined,
    clientOrderId: key,
    idempotencyKey: key
  }
}

async function waitForOrderStatus(orderId, statuses, timeoutMs) {
  let lastOrder
  await waitFor(async () => {
    const orders = await api('/api/trading/orders', { token: userAccessToken })
    lastOrder = orders.find((item) => item.id === orderId)
    return lastOrder && statuses.includes(lastOrder.status)
  }, `order ${orderId} status ${statuses.join('/')}`, timeoutMs)
  return lastOrder
}

async function assertDatabaseRows({ orderId, accountId, positionId }) {
  if (!databaseUrl) {
    if (requireDbAssertions) throw new Error('DATABASE_URL is required for database assertions')
    return { skipped: true, reason: 'DATABASE_URL not configured' }
  }
  const checks = {
    orders: psqlCount('select count(*) from trading.orders where id = $1', orderId),
    trades: psqlCount('select count(*) from trading.trades where order_id = $1', orderId),
    positions: psqlCount('select count(*) from trading.positions where id = $1', positionId),
    ledger: psqlCount('select count(*) from ledger.ledger_entries where account_id = $1', accountId)
  }
  if (Object.values(checks).some((count) => count === null)) {
    return { skipped: true, reason: 'psql is not available or query failed', checks }
  }
  for (const [name, count] of Object.entries(checks)) {
    assert(count > 0, `Database must contain ${name} rows`)
  }
  return checks
}

function psqlCount(sql, value) {
  const result = spawnSync('psql', [databaseUrl, '-tAc', `${sql.replace('$1', `'${value}'`)}`], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.error || result.status !== 0) {
    if (requireDbAssertions) {
      throw new Error(`psql failed: ${result.error?.message ?? result.stderr}`)
    }
    return null
  }
  return Number(result.stdout.trim())
}

async function api(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
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
  const response = await fetch(`${baseUrl}${path}`)
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  return response.json()
}

async function waitFor(check, label, timeoutMs) {
  const startedAt = Date.now()
  let lastError
  while (Date.now() - startedAt < timeoutMs) {
    try {
      if (await check()) return
    } catch (error) {
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`${label} timed out${lastError ? `: ${lastError.message}` : ''}`)
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
