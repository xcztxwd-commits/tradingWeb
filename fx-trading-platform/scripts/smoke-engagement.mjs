import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import net from 'node:net'
import { basename, dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { deflateSync } from 'node:zlib'

const projectDirectory = join(dirname(fileURLToPath(import.meta.url)), '..')
const runId = normalizeRunId(process.env.ENGAGEMENT_SMOKE_RUN_ID ?? Date.now().toString(36))
const prefix = `engagement-smoke-${runId}`
const apiBaseUrl = stripTrailingSlash(process.env.API_BASE_URL ?? 'http://localhost:8080')
const webBaseUrl = stripTrailingSlash(process.env.WEB_BASE_URL ?? 'http://localhost:5173')
const adminEmail = process.env.ENGAGEMENT_SMOKE_ADMIN_EMAIL ?? 'admin-smoke@example.com'
const adminPassword = process.env.ENGAGEMENT_SMOKE_ADMIN_PASSWORD ?? 'Password123!'
const userEmail = process.env.ENGAGEMENT_SMOKE_USER_EMAIL ?? `${prefix}@example.test`
const userPassword = process.env.ENGAGEMENT_SMOKE_USER_PASSWORD ?? 'Password123!'
const isolatedDatabaseMarker = 'CONFIRM_ISOLATED_ENGAGEMENT_DATABASE'
const scheduleTimeoutMs = positiveInteger(
  process.env.ENGAGEMENT_SMOKE_SCHEDULE_TIMEOUT_MS ?? '90000',
  'ENGAGEMENT_SMOKE_SCHEDULE_TIMEOUT_MS'
)
const artifactDirectory = resolve(
  process.env.ENGAGEMENT_SMOKE_ARTIFACT_DIR ?? join(projectDirectory, 'test-results', prefix)
)
const actionReason = (action) => `${prefix}: ${action}`
const requiredAdminAuthorities = Object.freeze([
  'content:campaign:read',
  'content:campaign:edit',
  'content:campaign:publish',
  'content:campaign:delete',
  'content:campaign:stats',
  'content:message:read',
  'content:message:edit',
  'content:message:send',
  'content:message:delete',
  'user:update',
  'user:disable'
])
const scenarioNames = Object.freeze([
  'hostile-content-rejected',
  'immediate-and-scheduled',
  'queue-cap-then-next-trigger',
  'new-user-all-audience',
  'frozen-disabled-recovery',
  'two-tabs-refresh-reconnect',
  'revision-snapshot',
  'browser-queue-interactions',
  'browser-frequency-caps',
  'browser-scheduled-all-audience',
  'responsive-template-image-matrix',
  'ordinary-message-no-popup',
  'message-receipts',
  'pause-delete-restore'
])

const context = {
  adminToken: '',
  primary: null,
  newcomer: null,
  browser: null,
  desktop: null,
  mobile: null,
  revisionCampaign: null,
  revisionPayload: null,
  platformAsset: null,
  ordinaryMessage: null,
  visualMessage: null,
  primaryStatusDirty: false
}
const ownedCampaignIds = new Set()
const ownedMessageIds = new Set()
const ownedUserIds = new Set()
const report = {
  smoke: 'engagement',
  runId,
  prefix,
  status: 'RUNNING',
  startedAt: new Date().toISOString(),
  finishedAt: null,
  environment: {
    apiBaseUrl,
    webBaseUrl,
    scheduleTimeoutMs,
    browserPath: null,
    browserPlugin: 'not available; using repository CDP pattern',
    viewports: ['desktop-1440x900', 'mobile-390x844']
  },
  scenarios: [],
  artifacts: [],
  cleanup: [],
  error: null
}

class ApiError extends Error {
  constructor(status, code, message) {
    super(`${code}: ${message}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

let failure = null
try {
  await preflight()
  await authenticateAdmin()
  await assertIsolatedEngagementDatabase()
  context.primary = await preparePrimaryDemoUser()

  await runScenario('hostile-content-rejected', hostileContentRejected)
  await runScenario('immediate-and-scheduled', immediateAndScheduled)
  await runScenario('queue-cap-then-next-trigger', queueCapThenNextTrigger)
  await runScenario('new-user-all-audience', newUserAllAudience)
  await runScenario('frozen-disabled-recovery', frozenDisabledRecovery)

  await openBrowserMatrix()
  await runScenario('two-tabs-refresh-reconnect', twoTabsRefreshReconnect)
  await runScenario('revision-snapshot', revisionSnapshot)
  await runScenario('browser-queue-interactions', browserQueueInteractions)
  await runScenario('browser-frequency-caps', browserFrequencyCaps)
  await runScenario('browser-scheduled-all-audience', browserScheduledAllAudience)
  await runScenario('responsive-template-image-matrix', responsiveTemplateImageMatrix)
  await runScenario('ordinary-message-no-popup', ordinaryMessageNoPopup)
  await runScenario('message-receipts', messageReceipts)
  await runScenario('pause-delete-restore', pauseDeleteRestore)
  await verifyBrowserHealth()
  report.status = 'PASS'
} catch (error) {
  failure = asError(error)
  report.status = 'FAIL'
  report.error = serializeError(failure)
} finally {
  try {
    await cleanupOwnedRecords()
  } catch (error) {
    const cleanupError = asError(error)
    report.cleanup.push({ target: 'owned records', status: 'FAIL', error: cleanupError.message })
    failure = combineErrors(failure, cleanupError, 'owned record cleanup')
    report.status = 'FAIL'
  }

  try {
    await closeBrowser()
  } catch (error) {
    const browserError = asError(error)
    report.cleanup.push({ target: 'browser', status: 'FAIL', error: browserError.message })
    failure = combineErrors(failure, browserError, 'browser cleanup')
    report.status = 'FAIL'
  }

  report.finishedAt = new Date().toISOString()
  try {
    await writeReport()
  } catch (error) {
    failure = combineErrors(failure, asError(error), 'report write')
    report.status = 'FAIL'
  }
}

if (failure) {
  process.stderr.write(`${redactSensitiveUrl(failure.stack ?? failure.message)}\n`)
  process.exitCode = 1
} else {
  process.stdout.write(`${JSON.stringify(redactReport(report), null, 2)}\n`)
}

async function preflight() {
  assertLoopbackUrl(apiBaseUrl, 'API_BASE_URL')
  assertLoopbackUrl(webBaseUrl, 'WEB_BASE_URL')
  assert(typeof WebSocket === 'function', 'This smoke requires a Node runtime with WebSocket support')
  await mkdir(artifactDirectory, { recursive: true })
  const health = await rawJson(`${apiBaseUrl}/actuator/health`)
  assert(health.status === 'UP', 'Backend actuator health must be UP before any smoke write')
  const web = await fetchWithTimeout(`${webBaseUrl}/`, {}, 10_000)
  assert(web.ok, `Web app must be reachable before any smoke write; HTTP ${web.status}`)
  const html = await web.text()
  assert(/<html|<div[^>]+id=["']root["']/i.test(html), 'Web app did not return its application shell')
  report.environment.browserPath = chromeExecutable()
}

async function authenticateAdmin() {
  const auth = await api('/api/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  })
  assert(auth.role === 'ADMIN' && auth.accessToken, 'Configured demo Admin login failed')
  assert(Array.isArray(auth.authorities), 'Admin login must expose authorities')
  for (const authority of requiredAdminAuthorities) {
    assert(auth.authorities.includes(authority), `Demo Admin is missing ${authority}`)
  }
  context.adminToken = auth.accessToken
}

async function assertIsolatedEngagementDatabase() {
  assert(
    process.env.ENGAGEMENT_SMOKE_ISOLATED_DATABASE === isolatedDatabaseMarker,
    `Set ENGAGEMENT_SMOKE_ISOLATED_DATABASE=${isolatedDatabaseMarker} only for a fresh, dedicated engagement smoke database`
  )
  const checks = [
    ['/api/admin/engagement/campaigns?page=0&size=1&lifecycleStatus=ACTIVE', 'ACTIVE campaigns'],
    ['/api/admin/engagement/campaigns?page=0&size=1&lifecycleStatus=SCHEDULED', 'SCHEDULED campaigns'],
    ['/api/admin/engagement/messages?page=0&size=1&lifecycleStatus=SCHEDULED', 'SCHEDULED messages']
  ]
  for (const [path, label] of checks) {
    const page = await api(path, { token: context.adminToken })
    assert(page.total === 0, `Isolated engagement database already contains ${label}; refusing to mutate or clean non-owned data`)
  }
  const policy = await api('/api/admin/engagement/popup-policy', { token: context.adminToken })
  assert(policy.maxSequentialPopups === 3, 'Isolated popup policy must keep maxSequentialPopups=3 for this smoke')
  report.environment.isolatedDatabaseConfirmed = true
  report.environment.maxSequentialPopups = policy.maxSequentialPopups
}

async function preparePrimaryDemoUser() {
  const auth = await registerOwnedUser(userEmail, userPassword)
  const accounts = await api('/api/accounts', { token: auth.accessToken })
  let demo = accounts.find((account) => account.accountType === 'DEMO')
  if (!demo) {
    demo = await api('/api/accounts/demo', { method: 'POST', token: auth.accessToken, body: {} })
  }
  assert(demo?.accountType === 'DEMO', 'Engagement smoke user must own a DEMO account')
  return { ...auth, email: userEmail, password: userPassword, demoAccountId: demo.id }
}

async function hostileContentRejected() {
  const hostileDocuments = [
    '<script>alert(1)</script>',
    JSON.stringify({
      type: 'doc',
      content: [{
        type: 'image',
        attrs: {
          assetId: '00000000-0000-0000-0000-000000000001',
          src: 'https://evil.example/external.png'
        }
      }]
    }),
    JSON.stringify({
      type: 'doc',
      content: [{
        type: 'paragraph',
        content: [{
          type: 'text',
          text: 'unsafe route',
          marks: [{ type: 'link', attrs: { routeKey: 'https://evil.example', params: {} } }]
        }]
      }]
    })
  ]

  const rejected = []
  for (const [index, bodyDocument] of hostileDocuments.entries()) {
    const error = await expectApiError('/api/admin/engagement/messages', {
      method: 'POST',
      token: context.adminToken,
      body: messagePayload(`rejected-${index}`, [context.primary.userId], { bodyDocument })
    }, [400])
    assert(error.code === 'CONTENT_DOCUMENT_INVALID', `Hostile document ${index} used ${error.code}`)
    rejected.push(error.code)
  }
  return { rejected }
}

async function immediateAndScheduled() {
  const immediate = await createMessage(messagePayload('immediate', [context.primary.userId]))
  const sentImmediate = await sendMessage(immediate.id, null)
  assert(sentImmediate.lifecycleStatus === 'SENT', 'Immediate message must be SENT')

  const dueAt = new Date(Date.now() + 15_000).toISOString()
  const scheduledMessage = await createMessage(messagePayload('scheduled', [context.primary.userId]))
  const queuedMessage = await sendMessage(scheduledMessage.id, dueAt)
  assert(queuedMessage.lifecycleStatus === 'SCHEDULED', 'Future message must be SCHEDULED')

  const campaignPayload = createCampaignPayload('scheduled', [context.primary.userId], {
    startAt: dueAt,
    priority: 50
  })
  const scheduledCampaign = await createCampaign(campaignPayload)
  const queuedCampaign = await publishCampaign(scheduledCampaign.id)
  assert(queuedCampaign.lifecycleStatus === 'SCHEDULED', 'Future campaign must be SCHEDULED')

  await waitFor(async () => {
    const detail = await api(`/api/admin/engagement/messages/${scheduledMessage.id}`, {
      token: context.adminToken
    })
    return detail.lifecycleStatus === 'SENT'
  }, 'scheduled message dispatch (enable ENGAGEMENT_SCHEDULER_ENABLED and use a short APP_ENGAGEMENT_SCHEDULER_FIXED_DELAY)', scheduleTimeoutMs)
  await waitFor(async () => {
    const detail = await api(`/api/admin/engagement/campaigns/${scheduledCampaign.id}`, {
      token: context.adminToken
    })
    return detail.lifecycleStatus === 'ACTIVE'
  }, 'scheduled campaign activation', scheduleTimeoutMs)

  const page = await listMessages(context.primary.accessToken)
  assert(page.items.some((item) => item.title === titleFor('immediate')), 'Immediate message is absent')
  assert(page.items.some((item) => item.title === titleFor('scheduled')), 'Scheduled message is absent')
  const claim = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(claim?.campaignId === scheduledCampaign.id, 'Scheduled campaign was not claimable after activation')
  await finishClaim(context.primary.accessToken, claim, 'close')
  const noPopupForMessages = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(noPopupForMessages === null, 'Ordinary messages must not create popup claims')
  return { immediateMessageId: immediate.id, scheduledMessageId: scheduledMessage.id, campaignId: scheduledCampaign.id }
}

async function queueCapThenNextTrigger() {
  const campaigns = []
  for (let index = 0; index < 4; index += 1) {
    const created = await createCampaign(createCampaignPayload(`queue-${index + 1}`, [context.primary.userId], {
      priority: 400 - index,
      maxTotalImpressions: 1
    }))
    campaigns.push(await publishCampaign(created.id))
  }

  const surface = popupSurface('LOGIN', 'PC')
  let claim = await startQueue(context.primary.accessToken, surface.triggerType, 'PC')
  assert(claim, 'Queue must issue its first campaign')
  const firstSessionId = claim.queueSessionId
  const firstThree = []
  for (let index = 0; index < 3; index += 1) {
    assert(claim, `Queue item ${index + 1} is missing`)
    firstThree.push(claim.campaignId)
    await finishClaim(context.primary.accessToken, claim, 'close')
    claim = await nextQueue(context.primary.accessToken, firstSessionId, surface)
  }
  assert(claim === null, 'A queue session must stop after three sequential popups')

  const fourth = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(fourth && !firstThree.includes(fourth.campaignId), 'Fourth campaign must wait for the next trigger')
  await finishClaim(context.primary.accessToken, fourth, 'close')

  const intervalCampaign = await createCampaign(createCampaignPayload('interval', [context.primary.userId], {
    priority: 320,
    maxTotalImpressions: 3,
    maxDailyImpressions: 3,
    minIntervalSeconds: 2
  }))
  await publishCampaign(intervalCampaign.id)
  const intervalFirst = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(intervalFirst?.campaignId === intervalCampaign.id, 'Interval campaign first claim is missing')
  await finishClaim(context.primary.accessToken, intervalFirst, 'close')
  assert(await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC') === null, 'Interval cap must reject an immediate replay')
  await sleep(2_100)
  const intervalSecond = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(intervalSecond?.campaignId === intervalCampaign.id, 'Interval campaign must resume after its interval')
  await finishClaim(context.primary.accessToken, intervalSecond, 'close')
  await deleteCampaign(intervalCampaign.id)

  const totalCampaign = await createCampaign(createCampaignPayload('total-cap', [context.primary.userId], {
    priority: 319,
    maxTotalImpressions: 2,
    maxDailyImpressions: 2
  }))
  await publishCampaign(totalCampaign.id)
  for (let index = 0; index < 2; index += 1) {
    const totalClaim = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
    assert(totalClaim?.campaignId === totalCampaign.id, `Total-cap delivery ${index + 1} is missing`)
    await finishClaim(context.primary.accessToken, totalClaim, 'close')
  }
  assert(await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC') === null, 'Total cap must independently reject delivery 3')

  const dailyCampaign = await createCampaign(createCampaignPayload('daily-cap', [context.primary.userId], {
    priority: 318,
    maxTotalImpressions: 3,
    maxDailyImpressions: 2
  }))
  await publishCampaign(dailyCampaign.id)
  for (let index = 0; index < 2; index += 1) {
    const dailyClaim = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
    assert(dailyClaim?.campaignId === dailyCampaign.id, `Daily-cap delivery ${index + 1} is missing`)
    await finishClaim(context.primary.accessToken, dailyClaim, 'close')
  }
  assert(await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC') === null, 'Daily cap must independently reject delivery 3 today')

  const optOutCampaign = await createCampaign(createCampaignPayload('opt-out', [context.primary.userId], {
    priority: 310,
    maxTotalImpressions: 3
  }))
  await publishCampaign(optOutCampaign.id)
  const optOutClaim = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(optOutClaim?.campaignId === optOutCampaign.id, 'Opt-out campaign claim is missing')
  await finishClaim(context.primary.accessToken, optOutClaim, 'opt-out')
  assert(await startQueue(context.primary.accessToken, 'ROUTE_CHANGE', 'MOBILE') === null, 'Opt-out must survive device changes')

  const ctaCampaign = await createCampaign(createCampaignPayload('cta-terminate', [context.primary.userId], {
    priority: 300,
    cta: { label: 'Open messages', routeKey: 'MESSAGE_CENTER', paramsJson: '{}' }
  }))
  const afterCtaCampaign = await createCampaign(createCampaignPayload('after-cta', [context.primary.userId], {
    priority: 299
  }))
  await publishCampaign(ctaCampaign.id)
  await publishCampaign(afterCtaCampaign.id)
  const ctaClaim = await startQueue(context.primary.accessToken, 'WINDOW_FOCUS', 'PC')
  assert(ctaClaim?.campaignId === ctaCampaign.id, 'CTA campaign must win priority')
  await finishClaim(context.primary.accessToken, ctaClaim, 'click')
  assert(
    await nextQueue(context.primary.accessToken, ctaClaim.queueSessionId, popupSurface('WINDOW_FOCUS', 'PC')) === null,
    'CTA must terminate the current queue'
  )
  await deleteCampaign(afterCtaCampaign.id)
  return { firstThree, fourth: fourth.campaignId, ctaTerminated: true }
}

async function newUserAllAudience() {
  const dueAt = new Date(Date.now() + 15_000).toISOString()
  const allCampaign = await createCampaign(createCampaignPayload('new-user-all', [], {
    audienceType: 'ALL',
    priority: 250,
    startAt: dueAt
  }))
  const scheduled = await publishCampaign(allCampaign.id)
  assert(scheduled.lifecycleStatus === 'SCHEDULED', 'Future ALL campaign must publish as SCHEDULED')
  const newcomerEmail = `${prefix}-new-user@example.test`
  context.newcomer = await registerOwnedUser(newcomerEmail, userPassword)
  assert(
    await startQueue(context.newcomer.accessToken, 'LOGIN', 'PC') === null,
    'New user must not claim a scheduled ALL campaign before its due time'
  )
  await waitFor(async () => {
    const detail = await api(`/api/admin/engagement/campaigns/${allCampaign.id}`, { token: context.adminToken })
    return detail.lifecycleStatus === 'ACTIVE'
  }, 'scheduled ALL campaign activation for new user', scheduleTimeoutMs)
  const claim = await startQueue(context.newcomer.accessToken, 'LOGIN', 'PC')
  assert(claim?.campaignId === allCampaign.id, 'ALL campaign must include a user registered after publish')
  await finishClaim(context.newcomer.accessToken, claim, 'close')
  await deleteCampaign(allCampaign.id)
  return { campaignId: allCampaign.id, newcomerId: context.newcomer.userId }
}

async function frozenDisabledRecovery() {
  for (const status of ['FROZEN', 'DISABLED']) {
    const campaign = await createCampaign(createCampaignPayload(`status-${status.toLowerCase()}`, [context.primary.userId], {
      priority: status === 'FROZEN' ? 240 : 230
    }))
    await publishCampaign(campaign.id)
    try {
      await setPrimaryStatus(status, `verify ${status}`)
      const inactiveResult = await claimWhileInactive(context.primary.accessToken)
      assert(inactiveResult === null || [401, 403].includes(inactiveResult), `${status} user received a popup`)
    } finally {
      if (context.primaryStatusDirty) await setPrimaryStatus('ACTIVE', `restore after ${status}`)
    }
    const login = await loginUser(context.primary.email, context.primary.password)
    context.primary.accessToken = login.accessToken
    context.primary.refreshToken = login.refreshToken
    const recovered = await startQueue(context.primary.accessToken, 'LOGIN', 'PC')
    assert(recovered?.campaignId === campaign.id, `${status} user did not receive the selected campaign after recovery`)
    await finishClaim(context.primary.accessToken, recovered, 'close')
  }
  return { statuses: ['FROZEN', 'DISABLED'], restored: true }
}

async function openBrowserMatrix() {
  context.browser = await launchChrome()
  context.desktop = await createCdpPage(context.browser.port, 'desktop')
  await configurePage(context.desktop, { width: 1440, height: 900, mobile: false })
  await setAuthTokens(context.desktop, context.primary)
  await context.desktop.navigate(`${webBaseUrl}/`)
  await waitForApp(context.desktop, '/')
  await focusSmokeAnchor(context.desktop)
}

function browserDevices() {
  return [
    { deviceClass: 'PC', slug: 'pc', artifact: 'desktop-1440x900', page: context.desktop, other: context.mobile },
    { deviceClass: 'MOBILE', slug: 'mobile', artifact: 'mobile-390x844', page: context.mobile, other: context.desktop }
  ]
}

async function twoTabsRefreshReconnect() {
  const payload = createCampaignPayload('revision-old', [context.primary.userId], {
    syncToInbox: true,
    priority: 220,
    maxTotalImpressions: 1,
    bodyDocument: longDocument('Old revision body')
  })
  const created = await createCampaign(payload)
  context.revisionCampaign = await publishCampaign(created.id)
  context.revisionPayload = payload
  await triggerBrowserReconciliation(context.desktop)
  await waitForPopup(context.desktop, titleFor('revision-old'))
  await waitFor(
    async () => (await campaignStats(created.id)).shownDeliveries === 1,
    'desktop popup shown outcome',
    20_000
  )

  context.mobile = await createCdpPage(context.browser.port, 'mobile')
  await configurePage(context.mobile, { width: 390, height: 844, mobile: true })
  await setAuthTokens(context.mobile, context.primary)
  await context.mobile.navigate(`${webBaseUrl}/`)
  await waitForApp(context.mobile, '/')
  await assertNoPopup(context.mobile, 2_000)

  const statsAfterTwoTabs = await campaignStats(created.id)
  assert(liveDeliveryCount(statsAfterTwoTabs) === 1, 'Two tabs must share one active backend delivery')
  await context.mobile.reload()
  await waitForApp(context.mobile, '/')
  await assertNoPopup(context.mobile, 1_500)
  await waitFor(
    () => context.mobile.evaluate(() => (
      Array.isArray(window.__engagementSmokeWebSockets)
        && window.__engagementSmokeWebSockets.some((socket) => (
          socket.readyState === WebSocket.OPEN && new URL(socket.url, location.href).pathname === '/ws'
        ))
    )),
    'captured live mobile /ws before offline cycle',
    20_000
  )
  const preOfflineSockets = context.mobile.diagnostics.webSockets.created.filter(isApplicationWebSocket)
  const preOfflineIds = new Set(preOfflineSockets.map((socket) => socket.requestId))
  const alreadyClosedIds = new Set(context.mobile.diagnostics.webSockets.closed.map((socket) => socket.requestId))
  const preOfflineSocket = [...preOfflineSockets].reverse().find((socket) => !alreadyClosedIds.has(socket.requestId))
  assert(preOfflineSocket, 'CDP must expose the live application /ws before controlled disconnect')
  const documentId = await context.mobile.evaluate(() => {
    window.__engagementSmokeDocumentId ??= crypto.randomUUID()
    return window.__engagementSmokeDocumentId
  })
  const captureContract = await context.mobile.evaluate(() => {
    const descriptor = Object.getOwnPropertyDescriptor(window, '__engagementSmokeWebSockets')
    const sockets = window.__engagementSmokeWebSockets
    return {
      enumerable: descriptor?.enumerable,
      count: Array.isArray(sockets) ? sockets.length : -1,
      nativePrototypeCompatible: Array.isArray(sockets) && sockets.every((socket) => socket instanceof WebSocket),
      constantsCompatible: WebSocket.OPEN === 1 && WebSocket.CLOSED === 3
    }
  })
  assert(captureContract.enumerable === false, 'Smoke WebSocket capture must be non-enumerable')
  assert(captureContract.count >= 1, 'Smoke WebSocket capture must retain a live socket')
  assert(captureContract.nativePrototypeCompatible, 'Smoke WebSocket wrapper must preserve instanceof semantics')
  assert(captureContract.constantsCompatible, 'Smoke WebSocket wrapper must preserve native ready-state constants')
  await context.mobile.setOffline(true)
  try {
    const closedByTest = await context.mobile.evaluate(() => {
      const socket = [...window.__engagementSmokeWebSockets].reverse().find((candidate) => (
        candidate.readyState === WebSocket.OPEN && new URL(candidate.url, location.href).pathname === '/ws'
      ))
      if (!socket) return false
      socket.close(4001, 'engagement-smoke-controlled-disconnect')
      return true
    })
    assert(closedByTest, 'Controlled reconnect seam could not close the captured application /ws')
    await waitFor(
      () => context.mobile.diagnostics.webSockets.closed.some(
        (socket) => socket.requestId === preOfflineSocket.requestId
      ),
      'captured pre-existing /ws close after controlled disconnect',
      10_000
    )
  } finally {
    await context.mobile.setOffline(false)
  }
  await waitFor(
    () => context.mobile.diagnostics.webSockets.created.some(
      (socket) => isApplicationWebSocket(socket) && !preOfflineIds.has(socket.requestId)
    ),
    'new /ws connection after same-document online recovery',
    20_000
  )
  const reconnectedSocket = [...context.mobile.diagnostics.webSockets.created].reverse().find(
    (socket) => isApplicationWebSocket(socket) && !preOfflineIds.has(socket.requestId)
  )
  assert(reconnectedSocket, 'STOMP auto-reconnect must create a new application /ws request id')
  await waitFor(
    () => context.mobile.diagnostics.webSockets.connected.some(
      (socket) => socket.requestId === reconnectedSocket.requestId
    ),
    'STOMP CONNECTED frame after same-document reconnect',
    20_000
  )
  assert(
    await context.mobile.evaluate((expected) => window.__engagementSmokeDocumentId === expected, documentId),
    'WebSocket reconnect must occur in the same browser document without reload/navigation'
  )
  await context.mobile.bringToFront()
  await context.mobile.evaluate(() => window.dispatchEvent(new Event('focus')))
  await assertNoPopup(context.mobile, 1_000)
  const statsAfterReconnect = await campaignStats(created.id)
  assert(liveDeliveryCount(statsAfterReconnect) === 1, 'Refresh/reconnect must not duplicate a live delivery')
  return {
    campaignId: created.id,
    liveDeliveries: liveDeliveryCount(statsAfterReconnect),
    shownDeliveries: statsAfterReconnect.shownDeliveries,
    tabs: 2,
    reconnectSeam: 'captured-native-socket-same-document-offline-online',
    preOfflineSocketCount: preOfflineSockets.length,
    postOnlineSocketCount: context.mobile.diagnostics.webSockets.created.filter(isApplicationWebSocket).length
  }
}

async function revisionSnapshot() {
  const oldRevisionId = context.revisionCampaign.revisionId
  const newTitle = titleFor('revision-new')
  const updatedPayload = {
    ...context.revisionPayload,
    content: {
      ...context.revisionPayload.content,
      title: newTitle,
      bodyDocument: documentFor('New revision body')
    },
    reason: actionReason('revise an open campaign')
  }
  const updated = await api(`/api/admin/engagement/campaigns/${context.revisionCampaign.id}`, {
    method: 'PUT',
    token: context.adminToken,
    body: updatedPayload
  })
  assert(updated.revisionId !== oldRevisionId, 'Campaign update must create a new revision')
  const browserTitle = await popupTitle(context.desktop)
  assert(browserTitle === titleFor('revision-old'), 'An open popup must retain its claimed revision')
  await waitFor(async () => {
    const page = await listMessages(context.primary.accessToken)
    return page.items.some((item) => item.title === newTitle && item.revisionId === updated.revisionId)
  }, 'inbox current revision', 20_000)

  const accessibility = await verifyPopupAccessibility(context.desktop, titleFor('revision-old'), false)
  const screenshot = await captureScreenshot(context.desktop, 'desktop-1440x900-popup.png')
  await context.desktop.pressKey('Tab')
  assert(await focusIsInsideDialog(context.desktop), 'Tab must remain trapped inside the popup')
  await escapePopupAndWaitForQueue(context.desktop)
  await waitForNoPopup(context.desktop)
  await waitFor(
    () => context.desktop.evaluate(() => document.activeElement?.hasAttribute('data-smoke-focus-anchor')),
    'PC popup focus restoration',
    5_000
  )

  await context.mobile.navigate(`${webBaseUrl}/`)
  await waitForApp(context.mobile, '/')
  await focusSmokeAnchor(context.mobile)
  const mobilePayload = createCampaignPayload('revision-mobile-old', [context.primary.userId], {
    deviceScope: 'MOBILE',
    syncToInbox: true,
    priority: 215,
    bodyDocument: longDocument('Mobile old revision body')
  })
  const mobileCreated = await createCampaign(mobilePayload)
  const mobilePublished = await publishCampaign(mobileCreated.id)
  await triggerBrowserReconciliation(context.mobile)
  await waitForPopup(context.mobile, titleFor('revision-mobile-old'))
  const mobileUpdatedPayload = {
    ...mobilePayload,
    content: {
      ...mobilePayload.content,
      title: titleFor('revision-mobile-new'),
      bodyDocument: documentFor('Mobile new revision body')
    },
    reason: actionReason('revise an open mobile campaign')
  }
  const mobileUpdated = await api(`/api/admin/engagement/campaigns/${mobileCreated.id}`, {
    method: 'PUT',
    token: context.adminToken,
    body: mobileUpdatedPayload
  })
  assert(mobileUpdated.revisionId !== mobilePublished.revisionId, 'Mobile campaign update must create a new revision')
  assert(
    await popupTitle(context.mobile) === titleFor('revision-mobile-old'),
    'An open Mobile popup must retain its claimed revision'
  )
  await waitFor(async () => {
    const page = await listMessages(context.primary.accessToken)
    return page.items.some((item) => (
      item.title === titleFor('revision-mobile-new') && item.revisionId === mobileUpdated.revisionId
    ))
  }, 'mobile inbox current revision', 20_000)
  const mobileAccessibility = await verifyPopupAccessibility(context.mobile, titleFor('revision-mobile-old'), false)
  const mobileScreenshot = await captureScreenshot(context.mobile, 'mobile-390x844-revision-popup.png')
  await context.mobile.pressKey('Tab')
  assert(await focusIsInsideDialog(context.mobile), 'Mobile Tab must remain trapped inside the popup')
  await escapePopupAndWaitForQueue(context.mobile)
  await waitForNoPopup(context.mobile)
  await waitFor(
    () => context.mobile.evaluate(() => document.activeElement?.hasAttribute('data-smoke-focus-anchor')),
    'Mobile popup focus restoration',
    5_000
  )
  return {
    PC: { oldRevisionId, newRevisionId: updated.revisionId, accessibility, screenshot },
    MOBILE: {
      oldRevisionId: mobilePublished.revisionId,
      newRevisionId: mobileUpdated.revisionId,
      accessibility: mobileAccessibility,
      screenshot: mobileScreenshot
    }
  }
}

async function browserQueueInteractions() {
  const devices = browserDevices()
  const queueAndCta = []
  for (const device of devices) queueAndCta.push(await verifyBrowserQueueAndCta(device))

  const optOut = []
  for (const device of devices) {
    await device.other.navigate(`${webBaseUrl}/messages`)
    await waitForApp(device.other, '/messages')
    await device.page.navigate(`${webBaseUrl}/`)
    await waitForApp(device.page, '/')
    const label = `browser-opt-out-${device.slug}`
    const campaign = await createCampaign(createCampaignPayload(label, [context.primary.userId], {
      displayScope: 'SELECTED_PAGES',
      pageKeys: ['HOME'],
      priority: device.deviceClass === 'PC' ? 540 : 530,
      maxTotalImpressions: 3
    }))
    await publishCampaign(campaign.id)
    await waitForPopup(device.page, titleFor(label))
    const screenshot = await captureScreenshot(device.page, `${device.artifact}-browser-opt-out.png`)
    await optOutPopupAndWaitForQueue(device.page)
    await waitForNoPopup(device.page)
    await device.page.reload()
    await waitForApp(device.page, '/')
    await assertNoPopup(device.page, 750)
    await device.other.navigate(`${webBaseUrl}/`)
    await waitForApp(device.other, '/')
    await assertNoPopup(device.other, 750)
    await deleteCampaign(campaign.id)
    optOut.push({ deviceClass: device.deviceClass, campaignId: campaign.id, screenshot })
  }
  return { queueAndCta, optOut }
}

async function verifyBrowserQueueAndCta(device) {
  await device.page.navigate(`${webBaseUrl}/`)
  await waitForApp(device.page, '/')
  await assertNoPopup(device.page, 500)
  const basePriority = device.deviceClass === 'PC' ? 600 : 570
  const queueCampaigns = []
  for (let index = 0; index < 4; index += 1) {
    const label = `browser-queue-${device.slug}-${index + 1}`
    queueCampaigns.push(await createCampaign(createCampaignPayload(label, [context.primary.userId], {
      deviceScope: device.deviceClass,
      priority: basePriority - index
    })))
  }
  for (const campaign of queueCampaigns) await publishCampaign(campaign.id)
  await waitForPopup(device.page, titleFor(`browser-queue-${device.slug}-1`))
  const sequentialScreenshot = await captureScreenshot(device.page, `${device.artifact}-browser-queue-first.png`)
  for (let index = 0; index < 3; index += 1) {
    const expectedTitle = titleFor(`browser-queue-${device.slug}-${index + 1}`)
    assert(await popupTitle(device.page) === expectedTitle, `${device.deviceClass} browser queue item ${index + 1} is out of order`)
    await clickPopupCloseAndWaitForQueue(device.page)
    if (index < 2) await waitForPopup(device.page, titleFor(`browser-queue-${device.slug}-${index + 2}`))
  }
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/markets')
  await waitForPopup(device.page, titleFor(`browser-queue-${device.slug}-4`))
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)

  const ctaLabel = `browser-cta-${device.slug}`
  const afterCtaLabel = `browser-after-cta-${device.slug}`
  const ctaCampaign = await createCampaign(createCampaignPayload(ctaLabel, [context.primary.userId], {
    deviceScope: device.deviceClass,
    displayScope: 'SELECTED_PAGES',
    pageKeys: ['MARKETS'],
    priority: basePriority - 10,
    cta: { label: 'Open messages', routeKey: 'MESSAGE_CENTER', paramsJson: '{}' }
  }))
  const afterCtaCampaign = await createCampaign(createCampaignPayload(afterCtaLabel, [context.primary.userId], {
    deviceScope: device.deviceClass,
    displayScope: 'SELECTED_PAGES',
    pageKeys: ['MARKETS'],
    priority: basePriority - 11
  }))
  await publishCampaign(ctaCampaign.id)
  await publishCampaign(afterCtaCampaign.id)
  await waitForPopup(device.page, titleFor(ctaLabel))
  const ctaScreenshot = await captureScreenshot(device.page, `${device.artifact}-browser-cta.png`)
  await clickPopupButtonByText(device.page, 'Open messages')
  await waitForApp(device.page, '/messages')
  await assertNoPopup(device.page, 750)
  await deleteCampaign(afterCtaCampaign.id)
  return {
    deviceClass: device.deviceClass,
    sequentialCampaignIds: queueCampaigns.map((campaign) => campaign.id),
    fourthNaturalTrigger: 'ROUTE_CHANGE',
    ctaCampaignId: ctaCampaign.id,
    ctaRoute: '/messages',
    screenshots: [sequentialScreenshot, ctaScreenshot]
  }
}

async function browserFrequencyCaps() {
  const evidence = []
  for (const device of browserDevices()) evidence.push(await verifyBrowserFrequencyCaps(device))
  return { evidence }
}

async function verifyBrowserFrequencyCaps(device) {
  await device.page.navigate(`${webBaseUrl}/`)
  await waitForApp(device.page, '/')
  await assertNoPopup(device.page, 500)
  const priority = device.deviceClass === 'PC' ? 500 : 490

  const intervalLabel = `browser-interval-${device.slug}`
  const intervalCampaign = await createCampaign(createCampaignPayload(intervalLabel, [context.primary.userId], {
    deviceScope: device.deviceClass,
    priority,
    maxTotalImpressions: 3,
    maxDailyImpressions: 3,
    minIntervalSeconds: 2
  }))
  await publishCampaign(intervalCampaign.id)
  await waitForPopup(device.page, titleFor(intervalLabel))
  await waitForCampaignImpressions(intervalCampaign.id, 1, `${device.deviceClass} interval impression 1`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/markets')
  await assertNoPopup(device.page, 750)
  assert((await campaignStats(intervalCampaign.id)).totalImpressions === 1, `${device.deviceClass} interval cap allowed an immediate replay`)
  await sleep(2_100)
  await clickRouteLink(device.page, '/')
  await waitForPopup(device.page, titleFor(intervalLabel))
  await waitForCampaignImpressions(intervalCampaign.id, 2, `${device.deviceClass} interval impression 2`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await deleteCampaign(intervalCampaign.id)

  const totalLabel = `browser-total-${device.slug}`
  const totalCampaign = await createCampaign(createCampaignPayload(totalLabel, [context.primary.userId], {
    deviceScope: device.deviceClass,
    priority: priority - 1,
    maxTotalImpressions: 2,
    maxDailyImpressions: 2
  }))
  await publishCampaign(totalCampaign.id)
  await waitForPopup(device.page, titleFor(totalLabel))
  await waitForCampaignImpressions(totalCampaign.id, 1, `${device.deviceClass} total impression 1`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/markets')
  await waitForPopup(device.page, titleFor(totalLabel))
  await waitForCampaignImpressions(totalCampaign.id, 2, `${device.deviceClass} total impression 2`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/')
  await assertNoPopup(device.page, 750)
  assert((await campaignStats(totalCampaign.id)).totalImpressions === 2, `${device.deviceClass} total cap allowed impression 3`)
  await deleteCampaign(totalCampaign.id)

  const dailyLabel = `browser-daily-${device.slug}`
  const dailyCampaign = await createCampaign(createCampaignPayload(dailyLabel, [context.primary.userId], {
    deviceScope: device.deviceClass,
    priority: priority - 2,
    maxTotalImpressions: 3,
    maxDailyImpressions: 2
  }))
  await publishCampaign(dailyCampaign.id)
  await waitForPopup(device.page, titleFor(dailyLabel))
  await waitForCampaignImpressions(dailyCampaign.id, 1, `${device.deviceClass} daily impression 1`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/markets')
  await waitForPopup(device.page, titleFor(dailyLabel))
  await waitForCampaignImpressions(dailyCampaign.id, 2, `${device.deviceClass} daily impression 2`)
  await clickPopupCloseAndWaitForQueue(device.page)
  await waitForNoPopup(device.page)
  await clickRouteLink(device.page, '/')
  await assertNoPopup(device.page, 750)
  assert((await campaignStats(dailyCampaign.id)).totalImpressions === 2, `${device.deviceClass} daily cap allowed impression 3 today`)
  await deleteCampaign(dailyCampaign.id)
  return {
    deviceClass: device.deviceClass,
    intervalCampaignId: intervalCampaign.id,
    totalCampaignId: totalCampaign.id,
    dailyCampaignId: dailyCampaign.id,
    impressions: { interval: 2, total: 2, daily: 2 }
  }
}

async function browserScheduledAllAudience() {
  const evidence = []
  for (const device of browserDevices()) {
    await device.page.navigate(`${webBaseUrl}/`)
    await waitForApp(device.page, '/')
    const dueAt = new Date(Date.now() + 15_000).toISOString()
    const label = `browser-scheduled-all-${device.slug}`
    const campaign = await createCampaign(createCampaignPayload(label, [], {
      audienceType: 'ALL',
      deviceScope: device.deviceClass,
      priority: device.deviceClass === 'PC' ? 470 : 460,
      startAt: dueAt
    }))
    const scheduled = await publishCampaign(campaign.id)
    assert(scheduled.lifecycleStatus === 'SCHEDULED', `${device.deviceClass} ALL campaign must publish as SCHEDULED`)
    await assertNoPopup(device.page, 1_000)
    await waitFor(async () => {
      const detail = await api(`/api/admin/engagement/campaigns/${campaign.id}`, { token: context.adminToken })
      return detail.lifecycleStatus === 'ACTIVE'
    }, `${device.deviceClass} scheduled ALL campaign activation`, scheduleTimeoutMs)
    await waitForPopup(device.page, titleFor(label))
    const screenshot = await captureScreenshot(device.page, `${device.artifact}-scheduled-all-popup.png`)
    await clickPopupCloseAndWaitForQueue(device.page)
    await waitForNoPopup(device.page)
    await deleteCampaign(campaign.id)
    evidence.push({ deviceClass: device.deviceClass, campaignId: campaign.id, dueAt, screenshot })
  }
  return { evidence }
}

async function waitForCampaignImpressions(campaignId, count, label) {
  await waitFor(async () => (await campaignStats(campaignId)).totalImpressions === count, label, 20_000)
}

async function responsiveTemplateImageMatrix() {
  context.platformAsset = await uploadPlatformImage()
  const publicImage = await fetchWithTimeout(`${apiBaseUrl}${context.platformAsset.url}`, {}, 10_000)
  assert(publicImage.ok, `Uploaded platform image is not publicly readable; HTTP ${publicImage.status}`)
  assert(publicImage.headers.get('content-type')?.startsWith('image/png'), 'Uploaded platform image MIME changed')

  const viewports = {
    PC: { page: context.desktop, label: 'desktop-1440x900' },
    MOBILE: { page: context.mobile, label: 'mobile-390x844' }
  }
  const evidence = []
  for (const deviceClass of ['PC', 'MOBILE']) {
    const { page, label: viewportLabel } = viewports[deviceClass]
    await page.bringToFront()
    await page.navigate(`${webBaseUrl}/`)
    await waitForApp(page, '/')
    for (const templateSize of ['SMALL', 'MEDIUM', 'LARGE']) {
      await focusSmokeAnchor(page)
      const label = `matrix-${deviceClass.toLowerCase()}-${templateSize.toLowerCase()}`
      const campaign = await createCampaign(createCampaignPayload(label, [context.primary.userId], {
        deviceScope: deviceClass,
        templateSize,
        priority: 180,
        coverAssetId: context.platformAsset.assetId,
        bodyDocument: longDocument(`${deviceClass} ${templateSize} long content`, context.platformAsset.assetId)
      }))
      await publishCampaign(campaign.id)
      await triggerBrowserReconciliation(page)
      await waitForPopup(page, titleFor(label))
      await page.waitForFunction((assetId) => {
        const images = [...document.querySelectorAll('[role="dialog"][aria-modal="true"] img')]
        return images.length >= 2 && images.every((image) => (
          image instanceof HTMLImageElement
            && image.complete
            && image.naturalWidth > 0
            && image.src.includes(`/api/public/engagement/assets/${assetId}`)
        ))
      }, `${deviceClass} ${templateSize} platform images`, context.platformAsset.assetId)
      const accessibility = await verifyPopupAccessibility(page, titleFor(label), true)
      await page.pressKey('Tab', true)
      assert(await focusIsInsideDialog(page), `${deviceClass} ${templateSize} Shift+Tab escaped the dialog`)
      await page.pressKey('Tab')
      assert(await focusIsInsideDialog(page), `${deviceClass} ${templateSize} Tab escaped the dialog`)
      const screenshot = await captureScreenshot(
        page,
        `${viewportLabel}-${templateSize.toLowerCase()}-popup.png`
      )
      await escapePopupAndWaitForQueue(page)
      await waitForNoPopup(page)
      await waitFor(
        () => page.evaluate(() => document.activeElement?.hasAttribute('data-smoke-focus-anchor')),
        `${deviceClass} ${templateSize} focus restoration`,
        5_000
      )
      await waitFor(() => page.evaluate(() => getComputedStyle(document.querySelector('main')).overflowY !== 'hidden'), `${deviceClass} main scroll restoration`, 5_000)
      await deleteCampaign(campaign.id)
      evidence.push({ deviceClass, templateSize, campaignId: campaign.id, screenshot, accessibility })
    }
  }
  report.cleanup.push({
    target: `asset:${context.platformAsset.assetId}`,
    status: 'RETAINED',
    reason: 'Immutable audited platform asset; all six referencing campaigns are logically deleted'
  })
  return { asset: context.platformAsset, evidence }
}

async function ordinaryMessageNoPopup() {
  await context.desktop.bringToFront()
  await context.desktop.navigate(`${webBaseUrl}/`)
  await waitForApp(context.desktop, '/')
  await context.mobile.bringToFront()
  await context.mobile.navigate(`${webBaseUrl}/`)
  await waitForApp(context.mobile, '/')
  const beforeUnread = await unreadCount(context.primary.accessToken)
  const message = await createMessage(messagePayload('ordinary-live', [context.primary.userId]))
  context.ordinaryMessage = await sendMessage(message.id, null)
  let afterUnread = beforeUnread
  await waitFor(async () => {
    await assertPopupAbsentNow([context.desktop, context.mobile], 'ordinary message realtime delivery')
    afterUnread = await unreadCount(context.primary.accessToken)
    return afterUnread > beforeUnread
  }, 'ordinary message unread wake-up', 20_000)
  assert(afterUnread === beforeUnread + 1, 'Immediate ordinary message must add exactly one unread receipt')
  await waitForExactUnreadWithoutPopup([context.desktop, context.mobile], afterUnread, 'immediate ordinary message realtime bells')

  const dueAt = new Date(Date.now() + 15_000).toISOString()
  const scheduled = await createMessage(messagePayload('ordinary-scheduled-browser', [context.primary.userId]))
  const scheduledState = await sendMessage(scheduled.id, dueAt)
  assert(scheduledState.lifecycleStatus === 'SCHEDULED', 'Browser ordinary message must first be SCHEDULED')
  await waitFor(async () => {
    await assertPopupAbsentNow([context.desktop, context.mobile], 'scheduled ordinary message wait')
    const detail = await api(`/api/admin/engagement/messages/${scheduled.id}`, { token: context.adminToken })
    return detail.lifecycleStatus === 'SENT'
  }, 'scheduled browser message dispatch', scheduleTimeoutMs)
  let scheduledUnread = afterUnread
  await waitFor(async () => {
    await assertPopupAbsentNow([context.desktop, context.mobile], 'scheduled ordinary message realtime delivery')
    scheduledUnread = await unreadCount(context.primary.accessToken)
    return scheduledUnread > afterUnread
  }, 'scheduled browser message unread wake-up', 20_000)
  assert(scheduledUnread === afterUnread + 1, 'Scheduled ordinary message must add exactly one unread receipt')
  await waitForExactUnreadWithoutPopup([context.desktop, context.mobile], scheduledUnread, 'scheduled ordinary message realtime bells')

  await context.desktop.bringToFront()
  await context.desktop.evaluate(() => {
    const control = document.querySelector('[aria-label^="Messages"]')
    if (!(control instanceof HTMLElement)) throw new Error('Message bell is missing')
    control.click()
  })
  await context.desktop.waitForFunction(
    (title) => document.querySelector('[role="dialog"][aria-label="Recent messages"]')?.textContent?.includes(title),
    'recent message menu update',
    titleFor('ordinary-scheduled-browser')
  )
  await context.desktop.pressKey('Escape')
  await context.mobile.bringToFront()
  await context.mobile.evaluate(() => {
    const control = document.querySelector('[aria-label^="Messages"]')
    if (!(control instanceof HTMLElement)) throw new Error('Mobile message link is missing')
    control.click()
  })
  await waitForApp(context.mobile, '/messages')
  await waitForMessage(context.mobile, titleFor('ordinary-scheduled-browser'))
  await assertNoPopup(context.mobile, 500)
  return {
    publicationId: context.ordinaryMessage.id,
    scheduledPublicationId: scheduled.id,
    unreadBefore: beforeUnread,
    unreadAfterImmediate: afterUnread,
    unreadAfterScheduled: scheduledUnread,
    exactPcAndMobileBells: true,
    mobileBellRoute: '/messages',
    popupFree: true
  }
}

async function messageReceipts() {
  const visual = await createMessage(messagePayload('visual-message', [context.primary.userId], {
    bodyDocument: longDocument('Message center long content')
  }))
  context.visualMessage = await sendMessage(visual.id, null)
  const mobileReceiptDraft = await createMessage(messagePayload('mobile-receipts', [context.primary.userId]))
  const mobileReceipt = await sendMessage(mobileReceiptDraft.id, null)
  await context.desktop.navigate(`${webBaseUrl}/messages`)
  await waitForMessage(context.desktop, titleFor('ordinary-live'))
  await waitForMessage(context.desktop, titleFor('visual-message'))
  await clickMessageAction(context.desktop, titleFor('ordinary-live'), 'Mark as read')
  await waitForMessageReadState(context.desktop, titleFor('ordinary-live'), false)
  await clickMessageAction(context.desktop, titleFor('ordinary-live'), 'Mark as unread')
  await waitForMessageReadState(context.desktop, titleFor('ordinary-live'), true)
  await clickButtonByText(context.desktop, 'Mark all as read')
  await waitFor(async () => (await unreadCount(context.primary.accessToken)) === 0, 'mark all as read', 20_000)

  await api(`/api/me/messages/${context.ordinaryMessage.id}/unread`, {
    method: 'POST',
    token: context.primary.accessToken
  })
  await context.desktop.reload()
  await waitForMessage(context.desktop, titleFor('ordinary-live'))
  await clickMessageAction(context.desktop, titleFor('ordinary-live'), 'Hide')
  await waitFor(async () => !(await listMessages(context.primary.accessToken)).items.some(
    (item) => item.publicationId === context.ordinaryMessage.id
  ), 'hidden message removed', 20_000)

  await api(`/api/me/messages/${context.visualMessage.id}/unread`, {
    method: 'POST',
    token: context.primary.accessToken
  })
  await context.desktop.reload()
  await waitForMessage(context.desktop, titleFor('visual-message'))

  const desktopHealth = await assertPageHealth(context.desktop, '/messages')
  const desktopScreenshot = await captureScreenshot(context.desktop, 'desktop-1440x900-messages.png')
  await context.mobile.navigate(`${webBaseUrl}/messages`)
  await waitForMessage(context.mobile, titleFor('visual-message'))
  await waitForMessage(context.mobile, titleFor('mobile-receipts'))
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), false)
  await clickMessageAction(context.mobile, titleFor('mobile-receipts'), 'Mark as unread')
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), true)
  await clickMessageAction(context.mobile, titleFor('mobile-receipts'), 'Mark as read')
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), false)
  await clickMessageAction(context.mobile, titleFor('mobile-receipts'), 'Mark as unread')
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), true)
  await clickButtonByText(context.mobile, 'Mark all as read')
  await waitFor(async () => (await unreadCount(context.primary.accessToken)) === 0, 'mobile mark all as read', 20_000)
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), false)
  await clickMessageAction(context.mobile, titleFor('mobile-receipts'), 'Mark as unread')
  await waitForMessageReadState(context.mobile, titleFor('mobile-receipts'), true)
  await clickMessageAction(context.mobile, titleFor('mobile-receipts'), 'Hide')
  await waitFor(async () => !(await listMessages(context.primary.accessToken)).items.some(
    (item) => item.publicationId === mobileReceipt.id
  ), 'mobile hidden message removed', 20_000)
  await api(`/api/me/messages/${context.visualMessage.id}/unread`, {
    method: 'POST',
    token: context.primary.accessToken
  })
  await context.mobile.reload()
  await waitForMessage(context.mobile, titleFor('visual-message'))
  await clickButtonByText(context.mobile, 'Unread')
  await context.mobile.waitForFunction(
    () => document.querySelector('[role="tab"][aria-selected="true"]')?.textContent?.includes('Unread'),
    'mobile unread filter'
  )
  const mobileHealth = await assertPageHealth(context.mobile, '/messages')
  const mobileScreenshot = await captureScreenshot(context.mobile, 'mobile-390x844-messages.png')
  return {
    desktopHealth,
    mobileHealth,
    desktopScreenshot,
    mobileScreenshot,
    desktopHiddenPublicationId: context.ordinaryMessage.id,
    mobileHiddenPublicationId: mobileReceipt.id
  }
}

async function pauseDeleteRestore() {
  const evidence = []
  for (const device of browserDevices()) evidence.push(await verifyPauseDeleteRestore(device))
  return { evidence }
}

async function verifyPauseDeleteRestore(device) {
  await device.page.navigate(`${webBaseUrl}/`)
  await waitForApp(device.page, '/')
  await focusSmokeAnchor(device.page)
  const deleteLabel = `delete-online-${device.slug}`
  const deletedPayload = createCampaignPayload(deleteLabel, [context.primary.userId], {
    syncToInbox: true,
    priority: device.deviceClass === 'PC' ? 210 : 205,
    deviceScope: device.deviceClass
  })
  const deletedCampaign = await createCampaign(deletedPayload)
  await publishCampaign(deletedCampaign.id)
  await waitForPopup(device.page, titleFor(deleteLabel))
  const deleteScreenshot = await captureScreenshot(device.page, `${device.artifact}-delete-online-before.png`)
  const deleteNextBefore = completedPopupNextCount(device.page)
  const deleted = await deleteCampaign(deletedCampaign.id)
  assert(deleted.lifecycleStatus === 'DELETED', `${device.deviceClass} delete must be logical`)
  await waitForNoPopup(device.page, 20_000)
  await waitForInvalidationQueueSettlement(device.page, deleteNextBefore, 'delete invalidation')
  await waitFor(
    () => device.page.evaluate(() => document.activeElement?.hasAttribute('data-smoke-focus-anchor')),
    `${device.deviceClass} delete realtime focus restoration`,
    5_000
  )
  await waitFor(async () => !(await listMessages(context.primary.accessToken)).items.some(
    (item) => item.title === titleFor(deleteLabel)
  ), `${device.deviceClass} deleted campaign inbox hide`, 20_000)
  const restored = await api(`/api/admin/engagement/campaigns/${deletedCampaign.id}/restore`, {
    method: 'POST',
    token: context.adminToken,
    body: { reason: actionReason(`verify ${device.deviceClass} restore remains paused`) }
  })
  assert(restored.lifecycleStatus === 'PAUSED', `${device.deviceClass} restored campaign must remain PAUSED`)

  const pauseLabel = `pause-online-${device.slug}`
  const pausedCampaign = await createCampaign(createCampaignPayload(pauseLabel, [context.primary.userId], {
    priority: device.deviceClass === 'PC' ? 200 : 195,
    deviceScope: device.deviceClass
  }))
  await publishCampaign(pausedCampaign.id)
  await waitForPopup(device.page, titleFor(pauseLabel))
  const pauseScreenshot = await captureScreenshot(device.page, `${device.artifact}-pause-online-before.png`)
  const pauseNextBefore = completedPopupNextCount(device.page)
  const paused = await api(`/api/admin/engagement/campaigns/${pausedCampaign.id}/pause`, {
    method: 'POST',
    token: context.adminToken,
    body: { reason: actionReason(`verify ${device.deviceClass} online pause`) }
  })
  assert(paused.lifecycleStatus === 'PAUSED', `${device.deviceClass} pause must persist PAUSED`)
  await waitForNoPopup(device.page, 20_000)
  await waitForInvalidationQueueSettlement(device.page, pauseNextBefore, 'pause invalidation')
  await waitFor(
    () => device.page.evaluate(() => document.activeElement?.hasAttribute('data-smoke-focus-anchor')),
    `${device.deviceClass} pause realtime focus restoration`,
    5_000
  )
  return {
    deviceClass: device.deviceClass,
    deletedCampaignId: deletedCampaign.id,
    restored: restored.lifecycleStatus,
    pausedCampaignId: pausedCampaign.id,
    screenshots: [deleteScreenshot, pauseScreenshot]
  }
}

async function verifyBrowserHealth() {
  for (const page of [context.desktop, context.mobile]) {
    if (!page) continue
    const consoleIssues = [...page.diagnostics.errors, ...page.diagnostics.warnings]
      .filter((entry) => !entry.expected && !isExpectedBrowserNoise(entry.message))
    const failedRequests = page.diagnostics.failedRequests
      .filter((request) => !request.expected)
    assert(consoleIssues.length === 0, `${page.name} console issues: ${consoleIssues.map((entry) => entry.message).join(' | ')}`)
    assert(failedRequests.length === 0, `${page.name} failed requests: ${failedRequests.map((request) => `${request.errorText} ${request.url}`).join(' | ')}`)
    await page.evaluate(() => {
      const body = document.body.textContent?.trim() ?? ''
      if (document.title !== 'FX Trader') throw new Error(`Unexpected page title ${document.title}`)
      if (body.length < 40) throw new Error('Page is blank')
      if (document.querySelector('[data-nextjs-dialog-overlay], vite-error-overlay, #webpack-dev-server-client-overlay')) {
        throw new Error('Framework error overlay is visible')
      }
    })
  }
}

async function cleanupOwnedRecords() {
  if (!context.adminToken) return
  const errors = []
  if (context.primaryStatusDirty && context.primary?.userId) {
    try {
      await setPrimaryStatus('ACTIVE', 'failure rollback before cleanup')
      report.cleanup.push({ target: `user:${context.primary.userId}`, status: 'PASS', action: 'restore ACTIVE before cleanup' })
    } catch (error) {
      errors.push(asError(error))
    }
  }
  for (const campaignId of [...ownedCampaignIds].reverse()) {
    try {
      const detail = await api(`/api/admin/engagement/campaigns/${campaignId}`, { token: context.adminToken })
      if (detail.lifecycleStatus !== 'DELETED') await deleteCampaign(campaignId, true)
      report.cleanup.push({ target: `campaign:${campaignId}`, status: 'PASS', action: 'logical delete' })
    } catch (error) {
      errors.push(asError(error))
    }
  }
  for (const messageId of [...ownedMessageIds].reverse()) {
    try {
      const detail = await api(`/api/admin/engagement/messages/${messageId}`, { token: context.adminToken })
      if (detail.lifecycleStatus !== 'DELETED') {
        await api(`/api/admin/engagement/messages/${messageId}`, {
          method: 'DELETE',
          token: context.adminToken,
          body: { reason: actionReason('cleanup message') }
        })
      }
      report.cleanup.push({ target: `message:${messageId}`, status: 'PASS', action: 'logical delete' })
    } catch (error) {
      errors.push(asError(error))
    }
  }
  for (const userId of ownedUserIds) {
    try {
      await setUserStatus(userId, 'DISABLED', 'cleanup owned demo user')
      report.cleanup.push({ target: `user:${userId}`, status: 'PASS', action: 'disable owned user' })
    } catch (error) {
      errors.push(asError(error))
    }
  }
  if (errors.length) throw new AggregateError(errors, `${errors.length} owned cleanup operation(s) failed`)
}

async function closeBrowser() {
  const browser = context.browser
  if (!browser) {
    for (const page of [context.desktop, context.mobile]) await page?.close().catch(() => undefined)
    return
  }

  const browserCloseRequested = await requestBrowserClose(browser).catch(() => false)
  for (const page of [context.desktop, context.mobile]) {
    await page?.close(false).catch(() => undefined)
  }

  let shutDown = false
  if (browserCloseRequested) {
    try {
      await waitForOwnedBrowserShutdown(browser, 5_000)
      shutDown = true
    } catch {
      // The exact owned-process fallback below handles a stalled graceful shutdown.
    }
  }
  if (!shutDown) {
    killProcessTree(browser.child)
    await forceStopOwnedDebugPortProcess(browser)
    await waitForOwnedBrowserShutdown(browser, 10_000)
  }
  await sleep(250)
  await safeRemoveOwnedBrowserProfile(browser.userDataDir)
  report.cleanup.push({ target: 'browser profile', status: 'PASS' })
}

async function writeReport() {
  await mkdir(artifactDirectory, { recursive: true })
  const reportPath = join(artifactDirectory, `${prefix}-report.json`)
  await writeFile(reportPath, `${JSON.stringify(redactReport(report), null, 2)}\n`, 'utf8')
  process.stdout.write(`Engagement smoke report: ${reportPath}\n`)
}

async function createCampaign(payload) {
  const created = await api('/api/admin/engagement/campaigns', {
    method: 'POST',
    token: context.adminToken,
    body: payload
  })
  if (created?.id) ownedCampaignIds.add(created.id)
  assert(created.id && created.lifecycleStatus === 'DRAFT', 'Campaign create must return a DRAFT id')
  return created
}

async function publishCampaign(campaignId) {
  return api(`/api/admin/engagement/campaigns/${campaignId}/publish`, {
    method: 'POST',
    token: context.adminToken,
    body: { reason: actionReason('publish campaign') }
  })
}

async function deleteCampaign(campaignId, cleanup = false) {
  return api(`/api/admin/engagement/campaigns/${campaignId}`, {
    method: 'DELETE',
    token: context.adminToken,
    body: { reason: actionReason(cleanup ? 'cleanup campaign' : 'verify logical delete') }
  })
}

async function campaignStats(campaignId) {
  return api(`/api/admin/engagement/campaigns/${campaignId}/stats`, { token: context.adminToken })
}

function liveDeliveryCount(stats) {
  return stats.issuedDeliveries + stats.shownDeliveries
}

async function uploadPlatformImage() {
  const png = createVisualFixturePng()
  const body = new FormData()
  body.append('file', new Blob([png], { type: 'image/png' }), `${prefix}.png`)
  const response = await fetchWithTimeout(`${apiBaseUrl}/api/admin/engagement/assets`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${context.adminToken}` },
    body
  }, 15_000)
  const payload = await parseJson(response)
  if (!response.ok || payload.success !== true) {
    throw new ApiError(response.status, payload.code ?? 'HTTP_ERROR', payload.message ?? `HTTP ${response.status}`)
  }
  assert(payload.data?.assetId && payload.data.mimeType === 'image/png', 'Platform image upload did not return PNG metadata')
  assert(payload.data.width === 320 && payload.data.height === 180, 'Platform image upload must preserve the visible 320x180 fixture')
  assert(payload.data.byteSize === png.byteLength, 'Platform image upload must preserve the fixture byte size')
  return payload.data
}

