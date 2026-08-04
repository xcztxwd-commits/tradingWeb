import assert from 'node:assert/strict'
import { setTimeout as delay } from 'node:timers/promises'

import { runSingleUserCoreCase } from './p0-user-trading-core-cases.mjs'
import {
  alignPriceToTick,
  DEMO_RATES,
  effectiveQuantityStep,
  floorToStep,
  marketFillOracle,
  perpCloseOracle,
  perpOpeningHoldOracle,
  spotOrderHoldOracle,
  tolerancesFromRules,
  walletBalanceOracle,
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

export function runSpot04(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpot04Journey)
}

export function runSpot05(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpot05Journey)
}

export function runSpot06(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpot06Journey)
}

export function runSpot07(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpot07Journey)
}

export function runPerp10(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerp10Journey)
}

export function runPerp11(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerp11Journey)
}

export const CASE_HANDLERS = Object.freeze({
  runSpot04,
  runSpot05,
  runSpot06,
  runSpot07,
  runPerp10,
  runPerp11
})

async function runSpot04Journey(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.001)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })

  const buyBaseline = await context.api.snapshotAccount(page)
  const buyPrice = alignedPrice(market, Number(quoteDecimal(market, 'bid')) * 0.5)
  const buyCursor = context.events.snapshotFrames(page).length
  const buyCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: buyPrice,
    amount: quantity
  })
  scope.addMutation('spot-pending-limit-buy-via-ui', buyCapture)
  const pendingBuy = await waitForCreatedOrder(
    scope,
    buyBaseline,
    buyCapture,
    symbol,
    'PENDING'
  )
  const buyHold = spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'LIMIT',
    baseQuantity: quantity,
    limitPrice: buyPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    buyBaseline,
    pendingBuy.snapshot,
    pendingBuy.order,
    buyHold,
    rules,
    'SPOT-04 BUY'
  )
  const buyPendingEvidence = await scope.capture('buy-pending', pendingBuy.snapshot)
  assertSpotOrderLedger(
    buyPendingEvidence.db,
    pendingBuy.order.id,
    'SPOT_ORDER_LOCK',
    negativeAmount(buyHold.amount),
    'SPOT-04 BUY lock'
  )
  const buyPendingEvents = await actionEvents(
    scope,
    buyCursor,
    ['ORDER_PENDING'],
    'SPOT-04 BUY pending'
  )

  const cancelBuyCursor = context.events.snapshotFrames(page).length
  const buyCancel = await orderActionViaUi(scope, pendingBuy.order, 'CANCEL')
  scope.addMutation('spot-pending-limit-buy-cancel-via-ui', buyCancel)
  const canceledBuy = await waitForOrderStatus(
    scope,
    pendingBuy.order.id,
    'CANCELED'
  )
  assertCanceledSpotOrder(
    buyBaseline,
    pendingBuy.snapshot,
    canceledBuy,
    pendingBuy.order,
    buyHold,
    rules,
    'SPOT-04 BUY'
  )
  const buyCanceledEvidence = await scope.capture('buy-canceled', canceledBuy)
  assertSpotOrderLedger(
    buyCanceledEvidence.db,
    pendingBuy.order.id,
    'SPOT_ORDER_RELEASE',
    buyHold.amount,
    'SPOT-04 BUY release'
  )
  const buyCancelEvents = await actionEvents(
    scope,
    cancelBuyCursor,
    ['ORDER_CANCELED'],
    'SPOT-04 BUY cancel'
  )
  scope.contractProbes.push({
    kind: 'ORDER_CANCEL_UI',
    status: 'PASS',
    orderId: pendingBuy.order.id,
    confirmationObserved: buyCancel.confirmationObserved,
    requestRef: buyCancel.requestRef
  })

  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  const beforeSeed = await context.api.snapshotAccount(page)
  const seed = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '100'
  })
  scope.addMutation('spot-btc-seed-buy-via-ui', seed)
  const seeded = await waitForFilledMutation(scope, beforeSeed, seed, symbol)
  const baseWallet = findWallet(seeded.snapshot, 'SPOT', 'BTC')
  const sellQuantity = floorToStep(
    String(baseWallet.available),
    effectiveQuantityStep(rules)
  )
  assert(Number(sellQuantity) > 0, 'SPOT-04 requires sellable BTC')
  const refreshedMarket = await context.api.snapshotMarket(symbol)
  const sellPrice = alignedPrice(
    refreshedMarket,
    Number(quoteDecimal(refreshedMarket, 'ask')) * 2
  )
  const sellBaseline = seeded.snapshot
  const sellCursor = context.events.snapshotFrames(page).length
  const sellCapture = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'LIMIT',
    price: sellPrice,
    amount: sellQuantity
  })
  scope.addMutation('spot-pending-limit-sell-via-ui', sellCapture)
  const pendingSell = await waitForCreatedOrder(
    scope,
    sellBaseline,
    sellCapture,
    symbol,
    'PENDING'
  )
  const sellHold = spotOrderHoldOracle({
    side: 'SELL',
    orderType: 'LIMIT',
    baseQuantity: sellQuantity,
    limitPrice: sellPrice,
    ask: quoteDecimal(refreshedMarket, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    sellBaseline,
    pendingSell.snapshot,
    pendingSell.order,
    sellHold,
    rules,
    'SPOT-04 SELL'
  )
  const sellPendingEvidence = await scope.capture('sell-pending', pendingSell.snapshot)
  assertSpotOrderLedger(
    sellPendingEvidence.db,
    pendingSell.order.id,
    'SPOT_ORDER_LOCK',
    negativeAmount(sellHold.amount),
    'SPOT-04 SELL lock'
  )
  const sellPendingEvents = await actionEvents(
    scope,
    sellCursor,
    ['ORDER_PENDING'],
    'SPOT-04 SELL pending'
  )

  const cancelSellCursor = context.events.snapshotFrames(page).length
  const sellCancel = await orderActionViaUi(scope, pendingSell.order, 'CANCEL')
  scope.addMutation('spot-pending-limit-sell-cancel-via-ui', sellCancel)
  const canceledSell = await waitForOrderStatus(
    scope,
    pendingSell.order.id,
    'CANCELED'
  )
  assertCanceledSpotOrder(
    sellBaseline,
    pendingSell.snapshot,
    canceledSell,
    pendingSell.order,
    sellHold,
    rules,
    'SPOT-04 SELL'
  )
  const sellCanceledEvidence = await scope.capture('sell-canceled', canceledSell)
  assertSpotOrderLedger(
    sellCanceledEvidence.db,
    pendingSell.order.id,
    'SPOT_ORDER_RELEASE',
    sellHold.amount,
    'SPOT-04 SELL release'
  )
  const sellCancelEvents = await actionEvents(
    scope,
    cancelSellCursor,
    ['ORDER_CANCELED'],
    'SPOT-04 SELL cancel'
  )

  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  const cleanup = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: sellQuantity
  })
  scope.addMutation('spot-btc-cleanup-sell-via-ui', cleanup)
  const cleaned = await waitForFilledMutation(
    scope,
    canceledSell,
    cleanup,
    symbol
  )
  assertNear(
    findWallet(cleaned.snapshot, 'SPOT', 'BTC').locked,
    0,
    'SPOT-04 cleanup BTC locked'
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_LIMIT_HOLD_CANCEL_RELEASE',
    buy: {
      orderId: pendingBuy.order.id,
      hold: buyHold,
      pendingEvents: buyPendingEvents,
      cancelEvents: buyCancelEvents
    },
    sell: {
      orderId: pendingSell.order.id,
      hold: sellHold,
      pendingEvents: sellPendingEvents,
      cancelEvents: sellCancelEvents
    }
  })
  return { finalSnapshot: cleaned.snapshot }
}

