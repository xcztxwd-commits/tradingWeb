import { randomUUID } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const apiBaseUrl = process.env.API_BASE_URL ?? 'http://127.0.0.1:18086'
const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://127.0.0.1:5199'
const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const runId = process.env.BTCUSDT_PERP_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.BTCUSDT_PERP_SMOKE_EMAIL ?? `btcusdt-perp-50x+${runId}@example.com`
const userPassword = process.env.BTCUSDT_PERP_SMOKE_PASSWORD ?? 'Password123!'
const adminEmail = process.env.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
const adminPassword = process.env.ADMIN_SMOKE_PASSWORD ?? 'Password123!'
const browserAttempts = positiveIntegerEnv('BTCUSDT_PERP_BROWSER_ATTEMPTS', 2)

const SYMBOL = 'BTCUSDT'
const PRODUCT_TYPE = 'LINEAR_PERP'
const LEVERAGE = 50
const OPEN_QUANTITY = '0.01'
const PARTIAL_CLOSE_RATIO = 0.3
const QUOTES = {
  open: 100000,
  partial: 102000,
  final: 101000
}
const UI_TEXT_KEYS = ['OrderConfirmationDialog', 'positions.closeAllMarket']
const statusOk = new Set(['FILLED', 'PARTIALLY_FILLED'])

const results = []
const bugs = []
const phases = []
const context = {
  runId,
  userEmail,
  adminEmail,
  apiBaseUrl,
  webBaseUrl,
  symbol: SYMBOL,
  productType: PRODUCT_TYPE,
  leverage: LEVERAGE,
  quantity: OPEN_QUANTITY,
  partialCloseRatio: PARTIAL_CLOSE_RATIO,
  uiTextKeys: UI_TEXT_KEYS
}
const processes = []
const restoreActions = []
let userAccessToken
let adminAccessToken
let accountId
let baseline
let openPositionId
let originalQuantity
let partialOrderId
let finalCloseMode = 'web-ui'
let partialHistoryStatus = 'not-checked'

