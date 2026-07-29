import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import type {
  TradingLabScenario,
} from '../model/types.ts'
import type { OracleCalculationOutcome } from '../oracle/types.ts'
import {
  calculateLocalExpectedScenario,
  calculateLocalExpectedWorkspace,
} from './calculateLocalExpectedScenario.ts'
import {
  createLocalOracleScheduler,
  estimateLocalExpectedWorkUnits,
  type LocalOracleSchedulerDependencies,
  type LocalOracleWorkerAdapter,
} from './localOracleScheduler.ts'
import {
  handleLocalOracleWorkerRequest,
  type LocalOracleWorkerResponse,
} from './localOracleWorker.ts'
import type { VisibleTickWindow } from '../chart/visibleTickWindow.ts'

function scenario(durationSeconds = 2): TradingLabScenario {
  const instrument = {
    symbol: 'XBT-USDT-LAB',
    productType: 'CRYPTO_SPOT' as const,
    baseAsset: 'XBT',
    quoteAsset: 'USDT',
    tickSize: '1',
    stepSize: '0.001',
    pricePrecision: 0,
    quantityPrecision: 3,
    minQty: '0.001',
    maxQty: '100',
    minNotional: '1',
    maxNotional: '1000000',
    initialMarginRate: '1',
    maintenanceMarginRate: '0',
    liquidationFeeRate: '0.002',
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
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: '0.001',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  return {
    id: 'local-expected-scenario',
    name: '本地预期测试',
    description: '',
    negativeMode: false,
    seed: 'local-expected-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments: [instrument],
    },
    configSnapshotHash: 'a'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: '2026-01-01T00:00:00Z',
      realistic: false,
      instruments: [{
        mode: 'SIMPLE',
        productType: instrument.productType,
        symbol: instrument.symbol,
        seed: 'path-seed',
        last: {
          start: '100',
          segments: [{
            target: '100',
            durationSeconds,
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
    initialBalances: { USDT: '1000', XBT: '0' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: instrument.symbol,
      productType: instrument.productType,
    }],
    timeline: [{
      id: 'buy-1',
      sequence: 1,
      type: 'PLACE_ORDER',
      symbol: instrument.symbol,
      productType: instrument.productType,
      trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
      parameters: {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '100',
        quantityUnit: 'QUOTE',
      },
    }],
  }
}

function forceFirstSimpleDuration(
  input: TradingLabScenario,
  durationSeconds: number,
): void {
  const firstPath = input.marketPath.instruments[0] as unknown as {
    last: {
      segments: Array<{ durationSeconds: number }>
    }
  }
  const firstSegment = firstPath.last.segments[0]
  assert.notEqual(firstSegment, undefined)
  firstSegment!.durationSeconds = durationSeconds
}

const calculatedOutcome: OracleCalculationOutcome = {
  status: 'CALCULATED',
  result: {
    modelVersion: 'model-v1',
    configSnapshotHash: 'a'.repeat(64),
    assumptions: [],
    snapshots: [],
  },
  runnerIssues: [],
}

const calculatedOutcomeWithSnapshot: OracleCalculationOutcome = {
  status: 'CALCULATED',
  result: {
    modelVersion: 'model-v1',
    configSnapshotHash: 'a'.repeat(64),
    assumptions: ['测试假设'],
    snapshots: [{
      actionId: 'buy-1',
      tickSequence: 1,
      virtualTime: '2026-01-01T00:00:01.000Z',
      orders: [{
        id: 'order-1',
        actionId: 'buy-1',
        symbol: 'XBT-USDT-LAB',
        productType: 'CRYPTO_SPOT',
        side: 'BUY',
        status: 'FILLED',
        quantity: '1',
        price: '100',
        feeUsdt: '0.1',
      }],
      trades: [{
        id: 'trade-1',
        orderId: 'order-1',
        actionId: 'buy-1',
        symbol: 'XBT-USDT-LAB',
        productType: 'CRYPTO_SPOT',
        side: 'BUY',
        quantity: '1',
        price: '100',
        notionalUsdt: '100',
        feeUsdt: '0.1',
      }],
      spotPositions: [{
        symbol: 'XBT-USDT-LAB',
        baseAsset: 'XBT',
        quoteAsset: 'USDT',
        status: 'OPEN',
        quantity: '1',
        averageCost: '100',
        grossQuoteCost: '100',
        feeCostUsdt: '0.1',
        netInvestedUsdt: '100.1',
        breakEvenPrice: '100.1',
        realizedGrossPnl: '0',
        realizedNetPnl: '0',
      }],
      perpetualPositions: [{
        symbol: 'XBT-USDT-LAB-PERP',
        positionSide: 'BOTH',
        direction: 'LONG',
        marginMode: 'CROSS',
        status: 'OPEN',
        quantity: '1',
        entryPrice: '100',
        markPrice: '100',
        leverage: 2,
        initialMargin: '50',
        isolatedMargin: '0',
        maintenanceMargin: '1',
        unrealizedGrossPnl: '0',
        realizedGrossPnl: '0',
        tradingFeeUsdt: '0.1',
        fundingPnlUsdt: '0',
        liquidationFeeUsdt: '0',
        realizedNetPnl: '-0.1',
        estimatedLiquidationPrice: '51',
      }],
      wallets: [{
        asset: 'USDT',
        available: '899.9',
        locked: '0',
        total: '899.9',
      }],
      accountSummary: {
        totalWalletBalanceUsdt: '999.9',
        availableBalanceUsdt: '899.9',
        totalUnrealizedPnlUsdt: '0',
        equityUsdt: '999.9',
        totalMaintenanceMarginUsdt: '1',
      },
      ledgerProjection: [{
        actionId: 'buy-1',
        asset: 'USDT',
        type: 'TRADE',
        amount: '-100.1',
        balanceAfter: '899.9',
      }],
      risk: {
        equityUsdt: '999.9',
        availableBalanceUsdt: '899.9',
        maintenanceMarginUsdt: '1',
        marginRatio: '0.0010001',
        liquidationTriggered: false,
      },
      warnings: [{
        code: 'TEST_WARNING',
        message: '测试警告',
      }],
    }],
  },
  runnerIssues: [{
    path: 'timeline[0]',
    code: 'TEST_RUNNER_WARNING',
    message: '测试 runner 警告',
    severity: 'WARNING',
  }],
}

const blockedOutcome: OracleCalculationOutcome = {
  status: 'BLOCKED',
  issues: [{
    path: 'timeline[0]',
    code: 'TEST_BLOCKED',
    message: '测试阻断',
  }],
  runnerIssues: [],
}

const emptyWindow: VisibleTickWindow = {
  ticks: [],
  limit: 10_000,
  totalCount: 0,
  firstSequence: null,
  lastSequence: null,
  hasOlder: false,
  hasNewer: false,
  olderBeforeSequence: null,
}

const validWorkerTick = {
  sequence: 1,
  virtualTime: '2026-01-01T00:00:01Z',
  instruments: [{
    productType: 'CRYPTO_SPOT' as const,
    symbol: 'XBT-USDT-LAB',
    bid: '99',
    ask: '101',
    last: '100',
  }],
  fundingRates: [],
}

const validWorkerWindow: VisibleTickWindow = {
  ...emptyWindow,
  ticks: [validWorkerTick],
  totalCount: 1,
  firstSequence: 1,
  lastSequence: 1,
}

const calculatedWorkspace = {
  outcome: calculatedOutcome,
  localTickWindow: emptyWindow,
} as const

const blockedWorkspace = {
  outcome: blockedOutcome,
  localTickWindow: emptyWindow,
} as const

class ManualClock {
  readonly delays: number[] = []
  private nextId = 1
  private readonly callbacks = new Map<number, () => void>()

  readonly setTimer = (callback: () => void, delay: number): unknown => {
    const id = this.nextId
    this.nextId += 1
    this.delays.push(delay)
    this.callbacks.set(id, callback)
    return id
  }

  readonly clearTimer = (handle: unknown): void => {
    this.callbacks.delete(handle as number)
  }

  runAll(): void {
    const pending = [...this.callbacks.values()]
    this.callbacks.clear()
    for (const callback of pending) {
      callback()
    }
  }

  get pendingCount(): number {
    return this.callbacks.size
  }
}

class FakeWorker implements LocalOracleWorkerAdapter {
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  onmessageerror: ((event: MessageEvent<unknown>) => void) | null = null
  readonly posted: unknown[] = []
  terminateCount = 0

  postMessage(message: unknown): void {
    this.posted.push(message)
  }

  terminate(): void {
    this.terminateCount += 1
  }

  respond(response: unknown): void {
    this.onmessage?.({ data: response } as MessageEvent<unknown>)
  }

  crash(message: string): void {
    this.onerror?.({
      message,
      preventDefault() {},
    } as ErrorEvent)
  }
}

function dependencies(
  clock: ManualClock,
  overrides: Partial<LocalOracleSchedulerDependencies> = {},
): Partial<LocalOracleSchedulerDependencies> {
  return {
    setTimer: clock.setTimer,
    clearTimer: clock.clearTimer,
    ...overrides,
  }
}

test('shared pure calculation generates the market and returns only the Oracle outcome', () => {
  const input = scenario()
  const before = structuredClone(input)

  const outcome = calculateLocalExpectedScenario(input)

  assert.equal(outcome.status, 'CALCULATED')
  assert.equal('ticks' in outcome, false)
  assert.deepEqual(input, before)
  if (outcome.status === 'CALCULATED') {
    assert.equal(outcome.result.snapshots.length, 1)
    assert.equal(outcome.result.snapshots[0]?.actionId, 'buy-1')
  }

  const source = readFileSync(
    new URL('./calculateLocalExpectedScenario.ts', import.meta.url),
    'utf8',
  )
  assert.equal(
    source.match(/\bgenerateMarketTicks\s*\(/g)?.length,
    1,
    'shared calculation must generate the market exactly once',
  )
  assert.equal(
    source.match(/\bcalculateScenario\s*\(/g)?.length,
    1,
    'shared calculation must call the Oracle exactly once',
  )
})

test('workspace calculation adds only a bounded local tail while the legacy outcome stays tick-free', () => {
  const input = scenario(10_001)
  const before = structuredClone(input)
  const legacy = calculateLocalExpectedScenario(input)
  const workspace = calculateLocalExpectedWorkspace(input)

  assert.equal('ticks' in legacy, false)
  assert.equal('localTickWindow' in legacy, false)
  assert.equal(workspace.outcome.status, 'CALCULATED')
  assert.equal(workspace.localTickWindow.ticks.length, 10_000)
  assert.equal(workspace.localTickWindow.totalCount, 10_001)
  assert.equal(workspace.localTickWindow.firstSequence, 2)
  assert.equal(workspace.localTickWindow.lastSequence, 10_001)
  assert.equal(workspace.localTickWindow.hasOlder, true)
  assert.deepEqual(input, before)

  const source = readFileSync(
    new URL('./calculateLocalExpectedScenario.ts', import.meta.url),
    'utf8',
  )
  assert.equal(
    source.match(/\bgenerateMarketTicks\s*\(/g)?.length,
    1,
    'legacy and workspace calculations must share one generator call site',
  )
  assert.equal(
    source.match(/\bcalculateScenario\s*\(/g)?.length,
    1,
    'legacy and workspace calculations must share one Oracle call site',
  )
})

test('workspace calculation preserves BLOCKED while retaining a valid local market window', () => {
  const input = scenario()
  input.timeline[0]!.parameters = {
    side: 'BUY',
    orderType: 'LIMIT',
    quantity: '1',
    quantityUnit: 'BASE',
    price: '100',
  }

  const workspace = calculateLocalExpectedWorkspace(input)

  assert.equal(workspace.outcome.status, 'BLOCKED')
  assert.equal(workspace.localTickWindow.ticks.length, 2)
  assert.equal(workspace.localTickWindow.firstSequence, 1)
  assert.equal(workspace.localTickWindow.lastSequence, 2)
})

test('work-unit estimate sums every lane duration and rejects malformed or unbounded shapes', () => {
  const simple = scenario(20)
  assert.equal(estimateLocalExpectedWorkUnits(simple), 30)
  assert.equal(estimateLocalExpectedWorkUnits(scenario(24_990)), 25_000)
  assert.equal(estimateLocalExpectedWorkUnits(scenario(24_991)), 25_001)

  const advanced = structuredClone(simple)
  advanced.marketPath = {
    ...advanced.marketPath,
    instruments: [{
      mode: 'ADVANCED',
      productType: 'CRYPTO_SPOT',
      symbol: 'XBT-USDT-LAB',
      seed: 'advanced-seed',
      prices: {
        bid: {
          start: '99',
          segments: [{
            target: '99',
            durationSeconds: 20,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 1,
          }],
        },
        ask: {
          start: '101',
          segments: [{
            target: '101',
            durationSeconds: 20,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 1,
          }],
        },
        last: {
          start: '100',
          segments: [{
            target: '100',
            durationSeconds: 20,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 1,
          }],
        },
      },
    }],
  }
  assert.equal(estimateLocalExpectedWorkUnits(advanced), 70)

  const malformed = structuredClone(simple)
  forceFirstSimpleDuration(malformed, Number.POSITIVE_INFINITY)
  assert.equal(estimateLocalExpectedWorkUnits(malformed), null)
})

test('scheduler debounces 300 ms, routes small work to main, and latest generation wins', () => {
  const clock = new ManualClock()
  const calculatedNames: string[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: (input) => {
      calculatedNames.push(input.name)
      return input.name === 'blocked' ? blockedWorkspace : calculatedWorkspace
    },
    createWorker: () => {
      throw new Error('small work must not create a Worker')
    },
  }))
  const states: string[] = []
  scheduler.subscribe(() => states.push(scheduler.getSnapshot().status))

  const first = scenario()
  first.name = 'stale'
  scheduler.schedule(first)
  const second = scenario()
  second.name = 'blocked'
  scheduler.schedule(second)

  assert.deepEqual(clock.delays, [300, 300])
  assert.equal(clock.pendingCount, 1)
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'CALCULATING',
    generation: 2,
  })

  clock.runAll()

  assert.deepEqual(calculatedNames, ['blocked'])
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'BLOCKED',
    generation: 2,
    outcome: blockedOutcome,
    localTickWindow: emptyWindow,
  })
  assert.deepEqual(states, ['CALCULATING', 'CALCULATING', 'BLOCKED'])
})

test('scheduler routes large and malformed estimates to Worker and fences stale responses', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('large work must not calculate on the main thread')
    },
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))

  const first = scenario(25_000)
  scheduler.schedule(first)
  clock.runAll()
  assert.equal(workers.length, 1)
  assert.deepEqual(workers[0]?.posted, [{
    type: 'CALCULATE_WORKSPACE',
    generation: 1,
    scenario: first,
  }])
  const staleError = workers[0]?.onerror

  const malformed = scenario()
  forceFirstSimpleDuration(malformed, Number.POSITIVE_INFINITY)
  scheduler.schedule(malformed)
  assert.equal(workers[0]?.terminateCount, 1)
  clock.runAll()
  assert.equal(workers.length, 2)

  workers[0]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: calculatedOutcome,
    localTickWindow: emptyWindow,
  })
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'CALCULATING',
    generation: 2,
  })
  staleError?.({
    message: 'stale crash',
    preventDefault() {},
  } as ErrorEvent)
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'CALCULATING',
    generation: 2,
  })

  workers[1]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 2,
    outcome: blockedOutcome,
    localTickWindow: emptyWindow,
  })
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'BLOCKED',
    generation: 2,
    outcome: blockedOutcome,
    localTickWindow: emptyWindow,
  })
  assert.equal(workers[1]?.terminateCount, 1)
})

