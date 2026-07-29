import { realpathSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  assertOwnedArtifactDirectory,
  main as runTradingLabSmoke,
  requireTerminalCreatedRunOwnership,
} from './smoke-trading-lab.mjs'
import {
  LARGE_REPORT_THRESHOLD_BYTES,
  prepareTradingLabLargeFixture,
} from './prepare-trading-lab-large-fixture.mjs'
import {
  createTradingLabRuntimeIsolationProbe,
} from './verify-trading-lab-runtime-isolation.mjs'

const MAX_LARGE_REPORT_BYTES = 128 * 1024 * 1024
const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u
const EMPTY_OWNERSHIP = Object.freeze({
  createdRuns: Object.freeze([]),
  referencedRunIds: Object.freeze([]),
  tradingLabRequestIds: Object.freeze([]),
  requestLogObservations: Object.freeze([]),
})

export async function runIsolatedTradingLabSmoke({
  argv = [],
  prepareLargeFixture = prepareTradingLabLargeFixture,
  createProbe = createTradingLabRuntimeIsolationProbe,
  runSmoke = runTradingLabSmoke,
  writeArtifact = writeIsolationArtifact,
  ownedAuthUserIds = ownedAuthUserIdsFromEnvironment(),
  now = () => new Date().toISOString(),
} = {}) {
  const startedAt = now()
  const apiUrl = smokeApiUrl(argv)
  const requestedArtifactRoot = artifactRootFromArguments(argv)
  const probe = createProbe()
  const preflight = await probe.before()
  if (preflight?.passed !== true) {
    throw new Error('Trading Lab runtime isolation preflight is not PASS')
  }

  let fixture = null
  let fixtureRecovery = null
  let smokeSummary = null
  let primaryFailure = null
  let canonicalOwnedAuthUserIds = []
  try {
    canonicalOwnedAuthUserIds = normalizeUuidList(
      ownedAuthUserIds,
      'owned auth user IDs',
    )
    const candidateFixture = validateLargeFixture(
      await prepareLargeFixture({
        apiUrl,
        ownedAuthUserIds: canonicalOwnedAuthUserIds,
        onRecovery(value) {
          const candidateRecovery = validateLargeFixtureRecovery(value)
          if (
            !canonicalOwnedAuthUserIds.includes(
              candidateRecovery.authUserId,
            )
          ) {
            throw new Error(
              'Trading Lab fixture auth user is outside runner-owned auth users',
            )
          }
          fixtureRecovery = candidateRecovery
        },
      }),
    )
    if (!canonicalOwnedAuthUserIds.includes(candidateFixture.authUserId)) {
      throw new Error(
        'Trading Lab fixture auth user is outside runner-owned auth users',
      )
    }
    fixture = candidateFixture
    const smokeArgv = [
      ...argv,
      `--large-run-id=${fixture.runId}`,
    ]
    smokeSummary = await runSmoke(smokeArgv)
    if (smokeSummary?.status !== 'PASS') {
      primaryFailure = new Error('Trading Lab smoke summary is not PASS')
    }
  } catch (error) {
    primaryFailure = asError(error)
    smokeSummary = smokeSummaryFromError(primaryFailure)
  }

  const reportPaths = reportPathsFrom(smokeSummary)
  let ownership = EMPTY_OWNERSHIP
  let recoveryFailure = null
  const recoverableFixture = fixture ?? fixtureRecovery
  if (recoverableFixture !== null) {
    try {
      ownership = mergeOwnership({
        smokeOwnership: EMPTY_OWNERSHIP,
        fixture: recoverableFixture,
        ownedAuthUserIds: [recoverableFixture.authUserId],
        requireFixtureReference: false,
      })
    } catch (error) {
      recoveryFailure = asError(error)
      primaryFailure = primaryFailure === null
        ? recoveryFailure
        : new AggregateError(
            [primaryFailure, recoveryFailure],
            'Trading Lab smoke and fixture ownership recovery both failed',
          )
    }
  }
  const smokePassed = (
    primaryFailure === null
    && smokeSummary?.status === 'PASS'
  )
  if (fixture !== null && recoveryFailure === null) {
    try {
      const smokeOwnership = ownershipFrom(smokeSummary)
      requireTerminalCreatedRunOwnership(
        smokeOwnership,
        smokePassed ? 'PASS' : 'FAIL',
      )
      const postflightAuthUserIds = selectPostflightAuthUserIds({
        smokeSummary,
        fixtureRunId: fixture.runId,
        fixtureAuthUserId: fixture.authUserId,
        canonicalOwnedAuthUserIds,
      })
      ownership = mergeOwnership({
        smokeOwnership,
        fixture,
        ownedAuthUserIds: postflightAuthUserIds,
        requireFixtureReference: smokePassed,
      })
    } catch (error) {
      const ownershipFailure = asError(error)
      primaryFailure = primaryFailure === null
        ? ownershipFailure
        : new AggregateError(
            [primaryFailure, ownershipFailure],
            'Trading Lab smoke and ownership evidence both failed',
          )
    }
  }
  let postflight = null
  let postflightFailure = null
  try {
    postflight = await probe.after({
      reports: [],
      reportPaths,
      ownership,
    })
    if (postflight?.passed !== true) {
      throw new Error('Trading Lab runtime isolation postflight is not PASS')
    }
  } catch (error) {
    postflightFailure = asError(error)
  }

  const finishedAt = now()
  const artifactRoot = requestedArtifactRoot
    ?? artifactRootFrom(smokeSummary)
  const status = (
    primaryFailure === null
    && postflightFailure === null
    && smokeSummary?.status === 'PASS'
  ) ? 'PASS' : 'FAIL'
  const artifact = redactValue({
    schemaVersion: 1,
    status,
    startedAt,
    finishedAt,
    largeFixture: fixture,
    ownership,
    preflight: {
      status: preflight.passed === true ? 'PASS' : 'FAIL',
      evidence: preflight,
    },
    smoke: {
      status: smokeSummary?.status === 'PASS' ? 'PASS' : 'FAIL',
      artifacts: artifactRoot,
      reportPaths,
      error: primaryFailure === null ? null : serializeError(primaryFailure),
    },
    postflight: {
      status: postflightFailure === null ? 'PASS' : 'FAIL',
      evidence: postflight,
      error: postflightFailure === null
        ? null
        : serializeError(postflightFailure),
    },
  })
  if (artifactRoot !== null) {
    try {
      await writeArtifact(
        artifactRoot,
        'runtime-isolation.json',
        artifact,
      )
    } catch (error) {
      const writeFailure = asError(error)
      postflightFailure = postflightFailure === null
        ? writeFailure
        : new AggregateError(
            [postflightFailure, writeFailure],
            'Trading Lab isolation artifact write failed after postflight',
          )
    }
  }

  if (primaryFailure !== null && postflightFailure !== null) {
    throw new AggregateError(
      [primaryFailure, postflightFailure],
      'Trading Lab smoke and isolation postflight both failed',
    )
  }
  if (primaryFailure !== null) throw primaryFailure
  if (postflightFailure !== null) throw postflightFailure
  return artifact
}

