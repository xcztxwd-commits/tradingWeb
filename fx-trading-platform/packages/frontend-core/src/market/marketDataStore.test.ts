import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createMarketDataStore } from './marketDataStore.ts'

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
