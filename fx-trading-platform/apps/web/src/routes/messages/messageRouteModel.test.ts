import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  actionPending,
  createMessagePageRequest,
  messagePageNumber,
  parseMessageRouteSearch
} from './messageRouteModel.ts'

describe('message route model', () => {
  it('maps canonical URL state to the server paging contract', () => {
    assert.deepEqual(parseMessageRouteSearch(new URLSearchParams()), { filter: 'ALL', page: 0 })
    assert.deepEqual(
      parseMessageRouteSearch(new URLSearchParams('filter=UNREAD&page=3')),
      { filter: 'UNREAD', page: 2 }
    )
    assert.deepEqual(createMessagePageRequest('UNREAD', 2), {
      filter: 'UNREAD', page: 2, size: 10
    })
  })

  it('fails invalid query values closed to the first ALL page', () => {
    for (const search of ['filter=read&page=2', 'filter=UNREAD&page=0', 'filter=ALL&page=nope']) {
      assert.deepEqual(parseMessageRouteSearch(new URLSearchParams(search)), {
        filter: search.startsWith('filter=UNREAD') ? 'UNREAD' : 'ALL',
        page: 0
      })
    }
  })

  it('uses human page numbers in the URL and zero-based page numbers at the API', () => {
    assert.equal(messagePageNumber(0), 1)
    assert.equal(messagePageNumber(8), 9)
  })

  it('matches pending mutation keys without disabling unrelated message rows', () => {
    const pending = ['read:publication-a', 'hide:publication-b', 'read-all']
    assert.equal(actionPending(pending, 'read', 'publication-a'), true)
    assert.equal(actionPending(pending, 'unread', 'publication-a'), false)
    assert.equal(actionPending(pending, 'hide', 'publication-b'), true)
    assert.equal(actionPending(pending, 'read-all'), true)
  })
})
