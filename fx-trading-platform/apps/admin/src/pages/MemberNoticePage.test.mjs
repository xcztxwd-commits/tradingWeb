import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))

function readSource(relativePath) {
  const absolutePath = join(currentDir, relativePath)
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

function assertContainsAll(source, values, sourceName) {
  for (const value of values) {
    assert.ok(source.includes(value), `${sourceName} must contain ${value}`)
  }
}

const appSource = readSource('../app/AdminApp.tsx')
const menuSource = readSource('../app/adminMenu.ts')
const listSource = readSource('MemberNoticePage.tsx')
const editorSource = readSource('MemberNoticeEditorPage.tsx')
const modelSource = readSource('normalMessageModel.ts')
const apiSource = readSource('../services/engagementAdminApi.ts')
const featureSources = [listSource, editorSource, modelSource, apiSource].join('\n')

describe('member notice dedicated admin surface', () => {
  it('replaces the generic member-notices CRUD route with dedicated list and editor routes', () => {
    assertContainsAll(appSource, [
      "import { MemberNoticePage } from '../pages/MemberNoticePage'",
      "import { MemberNoticeEditorPage } from '../pages/MemberNoticeEditorPage'",
      '<Route path="/content/member-notices" element={<MemberNoticePage />} />',
      '<Route path="/content/member-notices/new" element={<MemberNoticeEditorPage />} />',
      '<Route path="/content/member-notices/:id/edit" element={<MemberNoticeEditorPage />} />'
    ], 'AdminApp.tsx')
    assert.doesNotMatch(appSource, /FeatureCrudPage[^\n]+member-notices/i)
  })

  it('keeps the member-notices menu entry but removes its generic pageKey', () => {
    assert.match(menuSource, /\{\s*to:\s*'\/content\/member-notices',\s*label:[^}]+icon:[^}]+\}/)
    assert.doesNotMatch(menuSource, /to:\s*'\/content\/member-notices'[^}]+pageKey/)
  })
})

describe('member notice content and audience', () => {
  it('uses the restricted rich-text editor without textarea or raw HTML escape hatches', () => {
    assert.match(editorSource, /\blazy\(\(\)\s*=>\s*import\('\.\.\/components\/RestrictedRichTextEditor'\)\)/)
    assert.match(editorSource, /<RestrictedRichTextEditor\b/)
    assert.match(editorSource, /bodyDocument/)
    assert.doesNotMatch(editorSource, /<textarea\b|dangerouslySetInnerHTML|getHTML\(|rawHtml/i)
  })

  it('supports ALL and searched multi-select UUID audiences', () => {
    assertContainsAll(editorSource, [
      "'ALL'",
      "'SELECTED'",
      'searchAdminUsers(',
      'selectedUserIds',
      'type="checkbox"',
      'targetUserIds'
    ], 'MemberNoticeEditorPage.tsx')
    assert.match(editorSource, /targetUserIds:\s*Array\.from\(selectedUserIds\)/)
    assertContainsAll(modelSource, ['UUID_PATTERN', 'audienceType', 'targetUserIds'], 'normalMessageModel.ts')
    assert.match(apiSource, /\/api\/admin\/users\/search/)
  })

  it('freezes only audience after send and leaves content editable', () => {
    assert.match(modelSource, /isNormalMessageAudienceFrozen\([\s\S]+lifecycleStatus\s*===\s*'SENT'/)
    assert.match(modelSource, /isNormalMessageContentEditable\([\s\S]+lifecycleStatus\s*!==\s*'DELETED'/)
    assert.match(modelSource, /isNormalMessageAudienceFrozen\(lifecycleStatus\)[\s\S]+\? request[\s\S]+audienceType:[\s\S]+targetUserIds:/)
    assert.match(editorSource, /const audienceFrozen\s*=\s*isNormalMessageAudienceFrozen\(/)
    assert.match(editorSource, /disabled=\{[^}]*audienceFrozen/)
    assert.match(editorSource, /<RestrictedRichTextEditor[\s\S]+disabled=\{!canEdit\}/)
  })

  it('fails closed when the editor route changes or a different message fails to load', () => {
    assert.match(editorSource, /if \(!id\) \{[\s\S]+setNotice\(undefined\)[\s\S]+createNormalMessageForm\(\)[\s\S]+setSendAt\(''\)/)
    assert.match(editorSource, /useEffect\(\(\) => \{[\s\S]+if \(id && notice\?\.id === id\)[\s\S]+return[\s\S]+if \(!id\)[\s\S]+setNotice\(undefined\)[\s\S]+getNormalMessage\(token, id\)/)
    assert.match(editorSource, /if \(id && notice\?\.id !== id\) \{[\s\S]+state-block error[\s\S]+state-block loading/)
  })
})

describe('member notice lifecycle', () => {
  it('supports draft, immediate send, scheduled send and schedule cancellation', () => {
    assertContainsAll(editorSource, ['saveDraft(', 'sendNow(', 'scheduleMessage(', 'sendAt'], 'MemberNoticeEditorPage.tsx')
    assertContainsAll(listSource, ["'DRAFT'", "'SCHEDULED'", "'SENT'", "'cancel-schedule'"], 'MemberNoticePage.tsx')
    assertContainsAll(apiSource, [
      '/api/admin/engagement/messages',
      "`${messagePath(messageId)}/send`",
      "'cancel-schedule'"
    ], 'engagementAdminApi.ts')
  })

  it('offers logical delete and restore through the engagement API', () => {
    assertContainsAll(listSource, ["'delete'", "'restore'", "'DELETED'"], 'MemberNoticePage.tsx')
    assert.match(apiSource, /apiDelete/)
    assert.match(apiSource, /\/restore/)
  })
})

describe('member notice delivery semantics', () => {
  it('prominently states that ordinary messages never open a popup', () => {
    assert.match(featureSources, /className="member-notice-no-popup-notice"[^>]+role="status"[\s\S]+普通消息不会自动弹窗/)
  })

  it('keeps legacy SENT backfill readable and never fabricates a receipt reset', () => {
    assertContainsAll(listSource, ["'SENT'", 'getNormalMessages('], 'MemberNoticePage.tsx')
    assert.match(editorSource, /getNormalMessage\(/)
    assert.match(editorSource, /修改已发送内容不会重置已读状态/)
    assert.doesNotMatch(featureSources, /reset[-_ ]?(?:receipt|read)|receiptReset|readAt\s*:\s*null|\/receipts/i)
    assert.doesNotMatch(apiSource, /legacy\s*=\s*false|excludeLegacy|sourceType\s*,?\s*['"]MANUAL/i)
  })
})
