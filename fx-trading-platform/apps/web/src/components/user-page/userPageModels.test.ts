import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ApiClientError } from '../../services/apiClient.ts'
import { filterByStatus, formatApiError, paginateRows, sortRows } from './userPageModels.ts'

describe('user page shared models', () => {
  const rows = [
    { id: '1', status: 'FILLED', symbol: 'EURUSD', amount: '10', createdAt: '2026-06-12T01:00:00Z' },
    { id: '2', status: 'PENDING', symbol: 'BTCUSDT', amount: '25', createdAt: '2026-06-12T03:00:00Z' },
    { id: '3', status: 'CANCELED', symbol: 'XAUUSD', amount: '5', createdAt: '2026-06-12T02:00:00Z' }
  ]

  it('filters ALL as pass-through and exact statuses otherwise', () => {
    assert.deepEqual(filterByStatus(rows, 'ALL').map((row) => row.id), ['1', '2', '3'])
    assert.deepEqual(filterByStatus(rows, 'PENDING').map((row) => row.id), ['2'])
  })

  it('sorts string numbers and dates predictably without mutating the source rows', () => {
    const sortedByAmount = sortRows(rows, 'amount', 'asc')
    assert.deepEqual(sortedByAmount.map((row) => row.id), ['3', '1', '2'])
    assert.deepEqual(rows.map((row) => row.id), ['1', '2', '3'])

    const sortedByTime = sortRows(rows, 'createdAt', 'desc')
    assert.deepEqual(sortedByTime.map((row) => row.id), ['2', '3', '1'])
  })

  it('paginates with stable bounds and total page metadata', () => {
    assert.deepEqual(paginateRows(rows, 1, 2), {
      items: rows.slice(0, 2),
      page: 1,
      pageSize: 2,
      total: 3,
      totalPages: 2
    })
    assert.deepEqual(paginateRows(rows, 99, 2).items.map((row) => row.id), ['3'])
  })

  it('formats API errors with code, readable reason and request id', () => {
    const formatted = formatApiError(new ApiClientError({
      status: 400,
      code: 'ORDER_NOT_CANCELABLE',
      message: 'Only pending orders can be canceled',
      requestId: 'req-123'
    }))

    assert.equal(formatted.title, 'ORDER_NOT_CANCELABLE')
    assert.equal(formatted.message, 'Only pending orders can be canceled')
    assert.equal(formatted.requestId, 'req-123')
  })
})
