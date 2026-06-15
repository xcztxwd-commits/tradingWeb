import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { buildOrderPayload, createInitialTradeForm } from '../hooks/useTradeForm.ts'
import { mockMarket } from '../hooks/useMockBalances.ts'
import { submitOrder } from './orderApi.ts'

describe('mock order api', () => {
  it('submits a mock order and returns the original payload', async () => {
    const payload = buildOrderPayload(
      {
        ...createInitialTradeForm('buy', mockMarket),
        price: '60736.3',
        amount: '0.01',
        total: '607.363'
      },
      mockMarket
    )

    const response = await submitOrder(payload)

    assert.equal(response.success, true)
    assert.match(response.orderId, /^mock_/)
    assert.equal(response.status, 'submitted')
    assert.equal(response.payload, payload)
  })
})
