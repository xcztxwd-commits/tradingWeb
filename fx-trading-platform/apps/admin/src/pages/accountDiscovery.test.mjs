import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const helperUrl = new URL('./accountDiscovery.ts', import.meta.url)

async function loadHelper() {
  assert.equal(existsSync(fileURLToPath(helperUrl)), true, 'account discovery helper must exist')
  return import(helperUrl.href)
}

const blankFilters = {
  accountId: '',
  userId: '',
  accountType: '',
  status: ''
}

function account(index, overrides = {}) {
  return {
    id: `account-${String(index).padStart(3, '0')}`,
    userId: `user-${index}`,
    accountType: 'DEMO',
    balance: 0,
    equity: 0,
    usedMargin: 0,
    freeMargin: 0,
    status: 'ACTIVE',
    ...overrides
  }
}

describe('admin account discovery', () => {
  it('keeps a result on the 51st loaded backend row reachable', async () => {
    const { filterAccounts } = await loadHelper()
    const allLoadedAccounts = Array.from({ length: 51 }, (_, index) => account(index + 1))

    const result = filterAccounts(allLoadedAccounts, { ...blankFilters, accountId: 'ACCOUNT-051' })

    assert.deepEqual(result.map((item) => item.id), ['account-051'])
  })

  it('composes case-insensitive account, user, type, and status filters', async () => {
    const { filterAccounts } = await loadHelper()
    const accounts = [
      account(1, { id: 'Demo-Alpha', userId: 'User-One', accountType: 'DEMO', status: 'ACTIVE' }),
      account(2, { id: 'Demo-Beta', userId: 'User-One', accountType: 'DEMO', status: 'DISABLED' }),
      account(3, { id: 'Live-Alpha', userId: 'User-Two', accountType: 'LIVE', status: 'ACTIVE' })
    ]

    const result = filterAccounts(accounts, {
      accountId: 'demo',
      userId: 'USER-one',
      accountType: 'dEmO',
      status: 'aCtIvE'
    })

    assert.deepEqual(result.map((item) => item.id), ['Demo-Alpha'])
  })

  it('returns every loaded account for blank filters', async () => {
    const { filterAccounts } = await loadHelper()
    const accounts = [account(1), account(2), account(3)]

    assert.deepEqual(filterAccounts(accounts, { ...blankFilters, accountId: ' ', status: '\t' }), accounts)
  })
})
