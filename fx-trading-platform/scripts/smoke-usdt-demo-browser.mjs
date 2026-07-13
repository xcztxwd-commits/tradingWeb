import { randomUUID } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const apiBaseUrl = process.env.API_BASE_URL ?? 'http://127.0.0.1:18086'
const webBaseUrl = process.env.WEB_BASE_URL ?? 'http://127.0.0.1:5199'
const adminBaseUrl = process.env.ADMIN_BASE_URL ?? 'http://127.0.0.1:5200'
const runId = process.env.USDT_DEMO_SMOKE_RUN_ID ?? new Date().toISOString().replace(/[:.]/g, '-')
const userEmail = process.env.USDT_DEMO_SMOKE_EMAIL ?? `usdt-demo-browser+${runId}@example.com`
const userPassword = process.env.USDT_DEMO_SMOKE_PASSWORD ?? 'Password123!'
const adminEmail = process.env.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
const adminPassword = process.env.ADMIN_SMOKE_PASSWORD ?? 'Password123!'
const smokeDatabase = `fx_platform_smoke_${randomUUID().replaceAll('-', '')}`
const artifactRoot = resolve(process.env.USDT_DEMO_SMOKE_ARTIFACTS ?? join(projectRoot, 'artifacts', 'smoke-usdt-demo-browser', runId))
const screenshotsDir = join(artifactRoot, 'screenshots')
const logsDir = join(artifactRoot, 'logs')

const INITIAL_SPOT_USDT = 50000
const INITIAL_PERP_USDT = 50000
const SPOT_SYMBOL = 'BTCUSDT'
const PERP_SYMBOL = 'BTCUSDT-PERP'
const SETTINGS_SYMBOL = 'ETHUSDT-PERP'
const LIQUIDATION_SYMBOL = 'SOLUSDT-PERP'
const FALLBACK_FUNDING_SYMBOL = 'BNBUSDT-PERP'
const P0_SPOT_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT']
const P0_PERP_SYMBOLS = ['BTCUSDT-PERP', 'ETHUSDT-PERP', 'BNBUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP']
const FORBIDDEN_PRODUCTS = ['FOREX', 'INVERSE_PERP', 'OPTION']
const SOURCE_METADATA_FIELDS = ['providerCode', 'providerSymbol', 'sourceMode', 'asOf', 'expiresAt', 'stale']
const TERMINAL_ORDER_STATUSES = new Set(['FILLED', 'CANCELED', 'CANCELLED', 'REJECTED', 'EXPIRED'])

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

