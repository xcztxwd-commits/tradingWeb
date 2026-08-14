import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

import {
  allowExpectedBatchBindingErrors,
  P0PublicProviderUnavailableError,
  runSingleUserCoreCase,
  stepAlignedQuantity,
  tradingStateFingerprint
} from './p0-user-trading-core-cases.mjs'
import {
  alignPriceToTick,
  effectiveQuantityStep,
  floorToStep,
  fundingSettlementOracle,
  isolatedLiquidationOracle,
  withinTolerance
} from './p0-user-trading-oracles.mjs'
import {
  assertFundingSettlementContract,
  assertLiquidationLifecycleContract
} from './p0-user-trading-funding-liquidation-cases.mjs'

const SPOT = 'BTCUSDT'
const PERP = 'BTCUSDT-PERP'
const DESKTOP = Object.freeze({ name: 'desktop', width: 1440, height: 900, mobile: false })
const MOBILE = Object.freeze({ name: 'mobile', width: 390, height: 844, mobile: true })
const ACTIVE_ORDER_STATUSES = new Set([
  'RECEIVED',
  'VALIDATING',
  'ACCEPTED',
  'PENDING_ACTIVATION',
  'PENDING',
  'WORKING',
  'PARTIALLY_FILLED',
  'CANCEL_PENDING'
])
const REPLAY_CONFLICT_CODES = new Set([
  'BATCH_REQUEST_CONFLICT',
  'DUPLICATE_CLIENT_ORDER_ID',
  'IDEMPOTENCY_CONFLICT',
  'TRANSFER_REQUEST_CONFLICT'
])
const RACE_LOSER_CODES = new Set([
  'POSITION_NOT_FOUND',
  'ORDER_NOT_FOUND',
  'ORDER_NOT_CANCELLABLE',
  'ORDER_ALREADY_TERMINAL',
  'POSITION_VERSION_CONFLICT',
  'ORDER_VERSION_CONFLICT',
  'IDEMPOTENCY_CONFLICT',
  'BATCH_REQUEST_CONFLICT'
])
const PUBLIC_PROVIDER_UNAVAILABLE_CODES = new Set([
  'MARKET_DATA_STALE',
  'MARKET_DATA_UNAVAILABLE',
  'MARKET_PROVIDER_BINDING_NOT_FOUND'
])

export function assertAuthorityBundleContract(bundle, expected) {
  const { symbol, providers, sourceMode } = expected
  assert(bundle?.quote, `${symbol} quote is required`)
  assert(bundle?.depth, `${symbol} depth is required`)
  assert(Array.isArray(bundle.trades) && bundle.trades.length > 0, `${symbol} trades must not be empty`)
  assert(Array.isArray(bundle.candles) && bundle.candles.length > 0, `${symbol} candles must not be empty`)
  assert(Array.isArray(bundle.depth.bids) && bundle.depth.bids.length > 0, `${symbol} depth bids`)
  assert(Array.isArray(bundle.depth.asks) && bundle.depth.asks.length > 0, `${symbol} depth asks`)
  const parts = [bundle.quote, bundle.depth, bundle.trades[0]]
  if (symbol.endsWith('-PERP')) {
    assert(bundle.reference, `${symbol} reference is required`)
    parts.push(bundle.reference)
  }
  for (const [index, part] of parts.entries()) {
    assertSourceMetadata(part, `${symbol} bundle ${index}`)
  }
  for (const [index, candle] of bundle.candles.entries()) {
    assertSourceMetadata(candle, `${symbol} candle ${index}`)
    assert.equal(candle.providerCode, bundle.quote.providerCode, `${symbol} candle provider ${index}`)
    assert.equal(candle.sourceMode, bundle.quote.sourceMode, `${symbol} candle source ${index}`)
  }
  const providerCodes = new Set(parts.map(({ providerCode }) => providerCode))
  const sourceModes = new Set(parts.map(({ sourceMode: mode }) => mode))
  assert.equal(providerCodes.size, 1, `${symbol} bundle provider must not mix`)
  assert.equal(sourceModes.size, 1, `${symbol} bundle source must not mix`)
  assert(providers.includes(bundle.quote.providerCode), `${symbol} unexpected provider`)
  if (sourceMode !== undefined) assert.equal(bundle.quote.sourceMode, sourceMode, `${symbol} sourceMode`)
  for (const field of ['bid', 'ask']) assertPositiveNumber(bundle.quote[field], `${symbol} quote ${field}`)
  if (symbol.endsWith('-PERP')) {
    for (const field of ['bid', 'ask', 'last', 'mark', 'index']) {
      assertPositiveNumber(bundle.reference[field], `${symbol} reference ${field}`)
    }
  }
  for (const [index, candle] of bundle.candles.entries()) {
    for (const field of ['timestamp', 'open', 'high', 'low', 'close']) {
      assert(Number.isFinite(Number(candle[field])), `${symbol} candle ${index} ${field}`)
    }
  }
  if (sourceMode === 'LOCAL_SIMULATED') {
    assert.equal(new Set(parts.map(({ asOf }) => asOf)).size, 1, `${symbol} local bundle generation/asOf`)
  }
  return bundle
}

export function assertReplayConflict(capture, label, expectedCode) {
  assert(REPLAY_CONFLICT_CODES.has(expectedCode), `${label} expected conflict code is required`)
  assert(capture?.status >= 400 && capture.status < 500, `${label} conflict must return 4xx`)
  const code = responseCode(capture)
  assert.equal(code, expectedCode, `${label} must return ${expectedCode}, got ${code}`)
  return code
}

export function assertFilteredRecordIds({ visibleIds, expectedIds, foreignIds = [], label }) {
  const visible = new Set(visibleIds)
  const expected = new Set(expectedIds)
  const foreign = new Set(foreignIds)
  assert.equal(visible.size, visibleIds.length, `${label} contains duplicate records`)
  for (const id of visibleIds) {
    assert(!foreign.has(id), `${label} contains foreign record ${id}`)
    assert(expected.has(id), `${label} contains unexpected record ${id}`)
  }
  assert.deepEqual([...visible].sort(), [...expected].sort(), `${label} must show every filtered record`)
  return visibleIds
}

export function assertPositionRowViews(visibleRows, expectedRows, label) {
  const canonical = (row) => JSON.stringify(row)
  assert.deepEqual(
    visibleRows.map(canonical).sort(),
    expectedRows.map(canonical).sort(),
    `${label} must show every filtered position`
  )
  return visibleRows
}

export function requirePositionHistoryTarget(snapshot, label) {
  const target = snapshot?.positionHistory?.at(0)
  assert(target, `${label} requires Perp position history`)
  return target
}

export function assertStaleTradingStateUnchanged(before, after) {
  for (const field of [
    'accountRow',
    'walletRows',
    'orderRows',
    'orderEventRows',
    'tradeRows',
    'positionRows',
    'spotPositionRows',
    'assetLedgerRows',
    'cashLedgerRows'
  ]) {
    assert.deepEqual(after[field], before[field], `stale market must not mutate ${field}`)
  }
  return after
}

export function assertPollingFallbackWindow({
  baselineAtMs,
  observedAtMs,
  requests,
  label
}) {
  const elapsedMs = observedAtMs - baselineAtMs
  assert(elapsedMs >= 10000, `${label} polling request arrived too early`)
  assert(elapsedMs <= 18500, `${label} polling request arrived too late`)
  assert.equal(requests.length, 1, `${label} must issue exactly one polling request`)
  return requests[0]
}

export function assertBackendOutageUiState(state) {
  assert(
    /(?:Backend order failed|后端下单失败|バックエンド注文失敗)/u.test(state?.statusText ?? ''),
    'backend outage must show a visible backend failure'
  )
  assert.equal(state?.tabSelected, true, 'backend outage must permit an account tab change')
  assert(String(state?.tabLabel ?? '').trim().length > 0, 'backend outage account tab label')
  return state
}

export function assertLocalTradeAuthority(trades) {
  assert(trades.length > 0, 'LOCAL_SIMULATED must produce Trade evidence')
  for (const trade of trades) {
    const expectedProvider = trade.symbol.endsWith('-PERP') ? 'local-perp' : 'local-spot'
    assert.equal(
      trade.providerCode,
      expectedProvider,
      `${trade.id ?? trade.symbol} must use ${expectedProvider}`
    )
    assert.equal(
      trade.sourceMode,
      'LOCAL_SIMULATED',
      `${trade.id ?? trade.symbol} must use LOCAL_SIMULATED`
    )
  }
  return trades
}

function matchLedgerRowIds(rowTexts, expected) {
  return rowTexts.map((text) => {
    const matches = expected.filter((entry) => [
      entry.walletType,
      entry.entryType,
      entry.amount,
      entry.balanceAfter,
      entry.currency,
      entry.description
    ].filter((value) => value !== null && value !== undefined && value !== '')
      .every((value) => text.includes(String(value))))
    assert.equal(matches.length, 1, `wallet ledger row must identify one REST id: ${text}`)
    return matches[0].id
  })
}

export function runSource01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runSourcePrimaryJourney(scope, {
      mode: 'BINANCE_PUBLIC',
      sourceMode: 'PUBLIC_EXTERNAL',
      spotProvider: 'binance',
      perpProvider: 'binance-usdm'
    })
  ))
}

export function runSource02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSourceFailoverJourney)
}

export function runSource03(context, definition, details = {}) {
  return runIndependentSubruns(
    context, definition, details, runSourceLocalJourney,
    { kind: 'LOCAL_SIMULATED_INDEPENDENT_SUBRUNS' }
  )
}

export function runSource04(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSourceRecoveryJourney)
}

export function runRes01(context, definition, details = {}) {
  return runIndependentSubruns(
    context, definition, details, runReplayJourney,
    { kind: 'IDEMPOTENCY_REPLAY_INDEPENDENT_ACTIONS' }
  )
}

export function runRes02(context, definition, details = {}) {
  return runIndependentSubruns(
    context, definition, details, runRaceJourney,
    { kind: 'CONCURRENT_TERMINAL_STATE_INDEPENDENT_RACES' }
  )
}

export function runRes03(context, definition, details = {}) {
  return runIndependentSubruns(
    context, definition, details, runRestartJourney,
    { kind: 'BACKEND_RESTART_INDEPENDENT_SUBRUNS' }
  )
}

export function runRes04(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runRecoveryJourney)
}

export function runUi01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runViewportParityJourney)
}

export function runUi02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runHistoryAccessibilityJourney)
}

export const CASE_HANDLERS = Object.freeze({
  runSource01,
  runSource02,
  runSource03,
  runSource04,
  runRes01,
  runRes02,
  runRes03,
  runRes04,
  runUi01,
  runUi02
})

export async function runIndependentSubruns(
  context,
  definition,
  details,
  execute,
  summaryEvidence,
  runSingle = runSingleUserCoreCase
) {
  const results = []
  for (const subrun of definition.requiredSubruns) {
    let persisted
    const evidence = context.evidence
    const subContext = {
      ...context,
      userFactory: (seed) => context.userFactory(`${seed}-${subrun.id}`),
      evidence: {
        ...evidence,
        captureCheckpoint(checkpointContext, name, scope = {}) {
          return evidence.captureCheckpoint.call(
            evidence,
            checkpointContext,
            name,
            { ...scope, subrunIdentity: subrun.id }
          )
        },
        writeCaseResultAtomic(_path, result) {
          persisted = result
          return result
        }
      }
    }
    const subDefinition = { ...definition, requiredSubruns: [subrun] }
    try {
      const result = await runSingle(
        subContext,
        subDefinition,
        details,
        (scope) => execute(scope, subrun)
      )
      assert.equal(result, persisted, `${definition.id} ${subrun.id} persisted result`)
      results.push(result)
    } catch (error) {
      if (persisted) {
        context.evidence.writeCaseResultAtomic(
          join(context.run.artifactRoot, definition.id, 'result.json'),
          persisted
        )
      }
      throw error
    }
  }
  assert(results.length > 0, `${definition.id} requires an executable subrun`)
  const merged = mergeIndependentCaseResults(definition, results, summaryEvidence)
  context.evidence.writeCaseResultAtomic(
    join(context.run.artifactRoot, definition.id, 'result.json'),
    merged
  )
  return merged
}

function mergeIndependentCaseResults(definition, results, summaryEvidence) {
  const startedAt = results.map(({ startedAt }) => startedAt).toSorted()[0]
  const finishedAt = results.map(({ finishedAt }) => finishedAt).toSorted().at(-1)
  const blocked = results.find(({ status }) => status === 'BLOCKED')
  const status = blocked ? 'BLOCKED' : 'PASS'
  const artifactHashes = {}
  for (const result of results) {
    for (const [path, hash] of Object.entries(result.artifactHashes ?? {})) {
      assert(
        artifactHashes[path] === undefined || artifactHashes[path] === hash,
        `${definition.id} artifact hash collision ${path}`
      )
      artifactHashes[path] = hash
    }
  }
  const oracleEvidence = [
    summaryEvidence,
    ...results.flatMap(({ oracleEvidence }) => oracleEvidence ?? [])
  ]
  return {
    ...results[0],
    id: definition.id,
    status,
    durationMs: Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt)),
    scopeComplete: results.every(({ scopeComplete }) => scopeComplete === true),
    artifactHashes,
    subruns: results.flatMap(({ subruns }) => subruns ?? []),
    startedAt,
    finishedAt,
    userActions: results.flatMap(({ userActions }) => userActions ?? []),
    fixtureActions: results.flatMap(({ fixtureActions }) => fixtureActions ?? []),
    contractProbes: results.flatMap(({ contractProbes }) => contractProbes ?? []),
    replayProbes: results.flatMap(({ replayProbes }) => replayProbes ?? []),
    checkpoints: results.flatMap(({ checkpoints }) => checkpoints ?? []),
    uiEvidence: results.flatMap(({ uiEvidence }) => uiEvidence ?? []),
    networkEvidence: results.flatMap(({ networkEvidence }) => networkEvidence ?? []),
    apiEvidence: results.flatMap(({ apiEvidence }) => apiEvidence ?? []),
    dbEvidence: results.flatMap(({ dbEvidence }) => dbEvidence ?? []),
    eventEvidence: results.flatMap(({ eventEvidence }) => eventEvidence ?? []),
    financialCalculation: status === 'BLOCKED'
      ? { status, reasonCode: blocked.failureOrBlocker?.reasonCode }
      : { status, checks: oracleEvidence },
    oracleEvidence,
    snapshots: Object.fromEntries(results.flatMap((result, index) => (
      Object.entries(result.snapshots ?? {}).map(([name, value]) => [
        `${result.subruns?.[0]?.id ?? index}:${name}`,
        value
      ])
    ))),
    consoleErrors: results.flatMap(({ consoleErrors }) => consoleErrors ?? []),
    cleanup: { status: 'PASS' },
    ...(blocked ? { failureOrBlocker: blocked.failureOrBlocker } : { failureOrBlocker: undefined })
  }
}

async function runSourcePrimaryJourney(scope, authority) {
  const fixture = await useProviderBindings(scope, {
    [SPOT]: ['binance', 'okx', 'local-spot'],
    [PERP]: ['binance-usdm', 'okx-swap', 'local-perp']
  }, 'source primary bindings')
  assertBindingPriority(fixture, SPOT, 'binance', 'okx', true)
  assertBindingPriority(fixture, PERP, 'binance-usdm', 'okx-swap', true)
  const spotBundle = await externalAuthorityBundle(scope, SPOT, authority.spotProvider, authority.sourceMode)
  const spot = await spotRoundTrip(scope, scope.page, {
    label: authority.mode,
    expectedAuthority: spotBundle.quote
  })
  const perpBundle = await externalAuthorityBundle(scope, PERP, authority.perpProvider, authority.sourceMode)
  const perp = await perpRoundTrip(scope, scope.page, {
    label: authority.mode,
    expectedAuthority: perpBundle.quote
  })
  return finish(scope, perp.snapshot, {
    kind: 'SOURCE_PRIMARY',
    mode: authority.mode,
    spotProvider: spot.trade.providerCode,
    perpProvider: perp.openTrade.providerCode
  })
}

async function runSourceFailoverJourney(scope) {
  const required = subrunIds(scope)
  const fixture = await useProviderBindings(scope, {
    [SPOT]: ['okx', 'local-spot'],
    [PERP]: ['okx-swap', 'local-perp']
  }, 'Binance disabled for OKX failover')
  assertBindingPriority(fixture, SPOT, 'binance', 'okx', false)
  assertBindingPriority(fixture, PERP, 'binance-usdm', 'okx-swap', false)
  const spotBundle = await externalAuthorityBundle(scope, SPOT, 'okx', 'PUBLIC_EXTERNAL')
  const perpBundle = await externalAuthorityBundle(scope, PERP, 'okx-swap', 'PUBLIC_EXTERNAL')
  let snapshot
  if ([...required].some((id) => id.includes('ui-core'))) {
    const spot = await spotRoundTrip(scope, scope.page, {
      label: 'SOURCE-02 OKX',
      expectedAuthority: spotBundle.quote
    })
    const perp = await perpRoundTrip(scope, scope.page, {
      label: 'SOURCE-02 OKX',
      expectedAuthority: perpBundle.quote
    })
    snapshot = perp.snapshot
    scope.oracleEvidence.push({
      kind: 'OKX_FULL_FILL',
      spotTradeId: spot.trade.id,
      perpTradeId: perp.openTrade.id
    })
  }
  if ([...required].some((id) => id.includes('order-trigger'))) {
    await pendingSpotCancel(scope, scope.page, { label: 'SOURCE-02 OKX pending' })
    const stopped = await immediatePerpStopClose(scope, scope.page, {
      label: 'SOURCE-02 OKX STOP',
      expectedAuthority: perpBundle.quote
    })
    snapshot = stopped.snapshot
  }
  return finish(scope, snapshot, {
    kind: 'SOURCE_FAILOVER',
    disabled: ['binance', 'binance-usdm'],
    selected: ['okx', 'okx-swap']
  })
}