function smokeApiUrl(argv) {
  if (!Array.isArray(argv)) {
    throw new Error('Trading Lab isolated smoke argv must be an array')
  }
  const values = argv
    .filter((argument) => argument.startsWith('--api-url='))
    .map((argument) => argument.slice('--api-url='.length))
  if (values.length > 1) {
    throw new Error('Trading Lab isolated smoke API URL is duplicated')
  }
  if (argv.some((argument) => argument.startsWith('--large-run-id='))) {
    throw new Error(
      'Trading Lab isolated smoke owns the large fixture run ID',
    )
  }
  return values[0]
    ?? process.env.TRADING_LAB_SMOKE_API_URL
    ?? 'http://127.0.0.1:18086'
}

function artifactRootFromArguments(argv) {
  if (!Array.isArray(argv)) {
    throw new Error('Trading Lab isolated smoke argv must be an array')
  }
  const values = argv
    .filter((argument) => argument.startsWith('--artifacts='))
    .map((argument) => argument.slice('--artifacts='.length))
  if (values.length > 1) {
    throw new Error('Trading Lab isolated smoke artifact root is duplicated')
  }
  if (values.length === 1 && values[0].length === 0) {
    throw new Error('Trading Lab isolated smoke artifact root is empty')
  }
  return values[0] ?? null
}

