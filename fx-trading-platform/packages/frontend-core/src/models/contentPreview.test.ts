import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createContentPreviewModel } from './contentPreview.ts'

const assetId = '4e947142-81b6-4641-8d0d-78ca306f9f32'
const controlledAssetUrl = `/api/public/engagement/assets/${assetId}`

describe('engagement content preview model', () => {
  it('models the same read-only sanitized content for PC and mobile at every size', () => {
    for (const surface of ['PC', 'MOBILE'] as const) {
      for (const sizeMode of ['SMALL', 'MEDIUM', 'LARGE'] as const) {
        const model = createContentPreviewModel(source({ sizeMode }), surface)

        assert.deepEqual(Object.keys(model), [
          'surface', 'sizeMode', 'title', 'sanitizedHtml', 'coverAsset', 'cta'
        ])
        assert.equal(model.surface, surface)
        assert.equal(model.sizeMode, sizeMode)
        assert.equal(model.sanitizedHtml, '<p>Backend-sanitized body</p>')
      }
    }
  })

  it('exposes one optional CTA and only an assetId-matched controlled cover URL', () => {
    const model = createContentPreviewModel(source({
      coverAssetId: assetId,
      cta: {
        label: 'View unread messages',
        routeKey: 'MESSAGE_CENTER',
        params: { filter: 'UNREAD' }
      }
    }), 'PC')

    assert.deepEqual(model.coverAsset, { assetId, url: controlledAssetUrl })
    assert.deepEqual(model.cta, {
      label: 'View unread messages',
      routeKey: 'MESSAGE_CENTER',
      params: { filter: 'UNREAD' }
    })
    assert.equal(Object.hasOwn(model, 'rawHtml'), false)
    assert.equal(Object.hasOwn(model, 'bodyDocument'), false)
  })

  it('keeps cover and CTA absent when the trusted response omits them', () => {
    const model = createContentPreviewModel(source(), 'MOBILE')

    assert.equal(model.coverAsset, null)
    assert.equal(model.cta, null)
  })

  it('fails closed for injectable cover fields, external or Base64 identifiers, and raw HTML', () => {
    const unsafeSources = [
      { ...source(), rawHtml: '<script>alert(1)</script>' },
      { ...source(), coverAsset: { assetId, url: 'https://evil.example/cover.webp' } },
      { ...source(), url: 'https://evil.example/cover.webp' },
      { ...source(), src: 'data:image/png;base64,AAAA' },
      { ...source(), external: 'https://evil.example/cover.webp' },
      { ...source(), coverAssetId: 'https://evil.example/cover.webp' },
      { ...source(), coverAssetId: 'data:image/png;base64,AAAA' }
    ]

    for (const unsafe of unsafeSources) {
      assert.throws(() => createContentPreviewModel(unsafe, 'PC'), TypeError)
    }
  })

  it('rejects unsupported surfaces, sizes, and multiple CTA shapes', () => {
    assert.throws(() => createContentPreviewModel(source(), 'TABLET'), TypeError)
    assert.throws(() => createContentPreviewModel(source({ sizeMode: 'FULL' }), 'PC'), TypeError)
    assert.throws(() => createContentPreviewModel(source({
      cta: [{ label: 'One' }, { label: 'Two' }]
    }), 'PC'), TypeError)
  })
})

function source(patch: Record<string, unknown> = {}) {
  return {
    sizeMode: 'MEDIUM',
    title: 'Service notice',
    sanitizedHtml: '<p>Backend-sanitized body</p>',
    coverAssetId: null,
    ...patch
  }
}
