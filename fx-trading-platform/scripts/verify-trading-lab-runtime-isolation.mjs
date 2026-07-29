import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

export const VALIDATION_COMPOSE_PROJECT = 'fx-trading-validation'
export const VALIDATION_BACKEND_SERVICE = 'validation-backend'
export const VALIDATION_INTERNAL_NETWORK = 'fx-trading-validation-internal'
export const SUPERVISOR_URL =
  'http://127.0.0.1:18088/validation-supervisor'

const VALIDATION_POSTGRES_SERVICE = 'validation-postgres'
const VALIDATION_REDIS_SERVICE = 'validation-redis'
const MAIN_POSTGRES_CONTAINER = 'fx-platform-postgres'
const MAIN_REDIS_CONTAINER = 'fx-platform-redis'
const MAIN_POSTGRES_DATABASE = 'fx_platform'
const MAIN_POSTGRES_USER = 'postgres'
const CONTAINER_ID_PATTERN = /^[0-9a-f]{64}$/u
const MD5_PATTERN = /^[0-9a-f]{32}$/u
const SHA256_PATTERN = /^[0-9a-f]{64}$/u
const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u
const MAX_COMMAND_OUTPUT_BYTES = 8 * 1024 * 1024
const MAIN_POSTGRES_MAX_LINE_BYTES = 16 * 1024 * 1024
const MAIN_POSTGRES_MAX_STDERR_BYTES = 1024 * 1024
const DEFAULT_COMMAND_TIMEOUT_MS = 30_000
const NETWORK_COMMAND_TIMEOUT_MS = 7_000
const SUPERVISOR_REQUEST_TIMEOUT_MS = 5_000
const MAX_SUPERVISOR_RESPONSE_BYTES = 16 * 1024

const DISABLED_RUNTIME_FLAGS = Object.freeze([
  'MARKET_DEMO_QUOTES_ENABLED',
  'MARKET_REALTIME_ENABLED',
  'MARKET_REALTIME_BACKFILL_ENABLED',
  'MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED',
  'MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED',
  'MARKET_QUOTE_BROADCAST_ENABLED',
  'MARKET_TEST_DATA_ENABLED',
  'MARKET_TEST_CONTROL_ENABLED',
  'MARKET_WRITE_QUOTES_TO_DB',
  'ADMIN_BOOTSTRAP_ENABLED',
  'ENGAGEMENT_SCHEDULER_ENABLED',
  'ENGAGEMENT_RETENTION_ENABLED',
  'ENGAGEMENT_OUTBOX_ENABLED',
  'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
  'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
  'TRADING_FUNDING_ENABLED',
  'TRADING_FX_FINANCING_ENABLED',
  'TRADING_LIQUIDATION_ENABLED',
  'TRADING_LAB_QUEUE_ENABLED',
  'TRADING_LAB_REPORT_CLEANUP_ENABLED',
])

const REQUIRED_VALIDATION_SECRETS = Object.freeze([
  'VALIDATION_DATABASE_PASSWORD',
  'VALIDATION_REDIS_PASSWORD',
  'VALIDATION_JWT_SECRET',
  'VALIDATION_CONFIG_ENCRYPTION_KEY',
  'VALIDATION_INTERNAL_SECRET',
])

const FORBIDDEN_EXTERNAL_ENV = Object.freeze([
  'BINANCE_REST_BASE_URL',
  'BINANCE_WEB_BASE_URL',
  'BINANCE_FUTURES_BASE_URL',
  'BINANCE_WS_BASE_URL',
  'OKX_REST_BASE_URL',
  'MASSIVE_REST_BASE_URL',
  'MASSIVE_WS_FOREX_URL',
  'MASSIVE_S3_ENDPOINT',
  'MASSIVE_API_KEY',
  'MASSIVE_S3_ACCESS_KEY_ID',
  'MASSIVE_S3_SECRET_ACCESS_KEY',
  'EXECUTION_BROKER_ENDPOINT',
  'EXECUTION_BROKER_API_KEY',
  'EXECUTION_BROKER_ACCOUNT_ID',
  'EXECUTION_FIX_ENDPOINT',
  'EXECUTION_FIX_API_KEY',
  'EXECUTION_FIX_ACCOUNT_ID',
  'EXECUTION_LP_ENDPOINT',
  'EXECUTION_LP_API_KEY',
  'EXECUTION_LP_ACCOUNT_ID',
])

const REPORT_SECRET_ENV_NAMES = Object.freeze([
  'SUPERVISOR_INTERNAL_TOKEN',
  'VALIDATION_INTERNAL_SECRET',
  'VALIDATION_DATABASE_PASSWORD',
  'VALIDATION_REDIS_PASSWORD',
  'VALIDATION_JWT_SECRET',
  'VALIDATION_CONFIG_ENCRYPTION_KEY',
  'DATABASE_PASSWORD',
  'REDIS_PASSWORD',
  'JWT_SECRET',
  'CONFIG_ENCRYPTION_KEY',
  'ADMIN_BOOTSTRAP_PASSWORD',
  'TRADING_LAB_SMOKE_SUPER_PASSWORD',
  'TRADING_LAB_SMOKE_VIEW_PASSWORD',
  'TRADING_LAB_SMOKE_EXECUTE_PASSWORD',
  'TRADING_LAB_SMOKE_ORDINARY_PASSWORD',
])

const SENSITIVE_FIELD_NAMES = new Set([
  'authorization',
  'proxyauthorization',
  'cookie',
  'setcookie',
  'password',
  'passwd',
  'databasepassword',
  'dbpassword',
  'jwtsecret',
  'validationinternaltoken',
  'validationinternalsecret',
  'token',
  'accesstoken',
  'refreshtoken',
  'apikey',
  'secret',
  'credential',
])

const SAFE_CREDENTIAL_VALUE_PATTERN =
  /^(?:|(?:\[|<)?(?:redacted|masked|omitted|not[-_ ]?present|none|sanitized)(?:\]|>)?|\*{3,}|validation-only)$/iu
