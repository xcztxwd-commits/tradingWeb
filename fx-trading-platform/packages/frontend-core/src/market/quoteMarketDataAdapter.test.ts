import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'quoteMarketDataAdapter.ts'), 'utf8')

describe('quote market data adapter', () => {
  it('keeps the market data store, authoritative snapshot and adapter in one core boundary', () => {
    assert.equal(existsSync(join(currentDir, 'marketDataStore.ts')), true)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataAdapter.ts')), true)
    assert.equal(existsSync(join(currentDir, 'authoritativeMarketSnapshot.ts')), true)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataSnapshot.ts')), false)
  })

  it('depends only on package-level market modules', () => {
    assert.doesNotMatch(adapterSource, /apps\/web|pages\/trading|components\/market-side-panel/)
    assert.equal(existsSync(join(currentDir, 'tradingMarketApi.ts')), true)
    assert.equal(existsSync(join(currentDir, 'tradingMarketAdapters.ts')), true)
    assert.equal(existsSync(join(currentDir, 'tradingModels.ts')), true)
    assert.equal(existsSync(join(currentDir, 'mockTradingData.ts')), false)
  })

  it('publishes only a complete authoritative bundle and never synthesizes quote depth or trades', () => {
    assert.match(adapterSource, /Promise\.all\(\[/)
    assert.match(adapterSource, /createAuthoritativeMarketSnapshot\(latestQuote, latestOrderBook, latestTrades\)/)
    assert.doesNotMatch(adapterSource, /createQuoteMarketDataSnapshot|createFallbackMarketDataSnapshot/)
    assert.doesNotMatch(adapterSource, /latestQuote\?\.mid \?\? orderBook\.lastPrice/)
    assert.match(adapterSource, /subscribeQuote\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(adapterSource, /subscribeOrderBook\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(adapterSource, /subscribeRecentTrades\(symbol, token, scheduleBundleRefresh\)/)
    assert.doesNotMatch(adapterSource, /as BackendQuote|as BackendOrderBook|as BackendRecentTrade/)
  })

  it('invalidates the old bundle immediately while a provider source is changing', () => {
    assert.match(adapterSource, /subscribeMarketSourceChanges/)
    assert.match(adapterSource, /unavailableSnapshot\('source-changing'\)/)
    assert.match(adapterSource, /matchesExpectedMarketSource/)
    assert.doesNotMatch(adapterSource, /expectedSource = undefined/)
  })
})
