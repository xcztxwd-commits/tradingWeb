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

  it('models forex quantity as standard lots with margin sizing metadata', () => {
    const market = createPanelMarket(
      'EURUSD',
      { bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378 },
      { category: 'fx', leverage: 100 }
    )

    assert.equal(market.symbol, 'EUR-USD')
    assert.equal(market.unitSize, 100_000)
    assert.equal(market.quantityMode, 'quantity')
    assert.equal(market.leverage, 100)
  })

  it('uses productType to keep leveraged crypto spot in quote-budget market-buy mode', () => {
    const market = createPanelMarket(
      'BTCUSDT',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'CRYPTO_SPOT' }
    )

    assert.equal(market.unitSize, 1)
    assert.equal(market.quantityMode, 'quote-budget')
    assert.equal(market.productType, 'CRYPTO_SPOT')
  })

  it('preserves backend instrument rules in the panel market model', () => {
    const market = createPanelMarket(
      'BTCUSDT',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      {
        category: 'crypto',
        productType: 'CRYPTO_SPOT',
        rules: {
          symbol: 'BTCUSDT',
          exists: true,
          enabled: true,
          tradable: true,
          quoteEnabled: true,
          chartEnabled: true,
          orderBookEnabled: true,
          orderEnabled: true,
          minNotional: 5,
          stepSize: 0.0001,
          tickSize: 0.01,
          contractSize: 1
        }
      }
    )

    assert.equal(market.rules?.minNotional, 5)
    assert.equal(market.unitSize, 1)
  })

  it('uses explicit productType modes for perpetual contracts', () => {
    const linear = createPanelMarket(
      'BTCUSDT',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'LINEAR_PERP' }
    )
    const inverse = createPanelMarket(
      'BTCUSD',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'INVERSE_PERP' }
    )

    assert.equal(linear.quantityMode, 'quantity')
    assert.equal(inverse.quantityMode, 'contracts')
  })
})
