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
const listSource = readSource('PopupCampaignPage.tsx')
const editorSource = readSource('PopupCampaignEditorPage.tsx')
const policySource = readSource('PopupCampaignPolicyPage.tsx')
const statsSource = readSource('PopupCampaignStatsPage.tsx')
const modelSource = readSource('popupCampaignModel.ts')
const apiSource = readSource('../services/engagementAdminApi.ts')
const allCampaignPageSources = [listSource, editorSource, policySource, statsSource, modelSource].join('\n')

describe('popup campaign dedicated admin routes', () => {
  it('uses dedicated list, editor, policy, summary and user-detail routes', () => {
    assertContainsAll(appSource, [
      "import { PopupCampaignPage } from '../pages/PopupCampaignPage'",
      "import { PopupCampaignEditorPage } from '../pages/PopupCampaignEditorPage'",
      "import { PopupCampaignPolicyPage } from '../pages/PopupCampaignPolicyPage'",
      "import { PopupCampaignStatsPage } from '../pages/PopupCampaignStatsPage'",
      '<Route path="/content/popup-campaigns" element={<PopupCampaignPage />} />',
      '<Route path="/content/popup-campaigns/new" element={<PopupCampaignEditorPage />} />',
      '<Route path="/content/popup-campaigns/:id/edit" element={<PopupCampaignEditorPage />} />',
      '<Route path="/content/popup-campaigns/policy" element={<PopupCampaignPolicyPage />} />',
      '<Route path="/content/popup-campaigns/:id/stats" element={<PopupCampaignStatsPage />} />',
      '<Route path="/content/popup-campaigns/:id/users" element={<PopupCampaignStatsPage userDetail />} />'
    ], 'AdminApp.tsx')

    assert.doesNotMatch(appSource, /FeatureCrudPage[^\n]+popup-campaign/i)
  })

  it('puts campaigns and popup policy in the content menu without a generic pageKey', () => {
    assert.match(menuSource, /\{\s*to:\s*'\/content\/popup-campaigns',\s*label:[^}]+icon:[^}]+\}/)
    assert.match(menuSource, /\{\s*to:\s*'\/content\/popup-campaigns\/policy',\s*label:[^}]+icon:[^}]+\}/)
    assert.doesNotMatch(menuSource, /to:\s*'\/content\/popup-campaigns'[^}]+pageKey/)
  })
})