test('current Worker crash becomes FAILED while a stale crash cannot overwrite newer state', () => {
  const clock = new ManualClock()
  const worker = new FakeWorker()
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    createWorker: () => worker,
  }))

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  worker.crash(`Worker crashed ${'z'.repeat(1000)}`)

  const state = scheduler.getSnapshot()
  assert.equal(state.status, 'FAILED')
  if (state.status === 'FAILED') {
    assert.ok(state.message.startsWith('Worker crashed'))
    assert.ok(state.message.length <= 240)
  }
  assert.equal(worker.terminateCount, 1)
})

test('scheduler fails closed for Worker unavailability and bounds plain failure messages', () => {
  const clock = new ManualClock()
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    createWorker: () => {
      throw new Error(`Worker unavailable ${'x'.repeat(1000)}`)
    },
  }))

  scheduler.schedule(scenario(25_000))
  clock.runAll()

  const state = scheduler.getSnapshot()
  assert.equal(state.status, 'FAILED')
  if (state.status === 'FAILED') {
    assert.equal(typeof state.message, 'string')
    assert.ok(state.message.length <= 240)
    assert.equal(state.message.includes('Error:'), false)
  }
  assert.equal('localTickWindow' in state, false)
})

test('market generation failure exposes FAILED without a local Tick window', () => {
  const clock = new ManualClock()
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('market generation failed')
    },
  }))

  scheduler.schedule(scenario())
  clock.runAll()

  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'FAILED',
    generation: 1,
    message: 'market generation failed',
  })
  assert.equal('localTickWindow' in scheduler.getSnapshot(), false)
})

