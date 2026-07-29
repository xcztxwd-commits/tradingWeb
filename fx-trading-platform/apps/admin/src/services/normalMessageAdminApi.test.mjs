import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import {
  cancelNormalMessageSchedule,
  createNormalMessage,
  deleteNormalMessage,
  getNormalMessage,
  getNormalMessages,
  restoreNormalMessage,
  sendNormalMessage,
  updateNormalMessage
} from './engagementAdminApi.ts'

const originalFetch = globalThis.fetch
const MESSAGE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

afterEach(() => {
  globalThis.fetch = originalFetch
})

describe('normal message admin typed API', () => {
  it('maps list/detail/create/update to the dedicated manual-message routes', async () => {
    const calls = captureRequests()
    const save = {
      category: 'NOTICE',
      audienceType: 'SELECTED',
      targetUserIds: ['11111111-1111-4111-8111-111111111111'],
      content: {
        title: '维护通知',
        bodyDocument: '{"type":"doc"}',
        coverAssetId: null,
        cta: null
      },
      reason: '创建消息'
    }
    const update = { content: save.content, reason: '修正文案' }

    await getNormalMessages('token', {
      page: 2,
      size: 25,
      lifecycleStatus: 'SCHEDULED',
      title: '维护 通知'
    })
    await getNormalMessage('token', MESSAGE_ID)
    await createNormalMessage('token', save)
    await updateNormalMessage('token', MESSAGE_ID, update)

    assert.deepEqual(calls.map(({ method, path }) => [method, path]), [
      ['GET', '/api/admin/engagement/messages?page=2&size=25&lifecycleStatus=SCHEDULED&title=%E7%BB%B4%E6%8A%A4+%E9%80%9A%E7%9F%A5'],
      ['GET', `/api/admin/engagement/messages/${MESSAGE_ID}`],
      ['POST', '/api/admin/engagement/messages'],
      ['PUT', `/api/admin/engagement/messages/${MESSAGE_ID}`]
    ])
    assert.deepEqual(JSON.parse(calls[2].body), save)
    assert.deepEqual(JSON.parse(calls[3].body), update)
  })

  it('maps immediate/scheduled/cancel/delete/restore with mandatory reasons and no receipt reset', async () => {
    const calls = captureRequests()

    await sendNormalMessage('token', MESSAGE_ID, {
      sendAt: null,
      reason: '立即发送',
      receiptReset: true
    })
    await sendNormalMessage('token', MESSAGE_ID, {
      sendAt: '2026-07-21T00:00:00Z',
      reason: '定时发送'
    })
    await cancelNormalMessageSchedule('token', MESSAGE_ID, '取消排期')
    await deleteNormalMessage('token', MESSAGE_ID, '逻辑删除')
    await restoreNormalMessage('token', MESSAGE_ID, '恢复消息')

    assert.deepEqual(calls.map(({ method, path }) => [method, path]), [
      ['POST', `/api/admin/engagement/messages/${MESSAGE_ID}/send`],
      ['POST', `/api/admin/engagement/messages/${MESSAGE_ID}/send`],
      ['POST', `/api/admin/engagement/messages/${MESSAGE_ID}/cancel-schedule`],
      ['DELETE', `/api/admin/engagement/messages/${MESSAGE_ID}`],
      ['POST', `/api/admin/engagement/messages/${MESSAGE_ID}/restore`]
    ])
    assert.deepEqual(JSON.parse(calls[0].body), { sendAt: null, reason: '立即发送' })
    assert.deepEqual(JSON.parse(calls[1].body), {
      sendAt: '2026-07-21T00:00:00Z',
      reason: '定时发送'
    })
    assert.deepEqual(JSON.parse(calls[2].body), { reason: '取消排期' })
    assert.deepEqual(JSON.parse(calls[3].body), { reason: '逻辑删除' })
    assert.deepEqual(JSON.parse(calls[4].body), { reason: '恢复消息' })
    assert.equal(calls.some(({ body }) => body.toLowerCase().includes('receipt')), false)

    assert.throws(() => cancelNormalMessageSchedule('token', MESSAGE_ID, '   '), /reason is required/)
    assert.throws(() => deleteNormalMessage('token', MESSAGE_ID, 'x'.repeat(501)), /reason is too long/)
  })
})

function captureRequests() {
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    calls.push({
      path: String(url),
      method: init.method ?? 'GET',
      body: typeof init.body === 'string' ? init.body : ''
    })
    return new Response(JSON.stringify({ success: true, code: 'OK', message: 'OK', data: {} }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  }
  return calls
}
