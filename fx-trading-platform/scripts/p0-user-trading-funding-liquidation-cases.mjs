import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'

import {
  isAuthorityMarketSnapshot,
  P0PublicProviderUnavailableError,
  runSingleUserCoreCase,
  stepAlignedQuantity
} from './p0-user-trading-core-cases.mjs'
import {
  DEMO_RATES,
  alignPriceToTick,
  crossLiquidationOracle,
  fundingSettlementOracle,
  isolatedLiquidationOracle,
  liquidationFeeOracle,
  marketFillOracle,
  perpCloseOracle,
  roundDecimal,
  tolerancesFromRules,
  withinTolerance
} from './p0-user-trading-oracles.mjs'

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
export function runFund01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runPairedFundingJourney(scope, {
      symbol: 'BTCUSDT-PERP',
      marginMode: 'CROSS',
      rate: '0.0001000000'
    })
  ))
}

export function runFund02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runPairedFundingJourney(scope, {
      symbol: 'ETHUSDT-PERP',
      marginMode: 'ISOLATED',
      rate: '-0.0001000000'
    })
  ))
}

export function runFund03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runFundingSourceJourney)
}

export function runFund04(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runFundingRecoveryJourney)
}

export function runLiq01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runIsolatedLiquidationJourney(scope, 'LONG')
  ))
}

export function runLiq02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runIsolatedLiquidationJourney(scope, 'SHORT')
  ))
}

export function runLiq03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runCrossLiquidationJourney)
}

export function runLiq04(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runShortfallJourney)
}

export const CASE_HANDLERS = Object.freeze({
  runFund01,
  runFund02,
  runFund03,
  runFund04,
  runLiq01,
  runLiq02,
  runLiq03,
  runLiq04
})

export function assertFundingSettlementContract({
  settlement,
  expected,
  expectedPositionId,
  expectedFundingTime,
  expectedSource,
  expectedFundingRate,
  expectedMarkPrice,
  expectedPositionSide,
  expectedMarginMode
}) {
  assert(settlement && typeof settlement === 'object', 'funding settlement required')
  assert.equal(settlement.positionId, expectedPositionId, 'funding positionId')
  assert.equal(
    Date.parse(settlement.fundingTime),
    Date.parse(expectedFundingTime),
    'fundingTime'
  )
  assert.equal(String(settlement.source).toLowerCase(), expectedSource.toLowerCase(), 'source')
  assert.equal(settlement.asset, 'USDT', 'asset')
  assert.equal(decimal10(settlement.fundingRate), decimal10(expectedFundingRate), 'fundingRate')
  assert.equal(decimal10(settlement.markPrice), decimal10(expectedMarkPrice), 'markPrice')
  assert.equal(settlement.positionSide, expectedPositionSide, 'positionSide')
  assert.equal(settlement.marginMode, expectedMarginMode, 'marginMode')
  for (const [actualField, expectedField] of [
    ['amount', 'settlementAmount'],
    ['balanceAfter', 'balanceAfter'],
    ['isolatedMarginAfter', 'isolatedMarginAfter'],
    ['shortfall', 'shortfall']
  ]) {
    assert.equal(
      decimal8(settlement[actualField]),
      decimal8(expected[expectedField]),
      actualField
    )
  }
  if (expected.ledgerAmount === null) {
    assert.equal(settlement.ledgerEntryId, null, 'ledgerEntryId')
  } else {
    assert.match(String(settlement.ledgerEntryId ?? ''), /^[0-9a-f-]{36}$/iu, 'ledgerEntryId')
  }
}

export function assertLiquidationLifecycleContract({
  positionId,
  orders,
  trades,
  ledger,
  expectedFeeCharged
}) {
  const liquidationOrders = orders.filter((order) => (
    idOf(order, 'parentPositionId', 'parent_position_id') === positionId
      && valueOf(order, 'origin', 'order_origin') === 'LIQUIDATION'
      && valueOf(order, 'status') === 'FILLED'
  ))
  assert.equal(liquidationOrders.length, 1, `${positionId} exactly one liquidation order`)
  const orderId = idOf(liquidationOrders[0], 'id')
  const liquidationTrades = trades.filter((trade) => (
    idOf(trade, 'orderId', 'order_id') === orderId
  ))
  assert.equal(liquidationTrades.length, 1, `${positionId} exactly one liquidation trade`)
  const feeEntries = ledger.filter((entry) => (
    valueOf(entry, 'type', 'entryType', 'entry_type') === 'LIQUIDATION_FEE'
      && [orderId, positionId].includes(idOf(entry, 'referenceId', 'reference_id'))
  ))
  const expectedFeeEntries = expectedFeeCharged === undefined
    || moneyUnits(expectedFeeCharged) > 0n ? 1 : 0
  assert.equal(
    feeEntries.length,
    expectedFeeEntries,
    `${positionId} liquidation fee ledger cardinality`
  )
  return {
    order: liquidationOrders[0],
    trade: liquidationTrades[0],
    ledger: feeEntries[0] ?? null
  }
}

export function assertLiquidationTradeContract({ position, trade, market }) {
  const side = positionSide(position)
  const closingSide = side === 'LONG' ? 'SELL' : 'BUY'
  const rules = rulesFor(market)
  const tolerances = tolerancesFromRules(rules)
  assert.equal(valueOf(trade, 'side'), closingSide, 'liquidation closing side')
  assertDecimalClose(
    valueOf(trade, 'lots', 'base_quantity', 'quantity'),
    position.lots,
    tolerances.quantity,
    'liquidation full quantity'
  )
  const fill = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: closingSide,
    bid: quoteDecimal(market, 'bid'),
    ask: quoteDecimal(market, 'ask')
  })
  assertDecimalClose(valueOf(trade, 'price'), fill.filledPrice, tolerances.price, 'liquidation fill price')
  const oracle = perpCloseOracle({
    side,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closeFillPrice: fill.filledPrice,
    closeFeeRate: takerFeeRateForMarket(market),
    rules
  })
  assertDecimalClose(
    valueOf(trade, 'realizedPnl', 'realized_pnl'),
    oracle.grossRealizedPnl,
    oracle.tolerances.amount,
    'liquidation realized PnL'
  )
  assertDecimalClose(valueOf(trade, 'fee'), oracle.closeFee, oracle.tolerances.amount, 'liquidation taker fee')
  return { ...oracle, fillPrice: fill.filledPrice }
}

export function isolatedLiquidationCollectionCapacity({ position, tradeOracle }) {
  const tradeCashDelta = moneyUnits(tradeOracle.grossRealizedPnl)
    - moneyUnits(tradeOracle.closeFee)
  const coreDebit = tradeCashDelta < 0n ? -tradeCashDelta : 0n
  const margin = moneyUnits(position.marginHeld)
  return moneyText(margin > coreDebit ? margin - coreDebit : 0n)
}

export function assertCrossShortfallContract({
  fixture,
  trades,
  charges,
  shortfalls,
  accountId,
  audits
}) {
  assert(fixture && Number.isFinite(Number(fixture.capacity)), 'shortfall fixture capacity')
  assert(Array.isArray(trades) && Array.isArray(charges), 'shortfall rows required')
  const orderIds = charges.map((row) => idOf(row, 'orderId', 'order_id'))
  assert.equal(new Set(orderIds).size, charges.length, 'one cross charge per order')
  assert.equal(trades.length, charges.length, 'one liquidation trade per cross charge')
  for (const orderId of orderIds) {
    assert.equal(
      trades.filter((row) => idOf(row, 'orderId', 'order_id') === orderId).length,
      1,
      `${orderId} cross liquidation trade`
    )
  }
  for (const row of charges) assert.equal(row.status, 'SETTLED', 'cross charge status')
  const cashAfterCore = moneyUnits(fixture.capacity) + trades.reduce((sum, trade) => (
    sum + moneyUnits(valueOf(trade, 'realizedPnl', 'realized_pnl'))
      - moneyUnits(valueOf(trade, 'fee'))
  ), 0n)
  const uncoveredCoreDebit = cashAfterCore < 0n ? -cashAfterCore : 0n
  const feeDue = charges.reduce(
    (sum, row) => sum + moneyUnits(valueOf(row, 'feeDue', 'fee_due')),
    0n
  )
  const feeCharged = charges.reduce(
    (sum, row) => sum + moneyUnits(valueOf(row, 'feeCharged', 'fee_charged')),
    0n
  )
  const collectionCapacity = cashAfterCore > 0n ? cashAfterCore : 0n
  assert.equal(
    feeCharged,
    feeDue < collectionCapacity ? feeDue : collectionCapacity,
    'cross liquidation fee charged from independent capacity'
  )
  const uncollectedFee = feeDue - feeCharged
  const bankruptcyShortfall = uncoveredCoreDebit + uncollectedFee
  assert.equal(
    shortfalls.length,
    bankruptcyShortfall > 0n ? 1 : 0,
    'BANKRUPTCY_SHORTFALL cardinality'
  )
  if (bankruptcyShortfall > 0n) {
    const shortfall = shortfalls[0]
    assert.equal(
      moneyUnits(shortfall.amount),
      bankruptcyShortfall,
      'BANKRUPTCY_SHORTFALL exact amount'
    )
    assert.equal(decimal8(shortfall.balance_after), decimal8('0'), 'BANKRUPTCY_SHORTFALL balance after')
    assert.equal(
      shortfall.reference_type,
      'CROSS_LIQUIDATION_SETTLEMENT',
      'BANKRUPTCY_SHORTFALL reference type'
    )
    assert.match(String(shortfall.reference_id ?? ''), /^[0-9a-f-]{36}$/iu, 'shortfall settlement id')
    const matchingAudits = (audits ?? []).filter((row) => (
      row.action === 'BANKRUPTCY_SHORTFALL'
        && row.target_type === 'ACCOUNT'
        && String(row.target_id) === String(accountId)
    ))
    assert.equal(matchingAudits.length, 1, 'BANKRUPTCY_SHORTFALL audit cardinality')
    const details = typeof matchingAudits[0].details === 'string'
      ? JSON.parse(matchingAudits[0].details)
      : matchingAudits[0].details
    assert.equal(String(details?.settlementId), String(shortfall.reference_id), 'shortfall audit settlement id')
    assert.equal(moneyUnits(details?.amount), bankruptcyShortfall, 'shortfall audit amount')
  }
  return {
    cashAfterCore: moneyText(cashAfterCore),
    uncoveredCoreDebit: moneyText(uncoveredCoreDebit),
    feeDue: moneyText(feeDue),
    feeCharged: moneyText(feeCharged),
    uncollectedFee: moneyText(uncollectedFee),
    bankruptcyShortfall: moneyText(bankruptcyShortfall)
  }
}

