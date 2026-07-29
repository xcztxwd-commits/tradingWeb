import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '@fx-platform/frontend-core'
import { formatApiError } from './userPageModels.ts'

describe('user page shared models', () => {
  it('formats API errors with code, readable reason and request id', () => {
    const formatted = formatApiError(new ApiClientError({
      status: 400,
      code: 'ORDER_NOT_CANCELABLE',
      message: 'Only pending orders can be canceled',
      requestId: 'req-123'
    }))

    assert.equal(formatted.title, 'ORDER_NOT_CANCELABLE')
    assert.equal(formatted.message, 'Only pending orders can be canceled')
    assert.equal(formatted.requestId, 'req-123')
  })
})
