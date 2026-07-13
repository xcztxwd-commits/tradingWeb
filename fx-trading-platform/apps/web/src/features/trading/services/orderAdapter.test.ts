import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createInitialTradeForm } from '../hooks/useTradeForm.ts'
import type { TradeMarket } from '../types/order.ts'
import { CANONICAL_TIME_IN_FORCE, toOcoOrderPayload, toOrderPayload } from './orderAdapter.ts'

const spotMarket: TradeMarket = {
  symbol: 'BTCUSDT',
  lastPrice: 60_000,
  bestBid: 59_999,
  bestAsk: 60_001,
  baseAsset: 'BTC',
  quoteAsset: 'USDT',
  quantityMode: 'quote-budget',
  productType: 'CRYPTO_SPOT'
}

const perpetualMarket: TradeMarket = {
  ...spotMarket,
  symbol: 'BTCUSDT-PERP',
  quantityMode: 'quantity',
  productType: 'LINEAR_PERP',
  leverage: 10
}

describe('trading order adapter', () => {
  it('keeps a Spot MARKET BUY USDT budget as QUOTE quantity', () => {
    const form = {
      ...createInitialTradeForm('buy', spotMarket),
      orderType: 'market' as const,
      amount: '0.01',
      total: '600.25',
      clientOrderId: 'spot_market_buy'
    }

    const payload = toOrderPayload('acct_1', form, spotMarket)

    assert.equal(payload.symbol, 'BTCUSDT')
    assert.equal(payload.orderType, 'MARKET')
    assert.equal(payload.quantity, 600.25)
    assert.equal(payload.quantityUnit, 'QUOTE')
    assert.equal(payload.price, undefined)
    assert.equal(payload.positionSide, 'BOTH')
    assert.equal(payload.marginMode, 'CASH')
    assert.equal(payload.reduceOnly, false)
    assert.equal(CANONICAL_TIME_IN_FORCE, 'GTC')
    assert.equal('timeInForce' in payload, false)
    assert.equal('lots' in payload, false)
  })

  it('uses BASE quantity for Spot sell, LIMIT and STOP_MARKET orders', () => {
    const sell = toOrderPayload(
      'acct_1',
      {
        ...createInitialTradeForm('sell', spotMarket),
        orderType: 'market',
        amount: '0.02',
        total: '1199.98',
        clientOrderId: 'spot_sell'
      },
      spotMarket
    )
    const limit = toOrderPayload(
      'acct_1',
      {
        ...createInitialTradeForm('buy', spotMarket),
        orderType: 'limit',
        amount: '0.03',
        price: '59000',
        clientOrderId: 'spot_limit'
      },
      spotMarket
    )
    const stop = toOrderPayload(
      'acct_1',
      {
        ...createInitialTradeForm('sell', spotMarket),
        strategyType: 'trigger',
        amount: '0.04',
        triggerPrice: '58000',
        clientOrderId: 'spot_stop'
      },
      spotMarket
    )

    assert.deepEqual(
      [sell.quantityUnit, sell.quantity, limit.quantityUnit, limit.quantity],
      ['BASE', 0.02, 'BASE', 0.03]
    )
    assert.equal(limit.orderType, 'LIMIT')
    assert.equal(limit.price, 59000)
    assert.equal(stop.orderType, 'STOP_MARKET')
    assert.equal(stop.quantityUnit, 'BASE')
    assert.equal(stop.quantity, 0.04)
    assert.equal(stop.price, undefined)
    assert.equal(stop.triggerPrice, 58000)
    assert.equal(stop.triggerPriceType, 'LAST_PRICE')
  })

  it('builds a Spot OCO request with BASE quantity and GTC-compatible legs', () => {
    const form = {
      ...createInitialTradeForm('sell', spotMarket),
      amount: '0.05',
      price: '65000',
      triggerPrice: '57000',
      clientOrderId: 'spot_oco'
    }

    assert.deepEqual(toOcoOrderPayload('acct_1', form, spotMarket), {
      accountId: 'acct_1',
      symbol: 'BTCUSDT',
      side: 'SELL',
      quantity: 0.05,
      quantityUnit: 'BASE',
      limitPrice: 65000,
      stopTriggerPrice: 57000,
      triggerPriceType: 'LAST_PRICE',
      idempotencyKey: 'spot_oco',
      clientOrderId: 'spot_oco'
    })
  })

  it('supports BASE, QUOTE and CONTRACTS quantities for Perpetual orders', () => {
    const form = {
      ...createInitialTradeForm('buy', perpetualMarket),
      orderType: 'market' as const,
      amount: '0.2',
      total: '12000',
      clientOrderId: 'perp_units'
    }

    assert.deepEqual(
      ['BASE', 'QUOTE', 'CONTRACTS'].map((quantityUnit) => {
        const payload = toOrderPayload('acct_1', form, perpetualMarket, {
          quantityUnit: quantityUnit as 'BASE' | 'QUOTE' | 'CONTRACTS'
        })
        return [payload.quantityUnit, payload.quantity]
      }),
      [
        ['BASE', 0.2],
        ['QUOTE', 12000],
        ['CONTRACTS', 0.2]
      ]
    )
  })

  it('preserves the canonical Perpetual symbol and sends HEDGE order controls', () => {
    const form = {
      ...createInitialTradeForm('sell', perpetualMarket),
      orderType: 'limit' as const,
      amount: '0.3',
      price: '61000',
      clientOrderId: 'perp_hedge_short'
    }

    const payload = toOrderPayload('acct_1', form, perpetualMarket, {
      leverage: 25,
      positionMode: 'HEDGE',
      positionSide: 'SHORT',
      marginMode: 'ISOLATED',
      quantityUnit: 'BASE',
      reduceOnly: true
    })

    assert.equal(payload.symbol, 'BTCUSDT-PERP')
    assert.equal(payload.positionSide, 'SHORT')
    assert.equal(payload.marginMode, 'ISOLATED')
    assert.equal(payload.quantityUnit, 'BASE')
    assert.equal(payload.reduceOnly, true)
    assert.equal(payload.leverage, 25)
    assert.equal('timeInForce' in payload, false)
  })

  it('sends multiple attached Perpetual protection levels without scalar legacy fields', () => {
    const protections = [
      {
        protectionType: 'TAKE_PROFIT' as const,
        triggerPrice: 62000,
        triggerPriceType: 'MARK_PRICE' as const,
        triggerExecutionType: 'LIMIT' as const,
        price: 61950
      },
      {
        protectionType: 'STOP_LOSS' as const,
        triggerPrice: 58000,
        triggerPriceType: 'MARK_PRICE' as const,
        triggerExecutionType: 'MARKET' as const
      }
    ]
    const form = {
      ...createInitialTradeForm('buy', perpetualMarket),
      orderType: 'market' as const,
      amount: '0.1',
      clientOrderId: 'perp_protected',
      attachedProtections: protections
    }

    const payload = toOrderPayload('acct_1', form, perpetualMarket)

    assert.deepEqual(payload.attachedProtections, protections)
    assert.equal('stopLoss' in payload, false)
    assert.equal('takeProfit' in payload, false)
  })

  it('rejects non-finite or non-positive canonical quantities at the adapter boundary', () => {
    const form = {
      ...createInitialTradeForm('buy', perpetualMarket),
      orderType: 'market' as const,
      amount: 'not-a-number',
      clientOrderId: 'invalid_quantity'
    }

    assert.throws(() => toOrderPayload('acct_1', form, perpetualMarket), /quantity must be a finite positive number/)
  })
})