const results = []
const screenshots = []
const sourceEvidence = []
const processLogs = []
const managedProcesses = []
const bindingRestores = []
const fundingConfigRestores = new Map()
let userToken
let adminToken
let adminRefreshToken
let adminAuthorities = []
let userId
let accountId
let browser
let accountEventObserver
let smokeDatabaseCreated = false
let interruptionError
let sourceModeStartedAtMs = 0
const shutdownController = new AbortController()

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
  ['browser shutdown', async () => { if (browser) await browser.close() }],
  ['managed process shutdown', stopManagedProcesses],
  ['dedicated PostgreSQL database cleanup', dropSmokeDatabase]
]) {
  try {
    await cleanup()
  } catch (error) {
    failure = appendFailure(failure, error, label)
  }
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

async function startRealServices() {
  await startDockerInfrastructure()
  await ensureBackendServer()
  await ensureFrontendServer('web', webBaseUrl, '5199')
  await ensureFrontendServer('admin', adminBaseUrl, '5200')
  return { startupCommands: STARTUP_COMMANDS, apiBaseUrl, webBaseUrl, adminBaseUrl }
}

async function startDockerInfrastructure() {
  const composeFile = join(projectRoot, 'infra', 'docker-compose.yml')
  const result = spawnSync('docker', ['compose', '-f', composeFile, 'up', '-d'], {
    cwd: projectRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.status !== 0) {
    throw new Error(`Real PostgreSQL/Redis prerequisite failed. ${STARTUP_COMMANDS[0]}\n${result.error?.message ?? result.stderr ?? result.stdout}`)
  }
  await waitFor(() => {
    const ready = spawnSync('docker', ['exec', 'fx-platform-postgres', 'pg_isready', '-U', 'postgres', '-d', 'postgres'], {
      encoding: 'utf8',
      windowsHide: true
    })
    return ready.status === 0
  }, 'PostgreSQL container readiness', 30000)
  const created = spawnSync('docker', [
    'exec', 'fx-platform-postgres', 'psql', '-U', 'postgres', '-d', 'postgres',
    '-v', 'ON_ERROR_STOP=1', '-c', `CREATE DATABASE "${smokeDatabase}"`
  ], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (created.status !== 0) {
    throw new Error(`Dedicated PostgreSQL database creation failed:\n${created.error?.message ?? created.stderr ?? created.stdout}`)
  }
  smokeDatabaseCreated = true
}

async function ensureBackendServer() {
  if (await canFetch(`${apiBaseUrl}/actuator/health`)) {
    throw new Error(`The smoke must own ${apiBaseUrl}; an existing backend cannot prove demo execution and required schedulers are enabled`)
  }
  const backendDir = join(projectRoot, 'backend')
  const maven = process.env.MAVEN_CMD ?? (process.platform === 'win32' ? 'mvn.cmd' : 'mvn')
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${smokeDatabase}`
  const child = startManagedProcess('backend', maven, [
    'spring-boot:run',
    '-Dspring-boot.run.profiles=dev'
  ], backendDir, {
    SERVER_PORT: new URL(apiBaseUrl).port || '18086',
    DATABASE_URL: databaseUrl,
    DATABASE_USERNAME: 'postgres',
    DATABASE_PASSWORD: 'password',
    SPRING_DATASOURCE_URL: databaseUrl,
    SPRING_DATASOURCE_USERNAME: 'postgres',
    SPRING_DATASOURCE_PASSWORD: 'password',
    SPRING_PROFILES_ACTIVE: 'dev',
    EXECUTION_MODE: 'demo',
    MARKET_TEST_CONTROL_ENABLED: 'true',
    TRADING_PENDING_ORDER_EXECUTION_ENABLED: 'true',
    TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED: 'true',
    TRADING_FUNDING_ENABLED: 'true',
    TRADING_FUNDING_SCAN_MS: '500',
    TRADING_LIQUIDATION_ENABLED: 'true',
    TRADING_LIQUIDATION_SCAN_INTERVAL_MS: '500',
    PROVIDER_INSTRUMENT_SYNC_ENABLED: 'false'
  }, sanitizedBackendEnvironment())
  await waitFor(async () => {
    assertProcessRunning(child)
    const response = await rawJson('/actuator/health').catch(() => null)
    return response?.status === 'UP'
  }, 'real backend /actuator/health', 120000)
  await waitFor(() => number(runDbSql(`
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
  const child = spawn(command, args, {
    cwd,
    env: { ...inheritedEnv, ...extraEnv },
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: process.platform === 'win32',
    windowsHide: true
  })
  const log = { label, command: [command, ...args].join(' '), output: '' }
  processLogs.push(log)
  child.stdout?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, 20000) })
  child.stderr?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, 20000) })
  managedProcesses.push(child)
  return child
}

function sanitizedBackendEnvironment() {
  const env = { ...process.env }
  for (const key of Object.keys(env)) {
    if (
      key === 'SPRING_APPLICATION_JSON'
      || key === 'SPRING_PROFILES_ACTIVE'
      || key === 'JAVA_TOOL_OPTIONS'
      || key === '_JAVA_OPTIONS'
      || key.startsWith('SPRING_DATASOURCE_')
      || key.startsWith('SPRING_CONFIG_')
      || key.startsWith('DATABASE_')
    ) {
      delete env[key]
    }
  }
  return env
}

