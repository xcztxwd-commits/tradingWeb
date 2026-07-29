import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'
import type { TradingMarket } from '@fx-platform/frontend-core'

import { compareMarkets, filterMarketRows } from './marketsRouteModel.ts'
import { resolveMarketTradingTarget } from './marketTradingTarget.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const routeSource = readFileSync(join(currentDir, 'MarketsRoute.tsx'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useMarketsRouteController.ts'), 'utf8')
const contentSource = readFileSync(join(webSrc, 'shared-widgets', 'market', 'MarketsContent.tsx'), 'utf8')
const pcSource = readFileSync(join(webSrc, 'pc', 'pages', 'markets', 'PcMarketsPage.tsx'), 'utf8')
const mobileSource = readFileSync(join(webSrc, 'mobile', 'pages', 'markets', 'MobileMarketsPage.tsx'), 'utf8')
const collectionPropsPath = join(webSrc, 'shared-widgets', 'market', 'marketCollection.types.ts')
const pcTablePath = join(webSrc, 'pc', 'pages', 'markets', 'PcMarketTable.tsx')
const mobileListPath = join(webSrc, 'mobile', 'pages', 'markets', 'MobileMarketList.tsx')
const pcTableSource = existsSync(pcTablePath) ? readFileSync(pcTablePath, 'utf8') : ''
const mobileListSource = existsSync(mobileListPath) ? readFileSync(mobileListPath, 'utf8') : ''

const markets = [
  market({ symbol: 'BTCUSDT', name: 'Bitcoin', category: 'crypto', favorite: true, last: 70_000, changePercent: 2, volume: '100' }),
  market({ symbol: 'ETHUSDT', name: 'Ethereum', category: 'crypto', favorite: false, last: 3_000, changePercent: -1, volume: '200' }),
  market({ symbol: 'EURUSD', name: 'Euro Dollar', category: 'fx', favorite: true, last: 1.1, changePercent: 0.1, volume: '50' })
]

describe('markets route platform contract', () => {
  it('keeps filter, favorite, sort and navigation models deterministic', () => {
    assert.deepEqual(filterMarketRows(markets, '', 'favorites', 'all').map(({ symbol }) => symbol), ['BTCUSDT', 'EURUSD'])
    assert.deepEqual(filterMarketRows(markets, 'eth', 'crypto', 'all').map(({ symbol }) => symbol), ['ETHUSDT'])
    assert.deepEqual(filterMarketRows(markets, '', 'forex', 'all').map(({ symbol }) => symbol), ['EURUSD'])
    assert.deepEqual([...markets].sort((left, right) => compareMarkets(left, right, 'volume', 'desc')).map(({ symbol }) => symbol), ['BTCUSDT', 'ETHUSDT', 'EURUSD'])
    assert.equal(resolveMarketTradingTarget({ symbol: 'BTCUSDT', productType: 'CRYPTO_SPOT' }), '/trade/spot/BTCUSDT')
    assert.equal(resolveMarketTradingTarget({ symbol: 'EURUSD', productType: 'FX_MARGIN' }), null)
  })

  it('owns loading, source, favorites, subscriptions and futures state in one controller', () => {
    for (const token of [
      'fetchMarketSymbols',
      'fetchMarketQuotes',
      'fetchBinanceMarketOverview',
      'fetchBinanceFuturesDashboard',
      'useMarketFavorites',
      'subscribeQuote',
      'visibleSymbolKey',
      'futuresPeriod',
      'apiError',
      'reloadKey'
    ]) assert.match(controllerSource, new RegExp(token))

    assert.doesNotMatch(`${contentSource}\n${pcSource}\n${mobileSource}`, /fetchMarket|fetchBinance|subscribeQuote|useMarketFavorites/)
    assert.match(contentSource, /LoadingState/)
    assert.match(contentSource, /ApiErrorState/)
    assert.match(contentSource, /visibleMarkets\.length/)
    assert.match(contentSource, /formatMarketPrice/)
  })

  it('settles blocking loading before optional quote enrichment', () => {
    const coreLoadIndex = controllerSource.indexOf('const [symbolsResult, overviewResult]')
    const loadingSettledIndex = controllerSource.indexOf('setLoading(false)', coreLoadIndex)
    const quoteEnrichmentIndex = controllerSource.indexOf('const quotedMarkets = await loadQuotedMarkets', coreLoadIndex)

    assert.ok(coreLoadIndex >= 0)
    assert.ok(loadingSettledIndex > coreLoadIndex)
    assert.ok(loadingSettledIndex < quoteEnrichmentIndex)
  })

  it('lazy-loads only the active platform view around the same model', () => {
    assert.match(routeSource, /const PcMarketsPage = lazy/)
    assert.match(routeSource, /const MobileMarketsPage = lazy/)
    assert.match(routeSource, /const model = useMarketsRouteController\(\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{model\}/)
    assert.doesNotMatch(routeSource, /^import .*\/(?:pc|mobile)\//m)
    assert.match(pcSource, /data-platform-view="pc"/)
    assert.match(pcSource, /PcMarketTable/)
    assert.doesNotMatch(pcSource, /MobileMarketList/)
    assert.match(mobileSource, /data-platform-view="mobile"/)
    assert.match(mobileSource, /MobileMarketList/)
    assert.doesNotMatch(mobileSource, /PcMarketTable/)
  })

  it('owns each market collection adapter in its platform directory', () => {
    assert.equal(existsSync(collectionPropsPath), true)
    assert.equal(existsSync(pcTablePath), true)
    assert.equal(existsSync(mobileListPath), true)

    assert.doesNotMatch(contentSource, /export function (?:MarketTable|MarketMobileList)/)
    assert.match(pcTableSource, /export function PcMarketTable/)
    assert.match(pcTableSource, /<table>/)
    assert.match(pcTableSource, /data-market-collection="pc-table"/)
    assert.doesNotMatch(pcTableSource, /MobileMarketList/)
    assert.match(mobileListSource, /export function MobileMarketList/)
    assert.match(mobileListSource, /aria-label="移动端行情列表"/)
    assert.match(mobileListSource, /data-market-collection="mobile-list"/)
    assert.doesNotMatch(mobileListSource, /PcMarketTable|<table>/)
    assert.doesNotMatch(`${pcTableSource}\n${mobileListSource}`, /fetchMarket|fetchBinance|subscribeQuote|useMarketFavorites/)
    assert.match(contentSource, /data-market-page-tab=\{tab\.value\}/)
  })
})

function market(overrides: Partial<TradingMarket>): TradingMarket {
  return {
    symbol: 'BTCUSDT',
    name: 'Bitcoin',
    category: 'crypto',
    bid: 0,
    ask: 0,
    last: 0,
    changePercent: 0,
    volume: '0',
    spread: 0,
    ...overrides
  } as TradingMarket
}
