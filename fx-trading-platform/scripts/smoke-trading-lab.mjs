import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import {
  existsSync,
  lstatSync,
  realpathSync,
} from 'node:fs'
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from 'node:fs/promises'
import net from 'node:net'
import { tmpdir } from 'node:os'
import {
  basename,
  dirname,
  join,
  resolve,
} from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  canonicalJson,
  hashScenarioConfig,
  normalizeScenario,
} from '../apps/admin/src/features/tradingLab/model/normalization.ts'
import {
  generateRandomScenario,
} from '../apps/admin/src/features/tradingLab/generator/randomScenario.ts'

const platformRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const VIEWPORT_WIDTH = 1440
const VIEWPORT_HEIGHT = 900
const MOBILE_VIEWPORT_WIDTH = 390
const MOBILE_VIEWPORT_HEIGHT = 844
const TRADING_LAB_NARROW_MESSAGE =
  '交易路径实验室首版仅支持宽度不低于 1280px 的桌面端。'
const DEFAULT_API_URL = 'http://127.0.0.1:18086'
const DEFAULT_ADMIN_URL = 'http://127.0.0.1:5200'
const ARTIFACT_MARKER = '.trading-lab-owner.json'
const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const RUNNING_STATES = new Set(['RESETTING', 'RUNNING', 'PAUSED'])
const REQUIRED_FULL_AUTHORITIES = [
  'TRADING_LAB_VIEW',
  'TRADING_LAB_EXECUTE',
  'SUPER_ADMIN',
]
const MAX_DIAGNOSTIC_ROWS = 5_000
const RUN_TIMEOUT_MS = 240_000
const PAGE_TIMEOUT_MS = 45_000
const OWNED_DOWNLOAD_MODE = 'BLOB_FALLBACK'
const PERMISSION_ENVIRONMENT_PATH =
  '/api/admin/trading-lab/environment'
const EXPECTED_PERMISSION_DENIAL_CONSOLE_MESSAGE =
  'Failed to load resource: the server responded with a status of 403 ()'

export async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv)
  const startedAt = new Date().toISOString()
  const context = {
    options,
    artifacts: null,
    ownedProcesses: [],
    browser: null,
    page: null,
    downloadMode: null,
    screenshots: [],
    journeys: [],
    createdRuns: [],
    referencedRunIds: [],
    referencedReports: [],
    sseCloseReceipts: [],
    downloadReceipts: [],
    printCompletionReceipts: [],
    consoleErrorReceipts: [],
    requestLogObservations: [],
    environment: {},
    core: null,
    status: 'RUNNING',
    error: null,
    finalSummary: null,
  }
  let firstFailure = null

  try {
    context.artifacts = await prepareArtifacts(options.artifacts)
    context.accounts = accountsFromEnvironment()
    await discoverEnvironment(context)
    context.browser = await launchBrowser(context)
    context.page = await createCdpPage(
      context.browser.port,
      context.artifacts.downloads,
    )
    await configurePage(context.page, {
      headless: !context.options.headed,
    })

    await loginThroughAdminForm(
      context.page,
      context.options.adminUrl,
      context.accounts.superAdmin,
      REQUIRED_FULL_AUTHORITIES,
      '/trading/lab',
    )
    context.downloadMode = await requireOwnedDownloadMode(context.page)
    await runJourney(context, 'mobile guard journey', runMobileGuardJourney)
    context.core = await runJourney(context, 'core UI journey', runCoreJourney)
    await runJourney(context, 'random and chart journey', runRandomChartJourney)
    await runJourney(context, 'permission journey', runPermissionJourney)
    await runJourney(context, 'negative journey', runNegativeJourney)
    await runJourney(
      context,
      'failure and cancel journey',
      runFailureCancelJourney,
    )
    await runJourney(context, 'print journey', runPrintJourney)

    context.page.diagnostics.failures =
      classifyExpectedCanceledNetworkFailures({
        diagnostics: context.page.diagnostics,
        sseCloseReceipts: context.sseCloseReceipts,
        downloadReceipts: context.downloadReceipts,
        printReceipts: context.printCompletionReceipts,
        ownedRunIds: [
          ...context.createdRuns.map(({ runId }) => runId),
          ...context.referencedRunIds,
        ],
        ownedReportIds: [
          ...context.createdRuns.map(({ reportId }) => reportId),
          ...context.referencedReports.map(({ reportId }) => reportId),
        ],
      })
    context.page.diagnostics.console = classifyExpectedConsoleErrors({
      diagnostics: context.page.diagnostics,
      receipts: context.consoleErrorReceipts,
    })
    assertSseEvidence(context.page.diagnostics)
    assertNoUnexplainedDiagnostics(context.page.diagnostics)
    context.status = 'PASS'
  } catch (error) {
    firstFailure = asError(error)
    context.status = 'FAIL'
    context.error = serializeError(firstFailure)
    if (context.page !== null && context.artifacts !== null) {
      await captureScreenshot(context, 'failure').catch(() => undefined)
    }
  } finally {
    if (context.artifacts !== null) {
      try {
        await persistEvidence(context)
      } catch (error) {
        firstFailure = combineErrors(firstFailure, asError(error), 'evidence')
        context.status = 'FAIL'
        context.error = serializeError(firstFailure)
      }
    }

    try {
      await context.page?.close()
    } catch (error) {
      firstFailure = combineErrors(firstFailure, asError(error), 'page close')
      context.status = 'FAIL'
      context.error = serializeError(firstFailure)
    }

    try {
      await stopOwnedProcesses(context.ownedProcesses)
    } catch (error) {
      firstFailure = combineErrors(
        firstFailure,
        asError(error),
        'owned process cleanup',
      )
      context.status = 'FAIL'
      context.error = serializeError(firstFailure)
    }

    if (context.browser?.userDataDir !== undefined) {
      try {
        await removeOwnedBrowserProfile(context.browser.userDataDir)
      } catch (error) {
        firstFailure = combineErrors(
          firstFailure,
          asError(error),
          'browser profile cleanup',
        )
        context.status = 'FAIL'
        context.error = serializeError(firstFailure)
      }
    }

    if (context.artifacts !== null) {
      try {
        await persistProcessLogs(context)
        context.finalSummary = summaryFor(context, startedAt)
        await writeJsonExclusive(
          join(context.artifacts.root, 'summary.json'),
          context.finalSummary,
        )
      } catch (error) {
        firstFailure = combineErrors(firstFailure, asError(error), 'summary')
        context.status = 'FAIL'
        context.error = serializeError(firstFailure)
        context.finalSummary = summaryFor(
          context,
          startedAt,
          { failClosed: false },
        )
      }
    }
  }

  if (context.finalSummary === null) {
    try {
      context.finalSummary = summaryFor(context, startedAt)
    } catch (error) {
      firstFailure = combineErrors(firstFailure, asError(error), 'summary')
      context.status = 'FAIL'
      context.error = serializeError(firstFailure)
      context.finalSummary = summaryFor(
        context,
        startedAt,
        { failClosed: false },
      )
    }
  }
  if (firstFailure !== null) {
    Object.defineProperty(firstFailure, 'smokeSummary', {
      configurable: true,
      value: context.finalSummary,
    })
    throw firstFailure
  }
  return context.finalSummary
}

function parseArguments(argv) {
  const parsed = {
    artifacts: null,
    adminUrl: process.env.TRADING_LAB_SMOKE_ADMIN_URL ?? DEFAULT_ADMIN_URL,
    apiUrl: process.env.TRADING_LAB_SMOKE_API_URL ?? DEFAULT_API_URL,
    largeRunId: process.env.TRADING_LAB_SMOKE_LARGE_RUN_ID ?? null,
    headed: false,
    attachAdmin: false,
  }
  for (const argument of argv) {
    if (argument.startsWith('--artifacts=')) {
      parsed.artifacts = argument.slice('--artifacts='.length)
    } else if (argument.startsWith('--admin-url=')) {
      parsed.adminUrl = argument.slice('--admin-url='.length)
    } else if (argument.startsWith('--api-url=')) {
      parsed.apiUrl = argument.slice('--api-url='.length)
    } else if (argument.startsWith('--large-run-id=')) {
      parsed.largeRunId = argument.slice('--large-run-id='.length)
    } else if (argument === '--headed') {
      parsed.headed = true
    } else if (argument === '--attach-admin') {
      parsed.attachAdmin = true
    } else {
      throw new Error(`Unknown Trading Lab smoke argument: ${argument}`)
    }
  }
  parsed.adminUrl = strictLoopbackUrl(parsed.adminUrl, 'Admin URL')
  parsed.apiUrl = strictLoopbackUrl(parsed.apiUrl, 'backend API URL')
  const apiPort = Number(new URL(parsed.apiUrl).port || 80)
  if (apiPort === 8080) {
    throw new Error('Trading Lab browser smoke refuses to occupy or use host 8080')
  }
  if (parsed.adminUrl === parsed.apiUrl) {
    throw new Error('Admin and backend identities must use distinct origins')
  }
  if (
    typeof parsed.largeRunId !== 'string'
    || !canonicalUuidPattern().test(parsed.largeRunId)
  ) {
    throw new Error(
      'Trading Lab smoke large run ID must be a canonical UUID',
    )
  }
  return Object.freeze(parsed)
}

function strictLoopbackUrl(value, label) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${label} is invalid`)
  }
  if (
    url.protocol !== 'http:'
    || !['127.0.0.1', 'localhost'].includes(url.hostname)
    || url.username.length > 0
    || url.password.length > 0
    || url.search.length > 0
    || url.hash.length > 0
  ) {
    throw new Error(`${label} must be a credential-free loopback HTTP origin`)
  }
  url.pathname = url.pathname.replace(/\/+$/u, '')
  return url.toString().replace(/\/$/u, '')
}

async function prepareArtifacts(requestedPath) {
  let root
  if (requestedPath === null) {
    root = await mkdtemp(join(tmpdir(), 'fx-trading-lab-smoke-'))
    await writeFile(
      join(root, ARTIFACT_MARKER),
      `${JSON.stringify({
        schemaVersion: 1,
        runToken: `${randomUUID()}${randomUUID()}`,
      })}\n`,
      { encoding: 'utf8', flag: 'wx' },
    )
  } else {
    root = resolve(requestedPath)
  }
  root = await assertOwnedArtifactDirectory(root)
  const screenshots = join(root, 'screenshots')
  const downloads = join(root, 'downloads')
  const logs = join(root, 'logs')
  await Promise.all([
    mkdir(screenshots),
    mkdir(downloads),
    mkdir(logs),
  ])
  return Object.freeze({ root, screenshots, downloads, logs })
}

export async function assertOwnedArtifactDirectory(path) {
  const resolved = resolve(path)
  if (!existsSync(resolved) || !lstatSync(resolved).isDirectory()) {
    throw new Error('Trading Lab artifact directory must already exist')
  }
  if (lstatSync(resolved).isSymbolicLink()) {
    throw new Error('Trading Lab artifact directory cannot be a symlink')
  }
  if (realpathSync(resolved) !== resolved) {
    throw new Error('Trading Lab artifact directory must be canonical')
  }
  const markerPath = join(resolved, ARTIFACT_MARKER)
  if (!existsSync(markerPath) || lstatSync(markerPath).isSymbolicLink()) {
    throw new Error('Trading Lab artifact owner marker is missing or unsafe')
  }
  let marker
  try {
    marker = JSON.parse(await readFile(markerPath, 'utf8'))
  } catch {
    throw new Error('Trading Lab artifact owner marker is invalid')
  }
  if (
    marker?.schemaVersion !== 1
    || typeof marker.runToken !== 'string'
    || Buffer.byteLength(marker.runToken, 'utf8') < 32
    || /\s/u.test(marker.runToken)
  ) {
    throw new Error('Trading Lab artifact owner marker is not trustworthy')
  }
  return resolved
}

function accountsFromEnvironment() {
  const superAdmin = account(
    'SUPER_ADMIN',
    process.env.TRADING_LAB_SMOKE_SUPER_EMAIL ?? 'admin-smoke@example.com',
    process.env.TRADING_LAB_SMOKE_SUPER_PASSWORD ?? 'Password123!',
  )
  const viewOnly = account(
    'VIEW-only',
    requiredEnvironment('TRADING_LAB_SMOKE_VIEW_EMAIL'),
    requiredEnvironment('TRADING_LAB_SMOKE_VIEW_PASSWORD'),
  )
  const execute = account(
    'EXECUTE',
    requiredEnvironment('TRADING_LAB_SMOKE_EXECUTE_EMAIL'),
    requiredEnvironment('TRADING_LAB_SMOKE_EXECUTE_PASSWORD'),
  )
  const ordinary = account(
    'ordinary Admin',
    requiredEnvironment('TRADING_LAB_SMOKE_ORDINARY_EMAIL'),
    requiredEnvironment('TRADING_LAB_SMOKE_ORDINARY_PASSWORD'),
  )
  const emails = [superAdmin, viewOnly, execute, ordinary]
    .map((entry) => entry.email.toLowerCase())
  assert.equal(
    new Set(emails).size,
    emails.length,
    'Permission proof requires four distinct Admin identities',
  )
  return Object.freeze({ superAdmin, viewOnly, execute, ordinary })
}

function account(label, email, password) {
  if (
    typeof email !== 'string'
    || !email.includes('@')
    || typeof password !== 'string'
    || password.length === 0
  ) {
    throw new Error(`${label} browser-smoke credentials are invalid`)
  }
  return Object.freeze({ label, email, password })
}

function requiredEnvironment(name) {
  const value = process.env[name]
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${name} is required for the real permission journey`)
  }
  return value
}

async function discoverEnvironment(context) {
  await waitForBackendHealth(context.options.apiUrl)
  context.environment.backend = await assertBackendIdentity(
    context.options.apiUrl,
  )
  context.requestLogObservations.push(
    context.environment.backend.requestLogObservation,
  )

  const probe = await probeAdminIdentity(context.options.adminUrl)
  if (probe.kind === 'WRONG_IDENTITY') {
    throw new Error(
      `Admin port is occupied by a different service: ${probe.reason}`,
    )
  }
  if (probe.kind === 'UNAVAILABLE') {
    if (context.options.attachAdmin) {
      throw new Error('Admin attach was required but the Admin origin is down')
    }
    const ownedAdmin = await launchOwnedAdmin(context)
    await waitForAdminReady(context.options.adminUrl)
    ownedAdmin.listenerOwnerPid = process.platform === 'win32'
      ? windowsPortOwnerPid(ownedAdmin.ownedPort)
      : ownedAdmin.pid
    assert.ok(
      ownedAdmin.listenerOwnerPid,
      'Owned Admin listener PID is unknown',
    )
  }
  context.environment.admin = await assertAdminIdentity(
    context.options.adminUrl,
  )
}

async function waitForBackendHealth(apiUrl) {
  await waitFor(async () => {
    try {
      const response = await fetch(`${apiUrl}/actuator/health`, {
        signal: AbortSignal.timeout(2_000),
      })
      if (!response.ok) return false
      const payload = await response.json()
      return payload?.status === 'UP'
    } catch {
      return false
    }
  }, 'backend actuator health', 30_000)
}

export async function assertBackendIdentity(
  apiUrl,
  {
    fetchImpl = fetch,
    randomUuid = randomUUID,
  } = {},
) {
  const healthResponse = await fetchImpl(`${apiUrl}/actuator/health`, {
    signal: AbortSignal.timeout(5_000),
  })
  assert.equal(healthResponse.ok, true, 'Backend health endpoint must be 2xx')
  const health = await healthResponse.json()
  assert.equal(health?.status, 'UP', 'Backend actuator health must be UP')

  const requestLogRequestId = requireCanonicalOwnershipUuid(
    randomUuid(),
    'backend identity request ID',
  )
  const routeResponse = await fetchImpl(
    `${apiUrl}/api/admin/trading-lab/environment`,
    {
      headers: { 'X-Request-Id': requestLogRequestId },
      redirect: 'manual',
      signal: AbortSignal.timeout(5_000),
    },
  )
  assert.ok(
    [401, 403].includes(routeResponse.status),
    `Backend port identity failed; Trading Lab route returned ${routeResponse.status}`,
  )
  assert.equal(
    routeResponse.headers.get('x-request-id'),
    requestLogRequestId,
    'Backend identity request correlation drifted',
  )
  return Object.freeze({
    origin: apiUrl,
    health: health.status,
    unauthenticatedTradingLabStatus: routeResponse.status,
    requestLogObservation: buildRequestLogObservation({
      requestId: requestLogRequestId,
      method: 'GET',
      path: '/api/admin/trading-lab/environment',
      statusCode: routeResponse.status,
    }),
  })
}

async function probeAdminIdentity(adminUrl) {
  try {
    const response = await fetch(adminUrl, {
      redirect: 'manual',
      signal: AbortSignal.timeout(2_000),
    })
    const source = await response.text()
    if (
      response.ok
      && /<title>外汇后台管理<\/title>/u.test(source)
      && /<div id="root"><\/div>/u.test(source)
    ) {
      return { kind: 'MATCH' }
    }
    return {
      kind: 'WRONG_IDENTITY',
      reason: `HTTP ${response.status} without the Admin root signature`,
    }
  } catch (error) {
    if (
      error?.cause?.code === 'ECONNREFUSED'
      || error?.cause?.code === 'ECONNRESET'
      || error?.name === 'TimeoutError'
      || error?.name === 'AbortError'
    ) {
      return { kind: 'UNAVAILABLE' }
    }
    throw error
  }
}

async function waitForAdminReady(adminUrl) {
  await waitFor(async () => {
    const result = await probeAdminIdentity(adminUrl)
    if (result.kind === 'WRONG_IDENTITY') {
      throw new Error(`Owned Admin failed identity: ${result.reason}`)
    }
    return result.kind === 'MATCH'
  }, 'owned Admin page readiness', 60_000)
}

async function assertAdminIdentity(adminUrl) {
  const response = await fetch(adminUrl, {
    signal: AbortSignal.timeout(5_000),
  })
  const source = await response.text()
  assert.equal(response.ok, true, 'Admin root must be reachable')
  assert.match(source, /<title>外汇后台管理<\/title>/u)
  assert.match(source, /<div id="root"><\/div>/u)
  return Object.freeze({ origin: adminUrl, title: '外汇后台管理' })
}

export function buildChildProcessEnvironment(
  environment = process.env,
  additions = {},
) {
  if (
    environment === null
    || typeof environment !== 'object'
    || Array.isArray(environment)
    || additions === null
    || typeof additions !== 'object'
    || Array.isArray(additions)
  ) {
    throw new TypeError('Trading Lab child environment must be an object')
  }
  const childEnvironment = {}
  for (const [name, value] of Object.entries(environment)) {
    if (
      typeof value === 'string'
      && !isSensitiveChildEnvironmentName(name)
    ) {
      childEnvironment[name] = value
    }
  }
  for (const [name, value] of Object.entries(additions)) {
    if (
      isSensitiveChildEnvironmentName(name)
      || typeof value !== 'string'
    ) {
      throw new Error('Trading Lab child environment addition is unsafe')
    }
    childEnvironment[name] = value
  }
  return childEnvironment
}

function isSensitiveChildEnvironmentName(name) {
  const normalized = String(name).toUpperCase()
  return (
    /(?:^|_)(?:PASSWORD|PASSWD|SECRET|TOKEN|AUTHORIZATION|COOKIE|API_KEY|PRIVATE_KEY|ENCRYPTION_KEY|EMAIL)(?:_|$)/u
      .test(normalized)
    || /(?:^|_)(?:DATABASE|REDIS)_URL$/u.test(normalized)
    || normalized === 'TRADING_LAB_SMOKE_OWNED_AUTH_USER_IDS'
  )
}