try {
  await step('backend health is up', async () => {
    const health = await rawJson('/actuator/health')
    assert(health.status === 'UP', `Expected actuator health UP, got ${JSON.stringify(health)}`)
    return { status: health.status }
  })

  await step('admin can enable deterministic BTCUSDT quotes', async () => {
    adminAccessToken = await login(adminEmail, adminPassword)
    const quote = await pushQuote('open', QUOTES.open)
    return { source: quote.source, bid: quote.bid, ask: quote.ask, mid: quote.mid }
  })

  await step('BTCUSDT is a local LINEAR_PERP test fixture', async () => {
    const fixture = await ensureBtcusdtLinearPerpFixture()
    return fixture
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

  await step('demo account, wallet, and ledger baseline are available', async () => {
    const accounts = await api('/api/accounts', { token: userAccessToken })
    assert(Array.isArray(accounts) && accounts.length > 0, 'Registered user must have at least one account')
    const demo = accounts.find((account) => String(account.accountType ?? '').toUpperCase().includes('DEMO')) ?? accounts[0]
    accountId = demo.id
    context.accountId = accountId

    baseline = await snapshot('baseline')
    assert(n(baseline.summary.balance) > 0, `Expected positive initial balance, got ${baseline.summary.balance}`)
    assertWalletsConsistent(baseline.walletBalances)
    return phaseDigest(baseline)
  })

  await step('market symbol, quote, and candles are queryable', async () => {
    const symbols = await api('/api/market/symbols')
    const symbol = symbols.find((item) => item.symbol === SYMBOL)
    assert(symbol, `${SYMBOL} must exist in /api/market/symbols`)
    assert(symbol.productType === PRODUCT_TYPE, `${SYMBOL} productType must be ${PRODUCT_TYPE}, got ${symbol.productType}`)
    assert(symbol.enabled === true, `${SYMBOL} must be enabled`)
    assert(symbol.tradable === true, `${SYMBOL} must be tradable`)

    const quote = await api('/api/market/quotes/BTCUSDT')
    assert(near(n(quote.mid ?? quote.markPrice ?? quote.ask), QUOTES.open, 20), `Expected open quote near 100000, got ${JSON.stringify(quote)}`)

    const candles = await candles()
    assert(Array.isArray(candles), '/api/chart/candles must return an array')
    return { productType: symbol.productType, enabled: symbol.enabled, tradable: symbol.tradable, quote, candles: candles.length }
  })

  await step('web UI submits BUY MARKET 0.01 BTCUSDT at 50x', async () => {
    const beforeOrders = await orders()
    const order = await openLongThroughWebUi(beforeOrders)
    assert(statusOk.has(order.status), `Open order must fill, got ${order.status}`)
    assert(order.side === 'BUY', `Expected BUY order, got ${order.side}`)
    assert(order.symbol === SYMBOL, `Expected ${SYMBOL} order, got ${order.symbol}`)
    assert(Number(order.leverage) === LEVERAGE, `Expected leverage 50, got ${order.leverage}`)

    const events = await orderEvents(order.id)
    assert(events.length > 0, 'Open order must expose order events')

    const current = await openBtcusdtPosition()
    assert(current, 'Open order must create an OPEN BTCUSDT position')
    assert(current.side === 'BUY', `Expected long/BUY position, got ${current.side}`)
    assert(Number(current.leverage) === LEVERAGE, `Expected position leverage 50, got ${current.leverage}`)
    assert(near(n(current.lots), n(OPEN_QUANTITY), 0.000001), `Expected position quantity 0.01, got ${current.lots}`)
    assert(near(n(current.openPrice), QUOTES.open, 50), `Expected entryPrice near 100000, got ${current.openPrice}`)
    assert(n(current.marginHeld) > 0, `Expected marginHeld > 0, got ${current.marginHeld}`)
    assert(n(current.maintenanceMargin) >= 0, `Expected maintenanceMargin >= 0, got ${current.maintenanceMargin}`)
    assert(n(current.liquidationPrice) < n(current.openPrice), `Expected liquidationPrice < entryPrice, got ${current.liquidationPrice} >= ${current.openPrice}`)

    openPositionId = current.id
    originalQuantity = n(current.lots)
    const opened = await snapshot('after-open', openPositionId)
    assert(n(opened.summary.usedMargin) > n(baseline.summary.usedMargin), 'usedMargin must increase after opening')
    assert(n(opened.summary.freeMargin) < n(baseline.summary.freeMargin), 'freeMargin must decrease after opening')
    return { orderId: order.id, orderStatus: order.status, events: events.map((event) => event.eventType ?? event.type ?? event.status), ...phaseDigest(opened) }
  })

  await step('quote at 102000 lifts floating PnL and equity', async () => {
    await pushQuote('partial', QUOTES.partial)
    const priced = await snapshot('after-102000', openPositionId)
    const position = requiredPosition(priced)
    assert(n(position.floatingPnl) > 0, `Expected floatingPnl > 0 after quote 102000, got ${position.floatingPnl}`)
    assert(n(position.liquidationPrice) < n(position.openPrice), 'estimatedLiquidationPrice must stay below entryPrice for long')
    assert(n(priced.summary.equity) > n(baseline.summary.equity), `Expected equity > baseline, got ${priced.summary.equity} <= ${baseline.summary.equity}`)
    return phaseDigest(priced)
  })

  await step('API SELL MARKET partially closes 30 percent', async () => {
    const partialQuantity = roundQuantity(originalQuantity * PARTIAL_CLOSE_RATIO)
    const closeOrder = await api('/api/trading/orders', {
      method: 'POST',
      token: userAccessToken,
      body: {
        accountId,
        symbol: SYMBOL,
        side: 'SELL',
        orderType: 'MARKET',
        leverage: 50,
        quantity: String(partialQuantity),
        lots: String(partialQuantity),
        clientOrderId: `btcusdt-perp-partial-close-${runId}`,
        idempotencyKey: `btcusdt-perp-partial-close-${runId}`
      }
    })
    partialOrderId = closeOrder.id
    assert(closeOrder.status === 'FILLED', `Partial close order must be FILLED, got ${closeOrder.status}`)
    const events = await orderEvents(closeOrder.id)
    assert(events.length > 0, 'Partial close order must expose order events')

    const afterPartial = await snapshot('after-partial-close', openPositionId)
    const position = requiredPosition(afterPartial)
    const remainingQuantity = n(position.lots)
    assert(near(remainingQuantity, originalQuantity * 0.7, 0.000001), `Expected remainingQuantity near 70%, got ${remainingQuantity}`)
    assert(n(position.realizedPnl) > 0, `Expected positive realizedPnl after partial close, got ${position.realizedPnl}`)
    assert(n(position.floatingPnl) > 0, `Expected remaining position floatingPnl > 0, got ${position.floatingPnl}`)
    assert(n(position.marginHeld) < n(phases.find((phase) => phase.phase === 'after-open')?.position?.marginHeld), 'marginHeld must fall after partial close')
    assert(n(afterPartial.summary.usedMargin) < n(phases.find((phase) => phase.phase === 'after-open')?.summary?.usedMargin), 'usedMargin must fall after partial close')
    assert(n(afterPartial.summary.freeMargin) > n(phases.find((phase) => phase.phase === 'after-open')?.summary?.freeMargin), 'freeMargin must rise after partial close')
    assertLedgerTypes(afterPartial.ledger, ['MARGIN_RELEASE', 'TRADE_PNL', 'TRADE_FEE'])

    partialHistoryStatus = afterPartial.positionHistory.some((positionInHistory) => positionInHistory.id === openPositionId)
      ? 'partial-close-is-recorded-in-position-history'
      : 'current-design: partial close does not create a position history row'
    return { orderId: closeOrder.id, events: events.length, partialQuantity, remainingQuantity, partialHistoryStatus, ...phaseDigest(afterPartial) }
  })

  await step('web UI closes the remaining position at 101000', async () => {
    await pushQuote('final', QUOTES.final)
    try {
      await closeRemainingThroughWebUi(openPositionId)
    } catch (error) {
      finalCloseMode = 'api-fallback-after-web-ui-failure'
      recordBug({
        id: 'BUG-UI-FINAL-CLOSE-CLICK',
        steps: ['Open BTCUSDT 50x long from /trading', 'Attempt to click Close all at market in the positions grid'],
        expected: 'The browser click should close the remaining open position.',
        actual: error instanceof Error ? error.message : String(error),
        rootCause: 'apps/web/src/pages/trading/components/BottomAccountPositionsGrid.tsx',
        fixFiles: ['apps/web/src/pages/trading/components/BottomAccountPositionsGrid.tsx', 'scripts/smoke-btcusdt-perp-50x.mjs'],
        tests: ['scripts/smoke-btcusdt-perp-50x.mjs'],
        retestCommand: 'npm run smoke:btcusdt-perp-50x',
        retestResult: 'API fallback used after UI click failure.'
      })
      await api(`/api/trading/positions/${encodeURIComponent(openPositionId)}/close?accountId=${encodeURIComponent(accountId)}`, {
        method: 'POST',
        token: userAccessToken,
        body: {}
      })
    }

    const closed = await snapshot('after-final-close', openPositionId)
    assert(!closed.openPositions.some((position) => position.symbol === SYMBOL && position.status === 'OPEN'), 'BTCUSDT must have no OPEN position after final close')
    const history = closed.positionHistory.find((position) => position.id === openPositionId)
    assert(history?.status === 'CLOSED', 'Position history must include the CLOSED BTCUSDT position')
    assert(n(closed.summary.usedMargin) <= n(baseline.summary.usedMargin) + 1, `usedMargin should return near baseline, got ${closed.summary.usedMargin}`)
    assert(n(closed.summary.openFloatingPnl ?? 0) === 0, `openFloatingPnl should be 0 after full close, got ${closed.summary.openFloatingPnl}`)
    assert(n(history.realizedPnl) > 0, `Final realizedPnl must remain positive, got ${history.realizedPnl}`)
    assertWalletsConsistent(closed.walletBalances)
    assertLedgerTypes(closed.ledger, ['MARGIN_RELEASE', 'TRADE_PNL', 'TRADE_FEE'])
    assertOrdersTraceable(await orders())
    detectKnownAccountingGaps(closed, history)
    return { closeMode: finalCloseMode, partialHistoryStatus, ...phaseDigest(closed) }
  })

  const report = await writeReport()
  console.log(JSON.stringify({ status: 'PASS', report, context, results, phases, bugs }, null, 2))
} catch (error) {
  const report = await writeReport(error)
  console.error(JSON.stringify({ status: 'FAIL', report, context, results, phases, bugs }, null, 2))
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
    const message = `${payload?.code ?? response.status}: ${payload?.message ?? text || 'Request failed'}`
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

async function pushQuote(label, mid) {
  const bid = String(mid - 0.5)
  const ask = String(mid + 0.5)
  const quote = await api('/api/admin/market/test-control/overrides', {
    method: 'POST',
    token: adminAccessToken,
    body: { symbol: SYMBOL, bid, ask, ttl: 'PT5M' }
  })
  assert(near(n(quote.mid ?? quote.markPrice ?? mid), mid, 20), `Quote override ${label} expected ${mid}, got ${JSON.stringify(quote)}`)
  return quote
}

async function ensureBtcusdtLinearPerpFixture() {
  const before = await symbols()
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
    restoreActions.push(async () => {
      await restoreSymbol(original)
    })
  } else {
    restoreActions.push(async () => {
      await runDbSql(`delete from market.symbols where symbol='${SYMBOL}'`)
    })
  }

  if (original) {
    await runDbSql(linearPerpUpdateSql())
  } else {
    await runDbSql(linearPerpInsertSql())
  }

  const after = await symbols()
  const updated = after.find((item) => item.symbol === SYMBOL)
  assert(updated?.productType === PRODUCT_TYPE, `Fixture update failed; productType=${updated?.productType}`)
  return {
    changed: true,
    previousProductType: original?.product_type ?? current?.productType ?? null,
    productType: updated.productType,
    leverage: updated.leverage
  }
}

async function restoreSymbol(original) {
  const assignments = Object.entries(original)
    .filter(([key]) => key !== 'id' && key !== 'created_at')
    .map(([key, value]) => `${key}=${sqlLiteral(value)}`)
    .join(', ')
  await runDbSql(`update market.symbols set ${assignments} where symbol=${sqlLiteral(original.symbol)}`)
}

function linearPerpUpdateSql() {
  return `
    update market.symbols set
      display_name='Bitcoin / Tether Perpetual',
      provider='demo',
      provider_symbol='BTCUSDT',
      asset_class='LINEAR_PERPETUAL',
      product_type='LINEAR_PERP',
      base_currency='BTC',
      quote_currency='USDT',
      pip_size=0.01,
      tick_size=0.1,
      lot_size=1,
      contract_size=1,
      contract_multiplier=1,
      min_lot=0.0001,
      max_lot=100,
      leverage=50,
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
    where symbol='BTCUSDT'
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
      '${randomUUID()}', 'BTCUSDT', 'Bitcoin / Tether Perpetual', 'demo', 'BTCUSDT',
      'LINEAR_PERPETUAL', 'LINEAR_PERP', 'BTC', 'USDT', 0.01, 0.1, 1, 1, 1,
      0.0001, 100, 50, 0, 'USDT', 'USDT', 0.005, 0.0005, true, true, true,
      true, true, true, false, 'Crypto', 1, now(), now()
    )
  `
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

async function symbols() {
  return api('/api/market/symbols')
}

async function candles() {
  const to = Date.now()
  const from = to - 60 * 60 * 1000
  return api(`/api/chart/candles?symbol=${SYMBOL}&timeframe=1m&from=${from}&to=${to}`)
}

async function orders() {
  return api('/api/trading/orders', { token: userAccessToken })
}

async function orderEvents(orderId) {
  return api(`/api/trading/orders/${encodeURIComponent(orderId)}/events`, { token: userAccessToken })
}

async function openPositions() {
  return api(`/api/trading/positions?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken })
}

async function positionHistory() {
  return api(`/api/trading/positions/history?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken })
}

async function openBtcusdtPosition() {
  const positions = await openPositions()
  return positions.find((position) => position.symbol === SYMBOL && position.status === 'OPEN')
}

async function snapshot(phase, positionId = openPositionId) {
  const [summary, walletBalances, assetLedger, ledger, openPositionRows, historyRows, orderRows, quote, candleRows] = await Promise.all([
    api(`/api/accounts/${encodeURIComponent(accountId)}/summary`, { token: userAccessToken }),
    api(`/api/accounts/${encodeURIComponent(accountId)}/wallet-balances`, { token: userAccessToken }),
    api(`/api/accounts/${encodeURIComponent(accountId)}/asset-ledger`, { token: userAccessToken }),
    api(`/api/ledger?accountId=${encodeURIComponent(accountId)}`, { token: userAccessToken }),
    openPositions(),
    positionHistory(),
    orders(),
    api('/api/market/quotes/BTCUSDT'),
    candles()
  ])
  const position =
    openPositionRows.find((row) => row.id === positionId) ??
    historyRows.find((row) => row.id === positionId) ??
    openPositionRows.find((row) => row.symbol === SYMBOL)
  const row = {
    phase,
    summary,
    walletBalances,
    assetLedger,
    ledger,
    openPositions: openPositionRows,
    positionHistory: historyRows,
    orders: orderRows,
    quote,
    candles: candleRows,
    position,
    analysis: position ? positionAnalysis(position, quote, summary) : null
  }
  phases.push(row)
  return row
}

function positionAnalysis(position, quote, summary) {
  const quantity = n(position.lots)
  const markPrice = n(position.markPrice ?? position.currentPrice ?? quote.mid ?? quote.ask)
  return {
    positionId: position.id,
    side: position.side,
    quantity,
    entryPrice: n(position.openPrice),
    currentPrice: n(position.currentPrice ?? quote.mid),
    markPrice,
    notional: quantity * markPrice,
    leverage: position.leverage,
    marginHeld: n(position.marginHeld),
    maintenanceMargin: n(position.maintenanceMargin),
    floatingPnl: n(position.floatingPnl),
    realizedPnl: n(position.realizedPnl),
    estimatedLiquidationPrice: n(position.liquidationPrice),
    accountEquity: n(summary.equity),
    usedMargin: n(summary.usedMargin),
    freeMargin: n(summary.freeMargin)
  }
}

function phaseDigest(snapshotRow) {
  return {
    phase: snapshotRow.phase,
    balance: snapshotRow.summary.balance,
    equity: snapshotRow.summary.equity,
    usedMargin: snapshotRow.summary.usedMargin,
    freeMargin: snapshotRow.summary.freeMargin,
    openFloatingPnl: snapshotRow.summary.openFloatingPnl,
    position: snapshotRow.analysis,
    walletBalances: snapshotRow.walletBalances.map((wallet) => ({
      asset: wallet.asset,
      total: wallet.total,
      available: wallet.available,
      locked: wallet.locked
    })),
    ledgerTypes: [...new Set(snapshotRow.ledger.map((entry) => entry.entryType))],
    assetLedgerTypes: [...new Set(snapshotRow.assetLedger.map((entry) => entry.entryType))],
    orderCount: snapshotRow.orders.length,
    historyCount: snapshotRow.positionHistory.length
  }
}

function requiredPosition(snapshotRow) {
  assert(snapshotRow.position, `${snapshotRow.phase} must include ${SYMBOL} position ${openPositionId}`)
  return snapshotRow.position
}

async function openLongThroughWebUi(beforeOrders) {
  await ensureWebServer()
  const startedAt = Date.now()
  await withBrowserPage('BTCUSDT open order browser verification', async (page) => {
    await prepareTradingPage(page)
    await page.evaluate(() => {
      const target = [...document.querySelectorAll('button')].find((button) => /Market|市价/i.test(button.textContent ?? ''))
      target?.click()
    })
    await page.evaluate((quantity) => {
      const section = document.querySelector('.trade-panel__side--buy')
      if (!(section instanceof HTMLElement)) throw new Error('BUY form not found')
      const input = [...section.querySelectorAll('input')]
        .find((candidate) => !(candidate instanceof HTMLInputElement && candidate.disabled) && /quantity|数量/i.test(candidate.getAttribute('aria-label') ?? ''))
      if (!(input instanceof HTMLInputElement)) throw new Error('BUY quantity input not found')
      input.focus()
      input.value = quantity
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    }, OPEN_QUANTITY)
    await page.evaluate(() => {
      const section = document.querySelector('.trade-panel__side--buy')
      if (!(section instanceof HTMLElement)) throw new Error('BUY form not found')
      const submit = [...section.querySelectorAll('button')].find((button) => /Buy|买入/i.test(button.textContent ?? ''))
      if (!(submit instanceof HTMLButtonElement)) throw new Error('BUY submit button not found')
      if (submit.disabled) throw new Error(`BUY submit button disabled: ${submit.textContent ?? ''}`)
      submit.click()
    })
    await page.waitForFunction(() => /Backend order submitted|后端订单已提交|已提交|FILLED/i.test(document.body.textContent ?? ''), 'BUY market order notice')
  })

  const previousIds = new Set(beforeOrders.map((order) => order.id))
  return waitFor(async () => {
    const afterOrders = await orders()
    const order = afterOrders
      .filter((row) => !previousIds.has(row.id))
      .filter((row) => row.symbol === SYMBOL && row.side === 'BUY')
      .sort((a, b) => Date.parse(b.createdAt ?? 0) - Date.parse(a.createdAt ?? 0))[0]
    return order && Date.parse(order.createdAt ?? startedAt) >= startedAt - 5000 ? order : false
  }, 'BUY order created by web UI', 30000)
}

async function closeRemainingThroughWebUi(positionId) {
  await ensureWebServer()
  await withBrowserPage('BTCUSDT final close browser verification', async (page) => {
    await prepareTradingPage(page)
    await page.evaluate(() => {
      const buttons = [...document.querySelectorAll('button')]
      const tab = buttons.find((button) => /Current positions|当前持仓|持仓/i.test(button.textContent ?? ''))
      tab?.click()
    })
    await page.waitForFunction((symbol) => document.body.textContent?.includes(symbol), 'position row visible', SYMBOL)
    await page.evaluate((symbol) => {
      const rows = [...document.querySelectorAll('tr')]
      const row = rows.find((candidate) => candidate.textContent?.includes(symbol))
      if (!(row instanceof HTMLTableRowElement)) throw new Error(`Position row for ${symbol} not found`)
      const close = [...row.querySelectorAll('button')].find((button) => /Close all at market|市价全平|平仓/i.test(button.textContent ?? ''))
      if (!(close instanceof HTMLButtonElement)) throw new Error('Close all at market button not found')
      if (close.disabled) throw new Error('Close all at market button is disabled')
      close.click()
    }, SYMBOL)
    await waitFor(async () => {
      const position = await openBtcusdtPosition()
      return !position
    }, 'position closed after web UI click', 30000)
  })
  const stillOpen = await openBtcusdtPosition()
  assert(!stillOpen || stillOpen.id !== positionId, 'Position remains open after web UI close click')
}

async function prepareTradingPage(page) {
  await page.send('Network.enable')
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.navigate(`${webBaseUrl}/markets?auth=${runId}`)
  await page.evaluate((token) => {
    localStorage.setItem('fx-platform-auth-token', token)
    localStorage.setItem('fx-trade-confirm-skip', 'true')
    localStorage.setItem('i18nextLng', 'en-US')
  }, userAccessToken)
  await page.navigate(`${webBaseUrl}/trading?category=crypto&symbol=${SYMBOL}&run=${runId}`)
  await page.waitForFunction((symbol) => {
    return window.location.pathname === '/trading' &&
      document.body.textContent?.includes(symbol) &&
      !document.querySelector('.state-panel--error')
  }, 'trading page ready', SYMBOL)
}

async function ensureWebServer() {
  if (await canFetch(`${webBaseUrl}/trading`)) return

  const webUrl = new URL(webBaseUrl)
  const webHost = webUrl.hostname || '127.0.0.1'
  const webPort = webUrl.port || '5199'
  const viteBin = join(dirname(require.resolve('vite/package.json')), 'bin', 'vite.js')
  const child = spawn(process.execPath, [viteBin, '--host', webHost, '--port', webPort, '--strictPort'], {
    cwd: join(projectRoot, 'apps', 'web'),
    env: { ...process.env, VITE_API_BASE_URL: apiBaseUrl },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })
  processes.push(child)
  let output = ''
  child.stdout.on('data', (chunk) => {
    output = appendTail(output, chunk)
  })
  child.stderr.on('data', (chunk) => {
    output = appendTail(output, chunk)
  })

  await waitFor(async () => {
    if (child.exitCode !== null) throw new Error(`Vite dev server exited early:\n${output}`)
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
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-btcusdt-perp-smoke-'))
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

function detectKnownAccountingGaps(closed, history) {
  if (!closed.assetLedger.some((entry) => ['MARGIN_RELEASE', 'TRADE_PNL', 'TRADE_FEE'].includes(entry.entryType))) {
    recordBug({
      id: 'BUG-ASSET-LEDGER-TRACEABILITY',
      steps: ['Open BTCUSDT LINEAR_PERP long', 'Partially close 30%', 'Fully close the remaining position', 'Query /api/accounts/{accountId}/asset-ledger'],
      expected: 'Asset ledger should trace close, fee, margin release, and PnL entries or expose an equivalent user-facing account ledger link.',
      actual: `assetLedgerTypes=${JSON.stringify([...new Set(closed.assetLedger.map((entry) => entry.entryType))])}`,
      rootCause: 'backend/src/main/java/com/fxplatform/account/controller/AccountController.java',
      fixFiles: ['backend/src/main/java/com/fxplatform/account/controller/AccountController.java', 'backend/src/main/java/com/fxplatform/account/service/AccountLedgerService.java'],
      tests: ['scripts/smoke-btcusdt-perp-50x.mjs'],
      retestCommand: 'npm run smoke:btcusdt-perp-50x',
      retestResult: 'Recorded during smoke; verify after ledger surface is extended.'
    })
  }

  const expectedRealized = n(phases.find((phase) => phase.phase === 'after-partial-close')?.position?.realizedPnl) +
    ((originalQuantity * 0.7) * (QUOTES.final - n(phases.find((phase) => phase.phase === 'after-open')?.position?.openPrice)))
  if (n(history.realizedPnl) > 0 && n(history.realizedPnl) + 0.5 < expectedRealized) {
    recordBug({
      id: 'BUG-CLOSE-API-CUMULATIVE-REALIZED-PNL',
      steps: ['Open BTCUSDT LINEAR_PERP long', 'Partially close 30% with SELL MARKET', 'Close remaining position through the UI close-all action'],
      expected: `Final closed position realizedPnl should include partial close plus final close, expected about ${expectedRealized.toFixed(6)} before final fees.`,
      actual: `history.realizedPnl=${history.realizedPnl}`,
      rootCause: 'backend/src/main/java/com/fxplatform/trading/service/PositionService.java',
      fixFiles: ['backend/src/main/java/com/fxplatform/trading/service/PositionService.java'],
      tests: ['scripts/smoke-btcusdt-perp-50x.mjs'],
      retestCommand: 'npm run smoke:btcusdt-perp-50x',
      retestResult: 'Recorded during smoke; verify after cumulative realizedPnl is fixed.'
    })
  }
}

function assertLedgerTypes(ledger, types) {
  for (const type of types) {
    assert(ledger.some((entry) => entry.entryType === type), `Ledger must include ${type}`)
  }
}

function assertOrdersTraceable(orderRows) {
  assert(orderRows.some((order) => order.symbol === SYMBOL && order.side === 'BUY' && statusOk.has(order.status)), 'Orders must include opening BUY')
  assert(orderRows.some((order) => order.id === partialOrderId && order.side === 'SELL' && order.status === 'FILLED'), 'Orders must include partial close SELL')
}

function assertWalletsConsistent(walletBalances) {
  for (const wallet of walletBalances) {
    const total = n(wallet.total)
    const available = n(wallet.available)
    const locked = n(wallet.locked)
    assert(total >= 0 && available >= 0 && locked >= 0, `Wallet balance must not be negative: ${JSON.stringify(wallet)}`)
    assert(near(total, available + locked, 0.000001), `Wallet total must equal available + locked: ${JSON.stringify(wallet)}`)
  }
}

function recordBug(bug) {
  if (bugs.some((existing) => existing.id === bug.id)) return
  bugs.push({
    'Bug 编号': bug.id,
    '复现步骤': bug.steps,
    '预期结果': bug.expected,
    '实际结果': bug.actual,
    '根因文件': bug.rootCause,
    '修复文件': bug.fixFiles,
    '新增/修改测试': bug.tests,
    '复测命令': bug.retestCommand,
    '复测结果': bug.retestResult
  })
}

async function writeReport(error) {
  const reportDir = join(projectRoot, 'test-results', 'btcusdt-perp-50x', runId)
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
  for (const child of processes.reverse()) {
    killProcessTree(child)
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
      const value = await check()
      if (value) return value
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

function near(actual, expected, tolerance) {
  return Math.abs(actual - expected) <= tolerance
}

function positiveIntegerEnv(name, fallback) {
  const value = Number(process.env[name] ?? fallback)
  return Number.isInteger(value) && value > 0 ? value : fallback
}

function sleep(timeoutMs) {
  return new Promise((resolve) => setTimeout(resolve, timeoutMs))
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
