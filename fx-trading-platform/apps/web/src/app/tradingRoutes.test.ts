import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  defaultTradingSymbols,
  normalizeTradingProductSymbol,
  resolveSafeTradingPath,
  tradingProductSymbols
} from './tradingRoutes.ts'
import {
  getLastTradingSymbol,
  lastTradingSymbolsStorageKey,
  readLastTradingSymbols,
  resolveTradingPath,
  writeLastTradingSymbol
} from './hooks/useLastTradingSymbol.ts'

describe('canonical trading routes', () => {
  it('uses canonical Spot and Perpetual defaults', () => {
    assert.deepEqual(defaultTradingSymbols, {
      spot: 'BTCUSDT',
      perpetual: 'BTCUSDT-PERP'
    })
    assert.equal(resolveTradingPath('spot'), '/trade/spot/BTCUSDT')
    assert.equal(resolveTradingPath('perpetual'), '/trade/perpetual/BTCUSDT-PERP')
  })

  it('keeps explicit canonical symbols without provider-symbol rewriting', () => {
    assert.equal(resolveTradingPath('spot', 'ethusdt'), '/trade/spot/ETHUSDT')
    assert.equal(resolveTradingPath('perpetual', 'ethusdt-perp'), '/trade/perpetual/ETHUSDT-PERP')
    assert.equal(normalizeTradingProductSymbol('perpetual', 'BTCUSDT-PERP'), 'BTCUSDT-PERP')
    assert.equal(normalizeTradingProductSymbol('perpetual', 'BTC-USDT-SWAP'), null)
  })

  it('redirects unsupported products and symbols to a safe Spot route', () => {
    assert.equal(resolveSafeTradingPath('inverse', 'BTCUSD-PERP'), '/trade/spot/BTCUSDT')
    assert.equal(resolveSafeTradingPath('options', 'BTC-USD-OPTION'), '/trade/spot/BTCUSDT')
    assert.equal(resolveSafeTradingPath('perpetual', 'EURUSD'), '/trade/perpetual/BTCUSDT-PERP')
  })

  it('stores versioned last symbols independently for Spot and Perpetual', () => {
    const storage = createMemoryStorage()

    writeLastTradingSymbol('spot', 'SOLUSDT', storage)
    writeLastTradingSymbol('perpetual', 'XRPUSDT-PERP', storage)

    assert.match(lastTradingSymbolsStorageKey, /v2/)
    assert.deepEqual(JSON.parse(storage.getItem(lastTradingSymbolsStorageKey) ?? '{}'), {
      version: 2,
      spot: 'SOLUSDT',
      perpetual: 'XRPUSDT-PERP'
    })
    assert.equal(getLastTradingSymbol('spot', storage), 'SOLUSDT')
    assert.equal(getLastTradingSymbol('perpetual', storage), 'XRPUSDT-PERP')
    assert.equal(resolveTradingPath('perpetual', undefined, storage), '/trade/perpetual/XRPUSDT-PERP')
  })

  it('ignores stale or malformed storage instead of leaking legacy products', () => {
    const staleStorage = createMemoryStorage({
      'fx-platform-last-trading-symbols': JSON.stringify({ crypto: 'ETHUSDT', forex: 'EURUSD' })
    })
    const malformedStorage = createMemoryStorage({
      [lastTradingSymbolsStorageKey]: JSON.stringify({ version: 2, spot: 'EURUSD', perpetual: 'BTCUSD-PERP' })
    })

    assert.deepEqual(readLastTradingSymbols(staleStorage), defaultTradingSymbols)
    assert.deepEqual(readLastTradingSymbols(malformedStorage), defaultTradingSymbols)
    assert.deepEqual(tradingProductSymbols.spot, ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT'])
    assert.deepEqual(tradingProductSymbols.perpetual, [
      'BTCUSDT-PERP',
      'ETHUSDT-PERP',
      'BNBUSDT-PERP',
      'SOLUSDT-PERP',
      'XRPUSDT-PERP'
    ])
  })
})

function createMemoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem(key: string) {
      return values.get(key) ?? null
    },
    setItem(key: string, value: string) {
      values.set(key, value)
    }
  }
}
