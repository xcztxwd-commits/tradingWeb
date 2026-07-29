import { createHash, randomUUID } from 'node:crypto'

export const LARGE_REPORT_THRESHOLD_BYTES = 50 * 1024 * 1024
const MAX_REPORT_BYTES = 128 * 1024 * 1024
const ACTION_COUNT = 40
const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u
const CONFIG_HASH_PATTERN = /^[0-9a-f]{64}$/u
const REQUIRED_AUTHORITIES = [
  'TRADING_LAB_VIEW',
  'TRADING_LAB_EXECUTE',
  'SUPER_ADMIN',
]

export async function prepareTradingLabLargeFixture({
  apiUrl,
  fetchImpl = fetch,
  randomUuid = randomUUID,
  sleep = delay,
  timeoutMs = 20 * 60_000,
  credentials = defaultCredentials(),
  ownedAuthUserIds,
  onRecovery = () => {},
} = {}) {
  const canonicalOwnedAuthUserIds = requiredOwnedAuthUserIds(ownedAuthUserIds)
  const origin = strictMainApiOrigin(apiUrl)
  if (
    typeof fetchImpl !== 'function'
    || typeof randomUuid !== 'function'
    || typeof onRecovery !== 'function'
  ) {
    throw new Error('Large fixture HTTP dependencies are invalid')
  }
  if (
    !Number.isSafeInteger(timeoutMs)
    || timeoutMs < 1
    || timeoutMs > 30 * 60_000
  ) {
    throw new Error('Large fixture timeout is invalid')
  }
  const requestIds = []
  const tradingLabRequestIds = []
  const requestLogObservations = []
  let recoveryRoot = null
  const publishRecovery = async () => {
    if (recoveryRoot === null) return null
    const recovery = Object.freeze({
      ...recoveryRoot,
      actionCount: ACTION_COUNT,
      requestIds: Object.freeze([...requestIds]),
      tradingLabRequestIds: Object.freeze([...tradingLabRequestIds]),
      requestLogObservations: Object.freeze([
        ...requestLogObservations,
      ]),
    })
    await onRecovery(recovery)
    return recovery
  }
  const nextRequestId = (label, { tradingLab = true } = {}) => {
    const requestId = requiredGeneratedUuid(randomUuid, `${label} request ID`)
    if (requestIds.includes(requestId)) {
      throw new Error('Large fixture request IDs must be unique')
    }
    requestIds.push(requestId)
    if (tradingLab) tradingLabRequestIds.push(requestId)
    return requestId
  }
  const request = (options) => apiRequest({
    ...options,
    origin,
    fetchImpl,
    async observeRequestLog(observation) {
      const existingIndex = requestLogObservations.findIndex(
        ({ requestId }) => requestId === observation.requestId,
      )
      if (existingIndex < 0) {
        requestLogObservations.push(observation)
      } else {
        requestLogObservations[existingIndex] = observation
      }
      await publishRecovery()
    },
  })

  const loginRequestId = nextRequestId('login', { tradingLab: false })
  const login = await request({
    path: '/api/auth/login',
    method: 'POST',
    body: credentials,
    requestId: loginRequestId,
  })
  const authUserId = requiredUuid(
    login.userId,
    'Large fixture auth user ID',
  )
  if (!canonicalOwnedAuthUserIds.includes(authUserId)) {
    throw new Error(
      'Large fixture auth user is outside runner-owned auth users',
    )
  }
  const accessToken = requiredText(login, 'accessToken', 'login')
  if (!Array.isArray(login.authorities)) {
    throw new Error('Large fixture login authority snapshot is invalid')
  }
  for (const authority of REQUIRED_AUTHORITIES) {
    if (!login.authorities.includes(authority)) {
      throw new Error(`Large fixture Admin is missing ${authority}`)
    }
  }

  const configRequestId = nextRequestId('config')
  const config = await request({
    path: '/api/admin/trading-lab/config',
    accessToken,
    requestId: configRequestId,
  })
  const fixture = buildLargeCoordinatorFixture(config)
  const scenarioRequestId = nextRequestId('scenario')
  const createdScenario = await request({
    path: '/api/admin/trading-lab/scenarios',
    method: 'POST',
    body: fixture.scenarioRequest,
    accessToken,
    requestId: scenarioRequestId,
  })
  const scenarioId = requiredUuid(
    createdScenario.id,
    'Large fixture scenario ID',
  )
  recoveryRoot = Object.freeze({
    authUserId,
    scenarioId,
    provisional: true,
  })
  await publishRecovery()
  if (
    createdScenario.status !== 'DRAFT'
    || createdScenario.version !== 0
    || createdScenario.configSnapshotHash !== fixture.configSnapshotHash
  ) {
    throw new Error('Large fixture scenario response is inconsistent')
  }

  const runRequestId = nextRequestId('run')
  const accepted = await request({
    path: `/api/admin/trading-lab/scenarios/${scenarioId}/runs`,
    method: 'POST',
    body: fixture.runRequest,
    accessToken,
    requestId: runRequestId,
  })
  const runId = requiredUuid(accepted.id, 'Large fixture run ID')
  const reportId = requiredUuid(
    accepted.reportId,
    'Large fixture report ID',
  )
  recoveryRoot = Object.freeze({
    authUserId,
    scenarioId,
    runId,
    reportId,
    provisional: true,
  })
  await publishRecovery()
  assertRunIdentity(accepted, {
    scenarioId,
    runId,
    reportId,
    expectedState: 'QUEUED',
  })
  if (accepted.totalTicks !== ACTION_COUNT) {
    throw new Error('Large fixture accepted run tick count is inconsistent')
  }

  const deadline = Date.now() + timeoutMs
  let terminal = null
  while (Date.now() < deadline) {
    const pollRequestId = nextRequestId('run poll')
    const current = await request({
      path: `/api/admin/trading-lab/runs/${runId}`,
      accessToken,
      requestId: pollRequestId,
    })
    assertRunIdentity(current, { scenarioId, runId, reportId })
    if (TERMINAL_STATES.has(current.state)) {
      terminal = current
      recoveryRoot = Object.freeze({
        authUserId,
        scenarioId,
        runId,
        reportId,
        expectedTerminalState: current.state,
      })
      await publishRecovery()
      break
    }
    await sleep(250)
  }
  if (terminal === null) {
    throw new Error('Large fixture run did not reach a terminal state')
  }
  if (
    terminal.state !== 'COMPLETED'
    || terminal.processedTicks !== ACTION_COUNT
    || terminal.totalTicks !== ACTION_COUNT
  ) {
    throw new Error(
      `Large fixture run did not complete exactly: ${terminal.state}`,
    )
  }

  const reportRequestId = nextRequestId('report')
  const report = await request({
    path: `/api/admin/trading-lab/reports/${reportId}`,
    accessToken,
    requestId: reportRequestId,
  })
  if (
    requiredUuid(report.id, 'Large fixture report detail ID') !== reportId
    || requiredUuid(report.runId, 'Large fixture report run ID') !== runId
    || requiredUuid(
      report.scenarioId,
      'Large fixture report scenario ID',
    ) !== scenarioId
    || report.status !== 'COMPLETED'
  ) {
    throw new Error('Large fixture report identity is inconsistent')
  }
  const uncompressedBytes = report.uncompressedBytes
  if (
    !Number.isSafeInteger(uncompressedBytes)
    || uncompressedBytes <= LARGE_REPORT_THRESHOLD_BYTES
    || uncompressedBytes >= MAX_REPORT_BYTES
  ) {
    throw new Error(
      'Large fixture report must be > 50 MiB and < 128 MiB',
    )
  }
  return Object.freeze({
    authUserId,
    scenarioId,
    runId,
    reportId,
    uncompressedBytes,
    actionCount: ACTION_COUNT,
    requestIds: Object.freeze([...requestIds]),
    tradingLabRequestIds: Object.freeze([...tradingLabRequestIds]),
    requestLogObservations: Object.freeze([...requestLogObservations]),
  })
}