async function runSourceLocalJourney(scope) {
  const required = subrunIds(scope)
  await useProviderBindings(scope, {
    [SPOT]: ['local-spot'],
    [PERP]: ['local-perp']
  }, 'LOCAL_SIMULATED only')
  const spotBundle = await authorityBundle(scope, SPOT, 'local-spot', 'LOCAL_SIMULATED')
  const perpBundle = await authorityBundle(scope, PERP, 'local-perp', 'LOCAL_SIMULATED')
  let snapshot
  if (required.has('desktop-trade-ui-core')) {
    const spot = await spotRoundTrip(scope, scope.page, {
      label: 'SOURCE-03 LOCAL',
      expectedAuthority: spotBundle.quote
    })
    const perp = await perpRoundTrip(scope, scope.page, {
      label: 'SOURCE-03 LOCAL',
      expectedAuthority: perpBundle.quote,
      partial: true
    })
    snapshot = perp.snapshot
    scope.oracleEvidence.push({
      kind: 'LOCAL_TRADE_CLOSED_LOOP',
      spotTradeId: spot.trade.id,
      perpTradeId: perp.openTrade.id
    })
  }
  if (required.has('desktop-trigger-order-trigger')) {
    const limit = await triggerPendingSpot(scope, 'SOURCE-03 LOCAL LIMIT')
    const spotStop = await triggerStandaloneSpotStop(scope, 'SOURCE-03 LOCAL STOP_MARKET')
    const oco = await triggerOcoWinner(scope, 'SOURCE-03 LOCAL OCO')
    const perpLimit = await triggerPendingPerpLimit(scope, 'SOURCE-03 LOCAL PERP LIMIT')
    const stopped = await immediatePerpStopClose(scope, scope.page, {
      label: 'SOURCE-03 LOCAL STOP',
      expectedAuthority: perpBundle.quote
    })
    const protectedClose = await triggerPerpProtection(scope, 'SOURCE-03 LOCAL protection')
    const takeProfit = await triggerPerpTakeProfit(scope, 'SOURCE-03 LOCAL take-profit')
    snapshot = takeProfit.snapshot
    scope.oracleEvidence.push({
      kind: 'LOCAL_TRIGGER_CLOSED_LOOP',
      spotLimitTradeId: limit.trade.id,
      spotStopTradeId: spotStop.trade.id,
      ocoGroupId: oco.groupId,
      ocoWinnerTradeId: oco.trade.id,
      perpLimitTradeId: perpLimit.openTrade.id,
      stopTradeId: stopped.closeTrade.id,
      protectionTradeId: protectedClose.trade.id,
      takeProfitTradeId: takeProfit.trade.id
    })
  }
  if (required.has('desktop-funding')) {
    snapshot = await localFundingSettlement(scope)
  }
  if (required.has('desktop-liquidation')) {
    snapshot = await localLiquidation(scope)
  }
  assertLocalTradeAuthority(recordsAfter(scope.before, snapshot, 'trades'))
  return finish(scope, snapshot, {
    kind: 'LOCAL_SIMULATED',
    providerCode: 'local-perp',
    sourceMode: 'LOCAL_SIMULATED'
  })
}

async function runSourceRecoveryJourney(scope) {
  const fixture = await useProviderBindings(scope, {
    [SPOT]: ['local-spot'],
    [PERP]: ['local-perp']
  }, 'source recovery fallback')
  const mobile = await openMobileSession(scope, 'source-recovery')
  try {
    await authorityBundle(scope, PERP, 'local-perp', 'LOCAL_SIMULATED')
    await scope.context.ui.openTradePanel(scope.page, { product: 'perpetual', symbol: PERP })
    await scope.context.ui.openTradePanel(mobile.page, {
      product: 'perpetual', symbol: PERP, mobile: true
    })
    const opened = await openPerp(scope, scope.page, { label: 'SOURCE-04 safe position' })
    const pending = await pendingSpot(scope, scope.page, { label: 'SOURCE-04 pending' })
    const eventCursors = new Map([
      [scope.page, scope.context.events.snapshotFrames(scope.page).length],
      [mobile.page, scope.context.events.snapshotFrames(mobile.page).length]
    ])

    await fixture.restore()
    let recovered
    try {
      recovered = await externalAuthorityBundle(
        scope,
        PERP,
        'binance-usdm',
        'PUBLIC_EXTERNAL',
        60000
      )
    } catch (error) {
      if (!(error instanceof P0PublicProviderUnavailableError)) throw error
      const cleanupFixture = await useProviderBindings(scope, {
        [SPOT]: ['local-spot'],
        [PERP]: ['local-perp']
      }, 'SOURCE-04 blocked cleanup authority')
      try {
        await cleanupSourceRecoveryState(scope, opened.position, 'SOURCE-04 blocked cleanup')
      } finally {
        await cleanupFixture.restore()
      }
      throw error
    }
    for (const page of [scope.page, mobile.page]) {
      await page.waitForFunction((provider, sourceMode) => {
        const notice = document.querySelector(
          '[data-testid="market-source-change-notice"][role="status"]'
        )
        return notice?.getAttribute('data-current-provider') === provider
          && notice.getAttribute('data-current-source') === sourceMode
          && (notice.textContent?.trim().length ?? 0) > 10
      }, 'readable market source recovery notice', 'binance-usdm', 'PUBLIC_EXTERNAL')
      await waitUntil(() => scope.context.events.snapshotFrames(page)
        .slice(eventCursors.get(page))
        .some(({ direction, eventType }) => (
          direction === 'received' && eventType === 'MARKET_SOURCE_CHANGED'
        )), 'MARKET_SOURCE_CHANGED stream event', 15000)
    }
    assert.equal(recovered.quote.stale, false)
    assert.equal(activeOrders(await scope.context.api.snapshotAccount(scope.page), SPOT).length, 1)
    assert(openPositions(await scope.context.api.snapshotAccount(scope.page), PERP)
      .some(({ id }) => id === opened.position.id))
    await multiPageCheckpoint(scope, 'source-recovered', [scope.page, mobile.page])

    const finalSnapshot = await cleanupSourceRecoveryState(
      scope,
      opened.position,
      'SOURCE-04 recovered cleanup'
    )
    return finish(scope, finalSnapshot, {
      kind: 'SOURCE_RECOVERY',
      from: 'local-perp',
      to: 'binance-usdm',
      asOf: recovered.quote.asOf
    })
  } finally {
    await closeMobileSession(mobile, 'SOURCE-04 mobile')
  }
}

async function cleanupSourceRecoveryState(scope, position, label) {
  let snapshot = await scope.context.api.snapshotAccount(scope.page)
  if (activeOrders(snapshot).length > 0) {
    await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
    const cancel = await scope.context.ui.cancelAllOrdersViaUi(scope.page)
    scope.addMutation(`${slug(label)}-pending-cancel-via-ui`, cancel)
    snapshot = await waitForAccount(scope.context, scope.page, `${label} pending canceled`, (candidate) => (
      activeOrders(candidate).length === 0 ? candidate : false
    ))
  }
  if (openPositions(snapshot, position.symbol).some(({ id }) => id === position.id)) {
    snapshot = (await fullClose(scope, scope.page, position, `${label} close`)).snapshot
  }
  assert.equal(activeOrders(snapshot).length, 0, `${label} must leave no active orders`)
  assert.equal(
    openPositions(snapshot, position.symbol).some(({ id }) => id === position.id),
    false,
    `${label} must close the owned position`
  )
  return snapshot
}

async function runReplayJourney(scope) {
  const required = subrunIds(scope)
  let snapshot = await scope.context.api.snapshotAccount(scope.page)
  if (required.has('desktop-market-order')) {
    const bought = await filledSpotBuy(scope, scope.page, 'RES-01 market', { doubleClick: true })
    await proveReplay(scope, bought.capture, 'RES-01 market order', mutateQuantity, 'DUPLICATE_CLIENT_ORDER_ID')
    const sold = await closeSpot(scope, scope.page, bought.snapshot, 'RES-01 market cleanup')
    snapshot = sold.snapshot
  }
  if (required.has('desktop-pending-cancel')) {
    const pending = await pendingSpot(scope, scope.page, { label: 'RES-01 pending cancel' })
    const cancel = await withRapidDoubleClick(
      scope,
      scope.page,
      'RES-01 pending cancel',
      'cancel-all',
      () => scope.context.ui.cancelAllOrdersViaUi(scope.page)
    )
    scope.addMutation('res-01-pending-cancel-via-ui', cancel)
    await proveReplay(scope, cancel, 'RES-01 pending cancel', mutateExpectedOrderIds, 'BATCH_REQUEST_CONFLICT')
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-01 cancel settled', (candidate) => (
      activeOrders(candidate).length === 0 ? candidate : false
    ))
    assert.equal(snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length, 0)
  }

  const needsPartial = required.has('desktop-partial-close')
  const needsFull = required.has('desktop-full-close')
  if (needsPartial || needsFull) {
    const opened = await openPerp(scope, scope.page, { label: 'RES-01 close replay' })
    let position = opened.position
    if (needsPartial) {
      const partial = await partialClose(
        scope,
        scope.page,
        position,
        'RES-01 partial close',
        { doubleClick: true }
      )
      await proveReplay(scope, partial.capture, 'RES-01 partial close', mutateQuantity, 'DUPLICATE_CLIENT_ORDER_ID')
      position = partial.position
      snapshot = partial.snapshot
    }
    const closed = await fullClose(
      scope,
      scope.page,
      position,
      'RES-01 full close',
      { doubleClick: needsFull }
    )
    if (needsFull) {
      await proveReplay(scope, closed.capture, 'RES-01 full close', mutateQuantity, 'DUPLICATE_CLIENT_ORDER_ID')
    }
    snapshot = closed.snapshot
  }

  if (required.has('desktop-transfer')) {
    const transfer = await withRapidDoubleClick(
      scope,
      scope.page,
      'RES-01 transfer',
      'transfer-confirm',
      () => scope.context.ui.transferViaUi(scope.page, {
        direction: 'SPOT_TO_PERP',
        amount: '10'
      })
    )
    scope.addMutation('res-01-transfer-via-ui', transfer)
    await waitForAccount(scope.context, scope.page, 'RES-01 transfer', (candidate) => (
      recordsAfter(snapshot, candidate, 'transfers').length === 1 ? candidate : false
    ))
    await proveReplay(scope, transfer, 'RES-01 transfer', mutateAmount, 'TRANSFER_REQUEST_CONFLICT')
    const reverse = await scope.context.ui.transferViaUi(scope.page, {
      direction: 'PERP_TO_SPOT',
      amount: '10'
    })
    scope.addMutation('res-01-transfer-restore-via-ui', reverse)
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-01 transfer restore', (candidate) => (
      candidate.transfers.some(({ requestId }) => requestId === parsedResponse(reverse)?.data?.requestId)
        || recordsAfter(snapshot, candidate, 'transfers').length >= 2
        ? candidate
        : false
    ))
  }

  if (required.has('desktop-reset')) {
    const reset = await withRapidDoubleClick(
      scope,
      scope.page,
      'RES-01 reset',
      'reset-confirm',
      () => scope.context.ui.resetDemoViaUi(scope.page)
    )
    scope.addMutation('res-01-reset-via-ui', reset)
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-01 reset', (candidate) => (
      candidate.orders.length === 0
        && candidate.trades.length === 0
        && candidate.positions.length === 0
        ? candidate
        : false
    ))
    await proveReplay(scope, reset, 'RES-01 reset', mutateExpectedDemoGeneration, 'IDEMPOTENCY_CONFLICT')
  }
  const guarded = scope.contractProbes.filter(({ kind }) => kind === 'UI_PENDING_DOUBLE_CLICK_GUARD')
  assert.equal(guarded.length, required.size, 'RES-01 every selected action needs a real double-click probe')
  return finish(scope, snapshot, {
    kind: 'IDEMPOTENCY_REPLAY',
    rawRequest: true,
    idempotencyKey: true
  })
}

async function runRaceJourney(scope) {
  const required = subrunIds(scope)
  const peer = await openMobileSession(scope, 'race-peer')
  let snapshot
  try {
    if (required.has('desktop-fill-cancel')) {
      for (let round = 1; round <= 3; round += 1) {
        const label = `RES-02 fill/cancel round ${round}`
        const pending = await pendingSpot(scope, scope.page, { label })
        await scope.context.ui.openTradePanel(peer.page, { product: 'spot', symbol: SPOT, mobile: true })
        const market = await scope.context.api.snapshotMarket(SPOT)
        const limit = Number(pending.order.price)
        const cursor = peer.page.p0Evidence.cursor
        const [triggerResult, cancelResult] = await Promise.allSettled([
          setMarketOverride(scope, SPOT, limit * 0.98, limit * 0.99, `${label} trigger`),
          scope.context.ui.cancelAllOrdersViaUi(peer.page)
        ])
        assert.equal(triggerResult.status, 'fulfilled', `${label} trigger fixture must succeed`)
        snapshot = await waitForAccount(scope.context, scope.page, `${label} terminal`, (candidate) => {
          const order = candidate.orders.find(({ id }) => id === pending.order.id)
          return order && !ACTIVE_ORDER_STATUSES.has(order.status) ? candidate : false
        }, 45000)
        const terminal = snapshot.orders.find(({ id }) => id === pending.order.id)
        assert(['CANCELED', 'FILLED'].includes(terminal.status), `${label} legal winner`)
        if (terminal.status === 'CANCELED') {
          assert.equal(cancelResult.status, 'fulfilled', `${label} cancel winner must return 2xx`)
          assertCancelRaceWinner(cancelResult.value, pending.order.id, label)
          scope.addMutation(`${slug(label)}-via-ui`, cancelResult.value)
        } else {
          assert.equal(cancelResult.status, 'rejected', `${label} fill winner must reject cancel`)
          const allowedLosers = allowExpectedRaceErrors(peer.page, {
            start: cursor,
            end: peer.page.p0Evidence.cursor,
            requests: [{ method: 'POST', url: /\/api\/trading\/orders\/cancel-all$/u }],
            expectedCodes: ['BATCH_REQUEST_CONFLICT'],
            reason: `${label} fill winner`
          })
          assert.equal(allowedLosers.length, 1, `${label} cancel loser must be one conflict`)
        }
        assert(snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length <= 1)
        const db = await assertRaceRoundDb(scope, snapshot.account.id, {
          label,
          orderIds: [pending.order.id]
        })
        if (findWallet(snapshot, 'SPOT', 'BTC', false)?.available > 0) {
          snapshot = (await closeSpot(scope, scope.page, snapshot, `${label} cleanup`)).snapshot
        }
        scope.oracleEvidence.push({
          kind: 'FILL_CANCEL_RACE',
          round,
          symbol: SPOT,
          quoteBefore: market.quote,
          terminalStatus: terminal.status,
          committedAt: db.orderRows.find(({ id }) => id === pending.order.id)?.updated_at
        })
      }
    }

    if (required.has('desktop-close-protection')) {
      for (let round = 1; round <= 3; round += 1) {
        const label = `RES-02 close/protection round ${round}`
        const opened = await openPerp(scope, scope.page, { label })
        const mark = Number(opened.market.reference?.mark ?? opened.market.quote?.mid)
        const protection = await createStopLossProtectionViaUi(
          scope, scope.page, opened.position, mark * 0.8, `${label} SL`
        )
        await scope.context.ui.openTradePanel(peer.page, {
          product: 'perpetual', symbol: PERP, mobile: true
        })
        const cursor = peer.page.p0Evidence.cursor
        const trigger = Number(protection.order.triggerPrice)
        const [marketResult, closeResult] = await Promise.allSettled([
          setMarketOverride(scope, PERP, trigger * 0.98, trigger * 0.99, `${label} trigger`),
          scope.context.ui.positionActionViaUi(peer.page, {
            positionId: opened.position.id,
            positionSide: opened.position.positionSide,
            action: 'FULL_CLOSE'
          })
        ])
        const allowedLosers = allowExpectedRaceErrors(peer.page, {
          start: cursor,
          end: peer.page.p0Evidence.cursor,
          requests: [{ method: 'POST', url: /\/api\/trading\/positions\/[^/?]+\/close(?:\?|$)/u }],
          reason: `${label} loser`
        })
        assert.equal(marketResult.status, 'fulfilled', `${label} trigger fixture must succeed`)
        if (closeResult.status === 'fulfilled') {
          assert.equal(allowedLosers.length, 0, `${label} successful close has no allowed error`)
          scope.addMutation(`${slug(label)}-via-ui`, closeResult.value)
        } else {
          assert.equal(allowedLosers.length, 1, `${label} rejected close must be the exact race loser`)
        }
        snapshot = await waitForAccount(scope.context, scope.page, `${label} terminal`, (candidate) => (
          openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)
            ? false
            : candidate
        ), 45000)
        const manualCloseOrder = closeResult.status === 'fulfilled'
          ? exactOrderForCapture(protection.snapshot, snapshot, closeResult.value)
          : undefined
        if (closeResult.status === 'fulfilled') {
          assert(manualCloseOrder, `${label} successful manual close order identity`)
        }
        if (activeOrders(snapshot).length > 0) {
          await scope.context.ui.openTradePanel(scope.page, { product: 'perpetual', symbol: PERP })
          const cancel = await scope.context.ui.cancelAllOrdersViaUi(scope.page)
          scope.addMutation(`${slug(label)}-cleanup-via-ui`, cancel)
          snapshot = await waitForAccount(scope.context, scope.page, `${label} cleanup`, (candidate) => (
            activeOrders(candidate).length === 0 ? candidate : false
          ))
        }
        assert(snapshot.trades.filter(({ orderId }) => orderId === protection.order.id).length <= 1)
        await assertRaceRoundDb(scope, snapshot.account.id, {
          label,
          orderIds: [protection.order.id, manualCloseOrder?.id].filter(Boolean),
          positionId: opened.position.id
        })
      }
    }

    if (required.has('desktop-close-liquidation')) {
      for (let round = 1; round <= 3; round += 1) {
        const label = `RES-02 close/liquidation round ${round}`
        const opened = await openPerp(scope, scope.page, {
          label,
          marginMode: 'ISOLATED',
          leverage: 100
        })
        await scope.context.ui.openTradePanel(peer.page, {
          product: 'perpetual', symbol: PERP, mobile: true
        })
        const liquidation = Number(opened.position.liquidationPrice)
        assert(Number.isFinite(liquidation) && liquidation > 0)
        const cursor = peer.page.p0Evidence.cursor
        const [marketResult, closeResult] = await Promise.allSettled([
          setMarketOverride(
            scope,
            PERP,
            liquidation * 0.97,
            liquidation * 0.98,
            `${label} trigger`
          ),
          scope.context.ui.positionActionViaUi(peer.page, {
            positionId: opened.position.id,
            positionSide: opened.position.positionSide,
            action: 'FULL_CLOSE'
          })
        ])
        const allowedLosers = allowExpectedRaceErrors(peer.page, {
          start: cursor,
          end: peer.page.p0Evidence.cursor,
          requests: [{ method: 'POST', url: /\/api\/trading\/positions\/[^/?]+\/close(?:\?|$)/u }],
          reason: `${label} loser`
        })
        assert.equal(marketResult.status, 'fulfilled', `${label} trigger fixture must succeed`)
        if (closeResult.status === 'fulfilled') {
          assert.equal(allowedLosers.length, 0, `${label} successful close has no allowed error`)
          scope.addMutation(`${slug(label)}-via-ui`, closeResult.value)
        } else {
          assert.equal(allowedLosers.length, 1, `${label} rejected close must be the exact race loser`)
        }
        snapshot = await waitForAccount(scope.context, scope.page, `${label} terminal`, (candidate) => (
          openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)
            ? false
            : candidate
        ), 45000)
        const manualCloseOrder = closeResult.status === 'fulfilled'
          ? exactOrderForCapture(opened.snapshot, snapshot, closeResult.value)
          : undefined
        if (closeResult.status === 'fulfilled') {
          assert(manualCloseOrder, `${label} successful manual close order identity`)
        }
        assert.equal(snapshot.positions.some(({ lots }) => Number(lots) < 0), false)
        const db = await assertRaceRoundDb(scope, snapshot.account.id, {
          label,
          orderIds: manualCloseOrder ? [manualCloseOrder.id] : [],
          positionId: opened.position.id
        })
        const history = snapshot.positionHistory.find(({ id }) => id === opened.position.id)
        if (history?.status === 'LIQUIDATED') {
          assertLiquidationLifecycleContract({
            positionId: opened.position.id,
            orders: db.orderRows,
            trades: db.tradeRows,
            ledger: db.cashLedgerRows
          })
        }
      }
    }
    return finish(scope, snapshot, {
      kind: 'CONCURRENT_TERMINAL_STATE',
      roundsPerRace: 3,
      totalRaces: required.size * 3,
      allowedOutcomes: ['FILLED', 'CANCELED', 'CLOSED', 'LIQUIDATED']
    })
  } finally {
    await closeMobileSession(peer, 'RES-02 peer')
  }
}

