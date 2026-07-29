import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import test from 'node:test'

import {
  LARGE_REPORT_THRESHOLD_BYTES,
  prepareTradingLabLargeFixture,
} from './prepare-trading-lab-large-fixture.mjs'

const SCENARIO_ID = '11111111-1111-4111-8111-111111111111'
const RUN_ID = '22222222-2222-4222-8222-222222222222'
const REPORT_ID = '33333333-3333-4333-8333-333333333333'
const AUTH_USER_ID = '44444444-4444-4444-8444-444444444444'
const FOREIGN_AUTH_USER_ID = '55555555-5555-4555-8555-555555555555'
const ACCESS_TOKEN = 'fixture-access-token'

test('prepares a real public-API large fixture and proves canonical IDs and >50 MiB metadata', async () => {
  const calls = []
  const uuids = uuidSequence()
  const result = await prepareTradingLabLargeFixture({
    apiUrl: 'http://127.0.0.1:18086',
    fetchImpl: fixtureFetch(calls),
    randomUuid: () => uuids.shift(),
    sleep: async () => {},
    timeoutMs: 5_000,
    ownedAuthUserIds: [AUTH_USER_ID],
  })

  assert.deepEqual(result, {
    authUserId: AUTH_USER_ID,
    scenarioId: SCENARIO_ID,
    runId: RUN_ID,
    reportId: REPORT_ID,
    uncompressedBytes: LARGE_REPORT_THRESHOLD_BYTES + 1,
    actionCount: 40,
    requestIds: [
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      'ffffffff-ffff-4fff-8fff-ffffffffffff',
      '12121212-1212-4212-8212-121212121212',
    ],
    tradingLabRequestIds: [
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      'ffffffff-ffff-4fff-8fff-ffffffffffff',
      '12121212-1212-4212-8212-121212121212',
    ],
    requestLogObservations: [
      requestLogObservation(
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        'POST',
        '/api/auth/login',
      ),
      requestLogObservation(
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        'GET',
        '/api/admin/trading-lab/config',
      ),
      requestLogObservation(
        'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        'POST',
        '/api/admin/trading-lab/scenarios',
      ),
      requestLogObservation(
        'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        'POST',
        `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`,
      ),
      requestLogObservation(
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        'GET',
        `/api/admin/trading-lab/runs/${RUN_ID}`,
      ),
      requestLogObservation(
        'ffffffff-ffff-4fff-8fff-ffffffffffff',
        'GET',
        `/api/admin/trading-lab/runs/${RUN_ID}`,
      ),
      requestLogObservation(
        '12121212-1212-4212-8212-121212121212',
        'GET',
        `/api/admin/trading-lab/reports/${REPORT_ID}`,
      ),
    ],
  })
  assert.deepEqual(
    calls.map(({ method, path }) => `${method} ${path}`),
    [
      'POST /api/auth/login',
      'GET /api/admin/trading-lab/config',
      'POST /api/admin/trading-lab/scenarios',
      `POST /api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`,
      `GET /api/admin/trading-lab/runs/${RUN_ID}`,
      `GET /api/admin/trading-lab/runs/${RUN_ID}`,
      `GET /api/admin/trading-lab/reports/${REPORT_ID}`,
    ],
  )

  const createScenario = calls[2]
  assert.equal(createScenario.body.scenario.timeline.length, 40)
  assert.equal(createScenario.body.scenario.marketPath.instruments[0].symbol, 'BTCUSDT')
  assert.equal(createScenario.body.scenario.configSnapshotHash, 'a'.repeat(64))
  assert.equal(createScenario.body.configSnapshotHash, 'a'.repeat(64))
  assert.equal(createScenario.body.scenario.timeline[0].id, 'large-spot-buy-0')
  assert.equal(createScenario.body.scenario.timeline[39].id, 'large-spot-buy-39')
  assert.equal(
    createScenario.body.scenario.timeline.every(
      ({ trigger }) => trigger.atSecond === 1,
    ),
    true,
  )

  for (const call of calls.slice(1)) {
    assert.equal(call.headers.authorization, `Bearer ${ACCESS_TOKEN}`)
  }
  assert.equal(
    new Set(calls.map(({ headers }) => headers['x-request-id'])).size,
    calls.length,
  )
  for (const call of calls) {
    assert.match(
      call.headers['x-request-id'],
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u,
    )
    assert.equal(call.responseRequestId, call.headers['x-request-id'])
  }
})

