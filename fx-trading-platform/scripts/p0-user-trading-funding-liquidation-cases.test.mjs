import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import * as cases from './p0-user-trading-funding-liquidation-cases.mjs'
import {
  liquidationFeeOracle,
  marketFillOracle,
  perpCloseOracle
} from './p0-user-trading-oracles.mjs'

const source = await readFile(
  new URL('./p0-user-trading-funding-liquidation-cases.mjs', import.meta.url),
  'utf8'
)

const EXPECTED_HANDLERS = [
  'runFund01',
  'runFund02',
  'runFund03',
  'runFund04',
  'runLiq01',
  'runLiq02',
  'runLiq03',
  'runLiq04'
]

test('exports exactly the FUND and LIQ handlers', () => {
  assert.deepEqual(Object.keys(cases.CASE_HANDLERS).toSorted(), EXPECTED_HANDLERS)
  for (const name of EXPECTED_HANDLERS) {
    assert.equal(typeof cases[name], 'function', name)
    assert.equal(cases.CASE_HANDLERS[name], cases[name], name)
  }
})

test('funding settlement contract is exact and fail-closed', () => {
  const expected = {
    settlementAmount: '-1.00000000',
    balanceAfter: '999.00000000',
    isolatedMarginAfter: '0.00000000',
    fundingPnlAfter: '-1.00000000',
    shortfall: '0.00000000',
    ledgerAmount: '-1.00000000'
  }
  const settlement = {
    positionId: 'position-1',
    fundingTime: '2026-08-14T00:00:00Z',
    fundingRate: '0.01000000',
    amount: '-1.00000000',
    asset: 'USDT',
    ledgerEntryId: '00000000-0000-4000-8000-000000000001',
    positionSide: 'LONG',
    marginMode: 'CROSS',
    markPrice: '100.0000000000',
    source: 'FIXED',
    balanceAfter: '999.00000000',
    isolatedMarginAfter: '0.00000000',
    shortfall: '0.00000000'
  }
  assert.doesNotThrow(() => cases.assertFundingSettlementContract({
    settlement,
    expected,
    expectedPositionId: 'position-1',
    expectedFundingTime: '2026-08-14T00:00:00Z',
    expectedSource: 'FIXED',
    expectedFundingRate: '0.01000000',
    expectedMarkPrice: '100.0000000000',
    expectedPositionSide: 'LONG',
    expectedMarginMode: 'CROSS'
  }))
  assert.throws(
    () => cases.assertFundingSettlementContract({
      settlement: { ...settlement, ledgerEntryId: null },
      expected,
      expectedPositionId: 'position-1',
      expectedFundingTime: settlement.fundingTime,
      expectedSource: 'FIXED',
      expectedFundingRate: settlement.fundingRate,
      expectedMarkPrice: settlement.markPrice,
      expectedPositionSide: settlement.positionSide,
      expectedMarginMode: settlement.marginMode
    }),
    /ledgerEntryId/
  )
  assert.throws(
    () => cases.assertFundingSettlementContract({
      settlement: { ...settlement, amount: '-0.99999999' },
      expected,
      expectedPositionId: 'position-1',
      expectedFundingTime: settlement.fundingTime,
      expectedSource: 'FIXED',
      expectedFundingRate: settlement.fundingRate,
      expectedMarkPrice: settlement.markPrice,
      expectedPositionSide: settlement.positionSide,
      expectedMarginMode: settlement.marginMode
    }),
    /amount/
  )
  for (const [field, wrong, message] of [
    ['fundingRate', '0.02000000', /fundingRate/],
    ['markPrice', '101.0000000000', /markPrice/],
    ['positionSide', 'SHORT', /positionSide/],
    ['marginMode', 'ISOLATED', /marginMode/]
  ]) {
    assert.throws(
      () => cases.assertFundingSettlementContract({
        settlement: { ...settlement, [field]: wrong },
        expected,
        expectedPositionId: 'position-1',
        expectedFundingTime: settlement.fundingTime,
        expectedSource: 'FIXED',
        expectedFundingRate: settlement.fundingRate,
        expectedMarkPrice: settlement.markPrice,
        expectedPositionSide: settlement.positionSide,
        expectedMarginMode: settlement.marginMode
      }),
      message
    )
  }
})