export function resolveOwnedAdminNpmLaunch(
  args,
  {
    platform = process.platform,
    nodePath = process.execPath,
    npmCliPath = null,
    fileExists = existsSync,
  } = {},
) {
  assert.ok(
    Array.isArray(args)
      && args.every((argument) => (
        typeof argument === 'string'
        && argument.length > 0
      )),
    'Owned Admin npm arguments are invalid',
  )
  if (platform !== 'win32') {
    return {
      executable: 'npm',
      arguments: [...args],
    }
  }

  assert.ok(
    typeof nodePath === 'string' && nodePath.length > 0,
    'Owned Admin Node executable is invalid',
  )
  const resolvedNpmCliPath = npmCliPath
    ?? join(dirname(nodePath), 'node_modules', 'npm', 'bin', 'npm-cli.js')
  assert.ok(
    typeof resolvedNpmCliPath === 'string'
      && resolvedNpmCliPath.length > 0
      && fileExists(resolvedNpmCliPath),
    'Owned Admin npm CLI is unavailable',
  )
  return {
    executable: nodePath,
    arguments: [resolvedNpmCliPath, ...args],
  }
}

async function launchOwnedAdmin(context) {
  const adminPort = Number(new URL(context.options.adminUrl).port || 80)
  assert.equal(
    await isTcpPortOpen(adminPort),
    false,
    `Refusing to launch Admin because port ${adminPort} is occupied`,
  )
  const args = [
    '--prefix',
    join(platformRoot, 'apps', 'admin'),
    'run',
    'dev',
    '--',
    '--host',
    '127.0.0.1',
    '--port',
    String(adminPort),
    '--strictPort',
  ]
  const launch = resolveOwnedAdminNpmLaunch(args)
  const commandLine = [launch.executable, ...launch.arguments].join(' ')
  const child = spawn(launch.executable, launch.arguments, {
    cwd: platformRoot,
    env: buildChildProcessEnvironment(process.env, {
      VITE_API_BASE_URL: context.options.apiUrl,
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  return registerOwnedProcess(context, {
    kind: 'Admin',
    child,
    commandLine,
    ownedPort: adminPort,
  })
}

function registerOwnedProcess(context, input) {
  const child = input.child
  const record = {
    ...input,
    owned: true,
    pid: child.pid,
    output: '',
    startedAt: new Date().toISOString(),
  }
  if (!Number.isSafeInteger(record.pid) || record.pid <= 0) {
    throw new Error(`Owned ${input.kind} process did not expose a PID`)
  }
  const capture = (chunk) => {
    record.output = `${record.output}${chunk.toString()}`.slice(-200_000)
  }
  input.child.stdout?.on('data', capture)
  input.child.stderr?.on('data', capture)
  context.ownedProcesses.push(record)
  return record
}

async function launchBrowser(context) {
  assert.equal(
    typeof WebSocket,
    'function',
    'Trading Lab smoke requires Node with built-in WebSocket support',
  )
  const executable = browserExecutable()
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-trading-lab-chrome-'))
  const args = [
    ...(context.options.headed ? [] : ['--headless=new']),
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--disable-breakpad',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    `--window-size=${VIEWPORT_WIDTH},${VIEWPORT_HEIGHT}`,
    'about:blank',
  ]
  const commandLine = [executable, ...args].join(' ')
  const child = spawn(executable, args, {
    env: buildChildProcessEnvironment(),
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  const owned = registerOwnedProcess(context, {
    kind: 'browser',
    child,
    commandLine,
    ownedPort: port,
  })
  await waitFor(async () => {
    if (child.exitCode !== null) {
      throw new Error(
        `Owned browser exited before DevTools was ready: ${owned.output.slice(-800)}`,
      )
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      })
      return response.ok
    } catch {
      return false
    }
  }, 'owned Chrome or Edge DevTools endpoint', 20_000)
  const debugOwnerPid = process.platform === 'win32'
    ? windowsPortOwnerPid(port)
    : child.pid
  assert.ok(debugOwnerPid, 'Owned browser DevTools listener PID is unknown')
  owned.debugOwnerPid = debugOwnerPid
  owned.listenerOwnerPid = debugOwnerPid
  return Object.freeze({
    executable,
    port,
    userDataDir,
    child,
    debugOwnerPid,
  })
}

function browserExecutable() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.EDGE_PATH,
    process.env.SMOKE_BROWSER_PATH,
    process.platform === 'win32'
      ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
      : null,
    process.platform === 'win32'
      ? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
      : null,
    process.platform === 'win32'
      ? 'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
      : null,
    process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : null,
    process.platform === 'linux' ? '/usr/bin/google-chrome' : null,
    process.platform === 'linux' ? '/usr/bin/chromium' : null,
  ].filter(Boolean)
  const found = candidates.find((candidate) => existsSync(candidate))
  if (found === undefined) {
    throw new Error('Chrome or Edge executable was not found')
  }
  return realpathSync(found)
}

export function createCdpResponseRecorder({
  requestRows,
  responseExpectations,
  diagnostics,
  now = () => new Date().toISOString(),
}) {
  const responseRows = new Map()

  const recordResponse = ({
    requestId,
    url,
    xRequestId,
    status,
    mimeType = null,
    resourceType = null,
  }) => {
    const normalizedUrl = String(url ?? '')
    const normalizedStatus = Number(status)
    if (
      typeof requestId !== 'string'
      || requestId.length === 0
      || normalizedUrl.length === 0
      || !Number.isFinite(normalizedStatus)
    ) {
      return undefined
    }
    const existing = responseRows.get(requestId)
    if (existing !== undefined) {
      if (existing.xRequestId === null && xRequestId !== null) {
        existing.xRequestId = xRequestId
      }
      if (existing.mimeType === null && mimeType !== null) {
        existing.mimeType = mimeType
      }
      if (existing.resourceType === null && resourceType !== null) {
        existing.resourceType = resourceType
      }
      return existing
    }
    const requestRow = requestRows.get(requestId)
    const expected = expectedHttpResponse(
      responseExpectations,
      normalizedUrl,
      normalizedStatus,
      resourceType,
    )
    if (diagnostics.responses.length >= MAX_DIAGNOSTIC_ROWS) {
      if (isTradingLabBusinessRequest(normalizedUrl)) {
        diagnostics.businessResponseOverflow = true
      }
      if (isMainRequestLogResponse(normalizedUrl)) {
        diagnostics.ownershipResponseOverflow = true
      }
    }
    const row = {
      requestId,
      url: safeNetworkUrl(normalizedUrl),
      xRequestId,
      requestTupleSha256: isMainRequestLogResponse(normalizedUrl)
        ? observedRequestTupleSha256(
            requestRow?.method,
            normalizedUrl,
            normalizedStatus,
          )
        : null,
      status: normalizedStatus,
      mimeType,
      resourceType,
      expected,
      at: now(),
    }
    responseRows.set(requestId, row)
    pushBounded(diagnostics.responses, row)
    return row
  }

  const onResponseReceived = (event) => recordResponse({
    requestId: event.requestId,
    url: event.response?.url,
    xRequestId: responseHeaderValue(
      event.response?.headers,
      'x-request-id',
    ),
    status: event.response?.status,
    mimeType: event.response?.mimeType ?? null,
    resourceType: event.type ?? null,
  })

  const onResponseReceivedExtraInfo = (event) => {
    const requestRow = requestRows.get(event.requestId)
    if (requestRow === undefined) return undefined
    return recordResponse({
      requestId: event.requestId,
      url: requestRow.url,
      xRequestId: responseHeaderValue(event.headers, 'x-request-id'),
      status: event.statusCode,
      resourceType: requestRow.resourceType,
    })
  }

  return {
    responseRows,
    onResponseReceived,
    onResponseReceivedExtraInfo,
  }
}

export function createCdpRequestRecorder({
  requestRows,
  diagnostics,
  now = () => new Date().toISOString(),
}) {
  return {
    onRequestWillBeSent(event) {
      if (
        event.requestId === undefined
        || event.request?.url === undefined
      ) {
        return undefined
      }
      const row = {
        requestId: event.requestId,
        method: event.request.method,
        url: safeNetworkUrl(event.request.url),
        xRequestId: responseHeaderValue(
          event.request.headers,
          'x-request-id',
        ),
        resourceType: event.type ?? null,
        startedAt: now(),
      }
      requestRows.set(event.requestId, row)
      if (
        diagnostics.requests.length >= MAX_DIAGNOSTIC_ROWS
        && isMainRequestLogResponse(row.url)
      ) {
        diagnostics.ownershipRequestOverflow = true
      }
      pushBounded(diagnostics.requests, row)
      return row
    },
  }
}

async function createCdpPage(port, downloadDirectory) {
  const target = await createTarget(port)
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map()
  const listeners = new Map()
  const requestRows = new Map()
  const responseExpectations = []
  const dialogPlans = []
  const diagnostics = {
    console: [],
    exceptions: [],
    requests: [],
    responses: [],
    failures: [],
    downloads: [],
    dialogs: [],
    businessResponseOverflow: false,
    ownershipRequestOverflow: false,
    ownershipResponseOverflow: false,
  }
  const requestRecorder = createCdpRequestRecorder({
    requestRows,
    diagnostics,
  })
  const responseRecorder = createCdpResponseRecorder({
    requestRows,
    responseExpectations,
    diagnostics,
  })
  let nextId = 1

  await new Promise((resolveOpen, rejectOpen) => {
    socket.addEventListener('open', resolveOpen, { once: true })
    socket.addEventListener('error', rejectOpen, { once: true })
  })

  socket.addEventListener('message', (event) => {
    const payload = JSON.parse(event.data)
    if (payload.id !== undefined && pending.has(payload.id)) {
      const callback = pending.get(payload.id)
      pending.delete(payload.id)
      if (payload.error !== undefined) {
        callback.reject(new Error(payload.error.message))
      } else {
        callback.resolve(payload.result)
      }
      return
    }
    for (const listener of listeners.get(payload.method) ?? []) {
      listener(payload.params ?? {})
    }
  })

  const send = (method, params = {}, timeoutMs = 20_000) =>
    new Promise((resolveSend, rejectSend) => {
      const id = nextId
      nextId += 1
      const timeout = setTimeout(() => {
        pending.delete(id)
        rejectSend(new Error(`CDP ${method} timed out`))
      }, timeoutMs)
      pending.set(id, {
        resolve(value) {
          clearTimeout(timeout)
          resolveSend(value)
        },
        reject(error) {
          clearTimeout(timeout)
          rejectSend(error)
        },
      })
      socket.send(JSON.stringify({ id, method, params }))
    })

  const page = {
    port,
    targetId: target.id,
    diagnostics,
    send,
    on(method, listener) {
      if (!listeners.has(method)) listeners.set(method, new Set())
      listeners.get(method).add(listener)
    },
    async evaluate(fn, ...args) {
      const expression = `(${fn})(${args
        .map((argument) => JSON.stringify(argument))
        .join(',')})`
      const result = await send('Runtime.evaluate', {
        expression,
        awaitPromise: true,
        returnByValue: true,
      })
      if (result.exceptionDetails !== undefined) {
        throw new Error(
          result.exceptionDetails.exception?.description
            ?? result.exceptionDetails.text
            ?? 'Runtime evaluation failed',
        )
      }
      return result.result?.value
    },
    async navigate(url) {
      this.expectTransientNavigation()
      const nonce = randomUUID()
      await this.evaluate((value) => {
        window.__tradingLabSmokeNavigation = value
      }, nonce).catch(() => undefined)
      await send('Page.navigate', { url })
      await waitFor(
        () => this.evaluate((expected, marker) => (
          location.href.startsWith(expected)
          && document.readyState === 'complete'
          && window.__tradingLabSmokeNavigation !== marker
        ), url, nonce),
        `browser navigation to ${new URL(url).pathname}`,
        PAGE_TIMEOUT_MS,
      )
    },
    async reload() {
      this.expectTransientNavigation()
      const expected = await this.evaluate(() => location.href)
      const nonce = randomUUID()
      await this.evaluate((value) => {
        window.__tradingLabSmokeNavigation = value
      }, nonce)
      await send('Page.reload', { ignoreCache: true })
      await waitFor(
        () => this.evaluate((url, marker) => (
          location.href === url && document.readyState === 'complete'
          && window.__tradingLabSmokeNavigation !== marker
        ), expected, nonce),
        'Admin page reload',
        PAGE_TIMEOUT_MS,
      )
    },
    expectHttp(pathname, statuses, durationMs = 15_000) {
      const expectation = {
        pathname,
        statuses: new Set(statuses),
        expiresAt: Date.now() + durationMs,
        observed: 0,
      }
      responseExpectations.push(expectation)
      return expectation
    },
    expectTransientNavigation() {
      responseExpectations.push({
        pathname: null,
        statuses: new Set(),
        expiresAt: Date.now() + 5_000,
        navigationOnly: true,
        observed: 0,
      })
    },
    planDialog(pattern, accept) {
      const plan = {
        pattern: pattern.source,
        flags: pattern.flags,
        accept,
        observed: false,
      }
      dialogPlans.push(plan)
      return plan
    },
    async close() {
      try {
        socket.close()
      } catch {
        // The owned browser may already have closed the socket.
      }
      await fetch(
        `http://127.0.0.1:${port}/json/close/${target.id}`,
        { method: 'GET' },
      ).catch(() => undefined)
    },
  }

  page.on('Runtime.consoleAPICalled', (event) => {
    pushBounded(diagnostics.console, {
      type: event.type,
      source: 'runtime',
      url: null,
      networkRequestId: null,
      message: event.args
        ?.map((argument) => argument.value ?? argument.description ?? '')
        .join(' ')
        .slice(0, 2_000) ?? '',
      expected: false,
      at: new Date().toISOString(),
    })
  })
  page.on('Runtime.exceptionThrown', (event) => {
    pushBounded(diagnostics.exceptions, {
      kind: 'exception',
      message: (
        event.exceptionDetails?.exception?.description
        ?? event.exceptionDetails?.text
        ?? 'Runtime exception'
      ).slice(0, 2_000),
      at: new Date().toISOString(),
    })
  })
  page.on('Log.entryAdded', (event) => {
    const entryUrl = (
      typeof event.entry?.url === 'string'
      && event.entry.url.length > 0
    ) ? safeNetworkUrl(event.entry.url) : null
    pushBounded(diagnostics.console, {
      type: event.entry?.level ?? 'log',
      source: event.entry?.source ?? null,
      url: entryUrl,
      networkRequestId:
        typeof event.entry?.networkRequestId === 'string'
          ? event.entry.networkRequestId
          : null,
      message: String(event.entry?.text ?? '').slice(0, 2_000),
      expected: false,
      at: new Date().toISOString(),
    })
  })
  page.on('Network.requestWillBeSent', requestRecorder.onRequestWillBeSent)
  page.on('Network.responseReceived', responseRecorder.onResponseReceived)
  page.on(
    'Network.responseReceivedExtraInfo',
    responseRecorder.onResponseReceivedExtraInfo,
  )
  page.on('Network.loadingFailed', (event) => {
    const row = requestRows.get(event.requestId)
    pushBounded(diagnostics.failures, {
      requestId: event.requestId,
      url: row?.url ?? '',
      errorText: event.errorText ?? 'request failed',
      canceled: Boolean(event.canceled),
      expected: false,
      at: new Date().toISOString(),
    })
  })
  page.on('Browser.downloadWillBegin', (event) => {
    pushBounded(diagnostics.downloads, {
      guid: event.guid,
      suggestedFilename: safeFileName(event.suggestedFilename),
      url: safeNetworkUrl(event.url),
      state: 'inProgress',
      receivedBytes: 0,
      totalBytes: null,
      path: join(downloadDirectory, event.guid),
    })
  })
  page.on('Browser.downloadProgress', (event) => {
    const row = diagnostics.downloads.find(
      (candidate) => candidate.guid === event.guid,
    )
    if (row === undefined) return
    row.state = event.state
    row.receivedBytes = event.receivedBytes
    row.totalBytes = event.totalBytes
  })
  page.on('Page.javascriptDialogOpening', (event) => {
    const plan = dialogPlans.find((candidate) => !candidate.observed)
    const matches = plan !== undefined
      && new RegExp(plan.pattern, plan.flags).test(event.message ?? '')
    diagnostics.dialogs.push({
      type: event.type,
      message: String(event.message ?? '').slice(0, 1_000),
      expected: matches,
      accepted: matches ? plan.accept : false,
      at: new Date().toISOString(),
    })
    if (matches) plan.observed = true
    void send('Page.handleJavaScriptDialog', {
      accept: matches ? plan.accept : false,
    }).catch(() => undefined)
  })

  await Promise.all([
    send('Page.enable'),
    send('Runtime.enable'),
    send('Network.enable'),
    send('Log.enable'),
    send('Browser.setDownloadBehavior', {
      behavior: 'allowAndName',
      downloadPath: downloadDirectory,
      eventsEnabled: true,
    }),
  ])
  return page
}

async function createTarget(port) {
  const response = await fetch(
    `http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`,
    { method: 'PUT', signal: AbortSignal.timeout(5_000) },
  )
  assert.equal(response.ok, true, 'Could not create the owned CDP page')
  const target = await response.json()
  assert.ok(target.id, 'CDP target ID is missing')
  assert.ok(target.webSocketDebuggerUrl, 'CDP target WebSocket URL is missing')
  return target
}

export function installOwnedHeadlessPrintBridge(scope) {
  const property = '__tradingLabSmokePrintBridge'
  const mode = 'HEADLESS_PRINT_INVOCATION_INTERCEPT'
  if (
    scope === null
    || !['object', 'function'].includes(typeof scope)
  ) {
    throw new Error('Owned headless print bridge scope is invalid')
  }
  const existing = scope[property]
  if (existing !== undefined) {
    if (
      existing !== null
      && typeof existing === 'object'
      && existing.mode === mode
      && typeof existing.snapshot === 'function'
      && Object.isFrozen(existing)
    ) {
      return existing
    }
    throw new Error('Owned headless print bridge state is invalid')
  }
  const originalOpen = scope.open
  if (typeof originalOpen !== 'function') {
    throw new Error('Owned headless print bridge requires window.open')
  }
  const receipts = []
  const bridge = Object.freeze({
    mode,
    snapshot() {
      return receipts.map((receipt) => Object.freeze({ ...receipt }))
    },
  })
  const wrappedOpen = function (...args) {
    const popup = Reflect.apply(originalOpen, this, args)
    if (
      popup === null
      || !['object', 'function'].includes(typeof popup)
      || typeof popup.print !== 'function'
    ) {
      return popup
    }
    const callPopupMethod = (name, methodArguments) => {
      const method = popup[name]
      if (typeof method !== 'function') {
        throw new TypeError(`Popup method ${String(name)} is unavailable`)
      }
      return Reflect.apply(method, popup, methodArguments)
    }
    const optionalPopupMethod = (name) => {
      const method = popup[name]
      return typeof method === 'function'
        ? (...methodArguments) =>
            Reflect.apply(method, popup, methodArguments)
        : undefined
    }
    let observedDocument
    let documentObserved = false
    const readObservedDocument = () => {
      if (!documentObserved) {
        observedDocument = popup.document
        documentObserved = true
      }
      return observedDocument
    }
    return Object.freeze({
      get opener() {
        return popup.opener
      },
      set opener(value) {
        popup.opener = value
      },
      get closed() {
        return popup.closed
      },
      get document() {
        return readObservedDocument()
      },
      addEventListener(...eventArguments) {
        return callPopupMethod('addEventListener', eventArguments)
      },
      removeEventListener(...eventArguments) {
        return callPopupMethod('removeEventListener', eventArguments)
      },
      close(...closeArguments) {
        return callPopupMethod('close', closeArguments)
      },
      focus(...focusArguments) {
        return callPopupMethod('focus', focusArguments)
      },
      get requestAnimationFrame() {
        return optionalPopupMethod('requestAnimationFrame')
      },
      get cancelAnimationFrame() {
        return optionalPopupMethod('cancelAnimationFrame')
      },
      print(...printArguments) {
        let title
        try {
          title = readObservedDocument()?.title
        } catch {
          return callPopupMethod('print', printArguments)
        }
        if (title !== 'Trading Lab raw report') {
          return callPopupMethod('print', printArguments)
        }
        receipts.push(Object.freeze({
          sequence: receipts.length + 1,
          title,
          at: new Date().toISOString(),
        }))
        return undefined
      },
    })
  }
  Object.defineProperty(scope, property, {
    configurable: true,
    value: bridge,
  })
  try {
    Object.defineProperty(scope, 'open', {
      configurable: true,
      writable: true,
      value: wrappedOpen,
    })
    Object.defineProperty(scope, property, {
      configurable: false,
    })
  } catch (error) {
    delete scope[property]
    throw error
  }
  return bridge
}

async function configurePage(page, { headless }) {
  assert.equal(
    typeof headless,
    'boolean',
    'Owned browser headless mode is invalid',
  )
  let printBridgeSource = ''
  if (headless) {
    printBridgeSource =
      `(${installOwnedHeadlessPrintBridge.toString()})(globalThis);`
  }
  await page.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `${printBridgeSource}
    (() => {
      // This owned headless target explicitly exercises the real <=50 MiB
      // Blob/anchor download path, which emits auditable Browser.download
      // events. Native File System Access behavior remains covered by unit
      // tests because its save picker cannot be automated as a download.
      Object.defineProperty(globalThis, 'showSaveFilePicker', {
        configurable: true,
        value: undefined,
      })
      addEventListener('unhandledrejection', (event) => {
        const value = event.reason
        const message = value instanceof Error
          ? value.stack || value.message
          : String(value)
        console.error('__TRADING_LAB_UNHANDLED_REJECTION__', message)
      })
    })()`,
  })
  await setViewport(page, VIEWPORT_WIDTH, VIEWPORT_HEIGHT, false)
  await page.send('Emulation.setEmulatedMedia', {
    media: 'screen',
    features: [{ name: 'prefers-reduced-motion', value: 'reduce' }],
  })
}

