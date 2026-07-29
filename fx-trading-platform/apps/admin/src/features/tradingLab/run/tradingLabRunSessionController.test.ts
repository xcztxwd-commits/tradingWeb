import assert from 'node:assert/strict'
import test from 'node:test'

import { ApiClientError } from '../../../services/apiClient.ts'
import type {
  TradingLabRunControlResult,
  TradingLabRunResponse,
  TradingLabScenarioResponse,
} from '../api/tradingLabApi.ts'
import type {
  TradingLabStreamHandlers,
  TradingLabStreamOptions,
  TradingLabStreamResult,
} from '../api/tradingLabStream.ts'
import { TradingLabStreamError } from '../api/tradingLabStream.ts'
import type { TradingLabScenario } from '../model/types.ts'
import {
  createTradingLabRunSessionController,
  type TradingLabRunRequest,
  type TradingLabRunSessionControllerDependencies,
} from './tradingLabRunSessionController.ts'

const RUN_ID = '11111111-1111-4111-8111-111111111111'
const OTHER_RUN_ID = '99999999-9999-4999-8999-999999999999'
const SCENARIO_ID = '22222222-2222-4222-8222-222222222222'
const OTHER_SCENARIO_ID = '88888888-8888-4888-8888-888888888888'
const REPORT_ID = '33333333-3333-4333-8333-333333333333'
const ACTOR_ID = '44444444-4444-4444-8444-444444444444'
const HASH = 'a'.repeat(64)
const NOW = '2026-07-25T00:00:00Z'

test('malformed or absent locations stay inert with no API, stream, or timer work', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({
    runId: null,
    error: 'runId 必须是 canonical lowercase UUID。',
  })
  harness.flushMicrotasks()
  await settle()

  assert.equal(controller.getSnapshot().locationError?.includes('canonical'), true)
  assert.deepEqual(harness.calls, {
    order: [],
    getRun: [],
    getScenario: [],
    normalizeScenario: 0,
    getToken: 0,
    createScenario: 0,
    createRun: 0,
    controlRun: [],
    stream: [],
    writeRunLocation: [],
  })
  assert.equal(harness.pendingTimerCount(), 0)

  controller.setLocation({ runId: null, error: null })
  harness.flushMicrotasks()
  await settle()
  assert.equal(controller.getSnapshot().session, null)
  assert.equal(harness.calls.getRun.length, 0)
})

test('a valid location performs one ordered GET, normalize, and stream attach', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: false })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  assert.deepEqual(harness.calls.order, [
    `getRun:${RUN_ID}`,
    `getScenario:${SCENARIO_ID}`,
    'normalizeScenario',
    `stream:${RUN_ID}:fresh`,
  ])
  assert.equal(harness.calls.stream.length, 1)
  assert.equal(harness.calls.stream[0]?.options.signal.aborted, false)
  assert.equal(controller.getSnapshot().session?.run?.id, RUN_ID)
  assert.equal(controller.getSnapshot().session?.scenario?.id, SCENARIO_ID)
  assert.equal(controller.getSnapshot().session?.connection, 'CONNECTING')
})

test('durable activity reconciles an initially QUEUED Run through fenced authoritative GETs', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('QUEUED')))
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)
  assert.equal(controller.getSnapshot().session?.run?.state, 'QUEUED')

  harness.queueGetRun(Promise.resolve(runResponse('RESETTING')))
  stream.handlers.onEvent({
    id: '0',
    event: 'api-trace',
    data: {
      correlationId: 'validation-reset',
      method: 'POST',
      payload: { outcome: 'ACCEPTED' },
    },
  })
  await settle()

  assert.equal(harness.calls.getRun.length, 2)
  assert.equal(controller.getSnapshot().session?.run?.state, 'RESETTING')
  assert.equal(controller.getSnapshot().session?.validationState, null)

  harness.queueGetRun(Promise.resolve(runResponse('RUNNING')))
  stream.handlers.onEvent({
    id: '1',
    event: 'state',
    data: { payload: { state: 'RUNNING' } },
  })
  await settle()

  assert.equal(harness.calls.getRun.length, 3)
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
  assert.equal(controller.getSnapshot().session?.validationState, 'RUNNING')
  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('CANCELLING durable bursts coalesce to one in-flight and one trailing refresh', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('CANCELLING', {
    cancelRequested: true,
    version: 3,
  })))
  const firstRefresh = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueGetRun(firstRefresh.promise)
  for (const id of ['0', '1', '2']) {
    stream.handlers.onEvent({
      id,
      event: 'state',
      data: { payload: { state: 'CANCELLING' } },
    })
  }
  assert.equal(harness.calls.getRun.length, 2)

  harness.queueGetRun(Promise.resolve(runResponse('CANCELLING', {
    cancelRequested: true,
    version: 5,
  })))
  firstRefresh.resolve(runResponse('CANCELLING', {
    cancelRequested: true,
    version: 4,
  }))
  await settle()

  assert.equal(harness.calls.getRun.length, 3)
  assert.equal(controller.getSnapshot().session?.run?.state, 'CANCELLING')
  assert.equal(controller.getSnapshot().session?.run?.version, 5)
  assert.equal(controller.getSnapshot().session?.validationState, 'CANCELLING')
})

test('a newer active refresh survives an intermediate authoritative attach', async () => {
  const harness = createHarness()
  const activeRefresh = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueGetRun(activeRefresh.promise)
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  assert.equal(harness.calls.getRun.length, 2)

  controller.acceptAuthoritativeRun(runResponse('RUNNING', {
    pauseRequested: true,
    version: 4,
  }))
  activeRefresh.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  }))
  await settle()

  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.version, 5)
})

test('a late durable-activity refresh cannot cross Run owners', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('QUEUED')))
  const lateRefresh = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const firstStream = harness.calls.stream[0]
  assert.ok(firstStream)

  harness.queueGetRun(lateRefresh.promise)
  firstStream.handlers.onEvent({
    id: '0',
    event: 'api-trace',
    data: { payload: { outcome: 'ACCEPTED' } },
  })
  assert.equal(harness.calls.getRun.length, 2)

  controller.setLocation({ runId: OTHER_RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  lateRefresh.resolve(runResponse('RUNNING', { id: RUN_ID }))
  await settle()

  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
})

test('a late durable-activity refresh cannot regress the same Run after its final GET', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('QUEUED')))
  const lateRefresh = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueGetRun(lateRefresh.promise)
  stream.handlers.onEvent({
    id: '0',
    event: 'api-trace',
    data: { payload: { outcome: 'ACCEPTED' } },
  })
  assert.equal(harness.calls.getRun.length, 2)

  harness.queueGetRun(Promise.resolve(runResponse('COMPLETED', {
    reportId: REPORT_ID,
    version: 9,
  })))
  stream.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  stream.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: '0',
  })
  await settle()

  assert.equal(controller.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(controller.getSnapshot().session?.run?.state, 'COMPLETED')
  assert.equal(controller.getSnapshot().session?.run?.reportId, REPORT_ID)

  lateRefresh.resolve(runResponse('RESETTING', { version: 3 }))
  await settle()

  assert.equal(controller.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(controller.getSnapshot().session?.run?.state, 'COMPLETED')
  assert.equal(controller.getSnapshot().session?.run?.reportId, REPORT_ID)
})

