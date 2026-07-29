import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createMessageCenterController } from './messageCenterController.ts'

describe('message center controller', () => {
  it('loads authoritative unread and recent-message summaries for the active token', async () => {
    const api = new FakeMessageApi()
    api.countResults.push({ count: 2 })
    api.listResults.push(page([message('one', true), message('two', false)], 0, 5, 2))
    const controller = createMessageCenterController({ api })

    await controller.start('token-a')

    assert.equal(controller.getSnapshot().unreadCount, 2)
    assert.deepEqual(controller.getSnapshot().recent.map((item) => item.publicationId), [uuid('one'), uuid('two')])
    assert.deepEqual(api.countCalls, [{ accessToken: 'token-a' }])
    assert.deepEqual(api.listCalls, [{ accessToken: 'token-a', page: 0, size: 5, unreadOnly: false }])
  })

  it('loads server-paged ALL and UNREAD views without client-side pagination guesses', async () => {
    const api = new FakeMessageApi()
    const controller = createMessageCenterController({ api })
    await startIdle(controller, api)
    api.listResults.push(page([message('one', false)], 2, 10, 31))

    await controller.loadPage({ filter: 'ALL', page: 2, size: 10 })

    assert.equal(controller.getSnapshot().page?.page, 2)
    assert.equal(controller.getSnapshot().page?.total, 31)
    assert.deepEqual(api.listCalls.at(-1), {
      accessToken: 'token-a', page: 2, size: 10, unreadOnly: false
    })

    api.listResults.push(page([message('two', true)], 0, 10, 1))
    await controller.loadPage({ filter: 'UNREAD', page: 0, size: 10 })
    assert.equal(controller.getSnapshot().filter, 'UNREAD')
    assert.equal(api.listCalls.at(-1)?.unreadOnly, true)
  })

  it('refetches authoritative summary and page after read, unread and read-all mutations', async () => {
    const api = new FakeMessageApi()
    const controller = createMessageCenterController({ api })
    await startIdle(controller, api)
    api.listResults.push(page([message('one', true)], 0, 10, 1))
    await controller.loadPage({ filter: 'ALL', page: 0, size: 10 })

    seedRefresh(api, 0, [message('one', false)], [message('one', false)])
    await controller.markRead(uuid('one'))
    assert.deepEqual(api.readCalls, [{ accessToken: 'token-a', publicationId: uuid('one') }])
    assert.equal(controller.getSnapshot().unreadCount, 0)

    seedRefresh(api, 1, [message('one', true)], [message('one', true)])
    await controller.markUnread(uuid('one'))
    assert.deepEqual(api.unreadCalls, [{ accessToken: 'token-a', publicationId: uuid('one') }])
    assert.equal(controller.getSnapshot().unreadCount, 1)

    seedRefresh(api, 0, [message('one', false)], [message('one', false)])
    await controller.markAllRead()
    assert.deepEqual(api.readAllCalls, [{ accessToken: 'token-a' }])
    assert.equal(controller.getSnapshot().unreadCount, 0)
  })

  it('removes a hidden message immediately and never lets an older response restore it', async () => {
    const api = new FakeMessageApi()
    const controller = createMessageCenterController({ api })
    await startIdle(controller, api)
    api.listResults.push(page([message('one', true), message('two', false)], 0, 10, 2))
    await controller.loadPage({ filter: 'ALL', page: 0, size: 10 })
    const stale = deferred<ReturnType<typeof page>>()
    api.countResults.push({ count: 1 }, { count: 0 })
    api.listResults.push(stale.promise, page([message('two', false)], 0, 10, 1), page([message('two', false)], 0, 5, 1))

    const oldLoad = controller.loadPage({ filter: 'ALL', page: 0, size: 10 })
    await controller.hide(uuid('one'))
    assert.deepEqual(controller.getSnapshot().page?.items.map((item) => item.publicationId), [uuid('two')])

    stale.resolve(page([message('one', true), message('two', false)], 0, 10, 2))
    await oldLoad
    assert.deepEqual(controller.getSnapshot().page?.items.map((item) => item.publicationId), [uuid('two')])
    assert.deepEqual(api.hideCalls, [{ accessToken: 'token-a', publicationId: uuid('one') }])
  })

  it('treats every engagement update and reconnect as an inbox refresh but coalesces a burst', async () => {
    const api = new FakeMessageApi()
    const summary = deferred<{ count: number }>()
    const controller = createMessageCenterController({ api })
    await startIdle(controller, api)
    api.countResults.push(summary.promise, { count: 3 })
    api.listResults.push(page([], 0, 5, 0), page([message('three', true)], 0, 5, 1))

    const first = controller.handleWakeup()
    const second = controller.handleWakeup()
    const third = controller.handleWakeup()
    assert.equal(api.countCalls.length, 1)
    summary.resolve({ count: 1 })
    await Promise.all([first, second, third])

    assert.equal(api.countCalls.length, 2)
    assert.equal(controller.getSnapshot().unreadCount, 3)
  })

  it('clears summary loading after a failure and permits an authoritative retry', async () => {
    const api = new FakeMessageApi()
    const failedCount = deferred<{ count: number }>()
    api.countResults.push(failedCount.promise)
    api.listResults.push(page([], 0, 5, 0))
    const controller = createMessageCenterController({ api })

    const started = controller.start('token-a')
    failedCount.reject(new TypeError('offline'))
    await started

    assert.equal(controller.getSnapshot().summaryLoading, false)
    assert.equal(controller.getSnapshot().error, 'offline')

    api.countResults.push({ count: 1 })
    api.listResults.push(page([message('one', true)], 0, 5, 1))
    await controller.refreshSummary()
    assert.equal(controller.getSnapshot().summaryLoading, false)
    assert.equal(controller.getSnapshot().error, null)
    assert.equal(controller.getSnapshot().unreadCount, 1)
  })

  it('drops stale data and pending mutations after token change or stop', async () => {
    const api = new FakeMessageApi()
    const staleCount = deferred<{ count: number }>()
    const staleList = deferred<ReturnType<typeof page>>()
    api.countResults.push(staleCount.promise)
    api.listResults.push(staleList.promise)
    const controller = createMessageCenterController({ api })

    const first = controller.start('token-a')
    controller.stop()
    staleCount.resolve({ count: 99 })
    staleList.resolve(page([message('one', true)], 0, 5, 1))
    await first

    assert.equal(controller.getSnapshot().unreadCount, 0)
    assert.deepEqual(controller.getSnapshot().recent, [])
  })
})

