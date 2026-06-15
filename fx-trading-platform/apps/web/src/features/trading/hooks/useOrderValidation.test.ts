import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  applyBestPrice,
  applyPercent,
  buildOrderPayload,
  createInitialTradeForm,
  deriveTradeForm,
  syncLimitPriceFromMarket,
  validateOrder
} from './useTradeForm.ts'
import { mockBalances, mockMarket } from './useMockBalances.ts'
import { strategyOptions } from '../types/order.ts'
import type { TradeFormState } from '../types/order.ts'

describe('trade form model', () => {
  it('initializes limit forms with the side-specific best price', () => {
    assert.equal(createInitialTradeForm('buy', mockMarket).price, '60736.3')
    assert.equal(createInitialTradeForm('sell', mockMarket).price, '60732.2')
  })

  it('keeps limit price, amount and total in sync', () => {
    const form = createInitialTradeForm('buy', mockMarket)

    const withAmount = deriveTradeForm(
      { ...form, orderType: 'limit', price: '60736.3' },
      { amount: '0.1' },
      'amount'
    )
    assert.equal(withAmount.total, '6073.63')

    const withTotal = deriveTradeForm(withAmount, { total: '3036.815' }, 'total')
    assert.equal(withTotal.amount, '0.05')
  })

  it('uses ask for buy best price and bid for sell best price', () => {
    assert.equal(applyBestPrice(createInitialTradeForm('buy', mockMarket), mockMarket).price, '60736.3')
    assert.equal(applyBestPrice(createInitialTradeForm('sell', mockMarket), mockMarket).price, '60732.2')
  })

  it('updates untouched limit prices from the latest same-symbol market without overwriting edited input', () => {
    const form = {
      ...createInitialTradeForm('buy', mockMarket),
      amount: '0.1'
    }
    const nextMarket = { ...mockMarket, bestAsk: 60750, bestBid: 60745, lastPrice: 60748 }

    const synced = syncLimitPriceFromMarket(form, nextMarket, false, false)
    assert.equal(synced.price, '60750')
    assert.equal(synced.total, '6075')

    const edited = syncLimitPriceFromMarket({ ...form, price: '60600' }, nextMarket, true, false)
    assert.equal(edited.price, '60600')

    const focused = syncLimitPriceFromMarket({ ...form, price: '60610' }, nextMarket, false, true)
    assert.equal(focused.price, '60610')
  })

  it('fills buy and sell amounts from percentage balances', () => {
    const buyForm = applyPercent(
      { ...createInitialTradeForm('buy', mockMarket), orderType: 'limit', price: '50000' },
      25,
      mockBalances
    )
    assert.equal(buyForm.amount, '0.05')
    assert.equal(buyForm.total, '2500')

    const sellForm = applyPercent(
      { ...createInitialTradeForm('sell', mockMarket), orderType: 'limit', price: '50000' },
      50,
      mockBalances
    )
    assert.equal(sellForm.amount, '0.125')
    assert.equal(sellForm.total, '6250')
  })

  it('derives market buy base amount from quote total', () => {
    const marketBuy = {
      ...createInitialTradeForm('buy', mockMarket),
      orderType: 'market' as const,
      price: ''
    }
    const form = deriveTradeForm(marketBuy, { total: '6073.33' }, 'total', mockMarket)

    assert.equal(form.amount, '0.1')
  })

  it('keeps market buy notional in sync when the user edits contract quantity', () => {
    const marketBuy = {
      ...createInitialTradeForm('buy', mockMarket),
      orderType: 'market' as const,
      price: '',
      total: '2500'
    }
    const form = deriveTradeForm(marketBuy, { amount: '0.1' }, 'amount', mockMarket)

    assert.equal(form.total, '6073.33')
  })

  it('uses last price for market buy percentage sizing', () => {
    const marketBuy = applyPercent(
      {
        ...createInitialTradeForm('buy', mockMarket),
        orderType: 'market',
        price: ''
      },
      25,
      mockBalances,
      mockMarket
    )

    assert.equal(marketBuy.total, '2500')
    assert.ok(Number(marketBuy.amount) > 0)
  })

  it('blocks invalid orders before mock submission', () => {
    const zeroAmount = validateOrder(
      { ...createInitialTradeForm('buy', mockMarket), price: '60736.3', amount: '0', total: '0' },
      { balances: mockBalances, market: mockMarket, minAmount: 0.0001, minNotional: 5 }
    )
    assert.deepEqual(zeroAmount.errors, ['amount'])

    const tooSmallAmount = validateOrder(
      { ...createInitialTradeForm('buy', mockMarket), price: '60736.3', amount: '0.00001', total: '0.607363' },
      { balances: mockBalances, market: mockMarket, minAmount: 0.0001, minNotional: 5 }
    )
    assert.deepEqual(tooSmallAmount.errors, ['minAmount', 'minNotional'])

    const tooLargeBuy = validateOrder(
      { ...createInitialTradeForm('buy', mockMarket), price: '60736.3', amount: '1', total: '60736.3' },
      { balances: mockBalances, market: mockMarket, minAmount: 0.0001, minNotional: 5 }
    )
    assert.deepEqual(tooLargeBuy.errors, ['quoteBalance'])

    const badTpSl = validateOrder(
      {
        ...createInitialTradeForm('sell', mockMarket),
        price: '60736.3',
        amount: '0.01',
        total: '607.363',
        strategyType: 'tp_sl',
        tpSlEnabled: true,
        takeProfitEnabled: true,
        takeProfitTriggerPrice: ''
      },
      { balances: mockBalances, market: mockMarket, minAmount: 0.0001, minNotional: 5 }
    )
    assert.deepEqual(badTpSl.errors, ['takeProfitTriggerPrice'])
  })

  it('builds a payload with the reserved strategy fields', () => {
    const form: TradeFormState = {
      ...createInitialTradeForm('buy', mockMarket),
      orderType: 'limit',
      strategyType: 'advanced_limit',
      advancedLimitMode: 'post_only',
      price: '60736.3',
      amount: '0.02',
      total: '1214.726'
    }

    const payload = buildOrderPayload(form, mockMarket)
    assert.equal(payload.symbol, 'BTC-USDT')
    assert.equal(payload.side, 'buy')
    assert.equal(payload.orderType, 'limit')
    assert.equal(payload.strategyType, 'advanced_limit')
    assert.equal(payload.advancedLimitMode, 'post_only')
    assert.match(payload.clientOrderId, /^mock_/)
  })

  it('exposes the complete first-phase strategy menu model', () => {
    assert.deepEqual(
      strategyOptions.map((option) => option.labelKey),
      [
        'trading.strategy.tpSl',
        'trading.strategy.trailingTpSl',
        'trading.strategy.trigger',
        'trading.strategy.advancedLimit',
        'trading.strategy.splitOrder',
        'trading.strategy.iceberg',
        'trading.strategy.twap'
      ]
    )
    assert.deepEqual(
      strategyOptions.filter((option) => !option.available).map((option) => option.value),
      ['split_order', 'iceberg', 'twap']
    )
  })
})