async function requireOwnedDownloadMode(page) {
  const pickerUnavailable = await page.evaluate(
    () => typeof globalThis.showSaveFilePicker === 'undefined',
  )
  assert.equal(
    pickerUnavailable,
    true,
    'Owned browser did not enter the explicit Blob fallback mode',
  )
  return OWNED_DOWNLOAD_MODE
}

async function setViewport(page, width, height, mobile) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1,
    mobile,
    screenWidth: width,
    screenHeight: height,
  })
}

async function loginThroughAdminForm(
  page,
  adminUrl,
  credentials,
  expectedAuthorities,
  expectedLandingPath,
) {
  await page.navigate(`${adminUrl}/login`)
  await page.evaluate(() => {
    localStorage.removeItem('fx-platform-admin-token')
    localStorage.removeItem('fx-platform-admin-refresh-token')
    localStorage.removeItem('fx-platform-admin-authorities')
  })
  const protectedLoginMarker = randomUUID()
  await page.evaluate((value) => {
    window.__tradingLabSmokeNavigation = value
  }, protectedLoginMarker)
  page.expectTransientNavigation()
  await page.send('Page.navigate', {
    url: `${adminUrl}${expectedLandingPath}`,
  })
  await waitFor(
    () => page.evaluate((marker) => (
      location.pathname === '/login'
      && document.readyState === 'complete'
      && window.__tradingLabSmokeNavigation !== marker
    ), protectedLoginMarker),
    `${credentials.label} protected Trading Lab login redirect`,
    PAGE_TIMEOUT_MS,
  )
  await fillLabel(page, '管理员邮箱', credentials.email)
  await fillLabel(page, '密码', credentials.password)
  await clickRole(page, 'button', '登录')
  await waitFor(
    async () => isExpectedAdminLanding(
      await page.evaluate(() => ({
        pathname: location.pathname,
        h1: document.querySelector('#admin-content #trading-lab-title')
          ?.textContent?.trim() ?? null,
        h2: document.querySelector('#admin-content .page-header h2')
          ?.textContent?.trim() ?? null,
        dashboardReady:
          document.querySelector('[aria-label="后台运行状态"]') !== null,
      })),
      expectedLandingPath,
    ),
    `${credentials.label} real Admin login form`,
    PAGE_TIMEOUT_MS,
  )
  const authorities = await page.evaluate(() => {
    try {
      return JSON.parse(
        localStorage.getItem('fx-platform-admin-authorities') ?? '[]',
      )
    } catch {
      return []
    }
  })
  assert.ok(Array.isArray(authorities), 'Admin authority snapshot is invalid')
  for (const authority of expectedAuthorities) {
    assert.ok(
      authorities.includes(authority),
      `${credentials.label} is missing ${authority}`,
    )
  }
  return authorities
}

export function isExpectedAdminLanding(landing, expectedLandingPath) {
  if (!['/trading/lab', '/dashboard'].includes(expectedLandingPath)) {
    throw new Error('Admin login landing path is invalid')
  }
  if (
    landing === null
    || typeof landing !== 'object'
    || Array.isArray(landing)
    || canonicalJson(Object.keys(landing).sort())
      !== canonicalJson(['dashboardReady', 'h1', 'h2', 'pathname'])
    || typeof landing.pathname !== 'string'
    || (landing.h1 !== null && typeof landing.h1 !== 'string')
    || (landing.h2 !== null && typeof landing.h2 !== 'string')
    || typeof landing.dashboardReady !== 'boolean'
  ) {
    throw new Error('Admin login landing evidence is invalid')
  }
  if (expectedLandingPath === '/trading/lab') {
    return (
      landing.pathname === '/trading/lab'
      && landing.h1 === '交易路径实验室'
    )
  }
  return (
    landing.pathname === '/dashboard'
    && landing.h2 === '控制台'
    && landing.dashboardReady
  )
}

async function openTradingLab(page, adminUrl, runId = null) {
  const suffix = runId === null
    ? '/trading/lab'
    : `/trading/lab?runId=${encodeURIComponent(runId)}`
  await page.navigate(`${adminUrl}${suffix}`)
  await waitFor(
    () => page.evaluate(() => (
      location.pathname === '/trading/lab'
      && document.querySelector('h1')?.textContent?.trim() === '交易路径实验室'
    )),
    'Trading Lab page route /trading/lab',
    PAGE_TIMEOUT_MS,
  )
  const viewport = await page.evaluate(() => ({
    width: innerWidth,
    height: innerHeight,
  }))
  assert.ok(
    viewport.width >= VIEWPORT_WIDTH && viewport.height >= VIEWPORT_HEIGHT,
    `Trading Lab desktop viewport is ${viewport.width}x${viewport.height}`,
  )
}

async function runJourney(context, name, journey) {
  const started = Date.now()
  try {
    const details = await journey(context)
    context.journeys.push({
      name,
      status: 'PASS',
      durationMs: Date.now() - started,
      details,
    })
    return details
  } catch (error) {
    context.journeys.push({
      name,
      status: 'FAIL',
      durationMs: Date.now() - started,
      error: serializeError(asError(error)),
    })
    throw error
  }
}

async function captureCreatedRun(context, expectedTerminalState) {
  if (!TERMINAL_STATES.has(expectedTerminalState)) {
    throw new Error(
      `Canonical ownership terminal state is invalid: ${expectedTerminalState}`,
    )
  }
  const { scenarioId, runId, reportId } =
    await readCreatedRunIdentity(context.page)
  const createdRun = {
    scenarioId,
    runId,
    reportId,
    expectedTerminalState,
  }
  context.createdRuns = upsertCreatedRunOwnership(
    context.createdRuns,
    createdRun,
  )
  recordTerminalSseCloseReceipt(context, {
    runId,
    terminalState: expectedTerminalState,
  })
  return context.createdRuns.find((root) => root.runId === runId)
}

async function captureStartedRun(context) {
  const identity = await readCreatedRunIdentity(context.page)
  const createdRun = {
    ...identity,
    provisional: true,
  }
  context.createdRuns = upsertCreatedRunOwnership(
    context.createdRuns,
    createdRun,
  )
  return createdRun
}

async function readCreatedRunIdentity(page) {
  const routeRunId = await waitForRunId(page)
  const [scenarioId, runId, reportId] = await Promise.all([
    waitForDefinitionUuid(page, 'Scenario ID'),
    waitForDefinitionUuid(page, 'Run ID'),
    waitForDefinitionUuid(page, 'Report ID'),
  ])
  assert.equal(
    runId,
    routeRunId,
    'Created Run route and authoritative definition IDs differ',
  )
  return { scenarioId, runId, reportId }
}

function recordReferencedRun(context, runId) {
  const ownership = buildCanonicalOwnership({
    createdRuns: context.createdRuns,
    referencedRunIds: [...context.referencedRunIds, runId],
    requestLogObservations: context.requestLogObservations,
    responses: [],
  })
  context.referencedRunIds = ownership.referencedRunIds
  return runId
}

function recordReferencedReport(context, identity) {
  const { scenarioId, runId, reportId } = identity
  for (const [label, value] of [
    ['Scenario ID', scenarioId],
    ['Run ID', runId],
    ['Report ID', reportId],
  ]) {
    assert.match(
      value,
      canonicalUuidPattern(),
      `Referenced report ${label} must be a canonical UUID`,
    )
  }
  assert.equal(
    context.referencedRunIds.includes(runId),
    true,
    'Referenced report must belong to an owned referenced Run',
  )
  const existing = context.referencedReports.find(
    (candidate) => candidate.runId === runId,
  )
  if (existing !== undefined) {
    assert.deepEqual(
      existing,
      { scenarioId, runId, reportId },
      'Referenced report identity changed for the same Run',
    )
    return existing
  }
  assert.equal(
    context.referencedReports.some(
      (candidate) => candidate.reportId === reportId,
    ),
    false,
    'Referenced report ID must be unique across Runs',
  )
  const recorded = Object.freeze({ scenarioId, runId, reportId })
  context.referencedReports.push(recorded)
  return recorded
}

async function openTerminalReport(
  context,
  runId,
  expectedTerminalState = 'COMPLETED',
) {
  const responseStartIndex = context.page.diagnostics.responses.length
  await openTradingLab(context.page, context.options.adminUrl, runId)
  const terminalState = await waitForRunState(
    context.page,
    TERMINAL_STATES,
    RUN_TIMEOUT_MS,
  )
  assert.equal(
    terminalState,
    expectedTerminalState,
    `Referenced Run ${runId} terminal state differs`,
  )
  await waitForText(context.page, '固定报告 Schema')
  const identity = await readCreatedRunIdentity(context.page)
  assert.equal(
    identity.runId,
    runId,
    'Referenced report identity differs from the requested Run',
  )
  if (context.referencedRunIds.includes(runId)) {
    recordReferencedReport(context, identity)
  }
  recordTerminalSseCloseReceipt(context, {
    runId,
    terminalState,
    responseStartIndex,
  })
  return { ...identity, terminalState }
}

async function runMobileGuardJourney(context) {
  const { page, options } = context
  await setViewport(
    page,
    MOBILE_VIEWPORT_WIDTH,
    MOBILE_VIEWPORT_HEIGHT,
    true,
  )
  const businessRequestsBefore = page.diagnostics.requests
    .filter((request) => isTradingLabBusinessRequest(request.url))
    .length

  try {
    await page.navigate(`${options.adminUrl}/trading/lab`)
    await waitFor(
      () => page.evaluate((expected) => {
        const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
        return [...document.querySelectorAll('[role="status"]')].some(
          (candidate) => normalize(candidate.textContent ?? '') === expected,
        )
      }, TRADING_LAB_NARROW_MESSAGE),
      'exact Trading Lab narrow-screen guard',
      PAGE_TIMEOUT_MS,
    )
    await sleep(500)

    const rendered = await page.evaluate((expected) => {
      const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
      const exactMessageCount = [
        ...document.querySelectorAll('[role="status"]'),
      ].filter(
        (candidate) => normalize(candidate.textContent ?? '') === expected,
      ).length
      return {
        width: innerWidth,
        height: innerHeight,
        exactMessageCount,
        editorCount: document.querySelectorAll(
          '.trading-lab-timeline, .trading-lab-action-editor',
        ).length,
        chartCount: document.querySelectorAll(
          '.trading-lab-chart-section, .trading-lab-chart',
        ).length,
        runControlsCount: document.querySelectorAll(
          '.trading-lab-run-controls',
        ).length,
      }
    }, TRADING_LAB_NARROW_MESSAGE)

    assert.deepEqual(
      { width: rendered.width, height: rendered.height },
      { width: MOBILE_VIEWPORT_WIDTH, height: MOBILE_VIEWPORT_HEIGHT },
      'Trading Lab mobile guard viewport must be exactly 390x844',
    )
    assert.equal(
      rendered.exactMessageCount,
      1,
      'Trading Lab mobile guard must render the exact narrow-screen copy once',
    )
    assert.equal(rendered.editorCount, 0, 'Mobile guard rendered an editor')
    assert.equal(rendered.chartCount, 0, 'Mobile guard rendered a chart')
    assert.equal(
      rendered.runControlsCount,
      0,
      'Mobile guard rendered run controls',
    )

    const businessRequestsAfter = page.diagnostics.requests
      .filter((request) => isTradingLabBusinessRequest(request.url))
      .length
    assert.equal(
      businessRequestsAfter,
      businessRequestsBefore,
      'Mobile guard issued a Trading Lab business request',
    )
    await captureScreenshot(context, 'mobile-guard-390x844')
    return {
      viewport: {
        width: rendered.width,
        height: rendered.height,
      },
      message: TRADING_LAB_NARROW_MESSAGE,
      editorCount: rendered.editorCount,
      chartCount: rendered.chartCount,
      runControlsCount: rendered.runControlsCount,
      businessRequestsBefore,
      businessRequestsAfter,
    }
  } finally {
    await setViewport(page, VIEWPORT_WIDTH, VIEWPORT_HEIGHT, false)
  }
}

async function runCoreJourney(context) {
  const { page, options } = context
  await openTradingLab(page, options.adminUrl)
  const validationEnvironment = await waitForTradingLabEnvironmentReady(page)
  assert.equal(
    validationEnvironment.relay,
    '运行中',
    'Validation relay must already be running',
  )
  assert.match(
    validationEnvironment.validationHealth,
    /^(?:UP|healthy)$/iu,
  )
  const authority = await readAuthoritativeTradingLabConfig(
    page,
    options.apiUrl,
  )
  const scenario = await generatedScenario({
    authority,
    id: randomUUID(),
    name: 'Phase 4 manual Spot + Perp browser scenario',
    seed: 'phase4-browser-core',
    durationSeconds: 30,
    actionCount: 8,
    negativeMode: false,
  })
  const scenarioPath = await writeScenarioArtifact(
    context,
    'core-manual-spot-perp.json',
    scenario,
  )
  await uploadScenario(page, scenarioPath)
  await fillLabel(
    page,
    '名称',
    `Phase 4 manual Spot + Perp ${randomUUID().slice(0, 8)}`,
  )
  await waitForText(page, '本地预期已计算')
  await waitForText(page, '最终快照摘要')
  await captureScreenshot(context, '01-core-local-oracle')

  await clickRole(page, 'button', '保存并运行')
  const { runId } = await captureStartedRun(context)
  await waitForRunState(page, RUNNING_STATES)
  await waitForTick(page, 1)
  assertSseEvidence(page.diagnostics)

  await waitForRoleEnabled(page, 'button', '暂停')
  await clickRole(page, 'button', '暂停')
  await waitForRunState(page, new Set(['PAUSED']))
  const pausedTick = await numericDefinitionValue(page, '已观察 Tick')
  await sleep(1_500)
  assert.equal(
    await numericDefinitionValue(page, '已观察 Tick'),
    pausedTick,
    'Tick count must remain stable while the run is paused',
  )
  await waitForRoleEnabled(page, 'button', '恢复')
  await clickRole(page, 'button', '恢复')
  await waitForRunState(page, new Set(['RUNNING', 'COMPLETED']))
  const terminal = await waitForRunState(page, TERMINAL_STATES, RUN_TIMEOUT_MS)
  assert.equal(terminal, 'COMPLETED', `Core run ended as ${terminal}`)
  await waitForText(page, '权威实际状态')
  await waitForText(page, '固定报告 Schema')
  const createdRun = await captureCreatedRun(context, terminal)
  assert.equal(createdRun.runId, runId, 'Core route and report run IDs differ')
  await captureScreenshot(context, '02-core-completed')
  const report = await downloadCurrentReport(context)
  assertReportSchema(report.payload)
  return {
    runId,
    reportId: createdRun.reportId,
    scenarioId: createdRun.scenarioId,
    expectedTerminalState: createdRun.expectedTerminalState,
    createdRuns: [createdRun],
    pausedTick,
    finalTick: await numericDefinitionValue(page, '已观察 Tick'),
    download: basename(report.path),
    coverage: ['Spot', 'Perp', 'SSE', 'pause', 'resume'],
  }
}

async function runRandomChartJourney(context) {
  const { page, options } = context
  await openTradingLab(page, options.adminUrl)
  const authority = await readAuthoritativeTradingLabConfig(
    page,
    options.apiUrl,
  )
  const base = await generatedScenario({
    authority,
    id: randomUUID(),
    name: 'Phase 4 deterministic random browser base',
    seed: 'phase4-browser-random-base',
    durationSeconds: 24,
    actionCount: 6,
    negativeMode: false,
  })
  const path = await writeScenarioArtifact(
    context,
    'random-chart-base.json',
    base,
  )
  await uploadScenario(page, path)
  await fillTestId(
    page,
    'trading-lab-random-seed',
    'phase4-browser-fixed-seed',
  )
  await clickTestId(page, 'trading-lab-generate-random')
  const firstHash = await textTestId(page, 'trading-lab-scenario-hash')
  await clickTestId(page, 'trading-lab-generate-random')
  const secondHash = await textTestId(page, 'trading-lab-scenario-hash')
  assert.equal(firstHash, secondHash, 'Fixed seed must regenerate the same hash')

  const firstSeed = await valueTestId(page, 'trading-lab-random-seed')
  await clickTestId(page, 'trading-lab-rerandomize')
  const rerandomizedSeed = await valueTestId(page, 'trading-lab-random-seed')
  const rerandomizedHash = await textTestId(page, 'trading-lab-scenario-hash')
  assert.notEqual(rerandomizedSeed, firstSeed, 'Rerandomize must change seed')
  assert.notEqual(rerandomizedHash, firstHash, 'Rerandomize must change hash')
  await fillLabel(page, '名称', 'Edited deterministic random scenario')

  await clickRole(page, 'button', 'TICK')
  await clickRole(page, 'button', 'KLINE')
  for (const priceType of ['BID', 'ASK', 'LAST', 'MARK', 'INDEX']) {
    await setCheckboxLabel(page, priceType, true)
  }
  const chartInstrumentLabels = [
    `Spot ${authority.spot.symbol}`,
    `Perp ${authority.perp.symbol}`,
  ]
  await clickRole(page, 'button', chartInstrumentLabels[0])
  await clickRole(page, 'button', chartInstrumentLabels[1])
  await exerciseChartPointer(page, 'trading-lab-chart-canvas')
  assert.ok(
    (await textTestId(page, 'trading-lab-chart-virtual-time')).length > 0,
    'Chart must expose its virtual-time location',
  )
  await clickRole(page, 'button', 'LOCAL')
  await clickRole(page, 'button', '保存并运行')
  await captureStartedRun(context)
  await waitForRunState(page, RUNNING_STATES)
  await waitForTick(page, 1)
  const terminal = await waitForRunState(
    page,
    TERMINAL_STATES,
    RUN_TIMEOUT_MS,
  )
  assert.equal(
    terminal,
    'COMPLETED',
    'Deterministic random scenario must complete before ACTUAL inspection',
  )
  await waitForText(page, '固定报告 Schema')
  const createdRun = await captureCreatedRun(context, terminal)
  await waitForRoleEnabled(page, 'button', 'ACTUAL')
  await clickRole(page, 'button', 'ACTUAL')
  await selectActualMarkerInstrument(page, chartInstrumentLabels)
  await waitForTestId(page, 'trading-lab-chart-event-marker')
  await captureScreenshot(context, '03-random-actual-chart')
  return {
    createdRuns: [createdRun],
    deterministicHash: firstHash,
    rerandomizedHash,
    chart: [
      'TICK',
      'KLINE',
      'BID',
      'ASK',
      'LAST',
      'MARK',
      'INDEX',
      'LOCAL',
      'ACTUAL',
      'event markers',
      'zoom drag crosshair',
      'virtual-time location',
    ],
  }
}