test('dispose clears timers/listeners, terminates Worker, and ignores post-dispose results', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))
  let notifications = 0
  scheduler.subscribe(() => {
    notifications += 1
  })

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  const beforeDispose = scheduler.getSnapshot()
  const beforeNotifications = notifications
  scheduler.dispose()

  assert.equal(workers[0]?.terminateCount, 1)
  assert.equal(clock.pendingCount, 0)
  workers[0]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: calculatedOutcome,
    localTickWindow: emptyWindow,
  })
  scheduler.schedule(scenario())
  clock.runAll()
  assert.strictEqual(scheduler.getSnapshot(), beforeDispose)
  assert.equal(notifications, beforeNotifications)
})

test('Worker keeps legacy RESULT tick-free and adds a bounded workspace result discriminant', () => {
  const request = {
    type: 'CALCULATE' as const,
    generation: 7,
    scenario: scenario(),
  }
  const result = handleLocalOracleWorkerRequest(
    request,
    () => calculatedOutcome,
  )
  assert.deepEqual(result, {
    type: 'RESULT',
    generation: 7,
    outcome: calculatedOutcome,
  })
  assert.equal('ticks' in result, false)
  assert.equal('localTickWindow' in result, false)

  const workspaceRequest = {
    type: 'CALCULATE_WORKSPACE' as const,
    generation: 8,
    scenario: scenario(),
  }
  const workspaceResult = handleLocalOracleWorkerRequest(
    workspaceRequest,
    () => calculatedOutcome,
    () => calculatedWorkspace,
  )
  assert.deepEqual(workspaceResult, {
    type: 'WORKSPACE_RESULT',
    generation: 8,
    outcome: calculatedOutcome,
    localTickWindow: emptyWindow,
  })

  const oversizedWorkspaceResult = handleLocalOracleWorkerRequest(
    workspaceRequest,
    () => calculatedOutcome,
    () => ({
      outcome: calculatedOutcome,
      localTickWindow: {
        ...emptyWindow,
        ticks: Array.from({ length: 10_001 }, (_, index) => ({
          sequence: index + 1,
          virtualTime: new Date(
            Date.UTC(2026, 0, 1, 0, 0, index + 1),
          ).toISOString(),
          instruments: [],
          fundingRates: [],
        })),
        totalCount: 10_001,
        firstSequence: 1,
        lastSequence: 10_001,
      },
    }),
  )
  assert.equal(oversizedWorkspaceResult.type, 'FAILED')
  assert.equal('localTickWindow' in oversizedWorkspaceResult, false)

  const failed = handleLocalOracleWorkerRequest(request, () => {
    throw new Error(`calculation exploded ${'y'.repeat(1000)}`)
  })
  assert.equal(failed.type, 'FAILED')
  assert.equal(failed.generation, 7)
  if (failed.type === 'FAILED') {
    assert.equal(typeof failed.message, 'string')
    assert.ok(failed.message.length <= 240)
    assert.equal(failed.message.includes('Error:'), false)
  }
})

