import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  FIXED_NETWORK_PROBES,
  SUPERVISOR_INJECTION_PROBES,
  assertMainDataFingerprintUnchanged,
  assertValidationRuntimeIdentity,
  buildMainDataFingerprint,
  buildValidationTcpProbeSpec,
  captureMainDataFingerprint,
  createTradingLabRuntimeIsolationProbe,
  runSupervisorInjectionProbes,
  scanDownloadedReport,
  verifyTradingLabRuntimeIsolation,
} from './verify-trading-lab-runtime-isolation.mjs'
import { createValidationSupervisor } from './validation-supervisor.mjs'

const scriptsRoot = dirname(fileURLToPath(import.meta.url))
const platformRoot = resolve(scriptsRoot, '..')
const backendId = 'a'.repeat(64)

test('freezes fixed validation-only network probes and shell-free Docker argv', () => {
  assert.deepEqual(
    FIXED_NETWORK_PROBES.map(({ id, host, port, expectedReachable }) => ({
      id,
      host,
      port,
      expectedReachable,
    })),
    [
      {
        id: 'public-binance-rest',
        host: 'api.binance.com',
        port: 443,
        expectedReachable: false,
      },
      {
        id: 'public-okx-rest',
        host: 'www.okx.com',
        port: 443,
        expectedReachable: false,
      },
      {
        id: 'public-massive-rest',
        host: 'api.massive.com',
        port: 443,
        expectedReachable: false,
      },
      {
        id: 'main-postgres-host-port',
        host: 'host.docker.internal',
        port: 5432,
        expectedReachable: false,
      },
      {
        id: 'main-redis-host-port',
        host: 'host.docker.internal',
        port: 6379,
        expectedReachable: false,
      },
      {
        id: 'configured-broker-fail-sink',
        host: '127.0.0.1',
        port: 9,
        expectedReachable: false,
      },
      {
        id: 'configured-fix-fail-sink',
        host: '127.0.0.1',
        port: 9,
        expectedReachable: false,
      },
      {
        id: 'configured-lp-fail-sink',
        host: '127.0.0.1',
        port: 9,
        expectedReachable: false,
      },
      {
        id: 'validation-postgres',
        host: 'validation-postgres',
        port: 5432,
        expectedReachable: true,
      },
      {
        id: 'validation-redis',
        host: 'validation-redis',
        port: 6379,
        expectedReachable: true,
      },
    ],
  )

  const spec = buildValidationTcpProbeSpec(
    backendId,
    FIXED_NETWORK_PROBES.at(-1),
  )
  assert.deepEqual(spec, {
    executable: 'docker',
    args: [
      'exec',
      backendId,
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
      'validation-redis',
      '6379',
    ],
    options: {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  })
  assert.throws(
    () => buildValidationTcpProbeSpec('validation-backend', FIXED_NETWORK_PROBES[0]),
    /validated container id/iu,
  )
})

test('requires the exact validation identity, internal network, no published port, and disabled external runtime', () => {
  const identity = validRuntimeIdentity()
  assert.doesNotThrow(() => assertValidationRuntimeIdentity(identity))

  for (const mutate of [
    (value) => { value.composeProject = 'fx-platform-main' },
    (value) => { value.composeService = 'backend' },
    (value) => { value.running = false },
    (value) => { value.networks.push('bridge') },
    (value) => { value.networkInternal = false },
    (value) => { value.publishedPorts = true },
    (value) => { value.springProfiles = 'validation,dev' },
    (value) => { value.executionMode = 'live' },
    (value) => { value.disabledRuntimeFlags.MARKET_REALTIME_ENABLED = 'true' },
    (value) => { value.disabledRuntimeFlags.TRADING_LAB_QUEUE_ENABLED = 'true' },
    (value) => { value.requiredSecretsPresent.VALIDATION_INTERNAL_SECRET = false },
    (value) => { value.externalEndpointOverrides.push('BINANCE_REST_BASE_URL') },
  ]) {
    const candidate = structuredClone(identity)
    mutate(candidate)
    assert.throws(
      () => assertValidationRuntimeIdentity(candidate),
      /validation runtime isolation identity/iu,
    )
  }
})

test('canonicalizes main PostgreSQL counts/checksums and hashed Redis key inventory', () => {
  const alphaHex = Buffer.from('core.wallet_balances').toString('hex')
  const betaHex = Buffer.from('trading.orders').toString('hex')
  const before = buildMainDataFingerprint({
    postgresOutput: [
      `COUNT:${betaHex}\t2`,
      `CHECKSUM:${betaHex}\t${'b'.repeat(32)}`,
      `COUNT:${alphaHex}\t7`,
      `CHECKSUM:${alphaHex}\t${'a'.repeat(32)}`,
    ].join('\n'),
    redisOutput: ['session:z', 'quote:btc', 'session:a'].join('\n'),
  })
  const same = buildMainDataFingerprint({
    postgresOutput: [
      `CHECKSUM:${alphaHex}\t${'a'.repeat(32)}`,
      `COUNT:${alphaHex}\t7`,
      `CHECKSUM:${betaHex}\t${'b'.repeat(32)}`,
      `COUNT:${betaHex}\t2`,
    ].join('\n'),
    redisOutput: ['session:a', 'session:z', 'quote:btc'].join('\n'),
  })

  assert.deepEqual(before, same)
  assert.deepEqual(before.protectedPostgres.tables, [
    {
      schema: 'core',
      table: 'wallet_balances',
      count: '7',
      checksum: 'a'.repeat(32),
    },
    {
      schema: 'trading',
      table: 'orders',
      count: '2',
      checksum: 'b'.repeat(32),
    },
  ])
  assert.equal(before.mainRedis.keyCount, 3)
  assert.equal(
    before.mainRedis.sha256,
    sha256(canonicalJson(
      ['quote:btc', 'session:a', 'session:z']
        .map(sha256)
        .sort(),
    )),
  )
  assert.doesNotThrow(() => assertMainDataFingerprintUnchanged(before, same))

  const changed = structuredClone(same)
  changed.protectedPostgres.tables[1].count = '3'
  assert.throws(
    () => assertMainDataFingerprintUnchanged(before, changed),
    /protected PostgreSQL|split fingerprint/iu,
  )
})

test('parses split snapshots into digests without retaining row content or Redis keys', () => {
  const scenarioId = 'abcdefab-cdef-4abc-8def-abcdefabcdef'
  const fingerprint = buildMainDataFingerprint({
    postgresOutput: splitPostgresOutput({
      scenarioPayloads: [{
        id: scenarioId,
        rowMd5: 'b'.repeat(32),
      }],
    }),
    redisOutput: 'private:main:key\n',
  })

  assert.equal(fingerprint.schemaVersion, 2)
  assert.deepEqual(fingerprint.protectedPostgres.tables, [{
    schema: 'core',
    table: 'wallet_balances',
    count: '1',
    checksum: 'a'.repeat(32),
  }])
  assert.equal(fingerprint.mainRedis.keyCount, 1)
  assert.equal(
    fingerprint.controlPlane.rows[0].identitySha256,
    sha256(scenarioId),
  )
  assert.equal(
    JSON.stringify(fingerprint).includes(scenarioId),
    false,
  )
  assert.equal(
    JSON.stringify(fingerprint).includes('private:main:key'),
    false,
  )

  assert.throws(
    () => buildMainDataFingerprint({
      postgresOutput: splitPostgresOutput({
        extraControlTable: 'trading_lab.future_rows',
      }),
      redisOutput: '',
    }),
    /unknown Trading Lab table/iu,
  )
  assert.throws(
    () => buildMainDataFingerprint({
      postgresOutput: splitPostgresOutput({
        scenarioPayloads: [
          { id: scenarioId, rowMd5: 'b'.repeat(32) },
          { id: scenarioId, rowMd5: 'c'.repeat(32) },
        ],
      }),
      redisOutput: '',
    }),
    /duplicate/iu,
  )
  assert.throws(
    () => buildMainDataFingerprint({
      postgresOutput: splitPostgresOutput({
        scenarioPayloads: [{
          id: scenarioId.toUpperCase(),
          rowMd5: 'b'.repeat(32),
        }],
      }),
      redisOutput: '',
    }),
    /UUID is malformed/iu,
  )
})

test('models the complete browser request-log route set as hashed control rows', () => {
  const scenarioId = '11111111-1111-4111-8111-111111111111'
  const runId = '22222222-2222-4222-8222-222222222222'
  const reportId = '33333333-3333-4333-8333-333333333333'
  const businessRoutes = [
    ['POST', '/api/auth/login'],
    ['GET', '/api/admin/dashboard/summary'],
    ['GET', '/api/admin/trading-lab/config'],
    ['GET', '/api/admin/trading-lab/environment'],
    ['POST', '/api/admin/trading-lab/environment/start'],
    ['POST', '/api/admin/trading-lab/environment/stop'],
    ['POST', '/api/admin/trading-lab/environment/restart'],
    ['POST', '/api/admin/trading-lab/scenarios'],
    ['GET', `/api/admin/trading-lab/scenarios/${scenarioId}`],
    ['POST', `/api/admin/trading-lab/scenarios/${scenarioId}/runs`],
    ['GET', `/api/admin/trading-lab/runs/${runId}`],
    ['GET', `/api/admin/trading-lab/runs/${runId}/events`],
    ['POST', `/api/admin/trading-lab/runs/${runId}/pause`],
    ['POST', `/api/admin/trading-lab/runs/${runId}/resume`],
    ['POST', `/api/admin/trading-lab/runs/${runId}/cancel`],
    ['GET', `/api/admin/trading-lab/reports/${reportId}`],
    ['GET', `/api/admin/trading-lab/reports/${reportId}/download`],
    ['DELETE', `/api/admin/trading-lab/reports/${reportId}`],
    ['POST', `/api/admin/trading-lab/reports/${reportId}/permanent`],
    ['GET', `/api/admin/trading-lab/reports/${reportId}/print-info`],
    ['POST', `/api/admin/trading-lab/reports/${reportId}/print-confirmation`],
    ['GET', `/api/admin/trading-lab/reports/${reportId}/print`],
  ]
  const routes = [
    ...businessRoutes,
    ...[...new Set(businessRoutes.map(([, path]) => path))]
      .map((path) => ['OPTIONS', path]),
  ]
  const payloads = routes.map(([method, path], index) => ({
    id: numberedUuid(index + 1),
    requestId: numberedUuid(index + 101),
    method,
    path,
    queryStringPresent: false,
    statusCode: index === 2 ? 403 : 200,
    rowMd5: index.toString(16).padStart(32, '0'),
  }))
  const fingerprint = buildMainDataFingerprint({
    postgresOutput: splitPostgresOutput({ requestLogPayloads: payloads }),
    redisOutput: '',
  })
  const serialized = JSON.stringify(fingerprint)

  assert.equal(
    fingerprint.controlPlane.rows.filter(
      ({ schema, table }) => schema === 'audit' && table === 'request_logs',
    ).length,
    routes.length,
  )
  for (const payload of payloads) {
    assert.equal(serialized.includes(payload.id), false)
    assert.equal(serialized.includes(payload.requestId), false)
    assert.equal(serialized.includes(payload.path), false)
  }
  assert.equal(serialized.includes('/api/auth/login'), false)
})

test('request-log route parsing rejects method/path drift, query data, and malformed status', () => {
  const base = {
    id: '11111111-1111-4111-8111-111111111111',
    requestId: '22222222-2222-4222-8222-222222222222',
    method: 'POST',
    path: '/api/auth/login',
    queryStringPresent: false,
    statusCode: 200,
    rowMd5: 'a'.repeat(32),
  }
  for (const payload of [
    { ...base, method: 'GET' },
    { ...base, path: '/api/auth/login/' },
    { ...base, path: '/api/auth/login?password=raw-secret' },
    { ...base, queryStringPresent: true },
    { ...base, statusCode: null },
    {
      ...base,
      method: 'OPTIONS',
      path: '/api/auth/login',
      statusCode: 204,
    },
    {
      ...base,
      method: 'OPTIONS',
      path: '/api/admin/dashboard/users',
    },
    {
      ...base,
      method: 'OPTIONS',
      path: '/api/admin/trading-lab/scenarios/',
    },
    {
      ...base,
      method: 'GET',
      path: '/api/admin/trading-lab/reports/'
        + '33333333-3333-4333-8333-333333333333/metadata',
    },
    {
      ...base,
      method: 'GET',
      path: '/api/admin/trading-lab/runs/'
        + 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA',
    },
  ]) {
    assert.throws(
      () => buildMainDataFingerprint({
        postgresOutput: splitPostgresOutput({
          requestLogPayloads: [payload],
        }),
        redisOutput: '',
      }),
      /request log|route|fingerprint|status|query/iu,
    )
  }
})

test('binds exact CORS preflights and the Admin shell route to owned request rows', () => {
  const scenarioId = '11111111-1111-4111-8111-111111111111'
  const runId = '22222222-2222-4222-8222-222222222222'
  const reportId = '33333333-3333-4333-8333-333333333333'
  const allowed = [
    ['OPTIONS', '/api/auth/login'],
    ['GET', '/api/admin/dashboard/summary'],
    ['OPTIONS', '/api/admin/dashboard/summary'],
    ['OPTIONS', '/api/admin/trading-lab/config'],
    ['OPTIONS', '/api/admin/trading-lab/environment'],
    ['OPTIONS', '/api/admin/trading-lab/scenarios'],
  ]
  const ownership = {
    ...emptyOwnership(),
    requestLogObservations: allowed.map(([method, path], index) =>
      requestLogObservation(numberedUuid(index + 501), method, path)),
  }
  const rows = allowed.map(([method, path], index) =>
    requestLogControlRow(
      numberedUuid(index + 601),
      numberedUuid(index + 501),
      method,
      path,
    ))
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: rows }),
      ownership,
    ),
  )

  for (const [index, path] of [
    `/api/admin/trading-lab/scenarios/${scenarioId}`,
    `/api/admin/trading-lab/scenarios/${scenarioId}/runs`,
    `/api/admin/trading-lab/runs/${runId}`,
    `/api/admin/trading-lab/runs/${runId}/events`,
    `/api/admin/trading-lab/runs/${runId}/pause`,
    `/api/admin/trading-lab/reports/${reportId}`,
    `/api/admin/trading-lab/reports/${reportId}/download`,
    `/api/admin/trading-lab/reports/${reportId}/permanent`,
    `/api/admin/trading-lab/reports/${reportId}/print-info`,
    `/api/admin/trading-lab/reports/${reportId}/print-confirmation`,
    `/api/admin/trading-lab/reports/${reportId}/print`,
  ].entries()) {
    const requestId = numberedUuid(index + 701)
    const row = requestLogControlRow(
      numberedUuid(index + 801),
      requestId,
      'OPTIONS',
      path,
    )
    assert.throws(
      () => assertMainDataFingerprintUnchanged(
        splitMainFingerprint(),
        splitMainFingerprint({ controlRows: [row] }),
        {
          ...emptyOwnership(),
          requestLogObservations: [
            requestLogObservation(requestId, 'OPTIONS', path),
          ],
        },
      ),
      /request log|resource|control-plane ownership/iu,
      `foreign dynamic preflight was accepted: ${path}`,
    )
  }
})

