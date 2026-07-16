import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./smoke-backend.mjs', import.meta.url), 'utf8')

describe('backend smoke P0 API contract', () => {
  it('selects the active Demo account and trades only canonical BTCUSDT Spot', () => {
    assert.match(source, /account\.accountType === 'DEMO' && account\.status === 'ACTIVE'/)
    assert.match(source, /const symbol = 'BTCUSDT'/)
    assert.doesNotMatch(source, /EURUSD/)
  })

  it('uses current CreateOrderRequest fields without legacy FX aliases', () => {
    for (const field of [
      'quantity',
      'quantityUnit',
      'positionSide',
      'marginMode',
      'reduceOnly',
      'clientOrderId',
      'idempotencyKey'
    ]) {
      assert.match(source, new RegExp(`\\b${field}(?:\\s*:|\\s*[,}])`), `missing current order field: ${field}`)
    }
    assert.doesNotMatch(source, /\blots:/)
    assert.doesNotMatch(source, /\brequestedPrice:/)
  })

  it('uses the canonical Spot quantity unit for each order shape', () => {
    assert.match(source, /orderType: 'MARKET',[\s\S]*?quantity: '10',[\s\S]*?quantityUnit: 'QUOTE'/)
    assert.match(source, /orderType: 'LIMIT',[\s\S]*?quantity: '0\.001',[\s\S]*?quantityUnit: 'BASE'/)
    assert.match(source, /function createOrder\(\{ orderType, quantity, quantityUnit,/)
    assert.match(source, /body: \{[\s\S]*?quantity,[\s\S]*?quantityUnit,/)
  })

  it('uses account-scoped paginated order and position items', () => {
    assert.match(source, /loadAllPages\('\/api\/trading\/orders'/)
    assert.match(source, /loadAllPages\('\/api\/trading\/positions'/)
    assert.match(source, /async function loadAllPages/)
    assert.match(source, /page < totalPages/)
    assert.match(source, /size: String\(pageSize\)/)
  })

  it('checks real Spot wallets and cancels a non-marketable limit order', () => {
    assert.match(source, /\/wallet-balances/)
    assert.match(source, /\/summary/)
    assert.match(source, /\/asset-ledger/)
    assert.match(source, /assertWalletConservation/)
    assert.match(source, /SPOT_BUY_DEBIT/)
    assert.match(source, /SPOT_BUY_CREDIT/)
    assert.match(source, /market order must debit Spot USDT/)
    assert.match(source, /market order must credit Spot BTC/)
    assert.match(source, /non-marketable limit must lock Spot USDT/)
    assert.match(source, /\/api\/trading\/orders\/\$\{pendingLimit\.id\}\/cancel/)
    assert.match(source, /cancel must release the exact limit hold/)
  })
})