const BEARER_VALUE_PATTERN = /\bBearer\s+(?!\[?redacted\]?|<?masked>?|<?omitted>?)[^\s,;"']+/iu
const COOKIE_VALUE_PATTERN =
  /\b(?:Cookie|Set-Cookie)\s*[:=]\s*(?!\[?redacted\]?|<?masked>?|<?omitted>?)[^\r\n]+/iu

export const FIXED_NETWORK_PROBES = Object.freeze([
  fixedProbe('public-binance-rest', 'api.binance.com', 443, false),
  fixedProbe('public-okx-rest', 'www.okx.com', 443, false),
  fixedProbe('public-massive-rest', 'api.massive.com', 443, false),
  fixedProbe('main-postgres-host-port', 'host.docker.internal', 5432, false),
  fixedProbe('main-redis-host-port', 'host.docker.internal', 6379, false),
  fixedProbe('configured-broker-fail-sink', '127.0.0.1', 9, false),
  fixedProbe('configured-fix-fail-sink', '127.0.0.1', 9, false),
  fixedProbe('configured-lp-fail-sink', '127.0.0.1', 9, false),
  fixedProbe('validation-postgres', VALIDATION_POSTGRES_SERVICE, 5432, true),
  fixedProbe('validation-redis', VALIDATION_REDIS_SERVICE, 6379, true),
])

export const SUPERVISOR_INJECTION_PROBES = Object.freeze([
  fixedInjectionProbe(
    'extra-command',
    Object.freeze({ action: 'start', command: 'rm -rf /' }),
    'INVALID_REQUEST',
  ),
  fixedInjectionProbe(
    'path-as-action',
    Object.freeze({ action: '../docker-compose.yml' }),
    'INVALID_ACTION',
  ),
  fixedInjectionProbe(
    'service-selector',
    Object.freeze({ action: 'restart', service: 'postgres' }),
    'INVALID_REQUEST',
  ),
])

const CONTROL_PLANE_TABLES = Object.freeze([
  Object.freeze(['audit', 'audit_logs']),
  Object.freeze(['audit', 'request_logs']),
  Object.freeze(['trading_lab', 'audit_events']),
  Object.freeze(['trading_lab', 'report_appends']),
  Object.freeze(['trading_lab', 'report_chunks']),
  Object.freeze(['trading_lab', 'reports']),
  Object.freeze(['trading_lab', 'run_events']),
  Object.freeze(['trading_lab', 'run_transitions']),
  Object.freeze(['trading_lab', 'runs']),
  Object.freeze(['trading_lab', 'scenarios']),
])
const CONTROL_PLANE_TABLE_KEYS = new Set(
  CONTROL_PLANE_TABLES.map(([schema, table]) => `${schema}.${table}`),
)
const TERMINAL_RUN_STATES = new Set(['CANCELLED', 'FAILED', 'COMPLETED'])
const ENVIRONMENT_AUDIT_ACTION = 'TRADING_LAB_ENVIRONMENT_CHECKED'
const SCENARIO_CREATE_AUDIT_ACTION = 'TRADING_LAB_SCENARIO_CREATE'
const RUN_CREATE_AUDIT_ACTION = 'TRADING_LAB_RUN_CREATE'
const RUN_CONTROL_AUDIT_ACTION = 'TRADING_LAB_RUN_CONTROL'
const ENVIRONMENT_ACTION_AUDIT_ACTION = 'TRADING_LAB_ENVIRONMENT_ACTION'
const REPORT_DELETE_AUDIT_ACTION = 'TRADING_LAB_REPORT_DELETE'
const REPORT_PERMANENT_AUDIT_ACTION = 'TRADING_LAB_REPORT_PERMANENT'
const REQUEST_LOG_ROUTES = Object.freeze({
  AUTH_LOGIN: 'AUTH_LOGIN',
  DASHBOARD_SUMMARY: 'DASHBOARD_SUMMARY',
  CONFIG: 'CONFIG',
  ENVIRONMENT_STATUS: 'ENVIRONMENT_STATUS',
  ENVIRONMENT_CONTROL: 'ENVIRONMENT_CONTROL',
  SCENARIO_CREATE: 'SCENARIO_CREATE',
  SCENARIO_GET: 'SCENARIO_GET',
  RUN_CREATE: 'RUN_CREATE',
  RUN_GET: 'RUN_GET',
  RUN_EVENTS: 'RUN_EVENTS',
  RUN_CONTROL: 'RUN_CONTROL',
  REPORT_GET: 'REPORT_GET',
  REPORT_DOWNLOAD: 'REPORT_DOWNLOAD',
  REPORT_DELETE: 'REPORT_DELETE',
  REPORT_PERMANENT: 'REPORT_PERMANENT',
  REPORT_PRINT_INFO: 'REPORT_PRINT_INFO',
  REPORT_PRINT_CONFIRMATION: 'REPORT_PRINT_CONFIRMATION',
  REPORT_PRINT: 'REPORT_PRINT',
  PREFLIGHT_STATIC: 'PREFLIGHT_STATIC',
  PREFLIGHT_SCENARIO: 'PREFLIGHT_SCENARIO',
  PREFLIGHT_RUN: 'PREFLIGHT_RUN',
  PREFLIGHT_REPORT: 'PREFLIGHT_REPORT',
})

export function buildValidationTcpProbeSpec(backendContainerId, probe) {
  if (!CONTAINER_ID_PATTERN.test(backendContainerId ?? '')) {
    throw new Error('Expected a validated container id')
  }
  const fixed = FIXED_NETWORK_PROBES.find(({ id }) => id === probe?.id)
  if (
    !fixed
    || fixed.host !== probe.host
    || fixed.port !== probe.port
    || fixed.expectedReachable !== probe.expectedReachable
  ) {
    throw new Error('Expected a fixed validation network probe')
  }
  return {
    executable: 'docker',
    args: [
      'exec',
      backendContainerId,
      '/bin/busybox',
      'timeout',
      '-s',
      'KILL',
      '3',
      '/bin/busybox',
      'nc',
      '-z',
      '-w',
      '2',
      fixed.host,
      String(fixed.port),
    ],
    options: {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  }
}

export async function collectValidationRuntimeIdentity({
  runCommand = runFixedCommand,
} = {}) {
  const lookupOutput = runCommand({
    executable: 'docker',
    args: [
      'container',
      'ls',
      '--no-trunc',
      '--filter',
      `label=com.docker.compose.project=${VALIDATION_COMPOSE_PROJECT}`,
      '--filter',
      `label=com.docker.compose.service=${VALIDATION_BACKEND_SERVICE}`,
      '--filter',
      'status=running',
      '--format',
      '{{.ID}}',
    ],
    label: 'validation backend lookup',
  })
  const containerIds = lookupOutput
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
  if (
    containerIds.length !== 1
    || !CONTAINER_ID_PATTERN.test(containerIds[0])
  ) {
    throw new Error('Validation runtime isolation identity failed')
  }

  const inspectDocument = parseSingleDockerInspect(
    runCommand({
      executable: 'docker',
      args: ['inspect', containerIds[0]],
      label: 'validation backend inspect',
    }),
  )
  const networkDocument = parseSingleDockerInspect(
    runCommand({
      executable: 'docker',
      args: ['network', 'inspect', VALIDATION_INTERNAL_NETWORK],
      label: 'validation network inspect',
    }),
  )
  const environment = parseDockerEnvironment(inspectDocument.Config?.Env)
  const labels = inspectDocument.Config?.Labels ?? {}
  const networks = Object.keys(
    inspectDocument.NetworkSettings?.Networks ?? {},
  ).sort()
  const ports = Object.values(
    inspectDocument.NetworkSettings?.Ports ?? {},
  )

  const identity = {
    composeProject: labels['com.docker.compose.project'] ?? '',
    composeService: labels['com.docker.compose.service'] ?? '',
    running: inspectDocument.State?.Running === true,
    networks,
    networkInternal:
      networkDocument.Name === VALIDATION_INTERNAL_NETWORK
      && networkDocument.Internal === true,
    publishedPorts: ports.some(
      (bindings) => Array.isArray(bindings) && bindings.length > 0,
    ),
    springProfiles: environment.get('SPRING_PROFILES_ACTIVE') ?? '',
    executionMode: environment.get('EXECUTION_MODE') ?? '',
    disabledRuntimeFlags: Object.fromEntries(
      DISABLED_RUNTIME_FLAGS.map((name) => [
        name,
        environment.get(name) ?? '',
      ]),
    ),
    requiredSecretsPresent: Object.fromEntries(
      REQUIRED_VALIDATION_SECRETS.map((name) => [
        name,
        Boolean(environment.get(name)),
      ]),
    ),
    externalEndpointOverrides: FORBIDDEN_EXTERNAL_ENV
      .filter((name) => Boolean(environment.get(name)))
      .sort(),
    containerIdentitySha256: sha256(containerIds[0]),
  }
  assertValidationRuntimeIdentity(identity)
  return identity
}

export function assertValidationRuntimeIdentity(identity) {
  const valid =
    isPlainObject(identity)
    && identity.composeProject === VALIDATION_COMPOSE_PROJECT
    && identity.composeService === VALIDATION_BACKEND_SERVICE
    && identity.running === true
    && Array.isArray(identity.networks)
    && identity.networks.length === 1
    && identity.networks[0] === VALIDATION_INTERNAL_NETWORK
    && identity.networkInternal === true
    && identity.publishedPorts === false
    && identity.springProfiles === 'validation'
    && identity.executionMode === 'demo'
    && DISABLED_RUNTIME_FLAGS.every(
      (name) => identity.disabledRuntimeFlags?.[name] === 'false',
    )
    && REQUIRED_VALIDATION_SECRETS.every(
      (name) => identity.requiredSecretsPresent?.[name] === true,
    )
    && Array.isArray(identity.externalEndpointOverrides)
    && identity.externalEndpointOverrides.length === 0
  if (!valid) {
    throw new Error('Validation runtime isolation identity failed')
  }
}

export async function runValidationNetworkProbes({
  backendContainerId,
  runProbe = runTcpProbe,
  runCommand = runFixedCommand,
} = {}) {
  let fixedContainerId = backendContainerId
  if (fixedContainerId === undefined) {
    const output = runCommand({
      executable: 'docker',
      args: [
        'container',
        'ls',
        '--no-trunc',
        '--filter',
        `label=com.docker.compose.project=${VALIDATION_COMPOSE_PROJECT}`,
        '--filter',
        `label=com.docker.compose.service=${VALIDATION_BACKEND_SERVICE}`,
        '--filter',
        'status=running',
        '--format',
        '{{.ID}}',
      ],
      label: 'validation backend lookup',
    })
    const ids = output
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter(Boolean)
    if (ids.length !== 1 || !CONTAINER_ID_PATTERN.test(ids[0])) {
      throw new Error('Validation network probe could not resolve backend')
    }
    fixedContainerId = ids[0]
  }

  const results = []
  for (const probe of FIXED_NETWORK_PROBES) {
    const reachable = await runProbe(
      buildValidationTcpProbeSpec(fixedContainerId, probe),
    )
    const passed = reachable === probe.expectedReachable
    results.push({
      id: probe.id,
      expectedReachable: probe.expectedReachable,
      reachable,
      passed,
    })
    if (!passed) {
      throw new Error(`Validation network isolation probe failed: ${probe.id}`)
    }
  }
  return results
}

export function buildMainDataFingerprint({
  postgresOutput,
  redisOutput,
}) {
  const postgres = parsePostgresFingerprint(postgresOutput)
  return buildMainDataFingerprintFromParsed({ postgres, redisOutput })
}

function buildMainDataFingerprintFromParsed({
  postgres,
  redisOutput,
}) {
  const redisKeys = parseRedisKeys(redisOutput)
  const protectedPostgres = {
    tables: postgres.protectedTables,
    sha256: sha256(canonicalJson(postgres.protectedTables)),
  }
  const redisKeyHashes = redisKeys.map(sha256).sort()
  const mainRedis = {
    keyCount: redisKeyHashes.length,
    sha256: sha256(canonicalJson(redisKeyHashes)),
  }
  const controlValue = {
    knownTables: postgres.knownTables,
    tables: postgres.controlTables,
    rows: postgres.controlRows,
  }
  const controlPlane = {
    ...controlValue,
    sha256: sha256(canonicalJson(controlValue)),
  }
  const authSessions = {
    rows: postgres.authSessionRows,
    sha256: sha256(canonicalJson(postgres.authSessionRows)),
  }
  const value = {
    schemaVersion: 2,
    protectedPostgres,
    mainRedis,
    authSessions,
    controlPlane,
  }
  return {
    ...value,
    digest: sha256(canonicalJson(value)),
  }
}

export async function captureMainDataFingerprint({
  runCommand,
  ownership = {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [],
    requestLogObservations: [],
  },
} = {}) {
  const canonicalOwnership = normalizeControlPlaneOwnership(ownership)
  const postgresSpec = {
    executable: 'docker',
    args: [
      'exec',
      '-i',
      MAIN_POSTGRES_CONTAINER,
      '/usr/bin/timeout',
      '--signal=TERM',
      '--kill-after=5s',
      '110s',
      'psql',
      '--no-psqlrc',
      '--set=ON_ERROR_STOP=1',
      '--tuples-only',
      '--no-align',
      '-U',
      MAIN_POSTGRES_USER,
      '-d',
      MAIN_POSTGRES_DATABASE,
    ],
    input: mainPostgresFingerprintSql(
      [...canonicalOwnership.requestLogRequestIds],
    ),
    label: 'main PostgreSQL read-only fingerprint',
    maxLineBytes: MAIN_POSTGRES_MAX_LINE_BYTES,
    maxStderrBytes: MAIN_POSTGRES_MAX_STDERR_BYTES,
    timeoutMs: 120_000,
  }
  const postgres = runCommand
    ? parsePostgresFingerprint(await runCommand(postgresSpec))
    : await runPostgresFingerprintCommand(postgresSpec)
  const commandRunner = runCommand ?? runFixedCommand
  const redisOutput = await commandRunner({
    executable: 'docker',
    args: [
      'exec',
      MAIN_REDIS_CONTAINER,
      'redis-cli',
      '--raw',
      '--scan',
    ],
    label: 'main Redis read-only key inventory',
  })
  return buildMainDataFingerprintFromParsed({ postgres, redisOutput })
}

export function assertMainDataFingerprintUnchanged(
  before,
  after,
  ownership = {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [],
    requestLogObservations: [],
  },
) {
  if (before?.schemaVersion !== 2 || after?.schemaVersion !== 2) {
    if (
      !isPlainObject(before)
      || !isPlainObject(after)
      || canonicalJson(before) !== canonicalJson(after)
    ) {
      throw new Error('Main data fingerprint changed during validation run')
    }
    return
  }

  assertSplitFingerprint(before)
  assertSplitFingerprint(after)
  const canonicalOwnership = normalizeControlPlaneOwnership(ownership)
  if (
    canonicalJson(before.protectedPostgres)
    !== canonicalJson(after.protectedPostgres)
  ) {
    throw new Error(
      'Protected PostgreSQL fingerprint changed during validation run',
    )
  }
  if (canonicalJson(before.mainRedis) !== canonicalJson(after.mainRedis)) {
    throw new Error('Main Redis fingerprint changed during validation run')
  }
  assertAuthSessionDeltaOwned(
    before.authSessions,
    after.authSessions,
    normalizeUniqueOwnershipUuids(ownership.authUserIds ?? []),
  )
  assertControlPlaneDeltaOwned(
    before.controlPlane,
    after.controlPlane,
    canonicalOwnership,
  )
}

export async function captureDockerStateFingerprint({
  runCommand = runFixedCommand,
} = {}) {
  const output = runCommand({
    executable: 'docker',
    args: [
      'container',
      'ls',
      '-a',
      '--no-trunc',
      '--format',
      '{{.ID}}\t{{.Image}}\t{{.State}}\t{{.CreatedAt}}',
    ],
    label: 'Docker state fingerprint',
  })
  const lines = output
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .sort()
  return {
    containerCount: lines.length,
    digest: sha256(canonicalJson(lines)),
  }
}

export async function runSupervisorInjectionProbes({
  token,
  sendRequest,
  captureDockerStateFingerprint:
    captureState = captureDockerStateFingerprint,
} = {}) {
  const fixedSendRequest = sendRequest
    ?? createSupervisorRequestSender({ token })
  const before = await captureState()
  const audit = []

  for (const probe of SUPERVISOR_INJECTION_PROBES) {
    const response = await fixedSendRequest(probe.payload)
    const code = response?.body?.error?.code
    if (
      response?.status !== 400
      || response?.body?.ok !== false
      || code !== probe.expectedCode
    ) {
      throw new Error(`Supervisor injection probe failed closed: ${probe.id}`)
    }
    audit.push({
      id: probe.id,
      requestSha256: sha256(canonicalJson(probe.payload)),
      status: response.status,
      code,
      rejected: true,
    })
  }

  const after = await captureState()
  if (canonicalJson(before) !== canonicalJson(after)) {
    throw new Error('Docker state changed during Supervisor injection probes')
  }
  return audit
}

export function scanDownloadedReport(report, {
  reportName = 'downloaded-report.json',
  secretValues = [],
} = {}) {
  const document = parseReportDocument(report)
  const secrets = normalizeSecretValues(secretValues)
  const violations = []
  const seen = new Set()

  walkReport(document, [], undefined, (value, path, parentKey) => {
    if (typeof value === 'string') {
      for (const secret of secrets) {
        if (value.includes(secret)) {
          addViolation('KNOWN_SECRET_VALUE', path)
          break
        }
      }
      if (BEARER_VALUE_PATTERN.test(value)) {
        addViolation('BEARER_CREDENTIAL', path)
      }
      if (
        normalizeFieldName(parentKey) === 'cookie'
        || normalizeFieldName(parentKey) === 'setcookie'
        || COOKIE_VALUE_PATTERN.test(value)
      ) {
        if (!SAFE_CREDENTIAL_VALUE_PATTERN.test(value.trim())) {
          addViolation('COOKIE_CREDENTIAL', path)
        }
      }
      if (
        isSensitiveField(parentKey)
        && !SAFE_CREDENTIAL_VALUE_PATTERN.test(value.trim())
      ) {
        addViolation('SENSITIVE_FIELD_VALUE', path)
      }
      return
    }
    if (
      value !== null
      && typeof value !== 'object'
      && isSensitiveField(parentKey)
      && value !== false
    ) {
      addViolation('SENSITIVE_FIELD_VALUE', path)
    }
  })

  return {
    reportName,
    scanned: true,
    violations,
  }

  function addViolation(code, path) {
    const location = sha256(canonicalJson(path)).slice(0, 16)
    const identity = `${code}:${location}`
    if (seen.has(identity)) return
    seen.add(identity)
    violations.push({ code, location })
  }
}

export function scanDownloadedReports({
  reports = [],
  reportPaths = [],
  secretValues = readIsolationSecretValues(),
} = {}) {
  if (!Array.isArray(reports) || !Array.isArray(reportPaths)) {
    throw new Error('Trading Lab downloaded report inputs are invalid')
  }
  const scans = []
  for (const [index, report] of reports.entries()) {
    const wrapped = isPlainObject(report) && Object.hasOwn(report, 'document')
    scans.push(scanDownloadedReport(
      wrapped ? report.document : report,
      {
        reportName:
          wrapped && typeof report.name === 'string'
            ? report.name
            : `downloaded-report-${index + 1}.json`,
        secretValues,
      },
    ))
  }
  for (const path of reportPaths) {
    if (typeof path !== 'string' || path.length === 0) {
      throw new Error('Trading Lab downloaded report path is invalid')
    }
    scans.push(scanDownloadedReport(
      readFileSync(path),
      {
        reportName: path,
        secretValues,
      },
    ))
  }
  return scans
}

export function createTradingLabRuntimeIsolationProbe({
  supervisorToken = process.env.SUPERVISOR_INTERNAL_TOKEN,
  secretValues = readIsolationSecretValues(),
  collectValidationIdentity = collectValidationRuntimeIdentity,
  runNetworkProbes = runValidationNetworkProbes,
  runInjectionProbes = () =>
    runSupervisorInjectionProbes({ token: supervisorToken }),
  captureMainFingerprint = captureMainDataFingerprint,
  scanReports = (inputs) =>
    scanDownloadedReports({ ...inputs, secretValues }),
} = {}) {
  let beforeState
  let afterCompleted = false

  return {
    async before() {
      if (beforeState) {
        throw new Error('Trading Lab runtime isolation before probe already completed')
      }
      const validationIdentity = await collectValidationIdentity()
      assertValidationRuntimeIdentity(validationIdentity)
      const network = await runNetworkProbes()
      const injections = await runInjectionProbes()
      const mainFingerprint = await captureMainFingerprint()
      beforeState = {
        validationIdentity,
        mainFingerprint,
      }
      return {
        passed: true,
        validationIdentitySha256:
          sha256(canonicalJson(validationIdentity)),
        mainFingerprintSha256:
          sha256(canonicalJson(mainFingerprint)),
        network,
        injections,
      }
    },

    async after({
      reports = [],
      reportPaths = [],
      ownership = {
        createdRuns: [],
        referencedRunIds: [],
        tradingLabRequestIds: [],
        requestLogObservations: [],
      },
    } = {}) {
      if (!beforeState) {
        throw new Error('Trading Lab runtime isolation before probe is required')
      }
      if (afterCompleted) {
        throw new Error('Trading Lab runtime isolation after probe already completed')
      }
      afterCompleted = true

      const mainFingerprint = await captureMainFingerprint({ ownership })
      assertMainDataFingerprintUnchanged(
        beforeState.mainFingerprint,
        mainFingerprint,
        ownership,
      )
      const validationIdentity = await collectValidationIdentity()
      assertValidationRuntimeIdentity(validationIdentity)
      if (
        canonicalJson(beforeState.validationIdentity)
        !== canonicalJson(validationIdentity)
      ) {
        throw new Error('Validation runtime identity changed during validation run')
      }
      if (reports.length + reportPaths.length === 0) {
        throw new Error('Trading Lab downloaded report evidence is required')
      }
      const reportScans = await scanReports({ reports, reportPaths })
      const reportViolations = reportScans.flatMap(
        ({ violations = [] }) => violations,
      )
      if (reportViolations.length > 0) {
        const codes = [...new Set(
          reportViolations.map(({ code }) => code),
        )].sort()
        throw new Error(
          `Trading Lab downloaded report credential scan failed: ${codes.join(',')}`,
        )
      }
      return {
        passed: true,
        validationIdentitySha256:
          sha256(canonicalJson(validationIdentity)),
        mainFingerprintSha256:
          sha256(canonicalJson(mainFingerprint)),
        reportScans,
      }
    },
  }
}

export async function verifyTradingLabRuntimeIsolation({
  runValidation,
  reports,
  reportPaths,
  ownership,
  ...probeOptions
} = {}) {
  if (typeof runValidation !== 'function') {
    throw new Error('Trading Lab validation callback is required')
  }
  const probe = createTradingLabRuntimeIsolationProbe(probeOptions)
  const preflight = await probe.before()
  let runResult
  let primaryFailure
  try {
    runResult = await runValidation()
  } catch (failure) {
    primaryFailure = failure
  }

  let isolation
  let postflightFailure
  try {
    isolation = await probe.after({
      reports: reports ?? runResult?.reports ?? [],
      reportPaths: reportPaths ?? runResult?.reportPaths ?? [],
      ownership: ownership ?? runResult?.ownership ?? {
        createdRuns: [],
        referencedRunIds: [],
        tradingLabRequestIds: [],
        requestLogObservations: [],
      },
    })
  } catch (failure) {
    postflightFailure = failure
  }

  if (primaryFailure && postflightFailure) {
    throw new AggregateError(
      [primaryFailure, postflightFailure],
      'Trading Lab validation and runtime-isolation postflight both failed',
    )
  }
  if (primaryFailure) throw primaryFailure
  if (postflightFailure) throw postflightFailure
  return {
    runResult,
    isolation: {
      ...isolation,
      preflight,
    },
  }
}

export function readIsolationSecretValues(environment = process.env) {
  return REPORT_SECRET_ENV_NAMES
    .map((name) => environment[name])
    .filter((value) => typeof value === 'string' && value.length > 0)
}

function fixedProbe(id, host, port, expectedReachable) {
  return Object.freeze({ id, host, port, expectedReachable })
}

function fixedInjectionProbe(id, payload, expectedCode) {
  return Object.freeze({ id, payload, expectedCode })
}

export function runPostgresFingerprintCommand({
  executable,
  args,
  input,
  label = 'main PostgreSQL read-only fingerprint',
  maxLineBytes = MAIN_POSTGRES_MAX_LINE_BYTES,
  maxStderrBytes = MAIN_POSTGRES_MAX_STDERR_BYTES,
  timeoutMs = 120_000,
}) {
  if (
    typeof executable !== 'string'
    || executable.length === 0
    || !Array.isArray(args)
    || args.some((value) => typeof value !== 'string')
    || (input !== undefined && typeof input !== 'string')
    || typeof label !== 'string'
    || label.length === 0
    || !Number.isSafeInteger(maxLineBytes)
    || maxLineBytes < 1
    || !Number.isSafeInteger(maxStderrBytes)
    || maxStderrBytes < 1
    || !Number.isSafeInteger(timeoutMs)
    || timeoutMs < 1
  ) {
    return Promise.reject(
      new Error('PostgreSQL fingerprint command specification is invalid'),
    )
  }

  const parser = createPostgresFingerprintParser()
  return new Promise((resolvePromise, rejectPromise) => {
    let child
    let timeout
    let forceKillTimeout
    let terminationRequested = false
    let failureReason = null
    let failureCode = null
    let stdoutBytes = 0
    let stderrBytes = 0
    let lineBytes = 0
    let lineChunks = []

    const stopChild = () => {
      if (
        terminationRequested
        || !child
        || child.exitCode !== null
        || child.signalCode !== null
      ) {
        return
      }
      terminationRequested = true
      child.kill()
      forceKillTimeout = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
      }, 1_000)
      forceKillTimeout.unref()
    }

    const fail = (reason, code, { terminate = false } = {}) => {
      if (failureReason !== null) {
        if (terminate) stopChild()
        return
      }
      failureReason = reason
      failureCode = code
      if (terminate) {
        stopChild()
      } else {
        lineBytes = 0
        lineChunks = []
      }
    }

    const appendLineChunk = (chunk) => {
      if (chunk.length === 0) return
      lineBytes += chunk.length
      if (lineBytes > maxLineBytes) {
        fail(
          'PostgreSQL fingerprint line limit exceeded',
          'OUTPUT_LINE_LIMIT',
        )
        return
      }
      lineChunks.push(Buffer.from(chunk))
    }

    const flushLine = () => {
      if (failureReason !== null) return
      const line = lineBytes === 0
        ? ''
        : Buffer.concat(lineChunks, lineBytes).toString('utf8')
      lineBytes = 0
      lineChunks = []
      try {
        parser.push(line)
      } catch (error) {
        fail(error.message, 'OUTPUT_INVALID')
      }
    }

    try {
      child = spawn(executable, [...args], {
        shell: false,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
      })
    } catch (error) {
      rejectPromise(postgresCommandFailure({
        label,
        reason: 'child process could not start',
        code: error?.code ?? 'SPAWN_FAILED',
        status: null,
        signal: null,
        stdoutBytes,
        stderrBytes,
        pid: null,
      }))
      return
    }

    child.on('error', (error) => {
      fail(
        'child process could not start',
        error?.code ?? 'SPAWN_FAILED',
      )
    })
    child.stdout.on('error', (error) => {
      fail(
        'PostgreSQL fingerprint stdout failed',
        error?.code ?? 'STDOUT_FAILED',
        { terminate: true },
      )
    })
    child.stdout.on('data', (chunk) => {
      stdoutBytes += chunk.length
      if (failureReason !== null) return
      let offset = 0
      while (offset < chunk.length && failureReason === null) {
        const newline = chunk.indexOf(0x0a, offset)
        if (newline < 0) {
          appendLineChunk(chunk.subarray(offset))
          break
        }
        appendLineChunk(chunk.subarray(offset, newline))
        flushLine()
        offset = newline + 1
      }
    })
    child.stderr.on('data', (chunk) => {
      stderrBytes += chunk.length
      if (stderrBytes > maxStderrBytes) {
        fail(
          'PostgreSQL fingerprint stderr limit exceeded',
          'STDERR_LIMIT',
        )
      }
    })
    child.stderr.on('error', (error) => {
      fail(
        'PostgreSQL fingerprint stderr failed',
        error?.code ?? 'STDERR_FAILED',
        { terminate: true },
      )
    })
    child.stdin.on('error', (error) => {
      fail(
        'PostgreSQL fingerprint stdin failed',
        error?.code ?? 'STDIN_FAILED',
        { terminate: true },
      )
    })
    child.on('close', (status, signal) => {
      clearTimeout(timeout)
      clearTimeout(forceKillTimeout)
      if (failureReason === null && lineBytes > 0) {
        failureReason = 'PostgreSQL fingerprint output has a truncated final line'
        failureCode = 'OUTPUT_TRUNCATED'
      }
      if (failureReason === null && status !== 0) {
        failureReason = 'child process exited non-zero'
        failureCode = 'CHILD_EXIT'
      }
      let fingerprint
      if (failureReason === null) {
        try {
          fingerprint = parser.finish()
        } catch (error) {
          failureReason = error.message
          failureCode = 'OUTPUT_INVALID'
        }
      }
      if (failureReason !== null) {
        rejectPromise(postgresCommandFailure({
          label,
          reason: failureReason,
          code: failureCode,
          status,
          signal,
          stdoutBytes,
          stderrBytes,
          pid: child.pid,
        }))
        return
      }
      resolvePromise(fingerprint)
    })

    timeout = setTimeout(() => {
      fail('command timed out', 'ETIMEDOUT', { terminate: true })
    }, timeoutMs)
    timeout.unref()
    try {
      child.stdin.end(input ?? '')
    } catch (error) {
      fail(
        'PostgreSQL fingerprint stdin failed',
        error?.code ?? 'STDIN_FAILED',
        { terminate: true },
      )
    }
  })
}