test('an active refresh rejection and pending tail cannot poison terminal close', async () => {
  const harness = createHarness()
  const activeRefresh = deferred<TradingLabRunResponse>()
  const finalRefresh = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueGetRun(activeRefresh.promise)
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'RUNNING' } },
  })
  stream.handlers.onEvent({
    id: '1',
    event: 'state',
    data: { payload: { state: 'RUNNING' } },
  })
  assert.equal(harness.calls.getRun.length, 2)

  harness.queueGetRun(finalRefresh.promise)
  stream.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  stream.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: '1',
  })
  await settle()
  assert.equal(
    controller.getSnapshot().session?.connection,
    'FINAL_REFRESH_REQUIRED',
  )
  assert.equal(harness.calls.getRun.length, 3)

  activeRefresh.reject(new ApiClientError({
    status: 403,
    code: 'TRADING_LAB_ACTIVE_REFRESH_FORBIDDEN',
    message: 'stale active refresh rejected',
  }))
  await settle()

  assert.equal(
    controller.getSnapshot().session?.connection,
    'FINAL_REFRESH_REQUIRED',
  )
  assert.equal(harness.calls.getRun.length, 3)

  finalRefresh.resolve(runResponse('COMPLETED', { version: 9 }))
  await settle()
  assert.equal(controller.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(controller.getSnapshot().session?.run?.state, 'COMPLETED')
})

test('EXECUTE-only authority changes preserve the active read owner', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  const stream = harness.calls.stream[0]
  const session = controller.getSnapshot().session
  assert.ok(stream)
  assert.ok(session)

  controller.setAccess({ canView: true, canExecute: false })
  harness.flushMicrotasks()
  await settle()

  assert.equal(stream.options.signal.aborted, false)
  assert.equal(controller.getSnapshot().session, session)
  assert.equal(harness.calls.getRun.length, 1)
  assert.equal(harness.calls.getScenario.length, 1)
  assert.equal(harness.calls.stream.length, 1)
})

test('attach keeps document-local identity separate from the frozen entity', async () => {
  const harness = createHarness()
  harness.queueNormalize(Promise.resolve({
    ...scenario(),
    id: OTHER_SCENARIO_ID,
  }))
  const controller = createTradingLabRunSessionController(harness.dependencies)

  controller.setAccess({ canView: true, canExecute: false })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  assert.equal(harness.calls.stream.length, 1)
  assert.equal(controller.getSnapshot().session?.connection, 'CONNECTING')
  assert.equal(controller.getSnapshot().session?.run?.scenarioId, SCENARIO_ID)
  assert.equal(
    controller.getSnapshot().session?.scenario?.id,
    OTHER_SCENARIO_ID,
  )
})

test('location replacement aborts the old reader and fences its late event', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const first = harness.calls.stream[0]
  assert.ok(first)

  controller.setLocation({ runId: OTHER_RUN_ID, error: null })
  assert.equal(first.options.signal.aborted, true)
  harness.flushMicrotasks()
  await settle()
  const second = harness.calls.stream[1]
  assert.ok(second)
  assert.equal(second.runId, OTHER_RUN_ID)

  first.handlers.onEvent({
    id: '0',
    event: 'tick',
    data: {
      virtualTime: '2026-07-25T00:00:01.000Z',
      payload: { tickSequence: 1 },
    },
  })
  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.highestDurableEventId, null)

  controller.dispose()
  assert.equal(second.options.signal.aborted, true)
  assert.equal(harness.pendingTimerCount(), 0)
})

test('replaceOwner publish reentrancy cannot restore a stale owner', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  let switched = false
  const unsubscribe = controller.subscribe(() => {
    const current = controller.getSnapshot()
    if (
      !switched
      && current.runId === RUN_ID
      && current.session?.runId === RUN_ID
    ) {
      switched = true
      controller.setLocation({ runId: OTHER_RUN_ID, error: null })
    }
  })

  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  unsubscribe()

  assert.equal(switched, true)
  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.deepEqual(harness.calls.getRun, [OTHER_RUN_ID])
  const stream = harness.calls.stream[0]
  assert.equal(stream?.runId, OTHER_RUN_ID)
  assert.ok(stream)

  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    id: OTHER_RUN_ID,
    pauseRequested: true,
    version: 4,
  })))
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  await settle()
  assert.deepEqual(harness.calls.getRun, [OTHER_RUN_ID, OTHER_RUN_ID])
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
})

test('create writes one URL and attaches only through the unified location owner', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 1)
  assert.deepEqual(harness.calls.writeRunLocation, [RUN_ID])
  assert.equal(harness.calls.stream.length, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')

  harness.flushMicrotasks()
  await settle()
  assert.equal(harness.calls.getRun.length, 1)
  assert.equal(harness.calls.stream.length, 1)
  assert.equal(controller.getSnapshot().runId, RUN_ID)
})

test('a CREATING publish owner switch stops before every create POST', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  let switched = false
  const unsubscribe = controller.subscribe(() => {
    const current = controller.getSnapshot()
    if (
      !switched
      && current.runRequestState === 'CREATING'
      && current.runId === null
    ) {
      switched = true
      controller.setLocation({ runId: OTHER_RUN_ID, error: null })
    }
  })

  await controller.requestRun(runRequest())
  assert.match(controller.getSnapshot().message ?? '', /正在恢复 Run/)
  harness.flushMicrotasks()
  await settle()
  unsubscribe()

  assert.equal(switched, true)
  assert.equal(harness.calls.createScenario, 0)
  assert.equal(harness.calls.createRun, 0)
  assert.deepEqual(harness.calls.writeRunLocation, [])
  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
})

test('EXECUTE revocation after Scenario creation stops before createRun POST', async () => {
  const harness = createHarness()
  const createdScenario = deferred<TradingLabScenarioResponse>()
  harness.queueCreateScenario(createdScenario.promise)
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })

  const pending = controller.requestRun(runRequest())
  await settle()
  assert.equal(harness.calls.createScenario, 1)
  controller.setAccess({ canView: true, canExecute: false })
  createdScenario.resolve(scenarioResponse('DRAFT'))
  await pending

  assert.equal(harness.calls.createRun, 0)
  assert.deepEqual(harness.calls.writeRunLocation, [])
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
  assert.equal(controller.getSnapshot().message, null)
})

test('a malformed Scenario response stays unknown across EXECUTE revocation', async () => {
  const harness = createHarness()
  const createdScenario = deferred<TradingLabScenarioResponse>()
  harness.queueCreateScenario(createdScenario.promise)
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })

  const pending = controller.requestRun(runRequest())
  await settle()
  assert.equal(harness.calls.createScenario, 1)
  controller.setAccess({ canView: true, canExecute: false })
  createdScenario.resolve({
    ...scenarioResponse('DRAFT'),
    configSnapshotHash: 'b'.repeat(64),
  })
  await pending

  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'CREATE_UNKNOWN')
  controller.setAccess({ canView: true, canExecute: true })
  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
})

test('confirmed Scenario plus a local pre-Run token failure stays retryable', async () => {
  const harness = createHarness()
  let tokenReads = 0
  const controller = createTradingLabRunSessionController({
    ...harness.dependencies,
    getToken: () => {
      tokenReads += 1
      if (tokenReads === 2) {
        throw new Error('token storage unavailable')
      }
      return 'admin-token'
    },
  })
  controller.setAccess({ canView: true, canExecute: true })

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 2)
  assert.equal(harness.calls.createRun, 1)
  assert.deepEqual(harness.calls.writeRunLocation, [RUN_ID])
})

