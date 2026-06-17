import { randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const apiBaseUrl = process.env.API_BASE_URL ?? 'http://127.0.0.1:18086'
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const runId = process.env.ETHUSDT_PERP_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.ETHUSDT_PERP_SMOKE_EMAIL ?? `ethusdt-perp-20x+${runId}@example.com`
const userPassword = process.env.ETHUSDT_PERP_SMOKE_PASSWORD ?? 'Password123!'
const adminEmail = process.env.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
const adminPassword = process.env.ADMIN_SMOKE_PASSWORD ?? 'Password123!'

const SYMBOL = 'ETHUSDT'
const PRODUCT_TYPE = 'LINEAR_PERP'
const LEVERAGE = 20
const QUOTES = {
  firstOpen: 3000,
  secondOpen: 3200,
  thirdOpen: 2800,
  partialClose: 3300,
  finalClose: 3100
}
const statusOk = new Set(['FILLED'])

const results = []
const phases = []
const bugs = []
const context = {
  runId,
  userEmail,
  adminEmail,
  apiBaseUrl,
  symbol: SYMBOL,
  productType: PRODUCT_TYPE,
  leverage: LEVERAGE
}
const restoreActions = []
const orderIds = []

let adminAccessToken
let userAccessToken
let accountId
let baseline
let openPositionId

try {
  await step('backend health is up', async () => {
    const health = await rawJson('/actuator/health')
    assert(health.status === 'UP', `Expected actuator health UP, got ${JSON.stringify(health)}`)
    return { status: health.status }
  })

  await step('admin can enable deterministic ETHUSDT quotes', async () => {
    adminAccessToken = await login(adminEmail, adminPassword)
    const quote = await pushQuote('firstOpen', QUOTES.firstOpen)
    return { source: quote.source, bid: quote.bid, ask: quote.ask, mid: quote.mid }
  })

  await step('ETHUSDT is a local LINEAR_PERP test fixture', async () => {
    return ensureEthusdtLinearPerpFixture()
  })

  await step('test user can register and log in', async () => {
    const registered = await api('/api/auth/register', {
      method: 'POST',
      body: { email: userEmail, phone: null, password: userPassword }
    })
    assert(registered.accessToken, 'Register must return accessToken')
    userAccessToken = await login(userEmail, userPassword)
    context.userId = registered.userId
    return { userId: registered.userId, registered: true, loggedIn: true }
  })

  await step('baseline summary wallet ledger and positions are available', async () => {
    const accounts = await api('/api/accounts', { token: userAccessToken })
    assert(Array.isArray(accounts) && accounts.length > 0, 'Registered user must have at least one account')
    const demo = accounts.find((account) => String(account.accountType ?? '').toUpperCase().includes('DEMO')) ?? accounts[0]
    accountId = demo.id
    context.accountId = accountId
    baseline = await snapshot('baseline', 'record baseline')
    assert(n(baseline.summary.balance) > 0, `Expected positive balance, got ${baseline.summary.balance}`)
    assert(!baseline.openPositions.some((position) => position.symbol === SYMBOL && position.status === 'OPEN'), 'Baseline must not have an ETHUSDT open position')
    assertWalletsConsistent(baseline.walletBalances)
    return phaseDigest(baseline)
  })

  await step('first BUY MARKET 1 ETH opens a long near 3000', async () => {
    await pushQuote('firstOpen', QUOTES.firstOpen)
    const order = await submitMarketOrder('BUY', '1', 'first-open')
    assert(statusOk.has(order.status), `First order must be FILLED, got ${order.status}`)
    const row = await snapshot('after-first-open', 'BUY MARKET 1 ETH at 3000', order.id)
    const position = requiredOpenPosition(row)
    openPositionId = position.id
    assertNear(n(position.lots), 1, 0.000001, `Expected quantity 1, got ${position.lots}`)
    assertNear(n(position.openPrice), 3000, 5, `Expected entryPrice near 3000, got ${position.openPrice}`)
    assert(n(position.marginHeld) > 0, `Expected marginHeld > 0, got ${position.marginHeld}`)
    return { orderId: order.id, ...phaseDigest(row) }
  })

  await step('second BUY MARKET 1 ETH merges into one long near 3100 average', async () => {
    await pushQuote('secondOpen', QUOTES.secondOpen)
    const before = latestPhase()
    const order = await submitMarketOrder('BUY', '1', 'second-open')
    assert(statusOk.has(order.status), `Second order must be FILLED, got ${order.status}`)
    const row = await snapshot('after-second-open', 'BUY MARKET 1 ETH at 3200', order.id)
    const position = requiredOpenPosition(row)
    assert(position.id === openPositionId, 'Same-side add must keep one net ETHUSDT position')
    assertOpenPositionCount(row, 1)
    assertNear(n(position.lots), 2, 0.000001, `Expected quantity 2, got ${position.lots}`)
    assertNear(n(position.openPrice), 3100, 5, `Expected entryPrice near 3100, got ${position.openPrice}`)
    assert(n(position.marginHeld) > n(before.position.marginHeld), 'marginHeld must increase after second add')
    assert(n(row.summary.usedMargin) > n(before.summary.usedMargin), 'usedMargin must increase after second add')
    return { orderId: order.id, ...phaseDigest(row) }
  })

  await step('third BUY MARKET 2 ETH reaches four ETH and weighted average near 2950', async () => {
    await pushQuote('thirdOpen', QUOTES.thirdOpen)
    const before = latestPhase()
    const order = await submitMarketOrder('BUY', '2', 'third-open')
    assert(statusOk.has(order.status), `Third order must be FILLED, got ${order.status}`)
    const row = await snapshot('after-third-open', 'BUY MARKET 2 ETH at 2800', order.id)
    const position = requiredOpenPosition(row)
    assert(position.id === openPositionId, 'Third add must keep the original net position')
    assertOpenPositionCount(row, 1)
    assertNear(n(position.lots), 4, 0.000001, `Expected quantity 4, got ${position.lots}`)
    assertNear(n(position.openPrice), 2950, 5, `Expected weighted entryPrice near 2950, got ${position.openPrice}`)
    assertNear(n(position.notional), n(position.marginHeld) * LEVERAGE, 2, `Expected notional to match 20x margin, got notional=${position.notional} marginHeld=${position.marginHeld}`)
    assert(n(position.marginHeld) > n(before.position.marginHeld), 'marginHeld must increase after third add')
    return { orderId: order.id, ...phaseDigest(row) }
  })

  await step('quote at 3300 gives positive floating PnL before partial close', async () => {
    await pushQuote('partialClose', QUOTES.partialClose)
    const row = await snapshot('before-partial-close', 'mark ETHUSDT at 3300')
    const position = requiredOpenPosition(row)
    assert(n(position.floatingPnl) > 0, `Expected floatingPnl > 0 at 3300, got ${position.floatingPnl}`)
    return phaseDigest(row)
  })

  await step('SELL MARKET 50 percent partially closes and keeps remaining two ETH open', async () => {
    const before = latestPhase()
    const partialQuantity = String(roundQuantity(n(before.position.lots) * 0.5))
    const order = await submitMarketOrder('SELL', partialQuantity, 'partial-close')
    assert(statusOk.has(order.status), `Partial close order must be FILLED, got ${order.status}`)
    const row = await snapshot('after-partial-close', 'SELL MARKET 50% at 3300', order.id, partialQuantity)
    const position = requiredOpenPosition(row)
    assert(position.id === openPositionId, 'Partial close must keep the original position open')
    assertNear(n(position.lots), 2, 0.000001, `Expected remaining quantity 2, got ${position.lots}`)
    assert(n(position.realizedPnl) > 0, `Expected realizedPnl > 0 after partial close, got ${position.realizedPnl}`)
    assert(n(position.floatingPnl) > 0, `Expected remaining floatingPnl > 0, got ${position.floatingPnl}`)
    assert(n(position.marginHeld) < n(before.position.marginHeld), 'marginHeld must fall after partial close')
    assert(n(row.summary.usedMargin) < n(before.summary.usedMargin), 'usedMargin must fall after partial close')
    assertLedgerTypes(row.ledger, ['MARGIN_RELEASE', 'TRADE_PNL'])
    return { orderId: order.id, partialQuantity, ...phaseDigest(row) }
  })

  await step('SELL MARKET remaining two ETH fully closes at 3100', async () => {
    await pushQuote('finalClose', QUOTES.finalClose)
    const before = await snapshot('before-final-close', 'mark ETHUSDT at 3100')
    const position = requiredOpenPosition(before)
    const order = await submitMarketOrder('SELL', String(n(position.lots)), 'final-close')
    assert(statusOk.has(order.status), `Final close order must be FILLED, got ${order.status}`)
    const row = await snapshot('after-final-close', 'SELL MARKET remaining 2 ETH at 3100', order.id, String(n(position.lots)))
    assert(!row.openPositions.some((item) => item.symbol === SYMBOL && item.status === 'OPEN'), 'ETHUSDT open position must not exist after full close')
    const history = row.positionHistory.find((item) => item.id === openPositionId)
    assert(history?.status === 'CLOSED', 'Position history must include CLOSED ETHUSDT position')
    assert(n(history.realizedPnl) > 0, `Closed history realizedPnl must be positive, got ${history.realizedPnl}`)
    assert(n(row.summary.usedMargin) <= n(baseline.summary.usedMargin) + 1, `usedMargin must be released after full close, got ${row.summary.usedMargin}`)
    assertOrdersTraceable(row.orders)
    assertTradesTraceable(row.adminTrades)
    assertOrderEventsTraceable(row.orderEvents)
    assertWalletsConsistent(row.walletBalances)
    assertLedgerTypes(row.ledger, ['MARGIN_RELEASE', 'TRADE_PNL'])
    detectAccountingGaps(row, history)
    return { orderId: order.id, ...phaseDigest(row) }
  })

  const report = await writeReport()
  console.log(JSON.stringify({ status: 'PASS', report, context, results, phases: phases.map(phaseDigest), bugs }, null, 2))
} catch (error) {
  const report = await writeReport(error)
  console.error(JSON.stringify({ status: 'FAIL', report, context, results, phases: phases.map(phaseDigest), bugs }, null, 2))
  throw error
} finally {
  await cleanup()
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
    throw error
  }
}

