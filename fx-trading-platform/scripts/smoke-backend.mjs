const baseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const symbol = 'BTCUSDT'
const email = `smoke+${Date.now()}@example.com`
const password = 'Password123!'

const health = await request('/actuator/health', { raw: true })
assert(health.status === 'UP', 'Health endpoint must be UP')

const auth = await request('/api/auth/register', {
  method: 'POST',
  body: { email, phone: null, password }
})
const token = auth.accessToken
assert(token, 'Register must return access token')

const accounts = await request('/api/accounts', { token })
const demo = accounts.find((account) => account.accountType === 'DEMO' && account.status === 'ACTIVE')
assert(demo, 'Register must create one active Demo account')
const accountId = demo.id

const summaryBeforeMarket = await accountSummary()
const walletsBeforeMarket = await walletBalances()
assertWalletConservation(walletsBeforeMarket, 'before market order')
const usdtBeforeMarket = wallet(walletsBeforeMarket, 'USDT')
const btcBeforeMarket = wallet(walletsBeforeMarket, 'BTC')
const marketOrder = await createOrder({
  orderType: 'MARKET',
  quantity: '10',
  quantityUnit: 'QUOTE',
  label: 'market'
})
assert(marketOrder.status === 'FILLED', 'Spot market order must fill')

const walletsAfterMarket = await walletBalances()
assertWalletConservation(walletsAfterMarket, 'after market order')
const usdtAfterMarket = wallet(walletsAfterMarket, 'USDT')
const btcAfterMarket = wallet(walletsAfterMarket, 'BTC')
assert(usdtAfterMarket.total < usdtBeforeMarket.total, 'market order must debit Spot USDT')
assert(btcAfterMarket.total > btcBeforeMarket.total, 'market order must credit Spot BTC')
const summaryAfterMarket = await accountSummary()
assertAccountSummaryUnchanged(summaryBeforeMarket, summaryAfterMarket, 'Spot market order')
const ledgerAfterMarket = await assetLedger()
assert(ledgerAfterMarket.some((entry) =>
  entry.walletType === 'SPOT' && entry.asset === 'USDT' && entry.entryType === 'SPOT_BUY_DEBIT'),
'market buy must create a Spot USDT asset-ledger debit')
assert(ledgerAfterMarket.some((entry) =>
  entry.walletType === 'SPOT' && entry.asset === 'BTC' && entry.entryType === 'SPOT_BUY_CREDIT'),
'market buy must create a Spot BTC asset-ledger credit')

const quote = await request(`/api/market/quotes/${symbol}`)
const referencePrice = Number(quote.bid ?? quote.mid ?? quote.ask)
assert(Number.isFinite(referencePrice) && referencePrice > 0, 'BTCUSDT quote must expose a positive price')
const nonMarketablePrice = (Math.floor(referencePrice * 5) / 10).toFixed(1)
const pendingLimit = await createOrder({
  orderType: 'LIMIT',
  quantity: '0.001',
  quantityUnit: 'BASE',
  price: nonMarketablePrice,
  label: 'limit'
})
assert(!['FILLED', 'CANCELED', 'CANCELLED', 'REJECTED', 'EXPIRED'].includes(pendingLimit.status),
  `non-marketable Spot limit must remain active, got ${pendingLimit.status}`)

const walletsWithLimit = await walletBalances()
assertWalletConservation(walletsWithLimit, 'with pending limit')
assert(wallet(walletsWithLimit, 'USDT').locked > usdtAfterMarket.locked,
  'non-marketable limit must lock Spot USDT')

const orders = await loadAllPages('/api/trading/orders', accountId, token, 1)
assert(orders.some((order) => order.id === marketOrder.id), 'Market order must appear in order history')
assert(orders.some((order) => order.id === pendingLimit.id), 'Limit order must appear in order history')

const positions = await loadAllPages('/api/trading/positions', accountId, token, 1)
assert(!positions.some((position) => position.symbol === symbol),
  'Spot fills must not create leveraged positions')

const canceledLimit = await request(`/api/trading/orders/${pendingLimit.id}/cancel`, {
  method: 'POST',
  token
})
assert(['CANCELED', 'CANCELLED'].includes(canceledLimit.status), 'Limit cancel must reach a canceled state')