test('publishes provisional ownership before a terminal timeout', async () => {
  const calls = []
  const recoveries = []
  const uuids = uuidSequence()

  await assert.rejects(
    prepareTradingLabLargeFixture({
      apiUrl: 'http://127.0.0.1:18086',
      fetchImpl: fixtureFetch(calls, { neverTerminal: true }),
      randomUuid: () => uuids.shift(),
      sleep: () => new Promise((resolve) => setTimeout(resolve, 25)),
      timeoutMs: 10,
      ownedAuthUserIds: [AUTH_USER_ID],
      onRecovery(value) {
        recoveries.push(value)
      },
    }),
    /did not reach a terminal state/iu,
  )

  assert.ok(recoveries.length >= 1)
  const recovery = recoveries.at(-1)
  assert.deepEqual(
    {
      authUserId: recovery.authUserId,
      scenarioId: recovery.scenarioId,
      runId: recovery.runId,
      reportId: recovery.reportId,
      actionCount: recovery.actionCount,
      provisional: recovery.provisional,
    },
    {
      authUserId: AUTH_USER_ID,
      scenarioId: SCENARIO_ID,
      runId: RUN_ID,
      reportId: REPORT_ID,
      actionCount: 40,
      provisional: true,
    },
  )
  assert.equal(Object.isFrozen(recovery), true)
  assert.equal(Object.isFrozen(recovery.requestIds), true)
  assert.equal(Object.isFrozen(recovery.tradingLabRequestIds), true)
  assert.equal(Object.isFrozen(recovery.requestLogObservations), true)
  assert.ok(recovery.requestIds.length >= 4)
  assert.deepEqual(
    recovery.tradingLabRequestIds,
    recovery.requestIds.slice(1),
  )
  assert.deepEqual(
    recovery.requestLogObservations.map(({ requestId }) => requestId),
    recovery.requestIds,
  )
})

test('publishes an accepted run root before validating its response semantics', async () => {
  const recoveries = []
  const uuids = uuidSequence()

  await assert.rejects(
    prepareTradingLabLargeFixture({
      apiUrl: 'http://127.0.0.1:18086',
      fetchImpl: fixtureFetch([], { acceptedTotalTicks: 39 }),
      randomUuid: () => uuids.shift(),
      sleep: async () => {},
      timeoutMs: 5_000,
      ownedAuthUserIds: [AUTH_USER_ID],
      onRecovery(value) {
        recoveries.push(value)
      },
    }),
    /accepted run tick count is inconsistent/iu,
  )

  assert.ok(recoveries.length >= 1)
  assert.deepEqual(
    {
      scenarioId: recoveries.at(-1).scenarioId,
      runId: recoveries.at(-1).runId,
      reportId: recoveries.at(-1).reportId,
      provisional: recoveries.at(-1).provisional,
    },
    {
      scenarioId: SCENARIO_ID,
      runId: RUN_ID,
      reportId: REPORT_ID,
      provisional: true,
    },
  )
})

test('retains a standalone scenario and request-only evidence when run admission has no response', async () => {
  const recoveries = []
  const uuids = uuidSequence()
  const transportFailure = new Error('run admission transport failed')

  await assert.rejects(
    prepareTradingLabLargeFixture({
      apiUrl: 'http://127.0.0.1:18086',
      fetchImpl: fixtureFetch([], {
        throwRunRequest: transportFailure,
      }),
      randomUuid: () => uuids.shift(),
      sleep: async () => {},
      timeoutMs: 5_000,
      ownedAuthUserIds: [AUTH_USER_ID],
      onRecovery(value) {
        recoveries.push(value)
      },
    }),
    (failure) => failure === transportFailure,
  )

  assert.ok(recoveries.length >= 1)
  const recovery = recoveries.at(-1)
  assert.equal(recovery.authUserId, AUTH_USER_ID)
  assert.equal(recovery.scenarioId, SCENARIO_ID)
  assert.equal(recovery.provisional, true)
  assert.equal(Object.hasOwn(recovery, 'runId'), false)
  assert.equal(Object.hasOwn(recovery, 'reportId'), false)
  assert.deepEqual(
    recovery.requestLogObservations.at(-1),
    requestOnlyObservation(
      recovery.requestIds.at(-1),
      'POST',
      `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`,
    ),
  )
})

test('awaits recovery publication so snapshots cannot overtake each other', async () => {
  const uuids = uuidSequence()
  const seen = []
  let releaseRecovery
  const recoveryGate = new Promise((resolve) => {
    releaseRecovery = resolve
  })
  let settled = false
  const preparing = prepareTradingLabLargeFixture({
    apiUrl: 'http://127.0.0.1:18086',
    fetchImpl: fixtureFetch([]),
    randomUuid: () => uuids.shift(),
    sleep: async () => {},
    timeoutMs: 5_000,
    ownedAuthUserIds: [AUTH_USER_ID],
    async onRecovery(value) {
      await recoveryGate
      seen.push(value)
    },
  })
  preparing.finally(() => {
    settled = true
  })

  await new Promise((resolve) => setTimeout(resolve, 50))
  const settledBeforeRelease = settled
  releaseRecovery()
  await preparing

  assert.equal(settledBeforeRelease, false)
  assert.ok(seen.length >= 1)
  assert.equal(seen.at(-1).expectedTerminalState, 'COMPLETED')
})

