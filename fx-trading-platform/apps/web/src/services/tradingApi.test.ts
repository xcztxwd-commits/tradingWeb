import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import {
  getAccountTransfers,
  getFundingSettlements,
  getOrders,
  getPositionHistory,
  getPositions,
  getTrades
} from './tradingApi.ts'

const source = readFileSync(new URL('./tradingApi.ts', import.meta.url), 'utf8')

describe('trading api endpoint contracts', () => {
  it('covers user order lifecycle endpoints', () => {
    assert.match(source, /apiPost<OrderResponse>\('\/api\/trading\/orders'/)
    assert.match(source, /apiGet<OrderPage>\(`\/api\/trading\/orders\?accountId=\$\{encodeURIComponent\(accountId\)\}`/)
    assert.match(source, /apiGet<OrderEventResponse\[\]>\(`\/api\/trading\/orders\/\$\{orderId\}\/events`/)
    assert.match(source, /apiPost<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}\/cancel`/)
    assert.match(source, /apiPatch<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}`/)
  })

  it('keeps position reads and closes on the account-scoped backend routes', () => {
    assert.match(source, /apiGet<PositionPage>\(`\/api\/trading\/positions\?accountId=\$\{encodeURIComponent\(accountId\)\}`/)
    assert.match(source, /apiGet<PositionPage>\(`\/api\/trading\/positions\/history\?accountId=\$\{encodeURIComponent\(accountId\)\}`/)
    assert.match(source, /apiPost<PositionResponse>\(\s*`\/api\/trading\/positions\/\$\{positionId\}\/close\?accountId=\$\{encodeURIComponent\(accountId\)\}`/)
    assert.match(source, /apiPatch<PositionResponse>\(\s*`\/api\/trading\/positions\/\$\{positionId\}\/protection\?accountId=\$\{encodeURIComponent\(accountId\)\}`/)
  })

  it('uses the canonical partial-close, isolated-margin, and protection contracts', () => {
    assert.match(source, /payload: ClosePositionRequest \| undefined/)
    assert.match(source, /close\?accountId=\$\{encodeURIComponent\(accountId\)\}`,[\s\r\n]+payload,[\s\r\n]+token/)
    assert.match(source, /apiPost<AdjustPositionMarginResponse>\(`\/api\/trading\/positions\/\$\{positionId\}\/margin`, payload, token\)/)
    assert.match(source, /apiPost<OrderResponse>\(`\/api\/trading\/positions\/\$\{positionId\}\/protections`, payload, token\)/)
  })

  it('uses the generated trading settings GET and PATCH routes', () => {
    assert.match(source, /apiGet<TradingSettingsResponse>\(`\/api\/accounts\/\$\{accountId\}\/trading-settings`, token\)/)
    assert.match(source, /apiPatch<TradingSettingsResponse>\(`\/api\/accounts\/\$\{accountId\}\/position-mode`, \{ positionMode \}, token\)/)
    assert.match(source, /apiPatch<TradingSettingsResponse>\(\s*`\/api\/accounts\/\$\{accountId\}\/symbols\/\$\{encodeURIComponent\(symbol\)\}\/settings`/)
  })

  it('submits the canonical Spot OCO request on the dedicated endpoint', () => {
    assert.match(source, /apiPost<OcoOrderGroupResponse>\('\/api\/trading\/oco', payload, token\)/)
  })

  it('unwraps canonical paging envelopes for the existing trading session', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    globalThis.fetch = async (input) => {
      requestedPaths.push(String(input))
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: { items: [{ id: `item_${requestedPaths.length}` }], page: 0, size: 20, total: 1, totalPages: 1 }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      assert.deepEqual(await getOrders('acct_1', 'token_1'), [{ id: 'item_1' }])
      assert.deepEqual(await getPositions('acct_1', 'token_1'), [{ id: 'item_2' }])
      assert.deepEqual(await getPositionHistory('acct_1', 'token_1'), [{ id: 'item_3' }])
      assert.deepEqual(requestedPaths, [
        '/api/trading/orders?accountId=acct_1',
        '/api/trading/positions?accountId=acct_1',
        '/api/trading/positions/history?accountId=acct_1'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('loads trade, funding settlement and account transfer history from backend paging routes', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    globalThis.fetch = async (input) => {
      requestedPaths.push(String(input))
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: { items: [{ id: `history_${requestedPaths.length}` }], page: 0, size: 20, total: 1, totalPages: 1 }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      assert.deepEqual(await getTrades('acct_1', 'token_1'), [{ id: 'history_1' }])
      assert.deepEqual(await getFundingSettlements('acct_1', 'token_1'), [{ id: 'history_2' }])
      assert.deepEqual(await getAccountTransfers('acct_1', 'token_1'), [{ id: 'history_3' }])
      assert.deepEqual(requestedPaths, [
        '/api/trading/trades?accountId=acct_1',
        '/api/trading/funding/settlements?accountId=acct_1',
        '/api/accounts/acct_1/transfers'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
