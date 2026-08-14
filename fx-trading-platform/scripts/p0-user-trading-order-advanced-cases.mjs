import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

import {
  allowExpectedBatchBindingErrors,
  runSingleUserCoreCase
} from './p0-user-trading-core-cases.mjs'
import {
  DEMO_RATES,
  alignPriceToTick,
  effectiveQuantityStep,
  floorToStep,
  marketFillOracle,
  perpCloseOracle,
  spotOrderHoldOracle,
  spotSellOracle,
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
const OCO_ENDPOINT = '/api/trading/oco'

export function runSpot08(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runOcoWinnerJourney(scope, { side: 'SELL', winnerType: 'LIMIT', label: 'SPOT-08' })
  ))
}

export function runSpot09(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runOcoWinnerJourney(scope, { side: 'SELL', winnerType: 'STOP_MARKET', label: 'SPOT-09' })
  ))
}

export function runSpot10(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    runSpot10Journey,
    { kind: 'BUY_OCO_INDEPENDENT_WINNERS' }
  )
}

export function runSpot11(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    runSpot11Journey,
    { kind: 'OCO_VALIDATION_STALE_RECOVERY' }
  )
}

export function runBatch01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runBatch01Journey)
}

export function runProt01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runProt01Journey)
}

export function runProt02(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    (scope, subrun) => runMarketProtectionCase(scope, 'LONG', 'PROT-02', subrun),
    { kind: 'LONG_MARKET_PROTECTION_INDEPENDENT_PATHS' }
  )
}

export function runProt03(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    (scope, subrun) => runMarketProtectionCase(scope, 'SHORT', 'PROT-03', subrun),
    { kind: 'SHORT_MARKET_PROTECTION_INDEPENDENT_PATHS' }
  )
}

export function runProt04(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    runProt04Journey,
    { kind: 'LIMIT_PROTECTION_INDEPENDENT_PATHS' }
  )
}

export function runProt05(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runProt05Journey)
}

export function runProt06(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runProt06Journey)
}

export function runWallet02(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    runWallet02Journey,
    { kind: 'TRANSFER_INDEPENDENT_IDEMPOTENCY_PATHS' }
  )
}

export function runLife01(context, definition, details = {}) {
  return runIndependentSubruns(
    context,
    definition,
    details,
    runLife01Journey,
    { kind: 'DEMO_RESET_INDEPENDENT_BLOCKERS' }
  )
}

export function runLife03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runLife03Journey)
}

export const CASE_HANDLERS = Object.freeze({
  runSpot08,
  runSpot09,
  runSpot10,
  runSpot11,
  runBatch01,
  runProt01,
  runProt02,
  runProt03,
  runProt04,
  runProt05,
  runProt06,
  runWallet02,
  runLife01,
  runLife03
})

async function runIndependentSubruns(
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
    status: 'PASS',
    durationMs: Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt)),
    scopeComplete: true,
    artifactHashes,
    subruns: results.flatMap(({ subruns }) => subruns),
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
    financialCalculation: { status: 'PASS', checks: oracleEvidence },
    oracleEvidence,
    snapshots: Object.fromEntries(results.flatMap((result, index) => (
      Object.entries(result.snapshots ?? {}).map(([name, value]) => [
        `${result.subruns[0]?.id ?? index}:${name}`,
        value
      ])
    ))),
    consoleErrors: results.flatMap(({ consoleErrors }) => consoleErrors ?? []),
    cleanup: { status: 'PASS' }
  }
}

async function runSpot10Journey(scope, subrun) {
  const winnerType = {
    'desktop-limit-wins': 'LIMIT',
    'desktop-stop-wins': 'STOP_MARKET'
  }[subrun.id]
  assert(winnerType, `SPOT-10 unsupported subrun ${subrun.id}`)
  return runOcoWinnerJourney(scope, {
    side: 'BUY',
    winnerType,
    label: `SPOT-10 ${winnerType === 'LIMIT' ? 'limit' : 'stop'}`
  })
}

async function runOcoWinnerJourney(scope, options) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  let market = await freezeMarket(scope, symbol)
  const rules = rulesFor(market)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  let before = await context.api.snapshotAccount(page)

  if (options.side === 'SELL') {
    const seed = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'MARKET',
      amount: '100'
    })
    scope.addMutation(`${slug(options.label)}-seed-btc-via-ui`, seed)
    before = (await waitForFilledMutation(scope, before, seed, symbol)).snapshot
    await scope.capture(`${slug(options.label)}-seeded`, before)
  }

  market = await context.api.snapshotMarket(symbol)
  const last = lastPrice(market)
  const quantity = options.side === 'SELL'
    ? floorToStep(
        String(findWallet(before, 'SPOT', 'BTC').available),
        effectiveQuantityStep(rules)
      )
    : stepAlignedQuantity(market, 0.001)
  assert(Number(quantity) > 0, `${options.label} OCO quantity`)
  const limitPrice = alignedPrice(
    market,
    last * (options.side === 'SELL' ? 1.03 : 0.97)
  )
  const stopTriggerPrice = alignedPrice(
    market,
    last * (options.side === 'SELL' ? 0.97 : 1.03)
  )
  const beforeFrames = context.events.snapshotFrames(page).length
  const capture = await submitOcoViaUi(scope, {
    side: options.side,
    quantity,
    limitPrice,
    stopTriggerPrice
  })
  scope.addMutation(`${slug(options.label)}-oco-via-ui`, capture)
  const pending = await waitForOcoGroup(scope, before, capture)
  const hold = spotOrderHoldOracle({
    side: options.side,
    orderType: 'OCO',
    baseQuantity: quantity,
    limitPrice,
    stopTriggerPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertOcoPending(before, pending.snapshot, pending.legs, hold, rules, options.label)
  const pendingEvidence = await scope.capture(
    `${slug(options.label)}-oco-pending`,
    pending.snapshot
  )
  assertOcoDbGroup(pendingEvidence.db, pending.groupId, pending.legs, options.label)
  const pendingEvents = await actionEvents(
    scope,
    beforeFrames,
    ['ORDER_PENDING'],
    `${options.label} OCO pending`
  )

  const winner = pending.legs.find(({ orderType }) => orderType === options.winnerType)
  const peer = pending.legs.find(({ id }) => id !== winner.id)
  assert(winner && peer, `${options.label} OCO legs`)
  if (options.label === 'SPOT-09') {
    const disabled = await probeOcoModifyDisabled(scope, peer)
    assert.equal(disabled.disabled, true)
    assert.equal(disabled.requestSent, false)
    scope.contractProbes.push({
      kind: 'OCO_ORDER_NOT_MODIFIABLE',
      status: 'PASS',
      orderId: peer.id,
      requestSent: false
    })
    await context.ui.openTradePanel(page, { product: 'spot', symbol })
  }
  const target = options.winnerType === 'LIMIT'
    ? Number(limitPrice)
    : Number(stopTriggerPrice)
  const multiplier = options.side === 'SELL'
    ? (options.winnerType === 'LIMIT' ? 1.01 : 0.99)
    : (options.winnerType === 'LIMIT' ? 0.99 : 1.01)
  const fillCursor = context.events.snapshotFrames(page).length
  const triggerMarket = await setMarketAround(scope, symbol, target * multiplier)
  const completed = await waitForOcoOutcome(scope, pending.groupId, winner.id, peer.id)
  const expectedLiquidity = options.winnerType === 'LIMIT' ? 'MAKER' : 'TAKER'
  assert.equal(completed.winner.liquidityRole, expectedLiquidity)
  assert.equal(completed.winner.side, options.side)
  assert.equal(completed.peer.status, 'CANCELED')
  assert.equal(completed.groupTrades.length, 1)
  assertNear(
    findWallet(completed.snapshot, 'SPOT', hold.currency).locked,
    0,
    `${options.label} shared hold released`
  )
  const completedEvidence = await scope.capture(
    `${slug(options.label)}-oco-completed`,
    completed.snapshot
  )
  const financial = assertSpotExecutionFinancials({
    before,
    after: completed.snapshot,
    trade: completed.groupTrades[0],
    rules,
    expectedLiquidity,
    expectedPrice: options.winnerType === 'LIMIT' ? limitPrice : undefined,
    executionMarket: triggerMarket,
    label: options.label
  })
  const ledger = assertSpotTradeDbLedger(
    completedEvidence.db,
    completed.groupTrades[0],
    rules,
    options.label
  )
  assertSingleOcoRelease(
    pendingEvidence.db,
    completedEvidence.db,
    pending.legs,
    options.label
  )
  const fillEvents = await actionEvents(
    scope,
    fillCursor,
    ['ORDER_FILLED', 'ORDER_CANCELED'],
    `${options.label} OCO outcome`
  )

  let finalSnapshot = completed.snapshot
  let finalDb = completedEvidence.db
  const btc = findWallet(finalSnapshot, 'SPOT', 'BTC')
  const cleanupQuantity = floorToStep(
    String(btc.available),
    effectiveQuantityStep(rules)
  )
  if (Number(cleanupQuantity) > 0) {
    await context.ui.openTradePanel(page, { product: 'spot', symbol })
    const cleanup = await context.ui.submitOrderViaUi(page, {
      side: 'SELL',
      orderType: 'MARKET',
      amount: cleanupQuantity
    })
    scope.addMutation(`${slug(options.label)}-btc-cleanup-via-ui`, cleanup)
    finalSnapshot = (await waitForFilledMutation(
      scope,
      finalSnapshot,
      cleanup,
      symbol
    )).snapshot
    finalDb = (await scope.capture(`${slug(options.label)}-cleaned`, finalSnapshot)).db
  }

  const evidence = {
    groupId: pending.groupId,
    orderIds: pending.legs.map(({ id }) => id),
    winnerId: winner.id,
    peerId: peer.id,
    sharedHold: hold,
    pendingEvents,
    fillEvents,
    liquidityRole: expectedLiquidity,
    financial,
    ledger
  }
  if (!options.label.startsWith('SPOT-10')) {
    scope.oracleEvidence.push({ kind: 'SPOT_OCO_WINNER', ...evidence })
  }
  return { finalSnapshot, finalDb, evidence }
}

async function runSpot11Journey(scope, subrun) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  const recovery = subrun.id === 'desktop-recovery-trigger'
  assert(
    recovery || subrun.id === 'desktop-validation-stale',
    `SPOT-11 unsupported subrun ${subrun.id}`
  )
  let market = recovery
    ? await freezeMarket(scope, symbol)
    : await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  const initial = await context.api.snapshotAccount(page)
  const seed = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '100'
  })
  scope.addMutation('spot-11-seed-btc-via-ui', seed)
  const seeded = (await waitForFilledMutation(scope, initial, seed, symbol)).snapshot
  market = await context.api.snapshotMarket(symbol)
  const last = lastPrice(market)
  const quantity = floorToStep(
    String(findWallet(seeded, 'SPOT', 'BTC').available),
    effectiveQuantityStep(rules)
  )
  if (!recovery) {
    const cleanFingerprint = tradingStateFingerprint(seeded)
    const invalidSell = await submitOcoViaUi(scope, {
      side: 'SELL',
      quantity,
      limitPrice: alignedPrice(market, last * 0.98),
      stopTriggerPrice: alignedPrice(market, last * 1.02),
      expectFailure: true,
      reason: 'SPOT-11 invalid SELL relationship'
    })
    assertRejectedCode(invalidSell, 'OCO_PRICE_RELATION_INVALID', 'SPOT-11 SELL')
    const invalidBuy = await submitOcoViaUi(scope, {
      side: 'BUY',
      quantity: stepAlignedQuantity(market, 0.001),
      limitPrice: alignedPrice(market, last * 1.02),
      stopTriggerPrice: alignedPrice(market, last * 0.98),
      expectFailure: true,
      reason: 'SPOT-11 invalid BUY relationship'
    })
    assertRejectedCode(invalidBuy, 'OCO_PRICE_RELATION_INVALID', 'SPOT-11 BUY')
    const insufficient = await submitOcoViaUi(scope, {
      side: 'SELL',
      quantity: multiplyAlignedQuantity(quantity, 2, rules),
      limitPrice: alignedPrice(market, last * 1.03),
      stopTriggerPrice: alignedPrice(market, last * 0.97),
      expectFailure: true,
      reason: 'SPOT-11 insufficient BTC'
    })
    assertRejectedCode(insufficient, 'INSUFFICIENT_BALANCE', 'SPOT-11 balance')
    assertTradingStateEqual(
      cleanFingerprint,
      tradingStateFingerprint(await context.api.snapshotAccount(page)),
      'SPOT-11 rejected OCO zero mutation'
    )
  }

  const limitPrice = alignedPrice(market, last * 1.03)
  const stopTriggerPrice = alignedPrice(market, last * 0.97)
  const submitCursor = page.p0Evidence.cursor
  const legal = await submitOcoViaUi(scope, {
    side: 'SELL',
    quantity,
    limitPrice,
    stopTriggerPrice,
    doubleConfirm: !recovery
  })
  scope.addMutation(
    recovery ? 'spot-11-recovery-oco-via-ui' : 'spot-11-controlled-double-click-via-ui',
    legal
  )
  if (!recovery) {
    await delay(250)
    const createRequests = page.p0Evidence.requests.filter((request) => (
      request.cursor > submitCursor
        && request.method === 'POST'
        && /\/api\/trading\/oco$/u.test(request.url)
    ))
    assert.equal(createRequests.length, 1, 'SPOT-11 controlled double click sends once')
  }
  const pending = await waitForOcoGroup(scope, seeded, legal)
  const afterCreate = pending.snapshot
  const afterCreateDb = await context.db.snapshotTradingRows(afterCreate.account.id)
  let replay
  let afterReplay = afterCreate
  let afterReplayDb = afterCreateDb
  if (!recovery) {
    replay = await replayCapturedMutation(scope, page, legal)
    const replayBody = parsedResponse(replay)?.data ?? parsedResponse(replay)
    assert.equal(replayBody.contingencyGroupId, pending.groupId)
    afterReplay = await context.api.snapshotAccount(page)
    afterReplayDb = await context.db.snapshotTradingRows(afterReplay.account.id)
    assert.equal(recordsAfter(afterCreate, afterReplay, 'orders').length, 0)
    assertDecimalClose(
      findWallet(afterReplay, 'SPOT', 'BTC').locked,
      findWallet(afterCreate, 'SPOT', 'BTC').locked,
      tolerancesFromRules(rules).amount,
      'SPOT-11 replay hold unchanged'
    )
    assertDbReplayUnchanged(afterCreateDb, afterReplayDb, 'SPOT-11 replay')
  }

  const adminPage = await scope.getAdminPage()
  const bindingCursorStart = page.p0Evidence.cursor
  const binding = await context.fixtures.providerBindings(adminPage, {
    symbol,
    enabledProviders: []
  })
  let restored = false
  const restoreBinding = async () => {
    if (restored) return
    await binding.restore()
    restored = true
  }
  scope.registerFixtureRestore({ action: 'restore-provider-bindings', symbol }, restoreBinding)
  scope.fixtureActions.push({
    action: 'disable-provider-bindings',
    symbol,
    before: binding.before,
    after: binding.after
  })
  await delay(2500)
  const staleDb = await context.db.snapshotTradingRows(afterReplay.account.id)
  const staleLegs = staleDb.orderRows.filter(({ id }) => (
    pending.legs.some((leg) => leg.id === id)
  ))
  assert.equal(staleLegs.length, 2)
  assert.equal(staleLegs.every(({ status }) => status === 'PENDING'), true)
  assert.equal(staleDb.tradeRows.length, afterReplayDb.tradeRows.length)
  const beforeBtc = afterReplayDb.walletRows.find(({ wallet_type: type, asset }) => (
    type === 'SPOT' && asset === 'BTC'
  ))
  const staleBtc = staleDb.walletRows.find(({ wallet_type: type, asset }) => (
    type === 'SPOT' && asset === 'BTC'
  ))
  assertDecimalClose(staleBtc.locked, beforeBtc.locked, '0.00000001', 'SPOT-11 stale hold')
  await restoreBinding()
  const bindingCursorEnd = page.p0Evidence.cursor
  scope.fixtureActions.push({ action: 'restore-provider-bindings', symbol, status: 'PASS' })
  const accountId = afterReplay.account.id
  const apiBaseUrl = page.p0Options.apiBaseUrl
  await allowExpectedBatchBindingErrors(page, new Set([
    `${apiBaseUrl}/api/market/quotes/${symbol}`,
    `${apiBaseUrl}/api/market/order-book/${symbol}`,
    `${apiBaseUrl}/api/market/trades/${symbol}?limit=40`,
    `${apiBaseUrl}/api/accounts/${accountId}/summary`,
    `${apiBaseUrl}/api/trading/positions?accountId=${accountId}&page=0&size=100`
  ]), bindingCursorStart, bindingCursorEnd)
  if (!recovery) {
    const cancel = await context.ui.cancelAllOrdersViaUi(page)
    scope.addMutation('spot-11-validation-cleanup-via-ui', cancel)
    const terminal = await waitForAccount(
      context,
      page,
      'SPOT-11 validation cleanup',
      (candidate) => activeOrders(candidate).length === 0 && candidate
    )
    const finalEvidence = await scope.capture('spot-11-validation-final', terminal)
    scope.replayProbes.push({
      kind: 'OCO_IDEMPOTENT_REPLAY',
      status: 'PASS',
      requestRef: replay.requestRef,
      contingencyGroupId: pending.groupId
    })
    scope.oracleEvidence.push({
      kind: 'OCO_STALE_NO_MUTATION',
      groupId: pending.groupId,
      staleStatuses: staleLegs.map(({ status }) => status)
    })
    return { finalSnapshot: terminal, finalDb: finalEvidence.db }
  }

  const triggerMarket = await setMarketAround(scope, symbol, Number(limitPrice) * 1.01)
  const limitLeg = pending.legs.find(({ orderType }) => orderType === 'LIMIT')
  const stopLeg = pending.legs.find(({ orderType }) => orderType === 'STOP_MARKET')
  const completed = await waitForOcoOutcome(
    scope,
    pending.groupId,
    limitLeg.id,
    stopLeg.id
  )
  assert.equal(completed.groupTrades.length, 1)
  const completedEvidence = await scope.capture('spot-11-recovered', completed.snapshot)
  const financial = assertSpotExecutionFinancials({
    before: seeded,
    after: completed.snapshot,
    trade: completed.groupTrades[0],
    rules,
    expectedLiquidity: 'MAKER',
    expectedPrice: limitPrice,
    label: 'SPOT-11 recovery'
  })
  scope.oracleEvidence.push({
    kind: 'OCO_STALE_RECOVERY',
    groupId: pending.groupId,
    staleStatuses: staleLegs.map(({ status }) => status),
    recoveredWinnerId: completed.winner.id,
    triggerMarket: {
      bid: quoteDecimal(triggerMarket, 'bid'),
      ask: quoteDecimal(triggerMarket, 'ask')
    },
    financial
  })
  return { finalSnapshot: completed.snapshot, finalDb: completedEvidence.db }
}