async function runPairedFundingJourney(scope, options) {
  const { context, page, definition } = scope
  const secondary = await createSecondaryUser(scope, `${definition.id}-SHORT`)
  try {
    await prepareFixedFunding(scope, options.symbol, options.rate)
    const market = await freezeMarket(scope, options.symbol)
    const quantity = stepAlignedQuantity(market, 0.01)
    const long = await openPerpetual(scope, page, {
      symbol: options.symbol,
      side: 'LONG',
      marginMode: options.marginMode,
      leverage: 10,
      quantity,
      label: `${definition.id}-long`
    })
    const short = await openPerpetual(scope, secondary.page, {
      symbol: options.symbol,
      side: 'SHORT',
      marginMode: options.marginMode,
      leverage: 10,
      quantity,
      label: `${definition.id}-short`
    })
    const cycle = await createFundingCycleFixture(scope, {
      symbol: options.symbol,
      rate: options.rate,
      markPrice: markPrice(market),
      dueInMs: 1800
    })
    await shiftPositionBeforeCycle(scope, long.snapshot.account.id, long.position.id, cycle)
    await shiftPositionBeforeCycle(scope, short.snapshot.account.id, short.position.id, cycle)

    const longCursor = context.events.snapshotFrames(page).length
    const shortCursor = context.events.snapshotFrames(secondary.page).length
    const [longSettled, shortSettled] = await Promise.all([
      waitForFundingSettlement(context, page, long.snapshot, long.position, cycle),
      waitForFundingSettlement(context, secondary.page, short.snapshot, short.position, cycle)
    ])
    const [longEvents, shortEvents] = await Promise.all([
      actionEvents(
        context,
        page,
        longCursor,
        options.marginMode === 'CROSS' ? ['FUNDING_SETTLED', 'BALANCE_UPDATED'] : ['FUNDING_SETTLED'],
        `${definition.id} LONG`
      ),
      actionEvents(
        context,
        secondary.page,
        shortCursor,
        options.marginMode === 'CROSS' ? ['FUNDING_SETTLED', 'BALANCE_UPDATED'] : ['FUNDING_SETTLED'],
        `${definition.id} SHORT`
      )
    ])

    const longOracle = assertFundingOutcome(scope, long, longSettled, cycle)
    const shortOracle = assertFundingOutcome(scope, short, shortSettled, cycle)
    assert.equal(Number(longSettled.settlement.amount) < 0, Number(options.rate) > 0)
    assert.equal(Number(shortSettled.settlement.amount) > 0, Number(options.rate) > 0)
    await Promise.all([
      assertFundingVisibleViaUi(page, longSettled.settlement),
      assertFundingVisibleViaUi(secondary.page, shortSettled.settlement)
    ])

    const [longDb, shortDb] = await Promise.all([
      context.db.snapshotTradingRows(long.snapshot.account.id),
      context.db.snapshotTradingRows(short.snapshot.account.id)
    ])
    assertFundingDatabase(longDb, longSettled.settlement, longOracle)
    assertFundingDatabase(shortDb, shortSettled.settlement, shortOracle)
    scope.dbEvidence.push(shortDb)
    scope.apiEvidence.push(safeAccountEvidence(shortSettled.snapshot))
    scope.oracleEvidence.push({
      kind: 'PAIRED_FUNDING_CASHFLOW',
      rate: options.rate,
      cycle,
      long: longOracle,
      short: shortOracle,
      stomp: { long: longEvents, short: shortEvents }
    })
    await scope.capture('funding-settled', longSettled.snapshot, {
      apiEvidence: [safeAccountEvidence(longSettled.snapshot), safeAccountEvidence(shortSettled.snapshot)],
      dbEvidence: [longDb, shortDb]
    })

    const [longClosed, shortClosed] = await Promise.all([
      closePositionViaUi(scope, page, longSettled.snapshot, long.position, `${definition.id}-long-cleanup`),
      closePositionViaUi(scope, secondary.page, shortSettled.snapshot, short.position, `${definition.id}-short-cleanup`)
    ])
    const shortFinalDb = await context.db.snapshotTradingRows(short.snapshot.account.id)
    assert.equal(openPositions(shortClosed, options.symbol).length, 0, 'secondary user cleanup')
    assert.equal(Number(shortFinalDb.openPositions), 0, 'secondary DB cleanup')
    scope.dbEvidence.push(shortFinalDb)
    scope.apiEvidence.push(safeAccountEvidence(shortClosed))
    return { finalSnapshot: longClosed }
  } finally {
    await closeSecondaryUser(secondary)
  }
}

async function runFundingSourceJourney(scope) {
  const symbol = 'SOLUSDT-PERP'
  const market = await freezeMarket(scope, symbol)
  const opened = await openPerpetual(scope, scope.page, {
    symbol,
    side: 'LONG',
    marginMode: 'CROSS',
    leverage: 10,
    quantity: stepAlignedQuantity(market, 0.1),
    label: 'FUND-03-source-position'
  })
  await isolateFundingRateCursor(scope, symbol)
  const stages = [
    { provider: 'binance-usdm', enabled: ['binance-usdm', 'okx-swap', 'local-perp'] },
    { provider: 'okx-swap', enabled: ['okx-swap', 'local-perp'] }
  ]
  let latest = opened.snapshot
  const sources = []
  const publicFailures = []
  const publicTimeoutMs = 45000
  for (const stage of stages) {
    const stagePosition = positionById(latest, opened.position.id)
    assert(stagePosition, `${stage.provider} funding position remains open`)
    await shiftPositionOpenedAt(
      scope,
      opened.snapshot.account.id,
      stagePosition.id,
      new Date(Date.now() - 1000).toISOString(),
      `${stage.provider}-current-rate-bootstrap`
    )
    const configuredAt = Date.now()
    const binding = await setProviderBindings(scope, symbol, stage.enabled)
    await configureFunding(scope, symbol, {
      fundingSourcePriority: ['BINANCE', 'OKX', 'FIXED'],
      fixedFundingRate: '0.0001700000',
      fixedFundingIntervalMinutes: 525600,
      fundingStaleSeconds: 2,
      reason: `FUND-03 ${stage.provider} selection`
    })
    await removeCaseFundingRates(scope, symbol, null, 0)
    let currentRate
    let rate
    try {
      ({ currentRate, rate } = await prepareIngestedFundingCycle(scope, {
        symbol,
        providerCode: stage.provider,
        accountId: opened.snapshot.account.id,
        positionId: stagePosition.id,
        configuredAt,
        freshTimeoutMs: publicTimeoutMs,
        dueTimeoutMs: publicTimeoutMs
      }))
    } catch (error) {
      const message = String(error?.message)
      const expectedPrefix = `Timed out waiting for ${symbol} fresh ${stage.provider} funding ingestion (`
      if (!message.startsWith(expectedPrefix)) throw error
      const failure = {
        provider: stage.provider,
        status: 'TIMEOUT',
        code: 'FUNDING_INGESTION_TIMEOUT',
        asOf: null,
        configuredAt: new Date(configuredAt).toISOString(),
        timeoutMs: publicTimeoutMs,
        binding: binding.after
      }
      publicFailures.push(failure)
      sources.push({
        provider: stage.provider,
        status: 'BLOCKED',
        reason: 'PUBLIC_EXTERNAL funding source unavailable within 45 seconds',
        failure
      })
      await removeCaseFundingRates(scope, symbol, null, 0)
      continue
    }
    assert.equal(currentRate.sourceMode, 'PUBLIC_EXTERNAL')
    assert(currentRate.rawPayloadHash.length >= 32, `${stage.provider} raw payload hash`)
    const freshUntil = Date.parse(currentRate.asOf) + 2000
    assert(
      Date.parse(currentRate.createdAt) <= freshUntil,
      `${stage.provider} persisted inside freshness threshold`
    )
    const settled = await waitForFundingSettlement(
      scope.context,
      scope.page,
      latest,
      stagePosition,
      rate,
      45000
    )
    const oracle = assertFundingOutcome(
      scope,
      { ...opened, snapshot: latest, position: stagePosition },
      settled,
      rate
    )
    assert.equal(await canonicalRateCount(scope, symbol, rate.fundingTime), 1)
    await assertFundingVisibleViaUi(scope.page, settled.settlement)
    if (Date.now() <= freshUntil) await delay(freshUntil - Date.now() + 1)
    assert(Date.now() > freshUntil, `${stage.provider} becomes stale at configured boundary`)
    sources.push({
      provider: stage.provider,
      status: 'PASS',
      binding: binding.after,
      currentRate,
      rate,
      oracle,
      freshUntil: new Date(freshUntil).toISOString(),
      settlementId: settled.settlement.id
    })
    latest = settled.snapshot
    await removeCaseFundingRates(scope, symbol, null, 0)
  }

  const fixedPosition = positionById(latest, opened.position.id)
  assert(fixedPosition, 'fixed funding position remains open')
  await shiftPositionOpenedAt(
    scope,
    opened.snapshot.account.id,
    fixedPosition.id,
    new Date(Date.now() - 1000).toISOString(),
    'fixed-current-rate-bootstrap'
  )
  const fixedConfiguredAt = Date.now()
  await setProviderBindings(scope, symbol, ['local-perp'])
  await configureFunding(scope, symbol, {
    fundingSourcePriority: ['BINANCE', 'OKX', 'FIXED'],
    fixedFundingRate: '0.0001900000',
    fixedFundingIntervalMinutes: 1,
    fundingStaleSeconds: 2,
    reason: 'FUND-03 deterministic fixed fallback'
  })
  await removeCaseFundingRates(scope, symbol, null, 0)
  const { currentRate: fixedCurrent, rate: fixed } = await prepareIngestedFundingCycle(scope, {
    symbol,
    providerCode: 'fixed',
    accountId: opened.snapshot.account.id,
    positionId: fixedPosition.id,
    configuredAt: fixedConfiguredAt,
    freshTimeoutMs: 15000,
    dueTimeoutMs: 15000
  })
  assert.equal(fixedCurrent.sourceMode, 'LOCAL_SIMULATED')
  assert.equal(fixed.sourceMode, 'LOCAL_SIMULATED')
  assert.equal(decimal10(fixed.rate), decimal10('0.0001900000'), 'fixed funding rate')
  const fixedSettled = await waitForFundingSettlement(
    scope.context,
    scope.page,
    latest,
    fixedPosition,
    fixed,
    45000
  )
  const fixedOracle = assertFundingOutcome(
    scope,
    { ...opened, snapshot: latest, position: fixedPosition },
    fixedSettled,
    fixed
  )
  assert.equal(await canonicalRateCount(scope, symbol, fixed.fundingTime), 1)
  await assertFundingVisibleViaUi(scope.page, fixedSettled.settlement)
  const fixedFallback = {
    status: 'PASS',
    provider: 'fixed',
    sourceMode: fixed.sourceMode,
    settlementId: fixedSettled.settlement.id
  }
  scope.oracleEvidence.push({
    kind: 'FUNDING_SOURCE_PRIORITY',
    sources: [...sources.map((source) => source.status === 'BLOCKED'
      ? { ...source, fixedFallback }
      : source), {
      provider: 'fixed',
      status: 'PASS',
      currentRate: fixedCurrent,
      rate: fixed,
      oracle: fixedOracle,
      settlementId: fixedSettled.settlement.id
    }]
  })
  await scope.capture('source-fallback-settled', fixedSettled.snapshot)
  const finalSnapshot = await closePositionViaUi(
    scope,
    scope.page,
    fixedSettled.snapshot,
    opened.position,
    'FUND-03-cleanup'
  )
  await removeCaseFundingRates(scope, symbol, null, 0)
  await scope.capture('source-fallback-closed', finalSnapshot)
  if (publicFailures.length > 0) {
    const failures = publicFailures.map((failure) => ({ ...failure, fixedFallback }))
    throw new P0PublicProviderUnavailableError(
      failures.map(({ provider }) => provider),
      failures
    )
  }
  return { finalSnapshot }
}

