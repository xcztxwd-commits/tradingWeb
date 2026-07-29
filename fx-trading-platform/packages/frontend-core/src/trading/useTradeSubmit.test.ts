import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '../api/apiClient.ts'
import { createOrderSubmitFailureMessage, createOrderSubmitSuccessMessage } from './useTradeSubmit.ts'

describe('trade submit messages', () => {
  it('keeps the fallback submitted status translatable while preserving backend statuses', () => {
    assert.deepEqual(createOrderSubmitSuccessMessage(), {
      key: 'trading.backendOrderSuccess',
      values: { statusKey: 'trading.submitted' }
    })
    assert.deepEqual(createOrderSubmitSuccessMessage('ACCEPTED'), {
      key: 'trading.backendOrderSuccess',
      values: { status: 'ACCEPTED' }
    })
  })

  it('preserves API code, status and request id behind the user-visible failure key', () => {
    const message = createOrderSubmitFailureMessage(new ApiClientError({
      status: 422,
      code: 'ORDER_REJECTED',
      message: 'Order rejected',
      requestId: 'request-42'
    }))

    assert.equal(message.key, 'trading.backendOrderFailed')
    assert.deepEqual(message.values, {
      error: 'Order rejected (ORDER_REJECTED, HTTP 422, request request-42)',
      message: 'Order rejected',
      code: 'ORDER_REJECTED',
      status: 422,
      requestId: 'request-42'
    })
  })

  it('keeps non-API failures readable without inventing backend metadata', () => {
    assert.deepEqual(createOrderSubmitFailureMessage(new Error('Network unavailable')), {
      key: 'trading.backendOrderFailed',
      values: { error: 'Network unavailable', message: 'Network unavailable' }
    })
  })
})