test('accepts owned dynamic preflights and an observed stale-config 409', () => {
  const ownership = ownedControlPlane()
  const [{ scenarioId, runId, reportId }] = ownership.createdRuns
  const preflights = [
    [
      '71717171-7171-4171-8171-717171717171',
      `/api/admin/trading-lab/scenarios/${scenarioId}/runs`,
    ],
    [
      '72727272-7272-4272-8272-727272727272',
      `/api/admin/trading-lab/runs/${runId}/pause`,
    ],
    [
      '73737373-7373-4373-8373-737373737373',
      `/api/admin/trading-lab/reports/${reportId}/print-confirmation`,
    ],
  ]
  const staleRequestId = '74747474-7474-4474-8474-747474747474'
  ownership.tradingLabRequestIds.push(
    ...preflights.map(([requestId]) => requestId),
    staleRequestId,
  )
  ownership.requestLogObservations.push(
    ...preflights.map(([requestId, path]) =>
      requestLogObservation(requestId, 'OPTIONS', path)),
    requestLogObservation(
      staleRequestId,
      'POST',
      '/api/admin/trading-lab/scenarios',
      409,
    ),
  )
  const rows = [
    ...ownedControlRows(ownedControlPlane()),
    ...preflights.map(([requestId, path], index) =>
      requestLogControlRow(
        numberedUuid(index + 901),
        requestId,
        'OPTIONS',
        path,
      )),
    requestLogControlRow(
      '75757575-7575-4575-8575-757575757575',
      staleRequestId,
      'POST',
      '/api/admin/trading-lab/scenarios',
      409,
    ),
  ]
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: rows }),
      ownership,
    ),
  )
})

test('allows exactly one new request-log row per canonical observed request', () => {
  const loginId = '11111111-1111-4111-8111-111111111111'
  const configId = '22222222-2222-4222-8222-222222222222'
  const before = splitMainFingerprint()
  const ownedRows = [
    requestLogControlRow(
      '33333333-3333-4333-8333-333333333333',
      loginId,
      'POST',
      '/api/auth/login',
    ),
    requestLogControlRow(
      '44444444-4444-4444-8444-444444444444',
      configId,
      'GET',
      '/api/admin/trading-lab/config',
    ),
  ]
  const ownership = {
    ...emptyOwnership(),
    requestLogObservations: [
      requestLogObservation(loginId, 'POST', '/api/auth/login'),
      requestLogObservation(
        configId,
        'GET',
        '/api/admin/trading-lab/config',
      ),
    ],
  }
  const ownedAfter = splitMainFingerprint({ controlRows: ownedRows })
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(before, ownedAfter, ownership),
  )

  const foreignId = '55555555-5555-4555-8555-555555555555'
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      splitMainFingerprint({
        controlRows: [
          ...ownedRows,
          requestLogControlRow(
            '66666666-6666-4666-8666-666666666666',
            foreignId,
            'POST',
            '/api/auth/login',
          ),
        ],
      }),
      ownership,
    ),
    /request log|owned observation|control-plane ownership/iu,
  )
  assert.throws(
    () => assertMainDataFingerprintUnchanged(before, ownedAfter, {
      ...ownership,
      requestLogObservations: [
        ...ownership.requestLogObservations,
        requestLogObservation(foreignId, 'POST', '/api/auth/login'),
      ],
    }),
    /request log|observable|missing|control-plane ownership/iu,
  )
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      splitMainFingerprint({
        controlRows: [
          ...ownedRows,
          requestLogControlRow(
            '77777777-7777-4777-8777-777777777777',
            loginId,
            'POST',
            '/api/auth/login',
          ),
        ],
      }),
      ownership,
    ),
    /request log|duplicate|control-plane ownership/iu,
  )
})

