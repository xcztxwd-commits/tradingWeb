import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { formatFundingFallbackState } from './fundingFallbackModel.ts'

describe('admin funding fallback state', () => {
  it('shows the backend-authoritative fallback reason verbatim', () => {
    assert.equal(
      formatFundingFallbackState({
        actualSource: 'OKX',
        fallbackReason: 'BINANCE_UNAVAILABLE_OR_STALE',
        sourceMode: 'LIVE',
        fundingSourcePriority: ['BINANCE', 'OKX', 'FIXED']
      }),
      'FALLBACK · BINANCE_UNAVAILABLE_OR_STALE → OKX · LIVE'
    )
  })

  it('does not invent a fallback reason when the backend reports none', () => {
    assert.equal(
      formatFundingFallbackState({
        actualSource: 'OKX',
        fallbackReason: null,
        sourceMode: 'LIVE',
        fundingSourcePriority: ['BINANCE', 'OKX']
      }),
      'PRIMARY · OKX · LIVE'
    )
  })

  it('keeps an unavailable source explicit', () => {
    assert.equal(
      formatFundingFallbackState({ actualSource: null, fallbackReason: null, sourceMode: null }),
      'UNAVAILABLE · 未选出实际来源'
    )
  })
})
