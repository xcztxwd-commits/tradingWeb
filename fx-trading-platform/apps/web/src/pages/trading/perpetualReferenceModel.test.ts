import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { toPerpetualReferenceView } from './perpetualReferenceModel.ts'

describe('perpetual reference view model', () => {
  it('renders backend mark/index/source truth without inventing funding values', () => {
    const view = toPerpetualReferenceView({
      symbol: 'BTCUSDT-PERP',
      providerCode: 'binance',
      sourceMode: 'PUBLIC_EXTERNAL',
      mark: 60_001.25,
      index: 59_998.5,
      stale: false
    })

    assert.deepEqual(view, {
      markPrice: '60,001.25',
      indexPrice: '59,998.5',
      fundingRate: '--',
      fundingCountdown: '--',
      marketSource: 'PUBLIC_EXTERNAL',
      providerCode: 'binance',
      stale: false
    })
  })

  it('keeps missing or stale data explicit', () => {
    const view = toPerpetualReferenceView({ sourceMode: 'LOCAL_SIMULATED', stale: true })

    assert.equal(view.markPrice, '--')
    assert.equal(view.indexPrice, '--')
    assert.equal(view.marketSource, 'LOCAL_SIMULATED')
    assert.equal(view.stale, true)
  })
})
