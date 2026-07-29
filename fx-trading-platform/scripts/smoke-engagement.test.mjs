import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const scriptsDirectory = dirname(fileURLToPath(import.meta.url))
const projectDirectory = join(scriptsDirectory, '..')
const scriptPath = join(scriptsDirectory, 'smoke-engagement.mjs')
const packageJson = JSON.parse(readFileSync(join(projectDirectory, 'package.json'), 'utf8'))
const source = existsSync(scriptPath) ? readFileSync(scriptPath, 'utf8') : ''
const engagementClientSource = readFileSync(
  join(projectDirectory, 'packages/frontend-core/src/engagement/engagementApi.ts'),
  'utf8'
)

describe('independent engagement smoke command', () => {
  it('exposes dedicated contract and dynamic commands', () => {
    assert.equal(
      packageJson.scripts['test:engagement-smoke-contract'],
      'node --test scripts/smoke-engagement.test.mjs'
    )
    assert.equal(packageJson.scripts['smoke:engagement'], 'node scripts/smoke-engagement.mjs')
    assert.ok(existsSync(scriptPath), 'missing scripts/smoke-engagement.mjs')
  })

  it('owns its data, configuration, artifacts, and cleanup boundary', () => {
    for (const value of [
      'ENGAGEMENT_SMOKE_RUN_ID',
      'ENGAGEMENT_SMOKE_ADMIN_EMAIL',
      'ENGAGEMENT_SMOKE_ADMIN_PASSWORD',
      'ENGAGEMENT_SMOKE_USER_EMAIL',
      'ENGAGEMENT_SMOKE_USER_PASSWORD',
      'ENGAGEMENT_SMOKE_ISOLATED_DATABASE',
      'ENGAGEMENT_SMOKE_ARTIFACT_DIR',
      'ENGAGEMENT_SMOKE_SCHEDULE_TIMEOUT_MS',
      'API_BASE_URL',
      'WEB_BASE_URL',
      'CHROME_PATH',
      'engagement-smoke-'
    ]) {
      assert.match(source, new RegExp(value), `missing dedicated smoke boundary ${value}`)
    }

    assert.match(source, /assertLoopbackUrl/)
    assert.ok(
      source.indexOf('class ApiError extends Error') < source.indexOf('let failure = null'),
      'ApiError must initialize before top-level async API calls can fail'
    )
    assert.match(source, /CONFIRM_ISOLATED_ENGAGEMENT_DATABASE/)
    assert.match(source, /assertIsolatedEngagementDatabase/)
    assert.match(source, /lifecycleStatus=ACTIVE/)
    assert.match(source, /lifecycleStatus=SCHEDULED/)
    assert.match(source, /maxSequentialPopups\s*===\s*3/)
    assert.match(source, /finally\s*\{/)
    assert.match(source, /cleanupOwnedRecords/)
    assert.match(source, /safeRemoveOwnedBrowserProfile/)
    assert.match(source, /writeReport/)
    assert.doesNotMatch(
      source,
      /(?:import|spawn|exec|fork|require)[^\n]*(?:smoke-usdt|smoke-real-trading|smoke-business-closed-loop|p0-user-trading)/i,
      'engagement smoke must not invoke or import a trading smoke'
    )
    assert.doesNotMatch(source, /broker|\bfix\b|liquidity provider|\/api\/trading\//i)
  })

  it('names every required stateful acceptance scenario', () => {
    const scenarios = {
      'immediate-and-scheduled': 'immediateAndScheduled',
      'queue-cap-then-next-trigger': 'queueCapThenNextTrigger',
      'revision-snapshot': 'revisionSnapshot',
      'browser-queue-interactions': 'browserQueueInteractions',
      'browser-frequency-caps': 'browserFrequencyCaps',
      'browser-scheduled-all-audience': 'browserScheduledAllAudience',
      'pause-delete-restore': 'pauseDeleteRestore',
      'ordinary-message-no-popup': 'ordinaryMessageNoPopup',
      'hostile-content-rejected': 'hostileContentRejected',
      'two-tabs-refresh-reconnect': 'twoTabsRefreshReconnect',
      'message-receipts': 'messageReceipts',
      'frozen-disabled-recovery': 'frozenDisabledRecovery',
      'new-user-all-audience': 'newUserAllAudience',
      'responsive-template-image-matrix': 'responsiveTemplateImageMatrix'
    }
    for (const [scenario, handler] of Object.entries(scenarios)) {
      assert.match(source, new RegExp(`runScenario\\('${scenario}',\\s*${handler}\\)`), `scenario ${scenario} is not executed`)
      assert.match(source, new RegExp(`async function ${handler}\\(`), `scenario ${scenario} has no implementation`)
    }
  })

  it('drives the authoritative lifecycle and receipt endpoints instead of fixture-only claims', () => {
    for (const endpoint of [
      '/api/admin/engagement/campaigns',
      '/api/admin/engagement/messages',
      '/api/me/engagement/popup-queues',
      '/api/me/engagement/popup-deliveries/',
      '/api/me/messages/unread-count',
      '/pause',
      '/restore'
    ]) {
      assert.ok(source.includes(endpoint), `missing authoritative endpoint ${endpoint}`)
    }
    assert.match(source, /stats\.issuedDeliveries\s*\+\s*stats\.shownDeliveries/)
    assert.match(source, /for \(let index = 0; index < 4; index \+= 1\)/)
    assert.match(source, /Daily cap must independently reject delivery 3 today/)
    assert.match(source, /maxTotalImpressions:\s*3,[\s\S]*maxDailyImpressions:\s*2/)
    assert.match(source, /maxDailyImpressions\s*<=\s*maxTotalImpressions/)
    assert.doesNotMatch(source, /maxTotalImpressions:\s*2,\s*maxDailyImpressions:\s*3/)
    assert.match(source, /lifecycleStatus\s*===\s*'SCHEDULED'/)
    assert.match(source, /lifecycleStatus\s*===\s*'PAUSED'/)
    assert.match(source, /CONTENT_DOCUMENT_INVALID/)
    for (const method of [
      'markMessageRead',
      'markMessageUnread',
      'markAllMessagesRead',
      'hideMessage'
    ]) {
      assert.match(engagementClientSource, new RegExp(`${method}:`), `frontend client is missing ${method}`)
    }
    assert.match(engagementClientSource, /\/api\/me\/messages\/read-all/)
    assert.match(source, /clickMessageAction\([^\n]+['"]Mark as read['"]\)/)
    assert.match(source, /clickMessageAction\([^\n]+['"]Mark as unread['"]\)/)
    assert.match(source, /clickMessageAction\([^\n]+['"]Hide['"]\)/)
    assert.match(source, /clickButtonByText\([^\n]+['"]Mark all as read['"]\)/)
  })

  it('uses valid lifecycle values, disable confirmation, and isolated responsive image fixtures', () => {
    assert.match(source, /deviceScope:\s*options\.deviceScope\s*\?\?\s*'ALL'/)
    assert.doesNotMatch(source, /deviceScope:\s*options\.deviceScope\s*\?\?\s*'BOTH'/)
    assert.match(source, /'user:disable'/)
    assert.match(source, /confirmationText:\s*'CONFIRM_DISABLE_USER'/)
    assert.match(source, /response\?\.status\s*===\s*status/)
    assert.match(source, /primaryStatusDirty/)
    assert.match(source, /failure rollback before cleanup/)
    assert.match(source, /await deleteCampaign\(allCampaign\.id\)/)
    assert.match(source, /Future ALL campaign must publish as SCHEDULED/)
    assert.match(source, /scheduled ALL campaign activation for new user/)
    assert.match(source, /const auth = await registerOwnedUser\(userEmail, userPassword\)/)
    assert.doesNotMatch(source, /userEmailWasConfigured/)
    assert.match(source, /\/api\/admin\/engagement\/assets/)
    assert.match(source, /new FormData\(\)/)
    assert.match(source, /deflateSync/)
    assert.match(source, /const width = 320/)
    assert.match(source, /const height = 180/)
    assert.match(source, /payload\.data\.width\s*===\s*320\s*&&\s*payload\.data\.height\s*===\s*180/)
    assert.match(source, /for \(const deviceClass of \['PC', 'MOBILE'\]\)/)
    assert.match(source, /for \(const templateSize of \['SMALL', 'MEDIUM', 'LARGE'\]\)/)
    assert.match(source, /coverAssetId:\s*context\.platformAsset\.assetId/)
  })

  it('captures the requested responsive and accessibility evidence through CDP', () => {
    for (const value of [
      'Page.captureScreenshot',
      'Emulation.setDeviceMetricsOverride',
      'Emulation.setEmulatedMedia',
      '1440',
      '900',
      '390',
      '844',
      'desktop-1440x900',
      'mobile-390x844',
      'aria-modal',
      'prefers-reduced-motion',
      'Input.dispatchKeyEvent'
    ]) {
      assert.match(source, new RegExp(value), `missing browser evidence contract ${value}`)
    }
    assert.match(source, /pressKey\('Tab',\s*true\)/)
    assert.match(source, /mainRegionLocked/)
    assert.match(source, /dialogContentScrollable/)
    assert.match(source, /window\.dispatchEvent\(new Event\('focus'\)\)/)
    assert.match(source, /async function triggerBrowserReconciliation\(/)
    assert.match(source, /Network\.webSocketCreated/)
    assert.match(source, /Network\.webSocketClosed/)
    assert.match(source, /Network\.webSocketFrameReceived/)
    assert.match(source, /Page\.addScriptToEvaluateOnNewDocument/)
    assert.match(source, /class SmokeWebSocket extends NativeWebSocket/)
    assert.match(source, /__engagementSmokeWebSockets/)
    assert.match(source, /enumerable:\s*false/)
    assert.match(source, /socket\.close\(4001, 'engagement-smoke-controlled-disconnect'\)/)
    assert.match(source, /\['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'\]/)
    assert.match(source, /same browser document without reload\/navigation/)
    assert.match(source, /captured-native-socket-same-document-offline-online/)
    assert.match(source, /controlledOfflineNoiseUntil/)
    assert.match(source, /viewport\.mobile\s*\?\s*\{ enabled: true, maxTouchPoints: 1 \}\s*:\s*\{ enabled: false \}/)
    assert.doesNotMatch(source, /maxTouchPoints:\s*viewport\.mobile\s*\?\s*1\s*:\s*0/)
    assert.match(source, /maxRetries:\s*5,\s*retryDelay:\s*200/)
    assert.match(source, /waitForOwnedBrowserShutdown/)
    assert.match(source, /childExited\s*&&\s*debugPortDown/)
    const noiseFilter = source.match(/function isExpectedBrowserNoise\(message\) \{([\s\S]*?)\n\}/)?.[1] ?? ''
    assert.doesNotMatch(noiseFilter, /WebSocket|ERR_INTERNET_DISCONNECTED|ERR_NETWORK_CHANGED/)
    assert.match(source, /for \(const device of browserDevices\(\)\)/)
    assert.match(source, /clickPopupCloseAndWaitForQueue\(device\.page\)/)
    assert.match(source, /optOutPopupAndWaitForQueue\(device\.page\)/)
    assert.match(source, /clickPopupButtonByText\(device\.page, 'Open messages'\)/)
    assert.match(source, /completedPopupNextCount/)
    assert.match(source, /Mobile popup focus restoration/)
    assert.match(source, /\$\{deviceClass\} \$\{templateSize\} focus restoration/)
    assert.match(source, /browserFrequencyCaps/)
    assert.match(source, /browser-interval-\$\{device\.slug\}/)
    assert.match(source, /browser-total-\$\{device\.slug\}/)
    assert.match(source, /browser-daily-\$\{device\.slug\}/)
    assert.match(source, /browserScheduledAllAudience/)
    assert.match(source, /audienceType:\s*'ALL',[\s\S]*deviceScope:\s*device\.deviceClass/)
    assert.match(source, /displayScope:\s*options\.displayScope\s*\?\?\s*'ALL_BUSINESS_PAGES'/)
    assert.match(source, /pageKeys:\s*options\.pageKeys\s*\?\?\s*\[\]/)
    assert.doesNotMatch(source, /(?:startQueue|popupSurface)\([^\n]*['"](?:FOCUS|SCHEDULE|RESIZE)['"]/)
  })

  it('bounds screenshot recovery and closes the owned Windows browser before removing its profile', () => {
    const screenshot = source.slice(
      source.indexOf('async function captureScreenshot'),
      source.indexOf('function isExpectedBrowserNoise')
    )
    assert.match(screenshot, /await page\.bringToFront\(\)/)
    assert.match(screenshot, /requestAnimationFrame/)
    assert.match(screenshot, /optimizeForSpeed:\s*true/)
    assert.match(screenshot, /Page\.captureScreenshot[\s\S]*30_000/)
    assert.match(screenshot, /Page\.stopLoading/)
    assert.ok(
      screenshot.match(/Page\.captureScreenshot/g)?.length === 2,
      'screenshot timeout must have exactly one bounded retry'
    )

    const launch = source.slice(source.indexOf('async function launchChrome'), source.indexOf('async function createCdpPage'))
    assert.match(launch, /--disable-breakpad/)
    assert.match(launch, /webSocketDebuggerUrl/)
    assert.match(launch, /browserWebSocketDebuggerUrl/)
    assert.match(launch, /debugPortOwnerPid/)

    const cleanup = source.slice(source.indexOf('async function closeBrowser'), source.indexOf('async function writeReport'))
    const browserCloseIndex = cleanup.indexOf('requestBrowserClose')
    const targetCloseIndex = cleanup.indexOf('page?.close(false)')
    assert.ok(browserCloseIndex >= 0, 'cleanup must request browser-level shutdown')
    assert.ok(targetCloseIndex > browserCloseIndex, 'browser-level shutdown must precede target socket cleanup')
    assert.match(cleanup, /waitForOwnedBrowserShutdown\(browser,\s*5_000\)/)
    assert.match(cleanup, /killProcessTree\(browser\.child\)/)

    const shutdown = source.slice(source.indexOf('async function requestBrowserClose'), source.indexOf('async function safeRemoveOwnedBrowserProfile'))
    assert.match(shutdown, /method:\s*'Browser\.close'/)
    assert.match(shutdown, /async function isTcpPortOpen/)
    assert.match(shutdown, /childExited\s*&&\s*debugPortDown/)
    assert.match(shutdown, /ownerPid\s*!==\s*browser\.debugPortOwnerPid/)
    assert.match(shutdown, /columns\[3\][\s\S]*LISTENING/)
  })

  it('serializes scheduled ALL browser campaigns across the per-user active-delivery lease', () => {
    const scheduled = source.slice(
      source.indexOf('async function browserScheduledAllAudience'),
      source.indexOf('async function waitForCampaignImpressions')
    )
    const deviceLoop = scheduled.indexOf('for (const device of browserDevices())')
    const dueAt = scheduled.indexOf('const dueAt = new Date(Date.now() + 15_000).toISOString()')
    const waitForPopupIndex = scheduled.indexOf('await waitForPopup(device.page, titleFor(label))')
    const deleteCampaignIndex = scheduled.indexOf('await deleteCampaign(campaign.id)')

    assert.ok(deviceLoop >= 0, 'scheduled ALL acceptance must cover each browser device')
    assert.ok(dueAt > deviceLoop, 'each device needs its own due time after the prior delivery is closed')
    assert.ok(waitForPopupIndex > dueAt, 'each device must receive its scheduled popup through realtime')
    assert.ok(deleteCampaignIndex > waitForPopupIndex, 'the active delivery must be closed and deleted before the next device')
    assert.doesNotMatch(scheduled, /const campaigns = \[\]/)
  })

  it('keeps credentials and tokens out of the JSON report', () => {
    assert.match(source, /redactReport/)
    assert.match(source, /accessToken|refreshToken/)
    assert.doesNotMatch(source, /report\.(?:adminPassword|userPassword|accessToken|refreshToken)\s*=/)
    assert.match(source, /parseJson[\s\S]*redactSensitiveUrl\(response\.url\)/)
    const helper = source.match(/function redactSensitiveUrl\(value\) \{\r?\n([\s\S]*?)\r?\n\}/)
    assert.ok(helper, 'redactSensitiveUrl helper is missing')
    const redactSensitiveUrl = new Function('value', helper[1])
    const sentinel = 'literal-delivery-token-sentinel'
    const sanitized = redactSensitiveUrl(`http://localhost/api/me/engagement/popup-deliveries/${sentinel}/shown`)
    assert.doesNotMatch(sanitized, new RegExp(sentinel), 'delivery token leaked through URL redaction')
    assert.match(sanitized, /popup-deliveries\/\[REDACTED\]\/shown/)
  })

  it('proves realtime ordinary messages without focus or reload reconciliation', () => {
    assert.match(source, /waitForExactUnreadWithoutPopup\(\[context\.desktop, context\.mobile\], afterUnread/)
    assert.match(source, /waitForExactUnreadWithoutPopup\(\[context\.desktop, context\.mobile\], scheduledUnread/)
    assert.match(source, /value === `Messages, \$\{expectedUnread\} unread`/)
    assert.match(source, /Browser ordinary message must first be SCHEDULED/)
    assert.match(source, /mobileBellRoute:\s*'\/messages'/)
    const handler = source.match(/async function ordinaryMessageNoPopup\(\) \{([\s\S]*?)\n\}/)?.[1] ?? ''
    assert.doesNotMatch(handler, /triggerBrowserReconciliation|dispatchEvent\(new Event\('focus'\)\)/)
  })

  it('keeps online pause and delete invalidation as a pure realtime assertion', () => {
    assert.match(source, /for \(const device of browserDevices\(\)\) evidence\.push\(await verifyPauseDeleteRestore\(device\)\)/)
    const handler = source.match(/async function verifyPauseDeleteRestore\(device\) \{([\s\S]*?)\n\}/)?.[1] ?? ''
    assert.match(handler, /deleteCampaign[\s\S]*waitForNoPopup/)
    assert.match(handler, /\/pause[\s\S]*waitForNoPopup/)
    assert.doesNotMatch(handler, /triggerBrowserReconciliation|dispatchEvent|\.reload\(/)
  })

  it('executes message receipt mutations on both rendered surfaces', () => {
    assert.match(source, /clickMessageAction\(context\.desktop[\s\S]*'Mark as read'/)
    assert.match(source, /clickMessageAction\(context\.mobile[\s\S]*'Mark as unread'/)
    assert.match(source, /clickMessageAction\(context\.mobile[\s\S]*'Mark as read'/)
    assert.match(source, /clickButtonByText\(context\.mobile, 'Mark all as read'\)/)
    assert.match(source, /clickMessageAction\(context\.mobile[\s\S]*'Hide'/)
    assert.match(source, /mobileHiddenPublicationId/)
  })
})