const walletsAfterCancel = await walletBalances()
assertWalletConservation(walletsAfterCancel, 'after limit cancel')
assert(approximatelyEqual(wallet(walletsAfterCancel, 'USDT').locked, usdtAfterMarket.locked),
  'cancel must release the exact limit hold')
const summaryAfterCancel = await accountSummary()
assertAccountSummaryUnchanged(summaryBeforeMarket, summaryAfterCancel, 'Spot order and cancel lifecycle')

console.log(JSON.stringify({
  health: health.status,
  email,
  accountId,
  symbol,
  marketOrderStatus: marketOrder.status,
  limitOrderStatus: canceledLimit.status,
  spotUsdt: wallet(walletsAfterCancel, 'USDT'),
  spotBtc: wallet(walletsAfterCancel, 'BTC')
}, null, 2))

function createOrder({ orderType, quantity, quantityUnit, price, label }) {
  const clientOrderId = `smoke-${label}-${Date.now()}`
  return request('/api/trading/orders', {
    method: 'POST',
    token,
    body: {
      accountId,
      symbol,
      side: 'BUY',
      orderType,
      quantity,
      quantityUnit,
      ...(price === undefined ? {} : { price }),
      positionSide: 'BOTH',
      marginMode: 'CASH',
      reduceOnly: false,
      clientOrderId,
      idempotencyKey: clientOrderId
    }
  })
}

function walletBalances() {
  return request(`/api/accounts/${accountId}/wallet-balances`, { token })
}

function accountSummary() {
  return request(`/api/accounts/${accountId}/summary`, { token })
}

function assetLedger() {
  return request(`/api/accounts/${accountId}/asset-ledger`, { token })
}

async function loadAllPages(path, accountId, token, pageSize = 50) {
  const items = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const query = new URLSearchParams({ accountId, page: String(page), size: String(pageSize) })
    const result = await request(`${path}?${query}`, { token })
    assert(Array.isArray(result.items), `${path} must return paginated items`)
    const reportedTotalPages = Number(result.totalPages)
    assert(Number.isSafeInteger(reportedTotalPages) && reportedTotalPages >= 0,
      `${path} must return valid totalPages`)
    if (page === 0) totalPages = Math.max(1, reportedTotalPages)
    else assert(Math.max(1, reportedTotalPages) === totalPages, `${path} pagination metadata changed mid-read`)
    items.push(...result.items)
    page += 1
  }
  return items
}

function wallet(wallets, asset) {
  const balance = wallets.find((candidate) => candidate.walletType === 'SPOT' && candidate.asset === asset)
  return {
    total: Number(balance?.total ?? 0),
    available: Number(balance?.available ?? 0),
    locked: Number(balance?.locked ?? 0)
  }
}

function assertWalletConservation(wallets, label) {
  for (const balance of wallets.filter((candidate) => candidate.walletType === 'SPOT')) {
    const total = Number(balance.total)
    const available = Number(balance.available)
    const locked = Number(balance.locked)
    assert([total, available, locked].every(Number.isFinite), `${label}: wallet amounts must be finite`)
    assert(total >= 0 && available >= 0 && locked >= 0, `${label}: wallet amounts must be non-negative`)
    assert(approximatelyEqual(total, available + locked),
      `${label}: ${balance.asset} total must equal available plus locked`)
  }
}

function assertAccountSummaryUnchanged(before, after, label) {
  assert(after.id === before.id, `${label}: account summary identity changed`)
  for (const field of ['balance', 'equity', 'usedMargin', 'freeMargin']) {
    assert(approximatelyEqual(Number(after[field]), Number(before[field])),
      `${label}: Spot trading must not mutate Perpetual account ${field}`)
  }
}

function approximatelyEqual(left, right) {
  return Number.isFinite(left) && Number.isFinite(right) && Math.abs(left - right) <= 1e-8
}

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  })

  const payload = await response.json()
  if (options.raw) return payload
  if (!response.ok || !payload.success) {
    throw new Error(payload.message ?? `Request failed: ${response.status}`)
  }
  return payload.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
