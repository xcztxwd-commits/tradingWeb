import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createPanelMarket } from '../components/tradePanelMarket.ts'
import { testMarket as mockMarket } from './tradeFormTestFixtures.ts'
import {
  createInitialTradeForm,
  deriveTradeForm,
  getOrderNotional,
  getRequiredMargin,
  replaceAttachedProtections,
  validateOrder
} from './useTradeForm.ts'

describe('trade form sizing algorithms', () => {
  it('rejects malformed or more than ten attached protections before confirmation', () => {
    const perpetualMarket = createPanelMarket(
      'BTCUSDT-PERP',
      { bids: [{ price: 50_000 }], asks: [{ price: 50_001 }], lastPrice: 50_000 },
      { productType: 'LINEAR_PERP', leverage: 10 }
    )
    const form = createInitialTradeForm('buy', perpetualMarket)
    form.amount = '1'
    form.attachedProtections = [{
      protectionType: 'STOP_LOSS',
      triggerPrice: 0,
      triggerExecutionType: 'LIMIT',
      price: 0,
      quantity: 1,
      quantityUnit: 'BASE'
    }]

    assert.equal(validateOrder(form, { balances: { USDT: 50_000 }, market: perpetualMarket, minAmount: 0, minNotional: 0 }).errors.includes('attachedProtections'), true)
    form.attachedProtections = Array.from({ length: 11 }, (_, index) => ({
      protectionType: 'TAKE_PROFIT' as const,
      triggerPrice: 60_000 + index,
      triggerExecutionType: 'MARKET' as const,
      quantity: 0.05,
      quantityUnit: 'BASE' as const
    }))
    assert.equal(validateOrder(form, { balances: { USDT: 50_000 }, market: perpetualMarket, minAmount: 0, minNotional: 0 }).errors.includes('attachedProtections'), true)
  })

  it('requires attached quantities and caps each protection type at the parent quantity', () => {
    const perpetualMarket = createPanelMarket(
      'BTCUSDT-PERP',
      { bids: [{ price: 50_000 }], asks: [{ price: 50_001 }], lastPrice: 50_000 },
      { productType: 'LINEAR_PERP', leverage: 10 }
    )
    const form = {
      ...createInitialTradeForm('buy', perpetualMarket),
      orderType: 'market' as const,
      amount: '1',
      total: '50000',
      attachedProtections: [
        { protectionType: 'TAKE_PROFIT' as const, triggerPrice: 60_000, triggerExecutionType: 'MARKET' as const, quantity: 0.4, quantityUnit: 'BASE' as const },
        { protectionType: 'TAKE_PROFIT' as const, triggerPrice: 61_000, triggerExecutionType: 'MARKET' as const, quantity: 0.6, quantityUnit: 'BASE' as const },
        { protectionType: 'STOP_LOSS' as const, triggerPrice: 45_000, triggerExecutionType: 'MARKET' as const, quantity: 1, quantityUnit: 'BASE' as const }
      ]
    }
    const options = { balances: { USDT: 50_000 }, market: perpetualMarket, minAmount: 0, minNotional: 0 }

    assert.equal(validateOrder(form, options).errors.includes('attachedProtections'), false)
    assert.equal(validateOrder({
      ...form,
      attachedProtections: form.attachedProtections.map((protection, index) => index === 1 ? { ...protection, quantity: 0.7 } : protection)
    }, options).errors.includes('attachedProtections'), true)
    assert.equal(validateOrder({
      ...form,
      attachedProtections: [{ ...form.attachedProtections[0], quantity: undefined }]
    }, options).errors.includes('attachedProtections'), true)
  })
  it('initializes canonical order controls for Spot and Perpetual forms', () => {
    const spot = createPanelMarket(
      'BTCUSDT',
      { bids: [{ price: 60000 }], asks: [{ price: 60001 }], lastPrice: 60000 },
      { productType: 'CRYPTO_SPOT' }
    )
    const perpetual = createPanelMarket(
      'BTCUSDT-PERP',
      { bids: [{ price: 60000 }], asks: [{ price: 60001 }], lastPrice: 60000 },
      { productType: 'LINEAR_PERP' }
    )

    assert.deepEqual(
      {
        positionSide: createInitialTradeForm('buy', spot).positionSide,
        marginMode: createInitialTradeForm('buy', spot).marginMode,
        quantityUnit: createInitialTradeForm('buy', spot).quantityUnit,
        reduceOnly: createInitialTradeForm('buy', spot).reduceOnly,
        attachedProtections: createInitialTradeForm('buy', spot).attachedProtections
      },
      {
        positionSide: 'BOTH',
        marginMode: 'CASH',
        quantityUnit: 'BASE',
        reduceOnly: false,
        attachedProtections: []
      }
    )
    assert.deepEqual(
      {
        positionSide: createInitialTradeForm('sell', perpetual).positionSide,
        marginMode: createInitialTradeForm('sell', perpetual).marginMode,
        quantityUnit: createInitialTradeForm('sell', perpetual).quantityUnit
      },
      { positionSide: 'BOTH', marginMode: 'CROSS', quantityUnit: 'BASE' }
    )
  })

  it('replaces multi-level attached protections without mutating the current form', () => {
    const current = createInitialTradeForm('buy', mockMarket)
    const protections = [
      {
        protectionType: 'TAKE_PROFIT' as const,
        triggerPrice: 62000,
        triggerExecutionType: 'LIMIT' as const,
        price: 61950
      },
      {
        protectionType: 'STOP_LOSS' as const,
        triggerPrice: 58000,
        triggerExecutionType: 'MARKET' as const
      }
    ]

    const next = replaceAttachedProtections(current, protections)

    assert.equal(current.attachedProtections.length, 0)
    assert.notEqual(next, current)
    assert.notEqual(next.attachedProtections, protections)
    assert.deepEqual(next.attachedProtections, protections)
  })

  it('treats forex market buy amount as lots instead of quote budget', () => {
    const market = createPanelMarket(
      'EURUSD',
      { symbol: 'EURUSD', bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378, tradable: true },
      { category: 'fx', leverage: 100 }
    )
    const form = deriveTradeForm(
      { ...createInitialTradeForm('buy', market), orderType: 'market', price: '' },
      { amount: '0.01' },
      'amount',
      market
    )

    assert.equal(form.amount, '0.01')
    assert.equal(form.total, '1083.78')
    assert.equal(getOrderNotional(form, market), 1083.78)
    assert.equal(getRequiredMargin(form, market), 10.8378)
    assert.deepEqual(validateOrder(form, { balances: { USD: 100, EUR: 0 }, market, minAmount: 0.01, minNotional: 5 }).errors, [])
  })

  it('checks margin rather than base inventory for forex shorts', () => {
    const market = createPanelMarket(
      'EURUSD',
      { symbol: 'EURUSD', bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378, tradable: true },
      { category: 'fx', leverage: 100 }
    )
    const form = deriveTradeForm(
      { ...createInitialTradeForm('sell', market), orderType: 'market', price: '' },
      { amount: '0.01' },
      'amount',
      market
    )

    assert.deepEqual(validateOrder(form, { balances: { USD: 100, EUR: 0 }, market, minAmount: 0.01, minNotional: 5 }).errors, [])
  })

  it('keeps spot market buy total conversion for quote-budget markets', () => {
    const form = deriveTradeForm(
      { ...createInitialTradeForm('buy', mockMarket), orderType: 'market', price: '' },
      { total: '607.333' },
      'total',
      mockMarket
    )

    assert.equal(form.amount, '0.01')
    assert.equal(getOrderNotional(form, mockMarket), 607.333)
  })

  it('rejects market orders when the quote is unavailable or stale', () => {
    const emptyMarket = createPanelMarket('ETHUSDT', { bids: [], asks: [], lastPrice: 0 })
    const emptyQuoteForm = {
      ...createInitialTradeForm('buy', emptyMarket),
      orderType: 'market' as const,
      amount: '0.5',
      total: '0'
    }

    assert.deepEqual(
      validateOrder(emptyQuoteForm, { balances: { USDT: 1000, ETH: 0 }, market: emptyMarket, minAmount: 0, minNotional: 0 }).errors,
      ['marketStale']
    )

    const staleMarket = createPanelMarket(
      'BTCUSDT',
      { bids: [{ price: 60000 }], asks: [{ price: 60001 }], lastPrice: 60000, updatedAt: 1_780_000_000_000 }
    )
    const staleForm = {
      ...createInitialTradeForm('buy', staleMarket),
      orderType: 'market' as const,
      amount: '0.01',
      total: '600'
    }

    assert.deepEqual(
      validateOrder(staleForm, {
        balances: { USDT: 1000, BTC: 0 },
        market: staleMarket,
        minAmount: 0,
        minNotional: 0,
        now: 1_780_000_020_001
      }).errors,
      ['marketStale']
    )
  })
})