test('binds each request-log ID to the exact observed method path and status tuple', () => {
  const requestId = '11111111-1111-4111-8111-111111111111'
  const row = requestLogControlRow(
    '22222222-2222-4222-8222-222222222222',
    requestId,
    'POST',
    '/api/auth/login',
    200,
  )
  const before = splitMainFingerprint()
  const after = splitMainFingerprint({ controlRows: [row] })
  const ownership = {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [],
    requestLogObservations: [{
      requestId,
      requestTupleSha256: sha256(canonicalJson({
        method: 'POST',
        path: '/api/auth/login',
        statusCode: 200,
      })),
    }],
  }

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(before, after, ownership),
  )
  for (const observedTuple of [
    {
      method: 'GET',
      path: '/api/admin/trading-lab/config',
      statusCode: 200,
    },
    {
      method: 'POST',
      path: '/api/auth/login',
      statusCode: 204,
    },
  ]) {
    assert.throws(
      () => assertMainDataFingerprintUnchanged(before, after, {
        ...ownership,
        requestLogObservations: [{
          requestId,
          requestTupleSha256: sha256(canonicalJson(observedTuple)),
        }],
      }),
      /request log|observation|tuple|ownership/iu,
    )
  }
})

test('request-only ownership accepts only a matching successful persisted subset', () => {
  const persistedRequestId = '11111111-1111-4111-8111-111111111111'
  const cancelledRequestId = '22222222-2222-4222-8222-222222222222'
  const requestOnlyOwnership = {
    ...emptyOwnership(),
    requestLogObservations: [
      requestLogRequestObservation(
        persistedRequestId,
        'GET',
        '/api/admin/dashboard/summary',
      ),
      requestLogRequestObservation(
        cancelledRequestId,
        'GET',
        '/api/admin/trading-lab/environment',
      ),
    ],
  }
  const before = splitMainFingerprint()
  const matching = requestLogControlRow(
    '33333333-3333-4333-8333-333333333333',
    persistedRequestId,
    'GET',
    '/api/admin/dashboard/summary',
  )

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      before,
      splitMainFingerprint({ controlRows: [matching] }),
      requestOnlyOwnership,
    ),
  )
  for (const row of [
    requestLogControlRow(
      '44444444-4444-4444-8444-444444444444',
      persistedRequestId,
      'GET',
      '/api/admin/trading-lab/environment',
    ),
    requestLogControlRow(
      '55555555-5555-4555-8555-555555555555',
      persistedRequestId,
      'GET',
      '/api/admin/dashboard/summary',
      500,
    ),
    requestLogControlRow(
      '66666666-6666-4666-8666-666666666666',
      '77777777-7777-4777-8777-777777777777',
      'GET',
      '/api/admin/dashboard/summary',
    ),
  ]) {
    assert.throws(
      () => assertMainDataFingerprintUnchanged(
        before,
        splitMainFingerprint({ controlRows: [row] }),
        requestOnlyOwnership,
      ),
      /request log|observation|method|path|status|ownership/iu,
    )
  }
})

test('request-log resource IDs stay inside created or referenced ownership', () => {
  const ownership = ownedControlPlane()
  const foreignRunId = 'abababab-abab-4aba-8aba-abababababab'
  const requestId = 'cdcdcdcd-cdcd-4cdc-8dcd-cdcdcdcdcdcd'
  ownership.requestLogObservations.push(requestLogObservation(
    requestId,
    'GET',
    `/api/admin/trading-lab/runs/${foreignRunId}`,
  ))
  const after = splitMainFingerprint({
    controlRows: [
      ...ownedControlRows(ownership),
      requestLogControlRow(
        'efefefef-efef-4efe-8efe-efefefefefef',
        requestId,
        'GET',
        `/api/admin/trading-lab/runs/${foreignRunId}`,
      ),
    ],
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      after,
      ownership,
    ),
    /request log|resource|foreign|control-plane ownership/iu,
  )
})

test('allows observed requests without audits but rejects audits outside observations', () => {
  const ownership = ownedControlPlane()
  ownership.tradingLabRequestIds.push(
    'abababab-abab-4aba-8aba-abababababab',
  )
  const before = splitMainFingerprint()
  const after = splitMainFingerprint({
    controlRows: ownedControlRows(ownership),
  })

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(before, after, ownership),
  )

  const missingObservedAudit = structuredClone(ownership)
  missingObservedAudit.tradingLabRequestIds =
    missingObservedAudit.tradingLabRequestIds.slice(1)
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      after,
      missingObservedAudit,
    ),
    /audit request is outside owned observations|control-plane ownership/iu,
  )
})

test('response-less audited requests retain ownership through an exact successful request log', () => {
  const terminalOwnership = ownedControlPlane()
  const rows = ownedControlRows(terminalOwnership)
  const [scenarioRequestId] = terminalOwnership.tradingLabRequestIds
  const ownership = structuredClone(terminalOwnership)
  ownership.tradingLabRequestIds = ownership.tradingLabRequestIds.slice(1)
  ownership.requestLogObservations[0] = requestLogRequestObservation(
    scenarioRequestId,
    'POST',
    '/api/admin/trading-lab/scenarios',
  )

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: rows }),
      ownership,
    ),
  )

  const wrongRoute = structuredClone(ownership)
  wrongRoute.requestLogObservations[0] = requestLogRequestObservation(
    scenarioRequestId,
    'POST',
    '/api/admin/trading-lab/runs',
  )
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: rows }),
      wrongRoute,
    ),
    /request log|observation|method|path|route|ownership/iu,
  )
})

test('provisional created roots retain exact failure-path ownership without claiming a terminal state', () => {
  const terminalOwnership = ownedControlPlane()
  const [{ scenarioId, runId, reportId }] = terminalOwnership.createdRuns
  const ownership = {
    ...terminalOwnership,
    createdRuns: [{
      scenarioId,
      runId,
      reportId,
      provisional: true,
    }],
  }
  const rows = ownedControlRows(terminalOwnership).map((row) => (
    row.schema === 'trading_lab' && row.table === 'runs'
      ? { ...row, stateSha256: sha256('CLEANING') }
      : row
  ))

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: rows }),
      ownership,
    ),
  )
  for (const root of [
    { scenarioId, runId, reportId, provisional: false },
    {
      scenarioId,
      runId,
      reportId,
      provisional: true,
      expectedTerminalState: 'COMPLETED',
    },
  ]) {
    assert.throws(
      () => assertMainDataFingerprintUnchanged(
        splitMainFingerprint(),
        splitMainFingerprint({ controlRows: rows }),
        { ...ownership, createdRuns: [root] },
      ),
      /ownership|provisional|control-plane/iu,
    )
  }
})

test('standalone provisional scenarios retain exact pre-admission ownership', () => {
  const terminalOwnership = ownedControlPlane()
  const [{ scenarioId }] = terminalOwnership.createdRuns
  const scenarioRequestId = terminalOwnership.tradingLabRequestIds[0]
  const ownership = {
    createdScenarios: [scenarioId],
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [scenarioRequestId],
    requestLogObservations: [
      terminalOwnership.requestLogObservations[0],
    ],
  }
  const scenarioRows = ownedControlRows(terminalOwnership).filter((row) => (
    (
      row.schema === 'trading_lab'
      && row.table === 'scenarios'
    )
    || (
      row.requestIdSha256 === sha256(scenarioRequestId)
      && (
        row.table === 'audit_events'
        || row.table === 'audit_logs'
        || row.table === 'request_logs'
      )
    )
  ))

  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({ controlRows: scenarioRows }),
      ownership,
    ),
  )
})

test('keeps protected PostgreSQL and main Redis strictly unchanged', () => {
  const before = splitMainFingerprint()
  const sameCountUpdate = splitMainFingerprint({
    protectedChecksum: 'b'.repeat(32),
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      sameCountUpdate,
      emptyOwnership(),
    ),
    /protected PostgreSQL|main data fingerprint changed/iu,
  )

  const redisChanged = splitMainFingerprint({
    redisSha256: sha256('foreign-main-key'),
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      redisChanged,
      emptyOwnership(),
    ),
    /main Redis|main data fingerprint changed/iu,
  )
})

test('allows only new auth sessions belonging to the exact run-owned Admin users', () => {
  const ownedUserId = '10101010-1010-4010-8010-101010101010'
  const foreignUserId = '20202020-2020-4020-8020-202020202020'
  const before = splitMainFingerprint()
  const ownedAfter = splitMainFingerprint({
    authSessionRows: [authSessionRow(
      '30303030-3030-4030-8030-303030303030',
      ownedUserId,
    )],
  })
  const ownership = {
    ...emptyOwnership(),
    authUserIds: [ownedUserId],
  }
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(before, ownedAfter, ownership),
  )

  const foreignAfter = splitMainFingerprint({
    authSessionRows: [authSessionRow(
      '40404040-4040-4040-8040-404040404040',
      foreignUserId,
    )],
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(before, foreignAfter, ownership),
    /auth session|ownership|main data fingerprint/iu,
  )
})

test('rejects foreign or extra control rows and modification or deletion of existing rows', () => {
  const ownership = ownedControlPlane()
  const ownedRows = ownedControlRows(ownership)
  const before = splitMainFingerprint()
  const foreign = splitMainFingerprint({
    controlRows: [
      ...ownedRows,
      controlRow(
        'trading_lab',
        'run_events',
        'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        { runId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' },
      ),
    ],
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(before, foreign, ownership),
    /control-plane ownership|main data fingerprint changed/iu,
  )

  const existing = controlRow(
    'trading_lab',
    'runs',
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    {
      scenarioId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
      reportId: '99999999-9999-4999-8999-999999999999',
      state: 'COMPLETED',
    },
  )
  const existingBefore = splitMainFingerprint({ controlRows: [existing] })
  const modified = structuredClone(existing)
  modified.rowSha256 = sha256('modified-existing-run')
  const modifiedAfter = splitMainFingerprint({ controlRows: [modified] })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      existingBefore,
      modifiedAfter,
      emptyOwnership(),
    ),
    /existing control-plane row|main data fingerprint changed/iu,
  )
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      existingBefore,
      splitMainFingerprint(),
      emptyOwnership(),
    ),
    /existing control-plane row|main data fingerprint changed/iu,
  )
})

