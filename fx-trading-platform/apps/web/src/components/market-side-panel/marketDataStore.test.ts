import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createMarketDataStore } from './marketDataStore.ts'
import { buildOrderBookRows } from './utils.ts'
import { loadOrderBookSettings, saveOrderBookSettings } from './utils.ts'

describe('market side panel order book utilities', () => {
  it('aggregates bids downward, sorts from best bid, and accumulates totals', () => {
    const rows = buildOrderBookRows(
      {
        asks: [],
        bids: [
          { price: 101.9, amount: 1 },
          { price: 101.2, amount: 2 },
          { price: 100.7, amount: 3 }
        ],
        lastPrice: 101.4,
        lastPriceDirection: 'flat'
      },
      1,
      'bids'
    )

    assert.deepEqual(
      rows.bids.map((row) => ({ price: row.price, amount: row.amount, cumulativeTotal: row.cumulativeTotal })),
      [
        { price: 101, amount: 3, cumulativeTotal: 3 },
        { price: 100, amount: 3, cumulativeTotal: 6 }
      ]
    )
    assert.equal(rows.maxCumulativeTotal, 6)
  })

  it('aggregates asks upward and returns render order with the best ask nearest the last price', () => {
    const rows = buildOrderBookRows(
      {
        asks: [
          { price: 101.1, amount: 1 },
          { price: 101.8, amount: 2 },
          { price: 102.2, amount: 3 }
        ],
        bids: [],
        lastPrice: 101.4,
        lastPriceDirection: 'flat'
      },
      1,
      'asks'
    )

    assert.deepEqual(
      rows.asks.map((row) => ({ price: row.price, amount: row.amount, cumulativeTotal: row.cumulativeTotal })),
      [
        { price: 103, amount: 3, cumulativeTotal: 6 },
        { price: 102, amount: 3, cumulativeTotal: 3 }
      ]
    )
    assert.equal(rows.maxCumulativeTotal, 6)
  })
})

describe('market data store', () => {
  it('keeps rapid book writes in memory and publishes a batched snapshot', async () => {
    const store = createMarketDataStore({ flushMs: 20 })
    let commits = 0
    const unsubscribe = store.subscribe(() => {
      commits += 1
    })

    store.setOrderBookLevel('bid', 100, 1)
    store.setOrderBookLevel('bid', 99, 2)

    assert.equal(commits, 0)

    await new Promise((resolve) => setTimeout(resolve, 35))

    assert.equal(commits, 1)
    assert.deepEqual(store.getSnapshot().bids, [
      { price: 100, amount: 1 },
      { price: 99, amount: 2 }
    ])

    unsubscribe()
  })

  it('stores newest trades first and keeps the latest 100 items', () => {
    const store = createMarketDataStore({ flushMs: 20 })

    for (let index = 0; index < 101; index += 1) {
      store.addTrade({
        id: String(index),
        price: 100 + index,
        amount: 1,
        side: 'buy',
        time: index
      })
    }

    store.flushNow()

    const trades = store.getSnapshot().recentTrades
    assert.equal(trades.length, 100)
    assert.equal(trades[0].id, '100')
    assert.equal(trades.at(-1)?.id, '1')
  })

  it('replaces backend order book levels without clearing recent trades', () => {
    const store = createMarketDataStore({ flushMs: 20 })
    store.addTrade({ id: 't-1', price: 100, amount: 1, side: 'buy', time: 1 })
    store.setOrderBook([
      { side: 'bid', price: 99, amount: 2 },
      { side: 'ask', price: 101, amount: 3 }
    ])
    store.flushNow()

    assert.deepEqual(store.getSnapshot().bids, [{ price: 99, amount: 2 }])
    assert.deepEqual(store.getSnapshot().asks, [{ price: 101, amount: 3 }])
    assert.equal(store.getSnapshot().recentTrades[0].id, 't-1')
  })

  it('replaces backend recent trades without clearing order book levels', () => {
    const store = createMarketDataStore({ flushMs: 20 })
    store.setOrderBookLevel('bid', 99, 2)
    store.setRecentTrades([
      { id: 't-2', price: 100, amount: 1, side: 'sell', time: 2 }
    ])
    store.flushNow()

    assert.deepEqual(store.getSnapshot().bids, [{ price: 99, amount: 2 }])
    assert.deepEqual(store.getSnapshot().recentTrades, [
      { id: 't-2', price: 100, amount: 1, side: 'sell', time: 2 }
    ])
  })
})

describe('order book settings storage', () => {
  it('saves and reloads the depth bar setting', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value)
    }

    saveOrderBookSettings(
      {
        showAverageAndTotal: true,
        showBidAskRatio: true,
        showDepthBars: false,
        layoutMode: 'split'
      },
      storage
    )

    assert.equal(loadOrderBookSettings(storage).showDepthBars, false)
  })
})
