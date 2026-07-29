import assert from 'node:assert/strict'
import test from 'node:test'

import type { TradingLabScenario } from '../model/types.ts'
import {
  createTradingLabRunSession,
  reduceTradingLabRunSession,
  type TradingLabRunSession,
  type TradingLabRunSessionAction,
  type TradingLabRunSessionRun,
  type TradingLabStreamSessionEvent,
} from './tradingLabRunSession.ts'

const RUN_ID = '11111111-1111-4111-8111-11111111111a'
const OTHER_RUN_ID = '22222222-2222-4222-8222-222222222222'
const SCENARIO_ID = '33333333-3333-4333-8333-333333333333'
const REPORT_ID = '44444444-4444-4444-8444-444444444444'
const OWNER_GENERATION = 7

function run(
  state = 'RUNNING',
  overrides: Partial<TradingLabRunSessionRun> = {},
): TradingLabRunSessionRun {
  return {
    id: RUN_ID,
    scenarioId: SCENARIO_ID,
    reportId: REPORT_ID,
    state,
    totalTicks: 10,
    pauseRequested: false,
    cancelRequested: false,
    version: 3,
    ...overrides,
  }
}

function scenario(): TradingLabScenario {
  return {
    id: SCENARIO_ID,
    name: 'session fixture',
    description: '',
    negativeMode: false,
    seed: 'session-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: {
        matchingMode: 'SIMPLE',
        makerFeeRate: '0',
        takerFeeRate: '0',
        liquidationFeeRate: '0',
        slippageRate: '0',
        maxFillQuantityPerTick: '1',
      },
      instruments: [],
    },
    configSnapshotHash: 'a'.repeat(64),
    executionPolicy: {
      matchingMode: 'SIMPLE',
      makerFeeRate: '0',
      takerFeeRate: '0',
      liquidationFeeRate: '0',
      slippageRate: '0',
      maxFillQuantityPerTick: '1',
    },
    marketPath: {
      virtualStart: '2026-07-25T00:00:00.000Z',
      realistic: false,
      instruments: [],
    },
    initialBalances: { USDT: '1000' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [],
    timeline: [],
  }
}

type UnscopedAction<T> = T extends unknown
  ? Omit<T, 'ownerGeneration' | 'runId'>
  : never

function action(
  value: UnscopedAction<TradingLabRunSessionAction>,
): TradingLabRunSessionAction {
  return {
    ...value,
    ownerGeneration: OWNER_GENERATION,
    runId: RUN_ID,
  } as TradingLabRunSessionAction
}

function streamEvent(
  id: string | null,
  name: TradingLabStreamSessionEvent['name'],
  data: unknown,
): TradingLabRunSessionAction {
  return action({
    type: 'STREAM_EVENT',
    event: { id, name, data },
  })
}

function reduce(
  session: TradingLabRunSession,
  next: TradingLabRunSessionAction,
): TradingLabRunSession {
  return reduceTradingLabRunSession(session, next)
}

function openedSession(): TradingLabRunSession {
  let session = createTradingLabRunSession(RUN_ID, OWNER_GENERATION)
  session = reduce(session, action({ type: 'ATTACH_RUN', run: run() }))
  session = reduce(session, action({ type: 'RESTORE_SCENARIO', scenario: scenario() }))
  session = reduce(session, action({ type: 'STREAM_CONNECTING' }))
  return reduce(session, action({ type: 'STREAM_OPEN' }))
}

test('creates a small inert session with no invented progress', () => {
  const session = createTradingLabRunSession(RUN_ID, OWNER_GENERATION)

  assert.equal(session.runId, RUN_ID)
  assert.equal(session.ownerGeneration, OWNER_GENERATION)
  assert.equal(session.run, null)
  assert.equal(session.scenario, null)
  assert.equal(session.validationState, null)
  assert.equal(session.highestDurableEventId, null)
  assert.equal(session.highestObservedTick, null)
  assert.equal(session.highestCompletedCheckpoint, null)
  assert.equal(session.lastVirtualTime, null)
  assert.equal(session.connection, 'IDLE')
  assert.deepEqual(session.evidence, [])
  assert.equal(session.pendingTerminalState, null)
  assert.equal(session.transportIssue, null)
  assert.equal(session.productErrorCount, 0)
  assert.deepEqual(session.chartEvidence, {
    actualTicks: [],
    markers: [],
    markerProofs: [],
    lastTickSequence: null,
    lastVirtualTime: null,
  })
  assert.equal(session.actualState.availability, 'UNAVAILABLE')
  if (session.actualState.availability === 'UNAVAILABLE') {
    assert.equal(session.actualState.reason, 'NO_CHECKPOINT')
  }
})