function postgresCommandFailure({
  label,
  reason,
  code,
  status,
  signal,
  stdoutBytes,
  stderrBytes,
  pid,
}) {
  const error = new Error(
    `${label} failed: ${reason}; `
    + `code=${code ?? 'null'} status=${status ?? 'null'} `
    + `signal=${signal ?? 'null'} stdoutBytes=${stdoutBytes} `
    + `stderrBytes=${stderrBytes}`,
  )
  error.code = code ?? 'POSTGRES_FINGERPRINT_FAILED'
  error.pid = pid
  return error
}

function runFixedCommand({
  executable,
  args,
  input,
  label = 'fixed isolation command',
  maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES,
  timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
}) {
  const result = spawnSync(executable, [...args], {
    encoding: 'utf8',
    input,
    maxBuffer: maxOutputBytes,
    shell: false,
    windowsHide: true,
    timeout: timeoutMs,
  })
  if (result.error || result.status !== 0) {
    throw new Error(`${label} failed`)
  }
  return result.stdout
}

function runTcpProbe(spec) {
  const result = spawnSync(spec.executable, [...spec.args], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024,
    shell: spec.options.shell,
    windowsHide: spec.options.windowsHide,
    stdio: spec.options.stdio,
    timeout: NETWORK_COMMAND_TIMEOUT_MS,
  })
  if (
    result.error
    || ![0, 1, 137].includes(result.status)
  ) {
    throw new Error('Validation TCP probe command failed')
  }
  return result.status === 0
}

function parseSingleDockerInspect(output) {
  let value
  try {
    value = JSON.parse(output)
  } catch {
    throw new Error('Validation runtime isolation identity failed')
  }
  if (!Array.isArray(value) || value.length !== 1 || !isPlainObject(value[0])) {
    throw new Error('Validation runtime isolation identity failed')
  }
  return value[0]
}