async function runPermissionJourney(context) {
  const { page, options, accounts } = context
  const viewAuthorities = await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.viewOnly,
    ['TRADING_LAB_VIEW'],
    '/trading/lab',
  )
  assert.equal(viewAuthorities.includes('TRADING_LAB_EXECUTE'), false)
  await openTradingLab(page, options.adminUrl)
  assert.equal(
    await roleDisabled(page, 'button', '保存并运行'),
    true,
    'VIEW-only Admin cannot execute',
  )

  const executeAuthorities = await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.execute,
    ['TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE'],
    '/trading/lab',
  )
  assert.equal(executeAuthorities.includes('SUPER_ADMIN'), false)
  await openTradingLab(page, options.adminUrl)
  for (const label of ['启动', '停止', '重启']) {
    assert.equal(
      await roleDisabled(page, 'button', label),
      true,
      'EXECUTE Admin cannot start/stop environment',
    )
  }
  const largeRunId = options.largeRunId
  recordReferencedRun(context, largeRunId)
  await openTerminalReport(context, largeRunId)
  const dialogCount = page.diagnostics.dialogs.length
  await clickRole(page, 'button', '打印原始 JSON')
  await waitForText(page, 'SUPER_ADMIN')
  assert.equal(
    page.diagnostics.dialogs.length,
    dialogCount,
    'EXECUTE Admin cannot reach the >50 MiB second confirmation',
  )

  await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.ordinary,
    [],
    '/dashboard',
  )
  const requestStartIndex = page.diagnostics.requests.length
  const responseStartIndex = page.diagnostics.responses.length
  const consoleStartIndex = page.diagnostics.console.length
  const expectedForbidden = page.expectHttp(
    PERMISSION_ENVIRONMENT_PATH,
    [403],
  )
  const permissionRequestId = randomUUID()
  const directResult = await page.evaluate(async (
    apiUrl,
    permissionRequestId,
  ) => {
    const token = localStorage.getItem('fx-platform-admin-token')
    const headers = { 'X-Request-Id': permissionRequestId }
    if (token !== null) headers.Authorization = `Bearer ${token}`
    const response = await fetch(
      `${apiUrl}/api/admin/trading-lab/environment`,
      { headers },
    )
    await response.text()
    return {
      status: response.status,
      xRequestId: response.headers.get('x-request-id'),
    }
  }, options.apiUrl, permissionRequestId)
  const directStatus = directResult?.status
  assert.equal(directStatus, 403, 'ordinary Admin environment endpoint must deny')
  assert.equal(
    directResult?.xRequestId,
    permissionRequestId,
    'ordinary Admin direct denial request correlation is invalid',
  )
  await waitFor(
    () => expectedForbidden.observed === 1,
    'ordinary Admin environment endpoint evidence',
    10_000,
  )
  const forbiddenExchange = await waitForValue(
    () => {
      const responses = page.diagnostics.responses
        .slice(responseStartIndex)
        .filter((response) => (
          response.requestId !== undefined
          && networkPathname(response.url) === PERMISSION_ENVIRONMENT_PATH
          && response.status === 403
          && response.resourceType === 'Fetch'
          && response.mimeType === 'application/json'
          && response.expected === true
          && response.xRequestId === directResult.xRequestId
        ))
      if (responses.length !== 1) return null
      const [response] = responses
      const requests = page.diagnostics.requests
        .slice(requestStartIndex)
        .filter((request) => (
          request.requestId === response.requestId
          && request.url === response.url
          && request.method === 'GET'
          && request.resourceType === 'Fetch'
          && request.xRequestId === permissionRequestId
        ))
      if (requests.length !== 1) return null
      return (
        typeof response.requestId === 'string'
        && response.requestId.length > 0
        && canonicalUuidPattern().test(response.xRequestId ?? '')
        && /^[0-9a-f]{64}$/u.test(response.requestTupleSha256 ?? '')
      ) ? { request: requests[0], response } : null
    },
    'ordinary Admin exact GET/403 exchange receipt',
    10_000,
  )
  const forbiddenResponse = forbiddenExchange.response
  await waitFor(
    () => page.diagnostics.console
      .slice(consoleStartIndex)
      .some((entry) => (
        entry.type === 'error'
        && entry.source === 'network'
        && entry.networkRequestId === forbiddenResponse.requestId
        && entry.url === forbiddenResponse.url
        && entry.message === EXPECTED_PERMISSION_DENIAL_CONSOLE_MESSAGE
      )),
    'ordinary Admin request-bound 403 console evidence',
    10_000,
  )
  context.consoleErrorReceipts.push(Object.freeze({
    requestId: forbiddenResponse.requestId,
    xRequestId: forbiddenResponse.xRequestId,
    requestTupleSha256: forbiddenResponse.requestTupleSha256,
    url: forbiddenResponse.url,
    status: forbiddenResponse.status,
    method: forbiddenExchange.request.method,
    resourceType: forbiddenExchange.request.resourceType,
    mimeType: forbiddenResponse.mimeType,
    message: EXPECTED_PERMISSION_DENIAL_CONSOLE_MESSAGE,
  }))

  await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.superAdmin,
    REQUIRED_FULL_AUTHORITIES,
    '/trading/lab',
  )
  return {
    roles: ['VIEW-only', 'EXECUTE', 'ordinary Admin', 'SUPER_ADMIN'],
    directEnvironmentStatus: directStatus,
    referencedRunIds: [largeRunId],
  }
}

async function runNegativeJourney(context) {
  const { page, options } = context
  await openTradingLab(page, options.adminUrl)
  const authority = await readAuthoritativeTradingLabConfig(
    page,
    options.apiUrl,
  )
  const scenario = await generatedScenario({
    authority,
    id: randomUUID(),
    name: 'Phase 4 negative browser scenario',
    seed: 'phase4-browser-negative',
    durationSeconds: 16,
    actionCount: 4,
    negativeMode: true,
  })
  const path = await writeScenarioArtifact(
    context,
    'negative-real-error.json',
    scenario,
  )
  await uploadScenario(page, path)
  await waitForText(page, '负向模式')
  const banner = await page.evaluate(() => (
    document.querySelector('.trading-lab-negative-mode')?.textContent ?? ''
  ))
  assert.doesNotMatch(
    banner,
    /\b(?:PASS|FAIL)\b/u,
    'negative mode must not display a product Pass/Fail verdict',
  )
  await clickRole(page, 'button', '保存并运行')
  await captureStartedRun(context)
  const terminal = await waitForRunState(
    page,
    TERMINAL_STATES,
    RUN_TIMEOUT_MS,
  )
  await waitForText(page, '固定报告 Schema')
  const createdRun = await captureCreatedRun(context, terminal)
  const report = await downloadCurrentReport(context)
  assertReportSchema(report.payload)
  const expectedErrors = expectedNegativeTraceEvidence(
    report.payload,
    scenario,
  )
  assert.equal(
    Object.hasOwn(report.payload, 'productPass'),
    false,
    'Negative report cannot invent product Pass/Fail',
  )
  await captureScreenshot(context, '04-negative-real-error')
  return {
    createdRuns: [createdRun],
    negative: true,
    expectedErrorCount: expectedErrors.length,
    expectedErrorEvidence: expectedErrors,
    unexpectedErrors: report.payload.errors.length,
    productVerdict: 'absent',
  }
}

async function runFailureCancelJourney(context) {
  const { page, options } = context
  await openTradingLab(page, options.adminUrl)
  const cancelAuthority = await readAuthoritativeTradingLabConfig(
    page,
    options.apiUrl,
  )
  const cancelScenario = await generatedScenario({
    authority: cancelAuthority,
    id: randomUUID(),
    name: 'Phase 4 cancellable browser scenario',
    seed: 'phase4-browser-cancel',
    durationSeconds: 120,
    actionCount: 12,
    negativeMode: false,
  })
  const cancelPath = await writeScenarioArtifact(
    context,
    'cancellable-run.json',
    cancelScenario,
  )
  await uploadScenario(page, cancelPath)
  await clickRole(page, 'button', '保存并运行')
  await captureStartedRun(context)
  await waitForRunState(page, RUNNING_STATES)
  const observedBeforeCancel = await waitForTick(page, 1)
  await waitForRoleEnabled(page, 'button', '取消')
  await clickRole(page, 'button', '取消')
  const cancelTerminal = await waitForRunState(
    page,
    TERMINAL_STATES,
    RUN_TIMEOUT_MS,
  )
  assert.equal(cancelTerminal, 'CANCELLED')
  await waitForText(page, '固定报告 Schema')
  const cancelledRun = await captureCreatedRun(context, cancelTerminal)
  const partial = await downloadCurrentReport(context)
  assertReportSchema(partial.payload)
  assert.ok(
    partial.payload.marketTicks.length < 120,
    'Cancelled run must preserve a partial report',
  )
  assert.ok(
    Object.keys(partial.payload.cleanup).length > 0,
    'Cancelled partial report must retain cleanup evidence',
  )

  await openTradingLab(page, options.adminUrl)
  const failureAuthority = await readAuthoritativeTradingLabConfig(
    page,
    options.apiUrl,
  )
  const failureScenario = await buildControlledFailureScenario({
    authority: failureAuthority,
  })
  const failureAction = failureScenario.timeline[0]
  const unexecutedAction = failureScenario.timeline[1]
  const failurePath = await writeScenarioArtifact(
    context,
    'controlled-failure.json',
    failureScenario,
  )
  await uploadScenario(page, failurePath)
  await clickRole(page, 'button', '保存并运行')
  await captureStartedRun(context)
  const failureTerminal = await waitForRunState(
    page,
    TERMINAL_STATES,
    RUN_TIMEOUT_MS,
  )
  assert.equal(failureTerminal, 'FAILED')
  await waitForText(page, '固定报告 Schema')
  const failedRun = await captureCreatedRun(context, failureTerminal)
  const failed = await downloadCurrentReport(context)
  assertReportSchema(failed.payload)
  const executionFailure = failed.payload.errors.find(
    (error) => error?.type === 'RUN_EXECUTION_FAILED',
  )
  assert.ok(executionFailure, 'RUN_EXECUTION_FAILED evidence must be reported')
  assertControlledFailureEvidence({
    executionFailure,
    runId: failedRun.runId,
    validationGeneration:
      failed.payload.actualState?.validationGeneration,
    failureAction,
    unexecutedAction,
  })
  await captureScreenshot(context, '05-failure-cancel')
  return {
    createdRuns: [cancelledRun, failedRun],
    cancel: {
      observedBeforeCancel,
      partialTicks: partial.payload.marketTicks.length,
    },
    failure: {
      errors: failed.payload.errors.length,
      failurePoint: executionFailure.payload.failurePoint,
      unexecuted: executionFailure.payload.unexecuted,
    },
  }
}

async function runPrintJourney(context) {
  const { page, options, accounts, core } = context
  assert.equal(
    options.headed,
    false,
    'Automated print evidence requires the owned headless print bridge',
  )
  const smallIdentity = await openTerminalReport(context, core.runId)
  assert.deepEqual(
    {
      scenarioId: smallIdentity.scenarioId,
      runId: smallIdentity.runId,
      reportId: smallIdentity.reportId,
    },
    {
      scenarioId: core.scenarioId,
      runId: core.runId,
      reportId: core.reportId,
    },
    'Small print report identity differs from the created core Run',
  )
  const smallBaseline = await readOwnedHeadlessPrintBridge(page)
  const smallRequestStartIndex = page.diagnostics.requests.length
  await clickRole(page, 'button', '打印原始 JSON')
  const smallReceipt = await waitForOwnedHeadlessPrintInvocation(
    page,
    smallBaseline.receipts.length + 1,
    'small report headless print invocation',
    PAGE_TIMEOUT_MS,
  )
  await waitForText(page, '打印流程已结束')
  const smallAfter = await readOwnedHeadlessPrintBridge(page)
  assert.equal(
    smallAfter.receipts.length,
    smallBaseline.receipts.length + 1,
    'Small report produced a late duplicate print invocation',
  )
  assert.deepEqual(
    smallAfter.receipts.at(-1),
    smallReceipt,
    'Small report print receipt changed after UI completion',
  )
  recordCompletedReportPrintReceipt(context, {
    identity: smallIdentity,
    bridgeReceipt: smallReceipt,
    requestStartIndex: smallRequestStartIndex,
  })

  const largeRunId = options.largeRunId
  recordReferencedRun(context, largeRunId)
  await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.execute,
    ['TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE'],
    '/trading/lab',
  )
  const deniedIdentity = await openTerminalReport(context, largeRunId)
  const deniedBaseline = await readOwnedHeadlessPrintBridge(page)
  const deniedRequestStartIndex = page.diagnostics.requests.length
  const deniedCompletionCount = context.printCompletionReceipts.length
  await clickRole(page, 'button', '打印原始 JSON')
  await waitForText(page, 'SUPER_ADMIN')
  const deniedAfter = await readOwnedHeadlessPrintBridge(page)
  assert.equal(
    deniedAfter.receipts.length,
    deniedBaseline.receipts.length,
    'EXECUTE Admin large-report denial reached print invocation',
  )
  assert.equal(
    page.diagnostics.requests
      .slice(deniedRequestStartIndex)
      .filter((request) => (
        request.method === 'GET'
        && request.resourceType === 'Fetch'
        && networkPathname(request.url) === (
          '/api/admin/trading-lab/reports/'
          + `${deniedIdentity.reportId}/print`
        )
      ))
      .length,
    0,
    'EXECUTE Admin large-report denial reached the raw print request',
  )
  assert.equal(
    context.printCompletionReceipts.length,
    deniedCompletionCount,
    'EXECUTE Admin large-report denial created a print completion receipt',
  )

  await loginThroughAdminForm(
    page,
    options.adminUrl,
    accounts.superAdmin,
    REQUIRED_FULL_AUTHORITIES,
    '/trading/lab',
  )
  const largeIdentity = await openTerminalReport(context, largeRunId)
  const largeBaseline = await readOwnedHeadlessPrintBridge(page)
  const largeRequestStartIndex = page.diagnostics.requests.length
  const secondConfirmation = page.planDialog(
    /报告约 \d+ 页且超过 50 MiB，确认继续打印/u,
    true,
  )
  await clickRole(page, 'button', '打印原始 JSON')
  await waitFor(
    () => secondConfirmation.observed,
    'SUPER_ADMIN second confirmation for >50 MiB report',
    PAGE_TIMEOUT_MS,
  )
  const largeReceipt = await waitForOwnedHeadlessPrintInvocation(
    page,
    largeBaseline.receipts.length + 1,
    'large report headless print invocation',
    RUN_TIMEOUT_MS,
  )
  // The report metadata, confirmation token and streamed response stay real.
  // Only the owned headless target's physical-printer handoff is intercepted.
  await waitForText(page, '打印流程已结束', RUN_TIMEOUT_MS)
  const largeAfter = await readOwnedHeadlessPrintBridge(page)
  assert.equal(
    largeAfter.receipts.length,
    largeBaseline.receipts.length + 1,
    'Large report produced a late duplicate print invocation',
  )
  assert.deepEqual(
    largeAfter.receipts.at(-1),
    largeReceipt,
    'Large report print receipt changed after UI completion',
  )
  recordCompletedReportPrintReceipt(context, {
    identity: largeIdentity,
    bridgeReceipt: largeReceipt,
    requestStartIndex: largeRequestStartIndex,
  })
  await captureScreenshot(context, '06-print-confirmed')
  return {
    referencedRunIds: [largeRunId],
    printMode: 'HEADLESS_PRINT_INVOCATION_INTERCEPT',
    physicalPrinter: false,
    smallReport: {
      runId: core.runId,
      printInvocationObserved: true,
      receipt: smallReceipt,
    },
    executeDeniedLargeReport: {
      runId: largeRunId,
      requiredAuthority: 'SUPER_ADMIN',
      printInvocationObserved: false,
    },
    largeReport: {
      runId: largeRunId,
      threshold: '50 MiB',
      secondConfirmation: true,
      printInvocationObserved: true,
      receipt: largeReceipt,
    },
  }
}

async function readOwnedHeadlessPrintBridge(page) {
  const snapshot = await page.evaluate(() => {
    const bridge = globalThis.__tradingLabSmokePrintBridge
    let receipts = null
    try {
      receipts = typeof bridge?.snapshot === 'function'
        ? bridge.snapshot()
        : null
    } catch {
      receipts = null
    }
    return {
      mode: bridge?.mode ?? null,
      receipts: Array.isArray(receipts)
        ? receipts.map((receipt) => ({ ...receipt }))
        : null,
    }
  })
  assert.equal(
    snapshot?.mode,
    'HEADLESS_PRINT_INVOCATION_INTERCEPT',
    'Owned headless print bridge mode is invalid',
  )
  assert.ok(
    Array.isArray(snapshot.receipts),
    'Owned headless print receipts are invalid',
  )
  for (const [index, receipt] of snapshot.receipts.entries()) {
    assert.deepEqual(
      Object.keys(receipt).sort(),
      ['at', 'sequence', 'title'],
      'Owned headless print receipt schema is invalid',
    )
    assert.equal(
      receipt.sequence,
      index + 1,
      'Owned headless print receipt sequence is invalid',
    )
    assert.equal(
      receipt.title,
      'Trading Lab raw report',
      'Owned headless print receipt title is invalid',
    )
    assert.equal(
      Number.isNaN(Date.parse(receipt.at)),
      false,
      'Owned headless print receipt timestamp is invalid',
    )
  }
  return snapshot
}

async function waitForOwnedHeadlessPrintInvocation(
  page,
  expectedCount,
  label,
  timeout,
) {
  return waitForValue(
    async () => {
      const snapshot = await readOwnedHeadlessPrintBridge(page)
      if (snapshot.receipts.length < expectedCount) return null
      assert.equal(
        snapshot.receipts.length,
        expectedCount,
        `${label} count is invalid`,
      )
      return snapshot.receipts[expectedCount - 1]
    },
    label,
    timeout,
  )
}

