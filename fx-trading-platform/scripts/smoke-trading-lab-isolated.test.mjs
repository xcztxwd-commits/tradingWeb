import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import test from 'node:test'

const OWNERSHIP = Object.freeze({
  createdRuns: [{
    scenarioId: '11111111-1111-4111-8111-111111111111',
    runId: '22222222-2222-4222-8222-222222222222',
    reportId: '33333333-3333-4333-8333-333333333333',
    expectedTerminalState: 'COMPLETED',
  }],
  referencedRunIds: ['44444444-4444-4444-8444-444444444444'],
  tradingLabRequestIds: [
    '55555555-5555-4555-8555-555555555555',
  ],
  requestLogObservations: [
    requestLogObservation(
      '55555555-5555-4555-8555-555555555555',
      'GET',
      '/api/admin/trading-lab/environment',
    ),
  ],
})
const FIXTURE_REQUEST_IDS = Object.freeze([
  '88888888-8888-4888-8888-888888888888',
  '99999999-9999-4999-8999-999999999999',
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  'ffffffff-ffff-4fff-8fff-ffffffffffff',
])
const FIXTURE_TRADING_LAB_REQUEST_IDS = Object.freeze([
  '99999999-9999-4999-8999-999999999999',
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  'ffffffff-ffff-4fff-8fff-ffffffffffff',
])
const FIXTURE_REQUEST_LOG_OBSERVATIONS = Object.freeze([
  requestLogObservation(
    FIXTURE_REQUEST_IDS[0],
    'POST',
    '/api/auth/login',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[1],
    'GET',
    '/api/admin/trading-lab/config',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[2],
    'POST',
    '/api/admin/trading-lab/scenarios',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[3],
    'POST',
    '/api/admin/trading-lab/scenarios/'
      + '66666666-6666-4666-8666-666666666666/runs',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[4],
    'GET',
    '/api/admin/trading-lab/runs/'
      + '44444444-4444-4444-8444-444444444444',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[5],
    'GET',
    '/api/admin/trading-lab/runs/'
      + '44444444-4444-4444-8444-444444444444',
  ),
  requestLogObservation(
    FIXTURE_REQUEST_IDS[6],
    'GET',
    '/api/admin/trading-lab/reports/'
      + '77777777-7777-4777-8777-777777777777',
  ),
])
const OWNED_AUTH_USER_IDS = Object.freeze([
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4',
])
const FIXTURE_AUTH_USER_ID = OWNED_AUTH_USER_IDS[0]

test('isolated wrapper runs preflight, smoke, postflight, then writes truthful artifacts', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const calls = []
  const writes = []
  const smokeSummary = passingSmokeSummary()

  const result = await runIsolatedTradingLabSmoke({
    argv: ['--headed'],
    ownedAuthUserIds: OWNED_AUTH_USER_IDS,
    prepareLargeFixture: async () => {
      calls.push('fixture')
      return largeFixture()
    },
    createProbe: () => ({
      async before() {
        calls.push('before')
        return { passed: true, mainFingerprintSha256: 'before' }
      },
      async after(input) {
        calls.push(['after', input])
        return { passed: true, mainFingerprintSha256: 'after' }
      },
    }),
    runSmoke: async (argv) => {
      calls.push(['smoke', argv])
      return smokeSummary
    },
    writeArtifact: async (root, name, value) => {
      calls.push(['write', name])
      writes.push({ root, name, value })
    },
    now: monotonicNow(),
  })

  assert.equal(result.status, 'PASS')
  assert.deepEqual(calls.slice(0, 3), [
    'before',
    'fixture',
    ['smoke', [
      '--headed',
      `--large-run-id=${OWNERSHIP.referencedRunIds[0]}`,
    ]],
  ])
  assert.deepEqual(calls[3], [
    ['after', {
      reports: [],
      reportPaths: smokeSummary.reportPaths,
      ownership: expectedOwnership(),
    }],
  ][0])
  assert.deepEqual(
    writes.map(({ name }) => name),
    ['runtime-isolation.json'],
  )
  assert.equal(writes.every(({ root }) => root === smokeSummary.artifacts), true)
  assert.equal(writes[0].value.status, 'PASS')
  assert.deepEqual(result.ownership, expectedOwnership())
})