function validateLargeFixture(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Trading Lab large fixture evidence is invalid')
  }
  if (
    JSON.stringify(Object.keys(value).sort())
    !== JSON.stringify([
      'actionCount',
      'authUserId',
      'reportId',
      'requestIds',
      'requestLogObservations',
      'runId',
      'scenarioId',
      'tradingLabRequestIds',
      'uncompressedBytes',
    ])
  ) {
    throw new Error('Trading Lab large fixture evidence keys are invalid')
  }
  for (const field of ['authUserId', 'scenarioId', 'runId', 'reportId']) {
    if (typeof value[field] !== 'string' || !UUID_PATTERN.test(value[field])) {
      throw new Error(
        `Trading Lab large fixture ${field} must be a canonical UUID`,
      )
    }
  }
  if (
    !Number.isSafeInteger(value.uncompressedBytes)
    || value.uncompressedBytes <= LARGE_REPORT_THRESHOLD_BYTES
    || value.uncompressedBytes >= MAX_LARGE_REPORT_BYTES
  ) {
    throw new Error(
      'Trading Lab large fixture report must be > 50 MiB and < 128 MiB',
    )
  }
  if (value.actionCount !== 40) {
    throw new Error('Trading Lab large fixture action count is invalid')
  }
  if (
    !Array.isArray(value.requestIds)
    || value.requestIds.length < 6
    || new Set(value.requestIds).size !== value.requestIds.length
    || value.requestIds.some((requestId) => !UUID_PATTERN.test(requestId))
    || !Array.isArray(value.tradingLabRequestIds)
    || value.tradingLabRequestIds.length < 5
    || value.requestIds.length !== value.tradingLabRequestIds.length + 1
    || new Set(value.tradingLabRequestIds).size
      !== value.tradingLabRequestIds.length
    || value.tradingLabRequestIds.some(
      (requestId) => (
        !UUID_PATTERN.test(requestId)
        || !value.requestIds.includes(requestId)
      ),
    )
    || !Array.isArray(value.requestLogObservations)
    || value.requestLogObservations.length !== value.requestIds.length
    || value.requestLogObservations.some(
      (observation) => !isRequestLogObservation(observation),
    )
    || new Set(
      value.requestLogObservations.map(({ requestId }) => requestId),
    ).size !== value.requestLogObservations.length
    || value.requestIds.some(
      (requestId) => !value.requestLogObservations.some(
        (observation) => observation.requestId === requestId,
      ),
    )
  ) {
    throw new Error('Trading Lab large fixture request IDs are invalid')
  }
  return Object.freeze({
    authUserId: value.authUserId,
    scenarioId: value.scenarioId,
    runId: value.runId,
    reportId: value.reportId,
    uncompressedBytes: value.uncompressedBytes,
    actionCount: value.actionCount,
    requestIds: Object.freeze([...value.requestIds]),
    tradingLabRequestIds: Object.freeze([...value.tradingLabRequestIds]),
    requestLogObservations: Object.freeze(
      value.requestLogObservations.map((observation) =>
        Object.freeze({ ...observation })),
    ),
  })
}