function recordCompletedReportPrintReceipt(
  context,
  {
    identity,
    bridgeReceipt,
    requestStartIndex,
  },
) {
  const { runId, reportId } = identity
  assert.match(
    runId,
    canonicalUuidPattern(),
    'Completed print Run ID must be a canonical UUID',
  )
  assert.match(
    reportId,
    canonicalUuidPattern(),
    'Completed print report ID must be a canonical UUID',
  )
  assert.equal(
    Number.isSafeInteger(requestStartIndex)
      && requestStartIndex >= 0
      && requestStartIndex <= context.page.diagnostics.requests.length,
    true,
    'Completed print request start index is invalid',
  )
  const ownedRunIds = new Set([
    ...context.createdRuns.map((candidate) => candidate.runId),
    ...context.referencedRunIds,
  ])
  const ownedReportIds = new Set([
    ...context.createdRuns.map((candidate) => candidate.reportId),
    ...context.referencedReports.map((candidate) => candidate.reportId),
  ])
  assert.equal(
    ownedRunIds.has(runId) && ownedReportIds.has(reportId),
    true,
    'Completed print identity is outside canonical smoke ownership',
  )
  assert.deepEqual(
    Object.keys(bridgeReceipt).sort(),
    ['at', 'sequence', 'title'],
    'Completed print bridge receipt schema is invalid',
  )
  assert.equal(
    Number.isSafeInteger(bridgeReceipt.sequence)
      && bridgeReceipt.sequence > 0,
    true,
    'Completed print bridge sequence is invalid',
  )
  assert.equal(
    bridgeReceipt.title,
    'Trading Lab raw report',
    'Completed print bridge title is invalid',
  )
  assert.equal(
    typeof bridgeReceipt.at === 'string'
      && !Number.isNaN(Date.parse(bridgeReceipt.at))
      && new Date(bridgeReceipt.at).toISOString() === bridgeReceipt.at,
    true,
    'Completed print bridge timestamp is invalid',
  )

  const expectedPath =
    `/api/admin/trading-lab/reports/${reportId}/print`
  const requests = context.page.diagnostics.requests
    .slice(requestStartIndex)
    .filter((request) => (
      request.method === 'GET'
      && request.resourceType === 'Fetch'
      && networkPathname(request.url) === expectedPath
    ))
  assert.equal(
    requests.length,
    1,
    'Completed print must have one exact raw-report Fetch request',
  )
  const request = requests[0]
  assert.match(
    request.xRequestId,
    canonicalUuidPattern(),
    'Completed print request X-Request-ID is invalid',
  )
  const responses = context.page.diagnostics.responses.filter(
    (response) => (
      response.requestId === request.requestId
      && response.url === request.url
      && response.status === 200
      && response.resourceType === 'Fetch'
      && response.mimeType === 'text/plain'
    ),
  )
  assert.equal(
    responses.length,
    1,
    'Completed print must have one exact 200 text response',
  )
  const response = responses[0]
  assert.equal(
    response.xRequestId,
    request.xRequestId,
    'Completed print request and response X-Request-ID differ',
  )
  assert.match(
    response.requestTupleSha256,
    /^[0-9a-f]{64}$/u,
    'Completed print request tuple hash is invalid',
  )
  assert.equal(
    context.printCompletionReceipts.some(
      (receipt) => receipt.requestId === request.requestId,
    ),
    false,
    'Completed print receipt request ID must be unique',
  )
  const receipt = Object.freeze({
    requestId: request.requestId,
    runId,
    reportId,
    xRequestId: response.xRequestId,
    requestTupleSha256: response.requestTupleSha256,
    bridgeSequence: bridgeReceipt.sequence,
    bridgeTitle: bridgeReceipt.title,
    bridgeAt: bridgeReceipt.at,
    uiStatus: '打印流程已结束',
  })
  context.printCompletionReceipts.push(receipt)
  return receipt
}

export async function readAuthoritativeTradingLabConfig(page, apiUrl) {
  const path = '/api/admin/trading-lab/config'
  const result = await page.evaluate(async (origin, route) => {
    const token = localStorage.getItem('fx-platform-admin-token')
    if (token === null || token.length === 0) {
      return {
        status: 0,
        contentType: '',
        xRequestId: null,
        payload: null,
      }
    }
    const response = await fetch(`${origin}${route}`, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
      },
      redirect: 'manual',
    })
    const contentType = response.headers.get('content-type') ?? ''
    let payload = null
    if (/^application\/json(?:;|$)/iu.test(contentType)) {
      try {
        payload = await response.json()
      } catch {
        payload = null
      }
    } else {
      await response.text()
    }
    return {
      status: response.status,
      contentType,
      xRequestId: response.headers.get('x-request-id'),
      payload,
    }
  }, apiUrl, path)

  if (!isPlainRecord(result) || result.status !== 200) {
    throw new Error(
      `Authoritative Trading Lab config returned HTTP ${result?.status ?? 0}`,
    )
  }
  if (!/^application\/json(?:;|$)/iu.test(result.contentType ?? '')) {
    throw new Error('Authoritative Trading Lab config was not JSON')
  }
  if (!canonicalUuidPattern().test(result.xRequestId ?? '')) {
    throw new Error(
      'Authoritative Trading Lab config request correlation is invalid',
    )
  }
  if (
    !isPlainRecord(result.payload)
    || result.payload.success !== true
    || result.payload.code !== 'OK'
    || !isPlainRecord(result.payload.data)
  ) {
    throw new Error('Authoritative Trading Lab config envelope is invalid')
  }
  return normalizeAuthoritativeTradingLabConfig(result.payload.data)
}

async function normalizeAuthoritativeTradingLabConfig(config) {
  if (
    !hasExactKeys(config, [
      'codeVersion',
      'configSnapshot',
      'configSnapshotHash',
      'modelVersion',
      'symbolConfigVersion',
    ])
  ) {
    throw new Error('Authoritative Trading Lab config fields are invalid')
  }
  const snapshot = structuredClone(config.configSnapshot)
  if (
    !hasExactKeys(snapshot, [
      'codeVersion',
      'executionPolicy',
      'instruments',
      'modelVersion',
      'symbolConfigVersion',
    ])
    || !isPlainRecord(snapshot.executionPolicy)
    || !Array.isArray(snapshot.instruments)
    || typeof config.modelVersion !== 'string'
    || config.modelVersion.length === 0
    || typeof config.symbolConfigVersion !== 'string'
    || config.symbolConfigVersion.length === 0
    || typeof config.codeVersion !== 'string'
    || config.codeVersion.length === 0
    || snapshot.modelVersion !== config.modelVersion
    || snapshot.symbolConfigVersion !== config.symbolConfigVersion
    || snapshot.codeVersion !== config.codeVersion
  ) {
    throw new Error('Authoritative Trading Lab config version drifted')
  }
  if (
    typeof config.configSnapshotHash !== 'string'
    || !/^[0-9a-f]{64}$/u.test(config.configSnapshotHash)
    || await hashScenarioConfig(snapshot) !== config.configSnapshotHash
  ) {
    throw new Error('Authoritative Trading Lab config hash is invalid')
  }

  const spots = snapshot.instruments.filter((instrument) => (
    isPlainRecord(instrument)
    && instrument.productType === 'CRYPTO_SPOT'
    && typeof instrument.symbol === 'string'
    && instrument.symbol.length > 0
    && typeof instrument.baseAsset === 'string'
    && instrument.baseAsset.length > 0
    && instrument.quoteAsset === 'USDT'
  ))
  const pair = spots.map((spot) => ({
    spot,
    perp: snapshot.instruments.find((instrument) => (
      isPlainRecord(instrument)
      && instrument.productType === 'LINEAR_PERP'
      && typeof instrument.symbol === 'string'
      && instrument.symbol.length > 0
      && instrument.baseAsset === spot.baseAsset
      && instrument.quoteAsset === spot.quoteAsset
      && instrument.marginAsset === spot.quoteAsset
      && instrument.settlementAsset === spot.quoteAsset
    )),
  })).find(({ perp }) => perp !== undefined)
  if (pair === undefined) {
    throw new Error(
      'Authoritative Trading Lab config requires a paired USDT Spot and Perp',
    )
  }
  return Object.freeze({
    configSnapshot: snapshot,
    configSnapshotHash: config.configSnapshotHash,
    modelVersion: config.modelVersion,
    symbolConfigVersion: config.symbolConfigVersion,
    codeVersion: config.codeVersion,
    spot: structuredClone(pair.spot),
    perp: structuredClone(pair.perp),
  })
}

export async function generatedScenario(input) {
  const baseScenario = baseScenarioForBrowser(
    input.id,
    input.name,
    input.authority,
  )
  assert.equal(
    await hashScenarioConfig(baseScenario.configSnapshot),
    input.authority.configSnapshotHash,
    'Authoritative browser config changed before generation',
  )
  const scenario = generateRandomScenario({
    baseScenario,
    seed: input.seed,
    actionCount: input.actionCount,
    durationSeconds: input.durationSeconds,
    realistic: true,
    negativeMode: input.negativeMode,
    priceRange: { min: '100', max: '200' },
    leverageRange: { min: 2, max: 10 },
    fundingRateRange: { min: '-0.001', max: '0.001' },
    feeRateRange: { min: '0.0001', max: '0.001' },
    offsetRangeSteps: { min: 0, max: 2 },
    volatilitySteps: { min: 1, max: 3 },
  })
  scenario.id = input.id
  scenario.name = input.name
  assert.equal(
    await hashScenarioConfig(scenario.configSnapshot),
    input.authority.configSnapshotHash,
    'Generated browser scenario changed its authoritative config',
  )
  scenario.configSnapshotHash = input.authority.configSnapshotHash
  scenario.modelVersion = input.authority.modelVersion
  return normalizeScenario(scenario)
}

export async function buildControlledFailureScenario({
  authority,
  scenarioId = randomUUID(),
  failureActionId = randomUUID(),
  laterActionId = randomUUID(),
} = {}) {
  const scenario = baseScenarioForBrowser(
    scenarioId,
    'Phase 4 controlled validation failure',
    authority,
  )
  scenario.seed = 'phase4-browser-controlled-failure'
  scenario.initialBalances = {
    [authority.spot.quoteAsset]: '0',
    [authority.spot.baseAsset]: '0',
  }
  scenario.timeline = [
    {
      id: failureActionId,
      sequence: 1,
      type: 'PLACE_ORDER',
      symbol: authority.spot.symbol,
      productType: 'CRYPTO_SPOT',
      trigger: {
        type: 'VIRTUAL_TIME',
        atSecond: 1,
      },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '1',
        quantityUnit: 'QUOTE',
      },
    },
    {
      id: laterActionId,
      sequence: 2,
      type: 'PLACE_ORDER',
      symbol: authority.spot.symbol,
      productType: 'CRYPTO_SPOT',
      trigger: {
        type: 'VIRTUAL_TIME',
        atSecond: 2,
      },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '0.5',
        quantityUnit: 'QUOTE',
      },
    },
  ]
  assert.equal(
    await hashScenarioConfig(scenario.configSnapshot),
    authority.configSnapshotHash,
    'Controlled failure scenario changed its authoritative config',
  )
  scenario.configSnapshotHash = authority.configSnapshotHash
  return normalizeScenario(scenario)
}

export function expectedControlledFailureEvidence({
  runId,
  validationGeneration,
  failureAction,
  unexecutedAction,
}) {
  if (
    typeof runId !== 'string'
    || !canonicalUuidPattern().test(runId)
    || !Number.isSafeInteger(validationGeneration)
    || validationGeneration < 0
    || !isPlainRecord(failureAction)
    || typeof failureAction.id !== 'string'
    || !canonicalUuidPattern().test(failureAction.id)
    || !isPlainRecord(unexecutedAction)
    || typeof unexecutedAction.id !== 'string'
    || !canonicalUuidPattern().test(unexecutedAction.id)
  ) {
    throw new Error('Controlled failure runtime identity input is invalid')
  }
  return {
    failurePoint: {
      operation: 'PUBLIC_ACTION',
      actionId: javaNameUuid(
        `${runId}:${validationGeneration}:${failureAction.id}`,
      ),
      tickSequence: 1,
      actionSequence: 1,
      status: 400,
      code: 'INSUFFICIENT_BALANCE',
    },
    unexecuted: [{
      actionId: javaNameUuid(
        `${runId}:${validationGeneration}:${unexecutedAction.id}`,
      ),
      tickSequence: 2,
      actionSequence: 2,
      type: 'PLACE_ORDER',
    }],
  }
}

export function assertControlledFailureEvidence({
  executionFailure,
  ...identity
}) {
  const expected = expectedControlledFailureEvidence(identity)
  assert.deepEqual(
    executionFailure?.payload?.failurePoint,
    expected.failurePoint,
    'Controlled failure point must preserve the exact runtime action and real 400',
  )
  assert.deepEqual(
    executionFailure?.payload?.unexecuted,
    expected.unexecuted,
    'Controlled failure must list only the exact ordered later runtime action',
  )
  return expected
}

function javaNameUuid(value) {
  const bytes = createHash('md5').update(value, 'utf8').digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-')
}

function baseScenarioForBrowser(id, name, authority) {
  if (
    !isPlainRecord(authority)
    || !isPlainRecord(authority.configSnapshot)
    || !isPlainRecord(authority.spot)
    || !isPlainRecord(authority.perp)
    || typeof authority.configSnapshotHash !== 'string'
    || !/^[0-9a-f]{64}$/u.test(authority.configSnapshotHash)
    || authority.modelVersion !== authority.configSnapshot.modelVersion
  ) {
    throw new Error('Authoritative browser config is required')
  }
  const executionPolicy = structuredClone(
    authority.configSnapshot.executionPolicy,
  )
  const leverage = (
    Number.isSafeInteger(authority.perp.defaultLeverage)
    && authority.perp.defaultLeverage > 0
  )
    ? authority.perp.defaultLeverage
    : 1
  return {
    id,
    name,
    description: 'Real Admin UI Phase 4 smoke input',
    negativeMode: false,
    seed: 'phase4-browser-base',
    modelVersion: authority.modelVersion,
    configSnapshot: structuredClone(authority.configSnapshot),
    configSnapshotHash: authority.configSnapshotHash,
    executionPolicy,
    marketPath: {
      virtualStart: '2026-07-25T00:00:00.000Z',
      realistic: false,
      instruments: [
        browserPath(authority.spot),
        browserPath(authority.perp),
      ],
    },
    initialBalances: {
      [authority.spot.quoteAsset]: '10000',
      [authority.spot.baseAsset]: '10',
    },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage,
    },
    symbols: [
      {
        symbol: authority.spot.symbol,
        productType: authority.spot.productType,
      },
      {
        symbol: authority.perp.symbol,
        productType: authority.perp.productType,
      },
    ],
    timeline: [],
  }
}

function browserPath(instrument) {
  return {
    mode: 'SIMPLE',
    productType: instrument.productType,
    symbol: instrument.symbol,
    seed: `phase4-browser-path-${instrument.symbol}`,
    last: {
      start: '100',
      segments: [{
        target: '102',
        durationSeconds: 120,
        offsetRangeSteps: 0,
        volatilitySteps: 0,
        maxStepPerSecond: 100,
      }],
    },
    spreadSteps: 2,
    indexOffsetSteps: 0,
    basisSteps: instrument.productType === 'LINEAR_PERP' ? 1 : 0,
    ...(instrument.productType === 'LINEAR_PERP'
      ? { fundingRate: instrument.fixedFundingRate }
      : {}),
  }
}

function isPlainRecord(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasExactKeys(value, expectedKeys) {
  return isPlainRecord(value)
    && canonicalJson(Object.keys(value).sort())
      === canonicalJson([...expectedKeys].sort())
}

async function writeScenarioArtifact(context, fileName, scenario) {
  const normalized = normalizeScenario(scenario)
  const path = join(context.artifacts.root, safeFileName(fileName))
  await writeFile(path, `${canonicalJson(normalized)}\n`, {
    encoding: 'utf8',
    flag: 'wx',
  })
  return path
}

async function uploadScenario(page, path) {
  const expectedName = JSON.parse(await readFile(path, 'utf8')).name
  const document = await page.send('DOM.getDocument', { depth: 4, pierce: true })
  const selected = await page.send('DOM.querySelector', {
    nodeId: document.root.nodeId,
    selector: '[data-testid="trading-lab-scenario-import-input"]',
  })
  if (!selected.nodeId) {
    throw new Error(
      'Stable data-testid trading-lab-scenario-import-input is missing',
    )
  }
  await page.send('DOM.setFileInputFiles', {
    nodeId: selected.nodeId,
    files: [resolve(path)],
  })
  await waitFor(
    () => page.evaluate((expected) => (
      [...document.querySelectorAll('label')].some((label) => (
        label.textContent?.includes('名称')
        && label.querySelector('input')?.value === expected
      ))
    ), expectedName),
    'imported Trading Lab scenario',
    PAGE_TIMEOUT_MS,
  )
}

async function clickRole(page, role, accessibleName) {
  await waitForRoleEnabled(page, role, accessibleName)
  const point = await page.evaluate((expectedRole, expectedName) => {
    const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
    const implicitRole = (element) => {
      const tag = element.tagName.toLowerCase()
      if (tag === 'button') return 'button'
      if (tag === 'a' && element.hasAttribute('href')) return 'link'
      if (tag === 'input' && element.type === 'checkbox') return 'checkbox'
      return null
    }
    const name = (element) => normalize(
      element.getAttribute('aria-label') ?? element.textContent ?? '',
    )
    const match = [...document.querySelectorAll('*')].find((element) => (
      (element.getAttribute('role') ?? implicitRole(element)) === expectedRole
      && name(element) === expectedName
    ))
    if (!(match instanceof HTMLElement)) {
      throw new Error(
        `Role ${expectedRole} named ${expectedName} is missing`,
      )
    }
    if (
      ('disabled' in match && match.disabled)
      || match.getAttribute('aria-disabled') === 'true'
    ) {
      throw new Error(
        `Role ${expectedRole} named ${expectedName} is disabled`,
      )
    }
    match.scrollIntoView({ block: 'center', inline: 'center' })
    const bounds = match.getBoundingClientRect()
    return {
      x: Math.floor(bounds.left + bounds.width / 2),
      y: Math.floor(bounds.top + bounds.height / 2),
      width: bounds.width,
      height: bounds.height,
    }
  }, role, accessibleName)
  assert.ok(
    point.width > 0 && point.height > 0,
    `Role ${role} named ${accessibleName} has no clickable bounds`,
  )
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseMoved',
    x: point.x,
    y: point.y,
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mousePressed',
    x: point.x,
    y: point.y,
    button: 'left',
    clickCount: 1,
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseReleased',
    x: point.x,
    y: point.y,
    button: 'left',
    clickCount: 1,
  })
}

async function roleDisabled(page, role, accessibleName) {
  return page.evaluate((expectedRole, expectedName) => {
    const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
    const candidates = expectedRole === 'button'
      ? [...document.querySelectorAll('button,[role="button"]')]
      : [...document.querySelectorAll(`[role="${expectedRole}"]`)]
    const match = candidates.find((element) => (
      normalize(
        element.getAttribute('aria-label') ?? element.textContent ?? '',
      ) === expectedName
    ))
    if (!(match instanceof HTMLElement)) {
      throw new Error(
        `Role ${expectedRole} named ${expectedName} is missing`,
      )
    }
    return (
      ('disabled' in match && Boolean(match.disabled))
      || match.getAttribute('aria-disabled') === 'true'
    )
  }, role, accessibleName)
}

async function waitForRoleEnabled(page, role, accessibleName) {
  await waitFor(
    async () => !(await roleDisabled(page, role, accessibleName)),
    `${role} ${accessibleName} enabled`,
    PAGE_TIMEOUT_MS,
  )
}

