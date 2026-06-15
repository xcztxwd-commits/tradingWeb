import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createInitialTradeForm } from '../hooks/useTradeForm.ts'
import { toOrderPayload } from '../services/orderAdapter.ts'
import { createPanelMarket } from './tradePanelMarket.ts'

describe('trade panel market model', () => {
  it('keeps the selected symbol in the order payload when the quote snapshot is empty', () => {
    const market = createPanelMarket('ETHUSDT', { bids: [], asks: [], lastPrice: 0 })
    const form = {
      ...createInitialTradeForm('buy', market),
      amount: '0.5',
      clientOrderId: 'client_eth_empty_book'
    }

    assert.equal(market.symbol, 'ETH-USDT')
    assert.equal(market.baseAsset, 'ETH')
    assert.equal(market.quoteAsset, 'USDT')
    assert.equal(toOrderPayload('acct_1', form, market).symbol, 'ETHUSDT')
  })

  it('uses the current quote snapshot instead of the BTC mock market for BTCUSDT', () => {
    const market = createPanelMarket('BTCUSDT', {
      bids: [{ price: 67123.4 }],
      asks: [{ price: 67124.8 }],
      lastPrice: 67124.1
    })

    assert.equal(market.symbol, 'BTC-USDT')
    assert.equal(market.bestBid, 67123.4)
    assert.equal(market.bestAsk, 67124.8)
    assert.equal(market.lastPrice, 67124.1)
  })

  it('does not borrow BTC mock prices for another symbol when the snapshot is empty', () => {
    const market = createPanelMarket('ETHUSDT', { bids: [], asks: [], lastPrice: 0 })

    assert.equal(market.symbol, 'ETH-USDT')
    assert.equal(market.bestBid, 0)
    assert.equal(market.bestAsk, 0)
    assert.equal(market.lastPrice, 0)
  })
})