test('a missing pre-Run token stops creation in one visible state transition', async () => {
  const harness = createHarness()
  let tokenReads = 0
  const controller = createTradingLabRunSessionController({
    ...harness.dependencies,
    getToken: () => {
      tokenReads += 1
      return tokenReads === 2 ? null : 'admin-token'
    },
  })
  const observed: Array<ReturnType<typeof controller.getSnapshot>> = []
  controller.setAccess({ canView: true, canExecute: true })
  const unsubscribe = controller.subscribe(() => {
    observed.push(controller.getSnapshot())
  })

  await controller.requestRun(runRequest())
  unsubscribe()

  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
  assert.match(controller.getSnapshot().message ?? '', /管理员会话不可用/)
  assert.equal(
    observed.some((snapshot) => (
      snapshot.runRequestState === 'IDLE'
      && snapshot.message === '正在校验并创建冻结场景…'
    )),
    false,
  )
})

test('local request serialization failures never claim an unsent POST is unknown', async () => {
  const scenarioSerializationFailure = createHarness()
  const unsafeScenario = scenario()
  Object.defineProperty(unsafeScenario, 'toJSON', {
    configurable: true,
    enumerable: false,
    value: () => {
      throw new TypeError('scenario serialization failed')
    },
  })
  const scenarioController = createTradingLabRunSessionController(
    scenarioSerializationFailure.dependencies,
  )
  scenarioController.setAccess({ canView: true, canExecute: true })

  await scenarioController.requestRun({
    scenario: unsafeScenario,
    localCalculation: runRequest().localCalculation,
  })
  assert.equal(scenarioSerializationFailure.calls.createScenario, 0)
  assert.equal(
    scenarioController.getSnapshot().runRequestState,
    'IDLE',
  )

  const runSerializationFailure = createHarness()
  const cyclicCalculation: Record<string, unknown> = {
    status: 'BLOCKED',
    runnerIssues: [],
  }
  cyclicCalculation.self = cyclicCalculation
  const runController = createTradingLabRunSessionController(
    runSerializationFailure.dependencies,
  )
  runController.setAccess({ canView: true, canExecute: true })

  await runController.requestRun({
    scenario: scenario(),
    localCalculation: cyclicCalculation as never,
  })
  assert.equal(runSerializationFailure.calls.createScenario, 1)
  assert.equal(runSerializationFailure.calls.createRun, 0)
  assert.equal(runController.getSnapshot().runRequestState, 'IDLE')
})

test('Scenario serialization reentrancy cannot cross the create POST gate', async () => {
  const harness = createHarness()
  const unsafeScenario = scenario()
  const serializedScenario = { ...unsafeScenario }
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  Object.defineProperty(unsafeScenario, 'toJSON', {
    configurable: true,
    enumerable: false,
    value: () => {
      controller.setAccess({ canView: true, canExecute: false })
      return serializedScenario
    },
  })

  await controller.requestRun({
    scenario: unsafeScenario,
    localCalculation: runRequest().localCalculation,
  })

  assert.equal(harness.calls.createScenario, 0)
  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
  assert.equal(controller.getSnapshot().message, null)
})

test('local calculation serialization reentrancy cannot cross the Run POST gate', async () => {
  const harness = createHarness()
  const request = runRequest()
  const unsafeCalculation = request.localCalculation
  const serializedCalculation = { ...unsafeCalculation }
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  Object.defineProperty(unsafeCalculation, 'toJSON', {
    configurable: true,
    enumerable: false,
    value: () => {
      controller.setAccess({ canView: true, canExecute: false })
      return serializedCalculation
    },
  })

  await controller.requestRun({
    scenario: request.scenario,
    localCalculation: unsafeCalculation,
  })

  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
  assert.equal(controller.getSnapshot().message, null)
})

test('EXECUTE revocation after createRun POST still attaches its known success', async () => {
  const harness = createHarness()
  const createdRun = deferred<TradingLabRunResponse>()
  harness.queueCreateRun(createdRun.promise)
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })

  const pending = controller.requestRun(runRequest())
  await settle()
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 1)
  controller.setAccess({ canView: true, canExecute: false })
  createdRun.resolve(runResponse('QUEUED'))
  await pending

  assert.deepEqual(harness.calls.writeRunLocation, [RUN_ID])
  assert.equal(controller.getSnapshot().runId, RUN_ID)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
  harness.flushMicrotasks()
  await settle()
  await controller.controlRun('cancel')
  assert.deepEqual(harness.calls.controlRun, [])
})

test('a success-tail location switch is not overwritten by created Run navigation', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  let switched = false
  const unsubscribe = controller.subscribe(() => {
    const current = controller.getSnapshot()
    if (
      !switched
      && current.runRequestState === 'IDLE'
      && harness.calls.createRun === 1
    ) {
      switched = true
      controller.setLocation({ runId: OTHER_RUN_ID, error: null })
    }
  })

  await controller.requestRun(runRequest())
  harness.flushMicrotasks()
  await settle()
  unsubscribe()

  assert.equal(switched, true)
  assert.deepEqual(harness.calls.writeRunLocation, [RUN_ID])
  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
})

test('definite create failures unlock, but network uncertainty is permanently non-replayable', async () => {
  const definite = createHarness()
  const definiteController = createTradingLabRunSessionController(
    definite.dependencies,
  )
  definiteController.setAccess({ canView: true, canExecute: true })
  definite.queueCreateScenario(Promise.reject(new ApiClientError({
    status: 400,
    code: 'TRADING_LAB_SCENARIO_INVALID',
    message: 'invalid scenario',
  })))

  await definiteController.requestRun(runRequest())
  assert.equal(definite.calls.createScenario, 1)
  assert.equal(definite.calls.createRun, 0)
  assert.equal(definiteController.getSnapshot().runRequestState, 'IDLE')

  const uncertain = createHarness()
  const uncertainController = createTradingLabRunSessionController(
    uncertain.dependencies,
  )
  uncertainController.setAccess({ canView: true, canExecute: true })
  uncertain.queueCreateScenario(
    Promise.reject(new TypeError('response lost')),
  )

  await uncertainController.requestRun(runRequest())
  assert.equal(uncertainController.getSnapshot().runRequestState, 'CREATE_UNKNOWN')
  await uncertainController.requestRun(runRequest())
  assert.equal(uncertain.calls.createScenario, 1)
  assert.equal(uncertain.calls.createRun, 0)
  assert.deepEqual(uncertain.calls.writeRunLocation, [])
})

test('a semantically malformed successful create response locks as unknown', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  harness.queueCreateScenario(Promise.resolve({
    ...scenarioResponse('DRAFT'),
    configSnapshotHash: 'b'.repeat(64),
  }))

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'CREATE_UNKNOWN')
  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
})

test('uncertainty from the second create POST also locks without replay', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  harness.queueCreateRun(Promise.reject(new TypeError('run response lost')))

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 1)
  assert.equal(controller.getSnapshot().runRequestState, 'CREATE_UNKNOWN')
  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 1)
})

