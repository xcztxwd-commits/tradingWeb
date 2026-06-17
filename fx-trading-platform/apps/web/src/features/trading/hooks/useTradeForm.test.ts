import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createPanelMarket } from '../components/tradePanelMarket.ts'
import { mockMarket } from './useMockBalances.ts'
import {
  createInitialTradeForm,
  deriveTradeForm,
  getOrderNotional,
  getRequiredMargin,
  validateOrder
} from './useTradeForm.ts'

describe('trade form sizing algorithms', () => {
  it('treats forex market buy amount as lots instead of quote budget', () => {
    const market = createPanelMarket(
      'EURUSD',
      { bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378 },
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
      { bids: [{ price: 1.08377 }], asks: [{ price: 1.08379 }], lastPrice: 1.08378 },
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