async function runRestartJourney(scope) {
  const required = subrunIds(scope)
  let snapshot
  if (required.has('desktop-restart-order-trigger')) {
    const pending = await pendingSpot(scope, scope.page, { label: 'RES-03 restart pending' })
    const oco = await pendingOco(scope, scope.page, { label: 'RES-03 restart OCO' })
    const opened = await openPerp(scope, scope.page, { label: 'RES-03 restart protection' })
    const mark = Number(opened.market.reference?.mark ?? opened.market.quote?.mid)
    const protection = await createStopLossProtectionViaUi(
      scope,
      scope.page,
      opened.position,
      mark * 0.5,
      'RES-03 restart protection'
    )
    const eventCursor = scope.context.events.snapshotFrames(scope.page).length
    const beforeRestart = await scope.context.db.snapshotTradingRows(opened.snapshot.account.id)
    await restartWithEvidence(scope, async () => {
      const updated = scalarResult(await scope.context.db.query(`
        UPDATE trading.orders
        SET trigger_price = ${Number(mark * 2).toFixed(10)}, version = version + 1,
          updated_at = now()
        WHERE id = '${sqlLiteral(protection.order.id)}'
          AND parent_position_id = '${sqlLiteral(opened.position.id)}'
          AND order_origin = 'PROTECTIVE'
          AND status IN ('ACCEPTED', 'PENDING', 'WORKING')
        RETURNING id;
      `))
      assert.equal(updated, protection.order.id, 'RES-03 protection crosses trigger during downtime')
      return {
        pendingOrderId: pending.order.id,
        ocoGroupId: oco.groupId,
        protectionOrderId: protection.order.id
      }
    })
    await reloadAuthenticatedPage(scope.page)
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-03 trigger catch-up', (candidate) => {
      const pendingRecovered = candidate.orders.some(({ id, status }) => (
        id === pending.order.id && ACTIVE_ORDER_STATUSES.has(status)
      ))
      const ocoRecovered = candidate.orders.filter(({ contingencyGroupId, status }) => (
        contingencyGroupId === oco.groupId && ACTIVE_ORDER_STATUSES.has(status)
      )).length === 2
      const protectionFilled = candidate.orders.some(({ id, status }) => (
        id === protection.order.id && status === 'FILLED'
      ))
      const positionClosed = !openPositions(candidate, PERP)
        .some(({ id }) => id === opened.position.id)
      return pendingRecovered && ocoRecovered && protectionFilled && positionClosed
        ? candidate
        : false
    }, 60000)
    const afterRestart = await scope.context.db.snapshotTradingRows(snapshot.account.id)
    const protectionTrades = afterRestart.tradeRows.filter(({ order_id: orderId }) => (
      orderId === protection.order.id
    ))
    assert.equal(protectionTrades.length, 1, 'RES-03 protection catch-up Trade cardinality')
    assert.equal(afterRestart.orderRows.find(({ id }) => id === protection.order.id)?.order_origin, 'PROTECTIVE')
    assert.equal(
      afterRestart.orderRows.filter(({ id }) => id === pending.order.id).length,
      beforeRestart.orderRows.filter(({ id }) => id === pending.order.id).length,
      'RES-03 pending order persistence'
    )
    const fillEvents = await waitForValue(() => {
      const matches = scope.context.events.snapshotFrames(scope.page)
        .slice(eventCursor)
        .filter(({ direction, eventType }) => direction === 'received' && eventType === 'ORDER_FILLED')
      return matches.length === 1 ? matches : false
    }, 'RES-03 one protection catch-up notification', 15000)
    scope.dbEvidence.push(beforeRestart, afterRestart)
    await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
    const cancel = await scope.context.ui.cancelAllOrdersViaUi(scope.page)
    scope.addMutation('res-03-recovered-pending-cancel-via-ui', cancel)
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-03 pending cleanup', (candidate) => (
      activeOrders(candidate).length === 0 ? candidate : false
    ))
  }
  if (required.has('desktop-restart-funding')) {
    snapshot = await localFundingSettlement(scope, { restart: true })
  }
  if (required.has('desktop-restart-liquidation')) {
    const opened = await openPerp(scope, scope.page, {
      label: 'RES-03 restart liquidation',
      marginMode: 'ISOLATED',
      leverage: 100
    })
    const eventCursor = scope.context.events.snapshotFrames(scope.page).length
    await restartWithEvidence(scope, async () => {
      const updated = scalarResult(await scope.context.db.query(`
        WITH target AS (
          SELECT id, account_id, margin_held
          FROM trading.positions
          WHERE id = '${sqlLiteral(opened.position.id)}'
            AND account_id = '${sqlLiteral(opened.snapshot.account.id)}'
            AND status = 'OPEN'
          FOR UPDATE
        ), updated_position AS (
          UPDATE trading.positions position_row
          SET margin_held = 0.00000001, version = version + 1, updated_at = now()
          FROM target
          WHERE position_row.id = target.id
          RETURNING position_row.id
        ), updated_account AS (
          UPDATE core.trading_accounts account_row
          SET used_margin = GREATEST(0, account_row.used_margin - target.margin_held + 0.00000001),
            free_margin = account_row.free_margin + target.margin_held - 0.00000001,
            updated_at = now()
          FROM target
          WHERE account_row.id = target.account_id
          RETURNING account_row.id
        )
        SELECT id FROM updated_position;
      `))
      assert.equal(updated, opened.position.id, 'RES-03 liquidation crosses risk boundary during downtime')
      return { positionId: opened.position.id, marginHeld: '0.00000001' }
    })
    await reloadAuthenticatedPage(scope.page)
    snapshot = await waitForAccount(scope.context, scope.page, 'RES-03 liquidation catch-up', (candidate) => {
      if (openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)) return false
      const history = candidate.positionHistory.find(({ id }) => id === opened.position.id)
      return history?.status === 'LIQUIDATED' ? candidate : false
    }, 60000)
    const db = await scope.context.db.snapshotTradingRows(snapshot.account.id)
    const lifecycle = assertLiquidationLifecycleContract({
      positionId: opened.position.id,
      orders: db.orderRows,
      trades: db.tradeRows,
      ledger: db.cashLedgerRows
    })
    const liquidationEvents = await waitForValue(() => {
      const matches = scope.context.events.snapshotFrames(scope.page)
        .slice(eventCursor)
        .filter(({ direction, eventType }) => direction === 'received' && eventType === 'LIQUIDATION')
      return matches.length === 1 ? matches : false
    }, 'RES-03 one liquidation catch-up notification', 15000)
    scope.dbEvidence.push(db)
    scope.oracleEvidence.push({
      kind: 'RESTART_LIQUIDATION_CATCH_UP',
      positionId: opened.position.id,
      orderId: lifecycle.order.id,
      tradeId: lifecycle.trade.id,
      ledgerId: lifecycle.ledger.id,
      events: liquidationEvents.length
    })
  }
  return finish(scope, snapshot, {
    kind: 'BACKEND_RESTART_RECOVERY',
    restartBackend: true,
    duringDowntime: true,
    assertOwnedPorts: true
  })
}

async function runRecoveryJourney(scope) {
  await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
  let outageObserved = false
  let uncertainClientOrderId
  await scope.context.services.restartBackend({
    duringDowntime: async () => {
      const cursor = scope.page.p0Evidence.cursor
      try {
        await scope.context.ui.submitOrderViaUi(scope.page, {
          side: 'BUY', orderType: 'MARKET', amount: '10'
        })
      } catch {
        outageObserved = true
      }
      assert.equal(outageObserved, true, 'backend outage must not show a false order success')
      assert.equal(
        scope.page.p0Evidence.requests.slice().some((request) => (
          request.cursor > cursor && /\/api\/trading\/orders$/u.test(request.url)
        )),
        true,
        'backend outage attempt must remain browser-network evidence'
      )
      const uncertain = scope.page.p0Evidence.requests.find((request) => (
        request.cursor > cursor
          && request.method === 'POST'
          && /\/api\/trading\/orders$/u.test(request.url)
      ))
      assert(uncertain?.postData, 'outage request body is required for reconciliation')
      uncertainClientOrderId = JSON.parse(uncertain.postData).clientOrderId
      assert(uncertainClientOrderId, 'outage request must carry clientOrderId')
      const outageUi = await observeBackendOutageUi(scope.page)
      scope.contractProbes.push({
        kind: 'BACKEND_OUTAGE_UI',
        requestRef: uncertain.requestId,
        statusText: outageUi.statusText,
        accountTab: outageUi.tabLabel
      })
    }
  })
  await scope.context.services.assertOwnedPorts({ ports: [18086, 5199, 5200] })
  await reloadAuthenticatedPage(scope.page)
  scope.contractProbes.push({
    kind: 'BACKEND_OUTAGE_RECOVERY',
    action: 'reload'
  })

  let reconciled = await scope.context.api.snapshotAccount(scope.page)
  const uncertainMatches = reconciled.orders.filter(({ clientOrderId }) => (
    clientOrderId === uncertainClientOrderId
  ))
  assert(uncertainMatches.length <= 1, 'uncertain clientOrderId resolves to at most one order')
  if (uncertainMatches.some(({ status }) => ACTIVE_ORDER_STATUSES.has(status))) {
    await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
    const cancel = await scope.context.ui.cancelAllOrdersViaUi(scope.page)
    scope.addMutation('res-04-uncertain-order-reconcile-cancel-via-ui', cancel)
    reconciled = await waitForAccount(scope.context, scope.page, 'RES-04 uncertain cancel', (candidate) => (
      candidate.orders.filter(({ clientOrderId }) => clientOrderId === uncertainClientOrderId)
        .every(({ status }) => !ACTIVE_ORDER_STATUSES.has(status)) ? candidate : false
    ))
  }
  if (findWallet(reconciled, 'SPOT', 'BTC', false)?.available > 0) {
    reconciled = (await closeSpot(scope, scope.page, reconciled, 'RES-04 uncertain fill cleanup')).snapshot
  }

  const pending = await pendingSpot(scope, scope.page, { label: 'RES-04 stale pending' })
  const rejectedBaseline = pending.snapshot
  const staleDbBefore = await scope.context.db.snapshotTradingRows(rejectedBaseline.account.id)
  const bindingErrorCursorStart = scope.page.p0Evidence.cursor
  const fixture = await useProviderBindings(scope, {
    [SPOT]: []
  }, 'RES-04 all Spot providers unavailable')
  const staleLimit = Number(pending.order.price)
  const staleOverride = await setMarketOverrideBlind(
    scope,
    SPOT,
    alignedPrice(pending.market, staleLimit * 0.98),
    alignedPrice(pending.market, staleLimit * 0.99),
    'RES-04 stale target crossed'
  )
  const rejected = await scope.context.ui.submitOrderViaUi(scope.page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '10',
    expectFailure: true,
    reason: 'expected stale/unavailable market rejection'
  })
  scope.addMutation('res-04-stale-market-rejection-via-ui', rejected)
  assert(
    ['MARKET_DATA_STALE', 'MARKET_DATA_UNAVAILABLE', 'MARKET_PROVIDER_BINDING_NOT_FOUND']
      .includes(responseCode(rejected)),
    `RES-04 expected stale/unavailable code, got ${responseCode(rejected)}`
  )
  await delay(1200)
  const staleDbAfter = await scope.context.db.snapshotTradingRows(rejectedBaseline.account.id)
  assertStaleTradingStateUnchanged(staleDbBefore, staleDbAfter)
  assertStalePendingDbContract(staleDbAfter, pending.order.id)
  scope.dbEvidence.push(staleDbBefore, staleDbAfter)
  scope.oracleEvidence.push({
    kind: 'STALE_PENDING_TARGET_CROSSED_WITHOUT_TRADE',
    orderId: pending.order.id,
    bid: staleOverride.bid,
    ask: staleOverride.ask
  })
  await staleOverride.restore()
  await fixture.restore()
  const bindingErrorCursorEnd = scope.page.p0Evidence.cursor
  const apiBaseUrl = scope.page.p0Options.apiBaseUrl
  const accountId = rejectedBaseline.account.id
  await allowExpectedBatchBindingErrors(scope.page, new Set([
    `${apiBaseUrl}/api/market/quotes/${SPOT}`,
    `${apiBaseUrl}/api/market/order-book/${SPOT}`,
    `${apiBaseUrl}/api/market/trades/${SPOT}?limit=40`,
    `${apiBaseUrl}/api/accounts/${accountId}/summary`,
    `${apiBaseUrl}/api/trading/positions?accountId=${accountId}&page=0&size=100`
  ]), bindingErrorCursorStart, bindingErrorCursorEnd)
  const rejectedState = await scope.context.api.snapshotAccount(scope.page)
  assert.equal(recordsAfter(rejectedBaseline, rejectedState, 'trades').length, 0)
  assert(
    activeOrders(rejectedState, SPOT).some(({ id }) => id === pending.order.id),
    'stale pending order must not trigger from an unavailable bundle'
  )
  await authorityBundle(scope, SPOT, ['binance', 'okx', 'local-spot'], undefined, 60000)
  const limit = Number(pending.order.price)
  await setMarketOverride(scope, SPOT, limit * 0.98, limit * 0.99, 'RES-04 fresh pending recovery')
  const triggered = await waitForAccount(scope.context, scope.page, 'RES-04 pending triggers once', (candidate) => {
    const order = candidate.orders.find(({ id }) => id === pending.order.id)
    const trades = candidate.trades.filter(({ orderId }) => orderId === pending.order.id)
    return order?.status === 'FILLED' && trades.length === 1 ? candidate : false
  }, 45000)
  const pendingCleanup = await closeSpot(scope, scope.page, triggered, 'RES-04 pending recovery cleanup')
  const bought = await filledSpotBuy(scope, scope.page, 'RES-04 recovered order')
  const sold = await closeSpot(scope, scope.page, bought.snapshot, 'RES-04 recovered cleanup')
  return finish(scope, sold.snapshot, {
    kind: 'API_MARKET_RECOVERY',
    outageObserved,
    uncertainClientOrderId,
    uncertainResultCount: uncertainMatches.length,
    staleCode: responseCode(rejected),
    recoveredPendingOrderId: pending.order.id,
    recoveredPendingCleanupOrderId: pendingCleanup.order.id,
    recoveredOrderId: bought.order.id
  })
}

async function observeBackendOutageUi(page) {
  const state = await waitForValue(() => page.evaluate(() => {
    const visible = (element) => {
      const rect = element?.getBoundingClientRect()
      const style = element ? getComputedStyle(element) : undefined
      return Boolean(rect?.width && rect.height)
        && style?.display !== 'none'
        && style?.visibility !== 'hidden'
    }
    const status = [...document.querySelectorAll('[role="status"]')]
      .find((candidate) => visible(candidate)
        && /(?:Backend order failed|后端下单失败|バックエンド注文失敗)/u
          .test(candidate.textContent ?? ''))
    const panel = [...document.querySelectorAll('section[aria-label]')]
      .find((candidate) => candidate.querySelector(':scope > [role="tablist"]')
        && candidate.querySelector(':scope > [role="tabpanel"]'))
    const tabs = [...(panel?.querySelectorAll(':scope > [role="tablist"] > [role="tab"]') ?? [])]
    const tab = tabs[1]
    if (!status || !(tab instanceof HTMLButtonElement)) return false
    if (tab.getAttribute('aria-selected') !== 'true') {
      tab.click()
      return false
    }
    return {
      statusText: status.textContent?.replace(/\s+/gu, ' ').trim() ?? '',
      tabLabel: tab.textContent?.replace(/\s+/gu, ' ').trim() ?? '',
      tabSelected: true
    }
  }), 'RES-04 visible backend failure and account tab', 5000)
  return assertBackendOutageUiState(state)
}

async function runViewportParityJourney(scope) {
  const required = subrunIds(scope)
  const mobile = await openMobileSession(scope, 'ui-01')
  const evidence = []
  let snapshot
  try {
    for (const viewport of [
      { name: 'desktop', page: scope.page, mobile: false },
      { name: 'mobile', page: mobile.page, mobile: true }
    ]) {
      if (!required.has(`${viewport.name}-core`)) continue
      if (viewport.mobile) await assertMobileMarketDrawerKeyboard(viewport.page)
      const spot = await spotRoundTrip(scope, viewport.page, {
        label: `UI-01 ${viewport.name}`,
        mobile: viewport.mobile
      })
      await pendingSpotCancel(scope, viewport.page, {
        label: `UI-01 ${viewport.name} pending`,
        mobile: viewport.mobile
      })
      const perp = await perpRoundTrip(scope, viewport.page, {
        label: `UI-01 ${viewport.name}`,
        partial: true,
        leverage: 50,
        mobile: viewport.mobile
      })
      snapshot = perp.snapshot
      evidence.push({
        viewport: viewport.name,
        spotStatus: spot.order.status,
        perpPositionId: perp.positionId,
        partialRatio: perp.partialRatio,
        finalOpenPositions: openPositions(snapshot).length
      })
      await assertViewportGeometry(viewport.page, `UI-01 ${viewport.name} core`)
      await multiPageCheckpoint(
        scope,
        `ui-01-${viewport.name}-core`,
        [scope.page, mobile.page]
      )
    }

    for (const viewport of [
      { name: 'desktop', page: scope.page, mobile: false },
      { name: 'mobile', page: mobile.page, mobile: true }
    ]) {
      if (!required.has(`${viewport.name}-target`)) continue
      const oco = await pendingOcoCancel(scope, viewport.page, {
        label: `UI-01 ${viewport.name} OCO`,
        mobile: viewport.mobile
      })
      snapshot = oco.snapshot
      evidence.push({
        viewport: viewport.name,
        target: true,
        contingencyGroupId: oco.groupId,
        legCount: oco.legs.length
      })
      await assertViewportGeometry(viewport.page, `UI-01 ${viewport.name} target`)
    }
    snapshot ??= await scope.context.api.snapshotAccount(scope.page)
    assert.equal(activeOrders(snapshot).length, 0)
    assert.equal(openPositions(snapshot).length, 0)
    for (const page of [scope.page, mobile.page]) {
      await inspectHistoryRoutes(scope, page, snapshot)
      await inspectTerminalCurrentSymbolFilter(scope, page, snapshot)
    }
    scope.oracleEvidence.push({
      kind: 'VIEWPORT_BUSINESS_PARITY',
      viewports: { desktop: DESKTOP, mobile: MOBILE },
      evidence
    })
    return finish(scope, snapshot, {
      kind: 'DESKTOP_MOBILE_PARITY',
      viewportCount: evidence.length
    })
  } finally {
    await closeMobileSession(mobile, 'UI-01 mobile')
  }
}

