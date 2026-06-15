import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createAssetMarkModel } from './assetMarkModel.ts'

describe('asset mark model', () => {
  it('builds a paired forex icon from the base and quote currencies', () => {
    const mark = createAssetMarkModel('EURUSD', 'fx')

    assert.equal(mark.kind, 'pair')
    if (mark.kind !== 'pair') throw new Error('Expected paired forex mark')
    assert.equal(mark.asset, 'EURUSD')
    assert.equal(mark.label, 'EUR/USD')
    assert.equal(mark.base.code, 'EUR')
    assert.equal(mark.base.icon, '🇪🇺')
    assert.equal(mark.quote.code, 'USD')
    assert.equal(mark.quote.icon, '🇺🇸')
  })

  it('keeps common forex pairs visually distinct', () => {
    const marks = ['EURUSD', 'USDJPY', 'GBPUSD'].map((symbol) => createAssetMarkModel(symbol, 'fx'))

    assert.deepEqual(
      marks.map((mark) => mark.label),
      ['EUR/USD', 'USD/JPY', 'GBP/USD']
    )
    assert.equal(
      new Set(
        marks.map((mark) => {
          if (mark.kind !== 'pair') throw new Error('Expected paired forex mark')
          return `${mark.base.icon}${mark.quote.icon}`
        })
      ).size,
      marks.length
    )
  })

  it('uses metal and exotic currency marks for provider forex symbols', () => {
    const mark = createAssetMarkModel('XAUARS', 'fx')

    assert.equal(mark.kind, 'pair')
    if (mark.kind !== 'pair') throw new Error('Expected paired forex mark')
    assert.equal(mark.label, 'XAU/ARS')
    assert.equal(mark.base.icon, 'Au')
    assert.equal(mark.quote.icon, '🇦🇷')
  })

  it('keeps crypto symbols on the existing single-asset path', () => {
    const mark = createAssetMarkModel('BTCUSDT', 'crypto')

    assert.deepEqual(mark, {
      kind: 'single',
      asset: 'BTC',
      display: 'BTC',
      label: 'BTC',
      variant: 'btc'
    })
  })
})