function validateLargeFixtureRecovery(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Trading Lab large fixture recovery is invalid')
  }
  const keys = JSON.stringify(Object.keys(value).sort())
  const provisional = (
    keys === JSON.stringify([
      'actionCount',
      'authUserId',
      'provisional',
      'reportId',
      'requestIds',
      'requestLogObservations',
      'runId',
      'scenarioId',
      'tradingLabRequestIds',
    ])
    && value.provisional === true
  )
  const standalone = (
    keys === JSON.stringify([
      'actionCount',
      'authUserId',
      'provisional',
      'requestIds',
      'requestLogObservations',
      'scenarioId',
      'tradingLabRequestIds',
    ])
    && value.provisional === true
  )
  const terminal = (
    keys === JSON.stringify([
      'actionCount',
      'authUserId',
      'expectedTerminalState',
      'reportId',
      'requestIds',
      'requestLogObservations',
      'runId',
      'scenarioId',
      'tradingLabRequestIds',
    ])
    && TERMINAL_STATES.has(value.expectedTerminalState)
  )
  const identityFields = standalone
    ? ['authUserId', 'scenarioId']
    : ['authUserId', 'scenarioId', 'runId', 'reportId']
  for (const field of identityFields) {
    if (typeof value[field] !== 'string' || !UUID_PATTERN.test(value[field])) {
      throw new Error(
        `Trading Lab large fixture recovery ${field} must be a canonical UUID`,
      )
    }
  }
  if (
    (!provisional && !standalone && !terminal)
    || value.actionCount !== 40
    || !Array.isArray(value.requestIds)
    || value.requestIds.length < (standalone ? 3 : 4)
    || new Set(value.requestIds).size !== value.requestIds.length
    || value.requestIds.some((requestId) => !UUID_PATTERN.test(requestId))
    || !Array.isArray(value.tradingLabRequestIds)
    || value.tradingLabRequestIds.length < (standalone ? 2 : 3)
    || value.requestIds.length !== value.tradingLabRequestIds.length + 1
    || new Set(value.tradingLabRequestIds).size
      !== value.tradingLabRequestIds.length
    || value.tradingLabRequestIds.some(
      (requestId) => (
        !UUID_PATTERN.test(requestId)
        || !value.requestIds.includes(requestId)
      ),
    )
    || !Array.isArray(value.requestLogObservations)
    || value.requestLogObservations.length !== value.requestIds.length
    || value.requestLogObservations.some(
      (observation) => !isRequestLogObservation(observation),
    )
    || new Set(
      value.requestLogObservations.map(({ requestId }) => requestId),
    ).size !== value.requestLogObservations.length
    || value.requestIds.some(
      (requestId) => !value.requestLogObservations.some(
        (observation) => observation.requestId === requestId,
      ),
    )
  ) {
    throw new Error('Trading Lab large fixture recovery evidence is invalid')
  }
  return Object.freeze({
    authUserId: value.authUserId,
    scenarioId: value.scenarioId,
    ...(standalone
      ? {}
      : {
          runId: value.runId,
          reportId: value.reportId,
        }),
    actionCount: value.actionCount,
    ...(provisional || standalone
      ? { provisional: true }
      : { expectedTerminalState: value.expectedTerminalState }),
    requestIds: Object.freeze([...value.requestIds]),
    tradingLabRequestIds: Object.freeze([...value.tradingLabRequestIds]),
    requestLogObservations: Object.freeze(
      value.requestLogObservations.map((observation) =>
        Object.freeze({ ...observation })),
    ),
  })
}

async function writeIsolationArtifact(root, name, value) {
  const ownedRoot = await assertOwnedArtifactDirectory(root)
  await writeFile(
    join(ownedRoot, name),
    `${JSON.stringify(redactValue(value), null, 2)}\n`,
    { encoding: 'utf8', flag: 'wx' },
  )
}

function smokeSummaryFromError(error) {
  const summary = error?.smokeSummary
  return summary !== null && typeof summary === 'object'
    ? summary
    : null
}

function artifactRootFrom(summary) {
  return typeof summary?.artifacts === 'string' && summary.artifacts.length > 0
    ? summary.artifacts
    : null
}

function reportPathsFrom(summary) {
  return Array.isArray(summary?.reportPaths)
    ? summary.reportPaths.filter(
        (path) => typeof path === 'string' && path.length > 0,
      )
    : []
}

function ownershipFrom(summary) {
  const ownership = summary?.ownership
  if (ownership === null || typeof ownership !== 'object') {
    return EMPTY_OWNERSHIP
  }
  return ownership
}

