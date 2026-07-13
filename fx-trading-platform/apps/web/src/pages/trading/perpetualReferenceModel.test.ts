import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { toPerpetualReferenceView } from './perpetualReferenceModel.ts'

describe('perpetual reference view model', () => {
  it('renders backend mark/index/source truth without inventing funding values', () => {
    const view = toPerpetualReferenceView({
      symbol: 'BTCUSDT-PERP',
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      mark: 60_001.25,
      index: 59_998.5,
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    }, undefined, Date.parse('2026-07-13T00:00:01.000Z'))

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

  it('rejects a reference from a different bundle source than the selected quote', () => {
    const expectedSource = {
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL' as const,
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    }
    const view = toPerpetualReferenceView({
      symbol: 'BTCUSDT-PERP',
      providerCode: 'okx',
      providerSymbol: 'BTC-USDT-SWAP',
      sourceMode: 'PUBLIC_EXTERNAL',
      mark: 60_001.25,
      index: 59_998.5,
      asOf: expectedSource.asOf,
      expiresAt: expectedSource.expiresAt,
      stale: false
    }, expectedSource, Date.parse('2026-07-13T00:00:01.000Z'))

    assert.equal(view.markPrice, '--')
    assert.equal(view.indexPrice, '--')
    assert.equal(view.stale, true)
  })

  it('accepts a fresh reference fetched later from the same provider source', () => {
    const view = toPerpetualReferenceView({
      symbol: 'BTCUSDT-PERP',
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      mark: 60_001.25,
      index: 59_998.5,
      asOf: '2026-07-13T00:00:01.000Z',
      expiresAt: '2026-07-13T00:00:06.000Z',
      stale: false
    }, {
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    }, Date.parse('2026-07-13T00:00:02.000Z'))

    assert.equal(view.markPrice, '60,001.25')
    assert.equal(view.indexPrice, '59,998.5')
    assert.equal(view.stale, false)
  })
})