async function runSpot05Journey(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  let market = await freezeMarket(scope, symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.001)
  const modifiedQuantity = multiplyAlignedQuantity(quantity, 2, rules)
  const originalPrice = alignedPrice(market, Number(quoteDecimal(market, 'bid')) * 0.8)
  const modifiedPrice = alignedPrice(market, Number(quoteDecimal(market, 'bid')) * 0.9)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })

  const baseline = await context.api.snapshotAccount(page)
  const pendingCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: originalPrice,
    amount: quantity
  })
  scope.addMutation('spot-limit-create-via-ui', pendingCapture)
  const pending = await waitForCreatedOrder(
    scope,
    baseline,
    pendingCapture,
    symbol,
    'PENDING'
  )
  const originalHold = spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'LIMIT',
    baseQuantity: quantity,
    limitPrice: originalPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    baseline,
    pending.snapshot,
    pending.order,
    originalHold,
    rules,
    'SPOT-05 original'
  )
  await scope.capture('created', pending.snapshot)

  const modifyCursor = context.events.snapshotFrames(page).length
  const modifyCapture = await orderActionViaUi(
    scope,
    pending.order,
    'MODIFY',
    { quantity: modifiedQuantity, price: modifiedPrice }
  )
  scope.addMutation('spot-limit-modify-via-ui', modifyCapture)
  const modified = await waitForOrderStatus(
    scope,
    pending.order.id,
    'PENDING',
    (order) => (
      Number(order.quantity ?? order.lots) === Number(modifiedQuantity)
        && Number(order.price) === Number(modifiedPrice)
        && Number(order.version) > Number(pending.order.version)
    )
  )
  modified.order = orderById(modified, pending.order.id)
  assert.equal(
    modified.order.id,
    pending.order.id
  )
  assert(
    Number(modified.order.version) > Number(pending.order.version),
    'SPOT-05 version must increase'
  )
  const modifiedHold = spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'LIMIT',
    baseQuantity: modifiedQuantity,
    limitPrice: modifiedPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    baseline,
    modified,
    modified.order,
    modifiedHold,
    rules,
    'SPOT-05 modified'
  )
  const modifiedEvidence = await scope.capture('modified', modified)
  assertLedgerSum(
    recordsAfter(scope.beforeDb, modifiedEvidence.db, 'assetLedgerRows')
      .filter(({ entry_type: type }) => type === 'SPOT_ORDER_LOCK'),
    negativeAmount(modifiedHold.amount),
    'SPOT-05 exact replacement hold'
  )
  const modifiedEvents = await actionEvents(
    scope,
    modifyCursor,
    ['ORDER_MODIFIED'],
    'SPOT-05 modify'
  )

  const fillCursor = context.events.snapshotFrames(page).length
  await setMarketAround(scope, symbol, Number(modifiedPrice) * 0.98)
  market = await context.api.snapshotMarket(symbol)
  assert(
    Number(quoteDecimal(market, 'ask')) <= Number(modifiedPrice),
    'SPOT-05 authority ask must cross the modified limit'
  )
  const filled = await waitForExistingFilledOrder(
    scope,
    modified,
    pending.order.id
  )
  assert.equal(filled.order.id, pending.order.id)
  assert.equal(filled.order.status, 'FILLED')
  assert.equal(filled.order.filledQuantity, filled.order.quantity)
  assertNear(filled.order.remainingQuantity, 0, 'SPOT-05 remaining quantity')
  assert.equal(filled.trade.liquidityRole, 'MAKER')
  assert.equal(filled.trade.feeAsset, 'USDT')
  assertDecimalClose(
    filled.trade.fee,
    Number(filled.trade.lots)
      * Number(filled.trade.price)
      * Number(DEMO_RATES.makerFeeRate),
    tolerancesFromRules(rules).amount,
    'SPOT-05 maker fee'
  )
  assert.equal(
    filled.snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    1,
    'SPOT-05 exact full fill'
  )
  assert.equal(
    filled.snapshot.orders.some(({ status }) => status === 'PARTIALLY_FILLED'),
    false,
    'SPOT-05 no partial fill'
  )
  const filledEvidence = await scope.capture('filled', filled.snapshot)
  const tradeLedger = assertSpotTradeLedger(
    filledEvidence.db,
    filled.trade,
    rules,
    'SPOT-05'
  )
  assertOrderEventCount(
    filledEvidence.db,
    pending.order.id,
    'ORDER_FILLED',
    1,
    'SPOT-05 full-fill event'
  )
  const fillEvents = await actionEvents(
    scope,
    fillCursor,
    ['ORDER_FILLED', 'TRADE_CREATED'],
    'SPOT-05 fill'
  )

  const baseWallet = findWallet(filled.snapshot, 'SPOT', 'BTC')
  const cleanupQuantity = floorToStep(
    String(baseWallet.available),
    effectiveQuantityStep(rules)
  )
  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  const cleanup = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: cleanupQuantity
  })
  scope.addMutation('spot-maker-fill-cleanup-via-ui', cleanup)
  const cleaned = await waitForFilledMutation(
    scope,
    filled.snapshot,
    cleanup,
    symbol
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_LIMIT_MODIFY_MAKER_FILL',
    orderId: pending.order.id,
    version: {
      before: pending.order.version,
      after: modified.order.version
    },
    hold: { before: originalHold, after: modifiedHold },
    modifiedEvents,
    fillEvents,
    tradeLedger
  })
  return { finalSnapshot: cleaned.snapshot }
}

async function runSpot06Journey(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  let market = await freezeMarket(scope, symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.001)
  const initialLast = lastPrice(market)
  const triggerPrice = alignedPrice(market, initialLast * 1.02)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })

  const baseline = await context.api.snapshotAccount(page)
  const pendingCursor = context.events.snapshotFrames(page).length
  const capture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'STOP_MARKET',
    triggerPrice,
    amount: quantity
  })
  scope.addMutation('spot-stop-market-buy-via-ui', capture)
  const pending = await waitForCreatedOrder(
    scope,
    baseline,
    capture,
    symbol,
    'PENDING'
  )
  assert.equal(pending.order.triggerPriceType, 'LAST_PRICE')
  assert.equal(pending.order.triggerPrice, triggerPrice)
  const hold = spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'STOP_MARKET',
    baseQuantity: quantity,
    stopTriggerPrice: triggerPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    baseline,
    pending.snapshot,
    pending.order,
    hold,
    rules,
    'SPOT-06'
  )
  const pendingEvidence = await scope.capture('pending', pending.snapshot)
  assertSpotOrderLedger(
    pendingEvidence.db,
    pending.order.id,
    'SPOT_ORDER_LOCK',
    negativeAmount(hold.amount),
    'SPOT-06 lock'
  )
  const pendingEvents = await actionEvents(
    scope,
    pendingCursor,
    ['ORDER_PENDING'],
    'SPOT-06 pending'
  )

  await setMarketAround(scope, symbol, Number(triggerPrice) * 0.99)
  market = await context.api.snapshotMarket(symbol)
  assert(lastPrice(market) < Number(triggerPrice), 'SPOT-06 pre-trigger last')
  const beforeTrigger = await observeOrderPending(
    scope,
    pending.order.id,
    1200
  )
  assert.equal(
    beforeTrigger.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    0,
    'SPOT-06 no pre-trigger Trade'
  )
  await scope.capture('below-trigger', beforeTrigger)

  const fillCursor = context.events.snapshotFrames(page).length
  await setMarketAround(scope, symbol, Number(triggerPrice) * 1.01)
  const triggeredMarket = await context.api.snapshotMarket(symbol)
  assert(lastPrice(triggeredMarket) >= Number(triggerPrice), 'SPOT-06 crossed last')
  const filled = await waitForExistingFilledOrder(
    scope,
    beforeTrigger,
    pending.order.id
  )
  const pricing = marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'BUY',
    bid: quoteDecimal(triggeredMarket, 'bid'),
    ask: quoteDecimal(triggeredMarket, 'ask')
  })
  assertDecimalClose(
    filled.trade.price,
    pricing.filledPrice,
    tolerancesFromRules(rules).price,
    'SPOT-06 trigger fill'
  )
  assert.notEqual(Number(filled.trade.price), Number(triggerPrice))
  assert.equal(filled.trade.liquidityRole, 'TAKER')
  assert.equal(filled.trade.feeAsset, 'USDT')
  assertDecimalClose(
    filled.trade.fee,
    Number(filled.trade.lots)
      * Number(filled.trade.price)
      * Number(DEMO_RATES.takerFeeRate),
    tolerancesFromRules(rules).amount,
    'SPOT-06 quote fee'
  )
  assert.equal(
    filled.snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    1,
    'SPOT-06 exact triggered Trade'
  )
  assertNear(
    findWallet(filled.snapshot, 'SPOT', 'USDT').locked,
    0,
    'SPOT-06 unused quote hold released'
  )
  const filledEvidence = await scope.capture('filled', filled.snapshot)
  const tradeLedger = assertSpotTradeLedger(
    filledEvidence.db,
    filled.trade,
    rules,
    'SPOT-06'
  )
  const fillEvents = await actionEvents(
    scope,
    fillCursor,
    ['ORDER_FILLED', 'TRADE_CREATED'],
    'SPOT-06 fill'
  )

  const cleanupQuantity = floorToStep(
    String(findWallet(filled.snapshot, 'SPOT', 'BTC').available),
    effectiveQuantityStep(rules)
  )
  await context.ui.openTradePanel(page, { product: 'spot', symbol })
  const cleanup = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: cleanupQuantity
  })
  scope.addMutation('spot-stop-buy-cleanup-via-ui', cleanup)
  const cleaned = await waitForFilledMutation(
    scope,
    filled.snapshot,
    cleanup,
    symbol
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_STOP_MARKET_BUY',
    orderId: pending.order.id,
    triggerPriceType: 'LAST_PRICE',
    hold,
    pricing,
    pendingEvents,
    fillEvents,
    tradeLedger
  })
  return { finalSnapshot: cleaned.snapshot }
}