test('isolated funding requires no cash ledger when the oracle says none', () => {
  const settlement = {
    positionId: 'position-2',
    fundingTime: '2026-08-14T00:01:00Z',
    fundingRate: '-0.01000000',
    amount: '1.00000000',
    asset: 'USDT',
    ledgerEntryId: null,
    positionSide: 'LONG',
    marginMode: 'ISOLATED',
    markPrice: '100.0000000000',
    source: 'FIXED',
    balanceAfter: '1000.00000000',
    isolatedMarginAfter: '101.00000000',
    shortfall: '0.00000000'
  }
  const expected = {
    settlementAmount: '1.00000000',
    balanceAfter: '1000.00000000',
    isolatedMarginAfter: '101.00000000',
    fundingPnlAfter: '1.00000000',
    shortfall: '0.00000000',
    ledgerAmount: null
  }
  assert.doesNotThrow(() => cases.assertFundingSettlementContract({
    settlement,
    expected,
    expectedPositionId: 'position-2',
    expectedFundingTime: settlement.fundingTime,
    expectedSource: 'FIXED',
    expectedFundingRate: settlement.fundingRate,
    expectedMarkPrice: settlement.markPrice,
    expectedPositionSide: settlement.positionSide,
    expectedMarginMode: settlement.marginMode
  }))
  assert.throws(
    () => cases.assertFundingSettlementContract({
      settlement: { ...settlement, ledgerEntryId: 'unexpected-ledger' },
      expected,
      expectedPositionId: 'position-2',
      expectedFundingTime: settlement.fundingTime,
      expectedSource: 'FIXED',
      expectedFundingRate: settlement.fundingRate,
      expectedMarkPrice: settlement.markPrice,
      expectedPositionSide: settlement.positionSide,
      expectedMarginMode: settlement.marginMode
    }),
    /ledgerEntryId/
  )
})

test('liquidation lifecycle contract rejects duplicates and wrong origin', () => {
  const evidence = {
    positionId: 'position-1',
    orders: [{
      id: 'order-1',
      parent_position_id: 'position-1',
      order_origin: 'LIQUIDATION',
      status: 'FILLED'
    }],
    trades: [{ id: 'trade-1', order_id: 'order-1' }],
    ledger: [{ id: 'ledger-1', reference_id: 'position-1', entry_type: 'LIQUIDATION_FEE' }],
    expectedFeeCharged: '1.00000000'
  }
  assert.doesNotThrow(() => cases.assertLiquidationLifecycleContract(evidence))
  assert.throws(
    () => cases.assertLiquidationLifecycleContract({
      ...evidence,
      orders: [...evidence.orders, { ...evidence.orders[0], id: 'order-2' }]
    }),
    /exactly one liquidation order/
  )
  assert.throws(
    () => cases.assertLiquidationLifecycleContract({
      ...evidence,
      orders: [{ ...evidence.orders[0], order_origin: 'USER' }]
    }),
    /exactly one liquidation order/
  )
})

test('liquidation lifecycle permits no fee ledger only when expected charge is zero', () => {
  assert.doesNotThrow(() => cases.assertLiquidationLifecycleContract({
    positionId: 'position-1',
    orders: [{
      id: 'order-1',
      parent_position_id: 'position-1',
      order_origin: 'LIQUIDATION',
      status: 'FILLED'
    }],
    trades: [{ id: 'trade-1', order_id: 'order-1' }],
    ledger: [],
    expectedFeeCharged: '0.00000000'
  }))
})

