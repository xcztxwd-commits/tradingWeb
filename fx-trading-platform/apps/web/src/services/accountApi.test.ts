import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./accountApi.ts', import.meta.url), 'utf8')
const types = readFileSync(new URL('../types/trading.ts', import.meta.url), 'utf8')

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
})
