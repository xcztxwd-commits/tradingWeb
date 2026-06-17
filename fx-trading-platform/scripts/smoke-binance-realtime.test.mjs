import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { runSmoke } from './smoke-binance-realtime.mjs'

describe('binance realtime smoke script', () => {
  it('checks status, test-control quote override and cleanup paths', async () => {
    const calls = []
    let quoteRequestCount = 0
    const fetchImpl = async (url, options = {}) => {
      calls.push({ url, options })
      const path = new URL(url).pathname
      if (path === '/actuator/health') {
        return response({ status: 'UP' })
      }
      if (path === '/api/auth/login') {
        return response({ success: true, data: { accessToken: 'admin-token', role: 'ADMIN' } })
      }
      if (path === '/api/admin/market/realtime/status') {
        return response({
          success: true,
          data: {
            enabled: true,
            provider: 'binance',
            connected: true,
            activeSymbols: ['BTCUSDT'],
            desiredStreamCount: 11
          }
        })
      }
      if (path === '/api/admin/market/test-control/overrides') {
        return response({ success: true, data: { symbol: 'BTCUSDT', source: 'test-control' } })
      }
      if (path === '/api/market/quotes/BTCUSDT') {
        quoteRequestCount += 1
        if (quoteRequestCount === 1) {
          return response({
            success: true,
            data: { symbol: 'BTCUSDT', source: 'binance-ws-bookTicker', bid: '99900.00', ask: '99901.00', mid: '99900.50' }
          })
        }
        return response({
          success: true,
          data: { symbol: 'BTCUSDT', source: 'test-control', bid: '100.00', ask: '102.00', mid: '101.00' }
        })
      }
      if (path === '/api/admin/market/test-control/overrides/BTCUSDT') {
        return response({ success: true, data: null })
      }
      throw new Error(`Unexpected request ${path}`)
    }

    const result = await runSmoke({ fetchImpl, env: { API_BASE_URL: 'http://127.0.0.1:18090' } })

    assert.equal(result.results.length, 6)
    assert.deepEqual(result.results.map((item) => item.status), ['PASS', 'PASS', 'PASS', 'PASS', 'PASS', 'PASS'])
    assert.deepEqual(
      calls.map((call) => `${call.options.method ?? 'GET'} ${new URL(call.url).pathname}`),
      [
        'GET /actuator/health',
        'POST /api/auth/login',
        'GET /api/admin/market/realtime/status',
        'GET /api/market/quotes/BTCUSDT',
        'POST /api/admin/market/test-control/overrides',
        'GET /api/market/quotes/BTCUSDT',
        'DELETE /api/admin/market/test-control/overrides/BTCUSDT'
      ]
    )
    assert.equal(calls[2].options.headers.Authorization, 'Bearer admin-token')
  })

  it('fails when realtime status is not connected', async () => {
    const fetchImpl = async (url) => {
      const path = new URL(url).pathname
      if (path === '/actuator/health') {
        return response({ status: 'UP' })
      }
      if (path === '/api/auth/login') {
        return response({ success: true, data: { accessToken: 'admin-token', role: 'ADMIN' } })
      }
      if (path === '/api/admin/market/realtime/status') {
        return response({
          success: true,
          data: {
            enabled: true,
            provider: 'binance',
            connected: false,
            activeSymbols: ['BTCUSDT'],
            desiredStreamCount: 11
          }
        })
      }
      throw new Error(`Unexpected request ${path}`)
    }

    await assert.rejects(
      () => runSmoke({ fetchImpl, env: { API_BASE_URL: 'http://127.0.0.1:18090' } }),
      /Realtime status must be connected/
    )
  })

  it('cleans up test-control override when quote verification fails', async () => {
    const calls = []
    const fetchImpl = async (url, options = {}) => {
      calls.push({ url, options })
      const path = new URL(url).pathname
      if (path === '/actuator/health') {
        return response({ status: 'UP' })
      }
      if (path === '/api/auth/login') {
        return response({ success: true, data: { accessToken: 'admin-token', role: 'ADMIN' } })
      }
      if (path === '/api/admin/market/realtime/status') {
        return response({
          success: true,
          data: {
            enabled: true,
            provider: 'binance',
            connected: true,
            activeSymbols: ['BTCUSDT'],
            desiredStreamCount: 11
          }
        })
      }
      if (path === '/api/market/quotes/BTCUSDT') {
        return response({
          success: true,
          data: { symbol: 'BTCUSDT', source: 'binance-ws-bookTicker', bid: '99900.00', ask: '99901.00', mid: '99900.50' }
        })
      }
      if (path === '/api/admin/market/test-control/overrides') {
        return response({ success: true, data: { symbol: 'BTCUSDT', source: 'test-control' } })
      }
      if (path === '/api/market/quotes/BTCUSDT') {
        return response({
          success: true,
          data: { symbol: 'BTCUSDT', source: 'binance-ws-bookTicker', bid: '100.00', ask: '102.00', mid: '101.00' }
        })
      }
      if (path === '/api/admin/market/test-control/overrides/BTCUSDT') {
        return response({ success: true, data: null })
      }
      throw new Error(`Unexpected request ${path}`)
    }

    await assert.rejects(
      () => runSmoke({ fetchImpl, env: { API_BASE_URL: 'http://127.0.0.1:18090' } }),
      /Quote source must be test-control/
    )

    assert.ok(
      calls.some((call) => `${call.options.method ?? 'GET'} ${new URL(call.url).pathname}` === 'DELETE /api/admin/market/test-control/overrides/BTCUSDT')
    )
  })
})

function response(payload) {
  return {
    ok: true,
    status: 200,
    url: 'http://127.0.0.1:18090/test',
    async text() {
      return JSON.stringify(payload)
    }
  }
}