async function runFundingRecoveryJourney(scope) {
  const symbol = 'XRPUSDT-PERP'
  await prepareFixedFunding(scope, symbol, '0.0002100000')
  const market = await freezeMarket(scope, symbol)
  const opened = await openPerpetual(scope, scope.page, {
    symbol,
    side: 'LONG',
    marginMode: 'CROSS',
    leverage: 10,
    quantity: stepAlignedQuantity(market, 10),
    label: 'FUND-04-position'
  })
  const first = await createFundingCycleFixture(scope, {
    symbol,
    rate: '0.0002100000',
    markPrice: markPrice(market),
    dueInMs: 1200
  })
  await shiftPositionBeforeCycle(scope, opened.snapshot.account.id, opened.position.id, first)
  const settled = await waitForFundingSettlement(
    scope.context,
    scope.page,
    opened.snapshot,
    opened.position,
    first
  )
  const firstOracle = assertFundingOutcome(scope, opened, settled, first)
  const stable = await observeFundingStable(scope, opened.position.id, first.fundingTime, 1800)
  assert.equal(stable.count, 1, 'three scheduler scans keep one settlement')
  assert.equal(stable.balanceAfter, settled.settlement.balanceAfter, 'retry balanceAfter')

  const catchup = fundingCycle({
    symbol,
    rate: '-0.0002300000',
    markPrice: markPrice(market),
    dueInMs: -1000
  })
  const webBaseUrl = scope.page.p0Options?.webBaseUrl ?? 'http://127.0.0.1:5199'
  await scope.page.navigate('about:blank')
  await scope.context.services.restartBackend({
    duringDowntime: async () => {
      await insertFundingCycle(scope, catchup)
      await shiftPositionBeforeCycle(scope, opened.snapshot.account.id, opened.position.id, catchup)
    }
  })
  scope.fixtureActions.push({ action: 'restart-backend-across-due-funding-cycle', rateId: catchup.id })
  await scope.context.ui.openTradePanel(scope.page, { product: 'perpetual', symbol })
  assert.equal(scope.page.p0Options?.webBaseUrl ?? webBaseUrl, webBaseUrl)
  const caughtUp = await waitForFundingSettlement(
    scope.context,
    scope.page,
    settled.snapshot,
    opened.position,
    catchup
  )
  const catchupPosition = positionById(settled.snapshot, opened.position.id)
  assert(catchupPosition, 'FUND-04 catch-up position remains open')
  const catchupOracle = assertFundingOutcome(
    scope,
    { ...opened, snapshot: settled.snapshot, position: catchupPosition },
    caughtUp,
    catchup
  )
  const persistedFirstSettlements = caughtUp.snapshot.fundingSettlements.filter(({ id }) => (
    id === settled.settlement.id
  ))
  assert.equal(persistedFirstSettlements.length, 1, 'FUND-04 first settlement id survives restart')
  const persistedFirstSettlement = persistedFirstSettlements[0]
  assertFundingSettlementContract({
    settlement: persistedFirstSettlement,
    expected: firstOracle,
    expectedPositionId: opened.position.id,
    expectedFundingTime: first.fundingTime,
    expectedSource: first.providerCode,
    expectedFundingRate: first.rate,
    expectedMarkPrice: first.markPrice,
    expectedPositionSide: positionSide(opened.position),
    expectedMarginMode: opened.position.marginMode
  })
  assert.equal(
    persistedFirstSettlement.balanceAfter,
    settled.settlement.balanceAfter,
    'FUND-04 first settlement balanceAfter survives restart'
  )
  assert.equal(String(caughtUp.settlement.source).toLowerCase(), 'fixed')
  assert.equal(await settlementCount(scope, opened.position.id, catchup.fundingTime), 1)

  const peer = await createAuthenticatedPeer(scope, 'FUND-04-race-peer')
  try {
    await scope.context.ui.openTradePanel(peer.page, { product: 'perpetual', symbol })
    const race = await createFundingCycleFixture(scope, {
      symbol,
      rate: '0.0002500000',
      markPrice: markPrice(market),
      dueInMs: 1800
    })
    const racePosition = positionById(caughtUp.snapshot, opened.position.id)
    assert(racePosition, 'FUND-04 race position remains open')
    await shiftPositionBeforeCycle(scope, opened.snapshot.account.id, racePosition.id, race)
    await waitForFundingBoundary(race.fundingTimeMs)
    const close = await scope.context.ui.positionActionViaUi(peer.page, {
      positionId: racePosition.id,
      positionSide: racePosition.positionSide,
      action: 'FULL_CLOSE'
    })
    scope.addMutation('fund-04-close-at-due-via-ui', close)
    const finalSnapshot = await waitForAccount(
      scope.context,
      scope.page,
      'FUND-04 funding/close serialization',
      (snapshot) => openPositions(snapshot, symbol).length === 0 ? snapshot : false
    )
    const finalDb = await scope.context.db.snapshotTradingRows(finalSnapshot.account.id)
    const raceRows = finalDb.fundingSettlementRows.filter((row) => (
      row.position_id === racePosition.id
        && Date.parse(row.funding_time) === Date.parse(race.fundingTime)
    ))
    assert([0, 1].includes(raceRows.length), 'funding/close race settles at most once')
    const closeOrders = finalDb.orderRows.filter((row) => (
      row.parent_position_id === racePosition.id
        && row.order_origin === 'USER'
        && row.status === 'FILLED'
    ))
    assert.equal(closeOrders.length, 1, 'FUND-04 exactly one user close order')
    const closeTrades = finalDb.tradeRows.filter((row) => row.order_id === closeOrders[0].id)
    assert.equal(closeTrades.length, 1, 'FUND-04 exactly one user close trade')
    const closeTrade = closeTrades[0]
    const closeOracle = perpCloseOracle({
      side: positionSide(racePosition),
      quantity: String(racePosition.lots),
      entryPrice: String(racePosition.openPrice),
      closeFillPrice: String(closeTrade.price),
      closeFeeRate: takerFeeRateForMarket(market),
      rules: rulesFor(market)
    })
    assert.equal(decimal8(closeTrade.realized_pnl), decimal8(closeOracle.grossRealizedPnl))
    assert.equal(decimal8(closeTrade.fee), decimal8(closeOracle.closeFee))
    const raceOracle = fundingSettlementOracle({
      side: positionSide(racePosition),
      marginMode: racePosition.marginMode,
      quantity: String(racePosition.lots),
      markPrice: race.markPrice,
      fundingRate: race.rate,
      balanceBefore: caughtUp.snapshot.summary.balance,
      marginHeld: racePosition.marginHeld,
      previousFundingPnl: racePosition.fundingPnl ?? '0'
    })
    const winner = raceRows.length === 1 ? 'FUNDING_FIRST' : 'CLOSE_FIRST'
    if (winner === 'FUNDING_FIRST') {
      const settlement = finalSnapshot.fundingSettlements.find(({ id }) => id === raceRows[0].id)
      assertFundingSettlementContract({
        settlement,
        expected: raceOracle,
        expectedPositionId: racePosition.id,
        expectedFundingTime: race.fundingTime,
        expectedSource: race.providerCode,
        expectedFundingRate: race.rate,
        expectedMarkPrice: race.markPrice,
        expectedPositionSide: positionSide(racePosition),
        expectedMarginMode: racePosition.marginMode
      })
    }
    const expectedBalance = moneyUnits(caughtUp.snapshot.summary.balance)
      + (winner === 'FUNDING_FIRST' ? moneyUnits(raceOracle.appliedCashflow) : 0n)
      + moneyUnits(closeTrade.realized_pnl)
      - moneyUnits(closeTrade.fee)
    assert.equal(moneyUnits(finalSnapshot.summary.balance), expectedBalance, 'race final balance')
    assert.equal(Number(finalDb.openPositions), 0)
    assert.equal(
      finalDb.positionRows.filter((row) => row.id === racePosition.id && row.status === 'CLOSED').length,
      1,
      'race leaves one closed position and no orphan'
    )
    assert.equal(
      finalDb.fundingSettlementRows.filter((row) => row.position_id === racePosition.id).length,
      new Set(finalDb.fundingSettlementRows
        .filter((row) => row.position_id === racePosition.id)
        .map((row) => row.funding_time)).size,
      'position+fundingTime uniqueness'
    )
    scope.oracleEvidence.push({
      kind: 'FUNDING_IDEMPOTENCY_RESTART_RACE',
      first: { cycle: first, oracle: firstOracle },
      catchup: { cycle: catchup, oracle: catchupOracle },
      race: { cycle: race, winner, settlementCount: raceRows.length, closeOracle, raceOracle },
      stable
    })
    await scope.capture('funding-close-race', finalSnapshot, {}, [scope.page, peer.page])
    return { finalSnapshot, finalDb }
  } finally {
    await closeSecondaryUser(peer)
  }
}

async function runIsolatedLiquidationJourney(scope, side) {
  requireAuthorityFixture(scope)
  const symbol = side === 'LONG' ? 'BTCUSDT-PERP' : 'ETHUSDT-PERP'
  const market = await freezeMarket(scope, symbol)
  const opened = await openPerpetual(scope, scope.page, {
    symbol,
    side,
    marginMode: 'ISOLATED',
    leverage: 100,
    quantity: stepAlignedQuantity(market, side === 'LONG' ? 0.01 : 0.1),
    label: `${scope.definition.id}-${side.toLowerCase()}`
  })
  const rules = rulesFor(market)
  const initialRisk = isolatedRisk(opened.position, market)
  const tick = Number(rules.tickSize ?? rules.priceTick)
  assert(Number.isFinite(tick) && tick > 0, `${symbol} price tick`)
  assertDecimalClose(
    opened.position.liquidationPrice,
    initialRisk.estimatedLiquidationPrice,
    String(tick),
    `${scope.definition.id} displayed liquidation price`
  )
  const uiRisk = await assertIsolatedRiskVisibleViaUi(
    scope.page,
    opened.position,
    initialRisk.estimatedLiquidationPrice,
    String(tick)
  )

  await scope.context.ui.openTradePanel(scope.page, { product: 'perpetual', symbol })
  const pendingBefore = await scope.context.api.snapshotAccount(scope.page)
  const riskPrice = alignedPrice(
    market,
    Number(initialRisk.estimatedLiquidationPrice) * (side === 'LONG' ? 0.5 : 1.5)
  )
  const pending = await scope.context.ui.submitOrderViaUi(scope.page, {
    side: side === 'LONG' ? 'BUY' : 'SELL',
    orderType: 'LIMIT',
    price: riskPrice,
    amount: opened.quantity
  })
  scope.addMutation(`${scope.definition.id.toLowerCase()}-risk-order-via-ui`, pending)
  const pendingOrder = await waitForNewPendingOrder(
    scope.context,
    scope.page,
    pendingBefore,
    symbol
  )

  const displayed = Number(opened.position.liquidationPrice)
  const safeTarget = displayed + (side === 'LONG' ? tick : -tick)
  const safeMarket = await setMarketAround(scope, symbol, safeTarget)
  const safeSnapshot = await observePositionOpen(scope, opened.position.id, 1300)
  const safePosition = positionById(safeSnapshot, opened.position.id)
  const safeRisk = isolatedRisk(safePosition, safeMarket)
  assert.equal(safeRisk.liquidatable, false, `${scope.definition.id} one tick safe`)
  assert(Number(safeRisk.isolatedEquity) > Number(safeRisk.isolatedThreshold))
  await scope.capture('one-tick-safe', safeSnapshot)

  const eventCursor = scope.context.events.snapshotFrames(scope.page).length
  const boundaryInput = await scope.context.api.snapshotAccount(scope.page)
  const boundaryPosition = positionById(boundaryInput, opened.position.id)
  assert(boundaryPosition, `${scope.definition.id} fresh boundary position`)
  const boundaryMarket = await setMarketAround(scope, symbol, displayed)
  const canceledSnapshot = await waitForRiskOrdersCanceled(
    scope,
    [boundaryPosition.id],
    [pendingOrder.order.id],
    120000
  )
  const canceledPosition = positionById(canceledSnapshot, boundaryPosition.id)
  assert(canceledPosition, `${scope.definition.id} position remains open after risk-order cancellation`)
  const freshBoundaryMarket = await freshAuthorityMarket(scope, boundaryMarket)
  const boundaryRisk = isolatedRisk(canceledPosition, freshBoundaryMarket)
  assert.equal(boundaryRisk.liquidatable, true, `${scope.definition.id} post-cancel boundary predicate`)
  const liquidation = await waitForLiquidation(
    scope,
    [canceledPosition],
    [pendingOrder.order.id],
    120000
  )
  const events = await actionEvents(
    scope.context,
    scope.page,
    eventCursor,
    ['LIQUIDATION'],
    scope.definition.id,
    30000
  )
  const db = await scope.context.db.snapshotTradingRows(liquidation.snapshot.account.id)
  const lifecycle = assertLiquidationLifecycleContract({
    positionId: opened.position.id,
    orders: db.orderRows,
    trades: db.tradeRows,
    ledger: db.cashLedgerRows
  })
  assert.equal(
    valueOf(lifecycle.trade, 'side'),
    side === 'LONG' ? 'SELL' : 'BUY',
    `${scope.definition.id} closing side`
  )
  assert.equal(
    db.orderRows.find((row) => row.id === pendingOrder.order.id)?.status,
    'CANCELED',
    `${scope.definition.id} risk order canceled before system close`
  )
  assert.equal(Number(liquidation.snapshot.summary.balance) >= 0, true, 'balance floor')
  await assertLiquidationVisibleViaUi(scope.page, symbol)
  const feeEvidence = assertLiquidationFees(scope, lifecycle, freshBoundaryMarket, db, {
    position: canceledPosition
  })
  assertMoneyDelta(
    liquidation.snapshot.summary.balance,
    pendingBefore.summary.balance,
    moneyText(
      moneyUnits(feeEvidence.grossRealizedPnl)
        - moneyUnits(feeEvidence.closeFee)
        - moneyUnits(feeEvidence.expectedFeeCharged)
    ),
    `${scope.definition.id} liquidation account cash delta`
  )
  scope.oracleEvidence.push({
    kind: 'ISOLATED_LIQUIDATION_BOUNDARY',
    side,
    initialRisk,
    uiRisk,
    safeRisk,
    boundaryRisk,
    feeEvidence,
    orderId: idOf(lifecycle.order, 'id'),
    tradeId: idOf(lifecycle.trade, 'id'),
    stomp: events
  })
  return { finalSnapshot: liquidation.snapshot, finalDb: db }
}