test('referenced runs remain byte-identical while their observed audit pair may be appended', () => {
  const referencedRunId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
  const referencedScenarioId = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
  const requestId = 'abababab-abab-4aba-8aba-abababababab'
  const existing = controlRow(
    'trading_lab',
    'runs',
    referencedRunId,
    {
      scenarioId: referencedScenarioId,
      reportId: '99999999-9999-4999-8999-999999999999',
      state: 'COMPLETED',
    },
  )
  const tuple = auditTuple({
    requestId,
    action: 'TRADING_LAB_RUN_CONTROL',
    targetType: 'TRADING_LAB_RUN',
    targetId: referencedRunId,
    actorId: '12121212-1212-4212-8212-121212121212',
    result: 'SUCCESS',
    clientIp: '127.0.0.1',
    scenarioId: referencedScenarioId,
    runId: referencedRunId,
    detailsMd5: '1'.repeat(32),
  })
  const appendedAudits = [
    auditControlRow(
      'trading_lab',
      'audit_events',
      '13131313-1313-4313-8313-131313131313',
      tuple,
    ),
    auditControlRow(
      'audit',
      'audit_logs',
      '14141414-1414-4414-8414-141414141414',
      tuple,
    ),
  ]
  const additionalRequestIds = Array.from(
    { length: 8 },
    (_, index) => numberedUuid(index + 301),
  )
  const appendedRequestLogs = [
    requestLogControlRow(
      '15151515-1515-4515-8515-151515151515',
      requestId,
      'POST',
      `/api/admin/trading-lab/runs/${referencedRunId}/pause`,
    ),
    requestLogControlRow(
      numberedUuid(401),
      additionalRequestIds[0],
      'GET',
      `/api/admin/trading-lab/scenarios/${referencedScenarioId}`,
    ),
    requestLogControlRow(
      numberedUuid(402),
      additionalRequestIds[1],
      'GET',
      `/api/admin/trading-lab/runs/${referencedRunId}`,
    ),
    requestLogControlRow(
      numberedUuid(403),
      additionalRequestIds[2],
      'GET',
      `/api/admin/trading-lab/runs/${referencedRunId}/events`,
    ),
    ...[
      '',
      '/download',
      '/print-info',
      '/print-confirmation',
      '/print',
    ].map((suffix, index) => requestLogControlRow(
      numberedUuid(404 + index),
      additionalRequestIds[3 + index],
      suffix === '/print-confirmation' ? 'POST' : 'GET',
      `/api/admin/trading-lab/reports/`
        + `99999999-9999-4999-8999-999999999999${suffix}`,
    )),
  ]
  const ownership = {
    createdRuns: [],
    referencedRunIds: [referencedRunId],
    tradingLabRequestIds: [requestId],
    requestLogObservations: appendedRequestLogs.map((row, index) => ({
      requestId: [requestId, ...additionalRequestIds][index],
      requestTupleSha256: row.requestTupleSha256,
    })),
  }
  const before = splitMainFingerprint({ controlRows: [existing] })
  const after = splitMainFingerprint({
    controlRows: [
      existing,
      ...appendedAudits,
      ...appendedRequestLogs,
    ],
  })
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(before, after, ownership),
  )

  const changed = structuredClone(existing)
  changed.rowSha256 = sha256('referenced-run-was-updated')
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      splitMainFingerprint({
        controlRows: [
          changed,
          ...appendedAudits,
          ...appendedRequestLogs,
        ],
      }),
      ownership,
    ),
    /existing control-plane row|main data fingerprint changed/iu,
  )
})

test('allows a successful null-parent environment action with its exact request log', () => {
  const requestId = 'abababab-abab-4aba-8aba-abababababab'
  const tuple = auditTuple({
    requestId,
    action: 'TRADING_LAB_ENVIRONMENT_ACTION',
    targetType: 'TRADING_LAB_REQUEST',
    targetId: requestId,
    actorId: '12121212-1212-4212-8212-121212121212',
    result: 'SUCCESS',
    clientIp: '127.0.0.1',
    scenarioId: null,
    runId: null,
    detailsMd5: '1'.repeat(32),
  })
  const ownership = {
    ...emptyOwnership(),
    tradingLabRequestIds: [requestId],
    requestLogObservations: [requestLogObservation(
      requestId,
      'POST',
      '/api/admin/trading-lab/environment/restart',
    )],
  }
  const after = splitMainFingerprint({
    controlRows: [
      auditControlRow(
        'trading_lab',
        'audit_events',
        '13131313-1313-4313-8313-131313131313',
        tuple,
      ),
      auditControlRow(
        'audit',
        'audit_logs',
        '14141414-1414-4414-8414-141414141414',
        tuple,
      ),
      requestLogControlRow(
        '15151515-1515-4515-8515-151515151515',
        requestId,
        'POST',
        '/api/admin/trading-lab/environment/restart',
      ),
    ],
  })
  assert.doesNotThrow(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      after,
      ownership,
    ),
  )
})

test('fails closed on non-environment null-parent audits and mismatched dual audit tuples', () => {
  const requestId = 'abababab-abab-4aba-8aba-abababababab'
  const invalidTuple = auditTuple({
    requestId,
    action: 'TRADING_LAB_RUN_CONTROL',
    targetType: 'TRADING_LAB_REQUEST',
    targetId: requestId,
    actorId: '12121212-1212-4212-8212-121212121212',
    result: 'FAILED',
    clientIp: '127.0.0.1',
    scenarioId: null,
    runId: null,
    detailsMd5: '1'.repeat(32),
  })
  const ownership = {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [requestId],
    requestLogObservations: [requestLogObservation(
      requestId,
      'GET',
      '/api/admin/trading-lab/environment',
    )],
  }
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({
        controlRows: [
          auditControlRow(
            'trading_lab',
            'audit_events',
            '13131313-1313-4313-8313-131313131313',
            invalidTuple,
          ),
          auditControlRow(
            'audit',
            'audit_logs',
            '14141414-1414-4414-8414-141414141414',
            invalidTuple,
          ),
          requestLogControlRow(
            '17171717-1717-4717-8717-171717171717',
            requestId,
            'GET',
            '/api/admin/trading-lab/environment',
          ),
        ],
      }),
      ownership,
    ),
    /null-parent|environment|request-log route|control-plane ownership/iu,
  )

  const checkedTuple = {
    ...invalidTuple,
    action: 'TRADING_LAB_ENVIRONMENT_CHECKED',
  }
  const mismatchedTuple = {
    ...checkedTuple,
    result: 'SUCCESS',
  }
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      splitMainFingerprint(),
      splitMainFingerprint({
        controlRows: [
          auditControlRow(
            'trading_lab',
            'audit_events',
            '15151515-1515-4515-8515-151515151515',
            checkedTuple,
          ),
          auditControlRow(
            'audit',
            'audit_logs',
            '16161616-1616-4616-8616-161616161616',
            mismatchedTuple,
          ),
          requestLogControlRow(
            '17171717-1717-4717-8717-171717171717',
            requestId,
            'GET',
            '/api/admin/trading-lab/environment',
          ),
        ],
      }),
      ownership,
    ),
    /audit tuple|control-plane ownership/iu,
  )
})

test('fails closed on unknown Trading Lab tables and malformed or duplicate ownership UUIDs', () => {
  const before = splitMainFingerprint()
  const unknown = splitMainFingerprint({
    extraKnownTables: [
      { schema: 'trading_lab', table: 'future_unclassified_rows' },
    ],
  })
  assert.throws(
    () => assertMainDataFingerprintUnchanged(
      before,
      unknown,
      emptyOwnership(),
    ),
    /unknown Trading Lab table|control-plane fingerprint/iu,
  )

  for (const ownership of [
    {
      createdRuns: [],
      referencedRunIds: ['NOT-A-UUID'],
      tradingLabRequestIds: [],
      requestLogObservations: [],
    },
    {
      createdRuns: [],
      referencedRunIds: [],
      tradingLabRequestIds: [
        'abababab-abab-4aba-8aba-abababababab',
        'abababab-abab-4aba-8aba-abababababab',
      ],
      requestLogObservations: [],
    },
    {
      ...emptyOwnership(),
      ignoredField: [],
    },
  ]) {
    assert.throws(
      () => assertMainDataFingerprintUnchanged(before, before, ownership),
      /canonical UUID|duplicate UUID|control-plane ownership/iu,
    )
  }
})