function createVisualFixturePng() {
  const width = 320
  const height = 180
  const stride = width * 4 + 1
  const pixels = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y += 1) {
    const row = y * stride
    pixels[row] = 0
    for (let x = 0; x < width; x += 1) {
      const offset = row + 1 + x * 4
      const centerStripe = y >= 68 && y <= 112
      const diagonal = Math.abs(y - Math.round(x * height / width)) < 4
      pixels[offset] = centerStripe ? 245 : Math.round(25 + (210 * x / width))
      pixels[offset + 1] = diagonal ? 250 : Math.round(45 + (170 * y / height))
      pixels[offset + 2] = centerStripe ? 255 : Math.round(210 - (130 * x / width))
      pixels[offset + 3] = 255
    }
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8
  header[9] = 6
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(pixels)),
    pngChunk('IEND', Buffer.alloc(0))
  ])
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, 'ascii')
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const checksum = Buffer.alloc(4)
  checksum.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])))
  return Buffer.concat([length, typeBuffer, data, checksum])
}

function crc32(buffer) {
  let crc = 0xffffffff
  for (const byte of buffer) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

async function createMessage(payload) {
  const created = await api('/api/admin/engagement/messages', {
    method: 'POST',
    token: context.adminToken,
    body: payload
  })
  if (created?.id) ownedMessageIds.add(created.id)
  assert(created.id && created.lifecycleStatus === 'DRAFT', 'Message create must return a DRAFT id')
  return created
}

async function sendMessage(messageId, sendAt) {
  return api(`/api/admin/engagement/messages/${messageId}/send`, {
    method: 'POST',
    token: context.adminToken,
    body: { sendAt, reason: actionReason(sendAt ? 'schedule message' : 'send message now') }
  })
}

function createCampaignPayload(label, targetUserIds, options = {}) {
  const startAt = options.startAt ?? new Date(Date.now() - 2_000).toISOString()
  const maxTotalImpressions = options.maxTotalImpressions ?? 1
  const maxDailyImpressions = options.maxDailyImpressions ?? maxTotalImpressions
  assert(maxDailyImpressions <= maxTotalImpressions, 'Campaign maxDailyImpressions must not exceed maxTotalImpressions')
  return {
    name: `${prefix}-${label}`,
    content: {
      title: titleFor(label),
      bodyDocument: options.bodyDocument ?? documentFor(`${prefix} ${label}`),
      coverAssetId: options.coverAssetId ?? null,
      cta: options.cta ?? null
    },
    audienceType: options.audienceType ?? 'SELECTED',
    targetUserIds,
    syncToInbox: options.syncToInbox ?? false,
    priority: options.priority ?? 0,
    displayScope: options.displayScope ?? 'ALL_BUSINESS_PAGES',
    pageKeys: options.pageKeys ?? [],
    deviceScope: options.deviceScope ?? 'ALL',
    templateSize: options.templateSize ?? 'MEDIUM',
    timeZone: 'UTC',
    startAt,
    endAt: options.endAt ?? new Date(new Date(startAt).getTime() + 60 * 60 * 1000).toISOString(),
    maxTotalImpressions,
    maxDailyImpressions,
    minIntervalSeconds: options.minIntervalSeconds ?? 0,
    reason: actionReason(`create ${label}`)
  }
}

function messagePayload(label, targetUserIds, options = {}) {
  return {
    category: 'SMOKE',
    audienceType: options.audienceType ?? 'SELECTED',
    targetUserIds,
    content: {
      title: titleFor(label),
      bodyDocument: options.bodyDocument ?? documentFor(`${prefix} ${label}`),
      coverAssetId: null,
      cta: null
    },
    reason: actionReason(`create message ${label}`)
  }
}

function documentFor(text) {
  return JSON.stringify({
    type: 'doc',
    content: [{ type: 'paragraph', content: [{ type: 'text', text }] }]
  })
}

function longDocument(text, assetId = null) {
  const content = Array.from({ length: 24 }, (_, index) => ({
      type: index === 0 ? 'heading' : 'paragraph',
      attrs: index === 0 ? { level: 2, textAlign: 'left' } : { textAlign: 'left' },
      content: [{ type: 'text', text: `${text} ${index + 1}` }]
    }))
  if (assetId) content.splice(2, 0, { type: 'image', attrs: { assetId, alt: `${prefix} platform image` } })
  return JSON.stringify({ type: 'doc', content })
}

function titleFor(label) {
  return `${prefix} ${label}`
}

async function startQueue(token, triggerType, deviceClass) {
  return api('/api/me/engagement/popup-queues', {
    method: 'POST',
    token,
    body: popupSurface(triggerType, deviceClass)
  })
}

async function nextQueue(token, sessionId, surface) {
  return api(`/api/me/engagement/popup-queues/${sessionId}/next`, {
    method: 'POST',
    token,
    body: surface
  })
}

function popupSurface(triggerType, deviceClass) {
  return { triggerType, pageKey: 'HOME', deviceClass }
}

async function finishClaim(token, claim, outcome) {
  assert(claim?.deliveryToken, 'Popup claim must include its one-time credential')
  await api(`/api/me/engagement/popup-deliveries/${encodeURIComponent(claim.deliveryToken)}/shown`, {
    method: 'POST',
    token
  })
  await api(`/api/me/engagement/popup-deliveries/${encodeURIComponent(claim.deliveryToken)}/${outcome}`, {
    method: 'POST',
    token
  })
}

async function listMessages(token, unreadOnly = false) {
  return api(`/api/me/messages?page=0&size=100&unreadOnly=${unreadOnly}`, { token })
}

async function unreadCount(token) {
  const result = await api('/api/me/messages/unread-count', { token })
  return result.count
}

async function claimWhileInactive(token) {
  try {
    return await startQueue(token, 'LOGIN', 'PC')
  } catch (error) {
    if (error instanceof ApiError && [401, 403].includes(error.status)) return error.status
    throw error
  }
}

async function setUserStatus(userId, status, reason) {
  const response = await api(`/api/admin/users/${userId}/status`, {
    method: 'PATCH',
    token: context.adminToken,
    body: {
      status,
      reason: actionReason(reason),
      ...(status === 'DISABLED' ? { confirmationText: 'CONFIRM_DISABLE_USER' } : {})
    }
  })
  assert(response?.status === status, `User ${userId} status response must be ${status}`)
  return response
}

async function setPrimaryStatus(status, reason) {
  if (status !== 'ACTIVE') context.primaryStatusDirty = true
  const response = await setUserStatus(context.primary.userId, status, reason)
  if (status === 'ACTIVE') context.primaryStatusDirty = false
  return response
}

async function loginUser(email, password) {
  const auth = await api('/api/auth/login', { method: 'POST', body: { email, password } })
  assert(auth.role === 'USER' && auth.accessToken && auth.refreshToken, 'Demo user login failed')
  return auth
}

async function registerOwnedUser(email, password) {
  const auth = await api('/api/auth/register', {
    method: 'POST',
    body: { email, phone: null, password }
  })
  if (auth?.userId) ownedUserIds.add(auth.userId)
  assert(auth.role === 'USER' && auth.accessToken && auth.refreshToken, 'Demo user registration failed')
  return { ...auth, email, password }
}

async function api(path, options = {}) {
  const response = await fetchWithTimeout(`${apiBaseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined
  }, 15_000)
  const payload = await parseJson(response)
  if (!response.ok || payload.success !== true) {
    throw new ApiError(response.status, payload.code ?? 'HTTP_ERROR', payload.message ?? `HTTP ${response.status}`)
  }
  return payload.data
}

async function expectApiError(path, options, allowedStatuses) {
  try {
    await api(path, options)
  } catch (error) {
    assert(error instanceof ApiError, `Expected API error, received ${asError(error).message}`)
    assert(allowedStatuses.includes(error.status), `Expected HTTP ${allowedStatuses.join('/')}, got ${error.status}`)
    return error
  }
  throw new Error(`Expected ${path} to reject unsafe content`)
}

async function rawJson(url) {
  const response = await fetchWithTimeout(url, {}, 10_000)
  assert(response.ok, `${url} returned HTTP ${response.status}`)
  return parseJson(response)
}

async function parseJson(response) {
  const text = await response.text()
  try {
    return text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`Expected JSON from ${redactSensitiveUrl(response.url)}; received a non-JSON response`)
  }
}

async function fetchWithTimeout(url, options, timeoutMs) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(timeoutMs) })
}

async function launchChrome() {
  const executable = chromeExecutable()
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), `${prefix}-browser-`))
  const child = spawn(executable, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--disable-breakpad',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--window-size=1440,900',
    'about:blank'
  ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
  let output = ''
  child.stdout.on('data', (chunk) => { output += chunk.toString() })
  child.stderr.on('data', (chunk) => { output += chunk.toString() })
  await waitFor(async () => {
    if (child.exitCode !== null) throw new Error(`Headless browser exited early: ${output.slice(-500)}`)
    return canFetch(`http://127.0.0.1:${port}/json/version`)
  }, 'Chrome DevTools endpoint', 20_000)
  const version = await rawJson(`http://127.0.0.1:${port}/json/version`)
  assert(version.webSocketDebuggerUrl, 'Chrome DevTools version endpoint must expose webSocketDebuggerUrl')
  const debugPortOwnerPid = process.platform === 'win32' ? windowsDebugPortOwnerPid(port) : child.pid
  assert(debugPortOwnerPid, 'Could not identify the owned Chrome DevTools listener process')
  return {
    child,
    port,
    userDataDir,
    debugPortOwnerPid,
    browserWebSocketDebuggerUrl: version.webSocketDebuggerUrl
  }
}

