import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import {
  cancelAllOrders,
  cancelProtection,
  closeAllPositions,
  getAccountTransfers,
  getFundingSettlements,
  getOrders,
  getPositionHistory,
  getPositions,
  getTrades,
  modifyOrder,
  updateProtection
} from './tradingApi.ts'

const source = readFileSync(new URL('./tradingApi.ts', import.meta.url), 'utf8')

describe('trading api endpoint contracts', () => {
  it('covers user order lifecycle endpoints', () => {
    assert.match(source, /apiPost<OrderResponse>\('\/api\/trading\/orders'/)
    assert.match(source, /apiGet<OrderPage>\(`\/api\/trading\/orders\?accountId=\$\{encodeURIComponent\(accountId\)\}&page=\$\{page\}&size=\$\{accountHistoryPageSize\}`/)
    assert.match(source, /apiGet<OrderEventResponse\[\]>\(`\/api\/trading\/orders\/\$\{orderId\}\/events`/)
    assert.match(source, /apiPost<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}\/cancel`/)
    assert.match(source, /apiPatch<OrderResponse>\(`\/api\/trading\/orders\/\$\{orderId\}`/)
  })

  it('keeps position reads and closes on the account-scoped backend routes', () => {
    assert.match(source, /`\/api\/trading\/positions\?accountId=\$\{encodeURIComponent\(accountId\)\}&page=\$\{page\}&size=\$\{accountHistoryPageSize\}`/)
    assert.match(source, /`\/api\/trading\/positions\/history\?accountId=\$\{encodeURIComponent\(accountId\)\}&page=\$\{page\}&size=\$\{accountHistoryPageSize\}`/)
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

  it('posts canonical idempotent account batch actions exactly once', async () => {
    const originalFetch = globalThis.fetch
    const calls: Array<{ path: string; method?: string; body?: string | null; authorization: string | null }> = []
    globalThis.fetch = async (input, init) => {
      calls.push({
        path: String(input),
        method: init?.method,
        body: typeof init?.body === 'string' ? init.body : null,
        authorization: new Headers(init?.headers).get('Authorization')
      })
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: { accountId: 'acct_1', requestId: `request_${calls.length}`, items: [] }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      assert.equal((await cancelAllOrders({
        accountId: 'acct_1',
        requestId: 'cancel_request',
        expectedOrderIds: ['order_1']
      }, 'token_1')).requestId, 'request_1')
      assert.equal((await closeAllPositions({ accountId: 'acct_1', requestId: 'close_request' }, 'token_1')).requestId, 'request_2')
      assert.deepEqual(calls, [
        {
          path: '/api/trading/orders/cancel-all',
          method: 'POST',
          body: '{"accountId":"acct_1","requestId":"cancel_request","expectedOrderIds":["order_1"]}',
          authorization: 'Bearer token_1'
        },
        {
          path: '/api/trading/positions/close-all',
          method: 'POST',
          body: '{"accountId":"acct_1","requestId":"close_request"}',
          authorization: 'Bearer token_1'
        }
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('uses canonical normal and protective order mutation routes', async () => {
    const originalFetch = globalThis.fetch
    const calls: Array<{ path: string; method?: string; body?: string | null; authorization: string | null }> = []
    globalThis.fetch = async (input, init) => {
      calls.push({
        path: String(input),
        method: init?.method,
        body: typeof init?.body === 'string' ? init.body : null,
        authorization: new Headers(init?.headers).get('Authorization')
      })
      return new Response(JSON.stringify({
        success: true, code: 'OK', message: 'ok', data: { id: 'order-1' }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      await modifyOrder('normal-1', { quantity: 0.25, price: 64000 }, 'token_1')
      await updateProtection('protection-1', { triggerPrice: 59000, expectedVersion: 7 }, 'token_1')
      await cancelProtection('protection-1', 'token_1')

      assert.deepEqual(calls, [
        {
          path: '/api/trading/orders/normal-1', method: 'PATCH',
          body: '{"quantity":0.25,"price":64000}', authorization: 'Bearer token_1'
        },
        {
          path: '/api/trading/protections/protection-1', method: 'PATCH',
          body: '{"triggerPrice":59000,"expectedVersion":7}', authorization: 'Bearer token_1'
        },
        {
          path: '/api/trading/protections/protection-1', method: 'DELETE',
          body: null, authorization: 'Bearer token_1'
        }
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
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
        '/api/trading/orders?accountId=acct_1&page=0&size=100',
        '/api/trading/positions?accountId=acct_1&page=0&size=100',
        '/api/trading/positions/history?accountId=acct_1&page=0&size=100'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('loads every order page in newest-first page order so older pending orders remain visible', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    globalThis.fetch = async (input) => {
      const path = String(input)
      requestedPaths.push(path)
      const secondPage = path.includes('page=1')
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: {
          items: secondPage
            ? [{ id: 'old_pending', status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' }]
            : [{ id: 'new_filled', status: 'FILLED', createdAt: '2026-07-01T00:00:00Z' }],
          page: secondPage ? 1 : 0,
          size: 100,
          total: 101,
          totalPages: 2
        }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      const orders = await getOrders('acct_1', 'token_1')

      assert.deepEqual(orders.map((order) => order.id), ['new_filled', 'old_pending'])
      assert.deepEqual(requestedPaths, [
        '/api/trading/orders?accountId=acct_1&page=0&size=100',
        '/api/trading/orders?accountId=acct_1&page=1&size=100'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('loads every page for positions, trades, funding settlements and transfers', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    globalThis.fetch = async (input) => {
      const path = String(input)
      requestedPaths.push(path)
      const page = path.includes('page=1') ? 1 : 0
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: { items: [{ id: `item_${page}` }], page, size: 100, total: 101, totalPages: 2 }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      const readers = [getPositions, getPositionHistory, getTrades, getFundingSettlements, getAccountTransfers]
      for (const reader of readers) {
        assert.deepEqual((await reader('acct_1', 'token_1')).map((item) => item.id), ['item_0', 'item_1'])
      }
      assert.deepEqual(requestedPaths, [
        '/api/trading/positions?accountId=acct_1&page=0&size=100',
        '/api/trading/positions?accountId=acct_1&page=1&size=100',
        '/api/trading/positions/history?accountId=acct_1&page=0&size=100',
        '/api/trading/positions/history?accountId=acct_1&page=1&size=100',
        '/api/trading/trades?accountId=acct_1&page=0&size=100',
        '/api/trading/trades?accountId=acct_1&page=1&size=100',
        '/api/trading/funding/settlements?accountId=acct_1&page=0&size=100',
        '/api/trading/funding/settlements?accountId=acct_1&page=1&size=100',
        '/api/accounts/acct_1/transfers?page=0&size=100',
        '/api/accounts/acct_1/transfers?page=1&size=100'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('stops after the first order page when paging metadata is malformed', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    globalThis.fetch = async (input) => {
      requestedPaths.push(String(input))
      return new Response(JSON.stringify({
        success: true,
        code: 'OK',
        message: 'ok',
        data: {
          items: [{ id: 'item_1' }],
          page: 0,
          size: 100,
          total: 1,
          totalPages: Number.MAX_SAFE_INTEGER
        }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      assert.deepEqual(await getOrders('acct_1', 'token_1'), [{ id: 'item_1' }])
      assert.deepEqual(requestedPaths, ['/api/trading/orders?accountId=acct_1&page=0&size=100'])
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
        '/api/trading/trades?accountId=acct_1&page=0&size=100',
        '/api/trading/funding/settlements?accountId=acct_1&page=0&size=100',
        '/api/accounts/acct_1/transfers?page=0&size=100'
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