async function runSpot07Journey(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT'
  let market = await freezeMarket(scope, symbol)
  const rules = rulesFor(market)
  await context.ui.openTradePanel(page, { product: 'spot', symbol })

  const beforeSeed = await context.api.snapshotAccount(page)
  const seedCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '100'
  })
  scope.addMutation('spot-stop-sell-seed-via-ui', seedCapture)
  const seeded = await waitForFilledMutation(
    scope,
    beforeSeed,
    seedCapture,
    symbol
  )
  const baseBefore = findWallet(seeded.snapshot, 'SPOT', 'BTC')
  const quoteBefore = findWallet(seeded.snapshot, 'SPOT', 'USDT')
  const quantity = floorToStep(
    String(baseBefore.available),
    effectiveQuantityStep(rules)
  )
  const spotPosition = seeded.snapshot.positions.find((position) => (
    String(position.instrumentType).toUpperCase() === 'SPOT'
      && position.symbol === symbol
  ))
  assert(spotPosition, 'SPOT-07 seeded Spot position')
  const triggerPrice = alignedPrice(market, lastPrice(market) * 0.98)

  const pendingCursor = context.events.snapshotFrames(page).length
  const stopCapture = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'STOP_MARKET',
    triggerPrice,
    amount: quantity
  })
  scope.addMutation('spot-stop-market-sell-via-ui', stopCapture)
  const pending = await waitForCreatedOrder(
    scope,
    seeded.snapshot,
    stopCapture,
    symbol,
    'PENDING'
  )
  assert.equal(pending.order.triggerPriceType, 'LAST_PRICE')
  const hold = spotOrderHoldOracle({
    side: 'SELL',
    orderType: 'STOP_MARKET',
    baseQuantity: quantity,
    stopTriggerPrice: triggerPrice,
    ask: quoteDecimal(market, 'ask'),
    baseAsset: 'BTC'
  })
  assertSpotPendingWallet(
    seeded.snapshot,
    pending.snapshot,
    pending.order,
    hold,
    rules,
    'SPOT-07'
  )
  const pendingEvidence = await scope.capture('pending', pending.snapshot)
  assertSpotOrderLedger(
    pendingEvidence.db,
    pending.order.id,
    'SPOT_ORDER_LOCK',
    negativeAmount(hold.amount),
    'SPOT-07 lock'
  )
  const pendingEvents = await actionEvents(
    scope,
    pendingCursor,
    ['ORDER_PENDING'],
    'SPOT-07 pending'
  )

  await setMarketAround(scope, symbol, Number(triggerPrice) * 1.01)
  market = await context.api.snapshotMarket(symbol)
  assert(lastPrice(market) > Number(triggerPrice), 'SPOT-07 pre-trigger last')
  const aboveTrigger = await observeOrderPending(
    scope,
    pending.order.id,
    1200
  )
  assert.equal(
    aboveTrigger.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    0,
    'SPOT-07 no pre-trigger Trade'
  )
  await scope.capture('above-trigger', aboveTrigger)

  const fillCursor = context.events.snapshotFrames(page).length
  await setMarketAround(scope, symbol, Number(triggerPrice) * 0.99)
  const triggeredMarket = await context.api.snapshotMarket(symbol)
  assert(lastPrice(triggeredMarket) <= Number(triggerPrice), 'SPOT-07 crossed last')
  const filled = await waitForExistingFilledOrder(
    scope,
    aboveTrigger,
    pending.order.id
  )
  const pricing = marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'SELL',
    bid: quoteDecimal(triggeredMarket, 'bid'),
    ask: quoteDecimal(triggeredMarket, 'ask')
  })
  assertDecimalClose(
    filled.trade.price,
    pricing.filledPrice,
    tolerancesFromRules(rules).price,
    'SPOT-07 trigger fill'
  )
  assert.equal(filled.trade.liquidityRole, 'TAKER')
  assert.equal(filled.trade.feeAsset, 'USDT')
  const grossQuote = Number(filled.trade.lots) * Number(filled.trade.price)
  const quoteFee = grossQuote * Number(DEMO_RATES.takerFeeRate)
  const realizedPnl = (
    Number(filled.trade.price) - Number(spotPosition.openPrice)
  ) * Number(filled.trade.lots) - quoteFee
  assertDecimalClose(
    filled.trade.fee,
    quoteFee,
    tolerancesFromRules(rules).amount,
    'SPOT-07 quote fee'
  )
  assertDecimalClose(
    filled.trade.realizedPnl,
    realizedPnl,
    tolerancesFromRules(rules).amount,
    'SPOT-07 realized PnL'
  )
  assertDecimalClose(
    Number(findWallet(filled.snapshot, 'SPOT', 'USDT').total) - Number(quoteBefore.total),
    grossQuote - quoteFee,
    tolerancesFromRules(rules).amount,
    'SPOT-07 quote wallet delta'
  )
  assertDecimalClose(
    Number(baseBefore.total) - Number(findWallet(filled.snapshot, 'SPOT', 'BTC').total),
    quantity,
    tolerancesFromRules(rules).quantity,
    'SPOT-07 sold base quantity'
  )
  assertNear(
    findWallet(filled.snapshot, 'SPOT', 'BTC').locked,
    0,
    'SPOT-07 base hold released'
  )
  assert.equal(
    filled.snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    1,
    'SPOT-07 exact triggered Trade'
  )
  const filledEvidence = await scope.capture('filled', filled.snapshot)
  const tradeLedger = assertSpotTradeLedger(
    filledEvidence.db,
    filled.trade,
    rules,
    'SPOT-07'
  )
  const fillEvents = await actionEvents(
    scope,
    fillCursor,
    ['ORDER_FILLED', 'TRADE_CREATED'],
    'SPOT-07 fill'
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_STOP_MARKET_SELL',
    orderId: pending.order.id,
    triggerPriceType: 'LAST_PRICE',
    hold,
    pricing,
    grossQuote,
    quoteFee,
    realizedPnl,
    pendingEvents,
    fillEvents,
    tradeLedger
  })
  return { finalSnapshot: filled.snapshot, finalDb: filledEvidence.db }
}