async function runCrossLiquidationJourney(scope) {
  requireAuthorityFixture(scope)
  const symbols = ['BTCUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP']
  const markets = new Map()
  for (const symbol of symbols) markets.set(symbol, await freezeMarket(scope, symbol))
  const startingBalance = Number(scope.before.summary.balance)
  const opened = []
  for (const symbol of symbols) {
    const market = markets.get(symbol)
    const preferred = startingBalance * 0.45 / markPrice(market)
    opened.push(await openPerpetual(scope, scope.page, {
      symbol,
      side: 'LONG',
      marginMode: 'CROSS',
      leverage: 100,
      quantity: stepAlignedQuantity(market, preferred),
      label: `LIQ-03-${symbol}`
    }))
  }
  let latest = opened.at(-1).snapshot
  await scope.context.ui.openTradePanel(scope.page, {
    product: 'perpetual',
    symbol: symbols[0]
  })
  const openingLimit = await scope.context.ui.submitOrderViaUi(scope.page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: alignedPrice(markets.get(symbols[0]), markPrice(markets.get(symbols[0])) * 0.01),
    amount: opened[0].quantity
  })
  scope.addMutation('liq-03-active-opening-limit-via-ui', openingLimit)
  const openingPending = await waitForNewPendingOrder(
    scope.context,
    scope.page,
    latest,
    symbols[0]
  )
  latest = openingPending.snapshot

  const protectedPosition = opened[1].position
  const protection = await createProtectionViaUi(scope, {
    position: protectedPosition,
    quantity: opened[1].quantity,
    triggerPrice: alignedPrice(
      markets.get(symbols[1]),
      Number(protectedPosition.openPrice) * 1.2
    )
  })
  scope.addMutation('liq-03-active-protection-limit-via-ui', protection)
  const protectionOrder = await waitForNewPendingOrder(
    scope.context,
    scope.page,
    latest,
    symbols[1]
  )
  latest = protectionOrder.snapshot

  const safeMarkets = new Map()
  for (const item of opened) {
    safeMarkets.set(
      item.position.symbol,
      await setMarketAround(scope, item.position.symbol, Number(item.position.openPrice) * 0.95)
    )
  }
  const safeSnapshot = await observePositionsOpen(
    scope,
    opened.map(({ position }) => position.id),
    1300
  )
  const safeRisk = crossRisk(safeSnapshot, safeMarkets)
  assert.equal(safeRisk.liquidatable, false, 'LIQ-03 pre-boundary account risk')
  await scope.capture('cross-safe', safeSnapshot)

  const eventCursor = scope.context.events.snapshotFrames(scope.page).length
  const boundary = crossBoundaryMarkets(safeSnapshot, opened, markets)
  const dangerMarkets = new Map()
  for (const item of opened) {
    dangerMarkets.set(
      item.position.symbol,
      await setMarketAround(scope, item.position.symbol, boundary.targets.get(item.position.symbol))
    )
  }
  const dangerRisk = crossRisk(safeSnapshot, dangerMarkets)
  assert.equal(dangerRisk.liquidatable, true, 'LIQ-03 boundary account risk')
  const canceledSnapshot = await waitForRiskOrdersCanceled(
    scope,
    opened.map(({ position }) => position.id),
    [openingPending.order.id, protectionOrder.order.id],
    120000
  )
  const freshDangerMarkets = new Map()
  for (const [symbol, dangerMarket] of dangerMarkets) {
    freshDangerMarkets.set(symbol, await freshAuthorityMarket(scope, dangerMarket))
  }
  const canceledPositions = opened.map(({ position }) => {
    const fresh = positionById(canceledSnapshot, position.id)
    assert(fresh, `${position.id} remains open after risk-order cancellation`)
    return fresh
  })
  const postCancelRisk = crossRisk(canceledSnapshot, freshDangerMarkets)
  assert.equal(postCancelRisk.liquidatable, true, 'LIQ-03 post-cancel account risk')
  const liquidation = await waitForLiquidation(
    scope,
    canceledPositions,
    [openingPending.order.id, protectionOrder.order.id],
    120000
  )
  const events = await actionEvents(
    scope.context,
    scope.page,
    eventCursor,
    ['LIQUIDATION'],
    'LIQ-03',
    30000
  )
  const accountId = liquidation.snapshot.account.id
  const liquidationDb = await snapshotShortfallDb(scope, accountId)
  const db = liquidationDb.trading
  assert.equal(liquidationDb.charges.length, canceledPositions.length, 'LIQ-03 exact scoped charges')
  const charges = canceledPositions.map((position) => {
    const matches = liquidationDb.charges.filter((row) => row.position_id === position.id)
    assert.equal(matches.length, 1, `LIQ-03 exact charge ${position.id}`)
    return matches[0]
  })
  const lifecycles = canceledPositions.map((position, index) => assertLiquidationLifecycleContract({
    positionId: position.id,
    orders: db.orderRows,
    trades: db.tradeRows,
    ledger: db.cashLedgerRows,
    expectedFeeCharged: charges[index].fee_charged
  }))
  const feeEvidence = lifecycles.map((lifecycle, index) => {
    const charge = charges[index]
    return assertLiquidationFees(
      scope,
      lifecycle,
      freshDangerMarkets.get(canceledPositions[index].symbol),
      db,
      {
        position: canceledPositions[index],
        charge,
        expectedFeeCharged: charge.fee_charged
      }
    )
  })
  const shortfallOracle = assertCrossShortfallContract({
    fixture: { capacity: safeSnapshot.summary.balance },
    trades: lifecycles.map(({ trade }) => trade),
    charges,
    shortfalls: liquidationDb.shortfalls,
    accountId,
    audits: liquidationDb.audits
  })
  const cashAfterFees = moneyUnits(shortfallOracle.cashAfterCore)
    - moneyUnits(shortfallOracle.feeCharged)
  const expectedBalanceAfter = cashAfterFees > 0n ? cashAfterFees : 0n
  assertMoneyDelta(
    liquidation.snapshot.summary.balance,
    safeSnapshot.summary.balance,
    moneyText(expectedBalanceAfter - moneyUnits(safeSnapshot.summary.balance)),
    'LIQ-03 liquidation account cash delta'
  )
  for (const orderId of [openingPending.order.id, protectionOrder.order.id]) {
    assert.equal(
      db.orderRows.find((row) => row.id === orderId)?.status,
      'CANCELED',
      `LIQ-03 active order ${orderId} canceled`
    )
  }
  assert.equal(liquidation.snapshot.account.status, 'ACTIVE', 'cross account recovery')
  await assertLiquidationVisibleViaUi(scope.page, symbols)
  await assertAdminLiquidationVisible(scope, liquidation.snapshot.account.id, symbols)
  scope.oracleEvidence.push({
    kind: 'CROSS_ACCOUNT_LIQUIDATION',
    safeRisk,
    dangerRisk,
    postCancelRisk,
    boundaryFactor: boundary.factor,
    positionIds: opened.map(({ position }) => position.id),
    orderIds: lifecycles.map(({ order }) => order.id),
    feeEvidence,
    charges,
    shortfallOracle,
    shortfalls: liquidationDb.shortfalls,
    stomp: events
  })
  return { finalSnapshot: liquidation.snapshot, finalDb: db }
}

async function runShortfallJourney(scope) {
  requireAuthorityFixture(scope)
  const secondary = await createSecondaryUser(scope, 'LIQ-04-control-user')
  const symbols = ['BTCUSDT-PERP', 'ETHUSDT-PERP']
  try {
    const controlBefore = await scope.context.api.snapshotAccount(secondary.page)
    const markets = new Map()
    for (const symbol of symbols) markets.set(symbol, await freezeMarket(scope, symbol))
    const opened = []
    for (const symbol of symbols) {
      const market = markets.get(symbol)
      opened.push(await openPerpetual(scope, scope.page, {
        symbol,
        side: 'LONG',
        marginMode: 'CROSS',
        leverage: 100,
        quantity: stepAlignedQuantity(market, 0.02),
        label: `LIQ-04-${symbol}`
      }))
    }
    const fixture = await reduceAccountRiskCapacity(scope, opened.at(-1).snapshot)
    const eventCursor = scope.context.events.snapshotFrames(scope.page).length
    const liquidationMarkets = new Map()
    for (const item of opened) {
      liquidationMarkets.set(
        item.position.symbol,
        await setMarketAround(scope, item.position.symbol, Number(item.position.openPrice) * 0.001)
      )
    }
    const liquidation = await waitForLiquidation(
      scope,
      opened.map(({ position }) => position),
      [],
      120000
    )
    const events = await actionEvents(
      scope.context,
      scope.page,
      eventCursor,
      ['LIQUIDATION'],
      'LIQ-04',
      30000
    )
    const beforeRestartDb = await snapshotShortfallDb(scope, liquidation.snapshot.account.id)
    assert.equal(Number(liquidation.snapshot.summary.balance), 0, 'shortfall balance floors at zero')
    assert.equal(beforeRestartDb.shortfalls.length, 1, 'one account bankruptcy shortfall')
    assert.equal(beforeRestartDb.charges.length, opened.length, 'one cross charge per position')
    assert(beforeRestartDb.shortfalls[0].amount !== '0', 'shortfall amount is non-zero')

    const shortfallOracle = assertCrossShortfallContract({
      fixture,
      trades: beforeRestartDb.trading.tradeRows.filter((row) => (
        beforeRestartDb.charges.some((charge) => charge.order_id === row.order_id)
      )),
      charges: beforeRestartDb.charges,
      shortfalls: beforeRestartDb.shortfalls,
      accountId: liquidation.snapshot.account.id,
      audits: beforeRestartDb.audits
    })
    const feeEvidence = opened.map(({ position }) => {
      const charge = beforeRestartDb.charges.find((row) => row.position_id === position.id)
      assert(charge, `${position.id} cross liquidation charge`)
      const lifecycle = assertLiquidationLifecycleContract({
        positionId: position.id,
        orders: beforeRestartDb.trading.orderRows,
        trades: beforeRestartDb.trading.tradeRows,
        ledger: beforeRestartDb.trading.cashLedgerRows,
        expectedFeeCharged: charge.fee_charged
      })
      return assertLiquidationFees(
        scope,
        lifecycle,
        liquidationMarkets.get(position.symbol),
        beforeRestartDb.trading,
        { charge, expectedFeeCharged: charge.fee_charged, position }
      )
    })

    await scope.page.navigate('about:blank')
    await secondary.page.navigate('about:blank')
    await scope.context.services.restartBackend()
    scope.fixtureActions.push({ action: 'restart-backend-after-shortfall-settlement' })
    await scope.context.ui.openTradePanel(scope.page, {
      product: 'perpetual',
      symbol: symbols[0]
    })
    await delay(1800)
    const afterRestartDb = await snapshotShortfallDb(scope, liquidation.snapshot.account.id)
    assert.deepEqual(afterRestartDb, beforeRestartDb, 'three scans and restart are idempotent')
    const controlAfter = await scope.context.api.snapshotAccount(secondary.page)
    assert.deepEqual(
      accountFundsFingerprint(controlAfter),
      accountFundsFingerprint(controlBefore),
      'shortfall fixture does not affect another user'
    )
    const finalSnapshot = await scope.context.api.snapshotAccount(scope.page)
    assert.equal(Number(finalSnapshot.summary.balance), 0)
    scope.apiEvidence.push(safeAccountEvidence(controlBefore), safeAccountEvidence(controlAfter))
    scope.dbEvidence.push(afterRestartDb.trading)
    scope.oracleEvidence.push({
      kind: 'CROSS_BANKRUPTCY_SHORTFALL',
      fixture,
      shortfallOracle,
      feeEvidence,
      charges: beforeRestartDb.charges,
      shortfall: beforeRestartDb.shortfalls[0],
      stomp: events
    })
    return { finalSnapshot, finalDb: afterRestartDb.trading }
  } finally {
    await closeSecondaryUser(secondary)
  }
}