export function buildLargeCoordinatorFixture(config) {
  if (config === null || typeof config !== 'object' || Array.isArray(config)) {
    throw new Error('Large fixture config response is invalid')
  }
  const configSnapshot = config.configSnapshot
  if (
    configSnapshot === null
    || typeof configSnapshot !== 'object'
    || Array.isArray(configSnapshot)
  ) {
    throw new Error('Large fixture config snapshot is invalid')
  }
  const configSnapshotHash = requiredText(
    config,
    'configSnapshotHash',
    'config',
  )
  if (!CONFIG_HASH_PATTERN.test(configSnapshotHash)) {
    throw new Error('Large fixture config hash is invalid')
  }
  const modelVersion = requiredText(config, 'modelVersion', 'config')
  if (
    configSnapshot.modelVersion !== modelVersion
    || configSnapshot.executionPolicy === null
    || typeof configSnapshot.executionPolicy !== 'object'
    || Array.isArray(configSnapshot.executionPolicy)
    || !Array.isArray(configSnapshot.instruments)
  ) {
    throw new Error('Large fixture authoritative config is inconsistent')
  }
  const instrument = configSnapshot.instruments.find((candidate) => (
    candidate?.symbol === 'BTCUSDT'
    && candidate?.productType === 'CRYPTO_SPOT'
  ))
  if (
    instrument?.baseAsset !== 'BTC'
    || instrument?.quoteAsset !== 'USDT'
  ) {
    throw new Error(
      'Large fixture requires authoritative BTCUSDT CRYPTO_SPOT rules',
    )
  }

  const seed = 'phase4-http-large-coordinator-seed'
  const snapshot = structuredClone(configSnapshot)
  const executionPolicy = structuredClone(configSnapshot.executionPolicy)
  const scenario = {
    id: 'phase4-http-large-coordinator',
    name: 'phase4-http-large-coordinator',
    description: '',
    negativeMode: false,
    seed,
    modelVersion,
    configSnapshot: structuredClone(snapshot),
    configSnapshotHash,
    executionPolicy,
    marketPath: {
      virtualStart: '2026-07-25T00:00:00Z',
      realistic: false,
      instruments: [{
        mode: 'SIMPLE',
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        seed: `${seed}-path`,
        last: {
          start: '50000.00',
          segments: [{
            target: '50000.00',
            durationSeconds: ACTION_COUNT,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 1,
          }],
        },
        spreadSteps: 2,
        indexOffsetSteps: 0,
        basisSteps: 0,
      }],
    },
    initialBalances: { USDT: '100000.00000000' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
    }],
    timeline: Array.from({ length: ACTION_COUNT }, (_, sequence) => ({
      id: `large-spot-buy-${sequence}`,
      sequence,
      type: 'PLACE_ORDER',
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
      trigger: {
        type: 'VIRTUAL_TIME',
        atSecond: 1,
      },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '100.00000000',
        quantityUnit: 'QUOTE',
      },
    })),
  }
  const localCalculation = {
    fixture: 'phase4-http-large-coordinator',
    invariants: {
      actionCount: ACTION_COUNT,
      totalTicks: ACTION_COUNT,
      directReportWritesAllowed: false,
    },
  }
  return Object.freeze({
    configSnapshotHash,
    scenarioRequest: {
      name: 'Phase 4 real coordinator large report',
      description:
        'Real Admin/coordinator/validation report larger than 50 MiB',
      negativeMode: false,
      seed,
      modelVersion,
      scenario,
      configSnapshot: snapshot,
      configSnapshotHash,
      expectedVersion: null,
    },
    runRequest: {
      scenarioVersion: 0,
      configSnapshotHash,
      localCalculation,
    },
  })
}

