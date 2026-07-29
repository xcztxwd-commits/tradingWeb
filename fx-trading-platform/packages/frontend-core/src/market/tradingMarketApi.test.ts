import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import {
  buildMarketCandlesPath,
  buildMarketFavoritePath,
  buildMarketFavoritesPath,
  buildMarketOrderBookPath,
  buildMarketQuotesPath,
  buildMarketRecentTradesPath,
  buildMarketSymbolRulesBatchPath,
  buildMarketSymbolRulesPath,
  buildMarketStatusPath,
  buildMarketSymbolsPath,
  createTradingQuoteFromMarket,
  createTradingMarketPlaceholder,
  mapInstrumentRulesToTradingRules,
  mapOrderBookToMarketData,
  mapQuoteToTradingQuote,
  mapRecentTradeBatchToMarketData,
  mapRecentTradesToMarketData,
  mapSymbolToTradingMarket,
  tradingMarketEndpoints
} from './tradingMarketAdapters.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'tradingMarketAdapters.ts'), 'utf8')

describe('trading market API adapters', () => {
  it('owns market data snapshot types inside the market feature boundary', () => {
    assert.equal(existsSync(join(currentDir, 'marketDataTypes.ts')), true)
    assert.match(adapterSource, /from '\.\/marketDataTypes\.ts'/)
    assert.doesNotMatch(adapterSource, /components\/market-side-panel\/types/)
  })

  it('uses current backend endpoints instead of removed market ticker and candle paths', () => {
    assert.deepEqual(Object.values(tradingMarketEndpoints), [
      '/api/market/symbols',
      '/api/market/favorites',
      '/api/market/status',
      '/api/market/symbol-rules',
      '/api/chart/candles',
      '/api/market/order-book',
      '/api/market/trades'
    ])
  })

  it('exposes the backend market status endpoint for the trading page', () => {
    assert.equal(buildMarketStatusPath(), '/api/market/status')
  })

  it('exposes the authenticated user favorite endpoints for shared watchlists', () => {
    assert.equal(buildMarketFavoritesPath(), '/api/market/favorites')
    assert.equal(buildMarketFavoritePath('btcusdt'), '/api/market/favorites/BTCUSDT')
  })

  it('builds chart candle requests with timeframe, from, and to parameters', () => {
    const path = buildMarketCandlesPath('EURUSD', '5m', new Date('2026-06-06T00:00:00.000Z'), 3)

    assert.equal(
      path,
      '/api/chart/candles?symbol=EURUSD&timeframe=5m&from=2026-06-05T23%3A45%3A00.000Z&to=2026-06-06T00%3A00%3A00.000Z'
    )
  })

  it('builds older chart candle windows from an explicit end timestamp', () => {
    const path = buildMarketCandlesPath('EURUSD', '1m', 1_780_000_000_000, 2)

    assert.equal(
      path,
      '/api/chart/candles?symbol=EURUSD&timeframe=1m&from=2026-05-28T20%3A24%3A40.000Z&to=2026-05-28T20%3A26%3A40.000Z'
    )
  })

  it('builds backend order book and recent trade paths by symbol', () => {
    assert.equal(buildMarketOrderBookPath('BTCUSDT'), '/api/market/order-book/BTCUSDT')
    assert.equal(buildMarketRecentTradesPath('BTCUSDT', 40), '/api/market/trades/BTCUSDT?limit=40')
  })

  it('builds one backend request for batch quote hydration', () => {
    assert.equal(buildMarketQuotesPath(['eur-usd', 'BTCUSDT', 'EURUSD']), '/api/market/quotes?symbols=EURUSD%2CBTCUSDT')
    assert.equal(buildMarketQuotesPath(['btc_usdt-perp']), '/api/market/quotes?symbols=BTCUSDT-PERP')
  })

  it('requests enough symbols to keep provider forex rows from being truncated by local seed rows', () => {
    assert.equal(buildMarketSymbolsPath(), '/api/market/symbols?limit=2000')
    assert.equal(buildMarketSymbolsPath(1200), '/api/market/symbols?limit=1200')
  })

  it('builds backend instrument rules endpoints by symbol', () => {
    assert.equal(buildMarketSymbolRulesPath('btcusdt'), '/api/market/symbols/BTCUSDT/rules')
    assert.equal(buildMarketSymbolRulesBatchPath(['btcusdt', 'ethusdt']), '/api/market/symbol-rules?symbols=BTCUSDT%2CETHUSDT')
    assert.equal(buildMarketSymbolRulesPath('btc_usdt-perp'), '/api/market/symbols/BTCUSDT-PERP/rules')
    assert.equal(buildMarketSymbolRulesBatchPath(['btc/usdt_perp']), '/api/market/symbol-rules?symbols=BTCUSDT-PERP')
  })

  it('maps backend instrument rules to numeric frontend rules', () => {
    const rules = mapInstrumentRulesToTradingRules({
      symbol: 'BTCUSDT',
      exists: true,
      enabled: true,
      tradable: true,
      quoteEnabled: true,
      chartEnabled: true,
      orderBookEnabled: true,
      orderEnabled: false,
      productType: 'CRYPTO_SPOT',
      tickSize: '0.01',
      stepSize: '0.0001',
      minQty: '0.0001',
      maxQty: '100',
      minNotional: '5',
      maxNotional: '100000',
      maxLeverage: 1,
      defaultLeverage: 1,
      marginAsset: 'USDT',
      settlementAsset: 'USDT',
      contractSize: '1',
      contractMultiplier: '10',
      riskTier: 'spot-default',
      tradingSession: '24x7',
      kycRequirement: 'STANDARD',
      userRiskLevelRestriction: 'NONE'
    })

    assert.equal(rules.tickSize, 0.01)
    assert.equal(rules.stepSize, 0.0001)
    assert.equal(rules.minNotional, 5)
    assert.equal(rules.orderEnabled, false)
    assert.equal(rules.maxLeverage, 1)
    assert.equal(rules.contractMultiplier, 10)
  })

  it('maps backend symbols to the trading page market model', () => {
    const market = mapSymbolToTradingMarket({
      symbol: 'EURUSD',
      displayName: 'Euro / US Dollar',
      assetClass: 'FOREX',
      baseCurrency: 'EUR',
      quoteCurrency: 'USD',
      minLot: '0.01',
      maxLot: '100',
      leverage: 100,
      enabled: true
    })

    assert.deepEqual(
      {
        symbol: market.symbol,
        base: market.base,
        quote: market.quote,
        name: market.name,
        category: market.category,
        favorite: market.favorite,
      leverage: market.leverage,
      minLot: market.minLot,
      quantityPrecision: market.quantityPrecision,
      provider: market.provider,
      providerSymbol: market.providerSymbol,
      tradable: market.tradable
    },
    {
      symbol: 'EURUSD',
      base: 'EUR',
      quote: 'USD',
      name: 'Euro / US Dollar',
      category: 'fx',
      favorite: false,
      leverage: 100,
      minLot: '0.01',
      quantityPrecision: 2,
      provider: undefined,
      providerSymbol: undefined,
      tradable: true
    }
  )
  })

  it('preserves provider metadata for external market data symbols', () => {
    const market = mapSymbolToTradingMarket({
      symbol: 'USDJPY',
      displayName: 'US Dollar / Japanese Yen',
      assetClass: 'FOREX',
      baseCurrency: 'USD',
      quoteCurrency: 'JPY',
      minLot: '0.01',
      maxLot: '100',
      leverage: 100,
      enabled: true,
      provider: 'massive',
      providerSymbol: 'C:USDJPY',
      tradable: false
    })

    assert.equal(market.source, 'massive')
    assert.equal(market.providerSymbol, 'C:USDJPY')
    assert.equal(market.tradable, false)
  })

  it('preserves Binance icon URLs from backend crypto symbols', () => {
    const iconUrl = 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20201110/btc.png'
    const market = mapSymbolToTradingMarket({
      symbol: 'BTCUSDT',
      displayName: 'Bitcoin / Tether',
      assetClass: 'CRYPTO',
      productType: 'CRYPTO_SPOT',
      baseCurrency: 'BTC',
      quoteCurrency: 'USDT',
      enabled: true,
      provider: 'binance',
      providerSymbol: 'BTCUSDT',
      iconUrl
    })

    assert.equal(market.iconUrl, iconUrl)
    assert.equal(market.category, 'crypto')
    assert.equal(market.productType, 'CRYPTO_SPOT')
  })

  it('preserves runtime market data capabilities from backend symbols', () => {
    const market = mapSymbolToTradingMarket({
      symbol: 'EURUSD',
      displayName: 'Euro / US Dollar',
      assetClass: 'FOREX',
      baseCurrency: 'EUR',
      quoteCurrency: 'USD',
      enabled: true,
      quoteEnabled: false,
      chartEnabled: true,
      orderBookEnabled: false
    })

    assert.equal(market.quoteEnabled, false)
    assert.equal(market.chartEnabled, true)
    assert.equal(market.orderBookEnabled, false)
  })

  it('does not request realtime data for forex placeholders before backend capabilities load', () => {
    const market = createTradingMarketPlaceholder('EURUSD')

    assert.equal(market.quoteEnabled, false)
    assert.equal(market.chartEnabled, false)
    assert.equal(market.orderBookEnabled, false)
  })

  it('maps backend symbol quote metrics for full market list rows', () => {
    const market = mapSymbolToTradingMarket({
      symbol: 'EURUSD',
      displayName: 'Euro / US Dollar',
      assetClass: 'FOREX',
      baseCurrency: 'EUR',
      quoteCurrency: 'USD',
      minLot: '0.01',
      maxLot: '100',
      leverage: 100,
      enabled: true,
      provider: 'massive',
      providerSymbol: 'C:EURUSD',
      tradable: false,
      lastPrice: '1.12500',
      changePercent: '2.272727',
      high24h: '1.13000',
      low24h: '1.09000',
      volume24h: '12345',
      marketCap: '111111.25',
      spread: '0.00004',
      quoteSource: 'massive-snapshot',
      quoteTimestamp: 1_781_462_400_000
    })

    assert.equal(market.last, 1.125)
    assert.equal(market.changePercent, 2.272727)
    assert.equal(market.high24h, 1.13)
    assert.equal(market.low24h, 1.09)
    assert.equal(market.volume, '12.35K')
    assert.equal(market.marketCap, 111111.25)
    assert.equal(market.spread, 0.00004)
    assert.equal(market.source, 'massive-snapshot')
  })

  it('maps backend quote strings to numeric trading quotes without mock values', () => {
    const quote = mapQuoteToTradingQuote({
      type: 'quote',
      symbol: 'EURUSD',
      bid: '1.08318',
      ask: '1.08322',
      mid: '1.08320',
      spread: '0.00004',
      source: 'demo',
      timestamp: 1_780_000_000_000
    })

    assert.deepEqual(quote, {
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
  })

  it('uses provider quote summary fields for visible 24h market metrics', () => {
    const quote = mapQuoteToTradingQuote({
      type: 'quote',
      symbol: 'EURUSD',
      bid: '1.12498',
      ask: '1.12502',
      mid: '1.12500',
      spread: '0.00004',
      source: 'massive-aggregate',
      timestamp: 1_781_462_400_000,
      changePercent: '2.272727',
      high24h: '1.13000',
      low24h: '1.09000',
      volume24h: '12345'
    })

    assert.equal(quote.changePercent, 2.272727)
    assert.equal(quote.high24h, 1.13)
    assert.equal(quote.low24h, 1.09)
    assert.equal(quote.volume, '12.35K')
  })

  it('never turns a P0 symbol-list metric into an executable quote', () => {
    const quote = createTradingQuoteFromMarket({
      ...createTradingMarketPlaceholder('BTCUSDT'),
      last: 60_000,
      high24h: 61_000,
      low24h: 59_000,
      tradable: true,
      quoteTimestamp: Date.now()
    })

    assert.equal(quote.mid, 0)
    assert.equal(quote.timestamp, 0)
    assert.equal(quote.tradable, false)
  })

  it('preserves authoritative quote source metadata and freshness', () => {
    const quote = mapQuoteToTradingQuote({
      type: 'quote',
      symbol: 'BTCUSDT',
      bid: '59999',
      ask: '60001',
      mid: '60000',
      spread: '2',
      source: 'binance',
      timestamp: 1_784_000_000_000,
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    }, undefined, Date.parse('2026-07-13T00:00:01.000Z'))

    assert.deepEqual(quote.marketSource, {
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    })
    assert.equal(quote.tradable, true)
  })

  it('maps backend order book rows to the market side panel snapshot shape', () => {
    const snapshot = mapOrderBookToMarketData({
      symbol: 'EURUSD',
      timestamp: 1_780_000_000_000,
      bids: [
        { price: '1.08318', amount: '2.50000000' },
        { price: '1.08317', amount: '1.25000000' }
      ],
      asks: [{ price: '1.08322', amount: '3.00000000' }]
    })

    assert.deepEqual(snapshot, {
      symbol: 'EURUSD',
      bids: [
        { price: 1.08318, amount: 2.5 },
        { price: 1.08317, amount: 1.25 }
      ],
      asks: [{ price: 1.08322, amount: 3 }],
      lastPrice: 1.0832,
      lastPriceDirection: 'flat'
    })
  })

  it('maps backend recent trades newest first for the market side panel', () => {
    const trades = mapRecentTradesToMarketData([
      {
        id: 'EURUSD-1',
        symbol: 'EURUSD',
        price: '1.08320',
        amount: '0.42000000',
        side: 'buy',
        timestamp: 1_780_000_000_000
      },
      {
        id: 'EURUSD-2',
        symbol: 'EURUSD',
        price: '1.08318',
        amount: '0.24000000',
        side: 'sell',
        timestamp: 1_780_000_001_000
      }
    ])

    assert.deepEqual(trades, [
      { id: 'EURUSD-1', price: 1.0832, amount: 0.42, side: 'buy', time: 1_780_000_000_000 },
      { id: 'EURUSD-2', price: 1.08318, amount: 0.24, side: 'sell', time: 1_780_000_001_000 }
    ])
  })

  it('preserves one bundle source on depth and recent-trade batches', () => {
    const depth = mapOrderBookToMarketData({
      symbol: 'BTCUSDT',
      timestamp: 1_784_000_000_000,
      bids: [{ price: '59999', amount: '2' }],
      asks: [{ price: '60001', amount: '3' }],
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    })
    const trades = mapRecentTradeBatchToMarketData([{
      id: 't-1',
      symbol: 'BTCUSDT',
      price: '60000',
      amount: '0.1',
      side: 'buy',
      timestamp: 1_784_000_000_000,
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-13T00:00:00.000Z',
      expiresAt: '2026-07-13T00:00:05.000Z',
      stale: false
    }])

    assert.equal(depth.symbol, 'BTCUSDT')
    assert.equal(depth.source?.providerCode, 'binance')
    assert.equal(trades.symbol, 'BTCUSDT')
    assert.equal(trades.source?.providerCode, 'binance')
    assert.equal(trades.recentTrades[0]?.id, 't-1')
  })
})