async function createSecondaryUser(scope, label) {
  const browser = await scope.context.ui.launchBrowser()
  let page
  try {
    page = await scope.context.ui.createEvidencePage(browser, {
      caseId: `${scope.definition.id}-${label}`,
      viewport: { name: 'desktop', width: 1440, height: 1000 }
    })
    const registration = await scope.context.ui.registerViaUi(
      page,
      scope.context.userFactory(label)
    )
    scope.addMutation(`register-${slug(label)}-via-ui`, registration)
    return { browser, page }
  } catch (error) {
    await closeSecondaryUser({ browser, page }, false)
    throw error
  }
}

async function createAuthenticatedPeer(scope, label) {
  let page
  try {
    assert(scope.browser, 'FUND-04 requires the owned browser for its second tab')
    page = await scope.context.ui.createEvidencePage(scope.browser, {
      caseId: `${scope.definition.id}-${label}`,
      viewport: { name: 'desktop', width: 1440, height: 1000 }
    })
    const token = await scope.page.evaluate(() => localStorage.getItem('fx-platform-auth-token'))
    assert(typeof token === 'string' && token.length > 0, 'authenticated peer token')
    const base = scope.page.p0Options?.webBaseUrl ?? 'http://127.0.0.1:5199'
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `localStorage.setItem('fx-platform-auth-token', ${JSON.stringify(token)})`
    })
    await page.navigate(base)
    scope.contractProbes.push({ kind: 'SAME_USER_SECOND_BROWSER_TAB', label })
    return { page }
  } catch (error) {
    await closeSecondaryUser({ page }, false)
    throw error
  }
}

async function closeSecondaryUser(secondary, assertClean = true) {
  if (!secondary) return
  const failures = []
  if (secondary.page) {
    try {
      if (assertClean) secondary.page.assertEvidenceClean('secondary funding/liquidation user')
      await secondary.page.close()
    } catch (error) {
      failures.push(error)
    }
  }
  if (secondary.browser) {
    try {
      await secondary.browser.close()
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) throw new AggregateError(failures, 'P0_SECONDARY_BROWSER_CLEANUP_FAILED')
}

async function waitForFundingBoundary(fundingTimeMs) {
  assert(Number.isFinite(fundingTimeMs), 'funding boundary time')
  while (Date.now() < fundingTimeMs) {
    await delay(Math.min(50, Math.max(1, fundingTimeMs - Date.now())))
  }
}

async function prepareFixedFunding(scope, symbol, rate) {
  await setProviderBindings(scope, symbol, ['local-perp'])
  return configureFunding(scope, symbol, {
    fundingSourcePriority: ['FIXED'],
    fixedFundingRate: rate,
    fixedFundingIntervalMinutes: 525600,
    fundingStaleSeconds: 5,
    reason: `${scope.definition.id} deterministic canonical funding cycle`
  })
}

async function setProviderBindings(scope, symbol, enabledProviders) {
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.providerBindings(adminPage, {
    symbols: [symbol],
    enabledProviders
  })
  scope.registerFixtureRestore({
    action: 'restore-provider-bindings',
    symbol,
    enabledProviders
  }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-provider-bindings',
    symbol,
    enabledProviders,
    before: fixture.before,
    after: fixture.after
  })
  return fixture
}

async function configureFunding(scope, symbol, config) {
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.fundingConfig(adminPage, {
    symbol,
    ...config
  })
  scope.registerFixtureRestore({ action: 'restore-funding-config', symbol }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-funding-config',
    symbol,
    request: config,
    before: fixture.before,
    after: fixture.after
  })
  return fixture
}

async function createFundingCycleFixture(scope, input) {
  const cycle = fundingCycle(input)
  await insertFundingCycle(scope, cycle)
  return cycle
}

function fundingCycle({
  symbol,
  rate,
  markPrice: settlementMark,
  dueInMs,
  providerCode = 'fixed',
  sourceMode = 'LOCAL_SIMULATED'
}) {
  const fundingTimeMs = Math.floor((Date.now() + dueInMs) / 100) * 100
  const fundingTime = new Date(fundingTimeMs).toISOString()
  return {
    id: randomUUID(),
    symbol,
    rate: String(rate),
    fundingRate: String(rate),
    fundingTime,
    fundingTimeMs,
    nextFundingTime: new Date(fundingTimeMs + 60000).toISOString(),
    markPrice: String(settlementMark),
    providerCode,
    source: providerCode,
    sourceMode,
    asOf: new Date().toISOString(),
    intervalMinutes: 1,
    rawPayloadHash: 'f'.repeat(64)
  }
}

async function insertFundingCycle(scope, cycle) {
  const inserted = scalarResult(await scope.context.db.query(`
    INSERT INTO trading.funding_rates (
      id, symbol, funding_rate, funding_time, next_funding_time, mark_price,
      provider_code, source_mode, as_of, interval_minutes, raw_payload_hash
    ) VALUES (
      '${sqlLiteral(cycle.id)}',
      '${sqlLiteral(cycle.symbol)}',
      ${Number(cycle.rate).toFixed(10)},
      '${sqlLiteral(cycle.fundingTime)}',
      '${sqlLiteral(cycle.nextFundingTime)}',
      ${Number(cycle.markPrice).toFixed(10)},
      '${sqlLiteral(cycle.providerCode)}',
      '${sqlLiteral(cycle.sourceMode)}',
      '${sqlLiteral(cycle.asOf)}',
      ${Number(cycle.intervalMinutes)},
      '${sqlLiteral(cycle.rawPayloadHash)}'
    )
    ON CONFLICT (symbol, funding_time) DO NOTHING
    RETURNING id;
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
        WHERE id = '${sqlLiteral(cycle.id)}'
          AND symbol = '${sqlLiteral(cycle.symbol)}'
        RETURNING id
      )
      SELECT count(*) FROM deleted;
    `)))
    assert.equal(deleted, 1, `restore funding cycle ${cycle.id}`)
  })
  scope.fixtureActions.push({ action: 'insert-canonical-funding-cycle', ...cycle })
}

async function shiftPositionBeforeCycle(scope, accountId, positionId, cycle) {
  const openedAt = new Date(Date.parse(cycle.fundingTime) - 1000).toISOString()
  return shiftPositionOpenedAt(
    scope,
    accountId,
    positionId,
    openedAt,
    'shift-position-before-funding-cycle'
  )
}

async function shiftPositionOpenedAt(scope, accountId, positionId, openedAt, action) {
  const fixture = await scope.context.fixtures.positionTime({
    accountId,
    positionId,
    openedAt
  })
  scope.registerFixtureRestore({
    action: 'restore-position-opened-at',
    accountId,
    positionId
  }, fixture.restore)
  scope.fixtureActions.push({
    action,
    accountId,
    positionId,
    openedAt,
    before: fixture.before,
    after: fixture.after
  })
}