class FakeMessageApi {
  readonly countCalls: Array<{ accessToken: string }> = []
  readonly listCalls: Array<{ accessToken: string; page: number; size: number; unreadOnly: boolean }> = []
  readonly readCalls: Array<{ accessToken: string; publicationId: string }> = []
  readonly unreadCalls: Array<{ accessToken: string; publicationId: string }> = []
  readonly readAllCalls: Array<{ accessToken: string }> = []
  readonly hideCalls: Array<{ accessToken: string; publicationId: string }> = []
  readonly countResults: Array<{ count: number } | Promise<{ count: number }>> = []
  readonly listResults: Array<ReturnType<typeof page> | Promise<ReturnType<typeof page>>> = []

  getUnreadCount(input: { accessToken: string }) {
    this.countCalls.push(input)
    return take(this.countResults, { count: 0 })
  }
  listMessages(input: { accessToken: string; page: number; size: number; unreadOnly: boolean }) {
    this.listCalls.push(input)
    return take(this.listResults, page([], input.page, input.size, 0))
  }
  markMessageRead(input: { accessToken: string; publicationId: string }) {
    this.readCalls.push(input)
    return Promise.resolve()
  }
  markMessageUnread(input: { accessToken: string; publicationId: string }) {
    this.unreadCalls.push(input)
    return Promise.resolve()
  }
  markAllMessagesRead(input: { accessToken: string }) {
    this.readAllCalls.push(input)
    return Promise.resolve()
  }
  hideMessage(input: { accessToken: string; publicationId: string }) {
    this.hideCalls.push(input)
    return Promise.resolve()
  }
}

function seedRefresh(
  api: FakeMessageApi,
  count: number,
  pageItems: Array<ReturnType<typeof message>>,
  recentItems: Array<ReturnType<typeof message>>
) {
  api.countResults.push({ count })
  api.listResults.push(page(pageItems, 0, 10, pageItems.length), page(recentItems, 0, 5, recentItems.length))
}

async function startIdle(controller: ReturnType<typeof createMessageCenterController>, api: FakeMessageApi) {
  await controller.start('token-a')
  api.countCalls.length = 0
  api.listCalls.length = 0
}

function message(seed: string, unread: boolean) {
  return {
    publicationId: uuid(seed),
    contentItemId: uuid(`${seed}-content`),
    revisionId: uuid(`${seed}-revision`),
    sourceType: 'MANUAL' as const,
    category: 'NOTICE',
    sentAt: '2026-07-20T00:00:00Z',
    deliveredAt: '2026-07-20T00:00:01Z',
    title: `Message ${seed}`,
    sanitizedHtml: `<p>${seed}</p>`,
    coverAssetId: null,
    ctaLabel: null,
    ctaRouteKey: null,
    ctaParams: null,
    unread,
    readAt: unread ? null : '2026-07-20T00:02:00Z',
    readSource: unread ? null : 'USER' as const
  }
}

function page(items: Array<ReturnType<typeof message>>, pageNumber: number, size: number, total: number) {
  return { items, page: pageNumber, size, total, totalPages: total === 0 ? 0 : Math.ceil(total / size) }
}

function uuid(seed: string) {
  const hex = [...seed].reduce((sum, character) => (sum + character.charCodeAt(0)) % 0xffff, 0)
    .toString(16).padStart(4, '0')
  return `10000000-0000-4000-8000-00000000${hex}`
}

async function take<T>(values: Array<T | Promise<T>>, fallback: T): Promise<T> {
  return values.shift() ?? fallback
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