test('isolated wrapper runs postflight after smoke failure and preserves primary then postflight', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('browser primary Bearer raw-secret-token')
  Object.defineProperty(primary, 'smokeSummary', {
    value: {
      ...passingSmokeSummary(),
      status: 'FAIL',
    },
  })
  const postflight = new Error('postflight fingerprint changed')
  const calls = []
  const writes = []

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          calls.push('before')
          return { passed: true }
        },
        async after(input) {
          calls.push(['after', input])
          throw postflight
        },
      }),
      runSmoke: async () => {
        calls.push('smoke')
        throw primary
      },
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure instanceof AggregateError, true)
      assert.deepEqual(failure.errors, [primary, postflight])
      return true
    },
  )

  assert.deepEqual(calls.map((entry) =>
    Array.isArray(entry) ? entry[0] : entry), [
    'before',
    'smoke',
    'after',
  ])
  assert.equal(writes.length, 1)
  assert.equal(writes[0].value.status, 'FAIL')
  assert.equal(writes[0].value.smoke.status, 'FAIL')
  assert.equal(writes[0].value.postflight.status, 'FAIL')
  assert.equal(JSON.stringify(writes).includes('raw-secret-token'), false)
})

test('isolated wrapper preserves trusted fixture ownership after early smoke failure', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const partialObservation = requestLogRequestObservation(
    'abababab-abab-4aba-8aba-abababababab',
    'GET',
    '/api/admin/dashboard/summary',
  )
  const provisionalRun = {
    scenarioId: '11111111-1111-4111-8111-111111111111',
    runId: '22222222-2222-4222-8222-222222222222',
    reportId: '33333333-3333-4333-8333-333333333333',
    provisional: true,
  }
  const primary = new Error('Admin login form control was not ready')
  Object.defineProperty(primary, 'smokeSummary', {
    value: {
      ...passingSmokeSummary(),
      status: 'FAIL',
      journeys: [],
      reportPaths: [],
      ownership: {
        createdRuns: [provisionalRun],
        referencedRunIds: [],
        tradingLabRequestIds: [],
        requestLogObservations: [partialObservation],
      },
    },
  })
  const afterInputs = []

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw primary
      },
      writeArtifact: async () => {},
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure, primary)
      return true
    },
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(
    afterInputs[0].ownership,
    {
      ...fixtureRecoveryOwnership([partialObservation]),
      createdRuns: [
        provisionalRun,
        ...fixtureRecoveryOwnership().createdRuns,
      ],
    },
  )
})

test('isolated wrapper preserves every completed permission login after a later smoke failure', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('print journey failed after permission PASS')
  Object.defineProperty(primary, 'smokeSummary', {
    value: {
      ...passingSmokeSummary(),
      status: 'FAIL',
      journeys: [
        permissionJourneyReceipt(),
        {
          name: 'print journey',
          status: 'FAIL',
          durationMs: 45_000,
          error: { name: 'Error', message: 'print timeout' },
        },
      ],
    },
  })
  const afterInputs = []

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw primary
      },
      writeArtifact: async () => {},
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure, primary)
      return true
    },
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(
    afterInputs[0].ownership.authUserIds,
    [...OWNED_AUTH_USER_IDS],
  )
})