test('retains canonical reportId from every authoritative run refresh', () => {
  let session = createTradingLabRunSession(RUN_ID, OWNER_GENERATION)
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('RUNNING'),
  }))
  assert.equal(session.run?.reportId, REPORT_ID)

  session = reduce(session, action({ type: 'STREAM_CONNECTING' }))
  session = reduce(session, action({ type: 'STREAM_OPEN' }))
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'COMPLETED',
  }))
  session = reduce(session, action({
    type: 'FINAL_RUN_REFRESH',
    run: run('COMPLETED', { reportId: null }),
  }))

  assert.equal(session.connection, 'CLOSED')
  assert.equal(session.run?.reportId, null)
})

test('ignores an older authoritative run snapshot for the same owner', () => {
  let session = createTradingLabRunSession(RUN_ID, OWNER_GENERATION)
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('PAUSED', {
      pauseRequested: true,
      version: 5,
    }),
  }))
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('RUNNING', {
      pauseRequested: true,
      version: 4,
    }),
  }))

  assert.equal(session.run?.state, 'PAUSED')
  assert.equal(session.run?.pauseRequested, true)
  assert.equal(session.run?.version, 5)
})

test('does not resurrect a deleted report from an equal-version snapshot', () => {
  let session = createTradingLabRunSession(RUN_ID, OWNER_GENERATION)
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('COMPLETED', { reportId: null, version: 5 }),
  }))
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('COMPLETED', { reportId: REPORT_ID, version: 5 }),
  }))

  assert.equal(session.run?.state, 'COMPLETED')
  assert.equal(session.run?.reportId, null)
  assert.equal(session.run?.version, 5)
})

test('blocks an authoritative run with a malformed version', () => {
  const session = reduce(
    createTradingLabRunSession(RUN_ID, OWNER_GENERATION),
    action({
      type: 'ATTACH_RUN',
      run: run('RUNNING', { version: Number.NaN }),
    }),
  )

  assert.equal(session.connection, 'BLOCKED')
  assert.equal(session.run, null)
  assert.equal(
    session.transportIssue?.code,
    'TRADING_LAB_RUN_MALFORMED',
  )
})

test('keeps authoritative main state separate from validation state', () => {
  let session = openedSession()
  const restoredScenario = session.scenario

  session = reduce(session, streamEvent('0', 'state', {
    virtualTime: '2026-07-25T00:00:01.000Z',
    payload: {
      state: 'PAUSED',
      reason: 'PAUSE_REQUESTED',
    },
  }))

  assert.equal(session.run?.state, 'RUNNING')
  assert.equal(session.validationState, 'PAUSED')
  assert.equal(session.highestDurableEventId, '0')
  assert.equal(session.lastVirtualTime, '2026-07-25T00:00:01.000Z')
  assert.equal(session.scenario, restoredScenario)
  assert.equal(session.connection, 'OPEN')
  assert.equal(session.evidence[0]?.state, 'PAUSED')
})

test('accepts the real RUN_ACCEPTED public state shape without inventing a state', () => {
  let session = openedSession()

  session = reduce(session, streamEvent('0', 'state', {
    realTime: '2026-07-25T00:00:00.000Z',
    validationSequence: 1,
    payload: {
      tickCount: 10,
    },
  }))

  assert.equal(session.connection, 'OPEN')
  assert.equal(session.transportIssue, null)
  assert.equal(session.highestDurableEventId, '0')
  assert.equal(session.validationState, null)
  assert.equal(session.evidence[0]?.name, 'state')
  assert.equal(session.evidence[0]?.state, null)
})

