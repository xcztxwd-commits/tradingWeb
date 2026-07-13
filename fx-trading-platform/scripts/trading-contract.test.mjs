import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const generated = readFileSync(
  new URL('../packages/shared-types/src/generated/openapi.ts', import.meta.url),
  'utf8'
)

const pathEntries = [...generated.matchAll(/^    "([^"]+)": \{\r?\n([\s\S]*?)^    \};/gm)]
  .map(([, path, body]) => ({ path, body }))

function requireEndpoint(method, expectedPath) {
  const entry = pathEntries.find(({ path }) => path === expectedPath)

  assert.ok(entry, `OpenAPI path is missing: ${expectedPath}`)
  assert.match(
    entry.body,
    new RegExp(`^        ${method.toLowerCase()}: operations\\[`, 'm'),
    `OpenAPI operation is missing: ${method.toUpperCase()} ${expectedPath}`
  )
}

function requireSchema(name) {
  assert.ok(
    new RegExp(`^        ${name}: \\{`, 'm').test(generated),
    `OpenAPI schema is missing: ${name}`
  )
}

describe('P0 trading OpenAPI contract', () => {
  it('contains every API operation from specification section 20', () => {
    const endpoints = [
      ['get', '/api/accounts/{accountId}/trading-settings'],
      ['patch', '/api/accounts/{accountId}/position-mode'],
      ['patch', '/api/accounts/{accountId}/symbols/{symbol}/settings'],
      ['post', '/api/trading/positions/{positionId}/margin'],

      ['post', '/api/trading/orders'],
      ['get', '/api/trading/orders'],
      ['patch', '/api/trading/orders/{id}'],
      ['post', '/api/trading/orders/{id}/cancel'],
      ['get', '/api/trading/orders/{id}/events'],
      ['post', '/api/trading/orders/cancel-all'],
      ['post', '/api/trading/oco'],
      ['post', '/api/trading/positions/{id}/protections'],
      ['patch', '/api/trading/protections/{orderId}'],
      ['delete', '/api/trading/protections/{orderId}'],

      ['get', '/api/trading/trades'],
      ['get', '/api/trading/positions'],
      ['get', '/api/trading/positions/history'],
      ['post', '/api/trading/positions/{id}/close'],
      ['post', '/api/trading/positions/close-all'],

      ['get', '/api/trading/funding/settlements'],
      ['get', '/api/admin/market/symbols/{id}/funding-config'],
      ['put', '/api/admin/market/symbols/{id}/funding-config'],

      ['post', '/api/accounts/{id}/transfers'],
      ['get', '/api/accounts/{id}/transfers'],
      ['post', '/api/accounts/{id}/demo-reset'],
      ['get', '/api/admin/accounts/{id}/wallet-balances'],
      ['get', '/api/admin/accounts/{id}/asset-ledger'],
      ['get', '/api/admin/accounts/{id}/funding-settlements'],
      ['post', '/api/admin/accounts/{id}/force-cleanup'],
      ['post', '/api/admin/accounts/{id}/demo-reset'],

      ['get', '/api/market/perpetuals/{symbol}/reference']
    ]

    for (const [method, path] of endpoints) {
      requireEndpoint(method, path)
    }
  })

  it('publishes all P0 request and response DTO schemas', () => {
    const schemas = [
      'CreateOrderRequest',
      'UpdateOrderRequest',
      'OrderResponse',
      'OrderEventResponse',
      'CreateOcoOrderRequest',
      'OcoOrderGroupResponse',
      'CreateProtectionRequest',
      'UpdateProtectionRequest',
      'ClosePositionRequest',
      'PositionResponse',
      'AdjustPositionMarginRequest',
      'AdjustPositionMarginResponse',
      'BatchActionRequest',
      'BatchActionResponse',
      'TradingSettingsResponse',
      'UpdatePositionModeRequest',
      'UpdateSymbolSettingsRequest',
      'AccountTransferRequest',
      'AccountTransferResponse',
      'DemoResetRequest',
      'DemoResetResponse',
      'WalletBalanceResponse',
      'AssetLedgerEntryResponse',
      'TradeResponse',
      'FundingSettlementResponse',
      'TradingPageResponseOrderResponse',
      'TradingPageResponseTradeResponse',
      'TradingPageResponsePositionResponse',
      'TradingPageResponseFundingSettlementResponse',
      'TradingPageResponseAccountTransferResponse',
      'AdminFundingConfigRequest',
      'AdminFundingConfigResponse',
      'AdminAccountCleanupRequest',
      'AdminDemoResetRequest',
      'PerpetualReferenceResponse'
    ]

    for (const schema of schemas) {
      requireSchema(schema)
    }
  })

  it('keeps the complete P0 enum vocabulary in generated request contracts', () => {
    for (const value of [
      'FX_MARGIN',
      'CRYPTO_SPOT',
      'LINEAR_PERP',
      'INVERSE_PERP',
      'BUY',
      'SELL',
      'MARKET',
      'LIMIT',
      'STOP_MARKET',
      'RECEIVED',
      'VALIDATING',
      'ACCEPTED',
      'WORKING',
      'PARTIALLY_FILLED',
      'PENDING_ACTIVATION',
      'PENDING',
      'FILLED',
      'CANCEL_PENDING',
      'CANCELED',
      'CANCELLED',
      'REJECTED',
      'EXPIRED',
      'FAILED',
      'USER',
      'PROTECTIVE',
      'LIQUIDATION',
      'ADMIN_FORCE_CLOSE',
      'BATCH_CLOSE',
      'OCO',
      'ONE_WAY',
      'HEDGE',
      'BOTH',
      'LONG',
      'SHORT',
      'CASH',
      'CROSS',
      'ISOLATED',
      'BASE',
      'QUOTE',
      'CONTRACTS',
      'TAKE_PROFIT',
      'STOP_LOSS',
      'LAST_PRICE',
      'MARK_PRICE',
      'GTC',
      'MAKER',
      'TAKER'
    ]) {
      assert.ok(
        new RegExp(`(?:"|\\b)${value}(?:"|\\b)`).test(generated),
        `enum value is missing: ${value}`
      )
    }

    const createOrder = generated.match(/^        CreateOrderRequest: \{([\s\S]*?)^        \};/m)?.[1]
    assert.ok(createOrder, 'CreateOrderRequest schema is missing')
    const orderType = createOrder.match(/orderType:\s*([^;]+);/)?.[1]
    assert.ok(orderType, 'CreateOrderRequest.orderType is missing')
    assert.deepEqual(
      [...orderType.matchAll(/"([^"]+)"/g)].map(([, value]) => value).sort(),
      ['LIMIT', 'MARKET', 'STOP_MARKET'],
      'CreateOrderRequest.orderType must expose exactly MARKET, LIMIT, and STOP_MARKET'
    )
  })

  it('preserves the canonical perpetual symbol and forbids its stripped spelling', () => {
    assert.ok(generated.includes('BTCUSDT-PERP'), 'generated contract must contain BTCUSDT-PERP')
    assert.ok(!generated.includes('BTCUSDTPERP'), 'generated contract must not contain BTCUSDTPERP')
  })
})