test('postflight auth ownership expands only from an exact permission PASS receipt', async (t) => {
  const { selectPostflightAuthUserIds } =
    await import('./smoke-trading-lab-isolated.mjs')
  const input = {
    smokeSummary: {
      ...passingSmokeSummary(),
      journeys: [permissionJourneyReceipt()],
    },
    fixtureRunId: OWNERSHIP.referencedRunIds[0],
    fixtureAuthUserId: FIXTURE_AUTH_USER_ID,
    canonicalOwnedAuthUserIds: OWNED_AUTH_USER_IDS,
  }

  assert.deepEqual(
    selectPostflightAuthUserIds(input),
    [...OWNED_AUTH_USER_IDS],
  )

  for (const [name, journeys] of [
    ['missing receipt', []],
    ['failed receipt', [{
      ...permissionJourneyReceipt(),
      status: 'FAIL',
      error: { name: 'Error', message: 'permission failed' },
    }]],
    ['duplicate receipt', [
      permissionJourneyReceipt(),
      permissionJourneyReceipt(),
    ]],
    ['wrong roles', [permissionJourneyReceipt({
      details: {
        ...permissionJourneyReceipt().details,
        roles: ['VIEW-only', 'EXECUTE', 'SUPER_ADMIN'],
      },
    })]],
    ['wrong denial status', [permissionJourneyReceipt({
      details: {
        ...permissionJourneyReceipt().details,
        directEnvironmentStatus: 200,
      },
    })]],
    ['wrong fixture reference', [permissionJourneyReceipt({
      details: {
        ...permissionJourneyReceipt().details,
        referencedRunIds: ['11111111-1111-4111-8111-111111111111'],
      },
    })]],
  ]) {
    await t.test(name, () => {
      assert.deepEqual(
        selectPostflightAuthUserIds({
          ...input,
          smokeSummary: {
            ...input.smokeSummary,
            status: 'FAIL',
            journeys,
          },
        }),
        [FIXTURE_AUTH_USER_ID],
      )
      assert.throws(
        () => selectPostflightAuthUserIds({
          ...input,
          smokeSummary: {
            ...input.smokeSummary,
            status: 'PASS',
            journeys,
          },
        }),
        /permission auth-session receipt/u,
      )
    })
  }
})

test('isolated wrapper runs postflight with empty ownership after fixture preparation fails', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('fixture preparation failed after login')
  const afterInputs = []
  const writes = []
  const artifacts = 'C:\\owned\\trading-lab-artifacts'

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      argv: [`--artifacts=${artifacts}`],
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => {
        throw primary
      },
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw new Error('smoke must not run')
      },
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure, primary)
      return true
    },
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(afterInputs[0].ownership, emptyOwnership())
  assert.equal(writes.length, 1)
  assert.equal(writes[0].root, artifacts)
  assert.equal(writes[0].name, 'runtime-isolation.json')
  assert.equal(writes[0].value.status, 'FAIL')
  assert.equal(writes[0].value.smoke.artifacts, artifacts)
  assert.deepEqual(writes[0].value.ownership, emptyOwnership())
})

test('isolated wrapper passes recovered provisional fixture ownership to postflight after timeout', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('fixture run timed out after admission')
  const afterInputs = []
  const writes = []
  const recovery = provisionalLargeFixtureRecovery()

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      argv: ['--artifacts=C:\\owned\\trading-lab-artifacts'],
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async ({ onRecovery }) => {
        onRecovery(recovery)
        throw primary
      },
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw new Error('smoke must not run')
      },
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure, primary)
      return true
    },
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(
    afterInputs[0].ownership,
    provisionalFixtureRecoveryOwnership(),
  )
  assert.equal(writes.length, 1)
  assert.deepEqual(
    writes[0].value.ownership,
    provisionalFixtureRecoveryOwnership(),
  )
  assert.equal(writes[0].value.largeFixture, null)
})

