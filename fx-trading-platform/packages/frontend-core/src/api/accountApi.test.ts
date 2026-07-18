import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import * as accountApi from './accountApi.ts'

const source = readFileSync(new URL('./accountApi.ts', import.meta.url), 'utf8')
const types = readFileSync(new URL('../models/trading.ts', import.meta.url), 'utf8')

describe('account api endpoint contracts', () => {
  it('covers wallet typed asset ledger filters and asset conversions', () => {
    assert.match(types, /export type WalletType/)
    assert.match(types, /walletType:\s*WalletType \| string/)
    assert.match(types, /export type AssetConversionPayload/)
    assert.match(types, /export type AssetConversionResponse/)
    assert.match(source, /walletType\?: string/)
    assert.match(source, /convertAsset\(accountId: string, payload: AssetConversionPayload, token: string\)/)
    assert.match(source, /apiPost<AssetConversionResponse>\(`\/api\/accounts\/\$\{accountId\}\/asset-conversions`, payload, token\)/)
  })

  it('posts canonical idempotent Spot and Perp transfers and Demo resets', async () => {
    const transferDemoFunds = (accountApi as Record<string, unknown>).transferDemoFunds
    const resetDemoAccount = (accountApi as Record<string, unknown>).resetDemoAccount
    assert.equal(typeof transferDemoFunds, 'function')
    assert.equal(typeof resetDemoAccount, 'function')

    const originalFetch = globalThis.fetch
    const requests: Array<{ path: string; body: unknown }> = []
    globalThis.fetch = async (input, init) => {
      requests.push({ path: String(input), body: JSON.parse(String(init?.body)) })
      return new Response(JSON.stringify({
        success: true,
        data: requests.length === 1
          ? { direction: 'SPOT_TO_PERP', amount: 125, replayed: false }
          : { requestId: '22222222-2222-4222-8222-222222222222', replayed: false }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      await (transferDemoFunds as Function)(
        'account-1',
        {
          direction: 'SPOT_TO_PERP',
          amount: 125,
          requestId: '11111111-1111-4111-8111-111111111111'
        },
        'token-1'
      )
      await (resetDemoAccount as Function)(
        'account-1',
        { requestId: '22222222-2222-4222-8222-222222222222' },
        'token-1'
      )

      assert.deepEqual(requests, [
        {
          path: '/api/accounts/account-1/transfers',
          body: {
            direction: 'SPOT_TO_PERP',
            amount: 125,
            requestId: '11111111-1111-4111-8111-111111111111'
          }
        },
        {
          path: '/api/accounts/account-1/demo-reset',
          body: { requestId: '22222222-2222-4222-8222-222222222222' }
        }
      ])
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