export async function waitForLabelControl(
  page,
  labelText,
  timeoutMs = PAGE_TIMEOUT_MS,
) {
  await waitFor(
    () => page.evaluate((expected) => {
      const normalize = (text) => text.replace(/\s+/gu, ' ').trim()
      const label = [...document.querySelectorAll('label')].find(
        (candidate) => (
          normalize(candidate.childNodes[0]?.textContent ?? '')
            .startsWith(expected)
          || normalize(candidate.textContent ?? '').startsWith(expected)
        ),
      )
      const nestedControl = label?.querySelector('input,textarea,select')
      const target = label?.getAttribute('for')
      const control = nestedControl
        ?? (target === null || target === undefined
          ? null
          : document.getElementById(target))
      return (
        control instanceof HTMLInputElement
        || control instanceof HTMLTextAreaElement
        || control instanceof HTMLSelectElement
      )
    }, labelText),
    `label ${labelText} form control`,
    timeoutMs,
  )
}

async function fillLabel(page, labelText, value) {
  await waitForLabelControl(page, labelText)
  await page.evaluate((expected, nextValue) => {
    const normalize = (text) => text.replace(/\s+/gu, ' ').trim()
    const labels = [...document.querySelectorAll('label')]
    const label = labels.find((candidate) => (
      normalize(candidate.childNodes[0]?.textContent ?? '').startsWith(expected)
      || normalize(candidate.textContent ?? '').startsWith(expected)
    ))
    let control = label?.querySelector('input,textarea,select')
    if (control === null || control === undefined) {
      const target = label?.getAttribute('for')
      control = target === null || target === undefined
        ? null
        : document.getElementById(target)
    }
    if (
      !(control instanceof HTMLInputElement)
      && !(control instanceof HTMLTextAreaElement)
      && !(control instanceof HTMLSelectElement)
    ) {
      throw new Error(`Label ${expected} has no form control`)
    }
    const prototype = control instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : control instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype
    const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set
    setter?.call(control, nextValue)
    control.dispatchEvent(new Event('input', { bubbles: true }))
    control.dispatchEvent(new Event('change', { bubbles: true }))
    control.blur()
  }, labelText, value)
}

async function clickTestId(page, testId) {
  await page.evaluate((id) => {
    const element = document.querySelector(`[data-testid="${CSS.escape(id)}"]`)
    if (!(element instanceof HTMLElement)) {
      throw new Error(`data-testid ${id} is missing`)
    }
    if (
      ('disabled' in element && element.disabled)
      || element.getAttribute('aria-disabled') === 'true'
    ) {
      throw new Error(`data-testid ${id} is disabled`)
    }
    element.scrollIntoView({ block: 'center', inline: 'center' })
    element.click()
  }, testId)
}

async function fillTestId(page, testId, value) {
  await page.evaluate((id, nextValue) => {
    const element = document.querySelector(`[data-testid="${CSS.escape(id)}"]`)
    if (
      !(element instanceof HTMLInputElement)
      && !(element instanceof HTMLTextAreaElement)
    ) {
      throw new Error(`data-testid ${id} is not an editable control`)
    }
    const prototype = element instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set
      ?.call(element, nextValue)
    element.dispatchEvent(new Event('input', { bubbles: true }))
    element.dispatchEvent(new Event('change', { bubbles: true }))
    element.blur()
  }, testId, value)
}

async function textTestId(page, testId) {
  return page.evaluate((id) => {
    const element = document.querySelector(`[data-testid="${CSS.escape(id)}"]`)
    if (!(element instanceof HTMLElement)) {
      throw new Error(`data-testid ${id} is missing`)
    }
    return (element.textContent ?? '').replace(/\s+/gu, ' ').trim()
  }, testId)
}

async function valueTestId(page, testId) {
  return page.evaluate((id) => {
    const element = document.querySelector(`[data-testid="${CSS.escape(id)}"]`)
    if (
      !(element instanceof HTMLInputElement)
      && !(element instanceof HTMLTextAreaElement)
      && !(element instanceof HTMLSelectElement)
    ) {
      throw new Error(`data-testid ${id} has no value`)
    }
    return element.value
  }, testId)
}

async function waitForTestId(page, testId) {
  await waitFor(
    () => page.evaluate((id) => (
      document.querySelector(`[data-testid="${CSS.escape(id)}"]`) !== null
    ), testId),
    `data-testid ${testId}`,
    PAGE_TIMEOUT_MS,
  )
}

async function selectActualMarkerInstrument(page, accessibleNames) {
  for (const accessibleName of accessibleNames) {
    await clickRole(page, 'button', accessibleName)
    await waitFor(
      () => page.evaluate((expected) => {
        const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
        return [...document.querySelectorAll('button')].some((button) => (
          normalize(button.textContent ?? '') === expected
          && button.getAttribute('aria-pressed') === 'true'
        ))
      }, accessibleName),
      `ACTUAL chart instrument ${accessibleName}`,
      PAGE_TIMEOUT_MS,
    )
    const markerCount = await page.evaluate(() => (
      document.querySelectorAll(
        '[data-testid="trading-lab-chart-event-marker"]',
      ).length
    ))
    if (markerCount > 0) return accessibleName
  }
  throw new Error(
    'Completed ACTUAL chart has no durable marker on any scenario instrument',
  )
}

async function setCheckboxLabel(page, labelText, checked) {
  await page.evaluate((expected, desired) => {
    const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
    const label = [...document.querySelectorAll('label')].find(
      (candidate) => normalize(candidate.textContent ?? '') === expected,
    )
    const input = label?.querySelector('input[type="checkbox"]')
    if (!(input instanceof HTMLInputElement)) {
      throw new Error(`Checkbox label ${expected} is missing`)
    }
    if (input.checked !== desired) input.click()
  }, labelText, checked)
}

async function exerciseChartPointer(page, testId) {
  const box = await page.evaluate((id) => {
    const element = document.querySelector(`[data-testid="${CSS.escape(id)}"]`)
    if (!(element instanceof HTMLElement)) {
      throw new Error(`Chart data-testid ${id} is missing`)
    }
    const bounds = element.getBoundingClientRect()
    return {
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
    }
  }, testId)
  assert.ok(box.width > 200 && box.height > 100, 'Chart canvas is not visible')
  const x = Math.floor(box.x + box.width / 2)
  const y = Math.floor(box.y + box.height / 2)
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseWheel',
    x,
    y,
    deltaX: 0,
    deltaY: -180,
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mousePressed',
    x,
    y,
    button: 'left',
    clickCount: 1,
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseMoved',
    x: x + 80,
    y,
    button: 'left',
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseReleased',
    x: x + 80,
    y,
    button: 'left',
    clickCount: 1,
  })
  await page.send('Input.dispatchMouseEvent', {
    type: 'mouseMoved',
    x: x - 40,
    y: y + 20,
  })
}

async function definitionValue(page, term) {
  return page.evaluate((expected) => {
    const normalize = (value) => value.replace(/\s+/gu, ' ').trim()
    const dt = [...document.querySelectorAll('dt')].find(
      (candidate) => normalize(candidate.textContent ?? '') === expected,
    )
    const dd = dt?.parentElement?.querySelector('dd')
    if (!(dd instanceof HTMLElement)) {
      throw new Error(`Definition ${expected} is missing`)
    }
    return normalize(dd.textContent ?? '')
  }, term)
}

async function waitForDefinitionUuid(page, term) {
  return waitForValue(async () => {
    try {
      const value = await definitionValue(page, term)
      return canonicalUuidPattern().test(value) ? value : null
    } catch {
      return null
    }
  }, `canonical ${term}`, PAGE_TIMEOUT_MS)
}

export async function waitForTradingLabEnvironmentReady(
  page,
  timeoutMs = PAGE_TIMEOUT_MS,
) {
  let snapshot = null
  await waitFor(async () => {
    const [relay, validationHealth] = await Promise.all([
      definitionValue(page, 'Relay'),
      definitionValue(page, 'Validation health'),
    ])
    snapshot = { relay, validationHealth }
    return relay === '运行中' && /^(?:UP|healthy)$/iu.test(validationHealth)
  }, 'Trading Lab validation environment readiness', timeoutMs)
  return snapshot
}

async function numericDefinitionValue(page, term) {
  const value = await definitionValue(page, term)
  const parsed = Number(value)
  assert.ok(Number.isSafeInteger(parsed) && parsed >= 0, `${term} is not numeric`)
  return parsed
}

async function waitForText(page, text, timeoutMs = PAGE_TIMEOUT_MS) {
  await waitFor(
    () => page.evaluate((expected) => (
      document.body?.innerText.includes(expected) ?? false
    ), text),
    `visible text ${text}`,
    timeoutMs,
  )
}

async function waitForRunId(page) {
  return waitForValue(async () => {
    const value = await page.evaluate(() => (
      new URL(location.href).searchParams.get('runId')
    ))
    return canonicalUuidPattern().test(value ?? '') ? value : null
  }, 'created Trading Lab run ID', PAGE_TIMEOUT_MS)
}

async function waitForRunState(
  page,
  states,
  timeoutMs = PAGE_TIMEOUT_MS,
) {
  return waitForValue(async () => {
    const state = await definitionValue(page, '主 Run 状态')
    return states.has(state) ? state : null
  }, `Trading Lab run state ${[...states].join('/')}`, timeoutMs)
}

async function waitForTick(page, minimum) {
  return waitForValue(async () => {
    const value = await definitionValue(page, '已观察 Tick')
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) && parsed >= minimum ? parsed : null
  }, `SSE Tick >= ${minimum}`, RUN_TIMEOUT_MS)
}

