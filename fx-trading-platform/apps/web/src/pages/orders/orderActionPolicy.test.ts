import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  getOrderActionPolicy,
  getOrderActionSummary,
  isCurrentOrderStatus
} from './orderActionPolicy.ts'

describe('user order action policy', () => {
  it('matches the backend user order lifecycle for current orders', () => {
    for (const status of [
      'RECEIVED',
      'VALIDATING',
      'PENDING',
      'PENDING_ACTIVATION',
      'ACCEPTED',
      'WORKING',
      'PARTIALLY_FILLED',
      'CANCEL_PENDING'
    ]) {
      assert.equal(isCurrentOrderStatus(status), true, `${status} should remain visible as current`)
    }
    assert.equal(isCurrentOrderStatus('FILLED'), false)
    assert.equal(isCurrentOrderStatus('CANCELED'), false)
  })

  it('allows normal pending Spot LIMIT orders to cancel and modify through order APIs', () => {
    const policy = getOrderActionPolicy({
      status: 'PENDING',
      productType: 'CRYPTO_SPOT',
      orderType: 'LIMIT',
      origin: 'USER'
    })

    assert.equal(policy.canCancel, true)
    assert.equal(policy.canModify, true)
    assert.equal(policy.cancelVia, 'ORDER')
    assert.equal(policy.modifyVia, 'ORDER')
  })

  it('allows normal pending Linear Perp cancellation but never normal modification', () => {
    const policy = getOrderActionPolicy({
      status: 'PENDING',
      productType: 'LINEAR_PERP',
      orderType: 'LIMIT',
      origin: 'USER'
    })

    assert.equal(policy.canCancel, true)
    assert.equal(policy.canModify, false)
    assert.equal(policy.cancelVia, 'ORDER')
    assert.equal(policy.modifyVia, null)
  })

  it('allows OCO cancellation but prevents independent leg modification', () => {
    const policy = getOrderActionPolicy({
      status: 'PENDING',
      productType: 'CRYPTO_SPOT',
      orderType: 'LIMIT',
      orderOrigin: 'OCO',
      contingencyGroupId: 'oco-group-1'
    })

    assert.equal(policy.canCancel, true)
    assert.equal(policy.canModify, false)
    assert.equal(policy.cancelVia, 'ORDER')
    assert.equal(policy.modifyVia, null)
  })

  it('routes active protective orders through protection APIs only with a real version', () => {
    const policy = getOrderActionPolicy({
      status: 'PENDING_ACTIVATION',
      productType: 'LINEAR_PERP',
      orderType: 'STOP_MARKET',
      origin: 'PROTECTIVE',
      protectionType: 'STOP_LOSS',
      parentPositionId: 'position-1',
      version: 7
    })

    assert.equal(policy.canCancel, true)
    assert.equal(policy.canModify, true)
    assert.equal(policy.cancelVia, 'PROTECTION')
    assert.equal(policy.modifyVia, 'PROTECTION')

    const missingVersion = getOrderActionPolicy({
      status: 'PENDING_ACTIVATION',
      origin: 'PROTECTIVE',
      protectionType: 'STOP_LOSS',
      parentPositionId: 'position-1'
    })
    assert.equal(missingVersion.canCancel, true)
    assert.equal(missingVersion.canModify, false)
    assert.equal(missingVersion.modifyVia, null)
  })

  it('allows a triggered resting LIMIT protection to cancel but not modify', () => {
    const policy = getOrderActionPolicy({
      status: 'PENDING',
      productType: 'LINEAR_PERP',
      orderType: 'LIMIT',
      origin: 'PROTECTIVE',
      protectionType: 'TAKE_PROFIT',
      parentPositionId: 'position-1',
      triggerExecutionType: 'LIMIT',
      version: 8
    })

    assert.equal(policy.canCancel, true)
    assert.equal(policy.canModify, false)
    assert.equal(policy.cancelVia, 'PROTECTION')
    assert.equal(policy.modifyVia, null)
  })

  it('keeps pre-acceptance and cancellation-pending rows visible without invalid actions', () => {
    for (const status of ['RECEIVED', 'VALIDATING', 'CANCEL_PENDING']) {
      const policy = getOrderActionPolicy({ status })
      assert.equal(policy.canCancel, false)
      assert.equal(policy.canModify, false)
      assert.equal(policy.cancelVia, null)
      assert.equal(policy.modifyVia, null)
    }
  })

  it('returns readable action summaries for current order tables', () => {
    assert.equal(getOrderActionSummary({
      status: 'PENDING', productType: 'CRYPTO_SPOT', orderType: 'LIMIT', origin: 'USER'
    }), 'orders.actionSummary.available')
    assert.equal(getOrderActionSummary({ status: 'PARTIALLY_FILLED' }), 'orders.actionReasons.partiallyFilled')
  })
})
