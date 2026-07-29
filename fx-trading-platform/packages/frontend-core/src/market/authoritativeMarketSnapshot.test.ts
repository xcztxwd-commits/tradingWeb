import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createAuthoritativeMarketSnapshot } from './authoritativeMarketSnapshot.ts'
import type { MarketSourceMetadata, TradingQuote } from './tradingModels.ts'
import type { MarketOrderBook, MarketTradeBatch } from './marketDataTypes.ts'

const now = Date.parse('2026-07-13T00:00:00.000Z')

describe('authoritative P0 market snapshot', () => {
  it('publishes a tradable snapshot only when quote, depth and trades share one fresh bundle source', () => {
    const snapshot = createAuthoritativeMarketSnapshot(
      quote(),
      orderBook(),
      tradeBatch(),
      now
    )

    assert.equal(snapshot.status, 'ready')
    assert.equal(snapshot.tradable, true)
    assert.equal(snapshot.lastPrice, 60_000)
    assert.deepEqual(snapshot.source, source())
    assert.deepEqual(snapshot.bids, [{ price: 59_999, amount: 2 }])
    assert.deepEqual(snapshot.recentTrades.map(({ id, price }) => ({ id, price })), [{ id: 't-1', price: 60_000 }])
  })

  it('rejects an incomplete bundle instead of synthesizing depth or trades from a quote', () => {
    const snapshot = createAuthoritativeMarketSnapshot(quote(), undefined, undefined, now)

    assert.equal(snapshot.status, 'unavailable')
    assert.equal(snapshot.tradable, false)
    assert.equal(snapshot.lastPrice, 0)
    assert.deepEqual(snapshot.bids, [])
    assert.deepEqual(snapshot.asks, [])
    assert.deepEqual(snapshot.recentTrades, [])
  })

  it('rejects expired and backend-stale bundles', () => {
    const expired = createAuthoritativeMarketSnapshot(quote(), orderBook(), tradeBatch(), Date.parse('2026-07-13T00:00:06.000Z'))
    const staleSource = source({ stale: true })
    const stale = createAuthoritativeMarketSnapshot(
      quote(staleSource),
      orderBook(staleSource),
      tradeBatch(staleSource),
      now
    )

    assert.equal(expired.status, 'stale')
    assert.equal(expired.tradable, false)
    assert.equal(expired.lastPrice, 0)
    assert.equal(stale.status, 'stale')
    assert.equal(stale.tradable, false)
  })

  it('rejects component metadata from different providers without mixing fields', () => {
    const okxSource = source({ providerCode: 'okx', providerSymbol: 'BTC-USDT' })
    const snapshot = createAuthoritativeMarketSnapshot(quote(), orderBook(okxSource), tradeBatch(), now)

    assert.equal(snapshot.status, 'unavailable')
    assert.equal(snapshot.tradable, false)
    assert.equal(snapshot.lastPrice, 0)
    assert.deepEqual(snapshot.bids, [])
    assert.deepEqual(snapshot.recentTrades, [])
  })

  it('accepts separately fetched components from one provider and uses the earliest expiry', () => {
    const depthSource = source({
      asOf: '2026-07-13T00:00:00.500Z',
      expiresAt: '2026-07-13T00:00:04.500Z'
    })
    const tradeSource = source({
      asOf: '2026-07-13T00:00:01.000Z',
      expiresAt: '2026-07-13T00:00:06.000Z'
    })
    const snapshot = createAuthoritativeMarketSnapshot(quote(), orderBook(depthSource), tradeBatch(tradeSource), now)

    assert.equal(snapshot.status, 'ready')
    assert.equal(snapshot.source?.providerCode, 'binance')
    assert.equal(snapshot.source?.asOf, '2026-07-13T00:00:00.000Z')
    assert.equal(snapshot.source?.expiresAt, '2026-07-13T00:00:04.500Z')
    assert.deepEqual(snapshot.componentSources, {
      quote: source(),
      orderBook: depthSource,
      trades: tradeSource
    })
  })
})

function source(patch: Partial<MarketSourceMetadata> = {}): MarketSourceMetadata {
  return {
    providerCode: 'binance',
    providerSymbol: 'BTCUSDT',
    sourceMode: 'PUBLIC_EXTERNAL',
    asOf: '2026-07-13T00:00:00.000Z',
    expiresAt: '2026-07-13T00:00:05.000Z',
    stale: false,
    ...patch
  }
}

function quote(metadata = source()): TradingQuote {
  return {
    symbol: 'BTCUSDT',
    bid: 59_999,
    ask: 60_001,
    mid: 60_000,
    spread: 2,
    changePercent: 1,
    high24h: 61_000,
    low24h: 58_000,
    volume: '10K',
    source: 'binance',
    timestamp: now,
    marketSource: metadata,
    tradable: true
  }
}

function orderBook(metadata = source()): MarketOrderBook {
  return {
    symbol: 'BTCUSDT',
    bids: [{ price: 59_999, amount: 2 }],
    asks: [{ price: 60_001, amount: 3 }],
    lastPrice: 60_000,
    lastPriceDirection: 'flat',
    source: metadata
  }
}

function tradeBatch(metadata = source()): MarketTradeBatch {
  return {
    symbol: 'BTCUSDT',
    recentTrades: [{ id: 't-1', price: 60_000, amount: 0.1, side: 'buy', time: now }],
    source: metadata
  }
}