test('main fingerprints use fixed containers, a read-only PostgreSQL snapshot, and Redis scan', async () => {
  const tableHex = Buffer.from('core.wallet_balances').toString('hex')
  const calls = []
  const fingerprint = await captureMainDataFingerprint({
    runCommand: (spec) => {
      calls.push(spec)
      if (spec.args.includes('psql')) {
        return [
          `COUNT:${tableHex}\t1`,
          `CHECKSUM:${tableHex}\t${'a'.repeat(32)}`,
        ].join('\n')
      }
      return 'session:one\n'
    },
  })

  assert.deepEqual(fingerprint.protectedPostgres.tables[0], {
    schema: 'core',
    table: 'wallet_balances',
    count: '1',
    checksum: 'a'.repeat(32),
  })
  assert.equal(calls.length, 2)
  assert.deepEqual(calls[0].args, [
    'exec',
    '-i',
    'fx-platform-postgres',
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
    'postgres',
    '-d',
    'fx_platform',
  ])
  assert.match(calls[0].input, /REPEATABLE READ READ ONLY/iu)
  assert.match(calls[0].input, /PROTECTED/iu)
  assert.match(calls[0].input, /schemaname <> 'trading_lab'/iu)
  assert.match(calls[0].input, /trading_lab.*report_chunks/isu)
  assert.match(calls[0].input, /CONTROL_ROW/iu)
  assert.match(calls[0].input, /AUTH_SESSION/iu)
  assert.match(calls[0].input, /auth\.user_sessions/iu)
  assert.match(calls[0].input, /audit.*audit_logs/isu)
  assert.doesNotMatch(calls[0].input, /\b(?:INSERT|UPDATE|DELETE|TRUNCATE|DROP)\b/iu)
  assert.equal(calls[0].maxOutputBytes, undefined)
  assert.equal(calls[0].maxLineBytes, 16 * 1024 * 1024)
  assert.equal(calls[0].maxStderrBytes, 1024 * 1024)
  assert.equal(calls[0].timeoutMs, 120_000)
  assert.deepEqual(calls[1].args, [
    'exec',
    'fx-platform-redis',
    'redis-cli',
    '--raw',
    '--scan',
  ])
})

test('postflight SQL excludes only canonical owned request IDs from the protected aggregate', async () => {
  const requestId = 'abababab-abab-4aba-8aba-abababababab'
  const tableHex = Buffer.from('core.wallet_balances').toString('hex')
  let postgresSql = ''
  await captureMainDataFingerprint({
    ownership: {
      ...emptyOwnership(),
      requestLogObservations: [
        requestLogObservation(requestId, 'POST', '/api/auth/login'),
      ],
    },
    runCommand(spec) {
      if (!spec.args.includes('psql')) return ''
      postgresSql = spec.input
      return [
        `COUNT:${tableHex}\t1`,
        `CHECKSUM:${tableHex}\t${'a'.repeat(32)}`,
      ].join('\n')
    },
  })

  assert.match(
    postgresSql,
    new RegExp(`ARRAY\\['${requestId}'\\]::text\\[\\]`, 'u'),
  )
  assert.match(
    postgresSql,
    /WHERE NOT COALESCE\(source_row\.request_id = ANY\(.+\), FALSE\)/u,
  )
  assert.match(
    postgresSql,
    /FROM audit\.request_logs AS source_row[\s\S]+WHERE source_row\.request_id = ANY/iu,
  )
  assert.doesNotMatch(postgresSql, /user_agent|error_message/iu)
  const requestLogRowsSql = postgresSql.slice(
    postgresSql.indexOf(
      `CONTROL_ROW:${Buffer.from('audit.request_logs').toString('hex')}`,
    ),
    postgresSql.indexOf(
      `CONTROL_ROW:${Buffer.from('trading_lab.scenarios').toString('hex')}`,
    ),
  )
  assert.doesNotMatch(requestLogRowsSql, /client_ip|authorization|cookie/iu)
})

test('main PostgreSQL fingerprint streams cumulative output beyond the legacy 128 MiB cap', async () => {
  const { runPostgresFingerprintCommand } =
    await import('./verify-trading-lab-runtime-isolation.mjs')
  assert.equal(typeof runPostgresFingerprintCommand, 'function')
  const tableHex = Buffer.from('core.wallet_balances').toString('hex')
  const lineBytes = 1024 * 1024
  const lineCount = 129
  const fingerprint = await runPostgresFingerprintCommand({
    executable: process.execPath,
    args: [
      '-e',
      [
        "const { once } = require('node:events')",
        ';(async () => {',
        'const line = Buffer.alloc(Number(process.argv[1]), 32)',
        'line[line.length - 1] = 10',
        'for (let index = 0; index < Number(process.argv[2]); index += 1) {',
        "if (!process.stdout.write(line)) await once(process.stdout, 'drain')",
        '}',
        'process.stdout.write(process.argv[3])',
        '})().catch((error) => {',
        'process.stderr.write(String(error))',
        'process.exitCode = 1',
        '})',
      ].join(';'),
      String(lineBytes),
      String(lineCount),
      [
        `COUNT:${tableHex}\t1`,
        `CHECKSUM:${tableHex}\t${'a'.repeat(32)}`,
        '',
      ].join('\n'),
    ],
    label: 'streaming PostgreSQL fixture',
    maxLineBytes: 2 * 1024 * 1024,
    timeoutMs: 30_000,
  })

  assert.equal(lineBytes * lineCount > 128 * 1024 * 1024, true)
  assert.deepEqual(fingerprint.protectedTables, [{
    schema: 'core',
    table: 'wallet_balances',
    count: '1',
    checksum: 'a'.repeat(32),
  }])
})

test('streaming PostgreSQL fingerprint fails closed on child and line corruption', async () => {
  const { runPostgresFingerprintCommand } =
    await import('./verify-trading-lab-runtime-isolation.mjs')
  assert.equal(typeof runPostgresFingerprintCommand, 'function')
  const tableHex = Buffer.from('core.wallet_balances').toString('hex')
  const valid = [
    `COUNT:${tableHex}\t1`,
    `CHECKSUM:${tableHex}\t${'a'.repeat(32)}`,
    '',
  ].join('\n')
  const secret = 'must-not-echo-this-stderr-value'
  const cases = [
    {
      name: 'non-zero child',
      script: `process.stderr.write('${secret}');process.exit(7)`,
      timeoutMs: 5_000,
      pattern: /status=7/iu,
    },
    {
      name: 'timeout',
      script: 'setInterval(() => {}, 1000)',
      timeoutMs: 50,
      pattern: /code=ETIMEDOUT/iu,
    },
    {
      name: 'immediate exit with pending stdin',
      script: 'process.exit(9)',
      input: 'x'.repeat(1024 * 1024),
      timeoutMs: 5_000,
      pattern: /status=9|code=EPIPE/iu,
    },
    {
      name: 'truncated final line',
      script: `process.stdout.write(${JSON.stringify(valid.slice(0, -1))})`,
      timeoutMs: 5_000,
      pattern: /truncated/iu,
    },
    {
      name: 'malformed line',
      script: "process.stdout.write('not-a-fingerprint\\n')",
      timeoutMs: 5_000,
      pattern: /fingerprint output is invalid/iu,
    },
    {
      name: 'malformed line before timeout',
      script: [
        "process.stdout.write('not-a-fingerprint\\n')",
        'setTimeout(() => process.exit(0), 1000)',
      ].join(';'),
      timeoutMs: 250,
      maxElapsedMs: 600,
      pattern: /fingerprint output is invalid/iu,
    },
    {
      name: 'duplicate line',
      script: `process.stdout.write(${JSON.stringify(
        `COUNT:${tableHex}\t1\nCOUNT:${tableHex}\t1\n`,
      )})`,
      timeoutMs: 5_000,
      pattern: /fingerprint output is invalid/iu,
    },
    {
      name: 'oversized line',
      script: "process.stdout.write('x'.repeat(257) + '\\n')",
      timeoutMs: 5_000,
      maxLineBytes: 256,
      pattern: /line limit/iu,
    },
  ]

  for (const fixture of cases) {
    const startedAt = Date.now()
    await assert.rejects(runPostgresFingerprintCommand({
      executable: process.execPath,
      args: ['-e', fixture.script],
      label: fixture.name,
      input: fixture.input,
      maxLineBytes: fixture.maxLineBytes ?? 1024,
      timeoutMs: fixture.timeoutMs,
    }), (error) => {
      assert.match(error.message, fixture.pattern, fixture.name)
      assert.equal(error.message.includes(secret), false, fixture.name)
      if (fixture.maxElapsedMs) {
        assert.equal(
          Date.now() - startedAt < fixture.maxElapsedMs,
          true,
          fixture.name,
        )
      }
      assert.equal(Number.isSafeInteger(error.pid), true, fixture.name)
      assert.throws(
        () => process.kill(error.pid, 0),
        (probeError) => probeError?.code === 'ESRCH',
        fixture.name,
      )
      return true
    })
  }
  assert.equal(valid.endsWith('\n'), true)
})

test('streaming PostgreSQL fingerprint pipes large stdin to exact EOF', async () => {
  const { runPostgresFingerprintCommand } =
    await import('./verify-trading-lab-runtime-isolation.mjs')
  const tableHex = Buffer.from('core.wallet_balances').toString('hex')
  const valid = [
    `COUNT:${tableHex}\t1`,
    `CHECKSUM:${tableHex}\t${'a'.repeat(32)}`,
    '',
  ].join('\n')
  const input = 'read-only-fingerprint-sql\n'.repeat(50_000)
  const inputSha256 = createHash('sha256').update(input).digest('hex')
  const fingerprint = await runPostgresFingerprintCommand({
    executable: process.execPath,
    args: [
      '-e',
      [
        "const { createHash } = require('node:crypto')",
        'const chunks = []',
        "process.stdin.on('data', (chunk) => chunks.push(chunk))",
        "process.stdin.on('end', () => {",
        "const digest = createHash('sha256').update(Buffer.concat(chunks)).digest('hex')",
        'if (digest !== process.argv[1]) process.exit(8)',
        'process.stdout.write(process.argv[2])',
        '})',
      ].join(';'),
      inputSha256,
      valid,
    ],
    input,
    label: 'large stdin PostgreSQL fixture',
    maxLineBytes: 1024,
    timeoutMs: 5_000,
  })

  assert.deepEqual(fingerprint.protectedTables, [{
    schema: 'core',
    table: 'wallet_balances',
    count: '1',
    checksum: 'a'.repeat(32),
  }])
})