async function bootstrapIdentityAndAccount() {
  adminToken = await login(adminEmail, adminPassword, true)
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
  const databaseIdentity = runDbSql(`
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
    }, ['PRODUCT_NOT_ALLOWED', 'SYMBOL_NOT_FOUND', 'SYMBOL_NOT_TRADABLE'])
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
  assert([spotQuote, spotDepth, spotTradeSource].every((payload) => payload.providerCode === spotQuote.providerCode), 'Spot bundle must not mix providers')
  assert([perpQuote, perpDepth, perpTradeSource, perpReference].every((payload) => payload.providerCode === perpQuote.providerCode), 'Perp bundle must not mix providers')
  assert(mode.expectedSpot.includes(spotQuote.providerCode), `${modeId} unexpected Spot provider ${spotQuote.providerCode}`)
  assert(mode.expectedPerp.includes(perpQuote.providerCode), `${modeId} unexpected Perp provider ${perpQuote.providerCode}`)
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

  const providers = await adminApi('/api/admin/market/data-providers')
  const requestedAtMs = Date.parse(startedAt)
  const startedAtMs = sourceModeStartedAtMs > 0
    ? Math.min(requestedAtMs, sourceModeStartedAtMs)
    : requestedAtMs
  const unavailable = higherPriorityCodes.map((code) => {
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
  const buyOco = await createOco('BUY OCO', 'BUY', {
    quantity: '0.0001',
    limitPrice: aligned(last * 0.9998, tick, 'floor'),
    stopTriggerPrice: aligned(last * 1.0002, tick, 'ceil')
  })
  const buyOutcome = await waitOcoOutcome(buyOco.contingencyGroupId)
  assertOcoOutcome(buyOutcome, 'BUY OCO')
  const afterBuyOcoWallets = await walletBalances()
  assert(
    number(assetWallet(afterBuyOcoWallets, 'USDT').locked) <= number(assetWallet(beforeBuyOcoWallets, 'USDT').locked) + 0.000001,
    'OCO peer cancellation must release shared USDT hold'
  )

  const beforeSellOcoWallets = afterBuyOcoWallets
  const sellOco = await createOco('SELL OCO', 'SELL', {
    quantity: '0.0001',
    limitPrice: aligned(last * 1.0002, tick, 'ceil'),
    stopTriggerPrice: aligned(last * 0.9998, tick, 'floor')
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
    number(duringPending.summary.freeMargin) < number(beforePending.summary.freeMargin),
    'pending Perp order must reduce free margin while its hold is active'
  )
  assert(
    duringPending.ledger.some((entry) => entry.entryType === 'ORDER_HOLD' && entry.referenceId === pending.id),
    'pending Perp order must create its order-linked margin hold ledger entry'
  )
  await api(`/api/trading/orders/${pending.id}/cancel`, { method: 'POST', token: userToken })
  const afterPending = await accountFundsSnapshot()
  assert(number(afterPending.summary.freeMargin) >= number(beforePending.summary.freeMargin) - 0.01, 'cancel pending Perp order must release margin hold')
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
  const added = await api(`/api/trading/positions/${isolated.id}/margin`, {
    method: 'POST', token: userToken, body: { action: 'ADD', amount: '10', expectedVersion: isolated.version }
  })
  assertNear(number(added.positionMargin), marginBefore + 10, 0.000001, 'Isolated margin after +10')
  isolated = (await openPositions(SETTINGS_SYMBOL))[0]
  assertNear(number(isolated.marginHeld), marginBefore + 10, 0.000001, 'persisted Isolated margin after +10')
  const reduced = await api(`/api/trading/positions/${isolated.id}/margin`, {
    method: 'POST', token: userToken, body: { action: 'REDUCE', amount: '1', expectedVersion: isolated.version }
  })
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
  await expectApiError(`/api/trading/positions/${isolated.id}/margin`, {
    method: 'POST', token: userToken, body: { action: 'REDUCE', amount: '1000000', expectedVersion: isolated.version }
  }, ['MARGIN_REDUCTION_UNSAFE', 'INSUFFICIENT_MARGIN'])

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
    symbol: PERP_SYMBOL, side: 'BUY', orderType: 'MARKET', quantity: '0.05', quantityUnit: 'BASE', leverage: 10,
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
    .filter((order) => created.some((candidate) => candidate.id === order.id) && order.status === 'PENDING_ACTIVATION')
    .toSorted((left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt))
  const triggeredCommitment = beforeResize
    .filter((order) => created.some((candidate) => candidate.id === order.id)
      && order.status !== 'PENDING_ACTIVATION'
      && !TERMINAL_ORDER_STATUSES.has(order.status))
    .reduce((sum, order) => sum + number(order.quantity), 0)
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
    Math.max(0, number(resizedPosition.lots) - triggeredCommitment)
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
  clearFundingRatesForSymbol(FALLBACK_FUNDING_SYMBOL)
  const fallbackRate = await waitForRealFundingRate(
    FALLBACK_FUNDING_SYMBOL,
    ['fixed'],
    number(oppositeRate),
    75000,
    fallbackFundingPhaseStartedAt,
    15000
  )
  const fallbackConfig = await fundingConfig(FALLBACK_FUNDING_SYMBOL)
  assert(fallbackConfig.actualSource === 'FIXED', `fallback funding config must expose actualSource=FIXED, got ${fallbackConfig.actualSource}`)
  assert(fallbackConfig.sourceMode === 'LOCAL_SIMULATED', `fallback funding config must expose LOCAL_SIMULATED, got ${fallbackConfig.sourceMode}`)
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
  assert((await openPositions()).length === 0, 'close-all must leave no open position')
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

function assertNewestFirstProtectionResize(before, after, remainingProtectionCapacity) {
  const byId = new Map(after.map((order) => [order.id, order]))
  const changedFlags = before.map((order) => {
    const current = byId.get(order.id)
    return !current
      || current.status !== order.status
      || Math.abs(number(current.quantity) - number(order.quantity)) > 0.00000001
  })
  const firstChanged = changedFlags.indexOf(true)
  assert(firstChanged >= 0, 'partial close must auto-resize or cancel protection levels')
  assert(
    changedFlags.slice(firstChanged).every(Boolean),
    'protection newest-first auto-resize must change one contiguous newest-order suffix without gaps'
  )
  const beforeQuantity = before.reduce((sum, order) => sum + number(order.quantity), 0)
  const afterQuantity = before.reduce((sum, order) => {
    const current = byId.get(order.id)
    return sum + (current && !TERMINAL_ORDER_STATUSES.has(current.status) ? number(current.quantity) : 0)
  }, 0)
  const expectedReduction = Math.max(0, beforeQuantity - remainingProtectionCapacity)
  assertNear(beforeQuantity - afterQuantity, expectedReduction, 0.00000001, 'newest-first protection reduction amount')
  assert(afterQuantity <= remainingProtectionCapacity + 0.00000001, 'active protection quantity must not exceed the remaining position capacity')
}

async function assertNoForeignOpenPositions(symbol, label) {
  const count = number(runDbSql(`
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
  clearFundingRatesForSymbol(PERP_SYMBOL)
  try {
    const rate = await waitForRealFundingRate(
      PERP_SYMBOL,
      ['binance-usdm', 'okx-swap'],
      null,
      45000,
      externalPhaseStartedAt
    )
    const config = await fundingConfig(PERP_SYMBOL)
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
    clearFundingRatesForSymbol(PERP_SYMBOL)
    const rate = await waitForRealFundingRate(
      PERP_SYMBOL,
      ['fixed'],
      0.0001,
      75000,
      fixedPhaseStartedAt,
      15000
    )
    const fixedConfig = await fundingConfig(PERP_SYMBOL)
    assert(fixedConfig.actualSource === 'FIXED', `selected funding fallback must expose actualSource=FIXED, got ${fixedConfig.actualSource}`)
    assert(fixedConfig.sourceMode === 'LOCAL_SIMULATED', `selected funding fallback must expose LOCAL_SIMULATED, got ${fixedConfig.sourceMode}`)
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
  return waitFor(() => {
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
    const row = runDbSql(`
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
  const originalOpenedAtMicros = shiftPositionOpenedBeforeFunding(position.id, rate)
  let settlement
  try {
    settlement = await waitFundingSettlement(rate, position, settlementIdsBefore)
  } finally {
    restorePositionOpenedAt(position.id, originalOpenedAtMicros)
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

function shiftPositionOpenedBeforeFunding(positionId, rate) {
  const originalOpenedAtMicros = runDbSql(`
    SELECT floor(extract(epoch FROM opened_at) * 1000000)::bigint
    FROM trading.positions
    WHERE id = '${sqlLiteral(positionId)}'
      AND account_id = '${sqlLiteral(accountId)}'
      AND status = 'OPEN'
  `)
  assert(originalOpenedAtMicros, `funding fixture position ${positionId} must be an open position owned by the smoke account`)
  const updated = number(runDbSql(`
    WITH updated AS (
      UPDATE trading.positions
      SET opened_at = to_timestamp(${rate.fundingTimeMs} / 1000.0) - interval '1 millisecond',
          updated_at = now()
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

function restorePositionOpenedAt(positionId, originalOpenedAtMicros) {
  const restored = number(runDbSql(`
    WITH restored AS (
      UPDATE trading.positions
      SET opened_at = to_timestamp(${originalOpenedAtMicros} / 1000000.0),
          updated_at = now()
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

function clearFundingRatesForSymbol(symbol) {
  runDbSql(`DELETE FROM trading.funding_rates WHERE symbol = '${sqlLiteral(symbol)}'`)
}

async function isolatePreparedFundingRate(symbol, rate) {
  await updateFundingConfig(symbol, {
    fixedFundingIntervalMinutes: 525600,
    reason: 'Isolate the prepared Task18 funding cycle while its smoke position is open'
  })
  const deleted = number(runDbSql(`
    WITH deleted AS (
      DELETE FROM trading.funding_rates
      WHERE symbol = '${sqlLiteral(symbol)}'
        AND id <> '${sqlLiteral(rate.id)}'
      RETURNING id
    )
    SELECT count(*) FROM deleted
  `))
  const nearCompetingRates = number(runDbSql(`
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
  const notifications = runDbSql(`
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
    symbol: 'XRPUSDT-PERP', side: 'BUY', orderType: 'MARKET', quantity: '10000', quantityUnit: 'BASE', leverage: 100,
    positionSide: 'BOTH', marginMode: 'CROSS'
  })
  const opened = await openPositions()
  const openedCross = opened.filter((position) => position.marginMode === 'CROSS')
  assert(openedCross.length >= 2, 'Cross liquidation fixture must have at least two Cross positions')
  installAccountScopedCrossShortfallFixture()
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
  const settledCharges = number(runDbSql(`
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

function installAccountScopedCrossShortfallFixture() {
  const updated = number(runDbSql(`
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
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry.text)
  })
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Log.enable')
  await page.send('Network.enable')
  await setViewport(page, VIEWPORTS.find((viewport) => viewport.name === 'web-desktop'))
  try {
    await installBrowserSession(page, webBaseUrl, { 'fx-platform-auth-token': userToken })

    const spotQuote = await api(`/api/market/quotes/${SPOT_SYMBOL}`)
    const spotRules = await api(`/api/market/symbols/${SPOT_SYMBOL}/rules`)
    const spotLast = number(spotQuote.mid ?? spotQuote.ask)
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
    const spotLimit = await submitBrowserOrder(page, {
      side: 'buy', tabIndex: 0,
      values: [aligned(spotLast * 0.5, spotTick, 'floor'), '0.001'],
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
  await page.waitForFunction(() => Boolean(
    document.querySelector('.trade-panel')
      && document.querySelector('[data-source]')
      && !document.querySelector('.trade-panel__submit--login')
      && [...document.querySelectorAll('.trade-panel__balance strong')].some((value) => !value.textContent?.trim().startsWith('-'))
  ), `real order controls ${route}`)
  const actual = await api(`/api/market/quotes/${symbol}`)
  await page.waitForFunction(
    (provider) => document.body.textContent?.toUpperCase().includes(provider.toUpperCase()),
    `actual provider visible ${route}`,
    actual.providerCode
  )
}

async function submitBrowserOrder(page, { side, tabIndex, values, reduceOnly = false, label }) {
  const beforeIds = new Set((await orders({ size: 500 })).map((order) => order.id))
  const tabSelected = await page.evaluate((index) => {
    const tabs = document.querySelector('.trade-panel__order-tabs')
    const button = tabs?.querySelectorAll('button')[index]
    button?.click()
    return Boolean(button)
  }, tabIndex)
  assert(tabSelected, `${label} order-type tab must be available`)
  await page.waitForFunction((index) => {
    const button = document.querySelector('.trade-panel__order-tabs')?.querySelectorAll('button')[index]
    return button?.getAttribute('aria-selected') === 'true'
  }, `${label} order-type tab selected`, tabIndex)
  for (let index = 0; index < values.length; index += 1) {
    const changed = await page.evaluate((targetSide, inputIndex, value) => {
      const section = document.querySelector(`.trade-panel__side--${targetSide}`)
      const inputs = [...(section?.querySelectorAll('input[inputmode="decimal"]:not([disabled])') ?? [])]
      const input = inputs[inputIndex]
      if (!input) return false
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    }, side, index, values[index])
    assert(changed, `${label} input ${index} must be editable`)
    await sleep(50)
  }
  if (reduceOnly) {
    const checked = await page.evaluate((targetSide) => {
      const input = document.querySelector(`.trade-panel__side--${targetSide} .trade-panel__perpetual-options input[type="checkbox"]`)
      if (!input) return false
      if (!input.checked) input.click()
      return input.checked
    }, side)
    assert(checked, `${label} must visibly enable reduce-only`)
  }
  const clicked = await page.evaluate((targetSide) => {
    const button = document.querySelector(`.trade-panel__submit--${targetSide}`)
    if (!button || button.disabled) return false
    button.click()
    return true
  }, side)
  assert(clicked, `${label} submit control must be enabled`)
  const confirmationState = await waitFor(() => page.evaluate(() => {
    if (document.querySelector('.trade-panel__confirm')) return 'dialog'
    return localStorage.getItem('fx-trade-confirm-skip') === 'true' ? 'skipped' : null
  }), `${label} confirmation`, 5000)
  if (confirmationState === 'dialog') {
    const confirmed = await page.evaluate(() => {
      const dialog = document.querySelector('.trade-panel__confirm')
      const skip = dialog?.querySelector('input[type="checkbox"]')
      const submit = dialog?.querySelector('.trade-panel__confirm-submit')
      if (!dialog || !skip || !submit) return false
      if (!skip.checked) skip.click()
      submit.click()
      return true
    })
    assert(confirmed, `${label} confirmation dialog must submit`)
  }
  return waitFor(async () => {
    const created = (await orders({ size: 500 }))
      .filter((order) => !beforeIds.has(order.id))
      .toSorted((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
    return created[0] ?? false
  }, `${label} resulting REST order`, 15000)
}

async function cancelBrowserOrder(page, orderId, label) {
  await page.navigate(`${webBaseUrl}/orders?ui-loop=${runId}`)
  await waitForPageReady(page, '/orders')
  const clicked = await waitFor(() => page.evaluate((targetOrderId) => {
    const actions = [...document.querySelectorAll('[data-order-id]')]
      .find((element) => element.getAttribute('data-order-id') === targetOrderId)
    const button = actions?.querySelector('button.table-action--danger:not([disabled])')
    if (!button) return false
    button.click()
    return true
  }, orderId), `${label} UI action`, 15000)
  assert(clicked, `${label} must click the real Orders-page action`)
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
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry.text)
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
    if (event.entry?.level === 'error') runtimeErrors.push(event.entry.text)
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
          const actionBar = document.querySelector('nav[class*="actionBar"]')
          const tradeButton = actionBar?.querySelector('button:nth-of-type(2)')
          tradeButton?.click()
          return Boolean(tradeButton)
        })
        assert(opened, `${route} mobile Trade action must be available`)
        await page.waitForFunction(() => {
          const panel = document.querySelector('.trade-panel')
          const layer = panel?.closest('[aria-hidden]')
          const rect = panel?.getBoundingClientRect()
          return layer?.getAttribute('aria-hidden') === 'false'
            && Boolean(rect && rect.width > 0 && rect.height > 0)
        }, 'mobile real order sheet')
      } else {
        await page.waitForFunction(() => Boolean(document.querySelector('.trade-panel')), 'desktop real order controls')
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
  const selected = await page.evaluate((symbol) => {
    const select = [...document.querySelectorAll('select')].find((candidate) =>
      [...candidate.options].some((option) => option.textContent?.includes(symbol))
    )
    const option = select && [...select.options].find((candidate) => candidate.textContent?.includes(symbol))
    if (!select || !option) return false
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    setter?.call(select, option.value)
    select.dispatchEvent(new Event('input', { bubbles: true }))
    select.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  }, PERP_SYMBOL)
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

function assertNoRuntimeErrors(errors, label) {
  const relevant = errors.filter((message) => !/ResizeObserver loop|favicon\.ico/i.test(message))
  assert(relevant.length === 0, `${label} browser runtime errors: ${relevant.join(' | ')}`)
}

async function launchBrowser() {
  const executable = browserCandidates().find(existsSync)
  assert(executable, 'Chrome or Edge is required; set SMOKE_BROWSER_PATH or CHROME_PATH')
  const port = await freePort()
  const userDataDir = await mkdtemp(join(tmpdir(), 'fx-usdt-demo-smoke-'))
  const child = spawn(executable, [
    '--headless=new',
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
  const log = { label: 'browser', command: executable, output: '' }
  processLogs.push(log)
  child.stdout?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, 20000) })
  child.stderr?.on('data', (chunk) => { log.output = appendTail(log.output, chunk, 20000) })
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
  const raw = runDbSql(`
    SELECT json_build_object(
      'wallets', (SELECT count(*) FROM core.wallet_balances WHERE account_id = '${sqlLiteral(accountId)}'),
      'assetLedger', (SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = '${sqlLiteral(accountId)}'),
      'orders', (SELECT count(*) FROM trading.orders WHERE account_id = '${sqlLiteral(accountId)}'),
      'trades', (SELECT count(*) FROM trading.trades WHERE account_id = '${sqlLiteral(accountId)}'),
      'positions', (SELECT count(*) FROM trading.positions WHERE account_id = '${sqlLiteral(accountId)}' AND status = 'OPEN'),
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

function runDbSql(sql) {
  const result = spawnSync('docker', ['exec', 'fx-platform-postgres', 'psql', '-U', 'postgres', '-d', smokeDatabase, '-tA', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.status !== 0) throw new Error(`PostgreSQL assertion/fixture failed:\n${result.error?.message ?? result.stderr ?? result.stdout}`)
  return result.stdout.trim()
}

async function dropSmokeDatabase() {
  if (!smokeDatabaseCreated) return
  const result = spawnSync('docker', [
    'exec', 'fx-platform-postgres', 'dropdb', '-U', 'postgres', '--force', '--if-exists', smokeDatabase
  ], {
    encoding: 'utf8',
    windowsHide: true
  })
  if (result.status !== 0) {
    throw new Error(`Dedicated PostgreSQL database cleanup failed:\n${result.error?.message ?? result.stderr ?? result.stdout}`)
  }
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

async function waitFor(check, label, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    throwIfInterrupted()
    try {
      const value = await check()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await sleep(200)
  }
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

async function canFetch(url) {
  try {
    const response = await fetch(url, { signal: operationSignal(1500) })
    return response.ok || response.status < 500
  } catch {
    throwIfInterrupted()
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
  if (hasProcessExited(child)) throw new Error(`managed process exited early with code ${child.exitCode ?? child.signalCode}`)
}

async function stopManagedProcesses() {
  const failures = []
  for (const child of managedProcesses.toReversed()) {
    try {
      await terminateProcessTree(child, `managed process ${child.pid ?? 'unknown'}`)
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, `failed to stop ${failures.length} managed process(es)`)
  }
}

async function terminateProcessTree(child, label) {
  if (!child || hasProcessExited(child)) return
  let terminationError = ''
  if (process.platform === 'win32') {
    const result = spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
      encoding: 'utf8',
      windowsHide: true
    })
    if (result.status !== 0) terminationError = result.error?.message ?? result.stderr ?? result.stdout
  } else {
    if (!child.kill('SIGTERM')) terminationError = 'SIGTERM was not delivered'
  }
  if (await waitForProcessExit(child, 5000)) return
  if (process.platform !== 'win32') {
    if (!child.kill('SIGKILL')) terminationError = `${terminationError}; SIGKILL was not delivered`
    if (await waitForProcessExit(child, 3000)) return
  }
  throw new Error(`${label} did not exit after termination${terminationError ? `: ${terminationError.trim()}` : ''}`)
}

function hasProcessExited(child) {
  return child.exitCode !== null || child.signalCode !== null
}

function waitForProcessExit(child, timeoutMs) {
  if (hasProcessExited(child)) return Promise.resolve(true)
  return new Promise((resolvePromise) => {
    const timeout = setTimeout(() => {
      child.removeListener('exit', onExit)
      resolvePromise(hasProcessExited(child))
    }, timeoutMs)
    const onExit = () => {
      clearTimeout(timeout)
      child.removeListener('exit', onExit)
      resolvePromise(true)
    }
    child.once('exit', onExit)
    if (hasProcessExited(child)) onExit()
  })
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

function sleep(timeoutMs) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, timeoutMs))
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