test('a mismatched successful Run response locks before writing the URL', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  harness.queueCreateRun(Promise.resolve(runResponse('QUEUED', {
    scenarioId: OTHER_SCENARIO_ID,
  })))

  await controller.requestRun(runRequest())
  assert.equal(harness.calls.createScenario, 1)
  assert.equal(harness.calls.createRun, 1)
  assert.equal(controller.getSnapshot().runRequestState, 'CREATE_UNKNOWN')
  assert.deepEqual(harness.calls.writeRunLocation, [])
})

test('unsettled, stale CALCULATED, and LOCAL-only requests stop before POST', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })

  await controller.requestRun({
    scenario: scenario(),
    localCalculation: { status: 'IDLE' } as never,
  })
  assert.equal(harness.calls.createScenario, 0)

  await controller.requestRun({
    scenario: scenario(),
    localCalculation: {
      status: 'CALCULATED',
      result: {
        configSnapshotHash: 'b'.repeat(64),
        modelVersion: 'model-v1',
      },
      runnerIssues: [],
    } as never,
  })
  assert.equal(harness.calls.createScenario, 0)

  const localOnly = scenario()
  localOnly.timeline[0] = {
    ...localOnly.timeline[0]!,
    type: 'ADD_MARGIN',
    parameters: { amount: '1' },
  }
  await controller.requestRun({
    scenario: localOnly,
    localCalculation: runRequest().localCalculation,
  })
  assert.equal(harness.calls.createScenario, 0)
  assert.equal(controller.getSnapshot().runRequestState, 'IDLE')
})

test('late GET and scenario results cannot attach after an owner switch', async () => {
  const lateRunHarness = createHarness()
  const lateRun = deferred<TradingLabRunResponse>()
  lateRunHarness.queueGetRun(lateRun.promise)
  const lateRunController = createTradingLabRunSessionController(
    lateRunHarness.dependencies,
  )
  lateRunController.setAccess({ canView: true, canExecute: true })
  lateRunController.setLocation({ runId: RUN_ID, error: null })
  lateRunHarness.flushMicrotasks()
  await settle()
  assert.equal(lateRunHarness.calls.getRun.length, 1)

  lateRunController.setLocation({ runId: OTHER_RUN_ID, error: null })
  lateRunHarness.flushMicrotasks()
  await settle()
  assert.equal(lateRunHarness.calls.stream.length, 1)
  assert.equal(lateRunHarness.calls.stream[0]?.runId, OTHER_RUN_ID)

  lateRun.resolve(runResponse('RUNNING', { id: RUN_ID }))
  await settle()
  assert.equal(lateRunHarness.calls.stream.length, 1)
  assert.equal(lateRunController.getSnapshot().runId, OTHER_RUN_ID)

  const lateScenarioHarness = createHarness()
  const lateScenario = deferred<TradingLabScenarioResponse>()
  lateScenarioHarness.queueGetScenario(lateScenario.promise)
  const lateScenarioController = createTradingLabRunSessionController(
    lateScenarioHarness.dependencies,
  )
  lateScenarioController.setAccess({ canView: true, canExecute: true })
  lateScenarioController.setLocation({ runId: RUN_ID, error: null })
  lateScenarioHarness.flushMicrotasks()
  await settle()
  assert.equal(lateScenarioHarness.calls.getScenario.length, 1)

  lateScenarioController.setLocation({ runId: OTHER_RUN_ID, error: null })
  lateScenarioHarness.flushMicrotasks()
  await settle()
  assert.equal(lateScenarioHarness.calls.stream.length, 1)
  assert.equal(lateScenarioHarness.calls.stream[0]?.runId, OTHER_RUN_ID)

  lateScenario.resolve(scenarioResponse())
  await settle()
  assert.equal(lateScenarioHarness.calls.stream.length, 1)
  assert.equal(lateScenarioController.getSnapshot().runId, OTHER_RUN_ID)
})

test('VIEW revocation aborts reads and EXECUTE absence blocks mutations', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  assert.equal(harness.calls.getRun.length, 0)

  controller.setAccess({ canView: true, canExecute: false })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  await controller.requestRun(runRequest())
  await controller.controlRun('pause')
  assert.equal(harness.calls.createScenario, 0)
  assert.equal(harness.calls.controlRun.length, 0)

  controller.setAccess({ canView: false, canExecute: false })
  assert.equal(stream.options.signal.aborted, true)
  assert.equal(controller.getSnapshot().session, null)
  assert.equal(harness.pendingTimerCount(), 0)
})

test('transient stream failures refresh first, keep one timer, and reconnect from the cursor', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const first = harness.calls.stream[0]
  assert.ok(first)

  first.deferred.reject(transientStreamError(null))
  await settle()
  assert.equal(harness.calls.getRun.length, 2)
  assert.deepEqual(harness.pendingTimerDelays(), [250])
  assert.equal(harness.calls.stream.length, 1)

  assert.equal(harness.runNextTimer(), true)
  await settle()
  const second = harness.calls.stream[1]
  assert.ok(second)
  assert.equal(second.options.lastEventId, undefined)
  assert.equal(harness.pendingTimerCount(), 0)

  second.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { tickCount: 10 } },
  })
  second.deferred.reject(transientStreamError('0'))
  await settle()
  assert.deepEqual(harness.pendingTimerDelays(), [250])

  assert.equal(harness.runNextTimer(), true)
  await settle()
  const third = harness.calls.stream[2]
  assert.ok(third)
  assert.equal(third.options.lastEventId, '0')
  assert.equal(harness.calls.stream.length, 3)
  assert.equal(third.options.signal.aborted, false)
  assert.equal(harness.pendingTimerCount(), 0)
})

test('a terminal refresh gets exactly one stream-drain attempt before fail-closed', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueGetRun(Promise.resolve(runResponse('COMPLETED')))
  harness.calls.stream[0]?.deferred.reject(transientStreamError(null))
  await settle()
  assert.equal(harness.calls.stream.length, 2)
  assert.equal(harness.pendingTimerCount(), 0)

  harness.queueGetRun(Promise.resolve(runResponse('COMPLETED')))
  harness.calls.stream[1]?.deferred.reject(transientStreamError(null))
  await settle()
  assert.equal(harness.calls.stream.length, 2)
  assert.equal(harness.pendingTimerCount(), 0)
  assert.equal(controller.getSnapshot().session?.connection, 'BLOCKED')
  assert.equal(
    controller.getSnapshot().session?.transportIssue?.code,
    'TRADING_LAB_TERMINAL_COMPLETE_MISSING',
  )
})

test('nonretryable transport failure blocks without a timer, while product error stays evidence', async () => {
  const blockedHarness = createHarness()
  const blockedController = createTradingLabRunSessionController(
    blockedHarness.dependencies,
  )
  blockedController.setAccess({ canView: true, canExecute: true })
  blockedController.setLocation({ runId: RUN_ID, error: null })
  blockedHarness.flushMicrotasks()
  await settle()
  blockedHarness.calls.stream[0]?.deferred.reject(
    new TradingLabStreamError({
      kind: 'HTTP',
      retryable: false,
      status: 403,
      lastEventId: null,
      message: 'forbidden',
    }),
  )
  await settle()
  assert.equal(blockedController.getSnapshot().session?.connection, 'BLOCKED')
  assert.equal(blockedHarness.pendingTimerCount(), 0)
  assert.equal(blockedHarness.calls.getRun.length, 1)

  const productHarness = createHarness()
  const productController = createTradingLabRunSessionController(
    productHarness.dependencies,
  )
  productController.setAccess({ canView: true, canExecute: true })
  productController.setLocation({ runId: RUN_ID, error: null })
  productHarness.flushMicrotasks()
  await settle()
  productHarness.calls.stream[0]?.handlers.onEvent({
    id: '0',
    event: 'error',
    data: {
      payload: {
        code: 'EXPECTED_ERROR',
        message: 'negative evidence',
      },
    },
  })
  assert.equal(productController.getSnapshot().session?.productErrorCount, 1)
  assert.equal(productController.getSnapshot().session?.connection, 'OPEN')
  assert.equal(productHarness.calls.stream.length, 1)
  assert.equal(productHarness.pendingTimerCount(), 0)
})

