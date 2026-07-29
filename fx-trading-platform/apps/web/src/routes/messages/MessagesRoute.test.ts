import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))

function source(name: string) {
  const path = join(currentDir, name)
  assert.equal(existsSync(path), true, `${name} must exist`)
  return readFileSync(path, 'utf8')
}

describe('canonical messages route', () => {
  it('consumes the AppShell-owned runtime through context and never creates a second controller', () => {
    const runtime = source('MessageCenterRuntime.tsx')
    const hook = source('useMessagesRouteController.ts')

    assert.match(runtime, /export type MessageCenterRuntime/)
    assert.match(runtime, /MessageCenterRuntimeProvider/)
    assert.match(runtime, /useSyncExternalStore/)
    assert.match(hook, /useMessageCenterRuntime/)
    assert.doesNotMatch(`${runtime}\n${hook}`, /createMessageCenterController\s*\(/)
  })

  it('keeps one route model above distinct PC and Mobile views', () => {
    const route = source('MessagesRoute.tsx')
    const pc = source('PcMessagesPage.tsx')
    const mobile = source('MobileMessagesPage.tsx')

    assert.match(route, /const PcMessagesPage = lazy/)
    assert.match(route, /const MobileMessagesPage = lazy/)
    assert.match(route, /useMessagesRouteController/)
    assert.match(route, /<PlatformView[\s\S]*model=\{model\}[\s\S]*pc=\{PcMessagesPage\}[\s\S]*mobile=\{MobileMessagesPage\}/)
    assert.match(pc, /MessageCenterContent[\s\S]*platform="pc"/)
    assert.match(mobile, /MessageCenterContent[\s\S]*platform="mobile"/)
  })

  it('renders every canonical inbox operation and explicit data state', () => {
    const content = source('MessageCenterContent.tsx')

    for (const contract of [
      /filter === 'ALL'/,
      /filter === 'UNREAD'/,
      /markRead/,
      /markUnread/,
      /markAllRead/,
      /hide/,
      /pageLoading/,
      /error/,
      /page\.items\.length === 0/,
      /pendingActions/,
      /goToPage/
    ]) assert.match(content, contract)
    assert.match(content, /dangerouslySetInnerHTML/)
    assert.match(content, /prepareEngagementHtml/)
  })

  it('shows an actionable login state instead of fake loading for a signed-out deep link', () => {
    const runtime = source('MessageCenterRuntime.tsx')
    const hook = source('useMessagesRouteController.ts')
    const content = source('MessageCenterContent.tsx')

    assert.match(runtime, /useMessageCenterAuthenticated/)
    assert.match(hook, /if \(!authenticated\) return/)
    assert.match(content, /if \(!model\.authenticated\)/)
    assert.match(content, /to="\/login\?redirect=%2Fmessages"/)
  })
})
