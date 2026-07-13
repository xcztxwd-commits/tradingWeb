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

const walletsBeforeMarket = await walletBalances()
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
const usdtAfterMarket = wallet(walletsAfterMarket, 'USDT')
const btcAfterMarket = wallet(walletsAfterMarket, 'BTC')
assert(usdtAfterMarket.total < usdtBeforeMarket.total, 'market order must debit Spot USDT')
assert(btcAfterMarket.total > btcBeforeMarket.total, 'market order must credit Spot BTC')

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
assert(wallet(walletsWithLimit, 'USDT').locked > usdtAfterMarket.locked,
  'non-marketable limit must lock Spot USDT')

const query = new URLSearchParams({ accountId, page: '0', size: '50' })
const ordersPage = await request(`/api/trading/orders?${query}`, { token })
assert(Array.isArray(ordersPage.items), 'Order query must return paginated items')
assert(ordersPage.items.some((order) => order.id === marketOrder.id), 'Market order must appear in order history')
assert(ordersPage.items.some((order) => order.id === pendingLimit.id), 'Limit order must appear in order history')

const positionsPage = await request(`/api/trading/positions?${query}`, { token })
assert(Array.isArray(positionsPage.items), 'Position query must return paginated items')
assert(!positionsPage.items.some((position) => position.symbol === symbol),
  'Spot fills must not create leveraged positions')

const canceledLimit = await request(`/api/trading/orders/${pendingLimit.id}/cancel`, {
  method: 'POST',
  token
})
assert(['CANCELED', 'CANCELLED'].includes(canceledLimit.status), 'Limit cancel must reach a canceled state')

const walletsAfterCancel = await walletBalances()
assert(wallet(walletsAfterCancel, 'USDT').locked <= usdtAfterMarket.locked,
  'cancel must release the limit hold')

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

function wallet(wallets, asset) {
  const balance = wallets.find((candidate) => candidate.walletType === 'SPOT' && candidate.asset === asset)
  return {
    total: Number(balance?.total ?? 0),
    available: Number(balance?.available ?? 0),
    locked: Number(balance?.locked ?? 0)
  }
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