async function submitOcoViaUi(scope, options) {
  const { context, page } = scope
  if (!page.p0TradePanel) {
    await context.ui.openTradePanel(page, { product: 'spot', symbol: 'BTCUSDT' })
  }
  const panelSelector = page.p0TradePanel.selector
  await page.evaluate((selector) => {
    const panel = document.querySelector(selector)
    const tabs = [...(panel?.querySelectorAll('[role="tablist"]') ?? [])]
      .map((list) => [...list.querySelectorAll(':scope > [role="tab"]')])
      .find((candidates) => candidates.some((tab) => tab.textContent?.trim() === 'OCO'))
    const oco = tabs?.find((tab) => tab.textContent?.trim() === 'OCO')
    if (!(oco instanceof HTMLButtonElement) || oco.disabled) {
      throw new Error('P0_OCO_TAB_MISSING')
    }
    oco.click()
  }, panelSelector)
  await waitUntil(async () => page.evaluate((selector, values) => {
    const panel = document.querySelector(selector)
    const form = panel?.querySelector(
      `section[data-price-precision][class*="side--${values.side.toLowerCase()}"]`
    )
    const oco = [...(panel?.querySelectorAll('[role="tab"]') ?? [])]
      .find((tab) => tab.textContent?.trim() === 'OCO')
    if (!form || oco?.getAttribute('aria-selected') !== 'true') return false
    const inputs = [...form.querySelectorAll('input[inputmode="decimal"]')]
    if (inputs.length < 3) return false
    const setValue = (input, value) => {
      if (input.value === String(value) && input.defaultValue === String(value)) return true
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
      setter?.call(input, String(value))
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    const triggerReady = setValue(inputs[0], values.stopTriggerPrice)
    const limitReady = setValue(inputs[1], values.limitPrice)
    const quantityReady = setValue(inputs.at(-1), values.quantity)
    return triggerReady && limitReady && quantityReady
  }, panelSelector, options), 'OCO fields')
  await waitUntil(async () => page.evaluate((selector, side) => {
    const form = document.querySelector(selector)?.querySelector(
      `section[data-price-precision][class*="side--${side.toLowerCase()}"]`
    )
    const submit = form?.querySelector('[data-trading-action="submit-order"]')
    if (!(submit instanceof HTMLButtonElement) || submit.disabled) return false
    submit.click()
    return true
  }, panelSelector, options.side), 'OCO submit')
  await page.waitForFunction(
    () => Boolean([...document.querySelectorAll('section[role="dialog"]')]
      .find((dialog) => dialog.textContent?.includes('OCO / GTC'))),
    'OCO confirmation'
  )
  const capture = await context.ui.withCapturedMutation(
    page,
    ({ method, url }) => method === 'POST' && url.endsWith(OCO_ENDPOINT),
    () => page.evaluate((doubleConfirm) => {
      const dialog = [...document.querySelectorAll('section[role="dialog"]')]
        .find((candidate) => candidate.textContent?.includes('OCO / GTC'))
      const confirm = dialog?.querySelector('footer button:last-child')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_OCO_CONFIRM_MISSING')
      }
      confirm.click()
      if (doubleConfirm) confirm.click()
      return true
    }, options.doubleConfirm === true)
  )
  if (options.expectFailure) {
    assert.equal(capture.status >= 400 && capture.status < 500, true)
    page.allowHttpError(capture.requestRef, options.reason ?? 'expected OCO rejection')
  } else {
    assertSuccess(capture, 'OCO submission')
  }
  return capture
}

async function waitForOcoGroup(scope, before, capture) {
  const response = parsedResponse(capture)?.data ?? parsedResponse(capture)
  const expectedGroupId = response?.contingencyGroupId
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} OCO pending`,
    (snapshot) => {
      const created = recordsAfter(before, snapshot, 'orders')
      const groupId = expectedGroupId
        ?? created.find(({ contingencyGroupId }) => contingencyGroupId)?.contingencyGroupId
      const legs = snapshot.orders.filter((order) => (
        order.contingencyGroupId === groupId
      ))
      return groupId && legs.length === 2 && legs.every(({ status }) => (
        ACTIVE_ORDER_STATUSES.has(status)
      ))
        ? { snapshot, groupId, legs }
        : false
    }
  )
}

async function waitForOcoOutcome(scope, groupId, winnerId, peerId) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} OCO outcome`,
    (snapshot) => {
      const winnerOrder = orderById(snapshot, winnerId)
      const peer = orderById(snapshot, peerId)
      const groupTrades = snapshot.trades.filter(({ orderId }) => (
        [winnerId, peerId].includes(orderId)
      ))
      const winner = groupTrades.find(({ orderId }) => orderId === winnerId)
      return winnerOrder?.status === 'FILLED'
        && peer?.status === 'CANCELED'
        && winner
        && groupTrades.length === 1
        ? { snapshot, winner, winnerOrder, peer, groupTrades, groupId }
        : false
    },
    45000
  )
}

function assertOcoPending(before, after, legs, hold, rules, label) {
  assert.equal(legs.length, 2, `${label} exact OCO legs`)
  assert.equal(new Set(legs.map(({ contingencyGroupId }) => contingencyGroupId)).size, 1)
  assert.deepEqual(legs.map(({ orderType }) => orderType).toSorted(), ['LIMIT', 'STOP_MARKET'])
  assert.equal(legs.every(({ orderOrigin }) => orderOrigin === 'OCO'), true)
  assert.equal(legs.every(({ status }) => ACTIVE_ORDER_STATUSES.has(status)), true)
  assertDecimalClose(
    legs.reduce((sum, { holdAmount }) => sum + Number(holdAmount ?? 0), 0),
    hold.amount,
    tolerancesFromRules(rules).amount,
    `${label} one shared order hold`
  )
  const beforeWallet = findWallet(before, 'SPOT', hold.currency)
  const afterWallet = findWallet(after, 'SPOT', hold.currency)
  assertDecimalClose(
    Number(afterWallet.locked) - Number(beforeWallet.locked),
    hold.amount,
    tolerancesFromRules(rules).amount,
    `${label} wallet shared hold`
  )
  assert.equal(recordsAfter(before, after, 'trades').length, 0)
}

function assertOcoDbGroup(db, groupId, legs, label) {
  const rows = db.orderRows.filter(({ contingency_group_id: id }) => id === groupId)
  assert.equal(rows.length, 2, `${label} DB OCO legs`)
  assert.deepEqual(rows.map(({ order_type: type }) => type).toSorted(), ['LIMIT', 'STOP_MARKET'])
  assert.equal(new Set(rows.map(({ account_id: id }) => id)).size, 1)
  assert.deepEqual(rows.map(({ id }) => id).toSorted(), legs.map(({ id }) => id).toSorted())
}

function assertSingleOcoRelease(beforeDb, afterDb, legs, label) {
  const legIds = new Set(legs.map(({ id }) => id))
  const rows = recordsAfter(beforeDb, afterDb, 'assetLedgerRows').filter((entry) => (
    entry.entry_type === 'SPOT_ORDER_RELEASE'
      && entry.reference_type === 'ORDER'
      && legIds.has(entry.reference_id)
  ))
  assert.equal(rows.length, 1, `${label} shared hold released once`)
}