test('report scan allows credential metadata names but rejects raw values without echoing them', () => {
  const secret = 'validation-secret-value-0123456789'
  const safeReport = {
    authorization: '[REDACTED]',
    password: { present: false },
    authentication: {
      credentialLocation: 'validation-only',
      credentialFields: ['Authorization', 'Cookie', 'database password'],
    },
    validationInternalToken: '<redacted>',
    note: 'JWT secret and database password values are omitted',
  }
  assert.deepEqual(
    scanDownloadedReport(safeReport, {
      reportName: 'safe-report.json',
      secretValues: [secret],
    }),
    {
      reportName: 'safe-report.json',
      scanned: true,
      violations: [],
    },
  )

  const unsafeReport = {
    apiTrace: [
      {
        requestHeaders: {
          Authorization: 'Bearer live-token-value',
          Cookie: 'session=live-cookie',
        },
      },
    ],
    arbitrarySafeLookingField: `prefix-${secret}-suffix`,
    databasePassword: 'raw-database-password',
  }
  const result = scanDownloadedReport(JSON.stringify(unsafeReport), {
    reportName: 'unsafe-report.json',
    secretValues: [secret],
  })
  assert.equal(result.violations.length >= 4, true)
  assert.deepEqual(
    new Set(result.violations.map(({ code }) => code)),
    new Set([
      'BEARER_CREDENTIAL',
      'COOKIE_CREDENTIAL',
      'KNOWN_SECRET_VALUE',
      'SENSITIVE_FIELD_VALUE',
    ]),
  )
  assert.equal(JSON.stringify(result).includes(secret), false)
  assert.equal(JSON.stringify(result).includes('live-token-value'), false)
  assert.equal(JSON.stringify(result).includes('live-cookie'), false)
  assert.equal(JSON.stringify(result).includes('raw-database-password'), false)
})

test('runtime Supervisor injection probes reject exact payloads and retain Docker state', async () => {
  assert.deepEqual(
    SUPERVISOR_INJECTION_PROBES.map(({ id, payload, expectedCode }) => ({
      id,
      payload,
      expectedCode,
    })),
    [
      {
        id: 'extra-command',
        payload: { action: 'start', command: 'rm -rf /' },
        expectedCode: 'INVALID_REQUEST',
      },
      {
        id: 'path-as-action',
        payload: { action: '../docker-compose.yml' },
        expectedCode: 'INVALID_ACTION',
      },
      {
        id: 'service-selector',
        payload: { action: 'restart', service: 'postgres' },
        expectedCode: 'INVALID_REQUEST',
      },
    ],
  )

  const requests = []
  let fingerprintCalls = 0
  const audit = await runSupervisorInjectionProbes({
    sendRequest: async (payload) => {
      requests.push(payload)
      const probe = SUPERVISOR_INJECTION_PROBES[requests.length - 1]
      return {
        status: 400,
        body: { ok: false, error: { code: probe.expectedCode } },
      }
    },
    captureDockerStateFingerprint: async () => {
      fingerprintCalls += 1
      return { digest: 'stable-docker-state', containerCount: 5 }
    },
  })

  assert.deepEqual(requests, SUPERVISOR_INJECTION_PROBES.map(({ payload }) => payload))
  assert.equal(fingerprintCalls, 2)
  assert.equal(audit.every((entry) => entry.rejected === true), true)
  assert.deepEqual(
    audit.map(({ id, status, code }) => ({ id, status, code })),
    SUPERVISOR_INJECTION_PROBES.map(({ id, expectedCode }) => ({
      id,
      status: 400,
      code: expectedCode,
    })),
  )
  assert.equal(JSON.stringify(audit).includes('rm -rf'), false)

  await assert.rejects(
    runSupervisorInjectionProbes({
      sendRequest: async () => ({
        status: 200,
        body: { ok: true },
      }),
      captureDockerStateFingerprint: async () => ({
        digest: 'stable',
        containerCount: 5,
      }),
    }),
    /Supervisor injection probe failed closed/iu,
  )
  let changedCapture = 0
  await assert.rejects(
    runSupervisorInjectionProbes({
      sendRequest: async (payload) => {
        const probe = SUPERVISOR_INJECTION_PROBES.find(
          (candidate) => candidate.payload.action === payload.action
            && Object.keys(candidate.payload).length === Object.keys(payload).length,
        )
        return {
          status: 400,
          body: { ok: false, error: { code: probe.expectedCode } },
        }
      },
      captureDockerStateFingerprint: async () => ({
        digest: `docker-${changedCapture += 1}`,
        containerCount: 5,
      }),
    }),
    /Docker state changed/iu,
  )
})

test('the real loopback Supervisor rejects exact Task 5 payloads before any spawn', async (t) => {
  const token = 'task-5-supervisor-token-0123456789abcdef'
  const spawnCalls = []
  const relayLifecycle = {
    async start() {},
    async stop() {},
    isRunning() {
      return false
    },
  }
  const supervisor = createValidationSupervisor({
    token,
    listenPort: 0,
    spawnImpl: (...args) => {
      spawnCalls.push(args)
      throw new Error('Supervisor must reject injection before spawn')
    },
    relayLifecycle,
  })
  await supervisor.start()
  t.after(() => supervisor.close())
  const { port } = supervisor.address()

  for (const probe of SUPERVISOR_INJECTION_PROBES) {
    const response = await fetch(
      `http://127.0.0.1:${port}/validation-supervisor`,
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${token}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify(probe.payload),
      },
    )
    assert.equal(response.status, 400, probe.id)
    assert.deepEqual(await response.json(), {
      ok: false,
      error: { code: probe.expectedCode },
    })
  }
  assert.equal(spawnCalls.length, 0)
})

test('before/after probe wraps the real smoke seam and fails on main-data drift', async () => {
  const calls = []
  const stableMain = simpleMainFingerprint('main-stable')
  const probe = createTradingLabRuntimeIsolationProbe({
    collectValidationIdentity: async () => {
      calls.push('identity')
      return validRuntimeIdentity()
    },
    runNetworkProbes: async () => {
      calls.push('network')
      return [{ id: 'all-fixed-probes', passed: true }]
    },
    runInjectionProbes: async () => {
      calls.push('injection')
      return [{ id: 'all-fixed-injections', rejected: true }]
    },
    captureMainFingerprint: async () => {
      calls.push('fingerprint')
      return structuredClone(stableMain)
    },
    scanReports: ({ reports }) => {
      calls.push('reports')
      assert.equal(reports.length, 1)
      return [{ reportName: 'download.json', scanned: true, violations: [] }]
    },
  })

  const before = await probe.before()
  assert.equal(before.passed, true)
  const after = await probe.after({
    reports: [{ name: 'download.json', document: { authorization: '[REDACTED]' } }],
  })
  assert.equal(after.passed, true)
  assert.deepEqual(calls, [
    'identity',
    'network',
    'injection',
    'fingerprint',
    'fingerprint',
    'identity',
    'reports',
  ])
  await assert.rejects(
    probe.after({ reports: [{ name: 'second.json', document: {} }] }),
    /already completed/iu,
  )

  let fingerprintNumber = 0
  const drifting = createTradingLabRuntimeIsolationProbe({
    collectValidationIdentity: async () => validRuntimeIdentity(),
    runNetworkProbes: async () => [],
    runInjectionProbes: async () => [],
    captureMainFingerprint: async () =>
      simpleMainFingerprint(`main-${fingerprintNumber += 1}`),
    scanReports: () => [],
  })
  await drifting.before()
  await assert.rejects(
    drifting.after({ reports: [{ name: 'download.json', document: {} }] }),
    /protected PostgreSQL fingerprint changed/iu,
  )
})

test('convenience verifier always executes the smoke between before and after', async () => {
  const calls = []
  const result = await verifyTradingLabRuntimeIsolation({
    runValidation: async () => {
      calls.push('run')
      return {
        scenarioCount: 24,
        reports: [{ name: 'download.json', document: { authorization: '[REDACTED]' } }],
      }
    },
    collectValidationIdentity: async () => {
      calls.push('identity')
      return validRuntimeIdentity()
    },
    runNetworkProbes: async () => {
      calls.push('network')
      return []
    },
    runInjectionProbes: async () => {
      calls.push('injection')
      return []
    },
    captureMainFingerprint: async () => {
      calls.push('fingerprint')
      return simpleMainFingerprint('stable')
    },
    scanReports: ({ reports }) => {
      calls.push(`reports:${reports.length}`)
      return [{ reportName: 'download.json', scanned: true, violations: [] }]
    },
  })

  assert.equal(result.runResult.scenarioCount, 24)
  assert.equal(result.isolation.passed, true)
  assert.deepEqual(calls, [
    'identity',
    'network',
    'injection',
    'fingerprint',
    'run',
    'fingerprint',
    'identity',
    'reports:1',
  ])
})