test('fails closed for non-canonical identities and reports at or below 50 MiB', async (t) => {
  await t.test('duplicate generated request ID', async () => {
    const calls = []
    const uuids = uuidSequence()
    uuids[1] = uuids[0]
    await assert.rejects(
      prepareTradingLabLargeFixture({
        apiUrl: 'http://127.0.0.1:18086',
        fetchImpl: fixtureFetch(calls),
        randomUuid: () => uuids.shift(),
        sleep: async () => {},
        timeoutMs: 5_000,
        ownedAuthUserIds: [AUTH_USER_ID],
      }),
      /request IDs must be unique/iu,
    )
  })

  await t.test('non-canonical auth user ID', async () => {
    const calls = []
    const uuids = uuidSequence()
    await assert.rejects(
      prepareTradingLabLargeFixture({
        apiUrl: 'http://127.0.0.1:18086',
        fetchImpl: fixtureFetch(calls, { authUserId: 'NOT-A-UUID' }),
        randomUuid: () => uuids.shift(),
        sleep: async () => {},
        timeoutMs: 5_000,
        ownedAuthUserIds: [AUTH_USER_ID],
      }),
      /auth user ID.*canonical UUID/iu,
    )
  })

  await t.test('poll response request correlation drift', async () => {
    const calls = []
    const uuids = uuidSequence()
    await assert.rejects(
      prepareTradingLabLargeFixture({
        apiUrl: 'http://127.0.0.1:18086',
        fetchImpl: fixtureFetch(calls, { driftRequestIndex: 4 }),
        randomUuid: () => uuids.shift(),
        sleep: async () => {},
        timeoutMs: 5_000,
        ownedAuthUserIds: [AUTH_USER_ID],
      }),
      /request correlation drifted/iu,
    )
  })

  await t.test('non-canonical run ID', async () => {
    const calls = []
    const uuids = uuidSequence()
    await assert.rejects(
      prepareTradingLabLargeFixture({
        apiUrl: 'http://127.0.0.1:18086',
        fetchImpl: fixtureFetch(calls, {
          runId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'.toUpperCase(),
        }),
        randomUuid: () => uuids.shift(),
        sleep: async () => {},
        timeoutMs: 5_000,
        ownedAuthUserIds: [AUTH_USER_ID],
      }),
      /run ID.*canonical UUID/iu,
    )
  })

  await t.test('report is exactly 50 MiB', async () => {
    const calls = []
    const uuids = uuidSequence()
    await assert.rejects(
      prepareTradingLabLargeFixture({
        apiUrl: 'http://127.0.0.1:18086',
        fetchImpl: fixtureFetch(calls, {
          uncompressedBytes: LARGE_REPORT_THRESHOLD_BYTES,
        }),
        randomUuid: () => uuids.shift(),
        sleep: async () => {},
        timeoutMs: 5_000,
        ownedAuthUserIds: [AUTH_USER_ID],
      }),
      /> 50 MiB/iu,
    )
  })
})

test('rejects a foreign fixture login before any Trading Lab request', async () => {
  const calls = []
  const uuids = uuidSequence()

  await assert.rejects(
    prepareTradingLabLargeFixture({
      apiUrl: 'http://127.0.0.1:18086',
      fetchImpl: fixtureFetch(calls, {
        authUserId: FOREIGN_AUTH_USER_ID,
      }),
      randomUuid: () => uuids.shift(),
      sleep: async () => {},
      timeoutMs: 5_000,
      ownedAuthUserIds: [AUTH_USER_ID],
    }),
    /auth user.*runner-owned/iu,
  )

  assert.deepEqual(
    calls.map(({ method, path }) => `${method} ${path}`),
    ['POST /api/auth/login'],
  )
})

test('rejects an invalid auth allowlist before making an HTTP request', async () => {
  const calls = []
  const uuids = uuidSequence()

  await assert.rejects(
    prepareTradingLabLargeFixture({
      apiUrl: 'http://127.0.0.1:18086',
      fetchImpl: fixtureFetch(calls),
      randomUuid: () => uuids.shift(),
      sleep: async () => {},
      timeoutMs: 5_000,
      ownedAuthUserIds: ['NOT-A-UUID'],
    }),
    /owned auth user IDs.*invalid/iu,
  )

  assert.equal(calls.length, 0)
})

