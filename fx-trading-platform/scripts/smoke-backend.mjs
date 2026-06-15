const baseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'

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
assert(Array.isArray(accounts) && accounts.length > 0, 'Register must create a demo account')

const accountId = accounts[0].id
const order = await request('/api/trading/orders', {
  method: 'POST',
  token,
  body: {
    accountId,
    symbol: 'EURUSD',
    side: 'BUY',
    orderType: 'MARKET',
    lots: '0.01',
    idempotencyKey: `smoke-${Date.now()}`
  }
})
assert(order.status === 'FILLED', 'Market order must be filled in simulated execution')

const ledger = await request(`/api/ledger?accountId=${accountId}`, { token })
assert(ledger.some((entry) => entry.entryType === 'MARGIN_HOLD'), 'Filled market order must record MARGIN_HOLD ledger entry')

const openPositions = await request(`/api/trading/positions?accountId=${accountId}`, { token })
assert(openPositions.length > 0, 'Filled market order must create an open position')
assert(Number(openPositions[0].marginHeld) > 0, 'Open position must expose marginHeld')

const closedPosition = await request(`/api/trading/positions/${openPositions[0].id}/close?accountId=${accountId}`, {
  method: 'POST',
  token,
  body: {}
})
assert(closedPosition.status === 'CLOSED', 'Close position endpoint must close an open position')
assert(Number(closedPosition.marginHeld) === 0, 'Closed position must release held margin')

const ledgerAfterClose = await request(`/api/ledger?accountId=${accountId}`, { token })
assert(
  ledgerAfterClose.some((entry) => entry.entryType === 'MARGIN_RELEASE' && entry.referenceId === closedPosition.id && Number(entry.amount) > 0),
  'Closed position must record positive MARGIN_RELEASE ledger entry'
)
assert(ledgerAfterClose.some((entry) => entry.entryType === 'TRADE_PNL'), 'Closed position must record TRADE_PNL ledger entry')

const marginHoldCountAfterMarket = ledgerAfterClose.filter((entry) => entry.entryType === 'MARGIN_HOLD').length
const pendingLimitOrder = await request('/api/trading/orders', {
  method: 'POST',
  token,
  body: {
    accountId,
    symbol: 'EURUSD',
    side: 'BUY',
    orderType: 'LIMIT',
    requestedPrice: '0.00001',
    lots: '0.01',
    idempotencyKey: `smoke-limit-${Date.now()}`
  }
})
assert(pendingLimitOrder.status === 'PENDING', 'Limit order must stay PENDING in the first foundation release')
assert(!pendingLimitOrder.executionPrice, 'Pending limit order must not have an execution price')
assert(Number(pendingLimitOrder.filledQuantity) === 0, 'Pending limit order must not have filled quantity')
assert(Number(pendingLimitOrder.remainingQuantity) === Number(pendingLimitOrder.quantity), 'Pending limit order must keep remaining quantity')

await delay(2500)
const ordersAfterLimitWait = await request('/api/trading/orders', { token })
const limitOrderAfterWait = ordersAfterLimitWait.find((entry) => entry.id === pendingLimitOrder.id)
assert(limitOrderAfterWait?.status === 'PENDING', 'Limit order must not be auto-filled by demo scheduler')

const ledgerAfterLimit = await request(`/api/ledger?accountId=${accountId}`, { token })
const marginHoldCountAfterLimit = ledgerAfterLimit.filter((entry) => entry.entryType === 'MARGIN_HOLD').length
assert(
  marginHoldCountAfterLimit === marginHoldCountAfterMarket,
  'Foundation limit order must not create an execution margin hold ledger entry'
)

console.log(JSON.stringify({
  health: health.status,
  email,
  accountId,
  orderStatus: order.status,
  closedPositionStatus: closedPosition.status,
  limitOrderStatus: limitOrderAfterWait.status,
  ledgerTypes: ledgerAfterLimit.map((entry) => entry.entryType)
}, null, 2))

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
  if (options.raw) {
    return payload
  }
  if (!response.ok || !payload.success) {
    throw new Error(payload.message ?? `Request failed: ${response.status}`)
  }
  return payload.data
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

async function delay(timeoutMs) {
  await new Promise((resolve) => setTimeout(resolve, timeoutMs))
}