async function runPerp10Journey(scope) {
  const { context, definition, page } = scope
  const symbol = 'BTCUSDT-PERP'
  let market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })

  const immediateBefore = await context.api.snapshotAccount(page)
  const immediateCursor = context.events.snapshotFrames(page).length
  const immediateCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: quoteDecimal(market, 'ask'),
    amount: quantity
  })
  scope.addMutation('perp-marketable-limit-buy-via-ui', immediateCapture)
  const immediate = await waitForFilledMutation(
    scope,
    immediateBefore,
    immediateCapture,
    symbol
  )
  const immediatePosition = openPositions(immediate.snapshot, symbol).at(0)
  assert(immediatePosition, 'PERP-10 immediate position')
  const immediateOracle = assertPerpOpeningFill({
    before: immediateBefore,
    filled: immediate,
    position: immediatePosition,
    rules,
    leverage: 10,
    liquidityRole: 'TAKER',
    label: 'PERP-10 immediate'
  })
  const immediateEvidence = await scope.capture(
    'immediate-filled',
    immediate.snapshot
  )
  const immediateLedger = assertPerpOpeningLedger(
    scope.beforeDb,
    immediateEvidence.db,
    immediatePosition,
    immediate.trade,
    immediateOracle,
    null,
    'PERP-10 immediate'
  )
  const immediateEvents = await actionEvents(
    scope,
    immediateCursor,
    ['ORDER_FILLED', 'TRADE_CREATED'],
    'PERP-10 immediate fill'
  )
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  const immediateClosed = await closePerpPosition(
    scope,
    immediate.snapshot,
    immediatePosition,
    rules,
    'PERP-10 immediate cleanup'
  )
  await scope.capture('immediate-closed', immediateClosed.snapshot)

  market = await context.api.snapshotMarket(symbol)
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  const pendingBefore = immediateClosed.snapshot
  const pendingPrice = alignedPrice(
    market,
    Number(quoteDecimal(market, 'bid')) * 0.5
  )
  const pendingCursor = context.events.snapshotFrames(page).length
  const pendingCapture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: pendingPrice,
    amount: quantity
  })
  scope.addMutation('perp-pending-limit-buy-via-ui', pendingCapture)
  const pending = await waitForCreatedOrder(
    scope,
    pendingBefore,
    pendingCapture,
    symbol,
    'PENDING'
  )
  const pendingHold = perpOpeningHoldOracle({
    baseQuantity: quantity,
    worstPrice: pendingPrice,
    leverage: '10',
    rules
  })
  assertPerpPendingHold(
    pendingBefore,
    pending.snapshot,
    pending.order,
    pendingHold,
    'PERP-10 pending'
  )
  const pendingEvidence = await scope.capture('pending', pending.snapshot)
  assertCashLedger(
    pendingEvidence.db,
    'ORDER_HOLD',
    'ORDER',
    pending.order.id,
    pendingHold.holdAmount,
    'PERP-10 pending hold'
  )
  const pendingEvents = await actionEvents(
    scope,
    pendingCursor,
    ['ORDER_PENDING'],
    'PERP-10 pending'
  )

  const beforeModifyProbe = await context.api.snapshotAccount(page)
  const modifyProbe = await probeOrderModifyDisabled(scope, pending.order)
  assert.equal(modifyProbe.requestSent, false)
  assert.equal(modifyProbe.disabled, true)
  const afterModifyProbe = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    beforeModifyProbe,
    afterModifyProbe,
    'PERP-10 ORDER_NOT_MODIFIABLE UI no mutation'
  )
  scope.contractProbes.push({
    kind: 'ORDER_NOT_MODIFIABLE',
    status: 'PASS',
    orderId: pending.order.id,
    uiDisabled: modifyProbe.disabled,
    requestSent: modifyProbe.requestSent
  })

  const cancelCursor = context.events.snapshotFrames(page).length
  const cancelCapture = await orderActionViaUi(scope, pending.order, 'CANCEL')
  scope.addMutation('perp-pending-limit-cancel-via-ui', cancelCapture)
  const canceled = await waitForOrderStatus(scope, pending.order.id, 'CANCELED')
  assertPerpPendingReleased(
    pendingBefore,
    canceled,
    pending.order,
    'PERP-10 canceled'
  )
  const canceledEvidence = await scope.capture('canceled', canceled)
  assertCashLedger(
    canceledEvidence.db,
    'ORDER_RELEASE',
    'ORDER',
    pending.order.id,
    pendingHold.holdAmount,
    'PERP-10 pending release'
  )
  const cancelEvents = await actionEvents(
    scope,
    cancelCursor,
    ['ORDER_CANCELED'],
    'PERP-10 cancel'
  )

  let finalSnapshot = canceled
  let triggerEvidence = null
  if (definition.requiredSubruns.some(({ id }) => id === 'desktop-trigger')) {
    market = await freezeMarket(scope, symbol)
    await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
    const triggerBefore = await context.api.snapshotAccount(page)
    const triggerPrice = alignedPrice(
      market,
      Number(quoteDecimal(market, 'bid')) * 0.9
    )
    const triggerPendingCapture = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'LIMIT',
      price: triggerPrice,
      amount: quantity
    })
    scope.addMutation('perp-resting-limit-buy-via-ui', triggerPendingCapture)
    const triggerPending = await waitForCreatedOrder(
      scope,
      triggerBefore,
      triggerPendingCapture,
      symbol,
      'PENDING'
    )
    const triggerHold = perpOpeningHoldOracle({
      baseQuantity: quantity,
      worstPrice: triggerPrice,
      leverage: '10',
      rules
    })
    assertPerpPendingHold(
      triggerBefore,
      triggerPending.snapshot,
      triggerPending.order,
      triggerHold,
      'PERP-10 trigger pending'
    )
    const triggerPendingEvidence = await scope.capture(
      'trigger-pending',
      triggerPending.snapshot
    )
    assertCashLedger(
      triggerPendingEvidence.db,
      'ORDER_HOLD',
      'ORDER',
      triggerPending.order.id,
      triggerHold.holdAmount,
      'PERP-10 trigger hold'
    )

    const triggerCursor = context.events.snapshotFrames(page).length
    await setMarketAround(scope, symbol, Number(triggerPrice) * 0.98)
    const triggeredMarket = await context.api.snapshotMarket(symbol)
    assert(
      Number(quoteDecimal(triggeredMarket, 'ask')) <= Number(triggerPrice),
      'PERP-10 authority ask must cross the resting limit'
    )
    const triggered = await waitForExistingFilledOrder(
      scope,
      triggerPending.snapshot,
      triggerPending.order.id
    )
    const triggeredPosition = openPositions(triggered.snapshot, symbol).at(0)
    assert(triggeredPosition, 'PERP-10 triggered position')
    assert.equal(triggered.trade.liquidityRole, 'MAKER')
    const triggeredOracle = assertPerpOpeningFill({
      before: triggerBefore,
      filled: triggered,
      position: triggeredPosition,
      rules,
      leverage: 10,
      liquidityRole: 'MAKER',
      label: 'PERP-10 triggered'
    })
    assert.equal(
      triggered.snapshot.trades.filter(
        ({ orderId }) => orderId === triggerPending.order.id
      ).length,
      1,
      'PERP-10 one triggered Trade'
    )
    const triggeredDbEvidence = await scope.capture(
      'trigger-filled',
      triggered.snapshot
    )
    const triggeredLedger = assertPerpOpeningLedger(
      triggerPendingEvidence.db,
      triggeredDbEvidence.db,
      triggeredPosition,
      triggered.trade,
      triggeredOracle,
      {
        orderId: triggerPending.order.id,
        holdAmount: triggerHold.holdAmount
      },
      'PERP-10 triggered'
    )
    const triggerEvents = await actionEvents(
      scope,
      triggerCursor,
      ['ORDER_FILLED', 'TRADE_CREATED'],
      'PERP-10 triggered fill'
    )
    await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
    const triggerClosed = await closePerpPosition(
      scope,
      triggered.snapshot,
      triggeredPosition,
      rules,
      'PERP-10 trigger cleanup'
    )
    const triggerClosedEvidence = await scope.capture(
      'trigger-closed',
      triggerClosed.snapshot
    )
    finalSnapshot = triggerClosed.snapshot
    triggerEvidence = {
      orderId: triggerPending.order.id,
      hold: triggerHold,
      opening: triggeredOracle,
      openingLedger: triggeredLedger,
      close: triggerClosed.oracle,
      events: triggerEvents,
      finalDb: triggerClosedEvidence.db
    }
  }

  scope.oracleEvidence.push({
    kind: 'PERP_LIMIT_LIFECYCLE',
    immediate: {
      orderId: immediate.order.id,
      opening: immediateOracle,
      ledger: immediateLedger,
      close: immediateClosed.oracle,
      events: immediateEvents
    },
    pending: {
      orderId: pending.order.id,
      hold: pendingHold,
      pendingEvents,
      cancelEvents
    },
    trigger: triggerEvidence
  })
  return { finalSnapshot }
}

