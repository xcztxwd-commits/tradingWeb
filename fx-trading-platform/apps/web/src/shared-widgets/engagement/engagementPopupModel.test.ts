import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import type { PopupDisplayModel, PopupQueueSnapshot } from '@fx-platform/frontend-core'
import {
  activateEngagementPopupCta,
  createEngagementPopupView
} from './engagementPopupModel.ts'

const assetId = '4e947142-81b6-4641-8d0d-78ca306f9f32'

function claim(overrides: Partial<PopupDisplayModel['content']> = {}): PopupDisplayModel {
  return {
    queueSessionId: 'queue-1',
    deliveryId: 'delivery-1',
    campaignId: 'campaign-1',
    revisionId: 'revision-1',
    deliveryToken: 'opaque-token',
    expiresAt: '2026-07-20T12:00:00Z',
    content: {
      surface: 'PC',
      sizeMode: 'MEDIUM',
      title: 'Market update',
      sanitizedHtml: `<p>Read this</p><img data-asset-id="${assetId}" alt="Chart">`,
      coverAsset: { assetId, url: `/api/public/engagement/assets/${assetId}` },
      cta: { label: 'Open unread', routeKey: 'MESSAGE_CENTER', params: { filter: 'UNREAD' } },
      ...overrides
    }
  }
}

function snapshot(current: PopupDisplayModel | null, blocked = false): PopupQueueSnapshot<PopupDisplayModel> {
  return { current, shown: false, blocked }
}

describe('engagement popup view', () => {
  it('hydrates only platform images and preserves the responsive content model', () => {
    const view = createEngagementPopupView(snapshot(claim()))

    assert.equal(view?.deliveryId, 'delivery-1')
    assert.equal(view?.surface, 'PC')
    assert.equal(view?.sizeMode, 'MEDIUM')
    assert.equal(view?.coverUrl, `/api/public/engagement/assets/${assetId}`)
    assert.match(view?.html ?? '', new RegExp(`/api/public/engagement/assets/${assetId}`))
    assert.deepEqual(view?.cta, { label: 'Open unread', route: '/messages?filter=UNREAD' })
  })

  it('keeps an already-issued popup mounted below a critical overlay', () => {
    assert.equal(createEngagementPopupView(snapshot(claim(), true))?.deliveryId, 'delivery-1')
  })

  it('fails closed for unsafe HTML and invalid CTA routes', () => {
    assert.equal(createEngagementPopupView(snapshot(claim({ sanitizedHtml: '<img src="https://evil.example/x">' }))), null)

    const view = createEngagementPopupView(snapshot(claim({
      cta: { label: 'Leave', routeKey: 'https://evil.example', params: {} }
    })))
    assert.ok(view)
    assert.equal(view.cta, null)
  })
})

describe('engagement popup CTA', () => {
  it('navigates only after the server terminates the queue', async () => {
    const view = createEngagementPopupView(snapshot(claim()))
    assert.ok(view?.cta)
    const navigations: string[] = []

    assert.equal(await activateEngagementPopupCta(view.cta, async () => 'CONTINUE', (route) => navigations.push(route)), false)
    assert.deepEqual(navigations, [])

    assert.equal(await activateEngagementPopupCta(view.cta, async () => 'TERMINATE', (route) => navigations.push(route)), true)
    assert.deepEqual(navigations, ['/messages?filter=UNREAD'])
  })

  it('does not send a click receipt for a missing or rejected route', async () => {
    let clicks = 0
    const activated = await activateEngagementPopupCta(null, async () => {
      clicks += 1
      return 'TERMINATE'
    }, () => assert.fail('must not navigate'))

    assert.equal(activated, false)
    assert.equal(clicks, 0)
  })
})