test('scheduler rejects malformed and oversized workspace Worker results without exposing a window', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('large work must not calculate on the main thread')
    },
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  workers[0]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: calculatedOutcome,
    localTickWindow: {
      ...emptyWindow,
      ticks: Array.from({ length: 10_001 }, (_, index) => ({
        sequence: index + 1,
        virtualTime: new Date(
          Date.UTC(2026, 0, 1, 0, 0, index + 1),
        ).toISOString(),
        instruments: [],
        fundingRates: [],
      })),
      totalCount: 10_001,
      firstSequence: 1,
      lastSequence: 10_001,
    },
  })

  const oversizedState = scheduler.getSnapshot()
  assert.equal(oversizedState.status, 'FAILED')
  assert.equal('localTickWindow' in oversizedState, false)

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  workers[1]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: calculatedOutcome,
    localTickWindow: {
      ...emptyWindow,
      ticks: Array.from({ length: 10_001 }, (_, index) => ({
        sequence: index + 1,
        virtualTime: new Date(
          Date.UTC(2026, 0, 1, 0, 0, index + 1),
        ).toISOString(),
        instruments: [],
        fundingRates: [],
      })),
      totalCount: 10_001,
      firstSequence: 1,
      lastSequence: 10_001,
    },
  })
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'CALCULATING',
    generation: 2,
  })

  workers[1]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 2,
    outcome: calculatedOutcome,
    localTickWindow: {
      ...emptyWindow,
      totalCount: 1,
      firstSequence: 99,
    },
  })
  const malformedState = scheduler.getSnapshot()
  assert.equal(malformedState.status, 'FAILED')
  assert.equal('localTickWindow' in malformedState, false)
})