test('isolated wrapper retains standalone scenario ownership when run admission fails', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('run admission failed')
  const afterInputs = []
  const recovery = standaloneScenarioRecovery()

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async ({ onRecovery }) => {
        onRecovery(recovery)
        throw primary
      },
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw new Error('smoke must not run')
      },
      writeArtifact: async () => {},
      now: monotonicNow(),
    }),
    (failure) => failure === primary,
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(afterInputs[0].ownership, {
    createdScenarios: [recovery.scenarioId],
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [...recovery.tradingLabRequestIds],
    requestLogObservations: recovery.requestLogObservations.map(
      (observation) => ({ ...observation }),
    ),
    authUserIds: [FIXTURE_AUTH_USER_ID],
  })
})

test('isolated wrapper rejects duplicate partial fixture ownership and retains recovery', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const primary = new Error('browser failed after a duplicate observation')
  Object.defineProperty(primary, 'smokeSummary', {
    value: {
      ...passingSmokeSummary(),
      status: 'FAIL',
      ownership: {
        createdRuns: [],
        referencedRunIds: [],
        tradingLabRequestIds: [],
        requestLogObservations: [
          FIXTURE_REQUEST_LOG_OBSERVATIONS[0],
        ],
      },
    },
  })
  const afterInputs = []
  const writes = []

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        throw primary
      },
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    (failure) => {
      assert.equal(failure instanceof AggregateError, true)
      assert.equal(failure.errors[0], primary)
      assert.match(
        failure.errors[1].message,
        /request-log observations are invalid/iu,
      )
      return true
    },
  )

  assert.equal(afterInputs.length, 1)
  assert.deepEqual(
    afterInputs[0].ownership,
    fixtureRecoveryOwnership(),
  )
  assert.equal(writes.length, 1)
  assert.deepEqual(
    writes[0].value.smoke.error.errors.map(({ message }) => message),
    [
      primary.message,
      'Trading Lab request-log observations are invalid',
    ],
  )
})

test('isolated wrapper still runs postflight when ownership merge fails', async (t) => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const cases = [
    {
      name: 'missing referenced large fixture',
      summary: {
        ...passingSmokeSummary(),
        ownership: {
          ...passingSmokeSummary().ownership,
          referencedRunIds: [],
        },
      },
      pattern: /did not retain the large fixture/iu,
    },
    {
      name: 'malformed smoke ownership',
      summary: {
        ...passingSmokeSummary(),
        ownership: {
          createdRuns: {},
          referencedRunIds: [],
          tradingLabRequestIds: [],
          requestLogObservations: [],
        },
      },
      pattern: /iterable|ownership/iu,
    },
    {
      name: 'unknown smoke ownership key',
      summary: {
        ...passingSmokeSummary(),
        ownership: {
          ...passingSmokeSummary().ownership,
          ignored: [],
        },
      },
      pattern: /ownership schema/iu,
    },
  ]

  for (const fixture of cases) {
    await t.test(fixture.name, async () => {
      const afterInputs = []
      const postflight = new Error(`postflight after ${fixture.name}`)
      await assert.rejects(
        runIsolatedTradingLabSmoke({
          ownedAuthUserIds: OWNED_AUTH_USER_IDS,
          prepareLargeFixture: async () => largeFixture(),
          createProbe: () => ({
            async before() {
              return { passed: true }
            },
            async after(input) {
              afterInputs.push(input)
              throw postflight
            },
          }),
          runSmoke: async () => fixture.summary,
          writeArtifact: async () => {},
          now: monotonicNow(),
        }),
        (failure) => {
          assert.equal(failure instanceof AggregateError, true)
          assert.equal(failure.errors.length, 2)
          assert.match(failure.errors[0].message, fixture.pattern)
          assert.equal(failure.errors[1], postflight)
          return true
        },
      )
      assert.equal(afterInputs.length, 1)
      assert.deepEqual(
        afterInputs[0].ownership,
        fixtureRecoveryOwnership(),
      )
    })
  }
})