async function probeOcoModifyDisabled(scope, order) {
  const { page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/orders`)
  await page.waitForFunction(
    () => window.location.pathname === '/orders',
    `${scope.definition.id} orders route`
  )
  await page.waitForFunction((orderId) => Boolean(
    document.querySelector(`[data-order-id="${orderId}"]`)
  ), `${scope.definition.id} OCO order row`, order.id)
  const cursor = page.p0Evidence.cursor
  const disabled = await page.evaluate((orderId) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    const modify = row?.querySelectorAll('button')[1]
    if (!(modify instanceof HTMLButtonElement)) throw new Error('P0_OCO_MODIFY_MISSING')
    modify.click()
    return modify.disabled
  }, order.id)
  await delay(100)
  const requestSent = page.p0Evidence.requests.some((request) => (
    request.cursor > cursor
      && request.method === 'PATCH'
      && request.url.endsWith(`/api/trading/orders/${order.id}`)
  ))
  return { disabled, requestSent }
}

async function runBatch01Journey(scope) {
  const { context, page } = scope
  const spotSymbol = 'BTCUSDT'
  let spotMarket = await context.api.snapshotMarket(spotSymbol)
  await context.ui.openTradePanel(page, { product: 'spot', symbol: spotSymbol })
  let snapshot = await context.api.snapshotAccount(page)
  for (const multiplier of [0.65, 0.7]) {
    const capture = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'LIMIT',
      price: alignedPrice(spotMarket, Number(quoteDecimal(spotMarket, 'bid')) * multiplier),
      amount: stepAlignedQuantity(spotMarket, 0.001)
    })
    scope.addMutation('batch-01-spot-limit-via-ui', capture)
    snapshot = (await waitForCreatedOrder(
      scope,
      snapshot,
      capture,
      spotSymbol,
      'PENDING'
    )).snapshot
  }
  spotMarket = await context.api.snapshotMarket(spotSymbol)
  const spotLast = lastPrice(spotMarket)
  const ocoCapture = await submitOcoViaUi(scope, {
    side: 'BUY',
    quantity: stepAlignedQuantity(spotMarket, 0.001),
    limitPrice: alignedPrice(spotMarket, spotLast * 0.75),
    stopTriggerPrice: alignedPrice(spotMarket, spotLast * 1.25)
  })
  scope.addMutation('batch-01-spot-oco-via-ui', ocoCapture)
  snapshot = (await waitForOcoGroup(scope, snapshot, ocoCapture)).snapshot

  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.02',
    label: 'BATCH-01 protection position'
  })
  snapshot = opened.snapshot
  const protection = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'TAKE_PROFIT',
    quantity: '0.01',
    triggerPrice: alignedPrice(opened.market, Number(opened.position.openPrice) * 1.5),
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation('batch-01-protection-via-ui', protection.capture)
  snapshot = await waitForProtectionCount(scope, opened.position.id, 1)

  const perpMarket = await context.api.snapshotMarket('BTCUSDT-PERP')
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: 'BTCUSDT-PERP'
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  for (const multiplier of [0.5, 0.55]) {
    const capture = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'LIMIT',
      price: alignedPrice(perpMarket, Number(quoteDecimal(perpMarket, 'bid')) * multiplier),
      amount: stepAlignedQuantity(perpMarket, 0.01)
    })
    scope.addMutation('batch-01-perp-limit-via-ui', capture)
    snapshot = (await waitForCreatedOrder(
      scope,
      snapshot,
      capture,
      'BTCUSDT-PERP',
      'PENDING'
    )).snapshot
  }

  const activeBefore = activeOrders(snapshot)
  assert(activeBefore.length >= 7, 'BATCH-01 mixed active order count')
  assert(activeBefore.some(({ contingencyGroupId }) => contingencyGroupId), 'BATCH-01 OCO')
  assert(activeBefore.some(({ protectionType }) => protectionType), 'BATCH-01 protection')
  const beforeCancel = snapshot
  const beforeCancelDb = await context.db.snapshotTradingRows(snapshot.account.id)
  const cancelAll = await context.ui.cancelAllOrdersViaUi(page)
  scope.addMutation('batch-01-cancel-all-via-ui', cancelAll)
  const response = parsedResponse(cancelAll)?.data ?? parsedResponse(cancelAll)
  assert.equal(Array.isArray(response?.items), true, 'BATCH-01 response items')
  const activeIds = new Set(activeBefore.map(({ id }) => id))
  const resultIds = new Set(response.items.map(({ orderId }) => orderId).filter(Boolean))
  for (const orderId of activeIds) {
    assert(resultIds.has(orderId), `BATCH-01 missing item ${orderId}`)
  }
  assert.equal(
    response.items.every(({ status, errorCode }) => !errorCode && status === 'CANCELED'),
    true,
    'BATCH-01 per-item cancellation status'
  )
  const canceled = await waitForAccount(
    context,
    page,
    'BATCH-01 cancel-all terminal state',
    (candidate) => activeOrders(candidate).length === 0 && candidate
  )
  assert.equal(recordsAfter(beforeCancel, canceled, 'trades').length, 0)
  assertNear(findWallet(canceled, 'SPOT', 'USDT').locked, 0, 'BATCH-01 Spot hold')
  const canceledEvidence = await scope.capture('batch-01-canceled', canceled)
  assertBatchReleaseRows(beforeCancelDb, canceledEvidence.db, activeBefore)

  const repeat = await context.ui.cancelAllOrdersViaUi(page)
  scope.addMutation('batch-01-repeat-cancel-all-via-ui', repeat)
  const repeatResponse = parsedResponse(repeat)?.data ?? parsedResponse(repeat)
  assert.equal(Array.isArray(repeatResponse?.items), true)
  assert.equal(repeatResponse.items.length, 0, 'BATCH-01 repeat is empty')
  const afterRepeat = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    tradingStateFingerprint(canceled),
    tradingStateFingerprint(afterRepeat),
    'BATCH-01 repeat zero mutation'
  )
  const closed = await closePerpPosition(scope, afterRepeat, opened.position, 'BATCH-01 cleanup')
  const finalEvidence = await scope.capture('batch-01-final', closed.snapshot)
  scope.oracleEvidence.push({
    kind: 'BATCH_CANCEL_ALL',
    activeOrderIds: [...activeIds],
    items: response.items,
    repeatItems: repeatResponse.items
  })
  return { finalSnapshot: closed.snapshot, finalDb: finalEvidence.db }
}

async function runProt01Journey(scope) {
  const market = await freezeMarket(scope, 'BTCUSDT-PERP')
  const entry = markPrice(market)
  const levels = [
    protectionLevel(market, 'TAKE_PROFIT', '0.01', entry * 1.02, 'MARKET'),
    protectionLevel(market, 'TAKE_PROFIT', '0.01', entry * 1.03, 'LIMIT', entry * 1.04),
    protectionLevel(market, 'STOP_LOSS', '0.01', entry * 0.98, 'MARKET'),
    protectionLevel(market, 'STOP_LOSS', '0.01', entry * 0.97, 'LIMIT', entry * 0.96)
  ]
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.04',
    label: 'PROT-01',
    attachedProtections: levels
  })
  const request = parsedRequest(opened.capture)
  assert.equal(Array.isArray(request.attachedProtections), true)
  assert.equal(request.attachedProtections.length, 4)
  const activated = await waitForProtectionCount(scope, opened.position.id, 4)
  const protections = protectionOrders(activated, opened.position.id)
  assert.equal(protections.every(({ parentOrderId }) => parentOrderId === opened.order.id), true)
  assert.equal(protections.every(({ parentPositionId }) => parentPositionId === opened.position.id), true)
  assert.equal(protections.every(({ triggerPriceType }) => triggerPriceType === 'MARK_PRICE'), true)
  assert.equal(protections.every(({ reduceOnly }) => reduceOnly === true), true)
  assert.equal(protections.every(({ orderOrigin }) => orderOrigin === 'PROTECTIVE'), true)
  assertProtectionBudgets(protections, Number(opened.position.lots), 'PROT-01')
  const activatedEvidence = await scope.capture('prot-01-activated', activated)
  const rows = activatedEvidence.db.orderRows.filter(({ parent_position_id: id }) => (
    id === opened.position.id
  ))
  assert.equal(rows.length, 4)
  assert.equal(rows.every(({ parent_order_id: id }) => id === opened.order.id), true)
  const closed = await closePerpPosition(scope, activated, opened.position, 'PROT-01 cleanup')
  const terminal = await waitForAccount(
    scope.context,
    scope.page,
    'PROT-01 protection cleanup',
    (candidate) => protectionOrders(candidate, opened.position.id)
      .every(({ status }) => !ACTIVE_ORDER_STATUSES.has(status)) && candidate
  )
  const finalEvidence = await scope.capture('prot-01-final', terminal)
  scope.oracleEvidence.push({
    kind: 'ATTACHED_PROTECTIONS_ACTIVATED',
    parentOrderId: opened.order.id,
    parentPositionId: opened.position.id,
    protectionIds: protections.map(({ id }) => id)
  })
  return { finalSnapshot: terminal, finalDb: finalEvidence.db ?? closed.db }
}

async function runMarketProtectionCase(scope, positionSide, label, subrun) {
  const protectionType = subrun.id.endsWith('take-profit')
    ? 'TAKE_PROFIT'
    : subrun.id.endsWith('stop-loss')
      ? 'STOP_LOSS'
      : undefined
  assert(protectionType, `${label} unsupported subrun ${subrun.id}`)
  return runOneMarketProtection(
    scope,
    positionSide,
    protectionType,
    `${label} ${protectionType}`
  )
}

async function runOneMarketProtection(scope, positionSide, protectionType, label) {
  const openingSide = positionSide === 'LONG' ? 'BUY' : 'SELL'
  const closingSide = openingSide === 'BUY' ? 'SELL' : 'BUY'
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: openingSide,
    quantity: '0.02',
    label
  })
  const entry = Number(opened.position.openPrice)
  const upward = (
    (positionSide === 'LONG' && protectionType === 'TAKE_PROFIT')
      || (positionSide === 'SHORT' && protectionType === 'STOP_LOSS')
  )
  const triggerPrice = alignedPrice(opened.market, entry * (upward ? 1.02 : 0.98))
  const created = await createProtectionsViaUi(scope, opened.position, [{
    protectionType,
    quantity: String(opened.position.lots),
    triggerPrice,
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation(`${slug(label)}-via-ui`, created.capture)
  const pending = await waitForProtection(scope, opened.position.id, created.capture)
  assert.equal(pending.order.status, 'PENDING_ACTIVATION')
  assert.equal(pending.order.side, closingSide)
  assert.equal(pending.order.reduceOnly, true)
  assert.equal(pending.order.triggerPriceType, 'MARK_PRICE')
  await observeProtectionPending(scope, pending.order.id, 1200)
  const beforeTriggerDb = await scope.context.db.snapshotTradingRows(
    pending.snapshot.account.id
  )
  const triggerCursor = scope.context.events.snapshotFrames(scope.page).length
  const executionMarket = await setMarketAround(
    scope,
    'BTCUSDT-PERP',
    Number(triggerPrice) * (upward ? 1.01 : 0.99)
  )
  const filled = await waitForProtectionFilled(scope, opened.snapshot, pending.order.id)
  assert.equal(filled.trade.side, closingSide)
  assert.equal(filled.trade.liquidityRole, 'TAKER')
  assert.equal(openPositions(filled.snapshot, 'BTCUSDT-PERP').length, 0)
  assert.equal(
    filled.snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    1
  )
  const evidence = await scope.capture(`${slug(label)}-filled`, filled.snapshot)
  assertOrderEvent(evidence.db, pending.order.id, 'PROTECTION_TRIGGERED', 1, label)
  assertOrderEvent(evidence.db, pending.order.id, 'ORDER_FILLED', 1, label)
  const financial = assertPerpCloseFinancials({
    before: pending.snapshot,
    beforeDb: beforeTriggerDb,
    after: filled.snapshot,
    afterDb: evidence.db,
    position: opened.position,
    trade: filled.trade,
    rules: rulesFor(opened.market),
    executionMarket,
    expectedLiquidity: 'TAKER',
    label
  })
  const events = await actionEvents(
    scope,
    triggerCursor,
    ['PROTECTION_TRIGGERED', 'ORDER_FILLED'],
    label
  )
  return {
    finalSnapshot: filled.snapshot,
    finalDb: evidence.db,
    evidence: {
      positionId: opened.position.id,
      orderId: pending.order.id,
      protectionType,
      triggerPrice,
      closingSide,
      tradeId: filled.trade.id,
      events,
      financial
    }
  }
}

async function runProt04Journey(scope, subrun) {
  const options = {
    'desktop-immediate': {
      label: 'PROT-04 immediate',
      marketable: true,
      cancelResting: false
    },
    'desktop-two-stage': {
      label: 'PROT-04 maker',
      marketable: false,
      cancelResting: false
    },
    'desktop-cancel-resting': {
      label: 'PROT-04 cancel',
      marketable: false,
      cancelResting: true
    }
  }[subrun.id]
  assert(options, `PROT-04 unsupported subrun ${subrun.id}`)
  return runLimitProtection(scope, options)
}

async function runLimitProtection(scope, options) {
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.02',
    label: options.label
  })
  const triggerPrice = alignedPrice(opened.market, Number(opened.position.openPrice) * 1.02)
  const limitPrice = alignedPrice(
    opened.market,
    Number(triggerPrice) * (options.marketable ? 0.99 : 1.02)
  )
  const created = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'TAKE_PROFIT',
    quantity: String(opened.position.lots),
    triggerPrice,
    triggerExecutionType: 'LIMIT',
    price: limitPrice
  }])
  scope.addMutation(`${slug(options.label)}-via-ui`, created.capture)
  const pending = await waitForProtection(scope, opened.position.id, created.capture)
  const beforeTriggerDb = await scope.context.db.snapshotTradingRows(
    pending.snapshot.account.id
  )
  const triggerMarket = await setMarketAround(
    scope,
    'BTCUSDT-PERP',
    Number(triggerPrice) * 1.01
  )

  if (options.marketable) {
    const filled = await waitForProtectionFilled(scope, opened.snapshot, pending.order.id)
    assert.equal(filled.trade.liquidityRole, 'TAKER')
    const evidence = await scope.capture(`${slug(options.label)}-filled`, filled.snapshot)
    const financial = assertPerpCloseFinancials({
      before: pending.snapshot,
      beforeDb: beforeTriggerDb,
      after: filled.snapshot,
      afterDb: evidence.db,
      position: opened.position,
      trade: filled.trade,
      rules: rulesFor(opened.market),
      executionMarket: triggerMarket,
      expectedLiquidity: 'TAKER',
      expectedPrice: limitPrice,
      label: options.label
    })
    return {
      finalSnapshot: filled.snapshot,
      finalDb: evidence.db,
      evidence: {
        orderId: pending.order.id,
        path: 'IMMEDIATE',
        liquidityRole: 'TAKER',
        financial
      }
    }
  }

  const resting = await waitForOrderStatus(scope, pending.order.id, 'PENDING')
  assert.equal(recordsAfter(opened.snapshot, resting, 'trades')
    .filter(({ orderId }) => orderId === pending.order.id).length, 0)
  const restingEvidence = await scope.capture(`${slug(options.label)}-resting`, resting)
  assertOrderEvent(
    restingEvidence.db,
    pending.order.id,
    'PROTECTION_TRIGGERED',
    1,
    options.label
  )
  if (options.cancelResting) {
    const beforeFingerprint = tradingStateFingerprint(resting)
    const canceledCapture = await orderActionViaUi(scope, pending.order, 'CANCEL_PROTECTION')
    scope.addMutation(`${slug(options.label)}-cancel-via-ui`, canceledCapture)
    const canceled = await waitForOrderStatus(scope, pending.order.id, 'CANCELED')
    assert.equal(openPositions(canceled, 'BTCUSDT-PERP').length, 1)
    assert.equal(recordsAfter(resting, canceled, 'trades').length, 0)
    const afterFingerprint = tradingStateFingerprint(canceled)
    assert.deepEqual(afterFingerprint.positions, beforeFingerprint.positions)
    const canceledEvidence = await scope.capture(`${slug(options.label)}-canceled`, canceled)
    assertNoCashLedgerDelta(beforeTriggerDb, canceledEvidence.db, options.label)
    const closed = await closePerpPosition(scope, canceled, opened.position, `${options.label} cleanup`)
    const evidence = await scope.capture(`${slug(options.label)}-final`, closed.snapshot)
    return {
      finalSnapshot: closed.snapshot,
      finalDb: evidence.db,
      evidence: { orderId: pending.order.id, path: 'RESTING_CANCELED' }
    }
  }

  await setMarketAround(scope, 'BTCUSDT-PERP', Number(limitPrice) * 1.01)
  const filled = await waitForProtectionFilled(scope, resting, pending.order.id)
  assert.equal(filled.trade.liquidityRole, 'MAKER')
  const evidence = await scope.capture(`${slug(options.label)}-filled`, filled.snapshot)
  const financial = assertPerpCloseFinancials({
    before: resting,
    beforeDb: beforeTriggerDb,
    after: filled.snapshot,
    afterDb: evidence.db,
    position: opened.position,
    trade: filled.trade,
    rules: rulesFor(opened.market),
    expectedLiquidity: 'MAKER',
    expectedPrice: limitPrice,
    label: options.label
  })
  return {
    finalSnapshot: filled.snapshot,
    finalDb: evidence.db,
    evidence: {
      orderId: pending.order.id,
      path: 'TWO_STAGE',
      liquidityRole: 'MAKER',
      financial
    }
  }
}

async function runProt05Journey(scope) {
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.10',
    label: 'PROT-05'
  })
  const entry = Number(opened.position.openPrice)
  const beforeInvalid = tradingStateFingerprint(opened.snapshot)
  const oversized = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'TAKE_PROFIT',
    quantity: '0.11',
    triggerPrice: alignedPrice(opened.market, entry * 1.2),
    triggerExecutionType: 'MARKET'
  }], {
    expectFailure: true,
    reason: 'PROT-05 TAKE_PROFIT budget overflow'
  })
  assertRejectedCode(oversized.capture, 'PROTECTION_QUANTITY_EXCEEDED', 'PROT-05 TP budget')
  await dismissPositionActionDialog(scope.page)
  const oversizedStop = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'STOP_LOSS',
    quantity: '0.11',
    triggerPrice: alignedPrice(opened.market, entry * 0.8),
    triggerExecutionType: 'MARKET'
  }], {
    expectFailure: true,
    reason: 'PROT-05 STOP_LOSS budget overflow'
  })
  assertRejectedCode(
    oversizedStop.capture,
    'PROTECTION_QUANTITY_EXCEEDED',
    'PROT-05 SL budget'
  )
  await dismissPositionActionDialog(scope.page)
  const afterInvalid = await scope.context.api.snapshotAccount(scope.page)
  assertTradingStateEqual(
    beforeInvalid,
    tradingStateFingerprint(afterInvalid),
    'PROT-05 invalid budget zero mutation'
  )

  const levels = []
  for (const type of ['TAKE_PROFIT', 'STOP_LOSS']) {
    const direction = type === 'TAKE_PROFIT' ? 1 : -1
    for (let index = 1; index <= 5; index += 1) {
      levels.push(protectionLevel(
        opened.market,
        type,
        '0.02',
        entry * (1 + direction * (0.08 + index * 0.01)),
        'MARKET'
      ))
    }
  }
  const cursor = scope.page.p0Evidence.cursor
  const created = await createProtectionsViaUi(scope, opened.position, levels, {
    assertAddDisabled: true
  })
  scope.addMutation('prot-05-ten-protections-via-ui', created.capture)
  await waitUntil(() => countRequests(
    scope.page,
    cursor,
    'POST',
    new RegExp(`/api/trading/positions/${opened.position.id}/protections$`, 'u')
  ) === 10, 'PROT-05 ten UI protection requests')
  assert.deepEqual(created.addGuard, {
    disabled: true,
    beforeCount: 10,
    afterCount: 10
  })
  scope.contractProbes.push({
    kind: 'PROTECTION_UI_TEN_LEVEL_GUARD',
    status: 'PASS',
    requestCount: 10,
    ...created.addGuard
  })
  let snapshot = await waitForProtectionCount(scope, opened.position.id, 10)
  const beforeResize = protectionOrders(snapshot, opened.position.id)
  assertProtectionBudgets(beforeResize, 0.10, 'PROT-05 before resize')

  const eleventh = await replayCapturedMutation(
    scope,
    scope.page,
    created.capture,
    (body) => ({ ...body, clientOrderId: randomUUID() }),
    {
    expectFailure: true,
    reason: 'PROT-05 eleventh protection'
    }
  )
  assertRejectedCode(eleventh, 'PROTECTION_LIMIT_EXCEEDED', 'PROT-05 limit')
  scope.contractProbes.push({
    kind: 'L7_PROTECTION_LIMIT_EXCEEDED',
    status: 'REJECTED',
    requestRef: eleventh.requestRef,
    errorCode: responseCode(eleventh)
  })
  assert.equal(protectionOrders(
    await scope.context.api.snapshotAccount(scope.page),
    opened.position.id
  ).length, 10)

  const firstExecutionMarket = await freezeMarket(scope, 'BTCUSDT-PERP')
  const beforeFirst = snapshot
  const beforeFirstPosition = openPositions(beforeFirst, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  assert(beforeFirstPosition, 'PROT-05 first partial position')
  const beforeFirstDb = await scope.context.db.snapshotTradingRows(snapshot.account.id)
  const firstPartial = await scope.context.ui.positionActionViaUi(scope.page, {
    positionId: opened.position.id,
    positionSide: opened.position.positionSide,
    action: 'PARTIAL_CLOSE',
    quantity: '0.03',
    quantityUnit: 'BASE'
  })
  scope.addMutation('prot-05-first-partial-close-via-ui', firstPartial)
  snapshot = await waitForPositionQuantity(scope, opened.position.id, '0.07')
  const firstTrades = recordsAfter(beforeFirst, snapshot, 'trades')
  assert.equal(firstTrades.length, 1, 'PROT-05 first partial exact Trade')
  const afterFirst = protectionOrders(snapshot, opened.position.id)
  const firstEvidence = await scope.capture('prot-05-first-resize', snapshot)
  const firstFinancial = assertPerpCloseFinancials({
    before: beforeFirst,
    beforeDb: beforeFirstDb,
    after: snapshot,
    afterDb: firstEvidence.db,
    position: beforeFirstPosition,
    trade: firstTrades[0],
    quantity: '0.03',
    expectClosed: false,
    rules: rulesFor(opened.market),
    executionMarket: firstExecutionMarket,
    expectedLiquidity: 'TAKER',
    label: 'PROT-05 first partial'
  })
  assertProtectionResize(
    beforeResize,
    afterFirst,
    0.07,
    'PROT-05 first resize',
    beforeFirstDb,
    firstEvidence.db
  )

  const secondExecutionMarket = await freezeMarket(scope, 'BTCUSDT-PERP')
  const beforeSecond = snapshot
  const beforeSecondPosition = openPositions(beforeSecond, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  assert(beforeSecondPosition, 'PROT-05 second partial position')
  const secondPartial = await scope.context.ui.positionActionViaUi(scope.page, {
    positionId: opened.position.id,
    positionSide: opened.position.positionSide,
    action: 'PARTIAL_CLOSE',
    quantity: '0.02',
    quantityUnit: 'BASE'
  })
  scope.addMutation('prot-05-second-partial-close-via-ui', secondPartial)
  snapshot = await waitForPositionQuantity(scope, opened.position.id, '0.05')
  const secondTrades = recordsAfter(beforeSecond, snapshot, 'trades')
  assert.equal(secondTrades.length, 1, 'PROT-05 second partial exact Trade')
  const afterSecond = protectionOrders(snapshot, opened.position.id)
  const secondEvidence = await scope.capture('prot-05-second-resize', snapshot)
  const secondFinancial = assertPerpCloseFinancials({
    before: beforeSecond,
    beforeDb: firstEvidence.db,
    after: snapshot,
    afterDb: secondEvidence.db,
    position: beforeSecondPosition,
    trade: secondTrades[0],
    quantity: '0.02',
    expectClosed: false,
    rules: rulesFor(opened.market),
    executionMarket: secondExecutionMarket,
    expectedLiquidity: 'TAKER',
    label: 'PROT-05 second partial'
  })
  assertProtectionResize(
    afterFirst,
    afterSecond,
    0.05,
    'PROT-05 second resize',
    firstEvidence.db,
    secondEvidence.db
  )

  const finalExecutionMarket = await freezeMarket(scope, 'BTCUSDT-PERP')
  const beforeFinal = snapshot
  const position = openPositions(beforeFinal, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  assert(position, 'PROT-05 final position')
  const closed = await closePerpPosition(scope, beforeFinal, position, 'PROT-05 cleanup')
  const terminal = await waitForAccount(
    scope.context,
    scope.page,
    'PROT-05 terminal protections',
    (candidate) => protectionOrders(candidate, opened.position.id)
      .every(({ status }) => !ACTIVE_ORDER_STATUSES.has(status)) && candidate
  )
  assert.equal(openPositions(terminal, 'BTCUSDT-PERP').length, 0)
  assert.equal(
    recordsAfter(snapshot, terminal, 'trades').length,
    1,
    'PROT-05 full close produces only the requested closing Trade'
  )
  const finalEvidence = await scope.capture('prot-05-final', terminal)
  const finalFinancial = assertPerpCloseFinancials({
    before: beforeFinal,
    beforeDb: secondEvidence.db,
    after: terminal,
    afterDb: finalEvidence.db,
    position,
    trade: closed.trade,
    rules: rulesFor(opened.market),
    executionMarket: finalExecutionMarket,
    expectedLiquidity: 'TAKER',
    label: 'PROT-05 final close'
  })
  scope.oracleEvidence.push({
    kind: 'PROTECTION_LIMIT_AND_RESIZE',
    protectionIds: beforeResize.map(({ id }) => id),
    before: summarizeProtections(beforeResize),
    afterFirst: summarizeProtections(afterFirst),
    afterSecond: summarizeProtections(afterSecond),
    financial: [firstFinancial, secondFinancial, finalFinancial]
  })
  return { finalSnapshot: terminal, finalDb: finalEvidence.db ?? closed.db }
}

async function runProt06Journey(scope) {
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.04',
    label: 'PROT-06'
  })
  const entry = Number(opened.position.openPrice)
  await assertInvalidProtectionDirections(scope, opened, 'LONG')

  const legal = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'TAKE_PROFIT',
    quantity: '0.02',
    triggerPrice: alignedPrice(opened.market, entry * 1.1),
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation('prot-06-create-via-ui', legal.capture)
  let pending = await waitForProtection(scope, opened.position.id, legal.capture)
  const original = pending.order
  const firstModifyCursor = scope.page.p0Evidence.cursor
  const firstModify = await orderActionViaUi(scope, original, 'MODIFY_PROTECTION', {
    quantity: '0.015',
    triggerPrice: alignedPrice(opened.market, entry * 1.12),
    triggerExecutionType: 'LIMIT',
    price: alignedPrice(opened.market, entry * 1.13)
  })
  scope.addMutation('prot-06-modify-via-ui', firstModify)
  assert.equal(
    countRequests(
      scope.page,
      firstModifyCursor,
      'PATCH',
      new RegExp(`/api/trading/protections/${original.id}$`, 'u')
    ),
    1,
    'PROT-06 first modify exactly one PATCH'
  )
  pending = await waitForOrderVersion(scope, original.id, Number(original.version) + 1)
  const firstVersion = pending.order.version
  const firstFingerprint = assertProtectionModification(
    original,
    pending.order,
    parsedRequest(firstModify),
    'PROT-06 first modify'
  )

  const peer = await createAuthenticatedPeer(scope, 'prot-06-second-tab')
  let secondModify
  try {
    const secondModifyCursor = peer.page.p0Evidence.cursor
    secondModify = await orderActionViaUi(
      { ...scope, page: peer.page },
      pending.order,
      'MODIFY_PROTECTION',
      {
        quantity: '0.01',
        triggerPrice: alignedPrice(opened.market, entry * 1.14),
        triggerExecutionType: 'MARKET',
        price: ''
      }
    )
    assert.equal(
      countRequests(
        peer.page,
        secondModifyCursor,
        'PATCH',
        new RegExp(`/api/trading/protections/${original.id}$`, 'u')
      ),
      1,
      'PROT-06 second modify exactly one PATCH'
    )
    scope.checkpoints.push(await scope.context.evidence.captureCheckpoint(
      scope.context,
      'prot-06-second-tab-update',
      {
        caseId: scope.definition.id,
        pages: [peer.page]
      }
    ))
    peer.page.assertEvidenceClean('PROT-06 real second tab')
  } finally {
    await closeAuthenticatedPeer(peer, false)
  }
  scope.addMutation('prot-06-second-tab-update-via-ui', secondModify)
  const current = await waitForOrderVersion(scope, original.id, Number(firstVersion) + 1)
  const secondFingerprint = assertProtectionModification(
    pending.order,
    current.order,
    parsedRequest(secondModify),
    'PROT-06 second modify'
  )
  const stale = await replayCapturedMutation(scope, scope.page, firstModify, undefined, {
    expectFailure: true,
    reason: 'PROT-06 stale version'
  })
  assertRejectedCode(stale, 'PROTECTION_VERSION_CONFLICT', 'PROT-06 stale')
  const afterStale = await scope.context.api.snapshotAccount(scope.page)
  assert.deepEqual(
    protectionBusinessFingerprint(afterStale && orderById(afterStale, original.id)),
    secondFingerprint,
    'PROT-06 stale request preserves the complete second-tab value'
  )

  const cancel = await orderActionViaUi(scope, current.order, 'CANCEL_PROTECTION')
  scope.addMutation('prot-06-cancel-via-ui', cancel)
  const canceled = await waitForOrderStatus(scope, original.id, 'CANCELED')
  const canceledDb = await scope.context.db.snapshotTradingRows(canceled.account.id)
  const repeat = await replayCapturedMutation(scope, scope.page, cancel)
  assertSuccess(repeat, 'PROT-06 repeat cancel')
  const repeated = await scope.context.api.snapshotAccount(scope.page)
  assert.equal(orderById(repeated, original.id).status, 'CANCELED')
  assertTradingStateEqual(canceled, repeated, 'PROT-06 repeat cancel API zero mutation')
  assertDbReplayUnchanged(
    canceledDb,
    await scope.context.db.snapshotTradingRows(repeated.account.id),
    'PROT-06 repeat cancel'
  )

  const expiring = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'STOP_LOSS',
    quantity: '0.01',
    triggerPrice: alignedPrice(opened.market, entry * 0.9),
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation('prot-06-expiring-create-via-ui', expiring.capture)
  const expiringPending = await waitForProtection(scope, opened.position.id, expiring.capture)
  const position = openPositions(expiringPending.snapshot, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  const closed = await closePerpPosition(scope, expiringPending.snapshot, position, 'PROT-06 cleanup')
  const terminal = await waitForAccount(
    scope.context,
    scope.page,
    'PROT-06 expired protection',
    (candidate) => {
      const order = orderById(candidate, expiringPending.order.id)
      return order && !ACTIVE_ORDER_STATUSES.has(order.status) ? candidate : false
    }
  )
  const beforeExpiredDb = await scope.context.db.snapshotTradingRows(terminal.account.id)
  const expiredModify = await replayCapturedMutation(
    scope,
    scope.page,
    secondModify,
    (body) => ({
      ...body,
      expectedVersion: orderById(terminal, expiringPending.order.id).version
    }),
    {
      expectFailure: true,
      reason: 'PROT-06 closed position modification',
      url: secondModify.rawRequest.url.replace(original.id, expiringPending.order.id)
    }
  )
  assertRejectedCode(expiredModify, 'PROTECTION_NOT_MODIFIABLE', 'PROT-06 expired modification')
  const afterExpired = await scope.context.api.snapshotAccount(scope.page)
  assertTradingStateEqual(terminal, afterExpired, 'PROT-06 expired modify zero mutation')
  assertDbReplayUnchanged(
    beforeExpiredDb,
    await scope.context.db.snapshotTradingRows(afterExpired.account.id),
    'PROT-06 expired modify'
  )

  const short = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'SELL',
    quantity: '0.02',
    label: 'PROT-06 SHORT direction matrix'
  })
  await assertInvalidProtectionDirections(scope, short, 'SHORT')
  const shortPosition = openPositions(
    await scope.context.api.snapshotAccount(scope.page),
    'BTCUSDT-PERP'
  ).find(({ id }) => id === short.position.id)
  const shortClosed = await closePerpPosition(
    scope,
    short.snapshot,
    shortPosition,
    'PROT-06 SHORT cleanup'
  )
  const finalEvidence = await scope.capture('prot-06-final', shortClosed.snapshot)
  scope.replayProbes.push({
    kind: 'PROTECTION_STALE_AND_CANCEL_REPLAY',
    staleCode: responseCode(stale),
    repeatCancelRequestRef: repeat.requestRef,
    firstFingerprint,
    secondFingerprint
  })
  return { finalSnapshot: shortClosed.snapshot, finalDb: finalEvidence.db ?? closed.db }
}

async function runWallet02Journey(scope, subrun) {
  if (subrun.id === 'desktop-fingerprint-conflict') {
    return runWallet02ConflictJourney(scope)
  }
  assert.equal(subrun.id, 'desktop-availability-replay')
  const { context, page } = scope
  const spotSymbol = 'BTCUSDT'
  const spotMarket = await context.api.snapshotMarket(spotSymbol)
  await context.ui.openTradePanel(page, { product: 'spot', symbol: spotSymbol })
  let snapshot = await context.api.snapshotAccount(page)
  const pendingCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: alignedPrice(spotMarket, Number(quoteDecimal(spotMarket, 'bid')) * 0.9),
    amount: stepAlignedQuantity(spotMarket, 0.01)
  })
  scope.addMutation('wallet-02-spot-pending-via-ui', pendingCapture)
  const pending = await waitForCreatedOrder(
    scope,
    snapshot,
    pendingCapture,
    spotSymbol,
    'PENDING'
  )
  snapshot = pending.snapshot
  const spotWallet = findWallet(snapshot, 'SPOT', 'USDT')
  assert(Number(spotWallet.locked) > 0, 'WALLET-02 Spot hold')
  const unavailableSpot = midpoint(spotWallet.available, spotWallet.total)
  const spotRejected = await context.ui.transferViaUi(page, {
    direction: 'SPOT_TO_PERP',
    amount: unavailableSpot,
    expectFailure: true,
    reason: 'WALLET-02 locked Spot funds'
  })
  assertRejectedCode(spotRejected, 'TRANSFER_AMOUNT_UNAVAILABLE', 'WALLET-02 Spot unavailable')
  assert.equal(recordsAfter(snapshot, await context.api.snapshotAccount(page), 'transfers').length, 0)

  const cancelSpot = await orderActionViaUi(scope, pending.order, 'CANCEL_ORDER')
  scope.addMutation('wallet-02-spot-cancel-via-ui', cancelSpot)
  snapshot = await waitForOrderStatus(scope, pending.order.id, 'CANCELED')
  const forward = await context.ui.transferViaUi(page, {
    direction: 'SPOT_TO_PERP',
    amount: '1000'
  })
  scope.addMutation('wallet-02-spot-release-transfer-via-ui', forward)
  snapshot = await waitForTransfer(scope, snapshot, forward)

  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.02',
    label: 'WALLET-02'
  })
  snapshot = opened.snapshot
  const perpMarket = await context.api.snapshotMarket('BTCUSDT-PERP')
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol: 'BTCUSDT-PERP' })
  const perpPendingCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: alignedPrice(perpMarket, Number(quoteDecimal(perpMarket, 'bid')) * 0.5),
    amount: stepAlignedQuantity(perpMarket, 0.02)
  })
  scope.addMutation('wallet-02-perp-pending-via-ui', perpPendingCapture)
  const perpPending = await waitForCreatedOrder(
    scope,
    snapshot,
    perpPendingCapture,
    'BTCUSDT-PERP',
    'PENDING'
  )
  snapshot = perpPending.snapshot
  assert(Number(snapshot.summary.usedMargin) > 0, 'WALLET-02 used margin/order hold')
  const unavailablePerp = midpoint(snapshot.summary.freeMargin, snapshot.summary.balance)
  const perpRejected = await context.ui.transferViaUi(page, {
    direction: 'PERP_TO_SPOT',
    amount: unavailablePerp,
    expectFailure: true,
    reason: 'WALLET-02 used margin safety'
  })
  assertRejectedCode(perpRejected, 'TRANSFER_AMOUNT_UNAVAILABLE', 'WALLET-02 Perp unavailable')

  const cancelPerp = await orderActionViaUi(scope, perpPending.order, 'CANCEL_ORDER')
  scope.addMutation('wallet-02-perp-cancel-via-ui', cancelPerp)
  snapshot = await waitForOrderStatus(scope, perpPending.order.id, 'CANCELED')
  const position = openPositions(snapshot, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  snapshot = (await closePerpPosition(scope, snapshot, position, 'WALLET-02 cleanup')).snapshot
  const reverse = await context.ui.transferViaUi(page, {
    direction: 'PERP_TO_SPOT',
    amount: '500'
  })
  scope.addMutation('wallet-02-margin-release-transfer-via-ui', reverse)
  snapshot = await waitForTransfer(scope, snapshot, reverse)

  const beforeDouble = snapshot
  const transferCursor = page.p0Evidence.cursor
  const double = await transferViaUiWithDoubleConfirm(scope, {
    direction: 'SPOT_TO_PERP',
    amount: '250'
  })
  scope.addMutation('wallet-02-controlled-double-click-via-ui', double)
  await delay(250)
  assert.equal(
    countRequests(page, transferCursor, 'POST', /\/api\/accounts\/[^/]+\/transfers$/u),
    1,
    'WALLET-02 controlled double click sends once'
  )
  snapshot = await waitForTransfer(scope, beforeDouble, double)
  const beforeReplayDb = await context.db.snapshotTradingRows(snapshot.account.id)
  const replay = await replayCapturedMutation(scope, page, double)
  const replaySnapshot = await context.api.snapshotAccount(page)
  const replayDb = await context.db.snapshotTradingRows(replaySnapshot.account.id)
  assert.equal(recordsAfter(snapshot, replaySnapshot, 'transfers').length, 0)
  assert.deepEqual(walletFingerprint(replaySnapshot), walletFingerprint(snapshot))
  assertDbReplayUnchanged(beforeReplayDb, replayDb, 'WALLET-02 idempotent replay')
  const finalEvidence = await scope.capture('wallet-02-replay-final', replaySnapshot)
  assertTransferLedgerPair(finalEvidence.db, parsedRequest(double).requestId, 'WALLET-02')
  scope.replayProbes.push({
    kind: 'TRANSFER_IDEMPOTENT_REPLAY',
    replayRequestRef: replay.requestRef
  })
  return { finalSnapshot: replaySnapshot, finalDb: finalEvidence.db }
}

async function runWallet02ConflictJourney(scope) {
  const { context, page } = scope
  const before = await context.api.snapshotAccount(page)
  const legal = await context.ui.transferViaUi(page, {
    direction: 'SPOT_TO_PERP',
    amount: '250'
  })
  scope.addMutation('wallet-02-conflict-baseline-via-ui', legal)
  const transferred = await waitForTransfer(scope, before, legal)
  const transferredDb = await context.db.snapshotTradingRows(transferred.account.id)
  const conflict = await replayCapturedMutation(
    scope,
    page,
    legal,
    (body) => ({ ...body, amount: Number(body.amount) + 1 }),
    { expectFailure: true, reason: 'WALLET-02 request fingerprint conflict' }
  )
  assertRejectedCode(conflict, 'TRANSFER_REQUEST_CONFLICT', 'WALLET-02 conflict')
  const finalSnapshot = await context.api.snapshotAccount(page)
  const finalDb = await context.db.snapshotTradingRows(finalSnapshot.account.id)
  assert.deepEqual(walletFingerprint(finalSnapshot), walletFingerprint(transferred))
  assertDbReplayUnchanged(transferredDb, finalDb, 'WALLET-02 fingerprint conflict')
  assertTransferLedgerPair(finalDb, parsedRequest(legal).requestId, 'WALLET-02 conflict baseline')
  const finalEvidence = await scope.capture('wallet-02-conflict-final', finalSnapshot)
  scope.replayProbes.push({
    kind: 'TRANSFER_FINGERPRINT_CONFLICT',
    requestRef: conflict.requestRef,
    errorCode: responseCode(conflict)
  })
  return { finalSnapshot, finalDb: finalEvidence.db }
}

async function runLife01Journey(scope, subrun) {
  const { context, page } = scope
  if (subrun.id === 'desktop-pending-order') {
    const market = await context.api.snapshotMarket('BTCUSDT')
    await context.ui.openTradePanel(page, { product: 'spot', symbol: 'BTCUSDT' })
    const before = await context.api.snapshotAccount(page)
    const capture = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'LIMIT',
      price: alignedPrice(market, Number(quoteDecimal(market, 'bid')) * 0.5),
      amount: stepAlignedQuantity(market, 0.001)
    })
    scope.addMutation('life-01-pending-order-via-ui', capture)
    const pending = await waitForCreatedOrder(scope, before, capture, 'BTCUSDT', 'PENDING')
    const outcome = await assertResetBlocked(scope, pending.snapshot, 'pending-order')
    const cancel = await orderActionViaUi(scope, pending.order, 'CANCEL_ORDER')
    scope.addMutation('life-01-pending-cancel-via-ui', cancel)
    const terminal = await waitForOrderStatus(scope, pending.order.id, 'CANCELED')
    const evidence = await scope.capture('life-01-pending-final', terminal)
    scope.contractProbes.push(outcome)
    return { finalSnapshot: terminal, finalDb: evidence.db }
  }

  if (subrun.id === 'desktop-oco') {
    const market = await context.api.snapshotMarket('BTCUSDT')
    await context.ui.openTradePanel(page, { product: 'spot', symbol: 'BTCUSDT' })
    const before = await context.api.snapshotAccount(page)
    const last = lastPrice(market)
    const capture = await submitOcoViaUi(scope, {
      side: 'BUY',
      quantity: stepAlignedQuantity(market, 0.001),
      limitPrice: alignedPrice(market, last * 0.7),
      stopTriggerPrice: alignedPrice(market, last * 1.3)
    })
    scope.addMutation('life-01-oco-via-ui', capture)
    const pending = await waitForOcoGroup(scope, before, capture)
    const outcome = await assertResetBlocked(scope, pending.snapshot, 'oco')
    const cancel = await orderActionViaUi(scope, pending.legs[0], 'CANCEL_ORDER')
    scope.addMutation('life-01-oco-cancel-via-ui', cancel)
    const terminal = await waitForAccount(
      context,
      page,
      'LIFE-01 OCO canceled',
      (candidate) => pending.legs.every(({ id }) => (
        orderById(candidate, id)?.status === 'CANCELED'
      )) && candidate
    )
    const evidence = await scope.capture('life-01-oco-final', terminal)
    scope.contractProbes.push(outcome)
    return { finalSnapshot: terminal, finalDb: evidence.db }
  }

  assert.equal(subrun.id, 'desktop-perp-position-protection')
  const opened = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.02',
    label: 'LIFE-01'
  })
  const protection = await createProtectionsViaUi(scope, opened.position, [{
    protectionType: 'STOP_LOSS',
    quantity: '0.01',
    triggerPrice: alignedPrice(opened.market, Number(opened.position.openPrice) * 0.8),
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation('life-01-protection-via-ui', protection.capture)
  const snapshot = await waitForProtectionCount(scope, opened.position.id, 1)
  const outcome = await assertResetBlocked(scope, snapshot, 'perp-position-protection')
  const position = openPositions(snapshot, 'BTCUSDT-PERP')
    .find(({ id }) => id === opened.position.id)
  const closed = await closePerpPosition(scope, snapshot, position, 'LIFE-01 cleanup')
  const terminal = await waitForAccount(
    context,
    page,
    'LIFE-01 final cleanup',
    (candidate) => activeOrders(candidate).length === 0
      && openPositions(candidate).length === 0
      && candidate
  )
  const evidence = await scope.capture('life-01-perp-final', terminal)
  scope.contractProbes.push(outcome)
  return { finalSnapshot: terminal, finalDb: evidence.db ?? closed.db }
}

async function assertResetBlocked(scope, before, blocker) {
  const fingerprint = completeStateFingerprint(before)
  const capture = await scope.context.ui.resetDemoViaUi(scope.page, {
    expectFailure: true,
    reason: `LIFE-01 ${blocker}`
  })
  assertRejectedCode(capture, 'DEMO_RESET_BLOCKED', `LIFE-01 ${blocker}`)
  await scope.page.waitForFunction(
    () => Boolean(document.querySelector('[role="dialog"][aria-modal="true"] [role="alert"]')?.textContent?.trim()),
    `LIFE-01 ${blocker} explicit UI error`
  )
  const uiError = await scope.page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
    const alert = dialog?.querySelector('[role="alert"]')
    return {
      dialogVisible: Boolean(dialog),
      message: alert?.textContent?.trim() ?? '',
      successVisible: /reset (complete|succeeded)|重置成功/iu.test(dialog?.textContent ?? '')
    }
  })
  assert.equal(uiError.dialogVisible, true, `LIFE-01 ${blocker} reset dialog remains visible`)
  assert.notEqual(uiError.message, '', `LIFE-01 ${blocker} explicit UI error`)
  assert.equal(uiError.successVisible, false, `LIFE-01 ${blocker} no fake success notice`)
  const after = await scope.context.api.snapshotAccount(scope.page)
  assert.deepEqual(completeStateFingerprint(after), fingerprint, `LIFE-01 ${blocker} zero mutation`)
  return {
    kind: 'DEMO_RESET_BLOCKED',
    status: 'PASS',
    blocker,
    requestRef: capture.requestRef,
    errorCode: responseCode(capture),
    uiError: uiError.message
  }
}

async function runLife03Journey(scope) {
  const { context, page } = scope
  const spotMarket = await context.api.snapshotMarket('BTCUSDT')
  await context.ui.openTradePanel(page, { product: 'spot', symbol: 'BTCUSDT' })
  let snapshot = await context.api.snapshotAccount(page)
  const pendingCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: alignedPrice(spotMarket, Number(quoteDecimal(spotMarket, 'bid')) * 0.5),
    amount: stepAlignedQuantity(spotMarket, 0.001)
  })
  scope.addMutation('life-03-pending-order-via-ui', pendingCapture)
  const spotPending = await waitForCreatedOrder(
    scope,
    snapshot,
    pendingCapture,
    'BTCUSDT',
    'PENDING'
  )
  snapshot = spotPending.snapshot

  const btc = await openPerpPosition(scope, {
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    quantity: '0.02',
    label: 'LIFE-03 BTC'
  })
  const protection = await createProtectionsViaUi(scope, btc.position, [{
    protectionType: 'STOP_LOSS',
    quantity: '0.01',
    triggerPrice: alignedPrice(btc.market, Number(btc.position.openPrice) * 0.8),
    triggerExecutionType: 'MARKET'
  }])
  scope.addMutation('life-03-protection-via-ui', protection.capture)
  const protectionPending = await waitForProtection(scope, btc.position.id, protection.capture)
  const eth = await openPerpPosition(scope, {
    symbol: 'ETHUSDT-PERP',
    side: 'SELL',
    quantity: '0.02',
    label: 'LIFE-03 ETH'
  })
  snapshot = eth.snapshot
  assert(activeOrders(snapshot).length >= 2, 'LIFE-03 active orders/protections')
  assert(openPositions(snapshot).length >= 2, 'LIFE-03 multiple positions')
  const beforeAdmin = snapshot
  const beforeAdminDb = await context.db.snapshotTradingRows(snapshot.account.id)
  const expectedOrderIds = activeOrders(beforeAdmin).map(({ id }) => id)
  assert(expectedOrderIds.includes(spotPending.order.id), 'LIFE-03 expected Spot pending order')
  assert(expectedOrderIds.includes(protectionPending.order.id), 'LIFE-03 expected protection order')
  const expectedPositions = openPositions(beforeAdmin)
  const cleanupMarkets = new Map()
  for (const position of expectedPositions) {
    cleanupMarkets.set(position.symbol, await freezeMarket(scope, position.symbol))
  }

  const adminPage = await scope.getAdminPage()
  await openAdminAccountViaUi(adminPage, snapshot.account.id)
  const auditsBeforeCleanup = await context.api.admin(
    adminPage,
    '/api/admin/audit-logs?page=0&size=100'
  )
  const resetGuard = await probeAdminResetDisabled(adminPage)
  assert.equal(resetGuard.disabled, true)
  assert.equal(resetGuard.requestSent, false)

  const cleanupCursor = adminPage.p0Evidence.cursor
  const cleanup = await adminHighRiskActionViaUi(scope, adminPage, {
    label: '强制清理',
    confirmLabel: 'Confirm Force cleanup',
    matcher: {
      method: 'POST',
      url: new RegExp(`/api/admin/accounts/${snapshot.account.id}/force-cleanup$`, 'u')
    },
    reason: `P0 ${scope.definition.id} run-owned cleanup`,
    doubleConfirm: true
  })
  scope.addMutation('life-03-admin-force-cleanup-via-ui', cleanup.capture)
  assert.equal(
    countRequests(
      adminPage,
      cleanupCursor,
      'POST',
      new RegExp(`/api/admin/accounts/${snapshot.account.id}/force-cleanup$`, 'u')
    ),
    1,
    'LIFE-03 force cleanup controlled double click'
  )
  const cleanupResponse = parsedResponse(cleanup.capture)?.data ?? parsedResponse(cleanup.capture)
  assert.equal(Array.isArray(cleanupResponse?.items), true)
  const cleanupItems = assertCleanupItems(
    cleanupResponse.items,
    expectedOrderIds,
    expectedPositions.map(({ id }) => id),
    'LIFE-03 cleanup'
  )
  const cleaned = await waitForAccount(
    context,
    page,
    'LIFE-03 force cleanup visible to user',
    (candidate) => activeOrders(candidate).length === 0
      && openPositions(candidate).length === 0
      && candidate,
    60000
  )
  const cleanedDb = await context.db.snapshotTradingRows(cleaned.account.id)
  const cleanupReleaseLedger = assertBatchReleaseRows(
    beforeAdminDb,
    cleanedDb,
    activeOrders(beforeAdmin),
    'LIFE-03 cleanup'
  )
  const cleanupFinancial = assertAdminCleanupFinancials({
    before: beforeAdmin,
    beforeDb: beforeAdminDb,
    after: cleaned,
    afterDb: cleanedDb,
    positions: expectedPositions,
    items: cleanupResponse.items,
    markets: cleanupMarkets,
    label: 'LIFE-03 cleanup'
  })
  const auditsAfterCleanup = await context.api.admin(
    adminPage,
    '/api/admin/audit-logs?page=0&size=100'
  )
  const cleanupAuditDelta = auditRowsForRequest(
    auditsBeforeCleanup,
    auditsAfterCleanup,
    cleanup.requestId,
    'LIFE-03 cleanup'
  )
  const replay = await replayCapturedMutation(
    scope,
    adminPage,
    cleanup.capture,
    undefined,
    { tokenKey: 'fx-platform-admin-token' }
  )
  const afterReplay = await context.api.snapshotAccount(page)
  const afterReplayDb = await context.db.snapshotTradingRows(afterReplay.account.id)
  assert.equal(afterReplay.orders.length, cleaned.orders.length)
  assert.equal(afterReplay.trades.length, cleaned.trades.length)
  assertDbReplayUnchanged(cleanedDb, afterReplayDb, 'LIFE-03 cleanup replay')
  const auditsAfterReplay = await context.api.admin(
    adminPage,
    '/api/admin/audit-logs?page=0&size=100'
  )
  assert.deepEqual(
    auditFingerprint(auditsAfterReplay, cleanup.requestId),
    auditFingerprint(auditsAfterCleanup, cleanup.requestId),
    'LIFE-03 replay adds no Admin audit'
  )

  await closeAdminHighRiskDialog(adminPage)
  await adminPage.waitForFunction(
    () => document.body.textContent?.includes('当前未发现清理阻塞项。'),
    'LIFE-03 cleanup blocker cleared'
  )
  const reset = await adminHighRiskActionViaUi(scope, adminPage, {
    label: '重置 Demo 账户',
    confirmLabel: 'Confirm Reset demo account',
    matcher: {
      method: 'POST',
      url: new RegExp(`/api/admin/accounts/${snapshot.account.id}/demo-reset$`, 'u')
    },
    reason: `P0 ${scope.definition.id} run-owned reset`
  })
  scope.addMutation('life-03-admin-reset-via-ui', reset.capture)
  assert.notEqual(reset.requestId, cleanup.requestId)
  assert.notEqual(reset.auditId, cleanup.auditId)
  const resetState = await waitForAccount(
    context,
    page,
    'LIFE-03 Admin reset visible to user',
    (candidate) => Number(findWallet(candidate, 'SPOT', 'USDT').total) === 50000
      && Number(candidate.summary.balance) === 50000
      && Number(candidate.summary.usedMargin) === 0
      && candidate,
    60000
  )
  const resetDb = await context.db.snapshotTradingRows(resetState.account.id)
  const resetLedger = assertResetLedgerContract(
    afterReplayDb,
    resetDb,
    reset.requestId,
    resetState.account.id,
    'LIFE-03 reset'
  )
  for (const id of beforeAdmin.orders.map(({ id }) => id)) {
    assert(resetState.orders.some((order) => order.id === id), `LIFE-03 order history ${id}`)
  }
  for (const id of beforeAdmin.trades.map(({ id }) => id)) {
    assert(resetState.trades.some((trade) => trade.id === id), `LIFE-03 trade history ${id}`)
  }

  const beforeUserAdmin = completeStateFingerprint(resetState)
  const beforeUserAdminDb = resetDb
  let forbidden
  try {
    await context.api.user(page, `/api/admin/accounts/${snapshot.account.id}`)
  } catch (error) {
    forbidden = error
  }
  assert(forbidden && [401, 403].includes(forbidden.status), 'LIFE-03 user Admin probe forbidden')
  const afterUserAdmin = await context.api.snapshotAccount(page)
  const afterUserAdminDb = await context.db.snapshotTradingRows(afterUserAdmin.account.id)
  assertTradingStateEqual(
    beforeUserAdmin,
    completeStateFingerprint(afterUserAdmin),
    'LIFE-03 forbidden user Admin probe API zero mutation'
  )
  assertDbReplayUnchanged(
    beforeUserAdminDb,
    afterUserAdminDb,
    'LIFE-03 forbidden user Admin probe DB zero mutation'
  )
  const finalEvidence = await scope.capture('life-03-final', afterUserAdmin)
  const audits = await context.api.admin(
    adminPage,
    '/api/admin/audit-logs?page=0&size=100'
  )
  const cleanupAudits = audits.items.filter(({ requestId }) => requestId === cleanup.requestId)
  const resetAudits = audits.items.filter(({ requestId }) => requestId === reset.requestId)
  const cleanupAudit = cleanupAudits.find(({ id }) => id === cleanup.auditId)
  const resetAudit = resetAudits.find(({ id }) => id === reset.auditId)
  assert(cleanupAudit, 'LIFE-03 displayed cleanup audit exists in Admin REST')
  assert(resetAudit, 'LIFE-03 displayed reset audit exists in Admin REST')
  assert.equal(resetAudits.length, 1, 'LIFE-03 reset has exactly one Admin audit')
  assert.deepEqual(
    cleanupAudits.map(({ id }) => id).toSorted(),
    cleanupAuditDelta.map(({ id }) => id).toSorted(),
    'LIFE-03 cleanup audit set is replay-stable'
  )
  for (const audit of [...cleanupAudits, resetAudit]) {
    assertUuid(audit.actorUserId, `LIFE-03 ${audit.action} actor`)
    assert.equal(audit.targetType, 'TRADING_ACCOUNT', `LIFE-03 ${audit.action} target type`)
    assert.equal(audit.targetId, snapshot.account.id, `LIFE-03 ${audit.action} target id`)
    assert.notEqual(
      audit.actorUserId,
      beforeAdminDb.accountRow.user_id,
      `LIFE-03 ${audit.action} actor differs from target user`
    )
  }
  assert.equal(
    new Set([...cleanupAudits, resetAudit].map(({ actorUserId }) => actorUserId)).size,
    1,
    'LIFE-03 cleanup and reset use one Admin actor'
  )
  assert.equal(resetAudit.action, 'ADMIN_DEMO_RESET')
  assert.equal(resetAudit.requestId, reset.requestId)
  assert.equal(
    cleanupAudits.every(({ requestId }) => requestId === cleanup.requestId),
    true,
    'LIFE-03 cleanup audits use cleanup requestId'
  )
  assert.match(String(cleanupAudit?.details), /run-owned cleanup/u)
  assert.match(String(resetAudit?.details), /run-owned reset/u)
  scope.replayProbes.push({
    kind: 'ADMIN_FORCE_CLEANUP_REPLAY',
    status: 'PASS',
    requestRef: replay.requestRef,
    requestId: cleanup.requestId,
    auditId: cleanup.auditId
  })
  scope.contractProbes.push({
    kind: 'ADMIN_FORCE_CLEANUP_AND_RESET_SEPARATE',
    status: 'PASS',
    cleanupRequestId: cleanup.requestId,
    cleanupAuditId: cleanup.auditId,
    resetRequestId: reset.requestId,
    resetAuditId: reset.auditId,
    userAdminProbeStatus: forbidden.status,
    cleanupItems,
    cleanupReleaseLedger,
    cleanupFinancial,
    resetLedger,
    auditActorUserId: resetAudit.actorUserId
  })
  return { finalSnapshot: afterUserAdmin, finalDb: finalEvidence.db }
}

async function openPerpPosition(scope, options) {
  const { context, page } = scope
  const market = await context.api.snapshotMarket(options.symbol)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: options.symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const before = await context.api.snapshotAccount(page)
  const quantity = options.quantity ?? stepAlignedQuantity(market, 0.02)
  const capture = options.attachedProtections
    ? await submitPerpWithAttachedProtectionsViaUi(scope, {
        side: options.side,
        amount: quantity,
        levels: options.attachedProtections
      })
    : await context.ui.submitOrderViaUi(page, {
        side: options.side,
        orderType: 'MARKET',
        amount: quantity
      })
  scope.addMutation(`${slug(options.label)}-open-via-ui`, capture)
  const filled = await waitForFilledMutation(scope, before, capture, options.symbol)
  const newPositions = recordsAfter(before, filled.snapshot, 'positions')
    .filter(({ symbol }) => symbol === options.symbol)
  const position = newPositions.at(-1)
    ?? openPositions(filled.snapshot, options.symbol).at(-1)
  assert(position, `${options.label} open position`)
  assert.equal(position.side, options.side)
  assert.equal(filled.trade.side, options.side)
  assert.equal(filled.order.status, 'FILLED')
  return { ...filled, position, market, capture, before }
}

async function submitPerpWithAttachedProtectionsViaUi(scope, options) {
  const { context, page } = scope
  const selector = page.p0TradePanel?.selector
  assert(selector, 'attached protections require an open trade panel')
  await page.evaluate((panelSelector) => {
    const panel = document.querySelector(panelSelector)
    const tablists = [...(panel?.querySelectorAll('[role="tablist"]') ?? [])]
    const tabs = tablists
      .map((list) => [...list.querySelectorAll(':scope > [role="tab"]')])
      .find((items) => items.length >= 3)
    const market = tabs?.[1]
    if (!(market instanceof HTMLButtonElement)) throw new Error('P0_MARKET_TAB_MISSING')
    market.click()
  }, selector)
  await waitUntil(async () => page.evaluate((panelSelector, side) => {
    const panel = document.querySelector(panelSelector)
    const form = panel?.querySelector(
      `section[data-price-precision][class*="side--${side.toLowerCase()}"]`
    )
    return Boolean(form?.querySelector(
      'section[aria-label="Perpetual order options"]'
    ))
  }, selector, options.side), 'Perpetual attached-protection editor')
  const addGuard = await configureProtectionLevels(
    page,
    `section[data-price-precision][class*="side--${options.side.toLowerCase()}"]`,
    options.levels
  )
  return context.ui.submitOrderViaUi(page, {
    side: options.side,
    orderType: 'MARKET',
    amount: options.amount
  })
}

async function createProtectionsViaUi(scope, position, levels, options = {}) {
  const { context, page } = scope
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: position.symbol
  })
  const before = await context.api.snapshotAccount(page)
  await openPositionActionDialog(page, position)
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
  await waitUntil(async () => page.evaluate(() => Boolean(document.querySelector(
    'section[role="dialog"] section[aria-label="Position take-profit and stop-loss protections"]'
  ))), 'Position protection editor')
  await configureProtectionLevels(
    page,
    'section[role="dialog"] form',
    levels,
    options.assertAddDisabled
  )
  const capture = await context.ui.withCapturedMutation(
    page,
    {
      method: 'POST',
      url: new RegExp(`/api/trading/positions/${position.id}/protections$`, 'u')
    },
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
  if (options.expectFailure) {
    assert.equal(capture.status >= 400 && capture.status < 500, true)
    page.allowHttpError(capture.requestRef, options.reason ?? 'expected protection rejection')
  } else {
    assertSuccess(capture, 'protection creation')
    await page.waitForFunction(
      () => !document.querySelector(
        'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
      ),
      'Position protection action settled'
    )
  }
  return { capture, before, levels, addGuard }
}

async function configureProtectionLevels(page, rootSelector, levels, assertAddDisabled = false) {
  for (let index = 0; index < levels.length; index += 1) {
    const level = levels[index]
    await page.evaluate((selector, type) => {
      const root = document.querySelector(selector)
      const editor = root?.querySelector(
        'section[aria-label="Position take-profit and stop-loss protections"]'
      )
      const label = type === 'TAKE_PROFIT' ? '+ TP' : '+ SL'
      const add = [...(editor?.querySelectorAll('header button') ?? [])]
        .find((button) => button.textContent?.trim() === label)
      if (!(add instanceof HTMLButtonElement) || add.disabled) {
        throw new Error(`P0_PROTECTION_ADD_UNAVAILABLE: ${type}`)
      }
      add.click()
    }, rootSelector, level.protectionType)
    await waitUntil(async () => page.evaluate(
      (selector, count) => document.querySelector(selector)
        ?.querySelectorAll(
          'section[aria-label="Position take-profit and stop-loss protections"] fieldset'
        ).length === count,
      rootSelector,
      index + 1
    ), `protection level ${index + 1}`)
  }

  await waitUntil(async () => page.evaluate((selector, values) => {
    const root = document.querySelector(selector)
    const fieldsets = [...(root?.querySelectorAll(
      'section[aria-label="Position take-profit and stop-loss protections"] fieldset'
    ) ?? [])]
    if (fieldsets.length !== values.length) return false
    const setControl = (control, value) => {
      if (!control || value === undefined) return true
      if (control.value === String(value)) return true
      const prototype = control instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype
      const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set
      setter?.call(control, String(value))
      control.dispatchEvent(new Event('input', { bubbles: true }))
      control.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return fieldsets.every((fieldset, index) => {
      const value = values[index]
      const selects = [...fieldset.querySelectorAll('select')]
      const inputs = [...fieldset.querySelectorAll('input[inputmode="decimal"]')]
      const typeReady = setControl(selects[0], value.protectionType)
      const triggerReady = setControl(inputs[0], value.triggerPrice)
      const quantityReady = setControl(inputs[1], value.quantity)
      const executionReady = setControl(selects[1], value.triggerExecutionType)
      const limitInput = [...fieldset.querySelectorAll('input[inputmode="decimal"]')][2]
      const priceReady = value.triggerExecutionType !== 'LIMIT'
        || setControl(limitInput, value.price)
      return typeReady && triggerReady && quantityReady && executionReady && priceReady
    })
  }, rootSelector, levels), 'protection level values')

  if (assertAddDisabled) {
    const guard = await page.evaluate((selector) => {
      const editor = document.querySelector(selector)?.querySelector(
        'section[aria-label="Position take-profit and stop-loss protections"]'
      )
      const beforeCount = editor?.querySelectorAll('fieldset').length ?? 0
      const adds = [...(editor?.querySelectorAll('header button') ?? [])]
        .filter((button) => ['+ TP', '+ SL'].includes(button.textContent?.trim()))
      const disabled = adds.length === 2 && adds.every((button) => button.disabled)
      for (const add of adds) add.click()
      return {
        disabled,
        beforeCount,
        afterCount: editor?.querySelectorAll('fieldset').length ?? 0
      }
    }, rootSelector)
    assert.deepEqual(guard, {
      disabled: true,
      beforeCount: 10,
      afterCount: 10
    }, 'PROT-05 Add controls disabled at ten without an eleventh level')
    return guard
  }
  return undefined
}

async function openPositionActionDialog(page, position) {
  await page.waitForFunction((positionId) => Boolean(
    document.querySelector(`tr[data-position-id="${positionId}"] button`)
  ), 'target position row', position.id)
  await page.evaluate((positionId) => {
    const row = document.querySelector(`tr[data-position-id="${positionId}"]`)
    const button = row?.querySelector('button')
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_POSITION_ACTION_BUTTON_MISSING')
    }
    button.click()
  }, position.id)
  await page.waitForFunction(
    () => Boolean(document.querySelector(
      'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
    )),
    'Position action dialog'
  )
}

async function dismissPositionActionDialog(page) {
  const present = await page.evaluate(() => {
    const dialog = document.querySelector(
      'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
    )
    const cancel = dialog?.querySelector('footer button[type="button"]')
    if (!(cancel instanceof HTMLButtonElement) || cancel.disabled) return false
    cancel.click()
    return true
  })
  if (present) {
    await page.waitForFunction(
      () => !document.querySelector(
        'section[role="dialog"]:has([role="tablist"][aria-label="Position action type"])'
      ),
      'Position action dialog dismissed'
    )
  }
}

async function orderActionViaUi(scope, order, action, fields = {}) {
  const { context, page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/orders`)
  await page.waitForFunction(
    () => window.location.pathname === '/orders',
    `${scope.definition.id} orders route`
  )
  await page.waitForFunction((orderId) => Boolean(
    document.querySelector(`[data-order-id="${orderId}"]`)
  ), `${scope.definition.id} order row`, order.id)

  if (action === 'MODIFY_PROTECTION') {
    await page.evaluate((orderId) => {
      const row = document.querySelector(`[data-order-id="${orderId}"]`)
      const modify = row?.querySelectorAll('button')[1]
      if (!(modify instanceof HTMLButtonElement) || modify.disabled) {
        throw new Error('P0_PROTECTION_MODIFY_UNAVAILABLE')
      }
      modify.click()
    }, order.id)
    await page.waitForFunction(
      () => Boolean(document.querySelector('section[aria-label] form button[type="submit"]')),
      'protection modify form'
    )
    await waitUntil(async () => page.evaluate((values) => {
      const form = document.querySelector('section[aria-label] form:has(button[type="submit"])')
      if (!form) return false
      const controls = [...form.querySelectorAll('input, select')]
      const inputs = controls.filter((control) => control instanceof HTMLInputElement)
      const select = controls.find((control) => control instanceof HTMLSelectElement)
      const setControl = (control, value) => {
        if (!control || value === undefined) return true
        if (control.value === String(value)) return true
        const prototype = control instanceof HTMLSelectElement
          ? HTMLSelectElement.prototype
          : HTMLInputElement.prototype
        const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set
        setter?.call(control, String(value))
        control.dispatchEvent(new Event('input', { bubbles: true }))
        control.dispatchEvent(new Event('change', { bubbles: true }))
        return false
      }
      return setControl(inputs[0], values.quantity)
        && setControl(inputs[1], values.price)
        && setControl(inputs[2], values.triggerPrice)
        && setControl(select, values.triggerExecutionType)
    }, fields), 'protection modify fields')
    const capture = await context.ui.withCapturedMutation(
      page,
      { method: 'PATCH', url: new RegExp(`/api/trading/protections/${order.id}$`, 'u') },
      () => page.evaluate(() => {
        const submit = document.querySelector('section[aria-label] form button[type="submit"]')
        if (!(submit instanceof HTMLButtonElement) || submit.disabled) {
          throw new Error('P0_PROTECTION_MODIFY_CONFIRM_MISSING')
        }
        submit.click()
        return true
      })
    )
    assertSuccess(capture, 'protection modification')
    return capture
  }

  await page.evaluate((orderId) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    const cancel = [...(row?.querySelectorAll('button') ?? [])].at(-1)
    if (!(cancel instanceof HTMLButtonElement) || cancel.disabled) {
      throw new Error('P0_ORDER_CANCEL_UNAVAILABLE')
    }
    cancel.click()
  }, order.id)
  await page.waitForFunction(
    () => Boolean(document.querySelector(
      '[role="dialog"][aria-modal="true"][aria-labelledby="order-cancel-title"]'
    )),
    `${scope.definition.id} cancel dialog`
  )
  const protection = action === 'CANCEL_PROTECTION'
  const capture = await context.ui.withCapturedMutation(
    page,
    protection
      ? { method: 'DELETE', url: new RegExp(`/api/trading/protections/${order.id}$`, 'u') }
      : { method: 'POST', url: new RegExp(`/api/trading/orders/${order.id}/cancel$`, 'u') },
    () => page.evaluate(() => {
      const dialog = document.querySelector(
        '[role="dialog"][aria-modal="true"][aria-labelledby="order-cancel-title"]'
      )
      const confirm = dialog?.querySelector('button')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_ORDER_CANCEL_CONFIRM_MISSING')
      }
      confirm.click()
      return true
    })
  )
  assertSuccess(capture, protection ? 'protection cancel' : 'order cancel')
  return capture
}

