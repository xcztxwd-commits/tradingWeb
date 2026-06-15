import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError, parseApiErrorPayload } from './apiClient.ts'

describe('api client error model', () => {
  it('keeps status, code, message, requestId', () => {
    const error = new ApiClientError({
      status: 409,
      code: 'ORDER_REJECTED',
      message: 'Order rejected',
      requestId: 'req-1'
    })

    assert.equal(error.status, 409)
    assert.equal(error.code, 'ORDER_REJECTED')
    assert.equal(error.message, 'Order rejected')
    assert.equal(error.requestId, 'req-1')
  })

  it('parses wrapped backend failure payloads into an API error shape', () => {
    const error = parseApiErrorPayload(
      {
        success: false,
        code: 'VALIDATION_ERROR',
        message: 'Invalid quantity',
        requestId: 'payload-req-1'
      },
      422,
      'header-req-1'
    )

    assert.deepEqual(error, {
      status: 422,
      code: 'VALIDATION_ERROR',
      message: 'Invalid quantity',
      requestId: 'header-req-1'
    })
  })
})
