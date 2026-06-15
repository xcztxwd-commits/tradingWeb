import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  getOrderActionPolicy,
  getOrderActionSummary,
  isCurrentOrderStatus
} from './orderActionPolicy.ts'

describe('user order action policy', () => {
  it('matches the backend user order lifecycle for current orders', () => {
    assert.equal(isCurrentOrderStatus('PENDING'), true)
    assert.equal(isCurrentOrderStatus('ACCEPTED'), true)
    assert.equal(isCurrentOrderStatus('WORKING'), true)
    assert.equal(isCurrentOrderStatus('PARTIALLY_FILLED'), true)
    assert.equal(isCurrentOrderStatus('FILLED'), false)
    assert.equal(isCurrentOrderStatus('CANCELED'), false)
  })

  it('only enables user cancel and modify for PENDING orders', () => {
    assert.deepEqual(getOrderActionPolicy({ status: 'PENDING' }), {
      canCancel: true,
      canModify: true
    })

    for (const status of ['ACCEPTED', 'WORKING', 'PARTIALLY_FILLED']) {
      const policy = getOrderActionPolicy({ status })
      assert.equal(policy.canCancel, false)
      assert.equal(policy.canModify, false)
      assert.equal(policy.cancelReasonKey, `orders.actionReasons.${toReasonCase(status)}`)
      assert.equal(policy.modifyReasonKey, `orders.actionReasons.${toReasonCase(status)}`)
    }
  })

  it('returns readable action summaries for current order tables', () => {
    assert.equal(getOrderActionSummary({ status: 'PENDING' }), 'orders.actionSummary.available')
    assert.equal(getOrderActionSummary({ status: 'PARTIALLY_FILLED' }), 'orders.actionReasons.partiallyFilled')
  })
})

function toReasonCase(status: string) {
  if (status === 'PARTIALLY_FILLED') return 'partiallyFilled'
  return status.toLowerCase()
}