test('complete closes only after a matching final GET and blocks on mismatch', async () => {
  const matching = createHarness()
  const matchingController = createTradingLabRunSessionController(
    matching.dependencies,
  )
  matchingController.setAccess({ canView: true, canExecute: true })
  matchingController.setLocation({ runId: RUN_ID, error: null })
  matching.flushMicrotasks()
  await settle()
  matching.queueGetRun(Promise.resolve(runResponse('COMPLETED')))
  matching.calls.stream[0]?.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  matching.calls.stream[0]?.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: null,
  })
  await settle()
  assert.equal(matching.calls.getRun.length, 2)
  assert.equal(matchingController.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(matchingController.getSnapshot().session?.run?.state, 'COMPLETED')
  assert.equal(matching.pendingTimerCount(), 0)

  const mismatch = createHarness()
  const mismatchController = createTradingLabRunSessionController(
    mismatch.dependencies,
  )
  mismatchController.setAccess({ canView: true, canExecute: true })
  mismatchController.setLocation({ runId: RUN_ID, error: null })
  mismatch.flushMicrotasks()
  await settle()
  mismatch.queueGetRun(Promise.resolve(runResponse('FAILED')))
  mismatch.calls.stream[0]?.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  mismatch.calls.stream[0]?.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: null,
  })
  await settle()
  assert.equal(mismatchController.getSnapshot().session?.connection, 'BLOCKED')
  assert.equal(
    mismatchController.getSnapshot().session?.transportIssue?.code,
    'TRADING_LAB_FINAL_STATE_MISMATCH',
  )
})

test('final GET transient retries are bounded and fail closed when exhausted', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueGetRun(Promise.reject(new TypeError('final unavailable 0')))
  harness.calls.stream[0]?.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  harness.calls.stream[0]?.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: null,
  })
  await settle()
  assert.deepEqual(harness.pendingTimerDelays(), [250])

  const expectedDelays = [500, 1_000, 2_000, 5_000]
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    harness.queueGetRun(
      Promise.reject(new TypeError(`final unavailable ${attempt}`)),
    )
    assert.equal(harness.runNextTimer(), true)
    await settle()
    if (attempt <= expectedDelays.length) {
      assert.deepEqual(
        harness.pendingTimerDelays(),
        [expectedDelays[attempt - 1]],
      )
    }
  }

  assert.equal(harness.pendingTimerCount(), 0)
  assert.equal(controller.getSnapshot().session?.connection, 'BLOCKED')
  assert.equal(
    controller.getSnapshot().session?.transportIssue?.code,
    'TRADING_LAB_FINAL_REFRESH_FAILED',
  )
})

test('control response never changes the Run before its authoritative follow-up GET', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  const control = deferred<TradingLabRunControlResult>()
  const followUp = deferred<TradingLabRunResponse>()
  harness.queueControlRun(control.promise)
  harness.queueGetRun(followUp.promise)
  const pending = controller.controlRun('pause')
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')

  control.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 4,
    pauseRequested: true,
    cancelRequested: false,
  })
  await settle()
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, false)
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  followUp.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  }))
  await pending
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, true)
  assert.equal(controller.getSnapshot().controlPending, null)
})

test('a non-advancing control receipt stays fail-closed without a follow-up GET', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 3,
    pauseRequested: true,
    cancelRequested: false,
  }))
  await controller.controlRun('pause')

  assert.equal(harness.calls.getRun.length, 1)
  assert.equal(controller.getSnapshot().session?.run?.version, 3)
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, false)
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.match(
    controller.getSnapshot().message ?? '',
    /did not advance|version|保持锁定/iu,
  )
  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('action-inconsistent control receipts stay fail-closed before follow-up GET', async () => {
  const cases: ReadonlyArray<Readonly<{
    action: 'pause' | 'resume' | 'cancel'
    initial: TradingLabRunResponse
    receipt: TradingLabRunControlResult
  }>> = [
    {
      action: 'pause',
      initial: runResponse('RUNNING', { version: 3 }),
      receipt: {
        runId: RUN_ID,
        state: 'RUNNING',
        runVersion: 4,
        pauseRequested: false,
        cancelRequested: false,
      },
    },
    {
      action: 'resume',
      initial: runResponse('PAUSED', {
        pauseRequested: true,
        version: 5,
      }),
      receipt: {
        runId: RUN_ID,
        state: 'PAUSED',
        runVersion: 6,
        pauseRequested: true,
        cancelRequested: false,
      },
    },
    {
      action: 'cancel',
      initial: runResponse('RUNNING', { version: 7 }),
      receipt: {
        runId: RUN_ID,
        state: 'RUNNING',
        runVersion: 8,
        pauseRequested: false,
        cancelRequested: false,
      },
    },
  ]

  for (const { action, initial, receipt } of cases) {
    const harness = createHarness()
    harness.queueGetRun(Promise.resolve(initial))
    const controller = createTradingLabRunSessionController(
      harness.dependencies,
    )
    controller.setAccess({ canView: true, canExecute: true })
    controller.setLocation({ runId: RUN_ID, error: null })
    harness.flushMicrotasks()
    await settle()
    harness.queueControlRun(Promise.resolve(receipt))

    await controller.controlRun(action)

    assert.equal(harness.calls.getRun.length, 1, action)
    assert.equal(controller.getSnapshot().controlPending, action, action)
    assert.match(
      controller.getSnapshot().message ?? '',
      /flag|confirm|保持锁定|不一致/iu,
      action,
    )
    assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:${action}`])
  }
})

test('a same-watermark GET with the wrong action flag stays locked', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: true,
    cancelRequested: false,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    pauseRequested: false,
    version: 4,
  })))
  await controller.controlRun('pause')

  assert.equal(controller.getSnapshot().session?.run?.version, 4)
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, false)
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.match(
    controller.getSnapshot().message ?? '',
    /flag|confirm|保持锁定|不一致/iu,
  )
  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('a newer authoritative mutation settles a confirmed superseded control', async () => {
  const cases: ReadonlyArray<Readonly<{
    action: 'pause' | 'resume'
    initial: TradingLabRunResponse
    receipt: TradingLabRunControlResult
    newer: TradingLabRunResponse
  }>> = [
    {
      action: 'pause',
      initial: runResponse('RUNNING', { version: 3 }),
      receipt: {
        runId: RUN_ID,
        state: 'RUNNING',
        runVersion: 4,
        pauseRequested: true,
        cancelRequested: false,
      },
      newer: runResponse('PAUSED', {
        pauseRequested: false,
        version: 6,
      }),
    },
    {
      action: 'resume',
      initial: runResponse('PAUSED', {
        pauseRequested: true,
        version: 5,
      }),
      receipt: {
        runId: RUN_ID,
        state: 'PAUSED',
        runVersion: 6,
        pauseRequested: false,
        cancelRequested: false,
      },
      newer: runResponse('RUNNING', {
        pauseRequested: true,
        version: 8,
      }),
    },
  ]

  for (const { action, initial, receipt, newer } of cases) {
    const harness = createHarness()
    harness.queueGetRun(Promise.resolve(initial))
    const controller = createTradingLabRunSessionController(
      harness.dependencies,
    )
    controller.setAccess({ canView: true, canExecute: true })
    controller.setLocation({ runId: RUN_ID, error: null })
    harness.flushMicrotasks()
    await settle()
    harness.queueControlRun(Promise.resolve(receipt))
    harness.queueGetRun(Promise.resolve(newer))

    await controller.controlRun(action)

    assert.equal(
      controller.getSnapshot().session?.run?.version,
      newer.version,
      action,
    )
    assert.equal(controller.getSnapshot().controlPending, null, action)
    assert.equal(controller.getSnapshot().message, null, action)
    assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:${action}`])
  }
})

