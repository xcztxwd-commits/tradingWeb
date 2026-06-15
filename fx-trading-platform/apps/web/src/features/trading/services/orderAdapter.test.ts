import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createInitialTradeForm } from '../hooks/useTradeForm.ts'
import { mockMarket } from '../hooks/useMockBalances.ts'
import { toOrderPayload } from './orderAdapter.ts'

describe('trading order adapter', () => {
  it('maps a limit trade form to the shared backend order payload', () => {
    const form = {
      ...createInitialTradeForm('buy', mockMarket),
      orderType: 'limit' as const,
      price: '60736.3',
      amount: '0.02',
      takeProfitEnabled: true,
      takeProfitTriggerPrice: '62000',
      stopLossEnabled: true,
      stopLossTriggerPrice: '59000',
      clientOrderId: 'client_123'
    }

    assert.deepEqual(toOrderPayload('acct_1', form, mockMarket, 20), {
      accountId: 'acct_1',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      quantity: '0.02',
      price: '60736.3',
      clientOrderId: 'client_123',
      lots: '0.02',
      requestedPrice: '60736.3',
      takeProfit: '62000',
      stopLoss: '59000',
      idempotencyKey: 'client_123',
      leverage: 20
    })
  })

  it('converts market buy total into lots when amount is empty', () => {
    const form = {
      ...createInitialTradeForm('buy', mockMarket),
      orderType: 'market' as const,
      total: '607.333',
      amount: '',
      clientOrderId: 'client_456'
    }

    const payload = toOrderPayload('acct_1', form, mockMarket)

    assert.equal(payload.orderType, 'MARKET')
    assert.equal(payload.price, undefined)
    assert.equal(payload.requestedPrice, undefined)
    assert.equal(payload.quantity, '0.01')
    assert.equal(payload.lots, '0.01')
  })
})