async function apiRequest({
  origin,
  path,
  method = 'GET',
  body,
  accessToken,
  requestId,
  fetchImpl,
  observeRequestLog,
}) {
  const headers = {
    Accept: 'application/json',
    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    ...(accessToken === undefined
      ? {}
      : { Authorization: `Bearer ${accessToken}` }),
    ...(requestId === undefined ? {} : { 'X-Request-Id': requestId }),
  }
  if (requestId !== undefined) {
    await observeRequestLog(requestOnlyObservation(
      requestId,
      method,
      path,
    ))
  }
  const response = await fetchImpl(`${origin}${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    redirect: 'manual',
    signal: AbortSignal.timeout(30_000),
  })
  if (requestId !== undefined) {
    await observeRequestLog(requestLogObservation(
      requestId,
      method,
      path,
      response.status,
    ))
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!/^application\/json(?:;|$)/iu.test(contentType)) {
    throw new Error(`Large fixture ${method} ${path} was not JSON`)
  }
  let envelope
  try {
    envelope = await response.json()
  } catch {
    throw new Error(`Large fixture ${method} ${path} returned invalid JSON`)
  }
  if (
    !response.ok
    || envelope?.success !== true
    || envelope.code !== 'OK'
    || envelope.data === null
    || envelope.data === undefined
  ) {
    throw new Error(
      `Large fixture ${method} ${path} failed with HTTP ${response.status}`,
    )
  }
  if (
    requestId !== undefined
    && response.headers.get('x-request-id') !== requestId
  ) {
    throw new Error(
      `Large fixture ${method} ${path} request correlation drifted`,
    )
  }
  return envelope.data
}

function requestOnlyObservation(requestId, method, path) {
  return Object.freeze({
    requestId: requiredUuid(requestId, 'Large fixture request-log request ID'),
    requestMethodSha256: createHash('sha256')
      .update(method)
      .digest('hex'),
    requestPathSha256: createHash('sha256')
      .update(path)
      .digest('hex'),
  })
}

function requestLogObservation(requestId, method, path, statusCode) {
  return Object.freeze({
    requestId: requiredUuid(requestId, 'Large fixture request-log request ID'),
    requestTupleSha256: createHash('sha256')
      .update(JSON.stringify({ method, path, statusCode }))
      .digest('hex'),
  })
}

function assertRunIdentity(
  value,
  { scenarioId, runId, reportId, expectedState },
) {
  if (
    requiredUuid(value?.id, 'Large fixture observed run ID') !== runId
    || requiredUuid(
      value?.scenarioId,
      'Large fixture observed scenario ID',
    ) !== scenarioId
    || requiredUuid(
      value?.reportId,
      'Large fixture observed report ID',
    ) !== reportId
    || (
      expectedState !== undefined
      && value?.state !== expectedState
    )
  ) {
    throw new Error('Large fixture run identity is inconsistent')
  }
}

function requiredGeneratedUuid(generator, label) {
  return requiredUuid(generator(), `Large fixture ${label}`)
}

function requiredUuid(value, label) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new Error(`${label} must be a canonical UUID`)
  }
  return value
}

function requiredOwnedAuthUserIds(values) {
  if (
    !Array.isArray(values)
    || values.length === 0
    || values.some((value) => !UUID_PATTERN.test(value))
    || new Set(values).size !== values.length
  ) {
    throw new Error('Large fixture owned auth user IDs are invalid')
  }
  return Object.freeze([...values])
}

function requiredText(value, field, label) {
  const text = value?.[field]
  if (typeof text !== 'string' || text.trim().length === 0) {
    throw new Error(`Large fixture ${label} ${field} is invalid`)
  }
  return text
}

function strictMainApiOrigin(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error('Large fixture API URL is invalid')
  }
  if (
    url.protocol !== 'http:'
    || !['127.0.0.1', 'localhost'].includes(url.hostname)
    || url.port !== '18086'
    || url.username !== ''
    || url.password !== ''
    || !['', '/'].includes(url.pathname)
    || url.search !== ''
    || url.hash !== ''
  ) {
    throw new Error(
      'Large fixture API must be the credential-free loopback port 18086',
    )
  }
  return url.origin
}

function defaultCredentials() {
  return {
    email: process.env.TRADING_LAB_SMOKE_SUPER_EMAIL
      ?? 'admin-smoke@example.com',
    password: process.env.TRADING_LAB_SMOKE_SUPER_PASSWORD
      ?? 'Password123!',
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}
