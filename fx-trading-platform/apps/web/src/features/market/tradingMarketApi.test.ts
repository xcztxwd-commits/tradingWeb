import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import {
  buildMarketCandlesPath,
  buildMarketOrderBookPath,
  buildMarketRecentTradesPath,
  buildMarketSymbolsPath,
  mapOrderBookToMarketData,
  mapQuoteToTradingQuote,
  mapRecentTradesToMarketData,
  mapSymbolToTradingMarket,
  tradingMarketEndpoints
} from './tradingMarketAdapters.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'tradingMarketAdapters.ts'), 'utf8')
const componentTypesSource = readFileSync(join(currentDir, '..', '..', 'components', 'market-side-panel', 'types.ts'), 'utf8')

describe('trading market API adapters', () => {
  it('owns market data snapshot types inside the market feature boundary', () => {
    assert.equal(existsSync(join(currentDir, 'marketDataTypes.ts')), true)
    assert.match(adapterSource, /from '\.\/marketDataTypes'/)
    assert.doesNotMatch(adapterSource, /components\/market-side-panel\/types/)
    assert.match(componentTypesSource, /from '..\/..\/features\/market\/marketDataTypes'/)
  })

  it('uses current backend endpoints instead of removed market ticker and candle paths', () => {
    assert.deepEqual(Object.values(tradingMarketEndpoints), [
      '/api/market/symbols',
      '/api/chart/candles',
      '/api/market/order-book',
      '/api/market/trades'
    ])
  })

  it('builds chart candle requests with timeframe, from, and to parameters', () => {
    const path = buildMarketCandlesPath('EURUSD', '5m', new Date('2026-06-06T00:00:00.000Z'), 3)

    assert.equal(
      path,
      '/api/chart/candles?symbol=EURUSD&timeframe=5m&from=2026-06-05T23%3A45%3A00.000Z&to=2026-06-06T00%3A00%3A00.000Z'
    )
  })

  it('builds backend order book and recent trade paths by symbol', () => {
    assert.equal(buildMarketOrderBookPath('BTCUSDT'), '/api/market/order-book/BTCUSDT')
    assert.equal(buildMarketRecentTradesPath('BTCUSDT', 40), '/api/market/trades/BTCUSDT?limit=40')
  })

  it('requests enough symbols to keep provider forex rows from being truncated by local seed rows', () => {
    assert.equal(buildMarketSymbolsPath(), '/api/market/symbols?limit=2000')
    assert.equal(buildMarketSymbolsPath(1200), '/api/market/symbols?limit=1200')
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
})