function parseDockerEnvironment(entries) {
  if (!Array.isArray(entries)) {
    throw new Error('Validation runtime isolation identity failed')
  }
  const environment = new Map()
  for (const entry of entries) {
    if (typeof entry !== 'string') {
      throw new Error('Validation runtime isolation identity failed')
    }
    const separator = entry.indexOf('=')
    if (separator < 1) {
      throw new Error('Validation runtime isolation identity failed')
    }
    const name = entry.slice(0, separator)
    if (environment.has(name)) {
      throw new Error('Validation runtime isolation identity failed')
    }
    environment.set(name, entry.slice(separator + 1))
  }
  return environment
}

function parsePostgresFingerprint(output) {
  if (typeof output !== 'string') {
    throw new Error('Main PostgreSQL fingerprint output is invalid')
  }
  const parser = createPostgresFingerprintParser()
  for (const rawLine of output.split(/\r?\n/u)) {
    parser.push(rawLine)
  }
  return parser.finish()
}

function createPostgresFingerprintParser() {
  const protectedTables = new Map()
  const controlCounts = new Map()
  const controlRows = []
  const authSessionRows = []
  let authSessionCount = null
  const legacyCounts = new Map()
  const legacyChecksums = new Map()
  let splitLines = 0
  let legacyLines = 0

  const push = (rawLine) => {
    if (typeof rawLine !== 'string') {
      throw new Error('Main PostgreSQL fingerprint output is invalid')
    }
    for (const candidateLine of [rawLine]) {
      const line = candidateLine.trim()
      if (!line || line === 'BEGIN' || line === 'COMMIT') continue

      const protectedMatch =
        /^PROTECTED:([0-9a-f]+)\t(\d+)\t([0-9a-f]{32})$/u.exec(line)
      if (protectedMatch) {
        splitLines += 1
        const table = decodeTableKey(protectedMatch[1])
        if (
          table.schema === 'trading_lab'
          || protectedTables.has(table.key)
        ) {
          throw new Error('Main PostgreSQL protected fingerprint is invalid')
        }
        protectedTables.set(table.key, {
          schema: table.schema,
          table: table.table,
          count: protectedMatch[2],
          checksum: protectedMatch[3],
        })
        continue
      }

      const controlTableMatch =
        /^CONTROL_TABLE:([0-9a-f]+)\t(\d+)$/u.exec(line)
      if (controlTableMatch) {
        splitLines += 1
        const table = decodeTableKey(controlTableMatch[1])
        assertKnownControlTable(table.key)
        if (controlCounts.has(table.key)) {
          throw new Error('Main PostgreSQL control-plane fingerprint is invalid')
        }
        controlCounts.set(table.key, controlTableMatch[2])
        continue
      }

      const controlRowMatch =
        /^CONTROL_ROW:([0-9a-f]+)\t([0-9a-f]+)$/u.exec(line)
      if (controlRowMatch) {
        splitLines += 1
        const table = decodeTableKey(controlRowMatch[1])
        assertKnownControlTable(table.key)
        const payload = decodeHexJson(controlRowMatch[2])
        controlRows.push(normalizeControlRow(table, payload))
        continue
      }

      const authSessionCountMatch = /^AUTH_SESSION_COUNT\t(\d+)$/u.exec(line)
      if (authSessionCountMatch) {
        splitLines += 1
        if (authSessionCount !== null) {
          throw new Error('Main PostgreSQL auth session fingerprint is invalid')
        }
        authSessionCount = authSessionCountMatch[1]
        continue
      }

      const authSessionMatch = /^AUTH_SESSION\t([0-9a-f]+)$/u.exec(line)
      if (authSessionMatch) {
        splitLines += 1
        authSessionRows.push(normalizeAuthSessionRow(
          decodeHexJson(authSessionMatch[1]),
        ))
        continue
      }

      const legacyMatch =
        /^(COUNT|CHECKSUM):([0-9a-f]+)\t([0-9a-f]+)$/u.exec(line)
      if (!legacyMatch || legacyMatch[2].length % 2 !== 0) {
        throw new Error('Main PostgreSQL fingerprint output is invalid')
      }
      legacyLines += 1
      const table = decodeTableKey(legacyMatch[2])
      const collection = legacyMatch[1] === 'COUNT'
        ? legacyCounts
        : legacyChecksums
      if (
        collection.has(table.key)
        || (legacyMatch[1] === 'COUNT'
          && !/^\d+$/u.test(legacyMatch[3]))
        || (legacyMatch[1] === 'CHECKSUM'
          && !MD5_PATTERN.test(legacyMatch[3]))
      ) {
        throw new Error('Main PostgreSQL fingerprint output is invalid')
      }
      collection.set(table.key, {
        schema: table.schema,
        table: table.table,
        value: legacyMatch[3],
      })
    }
  }

  const finish = () => {
    if (splitLines > 0 && legacyLines > 0) {
      throw new Error('Main PostgreSQL fingerprint output mixes schema versions')
    }
    if (splitLines === 0 && legacyLines === 0) {
      throw new Error('Main PostgreSQL fingerprint output is empty')
    }

    if (legacyLines > 0) {
      const tables = []
      for (const [key, count] of legacyCounts) {
        const checksum = legacyChecksums.get(key)
        if (
          !checksum
          || key.startsWith('trading_lab.')
          || key === 'audit.audit_logs'
        ) {
          throw new Error('Legacy main PostgreSQL fingerprint is incomplete')
        }
        tables.push({
          schema: count.schema,
          table: count.table,
          count: count.value,
          checksum: checksum.value,
        })
      }
      if (
        tables.length === 0
        || legacyChecksums.size !== legacyCounts.size
      ) {
        throw new Error('Legacy main PostgreSQL fingerprint is incomplete')
      }
      return {
        protectedTables: tables.sort(tableEntryOrder),
        knownTables: knownControlTables(),
        controlTables: knownControlTables().map((table) => ({
          ...table,
          count: '0',
        })),
        controlRows: [],
        authSessionRows: [],
      }
    }

    const knownTables = knownControlTables()
    if (
      protectedTables.size === 0
      || authSessionCount === null
      || controlCounts.size !== knownTables.length
      || knownTables.some(({ schema, table }) =>
        !controlCounts.has(`${schema}.${table}`))
    ) {
      throw new Error('Main PostgreSQL control-plane fingerprint is incomplete')
    }
    const identities = new Set()
    for (const row of controlRows) {
      const identity = `${row.schema}.${row.table}:${row.identitySha256}`
      if (identities.has(identity)) {
        throw new Error('Main PostgreSQL control-plane UUID is duplicate')
      }
      identities.add(identity)
    }
    const rowsByTable = new Map()
    for (const row of controlRows) {
      const key = `${row.schema}.${row.table}`
      rowsByTable.set(key, (rowsByTable.get(key) ?? 0) + 1)
    }
    for (const [key, count] of controlCounts) {
      if (String(rowsByTable.get(key) ?? 0) !== count) {
        throw new Error('Main PostgreSQL control-plane row count is invalid')
      }
    }
    if (String(authSessionRows.length) !== authSessionCount) {
      throw new Error('Main PostgreSQL auth session row count is invalid')
    }
    const authSessionIdentities = new Set()
    for (const row of authSessionRows) {
      if (authSessionIdentities.has(row.identitySha256)) {
        throw new Error('Main PostgreSQL auth session UUID is duplicate')
      }
      authSessionIdentities.add(row.identitySha256)
    }

    return {
      protectedTables: [...protectedTables.values()].sort(tableEntryOrder),
      knownTables,
      controlTables: knownTables.map((table) => ({
        ...table,
        count: controlCounts.get(`${table.schema}.${table.table}`),
      })),
      controlRows: controlRows.sort(controlRowOrder),
      authSessionRows: authSessionRows.sort((left, right) =>
        left.identitySha256.localeCompare(right.identitySha256)),
    }
  }

  return Object.freeze({ push, finish })
}

function parseRedisKeys(output) {
  if (typeof output !== 'string') {
    throw new Error('Main Redis key inventory output is invalid')
  }
  const keys = output
    .split(/\r?\n/u)
    .filter((key) => key.length > 0)
    .sort()
  if (new Set(keys).size !== keys.length) {
    throw new Error('Main Redis key inventory output is invalid')
  }
  return keys
}