async function captureScreenshot(context, label) {
  const fileName = `${String(context.screenshots.length + 1).padStart(2, '0')}-${safeFileName(label)}.png`
  const path = join(context.artifacts.screenshots, fileName)
  await context.page.send('Page.bringToFront')
  await context.page.evaluate(() => new Promise((resolveFrame) => {
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
    optimizeForSpeed: true,
  }
  let result
  try {
    result = await context.page.send(
      'Page.captureScreenshot',
      options,
      PAGE_TIMEOUT_MS,
    )
  } catch (error) {
    if (asError(error).message !== 'CDP Page.captureScreenshot timed out') {
      throw error
    }
    await context.page.send('Page.bringToFront')
    await sleep(250)
    result = await context.page.send(
      'Page.captureScreenshot',
      options,
      PAGE_TIMEOUT_MS,
    )
  }
  await writeFile(path, Buffer.from(result.data, 'base64'), { flag: 'wx' })
  context.screenshots.push(path)
  return path
}

async function downloadCurrentReport(context) {
  const reportId = await waitForDefinitionUuid(
    context.page,
    'Report ID',
  )
  const before = context.page.diagnostics.downloads.length
  const requestStartIndex = context.page.diagnostics.requests.length
  await clickRole(context.page, 'button', '下载原始 JSON')
  const row = await waitForReportDownload(context, before)
  await waitFor(
    () => existsSync(row.path),
    'downloaded raw report file',
    10_000,
  )
  const source = await readFile(row.path, 'utf8')
  let payload
  try {
    payload = JSON.parse(source)
  } catch {
    throw new Error('Downloaded Trading Lab report is not JSON')
  }
  recordCompletedReportDownloadReceipt(context, {
    reportId,
    row,
    source,
    requestStartIndex,
  })
  return { path: row.path, payload }
}

async function waitForReportDownload(context, before) {
  const deadline = Date.now() + RUN_TIMEOUT_MS
  while (Date.now() <= deadline) {
    const candidates = context.page.diagnostics.downloads.slice(before)
    const completed = candidates.find(
      (candidate) => candidate.state === 'completed',
    )
    if (completed !== undefined) return completed
    const transferError = await reportTransferError(context.page)
    if (transferError !== null) {
      throw new Error(
        `Trading Lab report transfer failed before download: ${transferError}`,
      )
    }
    await sleep(100)
  }
  throw new Error('Timed out waiting for completed raw report download')
}

async function reportTransferError(page) {
  return page.evaluate(() => {
    const panel = document.querySelector('.trading-lab-report-panel')
    const status = panel?.querySelector('[role="status"]')
    const text = status?.textContent?.replace(/\s+/gu, ' ').trim() ?? ''
    if (text.length === 0 || text.includes('下载流程已结束')) return null
    return text.slice(0, 1_000)
  })
}

function recordCompletedReportDownloadReceipt(
  context,
  {
    reportId,
    row,
    source,
    requestStartIndex,
  },
) {
  assert.match(
    reportId,
    canonicalUuidPattern(),
    'Downloaded report ID must be a canonical UUID',
  )
  const expectedPath =
    `/api/admin/trading-lab/reports/${reportId}/download`
  const requests = context.page.diagnostics.requests
    .slice(requestStartIndex)
    .filter((request) => (
      request.method === 'GET'
      && request.resourceType === 'Fetch'
      && networkPathname(request.url) === expectedPath
    ))
  assert.equal(
    requests.length,
    1,
    'Raw report download must have one exact Fetch request',
  )
  const request = requests[0]
  const responses = context.page.diagnostics.responses.filter(
    (response) => (
      response.requestId === request.requestId
      && response.url === request.url
      && response.status === 200
      && response.resourceType === 'Fetch'
      && response.mimeType === 'application/json'
    ),
  )
  assert.equal(
    responses.length,
    1,
    'Raw report download must have one exact 200 JSON response',
  )
  const bytes = Buffer.byteLength(source, 'utf8')
  assert.ok(bytes > 0, 'Downloaded raw report must not be empty')
  assert.equal(
    row.suggestedFilename,
    `trading-lab-report-${reportId}.json`,
    'Browser download filename must bind the report ID',
  )
  assert.equal(row.state, 'completed')
  assert.equal(row.receivedBytes, bytes)
  assert.equal(row.totalBytes, bytes)
  assert.equal(row.path.length > 0 && existsSync(row.path), true)
  assert.equal(
    context.downloadReceipts.some(
      (receipt) => receipt.requestId === request.requestId,
    ),
    false,
    'Raw report download receipt request ID must be unique',
  )
  context.downloadReceipts.push(Object.freeze({
    requestId: request.requestId,
    reportId,
    guid: row.guid,
    path: row.path,
    bytes,
  }))
}

function recordTerminalSseCloseReceipt(
  context,
  {
    runId,
    terminalState,
    responseStartIndex = 0,
  },
) {
  assert.match(
    runId,
    canonicalUuidPattern(),
    'Terminal SSE Run ID must be a canonical UUID',
  )
  assert.ok(
    TERMINAL_STATES.has(terminalState),
    'Terminal SSE receipt requires a terminal state',
  )
  const expectedPath =
    `/api/admin/trading-lab/runs/${runId}/events`
  const response = context.page.diagnostics.responses
    .slice(responseStartIndex)
    .filter((candidate) => (
      candidate.status === 200
      && candidate.resourceType === 'Fetch'
      && candidate.mimeType === 'text/event-stream'
      && networkPathname(candidate.url) === expectedPath
    ))
    .at(-1)
  if (response === undefined) return null
  const requests = context.page.diagnostics.requests.filter(
    (request) => (
      request.requestId === response.requestId
      && request.method === 'GET'
      && request.resourceType === 'Fetch'
      && request.url === response.url
    ),
  )
  assert.equal(
    requests.length,
    1,
    'Terminal SSE receipt must bind one exact Fetch request',
  )
  const existing = context.sseCloseReceipts.find(
    (receipt) => receipt.requestId === response.requestId,
  )
  if (existing !== undefined) {
    assert.deepEqual(
      existing,
      {
        requestId: response.requestId,
        runId,
        terminalState,
      },
      'Terminal SSE receipt identity changed',
    )
    return existing
  }
  const receipt = Object.freeze({
    requestId: response.requestId,
    runId,
    terminalState,
  })
  context.sseCloseReceipts.push(receipt)
  return receipt
}

function assertReportSchema(payload) {
  const sections = [
    'metadata',
    'actor',
    'environment',
    'scenario',
    'modelVersion',
    'configSnapshot',
    'localCalculation',
    'lifecycle',
    'apiTrace',
    'marketTicks',
    'checkpoints',
    'actualState',
    'errors',
    'cleanup',
  ]
  assert.deepEqual(Object.keys(payload), sections)
}

export function expectedNegativeTraceEvidence(report, scenario) {
  assert.equal(
    scenario?.negativeMode,
    true,
    'Negative evidence requires an explicit negative scenario',
  )
  assert.ok(
    Array.isArray(scenario.timeline),
    'Negative scenario timeline is missing',
  )
  const expectedActions = scenario.timeline.filter((action) => (
    action !== null
    && typeof action === 'object'
    && action.expectedError !== undefined
  ))
  assert.ok(
    expectedActions.length > 0,
    'Negative scenario has no expected error action',
  )
  assert.ok(
    report !== null && typeof report === 'object' && !Array.isArray(report),
    'Negative report is invalid',
  )
  assert.equal(
    Object.hasOwn(report, 'productPass')
      || Object.hasOwn(report, 'productFail'),
    false,
    'Negative report cannot contain a product Pass/Fail verdict',
  )
  assert.equal(
    report.actualState?.terminalState,
    'COMPLETED',
    'Expected-error negative Run must complete',
  )
  assert.ok(
    Array.isArray(report.errors),
    'Negative report errors section is invalid',
  )
  assert.equal(
    report.errors.length,
    0,
    'Expected errors cannot be recorded as unexpected report errors',
  )
  assert.equal(
    report.cleanup?.status,
    'SUCCEEDED',
    'Expected-error negative Run cleanup must succeed',
  )
  assert.ok(
    Array.isArray(report.apiTrace),
    'Negative report API trace is invalid',
  )

  return expectedActions.map((action) => {
    const expected = action.expectedError
    assert.ok(
      Number.isSafeInteger(action.sequence)
        && action.sequence > 0
        && typeof action.id === 'string'
        && action.id.length > 0
        && expected !== null
        && typeof expected === 'object'
        && Number.isSafeInteger(expected.status)
        && expected.status >= 400
        && expected.status <= 499
        && typeof expected.code === 'string'
        && /^[A-Z][A-Z0-9_]{0,79}$/u.test(expected.code),
      'Negative expected error action is invalid',
    )
    const matches = report.apiTrace.filter((trace) => {
      const request = trace?.requestBody
      const command = request?.sanitizedRequest
      const response = trace?.responseBody
      return (
        trace?.exception === null
        && request?.environment === 'validation'
        && request?.method === 'POST'
        && request?.sequence === action.sequence
        && command?.scope === 'COMMAND'
        && command?.operation === 'PUBLIC_ACTION'
        && command?.outcome === 'EXPECTED_ERROR'
        && response?.recordedException === null
        && response?.status === expected.status
        && response?.sanitizedResponse?.success === false
        && response?.sanitizedResponse?.code === expected.code
        && typeof trace?.url === 'string'
      )
    })
    assert.equal(
      matches.length,
      1,
      `Negative action ${action.id} requires exactly one exact expected-error command trace`,
    )
    let path
    try {
      path = new URL(matches[0].url).pathname
    } catch {
      throw new Error('Negative expected-error command trace URL is invalid')
    }
    assert.ok(
      path.startsWith('/api/'),
      'Negative expected-error command trace is not a real API path',
    )
    return Object.freeze({
      actionId: action.id,
      sequence: action.sequence,
      status: expected.status,
      code: expected.code,
      path,
    })
  })
}

function assertSseEvidence(diagnostics) {
  assert.ok(
    diagnostics.responses.some((response) => (
      response.status === 200
      && response.mimeType === 'text/event-stream'
      && /\/api\/admin\/trading-lab\/runs\/[^/]+\/events$/u
        .test(new URL(response.url).pathname)
    )),
    'Real SSE response evidence is missing',
  )
}

export function classifyExpectedCanceledNetworkFailures({
  diagnostics,
  sseCloseReceipts,
  downloadReceipts,
  printReceipts,
  ownedRunIds,
  ownedReportIds,
  fileExists = existsSync,
}) {
  if (
    diagnostics === null
    || typeof diagnostics !== 'object'
    || !Array.isArray(diagnostics.failures)
    || !Array.isArray(diagnostics.requests)
    || !Array.isArray(diagnostics.responses)
    || !Array.isArray(diagnostics.downloads)
    || !Array.isArray(sseCloseReceipts)
    || !Array.isArray(downloadReceipts)
    || !Array.isArray(printReceipts)
    || typeof fileExists !== 'function'
  ) {
    throw new Error('Canceled network failure evidence is invalid')
  }
  const ownedRuns = canonicalNetworkUuidSet(
    ownedRunIds,
    'owned Run IDs',
  )
  const ownedReports = canonicalNetworkUuidSet(
    ownedReportIds,
    'owned report IDs',
  )
  const sseByRequest = uniqueNetworkReceiptMap(
    sseCloseReceipts,
    'SSE close receipts',
  )
  const downloadByRequest = uniqueNetworkReceiptMap(
    downloadReceipts,
    'download receipts',
  )
  const printByRequest = uniqueCompletedPrintReceiptMap(printReceipts)
  const consumedSse = new Set()
  const consumedDownloads = new Set()
  const consumedPrints = new Set()

  return diagnostics.failures.map((failure) => {
    const exchange = exactCanceledFetchExchange(diagnostics, failure)
    if (exchange === null) return unexpectedNetworkFailure(failure)

    const sseReceipt = sseByRequest.get(failure.requestId)
    if (
      sseReceipt !== undefined
      && !consumedSse.has(failure.requestId)
      && ownedRuns.has(sseReceipt.runId)
      && TERMINAL_STATES.has(sseReceipt.terminalState)
      && exchange.response.mimeType === 'text/event-stream'
      && networkPathname(exchange.request.url)
        === `/api/admin/trading-lab/runs/${sseReceipt.runId}/events`
    ) {
      consumedSse.add(failure.requestId)
      return {
        ...failure,
        expected: true,
        expectedReason: 'OWNED_TERMINAL_SSE_CLOSE',
        expectedReceipt: {
          requestId: failure.requestId,
          runId: sseReceipt.runId,
          terminalState: sseReceipt.terminalState,
        },
      }
    }

    const printReceipt = printByRequest.get(failure.requestId)
    if (
      printReceipt !== undefined
      && !consumedPrints.has(failure.requestId)
      && ownedRuns.has(printReceipt.runId)
      && ownedReports.has(printReceipt.reportId)
      && exchange.request.xRequestId === printReceipt.xRequestId
      && exchange.response.xRequestId === printReceipt.xRequestId
      && exchange.response.requestTupleSha256
        === printReceipt.requestTupleSha256
      && exchange.response.mimeType === 'text/plain'
      && networkPathname(exchange.request.url)
        === (
          '/api/admin/trading-lab/reports/'
          + `${printReceipt.reportId}/print`
        )
    ) {
      consumedPrints.add(failure.requestId)
      return {
        ...failure,
        expected: true,
        expectedReason: 'OWNED_COMPLETED_REPORT_PRINT',
        expectedReceipt: { ...printReceipt },
      }
    }

    const downloadReceipt = downloadByRequest.get(failure.requestId)
    const completedDownloads = downloadReceipt === undefined
      ? []
      : diagnostics.downloads.filter((download) => (
          download.guid === downloadReceipt.guid
          && download.path === downloadReceipt.path
          && download.state === 'completed'
          && download.receivedBytes === downloadReceipt.bytes
          && download.totalBytes === downloadReceipt.bytes
          && download.suggestedFilename
            === `trading-lab-report-${downloadReceipt.reportId}.json`
        ))
    if (
      downloadReceipt !== undefined
      && !consumedDownloads.has(failure.requestId)
      && ownedReports.has(downloadReceipt.reportId)
      && exchange.response.mimeType === 'application/json'
      && networkPathname(exchange.request.url)
        === (
          '/api/admin/trading-lab/reports/'
          + `${downloadReceipt.reportId}/download`
        )
      && Number.isSafeInteger(downloadReceipt.bytes)
      && downloadReceipt.bytes > 0
      && completedDownloads.length === 1
      && fileExists(downloadReceipt.path)
    ) {
      consumedDownloads.add(failure.requestId)
      return {
        ...failure,
        expected: true,
        expectedReason: 'OWNED_COMPLETED_REPORT_DOWNLOAD',
        expectedReceipt: {
          requestId: failure.requestId,
          reportId: downloadReceipt.reportId,
          guid: downloadReceipt.guid,
          bytes: downloadReceipt.bytes,
        },
      }
    }
    return unexpectedNetworkFailure(failure)
  })
}

export function classifyExpectedConsoleErrors({ diagnostics, receipts }) {
  if (
    diagnostics === null
    || typeof diagnostics !== 'object'
    || Array.isArray(diagnostics)
    || !Array.isArray(diagnostics.console)
    || !Array.isArray(diagnostics.requests)
    || !Array.isArray(diagnostics.responses)
    || !Array.isArray(receipts)
    || diagnostics.console.some((entry) => (
      entry === null
      || typeof entry !== 'object'
      || Array.isArray(entry)
    ))
  ) {
    throw new Error('Console error evidence is invalid')
  }
  const receiptsByRequestId = new Map()
  for (const receipt of receipts) {
    if (
      receipt === null
      || typeof receipt !== 'object'
      || Array.isArray(receipt)
      || canonicalJson(Object.keys(receipt).sort()) !== canonicalJson([
        'message',
        'method',
        'mimeType',
        'requestId',
        'requestTupleSha256',
        'resourceType',
        'status',
        'url',
        'xRequestId',
      ])
      || typeof receipt.requestId !== 'string'
      || receipt.requestId.length === 0
      || typeof receipt.xRequestId !== 'string'
      || !canonicalUuidPattern().test(receipt.xRequestId)
      || typeof receipt.requestTupleSha256 !== 'string'
      || !/^[0-9a-f]{64}$/u.test(receipt.requestTupleSha256)
      || typeof receipt.url !== 'string'
      || networkPathname(receipt.url) === null
      || typeof receipt.status !== 'number'
      || !Number.isInteger(receipt.status)
      || receipt.status < 100
      || receipt.status > 599
      || typeof receipt.method !== 'string'
      || !/^[A-Z]+$/u.test(receipt.method)
      || typeof receipt.resourceType !== 'string'
      || receipt.resourceType.length === 0
      || typeof receipt.mimeType !== 'string'
      || receipt.mimeType.length === 0
      || typeof receipt.message !== 'string'
      || receipt.message.length === 0
      || receipt.message.length > 2_000
    ) {
      throw new Error('Console error receipt is invalid')
    }
    if (receiptsByRequestId.has(receipt.requestId)) {
      throw new Error('Console error receipt has a duplicate request ID')
    }
    receiptsByRequestId.set(receipt.requestId, receipt)
  }
  if (receiptsByRequestId.size > 1) {
    throw new Error(
      'Console error evidence allows only one permission denial receipt',
    )
  }
  const consumed = new Set()
  return diagnostics.console.map((entry) => {
    const result = unexpectedConsoleEntry(entry)
    const receipt = receiptsByRequestId.get(entry.networkRequestId)
    const requests = receipt === undefined
      ? []
      : diagnostics.requests.filter((request) => (
          request?.requestId === receipt.requestId
          && request?.url === receipt.url
        ))
    const responses = receipt === undefined
      ? []
      : diagnostics.responses.filter((response) => (
          response?.requestId === receipt.requestId
          && response?.url === receipt.url
        ))
    const request = requests.length === 1 ? requests[0] : null
    const response = responses.length === 1 ? responses[0] : null
    if (
      receipt !== undefined
      && !consumed.has(receipt.requestId)
      && receipt.status === 403
      && receipt.method === 'GET'
      && receipt.resourceType === 'Fetch'
      && receipt.mimeType === 'application/json'
      && networkPathname(receipt.url) === PERMISSION_ENVIRONMENT_PATH
      && receipt.message === EXPECTED_PERMISSION_DENIAL_CONSOLE_MESSAGE
      && request?.method === receipt.method
      && request?.resourceType === receipt.resourceType
      && request?.xRequestId === receipt.xRequestId
      && response?.xRequestId === receipt.xRequestId
      && response?.requestTupleSha256 === receipt.requestTupleSha256
      && response?.status === receipt.status
      && response?.resourceType === receipt.resourceType
      && response?.mimeType === receipt.mimeType
      && response?.expected === true
      && entry.type === 'error'
      && entry.source === 'network'
      && entry.networkRequestId === receipt.requestId
      && entry.url === receipt.url
      && entry.message === receipt.message
    ) {
      consumed.add(receipt.requestId)
      return {
        ...result,
        expected: true,
        expectedReason: 'EXPECTED_PERMISSION_DENIAL_CONSOLE',
        expectedReceipt: { ...receipt },
      }
    }
    return result
  })
}

function unexpectedConsoleEntry(entry) {
  const result = { ...entry, expected: false }
  delete result.expectedReason
  delete result.expectedReceipt
  return result
}

function unexpectedNetworkFailure(failure) {
  const result = { ...failure, expected: false }
  delete result.expectedReason
  delete result.expectedReceipt
  return result
}

function exactCanceledFetchExchange(diagnostics, failure) {
  if (
    failure === null
    || typeof failure !== 'object'
    || typeof failure.requestId !== 'string'
    || typeof failure.url !== 'string'
    || failure.canceled !== true
    || failure.errorText !== 'net::ERR_ABORTED'
  ) {
    return null
  }
  const requests = diagnostics.requests.filter((request) => (
    request.requestId === failure.requestId
    && request.url === failure.url
    && request.method === 'GET'
    && request.resourceType === 'Fetch'
  ))
  const responses = diagnostics.responses.filter((response) => (
    response.requestId === failure.requestId
    && response.url === failure.url
    && response.status === 200
    && response.resourceType === 'Fetch'
  ))
  return requests.length === 1 && responses.length === 1
    ? { request: requests[0], response: responses[0] }
    : null
}

function canonicalNetworkUuidSet(values, label) {
  if (
    !Array.isArray(values)
    || values.some((value) => (
      typeof value !== 'string'
      || !canonicalUuidPattern().test(value)
    ))
    || new Set(values).size !== values.length
  ) {
    throw new Error(`Canceled network ${label} are invalid`)
  }
  return new Set(values)
}

function uniqueNetworkReceiptMap(receipts, label) {
  const result = new Map()
  for (const receipt of receipts) {
    if (
      receipt === null
      || typeof receipt !== 'object'
      || Array.isArray(receipt)
      || typeof receipt.requestId !== 'string'
      || receipt.requestId.length === 0
      || result.has(receipt.requestId)
    ) {
      throw new Error(`Canceled network ${label} are invalid`)
    }
    result.set(receipt.requestId, receipt)
  }
  return result
}

function uniqueCompletedPrintReceiptMap(receipts) {
  const result = new Map()
  for (const receipt of receipts) {
    if (
      receipt === null
      || typeof receipt !== 'object'
      || Array.isArray(receipt)
      || canonicalJson(Object.keys(receipt).sort()) !== canonicalJson([
        'bridgeAt',
        'bridgeSequence',
        'bridgeTitle',
        'reportId',
        'requestId',
        'requestTupleSha256',
        'runId',
        'uiStatus',
        'xRequestId',
      ])
      || typeof receipt.requestId !== 'string'
      || receipt.requestId.length === 0
      || !canonicalUuidPattern().test(receipt.runId)
      || !canonicalUuidPattern().test(receipt.reportId)
      || !canonicalUuidPattern().test(receipt.xRequestId)
      || typeof receipt.requestTupleSha256 !== 'string'
      || !/^[0-9a-f]{64}$/u.test(receipt.requestTupleSha256)
      || !Number.isSafeInteger(receipt.bridgeSequence)
      || receipt.bridgeSequence <= 0
      || receipt.bridgeTitle !== 'Trading Lab raw report'
      || typeof receipt.bridgeAt !== 'string'
      || Number.isNaN(Date.parse(receipt.bridgeAt))
      || new Date(receipt.bridgeAt).toISOString() !== receipt.bridgeAt
      || receipt.uiStatus !== '打印流程已结束'
      || result.has(receipt.requestId)
    ) {
      throw new Error(
        'Canceled network completed print receipts are invalid',
      )
    }
    result.set(receipt.requestId, receipt)
  }
  return result
}

function assertNoUnexplainedDiagnostics(diagnostics) {
  const consoleErrors = diagnostics.console.filter((entry) => (
    entry.expected !== true
    && (
      entry.type === 'error'
      || entry.type === 'assert'
      || entry.message.includes('__TRADING_LAB_UNHANDLED_REJECTION__')
    )
  ))
  const failedRequests = diagnostics.failures.filter((entry) => !entry.expected)
  const badResponses = diagnostics.responses.filter((entry) => (
    entry.status >= 400
    && !entry.expected
    && !(
      entry.status === 404
      && new URL(entry.url).pathname === '/favicon.ico'
    )
  ))
  assert.deepEqual(
    consoleErrors,
    [],
    `Unexplained console errors: ${consoleErrors
      .map((entry) => entry.message)
      .join(' | ')}`,
  )
  assert.deepEqual(
    failedRequests,
    [],
    `Unexplained failed requests: ${failedRequests
      .map((entry) => `${entry.url} ${entry.errorText}`)
      .join(' | ')}`,
  )
  assert.deepEqual(
    badResponses,
    [],
    `Unexplained network 4xx/5xx: ${badResponses
      .map((entry) => `${entry.status} ${entry.url}`)
      .join(' | ')}`,
  )
}

function expectedHttpResponse(expectations, rawUrl, status, resourceType) {
  const now = Date.now()
  let pathname = ''
  try {
    pathname = new URL(rawUrl).pathname
  } catch {
    return false
  }
  const match = expectations.find((entry) => (
    entry.navigationOnly !== true
    && entry.expiresAt >= now
    && entry.observed === 0
    && entry.pathname === pathname
    && entry.statuses.has(status)
  ))
  if (match !== undefined) {
    match.observed += 1
    return true
  }
  return (
    resourceType === 'Document'
    && expectations.some((entry) => (
      entry.navigationOnly === true && entry.expiresAt >= now
    ))
  )
}

async function persistEvidence(context) {
  const diagnostics = context.page?.diagnostics ?? {
    console: [],
    exceptions: [],
    requests: [],
    responses: [],
    failures: [],
    downloads: [],
    dialogs: [],
    businessResponseOverflow: false,
    ownershipRequestOverflow: false,
    ownershipResponseOverflow: false,
  }
  await Promise.all([
    writeJsonExclusive(
      join(context.artifacts.root, 'network-trace.json'),
      {
        requests: diagnostics.requests,
        responses: diagnostics.responses,
        failures: diagnostics.failures,
        businessResponseOverflow: diagnostics.businessResponseOverflow,
        ownershipRequestOverflow: diagnostics.ownershipRequestOverflow,
        ownershipResponseOverflow: diagnostics.ownershipResponseOverflow,
      },
    ),
    writeJsonExclusive(
      join(context.artifacts.root, 'console.json'),
      {
        console: diagnostics.console,
        consoleErrorReceipts: context.consoleErrorReceipts,
        exceptions: diagnostics.exceptions,
        dialogs: diagnostics.dialogs,
      },
    ),
    writeJsonExclusive(
      join(context.artifacts.root, 'downloads.json'),
      diagnostics.downloads,
    ),
  ])
}

async function persistProcessLogs(context) {
  await Promise.all(context.ownedProcesses.map(async (record, index) => {
    const path = join(
      context.artifacts.logs,
      `${String(index + 1).padStart(2, '0')}-${safeFileName(record.kind)}.log`,
    )
    await writeFile(
      path,
      `${record.commandLine}\n${redactText(record.output)}\n`,
      { encoding: 'utf8', flag: 'wx' },
    )
  }))
}

function summaryFor(context, startedAt, { failClosed = true } = {}) {
  const diagnostics = context.page?.diagnostics ?? {
    requests: [],
    responses: [],
    downloads: [],
  }
  let ownership
  let ownershipError = null
  try {
    if (diagnostics.businessResponseOverflow === true) {
      throw new Error(
        'Canonical ownership Trading Lab response evidence overflowed',
      )
    }
    if (diagnostics.ownershipResponseOverflow === true) {
      throw new Error(
        'Canonical ownership request-log response evidence overflowed',
      )
    }
    if (diagnostics.ownershipRequestOverflow === true) {
      throw new Error(
        'Canonical ownership request-log request evidence overflowed',
      )
    }
    const requestOnlyObservations =
      buildResponseLessRequestLogObservations({
        requests: diagnostics.requests,
        responses: diagnostics.responses,
      })
    ownership = requireTerminalCreatedRunOwnership(buildCanonicalOwnership({
      createdRuns: context.createdRuns,
      referencedRunIds: context.referencedRunIds,
      requestLogObservations: [
        ...context.requestLogObservations,
        ...requestOnlyObservations,
      ],
      responses: diagnostics.responses,
    }), context.status)
  } catch (error) {
    if (failClosed) throw error
    ownership = {
      createdRuns: [],
      referencedRunIds: [],
      tradingLabRequestIds: [],
      requestLogObservations: [],
    }
    ownershipError = serializeError(asError(error))
  }
  const reportPaths = [...new Set(
    diagnostics.downloads
      .filter((download) => (
        download?.state === 'completed'
        && typeof download.path === 'string'
        && download.path.length > 0
      ))
      .map(({ path }) => path),
  )]
  return redactValue({
    schemaVersion: 1,
    status: context.status,
    startedAt,
    finishedAt: new Date().toISOString(),
    viewport: {
      width: VIEWPORT_WIDTH,
      height: VIEWPORT_HEIGHT,
    },
    environment: context.environment,
    browser: context.browser === null
      ? null
      : {
          executable: context.browser.executable,
          devtoolsPort: context.browser.port,
          pid: context.browser.child.pid,
          downloadMode: context.downloadMode,
        },
    accounts: {
      full: 'TRADING_LAB_VIEW + TRADING_LAB_EXECUTE + SUPER_ADMIN',
      viewOnly: 'TRADING_LAB_VIEW',
      execute: 'TRADING_LAB_VIEW + TRADING_LAB_EXECUTE',
      ordinary: 'ROLE_ADMIN',
    },
    ownedProcesses: context.ownedProcesses.map((record) => ({
      kind: record.kind,
      pid: record.pid,
      commandLine: record.commandLine,
      startedAt: record.startedAt,
      stopped: record.stopped ?? false,
    })),
    journeys: context.journeys,
    screenshots: context.screenshots,
    networkAbortReceipts: {
      sseClose: context.sseCloseReceipts,
      reportDownload: context.downloadReceipts,
      reportPrint: context.printCompletionReceipts,
    },
    consoleErrorReceipts: context.consoleErrorReceipts,
    artifacts: context.artifacts?.root ?? null,
    reportPaths,
    ownership,
    ownershipError,
    error: context.error,
  })
}

async function stopOwnedProcesses(ownedProcesses) {
  const failures = []
  for (const record of [...ownedProcesses].reverse()) {
    try {
      if (record.owned !== true || record.child === undefined) {
        throw new Error('Refusing to stop an unowned process')
      }
      await stopOwnedProcess(record)
    } catch (error) {
      failures.push(asError(error))
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, 'Owned process cleanup failed')
  }
}

async function stopOwnedProcess(record) {
  const ownsListener = record.ownedPort !== undefined
    && await isTcpPortOpen(record.ownedPort)
  if (
    (record.child.exitCode !== null || record.child.signalCode !== null)
    && !ownsListener
  ) {
    record.stopped = true
    return
  }
  if (record.child.pid !== record.pid) {
    throw new Error('Refusing to stop an unowned process: PID identity changed')
  }
  if (
    record.child.exitCode === null
    && record.child.signalCode === null
    && process.platform === 'win32'
  ) {
    spawnSync(
      'taskkill.exe',
      ['/PID', String(record.pid), '/T', '/F'],
      { stdio: 'ignore', windowsHide: true },
    )
  } else if (
    record.child.exitCode === null
    && record.child.signalCode === null
  ) {
    record.child.kill('SIGTERM')
  }
  await waitFor(
    async () => (
      (
        record.ownedPort !== undefined
        && !(await isTcpPortOpen(record.ownedPort))
      )
      || (
        record.ownedPort === undefined
        && (
          record.child.exitCode !== null
          || record.child.signalCode !== null
        )
      )
    ),
    `owned ${record.kind} process exit`,
    5_000,
  ).catch(() => undefined)
  if (record.ownedPort !== undefined && await isTcpPortOpen(record.ownedPort)) {
    if (process.platform !== 'win32') {
      throw new Error(
        `Owned ${record.kind} listener survived its launcher and cannot be safely identified`,
      )
    }
    const currentOwner = windowsPortOwnerPid(record.ownedPort)
    if (
      currentOwner === null
      || currentOwner !== record.listenerOwnerPid
    ) {
      throw new Error(
        'Refusing to stop an unowned process: listener owner changed',
      )
    }
    spawnSync(
      'taskkill.exe',
      ['/PID', String(currentOwner), '/T', '/F'],
      { stdio: 'ignore', windowsHide: true },
    )
    await waitFor(
      () => isTcpPortOpen(record.ownedPort).then((open) => !open),
      `owned ${record.kind} listener shutdown`,
      10_000,
    )
  }
  record.stopped = true
}

async function removeOwnedBrowserProfile(path) {
  const resolved = resolve(path)
  if (
    dirname(resolved) !== resolve(tmpdir())
    || !basename(resolved).startsWith('fx-trading-lab-chrome-')
    || lstatSync(resolved).isSymbolicLink()
  ) {
    throw new Error('Refusing to remove an unowned browser profile')
  }
  await rm(resolved, { recursive: true, force: false })
}

function windowsPortOwnerPid(port) {
  const result = spawnSync('netstat.exe', ['-ano', '-p', 'TCP'], {
    encoding: 'utf8',
    windowsHide: true,
  })
  if (result.status !== 0) return null
  const suffix = `:${port}`
  for (const line of result.stdout.split(/\r?\n/u)) {
    const columns = line.trim().split(/\s+/u)
    if (
      columns.length >= 5
      && columns[0] === 'TCP'
      && columns[1].endsWith(suffix)
      && columns[3] === 'LISTENING'
    ) {
      const pid = Number(columns[4])
      return Number.isSafeInteger(pid) && pid > 0 ? pid : null
    }
  }
  return null
}

async function freePort() {
  return new Promise((resolvePort, rejectPort) => {
    const server = net.createServer()
    server.once('error', rejectPort)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (typeof address !== 'object' || address === null) {
        server.close()
        rejectPort(new Error('Could not reserve a DevTools port'))
        return
      }
      server.close(() => resolvePort(address.port))
    })
  })
}