test('isolated wrapper never reports PASS when smoke returns a failed summary', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const writes = []
  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after() {
          return { passed: true }
        },
      }),
      runSmoke: async () => ({
        ...passingSmokeSummary(),
        status: 'FAIL',
      }),
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    /smoke summary is not PASS/iu,
  )
  assert.equal(writes.length, 1)
  assert.equal(writes.every(({ value }) => value.status === 'FAIL'), true)
})

test('isolated wrapper rejects PASS smoke with provisional created-run ownership', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const provisional = {
    scenarioId: '11111111-1111-4111-8111-111111111111',
    runId: '22222222-2222-4222-8222-222222222222',
    reportId: '33333333-3333-4333-8333-333333333333',
    provisional: true,
  }
  const writes = []

  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS,
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after() {
          return { passed: true }
        },
      }),
      runSmoke: async () => ({
        ...passingSmokeSummary(),
        ownership: {
          ...OWNERSHIP,
          createdRuns: [provisional],
        },
      }),
      writeArtifact: async (root, name, value) => {
        writes.push({ root, name, value })
      },
      now: monotonicNow(),
    }),
    /PASS.*terminal|terminal.*PASS/iu,
  )

  assert.equal(writes.length, 1)
  assert.equal(writes[0].value.status, 'FAIL')
})

test('isolated wrapper publishes exactly one authoritative PASS artifact', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  let writes = 0
  const result = await runIsolatedTradingLabSmoke({
    ownedAuthUserIds: OWNED_AUTH_USER_IDS,
    prepareLargeFixture: async () => largeFixture(),
    createProbe: () => ({
      async before() {
        return { passed: true }
      },
      async after() {
        return { passed: true }
      },
    }),
    runSmoke: async () => passingSmokeSummary(),
    writeArtifact: async () => {
      writes += 1
      if (writes > 1) {
        throw new Error('second artifact publish failed')
      }
    },
    now: monotonicNow(),
  })
  assert.equal(result.status, 'PASS')
  assert.equal(writes, 1)
})

test('isolated wrapper captures isolation before its owned large fixture', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  const calls = []
  await runIsolatedTradingLabSmoke({
    ownedAuthUserIds: OWNED_AUTH_USER_IDS,
    argv: [
      '--admin-url=http://127.0.0.1:5174',
      '--api-url=http://127.0.0.1:18086',
      '--attach-admin',
    ],
    prepareLargeFixture: async ({ apiUrl, ownedAuthUserIds }) => {
      calls.push(['fixture', apiUrl, ownedAuthUserIds])
      return largeFixture()
    },
    createProbe: () => ({
      async before() {
        calls.push('before')
        return { passed: true }
      },
      async after() {
        calls.push('after')
        return { passed: true }
      },
    }),
    runSmoke: async (argv) => {
      calls.push(['smoke', argv])
      return passingSmokeSummary()
    },
    writeArtifact: async () => {},
  })
  assert.deepEqual(calls.slice(0, 3), [
    'before',
    ['fixture', 'http://127.0.0.1:18086', OWNED_AUTH_USER_IDS],
    ['smoke', [
      '--admin-url=http://127.0.0.1:5174',
      '--api-url=http://127.0.0.1:18086',
      '--attach-admin',
      `--large-run-id=${OWNERSHIP.referencedRunIds[0]}`,
    ]],
  ])
})

test('isolated wrapper fails closed inside the isolation boundary for invalid fixture evidence', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  for (const fixture of [
    { ...largeFixture(), runId: 'NOT-A-UUID' },
    { ...largeFixture(), uncompressedBytes: 50 * 1024 * 1024 },
    { ...largeFixture(), provisional: true },
    { ...largeFixture(), unknownEvidence: true },
    {
      ...largeFixture(),
      requestIds: [
        ...FIXTURE_REQUEST_IDS.slice(0, -1),
        FIXTURE_REQUEST_IDS[0],
      ],
    },
  ]) {
    let before = 0
    const afterInputs = []
    await assert.rejects(
      runIsolatedTradingLabSmoke({
        ownedAuthUserIds: OWNED_AUTH_USER_IDS,
        prepareLargeFixture: async () => fixture,
        createProbe: () => ({
          async before() {
            before += 1
            return { passed: true }
          },
          async after(input) {
            afterInputs.push(input)
            return { passed: true }
          },
        }),
        runSmoke: async () => passingSmokeSummary(),
      }),
      /large fixture|canonical UUID|> 50 MiB/iu,
    )
    assert.equal(before, 1)
    assert.equal(afterInputs.length, 1)
    assert.deepEqual(afterInputs[0].ownership, emptyOwnership())
  }
})

