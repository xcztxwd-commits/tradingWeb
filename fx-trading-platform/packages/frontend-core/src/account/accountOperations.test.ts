import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '../api/apiClient.ts'
import {
  createAccountDataController,
  firstOrCreatedAccount,
  loadAccountData
} from './accountOperations.ts'

describe('account operations', () => {
  it('enters login-required without loading or creating an account for a guest', async () => {
    let cleared = 0
    let accountReads = 0
    const controller = createAccountDataController({
      readStoredAuthToken: () => null,
      clearStoredAuthToken: () => { cleared += 1 },
      getSessionStatus: async () => ({ status: 'guest', authenticated: false }),
      getAccounts: async () => { accountReads += 1; return [] },
      createDemoAccount: async () => accountFixture('created'),
      loadAccountData: async () => accountDataFixture('unexpected'),
      subscribeAccountEvents: () => () => undefined
    })

    await controller.start()

    assert.deepEqual(controller.getSnapshot().state, { status: 'login-required' })
    assert.equal(controller.getSnapshot().token, null)
    assert.equal(cleared, 1)
    assert.equal(accountReads, 0)
    controller.dispose()
  })

  it('reuses a case-insensitive active Demo account and creates one only when absent', async () => {
    let creates = 0
    const active = accountFixture('active', { accountType: 'demo', status: 'active' })
    const archived = accountFixture('archived', { accountType: 'DEMO', status: 'ARCHIVED' })
    const dependencies = {
      getAccounts: async () => [archived, active],
      createDemoAccount: async () => { creates += 1; return accountFixture('created') }
    }

    assert.equal((await firstOrCreatedAccount('token', dependencies)).id, 'active')
    assert.equal(creates, 0)

    dependencies.getAccounts = async () => [archived]
    assert.equal((await firstOrCreatedAccount('token', dependencies)).id, 'created')
    assert.equal(creates, 1)
  })

  it('loads account summary, balances, ledgers and trading history as one snapshot', async () => {
    const calls: string[] = []
    const pending = Array.from({ length: 10 }, () => deferred<unknown>())
    const names = [
      'account', 'orders', 'trades', 'positions', 'positionHistory', 'funding',
      'transfers', 'ledger', 'assetLedger', 'walletBalances'
    ]
    const api = Object.fromEntries(names.map((name, index) => [name, () => {
      calls.push(name)
      return pending[index].promise
    }])) as Record<string, () => Promise<unknown>>

    const resultPromise = loadAccountData('token', 'account-1', {
      getAccountSummary: api.account,
      getOrders: api.orders,
      getTrades: api.trades,
      getPositions: api.positions,
      getPositionHistory: api.positionHistory,
      getFundingSettlements: api.funding,
      getAccountTransfers: api.transfers,
      getLedgerEntries: api.ledger,
      getAssetLedger: api.assetLedger,
      getWalletBalances: api.walletBalances
    })

    assert.deepEqual(calls, names)
    pending[0].resolve(accountFixture('account-1'))
    for (let index = 1; index < pending.length; index += 1) pending[index].resolve([])

    const result = await resultPromise
    assert.equal(result.account.id, 'account-1')
    assert.deepEqual(result.walletBalances, [])
    assert.deepEqual(result.assetLedgerEntries, [])
  })

  it('coalesces concurrent refreshes and never applies an obsolete response', async () => {
    const stale = deferred<ReturnType<typeof accountDataFixture>>()
    const latest = deferred<ReturnType<typeof accountDataFixture>>()
    let loads = 0
    const controller = createAccountDataController(authenticatedDependencies(async () => {
      loads += 1
      if (loads === 1) return accountDataFixture('initial')
      if (loads === 2) return stale.promise
      return latest.promise
    }))

    await controller.start()
    assert.equal(controller.getSnapshot().data?.account.id, 'initial')

    const first = controller.refresh()
    const replaced = controller.refresh()
    const last = controller.refresh()
    stale.resolve(accountDataFixture('obsolete'))
    await settle()

    assert.equal(loads, 3)
    assert.equal(controller.getSnapshot().data?.account.id, 'initial')

    latest.resolve(accountDataFixture('authoritative'))
    await Promise.all([first, replaced, last])
    assert.equal(controller.getSnapshot().data?.account.id, 'authoritative')
    controller.dispose()
  })

  it('preserves the last complete snapshot and ApiClientError details on a partial API failure', async () => {
    const failure = new ApiClientError({
      status: 503,
      code: 'WALLET_BALANCE_UNAVAILABLE',
      message: 'wallet balance unavailable',
      requestId: 'request-7'
    })
    let loads = 0
    const controller = createAccountDataController(authenticatedDependencies(async () => {
      loads += 1
      if (loads === 1) return accountDataFixture('stable')
      throw failure
    }))

    await controller.start()
    await assert.rejects(controller.refresh(), (error) => error === failure)

    const snapshot = controller.getSnapshot()
    assert.equal(snapshot.data?.account.id, 'stable')
    assert.equal(snapshot.state.status, 'error')
    if (snapshot.state.status !== 'error') assert.fail('expected error state')
    assert.equal(snapshot.state.error, failure)
    assert.equal(snapshot.state.message.key, 'account.dataUnavailable')
    assert.equal(snapshot.state.message.values?.requestId, 'request-7')
    controller.dispose()
  })
})

function authenticatedDependencies(loadAccountData: () => Promise<ReturnType<typeof accountDataFixture>>) {
  return {
    readStoredAuthToken: () => 'token',
    clearStoredAuthToken: () => undefined,
    getSessionStatus: async () => ({ status: 'valid_token', authenticated: true }),
    getAccounts: async () => [accountFixture('account-1')],
    createDemoAccount: async () => accountFixture('created'),
    loadAccountData,
    subscribeAccountEvents: () => () => undefined
  }
}

function accountDataFixture(id: string) {
  return {
    account: accountFixture(id),
    orders: [],
    trades: [],
    positions: [],
    positionHistory: [],
    fundingSettlements: [],
    transfers: [],
    ledgerEntries: [],
    assetLedgerEntries: [],
    walletBalances: []
  }
}

function accountFixture(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    accountType: 'DEMO',
    status: 'ACTIVE',
    baseCurrency: 'USDT',
    balance: '1000',
    equity: '1000',
    freeMargin: '1000',
    usedMargin: '0',
    marginLevel: '0',
    ...overrides
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
}
