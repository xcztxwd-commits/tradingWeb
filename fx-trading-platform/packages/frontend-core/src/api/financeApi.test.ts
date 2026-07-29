import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./financeApi.ts', import.meta.url), 'utf8')

describe('finance api endpoint contracts', () => {
  it('covers user fund order list and create endpoints', () => {
    assert.match(source, /fundOrders:\s*\(accountId: string\) => `\/api\/finance\/fund-orders\?\$\{new URLSearchParams\(\{ accountId \}\)\.toString\(\)\}`/)
    assert.match(source, /createFundOrder:\s*'\/api\/finance\/fund-orders'/)
    assert.match(source, /apiGet<FundOrder\[\]>\(financeEndpoints\.fundOrders\(accountId\), token\)/)
    assert.match(source, /apiPost<FundOrder>\(financeEndpoints\.createFundOrder, payload, token\)/)
  })
})
