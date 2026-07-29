import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const text = (path) => readFileSync(join(root, path), 'utf8')

function javaSources(path) {
  return readdirSync(join(root, path), { withFileTypes: true })
    .flatMap((entry) => entry.isDirectory()
      ? javaSources(join(path, entry.name))
      : entry.name.endsWith('.java') ? [text(join(path, entry.name))] : [])
    .join('\n')
}

describe('engagement implementation contract', () => {
  const backend = javaSources('backend/src/main/java')

  it('provides the authenticated user message-center API', () => {
    assert.ok(/\/api\/me\/messages/.test(backend), 'missing /api/me/messages controller')
  })

  it('uses a dedicated rich-text editor instead of the textarea branch', () => {
    const featurePage = text('apps/admin/src/pages/FeatureCrudPage.tsx')

    assert.ok(
      !/field\.component === 'textarea'\s*\|\|\s*field\.component === 'richtext'/.test(featurePage),
      'richtext still shares the textarea branch'
    )
    assert.ok(/RichTextEditor/.test(featurePage), 'missing dedicated RichTextEditor')
  })

  it('defines the PopupCampaign domain model', () => {
    assert.ok(
      /(?:class|record|interface)\s+PopupCampaign\b/.test(backend),
      'missing PopupCampaign domain model'
    )
  })

  it('allows the authenticated engagement wake-up destinations', () => {
    const websocket = [
      text('backend/src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java'),
      text('backend/src/main/java/com/fxplatform/common/websocket/WebSocketJwtChannelInterceptor.java')
    ].join('\n')

    assert.ok(/\/topic\/engagement\/updates/.test(websocket), 'missing engagement topic')
    assert.ok(/\/user\/queue\/engagement-updates/.test(websocket), 'missing engagement user queue')
  })
})