function mainPostgresFingerprintSql(ownedRequestLogIds = []) {
  const auditTableHex = hexText('audit.audit_logs')
  const requestLogTableHex = hexText('audit.request_logs')
  const ownedRequestLogArraySql = (
    ownedRequestLogIds.length === 0
      ? 'ARRAY[]::text[]'
      : `ARRAY[${ownedRequestLogIds
        .map((requestId) => `'${requestId}'`)
        .join(', ')}]::text[]`
  )
  const ownedRequestLogPredicate =
    `source_row.request_id = ANY(${ownedRequestLogArraySql})`
  const controlRowsSql = [
    controlRowSelect(
      'audit.request_logs',
      `jsonb_build_object(
        'id', source_row.id::text,
        'requestId', source_row.request_id,
        'method', source_row.method,
        'path', source_row.path,
        'queryStringPresent', source_row.query_string IS NOT NULL,
        'statusCode', source_row.status_code,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
      `WHERE ${ownedRequestLogPredicate}`,
    ),
    controlRowSelect(
      'trading_lab.scenarios',
      `jsonb_build_object(
        'id', source_row.id::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.reports',
      `jsonb_build_object(
        'id', source_row.id::text,
        'scenarioId', source_row.scenario_id::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.runs',
      `jsonb_build_object(
        'id', source_row.id::text,
        'scenarioId', source_row.scenario_id::text,
        'reportId', source_row.report_id::text,
        'state', source_row.state,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.run_transitions',
      `jsonb_build_object(
        'id', source_row.id::text,
        'runId', source_row.run_id::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.run_events',
      `jsonb_build_object(
        'id', source_row.id::text,
        'runId', source_row.run_id::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.report_chunks',
      `jsonb_build_object(
        'id', source_row.id::text,
        'reportId', source_row.report_id::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.report_appends',
      `jsonb_build_object(
        'reportId', source_row.report_id::text,
        'section', source_row.section,
        'sourceSequence', source_row.source_sequence::text,
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'trading_lab.audit_events',
      `jsonb_build_object(
        'id', source_row.id::text,
        'requestId', source_row.request_id::text,
        'scenarioId', source_row.scenario_id::text,
        'runId', source_row.run_id::text,
        'actorId', source_row.actor_id::text,
        'action', source_row.action,
        'result', source_row.result,
        'targetType', CASE
          WHEN source_row.run_id IS NOT NULL THEN 'TRADING_LAB_RUN'
          WHEN source_row.scenario_id IS NOT NULL THEN 'TRADING_LAB_SCENARIO'
          ELSE 'TRADING_LAB_REQUEST'
        END,
        'targetId', COALESCE(
          source_row.run_id,
          source_row.scenario_id,
          source_row.request_id
        )::text,
        'clientIp', source_row.client_ip,
        'detailsMd5', md5(source_row.details_json::text),
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
    ),
    controlRowSelect(
      'audit.audit_logs',
      `jsonb_build_object(
        'id', source_row.id::text,
        'requestId', source_row.request_id,
        'scenarioId', source_row.details ->> 'scenarioId',
        'runId', source_row.details ->> 'runId',
        'actorId', source_row.actor_user_id::text,
        'action', source_row.action,
        'result', source_row.details ->> 'result',
        'targetType', source_row.target_type,
        'targetId', source_row.target_id,
        'clientIp', source_row.details ->> 'clientIp',
        'detailsMd5', md5(COALESCE(
          (source_row.details -> 'details')::text,
          'null'
        )),
        'rowMd5', md5(row_to_json(source_row)::text)
      )`,
      `WHERE left(source_row.action, 12) = 'TRADING_LAB_'`,
    ),
  ].join('\n\n')

  return String.raw`
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT format(
  'SELECT %L || E''\t'' || count(*)::text || E''\t'' || md5(COALESCE(string_agg(row_hash, '''' ORDER BY row_hash), '''')) FROM (SELECT md5(row_to_json(source_row)::text) AS row_hash FROM %I.%I AS source_row) AS hashed_rows;',
  'PROTECTED:' || encode(convert_to(schemaname || '.' || tablename, 'UTF8'), 'hex'),
  schemaname,
  tablename
)
FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
  AND schemaname <> 'trading_lab'
  AND NOT (
    schemaname = 'audit'
    AND tablename IN ('audit_logs', 'request_logs')
  )
  AND NOT (schemaname = 'auth' AND tablename = 'user_sessions')
ORDER BY schemaname, tablename
\gexec

SELECT 'PROTECTED:${auditTableHex}'
  || E'\t'
  || count(*)::text
  || E'\t'
  || md5(COALESCE(string_agg(row_hash, '' ORDER BY row_hash), ''))
FROM (
  SELECT md5(row_to_json(source_row)::text) AS row_hash
  FROM audit.audit_logs AS source_row
  WHERE left(source_row.action, 12) <> 'TRADING_LAB_'
) AS protected_audit_rows;

SELECT 'PROTECTED:${requestLogTableHex}'
  || E'\t'
  || count(*)::text
  || E'\t'
  || md5(COALESCE(string_agg(row_hash, '' ORDER BY row_hash), ''))
FROM (
  SELECT md5(row_to_json(source_row)::text) AS row_hash
  FROM audit.request_logs AS source_row
  WHERE NOT COALESCE(${ownedRequestLogPredicate}, FALSE)
) AS protected_request_log_rows;

SELECT format(
  'SELECT %L || E''\t'' || count(*)::text FROM %I.%I;',
  'CONTROL_TABLE:' || encode(convert_to(schemaname || '.' || tablename, 'UTF8'), 'hex'),
  schemaname,
  tablename
)
FROM pg_tables
WHERE schemaname = 'trading_lab'
ORDER BY tablename
\gexec

SELECT 'CONTROL_TABLE:${auditTableHex}'
  || E'\t'
  || count(*)::text
FROM audit.audit_logs
WHERE left(action, 12) = 'TRADING_LAB_';

SELECT 'CONTROL_TABLE:${requestLogTableHex}'
  || E'\t'
  || count(*)::text
FROM audit.request_logs AS source_row
WHERE ${ownedRequestLogPredicate};

SELECT 'AUTH_SESSION_COUNT'
  || E'\t'
  || count(*)::text
FROM auth.user_sessions;

SELECT 'AUTH_SESSION'
  || E'\t'
  || encode(convert_to((jsonb_build_object(
    'id', source_row.id::text,
    'userId', source_row.user_id::text,
    'rowMd5', md5(row_to_json(source_row)::text)
  ))::text, 'UTF8'), 'hex')
FROM auth.user_sessions AS source_row;

${controlRowsSql}

COMMIT;
`
}

function controlRowSelect(tableKey, payloadSql, whereSql = '') {
  return `SELECT 'CONTROL_ROW:${hexText(tableKey)}'
  || E'\\t'
  || encode(convert_to((${payloadSql})::text, 'UTF8'), 'hex')
FROM ${tableKey} AS source_row
${whereSql};`
}

function normalizeControlRow(table, payload) {
  const base = (identity, rowMd5) => ({
    schema: table.schema,
    table: table.table,
    identitySha256: sha256(identity),
    rowSha256: sha256(requireMd5(rowMd5)),
  })
  switch (table.key) {
    case 'trading_lab.scenarios': {
      assertExactKeys(payload, ['id', 'rowMd5'])
      const id = requireCanonicalUuid(payload.id)
      return base(id, payload.rowMd5)
    }
    case 'trading_lab.reports': {
      assertExactKeys(payload, ['id', 'rowMd5', 'scenarioId'])
      const id = requireCanonicalUuid(payload.id)
      const scenarioId = requireNullableCanonicalUuid(payload.scenarioId)
      return {
        ...base(id, payload.rowMd5),
        scenarioIdSha256: hashNullable(scenarioId),
      }
    }
    case 'trading_lab.runs': {
      assertExactKeys(
        payload,
        ['id', 'reportId', 'rowMd5', 'scenarioId', 'state'],
      )
      const id = requireCanonicalUuid(payload.id)
      const scenarioId = requireCanonicalUuid(payload.scenarioId)
      const reportId = requireNullableCanonicalUuid(payload.reportId)
      const state = requireNonEmptyString(payload.state)
      return {
        ...base(id, payload.rowMd5),
        scenarioIdSha256: sha256(scenarioId),
        reportIdSha256: hashNullable(reportId),
        stateSha256: sha256(state),
      }
    }
    case 'trading_lab.run_transitions':
    case 'trading_lab.run_events': {
      assertExactKeys(payload, ['id', 'rowMd5', 'runId'])
      const id = requireCanonicalUuid(payload.id)
      const runId = requireCanonicalUuid(payload.runId)
      return {
        ...base(id, payload.rowMd5),
        runIdSha256: sha256(runId),
      }
    }
    case 'trading_lab.report_chunks': {
      assertExactKeys(payload, ['id', 'reportId', 'rowMd5'])
      const id = requireCanonicalUuid(payload.id)
      const reportId = requireCanonicalUuid(payload.reportId)
      return {
        ...base(id, payload.rowMd5),
        reportIdSha256: sha256(reportId),
      }
    }
    case 'trading_lab.report_appends': {
      assertExactKeys(
        payload,
        ['reportId', 'rowMd5', 'section', 'sourceSequence'],
      )
      const reportId = requireCanonicalUuid(payload.reportId)
      const section = requireNonEmptyString(payload.section)
      const sourceSequence = requireIntegerString(payload.sourceSequence)
      return {
        ...base(
          `${reportId}:${section}:${sourceSequence}`,
          payload.rowMd5,
        ),
        reportIdSha256: sha256(reportId),
      }
    }
    case 'audit.request_logs':
      return normalizeRequestLogControlRow(table, payload, base)
    case 'trading_lab.audit_events':
    case 'audit.audit_logs':
      return normalizeAuditControlRow(table, payload, base)
    default:
      throw new Error(`Unknown Trading Lab table in fingerprint: ${table.key}`)
  }
}

function normalizeRequestLogControlRow(table, payload, base) {
  assertExactKeys(payload, [
    'id',
    'method',
    'path',
    'queryStringPresent',
    'requestId',
    'rowMd5',
    'statusCode',
  ])
  const id = requireCanonicalUuid(payload.id)
  const requestId = requireCanonicalUuid(payload.requestId)
  const method = requireNonEmptyString(payload.method)
  const path = requireNonEmptyString(payload.path)
  if (payload.queryStringPresent !== false) {
    throw new Error('Trading Lab request log query data is not allowed')
  }
  const statusCode = requireHttpStatus(payload.statusCode)
  const route = classifyRequestLogRoute(method, path, statusCode)
  return {
    ...base(id, payload.rowMd5),
    requestIdSha256: sha256(requestId),
    methodSha256: sha256(method),
    pathSha256: sha256(path),
    requestTupleSha256: sha256(canonicalJson({
      method,
      path,
      statusCode,
    })),
    routeSha256: sha256(route.name),
    routeStatusSha256: sha256(`${route.name}:${statusCode}`),
    resourceIdSha256: hashNullable(route.resourceId),
    statusCodeSha256: sha256(String(statusCode)),
  }
}

function classifyRequestLogRoute(method, path, statusCode) {
  const staticRoutes = [
    ['POST', '/api/auth/login', REQUEST_LOG_ROUTES.AUTH_LOGIN],
    [
      'GET',
      '/api/admin/dashboard/summary',
      REQUEST_LOG_ROUTES.DASHBOARD_SUMMARY,
    ],
    ['GET', '/api/admin/trading-lab/config', REQUEST_LOG_ROUTES.CONFIG],
    [
      'GET',
      '/api/admin/trading-lab/environment',
      REQUEST_LOG_ROUTES.ENVIRONMENT_STATUS,
    ],
    [
      'POST',
      '/api/admin/trading-lab/scenarios',
      REQUEST_LOG_ROUTES.SCENARIO_CREATE,
    ],
  ]
  const staticRoute = staticRoutes.find(
    ([expectedMethod, expectedPath]) => (
      method === expectedMethod && path === expectedPath
    ),
  )
  if (staticRoute !== undefined) {
    return { name: staticRoute[2], resourceId: null }
  }
  if (
    method === 'POST'
    && /^\/api\/admin\/trading-lab\/environment\/(?:start|stop|restart)$/u
      .test(path)
  ) {
    return { name: REQUEST_LOG_ROUTES.ENVIRONMENT_CONTROL, resourceId: null }
  }

  const uuid =
    '([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})'
  const dynamicRoutes = [
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/scenarios/${uuid}$`, 'u'),
      REQUEST_LOG_ROUTES.SCENARIO_GET,
    ],
    [
      'POST',
      new RegExp(`^/api/admin/trading-lab/scenarios/${uuid}/runs$`, 'u'),
      REQUEST_LOG_ROUTES.RUN_CREATE,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/runs/${uuid}$`, 'u'),
      REQUEST_LOG_ROUTES.RUN_GET,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/runs/${uuid}/events$`, 'u'),
      REQUEST_LOG_ROUTES.RUN_EVENTS,
    ],
    [
      'POST',
      new RegExp(
        `^/api/admin/trading-lab/runs/${uuid}/(?:pause|resume|cancel)$`,
        'u',
      ),
      REQUEST_LOG_ROUTES.RUN_CONTROL,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_GET,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}/download$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_DOWNLOAD,
    ],
    [
      'DELETE',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_DELETE,
    ],
    [
      'POST',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}/permanent$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_PERMANENT,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}/print-info$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_PRINT_INFO,
    ],
    [
      'POST',
      new RegExp(
        `^/api/admin/trading-lab/reports/${uuid}/print-confirmation$`,
        'u',
      ),
      REQUEST_LOG_ROUTES.REPORT_PRINT_CONFIRMATION,
    ],
    [
      'GET',
      new RegExp(`^/api/admin/trading-lab/reports/${uuid}/print$`, 'u'),
      REQUEST_LOG_ROUTES.REPORT_PRINT,
    ],
  ]
  for (const [expectedMethod, pattern, name] of dynamicRoutes) {
    if (method !== expectedMethod) continue
    const match = pattern.exec(path)
    if (match !== null) {
      return { name, resourceId: requireCanonicalUuid(match[1]) }
    }
  }
  if (method === 'OPTIONS') {
    if (statusCode !== 200) {
      throw new Error('Trading Lab CORS preflight status is invalid')
    }
    const staticPreflightPaths = new Set([
      '/api/auth/login',
      '/api/admin/dashboard/summary',
      '/api/admin/trading-lab/config',
      '/api/admin/trading-lab/environment',
      '/api/admin/trading-lab/environment/start',
      '/api/admin/trading-lab/environment/stop',
      '/api/admin/trading-lab/environment/restart',
      '/api/admin/trading-lab/scenarios',
    ])
    if (staticPreflightPaths.has(path)) {
      return {
        name: REQUEST_LOG_ROUTES.PREFLIGHT_STATIC,
        resourceId: null,
      }
    }
    const dynamicPreflightRoutes = [
      [
        new RegExp(
          `^/api/admin/trading-lab/scenarios/${uuid}(?:/runs)?$`,
          'u',
        ),
        REQUEST_LOG_ROUTES.PREFLIGHT_SCENARIO,
      ],
      [
        new RegExp(
          `^/api/admin/trading-lab/runs/${uuid}`
            + '(?:/events|/(?:pause|resume|cancel))?$',
          'u',
        ),
        REQUEST_LOG_ROUTES.PREFLIGHT_RUN,
      ],
      [
        new RegExp(
          `^/api/admin/trading-lab/reports/${uuid}`
            + '(?:/download|/permanent|/print-info'
            + '|/print-confirmation|/print)?$',
          'u',
        ),
        REQUEST_LOG_ROUTES.PREFLIGHT_REPORT,
      ],
    ]
    for (const [pattern, name] of dynamicPreflightRoutes) {
      const match = pattern.exec(path)
      if (match !== null) {
        return {
          name,
          resourceId: requireCanonicalUuid(match[1]),
        }
      }
    }
  }
  throw new Error('Trading Lab request log method/path route is invalid')
}

function normalizeAuthSessionRow(payload) {
  assertExactKeys(payload, ['id', 'rowMd5', 'userId'])
  const id = requireCanonicalUuid(payload.id)
  const userId = requireCanonicalUuid(payload.userId)
  return {
    identitySha256: sha256(id),
    userIdSha256: sha256(userId),
    rowSha256: sha256(requireMd5(payload.rowMd5)),
  }
}

function normalizeAuditControlRow(table, payload, base) {
  assertExactKeys(payload, [
    'action',
    'actorId',
    'clientIp',
    'detailsMd5',
    'id',
    'requestId',
    'result',
    'rowMd5',
    'runId',
    'scenarioId',
    'targetId',
    'targetType',
  ])
  const id = requireCanonicalUuid(payload.id)
  const requestId = requireCanonicalUuid(payload.requestId)
  const scenarioId = requireNullableCanonicalUuid(payload.scenarioId)
  const runId = requireNullableCanonicalUuid(payload.runId)
  const actorId = requireNullableCanonicalUuid(payload.actorId)
  const action = requireNonEmptyString(payload.action)
  const result = requireNonEmptyString(payload.result)
  const clientIp = requireNonEmptyString(payload.clientIp)
  const targetType = requireNonEmptyString(payload.targetType)
  const targetId = requireCanonicalUuid(payload.targetId)
  const detailsMd5 = requireMd5(payload.detailsMd5)
  const expectedTarget = runId === null
    ? scenarioId === null
      ? ['TRADING_LAB_REQUEST', requestId]
      : ['TRADING_LAB_SCENARIO', scenarioId]
    : ['TRADING_LAB_RUN', runId]
  if (targetType !== expectedTarget[0] || targetId !== expectedTarget[1]) {
    throw new Error('Trading Lab audit target tuple is invalid')
  }
  const tuple = {
    requestId,
    action,
    targetType,
    targetId,
    actorId,
    result,
    clientIp,
    scenarioId,
    runId,
    detailsMd5,
  }
  return {
    ...base(id, payload.rowMd5),
    scenarioIdSha256: hashNullable(scenarioId),
    runIdSha256: hashNullable(runId),
    requestIdSha256: sha256(requestId),
    actionSha256: sha256(action),
    auditTupleSha256: sha256(canonicalJson(tuple)),
  }
}