test('a matching cancel receipt and GET settle the control normally', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: false,
    cancelRequested: true,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    cancelRequested: true,
    version: 4,
  })))
  await controller.controlRun('cancel')

  assert.equal(controller.getSnapshot().session?.run?.cancelRequested, true)
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:cancel`])
})

test('a newer Run cannot erase an accepted one-way cancel flag', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: false,
    cancelRequested: true,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('CANCELLING', {
    cancelRequested: false,
    version: 5,
  })))
  await controller.controlRun('cancel')

  assert.equal(controller.getSnapshot().session?.run?.version, 5)
  assert.equal(controller.getSnapshot().session?.run?.cancelRequested, false)
  assert.equal(controller.getSnapshot().controlPending, 'cancel')
  assert.match(
    controller.getSnapshot().message ?? '',
    /flag|confirm|保持锁定|不一致/iu,
  )
  await controller.controlRun('cancel')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:cancel`])
})

test('durable state activity settles accepted pause and resume controls after an early GET', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    pauseRequested: true,
    version: 4,
  })))
  await controller.controlRun('pause')
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, true)

  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  })))
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  await settle()

  assert.equal(harness.calls.getRun.length, 3)
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.validationState, 'PAUSED')

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 6,
    pauseRequested: false,
    cancelRequested: false,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: false,
    version: 6,
  })))
  await controller.controlRun('resume')
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, false)

  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    pauseRequested: false,
    version: 7,
  })))
  stream.handlers.onEvent({
    id: '1',
    event: 'state',
    data: { payload: { state: 'RUNNING' } },
  })
  await settle()

  assert.equal(harness.calls.getRun.length, 5)
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
  assert.equal(controller.getSnapshot().session?.validationState, 'RUNNING')
  assert.deepEqual(harness.calls.controlRun, [
    `${RUN_ID}:pause`,
    `${RUN_ID}:resume`,
  ])
})

test('an accepted resume cannot be replayed while PAUSED is still catching up', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  })))
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 6,
    pauseRequested: false,
    cancelRequested: false,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: false,
    version: 6,
  })))
  await controller.controlRun('resume')
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.pauseRequested, false)

  await controller.controlRun('resume')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:resume`])
  assert.equal(controller.getSnapshot().controlPending, null)
})

test('an older control follow-up GET cannot regress a newer durable state refresh', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  const staleFollowUp = deferred<TradingLabRunResponse>()
  harness.queueGetRun(staleFollowUp.promise)
  const pending = controller.controlRun('pause')
  await settle()
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  })))
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  await settle()
  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.version, 5)

  staleFollowUp.resolve(runResponse('RUNNING', {
    pauseRequested: true,
    version: 4,
  }))
  await pending

  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.version, 5)
  assert.equal(controller.getSnapshot().controlPending, null)
})

test('a malformed control follow-up GET blocks and keeps the accepted control locked', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    id: OTHER_RUN_ID,
    pauseRequested: true,
    version: 4,
  })))
  await controller.controlRun('pause')

  assert.equal(controller.getSnapshot().session?.connection, 'BLOCKED')
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.match(
    controller.getSnapshot().message ?? '',
    /cannot be attached|malformed|不能附加|错误/iu,
  )

  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('a control follow-up older than the accepted mutation watermark stays locked', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 5,
    pauseRequested: true,
    cancelRequested: false,
  }))
  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    pauseRequested: true,
    version: 4,
  })))
  await controller.controlRun('pause')

  assert.equal(controller.getSnapshot().session?.run?.version, 3)
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.match(
    controller.getSnapshot().message ?? '',
    /version|watermark|保持锁定|较旧/iu,
  )
  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('a later durable refresh settles an accepted control after its follow-up was lost', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 5,
    pauseRequested: true,
    cancelRequested: false,
  }))
  harness.queueGetRun(Promise.reject(new TypeError('follow-up lost')))
  await controller.controlRun('pause')
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  })))
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  await settle()

  assert.equal(controller.getSnapshot().session?.run?.state, 'PAUSED')
  assert.equal(controller.getSnapshot().session?.run?.version, 5)
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)
})

test('a pre-receipt durable refresh settles the matching control watermark', async () => {
  const harness = createHarness()
  const control = deferred<TradingLabRunControlResult>()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueControlRun(control.promise)
  harness.queueGetRun(Promise.resolve(runResponse('PAUSED', {
    pauseRequested: true,
    version: 5,
  })))
  const pending = controller.controlRun('pause')
  stream.handlers.onEvent({
    id: '0',
    event: 'state',
    data: { payload: { state: 'PAUSED' } },
  })
  await settle()
  assert.equal(controller.getSnapshot().session?.run?.version, 5)
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  control.resolve({
    runId: RUN_ID,
    state: 'PAUSED',
    runVersion: 5,
    pauseRequested: true,
    cancelRequested: false,
  })
  await pending

  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)
  assert.equal(harness.calls.getRun.length, 2)
})

test('a terminal final GET settlement ignores a late control follow-up failure', async () => {
  const harness = createHarness()
  const followUp = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  const stream = harness.calls.stream[0]
  assert.ok(stream)

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: true,
    cancelRequested: false,
  }))
  harness.queueGetRun(followUp.promise)
  const pending = controller.controlRun('pause')
  await settle()
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  harness.queueGetRun(Promise.resolve(runResponse('COMPLETED', {
    pauseRequested: true,
    version: 5,
  })))
  stream.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  stream.deferred.resolve({
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: null,
  })
  await settle()
  assert.equal(controller.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)

  followUp.reject(new TypeError('late follow-up failed'))
  await pending
  assert.equal(controller.getSnapshot().session?.connection, 'CLOSED')
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)
})

test('an accepted control follow-up cannot cross a replaced Run owner', async () => {
  const harness = createHarness()
  const followUp = deferred<TradingLabRunResponse>()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: true,
    cancelRequested: false,
  }))
  harness.queueGetRun(followUp.promise)
  const pending = controller.controlRun('pause')
  await settle()
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  controller.setLocation({ runId: OTHER_RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  followUp.resolve(runResponse('PAUSED', {
    id: RUN_ID,
    pauseRequested: true,
    version: 4,
  }))
  await pending

  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(controller.getSnapshot().message, null)
})

test('a location publish cannot reentrantly control the stale session owner', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  let attempted = false
  const unsubscribe = controller.subscribe(() => {
    const current = controller.getSnapshot()
    if (
      !attempted
      && current.runId === OTHER_RUN_ID
      && current.session?.run?.id === RUN_ID
    ) {
      attempted = true
      void controller.controlRun('pause')
    }
  })

  controller.setLocation({ runId: OTHER_RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  unsubscribe()

  assert.equal(attempted, true)
  assert.deepEqual(harness.calls.controlRun, [])
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().controlPending, null)
})

test('a pending publish owner switch fences the control before POST', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  let switched = false
  const unsubscribe = controller.subscribe(() => {
    const current = controller.getSnapshot()
    if (
      !switched
      && current.runId === RUN_ID
      && current.controlPending === 'pause'
    ) {
      switched = true
      controller.setLocation({ runId: OTHER_RUN_ID, error: null })
    }
  })

  await controller.controlRun('pause')
  harness.flushMicrotasks()
  await settle()
  unsubscribe()

  assert.equal(switched, true)
  assert.deepEqual(harness.calls.controlRun, [])
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().controlPending, null)
})

test('a throwing initial control token read resolves without sending a POST', async () => {
  const harness = createHarness()
  let throwTokenRead = false
  const controller = createTradingLabRunSessionController({
    ...harness.dependencies,
    getToken: () => {
      if (throwTokenRead) {
        throw new Error('token storage unavailable')
      }
      return 'admin-token'
    },
  })
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  throwTokenRead = true

  await assert.doesNotReject(() => controller.controlRun('pause'))

  assert.deepEqual(harness.calls.controlRun, [])
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.match(
    controller.getSnapshot().message ?? '',
    /token storage unavailable|管理员会话不可用/iu,
  )
})

test('a response-less control stays locked after a newer authoritative GET', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  harness.queueControlRun(Promise.reject(new TypeError('response lost')))
  await controller.controlRun('pause')
  assert.equal(controller.getSnapshot().controlPending, 'pause')

  controller.acceptAuthoritativeRun(runResponse('PAUSED', {
    pauseRequested: true,
    version: 9,
  }))
  assert.equal(controller.getSnapshot().session?.run?.version, 9)
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  assert.match(controller.getSnapshot().message ?? '', /保持锁定/iu)
  await controller.controlRun('pause')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:pause`])
})