async function isolateFundingRateCursor(scope, symbol) {
  const before = await jsonResult(scope.context, `
    WITH locked_symbol AS MATERIALIZED (
      SELECT id
      FROM market.symbols
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
    )::text
    FROM deleted;
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
        PERFORM id
        FROM market.symbols
        WHERE symbol = '${sqlLiteral(symbol)}'
        FOR UPDATE;
        DELETE FROM trading.funding_rates
        WHERE symbol = '${sqlLiteral(symbol)}';
        INSERT INTO trading.funding_rates
        SELECT restored.*
        FROM jsonb_populate_recordset(
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

async function prepareIngestedFundingCycle(scope, options) {
  const currentRate = await waitForIngestedFundingRate(
    scope,
    options.symbol,
    options.providerCode,
    options.configuredAt,
    options.freshTimeoutMs,
    'FRESH'
  )
  const currentTime = Date.parse(currentRate.fundingTime)
  const intervalMs = Number(currentRate.intervalMinutes) * 60_000
  assert(Number.isFinite(currentTime), `${options.providerCode} current funding time`)
  assert(Number.isSafeInteger(intervalMs) && intervalMs > 0, `${options.providerCode} interval`)
  const historyOpenedAt = new Date(currentTime - intervalMs - 1000).toISOString()
  assert(Date.parse(historyOpenedAt) < Date.now(), `${options.providerCode} history window`)
  await shiftPositionOpenedAt(
    scope,
    options.accountId,
    options.positionId,
    historyOpenedAt,
    `${options.providerCode}-history-ingestion-window`
  )
  const reingestAt = Date.now()
  await removeCaseFundingRates(scope, options.symbol, null, 0)
  const rate = await waitForIngestedFundingRate(
    scope,
    options.symbol,
    options.providerCode,
    reingestAt,
    options.dueTimeoutMs
  )
  assert(Date.parse(rate.fundingTime) > Date.parse(historyOpenedAt), 'ingested cycle after position open')
  return { currentRate, rate, historyOpenedAt }
}

async function waitForIngestedFundingRate(
  scope,
  symbol,
  providerCode,
  createdAtOrAfter,
  timeoutMs,
  selection = 'DUE'
) {
  assert(['DUE', 'FRESH'].includes(selection), 'funding rate selection')
  const temporalPredicate = selection === 'FRESH'
    ? `AND as_of >= to_timestamp(${Number(createdAtOrAfter) - 3000} / 1000.0)`
    : 'AND funding_time <= now()'
  return waitForValue(async () => {
    const rows = await jsonResult(scope.context, `
      SELECT COALESCE(json_agg(row_to_json(candidate)), '[]'::json)::text
      FROM (
        SELECT id::text, symbol, funding_rate::text AS "fundingRate",
          funding_time AS "fundingTime", next_funding_time AS "nextFundingTime",
          mark_price::text AS "markPrice", provider_code AS "providerCode",
          source_mode AS "sourceMode", as_of AS "asOf",
          interval_minutes AS "intervalMinutes", raw_payload_hash AS "rawPayloadHash",
          created_at AS "createdAt"
        FROM trading.funding_rates
        WHERE symbol = '${sqlLiteral(symbol)}'
          AND provider_code = '${sqlLiteral(providerCode)}'
          AND created_at >= to_timestamp(${Number(createdAtOrAfter)} / 1000.0)
          ${temporalPredicate}
        ORDER BY ${selection === 'FRESH' ? 'as_of' : 'funding_time'} DESC
        LIMIT 1
      ) candidate;
    `)
    const row = rows[0]
    return row ? {
      ...row,
      rate: row.fundingRate,
      fundingTimeMs: Date.parse(row.fundingTime),
      source: row.providerCode
    } : false
  }, `${symbol} fresh ${providerCode} funding ingestion (${selection})`, timeoutMs)
}

async function canonicalRateCount(scope, symbol, fundingTime) {
  return Number(scalarResult(await scope.context.db.query(`
    SELECT count(*)
    FROM trading.funding_rates
    WHERE symbol = '${sqlLiteral(symbol)}'
      AND funding_time = '${sqlLiteral(fundingTime)}';
  `)))
}

async function removeCaseFundingRates(scope, symbol, providerCode, createdAtOrAfter) {
  const providerPredicate = providerCode
    ? `AND provider_code = '${sqlLiteral(providerCode)}'`
    : ''
  const deleted = Number(scalarResult(await scope.context.db.query(`
    WITH deleted AS (
      DELETE FROM trading.funding_rates
      WHERE symbol = '${sqlLiteral(symbol)}'
        ${providerPredicate}
        AND created_at >= to_timestamp(${Number(createdAtOrAfter)} / 1000.0)
      RETURNING id
    )
    SELECT count(*) FROM deleted;
  `)))
  scope.fixtureActions.push({
    action: 'remove-case-owned-funding-rates-after-evidence',
    symbol,
    providerCode: providerCode ?? '*',
    createdAtOrAfter: new Date(createdAtOrAfter).toISOString(),
    deleted
  })
  return deleted
}

async function settlementCount(scope, positionId, fundingTime) {
  return Number(scalarResult(await scope.context.db.query(`
    SELECT count(*)
    FROM trading.funding_settlements
    WHERE position_id = '${sqlLiteral(positionId)}'
      AND funding_time = '${sqlLiteral(fundingTime)}';
  `)))
}

async function observeFundingStable(scope, positionId, fundingTime, durationMs) {
  const deadline = Date.now() + durationMs
  let result
  let observations = 0
  do {
    const rows = await jsonResult(scope.context, `
      SELECT COALESCE(json_agg(row_to_json(candidate)), '[]'::json)::text
      FROM (
        SELECT id::text, balance_after::text AS "balanceAfter"
        FROM trading.funding_settlements
        WHERE position_id = '${sqlLiteral(positionId)}'
          AND funding_time = '${sqlLiteral(fundingTime)}'
      ) candidate;
    `)
    assert.equal(rows.length, 1, 'funding scheduler retry uniqueness')
    result = { count: rows.length, balanceAfter: rows[0].balanceAfter }
    observations += 1
    await delay(600)
  } while (Date.now() < deadline)
  assert(observations >= 3, 'at least three funding scheduler observations')
  return { ...result, observations }
}

async function openPerpetual(scope, page, options) {
  const captures = await scope.context.ui.setPerpetualSettingsViaUi(page, {
    positionMode: 'ONE_WAY',
    marginMode: options.marginMode,
    leverage: options.leverage,
    quantityUnit: 'BASE'
  })
  for (const capture of captures) scope.addMutation(`${slug(options.label)}-settings-via-ui`, capture)
  await scope.context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: options.symbol
  })
  const before = await scope.context.api.snapshotAccount(page)
  const capture = await scope.context.ui.submitOrderViaUi(page, {
    side: options.side === 'LONG' ? 'BUY' : 'SELL',
    orderType: 'MARKET',
    amount: options.quantity
  })
  scope.addMutation(`${slug(options.label)}-open-via-ui`, capture)
  const opened = await waitForAccount(
    scope.context,
    page,
    `${options.label} position open`,
    (snapshot) => {
      const known = new Set(before.positions.map(({ id }) => id))
      const position = openPositions(snapshot, options.symbol).find(({ id }) => !known.has(id))
      const order = newRows(before.orders, snapshot.orders).find((candidate) => (
        candidate.symbol === options.symbol && candidate.status === 'FILLED'
      ))
      const trade = order && snapshot.trades.find(({ orderId }) => orderId === order.id)
      return position && order && trade ? { snapshot, position, order, trade } : false
    }
  )
  assert.equal(positionSide(opened.position), options.side, `${options.label} side`)
  assert.equal(opened.position.marginMode, options.marginMode, `${options.label} margin mode`)
  return { ...opened, quantity: options.quantity, before }
}

async function waitForFundingSettlement(
  context,
  page,
  before,
  position,
  cycle,
  timeoutMs = 30000
) {
  return waitForAccount(
    context,
    page,
    `funding ${cycle.id} for ${position.id}`,
    (snapshot) => {
      const settlements = snapshot.fundingSettlements.filter((candidate) => (
        candidate.positionId === position.id
          && Date.parse(candidate.fundingTime) === Date.parse(cycle.fundingTime)
      ))
      const created = settlements.filter((candidate) => (
        !(before.fundingSettlements ?? []).some(({ id }) => id === candidate.id)
      ))
      return settlements.length === 1 && created.length === 1
        ? { snapshot, settlement: created[0], position: positionById(snapshot, position.id) }
        : false
    },
    timeoutMs
  )
}

function assertFundingOutcome(scope, opened, settled, cycle) {
  const beforePosition = opened.position
  const beforeSummary = opened.snapshot.summary
  const afterPosition = settled.position
  assert(afterPosition, `${beforePosition.id} remains open after funding`)
  const oracle = fundingSettlementOracle({
    side: positionSide(beforePosition),
    marginMode: beforePosition.marginMode,
    quantity: String(beforePosition.lots),
    markPrice: String(cycle.markPrice),
    fundingRate: String(cycle.rate ?? cycle.fundingRate),
    balanceBefore: String(beforeSummary.balance),
    marginHeld: String(beforePosition.marginHeld),
    previousFundingPnl: String(beforePosition.fundingPnl ?? '0')
  })
  assertFundingSettlementContract({
    settlement: settled.settlement,
    expected: oracle,
    expectedPositionId: beforePosition.id,
    expectedFundingTime: cycle.fundingTime,
    expectedSource: cycle.providerCode,
    expectedFundingRate: cycle.rate ?? cycle.fundingRate,
    expectedMarkPrice: cycle.markPrice,
    expectedPositionSide: positionSide(beforePosition),
    expectedMarginMode: beforePosition.marginMode
  })
  assertDecimalClose(
    afterPosition.fundingPnl,
    oracle.fundingPnlAfter,
    '0.00000001',
    `${beforePosition.id} fundingPnl`
  )
  assert.equal(decimal8(afterPosition.marginHeld), decimal8(beforePosition.marginHeld), 'marginHeld')
  if (beforePosition.marginMode === 'CROSS') {
    assert.equal(decimal8(settled.snapshot.summary.balance), decimal8(oracle.balanceAfter), 'cross balance')
    assertMoneyDelta(
      settled.snapshot.summary.equity,
      beforeSummary.equity,
      oracle.appliedCashflow,
      'cross equity cashflow'
    )
    assertMoneyDelta(
      settled.snapshot.summary.freeMargin,
      beforeSummary.freeMargin,
      oracle.appliedCashflow,
      'cross free margin cashflow'
    )
  } else {
    assert.equal(decimal8(settled.snapshot.summary.balance), decimal8(beforeSummary.balance), 'isolated balance')
    assert.equal(decimal8(settled.snapshot.summary.usedMargin), decimal8(opened.snapshot.summary.usedMargin), 'isolated used margin')
    assert.equal(decimal8(settled.snapshot.summary.freeMargin), decimal8(opened.snapshot.summary.freeMargin), 'isolated free margin')
  }
  return oracle
}

function assertFundingDatabase(db, settlement, oracle) {
  const rows = db.fundingSettlementRows.filter(({ id }) => id === settlement.id)
  assert.equal(rows.length, 1, `${settlement.id} DB settlement`)
  const row = rows[0]
  assert.equal(row.position_id, settlement.positionId, 'DB funding position')
  assert.equal(Date.parse(row.funding_time), Date.parse(settlement.fundingTime), 'DB funding time')
  assert.equal(decimal10(row.funding_rate), decimal10(settlement.fundingRate), 'DB funding rate')
  assert.equal(decimal8(row.amount), decimal8(oracle.settlementAmount), 'DB funding amount')
  assert.equal(row.asset, settlement.asset, 'DB funding asset')
  assert.equal(row.ledger_entry_id, settlement.ledgerEntryId, 'DB funding ledger id')
  assert.equal(row.position_side, settlement.positionSide, 'DB funding side')
  assert.equal(row.margin_mode, settlement.marginMode, 'DB funding margin mode')
  assert.equal(decimal10(row.mark_price), decimal10(settlement.markPrice), 'DB funding mark')
  assert.equal(row.source, settlement.source, 'DB funding source')
  assert.equal(decimal8(row.balance_after), decimal8(settlement.balanceAfter), 'DB funding balance')
  assert.equal(
    decimal8(row.isolated_margin_after),
    decimal8(settlement.isolatedMarginAfter),
    'DB isolated margin'
  )
  assert.equal(decimal8(row.shortfall), decimal8(settlement.shortfall), 'DB funding shortfall')
  const ledgers = db.cashLedgerRows.filter((row) => (
    row.reference_id === settlement.id
      || row.id === settlement.ledgerEntryId
  ))
  assert.equal(ledgers.length, oracle.ledgerAmount === null ? 0 : 1, 'funding ledger cardinality')
  if (oracle.ledgerAmount !== null) {
    assert.equal(ledgers[0].entry_type, 'FUNDING_FEE')
    assert.equal(decimal8(ledgers[0].amount), decimal8(oracle.ledgerAmount))
  }
}

async function assertFundingVisibleViaUi(page, settlement) {
  await page.evaluate(() => {
    const tab = [...document.querySelectorAll('[role="tab"]')]
      .find((candidate) => ['Funding settlements', '资金费结算', '資金調達決済']
        .includes(candidate.textContent?.trim()))
    if (!tab) throw new Error('P0_FUNDING_TAB_MISSING')
    tab.click()
  })
  await page.waitForFunction((expected) => {
    const rows = [...document.querySelectorAll('[role="tabpanel"] tbody tr')]
    return rows.some((row) => {
      const cells = [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim() ?? '')
      return cells.includes(expected.symbol)
        && cells.some((value) => Number(value) === Number(expected.fundingRate))
        && cells.some((value) => Number(value) === Number(expected.amount))
        && cells.includes(expected.asset)
        && cells.includes(expected.positionSide)
        && cells.some((value) => value.toLowerCase() === String(expected.source).toLowerCase())
        && cells.at(-1) !== '-'
    })
  }, `Funding tab settlement ${settlement.id}`, settlement)
}

async function closePositionViaUi(scope, page, before, position, label) {
  const capture = await scope.context.ui.positionActionViaUi(page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'FULL_CLOSE'
  })
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  return waitForAccount(
    scope.context,
    page,
    `${label} closed`,
    (snapshot) => openPositions(snapshot).some(({ id }) => id === position.id)
      ? false
      : snapshot
  )
}

function requireAuthorityFixture(scope) {
  assert.equal(
    scope.context.authority?.authorityBundleFixture,
    'PASS',
    `${scope.definition.id} requires authority-bundle fixture PASS`
  )
}

async function freezeMarket(scope, symbol) {
  const market = await scope.context.api.snapshotMarket(symbol)
  return setMarketPrices(
    scope,
    symbol,
    quoteDecimal(market, 'bid'),
    quoteDecimal(market, 'ask')
  )
}

async function setMarketAround(scope, symbol, target) {
  const current = await scope.context.api.snapshotMarket(symbol)
  const rules = rulesFor(current)
  const tick = Number(rules.tickSize ?? rules.priceTick)
  const bidNow = Number(quoteDecimal(current, 'bid'))
  const askNow = Number(quoteDecimal(current, 'ask'))
  const halfSpread = Math.max((askNow - bidNow) / 2, tick / 2)
  const bid = alignedPrice(current, Math.max(tick, Number(target) - halfSpread), 'FLOOR')
  let ask = alignedPrice(current, Number(target) + halfSpread, 'CEILING')
  if (Number(ask) <= Number(bid)) {
    ask = alignedPrice(current, Number(bid) + tick, 'CEILING')
  }
  return setMarketPrices(scope, symbol, bid, ask)
}

async function setMarketPrices(scope, symbol, bid, ask) {
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.marketOverride(adminPage, {
    symbol,
    bid: String(bid),
    ask: String(ask),
    ttl: 'PT5M'
  })
  scope.registerFixtureRestore({
    action: 'restore-authority-market-bundle',
    symbol
  }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-authority-market-bundle',
    symbol,
    bid: String(bid),
    ask: String(ask)
  })
  const market = await waitForValue(async () => {
    const candidate = await scope.context.api.snapshotMarket(symbol)
    const exact = Number(quoteDecimal(candidate, 'bid')) === Number(bid)
      && Number(quoteDecimal(candidate, 'ask')) === Number(ask)
    if (!exact) return false
    assert.equal(
      isAuthorityMarketSnapshot(candidate, String(bid), String(ask)),
      true,
      `${symbol} complete authority bundle`
    )
    return candidate
  }, `${scope.definition.id} ${symbol} authority market`)
  const liquidationFeeRate = scalarResult(await scope.context.db.query(`
    SELECT liquidation_fee_rate::text
    FROM market.symbols
    WHERE symbol = '${sqlLiteral(symbol)}';
  `))
  assert(
    Number.isFinite(Number(liquidationFeeRate)) && Number(liquidationFeeRate) >= 0,
    `${symbol} configured liquidation fee rate`
  )
  return {
    ...market,
    feeRates: {
      liquidationFeeRate,
      takerFeeRate: DEMO_RATES.takerFeeRate
    }
  }
}

function isolatedRisk(position, market) {
  return isolatedLiquidationOracle({
    side: positionSide(position),
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    markPrice: String(markPrice(market)),
    marginHeld: String(position.marginHeld),
    fundingPnl: String(position.fundingPnl ?? '0'),
    maintenanceMarginRate: String(position.maintenanceMarginRate),
    rules: rulesFor(market)
  })
}

function crossRisk(snapshot, markets) {
  const positions = openPositions(snapshot).filter(({ marginMode }) => marginMode === 'CROSS')
  const oracle = crossLiquidationOracle({
    perpBalance: String(snapshot.summary.balance),
    isolatedPrincipal: '0',
    positions: positions.map((position) => {
      const market = markets.get(position.symbol)
      assert(market, `${position.symbol} cross market snapshot`)
      return {
        side: positionSide(position),
        quantity: String(position.lots),
        entryPrice: String(position.openPrice),
        markPrice: String(markPrice(market)),
        maintenanceMarginRate: String(position.maintenanceMarginRate),
        rules: rulesFor(market)
      }
    })
  })
  return oracle
}

function crossBoundaryMarkets(snapshot, opened, markets) {
  const projected = (factor) => new Map(opened.map(({ position }) => {
    const market = markets.get(position.symbol)
    const target = alignedPrice(market, Number(position.openPrice) * factor, 'FLOOR')
    return [position.symbol, marketWithMark(market, target)]
  }))
  let dangerous = 0.05
  let safe = 0.95
  assert.equal(crossRisk(snapshot, projected(safe)).liquidatable, false, 'cross safe bound')
  assert.equal(crossRisk(snapshot, projected(dangerous)).liquidatable, true, 'cross danger bound')
  for (let index = 0; index < 48; index += 1) {
    const candidate = (safe + dangerous) / 2
    if (crossRisk(snapshot, projected(candidate)).liquidatable) dangerous = candidate
    else safe = candidate
  }
  const targetMarkets = projected(dangerous)
  const risk = crossRisk(snapshot, targetMarkets)
  assert.equal(risk.liquidatable, true, 'cross boundary search')
  return {
    factor: dangerous,
    risk,
    targets: new Map([...targetMarkets].map(([symbol, market]) => [symbol, markPrice(market)]))
  }
}

function marketWithMark(market, value) {
  return {
    ...market,
    quote: { ...market.quote, bid: value, ask: value, mid: value, markPrice: value },
    reference: { ...market.reference, mark: value, markPrice: value }
  }
}

async function waitForNewPendingOrder(context, page, before, symbol) {
  return waitForAccount(context, page, `${symbol} active order`, (snapshot) => {
    const order = newRows(before.orders, snapshot.orders).find((candidate) => (
      candidate.symbol === symbol && ACTIVE_ORDER_STATUSES.has(candidate.status)
    ))
    return order ? { snapshot, order } : false
  })
}

async function observePositionOpen(scope, positionId, durationMs) {
  return observePositionsOpen(scope, [positionId], durationMs)
}

async function observePositionsOpen(scope, positionIds, durationMs) {
  const expected = new Set(positionIds)
  const deadline = Date.now() + durationMs
  let latest
  do {
    latest = await scope.context.api.snapshotAccount(scope.page)
    const actual = new Set(openPositions(latest).map(({ id }) => id))
    assert(
      [...expected].every((id) => actual.has(id)),
      `${scope.definition.id} must remain safe before threshold`
    )
    await delay(100)
  } while (Date.now() < deadline)
  return latest
}

async function waitForLiquidation(scope, positions, canceledOrderIds, timeoutMs) {
  const expectedPositions = new Set(positions.map(({ id }) => id))
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} liquidation completion`,
    async (snapshot) => {
      if (openPositions(snapshot).some(({ id }) => expectedPositions.has(id))) return false
      if (canceledOrderIds.some((id) => {
        const order = snapshot.orders.find((candidate) => candidate.id === id)
        return !order || !['CANCELED', 'CANCELLED'].includes(order.status)
      })) return false
      const db = await scope.context.db.snapshotTradingRows(snapshot.account.id)
      const complete = positions.every((position) => {
        const orders = db.orderRows.filter((row) => (
          row.parent_position_id === position.id
            && row.order_origin === 'LIQUIDATION'
            && row.status === 'FILLED'
        ))
        return orders.length === 1
          && db.tradeRows.filter((row) => row.order_id === orders[0].id).length === 1
      })
      return complete ? { snapshot, db } : false
    },
    timeoutMs
  )
}