async function runPerp11Journey(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  await freezeMarket(scope, symbol)
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })

  const long = await runPerpStopDirection(scope, {
    id: 'long-stop',
    openingSide: 'BUY',
    closingSide: 'SELL',
    positionSide: 'LONG',
    triggerMultiplier: 0.98,
    crossMultiplier: 0.99
  })
  await freezeMarket(scope, symbol)
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  const short = await runPerpStopDirection(scope, {
    id: 'short-stop',
    openingSide: 'SELL',
    closingSide: 'BUY',
    positionSide: 'SHORT',
    triggerMultiplier: 1.02,
    crossMultiplier: 1.01
  })
  scope.oracleEvidence.push({
    kind: 'PERP_REDUCE_ONLY_STOP_MARKET',
    triggerPriceType: 'MARK_PRICE',
    long,
    short
  })
  return { finalSnapshot: short.finalSnapshot, finalDb: short.finalDb }
}

async function runPerpStopDirection(scope, options) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  let market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
  const beforeOpen = await context.api.snapshotAccount(page)
  const beforeOpenDb = await context.db.snapshotTradingRows(beforeOpen.account.id)
  const openCapture = await context.ui.submitOrderViaUi(page, {
    side: options.openingSide,
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation(`perp-${options.id}-open-via-ui`, openCapture)
  const opened = await waitForFilledMutation(
    scope,
    beforeOpen,
    openCapture,
    symbol
  )
  const position = openPositions(opened.snapshot, symbol).at(0)
  assert(position, `PERP-11 ${options.id} position`)
  assert.equal(position.side, options.openingSide)
  const openingOracle = assertPerpOpeningFill({
    before: beforeOpen,
    filled: opened,
    position,
    rules,
    leverage: 10,
    liquidityRole: 'TAKER',
    label: `PERP-11 ${options.id} opening`
  })
  const openedEvidence = await scope.capture(`${options.id}-opened`, opened.snapshot)
  const openingLedger = assertPerpOpeningLedger(
    beforeOpenDb,
    openedEvidence.db,
    position,
    opened.trade,
    openingOracle,
    null,
    `PERP-11 ${options.id} opening`
  )

  market = await context.api.snapshotMarket(symbol)
  const mark = markPrice(market)
  const triggerPrice = alignedPrice(
    market,
    mark * options.triggerMultiplier
  )
  const pendingCursor = context.events.snapshotFrames(page).length
  const stopCapture = await context.ui.submitOrderViaUi(page, {
    side: options.closingSide,
    orderType: 'STOP_MARKET',
    triggerPrice,
    amount: quantity,
    reduceOnly: true
  })
  scope.addMutation(`perp-${options.id}-stop-via-ui`, stopCapture)
  const request = parsedRequest(stopCapture)
  assert.equal(request.reduceOnly, true)
  assert.equal(request.triggerPriceType, 'MARK_PRICE')
  assert.equal(request.orderType, 'STOP_MARKET')
  const pending = await waitForCreatedOrder(
    scope,
    opened.snapshot,
    stopCapture,
    symbol,
    'PENDING'
  )
  assert.equal(pending.order.triggerPriceType, 'MARK_PRICE')
  assert.equal(pending.order.reduceOnly, true)
  assert.equal(pending.order.side, options.closingSide)
  assertDecimalClose(
    pending.order.triggerPrice,
    triggerPrice,
    tolerancesFromRules(rules).price,
    `PERP-11 ${options.id} trigger`
  )
  const pendingEvidence = await scope.capture(
    `${options.id}-pending`,
    pending.snapshot
  )
  const pendingRow = pendingEvidence.db.orderRows.find(
    ({ id }) => id === pending.order.id
  )
  assert(pendingRow, `PERP-11 ${options.id} DB order`)
  assert.equal(pendingRow.trigger_price_type, 'MARK_PRICE')
  assert.equal(pendingRow.reduce_only, true)
  const pendingEvents = await actionEvents(
    scope,
    pendingCursor,
    ['ORDER_PENDING'],
    `PERP-11 ${options.id} pending`
  )

  const fillCursor = context.events.snapshotFrames(page).length
  await setMarketAround(
    scope,
    symbol,
    Number(triggerPrice) * options.crossMultiplier
  )
  const triggeredMarket = await context.api.snapshotMarket(symbol)
  if (options.positionSide === 'LONG') {
    assert(
      markPrice(triggeredMarket) <= Number(triggerPrice),
      'PERP-11 LONG mark crossed down'
    )
  } else {
    assert(
      markPrice(triggeredMarket) >= Number(triggerPrice),
      'PERP-11 SHORT mark crossed up'
    )
  }
  const filled = await waitForExistingFilledOrder(
    scope,
    pending.snapshot,
    pending.order.id
  )
  assert.equal(openPositions(filled.snapshot, symbol).length, 0)
  assert.equal(
    filled.snapshot.trades.filter(({ orderId }) => orderId === pending.order.id).length,
    1,
    `PERP-11 ${options.id} one closing Trade`
  )
  assert.equal(filled.trade.liquidityRole, 'TAKER')
  const pricing = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: options.closingSide,
    bid: quoteDecimal(triggeredMarket, 'bid'),
    ask: quoteDecimal(triggeredMarket, 'ask')
  })
  assertDecimalClose(
    filled.trade.price,
    pricing.filledPrice,
    tolerancesFromRules(rules).price,
    `PERP-11 ${options.id} fill`
  )
  const closeOracle = perpCloseOracle({
    side: options.positionSide,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closeFillPrice: String(filled.trade.price),
    rules
  })
  assertDecimalClose(
    filled.trade.realizedPnl,
    closeOracle.grossRealizedPnl,
    closeOracle.tolerances.amount,
    `PERP-11 ${options.id} realized PnL`
  )
  assertDecimalClose(
    filled.trade.fee,
    closeOracle.closeFee,
    closeOracle.tolerances.amount,
    `PERP-11 ${options.id} close fee`
  )
  const history = filled.snapshot.positionHistory.filter(
    ({ id }) => id === position.id
  )
  assert.equal(history.length, 1, `PERP-11 ${options.id} one history row`)
  assert.equal(history[0].status, 'CLOSED')
  assertDecimalClose(
    history[0].realizedPnl,
    closeOracle.grossRealizedPnl,
    closeOracle.tolerances.amount,
    `PERP-11 ${options.id} history realized PnL`
  )
  assertDecimalClose(
    Number(filled.snapshot.summary.balance) - Number(beforeOpen.summary.balance),
    Number(closeOracle.grossRealizedPnl)
      - Number(openingOracle.feeBuffer)
      - Number(closeOracle.closeFee),
    closeOracle.tolerances.amount,
    `PERP-11 ${options.id} balance delta`
  )
  assertNear(filled.snapshot.summary.usedMargin, 0, `PERP-11 ${options.id} margin`)
  assertDecimalClose(
    filled.snapshot.summary.freeMargin,
    filled.snapshot.summary.balance,
    '0.00000001',
    `PERP-11 ${options.id} free margin`
  )
  const filledEvidence = await scope.capture(`${options.id}-filled`, filled.snapshot)
  assertCashLedger(
    filledEvidence.db,
    'MARGIN_RELEASE',
    'POSITION',
    position.id,
    position.marginHeld,
    `PERP-11 ${options.id} margin release`
  )
  assertCashLedger(
    filledEvidence.db,
    'TRADE_FEE',
    'TRADE',
    filled.trade.id,
    negativeAmount(closeOracle.closeFee),
    `PERP-11 ${options.id} close fee ledger`
  )
  const fillEvents = await actionEvents(
    scope,
    fillCursor,
    ['ORDER_FILLED', 'TRADE_CREATED', 'POSITION_CLOSED'],
    `PERP-11 ${options.id} fill`
  )
  return {
    orderId: pending.order.id,
    positionId: position.id,
    opening: openingOracle,
    openingLedger,
    triggerPrice,
    triggerPriceType: 'MARK_PRICE',
    pricing,
    close: closeOracle,
    pendingEvents,
    fillEvents,
    finalSnapshot: filled.snapshot,
    finalDb: filledEvidence.db
  }
}