test('terminal and blocked connections reject controls before POST', async () => {
  const terminal = createHarness()
  const terminalController = createTradingLabRunSessionController(
    terminal.dependencies,
  )
  terminalController.setAccess({ canView: true, canExecute: true })
  terminalController.setLocation({ runId: RUN_ID, error: null })
  terminal.flushMicrotasks()
  await settle()
  terminal.calls.stream[0]?.handlers.onEvent({
    id: null,
    event: 'complete',
    data: { state: 'COMPLETED' },
  })
  assert.equal(
    terminalController.getSnapshot().session?.connection,
    'FINAL_REFRESH_REQUIRED',
  )
  await terminalController.controlRun('pause')
  assert.deepEqual(terminal.calls.controlRun, [])

  const blocked = createHarness()
  const blockedController = createTradingLabRunSessionController(
    blocked.dependencies,
  )
  blockedController.setAccess({ canView: true, canExecute: true })
  blockedController.setLocation({ runId: RUN_ID, error: null })
  blocked.flushMicrotasks()
  await settle()
  blocked.calls.stream[0]?.deferred.reject(new TradingLabStreamError({
    kind: 'HTTP',
    retryable: false,
    status: 403,
    lastEventId: null,
    message: 'forbidden',
  }))
  await settle()
  assert.equal(
    blockedController.getSnapshot().session?.connection,
    'BLOCKED',
  )
  await blockedController.controlRun('cancel')
  assert.deepEqual(blocked.calls.controlRun, [])
})

test('an externally reconciled authoritative Run refreshes only the current owner', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  controller.acceptAuthoritativeRun(runResponse('COMPLETED', {
    reportId: null,
  }))
  assert.equal(controller.getSnapshot().session?.run?.state, 'COMPLETED')
  assert.equal(controller.getSnapshot().session?.run?.reportId, null)

  controller.acceptAuthoritativeRun(runResponse('FAILED', {
    id: OTHER_RUN_ID,
    reportId: REPORT_ID,
  }))
  assert.equal(controller.getSnapshot().session?.run?.id, RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.state, 'COMPLETED')
  assert.equal(controller.getSnapshot().session?.run?.reportId, null)
})

test('an accepted control with an unknown GET result stays locked without replay', async () => {
  const harness = createHarness()
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  harness.queueGetRun(Promise.reject(new TypeError('follow-up lost')))

  await controller.controlRun('pause')
  assert.equal(harness.calls.controlRun.length, 1)
  assert.equal(controller.getSnapshot().session?.run?.state, 'RUNNING')
  assert.equal(controller.getSnapshot().controlPending, 'pause')
  await controller.controlRun('pause')
  assert.equal(harness.calls.controlRun.length, 1)
  assert.match(controller.getSnapshot().message ?? '', /保持锁定/)
})

