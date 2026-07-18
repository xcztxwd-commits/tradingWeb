import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  runWalletReset,
  runWalletTransfer,
  validateWalletTransfer
} from './accountOperations.ts'

describe('wallet operations', () => {
  it('validates positive amounts against the selected source wallet', () => {
    assert.deepEqual(validateWalletTransfer('0', '100'), { key: 'assets.transferAmountInvalid' })
    assert.deepEqual(validateWalletTransfer('101', '100'), { key: 'assets.transferBalanceInsufficient' })
    assert.equal(validateWalletTransfer('25.5', '100'), null)
  })

  it('preserves transfer direction, amount and request id, then refreshes the complete account snapshot', async () => {
    const calls: unknown[] = []
    let refreshes = 0
    const response = await runWalletTransfer(
      {
        accountId: 'account-1',
        token: 'token',
        direction: 'PERP_TO_SPOT',
        amount: '25.50',
        requestId: 'transfer-request'
      },
      {
        transferDemoFunds: async (accountId, payload, token) => {
          calls.push({ accountId, payload, token })
          return { transferId: 'transfer-1', ...payload }
        },
        refresh: async () => { refreshes += 1 }
      }
    )

    assert.deepEqual(calls, [{
      accountId: 'account-1',
      payload: { direction: 'PERP_TO_SPOT', amount: 25.5, requestId: 'transfer-request' },
      token: 'token'
    }])
    assert.equal(response.transferId, 'transfer-1')
    assert.equal(refreshes, 1)
  })

  it('resets Demo state with the supplied request id and refreshes account, wallet and ledger truth together', async () => {
    const calls: unknown[] = []
    let refreshes = 0
    await runWalletReset(
      { accountId: 'account-1', token: 'token', requestId: 'reset-request' },
      {
        resetDemoAccount: async (accountId, payload, token) => {
          calls.push({ accountId, payload, token })
          return { accountId, reset: true }
        },
        refresh: async () => { refreshes += 1 }
      }
    )

    assert.deepEqual(calls, [{
      accountId: 'account-1',
      payload: { requestId: 'reset-request' },
      token: 'token'
    }])
    assert.equal(refreshes, 1)
  })
})