describe('popup campaign four-step editor', () => {
  it('lazy-loads the restricted editor directly and exposes no textarea or raw HTML input', () => {
    assert.match(editorSource, /\blazy\(\(\)\s*=>\s*import\('\.\.\/components\/RestrictedRichTextEditor'\)\)/)
    assert.match(editorSource, /<Suspense\b/)
    assert.match(editorSource, /<RestrictedRichTextEditor\b/)
    assert.match(editorSource, /bodyDocument/)
    assert.doesNotMatch(editorSource, /<textarea\b|dangerouslySetInnerHTML|getHTML\(|rawHtml/i)
  })

  it('keeps content, audience, delivery and review as four explicit steps', () => {
    assertContainsAll(editorSource, [
      "id: 'CONTENT'",
      "id: 'AUDIENCE'",
      "id: 'DELIVERY'",
      "id: 'REVIEW'",
      'currentStep'
    ], 'PopupCampaignEditorPage.tsx')

    assertContainsAll(editorSource, [
      'name',
      'title',
      'bodyDocument',
      'audienceType',
      'targetUserIds',
      'syncToInbox',
      'pageKeys',
      'deviceScope',
      'startAt',
      'endAt',
      'timeZone',
      'priority',
      'maxTotalImpressions',
      'maxDailyImpressions',
      'minIntervalSeconds',
      'templateSize',
      'PC',
      'MOBILE'
    ], 'PopupCampaignEditorPage.tsx')
    assert.match(editorSource, /value: 'DASHBOARD', label: '仪表盘'/)
  })

  it('searches business users, stores selected UUIDs and freezes published targeting', () => {
    assert.match(apiSource, /\/api\/admin\/users\/search/)
    assert.match(editorSource, /searchAdminUsers\(/)
    assert.match(editorSource, /selectedUserIds/)
    assert.match(editorSource, /type="checkbox"/)
    assert.match(editorSource, /targetUserIds:\s*(?:Array\.from\()?selectedUserIds/)
    assert.match(modelSource, /UUID_PATTERN/)
    assert.match(modelSource, /targetUserIds/)
    assert.match(modelSource, /campaignFrozenFields/)
    assertContainsAll(modelSource, [
      "'audienceType'",
      "'targetUserIds'",
      "'syncToInbox'",
      'firstPublishedAt'
    ], 'popupCampaignModel.ts')
    assert.match(editorSource, /isCampaignFieldFrozen\(/)
    assert.match(editorSource, /disabled=\{[^}]*audience[^}]*Frozen|disabled=\{isCampaignFieldFrozen/s)
    assert.match(editorSource, /if \(!canEdit \|\| audienceFrozen\) return/)
    assert.ok(
      (editorSource.match(/disabled=\{disabled \|\| audienceFrozen\}/g) ?? []).length >= 6,
      'every audience control, including both UUID checkbox lists, must be read-only without edit authority'
    )
  })

  it('validates scheduled publication and renders an unmistakable PREVIEW', () => {
    assert.match(modelSource, /validateCampaignPublication/)
    assertContainsAll(modelSource, ['startAt', 'endAt', 'timeZone'], 'popupCampaignModel.ts')
    assert.match(modelSource, /previewLabel:\s*'PREVIEW'/)
    assert.match(modelSource, /createContentPreviewModel/)
    assert.match(editorSource, /validateCampaignPublication\(/)
    assert.match(editorSource, /createCampaignPreview\(/)
    assert.match(editorSource, /testPopup\(/)
    assert.match(editorSource, /PREVIEW/)
  })

  it('offers audited save only on the review step where its reason can be entered', () => {
    const footerStart = editorSource.indexOf('{currentStep < 3 ?')
    const footerEnd = editorSource.indexOf('</footer>', footerStart)
    assert.ok(footerStart >= 0 && footerEnd > footerStart, 'step navigation footer must exist')
    assert.doesNotMatch(editorSource.slice(footerStart, footerEnd), /saveDraft|保存草稿/)
    assert.match(editorSource, /popup-campaign-review-actions[\s\S]+操作原因[\s\S]+saveDraft/)
  })

  it('hides publish controls when the current lifecycle cannot be published', () => {
    assert.match(editorSource, /campaignActions\(campaign, authorities\)\.includes\('publish'\)/)
    assert.match(editorSource, /canPublish=\{canPublishCampaign\}/)
    assert.match(editorSource, /const detail = canEdit[\s\S]+persistCampaign\(publishForm\)[\s\S]+: campaign/)
  })

  it('lets a publish-only administrator enter the review flow from the list', () => {
    assert.match(listSource, /'publish': '发布审核'/)
    assert.match(listSource, /action === 'edit' \|\| action === 'publish'/)
  })
})

describe('popup campaign list operations', () => {
  it('sends status, name, audience, sync and effective-window filters to the server', () => {
    assertContainsAll(listSource, [
      'lifecycleStatus',
      'name',
      'audienceType',
      'syncToInbox',
      'effectiveFrom',
      'effectiveTo',
      'getPopupCampaigns'
    ], 'PopupCampaignPage.tsx')
    assertContainsAll(apiSource, [
      'lifecycleStatus',
      'name',
      'audienceType',
      'syncToInbox',
      'effectiveFrom',
      'effectiveTo',
      '/api/admin/engagement/campaigns'
    ], 'engagementAdminApi.ts')
    assert.match(apiSource, /URLSearchParams/)
  })

  it('offers every lifecycle operation and links to editing and statistics', () => {
    assertContainsAll(listSource, [
      "'edit'",
      "'pause'",
      "'resume'",
      "'end'",
      "'delete'",
      "'restore'",
      "'stats'"
    ], 'PopupCampaignPage.tsx')
    assert.match(listSource, /'reset-delivery': '重置投放次数'/)
    assertContainsAll(apiSource, [
      '/pause',
      '/resume',
      '/end',
      '/restore'
    ], 'engagementAdminApi.ts')
    assert.match(apiSource, /apiDelete/)
  })
})

describe('popup policy, permissions and statistics', () => {
  it('edits only maxSequentialPopups and raw-delivery retention through the policy API', () => {
    assertContainsAll(policySource, [
      'maxSequentialPopups',
      'deliveryRetentionDays',
      'reason',
      'getPopupPolicy',
      'updatePopupPolicy'
    ], 'PopupCampaignPolicyPage.tsx')
    assert.match(apiSource, /\/api\/admin\/engagement\/popup-policy/)
  })

  it('hides unauthorized actions and requires a reason before user-detail access', () => {
    assert.match(allCampaignPageSources, /getAdminAuthorities/)
    assertContainsAll(allCampaignPageSources, [
      'content:campaign:edit',
      'content:campaign:publish',
      'content:campaign:delete',
      'content:campaign:stats',
      'content:campaign:user-detail',
      'content:popup-policy:update'
    ], 'popup campaign page sources')
    assert.match(statsSource, /userDetail/)
    assert.match(statsSource, /reason\.trim\(\)/)
    assert.match(statsSource, /getPopupCampaignUsers\([^)]*reason/s)
    assert.match(apiSource, /\/users/)
    assert.match(apiSource, /reason/)
  })

  it('keeps aggregate stats optional for a user-detail-only administrator', () => {
    assert.match(statsSource, /if \(userDetail && !canViewStats\) return Promise\.resolve\(undefined\)/)
    assert.match(statsSource, /const showStats = !userDetail \|\| canViewStats/)
    assert.match(statsSource, /\{showStats && loading/)
  })

  it('distinguishes durable state summary from retention-window raw delivery details', () => {
    assert.match(statsSource, /累计展示与用户终态长期保留/)
    assert.match(statsSource, /领取、展示、关闭、点击、失效和过期仅统计原始明细保留期/)
  })

  it('drops user-detail results when the campaign route changes', () => {
    assert.match(statsSource, /const userRequestGeneration = useRef\(0\)/)
    assert.match(statsSource, /userRequestGeneration\.current \+= 1[\s\S]+setUserPage\(undefined\)/)
    assert.match(statsSource, /\[campaignId, userDetail\]/)
    assert.match(statsSource, /const requestGeneration = \+\+userRequestGeneration\.current/)
    assert.match(statsSource, /requestGeneration !== userRequestGeneration\.current/)
  })

  it('does not add campaign export routes, buttons or API calls', () => {
    const campaignSources = [appSource, menuSource, allCampaignPageSources, apiSource].join('\n')
    assert.doesNotMatch(campaignSources, /popup-campaigns\/export|exportCampaign|exportCsv|downloadCsv|\/campaigns\/[^'"`]*export|\bCSV\b|导出/i)
  })
})