async function isTcpPortOpen(port) {
  return new Promise((resolveOpen) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    const finish = (value) => {
      socket.removeAllListeners()
      socket.destroy()
      resolveOpen(value)
    }
    socket.setTimeout(500)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function waitFor(probe, label, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let lastError = null
  while (Date.now() <= deadline) {
    try {
      if (await probe()) return
      lastError = null
    } catch (error) {
      lastError = asError(error)
    }
    await sleep(100)
  }
  throw new Error(
    `Timed out waiting for ${label}`,
    lastError === null ? undefined : { cause: lastError },
  )
}

async function waitForValue(probe, label, timeoutMs) {
  let result = null
  await waitFor(async () => {
    result = await probe()
    return result !== null && result !== undefined
  }, label, timeoutMs)
  return result
}

function sleep(milliseconds) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds))
}

function safeNetworkUrl(value) {
  try {
    const url = new URL(value)
    url.username = ''
    url.password = ''
    url.search = ''
    url.hash = ''
    return url.toString()
  } catch {
    return String(value).slice(0, 1_000)
  }
}

function networkPathname(value) {
  try {
    return new URL(value).pathname
  } catch {
    return null
  }
}

function responseHeaderValue(headers, expectedName) {
  if (headers === null || typeof headers !== 'object') return null
  const match = Object.entries(headers).find(
    ([name]) => name.toLowerCase() === expectedName.toLowerCase(),
  )
  return match === undefined ? null : String(match[1])
}

export function isTradingLabBusinessRequest(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    return false
  }
  const tradingLabPath = (
    url.pathname === '/api/admin/trading-lab'
    || url.pathname.startsWith('/api/admin/trading-lab/')
  )
  const directValidationPort = (
    ['127.0.0.1', 'localhost'].includes(url.hostname)
    && ['18087', '18088'].includes(url.port)
  )
  return tradingLabPath || directValidationPort
}

export function buildResponseLessRequestLogObservations(input) {
  if (
    input === null
    || typeof input !== 'object'
    || Array.isArray(input)
    || canonicalJson(Object.keys(input).sort())
      !== canonicalJson(['requests', 'responses'])
    || !Array.isArray(input.requests)
    || !Array.isArray(input.responses)
  ) {
    throw new Error('Response-less request-log evidence is invalid')
  }
  const respondedCdpRequestIds = new Set(
    input.responses
      .map((response) => response?.requestId)
      .filter((requestId) => typeof requestId === 'string'),
  )
  const observations = []
  const observedRequestIds = new Set()
  for (const request of input.requests) {
    if (
      request === null
      || typeof request !== 'object'
      || respondedCdpRequestIds.has(request.requestId)
      || !isMainRequestLogResponse(request.url)
      || typeof request.xRequestId !== 'string'
      || !canonicalUuidPattern().test(request.xRequestId)
    ) {
      continue
    }
    if (observedRequestIds.has(request.xRequestId)) {
      throw new Error(
        'Response-less request-log evidence has a duplicate request UUID',
      )
    }
    const url = new URL(request.url)
    observations.push(buildRequestLogRequestObservation({
      requestId: request.xRequestId,
      method: request.method,
      path: `${url.pathname}${url.search}`,
    }))
    observedRequestIds.add(request.xRequestId)
  }
  return observations
}

export function upsertCreatedRunOwnership(createdRuns, candidate) {
  if (!Array.isArray(createdRuns)) {
    throw new Error('Created Run ownership collection is invalid')
  }
  const normalized = buildCanonicalOwnership({
    createdRuns,
    referencedRunIds: [],
    requestLogObservations: [],
    responses: [],
  }).createdRuns
  const replacement = buildCanonicalOwnership({
    createdRuns: [candidate],
    referencedRunIds: [],
    requestLogObservations: [],
    responses: [],
  }).createdRuns[0]
  const existingIndex = normalized
    .findIndex((root) => root.runId === replacement.runId)
  if (existingIndex < 0) {
    return buildCanonicalOwnership({
      createdRuns: [...normalized, replacement],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [],
    }).createdRuns
  }

  const existing = normalized[existingIndex]
  if (
    existing.scenarioId !== replacement.scenarioId
    || existing.reportId !== replacement.reportId
  ) {
    throw new Error('Created Run ownership identity is inconsistent')
  }
  if (
    Object.hasOwn(existing, 'expectedTerminalState')
    && Object.hasOwn(replacement, 'provisional')
  ) {
    return normalized
  }
  if (
    Object.hasOwn(existing, 'expectedTerminalState')
    && Object.hasOwn(replacement, 'expectedTerminalState')
    && existing.expectedTerminalState !== replacement.expectedTerminalState
  ) {
    throw new Error('Created Run ownership terminal state is inconsistent')
  }
  return normalized.map(
    (root, index) => index === existingIndex ? replacement : root,
  )
}

export function requireTerminalCreatedRunOwnership(ownership, status) {
  if (status !== 'PASS') {
    return ownership
  }
  if (
    ownership === null
    || typeof ownership !== 'object'
    || !Array.isArray(ownership.createdRuns)
    || ownership.createdRuns.length === 0
    || ownership.createdRuns.some((root) => (
      root === null
      || typeof root !== 'object'
      || Array.isArray(root)
      || Object.hasOwn(root, 'provisional')
      || !Object.hasOwn(root, 'expectedTerminalState')
      || !TERMINAL_STATES.has(root.expectedTerminalState)
    ))
  ) {
    throw new Error(
      'Trading Lab PASS ownership requires every created Run to be terminal',
    )
  }
  return ownership
}

export function buildCanonicalOwnership(input = {}) {
  if (
    input === null
    || typeof input !== 'object'
    || Array.isArray(input)
    || canonicalJson(Object.keys(input).sort())
      !== canonicalJson([
        'createdRuns',
        'referencedRunIds',
        'requestLogObservations',
        'responses',
      ])
  ) {
    throw new Error('Canonical ownership input keys are invalid')
  }
  const {
    createdRuns,
    referencedRunIds,
    requestLogObservations,
    responses,
  } = input
  for (const [label, value] of [
    ['createdRuns', createdRuns],
    ['referencedRunIds', referencedRunIds],
    ['requestLogObservations', requestLogObservations],
    ['responses', responses],
  ]) {
    if (!Array.isArray(value)) {
      throw new Error(`Canonical ownership ${label} must be an array`)
    }
  }

  const allOwnedUuids = new Set()
  const normalizedRuns = createdRuns.map((root, index) => {
    if (root === null || typeof root !== 'object' || Array.isArray(root)) {
      throw new Error(`Canonical ownership createdRuns[${index}] is invalid`)
    }
    const keys = Object.keys(root).sort()
    const terminal = (
      canonicalJson(keys)
      === canonicalJson([
        'expectedTerminalState',
        'reportId',
        'runId',
        'scenarioId',
      ])
    )
    const provisional = (
      canonicalJson(keys)
      === canonicalJson([
        'provisional',
        'reportId',
        'runId',
        'scenarioId',
      ])
      && root.provisional === true
    )
    if (!terminal && !provisional) {
      throw new Error(`Canonical ownership createdRuns[${index}] is invalid`)
    }
    const normalized = {
      scenarioId: requireCanonicalOwnershipUuid(
        root.scenarioId,
        `createdRuns[${index}].scenarioId`,
      ),
      runId: requireCanonicalOwnershipUuid(
        root.runId,
        `createdRuns[${index}].runId`,
      ),
      reportId: requireCanonicalOwnershipUuid(
        root.reportId,
        `createdRuns[${index}].reportId`,
      ),
      ...(terminal
        ? { expectedTerminalState: root.expectedTerminalState }
        : { provisional: true }),
    }
    if (
      terminal
      && !TERMINAL_STATES.has(normalized.expectedTerminalState)
    ) {
      throw new Error(
        `Canonical ownership createdRuns[${index}] terminal state is invalid`,
      )
    }
    for (const id of [
      normalized.scenarioId,
      normalized.runId,
      normalized.reportId,
    ]) {
      addUniqueOwnershipUuid(allOwnedUuids, id)
    }
    return normalized
  })

  const normalizedReferences = []
  const referenced = new Set()
  for (const [index, rawRunId] of referencedRunIds.entries()) {
    const runId = requireCanonicalOwnershipUuid(
      rawRunId,
      `referencedRunIds[${index}]`,
    )
    if (referenced.has(runId)) continue
    addUniqueOwnershipUuid(allOwnedUuids, runId)
    referenced.add(runId)
    normalizedReferences.push(runId)
  }

  const tradingLabRequestIds = []
  const observedTradingLabRequests = new Set()
  const observedRequestLogs = new Set()
  const normalizedRequestLogObservations = []
  for (const [index, observation] of requestLogObservations.entries()) {
    const normalized = normalizeRequestLogObservation(
      observation,
      `requestLogObservations[${index}]`,
    )
    if (observedRequestLogs.has(normalized.requestId)) {
      throw new Error('Canonical ownership has a duplicate request-log UUID')
    }
    if (allOwnedUuids.has(normalized.requestId)) {
      throw new Error('Canonical ownership has a duplicate UUID')
    }
    observedRequestLogs.add(normalized.requestId)
    normalizedRequestLogObservations.push(normalized)
  }
  for (const [index, response] of responses.entries()) {
    const tradingLab = (
      response !== null
      && typeof response === 'object'
      && !Array.isArray(response)
      && isTradingLabBusinessRequest(response.url)
    )
    const requestLog = (
      response !== null
      && typeof response === 'object'
      && !Array.isArray(response)
      && isMainRequestLogResponse(response.url)
    )
    if (
      !tradingLab
      && !requestLog
    ) {
      continue
    }
    const requestId = requireCanonicalOwnershipUuid(
      response.xRequestId,
      `responses[${index}] X-Request-Id`,
    )
    if (allOwnedUuids.has(requestId)) {
      throw new Error('Canonical ownership has a duplicate UUID')
    }
    if (tradingLab && !observedTradingLabRequests.has(requestId)) {
      observedTradingLabRequests.add(requestId)
      tradingLabRequestIds.push(requestId)
    }
    if (requestLog) {
      if (observedRequestLogs.has(requestId)) {
        throw new Error('Canonical ownership has a duplicate request-log UUID')
      }
      const observation = normalizeRequestLogObservation({
        requestId,
        requestTupleSha256: response.requestTupleSha256,
      }, `responses[${index}] request-log observation`)
      observedRequestLogs.add(requestId)
      normalizedRequestLogObservations.push(observation)
    }
  }

  return {
    createdRuns: normalizedRuns,
    referencedRunIds: normalizedReferences,
    tradingLabRequestIds,
    requestLogObservations: normalizedRequestLogObservations,
  }
}

export function buildRequestLogObservation(input) {
  if (
    input === null
    || typeof input !== 'object'
    || Array.isArray(input)
    || canonicalJson(Object.keys(input).sort())
      !== canonicalJson(['method', 'path', 'requestId', 'statusCode'])
  ) {
    throw new Error('Request-log observation keys are invalid')
  }
  return Object.freeze({
    requestId: requireCanonicalOwnershipUuid(
      input.requestId,
      'request-log observation request ID',
    ),
    requestTupleSha256: requestLogTupleSha256(
      input.method,
      input.path,
      input.statusCode,
    ),
  })
}

export function buildRequestLogRequestObservation(input) {
  if (
    input === null
    || typeof input !== 'object'
    || Array.isArray(input)
    || canonicalJson(Object.keys(input).sort())
      !== canonicalJson(['method', 'path', 'requestId'])
  ) {
    throw new Error('Request-log request observation keys are invalid')
  }
  if (
    typeof input.method !== 'string'
    || !/^[A-Z]+$/u.test(input.method)
    || typeof input.path !== 'string'
    || !input.path.startsWith('/')
    || /[\r\n]/u.test(input.path)
  ) {
    throw new Error('Request-log request method or path is invalid')
  }
  return Object.freeze({
    requestId: requireCanonicalOwnershipUuid(
      input.requestId,
      'request-log request observation request ID',
    ),
    requestMethodSha256: createHash('sha256')
      .update(input.method)
      .digest('hex'),
    requestPathSha256: createHash('sha256')
      .update(input.path)
      .digest('hex'),
  })
}

function normalizeRequestLogObservation(value, label) {
  const keys = (
    value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
  ) ? Object.keys(value).sort() : []
  const responseObserved = (
    canonicalJson(keys)
    === canonicalJson(['requestId', 'requestTupleSha256'])
  )
  const requestOnly = (
    canonicalJson(keys)
    === canonicalJson([
      'requestId',
      'requestMethodSha256',
      'requestPathSha256',
    ])
  )
  if (!responseObserved && !requestOnly) {
    throw new Error(`Canonical ownership ${label} is invalid`)
  }
  const normalized = {
    requestId: requireCanonicalOwnershipUuid(
      value.requestId,
      `${label} request ID`,
    ),
    ...(responseObserved
      ? { requestTupleSha256: value.requestTupleSha256 }
      : {
          requestMethodSha256: value.requestMethodSha256,
          requestPathSha256: value.requestPathSha256,
        }),
  }
  if (
    Object.values(normalized)
      .slice(1)
      .some((hash) => (
        typeof hash !== 'string'
        || !/^[0-9a-f]{64}$/u.test(hash)
      ))
  ) {
    throw new Error(`Canonical ownership ${label} is invalid`)
  }
  return Object.freeze(normalized)
}

function requestLogTupleSha256(method, path, statusCode) {
  if (
    typeof method !== 'string'
    || !/^[A-Z]+$/u.test(method)
    || typeof path !== 'string'
    || !path.startsWith('/')
    || /[\r\n]/u.test(path)
    || !Number.isInteger(statusCode)
    || statusCode < 100
    || statusCode > 599
  ) {
    throw new Error('Request-log observation method path or status is invalid')
  }
  return createHash('sha256')
    .update(canonicalJson({ method, path, statusCode }))
    .digest('hex')
}

function observedRequestTupleSha256(method, rawUrl, statusCode) {
  try {
    const url = new URL(rawUrl)
    return requestLogTupleSha256(
      method,
      `${url.pathname}${url.search}`,
      statusCode,
    )
  } catch {
    return null
  }
}

function isMainRequestLogResponse(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    return false
  }
  if (
    !['127.0.0.1', 'localhost'].includes(url.hostname)
    || ['18087', '18088'].includes(url.port)
  ) {
    return false
  }
  return (
    url.pathname === '/api/auth/login'
    || url.pathname === '/api/admin/dashboard/summary'
    || url.pathname === '/api/admin/trading-lab'
    || url.pathname.startsWith('/api/admin/trading-lab/')
  )
}

function requireCanonicalOwnershipUuid(value, label) {
  if (typeof value !== 'string' || !canonicalUuidPattern().test(value)) {
    throw new Error(`Canonical ownership ${label} must be a canonical UUID`)
  }
  return value
}

function addUniqueOwnershipUuid(collection, value) {
  if (collection.has(value)) {
    throw new Error('Canonical ownership has a duplicate UUID')
  }
  collection.add(value)
}

function safeFileName(value) {
  return String(value)
    .replace(/[^a-zA-Z0-9._-]+/gu, '-')
    .replace(/^-+|-+$/gu, '')
    .slice(0, 120) || 'artifact'
}

function canonicalUuidPattern() {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u
}

function pushBounded(collection, row) {
  if (collection.length < MAX_DIAGNOSTIC_ROWS) collection.push(row)
}

async function writeJsonExclusive(path, value) {
  await writeFile(
    path,
    `${JSON.stringify(redactValue(value), null, 2)}\n`,
    { encoding: 'utf8', flag: 'wx' },
  )
}

function redactValue(value) {
  return JSON.parse(redactText(JSON.stringify(value)))
}

function redactText(value) {
  return String(value)
    .replace(/\bBearer\s+[A-Za-z0-9._~-]+/giu, 'Bearer [REDACTED]')
    .replace(
      /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/gu,
      '[REDACTED_JWT]',
    )
}

function serializeError(error) {
  return {
    name: error.name,
    message: redactText(error.message),
    stack: redactText(error.stack ?? ''),
  }
}

function asError(value) {
  return value instanceof Error ? value : new Error(String(value))
}

function combineErrors(first, next, label) {
  if (first === null) return next
  return new AggregateError([first, next], `${label} failed after primary error`)
}

function isMainModule() {
  if (process.argv[1] === undefined) return false
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
}

if (isMainModule()) {
  main().then(
    (summary) => {
      process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`)
    },
    (error) => {
      process.stderr.write(`${redactText(asError(error).stack ?? error)}\n`)
      process.exitCode = 1
    },
  )
}