async function orderActionViaUi(scope, order, action, fields = {}) {
  const { context, page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/orders`)
  await page.waitForFunction(
    () => window.location.pathname === '/orders',
    `${scope.definition.id} orders route`
  )
  await page.waitForFunction((orderId, expectedAction) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    const buttons = [...(row?.querySelectorAll('button') ?? [])]
    const button = expectedAction === 'MODIFY' ? buttons[1] : buttons.at(-1)
    return button instanceof HTMLButtonElement && !button.disabled
  }, `${scope.definition.id} ${action} order action`, order.id, action)

  if (action === 'MODIFY') {
    await page.evaluate((orderId) => {
      const row = document.querySelector(`[data-order-id="${orderId}"]`)
      const button = row?.querySelectorAll('button')[1]
      if (!(button instanceof HTMLButtonElement) || button.disabled) {
        throw new Error('P0_ORDER_MODIFY_ACTION_MISSING')
      }
      button.click()
    }, order.id)
    await page.waitForFunction(
      () => Boolean(document.querySelector(
        'section[aria-label] form button[type="submit"]'
      )),
      `${scope.definition.id} order modify form`
    )
    const capture = await context.ui.withCapturedMutation(
      page,
      {
        method: 'PATCH',
        url: new RegExp(`/api/trading/orders/${order.id}$`)
      },
      () => page.evaluate((values) => {
        const submit = document.querySelector(
          'section[aria-label] form button[type="submit"]'
        )
        const form = submit?.form
        const inputs = [...(form?.querySelectorAll('input') ?? [])]
        if (!(submit instanceof HTMLButtonElement) || inputs.length < 2) {
          throw new Error('P0_ORDER_MODIFY_FORM_INCOMPLETE')
        }
        const setter = Object.getOwnPropertyDescriptor(
          HTMLInputElement.prototype,
          'value'
        )?.set
        for (const [input, value] of [
          [inputs[0], values.quantity],
          [inputs[1], values.price]
        ]) {
          if (!(input instanceof HTMLInputElement)) {
            throw new Error('P0_ORDER_MODIFY_INPUT_MISSING')
          }
          setter?.call(input, String(value))
          input.dispatchEvent(new Event('input', { bubbles: true }))
          input.dispatchEvent(new Event('change', { bubbles: true }))
        }
        submit.click()
        return true
      }, fields)
    )
    assertSuccess(capture, `${scope.definition.id} modify`)
    return capture
  }

  await page.evaluate((orderId) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    const button = [...(row?.querySelectorAll('button') ?? [])].at(-1)
    if (!(button instanceof HTMLButtonElement) || button.disabled) {
      throw new Error('P0_ORDER_CANCEL_ACTION_MISSING')
    }
    button.click()
  }, order.id)
  await page.waitForFunction(
    () => Boolean(document.querySelector(
      '[role="dialog"][aria-modal="true"][aria-labelledby="order-cancel-title"]'
    )),
    `${scope.definition.id} order cancel dialog`
  )
  const capture = await context.ui.withCapturedMutation(
    page,
    {
      method: 'DELETE',
      url: new RegExp(`/api/trading/orders/${order.id}/cancel$`)
    },
    () => page.evaluate(() => {
      const dialog = document.querySelector(
        '[role="dialog"][aria-modal="true"][aria-labelledby="order-cancel-title"]'
      )
      const confirm = dialog?.querySelector('button[type="button"]')
      if (!(confirm instanceof HTMLButtonElement) || confirm.disabled) {
        throw new Error('P0_ORDER_CANCEL_CONFIRM_MISSING')
      }
      confirm.click()
      return true
    })
  )
  assertSuccess(capture, `${scope.definition.id} cancel`)
  return { ...capture, confirmationObserved: true }
}

async function probeOrderModifyDisabled(scope, order) {
  const { page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/orders`)
  await page.waitForFunction(
    () => window.location.pathname === '/orders',
    `${scope.definition.id} orders route`
  )
  await page.waitForFunction((orderId) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    return row?.getAttribute('data-order-status') === 'PENDING'
      && row.querySelectorAll('button').length >= 3
  }, `${scope.definition.id} pending order row`, order.id)
  const cursor = page.p0Evidence.cursor
  const disabled = await page.evaluate((orderId) => {
    const row = document.querySelector(`[data-order-id="${orderId}"]`)
    const modify = row?.querySelectorAll('button')[1]
    if (!(modify instanceof HTMLButtonElement)) {
      throw new Error('P0_ORDER_MODIFY_ACTION_MISSING')
    }
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
  const spread = Number(quoteDecimal(current, 'ask'))
    - Number(quoteDecimal(current, 'bid'))
  const halfSpread = Math.max(spread / 2, tick)
  const bid = alignedPrice(current, Number(target) - halfSpread)
  const ask = alignedPrice(current, Number(target) + halfSpread)
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
  return waitForMarket(
    scope.context,
    symbol,
    `${scope.definition.id} ${symbol} authority bundle`,
    (candidate) => (
      Number(quoteDecimal(candidate, 'bid')) === Number(bid)
        && Number(quoteDecimal(candidate, 'ask')) === Number(ask)
    )
  )
}

async function waitForMarket(
  context,
  symbol,
  description,
  predicate,
  timeoutMs = 30000
) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const market = await context.api.snapshotMarket(symbol)
    if (predicate(market)) return market
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function waitForAccount(
  context,
  page,
  description,
  predicate,
  timeoutMs = 30000
) {
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

async function waitForCreatedOrder(
  scope,
  before,
  capture,
  symbol,
  status
) {
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
      if (!order || order.symbol !== symbol || order.status !== 'FILLED') {
        return false
      }
      const trade = tradeForOrder(snapshot, order.id)
      return trade ? { snapshot, order, trade } : false
    }
  )
}

async function waitForExistingFilledOrder(scope, before, orderId) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} order ${orderId} fill`,
    (snapshot) => {
      const order = orderById(snapshot, orderId)
      const trade = tradeForOrder(snapshot, orderId)
      if (order?.status !== 'FILLED' || !trade) return false
      const newTrades = recordsAfter(before, snapshot, 'trades')
        .filter(({ orderId: candidate }) => candidate === orderId)
      return newTrades.length === 1
        ? { snapshot, order, trade: newTrades[0] }
        : false
    }
  )
}

async function waitForOrderStatus(
  scope,
  orderId,
  status,
  extra = () => true
) {
  return waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} order ${orderId} ${status}`,
    (snapshot) => {
      const order = orderById(snapshot, orderId)
      return order?.status === status && extra(order) ? snapshot : false
    }
  )
}

async function observeOrderPending(scope, orderId, durationMs) {
  const deadline = Date.now() + durationMs
  let latest
  do {
    latest = await scope.context.api.snapshotAccount(scope.page)
    assert.equal(
      orderById(latest, orderId)?.status,
      'PENDING',
      `${scope.definition.id} order must remain pending before trigger`
    )
    await delay(100)
  } while (Date.now() < deadline)
  return latest
}

async function actionEvents(scope, cursor, expectedTypes, label) {
  const deadline = Date.now() + 15000
  let frames
  while (Date.now() < deadline) {
    frames = scope.context.events.snapshotFrames(scope.page).slice(cursor)
    if (expectedTypes.every((type) => (
      frames.some(({ direction, eventType }) => (
        direction === 'received' && eventType === type
      ))
    ))) {
      return frames.filter(({ eventType }) => expectedTypes.includes(eventType))
    }
    await delay(100)
  }
  throw new Error(
    `${label} missing STOMP events: ${expectedTypes.join(', ')}`
  )
}