test('isolated wrapper rejects a fixture login outside runner-owned auth users', async () => {
  const { runIsolatedTradingLabSmoke } =
    await import('./smoke-trading-lab-isolated.mjs')
  let smokeCalls = 0
  const afterInputs = []
  await assert.rejects(
    runIsolatedTradingLabSmoke({
      ownedAuthUserIds: OWNED_AUTH_USER_IDS.slice(1),
      prepareLargeFixture: async () => largeFixture(),
      createProbe: () => ({
        async before() {
          return { passed: true }
        },
        async after(input) {
          afterInputs.push(input)
          return { passed: true }
        },
      }),
      runSmoke: async () => {
        smokeCalls += 1
        return passingSmokeSummary()
      },
      writeArtifact: async () => {},
      now: monotonicNow(),
    }),
    /fixture auth user.*runner-owned/iu,
  )
  assert.equal(smokeCalls, 0)
  assert.equal(afterInputs.length, 1)
  assert.deepEqual(afterInputs[0].ownership, emptyOwnership())
})

test('isolated wrapper source is directly executable and delegates to the existing isolation probe', async () => {
  const { readFileSync } = await import('node:fs')
  const source = readFileSync(
    new URL('./smoke-trading-lab-isolated.mjs', import.meta.url),
    'utf8',
  )
  assert.match(source, /createTradingLabRuntimeIsolationProbe/)
  assert.match(source, /runIsolatedTradingLabSmoke/)
  assert.match(source, /runtime-isolation\.json/)
  assert.doesNotMatch(source, /runtime-isolation-summary\.json/)
  assert.match(source, /isMainModule/)
  assert.match(source, /argv:\s*process\.argv\.slice\(2\)/u)
  assert.match(
    source,
    /JSON\.stringify\(\s*serializeError\(asError\(error\)\)/u,
  )
  assert.doesNotMatch(source, /status:\s*'PASS'\s*,\s*passed:\s*true/iu)
})

function passingSmokeSummary() {
  return {
    schemaVersion: 1,
    status: 'PASS',
    artifacts: 'C:\\owned\\trading-lab-artifacts',
    reportPaths: [
      'C:\\owned\\trading-lab-artifacts\\downloads\\report.json',
    ],
    journeys: [permissionJourneyReceipt()],
    ownership: OWNERSHIP,
    error: null,
  }
}

function largeFixture() {
  return {
    authUserId: FIXTURE_AUTH_USER_ID,
    scenarioId: '66666666-6666-4666-8666-666666666666',
    runId: OWNERSHIP.referencedRunIds[0],
    reportId: '77777777-7777-4777-8777-777777777777',
    uncompressedBytes: 50 * 1024 * 1024 + 1,
    actionCount: 40,
    requestIds: [...FIXTURE_REQUEST_IDS],
    tradingLabRequestIds: [...FIXTURE_TRADING_LAB_REQUEST_IDS],
    requestLogObservations: FIXTURE_REQUEST_LOG_OBSERVATIONS.map(
      (observation) => ({ ...observation }),
    ),
  }
}

function provisionalLargeFixtureRecovery() {
  const fixture = largeFixture()
  return {
    authUserId: fixture.authUserId,
    scenarioId: fixture.scenarioId,
    runId: fixture.runId,
    reportId: fixture.reportId,
    actionCount: fixture.actionCount,
    provisional: true,
    requestIds: [...fixture.requestIds],
    tradingLabRequestIds: [...fixture.tradingLabRequestIds],
    requestLogObservations: fixture.requestLogObservations.map(
      (observation) => ({ ...observation }),
    ),
  }
}

function standaloneScenarioRecovery() {
  const fixture = largeFixture()
  return {
    authUserId: fixture.authUserId,
    scenarioId: fixture.scenarioId,
    actionCount: fixture.actionCount,
    provisional: true,
    requestIds: fixture.requestIds.slice(0, 4),
    tradingLabRequestIds: fixture.tradingLabRequestIds.slice(0, 3),
    requestLogObservations: fixture.requestLogObservations
      .slice(0, 4)
      .map((observation) => ({ ...observation })),
  }
}

function provisionalFixtureRecoveryOwnership() {
  const recovery = provisionalLargeFixtureRecovery()
  return {
    createdRuns: [{
      scenarioId: recovery.scenarioId,
      runId: recovery.runId,
      reportId: recovery.reportId,
      provisional: true,
    }],
    referencedRunIds: [],
    tradingLabRequestIds: [...recovery.tradingLabRequestIds],
    requestLogObservations: recovery.requestLogObservations.map(
      (observation) => ({ ...observation }),
    ),
    authUserIds: [FIXTURE_AUTH_USER_ID],
  }
}

function fixtureRecoveryOwnership(partialRequestLogObservations = []) {
  const fixture = largeFixture()
  return {
    createdRuns: [{
      scenarioId: fixture.scenarioId,
      runId: fixture.runId,
      reportId: fixture.reportId,
      expectedTerminalState: 'COMPLETED',
    }],
    referencedRunIds: [],
    tradingLabRequestIds: [...fixture.tradingLabRequestIds],
    requestLogObservations: [
      ...partialRequestLogObservations,
      ...fixture.requestLogObservations,
    ],
    authUserIds: [FIXTURE_AUTH_USER_ID],
  }
}

function emptyOwnership() {
  return {
    createdRuns: [],
    referencedRunIds: [],
    tradingLabRequestIds: [],
    requestLogObservations: [],
  }
}

function expectedOwnership() {
  return {
    createdRuns: [
      ...OWNERSHIP.createdRuns,
      {
        scenarioId: '66666666-6666-4666-8666-666666666666',
        runId: OWNERSHIP.referencedRunIds[0],
        reportId: '77777777-7777-4777-8777-777777777777',
        expectedTerminalState: 'COMPLETED',
      },
    ],
    referencedRunIds: [],
    tradingLabRequestIds: [
      ...OWNERSHIP.tradingLabRequestIds,
      ...FIXTURE_TRADING_LAB_REQUEST_IDS,
    ],
    requestLogObservations: [
      ...OWNERSHIP.requestLogObservations,
      ...FIXTURE_REQUEST_LOG_OBSERVATIONS,
    ],
    authUserIds: [...OWNED_AUTH_USER_IDS],
  }
}

function permissionJourneyReceipt(overrides = {}) {
  return {
    name: 'permission journey',
    status: 'PASS',
    durationMs: 1,
    details: {
      roles: ['VIEW-only', 'EXECUTE', 'ordinary Admin', 'SUPER_ADMIN'],
      directEnvironmentStatus: 403,
      referencedRunIds: [OWNERSHIP.referencedRunIds[0]],
    },
    ...overrides,
  }
}

function monotonicNow() {
  let tick = 0
  return () => `2026-07-26T00:00:0${tick += 1}.000Z`
}

function requestLogObservation(
  requestId,
  method,
  path,
  statusCode = 200,
) {
  return {
    requestId,
    requestTupleSha256: createHash('sha256')
      .update(JSON.stringify({ method, path, statusCode }))
      .digest('hex'),
  }
}

function requestLogRequestObservation(requestId, method, path) {
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
