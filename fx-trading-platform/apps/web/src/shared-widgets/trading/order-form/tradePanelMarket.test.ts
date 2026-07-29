import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createInitialTradeForm, toOrderPayload } from '@fx-platform/frontend-core'
import { createPanelMarket, resolveUnitSize } from './tradePanelMarket.ts'

describe('trade panel market model', () => {
  it('keeps the selected symbol in the order payload when the quote snapshot is empty', () => {
    const market = createPanelMarket('ETHUSDT', { bids: [], asks: [], lastPrice: 0 })
    const form = {
      ...createInitialTradeForm('buy', market),
      orderType: 'market' as const,
      amount: '0.5',
      total: '50',
      clientOrderId: 'client_eth_empty_book'
    }

    assert.equal(market.symbol, 'ETHUSDT')
    assert.equal(market.baseAsset, 'ETH')
    assert.equal(market.quoteAsset, 'USDT')
    assert.equal(toOrderPayload('acct_1', form, market).symbol, 'ETHUSDT')
  })

  it('uses the current quote snapshot instead of the BTC mock market for BTCUSDT', () => {
    const market = createPanelMarket('BTCUSDT', {
      symbol: 'BTCUSDT',
      bids: [{ price: 67123.4 }],
      asks: [{ price: 67124.8 }],
      lastPrice: 67124.1,
      tradable: true
    })

    assert.equal(market.symbol, 'BTCUSDT')
    assert.equal(market.bestBid, 67123.4)
    assert.equal(market.bestAsk, 67124.8)
    assert.equal(market.lastPrice, 67124.1)
  })

  it('does not borrow BTC mock prices for another symbol when the snapshot is empty', () => {
    const market = createPanelMarket('ETHUSDT', { bids: [], asks: [], lastPrice: 0 })

    assert.equal(market.symbol, 'ETHUSDT')
    assert.equal(market.bestBid, 0)
    assert.equal(market.bestAsk, 0)
    assert.equal(market.lastPrice, 0)
  })

  it('clears all executable prices when the authoritative bundle is incomplete or stale', () => {
    const market = createPanelMarket('BTCUSDT', {
      bids: [{ price: 59_999 }],
      asks: [{ price: 60_001 }],
      lastPrice: 60_000,
      updatedAt: Date.now(),
      tradable: false
    })

    assert.equal(market.tradable, false)
    assert.equal(market.lastPrice, 0)
    assert.equal(market.bestBid, 0)
    assert.equal(market.bestAsk, 0)
  })

  it('fails closed when the market snapshot does not explicitly declare itself tradable', () => {
    const market = createPanelMarket('BTCUSDT', {
      bids: [{ price: 59_999 }],
      asks: [{ price: 60_001 }],
      lastPrice: 60_000,
      updatedAt: Date.now()
    })

    assert.equal(market.tradable, false)
    assert.equal(market.lastPrice, 0)
  })

  it('fails closed during a symbol switch when the snapshot still belongs to the previous symbol', () => {
    const market = createPanelMarket('ETHUSDT', {
      symbol: 'BTCUSDT',
      bids: [{ price: 59_999 }],
      asks: [{ price: 60_001 }],
      lastPrice: 60_000,
      tradable: true
    })

    assert.equal(market.tradable, false)
    assert.equal(market.lastPrice, 0)
  })

  it('models forex quantity as standard lots with margin sizing metadata', () => {
    const market = createPanelMarket(
      'EURUSD',
      { bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378 },
      { category: 'fx', leverage: 100 }
    )

    assert.equal(market.symbol, 'EURUSD')
    assert.equal(market.unitSize, 100_000)
    assert.equal(market.quantityMode, 'quantity')
    assert.equal(market.leverage, 100)
  })

  it('uses productType to keep leveraged crypto spot in quote-budget market-buy mode', () => {
    const market = createPanelMarket(
      'BTCUSDT-PERP',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'CRYPTO_SPOT' }
    )

    assert.equal(market.unitSize, 1)
    assert.equal(market.quantityMode, 'quote-budget')
    assert.equal(market.productType, 'CRYPTO_SPOT')
  })

  it('preserves backend instrument rules in the panel market model', () => {
    const market = createPanelMarket(
      'BTCUSDT-PERP',
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
          contractSize: 0.1,
          contractMultiplier: 5
        }
      }
    )

    assert.equal(market.rules?.minNotional, 5)
    assert.equal(market.unitSize, 0.5)
    assert.equal(
      resolveUnitSize('BTCUSDT-PERP', 'crypto', 'LINEAR_PERP', {
        ...market.rules!,
        contractMultiplier: undefined
      }),
      0.1
    )
  })

  it('uses explicit productType modes for perpetual contracts', () => {
    const linear = createPanelMarket(
      'BTCUSDT-PERP',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'LINEAR_PERP' }
    )
    const inverse = createPanelMarket(
      'BTCUSD',
      { bids: [{ price: 67123.4 }], asks: [{ price: 67124.8 }], lastPrice: 67124.1 },
      { category: 'crypto', leverage: 20, productType: 'INVERSE_PERP' }
    )

    assert.equal(linear.quantityMode, 'quantity')
    assert.equal(linear.symbol, 'BTCUSDT-PERP')
    assert.equal(linear.baseAsset, 'BTC')
    assert.equal(linear.quoteAsset, 'USDT')
    assert.equal(inverse.quantityMode, 'contracts')
  })
})
