import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'quoteMarketDataAdapter.ts'), 'utf8')
const marketFeatureDir = join(currentDir, '..', '..', 'features', 'market')
const marketFeatureAdapterSource = readFileSync(join(marketFeatureDir, 'quoteMarketDataAdapter.ts'), 'utf8')

describe('quote market data adapter', () => {
  it('keeps market data store and quote adapter implementation in the market feature', () => {
    assert.equal(existsSync(join(marketFeatureDir, 'marketDataStore.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'quoteMarketDataAdapter.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'authoritativeMarketSnapshot.ts')), true)
    assert.match(adapterSource, /features\/market\/quoteMarketDataAdapter/)
    assert.equal(existsSync(join(marketFeatureDir, 'quoteMarketDataSnapshot.ts')), false)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataSnapshot.ts')), false)
  })

  it('depends on feature-level market modules instead of the trading page directory', () => {
    assert.doesNotMatch(adapterSource, /\.\.\/\.\.\/pages\/trading/)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingMarketApi.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingMarketAdapters.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'tradingModels.ts')), true)
    assert.equal(existsSync(join(marketFeatureDir, 'mockTradingData.ts')), false)
  })

  it('publishes only a complete authoritative bundle and never synthesizes quote depth or trades', () => {
    assert.match(marketFeatureAdapterSource, /Promise\.all\(\[/)
    assert.match(marketFeatureAdapterSource, /createAuthoritativeMarketSnapshot\(latestQuote, latestOrderBook, latestTrades\)/)
    assert.doesNotMatch(marketFeatureAdapterSource, /createQuoteMarketDataSnapshot|createFallbackMarketDataSnapshot/)
    assert.doesNotMatch(marketFeatureAdapterSource, /latestQuote\?\.mid \?\? orderBook\.lastPrice/)
    assert.match(marketFeatureAdapterSource, /subscribeQuote\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(marketFeatureAdapterSource, /subscribeOrderBook\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(marketFeatureAdapterSource, /subscribeRecentTrades\(symbol, token, scheduleBundleRefresh\)/)
    assert.doesNotMatch(marketFeatureAdapterSource, /as BackendQuote|as BackendOrderBook|as BackendRecentTrade/)
  })

  it('invalidates the old bundle immediately while a provider source is changing', () => {
    const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')

    assert.match(marketFeatureAdapterSource, /subscribeMarketSourceChanges/)
    assert.match(marketFeatureAdapterSource, /unavailableSnapshot\('source-changing'\)/)
    assert.match(marketFeatureAdapterSource, /matchesExpectedMarketSource/)
    assert.doesNotMatch(marketFeatureAdapterSource, /expectedSource = undefined/)
    assert.match(sidePanelSource, /marketStatus === 'source-changing'/)
    assert.match(sidePanelSource, /trading\.marketDataSourceChanging/)
  })
})