async function runHistoryAccessibilityJourney(scope) {
  const required = subrunIds(scope)
  const mobile = await openMobileSession(scope, 'ui-02')
  let snapshot
  try {
    const profile = [...required][0]?.includes('order-trigger')
      ? 'ORDER_TRIGGER'
      : 'UI_CORE'
    await assertMobileMarketDrawerKeyboard(mobile.page)
    await assertMobileDialogKeyboard(scope, mobile.page)
    await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
    await scope.context.ui.openTradePanel(mobile.page, {
      product: 'spot', symbol: SPOT, mobile: true
    })
    if (profile === 'ORDER_TRIGGER') {
      const pending = await pendingSpot(scope, mobile.page, {
        label: 'UI-02 realtime pending',
        mobile: true
      })
      await scope.page.navigate(`${scope.page.p0Options.webBaseUrl}/orders`)
      await assertRoute(scope.page, '/orders')
      await revealRouteRecord(scope.page, pending.order.id)
      const eventCursor = scope.context.events.snapshotFrames(scope.page).length
      const networkCursor = scope.page.p0Evidence.cursor
      const changedAt = Date.now()
      const cancel = await scope.context.ui.cancelAllOrdersViaUi(mobile.page)
      scope.addMutation('ui-02-mobile-realtime-cancel-via-ui', cancel)
      snapshot = await waitForAccount(scope.context, scope.page, 'UI-02 realtime cancel', (candidate) => {
        const order = candidate.orders.find(({ id }) => id === pending.order.id)
        return order?.status === 'CANCELED' ? candidate : false
      })
      await waitUntil(() => scope.page.evaluate((orderId) => {
        const row = document.querySelector(`[data-order-id="${orderId}"]`)
        return row?.getAttribute('data-order-status') === 'CANCELED'
      }, pending.order.id), 'UI-02 event-driven canceled row', 5000)
      await assertRealtimeAndPolling(scope, scope.page, {
        eventCursor,
        networkCursor,
        changedAt,
        eventTypes: ['ORDER_CANCELED'],
        label: 'UI-02 cancel'
      })
    } else {
      const before = await scope.context.api.snapshotAccount(scope.page)
      await scope.page.navigate(`${scope.page.p0Options.webBaseUrl}/orders`)
      await assertRoute(scope.page, '/orders')
      const eventCursor = scope.context.events.snapshotFrames(scope.page).length
      const networkCursor = scope.page.p0Evidence.cursor
      const changedAt = Date.now()
      const bought = await filledSpotBuy(scope, mobile.page, 'UI-02 realtime fill', { mobile: true })
      await revealRouteRecord(scope.page, bought.order.id)
      await assertRealtimeAndPolling(scope, scope.page, {
        eventCursor,
        networkCursor,
        changedAt,
        eventTypes: ['ORDER_FILLED', 'TRADE_CREATED'],
        label: 'UI-02 fill'
      })
      snapshot = await closeSpot(scope, mobile.page, bought.snapshot, 'UI-02 realtime cleanup')
        .then(({ snapshot: current }) => current)
      assert.equal(recordsAfter(before, snapshot, 'trades').length, 2)
    }

    const perp = await perpRoundTrip(scope, mobile.page, {
      label: 'UI-02 Perp history',
      leverage: 50,
      mobile: true
    })
    snapshot = perp.snapshot
    requirePositionHistoryTarget(snapshot, 'UI-02')

    for (const page of [scope.page, mobile.page]) {
      await inspectHistoryRoutes(scope, page, snapshot)
      await inspectTerminalCurrentSymbolFilter(scope, page, snapshot)
      await assertViewportGeometry(page, 'UI-02 history')
    }
    await multiPageCheckpoint(scope, 'ui-02-history-filters', [scope.page, mobile.page])
    scope.oracleEvidence.push({
      kind: 'HISTORY_FILTER_REALTIME_ACCESSIBILITY',
      symbol: SPOT,
      status: 'PASS',
      filters: ['symbol', 'status', 'from', 'to', 'current-symbol'],
      dialog: {
        selector: 'section[role="dialog"][aria-modal="true"]',
        closeKey: 'Escape',
        focusRestored: true
      },
      mergeWindowMs: '100-300'
    })
    return finish(scope, snapshot, {
      kind: 'UI_HISTORY_ACCESSIBILITY',
      profile,
      routes: ['/orders', '/positions', '/wallet']
    })
  } finally {
    await closeMobileSession(mobile, 'UI-02 mobile')
  }
}

async function assertRealtimeAndPolling(
  scope,
  page,
  { eventCursor, networkCursor, changedAt, eventTypes, label }
) {
  const event = await waitForValue(() => scope.context.events.snapshotFrames(page)
    .slice(eventCursor)
    .find(({ direction, eventType }) => (
      direction === 'received' && eventTypes.includes(eventType)
    )), `${label} STOMP event`, 5000)
  const visibleAfterMs = Date.now() - changedAt
  assert(visibleAfterMs <= 5000, `${label} realtime visible within 5s`)
  const eventRefreshes = page.p0Evidence.requests.filter((request) => (
    request.cursor > networkCursor && request.method === 'GET'
      && /\/api\/accounts\/[^/?]+\/summary(?:\?|$)/u.test(request.url)
  ))
  assert(eventRefreshes.length >= 1, `${label} event refresh request`)
  const broker = new URL(page.p0Options.apiBaseUrl)
  broker.protocol = broker.protocol === 'https:' ? 'wss:' : 'ws:'
  broker.pathname = '/ws'
  broker.search = ''
  broker.hash = ''
  const stompCursor = page.p0Evidence.stompFrames.length
  let polling
  await page.send('Network.setBlockedURLs', { urls: [broker.toString()] })
  try {
    const reloadCursor = page.p0Evidence.cursor
    await reloadAuthenticatedPage(page)
    await waitForValue(() => page.p0Evidence.requests.find((request) => (
      request.cursor > reloadCursor
        && request.loadingFinished
        && request.method === 'GET'
        && /\/api\/accounts\/[^/?]+\/summary(?:\?|$)/u.test(request.url)
    )), `${label} disconnected-STOMP bootstrap summary`, 10000)
    await delay(1000)
    const pollCursor = page.p0Evidence.cursor
    const pollingStartedAt = Date.now()
    polling = await waitForValue(() => {
      const requests = page.p0Evidence.requests.filter((request) => (
        request.cursor > pollCursor && request.method === 'GET'
          && /\/api\/accounts\/[^/?]+\/summary(?:\?|$)/u.test(request.url)
      ))
      return requests.length >= 1 ? requests : false
    }, `${label} disconnected-STOMP 15s polling fallback`, 18000)
    const pollingObservedAt = Date.now()
    await delay(1500)
    polling = page.p0Evidence.requests.filter((request) => (
      request.cursor > pollCursor && request.method === 'GET'
        && /\/api\/accounts\/[^/?]+\/summary(?:\?|$)/u.test(request.url)
    ))
    assertPollingFallbackWindow({
      baselineAtMs: pollingStartedAt,
      observedAtMs: pollingObservedAt,
      requests: polling,
      label
    })
    const frames = page.p0Evidence.stompFrames.slice(stompCursor)
    assert.equal(
      frames.some(({ direction, command }) => (
        direction === 'sent' && command === 'SUBSCRIBE'
      )),
      false,
      `${label} STOMP must remain disconnected during polling proof`
    )
  } finally {
    await page.send('Network.setBlockedURLs', { urls: [] })
    await reloadAuthenticatedPage(page)
  }
  scope.contractProbes.push({
    kind: 'UI_REALTIME_AND_POLLING',
    label,
    eventType: event.eventType,
    visibleAfterMs,
    eventRefreshes: eventRefreshes.length,
    pollingRefreshes: polling.length,
    stompDisconnected: true
  })
}

async function assertMobileMarketDrawerKeyboard(page) {
  await page.evaluate(() => {
    const button = document.querySelector('[data-platform-view="mobile"] header button')
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_MOBILE_MARKET_DRAWER_TRIGGER_MISSING')
    }
    button.focus()
    button.click()
  })
  await page.waitForFunction(() => {
    const close = document.querySelector('[role="dialog"] button[aria-label="Close Markets"]')
    const dialog = close?.closest('[role="dialog"]')
    return dialog?.getAttribute('aria-modal') === 'true'
      && Boolean(dialog.getAttribute('aria-label') || dialog.getAttribute('aria-labelledby'))
      && dialog.contains(document.activeElement)
  }, 'mobile Markets drawer semantics and focus')
  await page.send('Input.dispatchKeyEvent', {
    type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27
  })
  await page.send('Input.dispatchKeyEvent', {
    type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27
  })
  await page.waitForFunction(() => !document.querySelector(
    '[role="dialog"] button[aria-label="Close Markets"]'
  ), 'mobile Markets drawer Escape close')
}

async function assertViewportGeometry(page, label) {
  const geometry = await page.evaluate(() => {
    const visible = (element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden'
    }
    const controls = [...document.querySelectorAll('button, input, select')].filter(visible)
    const unreachable = controls.filter((element) => {
      const rect = element.getBoundingClientRect()
      return rect.right <= 0 || rect.left >= innerWidth
    }).length
    return {
      viewportWidth: innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      unreachable
    }
  })
  assert(
    geometry.scrollWidth <= geometry.viewportWidth + 1,
    `${label} must not overflow horizontally: ${JSON.stringify(geometry)}`
  )
  assert.equal(geometry.unreachable, 0, `${label} visible controls must be reachable`)
  return geometry
}

async function assertMobileDialogKeyboard(scope, page) {
  await scope.context.ui.openTradePanel(page, {
    product: 'spot', symbol: SPOT, mobile: true
  })
  const openState = await page.evaluate(() => {
    const dialog = [...document.querySelectorAll('section[role="dialog"]')]
      .find((candidate) => candidate.querySelector('[data-trading-action="submit-order"]'))
    return {
      ariaModal: dialog?.getAttribute('aria-modal'),
      labelled: Boolean(dialog?.getAttribute('aria-label') || dialog?.getAttribute('aria-labelledby')),
      focusInside: Boolean(dialog?.contains(document.activeElement))
    }
  })
  assert.deepEqual(openState, { ariaModal: 'true', labelled: true, focusInside: true })
  await page.send('Input.dispatchKeyEvent', {
    type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27
  })
  await page.send('Input.dispatchKeyEvent', {
    type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27
  })
  await page.waitForFunction(() => {
    const dialog = [...document.querySelectorAll('section[role="dialog"]')]
      .find((candidate) => candidate.querySelector('[data-trading-action="submit-order"]'))
    return !dialog
      && document.activeElement?.getAttribute('data-testid') === 'mobile-trade-action'
  }, 'mobile order sheet Escape closes and restores activeElement focus')
}

async function inspectHistoryRoutes(scope, page, snapshot) {
  await page.navigate(`${page.p0Options.webBaseUrl}/orders`)
  await assertRoute(page, '/orders')
  const orderFilters = await page.evaluate(() => {
    const candidates = [...document.querySelectorAll('[aria-label]')]
    const toolbar = candidates.find((candidate) => (
      candidate.querySelectorAll('input').length >= 3
        && candidate.querySelector('select')
    ))
    const inputs = [...(toolbar?.querySelectorAll('input') ?? [])]
    const select = toolbar?.querySelector('select')
    return {
      symbol: inputs.some(({ type }) => type !== 'date'),
      status: Boolean(select),
      from: inputs.filter(({ type }) => type === 'date').length >= 1,
      to: inputs.filter(({ type }) => type === 'date').length >= 2
    }
  })
  assert.deepEqual(orderFilters, { symbol: true, status: true, from: true, to: true })
  const targetOrder = [...snapshot.orders].reverse().find(({ status }) => (
    !ACTIVE_ORDER_STATUSES.has(status)
  ))
  if (targetOrder) {
    await page.evaluate((target) => {
      const tablist = [...document.querySelectorAll('[role="tablist"]')]
        .find((candidate) => candidate.closest('section')?.querySelector('[aria-label] input[type="date"]'))
      const history = tablist?.querySelectorAll(':scope > button')[1]
      if (!(history instanceof HTMLButtonElement)) throw new Error('P0_ORDER_HISTORY_TAB_MISSING')
      history.click()
      const toolbar = [...document.querySelectorAll('[aria-label]')]
        .find((candidate) => candidate.querySelectorAll('input').length >= 3
          && candidate.querySelector('select'))
      const symbol = [...(toolbar?.querySelectorAll('input') ?? [])]
        .find((input) => input.type !== 'date')
      const dates = [...(toolbar?.querySelectorAll('input[type="date"]') ?? [])]
      const status = toolbar?.querySelector('select')
      const setValue = (control, value) => {
        const prototype = control instanceof HTMLSelectElement
          ? HTMLSelectElement.prototype
          : HTMLInputElement.prototype
        Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(control, value)
        control.dispatchEvent(new Event('input', { bubbles: true }))
        control.dispatchEvent(new Event('change', { bubbles: true }))
      }
      if (!symbol || dates.length !== 2 || !status) throw new Error('P0_ORDER_FILTER_CONTROLS_MISSING')
      setValue(symbol, target.symbol)
      setValue(status, target.status)
      setValue(dates[0], target.day)
      setValue(dates[1], target.day)
    }, {
      symbol: targetOrder.symbol,
      status: targetOrder.status,
      day: targetOrder.createdAt.slice(0, 10)
    })
    const visibleIds = await waitForValue(() => page.evaluate((targetId) => {
      const ids = [...document.querySelectorAll('[data-order-id]')]
        .map((element) => element.getAttribute('data-order-id'))
        .filter(Boolean)
      return ids.includes(targetId) ? ids : false
    }, targetOrder.id), 'orders route filtered result', 5000)
    const expectedIds = snapshot.orders.filter((order) => (
      order.symbol === targetOrder.symbol
        && order.status === targetOrder.status
        && order.createdAt.slice(0, 10) === targetOrder.createdAt.slice(0, 10)
    )).map(({ id }) => id)
    assert(expectedIds.length <= 10, 'filtered orders must fit one route page')
    assertFilteredRecordIds({
      visibleIds,
      expectedIds,
      foreignIds: snapshot.orders.filter(({ id }) => !expectedIds.includes(id)).map(({ id }) => id),
      label: 'orders route filters'
    })
  }

  await page.navigate(`${page.p0Options.webBaseUrl}/positions`)
  await assertRoute(page, '/positions')
  const positionFilter = await page.evaluate(() => [...document.querySelectorAll('[aria-label]')]
    .some((candidate) => candidate.querySelector('input[placeholder]')))
  assert.equal(positionFilter, true, 'positions route must expose its real symbol filter')
  const targetPosition = requirePositionHistoryTarget(snapshot, 'positions route filters')
  {
    const history = true
    const expectedRows = snapshot.positionHistory.filter(({ symbol }) => symbol === targetPosition.symbol)
      .map(positionRowView)
    assert(expectedRows.length > 0 && expectedRows.length <= 10, 'filtered positions must fit one route page')
    await page.evaluate((target) => {
      const tablist = [...document.querySelectorAll('[role="tablist"]')]
        .find((candidate) => candidate.closest('section')?.querySelector('input[placeholder]'))
      const tab = tablist?.querySelectorAll(':scope > button')[target.history ? 1 : 0]
      const input = tablist?.closest('section')?.querySelector('input[placeholder]')
      if (!(tab instanceof HTMLButtonElement) || !(input instanceof HTMLInputElement)) {
        throw new Error('P0_POSITION_FILTER_CONTROLS_MISSING')
      }
      tab.click()
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
        ?.call(input, target.symbol)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    }, { symbol: targetPosition.symbol, history })
    const visibleRows = await waitForValue(() => page.evaluate((expectedCount) => {
      const toolbar = [...document.querySelectorAll('[aria-label]')]
        .find((candidate) => candidate.querySelector('input[placeholder]'))
      const root = toolbar?.closest('section')
      const records = root?.querySelector('table')
        ? [...root.querySelectorAll('tbody > tr')]
        : [...(root?.querySelectorAll('[role="list"] > [role="listitem"]') ?? [])]
      const text = (element) => element?.textContent?.replace(/\s+/gu, ' ').trim() ?? ''
      const result = records.map((record) => {
        const cells = record.matches('tr')
          ? [...record.querySelectorAll(':scope > td')]
          : [...record.children].map((cell) => cell.children[1]).filter(Boolean)
        if (cells.length < 12) return null
        return {
          symbol: text(cells[0].querySelector('strong')),
          side: text(cells[1]),
          lots: text(cells[2]),
          openPrice: text(cells[3]),
          realizedPnl: text(cells[6]),
          marginHeld: text(cells[7]),
          status: text(cells[11])
        }
      }).filter(Boolean)
      return result.length === expectedCount ? result : false
    }, expectedRows.length), 'positions route filtered rows', 5000)
    assertPositionRowViews(visibleRows, expectedRows, 'positions route filters')
  }

  await page.navigate(`${page.p0Options.webBaseUrl}/wallet`)
  await assertRoute(page, '/wallet')
  const walletFilters = await page.evaluate(() => ({
    type: document.querySelectorAll('select').length >= 1,
    wallet: document.body.innerText.includes('Wallet'),
    currency: document.body.innerText.includes('Asset'),
    date: document.querySelectorAll('input[type="date"]').length >= 2
  }))
  assert.equal(Object.values(walletFilters).every(Boolean), true)
  const ledger = await scope.context.api.user(
    page,
    `/api/ledger?accountId=${encodeURIComponent(snapshot.account.id)}`
  )
  const targetLedger = ledger.at(-1)
  if (targetLedger) {
    const day = targetLedger.createdAt.slice(0, 10)
    await page.evaluate((target) => {
      const toolbar = document.querySelector('#wallet-ledger [aria-label]')
      const selects = [...(toolbar?.querySelectorAll('select') ?? [])]
      const dates = [...(toolbar?.querySelectorAll('input[type="date"]') ?? [])]
      if (selects.length !== 3 || dates.length !== 2) {
        throw new Error('P0_WALLET_LEDGER_FILTER_CONTROLS_MISSING')
      }
      const values = [target.entryType, target.walletType, target.currency]
      const setValue = (control, value) => {
        const prototype = control instanceof HTMLSelectElement
          ? HTMLSelectElement.prototype
          : HTMLInputElement.prototype
        Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(control, value)
        control.dispatchEvent(new Event('input', { bubbles: true }))
        control.dispatchEvent(new Event('change', { bubbles: true }))
      }
      selects.forEach((select, index) => setValue(select, values[index]))
      dates.forEach((input) => setValue(input, target.day))
    }, {
      entryType: targetLedger.entryType,
      walletType: targetLedger.walletType,
      currency: targetLedger.currency,
      day
    })
    const expected = ledger.filter((entry) => (
      entry.entryType === targetLedger.entryType
        && entry.walletType === targetLedger.walletType
        && entry.currency === targetLedger.currency
        && entry.createdAt.slice(0, 10) === day
    ))
    const rowTexts = await waitForValue(() => page.evaluate(() => {
      const rows = [...document.querySelectorAll(
        '#wallet-ledger tbody tr, #wallet-ledger [role="listitem"]'
      )].filter((row) => row.textContent?.trim())
      return rows.length > 0 ? rows.map((row) => row.textContent.trim()) : false
    }), 'wallet ledger filtered result', 5000)
    const visibleIds = matchLedgerRowIds(rowTexts, expected)
    assertFilteredRecordIds({
      visibleIds,
      expectedIds: expected.map(({ id }) => id),
      foreignIds: ledger.filter(({ id }) => !expected.some((entry) => entry.id === id))
        .map(({ id }) => id),
      label: 'wallet ledger filters'
    })
    assert(visibleIds.includes(targetLedger.id), 'wallet ledger target row visible')
  }
}