test('convenience verifier always executes postflight and preserves primary plus postflight errors', async () => {
  const primary = new Error('browser smoke primary failure')
  const postflight = new Error('postflight isolation failure')
  const calls = []
  let fingerprintCall = 0

  await assert.rejects(
    verifyTradingLabRuntimeIsolation({
      reports: [{ name: 'partial.json', document: {} }],
      ownership: emptyOwnership(),
      runValidation: async () => {
        calls.push('run')
        throw primary
      },
      collectValidationIdentity: async () => validRuntimeIdentity(),
      runNetworkProbes: async () => [],
      runInjectionProbes: async () => [],
      captureMainFingerprint: async () => {
        fingerprintCall += 1
        if (fingerprintCall === 2) throw postflight
        return splitMainFingerprint()
      },
      scanReports: () => [],
    }),
    (failure) => {
      assert.equal(failure instanceof AggregateError, true)
      assert.deepEqual(failure.errors, [primary, postflight])
      return true
    },
  )
  assert.deepEqual(calls, ['run'])

  const postflightCalls = []
  await assert.rejects(
    verifyTradingLabRuntimeIsolation({
      reports: [{ name: 'partial.json', document: {} }],
      ownership: emptyOwnership(),
      runValidation: async () => {
        postflightCalls.push('run')
        throw primary
      },
      collectValidationIdentity: async () => {
        postflightCalls.push('identity')
        return validRuntimeIdentity()
      },
      runNetworkProbes: async () => [],
      runInjectionProbes: async () => [],
      captureMainFingerprint: async () => {
        postflightCalls.push('fingerprint')
        return splitMainFingerprint()
      },
      scanReports: () => {
        postflightCalls.push('reports')
        return []
      },
    }),
    (failure) => failure === primary,
  )
  assert.deepEqual(postflightCalls, [
    'identity',
    'fingerprint',
    'run',
    'fingerprint',
    'identity',
    'reports',
  ])

  const missingEvidenceCalls = []
  await assert.rejects(
    verifyTradingLabRuntimeIsolation({
      runValidation: async () => {
        missingEvidenceCalls.push('run')
        throw primary
      },
      collectValidationIdentity: async () => {
        missingEvidenceCalls.push('identity')
        return validRuntimeIdentity()
      },
      runNetworkProbes: async () => [],
      runInjectionProbes: async () => [],
      captureMainFingerprint: async () => {
        missingEvidenceCalls.push('fingerprint')
        return splitMainFingerprint()
      },
      scanReports: () => {
        throw new Error('report scan must not run without evidence')
      },
    }),
    (failure) => {
      assert.equal(failure instanceof AggregateError, true)
      assert.equal(failure.errors[0], primary)
      assert.match(failure.errors[1].message, /report evidence is required/iu)
      return true
    },
  )
  assert.deepEqual(missingEvidenceCalls, [
    'identity',
    'fingerprint',
    'run',
    'fingerprint',
    'identity',
  ])
})

test('publishes the focused Task 5 contract command', () => {
  const packageJson = JSON.parse(
    readFileSync(resolve(platformRoot, 'package.json'), 'utf8'),
  )
  assert.equal(
    packageJson.scripts['test:trading-lab-runtime-isolation'],
    'node --test scripts/verify-trading-lab-runtime-isolation.test.mjs',
  )
})

function validRuntimeIdentity() {
  const disabledRuntimeFlags = Object.fromEntries([
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
  ].map((name) => [name, 'false']))
  return {
    composeProject: 'fx-trading-validation',
    composeService: 'validation-backend',
    running: true,
    networks: ['fx-trading-validation-internal'],
    networkInternal: true,
    publishedPorts: false,
    springProfiles: 'validation',
    executionMode: 'demo',
    disabledRuntimeFlags,
    requiredSecretsPresent: {
      VALIDATION_DATABASE_PASSWORD: true,
      VALIDATION_REDIS_PASSWORD: true,
      VALIDATION_JWT_SECRET: true,
      VALIDATION_CONFIG_ENCRYPTION_KEY: true,
      VALIDATION_INTERNAL_SECRET: true,
    },
    externalEndpointOverrides: [],
  }
}

function simpleMainFingerprint(digest) {
  return splitMainFingerprint({
    protectedChecksum: sha256(digest).slice(0, 32),
  })
}

function splitPostgresOutput({
  scenarioPayloads = [],
  requestLogPayloads = [],
  extraControlTable,
} = {}) {
  const protectedKey = Buffer.from('core.wallet_balances').toString('hex')
  const lines = [
    `PROTECTED:${protectedKey}\t1\t${'a'.repeat(32)}`,
    'AUTH_SESSION_COUNT\t0',
  ]
  for (const [schema, table] of CONTROL_TABLES) {
    const key = `${schema}.${table}`
    const count = key === 'trading_lab.scenarios'
      ? scenarioPayloads.length
      : key === 'audit.request_logs'
        ? requestLogPayloads.length
        : 0
    lines.push(
      `CONTROL_TABLE:${Buffer.from(key).toString('hex')}\t${count}`,
    )
  }
  if (extraControlTable) {
    lines.push(
      `CONTROL_TABLE:${Buffer.from(extraControlTable).toString('hex')}\t0`,
    )
  }
  for (const payload of scenarioPayloads) {
    lines.push(
      `CONTROL_ROW:${Buffer.from('trading_lab.scenarios').toString('hex')}`
      + `\t${Buffer.from(JSON.stringify(payload)).toString('hex')}`,
    )
  }
  for (const payload of requestLogPayloads) {
    lines.push(
      `CONTROL_ROW:${Buffer.from('audit.request_logs').toString('hex')}`
      + `\t${Buffer.from(JSON.stringify(payload)).toString('hex')}`,
    )
  }
  return lines.join('\n')
}

const CONTROL_TABLES = Object.freeze([
  ['audit', 'audit_logs'],
  ['audit', 'request_logs'],
  ['trading_lab', 'audit_events'],
  ['trading_lab', 'report_appends'],
  ['trading_lab', 'report_chunks'],
  ['trading_lab', 'reports'],
  ['trading_lab', 'run_events'],
  ['trading_lab', 'run_transitions'],
  ['trading_lab', 'runs'],
  ['trading_lab', 'scenarios'],
])

function emptyOwnership() {
  return {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [],
    requestLogObservations: [],
  }
}

function ownedControlPlane() {
  return {
    createdRuns: [{
      scenarioId: '11111111-1111-4111-8111-111111111111',
      runId: '22222222-2222-4222-8222-222222222222',
      reportId: '33333333-3333-4333-8333-333333333333',
      expectedTerminalState: 'COMPLETED',
    }],
    referencedRunIds: [],
    tradingLabRequestIds: [
      '44444444-4444-4444-8444-444444444444',
      '55555555-5555-4555-8555-555555555555',
      '66666666-6666-4666-8666-666666666666',
    ],
    requestLogObservations: [
      requestLogObservation(
        '44444444-4444-4444-8444-444444444444',
        'POST',
        '/api/admin/trading-lab/scenarios',
      ),
      requestLogObservation(
        '55555555-5555-4555-8555-555555555555',
        'POST',
        '/api/admin/trading-lab/scenarios/'
          + '11111111-1111-4111-8111-111111111111/runs',
      ),
      requestLogObservation(
        '66666666-6666-4666-8666-666666666666',
        'GET',
        '/api/admin/trading-lab/environment',
      ),
    ],
  }
}

function ownedControlRows(ownership) {
  const [{ scenarioId, runId, reportId, expectedTerminalState }] =
    ownership.createdRuns
  const actorId = '77777777-7777-4777-8777-777777777777'
  const [scenarioRequestId, runRequestId, environmentRequestId] =
    ownership.tradingLabRequestIds
  const scenarioAudit = auditTuple({
    requestId: scenarioRequestId,
    action: 'TRADING_LAB_SCENARIO_CREATE',
    targetType: 'TRADING_LAB_SCENARIO',
    targetId: scenarioId,
    actorId,
    result: 'SUCCESS',
    clientIp: '127.0.0.1',
    scenarioId,
    runId: null,
    detailsMd5: '1'.repeat(32),
  })
  const runAudit = auditTuple({
    requestId: runRequestId,
    action: 'TRADING_LAB_RUN_CREATE',
    targetType: 'TRADING_LAB_RUN',
    targetId: runId,
    actorId,
    result: 'SUCCESS',
    clientIp: '127.0.0.1',
    scenarioId,
    runId,
    detailsMd5: '2'.repeat(32),
  })
  const environmentAudit = auditTuple({
    requestId: environmentRequestId,
    action: 'TRADING_LAB_ENVIRONMENT_CHECKED',
    targetType: 'TRADING_LAB_REQUEST',
    targetId: environmentRequestId,
    actorId,
    result: 'SUCCESS',
    clientIp: '127.0.0.1',
    scenarioId: null,
    runId: null,
    detailsMd5: '3'.repeat(32),
  })
  return [
    controlRow('trading_lab', 'scenarios', scenarioId),
    controlRow(
      'trading_lab',
      'reports',
      reportId,
      { scenarioId },
    ),
    controlRow(
      'trading_lab',
      'runs',
      runId,
      {
        scenarioId,
        reportId,
        state: expectedTerminalState,
      },
    ),
    controlRow(
      'trading_lab',
      'run_transitions',
      '88888888-8888-4888-8888-888888888888',
      { runId },
    ),
    controlRow(
      'trading_lab',
      'run_events',
      '99999999-9999-4999-8999-999999999999',
      { runId },
    ),
    controlRow(
      'trading_lab',
      'report_chunks',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      { reportId },
    ),
    controlRow(
      'trading_lab',
      'report_appends',
      `${reportId}:METADATA:-1`,
      { reportId },
    ),
    auditControlRow(
      'trading_lab',
      'audit_events',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1',
      scenarioAudit,
    ),
    auditControlRow(
      'audit',
      'audit_logs',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
      scenarioAudit,
    ),
    auditControlRow(
      'trading_lab',
      'audit_events',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3',
      runAudit,
    ),
    auditControlRow(
      'audit',
      'audit_logs',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb4',
      runAudit,
    ),
    auditControlRow(
      'trading_lab',
      'audit_events',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5',
      environmentAudit,
    ),
    auditControlRow(
      'audit',
      'audit_logs',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb6',
      environmentAudit,
    ),
    requestLogControlRow(
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb7',
      scenarioRequestId,
      'POST',
      '/api/admin/trading-lab/scenarios',
    ),
    requestLogControlRow(
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb8',
      runRequestId,
      'POST',
      `/api/admin/trading-lab/scenarios/${scenarioId}/runs`,
    ),
    requestLogControlRow(
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb9',
      environmentRequestId,
      'GET',
      '/api/admin/trading-lab/environment',
    ),
  ]
}

