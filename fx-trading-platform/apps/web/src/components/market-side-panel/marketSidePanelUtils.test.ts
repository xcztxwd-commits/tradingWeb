import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { buildOrderBookRows, loadOrderBookSettings, saveOrderBookSettings } from './utils.ts'

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
