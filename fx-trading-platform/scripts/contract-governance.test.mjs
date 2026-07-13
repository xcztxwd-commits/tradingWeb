import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const fromRoot = (path) => new URL(`../${path}`, import.meta.url)
const text = (path) => readFileSync(fromRoot(path), 'utf8')
const json = (path) => JSON.parse(text(path))

function interfaceFields(source, interfaceName) {
  const body = source.match(new RegExp(`export interface ${interfaceName} \\{([\\s\\S]*?)\\n\\}`))?.[1]
  assert.ok(body, `${interfaceName} interface is missing`)
  return Object.fromEntries(
    [...body.matchAll(/^\s+([A-Za-z][A-Za-z0-9]*):\s*([^\r\n]+)$/gm)]
      .map(([, name, type]) => [name, type.trim()])
  )
}

function recordComponentNames(source, recordName) {
  const body = source.match(new RegExp(`public record ${recordName}\\s*\\(([\\s\\S]*?)\\n\\) \\{`))?.[1]
  assert.ok(body, `${recordName} record is missing`)
  return body
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.match(/([A-Za-z][A-Za-z0-9]*)[,]?$/)?.[1])
    .filter(Boolean)
}

describe('contract governance workspace wiring', () => {
  it('defines contract export, generation, check, and CI scripts', () => {
    const pkg = json('package.json')

    assert.equal(pkg.scripts['contract:export'], 'node scripts/export-openapi.mjs')
    assert.equal(pkg.scripts['contract:generate'], 'node scripts/generate-openapi-types.mjs')
    assert.equal(pkg.scripts['contract:check'], 'node scripts/generate-openapi-types.mjs --check')
    assert.match(pkg.scripts['contract:ci'], /contract:export/)
    assert.match(pkg.scripts['contract:ci'], /contract:check/)
    assert.match(pkg.scripts['contract:ci'], /web:build/)
    assert.match(pkg.scripts['contract:ci'], /admin:build/)
  })

  it('shares generated OpenAPI types and error-code helpers with both frontends', () => {
    const sharedIndex = text('packages/shared-types/src/index.ts')

    assert.match(sharedIndex, /generated\/openapi/)
    assert.match(sharedIndex, /tradingTypes/)
    assert.match(sharedIndex, /ApiErrorCode/)
    assert.match(text('apps/web/src/services/authApi.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/admin/src/types.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/web/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
    assert.match(text('apps/admin/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
  })

  it('provides a generated-schema facade and canonical P0 trading types', () => {
    const apiTypes = text('packages/shared-types/src/apiTypes.ts')
    const tradingTypes = text('packages/shared-types/src/tradingTypes.ts')

    assert.match(apiTypes, /BackendSchema/)
    assert.match(apiTypes, /ApiPathData/)
    assert.match(tradingTypes, /CANONICAL_TRADING_SYMBOLS/)
    assert.match(tradingTypes, /'BTCUSDT-PERP'/)
    assert.doesNotMatch(tradingTypes, /BTCUSDTPERP/)
    assert.match(tradingTypes, /STOP_MARKET/)
    assert.match(tradingTypes, /CreateOrderRequest/)
    assert.match(tradingTypes, /TradingSettingsResponse/)
    assert.match(tradingTypes, /BatchActionResponse/)
    assert.match(tradingTypes, /TradeResponse/)
    assert.match(tradingTypes, /FundingSettlement/)
    assert.match(tradingTypes, /TradingPage/)
    for (const enumType of [
      'ProductType',
      'OrderSide',
      'OrderType',
      'OrderStatus',
      'OrderOrigin',
      'PositionMode',
      'PositionSide',
      'MarginMode',
      'QuantityUnit',
      'TimeInForce',
      'ProtectionType',
      'TriggerPriceType',
      'TriggerExecutionType',
      'LiquidityRole'
    ]) {
      assert.match(tradingTypes, new RegExp(`export type ${enumType}\\b`), `shared enum is missing: ${enumType}`)
    }
  })

  it('locks private trading and public market-source event payloads', () => {
    const tradingTypes = text('packages/shared-types/src/tradingTypes.ts')
    const tradingSessionEvent = interfaceFields(tradingTypes, 'TradingSessionEvent')
    const marketSourceChangedEvent = interfaceFields(tradingTypes, 'MarketSourceChangedEvent')

    assert.deepEqual(
      Object.keys(tradingSessionEvent),
      recordComponentNames(
        text('backend/src/main/java/com/fxplatform/trading/dto/response/TradingSessionEventResponse.java'),
        'TradingSessionEventResponse'
      ),
      'TradingSessionEvent fields must match TradingSessionEventResponse record components'
    )
    assert.deepEqual(
      Object.keys(marketSourceChangedEvent),
      recordComponentNames(
        text('backend/src/main/java/com/fxplatform/market/dto/MarketSourceChangedResponse.java'),
        'MarketSourceChangedResponse'
      ),
      'MarketSourceChangedEvent fields must match MarketSourceChangedResponse record components'
    )

    assert.deepEqual(tradingSessionEvent, {
      type: 'TradingEventType',
      accountId: 'string',
      resourceType: 'string',
      resourceId: 'string',
      relatedResourceId: 'string | null',
      version: 'number | null',
      createdAt: 'string'
    })
    assert.deepEqual(marketSourceChangedEvent, {
      type: "'MARKET_SOURCE_CHANGED'",
      symbol: 'string',
      previousProviderCode: 'string',
      previousSourceMode: 'MarketSourceMode',
      providerCode: 'string',
      sourceMode: 'MarketSourceMode',
      changedAt: 'string',
      asOf: 'string',
      expiresAt: 'string',
      stale: 'boolean'
    })

    for (const eventType of [
      'ORDER_ACCEPTED',
      'ORDER_PENDING',
      'ORDER_FILLED',
      'ORDER_CANCELED',
      'ORDER_REJECTED',
      'ORDER_EXPIRED',
      'ORDER_MODIFIED',
      'TRADE_CREATED',
      'BALANCE_UPDATED',
      'POSITION_UPDATED',
      'POSITION_CLOSED',
      'PROTECTION_CREATED',
      'PROTECTION_UPDATED',
      'PROTECTION_ACTIVATED',
      'PROTECTION_TRIGGERED',
      'PROTECTION_RESIZED',
      'PROTECTION_CANCELED',
      'PROTECTION_EXPIRED',
      'FUNDING_SETTLED',
      'MARGIN_ADJUSTED',
      'TRANSFER_COMPLETED',
      'LIQUIDATION',
      'DEMO_RESET',
      'MARKET_SOURCE_CHANGED'
    ]) {
      assert.match(
        tradingTypes,
        new RegExp(`\\| '${eventType}'`),
        `shared trading event is missing: ${eventType}`
      )
    }
  })

  it('shares the P0 lifecycle, settings, and protection error codes', () => {
    const errorCodes = text('packages/shared-types/src/errorCodes.ts')

    for (const code of [
      'DEMO_RESET_BLOCKED',
      'TRANSFER_REQUEST_CONFLICT',
      'POSITION_MODE_SWITCH_BLOCKED',
      'MARGIN_MODE_SWITCH_BLOCKED',
      'LEVERAGE_OUT_OF_RANGE',
      'REDUCE_ONLY_EXCEEDS_POSITION',
      'MARGIN_REDUCTION_UNSAFE',
      'PROTECTION_LIMIT_EXCEEDED',
      'PROTECTION_NOT_EXECUTABLE'
    ]) {
      assert.match(errorCodes, new RegExp(`'${code}'`), `shared error code is missing: ${code}`)
    }
  })

  it('has a CI workflow for the full API contract chain', () => {
    assert.equal(existsSync(fromRoot('../.github/workflows/fx-contract.yml')), true)

    const workflow = text('../.github/workflows/fx-contract.yml')
    for (const command of [
      'npm run contract:export',
      'npm run contract:check',
      'npm run web:test',
      'npm run web:build',
      'npm run admin:build',
      'npm run smoke:backend'
    ]) {
      assert.match(workflow, new RegExp(command.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    }
  })
})
