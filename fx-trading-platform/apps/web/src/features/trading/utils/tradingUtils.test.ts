import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { formatDecimal } from './format.ts'
import { parseSymbolAssets } from './symbols.ts'

describe('trading pure utilities', () => {
  it('parses common trading symbols with separators and JPY quotes', () => {
    assert.deepEqual(parseSymbolAssets('ETH-USDT'), { baseAsset: 'ETH', quoteAsset: 'USDT' })
    assert.deepEqual(parseSymbolAssets('EUR/USD'), { baseAsset: 'EUR', quoteAsset: 'USD' })
    assert.deepEqual(parseSymbolAssets('USDJPY'), { baseAsset: 'USD', quoteAsset: 'JPY' })
  })

  it('formats positive decimal values and rejects invalid non-positive values', () => {
    assert.equal(formatDecimal(1.23000000), '1.23')
    assert.equal(formatDecimal(1 / 3, 4), '0.3333')
    assert.equal(formatDecimal(0), '')
    assert.equal(formatDecimal(Number.NaN), '')
  })
})