async function closePerpPosition(scope, before, position, label) {
  await scope.context.ui.openTradePanel(scope.page, {
    product: 'perpetual',
    symbol: position.symbol
  })
  const capture = await scope.context.ui.positionActionViaUi(scope.page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'FULL_CLOSE'
  })
  scope.addMutation(`${slug(label)}-via-ui`, capture)
  const snapshot = await waitForAccount(
    scope.context,
    scope.page,
    `${label} position close`,
    (candidate) => !openPositions(candidate).some(({ id }) => id === position.id)
      && candidate
  )
  const trade = recordsAfter(before, snapshot, 'trades')
    .find(({ symbol, side }) => symbol === position.symbol && side !== position.side)
  assert(trade, `${label} closing Trade`)
  return { snapshot, trade, capture }
}

async function ensurePerpSettings(scope, settings) {
  const captures = await scope.context.ui.setPerpetualSettingsViaUi(
    scope.page,
    settings
  )
  for (const capture of captures) {
    scope.addMutation('perpetual-settings-via-ui', capture)
  }
  return captures
}

async function waitForAccount(context, page, description, predicate, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let latest
  while (Date.now() < deadline) {
    latest = await context.api.snapshotAccount(page)
    const matched = predicate(latest)
    if (matched) return typeof matched === 'object' ? matched : latest
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function waitForCreatedOrder(scope, before, capture, symbol, status) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} ${symbol} ${status}`,
    (snapshot) => {
      const order = orderForCapture(before, snapshot, capture)
      return order?.symbol === symbol && order.status === status
        ? { snapshot, order }
        : false
    }
  )
}

async function waitForFilledMutation(scope, before, capture, symbol) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} ${symbol} fill`,
    (snapshot) => {
      const order = orderForCapture(before, snapshot, capture)
      if (!order || order.symbol !== symbol || order.status !== 'FILLED') return false
      const trade = snapshot.trades.find(({ orderId }) => orderId === order.id)
      return trade ? { snapshot, order, trade } : false
    }
  )
}

