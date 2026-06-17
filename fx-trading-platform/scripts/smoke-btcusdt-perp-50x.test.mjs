import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-btcusdt-perp-50x.mjs')

describe('BTCUSDT LINEAR_PERP 50x long smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:btcusdt-perp-50x'], 'node scripts/smoke-btcusdt-perp-50x.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('pins the requested BTCUSDT quote phases', () => {
    const source = readFileSync(scriptPath, 'utf8')

    for (const quote of ['100000', '102000', '101000']) {
      assert.match(source, new RegExp(quote))
    }
    assert.match(source, /\/api\/admin\/market\/test-control\/overrides/)
    assert.match(source, /MARKET_TEST_CONTROL_ENABLED/)
  })

  it('covers the required account, market, order, position, wallet, and ledger endpoints', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const requiredFragments = [
      '/api/auth/register',
      '/api/auth/login',
      '/api/accounts',
      '/summary',
      '/wallet-balances',
      '/asset-ledger',
      '/api/market/symbols',
      '/api/market/quotes/BTCUSDT',
      '/api/chart/candles',
      '/api/trading/orders',
      '/events',
      '/api/trading/positions?accountId=',
      '/api/trading/positions/history?accountId=',
      '/api/ledger?accountId='
    ]

    for (const fragment of requiredFragments) {
      assert.match(source, new RegExp(escapeRegExp(fragment)))
    }
  })

  it('asserts the requested LINEAR_PERP 50x partial and final close behavior', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /LINEAR_PERP/)
    assert.match(source, /leverage:\s*50/)
    assert.match(source, /quantity:\s*'0\.01'/)
    assert.match(source, /side:\s*'BUY'/)
    assert.match(source, /side:\s*'SELL'/)
    assert.match(source, /PARTIAL_CLOSE_RATIO\s*=\s*0\.3/)
    assert.match(source, /remainingQuantity/)
    assert.match(source, /originalQuantity/)
    assert.match(source, /floatingPnl/)
    assert.match(source, /realizedPnl/)
    assert.match(source, /liquidationPrice/)
    assert.match(source, /marginHeld/)
    assert.match(source, /maintenanceMargin/)
  })

  it('uses the web UI for user-click open and final close evidence', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /openLongThroughWebUi/)
    assert.match(source, /closeRemainingThroughWebUi/)
    assert.match(source, /fx-platform-auth-token/)
    assert.match(source, /OrderConfirmationDialog/)
    assert.match(source, /positions\.closeAllMarket/)
  })
})

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