test('liquidation trade contract independently pins full quantity, fill, PnL and fee', () => {
  assert.equal(typeof cases.assertLiquidationTradeContract, 'function')
  const rules = {
    tickSize: '0.01',
    stepSize: '0.00001',
    minQty: '0.0001',
    contractSize: '1',
    contractMultiplier: '1'
  }
  const market = {
    rules,
    quote: { bid: '90', ask: '90.01' },
    feeRates: { takerFeeRate: '0.0005', liquidationFeeRate: '0.005' }
  }
  const position = { id: 'position-1', side: 'LONG', lots: '1', openPrice: '100' }
  const price = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: 'SELL',
    bid: market.quote.bid,
    ask: market.quote.ask
  }).filledPrice
  const oracle = perpCloseOracle({
    side: 'LONG',
    quantity: position.lots,
    entryPrice: position.openPrice,
    closeFillPrice: price,
    closeFeeRate: market.feeRates.takerFeeRate,
    rules
  })
  const trade = {
    side: 'SELL',
    lots: '1',
    price,
    realized_pnl: oracle.grossRealizedPnl,
    fee: oracle.closeFee
  }
  assert.doesNotThrow(() => cases.assertLiquidationTradeContract({ position, trade, market }))
  assert.throws(
    () => cases.assertLiquidationTradeContract({
      position,
      trade: { ...trade, realized_pnl: '0' },
      market
    }),
    /realized PnL/u
  )
  assert.throws(
    () => cases.assertLiquidationTradeContract({
      position,
      trade: { ...trade, lots: '0.5' },
      market
    }),
    /full quantity/u
  )
  assert.throws(
    () => cases.assertLiquidationTradeContract({
      position,
      trade: { ...trade, price: '91' },
      market
    }),
    /fill price/u
  )
  assert.throws(
    () => cases.assertLiquidationTradeContract({
      position,
      trade: { ...trade, fee: '0' },
      market
    }),
    /taker fee/u
  )
})

test('isolated liquidation charges only the independently remaining position capacity', () => {
  assert.equal(typeof cases.isolatedLiquidationCollectionCapacity, 'function')
  const rules = {
    tickSize: '0.10',
    stepSize: '0.0001',
    minQty: '0.0001',
    contractSize: '1',
    contractMultiplier: '1'
  }
  const position = { marginHeld: '60.00000000' }
  const tradeOracle = perpCloseOracle({
    side: 'LONG',
    quantity: '0.01',
    entryPrice: '60000',
    closeFillPrice: '54298.59253394',
    closeFeeRate: '0.0005',
    rules
  })
  const collectionCapacity = cases.isolatedLiquidationCollectionCapacity({
    position,
    tradeOracle
  })
  assert.equal(collectionCapacity, '2.71443238')
  const fee = liquidationFeeOracle({
    filledQuantity: '0.01',
    executionPrice: '54298.59253394',
    liquidationFeeRate: '0.005',
    collectionCapacity,
    rules
  })
  assert.equal(fee.chargedLiquidationFee, collectionCapacity)
  assert.notEqual(fee.chargedLiquidationFee, fee.nominalLiquidationFee)
})

test('cross shortfall contract rejects inflated and understated settlement', () => {
  const accountId = '00000000-0000-4000-8000-000000000010'
  const settlementId = '00000000-0000-4000-8000-000000000011'
  const evidence = {
    accountId,
    fixture: { capacity: 10 },
    trades: [
      { order_id: 'order-1', realized_pnl: '-12.00000000', fee: '1.00000000' },
      { order_id: 'order-2', realized_pnl: '-2.00000000', fee: '0.50000000' }
    ],
    charges: [
      { order_id: 'order-1', fee_due: '2.00000000', fee_charged: '0.00000000', status: 'SETTLED' },
      { order_id: 'order-2', fee_due: '1.00000000', fee_charged: '0.00000000', status: 'SETTLED' }
    ],
    shortfalls: [{
      amount: '8.50000000',
      balance_after: '0.00000000',
      reference_type: 'CROSS_LIQUIDATION_SETTLEMENT',
      reference_id: settlementId
    }],
    audits: [{
      action: 'BANKRUPTCY_SHORTFALL',
      target_type: 'ACCOUNT',
      target_id: accountId,
      details: JSON.stringify({ amount: 8.5, settlementId })
    }]
  }
  assert.deepEqual(cases.assertCrossShortfallContract(evidence), {
    cashAfterCore: '-5.50000000',
    uncoveredCoreDebit: '5.50000000',
    feeDue: '3.00000000',
    feeCharged: '0.00000000',
    uncollectedFee: '3.00000000',
    bankruptcyShortfall: '8.50000000'
  })
  evidence.shortfalls[0].amount = '9.50000000'
  assert.throws(() => cases.assertCrossShortfallContract(evidence), /BANKRUPTCY_SHORTFALL/)
  evidence.shortfalls[0].amount = '7.50000000'
  assert.throws(() => cases.assertCrossShortfallContract(evidence), /BANKRUPTCY_SHORTFALL/)
  evidence.shortfalls[0].amount = '8.50000000'
  evidence.shortfalls[0].reference_type = 'LIQUIDATION_ORDER'
  assert.throws(() => cases.assertCrossShortfallContract(evidence), /reference type/u)
  evidence.shortfalls[0].reference_type = 'CROSS_LIQUIDATION_SETTLEMENT'
  evidence.shortfalls[0].balance_after = '1.00000000'
  assert.throws(() => cases.assertCrossShortfallContract(evidence), /balance after/u)
  evidence.shortfalls[0].balance_after = '0.00000000'
  evidence.audits[0].target_id = '00000000-0000-4000-8000-000000000099'
  assert.throws(() => cases.assertCrossShortfallContract(evidence), /audit cardinality/u)
})

