import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  PRICE_FIELD_LABELS,
  PRICE_TYPE_LABELS,
  priceFieldLabel,
} from './priceLabels.ts'

const EXPECTED_LABELS = {
  bid: 'bid（买一价）',
  ask: 'ask（卖一价）',
  last: 'last（最新价）',
  mark: 'mark（标记价）',
  index: 'index（指数价）',
  price: 'price（委托价）',
  requestedPrice: 'requestedPrice（请求价）',
  triggerPrice: 'triggerPrice（触发价）',
  activationPrice: 'activationPrice（激活价）',
  stopLoss: 'stopLoss（止损价）',
  takeProfit: 'takeProfit（止盈价）',
  fillPrice: 'fillPrice（成交价）',
  entryPrice: 'entryPrice（开仓均价）',
  markPrice: 'markPrice（标记价）',
  breakEvenPrice: 'breakEvenPrice（回本价）',
  estimatedLiquidationPrice: 'estimatedLiquidationPrice（预计强平价）',
} as const

describe('Trading Lab price labels', () => {
  it('publishes every frozen bilingual price label exactly', () => {
    assert.deepEqual(PRICE_FIELD_LABELS, EXPECTED_LABELS)
    for (const [key, label] of Object.entries(EXPECTED_LABELS)) {
      assert.equal(priceFieldLabel(key as keyof typeof EXPECTED_LABELS), label)
      assert.notEqual(label, key)
      assert.match(label, new RegExp(`^${key}（[^）]+）$`))
    }
  })

  it('routes trigger price types through the same bilingual labels', () => {
    assert.deepEqual(PRICE_TYPE_LABELS, {
      BID: EXPECTED_LABELS.bid,
      ASK: EXPECTED_LABELS.ask,
      LAST: EXPECTED_LABELS.last,
      MARK: EXPECTED_LABELS.mark,
      INDEX: EXPECTED_LABELS.index,
    })
  })
})
