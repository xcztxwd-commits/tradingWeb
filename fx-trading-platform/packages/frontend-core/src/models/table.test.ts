import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { filterByStatus, paginateRows, sortRows, toNumber } from './table.ts'

const rows = [
  { id: '1', status: 'FILLED', symbol: 'EURUSD', amount: '10', createdAt: '2026-06-12T01:00:00Z' },
  { id: '2', status: 'PENDING', symbol: 'BTCUSDT', amount: '25', createdAt: '2026-06-12T03:00:00Z' },
  { id: '3', status: 'CANCELED', symbol: 'XAUUSD', amount: '5', createdAt: '2026-06-12T02:00:00Z' }
]

describe('shared table models', () => {
  it('filters status without changing ALL rows', () => {
    assert.deepEqual(filterByStatus(rows, 'ALL').map((row) => row.id), ['1', '2', '3'])
    assert.deepEqual(filterByStatus(rows, 'PENDING').map((row) => row.id), ['2'])
  })

  it('sorts string numbers and dates predictably without mutating input', () => {
    assert.deepEqual(sortRows(rows, 'amount', 'asc').map((row) => row.id), ['3', '1', '2'])
    assert.deepEqual(sortRows(rows, 'createdAt', 'desc').map((row) => row.id), ['2', '3', '1'])
    assert.deepEqual(rows.map((row) => row.id), ['1', '2', '3'])
  })

  it('paginates with stable bounds and converts finite numeric values', () => {
    assert.deepEqual(paginateRows(rows, 99, 2), {
      items: rows.slice(2), page: 2, pageSize: 2, total: 3, totalPages: 2
    })
    assert.equal(toNumber('12.5'), 12.5)
    assert.equal(toNumber(Number.POSITIVE_INFINITY), 0)
    assert.equal(toNumber(''), null)
  })
})