test('scheduler rejects hidden payloads, malformed Tick rows, and non-tail Worker windows', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('large work must not calculate on the main thread')
    },
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))

  const invalidResponses: ReadonlyArray<readonly [string, unknown]> = [
    ['response extra report', {
      type: 'WORKSPACE_RESULT',
      generation: 1,
      outcome: calculatedOutcome,
      localTickWindow: validWorkerWindow,
      report: { full: true },
    }],
    ['window extra full ticks', {
      type: 'WORKSPACE_RESULT',
      generation: 2,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        fullTicks: [validWorkerTick],
      },
    }],
    ['Tick extra report', {
      type: 'WORKSPACE_RESULT',
      generation: 3,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        ticks: [{ ...validWorkerTick, report: { full: true } }],
      },
    }],
    ['instrument extra debug field', {
      type: 'WORKSPACE_RESULT',
      generation: 4,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        ticks: [{
          ...validWorkerTick,
          instruments: [{
            ...validWorkerTick.instruments[0],
            debug: true,
          }],
        }],
      },
    }],
    ['funding rate is not a decimal string', {
      type: 'WORKSPACE_RESULT',
      generation: 5,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        ticks: [{
          ...validWorkerTick,
          fundingRates: [{ symbol: 'XBT-USDT-LAB-PERP', rate: 0.001 }],
        }],
      },
    }],
    ['workspace result is not the latest tail', {
      type: 'WORKSPACE_RESULT',
      generation: 6,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        totalCount: 2,
        hasOlder: false,
        hasNewer: true,
      },
    }],
    ['workspace result changes the default limit', {
      type: 'WORKSPACE_RESULT',
      generation: 7,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        limit: 1,
      },
    }],
    ['workspace result omits rows inside the bounded limit', {
      type: 'WORKSPACE_RESULT',
      generation: 8,
      outcome: calculatedOutcome,
      localTickWindow: {
        ...validWorkerWindow,
        totalCount: 2,
        hasOlder: true,
        olderBeforeSequence: 1,
      },
    }],
  ]

  for (const [index, [label, response]] of invalidResponses.entries()) {
    scheduler.schedule(scenario(25_000))
    clock.runAll()
    workers[index]?.respond(response)
    assert.deepEqual(scheduler.getSnapshot(), {
      status: 'FAILED',
      generation: index + 1,
      message: 'Worker 返回无效结果',
    }, label)
    assert.equal('localTickWindow' in scheduler.getSnapshot(), false, label)
  }
})

