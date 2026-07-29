import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  createMessagePageModel,
  createPopupDisplayModel,
  createUnreadCountModel
} from './engagementModels.ts'

const assetId = '4e947142-81b6-4641-8d0d-78ca306f9f32'

describe('engagement display models', () => {
  it('adapts the flat backend popup claim to the shared safe display shape', () => {
    const model = createPopupDisplayModel(popupClaim(), 'MOBILE')

    assert.deepEqual(model, {
      queueSessionId: '20000000-0000-0000-0000-000000000002',
      deliveryId: '30000000-0000-0000-0000-000000000003',
      campaignId: '40000000-0000-0000-0000-000000000004',
      revisionId: '50000000-0000-0000-0000-000000000005',
      deliveryToken: 'opaque-delivery-token',
      expiresAt: '2026-07-20T12:05:00Z',
      content: {
        surface: 'MOBILE',
        sizeMode: 'MEDIUM',
        title: 'Pinned campaign title',
        sanitizedHtml: '<p>Pinned safe body</p>',
        coverAsset: {
          assetId,
          url: `/api/public/engagement/assets/${assetId}`
        },
        cta: {
          label: 'Open messages',
          routeKey: 'MESSAGE_CENTER',
          params: { filter: 'UNREAD' }
        }
      }
    })
    assert.equal(Object.hasOwn(model.content, 'rawHtml'), false)
    assert.equal(Object.hasOwn(model.content, 'bodyDocument'), false)
  })

  it('fails closed instead of guessing from raw HTML or external asset fields', () => {
    for (const unsafe of [
      { ...popupClaim(), rawHtml: '<img src=x onerror=alert(1)>' },
      { ...popupClaim(), sanitizedHtml: undefined, bodyDocument: { type: 'doc' } },
      { ...popupClaim(), coverAssetId: 'https://evil.example/cover.webp' },
      { ...popupClaim(), coverAssetId: 'data:image/png;base64,AAAA' },
      { ...popupClaim(), coverUrl: 'https://evil.example/cover.webp' }
    ]) {
      assert.throws(() => createPopupDisplayModel(unsafe, 'PC'), TypeError)
    }
  })

  it('models paged messages with explicit unread and read states', () => {
    const model = createMessagePageModel({
      items: [
        message({ publicationId: 'publication-unread', unread: true, readAt: null, readSource: null }),
        message({
          publicationId: 'publication-read',
          unread: false,
          readAt: '2026-07-20T12:10:00Z',
          readSource: 'USER'
        })
      ],
      page: 1,
      size: 20,
      total: 22,
      totalPages: 2
    })

    assert.equal(model.items[0].readState, 'UNREAD')
    assert.equal(model.items[0].readAt, null)
    assert.equal(model.items[1].readState, 'READ')
    assert.equal(model.items[1].readAt, '2026-07-20T12:10:00Z')
    assert.equal(model.items[1].readSource, 'USER')
    assert.deepEqual(model.items[0].content.coverAsset, {
      assetId,
      url: `/api/public/engagement/assets/${assetId}`
    })
    assert.deepEqual(model.items[0].content.cta, {
      label: 'Open messages',
      routeKey: 'MESSAGE_CENTER',
      params: { filter: 'UNREAD' }
    })
    assert.deepEqual({ page: model.page, size: model.size, total: model.total, totalPages: model.totalPages }, {
      page: 1,
      size: 20,
      total: 22,
      totalPages: 2
    })
  })

  it('models unread count as a finite non-negative integer and rejects inconsistent receipt state', () => {
    assert.equal(createUnreadCountModel({ count: 7 }), 7)
    for (const invalid of [{ count: -1 }, { count: 1.5 }, { count: '7' }]) {
      assert.throws(() => createUnreadCountModel(invalid), TypeError)
    }
    assert.throws(() => createMessagePageModel({
      items: [message({ unread: true, readAt: '2026-07-20T12:10:00Z', readSource: 'USER' })],
      page: 0,
      size: 20,
      total: 1,
      totalPages: 1
    }), TypeError)
  })

  it('rejects message raw HTML guesses, external covers and malformed CTA params', () => {
    const unsafeMessages = [
      { ...message(), rawHtml: '<script>alert(1)</script>' },
      { ...message(), sanitizedHtml: undefined, bodyDocument: { type: 'doc' } },
      { ...message(), coverAssetId: 'https://evil.example/cover.webp' },
      { ...message(), coverUrl: 'https://evil.example/cover.webp' },
      { ...message(), ctaParams: '{not-json}' },
      { ...message(), ctaParams: '"not-an-object"' }
    ]

    for (const unsafe of unsafeMessages) {
      assert.throws(() => createMessagePageModel({
        items: [unsafe], page: 0, size: 20, total: 1, totalPages: 1
      }), TypeError)
    }
  })
})

function popupClaim() {
  return {
    queueSessionId: '20000000-0000-0000-0000-000000000002',
    deliveryId: '30000000-0000-0000-0000-000000000003',
    campaignId: '40000000-0000-0000-0000-000000000004',
    revisionId: '50000000-0000-0000-0000-000000000005',
    deliveryToken: 'opaque-delivery-token',
    expiresAt: '2026-07-20T12:05:00Z',
    templateSize: 'MEDIUM',
    title: 'Pinned campaign title',
    sanitizedHtml: '<p>Pinned safe body</p>',
    coverAssetId: assetId,
    ctaLabel: 'Open messages',
    ctaRouteKey: 'MESSAGE_CENTER',
    ctaParams: '{"filter":"UNREAD"}'
  }
}

function message(patch: Record<string, unknown> = {}) {
  return {
    publicationId: 'publication-unread',
    contentItemId: 'content-item-1',
    revisionId: 'revision-1',
    sourceType: 'MANUAL',
    category: 'SYSTEM',
    sentAt: '2026-07-20T12:00:00Z',
    deliveredAt: '2026-07-20T12:00:01Z',
    title: 'Service notice',
    sanitizedHtml: '<p>Backend-sanitized message</p>',
    coverAssetId: assetId,
    ctaLabel: 'Open messages',
    ctaRouteKey: 'MESSAGE_CENTER',
    ctaParams: '{"filter":"UNREAD"}',
    unread: true,
    readAt: null,
    readSource: null,
    ...patch
  }
}
