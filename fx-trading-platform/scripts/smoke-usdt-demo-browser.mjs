import { createHash, randomUUID } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { AsyncLocalStorage } from 'node:async_hooks'
import {
  closeSync,
  existsSync,
  fstatSync,
  linkSync,
  lstatSync,
  openSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync
} from 'node:fs'
import { link, lstat, mkdir, mkdtemp, open, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises'
import { createConnection } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, delimiter, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  aggregateReport,
  loadOrCreateRunState,
  planResume,
  redactNetworkEntry,
  writeCaseResultAtomic
} from './p0-user-trading-artifacts.mjs'
import {
  P0_CASES,
  P0_REGISTRY_FINGERPRINT,
  parseP0Cli,
  planP0Execution,
  registryFingerprint as calculateRegistryFingerprint,
  resolveP0RunRoot,
  runCase
} from './p0-user-trading-cases.mjs'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const P0_COMPOSE_PROJECT = 'infra'
const P0_COMPOSE_OVERRIDE_TEXT = [
  'services:',
  '  postgres:',
  '    ports: !override',
  '      - "127.0.0.1:5432:5432"',
  '  redis:',
  '    ports: !override',
  '      - "127.0.0.1:6379:6379"',
  ''
].join('\n')
const P0_DATABASE_PATTERN = /^fx_p0_user_e2e_[a-z0-9_]+$/
const P0_WINDOWS_JOB_OBJECT_CAPABILITY = 'WINDOWS_JOB_OBJECT_V1'
const P0_HOST_PLATFORM = process.platform
const defaultP0DependencyInstances = new WeakSet()
let apiBaseUrl
let webBaseUrl
let adminBaseUrl
let runId
let userEmail
let userPassword
let adminEmail
let adminPassword

export function assertP0ProcessTreeCapability() {
  if (P0_HOST_PLATFORM !== 'win32') return null
  throw new Error('P0_WINDOWS_JOB_OBJECT_REQUIRED')
}

function requireP0CleanupProcessTreeProvider(processTreeProvider) {
  if (processTreeProvider?.capability !== P0_WINDOWS_JOB_OBJECT_CAPABILITY
    || processTreeProvider.platform !== 'win32'
    || processTreeProvider.verification !== 'INDEPENDENTLY_VERIFIED'
    || typeof processTreeProvider.acquireNativeProcessHandle !== 'function') {
    throw new Error('P0_WINDOWS_JOB_OBJECT_REQUIRED')
  }
  return processTreeProvider
}

export function resolveCanonicalSmokeOwnership(inheritedEnv = process.env) {
  const inheritedToken = inheritedEnv.P0_RUN_OWNER_TOKEN
  const inheritedDatabase = inheritedEnv.USDT_DEMO_SMOKE_DATABASE
  if (Boolean(inheritedToken) !== Boolean(inheritedDatabase)) {
    throw new Error('P0_CANONICAL_OWNERSHIP_INCOMPLETE')
  }
  if (inheritedToken && inheritedDatabase) {
    assertP0DatabaseName(inheritedDatabase)
    return {
      database: inheritedDatabase,
      ownerToken: inheritedToken,
      ownerId: ownerIdForToken(inheritedToken),
      inherited: true
    }
  }
  const ownerToken = `${randomUUID()}${randomUUID()}`
  return {
    database: `fx_p0_user_e2e_canonical_1_${randomUUID().replaceAll('-', '').slice(0, 12)}`,
    ownerToken,
    ownerId: ownerIdForToken(ownerToken),
    inherited: false
  }
}

let canonicalSmokeOwnership
let canonicalExpectedComposeIdentity
let canonicalComposeTarget
let canonicalComposeIdentity
let canonicalPostgres
let canonicalRedis
let smokeDatabase
let artifactRoot
let screenshotsDir
let logsDir

const INITIAL_SPOT_USDT = 50000
const INITIAL_PERP_USDT = 50000
const SPOT_SYMBOL = 'BTCUSDT'
const PERP_SYMBOL = 'BTCUSDT-PERP'
const SETTINGS_SYMBOL = 'ETHUSDT-PERP'
const LIQUIDATION_SYMBOL = 'SOLUSDT-PERP'
const FALLBACK_FUNDING_SYMBOL = 'BNBUSDT-PERP'
const P0_SPOT_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT']
const P0_PERP_SYMBOLS = ['BTCUSDT-PERP', 'ETHUSDT-PERP', 'BNBUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP']
const P0_DEFAULT_REDIS_KEYS = [...P0_SPOT_SYMBOLS, ...P0_PERP_SYMBOLS]
  .map((symbol) => `quote:${symbol}`)
const FORBIDDEN_PRODUCTS = ['FOREX', 'INVERSE_PERP', 'OPTION']
const SOURCE_METADATA_FIELDS = ['providerCode', 'providerSymbol', 'sourceMode', 'asOf', 'expiresAt', 'stale']
const TERMINAL_ORDER_STATUSES = new Set(['FILLED', 'CANCELED', 'CANCELLED', 'REJECTED', 'EXPIRED'])
const CANONICAL_ADMIN_AUTHORITIES = [
  'market:symbol:update',
  'trading:account:demo-reset',
  'trading:account:force-cleanup'
]
const CANONICAL_PROCESS_LOG_TAIL_BYTES = 200_000
const TRADE_PANEL_SELECTOR = '[data-platform-view="pc"] [data-panel-id="trade"]'

// Human-readable bootstrap evidence retained in the report: docker compose, not an in-memory substitute.
const STARTUP_COMMANDS = [
  'docker compose -f infra/docker-compose.yml up -d',
  'mvn spring-boot:run -Dspring-boot.run.profiles=dev',
  'npm --workspace apps/web run dev',
  'npm --workspace apps/admin run dev'
]

const SOURCE_MODES = [
  {
    id: 'BINANCE_PUBLIC',
    alias: 'binance-public',
    enabled: new Set(['binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp']),
    expectedSpot: ['binance', 'okx', 'local-spot'],
    expectedPerp: ['binance-usdm', 'okx-swap', 'local-perp']
  },
  {
    id: 'BINANCE_TO_OKX',
    alias: 'binance-to-okx',
    enabled: new Set(['okx', 'local-spot', 'okx-swap', 'local-perp']),
    expectedSpot: ['okx', 'local-spot'],
    expectedPerp: ['okx-swap', 'local-perp']
  },
  {
    id: 'LOCAL_SIMULATED',
    alias: 'external-to-local',
    enabled: new Set(['local-spot', 'local-perp']),
    expectedSpot: ['local-spot'],
    expectedPerp: ['local-perp']
  },
  {
    id: 'RECOVERY',
    alias: 'public-recovery',
    enabled: new Set(['binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp']),
    expectedSpot: ['binance', 'okx', 'local-spot'],
    expectedPerp: ['binance-usdm', 'okx-swap', 'local-perp']
  }
]

const VIEWPORTS = [
  { name: 'web-desktop', width: 1440, height: 900, surface: 'web', mobile: false },
  { name: 'web-mobile', width: 390, height: 844, surface: 'web', mobile: true },
  { name: 'admin-desktop', width: 1440, height: 900, surface: 'admin', mobile: false },
  { name: 'admin-mobile', width: 390, height: 844, surface: 'admin', mobile: true }
]

let results = []
let screenshots = []
let sourceEvidence = []
let processLogs = []
let managedProcesses = []
let localNativeProcessHandles = new Map()
let bindingRestores = []
let fundingConfigRestores = new Map()
let userToken
let adminToken
let adminRefreshToken
let adminAuthorities = []
let userId
let accountId
let browser
let accountEventObserver
let smokeDatabaseCreated = false
let standaloneCanonicalRedisOwnership
let interruptionError
let sourceModeStartedAtMs = 0
const shutdownController = new AbortController()

function initializeCanonicalRuntime(inheritedEnv = process.env) {
  apiBaseUrl = inheritedEnv.API_BASE_URL ?? 'http://127.0.0.1:18086'
  webBaseUrl = inheritedEnv.WEB_BASE_URL ?? 'http://127.0.0.1:5199'
  adminBaseUrl = inheritedEnv.ADMIN_BASE_URL ?? 'http://127.0.0.1:5200'
  runId = inheritedEnv.USDT_DEMO_SMOKE_RUN_ID ?? new Date().toISOString().replace(/[:.]/g, '-')
  userEmail = inheritedEnv.USDT_DEMO_SMOKE_EMAIL ?? `usdt-demo-browser+${runId}@example.com`
  userPassword = inheritedEnv.USDT_DEMO_SMOKE_PASSWORD ?? 'Password123!'
  adminEmail = inheritedEnv.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
  adminPassword = inheritedEnv.ADMIN_SMOKE_PASSWORD ?? 'Password123!'
  canonicalSmokeOwnership = resolveCanonicalSmokeOwnership(inheritedEnv)
  canonicalExpectedComposeIdentity = canonicalSmokeOwnership.inherited
    ? safeP0ComposeIdentity({
        project: inheritedEnv.P0_COMPOSE_PROJECT,
        postgres: {
          id: inheritedEnv.P0_POSTGRES_CONTAINER_ID,
          image: 'postgres:16',
          host: '127.0.0.1',
          hostPort: 5432
        },
        redis: {
          id: inheritedEnv.P0_REDIS_CONTAINER_ID,
          image: 'redis:7',
          host: '127.0.0.1',
          hostPort: 6379
        }
      })
    : undefined
  canonicalComposeIdentity = undefined
  canonicalPostgres = undefined
  canonicalRedis = undefined
  smokeDatabase = canonicalSmokeOwnership.database
  artifactRoot = resolve(
    inheritedEnv.USDT_DEMO_SMOKE_ARTIFACTS
      ?? join(projectRoot, 'artifacts', 'smoke-usdt-demo-browser', runId)
  )
  const composeArtifactBase = canonicalSmokeOwnership.inherited
    ? resolve(artifactRoot, '..', '..')
    : resolve(artifactRoot, '..')
  canonicalComposeTarget = createP0ComposeTarget({ artifactBase: composeArtifactBase })
  if (canonicalSmokeOwnership.inherited
    && (inheritedEnv.P0_COMPOSE_BASE_FILE !== canonicalComposeTarget.composeFiles[0]
      || inheritedEnv.P0_COMPOSE_OVERRIDE_FILE !== canonicalComposeTarget.composeFiles[1])) {
    throw new Error('P0_CANONICAL_COMPOSE_TARGET_MISMATCH')
  }
  screenshotsDir = join(artifactRoot, 'screenshots')
  logsDir = join(artifactRoot, 'logs')
  results = []
  screenshots = []
  sourceEvidence = []
  processLogs = []
  managedProcesses = []
  localNativeProcessHandles = new Map()
  bindingRestores = []
  fundingConfigRestores = new Map()
  userToken = undefined
  adminToken = undefined
  adminRefreshToken = undefined
  adminAuthorities = []
  userId = undefined
  accountId = undefined
  browser = undefined
  accountEventObserver = undefined
  smokeDatabaseCreated = false
  interruptionError = undefined
  sourceModeStartedAtMs = 0
  standaloneCanonicalRedisOwnership = undefined
}

export async function cleanupCanonicalOwnershipBarrier({
  stopManagedProcesses,
  assertOwnedPortsFree,
  dropOwnedDatabase,
  cleanupRedisOwnership
}) {
  if (typeof stopManagedProcesses !== 'function'
    || typeof assertOwnedPortsFree !== 'function'
    || typeof dropOwnedDatabase !== 'function'
    || typeof cleanupRedisOwnership !== 'function') {
    throw new Error('P0_CANONICAL_CLEANUP_BARRIER_INVALID')
  }
  await stopManagedProcesses()
  await assertOwnedPortsFree()
  await dropOwnedDatabase()
  return cleanupRedisOwnership()
}

export async function runCanonicalSmoke() {
  assertP0ProcessTreeCapability()
  initializeCanonicalRuntime()
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => {
      interruptionError ??= new Error(`Smoke interrupted by ${signal}; cleanup is required before exit`)
      if (!shutdownController.signal.aborted) shutdownController.abort(interruptionError)
    })
  }

  await mkdir(screenshotsDir, { recursive: true })
  await mkdir(logsDir, { recursive: true })

  let failure
  try {
    await step('start real PostgreSQL Redis backend Web and Admin services', startRealServices)
    await step('register/login and verify 50k Spot + 50k Perp', bootstrapIdentityAndAccount)
    await step('verify exactly 5 Spot + 5 Perp symbols and non-P0 products cannot trade', assertP0Catalog)
    await step('snapshot isolated provider binding fixture controls', snapshotProviderBindings)
    await step('LOCAL_SIMULATED remains tradable for deterministic full P0 journey', async () => {
      await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'LOCAL_SIMULATED'))
      return assertBundleSources('LOCAL_SIMULATED')
    })
    browser = await launchBrowser()
    await step('connect authenticated browser account-event observer', async () => {
      accountEventObserver = await startAccountEventObserver()
      return { destination: '/user/queue/trading-events', transport: 'real STOMP WebSocket' }
    })
    await step('complete Spot transfer Perpetual protection funding liquidation reset journey', runFullP0Journey)

    for (const mode of SOURCE_MODES) {
      await step(`source mode ${mode.id} executable REST loop`, async () => {
        if (mode.id === 'RECOVERY') {
          await applySourceMode(SOURCE_MODES.find((candidate) => candidate.id === 'LOCAL_SIMULATED'))
          await assertBundleSources('LOCAL_SIMULATED')
        } else {
          await applySourceMode(mode)
        }
        const evidence = await assertBundleSources(mode.id === 'RECOVERY' ? 'LOCAL_SIMULATED' : mode.id)
        if (mode.id !== 'RECOVERY') {
          await runMinimalSpotLoop(mode)
          await runMinimalPerpetualLoop(mode)
          await runBrowserMinimalTradingLoop(mode)
        }
        return evidence
      })

      for (const viewport of VIEWPORTS) {
        await step(`${mode.id} ${viewport.name} browser critical paths`, () => captureBrowserEvidence(mode, viewport))
      }
    }

    await step('REST and PostgreSQL state are mutually traceable', assertDatabaseState)
  } catch (error) {
    failure = error
  }

  for (const [label, cleanup] of [
    ['funding configuration restore', restoreFundingConfigs],
    ['provider binding restore', restoreProviderBindings],
    ['account-event observer shutdown', async () => { if (accountEventObserver) await accountEventObserver.close() }],
    ['browser shutdown', async () => { if (browser) await browser.close() }]
  ]) {
    try {
      await cleanup()
    } catch (error) {
      failure = appendFailure(failure, error, label)
    }
  }
  try {
    await cleanupCanonicalOwnershipBarrier({
      stopManagedProcesses,
      async assertOwnedPortsFree() {
        const signal = AbortSignal.timeout(30000)
        for (const port of [18086, 5199, 5200]) {
          if (await isPortOpen(port, signal)) {
            throw new Error(`P0_PORT_STILL_IN_USE: ${port}`)
          }
        }
      },
      dropOwnedDatabase: dropSmokeDatabase,
      async cleanupRedisOwnership() {
        if (standaloneCanonicalRedisOwnership) {
          return standaloneCanonicalRedisOwnership.cleanup()
        }
      }
    })
  } catch (error) {
    failure = appendFailure(failure, error, 'canonical ownership cleanup')
  }

  if (interruptionError && !failure) failure = interruptionError

  if (failure) {
    const report = await writeReport('FAIL', failure)
    console.error(JSON.stringify({
      status: 'FAIL',
      report,
      error: failure instanceof Error ? failure.message : String(failure)
    }, null, 2))
    process.exitCode = 1
  } else {
    const report = await writeReport('PASS')
    console.log(JSON.stringify({ status: 'PASS', report, screenshots: screenshots.length }, null, 2))
  }
}

async function startRealServices() {
  await startDockerInfrastructure()
  await ensureBackendServer()
  await ensureFrontendServer('web', webBaseUrl, '5199')
  await ensureFrontendServer('admin', adminBaseUrl, '5200')
  return { startupCommands: STARTUP_COMMANDS, apiBaseUrl, webBaseUrl, adminBaseUrl }
}

export async function startCanonicalInfrastructureBoundary({
  ownership,
  startCompose,
  waitForPostgres,
  prepareInherited
}) {
  if (typeof startCompose !== 'function'
    || typeof waitForPostgres !== 'function'
    || typeof prepareInherited !== 'function') {
    throw new Error('P0_CANONICAL_INFRASTRUCTURE_DEPENDENCY_INVALID')
  }
  let composeStarted = false
  if (ownership?.inherited !== true) {
    await startCompose()
    composeStarted = true
  }
  await waitForPostgres()
  if (ownership?.inherited === true) await prepareInherited()
  return { inherited: ownership?.inherited === true, composeStarted }
}

export async function startCanonicalDockerInfrastructure({
  ownership,
  inheritedEnv,
  composeFiles,
  expectedComposeIdentity,
  operations
}) {
  if (!Array.isArray(composeFiles) || composeFiles.length < 2
    || composeFiles.some((file) => typeof file !== 'string' || file.length === 0)
    || typeof operations?.inspectDockerDaemon !== 'function'
    || typeof operations.startCompose !== 'function'
    || typeof operations.verifyComposeContainers !== 'function'
    || typeof operations.waitForPostgres !== 'function') {
    throw new Error('P0_CANONICAL_INFRASTRUCTURE_DEPENDENCY_INVALID')
  }
  assertNoInheritedDockerTarget(inheritedEnv)
  const daemon = await operations.inspectDockerDaemon()
  assertLocalDockerEndpoint(daemon?.endpoint)
  let composeStarted = false
  if (ownership?.inherited !== true) {
    await operations.startCompose({ composeFiles, project: P0_COMPOSE_PROJECT })
    composeStarted = true
  }
  const composeIdentity = await operations.verifyComposeContainers({
    composeFiles,
    expectedProject: P0_COMPOSE_PROJECT
  })
  const verifiedIdentity = safeP0ComposeIdentity(composeIdentity)
  if (expectedComposeIdentity !== undefined
    && JSON.stringify(verifiedIdentity) !== JSON.stringify(
      safeP0ComposeIdentity(expectedComposeIdentity)
    )) {
    throw new Error('P0_CANONICAL_COMPOSE_IDENTITY_MISMATCH')
  }
  await operations.waitForPostgres(composeIdentity)
  if (ownership?.inherited === true) {
    if (typeof operations.prepareInherited !== 'function') {
      throw new Error('P0_CANONICAL_INFRASTRUCTURE_DEPENDENCY_INVALID')
    }
    await operations.prepareInherited(composeIdentity)
  }
  return {
    inherited: ownership?.inherited === true,
    composeStarted,
    composeIdentity
  }
}

async function startDockerInfrastructure() {
  const composeFile = canonicalComposeTarget.composeFiles[0]
  assertNoInheritedDockerTarget(process.env)
  const composeTarget = await ensureP0ComposeTarget({
    artifactBase: dirname(dirname(canonicalComposeTarget.composeFiles[1]))
  })
  if (JSON.stringify(composeTarget) !== JSON.stringify(canonicalComposeTarget)) {
    throw new Error('P0_CANONICAL_COMPOSE_TARGET_MISMATCH')
  }
  const composeFiles = composeTarget.composeFiles
  const commandEnvironment = scrubLocalLauncherEnvironment(process.env)
  const runCommand = (descriptor) => runLocalCommand({
    ...descriptor,
    env: { ...commandEnvironment, ...descriptor.env },
    shell: false,
    signal: shutdownController.signal
  })
  const infrastructure = createLocalInfrastructureAdapter(composeFile, runCommand)
  const verifyCanonicalRedisTransactionBinding = async (
    _expectedBinding,
    { signal = shutdownController.signal } = {}
  ) => {
    if (!canonicalComposeIdentity) throw new Error('P0_REDIS_TRANSACTION_VERIFIER_REQUIRED')
    const observedIdentity = await revalidateP0CleanupComposeIdentity({
      artifactBase: dirname(dirname(composeFiles[1])),
      manifest: {
        composeTarget: safeP0ComposeTarget(composeTarget),
        composeIdentity: safeP0ComposeIdentity(canonicalComposeIdentity)
      },
      infrastructure,
      signal
    })
    return observedIdentity.redis
  }
  const boundary = await startCanonicalDockerInfrastructure({
    ownership: canonicalSmokeOwnership,
    inheritedEnv: process.env,
    composeFiles,
    expectedComposeIdentity: canonicalExpectedComposeIdentity,
    operations: {
      inspectDockerDaemon: () => infrastructure.inspectDockerDaemon({
        signal: shutdownController.signal
      }),
      async startCompose() {
        const composeArguments = composeFiles.flatMap((file) => ['-f', file])
        const result = await runCommand({
          id: 'canonical-compose-up',
          command: 'docker',
          args: [
            'compose', '--project-name', P0_COMPOSE_PROJECT,
            ...composeArguments, 'up', '-d', '--pull', 'never'
          ],
          cwd: projectRoot
        })
        if (result?.status !== 0 || result.signal) {
          throw new Error('P0_CANONICAL_COMPOSE_START_FAILED')
        }
      },
      verifyComposeContainers: ({ composeFiles: files }) => (
        infrastructure.verifyComposeContainers({
          composeFiles: files,
          expectedProject: P0_COMPOSE_PROJECT,
          signal: shutdownController.signal
        })
      ),
      async waitForPostgres(identity) {
        canonicalComposeIdentity = identity
        canonicalPostgres = createDockerPostgresAdapter(
          runCommand,
          () => canonicalComposeIdentity?.postgres?.id
        )
        await canonicalPostgres.waitUntilReady({ signal: shutdownController.signal })
      },
      async prepareInherited(identity) {
        canonicalRedis = createLoopbackRedisAdapter(
          undefined,
          () => identity.redis,
          verifyCanonicalRedisTransactionBinding
        )
        await canonicalRedis.waitUntilReady({ signal: shutdownController.signal })
        await acquireRedisOwnership({
          redis: canonicalRedis,
          runToken: canonicalSmokeOwnership.ownerToken,
          role: 'child',
          signal: shutdownController.signal
        })
        await prepareCanonicalSmokeDatabase({
          ownership: canonicalSmokeOwnership,
          postgres: canonicalPostgres
        })
      }
    }
  })
  canonicalComposeIdentity = boundary.composeIdentity
  canonicalRedis ??= createLoopbackRedisAdapter(
    undefined,
    () => canonicalComposeIdentity?.redis,
    verifyCanonicalRedisTransactionBinding
  )
  if (!boundary.inherited) {
    await canonicalRedis.waitUntilReady({ signal: shutdownController.signal })
    standaloneCanonicalRedisOwnership = await createStandaloneCanonicalRedisOwnership({
      redis: canonicalRedis,
      runToken: canonicalSmokeOwnership.ownerToken,
      artifactRoot,
      recoveryPath: join(artifactRoot, 'control', 'canonical-redis.json'),
      runId,
      signal: shutdownController.signal
    })
    await prepareCanonicalSmokeDatabase({
      ownership: { ...canonicalSmokeOwnership, inherited: true },
      postgres: canonicalPostgres
    })
  }
  smokeDatabaseCreated = true
}

async function ensureBackendServer() {
  if (await canFetch(`${apiBaseUrl}/actuator/health`)) {
    throw new Error(`The smoke must own ${apiBaseUrl}; an existing backend cannot prove demo execution and required schedulers are enabled`)
  }
  const backendDir = join(projectRoot, 'backend')
  const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${smokeDatabase}`
  const child = startManagedProcess('backend', maven, [
    'spring-boot:run',
    '-Dspring-boot.run.profiles=dev'
  ], backendDir, buildCanonicalBackendEnvironment({
    databaseUrl,
    serverPort: new URL(apiBaseUrl).port || '18086'
  }))
  await waitFor(async () => {
    assertProcessRunning(child)
    const response = await rawJson('/actuator/health').catch(() => null)
    return response?.status === 'UP'
  }, 'real backend /actuator/health', 180000)
  await waitFor(async () => number(await runDbSql(`
    SELECT count(*)
    FROM pg_stat_activity
    WHERE datname = current_database()
      AND pid <> pg_backend_pid()
      AND backend_type = 'client backend'
      AND coalesce(application_name, '') <> 'psql'
  `)) > 0, 'backend connection to dedicated smoke database before any user write', 10000)
}

async function ensureFrontendServer(surface, baseUrl, fallbackPort) {
  if (await canFetch(baseUrl)) {
    throw new Error(`The smoke must own ${baseUrl}; an existing ${surface} server may target a different backend`)
  }
  const command = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const url = new URL(baseUrl)
  const child = startManagedProcess(surface, command, [
    '--workspace', `apps/${surface}`, 'run', 'dev', '--',
    '--host', url.hostname || '127.0.0.1', '--port', url.port || fallbackPort, '--strictPort'
  ], projectRoot, { VITE_API_BASE_URL: apiBaseUrl })
  await waitFor(async () => {
    assertProcessRunning(child)
    return canFetch(baseUrl)
  }, `${surface} Vite server`, 60000)
}

function startManagedProcess(label, command, args, cwd, extraEnv = {}, inheritedEnv = process.env) {
  const descriptor = normalizeLocalCommandDescriptor({
    command,
    args,
    cwd,
    env: { ...inheritedEnv, ...extraEnv }
  })
  const child = spawn(descriptor.command, descriptor.args, {
    cwd: descriptor.cwd,
    env: descriptor.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: false,
    windowsHide: true
  })
  observeLocalChildSpawn(child, { descriptor, label, registerIdentity: true })
  const log = {
    label,
    command: [descriptor.command, ...descriptor.args].join(' '),
    output: ''
  }
  processLogs.push(log)
  child.stdout?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  child.stderr?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  managedProcesses.push(child)
  return child
}

export function sanitizedBackendEnvironment(inheritedEnv = process.env) {
  const env = { ...inheritedEnv }
  for (const key of Object.keys(env)) {
    const normalized = key.toUpperCase()
    if (
      normalized === 'SPRING_APPLICATION_JSON'
      || normalized === 'SPRING_PROFILES_ACTIVE'
      || normalized === 'JAVA_TOOL_OPTIONS'
      || normalized === '_JAVA_OPTIONS'
      || normalized.startsWith('SPRING_DATASOURCE_')
      || normalized.startsWith('SPRING_CONFIG_')
      || normalized.startsWith('SPRING_DATA_REDIS_')
      || normalized.startsWith('DATABASE_')
      || normalized.startsWith('REDIS_')
      || normalized.startsWith('EXECUTION_')
      || normalized.startsWith('MARKET_')
      || normalized.startsWith('TRADING_')
      || /(^|_)(BROKER|FIX|LP)(_|$)/.test(normalized)
      || /(^|_)(KEY|ACCOUNT|ENDPOINT)(_|$)/.test(normalized)
      || normalized === 'P0_RUN_OWNER_TOKEN'
      || normalized === 'USDT_DEMO_SMOKE_DATABASE'
    ) {
      delete env[key]
    }
  }
  return env
}

export function buildCanonicalBackendEnvironment({
  databaseUrl,
  serverPort = '18086',
  inheritedEnv = process.env
}) {
  const environment = sanitizedBackendEnvironment(inheritedEnv)
  for (const key of Object.keys(environment)) {
    if (key.toUpperCase() === 'PROVIDER_INSTRUMENT_SYNC_ENABLED') delete environment[key]
  }
  return {
    ...environment,
    SERVER_PORT: String(serverPort),
    DATABASE_URL: databaseUrl,
    DATABASE_USERNAME: 'postgres',
    DATABASE_PASSWORD: 'password',
    SPRING_DATASOURCE_URL: databaseUrl,
    SPRING_DATASOURCE_USERNAME: 'postgres',
    SPRING_DATASOURCE_PASSWORD: 'password',
    SPRING_PROFILES_ACTIVE: 'dev',
    EXECUTION_MODE: 'demo',
    ADMIN_BOOTSTRAP_ENABLED: 'true',
    ADMIN_BOOTSTRAP_EMAIL: environment.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com',
    ADMIN_BOOTSTRAP_PASSWORD: environment.ADMIN_SMOKE_PASSWORD ?? 'Password123!',
    MARKET_TEST_CONTROL_ENABLED: 'true',
    MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED: 'false',
    TRADING_PENDING_ORDER_EXECUTION_ENABLED: 'true',
    TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED: 'true',
    TRADING_FUNDING_ENABLED: 'true',
    TRADING_FUNDING_SCAN_MS: '500',
    TRADING_LIQUIDATION_ENABLED: 'true',
    TRADING_LIQUIDATION_SCAN_INTERVAL_MS: '500'
  }
}

const P0_COMMON_BACKEND_ENVIRONMENT = Object.freeze({
  SPRING_PROFILES_ACTIVE: 'dev',
  EXECUTION_MODE: 'demo',
  TZ: 'UTC',
  JAVA_TOOL_OPTIONS: '-Duser.timezone=UTC',
  MARKET_TEST_CONTROL_ENABLED: 'true',
  MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED: 'false',
  MARKET_REALTIME_ENABLED: 'false',
  TRADING_FX_FINANCING_ENABLED: 'false',
  REDIS_HOST: '127.0.0.1',
  REDIS_PORT: '6379',
  REDIS_PASSWORD: '',
  TRADING_PENDING_ORDER_SCAN_MS: '500',
  TRADING_PROTECTIVE_ORDER_SCAN_MS: '500',
  TRADING_FUNDING_SCAN_MS: '500',
  TRADING_LIQUIDATION_SCAN_INTERVAL_MS: '500'
})

const P0_PROFILE_WORKERS = Object.freeze({
  UI_CORE: Object.freeze(['false', 'false', 'false', 'false']),
  ORDER_TRIGGER: Object.freeze(['true', 'true', 'false', 'false']),
  FUNDING_ONLY: Object.freeze(['false', 'false', 'true', 'false']),
  LIQUIDATION_ONLY: Object.freeze(['false', 'false', 'false', 'true'])
})

const P0_WORKER_KEYS = Object.freeze([
  'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
  'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
  'TRADING_FUNDING_ENABLED',
  'TRADING_LIQUIDATION_ENABLED'
])

export function buildBackendEnvironment(profile, overrides = {}, inheritedEnv = process.env) {
  if (!Object.hasOwn(P0_PROFILE_WORKERS, profile)) throw new Error('P0_UNKNOWN_PROFILE')
  const workers = Object.fromEntries(P0_WORKER_KEYS.map((key, index) => (
    [key, P0_PROFILE_WORKERS[profile][index]]
  )))
  return {
    ...sanitizedBackendEnvironment(inheritedEnv),
    ...overrides,
    ...P0_COMMON_BACKEND_ENVIRONMENT,
    ...workers
  }
}

function ownerIdForToken(runToken) {
  if (typeof runToken !== 'string' || runToken.length < 16) throw new Error('P0_OWNER_TOKEN_INVALID')
  return createHash('sha256').update(runToken).digest('hex')
}

function assertContainedPath(base, target, error = 'P0_CONTROL_ESCAPE') {
  const pathFromBase = relative(base, target)
  if (pathFromBase === '..' || pathFromBase.startsWith(`..${sep}`) || isAbsolute(pathFromBase)) {
    throw new Error(error)
  }
}

export function createP0ComposeTarget({ artifactBase }) {
  const resolvedArtifactBase = resolve(artifactBase)
  const runtimeRoot = resolve(resolvedArtifactBase, '.runtime')
  const overridePath = resolve(runtimeRoot, 'compose.loopback.yml')
  assertContainedPath(resolvedArtifactBase, runtimeRoot, 'P0_COMPOSE_RUNTIME_ESCAPE')
  assertContainedPath(runtimeRoot, overridePath, 'P0_COMPOSE_RUNTIME_ESCAPE')
  return Object.freeze({
    expectedProject: P0_COMPOSE_PROJECT,
    composeFiles: Object.freeze([
      resolve(projectRoot, 'infra', 'docker-compose.yml'),
      overridePath
    ])
  })
}

async function inspectP0ComposeDirectory(path, { missingError, unsafeError }) {
  let metadata
  try {
    metadata = await lstat(path)
  } catch (error) {
    if (error?.code === 'ENOENT') throw new Error(missingError, { cause: error })
    throw new Error(unsafeError, { cause: error })
  }
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new Error(unsafeError)
  }
  let actual
  try {
    actual = await realpath(path)
  } catch (error) {
    throw new Error(unsafeError, { cause: error })
  }
  if (resolve(actual) !== resolve(path)) throw new Error(unsafeError)
  return metadata
}

function sameP0ComposeDirectoryIdentity(before, after) {
  return after.isDirectory()
    && !after.isSymbolicLink()
    && after.dev === before.dev
    && after.ino === before.ino
    && after.size === before.size
    && after.mtimeMs === before.mtimeMs
    && after.ctimeMs === before.ctimeMs
}

function sameP0ComposeFileIdentity(before, after) {
  return after.isFile()
    && after.nlink === 1
    && after.dev === before.dev
    && after.ino === before.ino
    && after.size === before.size
    && after.mtimeMs === before.mtimeMs
    && after.ctimeMs === before.ctimeMs
}

async function readStableP0ComposeOverride(path) {
  let before
  try {
    before = await lstat(path)
  } catch (error) {
    if (error?.code === 'ENOENT') {
      throw new Error('P0_COMPOSE_OVERRIDE_MISSING', { cause: error })
    }
    throw new Error('P0_CONTROL_FILE_UNSAFE', { cause: error })
  }
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1) {
    throw new Error('P0_CONTROL_FILE_UNSAFE')
  }
  let initialRealpath
  try {
    initialRealpath = await realpath(path)
  } catch (error) {
    throw new Error('P0_CONTROL_FILE_UNSAFE', { cause: error })
  }
  if (resolve(initialRealpath) !== resolve(path)) throw new Error('P0_CONTROL_FILE_UNSAFE')

  let handle
  try {
    handle = await open(path, 'r')
  } catch (error) {
    throw new Error('P0_CONTROL_FILE_UNSAFE', { cause: error })
  }
  try {
    const opened = await handle.stat()
    if (!sameP0ComposeFileIdentity(before, opened)) {
      throw new Error('P0_CONTROL_FILE_UNSAFE')
    }
    const text = await handle.readFile('utf8')
    const openedAfterRead = await handle.stat()
    let after
    let finalRealpath
    let final
    try {
      after = await lstat(path)
      finalRealpath = await realpath(path)
      final = await lstat(path)
    } catch (error) {
      throw new Error('P0_CONTROL_FILE_UNSAFE', { cause: error })
    }
    if (!sameP0ComposeFileIdentity(opened, openedAfterRead)
      || !sameP0ComposeFileIdentity(opened, after)
      || after.isSymbolicLink()
      || !sameP0ComposeFileIdentity(opened, final)
      || final.isSymbolicLink()
      || resolve(finalRealpath) !== resolve(path)) {
      throw new Error('P0_CONTROL_FILE_UNSAFE')
    }
    return text
  } finally {
    await handle.close()
  }
}

export async function verifyP0ComposeTarget({ artifactBase }) {
  const target = createP0ComposeTarget({ artifactBase })
  const resolvedArtifactBase = resolve(artifactBase)
  const runtimeRoot = dirname(target.composeFiles[1])
  const overridePath = target.composeFiles[1]
  assertContainedPath(resolvedArtifactBase, runtimeRoot, 'P0_COMPOSE_RUNTIME_ESCAPE')
  assertContainedPath(runtimeRoot, overridePath, 'P0_COMPOSE_RUNTIME_ESCAPE')
  const artifactBefore = await inspectP0ComposeDirectory(resolvedArtifactBase, {
    missingError: 'P0_COMPOSE_RUNTIME_MISSING',
    unsafeError: 'P0_COMPOSE_RUNTIME_UNSAFE'
  })
  const runtimeBefore = await inspectP0ComposeDirectory(runtimeRoot, {
    missingError: 'P0_COMPOSE_RUNTIME_MISSING',
    unsafeError: 'P0_COMPOSE_RUNTIME_UNSAFE'
  })
  const text = await readStableP0ComposeOverride(overridePath)
  const artifactAfter = await inspectP0ComposeDirectory(resolvedArtifactBase, {
    missingError: 'P0_COMPOSE_RUNTIME_MISSING',
    unsafeError: 'P0_COMPOSE_RUNTIME_UNSAFE'
  })
  const runtimeAfter = await inspectP0ComposeDirectory(runtimeRoot, {
    missingError: 'P0_COMPOSE_RUNTIME_MISSING',
    unsafeError: 'P0_COMPOSE_RUNTIME_UNSAFE'
  })
  if (!sameP0ComposeDirectoryIdentity(artifactBefore, artifactAfter)
    || !sameP0ComposeDirectoryIdentity(runtimeBefore, runtimeAfter)) {
    throw new Error('P0_COMPOSE_RUNTIME_UNSAFE')
  }
  if (text !== P0_COMPOSE_OVERRIDE_TEXT) throw new Error('P0_COMPOSE_OVERRIDE_INVALID')
  return target
}

export async function ensureP0ComposeTarget({ artifactBase }) {
  const target = createP0ComposeTarget({ artifactBase })
  const resolvedArtifactBase = resolve(artifactBase)
  const runtimeRoot = dirname(target.composeFiles[1])
  await mkdir(resolvedArtifactBase, { recursive: true })
  await mkdir(runtimeRoot, { recursive: true })
  const actualArtifactBase = await realpath(resolvedArtifactBase)
  const actualRuntimeRoot = await realpath(runtimeRoot)
  if (resolve(actualArtifactBase) !== resolvedArtifactBase
    || resolve(actualRuntimeRoot) !== runtimeRoot) {
    throw new Error('P0_COMPOSE_RUNTIME_UNSAFE')
  }
  assertContainedPath(actualArtifactBase, actualRuntimeRoot, 'P0_COMPOSE_RUNTIME_ESCAPE')
  const overridePath = target.composeFiles[1]
  const existing = await inspectSafeControlFile(overridePath, { allowMissing: true })
  if (!existing) {
    try {
      await writeControlTextNoClobber(
        overridePath,
        P0_COMPOSE_OVERRIDE_TEXT,
        'P0_COMPOSE_OVERRIDE_EXISTS'
      )
    } catch (error) {
      if (error?.message !== 'P0_COMPOSE_OVERRIDE_EXISTS') throw error
    }
  }
  return verifyP0ComposeTarget({ artifactBase: resolvedArtifactBase })
}

async function inspectSafeControlFile(path, { allowMissing = false } = {}) {
  let metadata
  try {
    metadata = await lstat(path)
  } catch (error) {
    if (allowMissing && error?.code === 'ENOENT') return null
    throw error
  }
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.nlink !== 1) {
    throw new Error('P0_CONTROL_FILE_UNSAFE')
  }
  const actual = await realpath(path)
  if (resolve(actual) !== resolve(path)) throw new Error('P0_CONTROL_FILE_UNSAFE')
  return metadata
}

async function syncControlDirectory(path) {
  let handle
  try {
    handle = await open(dirname(path), 'r')
    await handle.sync()
  } catch (error) {
    if (!['EBADF', 'EINVAL', 'EISDIR', 'ENOTSUP', 'EPERM'].includes(error?.code)) throw error
  } finally {
    await handle?.close()
  }
}

async function writeDurableControlTemp(path, text) {
  const tempPath = join(dirname(path), `.${basename(path)}.${randomUUID()}.tmp`)
  let handle
  try {
    handle = await open(tempPath, 'wx', 0o600)
    await handle.writeFile(text, 'utf8')
    await handle.sync()
    await handle.close()
    handle = null
    return tempPath
  } catch (error) {
    await rm(tempPath, { force: true }).catch(() => {})
    throw error
  } finally {
    await handle?.close()
  }
}

async function writeControlTextNoClobber(path, text, existsError = 'P0_CONTROL_EXISTS') {
  await inspectSafeControlFile(path, { allowMissing: true })
  const tempPath = await writeDurableControlTemp(path, text)
  try {
    await link(tempPath, path)
    await syncControlDirectory(path)
  } catch (error) {
    if (error?.code === 'EEXIST') throw new Error(existsError, { cause: error })
    throw error
  } finally {
    await rm(tempPath, { force: true })
  }
}

async function writeControlJsonNoClobber(path, value, existsError) {
  const text = `${JSON.stringify(value, null, 2)}\n`
  await writeControlTextNoClobber(path, text, existsError)
}

async function replaceControlJsonAtomic(path, value) {
  const text = `${JSON.stringify(value, null, 2)}\n`
  await inspectSafeControlFile(path)
  const tempPath = await writeDurableControlTemp(path, text)
  try {
    await rename(tempPath, path)
    await syncControlDirectory(path)
  } finally {
    await rm(tempPath, { force: true })
  }
}

async function readControlJson(path) {
  const before = await inspectSafeControlFile(path)
  const handle = await open(path, 'r')
  try {
    const opened = await handle.stat()
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino) {
      throw new Error('P0_CONTROL_FILE_UNSAFE')
    }
    const value = JSON.parse(await handle.readFile('utf8'))
    const after = await lstat(path)
    if (!after.isFile() || after.isSymbolicLink() || after.nlink !== 1
      || after.dev !== opened.dev || after.ino !== opened.ino) {
      throw new Error('P0_CONTROL_FILE_UNSAFE')
    }
    return value
  } finally {
    await handle.close()
  }
}

async function existingControlPaths(artifactBase, runId) {
  let base
  let runRoot
  let controlDirectory
  try {
    base = await realpath(resolve(artifactBase))
    runRoot = resolveP0RunRoot(base, runId)
    const actualRunRoot = await realpath(runRoot)
    assertContainedPath(base, actualRunRoot)
    controlDirectory = await realpath(join(runRoot, 'control'))
    assertContainedPath(actualRunRoot, controlDirectory)
  } catch (error) {
    if (error?.message?.startsWith('P0_')) throw error
    throw new Error('P0_CONTROL_MISSING', { cause: error })
  }
  return {
    runRoot,
    ownershipPath: join(controlDirectory, 'ownership.json'),
    cleanedPath: join(controlDirectory, 'cleaned.json'),
    redisPath: join(controlDirectory, 'redis.json')
  }
}

const P0_REDIS_CONTROL_STATES = new Set([
  'NOT_ACQUIRED',
  'ACQUIRE_ARMED',
  'OWNED',
  'SNAPSHOT_READY',
  'REDIS_RELEASE_ARMED'
])

export async function createControlManifest({
  artifactBase,
  runId,
  runToken,
  mode,
  selection,
  database,
  identity,
  profileWorkerMap,
  now = () => new Date().toISOString()
}) {
  const ownerId = ownerIdForToken(runToken)
  const basePath = resolve(artifactBase)
  await mkdir(basePath, { recursive: true })
  const base = await realpath(basePath)
  const runRoot = resolveP0RunRoot(base, runId)
  try {
    await mkdir(runRoot)
  } catch (error) {
    if (error?.code === 'EEXIST') throw new Error('P0_CONTROL_EXISTS', { cause: error })
    throw error
  }
  const actualRunRoot = await realpath(runRoot)
  assertContainedPath(base, actualRunRoot)
  const controlDirectory = join(runRoot, 'control')
  await mkdir(controlDirectory)
  const ownershipPath = join(controlDirectory, 'ownership.json')
  const manifest = {
    schemaVersion: 1,
    status: 'ACTIVE',
    runId,
    mode,
    selection,
    database,
    identity,
    profileWorkerMap,
    ownerId,
    ownerToken: runToken,
    redisState: 'NOT_ACQUIRED',
    journal: {
      schemaVersion: 1,
      sequence: 0,
      resources: []
    },
    createdAt: now()
  }
  try {
    await writeControlJsonNoClobber(ownershipPath, manifest, 'P0_CONTROL_EXISTS')
  } catch (error) {
    if (error?.code === 'EEXIST' || error?.message === 'P0_CONTROL_EXISTS') {
      throw new Error('P0_CONTROL_EXISTS', { cause: error })
    }
    throw error
  }
  return { runRoot, ownershipPath, ownerId }
}

function validateCleanedControlMarker(marker, runId, runToken) {
  const notRequiredKeys = [
    'cleanedAt',
    'ownerId',
    'receipt',
    'runId',
    'schemaVersion',
    'status'
  ]
  const acquiredKeys = [
    'cleanedAt',
    'composeIdentity',
    'composeTarget',
    'ownerId',
    'receipt',
    'runId',
    'schemaVersion',
    'status'
  ]
  const receiptKeys = ['completedAt', 'key', 'state']
  const actualKeys = Object.keys(marker ?? {}).sort()
  const ownerMatches = runToken === undefined
    ? typeof marker?.ownerId === 'string' && /^[a-f0-9]{64}$/.test(marker.ownerId)
    : marker?.ownerId === ownerIdForToken(runToken)
  if (!marker || marker.schemaVersion !== 2 || marker.status !== 'CLEANED'
    || marker.runId !== runId || !ownerMatches
    || typeof marker.cleanedAt !== 'string'
    || Number.isNaN(Date.parse(marker.cleanedAt))) {
    throw new Error('P0_CLEANED_MARKER_INVALID')
  }
  const receipt = marker.receipt
  if (!receipt || typeof receipt !== 'object' || Array.isArray(receipt)
    || JSON.stringify(Object.keys(receipt).sort()) !== JSON.stringify(receiptKeys)) {
    throw new Error('P0_CLEANED_MARKER_INVALID')
  }
  if (receipt.state === 'NOT_REQUIRED') {
    if (JSON.stringify(actualKeys) !== JSON.stringify(notRequiredKeys)
      || receipt.key !== null
      || receipt.completedAt !== marker.cleanedAt) {
      throw new Error('P0_CLEANED_MARKER_INVALID')
    }
    return marker
  }
  let composeTarget
  let composeIdentity
  try {
    composeTarget = safeP0ComposeTarget(marker.composeTarget)
    composeIdentity = safeP0ComposeIdentity(marker.composeIdentity)
  } catch (error) {
    throw new Error('P0_CLEANED_MARKER_INVALID', { cause: error })
  }
  const pending = receipt?.state === 'PENDING' && receipt.completedAt === null
  const completed = receipt?.state === 'COMPLETED'
    && typeof receipt.completedAt === 'string'
    && !Number.isNaN(Date.parse(receipt.completedAt))
  if (JSON.stringify(actualKeys) !== JSON.stringify(acquiredKeys)
    || JSON.stringify(marker.composeTarget) !== JSON.stringify(composeTarget)
    || JSON.stringify(marker.composeIdentity) !== JSON.stringify(composeIdentity)
    || receipt.key !== redisCleanupReceiptKey(marker.ownerId)
    || (!pending && !completed)) {
    throw new Error('P0_CLEANED_MARKER_INVALID')
  }
  return marker
}

async function assertPersistedCleanedControlMarker({
  ownershipPath,
  expected,
  runId,
  runToken
}) {
  let persisted
  try {
    persisted = validateCleanedControlMarker(
      await readControlJson(ownershipPath),
      runId,
      runToken
    )
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_CLEANED_MARKER_PERSISTENCE_MISMATCH', { cause: error })
  }
  if (JSON.stringify(persisted) !== JSON.stringify(expected)) {
    throw new Error('P0_CLEANED_MARKER_PERSISTENCE_MISMATCH')
  }
  return persisted
}

function validateActiveControlManifest(manifest, runId, runToken) {
  if (!manifest || manifest.schemaVersion !== 1 || manifest.status !== 'ACTIVE'
    || manifest.runId !== runId
    || typeof manifest.ownerToken !== 'string'
    || manifest.ownerToken.length === 0
    || typeof manifest.ownerId !== 'string'
    || !/^[a-f0-9]{64}$/.test(manifest.ownerId)
    || manifest.ownerId !== ownerIdForToken(manifest.ownerToken)
    || !P0_REDIS_CONTROL_STATES.has(manifest.redisState)
    || typeof manifest.createdAt !== 'string'
    || Number.isNaN(Date.parse(manifest.createdAt))) {
    throw new Error('P0_CONTROL_INVALID')
  }
  if (runToken !== undefined
    && (manifest.ownerToken !== runToken || manifest.ownerId !== ownerIdForToken(runToken))) {
    throw new Error('P0_OWNER_MISMATCH')
  }
  return manifest
}

export async function completeControlCleanup({
  artifactBase,
  runId,
  runToken,
  resourcesCleaned,
  redisReleaseReceipt,
  redisNotAcquired = false,
  replaceOwnership = replaceControlJsonAtomic,
  now = () => new Date().toISOString()
}) {
  const { ownershipPath } = await existingControlPaths(artifactBase, runId)
  const ownershipFile = await inspectSafeControlFile(ownershipPath, { allowMissing: true })
  if (!ownershipFile) throw new Error('P0_CONTROL_MISSING')
  let manifest
  try {
    manifest = await readControlJson(ownershipPath)
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_CONTROL_MISSING', { cause: error })
  }
  if (manifest?.status === 'CLEANED') {
    validateCleanedControlMarker(manifest, runId, runToken)
    return { status: 'CLEANED', alreadyCleaned: true }
  }
  validateActiveControlManifest(manifest, runId, runToken)
  if (resourcesCleaned !== true) throw new Error('P0_CLEANUP_INCOMPLETE')
  if (redisNotAcquired
    ? manifest.redisState !== 'NOT_ACQUIRED'
    : (manifest.redisState !== 'REDIS_RELEASE_ARMED'
      || redisReleaseReceipt !== manifest.ownerId)) {
    throw new Error('P0_REDIS_RELEASE_UNPROVEN')
  }
  const cleanedAt = now()
  let cleaned
  if (redisNotAcquired) {
    cleaned = {
      schemaVersion: 2,
      status: 'CLEANED',
      runId,
      ownerId: manifest.ownerId,
      cleanedAt,
      receipt: {
        key: null,
        state: 'NOT_REQUIRED',
        completedAt: cleanedAt
      }
    }
  } else {
    let composeTarget
    let composeIdentity
    try {
      composeTarget = safeP0ComposeTarget(manifest.composeTarget)
      composeIdentity = safeP0ComposeIdentity(manifest.composeIdentity)
    } catch (error) {
      throw new Error('P0_CLEANUP_COMPOSE_IDENTITY_INVALID', { cause: error })
    }
    cleaned = {
      schemaVersion: 2,
      status: 'CLEANED',
      runId,
      ownerId: manifest.ownerId,
      cleanedAt,
      composeTarget,
      composeIdentity,
      receipt: {
        key: redisCleanupReceiptKey(manifest.ownerId),
        state: 'PENDING',
        completedAt: null
      }
    }
  }
  validateCleanedControlMarker(cleaned, runId, runToken)
  await replaceOwnership(ownershipPath, cleaned)
  await assertPersistedCleanedControlMarker({
    ownershipPath,
    expected: cleaned,
    runId,
    runToken
  })
  return { status: 'CLEANED', alreadyCleaned: false }
}

const P0_RECOVERY_RESOURCE_TYPES = new Set([
  'process',
  'override',
  'database',
  'canonical-child'
])
const P0_PROCESS_FINGERPRINT_PATTERN = /^sha256:[a-f0-9]{64}$/

function normalizeP0RecoveryResource(resource, runToken) {
  if (!resource || typeof resource !== 'object' || Array.isArray(resource)) {
    throw new Error('P0_JOURNAL_RESOURCE_INVALID')
  }
  const serialized = JSON.stringify(resource)
  if (serialized.includes(runToken)) throw new Error('P0_JOURNAL_TOKEN_FORBIDDEN')
  const normalized = JSON.parse(serialized)
  if (!P0_RECOVERY_RESOURCE_TYPES.has(normalized.type)
    || typeof normalized.id !== 'string' || normalized.id.length === 0
    || normalized.id.length > 240) {
    throw new Error('P0_JOURNAL_RESOURCE_INVALID')
  }
  if (normalized.type === 'database') assertP0DatabaseName(normalized.id)
  if (normalized.live !== undefined && typeof normalized.live !== 'boolean') {
    throw new Error('P0_JOURNAL_RESOURCE_INVALID')
  }
  if (normalized.type === 'override') {
    const relativePath = relative('.', normalized.path ?? '')
    if (!normalized.path || isAbsolute(normalized.path)
      || relativePath === '..' || relativePath.startsWith(`..${sep}`)) {
      throw new Error('P0_JOURNAL_RESOURCE_INVALID')
    }
  }
  delete normalized.state
  delete normalized.sequence
  delete normalized.recordedAt
  delete normalized.startedAt
  delete normalized.pid
  delete normalized.processStartedAt
  delete normalized.processFingerprint
  return normalized
}

async function activeControlManifestForUpdate(artifactBase, runId, runToken) {
  const paths = await existingControlPaths(artifactBase, runId)
  const manifest = await readControlJson(paths.ownershipPath)
  if (manifest?.status === 'CLEANED') throw new Error('P0_CONTROL_NOT_ACTIVE')
  validateActiveControlManifest(manifest, runId, runToken)
  if (manifest.journal === undefined) {
    manifest.journal = { schemaVersion: 1, sequence: 0, resources: [] }
  }
  if (manifest.journal?.schemaVersion !== 1
    || !Number.isSafeInteger(manifest.journal.sequence)
    || manifest.journal.sequence < 0
    || !Array.isArray(manifest.journal.resources)) {
    throw new Error('P0_JOURNAL_INVALID')
  }
  return { paths, manifest }
}

async function transitionP0RedisControlState({
  artifactBase,
  runId,
  runToken,
  from,
  to,
  signal
}) {
  throwIfP0Aborted(signal)
  if (!P0_REDIS_CONTROL_STATES.has(from) || !P0_REDIS_CONTROL_STATES.has(to)) {
    throw new Error('P0_REDIS_STATE_INVALID')
  }
  const active = await activeControlManifestForUpdate(artifactBase, runId, runToken)
  throwIfP0Aborted(signal)
  if (active.manifest.redisState !== from) throw new Error('P0_REDIS_STATE_INVALID')
  active.manifest.redisState = to
  throwIfP0Aborted(signal)
  await replaceControlJsonAtomic(active.paths.ownershipPath, active.manifest)
  throwIfP0Aborted(signal)
  return active.manifest
}

export async function runP0JournaledMutation({
  artifactBase,
  runId,
  runToken,
  resource,
  start,
  stopLiveProcess,
  signal,
  now = () => new Date().toISOString()
}) {
  throwIfP0Aborted(signal)
  if (typeof start !== 'function') throw new Error('P0_JOURNAL_START_INVALID')
  const normalized = normalizeP0RecoveryResource(resource, runToken)
  const planned = await activeControlManifestForUpdate(artifactBase, runId, runToken)
  throwIfP0Aborted(signal)
  if (planned.manifest.journal.resources.some(({ type, id }) => (
    type === normalized.type && id === normalized.id
  ))) {
    throw new Error('P0_JOURNAL_RESOURCE_DUPLICATE')
  }
  const sequence = planned.manifest.journal.sequence + 1
  planned.manifest.journal.sequence = sequence
  planned.manifest.journal.resources.push({
    ...normalized,
    sequence,
    state: 'PLANNED',
    recordedAt: now()
  })
  throwIfP0Aborted(signal)
  await replaceControlJsonAtomic(planned.paths.ownershipPath, planned.manifest)
  throwIfP0Aborted(signal)

  let transition
  const markStarted = (startedResult) => {
    transition ??= (async () => {
      throwIfP0Aborted(signal)
      const processIdentity = startedResult?.processIdentity
      const processFingerprint = processIdentity?.processFingerprint
        ?? processIdentity?.commandFingerprint
      if (normalized.live === true
        && (!Number.isSafeInteger(startedResult?.pid)
          || startedResult.pid < 1
          || processIdentity?.pid !== startedResult.pid
          || typeof processIdentity.startedAt !== 'string'
          || processIdentity.startedAt.length === 0
          || !P0_PROCESS_FINGERPRINT_PATTERN.test(processFingerprint ?? ''))) {
        throw new Error('P0_PROCESS_IDENTITY_MISSING')
      }
      const started = await activeControlManifestForUpdate(artifactBase, runId, runToken)
      throwIfP0Aborted(signal)
      const entry = started.manifest.journal.resources.find((candidate) => (
        candidate.sequence === sequence
          && candidate.type === normalized.type
          && candidate.id === normalized.id
      ))
      if (entry?.state !== 'PLANNED') throw new Error('P0_JOURNAL_TRANSITION_INVALID')
      entry.state = 'STARTED'
      entry.startedAt = now()
      if (Number.isSafeInteger(startedResult?.pid) && startedResult.pid > 0
        && processIdentity?.pid === startedResult.pid
        && typeof processIdentity.startedAt === 'string'
        && processIdentity.startedAt.length > 0
        && P0_PROCESS_FINGERPRINT_PATTERN.test(processFingerprint ?? '')) {
        entry.pid = startedResult.pid
        entry.processStartedAt = processIdentity.startedAt
        entry.processFingerprint = processFingerprint
      }
      throwIfP0Aborted(signal)
      await replaceControlJsonAtomic(started.paths.ownershipPath, started.manifest)
      throwIfP0Aborted(signal)
    })()
    return transition
  }
  let result
  try {
    throwIfP0Aborted(signal)
    result = await start(markStarted, { signal })
    throwIfP0Aborted(signal)
  } catch (error) {
    if (transition) await transition
    throw error
  }
  if (normalized.live === true && !transition) {
    const processIdentity = result?.processIdentity
    const processFingerprint = processIdentity?.processFingerprint
      ?? processIdentity?.commandFingerprint
    if (!Number.isSafeInteger(result?.pid)
      || result.pid < 1
      || processIdentity?.pid !== result.pid
      || typeof processIdentity.startedAt !== 'string'
      || processIdentity.startedAt.length === 0
      || !P0_PROCESS_FINGERPRINT_PATTERN.test(processFingerprint ?? '')) {
      await stopLiveProcess?.(result, { signal })
      throw new Error('P0_PROCESS_IDENTITY_MISSING')
    }
  }
  await markStarted(result)
  throwIfP0Aborted(signal)
  if (normalized.live === true && Object.hasOwn(result ?? {}, 'status')) {
    const completed = await activeControlManifestForUpdate(artifactBase, runId, runToken)
    throwIfP0Aborted(signal)
    const entry = completed.manifest.journal.resources.find((candidate) => (
      candidate.sequence === sequence
        && candidate.type === normalized.type
        && candidate.id === normalized.id
    ))
    if (entry?.state !== 'STARTED') throw new Error('P0_JOURNAL_TRANSITION_INVALID')
    entry.state = 'COMPLETED'
    entry.completedAt = now()
    throwIfP0Aborted(signal)
    await replaceControlJsonAtomic(completed.paths.ownershipPath, completed.manifest)
    throwIfP0Aborted(signal)
  }
  return result
}

export function runP0JournaledCommand({
  artifactBase,
  runId,
  runToken,
  resourceId,
  commandFingerprint,
  descriptor,
  runCommand,
  inspectProcess,
  signal,
  now
}) {
  if (typeof runCommand !== 'function'
    || !descriptor || typeof descriptor !== 'object'
    || typeof resourceId !== 'string' || resourceId.length === 0
    || !P0_PROCESS_FINGERPRINT_PATTERN.test(commandFingerprint ?? '')) {
    throw new Error('P0_JOURNALED_COMMAND_INVALID')
  }
  return runP0JournaledMutation({
    artifactBase,
    runId,
    runToken,
    resource: {
      type: 'process',
      id: resourceId,
      live: true,
      commandFingerprint
    },
    start: (markStarted) => runCommand({
      ...descriptor,
      signal,
      async onSpawn(child) {
        throwIfP0Aborted(signal)
        const processIdentity = child?.processIdentity
          ?? await inspectProcess?.(child?.pid, { signal })
        throwIfP0Aborted(signal)
        await markStarted({
          pid: child?.pid,
          processIdentity
        })
      }
    }),
    signal,
    now
  })
}

export async function terminateJournaledOwnedProcesses({
  resources,
  acquireNativeProcessHandle,
  requireTreeProof = false,
  signal
}) {
  throwIfP0Aborted(signal)
  if (!Array.isArray(resources)
    || typeof acquireNativeProcessHandle !== 'function') {
    throw new Error('P0_PROCESS_RECOVERY_DEPENDENCY_INVALID')
  }
  let terminated = 0
  for (const resource of resources.toReversed()) {
    throwIfP0Aborted(signal)
    if (!['process', 'canonical-child'].includes(resource?.type)) continue
    if (resource.live === true && resource.state === 'COMPLETED') continue
    if (resource.live === true
      && (resource.state !== 'STARTED'
        || !Number.isSafeInteger(resource.pid)
        || resource.pid < 1
        || typeof resource.processStartedAt !== 'string'
        || resource.processStartedAt.length === 0
        || !P0_PROCESS_FINGERPRINT_PATTERN.test(resource.processFingerprint ?? ''))) {
      throw new Error('P0_PROCESS_IDENTITY_MISSING')
    }
    if (resource.state !== 'STARTED'
      || !Number.isSafeInteger(resource.pid)
      || resource.pid < 1) continue
    const expectedFingerprint = resource.processFingerprint ?? resource.commandFingerprint
    if (typeof resource.processStartedAt !== 'string'
      || resource.processStartedAt.length === 0
      || typeof expectedFingerprint !== 'string'
      || !P0_PROCESS_FINGERPRINT_PATTERN.test(expectedFingerprint)) {
      throw new Error('P0_PROCESS_IDENTITY_UNKNOWN')
    }
    const handle = await acquireNativeProcessHandle(resource.pid, { signal })
    throwIfP0Aborted(signal)
    if (!handle
      || typeof handle.inspectIdentity !== 'function'
      || typeof handle.terminateTree !== 'function') {
      throw new Error('P0_PROCESS_NATIVE_HANDLE_REQUIRED')
    }
    try {
      const observed = await handle.inspectIdentity({ signal })
      throwIfP0Aborted(signal)
      if (observed === null) {
        if (requireTreeProof) throw new Error('P0_PROCESS_TREE_TERMINATION_UNVERIFIED')
        continue
      }
      const observedFingerprint = observed?.processFingerprint ?? observed?.commandFingerprint
      if (!Number.isSafeInteger(observed?.pid)
        || observed.pid !== resource.pid
        || typeof observed.startedAt !== 'string'
        || observed.startedAt.length === 0
        || typeof observedFingerprint !== 'string'
        || !P0_PROCESS_FINGERPRINT_PATTERN.test(observedFingerprint)) {
        throw new Error('P0_PROCESS_IDENTITY_UNKNOWN')
      }
      if (observed.startedAt !== resource.processStartedAt
        || observedFingerprint !== expectedFingerprint) {
        throw new Error('P0_PROCESS_IDENTITY_MISMATCH')
      }
      const proof = await handle.terminateTree(resource, { signal })
      throwIfP0Aborted(signal)
      if (requireTreeProof
        && (proof?.provider !== P0_WINDOWS_JOB_OBJECT_CAPABILITY
          || proof.treeTerminated !== true
          || proof.pid !== resource.pid
          || proof.startedAt !== resource.processStartedAt
          || proof.processFingerprint !== expectedFingerprint)) {
        throw new Error('P0_PROCESS_TREE_TERMINATION_UNVERIFIED')
      }
      terminated += 1
    } finally {
      await handle.close?.({ signal })
    }
  }
  return { terminated }
}

const CANONICAL_CHILD_INHERITED_ENVIRONMENT = new Set([
  'APPDATA',
  'COMSPEC',
  'HOME',
  'HOMEDRIVE',
  'HOMEPATH',
  'LANG',
  'LC_ALL',
  'LOCALAPPDATA',
  'NUMBER_OF_PROCESSORS',
  'OS',
  'PATH',
  'PATHEXT',
  'PROCESSOR_ARCHITECTURE',
  'PROCESSOR_IDENTIFIER',
  'PROGRAMDATA',
  'PROGRAMFILES',
  'PROGRAMFILES(X86)',
  'SYSTEMDRIVE',
  'SYSTEMROOT',
  'TEMP',
  'TMP',
  'USERPROFILE',
  'WINDIR'
])

const CANONICAL_CHILD_FORCED_ENVIRONMENT = new Set([
  'ADMIN_BASE_URL',
  'ADMIN_SMOKE_EMAIL',
  'ADMIN_SMOKE_PASSWORD',
  'API_BASE_URL',
  'P0_COMPOSE_BASE_FILE',
  'P0_COMPOSE_OVERRIDE_FILE',
  'P0_COMPOSE_PROJECT',
  'P0_POSTGRES_CONTAINER_ID',
  'P0_REDIS_CONTAINER_ID',
  'P0_RUN_OWNER_TOKEN',
  'TZ',
  'USDT_DEMO_SMOKE_ARTIFACTS',
  'USDT_DEMO_SMOKE_DATABASE',
  'USDT_DEMO_SMOKE_EMAIL',
  'USDT_DEMO_SMOKE_PASSWORD',
  'USDT_DEMO_SMOKE_RUN_ID',
  'WEB_BASE_URL'
])

function explicitCanonicalProcessEnvironment(inheritedEnv) {
  const environment = {}
  for (const [key, value] of Object.entries(sanitizedBackendEnvironment(inheritedEnv))) {
    if (CANONICAL_CHILD_INHERITED_ENVIRONMENT.has(key.toUpperCase()) && value !== undefined) {
      environment[key] = String(value)
    }
  }
  return environment
}

export function validateCanonicalChildEnvironment(invocation) {
  const environment = invocation?.env
  const expected = invocation?.expected
  if (!environment || typeof environment !== 'object' || Array.isArray(environment)
    || !expected || typeof expected !== 'object' || Array.isArray(expected)) {
    throw new Error('P0_CANONICAL_ENVIRONMENT_INVALID')
  }
  for (const key of Object.keys(environment)) {
    const normalized = key.toUpperCase()
    if (!CANONICAL_CHILD_INHERITED_ENVIRONMENT.has(normalized)
      && !CANONICAL_CHILD_FORCED_ENVIRONMENT.has(normalized)) {
      throw new Error(`P0_CANONICAL_ENVIRONMENT_FORBIDDEN: ${key}`)
    }
  }
  if (ownerIdForToken(environment.P0_RUN_OWNER_TOKEN) !== expected.ownerId) {
    throw new Error('P0_OWNER_MISMATCH')
  }
  assertP0DatabaseName(environment.USDT_DEMO_SMOKE_DATABASE)
  if (environment.USDT_DEMO_SMOKE_DATABASE !== expected.database) {
    throw new Error('P0_DATABASE_IDENTITY_MISMATCH')
  }
  const expectedRunId = `${expected.runId}-canonical`
  if (environment.USDT_DEMO_SMOKE_RUN_ID !== expectedRunId
    || environment.API_BASE_URL !== 'http://127.0.0.1:18086'
    || environment.WEB_BASE_URL !== 'http://127.0.0.1:5199'
    || environment.ADMIN_BASE_URL !== 'http://127.0.0.1:5200') {
    throw new Error('P0_CANONICAL_ENVIRONMENT_INVALID')
  }
  const resolvedRunRoot = resolve(expected.runRoot)
  const expectedArtifacts = resolve(resolvedRunRoot, 'canonical')
  assertContainedPath(resolvedRunRoot, expectedArtifacts, 'P0_CANONICAL_ARTIFACT_ESCAPE')
  if (resolve(environment.USDT_DEMO_SMOKE_ARTIFACTS) !== expectedArtifacts) {
    throw new Error('P0_CANONICAL_ARTIFACT_ESCAPE')
  }
  if (expected.composeIdentity !== undefined) {
    const environmentIdentity = safeP0ComposeIdentity({
      project: environment.P0_COMPOSE_PROJECT,
      postgres: {
        id: environment.P0_POSTGRES_CONTAINER_ID,
        image: 'postgres:16',
        host: '127.0.0.1',
        hostPort: 5432
      },
      redis: {
        id: environment.P0_REDIS_CONTAINER_ID,
        image: 'redis:7',
        host: '127.0.0.1',
        hostPort: 6379
      }
    })
    if (JSON.stringify(environmentIdentity)
      !== JSON.stringify(safeP0ComposeIdentity(expected.composeIdentity))) {
      throw new Error('P0_CANONICAL_COMPOSE_IDENTITY_MISMATCH')
    }
  }
  if (expected.composeTarget !== undefined) {
    const environmentTarget = safeP0ComposeTarget({
      expectedProject: environment.P0_COMPOSE_PROJECT,
      composeFiles: [
        environment.P0_COMPOSE_BASE_FILE,
        environment.P0_COMPOSE_OVERRIDE_FILE
      ]
    })
    if (JSON.stringify(environmentTarget)
      !== JSON.stringify(safeP0ComposeTarget(expected.composeTarget))) {
      throw new Error('P0_CANONICAL_COMPOSE_TARGET_MISMATCH')
    }
  }
  if (environment.USDT_DEMO_SMOKE_EMAIL !== `p0-canonical+${expected.ownerId.slice(0, 16)}@example.invalid`
    || environment.ADMIN_SMOKE_EMAIL !== `p0-admin+${expected.ownerId.slice(0, 16)}@example.invalid`
    || environment.USDT_DEMO_SMOKE_PASSWORD !== `P0-${expected.ownerId.slice(0, 24)}!aA1`
    || environment.ADMIN_SMOKE_PASSWORD !== `P0-${expected.ownerId.slice(0, 24)}!aA2`) {
    throw new Error('P0_CANONICAL_CREDENTIAL_INVALID')
  }
  return true
}

export function buildCanonicalChildInvocation({
  scriptPath,
  ownerToken,
  ownerId,
  database,
  composeIdentity,
  composeTarget,
  runId = `p0-canonical-${ownerId.slice(0, 12)}`,
  runRoot = join(projectRoot, 'artifacts', 'p0-user-trading', runId),
  inheritedEnv = process.env
}) {
  if (ownerId !== ownerIdForToken(ownerToken)) throw new Error('P0_OWNER_MISMATCH')
  if (!/^fx_p0_user_e2e_[a-z0-9_]+$/.test(database)) throw new Error('P0_DATABASE_NAME_INVALID')
  if (typeof runId !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(runId)) {
    throw new Error('P0_RUN_ID_INVALID')
  }
  const canonicalScript = resolve(scriptPath)
  const args = [canonicalScript]
  const resolvedRunRoot = resolve(runRoot)
  const verifiedComposeIdentity = composeIdentity === undefined
    ? undefined
    : safeP0ComposeIdentity(composeIdentity)
  const verifiedComposeTarget = composeTarget === undefined
    ? undefined
    : safeP0ComposeTarget(composeTarget)
  const invocation = {
    command: process.execPath,
    args,
    shell: false,
    env: {
      ...explicitCanonicalProcessEnvironment(inheritedEnv),
      API_BASE_URL: 'http://127.0.0.1:18086',
      WEB_BASE_URL: 'http://127.0.0.1:5199',
      ADMIN_BASE_URL: 'http://127.0.0.1:5200',
      TZ: 'UTC',
      USDT_DEMO_SMOKE_RUN_ID: `${runId}-canonical`,
      USDT_DEMO_SMOKE_EMAIL: `p0-canonical+${ownerId.slice(0, 16)}@example.invalid`,
      USDT_DEMO_SMOKE_PASSWORD: `P0-${ownerId.slice(0, 24)}!aA1`,
      ADMIN_SMOKE_EMAIL: `p0-admin+${ownerId.slice(0, 16)}@example.invalid`,
      ADMIN_SMOKE_PASSWORD: `P0-${ownerId.slice(0, 24)}!aA2`,
      USDT_DEMO_SMOKE_ARTIFACTS: resolve(resolvedRunRoot, 'canonical'),
      P0_RUN_OWNER_TOKEN: ownerToken,
      USDT_DEMO_SMOKE_DATABASE: database,
      ...(verifiedComposeIdentity
        ? {
            P0_COMPOSE_PROJECT: verifiedComposeIdentity.project,
            P0_POSTGRES_CONTAINER_ID: verifiedComposeIdentity.postgres.id,
            P0_REDIS_CONTAINER_ID: verifiedComposeIdentity.redis.id
          }
        : {}),
      ...(verifiedComposeTarget
        ? {
            P0_COMPOSE_BASE_FILE: verifiedComposeTarget.composeFiles[0],
            P0_COMPOSE_OVERRIDE_FILE: verifiedComposeTarget.composeFiles[1]
          }
        : {})
    },
    log: { command: process.execPath, args, ownerId },
    evidence: { ownerId, database },
    expected: {
      ownerId,
      database,
      runId,
      runRoot: resolvedRunRoot,
      composeIdentity: verifiedComposeIdentity,
      composeTarget: verifiedComposeTarget
    }
  }
  validateCanonicalChildEnvironment(invocation)
  return invocation
}

export async function verifyCanonicalChildResult({ child, context, redis, postgres, signal }) {
  throwIfP0Aborted(signal)
  if (child?.status !== 0 || child.signal) throw new Error('P0_CANONICAL_CHILD_FAILED')
  const reportPath = resolve(context?.runRoot ?? '', 'canonical', 'report.json')
  assertContainedPath(resolve(context?.runRoot ?? ''), reportPath, 'P0_CANONICAL_ARTIFACT_ESCAPE')
  const report = await readControlJson(reportPath)
  throwIfP0Aborted(signal)
  if (report?.status !== 'PASS'
    || report.runId !== `${context.options?.runId}-canonical`
    || report.smokeDatabase !== context.canonicalDatabase
    || report.error !== null
    || !Array.isArray(report.results)
    || report.results.length === 0
    || report.results.some((result) => result?.status !== 'PASS')) {
    throw new Error('P0_CANONICAL_REPORT_INVALID')
  }
  if (!Array.isArray(report.sourceEvidence) || report.sourceEvidence.length === 0) {
    throw new Error('P0_CANONICAL_SOURCE_EVIDENCE_MISSING')
  }
  assertP0DatabaseName(context.canonicalDatabase)
  if (await postgres.readDatabaseOwnership(context.canonicalDatabase, { signal }) !== null) {
    throw new Error('P0_CANONICAL_DATABASE_NOT_CLEAN')
  }
  throwIfP0Aborted(signal)
  const redisOwner = await runRedisOwnershipTransaction(
    redis,
    () => redis.get(P0_REDIS_OWNER_KEY, { signal }),
    { signal }
  )
  if (redisOwner !== context.ownerToken) {
    throw new Error('P0_REDIS_OWNER_MISMATCH')
  }
  throwIfP0Aborted(signal)
  return {
    status: 'PASS',
    reportPath,
    sourceEvidence: report.sourceEvidence.length
  }
}

function assertP0DatabaseName(segmentName) {
  if (typeof segmentName !== 'string'
    || segmentName.length > 63
    || !P0_DATABASE_PATTERN.test(segmentName)) {
    throw new Error('P0_DATABASE_NAME_INVALID')
  }
}

function quotedDatabaseIdentifier(segmentName) {
  assertP0DatabaseName(segmentName)
  return `"${segmentName}"`
}

function quotedPostgresLiteral(value) {
  if (typeof value !== 'string') throw new Error('P0_DATABASE_LITERAL_INVALID')
  return `'${value.replaceAll("'", "''")}'`
}

export function databaseSegmentForAttempt({ phase, attempt, randomSuffix }) {
  if (typeof phase !== 'string' || !/^[a-z][a-z0-9-]{0,31}$/.test(phase)) {
    throw new Error('P0_DATABASE_PHASE_INVALID')
  }
  if (!Number.isSafeInteger(attempt) || attempt < 1) throw new Error('P0_DATABASE_ATTEMPT_INVALID')
  const suffix = randomSuffix ?? randomUUID().replaceAll('-', '').slice(0, 12)
  if (typeof suffix !== 'string' || !/^[a-f0-9]{12,24}$/.test(suffix)) {
    throw new Error('P0_DATABASE_RANDOM_INVALID')
  }
  const segmentName = `fx_p0_user_e2e_${phase.replaceAll('-', '_')}_${attempt}_${suffix}`
  assertP0DatabaseName(segmentName)
  return segmentName
}

async function requireOwnedDatabase({ segmentName, runToken, postgres, signal }) {
  throwIfP0Aborted(signal)
  assertP0DatabaseName(segmentName)
  const identity = signal === undefined
    ? await postgres.readDatabaseOwnership(segmentName)
    : await postgres.readDatabaseOwnership(segmentName, { signal })
  throwIfP0Aborted(signal)
  if (identity?.segmentName !== segmentName) throw new Error('P0_DATABASE_IDENTITY_MISMATCH')
  if (identity.ownerMarker !== `p0-owner:${runToken}`) throw new Error('P0_DATABASE_OWNER_MISMATCH')
  return identity
}

export async function createOwnedDatabase({
  segmentName,
  runToken,
  postgres,
  recordDatabase = async () => {},
  signal
}) {
  throwIfP0Aborted(signal)
  assertP0DatabaseName(segmentName)
  const ownerId = ownerIdForToken(runToken)
  if (signal === undefined) {
    await recordDatabase(segmentName)
  } else {
    await recordDatabase(segmentName, { signal })
  }
  throwIfP0Aborted(signal)
  await postgres.executeAdminSql(
    `CREATE DATABASE ${quotedDatabaseIdentifier(segmentName)}`,
    { sensitive: false, ...(signal === undefined ? {} : { signal }) }
  )
  throwIfP0Aborted(signal)
  await postgres.executeAdminSql(
    `COMMENT ON DATABASE ${quotedDatabaseIdentifier(segmentName)} IS ${quotedPostgresLiteral(`p0-owner:${runToken}`)}`,
    { sensitive: true, ...(signal === undefined ? {} : { signal }) }
  )
  throwIfP0Aborted(signal)
  await requireOwnedDatabase({ segmentName, runToken, postgres, signal })
  throwIfP0Aborted(signal)
  return { segmentName, ownerId }
}

export async function alterOwnedDatabaseTimezone({ segmentName, runToken, postgres, signal }) {
  throwIfP0Aborted(signal)
  await requireOwnedDatabase({ segmentName, runToken, postgres, signal })
  throwIfP0Aborted(signal)
  await postgres.executeAdminSql(
    `ALTER DATABASE ${quotedDatabaseIdentifier(segmentName)} SET timezone TO 'UTC'`,
    { sensitive: false, ...(signal === undefined ? {} : { signal }) }
  )
  throwIfP0Aborted(signal)
}

export async function dropOwnedDatabase({ segmentName, runToken, postgres, signal }) {
  throwIfP0Aborted(signal)
  await requireOwnedDatabase({ segmentName, runToken, postgres, signal })
  throwIfP0Aborted(signal)
  await postgres.executeAdminSql(
    `DROP DATABASE ${quotedDatabaseIdentifier(segmentName)} WITH (FORCE)`,
    { sensitive: false, ...(signal === undefined ? {} : { signal }) }
  )
  throwIfP0Aborted(signal)
}

export async function verifyDatabaseIdentity({
  segmentName,
  runToken,
  databaseUrl,
  postgres,
  signal
}) {
  throwIfP0Aborted(signal)
  assertP0DatabaseName(segmentName)
  const expectedUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  if (databaseUrl !== expectedUrl) throw new Error('P0_DATABASE_URL_INVALID')
  const identity = await postgres.probeDatabase({
    databaseUrl,
    segmentName,
    ...(signal === undefined ? {} : { signal })
  })
  throwIfP0Aborted(signal)
  if (identity?.currentDatabase !== segmentName) throw new Error('P0_DATABASE_IDENTITY_MISMATCH')
  if (identity.ownerMarker !== `p0-owner:${runToken}`) throw new Error('P0_DATABASE_OWNER_MISMATCH')
  if (identity.timezone !== 'UTC') throw new Error('P0_DATABASE_TIMEZONE_MISMATCH')
  return { segmentName, timezone: 'UTC' }
}

export async function verifyLiveBackendDatabaseIdentity(input) {
  const identity = await verifyDatabaseIdentity(input)
  if (typeof input.postgres.probeBackendConnection === 'function') {
    const activityCount = await input.postgres.probeBackendConnection({
      segmentName: input.segmentName,
      signal: input.signal
    })
    throwIfP0Aborted(input.signal)
    if (!Number.isSafeInteger(activityCount) || activityCount < 1) {
      throw new Error('P0_BACKEND_DATABASE_ACTIVITY_MISSING')
    }
  }
  return identity
}

export async function prepareCanonicalSmokeDatabase({ ownership, postgres }) {
  if (ownership?.inherited !== true
    || ownership.ownerId !== ownerIdForToken(ownership.ownerToken)) {
    throw new Error('P0_CANONICAL_OWNERSHIP_INVALID')
  }
  assertP0DatabaseName(ownership.database)
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${ownership.database}`
  try {
    await createOwnedDatabase({
      segmentName: ownership.database,
      runToken: ownership.ownerToken,
      postgres
    })
    await alterOwnedDatabaseTimezone({
      segmentName: ownership.database,
      runToken: ownership.ownerToken,
      postgres
    })
    return await verifyDatabaseIdentity({
      segmentName: ownership.database,
      runToken: ownership.ownerToken,
      databaseUrl,
      postgres
    })
  } catch (error) {
    try {
      const identity = await postgres.readDatabaseOwnership(ownership.database)
      if (identity !== null) {
        await dropOwnedDatabase({
          segmentName: ownership.database,
          runToken: ownership.ownerToken,
          postgres
        })
      }
    } catch (cleanupError) {
      throw appendFailure(error, cleanupError, 'canonical database rollback')
    }
    throw error
  }
}

export async function cleanupCanonicalSmokeDatabase({ ownership, postgres }) {
  if (ownership?.inherited !== true
    || ownership.ownerId !== ownerIdForToken(ownership.ownerToken)) {
    throw new Error('P0_CANONICAL_OWNERSHIP_INVALID')
  }
  return dropOwnedDatabase({
    segmentName: ownership.database,
    runToken: ownership.ownerToken,
    postgres
  })
}

const P0_REDIS_OWNER_KEY = 'p0:e2e:owner'
const P0_REDIS_CLEANUP_RECEIPT_PREFIX = 'p0:e2e:cleanup:'

function redisCleanupReceiptKey(ownerId) {
  if (typeof ownerId !== 'string' || !/^[a-f0-9]{64}$/.test(ownerId)) {
    throw new Error('P0_OWNER_ID_INVALID')
  }
  return `${P0_REDIS_CLEANUP_RECEIPT_PREFIX}${ownerId}`
}

function assertExactRedisKey(key) {
  if (typeof key !== 'string'
    || key.length === 0
    || key.length > 256
    || key === P0_REDIS_OWNER_KEY
    || /[\x00-\x20*?\[\]]/.test(key)) {
    throw new Error('P0_REDIS_KEY_INVALID')
  }
}

function runRedisOwnershipTransaction(redis, operation, { signal } = {}) {
  if (typeof redis?.runVerifiedTransaction !== 'function') return operation()
  return redis.runVerifiedTransaction(operation, { signal })
}

export async function acquireRedisOwnership({ redis, runToken, role, signal }) {
  return runRedisOwnershipTransaction(redis, async () => {
    throwIfP0Aborted(signal)
    const ownerId = ownerIdForToken(runToken)
    if (role === 'child') {
      if (await redis.get(P0_REDIS_OWNER_KEY, { signal }) !== runToken) {
        throw new Error('P0_REDIS_OWNER_MISMATCH')
      }
      throwIfP0Aborted(signal)
      return { ownerKey: P0_REDIS_OWNER_KEY, ownerId, inherited: true }
    }
    if (role !== 'parent' && role !== 'standalone') throw new Error('P0_REDIS_ROLE_INVALID')
    if (!await redis.setNx(P0_REDIS_OWNER_KEY, runToken, { signal })) {
      throwIfP0Aborted(signal)
      await redis.get(P0_REDIS_OWNER_KEY, { signal })
      throwIfP0Aborted(signal)
      throw new Error('P0_REDIS_OWNER_EXISTS')
    }
    throwIfP0Aborted(signal)
    return { ownerKey: P0_REDIS_OWNER_KEY, ownerId, inherited: false }
  }, { signal })
}

const P0_STANDALONE_REDIS_ACTIVE_KEYS = Object.freeze([
  'inventory',
  'inventoryCount',
  'inventoryFingerprint',
  'ownerId',
  'ownerToken',
  'runId',
  'schemaVersion',
  'state'
])
const P0_STANDALONE_REDIS_SNAPSHOT_KEYS = Object.freeze([
  ...P0_STANDALONE_REDIS_ACTIVE_KEYS,
  'snapshot',
  'touchedKeys'
].sort())
const P0_STANDALONE_REDIS_RELEASE_KEYS = Object.freeze([
  'ownerId',
  'receipt',
  'runId',
  'schemaVersion',
  'state'
])
const P0_STANDALONE_REDIS_RECEIPT_KEYS = Object.freeze([
  'completedAt',
  'key',
  'state'
])

function requireStandaloneRedisTransaction(redis) {
  if (typeof redis?.runVerifiedTransaction !== 'function') {
    throw new Error('P0_STANDALONE_REDIS_TRANSACTION_REQUIRED')
  }
}

function exactStandaloneRedisObject(value, expectedKeys) {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify(expectedKeys)
}

function standaloneRedisInventory(keys) {
  if (!Array.isArray(keys) || keys.length === 0) throw new Error('P0_REDIS_KEY_INVALID')
  const inventory = []
  for (let index = 0; index < keys.length; index += 1) {
    if (!Object.hasOwn(keys, index)) throw new Error('P0_REDIS_KEY_INVALID')
    assertExactRedisKey(keys[index])
    inventory.push(keys[index])
  }
  if (new Set(inventory).size !== inventory.length) throw new Error('P0_REDIS_KEY_INVALID')
  return inventory
}

function standaloneRedisInventoryFingerprint(inventory) {
  return createHash('sha256').update(JSON.stringify(inventory)).digest('hex')
}

function validateStandaloneRedisSnapshot(snapshot, inventory) {
  if (!Array.isArray(snapshot) || snapshot.length !== inventory.length) {
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_INVALID')
  }
  for (let index = 0; index < inventory.length; index += 1) {
    const entry = snapshot[index]
    if (!exactStandaloneRedisObject(entry, ['exists', 'expiresAtMs', 'key', 'value'])
      || entry.key !== inventory[index]
      || (entry.exists !== true && entry.exists !== false)) {
      throw new Error('P0_STANDALONE_REDIS_RECOVERY_INVALID')
    }
    if (entry.exists) {
      if (typeof entry.value !== 'string'
        || (entry.expiresAtMs !== null
          && (!Number.isSafeInteger(entry.expiresAtMs) || entry.expiresAtMs < 0))) {
        throw new Error('P0_STANDALONE_REDIS_RECOVERY_INVALID')
      }
    } else if (entry.value !== null || entry.expiresAtMs !== null) {
      throw new Error('P0_STANDALONE_REDIS_RECOVERY_INVALID')
    }
  }
}

function validateStandaloneCanonicalRedisManifest(manifest, runId) {
  try {
    if (manifest?.schemaVersion !== 1 || manifest.runId !== runId) {
      throw new Error('invalid standalone manifest identity')
    }
    if (['ACQUIRE_ARMED', 'SNAPSHOT_READY'].includes(manifest.state)) {
      const expectedKeys = manifest.state === 'ACQUIRE_ARMED'
        ? P0_STANDALONE_REDIS_ACTIVE_KEYS
        : P0_STANDALONE_REDIS_SNAPSHOT_KEYS
      if (!exactStandaloneRedisObject(manifest, expectedKeys)) {
        throw new Error('invalid standalone active schema')
      }
      const ownerId = ownerIdForToken(manifest.ownerToken)
      const inventory = standaloneRedisInventory(manifest.inventory)
      if (manifest.ownerId !== ownerId
        || manifest.inventoryCount !== inventory.length
        || manifest.inventoryFingerprint !== standaloneRedisInventoryFingerprint(inventory)) {
        throw new Error('invalid standalone active identity')
      }
      if (manifest.state === 'SNAPSHOT_READY') {
        validateStandaloneRedisSnapshot(manifest.snapshot, inventory)
        if (!Array.isArray(manifest.touchedKeys)
          || JSON.stringify(manifest.touchedKeys) !== JSON.stringify(inventory)) {
          throw new Error('invalid standalone touched keys')
        }
      }
      return structuredClone(manifest)
    }
    if (!['RELEASE_PENDING', 'COMPLETED'].includes(manifest.state)
      || !exactStandaloneRedisObject(manifest, P0_STANDALONE_REDIS_RELEASE_KEYS)
      || typeof manifest.ownerId !== 'string'
      || !/^[a-f0-9]{64}$/.test(manifest.ownerId)
      || !exactStandaloneRedisObject(
        manifest.receipt,
        P0_STANDALONE_REDIS_RECEIPT_KEYS
      )
      || manifest.receipt.key !== redisCleanupReceiptKey(manifest.ownerId)) {
      throw new Error('invalid standalone release schema')
    }
    const receiptValid = manifest.state === 'RELEASE_PENDING'
      ? manifest.receipt.state === 'PENDING' && manifest.receipt.completedAt === null
      : manifest.receipt.state === 'COMPLETED'
        && typeof manifest.receipt.completedAt === 'string'
        && !Number.isNaN(Date.parse(manifest.receipt.completedAt))
    if (!receiptValid) throw new Error('invalid standalone receipt')
    return structuredClone(manifest)
  } catch (error) {
    if (error?.message === 'P0_STANDALONE_REDIS_RECOVERY_INVALID') throw error
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_INVALID', { cause: error })
  }
}

async function resolveStandaloneCanonicalRedisRecoveryTarget({
  recoveryPath,
  runId,
  artifactRoot: requestedArtifactRoot,
  createControlDirectory = false
}) {
  if (typeof recoveryPath !== 'string'
    || !isAbsolute(recoveryPath)
    || resolve(recoveryPath) !== recoveryPath) {
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_PATH_INVALID')
  }
  const rootInput = requestedArtifactRoot ?? dirname(dirname(recoveryPath))
  if (typeof rootInput !== 'string' || !isAbsolute(rootInput)) {
    throw new Error('P0_STANDALONE_REDIS_ARTIFACT_ROOT_INVALID')
  }
  const resolvedArtifactRoot = resolve(rootInput)
  resolveP0RunRoot(dirname(resolvedArtifactRoot), runId)
  const expectedPath = join(resolvedArtifactRoot, 'control', 'canonical-redis.json')
  if (recoveryPath !== expectedPath) {
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_PATH_INVALID')
  }
  assertContainedPath(
    resolvedArtifactRoot,
    expectedPath,
    'P0_STANDALONE_REDIS_RECOVERY_PATH_INVALID'
  )
  const rootMetadata = await lstat(resolvedArtifactRoot)
  const actualArtifactRoot = await realpath(resolvedArtifactRoot)
  if (!rootMetadata.isDirectory()
    || rootMetadata.isSymbolicLink()
    || resolve(actualArtifactRoot) !== resolvedArtifactRoot) {
    throw new Error('P0_STANDALONE_REDIS_ARTIFACT_ROOT_UNSAFE')
  }
  const controlDirectory = dirname(expectedPath)
  if (createControlDirectory) {
    try {
      await mkdir(controlDirectory)
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error
    }
  }
  const controlMetadata = await lstat(controlDirectory)
  const actualControlDirectory = await realpath(controlDirectory)
  if (!controlMetadata.isDirectory()
    || controlMetadata.isSymbolicLink()
    || resolve(actualControlDirectory) !== controlDirectory) {
    throw new Error('P0_STANDALONE_REDIS_CONTROL_DIRECTORY_UNSAFE')
  }
  assertContainedPath(
    actualArtifactRoot,
    actualControlDirectory,
    'P0_STANDALONE_REDIS_RECOVERY_PATH_INVALID'
  )
  return { artifactRoot: resolvedArtifactRoot, recoveryPath: expectedPath }
}

async function persistStandaloneCanonicalRedisManifest({
  recoveryPath,
  manifest,
  runId,
  create = false
}) {
  const expected = validateStandaloneCanonicalRedisManifest(manifest, runId)
  if (create) {
    await writeControlJsonNoClobber(
      recoveryPath,
      expected,
      'P0_STANDALONE_REDIS_RECOVERY_EXISTS'
    )
  } else {
    await replaceControlJsonAtomic(recoveryPath, expected)
  }
  let persisted
  try {
    persisted = validateStandaloneCanonicalRedisManifest(
      await readControlJson(recoveryPath),
      runId
    )
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_PERSISTENCE_MISMATCH', {
      cause: error
    })
  }
  if (JSON.stringify(persisted) !== JSON.stringify(expected)) {
    throw new Error('P0_STANDALONE_REDIS_RECOVERY_PERSISTENCE_MISMATCH')
  }
  return persisted
}

function standaloneCanonicalRedisReleaseMarker({ state, runId, ownerId, completedAt }) {
  return {
    schemaVersion: 1,
    state,
    runId,
    ownerId,
    receipt: {
      key: redisCleanupReceiptKey(ownerId),
      state: state === 'COMPLETED' ? 'COMPLETED' : 'PENDING',
      completedAt: state === 'COMPLETED' ? completedAt : null
    }
  }
}

export async function recoverStandaloneCanonicalRedisOwnership({
  redis,
  recoveryPath,
  runId,
  signal,
  now = () => new Date().toISOString(),
  artifactRoot
}) {
  await resolveStandaloneCanonicalRedisRecoveryTarget({
    recoveryPath,
    runId,
    artifactRoot
  })
  const manifest = validateStandaloneCanonicalRedisManifest(
    await readControlJson(recoveryPath),
    runId
  )
  if (manifest.state === 'COMPLETED') {
    return { status: 'COMPLETED', alreadyCompleted: true }
  }
  requireStandaloneRedisTransaction(redis)
  let restored = 0
  let ownerReleased = true
  let releaseStatus = 'ALREADY_RELEASED'
  if (manifest.state === 'ACQUIRE_ARMED') {
    const release = await redis.runVerifiedTransaction(async () => {
      throwIfP0Aborted(signal)
      const owner = await redis.get(P0_REDIS_OWNER_KEY, { signal })
      throwIfP0Aborted(signal)
      if (owner === manifest.ownerToken) {
        return releaseP0RedisOwnershipWithReceipt({
          redis,
          runToken: manifest.ownerToken,
          ownerId: manifest.ownerId,
          signal
        })
      }
      if (owner !== null) throw new Error('P0_STANDALONE_REDIS_OWNER_MISMATCH')
      const receipt = await redis.get(manifest.receipt?.key
        ?? redisCleanupReceiptKey(manifest.ownerId), { signal })
      throwIfP0Aborted(signal)
      if (receipt === null) return { status: 'NOT_ACQUIRED' }
      if (receipt !== manifest.ownerId) {
        throw new Error('P0_STANDALONE_REDIS_CLEANUP_RECEIPT_MISMATCH')
      }
      return releaseP0RedisOwnershipWithReceipt({
        redis,
        runToken: manifest.ownerToken,
        ownerId: manifest.ownerId,
        signal
      })
    }, { signal })
    releaseStatus = release.status
    ownerReleased = release.status !== 'NOT_ACQUIRED'
  } else if (manifest.state === 'SNAPSHOT_READY') {
    const release = await redis.runVerifiedTransaction(async () => {
      throwIfP0Aborted(signal)
      const owner = await redis.get(P0_REDIS_OWNER_KEY, { signal })
      throwIfP0Aborted(signal)
      let restoredInSession = 0
      if (owner === manifest.ownerToken) {
        restoredInSession = await restoreP0RedisSnapshot({
          redis,
          snapshot: manifest.snapshot,
          touchedKeys: manifest.touchedKeys,
          signal
        })
      } else if (owner !== null) {
        throw new Error('P0_STANDALONE_REDIS_OWNER_MISMATCH')
      }
      const released = await releaseP0RedisOwnershipWithReceipt({
        redis,
        runToken: manifest.ownerToken,
        ownerId: manifest.ownerId,
        signal
      })
      if (owner === null && released.status !== 'ALREADY_RELEASED') {
        throw new Error('P0_STANDALONE_REDIS_RELEASE_PROOF_INVALID')
      }
      return { ...released, restored: restoredInSession }
    }, { signal })
    restored = release.restored
    releaseStatus = release.status
  }

  let pending = manifest
  if (manifest.state !== 'RELEASE_PENDING') {
    pending = standaloneCanonicalRedisReleaseMarker({
      state: 'RELEASE_PENDING',
      runId,
      ownerId: manifest.ownerId
    })
    pending = await persistStandaloneCanonicalRedisManifest({
      recoveryPath,
      manifest: pending,
      runId
    })
  }
  const receiptRemovalStatus = await removeP0CleanupReceipt({
    redis,
    marker: pending,
    signal
  })
  const completed = standaloneCanonicalRedisReleaseMarker({
    state: 'COMPLETED',
    runId,
    ownerId: pending.ownerId,
    completedAt: now()
  })
  await persistStandaloneCanonicalRedisManifest({
    recoveryPath,
    manifest: completed,
    runId
  })
  return {
    status: 'COMPLETED',
    restored,
    ownerReleased,
    releaseStatus,
    receiptRemovalStatus
  }
}

export async function createStandaloneCanonicalRedisOwnership({
  redis,
  runToken,
  keys = P0_DEFAULT_REDIS_KEYS,
  recoveryPath,
  runId,
  signal,
  artifactRoot
}) {
  requireStandaloneRedisTransaction(redis)
  const inventory = standaloneRedisInventory(keys)
  const ownerId = ownerIdForToken(runToken)
  await resolveStandaloneCanonicalRedisRecoveryTarget({
    recoveryPath,
    runId,
    artifactRoot,
    createControlDirectory: true
  })
  const existing = await inspectSafeControlFile(recoveryPath, { allowMissing: true })
  if (existing) {
    await recoverStandaloneCanonicalRedisOwnership({
      redis,
      recoveryPath,
      runId,
      signal,
      artifactRoot
    })
  }
  const armed = {
    schemaVersion: 1,
    state: 'ACQUIRE_ARMED',
    runId,
    ownerId,
    ownerToken: runToken,
    inventoryCount: inventory.length,
    inventoryFingerprint: standaloneRedisInventoryFingerprint(inventory),
    inventory
  }
  await persistStandaloneCanonicalRedisManifest({
    recoveryPath,
    manifest: armed,
    runId,
    create: !existing
  })
  const snapshot = await redis.runVerifiedTransaction(async () => {
    await acquireRedisOwnership({
      redis,
      runToken,
      role: 'standalone',
      signal
    })
    return snapshotRedisKeys({ redis, keys: inventory, signal })
  }, { signal })
  await persistStandaloneCanonicalRedisManifest({
    recoveryPath,
    manifest: {
      ...armed,
      state: 'SNAPSHOT_READY',
      snapshot,
      touchedKeys: [...inventory]
    },
    runId
  })
  let cleanupResult
  let cleanupPromise
  return {
    async cleanup() {
      if (cleanupResult) return { ...cleanupResult, alreadyCleaned: true }
      const cleanupSignal = AbortSignal.timeout(30000)
      cleanupPromise ??= recoverStandaloneCanonicalRedisOwnership({
        redis,
        recoveryPath,
        runId,
        signal: cleanupSignal,
        artifactRoot
      }).then((result) => {
        cleanupResult = {
          restored: result.restored,
          ownerReleased: result.ownerReleased
        }
        return cleanupResult
      }).finally(() => {
        cleanupPromise = undefined
      })
      return cleanupPromise
    }
  }
}

export async function snapshotRedisKeys({ redis, keys, signal }) {
  return runRedisOwnershipTransaction(redis, async () => {
    throwIfP0Aborted(signal)
    if (!Array.isArray(keys) || new Set(keys).size !== keys.length) {
      throw new Error('P0_REDIS_KEY_INVALID')
    }
    const snapshot = []
    for (const key of keys) {
      throwIfP0Aborted(signal)
      assertExactRedisKey(key)
      const current = await redis.readExact(key, { signal })
      throwIfP0Aborted(signal)
      const exists = current?.exists === true
      const expiresAtMs = exists ? current.expiresAtMs : null
      if (exists && expiresAtMs !== null
        && (!Number.isSafeInteger(expiresAtMs) || expiresAtMs < 0)) {
        throw new Error('P0_REDIS_EXPIRY_INVALID')
      }
      snapshot.push({
        key,
        exists,
        value: exists ? current.value : null,
        expiresAtMs
      })
    }
    return snapshot
  }, { signal })
}

export function validateP0RedisRecoveryState(state, {
  runId,
  ownerId,
  inventory = P0_DEFAULT_REDIS_KEYS
}) {
  try {
    const expectedKeys = [
      'inventoryCount',
      'inventoryFingerprint',
      'ownerId',
      'runId',
      'schemaVersion',
      'snapshot',
      'touchedKeys'
    ]
    if (!state || typeof state !== 'object' || Array.isArray(state)
      || JSON.stringify(Object.keys(state).sort()) !== JSON.stringify(expectedKeys)
      || state.schemaVersion !== 1
      || state.runId !== runId
      || state.ownerId !== ownerId
      || !/^[a-f0-9]{64}$/.test(ownerId ?? '')
      || !Array.isArray(inventory)
      || JSON.stringify(inventory) !== JSON.stringify(P0_DEFAULT_REDIS_KEYS)
      || state.inventoryCount !== inventory.length
      || state.inventoryFingerprint !== sha256Text(JSON.stringify(inventory))
      || !Array.isArray(state.snapshot)
      || state.snapshot.length !== inventory.length
      || !Array.isArray(state.touchedKeys)
      || new Set(state.touchedKeys).size !== state.touchedKeys.length) {
      throw new Error('invalid recovery envelope')
    }
    const inventorySet = new Set(inventory)
    for (let index = 0; index < inventory.length; index += 1) {
      const entry = state.snapshot[index]
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)
        || JSON.stringify(Object.keys(entry).sort())
          !== JSON.stringify(['exists', 'expiresAtMs', 'key', 'value'])
        || entry.key !== inventory[index]
        || (entry.exists !== true && entry.exists !== false)) {
        throw new Error('invalid recovery snapshot')
      }
      assertExactRedisKey(entry.key)
      if (entry.exists === true) {
        if (typeof entry.value !== 'string'
          || (entry.expiresAtMs !== null
            && (!Number.isSafeInteger(entry.expiresAtMs) || entry.expiresAtMs < 0))) {
          throw new Error('invalid recovery value')
        }
      } else if (entry.value !== null || entry.expiresAtMs !== null) {
        throw new Error('invalid missing recovery value')
      }
    }
    for (const key of state.touchedKeys) {
      assertExactRedisKey(key)
      if (!inventorySet.has(key)) throw new Error('invalid touched key')
    }
    return structuredClone(state)
  } catch (error) {
    if (error?.message === 'P0_REDIS_RECOVERY_INVALID') throw error
    throw new Error('P0_REDIS_RECOVERY_INVALID', { cause: error })
  }
}

export function createP0RedisRecoveryState({
  runId,
  ownerId,
  inventory = P0_DEFAULT_REDIS_KEYS,
  snapshot,
  touchedKeys
}) {
  return validateP0RedisRecoveryState({
    schemaVersion: 1,
    runId,
    ownerId,
    inventoryCount: inventory.length,
    inventoryFingerprint: sha256Text(JSON.stringify(inventory)),
    snapshot: structuredClone(snapshot),
    touchedKeys: [...touchedKeys]
  }, { runId, ownerId, inventory })
}

export async function cleanupOwnedRedis({ redis, runToken, snapshot, touchedKeys }) {
  ownerIdForToken(runToken)
  return runRedisOwnershipTransaction(redis, async () => {
    if (await redis.get(P0_REDIS_OWNER_KEY) !== runToken) {
      throw new Error('P0_REDIS_OWNER_MISMATCH')
    }
    const restored = await restoreP0RedisSnapshot({ redis, snapshot, touchedKeys })
    const released = await redis.compareDelete(P0_REDIS_OWNER_KEY, runToken)
    if (!released) {
      throw new Error('P0_REDIS_RELEASE_FAILED')
    }
    return { restored, ownerReleased: true }
  })
}

async function restoreP0RedisSnapshot({ redis, snapshot, touchedKeys, signal }) {
  throwIfP0Aborted(signal)
  if (!Array.isArray(snapshot) || !Array.isArray(touchedKeys)) throw new Error('P0_REDIS_SNAPSHOT_INVALID')
  const byKey = new Map()
  for (const entry of snapshot) {
    assertExactRedisKey(entry?.key)
    if (byKey.has(entry.key)) throw new Error('P0_REDIS_SNAPSHOT_INVALID')
    byKey.set(entry.key, entry)
  }
  for (const key of touchedKeys) {
    assertExactRedisKey(key)
    if (!byKey.has(key)) byKey.set(key, { key, exists: false, value: null, expiresAtMs: null })
  }
  for (const key of touchedKeys) {
    const entry = byKey.get(key)
    throwIfP0Aborted(signal)
    if (entry.exists === true) {
      await redis.restoreExact(key, entry.value, entry.expiresAtMs, { signal })
    } else {
      await redis.deleteExact(key, { signal })
    }
    throwIfP0Aborted(signal)
  }
  return touchedKeys.length
}

async function releaseP0RedisOwnershipWithReceipt({ redis, runToken, ownerId, signal }) {
  throwIfP0Aborted(signal)
  if (ownerId !== ownerIdForToken(runToken)
    || typeof redis?.releaseOwnershipWithReceipt !== 'function') {
    throw new Error('P0_REDIS_ATOMIC_RELEASE_REQUIRED')
  }
  const receiptKey = redisCleanupReceiptKey(ownerId)
  const status = await runRedisOwnershipTransaction(
    redis,
    () => redis.releaseOwnershipWithReceipt({
      ownerKey: P0_REDIS_OWNER_KEY,
      receiptKey,
      runToken,
      ownerId,
      signal
    }),
    { signal }
  )
  throwIfP0Aborted(signal)
  if (!['RELEASED', 'ALREADY_RELEASED'].includes(status)) {
    throw new Error('P0_REDIS_RELEASE_FAILED')
  }
  return { status, receiptKey, receipt: ownerId }
}

async function removeP0CleanupReceipt({ redis, marker, signal }) {
  throwIfP0Aborted(signal)
  if (typeof redis?.removeCleanupReceipt !== 'function') {
    throw new Error('P0_REDIS_CLEANUP_RECEIPT_REMOVER_REQUIRED')
  }
  const status = await runRedisOwnershipTransaction(
    redis,
    () => redis.removeCleanupReceipt({
      receiptKey: marker.receipt.key,
      ownerId: marker.ownerId,
      signal
    }),
    { signal }
  )
  throwIfP0Aborted(signal)
  if (status === 'MISMATCH') {
    throw new Error('P0_REDIS_CLEANUP_RECEIPT_MISMATCH')
  }
  if (!['REMOVED', 'ALREADY_ABSENT'].includes(status)) {
    throw new Error('P0_REDIS_CLEANUP_RECEIPT_RESULT_INVALID')
  }
  return status
}

function assertLoopbackHttpEndpoint(value, expectedPort) {
  let url
  try {
    url = new URL(value)
  } catch (error) {
    throw new Error('P0_ENDPOINT_NOT_LOOPBACK', { cause: error })
  }
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
    || url.protocol !== 'http:'
    || url.username
    || url.password) {
    throw new Error('P0_ENDPOINT_NOT_LOOPBACK')
  }
  if (Number(url.port) !== expectedPort) throw new Error('P0_ENDPOINT_PORT_INVALID')
}

function assertNoExternalTradingConfiguration(inheritedEnv) {
  for (const [key, value] of Object.entries(inheritedEnv ?? {})) {
    const normalized = key.toUpperCase()
    if (/(^|_)(BROKER|FIX|LP)(_|$)/.test(normalized) && String(value).length > 0) {
      throw new Error('P0_EXTERNAL_TRADING_CONFIGURATION')
    }
  }
}

function assertNoInheritedDockerTarget(inheritedEnv) {
  for (const [key, value] of Object.entries(inheritedEnv ?? {})) {
    const normalized = key.toUpperCase()
    if (['DOCKER_HOST', 'DOCKER_CONTEXT'].includes(normalized)
      && String(value).trim().length > 0) {
      throw new Error('P0_DOCKER_TARGET_INHERITED')
    }
    if (['COMPOSE_PROJECT_NAME', 'COMPOSE_FILE', 'COMPOSE_PROFILES'].includes(normalized)
      && String(value).trim().length > 0) {
      throw new Error('P0_COMPOSE_TARGET_INHERITED')
    }
  }
}

function assertLocalDockerEndpoint(endpoint) {
  if (typeof endpoint !== 'string'
    || ![
      'npipe:////./pipe/docker_engine',
      'unix:///var/run/docker.sock'
    ].includes(endpoint)) {
    throw new Error('P0_DOCKER_DAEMON_NOT_LOCAL')
  }
}

export function validateP0SafetyConfiguration({
  ownerId,
  database,
  endpoints,
  inheritedEnv
}) {
  if (typeof ownerId !== 'string' || !/^[a-f0-9]{64}$/.test(ownerId)) {
    throw new Error('P0_OWNER_ID_INVALID')
  }
  assertP0DatabaseName(database)
  assertNoInheritedDockerTarget(inheritedEnv)
  assertNoExternalTradingConfiguration(inheritedEnv)
  assertLoopbackHttpEndpoint(endpoints?.api, 18086)
  assertLoopbackHttpEndpoint(endpoints?.web, 5199)
  assertLoopbackHttpEndpoint(endpoints?.admin, 5200)
  if (endpoints?.databaseUrl !== `jdbc:postgresql://127.0.0.1:5432/${database}`) {
    throw new Error('P0_DATABASE_URL_INVALID')
  }
  if (endpoints?.redisHost !== '127.0.0.1' || endpoints?.redisPort !== 6379) {
    throw new Error('P0_REDIS_ENDPOINT_INVALID')
  }
}

export async function runSafetyPreflight({
  ownerId,
  database,
  endpoints,
  inheritedEnv,
  infrastructure,
  composeFiles,
  signal
}) {
  throwIfP0Aborted(signal)
  validateP0SafetyConfiguration({ ownerId, database, endpoints, inheritedEnv })
  if (typeof infrastructure.inspectDockerDaemon === 'function') {
    const daemon = await infrastructure.inspectDockerDaemon({ signal })
    throwIfP0Aborted(signal)
    assertLocalDockerEndpoint(daemon?.endpoint)
  }

  const compose = [
    {
      service: 'postgres',
      containerName: 'fx-platform-postgres',
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432,
      containerPort: 5432,
      composeFiles,
      expectedProject: P0_COMPOSE_PROJECT
    },
    {
      service: 'redis',
      containerName: 'fx-platform-redis',
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379,
      containerPort: 6379,
      composeFiles,
      expectedProject: P0_COMPOSE_PROJECT
    }
  ]
  for (const expected of compose) {
    throwIfP0Aborted(signal)
    if (!await infrastructure.verifyComposePort(expected, { signal })) {
      throw new Error('P0_COMPOSE_PORT_MISMATCH')
    }
    throwIfP0Aborted(signal)
  }
  for (const port of [18086, 5199, 5200]) {
    throwIfP0Aborted(signal)
    const listener = await infrastructure.inspectListener(
      { host: '127.0.0.1', port },
      { signal }
    )
    throwIfP0Aborted(signal)
    if (listener && listener.ownerId !== ownerId) throw new Error('P0_PORT_OWNED_BY_UNKNOWN')
  }
  return {
    status: 'PASS',
    database,
    composePorts: [5432, 6379],
    businessPorts: [18086, 5199, 5200]
  }
}

const P0_SELECTION_LIST_FIELDS = ['caseIds', 'phases', 'profiles', 'viewports']
const P0_SELECTION_FIELDS = [
  ...P0_SELECTION_LIST_FIELDS,
  'metadata'
]
const P0_CONTROL_REQUESTED_PHASES = new Set([
  'preflight',
  'canonical',
  'authority',
  'report'
])
const P0_MATRIX_REQUESTED_PHASES = new Set(P0_CASES.map(({ phase }) => phase))
const P0_SELECTION_PHASE_BY_HASH = new Map([
  ...new Set([
    ...P0_CONTROL_REQUESTED_PHASES,
    ...P0_MATRIX_REQUESTED_PHASES,
    'selected',
    'all',
    'cleanup'
  ])
].map((phase) => [p0SelectionMetadataHash('P0_SELECTION_PHASE', phase), phase]))

function p0SelectionMetadataHash(domain, value) {
  return sha256Text(`${domain}\0${value}`)
}

function p0RequestedScope(phase) {
  if (P0_CONTROL_REQUESTED_PHASES.has(phase)) return 'CONTROL'
  if (P0_MATRIX_REQUESTED_PHASES.has(phase)) return 'MATRIX'
  if (phase === 'selected') return 'SELECTED'
  if (phase === 'all') return 'ALL'
  if (phase === 'cleanup') return 'CLEANUP'
  throw new Error('P0_SELECTION_INVALID')
}

function normalizedP0Selection(selection) {
  if (!selection || typeof selection !== 'object' || Array.isArray(selection)
    || JSON.stringify(Object.keys(selection).toSorted())
      !== JSON.stringify([...P0_SELECTION_FIELDS].toSorted())) {
    throw new Error('P0_SELECTION_INVALID')
  }
  const normalized = {}
  for (const field of P0_SELECTION_LIST_FIELDS) {
    const values = selection[field]
    if (!Array.isArray(values)
      || new Set(values).size !== values.length
      || values.some((value) => typeof value !== 'string' || value.length === 0)) {
      throw new Error('P0_SELECTION_INVALID')
    }
    if (field === 'phases' && values.some((value) => !P0_MATRIX_REQUESTED_PHASES.has(value))) {
      throw new Error('P0_SELECTION_INVALID')
    }
    normalized[field] = [...values]
  }
  const metadata = selection.metadata
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)
    || JSON.stringify(Object.keys(metadata)) !== JSON.stringify(['values'])
    || !Array.isArray(metadata.values)
    || metadata.values.length !== 2) {
    throw new Error('P0_SELECTION_INVALID')
  }
  const [phaseHash, scopeHash] = metadata.values
  const requestedPhase = typeof phaseHash === 'string'
    ? P0_SELECTION_PHASE_BY_HASH.get(phaseHash)
    : null
  if (!requestedPhase || typeof scopeHash !== 'string') {
    throw new Error('P0_SELECTION_INVALID')
  }
  const requestedScope = p0RequestedScope(requestedPhase)
  const expectedScopeHash = p0SelectionMetadataHash('P0_SELECTION_SCOPE', requestedScope)
  if (scopeHash !== expectedScopeHash) {
    throw new Error('P0_SELECTION_INVALID')
  }
  const expectedPhases = P0_MATRIX_REQUESTED_PHASES.has(requestedPhase)
    ? [requestedPhase]
    : []
  if (JSON.stringify(normalized.phases) !== JSON.stringify(expectedPhases)) {
    throw new Error('P0_SELECTION_INVALID')
  }
  normalized.metadata = {
    values: [
      p0SelectionMetadataHash('P0_SELECTION_PHASE', requestedPhase),
      expectedScopeHash
    ]
  }
  return normalized
}

function artifactP0Selection(selection) {
  const normalized = normalizedP0Selection(selection)
  return Object.fromEntries(P0_SELECTION_LIST_FIELDS.map((field) => (
    [field, normalized[field]]
  )))
}

function sameP0Selection(first, second) {
  return JSON.stringify(normalizedP0Selection(first)) === JSON.stringify(normalizedP0Selection(second))
}

function persistedP0Mode(mode) {
  if (mode === 'discovery') return 'DISCOVERY'
  if (mode === 'certification') return 'CERTIFICATION'
  throw new Error('P0_MODE_INVALID')
}

function readSafeP0RunState(path, root) {
  const resolvedPath = resolve(path)
  const resolvedRoot = resolve(root ?? dirname(resolvedPath))
  assertContainedPath(resolvedRoot, resolvedPath, 'P0_RUN_STATE_FILE_UNSAFE')
  let descriptor
  try {
    const actualRoot = realpathSync(resolvedRoot)
    const before = lstatSync(resolvedPath)
    if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1) {
      throw new Error('P0_RUN_STATE_FILE_UNSAFE')
    }
    const actualPath = realpathSync(resolvedPath)
    if (resolve(actualRoot) !== resolvedRoot || resolve(actualPath) !== resolvedPath) {
      throw new Error('P0_RUN_STATE_FILE_UNSAFE')
    }
    assertContainedPath(actualRoot, actualPath, 'P0_RUN_STATE_FILE_UNSAFE')
    descriptor = openSync(resolvedPath, 'r')
    const opened = fstatSync(descriptor)
    if (!opened.isFile() || opened.nlink !== 1
      || opened.dev !== before.dev || opened.ino !== before.ino) {
      throw new Error('P0_RUN_STATE_FILE_UNSAFE')
    }
    const text = readFileSync(descriptor, 'utf8')
    const after = lstatSync(resolvedPath)
    const afterPath = realpathSync(resolvedPath)
    if (!after.isFile() || after.isSymbolicLink() || after.nlink !== 1
      || after.dev !== opened.dev || after.ino !== opened.ino
      || resolve(afterPath) !== actualPath) {
      throw new Error('P0_RUN_STATE_FILE_UNSAFE')
    }
    return JSON.parse(text)
  } finally {
    if (descriptor !== undefined) closeSync(descriptor)
  }
}

export function openP0RunState({
  path,
  root = dirname(resolve(path)),
  resume,
  runId,
  mode,
  commit,
  worktreeFingerprint,
  schemaVersion,
  registryFingerprint,
  definitions,
  selection,
  createState = loadOrCreateRunState
}) {
  const normalizedSelection = normalizedP0Selection(selection)
  const resolvedPath = resolve(path)
  const resolvedRoot = resolve(root)
  assertContainedPath(resolvedRoot, resolvedPath, 'P0_RUN_STATE_FILE_UNSAFE')
  const expected = {
    path,
    runId,
    mode: persistedP0Mode(mode),
    commit,
    worktreeFingerprint,
    schemaVersion,
    registryFingerprint,
    definitions,
    selection: normalizedSelection
  }
  if (resume !== true) {
    if (existsSync(path)) throw new Error('P0_RUN_STATE_EXISTS')
    const stagingPath = `${resolvedPath}.${process.pid}.${randomUUID()}.create`
    try {
      const created = createState({
        ...expected,
        path: stagingPath,
        selection: artifactP0Selection(normalizedSelection)
      })
      if (!created || typeof created !== 'object' || Array.isArray(created)) {
        throw new Error('P0_RUN_STATE_CREATE_INVALID')
      }
      writeCaseResultAtomic(stagingPath, {
        ...created,
        selection: normalizedSelection
      })
      try {
        linkSync(stagingPath, resolvedPath)
      } catch (error) {
        if (error?.code === 'EEXIST') throw new Error('P0_RUN_STATE_EXISTS', { cause: error })
        throw error
      }
    } finally {
      rmSync(stagingPath, { force: true })
    }
    return readSafeP0RunState(resolvedPath, resolvedRoot)
  }
  if (!existsSync(path)) throw new Error('P0_RESUME_STATE_MISSING')
  let state
  try {
    state = readSafeP0RunState(resolvedPath, resolvedRoot)
  } catch (error) {
    if (error?.message === 'P0_RUN_STATE_FILE_UNSAFE') throw error
    throw new Error('P0_RESUME_STATE_INVALID', { cause: error })
  }
  const persistedRunId = `sha256:${createHash('sha256').update(runId).digest('hex')}`
  if (state.runId !== persistedRunId) throw new Error('P0_RESUME_MISMATCH: runId')
  if (state.mode !== expected.mode) throw new Error('P0_RESUME_MISMATCH: mode')
  try {
    if (!sameP0Selection(state.selection, normalizedSelection)) {
      throw new Error('P0_RESUME_MISMATCH: selection')
    }
  } catch (error) {
    if (error?.message === 'P0_RESUME_MISMATCH: selection') throw error
    throw new Error('P0_RESUME_STATE_INVALID', { cause: error })
  }
  for (const field of ['commit', 'worktreeFingerprint', 'schemaVersion', 'registryFingerprint']) {
    if (state[field] !== expected[field]) throw new Error(`P0_RESUME_MISMATCH: ${field}`)
  }
  let stateRegistry
  let expectedRegistry
  try {
    stateRegistry = calculateRegistryFingerprint(state.definitions)
    expectedRegistry = calculateRegistryFingerprint(definitions)
  } catch (error) {
    throw new Error('P0_RESUME_REGISTRY_INVALID', { cause: error })
  }
  if (stateRegistry !== P0_REGISTRY_FINGERPRINT
    || expectedRegistry !== P0_REGISTRY_FINGERPRINT
    || registryFingerprint !== P0_REGISTRY_FINGERPRINT) {
    throw new Error('P0_RESUME_REGISTRY_INVALID')
  }
  return state
}

function p0ProfileWorkerMap() {
  return Object.fromEntries(Object.entries(P0_PROFILE_WORKERS).map(([profile, workers]) => (
    [profile, [...workers]]
  )))
}

function sha256Text(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`
}

function runLocalGit(args, cwd = projectRoot) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
    maxBuffer: 10 * 1024 * 1024
  })
  if (result.status !== 0 || result.error) {
    throw new Error(`P0_GIT_IDENTITY_FAILED: ${args.join(' ')}`, {
      cause: result.error ?? new Error(result.stderr)
    })
  }
  return result.stdout
}

export function captureLocalGitIdentity(cwd = projectRoot) {
  const branch = runLocalGit(['branch', '--show-current'], cwd).trim()
  const commit = runLocalGit(['rev-parse', 'HEAD'], cwd).trim().toLowerCase()
  const status = runLocalGit(['status', '--porcelain=v1', '--untracked-files=all'], cwd)
  const diff = runLocalGit(['diff', '--binary', 'HEAD'], cwd)
  if (!branch || !/^[a-f0-9]{40}$/.test(commit)) throw new Error('P0_GIT_IDENTITY_INVALID')
  return {
    branch,
    commit,
    clean: status.trim().length === 0,
    dirtyDiffHash: sha256Text(diff),
    worktreeFingerprint: sha256Text(`${commit}\0${status}\0${diff}`),
    schemaVersion: 1,
    registryFingerprint: P0_REGISTRY_FINGERPRINT,
    profileWorkerMap: p0ProfileWorkerMap()
  }
}

function normalizedP0RunIdentity(identity) {
  const profileWorkerMap = p0ProfileWorkerMap()
  if (!identity || typeof identity !== 'object' || Array.isArray(identity)
    || typeof identity.branch !== 'string' || identity.branch.length === 0
    || !/^[a-f0-9]{40}$/.test(identity.commit)
    || typeof identity.clean !== 'boolean'
    || !/^sha256:[a-f0-9]{64}$/.test(identity.dirtyDiffHash)
    || !/^sha256:[a-f0-9]{64}$/.test(identity.worktreeFingerprint)
    || identity.schemaVersion !== 1
    || identity.registryFingerprint !== P0_REGISTRY_FINGERPRINT
    || JSON.stringify(identity.profileWorkerMap) !== JSON.stringify(profileWorkerMap)) {
    throw new Error('P0_RUN_IDENTITY_INVALID')
  }
  return {
    branch: identity.branch,
    commit: identity.commit,
    clean: identity.clean,
    dirtyDiffHash: identity.dirtyDiffHash,
    worktreeFingerprint: identity.worktreeFingerprint,
    schemaVersion: identity.schemaVersion,
    registryFingerprint: identity.registryFingerprint,
    profileWorkerMap
  }
}

export function assertP0RunIdentityUnchanged(expectedIdentity, currentIdentity, { runRoot } = {}) {
  const expected = normalizedP0RunIdentity(expectedIdentity)
  const current = normalizedP0RunIdentity(currentIdentity)
  for (const field of [
    'branch',
    'commit',
    'clean',
    'dirtyDiffHash',
    'worktreeFingerprint',
    'schemaVersion',
    'registryFingerprint',
    'profileWorkerMap'
  ]) {
    if (JSON.stringify(current[field]) === JSON.stringify(expected[field])) continue
    const reason = `P0_IDENTITY_DRIFT: ${field}`
    if (runRoot) {
      writeCaseResultAtomic(join(runRoot, 'identity-invalid.json'), {
        schemaVersion: 1,
        status: 'INVALID_TEST',
        reasonCode: 'P0_IDENTITY_DRIFT',
        expected: {
          branch: expected.branch,
          commit: expected.commit,
          clean: expected.clean,
          dirtyDiffHash: expected.dirtyDiffHash,
          worktreeFingerprint: expected.worktreeFingerprint
        },
        actual: {
          branch: current.branch,
          commit: current.commit,
          clean: current.clean,
          dirtyDiffHash: current.dirtyDiffHash,
          worktreeFingerprint: current.worktreeFingerprint
        }
      })
    }
    throw new Error(reason)
  }
  return current
}

function p0RunSelection(options) {
  const requestedScope = p0RequestedScope(options.phase)
  return {
    caseIds: [...options.caseIds],
    phases: P0_MATRIX_REQUESTED_PHASES.has(options.phase) ? [options.phase] : [],
    profiles: options.profile ? [options.profile] : [],
    viewports: options.viewport === 'all' ? [] : [options.viewport],
    metadata: {
      values: [
        p0SelectionMetadataHash('P0_SELECTION_PHASE', options.phase),
        p0SelectionMetadataHash('P0_SELECTION_SCOPE', requestedScope)
      ]
    }
  }
}

function p0OwnedEndpoints(matrixDatabase) {
  return {
    api: 'http://127.0.0.1:18086',
    web: 'http://127.0.0.1:5199',
    admin: 'http://127.0.0.1:5200',
    databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${matrixDatabase}`,
    redisHost: '127.0.0.1',
    redisPort: 6379
  }
}

async function readActiveP0Control(artifactBase, runId) {
  const paths = await existingControlPaths(artifactBase, runId)
  if (existsSync(paths.cleanedPath) && !existsSync(paths.ownershipPath)) {
    throw new Error('P0_RESUME_CONTROL_CLEANED')
  }
  let manifest
  try {
    manifest = await readControlJson(paths.ownershipPath)
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_RESUME_CONTROL_MISSING', { cause: error })
  }
  const ownerId = ownerIdForToken(manifest.ownerToken)
  if (manifest.schemaVersion !== 1 || manifest.status !== 'ACTIVE'
    || manifest.runId !== runId || manifest.ownerId !== ownerId) {
    throw new Error('P0_RESUME_CONTROL_INVALID')
  }
  const canonicalDatabase = manifest.database?.canonical
  const matrixDatabase = manifest.database?.matrix
  assertP0DatabaseName(canonicalDatabase)
  assertP0DatabaseName(matrixDatabase)
  return {
    ...paths,
    manifest,
    ownerToken: manifest.ownerToken,
    ownerId,
    canonicalDatabase,
    matrixDatabase
  }
}

export async function prepareP0RunReservation({
  options,
  plan,
  artifactBase,
  inheritedEnv,
  captureIdentity,
  databaseExists,
  nextRunToken,
  nextRandomSuffix,
  infrastructure,
  signal,
  now = () => new Date().toISOString()
}) {
  if (typeof captureIdentity !== 'function' || typeof databaseExists !== 'function'
    || typeof nextRunToken !== 'function' || typeof nextRandomSuffix !== 'function') {
    throw new Error('P0_RESERVATION_DEPENDENCY_INVALID')
  }
  throwIfP0Aborted(signal)
  const basePath = resolve(artifactBase)
  const runRoot = resolveP0RunRoot(basePath, options.runId)
  const composeTarget = createP0ComposeTarget({ artifactBase: basePath })
  const { composeFiles, expectedProject } = composeTarget
  const selection = p0RunSelection(options)
  const resume = typeof options.resume === 'string'
  let active

  if (resume) {
    active = await readActiveP0Control(basePath, options.runId)
    throwIfP0Aborted(signal)
    let controlSelection
    try {
      controlSelection = normalizedP0Selection(active.manifest.selection)
    } catch (error) {
      throw new Error('P0_RESUME_CONTROL_SELECTION_INVALID', { cause: error })
    }
    const runStatePath = join(active.runRoot, 'run-state.json')
    if (!existsSync(runStatePath)) throw new Error('P0_RESUME_STATE_MISSING')
    let persistedState
    try {
      persistedState = readSafeP0RunState(runStatePath, active.runRoot)
    } catch (error) {
      if (error?.message === 'P0_RUN_STATE_FILE_UNSAFE') throw error
      throw new Error('P0_RESUME_STATE_INVALID', { cause: error })
    }
    let stateSelection
    try {
      stateSelection = normalizedP0Selection(persistedState.selection)
    } catch (error) {
      throw new Error('P0_RESUME_STATE_INVALID', { cause: error })
    }
    if (JSON.stringify(controlSelection) !== JSON.stringify(stateSelection)
      || JSON.stringify(controlSelection) !== JSON.stringify(selection)) {
      throw new Error('P0_RESUME_SELECTION_MISMATCH')
    }
  }

  if (resume) {
    await verifyP0ComposeTarget({ artifactBase: basePath })
    throwIfP0Aborted(signal)
  }

  const identity = normalizedP0RunIdentity(await captureIdentity({ signal }))
  throwIfP0Aborted(signal)
  if (options.mode === 'certification' && identity.clean !== true) {
    throw new Error('P0_CERTIFICATION_DIRTY_TREE')
  }

  if (resume) {
    if (active.manifest.mode !== options.mode
      || JSON.stringify(active.manifest.profileWorkerMap) !== JSON.stringify(identity.profileWorkerMap)) {
      throw new Error('P0_RESUME_CONTROL_MISMATCH')
    }
    assertP0RunIdentityUnchanged(active.manifest.identity, identity, { runRoot: active.runRoot })
    const runStatePath = join(active.runRoot, 'run-state.json')
    throwIfP0Aborted(signal)
    const runState = openP0RunState({
      path: runStatePath,
      root: active.runRoot,
      resume: true,
      runId: options.runId,
      mode: options.mode,
      commit: identity.commit,
      worktreeFingerprint: identity.worktreeFingerprint,
      schemaVersion: identity.schemaVersion,
      registryFingerprint: identity.registryFingerprint,
      definitions: P0_CASES,
      selection
    })
    let controlSelection
    try {
      controlSelection = normalizedP0Selection(active.manifest.selection)
    } catch (error) {
      throw new Error('P0_RESUME_CONTROL_SELECTION_INVALID', { cause: error })
    }
    if (JSON.stringify(controlSelection) !== JSON.stringify(normalizedP0Selection(runState.selection))
      || JSON.stringify(controlSelection) !== JSON.stringify(normalizedP0Selection(selection))) {
      throw new Error('P0_RESUME_SELECTION_MISMATCH')
    }
    const endpoints = p0OwnedEndpoints(active.matrixDatabase)
    await runSafetyPreflight({
      ownerId: active.ownerId,
      database: active.matrixDatabase,
      endpoints,
      inheritedEnv,
      infrastructure,
      composeFiles,
      signal
    })
    throwIfP0Aborted(signal)
    return {
      ...active,
      identity,
      profileWorkerMap: identity.profileWorkerMap,
      endpoints,
      databaseUrl: endpoints.databaseUrl,
      runStatePath,
      runState,
      resumePlan: planResume(runState, P0_CASES, artifactP0Selection(selection)),
      composeTarget,
      resumed: true
    }
  }

  if (existsSync(runRoot)) throw new Error('P0_RUN_COLLISION')
  throwIfP0Aborted(signal)
  await ensureP0ComposeTarget({ artifactBase: basePath })
  throwIfP0Aborted(signal)
  const ownerToken = nextRunToken()
  const ownerId = ownerIdForToken(ownerToken)
  const canonicalDatabase = databaseSegmentForAttempt({
    phase: 'canonical',
    attempt: 1,
    randomSuffix: nextRandomSuffix()
  })
  const matrixDatabase = databaseSegmentForAttempt({
    phase: 'preflight',
    attempt: 1,
    randomSuffix: nextRandomSuffix()
  })
  const endpoints = p0OwnedEndpoints(matrixDatabase)
  await runSafetyPreflight({
    ownerId,
    database: matrixDatabase,
    endpoints,
    inheritedEnv,
    infrastructure,
    composeFiles,
    signal
  })
  throwIfP0Aborted(signal)
  for (const segmentName of [canonicalDatabase, matrixDatabase]) {
    throwIfP0Aborted(signal)
    if (await databaseExists(segmentName, {
      composeFiles,
      expectedProject,
      signal
    })) throw new Error('P0_DATABASE_COLLISION')
    throwIfP0Aborted(signal)
  }
  throwIfP0Aborted(signal)
  const control = await createControlManifest({
    artifactBase: basePath,
    runId: options.runId,
    runToken: ownerToken,
    mode: options.mode,
    selection,
    database: { canonical: canonicalDatabase, matrix: matrixDatabase },
    identity,
    profileWorkerMap: identity.profileWorkerMap,
    now
  })
  throwIfP0Aborted(signal)
  const runStatePath = join(control.runRoot, 'run-state.json')
  throwIfP0Aborted(signal)
  const runState = openP0RunState({
    path: runStatePath,
    resume: false,
    runId: options.runId,
    mode: options.mode,
    commit: identity.commit,
    worktreeFingerprint: identity.worktreeFingerprint,
    schemaVersion: identity.schemaVersion,
    registryFingerprint: identity.registryFingerprint,
    definitions: P0_CASES,
    selection
  })
  return {
    ...control,
    ownerToken,
    ownerId,
    canonicalDatabase,
    matrixDatabase,
    identity,
    profileWorkerMap: identity.profileWorkerMap,
    endpoints,
    databaseUrl: endpoints.databaseUrl,
    runStatePath,
    runState,
    resumePlan: planResume(runState, P0_CASES, artifactP0Selection(selection)),
    composeTarget,
    resumed: false
  }
}

const P0_MATRIX_PHASES = new Set([
  'ui-core',
  'order-trigger',
  'funding',
  'liquidation',
  'source',
  'resilience',
  'ui',
  'selected'
])

const P0_CONTROL_PHASES = new Set([
  'preflight',
  'canonical',
  'authority',
  'report'
])

const P0_PROFILE_LIFECYCLE_OPERATIONS = [
  'stopProfileBackend',
  'assertProfilePortFree',
  'startProfileBackend',
  'waitForProfileHealth',
  'waitForProfileBusinessEndpoint',
  'verifyProfileDatabaseIdentity'
]

function supportsP0ProfileLifecycle(operations) {
  const available = P0_PROFILE_LIFECYCLE_OPERATIONS.filter(
    (name) => typeof operations?.[name] === 'function'
  )
  if (available.length === 0) return false
  if (available.length !== P0_PROFILE_LIFECYCLE_OPERATIONS.length) {
    throw new Error('P0_PROFILE_LIFECYCLE_INCOMPLETE')
  }
  return true
}

async function activateP0Profile({ phase, profile, attempt, context, operations, signal }) {
  throwIfP0Aborted(signal)
  await operations.stopProfileBackend(context, profile, { signal })
  throwIfP0Aborted(signal)
  await operations.assertProfilePortFree(18086, context, profile, { signal })
  throwIfP0Aborted(signal)
  const phaseDatabase = typeof operations.prepareProfileDatabase === 'function'
    ? await operations.prepareProfileDatabase({ phase, attempt, signal }, context)
    : {
        segmentName: context.matrixDatabase,
        databaseUrl: context.databaseUrl
      }
  throwIfP0Aborted(signal)
  const databaseUrl = phaseDatabase?.databaseUrl
  if (typeof databaseUrl !== 'string') throw new Error('P0_PROFILE_DATABASE_URL_REQUIRED')
  context.activeDatabaseSegment = phaseDatabase.segmentName
  context.activeDatabaseUrl = databaseUrl
  const environment = buildBackendEnvironment(profile, {
    DATABASE_URL: databaseUrl,
    SPRING_DATASOURCE_URL: databaseUrl,
    SERVER_PORT: '18086'
  }, context.inheritedEnv ?? {})
  const backend = await operations.startProfileBackend({
    phase,
    profile,
    attempt,
    databaseUrl,
    environment,
    signal
  }, context, { signal })
  throwIfP0Aborted(signal)
  await operations.waitForProfileHealth(backend, context, profile, { signal })
  throwIfP0Aborted(signal)
  await operations.waitForProfileBusinessEndpoint(backend, context, profile, { signal })
  throwIfP0Aborted(signal)
  await operations.verifyProfileDatabaseIdentity(
    backend,
    context,
    profile,
    phaseDatabase,
    { signal }
  )
  throwIfP0Aborted(signal)
  return backend
}

function isPlainP0ControlOutcome(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function validateP0ControlOutcome(
  phase,
  outcome,
  errorCode = 'P0_CONTROL_RESULT_INVALID'
) {
  const invalid = () => { throw new Error(errorCode) }
  if (!isPlainP0ControlOutcome(outcome)
    || outcome.status !== 'PASS'
    || Object.keys(outcome).every((key) => key === 'status')) {
    invalid()
  }
  if (phase === 'report') {
    const identity = outcome.identity
    if (outcome.kind !== 'P0_REPORT_BOUNDARY'
      || !['CONTROL', 'MATRIX'].includes(outcome.scope)
      || outcome.finalWriter !== 'PENDING'
      || !isPlainP0ControlOutcome(identity)
      || typeof identity.runId !== 'string'
      || identity.runId.length === 0
      || typeof identity.ownerId !== 'string'
      || !/^[a-f0-9]{64}$/.test(identity.ownerId)
      || typeof identity.reportPath !== 'string'
      || !isAbsolute(identity.reportPath)
      || identity.reportPath !== resolve(identity.reportPath)) {
      invalid()
    }
  }
  return outcome
}

export async function executeP0ReportPhaseBoundary({
  context,
  plan,
  writePhaseReport,
  signal
} = {}) {
  throwIfP0Aborted(signal)
  if (!isPlainP0ControlOutcome(context)
    || typeof context.runRoot !== 'string'
    || context.runRoot.length === 0
    || !isAbsolute(context.runRoot)
    || context.runRoot !== resolve(context.runRoot)
    || !isPlainP0ControlOutcome(plan)
    || !['CONTROL', 'MATRIX'].includes(plan.scope)) {
    throw new Error('P0_REPORT_PHASE_CONTEXT_INVALID')
  }
  const runId = context.options?.runId ?? context.runId
  if (typeof runId !== 'string'
    || runId.length === 0
    || typeof context.ownerId !== 'string'
    || !/^[a-f0-9]{64}$/.test(context.ownerId)) {
    throw new Error('P0_REPORT_PHASE_CONTEXT_INVALID')
  }
  const reportPath = resolve(context.runRoot, 'report.json')
  assertContainedPath(context.runRoot, reportPath, 'P0_REPORT_ARTIFACT_ESCAPE')
  if (typeof writePhaseReport !== 'function') {
    throw new Error('P0_REPORT_PHASE_WRITER_REQUIRED')
  }
  const expected = {
    status: 'PASS',
    kind: 'P0_REPORT_BOUNDARY',
    scope: plan.scope,
    finalWriter: 'PENDING',
    identity: {
      runId,
      ownerId: context.ownerId,
      reportPath
    }
  }
  const result = await writePhaseReport(context, plan, { signal })
  throwIfP0Aborted(signal)
  const expectedKeys = ['status', 'kind', 'scope', 'finalWriter', 'identity']
  const expectedIdentityKeys = ['runId', 'ownerId', 'reportPath']
  const exactKeys = (value, keys) => {
    const actual = Reflect.ownKeys(value)
    return actual.length === keys.length && keys.every((key) => Object.hasOwn(value, key))
  }
  if (!isPlainP0ControlOutcome(result)
    || !exactKeys(result, expectedKeys)
    || !isPlainP0ControlOutcome(result.identity)
    || !exactKeys(result.identity, expectedIdentityKeys)
    || result.status !== expected.status
    || result.kind !== expected.kind
    || result.scope !== expected.scope
    || result.finalWriter !== expected.finalWriter
    || result.identity.runId !== expected.identity.runId
    || result.identity.ownerId !== expected.identity.ownerId
    || result.identity.reportPath !== expected.identity.reportPath) {
    throw new Error('P0_REPORT_PHASE_RESULT_INVALID')
  }
  return result
}

export async function executeP0PlanPhases({
  plan,
  context,
  operations,
  signal,
  controlResults = []
}) {
  if (!plan || !Array.isArray(plan.phases)) throw new Error('P0_PLAN_INVALID')
  if (!Array.isArray(controlResults)) throw new Error('P0_CONTROL_RESULTS_INVALID')
  const recordControlResult = (phase, outcome, evidence = outcome) => {
    const validated = validateP0ControlOutcome(phase, outcome)
    controlResults.push({
      phase,
      status: validated.status,
      evidence
    })
  }
  let canonicalChildren = 0
  let matrixPhases = 0
  for (const phase of plan.phases) {
    throwIfP0Aborted(signal)
    if (phase === 'cleanup') continue
    await context.assertIdentity?.({ signal })
    throwIfP0Aborted(signal)
    if (phase === 'preflight') {
      const evidence = await operations.runPreflight(context, plan, { signal })
      throwIfP0Aborted(signal)
      recordControlResult(phase, evidence)
      continue
    }
    if (phase === 'canonical') {
      await operations.assertRedisOwnership(context, 'before-canonical', { signal })
      throwIfP0Aborted(signal)
      await operations.stopParentBackend(context, { signal })
      throwIfP0Aborted(signal)
      await operations.assertBusinessPortsFree([18086, 5199, 5200], context, { signal })
      throwIfP0Aborted(signal)
      const invocation = buildCanonicalChildInvocation({
        scriptPath: context.scriptPath,
        ownerToken: context.ownerToken,
        ownerId: context.ownerId,
        database: context.canonicalDatabase,
        composeIdentity: context.composeIdentity,
        composeTarget: context.composeTarget,
        runId: context.options?.runId ?? context.runId,
        runRoot: context.runRoot,
        inheritedEnv: context.inheritedEnv
      })
      const child = await operations.runCanonicalChild(invocation, context, { signal })
      throwIfP0Aborted(signal)
      if (!isPlainP0ControlOutcome(child)
        || child.status !== 0
        || child.signal !== null) {
        throw new Error('P0_CANONICAL_CHILD_FAILED')
      }
      canonicalChildren += 1
      const verification = await operations.verifyCanonicalChildCleanup(
        child,
        context,
        { signal }
      )
      throwIfP0Aborted(signal)
      await operations.assertRedisOwnership(context, 'after-canonical', { signal })
      throwIfP0Aborted(signal)
      await operations.assertBusinessPortsFree([18086, 5199, 5200], context, { signal })
      throwIfP0Aborted(signal)
      recordControlResult(phase, verification, {
        child: child === undefined ? null : child,
        verification: verification === undefined ? null : verification
      })
      continue
    }
    if (phase === 'authority') {
      const evidence = await operations.runAuthority(context, plan, { signal })
      throwIfP0Aborted(signal)
      recordControlResult(phase, evidence)
      continue
    }
    if (P0_MATRIX_PHASES.has(phase)) {
      await operations.runMatrixPhase(phase, context, plan, { signal })
      throwIfP0Aborted(signal)
      matrixPhases += 1
      continue
    }
    if (phase === 'report') {
      const evidence = await operations.writeReport(context, plan, { signal })
      throwIfP0Aborted(signal)
      recordControlResult(phase, evidence)
      continue
    }
    throw new Error(`P0_PHASE_NOT_IMPLEMENTED: ${phase}`)
  }
  return { canonicalChildren, matrixPhases }
}

const P0_SECURITY_GUARD_CLASSES = [
  'DemoExecutionGuardTest',
  'ExecutionAdapterApplicationContextTest',
  'ExecutionModeStartupValidatorTest',
  'ProductionConfigurationSafetyTest',
  'ProviderModeApplicationContextTest',
  'DatabaseItConfigurationContractTest'
]

const P0_DATABASE_IT_CLASSES = [
  'PostgresDatabaseIT',
  'V46V47EmptyDatabaseIT',
  'V45ToV47DemoResetIT',
  'Task5PostgresFullFillIT',
  'Task6PostgresSpotIT',
  'Task7PostgresDemoLifecycleIT',
  'Task8PostgresTradingSettingsIT',
  'Task9PostgresPerpetualOrderIT',
  'Task10PostgresProtectionIT',
  'Task11PostgresFundingIT',
  'DemoTradingConcurrencyIT',
  'PerpetualPositionConcurrencyIT',
  'ProtectionOrderConcurrencyIT',
  'FundingLiquidationConcurrencyIT'
]
const P0_TESTCONTAINERS_DOCKER_API_VERSION = '1.44'

export async function runP0Preflight({
  projectRoot: p0ProjectRoot,
  gateOutput,
  databaseUrl,
  now = () => new Date().toISOString(),
  signal,
  operations
}) {
  throwIfP0Aborted(signal)
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
  const backendDirectory = join(p0ProjectRoot, 'backend')
  const scriptsDirectory = join(p0ProjectRoot, 'scripts')
  const commands = [
    { id: 'backend-unit', command: maven, args: ['test'], cwd: backendDirectory },
    {
      id: 'node-contracts',
      command: process.execPath,
      args: [
        '--test',
        join(scriptsDirectory, 'smoke-usdt-demo-browser.test.mjs'),
        join(scriptsDirectory, 'p0-user-trading-runner.test.mjs')
      ],
      cwd: p0ProjectRoot
    },
    { id: 'web-test', command: npm, args: ['run', 'web:test'], cwd: p0ProjectRoot },
    { id: 'web-build', command: npm, args: ['run', 'web:build'], cwd: p0ProjectRoot },
    {
      id: 'admin-test',
      command: npm,
      args: ['--prefix', join(p0ProjectRoot, 'apps', 'admin'), 'test'],
      cwd: p0ProjectRoot
    },
    { id: 'admin-build', command: npm, args: ['run', 'admin:build'], cwd: p0ProjectRoot },
    { id: 'architecture', command: npm, args: ['run', 'verify:architecture'], cwd: p0ProjectRoot },
    {
      id: 'security-guards',
      command: maven,
      args: [`-Dtest=${P0_SECURITY_GUARD_CLASSES.join(',')}`, 'test'],
      cwd: backendDirectory
    }
  ]
  const runGate = async (command) => {
    throwIfP0Aborted(signal)
    const result = await operations.runCommand({ ...command, signal })
    await operations.recordGate(command.id, result)
    throwIfP0Aborted(signal)
    if (result?.status !== 0) throw new Error(`P0_PREFLIGHT_GATE_FAILED: ${command.id}`)
    return result
  }
  for (const command of commands) await runGate(command)

  const invocationStartedAt = now()
  if (typeof invocationStartedAt !== 'string'
    || new Date(invocationStartedAt).toISOString() !== invocationStartedAt) {
    throw new Error('P0_PREFLIGHT_TIMESTAMP_INVALID')
  }
  const reports = join(backendDirectory, 'target', 'surefire-reports')
  const itCommand = {
    id: 'database-concurrency-it',
    command: maven,
    args: [
      `-Dapi.version=${P0_TESTCONTAINERS_DOCKER_API_VERSION}`,
      `-Dtest=${P0_DATABASE_IT_CLASSES.join(',')}`,
      'test'
    ],
    cwd: backendDirectory,
    env: { DATABASE_PASSWORD: 'database-it-non-secret-password' }
  }
  await runGate(itCommand)
  const surefireCommand = {
    id: 'surefire-gate',
    command: process.execPath,
    args: [
      join(scriptsDirectory, 'p0-user-trading-artifacts.mjs'),
      'verify-surefire',
      `--reports=${reports}`,
      `--classes=${P0_DATABASE_IT_CLASSES.join(',')}`,
      `--started-at=${invocationStartedAt}`,
      `--output=${gateOutput}`
    ],
    cwd: p0ProjectRoot
  }
  await runGate(surefireCommand)

  let backend
  let executionFailure
  let executionFailed = false
  try {
    backend = await operations.startOwnedBackend({
      profile: 'UI_CORE',
      databaseUrl,
      signal,
      environment: buildBackendEnvironment('UI_CORE', {
        DATABASE_URL: databaseUrl,
        SPRING_DATASOURCE_URL: databaseUrl,
        SERVER_PORT: '18086'
      })
    })
    await operations.waitForBackendHealth(
      backend,
      'http://127.0.0.1:18086/actuator/health',
      signal
    )
    throwIfP0Aborted(signal)
    await operations.waitForBusinessEndpoint(
      backend,
      'http://127.0.0.1:18086/api/market/symbols',
      signal
    )
    throwIfP0Aborted(signal)
    await operations.verifyBackendDatabaseIdentity?.(backend, { databaseUrl, signal })
    throwIfP0Aborted(signal)
    const contractEnvironment = {
      OPENAPI_SOURCE_URL: 'http://127.0.0.1:18086/v3/api-docs'
    }
    await runGate({
      id: 'contract-export',
      command: npm,
      args: ['run', 'contract:export'],
      cwd: p0ProjectRoot,
      env: contractEnvironment
    })
    await runGate({
      id: 'contract-check',
      command: npm,
      args: ['run', 'contract:check'],
      cwd: p0ProjectRoot,
      env: contractEnvironment
    })
  } catch (error) {
    executionFailure = error
    executionFailed = true
  }
  const cleanupSignal = AbortSignal.timeout(30000)
  const failures = executionFailed ? [executionFailure] : []
  try {
    await operations.stopOwnedBackend(backend, { signal: cleanupSignal })
  } catch (error) {
    failures.push(error)
  }
  try {
    await operations.assertBusinessPortsFree([18086], { signal: cleanupSignal })
  } catch (error) {
    failures.push(error)
  }
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) {
    throw new AggregateError(failures, 'P0_PREFLIGHT_AND_CLEANUP_FAILED')
  }
  return {
    status: 'PASS',
    invocationStartedAt,
    gates: 12,
    itClasses: P0_DATABASE_IT_CLASSES.length,
    guardClasses: P0_SECURITY_GUARD_CLASSES.length
  }
}

export async function executeP0SuiteLifecycle({ options, operations }) {
  const controller = new AbortController()
  let interruptionError
  const removeSignalHandlers = options.phase === 'cleanup'
    ? async () => {}
    : operations.installSignalHandlers((signal) => {
        if (interruptionError) return
        interruptionError = new Error(`P0_INTERRUPTED: ${signal}`)
        controller.abort(interruptionError)
      })
  let prepared
  let execution = null
  let report = null
  let cleanup = null
  let failure
  try {
    if (options.phase !== 'cleanup') {
      prepared = await operations.prepare(options, { signal: controller.signal })
      if (interruptionError) throw interruptionError
      execution = await operations.execute(prepared, { signal: controller.signal })
      if (interruptionError) throw interruptionError
      report = await operations.writeReport(execution, prepared, { signal: controller.signal })
      if (interruptionError) throw interruptionError
    }
  } catch (error) {
    failure = interruptionError ?? error
  } finally {
    try {
      const cleanupSignal = AbortSignal.timeout(30000)
      cleanup = await operations.cleanup(prepared, {
        error: failure,
        interrupted: Boolean(interruptionError),
        signal: cleanupSignal
      })
    } catch (error) {
      failure = failure
        ? new AggregateError([failure, error], 'P0_RUN_AND_CLEANUP_FAILED')
        : error
    }
    try {
      await removeSignalHandlers()
    } catch (error) {
      failure = failure
        ? new AggregateError([failure, error], 'P0_RUN_AND_SIGNAL_CLEANUP_FAILED')
        : error
    }
  }
  if (failure) throw failure
  return { execution, report, cleanup }
}

async function bootstrapIdentityAndAccount() {
  await waitFor(
    async () => (await runDbSql(`
      SELECT count(*)
      FROM auth.users
      WHERE lower(email) = lower('${sqlLiteral(adminEmail)}')
        AND role = 'ADMIN'
        AND status = 'ACTIVE'
    `)) === '1',
    'canonical admin bootstrap user', 10000)
  for (const authority of CANONICAL_ADMIN_AUTHORITIES) await grantCanonicalAdminAuthority(authority)
  adminToken = await login(adminEmail, adminPassword, true)
  assert(
    CANONICAL_ADMIN_AUTHORITIES.every((authority) => adminAuthorities.includes(authority)),
    'canonical admin must receive every required journey authority'
  )
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: { email: userEmail, phone: null, password: userPassword }
  })
  userId = registration.userId
  userToken = await login(userEmail, userPassword)
  const accounts = await api('/api/accounts', { token: userToken })
  const demo = accounts.find((account) => account.accountType === 'DEMO' && account.status === 'ACTIVE')
  assert(demo, 'registration must create one ACTIVE DEMO account')
  accountId = demo.id
  const databaseIdentity = await runDbSql(`
    SELECT current_database() || '|' || (
      SELECT count(*)
      FROM core.trading_accounts
      WHERE id = '${sqlLiteral(accountId)}'
        AND user_id = '${sqlLiteral(userId)}'
        AND account_type = 'DEMO'
    )
  `)
  assert(databaseIdentity === `${smokeDatabase}|1`, 'backend API and psql assertions must target the same dedicated smoke database')
  const [summary, wallets, assetLedger] = await Promise.all([
    api(`/api/accounts/${accountId}/summary`, { token: userToken }),
    api(`/api/accounts/${accountId}/wallet-balances`, { token: userToken }),
    api(`/api/accounts/${accountId}/asset-ledger`, { token: userToken })
  ])
  const spotUsdt = wallets.find((wallet) => wallet.walletType === 'SPOT' && wallet.asset === 'USDT')
  assertNear(number(spotUsdt?.total), INITIAL_SPOT_USDT, 0.000001, 'initial Spot USDT')
  assertNear(number(summary.balance), INITIAL_PERP_USDT, 0.000001, 'initial Perpetual USDT')
  assert(assetLedger.some((entry) => entry.entryType === 'DEMO_INIT'), 'Spot asset ledger must contain DEMO_INIT')
  return { userId, accountId, spotUsdt: spotUsdt.total, perpUsdt: summary.balance }
}

async function grantCanonicalAdminAuthority(authority) {
  assert(CANONICAL_ADMIN_AUTHORITIES.includes(authority), 'canonical admin authority must stay narrowly scoped')
  const bound = await runDbSql(`
    WITH admin_user AS (
      SELECT id FROM auth.users WHERE lower(email) = lower('${sqlLiteral(adminEmail)}')
    ), role_upsert AS (
      INSERT INTO admin.roles (role_name, role_code, enabled, description)
      VALUES ('P0 canonical funding admin', 'p0-canonical-funding-admin', true, 'Isolated canonical smoke authority')
      ON CONFLICT (role_code) DO UPDATE SET enabled = true, updated_at = now()
      RETURNING id
    ), menu_upsert AS (
      INSERT INTO admin.menus (menu_name, permission_key, menu_type, enabled)
      VALUES ('P0 canonical funding configuration', '${sqlLiteral(authority)}', 'BUTTON', true)
      ON CONFLICT (permission_key) DO UPDATE SET enabled = true, updated_at = now()
      RETURNING id
    ), permission_upsert AS (
      INSERT INTO admin.role_menu_permissions (role_id, menu_id, buttons, enabled)
      SELECT role_upsert.id, menu_upsert.id, '[]'::jsonb, true
      FROM role_upsert CROSS JOIN menu_upsert
      ON CONFLICT (role_id, menu_id) DO UPDATE SET enabled = true, updated_at = now()
      RETURNING role_id
    ), user_role_upsert AS (
      INSERT INTO admin.user_roles (user_id, role_id)
      SELECT admin_user.id, permission_upsert.role_id
      FROM admin_user CROSS JOIN permission_upsert
      ON CONFLICT (user_id, role_id) DO UPDATE SET user_id = excluded.user_id
      RETURNING 1
    )
    SELECT count(*) FROM user_role_upsert
  `)
  assert(bound === '1', 'canonical admin authority binding must affect exactly one user')
}

async function login(email, password, admin = false) {
  const response = await api('/api/auth/login', { method: 'POST', body: { email, password } })
  assert(response.accessToken, `login must return accessToken for ${email}`)
  if (admin) {
    adminRefreshToken = response.refreshToken
    adminAuthorities = response.authorities ?? []
  }
  return response.accessToken
}

async function assertP0Catalog() {
  const symbols = await api('/api/market/symbols')
  const spot = symbols.filter((symbol) => symbol.productType === 'CRYPTO_SPOT' && symbol.tradable)
  const perp = symbols.filter((symbol) => symbol.productType === 'LINEAR_PERP' && symbol.tradable)
  assert(JSON.stringify(spot.map((symbol) => symbol.symbol).sort()) === JSON.stringify(P0_SPOT_SYMBOLS.toSorted()), 'Spot symbol set must match the canonical P0 set')
  assert(JSON.stringify(perp.map((symbol) => symbol.symbol).sort()) === JSON.stringify(P0_PERP_SYMBOLS.toSorted()), 'Perp symbol set must match the canonical P0 set')
  assert(spot.length === 5, `expected exactly 5 Spot symbols, got ${spot.length}`)
  assert(perp.length === 5, `expected exactly 5 Perp symbols, got ${perp.length}`)
  for (const productType of FORBIDDEN_PRODUCTS) {
    await expectApiError(`/api/trading/orders`, {
      method: 'POST', token: userToken, body: orderBody({
        symbol: forbiddenSymbol(productType), side: 'BUY', orderType: 'MARKET', quantity: '1', quantityUnit: 'BASE'
      })
    }, ['PRODUCT_NOT_ALLOWED', 'SYMBOL_NOT_ALLOWED', 'SYMBOL_NOT_FOUND', 'SYMBOL_NOT_TRADABLE'])
  }
  return { spot: spot.map((symbol) => symbol.symbol), perp: perp.map((symbol) => symbol.symbol), forbidden: FORBIDDEN_PRODUCTS }
}

function forbiddenSymbol(productType) {
  if (productType === 'FOREX') return 'EURUSD'
  if (productType === 'INVERSE_PERP') return 'BTCUSD-PERP'
  return 'BTCUSDT-OPTION'
}

async function snapshotProviderBindings() {
  const providers = await adminApi('/api/admin/market/data-providers')
  const providerCodes = new Map(providers.map((provider) => [provider.id, provider.code]))
  const page = await adminApi('/api/admin/market/symbols?page=0&size=2000')
  const adminSymbols = page.content ?? page.items ?? page.records ?? page
  for (const symbolName of [...P0_SPOT_SYMBOLS, ...P0_PERP_SYMBOLS]) {
    const symbol = adminSymbols.find((candidate) => candidate.symbol === symbolName)
    assert(symbol, `Admin symbol id missing for ${symbolName}`)
    const bindings = await adminApi(`/api/admin/market/symbols/${symbol.id}/provider-bindings`)
    for (const binding of bindings) {
      const providerCode = providerCodes.get(binding.providerId)
      if (!providerCode || !['binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp'].includes(providerCode)) continue
      bindingRestores.push({ symbol, providerCode, original: { ...binding } })
    }
  }
  assert(bindingRestores.length === 30, `expected 30 P0 provider bindings, got ${bindingRestores.length}`)
  return { bindings: bindingRestores.length }
}

async function applySourceMode(mode) {
  assert(mode, 'source mode is required')
  sourceModeStartedAtMs = Date.now()
  for (const fixture of bindingRestores) {
    await updateBinding(fixture, mode.enabled.has(fixture.providerCode))
  }
}

async function updateBinding(fixture, enabled) {
  const binding = fixture.original
  return adminApi(`/api/admin/market/symbols/${fixture.symbol.id}/provider-bindings/${binding.id}`, {
    method: 'PUT',
    body: {
      providerId: binding.providerId,
      providerInstrumentId: binding.providerInstrumentId,
      providerSymbol: binding.providerSymbol,
      priority: binding.priority,
      enabled,
      configJson: binding.configJson ?? '{}'
    }
  })
}

async function restoreProviderBindings() {
  if (!adminToken || bindingRestores.length === 0) return
  const failures = []
  for (const fixture of bindingRestores) {
    try {
      await updateBinding(fixture, fixture.original.enabled)
    } catch (error) {
      failures.push(error)
    }
  }
  try {
    await verifyProviderBindingsRestored()
  } catch (error) {
    failures.push(error)
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, `failed to restore ${failures.length} provider binding operation(s)`)
  }
}

async function verifyProviderBindingsRestored() {
  const bySymbol = new Map()
  for (const fixture of bindingRestores) {
    if (!bySymbol.has(fixture.symbol.id)) {
      bySymbol.set(
        fixture.symbol.id,
        await adminApi(`/api/admin/market/symbols/${fixture.symbol.id}/provider-bindings`)
      )
    }
    const restored = bySymbol.get(fixture.symbol.id).find((binding) => binding.id === fixture.original.id)
    assert(restored, `restored provider binding missing for ${fixture.symbol.symbol}/${fixture.providerCode}`)
    assert(
      Boolean(restored.enabled) === Boolean(fixture.original.enabled),
      `provider binding restore mismatch for ${fixture.symbol.symbol}/${fixture.providerCode}`
    )
  }
}

async function assertBundleSources(modeId) {
  const mode = SOURCE_MODES.find((candidate) => candidate.id === modeId)
  const startedAt = new Date().toISOString()
  const bundle = await waitFor(async () => {
    const [spotQuote, spotDepth, spotTrades, perpQuote, perpDepth, perpTrades, perpReference] = await Promise.all([
      api(`/api/market/quotes/${SPOT_SYMBOL}`),
      api(`/api/market/order-book/${SPOT_SYMBOL}`),
      api(`/api/market/trades/${SPOT_SYMBOL}`),
      api(`/api/market/quotes/${PERP_SYMBOL}`),
      api(`/api/market/order-book/${PERP_SYMBOL}`),
      api(`/api/market/trades/${PERP_SYMBOL}`),
      api(`/api/market/perpetuals/${PERP_SYMBOL}/reference`)
    ])
    const spotTradeSource = Array.isArray(spotTrades) ? spotTrades[0] : spotTrades
    const perpTradeSource = Array.isArray(perpTrades) ? perpTrades[0] : perpTrades
    for (const [label, payload] of [
      ['spot quote', spotQuote], ['spot depth', spotDepth], ['spot trades', spotTradeSource],
      ['perp quote', perpQuote], ['perp depth', perpDepth], ['perp trades', perpTradeSource], ['perp reference', perpReference]
    ]) assertSourceMetadata(payload, label)
    const spotProviders = {
      quote: spotQuote.providerCode,
      depth: spotDepth.providerCode,
      trades: spotTradeSource.providerCode
    }
    const perpProviders = {
      quote: perpQuote.providerCode,
      depth: perpDepth.providerCode,
      trades: perpTradeSource.providerCode,
      reference: perpReference.providerCode
    }
    assert(new Set(Object.values(spotProviders)).size === 1, `Spot bundle must not mix providers: ${JSON.stringify(spotProviders)}`)
    assert(new Set(Object.values(perpProviders)).size === 1, `Perp bundle must not mix providers: ${JSON.stringify(perpProviders)}`)
    assert(mode.expectedSpot.includes(spotQuote.providerCode), `${modeId} unexpected Spot provider ${spotQuote.providerCode}`)
    assert(mode.expectedPerp.includes(perpQuote.providerCode), `${modeId} unexpected Perp provider ${perpQuote.providerCode}`)
    return { spotQuote, perpQuote }
  }, `${modeId} source bundle consistency`, 30000)
  const { spotQuote, perpQuote } = bundle
  const [spotHigherPriorityFailures, perpHigherPriorityFailures] = await Promise.all([
    assertHigherPriorityProvidersUnavailable(mode, 'spot', spotQuote, startedAt),
    assertHigherPriorityProvidersUnavailable(mode, 'perp', perpQuote, startedAt)
  ])
  const evidence = {
    mode: modeId,
    spot: pickSourceMetadata(spotQuote),
    perp: pickSourceMetadata(perpQuote),
    spotHigherPriorityFailures,
    perpHigherPriorityFailures
  }
  sourceEvidence.push(evidence)
  return evidence
}

function assertSourceMetadata(payload, label) {
  assert(payload && typeof payload === 'object', `${label} payload is required`)
  for (const field of SOURCE_METADATA_FIELDS) {
    assert(payload[field] !== undefined && payload[field] !== null, `${label} missing source metadata ${field}`)
  }
  assert(['PUBLIC_EXTERNAL', 'LOCAL_SIMULATED'].includes(payload.sourceMode), `${label} invalid sourceMode ${payload.sourceMode}`)
  assert(Number.isFinite(Date.parse(payload.asOf)), `${label} asOf must be an instant`)
  assert(Number.isFinite(Date.parse(payload.expiresAt)), `${label} expiresAt must be an instant`)
  assert(payload.stale === false, `${label} source must be fresh`)
}

function pickSourceMetadata(payload) {
  return Object.fromEntries(SOURCE_METADATA_FIELDS.map((field) => [field, payload[field]]))
}

async function assertHigherPriorityProvidersUnavailable(mode, product, quote, startedAt) {
  const priorities = product === 'spot' ? mode.expectedSpot : mode.expectedPerp
  const actualIndex = priorities.indexOf(quote.providerCode)
  assert(actualIndex >= 0, `${mode.id} ${product} selected provider ${quote.providerCode} is outside its enabled priority list`)
  const higherPriorityCodes = priorities.slice(0, actualIndex)
  if (higherPriorityCodes.length === 0) return []

  const requestedAtMs = Date.parse(startedAt)
  const startedAtMs = sourceModeStartedAtMs > 0
    ? Math.min(requestedAtMs, sourceModeStartedAtMs)
    : requestedAtMs
  const unavailable = await waitFor(async () => {
    const providers = await adminApi('/api/admin/market/data-providers')
    return higherPriorityCodes.map((code) => {
      const provider = providers.find((candidate) => candidate.code === code)
      assert(provider, `${mode.id} ${product} missing health evidence for higher-priority provider ${code}`)
      const lastFailureAtMs = Date.parse(provider.lastFailureAt)
      const lastSuccessAtMs = Date.parse(provider.lastSuccessAt)
      assert(
        String(provider.healthStatus).toUpperCase() === 'DOWN'
          && number(provider.failureCount) > 0
          && Number.isFinite(lastFailureAtMs)
          && lastFailureAtMs >= startedAtMs - 1000
          && (!Number.isFinite(lastSuccessAtMs) || lastSuccessAtMs <= lastFailureAtMs),
        `${mode.id} ${product} selected ${quote.providerCode} without a current DOWN state and latest failure for higher-priority ${code}`
      )
      return {
        code,
        healthStatus: provider.healthStatus,
        failureCount: provider.failureCount,
        lastFailureAt: provider.lastFailureAt,
        lastSuccessAt: provider.lastSuccessAt
      }
    })
  }, `${mode.id} ${product} higher-priority provider health`, 5000)
  sourceEvidence.push({
    mode: mode.id,
    product,
    actual: pickSourceMetadata(quote),
    higherPriorityProvidersUnavailable: unavailable
  })
  return unavailable
}

async function runFullP0Journey() {
  const spot = await runSpotJourney()
  const transfer = await runTransferJourney()
  const perpetual = await runPerpetualJourney()
  const protection = await runProtectionAndFundingJourney()
  const liquidation = await runLiquidationJourney()
  const reset = await runResetLifecycleJourney()
  await assertBrowserObservedJourneyEvents()
  return { spot, transfer, perpetual, protection, liquidation, reset }
}

async function runSpotJourney() {
  const quote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
  const rules = await api(`/api/market/symbols/${SPOT_SYMBOL}/rules`)
  const last = number(quote.mid ?? quote.last ?? quote.ask)
  const tick = number(rules.tickSize ?? 0.1)

  const marketBuy = await createOrder('MARKET BUY QUOTE', {
    symbol: SPOT_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '100', quantityUnit: 'QUOTE', marginMode: 'CASH'
  })
  assert(marketBuy.status === 'FILLED', `Spot MARKET BUY(QUOTE) must fill, got ${marketBuy.status}`)

  const marketSell = await createOrder('MARKET SELL BASE', {
    symbol: SPOT_SYMBOL, side: 'SELL', orderType: 'MARKET', quantity: '0.0001', quantityUnit: 'BASE', marginMode: 'CASH'
  })
  assert(marketSell.status === 'FILLED', `Spot MARKET SELL(BASE) must fill, got ${marketSell.status}`)

  const immediate = await createOrder('LIMIT immediate', {
    symbol: SPOT_SYMBOL,
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.0001',
    quantityUnit: 'BASE',
    price: aligned(last * 1.02, tick, 'ceil'),
    marginMode: 'CASH'
  })
  assert(immediate.status === 'FILLED', `marketable Spot LIMIT must fill, got ${immediate.status}`)

  const pending = await createOrder('LIMIT pending cancel', {
    symbol: SPOT_SYMBOL,
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '0.001',
    quantityUnit: 'BASE',
    price: aligned(last * 0.5, tick, 'floor'),
    marginMode: 'CASH'
  })
  assert(['PENDING', 'ACCEPTED', 'WORKING'].includes(pending.status), `non-marketable Spot LIMIT must remain pending, got ${pending.status}`)
  const canceled = await api(`/api/trading/orders/${pending.id}/cancel`, { method: 'POST', token: userToken })
  assert(['CANCELED', 'CANCELLED'].includes(canceled.status), `Spot pending LIMIT cancel failed: ${canceled.status}`)

  const stop = await createOrder('STOP_MARKET LAST_PRICE', {
    symbol: SPOT_SYMBOL,
    side: 'BUY',
    orderType: 'STOP_MARKET',
    quantity: '0.0001',
    quantityUnit: 'BASE',
    triggerPrice: aligned(last * 0.99, tick, 'floor'),
    triggerPriceType: 'LAST_PRICE',
    marginMode: 'CASH'
  })
  const triggeredStop = await waitOrderTerminal(stop.id)
  assert(triggeredStop.status === 'FILLED', `Spot STOP_MARKET must trigger and fill, got ${triggeredStop.status}`)

  const beforeBuyOcoWallets = await walletBalances()
  const buyOcoQuote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
  const buyOcoLast = number(buyOcoQuote.mid ?? buyOcoQuote.last ?? buyOcoQuote.ask)
  const buyOco = await createOco('BUY OCO', 'BUY', {
    quantity: '0.0001',
    limitPrice: aligned(buyOcoLast * 0.9998, tick, 'floor'),
    stopTriggerPrice: aligned(buyOcoLast * 1.0002, tick, 'ceil')
  })
  const buyOutcome = await waitOcoOutcome(buyOco.contingencyGroupId)
  assertOcoOutcome(buyOutcome, 'BUY OCO')
  const afterBuyOcoWallets = await walletBalances()
  assert(
    number(assetWallet(afterBuyOcoWallets, 'USDT').locked) <= number(assetWallet(beforeBuyOcoWallets, 'USDT').locked) + 0.000001,
    'OCO peer cancellation must release shared USDT hold'
  )

  const beforeSellOcoWallets = afterBuyOcoWallets
  const sellOcoQuote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
  const sellOcoLast = number(sellOcoQuote.mid ?? sellOcoQuote.last ?? sellOcoQuote.ask)
  const sellOco = await createOco('SELL OCO', 'SELL', {
    quantity: '0.0001',
    limitPrice: aligned(sellOcoLast * 1.0002, tick, 'ceil'),
    stopTriggerPrice: aligned(sellOcoLast * 0.9998, tick, 'floor')
  })
  const sellOutcome = await waitOcoOutcome(sellOco.contingencyGroupId)
  assertOcoOutcome(sellOutcome, 'SELL OCO')
  const afterOcoWallets = await walletBalances()
  assertWalletInvariant(afterOcoWallets)
  assert(
    number(assetWallet(afterOcoWallets, 'BTC').locked) <= number(assetWallet(beforeSellOcoWallets, 'BTC').locked) + 0.000001,
    'OCO peer cancellation must release shared BTC hold'
  )

  return {
    marketBuy: marketBuy.id,
    marketSell: marketSell.id,
    immediateLimit: immediate.id,
    canceledLimit: pending.id,
    stopMarket: stop.id,
    buyOco: buyOco.contingencyGroupId,
    sellOco: sellOco.contingencyGroupId
  }
}

async function runTransferJourney() {
  const before = await accountFundsSnapshot()
  const spotToPerp = await api(`/api/accounts/${accountId}/transfers`, {
    method: 'POST', token: userToken,
    body: { direction: 'SPOT_TO_PERP', amount: '1000', requestId: randomUUID() }
  })
  const middle = await accountFundsSnapshot()
  assert(number(middle.summary.balance) > number(before.summary.balance), 'SPOT_TO_PERP must increase Perpetual balance')
  const perpToSpot = await api(`/api/accounts/${accountId}/transfers`, {
    method: 'POST', token: userToken,
    body: { direction: 'PERP_TO_SPOT', amount: '1000', requestId: randomUUID() }
  })
  const after = await accountFundsSnapshot()
  assertNear(combinedUsdt(after), combinedUsdt(before), 0.000001, 'round-trip transfer conservation')
  const history = await api(`/api/accounts/${accountId}/transfers?page=0&size=20`, { token: userToken })
  const transfers = pageContent(history)
  assert(transfers.some((row) => row.transferId === spotToPerp.transferId), 'SPOT_TO_PERP must appear in transfer history')
  assert(transfers.some((row) => row.transferId === perpToSpot.transferId), 'PERP_TO_SPOT must appear in transfer history')
  for (const transfer of [spotToPerp, perpToSpot]) {
    assert(
      after.assetLedger.some((entry) => entry.referenceId === transfer.transferId),
      `${transfer.direction} must create its Spot-side asset-ledger entry`
    )
    assert(
      after.ledger.some((entry) => entry.referenceId === transfer.transferId),
      `${transfer.direction} must create its Perpetual cash-ledger entry`
    )
  }
  return { spotToPerp: spotToPerp.transferId, perpToSpot: perpToSpot.transferId }
}

async function expectUnsafeMarginReduction(positionId) {
  const acceptedCodes = ['MARGIN_REDUCTION_UNSAFE', 'INSUFFICIENT_MARGIN', 'POSITION_VERSION_CONFLICT']
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const current = (await openPositions(SETTINGS_SYMBOL)).find((position) => position.id === positionId)
    assert(current, 'unsafe margin reduction requires the isolated position to remain open')
    const error = await expectApiError(`/api/trading/positions/${positionId}/margin`, {
      method: 'POST', token: userToken, body: { action: 'REDUCE', amount: '1000000', expectedVersion: current.version }
    }, acceptedCodes)
    if (error.code !== 'POSITION_VERSION_CONFLICT') return error
  }
  throw new Error('unsafe margin reduction safety could not be verified after 3 fresh position versions')
}

async function updatePositionMarginWithFreshVersion(positionId, action, amount) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const current = (await openPositions(SETTINGS_SYMBOL)).find((position) => position.id === positionId)
    assert(current, 'position margin update requires the isolated position to remain open')
    try {
      return await api(`/api/trading/positions/${positionId}/margin`, {
        method: 'POST', token: userToken, body: { action, amount, expectedVersion: current.version }
      })
    } catch (error) {
      if (error.code !== 'POSITION_VERSION_CONFLICT') throw error
    }
  }
  throw new Error(`${action} position margin update could not complete after 3 fresh position versions`)
}

async function runPerpetualJourney() {
  await cancelAndCloseAll('perp-journey-baseline')
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(PERP_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
  const rules = await api(`/api/market/symbols/${PERP_SYMBOL}/rules`)
  const last = number(quote.mid ?? quote.markPrice ?? quote.ask)
  const tick = number(rules.tickSize ?? 0.1)

  const market = await createOrder('Perp ONE_WAY CROSS MARKET BASE', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.001', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  assert(market.status === 'FILLED', `Perp MARKET(BASE) must fill, got ${market.status}`)

  // UI calls this a USDT_NOTIONAL order; the canonical REST enum remains QuantityUnit.QUOTE.
  const limit = await createOrder('Perp LIMIT USDT_NOTIONAL', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'LIMIT', quantity: '100', quantityUnit: 'QUOTE', leverage: 10,
    price: aligned(last * 1.02, tick, 'ceil'), positionSide: 'BOTH', marginMode: 'CROSS'
  })
  assert(limit.status === 'FILLED', `Perp LIMIT(USDT notional) must fill, got ${limit.status}`)

  const stop = await createOrder('Perp STOP_MARKET CONTRACTS', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'STOP_MARKET', quantity: '1', quantityUnit: 'CONTRACTS', leverage: 10,
    triggerPrice: aligned(last * 0.99, tick, 'floor'), triggerPriceType: 'MARK_PRICE', positionSide: 'BOTH', marginMode: 'CROSS'
  })
  assert((await waitOrderTerminal(stop.id)).status === 'FILLED', 'Perp STOP_MARKET(CONTRACTS) must trigger')

  const beforePending = await accountFundsSnapshot()
  const pending = await createOrder('Perp pending cancel hold release', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'LIMIT', quantity: '100', quantityUnit: 'QUOTE', leverage: 10,
    price: aligned(last * 0.5, tick, 'floor'), positionSide: 'BOTH', marginMode: 'CROSS'
  })
  assert(!TERMINAL_ORDER_STATUSES.has(pending.status), 'Perp pending order must retain its margin hold')
  const duringPending = await accountFundsSnapshot()
  assert(
    number(duringPending.summary.usedMargin) > number(beforePending.summary.usedMargin),
    'pending Perp order must increase used margin while its hold is active'
  )
  assert(
    duringPending.ledger.some((entry) => entry.entryType === 'ORDER_HOLD' && entry.referenceId === pending.id),
    'pending Perp order must create its order-linked margin hold ledger entry'
  )
  await api(`/api/trading/orders/${pending.id}/cancel`, { method: 'POST', token: userToken })
  const afterPending = await accountFundsSnapshot()
  assert(number(afterPending.summary.usedMargin) <= number(beforePending.summary.usedMargin) + 0.01, 'cancel pending Perp order must restore used margin')
  assert(
    afterPending.ledger.some((entry) => entry.entryType === 'ORDER_RELEASE' && entry.referenceId === pending.id),
    'cancel pending Perp order must create its order-linked margin release ledger entry'
  )

  await cancelAndCloseAll('clear active state before HEDGE')
  await updatePositionMode('HEDGE')
  const hedgeLong = await createOrder('HEDGE simultaneous LONG', {
    symbol: SETTINGS_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.01', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'LONG', marginMode: 'CROSS'
  })
  const hedgeShort = await createOrder('HEDGE simultaneous SHORT', {
    symbol: SETTINGS_SYMBOL, side: 'SELL', orderType: 'MARKET', quantity: '0.01', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'SHORT', marginMode: 'CROSS'
  })
  const hedgePositions = await openPositions(SETTINGS_SYMBOL)
  assert(hedgePositions.some((position) => position.positionSide === 'LONG'), 'HEDGE LONG position missing')
  assert(hedgePositions.some((position) => position.positionSide === 'SHORT'), 'HEDGE SHORT position missing')
  await cancelAndCloseAll('clear hedge positions before symbol settings')

  await updatePositionMode('ONE_WAY')
  const settings1 = await updateSymbolSettings(SETTINGS_SYMBOL, { leverage: 1, marginMode: 'CROSS', quantityUnit: 'BASE' })
  const configured1 = settings1.symbols.find((setting) => setting.symbol === SETTINGS_SYMBOL)
  assert(configured1?.leverage === 1 && configured1.marginMode === 'CROSS', 'clean symbol must persist leverage=1 and CROSS before switching')
  const settings100 = await updateSymbolSettings(SETTINGS_SYMBOL, { leverage: 100, marginMode: 'ISOLATED', quantityUnit: 'BASE' })
  const configured = settings100.symbols.find((setting) => setting.symbol === SETTINGS_SYMBOL)
  assert(configured.leverage === 100 && configured.marginMode === 'ISOLATED', 'clean symbol must switch CROSS→ISOLATED and leverage 1→100')

  const isolatedOrder = await createOrder('ISOLATED leverage 100 position', {
    symbol: SETTINGS_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.1', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'BOTH', marginMode: 'ISOLATED'
  })
  let isolated = (await openPositions(SETTINGS_SYMBOL))[0]
  const marginBefore = number(isolated.marginHeld)
  const added = await updatePositionMarginWithFreshVersion(isolated.id, 'ADD', '10')
  assertNear(number(added.positionMargin), marginBefore + 10, 0.000001, 'Isolated margin after +10')
  isolated = (await openPositions(SETTINGS_SYMBOL))[0]
  assertNear(number(isolated.marginHeld), marginBefore + 10, 0.000001, 'persisted Isolated margin after +10')
  const reduced = await updatePositionMarginWithFreshVersion(isolated.id, 'REDUCE', '1')
  assertNear(number(reduced.positionMargin), marginBefore + 9, 0.000001, 'Isolated margin after -1')
  isolated = (await openPositions(SETTINGS_SYMBOL))[0]
  assertNear(number(isolated.marginHeld), marginBefore + 9, 0.000001, 'persisted Isolated margin after -1')
  const marginLedger = await accountLedger()
  assert(
    marginLedger.some((entry) => entry.entryType === 'MARGIN_HOLD' && entry.referenceId === isolated.id && Math.abs(number(entry.amount) - 10) < 0.000001),
    'Isolated margin add must create an exact +10 position-linked ledger entry'
  )
  assert(
    marginLedger.some((entry) => entry.entryType === 'MARGIN_RELEASE' && entry.referenceId === isolated.id && Math.abs(number(entry.amount) - 1) < 0.000001),
    'Isolated margin reduce must create an exact +1 release position-linked ledger entry'
  )
  // unsafe reduction visibly rejects while the preceding safe reduction succeeds.
  await expectUnsafeMarginReduction(isolated.id)

  const originalQuantity = number(isolated.lots)
  await api(`/api/trading/positions/${isolated.id}/close?accountId=${accountId}`, {
    method: 'POST', token: userToken,
    body: { quantity: String(originalQuantity / 2), quantityUnit: 'BASE', clientOrderId: smokeKey('partial-close') }
  })
  const partial = (await openPositions(SETTINGS_SYMBOL))[0]
  assert(number(partial.lots) < originalQuantity && number(partial.lots) > 0, 'partial close by typed quantity must retain a smaller position')
  await expectApiError('/api/trading/orders', {
    method: 'POST', token: userToken,
    body: orderBody({
      symbol: SETTINGS_SYMBOL, side: 'SELL', orderType: 'MARKET', quantity: String(number(partial.lots) * 2),
      quantityUnit: 'BASE', leverage: 100, positionSide: 'BOTH', marginMode: 'ISOLATED', reduceOnly: true
    }, 'reduce-only over-close')
  }, ['REDUCE_ONLY_EXCEEDS_POSITION', 'REDUCE_ONLY_VIOLATION', 'POSITION_QUANTITY_EXCEEDED'])
  await api(`/api/trading/positions/${partial.id}/close?accountId=${accountId}`, { method: 'POST', token: userToken, body: {} })
  assert((await openPositions(SETTINGS_SYMBOL)).length === 0, 'full close must remove the isolated position')

  return {
    market: market.id, limit: limit.id, stop: stop.id, pending: pending.id,
    hedgeLong: hedgeLong.id, hedgeShort: hedgeShort.id, isolated: isolatedOrder.id,
    marginAdd: added.positionId ?? isolated.id, marginReduce: reduced.positionId ?? isolated.id
  }
}

async function runProtectionAndFundingJourney() {
  await cancelAndCloseAll('protection-funding-baseline')
  const selectedFundingSource = await prepareSelectedFundingRate()
  await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'LOCAL_SIMULATED'))
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(PERP_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  await assertNoForeignOpenPositions(PERP_SYMBOL, 'funding selected-source settlement')
  await createOrder('protection parent position', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.02', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  let position = (await openPositions(PERP_SYMBOL))[0]
  assert(position, 'protection journey requires an open Perpetual position')
  const selectedRate = selectedFundingSource.rate
  const selectedFunding = await settleRealFundingRateForLong(position, selectedRate, 'selected funding source')

  const rules = await api(`/api/market/symbols/${PERP_SYMBOL}/rules`)
  const tick = number(rules.tickSize ?? 0.1)
  const trend = await waitForDirectionalPerpMark(tick)
  const mark = trend.mark
  const created = []

  for (let index = 0; index < 10; index += 1) {
    if (index === 0) {
      created.push(await createDirectionalNearProtection(position.id, trend.direction, tick))
      continue
    }
    const protectionType = index % 2 === 0 ? 'TAKE_PROFIT' : 'STOP_LOSS'
    const triggerPrice = protectionType === 'TAKE_PROFIT'
      ? aligned(mark * 1.5 + tick * index, tick, 'ceil')
      : aligned(mark * 0.5 - tick * index, tick, 'floor')
    const triggerExecutionType = index % 3 === 0 ? 'LIMIT' : 'MARKET'
    const limitPrice = protectionType === 'TAKE_PROFIT'
      ? aligned(triggerPrice * 1.01, tick, 'ceil')
      : aligned(triggerPrice * 0.99, tick, 'floor')
    const order = await api(`/api/trading/positions/${position.id}/protections`, {
      method: 'POST', token: userToken,
      body: {
        protectionType,
        quantity: '0.003',
        quantityUnit: 'BASE',
        triggerPrice,
        triggerExecutionType,
        price: triggerExecutionType === 'LIMIT' ? limitPrice : null,
        clientOrderId: smokeKey(`protection-${index}`)
      }
    })
    created.push(order)
  }
  assert(created.length === 10, 'must create 10 mixed TP/SL protection levels')
  assert(created.some((order) => order.protectionType === 'TAKE_PROFIT' && order.triggerExecutionType === 'MARKET'), 'mixed protections must include MARKET TAKE_PROFIT')
  assert(created.some((order) => order.protectionType === 'STOP_LOSS' && order.triggerExecutionType === 'LIMIT'), 'mixed protections must include LIMIT STOP_LOSS')
  assert(created.filter((order) => order.triggerExecutionType === 'LIMIT').every((order) => order.timeInForce === 'GTC'), 'triggered protection LIMIT may remain GTC')

  const triggered = await waitFor(async () => {
    const current = await orders({ symbol: PERP_SYMBOL, size: 200 })
    const transitioned = current.filter((order) =>
      created.some((candidate) => candidate.id === order.id)
        && order.status !== 'PENDING_ACTIVATION'
    )
    return transitioned.length === 1 && transitioned[0].id === created[0].id ? transitioned[0] : false
  }, 'exactly one deterministic protection trigger', 45000)
  assert(triggered.triggerExecutionType === 'LIMIT', 'the deterministic protection trigger must exercise LIMIT execution')
  assert(triggered.timeInForce === 'GTC', 'triggered protection LIMIT must retain GTC')
  assert(!TERMINAL_ORDER_STATUSES.has(triggered.status), 'triggered far-price protection LIMIT must remain active instead of filling')

  position = (await openPositions(PERP_SYMBOL))[0]
  assert(position, 'a single protection trigger must not close the whole parent position')
  const beforeResize = await orders({ symbol: PERP_SYMBOL, size: 200 })
  const trackedBefore = beforeResize
    .filter((order) => created.some((candidate) => candidate.id === order.id)
      && !TERMINAL_ORDER_STATUSES.has(order.status))
  await api(`/api/trading/positions/${position.id}/close?accountId=${accountId}`, {
    method: 'POST', token: userToken,
    body: { quantity: String(Math.max(0.001, number(position.lots) * 0.5)), quantityUnit: 'BASE', clientOrderId: smokeKey('protection-partial-close') }
  })
  const resizedPosition = (await openPositions(PERP_SYMBOL))[0]
  assert(resizedPosition, 'partial close must retain the protection parent position')
  const afterResize = await orders({ symbol: PERP_SYMBOL, size: 200 })
  assertNewestFirstProtectionResize(
    trackedBefore,
    afterResize,
    number(resizedPosition.lots)
  )

  const protectionCancel = await api('/api/trading/orders/cancel-all', {
    method: 'POST', token: userToken, body: { accountId, requestId: randomUUID() }
  })
  assertBatchItemsSucceeded(protectionCancel.items, 'cancel remaining protection orders')
  const activeProtectionIds = new Set(created.map((order) => order.id))
  const protectionOrdersAfterCancel = await orders({ symbol: PERP_SYMBOL, size: 200 })
  assert(
    protectionOrdersAfterCancel.filter((order) => activeProtectionIds.has(order.id)).every((order) => TERMINAL_ORDER_STATUSES.has(order.status)),
    'funding balance checks require every protection order to be terminal'
  )

  await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'LOCAL_SIMULATED'))
  await updateSymbolSettings(FALLBACK_FUNDING_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  await snapshotFundingConfig(FALLBACK_FUNDING_SYMBOL)
  const oppositeRate = (-selectedRate.rate).toFixed(10)
  const fallbackFundingPhaseStartedAt = Date.now()
  await updateFundingConfig(FALLBACK_FUNDING_SYMBOL, {
    fixedFundingRate: oppositeRate,
    fixedFundingIntervalMinutes: 1,
    reason: 'Task18 deterministic real FIXED fallback funding source'
  })
  await clearFundingRatesForSymbol(FALLBACK_FUNDING_SYMBOL)
  const fallbackConfig = await waitForFundingConfigSource(FALLBACK_FUNDING_SYMBOL, 'FIXED', 'LOCAL_SIMULATED', fallbackFundingPhaseStartedAt, 75000)
  const fallbackRate = await waitForRealFundingRate(
    FALLBACK_FUNDING_SYMBOL,
    ['fixed'],
    number(oppositeRate),
    75000,
    fallbackFundingPhaseStartedAt,
    15000
  )
  assert(fallbackConfig.actualSource === 'FIXED', 'fallback funding config must expose actualSource=FIXED')
  assert(fallbackConfig.sourceMode === 'LOCAL_SIMULATED', 'fallback funding config must expose LOCAL_SIMULATED')
  await isolatePreparedFundingRate(FALLBACK_FUNDING_SYMBOL, fallbackRate)
  await assertNoForeignOpenPositions(FALLBACK_FUNDING_SYMBOL, 'funding fallback-source settlement')
  await createOrder('funding fallback LONG position', {
    symbol: FALLBACK_FUNDING_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '1', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  const fallbackPosition = (await openPositions(FALLBACK_FUNDING_SYMBOL))[0]
  assert(fallbackPosition, 'fallback funding journey requires a real open LONG position')
  const fallbackFunding = await settleRealFundingRateForLong(fallbackPosition, fallbackRate, 'fallback funding source')
  assert(selectedRate.rate * fallbackRate.rate < 0, 'selected and fallback funding rates must cover positive and negative funding signs')
  if (!selectedFundingSource.externalUnavailable) {
    assert(selectedRate.providerCode !== fallbackRate.providerCode, 'available selected and fallback funding paths must use distinct providers')
  }

  const positiveFunding = selectedRate.rate > 0 ? selectedFunding : fallbackFunding
  const negativeFunding = selectedRate.rate < 0 ? selectedFunding : fallbackFunding
  assert(positiveFunding.rate.rate > 0, 'funding positive settlement must use a positive real rate')
  assert(negativeFunding.rate.rate < 0, 'funding negative settlement must use a negative real rate')
  const cancelAll = await api('/api/trading/orders/cancel-all', {
    method: 'POST', token: userToken, body: { accountId, requestId: randomUUID() }
  })
  const closeAll = await api('/api/trading/positions/close-all', {
    method: 'POST', token: userToken, body: { accountId, requestId: randomUUID() }
  })
  assert(Array.isArray(cancelAll.items), 'cancel-all must return per-order outcomes')
  assert(Array.isArray(closeAll.items), 'close-all must return per-position outcomes')
  assertBatchItemsSucceeded(cancelAll.items, 'cancel-all')
  assertBatchItemsSucceeded(closeAll.items, 'close-all')
  assert((await openPositions()).every((position) => position.productType !== 'LINEAR_PERP'), 'close-all must leave no open Perpetual position')
  assert((await orders({ size: 500 })).every((order) => TERMINAL_ORDER_STATUSES.has(order.status)), 'cancel-all/close-all must leave no active order')
  await restoreFundingConfig(FALLBACK_FUNDING_SYMBOL)
  if (selectedFundingSource.externalUnavailable) await restoreFundingConfig(PERP_SYMBOL)
  return {
    protections: created.map((order) => order.id),
    triggered: triggered.id,
    positiveRateId: positiveFunding.rate.id,
    negativeRateId: negativeFunding.rate.id,
    selectedFundingExternalUnavailable: selectedFundingSource.externalUnavailable,
    cancelAll: cancelAll.items.length,
    closeAll: closeAll.items.length
  }
}

async function waitForDirectionalPerpMark(tick) {
  let previousMark
  let direction = 0
  let directionalMoves = 0
  let trendStart
  return waitFor(async () => {
    const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
    assert(quote.providerCode === 'local-perp' && quote.sourceMode === 'LOCAL_SIMULATED', 'protection trend must use the local Perpetual mark')
    const mark = number(quote.markPrice ?? quote.mid ?? quote.ask)
    if (previousMark === undefined) {
      previousMark = mark
      return false
    }
    const delta = mark - previousMark
    if (delta === 0) return false
    const nextDirection = Math.sign(delta)
    if (nextDirection === direction) {
      directionalMoves += 1
    } else {
      direction = nextDirection
      directionalMoves = 1
      trendStart = previousMark
    }
    previousMark = mark
    if (directionalMoves < 2 || Math.abs(delta) < tick * 5 || Math.abs(mark - trendStart) < tick * 10) return false
    return { direction: direction > 0 ? 'UP' : 'DOWN', mark, quote }
  }, 'a clear one-way local Perpetual mark trend', 30000)
}

async function createDirectionalNearProtection(positionId, direction, tick) {
  const protectionType = direction === 'UP' ? 'TAKE_PROFIT' : 'STOP_LOSS'
  let lastDirectionError
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
    assert(quote.providerCode === 'local-perp' && quote.sourceMode === 'LOCAL_SIMULATED', 'near protection must use the local Perpetual authority mark')
    const authorityMark = number(quote.markPrice ?? quote.mid ?? quote.ask)
    const triggerPrice = direction === 'UP'
      ? aligned(authorityMark + tick * 10, tick, 'ceil')
      : aligned(authorityMark - tick * 10, tick, 'floor')
    try {
      return await api(`/api/trading/positions/${positionId}/protections`, {
        method: 'POST', token: userToken,
        body: {
          protectionType,
          quantity: '0.003',
          quantityUnit: 'BASE',
          triggerPrice,
          triggerExecutionType: 'LIMIT',
          price: aligned(authorityMark * 1.5, tick, 'ceil'),
          clientOrderId: smokeKey(`protection-0-authority-${attempt}`)
        }
      })
    } catch (error) {
      if (error.code !== 'PROTECTION_DIRECTION_INVALID') throw error
      lastDirectionError = error
    }
  }
  throw lastDirectionError
}

export function assertNewestFirstProtectionResize(before, after, remainingPositionQuantity) {
  const byId = new Map(after.map((order) => [order.id, order]))
  const protectionsByType = Map.groupBy(before, (order) => order.protectionType)
  let resizingTypeCount = 0
  for (const [protectionType, protections] of protectionsByType) {
    assert(protectionType, 'tracked protection must declare its protection type')
    const oldestFirst = protections.toSorted((left, right) =>
      Date.parse(left.createdAt) - Date.parse(right.createdAt)
        || String(left.id).localeCompare(String(right.id)))
    const changedFlags = oldestFirst.map((order) => {
      const current = byId.get(order.id)
      return !current
        || current.status !== order.status
        || Math.abs(number(current.quantity) - number(order.quantity)) > 0.00000001
    })
    const beforeQuantity = oldestFirst.reduce((sum, order) => sum + number(order.quantity), 0)
    const afterQuantity = oldestFirst.reduce((sum, order) => {
      const current = byId.get(order.id)
      return sum + (current && !TERMINAL_ORDER_STATUSES.has(current.status) ? number(current.quantity) : 0)
    }, 0)
    const expectedReduction = Math.max(0, beforeQuantity - remainingPositionQuantity)
    if (expectedReduction > 0.00000001) {
      resizingTypeCount += 1
      const firstChanged = changedFlags.indexOf(true)
      assert(firstChanged >= 0, `partial close must auto-resize or cancel ${protectionType} levels`)
      assert(
        changedFlags.slice(firstChanged).every(Boolean),
        `${protectionType} newest-first auto-resize must change one contiguous newest-order suffix without gaps`
      )
    }
    assertNear(beforeQuantity - afterQuantity, expectedReduction, 0.00000001, `${protectionType} newest-first protection reduction amount`)
    assert(afterQuantity <= remainingPositionQuantity + 0.00000001, `active ${protectionType} quantity must not exceed the remaining position capacity`)
  }
  assert(resizingTypeCount > 0, 'partial close must require at least one protection type to resize')
}

async function assertNoForeignOpenPositions(symbol, label) {
  const count = number(await runDbSql(`
    SELECT count(*)
    FROM trading.positions
    WHERE symbol = '${sqlLiteral(symbol)}'
      AND status = 'OPEN'
      AND account_id <> '${sqlLiteral(accountId)}'
  `))
  assert(count === 0, `${label} requires an isolated local smoke database; found ${count} foreign open position(s) for ${symbol}`)
}

async function prepareSelectedFundingRate() {
  await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'BINANCE_PUBLIC'))
  const externalPhaseStartedAt = Date.now()
  await clearFundingRatesForSymbol(PERP_SYMBOL)
  try {
    const config = await waitForFundingConfigSource(PERP_SYMBOL, ['BINANCE', 'OKX'], 'PUBLIC_EXTERNAL', null, 45000)
    const expectedProviders = config.actualSource === 'BINANCE' ? ['binance-usdm'] : ['okx-swap']
    const rate = await waitForRealFundingRate(
      PERP_SYMBOL,
      expectedProviders,
      null,
      45000,
      externalPhaseStartedAt
    )
    const expectedActualSource = rate.providerCode === 'binance-usdm' ? 'BINANCE' : 'OKX'
    assert(config.actualSource === expectedActualSource, `selected funding config must expose actualSource=${expectedActualSource}, got ${config.actualSource}`)
    assert(config.sourceMode === 'PUBLIC_EXTERNAL', `selected external funding must expose PUBLIC_EXTERNAL, got ${config.sourceMode}`)
    return { rate, externalUnavailable: false }
  } catch (externalError) {
    const config = await fundingConfig(PERP_SYMBOL)
    assert(
      !['BINANCE', 'OKX'].includes(config.actualSource),
      `external funding ingestion failed despite Admin reporting actualSource=${config.actualSource}: ${externalError instanceof Error ? externalError.message : externalError}`
    )
    const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
    sourceEvidence.push({
      mode: 'FUNDING_SELECTED_EXTERNAL_UNAVAILABLE',
      error: externalError instanceof Error ? externalError.message : String(externalError),
      actualFundingSource: config.actualSource ?? 'UNAVAILABLE',
      actualQuote: pickSourceMetadata(quote)
    })

    await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'LOCAL_SIMULATED'))
    await snapshotFundingConfig(PERP_SYMBOL)
    const fixedPhaseStartedAt = Date.now()
    await updateFundingConfig(PERP_SYMBOL, {
      fixedFundingRate: '0.0001000000',
      fixedFundingIntervalMinutes: 1,
      reason: 'Task18 selected-source fallback after external funding unavailability'
    })
    await clearFundingRatesForSymbol(PERP_SYMBOL)
    const fixedConfig = await waitForFundingConfigSource(PERP_SYMBOL, 'FIXED', 'LOCAL_SIMULATED', fixedPhaseStartedAt, 75000)
    const rate = await waitForRealFundingRate(
      PERP_SYMBOL,
      ['fixed'],
      0.0001,
      75000,
      fixedPhaseStartedAt,
      15000
    )
    assert(fixedConfig.actualSource === 'FIXED', 'selected funding fallback must expose actualSource=FIXED')
    assert(fixedConfig.sourceMode === 'LOCAL_SIMULATED', 'selected funding fallback must expose LOCAL_SIMULATED')
    await isolatePreparedFundingRate(PERP_SYMBOL, rate)
    return { rate, externalUnavailable: true }
  }
}

async function waitForRealFundingRate(
  symbol,
  expectedProviderCodes = null,
  expectedRate = null,
  timeoutMs = 30000,
  createdAtOrAfterMs = null,
  minimumNextFundingLeadMs = 0
) {
  return waitFor(async () => {
    const providerPredicate = expectedProviderCodes?.length
      ? `AND provider_code IN (${expectedProviderCodes.map((code) => `'${sqlLiteral(code)}'`).join(',')})`
      : ''
    const ratePredicate = expectedRate === null
      ? 'AND funding_rate <> 0'
      : `AND funding_rate = ${Number(expectedRate).toFixed(10)}`
    const createdPredicate = createdAtOrAfterMs === null
      ? ''
      : `AND created_at >= to_timestamp(${Number(createdAtOrAfterMs)} / 1000.0)`
    const nextFundingLeadPredicate = minimumNextFundingLeadMs > 0
      ? `AND next_funding_time >= now() + (${Number(minimumNextFundingLeadMs)} * interval '1 millisecond')`
      : ''
    const row = await runDbSql(`
      SELECT id::text || '|' || funding_rate::text || '|'
        || floor(extract(epoch FROM funding_time) * 1000)::bigint || '|'
        || floor(extract(epoch FROM next_funding_time) * 1000)::bigint || '|'
        || provider_code || '|' || source_mode || '|'
        || floor(extract(epoch FROM as_of) * 1000)::bigint || '|'
        || raw_payload_hash || '|'
        || floor(extract(epoch FROM created_at) * 1000)::bigint
      FROM trading.funding_rates
      WHERE symbol = '${sqlLiteral(symbol)}'
        AND funding_time <= now()
        AND provider_code IN ('binance-usdm', 'okx-swap', 'fixed')
        AND source_mode IN ('PUBLIC_EXTERNAL', 'LOCAL_SIMULATED')
        AND next_funding_time IS NOT NULL
        AND as_of IS NOT NULL
        AND raw_payload_hash IS NOT NULL
        AND raw_payload_hash <> ''
        ${providerPredicate}
        ${ratePredicate}
        ${createdPredicate}
        ${nextFundingLeadPredicate}
      ORDER BY CASE WHEN provider_code = 'fixed' THEN 1 ELSE 0 END, funding_time DESC
      LIMIT 1
    `)
    if (!row) return false
    const [id, rate, fundingTimeMs, nextFundingTimeMs, providerCode, sourceMode, asOfMs, rawPayloadHash, createdAtMs] = row.split('|')
    const result = {
      id,
      symbol,
      rate: number(rate),
      fundingTimeMs: number(fundingTimeMs),
      nextFundingTimeMs: number(nextFundingTimeMs),
      providerCode,
      sourceMode,
      asOfMs: number(asOfMs),
      rawPayloadHash,
      createdAtMs: number(createdAtMs)
    }
    assert(result.rate !== 0, `${symbol} real funding rate must be non-zero`)
    assert(result.rawPayloadHash.length >= 32, `${symbol} real funding rate must retain provider raw-payload evidence`)
    sourceEvidence.push({
      mode: expectedProviderCodes?.includes('fixed') ? 'FUNDING_FALLBACK' : 'FUNDING_SELECTED',
      fundingRate: result
    })
    return result
  }, `real funding ingestion for ${symbol}`, timeoutMs)
}

async function settleRealFundingRateForLong(position, rate, label) {
  const [before, settlementsBefore] = await Promise.all([
    accountSummary(),
    fundingSettlements()
  ])
  const settlementIdsBefore = new Set(settlementsBefore.map((settlement) => settlement.id))
  const originalOpenedAtMicros = await shiftPositionOpenedBeforeFunding(position.id, rate)
  let settlement
  try {
    settlement = await waitFundingSettlement(rate, position, settlementIdsBefore)
  } finally {
    await restorePositionOpenedAt(position.id, originalOpenedAtMicros)
  }
  const after = await accountSummary()
  assertNear(
    number(after.balance) - number(before.balance),
    number(settlement.amount),
    0.000001,
    `${label} exact account balance cashflow`
  )
  if (rate.rate > 0) {
    assert(number(after.balance) < number(before.balance), `${label}: positive funding on a LONG must reduce actual balance`)
  } else {
    assert(number(after.balance) > number(before.balance), `${label}: negative funding on a LONG must increase actual balance`)
  }
  assert(String(settlement.source).toLowerCase() === rate.providerCode.toLowerCase(), `${label} must retain the scheduler-selected provider source`)
  return { rate, settlement, balanceBefore: before.balance, balanceAfter: after.balance }
}

async function shiftPositionOpenedBeforeFunding(positionId, rate) {
  const originalOpenedAtMicros = await runDbSql(`
    SELECT floor(extract(epoch FROM opened_at) * 1000000)::bigint
    FROM trading.positions
    WHERE id = '${sqlLiteral(positionId)}'
      AND account_id = '${sqlLiteral(accountId)}'
      AND status = 'OPEN'
  `)
  assert(originalOpenedAtMicros, `funding fixture position ${positionId} must be an open position owned by the smoke account`)
  const updated = number(await runDbSql(`
    WITH updated AS (
      UPDATE trading.positions
      SET opened_at = to_timestamp(${rate.fundingTimeMs} / 1000.0) - interval '1 millisecond'
      WHERE id = '${sqlLiteral(positionId)}'
        AND account_id = '${sqlLiteral(accountId)}'
        AND status = 'OPEN'
      RETURNING id
    )
    SELECT count(*) FROM updated
  `))
  assert(updated === 1, `funding fixture must shift exactly one smoke position before ${rate.id}`)
  return originalOpenedAtMicros
}

async function restorePositionOpenedAt(positionId, originalOpenedAtMicros) {
  const restored = number(await runDbSql(`
    WITH restored AS (
      UPDATE trading.positions
      SET opened_at = to_timestamp(${originalOpenedAtMicros} / 1000000.0)
      WHERE id = '${sqlLiteral(positionId)}'
        AND account_id = '${sqlLiteral(accountId)}'
      RETURNING id
    )
    SELECT count(*) FROM restored
  `))
  assert(restored === 1, `funding fixture must restore opened_at for ${positionId}`)
}

function adminSymbolFor(symbol) {
  const fixture = bindingRestores.find((candidate) => candidate.symbol.symbol === symbol)
  assert(fixture, `Admin symbol metadata missing for ${symbol}`)
  return fixture.symbol
}

function fundingConfig(symbol) {
  const adminSymbol = adminSymbolFor(symbol)
  return adminApi(`/api/admin/market/symbols/${adminSymbol.id}/funding-config`)
}

async function waitForFundingConfigSource(symbol, expectedSource, expectedMode, asOfOrAfterMs, timeoutMs) {
  const expectedSources = Array.isArray(expectedSource) ? expectedSource : [expectedSource]
  return waitFor(async () => {
    const candidate = await fundingConfig(symbol)
    const currentEnough = asOfOrAfterMs === null
      || (candidate.asOf && Date.parse(candidate.asOf) >= asOfOrAfterMs)
    return expectedSources.includes(candidate.actualSource)
      && candidate.sourceMode === expectedMode
      && currentEnough
      ? candidate
      : false
  }, `active ${expectedSources.join('/')} funding config for ${symbol}`, timeoutMs)
}

async function snapshotFundingConfig(symbol) {
  if (!fundingConfigRestores.has(symbol)) {
    fundingConfigRestores.set(symbol, await fundingConfig(symbol))
  }
}

async function updateFundingConfig(symbol, patch) {
  const current = await fundingConfig(symbol)
  const adminSymbol = adminSymbolFor(symbol)
  return adminApi(`/api/admin/market/symbols/${adminSymbol.id}/funding-config`, {
    method: 'PUT',
    body: {
      fundingSourcePriority: patch.fundingSourcePriority ?? current.fundingSourcePriority,
      fixedFundingRate: patch.fixedFundingRate ?? current.fixedFundingRate,
      fixedFundingIntervalMinutes: patch.fixedFundingIntervalMinutes ?? current.fixedFundingIntervalMinutes,
      fundingStaleSeconds: patch.fundingStaleSeconds ?? current.fundingStaleSeconds,
      reason: patch.reason
    }
  })
}

async function clearFundingRatesForSymbol(symbol) {
  await runDbSql(`DELETE FROM trading.funding_rates WHERE symbol = '${sqlLiteral(symbol)}'`)
}

async function isolatePreparedFundingRate(symbol, rate) {
  await updateFundingConfig(symbol, {
    fixedFundingIntervalMinutes: 525600,
    reason: 'Isolate the prepared Task18 funding cycle while its smoke position is open'
  })
  const deleted = number(await runDbSql(`
    WITH deleted AS (
      DELETE FROM trading.funding_rates
      WHERE symbol = '${sqlLiteral(symbol)}'
        AND id <> '${sqlLiteral(rate.id)}'
      RETURNING id
    )
    SELECT count(*) FROM deleted
  `))
  const nearCompetingRates = number(await runDbSql(`
    SELECT count(*)
    FROM trading.funding_rates
    WHERE symbol = '${sqlLiteral(symbol)}'
      AND id <> '${sqlLiteral(rate.id)}'
      AND funding_time <= now() + interval '10 minutes'
  `))
  assert(nearCompetingRates === 0, `${symbol} prepared funding cycle must have no competing rate due during the smoke position lifecycle`)
  sourceEvidence.push({ mode: 'FUNDING_CYCLE_ISOLATION', symbol, rateId: rate.id, deletedCompetingRates: deleted })
}

async function restoreFundingConfig(symbol) {
  const original = fundingConfigRestores.get(symbol)
  if (!original) return
  await updateFundingConfig(symbol, {
    fundingSourcePriority: original.fundingSourcePriority,
    fixedFundingRate: original.fixedFundingRate,
    fixedFundingIntervalMinutes: original.fixedFundingIntervalMinutes,
    fundingStaleSeconds: original.fundingStaleSeconds,
    reason: 'Restore configuration after Task18 funding smoke'
  })
  const restored = await fundingConfig(symbol)
  assert(JSON.stringify(restored.fundingSourcePriority) === JSON.stringify(original.fundingSourcePriority), `${symbol} funding priority restore mismatch`)
  assert(number(restored.fixedFundingRate) === number(original.fixedFundingRate), `${symbol} fixed funding rate restore mismatch`)
  assert(restored.fixedFundingIntervalMinutes === original.fixedFundingIntervalMinutes, `${symbol} funding interval restore mismatch`)
  assert(restored.fundingStaleSeconds === original.fundingStaleSeconds, `${symbol} funding stale-window restore mismatch`)
  fundingConfigRestores.delete(symbol)
}

async function restoreFundingConfigs() {
  if (!adminToken || fundingConfigRestores.size === 0) return
  const failures = []
  for (const symbol of [...fundingConfigRestores.keys()]) {
    try {
      await restoreFundingConfig(symbol)
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, `failed to restore ${failures.length} funding configuration(s)`)
  }
}

async function waitFundingSettlement(rate, position, settlementIdsBefore) {
  const settlement = await waitFor(async () => {
    const current = await fundingSettlements()
    return current.find((candidate) =>
      !settlementIdsBefore.has(candidate.id)
      && candidate.positionId === position.id
      && candidate.symbol === position.symbol
      && Math.abs(number(candidate.fundingRate) - rate.rate) < 0.00000000001
      && String(candidate.source).toLowerCase() === rate.providerCode.toLowerCase()
      && Math.abs(Date.parse(candidate.fundingTime) - rate.fundingTimeMs) < 1000
    ) ?? false
  }, `funding settlement ${rate.id} for ${position.id}`, 30000)
  const positionDelta = (await fundingSettlements()).filter((candidate) =>
    !settlementIdsBefore.has(candidate.id) && candidate.positionId === position.id
  )
  assert(positionDelta.length === 1, `position ${position.id} must add exactly one funding settlement`)
  const matchingDelta = positionDelta.filter((candidate) =>
    candidate.symbol === position.symbol
      && Math.abs(number(candidate.fundingRate) - rate.rate) < 0.00000000001
      && String(candidate.source).toLowerCase() === rate.providerCode.toLowerCase()
      && Math.abs(Date.parse(candidate.fundingTime) - rate.fundingTimeMs) < 1000
  )
  assert(matchingDelta.length === 1, `funding rate ${rate.id} must add exactly one position-bound settlement`)
  return settlement
}

async function runLiquidationJourney() {
  await cancelAndCloseAll('liquidation-baseline')
  await resetDemoAsAdmin('clean baseline before liquidation')
  await applySourceMode(SOURCE_MODES.find((mode) => mode.id === 'LOCAL_SIMULATED'))
  const isolated = await runIsolatedLiquidation()
  await resetDemoAsAdmin('reset after isolated liquidation')
  const cross = await runCrossLiquidation()
  await resetDemoAsAdmin('reset after cross liquidation')
  return { isolated, cross }
}

async function runIsolatedLiquidation() {
  await updatePositionMode('HEDGE')
  await updateSymbolSettings(LIQUIDATION_SYMBOL, { leverage: 100, marginMode: 'ISOLATED', quantityUnit: 'BASE' })
  const long = await createOrder('Isolated liquidation LONG fixture', {
    symbol: LIQUIDATION_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '10', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'LONG', marginMode: 'ISOLATED'
  })
  const short = await createOrder('Isolated liquidation SHORT fixture', {
    symbol: LIQUIDATION_SYMBOL, side: 'SELL', orderType: 'MARKET', quantity: '10', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'SHORT', marginMode: 'ISOLATED'
  })
  const opened = await openPositions(LIQUIDATION_SYMBOL)
  const openedIds = new Set(opened.map((position) => position.id))
  const liquidated = await waitFor(async () => {
    const currentIds = new Set((await openPositions(LIQUIDATION_SYMBOL)).map((position) => position.id))
    return [...openedIds].find((id) => !currentIds.has(id)) ?? false
  }, 'deterministic Isolated liquidation', 120000)
  const [ordersAfter, tradesAfter, history, ledger] = await Promise.all([
    orders({ symbol: LIQUIDATION_SYMBOL, size: 200 }),
    trades({ symbol: LIQUIDATION_SYMBOL, size: 200 }),
    positionHistory(LIQUIDATION_SYMBOL),
    accountLedger()
  ])
  assert(history.some((position) => position.id === liquidated), 'Isolated liquidation must close a position')
  const evidence = assertLiquidationEvidence(
    [liquidated],
    ordersAfter,
    tradesAfter,
    ledger,
    'Isolated liquidation'
  )
  const notifications = await runDbSql(`
    SELECT count(*)
    FROM trading.order_events event
    JOIN trading.orders orders ON orders.id = event.order_id
    WHERE orders.user_id = '${sqlLiteral(userId)}'
      AND orders.id IN (${[...evidence.liquidationOrderIds].map((id) => `'${sqlLiteral(id)}'`).join(',')})
      AND event.event_type = 'ORDER_FILLED'
  `)
  assert(number(notifications) > 0, 'liquidation must emit the ORDER_FILLED event that publishes the LIQUIDATION notification')
  await assertLiquidationNotifications(evidence.liquidationOrders, 'Isolated liquidation')
  await cancelAndCloseAll('cleanup surviving isolated hedge leg')
  return { long: long.id, short: short.id, liquidated }
}

async function runCrossLiquidation() {
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(LIQUIDATION_SYMBOL, { leverage: 100, marginMode: 'CROSS', quantityUnit: 'BASE' })
  await updateSymbolSettings('XRPUSDT-PERP', { leverage: 100, marginMode: 'CROSS', quantityUnit: 'BASE' })
  await createOrder('Cross liquidation SOL leg', {
    symbol: LIQUIDATION_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '10', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  await createOrder('Cross liquidation XRP leg', {
    symbol: 'XRPUSDT-PERP', side: 'BUY', orderType: 'MARKET', quantity: '100', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  const opened = await openPositions()
  const openedCross = opened.filter((position) => position.marginMode === 'CROSS')
  assert(openedCross.length >= 2, 'Cross liquidation fixture must have at least two Cross positions')
  await installAccountScopedCrossShortfallFixture()
  await waitFor(async () => (await openPositions()).every((position) => position.marginMode !== 'CROSS'), 'Cross liquidation closes all Cross positions', 30000)
  const after = await accountSummary()
  assert(number(after.balance) >= 0, 'Cross liquidation shortfall keeps balance at zero, never negative')
  const ledger = await accountLedger()
  const [ordersAfter, tradesAfter] = await Promise.all([
    orders({ size: 300 }),
    trades({ size: 300 })
  ])
  const evidence = assertLiquidationEvidence(
    openedCross.map((position) => position.id),
    ordersAfter,
    tradesAfter,
    ledger,
    'Cross liquidation'
  )
  await assertLiquidationNotifications(evidence.liquidationOrders, 'Cross liquidation')
  assert(evidence.liquidationOrders.length === openedCross.length, 'Cross liquidation must create one system Order per Cross position')
  const shortfall = ledger.filter((entry) =>
    entry.entryType === 'BANKRUPTCY_SHORTFALL'
      && entry.referenceType === 'CROSS_LIQUIDATION_SETTLEMENT'
      && entry.referenceId
  )
  assert(shortfall.length === 1, 'Cross liquidation fixture must create exactly one aggregate bankruptcy-shortfall settlement')
  assert(number(shortfall[0].amount) > 0, 'Cross liquidation bankruptcy shortfall must be positive')
  const chargePairs = evidence.liquidationOrders
    .map((order) => `('${sqlLiteral(order.id)}','${sqlLiteral(order.parentPositionId)}')`)
    .join(',')
  const settledCharges = number(await runDbSql(`
    SELECT count(*)
    FROM trading.cross_liquidation_charges
    WHERE account_id = '${sqlLiteral(accountId)}'
      AND status = 'SETTLED'
      AND (order_id, position_id) IN (${chargePairs})
  `))
  assert(settledCharges === openedCross.length, 'every Cross liquidation Order/position pair must have one settled charge row')
  assertNear(number(after.balance), 0, 0.000001, 'Cross liquidation shortfall balance')
  return { closed: openedCross.map((position) => position.id), shortfall: shortfall.length, balance: after.balance }
}

async function installAccountScopedCrossShortfallFixture() {
  const updated = number(await runDbSql(`
    WITH updated AS (
      UPDATE core.trading_accounts
      SET balance = 0,
          equity = 0,
          free_margin = 0,
          updated_at = now()
      WHERE id = '${sqlLiteral(accountId)}'
        AND user_id = '${sqlLiteral(userId)}'
        AND account_type = 'DEMO'
        AND status = 'ACTIVE'
      RETURNING id
    )
    SELECT count(*) FROM updated
  `))
  assert(updated === 1, 'account-scoped Cross shortfall fixture must target exactly the smoke DEMO account')
}

function assertLiquidationEvidence(positionIds, ordersAfter, tradesAfter, ledger, label) {
  const positionIdSet = new Set(positionIds)
  const liquidationOrders = ordersAfter.filter((order) =>
    order.origin === 'LIQUIDATION'
      && positionIdSet.has(order.parentPositionId)
      && order.status === 'FILLED'
  )
  const liquidationOrderIds = new Set(liquidationOrders.map((order) => order.id))
  assert(liquidationOrders.length === positionIdSet.size, `${label} must create exactly one FILLED system Order for every liquidated position`)
  const liquidationTrades = []
  for (const positionId of positionIdSet) {
    const positionOrders = liquidationOrders.filter((order) => order.parentPositionId === positionId)
    assert(positionOrders.length === 1, `${label} position ${positionId} must map to exactly one liquidation Order`)
    const orderTrades = tradesAfter.filter((trade) => trade.orderId === positionOrders[0].id)
    assert(orderTrades.length >= 1, `${label} Order ${positionOrders[0].id} must create a linked Trade`)
    liquidationTrades.push(...orderTrades)
    assert(
      orderTrades.some((trade) => ledger.some((entry) => entry.entryType === 'TRADE_FEE' && entry.referenceId === trade.id)),
      `${label} position ${positionId} must create a Trade-linked fee ledger entry`
    )
    assert(
      ledger.some((entry) => ['TRADE_PNL', 'LIQUIDATION_FEE'].includes(entry.entryType) && entry.referenceId === positionId),
      `${label} position ${positionId} must create position-linked PnL/liquidation-fee evidence`
    )
  }
  return { liquidationOrders, liquidationOrderIds, liquidationTrades }
}

async function runResetLifecycleJourney() {
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(PERP_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  await createOrder('active state blocks reset', {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.001', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  await expectApiError(`/api/accounts/${accountId}/demo-reset`, {
    method: 'POST', token: userToken, body: { requestId: randomUUID() }
  }, ['DEMO_RESET_BLOCKED', 'ACTIVE_TRADING_STATE'])
  const cleanup = await adminApi(`/api/admin/accounts/${accountId}/force-cleanup`, {
    method: 'POST',
    body: { reason: 'Task18 active-state cleanup', requestId: randomUUID(), confirmationText: 'CONFIRM_FORCE_CLEANUP' }
  })
  const reset = await resetDemoAsAdmin('Task18 reset succeeds after Admin force cleanup')
  const funds = await accountFundsSnapshot()
  assertNear(number(usdtWallet(funds.wallets).total), INITIAL_SPOT_USDT, 0.000001, 'Spot balance after reset')
  assertNear(number(funds.summary.balance), INITIAL_PERP_USDT, 0.000001, 'Perpetual balance after reset')
  return { cleanupOutcomes: cleanup.items?.length ?? 0, generation: reset.demoGeneration }
}

async function resetDemoAsAdmin(reason) {
  return adminApi(`/api/admin/accounts/${accountId}/demo-reset`, {
    method: 'POST',
    body: { reason, requestId: randomUUID(), confirmationText: 'CONFIRM_DEMO_RESET' }
  })
}

async function runMinimalSpotLoop(mode) {
  const quoteStartedAt = new Date().toISOString()
  const quote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
  await assertHigherPriorityProvidersUnavailable(mode, 'spot', quote, quoteStartedAt)
  const rules = await api(`/api/market/symbols/${SPOT_SYMBOL}/rules`)
  const last = number(quote.mid ?? quote.ask)
  const tick = number(rules.tickSize ?? 0.1)
  const market = await createOrder(`${mode.id} Spot MARKET`, {
    symbol: SPOT_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '10', quantityUnit: 'QUOTE', marginMode: 'CASH'
  })
  await assertOrderTradeUsesMode(market, mode, 'spot', quote, SPOT_SYMBOL, 200)
  const limit = await createOrder(`${mode.id} Spot LIMIT cancel`, {
    symbol: SPOT_SYMBOL, side: 'BUY', orderType: 'LIMIT', quantity: '0.001', quantityUnit: 'BASE',
    price: aligned(last * 0.5, tick, 'floor'), marginMode: 'CASH'
  })
  const canceled = await api(`/api/trading/orders/${limit.id}/cancel`, { method: 'POST', token: userToken })
  assert(market.status === 'FILLED' && ['CANCELED', 'CANCELLED'].includes(canceled.status), `${mode.id} minimal Spot loop failed`)
}

async function runMinimalPerpetualLoop(mode) {
  await cancelAndCloseAll(`${mode.id}-minimal-perp-baseline`)
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(PERP_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  const quoteStartedAt = new Date().toISOString()
  const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
  await assertHigherPriorityProvidersUnavailable(mode, 'perp', quote, quoteStartedAt)
  const rules = await api(`/api/market/symbols/${PERP_SYMBOL}/rules`)
  const tick = number(rules.tickSize ?? 0.1)
  const market = await createOrder(`${mode.id} Perp MARKET`, {
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.001', quantityUnit: 'BASE', leverage: 10,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  await assertOrderTradeUsesMode(market, mode, 'perp', quote, PERP_SYMBOL, 200)
  const stopQuoteStartedAt = new Date().toISOString()
  const stopQuote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
  await assertHigherPriorityProvidersUnavailable(mode, 'perp', stopQuote, stopQuoteStartedAt)
  const stopLast = number(stopQuote.mid ?? stopQuote.markPrice ?? stopQuote.ask)
  const stop = await createOrder(`${mode.id} Perp STOP_MARKET close`, {
    symbol: PERP_SYMBOL, side: 'SELL', orderType: 'STOP_MARKET', quantity: '0.001', quantityUnit: 'BASE', leverage: 10,
    triggerPrice: aligned(stopLast * 1.01, tick, 'ceil'), triggerPriceType: 'MARK_PRICE', positionSide: 'BOTH', marginMode: 'CROSS', reduceOnly: true
  })
  const triggeredStop = await waitOrderTerminal(stop.id)
  assert(triggeredStop.status === 'FILLED', `${mode.id} Perp STOP_MARKET must execute the close`)
  await assertOrderTradeUsesMode(stop, mode, 'perp', stopQuote, PERP_SYMBOL, 200)
  assert(market.status === 'FILLED' && (await openPositions(PERP_SYMBOL)).length === 0, `${mode.id} minimal Perp MARKET/STOP/close loop failed`)
}

async function assertOrderTradeUsesMode(order, mode, product, quote, symbol, size) {
  const trade = (await trades({ symbol, size })).find((candidate) => candidate.orderId === order.id)
  assert(trade, `${mode.id} ${product} order ${order.id} must create a linked Trade`)
  await assertTradeUsesMode(trade, mode, product, quote)
  return trade
}

async function assertTradeUsesMode(trade, mode, product, quote) {
  const providers = product === 'spot' ? mode.expectedSpot : mode.expectedPerp
  assert(providers.includes(quote.providerCode), `${mode.id} ${product} quote used unexpected provider ${quote.providerCode}`)
  assert(providers.includes(trade.providerCode), `${mode.id} ${product} Trade used unexpected provider ${trade.providerCode}`)
  const quoteSourceMode = quote.providerCode.startsWith('local-') ? 'LOCAL_SIMULATED' : 'PUBLIC_EXTERNAL'
  const tradeSourceMode = trade.providerCode.startsWith('local-') ? 'LOCAL_SIMULATED' : 'PUBLIC_EXTERNAL'
  assert(quote.sourceMode === quoteSourceMode, `${mode.id} ${product} quote sourceMode mismatch for ${quote.providerCode}`)
  assert(trade.sourceMode === tradeSourceMode, `${mode.id} ${product} Trade sourceMode mismatch for ${trade.providerCode}`)
  if (trade.providerCode === quote.providerCode && trade.sourceMode === quote.sourceMode) return

  const higherPriorityFailures = await assertHigherPriorityProvidersUnavailable(
    mode,
    product,
    trade,
    quote.asOf ?? new Date(sourceModeStartedAtMs).toISOString()
  )
  sourceEvidence.push({
    mode: mode.id,
    product,
    sourceChangedBetweenQuoteAndTrade: `${quote.providerCode}/${quote.sourceMode} -> ${trade.providerCode}/${trade.sourceMode}`,
    higherPriorityFailures
  })
}

async function runBrowserMinimalTradingLoop(mode) {
  await cancelAndCloseAll(`${mode.id}-browser-loop-baseline`)
  await updatePositionMode('ONE_WAY')
  await updateSymbolSettings(PERP_SYMBOL, { leverage: 10, marginMode: 'CROSS', quantityUnit: 'BASE' })
  const page = await createCdpPage(browser.port)
  const runtimeErrors = []
  page.on('Runtime.exceptionThrown', (event) => runtimeErrors.push(event.exceptionDetails?.text ?? 'runtime exception'))
  page.on('Log.entryAdded', (event) => {
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry)
  })
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Log.enable')
  await page.send('Network.enable')
  await setViewport(page, VIEWPORTS.find((viewport) => viewport.name === 'web-desktop'))
  try {
    await installBrowserSession(page, webBaseUrl, { 'fx-platform-auth-token': userToken })

    const spotRules = await api(`/api/market/symbols/${SPOT_SYMBOL}/rules`)
    const spotTick = number(spotRules.tickSize ?? 0.1)
    await openBrowserTradeRoute(page, `/trade/spot/${encodeURIComponent(SPOT_SYMBOL)}?ui-loop=${runId}`, SPOT_SYMBOL)
    const spotExecutionStartedAt = new Date().toISOString()
    const spotExecutionQuote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
    await assertHigherPriorityProvidersUnavailable(mode, 'spot', spotExecutionQuote, spotExecutionStartedAt)
    const spotMarket = await submitBrowserOrder(page, {
      side: 'buy', tabIndex: 1, values: ['10'], label: `${mode.id} browser Spot MARKET`
    })
    assert(spotMarket.status === 'FILLED', `${mode.id} browser Spot MARKET must fill`)
    await assertOrderTradeUsesMode(spotMarket, mode, 'spot', spotExecutionQuote, SPOT_SYMBOL, 300)
    const spotLimitQuote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
    const spotLimitLast = number(spotLimitQuote.mid ?? spotLimitQuote.ask)
    const spotLimit = await submitBrowserOrder(page, {
      side: 'buy', tabIndex: 0,
      values: [aligned(spotLimitLast * 0.5, spotTick, 'floor'), '0.001'],
      label: `${mode.id} browser Spot LIMIT`
    })
    assert(!TERMINAL_ORDER_STATUSES.has(spotLimit.status), `${mode.id} browser Spot LIMIT must remain cancelable`)
    await cancelBrowserOrder(page, spotLimit.id, `${mode.id} browser Spot LIMIT cancel`)

    const perpRules = await api(`/api/market/symbols/${PERP_SYMBOL}/rules`)
    const perpTick = number(perpRules.tickSize ?? 0.1)
    await openBrowserTradeRoute(page, `/trade/perpetual/${encodeURIComponent(PERP_SYMBOL)}?ui-loop=${runId}`, PERP_SYMBOL)
    const perpExecutionStartedAt = new Date().toISOString()
    const perpExecutionQuote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
    await assertHigherPriorityProvidersUnavailable(mode, 'perp', perpExecutionQuote, perpExecutionStartedAt)
    const perpMarket = await submitBrowserOrder(page, {
      side: 'buy', tabIndex: 1, values: ['0.001'], label: `${mode.id} browser Perp MARKET`
    })
    assert(perpMarket.status === 'FILLED', `${mode.id} browser Perp MARKET must fill`)
    await assertOrderTradeUsesMode(perpMarket, mode, 'perp', perpExecutionQuote, PERP_SYMBOL, 300)
    const perpStopStartedAt = new Date().toISOString()
    const perpStopQuote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
    await assertHigherPriorityProvidersUnavailable(mode, 'perp', perpStopQuote, perpStopStartedAt)
    const perpStopLast = number(perpStopQuote.mid ?? perpStopQuote.markPrice ?? perpStopQuote.ask)
    const perpStop = await submitBrowserOrder(page, {
      side: 'sell', tabIndex: 2,
      values: [aligned(perpStopLast * 1.01, perpTick, 'ceil'), '0.001'],
      reduceOnly: true,
      label: `${mode.id} browser Perp STOP_MARKET close`
    })
    const closed = await waitOrderTerminal(perpStop.id)
    assert(closed.status === 'FILLED', `${mode.id} browser Perp STOP_MARKET must fill`)
    await assertOrderTradeUsesMode(perpStop, mode, 'perp', perpStopQuote, PERP_SYMBOL, 300)
    assert((await openPositions(PERP_SYMBOL)).length === 0, `${mode.id} browser Perp STOP_MARKET must close the position`)
    assertNoRuntimeErrors(runtimeErrors, `${mode.id} browser executable trading loop`)
  } finally {
    await page.close()
  }
}

async function openBrowserTradeRoute(page, route, symbol) {
  await page.navigate(`${webBaseUrl}${route}`)
  await waitForPageReady(page, route)
  try {
    await page.waitForFunction((panelSelector) => {
      const panel = document.querySelector(panelSelector)
      const submitButtons = [...(panel?.querySelectorAll('[data-trading-action="submit-order"]') ?? [])]
      return Boolean(
        panel
          && document.querySelector('[data-source]')
          && !panel.querySelector('[data-trading-action="login-required"]')
          && submitButtons.length === 2
          && submitButtons.every((button) => [...(button.previousElementSibling?.querySelectorAll('strong') ?? [])]
            .some((value) => !value.textContent?.trim().startsWith('-')))
      )
    }, `real order controls ${route}`, TRADE_PANEL_SELECTOR)
    const actual = await api(`/api/market/quotes/${symbol}`)
    await page.waitForFunction(
      (provider) => document.body.textContent?.toUpperCase().includes(provider.toUpperCase()),
      `actual provider visible ${route}`,
      actual.providerCode
    )
  } catch (error) {
    const diagnostic = await page.evaluate((panelSelector) => {
      const panel = document.querySelector(panelSelector)
      const settings = document.querySelector('[aria-label="Perpetual trading settings"]')
      const sources = [...document.querySelectorAll('[data-source]')].map((source) => ({
        source: source.getAttribute('data-source'),
        stale: source.getAttribute('data-stale'),
        text: source.textContent?.trim(),
        container: source.closest('[aria-label]')?.getAttribute('aria-label') ?? null
      }))
      const buttons = [...(panel?.querySelectorAll('[data-trading-action]') ?? [])].map((button) => ({
        action: button.getAttribute('data-trading-action'),
        text: button.textContent?.trim(),
        disabled: button.disabled,
        title: button.getAttribute('title'),
        balance: [...(button.previousElementSibling?.querySelectorAll('strong') ?? [])]
          .map((value) => value.textContent?.trim())
      }))
      const marketResources = performance.getEntriesByType('resource')
        .filter((entry) => entry.name.includes('/api/market/'))
        .slice(-20)
        .map((entry) => ({
          name: entry.name.replace(window.location.origin, ''),
          duration: Math.round(entry.duration),
          responseEnd: Math.round(entry.responseEnd)
        }))
      return {
        pathname: window.location.pathname,
        panelPresent: Boolean(panel),
        panelText: panel?.textContent?.trim().slice(0, 600) ?? null,
        sources,
        marketStatus: document.querySelector('[aria-label$="market side panel"] [role="status"]')?.textContent?.trim() ?? null,
        buttons,
        session: panel?.querySelector('[aria-live="polite"]')?.textContent?.trim() ?? null,
        sessionAlert: panel?.querySelector('[role="alert"]')?.textContent?.trim() ?? null,
        settingsBusy: settings?.getAttribute('aria-busy') ?? null,
        settingsControls: [...(settings?.querySelectorAll('select, input, button') ?? [])].map((control) => ({
          label: control.getAttribute('aria-label'),
          disabled: control.disabled,
          value: control.value
        })),
        tokenPresent: Boolean(localStorage.getItem('fx-platform-auth-token')),
        marketResources
      }
    }, TRADE_PANEL_SELECTOR)
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`${message}; route state: ${JSON.stringify(diagnostic)}`)
  }
}

async function submitBrowserOrder(page, { side, tabIndex, values, reduceOnly = false, label }) {
  const beforeIds = new Set((await orders({ size: 500 })).map((order) => order.id))
  const tabSelected = await page.evaluate((panelSelector, index) => {
    const panel = document.querySelector(panelSelector)
    const tabs = panel?.querySelector('[role="tablist"] [role="tab"]')?.closest('[role="tablist"]')
    const button = tabs?.querySelectorAll('button')[index]
    button?.click()
    return Boolean(button)
  }, TRADE_PANEL_SELECTOR, tabIndex)
  assert(tabSelected, `${label} order-type tab must be available`)
  await page.waitForFunction((panelSelector, index) => {
    const panel = document.querySelector(panelSelector)
    const tabs = panel?.querySelector('[role="tablist"] [role="tab"]')?.closest('[role="tablist"]')
    const button = tabs?.querySelectorAll('button')[index]
    return button?.getAttribute('aria-selected') === 'true'
  }, `${label} order-type tab selected`, TRADE_PANEL_SELECTOR, tabIndex)
  for (let index = 0; index < values.length; index += 1) {
    const changed = await page.evaluate((panelSelector, targetSide, inputIndex, value) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const inputs = [...(section?.querySelectorAll('input[inputmode="decimal"]:not([disabled])') ?? [])]
      const input = inputs[inputIndex]
      if (!input) return false
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    }, TRADE_PANEL_SELECTOR, side, index, values[index])
    assert(changed, `${label} input ${index} must be editable`)
    await page.waitForFunction((panelSelector, targetSide, inputIndex, expected) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const inputs = [...(section?.querySelectorAll('input[inputmode="decimal"]:not([disabled])') ?? [])]
      const input = inputs[inputIndex]
      return input?.value === String(expected) && input.defaultValue === String(expected)
    }, `${label} input ${index} controlled state`, TRADE_PANEL_SELECTOR, side, index, values[index])
  }
  if (reduceOnly) {
    const checked = await waitFor(() => page.evaluate((panelSelector, targetSide) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const input = section?.querySelector('section[aria-label="Perpetual order options"] input[type="checkbox"]')
      if (!input || input.disabled) return false
      if (!input.checked) {
        input.click()
        return false
      }
      return input.checked
    }, TRADE_PANEL_SELECTOR, side), `${label} reduce-only control`, 15000)
    assert(checked, `${label} must visibly enable reduce-only`)
  }
  try {
    const clicked = await waitFor(() => page.evaluate((panelSelector, targetSide) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const button = section?.querySelector('[data-trading-action="submit-order"]')
      const balance = button?.previousElementSibling
      const ready = [...(balance?.querySelectorAll('strong') ?? [])]
        .some((value) => !value.textContent?.trim().startsWith('-'))
      if (!button || button.disabled || !ready) return false
      button.click()
      return true
    }, TRADE_PANEL_SELECTOR, side), `${label} ready submit control`, 15000)
    assert(clicked, `${label} submit control must be enabled`)
    const confirmationState = await waitFor(() => page.evaluate(() => {
      if (document.querySelector('section[role="dialog"] > dl')) return 'dialog'
      return localStorage.getItem('fx-trade-confirm-skip') === 'true' ? 'skipped' : null
    }), `${label} confirmation`, 5000)
    if (confirmationState === 'dialog') {
      const confirmed = await waitFor(() => page.evaluate((panelSelector, targetSide) => {
        const panel = document.querySelector(panelSelector)
        const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
        const section = sections[targetSide === 'buy' ? 0 : 1]
        const button = section?.querySelector('[data-trading-action="submit-order"]')
        const balance = button?.previousElementSibling
        const ready = [...(balance?.querySelectorAll('strong') ?? [])]
          .some((value) => !value.textContent?.trim().startsWith('-'))
        if (!button || button.disabled || !ready) return false
        const dialog = document.querySelector('section[role="dialog"] > dl')?.closest('section[role="dialog"]')
        const skip = dialog?.querySelector('input[type="checkbox"]')
        const submit = dialog?.querySelector('footer button:last-of-type')
        if (!dialog || !skip || !submit) return false
        if (!skip.checked) skip.click()
        submit.click()
        return true
      }, TRADE_PANEL_SELECTOR, side), `${label} ready confirmation`, 15000)
      assert(confirmed, `${label} confirmation dialog must submit`)
    }
    const created = await waitFor(async () => {
      const created = (await orders({ size: 500 }))
        .filter((order) => !beforeIds.has(order.id))
        .toSorted((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
      return created[0] ?? false
    }, `${label} resulting REST order`, 15000)
    if (reduceOnly) assert(created.reduceOnly === true, `${label} resulting order must be reduce-only`)
    await page.waitForFunction((panelSelector, targetSide) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const button = section?.querySelector('[data-trading-action="submit-order"]')
      return Boolean(button && !button.disabled)
    }, `${label} browser submission settled`, TRADE_PANEL_SELECTOR, side)
    return created
  } catch (error) {
    const diagnostic = await page.evaluate((panelSelector, targetSide) => {
      const panel = document.querySelector(panelSelector)
      const sections = [...(panel?.querySelectorAll('section[data-price-precision]') ?? [])]
      const section = sections[targetSide === 'buy' ? 0 : 1]
      const button = section?.querySelector('[data-trading-action="submit-order"]')
      const balance = button?.previousElementSibling
      const settings = document.querySelector('[aria-label="Perpetual trading settings"]')
      return {
        button: button ? [button.textContent?.trim(), button.disabled, button.getAttribute('title')] : null,
        action: button?.getAttribute('data-trading-action') ?? null,
        balance: [...(balance?.querySelectorAll('strong') ?? [])].map((value) => value.textContent?.trim()),
        inputs: [...(section?.querySelectorAll('input') ?? [])].map((input) => ({
          label: input.getAttribute('aria-label'),
          disabled: input.disabled,
          value: input.value
        })),
        notice: panel?.querySelector('[role="status"]')?.textContent?.trim() ?? null,
        session: panel?.querySelector('[aria-live="polite"]')?.textContent?.trim() ?? null,
        sessionAlert: panel?.querySelector('[role="alert"]')?.textContent?.trim() ?? null,
        settingsBusy: settings?.getAttribute('aria-busy') ?? null,
        settingsControls: [...(settings?.querySelectorAll('select, input, button') ?? [])].map((control) => ({
          label: control.getAttribute('aria-label'),
          disabled: control.disabled
        })),
        source: document.querySelector('[data-source]')?.textContent?.trim() ?? null,
        tokenPresent: Boolean(localStorage.getItem('fx-platform-auth-token')),
        skipConfirm: localStorage.getItem('fx-trade-confirm-skip')
      }
    }, TRADE_PANEL_SELECTOR, side)
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`${message}; browser state: ${JSON.stringify(diagnostic)}`)
  }
}

async function cancelBrowserOrder(page, orderId, label) {
  await page.navigate(`${webBaseUrl}/orders?ui-loop=${runId}`)
  await waitForPageReady(page, '/orders')
  const clicked = await waitFor(() => page.evaluate((targetOrderId) => {
    const actions = [...document.querySelectorAll('[data-order-id]')]
      .find((element) => element.getAttribute('data-order-id') === targetOrderId)
    const button = actions?.querySelectorAll(':scope > button')[2]
    if (!button) return false
    button.click()
    return true
  }, orderId), `${label} UI action`, 15000)
  assert(clicked, `${label} must click the real Orders-page action`)
  const confirmed = await waitFor(() => page.evaluate(() => {
    const dialog = document.getElementById('order-cancel-title')?.closest('section[role="dialog"]')
    const button = dialog?.querySelector('button:not([disabled])')
    if (!button) return false
    button.click()
    return true
  }), `${label} confirmation`, 5000)
  assert(confirmed, `${label} must confirm the real Orders-page action`)
  const canceled = await waitFor(async () => {
    const order = (await orders({ size: 500 })).find((candidate) => candidate.id === orderId)
    return order && ['CANCELED', 'CANCELLED'].includes(order.status) ? order : false
  }, `${label} resulting REST state`, 15000)
  return canceled
}

async function startAccountEventObserver() {
  const page = await createCdpPage(browser.port)
  const runtimeErrors = []
  const network = { webSockets: [], sentFrames: [], receivedFrames: [] }
  page.on('Runtime.exceptionThrown', (event) => runtimeErrors.push(event.exceptionDetails?.text ?? 'runtime exception'))
  page.on('Log.entryAdded', (event) => {
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry)
  })
  page.on('Network.webSocketCreated', (event) => network.webSockets.push(event.url ?? ''))
  page.on('Network.webSocketFrameSent', (event) => network.sentFrames.push(event.response?.payloadData ?? ''))
  page.on('Network.webSocketFrameReceived', (event) => network.receivedFrames.push(event.response?.payloadData ?? ''))
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Log.enable')
  await page.send('Network.enable')
  await setViewport(page, VIEWPORTS.find((viewport) => viewport.name === 'web-desktop'))
  await installBrowserSession(page, webBaseUrl, { 'fx-platform-auth-token': userToken })
  const route = `/trade/perpetual/${encodeURIComponent(PERP_SYMBOL)}?observer=${runId}`
  await page.navigate(`${webBaseUrl}${route}`)
  await waitForPageReady(page, route)
  await waitForStompSubscription(network, '/user/queue/trading-events')
  return {
    network,
    close: async () => {
      assertNoRuntimeErrors(runtimeErrors, 'authenticated account-event observer')
      await page.close()
    }
  }
}

async function assertLiquidationNotifications(liquidationOrders, label) {
  assert(accountEventObserver, `${label} requires the authenticated browser account-event observer`)
  await waitFor(() => liquidationOrders.every((order) => accountEventObserver.network.receivedFrames.some((frame) =>
    frame.includes('"type":"LIQUIDATION"')
      && frame.includes(String(order.parentPositionId))
      && frame.includes(String(order.id))
  )), `${label} authenticated WebSocket LIQUIDATION notifications`, 10000)
}

async function assertBrowserObservedJourneyEvents() {
  assert(accountEventObserver, 'the P0 journey requires its authenticated browser observer')
  for (const type of ['TRADE_CREATED', 'TRANSFER_COMPLETED', 'MARGIN_ADJUSTED', 'FUNDING_SETTLED', 'LIQUIDATION', 'DEMO_RESET']) {
    assert(
      accountEventObserver.network.receivedFrames.some((frame) => frame.includes(`"type":"${type}"`)),
      `authenticated browser must observe the real ${type} journey event`
    )
  }
}

async function captureBrowserEvidence(mode, viewport) {
  const page = await createCdpPage(browser.port)
  const runtimeErrors = []
  const network = { webSockets: [], sentFrames: [], receivedFrames: [] }
  page.on('Runtime.exceptionThrown', (event) => runtimeErrors.push(event.exceptionDetails?.text ?? 'runtime exception'))
  page.on('Log.entryAdded', (event) => {
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry)
  })
  page.on('Network.webSocketCreated', (event) => network.webSockets.push(event.url ?? ''))
  page.on('Network.webSocketFrameSent', (event) => network.sentFrames.push(event.response?.payloadData ?? ''))
  page.on('Network.webSocketFrameReceived', (event) => network.receivedFrames.push(event.response?.payloadData ?? ''))
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Log.enable')
  await page.send('Network.enable')
  await setViewport(page, viewport)
  try {
    if (viewport.surface === 'web') {
      return await captureWebCriticalPaths(page, mode, viewport, runtimeErrors, network)
    }
    return await captureAdminCriticalPaths(page, mode, viewport, runtimeErrors)
  } finally {
    await page.close()
  }
}

async function captureWebCriticalPaths(page, mode, viewport, runtimeErrors, network) {
  if (mode.id === 'RECOVERY') {
    await applySourceMode(SOURCE_MODES.find((candidate) => candidate.id === 'LOCAL_SIMULATED'))
  } else {
    await applySourceMode(mode)
  }
  await installBrowserSession(page, webBaseUrl, {
    'fx-platform-auth-token': userToken
  })
  const routes = [
    `/trade/spot/${encodeURIComponent(SPOT_SYMBOL)}?smoke=${runId}`,
    `/trade/perpetual/${encodeURIComponent(PERP_SYMBOL)}?smoke=${runId}`,
    '/wallet',
    '/orders',
    '/positions',
    '/settings'
  ]
  const evidence = []
  for (const route of routes) {
    await page.navigate(`${webBaseUrl}${route}`)
    await waitForPageReady(page, route)
    if (route.startsWith('/trade/')) {
      await page.waitForFunction(() => Boolean(document.querySelector('[data-source]')), 'market source badge')
      const symbol = route.startsWith('/trade/perpetual/') ? PERP_SYMBOL : SPOT_SYMBOL
      if (mode.id === 'RECOVERY' && symbol === PERP_SYMBOL) {
        await waitForStompSubscription(network, `/topic/market/source-changes/${PERP_SYMBOL}`)
        await exerciseVisibleRecovery(page, mode)
      } else {
        const actual = await api(`/api/market/quotes/${symbol}`)
        await page.waitForFunction((provider) => document.body.textContent?.toUpperCase().includes(provider.toUpperCase()), 'actual provider label', actual.providerCode)
      }
      if (viewport.mobile) {
        const opened = await page.evaluate(() => {
          const tradeButton = document.querySelector('[data-testid="mobile-trade-action"]')
          tradeButton?.click()
          return Boolean(tradeButton)
        })
        assert(opened, `${route} mobile Trade action must be available`)
        await page.waitForFunction(() => {
          const layer = document.getElementById('mobile-order-sheet-title')?.closest('[aria-hidden]')
          const side = layer?.querySelector('section[data-price-precision]')
          const panel = side?.parentElement?.closest('section[aria-label]')
          const rect = panel?.getBoundingClientRect()
          return layer?.getAttribute('aria-hidden') === 'false'
            && Boolean(rect && rect.width > 0 && rect.height > 0)
        }, 'mobile real order sheet')
      } else {
        await page.waitForFunction(
          (panelSelector) => Boolean(document.querySelector(panelSelector)?.querySelector('section[data-price-precision]')),
          'desktop real order controls',
          TRADE_PANEL_SELECTOR
        )
      }
    }
    await assertPageLayout(page, route)
    const screenshotPath = await captureScreenshot(page, mode, viewport, route)
    evidence.push({ route, screenshotPath })
  }
  assertNoRuntimeErrors(runtimeErrors, `${mode.id}/${viewport.name}`)
  return evidence
}

async function waitForStompSubscription(network, destination) {
  await waitFor(
    () => network.webSockets.some((url) => /\/ws(?:\?|$)/.test(url))
      && network.sentFrames.some((frame) => frame.includes('SUBSCRIBE') && frame.includes(destination)),
    `real STOMP subscription ${destination}`,
    15000
  )
}

async function captureAdminCriticalPaths(page, mode, viewport, runtimeErrors) {
  await applySourceMode(mode)
  await installBrowserSession(page, adminBaseUrl, {
    'fx-platform-admin-token': adminToken,
    'fx-platform-admin-refresh-token': adminRefreshToken ?? '',
    'fx-platform-admin-authorities': JSON.stringify(adminAuthorities)
  })
  const routes = ['/market/status', '/products/symbol-bindings', '/market/funding-config', `/accounts/${accountId}`]
  const evidence = []
  for (const route of routes) {
    await page.navigate(`${adminBaseUrl}${route}`)
    await waitForPageReady(page, route)
    assert(!(await page.evaluate(() => /admin login|管理员登录/i.test(document.body.innerText))), `Admin route redirected to login: ${route}`)
    if (route === '/market/status') {
      const status = await adminApi('/api/admin/market/status')
      await page.waitForFunction((sourceMode, providerStatus) => {
        const text = document.body.innerText
        return text.includes(sourceMode) && text.includes(providerStatus) && !document.querySelector('.state-block.loading')
      }, 'Admin market status API values visible', status.sourceMode, status.providerStatus)
    }
    if (route === '/products/symbol-bindings') {
      await assertAdminBindingMode(page, mode)
    }
    await assertPageLayout(page, route)
    const screenshotPath = await captureScreenshot(page, mode, viewport, route)
    evidence.push({ route, screenshotPath })
  }
  assertNoRuntimeErrors(runtimeErrors, `${mode.id}/${viewport.name}`)
  return evidence
}

async function assertAdminBindingMode(page, mode) {
  const providerCodes = ['binance-usdm', 'okx-swap', 'local-perp']
  const selected = await waitFor(() => page.evaluate((symbol) => {
    const select = [...document.querySelectorAll('select')].find((candidate) =>
      [...candidate.options].some((option) => option.textContent?.includes(symbol))
    )
    const option = select && [...select.options].find((candidate) => candidate.textContent?.includes(symbol))
    if (!select || !option) return false
    if (select.value !== option.value) {
      const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
      setter?.call(select, option.value)
      select.dispatchEvent(new Event('input', { bubbles: true }))
      select.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return true
  }, PERP_SYMBOL), `Admin ${PERP_SYMBOL} symbol option`, 15000)
  assert(selected, `Admin binding page must expose ${PERP_SYMBOL}`)
  const rows = await waitFor(() => page.evaluate((codes) => {
    const tableRows = [...document.querySelectorAll('tbody tr')].map((row) =>
      [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim() ?? '')
    )
    const result = Object.fromEntries(codes.map((code) => {
      const row = tableRows.find((cells) => cells[0]?.toLowerCase().includes(code))
      return [code, row?.[3] ?? null]
    }))
    return Object.values(result).every(Boolean) ? result : null
  }, providerCodes), `Admin ${PERP_SYMBOL} binding rows`, 15000)
  for (const code of providerCodes) {
    const enabled = mode.enabled.has(code)
    const text = String(rows[code]).toLowerCase()
    const visibleEnabled = /启用|enabled|true/.test(text)
    const visibleDisabled = /停用|disabled|false/.test(text)
    assert(
      enabled ? visibleEnabled : visibleDisabled,
      `Admin ${mode.id} must display ${code} enabled=${enabled}, got ${rows[code]}`
    )
  }
}

async function installBrowserSession(page, baseUrl, entries) {
  await page.navigate(baseUrl)
  await page.evaluate((values) => {
    localStorage.clear()
    sessionStorage.clear()
    for (const [key, value] of Object.entries(values)) {
      if (value) localStorage.setItem(key, value)
    }
  }, entries)
}

async function assertVisibleSourceNotification(page, before, after) {
  const visible = await waitFor(() => page.evaluate((previousProvider, provider, previousSource, source, symbol) => {
    const status = document.querySelector('[role="status"][data-market-source-change]')
    if (!status) return null
    const text = status.textContent?.toUpperCase() ?? ''
    const style = getComputedStyle(status)
    const rect = status.getBoundingClientRect()
    const result = {
      text: status.textContent,
      hidden: status.getAttribute('aria-hidden'),
      previousProvider: status.getAttribute('data-previous-provider'),
      currentProvider: status.getAttribute('data-current-provider'),
      previousSource: status.getAttribute('data-previous-source'),
      currentSource: status.getAttribute('data-current-source'),
      symbol: status.getAttribute('data-symbol'),
      display: style.display,
      visibility: style.visibility,
      opacity: Number(style.opacity),
      width: rect.width,
      height: rect.height,
      onScreen: rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth
    }
    return text.includes(previousProvider.toUpperCase())
      && text.includes(provider.toUpperCase())
      && result.previousProvider?.toUpperCase() === previousProvider.toUpperCase()
      && result.currentProvider?.toUpperCase() === provider.toUpperCase()
      && result.previousSource?.toUpperCase() === previousSource.toUpperCase()
      && result.currentSource?.toUpperCase() === source.toUpperCase()
      && result.symbol === symbol
      && result.hidden !== 'true'
      && result.display !== 'none'
      && result.visibility !== 'hidden'
      && result.opacity > 0
      && result.width > 0
      && result.height > 0
      && result.onScreen
      ? result
      : null
  }, before.providerCode, after.providerCode, before.sourceMode, after.sourceMode, PERP_SYMBOL), 'visible on-screen source jump notification', 6000)
  assert(visible?.text && visible.onScreen, 'MARKET_SOURCE_CHANGED notification must remain visibly readable during recovery')
}

async function exerciseVisibleRecovery(page, mode) {
  let startedAt = new Date().toISOString()
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    await applySourceMode(SOURCE_MODES.find((candidate) => candidate.id === 'LOCAL_SIMULATED'))
    const before = await waitFor(async () => {
      const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
      return quote.sourceMode === 'LOCAL_SIMULATED' ? quote : false
    }, 'LOCAL_SIMULATED recovery baseline', 15000)
    await page.waitForFunction((provider) => document.body.textContent?.toUpperCase().includes(provider.toUpperCase()), 'local recovery baseline visible', before.providerCode)

    startedAt = new Date().toISOString()
    await applySourceMode(mode)
    let after
    try {
      after = await waitFor(async () => {
        const quote = await api(`/api/market/quotes/${PERP_SYMBOL}`)
        return quote.sourceMode === 'PUBLIC_EXTERNAL' ? quote : false
      }, 'public provider recovery', 20000)
    } catch (error) {
      if (attempt < 3) continue
      const actual = await api(`/api/market/quotes/${PERP_SYMBOL}`)
      assert(actual.sourceMode === 'LOCAL_SIMULATED', `unavailable public recovery must fall back to LOCAL_SIMULATED, got ${actual.sourceMode}`)
      const providerHealth = await assertRecoveryUnavailableFromProviderHealth(startedAt)
      await page.waitForFunction((sourceMode, provider) => {
        const badge = document.querySelector(`[data-source="${sourceMode}"]`)
        return Boolean(badge && badge.textContent?.toUpperCase().includes(provider.toUpperCase()))
      }, 'actual fallback source remains visible', actual.sourceMode, actual.providerCode)
      sourceEvidence.push({
        mode: mode.id,
        sourceJump: 'public recovery unavailable; actual local fallback remained visible but recovery jump was not satisfied',
        actual: pickSourceMetadata(actual),
        providerHealth
      })
      return { unavailable: true, actual: pickSourceMetadata(actual), providerHealth }
    }

    try {
      await assertVisibleSourceNotification(page, before, after)
      sourceEvidence.push({
        mode: mode.id,
        sourceJump: `${before.providerCode}/${before.sourceMode} -> ${after.providerCode}/${after.sourceMode}`,
        notification: 'MARKET_SOURCE_CHANGED',
        attempt
      })
      return
    } catch (error) {
      if (attempt === 3) throw error
    }
  }
  throw new Error('recovery source jump did not produce visible notification evidence')
}

async function assertRecoveryUnavailableFromProviderHealth(startedAt) {
  const providers = await adminApi('/api/admin/market/data-providers')
  const external = providers.filter((provider) => ['binance-usdm', 'okx-swap'].includes(provider.code))
  assert(external.length === 2, 'recovery health evidence requires Binance USD-M and OKX Swap providers')
  const startedAtMs = Date.parse(startedAt)
  for (const provider of external) {
    const lastFailureAtMs = Date.parse(provider.lastFailureAt)
    const lastSuccessAtMs = Date.parse(provider.lastSuccessAt)
    assert(
      String(provider.healthStatus).toUpperCase() === 'DOWN'
        && number(provider.failureCount) > 0
        && Number.isFinite(lastFailureAtMs)
        && lastFailureAtMs >= startedAtMs - 1000
        && (!Number.isFinite(lastSuccessAtMs) || lastSuccessAtMs <= lastFailureAtMs),
      `${provider.code} remained local without a current DOWN state and latest failure from the recovery attempt`
    )
  }
  return external.map((provider) => ({
    code: provider.code,
    healthStatus: provider.healthStatus,
    failureCount: provider.failureCount,
    lastFailureAt: provider.lastFailureAt,
    lastSuccessAt: provider.lastSuccessAt
  }))
}

async function waitForPageReady(page, route) {
  await page.waitForFunction((expectedPath) => {
    const body = document.body?.innerText ?? ''
    return window.location.pathname === expectedPath.split('?')[0]
      && body.trim().length > 40
      && !document.querySelector('vite-error-overlay')
  }, `page ready ${route}`, route)
}

async function assertPageLayout(page, route) {
  const state = await page.evaluate(() => ({
    textLength: document.body?.innerText?.trim().length ?? 0,
    bodyWidth: document.body?.scrollWidth ?? 0,
    viewportWidth: window.innerWidth,
    frameworkOverlay: Boolean(document.querySelector('vite-error-overlay, #webpack-dev-server-client-overlay'))
  }))
  assert(state.textLength > 40, `${route} must render non-empty content`)
  assert(!state.frameworkOverlay, `${route} must not render a framework error overlay`)
  assert(state.bodyWidth <= state.viewportWidth + 1, `${route} has horizontal overflow ${state.bodyWidth} > ${state.viewportWidth}`)
}

async function captureScreenshot(page, mode, viewport, route) {
  const routeName = safeName(route.split('?')[0].replace(/^\//, '') || 'root')
  const path = join(screenshotsDir, `${mode.alias}-${viewport.name}-${routeName}.png`)
  const image = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  await writeFile(path, Buffer.from(image.data, 'base64'))
  screenshots.push(path)
  return path
}

function browserRuntimeErrorMessage(error) {
  return typeof error === 'string' ? error : error?.text ?? 'browser log error'
}

export function assertNoRuntimeErrors(errors, label) {
  const relevant = errors.filter((error) => !/ResizeObserver loop|favicon\.ico/i.test(browserRuntimeErrorMessage(error)))
  const details = relevant.map((error) => typeof error === 'string'
    ? error
    : `${browserRuntimeErrorMessage(error)} [source=${error.source ?? 'unknown'}, url=${error.url ?? 'unknown'}]`)
  assert(relevant.length === 0, `${label} browser runtime errors: ${details.join(' | ')}`)
}

async function launchBrowser() {
  const executable = browserCandidates().find(existsSync)
  assert(executable, 'Chrome or Edge is required; set SMOKE_BROWSER_PATH or CHROME_PATH')
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-usdt-demo-smoke-'))
  const child = spawn(executable, [
    '--headless=new',
    ...(process.getuid?.() === 0 ? ['--no-sandbox'] : []),
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--disable-features=RendererCodeIntegrity',
    '--remote-allow-origins=*',
    '--no-first-run',
    '--no-default-browser-check',
    '--window-size=1440,900',
    'about:blank'
  ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
  observeLocalChildSpawn(child, { label: 'browser' })
  const log = { label: 'browser', command: executable, output: '' }
  processLogs.push(log)
  child.stdout?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  child.stderr?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  await child.p0SpawnReady
  await waitFor(async () => {
    assertProcessRunning(child)
    return canFetch(`http://127.0.0.1:${port}/json/version`)
  }, 'Chrome DevTools endpoint', 30000)
  return {
    port,
    close: async () => {
      await terminateProcessTree(child, 'browser')
      await rm(userDataDir, { recursive: true, force: true })
    }
  }
}

async function createCdpPage(port) {
  const target = await createTarget(port)
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  const pending = new Map()
  const listeners = new Map()
  let nextId = 1
  const openSignal = operationSignal(10000)
  await new Promise((resolvePromise, rejectPromise) => {
    const finish = (error) => {
      socket.removeEventListener('open', onOpen)
      socket.removeEventListener('error', onError)
      openSignal.removeEventListener('abort', onAbort)
      if (error) rejectPromise(error)
      else resolvePromise()
    }
    const onOpen = () => finish()
    const onError = () => finish(new Error(`CDP socket failed to open for target ${target.id}`))
    const onAbort = () => finish(abortError(openSignal, `CDP socket open for target ${target.id}`))
    socket.addEventListener('open', onOpen, { once: true })
    socket.addEventListener('error', onError, { once: true })
    openSignal.addEventListener('abort', onAbort, { once: true })
    if (openSignal.aborted) onAbort()
  })
  const rejectPending = (error) => {
    const callbacks = [...pending.values()]
    pending.clear()
    for (const callback of callbacks) callback.reject(error)
  }
  const onSocketError = () => rejectPending(new Error(`CDP socket error for target ${target.id}`))
  const onSocketClose = () => rejectPending(new Error(`CDP socket closed for target ${target.id}`))
  const onShutdown = () => rejectPending(interruptionError ?? new Error('Smoke interrupted during CDP command'))
  socket.addEventListener('error', onSocketError)
  socket.addEventListener('close', onSocketClose)
  shutdownController.signal.addEventListener('abort', onShutdown, { once: true })
  socket.addEventListener('message', (message) => {
    const payload = JSON.parse(message.data)
    if (payload.id && pending.has(payload.id)) {
      const callback = pending.get(payload.id)
      if (payload.error) callback.reject(new Error(payload.error.message))
      else callback.resolve(payload.result)
      return
    }
    for (const listener of listeners.get(payload.method) ?? []) listener(payload.params ?? {})
  })
  const send = (method, params = {}) => {
    if (socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error(`CDP socket is not open for ${method}`))
    }
    const commandSignal = operationSignal(30000)
    return new Promise((resolvePromise, rejectPromise) => {
      const id = nextId++
      const finish = (settle, value) => {
        pending.delete(id)
        commandSignal.removeEventListener('abort', onAbort)
        settle(value)
      }
      const callback = {
        resolve: (value) => finish(resolvePromise, value),
        reject: (error) => finish(rejectPromise, error)
      }
      const onAbort = () => callback.reject(shutdownController.signal.aborted
        ? abortError(shutdownController.signal, `CDP command ${method}`)
        : new Error(`CDP command ${method} timed out`))
      pending.set(id, callback)
      commandSignal.addEventListener('abort', onAbort, { once: true })
      if (commandSignal.aborted) {
        onAbort()
        return
      }
      try {
        socket.send(JSON.stringify({ id, method, params }))
      } catch (error) {
        callback.reject(error)
      }
    })
  }
  return {
    send,
    on(method, listener) {
      if (!listeners.has(method)) listeners.set(method, new Set())
      listeners.get(method).add(listener)
    },
    async navigate(url) {
      await send('Page.navigate', { url })
      await waitFor(() => this.evaluate(() => document.readyState === 'complete'), `load ${url}`, 30000)
    },
    async evaluate(fn, ...args) {
      const result = await send('Runtime.evaluate', {
        expression: `(${fn})(${args.map((argument) => JSON.stringify(argument)).join(',')})`,
        awaitPromise: true,
        returnByValue: true
      })
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text ?? 'browser evaluation failed')
      return result.result?.value
    },
    async waitForFunction(fn, label, ...args) {
      return waitFor(() => this.evaluate(fn, ...args), label, 30000)
    },
    async close() {
      shutdownController.signal.removeEventListener('abort', onShutdown)
      rejectPending(new Error(`CDP target ${target.id} is closing`))
      try {
        const response = await fetch(`http://127.0.0.1:${port}/json/close/${encodeURIComponent(target.id)}`, {
          signal: operationSignal(5000, true)
        })
        assert(response.ok, `CDP target ${target.id} must close cleanly`)
      } finally {
        socket.removeEventListener('error', onSocketError)
        socket.removeEventListener('close', onSocketClose)
        if (socket.readyState < WebSocket.CLOSING) socket.close()
      }
    }
  }
}

async function createTarget(port) {
  const endpoint = `http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`
  const response = await fetch(endpoint, { method: 'PUT', signal: operationSignal(10000) }).catch(() => null)
  throwIfInterrupted()
  const actual = response?.ok ? response : await fetch(endpoint, { signal: operationSignal(10000) })
  assert(actual.ok, `CDP target creation failed with HTTP ${actual.status}`)
  const target = await actual.json()
  assert(target.webSocketDebuggerUrl, 'CDP target must expose webSocketDebuggerUrl')
  return target
}

async function setViewport(page, viewport) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: 1,
    mobile: viewport.mobile,
    screenWidth: viewport.width,
    screenHeight: viewport.height
  })
}

function browserCandidates() {
  return [...new Set([
    process.env.SMOKE_BROWSER_PATH,
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  ].filter(Boolean))]
}

async function api(path, options = {}) {
  throwIfInterrupted()
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: operationSignal(options.timeoutMs ?? 30000)
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.success === false) {
    const code = payload?.error?.code ?? payload?.code ?? `HTTP_${response.status}`
    const message = payload?.error?.message ?? payload?.message ?? response.statusText
    const error = new Error(`${code}: ${message}`)
    error.code = code
    error.status = response.status
    error.payload = payload
    throw error
  }
  return payload?.data ?? payload
}

function adminApi(path, options = {}) {
  return api(path, { ...options, token: adminToken })
}

async function rawJson(path) {
  throwIfInterrupted()
  const response = await fetch(`${apiBaseUrl}${path}`, { signal: operationSignal(30000) })
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`)
  return response.json()
}

async function expectApiError(path, options, acceptedCodes) {
  try {
    await api(path, options)
  } catch (error) {
    assert(error.status >= 400 && error.status < 500, `${path} must reject with a client-visible 4xx error`)
    assert(acceptedCodes.includes(error.code), `${path} rejected with unexpected code ${error.code}; expected ${acceptedCodes.join(', ')}`)
    return error
  }
  throw new Error(`${path} unexpectedly succeeded`)
}

function orderBody(patch, label = 'order') {
  const clientOrderId = smokeKey(label)
  return {
    accountId,
    symbol: patch.symbol,
    side: patch.side,
    orderType: patch.orderType,
    quantity: patch.quantity,
    lots: patch.quantity,
    price: patch.price ?? null,
    requestedPrice: patch.price ?? null,
    leverage: patch.leverage ?? 1,
    positionSide: patch.positionSide ?? 'BOTH',
    quantityUnit: patch.quantityUnit ?? 'BASE',
    marginMode: patch.marginMode ?? (patch.symbol?.endsWith('-PERP') ? 'CROSS' : 'CASH'),
    triggerPrice: patch.triggerPrice ?? null,
    triggerPriceType: patch.triggerPriceType ?? null,
    reduceOnly: patch.reduceOnly ?? false,
    attachedProtections: patch.attachedProtections ?? [],
    clientOrderId,
    idempotencyKey: clientOrderId
  }
}

function createOrder(label, patch) {
  return api('/api/trading/orders', {
    method: 'POST', token: userToken, body: orderBody(patch, label)
  })
}

function createOco(label, side, values) {
  return api('/api/trading/oco', {
    method: 'POST', token: userToken,
    body: {
      accountId,
      symbol: SPOT_SYMBOL,
      side,
      quantity: values.quantity,
      quantityUnit: 'BASE',
      limitPrice: values.limitPrice,
      stopTriggerPrice: values.stopTriggerPrice,
      triggerPriceType: 'LAST_PRICE',
      clientOrderId: smokeKey(label),
      idempotencyKey: smokeKey(`${label}-idempotency`)
    }
  })
}

async function waitOrderTerminal(orderId) {
  return waitFor(async () => {
    const row = (await orders({ size: 200 })).find((order) => order.id === orderId)
    return row && TERMINAL_ORDER_STATUSES.has(row.status) ? row : false
  }, `terminal order ${orderId}`, 30000)
}

async function waitOcoOutcome(groupId) {
  return waitFor(async () => {
    const rows = (await orders({ size: 200 })).filter((order) => order.contingencyGroupId === groupId)
    return rows.length === 2 && rows.every((order) => TERMINAL_ORDER_STATUSES.has(order.status)) ? rows : false
  }, `OCO outcome ${groupId}`, 30000)
}

function assertOcoOutcome(rows, label) {
  assert(rows.filter((order) => order.status === 'FILLED').length === 1, `${label} must fill exactly one leg`)
  assert(rows.filter((order) => ['CANCELED', 'CANCELLED'].includes(order.status)).length === 1, `${label} must cancel the peer leg`)
}

async function orders({ symbol, size = 100 } = {}) {
  const query = new URLSearchParams({ accountId, page: '0', size: String(size) })
  if (symbol) query.set('symbol', symbol)
  return pageContent(await api(`/api/trading/orders?${query}`, { token: userToken }))
}

async function trades({ symbol, size = 100 } = {}) {
  const query = new URLSearchParams({ accountId, page: '0', size: String(size) })
  if (symbol) query.set('symbol', symbol)
  return pageContent(await api(`/api/trading/trades?${query}`, { token: userToken }))
}

async function openPositions(symbol) {
  const query = new URLSearchParams({ accountId, page: '0', size: '200' })
  if (symbol) query.set('symbol', symbol)
  return pageContent(await api(`/api/trading/positions?${query}`, { token: userToken }))
}

async function positionHistory(symbol) {
  const query = new URLSearchParams({ accountId, page: '0', size: '200' })
  if (symbol) query.set('symbol', symbol)
  return pageContent(await api(`/api/trading/positions/history?${query}`, { token: userToken }))
}

function accountSummary() {
  return api(`/api/accounts/${accountId}/summary`, { token: userToken })
}

function walletBalances() {
  return api(`/api/accounts/${accountId}/wallet-balances`, { token: userToken })
}

function accountLedger() {
  return api(`/api/ledger?accountId=${accountId}`, { token: userToken })
}

async function fundingSettlements() {
  const response = await api(`/api/trading/funding/settlements?accountId=${accountId}&page=0&size=500`, { token: userToken })
  return pageContent(response)
}

async function accountFundsSnapshot() {
  const [summary, wallets, assetLedger, ledger] = await Promise.all([
    accountSummary(),
    walletBalances(),
    api(`/api/accounts/${accountId}/asset-ledger`, { token: userToken }),
    accountLedger()
  ])
  assertWalletInvariant(wallets)
  return { summary, wallets, assetLedger, ledger }
}

function usdtWallet(wallets) {
  return assetWallet(wallets, 'USDT')
}

function assetWallet(wallets, asset) {
  const wallet = wallets.find((candidate) => candidate.walletType === 'SPOT' && candidate.asset === asset)
  assert(wallet, `SPOT ${asset} wallet is required`)
  return wallet
}

function combinedUsdt(snapshot) {
  return number(usdtWallet(snapshot.wallets).total) + number(snapshot.summary.balance)
}

function assertWalletInvariant(wallets) {
  for (const wallet of wallets) {
    assert(number(wallet.total) >= 0 && number(wallet.available) >= 0 && number(wallet.locked) >= 0, `wallet values cannot be negative: ${JSON.stringify(wallet)}`)
    assertNear(number(wallet.total), number(wallet.available) + number(wallet.locked), 0.000001, `wallet total invariant ${wallet.walletType}/${wallet.asset}`)
  }
}

async function updatePositionMode(positionMode) {
  return api(`/api/accounts/${accountId}/position-mode`, {
    method: 'PATCH', token: userToken, body: { positionMode }
  })
}

async function updateSymbolSettings(symbol, values) {
  const settings = await api(`/api/accounts/${accountId}/trading-settings`, { token: userToken })
  const current = settings.symbols.find((candidate) => candidate.symbol === symbol)
  assert(current, `trading settings missing for ${symbol}`)
  return api(`/api/accounts/${accountId}/symbols/${encodeURIComponent(symbol)}/settings`, {
    method: 'PATCH', token: userToken,
    body: { ...values, expectedVersion: current.version }
  })
}

async function cancelAndCloseAll(label) {
  const cancel = await api('/api/trading/orders/cancel-all', {
    method: 'POST', token: userToken, body: { accountId, requestId: stableRequestId(`${label}-cancel`) }
  })
  const close = await api('/api/trading/positions/close-all', {
    method: 'POST', token: userToken, body: { accountId, requestId: stableRequestId(`${label}-close`) }
  })
  assertBatchItemsSucceeded(cancel.items, `${label} cancel-all`)
  assertBatchItemsSucceeded(close.items, `${label} close-all`)
  return { cancel, close }
}

function assertBatchItemsSucceeded(items, label) {
  assert(Array.isArray(items), `${label} must return per-item outcomes`)
  for (const item of items) {
    assert(item.errorCode == null, `${label} item ${item.positionId ?? item.orderId ?? 'unknown'} failed with ${item.errorCode}`)
    assert(item.status !== 'FAILED', `${label} item ${item.positionId ?? item.orderId ?? 'unknown'} must not fail`)
  }
}

async function assertDatabaseState() {
  const rest = await Promise.all([
    walletBalances(),
    api(`/api/accounts/${accountId}/asset-ledger`, { token: userToken }),
    orders({ size: 1000 }),
    trades({ size: 1000 }),
    openPositions(),
    api(`/api/trading/funding/settlements?accountId=${accountId}&page=0&size=1000`, { token: userToken })
  ])
  const raw = await runDbSql(`
    SELECT json_build_object(
      'wallets', (SELECT count(*) FROM core.wallet_balances WHERE account_id = '${sqlLiteral(accountId)}'),
      'assetLedger', (SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = '${sqlLiteral(accountId)}'),
      'orders', (SELECT count(*) FROM trading.orders WHERE account_id = '${sqlLiteral(accountId)}'),
      'trades', (SELECT count(*) FROM trading.trades WHERE account_id = '${sqlLiteral(accountId)}'),
      'positions', (
        (SELECT count(*) FROM trading.positions WHERE account_id = '${sqlLiteral(accountId)}' AND status = 'OPEN')
        + (SELECT count(*) FROM trading.spot_positions
           WHERE account_id = '${sqlLiteral(accountId)}' AND wallet_type = 'SPOT' AND quantity > 0)
      ),
      'funding', (SELECT count(*) FROM trading.funding_settlements WHERE account_id = '${sqlLiteral(accountId)}')
    )::text
  `)
  const db = JSON.parse(raw.split(/\r?\n/).filter(Boolean).at(-1))
  assert(db.wallets === rest[0].length, 'REST wallet balance count must match PostgreSQL')
  assert(db.assetLedger === rest[1].length, 'REST asset ledger count must match PostgreSQL')
  assert(db.orders === rest[2].length, 'REST order count must match PostgreSQL')
  assert(db.trades === rest[3].length, 'REST trade count must match PostgreSQL')
  assert(db.positions === rest[4].length, 'REST open position count must match PostgreSQL')
  assert(db.funding === pageContent(rest[5]).length, 'REST funding settlement count must match PostgreSQL')
  return { rest: rest.map((rows) => pageContent(rows).length), db }
}

async function runDbSql(sql) {
  const postgresContainerId = canonicalComposeIdentity?.postgres?.id
  if (typeof postgresContainerId !== 'string' || !/^[a-f0-9]{64}$/.test(postgresContainerId)) {
    throw new Error('P0_VERIFIED_POSTGRES_CONTAINER_REQUIRED')
  }
  const result = await runLocalCommand({
    id: 'canonical-db-sql',
    command: 'docker',
    args: ['exec', '-i', postgresContainerId, 'psql', '-U', 'postgres', '-d', smokeDatabase, '-tA', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
    cwd: projectRoot,
    stdin: `${sql}\n`,
    shell: false,
    signal: shutdownController.signal
  })
  if (result.status !== 0 || result.signal) throw new Error(`PostgreSQL assertion/fixture failed:\n${result.stderr ?? result.stdout}`)
  return String(result.stdout ?? '').trim()
}

async function dropSmokeDatabase() {
  if (!smokeDatabaseCreated) return
  if (!canonicalPostgres) throw new Error('P0_VERIFIED_POSTGRES_CONTAINER_REQUIRED')
  await cleanupCanonicalSmokeDatabase({
    ownership: { ...canonicalSmokeOwnership, inherited: true },
    postgres: canonicalPostgres
  })
  smokeDatabaseCreated = false
}

const requestIds = new Map()

function stableRequestId(label) {
  if (!requestIds.has(label)) requestIds.set(label, randomUUID())
  return requestIds.get(label)
}

function smokeKey(label) {
  return `usdt-demo-${runId}-${safeName(label)}`.slice(0, 120)
}

function pageContent(value) {
  if (Array.isArray(value)) return value
  return value?.content ?? value?.items ?? value?.records ?? []
}

function aligned(value, tick, direction = 'round') {
  const scaled = value / tick
  const units = direction === 'ceil' ? Math.ceil(scaled) : direction === 'floor' ? Math.floor(scaled) : Math.round(scaled)
  return String(Number((units * tick).toFixed(10)))
}

function number(value) {
  const parsed = Number(Array.isArray(value) ? value.at(-1) : value)
  return Number.isFinite(parsed) ? parsed : 0
}

function assertNear(actual, expected, tolerance, label) {
  assert(Math.abs(actual - expected) <= tolerance, `${label}: expected ${expected} ± ${tolerance}, got ${actual}`)
}

function appendFailure(primary, cleanupError, label) {
  const cleanup = cleanupError instanceof Error ? cleanupError : new Error(String(cleanupError))
  if (!primary) return new Error(`${label} failed: ${cleanup.message}`, { cause: cleanup })
  const original = primary instanceof Error ? primary : new Error(String(primary))
  return new AggregateError(
    [original, cleanup],
    `${original.message}; ${label} failed: ${cleanup.message}`
  )
}

async function step(name, action) {
  const startedAt = Date.now()
  try {
    throwIfInterrupted()
    const details = await action()
    throwIfInterrupted()
    results.push({ name, status: 'PASS', durationMs: Date.now() - startedAt, details })
    console.log(`PASS ${name}`)
    return details
  } catch (error) {
    results.push({
      name,
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error)
    })
    throw error
  }
}

async function writeReport(status, error) {
  for (const processLog of processLogs) {
    await writeFile(join(logsDir, `${safeName(processLog.label)}.log`), `${processLog.command}\n${processLog.output}`, 'utf8')
  }
  const reportPath = join(artifactRoot, 'report.json')
  await writeFile(reportPath, JSON.stringify({
    status,
    runId,
    startedCommands: STARTUP_COMMANDS,
    apiBaseUrl,
    webBaseUrl,
    adminBaseUrl,
    smokeDatabase,
    accountId,
    userId,
    sourceEvidence,
    screenshots,
    results,
    error: error instanceof Error ? { message: error.message, stack: error.stack } : error ? String(error) : null
  }, null, 2), 'utf8')
  return reportPath
}

async function waitFor(check, label, timeoutMs = 10000, signal) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    throwIfInterrupted()
    throwIfP0Aborted(signal)
    try {
      const value = await check()
      throwIfP0Aborted(signal)
      if (value) return value
    } catch (error) {
      throwIfP0Aborted(signal)
      lastError = error
    }
    await sleep(200, signal)
  }
  throwIfP0Aborted(signal)
  throw new Error(`Timed out waiting for ${label}${lastError ? `: ${lastError.message}` : ''}`)
}

function throwIfInterrupted() {
  if (interruptionError) throw interruptionError
}

function operationSignal(timeoutMs, ignoreShutdown = false) {
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  return ignoreShutdown
    ? timeoutSignal
    : AbortSignal.any([shutdownController.signal, timeoutSignal])
}

function abortError(signal, label) {
  return signal.reason instanceof Error ? signal.reason : new Error(`${label} aborted`)
}

function throwIfP0Aborted(signal) {
  if (!signal?.aborted) return
  throw signal.reason instanceof Error ? signal.reason : new Error('P0_ABORTED')
}

export async function fetchP0Local(
  url,
  { signal, timeoutMs = 2000, fetchImpl = fetch, request = {} } = {}
) {
  const endpoint = new URL(url)
  if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1') {
    throw new Error('P0_LOCAL_FETCH_ENDPOINT_INVALID')
  }
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
    throw new Error('P0_LOCAL_FETCH_TIMEOUT_INVALID')
  }
  throwIfP0Aborted(signal)
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  const combinedSignal = signal
    ? AbortSignal.any([signal, timeoutSignal])
    : timeoutSignal
  try {
    const response = await fetchImpl(url, { ...request, signal: combinedSignal })
    throwIfP0Aborted(signal)
    return response
  } catch (error) {
    if (signal?.aborted) throw abortError(signal, `local fetch ${endpoint.pathname}`)
    throw error
  }
}

async function canFetch(url, signal) {
  try {
    const response = await fetchP0Local(url, { signal, timeoutMs: 1500 })
    return response.ok || response.status < 500
  } catch (error) {
    throwIfInterrupted()
    throwIfP0Aborted(signal)
    return false
  }
}

async function freePort() {
  const net = await import('node:net')
  return new Promise((resolvePromise, rejectPromise) => {
    const server = net.createServer()
    server.unref()
    server.on('error', rejectPromise)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => resolvePromise(address.port))
    })
  })
}

function assertProcessRunning(child) {
  if (child?.p0SpawnError) throw child.p0SpawnError
  if (hasProcessExited(child)) throw new Error(`managed process exited early with code ${child.exitCode ?? child.signalCode}`)
}

async function stopManagedProcesses({
  signal,
  processes = managedProcesses,
  terminate = terminateProcessTree
} = {}) {
  const failures = []
  for (const child of processes.toReversed()) {
    throwIfP0Aborted(signal)
    try {
      await terminate(child, `managed process ${child.pid ?? 'unknown'}`, signal)
      throwIfP0Aborted(signal)
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, `failed to stop ${failures.length} managed process(es)`)
  }
}

async function terminateProcessTree(child, label, signal) {
  throwIfP0Aborted(signal)
  if (!child || hasProcessExited(child)) return
  let terminationError = ''
  if (!child.kill('SIGTERM')) terminationError = 'SIGTERM was not delivered'
  if (await waitForProcessExit(child, 5000, signal)) return
  throwIfP0Aborted(signal)
  if (!child.kill('SIGKILL')) terminationError = `${terminationError}; SIGKILL was not delivered`
  if (await waitForProcessExit(child, 3000, signal)) return
  throwIfP0Aborted(signal)
  throw new Error(`${label} did not exit after termination${terminationError ? `: ${terminationError.trim()}` : ''}`)
}

function hasProcessExited(child) {
  return child.exitCode !== null || child.signalCode !== null
}

function waitForProcessExit(child, timeoutMs, signal) {
  throwIfP0Aborted(signal)
  if (hasProcessExited(child)) return Promise.resolve(true)
  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false
    const finish = (exited, error) => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      child.removeListener('exit', onExit)
      signal?.removeEventListener('abort', onAbort)
      if (error) rejectPromise(error)
      else resolvePromise(exited)
    }
    const onExit = () => finish(true)
    const onAbort = () => finish(false, abortError(signal, 'managed process termination'))
    const timeout = setTimeout(() => finish(hasProcessExited(child)), timeoutMs)
    child.once('exit', onExit)
    signal?.addEventListener('abort', onAbort, { once: true })
    if (hasProcessExited(child)) onExit()
    if (signal?.aborted) onAbort()
  })
}

function registerLocalNativeProcess(child, descriptor, label) {
  if (!Number.isSafeInteger(child?.pid) || child.pid < 1) {
    throw new Error('P0_PROCESS_PID_INVALID')
  }
  child.processIdentity ??= {
    pid: child.pid,
    startedAt: `spawn:${randomUUID()}`,
    processFingerprint: sha256Text(JSON.stringify([
      descriptor.command,
      descriptor.args ?? [],
      descriptor.cwd ?? null,
      label
    ]))
  }
  localNativeProcessHandles.set(child.pid, child)
  return child.processIdentity
}

function observeLocalChildSpawn(child, {
  descriptor,
  label = 'local process',
  registerIdentity = false
} = {}) {
  let settled = false
  let resolveReady
  let rejectReady
  const ready = new Promise((resolvePromise, rejectPromise) => {
    resolveReady = resolvePromise
    rejectReady = rejectPromise
  })
  void ready.catch(() => {})
  const finish = (error) => {
    if (settled) return
    settled = true
    if (error) {
      child.p0SpawnError ??= error
      rejectReady(error)
    } else {
      resolveReady(child)
    }
  }
  child.once('error', (error) => {
    child.p0SpawnError ??= error
    finish(error)
  })
  child.once('spawn', () => {
    try {
      if (registerIdentity) registerLocalNativeProcess(child, descriptor, label)
      finish()
    } catch (error) {
      finish(error)
    }
  })
  child.once('exit', (status, childSignal) => {
    if (!settled) {
      finish(new Error(
        `P0_PROCESS_EXITED_BEFORE_SPAWN: ${label}/${status ?? childSignal ?? 'unknown'}`
      ))
    }
  })
  child.p0SpawnReady = ready
  return ready
}

export function acquireLocalNativeProcessHandle(pid) {
  const child = localNativeProcessHandles.get(pid)
  if (!child) return null
  return {
    async inspectIdentity({ signal } = {}) {
      throwIfP0Aborted(signal)
      return hasProcessExited(child) ? null : child.processIdentity
    },
    async terminateTree(resource, { signal } = {}) {
      await terminateProcessTree(child, `journaled process ${resource.id}`, signal)
      return {
        provider: 'DIRECT_CHILD',
        rootTerminated: true,
        treeTerminated: false
      }
    },
    async close({ signal } = {}) {
      throwIfP0Aborted(signal)
      if (hasProcessExited(child)) localNativeProcessHandles.delete(pid)
    }
  }
}

function appendTail(current, chunk, maxLength = 4000) {
  return `${current}${String(chunk)}`.slice(-maxLength)
}

function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

function safeName(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'artifact'
}

function sleep(timeoutMs, signal) {
  if (!signal) return new Promise((resolvePromise) => setTimeout(resolvePromise, timeoutMs))
  throwIfP0Aborted(signal)
  return new Promise((resolvePromise, rejectPromise) => {
    const timeout = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolvePromise()
    }, timeoutMs)
    const onAbort = () => {
      clearTimeout(timeout)
      signal.removeEventListener('abort', onAbort)
      rejectPromise(signal.reason instanceof Error ? signal.reason : new Error('P0_ABORTED'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function installP0SignalHandlers(handler) {
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, handler)
  return () => {
    for (const signal of ['SIGINT', 'SIGTERM']) process.removeListener(signal, handler)
  }
}

const WINDOWS_COMMAND_SHIMS = new Set(['npm.cmd', 'mvn.cmd'])
const LOCAL_LAUNCHER_ENVIRONMENT_KEYS = new Set([
  'COMSPEC',
  'COMPOSE_FILE',
  'COMPOSE_PROFILES',
  'COMPOSE_PROJECT_NAME',
  'DOCKER_CONTEXT',
  'DOCKER_HOST',
  'MAVEN_HOME',
  'MAVEN_CMD',
  'NPM_CMD',
  'PATH',
  'SYSTEMROOT',
  'WINDIR'
])

function scrubLocalLauncherEnvironment(environment = process.env) {
  const scrubbed = { ...environment }
  for (const key of Object.keys(scrubbed)) {
    if (LOCAL_LAUNCHER_ENVIRONMENT_KEYS.has(key.toUpperCase())) delete scrubbed[key]
  }
  return scrubbed
}

function trustedContainedRegularFile(candidate, root, errorCode) {
  if (!isAbsolute(candidate) || !existsSync(candidate)) throw new Error(errorCode)
  const metadata = lstatSync(candidate)
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.nlink !== 1) {
    throw new Error(errorCode)
  }
  const actual = realpathSync(candidate)
  const relativePath = relative(root, actual)
  const actualMetadata = lstatSync(actual)
  if (!isAbsolute(actual)
    || relativePath === '..'
    || relativePath.startsWith(`..${sep}`)
    || isAbsolute(relativePath)
    || !actualMetadata.isFile()
    || actualMetadata.isSymbolicLink()
    || actualMetadata.nlink !== 1) {
    throw new Error(errorCode)
  }
  return actual
}

function containedSiblingDirectories(toolRoot) {
  return readdirSync(toolRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => realpathSync(join(toolRoot, entry.name)))
    .filter((path) => {
      const relativePath = relative(toolRoot, path)
      return relativePath !== '..'
        && !relativePath.startsWith(`..${sep}`)
        && !isAbsolute(relativePath)
    })
}

export function resolveTrustedLocalToolchain() {
  const nodePath = realpathSync(process.execPath)
  const nodeRoot = realpathSync(dirname(nodePath))
  const toolRoot = realpathSync(dirname(nodeRoot))
  trustedContainedRegularFile(nodePath, nodeRoot, 'P0_TRUSTED_NODE_REQUIRED')

  let npmCli
  try {
    npmCli = trustedContainedRegularFile(
      join(nodeRoot, 'node_modules', 'npm', 'bin', 'npm-cli.js'),
      nodeRoot,
      'P0_TRUSTED_NPM_REQUIRED'
    )
  } catch (error) {
    throw new Error('P0_TRUSTED_NPM_REQUIRED', { cause: error })
  }

  const mavenCandidates = []
  for (const directory of containedSiblingDirectories(toolRoot)) {
    if (directory === nodeRoot || !basename(directory).startsWith('apache-maven-')) continue
    try {
      const mavenShim = trustedContainedRegularFile(
        join(directory, 'bin', 'mvn.cmd'),
        directory,
        'P0_TRUSTED_MAVEN_REQUIRED'
      )
      const mavenConfig = trustedContainedRegularFile(
        join(directory, 'bin', 'm2.conf'),
        directory,
        'P0_TRUSTED_MAVEN_REQUIRED'
      )
      const launchers = readdirSync(join(directory, 'boot'), { withFileTypes: true })
        .filter((entry) => entry.isFile() && /^plexus-classworlds-[0-9.]+\.jar$/.test(entry.name))
        .map((entry) => trustedContainedRegularFile(
          join(directory, 'boot', entry.name),
          directory,
          'P0_TRUSTED_MAVEN_REQUIRED'
        ))
      if (launchers.length !== 1) continue
      mavenCandidates.push({
        mavenHome: directory,
        mavenShim,
        mavenConfig,
        plexusLauncher: launchers[0]
      })
    } catch {}
  }
  if (mavenCandidates.length !== 1) throw new Error('P0_TRUSTED_MAVEN_REQUIRED')

  const javaCandidates = []
  for (const directory of containedSiblingDirectories(toolRoot)) {
    const roots = [directory]
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory()) roots.push(join(directory, entry.name))
    }
    for (const root of roots) {
      const candidate = join(root, 'bin', 'java.exe')
      if (!existsSync(candidate)) continue
      try {
        javaCandidates.push(trustedContainedRegularFile(
          candidate,
          toolRoot,
          'P0_TRUSTED_MAVEN_REQUIRED'
        ))
      } catch {}
    }
  }
  const uniqueJava = [...new Set(javaCandidates)]
  if (uniqueJava.length !== 1) throw new Error('P0_TRUSTED_MAVEN_REQUIRED')

  return {
    nodePath,
    nodeRoot,
    toolRoot,
    npmCli,
    ...mavenCandidates[0],
    javaPath: uniqueJava[0]
  }
}

function assertSafeWindowsCommandArgument(argument) {
  if (typeof argument !== 'string'
    || argument.length === 0
    || /[\0\r\n"&|<>^%!]/.test(argument)) {
    throw new Error('P0_LOCAL_COMMAND_ARGUMENT_UNSAFE')
  }
}

export function normalizeLocalCommandDescriptor(
  descriptor,
  { platform = process.platform, resolveToolchain = resolveTrustedLocalToolchain } = {}
) {
  if (!descriptor || typeof descriptor !== 'object' || Array.isArray(descriptor)
    || typeof descriptor.command !== 'string'
    || !Array.isArray(descriptor.args ?? [])) {
    throw new Error('P0_LOCAL_COMMAND_DESCRIPTOR_INVALID')
  }
  const args = [...(descriptor.args ?? [])]
  const normalized = {
    ...descriptor,
    args,
    env: scrubLocalLauncherEnvironment(descriptor.env),
    shell: false
  }
  if (platform !== 'win32') {
    const trustedPath = [
      dirname(realpathSync(process.execPath)),
      '/usr/local/sbin',
      '/usr/local/bin',
      '/usr/sbin',
      '/usr/bin',
      '/sbin',
      '/bin'
    ].filter((path, index, paths) => paths.indexOf(path) === index).join(delimiter)
    return {
      ...normalized,
      env: { ...normalized.env, PATH: trustedPath }
    }
  }
  const command = descriptor.command.toLowerCase()
  if (!WINDOWS_COMMAND_SHIMS.has(command)) return normalized
  for (const argument of args) assertSafeWindowsCommandArgument(argument)
  const toolchain = resolveToolchain()
  if (command === 'npm.cmd') {
    return {
      ...normalized,
      command: toolchain.nodePath,
      args: [toolchain.npmCli, ...args]
    }
  }
  if (!toolchain?.javaPath || !toolchain?.mavenHome
    || !toolchain?.mavenConfig || !toolchain?.plexusLauncher) {
    throw new Error('P0_TRUSTED_MAVEN_REQUIRED')
  }
  return {
    ...normalized,
    command: toolchain.javaPath,
    args: [
      '-classpath',
      toolchain.plexusLauncher,
      `-Dclassworlds.conf=${toolchain.mavenConfig}`,
      `-Dmaven.home=${toolchain.mavenHome}`,
      `-Dmaven.multiModuleProjectDirectory=${resolve(descriptor.cwd ?? process.cwd())}`,
      'org.codehaus.plexus.classworlds.launcher.Launcher',
      ...args
    ]
  }
}

export function runLocalCommand(descriptor) {
  const normalized = normalizeLocalCommandDescriptor(descriptor)
  throwIfP0Aborted(normalized.signal)
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(normalized.command, normalized.args, {
      cwd: normalized.cwd,
      env: normalized.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      shell: false,
      windowsHide: true
    })
    observeLocalChildSpawn(child, {
      descriptor: normalized,
      label: normalized.id ?? normalized.command,
      registerIdentity: true
    })
    let stdout = ''
    let stderr = ''
    let settled = false
    let abortTermination
    const removeAbortListener = () => normalized.signal?.removeEventListener('abort', onAbort)
    const finish = (error, result) => {
      if (settled) return
      settled = true
      removeAbortListener()
      if (error) rejectPromise(error)
      else resolvePromise(result)
    }
    const onAbort = () => {
      abortTermination ??= terminateProcessTree(
        child,
        `P0 command ${normalized.id ?? normalized.command}`
      )
    }
    const spawnRegistration = child.p0SpawnReady
      .then(() => normalized.onSpawn?.(child))
      .catch(async (error) => {
        if (child.processIdentity) {
          await terminateProcessTree(
            child,
            `unregistered P0 command ${normalized.id ?? normalized.command}`
          )
        }
        throw error
      })
    void spawnRegistration.catch((error) => finish(error))
    child.stdout?.on('data', (chunk) => { stdout = appendTail(stdout, chunk, 200000) })
    child.stderr?.on('data', (chunk) => { stderr = appendTail(stderr, chunk, 200000) })
    child.once('error', (error) => finish(error))
    child.once('exit', (status, childSignal) => {
      Promise.all([Promise.resolve(abortTermination), spawnRegistration]).then(() => {
        if (normalized.signal?.aborted) {
          finish(normalized.signal.reason instanceof Error
            ? normalized.signal.reason
            : new Error('P0_ABORTED'))
          return
        }
        finish(null, { status, signal: childSignal, stdout, stderr })
      }, (error) => finish(error))
    })
    normalized.signal?.addEventListener('abort', onAbort, { once: true })
    if (normalized.signal?.aborted) onAbort()
    child.stdin?.end(normalized.stdin ?? '')
  })
}

function redisRequest(args, { host = '127.0.0.1', port = 6379, signal } = {}) {
  throwIfP0Aborted(signal)
  const payload = Buffer.concat([
    Buffer.from(`*${args.length}\r\n`),
    ...args.flatMap((argument) => {
      const value = Buffer.from(String(argument))
      return [Buffer.from(`$${value.length}\r\n`), value, Buffer.from('\r\n')]
    })
  ])
  return new Promise((resolvePromise, rejectPromise) => {
    const socket = createConnection({ host, port })
    let settled = false
    let response = Buffer.alloc(0)
    const onAbort = () => finish(abortError(signal, 'local Redis request'))
    const finish = (error, value) => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', onAbort)
      socket.destroy()
      if (error) rejectPromise(error)
      else resolvePromise(value)
    }
    socket.setTimeout(5000, () => finish(new Error('P0_REDIS_TIMEOUT')))
    socket.once('error', (error) => finish(error))
    socket.once('connect', () => {
      if (signal?.aborted) return onAbort()
      socket.write(payload)
    })
    socket.on('data', (chunk) => {
      response = Buffer.concat([response, chunk])
      const lineEnd = response.indexOf('\r\n')
      if (lineEnd < 0) return
      const prefix = String.fromCharCode(response[0])
      const header = response.subarray(1, lineEnd).toString('utf8')
      if (prefix === '-') return finish(new Error(`P0_REDIS_ERROR: ${header}`))
      if (prefix === '+') return finish(null, header)
      if (prefix === ':') return finish(null, Number(header))
      if (prefix !== '$') return finish(new Error('P0_REDIS_PROTOCOL_INVALID'))
      const length = Number(header)
      if (length === -1) return finish(null, null)
      const start = lineEnd + 2
      if (!Number.isSafeInteger(length) || length < 0 || response.length < start + length + 2) return
      finish(null, response.subarray(start, start + length).toString('utf8'))
    })
    signal?.addEventListener('abort', onAbort, { once: true })
    if (signal?.aborted) onAbort()
  })
}

function encodeRedisRespCommand(args) {
  if (!Array.isArray(args) || args.length === 0) {
    throw new Error('P0_REDIS_COMMAND_INVALID')
  }
  return Buffer.concat([
    Buffer.from('*' + args.length + '\r\n'),
    ...args.flatMap((argument) => {
      const value = Buffer.from(String(argument))
      return [
        Buffer.from('$' + value.length + '\r\n'),
        value,
        Buffer.from('\r\n')
      ]
    })
  ])
}

function parseRedisRespFrame(buffer) {
  const lineEnd = buffer.indexOf('\r\n')
  if (lineEnd < 0) return { complete: false }
  if (buffer.length === 0) throw new Error('P0_REDIS_PROTOCOL_INVALID')
  const prefix = String.fromCharCode(buffer[0])
  const header = buffer.subarray(1, lineEnd).toString('utf8')
  const headerEnd = lineEnd + 2
  if (prefix === '+') {
    return { complete: true, consumed: headerEnd, value: header }
  }
  if (prefix === '-') {
    return {
      complete: true,
      consumed: headerEnd,
      error: new Error('P0_REDIS_ERROR: ' + header)
    }
  }
  if (prefix === ':') {
    if (!/^-?\d+$/.test(header)) throw new Error('P0_REDIS_PROTOCOL_INVALID')
    const value = Number(header)
    if (!Number.isSafeInteger(value)) throw new Error('P0_REDIS_PROTOCOL_INVALID')
    return { complete: true, consumed: headerEnd, value }
  }
  if (prefix !== '$' || !/^-?\d+$/.test(header)) {
    throw new Error('P0_REDIS_PROTOCOL_INVALID')
  }
  const length = Number(header)
  if (!Number.isSafeInteger(length) || length < -1) {
    throw new Error('P0_REDIS_PROTOCOL_INVALID')
  }
  if (length === -1) {
    return { complete: true, consumed: headerEnd, value: null }
  }
  const valueEnd = headerEnd + length
  if (buffer.length < valueEnd + 2) return { complete: false }
  if (buffer.subarray(valueEnd, valueEnd + 2).toString('utf8') !== '\r\n') {
    throw new Error('P0_REDIS_PROTOCOL_INVALID')
  }
  return {
    complete: true,
    consumed: valueEnd + 2,
    value: buffer.subarray(headerEnd, valueEnd).toString('utf8')
  }
}

export async function openRedisRespSession({
  host = '127.0.0.1',
  port = 6379,
  signal,
  connect = createConnection
} = {}) {
  throwIfP0Aborted(signal)
  if (host !== '127.0.0.1' || port !== 6379 || typeof connect !== 'function') {
    throw new Error('P0_VERIFIED_REDIS_CONTAINER_REQUIRED')
  }
  let state = 'OPEN'
  let socket
  let pending
  let response = Buffer.alloc(0)
  let connectSettled = false
  let closeCalled = false
  let resolveConnected
  let rejectConnected
  const connected = new Promise((resolvePromise, rejectPromise) => {
    resolveConnected = resolvePromise
    rejectConnected = rejectPromise
  })
  const takePending = () => {
    const current = pending
    if (!current) return undefined
    pending = undefined
    response = Buffer.alloc(0)
    current.abortSignal?.removeEventListener('abort', current.onAbort)
    return current
  }
  const destroySocket = () => {
    if (!socket || socket.destroyed) return
    try {
      socket.destroy()
    } catch {
      // The session is already terminal; destruction remains best effort.
    }
  }
  const fail = (cause) => {
    if (state === 'FAILED' || state === 'CLOSED') return
    const error = cause instanceof Error
      ? cause
      : new Error(String(cause ?? 'P0_REDIS_SESSION_FAILED'))
    state = 'FAILED'
    signal?.removeEventListener('abort', onAbort)
    takePending()?.reject(error)
    if (!connectSettled) {
      connectSettled = true
      rejectConnected(error)
    }
    destroySocket()
  }
  const onAbort = () => fail(abortError(signal, 'local Redis session'))
  const close = () => {
    if (closeCalled) return
    closeCalled = true
    if (state === 'OPEN') state = 'CLOSED'
    signal?.removeEventListener('abort', onAbort)
    takePending()?.reject(new Error('P0_REDIS_SESSION_CLOSED'))
    destroySocket()
  }

  try {
    socket = connect({ host, port })
  } catch (error) {
    state = 'FAILED'
    throw error
  }
  if (!socket
    || typeof socket.once !== 'function'
    || typeof socket.on !== 'function'
    || typeof socket.write !== 'function'
    || typeof socket.destroy !== 'function') {
    state = 'FAILED'
    destroySocket()
    throw new Error('P0_REDIS_SESSION_INVALID')
  }

  socket.once('connect', () => {
    if (connectSettled || state !== 'OPEN') return
    connectSettled = true
    resolveConnected()
  })
  socket.on('data', (chunk) => {
    if (state !== 'OPEN') return
    if (!pending) {
      fail(new Error('P0_REDIS_PROTOCOL_INVALID'))
      return
    }
    response = Buffer.concat([response, Buffer.from(chunk)])
    let parsed
    try {
      parsed = parseRedisRespFrame(response)
    } catch (error) {
      fail(error)
      return
    }
    if (!parsed.complete) return
    if (parsed.consumed !== response.length) {
      fail(new Error('P0_REDIS_PROTOCOL_INVALID'))
      return
    }
    const current = takePending()
    if (parsed.error) current.reject(parsed.error)
    else current.resolve(parsed.value)
  })
  socket.on('error', (error) => fail(error))
  socket.on('end', () => fail(new Error('P0_REDIS_SESSION_CLOSED')))
  socket.on('close', () => {
    if (state === 'OPEN') fail(new Error('P0_REDIS_SESSION_CLOSED'))
  })
  socket.setTimeout?.(5000, () => fail(new Error('P0_REDIS_TIMEOUT')))
  signal?.addEventListener('abort', onAbort, { once: true })
  if (signal?.aborted) onAbort()
  await connected

  return {
    get state() {
      return state
    },
    async command(args, { signal: commandSignal } = {}) {
      if (state !== 'OPEN') throw new Error('P0_REDIS_SESSION_CLOSED')
      if (pending) throw new Error('P0_REDIS_SESSION_BUSY')
      const payload = encodeRedisRespCommand(args)
      if (commandSignal?.aborted) {
        const error = abortError(commandSignal, 'local Redis command')
        fail(error)
        throw error
      }
      return new Promise((resolvePromise, rejectPromise) => {
        const onCommandAbort = () => {
          fail(abortError(commandSignal, 'local Redis command'))
        }
        pending = {
          resolve: resolvePromise,
          reject: rejectPromise,
          abortSignal: commandSignal,
          onAbort: onCommandAbort
        }
        response = Buffer.alloc(0)
        commandSignal?.addEventListener('abort', onCommandAbort, { once: true })
        if (commandSignal?.aborted) {
          onCommandAbort()
          return
        }
        try {
          socket.write(payload)
        } catch (error) {
          fail(error)
        }
      })
    },
    close
  }
}

function createInjectedRedisRequestSession(request, { host, port, signal }) {
  let state = 'OPEN'
  let busy = false
  return {
    get state() {
      return state
    },
    async command(args, { signal: commandSignal = signal } = {}) {
      if (state !== 'OPEN') throw new Error('P0_REDIS_SESSION_CLOSED')
      if (busy) throw new Error('P0_REDIS_SESSION_BUSY')
      if (commandSignal?.aborted) {
        state = 'FAILED'
        throw abortError(commandSignal, 'local Redis command')
      }
      busy = true
      try {
        return await request(args, { host, port, signal: commandSignal })
      } catch (error) {
        state = 'FAILED'
        throw error
      } finally {
        busy = false
      }
    },
    close() {
      if (state === 'OPEN') state = 'CLOSED'
    }
  }
}

export function createLoopbackRedisAdapter(
  request = redisRequest,
  resolveBinding,
  verifyTransactionBinding,
  options = {}
) {
  const transactionBindings = new AsyncLocalStorage()
  const verificationFailures = new WeakSet()
  const exactBinding = (binding) => {
    if (typeof binding?.id !== 'string' || !/^[a-f0-9]{64}$/.test(binding.id)
      || binding.image !== 'redis:7'
      || binding.host !== '127.0.0.1'
      || binding.hostPort !== 6379) {
      throw new Error('P0_VERIFIED_REDIS_CONTAINER_REQUIRED')
    }
    return {
      id: binding.id,
      image: binding.image,
      host: binding.host,
      hostPort: binding.hostPort
    }
  }
  const sameBinding = (left, right) => (
    left.id === right.id
    && left.image === right.image
    && left.host === right.host
    && left.hostPort === right.hostPort
  )
  const markVerificationFailure = (error) => {
    verificationFailures.add(error)
    return error
  }
  const normalizeThrown = (cause) => (
    cause instanceof Error ? cause : new Error(String(cause))
  )
  const resolveExpectedBinding = () => {
    if (typeof resolveBinding !== 'function'
      || typeof verifyTransactionBinding !== 'function') {
      throw markVerificationFailure(
        new Error('P0_REDIS_TRANSACTION_VERIFIER_REQUIRED')
      )
    }
    try {
      return exactBinding(resolveBinding())
    } catch (cause) {
      throw markVerificationFailure(normalizeThrown(cause))
    }
  }
  const verifyBinding = async (expectedBinding, stage, signal) => {
    let observedBinding
    try {
      observedBinding = exactBinding(await verifyTransactionBinding(
        structuredClone(expectedBinding),
        { stage, signal }
      ))
    } catch (cause) {
      throw markVerificationFailure(normalizeThrown(cause))
    }
    throwIfP0Aborted(signal)
    if (!sameBinding(expectedBinding, observedBinding)) {
      throw markVerificationFailure(
        new Error('P0_REDIS_CONTAINER_BINDING_MISMATCH')
      )
    }
    return observedBinding
  }
  const injectedCreateSession = options?.createSession
  if (injectedCreateSession !== undefined && typeof injectedCreateSession !== 'function') {
    throw new Error('P0_REDIS_SESSION_FACTORY_INVALID')
  }
  const createSession = injectedCreateSession
    ?? (request === redisRequest
      ? (details) => openRedisRespSession(details)
      : (details) => {
          if (typeof request !== 'function') {
            throw new Error('P0_REDIS_SESSION_FACTORY_INVALID')
          }
          return createInjectedRedisRequestSession(request, details)
        })
  const runVerifiedTransaction = async (operation, { signal } = {}) => {
    if (typeof operation !== 'function') throw new Error('P0_REDIS_TRANSACTION_INVALID')
    throwIfP0Aborted(signal)
    const active = transactionBindings.getStore()
    if (active) {
      if (active.session.state !== 'OPEN') {
        throw new Error('P0_REDIS_SESSION_CLOSED')
      }
      return operation()
    }
    const expectedBinding = resolveExpectedBinding()
    const preConnectBinding = await verifyBinding(
      expectedBinding,
      'PRE_CONNECT',
      signal
    )
    const session = await createSession({
      host: preConnectBinding.host,
      port: preConnectBinding.hostPort,
      signal
    })
    if (typeof session?.command !== 'function'
      || typeof session?.close !== 'function'
      || session.state !== 'OPEN') {
      await session?.close?.()
      throw new Error('P0_REDIS_SESSION_INVALID')
    }
    try {
      const postConnectBinding = await verifyBinding(
        expectedBinding,
        'POST_CONNECT',
        signal
      )
      return await transactionBindings.run(
        { binding: postConnectBinding, session },
        operation
      )
    } finally {
      await session.close()
    }
  }
  const executeRequest = (args, { signal } = {}) => {
    const active = transactionBindings.getStore()
    if (!active || active.session.state !== 'OPEN') {
      throw new Error('P0_REDIS_SESSION_CLOSED')
    }
    return active.session.command(args, { signal })
  }
  return {
    runVerifiedTransaction,
    async waitUntilReady({ signal } = {}) {
      const deadline = Date.now() + 30000
      let lastError
      while (Date.now() < deadline) {
        throwIfInterrupted()
        throwIfP0Aborted(signal)
        try {
          const response = await runVerifiedTransaction(
            () => executeRequest(['PING'], { signal }),
            { signal }
          )
          if (response === 'PONG') return
          lastError = new Error('P0_REDIS_PING_INVALID')
        } catch (error) {
          throwIfP0Aborted(signal)
          if (verificationFailures.has(error)) throw error
          lastError = error
        }
        await sleep(200, signal)
      }
      throwIfP0Aborted(signal)
      throw new Error(
        'Timed out waiting for local Redis'
        + (lastError ? ': ' + lastError.message : '')
      )
    },
    async setNx(key, value, { signal } = {}) {
      return runVerifiedTransaction(
        async () => await executeRequest(['SET', key, value, 'NX'], { signal }) === 'OK',
        { signal }
      )
    },
    get(key, { signal } = {}) {
      return runVerifiedTransaction(
        () => executeRequest(['GET', key], { signal }),
        { signal }
      )
    },
    async readExact(key, { signal } = {}) {
      return runVerifiedTransaction(async () => {
        const value = await executeRequest(['GET', key], { signal })
        throwIfP0Aborted(signal)
        if (value === null) return { exists: false, value: null, expiresAtMs: null }
        const expiry = await executeRequest(['PEXPIRETIME', key], { signal })
        throwIfP0Aborted(signal)
        return {
          exists: true,
          value,
          expiresAtMs: expiry === -1 ? null : expiry
        }
      }, { signal })
    },
    async restoreExact(key, value, expiresAtMs, { signal } = {}) {
      return runVerifiedTransaction(async () => {
        if (await executeRequest(['SET', key, value], { signal }) !== 'OK') {
          throw new Error('P0_REDIS_RESTORE_FAILED')
        }
        throwIfP0Aborted(signal)
        if (expiresAtMs === null) {
          await executeRequest(['PERSIST', key], { signal })
        } else {
          await executeRequest(['PEXPIREAT', key, String(expiresAtMs)], { signal })
        }
        throwIfP0Aborted(signal)
      }, { signal })
    },
    async deleteExact(key, { signal } = {}) {
      return runVerifiedTransaction(async () => {
        await executeRequest(['DEL', key], { signal })
        throwIfP0Aborted(signal)
      }, { signal })
    },
    async compareDelete(key, value, { signal } = {}) {
      const script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
      return runVerifiedTransaction(
        async () => await executeRequest(
          ['EVAL', script, '1', key, value],
          { signal }
        ) === 1,
        { signal }
      )
    },
    releaseOwnershipWithReceipt({ ownerKey, receiptKey, runToken, ownerId, signal }) {
      const script = [
        "local owner = redis.call('get', KEYS[1])",
        "local receipt = redis.call('get', KEYS[2])",
        "if owner == ARGV[1] then",
        "  if receipt and receipt ~= ARGV[2] then return 'MISMATCH' end",
        "  redis.call('del', KEYS[1])",
        "  redis.call('set', KEYS[2], ARGV[2])",
        "  return 'RELEASED'",
        'end',
        "if not owner and receipt == ARGV[2] then return 'ALREADY_RELEASED' end",
        "return 'MISMATCH'"
      ].join('\n')
      return runVerifiedTransaction(
        () => executeRequest(
          ['EVAL', script, '2', ownerKey, receiptKey, runToken, ownerId],
          { signal }
        ),
        { signal }
      )
    },
    async removeCleanupReceipt({ receiptKey, ownerId, signal }) {
      const script = [
        "local receipt = redis.call('get', KEYS[1])",
        "if not receipt then return 'ALREADY_ABSENT' end",
        "if receipt ~= ARGV[1] then return 'MISMATCH' end",
        "redis.call('del', KEYS[1])",
        "return 'REMOVED'"
      ].join('\n')
      return runVerifiedTransaction(
        () => executeRequest(
          ['EVAL', script, '1', receiptKey, ownerId],
          { signal }
        ),
        { signal }
      )
    }
  }
}

export function createDockerPostgresAdapter(
  runCommand = runLocalCommand,
  resolveContainerId = () => undefined
) {
  let pinnedContainerId
  const execute = async (id, database, sql, sensitive = false, signal) => {
    throwIfP0Aborted(signal)
    const containerId = resolveContainerId()
    if (typeof containerId !== 'string' || !/^[a-f0-9]{64}$/.test(containerId)) {
      throw new Error('P0_VERIFIED_POSTGRES_CONTAINER_REQUIRED')
    }
    if (pinnedContainerId === undefined) {
      pinnedContainerId = containerId
    } else if (containerId !== pinnedContainerId) {
      throw new Error('P0_POSTGRES_CONTAINER_ID_CHANGED')
    }
    const result = await runCommand({
      id,
      command: 'docker',
      args: [
        'exec', '-i', pinnedContainerId,
        'psql', '-U', 'postgres', '-d', database, '-tA', '-v', 'ON_ERROR_STOP=1', '-f', '-'
      ],
      cwd: projectRoot,
      stdin: sql,
      shell: false,
      sensitive,
      signal
    })
    throwIfP0Aborted(signal)
    if (result?.status !== 0) throw new Error(`P0_POSTGRES_COMMAND_FAILED: ${id}`)
    return String(result.stdout ?? '').trim()
  }
  return {
    async waitUntilReady({ signal } = {}) {
      await waitFor(async () => {
        try {
          return await execute('postgres-ready', 'postgres', 'SELECT 1;\n', false, signal) === '1'
        } catch (error) {
          throwIfP0Aborted(signal)
          return false
        }
      }, 'local PostgreSQL', 30000, signal)
    },
    executeAdminSql(sql, { sensitive = false, signal } = {}) {
      return execute('postgres-admin', 'postgres', `${sql};\n`, sensitive, signal)
    },
    async readDatabaseOwnership(segmentName, { signal } = {}) {
      assertP0DatabaseName(segmentName)
      const row = await execute('postgres-owner-read', 'postgres', `
        SELECT datname || E'\\t' || coalesce(shobj_description(oid, 'pg_database'), '')
        FROM pg_database
        WHERE datname = '${sqlLiteral(segmentName)}';
      `, true, signal)
      throwIfP0Aborted(signal)
      if (!row) return null
      const [database, ownerMarker] = row.split('\t')
      return { segmentName: database, ownerMarker }
    },
    async probeDatabase({ databaseUrl, segmentName, signal }) {
      assertP0DatabaseName(segmentName)
      const row = await execute('postgres-database-probe', segmentName, `
        SELECT current_database() || E'\\t'
          || coalesce((SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()), '')
          || E'\\t' || current_setting('timezone');
      `, true, signal)
      throwIfP0Aborted(signal)
      const [currentDatabase, ownerMarker, timezone] = row.split('\t')
      return { databaseUrl, currentDatabase, ownerMarker, timezone }
    },
    async probeBackendConnection({ segmentName, signal }) {
      assertP0DatabaseName(segmentName)
      const row = await execute('postgres-backend-activity', segmentName, `
        SELECT count(*)
        FROM pg_stat_activity
        WHERE datname = '${sqlLiteral(segmentName)}'
          AND backend_type = 'client backend'
          AND pid <> pg_backend_pid();
      `, true, signal)
      throwIfP0Aborted(signal)
      const activityCount = Number(row)
      if (!Number.isSafeInteger(activityCount) || activityCount < 0) {
        throw new Error('P0_BACKEND_DATABASE_ACTIVITY_INVALID')
      }
      return activityCount
    }
  }
}

function isPortOpen(port, signal) {
  throwIfP0Aborted(signal)
  return new Promise((resolvePromise, rejectPromise) => {
    const socket = createConnection({ host: '127.0.0.1', port })
    let settled = false
    const onAbort = () => finish(false, abortError(signal, `port probe ${port}`))
    const finish = (open, error) => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', onAbort)
      socket.destroy()
      if (error) rejectPromise(error)
      else resolvePromise(open)
    }
    socket.setTimeout(500, () => finish(false))
    socket.once('connect', () => finish(true))
    socket.once('error', () => finish(false))
    signal?.addEventListener('abort', onAbort, { once: true })
    if (signal?.aborted) onAbort()
  })
}

function parseDockerInspection(output, expected) {
  let inspection
  try {
    const parsed = JSON.parse(output)
    inspection = Array.isArray(parsed) && parsed.length === 1 ? parsed[0] : null
  } catch (error) {
    throw new Error('P0_COMPOSE_INSPECT_INVALID', { cause: error })
  }
  if (!inspection || inspection.State?.Running !== true) throw new Error('P0_COMPOSE_CONTAINER_NOT_RUNNING')
  if (typeof inspection.Id !== 'string' || !/^[a-f0-9]{64}$/.test(inspection.Id)) {
    throw new Error('P0_COMPOSE_CONTAINER_ID_INVALID')
  }
  if (inspection.Config?.Image !== expected.image) throw new Error('P0_COMPOSE_IMAGE_MISMATCH')
  const labels = inspection.Config?.Labels
  if (!labels || labels['com.docker.compose.service'] !== expected.service) {
    throw new Error('P0_COMPOSE_LABEL_MISMATCH')
  }
  if (labels['com.docker.compose.project'] !== expected.project) {
    throw new Error('P0_COMPOSE_PROJECT_MISMATCH')
  }
  const workingDirectory = labels['com.docker.compose.project.working_dir']
  if (typeof workingDirectory !== 'string'
    || resolve(workingDirectory) !== expected.composeDirectory) {
    throw new Error('P0_COMPOSE_WORKDIR_MISMATCH')
  }
  const configFilesLabel = labels['com.docker.compose.project.config_files']
  const rawConfigFiles = typeof configFilesLabel === 'string'
    ? configFilesLabel.split(',')
    : []
  const actualConfigFiles = rawConfigFiles.map((file) => resolve(file))
  if (rawConfigFiles.length === 0
    || rawConfigFiles.some((file, index) => (
      file.length === 0
        || !isAbsolute(file)
        || file !== actualConfigFiles[index]
        || rawConfigFiles.indexOf(file) !== index
        || !expected.composeFiles.includes(actualConfigFiles[index])
    ))
    || actualConfigFiles.length !== expected.composeFiles.length
    || actualConfigFiles.some((file, index) => file !== expected.composeFiles[index])) {
    throw new Error('P0_COMPOSE_CONFIG_MISMATCH')
  }
  const bindings = inspection.NetworkSettings?.Ports?.[`${expected.port}/tcp`]
  if (!Array.isArray(bindings) || bindings.length !== 1
    || bindings[0]?.HostIp !== '127.0.0.1'
    || bindings[0]?.HostPort !== String(expected.port)) {
    throw new Error('P0_COMPOSE_PORT_MISMATCH')
  }
  return {
    id: inspection.Id,
    project: labels['com.docker.compose.project'],
    image: expected.image,
    host: '127.0.0.1',
    hostPort: expected.port,
    environment: inspection.Config?.Env ?? []
  }
}

function composeCredential(environment, name) {
  const prefix = `${name}=`
  const matches = environment.filter((value) => typeof value === 'string' && value.startsWith(prefix))
  if (matches.length !== 1) throw new Error('P0_COMPOSE_CREDENTIAL_MISSING')
  return matches[0].slice(prefix.length)
}

function withVerifiedComposeDatabaseCredentials(environment, composeIdentity) {
  const username = composeIdentity?.credentials?.username
  const password = composeIdentity?.credentials?.password
  if (typeof username !== 'string' || username.length === 0
    || typeof password !== 'string' || password.length === 0
    || username.includes('\0') || password.includes('\0')) {
    throw new Error('P0_COMPOSE_CREDENTIAL_MISSING')
  }
  return {
    ...environment,
    DATABASE_USERNAME: username,
    DATABASE_PASSWORD: password,
    SPRING_DATASOURCE_USERNAME: username,
    SPRING_DATASOURCE_PASSWORD: password
  }
}

export function createLocalInfrastructureAdapter(
  composeFile,
  runCommand = runLocalCommand,
  { portIsOpen = isPortOpen } = {}
) {
  const resolvedComposeFile = resolve(composeFile)
  const composeDirectory = resolve(dirname(resolvedComposeFile))
  const runDocker = async (id, args, { signal } = {}) => {
    throwIfP0Aborted(signal)
    const result = await runCommand({
      id,
      command: 'docker',
      args,
      cwd: projectRoot,
      shell: false,
      signal
    })
    throwIfP0Aborted(signal)
    if (result?.status !== 0 || result.signal) throw new Error(`P0_DOCKER_COMMAND_FAILED: ${id}`)
    return String(result.stdout ?? '').trim()
  }
  return {
    async inspectDockerDaemon({ signal } = {}) {
      const endpoint = await runDocker('docker-context-inspect', [
        'context', 'inspect', '--format={{.Endpoints.docker.Host}}'
      ], { signal })
      return { endpoint }
    },
    async verifyComposePort({
      service,
      containerName,
      image,
      host,
      hostPort,
      containerPort,
      composeFiles = [resolvedComposeFile],
      expectedProject = P0_COMPOSE_PROJECT
    }, { signal } = {}) {
      throwIfP0Aborted(signal)
      if (host !== '127.0.0.1') return false
      if (expectedProject !== P0_COMPOSE_PROJECT) throw new Error('P0_COMPOSE_PROJECT_MISMATCH')
      const resolvedComposeFiles = composeFiles.map((file) => resolve(file))
      if (!resolvedComposeFiles.includes(resolvedComposeFile)) {
        throw new Error('P0_COMPOSE_CONFIG_MISMATCH')
      }
      const source = readFileSync(resolvedComposeFile, 'utf8')
      const configured = source.includes(`container_name: ${containerName}`)
        && source.includes(`"${hostPort}:${containerPort}"`)
      if (!configured) return false
      if (!await portIsOpen(hostPort, signal)) return true
      throwIfP0Aborted(signal)
      const output = await runDocker(`compose-${service}-published-id`, [
        'ps', '--no-trunc',
        '--filter', `publish=${hostPort}`,
        '--format={{.ID}}'
      ], { signal })
      const ids = output.split(/\r?\n/).filter(Boolean)
      if (ids.length !== 1 || !/^[a-f0-9]{64}$/.test(ids[0])) return false
      const inspection = parseDockerInspection(
        await runDocker(`compose-${service}-published-inspect`, ['inspect', ids[0]], { signal }),
        {
          service,
          image,
          port: containerPort,
          composeDirectory,
          composeFiles: resolvedComposeFiles,
          project: expectedProject
        }
      )
      throwIfP0Aborted(signal)
      return inspection.id === ids[0]
    },
    async databaseExists(segmentName, {
      composeFiles = [resolvedComposeFile],
      expectedProject = P0_COMPOSE_PROJECT,
      signal
    } = {}) {
      throwIfP0Aborted(signal)
      assertP0DatabaseName(segmentName)
      if (expectedProject !== P0_COMPOSE_PROJECT) throw new Error('P0_COMPOSE_PROJECT_MISMATCH')
      const resolvedComposeFiles = composeFiles.map((file) => resolve(file))
      if (!resolvedComposeFiles.includes(resolvedComposeFile)) {
        throw new Error('P0_COMPOSE_CONFIG_MISMATCH')
      }
      const output = await runDocker('compose-postgres-existing-id', [
        'ps', '--no-trunc',
        '--filter', `label=com.docker.compose.project=${expectedProject}`,
        '--filter', 'label=com.docker.compose.service=postgres',
        '--format={{.ID}}'
      ], { signal })
      throwIfP0Aborted(signal)
      const ids = output.split(/\r?\n/).filter(Boolean)
      if (ids.length === 0) return false
      if (ids.length !== 1 || !/^[a-f0-9]{64}$/.test(ids[0])) {
        throw new Error('P0_COMPOSE_CONTAINER_ID_INVALID')
      }
      const [id] = ids
      const inspection = parseDockerInspection(
        await runDocker('compose-postgres-existing-inspect', ['inspect', id], { signal }),
        {
          service: 'postgres',
          image: 'postgres:16',
          port: 5432,
          composeDirectory,
          composeFiles: resolvedComposeFiles,
          project: expectedProject
        }
      )
      throwIfP0Aborted(signal)
      if (inspection.id !== id) throw new Error('P0_COMPOSE_CONTAINER_ID_MISMATCH')
      const result = await runCommand({
        id: 'postgres-database-collision',
        command: 'docker',
        args: [
          'exec', '-i', inspection.id,
          'psql', '-U', 'postgres', '-d', 'postgres', '-tA', '-v', 'ON_ERROR_STOP=1', '-f', '-'
        ],
        cwd: projectRoot,
        stdin: `SELECT 1 FROM pg_database WHERE datname = '${sqlLiteral(segmentName)}';\n`,
        shell: false,
        sensitive: false,
        signal
      })
      throwIfP0Aborted(signal)
      if (result?.status !== 0 || result.signal) throw new Error('P0_DATABASE_COLLISION_CHECK_FAILED')
      return String(result.stdout ?? '').trim() === '1'
    },
    async verifyComposeContainers({
      composeFiles = [resolvedComposeFile],
      expectedProject = P0_COMPOSE_PROJECT,
      signal
    } = {}) {
      throwIfP0Aborted(signal)
      if (expectedProject !== P0_COMPOSE_PROJECT) {
        throw new Error('P0_COMPOSE_PROJECT_MISMATCH')
      }
      const resolvedComposeFiles = composeFiles.map((file) => resolve(file))
      if (!resolvedComposeFiles.includes(resolvedComposeFile)) {
        throw new Error('P0_COMPOSE_CONFIG_MISMATCH')
      }
      const composeArguments = resolvedComposeFiles.flatMap((file) => ['-f', file])
      const inspectService = async (service, image, port) => {
        const id = await runDocker(`compose-${service}-id`, [
          'compose', '--project-name', expectedProject,
          ...composeArguments, 'ps', '-q', service
        ], { signal })
        throwIfP0Aborted(signal)
        if (!/^[a-f0-9]{12,64}$/.test(id)) throw new Error('P0_COMPOSE_CONTAINER_ID_INVALID')
        const output = await runDocker(`compose-${service}-inspect`, ['inspect', id], { signal })
        throwIfP0Aborted(signal)
        const identity = parseDockerInspection(output, {
          service,
          image,
          port,
          composeDirectory,
          composeFiles: resolvedComposeFiles,
          project: expectedProject
        })
        if (!identity.id.startsWith(id)) throw new Error('P0_COMPOSE_CONTAINER_ID_MISMATCH')
        return identity
      }
      const postgres = await inspectService('postgres', 'postgres:16', 5432)
      throwIfP0Aborted(signal)
      const redis = await inspectService('redis', 'redis:7', 6379)
      throwIfP0Aborted(signal)
      if (postgres.project !== expectedProject || redis.project !== expectedProject) {
        throw new Error('P0_COMPOSE_PROJECT_MISMATCH')
      }
      const username = composeCredential(postgres.environment, 'POSTGRES_USER')
      const password = composeCredential(postgres.environment, 'POSTGRES_PASSWORD')
      if (username !== 'postgres' || password !== 'password') {
        throw new Error('P0_COMPOSE_CREDENTIAL_MISMATCH')
      }
      return {
        project: postgres.project,
        postgres: {
          id: postgres.id,
          image: postgres.image,
          host: postgres.host,
          hostPort: postgres.hostPort
        },
        redis: {
          id: redis.id,
          image: redis.image,
          host: redis.host,
          hostPort: redis.hostPort
        },
        credentials: { username, password }
      }
    },
    async inspectListener({ port }, { signal } = {}) {
      return await isPortOpen(port, signal) ? { ownerId: null } : null
    },
    async assertPortsFree(ports, { signal } = {}) {
      for (const port of ports) {
        throwIfP0Aborted(signal)
        if (await portIsOpen(port, signal)) throw new Error(`P0_PORT_STILL_IN_USE: ${port}`)
      }
    }
  }
}

function bindP0AbortToManagedProcess(child, signal, label) {
  if (!signal) return
  const onAbort = () => {
    child.p0AbortTermination ??= terminateProcessTree(child, label)
    void child.p0AbortTermination.catch(() => {})
  }
  signal.addEventListener('abort', onAbort, { once: true })
  child.once('exit', () => signal.removeEventListener('abort', onAbort))
  if (signal.aborted) onAbort()
}

export function startP0ManagedProcess(
  label,
  command,
  args,
  cwd,
  environment,
  signal,
  {
    normalizeDescriptor = normalizeLocalCommandDescriptor,
    spawnProcess = spawn
  } = {}
) {
  throwIfP0Aborted(signal)
  const descriptor = normalizeDescriptor({ command, args, cwd, env: environment })
  const child = spawnProcess(descriptor.command, descriptor.args, {
    cwd: descriptor.cwd,
    env: descriptor.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: false,
    windowsHide: true
  })
  observeLocalChildSpawn(child, { descriptor, label, registerIdentity: true })
  const log = {
    label,
    command: [descriptor.command, ...descriptor.args].join(' '),
    output: ''
  }
  processLogs.push(log)
  child.stdout?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  child.stderr?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES) })
  managedProcesses.push(child)
  bindP0AbortToManagedProcess(child, signal, label)
  return child
}

export function createLocalProcessManager(
  runCommand = runLocalCommand,
  {
    stopProcesses = stopManagedProcesses,
    acquireNativeProcessHandle = acquireLocalNativeProcessHandle
  } = {}
) {
  return {
    async startOwnedBackend({ environment, signal }) {
      const mavenArguments = [
        'spring-boot:run',
        '-Dspring-boot.run.profiles=dev'
      ]
      const child = startP0ManagedProcess(
        'p0-backend',
        process.platform === 'win32' ? 'mvn.cmd' : 'mvn',
        mavenArguments,
        join(projectRoot, 'backend'),
        environment,
        signal
      )
      await child.p0SpawnReady
      if (!child.processIdentity) {
        await terminateProcessTree(child, 'unidentified P0 backend')
        throw new Error('P0_PROCESS_IDENTITY_UNKNOWN')
      }
      return child
    },
    async waitForBackendHealth(backend, url, signal) {
      await waitFor(async () => {
        assertProcessRunning(backend)
        let response
        try {
          response = await fetchP0Local(url, { signal, timeoutMs: 2000 })
        } catch (error) {
          throwIfP0Aborted(signal)
          return false
        }
        throwIfP0Aborted(signal)
        if (!response?.ok) return false
        const body = await response.json().catch((error) => {
          throwIfP0Aborted(signal)
          return null
        })
        throwIfP0Aborted(signal)
        return body?.status === 'UP'
      }, 'owned P0 backend health', 120000, signal)
    },
    async waitForBusinessEndpoint(backend, url, signal) {
      await waitFor(async () => {
        assertProcessRunning(backend)
        return canFetch(url, signal)
      }, 'owned P0 backend business endpoint', 30000, signal)
    },
    async stopOwnedBackend(backend, { signal } = {}) {
      if (!backend) return
      if (backend.p0AbortTermination) await backend.p0AbortTermination
      throwIfP0Aborted(signal)
      await terminateProcessTree(backend, 'owned P0 backend', signal)
    },
    stopParentBackend({ signal } = {}) {
      return stopProcesses({ signal })
    },
    acquireNativeProcessHandle,
    runCanonicalChild(invocation, { signal, onSpawn } = {}) {
      validateCanonicalChildEnvironment(invocation)
      return runCommand({
        id: 'canonical-child',
        command: invocation.command,
        args: invocation.args,
        cwd: projectRoot,
        env: invocation.env,
        shell: false,
        signal,
        async onSpawn(child) {
          if (!child.processIdentity) throw new Error('P0_PROCESS_IDENTITY_UNKNOWN')
          await onSpawn?.(child)
        }
      })
    }
  }
}

async function writeRedisRecoveryState({
  runRoot,
  runId,
  ownerId,
  inventory,
  snapshot,
  touchedKeys,
  signal
}) {
  throwIfP0Aborted(signal)
  const path = join(runRoot, 'control', 'redis.json')
  const recovery = createP0RedisRecoveryState({
    runId,
    ownerId,
    inventory,
    snapshot,
    touchedKeys
  })
  await writeControlJsonNoClobber(path, recovery, 'P0_REDIS_SNAPSHOT_EXISTS')
  throwIfP0Aborted(signal)
  return path
}

export async function runP0TrackedRedisMutation({
  artifactBase,
  runId,
  runToken,
  key,
  mutate,
  signal
}) {
  throwIfP0Aborted(signal)
  assertExactRedisKey(key)
  if (typeof mutate !== 'function') throw new Error('P0_REDIS_MUTATION_INVALID')
  const active = await activeControlManifestForUpdate(artifactBase, runId, runToken)
  throwIfP0Aborted(signal)
  if (active.manifest.redisState !== 'SNAPSHOT_READY') {
    throw new Error('P0_REDIS_SNAPSHOT_NOT_READY')
  }
  const recovery = validateP0RedisRecoveryState(
    await readControlJson(active.paths.redisPath),
    { runId, ownerId: active.manifest.ownerId }
  )
  throwIfP0Aborted(signal)
  if (!recovery.touchedKeys.includes(key)) {
    const updated = createP0RedisRecoveryState({
      runId,
      ownerId: active.manifest.ownerId,
      inventory: P0_DEFAULT_REDIS_KEYS,
      snapshot: recovery.snapshot,
      touchedKeys: [...recovery.touchedKeys, key]
    })
    throwIfP0Aborted(signal)
    await replaceControlJsonAtomic(active.paths.redisPath, updated)
    throwIfP0Aborted(signal)
  }
  throwIfP0Aborted(signal)
  const result = await mutate({ signal })
  throwIfP0Aborted(signal)
  return result
}

async function recoverP0ActiveControlContext(artifactBase, runId) {
  const paths = await existingControlPaths(artifactBase, runId)
  let manifest
  try {
    manifest = await readControlJson(paths.ownershipPath)
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_CONTROL_MISSING', { cause: error })
  }
  if (manifest?.status === 'CLEANED') {
    validateCleanedControlMarker(manifest, runId)
    return {
      alreadyCleaned: true,
      artifactBase,
      paths,
      manifest,
      runRoot: paths.runRoot,
      ownerId: manifest.ownerId
    }
  }
  validateActiveControlManifest(manifest, runId)
  const ownerToken = manifest.ownerToken
  const ownerId = manifest.ownerId
  const database = manifest.database
  const canonicalDatabase = typeof database === 'string' ? database : database?.canonical
  const matrixDatabase = typeof database === 'object' ? database?.matrix : null
  if (canonicalDatabase) assertP0DatabaseName(canonicalDatabase)
  if (matrixDatabase) assertP0DatabaseName(matrixDatabase)
  const ownedDatabaseSegments = Array.isArray(manifest.journal?.resources)
    ? manifest.journal.resources
        .filter(({ type, id }) => type === 'database' && typeof id === 'string')
        .map(({ id }) => id)
    : []
  for (const segmentName of ownedDatabaseSegments) assertP0DatabaseName(segmentName)
  return {
    artifactBase,
    paths,
    manifest,
    runRoot: paths.runRoot,
    ownerToken,
    ownerId,
    canonicalDatabase,
    matrixDatabase,
    ownedDatabaseSegments
  }
}

function assertP0PreSnapshotRecoverySafe(manifest) {
  const resources = manifest.journal?.resources
  if (!Array.isArray(resources)) throw new Error('P0_JOURNAL_INVALID')
  for (const resource of resources) {
    const allowedOverride = resource?.type === 'override' && resource.id === 'compose-loopback'
    const allowedCompose = resource?.type === 'process' && resource.id === 'compose-up'
    if (!allowedOverride && !allowedCompose) {
      throw new Error('P0_REDIS_PRE_SNAPSHOT_RECOVERY_UNSAFE')
    }
  }
}

function assertP0NotAcquiredCleanupSafe(manifest) {
  if (manifest.redisState !== 'NOT_ACQUIRED'
    || !Array.isArray(manifest.journal?.resources)
    || manifest.journal.resources.some((resource) => (
      resource?.type === 'database'
        || resource?.type === 'canonical-child'
        || !['process', 'override'].includes(resource?.type)
    ))) {
    throw new Error('P0_NOT_ACQUIRED_RECOVERY_UNSAFE')
  }
}

async function recoverP0CleanupContext(artifactBase, runId) {
  const active = await recoverP0ActiveControlContext(artifactBase, runId)
  if (active.alreadyCleaned) return active
  const { paths } = active
  const redisRecoveryFile = await inspectSafeControlFile(paths.redisPath, { allowMissing: true })
  if (!redisRecoveryFile) {
    if (active.manifest.redisState === 'NOT_ACQUIRED') {
      assertP0NotAcquiredCleanupSafe(active.manifest)
      return {
        ...active,
        redisRecoveryMode: 'NOT_ACQUIRED',
        redisSnapshot: [],
        touchedRedisKeys: []
      }
    }
    if (!['ACQUIRE_ARMED', 'OWNED', 'REDIS_RELEASE_ARMED'].includes(active.manifest.redisState)) {
      throw new Error('P0_REDIS_SNAPSHOT_MISSING')
    }
    assertP0PreSnapshotRecoverySafe(active.manifest)
    return {
      ...active,
      redisRecoveryMode: 'RELEASE_ONLY',
      redisSnapshot: [],
      touchedRedisKeys: []
    }
  }
  let redisRecovery
  try {
    redisRecovery = await readControlJson(paths.redisPath)
  } catch (error) {
    if (error?.message === 'P0_CONTROL_FILE_UNSAFE') throw error
    throw new Error('P0_REDIS_SNAPSHOT_MISSING', { cause: error })
  }
  redisRecovery = validateP0RedisRecoveryState(redisRecovery, {
    runId,
    ownerId: active.ownerId
  })
  return {
    ...active,
    redisRecoveryMode: 'SNAPSHOT',
    redisSnapshot: redisRecovery.snapshot,
    touchedRedisKeys: redisRecovery.touchedKeys
  }
}

export function mergeP0CaseFragments(entry, fragments) {
  if (!entry || typeof entry !== 'object'
    || !Array.isArray(entry.selectedSubruns)
    || !Array.isArray(fragments)
    || fragments.length === 0) {
    throw new Error('P0_CASE_FRAGMENT_INCOMPLETE')
  }
  const expected = new Map(entry.selectedSubruns.map((subrun) => [subrun?.id, subrun]))
  if (expected.size !== entry.selectedSubruns.length || expected.has(undefined)) {
    throw new Error('P0_CASE_FRAGMENT_INCOMPLETE')
  }
  const observed = new Map()
  let passed = true
  for (const fragment of fragments) {
    if (fragment?.id !== entry.id || !Array.isArray(fragment.subruns)) {
      throw new Error('P0_CASE_FRAGMENT_UNEXPECTED')
    }
    if (fragment.status !== 'PASS') passed = false
    for (const subrun of fragment.subruns) {
      const required = expected.get(subrun?.id)
      if (!required
        || subrun.profile !== required.profile
        || subrun.viewport !== required.viewport) {
        throw new Error('P0_CASE_FRAGMENT_UNEXPECTED')
      }
      if (observed.has(subrun.id)) throw new Error('P0_CASE_FRAGMENT_DUPLICATE')
      observed.set(subrun.id, subrun)
      if (subrun.status !== 'PASS') passed = false
    }
  }
  if (observed.size !== expected.size) throw new Error('P0_CASE_FRAGMENT_INCOMPLETE')
  const canonical = entry.definition?.requiredSubruns ?? []
  const exactCanonicalScope = !entry.cropped
    && canonical.length === entry.selectedSubruns.length
    && canonical.every((subrun, index) => {
      const selected = entry.selectedSubruns[index]
      return selected?.id === subrun.id
        && selected.profile === subrun.profile
        && selected.viewport === subrun.viewport
    })
  return {
    id: entry.id,
    status: passed ? 'PASS' : 'FAIL',
    scopeComplete: exactCanonicalScope,
    subruns: entry.selectedSubruns.map(({ id }) => observed.get(id))
  }
}

function validateCompletedP0Cases(plan, caseResults, matrixPhases) {
  if (matrixPhases === 0) {
    if (caseResults.length !== 0) throw new Error('P0_REPORT_UNEXPECTED_CASES')
    return
  }
  const entries = Array.isArray(plan.executionEntries)
    ? plan.executionEntries
    : plan.definitions.map((definition) => ({
        id: definition.id,
        definition,
        selectedSubruns: definition.requiredSubruns,
        cropped: false
      }))
  if (caseResults.length !== entries.length) throw new Error('P0_REPORT_INCOMPLETE')
  const byId = new Map(caseResults.map((result) => [result?.id, result]))
  if (byId.size !== caseResults.length) throw new Error('P0_REPORT_DUPLICATE_CASE')
  for (const entry of entries) {
    const definition = entry.definition
    const expectedSubruns = entry.selectedSubruns
    const result = byId.get(entry.id)
    if (result?.status !== 'PASS' || result.scopeComplete !== !entry.cropped) {
      throw new Error(`P0_REPORT_CASE_INCOMPLETE: ${entry.id}`)
    }
    if (!Array.isArray(result.subruns)
      || result.subruns.length !== expectedSubruns.length) {
      throw new Error(`P0_REPORT_SUBRUN_INCOMPLETE: ${entry.id}`)
    }
    const subruns = new Map(result.subruns.map((subrun) => [subrun?.id, subrun]))
    if (subruns.size !== result.subruns.length) {
      throw new Error(`P0_REPORT_SUBRUN_DUPLICATE: ${entry.id}`)
    }
    for (const required of expectedSubruns) {
      const actual = subruns.get(required.id)
      if (actual?.status !== 'PASS'
        || actual.profile !== required.profile
        || actual.viewport !== required.viewport) {
        throw new Error(`P0_REPORT_SUBRUN_INCOMPLETE: ${entry.id}/${required.id}`)
      }
    }
  }
}

async function writeP0ReportAtomic(path, report) {
  await inspectSafeControlFile(path, { allowMissing: true })
  const temporaryPath = await writeDurableControlTemp(
    path,
    `${JSON.stringify(report, null, 2)}\n`
  )
  try {
    await rename(temporaryPath, path)
    await syncControlDirectory(path)
  } finally {
    await rm(temporaryPath, { force: true })
  }
  const persisted = await readControlJson(path)
  if (JSON.stringify(persisted) !== JSON.stringify(report)) {
    throw new Error('P0_REPORT_PERSISTENCE_MISMATCH')
  }
  return persisted
}

function safeP0ComposeIdentity(identity) {
  const project = identity?.project
  const normalizeContainer = (container, port) => {
    if (typeof container?.id !== 'string'
      || !/^[a-f0-9]{64}$/.test(container.id)
      || typeof container.image !== 'string'
      || container.image.length === 0
      || container.host !== '127.0.0.1'
      || container.hostPort !== port) {
      throw new Error('P0_COMPOSE_IDENTITY_INVALID')
    }
    return {
      id: container.id,
      image: container.image,
      host: container.host,
      hostPort: container.hostPort
    }
  }
  if (project !== P0_COMPOSE_PROJECT) {
    throw new Error('P0_COMPOSE_IDENTITY_INVALID')
  }
  return {
    project,
    postgres: normalizeContainer(identity.postgres, 5432),
    redis: normalizeContainer(identity.redis, 6379)
  }
}

function safeP0ComposeTarget(target) {
  if (target?.expectedProject !== P0_COMPOSE_PROJECT
    || !Array.isArray(target.composeFiles)
    || target.composeFiles.length !== 2
    || target.composeFiles.some((file) => typeof file !== 'string' || !isAbsolute(file))
    || resolve(target.composeFiles[0]) !== resolve(projectRoot, 'infra', 'docker-compose.yml')
    || resolve(target.composeFiles[1]) === resolve(target.composeFiles[0])) {
    throw new Error('P0_COMPOSE_TARGET_INVALID')
  }
  return Object.freeze({
    expectedProject: P0_COMPOSE_PROJECT,
    composeFiles: Object.freeze(target.composeFiles.map((file) => resolve(file)))
  })
}

async function revalidateP0CleanupComposeIdentity({
  artifactBase,
  manifest,
  infrastructure,
  signal
}) {
  let persistedTarget
  let persistedIdentity
  try {
    persistedTarget = safeP0ComposeTarget(manifest.composeTarget)
    persistedIdentity = safeP0ComposeIdentity(manifest.composeIdentity)
  } catch (error) {
    throw new Error('P0_CLEANUP_COMPOSE_IDENTITY_INVALID', { cause: error })
  }
  const verifiedTarget = await verifyP0ComposeTarget({ artifactBase })
  throwIfP0Aborted(signal)
  if (JSON.stringify(persistedTarget) !== JSON.stringify(verifiedTarget)) {
    throw new Error('P0_CLEANUP_COMPOSE_IDENTITY_MISMATCH')
  }
  if (typeof infrastructure.inspectDockerDaemon !== 'function'
    || typeof infrastructure.verifyComposeContainers !== 'function') {
    throw new Error('P0_CLEANUP_COMPOSE_VERIFIER_REQUIRED')
  }
  const daemon = await infrastructure.inspectDockerDaemon({ signal })
  throwIfP0Aborted(signal)
  assertLocalDockerEndpoint(daemon?.endpoint)
  const observedIdentity = safeP0ComposeIdentity(
    await infrastructure.verifyComposeContainers({
      composeFiles: verifiedTarget.composeFiles,
      expectedProject: verifiedTarget.expectedProject,
      signal
    })
  )
  throwIfP0Aborted(signal)
  if (JSON.stringify(persistedIdentity) !== JSON.stringify(observedIdentity)) {
    throw new Error('P0_CLEANUP_COMPOSE_IDENTITY_MISMATCH')
  }
  return observedIdentity
}

export function validateP0ResumeJournal(manifest, matrixDatabase) {
  const resources = manifest.journal?.resources
  if (!Array.isArray(resources)) throw new Error('P0_RESUME_JOURNAL_INVALID')
  const override = resources.find(({ type, id }) => type === 'override' && id === 'compose-loopback')
  const compose = resources.find(({ type, id }) => type === 'process' && id === 'compose-up')
  if ((override !== undefined
      && (override?.state !== 'STARTED' || override.path !== 'control/compose.loopback.yml'))
    || compose?.state !== 'COMPLETED') {
    throw new Error('P0_RESUME_JOURNAL_INVALID')
  }
  for (const resource of resources) {
    if (resource?.live === true
      && (!Number.isSafeInteger(resource.pid)
        || resource.pid < 1
        || typeof resource.processStartedAt !== 'string'
        || resource.processStartedAt.length === 0
        || !P0_PROCESS_FINGERPRINT_PATTERN.test(resource.processFingerprint ?? ''))) {
      throw new Error('P0_PROCESS_IDENTITY_MISSING')
    }
    if (resource?.state === 'PLANNED'
      || (resource?.live === true && resource.state !== 'COMPLETED')
      || (resource?.type === 'database' && resource.state !== 'STARTED')) {
      throw new Error('P0_RESUME_JOURNAL_INCOMPLETE')
    }
  }
  const databases = [...new Set(resources
    .filter(({ type, id }) => type === 'database' && typeof id === 'string')
    .map(({ id }) => id))]
  for (const segmentName of databases) assertP0DatabaseName(segmentName)
  if (!databases.includes(matrixDatabase)) throw new Error('P0_RESUME_DATABASE_MISSING')
  return databases
}

export function createDefaultP0Dependencies(runtime = {}) {
  const platform = P0_HOST_PLATFORM
  const processTreeProvider = runtime.processTreeProvider
  const inheritedEnv = runtime.inheritedEnv ?? process.env
  const artifactBase = resolve(
    runtime.artifactBase
      ?? inheritedEnv.P0_USER_TRADING_ARTIFACTS
      ?? join(projectRoot, 'artifacts', 'p0-user-trading')
  )
  const composeFile = join(projectRoot, 'infra', 'docker-compose.yml')
  const composeTarget = createP0ComposeTarget({ artifactBase })
  const commandEnvironment = sanitizedBackendEnvironment(inheritedEnv)
  const executeCommand = runtime.runCommand ?? runLocalCommand
  const runCommand = (descriptor) => executeCommand({
    ...descriptor,
    env: { ...commandEnvironment, ...descriptor.env },
    shell: false
  })
  let composeIdentity
  let redisVerificationManifest
  const infrastructure = runtime.infrastructure ?? createLocalInfrastructureAdapter(composeFile, runCommand)
  const redis = runtime.redis ?? createLoopbackRedisAdapter(
    runtime.redisRequest,
    () => composeIdentity?.redis,
    async (_expectedBinding, { signal } = {}) => {
      if (!redisVerificationManifest) {
        throw new Error('P0_REDIS_TRANSACTION_VERIFIER_REQUIRED')
      }
      const observedIdentity = await revalidateP0CleanupComposeIdentity({
        artifactBase,
        manifest: redisVerificationManifest,
        infrastructure,
        signal
      })
      return observedIdentity.redis
    }
  )
  const postgres = runtime.postgres ?? createDockerPostgresAdapter(
    runCommand,
    () => composeIdentity?.postgres?.id
  )
  const processManager = runtime.processManager ?? createLocalProcessManager(runCommand)
  const nextRunToken = runtime.runToken ?? (() => `${randomUUID()}${randomUUID()}`)
  const nextRandomSuffix = runtime.randomSuffix
    ?? (() => randomUUID().replaceAll('-', '').slice(0, 12))
  const now = runtime.now ?? (() => new Date().toISOString())
  const replaceOwnership = runtime.replaceOwnership ?? replaceControlJsonAtomic
  const redisKeys = [...(runtime.redisKeys ?? P0_DEFAULT_REDIS_KEYS)]
  if (JSON.stringify(redisKeys) !== JSON.stringify(P0_DEFAULT_REDIS_KEYS)) {
    throw new Error('P0_REDIS_INVENTORY_INVALID')
  }
  const captureIdentity = runtime.captureIdentity ?? (() => captureLocalGitIdentity(projectRoot))
  const databaseExists = runtime.databaseExists
    ?? (typeof infrastructure.databaseExists === 'function'
      ? (segmentName, details) => infrastructure.databaseExists(segmentName, details)
      : async () => false)
  const dispatchCaseOperation = runtime.dispatchCase ?? runCase

  const finalizePendingCleanupReceipt = async (cleanedContext, runId, { signal } = {}) => {
    throwIfP0Aborted(signal)
    const marker = validateCleanedControlMarker(cleanedContext.manifest, runId)
    if (['NOT_REQUIRED', 'COMPLETED'].includes(marker.receipt.state)) return marker
    composeIdentity = await revalidateP0CleanupComposeIdentity({
      artifactBase: cleanedContext.artifactBase ?? artifactBase,
      manifest: marker,
      infrastructure,
      signal
    })
    throwIfP0Aborted(signal)
    redisVerificationManifest = marker
    await removeP0CleanupReceipt({ redis, marker, signal })
    throwIfP0Aborted(signal)
    const completed = {
      ...marker,
      receipt: {
        ...marker.receipt,
        state: 'COMPLETED',
        completedAt: now()
      }
    }
    validateCleanedControlMarker(completed, runId)
    await replaceOwnership(cleanedContext.paths.ownershipPath, completed)
    await assertPersistedCleanedControlMarker({
      ownershipPath: cleanedContext.paths.ownershipPath,
      expected: completed,
      runId
    })
    throwIfP0Aborted(signal)
    return completed
  }

  const finishAlreadyCleaned = async (cleanedContext, runId, { signal } = {}) => {
    if (cleanedContext.manifest.schemaVersion === 2
      && cleanedContext.manifest.receipt.state === 'PENDING'
      && platform === 'win32') {
      requireP0CleanupProcessTreeProvider(processTreeProvider)
    }
    await finalizePendingCleanupReceipt(cleanedContext, runId, { signal })
    throwIfP0Aborted(signal)
    return { status: 'CLEANED', alreadyCleaned: true }
  }

  const dependencies = {
    composeTarget,
    installSignalHandlers(handler) {
      assertP0ProcessTreeCapability()
      return (runtime.installSignalHandlers ?? installP0SignalHandlers)(handler)
    },
    async initializeOwnership(options, plan, { signal } = {}) {
      throwIfP0Aborted(signal)
      assertP0ProcessTreeCapability()
      const reservation = await prepareP0RunReservation({
        options,
        plan,
        artifactBase,
        inheritedEnv,
        captureIdentity,
        databaseExists,
        nextRunToken,
        nextRandomSuffix,
        infrastructure,
        signal,
        now
      })
      throwIfP0Aborted(signal)
      const assertIdentity = async ({ signal: identitySignal = signal } = {}) => {
        throwIfP0Aborted(identitySignal)
        const currentIdentity = await captureIdentity({ signal: identitySignal })
        throwIfP0Aborted(identitySignal)
        return assertP0RunIdentityUnchanged(
          reservation.identity,
          currentIdentity,
          { runRoot: reservation.runRoot }
        )
      }
      const journalMutation = (resource, start) => runP0JournaledMutation({
        artifactBase,
        runId: options.runId,
        runToken: reservation.ownerToken,
        resource,
        start,
        signal,
        now
      })
      await assertIdentity()
      throwIfP0Aborted(signal)
      const { composeFiles, expectedProject } = reservation.composeTarget
      const buildPreparedContext = (redisSnapshot, touchedRedisKeys) => ({
        ...reservation,
        artifactBase,
        scriptPath: fileURLToPath(import.meta.url),
        composeIdentity,
        inheritedEnv,
        redisSnapshot,
        touchedRedisKeys,
        ownedDatabaseSegments: [],
        runRedisMutation(key, mutate) {
          return runP0TrackedRedisMutation({
            artifactBase,
            runId: options.runId,
            runToken: reservation.ownerToken,
            key,
            signal,
            mutate: async () => {
              throwIfP0Aborted(signal)
              if (!touchedRedisKeys.includes(key)) touchedRedisKeys.push(key)
              throwIfP0Aborted(signal)
              const result = await mutate({ signal })
              throwIfP0Aborted(signal)
              return result
            }
          })
        },
        assertIdentity,
        caseResults: []
      })
      if (reservation.resumed) {
        throwIfP0Aborted(signal)
        const recovery = await recoverP0CleanupContext(artifactBase, options.runId)
        throwIfP0Aborted(signal)
        if (recovery.manifest.redisState !== 'SNAPSHOT_READY') {
          throw new Error('P0_REDIS_STATE_INVALID')
        }
        const databases = validateP0ResumeJournal(
          recovery.manifest,
          reservation.matrixDatabase
        )
        if (typeof infrastructure.verifyComposeContainers !== 'function') {
          throw new Error('P0_RESUME_COMPOSE_IDENTITY_MISSING')
        }
        composeIdentity = await infrastructure.verifyComposeContainers({
          composeFiles,
          expectedProject,
          signal
        })
        throwIfP0Aborted(signal)
        if (JSON.stringify(safeP0ComposeIdentity(composeIdentity))
          !== JSON.stringify(safeP0ComposeIdentity(recovery.manifest.composeIdentity))) {
          throw new Error('P0_RESUME_COMPOSE_IDENTITY_MISMATCH')
        }
        redisVerificationManifest = recovery.manifest
        await postgres.waitUntilReady?.({ signal })
        throwIfP0Aborted(signal)
        await redis.waitUntilReady?.({ signal })
        throwIfP0Aborted(signal)
        await acquireRedisOwnership({
          redis,
          runToken: reservation.ownerToken,
          role: 'child',
          signal
        })
        throwIfP0Aborted(signal)
        for (const segmentName of databases) {
          throwIfP0Aborted(signal)
          await requireOwnedDatabase({
            segmentName,
            runToken: reservation.ownerToken,
            postgres,
            signal
          })
          throwIfP0Aborted(signal)
          await verifyDatabaseIdentity({
            segmentName,
            runToken: reservation.ownerToken,
            databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${segmentName}`,
            postgres,
            signal
          })
          throwIfP0Aborted(signal)
        }
        await assertIdentity()
        return buildPreparedContext(recovery.redisSnapshot, recovery.touchedRedisKeys)
      }
      const composeArguments = composeFiles.flatMap((file) => ['-f', file])
      const composeFingerprint = sha256Text(JSON.stringify([
        'docker', 'compose', '--project-name', P0_COMPOSE_PROJECT,
        ...composeArguments, 'up', '-d', '--pull', 'never'
      ]))
      const compose = await runP0JournaledCommand({
        artifactBase,
        runId: options.runId,
        runToken: reservation.ownerToken,
        resourceId: 'compose-up',
        commandFingerprint: composeFingerprint,
        descriptor: {
          id: 'compose-up',
          command: 'docker',
          args: [
            'compose', '--project-name', P0_COMPOSE_PROJECT,
            ...composeArguments, 'up', '-d', '--pull', 'never'
          ],
          cwd: projectRoot
        },
        runCommand,
        inspectProcess: processManager.inspectProcess,
        signal,
        now
      })
      throwIfP0Aborted(signal)
      if (compose?.status !== 0) throw new Error('P0_COMPOSE_START_FAILED')
      if (typeof infrastructure.verifyComposeContainers === 'function') {
        composeIdentity = await infrastructure.verifyComposeContainers({
          composeFiles,
          expectedProject,
          signal
        })
        throwIfP0Aborted(signal)
      } else if (!runtime.infrastructure) {
        throw new Error('P0_COMPOSE_IDENTITY_MISSING')
      }
      if (composeIdentity) {
        throwIfP0Aborted(signal)
        const active = await activeControlManifestForUpdate(
          artifactBase,
          options.runId,
          reservation.ownerToken
        )
        throwIfP0Aborted(signal)
        active.manifest.composeTarget = safeP0ComposeTarget(reservation.composeTarget)
        active.manifest.composeIdentity = safeP0ComposeIdentity(composeIdentity)
        throwIfP0Aborted(signal)
        await replaceControlJsonAtomic(active.paths.ownershipPath, active.manifest)
        throwIfP0Aborted(signal)
        redisVerificationManifest = active.manifest
      }
      await postgres.waitUntilReady?.({ signal })
      throwIfP0Aborted(signal)
      await redis.waitUntilReady?.({ signal })
      throwIfP0Aborted(signal)
      await assertIdentity()

      let redisSnapshot
      let touchedRedisKeys
      await transitionP0RedisControlState({
        artifactBase,
        runId: options.runId,
        runToken: reservation.ownerToken,
        from: 'NOT_ACQUIRED',
        to: 'ACQUIRE_ARMED',
        signal
      })
      throwIfP0Aborted(signal)
      await acquireRedisOwnership({
        redis,
        runToken: reservation.ownerToken,
        role: 'parent',
        signal
      })
      throwIfP0Aborted(signal)
      await transitionP0RedisControlState({
        artifactBase,
        runId: options.runId,
        runToken: reservation.ownerToken,
        from: 'ACQUIRE_ARMED',
        to: 'OWNED',
        signal
      })
      throwIfP0Aborted(signal)
      redisSnapshot = await snapshotRedisKeys({ redis, keys: redisKeys, signal })
      throwIfP0Aborted(signal)
      touchedRedisKeys = [...redisKeys]
      await writeRedisRecoveryState({
        runRoot: reservation.runRoot,
        runId: options.runId,
        ownerId: reservation.ownerId,
        inventory: redisKeys,
        snapshot: redisSnapshot,
        touchedKeys: touchedRedisKeys,
        signal
      })
      throwIfP0Aborted(signal)
      await transitionP0RedisControlState({
        artifactBase,
        runId: options.runId,
        runToken: reservation.ownerToken,
        from: 'OWNED',
        to: 'SNAPSHOT_READY',
        signal
      })
      throwIfP0Aborted(signal)
      await journalMutation({
        type: 'database',
        id: reservation.matrixDatabase
      }, async () => {
        await createOwnedDatabase({
          segmentName: reservation.matrixDatabase,
          runToken: reservation.ownerToken,
          postgres,
          signal
        })
        throwIfP0Aborted(signal)
        await alterOwnedDatabaseTimezone({
          segmentName: reservation.matrixDatabase,
          runToken: reservation.ownerToken,
          postgres,
          signal
        })
        throwIfP0Aborted(signal)
        return verifyDatabaseIdentity({
          segmentName: reservation.matrixDatabase,
          runToken: reservation.ownerToken,
          databaseUrl: reservation.databaseUrl,
          postgres,
          signal
        })
      })
      throwIfP0Aborted(signal)
      return buildPreparedContext(redisSnapshot, touchedRedisKeys)
    },
    phaseOperations: {
      async runPreflight(context, _plan, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return runP0Preflight({
          projectRoot,
          gateOutput: join(context.runRoot, 'preflight', 'surefire-gate.json'),
          databaseUrl: context.databaseUrl,
          now,
          signal,
          operations: {
            runCommand(descriptor) {
              return runP0JournaledCommand({
                artifactBase,
                runId: context.options.runId,
                runToken: context.ownerToken,
                resourceId: `gate:${descriptor.id}`,
                commandFingerprint: sha256Text(JSON.stringify([
                  descriptor.command,
                  descriptor.args ?? [],
                  descriptor.cwd
                ])),
                descriptor,
                runCommand,
                inspectProcess: processManager.inspectProcess,
                signal,
                now
              })
            },
            async recordGate(id, result) {
              throwIfP0Aborted(signal)
              writeCaseResultAtomic(join(context.runRoot, 'preflight', `${safeName(id)}.json`), {
                id,
                status: result?.status === 0 ? 'PASS' : 'FAIL',
                signal: result?.signal ?? null
              })
              throwIfP0Aborted(signal)
            },
            startOwnedBackend: (...args) => {
              const input = {
                ...args[0],
                environment: withVerifiedComposeDatabaseCredentials(
                  args[0]?.environment ?? {},
                  composeIdentity
                )
              }
              return runP0JournaledMutation({
                artifactBase,
                runId: context.options.runId,
                runToken: context.ownerToken,
                resource: {
                  type: 'process',
                  id: `backend:preflight:${input.profile ?? 'UNKNOWN'}`,
                  live: true,
                  commandFingerprint: sha256Text('owned-p0-backend')
                },
                start: () => processManager.startOwnedBackend(input),
                stopLiveProcess: (backend, details) => (
                  processManager.stopOwnedBackend(backend, details)
                ),
                signal: input.signal,
                now
              })
            },
            waitForBackendHealth: (...args) => processManager.waitForBackendHealth(...args),
            waitForBusinessEndpoint: (...args) => processManager.waitForBusinessEndpoint(...args),
            verifyBackendDatabaseIdentity: (_backend, { signal } = {}) => verifyLiveBackendDatabaseIdentity({
              segmentName: context.matrixDatabase,
              runToken: context.ownerToken,
              databaseUrl: context.databaseUrl,
              postgres,
              signal
            }),
            stopOwnedBackend: (...args) => processManager.stopOwnedBackend(...args),
            assertBusinessPortsFree: (ports) => infrastructure.assertPortsFree(ports, { signal })
          }
        })
      },
      stopProfileBackend(_context, _profile, { signal } = {}) {
        assertP0ProcessTreeCapability()
        throwIfP0Aborted(signal)
        return processManager.stopParentBackend({ signal })
      },
      assertProfilePortFree(port, _context, _profile, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return infrastructure.assertPortsFree([port], { signal })
      },
      async prepareProfileDatabase({ phase, attempt, signal }, context) {
        assertP0ProcessTreeCapability()
        throwIfP0Aborted(signal)
        if (!context.phaseDatabaseSegments) context.phaseDatabaseSegments = new Map()
        if (!(context.phaseDatabaseSegments instanceof Map)) {
          throw new Error('P0_PHASE_DATABASE_STATE_INVALID')
        }
        const key = `${phase}:${attempt}`
        if (context.phaseDatabaseSegments.has(key)) return context.phaseDatabaseSegments.get(key)
        const randomSuffix = createHash('sha256')
          .update(`${context.ownerId}:${phase}:${attempt}`)
          .digest('hex')
          .slice(0, 12)
        const segmentName = databaseSegmentForAttempt({ phase, attempt, randomSuffix })
        if (await databaseExists(segmentName, {
          composeFiles: composeTarget.composeFiles,
          expectedProject: composeTarget.expectedProject,
          signal
        })) throw new Error('P0_DATABASE_COLLISION')
        throwIfP0Aborted(signal)
        const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
        await runP0JournaledMutation({
          artifactBase,
          runId: context.options.runId,
          runToken: context.ownerToken,
          resource: { type: 'database', id: segmentName, phase, attempt },
          start: async () => {
            await createOwnedDatabase({
              segmentName,
              runToken: context.ownerToken,
              postgres,
              signal
            })
            throwIfP0Aborted(signal)
            await alterOwnedDatabaseTimezone({
              segmentName,
              runToken: context.ownerToken,
              postgres,
              signal
            })
            throwIfP0Aborted(signal)
            return verifyDatabaseIdentity({
              segmentName,
              runToken: context.ownerToken,
              databaseUrl,
              postgres,
              signal
            })
          },
          signal,
          now
        })
        throwIfP0Aborted(signal)
        const descriptor = { phase, attempt, segmentName, databaseUrl }
        context.phaseDatabaseSegments.set(key, descriptor)
        context.ownedDatabaseSegments ??= []
        if (!context.ownedDatabaseSegments.includes(segmentName)) {
          context.ownedDatabaseSegments.push(segmentName)
        }
        return descriptor
      },
      startProfileBackend({ phase, profile, attempt, environment, signal }, context) {
        assertP0ProcessTreeCapability()
        const ownedEnvironment = withVerifiedComposeDatabaseCredentials(environment, composeIdentity)
        return runP0JournaledMutation({
          artifactBase,
          runId: context.options.runId,
          runToken: context.ownerToken,
          resource: {
            type: 'process',
            id: `backend:${phase}:${attempt}:${profile}`,
            live: true,
            commandFingerprint: sha256Text(`owned-p0-backend:${phase}:${attempt}:${profile}`)
          },
          start: () => processManager.startOwnedBackend({
            profile,
            environment: ownedEnvironment,
            signal
          }),
          stopLiveProcess: (backend, details) => processManager.stopOwnedBackend(backend, details),
          signal,
          now
        })
      },
      waitForProfileHealth(backend, _context, _profile, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return processManager.waitForBackendHealth(
          backend,
          'http://127.0.0.1:18086/actuator/health',
          signal
        )
      },
      waitForProfileBusinessEndpoint(backend, _context, _profile, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return processManager.waitForBusinessEndpoint(
          backend,
          'http://127.0.0.1:18086/api/market/symbols',
          signal
        )
      },
      verifyProfileDatabaseIdentity(_backend, context, _profile, phaseDatabase, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return verifyLiveBackendDatabaseIdentity({
          segmentName: phaseDatabase.segmentName,
          runToken: context.ownerToken,
          databaseUrl: phaseDatabase.databaseUrl,
          postgres,
          signal
        })
      },
      async assertRedisOwnership(context, _stage, { signal } = {}) {
        assertP0ProcessTreeCapability()
        throwIfP0Aborted(signal)
        const redisOwner = await runRedisOwnershipTransaction(
          redis,
          () => redis.get(P0_REDIS_OWNER_KEY, { signal }),
          { signal }
        )
        if (redisOwner !== context.ownerToken) {
          throw new Error('P0_REDIS_OWNER_MISMATCH')
        }
        throwIfP0Aborted(signal)
      },
      stopParentBackend(_context, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return processManager.stopParentBackend({ signal })
      },
      assertBusinessPortsFree(ports, _context, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return infrastructure.assertPortsFree(ports, { signal })
      },
      runCanonicalChild(invocation, context, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return runP0JournaledMutation({
          artifactBase,
          runId: context.options.runId,
          runToken: context.ownerToken,
          resource: {
            type: 'database',
            id: context.canonicalDatabase
          },
          start: () => runP0JournaledMutation({
            artifactBase,
            runId: context.options.runId,
            runToken: context.ownerToken,
            resource: {
              type: 'canonical-child',
              id: 'canonical:attempt-1',
              live: true,
              database: context.canonicalDatabase,
              commandFingerprint: sha256Text(JSON.stringify([
                invocation.command,
                invocation.args,
                projectRoot
              ]))
            },
            start: (markStarted) => processManager.runCanonicalChild(invocation, {
              signal,
              onSpawn: markStarted
            }),
            signal,
            now
          }),
          signal,
          now
        })
      },
      verifyCanonicalChildCleanup(child, context, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return verifyCanonicalChildResult({ child, context, redis, postgres, signal })
      },
      async runAuthority(context, plan, { signal } = {}) {
        assertP0ProcessTreeCapability()
        throwIfP0Aborted(signal)
        if (typeof runtime.runAuthority !== 'function') {
          throw new Error('P0_AUTHORITY_OPERATION_REQUIRED')
        }
        const result = await runtime.runAuthority(context, plan, { signal })
        throwIfP0Aborted(signal)
        return result
      },
      async writeReport(context, plan, { signal } = {}) {
        assertP0ProcessTreeCapability()
        return executeP0ReportPhaseBoundary({
          context,
          plan,
          writePhaseReport: runtime.writePhaseReport,
          signal
        })
      }
    },
    dispatchCase(...args) {
      assertP0ProcessTreeCapability()
      return dispatchCaseOperation(...args)
    },
    handlers: runtime.handlers ?? Object.create(null),
    async writeReport(execution, prepared, { signal } = {}) {
      assertP0ProcessTreeCapability()
      throwIfP0Aborted(signal)
      await prepared.assertIdentity?.({ signal })
      throwIfP0Aborted(signal)
      const scope = execution.plan?.scope ?? 'MATRIX'
      if (!['MATRIX', 'CONTROL'].includes(scope)) throw new Error('P0_PLAN_SCOPE_INVALID')
      const aggregateState = prepared.runState ?? {
        definitions: P0_CASES,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        selection: p0RunSelection(prepared.options)
      }
      if (scope === 'CONTROL' && execution.caseResults.length !== 0) {
        throw new Error('P0_REPORT_UNEXPECTED_CASES')
      }
      const aggregate = scope === 'MATRIX'
        ? aggregateReport({
            ...aggregateState,
            selection: artifactP0Selection(aggregateState.selection)
          }, execution.caseResults)
        : {
            verdict: 'PARTIAL_PASS',
            scopeComplete: false,
            counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
            issues: []
          }
      const mode = prepared.runState?.mode ?? persistedP0Mode(prepared.options.mode)
      if (!['DISCOVERY', 'CERTIFICATION'].includes(mode)) throw new Error('P0_MODE_INVALID')
      const safeCases = redactNetworkEntry({ cases: execution.caseResults }).cases
      if (!Array.isArray(safeCases) || safeCases.length !== execution.caseResults.length) {
        throw new Error('P0_REPORT_EVIDENCE_INVALID')
      }
      const expectedControlPhases = execution.plan.phases.filter(
        (phase) => P0_CONTROL_PHASES.has(phase)
      )
      const controlResults = execution.controlResults ?? []
      if (scope === 'CONTROL'
        && (!Array.isArray(controlResults)
          || controlResults.length !== expectedControlPhases.length
          || controlResults.some((result, index) => (
            !result
            || typeof result !== 'object'
            || Array.isArray(result)
            || result.phase !== expectedControlPhases[index]
            || result.status !== 'PASS'
            || !Object.hasOwn(result, 'evidence')
          )))) {
        throw new Error('P0_REPORT_CONTROL_EVIDENCE_INVALID')
      }
      const safeControlResults = scope === 'CONTROL'
        ? controlResults.map((result) => {
            const safe = redactNetworkEntry({ evidence: result.evidence })
            return {
              phase: result.phase,
              status: result.status,
              evidence: Object.hasOwn(safe, 'evidence') ? safe.evidence : null
            }
          })
        : []
      const report = {
        schemaVersion: 1,
        status: aggregate.verdict,
        ...aggregate,
        runId: prepared.options.runId,
        mode,
        scope,
        phases: execution.plan.phases,
        selection: aggregateState.selection,
        identity: prepared.identity ? structuredClone(prepared.identity) : null,
        ownerId: prepared.ownerId,
        database: {
          canonical: prepared.canonicalDatabase,
          matrix: prepared.matrixDatabase
        },
        caseResults: safeCases,
        ...(scope === 'CONTROL' ? { controlResults: safeControlResults } : {}),
        completedAt: now()
      }
      throwIfP0Aborted(signal)
      const persisted = await writeP0ReportAtomic(join(prepared.runRoot, 'report.json'), report)
      throwIfP0Aborted(signal)
      return persisted
    },
    async cleanup(prepared, { signal } = {}, options) {
      throwIfP0Aborted(signal)
      let context = prepared
      let active
      if (context) {
        const recovered = await recoverP0ActiveControlContext(
          context.artifactBase ?? artifactBase,
          options.runId
        )
        throwIfP0Aborted(signal)
        if (recovered.alreadyCleaned) {
          if (recovered.ownerId !== context.ownerId) throw new Error('P0_OWNER_MISMATCH')
          return finishAlreadyCleaned(recovered, options.runId, { signal })
        }
        active = await activeControlManifestForUpdate(
          context.artifactBase ?? artifactBase,
          options.runId,
          context.ownerToken
        )
        throwIfP0Aborted(signal)
      } else {
        const recovered = await recoverP0ActiveControlContext(artifactBase, options.runId)
        throwIfP0Aborted(signal)
        if (recovered.alreadyCleaned) {
          return finishAlreadyCleaned(recovered, options.runId, { signal })
        }
        context = recovered
        active = { paths: recovered.paths, manifest: recovered.manifest }
      }
      const hasJournaledProcesses = active.manifest.journal.resources.some((resource) => (
        ['process', 'canonical-child'].includes(resource?.type)
          && (resource.live === true
            || (resource.state === 'STARTED'
              && Number.isSafeInteger(resource.pid)
              && resource.pid > 0))
      ))
      const hasJournaledDatabases = active.manifest.journal.resources.some(
        (resource) => resource?.type === 'database'
      )
      const hasRedisResources = active.manifest.redisState !== 'NOT_ACQUIRED'
      const requiresCleanupAuthority = hasJournaledProcesses
        || hasJournaledDatabases
        || hasRedisResources
      const verifiedProcessTreeProvider = platform === 'win32' && requiresCleanupAuthority
        ? requireP0CleanupProcessTreeProvider(processTreeProvider)
        : null
      redisVerificationManifest = active.manifest
      const terminateOwnedProcesses = () => terminateJournaledOwnedProcesses({
        resources: active.manifest.journal.resources,
        acquireNativeProcessHandle: verifiedProcessTreeProvider
          ? (pid, details) => verifiedProcessTreeProvider.acquireNativeProcessHandle(pid, details)
          : processManager.acquireNativeProcessHandle,
        requireTreeProof: platform === 'win32',
        signal
      })
      if (platform === 'win32' && hasJournaledProcesses) {
        await terminateOwnedProcesses()
        throwIfP0Aborted(signal)
      }
      await processManager.stopParentBackend({ signal })
      throwIfP0Aborted(signal)
      if (platform !== 'win32' && hasJournaledProcesses) {
        await terminateOwnedProcesses()
        throwIfP0Aborted(signal)
      }
      const recoveredContext = await recoverP0CleanupContext(artifactBase, options.runId)
      throwIfP0Aborted(signal)
      if (prepared && (recoveredContext.ownerId !== context.ownerId
        || recoveredContext.ownerToken !== context.ownerToken)) {
        throw new Error('P0_OWNER_MISMATCH')
      }
      context = recoveredContext
      active = { paths: recoveredContext.paths, manifest: recoveredContext.manifest }
      redisVerificationManifest = active.manifest
      const databases = [...new Set(active.manifest.journal.resources
        .filter(({ type, id }) => type === 'database' && typeof id === 'string')
        .map(({ id }) => id))]
      for (const segmentName of databases) assertP0DatabaseName(segmentName)
      if (databases.length !== 0 || context.redisRecoveryMode !== 'NOT_ACQUIRED') {
        composeIdentity = await revalidateP0CleanupComposeIdentity({
          artifactBase: context.artifactBase,
          manifest: active.manifest,
          infrastructure,
          signal
        })
        throwIfP0Aborted(signal)
      }
      await infrastructure.assertPortsFree([18086, 5199, 5200], { signal })
      throwIfP0Aborted(signal)
      if (context.redisRecoveryMode === 'NOT_ACQUIRED') {
        if (databases.length !== 0) throw new Error('P0_NOT_ACQUIRED_RECOVERY_UNSAFE')
        await completeControlCleanup({
          artifactBase: context.artifactBase,
          runId: options.runId,
          runToken: context.ownerToken,
          resourcesCleaned: true,
          redisNotAcquired: true,
          replaceOwnership,
          now
        })
        throwIfP0Aborted(signal)
        return {
          status: 'CLEANED',
          databases: 0,
          redis: 'NOT_ACQUIRED',
          restored: 0
        }
      }
      for (const segmentName of databases) {
        throwIfP0Aborted(signal)
        const identity = await postgres.readDatabaseOwnership(segmentName, { signal })
        throwIfP0Aborted(signal)
        if (identity !== null) {
          await dropOwnedDatabase({
            segmentName,
            runToken: context.ownerToken,
            postgres,
            signal
          })
          throwIfP0Aborted(signal)
        }
      }
      let restored = 0
      const redisState = active.manifest.redisState
      const redisRecoveryMode = context.redisRecoveryMode
        ?? (Array.isArray(context.redisSnapshot) && Array.isArray(context.touchedRedisKeys)
          ? 'SNAPSHOT'
          : null)
      const shouldRestoreSnapshot = redisState === 'SNAPSHOT_READY'
        || (redisState === 'OWNED' && redisRecoveryMode === 'SNAPSHOT')
      const release = await runRedisOwnershipTransaction(redis, async () => {
        if (shouldRestoreSnapshot) {
          if (await redis.get(P0_REDIS_OWNER_KEY, { signal }) !== context.ownerToken) {
            throw new Error('P0_REDIS_OWNER_MISMATCH')
          }
          throwIfP0Aborted(signal)
          restored = await restoreP0RedisSnapshot({
            redis,
            snapshot: context.redisSnapshot,
            touchedKeys: context.touchedRedisKeys,
            signal
          })
          throwIfP0Aborted(signal)
          await transitionP0RedisControlState({
            artifactBase: context.artifactBase,
            runId: options.runId,
            runToken: context.ownerToken,
            from: redisState,
            to: 'REDIS_RELEASE_ARMED',
            signal
          })
          throwIfP0Aborted(signal)
          active.manifest.redisState = 'REDIS_RELEASE_ARMED'
        } else if (['ACQUIRE_ARMED', 'OWNED'].includes(redisState)) {
          if (redisRecoveryMode !== 'RELEASE_ONLY') throw new Error('P0_REDIS_STATE_INVALID')
          assertP0PreSnapshotRecoverySafe(active.manifest)
          if (await redis.get(P0_REDIS_OWNER_KEY, { signal }) !== context.ownerToken) {
            throw new Error('P0_REDIS_OWNER_MISMATCH')
          }
          throwIfP0Aborted(signal)
          await transitionP0RedisControlState({
            artifactBase: context.artifactBase,
            runId: options.runId,
            runToken: context.ownerToken,
            from: redisState,
            to: 'REDIS_RELEASE_ARMED',
            signal
          })
          throwIfP0Aborted(signal)
          active.manifest.redisState = 'REDIS_RELEASE_ARMED'
        } else if (redisState !== 'REDIS_RELEASE_ARMED') {
          throw new Error('P0_REDIS_STATE_INVALID')
        }
        return releaseP0RedisOwnershipWithReceipt({
          redis,
          runToken: context.ownerToken,
          ownerId: context.ownerId,
          signal
        })
      }, { signal })
      throwIfP0Aborted(signal)
      await completeControlCleanup({
        artifactBase: context.artifactBase,
        runId: options.runId,
        runToken: context.ownerToken,
        resourcesCleaned: true,
        redisReleaseReceipt: release.receipt,
        replaceOwnership,
        now
      })
      throwIfP0Aborted(signal)
      const cleanedContext = await recoverP0ActiveControlContext(
        context.artifactBase,
        options.runId
      )
      await finalizePendingCleanupReceipt(cleanedContext, options.runId, { signal })
      throwIfP0Aborted(signal)
      return {
        status: 'CLEANED',
        databases: databases.length,
        redis: 'RESTORED',
        restored
      }
    }
  }
  defaultP0DependencyInstances.add(dependencies)
  return dependencies
}

export async function runP0Suite(options, dependencies) {
  const usesDefaultDependencies = dependencies === undefined
    || defaultP0DependencyInstances.has(dependencies)
  if (usesDefaultDependencies && options.phase !== 'cleanup') {
    assertP0ProcessTreeCapability()
  }
  dependencies ??= createDefaultP0Dependencies()
  const plan = planP0Execution(options, P0_CASES)
  const installSignalHandlers = dependencies.installSignalHandlers ?? installP0SignalHandlers
  const dispatchCase = dependencies.dispatchCase ?? runCase
  const handlers = dependencies.handlers ?? Object.create(null)
  return executeP0SuiteLifecycle({
    options,
    operations: {
      installSignalHandlers,
      async prepare(receivedOptions, details) {
        const prepared = await dependencies.initializeOwnership(receivedOptions, plan, details)
        if (!prepared || typeof prepared !== 'object') throw new Error('P0_OWNERSHIP_INVALID')
        return {
          ...prepared,
          options: receivedOptions,
          plan,
          caseResults: Array.isArray(prepared.caseResults) ? prepared.caseResults : []
        }
      },
      async execute(prepared, details) {
        const baseOperations = typeof dependencies.phaseOperations === 'function'
          ? await dependencies.phaseOperations(prepared, plan, details)
          : dependencies.phaseOperations
        if (!baseOperations || typeof baseOperations !== 'object') {
          throw new Error('P0_PHASE_OPERATIONS_INVALID')
        }
        const controlResults = []
        const phaseResult = await executeP0PlanPhases({
          plan,
          context: prepared,
          signal: details.signal,
          controlResults,
          operations: {
            ...baseOperations,
            async runMatrixPhase(phase, _context, _phasePlan, { signal } = {}) {
              const entries = phase === 'selected'
                ? plan.executionEntries
                : plan.executionEntries.filter((entry) => entry.phase === phase)
              const executeEntry = async (entry, selectedSubruns = entry.selectedSubruns) => {
                const definition = {
                  ...entry.definition,
                  requiredSubruns: selectedSubruns
                }
                throwIfP0Aborted(signal)
                const result = await dispatchCase(definition, prepared, handlers, { signal })
                throwIfP0Aborted(signal)
                return result
              }
              if (supportsP0ProfileLifecycle(baseOperations)) {
                const fragmentsByEntry = new Map(entries.map(({ id }) => [id, []]))
                let attempt = 0
                for (const profile of plan.profiles) {
                  const profileEntries = entries.filter((entry) => (
                    entry.selectedSubruns.some((subrun) => subrun.profile === profile)
                  ))
                  if (profileEntries.length === 0) continue
                  attempt += 1
                  await activateP0Profile({
                    phase,
                    profile,
                    attempt,
                    context: prepared,
                    operations: baseOperations,
                    signal
                  })
                  for (const entry of profileEntries) {
                    const selectedSubruns = entry.selectedSubruns
                      .filter((subrun) => subrun.profile === profile)
                    fragmentsByEntry.get(entry.id).push(
                      await executeEntry(entry, selectedSubruns)
                    )
                  }
                }
                for (const entry of entries) {
                  const fragments = fragmentsByEntry.get(entry.id)
                  if (fragments.length === 0) fragments.push(await executeEntry(entry))
                  prepared.caseResults.push(mergeP0CaseFragments(entry, fragments))
                }
                return
              }
              for (const entry of entries) prepared.caseResults.push(await executeEntry(entry))
            }
          }
        })
        return {
          plan,
          phaseResult,
          controlResults,
          caseResults: [...prepared.caseResults]
        }
      },
      writeReport(execution, prepared, details) {
        return dependencies.writeReport(execution, prepared, details)
      },
      cleanup(prepared, details) {
        return dependencies.cleanup(prepared, details, options, plan)
      }
    }
  })
}

export function createSmokeMain({
  runCanonicalSmoke: runCanonical = runCanonicalSmoke,
  runP0Suite: runP0 = runP0Suite,
  createP0Dependencies = createDefaultP0Dependencies,
  writeStdout = (value) => process.stdout.write(value),
  processTreeProvider
} = {}) {
  const usesDefaultCanonical = runCanonical === runCanonicalSmoke
  const usesDefaultP0 = runP0 === runP0Suite
    && createP0Dependencies === createDefaultP0Dependencies
  return async (argv) => {
    const options = parseP0Cli(argv)
    if (options.list) {
      writeStdout(`${JSON.stringify(P0_CASES, null, 2)}\n`)
      return { listed: P0_CASES.length }
    }
    if (options.suite === 'canonical') {
      if (usesDefaultCanonical) assertP0ProcessTreeCapability()
      return runCanonical({ processTreeProvider })
    }
    if (usesDefaultP0 && options.phase !== 'cleanup') assertP0ProcessTreeCapability()
    return runP0(options, createP0Dependencies({ processTreeProvider }))
  }
}

export async function main(argv = process.argv.slice(2)) {
  return createSmokeMain()(argv)
}

function isMainModule() {
  if (!process.argv[1]) return false
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
}

if (isMainModule()) await main()