function assertSplitFingerprint(fingerprint) {
  if (
    !isPlainObject(fingerprint)
    || fingerprint.schemaVersion !== 2
    || !isPlainObject(fingerprint.protectedPostgres)
    || !isPlainObject(fingerprint.mainRedis)
    || !isPlainObject(fingerprint.authSessions)
    || !isPlainObject(fingerprint.controlPlane)
  ) {
    throw new Error('Main data split fingerprint is invalid')
  }
  const {
    protectedPostgres,
    mainRedis,
    authSessions,
    controlPlane,
    digest,
  } = fingerprint
  if (
    !Array.isArray(protectedPostgres.tables)
    || protectedPostgres.tables.length === 0
    || protectedPostgres.sha256
      !== sha256(canonicalJson(protectedPostgres.tables))
  ) {
    throw new Error('Protected PostgreSQL fingerprint is invalid')
  }
  const protectedKeys = new Set()
  for (const table of protectedPostgres.tables) {
    if (
      !isPlainObject(table)
      || !isTableName(table.schema)
      || !isTableName(table.table)
      || !/^\d+$/u.test(table.count ?? '')
      || !MD5_PATTERN.test(table.checksum ?? '')
      || table.schema === 'trading_lab'
    ) {
      throw new Error('Protected PostgreSQL fingerprint is invalid')
    }
    const key = `${table.schema}.${table.table}`
    if (protectedKeys.has(key)) {
      throw new Error('Protected PostgreSQL fingerprint is invalid')
    }
    protectedKeys.add(key)
  }
  if (
    !Number.isSafeInteger(mainRedis.keyCount)
    || mainRedis.keyCount < 0
    || !SHA256_PATTERN.test(mainRedis.sha256 ?? '')
  ) {
    throw new Error('Main Redis fingerprint is invalid')
  }
  if (
    !Array.isArray(authSessions.rows)
    || authSessions.sha256 !== sha256(canonicalJson(authSessions.rows))
  ) {
    throw new Error('Main auth session fingerprint is invalid')
  }
  const authSessionIdentities = new Set()
  for (const row of authSessions.rows) {
    if (
      !isPlainObject(row)
      || !hasExactKeys(row, [
        'identitySha256',
        'rowSha256',
        'userIdSha256',
      ])
      || !SHA256_PATTERN.test(row.identitySha256 ?? '')
      || !SHA256_PATTERN.test(row.rowSha256 ?? '')
      || !SHA256_PATTERN.test(row.userIdSha256 ?? '')
      || authSessionIdentities.has(row.identitySha256)
    ) {
      throw new Error('Main auth session fingerprint is invalid')
    }
    authSessionIdentities.add(row.identitySha256)
  }

  const expectedKnownTables = knownControlTables()
  if (
    !Array.isArray(controlPlane.knownTables)
    || canonicalJson(controlPlane.knownTables)
      !== canonicalJson(expectedKnownTables)
  ) {
    throw new Error('Unknown Trading Lab table in control-plane fingerprint')
  }
  if (
    !Array.isArray(controlPlane.tables)
    || !Array.isArray(controlPlane.rows)
  ) {
    throw new Error('Trading Lab control-plane fingerprint is invalid')
  }
  const controlValue = {
    knownTables: controlPlane.knownTables,
    tables: controlPlane.tables,
    rows: controlPlane.rows,
  }
  if (controlPlane.sha256 !== sha256(canonicalJson(controlValue))) {
    throw new Error('Trading Lab control-plane fingerprint is invalid')
  }
  const counts = new Map()
  for (const table of controlPlane.tables) {
    const key = `${table?.schema}.${table?.table}`
    if (
      !CONTROL_PLANE_TABLE_KEYS.has(key)
      || !/^\d+$/u.test(table?.count ?? '')
      || counts.has(key)
    ) {
      throw new Error('Trading Lab control-plane fingerprint is invalid')
    }
    counts.set(key, table.count)
  }
  if (counts.size !== expectedKnownTables.length) {
    throw new Error('Trading Lab control-plane fingerprint is invalid')
  }
  const rowCounts = new Map()
  const identities = new Set()
  for (const row of controlPlane.rows) {
    const key = `${row?.schema}.${row?.table}`
    const identity = `${key}:${row?.identitySha256}`
    if (
      !isPlainObject(row)
      || !CONTROL_PLANE_TABLE_KEYS.has(key)
      || !SHA256_PATTERN.test(row.identitySha256 ?? '')
      || !SHA256_PATTERN.test(row.rowSha256 ?? '')
      || identities.has(identity)
    ) {
      throw new Error('Trading Lab control-plane fingerprint is invalid')
    }
    assertControlSnapshotRowShape(row, key)
    for (const [field, value] of Object.entries(row)) {
      if (
        field.endsWith('Sha256')
        && value !== null
        && !SHA256_PATTERN.test(value ?? '')
      ) {
        throw new Error('Trading Lab control-plane fingerprint is invalid')
      }
    }
    identities.add(identity)
    rowCounts.set(key, (rowCounts.get(key) ?? 0) + 1)
  }
  for (const [key, count] of counts) {
    if (String(rowCounts.get(key) ?? 0) !== count) {
      throw new Error('Trading Lab control-plane fingerprint is invalid')
    }
  }
  const value = {
    schemaVersion: 2,
    protectedPostgres,
    mainRedis,
    authSessions,
    controlPlane,
  }
  if (
    !SHA256_PATTERN.test(digest ?? '')
    || digest !== sha256(canonicalJson(value))
  ) {
    throw new Error('Main data split fingerprint is invalid')
  }
}

function assertControlSnapshotRowShape(row, tableKey) {
  const base = ['identitySha256', 'rowSha256', 'schema', 'table']
  const relationFields = controlSnapshotRelationFields(tableKey)
  if (!hasExactKeys(row, [...base, ...relationFields])) {
    throw new Error('Trading Lab control-plane fingerprint is invalid')
  }
  for (const field of relationFields) {
    if (
      row[field] !== null
      && !SHA256_PATTERN.test(row[field] ?? '')
    ) {
      throw new Error('Trading Lab control-plane fingerprint is invalid')
    }
  }
}

function controlSnapshotRelationFields(tableKey) {
  switch (tableKey) {
    case 'trading_lab.scenarios':
      return []
    case 'trading_lab.reports':
      return ['scenarioIdSha256']
    case 'trading_lab.runs':
      return [
        'reportIdSha256',
        'scenarioIdSha256',
        'stateSha256',
      ]
    case 'trading_lab.run_events':
    case 'trading_lab.run_transitions':
      return ['runIdSha256']
    case 'trading_lab.report_chunks':
    case 'trading_lab.report_appends':
      return ['reportIdSha256']
    case 'audit.request_logs':
      return [
        'methodSha256',
        'pathSha256',
        'requestIdSha256',
        'requestTupleSha256',
        'resourceIdSha256',
        'routeSha256',
        'routeStatusSha256',
        'statusCodeSha256',
      ]
    case 'trading_lab.audit_events':
    case 'audit.audit_logs':
      return [
        'actionSha256',
        'auditTupleSha256',
        'requestIdSha256',
        'runIdSha256',
        'scenarioIdSha256',
      ]
    default:
      throw new Error('Unknown Trading Lab table in control-plane fingerprint')
  }
}

function normalizeControlPlaneOwnership(ownership) {
  const keys = isPlainObject(ownership)
    ? Object.keys(ownership).sort()
    : []
  const validKeys = [
    [
      'createdRuns',
      'referencedRunIds',
      'requestLogObservations',
      'tradingLabRequestIds',
    ],
    [
      'authUserIds',
      'createdRuns',
      'referencedRunIds',
      'requestLogObservations',
      'tradingLabRequestIds',
    ],
    [
      'createdRuns',
      'createdScenarios',
      'referencedRunIds',
      'requestLogObservations',
      'tradingLabRequestIds',
    ],
    [
      'authUserIds',
      'createdRuns',
      'createdScenarios',
      'referencedRunIds',
      'requestLogObservations',
      'tradingLabRequestIds',
    ],
  ].some((expected) => canonicalJson(keys) === canonicalJson(expected))
  if (
    !isPlainObject(ownership)
    || !validKeys
    || !Array.isArray(ownership.createdRuns)
    || !Array.isArray(ownership.referencedRunIds)
    || !Array.isArray(ownership.tradingLabRequestIds)
    || !Array.isArray(ownership.requestLogObservations)
    || (
      Object.hasOwn(ownership, 'createdScenarios')
      && !Array.isArray(ownership.createdScenarios)
    )
    || (
      Object.hasOwn(ownership, 'authUserIds')
      && !Array.isArray(ownership.authUserIds)
    )
  ) {
    throw new Error('Trading Lab control-plane ownership is invalid')
  }
  const seenRunIds = new Set()
  const seenReportIds = new Set()
  const seenRoots = new Set()
  const allOwnedUuids = new Set()
  const createdScenarios = normalizeUniqueOwnershipUuids(
    ownership.createdScenarios ?? [],
  )
  for (const scenarioId of createdScenarios.raw) {
    allOwnedUuids.add(scenarioId)
  }
  const createdRuns = ownership.createdRuns.map((root) => {
    const terminal = (
      isPlainObject(root)
      && hasExactKeys(root, [
        'expectedTerminalState',
        'reportId',
        'runId',
        'scenarioId',
      ])
      && TERMINAL_RUN_STATES.has(root.expectedTerminalState)
    )
    const provisional = (
      isPlainObject(root)
      && hasExactKeys(root, [
        'provisional',
        'reportId',
        'runId',
        'scenarioId',
      ])
      && root.provisional === true
    )
    if (
      !terminal
      && !provisional
    ) {
      throw new Error('Trading Lab control-plane ownership is invalid')
    }
    const scenarioId = requireOwnershipUuid(root.scenarioId)
    const runId = requireOwnershipUuid(root.runId)
    const reportId = requireOwnershipUuid(root.reportId)
    if (
      seenRunIds.has(runId)
      || seenReportIds.has(reportId)
      || allOwnedUuids.has(scenarioId)
      || allOwnedUuids.has(runId)
      || allOwnedUuids.has(reportId)
    ) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
    allOwnedUuids.add(scenarioId)
    allOwnedUuids.add(runId)
    allOwnedUuids.add(reportId)
    seenRunIds.add(runId)
    seenReportIds.add(reportId)
    const identity = `${scenarioId}:${runId}:${reportId}`
    if (seenRoots.has(identity)) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
    seenRoots.add(identity)
    return {
      scenarioIdSha256: sha256(scenarioId),
      runIdSha256: sha256(runId),
      reportIdSha256: sha256(reportId),
      expectedStateSha256: terminal
        ? sha256(root.expectedTerminalState)
        : null,
    }
  })
  const referencedRunIds = normalizeUniqueOwnershipUuids(
    ownership.referencedRunIds,
  )
  for (const runId of referencedRunIds.raw) {
    if (allOwnedUuids.has(runId)) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
    allOwnedUuids.add(runId)
  }
  const tradingLabRequestIds = normalizeUniqueOwnershipUuids(
    ownership.tradingLabRequestIds,
  )
  for (const requestId of tradingLabRequestIds.raw) {
    if (allOwnedUuids.has(requestId)) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
    allOwnedUuids.add(requestId)
  }
  const requestLogObservations = normalizeRequestLogObservations(
    ownership.requestLogObservations,
  )
  for (const requestId of requestLogObservations.requestIds) {
    if (
      allOwnedUuids.has(requestId)
      && !tradingLabRequestIds.raw.has(requestId)
    ) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
  }
  return {
    createdScenarioHashes: createdScenarios.hashes,
    createdRuns,
    referencedRunIdHashes: referencedRunIds.hashes,
    requestIdHashes: tradingLabRequestIds.hashes,
    requestLogRequestIds: requestLogObservations.requestIds,
    requestLogObservationByRequestHash:
      requestLogObservations.byRequestHash,
  }
}

