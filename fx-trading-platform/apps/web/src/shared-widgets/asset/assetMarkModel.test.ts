import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createAssetMarkModel } from './assetMarkModel.ts'

describe('asset mark model', () => {
  it('builds a paired forex flag mark from the base and quote currencies', () => {
    const mark = createAssetMarkModel('EURUSD', 'fx')

    assert.equal(mark.kind, 'pair')
    if (mark.kind !== 'pair') throw new Error('Expected paired forex mark')
    assert.equal(mark.asset, 'EURUSD')
    assert.equal(mark.label, 'EUR/USD')
    assert.equal(mark.base.code, 'EUR')
    assert.deepEqual(mark.base.visual, {
      kind: 'flag',
      style: 'solid',
      colors: ['#234ad5', '#f7c948']
    })
    assert.equal(mark.quote.code, 'USD')
    assert.deepEqual(mark.quote.visual, {
      kind: 'flag',
      style: 'stripes',
      colors: ['#b22234', '#ffffff', '#3c3b6e']
    })
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
          return `${mark.base.code}${mark.quote.code}:${mark.base.visual.kind}:${mark.quote.visual.kind}`
        })
      ).size,
      marks.length
    )
  })

  it('uses metal glyphs and currency flags for provider forex symbols', () => {
    const mark = createAssetMarkModel('XAUARS', 'fx')

    assert.equal(mark.kind, 'pair')
    if (mark.kind !== 'pair') throw new Error('Expected paired forex mark')
    assert.equal(mark.label, 'XAU/ARS')
    assert.deepEqual(mark.base.visual, { kind: 'glyph', text: 'Au' })
    assert.deepEqual(mark.quote.visual, {
      kind: 'flag',
      style: 'horizontal',
      colors: ['#74acdf', '#ffffff', '#74acdf']
    })
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

  it('uses Binance image URLs for crypto marks when provided', () => {
    const iconUrl = 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20201110/btc.png'
    const mark = createAssetMarkModel('BTCUSDT', 'crypto', iconUrl)

    assert.equal(mark.kind, 'single')
    if (mark.kind !== 'single') throw new Error('Expected single crypto mark')
    assert.equal(mark.imageUrl, iconUrl)
    assert.equal(mark.display, 'BTC')
  })
})