async function waitForRiskOrdersCanceled(scope, positionIds, orderIds, timeoutMs) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} risk-order cancellation before liquidation`,
    (snapshot) => {
      if (!positionIds.every((id) => positionById(snapshot, id))) return false
      return orderIds.every((id) => {
        const order = snapshot.orders.find((candidate) => candidate.id === id)
        return order && ['CANCELED', 'CANCELLED'].includes(order.status)
      }) ? snapshot : false
    },
    timeoutMs
  )
}

async function freshAuthorityMarket(scope, expected) {
  const market = await scope.context.api.snapshotMarket(expected.symbol)
  assert.equal(
    isAuthorityMarketSnapshot(
      market,
      quoteDecimal(expected, 'bid'),
      quoteDecimal(expected, 'ask')
    ),
    true,
    `${expected.symbol} fresh post-cancel authority market`
  )
  return { ...market, feeRates: expected.feeRates }
}

async function createProtectionViaUi(scope, options) {
  const { page } = scope
  await page.evaluate(() => {
    const tab = [...document.querySelectorAll('[role="tab"]')]
      .find((candidate) => ['Current positions', '当前持仓', '現在ポジション']
        .includes(candidate.textContent?.trim()))
    if (!tab) throw new Error('P0_CURRENT_POSITIONS_TAB_MISSING')
    tab.click()
  })
  await page.waitForFunction((positionId) => {
    const row = document.querySelector(`[data-position-id="${positionId}"]`)
    return Boolean(row && [...row.querySelectorAll('button')].some((button) => !button.disabled))
  }, `position ${options.position.id} protection row`, options.position.id)
  await page.evaluate((positionId) => {
    const row = document.querySelector(`[data-position-id="${positionId}"]`)
    const button = [...(row?.querySelectorAll('button') ?? [])].find((candidate) => !candidate.disabled)
    if (!button) throw new Error('P0_PROTECTION_ACTION_BUTTON_MISSING')
    button.click()
  }, options.position.id)
  await page.waitForFunction(() => Boolean(
    document.querySelector('section[role="dialog"] [role="tablist"][aria-label="Position action type"]')
  ), 'Position protection dialog')
  await waitForValue(() => page.evaluate((values) => {
    const dialog = document.querySelector('section[role="dialog"]:has([aria-label="Position action type"])')
    const tab = [...(dialog?.querySelectorAll('[role="tab"]') ?? [])]
      .find((candidate) => candidate.textContent?.trim() === 'TP / SL')
    if (!tab || tab.disabled) throw new Error('P0_PROTECTION_TAB_UNAVAILABLE')
    if (tab.getAttribute('aria-selected') !== 'true') {
      tab.click()
      return false
    }
    const editor = dialog.querySelector('[aria-label="Position take-profit and stop-loss protections"]')
    if (!editor) return false
    if (editor.querySelectorAll('fieldset').length === 0) {
      const add = [...editor.querySelectorAll('button')]
        .find((button) => button.textContent?.trim() === '+ TP')
      add?.click()
      return false
    }
    const fieldset = editor.querySelector('fieldset')
    const setValue = (labelStart, value) => {
      const label = [...fieldset.querySelectorAll('label')]
        .find((candidate) => candidate.querySelector('span')?.textContent?.trim().startsWith(labelStart))
      const control = label?.querySelector('input, select')
      if (!control) throw new Error(`P0_PROTECTION_CONTROL_MISSING: ${labelStart}`)
      if (control.value === String(value)) return true
      const prototype = control instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(control, String(value))
      control.dispatchEvent(new Event('input', { bubbles: true }))
      control.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    if (!setValue('Trigger price', values.triggerPrice)) return false
    if (!setValue('Quantity', values.quantity)) return false
    if (!setValue('Execution', 'LIMIT')) return false
    if (!setValue('Limit price', values.triggerPrice)) return false
    const confirm = dialog.querySelector('footer button[type="submit"]')
    return Boolean(confirm && !confirm.disabled)
  }, {
    triggerPrice: options.triggerPrice,
    quantity: options.quantity
  }), 'ready TP/SL protection', 15000)
  const capture = await scope.context.ui.withCapturedMutation(
    page,
    { method: 'POST', url: /\/api\/trading\/positions\/[^/?]+\/protections$/ },
    () => page.evaluate(() => {
      const confirm = document.querySelector(
        'section[role="dialog"]:has([aria-label="Position action type"]) footer button[type="submit"]'
      )
      if (!confirm || confirm.disabled) throw new Error('P0_PROTECTION_CONFIRM_MISSING')
      confirm.click()
      return true
    })
  )
  assert(capture.status >= 200 && capture.status < 300, `protection status ${capture.status}`)
  return capture
}

async function assertLiquidationVisibleViaUi(page, symbols) {
  const expected = Array.isArray(symbols) ? symbols : [symbols]
  await page.evaluate(() => {
    const tab = [...document.querySelectorAll('[role="tab"]')]
      .find((candidate) => ['Position history', '历史持仓', 'ポジション履歴']
        .includes(candidate.textContent?.trim()))
    if (!tab) throw new Error('P0_POSITION_HISTORY_TAB_MISSING')
    tab.click()
  })
  await page.waitForFunction((items) => {
    const text = [...document.querySelectorAll('[role="tabpanel"] tbody tr')]
      .map((row) => row.textContent ?? '')
    return items.every((symbol) => text.some((row) => row.includes(symbol)))
  }, 'liquidated positions visible in history', expected)
}

async function assertIsolatedRiskVisibleViaUi(page, position, expectedPrice, tolerance) {
  await page.evaluate(() => {
    const tab = [...document.querySelectorAll('[role="tab"]')]
      .find((candidate) => ['Current positions', '当前持仓', '現在ポジション']
        .includes(candidate.textContent?.trim()))
    if (!tab) throw new Error('P0_CURRENT_POSITIONS_TAB_MISSING')
    tab.click()
  })
  await page.waitForFunction((positionId) => Boolean(
    document.querySelector(`tr[data-position-id="${positionId}"]`)
  ), 'isolated liquidation price row', position.id)
  const visible = await page.evaluate((positionId) => {
    const row = document.querySelector(`tr[data-position-id="${positionId}"]`)
    const cells = [...(row?.querySelectorAll('td') ?? [])]
    const note = [...document.querySelectorAll('aside[role="note"]')].find((candidate) => (
      candidate.textContent?.includes('Estimated liquidation price / 预计强平价')
    ))
    return {
      liquidationPrice: cells[6]?.textContent?.trim().replaceAll(',', '') ?? '',
      disclaimer: note?.textContent?.trim() ?? ''
    }
  }, position.id)
  assertDecimalClose(
    visible.liquidationPrice,
    expectedPrice,
    tolerance,
    `${position.id} UI estimated liquidation price`
  )
  assert.match(visible.disclaimer, /simplified Demo model|简化 Demo 模型/u, 'Demo liquidation disclaimer')
  return visible
}

async function assertAdminLiquidationVisible(scope, accountId, symbols) {
  const page = await scope.getAdminPage()
  const base = page.p0Options?.adminBaseUrl ?? 'http://127.0.0.1:5200'
  await page.navigate(`${base}/accounts/${encodeURIComponent(accountId)}`)
  await page.waitForFunction((expected) => {
    if (document.querySelector('.state-block.loading, .state-block.error')) return false
    const section = [...document.querySelectorAll('section.operation-section')].find((candidate) => (
      candidate.querySelector('h3')?.textContent?.trim() === '持仓'
    ))
    if (!section) return false
    const rows = [...section.querySelectorAll('tbody tr')].map((row) => row.textContent ?? '')
    return expected.every((symbol) => rows.some((row) => row.includes(symbol) && row.includes('CLOSED')))
  }, 'Admin account liquidation positions', symbols)
  scope.contractProbes.push({
    kind: 'ADMIN_ACCOUNT_LIQUIDATION_VISIBILITY',
    accountId,
    symbols
  })
}

function assertLiquidationFees(scope, lifecycle, market, db, options = {}) {
  const trade = lifecycle.trade
  assert(options.position, `${scope.definition.id} liquidation position oracle input`)
  const tradeOracle = assertLiquidationTradeContract({
    position: options.position,
    trade,
    market
  })
  const liquidationFeeRate = liquidationFeeRateForMarket(market)
  const collectionCapacity = options.collectionCapacity
    ?? (options.position.marginMode === 'ISOLATED'
      ? isolatedLiquidationCollectionCapacity({ position: options.position, tradeOracle })
      : '99999999999999999999.99999999')
  const oracle = liquidationFeeOracle({
    filledQuantity: String(valueOf(trade, 'lots', 'base_quantity', 'quantity')),
    executionPrice: String(valueOf(trade, 'price')),
    liquidationFeeRate,
    collectionCapacity,
    rules: rulesFor(market)
  })
  const expectedFeeCharged = options.expectedFeeCharged ?? oracle.chargedLiquidationFee
  if (moneyUnits(expectedFeeCharged) > 0n) {
    assert(lifecycle.ledger, `${scope.definition.id} liquidation fee ledger`)
    assert.equal(
      moneyUnits(valueOf(lifecycle.ledger, 'amount')),
      -moneyUnits(expectedFeeCharged),
      `${scope.definition.id} liquidation fee ledger amount`
    )
  } else {
    assert.equal(lifecycle.ledger, null, `${scope.definition.id} zero fee has no ledger`)
  }
  if (options.charge) {
    assert.equal(
      decimal8(valueOf(options.charge, 'feeDue', 'fee_due')),
      decimal8(oracle.nominalLiquidationFee),
      'contractual liquidation fee due'
    )
    assert.equal(
      decimal8(valueOf(options.charge, 'feeCharged', 'fee_charged')),
      decimal8(expectedFeeCharged),
      'liquidation fee charged'
    )
  }
  const order = lifecycle.order
  assert.equal(valueOf(order, 'liquidity_role'), 'TAKER', 'liquidation is taker')
  const positionId = idOf(order, 'parentPositionId', 'parent_position_id')
  assert(db.positionRows.some((row) => row.id === positionId), 'liquidation position row')
  return { ...oracle, ...tradeOracle, expectedFeeCharged, tradeFee: tradeOracle.closeFee }
}

function liquidationFeeRateForMarket(market) {
  const value = market?.feeRates?.liquidationFeeRate
  assert(Number.isFinite(Number(value)) && Number(value) >= 0, 'liquidation fee rate')
  return String(value)
}

function takerFeeRateForMarket(market) {
  const value = market?.feeRates?.takerFeeRate
  assert(Number.isFinite(Number(value)) && Number(value) >= 0, 'taker fee rate')
  return String(value)
}

async function reduceAccountRiskCapacity(scope, snapshot) {
  const accountId = snapshot.account.id
  const before = await jsonResult(scope.context, `
    SELECT json_build_array(json_build_object(
      'balance', balance::text,
      'equity', equity::text,
      'usedMargin', used_margin::text,
      'freeMargin', free_margin::text
    ))::text
    FROM core.trading_accounts
    WHERE id = '${sqlLiteral(accountId)}';
  `)
  assert.equal(before.length, 1, 'shortfall account row')
  const used = moneyUnits(snapshot.summary.usedMargin)
  assert(used >= 0n, 'shortfall used margin')
  const capacity = moneyText(used + 100000000n)
  const updated = Number(scalarResult(await scope.context.db.query(`
    WITH updated AS (
      UPDATE core.trading_accounts
      SET balance = ${capacity},
          equity = ${capacity},
          free_margin = 1.00000000
      WHERE id = '${sqlLiteral(accountId)}'
        AND account_type = 'DEMO'
        AND status = 'ACTIVE'
      RETURNING id
    )
    SELECT count(*) FROM updated;
  `)))
  assert.equal(updated, 1, 'shortfall fixture exact account scope')
  const evidence = {
    action: 'set-shortfall-risk-capacity',
    accountId,
    before: before[0],
    capacity,
    cleanup: 'CASE_OWNED_ACCOUNT_FINAL_STATE'
  }
  scope.fixtureActions.push(evidence)
  return evidence
}

async function snapshotShortfallDb(scope, accountId) {
  const [trading, charges, shortfalls, audits] = await Promise.all([
    scope.context.db.snapshotTradingRows(accountId),
    jsonResult(scope.context, `
      SELECT COALESCE(json_agg(row_to_json(candidate) ORDER BY candidate.created_at), '[]'::json)::text
      FROM (
        SELECT order_id::text, account_id::text, position_id::text,
          fee_due::text, fee_charged::text, status, created_at, settled_at
        FROM trading.cross_liquidation_charges
        WHERE account_id = '${sqlLiteral(accountId)}'
      ) candidate;
    `),
    jsonResult(scope.context, `
      SELECT COALESCE(json_agg(row_to_json(candidate) ORDER BY candidate.created_at), '[]'::json)::text
      FROM (
        SELECT id::text, amount::text, balance_after::text,
          reference_type, reference_id::text, created_at
        FROM ledger.ledger_entries
        WHERE account_id = '${sqlLiteral(accountId)}'
          AND entry_type = 'BANKRUPTCY_SHORTFALL'
      ) candidate;
    `),
    jsonResult(scope.context, `
      SELECT COALESCE(json_agg(row_to_json(candidate) ORDER BY candidate.created_at), '[]'::json)::text
      FROM (
        SELECT action, target_type, target_id::text, details, created_at
        FROM audit.audit_logs
        WHERE action = 'BANKRUPTCY_SHORTFALL'
          AND target_type = 'ACCOUNT'
          AND target_id = '${sqlLiteral(accountId)}'
      ) candidate;
    `)
  ])
  return { trading, charges, shortfalls, audits }
}

async function actionEvents(
  context,
  page,
  cursor,
  expectedTypes,
  label,
  timeoutMs = 15000
) {
  const deadline = Date.now() + timeoutMs
  let frames = []
  while (Date.now() < deadline) {
    frames = context.events.snapshotFrames(page).slice(cursor)
    if (expectedTypes.every((type) => frames.some(({ direction, eventType }) => (
      direction === 'received' && eventType === type
    )))) {
      return frames.filter(({ eventType }) => expectedTypes.includes(eventType))
    }
    await delay(100)
  }
  throw new Error(`${label} missing STOMP events: ${expectedTypes.join(', ')}`)
}

async function waitForAccount(
  context,
  page,
  description,
  predicate,
  timeoutMs = 30000
) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const snapshot = await context.api.snapshotAccount(page)
    const matched = await predicate(snapshot)
    if (matched) return typeof matched === 'object' ? matched : snapshot
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function waitForValue(producer, description, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await producer()
    if (value) return value
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
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

function scalarResult(raw) {
  return String(raw ?? '')
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !/^(?:INSERT|UPDATE|DELETE|SELECT)\b/iu.test(line))
    .at(-1) ?? ''
}

function safeAccountEvidence(snapshot) {
  return {
    account: snapshot.account,
    summary: snapshot.summary,
    wallets: snapshot.wallets,
    orders: snapshot.orders,
    trades: snapshot.trades,
    positions: snapshot.positions,
    positionHistory: snapshot.positionHistory,
    fundingSettlements: snapshot.fundingSettlements,
    assetLedger: snapshot.assetLedger
  }
}

function accountFundsFingerprint(snapshot) {
  return {
    account: {
      id: snapshot.account.id,
      status: snapshot.account.status,
      generation: snapshot.account.demoGeneration
    },
    summary: Object.fromEntries([
      'balance',
      'equity',
      'usedMargin',
      'freeMargin',
      'maintenanceMargin'
    ].map((field) => [field, snapshot.summary[field]])),
    wallets: snapshot.wallets.map((row) => ({
      walletType: row.walletType,
      asset: row.asset,
      total: row.total,
      available: row.available,
      locked: row.locked
    })).toSorted((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right))),
    orders: snapshot.orders.length,
    trades: snapshot.trades.length,
    positions: snapshot.positions.length,
    ledger: snapshot.assetLedger.length
  }
}

function rulesFor(market) {
  assert(market?.rules && typeof market.rules === 'object', 'symbol rules required')
  return market.rules
}

function quoteDecimal(market, field) {
  const value = market?.quote?.[field] ?? market?.reference?.[field]
  assert(Number.isFinite(Number(value)) && Number(value) > 0, `market ${field}`)
  return String(value)
}

function markPrice(market) {
  const value = market?.reference?.mark
    ?? market?.reference?.markPrice
    ?? market?.quote?.markPrice
    ?? market?.quote?.mid
    ?? (Number(quoteDecimal(market, 'bid')) + Number(quoteDecimal(market, 'ask'))) / 2
  assert(Number.isFinite(Number(value)) && Number(value) > 0, 'authority mark')
  return String(value)
}

function alignedPrice(market, value, rounding = 'FLOOR') {
  return alignPriceToTick(
    Number(value).toFixed(12).replace(/0+$/u, '').replace(/\.$/u, ''),
    rulesFor(market),
    rounding
  )
}

function openPositions(snapshot, symbol) {
  return (snapshot.positions ?? []).filter((position) => (
    String(position.instrumentType ?? position.productType ?? '').toUpperCase() !== 'SPOT'
      && (!symbol || position.symbol === symbol)
      && (position.status === undefined || position.status === 'OPEN')
  ))
}

function positionById(snapshot, positionId) {
  return openPositions(snapshot).find(({ id }) => id === positionId)
}

function positionSide(position) {
  const side = String(position.positionSide ?? position.side).toUpperCase()
  if (side === 'BUY') return 'LONG'
  if (side === 'SELL') return 'SHORT'
  assert(['LONG', 'SHORT'].includes(side), `position side ${side}`)
  return side
}

function newRows(before, after) {
  const known = new Set((before ?? []).map(({ id }) => id))
  return (after ?? []).filter(({ id }) => !known.has(id))
}

function idOf(object, ...fields) {
  return String(valueOf(object, ...fields) ?? '')
}

function valueOf(object, ...fields) {
  for (const field of fields) {
    if (object?.[field] !== undefined && object[field] !== null) return object[field]
  }
  return undefined
}

function decimal8(value) {
  return roundDecimal(String(value), 8, 'HALF_UP')
}

function decimal10(value) {
  return roundDecimal(String(value), 10, 'HALF_UP')
}

function moneyUnits(value) {
  const text = decimal8(value)
  const negative = text.startsWith('-')
  const [integer, fraction = ''] = text.replace(/^[+-]/u, '').split('.')
  const units = BigInt(integer) * 100000000n + BigInt(fraction.padEnd(8, '0'))
  return negative ? -units : units
}

function moneyText(value) {
  const negative = value < 0n
  const absolute = negative ? -value : value
  const integer = absolute / 100000000n
  const fraction = String(absolute % 100000000n).padStart(8, '0')
  return `${negative ? '-' : ''}${integer}.${fraction}`
}

function assertMoneyDelta(after, before, expected, label) {
  assert.equal(
    moneyUnits(after) - moneyUnits(before),
    moneyUnits(expected),
    label
  )
}

function assertDecimalClose(actual, expected, tolerance, label) {
  assert.equal(
    withinTolerance(String(actual), String(expected), String(tolerance)),
    true,
    `${label}: expected ${expected}, got ${actual}`
  )
}

function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

function slug(value) {
  return String(value).toLowerCase().replaceAll(/[^a-z0-9]+/gu, '-').replaceAll(/^-|-$/gu, '')
}
