import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import {
  createPopupCampaign,
  getPopupCampaign,
  getPopupCampaigns,
  getPopupCampaignStats,
  getPopupCampaignUsers,
  getPopupPolicy,
  runPopupCampaignAction,
  searchAdminUsers,
  updatePopupCampaign,
  updatePopupPolicy,
  uploadContentAsset
} from './engagementAdminApi.ts'

const originalFetch = globalThis.fetch
const CAMPAIGN_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

afterEach(() => {
  globalThis.fetch = originalFetch
})

describe('engagement admin typed API', () => {
  it('encodes every campaign list filter and never exposes an export endpoint', async () => {
    const calls = captureRequests()
    await getPopupCampaigns('token', {
      page: 2,
      size: 25,
      lifecycleStatus: 'ACTIVE',
      name: '欢迎 活动',
      audienceType: 'SELECTED',
      syncToInbox: false,
      effectiveFrom: '2026-07-20T00:00:00Z',
      effectiveTo: '2026-07-21T00:00:00Z'
    })

    const url = new URL(calls[0].url, 'https://admin.invalid')
    assert.equal(url.pathname, '/api/admin/engagement/campaigns')
    assert.deepEqual(Object.fromEntries(url.searchParams), {
      page: '2',
      size: '25',
      lifecycleStatus: 'ACTIVE',
      name: '欢迎 活动',
      audienceType: 'SELECTED',
      syncToInbox: 'false',
      effectiveFrom: '2026-07-20T00:00:00Z',
      effectiveTo: '2026-07-21T00:00:00Z'
    })
    assert.equal(calls[0].authorization, 'Bearer token')
    assert.equal(calls[0].url.includes('export'), false)
  })

  it('maps campaign CRUD and lifecycle actions, including a DELETE request body', async () => {
    const calls = captureRequests()
    const request = { name: '活动', reason: '运营调整' }

    await getPopupCampaign('token', CAMPAIGN_ID)
    await createPopupCampaign('token', request)
    await updatePopupCampaign('token', CAMPAIGN_ID, request)
    await runPopupCampaignAction('token', CAMPAIGN_ID, 'pause', '暂停排查')
    await runPopupCampaignAction('token', CAMPAIGN_ID, 'delete', '活动下线')

    assert.deepEqual(calls.map(({ method, path }) => [method, path]), [
      ['GET', `/api/admin/engagement/campaigns/${CAMPAIGN_ID}`],
      ['POST', '/api/admin/engagement/campaigns'],
      ['PUT', `/api/admin/engagement/campaigns/${CAMPAIGN_ID}`],
      ['POST', `/api/admin/engagement/campaigns/${CAMPAIGN_ID}/pause`],
      ['DELETE', `/api/admin/engagement/campaigns/${CAMPAIGN_ID}`]
    ])
    assert.deepEqual(JSON.parse(calls[3].body), { reason: '暂停排查' })
    assert.deepEqual(JSON.parse(calls[4].body), { reason: '活动下线' })
  })

  it('keeps aggregate stats separate from audited user details with a mandatory reason', async () => {
    const calls = captureRequests()
    await getPopupCampaignStats('token', CAMPAIGN_ID)
    await getPopupCampaignUsers('token', CAMPAIGN_ID, { page: 1, size: 30, reason: '处理用户投诉' })

    assert.equal(calls[0].path, `/api/admin/engagement/campaigns/${CAMPAIGN_ID}/stats`)
    const usersUrl = new URL(calls[1].url, 'https://admin.invalid')
    assert.equal(usersUrl.pathname, `/api/admin/engagement/campaigns/${CAMPAIGN_ID}/users`)
    assert.deepEqual(Object.fromEntries(usersUrl.searchParams), {
      page: '1',
      size: '30',
      reason: '处理用户投诉'
    })
    assert.throws(
      () => getPopupCampaignUsers('token', CAMPAIGN_ID, { page: 0, size: 20, reason: '   ' }),
      /reason is required/
    )
  })

  it('maps popup policy and minimal user search APIs', async () => {
    const calls = captureRequests()
    await getPopupPolicy('token')
    await updatePopupPolicy('token', {
      maxSequentialPopups: 3,
      deliveryRetentionDays: 365,
      reason: '季度策略调整'
    })
    await searchAdminUsers('token', { q: 'alice+test@example.com', page: 0, size: 10 })

    assert.deepEqual(calls.map(({ method, path }) => [method, path]), [
      ['GET', '/api/admin/engagement/popup-policy'],
      ['PUT', '/api/admin/engagement/popup-policy'],
      ['GET', '/api/admin/users/search?q=alice%2Btest%40example.com&page=0&size=10']
    ])
    assert.deepEqual(JSON.parse(calls[1].body), {
      maxSequentialPopups: 3,
      deliveryRetentionDays: 365,
      reason: '季度策略调整'
    })
  })

  it('uploads an asset as multipart without manually setting the boundary header', async () => {
    const calls = captureRequests()
    const file = new Blob(['image-bytes'], { type: 'image/png' })
    await uploadContentAsset('token', file, 'cover.png')

    assert.equal(calls[0].method, 'POST')
    assert.equal(calls[0].path, '/api/admin/engagement/assets')
    assert.ok(calls[0].rawBody instanceof FormData)
    assert.equal(calls[0].contentType, null)
    assert.equal(calls[0].rawBody.get('file')?.name, 'cover.png')
  })
})

function captureRequests() {
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    const headers = new Headers(init.headers)
    const fullUrl = String(url)
    calls.push({
      url: fullUrl,
      path: fullUrl,
      method: init.method ?? 'GET',
      body: typeof init.body === 'string' ? init.body : '',
      rawBody: init.body,
      authorization: headers.get('Authorization'),
      contentType: headers.get('Content-Type')
    })
    return new Response(JSON.stringify({ success: true, code: 'OK', message: 'OK', data: {} }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  }
  return calls
}
