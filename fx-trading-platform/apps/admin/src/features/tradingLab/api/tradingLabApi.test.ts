import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'

import {
  TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
  controlTradingLabEnvironment,
  controlTradingLabRun,
  createTradingLabReportPrintConfirmation,
  createTradingLabRun,
  createTradingLabScenario,
  deleteTradingLabReport,
  getTradingLabEnvironment,
  getTradingLabReport,
  getTradingLabReportPrintInfo,
  getTradingLabRun,
  getTradingLabScenario,
  setTradingLabReportPermanent,
  type TradingLabRunCreateInput,
  type TradingLabScenarioCreateInput,
} from './tradingLabApi.ts'

const RUN_ID = '11111111-1111-4111-8111-111111111111'
const SCENARIO_ID = '22222222-2222-4222-8222-222222222222'
const REPORT_ID = '33333333-3333-4333-8333-33333333333a'
const ACTOR_ID = '44444444-4444-4444-8444-444444444444'
const TOKEN = 'explicit-admin-token'
const HASH = 'a'.repeat(64)
const NOW = '2026-07-25T00:00:00Z'

const originalFetch = globalThis.fetch
const originalLocalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'localStorage',
)

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalLocalStorage) {
    Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  } else {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
})

test('all JSON methods use the frozen endpoint allowlist and explicit token', async () => {
  const calls: Array<{
    url: string
    method: string
    authorization: string | null
    body: unknown
  }> = []
  globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
    const requestUrl = String(url)
    const method = init?.method ?? 'GET'
    calls.push({
      url: requestUrl,
      method,
      authorization: new Headers(init?.headers).get('Authorization'),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : null,
    })

    if (requestUrl === `/api/admin/trading-lab/runs/${RUN_ID}` && method === 'GET') {
      return jsonResponse(sampleRun())
    }
    if (requestUrl === `/api/admin/trading-lab/scenarios/${SCENARIO_ID}` && method === 'GET') {
      return jsonResponse(sampleScenario())
    }
    if (requestUrl === '/api/admin/trading-lab/scenarios' && method === 'POST') {
      return jsonResponse(sampleScenario())
    }
    if (
      requestUrl === `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`
      && method === 'POST'
    ) {
      return jsonResponse(sampleRun())
    }
    if (requestUrl === `/api/admin/trading-lab/runs/${RUN_ID}/pause` && method === 'POST') {
      return jsonResponse({
        runId: RUN_ID,
        state: 'RUNNING',
        runVersion: 4,
        pauseRequested: true,
        cancelRequested: false,
      })
    }
    if (requestUrl === '/api/admin/trading-lab/environment' && method === 'GET') {
      return jsonResponse({
        relayRunning: true,
        validationHealth: 'UP',
      })
    }
    if (
      requestUrl === '/api/admin/trading-lab/environment/restart'
      && method === 'POST'
    ) {
      return jsonResponse({
        action: 'restart',
        relayRunning: true,
      })
    }
    throw new Error(`Unexpected request: ${method} ${requestUrl}`)
  }) as typeof fetch

  const scenarioInput: TradingLabScenarioCreateInput = {
    name: 'Replay',
    description: null,
    negativeMode: false,
    seed: 'seed-1',
    modelVersion: 'model-v1',
    scenario: { seed: 'seed-1' },
    configSnapshot: { modelVersion: 'model-v1' },
    configSnapshotHash: HASH,
    expectedVersion: null,
  }
  const runInput: TradingLabRunCreateInput = {
    scenarioVersion: 3,
    configSnapshotHash: HASH,
    localCalculation: { status: 'CALCULATED' },
  }

  assert.equal((await getTradingLabRun(RUN_ID, TOKEN)).id, RUN_ID)
  assert.equal((await getTradingLabScenario(SCENARIO_ID, TOKEN)).id, SCENARIO_ID)
  assert.equal((await createTradingLabScenario(scenarioInput, TOKEN)).id, SCENARIO_ID)
  assert.equal((await createTradingLabRun(SCENARIO_ID, runInput, TOKEN)).id, RUN_ID)
  assert.equal((await controlTradingLabRun(RUN_ID, 'pause', TOKEN)).pauseRequested, true)
  assert.equal((await getTradingLabEnvironment(TOKEN)).validationHealth, 'UP')
  assert.equal((await controlTradingLabEnvironment('restart', TOKEN)).action, 'restart')

  assert.deepEqual(
    calls.map(({ url, method, authorization }) => ({ url, method, authorization })),
    [
      {
        url: `/api/admin/trading-lab/runs/${RUN_ID}`,
        method: 'GET',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: `/api/admin/trading-lab/scenarios/${SCENARIO_ID}`,
        method: 'GET',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: '/api/admin/trading-lab/scenarios',
        method: 'POST',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`,
        method: 'POST',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: `/api/admin/trading-lab/runs/${RUN_ID}/pause`,
        method: 'POST',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: '/api/admin/trading-lab/environment',
        method: 'GET',
        authorization: `Bearer ${TOKEN}`,
      },
      {
        url: '/api/admin/trading-lab/environment/restart',
        method: 'POST',
        authorization: `Bearer ${TOKEN}`,
      },
    ],
  )
  assert.deepEqual(calls[2]?.body, scenarioInput)
  assert.deepEqual(calls[3]?.body, runInput)
  assert.deepEqual(calls[4]?.body, {})
  assert.deepEqual(calls[6]?.body, {})
})

test('run, scenario, and environment actions reject values outside their allowlists before fetch', async () => {
  let fetchCount = 0
  globalThis.fetch = (async () => {
    fetchCount += 1
    return jsonResponse({})
  }) as typeof fetch

  await assert.rejects(
    controlTradingLabRun(RUN_ID, 'delete' as 'pause', TOKEN),
    /Trading Lab run action is invalid/,
  )
  await assert.rejects(
    controlTradingLabEnvironment('destroy' as 'start', TOKEN),
    /Trading Lab environment action is invalid/,
  )
  await assert.rejects(
    getTradingLabRun('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA', TOKEN),
    /canonical lowercase UUID/,
  )
  await assert.rejects(
    getTradingLabRun(RUN_ID, '   '),
    /admin token is required/,
  )
  assert.equal(fetchCount, 0)
})

test('bounded response guards reject unsafe numeric and unbounded JSON shapes', async () => {
  const responses: unknown[] = [
    {
      ...sampleRun(),
      queueSequence: Number.MAX_SAFE_INTEGER + 1,
    },
    {
      ...sampleScenario(),
      scenario: nestJson(65),
    },
  ]
  globalThis.fetch = (async () => jsonResponse(responses.shift())) as typeof fetch

  await assert.rejects(
    getTradingLabRun(RUN_ID, TOKEN),
    /Invalid Trading Lab run response/,
  )
  await assert.rejects(
    getTradingLabScenario(SCENARIO_ID, TOKEN),
    /Invalid Trading Lab scenario response/,
  )
})

test('create sends only once when the server returns a definite failure', async () => {
  let fetchCount = 0
  globalThis.fetch = (async () => {
    fetchCount += 1
    return new Response(
      JSON.stringify({
        success: false,
        code: 'TRADING_LAB_SCENARIO_INVALID',
        message: 'invalid',
        data: null,
      }),
      {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      },
    )
  }) as typeof fetch

  await assert.rejects(
    createTradingLabRun(
      SCENARIO_ID,
      {
        scenarioVersion: 3,
        configSnapshotHash: HASH,
        localCalculation: { status: 'CALCULATED' },
      },
      TOKEN,
    ),
  )
  assert.equal(fetchCount, 1)
})

test('create does not refresh or replay after a 401 response', async () => {
  installStorage({
    'fx-platform-admin-token': TOKEN,
    'fx-platform-admin-refresh-token': 'refresh-token',
  })
  const calls: string[] = []
  globalThis.fetch = (async (url: string | URL | Request) => {
    const requestUrl = String(url)
    calls.push(requestUrl)
    if (requestUrl === '/api/auth/refresh') {
      return jsonResponse({
        accessToken: 'refreshed-token',
        refreshToken: 'refreshed-refresh-token',
        authorities: ['TRADING_LAB_EXECUTE'],
      })
    }
    return new Response(
      JSON.stringify({
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'expired',
        data: null,
      }),
      {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      },
    )
  }) as typeof fetch

  await assert.rejects(
    createTradingLabRun(
      SCENARIO_ID,
      {
        scenarioVersion: 3,
        configSnapshotHash: HASH,
        localCalculation: { status: 'CALCULATED' },
      },
      TOKEN,
    ),
  )

  assert.deepEqual(calls, [
    `/api/admin/trading-lab/scenarios/${SCENARIO_ID}/runs`,
  ])
})

test('print confirmation issuance does not refresh or replay after a 401 response', async () => {
  installStorage({
    'fx-platform-admin-token': TOKEN,
    'fx-platform-admin-refresh-token': 'refresh-token',
  })
  const calls: string[] = []
  globalThis.fetch = (async (url: string | URL | Request) => {
    const requestUrl = String(url)
    calls.push(requestUrl)
    if (requestUrl === '/api/auth/refresh') {
      return jsonResponse({
        accessToken: 'refreshed-token',
        refreshToken: 'refreshed-refresh-token',
        authorities: ['SUPER_ADMIN'],
      })
    }
    return new Response(
      JSON.stringify({
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'expired',
        data: null,
      }),
      {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      },
    )
  }) as typeof fetch

  await assert.rejects(
    createTradingLabReportPrintConfirmation(REPORT_ID, TOKEN),
  )

  assert.deepEqual(calls, [
    `/api/admin/trading-lab/reports/${REPORT_ID}/print-confirmation`,
  ])
})

test('report JSON methods use exact paths, methods, bodies, and explicit tokens', async () => {
  const calls: Array<{
    url: string
    method: string
    authorization: string | null
    body: string | null
  }> = []
  globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
    const requestUrl = String(url)
    const method = init?.method ?? 'GET'
    calls.push({
      url: requestUrl,
      method,
      authorization: new Headers(init?.headers).get('Authorization'),
      body: typeof init?.body === 'string' ? init.body : null,
    })
    if (
      requestUrl === `/api/admin/trading-lab/reports/${REPORT_ID}`
      && method === 'GET'
    ) {
      return jsonResponse(sampleReport())
    }
    if (
      requestUrl === `/api/admin/trading-lab/reports/${REPORT_ID}`
      && method === 'DELETE'
    ) {
      return jsonResponse(null)
    }
    if (
      requestUrl === `/api/admin/trading-lab/reports/${REPORT_ID}/permanent`
      && method === 'POST'
    ) {
      return jsonResponse({
        reportId: REPORT_ID,
        permanent: true,
        version: 8,
      })
    }
    if (
      requestUrl === `/api/admin/trading-lab/reports/${REPORT_ID}/print-info`
      && method === 'GET'
    ) {
      return jsonResponse(samplePrintInfo())
    }
    if (
      requestUrl
        === `/api/admin/trading-lab/reports/${REPORT_ID}/print-confirmation`
      && method === 'POST'
    ) {
      return jsonResponse({
        reportId: REPORT_ID,
        token: 'single-use-print-token',
        expiresAt: '2026-07-25T00:05:00Z',
      })
    }
    throw new Error(`Unexpected request: ${method} ${requestUrl}`)
  }) as typeof fetch

  assert.equal((await getTradingLabReport(REPORT_ID, TOKEN)).id, REPORT_ID)
  assert.equal(await deleteTradingLabReport(REPORT_ID, TOKEN), undefined)
  assert.equal(
    (await setTradingLabReportPermanent(REPORT_ID, true, TOKEN)).permanent,
    true,
  )
  assert.equal(
    (await getTradingLabReportPrintInfo(REPORT_ID, TOKEN))
      .requiresConfirmation,
    false,
  )
  assert.equal(
    (await createTradingLabReportPrintConfirmation(REPORT_ID, TOKEN)).token,
    'single-use-print-token',
  )

  assert.deepEqual(calls, [
    {
      url: `/api/admin/trading-lab/reports/${REPORT_ID}`,
      method: 'GET',
      authorization: `Bearer ${TOKEN}`,
      body: null,
    },
    {
      url: `/api/admin/trading-lab/reports/${REPORT_ID}`,
      method: 'DELETE',
      authorization: `Bearer ${TOKEN}`,
      body: null,
    },
    {
      url: `/api/admin/trading-lab/reports/${REPORT_ID}/permanent`,
      method: 'POST',
      authorization: `Bearer ${TOKEN}`,
      body: '{"permanent":true}',
    },
    {
      url: `/api/admin/trading-lab/reports/${REPORT_ID}/print-info`,
      method: 'GET',
      authorization: `Bearer ${TOKEN}`,
      body: null,
    },
    {
      url: `/api/admin/trading-lab/reports/${REPORT_ID}/print-confirmation`,
      method: 'POST',
      authorization: `Bearer ${TOKEN}`,
      body: '{}',
    },
  ])
})

test('report detail rejects extra or missing keys and invalid scalar contracts', async () => {
  const invalidReports: unknown[] = [
    { ...sampleReport(), unexpected: true },
    omit(sampleReport(), 'completedAt'),
    { ...sampleReport(), id: REPORT_ID.toUpperCase() },
    { ...sampleReport(), configSnapshotHash: 'not-a-hash' },
    { ...sampleReport(), retainedUntil: '2026-02-30T00:00:00Z' },
    {
      ...sampleReport(),
      uncompressedBytes: Number.MAX_SAFE_INTEGER + 1,
    },
    { ...sampleReport(), status: 'WRITING' },
  ]
  let index = 0
  globalThis.fetch = (async () => jsonResponse(invalidReports[index++])) as typeof fetch

  for (const _invalidReport of invalidReports) {
    await assert.rejects(
      getTradingLabReport(REPORT_ID, TOKEN),
      /Invalid Trading Lab report response/,
    )
  }
})

test('print-info freezes the exact 50 MiB boundary and rejects inconsistent metadata', async () => {
  assert.equal(
    TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
    52_428_800,
  )
  const responses = [
    samplePrintInfo({
      uncompressedBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
      estimatedPageCount: 12_800,
      requiresConfirmation: false,
    }),
    samplePrintInfo({
      uncompressedBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES + 1,
      estimatedPageCount: 12_801,
      requiresConfirmation: true,
    }),
    samplePrintInfo({
      uncompressedBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
      requiresConfirmation: true,
    }),
    samplePrintInfo({ thresholdBytes: 1 }),
    samplePrintInfo({ estimatedPageCount: 1 }),
  ]
  globalThis.fetch = (async () => jsonResponse(responses.shift())) as typeof fetch

  assert.equal(
    (await getTradingLabReportPrintInfo(REPORT_ID, TOKEN))
      .requiresConfirmation,
    false,
  )
  assert.equal(
    (await getTradingLabReportPrintInfo(REPORT_ID, TOKEN))
      .requiresConfirmation,
    true,
  )
  for (let index = 0; index < 3; index += 1) {
    await assert.rejects(
      getTradingLabReportPrintInfo(REPORT_ID, TOKEN),
      /Invalid Trading Lab print-info response/,
    )
  }
})

test('report mutation and confirmation responses are exact and identity-bound', async () => {
  const responses: unknown[] = [
    {
      reportId: RUN_ID,
      permanent: true,
      version: 1,
    },
    {
      reportId: REPORT_ID,
      permanent: true,
      version: 1,
      unexpected: true,
    },
    {
      reportId: RUN_ID,
      token: 'single-use-print-token',
      expiresAt: '2026-07-25T00:05:00Z',
    },
    {
      reportId: REPORT_ID,
      token: '',
      expiresAt: '2026-07-25T00:05:00Z',
    },
  ]
  globalThis.fetch = (async () => jsonResponse(responses.shift())) as typeof fetch

  await assert.rejects(
    setTradingLabReportPermanent(REPORT_ID, true, TOKEN),
    /Invalid Trading Lab permanent response/,
  )
  await assert.rejects(
    setTradingLabReportPermanent(REPORT_ID, true, TOKEN),
    /Invalid Trading Lab permanent response/,
  )
  await assert.rejects(
    createTradingLabReportPrintConfirmation(REPORT_ID, TOKEN),
    /Invalid Trading Lab print-confirmation response/,
  )
  await assert.rejects(
    createTradingLabReportPrintConfirmation(REPORT_ID, TOKEN),
    /Invalid Trading Lab print-confirmation response/,
  )
})

test('report methods reject invalid input before fetch', async () => {
  let fetchCount = 0
  globalThis.fetch = (async () => {
    fetchCount += 1
    return jsonResponse(null)
  }) as typeof fetch

  await assert.rejects(
    getTradingLabReport(REPORT_ID.toUpperCase(), TOKEN),
    /canonical lowercase UUID/,
  )
  await assert.rejects(
    deleteTradingLabReport(REPORT_ID, ' '),
    /admin token is required/,
  )
  await assert.rejects(
    setTradingLabReportPermanent(
      REPORT_ID,
      'yes' as unknown as boolean,
      TOKEN,
    ),
    /permanent must be a boolean/,
  )
  assert.equal(fetchCount, 0)
})

function sampleRun() {
  return {
    id: RUN_ID,
    scenarioId: SCENARIO_ID,
    reportId: REPORT_ID,
    state: 'RUNNING',
    queueSequence: 0,
    pauseRequested: false,
    cancelRequested: false,
    virtualStartedAt: NOW,
    virtualCurrentAt: NOW,
    processedTicks: 1,
    totalTicks: 10,
    speedMultiplier: 1,
    currentStep: 1,
    failureCode: null,
    failureMessage: null,
    configSnapshotHash: HASH,
    modelVersion: 'model-v1',
    symbolConfigVersion: 'symbol-v1',
    codeVersion: 'code-v1',
    createdBy: ACTOR_ID,
    createdAt: NOW,
    updatedAt: NOW,
    startedAt: NOW,
    finishedAt: null,
    version: 3,
  }
}

function sampleScenario() {
  return {
    id: SCENARIO_ID,
    name: 'Replay',
    description: null,
    status: 'FROZEN',
    negativeMode: false,
    seed: 'seed-1',
    modelVersion: 'model-v1',
    scenario: { seed: 'seed-1' },
    configSnapshot: { modelVersion: 'model-v1' },
    configSnapshotHash: HASH,
    symbolConfigVersion: 'symbol-v1',
    codeVersion: 'code-v1',
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    createdAt: NOW,
    updatedAt: NOW,
    version: 3,
  }
}

function sampleReport() {
  return {
    id: REPORT_ID,
    runId: RUN_ID,
    scenarioId: SCENARIO_ID,
    status: 'COMPLETED',
    modelVersion: 'model-v1',
    configSnapshotHash: HASH,
    codeVersion: 'code-v1',
    uncompressedBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
    compressedBytes: 1024,
    chunkCount: 2,
    retainedUntil: '2026-08-24T00:00:00Z',
    permanent: false,
    failureCode: null,
    failureMessage: null,
    createdAt: NOW,
    completedAt: NOW,
    version: 7,
  }
}

function samplePrintInfo(
  overrides: Partial<{
    uncompressedBytes: number
    estimatedPageCount: number
    thresholdBytes: number
    requiresConfirmation: boolean
  }> = {},
) {
  return {
    uncompressedBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
    estimatedPageCount: 12_800,
    thresholdBytes: TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
    requiresConfirmation: false,
    ...overrides,
  }
}

function omit(
  value: Record<string, unknown>,
  key: string,
): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).filter(([candidate]) => candidate !== key),
  )
}

function jsonResponse(data: unknown) {
  return new Response(
    JSON.stringify({
      success: true,
      code: 'OK',
      message: 'OK',
      data,
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    },
  )
}

function nestJson(depth: number): unknown {
  let value: unknown = 'leaf'
  for (let index = 0; index < depth; index += 1) {
    value = { nested: value }
  }
  return value
}

function installStorage(initial: Readonly<Record<string, string>>) {
  const values = new Map(Object.entries(initial))
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
      key: (index: number) => Array.from(values.keys())[index] ?? null,
      get length() {
        return values.size
      },
    } satisfies Storage,
  })
}
