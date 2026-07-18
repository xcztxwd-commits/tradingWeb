import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import {
  hydrateMarketFavorites,
  loadFavoriteSymbols,
  saveFavoriteSymbols,
  toggleFavoriteSymbol
} from './marketFavorites.ts'
import type { TradingMarket } from './tradingModels.ts'

describe('market favorites', () => {
  it('reuses the frontend-core browser storage boundary', () => {
    const source = readFileSync(new URL('./marketFavorites.ts', import.meta.url), 'utf8')
    assert.match(source, /import \{ getBrowserStorage, type KeyValueStorage \} from '\.\.\/storage\/browserStorage\.ts'/)
    assert.doesNotMatch(source, /Pick<Storage/)
    assert.doesNotMatch(source, /globalThis\.localStorage/)
  })

  it('persists normalized favorite symbols for the shared watchlist', () => {
    const storage = createStorage()

    saveFavoriteSymbols(new Set(['eurusd', 'BTCUSDT', '']), storage)

    assert.deepEqual([...loadFavoriteSymbols(storage)], ['EURUSD', 'BTCUSDT'])
  })

  it('toggles a symbol into and out of the favorites set', () => {
    const added = toggleFavoriteSymbol(new Set(['EURUSD']), 'btcusdt')
    const removed = toggleFavoriteSymbol(added, 'eurusd')

    assert.deepEqual([...added].sort(), ['BTCUSDT', 'EURUSD'])
    assert.deepEqual([...removed], ['BTCUSDT'])
  })

  it('hydrates market rows from the shared favorites set', () => {
    const markets = [
      market('EURUSD', false),
      market('BTCUSDT', false),
      market('ETHUSDT', true)
    ]

    const hydrated = hydrateMarketFavorites(markets, new Set(['btcusdt']))

    assert.deepEqual(hydrated.map((item) => [item.symbol, item.favorite]), [
      ['EURUSD', false],
      ['BTCUSDT', true],
      ['ETHUSDT', true]
    ])
  })
})

function createStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
    removeItem: (key: string) => {
      values.delete(key)
    }
  }
}

function market(symbol: string, favorite: boolean): TradingMarket {
  return {
    symbol,
    base: symbol.slice(0, 3),
    quote: symbol.slice(3),
    name: symbol,
    category: symbol.endsWith('USD') ? 'fx' : 'crypto',
    favorite,
    last: 1,
    changePercent: 0,
    volume: '0',
    high24h: 1,
    low24h: 1,
    spread: 0,
    source: 'test'
  }
}