async function closePerpPosition(scope, before, position, rules, label) {
  const capture = await scope.context.ui.positionActionViaUi(scope.page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'FULL_CLOSE'
  })
  scope.addMutation(`${label.toLowerCase().replaceAll(/[^a-z0-9]+/g, '-')}-via-ui`, capture)
  const closed = await waitForAccount(
    scope.context,
    scope.page,
    `${label} close`,
    (snapshot) => {
      if (openPositions(snapshot, position.symbol).some(
        ({ id }) => id === position.id
      )) {
        return false
      }
      const trades = recordsAfter(before, snapshot, 'trades')
      const trade = trades.find((candidate) => (
        candidate.symbol === position.symbol
          && candidate.side !== position.side
      )) ?? trades.at(-1)
      const history = snapshot.positionHistory.find(
        ({ id }) => id === position.id
      )
      return trade && history ? { snapshot, trade, history } : false
    }
  )
  const side = position.side === 'BUY' ? 'LONG' : 'SHORT'
  const oracle = perpCloseOracle({
    side,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closeFillPrice: String(closed.trade.price),
    rules
  })
  assertDecimalClose(
    closed.trade.realizedPnl,
    oracle.grossRealizedPnl,
    oracle.tolerances.amount,
    `${label} realized PnL`
  )
  assertDecimalClose(
    closed.trade.fee,
    oracle.closeFee,
    oracle.tolerances.amount,
    `${label} fee`
  )
  assert.equal(closed.history.status, 'CLOSED')
  assertNear(closed.snapshot.summary.usedMargin, 0, `${label} used margin`)
  return { ...closed, oracle, capture }
}

function assertSpotPendingWallet(
  before,
  after,
  order,
  hold,
  rules,
  label
) {
  assert.equal(order.status, 'PENDING')
  assert.equal(order.holdCurrency, hold.currency)
  assertDecimalClose(
    order.holdAmount,
    hold.amount,
    tolerancesFromRules(rules).amount,
    `${label} order hold`
  )
  const beforeWallet = findWallet(before, 'SPOT', hold.currency)
  const afterWallet = findWallet(after, 'SPOT', hold.currency)
  assertDecimalClose(
    afterWallet.total,
    beforeWallet.total,
    tolerancesFromRules(rules).amount,
    `${label} total unchanged`
  )
  assertDecimalClose(
    Number(beforeWallet.available) - Number(afterWallet.available),
    hold.amount,
    tolerancesFromRules(rules).amount,
    `${label} available decrease`
  )
  assertDecimalClose(
    Number(afterWallet.locked) - Number(beforeWallet.locked),
    hold.amount,
    tolerancesFromRules(rules).amount,
    `${label} locked increase`
  )
  const invariant = walletBalanceOracle({
    total: String(afterWallet.total),
    available: String(afterWallet.available),
    locked: String(afterWallet.locked),
    rules
  })
  assert.equal(invariant.valid, true, `${label} wallet invariant`)
  assert.equal(
    after.trades.filter(({ orderId }) => orderId === order.id).length,
    0,
    `${label} pending Trade count`
  )
  return invariant
}

function assertCanceledSpotOrder(
  before,
  pending,
  canceled,
  order,
  hold,
  rules,
  label
) {
  const canceledOrder = orderById(canceled, order.id)
  assert.equal(canceledOrder.status, 'CANCELED')
  assertNear(canceledOrder.holdAmount, 0, `${label} canceled hold`)
  const beforeWallet = findWallet(before, 'SPOT', hold.currency)
  const afterWallet = findWallet(canceled, 'SPOT', hold.currency)
  for (const field of ['total', 'available', 'locked']) {
    assertDecimalClose(
      afterWallet[field],
      beforeWallet[field],
      tolerancesFromRules(rules).amount,
      `${label} released ${field}`
    )
  }
  assert.equal(
    recordsAfter(pending, canceled, 'trades').length,
    0,
    `${label} cancel creates no Trade`
  )
  assert.equal(
    canceled.trades.filter(({ orderId }) => order.id).length,
    0,
    `${label} canceled order has no Trade`
  )
}

function assertPerpPendingHold(before, after, order, hold, label) {
  assert.equal(order.status, 'PENDING')
  assert.equal(order.holdCurrency, 'USDT')
  assertDecimalClose(order.holdAmount, hold.holdAmount, '0.00000001', `${label} hold`)
  assertDecimalClose(
    Number(after.summary.usedMargin) - Number(before.summary.usedMargin),
    hold.holdAmount,
    '0.00000001',
    `${label} used margin`
  )
  assertDecimalClose(after.summary.balance, before.summary.balance, '0.00000001', `${label} balance`)
  assertDecimalClose(
    Number(before.summary.freeMargin) - Number(after.summary.freeMargin),
    hold.holdAmount,
    '0.00000001',
    `${label} free margin`
  )
  assert.equal(
    after.trades.filter(({ orderId }) => orderId === order.id).length,
    0,
    `${label} no Trade`
  )
  assert.equal(openPositions(after, order.symbol).length, 0, `${label} no position`)
}

function assertPerpPendingReleased(before, canceled, order, label) {
  const canceledOrder = orderById(canceled, order.id)
  assert.equal(canceledOrder.status, 'CANCELED')
  assertNear(canceledOrder.holdAmount, 0, `${label} order hold`)
  for (const field of [
    'balance',
    'equity',
    'usedMargin',
    'freeMargin',
    'openFloatingPnl'
  ]) {
    assertDecimalClose(
      canceled.summary[field],
      before.summary[field],
      '0.00000001',
      `${label} ${field}`
    )
  }
  assert.equal(
    canceled.trades.filter(({ orderId }) => order.id).length,
    0,
    `${label} no Trade`
  )
  assert.equal(openPositions(canceled, order.symbol).length, 0, `${label} no position`)
}

function assertPerpOpeningFill({
  before,
  filled,
  position,
  rules,
  leverage,
  liquidityRole,
  label
}) {
  assert.equal(filled.order.status, 'FILLED')
  assert.equal(filled.trade.liquidityRole, liquidityRole)
  assert.equal(Number(position.leverage), leverage)
  assertDecimalClose(
    position.lots,
    filled.trade.lots,
    tolerancesFromRules(rules).quantity,
    `${label} quantity`
  )
  const feeRate = liquidityRole === 'MAKER'
    ? DEMO_RATES.makerFeeRate
    : DEMO_RATES.takerFeeRate
  const feeBuffer = String(
    Number(filled.trade.lots) * Number(filled.trade.price) * Number(feeRate)
  )
  const hold = perpOpeningHoldOracle({
    baseQuantity: String(filled.trade.lots),
    worstPrice: String(filled.trade.price),
    leverage: String(leverage),
    worstFeeRate: feeRate,
    rules
  })
  assertDecimalClose(
    filled.trade.fee,
    feeBuffer,
    hold.tolerances.amount,
    `${label} fee`
  )
  assertDecimalClose(
    position.marginHeld,
    hold.openingInitialMargin,
    hold.tolerances.amount,
    `${label} position margin`
  )
  assertDecimalClose(
    filled.snapshot.summary.usedMargin,
    position.marginHeld,
    hold.tolerances.amount,
    `${label} used margin`
  )
  assertDecimalClose(
    Number(before.summary.balance) - Number(filled.snapshot.summary.balance),
    filled.trade.fee,
    hold.tolerances.amount,
    `${label} balance fee`
  )
  return { ...hold, feeRate, feeBuffer }
}

