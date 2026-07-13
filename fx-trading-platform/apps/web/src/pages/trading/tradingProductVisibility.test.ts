import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import type { TradingMarket } from '../../features/market/tradingModels.ts'
import {
  getTradingMarketsForProduct,
  mergeWithLocalTradingMarkets,
  normalizeTradingSymbol
} from './tradingPageMarketSelection.ts'

const spotSymbols = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT']
const perpetualSymbols = spotSymbols.map((symbol) => `${symbol}-PERP`)

describe('P0 trading product visibility', () => {
  it('shows exactly five Spot and five linear Perpetual markets', () => {
    const merged = mergeWithLocalTradingMarkets([
      market('EURUSD', 'FX_MARGIN'),
      market('BTCUSD-PERP', 'INVERSE_PERP'),
      market('BTC-USD-OPTION'),
      ...spotSymbols.map((symbol) => market(symbol, 'CRYPTO_SPOT')),
      ...perpetualSymbols.map((symbol) => market(symbol, 'LINEAR_PERP'))
    ])

    assert.deepEqual(getTradingMarketsForProduct(merged, 'spot').map(({ symbol }) => symbol), spotSymbols)
    assert.deepEqual(getTradingMarketsForProduct(merged, 'perpetual').map(({ symbol }) => symbol), perpetualSymbols)
    assert.equal(merged.some(({ symbol }) => symbol === 'EURUSD'), false)
    assert.equal(merged.some(({ productType }) => productType === 'INVERSE_PERP'), false)
    assert.equal(merged.some(({ symbol }) => symbol.includes('OPTION')), false)
  })

  it('keeps the local fallback within the exact ten-product allowlist', () => {
    const merged = mergeWithLocalTradingMarkets([])

    assert.equal(merged.length, 10)
    assert.deepEqual(merged.map(({ symbol }) => symbol), [...spotSymbols, ...perpetualSymbols])
  })

  it('preserves the canonical Perpetual hyphen and rejects provider symbols', () => {
    assert.equal(normalizeTradingSymbol(' btcusdt-perp '), 'BTCUSDT-PERP')
    assert.equal(normalizeTradingSymbol('BTC-USDT-SWAP'), 'BTC-USDT-SWAP')
    assert.notEqual(normalizeTradingSymbol('BTCUSDT-PERP'), 'BTCUSDTPERP')
  })

  it('uses local entries only as zero-price metadata and never imports ready-path mock markets', () => {
    const source = readFileSync(new URL('./tradingPageMarketSelection.ts', import.meta.url), 'utf8')
    const merged = mergeWithLocalTradingMarkets([])

    assert.doesNotMatch(source, /mockTradingData/)
    assert.ok(merged.every((market) => market.last === 0 && market.high24h === 0 && market.low24h === 0))
    assert.ok(merged.every((market) => market.tradable === false && market.source === 'metadata-only'))
  })
})

function market(symbol: string, productType?: TradingMarket['productType']): TradingMarket {
  return {
    symbol,
    base: symbol.split(/USDT|USD/)[0] || symbol,
    quote: symbol.includes('USDT') ? 'USDT' : 'USD',
    name: symbol,
    category: productType === 'FX_MARGIN' ? 'fx' : 'crypto',
    favorite: false,
    last: 1,
    changePercent: 0,
    volume: '0',
    high24h: 1,
    low24h: 1,
    spread: 0,
    source: 'test',
    productType
  }
}