test('distinguishes an observed tick from a completed checkpoint', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'tick', {
    virtualTime: '2026-07-25T00:00:07.000Z',
    payload: {
      tickSequence: 1,
      spotSymbols: 1,
      perpetualSymbols: 0,
      instruments: [{
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '100',
        ask: '102',
        last: '101',
      }],
    },
  }))

  assert.equal(session.highestObservedTick, 1)
  assert.equal(session.highestCompletedCheckpoint, null)
  assert.equal(session.chartEvidence.actualTicks.length, 1)

  session = reduce(session, streamEvent('1', 'checkpoint', {
    virtualTime: '2026-07-25T00:00:06.000Z',
    correlationId: 'checkpoint-6',
    payload: {
      tickSequence: 1,
      state: {
        massiveActualState: 'must-not-survive-session-projection',
      },
    },
  }))
  session = reduce(session, streamEvent('2', 'tick', {
    virtualTime: '2026-07-25T00:00:08.000Z',
    payload: {
      tickSequence: 2,
      instruments: [{
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '101',
        ask: '103',
        last: '102',
      }],
    },
  }))

  assert.equal(session.highestObservedTick, 2)
  assert.equal(session.highestCompletedCheckpoint, 1)
  assert.equal(session.evidence[1]?.tickSequence, 1)
  assert.deepEqual(
    session.chartEvidence.actualTicks.map((tick) => tick.sequence),
    [1, 2],
  )
  assert.doesNotMatch(
    JSON.stringify(session.evidence),
    /massiveActualState|must-not-survive-session-projection/u,
  )
  assert.equal(session.actualState.availability, 'UNAVAILABLE')
  if (session.actualState.availability === 'UNAVAILABLE') {
    assert.equal(session.actualState.reason, 'MALFORMED_CHECKPOINT')
  }
})

test('fails the run session closed on conflicting durable ACTUAL chart evidence', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'tick', {
    virtualTime: '2026-07-25T00:00:01.000Z',
    payload: {
      tickSequence: 1,
      instruments: [{
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '100',
        ask: '102',
        last: '101',
      }],
    },
  }))
  session = reduce(session, streamEvent('1', 'tick', {
    virtualTime: '2026-07-25T00:00:01.000Z',
    payload: {
      tickSequence: 1,
      instruments: [{
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '100',
        ask: '102',
        last: '999',
      }],
    },
  }))

  assert.equal(session.connection, 'BLOCKED')
  assert.equal(
    session.transportIssue?.code,
    'TRADING_LAB_CHART_TICK_CONFLICT',
  )
  assert.equal(session.highestDurableEventId, '0')
  assert.equal(session.chartEvidence.actualTicks.length, 1)
})

test('retains only the newest valid checkpoint actual state outside evidence', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'checkpoint', {
    virtualTime: '2026-07-25T00:00:06.000Z',
    correlationId: 'checkpoint-6',
    payload: {
      tickSequence: 6,
      state: actualState('first-order'),
    },
  }))
  session = reduce(session, streamEvent('1', 'checkpoint', {
    virtualTime: '2026-07-25T00:00:07.000Z',
    correlationId: 'checkpoint-7',
    payload: {
      tickSequence: 7,
      state: actualState('newest-order'),
    },
  }))

  assert.equal(session.actualState.availability, 'AVAILABLE')
  if (session.actualState.availability === 'AVAILABLE') {
    assert.equal(session.actualState.sourceEventId, '1')
    assert.equal(session.actualState.tickSequence, 7)
    assert.equal(session.actualState.virtualTime, '2026-07-25T00:00:07.000Z')
    assert.equal(
      (
        session.actualState.state.orders[0] as {
          readonly orderId: string
        }
      ).orderId,
      'newest-order',
    )
  }
  assert.doesNotMatch(
    JSON.stringify(session.evidence),
    /first-order|newest-order|walletBalances|fundingSettlements/u,
  )
})

test('a newer malformed checkpoint clears stale actual state without storing it in evidence', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'checkpoint', {
    virtualTime: '2026-07-25T00:00:06.000Z',
    payload: {
      tickSequence: 6,
      state: actualState('must-be-cleared'),
    },
  }))
  session = reduce(session, streamEvent('1', 'checkpoint', {
    virtualTime: '2026-07-25T00:00:07.000Z',
    payload: {
      tickSequence: 7,
      state: {
        summary: {},
        walletBalances: [],
      },
    },
  }))

  assert.equal(session.connection, 'OPEN')
  assert.equal(session.highestCompletedCheckpoint, 7)
  assert.equal(session.actualState.availability, 'UNAVAILABLE')
  if (session.actualState.availability === 'UNAVAILABLE') {
    assert.equal(session.actualState.reason, 'MALFORMED_CHECKPOINT')
    assert.equal(session.actualState.sourceEventId, '1')
    assert.equal(session.actualState.tickSequence, 7)
  }
  assert.doesNotMatch(
    JSON.stringify(session.evidence),
    /must-be-cleared|walletBalances/u,
  )
})

test('retains only the newest 200 bounded evidence summaries', () => {
  let session = openedSession()
  for (let id = 0; id < 205; id += 1) {
    session = reduce(session, streamEvent(String(id), 'progress', {
      kind: 'high-watermark',
      highWatermark: id,
      ignoredNestedPayload: {
        raw: `not-retained-${id}`,
      },
    }))
  }

  assert.equal(session.evidence.length, 200)
  assert.equal(session.evidence[0]?.id, '5')
  assert.equal(session.evidence.at(-1)?.id, '204')
  assert.equal(session.highestDurableEventId, '204')
  assert.doesNotMatch(JSON.stringify(session.evidence), /not-retained-/u)
})