function normalizeRequestLogObservations(observations) {
  const requestIds = new Set()
  const byRequestHash = new Map()
  for (const observation of observations) {
    const responseObserved = (
      isPlainObject(observation)
      && hasExactKeys(observation, [
        'requestId',
        'requestTupleSha256',
      ])
      && SHA256_PATTERN.test(observation.requestTupleSha256 ?? '')
    )
    const requestOnly = (
      isPlainObject(observation)
      && hasExactKeys(observation, [
        'requestId',
        'requestMethodSha256',
        'requestPathSha256',
      ])
      && SHA256_PATTERN.test(observation.requestMethodSha256 ?? '')
      && SHA256_PATTERN.test(observation.requestPathSha256 ?? '')
    )
    if (!responseObserved && !requestOnly) {
      throw new Error('Trading Lab request-log observation is invalid')
    }
    const requestId = requireOwnershipUuid(observation.requestId)
    const requestIdSha256 = sha256(requestId)
    if (
      requestIds.has(requestId)
      || byRequestHash.has(requestIdSha256)
    ) {
      throw new Error('Trading Lab request-log observation is duplicated')
    }
    requestIds.add(requestId)
    byRequestHash.set(
      requestIdSha256,
      responseObserved
        ? {
            kind: 'RESPONSE',
            requestTupleSha256: observation.requestTupleSha256,
          }
        : {
            kind: 'REQUEST',
            methodSha256: observation.requestMethodSha256,
            pathSha256: observation.requestPathSha256,
          },
    )
  }
  return { requestIds, byRequestHash }
}

function assertAuthSessionDeltaOwned(before, after, ownership) {
  if (
    !isPlainObject(before)
    || !isPlainObject(after)
    || !Array.isArray(before.rows)
    || !Array.isArray(after.rows)
  ) {
    throw new Error('Main auth session fingerprint is invalid')
  }
  const beforeRows = new Map(
    before.rows.map((row) => [row.identitySha256, row]),
  )
  for (const [identity, row] of beforeRows) {
    const current = after.rows.find((candidate) =>
      candidate.identitySha256 === identity)
    if (!current || canonicalJson(current) !== canonicalJson(row)) {
      throw new Error('Existing main auth session changed or was deleted')
    }
  }
  const addedRows = after.rows.filter((row) =>
    !beforeRows.has(row.identitySha256))
  for (const row of addedRows) {
    if (!ownership.hashes.has(row.userIdSha256)) {
      throw new Error('Main auth session is outside run ownership')
    }
  }
  for (const userIdSha256 of ownership.hashes) {
    if (!addedRows.some((row) => row.userIdSha256 === userIdSha256)) {
      throw new Error('Run-owned Admin produced no observable auth session')
    }
  }
}

function assertControlPlaneDeltaOwned(before, after, ownership) {
  const beforeRows = indexControlRows(before.rows)
  const afterRows = indexControlRows(after.rows)
  for (const [identity, row] of beforeRows) {
    const current = afterRows.get(identity)
    if (!current || canonicalJson(current) !== canonicalJson(row)) {
      throw new Error(
        'Existing control-plane row changed or was deleted during validation run',
      )
    }
  }
  const addedRows = after.rows.filter((row) =>
    !beforeRows.has(controlRowIdentity(row)))
  const addedByTable = groupControlRowsByTable(addedRows)
  const beforeByTable = groupControlRowsByTable(before.rows)
  const createdRunHashes = new Set(
    ownership.createdRuns.map(({ runIdSha256 }) => runIdSha256),
  )
  const createdReportHashes = new Set(
    ownership.createdRuns.map(({ reportIdSha256 }) => reportIdSha256),
  )
  const createdScenarioHashes = new Set([
    ...ownership.createdScenarioHashes,
    ...ownership.createdRuns.map(
      ({ scenarioIdSha256 }) => scenarioIdSha256,
    ),
  ])
  const allowedRunHashes = new Set(createdRunHashes)
  const allowedReportHashes = new Set(createdReportHashes)
  const allowedScenarioHashes = new Set(createdScenarioHashes)
  const reportHashByRunHash = new Map(
    ownership.createdRuns.map((root) => [
      root.runIdSha256,
      root.reportIdSha256,
    ]),
  )

  for (const referencedRunId of ownership.referencedRunIdHashes) {
    const referenced = (beforeByTable.get('trading_lab.runs') ?? [])
      .find(({ identitySha256 }) => identitySha256 === referencedRunId)
    if (referenced === undefined) {
      throw new Error(
        'Trading Lab referenced run ownership does not exist before validation',
      )
    }
    allowedRunHashes.add(referencedRunId)
    allowedScenarioHashes.add(referenced.scenarioIdSha256)
    if (referenced.reportIdSha256 !== null) {
      allowedReportHashes.add(referenced.reportIdSha256)
      reportHashByRunHash.set(referencedRunId, referenced.reportIdSha256)
    }
  }

  for (const scenarioIdSha256 of ownership.createdScenarioHashes) {
    requireAddedRow(
      addedByTable,
      'trading_lab.scenarios',
      scenarioIdSha256,
    )
  }
  for (const root of ownership.createdRuns) {
    const scenario = requireAddedRow(
      addedByTable,
      'trading_lab.scenarios',
      root.scenarioIdSha256,
    )
    const report = requireAddedRow(
      addedByTable,
      'trading_lab.reports',
      root.reportIdSha256,
    )
    const run = requireAddedRow(
      addedByTable,
      'trading_lab.runs',
      root.runIdSha256,
    )
    if (
      report.scenarioIdSha256 !== root.scenarioIdSha256
      || run.scenarioIdSha256 !== root.scenarioIdSha256
      || run.reportIdSha256 !== root.reportIdSha256
      || (
        root.expectedStateSha256 !== null
        && run.stateSha256 !== root.expectedStateSha256
      )
      || scenario.identitySha256 !== root.scenarioIdSha256
    ) {
      throw new Error('Trading Lab control-plane ownership root is invalid')
    }
  }

  for (const row of addedRows) {
    const key = `${row.schema}.${row.table}`
    switch (key) {
      case 'trading_lab.scenarios':
        requireHashMembership(createdScenarioHashes, row.identitySha256)
        break
      case 'trading_lab.reports':
        requireHashMembership(createdReportHashes, row.identitySha256)
        requireHashMembership(createdScenarioHashes, row.scenarioIdSha256)
        break
      case 'trading_lab.runs':
        requireHashMembership(createdRunHashes, row.identitySha256)
        requireHashMembership(createdScenarioHashes, row.scenarioIdSha256)
        requireHashMembership(createdReportHashes, row.reportIdSha256)
        break
      case 'trading_lab.run_transitions':
      case 'trading_lab.run_events':
        requireHashMembership(createdRunHashes, row.runIdSha256)
        break
      case 'trading_lab.report_chunks':
      case 'trading_lab.report_appends':
        requireHashMembership(createdReportHashes, row.reportIdSha256)
        break
      case 'trading_lab.audit_events':
      case 'audit.audit_logs':
      case 'audit.request_logs':
        break
      default:
        throw new Error('Unknown Trading Lab table in control-plane delta')
    }
  }

  const requestLogsByRequest = assertRequestLogDeltaOwned({
    requestLogs: addedByTable.get('audit.request_logs') ?? [],
    ownership,
    allowedScenarioHashes,
    allowedRunHashes,
    allowedReportHashes,
  })
  const labAudits = addedByTable.get('trading_lab.audit_events') ?? []
  const genericAudits = addedByTable.get('audit.audit_logs') ?? []
  const labTuples = labAudits
    .map(({ auditTupleSha256 }) => auditTupleSha256)
    .sort()
  const genericTuples = genericAudits
    .map(({ auditTupleSha256 }) => auditTupleSha256)
    .sort()
  if (canonicalJson(labTuples) !== canonicalJson(genericTuples)) {
    throw new Error('Trading Lab dual audit tuple sets do not match')
  }

  for (const audit of labAudits) {
    const requestLogObservation =
      ownership.requestLogObservationByRequestHash.get(audit.requestIdSha256)
    if (
      !ownership.requestIdHashes.has(audit.requestIdSha256)
      && requestLogObservation?.kind !== 'REQUEST'
    ) {
      throw new Error('Trading Lab audit request is outside owned observations')
    }
    assertAuditRequestLogRoute({
      audit,
      requestLog: requestLogsByRequest.get(audit.requestIdSha256),
      reportHashByRunHash,
    })
    const hasScenario = audit.scenarioIdSha256 !== null
    const hasRun = audit.runIdSha256 !== null
    if (!hasScenario && !hasRun) {
      if (
        audit.actionSha256 !== sha256(ENVIRONMENT_AUDIT_ACTION)
        && audit.actionSha256 !== sha256(ENVIRONMENT_ACTION_AUDIT_ACTION)
      ) {
        throw new Error(
          'Trading Lab null-parent audit is not an environment operation',
        )
      }
      continue
    }
    if (hasRun) {
      requireHashMembership(allowedRunHashes, audit.runIdSha256)
      if (hasScenario) {
        const root = ownership.createdRuns.find(
          ({ runIdSha256 }) => runIdSha256 === audit.runIdSha256,
        )
        const referenced = (beforeByTable.get('trading_lab.runs') ?? [])
          .find(({ identitySha256 }) =>
            identitySha256 === audit.runIdSha256)
        const expectedScenario = root?.scenarioIdSha256
          ?? referenced?.scenarioIdSha256
        if (expectedScenario !== audit.scenarioIdSha256) {
          throw new Error('Trading Lab audit parent ownership is invalid')
        }
      }
      continue
    }
    requireHashMembership(createdScenarioHashes, audit.scenarioIdSha256)
  }
  for (const root of ownership.createdRuns) {
    const scenarioCreated = labAudits.some((audit) =>
      audit.actionSha256 === sha256(SCENARIO_CREATE_AUDIT_ACTION)
      && audit.scenarioIdSha256 === root.scenarioIdSha256
      && audit.runIdSha256 === null)
    const runCreated = labAudits.some((audit) =>
      audit.actionSha256 === sha256(RUN_CREATE_AUDIT_ACTION)
      && audit.scenarioIdSha256 === root.scenarioIdSha256
      && audit.runIdSha256 === root.runIdSha256)
    if (!scenarioCreated || !runCreated) {
      throw new Error('Trading Lab ownership root audit is incomplete')
    }
  }
  for (const scenarioIdSha256 of ownership.createdScenarioHashes) {
    const scenarioCreated = labAudits.some((audit) =>
      audit.actionSha256 === sha256(SCENARIO_CREATE_AUDIT_ACTION)
      && audit.scenarioIdSha256 === scenarioIdSha256
      && audit.runIdSha256 === null)
    if (!scenarioCreated) {
      throw new Error('Trading Lab scenario ownership audit is incomplete')
    }
  }
  for (const runId of ownership.referencedRunIdHashes) {
    if (!labAudits.some(({ runIdSha256 }) => runIdSha256 === runId)) {
      throw new Error('Trading Lab referenced run has no paired audit')
    }
  }
}

function assertRequestLogDeltaOwned({
  requestLogs,
  ownership,
  allowedScenarioHashes,
  allowedRunHashes,
  allowedReportHashes,
}) {
  const byRequest = new Map()
  for (const row of requestLogs) {
    const observation =
      ownership.requestLogObservationByRequestHash.get(row.requestIdSha256)
    if (observation === undefined) {
      throw new Error('Trading Lab request log is outside owned observations')
    }
    if (byRequest.has(row.requestIdSha256)) {
      throw new Error('Trading Lab request log request ID is duplicated')
    }
    if (
      observation.kind === 'RESPONSE'
      && row.requestTupleSha256 !== observation.requestTupleSha256
    ) {
      throw new Error(
        'Trading Lab request log response observation tuple is inconsistent',
      )
    }
    if (
      observation.kind === 'REQUEST'
      && (
        row.methodSha256 !== observation.methodSha256
        || row.pathSha256 !== observation.pathSha256
        || !isSuccessfulHttpStatusHash(row.statusCodeSha256)
      )
    ) {
      throw new Error(
        'Trading Lab request-only observation method path or status is inconsistent',
      )
    }
    byRequest.set(row.requestIdSha256, row)
    const route = row.routeSha256
    const noResourceRoutes = new Set([
      REQUEST_LOG_ROUTES.AUTH_LOGIN,
      REQUEST_LOG_ROUTES.DASHBOARD_SUMMARY,
      REQUEST_LOG_ROUTES.CONFIG,
      REQUEST_LOG_ROUTES.ENVIRONMENT_STATUS,
      REQUEST_LOG_ROUTES.ENVIRONMENT_CONTROL,
      REQUEST_LOG_ROUTES.SCENARIO_CREATE,
      REQUEST_LOG_ROUTES.PREFLIGHT_STATIC,
    ].map(sha256))
    const scenarioRoutes = new Set([
      REQUEST_LOG_ROUTES.SCENARIO_GET,
      REQUEST_LOG_ROUTES.RUN_CREATE,
      REQUEST_LOG_ROUTES.PREFLIGHT_SCENARIO,
    ].map(sha256))
    const runRoutes = new Set([
      REQUEST_LOG_ROUTES.RUN_GET,
      REQUEST_LOG_ROUTES.RUN_EVENTS,
      REQUEST_LOG_ROUTES.RUN_CONTROL,
      REQUEST_LOG_ROUTES.PREFLIGHT_RUN,
    ].map(sha256))
    const reportRoutes = new Set([
      REQUEST_LOG_ROUTES.REPORT_GET,
      REQUEST_LOG_ROUTES.REPORT_DOWNLOAD,
      REQUEST_LOG_ROUTES.REPORT_DELETE,
      REQUEST_LOG_ROUTES.REPORT_PERMANENT,
      REQUEST_LOG_ROUTES.REPORT_PRINT_INFO,
      REQUEST_LOG_ROUTES.REPORT_PRINT_CONFIRMATION,
      REQUEST_LOG_ROUTES.REPORT_PRINT,
      REQUEST_LOG_ROUTES.PREFLIGHT_REPORT,
    ].map(sha256))
    if (noResourceRoutes.has(route)) {
      if (row.resourceIdSha256 !== null) {
        throw new Error('Trading Lab request log resource is invalid')
      }
    } else if (scenarioRoutes.has(route)) {
      requireHashMembership(allowedScenarioHashes, row.resourceIdSha256)
    } else if (runRoutes.has(route)) {
      requireHashMembership(allowedRunHashes, row.resourceIdSha256)
    } else if (reportRoutes.has(route)) {
      requireHashMembership(allowedReportHashes, row.resourceIdSha256)
    } else {
      throw new Error('Trading Lab request log route is outside browser scope')
    }
  }
  for (const [
    requestIdSha256,
    observation,
  ] of ownership.requestLogObservationByRequestHash) {
    if (
      observation.kind === 'RESPONSE'
      && !byRequest.has(requestIdSha256)
    ) {
      throw new Error('Run-owned request produced no observable request log')
    }
  }
  return byRequest
}

