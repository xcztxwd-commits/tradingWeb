import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { isMarketTradingEnabled, resolveMarketTradingTarget } from './marketTradingTarget.ts'

describe('market trading targets', () => {
  it('routes only the canonical P0 Spot and linear Perpetual products', () => {
    assert.equal(
      resolveMarketTradingTarget({ symbol: 'ETHUSDT', productType: 'CRYPTO_SPOT' }),
      '/trade/spot/ETHUSDT'
    )
    assert.equal(
      resolveMarketTradingTarget({ symbol: 'BTCUSDT-PERP', productType: 'LINEAR_PERP' }),
      '/trade/perpetual/BTCUSDT-PERP'
    )
  })

  it('does not expose FX, inverse, option, or provider-symbol trading actions', () => {
    assert.equal(resolveMarketTradingTarget({ symbol: 'EURUSD', productType: 'FX_MARGIN' }), null)
    assert.equal(resolveMarketTradingTarget({ symbol: 'BTCUSD-PERP', productType: 'INVERSE_PERP' }), null)
    assert.equal(resolveMarketTradingTarget({ symbol: 'BTC-USD-OPTION' }), null)
    assert.equal(resolveMarketTradingTarget({ symbol: 'BTC-USDT-SWAP', productType: 'LINEAR_PERP' }), null)
  })

  it('keeps empty fallback summary slots non-interactive', () => {
    assert.equal(resolveMarketTradingTarget(undefined), null)
    assert.equal(isMarketTradingEnabled(undefined), false)
  })
})