export function selectPostflightAuthUserIds({
  smokeSummary,
  fixtureRunId,
  fixtureAuthUserId,
  canonicalOwnedAuthUserIds,
}) {
  if (!UUID_PATTERN.test(fixtureRunId)) {
    throw new Error('Trading Lab fixture Run ID is invalid')
  }
  const fixtureAuthUserIds = normalizeUuidList(
    [fixtureAuthUserId],
    'fixture auth user ID',
  )
  const canonical = normalizeUuidList(
    canonicalOwnedAuthUserIds,
    'owned auth user IDs',
  )
  if (!canonical.includes(fixtureAuthUserId)) {
    throw new Error(
      'Trading Lab fixture auth user is outside runner-owned auth users',
    )
  }
  if (hasCompletedPermissionAuthSessionReceipt(smokeSummary, fixtureRunId)) {
    if (canonical.length !== 4) {
      throw new Error(
        'Trading Lab permission auth-session receipt requires four owned users',
      )
    }
    return canonical
  }
  if (smokeSummary?.status === 'PASS') {
    throw new Error(
      'Trading Lab PASS smoke lacks completed permission auth-session receipt',
    )
  }
  return fixtureAuthUserIds
}

function hasCompletedPermissionAuthSessionReceipt(summary, fixtureRunId) {
  if (!Array.isArray(summary?.journeys)) return false
  const receipts = summary.journeys.filter(
    (journey) => journey?.name === 'permission journey',
  )
  if (receipts.length !== 1) return false
  const [receipt] = receipts
  if (
    receipt === null
    || typeof receipt !== 'object'
    || Array.isArray(receipt)
    || JSON.stringify(Object.keys(receipt).sort())
      !== JSON.stringify(['details', 'durationMs', 'name', 'status'])
    || receipt.status !== 'PASS'
    || !Number.isSafeInteger(receipt.durationMs)
    || receipt.durationMs < 0
  ) {
    return false
  }
  const details = receipt.details
  return (
    details !== null
    && typeof details === 'object'
    && !Array.isArray(details)
    && JSON.stringify(Object.keys(details).sort())
      === JSON.stringify([
        'directEnvironmentStatus',
        'referencedRunIds',
        'roles',
      ])
    && JSON.stringify(details.roles)
      === JSON.stringify([
        'VIEW-only',
        'EXECUTE',
        'ordinary Admin',
        'SUPER_ADMIN',
      ])
    && details.directEnvironmentStatus === 403
    && JSON.stringify(details.referencedRunIds)
      === JSON.stringify([fixtureRunId])
  )
}