function assertAuditRequestLogRoute({
  audit,
  requestLog,
  reportHashByRunHash,
}) {
  if (requestLog === undefined) {
    throw new Error('Trading Lab audit has no owned request log')
  }
  if (!isSuccessfulHttpStatusHash(requestLog.statusCodeSha256)) {
    throw new Error('Trading Lab audit request log was not successful')
  }
  let expectedRoute
  let expectedResource = null
  if (audit.actionSha256 === sha256(ENVIRONMENT_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.ENVIRONMENT_STATUS
  } else if (
    audit.actionSha256 === sha256(ENVIRONMENT_ACTION_AUDIT_ACTION)
  ) {
    expectedRoute = REQUEST_LOG_ROUTES.ENVIRONMENT_CONTROL
  } else if (audit.actionSha256 === sha256(SCENARIO_CREATE_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.SCENARIO_CREATE
    expectedResource = null
  } else if (audit.actionSha256 === sha256(RUN_CREATE_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.RUN_CREATE
    expectedResource = audit.scenarioIdSha256
  } else if (audit.actionSha256 === sha256(RUN_CONTROL_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.RUN_CONTROL
    expectedResource = audit.runIdSha256
  } else if (audit.actionSha256 === sha256(REPORT_DELETE_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.REPORT_DELETE
    expectedResource = reportHashByRunHash.get(audit.runIdSha256)
  } else if (audit.actionSha256 === sha256(REPORT_PERMANENT_AUDIT_ACTION)) {
    expectedRoute = REQUEST_LOG_ROUTES.REPORT_PERMANENT
    expectedResource = reportHashByRunHash.get(audit.runIdSha256)
  } else {
    throw new Error('Trading Lab audit action has no request-log route')
  }
  if (
    requestLog.routeSha256 !== sha256(expectedRoute)
    || requestLog.resourceIdSha256 !== expectedResource
  ) {
    throw new Error('Trading Lab audit request-log route is inconsistent')
  }
}

function isSuccessfulHttpStatusHash(value) {
  for (let status = 200; status <= 299; status += 1) {
    if (value === sha256(String(status))) return true
  }
  return false
}

function requireAddedRow(rowsByTable, table, identitySha256) {
  const matches = (rowsByTable.get(table) ?? [])
    .filter((row) => row.identitySha256 === identitySha256)
  if (matches.length !== 1) {
    throw new Error('Trading Lab control-plane ownership root is missing')
  }
  return matches[0]
}

function requireHashMembership(values, value) {
  if (!values.has(value)) {
    throw new Error('Trading Lab control-plane ownership rejected foreign row')
  }
}

function groupControlRowsByTable(rows) {
  const grouped = new Map()
  for (const row of rows) {
    const key = `${row.schema}.${row.table}`
    const entries = grouped.get(key) ?? []
    entries.push(row)
    grouped.set(key, entries)
  }
  return grouped
}

function indexControlRows(rows) {
  return new Map(rows.map((row) => [controlRowIdentity(row), row]))
}

function controlRowIdentity(row) {
  return `${row.schema}.${row.table}:${row.identitySha256}`
}

function normalizeUniqueOwnershipUuids(values) {
  const raw = new Set()
  const hashes = new Set()
  for (const value of values) {
    const uuid = requireOwnershipUuid(value)
    if (raw.has(uuid)) {
      throw new Error('Trading Lab control-plane ownership has duplicate UUID')
    }
    raw.add(uuid)
    hashes.add(sha256(uuid))
  }
  return { raw, hashes }
}

function requireOwnershipUuid(value) {
  if (!CANONICAL_UUID_PATTERN.test(value ?? '')) {
    throw new Error('Trading Lab control-plane ownership requires canonical UUID')
  }
  return value
}

function requireCanonicalUuid(value) {
  if (!CANONICAL_UUID_PATTERN.test(value ?? '')) {
    throw new Error('Main PostgreSQL control-plane UUID is malformed')
  }
  return value
}

function requireNullableCanonicalUuid(value) {
  return value === null ? null : requireCanonicalUuid(value)
}

function requireMd5(value) {
  if (!MD5_PATTERN.test(value ?? '')) {
    throw new Error('Main PostgreSQL control-plane row digest is invalid')
  }
  return value
}

function requireNonEmptyString(value) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error('Main PostgreSQL control-plane value is invalid')
  }
  return value
}

function requireIntegerString(value) {
  if (typeof value !== 'string' || !/^-?\d+$/u.test(value)) {
    throw new Error('Main PostgreSQL control-plane value is invalid')
  }
  return value
}

function requireHttpStatus(value) {
  if (!Number.isInteger(value) || value < 100 || value > 599) {
    throw new Error('Trading Lab request log HTTP status is invalid')
  }
  return value
}

function hashNullable(value) {
  return value === null ? null : sha256(value)
}

function assertExactKeys(value, expectedKeys) {
  if (!isPlainObject(value) || !hasExactKeys(value, expectedKeys)) {
    throw new Error('Main PostgreSQL control-plane row is invalid')
  }
}

function hasExactKeys(value, expectedKeys) {
  return canonicalJson(Object.keys(value).sort())
    === canonicalJson([...expectedKeys].sort())
}

function decodeHexJson(value) {
  if (value.length % 2 !== 0) {
    throw new Error('Main PostgreSQL control-plane row is invalid')
  }
  const text = Buffer.from(value, 'hex').toString('utf8')
  if (Buffer.from(text, 'utf8').toString('hex') !== value) {
    throw new Error('Main PostgreSQL control-plane row is invalid')
  }
  try {
    return JSON.parse(text)
  } catch {
    throw new Error('Main PostgreSQL control-plane row is invalid')
  }
}

function decodeTableKey(value) {
  if (value.length % 2 !== 0) {
    throw new Error('Main PostgreSQL fingerprint table is invalid')
  }
  const key = Buffer.from(value, 'hex').toString('utf8')
  if (
    Buffer.from(key, 'utf8').toString('hex') !== value
    || !/^[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*$/u.test(key)
  ) {
    throw new Error('Main PostgreSQL fingerprint table is invalid')
  }
  const separator = key.indexOf('.')
  return {
    key,
    schema: key.slice(0, separator),
    table: key.slice(separator + 1),
  }
}

function assertKnownControlTable(key) {
  if (!CONTROL_PLANE_TABLE_KEYS.has(key)) {
    throw new Error(`Unknown Trading Lab table in fingerprint: ${key}`)
  }
}

function knownControlTables() {
  return CONTROL_PLANE_TABLES.map(([schema, table]) => ({ schema, table }))
}

function tableEntryOrder(left, right) {
  return `${left.schema}.${left.table}`
    .localeCompare(`${right.schema}.${right.table}`)
}

function controlRowOrder(left, right) {
  return controlRowIdentity(left).localeCompare(controlRowIdentity(right))
}

function isTableName(value) {
  return typeof value === 'string'
    && /^[a-z_][a-z0-9_]*$/u.test(value)
}

function hexText(value) {
  return Buffer.from(value, 'utf8').toString('hex')
}

function createSupervisorRequestSender({
  token,
  fetchImpl = globalThis.fetch,
} = {}) {
  if (
    typeof token !== 'string'
    || Buffer.byteLength(token) < 32
    || token.trim() !== token
    || /[\r\n]/u.test(token)
  ) {
    throw new Error('Supervisor isolation credential is invalid')
  }
  if (typeof fetchImpl !== 'function') {
    throw new Error('Supervisor HTTP client is unavailable')
  }
  return async (payload) => {
    let response
    try {
      response = await fetchImpl(SUPERVISOR_URL, {
        method: 'POST',
        headers: {
          accept: 'application/json',
          authorization: `Bearer ${token}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(SUPERVISOR_REQUEST_TIMEOUT_MS),
      })
    } catch {
      throw new Error('Supervisor injection probe request failed')
    }
    const text = await response.text()
    if (Buffer.byteLength(text) > MAX_SUPERVISOR_RESPONSE_BYTES) {
      throw new Error('Supervisor injection probe response is too large')
    }
    let body
    try {
      body = JSON.parse(text)
    } catch {
      throw new Error('Supervisor injection probe response is invalid')
    }
    return { status: response.status, body }
  }
}

function parseReportDocument(report) {
  if (Buffer.isBuffer(report)) {
    return parseReportJson(report.toString('utf8'))
  }
  if (typeof report === 'string') {
    return parseReportJson(report)
  }
  if (!isJsonValue(report)) {
    throw new Error('Trading Lab downloaded report is invalid')
  }
  return report
}

function parseReportJson(text) {
  let value
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('Trading Lab downloaded report is invalid')
  }
  if (!isJsonValue(value)) {
    throw new Error('Trading Lab downloaded report is invalid')
  }
  return value
}

function normalizeSecretValues(values) {
  if (!Array.isArray(values)) {
    throw new Error('Trading Lab report secret registry is invalid')
  }
  const secrets = []
  for (const value of values) {
    if (typeof value !== 'string' || value.length === 0) continue
    if (Buffer.byteLength(value) < 8) {
      throw new Error('Trading Lab report secret registry is unsafe')
    }
    secrets.push(value)
  }
  return [...new Set(secrets)]
}

function walkReport(value, path, parentKey, visit) {
  visit(value, path, parentKey)
  if (Array.isArray(value)) {
    value.forEach((entry, index) => {
      walkReport(entry, [...path, index], parentKey, visit)
    })
    return
  }
  if (!isPlainObject(value)) return
  for (const [key, entry] of Object.entries(value)) {
    walkReport(entry, [...path, sha256(key).slice(0, 12)], key, visit)
  }
}

function isSensitiveField(name) {
  return SENSITIVE_FIELD_NAMES.has(normalizeFieldName(name))
}

function normalizeFieldName(name) {
  return typeof name === 'string'
    ? name.toLowerCase().replaceAll(/[^a-z0-9]/gu, '')
    : ''
}

function isJsonValue(value, seen = new Set()) {
  if (
    value === null
    || typeof value === 'string'
    || typeof value === 'boolean'
    || (typeof value === 'number' && Number.isFinite(value))
  ) {
    return true
  }
  if (typeof value !== 'object' || seen.has(value)) return false
  seen.add(value)
  if (Array.isArray(value)) {
    return value.every((entry) => isJsonValue(entry, seen))
  }
  if (!isPlainObject(value)) return false
  return Object.values(value).every((entry) => isJsonValue(entry, seen))
}

function isPlainObject(value) {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype
}

function sortObject(value) {
  return Object.fromEntries(
    Object.entries(value).sort(([left], [right]) =>
      left.localeCompare(right)),
  )
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`
  }
  if (isPlainObject(value)) {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

const invokedPath = process.argv[1]
  ? pathToFileURL(resolve(process.argv[1])).href
  : ''
if (invokedPath === import.meta.url) {
  process.stderr.write(
    'Import createTradingLabRuntimeIsolationProbe into the owned Trading Lab smoke runner.\n',
  )
  process.exitCode = 2
}