function fixtureFetch(calls, overrides = {}) {
  const runId = overrides.runId ?? RUN_ID
  const bytes = overrides.uncompressedBytes
    ?? LARGE_REPORT_THRESHOLD_BYTES + 1
  let runPoll = 0
  return async (rawUrl, options = {}) => {
    const url = new URL(rawUrl)
    const method = options.method ?? 'GET'
    const headers = Object.fromEntries(
      new Headers(options.headers).entries(),
    )
    const body = options.body === undefined
      ? null
      : JSON.parse(options.body)
    const call = {
      method,
      path: url.pathname,
      headers,
      body,
      responseRequestId: null,
    }
    calls.push(call)

    if (
      overrides.throwRunRequest !== undefined
      && url.pathname
        === `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`
    ) {
      throw overrides.throwRunRequest
    }

    let data
    if (url.pathname === '/api/auth/login') {
      data = {
        userId: overrides.authUserId ?? AUTH_USER_ID,
        accessToken: ACCESS_TOKEN,
        authorities: [
          'TRADING_LAB_VIEW',
          'TRADING_LAB_EXECUTE',
          'SUPER_ADMIN',
        ],
      }
    } else if (url.pathname === '/api/admin/trading-lab/config') {
      data = config()
    } else if (url.pathname === '/api/admin/trading-lab/scenarios') {
      data = {
        id: SCENARIO_ID,
        status: 'DRAFT',
        version: 0,
        configSnapshotHash: 'a'.repeat(64),
      }
    } else if (
      url.pathname === `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`
    ) {
      data = {
        id: runId,
        scenarioId: SCENARIO_ID,
        reportId: REPORT_ID,
        state: 'QUEUED',
        totalTicks: overrides.acceptedTotalTicks ?? 40,
        processedTicks: 0,
      }
    } else if (url.pathname === `/api/admin/trading-lab/runs/${runId}`) {
      runPoll += 1
      const running = overrides.neverTerminal === true || runPoll === 1
      data = {
        id: runId,
        scenarioId: SCENARIO_ID,
        reportId: REPORT_ID,
        state: running ? 'RUNNING' : 'COMPLETED',
        totalTicks: 40,
        processedTicks: running ? 1 : 40,
      }
    } else if (
      url.pathname === `/api/admin/trading-lab/reports/${REPORT_ID}`
    ) {
      data = {
        id: REPORT_ID,
        runId,
        scenarioId: SCENARIO_ID,
        status: 'COMPLETED',
        uncompressedBytes: bytes,
      }
    } else {
      return response(404, null)
    }

    const requestId = headers['x-request-id']
    const responseRequestId = calls.length - 1 === overrides.driftRequestIndex
      ? '12121212-1212-4212-8212-121212121212'
      : requestId
    call.responseRequestId = responseRequestId ?? null
    return response(200, data, responseRequestId)
  }
}

function response(status, data, requestId = null) {
  return new Response(JSON.stringify({
    success: status >= 200 && status < 300,
    code: status >= 200 && status < 300 ? 'OK' : 'NOT_FOUND',
    message: status >= 200 && status < 300 ? 'success' : 'not found',
    data,
    timestamp: '2026-07-26T00:00:00Z',
  }), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...(requestId === null ? {} : { 'X-Request-Id': requestId }),
    },
  })
}

function requestLogObservation(requestId, method, path, statusCode = 200) {
  return {
    requestId,
    requestTupleSha256: createHash('sha256')
      .update(JSON.stringify({ method, path, statusCode }))
      .digest('hex'),
  }
}

function requestOnlyObservation(requestId, method, path) {
  return {
    requestId,
    requestMethodSha256: createHash('sha256')
      .update(method)
      .digest('hex'),
    requestPathSha256: createHash('sha256')
      .update(path)
      .digest('hex'),
  }
}

function config() {
  const executionPolicy = {
    matchingMode: 'SIMPLE',
    makerFeeRate: '0.0002',
    takerFeeRate: '0.0005',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  return {
    configSnapshot: {
      modelVersion: 'trading-lab-v1',
      symbolConfigVersion: 'fixture-symbols-v1',
      codeVersion: 'fixture-code-v1',
      executionPolicy,
      instruments: [{
        symbol: 'BTCUSDT',
        productType: 'CRYPTO_SPOT',
        baseAsset: 'BTC',
        quoteAsset: 'USDT',
        tickSize: '0.01',
        stepSize: '0.00000001',
      }],
    },
    configSnapshotHash: 'a'.repeat(64),
    modelVersion: 'trading-lab-v1',
  }
}

function uuidSequence() {
  return [
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    'ffffffff-ffff-4fff-8fff-ffffffffffff',
    '12121212-1212-4212-8212-121212121212',
  ]
}