async function waitForOrderStatus(scope, orderId, status) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} order ${orderId} ${status}`,
    (snapshot) => orderById(snapshot, orderId)?.status === status && snapshot
  )
}

async function waitForOrderVersion(scope, orderId, minimum) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} order ${orderId} version ${minimum}`,
    (snapshot) => {
      const order = orderById(snapshot, orderId)
      return Number(order?.version) >= minimum ? { snapshot, order } : false
    }
  )
}

async function waitForProtection(scope, positionId, creation) {
  const response = parsedResponse(creation.capture ?? creation)?.data
    ?? parsedResponse(creation.capture ?? creation)
  const expectedId = response?.id
  const before = creation.before ?? { orders: [] }
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} protection creation`,
    (snapshot) => {
      const order = expectedId
        ? orderById(snapshot, expectedId)
        : recordsAfter(before, snapshot, 'orders')
          .find(({ parentPositionId }) => parentPositionId === positionId)
      return order?.parentPositionId === positionId
        ? { snapshot, order }
        : false
    }
  )
}

async function waitForProtectionCount(scope, positionId, count) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} ${count} protections`,
    (snapshot) => protectionOrders(snapshot, positionId).length === count && snapshot,
    45000
  )
}

async function waitForProtectionFilled(scope, before, orderId) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} protection ${orderId} fill`,
    (snapshot) => {
      const order = orderById(snapshot, orderId)
      const trades = recordsAfter(before, snapshot, 'trades')
        .filter(({ orderId: candidate }) => candidate === orderId)
      return order?.status === 'FILLED' && trades.length === 1
        ? { snapshot, order, trade: trades[0] }
        : false
    },
    45000
  )
}

async function observeProtectionPending(scope, orderId, durationMs) {
  const deadline = Date.now() + durationMs
  do {
    const snapshot = await scope.context.api.snapshotAccount(scope.page)
    assert.equal(orderById(snapshot, orderId)?.status, 'PENDING_ACTIVATION')
    assert.equal(snapshot.trades.some(({ orderId: id }) => id === orderId), false)
    await delay(100)
  } while (Date.now() < deadline)
}

async function waitForPositionQuantity(scope, positionId, expected) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} position ${positionId} quantity ${expected}`,
    (snapshot) => {
      const position = snapshot.positions.find(({ id }) => id === positionId)
      return position && Number(position.lots) === Number(expected) ? snapshot : false
    },
    45000
  )
}