test('projects api-trace into a bounded summary without request or response bodies', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'api-trace', {
    phase: 'result',
    method: 'POST',
    status: 202,
    request: { secretBody: 'request-must-not-survive' },
    response: { secretBody: 'response-must-not-survive' },
    payload: {
      operation: 'PUBLIC_ACTION',
      outcome: 'SUCCEEDED',
      response: { nestedSecret: 'must-not-survive' },
    },
  }))

  const summary = session.evidence[0]
  assert.equal(summary?.phase, 'result')
  assert.equal(summary?.method, 'POST')
  assert.equal(summary?.httpStatus, 202)
  assert.equal(summary?.operation, 'PUBLIC_ACTION')
  assert.equal(summary?.outcome, 'SUCCEEDED')
  assert.doesNotMatch(
    JSON.stringify(summary),
    /secretBody|nestedSecret|must-not-survive/u,
  )
})

test('requires an unnumbered complete and a matching final GET before closing', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'state', {
    payload: { state: 'COMPLETED' },
  }))
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'COMPLETED',
  }))

  assert.equal(session.connection, 'FINAL_REFRESH_REQUIRED')
  assert.equal(session.pendingTerminalState, 'COMPLETED')
  assert.equal(session.highestDurableEventId, '0')
  assert.equal(session.run?.state, 'RUNNING')

  session = reduce(session, action({
    type: 'FINAL_RUN_REFRESH',
    run: run('COMPLETED'),
  }))

  assert.equal(session.connection, 'CLOSED')
  assert.equal(session.pendingTerminalState, null)
  assert.equal(session.run?.state, 'COMPLETED')
  assert.equal(session.transportIssue, null)
})

test('blocks a final GET that disagrees with the terminal control frame', () => {
  let session = openedSession()
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'FAILED',
  }))
  session = reduce(session, action({
    type: 'FINAL_RUN_REFRESH',
    run: run('COMPLETED'),
  }))

  assert.equal(session.connection, 'BLOCKED')
  assert.equal(session.run?.state, 'RUNNING')
  assert.equal(
    session.transportIssue?.code,
    'TRADING_LAB_FINAL_STATE_MISMATCH',
  )
})

test('blocks a final GET older than the latest authoritative run snapshot', () => {
  let session = openedSession()
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('RUNNING', { version: 5 }),
  }))
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'COMPLETED',
  }))
  session = reduce(session, action({
    type: 'FINAL_RUN_REFRESH',
    run: run('COMPLETED', { version: 4 }),
  }))

  assert.equal(session.connection, 'BLOCKED')
  assert.equal(session.run?.state, 'RUNNING')
  assert.equal(session.run?.version, 5)
  assert.equal(
    session.transportIssue?.code,
    'TRADING_LAB_RUN_VERSION_REGRESSION',
  )
})

test('a final GET cannot resurrect an equal-version deleted report', () => {
  let session = openedSession()
  session = reduce(session, action({
    type: 'ATTACH_RUN',
    run: run('RUNNING', { reportId: null, version: 5 }),
  }))
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'COMPLETED',
  }))
  session = reduce(session, action({
    type: 'FINAL_RUN_REFRESH',
    run: run('COMPLETED', { reportId: REPORT_ID, version: 5 }),
  }))

  assert.equal(session.connection, 'CLOSED')
  assert.equal(session.run?.state, 'COMPLETED')
  assert.equal(session.run?.reportId, null)
  assert.equal(session.run?.version, 5)
})

test('fences stale owner generations and run IDs by object identity', () => {
  const session = openedSession()
  const staleGeneration = reduce(session, {
    type: 'ATTACH_RUN',
    ownerGeneration: OWNER_GENERATION - 1,
    runId: RUN_ID,
    run: run('COMPLETED'),
  })
  const staleRun = reduce(session, {
    type: 'STREAM_BLOCKED',
    ownerGeneration: OWNER_GENERATION,
    runId: OTHER_RUN_ID,
    issue: {
      code: 'STALE',
      message: 'must be ignored',
    },
  })

  assert.equal(staleGeneration, session)
  assert.equal(staleRun, session)
})

