import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./tradingApi.ts', import.meta.url), 'utf8')

describe('trading api endpoint contracts', () => {
  it('covers user order lifecycle endpoints', () => {
    assert.match(source, /apiPost<OrderResponse>\('\/api\/trading\/orders'/)
    assert.match(source, /apiGet<OrderResponse\[\]>\('\/api\/trading\/orders'/)
    assert.match(source, /apiGet<OrderEventResponse\[\]>\(`\/api\/trading\/orders\/\$\{orderId\}\/events`/)
    assert.match(source, /apiPost<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}\/cancel`/)
    assert.match(source, /apiPatch<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}`/)
  })

  it('keeps position reads and closes on the account-scoped backend routes', () => {
    assert.match(source, /apiGet<PositionResponse\[\]>\(`\/api\/trading\/positions\?accountId=\$\{accountId\}`/)
    assert.match(source, /apiGet<PositionResponse\[\]>\(`\/api\/trading\/positions\/history\?accountId=\$\{accountId\}`/)
    assert.match(source, /apiPost<PositionResponse>\(`\/api\/trading\/positions\/\$\{positionId\}\/close\?accountId=\$\{accountId\}`/)
    assert.match(source, /apiPatch<PositionResponse>\(`\/api\/trading\/positions\/\$\{positionId\}\/protection\?accountId=\$\{accountId\}`/)
  })
})