async function waitForTransfer(scope, before, capture) {
  const response = parsedResponse(capture)?.data ?? parsedResponse(capture)
  const transferId = response?.transferId ?? parsedRequest(capture).requestId
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} transfer ${transferId}`,
    (snapshot) => recordsAfter(before, snapshot, 'transfers')
      .some((transfer) => transfer.transferId === transferId) && snapshot
  )
}

async function actionEvents(scope, cursor, expectedTypes, label) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    const frames = scope.context.events.snapshotFrames(scope.page).slice(cursor)
    if (expectedTypes.every((type) => frames.some(({ direction, eventType }) => (
      direction === 'received' && eventType === type
    )))) {
      return frames.filter(({ eventType }) => expectedTypes.includes(eventType))
    }
    await delay(100)
  }
  throw new Error(`${label} missing STOMP events: ${expectedTypes.join(', ')}`)
}

async function waitUntil(predicate, description, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await delay(50)
  }
  throw new Error(`Timed out waiting for ${description}`)
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
  const market = await scope.context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const tick = Number(rules.tickSize ?? rules.priceTick)
  const spread = Number(quoteDecimal(market, 'ask')) - Number(quoteDecimal(market, 'bid'))
  const half = Math.max(spread / 2, tick)
  const bid = alignedPrice(market, Number(target) - half)
  const ask = alignedPrice(market, Number(target) + half)
  assert(Number(ask) > Number(bid), `${symbol} authority spread`)
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
  scope.registerFixtureRestore({ action: 'restore-authority-market-bundle', symbol }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-authority-market-bundle',
    symbol,
    bid: String(bid),
    ask: String(ask)
  })
  return waitForMarket(
    scope.context,
    symbol,
    `${scope.definition.id} ${symbol} authority bundle`,
    (market) => Number(quoteDecimal(market, 'bid')) === Number(bid)
      && Number(quoteDecimal(market, 'ask')) === Number(ask)
  )
}

async function waitForMarket(context, symbol, description, predicate) {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    const market = await context.api.snapshotMarket(symbol)
    if (predicate(market)) return market
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function transferViaUiWithDoubleConfirm(scope, options) {
  const { context, page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/wallet`)
  await page.waitForFunction(
    () => window.location.pathname === '/wallet'
      && [...document.querySelectorAll('button')]
        .some((button) => button.textContent?.trim() === 'Transfer Spot / Perpetual'),
    'wallet transfer route'
  )
  await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.trim() === 'Transfer Spot / Perpetual')
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_TRANSFER_ACTION_MISSING')
    }
    button.click()
  })
  await page.waitForFunction(
    () => Boolean(document.getElementById('wallet-transfer-title')?.closest('[role="dialog"]')),
    'wallet transfer dialog'
  )
  await waitUntil(async () => page.evaluate((values) => {
    const dialog = document.getElementById('wallet-transfer-title')?.closest('[role="dialog"]')
    const select = dialog?.querySelector('select')
    const input = [...(dialog?.querySelectorAll('input') ?? [])]
      .find((candidate) => candidate.inputMode === 'decimal')
    const setControl = (control, value) => {
      if (!control || control.value === String(value)) return Boolean(control)
      const prototype = control instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype
      const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set
      setter?.call(control, String(value))
      control.dispatchEvent(new Event('input', { bubbles: true }))
      control.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return setControl(select, values.direction) && setControl(input, values.amount)
  }, options), 'wallet transfer fields')
  const capture = await context.ui.withCapturedMutation(
    page,
    { method: 'POST', url: /\/api\/accounts\/[^/]+\/transfers$/u },
    () => page.evaluate(() => {
      const dialog = document.getElementById('wallet-transfer-title')?.closest('[role="dialog"]')
      const confirm = [...(dialog?.querySelectorAll('button') ?? [])]
        .find((button) => button.textContent?.trim() === 'Confirm transfer')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_TRANSFER_CONFIRM_MISSING')
      }
      confirm.click()
      confirm.click()
      return true
    })
  )
  assertSuccess(capture, 'wallet transfer controlled double click')
  return capture
}

async function openAdminAccountViaUi(page, accountId) {
  await page.navigate(`${page.p0Options.adminBaseUrl}/accounts`)
  await page.waitForFunction(
    () => window.location.pathname === '/accounts'
      && [...document.querySelectorAll('label')]
        .some((label) => label.textContent?.includes('账户 ID')),
    'Admin accounts route'
  )
  await waitUntil(async () => page.evaluate((value) => {
    const label = [...document.querySelectorAll('label')]
      .find((candidate) => candidate.textContent?.includes('账户 ID'))
    const input = label?.querySelector('input')
    if (!(input instanceof HTMLInputElement)) return false
    if (input.value !== value) {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
      setter?.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return true
  }, accountId), 'Admin account filter')
  await page.waitForFunction((value) => Boolean(
    [...document.querySelectorAll('a')]
      .find((link) => link.textContent?.trim() === value)
  ), 'Admin account result', accountId)
  await page.evaluate((value) => {
    const link = [...document.querySelectorAll('a')]
      .find((candidate) => candidate.textContent?.trim() === value)
    if (!(link instanceof HTMLAnchorElement)) throw new Error('P0_ADMIN_ACCOUNT_LINK_MISSING')
    link.click()
  }, accountId)
  await page.waitForFunction(
    () => document.body.textContent?.includes('Demo 交易账户详情')
      && !document.querySelector('.state-block.loading, .state-block.error'),
    'Admin Demo account detail'
  )
}

async function probeAdminResetDisabled(page) {
  const cursor = page.p0Evidence.cursor
  const disabled = await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.trim() === '重置 Demo 账户')
    if (!(button instanceof HTMLButtonElement)) throw new Error('P0_ADMIN_RESET_MISSING')
    button.click()
    return button.disabled
  })
  await delay(100)
  const requestSent = page.p0Evidence.requests.some((request) => (
    request.cursor > cursor
      && request.method === 'POST'
      && /\/api\/admin\/accounts\/[^/]+\/demo-reset$/u.test(request.url)
  ))
  return { disabled, requestSent }
}

async function adminHighRiskActionViaUi(scope, page, options) {
  await page.evaluate((label) => {
    const button = [...document.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.trim() === label)
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error(`P0_ADMIN_ACTION_UNAVAILABLE: ${label}`)
    }
    button.click()
  }, options.label)
  await page.waitForFunction(
    () => Boolean(document.querySelector('section.high-risk-action-dialog[role="dialog"]')),
    `${options.label} dialog`
  )
  const requestId = await page.evaluate(() => document.querySelector(
    '[data-testid="high-risk-request-id"]'
  )?.textContent?.trim())
  assertUuid(requestId, `${options.label} request id`)
  await waitUntil(async () => page.evaluate((reason) => {
    const dialog = document.querySelector('section.high-risk-action-dialog[role="dialog"]')
    const input = dialog?.querySelector('textarea')
    if (!(input instanceof HTMLTextAreaElement)) return false
    if (input.value !== reason) {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
      setter?.call(input, reason)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return false
    }
    return true
  }, options.reason), `${options.label} reason`)
  await page.evaluate(() => {
    const button = [...document.querySelectorAll(
      'section.high-risk-action-dialog[role="dialog"] button'
    )].find((candidate) => candidate.textContent?.trim() === 'Continue to confirmation')
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_ADMIN_CONTINUE_MISSING')
    }
    button.click()
  })
  await page.waitForFunction(
    (label) => [...document.querySelectorAll(
      'section.high-risk-action-dialog[role="dialog"] button'
    )].some((button) => button.textContent?.trim() === label),
    `${options.label} second confirmation`,
    options.confirmLabel
  )
  const capture = await scope.context.ui.withCapturedMutation(
    page,
    options.matcher,
    () => page.evaluate((label, doubleConfirm) => {
      const button = [...document.querySelectorAll(
        'section.high-risk-action-dialog[role="dialog"] button'
      )].find((candidate) => candidate.textContent?.trim() === label)
      if (!(button instanceof HTMLButtonElement) || button.disabled) {
        throw new Error(`P0_ADMIN_CONFIRM_MISSING: ${label}`)
      }
      button.click()
      if (doubleConfirm) button.click()
      return true
    }, options.confirmLabel, options.doubleConfirm === true)
  )
  assertSuccess(capture, options.label)
  await page.waitForFunction(
    () => Boolean(document.querySelector('[data-testid="high-risk-audit-id"]'))
      && document.body.textContent?.includes('Operation accepted'),
    `${options.label} operation accepted`
  )
  const auditId = await page.evaluate(() => document.querySelector(
    '[data-testid="high-risk-audit-id"]'
  )?.textContent?.trim())
  assertUuid(auditId, `${options.label} audit id`)
  return { capture, requestId, auditId }
}

async function closeAdminHighRiskDialog(page) {
  await page.evaluate(() => {
    const button = [...document.querySelectorAll(
      'section.high-risk-action-dialog[role="dialog"] button'
    )].find((candidate) => candidate.textContent?.trim() === 'Close')
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_ADMIN_HIGH_RISK_CLOSE_MISSING')
    }
    button.click()
  })
  await page.waitForFunction(
    () => !document.querySelector('section.high-risk-action-dialog[role="dialog"]'),
    'Admin high-risk dialog closed'
  )
}

async function replayCapturedMutation(scope, page, capture, mutate, options = {}) {
  const raw = capture?.rawRequest
  assert(raw && typeof raw.url === 'string', 'captured raw request is required')
  const targetUrl = options.url ?? raw.url
  const originalBody = raw.postData ? JSON.parse(raw.postData) : undefined
  const body = typeof mutate === 'function'
    ? mutate(structuredClone(originalBody))
    : originalBody
  const replay = await scope.context.ui.withCapturedMutation(
    page,
    ({ method, url }) => method === raw.method && url === targetUrl,
    () => page.evaluate(async (request, payload, tokenKey) => {
      const token = localStorage.getItem(tokenKey)
      const response = await fetch(request.url, {
        method: request.method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: payload === undefined ? undefined : JSON.stringify(payload)
      })
      const text = await response.text()
      let data
      try {
        data = text ? JSON.parse(text) : null
      } catch {
        data = text
      }
      return { status: response.status, data }
    }, { method: raw.method, url: targetUrl }, body,
    options.tokenKey ?? 'fx-platform-auth-token')
  )
  if (options.expectFailure) {
    assert.equal(replay.status >= 400 && replay.status < 500, true)
    page.allowHttpError(replay.requestRef, options.reason ?? 'expected contract rejection')
  } else {
    assertSuccess(replay, options.reason ?? 'captured mutation replay')
  }
  return replay
}

function parsedRequest(capture) {
  assert(capture?.rawRequest?.postData, 'captured request body required')
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
  return response?.code
    ?? response?.data?.code
    ?? capture?.actionResult?.data?.code
}

function assertRejectedCode(capture, expected, label) {
  assert.equal(capture.status >= 400 && capture.status < 500, true, `${label} status`)
  assert.equal(responseCode(capture), expected, `${label} error code`)
}

function assertSuccess(capture, label) {
  assert(
    capture.status >= 200 && capture.status < 300,
    `${label} must return 2xx, got ${capture.status}`
  )
}

function assertTradingStateEqual(before, after, label) {
  assert.deepEqual(after, before, label)
}