test('scheduler rejects incomplete CALCULATED and BLOCKED outcomes from Worker', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('large work must not calculate on the main thread')
    },
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  workers[0]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: { status: 'CALCULATED' },
    localTickWindow: emptyWindow,
  })
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'FAILED',
    generation: 1,
    message: 'Worker 返回无效结果',
  })
  assert.equal('localTickWindow' in scheduler.getSnapshot(), false)

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  workers[1]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 2,
    outcome: { status: 'BLOCKED' },
    localTickWindow: emptyWindow,
  })
  assert.deepEqual(scheduler.getSnapshot(), {
    status: 'FAILED',
    generation: 2,
    message: 'Worker 返回无效结果',
  })
  assert.equal('localTickWindow' in scheduler.getSnapshot(), false)
})

test('scheduler validates every nested Worker outcome row before publishing typed state', () => {
  const clock = new ManualClock()
  const workers: FakeWorker[] = []
  const scheduler = createLocalOracleScheduler(dependencies(clock, {
    calculate: () => {
      throw new Error('large work must not calculate on the main thread')
    },
    createWorker: () => {
      const worker = new FakeWorker()
      workers.push(worker)
      return worker
    },
  }))
  const validSnapshot = calculatedOutcomeWithSnapshot.result.snapshots[0]
  assert.notEqual(validSnapshot, undefined)

  scheduler.schedule(scenario(25_000))
  clock.runAll()
  workers[0]?.respond({
    type: 'WORKSPACE_RESULT',
    generation: 1,
    outcome: calculatedOutcomeWithSnapshot,
    localTickWindow: emptyWindow,
  })
  assert.equal(scheduler.getSnapshot().status, 'CALCULATED')

  const invalidSnapshots: ReadonlyArray<readonly [string, unknown]> = [
    ['orders row', { ...validSnapshot, orders: [null] }],
    ['trades row', { ...validSnapshot, trades: [{}] }],
    ['spot position row', {
      ...validSnapshot,
      spotPositions: [{
        ...validSnapshot!.spotPositions[0],
        averageCost: 100,
      }],
    }],
    ['perpetual position row', {
      ...validSnapshot,
      perpetualPositions: [{
        ...validSnapshot!.perpetualPositions[0],
        marginMode: 'PORTFOLIO',
      }],
    }],
    ['wallet row', { ...validSnapshot, wallets: [null] }],
    ['ledger row', {
      ...validSnapshot,
      ledgerProjection: [{
        ...validSnapshot!.ledgerProjection[0],
        amount: Number.NaN,
      }],
    }],
    ['warning row', { ...validSnapshot, warnings: [null] }],
    ['account summary', {
      ...validSnapshot,
      accountSummary: {
        ...validSnapshot!.accountSummary,
        equityUsdt: null,
      },
    }],
    ['risk row', {
      ...validSnapshot,
      risk: {
        ...validSnapshot!.risk,
        marginRatio: 1,
      },
    }],
    ['positive sequence', { ...validSnapshot, tickSequence: 0 }],
    ['canonical UTC time', {
      ...validSnapshot,
      virtualTime: '2026-01-01T08:00:01+08:00',
    }],
  ]

  for (const [index, [label, invalidSnapshot]] of (
    invalidSnapshots.entries()
  )) {
    const generation = index + 2
    scheduler.schedule(scenario(25_000))
    clock.runAll()
    workers[index + 1]?.respond({
      type: 'WORKSPACE_RESULT',
      generation,
      outcome: {
        ...calculatedOutcomeWithSnapshot,
        result: {
          ...calculatedOutcomeWithSnapshot.result,
          snapshots: [invalidSnapshot],
        },
      },
      localTickWindow: emptyWindow,
    })
    assert.deepEqual(scheduler.getSnapshot(), {
      status: 'FAILED',
      generation,
      message: 'Worker 返回无效结果',
    }, label)
  }
})

test('Worker source has one Oracle seam and no React, storage, API, fetch, backend, or Pass/Fail payload', () => {
  const source = readFileSync(
    new URL('./localOracleWorker.ts', import.meta.url),
    'utf8',
  )
  for (const token of [
    'react',
    'indexedDB',
    'localStorage',
    'sessionStorage',
    'fetch(',
    '/api/',
    '/backend/',
    '\\backend\\',
    'draftStore',
    'Pass',
    'Fail',
  ]) {
    assert.equal(source.includes(token), false, `forbidden Worker token: ${token}`)
  }
  assert.equal(source.includes('../generator/marketPath.ts'), false)
  assert.equal(source.includes('../oracle/runOracle.ts'), false)
  assert.equal(source.includes('./calculateLocalExpectedScenario.ts'), true)
})
