import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import {
  defaultTradingSymbols,
  normalizeTradingProductSymbol,
  resolveMobileTradingPath,
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

  it('keeps the mobile Trading tab on the exact current terminal route', () => {
    assert.equal(resolveMobileTradingPath('/trade/spot/ETHUSDT'), '/trade/spot/ETHUSDT')
    assert.equal(resolveMobileTradingPath('/trade/perpetual/ETHUSDT-PERP'), '/trade/perpetual/ETHUSDT-PERP')
  })

  it('uses the default Spot terminal for the mobile Trading tab outside a canonical terminal route', () => {
    assert.equal(resolveMobileTradingPath('/wallet'), '/trade/spot/BTCUSDT')
    assert.equal(resolveMobileTradingPath('/trade/perpetual/EURUSD'), '/trade/spot/BTCUSDT')
  })

  it('wires the route-aware resolver into AppShell without rewriting the static mobile nav', () => {
    const source = readFileSync(new URL('../mobile/shell/MobileShellChrome.tsx', import.meta.url), 'utf8')

    assert.match(source, /resolveMobileTradingPath/)
    assert.match(source, /resolveMobileTradingPath\(pathname\)/)
    assert.doesNotMatch(source, /mobileNavItems\[[^\]]+\]\.to\s*=/)
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
