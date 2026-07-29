import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  NORMAL_MESSAGE_DELIVERY_NOTE,
  createNormalMessageForm,
  isNormalMessageAudienceFrozen,
  isNormalMessageContentEditable,
  normalMessageActions,
  normalizeNormalMessageSaveRequest,
  normalizeNormalMessageSendRequest,
  normalizeNormalMessageUpdateRequest
} from './normalMessageModel.ts'

const USER_A = '11111111-1111-4111-8111-111111111111'
const USER_B = '22222222-2222-4222-8222-222222222222'

describe('normal message admin model', () => {
  it('serializes ALL/SELECTED drafts with UUID-only targets and JSON content', () => {
    const form = createNormalMessageForm()
    const selected = normalizeNormalMessageSaveRequest({
      ...form,
      category: ' NOTICE ',
      title: ' 系统维护 ',
      reason: ' 创建维护通知 ',
      audienceType: 'SELECTED',
      selectedUsers: [
        { id: USER_A, email: 'alice@example.com' },
        { id: USER_A, email: 'duplicate@example.com' },
        { id: USER_B, phone: '+8613800000000' }
      ]
    })

    assert.equal(selected.category, 'NOTICE')
    assert.equal(selected.content.title, '系统维护')
    assert.equal(selected.content.bodyDocument, JSON.stringify(form.bodyDocument))
    assert.deepEqual(selected.targetUserIds, [USER_A, USER_B])
    assert.equal(JSON.stringify(selected).includes('alice@example.com'), false)

    const all = normalizeNormalMessageSaveRequest({
      ...form,
      category: 'NOTICE',
      title: '全站通知',
      reason: '创建全站通知',
      selectedUsers: [{ id: USER_A, email: 'alice@example.com' }]
    })
    assert.deepEqual(all.targetUserIds, [])
  })

  it('rejects labels and arbitrary target values instead of treating them as user IDs', () => {
    const form = createNormalMessageForm()
    assert.throws(() => normalizeNormalMessageSaveRequest({
      ...form,
      category: 'NOTICE',
      title: '定向通知',
      reason: '验证目标',
      audienceType: 'SELECTED',
      selectedUsers: [{ id: 'alice@example.com', email: 'alice@example.com' }]
    }), /UUID/)
  })

  it('hydrates persisted and backfilled message details through the same model', () => {
    const form = createNormalMessageForm({
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      contentItemId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      lifecycleStatus: 'SENT',
      audienceType: 'SELECTED',
      category: 'LEGACY_NOTICE',
      targetUserIds: [USER_A],
      scheduledAt: null,
      sentAt: '2026-07-19T00:00:00Z',
      audienceCutoffAt: '2026-07-19T00:00:00Z',
      deletedAt: null,
      revisionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      revisionNo: 1,
      title: '历史通知',
      bodyDocument: '{"type":"doc","content":[{"type":"paragraph"}]}',
      sanitizedHtml: '<p></p>',
      coverAssetId: null,
      ctaLabel: '查看订单',
      ctaRouteKey: 'ORDERS',
      ctaParams: '{"tab":"open"}',
      targetCount: 1,
      createdAt: '2026-07-19T00:00:00Z',
      updatedAt: '2026-07-19T00:00:00Z'
    })

    assert.equal(form.category, 'LEGACY_NOTICE')
    assert.deepEqual(form.selectedUsers, [{ id: USER_A }])
    assert.deepEqual(form.cta, {
      label: '查看订单',
      routeKey: 'ORDERS',
      params: { tab: 'open' }
    })
  })

  it('omits the frozen audience from SENT updates while keeping content editable', () => {
    const form = {
      ...createNormalMessageForm(),
      category: 'NOTICE',
      title: '更新后的内容',
      reason: '修正文案',
      audienceType: 'SELECTED',
      selectedUsers: [{ id: USER_A }]
    }

    const sentUpdate = normalizeNormalMessageUpdateRequest(form, 'SENT')
    assert.equal(isNormalMessageAudienceFrozen('SENT'), true)
    assert.equal(isNormalMessageContentEditable('SENT'), true)
    assert.equal('audienceType' in sentUpdate, false)
    assert.equal('targetUserIds' in sentUpdate, false)
    assert.equal(sentUpdate.content.title, '更新后的内容')
    assert.equal(JSON.stringify(sentUpdate).toLowerCase().includes('receipt'), false)

    const scheduledUpdate = normalizeNormalMessageUpdateRequest(form, 'SCHEDULED')
    assert.equal(isNormalMessageAudienceFrozen('SCHEDULED'), false)
    assert.equal(scheduledUpdate.audienceType, 'SELECTED')
    assert.deepEqual(scheduledUpdate.targetUserIds, [USER_A])
    assert.throws(() => normalizeNormalMessageUpdateRequest(form, 'DELETED'), /deleted/i)
  })

  it('builds immediate and future scheduled send requests without inventing delivery controls', () => {
    const form = {
      ...createNormalMessageForm(),
      category: 'NOTICE',
      title: '发送通知',
      reason: '运营发送',
      audienceType: 'SELECTED',
      selectedUsers: [{ id: USER_A }]
    }
    const now = new Date('2026-07-20T00:00:00Z')

    assert.deepEqual(normalizeNormalMessageSendRequest(form, 'IMMEDIATE', null, now), {
      sendAt: null,
      reason: '运营发送'
    })
    assert.deepEqual(normalizeNormalMessageSendRequest(
      form,
      'SCHEDULED',
      '2026-07-20T01:00:00Z',
      now
    ), {
      sendAt: '2026-07-20T01:00:00Z',
      reason: '运营发送'
    })
    assert.throws(() => normalizeNormalMessageSendRequest(
      form,
      'SCHEDULED',
      '2026-07-20T00:00:00Z',
      now
    ), /future/)
    assert.throws(() => normalizeNormalMessageSendRequest(
      { ...form, selectedUsers: [] },
      'IMMEDIATE',
      null,
      now
    ), /selected users/)
  })

  it('exposes only lifecycle and permission-appropriate normal-message actions', () => {
    const authorities = new Set([
      'content:message:edit',
      'content:message:send',
      'content:message:delete'
    ])

    assert.deepEqual(normalMessageActions('DRAFT', authorities), [
      'edit', 'send-now', 'schedule', 'delete'
    ])
    assert.deepEqual(normalMessageActions('SCHEDULED', authorities), [
      'edit', 'send-now', 'schedule', 'cancel-schedule', 'delete'
    ])
    assert.deepEqual(normalMessageActions('SENT', authorities), ['edit', 'delete'])
    assert.deepEqual(normalMessageActions('DELETED', authorities), ['restore'])
    assert.equal(normalMessageActions('DRAFT', new Set(['content:message:read'])).length, 0)
    assert.match(NORMAL_MESSAGE_DELIVERY_NOTE, /不会自动弹窗/)
    assert.doesNotMatch(normalMessageActions.toString(), /popup|receipt|reset/i)
  })
})