function positionRowView(position) {
  const display = (value) => (
    value === null || value === undefined || value === '' ? '-' : String(value)
  )
  return {
    symbol: position.symbol,
    side: display(position.side),
    lots: display(position.lots),
    openPrice: display(position.openPrice),
    realizedPnl: display(position.realizedPnl),
    marginHeld: display(position.marginHeld),
    status: display(position.status)
  }
}

async function inspectTerminalCurrentSymbolFilter(scope, page, snapshot) {
  const target = requirePositionHistoryTarget(snapshot, 'terminal current-symbol filter')
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: target.symbol,
    mobile: page === scope.page ? false : true
  })
  const filtered = await page.evaluate((history) => {
    const panels = [...document.querySelectorAll('section[aria-label]')]
      .filter((candidate) => candidate.querySelector('[role="tabpanel"]'))
    const panel = panels.find((candidate) => candidate.querySelector('input[type="checkbox"]'))
    if (history) {
      const tab = panel?.querySelectorAll('[role="tablist"] > [role="tab"]')[3]
      if (!(tab instanceof HTMLButtonElement)) throw new Error('P0_TERMINAL_HISTORY_TAB_MISSING')
      tab.click()
    }
    const checkbox = panel?.querySelector('input[type="checkbox"]')
    if (!(checkbox instanceof HTMLInputElement) || checkbox.disabled) return false
    checkbox.click()
    return checkbox.checked
  }, true)
  assert.equal(filtered, true, 'terminal current-symbol checkbox must be operable')
  {
    const visibleIds = await waitForValue(() => page.evaluate((targetId) => {
      const ids = [...document.querySelectorAll('[role="tabpanel"] [data-position-id]')]
        .map((element) => element.getAttribute('data-position-id'))
        .filter(Boolean)
      return ids.includes(targetId) ? ids : false
    }, target.id), 'terminal current-symbol filtered positions', 5000)
    const expectedIds = snapshot.positionHistory.filter(({ symbol }) => symbol === target.symbol)
      .map(({ id }) => id)
    assertFilteredRecordIds({
      visibleIds,
      expectedIds,
      foreignIds: snapshot.positionHistory
        .filter(({ id }) => !expectedIds.includes(id)).map(({ id }) => id),
      label: 'terminal current-symbol filter'
    })
  }
}

async function externalAuthorityBundle(scope, symbol, provider, sourceMode, timeoutMs = 30000) {
  try {
    return await authorityBundle(scope, symbol, provider, sourceMode, timeoutMs)
  } catch (error) {
    const directFailure = publicProviderProbeFailure(provider, { error })
    if (directFailure) {
      throw new P0PublicProviderUnavailableError(provider, [directFailure])
    }
    let quote
    try {
      quote = await scope.context.api.user(
        scope.page,
        `/api/market/quotes/${encodeURIComponent(symbol)}`,
        { timeoutMs: Math.min(timeoutMs, 5000) }
      )
    } catch (probeError) {
      const probeFailure = publicProviderProbeFailure(provider, { error: probeError })
      if (probeFailure) {
        throw new P0PublicProviderUnavailableError(provider, [probeFailure])
      }
      throw error
    }
    const fallbackFailure = publicProviderProbeFailure(provider, { quote })
    if (!fallbackFailure) throw error
    throw new P0PublicProviderUnavailableError(provider, [fallbackFailure])
  }
}

export function publicProviderProbeFailure(provider, { error, quote } = {}) {
  if (quote?.providerCode && quote.providerCode !== provider) {
    return {
      provider,
      status: 200,
      code: 'FALLBACK_SELECTED',
      asOf: quote.asOf ?? null
    }
  }
  const seen = new Set()
  for (let current = error; current && !seen.has(current); current = current.cause) {
    seen.add(current)
    const status = Number(current.status)
    const code = current.code
    if (Number.isInteger(status) && PUBLIC_PROVIDER_UNAVAILABLE_CODES.has(code)) {
      return { provider, status, code, asOf: null }
    }
  }
  return null
}

async function authorityBundle(
  scope,
  symbol,
  expectedProvider,
  expectedSourceMode,
  timeoutMs = 30000
) {
  const providers = Array.isArray(expectedProvider) ? expectedProvider : [expectedProvider]
  const bundle = await waitForValue(async () => {
    const encoded = encodeURIComponent(symbol)
    const candleTo = new Date()
    const candleFrom = new Date(candleTo.getTime() - 30 * 60 * 1000)
    const candleQuery = new URLSearchParams({
      symbol,
      timeframe: '1m',
      from: candleFrom.toISOString(),
      to: candleTo.toISOString()
    })
    const [quote, depth, trades, reference, candles] = await Promise.all([
      scope.context.api.user(scope.page, `/api/market/quotes/${encoded}`),
      scope.context.api.user(scope.page, `/api/market/order-book/${encoded}`),
      scope.context.api.user(scope.page, `/api/market/trades/${encoded}`),
      symbol.endsWith('-PERP')
        ? scope.context.api.user(
            scope.page,
            `/api/market/perpetuals/${encoded}/reference`
          )
        : undefined,
      scope.context.api.user(scope.page, `/api/chart/candles?${candleQuery}`)
    ])
    if (!providers.includes(quote.providerCode)) return false
    if (expectedSourceMode !== undefined && quote.sourceMode !== expectedSourceMode) return false
    return assertAuthorityBundleContract({ quote, depth, trades, reference, candles }, {
      symbol,
      providers,
      sourceMode: expectedSourceMode
    })
  }, `${symbol} authority bundle`, timeoutMs)
  const product = symbol.endsWith('-PERP') ? 'perpetual' : 'spot'
  await scope.context.ui.openTradePanel(scope.page, { product, symbol })
  await scope.page.waitForFunction((provider, sourceMode) => {
    const badges = [...document.querySelectorAll('[data-source]')].filter((candidate) => {
      const rect = candidate.getBoundingClientRect()
      const style = getComputedStyle(candidate)
      return rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden'
    })
    return badges.some((badge) => (
      (sourceMode === undefined || badge.getAttribute('data-source') === sourceMode)
        && badge.textContent?.toLowerCase().includes(provider.toLowerCase().replace('-usdm', '').replace('-swap', ''))
    ))
  }, `${symbol} source badge`, bundle.quote.providerCode, expectedSourceMode)
  scope.apiEvidence.push({
    kind: 'AUTHORITY_BUNDLE',
    symbol,
    providerCode: bundle.quote.providerCode,
    providerSymbol: bundle.quote.providerSymbol,
    sourceMode: bundle.quote.sourceMode,
    asOf: bundle.quote.asOf,
    expiresAt: bundle.quote.expiresAt,
    stale: bundle.quote.stale,
    depthLevels: bundle.depth.bids.length + bundle.depth.asks.length,
    marketTrades: bundle.trades.length,
    candles: bundle.candles.length
  })
  return bundle
}

function assertSourceMetadata(payload, label) {
  assert(payload && typeof payload === 'object', `${label} payload is required`)
  for (const field of [
    'providerCode', 'providerSymbol', 'sourceMode', 'asOf', 'expiresAt', 'stale'
  ]) {
    assert.notEqual(payload[field], undefined, `${label} missing ${field}`)
    assert.notEqual(payload[field], null, `${label} missing ${field}`)
  }
  assert(['PUBLIC_EXTERNAL', 'LOCAL_SIMULATED'].includes(payload.sourceMode))
  assert(Number.isFinite(Date.parse(payload.asOf)), `${label} asOf`)
  assert(Number.isFinite(Date.parse(payload.expiresAt)), `${label} expiresAt`)
  assert.equal(payload.stale, false, `${label} must be fresh`)
}

async function useProviderBindings(scope, bySymbol, label) {
  const adminPage = await scope.getAdminPage()
  const fixtures = []
  let restored = false
  const restore = async () => {
    if (restored) return { status: 'ALREADY_RESTORED' }
    const failures = []
    for (const fixture of [...fixtures].reverse()) {
      try {
        await fixture.restore()
      } catch (error) {
        failures.push(error)
      }
    }
    if (failures.length > 0) throw new AggregateError(failures, `${label} restore failed`)
    restored = true
    return { status: 'RESTORED' }
  }
  try {
    for (const [symbol, enabledProviders] of Object.entries(bySymbol)) {
      fixtures.push(await scope.context.fixtures.providerBindings(adminPage, {
        symbols: [symbol],
        enabledProviders
      }))
    }
  } catch (error) {
    await restore()
    throw error
  }
  scope.registerFixtureRestore({ action: 'restore-provider-bindings', label }, restore)
  const evidence = {
    before: fixtures.flatMap(({ before }) => before),
    after: fixtures.flatMap(({ after }) => after),
    restore
  }
  scope.fixtureActions.push({
    action: 'set-provider-bindings',
    label,
    changes: evidence.after
  })
  return evidence
}

function assertBindingPriority(fixture, symbol, primaryCode, fallbackCode, primaryEnabled) {
  const beforePrimary = fixture.before.filter((row) => (
    row.symbol === symbol && row.providerCode === primaryCode
  ))
  const beforeFallback = fixture.before.filter((row) => (
    row.symbol === symbol && row.providerCode === fallbackCode
  ))
  const afterPrimary = fixture.after.filter((row) => (
    row.symbol === symbol && row.providerCode === primaryCode
  ))
  const afterFallback = fixture.after.filter((row) => (
    row.symbol === symbol && row.providerCode === fallbackCode
  ))
  assert.equal(beforePrimary.length, 1, `${symbol} ${primaryCode} binding cardinality`)
  assert.equal(beforeFallback.length, 1, `${symbol} ${fallbackCode} binding cardinality`)
  assert.equal(afterPrimary.length, 1, `${symbol} ${primaryCode} post-fixture cardinality`)
  assert.equal(afterFallback.length, 1, `${symbol} ${fallbackCode} post-fixture cardinality`)
  assert(
    Number(beforePrimary[0].priority) < Number(beforeFallback[0].priority),
    `${symbol} ${primaryCode} must have higher priority than ${fallbackCode}`
  )
  assert.equal(afterPrimary[0].enabled, primaryEnabled, `${symbol} ${primaryCode} enabled`)
  assert.equal(afterFallback[0].enabled, true, `${symbol} ${fallbackCode} enabled`)
}

async function setMarketOverride(scope, symbol, bidValue, askValue, label) {
  const market = await scope.context.api.snapshotMarket(symbol)
  const bid = alignedPrice(market, bidValue)
  const ask = alignedPrice(market, askValue)
  assert(Number(ask) > Number(bid), `${label} spread`)
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.marketOverride(adminPage, {
    symbol,
    bid,
    ask,
    ttl: 'PT5M'
  })
  let restored = false
  const restore = async () => {
    if (restored) return { status: 'ALREADY_RESTORED' }
    const result = await fixture.restore()
    restored = true
    return result
  }
  scope.registerFixtureRestore({ action: 'restore-market-override', symbol, label }, restore)
  scope.fixtureActions.push({ action: 'set-market-override', symbol, bid, ask, label })
  return waitForValue(async () => {
    const candidate = await scope.context.api.snapshotMarket(symbol)
    return Number(candidate.quote?.bid) === Number(bid)
      && Number(candidate.quote?.ask) === Number(ask)
      ? { ...candidate, restore }
      : false
  }, label, 30000)
}

async function setMarketOverrideBlind(scope, symbol, bid, ask, label) {
  assert(Number(ask) > Number(bid), `${label} spread`)
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.marketOverride(adminPage, {
    symbol,
    bid,
    ask,
    ttl: 'PT5M'
  })
  let restored = false
  const restore = async () => {
    if (restored) return { status: 'ALREADY_RESTORED' }
    const result = await fixture.restore()
    restored = true
    return result
  }
  scope.registerFixtureRestore({ action: 'restore-market-override', symbol, label }, restore)
  scope.fixtureActions.push({ action: 'set-market-override-without-fresh-read', symbol, bid, ask, label })
  return { bid, ask, restore }
}

export function assertStalePendingDbContract(db, orderId) {
  const orders = db.orderRows.filter(({ id }) => id === orderId)
  assert.equal(orders.length, 1, `${orderId} stale order cardinality`)
  assert(ACTIVE_ORDER_STATUSES.has(orders[0].status), `${orderId} must remain pending while stale`)
  assert.equal(
    db.tradeRows.filter(({ order_id: candidate }) => candidate === orderId).length,
    0,
    `${orderId} must not trade while stale`
  )
  return orders[0]
}

async function spotRoundTrip(scope, page, options = {}) {
  const bought = await filledSpotBuy(scope, page, `${options.label} Spot buy`, options)
  assertTradeAuthority(bought.trade, options.expectedAuthority, `${options.label} Spot buy`)
  const sold = await closeSpot(scope, page, bought.snapshot, `${options.label} Spot sell`, options)
  assertTradeAuthority(sold.trade, options.expectedAuthority, `${options.label} Spot sell`)
  return { ...sold, buyTrade: bought.trade, buyOrder: bought.order }
}

async function filledSpotBuy(scope, page, label, options = {}) {
  await scope.context.ui.openTradePanel(page, {
    product: 'spot', symbol: SPOT, mobile: options.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  const submit = () => scope.context.ui.submitOrderViaUi(page, {
    side: 'BUY', orderType: 'MARKET', amount: '20'
  })
  const capture = options.doubleClick
    ? await withRapidDoubleClick(scope, page, label, 'order-confirm', submit)
    : await submit()
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const filled = await waitForFilled(scope.context, page, before, capture, SPOT, label)
  assert.equal(filled.order.status, 'FILLED')
  assert.equal(filled.trade.orderId, filled.order.id)
  return { ...filled, capture }
}

async function closeSpot(scope, page, before, label, options = {}) {
  const market = await scope.context.api.snapshotMarket(SPOT)
  const wallet = findWallet(before, 'SPOT', 'BTC')
  const amount = floorToStep(String(wallet.available), effectiveQuantityStep(market.rules))
  assert(Number(amount) > 0, `${label} requires sellable BTC`)
  await scope.context.ui.openTradePanel(page, {
    product: 'spot', symbol: SPOT, mobile: options.mobile === true
  })
  const capture = await scope.context.ui.submitOrderViaUi(page, {
    side: 'SELL', orderType: 'MARKET', amount
  })
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const filled = await waitForFilled(scope.context, page, before, capture, SPOT, label)
  assert.equal(filled.order.status, 'FILLED')
  return { ...filled, capture }
}

async function perpRoundTrip(scope, page, options = {}) {
  const opened = await openPerp(scope, page, options)
  assertTradeAuthority(opened.trade, options.expectedAuthority, `${options.label} Perp open`)
  let position = opened.position
  let partialRatio
  if (options.partial) {
    const partial = await partialClose(scope, page, position, `${options.label} 30% close`)
    position = partial.position
    partialRatio = partial.ratio
  }
  const closed = await fullClose(scope, page, position, `${options.label} full close`)
  assertTradeAuthority(closed.trade, options.expectedAuthority, `${options.label} Perp close`)
  return {
    ...closed,
    openTrade: opened.trade,
    positionId: opened.position.id,
    partialRatio
  }
}

async function openPerp(scope, page, options = {}) {
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual', symbol: PERP, mobile: options.mobile === true
  })
  const settings = await scope.context.ui.setPerpetualSettingsViaUi(page, {
    positionMode: 'ONE_WAY',
    marginMode: options.marginMode ?? 'CROSS',
    leverage: options.leverage ?? 10,
    quantityUnit: 'BASE'
  })
  for (const capture of settings) scope.addMutation('perpetual-settings-via-ui', capture)
  const market = await scope.context.api.snapshotMarket(PERP)
  const amount = stepAlignedQuantity(market, options.quantity ?? 0.01)
  const before = await scope.context.api.snapshotAccount(page)
  const capture = await scope.context.ui.submitOrderViaUi(page, {
    side: options.side ?? 'BUY',
    positionSide: 'BOTH',
    orderType: 'MARKET',
    amount,
    quantityUnit: 'BASE'
  })
  scope.addMutation(`${slug(options.label ?? 'perp-open')}-via-ui`, capture)
  const filled = await waitForFilled(scope.context, page, before, capture, PERP, options.label)
  const position = openPositions(filled.snapshot, PERP).find((candidate) => (
    !before.positions.some(({ id }) => id === candidate.id)
  )) ?? openPositions(filled.snapshot, PERP).at(-1)
  assert(position, `${options.label} must create an open Perp position`)
  return { ...filled, position, market, capture }
}

async function partialClose(scope, page, position, label, options = {}) {
  const market = await scope.context.api.snapshotMarket(position.symbol)
  const quantity = floorToStep(
    String(Number(position.lots) * 0.3),
    effectiveQuantityStep(market.rules)
  )
  assert(Number(quantity) > 0 && Number(quantity) < Number(position.lots), `${label} quantity`)
  await scope.context.ui.openTradePanel(page, { product: 'perpetual', symbol: position.symbol, mobile: page.p0TradePanel?.mobile === true })
  const submit = () => scope.context.ui.positionActionViaUi(page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'PARTIAL_CLOSE',
    quantity,
    quantityUnit: 'BASE'
  })
  const capture = options.doubleClick
    ? await withRapidDoubleClick(scope, page, label, 'position-confirm', submit)
    : await submit()
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const result = await waitForAccount(scope.context, page, label, (candidate) => {
    const current = openPositions(candidate, position.symbol).find(({ id }) => id === position.id)
    return current && Number(current.lots) < Number(position.lots)
      ? { snapshot: candidate, position: current }
      : false
  })
  return {
    ...result,
    capture,
    quantity,
    ratio: Number(quantity) / Number(position.lots)
  }
}

async function fullClose(scope, page, position, label, options = {}) {
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual', symbol: position.symbol, mobile: page.p0TradePanel?.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  const submit = () => scope.context.ui.positionActionViaUi(page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'FULL_CLOSE'
  })
  const capture = options.doubleClick
    ? await withRapidDoubleClick(scope, page, label, 'position-confirm', submit)
    : await submit()
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const result = await waitForAccount(scope.context, page, label, (candidate) => {
    if (openPositions(candidate, position.symbol).some(({ id }) => id === position.id)) return false
    const trade = recordsAfter(before, candidate, 'trades')
      .find(({ symbol }) => symbol === position.symbol)
    return trade ? { snapshot: candidate, trade } : false
  })
  return { ...result, capture }
}

function assertTradeAuthority(trade, authority, label) {
  if (!authority) return
  assert.equal(
    trade.providerCode,
    authority.providerCode,
    'Trade provider must match the pre-submit authority bundle'
  )
  assert.equal(trade.sourceMode, authority.sourceMode, `${label} sourceMode`)
}