test('control gates use authoritative state and stale responses cannot cross owners', async () => {
  const harness = createHarness()
  harness.queueGetRun(Promise.resolve(runResponse('RUNNING', {
    pauseRequested: true,
  })))
  const controller = createTradingLabRunSessionController(harness.dependencies)
  controller.setAccess({ canView: true, canExecute: true })
  controller.setLocation({ runId: RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()

  await controller.controlRun('pause')
  await controller.controlRun('resume')
  assert.equal(harness.calls.controlRun.length, 0)

  const cancel = deferred<TradingLabRunControlResult>()
  harness.queueControlRun(cancel.promise)
  const pending = controller.controlRun('cancel')
  assert.deepEqual(harness.calls.controlRun, [`${RUN_ID}:cancel`])
  controller.setLocation({ runId: OTHER_RUN_ID, error: null })
  harness.flushMicrotasks()
  await settle()
  cancel.resolve({
    runId: RUN_ID,
    state: 'RUNNING',
    runVersion: 4,
    pauseRequested: true,
    cancelRequested: true,
  })
  await pending

  assert.equal(controller.getSnapshot().runId, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().session?.run?.id, OTHER_RUN_ID)
  assert.equal(controller.getSnapshot().controlPending, null)
  assert.equal(
    harness.calls.getRun.filter((runId) => runId === RUN_ID).length,
    1,
  )
})

type Deferred<Value> = Readonly<{
  promise: Promise<Value>
  resolve(value: Value): void
  reject(reason: unknown): void
}>

function deferred<Value>(): Deferred<Value> {
  let resolve!: (value: Value) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<Value>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function createHarness() {
  const microtasks: Array<() => void> = []
  const timers = new Map<number, {
    callback: () => void
    milliseconds: number
  }>()
  let nextTimer = 1
  const getRunQueue: Array<Promise<TradingLabRunResponse>> = []
  const getScenarioQueue: Array<Promise<TradingLabScenarioResponse>> = []
  const createScenarioQueue: Array<Promise<TradingLabScenarioResponse>> = []
  const createRunQueue: Array<Promise<TradingLabRunResponse>> = []
  const controlRunQueue: Array<Promise<TradingLabRunControlResult>> = []
  const normalizeQueue: Array<Promise<TradingLabScenario>> = []
  const calls = {
    order: [] as string[],
    getRun: [] as string[],
    getScenario: [] as string[],
    normalizeScenario: 0,
    getToken: 0,
    createScenario: 0,
    createRun: 0,
    controlRun: [] as string[],
    stream: [] as Array<{
      runId: string
      handlers: TradingLabStreamHandlers
      options: TradingLabStreamOptions
      deferred: Deferred<TradingLabStreamResult>
    }>,
    writeRunLocation: [] as string[],
  }

  const dependencies: TradingLabRunSessionControllerDependencies = {
    getToken: () => {
      calls.getToken += 1
      return 'admin-token'
    },
    getRun: async (runId) => {
      calls.getRun.push(runId)
      calls.order.push(`getRun:${runId}`)
      const queued = getRunQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return runResponse('RUNNING', { id: runId })
    },
    getScenario: async (scenarioId) => {
      calls.getScenario.push(scenarioId)
      calls.order.push(`getScenario:${scenarioId}`)
      const queued = getScenarioQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return scenarioResponse()
    },
    createScenario: async () => {
      calls.createScenario += 1
      const queued = createScenarioQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return scenarioResponse('DRAFT')
    },
    createRun: async () => {
      calls.createRun += 1
      const queued = createRunQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return runResponse('QUEUED')
    },
    controlRun: async (runId, action) => {
      calls.controlRun.push(`${runId}:${action}`)
      const queued = controlRunQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return {
        runId,
        state: 'RUNNING',
        runVersion: 4,
        pauseRequested: action === 'pause',
        cancelRequested: action === 'cancel',
      }
    },
    streamRun: (runId, handlers, options) => {
      const pending = deferred<TradingLabStreamResult>()
      calls.order.push(
        `stream:${runId}:${options.lastEventId ?? 'fresh'}`,
      )
      calls.stream.push({
        runId,
        handlers,
        options,
        deferred: pending,
      })
      return pending.promise
    },
    normalizeScenario: async (input) => {
      calls.normalizeScenario += 1
      calls.order.push('normalizeScenario')
      const queued = normalizeQueue.shift()
      if (queued !== undefined) {
        return queued
      }
      return input as TradingLabScenario
    },
    writeRunLocation: (runId) => {
      calls.writeRunLocation.push(runId)
      return { runId, error: null }
    },
    setTimer: (callback, milliseconds) => {
      const handle = nextTimer
      nextTimer += 1
      timers.set(handle, { callback, milliseconds })
      return handle
    },
    clearTimer: (handle) => {
      timers.delete(handle as number)
    },
    enqueueMicrotask: (callback) => {
      microtasks.push(callback)
    },
  }

  return {
    calls,
    dependencies,
    queueGetRun(value: Promise<TradingLabRunResponse>) {
      getRunQueue.push(value)
    },
    queueGetScenario(value: Promise<TradingLabScenarioResponse>) {
      getScenarioQueue.push(value)
    },
    queueCreateScenario(value: Promise<TradingLabScenarioResponse>) {
      createScenarioQueue.push(value)
    },
    queueCreateRun(value: Promise<TradingLabRunResponse>) {
      createRunQueue.push(value)
    },
    queueControlRun(value: Promise<TradingLabRunControlResult>) {
      controlRunQueue.push(value)
    },
    queueNormalize(value: Promise<TradingLabScenario>) {
      normalizeQueue.push(value)
    },
    flushMicrotasks() {
      while (microtasks.length > 0) {
        microtasks.shift()?.()
      }
    },
    pendingTimerCount: () => timers.size,
    pendingTimerDelays: () =>
      [...timers.values()].map(({ milliseconds }) => milliseconds),
    runNextTimer() {
      const entry = timers.entries().next().value as
        | [number, { callback: () => void; milliseconds: number }]
        | undefined
      if (entry === undefined) {
        return false
      }
      timers.delete(entry[0])
      entry[1].callback()
      return true
    },
  }
}

function runResponse(
  state: TradingLabRunResponse['state'] = 'RUNNING',
  overrides: Partial<TradingLabRunResponse> = {},
): TradingLabRunResponse {
  return {
    id: RUN_ID,
    scenarioId: SCENARIO_ID,
    reportId: REPORT_ID,
    state,
    queueSequence: 0,
    pauseRequested: false,
    cancelRequested: false,
    virtualStartedAt: NOW,
    virtualCurrentAt: NOW,
    processedTicks: 0,
    totalTicks: 10,
    speedMultiplier: 1,
    currentStep: 0,
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
    ...overrides,
  }
}

function scenarioResponse(
  status: TradingLabScenarioResponse['status'] = 'FROZEN',
): TradingLabScenarioResponse {
  return {
    id: SCENARIO_ID,
    name: 'controller fixture',
    description: null,
    status,
    negativeMode: false,
    seed: 'controller-seed',
    modelVersion: 'model-v1',
    scenario: scenario() as never,
    configSnapshot: scenario().configSnapshot as never,
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

function scenario(): TradingLabScenario {
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: '0.002',
    liquidationFeeRate: '0.003',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  const instrument = {
    symbol: 'BTCUSDT',
    productType: 'CRYPTO_SPOT' as const,
    baseAsset: 'BTC',
    quoteAsset: 'USDT',
    tickSize: '0.01',
    stepSize: '0.001',
    pricePrecision: 2,
    quantityPrecision: 3,
    minQty: '0.001',
    maxQty: '100',
    minNotional: '10',
    maxNotional: '1000000',
    initialMarginRate: '0.05',
    maintenanceMarginRate: '0.025',
    liquidationFeeRate: '0.003',
    fixedFundingRate: '0.0001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: 'quote_mid',
    contractSize: '1',
    maxLeverage: 1,
    defaultLeverage: 1,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'TIER_1',
  }
  return {
    id: SCENARIO_ID,
    name: 'controller fixture',
    description: '',
    negativeMode: false,
    seed: 'controller-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbol-v1',
      codeVersion: 'code-v1',
      executionPolicy,
      instruments: [instrument],
    },
    configSnapshotHash: HASH,
    executionPolicy,
    marketPath: {
      virtualStart: '2026-07-25T00:00:00.000Z',
      realistic: false,
      instruments: [{
        mode: 'SIMPLE',
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        seed: 'path-controller-seed',
        last: {
          start: '60000',
          segments: [{
            target: '60100',
            durationSeconds: 300,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 100,
          }],
        },
        spreadSteps: 2,
        indexOffsetSteps: 0,
        basisSteps: 0,
      }],
    },
    initialBalances: { USDT: '100000' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
    }],
    timeline: [{
      id: 'action-1',
      sequence: 1,
      type: 'PLACE_ORDER',
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
      trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
      parameters: {
        side: 'BUY',
        orderType: 'LIMIT',
        quantity: '0.01',
        price: '60000',
      },
    }],
  }
}

function runRequest(): TradingLabRunRequest {
  return {
    scenario: scenario(),
    localCalculation: {
      status: 'BLOCKED',
      issues: [{
        path: 'timeline[0]',
        code: 'EXPECTED_BUSINESS_ERROR',
        message: '由真实 HTTP 验证的预期业务错误',
      }],
      runnerIssues: [],
    },
  }
}

function transientStreamError(
  lastEventId: string | null,
): TradingLabStreamError {
  return new TradingLabStreamError({
    kind: 'EOF',
    retryable: true,
    lastEventId,
    message: 'transient EOF',
  })
}

async function settle(): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) {
    await Promise.resolve()
  }
}
