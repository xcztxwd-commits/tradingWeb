import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { createQuoteMarketDataSnapshot } from './quoteMarketDataSnapshot.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'quoteMarketDataAdapter.ts'), 'utf8')
const snapshotSource = readFileSync(join(currentDir, 'quoteMarketDataSnapshot.ts'), 'utf8')
const marketFeatureDir = join(currentDir, '..', '..', 'features', 'market')
const marketFeatureAdapterSource = readFileSync(join(marketFeatureDir, 'quoteMarketDataAdapter.ts'), 'utf8')

describe('quote market data adapter', () => {
  it('keeps market data store and quote adapter implementation in the market feature', () => {
    assert.equal(existsSync(join(marketFeatureDir, 'marketDataStore.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'quoteMarketDataAdapter.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'quoteMarketDataSnapshot.ts')), true)
    assert.match(adapterSource, /features\/market\/quoteMarketDataAdapter/)
    assert.match(snapshotSource, /features\/market\/quoteMarketDataSnapshot/)
  })

  it('depends on feature-level market modules instead of the trading page directory', () => {
    assert.doesNotMatch(adapterSource, /\.\.\/\.\.\/pages\/trading/)
    assert.doesNotMatch(snapshotSource, /\.\.\/\.\.\/pages\/trading/)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingMarketApi.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingMarketAdapters.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingModels.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'mockTradingData.ts')), true)
  })

  it('builds a deterministic panel snapshot from backend quote prices', () => {
    const snapshot = createQuoteMarketDataSnapshot({
      symbol: 'EURUSD',
      bid: 1.08318,
      ask: 1.08322,
      mid: 1.0832,
      spread: 0.00004,
      changePercent: 0,
      high24h: 1.0832,
      low24h: 1.0832,
      volume: 'Live',
      source: 'demo',
      timestamp: 1_780_000_000_000
    })

    assert.equal(snapshot.lastPrice, 1.0832)
    assert.deepEqual(snapshot.bids.slice(0, 2), [
      { price: 1.08318, amount: 1 },
      { price: 1.08317, amount: 1.2 }
    ])
    assert.deepEqual(snapshot.asks.slice(0, 2), [
      { price: 1.08322, amount: 1 },
      { price: 1.08323, amount: 1.2 }
    ])
    assert.deepEqual(snapshot.recentTrades[0], {
      id: 'EURUSD-1780000000000',
      price: 1.0832,
      amount: 1,
      side: 'buy',
      time: 1_780_000_000_000
    })
  })

  it('does not synthesize a tradable fallback price when backend market data is unavailable', () => {
    assert.doesNotMatch(marketFeatureAdapterSource, /createFallbackMarketDataSnapshot|mockTradingMarkets/)
  })
})