function tradingStateFingerprint(snapshot) {
  const pick = (rows, fields) => (rows ?? []).map((row) => (
    Object.fromEntries(fields.map((field) => [field, row[field]]))
  )).toSorted((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
  return {
    wallets: pick(snapshot.wallets, ['walletType', 'asset', 'total', 'available', 'locked']),
    summary: Object.fromEntries([
      'balance',
      'equity',
      'usedMargin',
      'freeMargin',
      'openFloatingPnl',
      'maintenanceMargin'
    ].map((field) => [field, snapshot.summary[field]])),
    orders: pick(snapshot.orders, [
      'id', 'status', 'quantity', 'price', 'triggerPrice', 'holdAmount', 'version'
    ]),
    trades: pick(snapshot.trades, [
      'id', 'orderId', 'price', 'lots', 'fee', 'realizedPnl'
    ]),
    positions: pick(snapshot.positions, [
      'id', 'status', 'lots', 'openPrice', 'marginHeld', 'realizedPnl', 'version'
    ]),
    positionHistory: pick(snapshot.positionHistory, [
      'id', 'status', 'lots', 'realizedPnl', 'version'
    ]),
    transfers: pick(snapshot.transfers, [
      'transferId', 'direction', 'amount', 'status'
    ]),
    assetLedger: pick(snapshot.assetLedger, [
      'id', 'entryType', 'amount', 'referenceType', 'referenceId'
    ])
  }
}

function completeStateFingerprint(snapshot) {
  return {
    ...tradingStateFingerprint(snapshot),
    settings: structuredClone(snapshot.settings)
  }
}

function protectionBusinessFingerprint(order) {
  assert(order && typeof order === 'object', 'protection order is required')
  const decimals = [
    'lots',
    'quantity',
    'price',
    'executionPrice',
    'filledQuantity',
    'remainingQuantity',
    'avgFillPrice',
    'fee',
    'slippage',
    'holdAmount',
    'originalQuantity',
    'baseQuantity',
    'activationPrice',
    'trailingDelta',
    'trailingRate',
    'trailingExtreme',
    'triggerPrice'
  ]
  const values = Object.fromEntries(decimals.map((field) => [
    field,
    order[field] == null ? null : String(order[field])
  ]))
  return {
    id: order.id ?? null,
    accountId: order.accountId ?? null,
    clientOrderId: order.clientOrderId ?? null,
    symbol: order.symbol ?? null,
    side: order.side ?? null,
    orderType: order.orderType ?? null,
    leverage: order.leverage ?? null,
    status: order.status ?? null,
    ...values,
    holdCurrency: order.holdCurrency ?? null,
    rejectCode: order.rejectCode ?? null,
    rejectMessage: order.rejectMessage ?? null,
    productType: order.productType ?? null,
    positionMode: order.positionMode ?? null,
    positionSide: order.positionSide ?? null,
    marginMode: order.marginMode ?? null,
    quantityUnit: order.quantityUnit ?? null,
    timeInForce: order.timeInForce ?? null,
    postOnly: order.postOnly ?? null,
    reduceOnly: order.reduceOnly ?? null,
    origin: order.origin ?? order.orderOrigin ?? null,
    systemReason: order.systemReason ?? null,
    feeAsset: order.feeAsset ?? null,
    liquidityRole: order.liquidityRole ?? null,
    triggerPriceType: order.triggerPriceType ?? null,
    triggerExecutionType: order.triggerExecutionType ?? null,
    protectionType: order.protectionType ?? null,
    parentOrderId: order.parentOrderId ?? null,
    parentPositionId: order.parentPositionId ?? null,
    contingencyGroupId: order.contingencyGroupId ?? null,
    holdOwnerOrderId: order.holdOwnerOrderId ?? null,
    version: order.version ?? null
  }
}

function assertProtectionModification(before, after, request, label) {
  assert.equal(request.expectedVersion, Number(before.version), `${label} expected version`)
  assert.equal(request.quantityUnit, before.quantityUnit, `${label} quantity unit`)
  const expected = protectionBusinessFingerprint(before)
  if (request.quantity !== undefined) {
    const quantity = String(request.quantity)
    for (const field of [
      'lots', 'quantity', 'originalQuantity', 'baseQuantity', 'remainingQuantity'
    ]) {
      expected[field] = quantity
    }
  }
  if (request.triggerPrice !== undefined) expected.triggerPrice = String(request.triggerPrice)
  if (request.triggerExecutionType !== undefined) {
    expected.triggerExecutionType = request.triggerExecutionType
  }
  expected.price = request.triggerExecutionType === 'MARKET'
    ? null
    : request.price === undefined ? expected.price : String(request.price)
  expected.version = Number(before.version) + 1
  const actual = protectionBusinessFingerprint(after)
  assert.deepEqual(actual, expected, `${label} complete business fingerprint`)
  return actual
}

function walletFingerprint(snapshot) {
  return {
    wallets: tradingStateFingerprint(snapshot).wallets,
    summary: tradingStateFingerprint(snapshot).summary,
    transfers: tradingStateFingerprint(snapshot).transfers
  }
}

function orderForCapture(before, after, capture) {
  const created = recordsAfter(before, after, 'orders')
  return created.find((order) => (
    !capture.idempotencyKey
      || order.clientOrderId === capture.idempotencyKey
      || order.idempotencyKey === capture.idempotencyKey
  )) ?? created.at(-1)
}

function recordsAfter(before, after, field) {
  const known = new Set((before?.[field] ?? []).map(({ id, transferId }) => id ?? transferId))
  return (after?.[field] ?? []).filter(({ id, transferId }) => !known.has(id ?? transferId))
}

function orderById(snapshot, orderId) {
  return snapshot.orders.find(({ id }) => id === orderId)
}

function activeOrders(snapshot, symbol) {
  return snapshot.orders.filter((order) => (
    (!symbol || order.symbol === symbol) && ACTIVE_ORDER_STATUSES.has(order.status)
  ))
}

function protectionOrders(snapshot, positionId) {
  return snapshot.orders.filter((order) => (
    order.parentPositionId === positionId || (
      order.orderOrigin === 'PROTECTIVE'
        && (!positionId || order.parentPositionId === positionId)
    )
  ))
}

function openPositions(snapshot, symbol) {
  return snapshot.positions.filter((position) => (
    String(position.instrumentType ?? '').toUpperCase() !== 'SPOT'
      && (!symbol || position.symbol === symbol)
      && (position.status === undefined || position.status === 'OPEN')
  ))
}

function findWallet(snapshot, walletType, asset) {
  const wallet = snapshot.wallets.find((candidate) => (
    candidate.walletType === walletType && candidate.asset === asset
  ))
  assert(wallet, `wallet ${walletType}/${asset} is required`)
  return wallet
}

function rulesFor(market) {
  assert(market?.rules && typeof market.rules === 'object', 'symbol rules required')
  return market.rules
}

function quoteDecimal(market, field) {
  const value = market?.quote?.[field] ?? market?.reference?.[field]
  assert(Number.isFinite(Number(value)) && Number(value) > 0, `market ${field} positive`)
  return String(value)
}

function lastPrice(market) {
  const value = Number(
    market.quote?.last
      ?? market.quote?.mid
      ?? market.reference?.last
      ?? (Number(quoteDecimal(market, 'bid')) + Number(quoteDecimal(market, 'ask'))) / 2
  )
  assert(Number.isFinite(value) && value > 0, 'authority last price')
  return value
}

function markPrice(market) {
  const value = Number(
    market.reference?.mark
      ?? market.quote?.markPrice
      ?? market.quote?.mid
      ?? (Number(quoteDecimal(market, 'bid')) + Number(quoteDecimal(market, 'ask'))) / 2
  )
  assert(Number.isFinite(value) && value > 0, 'authority mark price')
  return value
}

function alignedPrice(market, value) {
  return alignPriceToTick(
    Number(value).toFixed(12).replace(/0+$/u, '').replace(/\.$/u, ''),
    rulesFor(market)
  )
}

function stepAlignedQuantity(market, preferred) {
  const rules = rulesFor(market)
  const stepText = effectiveQuantityStep(rules)
  const step = Number(stepText)
  const reference = markPrice(market)
  const minimum = Math.max(
    preferred,
    step,
    Number(rules.minQty ?? step),
    Number(rules.minNotional ?? 0) / reference
  )
  const steps = Math.ceil((minimum - step * 1e-8) / step)
  return (steps * step).toFixed((stepText.split('.')[1] ?? '').length)
}

function multiplyAlignedQuantity(quantity, multiplier, rules) {
  const step = effectiveQuantityStep(rules)
  return (Number(quantity) * multiplier).toFixed((step.split('.')[1] ?? '').length)
}

function protectionLevel(market, protectionType, quantity, trigger, execution, price) {
  return {
    protectionType,
    quantity,
    triggerPrice: alignedPrice(market, trigger),
    triggerExecutionType: execution,
    ...(execution === 'LIMIT' ? { price: alignedPrice(market, price) } : {})
  }
}

function assertProtectionBudgets(orders, positionQuantity, label) {
  for (const type of ['TAKE_PROFIT', 'STOP_LOSS']) {
    const total = orders
      .filter(({ protectionType }) => protectionType === type)
      .reduce((sum, { quantity, lots }) => sum + Number(quantity ?? lots), 0)
    assert(total <= Number(positionQuantity) + 1e-8, `${label} ${type} budget`)
  }
}

function expectedProtectionResize(before, remaining) {
  const expected = new Map(before.map((order) => [order.id, {
    id: order.id,
    quantity: normalizedQuantity(order.quantity ?? order.lots),
    status: order.status,
    version: Number(order.version),
    changed: false
  }]))
  for (const type of ['TAKE_PROFIT', 'STOP_LOSS']) {
    let capacity = Number(remaining)
    const active = before
      .filter((order) => (
        order.protectionType === type && ACTIVE_ORDER_STATUSES.has(order.status)
      ))
      .toSorted((left, right) => (
        String(left.createdAt).localeCompare(String(right.createdAt))
          || left.id.localeCompare(right.id)
      ))
    for (const order of active) {
      const original = Number(order.quantity ?? order.lots)
      const next = Math.max(0, Math.min(original, capacity))
      capacity = Math.max(0, capacity - next)
      if (Math.abs(next - original) <= 1e-8) continue
      expected.set(order.id, {
        id: order.id,
        quantity: normalizedQuantity(next),
        status: next <= 1e-8 ? 'EXPIRED' : order.status,
        version: Number(order.version) + 1,
        changed: true
      })
    }
  }
  return before.map(({ id }) => expected.get(id))
}

function assertProtectionResize(before, after, remaining, label, beforeDb, afterDb) {
  assertProtectionBudgets(
    after.filter(({ status }) => ACTIVE_ORDER_STATUSES.has(status)),
    remaining,
    label
  )
  const expected = expectedProtectionResize(before, remaining)
  assert.deepEqual(
    after.map(({ id }) => id).toSorted(),
    before.map(({ id }) => id).toSorted(),
    `${label} protection identities`
  )
  for (const item of expected) {
    const actual = after.find(({ id }) => id === item.id)
    assert(actual, `${label} protection ${item.id}`)
    assertDecimalClose(
      actual.quantity ?? actual.lots,
      item.quantity,
      '0.00000001',
      `${label} ${item.id} quantity`
    )
    assert.equal(actual.status, item.status, `${label} ${item.id} status`)
    assert.equal(Number(actual.version), item.version, `${label} ${item.id} version`)
  }
  if (beforeDb && afterDb) {
    const beforeIds = new Set((beforeDb.orderEventRows ?? []).map(({ id }) => id))
    const added = (afterDb.orderEventRows ?? []).filter(({ id }) => !beforeIds.has(id))
    for (const item of expected) {
      const expectedType = item.status === 'EXPIRED'
        ? 'PROTECTION_EXPIRED'
        : 'PROTECTION_RESIZED'
      const matches = added.filter((event) => (
        event.order_id === item.id
          && ['PROTECTION_EXPIRED', 'PROTECTION_RESIZED'].includes(event.event_type)
      ))
      assert.equal(matches.length, item.changed ? 1 : 0, `${label} ${item.id} exact resize event`)
      if (item.changed) {
        assert.equal(matches[0].event_type, expectedType, `${label} ${item.id} resize event type`)
      }
    }
  }
  return expected
}

function summarizeProtections(orders) {
  return orders.map(({ id, protectionType, quantity, lots, status, version, createdAt }) => ({
    id,
    protectionType,
    quantity: quantity ?? lots,
    status,
    version,
    createdAt
  }))
}

function assertBatchReleaseRows(beforeDb, afterDb, orders, label = 'BATCH-01') {
  const ids = new Set(orders.map(({ id }) => id))
  const releasedSpot = recordsAfter(beforeDb, afterDb, 'assetLedgerRows')
    .filter(({ entry_type: type, reference_id: id }) => (
      type === 'SPOT_ORDER_RELEASE' && ids.has(id)
    ))
  const releasedPerp = recordsAfter(beforeDb, afterDb, 'cashLedgerRows')
    .filter(({ operation_type: type, reference_id: id }) => (
      type === 'ORDER_RELEASE' && ids.has(id)
    ))
  const released = [...releasedSpot, ...releasedPerp]
  const held = orders.filter(({ holdAmount }) => Number(holdAmount) > 0)
  assert.equal(released.length, held.length, `${label} exact release row count`)
  for (const order of held) {
    const matches = released.filter(({ reference_id: id }) => id === order.id)
    assert.equal(matches.length, 1, `${label} exact release ${order.id}`)
    assertDecimalClose(
      matches[0].amount,
      order.holdAmount,
      '0.00000001',
      `${label} release amount ${order.id}`
    )
  }
  return released.map(({ id }) => id)
}

function assertOrderEvent(db, orderId, eventType, count, label) {
  assert.equal(
    db.orderEventRows.filter((event) => (
      event.order_id === orderId && event.event_type === eventType
    )).length,
    count,
    `${label} ${eventType}`
  )
}

function assertTransferLedgerPair(db, requestId, label) {
  const assetEntries = (db.assetLedgerRows ?? []).filter((entry) => (
    entry.reference_id === requestId
      || entry.transfer_id === requestId
      || entry.request_id === requestId
  ))
  const cashEntries = (db.cashLedgerRows ?? []).filter((entry) => (
      entry.reference_id === requestId
        || entry.transfer_id === requestId
        || entry.request_id === requestId
  ))
  const entries = [...assetEntries, ...cashEntries]
  assert.equal(assetEntries.length, 1, `${label} exact Spot transfer ledger`)
  assert.equal(cashEntries.length, 1, `${label} exact Perp transfer ledger`)
  assert.equal(entries.length, 2, `${label} exact paired transfer ledger`)
  const amounts = entries.map(({ amount }) => Number(amount)).filter(Number.isFinite)
  assert.equal(
    Math.abs(amounts.reduce((sum, amount) => sum + amount, 0)) <= 0.00000001,
    true,
    `${label} paired ledger conservation`
  )
  return entries.map(({ id }) => id)
}

function normalizedQuantity(value) {
  const fixed = Number(value).toFixed(8)
  const compact = fixed.replace(/0+$/u, '').replace(/\.$/u, '')
  return compact === '-0' ? '0' : compact
}

function assertDbReplayUnchanged(before, after, label) {
  for (const field of [
    'activeDemoAccounts',
    'orders',
    'trades',
    'openPositions',
    'fundingSettlements',
    'accountRow',
    'walletRows',
    'orderRows',
    'orderEventRows',
    'tradeRows',
    'positionRows',
    'spotPositionRows',
    'symbolSettingRows',
    'assetLedgerRows',
    'cashLedgerRows',
    'fundingSettlementRows',
    'batchActionRows',
    'auditRows'
  ]) {
    assert.deepEqual(after?.[field], before?.[field], `${label} ${field} zero delta`)
  }
}

function assertResetLedgerContract(beforeDb, afterDb, requestId, accountId, label) {
  const preservedIds = (field) => new Set((beforeDb[field] ?? []).map(({ id }) => id))
  for (const [field, description] of [
    ['assetLedgerRows', 'asset ledger'],
    ['cashLedgerRows', 'cash ledger']
  ]) {
    for (const row of beforeDb[field] ?? []) {
      const matches = (afterDb[field] ?? []).filter(({ id }) => id === row.id)
      assert.equal(matches.length, 1, `${label} preserves ${description} ${row.id}`)
      assert.deepEqual(matches[0], row, `${label} preserves ${description} ${row.id}`)
    }
  }
  const assetIds = preservedIds('assetLedgerRows')
  const cashIds = preservedIds('cashLedgerRows')
  const addedAssets = (afterDb.assetLedgerRows ?? []).filter(({ id }) => !assetIds.has(id))
  const addedCash = (afterDb.cashLedgerRows ?? []).filter(({ id }) => !cashIds.has(id))
  assert(addedAssets.length > 0, `${label} asset DEMO_RESET rows`)
  assert.equal(addedCash.length, 1, `${label} exactly one cash DEMO_RESET row`)
  for (const [kind, entries] of [['asset', addedAssets], ['cash', addedCash]]) {
    for (const entry of entries) {
      assert.equal(entry.account_id, accountId, `${label} ${kind} account`)
      assert.equal(entry.entry_type, 'DEMO_RESET', `${label} ${kind} entry type`)
      assert.equal(entry.operation_type, 'DEMO_RESET', `${label} ${kind} operation type`)
      assert.equal(entry.reference_type, 'DEMO_RESET', `${label} ${kind} reference type`)
      assert.equal(entry.reference_id, requestId, `${label} ${kind} requestId`)
    }
  }
  return {
    assetLedgerIds: addedAssets.map(({ id }) => id),
    cashLedgerId: addedCash[0].id
  }
}

function assertCleanupItems(items, orderIds, positionIds, label) {
  assert(Array.isArray(items), `${label} items`)
  assert.equal(
    items.length,
    orderIds.length + positionIds.length,
    `${label} exact item count`
  )
  const requireSuccessFields = (item, itemLabel) => {
    for (const field of ['status', 'errorCode', 'message']) {
      assert(Object.hasOwn(item, field), `${itemLabel} ${field} field`)
    }
    assert.equal(item.errorCode, null, `${itemLabel} errorCode`)
    assert.equal(item.message, null, `${itemLabel} message`)
  }
  for (const orderId of orderIds) {
    const matches = items.filter((item) => item.orderId === orderId && item.positionId == null)
    assert.equal(matches.length, 1, `${label} exact item for order ${orderId}`)
    requireSuccessFields(matches[0], `${label} order ${orderId}`)
    assert.equal(matches[0].status, 'CANCELED', `${label} order ${orderId} status`)
  }
  for (const positionId of positionIds) {
    const matches = items.filter((item) => item.positionId === positionId)
    assert.equal(matches.length, 1, `${label} exact item for position ${positionId}`)
    requireSuccessFields(matches[0], `${label} position ${positionId}`)
    assertUuid(matches[0].orderId, `${label} position ${positionId} close order`)
    assert.equal(matches[0].status, 'FILLED', `${label} position ${positionId} status`)
  }
  return items.map(({ positionId, orderId, status, errorCode, message }) => ({
    positionId,
    orderId,
    status,
    errorCode,
    message
  }))
}

function auditFingerprint(response, requestId) {
  return (response?.items ?? [])
    .filter((item) => item.requestId === requestId)
    .toSorted((left, right) => String(left.id).localeCompare(String(right.id)))
}

function auditRowsForRequest(before, after, requestId, label) {
  const known = new Set(auditFingerprint(before, requestId).map(({ id }) => id))
  const added = auditFingerprint(after, requestId).filter(({ id }) => !known.has(id))
  assert(added.length > 0, `${label} Admin audit rows`)
  assert.equal(new Set(added.map(({ id }) => id)).size, added.length, `${label} unique audits`)
  return added
}

function assertSpotExecutionFinancials({
  before,
  after,
  trade,
  rules,
  expectedLiquidity,
  expectedPrice,
  executionMarket,
  label
}) {
  assert.equal(trade.liquidityRole, expectedLiquidity, `${label} liquidity`)
  assert.equal(trade.feeAsset, 'USDT', `${label} fee asset`)
  const feeRate = expectedLiquidity === 'MAKER'
    ? DEMO_RATES.makerFeeRate
    : DEMO_RATES.takerFeeRate
  const feeOracle = spotSellOracle({
    soldBase: String(trade.lots),
    fillPrice: String(trade.price),
    feeRate,
    averageCost: '0',
    rules
  })
  assertDecimalClose(
    trade.fee,
    feeOracle.quoteFee,
    feeOracle.tolerances.amount,
    `${label} fee`
  )
  let fillPrice = expectedPrice
  if (!fillPrice) {
    assert(executionMarket, `${label} execution market`)
    fillPrice = marketFillOracle({
      productType: 'CRYPTO_SPOT',
      side: trade.side,
      bid: quoteDecimal(executionMarket, 'bid'),
      ask: quoteDecimal(executionMarket, 'ask')
    }).filledPrice
  }
  assertDecimalClose(
    trade.price,
    fillPrice,
    tolerancesFromRules(rules).price,
    `${label} fill price`
  )

  const quantity = Number(trade.lots)
  const grossQuote = Number(trade.lots) * Number(trade.price)
  const fee = Number(trade.fee)
  const beforeBtc = findWalletOrZero(before, 'SPOT', 'BTC')
  const afterBtc = findWallet(after, 'SPOT', 'BTC')
  const beforeUsdt = findWalletOrZero(before, 'SPOT', 'USDT')
  const afterUsdt = findWallet(after, 'SPOT', 'USDT')
  const buy = trade.side === 'BUY'
  assertDecimalClose(
    Number(afterBtc.total) - Number(beforeBtc.total),
    buy ? quantity : -quantity,
    tolerancesFromRules(rules).quantity,
    `${label} BTC total delta`
  )
  assertDecimalClose(
    Number(afterBtc.available) - Number(beforeBtc.available),
    buy ? quantity : -quantity,
    tolerancesFromRules(rules).quantity,
    `${label} BTC available delta`
  )
  assertDecimalClose(
    Number(afterUsdt.total) - Number(beforeUsdt.total),
    buy ? -(grossQuote + fee) : grossQuote - fee,
    tolerancesFromRules(rules).amount,
    `${label} USDT total delta`
  )
  assertDecimalClose(
    Number(afterUsdt.available) - Number(beforeUsdt.available),
    buy ? -(grossQuote + fee) : grossQuote - fee,
    tolerancesFromRules(rules).amount,
    `${label} USDT available delta`
  )
  assertDecimalClose(afterBtc.locked, beforeBtc.locked, '0.00000001', `${label} BTC unlocked`)
  assertDecimalClose(afterUsdt.locked, beforeUsdt.locked, '0.00000001', `${label} USDT unlocked`)
  if (buy) {
    assertDecimalClose(trade.realizedPnl ?? '0', '0', '0.00000001', `${label} BUY PnL`)
  } else {
    const spot = (before.positions ?? []).find((position) => (
      position.symbol === trade.symbol
        && String(position.instrumentType ?? '').toUpperCase() === 'SPOT'
    ))
    assert(spot, `${label} Spot cost position`)
    const pnlOracle = spotSellOracle({
      soldBase: String(trade.lots),
      fillPrice: String(trade.price),
      feeRate,
      averageCost: String(spot.openPrice),
      rules
    })
    assertDecimalClose(
      trade.realizedPnl,
      pnlOracle.realizedPnl,
      pnlOracle.tolerances.amount,
      `${label} realized PnL`
    )
  }
  return {
    tradeId: trade.id,
    fillPrice: String(trade.price),
    fee: String(trade.fee),
    feeAsset: trade.feeAsset,
    realizedPnl: String(trade.realizedPnl ?? '0'),
    liquidityRole: trade.liquidityRole
  }
}

function assertSpotTradeDbLedger(db, trade, rules, label) {
  const entries = (db.assetLedgerRows ?? []).filter((entry) => (
    entry.reference_type === 'TRADE' && entry.reference_id === trade.id
  ))
  const gross = Number(trade.lots) * Number(trade.price)
  const expected = trade.side === 'BUY'
    ? {
        SPOT_BUY_DEBIT: { asset: 'USDT', amount: -gross },
        SPOT_BUY_CREDIT: { asset: 'BTC', amount: Number(trade.lots) },
        TRADE_FEE: { asset: 'USDT', amount: -Number(trade.fee) }
      }
    : {
        SPOT_SELL_DEBIT: { asset: 'BTC', amount: -Number(trade.lots) },
        SPOT_SELL_CREDIT: { asset: 'USDT', amount: gross },
        TRADE_FEE: { asset: 'USDT', amount: -Number(trade.fee) }
      }
  assert.deepEqual(
    entries.map(({ entry_type: type }) => type).toSorted(),
    Object.keys(expected).toSorted(),
    `${label} exact Trade-linked DB ledger types`
  )
  for (const entry of entries) {
    const oracle = expected[entry.entry_type]
    assert(oracle, `${label} unexpected DB ledger ${entry.entry_type}`)
    assert.equal(entry.wallet_type, 'SPOT', `${label} ${entry.entry_type} wallet`)
    assert.equal(entry.asset, oracle.asset, `${label} ${entry.entry_type} asset`)
    assertDecimalClose(
      entry.amount,
      oracle.amount,
      tolerancesFromRules(rules).amount,
      `${label} ${entry.entry_type} amount`
    )
  }
  return entries.map(({ id }) => id)
}

function assertPerpCloseFinancials({
  before,
  beforeDb,
  after,
  afterDb,
  position,
  trade,
  quantity = position.lots,
  expectClosed = true,
  rules,
  executionMarket,
  expectedLiquidity,
  expectedPrice,
  label
}) {
  assert.equal(trade.liquidityRole, expectedLiquidity, `${label} liquidity`)
  assert.equal(trade.feeAsset, 'USDT', `${label} fee asset`)
  const closingSide = ['BUY', 'LONG'].includes(position.side) ? 'SELL' : 'BUY'
  assert.equal(trade.side, closingSide, `${label} closing side`)
  assertDecimalClose(
    trade.lots,
    quantity,
    tolerancesFromRules(rules).quantity,
    `${label} close quantity`
  )
  let fillPrice = expectedPrice
  if (!fillPrice) {
    assert(executionMarket, `${label} execution market`)
    fillPrice = marketFillOracle({
      productType: 'LINEAR_PERP',
      side: closingSide,
      bid: quoteDecimal(executionMarket, 'bid'),
      ask: quoteDecimal(executionMarket, 'ask')
    }).filledPrice
  }
  assertDecimalClose(
    trade.price,
    fillPrice,
    tolerancesFromRules(rules).price,
    `${label} close fill`
  )
  const oracle = perpCloseOracle({
    side: ['BUY', 'LONG'].includes(position.side) ? 'LONG' : 'SHORT',
    quantity: String(quantity),
    entryPrice: String(position.openPrice),
    closeFillPrice: String(trade.price),
    closeFeeRate: expectedLiquidity === 'MAKER'
      ? DEMO_RATES.makerFeeRate
      : DEMO_RATES.takerFeeRate,
    rules
  })
  assertDecimalClose(
    trade.realizedPnl,
    oracle.grossRealizedPnl,
    oracle.tolerances.amount,
    `${label} realized PnL`
  )
  assertDecimalClose(trade.fee, oracle.closeFee, oracle.tolerances.amount, `${label} fee`)
  assertDecimalClose(
    Number(after.summary.balance) - Number(before.summary.balance),
    Number(oracle.grossRealizedPnl) - Number(oracle.closeFee),
    oracle.tolerances.amount,
    `${label} account balance delta`
  )
  const currentPositions = (after.positions ?? []).filter(({ id }) => id === position.id)
  const historyPositions = (after.positionHistory ?? []).filter(({ id }) => id === position.id)
  assert.equal(currentPositions.length, expectClosed ? 0 : 1, `${label} open position cardinality`)
  assert.equal(historyPositions.length, expectClosed ? 1 : 0, `${label} history cardinality`)
  const resultingPosition = expectClosed ? historyPositions[0] : currentPositions[0]
  const remainingQuantity = expectClosed
    ? '0'
    : String(Number(position.lots) - Number(quantity))
  if (!expectClosed) {
    assertDecimalClose(
      resultingPosition.lots,
      remainingQuantity,
      tolerancesFromRules(rules).quantity,
      `${label} remaining position quantity`
    )
  }
  const cumulativeRealized = Number(position.realizedPnl ?? 0)
    + Number(oracle.grossRealizedPnl)
  assertDecimalClose(
    resultingPosition.realizedPnl,
    cumulativeRealized,
    oracle.tolerances.amount,
    `${label} cumulative position realized PnL`
  )
  const remainingMargin = expectClosed ? 0 : Number(resultingPosition.marginHeld)
  const releasedMargin = Number(position.marginHeld) - remainingMargin
  const expectedLedger = [
    {
      operationType: 'MARGIN_RELEASE',
      referenceType: 'POSITION',
      referenceId: position.id,
      amount: String(releasedMargin)
    },
    {
      operationType: 'TRADE_FEE',
      referenceType: 'TRADE',
      referenceId: trade.id,
      amount: negativeDecimal(oracle.closeFee)
    }
  ]
  if (Number(oracle.grossRealizedPnl) !== 0) {
    expectedLedger.push({
      operationType: 'TRADE_PNL',
      referenceType: 'POSITION',
      referenceId: position.id,
      amount: oracle.grossRealizedPnl
    })
  }
  const ledger = assertPerpLedgerRows(beforeDb, afterDb, expectedLedger, label)

  const dbOrders = (afterDb.orderRows ?? []).filter(({ id }) => id === trade.orderId)
  assert.equal(dbOrders.length, 1, `${label} exact DB close Order`)
  assert.equal(dbOrders[0].status, 'FILLED', `${label} DB close Order status`)
  const dbTrades = (afterDb.tradeRows ?? []).filter(({ id }) => id === trade.id)
  assert.equal(dbTrades.length, 1, `${label} exact DB Trade`)
  const dbTrade = dbTrades[0]
  assert.equal(dbTrade.order_id, trade.orderId, `${label} DB Trade order`)
  assert.equal(dbTrade.side, trade.side, `${label} DB Trade side`)
  assert.equal(dbTrade.fee_asset, trade.feeAsset, `${label} DB fee asset`)
  assert.equal(dbTrade.liquidity_role, trade.liquidityRole, `${label} DB liquidity`)
  for (const [field, expected, tolerance, fieldLabel] of [
    ['lots', trade.lots, tolerancesFromRules(rules).quantity, 'quantity'],
    ['price', trade.price, tolerancesFromRules(rules).price, 'fill'],
    ['realized_pnl', trade.realizedPnl, oracle.tolerances.amount, 'realized PnL'],
    ['fee', trade.fee, oracle.tolerances.amount, 'fee']
  ]) {
    assertDecimalClose(dbTrade[field], expected, tolerance, `${label} DB ${fieldLabel}`)
  }
  const dbPositions = (afterDb.positionRows ?? []).filter(({ id }) => id === position.id)
  assert.equal(dbPositions.length, 1, `${label} exact DB position`)
  const dbPosition = dbPositions[0]
  assert.equal(dbPosition.status, expectClosed ? 'CLOSED' : 'OPEN', `${label} DB position status`)
  assertDecimalClose(
    dbPosition.lots,
    expectClosed ? position.lots : remainingQuantity,
    tolerancesFromRules(rules).quantity,
    `${label} DB position quantity`
  )
  assertDecimalClose(
    dbPosition.realized_pnl,
    cumulativeRealized,
    oracle.tolerances.amount,
    `${label} DB position realized PnL`
  )
  assertDecimalClose(
    dbPosition.margin_held,
    expectClosed ? 0 : remainingMargin,
    oracle.tolerances.amount,
    `${label} DB position margin`
  )
  assertDecimalClose(
    afterDb.accountRow?.balance,
    after.summary.balance,
    oracle.tolerances.amount,
    `${label} DB account balance`
  )
  return {
    tradeId: trade.id,
    orderId: trade.orderId,
    quantity: String(quantity),
    fillPrice: String(trade.price),
    realizedPnl: String(trade.realizedPnl),
    fee: String(trade.fee),
    ledger
  }
}

function assertPerpLedgerRows(beforeDb, afterDb, expected, label) {
  const known = new Set((beforeDb.cashLedgerRows ?? []).map(({ id }) => id))
  const added = (afterDb.cashLedgerRows ?? []).filter(({ id }) => !known.has(id))
  const types = new Set(expected.map(({ operationType }) => operationType))
  assert.equal(
    added.filter(({ operation_type: type }) => types.has(type)).length,
    expected.length,
    `${label} exact relevant cash ledger rows`
  )
  return expected.map((item) => {
    const matches = added.filter((entry) => (
      entry.operation_type === item.operationType
        && entry.reference_type === item.referenceType
        && entry.reference_id === item.referenceId
    ))
    assert.equal(matches.length, 1, `${label} exact ${item.operationType} ledger`)
    assertDecimalClose(
      matches[0].amount,
      item.amount,
      '0.00000001',
      `${label} ${item.operationType} amount`
    )
    return matches[0].id
  })
}

function assertNoCashLedgerDelta(beforeDb, afterDb, label) {
  const known = new Set((beforeDb.cashLedgerRows ?? []).map(({ id }) => id))
  const added = (afterDb.cashLedgerRows ?? []).filter(({ id }) => !known.has(id))
  assert.equal(
    added.filter(({ operation_type: type }) => (
      ['MARGIN_RELEASE', 'TRADE_FEE'].includes(type)
    )).length,
    0,
    `${label} no financial cash ledger delta`
  )
}

function negativeDecimal(value) {
  return String(value).startsWith('-') ? String(value) : `-${value}`
}

function findWalletOrZero(snapshot, walletType, asset) {
  return snapshot.wallets.find((wallet) => (
    wallet.walletType === walletType && wallet.asset === asset
  )) ?? { total: '0', available: '0', locked: '0' }
}

function assertAdminCleanupFinancials({
  before,
  beforeDb,
  after,
  afterDb,
  positions,
  items,
  markets,
  label
}) {
  const financial = positions.map((position) => {
    const item = items.find((candidate) => candidate.positionId === position.id)
    assert(item, `${label} item ${position.id}`)
    const order = after.orders.find(({ id }) => id === item.orderId)
    assert(order, `${label} close Order ${item.orderId}`)
    assert.equal(
      order.origin ?? order.orderOrigin,
      'ADMIN_FORCE_CLOSE',
      `${label} close origin ${position.id}`
    )
    const trades = after.trades.filter(({ orderId }) => orderId === item.orderId)
    assert.equal(trades.length, 1, `${label} exact Trade ${position.id}`)
    const trade = trades[0]
    const market = markets.get(position.symbol)
    assert(market, `${label} market ${position.symbol}`)
    const rules = rulesFor(market)
    const closingSide = ['BUY', 'LONG'].includes(position.side) ? 'SELL' : 'BUY'
    const pricing = marketFillOracle({
      productType: 'LINEAR_PERP',
      side: closingSide,
      bid: quoteDecimal(market, 'bid'),
      ask: quoteDecimal(market, 'ask')
    })
    assert.equal(trade.side, closingSide, `${label} closing side ${position.id}`)
    assert.equal(trade.liquidityRole, 'TAKER', `${label} liquidity ${position.id}`)
    assert.equal(trade.feeAsset, 'USDT', `${label} fee asset ${position.id}`)
    assertDecimalClose(
      trade.price,
      pricing.filledPrice,
      tolerancesFromRules(rules).price,
      `${label} fill ${position.id}`
    )
    const oracle = perpCloseOracle({
      side: ['BUY', 'LONG'].includes(position.side) ? 'LONG' : 'SHORT',
      quantity: String(position.lots),
      entryPrice: String(position.openPrice),
      closeFillPrice: String(trade.price),
      rules
    })
    assertDecimalClose(
      trade.realizedPnl,
      oracle.grossRealizedPnl,
      oracle.tolerances.amount,
      `${label} realized PnL ${position.id}`
    )
    assertDecimalClose(
      trade.fee,
      oracle.closeFee,
      oracle.tolerances.amount,
      `${label} fee ${position.id}`
    )
    assert.equal(
      (after.positionHistory ?? []).filter(({ id }) => id === position.id).length,
      1,
      `${label} history ${position.id}`
    )
    return { position, trade, oracle }
  })
  const expectedBalanceDelta = financial.reduce((sum, { oracle }) => (
    sum + Number(oracle.grossRealizedPnl) - Number(oracle.closeFee)
  ), 0)
  assertDecimalClose(
    Number(after.summary.balance) - Number(before.summary.balance),
    expectedBalanceDelta,
    '0.00000001',
    `${label} aggregate account balance delta`
  )
  const ledger = assertPerpLedgerRows(
    beforeDb,
    afterDb,
    financial.flatMap(({ position, trade, oracle }) => {
      const expected = [
        {
          operationType: 'MARGIN_RELEASE',
          referenceType: 'POSITION',
          referenceId: position.id,
          amount: String(position.marginHeld)
        },
        {
          operationType: 'TRADE_FEE',
          referenceType: 'TRADE',
          referenceId: trade.id,
          amount: negativeDecimal(oracle.closeFee)
        }
      ]
      if (Number(oracle.grossRealizedPnl) !== 0) {
        expected.push({
          operationType: 'TRADE_PNL',
          referenceType: 'POSITION',
          referenceId: position.id,
          amount: oracle.grossRealizedPnl
        })
      }
      return expected
    }),
    label
  )
  return {
    positions: financial.map(({ position, trade, oracle }) => ({
      positionId: position.id,
      orderId: trade.orderId,
      tradeId: trade.id,
      realizedPnl: oracle.grossRealizedPnl,
      fee: oracle.closeFee
    })),
    ledger
  }
}

async function assertInvalidProtectionDirections(scope, opened, positionSide) {
  const entry = Number(opened.position.openPrice)
  const baseline = await scope.context.api.snapshotAccount(scope.page)
  const baselineDb = await scope.context.db.snapshotTradingRows(baseline.account.id)
  const long = positionSide === 'LONG'
  for (const invalid of [
    {
      protectionType: 'TAKE_PROFIT',
      quantity: '0.01',
      triggerPrice: alignedPrice(opened.market, entry * (long ? 0.98 : 1.02)),
      triggerExecutionType: 'MARKET'
    },
    {
      protectionType: 'STOP_LOSS',
      quantity: '0.01',
      triggerPrice: alignedPrice(opened.market, entry * (long ? 1.02 : 0.98)),
      triggerExecutionType: 'MARKET'
    }
  ]) {
    const rejected = await createProtectionsViaUi(scope, opened.position, [invalid], {
      expectFailure: true,
      reason: `PROT-06 ${positionSide} invalid direction`
    })
    assertRejectedCode(
      rejected.capture,
      'PROTECTION_DIRECTION_INVALID',
      `PROT-06 ${positionSide} direction`
    )
    await dismissPositionActionDialog(scope.page)
  }
  const after = await scope.context.api.snapshotAccount(scope.page)
  assertTradingStateEqual(
    baseline,
    after,
    `PROT-06 ${positionSide} invalid directions zero mutation`
  )
  assertDbReplayUnchanged(
    baselineDb,
    await scope.context.db.snapshotTradingRows(after.account.id),
    `PROT-06 ${positionSide} invalid directions`
  )
  scope.contractProbes.push({
    kind: 'PROTECTION_DIRECTION_MATRIX',
    status: 'PASS',
    positionSide,
    rejectedTypes: ['TAKE_PROFIT', 'STOP_LOSS']
  })
}

async function createAuthenticatedPeer(scope, label) {
  let page
  try {
    assert(scope.browser, `${label} requires the owned browser for its second tab`)
    page = await scope.context.ui.createEvidencePage(scope.browser, {
      caseId: `${scope.definition.id}-${label}`,
      viewport: { name: 'desktop', width: 1440, height: 1000 }
    })
    const token = await scope.page.evaluate(() => localStorage.getItem('fx-platform-auth-token'))
    assert(typeof token === 'string' && token.length > 0, `${label} auth token`)
    const base = scope.page.p0Options?.webBaseUrl ?? 'http://127.0.0.1:5199'
    await page.navigate(base)
    await page.evaluate((value) => {
      localStorage.setItem('fx-platform-auth-token', value)
    }, token)
    scope.contractProbes.push({ kind: 'SAME_USER_REAL_SECOND_TAB', label })
    return { page }
  } catch (error) {
    await closeAuthenticatedPeer({ page }, false)
    throw error
  }
}

async function closeAuthenticatedPeer(peer, assertClean = true) {
  if (!peer) return
  const failures = []
  if (peer.page) {
    try {
      if (assertClean) peer.page.assertEvidenceClean('order advanced authenticated peer')
      await peer.page.close()
    } catch (error) {
      failures.push(error)
    }
  }
  if (peer.browser) {
    try {
      await peer.browser.close()
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) throw new AggregateError(failures, 'P0_PEER_CLEANUP_FAILED')
}

function countRequests(page, cursor, method, url) {
  return page.p0Evidence.requests.filter((request) => (
    request.cursor > cursor
      && request.method === method
      && (url instanceof RegExp ? url.test(request.url) : request.url === url)
  )).length
}

function midpoint(left, right) {
  const low = Number(left)
  const high = Number(right)
  assert(Number.isFinite(low) && Number.isFinite(high) && high > low, 'midpoint range')
  return String((low + high) / 2)
}

function assertDecimalClose(actual, expected, tolerance, label) {
  assert.equal(
    withinTolerance(String(actual), String(expected), String(tolerance)),
    true,
    `${label}: expected ${expected}, got ${actual}`
  )
}

function assertNear(actual, expected, label) {
  assertDecimalClose(actual, expected, '0.000001', label)
}

function assertUuid(value, label) {
  assert.match(
    String(value),
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu,
    label
  )
}

function slug(value) {
  return String(value).toLowerCase().replaceAll(/[^a-z0-9]+/g, '-').replace(/^-|-$/gu, '')
}

export const __testables = Object.freeze({
  assertAdminCleanupFinancials,
  assertBatchReleaseRows,
  assertCleanupItems,
  assertDbReplayUnchanged,
  assertPerpCloseFinancials,
  assertResetLedgerContract,
  expectedProtectionResize,
  protectionBusinessFingerprint,
  runIndependentSubruns
})