async function createCdpPage(port, name) {
  const target = await createTarget(port)
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map()
  const listeners = new Map()
  const diagnostics = {
    errors: [],
    warnings: [],
    failedRequests: [],
    requests: [],
    webSockets: { created: [], closed: [], connected: [] },
    expectedFailureUntil: 0,
    expectedFailureReason: null,
    controlledOfflineNoiseUntil: 0
  }
  const requestUrls = new Map()
  const requestRecords = new Map()
  let nextId = 1
  await new Promise((resolveOpen, rejectOpen) => {
    socket.addEventListener('open', resolveOpen, { once: true })
    socket.addEventListener('error', rejectOpen, { once: true })
  })
  socket.addEventListener('message', (event) => {
    const payload = JSON.parse(event.data)
    if (payload.id && pending.has(payload.id)) {
      const callback = pending.get(payload.id)
      pending.delete(payload.id)
      if (payload.error) callback.reject(new Error(payload.error.message))
      else callback.resolve(payload.result)
      return
    }
    for (const listener of listeners.get(payload.method) ?? []) listener(payload.params ?? {})
  })
  const send = (method, params = {}, timeoutMs = 15_000) => new Promise((resolveSend, rejectSend) => {
    const id = nextId++
    const timeout = setTimeout(() => {
      pending.delete(id)
      rejectSend(new Error(`CDP ${method} timed out`))
    }, timeoutMs)
    pending.set(id, {
      resolve(value) { clearTimeout(timeout); resolveSend(value) },
      reject(error) { clearTimeout(timeout); rejectSend(error) }
    })
    socket.send(JSON.stringify({ id, method, params }))
  })
  const page = {
    name,
    targetId: target.id,
    diagnostics,
    send,
    on(method, listener) {
      if (!listeners.has(method)) listeners.set(method, new Set())
      listeners.get(method).add(listener)
    },
    async evaluate(fn, ...args) {
      const result = await send('Runtime.evaluate', {
        expression: `(${fn})(${args.map((argument) => JSON.stringify(argument)).join(',')})`,
        awaitPromise: true,
        returnByValue: true
      })
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? 'Runtime evaluation failed')
      return result.result?.value
    },
    async navigate(url) {
      this.allowExpectedNetworkFailures('navigation', 5_000)
      await this.evaluate(() => { window.__engagementSmokeNavigationMarker = true }).catch(() => undefined)
      await send('Page.navigate', { url })
      await waitFor(() => this.evaluate((expected) => (
        location.href.startsWith(expected)
          && document.readyState === 'complete'
          && window.__engagementSmokeNavigationMarker !== true
      ), url), `load ${url}`, 30_000)
    },
    async reload() {
      this.allowExpectedNetworkFailures('reload', 5_000)
      const expected = await this.evaluate(() => location.href)
      await this.evaluate(() => { window.__engagementSmokeNavigationMarker = true })
      await send('Page.reload', { ignoreCache: true })
      await waitFor(() => this.evaluate((url) => (
        location.href === url
          && document.readyState === 'complete'
          && window.__engagementSmokeNavigationMarker !== true
      ), expected), `reload ${expected}`, 30_000)
      await sleep(300)
    },
    waitForFunction(fn, label, ...args) {
      return waitFor(() => this.evaluate(fn, ...args), label, 30_000)
    },
    bringToFront() { return send('Page.bringToFront') },
    pressKey(key, shift = false) {
      const modifiers = shift ? 8 : 0
      return send('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key, modifiers })
        .then(() => send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: key, modifiers }))
    },
    allowExpectedNetworkFailures(reason, durationMs) {
      diagnostics.expectedFailureUntil = Math.max(diagnostics.expectedFailureUntil, Date.now() + durationMs)
      diagnostics.expectedFailureReason = reason
    },
    async setOffline(offline) {
      if (offline) {
        this.allowExpectedNetworkFailures('controlled-offline', 20_000)
        diagnostics.controlledOfflineNoiseUntil = Date.now() + 30_000
      } else {
        diagnostics.expectedFailureUntil = Date.now() + 5_000
        diagnostics.expectedFailureReason = 'controlled-online'
      }
      await send('Network.emulateNetworkConditions', {
        offline,
        latency: offline ? 0 : 20,
        downloadThroughput: offline ? 0 : -1,
        uploadThroughput: offline ? 0 : -1
      })
    },
    async close(closeTarget = true) {
      try { socket.close() } catch { /* Browser.close may already have closed this socket. */ }
      if (closeTarget) {
        await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`).catch(() => undefined)
      }
    }
  }
  const recordConsoleIssue = (collection, message) => {
    const at = Date.now()
    collection.push({
      message,
      at,
      expected: at <= diagnostics.controlledOfflineNoiseUntil
        && /WebSocket|ERR_INTERNET_DISCONNECTED|ERR_NETWORK_CHANGED|network connection was lost/i.test(message)
    })
  }
  page.on('Runtime.consoleAPICalled', (event) => {
    const message = event.args?.map((argument) => argument.value ?? argument.description).join(' ') ?? event.type
    if (event.type === 'error') recordConsoleIssue(diagnostics.errors, message)
    if (event.type === 'warning') recordConsoleIssue(diagnostics.warnings, message)
  })
  page.on('Runtime.exceptionThrown', (event) => recordConsoleIssue(
    diagnostics.errors,
    event.exceptionDetails?.text ?? 'Runtime exception'
  ))
  page.on('Log.entryAdded', (event) => {
    if (event.entry?.level === 'error') recordConsoleIssue(diagnostics.errors, event.entry.text)
    if (event.entry?.level === 'warning') recordConsoleIssue(diagnostics.warnings, event.entry.text)
  })
  page.on('Network.requestWillBeSent', (event) => {
    if (!event.requestId || !event.request?.url) return
    requestUrls.set(event.requestId, event.request.url)
    const record = {
      requestId: event.requestId,
      url: redactSensitiveUrl(event.request.url),
      method: event.request.method,
      startedAt: Date.now(),
      finishedAt: null,
      failedAt: null
    }
    requestRecords.set(event.requestId, record)
    diagnostics.requests.push(record)
  })
  page.on('Network.loadingFinished', (event) => {
    const record = requestRecords.get(event.requestId)
    if (record) record.finishedAt = Date.now()
  })
  page.on('Network.loadingFailed', (event) => {
    const at = Date.now()
    const errorText = event.errorText ?? 'request failed'
    const controlledFailure = at <= diagnostics.expectedFailureUntil
      && (event.canceled || /ERR_INTERNET_DISCONNECTED|ERR_NETWORK_CHANGED|ERR_ABORTED/i.test(errorText))
    diagnostics.failedRequests.push({
      url: redactSensitiveUrl(requestUrls.get(event.requestId) ?? ''),
      errorText,
      canceled: Boolean(event.canceled),
      at,
      expected: controlledFailure,
      expectedReason: controlledFailure ? diagnostics.expectedFailureReason : null
    })
    const record = requestRecords.get(event.requestId)
    if (record) record.failedAt = at
  })
  page.on('Network.webSocketCreated', (event) => {
    diagnostics.webSockets.created.push({ requestId: event.requestId, url: redactSensitiveUrl(event.url), at: Date.now() })
  })
  page.on('Network.webSocketClosed', (event) => {
    diagnostics.webSockets.closed.push({ requestId: event.requestId, at: Date.now() })
  })
  page.on('Network.webSocketFrameReceived', (event) => {
    const payload = event.response?.payloadData
    if (typeof payload === 'string' && /^CONNECTED(?:\r?\n|$)/u.test(payload)) {
      diagnostics.webSockets.connected.push({ requestId: event.requestId, at: Date.now() })
    }
  })
  await Promise.all([send('Page.enable'), send('Runtime.enable'), send('Network.enable'), send('Log.enable')])
  return page
}

async function createTarget(port) {
  const response = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  assert(response.ok, `Could not create CDP target; HTTP ${response.status}`)
  const target = await response.json()
  assert(target.id && target.webSocketDebuggerUrl, 'CDP target must expose id and webSocketDebuggerUrl')
  return target
}

async function configurePage(page, viewport) {
  await page.send('Page.addScriptToEvaluateOnNewDocument', { source: webSocketCaptureBootstrap() })
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: 1,
    mobile: viewport.mobile,
    screenWidth: viewport.width,
    screenHeight: viewport.height
  })
  await page.send(
    'Emulation.setTouchEmulationEnabled',
    viewport.mobile ? { enabled: true, maxTouchPoints: 1 } : { enabled: false }
  )
  await page.send('Emulation.setEmulatedMedia', {
    media: 'screen',
    features: [{ name: 'prefers-reduced-motion', value: 'reduce' }]
  })
}

function webSocketCaptureBootstrap() {
  return `(() => {
    const NativeWebSocket = window.WebSocket
    const liveSockets = []
    Object.defineProperty(window, '__engagementSmokeWebSockets', {
      configurable: false,
      enumerable: false,
      writable: false,
      value: liveSockets
    })
    class SmokeWebSocket extends NativeWebSocket {
      constructor(...args) {
        super(...args)
        liveSockets.push(this)
        this.addEventListener('close', () => {
          const index = liveSockets.indexOf(this)
          if (index >= 0) liveSockets.splice(index, 1)
        }, { once: true })
      }
    }
    Object.defineProperty(SmokeWebSocket, 'name', { value: 'WebSocket' })
    for (const key of ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED']) {
      Object.defineProperty(SmokeWebSocket, key, {
        configurable: false,
        enumerable: true,
        writable: false,
        value: NativeWebSocket[key]
      })
    }
    const descriptor = Object.getOwnPropertyDescriptor(window, 'WebSocket')
    Object.defineProperty(window, 'WebSocket', {
      configurable: descriptor?.configurable ?? true,
      enumerable: descriptor?.enumerable ?? false,
      writable: descriptor?.writable ?? true,
      value: SmokeWebSocket
    })
  })()`
}

async function setAuthTokens(page, user) {
  await page.navigate(`${webBaseUrl}/login?seed=${encodeURIComponent(runId)}`)
  await page.evaluate((accessToken, refreshToken, email) => {
    localStorage.setItem('fx-platform-auth-token', accessToken)
    localStorage.setItem('fx-platform-auth-refresh-token', refreshToken)
    localStorage.setItem('fx-platform-user-email', email)
    window.dispatchEvent(new Event('fx-platform-auth-session-changed'))
  }, user.accessToken, user.refreshToken, user.email)
}

async function focusSmokeAnchor(page) {
  await page.evaluate(() => {
    const anchor = [...document.querySelectorAll('a,button')].find((element) => {
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && !element.disabled
    })
    if (!(anchor instanceof HTMLElement)) throw new Error('No focus anchor found before popup')
    anchor.setAttribute('data-smoke-focus-anchor', '')
    anchor.focus()
  })
}

async function triggerBrowserReconciliation(page) {
  await page.bringToFront()
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
}

async function clickRouteLink(page, pathname) {
  await page.evaluate((expectedPath) => {
    const link = [...document.querySelectorAll('a[href]')].find((candidate) => candidate.getAttribute('href') === expectedPath)
    if (!(link instanceof HTMLAnchorElement)) throw new Error(`Route link ${expectedPath} is missing`)
    link.click()
  }, pathname)
  await waitForApp(page, pathname)
}

async function clickPopupButtonByText(page, text) {
  await page.evaluate((expected) => {
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
    const button = [...(dialog?.querySelectorAll('button') ?? [])].find((candidate) => candidate.textContent?.trim() === expected)
    if (!(button instanceof HTMLButtonElement)) throw new Error(`Popup button ${expected} is missing`)
    button.click()
  }, text)
}

function completedPopupNextCount(page) {
  return page.diagnostics.requests.filter((request) => (
    request.method === 'POST'
      && request.finishedAt
      && /\/api\/me\/engagement\/popup-queues\/[^/]+\/next(?:\?|$)/u.test(request.url)
  )).length
}

async function clickPopupCloseAndWaitForQueue(page) {
  const before = completedPopupNextCount(page)
  await clickPopupButtonByText(page, 'Close')
  await waitFor(() => completedPopupNextCount(page) > before, `${page.name} popup close queue settlement`, 20_000)
}

async function optOutPopupAndWaitForQueue(page) {
  const before = completedPopupNextCount(page)
  await clickPopupButtonByText(page, "Don't show again")
  await waitFor(() => completedPopupNextCount(page) > before, `${page.name} popup opt-out queue settlement`, 20_000)
}

async function escapePopupAndWaitForQueue(page) {
  const before = completedPopupNextCount(page)
  await page.pressKey('Escape')
  await waitFor(() => completedPopupNextCount(page) > before, `${page.name} Escape queue settlement`, 20_000)
}

async function waitForInvalidationQueueSettlement(page, before, label) {
  await waitFor(() => completedPopupNextCount(page) > before, `${page.name} ${label} queue settlement`, 20_000)
}

async function waitForApp(page, pathname) {
  await page.waitForFunction((expected) => (
    location.pathname === expected
      && (document.body.textContent?.trim().length ?? 0) > 40
      && !document.querySelector('[data-nextjs-dialog-overlay], vite-error-overlay, #webpack-dev-server-client-overlay')
  ), `app route ${pathname}`, pathname)
}

async function waitForPopup(page, title) {
  await page.waitForFunction((expected) => {
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
    return dialog?.querySelector('h2')?.textContent === expected
  }, `popup ${title}`, title)
}

async function waitForNoPopup(page, timeoutMs = 30_000) {
  await waitFor(() => page.evaluate(() => !document.querySelector('[role="dialog"][aria-modal="true"]')), `${page.name} popup closes`, timeoutMs)
}

async function assertNoPopup(page, durationMs) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < durationMs) {
    const open = await page.evaluate(() => Boolean(document.querySelector('[role="dialog"][aria-modal="true"]')))
    assert(!open, `${page.name} unexpectedly rendered a popup`)
    await sleep(150)
  }
}

async function assertPopupAbsentNow(pages, label) {
  for (const page of pages) {
    const open = await page.evaluate(() => Boolean(document.querySelector('[role="dialog"][aria-modal="true"]')))
    assert(!open, `${page.name} unexpectedly rendered a popup during ${label}`)
  }
}

async function waitForExactUnreadWithoutPopup(pages, expectedUnread, label) {
  await waitFor(async () => {
    await assertPopupAbsentNow(pages, label)
    const labels = await Promise.all(pages.map((page) => page.evaluate(
      () => document.querySelector('[aria-label^="Messages"]')?.getAttribute('aria-label') ?? null
    )))
    return labels.every((value) => value === `Messages, ${expectedUnread} unread`)
  }, label, 20_000)
}

async function popupTitle(page) {
  return page.evaluate(() => document.querySelector('[role="dialog"][aria-modal="true"] h2')?.textContent ?? null)
}

async function verifyPopupAccessibility(page, expectedTitle, requireScroll) {
  const result = await page.evaluate((title) => {
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
    if (!(dialog instanceof HTMLElement)) return null
    const labelledBy = dialog.getAttribute('aria-labelledby')
    const label = labelledBy ? document.getElementById(labelledBy)?.textContent : dialog.getAttribute('aria-label')
    const buttons = [...dialog.querySelectorAll('button:not(:disabled)')]
    const dialogContentScrollable = [...dialog.querySelectorAll('div')].some((element) => {
      const style = getComputedStyle(element)
      return /auto|scroll/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 1
    })
    const mainRegion = document.querySelector('main')
    return {
      ariaModal: dialog.getAttribute('aria-modal'),
      label,
      closeLabel: buttons[0]?.getAttribute('aria-label'),
      activeInside: dialog.contains(document.activeElement),
      focusableButtons: buttons.length,
      dialogContentScrollable,
      mainRegionLocked: mainRegion instanceof HTMLElement && getComputedStyle(mainRegion).overflowY === 'hidden',
      bodyLocked: getComputedStyle(document.body).overflowY === 'hidden',
      reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
      bodyText: dialog.textContent?.slice(0, 180),
      expectedTitle: title
    }
  }, expectedTitle)
  assert(result?.ariaModal === 'true', 'Popup must expose aria-modal=true')
  assert(result.label === expectedTitle, 'Popup accessible label must equal its title')
  assert(result.closeLabel, 'Popup close control must expose a screen reader label')
  assert(result.activeInside, 'Initial focus must be inside the popup')
  assert(result.focusableButtons >= 3, 'Popup focus order must include close, opt-out, and dismiss')
  assert(result.mainRegionLocked, '.mainRegion must be scroll-locked while the popup is open')
  assert(result.bodyLocked, 'Document body must be scroll-locked while the popup is open')
  assert(result.reducedMotion, 'Reduced-motion media emulation must be active')
  if (requireScroll) assert(result.dialogContentScrollable, 'Long popup content must scroll inside the dialog')
  return result
}

async function focusIsInsideDialog(page) {
  return page.evaluate(() => document.querySelector('[role="dialog"][aria-modal="true"]')?.contains(document.activeElement) ?? false)
}

async function waitForMessage(page, title) {
  await page.waitForFunction((expected) => [...document.querySelectorAll('article h2')].some((heading) => heading.textContent === expected), `message ${title}`, title)
}

async function clickMessageAction(page, title, label) {
  await page.evaluate((expectedTitle, expectedLabel) => {
    const heading = [...document.querySelectorAll('article h2')].find((node) => node.textContent === expectedTitle)
    const article = heading?.closest('article')
    const button = [...(article?.querySelectorAll('button') ?? [])].find((node) => node.textContent?.trim() === expectedLabel)
    if (!(button instanceof HTMLButtonElement)) throw new Error(`${expectedLabel} is missing for ${expectedTitle}`)
    button.click()
  }, title, label)
}

async function waitForMessageReadState(page, title, unread) {
  await waitFor(() => page.evaluate((expectedTitle, expectedUnread) => {
    const heading = [...document.querySelectorAll('article h2')].find((node) => node.textContent === expectedTitle)
    const article = heading?.closest('article')
    return Boolean(article) && Boolean([...article.querySelectorAll('span')].some((span) => span.textContent === 'Unread')) === expectedUnread
  }, title, unread), `${title} unread=${unread}`, 20_000)
}

async function clickButtonByText(page, text) {
  await page.evaluate((expected) => {
    const button = [...document.querySelectorAll('button')].find((node) => node.textContent?.trim().startsWith(expected))
    if (!(button instanceof HTMLButtonElement)) throw new Error(`Button ${expected} is missing`)
    button.click()
  }, text)
}

async function assertPageHealth(page, pathname) {
  return page.evaluate((expectedPath) => {
    const body = document.body.textContent?.trim() ?? ''
    const overlay = Boolean(document.querySelector('[data-nextjs-dialog-overlay], vite-error-overlay, #webpack-dev-server-client-overlay'))
    if (location.pathname !== expectedPath) throw new Error(`Expected ${expectedPath}, got ${location.pathname}`)
    if (document.title !== 'FX Trader') throw new Error(`Unexpected page title ${document.title}`)
    if (body.length < 40) throw new Error('Page is blank')
    if (overlay) throw new Error('Framework error overlay is visible')
    return { url: location.href, title: document.title, bodyLength: body.length, overlay }
  }, pathname)
}

async function captureScreenshot(page, suffix) {
  await page.bringToFront()
  await page.evaluate(() => new Promise((resolveFrame) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(fallback)
      resolveFrame()
    }
    const fallback = setTimeout(finish, 500)
    requestAnimationFrame(() => requestAnimationFrame(finish))
  }))
  const options = {
    format: 'png',
    fromSurface: true,
    captureBeyondViewport: false,
    optimizeForSpeed: true
  }
  let result
  try {
    result = await page.send('Page.captureScreenshot', options, 30_000)
  } catch (error) {
    if (!/Page\.captureScreenshot timed out/i.test(asError(error).message)) throw error
    await page.bringToFront()
    await page.send('Page.stopLoading', {}, 5_000).catch(() => undefined)
    await sleep(250)
    result = await page.send('Page.captureScreenshot', options, 30_000)
  }
  const path = join(artifactDirectory, `${prefix}-${suffix}`)
  await writeFile(path, Buffer.from(result.data, 'base64'))
  report.artifacts.push(path)
  return path
}

function isExpectedBrowserNoise(message) {
  return /favicon|React Router Future Flag/i.test(message)
}

function isApplicationWebSocket(socket) {
  try {
    return new URL(socket.url).pathname === '/ws'
  } catch {
    return false
  }
}

async function runScenario(name, action) {
  assert(scenarioNames.includes(name), `Unknown engagement scenario ${name}`)
  const startedAt = Date.now()
  try {
    const details = await action()
    report.scenarios.push({ name, status: 'PASS', durationMs: Date.now() - startedAt, details })
    return details
  } catch (error) {
    report.scenarios.push({ name, status: 'FAIL', durationMs: Date.now() - startedAt, error: asError(error).message })
    throw error
  }
}

async function waitFor(check, label, timeoutMs = 10_000) {
  const startedAt = Date.now()
  let lastError = null
  while (Date.now() - startedAt < timeoutMs) {
    try {
      if (await check()) return
    } catch (error) {
      lastError = asError(error)
    }
    await sleep(250)
  }
  throw new Error(`${label} timed out${lastError ? `: ${lastError.message}` : ''}`)
}

async function canFetch(url) {
  try {
    const response = await fetchWithTimeout(url, {}, 1_500)
    return response.ok
  } catch {
    return false
  }
}

function freePort() {
  return new Promise((resolvePort, rejectPort) => {
    const server = net.createServer()
    server.on('error', rejectPort)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => resolvePort(address.port))
    })
  })
}

function chromeExecutable() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.SMOKE_BROWSER_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)
  const executable = candidates.find((candidate) => existsSync(candidate))
  assert(executable, 'Chrome or Edge is required; set CHROME_PATH')
  return executable
}

function killProcessTree(child) {
  if (!child?.pid || child.exitCode !== null) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

async function requestBrowserClose(browser) {
  const socket = new WebSocket(browser.browserWebSocketDebuggerUrl)
  try {
    await new Promise((resolveOpen, rejectOpen) => {
      const timer = setTimeout(() => finish(new Error('Browser DevTools socket open timed out')), 3_000)
      const onOpen = () => finish()
      const onError = () => finish(new Error('Browser DevTools socket failed to open'))
      const onClose = () => finish(new Error('Browser DevTools socket closed before opening'))
      const finish = (error) => {
        clearTimeout(timer)
        socket.removeEventListener('open', onOpen)
        socket.removeEventListener('error', onError)
        socket.removeEventListener('close', onClose)
        if (error) rejectOpen(error)
        else resolveOpen()
      }
      socket.addEventListener('open', onOpen, { once: true })
      socket.addEventListener('error', onError, { once: true })
      socket.addEventListener('close', onClose, { once: true })
    })

    await new Promise((resolveClose, rejectClose) => {
      const commandId = 1
      const timer = setTimeout(() => finish(new Error('CDP Browser.close timed out')), 3_000)
      const onMessage = (event) => {
        const payload = JSON.parse(event.data)
        if (payload.id !== commandId) return
        finish(payload.error ? new Error(payload.error.message) : null)
      }
      const onClose = () => finish()
      const onError = () => finish()
      const finish = (error) => {
        clearTimeout(timer)
        socket.removeEventListener('message', onMessage)
        socket.removeEventListener('close', onClose)
        socket.removeEventListener('error', onError)
        if (error) rejectClose(error)
        else resolveClose()
      }
      socket.addEventListener('message', onMessage)
      socket.addEventListener('close', onClose, { once: true })
      socket.addEventListener('error', onError, { once: true })
      socket.send(JSON.stringify({ id: commandId, method: 'Browser.close' }))
    })
    return true
  } finally {
    try { socket.close() } catch { /* The browser normally closes the socket first. */ }
  }
}

async function forceStopOwnedDebugPortProcess(browser) {
  if (process.platform !== 'win32') return
  await sleep(250)
  const ownerPid = windowsDebugPortOwnerPid(browser.port)
  if (!ownerPid) return
  if (ownerPid !== browser.debugPortOwnerPid) return
  assert(ownerPid !== process.pid, 'Refusing to stop the smoke harness process')
  spawnSync('taskkill.exe', ['/PID', String(ownerPid), '/T', '/F'], { stdio: 'ignore' })
  await sleep(250)
  if (windowsDebugPortOwnerPid(browser.port) !== ownerPid) return
  try {
    process.kill(ownerPid, 'SIGKILL')
  } catch (error) {
    if (asError(error).code !== 'ESRCH') throw error
  }
}

function windowsDebugPortOwnerPid(port) {
  const result = spawnSync('netstat.exe', ['-ano', '-p', 'TCP'], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.error || result.status !== 0) return null
  const suffix = `:${port}`
  for (const line of result.stdout.split(/\r?\n/u)) {
    const columns = line.trim().split(/\s+/u)
    if (
      columns[0]?.toUpperCase() !== 'TCP'
      || !columns[1]?.endsWith(suffix)
      || columns[3]?.toUpperCase() !== 'LISTENING'
    ) continue
    const pid = Number(columns.at(-1))
    if (Number.isSafeInteger(pid) && pid > 0) return pid
  }
  return null
}

async function isTcpPortOpen(port) {
  return new Promise((resolveOpen) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let settled = false
    const finish = (open) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolveOpen(open)
    }
    socket.setTimeout(300, () => finish(false))
    socket.once('connect', () => finish(true))
    socket.once('error', () => finish(false))
  })
}

async function waitForOwnedBrowserShutdown(browser, timeoutMs = 10_000) {
  await waitFor(async () => {
    const childExited = browser.child.exitCode !== null || browser.child.signalCode !== null
    const debugPortDown = !(await isTcpPortOpen(browser.port))
    return childExited && debugPortDown
  }, 'owned Chrome process exit and DevTools shutdown', timeoutMs)
}

async function safeRemoveOwnedBrowserProfile(path) {
  if (!path) return
  const expectedPrefix = `${prefix}-browser-`
  assert(dirname(path) === tmpdir() && basename(path).startsWith(expectedPrefix), 'Refusing to remove an unowned browser profile')
  await rm(path, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}

function assertLoopbackUrl(value, label) {
  const url = new URL(value)
  assert(['http:', 'https:'].includes(url.protocol), `${label} must use HTTP(S)`)
  assert(['localhost', '127.0.0.1', '::1', '[::1]'].includes(url.hostname), `${label} must remain loopback-only`)
  assert(!url.username && !url.password, `${label} must not embed credentials`)
}

function stripTrailingSlash(value) {
  return value.replace(/\/+$/, '')
}

function normalizeRunId(value) {
  const normalized = value.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '')
  assert(normalized.length >= 4 && normalized.length <= 48, 'ENGAGEMENT_SMOKE_RUN_ID must normalize to 4-48 characters')
  return normalized
}

function positiveInteger(value, label) {
  const number = Number(value)
  assert(Number.isInteger(number) && number > 0, `${label} must be a positive integer`)
  return number
}

function redactReport(value) {
  const sensitiveKeys = /password|token|authorization|credential|secret/i
  return JSON.parse(JSON.stringify(value, (key, current) => {
    if (sensitiveKeys.test(key)) return '[REDACTED]'
    return typeof current === 'string' ? redactSensitiveUrl(current) : current
  }))
}

function serializeError(error) {
  return {
    name: error.name,
    message: redactSensitiveUrl(error.message),
    stack: redactSensitiveUrl(error.stack ?? '')
  }
}

function redactSensitiveUrl(value) {
  return String(value).replace(/(\/api\/me\/engagement\/popup-deliveries\/)[^/?#\s]+/giu, '$1[REDACTED]')
}

function combineErrors(primary, secondary, label) {
  if (!primary) return new Error(`${label} failed: ${secondary.message}`, { cause: secondary })
  return new AggregateError([primary, secondary], `${primary.message}; ${label} failed: ${secondary.message}`)
}

function asError(error) {
  return error instanceof Error ? error : new Error(String(error))
}

function sleep(timeoutMs) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, timeoutMs))
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