async function login(email, password) {
  const loggedIn = await api('/api/auth/login', {
    method: 'POST',
    body: { email, password }
  })
  assert(loggedIn.accessToken, `Login for ${email} must return accessToken`)
  return loggedIn.accessToken
}

async function submitMarketOrder(side, quantity, suffix) {
  const key = `ethusdt-perp-20x-${suffix}-${runId}`
  const order = await api('/api/trading/orders', {
    method: 'POST',
    token: userAccessToken,
    body: {
      accountId,
      symbol: SYMBOL,
      side,
      orderType: 'MARKET',
      leverage: LEVERAGE,
      quantity,
      lots: quantity,
      clientOrderId: key,
      idempotencyKey: key
    }
  })
  orderIds.push(order.id)
  return order
}

async function snapshot(phase, action, orderId = null, closedQuantity = null) {
  const [accounts, summary, walletBalances, assetLedger, ledger, openPositions, positionHistory, orderRows, adminTradeRows] = await Promise.all([
    api('/api/accounts', { token: userAccessToken }),
    api(`/api/accounts/${encodeURIComponent(accountId)}/summary`, { token: userAccessToken }),
    api(`/api/accounts/${encodeURIComponent(accountId)}/wallet-balances`, { token: userAccessToken }),
    api(`/api/accounts/${encodeURIComponent(accountId)}/asset-ledger`, { token: userAccessToken }),
    api(`/api/ledger?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken }),
    api(`/api/trading/positions?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken }),
    api(`/api/trading/positions/history?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken }),
    api('/api/trading/orders', { token: userAccessToken }),
    fetchAdminTrades()
  ])
  const orderEvents = {}
  for (const id of orderIds) {
    orderEvents[id] = await api(`/api/trading/orders/${encodeURIComponent(id)}/events`, { token: userAccessToken })
  }
  const position = openPosition(openPositions) ?? positionHistory.find((item) => item.id === openPositionId) ?? null
  const row = {
    phase,
    action,
    orderId,
    closedQuantity,
    accounts,
    summary,
    walletBalances,
    assetLedger,
    ledger,
    openPositions,
    positionHistory,
    orders: orderRows,
    adminTrades: adminTradeRows,
    orderEvents,
    position
  }
  phases.push(row)
  return row
}

async function fetchAdminTrades() {
  if (!adminAccessToken) return []
  const page = await api('/api/admin/trading/trades?page=0&size=200', { token: adminAccessToken })
  return Array.isArray(page?.items) ? page.items : []
}

function openPosition(rows) {
  return rows.find((item) => item.symbol === SYMBOL && item.status === 'OPEN') ?? null
}

function requiredOpenPosition(row) {
  const position = openPosition(row.openPositions)
  assert(position, `${row.phase} must include an OPEN ${SYMBOL} position`)
  assert(position.notional !== undefined, `${row.phase} position response must expose notional`)
  return position
}

function assertOpenPositionCount(row, expected) {
  const count = row.openPositions.filter((item) => item.symbol === SYMBOL && item.status === 'OPEN').length
  assert(count === expected, `Expected ${expected} OPEN ${SYMBOL} position(s), got ${count}`)
}

function assertOrdersTraceable(orderRows) {
  for (const id of orderIds) {
    const order = orderRows.find((item) => item.id === id)
    assert(order, `Order ${id} must be visible in /api/trading/orders`)
    assert(order.status === 'FILLED', `Order ${id} must be FILLED, got ${order.status}`)
    assert(n(order.filledQuantity) > 0, `Order ${id} must expose filledQuantity`)
  }
}

function assertTradesTraceable(trades) {
  for (const id of orderIds) {
    assert(trades.some((trade) => trade.orderId === id || trade.order_id === id), `Admin trades must include order ${id}`)
  }
}

function assertOrderEventsTraceable(orderEvents) {
  for (const id of orderIds) {
    const events = orderEvents[id] ?? []
    assert(events.length > 0, `Order ${id} must expose events`)
  }
}

function assertLedgerTypes(ledger, types) {
  for (const type of types) {
    assert(ledger.some((entry) => entry.entryType === type), `Ledger must include ${type}`)
  }
}

function assertWalletsConsistent(walletBalances) {
  for (const wallet of walletBalances) {
    const total = n(wallet.total)
    const available = n(wallet.available)
    const locked = n(wallet.locked)
    assert(total >= 0 && available >= 0 && locked >= 0, `Wallet balance must not be negative: ${JSON.stringify(wallet)}`)
    assertNear(total, available + locked, 0.000001, `Wallet total must equal available + locked: ${JSON.stringify(wallet)}`)
  }
}

function detectAccountingGaps(row, history) {
  if (!row.assetLedger.some((entry) => ['MARGIN_RELEASE', 'TRADE_PNL', 'TRADE_FEE'].includes(entry.entryType))) {
    recordBug({
      id: 'BUG-ASSET-LEDGER-POSITION-CLOSE-TRACEABILITY',
      expected: '/api/accounts/{accountId}/asset-ledger should expose close, PnL, fee, or an equivalent user-visible trace.',
      actual: `assetLedgerTypes=${JSON.stringify([...new Set(row.assetLedger.map((entry) => entry.entryType))])}`,
      retest: 'npm run smoke:ethusdt-perp-20x'
    })
  }
  if (n(history.realizedPnl) <= 0) {
    recordBug({
      id: 'BUG-POSITION-HISTORY-REALIZED-PNL',
      expected: 'Closed position history should retain positive cumulative realizedPnl.',
      actual: `history.realizedPnl=${history.realizedPnl}`,
      retest: 'npm run smoke:ethusdt-perp-20x'
    })
  }
}

function recordBug(bug) {
  if (bugs.some((existing) => existing.id === bug.id)) return
  bugs.push(bug)
}

function phaseDigest(row) {
  const position = row.position ?? {}
  const wallet = aggregateWallet(row.walletBalances ?? [])
  const history = row.positionHistory?.find((item) => item.id === openPositionId)
  return {
    phase: row.phase,
    action: row.action,
    positionId: position.id ?? openPositionId ?? null,
    side: position.side ?? null,
    quantity: position.lots ?? null,
    entryPrice: position.openPrice ?? null,
    currentPrice: position.currentPrice ?? null,
    markPrice: position.markPrice ?? null,
    notional: position.notional ?? null,
    leverage: position.leverage ?? null,
    marginHeld: position.marginHeld ?? null,
    maintenanceMargin: position.maintenanceMargin ?? null,
    estimatedLiquidationPrice: position.liquidationPrice ?? estimateLiquidationPrice(position),
    floatingPnl: position.floatingPnl ?? null,
    realizedPnl: position.realizedPnl ?? history?.realizedPnl ?? null,
    closedQuantity: row.closedQuantity ?? null,
    accountBalance: row.summary?.balance ?? null,
    equity: row.summary?.equity ?? null,
    usedMargin: row.summary?.usedMargin ?? null,
    freeMargin: row.summary?.freeMargin ?? null,
    wallet: wallet ? `${wallet.total}/${wallet.available}/${wallet.locked}` : null,
    ledgerDelta: baseline ? (row.ledger?.length ?? 0) - (baseline.ledger?.length ?? 0) : 0,
    ordersStatus: orderIds.map((id) => row.orders?.find((order) => order.id === id)?.status ?? 'missing'),
    tradesCount: row.adminTrades?.filter((trade) => orderIds.includes(trade.orderId ?? trade.order_id)).length ?? 0,
    historyStatus: history?.status ?? null
  }
}

function aggregateWallet(walletBalances) {
  if (!Array.isArray(walletBalances) || walletBalances.length === 0) return null
  return walletBalances.reduce((total, wallet) => ({
    total: total.total + n(wallet.total),
    available: total.available + n(wallet.available),
    locked: total.locked + n(wallet.locked)
  }), { total: 0, available: 0, locked: 0 })
}

function latestPhase() {
  return phases[phases.length - 1]
}

function estimateLiquidationPrice(position) {
  const entry = n(position.openPrice)
  const leverage = n(position.leverage) || LEVERAGE
  const maintenanceRate = n(position.maintenanceMarginRate) || 0.005
  const liquidationFeeRate = 0.0005
  if (!entry || !leverage) return null
  if (position.side === 'SELL') {
    return entry * (1 + 1 / leverage) / (1 + maintenanceRate + liquidationFeeRate)
  }
  return entry * (1 - 1 / leverage) / (1 - maintenanceRate - liquidationFeeRate)
}

async function pushQuote(label, mid) {
  const bid = String(mid - 0.01)
  const ask = String(mid + 0.01)
  const quote = await api('/api/admin/market/test-control/overrides', {
    method: 'POST',
    token: adminAccessToken,
    body: { symbol: SYMBOL, bid, ask, ttl: 'PT5M' }
  })
  assertNear(n(quote.mid ?? quote.markPrice), mid, 0.05, `Quote ${label} expected ${mid}, got ${JSON.stringify(quote)}`)
  return quote
}

async function ensureEthusdtLinearPerpFixture() {
  const before = await api('/api/market/symbols')
  const current = before.find((item) => item.symbol === SYMBOL)
  if (
    current?.productType === PRODUCT_TYPE &&
    current.enabled === true &&
    current.tradable === true &&
    current.quoteEnabled === true &&
    Number(current.leverage ?? 0) >= LEVERAGE
  ) {
    return { changed: false, productType: current.productType, leverage: current.leverage }
  }

  const existingJson = await runDbSql(`select coalesce((select row_to_json(s)::text from (select * from market.symbols where symbol='${SYMBOL}') s), '')`)
  const original = existingJson.trim() ? JSON.parse(existingJson.trim()) : null
  if (original) {
    restoreActions.push(() => restoreSymbol(original))
    await runDbSql(linearPerpUpdateSql())
  } else {
    restoreActions.push(() => runDbSql(`delete from market.symbols where symbol='${SYMBOL}'`))
    await runDbSql(linearPerpInsertSql())
  }

  const after = await api('/api/market/symbols')
  const updated = after.find((item) => item.symbol === SYMBOL)
  assert(updated?.productType === PRODUCT_TYPE, `Fixture update failed; productType=${updated?.productType}`)
  assert(updated.tradable === true && updated.quoteEnabled === true, 'ETHUSDT fixture must be tradable and quote-enabled')
  return {
    changed: true,
    previousProductType: original?.product_type ?? current?.productType ?? null,
    productType: updated.productType,
    leverage: updated.leverage
  }
}

function linearPerpUpdateSql() {
  return `
    update market.symbols set
      display_name='Ethereum / Tether Perpetual',
      provider='demo',
      provider_symbol='${SYMBOL}',
      asset_class='LINEAR_PERPETUAL',
      product_type='LINEAR_PERP',
      base_currency='ETH',
      quote_currency='USDT',
      pip_size=0.01,
      tick_size=0.01,
      lot_size=1,
      contract_size=1,
      contract_multiplier=1,
      min_lot=0.01,
      max_lot=1000,
      leverage=20,
      spread_markup=0,
      settlement_asset='USDT',
      margin_asset='USDT',
      maintenance_margin_rate=0.005,
      liquidation_fee_rate=0.0005,
      enabled=true,
      display_enabled=true,
      quote_enabled=true,
      chart_enabled=true,
      order_book_enabled=true,
      tradable=true,
      updated_at=now()
    where symbol='${SYMBOL}'
  `
}

function linearPerpInsertSql() {
  return `
    insert into market.symbols (
      id, symbol, display_name, provider, provider_symbol, asset_class, product_type,
      base_currency, quote_currency, pip_size, tick_size, lot_size, contract_size,
      contract_multiplier, min_lot, max_lot, leverage, spread_markup, settlement_asset,
      margin_asset, maintenance_margin_rate, liquidation_fee_rate, enabled,
      display_enabled, quote_enabled, chart_enabled, order_book_enabled, tradable,
      featured, display_group, display_order, created_at, updated_at
    ) values (
      '${randomUUID()}', '${SYMBOL}', 'Ethereum / Tether Perpetual', 'demo', '${SYMBOL}',
      'LINEAR_PERPETUAL', 'LINEAR_PERP', 'ETH', 'USDT', 0.01, 0.01, 1, 1, 1,
      0.01, 1000, 20, 0, 'USDT', 'USDT', 0.005, 0.0005, true, true, true,
      true, true, true, false, 'Crypto', 2, now(), now()
    )
  `
}

async function restoreSymbol(original) {
  const assignments = Object.entries(original)
    .filter(([key]) => key !== 'id' && key !== 'created_at')
    .map(([key, value]) => `${key}=${sqlLiteral(value)}`)
    .join(', ')
  await runDbSql(`update market.symbols set ${assignments} where symbol=${sqlLiteral(original.symbol)}`)
}

async function runDbSql(sql) {
  const result = spawnSync('docker', ['exec', 'fx-platform-postgres', 'psql', '-U', 'postgres', '-d', 'fx_platform', '-tAc', sql], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.status !== 0) {
    throw new Error(`Postgres fixture command failed. Start local DB with docker compose -f fx-trading-platform/infra/docker-compose.yml up -d.\n${result.stderr || result.stdout}`)
  }
  return result.stdout
}

async function writeReport(error) {
  const reportDir = join(projectRoot, 'test-results', 'ethusdt-perp-20x', runId)
  await mkdir(reportDir, { recursive: true })
  const reportPath = join(reportDir, 'report.json')
  await writeFile(reportPath, JSON.stringify({
    status: error ? 'FAIL' : 'PASS',
    error: error instanceof Error ? error.message : error ? String(error) : null,
    context,
    results,
    phases: phases.map(phaseDigest),
    bugs
  }, null, 2))
  return reportPath
}

async function cleanup() {
  if (adminAccessToken) {
    await api(`/api/admin/market/test-control/overrides/${SYMBOL}`, {
      method: 'DELETE',
      token: adminAccessToken
    }).catch(() => undefined)
  }
  for (const action of restoreActions.reverse()) {
    await action().catch((error) => {
      console.error(`Fixture cleanup failed: ${error instanceof Error ? error.message : String(error)}`)
    })
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
  const text = await response.text()
  const payload = text ? JSON.parse(text) : null
  if (!response.ok || payload?.success === false) {
    const message = `${payload?.code ?? response.status}: ${(payload?.message ?? text) || 'Request failed'}`
    if (message.includes('MARKET_TEST_CONTROL_DISABLED')) {
      throw new Error(`${message}. Start backend with MARKET_TEST_CONTROL_ENABLED=true for this smoke.`)
    }
    throw new Error(message)
  }
  return payload?.data ?? payload
}

async function rawJson(path) {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  return response.json()
}

function sqlLiteral(value) {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'number') return String(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return `'${String(value).replace(/'/g, "''")}'`
}

function roundQuantity(value) {
  return Number(value.toFixed(8))
}

function n(value) {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

function assertNear(actual, expected, tolerance, message) {
  assert(Math.abs(actual - expected) <= tolerance, `${message}; tolerance=${tolerance}`)
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