test('strict funding and liquidation journeys keep the missing UI and recovery gates', () => {
  const fundingSourceJourney = source.slice(
    source.indexOf('async function runFundingSourceJourney'),
    source.indexOf('async function runFundingRecoveryJourney')
  )
  const fundingRecoveryJourney = source.slice(
    source.indexOf('async function runFundingRecoveryJourney'),
    source.indexOf('async function runIsolatedLiquidationJourney')
  )
  const isolatedJourney = source.slice(
    source.indexOf('async function runIsolatedLiquidationJourney'),
    source.indexOf('async function runCrossLiquidationJourney')
  )
  const crossJourney = source.slice(
    source.indexOf('async function runCrossLiquidationJourney'),
    source.indexOf('async function runShortfallJourney')
  )
  const adminVisibility = source.slice(
    source.indexOf('async function assertAdminLiquidationVisible'),
    source.indexOf('function assertLiquidationFees')
  )
  assert.equal((fundingRecoveryJourney.match(/assertFundingOutcome\(/gu) ?? []).length >= 2, true)
  assert.match(fundingRecoveryJourney, /persistedFirstSettlement/u)
  assert.match(fundingRecoveryJourney, /assertFundingSettlementContract\(\{[\s\S]*firstOracle/u)
  assert.match(fundingSourceJourney, /assertFundingVisibleViaUi\(scope\.page/u)
  assert.match(fundingSourceJourney, /canonicalRateCount/u)
  assert.match(isolatedJourney, /assertIsolatedRiskVisibleViaUi/u)
  assert.match(isolatedJourney, /waitForRiskOrdersCanceled/u)
  assert.match(crossJourney, /waitForRiskOrdersCanceled/u)
  assert.match(crossJourney, /postCancelRisk/u)
  assert.match(crossJourney, /snapshotShortfallDb/u)
  assert.match(crossJourney, /assertCrossShortfallContract/u)
  assert.match(crossJourney, /fixture:\s*\{\s*capacity:\s*safeSnapshot\.summary\.balance\s*\}/u)
  assert.match(crossJourney, /shortfalls:\s*liquidationDb\.shortfalls/u)
  assert.match(crossJourney, /expectedFeeCharged:\s*charge\.fee_charged/u)
  assert.doesNotMatch(crossJourney, /BANKRUPTCY_SHORTFALL['"]\)\.length,\s*0/u)
  assert.doesNotMatch(adminVisibility, /document\.body/u)
  assert.match(adminVisibility, /operation-section/u)
})

test('journeys retain real UI, REST, DB, STOMP and reversible fixture evidence', () => {
  for (const token of [
    'runSingleUserCoreCase',
    'submitOrderViaUi',
    'positionActionViaUi',
    'snapshotAccount',
    'snapshotTradingRows',
    'snapshotFrames',
    'fundingConfig',
    'providerBindings',
    'positionTime',
    'marketOverride',
    'FUNDING_SETTLED',
    'LIQUIDATION',
    'BANKRUPTCY_SHORTFALL'
  ]) {
    assert.match(source, new RegExp(token))
  }
  assert.doesNotMatch(source, /BACKEND_CONTRACT_ONLY|TODO|stub|placeholder/iu)
  assert.doesNotMatch(source, /INSERT\s+INTO\s+trading\.(orders|trades|positions)/iu)
  assert.doesNotMatch(source, /UPDATE\s+trading\.positions\s+SET\s+status/iu)
  assert.doesNotMatch(source, /api\/.*(?:settle|liquidat)/iu)
  const fundingSourceJourney = source.slice(
    source.indexOf('async function runFundingSourceJourney'),
    source.indexOf('async function runFundingRecoveryJourney')
  )
  assert.doesNotMatch(fundingSourceJourney, /createFundingCycleFixture/)
  assert.match(fundingSourceJourney, /isolateFundingRateCursor/u)
  assert.match(source, /jsonb_populate_recordset\(\s*null::trading\.funding_rates/u)
  assert.match(fundingSourceJourney, /prepareIngestedFundingCycle[\s\S]*providerCode:\s*['"]fixed['"]/u)
  assert.match(fundingSourceJourney, /prepareIngestedFundingCycle/u)
  assert.match(fundingSourceJourney, /fixedFundingIntervalMinutes:\s*525600/u)
  assert.match(fundingSourceJourney, /PUBLIC_EXTERNAL/u)
  assert.match(fundingSourceJourney, /P0PublicProviderUnavailableError/u)
  assert.match(fundingSourceJourney, /FUNDING_INGESTION_TIMEOUT/u)
  assert.match(fundingSourceJourney, /fixedFallback/u)
  assert.match(fundingSourceJourney, /publicFailures/u)
  assert(
    fundingSourceJourney.indexOf('closePositionViaUi')
      < fundingSourceJourney.indexOf('throw new P0PublicProviderUnavailableError'),
    'FUND-03 must prove fixed fallback and close its position before reporting BLOCKED'
  )
  const fundingRecoveryJourney = source.slice(
    source.indexOf('async function runFundingRecoveryJourney'),
    source.indexOf('async function runIsolatedLiquidationJourney')
  )
  assert.match(fundingRecoveryJourney, /createAuthenticatedPeer/)
  assert.match(fundingRecoveryJourney, /waitForFundingBoundary/)
  assert.match(fundingRecoveryJourney, /FUNDING_FIRST|CLOSE_FIRST/u)
  assert.match(source, /SAME_USER_SECOND_BROWSER_TAB/u)
  assert.doesNotMatch(source, /SAME_USER_SECOND_BROWSER_CLIENT/u)
  const authenticatedPeer = source.slice(
    source.indexOf('async function createAuthenticatedPeer'),
    source.indexOf('async function closeSecondaryUser')
  )
  assert.match(authenticatedPeer, /createEvidencePage\(scope\.browser/u)
  assert.doesNotMatch(authenticatedPeer, /launchBrowser/u)
  assert.match(
    fundingRecoveryJourney,
    /scope\.capture\([\s\S]*?\[scope\.page,\s*peer\.page\]/u,
    'FUND-04 must persist evidence from both same-browser tabs before closing the peer tab'
  )
  const crossJourney = source.slice(
    source.indexOf('async function runCrossLiquidationJourney'),
    source.indexOf('async function runShortfallJourney')
  )
  assert.doesNotMatch(crossJourney, /openPrice\) \* 0\.05/u)
  assert.match(crossJourney, /crossBoundaryMarkets/)
  assert.doesNotMatch(source, /restore-shortfall-risk-capacity/)
  assert.match(source, /SELECT\s+liquidation_fee_rate::text/u)
  assert.match(source, /DEMO_RATES\.takerFeeRate/u)
  assert.doesNotMatch(source, /\?\?\s*['"]0\.(?:0006|005)['"]/u)
})
