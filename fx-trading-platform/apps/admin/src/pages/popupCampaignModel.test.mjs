import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  EMPTY_DOCUMENT,
  campaignActions,
  campaignFrozenFields,
  createCampaignPreview,
  createPopupCampaignForm,
  isCampaignFieldFrozen,
  normalizeCampaignSaveRequest,
  validateCampaignPublication
} from './popupCampaignModel.ts'

const USER_A = '11111111-1111-4111-8111-111111111111'
const USER_B = '22222222-2222-4222-8222-222222222222'

describe('popup campaign admin model', () => {
  it('builds a minimal safe form and serializes only deduplicated selected user UUIDs', () => {
    const form = createPopupCampaignForm(undefined, new Date('2026-07-20T00:00:00Z'))
    assert.deepEqual(form.bodyDocument, EMPTY_DOCUMENT)
    assert.equal(form.maxTotalImpressions, 3)
    assert.equal(form.maxDailyImpressions, 1)
    assert.equal(form.minIntervalSeconds, 4 * 60 * 60)

    const request = normalizeCampaignSaveRequest({
      ...form,
      name: '新用户欢迎活动',
      title: '欢迎',
      reason: '创建欢迎活动',
      audienceType: 'SELECTED',
      selectedUsers: [
        { id: USER_A, email: 'a@example.com', phone: null, status: 'ACTIVE' },
        { id: USER_A, email: 'duplicate@example.com', phone: null, status: 'ACTIVE' },
        { id: USER_B, email: 'b@example.com', phone: null, status: 'FROZEN' }
      ]
    })

    assert.deepEqual(request.targetUserIds, [USER_A, USER_B])
    assert.equal(request.content.bodyDocument, JSON.stringify(EMPTY_DOCUMENT))
    assert.equal('selectedUsers' in request, false)
    assert.equal(JSON.stringify(request).includes('a@example.com'), false)

    const allRequest = normalizeCampaignSaveRequest({
      ...form,
      name: '全量活动',
      title: '公告',
      reason: '创建全量活动',
      audienceType: 'ALL',
      selectedUsers: [{ id: USER_A, email: 'a@example.com', phone: null, status: 'ACTIVE' }]
    })
    assert.deepEqual(allRequest.targetUserIds, [])
  })

  it('rejects malformed selected-user IDs instead of sending labels or arbitrary values', () => {
    const form = createPopupCampaignForm(undefined, new Date('2026-07-20T00:00:00Z'))
    assert.throws(() => normalizeCampaignSaveRequest({
      ...form,
      name: '定向活动',
      title: '标题',
      reason: '测试非法用户',
      audienceType: 'SELECTED',
      selectedUsers: [{ id: 'a@example.com', email: 'a@example.com', phone: null, status: 'ACTIVE' }]
    }), /UUID/)
  })

  it('uses firstPublishedAt to freeze every field the backend freezes', () => {
    assert.deepEqual(campaignFrozenFields, [
      'name',
      'audienceType',
      'targetUserIds',
      'syncToInbox',
      'startAt',
      'timeZone',
      'templateSize'
    ])
    const draft = { firstPublishedAt: null }
    const published = { firstPublishedAt: '2026-07-20T01:00:00Z' }
    assert.equal(isCampaignFieldFrozen(draft, 'audienceType'), false)
    assert.equal(isCampaignFieldFrozen(published, 'selectedUsers'), true)
    assert.equal(isCampaignFieldFrozen(published, 'name'), true)
    assert.equal(isCampaignFieldFrozen(published, 'endAt'), false)
  })

  it('validates immediate and scheduled publication windows, time zone and reason', () => {
    const base = {
      ...createPopupCampaignForm(undefined, new Date('2026-07-20T00:00:00Z')),
      name: '定时活动',
      title: '标题',
      reason: '运营排期',
      startAt: '2026-07-20T02:00:00Z',
      endAt: '2026-07-21T02:00:00Z'
    }
    assert.deepEqual(
      validateCampaignPublication(base, 'SCHEDULED', new Date('2026-07-20T01:00:00Z')),
      []
    )
    assert.ok(validateCampaignPublication(
      { ...base, startAt: '2026-07-20T01:00:00Z' },
      'SCHEDULED',
      new Date('2026-07-20T01:00:00Z')
    ).includes('startAt must be in the future for scheduled publication'))
    assert.ok(validateCampaignPublication(
      { ...base, endAt: base.startAt },
      'SCHEDULED',
      new Date('2026-07-20T01:00:00Z')
    ).includes('endAt must be after startAt'))
    assert.ok(validateCampaignPublication(
      { ...base, timeZone: 'Mars/Olympus' },
      'SCHEDULED',
      new Date('2026-07-20T01:00:00Z')
    ).includes('timeZone is invalid'))
    assert.ok(validateCampaignPublication(
      { ...base, reason: '   ' },
      'SCHEDULED',
      new Date('2026-07-20T01:00:00Z')
    ).includes('reason is required'))
    assert.ok(validateCampaignPublication(
      base,
      'IMMEDIATE',
      new Date('2026-07-20T01:00:00Z')
    ).includes('startAt must not be in the future for immediate publication'))
  })

  it('derives lifecycle actions from exact authorities without granting user details through stats', () => {
    const allAuthorities = new Set([
      'content:campaign:edit',
      'content:campaign:publish',
      'content:campaign:delete',
      'content:campaign:stats'
    ])
    const draftActions = campaignActions(
      { lifecycleStatus: 'DRAFT', firstPublishedAt: null },
      allAuthorities
    )
    assert.deepEqual(draftActions, ['edit', 'test-popup', 'publish', 'delete', 'stats'])
    assert.equal(draftActions.includes('users'), false)
    assert.equal(draftActions.includes('export'), false)

    assert.deepEqual(campaignActions(
      { lifecycleStatus: 'ACTIVE', firstPublishedAt: '2026-07-20T00:00:00Z' },
      allAuthorities
    ), ['edit', 'test-popup', 'pause', 'end', 'reset-delivery', 'delete', 'stats'])
    assert.deepEqual(campaignActions(
      { lifecycleStatus: 'PAUSED', firstPublishedAt: '2026-07-20T00:00:00Z' },
      allAuthorities
    ), ['edit', 'test-popup', 'resume', 'end', 'reset-delivery', 'delete', 'stats'])
    assert.deepEqual(campaignActions(
      { lifecycleStatus: 'DELETED', firstPublishedAt: '2026-07-20T00:00:00Z' },
      new Set(['content:campaign:delete', 'content:campaign:user-detail'])
    ), ['restore', 'users'])
  })

  it('builds a visibly marked preview through frontend-core with controlled asset URLs', () => {
    const preview = createCampaignPreview({
      templateSize: 'SMALL',
      title: '安全预览',
      sanitizedHtml: '<p>后端清洗内容</p>',
      coverAssetId: USER_A,
      ctaLabel: '查看订单',
      ctaRouteKey: 'ORDERS',
      ctaParams: '{"tab":"open"}'
    }, 'MOBILE')

    assert.equal(preview.preview, true)
    assert.equal(preview.previewLabel, 'PREVIEW')
    assert.equal(preview.surface, 'MOBILE')
    assert.equal(preview.coverAsset?.url, `/api/public/engagement/assets/${USER_A}`)
    assert.deepEqual(preview.cta?.params, { tab: 'open' })
    assert.equal('bodyDocument' in preview, false)
  })
})
