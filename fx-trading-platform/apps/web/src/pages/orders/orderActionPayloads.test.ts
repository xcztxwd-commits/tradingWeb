import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  buildNormalOrderUpdatePayload,
  buildProtectionUpdatePayload
} from './orderActionPayloads.ts'

describe('order action payloads', () => {
  it('never carries legacy stop loss or take profit fields into a normal order update', () => {
    const payload = buildNormalOrderUpdatePayload({
      quantity: '0.25',
      price: '64000',
      stopLoss: '59000',
      takeProfit: '70000'
    })

    assert.deepEqual(payload, { quantity: 0.25, price: 64000 })
    assert.equal('stopLoss' in payload, false)
    assert.equal('takeProfit' in payload, false)
  })

  it('builds the canonical protection update with the backend version', () => {
    assert.deepEqual(buildProtectionUpdatePayload({
      version: 7,
      quantityUnit: 'CONTRACTS'
    }, {
      quantity: '0.5',
      triggerPrice: '59000',
      triggerExecutionType: 'LIMIT',
      price: '58950'
    }), {
      quantity: 0.5,
      quantityUnit: 'CONTRACTS',
      triggerPrice: 59000,
      triggerExecutionType: 'LIMIT',
      price: 58950,
      expectedVersion: 7
    })
  })

  it('refuses to guess a protection version and omits limit price for market execution', () => {
    assert.equal(buildProtectionUpdatePayload({ version: undefined }, { triggerPrice: '59000' }), null)
    assert.deepEqual(buildProtectionUpdatePayload({ version: 2 }, {
      triggerPrice: '59000',
      triggerExecutionType: 'MARKET',
      price: '58950'
    }), {
      triggerPrice: 59000,
      triggerExecutionType: 'MARKET',
      expectedVersion: 2
    })
  })
})