function auditTuple(value) {
  return { ...value }
}

function auditControlRow(schema, table, identity, tuple) {
  return controlRow(schema, table, identity, {
    scenarioId: tuple.scenarioId,
    runId: tuple.runId,
    requestId: tuple.requestId,
    action: tuple.action,
    auditTupleSha256: sha256(canonicalJson(tuple)),
  })
}

function controlRow(schema, table, identity, {
  scenarioId,
  runId,
  reportId,
  state,
  requestId,
  action,
  auditTupleSha256,
  method,
  path,
  requestLogRoute,
  requestTupleSha256,
  routeStatus,
  resourceId,
  statusCode,
} = {}) {
  return {
    schema,
    table,
    identitySha256: sha256(identity),
    rowSha256: sha256(`row:${schema}.${table}:${identity}`),
    ...(scenarioId === undefined
      ? {}
      : { scenarioIdSha256: hashNullable(scenarioId) }),
    ...(runId === undefined ? {} : { runIdSha256: hashNullable(runId) }),
    ...(reportId === undefined
      ? {}
      : { reportIdSha256: hashNullable(reportId) }),
    ...(state === undefined ? {} : { stateSha256: sha256(state) }),
    ...(requestId === undefined
      ? {}
      : { requestIdSha256: sha256(requestId) }),
    ...(action === undefined ? {} : { actionSha256: sha256(action) }),
    ...(auditTupleSha256 === undefined ? {} : { auditTupleSha256 }),
    ...(method === undefined ? {} : { methodSha256: sha256(method) }),
    ...(path === undefined ? {} : { pathSha256: sha256(path) }),
    ...(requestLogRoute === undefined
      ? {}
      : { routeSha256: sha256(requestLogRoute) }),
    ...(requestTupleSha256 === undefined
      ? {}
      : { requestTupleSha256 }),
    ...(routeStatus === undefined
      ? {}
      : { routeStatusSha256: sha256(routeStatus) }),
    ...(resourceId === undefined
      ? {}
      : { resourceIdSha256: hashNullable(resourceId) }),
    ...(statusCode === undefined
      ? {}
      : { statusCodeSha256: sha256(String(statusCode)) }),
  }
}

function requestLogControlRow(
  identity,
  requestId,
  method,
  path,
  statusCode = 200,
) {
  const route = requestLogRoute(method, path, statusCode)
  return controlRow('audit', 'request_logs', identity, {
    requestId,
    method,
    path,
    requestLogRoute: route.name,
    requestTupleSha256: sha256(canonicalJson({
      method,
      path,
      statusCode,
    })),
    routeStatus: `${route.name}:${statusCode}`,
    resourceId: route.resourceId,
    statusCode,
  })
}

function requestLogObservation(
  requestId,
  method,
  path,
  statusCode = 200,
) {
  return {
    requestId,
    requestTupleSha256: sha256(canonicalJson({
      method,
      path,
      statusCode,
    })),
  }
}

function requestLogRequestObservation(requestId, method, path) {
  return {
    requestId,
    requestMethodSha256: sha256(method),
    requestPathSha256: sha256(path),
  }
}

function requestLogRoute(method, path, statusCode = 200) {
  const uuidPattern =
    '([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})'
  const routes = [
    ['POST', /^\/api\/auth\/login$/u, 'AUTH_LOGIN'],
    ['GET', /^\/api\/admin\/dashboard\/summary$/u, 'DASHBOARD_SUMMARY'],
    ['GET', /^\/api\/admin\/trading-lab\/config$/u, 'CONFIG'],
    ['GET', /^\/api\/admin\/trading-lab\/environment$/u, 'ENVIRONMENT_STATUS'],
    ['POST', /^\/api\/admin\/trading-lab\/environment\/(?:start|stop|restart)$/u, 'ENVIRONMENT_CONTROL'],
    ['POST', /^\/api\/admin\/trading-lab\/scenarios$/u, 'SCENARIO_CREATE'],
    ['GET', new RegExp(`^/api/admin/trading-lab/scenarios/${uuidPattern}$`, 'u'), 'SCENARIO_GET'],
    ['POST', new RegExp(`^/api/admin/trading-lab/scenarios/${uuidPattern}/runs$`, 'u'), 'RUN_CREATE'],
    ['GET', new RegExp(`^/api/admin/trading-lab/runs/${uuidPattern}$`, 'u'), 'RUN_GET'],
    ['GET', new RegExp(`^/api/admin/trading-lab/runs/${uuidPattern}/events$`, 'u'), 'RUN_EVENTS'],
    ['POST', new RegExp(`^/api/admin/trading-lab/runs/${uuidPattern}/(?:pause|resume|cancel)$`, 'u'), 'RUN_CONTROL'],
    ['GET', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}$`, 'u'), 'REPORT_GET'],
    ['GET', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}/download$`, 'u'), 'REPORT_DOWNLOAD'],
    ['DELETE', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}$`, 'u'), 'REPORT_DELETE'],
    ['POST', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}/permanent$`, 'u'), 'REPORT_PERMANENT'],
    ['GET', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}/print-info$`, 'u'), 'REPORT_PRINT_INFO'],
    ['POST', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}/print-confirmation$`, 'u'), 'REPORT_PRINT_CONFIRMATION'],
    ['GET', new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}/print$`, 'u'), 'REPORT_PRINT'],
  ]
  for (const [expectedMethod, pattern, name] of routes) {
    if (method !== expectedMethod) continue
    const match = pattern.exec(path)
    if (match !== null) return { name, resourceId: match[1] ?? null }
  }
  if (method === 'OPTIONS' && statusCode === 200) {
    const staticPaths = new Set([
      '/api/auth/login',
      '/api/admin/dashboard/summary',
      '/api/admin/trading-lab/config',
      '/api/admin/trading-lab/environment',
      '/api/admin/trading-lab/environment/start',
      '/api/admin/trading-lab/environment/stop',
      '/api/admin/trading-lab/environment/restart',
      '/api/admin/trading-lab/scenarios',
    ])
    if (staticPaths.has(path)) {
      return { name: 'PREFLIGHT_STATIC', resourceId: null }
    }
    const preflightRoutes = [
      [
        new RegExp(`^/api/admin/trading-lab/scenarios/${uuidPattern}(?:/runs)?$`, 'u'),
        'PREFLIGHT_SCENARIO',
      ],
      [
        new RegExp(`^/api/admin/trading-lab/runs/${uuidPattern}(?:/events|/(?:pause|resume|cancel))?$`, 'u'),
        'PREFLIGHT_RUN',
      ],
      [
        new RegExp(`^/api/admin/trading-lab/reports/${uuidPattern}(?:/download|/permanent|/print-info|/print-confirmation|/print)?$`, 'u'),
        'PREFLIGHT_REPORT',
      ],
    ]
    for (const [pattern, name] of preflightRoutes) {
      const match = pattern.exec(path)
      if (match !== null) {
        return { name, resourceId: match[1] }
      }
    }
  }
  throw new Error(`Unsupported test request-log route: ${method} ${path}`)
}

function authSessionRow(identity, userId) {
  return {
    identitySha256: sha256(identity),
    userIdSha256: sha256(userId),
    rowSha256: sha256(`auth.user_sessions:${identity}`),
  }
}

function hashNullable(value) {
  return value === null ? null : sha256(value)
}

function splitMainFingerprint({
  protectedChecksum = 'a'.repeat(32),
  redisSha256 = sha256('empty-main-redis'),
  controlRows = [],
  authSessionRows = [],
  extraKnownTables = [],
} = {}) {
  const knownTables = [
    ...CONTROL_TABLES.map(([schema, table]) => ({ schema, table })),
    ...extraKnownTables,
  ].sort(tableOrder)
  const tables = knownTables.map(({ schema, table }) => ({
    schema,
    table,
    count: String(controlRows.filter(
      (row) => row.schema === schema && row.table === table,
    ).length),
  }))
  const sortedRows = structuredClone(controlRows).sort((left, right) =>
    `${left.schema}.${left.table}:${left.identitySha256}`
      .localeCompare(`${right.schema}.${right.table}:${right.identitySha256}`))
  const protectedTables = [{
    schema: 'core',
    table: 'wallet_balances',
    count: '1',
    checksum: protectedChecksum,
  }]
  const protectedPostgres = {
    tables: protectedTables,
    sha256: sha256(canonicalJson(protectedTables)),
  }
  const mainRedis = {
    keyCount: 0,
    sha256: redisSha256,
  }
  const authSessions = {
    rows: structuredClone(authSessionRows).sort((left, right) =>
      left.identitySha256.localeCompare(right.identitySha256)),
  }
  authSessions.sha256 = sha256(canonicalJson(authSessions.rows))
  const controlValue = { knownTables, tables, rows: sortedRows }
  const controlPlane = {
    ...controlValue,
    sha256: sha256(canonicalJson(controlValue)),
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

function tableOrder(left, right) {
  return `${left.schema}.${left.table}`
    .localeCompare(`${right.schema}.${right.table}`)
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`
  }
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function numberedUuid(value) {
  const hex = Number(value).toString(16)
  return `${hex.padStart(8, '0')}-0000-4000-8000-${hex.padStart(12, '0')}`
}