test('keeps product error evidence separate from transport retry/block state', () => {
  let session = openedSession()
  session = reduce(session, streamEvent('0', 'error', {
    kind: 'failure',
    payload: {
      code: 'EXPECTED_PRODUCT_FAILURE',
      message: 'negative-mode evidence',
    },
  }))

  assert.equal(session.connection, 'OPEN')
  assert.equal(session.productErrorCount, 1)
  assert.equal(session.transportIssue, null)
  assert.equal(session.evidence[0]?.code, 'EXPECTED_PRODUCT_FAILURE')

  session = reduce(session, action({
    type: 'STREAM_RETRY_WAIT',
    issue: {
      code: 'STREAM_EOF',
      message: 'connection ended before complete',
    },
  }))
  assert.equal(session.connection, 'RETRY_WAIT')
  assert.equal(session.transportIssue?.retryable, true)
  assert.equal(session.productErrorCount, 1)

  session = reduce(session, action({
    type: 'STREAM_BLOCKED',
    issue: {
      code: 'STREAM_PROTOCOL',
      message: 'durable evidence gap',
    },
  }))
  assert.equal(session.connection, 'BLOCKED')
  assert.equal(session.transportIssue?.retryable, false)
  assert.equal(session.productErrorCount, 1)
})

test('does not let retry or open actions resurrect a non-retryable block', () => {
  const blocked = reduce(openedSession(), action({
    type: 'STREAM_BLOCKED',
    issue: {
      code: 'STREAM_PROTOCOL',
      message: 'durable evidence is corrupt',
    },
  }))
  const retry = reduce(blocked, action({
    type: 'STREAM_RETRY_WAIT',
    issue: {
      code: 'STREAM_EOF',
      message: 'must not replace the protocol block',
    },
  }))
  const connecting = reduce(retry, action({ type: 'STREAM_CONNECTING' }))
  const opened = reduce(connecting, action({ type: 'STREAM_OPEN' }))

  assert.equal(retry, blocked)
  assert.equal(connecting, blocked)
  assert.equal(opened, blocked)
  assert.equal(opened.connection, 'BLOCKED')
  assert.equal(opened.transportIssue?.code, 'STREAM_PROTOCOL')
})

test('can fail closed when the required final GET cannot be completed', () => {
  let session = openedSession()
  session = reduce(session, streamEvent(null, 'complete', {
    state: 'COMPLETED',
  }))
  assert.equal(session.connection, 'FINAL_REFRESH_REQUIRED')

  session = reduce(session, action({
    type: 'STREAM_BLOCKED',
    issue: {
      code: 'TRADING_LAB_FINAL_REFRESH_FAILED',
      message: 'Final GET could not be completed',
    },
  }))

  assert.equal(session.connection, 'BLOCKED')
  assert.equal(
    session.transportIssue?.code,
    'TRADING_LAB_FINAL_REFRESH_FAILED',
  )
  assert.equal(session.transportIssue?.retryable, false)
})

test('fails closed on a durable id gap or malformed tick without advancing cursor', () => {
  const gap = reduce(openedSession(), streamEvent('1', 'progress', {
    kind: 'progress',
  }))
  assert.equal(gap.connection, 'BLOCKED')
  assert.equal(gap.highestDurableEventId, null)
  assert.deepEqual(gap.evidence, [])
  assert.equal(gap.transportIssue?.code, 'TRADING_LAB_DURABLE_EVENT_GAP')

  const malformedTick = reduce(openedSession(), streamEvent('0', 'tick', {
    virtualTime: '2026-07-25T00:00:01.000Z',
    payload: { tickSequence: 0 },
  }))
  assert.equal(malformedTick.connection, 'BLOCKED')
  assert.equal(malformedTick.highestDurableEventId, null)
  assert.deepEqual(malformedTick.evidence, [])
  assert.equal(
    malformedTick.transportIssue?.code,
    'TRADING_LAB_EVENT_MALFORMED',
  )
})

test('rejects invalid session identities at the interface', () => {
  for (const runId of [
    '',
    RUN_ID.toUpperCase(),
    'not-a-uuid',
  ]) {
    assert.throws(() => createTradingLabRunSession(
      runId,
      OWNER_GENERATION,
    ))
  }
  for (const ownerGeneration of [-1, 1.5, Number.NaN]) {
    assert.throws(() => createTradingLabRunSession(
      RUN_ID,
      ownerGeneration,
    ))
  }
})

function actualState(orderId: string) {
  return {
    summary: {
      accountId: 'account-1',
      equity: '1000',
    },
    walletBalances: [{ asset: 'USDT', available: '1000' }],
    assetLedger: [],
    cashLedger: [],
    orders: [{ orderId }],
    trades: [],
    positions: [],
    fundingSettlements: [],
  }
}