async function pendingSpot(scope, page, options = {}) {
  const market = await scope.context.api.snapshotMarket(SPOT)
  const amount = stepAlignedQuantity(market, 0.001)
  const price = alignedPrice(market, Number(market.quote.bid) * 0.5)
  await scope.context.ui.openTradePanel(page, {
    product: 'spot', symbol: SPOT, mobile: options.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  const capture = await scope.context.ui.submitOrderViaUi(page, {
    side: 'BUY', orderType: 'LIMIT', price, amount
  })
  scope.addMutation(`${slug(options.label ?? 'pending-spot')}-via-ui`, capture)
  const result = await waitForAccount(scope.context, page, options.label, (candidate) => {
    const order = orderForCapture(before, candidate, capture)
    return order?.symbol === SPOT && ACTIVE_ORDER_STATUSES.has(order.status)
      ? { snapshot: candidate, order }
      : false
  })
  return { ...result, capture, market }
}

async function pendingSpotCancel(scope, page, options = {}) {
  const pending = await pendingSpot(scope, page, options)
  const cancel = await scope.context.ui.cancelAllOrdersViaUi(page)
  scope.addMutation(`${slug(options.label ?? 'pending-spot')}-cancel-via-ui`, cancel)
  const snapshot = await waitForAccount(scope.context, page, options.label, (candidate) => (
    activeOrders(candidate).length === 0 ? candidate : false
  ))
  assert.equal(snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length, 0)
  return { ...pending, snapshot, cancel }
}

async function triggerPendingSpot(scope, label) {
  const pending = await pendingSpot(scope, scope.page, { label })
  const limit = Number(pending.order.price)
  await setMarketOverride(scope, SPOT, limit * 0.98, limit * 0.99, `${label} trigger`)
  const filled = await waitForAccount(scope.context, scope.page, `${label} fill`, (candidate) => {
    const order = candidate.orders.find(({ id }) => id === pending.order.id)
    const trades = candidate.trades.filter(({ orderId }) => orderId === pending.order.id)
    return order?.status === 'FILLED' && trades.length === 1
      ? { snapshot: candidate, order, trade: trades[0] }
      : false
  }, 45000)
  assertTradeAuthority(filled.trade, {
    providerCode: 'local-spot', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  const cleanup = await closeSpot(scope, scope.page, filled.snapshot, `${label} cleanup`)
  return { ...filled, snapshot: cleanup.snapshot }
}

async function triggerStandaloneSpotStop(scope, label) {
  const market = await scope.context.api.snapshotMarket(SPOT)
  const triggerPrice = alignedPrice(market, Number(market.quote.ask) * 1.2)
  const amount = stepAlignedQuantity(market, 0.001)
  await scope.context.ui.openTradePanel(scope.page, { product: 'spot', symbol: SPOT })
  const before = await scope.context.api.snapshotAccount(scope.page)
  const capture = await scope.context.ui.submitOrderViaUi(scope.page, {
    side: 'BUY', orderType: 'STOP_MARKET', triggerPrice, amount
  })
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const pending = await waitForAccount(scope.context, scope.page, `${label} pending`, (candidate) => {
    const order = orderForCapture(before, candidate, capture)
    return order && ACTIVE_ORDER_STATUSES.has(order.status) ? { snapshot: candidate, order } : false
  })
  await setMarketOverride(
    scope, SPOT, Number(triggerPrice) * 1.01, Number(triggerPrice) * 1.02, `${label} trigger`
  )
  const filled = await waitForAccount(scope.context, scope.page, `${label} fill`, (candidate) => {
    const order = candidate.orders.find(({ id }) => id === pending.order.id)
    const trades = candidate.trades.filter(({ orderId }) => orderId === pending.order.id)
    return order?.status === 'FILLED' && trades.length === 1
      ? { snapshot: candidate, order, trade: trades[0] }
      : false
  }, 45000)
  assertTradeAuthority(filled.trade, {
    providerCode: 'local-spot', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  const cleanup = await closeSpot(scope, scope.page, filled.snapshot, `${label} cleanup`)
  return { ...filled, snapshot: cleanup.snapshot }
}

async function triggerPendingPerpLimit(scope, label) {
  await scope.context.ui.openTradePanel(scope.page, { product: 'perpetual', symbol: PERP })
  const settings = await scope.context.ui.setPerpetualSettingsViaUi(scope.page, {
    positionMode: 'ONE_WAY', marginMode: 'CROSS', leverage: 10, quantityUnit: 'BASE'
  })
  for (const capture of settings) scope.addMutation('perpetual-settings-via-ui', capture)
  const market = await scope.context.api.snapshotMarket(PERP)
  const quantity = stepAlignedQuantity(market, 0.01)
  const price = alignedPrice(market, Number(market.quote.bid) * 0.8)
  const before = await scope.context.api.snapshotAccount(scope.page)
  const capture = await scope.context.ui.submitOrderViaUi(scope.page, {
    side: 'BUY', positionSide: 'BOTH', orderType: 'LIMIT', price,
    amount: quantity, quantityUnit: 'BASE'
  })
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const pending = await waitForAccount(scope.context, scope.page, `${label} pending`, (candidate) => {
    const order = orderForCapture(before, candidate, capture)
    return order && ACTIVE_ORDER_STATUSES.has(order.status) ? { snapshot: candidate, order } : false
  })
  await setMarketOverride(scope, PERP, Number(price) * 0.98, Number(price) * 0.99, `${label} trigger`)
  const filled = await waitForAccount(scope.context, scope.page, `${label} fill`, (candidate) => {
    const order = candidate.orders.find(({ id }) => id === pending.order.id)
    const openTrade = candidate.trades.find(({ orderId }) => orderId === pending.order.id)
    const position = openPositions(candidate, PERP).find((current) => (
      !before.positions.some(({ id }) => id === current.id)
    ))
    return order?.status === 'FILLED' && openTrade && position
      ? { snapshot: candidate, order, openTrade, position }
      : false
  }, 45000)
  assertTradeAuthority(filled.openTrade, {
    providerCode: 'local-perp', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  const cleanup = await fullClose(scope, scope.page, filled.position, `${label} cleanup`)
  return { ...filled, snapshot: cleanup.snapshot }
}

async function pendingPerpStop(scope, page, position, options = {}) {
  const market = await scope.context.api.snapshotMarket(PERP)
  const mark = Number(market.reference?.mark ?? market.quote?.last)
  const triggerPrice = alignedPrice(
    market,
    mark * (options.immediate ? 1.01 : 0.8)
  )
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual', symbol: PERP, mobile: options.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  const capture = await scope.context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    positionSide: 'BOTH',
    orderType: 'STOP_MARKET',
    triggerPrice,
    amount: String(position.lots),
    quantityUnit: 'BASE',
    reduceOnly: true
  })
  scope.addMutation(`${slug(options.label ?? 'perp-stop')}-via-ui`, capture)
  const result = await waitForAccount(scope.context, page, options.label, (candidate) => {
    const order = orderForCapture(before, candidate, capture)
    return order?.symbol === PERP
      && (ACTIVE_ORDER_STATUSES.has(order.status) || order.status === 'FILLED')
      ? { snapshot: candidate, order }
      : false
  })
  return { ...result, capture, triggerPrice }
}

async function immediatePerpStopClose(scope, page, options = {}) {
  const opened = await openPerp(scope, page, options)
  const stop = await pendingPerpStop(scope, page, opened.position, {
    ...options,
    immediate: true
  })
  const result = await waitForAccount(scope.context, page, options.label, (candidate) => {
    if (openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)) return false
    const order = candidate.orders.find(({ id }) => id === stop.order.id)
    const closeTrade = candidate.trades.find(({ orderId }) => orderId === stop.order.id)
    return order?.status === 'FILLED' && closeTrade
      ? { snapshot: candidate, closeTrade }
      : false
  }, 45000)
  assertTradeAuthority(result.closeTrade, options.expectedAuthority, options.label)
  return { ...result, opened, stop }
}

async function pendingOcoCancel(scope, page = scope.page, options = {}) {
  const result = await pendingOco(scope, page, options)
  const cancel = await scope.context.ui.cancelAllOrdersViaUi(page)
  scope.addMutation(`${slug(options.label ?? 'oco')}-cancel-via-ui`, cancel)
  const snapshot = await waitForAccount(scope.context, page, options.label, (candidate) => (
    activeOrders(candidate).length === 0 ? candidate : false
  ))
  return { ...result, snapshot, cancel }
}

async function pendingOco(scope, page = scope.page, options = {}) {
  const market = await scope.context.api.snapshotMarket(SPOT)
  const last = Number(market.quote?.last ?? market.quote?.mid)
  const quantity = stepAlignedQuantity(market, 0.001)
  await scope.context.ui.openTradePanel(page, {
    product: 'spot', symbol: SPOT, mobile: options.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  const capture = await submitOcoViaUi(scope, page, {
    side: 'BUY',
    quantity,
    limitPrice: alignedPrice(market, last * 0.8),
    stopTriggerPrice: alignedPrice(market, last * 1.2)
  })
  scope.addMutation(`${slug(options.label ?? 'oco')}-via-ui`, capture)
  const response = parsedResponse(capture)?.data ?? parsedResponse(capture)
  const result = await waitForAccount(scope.context, page, options.label, (candidate) => {
    const created = recordsAfter(before, candidate, 'orders')
    const groupId = response?.contingencyGroupId
      ?? created.find(({ contingencyGroupId }) => contingencyGroupId)?.contingencyGroupId
    const legs = candidate.orders.filter(({ contingencyGroupId }) => contingencyGroupId === groupId)
    return groupId && legs.length === 2 && legs.every(({ status }) => ACTIVE_ORDER_STATUSES.has(status))
      ? { snapshot: candidate, groupId, legs }
      : false
  })
  assert.deepEqual(result.legs.map(({ orderType }) => orderType).toSorted(), ['LIMIT', 'STOP_MARKET'])
  return result
}

async function triggerOcoWinner(scope, label) {
  const pending = await pendingOco(scope, scope.page, { label })
  const winner = pending.legs.find(({ orderType }) => orderType === 'LIMIT')
  assert(winner, `${label} LIMIT leg`)
  const limit = Number(winner.price)
  await setMarketOverride(scope, SPOT, limit * 0.98, limit * 0.99, `${label} trigger`)
  const result = await waitForAccount(scope.context, scope.page, `${label} winner`, (candidate) => {
    const legs = candidate.orders.filter(({ contingencyGroupId }) => (
      contingencyGroupId === pending.groupId
    ))
    const filled = legs.filter(({ status }) => status === 'FILLED')
    const canceled = legs.filter(({ status }) => status === 'CANCELED')
    const trades = candidate.trades.filter(({ orderId }) => filled.some(({ id }) => id === orderId))
    return legs.length === 2 && filled.length === 1 && canceled.length === 1 && trades.length === 1
      ? { snapshot: candidate, trade: trades[0], legs }
      : false
  }, 45000)
  assertTradeAuthority(result.trade, {
    providerCode: 'local-spot', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  const cleanup = await closeSpot(scope, scope.page, result.snapshot, `${label} cleanup`)
  return { ...result, snapshot: cleanup.snapshot, groupId: pending.groupId }
}

async function triggerPerpProtection(scope, label) {
  const opened = await openPerp(scope, scope.page, { label })
  const mark = Number(opened.market.reference?.mark ?? opened.market.quote?.mid)
  const protection = await createStopLossProtectionViaUi(
    scope, scope.page, opened.position, mark * 0.8, `${label} SL`
  )
  const trigger = Number(protection.order.triggerPrice)
  await setMarketOverride(scope, PERP, trigger * 0.98, trigger * 0.99, `${label} trigger`)
  const result = await waitForAccount(scope.context, scope.page, `${label} fill`, (candidate) => {
    if (openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)) return false
    const order = candidate.orders.find(({ id }) => id === protection.order.id)
    const trades = candidate.trades.filter(({ orderId }) => orderId === protection.order.id)
    return order?.status === 'FILLED' && trades.length === 1
      ? { snapshot: candidate, order, trade: trades[0] }
      : false
  }, 45000)
  assert.equal(result.order.orderOrigin, 'PROTECTIVE')
  assertTradeAuthority(result.trade, {
    providerCode: 'local-perp', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  return result
}

async function triggerPerpTakeProfit(scope, label) {
  const opened = await openPerp(scope, scope.page, { label })
  const mark = Number(opened.market.reference?.mark ?? opened.market.quote?.mid)
  const protection = await createStopLossProtectionViaUi(
    scope,
    scope.page,
    opened.position,
    mark * 1.2,
    `${label} TP`,
    'TAKE_PROFIT'
  )
  const trigger = Number(protection.order.triggerPrice)
  await setMarketOverride(scope, PERP, trigger * 1.01, trigger * 1.02, `${label} trigger`)
  const result = await waitForAccount(scope.context, scope.page, `${label} fill`, (candidate) => {
    if (openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)) return false
    const order = candidate.orders.find(({ id }) => id === protection.order.id)
    const trades = candidate.trades.filter(({ orderId }) => orderId === protection.order.id)
    return order?.status === 'FILLED' && trades.length === 1
      ? { snapshot: candidate, order, trade: trades[0] }
      : false
  }, 45000)
  assert.equal(result.order.protectionType, 'TAKE_PROFIT')
  assert.equal(result.order.orderOrigin, 'PROTECTIVE')
  assertTradeAuthority(result.trade, {
    providerCode: 'local-perp', sourceMode: 'LOCAL_SIMULATED'
  }, label)
  return result
}

async function submitOcoViaUi(scope, page, options) {
  const panelSelector = page.p0TradePanel.selector
  await page.evaluate((selector) => {
    const panel = document.querySelector(selector)
    const oco = [...(panel?.querySelectorAll('[role="tab"]') ?? [])]
      .find((tab) => tab.textContent?.trim() === 'OCO')
    if (!(oco instanceof HTMLButtonElement) || oco.disabled) throw new Error('P0_OCO_TAB_MISSING')
    oco.click()
  }, panelSelector)
  await waitUntil(() => page.evaluate((selector, values) => {
    const panel = document.querySelector(selector)
    const form = panel?.querySelector(
      `section[data-price-precision][class*="side--${values.side.toLowerCase()}"]`
    )
    const oco = [...(panel?.querySelectorAll('[role="tab"]') ?? [])]
      .find((tab) => tab.textContent?.trim() === 'OCO')
    const inputs = [...(form?.querySelectorAll('input[inputmode="decimal"]') ?? [])]
    if (!form || oco?.getAttribute('aria-selected') !== 'true' || inputs.length < 3) return false
    const setValue = (input, value) => {
      if (input.value === String(value) && input.defaultValue === String(value)) return true
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
        ?.call(input, String(value))
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return [
      setValue(inputs[0], values.stopTriggerPrice),
      setValue(inputs[1], values.limitPrice),
      setValue(inputs.at(-1), values.quantity)
    ].every(Boolean)
  }, panelSelector, options), 'OCO fields')
  await waitUntil(() => page.evaluate((selector, side) => {
    const form = document.querySelector(selector)?.querySelector(
      `section[data-price-precision][class*="side--${side.toLowerCase()}"]`
    )
    const submit = form?.querySelector('[data-trading-action="submit-order"]')
    if (!(submit instanceof HTMLButtonElement) || submit.disabled) return false
    submit.click()
    return true
  }, panelSelector, options.side), 'OCO submit')
  await page.waitForFunction(() => [...document.querySelectorAll('section[role="dialog"]')]
    .some((dialog) => dialog.textContent?.includes('OCO / GTC')), 'OCO confirmation')
  const capture = await scope.context.ui.withCapturedMutation(
    page,
    { method: 'POST', url: /\/api\/trading\/oco$/u },
    () => page.evaluate(() => {
      const dialog = [...document.querySelectorAll('section[role="dialog"]')]
        .find((candidate) => candidate.textContent?.includes('OCO / GTC'))
      const confirm = dialog?.querySelector('footer button:last-child')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_OCO_CONFIRM_MISSING')
      }
      confirm.click()
      return true
    })
  )
  assertSuccess(capture, 'OCO submission')
  return capture
}

async function createStopLossProtectionViaUi(
  scope,
  page,
  position,
  triggerValue,
  label,
  protectionType = 'STOP_LOSS'
) {
  const market = await scope.context.api.snapshotMarket(position.symbol)
  const triggerPrice = alignedPrice(market, triggerValue)
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: position.symbol,
    mobile: page.p0TradePanel?.mobile === true
  })
  const before = await scope.context.api.snapshotAccount(page)
  await page.waitForFunction((positionId) => Boolean(
    document.querySelector(`tr[data-position-id="${positionId}"] button`)
  ), 'target position row', position.id)
  await page.evaluate((positionId) => {
    const button = document.querySelector(`tr[data-position-id="${positionId}"] button`)
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_POSITION_ACTION_BUTTON_MISSING')
    }
    button.click()
  }, position.id)
  await page.waitForFunction(() => Boolean(document.querySelector(
    'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
  )), 'Position action dialog')
  await page.evaluate(() => {
    const dialog = document.querySelector(
      'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
    )
    const tab = [...(dialog?.querySelectorAll(
      '[role="tablist"][aria-label="Position action type"] [role="tab"]'
    ) ?? [])].find((candidate) => candidate.textContent?.trim() === 'TP / SL')
    if (!(tab instanceof HTMLButtonElement) || tab.disabled) {
      throw new Error('P0_PROTECTION_ACTION_UNAVAILABLE')
    }
    tab.click()
  })
  await waitUntil(() => page.evaluate(() => Boolean(document.querySelector(
    'section[role="dialog"] section[aria-label="Position take-profit and stop-loss protections"]'
  ))), 'Position protection editor')
  await page.evaluate((type) => {
    const editor = document.querySelector(
      'section[role="dialog"] section[aria-label="Position take-profit and stop-loss protections"]'
    )
    const addLabel = type === 'TAKE_PROFIT' ? '+ TP' : '+ SL'
    const add = [...(editor?.querySelectorAll('header button') ?? [])]
      .find((button) => button.textContent?.trim() === addLabel)
    if (!(add instanceof HTMLButtonElement) || add.disabled) {
      throw new Error(`P0_PROTECTION_ADD_UNAVAILABLE: ${type}`)
    }
    add.click()
  }, protectionType)
  await waitUntil(() => page.evaluate(() => (
    document.querySelectorAll(
      'section[role="dialog"] section[aria-label="Position take-profit and stop-loss protections"] fieldset'
    ).length === 1
  )), `one ${protectionType} protection level`)
  await waitUntil(() => page.evaluate((values) => {
    const fieldset = document.querySelector(
      'section[role="dialog"] section[aria-label="Position take-profit and stop-loss protections"] fieldset'
    )
    if (!fieldset) return false
    const selects = [...fieldset.querySelectorAll('select')]
    const inputs = [...fieldset.querySelectorAll('input[inputmode="decimal"]')]
    const setControl = (control, value) => {
      if (!control) return false
      if (control.value === String(value)) return true
      const prototype = control instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(control, String(value))
      control.dispatchEvent(new Event('input', { bubbles: true }))
      control.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return setControl(selects[0], values.protectionType)
      && setControl(inputs[0], values.triggerPrice)
      && setControl(inputs[1], values.quantity)
      && setControl(selects[1], 'MARKET')
  }, {
    triggerPrice,
    quantity: String(position.lots),
    protectionType
  }), `${label} protection fields`)
  const capture = await scope.context.ui.withCapturedMutation(
    page,
    { method: 'POST', url: new RegExp(`/api/trading/positions/${position.id}/protections$`, 'u') },
    () => page.evaluate(() => {
      const dialog = document.querySelector(
        'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
      )
      const confirm = dialog?.querySelector('footer button[type="submit"]')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_PROTECTION_CONFIRM_MISSING')
      }
      confirm.click()
      return true
    })
  )
  assertSuccess(capture, `${label} protection creation`)
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const created = await waitForAccount(scope.context, page, label, (candidate) => {
    const orders = recordsAfter(before, candidate, 'orders').filter((order) => (
      order.parentPositionId === position.id
        && order.protectionType === protectionType
        && order.orderOrigin === 'PROTECTIVE'
    ))
    return orders.length === 1 && ACTIVE_ORDER_STATUSES.has(orders[0].status)
      ? { snapshot: candidate, order: orders[0] }
      : false
  })
  return { ...created, capture, triggerPrice }
}

async function localFundingSettlement(scope, options = {}) {
  await useProviderBindings(scope, { [PERP]: ['local-perp'] }, 'local funding provider')
  const adminPage = await scope.getAdminPage()
  const config = await scope.context.fixtures.fundingConfig(adminPage, {
    symbol: PERP,
    fundingSourcePriority: ['FIXED'],
    fixedFundingRate: '0.0001000000',
    fixedFundingIntervalMinutes: 525600,
    fundingStaleSeconds: 5,
    reason: `${scope.definition.id} deterministic LOCAL funding`
  })
  scope.registerFixtureRestore({ action: 'restore-funding-config', symbol: PERP }, config.restore)
  scope.fixtureActions.push({
    action: 'set-local-fixed-funding',
    symbol: PERP,
    before: config.before,
    after: config.after
  })
  await isolateFundingRateCursor(scope, PERP)
  const opened = await openPerp(scope, scope.page, { label: `${scope.definition.id} funding` })
  const cycle = await createFundingCycleFixture(scope, {
    symbol: PERP,
    rate: '0.0001000000',
    markPrice: opened.position.markPrice ?? opened.market.reference?.mark,
    dueInMs: options.restart ? 2500 : 1800
  })
  const positionTime = await scope.context.fixtures.positionTime({
    accountId: opened.snapshot.account.id,
    positionId: opened.position.id,
    openedAt: new Date(Date.parse(cycle.fundingTime) - 1000).toISOString()
  })
  scope.registerFixtureRestore({ action: 'restore-position-opened-at', positionId: opened.position.id }, positionTime.restore)
  scope.fixtureActions.push({
    action: 'set-position-opened-at',
    positionId: opened.position.id,
    before: positionTime.before,
    after: positionTime.after
  })
  const eventCursor = scope.context.events.snapshotFrames(scope.page).length
  if (options.restart) {
    await restartWithEvidence(scope, async () => {
      while (Date.now() <= Date.parse(cycle.fundingTime) + 100) await delay(50)
      return { positionId: opened.position.id, crossedFundingTime: cycle.fundingTime }
    })
    await reloadAuthenticatedPage(scope.page)
  }
  const settled = await waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} local funding settlement`,
    (candidate) => {
      const created = candidate.fundingSettlements.filter(({ positionId, fundingTime }) => (
        positionId === opened.position.id
          && Date.parse(fundingTime) === Date.parse(cycle.fundingTime)
      ))
      return created.length === 1 ? { snapshot: candidate, settlement: created[0] } : false
    },
    100000
  )
  const position = openPositions(settled.snapshot, PERP)
    .find(({ id }) => id === opened.position.id)
  assert(position, `${opened.position.id} remains open after funding`)
  const oracle = fundingSettlementOracle({
    side: positionSide(opened.position),
    marginMode: opened.position.marginMode,
    quantity: String(opened.position.lots),
    markPrice: String(settled.settlement.markPrice),
    fundingRate: String(settled.settlement.fundingRate),
    balanceBefore: String(opened.snapshot.summary.balance),
    marginHeld: String(opened.position.marginHeld),
    previousFundingPnl: String(opened.position.fundingPnl ?? '0')
  })
  assertFundingSettlementContract({
    settlement: settled.settlement,
    expected: oracle,
    expectedPositionId: opened.position.id,
    expectedFundingTime: cycle.fundingTime,
    expectedSource: 'fixed'
  })
  const fundingEvents = await waitForValue(() => {
    const matches = scope.context.events.snapshotFrames(scope.page)
      .slice(eventCursor)
      .filter(({ direction, eventType }) => direction === 'received' && eventType === 'FUNDING_SETTLED')
    return matches.length === 1 ? matches : false
  }, `${scope.definition.id} one FUNDING_SETTLED event`, 15000)
  const db = await scope.context.db.snapshotTradingRows(opened.snapshot.account.id)
  const settlementRows = db.fundingSettlementRows.filter(({ position_id: positionId, funding_time: fundingTime }) => (
    positionId === opened.position.id
      && Date.parse(fundingTime) === Date.parse(cycle.fundingTime)
  ))
  assert.equal(settlementRows.length, 1, `${opened.position.id} DB funding settlement cardinality`)
  const ledgers = db.cashLedgerRows.filter(({ reference_id: referenceId, entry_type: entryType }) => (
    referenceId === settled.settlement.id && entryType === 'FUNDING_FEE'
  ))
  assert.equal(ledgers.length, oracle.ledgerAmount === null ? 0 : 1, 'funding ledger cardinality')
  scope.dbEvidence.push(db)
  scope.oracleEvidence.push({
    kind: 'LOCAL_FUNDING_SETTLEMENT',
    positionId: opened.position.id,
    settlementId: settled.settlement.id,
    providerCode: 'fixed',
    sourceMode: 'LOCAL_SIMULATED',
    source: settled.settlement.source,
    fundingTime: cycle.fundingTime,
    fundingRate: cycle.rate,
    markPrice: cycle.markPrice,
    events: fundingEvents.length
  })
  return (await fullClose(
    scope,
    scope.page,
    openPositions(settled.snapshot, PERP).find(({ id }) => id === opened.position.id),
    `${scope.definition.id} funding cleanup`
  )).snapshot
}

async function createFundingCycleFixture(scope, input) {
  const fundingTimeMs = Math.floor((Date.now() + input.dueInMs) / 100) * 100
  const cycle = {
    id: randomUUID(),
    symbol: input.symbol,
    rate: String(input.rate),
    fundingTime: new Date(fundingTimeMs).toISOString(),
    nextFundingTime: new Date(fundingTimeMs + 60000).toISOString(),
    markPrice: String(input.markPrice),
    providerCode: 'fixed',
    sourceMode: 'LOCAL_SIMULATED',
    asOf: new Date().toISOString(),
    intervalMinutes: 1,
    rawPayloadHash: 'f'.repeat(64)
  }
  assertPositiveNumber(cycle.markPrice, `${cycle.symbol} funding mark`)
  const inserted = scalarResult(await scope.context.db.query(`
    INSERT INTO trading.funding_rates (
      id, symbol, funding_rate, funding_time, next_funding_time, mark_price,
      provider_code, source_mode, as_of, interval_minutes, raw_payload_hash
    ) VALUES (
      '${sqlLiteral(cycle.id)}', '${sqlLiteral(cycle.symbol)}', ${Number(cycle.rate).toFixed(10)},
      '${sqlLiteral(cycle.fundingTime)}', '${sqlLiteral(cycle.nextFundingTime)}',
      ${Number(cycle.markPrice).toFixed(10)}, '${cycle.providerCode}', '${cycle.sourceMode}',
      '${sqlLiteral(cycle.asOf)}', ${cycle.intervalMinutes}, '${cycle.rawPayloadHash}'
    ) ON CONFLICT (symbol, funding_time) DO NOTHING RETURNING id;
  `))
  assert.equal(inserted, cycle.id, `${cycle.symbol} canonical funding cycle inserted`)
  scope.registerFixtureRestore({
    action: 'delete-canonical-funding-cycle',
    symbol: cycle.symbol,
    rateId: cycle.id
  }, async () => {
    const deleted = Number(scalarResult(await scope.context.db.query(`
      WITH deleted AS (
        DELETE FROM trading.funding_rates
        WHERE id = '${sqlLiteral(cycle.id)}' AND symbol = '${sqlLiteral(cycle.symbol)}'
        RETURNING id
      ) SELECT count(*) FROM deleted;
    `)))
    assert.equal(deleted, 1, `restore funding cycle ${cycle.id}`)
  })
  scope.fixtureActions.push({ action: 'insert-canonical-funding-cycle', ...cycle })
  return cycle
}

async function isolateFundingRateCursor(scope, symbol) {
  const before = await jsonResult(scope.context, `
    WITH locked_symbol AS MATERIALIZED (
      SELECT id FROM market.symbols
      WHERE symbol = '${sqlLiteral(symbol)}'
      FOR UPDATE
    ), deleted AS (
      DELETE FROM trading.funding_rates rate_row
      USING locked_symbol
      WHERE rate_row.symbol = '${sqlLiteral(symbol)}'
      RETURNING rate_row.*
    )
    SELECT COALESCE(
      json_agg(row_to_json(deleted) ORDER BY deleted.funding_time, deleted.id),
      '[]'::json
    )::text FROM deleted;
  `)
  const payload = sqlLiteral(JSON.stringify(before))
  scope.registerFixtureRestore({
    action: 'restore-isolated-funding-rate-cursor',
    symbol,
    rateIds: before.map(({ id }) => id)
  }, async () => {
    await scope.context.db.query(`
      DO $p0_restore$
      DECLARE restored_count integer;
      BEGIN
        PERFORM id FROM market.symbols
        WHERE symbol = '${sqlLiteral(symbol)}'
        FOR UPDATE;
        DELETE FROM trading.funding_rates
        WHERE symbol = '${sqlLiteral(symbol)}';
        INSERT INTO trading.funding_rates
        SELECT restored.* FROM jsonb_populate_recordset(
          null::trading.funding_rates,
          '${payload}'::jsonb
        ) AS restored;
        GET DIAGNOSTICS restored_count = ROW_COUNT;
        IF restored_count <> ${before.length} THEN
          RAISE EXCEPTION 'P0_FUNDING_RATE_CURSOR_RESTORE_FAILED';
        END IF;
      END
      $p0_restore$;
    `)
  })
  scope.fixtureActions.push({
    action: 'isolate-funding-rate-cursor',
    symbol,
    removed: before.length,
    rateIds: before.map(({ id }) => id)
  })
}

async function localLiquidation(scope) {
  await useProviderBindings(scope, { [PERP]: ['local-perp'] }, 'local liquidation provider')
  const opened = await openPerp(scope, scope.page, {
    label: `${scope.definition.id} liquidation`,
    marginMode: 'ISOLATED',
    leverage: 100
  })
  const liquidationPrice = Number(opened.position.liquidationPrice)
  assert(Number.isFinite(liquidationPrice) && liquidationPrice > 0)
  const safeMarket = await setMarketOverride(
    scope,
    PERP,
    liquidationPrice * 1.01,
    liquidationPrice * 1.02,
    `${scope.definition.id} one-percent safe boundary`
  )
  const safeRisk = isolatedLiquidationRisk(opened.position, safeMarket)
  assert.equal(safeRisk.liquidatable, false, `${scope.definition.id} safe liquidation predicate`)
  await observeOpenPosition(scope, opened.position.id, 1200)
  const eventCursor = scope.context.events.snapshotFrames(scope.page).length
  const boundaryMarket = await setMarketOverride(
    scope,
    PERP,
    liquidationPrice * 0.97,
    liquidationPrice * 0.98,
    `${scope.definition.id} liquidation boundary`
  )
  const boundaryRisk = isolatedLiquidationRisk(opened.position, boundaryMarket)
  assert.equal(boundaryRisk.liquidatable, true, `${scope.definition.id} liquidation predicate`)
  const liquidated = await waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} isolated liquidation`,
    (candidate) => {
      if (openPositions(candidate, PERP).some(({ id }) => id === opened.position.id)) return false
      const history = candidate.positionHistory.find(({ id }) => id === opened.position.id)
      return history ? { snapshot: candidate, history } : false
    },
    45000
  )
  assert.equal(liquidated.history.status, 'LIQUIDATED')
  const events = await waitForValue(() => {
    const matches = scope.context.events.snapshotFrames(scope.page)
      .slice(eventCursor)
      .filter(({ direction, eventType }) => direction === 'received' && eventType === 'LIQUIDATION')
    return matches.length === 1 ? matches : false
  }, `${scope.definition.id} one LIQUIDATION event`, 30000)
  const db = await scope.context.db.snapshotTradingRows(liquidated.snapshot.account.id)
  const lifecycle = assertLiquidationLifecycleContract({
    positionId: opened.position.id,
    orders: db.orderRows,
    trades: db.tradeRows,
    ledger: db.cashLedgerRows
  })
  scope.dbEvidence.push(db)
  scope.oracleEvidence.push({
    kind: 'LOCAL_ISOLATED_LIQUIDATION',
    positionId: opened.position.id,
    status: liquidated.history.status,
    liquidationPrice,
    safeRisk,
    boundaryRisk,
    liquidationOrderId: lifecycle.order.id,
    liquidationTradeId: lifecycle.trade.id,
    feeLedgerId: lifecycle.ledger.id,
    events: events.length
  })
  return liquidated.snapshot
}

function isolatedLiquidationRisk(position, market) {
  return isolatedLiquidationOracle({
    side: positionSide(position),
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    markPrice: String(market.reference?.mark ?? market.quote?.markPrice ?? market.quote?.mid),
    marginHeld: String(position.marginHeld),
    fundingPnl: String(position.fundingPnl ?? '0'),
    maintenanceMarginRate: String(position.maintenanceMarginRate),
    rules: market.rules
  })
}

async function observeOpenPosition(scope, positionId, durationMs) {
  const deadline = Date.now() + durationMs
  let latest
  do {
    latest = await scope.context.api.snapshotAccount(scope.page)
    assert(openPositions(latest).some(({ id }) => id === positionId), `${positionId} must remain open`)
    await delay(100)
  } while (Date.now() < deadline)
  return latest
}

async function assertRaceRoundDb(scope, accountId, { label, orderIds = [], positionId }) {
  const db = await scope.context.db.snapshotTradingRows(accountId)
  assertRaceDbContract(db, { label, orderIds, positionId })
  scope.dbEvidence.push(db)
  return db
}

export function assertRaceDbContract(db, { label, orderIds = [], positionId }) {
  for (const orderId of orderIds) {
    const orders = db.orderRows.filter(({ id }) => id === orderId)
    assert.equal(orders.length, 1, `${label} order cardinality ${orderId}`)
    const trades = db.tradeRows.filter(({ order_id: id }) => id === orderId)
    if (orders[0].status === 'FILLED') {
      assert.equal(trades.length, 1, `${label} filled order must trade exactly once ${orderId}`)
    } else if (orders[0].status === 'CANCELED') {
      assert.equal(trades.length, 0, `${label} canceled order must not trade ${orderId}`)
    } else {
      assert(trades.length <= 1, `${label} trade cardinality ${orderId}`)
    }
    if (!ACTIVE_ORDER_STATUSES.has(orders[0].status)) {
      assert.equal(Number(orders[0].hold_amount ?? 0), 0, `${label} hold released once`)
    }
    const order = orders[0]
    const spot = order.product_type === 'CRYPTO_SPOT'
    const spotOrderLedger = spot
      ? db.assetLedgerRows.filter(({ reference_type: type, reference_id: id }) => (
          type === 'ORDER' && id === order.id
        ))
      : []
    const spotLocks = spotOrderLedger.filter(({ entry_type: type }) => type === 'SPOT_ORDER_LOCK')
    if (spot) assert.equal(spotLocks.length, 1, `${label} Spot ledger lock ${order.id}`)
    if (spot && order.status === 'CANCELED') {
      assert.equal(trades.length, 0, `${label} canceled Spot order must not trade`)
      const releases = spotOrderLedger.filter(({ entry_type: type }) => type === 'SPOT_ORDER_RELEASE')
      assert.equal(releases.length, 1, `${label} Spot ledger release ${order.id}`)
      assert(
        withinTolerance(releases[0].amount, String(-Number(spotLocks[0].amount)), '0.00000001'),
        `${label} Spot release amount ${order.id}`
      )
      assert.equal(
        spotOrderLedger.filter(({ entry_type: type }) => type === 'ORDER_RELEASE').length,
        0,
        `${label} canceled Spot order must not add fill release`
      )
    }
    for (const trade of trades) {
      if (spot) {
        const side = String(trade.side ?? order.side).toUpperCase()
        const expectedTypes = side === 'BUY'
          ? ['SPOT_BUY_CREDIT', 'SPOT_BUY_DEBIT', 'TRADE_FEE']
          : ['SPOT_SELL_CREDIT', 'SPOT_SELL_DEBIT', 'TRADE_FEE']
        const tradeLedger = db.assetLedgerRows.filter(({ reference_type: referenceType, reference_id: referenceId }) => (
          referenceType === 'TRADE' && referenceId === trade.id
        ))
        const actualTypes = tradeLedger.map(({ entry_type: type }) => type).sort()
        assert.deepEqual(actualTypes, expectedTypes, `${label} Spot ledger ${trade.id}`)
        const expected = side === 'BUY'
          ? {
              SPOT_BUY_DEBIT: { asset: 'USDT', amount: -Number(trade.lots) * Number(trade.price) },
              SPOT_BUY_CREDIT: { asset: 'BTC', amount: Number(trade.lots) },
              TRADE_FEE: { asset: trade.fee_asset ?? 'USDT', amount: -Number(trade.fee) }
            }
          : {
              SPOT_SELL_DEBIT: { asset: 'BTC', amount: -Number(trade.lots) },
              SPOT_SELL_CREDIT: { asset: 'USDT', amount: Number(trade.lots) * Number(trade.price) },
              TRADE_FEE: { asset: trade.fee_asset ?? 'USDT', amount: -Number(trade.fee) }
            }
        for (const entry of tradeLedger) {
          const oracle = expected[entry.entry_type]
          assert.equal(entry.asset, oracle.asset, `${label} ${entry.entry_type} asset`)
          assert(
            withinTolerance(entry.amount, String(oracle.amount), '0.00000001'),
            `${label} ${entry.entry_type} amount`
          )
        }
        if (side === 'BUY') {
          const releases = spotOrderLedger.filter(({ entry_type: type }) => type === 'ORDER_RELEASE')
          assert.equal(releases.length, 1, `${label} Spot ledger fill release ${order.id}`)
          assert.equal(
            spotOrderLedger.filter(({ entry_type: type }) => type === 'SPOT_ORDER_RELEASE').length,
            0,
            `${label} filled Spot order must not add cancel release`
          )
          const spent = tradeLedger.filter(({ asset }) => asset === spotLocks[0].asset)
            .reduce((sum, { amount }) => sum + Number(amount), 0)
          assert(
            withinTolerance(
              releases[0].amount,
              String(-Number(spotLocks[0].amount) + spent),
              '0.00000001'
            ),
            `${label} Spot fill release amount ${order.id}`
          )
        }
      } else {
        assert.equal(db.cashLedgerRows.filter(({
          entry_type: entryType,
          operation_type: operationType,
          reference_type: referenceType,
          reference_id: referenceId
        }) => (
          (operationType ?? entryType) === 'TRADE_FEE'
            && referenceType === 'TRADE'
            && referenceId === trade.id
        )).length, 1, `${label} fee ledger cardinality ${trade.id}`)
      }
    }
  }
  if (positionId) {
    const positions = db.positionRows.filter(({ id }) => id === positionId)
    assert(positions.length <= 1, `${label} position cardinality`)
    assert(positions.every(({ lots, quantity }) => Number(lots ?? quantity ?? 0) >= 0), `${label} no reverse position`)
    const targetTrades = db.tradeRows.filter(({ order_id: orderId }) => orderIds.includes(orderId))
    if (orderIds.length > 0) {
      assert.equal(targetTrades.length, 1, `${label} one terminal position trade`)
      const realized = String(targetTrades[0].realized_pnl ?? '0')
      const pnlRows = db.cashLedgerRows.filter(({
        entry_type: entryType,
        operation_type: operationType,
        reference_type: referenceType,
        reference_id: referenceId
      }) => (
        (operationType ?? entryType) === 'TRADE_PNL'
          && referenceType === 'POSITION'
          && referenceId === positionId
      ))
      const expectedPnlRows = Number(realized) === 0 ? 0 : 1
      assert.equal(
        pnlRows.length,
        expectedPnlRows,
        `${label} realized PnL ledger cardinality`
      )
      if (expectedPnlRows === 1) {
        assert(
          withinTolerance(pnlRows[0].amount, realized, '0.00000001'),
          `${label} realized PnL ledger amount`
        )
      }
    }
  }
  return db
}

export function assertCancelRaceWinner(capture, orderId, label) {
  const response = parsedResponse(capture)?.data ?? parsedResponse(capture)
  assert.equal(response?.items?.length, 1, `${label} must return exactly one batch item`)
  const matches = (response?.items ?? []).filter((item) => item.orderId === orderId)
  assert.equal(matches.length, 1, `${label} must return the target cancel item exactly once`)
  assert.deepEqual({
    positionId: matches[0].positionId ?? null,
    orderId: matches[0].orderId ?? null,
    status: matches[0].status ?? null,
    errorCode: matches[0].errorCode ?? null,
    message: matches[0].message ?? null
  }, {
    positionId: null,
    orderId,
    status: 'CANCELED',
    errorCode: null,
    message: null
  }, `${label} must cancel only the target order`)
  return matches[0]
}

async function proveReplay(scope, capture, label, mutate, expectedConflictCode) {
  assert(capture?.rawRequest, `${label} rawRequest`)
  const before = await scope.context.api.snapshotAccount(scope.page)
  const same = await replayCapturedMutation(scope, capture, (body) => body)
  const afterSame = await scope.context.api.snapshotAccount(scope.page)
  assert.deepEqual(
    tradingStateFingerprint(afterSame),
    tradingStateFingerprint(before),
    `${label} same fingerprint must not duplicate state`
  )
  const probe = {
    action: label,
    idempotencyKey: capture.idempotencyKey ?? parsedRequest(capture)?.requestId,
    sameFingerprint: { status: same.status, requestRef: same.requestRef },
    explanation: 'same fingerprint replays one business result'
  }
  if (mutate) {
    const conflict = await replayCapturedMutation(scope, capture, mutate, {
      expectFailure: true,
      reason: `${label} different fingerprint conflict`
    })
    const afterConflict = await scope.context.api.snapshotAccount(scope.page)
    assert.deepEqual(
      tradingStateFingerprint(afterConflict),
      tradingStateFingerprint(before),
      `${label} different fingerprint must not mutate state`
    )
    probe.differentFingerprint = {
      status: conflict.status,
      code: assertReplayConflict(conflict, label, expectedConflictCode),
      requestRef: conflict.requestRef
    }
  } else {
    probe.differentFingerprint = {
      status: 'NOT_APPLICABLE',
      reasonCode: 'REQUEST_HAS_NO_MUTABLE_FINGERPRINT_FIELDS'
    }
  }
  scope.replayProbes.push(probe)
  return probe
}

async function withRapidDoubleClick(scope, page, label, targetKind, action) {
  const key = '__p0RapidDoubleClickProbe'
  const source = rapidDoubleClickScript(key, targetKind)
  const start = page.p0Evidence.cursor
  const installed = await page.send('Page.addScriptToEvaluateOnNewDocument', { source })
  await page.send('Runtime.evaluate', { expression: source })
  let capture
  try {
    capture = await action()
    await delay(250)
  } catch (error) {
    await page.send('Runtime.evaluate', {
      expression: `window[${JSON.stringify(key)}]?.dispose?.(); delete window[${JSON.stringify(key)}]`
    }).catch(() => {})
    throw error
  } finally {
    await page.send('Page.removeScriptToEvaluateOnNewDocument', {
      identifier: installed.identifier
    }).catch(() => {})
  }
  const state = await page.evaluate((probeKey) => {
    const probe = window[probeKey]
    const snapshot = probe?.state ? { ...probe.state } : null
    probe?.dispose?.()
    delete window[probeKey]
    return snapshot
  }, key)
  assert(state?.matched, `${label} rapid second click target`)
  assert(state.secondAttempted, `${label} rapid second click attempt`)
  assert(state.guarded, `${label} UI pending state must guard the second click`)
  const method = capture?.rawRequest?.method
  const url = capture?.rawRequest?.url
  assert(method && url, `${label} captured request identity`)
  const matching = page.p0Evidence.requests.filter((request) => (
    request.cursor > start && request.method === method && request.url === url
  ))
  assert.equal(matching.length, 1, `${label} rapid double click must send exactly one mutation`)
  scope.contractProbes.push({
    kind: 'UI_PENDING_DOUBLE_CLICK_GUARD',
    status: 'PASS',
    action: label,
    requestRef: capture.requestRef,
    requestCount: matching.length,
    secondAttempted: state.secondAttempted,
    guarded: state.guarded
  })
  return capture
}

function rapidDoubleClickScript(key, targetKind) {
  return `(() => {
    const key = ${JSON.stringify(key)};
    window[key]?.dispose?.();
    const kind = ${JSON.stringify(targetKind)};
    const state = { matched: false, secondAttempted: false, guarded: false };
    const matches = (button) => {
      if (kind === 'order-confirm') {
        const dialog = button.closest('section[role="dialog"]');
        return Boolean(dialog?.querySelector(':scope > dl') && button.matches('footer button:last-child'));
      }
      if (kind === 'position-confirm') {
        const dialog = button.closest('section[role="dialog"]');
        return Boolean(dialog?.querySelector('[role="tablist"][aria-label="Position action type"]')
          && button.matches('footer button[type="submit"]'));
      }
      if (kind === 'transfer-confirm') return button.textContent?.trim() === 'Confirm transfer';
      if (kind === 'reset-confirm') return button.textContent?.trim() === 'Confirm reset';
      if (kind === 'cancel-all') {
        return ['Cancel all orders', '全部撤单', 'すべての注文をキャンセル']
          .includes(button.textContent?.trim());
      }
      return false;
    };
    const listener = (event) => {
      const button = event.target?.closest?.('button');
      if (state.matched || !button || !matches(button)) return;
      state.matched = true;
      setTimeout(() => {
        state.secondAttempted = true;
        state.guarded = !button.isConnected || button.disabled
          || button.getAttribute('aria-disabled') === 'true';
        if (!state.guarded) button.click();
      }, 0);
    };
    document.addEventListener('click', listener, true);
    window[key] = {
      state,
      dispose: () => document.removeEventListener('click', listener, true)
    };
  })()`
}

async function replayCapturedMutation(scope, capture, mutate, options = {}) {
  const raw = capture?.rawRequest
  assert(raw && typeof raw.url === 'string', 'captured raw request is required')
  const body = raw.postData ? JSON.parse(raw.postData) : undefined
  const payload = mutate ? mutate(structuredClone(body)) : body
  const replay = await scope.context.ui.withCapturedMutation(
    scope.page,
    ({ method, url }) => method === raw.method && url === raw.url,
    () => scope.page.evaluate(async (request, nextBody) => {
      const token = localStorage.getItem('fx-platform-auth-token')
      const response = await fetch(request.url, {
        method: request.method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: nextBody === undefined ? undefined : JSON.stringify(nextBody)
      })
      return { status: response.status, body: await response.text() }
    }, { method: raw.method, url: raw.url }, payload)
  )
  if (options.expectFailure) {
    assert(replay.status >= 400 && replay.status < 500, `${options.reason} must return 4xx`)
    scope.page.allowHttpError(replay.requestRef, options.reason)
  } else {
    assertSuccess(replay, 'same fingerprint replay')
  }
  return replay
}

function mutateQuantity(body) {
  for (const key of ['quantity', 'amount', 'closeQuantity', 'marginAmount']) {
    if (body?.[key] !== undefined && body[key] !== null) {
      return { ...body, [key]: String(Number(body[key]) * 2) }
    }
  }
  return { ...body, quantity: '0.001' }
}

function mutateAmount(body) {
  return { ...body, amount: String(Number(body?.amount ?? 1) + 1) }
}

export function mutateExpectedOrderIds(body) {
  assert(Array.isArray(body?.expectedOrderIds), 'cancel replay requires expectedOrderIds')
  return { ...body, expectedOrderIds: [...body.expectedOrderIds, randomUUID()].sort() }
}

export function mutateExpectedDemoGeneration(body) {
  const generation = Number(body?.expectedDemoGeneration)
  assert(Number.isSafeInteger(generation) && generation >= 0, 'reset replay requires expectedDemoGeneration')
  return { ...body, expectedDemoGeneration: generation + 1 }
}

async function restartWithEvidence(scope, duringDowntime) {
  const result = await scope.context.services.restartBackend({ duringDowntime })
  const ownership = await scope.context.services.assertOwnedPorts({ ports: [18086, 5199, 5200] })
  scope.fixtureActions.push({
    action: 'restart-owned-backend',
    restartBackend: true,
    duringDowntime: true,
    ownership
  })
  return result
}

async function openMobileSession(scope, suffix) {
  const browser = await scope.context.ui.launchBrowser()
  let page
  try {
    page = await scope.context.ui.createEvidencePage(browser, {
      caseId: `${scope.definition.id}-${suffix}-mobile`,
      viewport: MOBILE
    })
    const login = await scope.context.ui.loginViaUi(
      page,
      scope.context.userFactory(scope.definition.id)
    )
    scope.userActions.push({
      action: `${suffix}-mobile-login-via-ui`,
      requestRef: login.requestRef
    })
    return { browser, page }
  } catch (error) {
    await page?.close().catch(() => {})
    await browser.close().catch(() => {})
    throw error
  }
}

async function closeMobileSession(session, label) {
  if (!session) return
  let failure
  try {
    session.page.assertEvidenceClean(label)
  } catch (error) {
    failure = error
  }
  await session.page.close().catch((error) => { failure ??= error })
  await session.browser.close().catch((error) => { failure ??= error })
  if (failure) throw failure
}

async function multiPageCheckpoint(scope, name, pages) {
  const checkpoint = await scope.context.evidence.captureCheckpoint(scope.context, name, {
    caseId: scope.definition.id,
    pages
  })
  scope.checkpoints.push(checkpoint)
  return checkpoint
}

async function reloadAuthenticatedPage(page) {
  const url = await page.evaluate(() => window.location.href)
  await page.navigate(url)
  await page.waitForFunction(
    () => (document.body?.innerText?.trim().length ?? 0) > 40,
    'authenticated page reload'
  )
}

export function allowExpectedRaceErrors(page, window) {
  const {
    start,
    end,
    requests: expectedRequests,
    expectedCodes = RACE_LOSER_CODES,
    reason
  } = window
  const allowedCodes = expectedCodes instanceof Set ? expectedCodes : new Set(expectedCodes)
  assert(Number.isInteger(start) && Number.isInteger(end) && end >= start, 'race cursor window')
  assert(Array.isArray(expectedRequests) && expectedRequests.length > 0, 'race request allowlist')
  const allowed = []
  for (const request of page.p0Evidence.requests) {
    const overlaps = request.cursor <= end
      && Number.isInteger(request.responseCursor)
      && request.responseCursor > start
    const exactRequest = expectedRequests.some((candidate) => (
      request.method === candidate.method
        && matchesUrl(request.url, candidate.url)
    ))
    if (!overlaps || !exactRequest || request.response?.status < 400 || request.response?.status >= 500) continue
    let code
    try {
      const body = JSON.parse(request.responseBody ?? '{}')
      code = body.code ?? body.data?.code ?? body.error?.code
    } catch (error) {
      throw new Error(`unexpected race response ${request.url}: invalid JSON`, { cause: error })
    }
    if (!allowedCodes.has(code)) {
      throw new Error(`unexpected race response ${request.url}: ${String(code)}`)
    }
    page.allowHttpError(request.requestId, `${reason}: ${code}`)
    allowed.push(request.requestId)
  }
  return allowed
}

function matchesUrl(actual, expected) {
  if (expected instanceof RegExp) {
    expected.lastIndex = 0
    return expected.test(actual)
  }
  return actual === expected
}

async function revealRouteRecord(page, id) {
  const selector = `[data-order-id="${id}"], [data-position-id="${id}"]`
  for (let index = 0; index < 4; index += 1) {
    if (await page.evaluate((value) => Boolean(document.querySelector(value)), selector)) return
    const clicked = await page.evaluate((tabIndex) => {
      const tablist = [...document.querySelectorAll('[role="tablist"]')]
        .find((candidate) => candidate.querySelectorAll(':scope > button').length > 1)
      const button = tablist?.querySelectorAll(':scope > button')[tabIndex]
      button?.click()
      return Boolean(button)
    }, index)
    if (!clicked) break
    await delay(150)
  }
  assert.equal(
    await page.evaluate((value) => Boolean(document.querySelector(value)), selector),
    true,
    `route record ${id} must be visible`
  )
}

async function assertRoute(page, route) {
  await page.waitForFunction(
    (expected) => window.location.pathname === expected
      && (document.body?.innerText?.trim().length ?? 0) > 40,
    `real route ${route}`,
    route
  )
}

async function waitForFilled(context, page, before, capture, symbol, label) {
  return waitForAccount(context, page, `${label} fill`, (candidate) => {
    const order = orderForCapture(before, candidate, capture)
    const trade = order && candidate.trades.find(({ orderId }) => orderId === order.id)
    return order?.symbol === symbol && order.status === 'FILLED' && trade
      ? { snapshot: candidate, order, trade }
      : false
  })
}

async function waitForAccount(context, page, description, predicate, timeoutMs = 30000) {
  return waitForValue(async () => {
    const snapshot = await context.api.snapshotAccount(page)
    return predicate(snapshot)
  }, description, timeoutMs)
}

async function waitForValue(action, description, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let latestError
  while (Date.now() < deadline) {
    try {
      const value = await action()
      if (value) return value
    } catch (error) {
      latestError = error
    }
    await delay(100)
  }
  throw new Error(
    `Timed out waiting for ${description}${latestError ? `: ${latestError.message}` : ''}`,
    latestError ? { cause: latestError } : undefined
  )
}

function waitUntil(action, description, timeoutMs = 30000) {
  return waitForValue(action, description, timeoutMs)
}

function orderForCapture(before, after, capture) {
  const created = recordsAfter(before, after, 'orders')
  return exactOrderForCapture(before, after, capture) ?? created.at(-1)
}

function exactOrderForCapture(before, after, capture) {
  return recordsAfter(before, after, 'orders').find((order) => (
    capture.idempotencyKey
      && [order.clientOrderId, order.idempotencyKey].includes(capture.idempotencyKey)
  ))
}

function recordsAfter(before, after, field) {
  const known = new Set((before?.[field] ?? []).map(({ id }) => id))
  return (after?.[field] ?? []).filter(({ id }) => !known.has(id))
}

function activeOrders(snapshot, symbol) {
  return (snapshot.orders ?? []).filter((order) => (
    (!symbol || order.symbol === symbol) && ACTIVE_ORDER_STATUSES.has(order.status)
  ))
}

function openPositions(snapshot, symbol) {
  return (snapshot.positions ?? []).filter((position) => (
    String(position.instrumentType ?? '').toUpperCase() !== 'SPOT'
      && (!symbol || position.symbol === symbol)
      && (position.status === undefined || position.status === 'OPEN')
  ))
}

function findWallet(snapshot, walletType, asset, required = true) {
  const wallet = (snapshot.wallets ?? []).find((candidate) => (
    candidate.walletType === walletType && candidate.asset === asset
  ))
  if (required) assert(wallet, `wallet ${walletType}/${asset} is required`)
  return wallet
}

function positionSide(position) {
  const side = String(position.positionSide ?? position.side ?? '').toUpperCase()
  if (side === 'LONG' || side === 'SHORT') return side
  return Number(position.lots) < 0 ? 'SHORT' : 'LONG'
}

function alignedPrice(market, value) {
  return alignPriceToTick(
    Number(value).toFixed(12).replace(/0+$/u, '').replace(/\.$/u, ''),
    market.rules
  )
}

function assertPositiveNumber(value, label) {
  assert(Number.isFinite(Number(value)) && Number(value) > 0, `${label} must be positive`)
}

function parsedRequest(capture) {
  if (!capture?.rawRequest?.postData) return undefined
  return JSON.parse(capture.rawRequest.postData)
}

function parsedResponse(capture) {
  if (capture?.parsedResponse !== undefined) return capture.parsedResponse
  const body = capture?.networkEvidence?.responseBody
  if (typeof body !== 'string' || body.length === 0) return undefined
  try {
    return JSON.parse(body)
  } catch {
    return body
  }
}

function responseCode(capture) {
  const response = parsedResponse(capture)
  return response?.code ?? response?.data?.code ?? response?.error?.code
}

function scalarResult(raw) {
  return String(raw ?? '')
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !/^(?:INSERT|UPDATE|DELETE|SELECT)\b/iu.test(line))
    .at(-1) ?? ''
}

async function jsonResult(context, sql) {
  const raw = await context.db.query(sql)
  const line = String(raw ?? '')
    .split(/\r?\n/u)
    .map((candidate) => candidate.trim())
    .filter(Boolean)
    .filter((candidate) => !/^(?:INSERT|UPDATE|DELETE|SELECT)\b/iu.test(candidate))
    .at(-1)
  assert(line, 'PostgreSQL JSON result required')
  const parsed = JSON.parse(line)
  return Array.isArray(parsed) ? parsed : [parsed]
}

function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

function assertSuccess(capture, label) {
  assert(
    capture.status >= 200 && capture.status < 300,
    `${label} must return 2xx, got ${capture.status}`
  )
}

function subrunIds(scope) {
  return new Set(scope.definition.requiredSubruns.map(({ id }) => id))
}

function slug(value) {
  return String(value).toLowerCase().replaceAll(/[^a-z0-9]+/gu, '-').replaceAll(/^-|-$/gu, '')
}

function finish(scope, snapshot, evidence) {
  scope.oracleEvidence.push(evidence)
  return { finalSnapshot: snapshot }
}