function mergeOwnership({
  smokeOwnership,
  fixture,
  ownedAuthUserIds,
  requireFixtureReference = true,
}) {
  if (
    smokeOwnership === null
    || typeof smokeOwnership !== 'object'
    || Array.isArray(smokeOwnership)
    || ![
      [
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
    ].some(
      (keys) => JSON.stringify(Object.keys(smokeOwnership).sort())
        === JSON.stringify(keys),
    )
  ) {
    throw new Error('Trading Lab smoke ownership schema is invalid')
  }
  const authUserIds = normalizeUuidList(
    ownedAuthUserIds,
    'owned auth user IDs',
  )
  const createdScenarios = [
    ...(smokeOwnership.createdScenarios ?? []),
  ]
  const createdRuns = [...(smokeOwnership.createdRuns ?? [])]
  const referencedRunIds = [...(smokeOwnership.referencedRunIds ?? [])]
  const tradingLabRequestIds = [
    ...(smokeOwnership.tradingLabRequestIds ?? []),
  ]
  const requestLogObservations = [
    ...(smokeOwnership.requestLogObservations ?? []),
  ]
  if (fixture !== null) {
    if (!Object.hasOwn(fixture, 'runId')) {
      if (requireFixtureReference) {
        throw new Error(
          'Trading Lab standalone fixture cannot satisfy a Run reference',
        )
      }
      createdScenarios.push(fixture.scenarioId)
    } else {
      const referencedIndex = referencedRunIds.indexOf(fixture.runId)
      if (referencedIndex < 0 && requireFixtureReference) {
        throw new Error(
          'Trading Lab smoke did not retain the large fixture as referenced',
        )
      }
      if (referencedIndex >= 0) referencedRunIds.splice(referencedIndex, 1)
      const createdRun = {
        scenarioId: fixture.scenarioId,
        runId: fixture.runId,
        reportId: fixture.reportId,
        ...(fixture.provisional === true
          ? { provisional: true }
          : {
              expectedTerminalState:
                fixture.expectedTerminalState ?? 'COMPLETED',
            }),
      }
      createdRuns.push(createdRun)
    }
    tradingLabRequestIds.push(...fixture.tradingLabRequestIds)
    requestLogObservations.push(...fixture.requestLogObservations)
  }
  const normalizedCreatedScenarios = normalizeUuidList(
    createdScenarios,
    'created scenario IDs',
  )
  return Object.freeze({
    ...(normalizedCreatedScenarios.length === 0
      ? {}
      : {
          createdScenarios: Object.freeze(normalizedCreatedScenarios),
        }),
    createdRuns: Object.freeze(createdRuns),
    referencedRunIds: Object.freeze(normalizeUuidList(
      referencedRunIds,
      'referenced run IDs',
    )),
    tradingLabRequestIds: Object.freeze(normalizeUuidList(
      tradingLabRequestIds,
      'request IDs',
    )),
    requestLogObservations: Object.freeze(normalizeRequestLogObservations(
      requestLogObservations,
    )),
    authUserIds: Object.freeze(authUserIds),
  })
}

function ownedAuthUserIdsFromEnvironment() {
  const raw = process.env.TRADING_LAB_SMOKE_OWNED_AUTH_USER_IDS
  return raw === undefined || raw.length === 0 ? [] : raw.split(',')
}

function normalizeUuidList(values, label) {
  if (
    !Array.isArray(values)
    || values.some((value) => !UUID_PATTERN.test(value))
    || new Set(values).size !== values.length
  ) {
    throw new Error(`Trading Lab ${label} are invalid`)
  }
  return [...values]
}

function normalizeRequestLogObservations(observations) {
  if (
    !Array.isArray(observations)
    || observations.some(
      (observation) => !isRequestLogObservation(observation),
    )
    || new Set(observations.map(({ requestId }) => requestId)).size
      !== observations.length
  ) {
    throw new Error('Trading Lab request-log observations are invalid')
  }
  return observations.map((observation) =>
    Object.freeze({ ...observation }))
}

function isRequestLogObservation(value) {
  if (
    value === null
    || typeof value !== 'object'
    || Array.isArray(value)
    || !UUID_PATTERN.test(value.requestId)
  ) {
    return false
  }
  const keys = JSON.stringify(Object.keys(value).sort())
  return (
    (
      keys === JSON.stringify(['requestId', 'requestTupleSha256'])
      && typeof value.requestTupleSha256 === 'string'
      && /^[0-9a-f]{64}$/u.test(value.requestTupleSha256)
    )
    || (
      keys === JSON.stringify([
        'requestId',
        'requestMethodSha256',
        'requestPathSha256',
      ])
      && typeof value.requestMethodSha256 === 'string'
      && /^[0-9a-f]{64}$/u.test(value.requestMethodSha256)
      && typeof value.requestPathSha256 === 'string'
      && /^[0-9a-f]{64}$/u.test(value.requestPathSha256)
    )
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

function serializeError(error, seen = new Set()) {
  const normalized = asError(error)
  if (seen.has(normalized)) {
    return {
      name: normalized.name,
      message: '[Circular error]',
      stack: '',
    }
  }
  seen.add(normalized)
  const serialized = {
    name: normalized.name,
    message: redactText(normalized.message),
    stack: redactText(normalized.stack ?? ''),
    ...(normalized instanceof AggregateError
      ? {
          errors: normalized.errors.map(
            (child) => serializeError(child, seen),
          ),
        }
      : {}),
  }
  seen.delete(normalized)
  return serialized
}

function asError(value) {
  return value instanceof Error ? value : new Error(String(value))
}

function isMainModule() {
  if (process.argv[1] === undefined) return false
  try {
    return realpathSync(process.argv[1])
      === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
}

if (isMainModule()) {
  runIsolatedTradingLabSmoke({ argv: process.argv.slice(2) }).then(
    (summary) => {
      process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`)
    },
    (error) => {
      process.stderr.write(`${JSON.stringify(
        serializeError(asError(error)),
        null,
        2,
      )}\n`)
      process.exitCode = 1
    },
  )
}