function assertPerpOpeningLedger(
  beforeDb,
  afterDb,
  position,
  trade,
  oracle,
  pendingOrder,
  label
) {
  const evidence = {
    margin: assertCashLedger(
      afterDb,
      'MARGIN_HOLD',
      'POSITION',
      position.id,
      oracle.openingInitialMargin,
      `${label} MARGIN_HOLD`
    ),
    fee: assertCashLedger(
      afterDb,
      'TRADE_FEE',
      'TRADE',
      trade.id,
      negativeAmount(trade.fee),
      `${label} TRADE_FEE`
    )
  }
  if (pendingOrder) {
    evidence.orderHold = assertCashLedger(
      afterDb,
      'ORDER_HOLD',
      'ORDER',
      pendingOrder.orderId,
      pendingOrder.holdAmount,
      `${label} ORDER_HOLD`
    )
    evidence.orderRelease = assertCashLedger(
      afterDb,
      'ORDER_RELEASE',
      'ORDER',
      pendingOrder.orderId,
      pendingOrder.holdAmount,
      `${label} ORDER_RELEASE`
    )
  }
  const added = recordsAfter(beforeDb, afterDb, 'cashLedgerRows')
  assert(
    added.some(({ reference_id: id }) => id === trade.id),
    `${label} requires Trade-correlated DB ledger`
  )
  return evidence
}

function assertSpotTradeLedger(db, trade, rules, label) {
  const entries = db.assetLedgerRows.filter((entry) => (
    entry.reference_type === 'TRADE' && entry.reference_id === trade.id
  ))
  const expected = trade.side === 'BUY'
    ? {
        SPOT_BUY_DEBIT: {
          asset: 'USDT',
          amount: String(-Number(trade.lots) * Number(trade.price))
        },
        SPOT_BUY_CREDIT: { asset: 'BTC', amount: String(trade.lots) },
        TRADE_FEE: { asset: 'USDT', amount: negativeAmount(trade.fee) }
      }
    : {
        SPOT_SELL_DEBIT: { asset: 'BTC', amount: negativeAmount(trade.lots) },
        SPOT_SELL_CREDIT: {
          asset: 'USDT',
          amount: String(Number(trade.lots) * Number(trade.price))
        },
        TRADE_FEE: { asset: 'USDT', amount: negativeAmount(trade.fee) }
      }
  assert.deepEqual(
    entries.map(({ entry_type: type }) => type).toSorted(),
    Object.keys(expected).toSorted(),
    `${label} exact Trade ledger types`
  )
  for (const entry of entries) {
    const oracle = expected[entry.entry_type]
    assert(oracle, `${label} unexpected ${entry.entry_type}`)
    assert.equal(entry.asset, oracle.asset, `${label} ${entry.entry_type} asset`)
    assertDecimalClose(
      entry.amount,
      oracle.amount,
      tolerancesFromRules(rules).amount,
      `${label} ${entry.entry_type} amount`
    )
  }
  return entries.map(({ id, entry_type: entryType, asset, amount }) => ({
    id,
    entryType,
    asset,
    amount
  }))
}

function assertSpotOrderLedger(db, orderId, entryType, amount, label) {
  const matches = db.assetLedgerRows.filter((entry) => (
    entry.reference_type === 'ORDER'
      && entry.reference_id === orderId
      && entry.entry_type === entryType
  ))
  assert.equal(matches.length, 1, `${label} exact row`)
  assertDecimalClose(matches[0].amount, amount, '0.00000001', `${label} amount`)
  return matches[0]
}

function assertCashLedger(
  db,
  operationType,
  referenceType,
  referenceId,
  amount,
  label
) {
  const matches = db.cashLedgerRows.filter((entry) => (
    entry.operation_type === operationType
      && entry.reference_type === referenceType
      && entry.reference_id === referenceId
  ))
  assert.equal(matches.length, 1, `${label} exact row`)
  assertDecimalClose(matches[0].amount, amount, '0.00000001', `${label} amount`)
  return {
    id: matches[0].id,
    operationType,
    referenceType,
    referenceId,
    amount: matches[0].amount
  }
}

function assertLedgerSum(entries, expected, label) {
  assert(entries.length > 0, `${label} rows`)
  const actual = entries.reduce((sum, { amount }) => sum + Number(amount), 0)
  assertDecimalClose(actual, expected, '0.00000001', label)
}

function assertOrderEventCount(db, orderId, eventType, count, label) {
  assert.equal(
    db.orderEventRows.filter((event) => (
      event.order_id === orderId && event.event_type === eventType
    )).length,
    count,
    label
  )
}

function assertTradingStateEqual(before, after, label) {
  assert.deepEqual(
    tradingStateFingerprint(after),
    tradingStateFingerprint(before),
    label
  )
}

function tradingStateFingerprint(snapshot) {
  const pick = (rows, fields) => rows.map((row) => (
    Object.fromEntries(fields.map((field) => [field, row[field]]))
  )).toSorted((left, right) => (
    JSON.stringify(left).localeCompare(JSON.stringify(right))
  ))
  return {
    wallets: pick(snapshot.wallets, [
      'walletType',
      'asset',
      'total',
      'available',
      'locked'
    ]),
    summary: Object.fromEntries([
      'balance',
      'equity',
      'usedMargin',
      'freeMargin',
      'openFloatingPnl',
      'maintenanceMargin'
    ].map((field) => [field, snapshot.summary[field]])),
    orders: pick(snapshot.orders, [
      'id',
      'status',
      'quantity',
      'price',
      'triggerPrice',
      'holdAmount',
      'version'
    ]),
    trades: pick(snapshot.trades, [
      'id',
      'orderId',
      'price',
      'lots',
      'fee',
      'realizedPnl'
    ]),
    positions: pick(snapshot.positions, [
      'id',
      'status',
      'lots',
      'openPrice',
      'marginHeld',
      'realizedPnl',
      'version'
    ]),
    positionHistory: pick(snapshot.positionHistory ?? [], [
      'id',
      'status',
      'lots',
      'realizedPnl',
      'version'
    ]),
    assetLedger: pick(snapshot.assetLedger ?? [], [
      'id',
      'entryType',
      'amount',
      'referenceType',
      'referenceId'
    ])
  }
}

function orderForCapture(before, after, capture) {
  const created = recordsAfter(before, after, 'orders')
  const matching = created.find((order) => (
    !capture.idempotencyKey
      || order.clientOrderId === capture.idempotencyKey
      || order.idempotencyKey === capture.idempotencyKey
  ))
  return matching ?? created.at(-1)
}

function recordsAfter(before, after, field) {
  const known = new Set((before[field] ?? []).map(({ id }) => id))
  return (after[field] ?? []).filter(({ id }) => !known.has(id))
}

function orderById(snapshot, orderId) {
  return snapshot.orders.find(({ id }) => id === orderId)
}

function tradeForOrder(snapshot, orderId) {
  return snapshot.trades.find((trade) => trade.orderId === orderId)
}

function activeOrders(snapshot, symbol) {
  return snapshot.orders.filter((order) => (
    (!symbol || order.symbol === symbol) && ACTIVE_ORDER_STATUSES.has(order.status)
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
  assert(
    Number.isFinite(Number(value)) && Number(value) > 0,
    `market ${field} must be positive`
  )
  return String(value)
}

function lastPrice(market) {
  return Number(
    market.quote?.last
      ?? market.quote?.mid
      ?? market.reference?.last
  )
}

function markPrice(market) {
  const mark = Number(
    market.reference?.mark
      ?? market.quote?.markPrice
      ?? market.quote?.mid
  )
  assert(Number.isFinite(mark) && mark > 0, 'authority mark required')
  return mark
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
  const reference = Number(
    market.reference?.mark
      ?? market.quote?.markPrice
      ?? market.quote?.mid
      ?? market.quote?.last
      ?? market.quote?.ask
  )
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
  const scale = (step.split('.')[1] ?? '').length
  return (Number(quantity) * multiplier).toFixed(scale)
}

function parsedRequest(capture) {
  assert(capture?.rawRequest?.postData, 'captured request body required')
  return JSON.parse(capture.rawRequest.postData)
}

function assertSuccess(capture, label) {
  assert(
    capture.status >= 200 && capture.status < 300,
    `${label} must return 2xx, got ${capture.status}`
  )
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

function negativeAmount(value) {
  return String(-Number(value))
}
