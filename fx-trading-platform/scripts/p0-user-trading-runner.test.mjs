import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { EventEmitter } from 'node:events'
import {
  existsSync,
  linkSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  readdirSync,
  rmSync,
  statSync,
  symlinkSync,
  utimesSync,
  writeFileSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, delimiter, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import test from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { Worker } from 'node:worker_threads'

import {
  aggregateReport,
  loadOrCreateRunState,
  parseSurefireReports,
  planResume,
  redactNetworkEntry,
  writeCaseResultAtomic
} from './p0-user-trading-artifacts.mjs'
import {
  P0_CASES,
  P0_REGISTRY_FINGERPRINT,
  countByPhase,
  registryFingerprint as canonicalRegistryFingerprint,
  runCase
} from './p0-user-trading-cases.mjs'
import * as p0CaseContracts from './p0-user-trading-cases.mjs'
import * as financialOracles from './p0-user-trading-oracles.mjs'
import * as smokeContracts from './smoke-usdt-demo-browser.mjs'
import './p0-user-trading-advanced-cases.mjs'
import * as p0CoreContracts from './p0-user-trading-core-cases.mjs'
import './p0-user-trading-order-cases.mjs'

const specPath = new URL(
  '../docs/superpowers/specs/2026-07-14-p0-user-trading-acceptance-test-design.md',
  import.meta.url
)
const artifactsScript = fileURLToPath(new URL('./p0-user-trading-artifacts.mjs', import.meta.url))

const BTC_RULES = Object.freeze({
  tickSize: '0.01',
  stepSize: '0.00001',
  minQty: '0.0001',
  contractSize: '1',
  contractMultiplier: '1'
})

test('financial oracle: BigInt fixed-point uses explicit rounding and rule-derived grids', () => {
  assert.equal(typeof financialOracles.roundDecimal, 'function')
  assert.equal(financialOracles.roundDecimal('1.005', 2, 'HALF_UP'), '1.01')
  assert.equal(financialOracles.roundDecimal('-1.005', 2, 'HALF_UP'), '-1.01')
  assert.equal(financialOracles.roundDecimal('1.009', 2, 'DOWN'), '1.00')
  assert.equal(financialOracles.divideDecimal('2', '3', 8, 'HALF_UP'), '0.66666667')
  assert.equal(financialOracles.divideDecimal('2', '3', 8, 'DOWN'), '0.66666666')
  assert.throws(
    () => financialOracles.roundDecimal(1.005, 2, 'HALF_UP'),
    /decimal string/
  )
  assert.equal(
    financialOracles.floorToStep('0.019998', financialOracles.effectiveQuantityStep(BTC_RULES)),
    '0.0199'
  )
  assert.deepEqual(financialOracles.tolerancesFromRules(BTC_RULES), {
    price: '0.005',
    quantity: '0.00005',
    amount: '0.000000005'
  })
  assert.equal(financialOracles.withinTolerance('1.000000004', '1', '0.000000005'), true)
  assert.equal(financialOracles.withinTolerance('1.000000006', '1', '0.000000005'), false)
  assert.equal(financialOracles.alignPriceToTick('50000.019', BTC_RULES), '50000.01')
  assert.equal(financialOracles.quantityFromUnit({
    unit: 'QUOTE',
    quantity: '500',
    authorityMark: '50000',
    rules: BTC_RULES
  }), '0.0100')
  assert.equal(financialOracles.quantityFromUnit({
    unit: 'CONTRACTS',
    quantity: '3',
    authorityMark: '50000',
    rules: {
      ...BTC_RULES,
      contractSize: '0.001',
      contractMultiplier: '1'
    }
  }), '0.0030')
  assert.throws(
    () => financialOracles.quantityFromUnit({
      unit: 'CONTRACTS',
      quantity: '3.5',
      authorityMark: '50000',
      rules: BTC_RULES
    }),
    /integral/
  )
})

test('financial oracle review: REST and DB money tolerance is half the 8-place unit', () => {
  assert.equal(
    financialOracles.tolerancesFromRules(BTC_RULES).amount,
    '0.000000005'
  )
})

test('financial oracle review: BASE quantity rejects rather than floors a step mismatch', () => {
  assert.throws(
    () => financialOracles.quantityFromUnit({
      unit: 'BASE',
      quantity: '0.01005',
      authorityMark: '50000',
      rules: BTC_RULES
    }),
    /effective step/
  )
  assert.equal(financialOracles.quantityFromUnit({
    unit: 'QUOTE',
    quantity: '502.5',
    authorityMark: '50000',
    rules: BTC_RULES
  }), '0.0100')
})

test('financial oracle: Spot BUY floors gross base and charges the fee in base', () => {
  assert.equal(typeof financialOracles.spotBuyOracle, 'function')
  assert.deepEqual(financialOracles.spotBuyOracle({
    quoteBudget: '1000',
    fillPrice: '50005',
    feeRate: '0.0005',
    rules: BTC_RULES
  }), {
    effectiveStep: '0.0001',
    grossBase: '0.0199',
    quoteSpent: '995.09950000',
    baseFee: '0.00000995',
    netBase: '0.01989005',
    cumulativeGrossQuoteCost: '995.09950000',
    currentNetBase: '0.01989005',
    averageCost: '50030.01500750',
    feeAsset: 'BASE',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
})

test('financial oracle: Spot SELL keeps quote fee inside realized PnL and checks wallet invariant', () => {
  assert.equal(typeof financialOracles.spotSellOracle, 'function')
  assert.deepEqual(financialOracles.spotSellOracle({
    soldBase: '0.006',
    fillPrice: '54994.5',
    feeRate: '0.0005',
    averageCost: '50030.01500750',
    rules: BTC_RULES
  }), {
    grossQuote: '329.96700000',
    quoteFee: '0.16498350',
    netQuote: '329.80201650',
    costBasis: '300.18009005',
    realizedPnl: '29.62192646',
    feeAsset: 'QUOTE',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
  assert.deepEqual(financialOracles.walletBalanceOracle({
    total: '329.80201650',
    available: '300',
    locked: '29.80201650',
    rules: BTC_RULES
  }), {
    balanced: true,
    nonNegative: true,
    valid: true,
    tolerance: '0.000000005'
  })
  assert.equal(financialOracles.walletBalanceOracle({
    total: '1',
    available: '-0.01',
    locked: '1.01',
    rules: BTC_RULES
  }).valid, false)
})

test('financial oracle: Spot pending and OCO holds match the backend ceiling policy', () => {
  assert.deepEqual(financialOracles.spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'LIMIT',
    baseQuantity: '0.2',
    limitPrice: '45',
    ask: '50',
    baseAsset: 'BTC'
  }), {
    amount: '9.00450000',
    currency: 'USDT',
    basis: 'LIMIT',
    shared: false
  })
  assert.deepEqual(financialOracles.spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'STOP_MARKET',
    baseQuantity: '0.2',
    stopTriggerPrice: '50.5',
    ask: '50',
    baseAsset: 'BTC'
  }), {
    amount: '10.10606051',
    currency: 'USDT',
    basis: 'STOP_MARKET',
    shared: false
  })
  assert.deepEqual(financialOracles.spotOrderHoldOracle({
    side: 'BUY',
    orderType: 'OCO',
    baseQuantity: '0.2',
    limitPrice: '45',
    stopTriggerPrice: '50.5',
    ask: '50',
    baseAsset: 'BTC'
  }), {
    amount: '10.10606051',
    currency: 'USDT',
    basis: 'STOP_MARKET',
    shared: true
  })
  assert.deepEqual(financialOracles.spotOrderHoldOracle({
    side: 'SELL',
    orderType: 'OCO',
    baseQuantity: '0.2',
    limitPrice: '55',
    stopTriggerPrice: '49',
    ask: '50',
    baseAsset: 'BTC'
  }), {
    amount: '0.20000000',
    currency: 'BTC',
    basis: 'LIMIT',
    shared: true
  })
})

test('financial oracle: linear Perp long and short use directional gross PnL', () => {
  assert.equal(typeof financialOracles.perpPositionOracle, 'function')
  const long = financialOracles.perpPositionOracle({
    side: 'LONG',
    quantity: '0.010',
    entryPrice: '50000',
    markPrice: '55000',
    leverage: '50',
    positionMargin: '10',
    maintenanceMarginRate: '0.005',
    rules: BTC_RULES
  })
  assert.deepEqual(long, {
    entryNotional: '500.00000000',
    markNotional: '550.00000000',
    initialMargin: '10.00000000',
    maintenanceMargin: '2.75000000',
    unrealizedPnl: '50.00000000',
    roiPercent: '500.00000000',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
  assert.equal(financialOracles.perpPositionOracle({
    side: 'SHORT',
    quantity: '0.010',
    entryPrice: '50000',
    markPrice: '45000',
    leverage: '100',
    positionMargin: '5',
    maintenanceMarginRate: '0.005',
    rules: BTC_RULES
  }).unrealizedPnl, '50.00000000')
  assert.deepEqual(financialOracles.perpCloseOracle({
    side: 'LONG',
    quantity: '0.003',
    entryPrice: '50000',
    closeFillPrice: '54994.5',
    closeFeeRate: '0.0005',
    openingFee: '0.075',
    fundingCashflow: '0.01',
    rules: BTC_RULES
  }), {
    grossRealizedPnl: '14.98350000',
    closeFee: '0.08249175',
    cashDelta: '14.83600825',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
})

test('financial oracle: funding applies directional cashflow to the correct margin pool', () => {
  assert.deepEqual(financialOracles.fundingSettlementOracle({
    side: 'LONG',
    marginMode: 'CROSS',
    quantity: '0.01',
    markPrice: '50000',
    fundingRate: '0.0001',
    balanceBefore: '50000',
    marginHeld: '10',
    previousFundingPnl: '0'
  }), {
    notional: '500.00000000',
    settlementAmount: '-0.05000000',
    appliedCashflow: '-0.05000000',
    shortfall: '0.00000000',
    balanceAfter: '49999.95000000',
    isolatedMarginAfter: '0.00000000',
    fundingPnlAfter: '-0.05000000',
    ledgerAmount: '-0.05000000'
  })
  assert.deepEqual(financialOracles.fundingSettlementOracle({
    side: 'SHORT',
    marginMode: 'ISOLATED',
    quantity: '0.01',
    markPrice: '50000',
    fundingRate: '0.0001',
    balanceBefore: '50000',
    marginHeld: '10',
    previousFundingPnl: '0'
  }), {
    notional: '500.00000000',
    settlementAmount: '0.05000000',
    appliedCashflow: '0.05000000',
    shortfall: '0.00000000',
    balanceAfter: '50000.00000000',
    isolatedMarginAfter: '10.05000000',
    fundingPnlAfter: '0.05000000',
    ledgerAmount: null
  })
  assert.deepEqual(financialOracles.fundingSettlementOracle({
    side: 'LONG',
    marginMode: 'ISOLATED',
    quantity: '0.01',
    markPrice: '50000',
    fundingRate: '0.01',
    balanceBefore: '50000',
    marginHeld: '1',
    previousFundingPnl: '0'
  }), {
    notional: '500.00000000',
    settlementAmount: '-5.00000000',
    appliedCashflow: '-1.00000000',
    shortfall: '4.00000000',
    balanceAfter: '50000.00000000',
    isolatedMarginAfter: '0.00000000',
    fundingPnlAfter: '-1.00000000',
    ledgerAmount: '-4.00000000'
  })
})

test('financial oracle: Perp opening hold includes initial margin and worst fee once', () => {
  assert.deepEqual(financialOracles.perpOpeningHoldOracle({
    baseQuantity: '0.01',
    worstPrice: '50005',
    leverage: '50',
    worstFeeRate: '0.0005',
    rules: BTC_RULES
  }), {
    notional: '500.05000000',
    openingInitialMargin: '10.00100000',
    feeBuffer: '0.25002500',
    holdAmount: '10.25102500',
    holdCurrency: 'USDT',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
})

test('financial oracle: Spot and Perp transfer deltas conserve combined USDT', () => {
  const spotToPerp = financialOracles.transferConservationOracle({
    direction: 'SPOT_TO_PERP',
    amount: '1000',
    spotAvailable: '50000',
    perpBalance: '50000',
    perpEquity: '50000',
    perpFreeMargin: '50000'
  })
  assert.deepEqual(spotToPerp, {
    spotAvailableAfter: '49000.00000000',
    perpBalanceAfter: '51000.00000000',
    perpEquityAfter: '51000.00000000',
    perpFreeMarginAfter: '51000.00000000',
    combinedBefore: '100000.00000000',
    combinedAfter: '100000.00000000'
  })
  assert.deepEqual(financialOracles.transferConservationOracle({
    direction: 'PERP_TO_SPOT',
    amount: '400',
    spotAvailable: spotToPerp.spotAvailableAfter,
    perpBalance: spotToPerp.perpBalanceAfter,
    perpEquity: spotToPerp.perpEquityAfter,
    perpFreeMargin: spotToPerp.perpFreeMarginAfter
  }), {
    spotAvailableAfter: '49400.00000000',
    perpBalanceAfter: '50600.00000000',
    perpEquityAfter: '50600.00000000',
    perpFreeMarginAfter: '50600.00000000',
    combinedBefore: '100000.00000000',
    combinedAfter: '100000.00000000'
  })
})

test('financial oracle: 30% partial close only realizes and releases the closed quantity', () => {
  assert.equal(typeof financialOracles.partialCloseOracle, 'function')
  assert.deepEqual(financialOracles.partialCloseOracle({
    side: 'LONG',
    originalQuantity: '0.010',
    oldMargin: '10',
    entryPrice: '50000',
    closeFillPrice: '55000',
    closeFeeRate: '0.0005',
    previousPositionRealizedPnl: '2',
    rules: BTC_RULES
  }), {
    entryPrice: '50000',
    closedQuantity: '0.0030',
    remainingQuantity: '0.0070',
    releasedMargin: '3.00000000',
    remainingMargin: '7.00000000',
    tradeRealizedPnl: '15.00000000',
    positionRealizedPnl: '17.00000000',
    closeFee: '0.08250000',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
  assert.throws(
    () => financialOracles.partialCloseOracle({
      side: 'LONG',
      originalQuantity: '0.0001',
      oldMargin: '1',
      entryPrice: '50000',
      closeFillPrice: '55000',
      closeFeeRate: '0.0005',
      rules: BTC_RULES
    }),
    /30%.*step/
  )
})

test('financial oracle: projected net applies closing-side slippage and never re-deducts opening fee', () => {
  assert.equal(typeof financialOracles.projectedNetOracle, 'function')
  assert.deepEqual(financialOracles.projectedNetOracle({
    side: 'LONG',
    quantity: '0.010',
    entryPrice: '50000',
    closingBid: '55000',
    closingAsk: '55010',
    executionPath: 'MARKET',
    openingFee: '0.25',
    rules: BTC_RULES
  }), {
    liquidityRole: 'TAKER',
    projectedCloseFill: '54994.50000000',
    projectedGrossPnl: '49.94500000',
    projectedCloseFee: '0.27497250',
    projectedNetFromNow: '49.67002750',
    projectedWholeTradeNet: '49.42002750',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
  assert.deepEqual(financialOracles.projectedNetOracle({
    side: 'LONG',
    quantity: '0.010',
    entryPrice: '50000',
    closingBid: '55000',
    closingAsk: '55010',
    executionPath: 'RESTING_LIMIT',
    limitPrice: '54000',
    rules: BTC_RULES
  }), {
    liquidityRole: 'MAKER',
    projectedCloseFill: '55000.00000000',
    projectedGrossPnl: '50.00000000',
    projectedCloseFee: '0.11000000',
    projectedNetFromNow: '49.89000000',
    projectedWholeTradeNet: '49.89000000',
    tolerances: {
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }
  })
})

test('financial oracle review: market fill derives independent Spot and Perp slippage', () => {
  assert.equal(typeof financialOracles.marketFillOracle, 'function')
  assert.deepEqual(financialOracles.marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'BUY',
    bid: '99.99',
    ask: '100.01'
  }), {
    referencePrice: '100.01',
    filledPrice: '100.020001',
    slippage: '0.010001',
    slippageRate: '0.0001',
    feeRate: '0.0005',
    liquidityRole: 'TAKER'
  })
  assert.deepEqual(financialOracles.marketFillOracle({
    productType: 'LINEAR_PERP',
    side: 'SELL',
    bid: '100.005678901',
    ask: '100.015678901'
  }), {
    referencePrice: '100.005678901',
    filledPrice: '99.99567833',
    slippage: '0.01000057',
    slippageRate: '0.0001',
    feeRate: '0.0005',
    liquidityRole: 'TAKER'
  })
})

test('financial oracle: isolated liquidation flips only at the long and short equity boundary', () => {
  assert.equal(typeof financialOracles.isolatedLiquidationOracle, 'function')
  const isolated = (side, markPrice) => financialOracles.isolatedLiquidationOracle({
    side,
    quantity: '1',
    entryPrice: '100',
    markPrice,
    marginHeld: '10',
    fundingPnl: '0',
    maintenanceMarginRate: '0.01',
    rules: BTC_RULES
  })
  assert.equal(isolated('LONG', '90.96').liquidatable, false)
  assert.equal(isolated('LONG', '90.95').liquidatable, true)
  assert.equal(isolated('LONG', '90.95').estimatedLiquidationPrice, '90.95502779')
  assert.equal(isolated('SHORT', '108.85').liquidatable, false)
  assert.equal(isolated('SHORT', '108.86').liquidatable, true)
  assert.equal(isolated('SHORT', '108.86').estimatedLiquidationPrice, '108.85700148')
  assert.equal(financialOracles.isolatedLiquidationOracle({
    side: 'LONG',
    quantity: '1',
    entryPrice: '100',
    markPrice: '100',
    marginHeld: '1.05',
    maintenanceMarginRate: '0.01',
    rules: BTC_RULES
  }).liquidatable, true)
})

test('financial oracle review: liquidation compares backend-rounded money intermediates', () => {
  const position = {
    side: 'LONG',
    quantity: '0.0001',
    entryPrice: '50225.00985',
    markPrice: '50000.00995',
    maintenanceMarginRate: '0.005',
    rules: BTC_RULES
  }
  const isolated = financialOracles.isolatedLiquidationOracle({
    ...position,
    marginHeld: '0.05'
  })
  assert.deepEqual({
    unrealizedPnl: isolated.unrealizedPnl,
    isolatedEquity: isolated.isolatedEquity,
    maintenanceMargin: isolated.maintenanceMargin,
    estimatedCloseTakerFee: isolated.estimatedCloseTakerFee,
    isolatedThreshold: isolated.isolatedThreshold,
    liquidatable: isolated.liquidatable
  }, {
    unrealizedPnl: '-0.02249999',
    isolatedEquity: '0.02750001',
    maintenanceMargin: '0.02500001',
    estimatedCloseTakerFee: '0.00250000',
    isolatedThreshold: '0.02750001',
    liquidatable: true
  })

  const cross = financialOracles.crossLiquidationOracle({
    perpBalance: '0.05',
    positions: [position]
  })
  assert.deepEqual({
    crossEquity: cross.crossEquity,
    crossMaintenance: cross.crossMaintenance,
    estimatedCloseTakerFees: cross.estimatedCloseTakerFees,
    crossThreshold: cross.crossThreshold,
    liquidatable: cross.liquidatable
  }, {
    crossEquity: '0.02750001',
    crossMaintenance: '0.02500001',
    estimatedCloseTakerFees: '0.00250000',
    crossThreshold: '0.02750001',
    liquidatable: true
  })
})

test('financial oracle review: cross validates each position with its own symbol rules', () => {
  const altRules = {
    tickSize: '0.1',
    stepSize: '1',
    minQty: '1',
    contractSize: '1',
    contractMultiplier: '1'
  }
  const cross = financialOracles.crossLiquidationOracle({
    perpBalance: '100',
    positions: [{
      side: 'LONG',
      quantity: '0.0001',
      entryPrice: '50000',
      markPrice: '50000',
      maintenanceMarginRate: '0.005',
      rules: BTC_RULES
    }, {
      side: 'SHORT',
      quantity: '2',
      entryPrice: '100',
      markPrice: '90',
      maintenanceMarginRate: '0.01',
      rules: altRules
    }]
  })
  assert.deepEqual(cross, {
    crossEquity: '120.00000000',
    crossMaintenance: '1.82500000',
    estimatedCloseTakerFees: '0.09250000',
    crossThreshold: '1.91750000',
    liquidatable: false,
    tolerances: [{
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }, {
      price: '0.05',
      quantity: '0.5',
      amount: '0.000000005'
    }]
  })
  assert.throws(
    () => financialOracles.crossLiquidationOracle({
      perpBalance: '100',
      positions: [{
        side: 'LONG',
        quantity: '1',
        entryPrice: '100',
        markPrice: '100',
        maintenanceMarginRate: '0.01'
      }],
      rules: BTC_RULES
    }),
    /positions\[0\]\.rules/
  )
})

test('financial oracle: cross boundary is account-wide and liquidation shortfall includes unpaid fee', () => {
  assert.equal(typeof financialOracles.crossLiquidationOracle, 'function')
  const cross = (markPrice) => financialOracles.crossLiquidationOracle({
    perpBalance: '20',
    isolatedPrincipal: '10',
    positions: [{
      side: 'LONG',
      quantity: '1',
      entryPrice: '100',
      markPrice,
      maintenanceMarginRate: '0.01',
      rules: BTC_RULES
    }]
  })
  assert.equal(cross('90.96').liquidatable, false)
  assert.equal(cross('90.95').liquidatable, true)
  assert.equal(financialOracles.crossLiquidationOracle({
    perpBalance: '1.05',
    positions: [{
      side: 'LONG',
      quantity: '1',
      entryPrice: '100',
      markPrice: '100',
      maintenanceMarginRate: '0.01',
      rules: BTC_RULES
    }]
  }).liquidatable, true)
  assert.deepEqual(cross('90.95'), {
    crossEquity: '0.95000000',
    crossMaintenance: '0.90950000',
    estimatedCloseTakerFees: '0.04547500',
    crossThreshold: '0.95497500',
    liquidatable: true,
    tolerances: [{
      price: '0.005',
      quantity: '0.00005',
      amount: '0.000000005'
    }]
  })
  assert.deepEqual(financialOracles.liquidationFeeOracle({
    filledQuantity: '1',
    executionPrice: '90.95',
    liquidationFeeRate: '0.002',
    collectionCapacity: '0.1',
    uncoveredCoreDebit: '0.05',
    rules: BTC_RULES
  }), {
    nominalLiquidationFee: '0.18190000',
    chargedLiquidationFee: '0.10000000',
    uncollectedLiquidationFee: '0.08190000',
    bankruptcyShortfall: '0.13190000',
    tolerance: '0.000000005'
  })
})

const EXPECTED_EXECUTION_MANIFEST = {
  'AUTH-01': {
    executionGroup: 'auth-session',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'AUTH-02': {
    executionGroup: 'auth-session',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'AUTH-03': {
    executionGroup: 'auth-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-01': {
    executionGroup: 'cat-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-02': {
    executionGroup: 'cat-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'CAT-03': {
    executionGroup: 'cat-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-01': {
    executionGroup: 'spot-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-02': {
    executionGroup: 'spot-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-03': {
    executionGroup: 'spot-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SPOT-04': {
    executionGroup: 'spot-04',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-05': {
    executionGroup: 'spot-05',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-06': {
    executionGroup: 'spot-06',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-07': {
    executionGroup: 'spot-07',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-08': {
    executionGroup: 'spot-08',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-09': {
    executionGroup: 'spot-09',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SPOT-10': {
    executionGroup: 'spot-10',
    requiredSubruns: [
      { id: 'desktop-limit-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-stop-wins', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'SPOT-11': {
    executionGroup: 'spot-11',
    requiredSubruns: [
      { id: 'desktop-validation-stale', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-recovery-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-01': {
    executionGroup: 'perp-01',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-02': {
    executionGroup: 'perp-02',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-target-mark', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-03': {
    executionGroup: 'perp-03',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-04': {
    executionGroup: 'perp-04',
    requiredSubruns: [
      { id: 'desktop-base', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-quote', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-contracts', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-05': {
    executionGroup: 'perp-05',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-06': {
    executionGroup: 'perp-06',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-07': {
    executionGroup: 'perp-07',
    requiredSubruns: [
      { id: 'desktop-over-reversal-replay', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-empty-reduce-only', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-oversized-reduce-only', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PERP-08': {
    executionGroup: 'perp-08',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-09': {
    executionGroup: 'perp-09',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'PERP-10': {
    executionGroup: 'perp-10',
    requiredSubruns: [
      { id: 'desktop-immediate-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-11': {
    executionGroup: 'perp-11',
    requiredSubruns: [
      { id: 'desktop-long-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-short-stop', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PERP-12': {
    executionGroup: 'perp-12',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'BATCH-01': {
    executionGroup: 'batch-01',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'BATCH-02': {
    executionGroup: 'batch-02',
    requiredSubruns: [
      { id: 'desktop-normal', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-partial-failure', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'PROT-01': {
    executionGroup: 'prot-01',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'PROT-02': {
    executionGroup: 'prot-02',
    requiredSubruns: [
      { id: 'desktop-long-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-long-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-03': {
    executionGroup: 'prot-03',
    requiredSubruns: [
      { id: 'desktop-short-take-profit', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-short-stop-loss', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-04': {
    executionGroup: 'prot-04',
    requiredSubruns: [
      { id: 'desktop-immediate', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-two-stage', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-cancel-resting', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'PROT-05': {
    executionGroup: 'prot-05',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'PROT-06': {
    executionGroup: 'prot-06',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'FUND-01': {
    executionGroup: 'fund-01',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-02': {
    executionGroup: 'fund-02',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-03': {
    executionGroup: 'fund-03',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'FUND-04': {
    executionGroup: 'fund-04',
    requiredSubruns: [{ id: 'desktop-funding-only', profile: 'FUNDING_ONLY', viewport: 'desktop' }]
  },
  'LIQ-01': {
    executionGroup: 'liq-01',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-02': {
    executionGroup: 'liq-02',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-03': {
    executionGroup: 'liq-03',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'LIQ-04': {
    executionGroup: 'liq-04',
    requiredSubruns: [{ id: 'desktop-liquidation-only', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }]
  },
  'WALLET-01': {
    executionGroup: 'wallet-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'WALLET-02': {
    executionGroup: 'wallet-02',
    requiredSubruns: [
      { id: 'desktop-availability-replay', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-fingerprint-conflict', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'LIFE-01': {
    executionGroup: 'life-01',
    requiredSubruns: [
      { id: 'desktop-pending-order', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-oco', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-perp-position-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'LIFE-02': {
    executionGroup: 'life-02',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'LIFE-03': {
    executionGroup: 'life-03',
    requiredSubruns: [{ id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
  },
  'SOURCE-01': {
    executionGroup: 'source-01',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'SOURCE-02': {
    executionGroup: 'source-02',
    requiredSubruns: [
      { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  },
  'SOURCE-03': {
    executionGroup: 'source-03',
    requiredSubruns: [
      { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
      { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'SOURCE-04': {
    executionGroup: 'source-04',
    requiredSubruns: [
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  },
  'RES-01': {
    executionGroup: 'res-01',
    requiredSubruns: [
      { id: 'desktop-market-order', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-pending-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-partial-close', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-full-close', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-transfer', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-reset', profile: 'UI_CORE', viewport: 'desktop' }
    ]
  },
  'RES-02': {
    executionGroup: 'res-02',
    requiredSubruns: [
      { id: 'desktop-fill-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-close-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-close-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'RES-03': {
    executionGroup: 'res-03',
    requiredSubruns: [
      { id: 'desktop-restart-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-restart-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
      { id: 'desktop-restart-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
    ]
  },
  'RES-04': {
    executionGroup: 'res-04',
    requiredSubruns: [{ id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' }]
  },
  'UI-01': {
    executionGroup: 'ui-01',
    requiredSubruns: [
      { id: 'desktop-core', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-target', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-core', profile: 'ORDER_TRIGGER', viewport: 'mobile' },
      { id: 'mobile-target', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  },
  'UI-02': {
    executionGroup: 'ui-02',
    requiredSubruns: [
      { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'mobile-ui-core', profile: 'UI_CORE', viewport: 'mobile' },
      { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  }
}

test('registry matches every case heading in the approved specification', () => {
  const specSource = readFileSync(specPath, 'utf8')
  const specIds = [...specSource.matchAll(
    /^### ((?:AUTH|CAT|SPOT|PERP|BATCH|PROT|FUND|LIQ|WALLET|LIFE|SOURCE|RES|UI)-\d{2})\b/gm
  )].map((match) => match[1])

  assert.equal(new Set(P0_CASES.map((item) => item.id)).size, 60)
  assert.deepEqual(P0_CASES.map((item) => item.id).toSorted(), specIds.toSorted())
  assert.deepEqual(countByPhase(P0_CASES), {
    'ui-core': 22,
    'order-trigger': 20,
    funding: 4,
    liquidation: 4,
    source: 4,
    resilience: 4,
    ui: 2
  })
})

test('literal execution manifest locks every case group and required subrun', () => {
  const actual = Object.fromEntries(P0_CASES.map((definition) => [definition.id, {
    executionGroup: definition.executionGroup,
    requiredSubruns: definition.requiredSubruns
  }]))

  assert.deepEqual(actual, EXPECTED_EXECUTION_MANIFEST)
})

test('every descriptor locks its profiles, viewports, authority and required subruns', () => {
  const validProfiles = new Set([
    'UI_CORE',
    'ORDER_TRIGGER',
    'FUNDING_ONLY',
    'LIQUIDATION_ONLY'
  ])
  const validViewports = new Set(['desktop', 'mobile'])

  for (const definition of P0_CASES) {
    assert.deepEqual(Object.keys(definition).toSorted(), [
      'authority',
      'executionGroup',
      'handlerId',
      'id',
      'phase',
      'profiles',
      'requiredSubruns',
      'viewports'
    ])
    assert.ok(definition.profiles.length > 0, `${definition.id} profiles`)
    assert.ok(definition.profiles.every((profile) => validProfiles.has(profile)))
    assert.ok(definition.viewports.length > 0, `${definition.id} viewports`)
    assert.ok(definition.viewports.every((viewport) => validViewports.has(viewport)))
    assert.ok(definition.executionGroup)
    assert.equal(
      definition.handlerId,
      `run${definition.id.split('-').map((part) => (
        part[0] + part.slice(1).toLowerCase()
      )).join('')}`
    )
    assert.ok(definition.requiredSubruns.length > 0, `${definition.id} requiredSubruns`)
    assert.equal(
      new Set(definition.requiredSubruns.map((subrun) => subrun.id)).size,
      definition.requiredSubruns.length,
      `${definition.id} duplicate requiredSubruns`
    )
    for (const subrun of definition.requiredSubruns) {
      assert.ok(definition.profiles.includes(subrun.profile), `${definition.id}/${subrun.id} profile`)
      assert.ok(definition.viewports.includes(subrun.viewport), `${definition.id}/${subrun.id} viewport`)
    }
    assert.ok(['none', 'whole-case', 'subruns'].includes(definition.authority.mode))
    assert.ok(definition.authority.subruns.every((subrunId) => (
      definition.requiredSubruns.some((subrun) => subrun.id === subrunId)
    )))
  }
})

test('cross-profile and responsive cases enumerate every required combination', () => {
  const byId = (id) => P0_CASES.find((definition) => definition.id === id)

  assert.deepEqual(byId('SOURCE-03').requiredSubruns, [
    { id: 'desktop-trade-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-trigger-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('RES-02').requiredSubruns, [
    { id: 'desktop-fill-cancel', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-protection', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-close-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('RES-03').requiredSubruns, [
    { id: 'desktop-restart-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-restart-funding', profile: 'FUNDING_ONLY', viewport: 'desktop' },
    { id: 'desktop-restart-liquidation', profile: 'LIQUIDATION_ONLY', viewport: 'desktop' }
  ])
  assert.deepEqual(byId('UI-01').requiredSubruns, [
    { id: 'desktop-core', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'desktop-target', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-core', profile: 'ORDER_TRIGGER', viewport: 'mobile' },
    { id: 'mobile-target', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
  assert.deepEqual(byId('UI-02').requiredSubruns, [
    { id: 'desktop-ui-core', profile: 'UI_CORE', viewport: 'desktop' },
    { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-ui-core', profile: 'UI_CORE', viewport: 'mobile' },
    { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
})

test('SOURCE-04 requires desktop and mobile source-transition evidence', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-04')

  assert.deepEqual(definition.viewports, ['desktop', 'mobile'])
  assert.deepEqual(definition.requiredSubruns, [
    { id: 'desktop-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
    { id: 'mobile-order-trigger', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
  ])
})

test('phase, profile, viewport and authority assignments match the approved matrix', () => {
  const ids = (prefix, numbers) => numbers.map((number) => (
    `${prefix}-${String(number).padStart(2, '0')}`
  ))
  const range = (start, end) => Array.from(
    { length: end - start + 1 },
    (_, index) => start + index
  )
  const expectedByPhase = {
    'ui-core': [
      ...ids('AUTH', range(1, 3)),
      ...ids('CAT', range(1, 3)),
      ...ids('SPOT', range(1, 3)),
      ...ids('PERP', [...range(1, 9), 12]),
      'BATCH-02',
      'WALLET-01',
      'LIFE-02'
    ],
    'order-trigger': [
      ...ids('SPOT', range(4, 11)),
      'PERP-10',
      'PERP-11',
      'BATCH-01',
      ...ids('PROT', range(1, 6)),
      'WALLET-02',
      'LIFE-01',
      'LIFE-03'
    ],
    funding: ids('FUND', range(1, 4)),
    liquidation: ids('LIQ', range(1, 4)),
    source: ids('SOURCE', range(1, 4)),
    resilience: ids('RES', range(1, 4)),
    ui: ids('UI', range(1, 2))
  }
  for (const [phase, expectedIds] of Object.entries(expectedByPhase)) {
    assert.deepEqual(
      P0_CASES.filter((definition) => definition.phase === phase).map(({ id }) => id),
      expectedIds
    )
  }

  const profileOverrides = {
    'SOURCE-01': ['UI_CORE'],
    'SOURCE-02': ['UI_CORE', 'ORDER_TRIGGER'],
    'SOURCE-03': ['UI_CORE', 'ORDER_TRIGGER', 'FUNDING_ONLY', 'LIQUIDATION_ONLY'],
    'SOURCE-04': ['ORDER_TRIGGER'],
    'RES-01': ['UI_CORE', 'ORDER_TRIGGER'],
    'RES-02': ['ORDER_TRIGGER', 'LIQUIDATION_ONLY'],
    'RES-03': ['ORDER_TRIGGER', 'FUNDING_ONLY', 'LIQUIDATION_ONLY'],
    'RES-04': ['UI_CORE'],
    'UI-01': ['ORDER_TRIGGER'],
    'UI-02': ['UI_CORE', 'ORDER_TRIGGER']
  }
  const profileByPhase = {
    'ui-core': ['UI_CORE'],
    'order-trigger': ['ORDER_TRIGGER'],
    funding: ['FUNDING_ONLY'],
    liquidation: ['LIQUIDATION_ONLY']
  }
  for (const definition of P0_CASES) {
    assert.deepEqual(
      definition.profiles,
      profileOverrides[definition.id] ?? profileByPhase[definition.phase]
    )
    assert.deepEqual(
      definition.viewports,
      definition.phase === 'ui' || definition.id === 'SOURCE-04'
        ? ['desktop', 'mobile']
        : ['desktop']
    )
  }

  const wholeCaseAuthority = new Set([
    ...ids('SPOT', range(5, 10)),
    'PERP-04', 'PERP-05', 'PERP-11',
    'PROT-02', 'PROT-03', 'PROT-04',
    ...ids('LIQ', range(1, 4)),
    'RES-02'
  ])
  const subrunAuthority = {
    'SPOT-11': ['desktop-recovery-trigger'],
    'PERP-01': ['desktop-target-mark'],
    'PERP-02': ['desktop-target-mark'],
    'PERP-10': ['desktop-trigger'],
    'SOURCE-03': ['desktop-trigger-order-trigger', 'desktop-liquidation'],
    'RES-03': ['desktop-restart-order-trigger', 'desktop-restart-liquidation'],
    'UI-01': ['desktop-target', 'mobile-target']
  }
  for (const definition of P0_CASES) {
    const expected = subrunAuthority[definition.id]
      ? { mode: 'subruns', subruns: subrunAuthority[definition.id] }
      : { mode: wholeCaseAuthority.has(definition.id) ? 'whole-case' : 'none', subruns: [] }
    assert.deepEqual(definition.authority, expected)
  }
  assert.equal(P0_CASES.find(({ id }) => id === 'AUTH-01').executionGroup, 'auth-session')
  assert.equal(P0_CASES.find(({ id }) => id === 'AUTH-02').executionGroup, 'auth-session')
})

test('registry initialization ignores inherited lookup-table values', () => {
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const expected = JSON.stringify({
    definitions: P0_CASES,
    fingerprint: P0_REGISTRY_FINGERPRINT
  })
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { readFileSync } from 'node:fs'

      const expected = JSON.parse(readFileSync(0, 'utf8'))
      const importUrl = new URL(${JSON.stringify(casesUrl)})
      importUrl.searchParams.set('review15-registry-inherited', '1')
      const inheritedCaseId = 'AUTH-01'
      const inheritedLookup = ['REVIEW15_INHERITED_LOOKUP']
      const originalDescriptor = Object.getOwnPropertyDescriptor(
        Object.prototype,
        inheritedCaseId
      )
      let getterCalls = 0
      let imported
      let importError

      try {
        Object.defineProperty(Object.prototype, inheritedCaseId, {
          configurable: true,
          get() {
            getterCalls += 1
            return inheritedLookup
          }
        })
        imported = await import(importUrl)
      } catch (error) {
        importError = error
      } finally {
        if (originalDescriptor) {
          Object.defineProperty(Object.prototype, inheritedCaseId, originalDescriptor)
        } else {
          delete Object.prototype[inheritedCaseId]
        }
      }

      assert.deepEqual(
        Object.getOwnPropertyDescriptor(Object.prototype, inheritedCaseId),
        originalDescriptor
      )
      if (importError) throw importError
      assert.equal(getterCalls, 0)
      assert.equal(imported.P0_CASES.length, 60)
      assert.deepEqual(imported.P0_CASES, expected.definitions)
      assert.equal(imported.P0_REGISTRY_FINGERPRINT, expected.fingerprint)
      for (let index = 0; index < expected.definitions.length; index += 1) {
        const actual = imported.P0_CASES[index]
        const clean = expected.definitions[index]
        assert.deepEqual(Reflect.ownKeys(actual), Reflect.ownKeys(clean), actual.id)
        assert.equal(Object.hasOwn(actual, inheritedCaseId), false, actual.id)
        assert.equal(Object.hasOwn(actual, actual.phase), false, actual.id)
      }
    `
  ], {
    encoding: 'utf8',
    input: expected
  })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('dispatch invokes the declared handler and fails fast when it is absent', async () => {
  const definition = P0_CASES[0]
  const context = { runId: 'contract-run' }
  const expected = { status: 'PASS' }
  const handlers = {
    [definition.handlerId]: async (received) => {
      assert.equal(received, context)
      return expected
    }
  }

  assert.equal(await runCase(definition, context, handlers), expected)
  await assert.rejects(
    runCase(definition, context, {}),
    new RegExp(`^Error: INCOMPLETE_MATRIX: ${definition.id}$`)
  )
})

test('P0Context locks the real runtime interfaces used by detailed case modules', () => {
  const noop = () => {}
  const input = {
    run: {
      runId: 'p0-context-contract-a1',
      mode: 'DISCOVERY',
      commit: 'a'.repeat(40),
      artifactRoot: 'C:\\p0-context-contract'
    },
    ui: {
      launchBrowser: noop,
      createEvidencePage: noop,
      registerViaUi: noop,
      loginViaUi: noop,
      loginAdminViaUi: noop,
      logoutViaUi: noop,
      openTradePanel: noop,
      withCapturedMutation: noop,
      submitOrderViaUi: noop,
      acceptNextNativeDialog: noop,
      followLoginPromptViaUi: noop,
      cancelAllOrdersViaUi: noop
    },
    api: {
      user: noop,
      admin: noop,
      snapshotAccount: noop,
      snapshotMarket: noop
    },
    db: {
      query: noop,
      snapshotTradingRows: noop,
      assertDedicatedDatabase: noop
    },
    events: {
      waitForStompEvent: noop,
      snapshotFrames: noop,
      probeForbiddenSubscription: noop
    },
    services: {
      ensureProfile: noop,
      restartBackend: noop,
      assertOwnedPorts: noop
    },
    fixtures: {
      marketOverride: noop,
      providerBindings: noop,
      fundingConfig: noop,
      positionTime: noop,
      kline: noop
    },
    evidence: {
      captureCheckpoint: noop,
      writeCaseResultAtomic: noop
    },
    userFactory: noop
  }

  const context = p0CaseContracts.createP0Context(input)
  assert.notEqual(context, input)
  assert.equal(context.run.runId, input.run.runId)
  assert.equal(context.ui.withCapturedMutation, noop)
  assert.equal(context.evidence.captureCheckpoint, noop)
  assert.equal(context.userFactory, noop)
  assert.equal(Object.isFrozen(context), true)
  assert.throws(
    () => p0CaseContracts.createP0Context({
      ...input,
      ui: { ...input.ui, withCapturedMutation: undefined }
    }),
    /P0_CONTEXT_INTERFACE_REQUIRED: ui\.withCapturedMutation/
  )
  for (const method of [
    'launchBrowser',
    'logoutViaUi',
    'followLoginPromptViaUi',
    'cancelAllOrdersViaUi'
  ]) {
    assert.throws(
      () => p0CaseContracts.createP0Context({
        ...input,
        ui: { ...input.ui, [method]: undefined }
      }),
      new RegExp(`P0_CONTEXT_INTERFACE_REQUIRED: ui\\.${method}`)
    )
  }
  assert.throws(
    () => p0CaseContracts.createP0Context({
      ...input,
      events: { ...input.events, probeForbiddenSubscription: undefined }
    }),
    /P0_CONTEXT_INTERFACE_REQUIRED: events\.probeForbiddenSubscription/
  )
})

test('AUTH handlers are real owned dispatch targets and AUTH-03 launches two browser processes', () => {
  assert.deepEqual(Object.keys(p0CoreContracts.CASE_HANDLERS).toSorted(), [
    'runAuth01',
    'runAuth02',
    'runAuth03'
  ])
  for (const id of ['AUTH-01', 'AUTH-02', 'AUTH-03']) {
    const definition = P0_CASES.find((candidate) => candidate.id === id)
    assert.equal(typeof p0CoreContracts.CASE_HANDLERS[definition.handlerId], 'function')
  }

  const corePath = fileURLToPath(new URL('./p0-user-trading-core-cases.mjs', import.meta.url))
  const coreSource = readFileSync(corePath, 'utf8')
  const auth03Source = coreSource.slice(
    coreSource.indexOf('export async function runAuth03'),
    coreSource.indexOf('export const CASE_HANDLERS')
  )
  assert.doesNotMatch(coreSource, /smoke-usdt-demo-browser/)
  assert.doesNotMatch(coreSource, /installBrowserSession/)
  assert.doesNotMatch(coreSource, /fx-trade-confirm-skip/)
  assert.doesNotMatch(coreSource, /Fetch\.(?:enable|requestPaused|fulfillRequest)/)
  assert.doesNotMatch(coreSource, /(?:^|[^\\w-])\.trade-panel/)
  assert.equal(
    [...auth03Source.matchAll(/context\.ui\.launchBrowser\(\)/g)].length,
    2,
    'AUTH-03 must start two independent browser processes'
  )
  assert.match(
    auth03Source,
    /context\.events\.probeForbiddenSubscription\([\s\S]*\/topic\/trading\/accounts\//
  )
})

test('default P0 dispatch builds one context and injects the owned AUTH handlers', () => {
  const smokePath = fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url))
  const smokeSource = readFileSync(smokePath, 'utf8')

  assert.match(smokeSource, /createP0Context/)
  assert.match(smokeSource, /CASE_HANDLERS/)
  assert.match(smokeSource, /const p0Context = dependencies\.createP0Context/)
  assert.match(smokeSource, /dispatchCase\(definition, caseContext, handlers/)
})

test('default P0 DB oracle follows the active profile database', async (t) => {
  const artifactBase = mkdtempSync(join(tmpdir(), 'p0-active-profile-database-'))
  t.after(() => rmSync(artifactBase, { recursive: true, force: true }))
  const matrixDatabase = 'fx_p0_user_e2e_matrix_oracle_a1'
  const phaseDatabase = 'fx_p0_user_e2e_ui_core_oracle_a1'
  const queriedDatabases = []
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    infrastructure: {},
    redis: {},
    postgres: {
      async queryDatabase(database, sql) {
        queriedDatabases.push({ database, sql })
        return sql === 'SELECT current_database();' ? database : 'ok'
      }
    }
  })
  const prepared = {
    matrixDatabase,
    identity: { commit: 'c'.repeat(40) },
    runRoot: artifactBase
  }
  const context = dependencies.createP0Context(prepared, {
    runId: 'p0-active-profile-database-a1',
    mode: 'discovery'
  })

  assert.deepEqual(context.authority, {
    status: 'PENDING',
    authorityBundleFixture: 'BLOCKED'
  })
  await context.db.query('SELECT 1;')
  prepared.activeDatabaseSegment = phaseDatabase
  await context.db.query('SELECT 2;')
  assert.equal(await context.db.assertDedicatedDatabase(), phaseDatabase)
  assert.deepEqual(
    queriedDatabases.map(({ database }) => database),
    [matrixDatabase, phaseDatabase, phaseDatabase]
  )
})

test('default P0 browser launch journals native identity before readiness', async (t) => {
  const artifactBase = mkdtempSync(join(tmpdir(), 'p0-owned-browser-journal-'))
  t.after(() => rmSync(artifactBase, { recursive: true, force: true }))
  const runId = 'p0-owned-browser-journal-a1'
  const runToken = 'p0-owned-browser-journal-owner-token-a1'
  const matrixDatabase = 'fx_p0_user_e2e_browser_matrix_1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: {
      caseIds: ['AUTH-01'],
      phases: [],
      profiles: [],
      viewports: []
    },
    database: {
      canonical: 'fx_p0_user_e2e_browser_canonical_1',
      matrix: matrixDatabase
    }
  })
  const states = []
  const browser = {
    pid: 43121,
    processIdentity: {
      pid: 43121,
      startedAt: '2026-07-23T14:00:00.000Z',
      processFingerprint: `sha256:${'b'.repeat(64)}`
    },
    async close() {}
  }
  const ownership = () => JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    postgres: { queryDatabase() { throw new Error('P0_TEST_DB_NOT_EXPECTED') } },
    p0Ui: {
      async launchBrowser({ onSpawn }) {
        states.push(ownership().journal.resources.at(-1)?.state)
        await onSpawn(browser)
        states.push(ownership().journal.resources.at(-1)?.state)
        return browser
      }
    }
  })
  const context = dependencies.createP0Context({
    artifactBase,
    ownerToken: runToken,
    ownerId: control.ownerId,
    matrixDatabase,
    identity: { commit: 'c'.repeat(40) },
    runRoot: control.runRoot
  }, {
    runId,
    mode: 'discovery'
  })

  assert.equal(await context.ui.launchBrowser(), browser)
  assert.deepEqual(states, ['PLANNED', 'STARTED'])
  const resource = ownership().journal.resources.at(-1)
  assert.equal(resource.type, 'process')
  assert.equal(resource.id, 'browser:1')
  assert.equal(resource.live, true)
  assert.equal(resource.state, 'STARTED')
  assert.equal(resource.pid, browser.pid)
  assert.equal(resource.processFingerprint, browser.processIdentity.processFingerprint)
})

test('AUTH-02 fails closed when a login cycle changes account continuity', async () => {
  const definition = P0_CASES.find(({ id }) => id === 'AUTH-02')
  const beforeSnapshot = authAccountSnapshot({
    accountId: '11111111-1111-4111-8111-111111111111'
  })
  const afterSnapshot = structuredClone(beforeSnapshot)
  afterSnapshot.wallets[0].available = 49999
  afterSnapshot.trades.push({ id: 'unexpected-trade' })
  const snapshots = [beforeSnapshot, afterSnapshot]
  const page = authEvidencePage('AUTH-02')
  const context = {
    run: authRun('p0-auth-02-continuity-a1'),
    userFactory() {
      return { email: 'member@example.test', password: 'Password123!' }
    },
    ui: {
      async launchBrowser() { return { async close() {} } },
      async createEvidencePage() { return page },
      async loginViaUi(_page, _credentials, options = {}) {
        return options.expectFailure
          ? { authenticated: false, requestRef: 'wrong-password' }
          : { authenticated: true, requestRef: 'login' }
      },
      async logoutViaUi() { return { requestRef: 'logout' } },
      async openTradePanel() {},
      async submitOrderViaUi() {
        return { loginRequired: true, requestRef: null }
      },
      async followLoginPromptViaUi() {
        return {
          path: '/login',
          redirect: '/trade/perpetual/BTCUSDT-PERP'
        }
      }
    },
    api: {
      async snapshotAccount() { return snapshots.shift() }
    },
    db: {
      async assertDedicatedDatabase() {
        return 'fx_p0_user_e2e_auth_02_continuity_a1'
      },
      async snapshotTradingRows() {
        return {
          database: 'fx_p0_user_e2e_auth_02_continuity_a1',
          activeDemoAccounts: 1,
          orders: 0,
          trades: 0,
          openPositions: 0,
          fundingSettlements: 0
        }
      }
    },
    evidence: authEvidenceSink()
  }

  await assert.rejects(
    p0CoreContracts.runAuth02(context, definition),
    /AUTH-02 session continuity/
  )
})

test('AUTH-03 fails closed when USER_B owns trading rows in the active database', async () => {
  const definition = P0_CASES.find(({ id }) => id === 'AUTH-03')
  const accountA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const accountB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  const database = 'fx_p0_user_e2e_auth_03_isolation_a1'
  const context = auth03Context({
    accountA,
    accountB,
    database,
    dbB: {
      database,
      activeDemoAccounts: 1,
      orders: 0,
      trades: 1,
      openPositions: 0,
      fundingSettlements: 0
    }
  })

  await assert.rejects(
    p0CoreContracts.runAuth03(context, definition),
    /AUTH-03 USER_B database isolation/
  )
})

test('AUTH-03 fails closed when USER_B Trades UI renders another user row', async () => {
  const definition = P0_CASES.find(({ id }) => id === 'AUTH-03')
  const accountA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const accountB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  const database = 'fx_p0_user_e2e_auth_03_ui_isolation_a1'
  const context = auth03Context({
    accountA,
    accountB,
    database,
    uiBRows: {
      '/orders': [0, 0, 1],
      '/positions': [0, 0]
    },
    dbB: {
      database,
      activeDemoAccounts: 1,
      orders: 0,
      trades: 0,
      openPositions: 0,
      fundingSettlements: 0
    }
  })

  await assert.rejects(
    p0CoreContracts.runAuth03(context, definition),
    /AUTH-03 USER_B UI isolation requires zero TRADES rows/
  )
})

test('AUTH fragments satisfy the merge contract and hash every checkpoint screenshot', async (t) => {
  const artifactRoot = mkdtempSync(join(tmpdir(), 'p0-auth-fragment-contract-'))
  t.after(() => rmSync(artifactRoot, { recursive: true, force: true }))
  const definition = P0_CASES.find(({ id }) => id === 'AUTH-01')
  let persisted
  let screenshotSequence = 0
  const page = {
    p0Options: { webBaseUrl: 'http://127.0.0.1:5199' },
    async navigate() {},
    async waitForFunction() {},
    async evaluate() { return 1 },
    async send(method) {
      if (method === 'Page.captureScreenshot') {
        screenshotSequence += 1
        return {
          data: Buffer.from(`auth-checkpoint-${screenshotSequence}`).toString('base64')
        }
      }
      return {}
    },
    assertEvidenceClean() {},
    snapshotEvidence() {
      return {
        networkEvidence: [],
        eventEvidence: [],
        consoleErrors: []
      }
    },
    async close() {}
  }
  const accountSnapshot = {
    account: {
      id: '11111111-1111-4111-8111-111111111111',
      accountType: 'DEMO',
      status: 'ACTIVE'
    },
    wallets: [{
      walletType: 'SPOT',
      asset: 'USDT',
      total: 50000,
      available: 50000,
      locked: 0
    }],
    summary: {
      balance: 50000,
      equity: 50000,
      freeMargin: 50000,
      usedMargin: 0
    },
    settings: {
      positionMode: 'ONE_WAY',
      symbols: [{
        symbol: 'BTCUSDT-PERP',
        marginMode: 'CROSS',
        leverage: 10,
        quantityUnit: 'BASE'
      }]
    },
    orders: [],
    trades: [],
    positions: [],
    fundingSettlements: []
  }
  const dbSnapshot = {
    activeDemoAccounts: 1,
    orders: 0,
    trades: 0,
    openPositions: 0,
    fundingSettlements: 0
  }
  const context = {
    run: {
      runId: 'p0-auth-fragment-contract-a1',
      commit: 'a'.repeat(40),
      artifactRoot
    },
    userFactory() {
      return { email: 'member@example.test', password: 'Password123!' }
    },
    ui: {
      async launchBrowser() { return { async close() {} } },
      async createEvidencePage() { return page },
      async registerViaUi() { return { requestRef: '101.1' } },
      async openTradePanel() {}
    },
    api: {
      async snapshotAccount() { return accountSnapshot },
      async snapshotMarket() { return { quote: { last: 60000 } } }
    },
    db: {
      async assertDedicatedDatabase() { return 'fx_p0_user_e2e_auth_fragment_a1' },
      async snapshotTradingRows() { return dbSnapshot }
    },
    evidence: {
      captureCheckpoint: smokeContracts.captureCheckpoint,
      writeCaseResultAtomic(_path, result) { persisted = result }
    }
  }

  const result = await p0CoreContracts.runAuth01(context, definition, {
    attempt: 2,
    profileAttempt: 3
  })
  assert.equal(result.schemaVersion, 1)
  assert.equal(result.attempt, 2)
  assert.equal(result.scopeComplete, true)
  assert.equal(Number.isFinite(result.durationMs) && result.durationMs >= 0, true)
  assert.deepEqual(Object.keys(result.artifactHashes).toSorted(), [
    'AUTH-01/before.png',
    'AUTH-01/final.png',
    'AUTH-01/submitted.png'
  ])
  for (const [path, hash] of Object.entries(result.artifactHashes)) {
    const bytes = readFileSync(join(artifactRoot, ...path.split('/')))
    const expected = `sha256:${createHash('sha256').update(bytes).digest('hex')}`
    assert.equal(hash, expected)
  }
  assert.deepEqual(result.subruns, [{
    ...definition.requiredSubruns[0],
    status: 'PASS',
    attempt: 2,
    profileAttempt: 3,
    durationMs: result.durationMs,
    artifactHashes: result.artifactHashes
  }])
  assert.equal(persisted, result)
  assert.doesNotThrow(() => smokeContracts.mergeP0CaseFragments({
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }, [result]))
})

test('AUTH failure fragments retain the formal terminal fields', async () => {
  const definition = P0_CASES.find(({ id }) => id === 'AUTH-01')
  let persisted
  let persistedPath
  const context = {
    run: {
      runId: 'p0-auth-failure-fragment-a1',
      commit: 'b'.repeat(40),
      artifactRoot: resolve(tmpdir(), 'p0-auth-failure-fragment-a1')
    },
    userFactory() {
      return { email: 'member@example.test', password: 'Password123!' }
    },
    ui: {
      async launchBrowser() { throw new Error('AUTH_BROWSER_START_FAILED') }
    },
    evidence: {
      writeCaseResultAtomic(path, result) {
        persistedPath = path
        persisted = result
      }
    }
  }

  await assert.rejects(
    p0CoreContracts.runAuth01(context, definition, {
      attempt: 4,
      profileAttempt: 5
    }),
    /AUTH_BROWSER_START_FAILED/
  )
  assert.equal(persisted.schemaVersion, 1)
  assert.equal(persisted.attempt, 4)
  assert.equal(persisted.scopeComplete, true)
  assert.equal(Number.isFinite(persisted.durationMs) && persisted.durationMs >= 0, true)
  assert.deepEqual(persisted.artifactHashes, {})
  assert.deepEqual(persisted.subruns, [{
    ...definition.requiredSubruns[0],
    status: 'FAIL',
    attempt: 4,
    profileAttempt: 5,
    durationMs: persisted.durationMs,
    artifactHashes: {}
  }])
  assert.equal(
    persistedPath,
    join(context.run.artifactRoot, 'AUTH-01', 'result.json')
  )
})

function authRun(runId) {
  return {
    runId,
    commit: 'a'.repeat(40),
    artifactRoot: resolve(tmpdir(), runId)
  }
}

function authEvidencePage(role) {
  return {
    role,
    p0Options: { webBaseUrl: 'http://127.0.0.1:5199' },
    async navigate() {},
    async waitForFunction() {},
    async evaluate() { return false },
    assertEvidenceClean() {},
    async close() {}
  }
}

function authEvidenceSink() {
  return {
    async captureCheckpoint(_context, name) {
      return {
        name,
        artifactHashes: {},
        uiEvidence: [],
        networkEvidence: [],
        eventEvidence: []
      }
    },
    writeCaseResultAtomic() {}
  }
}

function authAccountSnapshot({
  accountId,
  orders = [],
  trades = [],
  positions = [],
  fundingSettlements = []
}) {
  return {
    accounts: [],
    activeDemoAccounts: [],
    account: {
      id: accountId,
      accountType: 'DEMO',
      status: 'ACTIVE'
    },
    wallets: [{
      walletType: 'SPOT',
      asset: 'USDT',
      total: 50000,
      available: 50000,
      locked: 0
    }],
    summary: {
      balance: 50000,
      equity: 50000,
      freeMargin: 50000,
      usedMargin: 0
    },
    settings: {
      positionMode: 'ONE_WAY',
      symbols: [{
        symbol: 'BTCUSDT-PERP',
        marginMode: 'CROSS',
        leverage: 10,
        quantityUnit: 'BASE'
      }]
    },
    orders,
    trades,
    positions,
    fundingSettlements
  }
}

function auth03Context({
  accountA,
  accountB,
  database,
  dbB,
  uiBRows = {
    '/orders': [0, 0, 0],
    '/positions': [0, 0]
  }
}) {
  const pageA = authEvidencePage('AUTH-03-A')
  const pageB = authEvidencePage('AUTH-03-B')
  pageB.evaluate = async (fn, ...args) => {
    if (fn.name === 'clickIsolationTab') return true
    if (fn.name === 'readIsolationTable') {
      const [route, view, index] = args
      const dataRows = uiBRows[route][index]
      return {
        route,
        view,
        dataRows,
        emptyRows: dataRows === 0 ? 1 : 0
      }
    }
    if (fn.name === 'readIsolationWallet') {
      return {
        route: '/wallet',
        balance: '50000',
        freeMargin: '50000',
        leaked: false
      }
    }
    return false
  }
  const browserA = { role: 'AUTH-03-A', async close() {} }
  const browserB = { role: 'AUTH-03-B', async close() {} }
  const browsers = [browserA, browserB]
  const initialA = authAccountSnapshot({ accountId: accountA })
  const afterA = authAccountSnapshot({
    accountId: accountA,
    orders: [{ id: 'market-a' }, { id: 'limit-a' }],
    trades: [{ id: 'trade-a' }]
  })
  const snapshotByPage = new Map([
    [pageA, [initialA, afterA]],
    [pageB, [
      authAccountSnapshot({ accountId: accountB }),
      authAccountSnapshot({ accountId: accountB })
    ]]
  ])
  return {
    run: authRun('p0-auth-03-isolation-a1'),
    userFactory(role) {
      return {
        email: `${role.toLowerCase()}@example.test`,
        password: 'Password123!'
      }
    },
    ui: {
      async launchBrowser() { return browsers.shift() },
      async createEvidencePage(browser) {
        return browser.role === 'AUTH-03-A' ? pageA : pageB
      },
      async registerViaUi(page) { return { requestRef: `register-${page.role}` } },
      async logoutViaUi(page) { return { requestRef: `logout-${page.role}` } },
      async loginViaUi(page) { return { requestRef: `login-${page.role}` } },
      async openTradePanel() {},
      async submitOrderViaUi(_page, order) {
        return { requestRef: `submit-${order.orderType.toLowerCase()}` }
      },
      async cancelAllOrdersViaUi() { return { requestRef: 'cancel-all' } }
    },
    api: {
      async snapshotAccount(page) {
        return snapshotByPage.get(page).shift()
      },
      async snapshotMarket() {
        return { quote: { bid: 60000 } }
      },
      async user() {
        const error = new Error('forbidden')
        error.status = 403
        error.code = 'ACCOUNT_ACCESS_DENIED'
        throw error
      }
    },
    db: {
      async assertDedicatedDatabase() { return database },
      async snapshotTradingRows(accountId) {
        if (accountId === accountB) return dbB
        return {
          database,
          activeDemoAccounts: 1,
          orders: 2,
          trades: 1,
          openPositions: 0,
          fundingSettlements: 0
        }
      }
    },
    events: {
      async waitForStompEvent() {},
      snapshotFrames() { return [] },
      async probeForbiddenSubscription() {
        return { status: 'REJECTED' }
      }
    },
    evidence: authEvidenceSink()
  }
}

test('dispatch and phase counting ignore inherited values', async () => {
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'

      const { P0_CASES, countByPhase, runCase } = await import(${JSON.stringify(casesUrl)})
      const definition = P0_CASES[0]
      const context = { runId: 'own-handler-contract' }
      const expected = { status: 'PASS' }
      let ownCalls = 0
      const ownHandlers = {
        [definition.handlerId]: async (received) => {
          ownCalls += 1
          assert.equal(received, context)
          return expected
        }
      }
      assert.equal(await runCase(definition, context, ownHandlers), expected)
      assert.equal(ownCalls, 1)

      for (const variant of ['value', 'getter']) {
        let inheritedCalls = 0
        let error
        let result
        const inheritedHandler = async () => {
          inheritedCalls += 1
          return { status: 'PASS' }
        }
        Object.defineProperty(Object.prototype, definition.handlerId, variant === 'getter'
          ? {
              configurable: true,
              get() {
                inheritedCalls += 1
                return inheritedHandler
              }
            }
          : { configurable: true, value: inheritedHandler })
        try {
          try {
            result = await runCase(definition, context, {})
          } catch (caught) {
            error = caught.message
          }
        } finally {
          delete Object.prototype[definition.handlerId]
        }
        assert.equal(result, undefined, variant)
        assert.equal(error, 'INCOMPLETE_MATRIX: ' + definition.id, variant)
        assert.equal(inheritedCalls, 0, variant)
      }

      const expectedCounts = {
        'ui-core': 22,
        'order-trigger': 20,
        funding: 4,
        liquidation: 4,
        source: 4,
        resilience: 4,
        ui: 2
      }
      let phaseGetterCalls = 0
      let counts
      let countError
      for (const phase of Object.keys(expectedCounts)) {
        Object.defineProperty(Object.prototype, phase, {
          configurable: true,
          get() {
            phaseGetterCalls += 1
            return 10_000
          }
        })
      }
      try {
        try {
          counts = countByPhase(P0_CASES)
        } catch (caught) {
          countError = caught.message
        }
      } finally {
        for (const phase of Object.keys(expectedCounts)) delete Object.prototype[phase]
      }
      assert.equal(countError, undefined)
      assert.deepEqual(counts, expectedCounts)
      assert.equal(phaseGetterCalls, 0)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
})

test('P0 CLI rejects invalid invocation before the first mutation', () => {
  assert.equal(typeof p0CaseContracts.parseP0Cli, 'function')
  assert.equal(typeof p0CaseContracts.resolveP0RunRoot, 'function')
  const parse = p0CaseContracts.parseP0Cli
  const generatedRunId = 'p0-discovery-20260715-000000-a1b2c3d4'
  let generationCalls = 0
  const generateRunId = () => {
    generationCalls += 1
    return generatedRunId
  }

  assert.deepEqual(parse([], { generateRunId }), {
    suite: 'canonical',
    mode: 'discovery',
    phase: 'canonical',
    runId: null,
    resume: null,
    caseIds: [],
    viewport: 'all',
    profile: null,
    list: false
  })
  assert.equal(generationCalls, 0)

  assert.deepEqual(parse(['--suite=p0'], { generateRunId }), {
    suite: 'p0',
    mode: 'discovery',
    phase: 'all',
    runId: generatedRunId,
    resume: null,
    caseIds: [],
    viewport: 'all',
    profile: null,
    list: false
  })
  assert.equal(generationCalls, 1)

  generationCalls = 0
  assert.deepEqual(parse([
    '--suite=p0',
    '--mode=discovery',
    '--phase=selected',
    '--run-id=p0-selected-a1',
    '--case=SPOT-01,LIQ-01',
    '--viewport=mobile',
    '--profile=LIQUIDATION_ONLY'
  ], { generateRunId }), {
    suite: 'p0',
    mode: 'discovery',
    phase: 'selected',
    runId: 'p0-selected-a1',
    resume: null,
    caseIds: ['SPOT-01', 'LIQ-01'],
    viewport: 'mobile',
    profile: 'LIQUIDATION_ONLY',
    list: false
  })
  assert.equal(generationCalls, 0)

  assert.deepEqual(parse([
    '--suite=p0',
    '--resume=p0-resume-a1',
    '--run-id=p0-resume-a1',
    '--phase=report'
  ], { generateRunId }), {
    suite: 'p0',
    mode: 'discovery',
    phase: 'report',
    runId: 'p0-resume-a1',
    resume: 'p0-resume-a1',
    caseIds: [],
    viewport: 'all',
    profile: null,
    list: false
  })

  const invalidInvocations = [
    ['--unknown=value'],
    ['--suite=p0', '--suite=p0'],
    ['--suite'],
    ['--suite=other'],
    ['--suite=p0', '--mode=other'],
    ['--suite=p0', '--phase=other'],
    ['--suite=p0', '--viewport=tablet'],
    ['--suite=p0', '--profile=ALL'],
    ['--suite=p0', '--phase=selected'],
    ['--suite=p0', '--phase=all', '--case=SPOT-01'],
    ['--suite=p0', '--phase=all', '--viewport=mobile'],
    ['--suite=p0', '--phase=all', '--profile=UI_CORE'],
    ['--suite=p0', '--case=UNKNOWN-01'],
    ['--suite=p0', '--run-id=../escape'],
    ['--suite=p0', '--run-id=..\\escape'],
    ['--suite=p0', '--run-id=C:escape'],
    ['--suite=p0', '--run-id=C:\\escape'],
    ['--suite=p0', '--run-id=\\\\server\\share'],
    ['--suite=p0', '--resume=p0-resume-a1', '--run-id=p0-other-a1'],
    ['--suite=p0', '--mode=certification', '--resume=p0-resume-a1'],
    ['--suite=p0', '--mode=certification', '--phase=selected', '--case=SPOT-01'],
    ['--suite=p0', '--mode=certification', '--phase=ui-core'],
    ['--suite=p0', '--phase=cleanup']
  ]
  for (const arguments_ of invalidInvocations) {
    generationCalls = 0
    assert.throws(
      () => parse(arguments_, { generateRunId }),
      /^Error: P0_CLI_/,
      arguments_.join(' ')
    )
    assert.equal(generationCalls, 0, arguments_.join(' '))
  }

  const artifactBase = resolve(tmpdir(), 'p0-contained-runs')
  assert.equal(
    p0CaseContracts.resolveP0RunRoot(artifactBase, 'p0-contained-a1'),
    join(artifactBase, 'p0-contained-a1')
  )
  for (const unsafe of ['.', '..', '../escape', '..\\escape', 'C:escape', 'C:\\escape', '\\\\server\\share']) {
    assert.throws(
      () => p0CaseContracts.resolveP0RunRoot(artifactBase, unsafe),
      /^Error: P0_RUN_ID_INVALID/,
      unsafe
    )
  }
})

test('P0 phase planner distinguishes selected partial all complete and cleanup verdict-free', () => {
  assert.equal(typeof p0CaseContracts.planP0Execution, 'function')
  const selectedOptions = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--run-id=p0-selected-plan-a1',
    '--case=SPOT-01,LIQ-01'
  ])
  const selected = p0CaseContracts.planP0Execution(selectedOptions, P0_CASES)

  assert.deepEqual(selected.phases, ['preflight', 'authority', 'selected', 'report', 'cleanup'])
  assert.deepEqual(selected.profiles, ['UI_CORE', 'LIQUIDATION_ONLY'])
  assert.deepEqual(selected.definitions.map(({ id }) => id), ['SPOT-01', 'LIQ-01'])
  assert.equal(selected.includesCanonical, false)
  assert.equal(selected.fullMatrix, false)
  assert.equal(selected.verdict, 'PARTIAL_PASS')

  const all = p0CaseContracts.planP0Execution(
    p0CaseContracts.parseP0Cli(['--suite=p0', '--run-id=p0-all-plan-a1']),
    P0_CASES
  )
  assert.deepEqual(all.phases, [
    'preflight',
    'canonical',
    'authority',
    'ui-core',
    'order-trigger',
    'funding',
    'liquidation',
    'source',
    'resilience',
    'ui',
    'report',
    'cleanup'
  ])
  assert.deepEqual(all.profiles, [
    'UI_CORE',
    'ORDER_TRIGGER',
    'FUNDING_ONLY',
    'LIQUIDATION_ONLY'
  ])
  assert.equal(all.definitions.length, 60)
  assert.equal(all.includesCanonical, true)
  assert.equal(all.fullMatrix, true)
  assert.equal(all.verdict, 'PASS')

  const cleanup = p0CaseContracts.planP0Execution(
    p0CaseContracts.parseP0Cli([
      '--suite=p0',
      '--mode=certification',
      '--phase=cleanup',
      '--run-id=p0-cleanup-plan-a1'
    ]),
    P0_CASES
  )
  assert.deepEqual(cleanup.phases, ['cleanup'])
  assert.deepEqual(cleanup.profiles, [])
  assert.deepEqual(cleanup.definitions, [])
  assert.equal(cleanup.includesCanonical, false)
  assert.equal(cleanup.fullMatrix, false)
  assert.equal(cleanup.verdict, null)
  assert.equal(cleanup.businessMutation, false)
})

test('canonical default and explicit suite dispatch the same import-safe journey', async () => {
  assert.equal(typeof smokeContracts.createSmokeMain, 'function')
  const calls = []
  const dispatch = smokeContracts.createSmokeMain({
    runCanonicalSmoke: async () => {
      calls.push('canonical')
      return { status: 'CANONICAL_SENTINEL' }
    },
    runP0Suite: async () => {
      calls.push('p0')
      return { status: 'P0_SENTINEL' }
    }
  })

  assert.deepEqual(await dispatch([]), {
    status: 'CANONICAL_SENTINEL'
  })
  assert.deepEqual(await dispatch(['--suite=canonical']), {
    status: 'CANONICAL_SENTINEL'
  })
  assert.deepEqual(calls, ['canonical', 'canonical'])
})

test('P0 list prints only the canonical registry and performs zero side effects', async () => {
  assert.equal(typeof smokeContracts.createSmokeMain, 'function')
  let mutationCalls = 0
  let stdout = ''
  const dispatch = smokeContracts.createSmokeMain({
    runCanonicalSmoke: async () => { mutationCalls += 1 },
    runP0Suite: async () => { mutationCalls += 1 },
    writeStdout: (value) => { stdout += value }
  })
  const result = await dispatch(['--suite=p0', '--list'])

  assert.deepEqual(result, { listed: 60 })
  assert.deepEqual(JSON.parse(stdout), P0_CASES)
  assert.equal(stdout, `${JSON.stringify(P0_CASES, null, 2)}\n`)
  assert.equal(mutationCalls, 0)
})

test('canonical child uses an explicit local smoke environment and never inherits endpoints credentials or artifact roots', () => {
  const root = resolve(tmpdir(), 'p0-canonical-environment')
  const runRoot = join(root, 'p0-child-environment-a1')
  const ownerToken = 'canonical-child-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const invocation = smokeContracts.buildCanonicalChildInvocation({
    scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
    ownerToken,
    ownerId,
    database: 'fx_p0_user_e2e_canonical_1_abcdef123456',
    runId: 'p0-child-environment-a1',
    runRoot,
    inheritedEnv: {
      PATH: 'trusted-path',
      SystemRoot: 'trusted-system-root',
      API_BASE_URL: 'https://external-api.invalid',
      WEB_BASE_URL: 'https://external-web.invalid',
      ADMIN_BASE_URL: 'https://external-admin.invalid',
      USDT_DEMO_SMOKE_RUN_ID: 'foreign-run',
      USDT_DEMO_SMOKE_EMAIL: 'real-user@example.com',
      USDT_DEMO_SMOKE_PASSWORD: 'real-user-password',
      ADMIN_SMOKE_EMAIL: 'real-admin@example.com',
      ADMIN_SMOKE_PASSWORD: 'real-admin-password',
      USDT_DEMO_SMOKE_ARTIFACTS: resolve(tmpdir(), 'foreign-artifacts'),
      HTTPS_PROXY: 'http://proxy.invalid:3128',
      no_proxy: '*',
      DOCKER_HOST: 'tcp://remote.invalid:2375',
      DOCKER_CONTEXT: 'remote-context',
      BINANCE_API_KEY: 'real-binance-key',
      OKX_ENDPOINT: 'https://okx.invalid',
      BROKER_ENDPOINT: 'https://broker.invalid'
    }
  })

  assert.equal(invocation.env.PATH, 'trusted-path')
  assert.equal(invocation.env.SystemRoot, 'trusted-system-root')
  assert.equal(invocation.env.API_BASE_URL, 'http://127.0.0.1:18086')
  assert.equal(invocation.env.WEB_BASE_URL, 'http://127.0.0.1:5199')
  assert.equal(invocation.env.ADMIN_BASE_URL, 'http://127.0.0.1:5200')
  assert.equal(invocation.env.USDT_DEMO_SMOKE_RUN_ID, 'p0-child-environment-a1-canonical')
  assert.equal(invocation.env.USDT_DEMO_SMOKE_ARTIFACTS, join(runRoot, 'canonical'))
  assert.match(invocation.env.USDT_DEMO_SMOKE_EMAIL, /^p0-canonical\+[a-f0-9]{16}@example\.invalid$/)
  assert.match(invocation.env.USDT_DEMO_SMOKE_PASSWORD, /^P0-[a-f0-9]{24}!aA1$/)
  assert.match(invocation.env.ADMIN_SMOKE_EMAIL, /^p0-admin\+[a-f0-9]{16}@example\.invalid$/)
  assert.match(invocation.env.ADMIN_SMOKE_PASSWORD, /^P0-[a-f0-9]{24}!aA2$/)
  for (const key of [
    'HTTPS_PROXY',
    'no_proxy',
    'DOCKER_HOST',
    'DOCKER_CONTEXT',
    'BINANCE_API_KEY',
    'OKX_ENDPOINT',
    'BROKER_ENDPOINT'
  ]) {
    assert.equal(invocation.env[key], undefined, key)
  }
  assert.equal(invocation.env.P0_RUN_OWNER_TOKEN, ownerToken)
  assert.equal(invocation.env.USDT_DEMO_SMOKE_DATABASE, 'fx_p0_user_e2e_canonical_1_abcdef123456')
  assert.doesNotThrow(() => smokeContracts.validateCanonicalChildEnvironment(invocation))
})

test('canonical import and P0 list remain lazy with incomplete ownership environment', () => {
  const moduleUrl = new URL('./smoke-usdt-demo-browser.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      const smoke = await import(${JSON.stringify(moduleUrl)})
      let stdout = ''
      const result = await smoke.createSmokeMain({
        writeStdout(value) { stdout += value }
      })(['--suite=p0', '--list'])
      assert.deepEqual(result, { listed: 60 })
      assert.equal(JSON.parse(stdout).length, 60)
    `
  ], {
    encoding: 'utf8',
    env: {
      ...process.env,
      P0_RUN_OWNER_TOKEN: 'incomplete-owner-token-a1',
      USDT_DEMO_SMOKE_DATABASE: '',
      USDT_DEMO_SMOKE_ARTIFACTS: resolve(tmpdir(), 'must-not-be-created')
    }
  })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
  assert.equal(execution.stderr, '')
})

test('backend environment scrub is case-insensitive and profile maps are exact', () => {
  assert.equal(typeof smokeContracts.sanitizedBackendEnvironment, 'function')
  assert.equal(typeof smokeContracts.buildBackendEnvironment, 'function')
  const poisoned = {
    Path: 'safe-path',
    SAFE_VALUE: 'kept',
    spring_application_json: '{"unsafe":true}',
    SprIng_PrOfIlEs_AcTiVe: 'production',
    spring_datasource_url: 'jdbc:postgresql://external.invalid/real',
    SpRiNg_DaTa_ReDiS_HoSt: 'external.invalid',
    rEdIs_PaSsWoRd: 'real-secret',
    execution_mode: 'live',
    MARKET_REALTIME_ENABLED: 'true',
    trading_funding_enabled: 'true',
    Broker_Endpoint: 'https://broker.invalid',
    fIx_AcCoUnT: 'real-account',
    LP_PRIVATE_KEY: 'real-key'
  }

  assert.deepEqual(smokeContracts.sanitizedBackendEnvironment(poisoned), {
    Path: 'safe-path',
    SAFE_VALUE: 'kept'
  })

  const common = {
    SPRING_PROFILES_ACTIVE: 'dev',
    EXECUTION_MODE: 'demo',
    TZ: 'UTC',
    JAVA_TOOL_OPTIONS: '-Duser.timezone=UTC',
    MARKET_TEST_CONTROL_ENABLED: 'true',
    MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED: 'false',
    MARKET_REALTIME_ENABLED: 'false',
    TRADING_FX_FINANCING_ENABLED: 'false',
    REDIS_HOST: '127.0.0.1',
    REDIS_PORT: '6379',
    REDIS_PASSWORD: ''
  }
  const scans = {
    TRADING_PENDING_ORDER_SCAN_MS: '500',
    TRADING_PROTECTIVE_ORDER_SCAN_MS: '500',
    TRADING_FUNDING_SCAN_MS: '500',
    TRADING_LIQUIDATION_SCAN_INTERVAL_MS: '500'
  }
  const workerKeys = [
    'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
    'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
    'TRADING_FUNDING_ENABLED',
    'TRADING_LIQUIDATION_ENABLED'
  ]
  const expectedWorkers = {
    UI_CORE: ['false', 'false', 'false', 'false'],
    ORDER_TRIGGER: ['true', 'true', 'false', 'false'],
    FUNDING_ONLY: ['false', 'false', 'true', 'false'],
    LIQUIDATION_ONLY: ['false', 'false', 'false', 'true']
  }

  for (const [profile, workers] of Object.entries(expectedWorkers)) {
    const environment = smokeContracts.buildBackendEnvironment(profile, {
      SERVER_PORT: '18086',
      DATABASE_URL: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_test'
    }, poisoned)
    for (const [key, value] of Object.entries({ ...common, ...scans })) {
      assert.equal(environment[key], value, `${profile} ${key}`)
    }
    assert.deepEqual(workerKeys.map((key) => environment[key]), workers, profile)
    assert.equal(environment.Path, 'safe-path')
    assert.equal(environment.SAFE_VALUE, 'kept')
    assert.equal(environment.SERVER_PORT, '18086')
    assert.equal(environment.DATABASE_URL, 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_test')
    for (const unsafeKey of [
      'spring_application_json',
      'spring_datasource_url',
      'SpRiNg_DaTa_ReDiS_HoSt',
      'Broker_Endpoint',
      'fIx_AcCoUnT',
      'LP_PRIVATE_KEY'
    ]) {
      assert.equal(
        Object.keys(environment).some((key) => key.toUpperCase() === unsafeKey.toUpperCase()),
        false,
        `${profile} inherited ${unsafeKey}`
      )
    }
  }
  assert.throws(
    () => smokeContracts.buildBackendEnvironment('UNKNOWN_PROFILE', {}, poisoned),
    /^Error: P0_UNKNOWN_PROFILE$/
  )
})

test('control manifest confines raw ownership and cleanup is exact and idempotent', async (t) => {
  assert.equal(typeof smokeContracts.createControlManifest, 'function')
  assert.equal(typeof smokeContracts.completeControlCleanup, 'function')
  assert.equal(typeof smokeContracts.buildCanonicalChildInvocation, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-control-manifest-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifacts = join(root, 'artifacts')
  const runId = 'p0-control-a1'
  const rawToken = 'raw-owner-token-never-evidence'
  const script = join(root, 'canonical-script.mjs')
  writeFileSync(script, '')

  const created = await smokeContracts.createControlManifest({
    artifactBase: artifacts,
    runId,
    runToken: rawToken,
    mode: 'discovery',
    selection: { caseIds: ['SPOT-01'], phases: [], profiles: [], viewports: [] },
    database: 'fx_p0_user_e2e_control_a1',
    now: () => '2026-07-15T00:00:00.000Z'
  })
  const runRoot = join(artifacts, runId)
  const ownershipPath = join(runRoot, 'control', 'ownership.json')
  assert.equal(created.runRoot, runRoot)
  assert.equal(created.ownershipPath, ownershipPath)
  assert.match(created.ownerId, /^[a-f0-9]{64}$/)
  assert.equal(created.ownerId.includes(rawToken), false)
  const persisted = JSON.parse(readFileSync(ownershipPath, 'utf8'))
  assert.equal(persisted.ownerToken, rawToken)
  assert.equal(persisted.ownerId, created.ownerId)
  assert.equal(persisted.status, 'ACTIVE')
  assert.deepEqual(persisted.selection, {
    caseIds: ['SPOT-01'],
    phases: [],
    profiles: [],
    viewports: []
  })

  const child = smokeContracts.buildCanonicalChildInvocation({
    scriptPath: script,
    ownerToken: rawToken,
    ownerId: created.ownerId,
    database: 'fx_p0_user_e2e_control_a1',
    inheritedEnv: {
      PATH: 'safe-path',
      P0_RUN_OWNER_TOKEN: 'foreign-owner-token-a1',
      BROKER_ENDPOINT: 'https://broker.invalid'
    }
  })
  assert.equal(child.command, process.execPath)
  assert.deepEqual(child.args, [script])
  assert.equal(child.env.P0_RUN_OWNER_TOKEN, rawToken)
  assert.equal(child.env.USDT_DEMO_SMOKE_DATABASE, 'fx_p0_user_e2e_control_a1')
  assert.equal(child.env.BROKER_ENDPOINT, undefined)
  assert.equal(JSON.stringify({
    command: child.command,
    args: child.args,
    log: child.log,
    evidence: child.evidence
  }).includes(rawToken), false)
  assert.equal(child.log.ownerId, created.ownerId)

  await assert.rejects(
    smokeContracts.createControlManifest({
      artifactBase: artifacts,
      runId,
      runToken: 'second-owner-token-a1',
      mode: 'discovery',
      selection: [],
      database: 'fx_p0_user_e2e_control_a1'
    }),
    /P0_CONTROL_EXISTS/
  )
  assert.equal(JSON.parse(readFileSync(ownershipPath, 'utf8')).ownerToken, rawToken)

  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase: artifacts,
      runId,
      runToken: 'wrong-owner-token-a1',
      resourcesCleaned: true
    }),
    /P0_OWNER_MISMATCH/
  )
  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase: artifacts,
      runId,
      runToken: rawToken,
      resourcesCleaned: false
    }),
    /P0_CLEANUP_INCOMPLETE/
  )
  assert.equal(existsSync(ownershipPath), true)

  markP0RedisSnapshotReady(created, [], [], 'REDIS_RELEASE_ARMED')
  const cleanupProof = await installStrictCleanupComposeProof(created, artifacts)
  const completed = await smokeContracts.completeControlCleanup({
    artifactBase: artifacts,
    runId,
    runToken: rawToken,
    resourcesCleaned: true,
    redisReleaseReceipt: created.ownerId,
    now: () => '2026-07-15T01:00:00.000Z'
  })
  assert.deepEqual(completed, { status: 'CLEANED', alreadyCleaned: false })
  assert.equal(existsSync(ownershipPath), true)
  const cleanedPath = join(runRoot, 'control', 'cleaned.json')
  const cleanedText = readFileSync(ownershipPath, 'utf8')
  assert.equal(cleanedText.includes(rawToken), false)
  assert.deepEqual(JSON.parse(cleanedText), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: created.ownerId,
    cleanedAt: '2026-07-15T01:00:00.000Z',
    composeTarget: cleanupProof.composeTarget,
    composeIdentity: cleanupProof.composeIdentity,
    receipt: {
      key: `p0:e2e:cleanup:${created.ownerId}`,
      state: 'PENDING',
      completedAt: null
    }
  })
  assert.deepEqual(await smokeContracts.completeControlCleanup({
    artifactBase: artifacts,
    runId,
    runToken: rawToken,
    resourcesCleaned: true,
    redisReleaseReceipt: created.ownerId
  }), { status: 'CLEANED', alreadyCleaned: true })
  assert.equal(existsSync(cleanedPath), false)

  assert.throws(
    () => p0CaseContracts.resolveP0RunRoot(artifacts, '../escape'),
    /P0_RUN_ID_INVALID/
  )
  const outside = join(root, 'outside')
  mkdirSync(outside)
  const escapedRun = join(artifacts, 'p0-junction-a1')
  symlinkSync(outside, escapedRun, process.platform === 'win32' ? 'junction' : 'dir')
  await assert.rejects(
    smokeContracts.createControlManifest({
      artifactBase: artifacts,
      runId: 'p0-junction-a1',
      runToken: 'escape-owner-token-a1',
      mode: 'discovery',
      selection: [],
      database: 'fx_p0_user_e2e_junction_a1'
    }),
    /P0_CONTROL_EXISTS/
  )
  assert.deepEqual(readdirSync(outside), [])
})

test('database ownership is strictly quoted read back and required before alter or drop', async () => {
  assert.equal(typeof smokeContracts.databaseSegmentForAttempt, 'function')
  assert.equal(typeof smokeContracts.createOwnedDatabase, 'function')
  assert.equal(typeof smokeContracts.alterOwnedDatabaseTimezone, 'function')
  assert.equal(typeof smokeContracts.dropOwnedDatabase, 'function')
  const segmentName = smokeContracts.databaseSegmentForAttempt({
    phase: 'order-trigger',
    attempt: 2,
    randomSuffix: 'abcdef123456'
  })
  assert.equal(segmentName, 'fx_p0_user_e2e_order_trigger_2_abcdef123456')
  assert.match(segmentName, /^fx_p0_user_e2e_[a-z0-9_]+$/)
  assert.throws(
    () => smokeContracts.databaseSegmentForAttempt({
      phase: '../escape',
      attempt: 1,
      randomSuffix: 'abcdef123456'
    }),
    /P0_DATABASE_PHASE_INVALID/
  )

  const runToken = "database-owner-token-'quoted'-a1"
  const ownerMarker = `p0-owner:${runToken}`
  const actions = []
  const postgres = {
    async executeAdminSql(sql, options) {
      actions.push({ type: 'sql', sql, options })
    },
    async readDatabaseOwnership(database) {
      actions.push({ type: 'read', database })
      return { segmentName: database, ownerMarker }
    }
  }
  const created = await smokeContracts.createOwnedDatabase({
    segmentName,
    runToken,
    postgres,
    recordDatabase: async (database) => actions.push({ type: 'record', database })
  })
  assert.deepEqual(created, {
    segmentName,
    ownerId: createHash('sha256').update(runToken).digest('hex')
  })
  assert.deepEqual(actions, [
    { type: 'record', database: segmentName },
    {
      type: 'sql',
      sql: `CREATE DATABASE "${segmentName}"`,
      options: { sensitive: false }
    },
    {
      type: 'sql',
      sql: `COMMENT ON DATABASE "${segmentName}" IS 'p0-owner:database-owner-token-''quoted''-a1'`,
      options: { sensitive: true }
    },
    { type: 'read', database: segmentName }
  ])

  actions.length = 0
  await smokeContracts.alterOwnedDatabaseTimezone({
    segmentName,
    runToken,
    postgres
  })
  await smokeContracts.dropOwnedDatabase({
    segmentName,
    runToken,
    postgres
  })
  assert.deepEqual(actions, [
    { type: 'read', database: segmentName },
    {
      type: 'sql',
      sql: `ALTER DATABASE "${segmentName}" SET timezone TO 'UTC'`,
      options: { sensitive: false }
    },
    { type: 'read', database: segmentName },
    {
      type: 'sql',
      sql: `DROP DATABASE "${segmentName}" WITH (FORCE)`,
      options: { sensitive: false }
    }
  ])

  for (const unsafeName of [
    'fx_p0_user_e2e_*',
    'fx_platform',
    'fx_p0_user_e2e_safe;DROP_DATABASE',
    'FX_P0_USER_E2E_UPPER'
  ]) {
    const unsafeActions = []
    await assert.rejects(
      smokeContracts.dropOwnedDatabase({
        segmentName: unsafeName,
        runToken,
        postgres: {
          async readDatabaseOwnership() { unsafeActions.push('read') },
          async executeAdminSql() { unsafeActions.push('sql') }
        }
      }),
      /P0_DATABASE_NAME_INVALID/,
      unsafeName
    )
    assert.deepEqual(unsafeActions, [], unsafeName)
  }

  const mismatchActions = []
  const mismatchedPostgres = {
    async readDatabaseOwnership(database) {
      mismatchActions.push({ type: 'read', database })
      return { segmentName: database, ownerMarker: 'p0-owner:foreign-owner-token-a1' }
    },
    async executeAdminSql(sql) { mismatchActions.push({ type: 'sql', sql }) }
  }
  await assert.rejects(
    smokeContracts.alterOwnedDatabaseTimezone({ segmentName, runToken, postgres: mismatchedPostgres }),
    /P0_DATABASE_OWNER_MISMATCH/
  )
  await assert.rejects(
    smokeContracts.dropOwnedDatabase({ segmentName, runToken, postgres: mismatchedPostgres }),
    /P0_DATABASE_OWNER_MISMATCH/
  )
  assert.deepEqual(mismatchActions, [
    { type: 'read', database: segmentName },
    { type: 'read', database: segmentName }
  ])
})

test('database identity probe pins loopback owned segment marker and UTC', async () => {
  assert.equal(typeof smokeContracts.verifyDatabaseIdentity, 'function')
  const segmentName = 'fx_p0_user_e2e_probe_a1'
  const runToken = 'database-probe-owner-token-a1'
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  const probes = []
  const postgres = {
    async probeDatabase(identity) {
      probes.push(identity)
      return {
        currentDatabase: segmentName,
        ownerMarker: `p0-owner:${runToken}`,
        timezone: 'UTC'
      }
    }
  }
  assert.deepEqual(await smokeContracts.verifyDatabaseIdentity({
    segmentName,
    runToken,
    databaseUrl,
    postgres
  }), { segmentName, timezone: 'UTC' })
  assert.deepEqual(probes, [{ databaseUrl, segmentName }])

  for (const unsafeUrl of [
    `jdbc:postgresql://localhost:5432/${segmentName}`,
    'jdbc:postgresql://127.0.0.1:5432/fx_platform',
    `jdbc:postgresql://127.0.0.1:5433/${segmentName}`,
    `jdbc:postgresql://external.invalid:5432/${segmentName}`
  ]) {
    await assert.rejects(
      smokeContracts.verifyDatabaseIdentity({ segmentName, runToken, databaseUrl: unsafeUrl, postgres }),
      /P0_DATABASE_URL_INVALID/,
      unsafeUrl
    )
  }

  for (const [field, value, error] of [
    ['currentDatabase', 'fx_p0_user_e2e_other_a1', 'P0_DATABASE_IDENTITY_MISMATCH'],
    ['ownerMarker', 'p0-owner:foreign-owner-token-a1', 'P0_DATABASE_OWNER_MISMATCH'],
    ['timezone', 'Asia/Shanghai', 'P0_DATABASE_TIMEZONE_MISMATCH']
  ]) {
    const response = {
      currentDatabase: segmentName,
      ownerMarker: `p0-owner:${runToken}`,
      timezone: 'UTC',
      [field]: value
    }
    await assert.rejects(
      smokeContracts.verifyDatabaseIdentity({
        segmentName,
        runToken,
        databaseUrl,
        postgres: { async probeDatabase() { return response } }
      }),
      new RegExp(error),
      field
    )
  }
})

test('Redis ownership snapshots exact keys restores absolute expiry and compare deletes', async () => {
  assert.equal(typeof smokeContracts.acquireRedisOwnership, 'function')
  assert.equal(typeof smokeContracts.snapshotRedisKeys, 'function')
  assert.equal(typeof smokeContracts.cleanupOwnedRedis, 'function')
  const runToken = 'redis-parent-owner-token-a1'
  const ownerKey = 'p0:e2e:owner'
  const existingKey = 'market:test-control:provider-bindings'
  const persistentKey = 'trading:test-control:authority-bundle'
  const missingKey = 'trading:test-control:created-during-run'
  const state = new Map([
    [existingKey, { value: 'original-provider-state', expiresAtMs: 1_800_000_000_000 }],
    [persistentKey, { value: 'original-authority-state', expiresAtMs: null }]
  ])
  const operations = []
  const redis = {
    async setNx(key, value) {
      operations.push({ type: 'setNx', key, value })
      if (state.has(key)) return false
      state.set(key, { value, expiresAtMs: null })
      return true
    },
    async get(key) {
      operations.push({ type: 'get', key })
      return state.get(key)?.value ?? null
    },
    async readExact(key) {
      operations.push({ type: 'readExact', key })
      const entry = state.get(key)
      return entry
        ? { exists: true, value: entry.value, expiresAtMs: entry.expiresAtMs }
        : { exists: false, value: null, expiresAtMs: null }
    },
    async restoreExact(key, value, expiresAtMs) {
      operations.push({ type: 'restoreExact', key, value, expiresAtMs })
      state.set(key, { value, expiresAtMs })
    },
    async deleteExact(key) {
      operations.push({ type: 'deleteExact', key })
      state.delete(key)
    },
    async compareDelete(key, value) {
      operations.push({ type: 'compareDelete', key, value })
      if (state.get(key)?.value !== value) return false
      state.delete(key)
      return true
    }
  }

  const acquired = await smokeContracts.acquireRedisOwnership({ redis, runToken, role: 'parent' })
  assert.deepEqual(acquired, {
    ownerKey,
    ownerId: createHash('sha256').update(runToken).digest('hex'),
    inherited: false
  })
  assert.equal(JSON.stringify(acquired).includes(runToken), false)
  assert.deepEqual(await smokeContracts.acquireRedisOwnership({ redis, runToken, role: 'child' }), {
    ownerKey,
    ownerId: acquired.ownerId,
    inherited: true
  })
  await assert.rejects(
    smokeContracts.acquireRedisOwnership({
      redis,
      runToken: 'redis-foreign-owner-token-a1',
      role: 'parent'
    }),
    /P0_REDIS_OWNER_EXISTS/
  )
  assert.equal(state.get(ownerKey).value, runToken)

  const snapshot = await smokeContracts.snapshotRedisKeys({
    redis,
    keys: [existingKey, persistentKey, missingKey]
  })
  assert.deepEqual(snapshot, [
    { key: existingKey, exists: true, value: 'original-provider-state', expiresAtMs: 1_800_000_000_000 },
    { key: persistentKey, exists: true, value: 'original-authority-state', expiresAtMs: null },
    { key: missingKey, exists: false, value: null, expiresAtMs: null }
  ])
  state.set(existingKey, { value: 'mutated', expiresAtMs: 1_900_000_000_000 })
  state.delete(persistentKey)
  state.set(missingKey, { value: 'new-value', expiresAtMs: null })
  state.set('trading:test-control:another-new-key', { value: 'new', expiresAtMs: null })

  const cleanup = await smokeContracts.cleanupOwnedRedis({
    redis,
    runToken,
    snapshot,
    touchedKeys: [
      existingKey,
      persistentKey,
      missingKey,
      'trading:test-control:another-new-key'
    ]
  })
  assert.deepEqual(cleanup, { restored: 4, ownerReleased: true })
  assert.deepEqual(state.get(existingKey), {
    value: 'original-provider-state',
    expiresAtMs: 1_800_000_000_000
  })
  assert.deepEqual(state.get(persistentKey), {
    value: 'original-authority-state',
    expiresAtMs: null
  })
  assert.equal(state.has(missingKey), false)
  assert.equal(state.has('trading:test-control:another-new-key'), false)
  assert.equal(state.has(ownerKey), false)
  assert.equal(
    operations.some(({ type }) => /flush/i.test(type)),
    false
  )

  for (const invalidKey of ['*', 'prefix:*', 'key?', '[key]', ownerKey, 'line\nbreak']) {
    await assert.rejects(
      smokeContracts.snapshotRedisKeys({ redis, keys: [invalidKey] }),
      /P0_REDIS_KEY_INVALID/,
      invalidKey
    )
  }

  state.set(ownerKey, { value: 'redis-unknown-owner-token-a1', expiresAtMs: null })
  const operationsBeforeRefusal = operations.length
  await assert.rejects(
    smokeContracts.cleanupOwnedRedis({ redis, runToken, snapshot, touchedKeys: [] }),
    /P0_REDIS_OWNER_MISMATCH/
  )
  assert.deepEqual(operations.slice(operationsBeforeRefusal), [{ type: 'get', key: ownerKey }])
  assert.equal(state.get(ownerKey).value, 'redis-unknown-owner-token-a1')
})

test('safety preflight requires loopback compose identity free owned ports and no broker secrets', async () => {
  assert.equal(typeof smokeContracts.runSafetyPreflight, 'function')
  const ownerId = 'a'.repeat(64)
  const database = 'fx_p0_user_e2e_safety_a1'
  const endpoints = {
    api: 'http://127.0.0.1:18086',
    web: 'http://127.0.0.1:5199',
    admin: 'http://127.0.0.1:5200',
    databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${database}`,
    redisHost: '127.0.0.1',
    redisPort: 6379
  }
  const calls = []
  const infrastructure = {
    async verifyComposePort(expected) {
      calls.push({ type: 'compose', expected })
      return true
    },
    async inspectListener(expected) {
      calls.push({ type: 'listener', expected })
      return expected.port === 5199 ? { ownerId } : null
    }
  }
  assert.deepEqual(await smokeContracts.runSafetyPreflight({
    ownerId,
    database,
    endpoints,
    inheritedEnv: {
      PATH: 'safe-path',
      BROKER_ENDPOINT: '',
      fix_password: '',
      Lp_Api_Key: ''
    },
    infrastructure
  }), {
    status: 'PASS',
    database,
    composePorts: [5432, 6379],
    businessPorts: [18086, 5199, 5200]
  })
  assert.deepEqual(calls, [
    {
      type: 'compose',
      expected: {
        service: 'postgres',
        containerName: 'fx-platform-postgres',
        image: 'postgres:16',
        host: '127.0.0.1',
        hostPort: 5432,
        containerPort: 5432,
        composeFiles: undefined,
        expectedProject: 'infra'
      }
    },
    {
      type: 'compose',
      expected: {
        service: 'redis',
        containerName: 'fx-platform-redis',
        image: 'redis:7',
        host: '127.0.0.1',
        hostPort: 6379,
        containerPort: 6379,
        composeFiles: undefined,
        expectedProject: 'infra'
      }
    },
    { type: 'listener', expected: { host: '127.0.0.1', port: 18086 } },
    { type: 'listener', expected: { host: '127.0.0.1', port: 5199 } },
    { type: 'listener', expected: { host: '127.0.0.1', port: 5200 } }
  ])

  for (const [label, patch, error] of [
    ['external api', { endpoints: { ...endpoints, api: 'https://api.example.com' } }, 'P0_ENDPOINT_NOT_LOOPBACK'],
    ['wrong api port', { endpoints: { ...endpoints, api: 'http://127.0.0.1:8080' } }, 'P0_ENDPOINT_PORT_INVALID'],
    ['external database', {
      endpoints: { ...endpoints, databaseUrl: `jdbc:postgresql://db.example.com:5432/${database}` }
    }, 'P0_DATABASE_URL_INVALID'],
    ['default database', {
      database: 'fx_platform',
      endpoints: { ...endpoints, databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_platform' }
    }, 'P0_DATABASE_NAME_INVALID'],
    ['external redis', {
      endpoints: { ...endpoints, redisHost: 'redis.example.com' }
    }, 'P0_REDIS_ENDPOINT_INVALID']
  ]) {
    let probes = 0
    await assert.rejects(
      smokeContracts.runSafetyPreflight({
        ownerId,
        database: patch.database ?? database,
        endpoints: patch.endpoints,
        inheritedEnv: {},
        infrastructure: {
          async verifyComposePort() { probes += 1; return true },
          async inspectListener() { probes += 1; return null }
        }
      }),
      new RegExp(error),
      label
    )
    assert.equal(probes, 0, label)
  }

  for (const [key, value] of [
    ['BROKER_ENDPOINT', 'https://broker.example.com'],
    ['fix_private_key', 'real-key'],
    ['LP_ACCOUNT', 'real-account']
  ]) {
    let probes = 0
    await assert.rejects(
      smokeContracts.runSafetyPreflight({
        ownerId,
        database,
        endpoints,
        inheritedEnv: { [key]: value },
        infrastructure: {
          async verifyComposePort() { probes += 1; return true },
          async inspectListener() { probes += 1; return null }
        }
      }),
      /P0_EXTERNAL_TRADING_CONFIGURATION/,
      key
    )
    assert.equal(probes, 0, key)
  }

  await assert.rejects(
    smokeContracts.runSafetyPreflight({
      ownerId,
      database,
      endpoints,
      inheritedEnv: {},
      infrastructure: {
        async verifyComposePort({ hostPort }) { return hostPort !== 6379 },
        async inspectListener() { throw new Error('listener must not run') }
      }
    }),
    /P0_COMPOSE_PORT_MISMATCH/
  )
  await assert.rejects(
    smokeContracts.runSafetyPreflight({
      ownerId,
      database,
      endpoints,
      inheritedEnv: {},
      infrastructure: {
        async verifyComposePort() { return true },
        async inspectListener({ port }) {
          return port === 18086 ? { ownerId: 'b'.repeat(64) } : null
        }
      }
    }),
    /P0_PORT_OWNED_BY_UNKNOWN/
  )
})

test('P0 safety rejects remote Docker context before the first mutation', async () => {
  const ownerId = 'd'.repeat(64)
  const database = 'fx_p0_user_e2e_remote_docker_a1'
  const endpoints = {
    api: 'http://127.0.0.1:18086',
    web: 'http://127.0.0.1:5199',
    admin: 'http://127.0.0.1:5200',
    databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${database}`,
    redisHost: '127.0.0.1',
    redisPort: 6379
  }

  for (const inheritedEnv of [
    { DOCKER_HOST: 'tcp://remote.invalid:2375' },
    { docker_context: 'remote-context' }
  ]) {
    let probes = 0
    await assert.rejects(
      smokeContracts.runSafetyPreflight({
        ownerId,
        database,
        endpoints,
        inheritedEnv,
        infrastructure: {
          async inspectDockerDaemon() { probes += 1; return { endpoint: 'npipe:////./pipe/docker_engine' } },
          async verifyComposePort() { probes += 1; return true },
          async inspectListener() { probes += 1; return null }
        }
      }),
      /P0_DOCKER_TARGET_INHERITED/
    )
    assert.equal(probes, 0)
  }

  let postDaemonProbes = 0
  await assert.rejects(
    smokeContracts.runSafetyPreflight({
      ownerId,
      database,
      endpoints,
      inheritedEnv: {},
      infrastructure: {
        async inspectDockerDaemon() { return { endpoint: 'tcp://remote.invalid:2375' } },
        async verifyComposePort() { postDaemonProbes += 1; return true },
        async inspectListener() { postDaemonProbes += 1; return null }
      }
    }),
    /P0_DOCKER_DAEMON_NOT_LOCAL/
  )
  assert.equal(postDaemonProbes, 0)
})

test('P0 compose startup binds verified local container identities and database probes', async () => {
  assert.equal(typeof smokeContracts.createLocalInfrastructureAdapter, 'function')
  assert.equal(typeof smokeContracts.createDockerPostgresAdapter, 'function')
  const composeFile = join(dirname(artifactsScript), '..', 'infra', 'docker-compose.yml')
  const composeDirectory = dirname(composeFile)
  const postgresId = 'a'.repeat(64)
  const redisId = 'b'.repeat(64)
  const runToken = 'compose-probe-owner-token-a1'
  const segmentName = 'fx_p0_user_e2e_compose_probe_a1'
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  const commands = []
  const inspection = (id, service, image, port) => JSON.stringify([{
    Id: id,
    State: { Running: true },
    Config: {
      Image: image,
      Env: service === 'postgres'
        ? ['POSTGRES_DB=fx_platform', 'POSTGRES_USER=postgres', 'POSTGRES_PASSWORD=password']
        : [],
      Labels: {
        'com.docker.compose.project': 'infra',
        'com.docker.compose.service': service,
        'com.docker.compose.project.working_dir': composeDirectory,
        'com.docker.compose.project.config_files': composeFile
      }
    },
    NetworkSettings: {
      Ports: {
        [`${port}/tcp`]: [{ HostIp: '127.0.0.1', HostPort: String(port) }]
      }
    }
  }])
  const runCommand = async (descriptor) => {
    commands.push(descriptor)
    if (descriptor.args[0] === 'context') {
      return { status: 0, stdout: 'npipe:////./pipe/docker_engine\n', stderr: '' }
    }
    if (descriptor.args[0] === 'compose') {
      return {
        status: 0,
        stdout: descriptor.args.at(-1) === 'postgres' ? `${postgresId}\n` : `${redisId}\n`,
        stderr: ''
      }
    }
    if (descriptor.args[0] === 'inspect') {
      return {
        status: 0,
        stdout: descriptor.args[1] === postgresId
          ? inspection(postgresId, 'postgres', 'postgres:16', 5432)
          : inspection(redisId, 'redis', 'redis:7', 6379),
        stderr: ''
      }
    }
    if (descriptor.args[0] === 'exec') {
      assert.equal(descriptor.args[2], postgresId)
      if (descriptor.stdin.includes('current_database()')) {
        return {
          status: 0,
          stdout: `${segmentName}\tp0-owner:${runToken}\tUTC\n`,
          stderr: ''
        }
      }
      return {
        status: 0,
        stdout: `${segmentName}\tp0-owner:${runToken}\n`,
        stderr: ''
      }
    }
    throw new Error(`unexpected command: ${descriptor.args.join(' ')}`)
  }
  const infrastructure = smokeContracts.createLocalInfrastructureAdapter(composeFile, runCommand)

  assert.deepEqual(await infrastructure.inspectDockerDaemon(), {
    endpoint: 'npipe:////./pipe/docker_engine'
  })
  const identities = await infrastructure.verifyComposeContainers({ composeFiles: [composeFile] })
  assert.deepEqual(identities, {
    project: 'infra',
    postgres: { id: postgresId, image: 'postgres:16', host: '127.0.0.1', hostPort: 5432 },
    redis: { id: redisId, image: 'redis:7', host: '127.0.0.1', hostPort: 6379 },
    credentials: { username: 'postgres', password: 'password' }
  })

  const postgres = smokeContracts.createDockerPostgresAdapter(runCommand, () => identities.postgres.id)
  assert.deepEqual(await smokeContracts.verifyDatabaseIdentity({
    segmentName,
    runToken,
    databaseUrl,
    postgres
  }), { segmentName, timezone: 'UTC' })
  assert.deepEqual(await postgres.readDatabaseOwnership(segmentName), {
    segmentName,
    ownerMarker: `p0-owner:${runToken}`
  })
  const dockerExecs = commands.filter(({ args }) => args[0] === 'exec')
  assert.equal(dockerExecs.length, 2)
  assert.equal(dockerExecs.every(({ args }) => args[2] === postgresId), true)
  assert.equal(dockerExecs.some(({ args }) => args.includes('fx-platform-postgres')), false)
})

function review1P0Identity(overrides = {}) {
  return {
    branch: 'codex/usdt-spot-perp-p0',
    commit: '1'.repeat(40),
    clean: true,
    dirtyDiffHash: `sha256:${'2'.repeat(64)}`,
    worktreeFingerprint: `sha256:${'3'.repeat(64)}`,
    schemaVersion: 1,
    registryFingerprint: P0_REGISTRY_FINGERPRINT,
    profileWorkerMap: {
      UI_CORE: ['false', 'false', 'false', 'false'],
      ORDER_TRIGGER: ['true', 'true', 'false', 'false'],
      FUNDING_ONLY: ['false', 'false', 'true', 'false'],
      LIQUIDATION_ONLY: ['false', 'false', 'false', 'true']
    },
    ...overrides
  }
}

function review1SafetyInfrastructure(events = []) {
  return {
    async inspectDockerDaemon() {
      events.push('docker:inspect-daemon')
      return { endpoint: 'npipe:////./pipe/docker_engine' }
    },
    async verifyComposePort({ hostPort }) {
      events.push(`docker:planned-port:${hostPort}`)
      return true
    },
    async inspectListener({ port }) {
      events.push(`listener:${port}`)
      return null
    }
  }
}

function markP0RedisSnapshotReady(
  control,
  snapshot = [],
  touchedKeys = [],
  redisState = 'SNAPSHOT_READY'
) {
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.redisState = redisState
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  const inventory = [
    'quote:BTCUSDT',
    'quote:ETHUSDT',
    'quote:BNBUSDT',
    'quote:SOLUSDT',
    'quote:XRPUSDT',
    'quote:BTCUSDT-PERP',
    'quote:ETHUSDT-PERP',
    'quote:BNBUSDT-PERP',
    'quote:SOLUSDT-PERP',
    'quote:XRPUSDT-PERP'
  ]
  const provided = new Map(snapshot.map((entry) => [entry.key, entry]))
  const canonicalSnapshot = inventory.map((key) => provided.get(key) ?? ({
    key,
    exists: false,
    value: null,
    expiresAtMs: null
  }))
  const recovery = smokeContracts.createP0RedisRecoveryState({
    runId: active.runId,
    ownerId: active.ownerId,
    inventory,
    snapshot: canonicalSnapshot,
    touchedKeys
  })
  writeFileSync(
    join(control.runRoot, 'control', 'redis.json'),
    `${JSON.stringify(recovery, null, 2)}\n`
  )
}

test('resume reopens exact owned run state before any resource mutation', async (t) => {
  assert.equal(typeof smokeContracts.prepareP0RunReservation, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-resume-entry-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const runId = 'p0-review1-resume-a1'
  const ownerToken = 'review1-resume-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const canonicalDatabase = 'fx_p0_user_e2e_canonical_1_abcdef123456'
  const matrixDatabase = 'fx_p0_user_e2e_matrix_1_abcdef123457'
  const identity = review1P0Identity()
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${runId}`,
    `--resume=${runId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken: ownerToken,
    mode: options.mode,
    selection: {
      caseIds: ['SPOT-01'],
      phases: [],
      profiles: [],
      viewports: [],
      metadata: s10RequestedPhaseSelection('selected').metadata
    },
    database: { canonical: canonicalDatabase, matrix: matrixDatabase },
    identity,
    profileWorkerMap: identity.profileWorkerMap,
    now: () => '2026-07-15T04:00:00.000Z'
  })
  const runStatePath = join(control.runRoot, 'run-state.json')
  const selection = {
    caseIds: ['SPOT-01'],
    phases: [],
    profiles: [],
    viewports: [],
    metadata: s10RequestedPhaseSelection('selected').metadata
  }
  smokeContracts.openP0RunState({
    path: runStatePath,
    resume: false,
    runId,
    mode: options.mode,
    commit: identity.commit,
    worktreeFingerprint: identity.worktreeFingerprint,
    schemaVersion: identity.schemaVersion,
    registryFingerprint: identity.registryFingerprint,
    definitions: P0_CASES,
    selection
  })

  let tokenGenerationCalls = 0
  let databaseCollisionCalls = 0
  const events = []
  const prepared = await smokeContracts.prepareP0RunReservation({
    options,
    plan,
    artifactBase,
    inheritedEnv: {},
    captureIdentity: async () => { events.push('identity:capture'); return identity },
    databaseExists: async () => { databaseCollisionCalls += 1; return false },
    nextRunToken: () => { tokenGenerationCalls += 1; return 'must-not-generate-owner-token' },
    nextRandomSuffix: () => 'abcdef123458',
    infrastructure: review1SafetyInfrastructure(events)
  })

  assert.equal(prepared.ownerToken, ownerToken)
  assert.equal(prepared.ownerId, ownerId)
  assert.equal(prepared.canonicalDatabase, canonicalDatabase)
  assert.equal(prepared.matrixDatabase, matrixDatabase)
  assert.equal(prepared.runStatePath, runStatePath)
  assert.equal(prepared.runState.commit, identity.commit)
  assert.deepEqual(prepared.resumePlan.entries.map(({ id }) => id), ['SPOT-01'])
  assert.equal(tokenGenerationCalls, 0)
  assert.equal(databaseCollisionCalls, 0)
  assert.equal(events[0], 'identity:capture')
  assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).ownerToken, ownerToken)
})

test('certification rejects dirty identity and invalidates commit or tree drift', async (t) => {
  assert.equal(typeof smokeContracts.prepareP0RunReservation, 'function')
  assert.equal(typeof smokeContracts.assertP0RunIdentityUnchanged, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-cert-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--mode=certification',
    '--run-id=p0-review1-cert-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  let mutationCalls = 0
  await assert.rejects(
    smokeContracts.prepareP0RunReservation({
      options,
      plan,
      artifactBase: join(root, 'dirty-artifacts'),
      inheritedEnv: {},
      captureIdentity: async () => review1P0Identity({
        clean: false,
        dirtyDiffHash: `sha256:${'9'.repeat(64)}`
      }),
      databaseExists: async () => { mutationCalls += 1; return false },
      nextRunToken: () => { mutationCalls += 1; return 'review1-cert-owner-token-a1' },
      nextRandomSuffix: () => 'abcdef123456',
      infrastructure: review1SafetyInfrastructure()
    }),
    /P0_CERTIFICATION_DIRTY_TREE/
  )
  assert.equal(mutationCalls, 0)
  assert.equal(existsSync(join(root, 'dirty-artifacts')), false)

  const expected = review1P0Identity()
  const runRoot = join(root, 'drift-artifacts', 'p0-review1-drift-a1')
  mkdirSync(runRoot, { recursive: true })
  for (const current of [
    review1P0Identity({ commit: '4'.repeat(40) }),
    review1P0Identity({ worktreeFingerprint: `sha256:${'5'.repeat(64)}` })
  ]) {
    assert.throws(
      () => smokeContracts.assertP0RunIdentityUnchanged(expected, current, { runRoot }),
      /P0_IDENTITY_DRIFT/
    )
    const evidence = JSON.parse(readFileSync(join(runRoot, 'identity-invalid.json'), 'utf8'))
    assert.equal(evidence.status, 'INVALID_TEST')
    assert.equal(evidence.reasonCode, 'P0_IDENTITY_DRIFT')
    rmSync(join(runRoot, 'identity-invalid.json'))
  }
})

test('new P0 run rejects artifact run-state and database collisions before compose mutation', async (t) => {
  assert.equal(typeof smokeContracts.prepareP0RunReservation, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-new-run-collision-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const identity = review1P0Identity()
  const createOptions = (runId) => p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${runId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const createInput = (runId, overrides = {}) => {
    const options = createOptions(runId)
    return {
      options,
      plan: p0CaseContracts.planP0Execution(options, P0_CASES),
      artifactBase: join(root, 'artifacts'),
      inheritedEnv: {},
      captureIdentity: async () => identity,
      nextRunToken: () => 'review1-collision-owner-token-a1',
      nextRandomSuffix: (() => {
        const suffixes = ['abcdef123456', 'abcdef123457']
        return () => suffixes.shift()
      })(),
      infrastructure: review1SafetyInfrastructure(),
      ...overrides
    }
  }

  const artifactRunId = 'p0-review1-artifact-collision-a1'
  const artifactRunRoot = join(root, 'artifacts', artifactRunId)
  mkdirSync(artifactRunRoot, { recursive: true })
  writeFileSync(join(artifactRunRoot, 'run-state.json'), '{}')
  let databaseChecks = 0
  await assert.rejects(
    smokeContracts.prepareP0RunReservation(createInput(artifactRunId, {
      databaseExists: async () => { databaseChecks += 1; return false }
    })),
    /P0_RUN_COLLISION/
  )
  assert.equal(databaseChecks, 0)

  const databaseRunId = 'p0-review1-database-collision-a1'
  const databaseRunRoot = join(root, 'artifacts', databaseRunId)
  let firstDatabase
  await assert.rejects(
    smokeContracts.prepareP0RunReservation(createInput(databaseRunId, {
      databaseExists: async (segmentName) => {
        firstDatabase ??= segmentName
        assert.equal(existsSync(databaseRunRoot), false)
        return true
      }
    })),
    /P0_DATABASE_COLLISION/
  )
  assert.match(firstDatabase, /^fx_p0_user_e2e_/)
  assert.equal(existsSync(databaseRunRoot), false)

  const reservedRunId = 'p0-review1-reserved-a1'
  const reserved = await smokeContracts.prepareP0RunReservation(createInput(reservedRunId, {
    databaseExists: async () => false
  }))
  assert.equal(existsSync(reserved.ownershipPath), true)
  assert.equal(existsSync(reserved.runStatePath), true)
  assert.deepEqual(JSON.parse(readFileSync(reserved.ownershipPath, 'utf8')).identity, identity)
})

test('control state writes are atomic no-clobber and reject file symlinks', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-control-atomic-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const partialRunId = 'p0-review1-control-partial-a1'

  await assert.rejects(
    smokeContracts.createControlManifest({
      artifactBase,
      runId: partialRunId,
      runToken: 'review1-control-partial-owner-token-a1',
      mode: 'discovery',
      selection: [1n],
      database: 'fx_p0_user_e2e_control_partial_a1'
    }),
    TypeError
  )
  const partialControl = join(artifactBase, partialRunId, 'control')
  assert.equal(existsSync(join(partialControl, 'ownership.json')), false)
  assert.deepEqual(readdirSync(partialControl), [])

  const symlinkRunId = 'p0-review1-control-symlink-a1'
  const symlinkControl = join(artifactBase, symlinkRunId, 'control')
  mkdirSync(symlinkControl, { recursive: true })
  const ownerToken = 'review1-control-symlink-owner-token-a1'
  const externalOwnership = join(root, 'external-ownership.json')
  const externalText = `${JSON.stringify({
    schemaVersion: 1,
    status: 'ACTIVE',
    runId: symlinkRunId,
    ownerId: createHash('sha256').update(ownerToken).digest('hex'),
    ownerToken
  })}\n`
  writeFileSync(externalOwnership, externalText)
  linkSync(externalOwnership, join(symlinkControl, 'ownership.json'))

  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId: symlinkRunId,
      runToken: ownerToken,
      resourcesCleaned: false
    }),
    /P0_CONTROL_FILE_UNSAFE/
  )
  assert.equal(readFileSync(externalOwnership, 'utf8'), externalText)
})

test('hard kill journal records each owned process override database and canonical child before start', async (t) => {
  assert.equal(typeof smokeContracts.runP0JournaledMutation, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-hard-kill-journal-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-journal-a1'
  const runToken = 'review1-journal-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: ['SPOT-01'],
    database: {
      canonical: 'fx_p0_user_e2e_canonical_1_abcdef123456',
      matrix: 'fx_p0_user_e2e_matrix_1_abcdef123457'
    }
  })
  const resources = [
    {
      type: 'process',
      id: 'backend:UI_CORE',
      commandFingerprint: `sha256:${'1'.repeat(64)}`
    },
    {
      type: 'override',
      id: 'compose-loopback',
      path: 'control/compose.loopback.yml'
    },
    {
      type: 'database',
      id: 'fx_p0_user_e2e_matrix_1_abcdef123457'
    },
    {
      type: 'canonical-child',
      id: 'canonical:attempt-1',
      database: 'fx_p0_user_e2e_canonical_1_abcdef123456'
    }
  ]
  const started = []

  for (const resource of resources) {
    await smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource,
      start: async () => {
        const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
        const recorded = active.journal.resources.find(({ type, id }) => (
          type === resource.type && id === resource.id
        ))
        assert.equal(recorded?.state, 'PLANNED')
        started.push(`${resource.type}:${resource.id}`)
        return { started: resource.id }
      }
    })
  }

  assert.deepEqual(started, resources.map(({ type, id }) => `${type}:${id}`))
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.deepEqual(active.journal.resources.map(({ type, id, state }) => ({ type, id, state })),
    resources.map(({ type, id }) => ({ type, id, state: 'STARTED' })))
  assert.equal(JSON.stringify(active.journal).includes(runToken), false)
})

test('cleanup atomically replaces raw ownership with a validated CLEANED marker', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-cleaned-transition-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-cleaned-transition-a1'
  const runToken = 'review1-cleaned-transition-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: 'fx_p0_user_e2e_cleaned_transition_a1'
  })
  markP0RedisSnapshotReady(control, [], [], 'REDIS_RELEASE_ARMED')
  await installStrictCleanupComposeProof(control, artifactBase)
  const cleanedPath = join(control.runRoot, 'control', 'cleaned.json')

  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId,
      runToken,
      resourcesCleaned: true,
      redisReleaseReceipt: control.ownerId,
      replaceOwnership: async () => { throw new Error('INJECTED_OWNERSHIP_REPLACE_FAILURE') }
    }),
    /INJECTED_OWNERSHIP_REPLACE_FAILURE/
  )
  assert.equal(existsSync(control.ownershipPath), true)
  assert.equal(existsSync(cleanedPath), false)

  await smokeContracts.completeControlCleanup({
    artifactBase,
    runId,
    runToken,
    resourcesCleaned: true,
    redisReleaseReceipt: control.ownerId,
    now: () => '2026-07-15T06:00:00.000Z'
  })
  assert.equal(existsSync(control.ownershipPath), true)
  assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).status, 'CLEANED')
  assert.equal(existsSync(cleanedPath), false)

  const forgedRunId = 'p0-review1-forged-cleaned-a1'
  const forgedControl = join(artifactBase, forgedRunId, 'control')
  mkdirSync(forgedControl, { recursive: true })
  writeFileSync(join(forgedControl, 'ownership.json'), `${JSON.stringify({
    schemaVersion: 1,
    status: 'CLEANED',
    runId: forgedRunId,
    ownerId: '0'.repeat(64),
    cleanedAt: '2026-07-15T06:00:00.000Z'
  })}\n`)
  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId: forgedRunId,
      runToken: 'review1-forged-cleaned-owner-token-a1',
      resourcesCleaned: true,
      redisReleaseReceipt: '0'.repeat(64)
    }),
    /P0_CLEANED_MARKER_INVALID/
  )
})

test('default P0 Redis journal snapshots every exact key and records touched keys before mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-default-redis-journal-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-default-redis-a1'
  const runToken = 'review1-default-redis-owner-token-a1'
  const expectedKeys = [
    'quote:BTCUSDT',
    'quote:ETHUSDT',
    'quote:BNBUSDT',
    'quote:SOLUSDT',
    'quote:XRPUSDT',
    'quote:BTCUSDT-PERP',
    'quote:ETHUSDT-PERP',
    'quote:BNBUSDT-PERP',
    'quote:SOLUSDT-PERP',
    'quote:XRPUSDT-PERP'
  ]
  const redisReads = []
  const redisRequests = []
  const transactionVerifications = []
  let redisOwner = null
  const identity = review1P0Identity()
  const redisBinding = {
    id: 'b'.repeat(64),
    image: 'redis:7',
    host: '127.0.0.1',
    hostPort: 6379
  }
  let ownershipPath
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args, details) => {
      const redisState = JSON.parse(readFileSync(ownershipPath, 'utf8')).redisState
      redisRequests.push({
        args: [...args],
        host: details.host,
        port: details.port,
        redisState
      })
      if (args[0] === 'SET' && args[1] === 'p0:e2e:owner') {
        redisOwner = args[2]
        return 'OK'
      }
      if (args[0] === 'GET' && expectedKeys.includes(args[1])) {
        redisReads.push(args[1])
        return null
      }
      throw new Error(`P0_TEST_UNEXPECTED_REDIS: ${args[0]}`)
    },
    () => redisBinding,
    async (expectedBinding) => {
      transactionVerifications.push({
        expectedBinding: structuredClone(expectedBinding),
        requestCount: redisRequests.length
      })
      return structuredClone(redisBinding)
    }
  )
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${runId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const suffixes = ['abcdef123456', 'abcdef123457']
  const reservation = await smokeContracts.prepareP0RunReservation({
    options,
    plan,
    artifactBase,
    inheritedEnv: {},
    captureIdentity: async () => identity,
    databaseExists: async () => false,
    nextRunToken: () => runToken,
    nextRandomSuffix: () => suffixes.shift(),
    infrastructure: review1SafetyInfrastructure()
  })
  ownershipPath = reservation.ownershipPath
  const setRedisState = (redisState) => {
    const active = JSON.parse(readFileSync(ownershipPath, 'utf8'))
    active.redisState = redisState
    writeFileSync(ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  }
  setRedisState('ACQUIRE_ARMED')
  await smokeContracts.acquireRedisOwnership({ redis, runToken, role: 'parent' })
  setRedisState('OWNED')
  const snapshot = await smokeContracts.snapshotRedisKeys({ redis, keys: expectedKeys })
  const redisPath = join(reservation.runRoot, 'control', 'redis.json')
  const recovery = smokeContracts.createP0RedisRecoveryState({
    runId,
    ownerId: reservation.ownerId,
    inventory: expectedKeys,
    snapshot,
    touchedKeys: []
  })
  writeFileSync(redisPath, `${JSON.stringify(recovery, null, 2)}\n`)
  setRedisState('SNAPSHOT_READY')
  const initial = JSON.parse(readFileSync(redisPath, 'utf8'))

  assert.equal(redisOwner, runToken)
  assert.deepEqual(redisReads, expectedKeys)
  assert.deepEqual(initial.snapshot.map(({ key }) => key), expectedKeys)
  assert.deepEqual(initial.touchedKeys, [])
  assert.deepEqual(transactionVerifications, [0, 0, 1, 1].map((requestCount) => ({
    expectedBinding: redisBinding,
    requestCount
  })))
  assert.deepEqual(redisRequests.map(({ args, host, port, redisState }) => ({
    command: args[0],
    key: args[1],
    host,
    port,
    redisState
  })), [
    {
      command: 'SET',
      key: 'p0:e2e:owner',
      host: '127.0.0.1',
      port: 6379,
      redisState: 'ACQUIRE_ARMED'
    },
    ...expectedKeys.map((key) => ({
      command: 'GET',
      key,
      host: '127.0.0.1',
      port: 6379,
      redisState: 'OWNED'
    }))
  ])

  let mutationCalls = 0
  await smokeContracts.runP0TrackedRedisMutation({
    artifactBase,
    runId,
    runToken,
    key: 'quote:BTCUSDT',
    mutate: async () => {
      mutationCalls += 1
      const beforeMutation = JSON.parse(readFileSync(redisPath, 'utf8'))
      const active = JSON.parse(readFileSync(ownershipPath, 'utf8'))
      assert.deepEqual(beforeMutation.touchedKeys, ['quote:BTCUSDT'])
      assert.equal(active.redisState, 'SNAPSHOT_READY')
    }
  })
  assert.equal(mutationCalls, 1)
  assert.deepEqual(JSON.parse(readFileSync(redisPath, 'utf8')).touchedKeys, ['quote:BTCUSDT'])
})

function createCleanupOnlyProcessTreeProvider(label) {
  return {
    capability: 'WINDOWS_JOB_OBJECT_V1',
    platform: 'win32',
    verification: 'INDEPENDENTLY_VERIFIED',
    async acquireNativeProcessHandle(pid) {
      assert.fail(`${label}: unexpected journaled process ${pid}`)
    }
  }
}

async function installStrictCleanupComposeProof(control, artifactBase) {
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const composeIdentity = {
    project: 'infra',
    postgres: {
      id: 'a'.repeat(64),
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64),
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379
    }
  }
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.composeTarget = {
    expectedProject: composeTarget.expectedProject,
    composeFiles: [...composeTarget.composeFiles]
  }
  active.composeIdentity = composeIdentity
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  return {
    composeTarget: active.composeTarget,
    composeIdentity
  }
}

function createStrictCleanupComposeInfrastructure(proof, {
  assertPortsFree = async () => {},
  expectedSignal,
  record = () => {}
} = {}) {
  return {
    async inspectDockerDaemon(details = {}) {
      if (expectedSignal !== undefined) assert.equal(details.signal, expectedSignal)
      record('compose:daemon', details.signal)
      return { endpoint: 'npipe:////./pipe/docker_engine' }
    },
    async verifyComposeContainers({ composeFiles, expectedProject, signal } = {}) {
      if (expectedSignal !== undefined) assert.equal(signal, expectedSignal)
      assert.deepEqual(composeFiles, proof.composeTarget.composeFiles)
      assert.equal(expectedProject, proof.composeTarget.expectedProject)
      record('compose:containers', signal)
      return structuredClone(proof.composeIdentity)
    },
    assertPortsFree
  }
}

test('cleanup retains Redis ownership and ACTIVE control state on any earlier or owner failure', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-cleanup-fail-closed-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))

  const runScenario = async ({ suffix, processFailure, ownerValue }) => {
    const artifactBase = join(root, suffix, 'artifacts')
    const runId = `p0-review1-cleanup-${suffix}-a1`
    const runToken = `review1-cleanup-${suffix}-owner-token-a1`
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: 'discovery',
      selection: [],
      database: {}
    })
    markP0RedisSnapshotReady(control)
    const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)
    const events = []
    let redisOwner = ownerValue === 'OWNED' ? runToken : ownerValue
    const dependencies = smokeContracts.createDefaultP0Dependencies({
      artifactBase,
      inheritedEnv: {},
      processTreeProvider: createCleanupOnlyProcessTreeProvider(
        `cleanup-fail-closed:${suffix}`
      ),
      redis: {
        async get() { events.push('redis:get'); return redisOwner },
        async compareDelete() { events.push('redis:compare-delete'); redisOwner = null; return true },
        async restoreExact() { events.push('redis:restore') },
        async deleteExact() { events.push('redis:delete') }
      },
      postgres: {
        async readDatabaseOwnership() { events.push('postgres:read'); return null }
      },
      infrastructure: createStrictCleanupComposeInfrastructure(cleanupProof, {
        async assertPortsFree() { events.push('ports:free') }
      }),
      processManager: {
        async stopParentBackend() {
          events.push('process:stop')
          if (processFailure) throw new Error('INJECTED_PROCESS_CLEANUP_FAILURE')
        }
      }
    })
    const context = {
      artifactBase,
      runRoot: control.runRoot,
      ownerToken: runToken,
      ownerId: control.ownerId,
      redisSnapshot: [],
      touchedRedisKeys: []
    }
    await assert.rejects(
      dependencies.cleanup(context, {}, { runId }),
      processFailure ? /INJECTED_PROCESS_CLEANUP_FAILURE|P0_CLEANUP_FAILED/ : /P0_REDIS_OWNER_MISMATCH/
    )
    assert.equal(existsSync(control.ownershipPath), true)
    assert.equal(existsSync(join(control.runRoot, 'control', 'cleaned.json')), false)
    return { events, redisOwner, runToken }
  }

  const earlierFailure = await runScenario({
    suffix: 'process-failure',
    processFailure: true,
    ownerValue: 'OWNED'
  })
  assert.deepEqual(earlierFailure.events, ['process:stop'])
  assert.equal(earlierFailure.redisOwner, earlierFailure.runToken)

  const missingOwner = await runScenario({
    suffix: 'missing-owner',
    processFailure: false,
    ownerValue: null
  })
  assert.deepEqual(missingOwner.events, ['process:stop', 'ports:free', 'redis:get'])
  assert.equal(missingOwner.redisOwner, null)
})

function exactP0ReportPhaseEvidence(context, plan) {
  return {
    status: 'PASS',
    kind: 'P0_REPORT_BOUNDARY',
    scope: plan.scope,
    finalWriter: 'PENDING',
    identity: {
      runId: context.options?.runId ?? context.runId,
      ownerId: context.ownerId,
      reportPath: resolve(context.runRoot, 'report.json')
    }
  }
}

test('default phase engine restarts the owned backend for each planned profile before dispatch', async () => {
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-profile-engine-a1',
    '--phase=selected',
    '--case=SPOT-01,SPOT-04,FUND-01,LIQ-01'
  ])
  const workerKeys = [
    'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
    'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
    'TRADING_FUNDING_ENABLED',
    'TRADING_LIQUIDATION_ENABLED'
  ]
  const expectedWorkers = {
    UI_CORE: 'false,false,false,false',
    ORDER_TRIGGER: 'true,true,false,false',
    FUNDING_ONLY: 'false,false,true,false',
    LIQUIDATION_ONLY: 'false,false,false,true'
  }
  const expectedCase = {
    UI_CORE: 'SPOT-01',
    ORDER_TRIGGER: 'SPOT-04',
    FUNDING_ONLY: 'FUND-01',
    LIQUIDATION_ONLY: 'LIQ-01'
  }
  const events = []
  let activeProfile = null
  const dependencies = {
    installSignalHandlers() { return () => {} },
    async initializeOwnership() {
      return {
        runRoot: resolve(tmpdir(), options.runId),
        options,
        ownerToken: 'review1-profile-engine-owner-token-a1',
        ownerId: '1'.repeat(64),
        databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_profile_engine_a1',
        matrixDatabase: 'fx_p0_user_e2e_profile_engine_a1',
        inheritedEnv: {},
        caseResults: []
      }
    },
    phaseOperations: {
      async runPreflight() { return { id: 'AUTH-01', status: 'PASS' } },
      async runAuthority() { return { id: 'AUTH-03', status: 'PASS' } },
      async stopProfileBackend(_context, profile) {
        events.push(`stop:${profile}`)
        activeProfile = null
      },
      async assertProfilePortFree(port, _context, profile) {
        events.push(`free:${profile}:${port}`)
      },
      async startProfileBackend({ profile, environment, databaseUrl }) {
        assert.equal(databaseUrl, 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_profile_engine_a1')
        events.push(`start:${profile}:${workerKeys.map((key) => environment[key]).join(',')}`)
        activeProfile = profile
        return { profile }
      },
      async waitForProfileHealth(_backend, _context, profile) {
        events.push(`health:${profile}`)
      },
      async waitForProfileBusinessEndpoint(_backend, _context, profile) {
        events.push(`business:${profile}`)
      },
      async verifyProfileDatabaseIdentity(_backend, _context, profile) {
        events.push(`database:${profile}`)
      },
      async writeReport(context, plan) {
        return exactP0ReportPhaseEvidence(context, plan)
      }
    },
    async dispatchCase(definition) {
      assert.equal(activeProfile, definition.requiredSubruns[0].profile)
      events.push(`dispatch:${activeProfile}:${definition.id}`)
      return formalP0CaseFragment(
        definition,
        definition.requiredSubruns,
        { scopeComplete: true }
      )
    },
    handlers: Object.create(null),
    async writeReport() { return { status: 'TEST' } },
    async cleanup() { return { status: 'CLEANED' } }
  }

  await smokeContracts.runP0Suite(options, dependencies)
  assert.deepEqual(events, [
    'stop:UI_CORE',
    'free:UI_CORE:18086',
    `start:UI_CORE:${expectedWorkers.UI_CORE}`,
    'health:UI_CORE',
    'business:UI_CORE',
    'database:UI_CORE',
    ...Object.keys(expectedWorkers).flatMap((profile) => [
      `stop:${profile}`,
      `free:${profile}:18086`,
      `start:${profile}:${expectedWorkers[profile]}`,
      `health:${profile}`,
      `business:${profile}`,
      `database:${profile}`,
      `dispatch:${profile}:${expectedCase[profile]}`
    ])
  ])
})

test('authority bundle evidence is complete, fail-closed, and separates fixture verdict', () => {
  assert.equal(typeof smokeContracts.evaluateAuthorityBundleEvidence, 'function')
  assert.equal(typeof smokeContracts.runAuthorityBundleGate, 'function')
  const passingChecks = smokeContracts.AUTHORITY_BUNDLE_REQUIRED_CHECKS.map((id) => ({
    id,
    status: 'PASS'
  }))

  assert.deepEqual(
    smokeContracts.evaluateAuthorityBundleEvidence({
      checks: passingChecks,
      cleanup: { status: 'PASS' }
    }),
    {
      status: 'PASS',
      authorityBundleFixture: 'PASS',
      checks: passingChecks,
      cleanup: { status: 'PASS' }
    }
  )

  const comparisonFailure = passingChecks.map((check) => (
    check.id === 'controlled-perp-risk'
      ? { ...check, status: 'FAIL', reason: 'maintenance mismatch' }
      : check
  ))
  assert.deepEqual(
    smokeContracts.evaluateAuthorityBundleEvidence({
      checks: comparisonFailure,
      cleanup: { status: 'PASS' }
    }).authorityBundleFixture,
    'BLOCKED',
    'a fully executed comparison failure is evidence, not a matrix-wide exception'
  )

  assert.throws(
    () => smokeContracts.evaluateAuthorityBundleEvidence({
      checks: passingChecks.slice(1),
      cleanup: { status: 'PASS' }
    }),
    /P0_AUTHORITY_EVIDENCE_INCOMPLETE/
  )
  assert.throws(
    () => smokeContracts.evaluateAuthorityBundleEvidence({
      checks: passingChecks,
      cleanup: { status: 'FAIL' }
    }),
    /P0_AUTHORITY_CLEANUP_FAILED/
  )
})

test('authority baseline fill and Perp risk checks use independent market evidence', () => {
  const quote = {
    symbol: 'BTCUSDT',
    bid: '99',
    ask: '100',
    providerCode: 'binance',
    sourceMode: 'PUBLIC_EXTERNAL'
  }
  const expectedFill = financialOracles.marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'BUY',
    bid: quote.bid,
    ask: quote.ask
  })
  const fillProbe = {
    trade: {
      id: '11111111-1111-4111-8111-111111111111',
      orderId: '22222222-2222-4222-8222-222222222222',
      symbol: quote.symbol,
      productType: 'CRYPTO_SPOT',
      side: 'BUY',
      lots: '1',
      price: expectedFill.filledPrice,
      realizedPnl: '0',
      fee: '0.050005',
      feeAsset: 'BTC',
      liquidityRole: 'TAKER',
      providerCode: quote.providerCode,
      sourceMode: quote.sourceMode
    }
  }
  assert.equal(
    smokeContracts.authorityFillCheck(fillProbe, quote, BTC_RULES, 'CRYPTO_SPOT').pass,
    true
  )
  assert.equal(
    smokeContracts.authorityFillCheck({
      trade: { ...fillProbe.trade, price: '101' }
    }, quote, BTC_RULES, 'CRYPTO_SPOT').pass,
    false,
    'a filled order at the wrong authority price must block the fixture'
  )

  const position = {
    id: '33333333-3333-4333-8333-333333333333',
    symbol: 'BTCUSDT-PERP',
    side: 'LONG',
    productType: 'LINEAR_PERP',
    positionMode: 'ONE_WAY',
    positionSide: 'LONG',
    marginMode: 'CROSS',
    leverage: '10',
    lots: '0.001',
    openPrice: '100',
    markPrice: '110',
    floatingPnl: '0.01000000',
    marginHeld: '0.01000000',
    maintenanceMargin: '0.00055000',
    maintenanceMarginRate: '0.005',
    status: 'OPEN'
  }
  const riskProbe = {
    position,
    snapshot: {
      summary: {
        balance: '50000',
        equity: '50000.01',
        usedMargin: '0.01000000',
        freeMargin: '50000',
        openFloatingPnl: '0.01000000',
        maintenanceMargin: '0.00055000'
      }
    }
  }
  const reference = { symbol: position.symbol, mark: '110' }
  const risk = smokeContracts.authorityPerpRiskCheck(
    riskProbe,
    reference,
    { ...BTC_RULES, stepSize: '0.001', minQty: '0.001' }
  )
  assert.equal(risk.markPass, true)
  assert.equal(risk.riskPass, true)
  assert.equal(
    smokeContracts.authorityPerpRiskCheck(
      { ...riskProbe, position: { ...position, markPrice: '111' } },
      reference,
      { ...BTC_RULES, stepSize: '0.001', minQty: '0.001' }
    ).markPass,
    false,
    'position mark must be compared with the independent reference mark'
  )
  assert.equal(
    smokeContracts.authorityPerpRiskCheck({
      ...riskProbe,
      snapshot: {
        summary: { ...riskProbe.snapshot.summary, openFloatingPnl: '0.02000000' }
      }
    }, reference, { ...BTC_RULES, stepSize: '0.001', minQty: '0.001' }).riskPass,
    false,
    'account summary UPL must agree with the independent position oracle'
  )

  const gateSource = smokeContracts.runAuthorityBundleGate.toString()
  assert.match(gateSource, /baselineSpotFill\.pass/)
  assert.match(gateSource, /baselinePerpFill\.pass/)
  assert.match(
    gateSource,
    /authorityPerpRiskCheck\(\s*baselinePerpProbe,\s*baselineReference,/,
    'the baseline gate must not replace the independent reference mark with position evidence'
  )
})

test('authority checkpoints retain independent oracle evidence without a browser page', async (t) => {
  const artifactRoot = mkdtempSync(join(tmpdir(), 'p0-authority-oracle-checkpoint-'))
  t.after(() => rmSync(artifactRoot, { recursive: true, force: true }))
  const oracleEvidence = [{
    symbol: 'BTCUSDT-PERP',
    markPrice: '60005.00000000',
    floatingPnl: '1.23450000',
    maintenanceMargin: '0.30002500'
  }]
  const checkpoint = await smokeContracts.captureCheckpoint(
    { run: { artifactRoot } },
    'controlled-risk',
    {
      caseId: 'authority',
      oracleEvidence: async () => oracleEvidence
    }
  )

  assert.deepEqual(checkpoint.oracleEvidence, oracleEvidence)
  assert.deepEqual(checkpoint.uiEvidence, [])
  assert.deepEqual(checkpoint.artifactHashes, {})
})

test('a complete authority fixture BLOCKED remains control PASS and does not abort matrix phases', async () => {
  const controlResults = []
  const phases = []
  await smokeContracts.executeP0PlanPhases({
    plan: { phases: ['authority', 'ui-core'] },
    context: {},
    controlResults,
    operations: {
      async runAuthority() {
        phases.push('authority')
        return {
          status: 'PASS',
          authorityBundleFixture: 'BLOCKED',
          checks: [{ id: 'controlled-perp-risk', status: 'FAIL' }]
        }
      },
      async runMatrixPhase(phase) {
        phases.push(phase)
      }
    }
  })

  assert.deepEqual(phases, ['authority', 'ui-core'])
  assert.equal(controlResults[0].status, 'PASS')
  assert.equal(controlResults[0].evidence.authorityBundleFixture, 'BLOCKED')
})

test('authority fixture BLOCKED blocks only declared dependent subruns and executes the rest', async () => {
  const blockedAuthority = {
    status: 'COMPLETE',
    authorityBundleFixture: 'BLOCKED'
  }
  const wholeCase = P0_CASES.find(({ id }) => id === 'SPOT-05')
  const independent = P0_CASES.find(({ id }) => id === 'SPOT-01')
  assert.deepEqual(
    smokeContracts.authorityBlockedSubruns(
      wholeCase,
      wholeCase.requiredSubruns,
      blockedAuthority
    ),
    wholeCase.requiredSubruns
  )
  assert.deepEqual(
    smokeContracts.authorityBlockedSubruns(
      independent,
      independent.requiredSubruns,
      blockedAuthority
    ),
    []
  )

  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=PERP-01',
    '--run-id=p0-authority-subrun-block-a1'
  ])
  const dispatched = []
  const caseAuthority = {
    status: 'PENDING',
    authorityBundleFixture: 'BLOCKED'
  }
  let persisted
  let execution
  await smokeContracts.runP0Suite(options, {
    installSignalHandlers() { return () => {} },
    async initializeOwnership() {
      return {
        runRoot: resolve(tmpdir(), options.runId),
        options,
        ownerId: 'b'.repeat(64),
        matrixDatabase: 'fx_p0_user_e2e_authority_subrun_block_a1',
        databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_authority_subrun_block_a1',
        inheritedEnv: {},
        caseResults: []
      }
    },
    createP0Context(prepared) {
      return {
        run: { artifactRoot: prepared.runRoot },
        authority: caseAuthority,
        evidence: {
          writeCaseResultAtomic(_path, result) {
            persisted = result
          }
        }
      }
    },
    phaseOperations: {
      async runPreflight() {
        return { id: 'AUTH-01', status: 'PASS' }
      },
      async runAuthority() {
        return {
          status: 'PASS',
          authorityBundleFixture: 'BLOCKED',
          checks: [{ id: 'controlled-perp-risk', status: 'FAIL' }]
        }
      },
      async writeReport(context, plan) {
        return exactP0ReportPhaseEvidence(context, plan)
      }
    },
    async dispatchCase(definition) {
      dispatched.push(definition.requiredSubruns.map(({ id }) => id))
      return formalP0CaseFragment(definition, definition.requiredSubruns)
    },
    handlers: Object.create(null),
    async writeReport(value) {
      execution = value
      return { status: 'TEST' }
    },
    async cleanup() {
      return { status: 'CLEANED' }
    }
  })

  assert.deepEqual(dispatched, [['desktop-core']])
  const result = execution.caseResults[0]
  assert.equal(persisted, result)
  assert.equal(result.id, 'PERP-01')
  assert.equal(result.status, 'BLOCKED')
  assert.deepEqual(
    result.subruns.map(({ id, status }) => ({ id, status })),
    [
      { id: 'desktop-core', status: 'PASS' },
      { id: 'desktop-target-mark', status: 'BLOCKED' }
    ]
  )
  assert.equal(
    result.subruns[1].failureOrBlocker.reasonCode,
    'AUTHORITY_BUNDLE_FIXTURE_MISSING'
  )
  assert.equal(caseAuthority.status, 'COMPLETE')
  assert.equal(caseAuthority.authorityBundleFixture, 'BLOCKED')
})

test('P0 planner intersects phase case profile and viewport into one effective subrun selection', () => {
  const source = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const fundingDesktop = source.requiredSubruns.filter(({ profile, viewport }) => (
    profile === 'FUNDING_ONLY' && viewport === 'desktop'
  ))
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-effective-selection-a1',
    '--phase=source',
    '--case=SOURCE-03',
    '--profile=FUNDING_ONLY',
    '--viewport=desktop'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)

  assert.deepEqual(plan.definitions.map(({ id }) => id), ['SOURCE-03'])
  assert.equal(plan.definitions[0], source)
  assert.deepEqual(plan.definitions[0].requiredSubruns, source.requiredSubruns)
  assert.deepEqual(plan.profiles, ['FUNDING_ONLY'])
  assert.deepEqual(plan.executionEntries.map(({ id, phase, selectedSubruns, cropped }) => ({
    id,
    phase,
    selectedSubruns,
    cropped
  })), [{
    id: 'SOURCE-03',
    phase: 'source',
    selectedSubruns: fundingDesktop,
    cropped: true
  }])

  for (const arguments_ of [
    [
      '--suite=p0',
      '--run-id=p0-review1-phase-case-empty-a1',
      '--phase=ui-core',
      '--case=LIQ-01'
    ],
    [
      '--suite=p0',
      '--run-id=p0-review1-profile-viewport-empty-a1',
      '--phase=source',
      '--case=SOURCE-03',
      '--profile=FUNDING_ONLY',
      '--viewport=mobile'
    ]
  ]) {
    assert.throws(
      () => p0CaseContracts.planP0Execution(
        p0CaseContracts.parseP0Cli(arguments_),
        P0_CASES
      ),
      /P0_CLI_EMPTY_SELECTION/
    )
  }
})

test('filtered P0 report accepts only selected subruns with scopeComplete false and PARTIAL_PASS', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-filtered-report-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-filtered-report-a1',
    '--phase=source',
    '--case=SOURCE-03',
    '--profile=FUNDING_ONLY',
    '--viewport=desktop'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const selectedSubruns = plan.executionEntries[0].selectedSubruns
  const caseResult = {
    id: 'SOURCE-03',
    status: 'PASS',
    scopeComplete: false,
    subruns: selectedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
  const runRoot = join(root, options.runId)
  mkdirSync(runRoot, { recursive: true })
  const report = await writeFullyFakeP0Report({
    plan,
    phaseResult: { matrixPhases: 1 },
    caseResults: [caseResult]
  }, {
    assertIdentity: async () => {},
    options,
    runRoot,
    ownerId: '2'.repeat(64),
    canonicalDatabase: 'fx_p0_user_e2e_filtered_canonical_a1',
    matrixDatabase: 'fx_p0_user_e2e_filtered_matrix_a1',
    selection: {
      caseIds: ['SOURCE-03'],
      phases: [],
      profiles: ['FUNDING_ONLY'],
      viewports: ['desktop']
    }
  }, {
    now: () => '2026-07-15T07:00:00.000Z'
  })

  assert.equal(report.verdict, 'PARTIAL_PASS')
  assert.deepEqual(report.caseResults, [caseResult])
  assert.equal(JSON.parse(readFileSync(join(runRoot, 'report.json'), 'utf8')).verdict, 'PARTIAL_PASS')
})

test('each P0 phase and attempt journals a distinct owned database segment', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-phase-database-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-phase-database-a1'
  const runToken = 'review1-phase-database-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: ['SOURCE-03'],
    database: {}
  })
  const databases = new Map()
  const postgres = {
    async executeAdminSql(sql) {
      let match = sql.match(/^CREATE DATABASE "([a-z0-9_]+)"$/)
      if (match) {
        const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
        const journal = active.journal.resources.find(({ type, id }) => (
          type === 'database' && id === match[1]
        ))
        assert.equal(journal?.state, 'PLANNED')
        databases.set(match[1], { ownerMarker: null, timezone: null })
        return
      }
      match = sql.match(/^COMMENT ON DATABASE "([a-z0-9_]+)" IS '(.+)'$/)
      if (match) { databases.get(match[1]).ownerMarker = match[2]; return }
      match = sql.match(/^ALTER DATABASE "([a-z0-9_]+)" SET timezone TO 'UTC'$/)
      if (match) { databases.get(match[1]).timezone = 'UTC'; return }
      throw new Error(`UNEXPECTED_SQL: ${sql}`)
    },
    async readDatabaseOwnership(segmentName) {
      const state = databases.get(segmentName)
      return state ? { segmentName, ownerMarker: state.ownerMarker } : null
    },
    async probeDatabase({ segmentName }) {
      const state = databases.get(segmentName)
      return {
        currentDatabase: segmentName,
        ownerMarker: state?.ownerMarker,
        timezone: state?.timezone
      }
    }
  }
  assert.equal(typeof smokeContracts.runP0JournaledMutation, 'function')
  const context = {
    options: { runId },
    runRoot: control.runRoot,
    ownerToken: runToken,
    ownerId: control.ownerId,
    ownedDatabaseSegments: []
  }
  const requests = [
    { phase: 'source', attempt: 1 },
    { phase: 'source', attempt: 2 },
    { phase: 'resilience', attempt: 1 }
  ]
  const prepared = []
  for (const { phase, attempt } of requests) {
    const randomSuffix = createHash('sha256')
      .update(`${context.ownerId}:${phase}:${attempt}`)
      .digest('hex')
      .slice(0, 12)
    const segmentName = smokeContracts.databaseSegmentForAttempt({
      phase,
      attempt,
      randomSuffix
    })
    const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
    await smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource: { type: 'database', id: segmentName, phase, attempt },
      start: async () => {
        await smokeContracts.createOwnedDatabase({ segmentName, runToken, postgres })
        await smokeContracts.alterOwnedDatabaseTimezone({ segmentName, runToken, postgres })
        return smokeContracts.verifyDatabaseIdentity({
          segmentName,
          runToken,
          databaseUrl,
          postgres
        })
      }
    })
    const descriptor = { phase, attempt, segmentName, databaseUrl }
    context.ownedDatabaseSegments.push(segmentName)
    prepared.push(descriptor)
  }

  assert.equal(new Set(prepared.map(({ segmentName }) => segmentName)).size, requests.length)
  assert.deepEqual(prepared.map(({ phase, attempt }) => ({ phase, attempt })), requests)
  assert.equal(prepared.every(({ segmentName, databaseUrl }) => (
    databaseUrl === `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  )), true)
  assert.deepEqual(context.ownedDatabaseSegments, prepared.map(({ segmentName }) => segmentName))
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.deepEqual(active.journal.resources
    .filter(({ type }) => type === 'database')
    .map(({ id, state }) => ({ id, state })),
  prepared.map(({ segmentName }) => ({ id: segmentName, state: 'STARTED' })))
})

test('managed backend receives verified compose credentials and exact database identity probes', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-managed-database-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runToken = 'review1-managed-database-owner-token-a1'
  const events = []
  const databases = new Map()
  const postgres = {
    async executeAdminSql(sql) {
      let match = sql.match(/^CREATE DATABASE "([a-z0-9_]+)"$/)
      if (match) { databases.set(match[1], { ownerMarker: null, timezone: null }); return }
      match = sql.match(/^COMMENT ON DATABASE "([a-z0-9_]+)" IS '(.+)'$/)
      if (match) { databases.get(match[1]).ownerMarker = match[2]; return }
      match = sql.match(/^ALTER DATABASE "([a-z0-9_]+)" SET timezone TO 'UTC'$/)
      if (match) { databases.get(match[1]).timezone = 'UTC'; return }
      match = sql.match(/^DROP DATABASE "([a-z0-9_]+)" WITH \(FORCE\)$/)
      if (match) { databases.delete(match[1]); return }
      throw new Error(`UNEXPECTED_SQL: ${sql}`)
    },
    async readDatabaseOwnership(segmentName) {
      const state = databases.get(segmentName)
      return state ? { segmentName, ownerMarker: state.ownerMarker } : null
    },
    async probeDatabase({ segmentName }) {
      events.push(`probe:${segmentName}`)
      const state = databases.get(segmentName)
      return {
        currentDatabase: segmentName,
        ownerMarker: state?.ownerMarker,
        timezone: state?.timezone
      }
    }
  }
  let redisOwner = null
  let redisReceipt = null
  const redis = {
    async setNx(_key, value) { redisOwner = value; return true },
    async get() { return redisOwner },
    async readExact() { return { exists: false, value: null, expiresAtMs: null } },
    async deleteExact() {},
    async restoreExact() {},
    async compareDelete(_key, value) {
      if (redisOwner !== value) return false
      redisOwner = null
      return true
    },
    async releaseOwnershipWithReceipt({ runToken: value, ownerId }) {
      if (redisOwner === value) {
        redisOwner = null
        redisReceipt = ownerId
        return 'RELEASED'
      }
      return redisOwner === null && redisReceipt === ownerId
        ? 'ALREADY_RELEASED'
        : 'MISMATCH'
    },
    async removeCleanupReceipt({ ownerId }) {
      if (redisReceipt === ownerId) redisReceipt = null
    }
  }
  const infrastructure = {
    ...review1SafetyInfrastructure(events),
    async verifyComposeContainers() {
      return {
        project: 'infra',
        postgres: { id: 'a'.repeat(64), image: 'postgres:16', host: '127.0.0.1', hostPort: 5432 },
        redis: { id: 'b'.repeat(64), image: 'redis:7', host: '127.0.0.1', hostPort: 6379 },
        credentials: { username: 'postgres', password: 'password' }
      }
    },
    async assertPortsFree() { events.push('ports:free') }
  }
  const started = []
  const processManager = {
    async startOwnedBackend({ profile, environment }) {
      const segmentName = environment.DATABASE_URL.split('/').at(-1)
      for (const [key, value] of Object.entries({
        DATABASE_USERNAME: 'postgres',
        DATABASE_PASSWORD: 'password',
        SPRING_DATASOURCE_USERNAME: 'postgres',
        SPRING_DATASOURCE_PASSWORD: 'password'
      })) assert.equal(environment[key], value, `${profile} ${key}`)
      started.push({ profile, segmentName, environment })
      events.push(`start:${segmentName}`)
      const pid = 6000 + started.length
      return {
        pid,
        segmentName,
        processIdentity: {
          pid,
          startedAt: `managed-fixture-${pid}`,
          processFingerprint: `sha256:${'8'.repeat(64)}`
        }
      }
    },
    async waitForBackendHealth({ segmentName }) { events.push(`health:${segmentName}`) },
    async waitForBusinessEndpoint({ segmentName }) { events.push(`business:${segmentName}`) },
    async stopOwnedBackend() { events.push('backend:stop-owned') },
    async stopParentBackend() { events.push('backend:stop-parent') },
    async acquireNativeProcessHandle() {
      return {
        async inspectIdentity() { return null },
        async terminateTree() {
          throw new Error('TERMINATE_MUST_NOT_RUN_FOR_STOPPED_MANAGED_FIXTURE')
        }
      }
    }
  }
  const identity = review1P0Identity()
  const verifiedComposeIdentity = await infrastructure.verifyComposeContainers()
  const credentials = verifiedComposeIdentity.credentials
  const prepareDatabase = async ({ phase, attempt }, context) => {
    const randomSuffix = createHash('sha256')
      .update(`${context.ownerId}:${phase}:${attempt}`)
      .digest('hex')
      .slice(0, 12)
    const segmentName = smokeContracts.databaseSegmentForAttempt({
      phase,
      attempt,
      randomSuffix
    })
    const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
    await smokeContracts.createOwnedDatabase({ segmentName, runToken, postgres })
    await smokeContracts.alterOwnedDatabaseTimezone({ segmentName, runToken, postgres })
    await smokeContracts.verifyDatabaseIdentity({
      segmentName,
      runToken,
      databaseUrl,
      postgres
    })
    context.ownedDatabaseSegments.push(segmentName)
    return { phase, attempt, segmentName, databaseUrl }
  }
  const startManagedBackend = ({ profile, environment }) => processManager.startOwnedBackend({
    profile,
    environment: {
      ...environment,
      DATABASE_USERNAME: credentials.username,
      DATABASE_PASSWORD: credentials.password,
      SPRING_DATASOURCE_USERNAME: credentials.username,
      SPRING_DATASOURCE_PASSWORD: credentials.password
    }
  })
  const dependencies = {
    installSignalHandlers() { return () => {} },
    async initializeOwnership(receivedOptions) {
      const runRoot = join(artifactBase, receivedOptions.runId)
      mkdirSync(runRoot, { recursive: true })
      await redis.setNx('p0:e2e:owner', runToken)
      return {
        artifactBase,
        runRoot,
        options: receivedOptions,
        ownerToken: runToken,
        ownerId: createHash('sha256').update(runToken).digest('hex'),
        canonicalDatabase: 'fx_p0_user_e2e_managed_canonical_1',
        matrixDatabase: 'fx_p0_user_e2e_managed_matrix_1',
        inheritedEnv: {},
        identity,
        composeIdentity: verifiedComposeIdentity,
        ownedDatabaseSegments: [],
        caseResults: [],
        async assertIdentity() {}
      }
    },
    phaseOperations: {
      async runPreflight(context) {
        const phaseDatabase = await prepareDatabase({ phase: 'preflight', attempt: 1 }, context)
        const backend = await startManagedBackend({
          profile: 'PREFLIGHT',
          environment: {
            DATABASE_URL: phaseDatabase.databaseUrl,
            SPRING_DATASOURCE_URL: phaseDatabase.databaseUrl
          }
        })
        await processManager.waitForBackendHealth(backend)
        await processManager.waitForBusinessEndpoint(backend)
        await smokeContracts.verifyDatabaseIdentity({
          segmentName: phaseDatabase.segmentName,
          runToken,
          databaseUrl: phaseDatabase.databaseUrl,
          postgres
        })
        await processManager.stopOwnedBackend(backend)
        return { id: 'AUTH-01', status: 'PASS' }
      },
      stopProfileBackend() { return processManager.stopParentBackend() },
      assertProfilePortFree() { return infrastructure.assertPortsFree() },
      prepareProfileDatabase(request, context) {
        return prepareDatabase(request, context)
      },
      startProfileBackend(input) { return startManagedBackend(input) },
      waitForProfileHealth(backend) { return processManager.waitForBackendHealth(backend) },
      waitForProfileBusinessEndpoint(backend) {
        return processManager.waitForBusinessEndpoint(backend)
      },
      verifyProfileDatabaseIdentity(_backend, context, _profile, phaseDatabase) {
        return smokeContracts.verifyDatabaseIdentity({
          segmentName: phaseDatabase.segmentName,
          runToken: context.ownerToken,
          databaseUrl: phaseDatabase.databaseUrl,
          postgres
        })
      },
      writeReport(context, plan) {
        return {
          status: 'PASS',
          kind: 'P0_REPORT_BOUNDARY',
          scope: plan.scope,
          finalWriter: 'PENDING',
          identity: {
            runId: context.options.runId,
            ownerId: context.ownerId,
            reportPath: join(context.runRoot, 'report.json')
          }
        }
      }
    },
    dispatchCase: async (definition) => {
      events.push(`dispatch:${definition.id}`)
      return formalP0CaseFragment(
        definition,
        definition.requiredSubruns,
        { scopeComplete: true }
      )
    },
    handlers: Object.create(null),
    async writeReport() { return { status: 'TEST' } },
    async cleanup(context) {
      await processManager.stopParentBackend()
      for (const segmentName of context.ownedDatabaseSegments.toReversed()) {
        await smokeContracts.dropOwnedDatabase({ segmentName, runToken, postgres })
      }
      const status = await redis.releaseOwnershipWithReceipt({
        runToken,
        ownerId: context.ownerId
      })
      assert.equal(status, 'RELEASED')
      await redis.removeCleanupReceipt({ ownerId: context.ownerId })
      return { status: 'CLEANED' }
    }
  }
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-managed-database-a1',
    '--phase=selected',
    '--case=SPOT-01'
  ])

  await smokeContracts.runP0Suite(options, dependencies)
  assert.equal(started.length, 2)
  assert.equal(new Set(started.map(({ segmentName }) => segmentName)).size, 2)
  for (const { segmentName } of started) {
    const business = events.indexOf(`business:${segmentName}`)
    const probeAfterBusiness = events.findIndex((event, index) => (
      index > business && event === `probe:${segmentName}`
    ))
    assert.ok(business >= 0, segmentName)
    assert.ok(probeAfterBusiness > business, segmentName)
  }
  const selectedSegment = started.at(-1).segmentName
  assert.ok(events.indexOf(`probe:${selectedSegment}`) < events.indexOf('dispatch:SPOT-01'))
  assert.equal(databases.size, 0)
  assert.equal(redisOwner, null)
})

test('standalone canonical owns snapshots restores and releases Redis exactly once', async (t) => {
  assert.equal(typeof smokeContracts.createStandaloneCanonicalRedisOwnership, 'function')
  const artifactRoot = mkdtempSync(join(tmpdir(), 'p0-review1-standalone-canonical-'))
  t.after(() => rmSync(artifactRoot, { recursive: true, force: true }))
  const runId = 'p0-review1-standalone-canonical-a1'
  const recoveryPath = join(artifactRoot, 'control', 'canonical-redis.json')
  mkdirSync(dirname(recoveryPath), { recursive: true })
  const runToken = 'review1-standalone-canonical-owner-token-a1'
  const ownerKey = 'p0:e2e:owner'
  const keys = [
    'quote:BTCUSDT',
    'quote:ETHUSDT',
    'quote:BNBUSDT',
    'quote:SOLUSDT',
    'quote:XRPUSDT',
    'quote:BTCUSDT-PERP',
    'quote:ETHUSDT-PERP',
    'quote:BNBUSDT-PERP',
    'quote:SOLUSDT-PERP',
    'quote:XRPUSDT-PERP'
  ]
  const state = new Map([
    ['quote:BTCUSDT', { value: 'before-spot', expiresAtMs: 1_900_000_000_000 }],
    ['quote:BTCUSDT-PERP', { value: 'before-perp', expiresAtMs: null }]
  ])
  const events = []
  let cleanupReceipt = null
  const redis = {
    runVerifiedTransaction(operation) { return operation() },
    async setNx(key, value) {
      events.push(`setNx:${key}`)
      if (state.has(key)) return false
      state.set(key, { value, expiresAtMs: null })
      return true
    },
    async get(key) { events.push(`get:${key}`); return state.get(key)?.value ?? null },
    async readExact(key) {
      events.push(`read:${key}`)
      const entry = state.get(key)
      return entry
        ? { exists: true, value: entry.value, expiresAtMs: entry.expiresAtMs }
        : { exists: false, value: null, expiresAtMs: null }
    },
    async restoreExact(key, value, expiresAtMs) {
      events.push(`restore:${key}`)
      state.set(key, { value, expiresAtMs })
    },
    async deleteExact(key) { events.push(`delete:${key}`); state.delete(key) },
    async releaseOwnershipWithReceipt({
      ownerKey: key,
      receiptKey,
      runToken: expectedToken,
      ownerId
    }) {
      events.push(`release:${key}`)
      assert.equal(receiptKey, `p0:e2e:cleanup:${ownerId}`)
      if (state.get(key)?.value === expectedToken) {
        if (cleanupReceipt !== null && cleanupReceipt !== ownerId) return 'MISMATCH'
        state.delete(key)
        cleanupReceipt = ownerId
        return 'RELEASED'
      }
      return !state.has(key) && cleanupReceipt === ownerId
        ? 'ALREADY_RELEASED'
        : 'MISMATCH'
    },
    async removeCleanupReceipt({ receiptKey, ownerId }) {
      events.push(`removeReceipt:${receiptKey}`)
      assert.equal(receiptKey, `p0:e2e:cleanup:${ownerId}`)
      if (cleanupReceipt === null) return 'ALREADY_ABSENT'
      if (cleanupReceipt !== ownerId) return 'MISMATCH'
      cleanupReceipt = null
      return 'REMOVED'
    }
  }
  const ownership = await smokeContracts.createStandaloneCanonicalRedisOwnership({
    redis,
    runToken,
    artifactRoot,
    recoveryPath,
    runId
  })
  assert.deepEqual(events.filter((event) => event.startsWith('read:')), keys.map((key) => `read:${key}`))
  state.set('quote:BTCUSDT', { value: 'mutated', expiresAtMs: null })
  state.set('quote:ETHUSDT', { value: 'created', expiresAtMs: null })

  assert.deepEqual(await ownership.cleanup(), { restored: 10, ownerReleased: true })
  assert.deepEqual(await ownership.cleanup(), { restored: 10, ownerReleased: true, alreadyCleaned: true })
  assert.deepEqual(state.get('quote:BTCUSDT'), {
    value: 'before-spot',
    expiresAtMs: 1_900_000_000_000
  })
  assert.equal(state.has('quote:ETHUSDT'), false)
  assert.equal(state.has(ownerKey), false)
  assert.equal(cleanupReceipt, null)
  assert.equal(events.filter((event) => event === `setNx:${ownerKey}`).length, 1)
  assert.equal(events.filter((event) => event === `release:${ownerKey}`).length, 1)
  assert.equal(events.filter((event) => event.startsWith('removeReceipt:')).length, 1)
  const completedText = readFileSync(recoveryPath, 'utf8')
  const completed = JSON.parse(completedText)
  assert.equal(completed.schemaVersion, 1)
  assert.equal(completed.state, 'COMPLETED')
  assert.equal(completed.runId, runId)
  assert.equal(completed.receipt.key, `p0:e2e:cleanup:${completed.ownerId}`)
  assert.equal(completed.receipt.state, 'COMPLETED')
  assert.equal(typeof completed.receipt.completedAt, 'string')
  assert.equal(completedText.includes(runToken), false)
  assert.equal(completedText.includes('before-spot'), false)
  assert.equal(completedText.includes('before-perp'), false)
})

test('canonical backend binds its admin login and provider-sync configuration', () => {
  assert.equal(typeof smokeContracts.buildCanonicalBackendEnvironment, 'function')
  const environment = smokeContracts.buildCanonicalBackendEnvironment({
    databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_platform_smoke_review1',
    inheritedEnv: {
      PATH: 'trusted-path',
      ADMIN_SMOKE_EMAIL: 'canonical-admin@example.invalid',
      ADMIN_SMOKE_PASSWORD: 'canonical-admin-password',
      ADMIN_BOOTSTRAP_ENABLED: 'false',
      ADMIN_BOOTSTRAP_EMAIL: 'poisoned-admin@example.invalid',
      ADMIN_BOOTSTRAP_PASSWORD: 'poisoned-admin-password',
      PROVIDER_INSTRUMENT_SYNC_ENABLED: 'true',
      MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED: 'true'
    }
  })
  assert.equal(environment.ADMIN_BOOTSTRAP_ENABLED, 'true')
  assert.equal(environment.ADMIN_BOOTSTRAP_EMAIL, 'canonical-admin@example.invalid')
  assert.equal(environment.ADMIN_BOOTSTRAP_PASSWORD, 'canonical-admin-password')
  assert.equal(environment.MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED, 'false')
  assert.equal(Object.hasOwn(environment, 'PROVIDER_INSTRUMENT_SYNC_ENABLED'), false)
  assert.equal(environment.PATH, 'trusted-path')
})

test('parent accepts canonical child only with PASS source evidence database cleanup and retained Redis ownership', async (t) => {
  assert.equal(typeof smokeContracts.verifyCanonicalChildResult, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-canonical-child-result-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runRoot = join(root, 'p0-review1-parent-a1')
  const canonicalRoot = join(runRoot, 'canonical')
  mkdirSync(canonicalRoot, { recursive: true })
  const runToken = 'review1-parent-canonical-owner-token-a1'
  const canonicalDatabase = 'fx_p0_user_e2e_canonical_1_abcdef123456'
  const context = {
    runRoot,
    options: { runId: 'p0-review1-parent-a1' },
    ownerToken: runToken,
    canonicalDatabase
  }
  const reportPath = join(canonicalRoot, 'report.json')
  const writeChildReport = (overrides = {}) => writeFileSync(reportPath, `${JSON.stringify({
    status: 'PASS',
    runId: 'p0-review1-parent-a1-canonical',
    smokeDatabase: canonicalDatabase,
    sourceEvidence: [{ mode: 'LOCAL_SIMULATED', spot: {}, perp: {} }],
    results: [{ name: 'canonical', status: 'PASS' }],
    error: null,
    ...overrides
  })}\n`)
  writeChildReport()
  let redisOwner = runToken
  let databaseOwnership = null
  const redis = { async get() { return redisOwner } }
  const postgres = { async readDatabaseOwnership() { return databaseOwnership } }
  const child = { status: 0, signal: null, stdout: '', stderr: '' }

  assert.deepEqual(await smokeContracts.verifyCanonicalChildResult({ child, context, redis, postgres }), {
    status: 'PASS',
    reportPath,
    sourceEvidence: 1
  })

  writeChildReport({ sourceEvidence: [] })
  await assert.rejects(
    smokeContracts.verifyCanonicalChildResult({ child, context, redis, postgres }),
    /P0_CANONICAL_SOURCE_EVIDENCE_MISSING/
  )
  writeChildReport()
  databaseOwnership = { segmentName: canonicalDatabase, ownerMarker: `p0-owner:${runToken}` }
  await assert.rejects(
    smokeContracts.verifyCanonicalChildResult({ child, context, redis, postgres }),
    /P0_CANONICAL_DATABASE_NOT_CLEAN/
  )
  databaseOwnership = null
  redisOwner = null
  await assert.rejects(
    smokeContracts.verifyCanonicalChildResult({ child, context, redis, postgres }),
    /P0_REDIS_OWNER_MISMATCH/
  )
})

test('Windows local command adapter executes trusted npm and Maven shims without shell injection', async (t) => {
  assert.equal(typeof smokeContracts.normalizeLocalCommandDescriptor, 'function')
  assert.equal(typeof smokeContracts.runLocalCommand, 'function')
  if (process.platform !== 'win32') {
    const poisonedPath = join(tmpdir(), 'p0-poisoned-launcher-path')
    const normalized = smokeContracts.normalizeLocalCommandDescriptor({
      command: process.execPath,
      args: ['--version'],
      cwd: process.cwd(),
      env: { ...process.env, PATH: poisonedPath },
      shell: true
    })
    assert.equal(normalized.command, process.execPath)
    assert.deepEqual(normalized.args, ['--version'])
    assert.equal(normalized.shell, false)
    assert.notEqual(normalized.env.PATH, poisonedPath)
    assert.equal(
      normalized.env.PATH.split(delimiter).includes(dirname(realpathSync(process.execPath))),
      true
    )
    return
  }

  const root = mkdtempSync(join(tmpdir(), 'p0-review1-trusted-launcher-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const marker = join(root, 'injected.txt')
  const poisonedEnvironment = {
    ...process.env,
    MAVEN_CMD: `mvn.cmd & type nul > "${marker}"`,
    NPM_CMD: `npm.cmd & type nul > "${marker}"`,
    ComSpec: marker,
    COMSPEC: marker
  }
  const toolchain = smokeContracts.resolveTrustedLocalToolchain()

  for (const shim of ['npm.cmd', 'mvn.cmd']) {
    const descriptor = {
      command: shim,
      args: ['--version'],
      cwd: process.cwd(),
      env: poisonedEnvironment,
      shell: true
    }
    const normalized = smokeContracts.normalizeLocalCommandDescriptor(descriptor)
    assert.equal(isAbsolute(normalized.command), true, shim)
    if (shim === 'npm.cmd') {
      assert.equal(normalized.command, toolchain.nodePath)
      assert.deepEqual(normalized.args, [toolchain.npmCli, '--version'])
    } else {
      assert.equal(normalized.command, toolchain.javaPath)
      assert.equal(normalized.args.includes(toolchain.plexusLauncher), true)
      assert.equal(normalized.args.includes('org.codehaus.plexus.classworlds.launcher.Launcher'), true)
      assert.equal(normalized.args.at(-1), '--version')
    }
    assert.equal(normalized.shell, false, shim)
    assert.equal(normalized.env.MAVEN_CMD, undefined, shim)
    assert.equal(normalized.env.NPM_CMD, undefined, shim)
    assert.equal(normalized.env.ComSpec, undefined, shim)
    assert.equal(normalized.env.COMSPEC, undefined, shim)

    const result = await smokeContracts.runLocalCommand(descriptor)
    assert.equal(result.status, 0, `${shim}: ${result.stderr}`)
    assert.equal(result.signal, null, shim)
    assert.notEqual(result.stdout.trim(), '', shim)
  }
  assert.equal(existsSync(marker), false)
  assert.throws(
    () => smokeContracts.normalizeLocalCommandDescriptor({
      command: 'npm.cmd',
      args: [`--version&type nul>${marker}`],
      cwd: process.cwd(),
      env: process.env
    }),
    /P0_LOCAL_COMMAND_ARGUMENT_UNSAFE/
  )
})

test('P0 report derives verdict from aggregate evidence and persists canonical mode and identity', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-aggregate-entry-report-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const identity = {
    branch: 'codex/review1-report',
    commit: 'a'.repeat(40),
    clean: true,
    dirtyDiffHash: `sha256:${'b'.repeat(64)}`,
    worktreeFingerprint: `sha256:${'c'.repeat(64)}`,
    schemaVersion: 1,
    registryFingerprint: P0_REGISTRY_FINGERPRINT,
    profileWorkerMap: {
      UI_CORE: ['false', 'false', 'false', 'false']
    }
  }
  const dependencies = {
    writeReport(execution, prepared, details = {}) {
      const { caseIds, phases, profiles, viewports } = prepared.runState.selection
      return writeFullyFakeP0Report(execution, {
        ...prepared,
        runState: {
          ...prepared.runState,
          selection: { caseIds, phases, profiles, viewports }
        }
      }, {
        ...details,
        now: () => '2026-07-15T04:05:06.789Z'
      })
    }
  }
  const writeScenario = async ({
    runId,
    mode,
    selection,
    definitions,
    executionEntries,
    caseResults,
    planVerdict
  }) => {
    const runRoot = join(root, runId)
    mkdirSync(runRoot, { recursive: true })
    const prepared = {
      runRoot,
      runState: {
        ...aggregateState({
          caseIds: selection.caseIds ?? [],
          phases: selection.phases ?? [],
          profiles: selection.profiles ?? [],
          viewports: selection.viewports ?? [],
          metadata: s10RequestedPhaseSelection('selected').metadata
        }),
        mode: mode.toUpperCase()
      },
      options: { runId, mode },
      ownerId: 'd'.repeat(64),
      identity,
      canonicalDatabase: `fx_p0_user_e2e_canonical_1_${runId.slice(-12)}`,
      matrixDatabase: `fx_p0_user_e2e_preflight_1_${runId.slice(-12)}`,
      async assertIdentity() {}
    }
    const report = await dependencies.writeReport({
      plan: {
        verdict: planVerdict,
        phases: ['selected', 'report'],
        definitions,
        executionEntries
      },
      phaseResult: { matrixPhases: 1 },
      caseResults
    }, prepared)
    const persisted = JSON.parse(readFileSync(join(runRoot, 'report.json'), 'utf8'))
    assert.deepEqual(persisted, report)
    return report
  }

  const full = await writeScenario({
    runId: 'p0-report-full-a1',
    mode: 'discovery',
    selection: {},
    definitions: P0_CASES,
    caseResults: P0_CASES.map(passingCase),
    planVerdict: 'FAIL'
  })
  assert.equal(full.status, 'PASS')
  assert.equal(full.verdict, 'PASS')
  assert.equal(full.scopeComplete, true)
  assert.equal(full.counts.PASS, 60)
  assert.equal(full.mode, 'DISCOVERY')
  assert.deepEqual(full.identity, identity)

  const failedDefinitions = P0_CASES.slice(0, 2)
  const failed = await writeScenario({
    runId: 'p0-report-fail-a1',
    mode: 'certification',
    selection: { caseIds: failedDefinitions.map(({ id }) => id) },
    definitions: failedDefinitions,
    caseResults: [
      { id: failedDefinitions[0].id, status: 'BLOCKED' },
      { id: failedDefinitions[1].id, status: 'FAIL' }
    ],
    planVerdict: 'PASS'
  })
  assert.equal(failed.status, 'FAIL')
  assert.equal(failed.verdict, 'FAIL')
  assert.equal(failed.counts.BLOCKED, 1)
  assert.equal(failed.counts.FAIL, 1)
  assert.equal(failed.mode, 'CERTIFICATION')

  const croppedDefinition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const selectedSubruns = croppedDefinition.requiredSubruns.filter(
    ({ profile }) => profile === 'FUNDING_ONLY'
  )
  const cropped = await writeScenario({
    runId: 'p0-report-crop-a1',
    mode: 'discovery',
    selection: { caseIds: [croppedDefinition.id], profiles: ['FUNDING_ONLY'] },
    definitions: [croppedDefinition],
    executionEntries: [{
      id: croppedDefinition.id,
      definition: croppedDefinition,
      selectedSubruns,
      cropped: true
    }],
    caseResults: [{
      id: croppedDefinition.id,
      status: 'PASS',
      scopeComplete: false,
      subruns: selectedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }],
    planVerdict: 'PASS'
  })
  assert.equal(cropped.status, 'PARTIAL_PASS')
  assert.equal(cropped.verdict, 'PARTIAL_PASS')
  assert.equal(cropped.scopeComplete, false)
})

test('SIGINT aborts active gate backend and canonical child before exactly-once cleanup', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-signal-propagation-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const ownerToken = 'review1-signal-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const signalHandlers = new Map()
  const installSignalHandlers = (scenario, events) => (handler) => {
    signalHandlers.set(scenario, handler)
    events.push('signals:install')
    return () => events.push('signals:remove')
  }
  const prepared = (options) => ({
    scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
    runRoot: join(root, options.runId),
    ownerToken,
    ownerId,
    canonicalDatabase: 'fx_p0_user_e2e_canonical_1_abcdef123456',
    matrixDatabase: 'fx_p0_user_e2e_preflight_1_abcdef123456',
    databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_preflight_1_abcdef123456',
    inheritedEnv: {},
    caseResults: []
  })

  const gateEvents = []
  let gateCleanupCalls = 0
  let gateBackendActive = false
  const gateOptions = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=AUTH-01',
    '--run-id=p0-review1-signal-gate-a1'
  ])
  await assert.rejects(
    smokeContracts.runP0Suite(gateOptions, {
      installSignalHandlers: installSignalHandlers('gate', gateEvents),
      async initializeOwnership(options) { return prepared(options) },
      phaseOperations: {
        runPreflight(_context, _plan, { signal }) {
          return smokeContracts.runP0Preflight({
            projectRoot: join(root, 'gate-project'),
            gateOutput: join(root, 'gate-surefire.json'),
            databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_preflight_1_abcdef123456',
            now: () => '2026-07-15T05:06:07.890Z',
            signal,
            operations: {
              async runCommand(descriptor) {
                assert.equal(descriptor.signal, signal)
                gateEvents.push(`command:${descriptor.id}`)
                return { status: 0, signal: null, stdout: '', stderr: '' }
              },
              async recordGate() {},
              async startOwnedBackend(input) {
                assert.equal(input.signal, signal)
                gateBackendActive = true
                gateEvents.push('backend:start')
                return { pid: 7001 }
              },
              async waitForBackendHealth(_backend, _url, receivedSignal) {
                assert.equal(receivedSignal, signal)
                gateEvents.push('backend:health')
                signalHandlers.get('gate')('SIGINT')
                assert.equal(signal.aborted, true)
              },
              async waitForBusinessEndpoint() {
                throw new Error('BUSINESS_WAIT_MUST_NOT_RUN_AFTER_ABORT')
              },
              async stopOwnedBackend() {
                gateBackendActive = false
                gateEvents.push('backend:stop')
              },
              async assertBusinessPortsFree() { gateEvents.push('ports:free') }
            }
          })
        },
        async runAuthority() {},
        async writeReport() {}
      },
      async dispatchCase() { throw new Error('CASE_MUST_NOT_RUN_AFTER_ABORT') },
      async writeReport() { throw new Error('REPORT_MUST_NOT_RUN_AFTER_ABORT') },
      async cleanup() {
        gateCleanupCalls += 1
        assert.equal(gateBackendActive, false)
        gateEvents.push('cleanup')
        return { status: 'CLEANED' }
      }
    }),
    /P0_INTERRUPTED: SIGINT/
  )
  assert.equal(gateCleanupCalls, 1)
  assert.ok(gateEvents.indexOf('backend:stop') < gateEvents.indexOf('cleanup'))

  const canonicalEvents = []
  let canonicalCleanupCalls = 0
  let canonicalChildActive = false
  const canonicalOptions = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-signal-canonical-a1'
  ])
  await assert.rejects(
    smokeContracts.runP0Suite(canonicalOptions, {
      installSignalHandlers: installSignalHandlers('canonical', canonicalEvents),
      async initializeOwnership(options) { return prepared(options) },
      phaseOperations: {
        async runPreflight() { return { id: 'AUTH-01', status: 'PASS' } },
        async assertRedisOwnership() {},
        async stopParentBackend() {},
        async assertBusinessPortsFree() {},
        async runCanonicalChild(_invocation, _context, { signal }) {
          canonicalChildActive = true
          canonicalEvents.push('canonical:start')
          signal.addEventListener('abort', () => {
            canonicalChildActive = false
            canonicalEvents.push('canonical:abort')
          }, { once: true })
          signalHandlers.get('canonical')('SIGINT')
          assert.equal(signal.aborted, true)
          return { status: null, signal: 'SIGINT', stdout: '', stderr: '' }
        },
        async verifyCanonicalChildCleanup() {
          throw new Error('CANONICAL_VERIFY_MUST_NOT_RUN_AFTER_ABORT')
        },
        async runAuthority() { throw new Error('AUTHORITY_MUST_NOT_RUN_AFTER_ABORT') },
        async writeReport() {}
      },
      async dispatchCase() { throw new Error('CASE_MUST_NOT_RUN_AFTER_ABORT') },
      async writeReport() { throw new Error('REPORT_MUST_NOT_RUN_AFTER_ABORT') },
      async cleanup() {
        canonicalCleanupCalls += 1
        assert.equal(canonicalChildActive, false)
        canonicalEvents.push('cleanup')
        return { status: 'CLEANED' }
      }
    }),
    /P0_INTERRUPTED: SIGINT/
  )
  assert.equal(canonicalCleanupCalls, 1)
  assert.ok(canonicalEvents.indexOf('canonical:abort') < canonicalEvents.indexOf('cleanup'))
  assert.equal(canonicalEvents.filter((event) => event === 'cleanup').length, 1)
})

test('AbortSignal propagates through initializeOwnership runCase and local fetch', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s5-signal-propagation-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-s5-signal-propagation-a1',
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const controller = new AbortController()
  const observed = {
    captureIdentity: [],
    databaseExists: [],
    safety: [],
    commands: []
  }
  const suffixes = ['abcdef123456', 'abcdef123457']
  const infrastructure = {
    async inspectDockerDaemon(details = {}) {
      observed.safety.push(details.signal)
      return { endpoint: 'npipe:////./pipe/docker_engine' }
    },
    async verifyComposePort(_expected, details = {}) {
      observed.safety.push(details.signal)
      return true
    },
    async inspectListener(_expected, details = {}) {
      observed.safety.push(details.signal)
      return null
    }
  }
  const reservation = await smokeContracts.prepareP0RunReservation({
    options,
    plan,
    artifactBase,
    inheritedEnv: {},
    nextRunToken: () => 'review1-s5-signal-owner-token-a1',
    nextRandomSuffix: () => suffixes.shift(),
    captureIdentity: async (details = {}) => {
      observed.captureIdentity.push(details.signal)
      return review1P0Identity()
    },
    databaseExists: async (_segmentName, details = {}) => {
      observed.databaseExists.push(details.signal)
      return false
    },
    infrastructure,
    signal: controller.signal
  })

  await assert.rejects(
    smokeContracts.runP0JournaledCommand({
      artifactBase,
      runId: options.runId,
      runToken: reservation.ownerToken,
      resourceId: 's5-signal-probe',
      commandFingerprint: `sha256:${'5'.repeat(64)}`,
      descriptor: {
        id: 's5-signal-probe',
        command: process.execPath,
        args: ['--version'],
        cwd: root
      },
      runCommand: async (descriptor) => {
      observed.commands.push(descriptor.signal)
      throw new Error('S5_SIGNAL_PROBE_COMPLETE')
      },
      inspectProcess: async () => null,
      signal: controller.signal
    }),
    /S5_SIGNAL_PROBE_COMPLETE/
  )
  for (const signals of Object.values(observed)) {
    assert.ok(signals.length > 0)
    assert.equal(signals.every((signal) => signal === controller.signal), true)
  }

  const definition = P0_CASES.find(({ id }) => id === 'SPOT-01')
  const context = { marker: 's5-run-case-context' }
  const executionDetails = { signal: controller.signal }
  let handlerArguments
  const handlerResult = { id: definition.id, status: 'PASS' }
  const result = await runCase(definition, context, {
    [definition.handlerId](...args) {
      handlerArguments = args
      return handlerResult
    }
  }, executionDetails)
  assert.equal(result, handlerResult)
  assert.equal(handlerArguments[0], context)
  assert.equal(handlerArguments[1], definition)
  assert.equal(handlerArguments[2], executionDetails)

  assert.equal(typeof smokeContracts.fetchP0Local, 'function')
  const fetchController = new AbortController()
  const abortReason = new Error('S5_LOCAL_FETCH_ABORT')
  let localFetchSignal
  const fetchPromise = smokeContracts.fetchP0Local(
    'http://127.0.0.1:18086/actuator/health',
    {
      signal: fetchController.signal,
      timeoutMs: 1000,
      fetchImpl: async (_url, { signal }) => {
        localFetchSignal = signal
        return new Promise((_resolvePromise, rejectPromise) => {
          const rejectForAbort = () => rejectPromise(signal.reason)
          signal.addEventListener('abort', rejectForAbort, { once: true })
          if (signal.aborted) rejectForAbort()
        })
      }
    }
  )
  fetchController.abort(abortReason)
  await assert.rejects(fetchPromise, (error) => error === abortReason)
  assert.equal(localFetchSignal.aborted, true)
})

test('abort prevents later mutation and still performs cleanup exactly once', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s5-abort-boundary-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s5-abort-boundary-a1'
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${runId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const events = []
  let signalHandler
  let executionSignal
  let lateMutations = 0
  let cleanupCalls = 0
  let cleanupSignal
  const dependencies = {
    installSignalHandlers(handler) {
      signalHandler = handler
      events.push('signals:install')
      return () => events.push('signals:remove')
    },
    async initializeOwnership(_receivedOptions, _plan, details) {
      executionSignal = details.signal
      events.push('prepare')
      signalHandler('SIGINT')
      return { artifactBase, runId, caseResults: [] }
    },
    async phaseOperations() {
      lateMutations += 1
      events.push('execute:mutation')
      return {}
    },
    async writeReport() {
      lateMutations += 1
      events.push('report:mutation')
    },
    async cleanup(_prepared, details) {
      cleanupCalls += 1
      cleanupSignal = details.signal
      events.push('cleanup')
      return { status: 'CLEANED' }
    }
  }

  await assert.rejects(
    smokeContracts.runP0Suite(options, dependencies),
    /P0_INTERRUPTED: SIGINT/
  )

  assert.equal(lateMutations, 0)
  assert.equal(existsSync(join(artifactBase, runId)), false)
  assert.equal(cleanupCalls, 1)
  assert.ok(cleanupSignal)
  assert.equal(cleanupSignal.aborted, false)
  assert.notEqual(cleanupSignal, executionSignal)
  assert.deepEqual(events.slice(-2), ['cleanup', 'signals:remove'])
})

test('canonical rejects non-default Docker endpoints before compose mutation', async () => {
  assert.equal(typeof smokeContracts.startCanonicalDockerInfrastructure, 'function')
  const composeIdentity = {
    project: 'infra',
    postgres: {
      id: 'a'.repeat(64),
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64),
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379
    },
    credentials: { username: 'postgres', password: 'password' }
  }
  const runRejected = async ({ inheritedEnv = {}, endpoint, expectedError }) => {
    const events = []
    await assert.rejects(
      smokeContracts.startCanonicalDockerInfrastructure({
        ownership: { inherited: false },
        inheritedEnv,
        composeFiles: ['C:\\trusted\\docker-compose.yml', 'C:\\trusted\\compose.loopback.yml'],
        operations: {
          async inspectDockerDaemon() {
            events.push('daemon:inspect')
            return { endpoint }
          },
          async startCompose() { events.push('compose:mutation') },
          async verifyComposeContainers() {
            events.push('compose:verify')
            return composeIdentity
          },
          async waitForPostgres() { events.push('postgres:ready') }
        }
      }),
      expectedError
    )
    assert.equal(events.includes('compose:mutation'), false)
    return events
  }

  assert.deepEqual(await runRejected({
    inheritedEnv: { DOCKER_HOST: 'tcp://127.0.0.1:2375' },
    endpoint: 'npipe:////./pipe/docker_engine',
    expectedError: /P0_DOCKER_TARGET_INHERITED/
  }), [])
  for (const endpoint of [
    'npipe:////./pipe/docker_engine-foreign',
    'unix:///tmp/docker.sock'
  ]) {
    assert.deepEqual(await runRejected({
      endpoint,
      expectedError: /P0_DOCKER_DAEMON_NOT_LOCAL/
    }), ['daemon:inspect'])
  }

  const successEvents = []
  const result = await smokeContracts.startCanonicalDockerInfrastructure({
    ownership: { inherited: false },
    inheritedEnv: {},
    composeFiles: ['C:\\trusted\\docker-compose.yml', 'C:\\trusted\\compose.loopback.yml'],
    operations: {
      async inspectDockerDaemon() {
        successEvents.push('daemon:inspect')
        return { endpoint: 'npipe:////./pipe/docker_engine' }
      },
      async startCompose() { successEvents.push('compose:mutation') },
      async verifyComposeContainers() {
        successEvents.push('compose:verify')
        return composeIdentity
      },
      async waitForPostgres(identity) {
        assert.equal(identity, composeIdentity)
        successEvents.push('postgres:ready')
      }
    }
  })
  assert.deepEqual(successEvents, [
    'daemon:inspect',
    'compose:mutation',
    'compose:verify',
    'postgres:ready'
  ])
  assert.equal(result.composeIdentity, composeIdentity)
})

test('canonical PostgreSQL operations bind only verified compose container ids', async () => {
  const runToken = 'review1-s6-canonical-postgres-owner-token-a1'
  const segmentName = 'fx_p0_user_e2e_canonical_1_abcdef123456'
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  const originalId = 'c'.repeat(64)
  const replacementId = 'd'.repeat(64)
  let currentId = originalId
  const commands = []
  const adapter = smokeContracts.createDockerPostgresAdapter(
    async (descriptor) => {
      commands.push(descriptor)
      let stdout = ''
      if (descriptor.id === 'postgres-owner-read') {
        stdout = `${segmentName}\tp0-owner:${runToken}`
      } else if (descriptor.id === 'postgres-database-probe') {
        stdout = `${segmentName}\tp0-owner:${runToken}\tUTC`
      }
      return { status: 0, signal: null, stdout, stderr: '' }
    },
    () => currentId
  )

  await smokeContracts.createOwnedDatabase({ segmentName, runToken, postgres: adapter })
  await smokeContracts.alterOwnedDatabaseTimezone({ segmentName, runToken, postgres: adapter })
  await smokeContracts.verifyDatabaseIdentity({
    segmentName,
    runToken,
    databaseUrl,
    postgres: adapter
  })
  await smokeContracts.dropOwnedDatabase({ segmentName, runToken, postgres: adapter })
  assert.ok(commands.length > 0)
  assert.equal(commands.every(({ args }) => (
    args[0] === 'exec' && args[1] === '-i' && args[2] === originalId
  )), true)
  assert.equal(JSON.stringify(commands).includes('fx-platform-postgres'), false)

  const commandCount = commands.length
  currentId = replacementId
  await assert.rejects(
    adapter.executeAdminSql('SELECT 1'),
    /P0_POSTGRES_CONTAINER_ID_CHANGED/
  )
  assert.equal(commands.length, commandCount)

  let unverifiedCommands = 0
  const unverified = smokeContracts.createDockerPostgresAdapter(async () => {
    unverifiedCommands += 1
    return { status: 0, signal: null, stdout: '', stderr: '' }
  })
  await assert.rejects(
    unverified.executeAdminSql('SELECT 1'),
    /P0_VERIFIED_POSTGRES_CONTAINER_REQUIRED/
  )
  assert.equal(unverifiedCommands, 0)
})

test('default managed backend registers native identity before readiness', async () => {
  assert.equal(typeof smokeContracts.startP0ManagedProcess, 'function')
  assert.equal(typeof smokeContracts.acquireLocalNativeProcessHandle, 'function')
  const child = new EventEmitter()
  child.pid = 8123
  child.stdout = new EventEmitter()
  child.stderr = new EventEmitter()
  child.exitCode = null
  child.signalCode = null
  const descriptor = {
    command: 'C:\\trusted\\java.exe',
    args: ['-version'],
    cwd: 'C:\\trusted',
    env: {},
    shell: false
  }
  const started = smokeContracts.startP0ManagedProcess(
    's7-default-backend',
    descriptor.command,
    descriptor.args,
    descriptor.cwd,
    descriptor.env,
    undefined,
    {
      normalizeDescriptor: () => descriptor,
      spawnProcess: () => child
    }
  )
  assert.equal(started, child)
  child.emit('spawn')
  await child.p0SpawnReady
  assert.deepEqual(started.processIdentity, {
    pid: child.pid,
    startedAt: started.processIdentity.startedAt,
    processFingerprint: started.processIdentity.processFingerprint
  })
  assert.match(started.processIdentity.startedAt, /^spawn:/)
  assert.match(started.processIdentity.processFingerprint, /^sha256:[a-f0-9]{64}$/)
  const handle = smokeContracts.acquireLocalNativeProcessHandle(child.pid)
  assert.ok(handle)
  assert.deepEqual(await handle.inspectIdentity(), started.processIdentity)
  child.exitCode = 0
  await handle.close()
})

test('default cleanup preserves its independent bounded signal', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s7-cleanup-signal-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s7-cleanup-signal-a1'
  const runToken = 'review1-s7-cleanup-signal-owner-token-a1'
  const database = 'fx_p0_user_e2e_cleanup_signal_1_abcdef123456'
  const redisKey = 'quote:BTCUSDT'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  markP0RedisSnapshotReady(control, [{
    key: redisKey,
    exists: false,
    value: null,
    expiresAtMs: null
  }], [redisKey])
  await smokeContracts.runP0JournaledMutation({
    artifactBase,
    runId,
    runToken,
    resource: { type: 'database', id: database },
    start: async () => ({ segmentName: database })
  })
  const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)
  const cleanupSignal = AbortSignal.timeout(30000)
  const observed = {
    compose: [],
    process: [],
    ports: [],
    postgres: [],
    redis: []
  }
  let redisOwner = runToken
  let redisReceipt = null
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      'default-cleanup-bounded-signal'
    ),
    processManager: {
      async stopParentBackend(details = {}) { observed.process.push(details.signal) }
    },
    infrastructure: createStrictCleanupComposeInfrastructure(cleanupProof, {
      expectedSignal: cleanupSignal,
      record(_label, observedSignal) { observed.compose.push(observedSignal) },
      async assertPortsFree(_ports, details = {}) { observed.ports.push(details.signal) }
    }),
    postgres: {
      async readDatabaseOwnership(segmentName, details = {}) {
        observed.postgres.push(details.signal)
        return { segmentName, ownerMarker: `p0-owner:${runToken}` }
      },
      async executeAdminSql(_sql, details = {}) {
        observed.postgres.push(details.signal)
      }
    },
    redis: {
      async get(_key, details = {}) {
        observed.redis.push(details.signal)
        return redisOwner
      },
      async deleteExact(_key, details = {}) {
        observed.redis.push(details.signal)
      },
      async releaseOwnershipWithReceipt(input) {
        observed.redis.push(input.signal)
        redisOwner = null
        redisReceipt = control.ownerId
        return 'RELEASED'
      },
      async removeCleanupReceipt(input) {
        observed.redis.push(input.signal)
        if (redisReceipt === input.ownerId) {
          redisReceipt = null
          return 'REMOVED'
        }
        return redisReceipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
      }
    }
  })
  const result = await dependencies.cleanup({
    artifactBase,
    runRoot: control.runRoot,
    ownerToken: runToken,
    ownerId: control.ownerId,
    redisSnapshot: [{
      key: redisKey,
      exists: false,
      value: null,
      expiresAtMs: null
    }],
    touchedRedisKeys: [redisKey]
  }, { signal: cleanupSignal }, { runId })
  assert.equal(result.status, 'CLEANED')
  for (const signals of Object.values(observed)) {
    assert.ok(signals.length > 0)
    assert.equal(signals.every((signal) => signal === cleanupSignal), true)
  }
})

test('resume rejects incomplete completed live process identity before mutation', () => {
  assert.equal(typeof smokeContracts.validateP0ResumeJournal, 'function')
  const matrixDatabase = 'fx_p0_user_e2e_resume_identity_1_abcdef123456'
  const manifest = {
    journal: {
      resources: [
        {
          type: 'override',
          id: 'compose-loopback',
          path: 'control/compose.loopback.yml',
          state: 'STARTED'
        },
        {
          type: 'process',
          id: 'compose-up',
          live: true,
          state: 'COMPLETED'
        },
        { type: 'database', id: matrixDatabase, state: 'STARTED' }
      ]
    }
  }
  assert.throws(
    () => smokeContracts.validateP0ResumeJournal(manifest, matrixDatabase),
    /P0_PROCESS_IDENTITY_MISSING/
  )
  Object.assign(manifest.journal.resources[1], {
    pid: 8124,
    processStartedAt: 'spawn:s7-compose-up',
    processFingerprint: `sha256:${'e'.repeat(64)}`
  })
  assert.deepEqual(
    smokeContracts.validateP0ResumeJournal(manifest, matrixDatabase),
    [matrixDatabase]
  )
})

test('compose identity fixes project name and exact config file set before mutation', async () => {
  const composeIdentity = {
    project: 'infra',
    postgres: {
      id: 'a'.repeat(64), image: 'postgres:16', host: '127.0.0.1', hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64), image: 'redis:7', host: '127.0.0.1', hostPort: 6379
    },
    credentials: { username: 'postgres', password: 'password' }
  }
  for (const inheritedEnv of [
    { COMPOSE_PROJECT_NAME: 'foreign' },
    { compose_file: 'foreign.yml' },
    { COMPOSE_PROFILES: 'foreign' }
  ]) {
    let mutations = 0
    await assert.rejects(
      smokeContracts.startCanonicalDockerInfrastructure({
        ownership: { inherited: false },
        inheritedEnv,
        composeFiles: ['C:\\trusted\\docker-compose.yml', 'C:\\trusted\\compose.loopback.yml'],
        operations: {
          async inspectDockerDaemon() {
            return { endpoint: 'npipe:////./pipe/docker_engine' }
          },
          async startCompose() { mutations += 1 },
          async verifyComposeContainers() { return composeIdentity },
          async waitForPostgres() {}
        }
      }),
      /P0_COMPOSE_TARGET_INHERITED/
    )
    assert.equal(mutations, 0)
  }

  const composeFile = join(dirname(artifactsScript), '..', 'infra', 'docker-compose.yml')
  const overrideFile = resolve(tmpdir(), 'p0-review1-s7-compose.loopback.yml')
  const extraFile = resolve(tmpdir(), 'p0-review1-s7-extra.yml')
  const postgresId = 'f'.repeat(64)
  const redisId = '1'.repeat(64)
  let actualProject = 'foreign'
  let actualConfigFiles = [composeFile, overrideFile]
  const inspection = (id, service, image, port) => JSON.stringify([{
    Id: id,
    State: { Running: true },
    Config: {
      Image: image,
      Env: service === 'postgres'
        ? ['POSTGRES_USER=postgres', 'POSTGRES_PASSWORD=password']
        : [],
      Labels: {
        'com.docker.compose.project': actualProject,
        'com.docker.compose.service': service,
        'com.docker.compose.project.working_dir': dirname(composeFile),
        'com.docker.compose.project.config_files': actualConfigFiles.join(',')
      }
    },
    NetworkSettings: {
      Ports: {
        [`${port}/tcp`]: [{ HostIp: '127.0.0.1', HostPort: String(port) }]
      }
    }
  }])
  const runCommand = async ({ args }) => {
    if (args[0] === 'compose') {
      return {
        status: 0,
        signal: null,
        stdout: args.at(-1) === 'postgres' ? postgresId : redisId,
        stderr: ''
      }
    }
    if (args[0] === 'inspect') {
      return {
        status: 0,
        signal: null,
        stdout: args[1] === postgresId
          ? inspection(postgresId, 'postgres', 'postgres:16', 5432)
          : inspection(redisId, 'redis', 'redis:7', 6379),
        stderr: ''
      }
    }
    throw new Error(`UNEXPECTED_DOCKER_COMMAND: ${args.join(' ')}`)
  }
  const infrastructure = smokeContracts.createLocalInfrastructureAdapter(composeFile, runCommand)
  await assert.rejects(
    infrastructure.verifyComposeContainers({
      composeFiles: [composeFile, overrideFile],
      expectedProject: 'infra'
    }),
    /P0_COMPOSE_PROJECT_MISMATCH/
  )
  actualProject = 'infra'
  actualConfigFiles = [composeFile, overrideFile, extraFile]
  await assert.rejects(
    infrastructure.verifyComposeContainers({
      composeFiles: [composeFile, overrideFile],
      expectedProject: 'infra'
    }),
    /P0_COMPOSE_CONFIG_MISMATCH/
  )
})

test('inherited canonical reuses parent infrastructure without a second compose startup', async () => {
  assert.equal(typeof smokeContracts.startCanonicalInfrastructureBoundary, 'function')
  const events = []
  const result = await smokeContracts.startCanonicalInfrastructureBoundary({
    ownership: { inherited: true },
    async startCompose() { events.push('compose') },
    async waitForPostgres() { events.push('postgres:ready') },
    async prepareInherited() { events.push('inherited:prepare') }
  })
  assert.deepEqual(result, { inherited: true, composeStarted: false })
  assert.deepEqual(events, ['postgres:ready', 'inherited:prepare'])
})

test('same P0 phase increments backend attempt database for every profile activation', async () => {
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-review1-phase-attempt-a1',
    '--phase=source',
    '--case=SOURCE-01,SOURCE-02,SOURCE-03,SOURCE-04'
  ])
  const attempts = []
  let preparedAttempt = null
  await smokeContracts.runP0Suite(options, {
    installSignalHandlers() { return () => {} },
    async initializeOwnership() {
      return {
        runRoot: resolve(tmpdir(), options.runId),
        options,
        ownerId: '2'.repeat(64),
        matrixDatabase: 'fx_p0_user_e2e_preflight_1_abcdef123456',
        databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_preflight_1_abcdef123456',
        inheritedEnv: {},
        caseResults: []
      }
    },
    phaseOperations: {
      async runPreflight() { return { id: 'AUTH-01', status: 'PASS' } },
      async runAuthority() { return { id: 'AUTH-03', status: 'PASS' } },
      async stopProfileBackend() {},
      async assertProfilePortFree() {},
      async prepareProfileDatabase({ phase, attempt }) {
        assert.equal(preparedAttempt, null)
        preparedAttempt = { phase, attempt }
        const segmentName = `fx_p0_user_e2e_${phase}_${attempt}_abcdef123456`
        return {
          segmentName,
          databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
        }
      },
      async startProfileBackend({ profile }) {
        assert.notEqual(preparedAttempt, null)
        attempts.push({ ...preparedAttempt, profile })
        preparedAttempt = null
        return { profile }
      },
      async waitForProfileHealth() {},
      async waitForProfileBusinessEndpoint() {},
      async verifyProfileDatabaseIdentity() {},
      async writeReport(context, plan) {
        return exactP0ReportPhaseEvidence(context, plan)
      }
    },
    async dispatchCase(definition) {
      return formalP0CaseFragment(
        definition,
        definition.requiredSubruns,
        { scopeComplete: true }
      )
    },
    async writeReport(execution) { return { verdict: execution.plan.verdict } },
    async cleanup() { return { status: 'CLEANED' } }
  })
  assert.equal(preparedAttempt, null)
  assert.deepEqual(attempts, [
    { phase: 'authority', attempt: 1, profile: 'UI_CORE' },
    { phase: 'source', attempt: 1, profile: 'UI_CORE' },
    { phase: 'source', attempt: 2, profile: 'ORDER_TRIGGER' },
    { phase: 'source', attempt: 3, profile: 'FUNDING_ONLY' },
    { phase: 'source', attempt: 4, profile: 'LIQUIDATION_ONLY' }
  ])
})

test('cleanup recovers every planned database from the active journal', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-journal-database-cleanup-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-journal-database-cleanup-a1'
  const runToken = 'review1-journal-database-cleanup-token-a1'
  const database = 'fx_p0_user_e2e_source_2_abcdef123456'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: ['SOURCE-03'],
    database: {}
  })
  markP0RedisSnapshotReady(control)
  const databases = new Map()
  await assert.rejects(
    smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource: { type: 'database', id: database },
      start: async () => {
        databases.set(database, { ownerMarker: `p0-owner:${runToken}` })
        throw new Error('INJECTED_AFTER_CREATE_BEFORE_STARTED')
      }
    }),
    /INJECTED_AFTER_CREATE_BEFORE_STARTED/
  )
  const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)
  let redisOwner = runToken
  let redisReceipt = null
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      'planned-database-cleanup'
    ),
    processManager: { async stopParentBackend() {} },
    infrastructure: createStrictCleanupComposeInfrastructure(cleanupProof),
    postgres: {
      async readDatabaseOwnership(segmentName) {
        const state = databases.get(segmentName)
        return state ? { segmentName, ownerMarker: state.ownerMarker } : null
      },
      async executeAdminSql(sql) {
        const match = sql.match(/^DROP DATABASE "([a-z0-9_]+)" WITH \(FORCE\)$/)
        if (!match) throw new Error(`UNEXPECTED_SQL: ${sql}`)
        databases.delete(match[1])
      }
    },
    redis: {
      async get() { return redisOwner },
      async releaseOwnershipWithReceipt({ runToken: value, ownerId }) {
        if (redisOwner === value) {
          redisOwner = null
          redisReceipt = ownerId
          return 'RELEASED'
        }
        return redisOwner === null && redisReceipt === ownerId
          ? 'ALREADY_RELEASED'
          : 'MISMATCH'
      },
      async removeCleanupReceipt({ ownerId }) {
        if (redisReceipt === ownerId) {
          redisReceipt = null
          return 'REMOVED'
        }
        return redisReceipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
      }
    }
  })
  const result = await dependencies.cleanup({
    artifactBase,
    runRoot: control.runRoot,
    ownerToken: runToken,
    ownerId: control.ownerId,
    ownedDatabaseSegments: [],
    redisSnapshot: [],
    touchedRedisKeys: []
  }, {}, { runId })
  assert.equal(result.status, 'CLEANED')
  assert.equal(databases.size, 0)
  assert.equal(redisOwner, null)
})

test('backend database verification requires a live JDBC activity in the owned segment', async () => {
  const runToken = 'review1-live-jdbc-activity-owner-token-a1'
  const segmentName = 'fx_p0_user_e2e_source_1_abcdef123456'
  const databaseUrl = `jdbc:postgresql://127.0.0.1:5432/${segmentName}`
  let activityCount = 1
  let activityProbes = 0
  const postgres = {
    async probeDatabase() {
      return {
        currentDatabase: segmentName,
        ownerMarker: `p0-owner:${runToken}`,
        timezone: 'UTC'
      }
    },
    async probeBackendConnection({ segmentName: received }) {
      assert.equal(received, segmentName)
      activityProbes += 1
      return activityCount
    }
  }
  await smokeContracts.verifyLiveBackendDatabaseIdentity({
    segmentName,
    runToken,
    databaseUrl,
    postgres
  })
  assert.equal(activityProbes, 1)
  activityCount = 0
  await assert.rejects(
    smokeContracts.verifyLiveBackendDatabaseIdentity({
      segmentName,
      runToken,
      databaseUrl,
      postgres
    }),
    /P0_BACKEND_DATABASE_ACTIVITY_MISSING/
  )
  assert.equal(activityProbes, 2)
})

test('hard-kill cleanup terminates only a journaled owned process with matching identity', async () => {
  assert.equal(typeof smokeContracts.terminateJournaledOwnedProcesses, 'function')
  const fingerprint = `sha256:${'a'.repeat(64)}`
  const startedAt = '2026-07-15T08:09:10.111Z'
  const inspected = []
  const terminated = []
  const identities = new Map([
    [4201, { pid: 4201, commandFingerprint: fingerprint, startedAt }],
    [4202, { pid: 4202, commandFingerprint: `sha256:${'b'.repeat(64)}`, startedAt: '2026-07-15T08:09:12.111Z' }],
    [4203, { pid: 4203 }]
  ])
  const runCleanup = (resource) => smokeContracts.terminateJournaledOwnedProcesses({
    resources: [resource],
    async acquireNativeProcessHandle(pid) {
      inspected.push(pid)
      return {
        async inspectIdentity() { return identities.get(pid) ?? null },
        async terminateTree(journaled) {
          terminated.push({ pid, id: journaled.id })
        }
      }
    }
  })
  const resource = {
    type: 'process',
    id: 'backend:source:1:UI_CORE',
    state: 'STARTED',
    pid: 4201,
    commandFingerprint: fingerprint,
    processStartedAt: startedAt
  }

  await runCleanup(resource)
  await assert.rejects(
    runCleanup({ ...resource, id: 'backend:source:2:ORDER_TRIGGER', pid: 4202 }),
    /P0_PROCESS_IDENTITY_MISMATCH/
  )
  await assert.rejects(
    runCleanup({ ...resource, id: 'backend:source:3:ORDER_TRIGGER', pid: 4203 }),
    /P0_PROCESS_IDENTITY_UNKNOWN/
  )

  assert.deepEqual(inspected, [4201, 4202, 4203])
  assert.deepEqual(terminated, [{ pid: 4201, id: 'backend:source:1:UI_CORE' }])
})

test('default cleanup recovers only exact journaled process identities and fails closed on reused or unknown PIDs', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-process-recovery-entry-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fingerprint = `sha256:${'c'.repeat(64)}`
  const processStartedAt = '2026-07-15T09:10:11.123Z'

  const createScenario = async (suffix, observedIdentity) => {
    const artifactBase = join(root, suffix, 'artifacts')
    const runId = `p0-review1-process-recovery-${suffix}-a1`
    const runToken = `review1-process-recovery-${suffix}-owner-token-a1`
    const pid = suffix === 'matching' ? 4301 : suffix === 'reused' ? 4302 : 4303
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: 'discovery',
      selection: [],
      database: {}
    })
    markP0RedisSnapshotReady(control)
    await smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource: {
        type: 'process',
        id: `backend:recovery:${suffix}`,
        commandFingerprint: fingerprint
      },
      start: async () => ({
        pid,
        processIdentity: {
          pid,
          startedAt: processStartedAt,
          processFingerprint: fingerprint
        }
      })
    })
    const cleanupProof = suffix === 'matching'
      ? await installStrictCleanupComposeProof(control, artifactBase)
      : null
    let currentIdentity = observedIdentity(pid)
    let redisOwner = runToken
    let redisReceipt = null
    const terminated = []
    const acquireNativeProcessHandle = async (receivedPid) => {
      assert.equal(receivedPid, pid)
      return {
        async inspectIdentity() { return currentIdentity },
        async terminateTree(resource) {
          terminated.push({ pid, id: resource.id })
          currentIdentity = null
          return {
            provider: 'WINDOWS_JOB_OBJECT_V1',
            pid,
            startedAt: resource.processStartedAt,
            processFingerprint: resource.processFingerprint,
            rootTerminated: true,
            treeTerminated: true
          }
        }
      }
    }
    const dependencies = smokeContracts.createDefaultP0Dependencies({
      artifactBase,
      inheritedEnv: {},
      processTreeProvider: {
        capability: 'WINDOWS_JOB_OBJECT_V1',
        platform: 'win32',
        verification: 'INDEPENDENTLY_VERIFIED',
        acquireNativeProcessHandle
      },
      processManager: {
        async stopParentBackend() {},
        acquireNativeProcessHandle
      },
      infrastructure: cleanupProof
        ? createStrictCleanupComposeInfrastructure(cleanupProof)
        : { async assertPortsFree() {} },
      postgres: { async readDatabaseOwnership() { return null } },
      redis: {
        async get() { return redisOwner },
        async releaseOwnershipWithReceipt({ runToken: value, ownerId }) {
          if (redisOwner === value) {
            redisOwner = null
            redisReceipt = ownerId
            return 'RELEASED'
          }
          return redisOwner === null && redisReceipt === ownerId
            ? 'ALREADY_RELEASED'
            : 'MISMATCH'
        },
        ...(suffix === 'matching'
          ? {
              async removeCleanupReceipt({ ownerId }) {
                if (redisReceipt === ownerId) {
                  redisReceipt = null
                  return 'REMOVED'
                }
                return redisReceipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
              }
            }
          : {
              async removeCleanupReceipt({ ownerId }) {
                if (redisReceipt === ownerId) redisReceipt = null
              }
            })
      }
    })
    return {
      control,
      runToken,
      terminated,
      redisOwner: () => redisOwner,
      cleanup: () => dependencies.cleanup({
        artifactBase,
        runRoot: control.runRoot,
        ownerToken: runToken,
        ownerId: control.ownerId,
        redisSnapshot: [],
        touchedRedisKeys: []
      }, {}, { runId })
    }
  }

  const matching = await createScenario('matching', (pid) => ({
    pid,
    startedAt: processStartedAt,
    processFingerprint: fingerprint
  }))
  assert.equal((await matching.cleanup()).status, 'CLEANED')
  assert.deepEqual(matching.terminated, [
    { pid: 4301, id: 'backend:recovery:matching' }
  ])
  assert.equal(matching.redisOwner(), null)

  for (const [suffix, observedIdentity, expectedError] of [
    ['reused', (pid) => ({
      pid,
      startedAt: '2026-07-15T09:10:12.123Z',
      processFingerprint: fingerprint
    }), /P0_PROCESS_IDENTITY_MISMATCH/],
    ['unknown', (pid) => ({ pid }), /P0_PROCESS_IDENTITY_UNKNOWN/]
  ]) {
    const scenario = await createScenario(suffix, observedIdentity)
    await assert.rejects(scenario.cleanup(), expectedError)
    assert.deepEqual(scenario.terminated, [])
    assert.equal(scenario.redisOwner(), scenario.runToken)
    assert.equal(existsSync(scenario.control.ownershipPath), true)
    assert.equal(existsSync(join(scenario.control.runRoot, 'control', 'cleaned.json')), false)
  }
})

test('canonical child journals spawn identity before waiting and hard-kill cleanup owns it', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-canonical-spawn-recovery-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fingerprint = `sha256:${'d'.repeat(64)}`
  const processStartedAt = '2026-07-15T10:11:12.345Z'

  const runScenario = async (suffix, reusePid) => {
    const artifactBase = join(root, suffix, 'artifacts')
    const runId = `p0-review1-canonical-spawn-${suffix}-a1`
    const runToken = `review1-canonical-spawn-${suffix}-owner-token-a1`
    const canonicalDatabase = `fx_p0_user_e2e_canonical_1_${suffix === 'matching' ? 'abcdef123456' : 'abcdef123457'}`
    const pid = suffix === 'matching' ? 4401 : 4402
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: 'discovery',
      selection: [],
      database: { canonical: canonicalDatabase }
    })
    markP0RedisSnapshotReady(control)
    let spawnOptions
    let rejectChildExit
    let signalSpawnObserved
    const spawnObserved = new Promise((resolvePromise) => { signalSpawnObserved = resolvePromise })
    const childExit = new Promise((_resolvePromise, rejectPromise) => { rejectChildExit = rejectPromise })
    const processManager = {
      async runCanonicalChild(_invocation, options = {}) {
        spawnOptions = options
        const activeBeforeSpawn = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
        const planned = activeBeforeSpawn.journal.resources.find(({ type }) => type === 'canonical-child')
        assert.equal(planned?.state, 'PLANNED')
        assert.equal(Object.hasOwn(planned, 'pid'), false)
        if (typeof options.onSpawn !== 'function') {
          signalSpawnObserved()
          throw new Error('P0_CANONICAL_ON_SPAWN_MISSING')
        }
        await options.onSpawn({
          pid,
          processIdentity: {
            pid,
            startedAt: processStartedAt,
            processFingerprint: fingerprint
          }
        })
        signalSpawnObserved()
        return childExit
      }
    }
    const runCanonicalChild = (invocation) => smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource: { type: 'database', id: canonicalDatabase },
      start: () => smokeContracts.runP0JournaledMutation({
        artifactBase,
        runId,
        runToken,
        resource: {
          type: 'canonical-child',
          id: 'canonical:attempt-1',
          live: true,
          database: canonicalDatabase,
          commandFingerprint: fingerprint
        },
        start: (markStarted) => processManager.runCanonicalChild(invocation, {
          onSpawn: markStarted
        })
      })
    })
    const context = {
      options: { runId },
      ownerToken: runToken,
      canonicalDatabase
    }
    const childOutcome = runCanonicalChild(
      { command: process.execPath, args: [], env: {} },
      context
    ).then(
      () => null,
      (error) => error
    )
    await spawnObserved
    assert.equal(typeof spawnOptions?.onSpawn, 'function')
    const activeWhileChildWaits = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
    const started = activeWhileChildWaits.journal.resources.find(({ type }) => type === 'canonical-child')
    assert.deepEqual({
      state: started?.state,
      pid: started?.pid,
      processStartedAt: started?.processStartedAt,
      processFingerprint: started?.processFingerprint
    }, {
      state: 'STARTED',
      pid,
      processStartedAt,
      processFingerprint: fingerprint
    })
    rejectChildExit(new Error('INJECTED_CONTROLLER_HARD_KILL'))
    assert.match((await childOutcome)?.message ?? '', /INJECTED_CONTROLLER_HARD_KILL/)

    let currentIdentity = {
      pid,
      startedAt: reusePid ? '2026-07-15T10:11:13.345Z' : processStartedAt,
      processFingerprint: fingerprint
    }
    let redisOwner = runToken
    const terminated = []
    const cleanup = async () => {
      const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
      await smokeContracts.terminateJournaledOwnedProcesses({
        resources: active.journal.resources,
        requireTreeProof: true,
        async acquireNativeProcessHandle() {
          return {
            async inspectIdentity() { return currentIdentity },
            async terminateTree(resource) {
              terminated.push({ pid, id: resource.id })
              currentIdentity = null
              return {
                provider: 'WINDOWS_JOB_OBJECT_V1',
                pid,
                startedAt: processStartedAt,
                processFingerprint: fingerprint,
                rootTerminated: true,
                treeTerminated: true
              }
            },
            async close() {}
          }
        }
      })
      redisOwner = null
      return { status: 'CLEANED' }
    }
    return { cleanup, control, redisOwner: () => redisOwner, runToken, terminated }
  }

  const matching = await runScenario('matching', false)
  assert.equal((await matching.cleanup()).status, 'CLEANED')
  assert.deepEqual(matching.terminated, [{ pid: 4401, id: 'canonical:attempt-1' }])
  const reused = await runScenario('reused', true)
  await assert.rejects(reused.cleanup(), /P0_PROCESS_IDENTITY_MISMATCH/)
  assert.deepEqual(reused.terminated, [])
  assert.equal(reused.redisOwner(), reused.runToken)
  assert.equal(existsSync(reused.control.ownershipPath), true)
})

test('journaled process fingerprints use canonical sha256 identity and reject missing live identity', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s1-process-fingerprint-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const fingerprint = `sha256:${'a'.repeat(64)}`

  const validRunId = 'p0-s1-process-fingerprint-valid-a1'
  const validToken = 'p0-s1-process-fingerprint-valid-owner-token-a1'
  const validControl = await smokeContracts.createControlManifest({
    artifactBase,
    runId: validRunId,
    runToken: validToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  let startedSnapshot
  await smokeContracts.runP0JournaledMutation({
    artifactBase,
    runId: validRunId,
    runToken: validToken,
    resource: {
      type: 'process',
      id: 'gate:backend-unit',
      live: true,
      commandFingerprint: fingerprint
    },
    start: async (markStarted) => {
      await markStarted({
        pid: 4501,
        processIdentity: {
          pid: 4501,
          startedAt: 'process-start-4501',
          processFingerprint: fingerprint
        }
      })
      startedSnapshot = JSON.parse(readFileSync(validControl.ownershipPath, 'utf8'))
        .journal.resources.find(({ id }) => id === 'gate:backend-unit')
      return { status: 0, signal: null, stdout: '', stderr: '' }
    }
  })
  assert.deepEqual({
    state: startedSnapshot?.state,
    pid: startedSnapshot?.pid,
    processStartedAt: startedSnapshot?.processStartedAt,
    processFingerprint: startedSnapshot?.processFingerprint
  }, {
    state: 'STARTED',
    pid: 4501,
    processStartedAt: 'process-start-4501',
    processFingerprint: fingerprint
  })
  const completed = JSON.parse(readFileSync(validControl.ownershipPath, 'utf8'))
    .journal.resources.find(({ id }) => id === 'gate:backend-unit')
  assert.equal(completed?.state, 'COMPLETED')

  const invalidRunId = 'p0-s1-process-fingerprint-missing-a1'
  const invalidToken = 'p0-s1-process-fingerprint-missing-owner-token-a1'
  const invalidControl = await smokeContracts.createControlManifest({
    artifactBase,
    runId: invalidRunId,
    runToken: invalidToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  let stopped = 0
  await assert.rejects(
    smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId: invalidRunId,
      runToken: invalidToken,
      resource: {
        type: 'process',
        id: 'gate:web-build',
        live: true,
        commandFingerprint: fingerprint
      },
      start: async () => ({ pid: 4502 }),
      async stopLiveProcess(result) {
        assert.equal(result.pid, 4502)
        stopped += 1
      }
    }),
    /P0_PROCESS_IDENTITY_MISSING/
  )
  assert.equal(stopped, 1)
  const invalid = JSON.parse(readFileSync(invalidControl.ownershipPath, 'utf8'))
    .journal.resources.find(({ id }) => id === 'gate:web-build')
  assert.equal(invalid?.state, 'PLANNED')
})

test('compose and preflight children persist spawn identity before work begins', async (t) => {
  assert.equal(typeof smokeContracts.runP0JournaledCommand, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-s1-journaled-command-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-s1-journaled-command-a1'
  const runToken = 'p0-s1-journaled-command-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  const fingerprint = `sha256:${'b'.repeat(64)}`
  const states = []
  let activeResourceId
  let nextPid = 4510
  const runCommand = async (descriptor) => {
    const planned = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
      .journal.resources.find(({ id }) => id === activeResourceId)
    states.push(`${activeResourceId}:${planned?.state}`)
    const pid = nextPid
    nextPid += 1
    await descriptor.onSpawn({
      pid,
      processIdentity: {
        pid,
        startedAt: `process-start-${pid}`,
        processFingerprint: fingerprint
      }
    })
    const started = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
      .journal.resources.find(({ id }) => id === activeResourceId)
    states.push(`${activeResourceId}:${started?.state}`)
    return { status: 0, signal: null, stdout: '', stderr: '' }
  }
  for (const [resourceId, commandId] of [
    ['compose-up', 'compose-up'],
    ['gate:backend-unit', 'backend-unit']
  ]) {
    activeResourceId = resourceId
    await smokeContracts.runP0JournaledCommand({
      artifactBase,
      runId,
      runToken,
      resourceId,
      commandFingerprint: fingerprint,
      descriptor: { id: commandId, command: process.execPath, args: [], cwd: root },
      runCommand
    })
  }
  assert.deepEqual(states, [
    'compose-up:PLANNED',
    'compose-up:STARTED',
    'gate:backend-unit:PLANNED',
    'gate:backend-unit:STARTED'
  ])
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.deepEqual(active.journal.resources.map(({ id, state }) => ({ id, state })), [
    { id: 'compose-up', state: 'COMPLETED' },
    { id: 'gate:backend-unit', state: 'COMPLETED' }
  ])
  const source = readFileSync(
    fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
    'utf8'
  )
  assert.ok((source.match(/runP0JournaledCommand\(\{/g) ?? []).length >= 2)
})

test('hard kill cleanup terminates journaled processes before requiring redis recovery state', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s1-process-first-recovery-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-s1-process-first-recovery-a1'
  const runToken = 'p0-s1-process-first-recovery-owner-token-a1'
  const fingerprint = `sha256:${'c'.repeat(64)}`
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  await assert.rejects(
    smokeContracts.runP0JournaledMutation({
      artifactBase,
      runId,
      runToken,
      resource: {
        type: 'process',
        id: 'compose-up',
        live: true,
        commandFingerprint: fingerprint
      },
      start: async (markStarted) => {
        await markStarted({
          pid: 4520,
          processIdentity: {
            pid: 4520,
            startedAt: 'process-start-4520',
            processFingerprint: fingerprint
          }
        })
        throw new Error('INJECTED_HARD_KILL')
      }
    }),
    /INJECTED_HARD_KILL/
  )
  const missingRecovery = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  missingRecovery.redisState = 'SNAPSHOT_READY'
  writeFileSync(control.ownershipPath, `${JSON.stringify(missingRecovery, null, 2)}\n`)
  let currentIdentity = {
    pid: 4520,
    startedAt: 'process-start-4520',
    processFingerprint: fingerprint
  }
  const events = []
  const processManager = {
    async stopParentBackend() { events.push('process:memory-stop') },
    async acquireNativeProcessHandle() {
      return {
        async inspectIdentity() { events.push('process:inspect'); return currentIdentity },
        async terminateTree(resource) {
          events.push('process:terminate')
          currentIdentity = null
          return {
            provider: 'WINDOWS_JOB_OBJECT_V1',
            pid: resource.pid,
            startedAt: resource.processStartedAt,
            processFingerprint: resource.processFingerprint,
            rootTerminated: true,
            treeTerminated: true
          }
        }
      }
    }
  }
  const processTreeProvider = {
    capability: 'WINDOWS_JOB_OBJECT_V1',
    platform: 'win32',
    verification: 'INDEPENDENTLY_VERIFIED',
    acquireNativeProcessHandle(...args) {
      return processManager.acquireNativeProcessHandle(...args)
    }
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processManager,
    processTreeProvider,
    infrastructure: { async assertPortsFree() { events.push('ports') } },
    postgres: { async readDatabaseOwnership() { events.push('database'); return null } },
    redis: { async get() { events.push('redis'); return runToken } }
  })
  await assert.rejects(
    dependencies.cleanup(undefined, {}, { runId }),
    /P0_REDIS_SNAPSHOT_MISSING/
  )
  assert.deepEqual(events, process.platform === 'win32'
    ? ['process:inspect', 'process:terminate', 'process:memory-stop']
    : ['process:memory-stop', 'process:inspect', 'process:terminate'])
  assert.equal(existsSync(control.ownershipPath), true)
})

test('cleanup replays Redis release receipt after a crash and reaches CLEANED exactly once', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s2-redis-release-replay-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const runId = 'p0-s2-redis-release-replay-a1'
  const runToken = 'p0-s2-redis-release-replay-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  const composeIdentity = {
    project: 'infra',
    postgres: {
      id: 'a'.repeat(64), image: 'postgres:16', host: '127.0.0.1', hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64), image: 'redis:7', host: '127.0.0.1', hostPort: 6379
    }
  }
  active.composeTarget = {
    expectedProject: composeTarget.expectedProject,
    composeFiles: [...composeTarget.composeFiles]
  }
  active.composeIdentity = composeIdentity
  active.redisState = 'SNAPSHOT_READY'
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  const snapshot = [{
    key: 'quote:BTCUSDT',
    exists: true,
    value: 'before',
    expiresAtMs: 1_900_000_000_000
  }]
  markP0RedisSnapshotReady(control, snapshot, ['quote:BTCUSDT'])

  const ownerKey = 'p0:e2e:owner'
  const receiptKey = `p0:e2e:cleanup:${control.ownerId}`
  let owner = runToken
  let receipt = null
  let restores = 0
  let releaseCalls = 0
  let failControlCommit = true
  const redis = {
    async get(key) {
      if (key === ownerKey) return owner
      if (key === receiptKey) return receipt
      return null
    },
    async restoreExact(key, value, expiresAtMs) {
      assert.deepEqual({ key, value, expiresAtMs }, {
        key: snapshot[0].key,
        value: snapshot[0].value,
        expiresAtMs: snapshot[0].expiresAtMs
      })
      restores += 1
    },
    async deleteExact() { throw new Error('UNEXPECTED_DELETE_EXACT') },
    async compareDelete(key, value) {
      assert.equal(key, ownerKey)
      assert.equal(value, runToken)
      if (owner !== runToken) return false
      owner = null
      receipt = control.ownerId
      return true
    },
    async releaseOwnershipWithReceipt(input) {
      releaseCalls += 1
      assert.deepEqual(input, {
        ownerKey,
        receiptKey,
        runToken,
        ownerId: control.ownerId,
        signal: undefined
      })
      if (owner === runToken) {
        owner = null
        receipt = control.ownerId
        return 'RELEASED'
      }
      if (owner === null && receipt === control.ownerId) return 'ALREADY_RELEASED'
      return 'MISMATCH'
    },
    async removeCleanupReceipt(input) {
      assert.deepEqual(input, { receiptKey, ownerId: control.ownerId, signal: undefined })
      if (receipt === control.ownerId) {
        receipt = null
        return 'REMOVED'
      }
      return receipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
    }
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      'cleanup-redis-release-replay'
    ),
    redis,
    processManager: { async stopParentBackend() {} },
    infrastructure: {
      async inspectDockerDaemon() {
        return { endpoint: 'npipe:////./pipe/docker_engine' }
      },
      async verifyComposeContainers({ composeFiles, expectedProject }) {
        assert.deepEqual(composeFiles, composeTarget.composeFiles)
        assert.equal(expectedProject, composeTarget.expectedProject)
        return composeIdentity
      },
      async assertPortsFree() {}
    },
    postgres: { async readDatabaseOwnership() { return null } },
    now() {
      if (failControlCommit) {
        failControlCommit = false
        throw new Error('INJECTED_CONTROL_COMMIT_CRASH')
      }
      return '2026-07-15T12:00:00.000Z'
    }
  })
  const context = {
    artifactBase,
    runRoot: control.runRoot,
    ownerToken: runToken,
    ownerId: control.ownerId,
    redisSnapshot: snapshot,
    touchedRedisKeys: ['quote:BTCUSDT']
  }

  await assert.rejects(
    dependencies.cleanup(context, {}, { runId }),
    /INJECTED_CONTROL_COMMIT_CRASH/
  )
  const releaseArmed = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.equal(releaseArmed.status, 'ACTIVE')
  assert.equal(releaseArmed.redisState, 'REDIS_RELEASE_ARMED')
  assert.equal(owner, null)
  assert.equal(receipt, control.ownerId)
  assert.equal(restores, 1)

  const recovered = await dependencies.cleanup(undefined, {}, { runId })
  assert.equal(recovered.status, 'CLEANED')
  const cleanedText = readFileSync(control.ownershipPath, 'utf8')
  assert.equal(cleanedText.includes(runToken), false)
  assert.equal(JSON.parse(cleanedText).status, 'CLEANED')
  assert.equal(existsSync(join(control.runRoot, 'control', 'cleaned.json')), false)
  assert.equal(receipt, null)
  assert.equal(restores, 1)
  assert.equal(releaseCalls, 2)

  assert.deepEqual(await dependencies.cleanup(undefined, {}, { runId }), {
    status: 'CLEANED',
    alreadyCleaned: true
  })
  assert.equal(restores, 1)
  assert.equal(releaseCalls, 2)
})

test('CLEANED atomically replaces ACTIVE without a raw ownership deletion window', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s2-control-replace-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-s2-control-replace-a1'
  const runToken = 'p0-s2-control-replace-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.redisState = 'REDIS_RELEASE_ARMED'
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)

  let observedActiveDuringReplace = false
  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId,
      runToken,
      resourcesCleaned: true,
      redisReleaseReceipt: control.ownerId,
      async replaceOwnership(path, cleaned) {
        assert.equal(path, control.ownershipPath)
        assert.equal(cleaned.status, 'CLEANED')
        observedActiveDuringReplace = JSON.parse(readFileSync(path, 'utf8')).status === 'ACTIVE'
        throw new Error('INJECTED_ATOMIC_REPLACE_FAILURE')
      }
    }),
    /INJECTED_ATOMIC_REPLACE_FAILURE/
  )
  assert.equal(observedActiveDuringReplace, true)
  assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).status, 'ACTIVE')
  assert.equal(existsSync(join(control.runRoot, 'control', 'cleaned.json')), false)

  assert.deepEqual(await smokeContracts.completeControlCleanup({
    artifactBase,
    runId,
    runToken,
    resourcesCleaned: true,
    redisReleaseReceipt: control.ownerId,
    now: () => '2026-07-15T12:01:00.000Z'
  }), { status: 'CLEANED', alreadyCleaned: false })
  const cleanedText = readFileSync(control.ownershipPath, 'utf8')
  assert.equal(cleanedText.includes(runToken), false)
  assert.deepEqual(JSON.parse(cleanedText), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: control.ownerId,
    cleanedAt: '2026-07-15T12:01:00.000Z',
    composeTarget: cleanupProof.composeTarget,
    composeIdentity: cleanupProof.composeIdentity,
    receipt: {
      key: `p0:e2e:cleanup:${control.ownerId}`,
      state: 'PENDING',
      completedAt: null
    }
  })
  assert.equal(existsSync(join(control.runRoot, 'control', 'cleaned.json')), false)
})

test('cleanup is a no-op only after prepare fails before the run root exists', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-pre-control-cleanup-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-pre-control-cleanup-a1'
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    redis: {},
    processManager: {},
    infrastructure: {},
    postgres: {}
  })
  const options = { suite: 'p0', mode: 'discovery', phase: 'all', runId }
  const preparationFailure = new Error('P0_PREPARE_SENTINEL')

  await assert.rejects(
    smokeContracts.executeP0SuiteLifecycle({
      options,
      operations: {
        installSignalHandlers() { return async () => {} },
        async prepare() { throw preparationFailure },
        async execute() { assert.fail('execute must not run') },
        async writeReport() { assert.fail('report must not run') },
        cleanup(prepared, details) {
          return dependencies.cleanup(prepared, details, options)
        }
      }
    }),
    (error) => error === preparationFailure && !(error instanceof AggregateError)
  )

  assert.deepEqual(await dependencies.cleanup(undefined, {
    error: preparationFailure
  }, options), {
    status: 'NOT_STARTED'
  })
  assert.equal(existsSync(join(artifactBase, runId)), false)
  await assert.rejects(
    dependencies.cleanup(undefined, {}, options),
    /P0_CONTROL_MISSING/
  )

  const partialRunId = 'p0-pre-control-partial-a1'
  mkdirSync(join(artifactBase, partialRunId), { recursive: true })
  await assert.rejects(
    dependencies.cleanup(undefined, {
      error: preparationFailure
    }, { ...options, runId: partialRunId }),
    /P0_CONTROL_MISSING/
  )
})

test('cleanup rejects missing forged or unsafe authoritative control state', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s2-authoritative-control-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')

  const missingRunId = 'p0-s2-missing-control-a1'
  const missingToken = 'p0-s2-missing-control-owner-token-a1'
  const missingControl = join(artifactBase, missingRunId, 'control')
  mkdirSync(missingControl, { recursive: true })
  writeFileSync(join(missingControl, 'cleaned.json'), `${JSON.stringify({
    schemaVersion: 1,
    status: 'CLEANED',
    runId: missingRunId,
    ownerId: createHash('sha256').update(missingToken).digest('hex'),
    cleanedAt: '2026-07-15T12:02:00.000Z'
  })}\n`)
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    redis: {},
    processManager: {},
    infrastructure: {},
    postgres: {}
  })
  await assert.rejects(
    dependencies.cleanup(undefined, {}, { runId: missingRunId }),
    /P0_CONTROL_MISSING/
  )

  const forgedRunId = 'p0-s2-forged-control-a1'
  const forgedToken = 'p0-s2-forged-control-owner-token-a1'
  const forgedControl = join(artifactBase, forgedRunId, 'control')
  mkdirSync(forgedControl, { recursive: true })
  writeFileSync(join(forgedControl, 'ownership.json'), `${JSON.stringify({
    schemaVersion: 1,
    status: 'CLEANED',
    runId: forgedRunId,
    ownerId: createHash('sha256').update(forgedToken).digest('hex'),
    ownerToken: forgedToken,
    cleanedAt: '2026-07-15T12:03:00.000Z'
  })}\n`)
  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId: forgedRunId,
      runToken: forgedToken,
      resourcesCleaned: true,
      redisReleaseReceipt: createHash('sha256').update(forgedToken).digest('hex')
    }),
    /P0_CLEANED_MARKER_INVALID/
  )

  const unsafeRunId = 'p0-s2-unsafe-control-a1'
  const unsafeToken = 'p0-s2-unsafe-control-owner-token-a1'
  const unsafeControl = join(artifactBase, unsafeRunId, 'control')
  mkdirSync(unsafeControl, { recursive: true })
  const external = join(root, 'external-ownership.json')
  writeFileSync(external, `${JSON.stringify({
    schemaVersion: 1,
    status: 'ACTIVE',
    runId: unsafeRunId,
    ownerId: createHash('sha256').update(unsafeToken).digest('hex'),
    ownerToken: unsafeToken,
    redisState: 'REDIS_RELEASE_ARMED',
    createdAt: '2026-07-15T12:04:00.000Z'
  })}\n`)
  linkSync(external, join(unsafeControl, 'ownership.json'))
  await assert.rejects(
    smokeContracts.completeControlCleanup({
      artifactBase,
      runId: unsafeRunId,
      runToken: unsafeToken,
      resourcesCleaned: true,
      redisReleaseReceipt: createHash('sha256').update(unsafeToken).digest('hex')
    }),
    /P0_CONTROL_FILE_UNSAFE/
  )
})

test('Windows local toolchain ignores PATH SystemRoot WINDIR COMSPEC and shim overrides', {
  skip: process.platform !== 'win32'
}, (t) => {
  assert.equal(typeof smokeContracts.resolveTrustedLocalToolchain, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-s3-poisoned-toolchain-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fakeSystem32 = join(root, 'System32')
  mkdirSync(fakeSystem32, { recursive: true })
  for (const path of [
    join(fakeSystem32, 'cmd.exe'),
    join(root, 'npm.cmd'),
    join(root, 'mvn.cmd'),
    join(root, 'taskkill.exe')
  ]) writeFileSync(path, 'poisoned')

  const poisoned = {
    PATH: root,
    SystemRoot: root,
    WINDIR: root,
    ComSpec: join(fakeSystem32, 'cmd.exe'),
    COMSPEC: join(fakeSystem32, 'cmd.exe'),
    MAVEN_HOME: root,
    MAVEN_CMD: join(root, 'mvn.cmd'),
    NPM_CMD: join(root, 'npm.cmd')
  }
  const previous = new Map()
  for (const [key, value] of Object.entries(poisoned)) {
    previous.set(key, process.env[key])
    process.env[key] = value
  }
  t.after(() => {
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  })

  const toolchain = smokeContracts.resolveTrustedLocalToolchain()
  assert.equal(toolchain.nodePath, realpathSync(process.execPath))
  assert.equal(relative(toolchain.nodeRoot, toolchain.npmCli).startsWith('..'), false)
  assert.equal(relative(toolchain.toolRoot, toolchain.mavenHome).startsWith('..'), false)
  assert.equal(relative(toolchain.toolRoot, toolchain.javaPath).startsWith('..'), false)
  assert.equal(relative(toolchain.mavenHome, toolchain.plexusLauncher).startsWith('..'), false)
  assert.equal(JSON.stringify(toolchain).includes(root), false)

  const normalize = (command) => smokeContracts.normalizeLocalCommandDescriptor({
    command,
    args: ['--version'],
    cwd: process.cwd(),
    env: { ...process.env, ...poisoned },
    shell: true
  }, { platform: 'win32' })
  const npm = normalize('npm.cmd')
  assert.equal(npm.command, toolchain.nodePath)
  assert.deepEqual(npm.args, [toolchain.npmCli, '--version'])
  const maven = normalize('mvn.cmd')
  assert.equal(maven.command, toolchain.javaPath)
  assert.equal(maven.args.includes(toolchain.plexusLauncher), true)
  assert.equal(maven.args.includes('org.codehaus.plexus.classworlds.launcher.Launcher'), true)
  assert.equal(maven.args.at(-1), '--version')
  for (const descriptor of [npm, maven]) {
    assert.equal(descriptor.shell, false)
    assert.equal(JSON.stringify(descriptor).includes(root), false)
    for (const key of Object.keys(poisoned)) {
      assert.notEqual(descriptor.env[key], poisoned[key], key)
    }
  }
})

test('recovery never terminates a reused PID without the same native process handle', async () => {
  const fingerprint = `sha256:${'e'.repeat(64)}`
  const startedAt = '2026-07-15T13:14:15.678Z'
  const resource = {
    type: 'process',
    id: 'backend:handle-bound',
    live: true,
    state: 'STARTED',
    pid: 4701,
    processStartedAt: startedAt,
    processFingerprint: fingerprint
  }
  const events = []
  const handle = {
    async inspectIdentity() {
      events.push('handle:inspect')
      return { pid: 4701, startedAt, processFingerprint: fingerprint }
    },
    async terminateTree(journaled) {
      events.push(`handle:terminate:${journaled.id}`)
    },
    async close() { events.push('handle:close') }
  }
  assert.deepEqual(await smokeContracts.terminateJournaledOwnedProcesses({
    resources: [resource],
    async acquireNativeProcessHandle(pid) {
      events.push(`handle:acquire:${pid}`)
      return handle
    },
    async inspectProcess() { throw new Error('PID_ONLY_INSPECTION_MUST_NOT_RUN') },
    async terminateProcessTree() { throw new Error('PID_ONLY_TERMINATION_MUST_NOT_RUN') }
  }), { terminated: 1 })
  assert.deepEqual(events, [
    'handle:acquire:4701',
    'handle:inspect',
    'handle:terminate:backend:handle-bound',
    'handle:close'
  ])

  let reusedTerminated = false
  let reusedClosed = false
  await assert.rejects(
    smokeContracts.terminateJournaledOwnedProcesses({
      resources: [{ ...resource, pid: 4702 }],
      async acquireNativeProcessHandle() {
        return {
          async inspectIdentity() {
            return {
              pid: 4702,
              startedAt: '2026-07-15T13:14:16.678Z',
              processFingerprint: fingerprint
            }
          },
          async terminateTree() { reusedTerminated = true },
          async close() { reusedClosed = true }
        }
      }
    }),
    /P0_PROCESS_IDENTITY_MISMATCH/
  )
  assert.equal(reusedTerminated, false)
  assert.equal(reusedClosed, true)

  await assert.rejects(
    smokeContracts.terminateJournaledOwnedProcesses({
      resources: [{ ...resource, pid: 4703 }],
      async acquireNativeProcessHandle() { return null }
    }),
    /P0_PROCESS_NATIVE_HANDLE_REQUIRED/
  )
})

function formalP0CaseFragment(definition, subruns, {
  status = 'PASS',
  subrunStatus = status,
  schemaVersion = 1,
  attempt = 1,
  durationMs = 1,
  scopeComplete = true,
  artifactHashes
} = {}) {
  const resolvedHashes = artifactHashes ?? Object.fromEntries(subruns.map((subrun) => [
    `subruns/${subrun.id}/result.json`,
    `sha256:${createHash('sha256').update(`${definition.id}:${subrun.id}:${attempt}`).digest('hex')}`
  ]))
  return {
    schemaVersion,
    id: definition.id,
    status,
    attempt,
    durationMs,
    scopeComplete,
    subruns: subruns.map((subrun) => ({
      ...subrun,
      status: typeof subrunStatus === 'function' ? subrunStatus(subrun) : subrunStatus
    })),
    artifactHashes: resolvedHashes
  }
}

test('multi-profile cases persist one canonical merged result after exact subrun dispatch', async (t) => {
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-s4-multi-profile-slice-a1',
    '--phase=selected',
    '--case=SOURCE-03'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const entry = plan.executionEntries.find(({ id }) => id === 'SOURCE-03')
  assert.ok(entry)
  const expectedProfiles = [...new Set(entry.selectedSubruns.map(({ profile }) => profile))]
  const dispatches = []
  const fragments = []
  const canonicalDuringDispatch = []
  let activeProfile = null
  const runRoot = mkdtempSync(join(tmpdir(), 'p0-s4-multi-profile-slice-'))
  t.after(() => rmSync(runRoot, { recursive: true, force: true }))
  const page = {
    assertEvidenceClean() {},
    async send(method) {
      assert.equal(method, 'Page.captureScreenshot')
      return { data: Buffer.from(`checkpoint:${activeProfile}`).toString('base64') }
    },
    snapshotEvidence() {
      return { networkEvidence: [], eventEvidence: [] }
    }
  }
  const p0Context = {
    run: { artifactRoot: runRoot },
    evidence: {
      captureCheckpoint: smokeContracts.captureCheckpoint,
      writeCaseResultAtomic
    }
  }
  const handlers = {
    async [entry.definition.handlerId](context, definition, details) {
      assert.equal(definition.id, entry.id)
      assert.equal(definition.requiredSubruns.length > 0, true)
      assert.equal(definition.requiredSubruns.every(({ profile }) => (
        profile === activeProfile
      )), true)
      dispatches.push(activeProfile)
      assert.equal(details.profileAttempt, dispatches.length)
      const checkpoint = await context.evidence.captureCheckpoint(context, 'final', {
        caseId: definition.id,
        pages: [page]
      })
      const fragment = formalP0CaseFragment(
        definition,
        definition.requiredSubruns,
        {
          durationMs: definition.requiredSubruns.length,
          artifactHashes: checkpoint.artifactHashes
        }
      )
      context.evidence.writeCaseResultAtomic(
        join(runRoot, definition.id, 'result.json'),
        fragment
      )
      canonicalDuringDispatch.push(existsSync(join(runRoot, definition.id, 'result.json')))
      fragments.push(fragment)
      return fragment
    }
  }
  const dependencies = {
    installSignalHandlers() { return () => {} },
    async initializeOwnership() {
      return {
        runRoot,
        options,
        ownerToken: 'p0-s4-multi-profile-owner-token-a1',
        ownerId: 'a'.repeat(64),
        databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_s4_multi_profile_a1',
        matrixDatabase: 'fx_p0_user_e2e_s4_multi_profile_a1',
        inheritedEnv: {},
        caseResults: []
      }
    },
    createP0Context() { return p0Context },
    phaseOperations: {
      async runPreflight() { return { id: 'AUTH-01', status: 'PASS' } },
      async runAuthority() { return { id: 'AUTH-03', status: 'PASS' } },
      async stopProfileBackend() { activeProfile = null },
      async assertProfilePortFree() {},
      async startProfileBackend({ profile }) { activeProfile = profile; return { profile } },
      async waitForProfileHealth() {},
      async waitForProfileBusinessEndpoint() {},
      async verifyProfileDatabaseIdentity() {},
      async writeReport(context, plan) {
        return exactP0ReportPhaseEvidence(context, plan)
      }
    },
    handlers,
    async writeReport(execution) { return { caseResults: execution.caseResults } },
    async cleanup() { return { status: 'CLEANED' } }
  }

  const completed = await smokeContracts.runP0Suite(options, dependencies)
  assert.deepEqual(dispatches, expectedProfiles)
  assert.equal(completed.execution.caseResults.length, 1)
  const merged = completed.execution.caseResults[0]
  assert.equal(merged.id, entry.id)
  assert.equal(merged.schemaVersion, 1)
  assert.equal(merged.status, 'PASS')
  assert.equal(merged.attempt, 1)
  assert.equal(merged.durationMs, entry.selectedSubruns.length)
  assert.equal(merged.scopeComplete, !entry.cropped)
  assert.equal(
    Object.keys(merged.artifactHashes).length,
    entry.selectedSubruns.length
  )
  assert.deepEqual(
    merged.subruns.map(({ id }) => id),
    entry.selectedSubruns.map(({ id }) => id)
  )
  assert.deepEqual(canonicalDuringDispatch, expectedProfiles.map(() => false))
  assert.deepEqual(
    readdirSync(join(runRoot, entry.id, 'fragments')).toSorted(),
    expectedProfiles.map((profile, index) => `${profile}-${index + 1}.json`).toSorted()
  )
  assert.deepEqual(
    Object.keys(merged.artifactHashes).toSorted(),
    entry.selectedSubruns.map((subrun, index) => (
      `${entry.id}/final-${subrun.id}-p${index + 1}.png`
    )).toSorted()
  )
  const canonicalPath = join(runRoot, entry.id, 'result.json')
  const expectedCanonicalPath = join(runRoot, 'expected-canonical.json')
  writeCaseResultAtomic(expectedCanonicalPath, merged)
  assert.equal(
    readFileSync(canonicalPath, 'utf8'),
    readFileSync(expectedCanonicalPath, 'utf8')
  )
  assert.equal(typeof smokeContracts.mergeP0CaseFragments, 'function')
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, fragments.slice(1)),
    /P0_CASE_FRAGMENT_INCOMPLETE/
  )
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [...fragments, fragments[0]]),
    /P0_CASE_FRAGMENT_DUPLICATE/
  )
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      formalP0CaseFragment(entry.definition, [{
        id: 'unexpected-subrun',
        profile: 'UI_CORE',
        viewport: 'desktop'
      }])
    ]),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )
})

test('formal case fragment merge preserves evidence and terminal status priority', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const entry = {
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }
  const [core, target] = definition.requiredSubruns
  const mergeStatuses = (leftStatus, rightStatus) => smokeContracts.mergeP0CaseFragments(
    entry,
    [
      formalP0CaseFragment(definition, [{ ...core, checkpoint: 'core-evidence' }], {
        status: leftStatus,
        durationMs: 7
      }),
      formalP0CaseFragment(definition, [target], {
        status: rightStatus,
        durationMs: 11
      })
    ]
  )

  const blocked = mergeStatuses('PASS', 'BLOCKED')
  assert.equal(blocked.status, 'BLOCKED')
  assert.equal(blocked.schemaVersion, 1)
  assert.equal(blocked.attempt, 1)
  assert.equal(blocked.durationMs, 18)
  assert.equal(blocked.scopeComplete, true)
  assert.equal(blocked.subruns[0].checkpoint, 'core-evidence')
  assert.deepEqual(
    Object.keys(blocked.artifactHashes).toSorted(),
    definition.requiredSubruns
      .map(({ id }) => `subruns/${id}/result.json`)
      .toSorted()
  )

  assert.equal(mergeStatuses('BLOCKED', 'INVALID_TEST').status, 'INVALID_TEST')
  assert.equal(mergeStatuses('INVALID_TEST', 'FAIL').status, 'FAIL')

  const cropped = smokeContracts.mergeP0CaseFragments(
    { ...entry, selectedSubruns: [core], cropped: true },
    [formalP0CaseFragment(definition, [core], { scopeComplete: true })]
  )
  assert.equal(cropped.scopeComplete, false)
})

test('formal case fragment merge retains unified evidence and explicit hashes on disk', (t) => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const [core, target] = definition.requiredSubruns
  const entry = {
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }
  const directory = mkdtempSync(join(tmpdir(), 'p0-merged-case-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const startedAt = '2026-07-23T00:00:00.000Z'
  const middleAt = '2026-07-23T00:00:01.000Z'
  const finishedAt = '2026-07-23T00:00:02.000Z'
  const fragment = (subrun, suffix, from, to) => ({
    ...formalP0CaseFragment(definition, [subrun]),
    commit: RUN_STATE_COMMIT_A,
    database: 'fx_p0_user_e2e_perp_01_a1',
    profile: subrun.profile,
    viewport: subrun.viewport,
    startedAt: from,
    finishedAt: to,
    preconditions: [{ status: 'PASS', subrunId: subrun.id }],
    userActions: [{
      action: `submit-${suffix}`,
      requestRef: `${suffix}.1`,
      subrunId: subrun.id
    }],
    fixtureActions: [],
    contractProbes: [{ status: 'PASS', subrunId: subrun.id }],
    replayProbes: [],
    checkpoints: [{ status: 'PASS', subrunId: subrun.id }],
    financialCalculation: {
      status: 'PASS',
      subrunId: subrun.id,
      amount: suffix === 'core' ? '1.25000000' : '2.50000000'
    },
    uiEvidence: [{ status: 'OBSERVED', subrunId: subrun.id }],
    networkEvidence: [{
      method: 'POST',
      url: 'http://127.0.0.1:18086/api/trading/orders',
      status: 200,
      requestId: `${suffix}.1`,
      subrunId: subrun.id
    }],
    apiEvidence: [{ status: 'OBSERVED', subrunId: subrun.id }],
    dbEvidence: [{ status: 'OBSERVED', subrunId: subrun.id }],
    eventEvidence: [{ status: 'OBSERVED', subrunId: subrun.id }],
    consoleErrors: [],
    cleanup: { status: 'PASS' }
  })
  const merged = smokeContracts.mergeP0CaseFragments(entry, [
    fragment(core, 'core', startedAt, middleAt),
    fragment(target, 'target', middleAt, finishedAt)
  ])

  assert.equal(merged.commit, RUN_STATE_COMMIT_A)
  assert.equal(merged.database, 'fx_p0_user_e2e_perp_01_a1')
  assert.equal(merged.profile, 'UI_CORE')
  assert.equal(merged.viewport, 'desktop')
  assert.equal(merged.startedAt, startedAt)
  assert.equal(merged.finishedAt, finishedAt)
  for (const field of [
    'preconditions',
    'userActions',
    'contractProbes',
    'checkpoints',
    'uiEvidence',
    'networkEvidence',
    'apiEvidence',
    'dbEvidence',
    'eventEvidence'
  ]) {
    assert.equal(merged[field].length, 2, field)
  }
  assert.deepEqual(merged.fixtureActions, [])
  assert.deepEqual(merged.replayProbes, [])
  assert.deepEqual(merged.consoleErrors, [])
  assert.equal(merged.financialCalculation.checks.length, 2)
  assert.deepEqual(merged.cleanup, { status: 'PASS' })

  const path = join(directory, 'result.json')
  writeCaseResultAtomic(path, merged)
  const persisted = JSON.parse(readFileSync(path, 'utf8'))
  assert.deepEqual(persisted.artifactHashes, merged.artifactHashes)
  assert.equal(persisted.userActions.length, 2)
  assert.equal(persisted.financialCalculation.checks.length, 2)
})

test('formal case fragment merge rejects metadata and artifact hash collisions', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const [core, target] = definition.requiredSubruns
  const entry = {
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }
  const sharedPath = 'subruns/shared/result.json'
  const sharedHash = `sha256:${'a'.repeat(64)}`
  const first = formalP0CaseFragment(definition, [core], {
    artifactHashes: { [sharedPath]: sharedHash }
  })

  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      first,
      formalP0CaseFragment(definition, [target], {
        artifactHashes: { [sharedPath]: sharedHash }
      })
    ]),
    /P0_CASE_FRAGMENT_HASH_DUPLICATE/
  )
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      first,
      formalP0CaseFragment(definition, [target], {
        artifactHashes: { [sharedPath]: `sha256:${'b'.repeat(64)}` }
      })
    ]),
    /P0_CASE_FRAGMENT_HASH_CONFLICT/
  )

  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      first,
      formalP0CaseFragment(definition, [target], { attempt: 2 })
    ]),
    /P0_CASE_FRAGMENT_METADATA_CONFLICT/
  )

  for (const [label, patch] of [
    ['duration', { durationMs: -1 }],
    ['scope', { scopeComplete: 'false' }],
    ['hash', { artifactHashes: { bad: 'not-a-sha256' } }]
  ]) {
    assert.throws(
      () => smokeContracts.mergeP0CaseFragments(entry, [
        formalP0CaseFragment(definition, [core], patch),
        formalP0CaseFragment(definition, [target])
      ]),
      /P0_CASE_FRAGMENT_UNEXPECTED/,
      label
    )
  }
})

test('formal case fragment merge rejects unsupported schema versions', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      id: definition.id,
      definition,
      selectedSubruns: definition.requiredSubruns,
      cropped: false
    }, definition.requiredSubruns.map((subrun) => (
      formalP0CaseFragment(definition, [subrun], { schemaVersion: 999 })
    ))),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )
})

test('formal case fragment merge rejects incomplete fragment scope', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const [core, target] = definition.requiredSubruns
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      id: definition.id,
      definition,
      selectedSubruns: definition.requiredSubruns,
      cropped: false
    }, [
      formalP0CaseFragment(definition, [core], { scopeComplete: false }),
      formalP0CaseFragment(definition, [target])
    ]),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )
})

test('formal case fragment merge rejects unsafe artifact paths', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const [core, target] = definition.requiredSubruns
  const entry = {
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }
  const hash = `sha256:${'a'.repeat(64)}`
  for (const [label, path] of [
    ['absolute', '/tmp/result.json'],
    ['drive-absolute', 'C:/tmp/result.json'],
    ['dot-segment', 'subruns/./result.json'],
    ['parent-segment', 'subruns/../result.json'],
    ['backslash', 'subruns\\core\\result.json'],
    ['empty-segment', 'subruns//result.json'],
    ['control-character', 'subruns/\u0000/result.json']
  ]) {
    assert.throws(
      () => smokeContracts.mergeP0CaseFragments(entry, [
        formalP0CaseFragment(definition, [core], {
          artifactHashes: { [path]: hash }
        }),
        formalP0CaseFragment(definition, [target])
      ]),
      /P0_CASE_FRAGMENT_UNEXPECTED/,
      label
    )
  }
})

test('formal case fragment merge rejects incomplete definitions and accessor or prototype forgery', () => {
  const definition = P0_CASES.find(({ id }) => id === 'PERP-01')
  const [core, target] = definition.requiredSubruns
  const entry = {
    id: definition.id,
    definition,
    selectedSubruns: definition.requiredSubruns,
    cropped: false
  }
  const fragments = [
    formalP0CaseFragment(definition, [core]),
    formalP0CaseFragment(definition, [target])
  ]

  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      ...entry,
      definition: {
        ...definition,
        requiredSubruns: [core, core]
      }
    }, fragments),
    /P0_CASE_FRAGMENT_INCOMPLETE/
  )
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      ...entry,
      selectedSubruns: [core]
    }, [fragments[0]]),
    /P0_CASE_FRAGMENT_INCOMPLETE/
  )
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      ...entry,
      selectedSubruns: [{ ...core, profile: 'ORDER_TRIGGER' }, target]
    }, fragments),
    /P0_CASE_FRAGMENT_INCOMPLETE/
  )

  let definitionGetterCalls = 0
  const getterDefinition = { ...definition }
  Object.defineProperty(getterDefinition, 'requiredSubruns', {
    enumerable: true,
    get() {
      definitionGetterCalls += 1
      return definition.requiredSubruns
    }
  })
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments({
      ...entry,
      definition: getterDefinition
    }, fragments),
    /P0_CASE_FRAGMENT_INCOMPLETE/
  )
  assert.equal(definitionGetterCalls, 0)

  let statusGetterCalls = 0
  const getterFragment = formalP0CaseFragment(definition, [core])
  Object.defineProperty(getterFragment, 'status', {
    enumerable: true,
    get() {
      statusGetterCalls += 1
      return 'PASS'
    }
  })
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      getterFragment,
      fragments[1]
    ]),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )
  assert.equal(statusGetterCalls, 0)

  const inheritedStatus = Object.create({ status: 'PASS' })
  Object.assign(inheritedStatus, formalP0CaseFragment(definition, [core]))
  delete inheritedStatus.status
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      inheritedStatus,
      fragments[1]
    ]),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )

  const inheritedSubrun = Object.create(core)
  inheritedSubrun.status = 'PASS'
  const forgedSubrunFragment = formalP0CaseFragment(definition, [core])
  forgedSubrunFragment.subruns = [inheritedSubrun]
  assert.throws(
    () => smokeContracts.mergeP0CaseFragments(entry, [
      forgedSubrunFragment,
      fragments[1]
    ]),
    /P0_CASE_FRAGMENT_UNEXPECTED/
  )
})

test('resume validates persisted and live ownership before any override or compose mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-s4-resume-zero-mutation-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-s4-resume-zero-mutation-a1'
  const runToken = 'p0-s4-resume-zero-mutation-owner-token-a1'
  const canonicalDatabase = 'fx_p0_user_e2e_canonical_1_abcdef123456'
  const matrixDatabase = 'fx_p0_user_e2e_preflight_1_abcdef123457'
  const identity = review1P0Identity()
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${runId}`,
    `--resume=${runId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: options.mode,
    selection: {
      caseIds: ['SPOT-01'],
      phases: [],
      profiles: [],
      viewports: [],
      metadata: s10RequestedPhaseSelection('selected').metadata
    },
    database: { canonical: canonicalDatabase, matrix: matrixDatabase },
    identity,
    profileWorkerMap: identity.profileWorkerMap,
    now: () => '2026-07-15T14:00:00.000Z'
  })
  const composeIdentity = {
    project: 'infra',
    postgres: { id: 'a'.repeat(64), image: 'postgres:16', host: '127.0.0.1', hostPort: 5432 },
    redis: { id: 'b'.repeat(64), image: 'redis:7', host: '127.0.0.1', hostPort: 6379 }
  }
  const overridePath = composeTarget.composeFiles[1]
  const overrideText = readFileSync(overridePath, 'utf8')
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.redisState = 'SNAPSHOT_READY'
  active.composeTarget = composeTarget
  active.composeIdentity = composeIdentity
  active.journal = {
    schemaVersion: 1,
    sequence: 2,
    resources: [
      {
        type: 'process',
        id: 'compose-up',
        live: true,
        commandFingerprint: `sha256:${'c'.repeat(64)}`,
        pid: 5391,
        processStartedAt: 'spawn:p0-s4-compose-up',
        processFingerprint: `sha256:${'c'.repeat(64)}`,
        sequence: 1,
        state: 'COMPLETED',
        recordedAt: '2026-07-15T14:00:03.000Z',
        startedAt: '2026-07-15T14:00:04.000Z',
        completedAt: '2026-07-15T14:00:05.000Z'
      },
      {
        type: 'database',
        id: matrixDatabase,
        sequence: 2,
        state: 'STARTED',
        recordedAt: '2026-07-15T14:00:06.000Z',
        startedAt: '2026-07-15T14:00:07.000Z'
      }
    ]
  }
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  markP0RedisSnapshotReady(control)
  smokeContracts.openP0RunState({
    path: join(control.runRoot, 'run-state.json'),
    resume: false,
    runId,
    mode: options.mode,
    commit: identity.commit,
    worktreeFingerprint: identity.worktreeFingerprint,
    schemaVersion: identity.schemaVersion,
    registryFingerprint: identity.registryFingerprint,
    definitions: P0_CASES,
    selection: {
      caseIds: ['SPOT-01'],
      phases: [],
      profiles: [],
      viewports: [],
      metadata: s10RequestedPhaseSelection('selected').metadata
    }
  })
  const beforeControl = readFileSync(control.ownershipPath, 'utf8')
  const events = []
  const infrastructure = {
    ...review1SafetyInfrastructure(events),
    async verifyComposeContainers({ composeFiles }) {
      events.push('compose:verify-live')
      assert.deepEqual(composeFiles, [
        join(fileURLToPath(new URL('../infra/docker-compose.yml', import.meta.url))),
        join(artifactBase, '.runtime', 'compose.loopback.yml')
      ])
      return { ...composeIdentity, credentials: { username: 'postgres', password: 'password' } }
    }
  }
  const redis = {
    async waitUntilReady() { events.push('redis:ready') },
    async get(key) { events.push(`redis:get:${key}`); return runToken }
  }
  const postgres = {
    async waitUntilReady() { events.push('postgres:ready') },
    async readDatabaseOwnership(segmentName) {
      events.push(`postgres:owner:${segmentName}`)
      return { segmentName, ownerMarker: `p0-owner:${runToken}` }
    },
    async probeDatabase({ segmentName }) {
      events.push(`postgres:probe:${segmentName}`)
      return {
        currentDatabase: segmentName,
        ownerMarker: `p0-owner:${runToken}`,
        timezone: 'UTC'
      }
    }
  }

  const reservation = await smokeContracts.prepareP0RunReservation({
    options,
    plan,
    artifactBase,
    inheritedEnv: {},
    captureIdentity: async () => { events.push('identity'); return identity },
    databaseExists: async () => { throw new Error('RESUME_DATABASE_COLLISION_CHECK_FORBIDDEN') },
    nextRunToken: () => { throw new Error('RESUME_TOKEN_GENERATION_FORBIDDEN') },
    nextRandomSuffix: () => { throw new Error('RESUME_SUFFIX_GENERATION_FORBIDDEN') },
    infrastructure
  })
  const persisted = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  const databases = smokeContracts.validateP0ResumeJournal(persisted, matrixDatabase)
  const liveComposeIdentity = await infrastructure.verifyComposeContainers({
    composeFiles: reservation.composeTarget.composeFiles,
    expectedProject: reservation.composeTarget.expectedProject
  })
  assert.deepEqual({
    project: liveComposeIdentity.project,
    postgres: liveComposeIdentity.postgres,
    redis: liveComposeIdentity.redis
  }, composeIdentity)
  await postgres.waitUntilReady()
  await redis.waitUntilReady()
  await smokeContracts.acquireRedisOwnership({ redis, runToken, role: 'child' })
  for (const segmentName of databases) {
    assert.deepEqual(await postgres.readDatabaseOwnership(segmentName), {
      segmentName,
      ownerMarker: `p0-owner:${runToken}`
    })
    await smokeContracts.verifyDatabaseIdentity({
      segmentName,
      runToken,
      databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${segmentName}`,
      postgres
    })
  }
  const prepared = { ...reservation, composeIdentity: liveComposeIdentity }
  assert.equal(prepared.resumed, true)
  assert.deepEqual(prepared.composeIdentity, {
    ...composeIdentity,
    credentials: { username: 'postgres', password: 'password' }
  })
  assert.equal(events.some((event) => event.startsWith('MUTATION:')), false)
  assert.equal(events.includes('compose:verify-live'), true)
  assert.equal(events.includes('redis:get:p0:e2e:owner'), true)
  assert.equal(events.includes(`postgres:probe:${matrixDatabase}`), true)
  assert.equal(readFileSync(control.ownershipPath, 'utf8'), beforeControl)
  assert.equal(readFileSync(overridePath, 'utf8'), overrideText)
})

test('P0 resume requires existing state and exact run mode selection and evidence identity', (t) => {
  assert.equal(typeof smokeContracts.openP0RunState, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-resume-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const statePath = join(root, 'run-state.json')
  const identity = {
    commit: 'a'.repeat(40),
    worktreeFingerprint: 'b'.repeat(64),
    schemaVersion: 1,
    registryFingerprint: P0_REGISTRY_FINGERPRINT
  }
  const selection = {
    caseIds: ['SPOT-01'],
    phases: [],
    profiles: ['UI_CORE'],
    viewports: ['desktop'],
    metadata: s10RequestedPhaseSelection('selected').metadata
  }
  const options = {
    path: statePath,
    resume: false,
    runId: 'p0-resume-identity-a1',
    mode: 'discovery',
    selection,
    definitions: P0_CASES,
    ...identity
  }

  let createCalls = 0
  assert.throws(
    () => smokeContracts.openP0RunState({
      ...options,
      selection: { ...selection, phases: ['selected'] },
      createState() { createCalls += 1 }
    }),
    /P0_SELECTION_INVALID/
  )
  assert.equal(createCalls, 0)
  assert.throws(
    () => smokeContracts.openP0RunState({
      ...options,
      path: join(root, 'missing-resume.json'),
      resume: true,
      createState() { createCalls += 1 }
    }),
    /P0_RESUME_STATE_MISSING/
  )
  assert.equal(createCalls, 0)
  assert.equal(existsSync(join(root, 'missing-resume.json')), false)

  const created = smokeContracts.openP0RunState(options)
  assert.equal(
    created.runId,
    `sha256:${createHash('sha256').update(options.runId).digest('hex')}`
  )
  assert.equal(created.mode, 'DISCOVERY')
  assert.deepEqual(created.selection, selection)
  const baseline = readFileSync(statePath, 'utf8')
  assert.throws(
    () => smokeContracts.openP0RunState(options),
    /P0_RUN_STATE_EXISTS/
  )
  assert.equal(readFileSync(statePath, 'utf8'), baseline)
  assert.deepEqual(smokeContracts.openP0RunState({ ...options, resume: true }), created)

  for (const [label, patch, error] of [
    ['runId', { runId: 'p0-resume-identity-other' }, 'P0_RESUME_MISMATCH: runId'],
    ['mode', { mode: 'certification' }, 'P0_RESUME_MISMATCH: mode'],
    ['selection', {
      selection: { ...selection, caseIds: ['SPOT-02'] }
    }, 'P0_RESUME_MISMATCH: selection'],
    ['commit', { commit: 'c'.repeat(40) }, 'P0_RESUME_MISMATCH: commit'],
    ['worktree', {
      worktreeFingerprint: 'd'.repeat(64)
    }, 'P0_RESUME_MISMATCH: worktreeFingerprint'],
    ['schema', { schemaVersion: 2 }, 'P0_RESUME_MISMATCH: schemaVersion'],
    ['registry', {
      registryFingerprint: 'e'.repeat(64)
    }, 'P0_RESUME_MISMATCH: registryFingerprint']
  ]) {
    assert.throws(
      () => smokeContracts.openP0RunState({ ...options, ...patch, resume: true }),
      new RegExp(error),
      label
    )
    assert.equal(readFileSync(statePath, 'utf8'), baseline, label)
  }

  for (const [field, value] of [
    ['runId', 'p0-resume-forged-a1'],
    ['mode', 'certification'],
    ['selection', { ...selection, profiles: ['ORDER_TRIGGER'] }]
  ]) {
    const forged = JSON.parse(baseline)
    forged[field] = value
    writeFileSync(statePath, `${JSON.stringify(forged, null, 2)}\n`)
    assert.throws(
      () => smokeContracts.openP0RunState({ ...options, resume: true }),
      new RegExp(`P0_RESUME_MISMATCH: ${field}`),
      `persisted ${field}`
    )
    writeFileSync(statePath, baseline)
  }
})

test('canonical child is singular argless and cleaned before authority and matrix', async () => {
  assert.equal(typeof smokeContracts.executeP0PlanPhases, 'function')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-canonical-order-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options)
  const ownerToken = 'canonical-child-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const scriptPath = resolve(tmpdir(), 'canonical-smoke-script.mjs')
  const sequence = []
  const childInvocations = []
  const result = await smokeContracts.executeP0PlanPhases({
    plan,
    context: {
      runRoot: resolve(tmpdir(), options.runId),
      options,
      scriptPath,
      ownerToken,
      ownerId,
      canonicalDatabase: 'fx_p0_user_e2e_canonical_order_a1',
      inheritedEnv: { PATH: 'safe-path' }
    },
    operations: {
      async runPreflight() {
        sequence.push('preflight')
        return { id: 'AUTH-01', status: 'PASS' }
      },
      async assertRedisOwnership(_context, boundary) { sequence.push(`redis:${boundary}`) },
      async stopParentBackend() { sequence.push('parent-backend-stop') },
      async assertBusinessPortsFree(ports) {
        sequence.push(`ports-free:${ports.join(',')}`)
      },
      async runCanonicalChild(invocation) {
        sequence.push('canonical-child')
        childInvocations.push(invocation)
        return { status: 0, signal: null }
      },
      async verifyCanonicalChildCleanup(child) {
        assert.deepEqual(child, { status: 0, signal: null })
        sequence.push('canonical-cleanup')
        return { id: 'CAT-01', status: 'PASS' }
      },
      async runAuthority() {
        sequence.push('authority')
        return { id: 'AUTH-03', status: 'PASS' }
      },
      async runMatrixPhase(phase) { sequence.push(`matrix:${phase}`) },
      async writeReport(context, receivedPlan) {
        sequence.push('report')
        return exactP0ReportPhaseEvidence(context, receivedPlan)
      }
    }
  })

  assert.deepEqual(result, { canonicalChildren: 1, matrixPhases: 7 })
  assert.equal(childInvocations.length, 1)
  assert.equal(childInvocations[0].command, process.execPath)
  assert.deepEqual(childInvocations[0].args, [scriptPath])
  assert.equal(childInvocations[0].args.some((argument) => argument.startsWith('--')), false)
  assert.equal(childInvocations[0].env.P0_RUN_OWNER_TOKEN, ownerToken)
  assert.equal(
    JSON.stringify(childInvocations[0].log).includes(ownerToken),
    false
  )
  assert.deepEqual(sequence, [
    'preflight',
    'redis:before-canonical',
    'parent-backend-stop',
    'ports-free:18086,5199,5200',
    'canonical-child',
    'canonical-cleanup',
    'redis:after-canonical',
    'ports-free:18086,5199,5200',
    'authority',
    'matrix:ui-core',
    'matrix:order-trigger',
    'matrix:funding',
    'matrix:liquidation',
    'matrix:source',
    'matrix:resilience',
    'matrix:ui',
    'report'
  ])
})

test('authority phase owns an isolated UI_CORE database and backend before its gate', async () => {
  const events = []
  const context = {
    runId: 'p0-authority-owned-profile-a1',
    ownerId: 'a'.repeat(64),
    matrixDatabase: 'fx_p0_user_e2e_authority_fallback_a1',
    databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_authority_fallback_a1',
    inheritedEnv: {
      ADMIN_BOOTSTRAP_ENABLED: 'false',
      ADMIN_BOOTSTRAP_EMAIL: 'poison@example.com',
      ADMIN_BOOTSTRAP_PASSWORD: 'poison'
    }
  }
  const authorityDatabase = 'fx_p0_user_e2e_authority_owned_a1'
  await smokeContracts.executeP0PlanPhases({
    plan: { phases: ['authority'] },
    context,
    operations: {
      async stopProfileBackend(_context, profile) {
        events.push(`stop:${profile}`)
      },
      async assertProfilePortFree(port, _context, profile) {
        events.push(`free:${profile}:${port}`)
      },
      async prepareProfileDatabase({ phase, attempt }) {
        assert.equal(phase, 'authority')
        assert.equal(attempt, 1)
        events.push('database:prepare')
        return {
          segmentName: authorityDatabase,
          databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${authorityDatabase}`
        }
      },
      async startProfileBackend({ phase, profile, attempt, databaseUrl, environment }) {
        assert.equal(phase, 'authority')
        assert.equal(profile, 'UI_CORE')
        assert.equal(attempt, 1)
        assert.equal(databaseUrl.endsWith(`/${authorityDatabase}`), true)
        assert.equal(environment.TRADING_PENDING_ORDER_EXECUTION_ENABLED, 'false')
        assert.equal(environment.TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED, 'false')
        assert.equal(environment.TRADING_FUNDING_ENABLED, 'false')
        assert.equal(environment.TRADING_LIQUIDATION_ENABLED, 'false')
        assert.equal(environment.ADMIN_BOOTSTRAP_ENABLED, 'true')
        assert.match(environment.ADMIN_BOOTSTRAP_EMAIL, /^p0-authority-/)
        assert.notEqual(environment.ADMIN_BOOTSTRAP_EMAIL, 'poison@example.com')
        assert.notEqual(environment.ADMIN_BOOTSTRAP_PASSWORD, 'poison')
        events.push('backend:start')
        return { profile, databaseUrl }
      },
      async waitForProfileHealth() {
        events.push('backend:health')
      },
      async waitForProfileBusinessEndpoint() {
        events.push('backend:business')
      },
      async verifyProfileDatabaseIdentity() {
        events.push('database:verify')
      },
      async ensureParentFrontends() {
        assert.equal(context.activeDatabaseSegment, authorityDatabase)
        events.push('frontends')
      },
      async runAuthority(received) {
        assert.equal(received.activeDatabaseSegment, authorityDatabase)
        events.push('gate')
        return {
          status: 'PASS',
          authorityBundleFixture: 'PASS'
        }
      }
    }
  })

  assert.deepEqual(events, [
    'stop:UI_CORE',
    'free:UI_CORE:18086',
    'database:prepare',
    'backend:start',
    'backend:health',
    'backend:business',
    'database:verify',
    'frontends',
    'gate'
  ])
})

test('managed preflight runs exact gates invocation scoped IT and owned OpenAPI lifecycle', async () => {
  assert.equal(typeof smokeContracts.runP0Preflight, 'function')
  const root = resolve(tmpdir(), 'p0-preflight-contract')
  const gateOutput = join(root, 'artifacts', 'gates', 'surefire.json')
  const invocationStartedAt = '2026-07-15T02:03:04.567Z'
  const commands = []
  const events = []
  const recorded = []
  const operations = {
    async runCommand(command) {
      commands.push(command)
      events.push(`command:${command.id}`)
      return { status: 0, stdout: `${command.id}-ok`, stderr: '' }
    },
    async recordGate(id, result) {
      recorded.push({ id, status: result.status })
    },
    async startOwnedBackend(options) {
      events.push('backend:start')
      return { pid: 4242, options }
    },
    async waitForBackendHealth(backend, url) {
      events.push(`backend:health:${backend.pid}:${url}`)
    },
    async waitForBusinessEndpoint(backend, url) {
      events.push(`backend:business:${backend.pid}:${url}`)
    },
    async stopOwnedBackend(backend) {
      events.push(`backend:stop:${backend?.pid ?? 'none'}`)
    },
    async assertBusinessPortsFree(ports) {
      events.push(`ports-free:${ports.join(',')}`)
    }
  }

  const result = await smokeContracts.runP0Preflight({
    projectRoot: root,
    gateOutput,
    databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_preflight_a1',
    now: () => invocationStartedAt,
    operations
  })
  assert.deepEqual(result, {
    status: 'PASS',
    invocationStartedAt,
    gates: 12,
    itClasses: 14,
    guardClasses: 6
  })
  assert.deepEqual(commands.map(({ id }) => id), [
    'backend-unit',
    'node-contracts',
    'web-test',
    'web-build',
    'admin-test',
    'admin-build',
    'architecture',
    'security-guards',
    'database-concurrency-it',
    'surefire-gate',
    'contract-export',
    'contract-check'
  ])
  assert.deepEqual(recorded, commands.map(({ id }) => ({ id, status: 0 })))
  const guardCommand = commands.find(({ id }) => id === 'security-guards')
  assert.equal(guardCommand.command, process.platform === 'win32' ? 'mvn.cmd' : 'mvn')
  assert.deepEqual(guardCommand.args, [
    '-Dtest=DemoExecutionGuardTest,ExecutionAdapterApplicationContextTest,ExecutionModeStartupValidatorTest,ProductionConfigurationSafetyTest,ProviderModeApplicationContextTest,DatabaseItConfigurationContractTest',
    'test'
  ])
  const itClasses = [
    'PostgresDatabaseIT',
    'V46V47EmptyDatabaseIT',
    'V45ToV47DemoResetIT',
    'Task5PostgresFullFillIT',
    'Task6PostgresSpotIT',
    'Task7PostgresDemoLifecycleIT',
    'Task8PostgresTradingSettingsIT',
    'Task9PostgresPerpetualOrderIT',
    'Task10PostgresProtectionIT',
    'Task11PostgresFundingIT',
    'DemoTradingConcurrencyIT',
    'PerpetualPositionConcurrencyIT',
    'ProtectionOrderConcurrencyIT',
    'FundingLiquidationConcurrencyIT'
  ]
  const itCommand = commands.find(({ id }) => id === 'database-concurrency-it')
  assert.deepEqual(itCommand.args, [
    '-Dapi.version=1.44',
    `-Dtest=${itClasses.join(',')}`,
    'test'
  ])
  assert.equal(itCommand.env.DATABASE_PASSWORD, 'database-it-non-secret-password')
  const surefire = commands.find(({ id }) => id === 'surefire-gate')
  assert.equal(surefire.command, process.execPath)
  assert.ok(surefire.args.includes(`--classes=${itClasses.join(',')}`))
  assert.ok(surefire.args.includes(`--started-at=${invocationStartedAt}`))
  assert.ok(surefire.args.includes(`--output=${gateOutput}`))
  for (const contract of commands.filter(({ id }) => id.startsWith('contract-'))) {
    assert.deepEqual(contract.env, {
      OPENAPI_SOURCE_URL: 'http://127.0.0.1:18086/v3/api-docs'
    })
  }
  assert.equal(JSON.stringify(commands).includes('localhost:8080'), false)
  assert.deepEqual(events.slice(-7), [
    'backend:start',
    'backend:health:4242:http://127.0.0.1:18086/actuator/health',
    'backend:business:4242:http://127.0.0.1:18086/api/market/symbols',
    'command:contract-export',
    'command:contract-check',
    'backend:stop:4242',
    'ports-free:18086'
  ])

  const failureEvents = []
  await assert.rejects(
    smokeContracts.runP0Preflight({
      projectRoot: root,
      gateOutput,
      databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_preflight_a1',
      now: () => invocationStartedAt,
      operations: {
        ...operations,
        async runCommand(command) {
          if (command.id === 'contract-check') throw new Error('CONTRACT_CHECK_FAILED')
          return { status: 0, stdout: '', stderr: '' }
        },
        async recordGate() {},
        async startOwnedBackend() { failureEvents.push('start'); return { pid: 4343 } },
        async waitForBackendHealth() {},
        async waitForBusinessEndpoint() {},
        async stopOwnedBackend() { failureEvents.push('stop') },
        async assertBusinessPortsFree() { failureEvents.push('ports-free') }
      }
    }),
    /CONTRACT_CHECK_FAILED/
  )
  assert.deepEqual(failureEvents, ['start', 'stop', 'ports-free'])
})

test('P0 lifecycle cleans exactly once on signal gate case and report failures', async () => {
  assert.equal(typeof smokeContracts.executeP0SuiteLifecycle, 'function')
  const options = {
    suite: 'p0',
    mode: 'discovery',
    phase: 'all',
    runId: 'p0-lifecycle-a1'
  }
  for (const [scenario, failAt, error] of [
    ['gate', 'prepare', 'GATE_FAILED'],
    ['case', 'execute', 'CASE_FAILED'],
    ['report', 'report', 'REPORT_FAILED'],
    ['signal', 'signal', 'P0_INTERRUPTED: SIGINT']
  ]) {
    const events = []
    let signalHandler
    let cleanupCalls = 0
    await assert.rejects(
      smokeContracts.executeP0SuiteLifecycle({
        options,
        operations: {
          installSignalHandlers(handler) {
            signalHandler = handler
            events.push('signals:install')
            return () => events.push('signals:remove')
          },
          async prepare() {
            events.push('prepare')
            if (failAt === 'prepare') throw new Error(error)
            return { prepared: true }
          },
          async execute(_prepared, { signal }) {
            events.push('execute')
            assert.equal(signal.aborted, false)
            if (failAt === 'signal') {
              signalHandler('SIGINT')
              signalHandler('SIGINT')
              return { executed: false }
            }
            if (failAt === 'execute') throw new Error(error)
            return { executed: true }
          },
          async writeReport() {
            events.push('report')
            if (failAt === 'report') throw new Error(error)
            return { verdict: 'PASS' }
          },
          async cleanup(_prepared, details) {
            cleanupCalls += 1
            events.push(`cleanup:${details.interrupted ? 'interrupted' : 'normal'}`)
            return { status: 'CLEANED' }
          }
        }
      }),
      new RegExp(error),
      scenario
    )
    assert.equal(cleanupCalls, 1, scenario)
    assert.equal(events.at(-1), 'signals:remove', scenario)
    assert.equal(events.filter((event) => event.startsWith('cleanup:')).length, 1, scenario)
  }

  const successEvents = []
  const success = await smokeContracts.executeP0SuiteLifecycle({
    options,
    operations: {
      installSignalHandlers() {
        successEvents.push('signals:install')
        return () => successEvents.push('signals:remove')
      },
      async prepare() { successEvents.push('prepare'); return { prepared: true } },
      async execute() { successEvents.push('execute'); return { executed: true } },
      async writeReport(_execution, _prepared, details) {
        successEvents.push('report')
        assert.deepEqual(details.cleanup, { status: 'CLEANED' })
        return { verdict: 'PASS' }
      },
      async cleanup() { successEvents.push('cleanup'); return { status: 'CLEANED' } }
    }
  })
  assert.deepEqual(success, {
    execution: { executed: true },
    report: { verdict: 'PASS' },
    cleanup: { status: 'CLEANED' }
  })
  assert.deepEqual(successEvents, [
    'signals:install',
    'prepare',
    'execute',
    'cleanup',
    'report',
    'signals:remove'
  ])

  const cleanupOnlyEvents = []
  const cleanupOnly = await smokeContracts.executeP0SuiteLifecycle({
    options: { ...options, phase: 'cleanup' },
    operations: {
      installSignalHandlers() {
        throw new Error('CLEANUP_ONLY_MUST_NOT_INSTALL_SIGNAL_HANDLERS')
      },
      async prepare() { cleanupOnlyEvents.push('prepare') },
      async execute() { cleanupOnlyEvents.push('execute') },
      async writeReport() { cleanupOnlyEvents.push('report') },
      async cleanup() {
        cleanupOnlyEvents.push('cleanup')
        return { status: 'CLEANED', alreadyCleaned: true }
      }
    }
  })
  assert.deepEqual(cleanupOnly, {
    execution: null,
    report: null,
    cleanup: { status: 'CLEANED', alreadyCleaned: true }
  })
  assert.deepEqual(cleanupOnlyEvents, ['cleanup'])
})

test('P0 entry connects ownership phases dispatch report and exactly once cleanup', async () => {
  assert.equal(typeof smokeContracts.runP0Suite, 'function')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-entry-wiring-a1'
  ])
  const ownerToken = 'entry-wiring-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const events = []
  let cleanupCalls = 0
  const dependencies = {
    installSignalHandlers() {
      events.push('signals:install')
      return () => events.push('signals:remove')
    },
    async initializeOwnership(receivedOptions, plan) {
      events.push(`ownership:${receivedOptions.runId}:${plan.verdict}`)
      return {
        runRoot: resolve(tmpdir(), receivedOptions.runId),
        options: receivedOptions,
        scriptPath: resolve(tmpdir(), 'entry-canonical.mjs'),
        ownerToken,
        ownerId,
        canonicalDatabase: 'fx_p0_user_e2e_entry_canonical_a1',
        inheritedEnv: {},
        caseResults: []
      }
    },
    phaseOperations: {
      async runPreflight() {
        events.push('preflight')
        return { id: 'AUTH-01', status: 'PASS' }
      },
      async assertRedisOwnership(_context, boundary) { events.push(`redis:${boundary}`) },
      async stopParentBackend() { events.push('parent-stop') },
      async assertBusinessPortsFree() { events.push('ports-free') },
      async runCanonicalChild() {
        events.push('canonical-child')
        return { status: 0, signal: null }
      },
      async verifyCanonicalChildCleanup() {
        events.push('canonical-cleanup')
        return { id: 'CAT-01', status: 'PASS' }
      },
      async runAuthority() {
        events.push('authority')
        return { id: 'AUTH-03', status: 'PASS' }
      },
      async writeReport(context, plan) {
        events.push('report-phase')
        return exactP0ReportPhaseEvidence(context, plan)
      }
    },
    async dispatchCase(definition) {
      events.push(`case:${definition.id}`)
      return { id: definition.id, status: 'TEST_SENTINEL' }
    },
    handlers: Object.create(null),
    async writeReport(execution, prepared, details) {
      events.push(`report:${prepared.caseResults.length}`)
      assert.deepEqual(details.cleanup, { status: 'CLEANED' })
      return { verdict: execution.plan.verdict }
    },
    async cleanup() {
      cleanupCalls += 1
      events.push('cleanup')
      return { status: 'CLEANED' }
    }
  }

  const completed = await smokeContracts.runP0Suite(options, dependencies)
  assert.equal(completed.execution.plan.fullMatrix, true)
  assert.deepEqual(completed.report, { verdict: 'PASS' })
  assert.deepEqual(completed.cleanup, { status: 'CLEANED' })
  assert.equal(cleanupCalls, 1)
  assert.equal(events.filter((event) => event === 'canonical-child').length, 1)
  assert.equal(events.filter((event) => event.startsWith('case:')).length, 60)
  assert.ok(events.indexOf('ownership:p0-entry-wiring-a1:PASS') < events.indexOf('preflight'))
  assert.ok(events.indexOf('preflight') < events.indexOf('canonical-child'))
  assert.ok(events.indexOf('canonical-cleanup') < events.indexOf('authority'))
  assert.ok(events.indexOf('authority') < events.indexOf('case:AUTH-01'))
  assert.ok(events.indexOf('case:UI-02') < events.indexOf('report-phase'))
  assert.ok(events.indexOf('report-phase') < events.indexOf('cleanup'))
  assert.ok(events.indexOf('cleanup') < events.indexOf('report:60'))
  assert.equal(events.at(-1), 'signals:remove')

  let failedCleanupCalls = 0
  await assert.rejects(
    smokeContracts.runP0Suite(options, {
      ...dependencies,
      installSignalHandlers() { return () => {} },
      async initializeOwnership() {
        return {
          runRoot: resolve(tmpdir(), options.runId),
          options,
          scriptPath: resolve(tmpdir(), 'entry-canonical.mjs'),
          ownerToken,
          ownerId,
          canonicalDatabase: 'fx_p0_user_e2e_entry_canonical_a1',
          inheritedEnv: {},
          caseResults: []
        }
      },
      dispatchCase: runCase,
      handlers: Object.create(null),
      async cleanup() {
        failedCleanupCalls += 1
        return { status: 'CLEANED' }
      }
    }),
    /INCOMPLETE_MATRIX: AUTH-01/
  )
  assert.equal(failedCleanupCalls, 1)
})

test('default P0 dependency factory wires local adapters and main injects it', async (t) => {
  assert.equal(typeof smokeContracts.createDefaultP0Dependencies, 'function')
  const smokeSource = readFileSync(
    fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
    'utf8'
  )
  assert.doesNotMatch(smokeSource, /P0_LOCAL_ADAPTER_REQUIRED|P0_SUITE_NOT_IMPLEMENTED/)
  assert.doesNotMatch(smokeSource, /\b(?:FLUSHDB|FLUSHALL)\b|request\(\['KEYS'/)
  assert.match(
    smokeSource,
    /gateOutput: join\(context\.runRoot, 'preflight', 'surefire-details\.json'\)/
  )

  const root = mkdtempSync(join(tmpdir(), 'p0-default-factory-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const events = []
  const runToken = 'default-factory-owner-token-a1'
  const redisKey = 'quote:BTCUSDT'
  const redisState = new Map([
    [redisKey, { value: 'before', expiresAtMs: 1_900_000_000_000 }]
  ])
  const redis = {
    async setNx(key, value) {
      events.push(`redis:setNx:${key}`)
      if (redisState.has(key)) return false
      redisState.set(key, { value, expiresAtMs: null })
      return true
    },
    async get(key) {
      events.push(`redis:get:${key}`)
      return redisState.get(key)?.value ?? null
    },
    async readExact(key) {
      events.push(`redis:read:${key}`)
      const entry = redisState.get(key)
      return entry
        ? { exists: true, value: entry.value, expiresAtMs: entry.expiresAtMs }
        : { exists: false, value: null, expiresAtMs: null }
    },
    async restoreExact(key, value, expiresAtMs) {
      events.push(`redis:restore:${key}:${expiresAtMs}`)
      redisState.set(key, { value, expiresAtMs })
    },
    async deleteExact(key) {
      events.push(`redis:delete:${key}`)
      redisState.delete(key)
    },
    async compareDelete(key, value) {
      events.push(`redis:compareDelete:${key}`)
      if (redisState.get(key)?.value !== value) return false
      redisState.delete(key)
      return true
    },
    async releaseOwnershipWithReceipt({ ownerKey, receiptKey, runToken: value, ownerId }) {
      events.push(`redis:releaseWithReceipt:${ownerKey}`)
      const owner = redisState.get(ownerKey)?.value ?? null
      const receipt = redisState.get(receiptKey)?.value ?? null
      if (owner === value && (receipt === null || receipt === ownerId)) {
        redisState.delete(ownerKey)
        redisState.set(receiptKey, { value: ownerId, expiresAtMs: null })
        return 'RELEASED'
      }
      return owner === null && receipt === ownerId ? 'ALREADY_RELEASED' : 'MISMATCH'
    },
    async removeCleanupReceipt({ receiptKey, ownerId }) {
      events.push(`redis:removeReceipt:${receiptKey}`)
      const receipt = redisState.get(receiptKey)?.value ?? null
      if (receipt === ownerId) {
        redisState.delete(receiptKey)
        return 'REMOVED'
      }
      return receipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
    }
  }
  const databases = new Map()
  const postgres = {
    async executeAdminSql(sql, { sensitive }) {
      events.push(`postgres:sql:${sensitive ? 'sensitive' : 'public'}`)
      let match = sql.match(/^CREATE DATABASE "([a-z0-9_]+)"$/)
      if (match) {
        databases.set(match[1], { ownerMarker: null, timezone: null })
        return
      }
      match = sql.match(/^COMMENT ON DATABASE "([a-z0-9_]+)" IS '(.+)'$/)
      if (match) {
        databases.get(match[1]).ownerMarker = match[2].replaceAll("''", "'")
        return
      }
      match = sql.match(/^ALTER DATABASE "([a-z0-9_]+)" SET timezone TO 'UTC'$/)
      if (match) {
        databases.get(match[1]).timezone = 'UTC'
        return
      }
      match = sql.match(/^DROP DATABASE "([a-z0-9_]+)" WITH \(FORCE\)$/)
      if (match) {
        databases.delete(match[1])
        return
      }
      throw new Error(`UNEXPECTED_SQL: ${sql}`)
    },
    async readDatabaseOwnership(segmentName) {
      events.push(`postgres:read:${segmentName}`)
      const database = databases.get(segmentName)
      return database
        ? { segmentName, ownerMarker: database.ownerMarker }
        : null
    },
    async probeDatabase({ segmentName }) {
      events.push(`postgres:probe:${segmentName}`)
      const database = databases.get(segmentName)
      return {
        currentDatabase: segmentName,
        ownerMarker: database?.ownerMarker,
        timezone: database?.timezone
      }
    }
  }
  const infrastructure = {
    async inspectDockerDaemon() {
      return {
        endpoint: process.platform === 'win32'
          ? 'npipe:////./pipe/docker_engine'
          : 'unix:///var/run/docker.sock'
      }
    },
    async verifyComposePort({ hostPort }) {
      events.push(`safety:compose:${hostPort}`)
      return true
    },
    async inspectListener({ port }) {
      events.push(`safety:listener:${port}`)
      return null
    },
    async verifyComposeContainers() {
      return {
        project: 'infra',
        postgres: { id: 'a'.repeat(64), image: 'postgres:16', host: '127.0.0.1', hostPort: 5432 },
        redis: { id: 'b'.repeat(64), image: 'redis:7', host: '127.0.0.1', hostPort: 6379 },
        credentials: { username: 'postgres', password: 'password' }
      }
    },
    async assertPortsFree(ports) {
      events.push(`ports-free:${ports.join(',')}`)
    }
  }
  const canonicalInvocations = []
  const processManager = {
    async startOwnedBackend({ environment }) {
      events.push(`backend:start:${environment.EXECUTION_MODE}`)
      return {
        pid: 5151,
        processIdentity: {
          pid: 5151,
          startedAt: 'fixture-process-5151',
          processFingerprint: `sha256:${'5'.repeat(64)}`
        }
      }
    },
    async waitForBackendHealth() { events.push('backend:health') },
    async waitForBusinessEndpoint() { events.push('backend:business') },
    async stopOwnedBackend(backend) { events.push(`backend:stop:${backend?.pid ?? 'none'}`) },
    async stopParentBackend() { events.push('backend:parent-stop') },
    async acquireNativeProcessHandle() {
      return {
        async inspectIdentity() { return null },
        async terminateTree() {
          throw new Error('TERMINATE_MUST_NOT_RUN_FOR_EXITED_FIXTURE_PROCESS')
        }
      }
    },
    async runCanonicalChild(invocation, { onSpawn } = {}) {
      events.push('canonical:child')
      canonicalInvocations.push(invocation)
      await onSpawn?.({
        pid: 5152,
        processIdentity: {
          pid: 5152,
          startedAt: 'fixture-process-5152',
          processFingerprint: `sha256:${'6'.repeat(64)}`
        }
      })
      redisState.set(redisKey, { value: 'during-canonical', expiresAtMs: null })
      mkdirSync(invocation.env.USDT_DEMO_SMOKE_ARTIFACTS, { recursive: true })
      writeFileSync(
        join(invocation.env.USDT_DEMO_SMOKE_ARTIFACTS, 'report.json'),
        `${JSON.stringify({
          status: 'PASS',
          runId: invocation.env.USDT_DEMO_SMOKE_RUN_ID,
          smokeDatabase: invocation.env.USDT_DEMO_SMOKE_DATABASE,
          sourceEvidence: [{ mode: 'LOCAL_SIMULATED', spot: {}, perp: {} }],
          results: [{ name: 'canonical', status: 'PASS' }],
          error: null
        })}\n`
      )
      return { status: 0, stdout: '{"status":"PASS"}', stderr: '' }
    }
  }
  const commandDescriptors = []
  let commandPid = 5160
  const runCommand = async (descriptor) => {
    events.push(`command:${descriptor.id}`)
    commandDescriptors.push(descriptor)
    if (typeof descriptor.onSpawn === 'function') {
      const pid = commandPid
      commandPid += 1
      await descriptor.onSpawn({
        pid,
        processIdentity: {
          pid,
          startedAt: `fixture-process-${pid}`,
          processFingerprint: `sha256:${'7'.repeat(64)}`
        }
      })
    }
    return { status: 0, stdout: '', stderr: '' }
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase: join(root, 'artifacts'),
    runToken: () => runToken,
    randomSuffix: (() => {
      const suffixes = ['abcdef123456', 'abcdef123457']
      return () => suffixes.shift()
    })(),
    now: () => '2026-07-15T03:04:05.678Z',
    inheritedEnv: {},
    redis,
    postgres,
    infrastructure,
    processManager: {
      ...processManager,
      async runCanonicalChild(...args) {
        return {
          ...await processManager.runCanonicalChild(...args),
          signal: null
        }
      }
    },
    runCommand,
    runAuthority: async () => ({ status: 'PASS', source: 'default-factory-fixture' }),
    writePhaseReport: async (context, plan) => exactP0ReportPhaseEvidence(context, plan),
    dispatchCase: async (definition) => ({
      id: definition.id,
      status: 'PASS',
      scopeComplete: true,
      subruns: definition.requiredSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    })
  })
  for (const name of [
    'installSignalHandlers',
    'initializeOwnership',
    'dispatchCase',
    'writeReport',
    'cleanup'
  ]) assert.equal(typeof dependencies[name], 'function', name)
  assert.equal(typeof dependencies.phaseOperations.runPreflight, 'function')
  assert.equal(typeof dependencies.phaseOperations.runCanonicalChild, 'function')

  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--run-id=p0-default-factory-a1'
  ])
  if (process.platform === 'win32') {
    await assert.rejects(
      smokeContracts.runP0Suite(options, dependencies),
      /P0_WINDOWS_JOB_OBJECT_REQUIRED/
    )
    assert.deepEqual(events, [])
    assert.equal(existsSync(join(root, 'artifacts')), false)
  } else {
    const completed = await smokeContracts.runP0Suite(options, dependencies)
    assert.equal(completed.report.verdict, 'PASS')
    assert.equal(completed.cleanup.status, 'CLEANED')
    assert.equal(completed.cleanup.restored, 10)
    assert.equal(canonicalInvocations.length, 1)
    assert.deepEqual(canonicalInvocations[0].args, [
      fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url))
    ])
    assert.equal(canonicalInvocations[0].shell, false)
    assert.equal(canonicalInvocations[0].env.P0_RUN_OWNER_TOKEN, runToken)
    assert.equal(JSON.stringify(canonicalInvocations[0].log).includes(runToken), false)
    assert.equal(commandDescriptors.find(({ id }) => id === 'compose-up').shell, false)
    assert.equal(commandDescriptors.some(({ args }) => args?.some((value) => value === runToken)), false)
    assert.equal(commandDescriptors.some(({ stdin }) => String(stdin ?? '').includes(runToken)), false)
    assert.equal(databases.size, 0)
    assert.deepEqual(redisState.get(redisKey), {
      value: 'before',
      expiresAtMs: 1_900_000_000_000
    })
    assert.equal(redisState.has('p0:e2e:owner'), false)
    const redisRecovery = JSON.parse(readFileSync(
      join(root, 'artifacts', options.runId, 'control', 'redis.json'),
      'utf8'
    ))
    assert.equal(redisRecovery.touchedKeys.length, 10)
    assert.equal(redisRecovery.touchedKeys.includes(redisKey), true)
    const cleaned = JSON.parse(readFileSync(
      join(root, 'artifacts', options.runId, 'control', 'ownership.json'),
      'utf8'
    ))
    assert.equal(cleaned.status, 'CLEANED')
    assert.ok(events.indexOf('safety:compose:5432') < events.indexOf('command:compose-up'))
    assert.ok(events.indexOf('command:compose-up') < events.indexOf('redis:setNx:p0:e2e:owner'))
    assert.ok(events.indexOf('redis:setNx:p0:e2e:owner') < events.indexOf('postgres:sql:public'))
    assert.ok(events.indexOf('command:surefire-gate') < events.indexOf('canonical:child'))
    assert.ok(events.indexOf('canonical:child') < events.indexOf('redis:releaseWithReceipt:p0:e2e:owner'))
  }

  const dependencySentinel = { sentinel: true }
  let forwarded
  const dispatch = smokeContracts.createSmokeMain({
    runCanonicalSmoke: async () => {},
    createP0Dependencies: () => dependencySentinel,
    runP0Suite: async (receivedOptions, receivedDependencies) => {
      forwarded = { receivedOptions, receivedDependencies }
      return { status: 'WIRED' }
    }
  })
  assert.deepEqual(await dispatch([
    '--suite=p0',
    '--run-id=p0-main-factory-a1'
  ]), { status: 'WIRED' })
  assert.equal(forwarded.receivedOptions.runId, 'p0-main-factory-a1')
  assert.equal(forwarded.receivedDependencies, dependencySentinel)
})

test('canonical child consumes parent database ownership and enforces marker cleanup', async () => {
  assert.equal(typeof smokeContracts.resolveCanonicalSmokeOwnership, 'function')
  assert.equal(typeof smokeContracts.prepareCanonicalSmokeDatabase, 'function')
  assert.equal(typeof smokeContracts.cleanupCanonicalSmokeDatabase, 'function')
  const runToken = 'canonical-runtime-owner-token-a1'
  const database = 'fx_p0_user_e2e_canonical_1_abcdef123456'
  const ownership = smokeContracts.resolveCanonicalSmokeOwnership({
    P0_RUN_OWNER_TOKEN: runToken,
    USDT_DEMO_SMOKE_DATABASE: database
  })
  assert.deepEqual(ownership, {
    database,
    ownerToken: runToken,
    ownerId: createHash('sha256').update(runToken).digest('hex'),
    inherited: true
  })
  assert.throws(
    () => smokeContracts.resolveCanonicalSmokeOwnership({ P0_RUN_OWNER_TOKEN: runToken }),
    /P0_CANONICAL_OWNERSHIP_INCOMPLETE/
  )

  const databases = new Map()
  const postgres = {
    async executeAdminSql(sql) {
      let match = sql.match(/^CREATE DATABASE "([a-z0-9_]+)"$/)
      if (match) {
        databases.set(match[1], { ownerMarker: null, timezone: null })
        return
      }
      match = sql.match(/^COMMENT ON DATABASE "([a-z0-9_]+)" IS '(.+)'$/)
      if (match) {
        databases.get(match[1]).ownerMarker = match[2].replaceAll("''", "'")
        return
      }
      match = sql.match(/^ALTER DATABASE "([a-z0-9_]+)" SET timezone TO 'UTC'$/)
      if (match) {
        databases.get(match[1]).timezone = 'UTC'
        return
      }
      match = sql.match(/^DROP DATABASE "([a-z0-9_]+)" WITH \(FORCE\)$/)
      if (match) {
        databases.delete(match[1])
        return
      }
      throw new Error(`UNEXPECTED_SQL: ${sql}`)
    },
    async readDatabaseOwnership(segmentName) {
      const state = databases.get(segmentName)
      return state ? { segmentName, ownerMarker: state.ownerMarker } : null
    },
    async probeDatabase({ segmentName }) {
      const state = databases.get(segmentName)
      return {
        currentDatabase: segmentName,
        ownerMarker: state?.ownerMarker,
        timezone: state?.timezone
      }
    }
  }
  await smokeContracts.prepareCanonicalSmokeDatabase({ ownership, postgres })
  assert.deepEqual(databases.get(database), {
    ownerMarker: `p0-owner:${runToken}`,
    timezone: 'UTC'
  })
  databases.get(database).ownerMarker = 'p0-owner:foreign-owner-token-a1'
  await assert.rejects(
    smokeContracts.cleanupCanonicalSmokeDatabase({ ownership, postgres }),
    /P0_DATABASE_OWNER_MISMATCH/
  )
  assert.equal(databases.has(database), true)
  databases.get(database).ownerMarker = `p0-owner:${runToken}`
  await smokeContracts.cleanupCanonicalSmokeDatabase({ ownership, postgres })
  assert.equal(databases.has(database), false)
})

const persistenceDigest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

test('case evidence replaces atomically without leaving partial files', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-artifact-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'nested', 'result.json')

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'PASS' })
  assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), {
    id: 'AUTH-01',
    status: 'PASS'
  })
  assert.equal(existsSync(`${resultPath}.tmp`), false)

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'FAIL' })
  assert.equal(JSON.parse(readFileSync(resultPath, 'utf8')).status, 'FAIL')
  assert.throws(() => writeCaseResultAtomic(resultPath, { unsupported: 1n }), TypeError)
  assert.equal(JSON.parse(readFileSync(resultPath, 'utf8')).status, 'FAIL')
  assert.equal(existsSync(`${resultPath}.tmp`), false)
})

test('persistence rejects unsupported root evidence atomically', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-unsupported-root-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const baseline = '{"id":"AUTH-01","status":"PASS"}\n'
  writeFileSync(resultPath, baseline)

  for (const [scenario, value] of [
    ['undefined', undefined],
    ['function', () => {}],
    ['symbol', Symbol('root')],
    ['array', ['unsupported-root']]
  ]) {
    assert.throws(
      () => writeCaseResultAtomic(resultPath, value),
      TypeError,
      scenario
    )
    assert.equal(readFileSync(resultPath, 'utf8'), baseline, scenario)
    assert.deepEqual(readdirSync(directory), ['result.json'], scenario)
  }

  const ordinaryPath = join(directory, 'ordinary.json')
  writeCaseResultAtomic(ordinaryPath, {
    id: 'AUTH-02',
    nested: { kept: 'safe', discarded: () => {} },
    values: ['safe', undefined, Symbol('nested')]
  })
  assert.deepEqual(JSON.parse(readFileSync(ordinaryPath, 'utf8')), {
    id: 'AUTH-02',
    nested: { [persistenceDigest('kept')]: persistenceDigest('safe') },
    values: [persistenceDigest('safe')]
  })
})

test('atomic writer uses a unique adjacent temp without touching a foreign fixed temp', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-unique-temp-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const foreignTempPath = `${resultPath}.tmp`
  const foreignMarker = 'FOREIGN_TEMP_OWNER_4f8c'
  writeFileSync(resultPath, '{"status":"OLD"}\n')
  writeFileSync(foreignTempPath, foreignMarker)

  writeCaseResultAtomic(resultPath, { id: 'AUTH-01', status: 'PASS' })

  assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), {
    id: 'AUTH-01',
    status: 'PASS'
  })
  assert.equal(existsSync(foreignTempPath), true)
  assert.equal(readFileSync(foreignTempPath, 'utf8'), foreignMarker)
  assert.deepEqual(readdirSync(directory).toSorted(), ['result.json', 'result.json.tmp'])
})

test('persistence serialization ignores toJSON hooks and non-JSON values', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-inert-json-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const rootPath = join(directory, 'root.json')
  const nestedPath = join(directory, 'nested.json')
  const rootMarker = 'ROOT_TO_JSON_BYPASS_1c42'
  const nestedMarker = 'NESTED_TO_JSON_BYPASS_7e31'
  let callbackCalls = 0

  writeCaseResultAtomic(rootPath, {
    id: 'AUTH-01',
    safe: 'root-safe',
    toJSON() {
      callbackCalls += 1
      return {
        rawRequest: { method: 'POST', url: `/api/orders/${rootMarker}`, body: rootMarker },
        password: rootMarker
      }
    }
  })
  writeCaseResultAtomic(nestedPath, {
    id: 'AUTH-02',
    safe: 'outer-safe',
    nested: {
      safe: 'inner-safe',
      toJSON() {
        callbackCalls += 1
        return {
          rawRequest: {
            method: 'POST',
            url: `/api/orders/${nestedMarker}`,
            body: nestedMarker
          },
          accessToken: nestedMarker
        }
      },
      functionValue() {},
      symbolValue: Symbol('nested-symbol'),
      undefinedValue: undefined
    },
    values: [
      'kept',
      () => {},
      Symbol('array-symbol'),
      undefined,
      { safe: 'deep-safe', functionValue() {}, symbolValue: Symbol('deep-symbol') }
    ],
    functionValue() {},
    symbolValue: Symbol('root-symbol'),
    undefinedValue: undefined
  })

  const rootSource = readFileSync(rootPath, 'utf8')
  const nestedSource = readFileSync(nestedPath, 'utf8')
  assert.equal(callbackCalls, 0)
  for (const source of [rootSource, nestedSource]) {
    assert.equal(source.includes(rootMarker), false)
    assert.equal(source.includes(nestedMarker), false)
    assert.equal(source.includes('toJSON'), false)
  }
  assert.deepEqual(JSON.parse(rootSource), {
    id: 'AUTH-01',
    [persistenceDigest('safe')]: persistenceDigest('root-safe')
  })
  assert.deepEqual(JSON.parse(nestedSource), {
    id: 'AUTH-02',
    [persistenceDigest('safe')]: persistenceDigest('outer-safe'),
    nested: { [persistenceDigest('safe')]: persistenceDigest('inner-safe') },
    values: [
      persistenceDigest('kept'),
      { [persistenceDigest('safe')]: persistenceDigest('deep-safe') }
    ]
  })
})

test('own-data serialization ignores inherited serialization methods', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-inherited-json-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href

  for (const prototypeName of ['object', 'array']) {
    const execution = spawnSync(process.execPath, [
      '--input-type=module',
      '--eval',
      `
        import assert from 'node:assert/strict'
        import { readFileSync, writeFileSync } from 'node:fs'

        const artifacts = await import(${JSON.stringify(artifactsUrl)})
        const cases = await import(${JSON.stringify(casesUrl)})
        const prototype = ${JSON.stringify(prototypeName)} === 'array'
          ? Array.prototype
          : Object.prototype
        const marker = 'INHERITED_SERIALIZATION_SECRET_${prototypeName}'
        const resultPath = ${JSON.stringify(join(directory, `${prototypeName}-result.json`))}
        const cliPath = ${JSON.stringify(join(directory, `${prototypeName}-cli.json`))}
        let callbackCalls = 0
        let result
        let cliResult
        let fingerprint

        Object.defineProperty(prototype, 'toJSON', {
          configurable: true,
          value() {
            callbackCalls += 1
            return { status: 'PASS', marker }
          }
        })
        try {
          artifacts.writeCaseResultAtomic(resultPath, {
            id: 'AUTH-01',
            status: 'FAIL',
            nested: {
              status: 'FAIL',
              values: [{ status: 'FAIL' }]
            }
          })
          result = JSON.parse(readFileSync(resultPath, 'utf8'))
          fingerprint = cases.registryFingerprint(cases.P0_CASES)

          writeFileSync(cliPath, '{"status":"PASS"}\\n')
          process.argv = [
            process.execPath,
            ${JSON.stringify(artifactsScript)},
            'verify-surefire',
            '--output=' + cliPath,
            '--unknown=token=' + marker
          ]
          await import(${JSON.stringify(artifactsUrl)} + '?inherited=' + ${JSON.stringify(prototypeName)})
          process.exitCode = 0
          cliResult = JSON.parse(readFileSync(cliPath, 'utf8'))
        } finally {
          delete prototype.toJSON
        }

        assert.deepEqual(result, {
          id: 'AUTH-01',
          status: 'FAIL',
          nested: {
            status: 'FAIL',
            values: [{ status: 'FAIL' }]
          }
        })
        assert.deepEqual(cliResult, { status: 'FAIL', error: 'CLI_UNKNOWN_OPTION' })
        assert.equal(readFileSync(resultPath, 'utf8').includes(marker), false)
        assert.equal(readFileSync(cliPath, 'utf8').includes(marker), false)
        assert.equal(fingerprint, cases.P0_REGISTRY_FINGERPRINT)
        assert.equal(callbackCalls, 0)
      `
    ], { encoding: 'utf8' })

    assert.equal(execution.status, 0, `${prototypeName}: ${execution.stderr}`)
  }
})

test('sanitizer intermediates ignore inherited prototype behavior', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-sanitizer-prototype-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href

  for (const scenario of [
    'object-toJSON',
    'array-toJSON',
    'name-getter-value-setter',
    'outcome-setter'
  ]) {
    const execution = spawnSync(process.execPath, [
      '--input-type=module',
      '--eval',
      `
        import assert from 'node:assert/strict'
        import { readFileSync } from 'node:fs'

        const artifacts = await import(${JSON.stringify(artifactsUrl)})
        const scenario = ${JSON.stringify(scenario)}
        const marker = 'SANITIZER_PROTOTYPE_SECRET_' + scenario
        const resultPath = ${JSON.stringify(join(directory, `${scenario}-result.json`))}
        const rawBody = JSON.stringify({
          status: 'FAIL',
          values: [{ status: 'FAIL', password: marker }]
        })
        const input = {
          id: 'AUTH-01',
          status: 'FAIL',
          body: rawBody,
          responseBody: rawBody,
          replayProbes: [{
            id: 'AUTH-01',
            status: 'FAIL',
            outcome: { status: 'FAIL', errorCode: 'REJECTED' }
          }]
        }
        const original = structuredClone(input)
        let callbackCalls = 0
        let publicResult
        let persistedSource

        if (scenario === 'object-toJSON') {
          Object.defineProperty(Object.prototype, 'toJSON', {
            configurable: true,
            value() {
              callbackCalls += 1
              return { status: 'PASS', marker }
            }
          })
        } else if (scenario === 'array-toJSON') {
          Object.defineProperty(Array.prototype, 'toJSON', {
            configurable: true,
            value() {
              callbackCalls += 1
              return [{ status: 'PASS', marker }]
            }
          })
        } else if (scenario === 'name-getter-value-setter') {
          Object.defineProperty(Object.prototype, 'name', {
            configurable: true,
            get() {
              callbackCalls += 1
              return 'Authorization'
            }
          })
          Object.defineProperty(Object.prototype, 'value', {
            configurable: true,
            set() {
              callbackCalls += 1
              Object.defineProperties(this, {
                status: {
                  value: 'PASS',
                  enumerable: true,
                  configurable: true,
                  writable: true
                },
                marker: {
                  value: marker,
                  enumerable: true,
                  configurable: true,
                  writable: true
                }
              })
            }
          })
        } else {
          Object.defineProperty(Object.prototype, 'outcome', {
            configurable: true,
            set() {
              callbackCalls += 1
              Object.defineProperties(this, {
                status: {
                  value: 'PASS',
                  enumerable: true,
                  configurable: true,
                  writable: true
                },
                marker: {
                  value: marker,
                  enumerable: true,
                  configurable: true,
                  writable: true
                }
              })
            }
          })
        }

        try {
          publicResult = artifacts.redactNetworkEntry(input)
          artifacts.writeCaseResultAtomic(resultPath, input)
          persistedSource = readFileSync(resultPath, 'utf8')
        } finally {
          delete Object.prototype.toJSON
          delete Array.prototype.toJSON
          delete Object.prototype.name
          delete Object.prototype.value
          delete Object.prototype.outcome
        }

        const persistedResult = JSON.parse(persistedSource)
        const publicSnapshot = JSON.parse(JSON.stringify(publicResult))
        assert.deepEqual(input, original, 'sanitization must not mutate its input')
        assert.equal(callbackCalls, 0, 'inherited prototype callback executed')
        assert.equal(Object.hasOwn(publicResult, 'status'), true)
        assert.equal(publicResult.status, 'FAIL')
        assert.equal(Object.hasOwn(persistedResult, 'status'), true)
        assert.equal(persistedResult.status, 'FAIL')
        assert.deepEqual(persistedResult, publicSnapshot, 'public and persisted evidence diverged')

        for (const result of [publicSnapshot, persistedResult]) {
          const requestBody = JSON.parse(result.body)
          const responseBody = JSON.parse(result.responseBody)
          assert.equal(requestBody.status, 'FAIL')
          assert.equal(Array.isArray(requestBody.values), true)
          assert.equal(requestBody.values[0].status, 'FAIL')
          assert.equal(responseBody.status, 'FAIL')
          assert.equal(Array.isArray(responseBody.values), true)
          assert.equal(responseBody.values[0].status, 'FAIL')
          assert.equal(Object.hasOwn(result.replayProbes[0], 'status'), true)
          assert.equal(result.replayProbes[0].status, 'FAIL')
          assert.equal(Object.hasOwn(result.replayProbes[0], 'outcome'), true)
          assert.equal(result.replayProbes[0].outcome.status, 'FAIL')
          assert.equal(JSON.stringify(result).includes(marker), false)
        }
        assert.equal(persistedSource.includes(marker), false)
      `
    ], { encoding: 'utf8' })

    assert.equal(
      execution.status,
      0,
      `${scenario} child failed\nstdout:\n${execution.stdout}\nstderr:\n${execution.stderr}`
    )
  }
})

for (const scenario of [
  'root-toJSON-getter',
  'nested-toJSON-getter',
  'root-ordinary-getter',
  'nested-ordinary-getter',
  'mutating-getter'
]) {
  test(`persistence input safety rejects accessors without executing: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-accessor-input-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    const marker = `FORBIDDEN_ACCESSOR_${scenario}`
    let calls = 0
    let input

    if (scenario === 'mutating-getter') {
      input = { id: 'AUTH-01' }
      Object.defineProperty(input, 'mutator', {
        enumerable: true,
        get() {
          calls += 1
          input.later = marker
          return 'safe'
        }
      })
      input.later = 'unchanged'
    } else {
      const nested = scenario.startsWith('nested')
      const target = {}
      Object.defineProperty(target, scenario.includes('toJSON') ? 'toJSON' : 'evidence', {
        enumerable: true,
        get() {
          calls += 1
          return marker
        }
      })
      input = nested ? { id: 'AUTH-01', nested: target } : { id: 'AUTH-01' }
      if (!nested) Object.defineProperties(input, Object.getOwnPropertyDescriptors(target))
    }

    writeCaseResultAtomic(resultPath, baseline)
    let failure
    try {
      writeCaseResultAtomic(resultPath, input)
    } catch (error) {
      failure = error
    }

    assert.equal(calls, 0)
    assert.ok(failure instanceof TypeError)
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
    assert.equal(readFileSync(resultPath, 'utf8').includes(marker), false)
    if (scenario === 'mutating-getter') assert.equal(input.later, 'unchanged')
  })
}

for (const scenario of ['root-proxy', 'nested-proxy']) {
  test(`persistence input safety rejects proxies without executing traps: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-proxy-input-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    let traps = 0
    const proxy = new Proxy({ evidence: 'safe' }, {
      get(target, key, receiver) {
        traps += 1
        return Reflect.get(target, key, receiver)
      },
      getOwnPropertyDescriptor(target, key) {
        traps += 1
        return Reflect.getOwnPropertyDescriptor(target, key)
      },
      getPrototypeOf(target) {
        traps += 1
        return Reflect.getPrototypeOf(target)
      },
      ownKeys(target) {
        traps += 1
        return Reflect.ownKeys(target)
      }
    })
    const input = scenario === 'root-proxy'
      ? proxy
      : { id: 'AUTH-01', nested: proxy }

    writeCaseResultAtomic(resultPath, baseline)
    let failure
    try {
      writeCaseResultAtomic(resultPath, input)
    } catch (error) {
      failure = error
    }

    assert.equal(traps, 0)
    assert.ok(failure instanceof TypeError)
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
  })
}

for (const [scenario, value] of [
  ['NaN', Number.NaN],
  ['positive-Infinity', Number.POSITIVE_INFINITY],
  ['negative-Infinity', Number.NEGATIVE_INFINITY]
]) {
  test(`persistence input safety rejects non-finite numbers atomically: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-non-finite-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const resultPath = join(directory, 'result.json')
    const baseline = {
      id: 'AUTH-01',
      status: 'FAIL',
      reason: persistenceDigest('baseline')
    }
    writeCaseResultAtomic(resultPath, baseline)

    assert.throws(
      () => writeCaseResultAtomic(resultPath, { id: 'AUTH-01', metrics: { value } }),
      TypeError
    )
    assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), baseline)
  })
}

test('network evidence redacts secrets recursively without mutating live replay data', () => {
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const entry = {
    url: '/api/orders?access_token=query-secret&symbol=BTCUSDT',
    headers: {
      Authorization: 'Bearer header-secret',
      Cookie: 'session=cookie-secret',
      'X-Trace-Id': 'safe-trace'
    },
    postData: JSON.stringify({
      password: 'body-secret',
      nested: { refreshToken: 'refresh-secret', amount: '10' }
    }),
    responseHeaders: [
      { name: 'Set-Cookie', value: 'session=response-secret' },
      { name: 'Content-Type', value: 'application/json' }
    ]
  }

  const redacted = redactNetworkEntry(entry)
  assert.notEqual(redacted, entry)
  assert.equal(entry.headers.Authorization, 'Bearer header-secret')
  assert.equal(redacted.headers.Authorization, '[REDACTED]')
  assert.equal(redacted.headers.Cookie, '[REDACTED]')
  assert.equal(redacted.headers['X-Trace-Id'], digest('safe-trace'))
  assert.equal(new URL(redacted.url, 'https://contract.invalid').searchParams.get('access_token'), '[REDACTED]')
  assert.equal(new URL(redacted.url, 'https://contract.invalid').searchParams.get('symbol'), 'BTCUSDT')
  assert.deepEqual(JSON.parse(redacted.postData), {
    password: '[REDACTED]',
    nested: { refreshToken: '[REDACTED]', amount: '10' }
  })
  assert.equal(redacted.responseHeaders[0].value, '[REDACTED]')
  assert.equal(redacted.responseHeaders[1].value, 'application/json')

  const form = redactNetworkEntry({
    url: 'https://example.invalid/login?token=url-secret&next=%2Fwallet',
    body: 'password=form-secret&email=user%40example.com',
    postData: JSON.stringify({ symbol: 'ETHUSDT', status: 'ACCEPTED' })
  })
  assert.equal(new URL(form.url).searchParams.get('token'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).get('password'), '[REDACTED]')
  assert.equal(new URLSearchParams(form.body).has('email'), false)
  assert.deepEqual(JSON.parse(form.postData), { symbol: 'ETHUSDT', status: 'ACCEPTED' })

  const plainText = redactNetworkEntry({
    body: 'opaque credential words',
    postData: JSON.stringify({ symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' })
  })
  assert.equal('body' in plainText, false)
  assert.deepEqual(JSON.parse(plainText.postData), {
    symbol: 'SOLUSDT-PERP',
    sourceMode: 'LOCAL_SIMULATED'
  })
})

test('public network redaction matches persisted positive network schema', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-public-network-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    query: 'REVIEW14_QUERY_SECRET_4d1a',
    authorization: 'REVIEW14_HEADER_SECRET_5e2b',
    body: 'REVIEW14_BODY_SECRET_6f3c',
    rawRequest: 'REVIEW14_RAW_REQUEST_SECRET_704d',
    nestedRaw: 'REVIEW14_NESTED_RAW_SECRET_815e',
    unknown: 'REVIEW14_UNKNOWN_NETWORK_SECRET_926f',
    response: 'REVIEW14_RESPONSE_SECRET_a370',
    responseHeader: 'REVIEW14_RESPONSE_HEADER_SECRET_b481'
  }
  const correlation = 'review14-safe-correlation'
  const requestRef = 'review14-request-reference'
  let callableCalls = 0
  const baseEntry = {
    method: 'POST',
    url: `https://example.invalid/api/orders?symbol=BTCUSDT&token=${markers.query}`,
    status: 201,
    requestRef,
    mimeType: 'application/json',
    headers: {
      Authorization: `Bearer ${markers.authorization}`,
      'X-Trace-Id': correlation
    },
    body: `password=${markers.body}&symbol=BTCUSDT`,
    rawRequest: {
      method: 'POST',
      url: `/api/orders/${markers.rawRequest}`,
      body: markers.rawRequest
    },
    requestData: markers.rawRequest,
    unknownNetworkField: markers.unknown,
    response: {
      status: 202,
      data: markers.response,
      rawRequest: { body: markers.nestedRaw }
    },
    responseHeaders: [
      ['Content-Type', 'application/json'],
      ['Authorization', `Bearer ${markers.responseHeader}`]
    ]
  }
  const entry = structuredClone(baseEntry)
  const original = structuredClone(baseEntry)
  const ignoredCallback = () => {
    callableCalls += 1
    return markers.unknown
  }
  entry.ignoredCallback = ignoredCallback
  original.ignoredCallback = ignoredCallback

  const direct = redactNetworkEntry(entry)
  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    status: 'PASS',
    networkEvidence: [entry]
  })
  const persisted = JSON.parse(readFileSync(resultPath, 'utf8')).networkEvidence[0]

  assert.equal(callableCalls, 0)
  assert.deepEqual(entry, original)
  assert.deepEqual(direct, persisted)
  assert.equal(direct.method, 'POST')
  assert.equal(direct.status, 201)
  assert.equal(direct.requestRef, persistenceDigest(requestRef))
  assert.equal(direct.mimeType, 'application/json')
  const url = new URL(direct.url)
  assert.equal(url.searchParams.get('symbol'), 'BTCUSDT')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  assert.deepEqual(direct.headers, {
    Authorization: '[REDACTED]',
    'X-Trace-Id': persistenceDigest(correlation)
  })
  assert.deepEqual(direct.response, { status: 202 })
  assert.deepEqual(direct.responseHeaders, [
    ['Content-Type', 'application/json'],
    ['Authorization', '[REDACTED]']
  ])
  for (const field of [
    'body',
    'rawRequest',
    'requestData',
    'unknownNetworkField',
    'ignoredCallback'
  ]) {
    assert.equal(Object.hasOwn(direct, field), false, field)
  }
  const directSource = JSON.stringify(direct)
  const persistedSource = JSON.stringify(persisted)
  for (const marker of Object.values(markers)) {
    assert.equal(directSource.includes(marker), false, `direct ${marker}`)
    assert.equal(persistedSource.includes(marker), false, `persisted ${marker}`)
  }

  let accessorCalls = 0
  const accessorEntry = structuredClone(baseEntry)
  Object.defineProperty(accessorEntry, 'unknownEvidence', {
    enumerable: true,
    get() {
      accessorCalls += 1
      return markers.unknown
    }
  })
  assert.throws(
    () => redactNetworkEntry(accessorEntry),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.throws(
    () => writeCaseResultAtomic(join(directory, 'accessor.json'), {
      id: 'AUTH-01',
      networkEvidence: [accessorEntry]
    }),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(accessorCalls, 0)

  let proxyTrapCalls = 0
  const proxyEntry = new Proxy(structuredClone(baseEntry), {
    get(target, field, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, field, receiver)
    }
  })
  assert.throws(
    () => redactNetworkEntry(proxyEntry),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: Proxy$/
  )
  assert.throws(
    () => writeCaseResultAtomic(join(directory, 'proxy.json'), {
      id: 'AUTH-01',
      networkEvidence: [proxyEntry]
    }),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: Proxy$/
  )
  assert.equal(proxyTrapCalls, 0)

  assert.deepEqual(redactNetworkEntry({ id: 'AUTH-01', status: 'PASS' }), {
    id: 'AUTH-01',
    status: 'PASS'
  })
})

test('public no-detail network entries use persisted positive schema', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-public-network-no-detail-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    query: 'REVIEW15_NO_DETAIL_QUERY_SECRET_1a2b',
    requestRef: 'REVIEW15_NO_DETAIL_REFERENCE_2b3c',
    response: 'REVIEW15_NO_DETAIL_RESPONSE_SECRET_3c4d',
    unknown: 'REVIEW15_NO_DETAIL_UNKNOWN_SECRET_4d5e',
    callback: 'REVIEW15_NO_DETAIL_CALLBACK_SECRET_5e6f'
  }
  let callbackCalls = 0
  const ignoredCallback = () => {
    callbackCalls += 1
    return markers.callback
  }
  const entry = {
    method: 'GET',
    url: `https://example.invalid/api/orders?symbol=ETHUSDT&token=${markers.query}`,
    status: 204,
    requestRef: markers.requestRef,
    response: markers.response,
    unknownNetworkField: markers.unknown,
    ignoredCallback
  }
  const original = { ...entry }

  const direct = redactNetworkEntry(entry)
  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    status: 'PASS',
    networkEvidence: [entry]
  })
  const persisted = JSON.parse(readFileSync(resultPath, 'utf8')).networkEvidence[0]

  assert.equal(callbackCalls, 0)
  assert.deepEqual(entry, original)
  assert.deepEqual(direct, persisted)
  assert.equal(direct.method, 'GET')
  assert.equal(direct.status, 204)
  assert.equal(direct.requestRef, persistenceDigest(markers.requestRef))
  const url = new URL(direct.url)
  assert.equal(url.searchParams.get('symbol'), 'ETHUSDT')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  for (const field of ['response', 'unknownNetworkField', 'ignoredCallback']) {
    assert.equal(Object.hasOwn(direct, field), false, `direct ${field}`)
    assert.equal(Object.hasOwn(persisted, field), false, `persisted ${field}`)
  }
  const directSource = JSON.stringify(direct)
  const persistedSource = JSON.stringify(persisted)
  for (const marker of Object.values(markers)) {
    assert.equal(directSource.includes(marker), false, `direct ${marker}`)
    assert.equal(persistedSource.includes(marker), false, `persisted ${marker}`)
  }
  assert.deepEqual(redactNetworkEntry({ id: 'AUTH-02', status: 'FAIL' }), {
    id: 'AUTH-02',
    status: 'FAIL'
  })
})

test('public network routing is independent of method output allowlist', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-public-network-method-routing-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    query: 'REVIEW16_METHOD_QUERY_SECRET_1a2b',
    requestRef: 'REVIEW16_METHOD_REFERENCE_2b3c',
    response: 'REVIEW16_METHOD_RESPONSE_SECRET_3c4d',
    unknown: 'REVIEW16_METHOD_UNKNOWN_SECRET_4d5e',
    callback: 'REVIEW16_METHOD_CALLBACK_SECRET_5e6f'
  }
  let callbackCalls = 0
  const ignoredCallback = () => {
    callbackCalls += 1
    return markers.callback
  }
  const entry = {
    method: 'PROPFIND',
    url: `https://example.invalid/api/orders?symbol=SOLUSDT&token=${markers.query}`,
    status: 207,
    requestRef: markers.requestRef,
    response: markers.response,
    unknownNetworkField: markers.unknown,
    ignoredCallback
  }
  const original = { ...entry }

  const direct = redactNetworkEntry(entry)
  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    status: 'PASS',
    networkEvidence: [entry]
  })
  const persisted = JSON.parse(readFileSync(resultPath, 'utf8')).networkEvidence[0]

  assert.equal(callbackCalls, 0)
  assert.deepEqual(entry, original)
  assert.deepEqual(direct, persisted)
  assert.equal(Object.hasOwn(direct, 'method'), false)
  assert.equal(direct.status, 207)
  assert.equal(direct.requestRef, persistenceDigest(markers.requestRef))
  const url = new URL(direct.url)
  assert.equal(url.searchParams.get('symbol'), 'SOLUSDT')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  for (const field of ['response', 'unknownNetworkField', 'ignoredCallback']) {
    assert.equal(Object.hasOwn(direct, field), false, `direct ${field}`)
    assert.equal(Object.hasOwn(persisted, field), false, `persisted ${field}`)
  }
  const directSource = JSON.stringify(direct)
  const persistedSource = JSON.stringify(persisted)
  for (const marker of Object.values(markers)) {
    assert.equal(directSource.includes(marker), false, `direct ${marker}`)
    assert.equal(persistedSource.includes(marker), false, `persisted ${marker}`)
  }
})

test('public and persistence share a context-aware safe evidence boundary', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-context-safe-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    nested: 'ITEM2_NESTED_CREDENTIAL_7b1a',
    array: 'ITEM2_ARRAY_CREDENTIAL_2c2b',
    failure: 'ITEM2_FAILURE_CREDENTIAL_3d3c',
    blocker: 'ITEM2_BLOCKER_CREDENTIAL_4e4d',
    checkpoint: 'ITEM2_CHECKPOINT_CREDENTIAL_5f5e',
    dynamicKey: 'ITEM2_DYNAMIC_KEY_CREDENTIAL_6a6f',
    rawEndpoint: 'ITEM2_RAW_ENDPOINT_CREDENTIAL_7b70',
    rawData: 'ITEM2_RAW_DATA_CREDENTIAL_8c81',
    rawResponse: 'ITEM2_RAW_RESPONSE_CREDENTIAL_9d92',
    urlUserinfo: 'ITEM2_URL_USERINFO_CREDENTIAL_ae03',
    urlQuery: 'ITEM2_URL_QUERY_CREDENTIAL_bf14',
    urlBody: 'ITEM2_URL_BODY_CREDENTIAL_c025',
    bearer: 'ITEM2_BEARER_CREDENTIAL_d136',
    cookie: 'ITEM2_COOKIE_CREDENTIAL_e247',
    session: 'ITEM2_SESSION_CREDENTIAL_f358',
    password: 'ITEM2_PASSWORD_CREDENTIAL_0469',
    token: 'ITEM2_TOKEN_CREDENTIAL_157a',
    jwt: 'eyJhbGciOiJIUzI1NiJ9.SVRFTTJfSldUX0NSRURFTlRJQUxfMjY4Yg.signature268b',
    base64: 'SVRFTTJfQkFTRTY0X0NSRURFTlRJQUxfMzc5Yw==',
    aws: 'AKIAITEM2CRED48AD90E',
    opaque: 'mQ9ITEM2opaqueCredential59be+/='
  }
  const usefulOpaque = 'ordinary-correlation-item2-6acf'
  const expectedOpaque = `sha256:${createHash('sha256').update(usefulOpaque).digest('hex')}`
  const evidence = {
    id: 'AUTH-01',
    status: 'PASS',
    method: 'GET',
    symbol: 'BTCUSDT',
    flag: true,
    count: 7,
    empty: null,
    note: usefulOpaque,
    ordinary: {
      nestedNote: `Bearer ${markers.nested}`,
      list: [`token=${markers.array}`]
    },
    failure: { message: `password=${markers.failure}` },
    blocker: { message: `session=${markers.blocker}` },
    checkpoint: { message: `Cookie: session=${markers.checkpoint}` },
    [`password-${markers.dynamicKey}`]: 'redact-me',
    rawRequestAlias: {
      method: 'POST',
      endpoint: `/api/${markers.rawEndpoint}`,
      data: { session: `session=${markers.rawData}` },
      response: { note: `Bearer ${markers.rawResponse}` }
    },
    url: {
      userinfo: `password=${markers.urlUserinfo}`,
      query: { token: markers.urlQuery },
      body: [`Bearer ${markers.urlBody}`]
    },
    credentialSamples: [
      `Bearer ${markers.bearer}`,
      `Cookie: session=${markers.cookie}`,
      `session=${markers.session}`,
      `password=${markers.password}`,
      `token=${markers.token}`,
      markers.jwt,
      markers.base64,
      markers.aws,
      markers.opaque
    ]
  }
  const original = structuredClone(evidence)

  const redacted = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)

  assert.deepEqual(evidence, original)
  const publicSource = JSON.stringify(redacted)
  const persistedSource = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) {
    assert.equal(publicSource.includes(marker), false, `public ${marker}`)
    assert.equal(persistedSource.includes(marker), false, `persisted ${marker}`)
  }
  const persisted = JSON.parse(persistedSource)
  for (const safe of [redacted, persisted]) {
    assert.equal(safe.id, 'AUTH-01')
    assert.equal(safe.status, 'PASS')
    assert.equal(safe.method, 'GET')
    assert.equal(safe.symbol, 'BTCUSDT')
    assert.equal(safe.flag, true)
    assert.equal(safe.count, 7)
    assert.equal(safe.empty, null)
    assert.equal(safe.note, expectedOpaque)
    assert.equal('url' in safe, false)
    assert.equal('rawRequestAlias' in safe, false)
  }
})

test('dynamic credential-shaped object keys never survive public or persisted evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-dynamic-key-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const credentialKeys = {
    aws: 'AKIAABCDEFGHIJKLMNOP',
    jwt: 'eyJhbGciOiJIUzI1NiJ9.REVIEW10PAYLOAD.REVIEW10SIGNATURE',
    base64: 'SVRFTTJfQkFTRTY0X0NSRURFTlRJQUxfMzc5Yw==',
    bearer: 'Bearer REVIEW10_DYNAMIC_KEY_CREDENTIAL',
    cookie: 'Cookie: session=REVIEW10_DYNAMIC_KEY_CREDENTIAL',
    sensitive: 'password-REVIEW10_DYNAMIC_KEY_CREDENTIAL'
  }
  const evidence = {
    id: 'AUTH-01',
    status: 'PASS',
    selection: {
      caseIds: ['AUTH-01'],
      [credentialKeys.base64]: true
    },
    cases: {
      'AUTH-01': {
        status: 'PASS',
        subruns: [{
          id: 'desktop-ui-core',
          profile: 'UI_CORE',
          viewport: 'desktop',
          status: 'PASS',
          [credentialKeys.jwt]: true,
          outcome: { [credentialKeys.cookie]: true }
        }]
      },
      [credentialKeys.aws]: { status: 'PASS' }
    },
    counts: {
      PASS: 1,
      [credentialKeys.bearer]: true
    },
    [credentialKeys.sensitive]: true
  }
  const original = structuredClone(evidence)

  const redacted = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)

  assert.deepEqual(evidence, original)
  const publicSource = JSON.stringify(redacted)
  const persistedSource = readFileSync(resultPath, 'utf8')
  for (const credentialKey of Object.values(credentialKeys)) {
    assert.equal(publicSource.includes(credentialKey), false, `public ${credentialKey}`)
    assert.equal(persistedSource.includes(credentialKey), false, `persisted ${credentialKey}`)
  }
  for (const safe of [redacted, JSON.parse(persistedSource)]) {
    assert.equal(safe.id, 'AUTH-01')
    assert.equal(safe.status, 'PASS')
    assert.deepEqual(safe.selection.caseIds, ['AUTH-01'])
    assert.equal(safe.cases['AUTH-01'].status, 'PASS')
    assert.equal(safe.cases['AUTH-01'].subruns[0].id, 'desktop-ui-core')
    assert.equal(safe.counts.PASS, 1)
  }
})

test('persistence preserves the real P0 typed evidence contract', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-domain-evidence-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const symbols = [
    'BTCUSDT',
    'ETHUSDT',
    'BNBUSDT',
    'SOLUSDT',
    'XRPUSDT',
    'BTCUSDT-PERP',
    'ETHUSDT-PERP',
    'BNBUSDT-PERP',
    'SOLUSDT-PERP',
    'XRPUSDT-PERP'
  ]
  const orderStatuses = [
    'RECEIVED',
    'VALIDATING',
    'ACCEPTED',
    'WORKING',
    'PARTIALLY_FILLED',
    'PENDING_ACTIVATION',
    'PENDING',
    'FILLED',
    'CANCEL_PENDING',
    'CANCELED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'FAILED'
  ]
  const tradingEventTypes = [
    'ORDER_ACCEPTED',
    'ORDER_PENDING',
    'ORDER_FILLED',
    'ORDER_CANCELED',
    'ORDER_REJECTED',
    'ORDER_EXPIRED',
    'ORDER_MODIFIED',
    'TRADE_CREATED',
    'BALANCE_UPDATED',
    'POSITION_UPDATED',
    'POSITION_CLOSED',
    'PROTECTION_CREATED',
    'PROTECTION_UPDATED',
    'PROTECTION_ACTIVATED',
    'PROTECTION_TRIGGERED',
    'PROTECTION_RESIZED',
    'PROTECTION_CANCELED',
    'PROTECTION_EXPIRED',
    'FUNDING_SETTLED',
    'MARGIN_ADJUSTED',
    'TRANSFER_COMPLETED',
    'LIQUIDATION',
    'DEMO_RESET',
    'MARKET_SOURCE_CHANGED'
  ]
  const ledgerEntryTypes = [
    'DEMO_INIT',
    'DEMO_RESET',
    'TRANSFER_IN',
    'TRANSFER_OUT',
    'DEMO_DEPOSIT',
    'ORDER_HOLD',
    'ORDER_RELEASE',
    'MARGIN_HOLD',
    'MARGIN_RELEASE',
    'TRADE_FEE',
    'TRADE_PNL',
    'FUNDING_FEE',
    'FINANCING',
    'CONVERSION_FEE',
    'LIQUIDATION_FEE',
    'BANKRUPTCY_SHORTFALL',
    'FORCED_CLOSE',
    'ADMIN_ADJUSTMENT',
    'CREDIT_AVAILABLE',
    'DEBIT_AVAILABLE',
    'LOCK_AVAILABLE',
    'RELEASE_LOCKED',
    'DEBIT_LOCKED'
  ]
  const snapshots = {
    catalog: symbols.map((symbol) => ({ symbol })),
    market: {
      symbol: 'ETHUSDT',
      bid: '3450.10',
      ask: '3450.20',
      last: '3450.15',
      mark: '3450.12',
      index: '3450.08',
      provider: 'binance',
      providerCode: 'binance',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2026-07-14T00:00:00.000Z',
      expiresAt: '2026-07-14T00:00:05.000Z',
      stale: false
    },
    order: {
      id: '11111111-1111-4111-8111-111111111111',
      clientOrderId: '22222222-2222-4222-8222-222222222222',
      symbol: 'BTCUSDT',
      status: 'FILLED',
      origin: 'USER',
      side: 'BUY',
      type: 'LIMIT',
      orderType: 'LIMIT',
      quantity: '0.25000000',
      unit: 'BASE',
      quantityUnit: 'BASE',
      baseQuantity: '0.25000000',
      fill: '0.25000000',
      filledQuantity: '0.25000000',
      price: '68000.50',
      fee: '8.50006250',
      feeAsset: 'USDT',
      liquidityRole: 'TAKER',
      realizedPnl: '12.25',
      reduceOnly: false,
      version: 3,
      createdAt: '2026-07-14T00:01:00.000Z'
    },
    trade: {
      id: '33333333-3333-4333-8333-333333333333',
      orderId: '11111111-1111-4111-8111-111111111111',
      symbol: 'BTCUSDT-PERP',
      side: 'SELL',
      price: '68125.75',
      quantity: '2',
      fee: '0.75',
      feeAsset: 'USDT',
      liquidityRole: 'MAKER',
      realizedPnl: '-3.50',
      providerCode: 'binance-usdm',
      sourceMode: 'PUBLIC_EXTERNAL',
      executedAt: '2026-07-14T00:02:00.000Z'
    },
    position: {
      id: '44444444-4444-4444-8444-444444444444',
      symbol: 'SOLUSDT-PERP',
      slot: 1,
      side: 'LONG',
      positionSide: 'LONG',
      quantity: '12.5',
      entry: '145.20',
      mark: '146.10',
      upl: '11.25',
      realizedPnl: '3.75',
      margin: '90.75',
      leverage: 20,
      maintenance: '18.15',
      liquidation: '132.40',
      marginMode: 'ISOLATED',
      status: 'OPEN',
      version: 9,
      openedAt: '2026-07-14T00:03:00.000Z'
    },
    account: {
      balance: '100000.00',
      equity: '100015.00',
      usedMargin: '90.75',
      freeMargin: '99924.25',
      available: '99924.25',
      locked: '75.00',
      total: '100000.00'
    },
    wallet: {
      walletType: 'USDT_PERP',
      asset: 'USDT',
      available: '9975.00',
      locked: '25.00',
      total: '10000.00'
    },
    ledger: {
      operation: 'ORDER_HOLD',
      operationType: 'ORDER_HOLD',
      entryType: 'TRADE_FEE',
      eventType: 'ORDER_FILLED',
      referenceType: 'ORDER',
      amount: '-8.50006250',
      referenceId: '11111111-1111-4111-8111-111111111111',
      balanceAfter: '99991.49993750',
      resourceType: 'ORDER',
      resourceId: '11111111-1111-4111-8111-111111111111',
      createdAt: '2026-07-14T00:04:00.000Z'
    },
    enums: {
      orderStatuses: orderStatuses.map((status) => ({ status })),
      orderTypes: ['MARKET', 'LIMIT', 'STOP', 'STOP_MARKET'].map((orderType) => ({ orderType })),
      sides: ['BUY', 'SELL'].map((side) => ({ side })),
      positionSides: ['BOTH', 'LONG', 'SHORT'].map((positionSide) => ({ positionSide })),
      origins: [
        'USER',
        'PROTECTIVE',
        'LIQUIDATION',
        'ADMIN_FORCE_CLOSE',
        'BATCH_CLOSE',
        'OCO'
      ].map((origin) => ({ origin })),
      units: ['BASE', 'QUOTE', 'CONTRACTS'].map((quantityUnit) => ({ quantityUnit })),
      marginModes: ['CASH', 'CROSS', 'ISOLATED'].map((marginMode) => ({ marginMode })),
      liquidityRoles: ['MAKER', 'TAKER'].map((liquidityRole) => ({ liquidityRole })),
      sourceModes: ['PUBLIC_EXTERNAL', 'LOCAL_SIMULATED'].map((sourceMode) => ({ sourceMode })),
      providers: [
        'binance',
        'okx',
        'local-spot',
        'binance-usdm',
        'okx-swap',
        'local-perp'
      ].map((providerCode) => ({ providerCode })),
      eventTypes: tradingEventTypes.map((eventType) => ({ eventType })),
      ledgerEntryTypes: ledgerEntryTypes.map((entryType) => ({ entryType }))
    }
  }
  const marker = 'ITEM3_TYPED_FIELD_CREDENTIAL_7bd1'
  const credentialFields = [
    'symbol',
    'bid',
    'provider',
    'providerCode',
    'sourceMode',
    'asOf',
    'status',
    'origin',
    'side',
    'orderType',
    'quantity',
    'quantityUnit',
    'feeAsset',
    'liquidityRole',
    'positionSide',
    'marginMode',
    'walletType',
    'asset',
    'operationType',
    'entryType',
    'eventType',
    'referenceType',
    'resourceType',
    'createdAt'
  ]
  const result = {
    id: 'AUTH-01',
    status: 'PASS',
    snapshots,
    rejected: Object.fromEntries(
      credentialFields.map((field) => [field, `Bearer ${marker}-${field}`])
    ),
    invalidTypes: {
      bid: '01.0',
      quantity: '1e3',
      leverage: 20.5,
      version: -1,
      stale: 'false',
      asOf: '2026-07-14T08:00:00+08:00'
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.snapshots, snapshots)
  const keyDigest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  assert.equal(Object.hasOwn(persisted, 'rejected'), false)
  assert.equal(Object.hasOwn(persisted, 'invalidTypes'), false)
  assert.deepEqual(persisted[keyDigest('rejected')], {})
  assert.deepEqual(persisted[keyDigest('invalidTypes')], {})
})

test('typed P0 gate evidence round-trips verdict modes timestamps and backend classes', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-gate-contract-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const expectedClasses = [
    'PostgresDatabaseIT',
    'V46V47EmptyDatabaseIT',
    'V45ToV47DemoResetIT',
    'Task5PostgresFullFillIT',
    'Task6PostgresSpotIT',
    'Task7PostgresDemoLifecycleIT',
    'Task8PostgresTradingSettingsIT',
    'Task9PostgresPerpetualOrderIT',
    'Task10PostgresProtectionIT',
    'Task11PostgresFundingIT',
    'DemoTradingConcurrencyIT',
    'PerpetualPositionConcurrencyIT',
    'ProtectionOrderConcurrencyIT',
    'FundingLiquidationConcurrencyIT'
  ]
  const invocationStartedAt = '2026-07-15T00:00:00.000Z'
  const suites = expectedClasses.map((className, index) => ({
    className,
    modifiedAt: new Date(Date.UTC(2026, 6, 15, 0, index + 1)).toISOString()
  }))
  const cases = {
    'AUTH-01': { verdict: 'PASS', mode: 'DISCOVERY' },
    'AUTH-02': { verdict: 'PARTIAL_PASS', mode: 'CERTIFICATION' },
    'AUTH-03': { verdict: 'FAIL', mode: 'DISCOVERY' },
    'CAT-01': { verdict: 'BLOCKED', mode: 'CERTIFICATION' }
  }
  const arbitrary = {
    verdict: 'ARBITRARY_VERDICT_REVIEW10',
    mode: 'ARBITRARY_MODE_REVIEW10',
    invocationStartedAt: 'not-a-canonical-instant-review10',
    expectedClasses: ['ArbitraryReview10IT'],
    suites: [{
      className: 'ArbitraryReview10IT',
      modifiedAt: 'not-a-canonical-instant-review10'
    }]
  }
  const evidence = {
    id: 'AUTH-01',
    verdict: 'PASS',
    mode: 'DISCOVERY',
    invocationStartedAt,
    expectedClasses,
    suites,
    cases,
    metadata: arbitrary
  }
  const original = structuredClone(evidence)

  const redacted = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)

  assert.deepEqual(evidence, original)
  const publicSource = JSON.stringify(redacted)
  const persistedSource = readFileSync(resultPath, 'utf8')
  for (const value of Object.values(arbitrary).flatMap((item) => (
    Array.isArray(item) ? item.map((entry) => (
      typeof entry === 'string' ? entry : JSON.stringify(entry)
    )) : [item]
  ))) {
    if (typeof value === 'string') assert.equal(publicSource.includes(value), false, value)
    if (typeof value === 'string') assert.equal(persistedSource.includes(value), false, value)
  }
  for (const safe of [redacted, JSON.parse(persistedSource)]) {
    assert.equal(safe.verdict, 'PASS')
    assert.equal(safe.mode, 'DISCOVERY')
    assert.equal(safe.invocationStartedAt, invocationStartedAt)
    assert.deepEqual(safe.expectedClasses, expectedClasses)
    assert.deepEqual(safe.suites, suites)
    assert.deepEqual(safe.cases, cases)
  }
})

test('positive evidence schema preserves Surefire gate counters', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-gate-counter-schema-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const resultPath = join(root, 'gate.json')
  const startedAt = new Date(Date.now() - 5_000)
  writeSurefireSuite(reports, 'TEST-postgres.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT',
    tests: 2
  })
  writeSurefireSuite(reports, 'TEST-fill.xml', {
    name: 'com.fxplatform.Task5PostgresFullFillIT',
    tests: 3
  })
  const gate = parseSurefireReports(
    reports,
    ['PostgresDatabaseIT', 'Task5PostgresFullFillIT'],
    startedAt
  )
  const evidence = {
    ...gate,
    metadata: {
      tests: -1,
      skipped: 1.5,
      failures: '0',
      errors: -2
    }
  }
  const original = structuredClone(evidence)

  const publicGate = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)

  assert.deepEqual(evidence, original)
  for (const safe of [publicGate, JSON.parse(readFileSync(resultPath, 'utf8'))]) {
    assert.deepEqual(safe.suites.map((suite) => ({
      tests: suite.tests,
      skipped: suite.skipped,
      failures: suite.failures,
      errors: suite.errors
    })), [
      { tests: 2, skipped: 0, failures: 0, errors: 0 },
      { tests: 3, skipped: 0, failures: 0, errors: 0 }
    ])
    assert.deepEqual(safe.totals, {
      tests: 5,
      skipped: 0,
      failures: 0,
      errors: 0
    })
    assert.deepEqual(safe.metadata, {})
  }
})

test('typed domain fields reject null while structural null remains safe', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-typed-null-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const startedAt = '2026-07-15T00:00:00.000Z'
  const finishedAt = '2026-07-15T00:01:00.000Z'
  const evidence = {
    id: 'AUTH-01',
    status: 'FAIL',
    metadata: {
      tests: null,
      skipped: '0',
      failures: -1,
      startedAt: null,
      finishedAt: '2026-07-15',
      authorityBundleFixture: null,
      amount: null,
      enabled: null,
      empty: null
    },
    totals: {
      tests: 3,
      skipped: 0,
      failures: 0,
      errors: 0,
      startedAt,
      finishedAt,
      authorityBundleFixture: 'PASS',
      amount: '1.2500',
      enabled: false
    },
    checks: [
      { authorityBundleFixture: 'BLOCKED' },
      { authorityBundleFixture: 'OUTSIDE_CONTRACT' }
    ]
  }
  const original = structuredClone(evidence)

  const publicResult = redactNetworkEntry(evidence)
  writeCaseResultAtomic(resultPath, evidence)
  const persistedResult = JSON.parse(readFileSync(resultPath, 'utf8'))

  assert.deepEqual(evidence, original)
  assert.deepEqual(publicResult, persistedResult)
  for (const safe of [publicResult, persistedResult]) {
    for (const field of [
      'tests',
      'skipped',
      'failures',
      'startedAt',
      'finishedAt',
      'authorityBundleFixture',
      'amount',
      'enabled'
    ]) {
      assert.equal(Object.hasOwn(safe.metadata, field), false, `metadata.${field}`)
    }
    assert.equal(Object.hasOwn(safe.metadata, 'empty'), true)
    assert.equal(safe.metadata.empty, null)
    assert.deepEqual(safe.totals, {
      tests: 3,
      skipped: 0,
      failures: 0,
      errors: 0,
      startedAt,
      finishedAt,
      authorityBundleFixture: 'PASS',
      amount: '1.2500',
      enabled: false
    })
    assert.deepEqual(safe.checks, [{ authorityBundleFixture: 'BLOCKED' }, {}])
  }
})

test('positive evidence schema preserves unified case records', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-unified-case-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const unknownContainer = 'unknownFixtureContainer'
  const credentialMarker = 'UNIFIED_CASE_CREDENTIAL_MARKER_31af'
  const record = {
    id: 'AUTH-01',
    status: 'BLOCKED',
    commit: RUN_STATE_COMMIT_A,
    database: 'p0-demo-database-01',
    user: 'p0-user-01',
    account: 'p0-account-01',
    profile: 'UI_CORE',
    viewport: 'desktop',
    startedAt: '2026-07-15T00:00:00.000Z',
    finishedAt: '2026-07-15T00:01:00.000Z',
    preconditions: [{ status: 'PASS' }],
    userActions: [{ status: 'PASS', note: 'clicked-order-submit' }],
    fixtureActions: [{ status: 'PASS' }],
    authorityBundleFixture: 'BLOCKED',
    contractProbes: [{ status: 'PASS' }],
    replayProbes: [{ id: 'AUTH-01', status: 'PASS' }],
    checkpoints: [{ status: 'PASS' }],
    financialCalculation: { amount: '1.2500' },
    uiEvidence: [{ status: 'OBSERVED' }],
    networkEvidence: [{ id: 'AUTH-01', status: 'OBSERVED' }],
    apiEvidence: [{ status: 'OBSERVED' }],
    dbEvidence: [{ status: 'OBSERVED' }],
    eventEvidence: [{ status: 'OBSERVED' }],
    consoleErrors: [],
    cleanup: { status: 'PASS' },
    failureOrBlocker: { status: 'BLOCKED', reason: 'CLI_INTERNAL_ERROR' },
    [unknownContainer]: { status: 'PASS' },
    apiToken: credentialMarker
  }
  const expectedFields = [
    'id', 'status', 'commit', 'database', 'user', 'account', 'profile', 'viewport',
    'startedAt', 'finishedAt', 'preconditions', 'userActions', 'fixtureActions',
    'authorityBundleFixture', 'contractProbes', 'replayProbes', 'checkpoints',
    'financialCalculation', 'uiEvidence', 'networkEvidence', 'apiEvidence',
    'dbEvidence', 'eventEvidence', 'consoleErrors', 'cleanup', 'failureOrBlocker'
  ]
  const original = structuredClone(record)

  const publicRecord = redactNetworkEntry(record)
  writeCaseResultAtomic(resultPath, record)

  assert.deepEqual(record, original)
  const persistedSource = readFileSync(resultPath, 'utf8')
  assert.equal(JSON.stringify(publicRecord).includes(credentialMarker), false)
  assert.equal(persistedSource.includes(credentialMarker), false)
  for (const safe of [publicRecord, JSON.parse(persistedSource)]) {
    for (const field of expectedFields) assert.equal(Object.hasOwn(safe, field), true, field)
    assert.equal(safe.startedAt, record.startedAt)
    assert.equal(safe.finishedAt, record.finishedAt)
    assert.equal(safe.authorityBundleFixture, 'BLOCKED')
    assert.deepEqual(safe.financialCalculation, { amount: '1.2500' })
    assert.equal(Object.hasOwn(safe, unknownContainer), false)
    assert.deepEqual(safe[persistenceDigest(unknownContainer)], { status: 'PASS' })
    assert.equal(Object.hasOwn(safe, 'apiToken'), false)
  }
  assert.deepEqual(publicRecord, JSON.parse(persistedSource))
})

test('network redaction sanitizes URL userinfo and explicit header representations', () => {
  const unknownMarker = 'UNKNOWN_HEADER_STRUCTURE_8ad4'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const entry = {
    url: 'https://trader:plain-password@example.invalid/orders?token=query-secret&symbol=BTCUSDT#access_token=fragment-secret&tab=open',
    headers: {
      Authorization: 'Bearer object-secret',
      Cookie: 'session=object-secret',
      'X-Trace-Id': 'safe-trace'
    },
    responseHeaders: [
      { name: 'Set-Cookie', value: 'session=name-value-secret' },
      { name: 'Content-Type', value: 'application/json' },
      ['Authorization', 'Bearer tuple-secret'],
      ['X-Request-Id', 'safe-request'],
      'Cookie: string-secret',
      'X-Region: safe-region',
      { label: 'Authorization', content: unknownMarker },
      ['Set-Cookie', unknownMarker, 'unexpected'],
      42
    ]
  }
  const original = JSON.parse(JSON.stringify(entry))

  const redacted = redactNetworkEntry(entry)

  assert.deepEqual(entry, original)
  const url = new URL(redacted.url)
  assert.equal(url.username, '')
  assert.equal(url.password, '')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  assert.equal(url.searchParams.get('symbol'), 'BTCUSDT')
  assert.equal(url.hash, '')
  assert.deepEqual(redacted.headers, {
    Authorization: '[REDACTED]',
    Cookie: '[REDACTED]',
    'X-Trace-Id': digest('safe-trace')
  })
  assert.deepEqual(redacted.responseHeaders, [
    { name: 'Set-Cookie', value: '[REDACTED]' },
    { name: 'Content-Type', value: 'application/json' },
    ['Authorization', '[REDACTED]'],
    ['X-Request-Id', digest('safe-request')],
    'Cookie: [REDACTED]',
    `X-Region: ${digest('safe-region')}`
  ])
  assert.equal(JSON.stringify(redacted).includes(unknownMarker), false)
})

test('header evidence hashes bounded unknown values and rejects credential-shaped values', () => {
  const opaqueHeader = 'opaque-marker'
  const credentialHeader = 'token=HEADER_CREDENTIAL_SECRET_4f8a'
  const accessKeySubtype = 'application/AKIAIOSFODNN7EXAMPLE'
  const secretCharset = `application/json; charset=${credentialHeader}`
  const entry = {
    headers: {
      'X-Node': opaqueHeader,
      'X-Region': credentialHeader,
      'Content-Type': accessKeySubtype
    },
    mimeType: secretCharset,
    responseHeaders: [
      ['X-Node', 'safe-node'],
      ['X-Region', 'safe-region'],
      ['Content-Type', 'application/json']
    ]
  }
  const original = structuredClone(entry)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  const redacted = redactNetworkEntry(entry)

  assert.deepEqual(entry, original)
  const serialized = JSON.stringify(redacted)
  assert.equal(serialized.includes(opaqueHeader), false)
  assert.equal(serialized.includes(credentialHeader), false)
  assert.equal(serialized.includes(accessKeySubtype), false)
  assert.deepEqual(redacted, {
    headers: { 'X-Node': digest(opaqueHeader) },
    responseHeaders: [
      ['X-Node', digest('safe-node')],
      ['X-Region', digest('safe-region')],
      ['Content-Type', 'application/json']
    ]
  })
})

test('persistence drops raw header text blobs without mutating safe network metadata', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-header-text-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const requestMarker = 'RAW_AUTHORIZATION_HEADER_TEXT_f271'
  const responseMarker = 'RAW_SET_COOKIE_HEADER_TEXT_88d4'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?symbol=BTCUSDT',
      status: 200,
      mimeType: 'application/json',
      headersText: `Authorization: Bearer ${requestMarker}\r\nX-Trace-Id: safe-trace`,
      responseHeadersText: `Set-Cookie: session=${responseMarker}\r\nContent-Type: application/json`,
      headers: { 'X-Trace-Id': 'safe-trace' }
    }]
  }
  const original = JSON.parse(JSON.stringify(result))

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(requestMarker), false)
  assert.equal(source.includes(responseMarker), false)
  const evidence = JSON.parse(source).networkEvidence[0]
  assert.equal('headersText' in evidence, false)
  assert.equal('responseHeadersText' in evidence, false)
  assert.deepEqual(evidence, {
    method: 'GET',
    url: '/api/orders?symbol=BTCUSDT',
    status: 200,
    mimeType: 'application/json',
    headers: { 'X-Trace-Id': digest('safe-trace') }
  })
})

test('header evidence rejects control injection and raw aliases', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-header-injection-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    map: 'INJECTED_MAP_AUTH_5f2a',
    tuple: 'INJECTED_TUPLE_AUTH_91c4',
    object: 'INJECTED_OBJECT_COOKIE_3d7b',
    string: 'INJECTED_STRING_AUTH_8a6e',
    fieldName: 'INVALID_FIELD_NAME_2e5c',
    raw: 'RAW_RESPONSE_HEADERS_f8b1',
    text: 'RAW_HEADER_TEXT_14d9',
    blob: 'RAW_HEADER_BLOB_7a30',
    stringAlias: 'RAW_HEADERS_STRING_6c42',
    block: 'RAW_HEADERS_BLOCK_0bd7'
  }
  const result = {
    id: 'AUTH-01',
    networkEvidence: [{
      headers: {
        'X-Trace-Id': 'safe-trace',
        Authorization: 'Bearer ordinary-secret',
        'X-Map': `safe\r\nAuthorization: Bearer ${markers.map}`,
        'Bad Header': markers.fieldName
      },
      requestHeaders: { 'X-Request-Id': 'safe-request' },
      responseHeaders: [
        ['X-Tuple', `safe\r\nAuthorization: Bearer ${markers.tuple}`],
        { name: 'X-Object', value: `safe\nSet-Cookie: session=${markers.object}` },
        `X-String: safe\u0000${markers.string}`,
        ['Bad Header', markers.fieldName],
        { name: 'Content-Type', value: 'application/json' },
        ['X-Region', 'safe-region'],
        'X-Node: safe-node'
      ],
      responseHeadersRaw: `Authorization: Bearer ${markers.raw}`,
      headerText: `Set-Cookie: session=${markers.text}`,
      responseHeaderBlob: `Authorization: Bearer ${markers.blob}`,
      headersString: `Set-Cookie: session=${markers.stringAlias}`,
      headersBlock: `Authorization: Bearer ${markers.block}`
    }]
  }
  const original = structuredClone(result)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  assert.deepEqual(JSON.parse(source).networkEvidence[0], {
    headers: {
      'X-Trace-Id': digest('safe-trace'),
      Authorization: '[REDACTED]'
    },
    requestHeaders: { 'X-Request-Id': digest('safe-request') },
    responseHeaders: [
      { name: 'Content-Type', value: 'application/json' },
      ['X-Region', digest('safe-region')],
      `X-Node: ${digest('safe-node')}`
    ]
  })
})

test('persistence routes header and body evidence by positive schema', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-positive-routing-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    headerLines: 'RAW_HEADER_LINES_2f8a',
    headersList: 'RAW_HEADERS_LIST_47bd',
    extraHeaders: 'RAW_EXTRA_HEADERS_6c31',
    headersDump: 'RAW_HEADERS_DUMP_9a52',
    responseHeadersRaw: 'RAW_RESPONSE_HEADERS_f1e4',
    bodyAlias: 'RAW_BODY_ALIAS_b4d7',
    payloadAlias: 'RAW_PAYLOAD_ALIAS_83ac'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    headerLines: `Authorization: Bearer ${markers.headerLines}`,
    headersList: [['Authorization', `Bearer ${markers.headersList}`]],
    extraHeaders: { Authorization: `Bearer ${markers.extraHeaders}` },
    headersDump: { name: 'Set-Cookie', value: `session=${markers.headersDump}` },
    responseHeadersRaw: `Set-Cookie: session=${markers.responseHeadersRaw}`,
    bodyDump: `password=${markers.bodyAlias}`,
    responseBodyLines: JSON.stringify({ token: markers.bodyAlias }),
    payloadList: [markers.payloadAlias],
    extraPayload: { password: markers.payloadAlias },
    headers: { Authorization: 'Bearer request-secret', 'X-Trace-Id': 'safe-trace' },
    requestHeaders: [['X-Request-Id', 'safe-request']],
    responseHeaders: [{ name: 'Set-Cookie', value: 'session=response-secret' }],
    Bo_Dy: JSON.stringify({
      password: 'request-json-secret',
      symbol: 'ETHUSDT',
      status: 'ACCEPTED'
    }),
    POST_DATA: 'password=request-form-secret&symbol=BNBUSDT',
    response_body: JSON.stringify({
      accessToken: 'response-json-secret',
      symbol: 'SOLUSDT-PERP',
      sourceMode: 'LOCAL_SIMULATED'
    }),
    'Response-Payload': 'password=response-form-secret&status=FILLED'
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  for (const alias of [
    'headerLines',
    'headersList',
    'extraHeaders',
    'headersDump',
    'responseHeadersRaw',
    'bodyDump',
    'responseBodyLines',
    'payloadList',
    'extraPayload'
  ]) assert.equal(alias in persisted, false, alias)
  assert.deepEqual(persisted.headers, {
    Authorization: '[REDACTED]',
    'X-Trace-Id': digest('safe-trace')
  })
  assert.deepEqual(persisted.requestHeaders, [['X-Request-Id', digest('safe-request')]])
  assert.deepEqual(persisted.responseHeaders, [{ name: 'Set-Cookie', value: '[REDACTED]' }])
  assert.deepEqual(JSON.parse(persisted.Bo_Dy), {
    password: '[REDACTED]',
    symbol: 'ETHUSDT',
    status: 'ACCEPTED'
  })
  assert.equal(new URLSearchParams(persisted.POST_DATA).get('password'), '[REDACTED]')
  assert.equal(new URLSearchParams(persisted.POST_DATA).get('symbol'), 'BNBUSDT')
  assert.deepEqual(JSON.parse(persisted.response_body), {
    accessToken: '[REDACTED]',
    symbol: 'SOLUSDT-PERP',
    sourceMode: 'LOCAL_SIMULATED'
  })
  assert.equal(
    new URLSearchParams(persisted['Response-Payload']).get('password'),
    '[REDACTED]'
  )
  assert.equal(new URLSearchParams(persisted['Response-Payload']).get('status'), 'FILLED')
})

test('persistence applies positive header body URL and network schemas', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-positive-network-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    requestData: 'REQUEST_BODY_UNKNOWN_DATA_Review8_a1',
    responseData: 'RESPONSE_BODY_UNKNOWN_DATA_Review8_b2',
    formData: 'FORM_UNKNOWN_DATA_Review8_c3',
    unknownHeader: 'UNKNOWN_X_SESSION_HEADER_Review8_d4',
    correlationHeader: 'Opaque+HeaderCorrelation==Review8E5',
    userinfo: 'URL_USERINFO_CREDENTIAL_Review8_f6',
    path: 'URL_PATH_CREDENTIAL_Review8_g7',
    query: 'URL_UNKNOWN_QUERY_Review8_h8',
    tokenQuery: 'URL_TOKEN_QUERY_Review8_i9',
    fragment: 'URL_FRAGMENT_CREDENTIAL_Review8_j0',
    networkAlias: 'NETWORK_UNKNOWN_ALIAS_Review8_k1',
    rawL8: 'RAW_L8_ENDPOINT_DATA_Review8_l2'
  }
  const result = {
    id: 'AUTH-01',
    body: JSON.stringify({
      data: markers.requestData,
      symbol: 'BNBUSDT',
      status: 'ACCEPTED',
      amount: '10',
      password: 'request-password-secret'
    }),
    arbitraryEvidence: [{
      method: 'POST',
      endpoint: '/api/orders',
      data: markers.rawL8
    }],
    networkEvidence: [{
      method: 'POST',
      url: `https://trader:${markers.userinfo}@example.invalid/api/orders/${markers.path}?symbol=BTCUSDT&sessionHint=${markers.query}&token=${markers.tokenQuery}#${markers.fragment}`,
      status: 200,
      mimeType: 'application/json',
      headers: {
        Authorization: 'Bearer request-secret',
        'Content-Type': 'application/json',
        'X-Trace-Id': markers.correlationHeader,
        'X-Session': markers.unknownHeader
      },
      responseHeaders: [
        ['X-Request-Id', markers.correlationHeader],
        ['Content-Type', 'application/json'],
        ['X-Session', markers.unknownHeader]
      ],
      responseBody: JSON.stringify({
        data: markers.responseData,
        market: {
          symbol: 'XRPUSDT',
          sourceMode: 'PUBLIC_EXTERNAL',
          unknown: markers.responseData
        },
        status: 'FILLED',
        accessToken: 'response-token-secret'
      }),
      responsePayload: new URLSearchParams({
        data: markers.formData,
        symbol: 'XRPUSDT',
        status: 'FILLED',
        password: 'form-password-secret'
      }).toString(),
      requestData: markers.networkAlias,
      endpoint: `/api/orders/${markers.networkAlias}`,
      data: markers.networkAlias,
      unknownEvidence: markers.networkAlias,
      response: { status: 201, data: markers.networkAlias }
    }]
  }
  const original = structuredClone(result)
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  assert.deepEqual(JSON.parse(persisted.body), {
    symbol: 'BNBUSDT',
    status: 'ACCEPTED',
    amount: '10',
    password: '[REDACTED]'
  })
  assert.deepEqual(persisted.arbitraryEvidence, [])
  const network = persisted.networkEvidence[0]
  assert.deepEqual(Object.keys(network).sort(), [
    'headers',
    'method',
    'mimeType',
    'response',
    'responseBody',
    'responseHeaders',
    'responsePayload',
    'status',
    'url'
  ])
  const url = new URL(network.url)
  assert.equal(url.username, '')
  assert.equal(url.password, '')
  assert.equal(url.hash, '')
  assert.equal(url.pathname.startsWith('/api/orders/'), true)
  assert.equal(url.pathname.includes(markers.path), false)
  assert.equal(url.searchParams.get('symbol'), 'BTCUSDT')
  assert.equal(url.searchParams.get('token'), '[REDACTED]')
  assert.equal(url.searchParams.has('sessionHint'), false)
  assert.deepEqual(network.headers, {
    Authorization: '[REDACTED]',
    'Content-Type': 'application/json',
    'X-Trace-Id': digest(markers.correlationHeader)
  })
  assert.deepEqual(network.responseHeaders, [
    ['X-Request-Id', digest(markers.correlationHeader)],
    ['Content-Type', 'application/json']
  ])
  assert.deepEqual(JSON.parse(network.responseBody), {
    market: { symbol: 'XRPUSDT', sourceMode: 'PUBLIC_EXTERNAL' },
    status: 'FILLED',
    accessToken: '[REDACTED]'
  })
  const form = new URLSearchParams(network.responsePayload)
  assert.equal(form.has('data'), false)
  assert.equal(form.get('symbol'), 'XRPUSDT')
  assert.equal(form.get('status'), 'FILLED')
  assert.equal(form.get('password'), '[REDACTED]')
  assert.deepEqual(network.response, { status: 201 })
})

test('body and URL schemas accept only contract-owned raw strings', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-body-url-public-values-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const credentialPhrase = 'secret-token'
  const credentialEmail = `${accessKey}@credential.invalid`
  const result = {
    id: 'AUTH-01',
    checks: [{
      body: JSON.stringify({
        symbol: accessKey,
        safe: credentialPhrase,
        state: credentialPhrase,
        displayName: accessKey,
        email: credentialEmail
      })
    }, {
      body: JSON.stringify({
        symbol: 'ETHUSDT-PERP',
        status: 'FILLED',
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '1.25',
        sourceMode: 'PUBLIC_EXTERNAL'
      })
    }],
    networkEvidence: [{
      method: 'GET',
      url: `https://${accessKey}.example.invalid/api/orders?symbol=${accessKey}`,
      status: 200
    }, {
      method: 'GET',
      url: 'https://example.invalid/api/orders?symbol=BTCUSDT',
      status: 200
    }]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(accessKey), false)
  assert.equal(source.includes(credentialPhrase), false)
  assert.equal(source.includes(credentialEmail), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(JSON.parse(persisted.checks[0].body), {})
  assert.deepEqual(JSON.parse(persisted.checks[1].body), {
    symbol: 'ETHUSDT-PERP',
    status: 'FILLED',
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '1.25',
    sourceMode: 'PUBLIC_EXTERNAL'
  })
  const unsafeUrl = new URL(persisted.networkEvidence[0].url)
  assert.equal(unsafeUrl.hostname, 'redacted.invalid')
  assert.equal(unsafeUrl.searchParams.has('symbol'), false)
  const safeUrl = new URL(persisted.networkEvidence[1].url)
  assert.equal(safeUrl.hostname, 'example.invalid')
  assert.equal(safeUrl.searchParams.get('symbol'), 'BTCUSDT')
})

test('network evidence accepts only typed plain entry objects', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-network-entry-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const arrayPath = join(directory, 'array.json')
  const scalarPath = join(directory, 'scalar.json')
  const markers = {
    scalarEntry: 'NETWORK_SCALAR_ENTRY_Review8_m3',
    arrayEntry: 'NETWORK_ARRAY_ENTRY_Review8_n4',
    scalarResponse: 'NETWORK_SCALAR_RESPONSE_Review8_o5',
    topLevel: 'NETWORK_TOP_LEVEL_SCALAR_Review8_p6'
  }
  const arrayResult = {
    id: 'AUTH-01',
    networkEvidence: [
      markers.scalarEntry,
      [markers.arrayEntry],
      {
        method: 'GET',
        url: '/api/orders',
        status: 200,
        response: markers.scalarResponse
      }
    ]
  }
  const scalarResult = {
    id: 'AUTH-01',
    networkEvidence: markers.topLevel
  }
  const originalArray = structuredClone(arrayResult)
  const originalScalar = structuredClone(scalarResult)

  writeCaseResultAtomic(arrayPath, arrayResult)
  writeCaseResultAtomic(scalarPath, scalarResult)

  assert.deepEqual(arrayResult, originalArray)
  assert.deepEqual(scalarResult, originalScalar)
  const arraySource = readFileSync(arrayPath, 'utf8')
  const scalarSource = readFileSync(scalarPath, 'utf8')
  for (const marker of Object.values(markers)) {
    assert.equal(arraySource.includes(marker), false, marker)
    assert.equal(scalarSource.includes(marker), false, marker)
  }
  assert.deepEqual(JSON.parse(arraySource).networkEvidence, [{
    method: 'GET',
    url: '/api/orders',
    status: 200
  }])
  assert.deepEqual(JSON.parse(scalarSource).networkEvidence, [])
})

test('atomic evidence never persists credentials', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-secret-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    networkEvidence: [{ headers: { authorization: 'Bearer process-only' } }]
  })

  const persisted = readFileSync(resultPath, 'utf8')
  assert.equal(persisted.includes('process-only'), false)
  assert.equal(JSON.parse(persisted).networkEvidence[0].headers.authorization, '[REDACTED]')
})

test('persistence strips raw replay requests but keeps sanitized replay and network evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-raw-replay-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    raw: 'RAW_REQUEST_ONLY_7af3',
    payload: 'REQUEST_PAYLOAD_ONLY_4c91',
    replay: 'REPLAY_PROBE_ONLY_8e62',
    network: 'NETWORK_SECRET_ONLY_2bd5'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    rawRequest: {
      method: `POST-${markers.raw}`,
      url: `/api/orders/${markers.raw}`,
      headers: { Authorization: `Bearer ${markers.raw}` },
      body: { clientSecret: markers.raw },
      postData: markers.raw
    },
    requestPayload: JSON.stringify({ password: markers.payload }),
    replayProbes: [{
      id: 'replay-1',
      referenceId: 'order-1',
      fingerprint: `sha256:${'1'.repeat(64)}`,
      outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' },
      method: `POST-${markers.replay}`,
      url: `/api/orders/${markers.replay}`,
      headers: { Cookie: markers.replay },
      body: markers.replay,
      postData: markers.replay,
      rawRequest: { url: markers.replay }
    }],
    networkEvidence: [{
      method: 'GET',
      url: `/api/orders?token=${markers.network}&symbol=BTCUSDT`,
      headers: { Authorization: `Bearer ${markers.network}` },
      status: 200
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  const persisted = JSON.parse(serialized)
  assert.equal('rawRequest' in persisted, false)
  assert.equal('requestPayload' in persisted, false)
  assert.deepEqual(persisted.replayProbes, [{
    id: digest('replay-1'),
    referenceId: digest('order-1'),
    fingerprint: `sha256:${'1'.repeat(64)}`,
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.networkEvidence[0].method, 'GET')
  assert.equal(persisted.networkEvidence[0].status, 200)
  assert.equal(
    new URL(persisted.networkEvidence[0].url, 'https://contract.invalid').searchParams.get('symbol'),
    'BTCUSDT'
  )
  assert.equal(persisted.networkEvidence[0].headers.Authorization, '[REDACTED]')
})

test('persistence rejects semantic request containers and nested replay tuples', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-request-bypass-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    l8: 'L8_REQUEST_BYPASS_52d1',
    rawL8: 'RAW_L8_REQUEST_BYPASS_f80a',
    body: 'REQUEST_BODY_BYPASS_961c',
    tuple: 'REQUEST_TUPLE_BYPASS_a74e'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    l8Request: { method: 'POST', url: `/api/orders/${markers.l8}`, body: markers.l8 },
    rawL8Request: {
      method: 'POST',
      url: `/api/orders/${markers.rawL8}`,
      body: markers.rawL8
    },
    requestBody: { order: markers.body },
    replayProbes: [{
      id: 'replay-safe',
      referenceId: 'order-safe',
      requestId: 'request-safe',
      clientOrderId: 'client-safe',
      fingerprint: `sha256:${'2'.repeat(64)}`,
      requestFingerprint: `sha256:${'3'.repeat(64)}`,
      outcome: {
        status: 'REJECTED',
        errorCode: 'REQUEST_CONFLICT',
        requestTuple: {
          method: 'POST',
          url: `/api/orders/${markers.tuple}`,
          body: markers.tuple
        }
      }
    }],
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?token=ordinary-secret&symbol=BTCUSDT',
      status: 200
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  for (const container of ['l8Request', 'rawL8Request', 'requestBody', 'requestTuple']) {
    assert.equal(serialized.includes(`"${container}"`), false)
  }
  const persisted = JSON.parse(serialized)
  assert.deepEqual(persisted.replayProbes, [{
    id: digest('replay-safe'),
    referenceId: digest('order-safe'),
    requestId: digest('request-safe'),
    clientOrderId: digest('client-safe'),
    fingerprint: `sha256:${'2'.repeat(64)}`,
    requestFingerprint: `sha256:${'3'.repeat(64)}`,
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.networkEvidence[0].method, 'GET')
  assert.equal(persisted.networkEvidence[0].status, 200)
  assert.equal(
    new URL(persisted.networkEvidence[0].url, 'https://contract.invalid').searchParams.get('symbol'),
    'BTCUSDT'
  )
})

test('persistence drops structurally raw HTTP requests outside sanitized network evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-structural-request-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    replay: 'RAW_REPLAY_PAYLOAD_51ad',
    arbitrary: 'ARBITRARY_HTTP_CONTAINER_18f7',
    networkBody: 'NETWORK_REQUEST_BODY_a0c2',
    networkPostData: 'NETWORK_REQUEST_POST_DATA_3e64',
    networkPayload: 'NETWORK_REQUEST_PAYLOAD_972b'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'RES-01',
    rawReplayPayload: {
      method: 'POST',
      url: `/api/orders/${markers.replay}`,
      body: markers.replay
    },
    arbitraryEvidence: [
      { id: 'safe-observation', status: 'OBSERVED' },
      {
        method: 'PUT',
        url: `/api/orders/${markers.arbitrary}`,
        headers: { 'X-Marker': markers.arbitrary },
        payload: markers.arbitrary
      }
    ],
    networkEvidence: [{
      method: 'POST',
      url: '/api/orders?token=network-only-secret&symbol=BTCUSDT',
      status: 201,
      body: markers.networkBody,
      postData: markers.networkPostData,
      payload: markers.networkPayload,
      response: { status: 201 }
    }]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  assert.equal(serialized.includes('"rawReplayPayload"'), false)
  const persisted = JSON.parse(serialized)
  assert.deepEqual(persisted.arbitraryEvidence, [
    { id: digest('safe-observation'), status: 'OBSERVED' }
  ])
  assert.deepEqual(persisted.networkEvidence, [{
    method: 'POST',
    url: '/api/orders?token=%5BREDACTED%5D&symbol=BTCUSDT',
    status: 201,
    response: { status: 201 }
  }])
})

test('persistence keeps only scalar user action requestRef evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-request-ref-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const requestRef = 'request-ref-safe-78b1'
  const attackMarker = 'REQUEST_REF_OBJECT_ATTACK_d497'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    userActions: [
      { action: 'submit-order', requestRef },
      {
        action: 'non-scalar-attack',
        requestRef: {
          method: 'POST',
          url: `/api/orders/${attackMarker}`,
          body: attackMarker
        }
      }
    ]
  })

  const serialized = readFileSync(resultPath, 'utf8')
  assert.equal(serialized.includes(attackMarker), false)
  assert.deepEqual(JSON.parse(serialized).userActions, [
    { action: digest('submit-order'), requestRef: digest(requestRef) },
    { action: digest('non-scalar-attack') }
  ])
})

test('persistence sanitizes scalar reference and replay fields and drops standalone payloads', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-scalar-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    requestRef: 'UNSAFE_REQUEST_REF_91ac',
    requestId: 'UNSAFE_REQUEST_ID_77e2',
    requestFingerprint: 'UNSAFE_REQUEST_FINGERPRINT_a62d',
    fingerprint: 'UNSAFE_FINGERPRINT_04b8',
    replayId: 'UNSAFE_REPLAY_ID_103a',
    replayCaseId: 'UNSAFE_REPLAY_CASE_ID_82cf',
    replaySubrunId: 'UNSAFE_REPLAY_SUBRUN_ID_11f0',
    replayReferenceId: 'UNSAFE_REPLAY_REFERENCE_ID_43b1',
    replayRequestId: 'UNSAFE_REPLAY_REQUEST_ID_5d9e',
    replayClientOrderId: 'UNSAFE_REPLAY_CLIENT_ORDER_ID_b447',
    replayFingerprint: 'UNSAFE_REPLAY_FINGERPRINT_3f20',
    replayRequestFingerprint: 'UNSAFE_REPLAY_REQUEST_FINGERPRINT_8c62',
    replayStatus: 'UNSAFE_REPLAY_STATUS_54d3',
    replayErrorCode: 'UNSAFE_REPLAY_ERROR_CODE_65ae',
    outcomeStatus: 'UNSAFE_OUTCOME_STATUS_706c',
    outcomeErrorCode: 'UNSAFE_OUTCOME_ERROR_CODE_0f19',
    payload: 'UNSAFE_STANDALONE_PAYLOAD_9f5c',
    requestPayload: 'UNSAFE_STANDALONE_REQUEST_PAYLOAD_f43b'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const unsafeReplay = {
    id: `Bearer ${markers.replayId}`,
    caseId: `session=${markers.replayCaseId}`,
    subrunId: `password=${markers.replaySubrunId}`,
    referenceId: `Cookie: ${markers.replayReferenceId}`,
    requestId: `request\r\n${markers.replayRequestId}`,
    clientOrderId: `client\u0000${markers.replayClientOrderId}`,
    fingerprint: `token=${markers.replayFingerprint}`,
    requestFingerprint: `secret=${markers.replayRequestFingerprint}`,
    status: `PASS ${markers.replayStatus}`,
    errorCode: `ERROR=${markers.replayErrorCode}`,
    outcome: {
      status: `FAIL\r\n${markers.outcomeStatus}`,
      errorCode: `PASSWORD=${markers.outcomeErrorCode}`
    }
  }
  const safeReplay = {
    id: 'replay-1',
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: 'order/123',
    requestId: '1234.56',
    clientOrderId: 'client_order-1',
    fingerprint: `sha256:${'a'.repeat(64)}`,
    requestFingerprint: `sha256:${'b'.repeat(64)}`,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }
  const persistedSafeReplay = {
    ...safeReplay,
    id: digest(safeReplay.id),
    referenceId: digest(safeReplay.referenceId),
    clientOrderId: digest(safeReplay.clientOrderId)
  }
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'unsafe-ref', requestRef: `Bearer ${markers.requestRef}` },
      { action: 'safe-ref', requestRef: 'request-ref-safe-78b1' },
      { action: 'unsafe-id', requestId: `Cookie: ${markers.requestId}` },
      { action: 'safe-id', requestId: '1234.56' },
      {
        action: 'unsafe-request-fingerprint',
        requestFingerprint: `password=${markers.requestFingerprint}`
      },
      { action: 'safe-request-fingerprint', requestFingerprint: `sha256:${'c'.repeat(64)}` },
      { action: 'unsafe-fingerprint', fingerprint: `token=${markers.fingerprint}` },
      { action: 'safe-fingerprint', fingerprint: `sha256:${'d'.repeat(64)}` }
    ],
    replayProbes: [unsafeReplay, safeReplay],
    payload: `password=${markers.payload}`,
    requestPayload: `secret=${markers.requestPayload}`
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('unsafe-ref') },
    { action: digest('safe-ref'), requestRef: digest('request-ref-safe-78b1') },
    { action: digest('unsafe-id') },
    { action: digest('safe-id'), requestId: '1234.56' },
    {
      action: digest('unsafe-request-fingerprint'),
      requestFingerprint: digest(`password=${markers.requestFingerprint}`)
    },
    {
      action: digest('safe-request-fingerprint'),
      requestFingerprint: `sha256:${'c'.repeat(64)}`
    },
    {
      action: digest('unsafe-fingerprint'),
      fingerprint: digest(`token=${markers.fingerprint}`)
    },
    { action: digest('safe-fingerprint'), fingerprint: `sha256:${'d'.repeat(64)}` }
  ])
  assert.deepEqual(persisted.replayProbes, [{
    fingerprint: digest(`token=${markers.replayFingerprint}`),
    requestFingerprint: digest(`secret=${markers.replayRequestFingerprint}`)
  }, persistedSafeReplay])
  assert.equal('payload' in persisted, false)
  assert.equal('requestPayload' in persisted, false)
})

test('persistence hashes opaque references with field-specific rules', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-field-specific-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc2X0pXVF9DUkVERU5USUFMIn0.signaturepart'
  const base64url = 'QWxhZGRpbk9wYXF1ZVJldmlldzZCYXNlNjRVcmxDcmVkZW50aWFsX01hcmtlcl8xMjM0NTY3ODkw'
  const opaque = 'OpaqueCredentialA7f4C9e2B6d8'
  const canonicalFingerprint = `sha256:${'e'.repeat(64)}`
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'jwt-ref', requestRef: jwt },
      { action: 'base64url-id', requestId: base64url },
      { action: 'opaque-ref', requestRef: opaque },
      { action: 'opaque-id', requestId: opaque },
      { action: 'jwt-fingerprint', requestFingerprint: jwt },
      { action: 'canonical-fingerprint', fingerprint: canonicalFingerprint },
      { action: 'cdp-request', requestId: '1234.56' },
      { action: 'uuid-request', requestRef: uuid },
      { action: 'client-request', requestRef: 'client_order-42' }
    ],
    replayProbes: [
      {
        id: jwt,
        referenceId: base64url,
        fingerprint: opaque,
        status: opaque,
        errorCode: jwt,
        outcome: { status: jwt, errorCode: base64url }
      },
      {
        id: 'replay-1',
        caseId: 'AUTH-01',
        subrunId: 'desktop-ui-core',
        referenceId: 'order/123',
        requestId: '1234.56',
        clientOrderId: uuid,
        fingerprint: canonicalFingerprint,
        requestFingerprint: canonicalFingerprint,
        status: 'REJECTED',
        errorCode: 'REQUEST_CONFLICT',
        outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
      }
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const credential of [jwt, base64url, opaque]) {
    assert.equal(source.includes(credential), false, credential)
  }
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('jwt-ref'), requestRef: digest(jwt) },
    { action: digest('base64url-id'), requestId: digest(base64url) },
    { action: digest('opaque-ref'), requestRef: digest(opaque) },
    { action: digest('opaque-id'), requestId: digest(opaque) },
    { action: digest('jwt-fingerprint'), requestFingerprint: digest(jwt) },
    { action: digest('canonical-fingerprint'), fingerprint: canonicalFingerprint },
    { action: digest('cdp-request'), requestId: '1234.56' },
    { action: digest('uuid-request'), requestRef: uuid },
    { action: digest('client-request'), requestRef: digest('client_order-42') }
  ])
  assert.deepEqual(persisted.replayProbes, [
    {
      id: digest(jwt),
      referenceId: digest(base64url),
      fingerprint: digest(opaque)
    },
    {
      id: digest('replay-1'),
      caseId: 'AUTH-01',
      subrunId: 'desktop-ui-core',
      referenceId: digest('order/123'),
      requestId: '1234.56',
      clientOrderId: uuid,
      fingerprint: canonicalFingerprint,
      requestFingerprint: canonicalFingerprint,
      status: 'REJECTED',
      errorCode: 'REQUEST_CONFLICT',
      outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
    }
  ])
  assert.equal(persisted.userActions[2].requestRef, persisted.userActions[3].requestId)
  assert.equal(persisted.userActions[2].requestRef, persisted.replayProbes[0].fingerprint)
})

test('reference fields hash bounded correlations and expose only exact public identities', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-bounded-references-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const caseId = P0_CASES[0].id
  const subrunId = P0_CASES[0].requiredSubruns[0].id
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const correlations = {
    secretary: 'secretary-correlation-123',
    tokenized: 'tokenized-order-correlation-456',
    longDigits: '1234567890123456789012345678901234567890',
    longDotted: '1234567890.1234567890.1234567890.1234567890',
    aws: 'AKIAIOSFODNN7EXAMPLE',
    jwt: 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJJVEVNNF9KV1QifQ.signaturepart',
    base64: 'SVRFTTRfQkFTRTY0X0NPVlJFTEFUSU9OX01BUktFUg==',
    opaque: 'Opaque+Item4Correlation==A7f4',
    client: 'client_order-unknown-correlation-789',
    order: 'order/unknown-correlation-987'
  }
  const credentialMarkers = {
    bearer: 'ITEM4_BEARER_CREDENTIAL_17a1',
    cookie: 'ITEM4_COOKIE_CREDENTIAL_28b2',
    session: 'ITEM4_SESSION_CREDENTIAL_39c3',
    password: 'ITEM4_PASSWORD_CREDENTIAL_40d4',
    token: 'ITEM4_TOKEN_CREDENTIAL_51e5'
  }
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: caseId,
    userActions: [
      ...Object.values(correlations).map((requestRef) => ({ requestRef })),
      { requestId: correlations.secretary },
      { referenceId: correlations.secretary },
      { requestRef: `Bearer ${credentialMarkers.bearer}` },
      { requestRef: `Cookie: session=${credentialMarkers.cookie}` },
      { requestRef: `session=${credentialMarkers.session}` },
      { requestRef: `password=${credentialMarkers.password}` },
      { requestRef: `token=${credentialMarkers.token}` }
    ],
    publicReferences: [
      { id: caseId },
      { caseId },
      { subrunId },
      { requestId: '1234.56' },
      { referenceId: '1234.56' },
      { requestRef: uuid },
      { requestId: correlations.longDigits }
    ],
    numericReferences: {
      id: 303,
      requestId: 404,
      referenceId: 505,
      caseId: 606,
      subrunId: 707
    },
    checkpoint: {
      id: correlations.opaque,
      referenceId: correlations.secretary,
      clientOrderId: correlations.client,
      requestId: correlations.tokenized
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const value of Object.values(correlations)) assert.equal(source.includes(value), false, value)
  for (const marker of Object.values(credentialMarkers)) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  Object.values(correlations).forEach((value, index) => {
    assert.equal(persisted.userActions[index].requestRef, digest(value), value)
  })
  assert.equal(persisted.userActions[10].requestId, digest(correlations.secretary))
  assert.equal(persisted.userActions[11].referenceId, digest(correlations.secretary))
  for (const action of persisted.userActions.slice(12)) assert.equal('requestRef' in action, false)
  assert.deepEqual(persisted.publicReferences, [
    { id: caseId },
    { caseId },
    { subrunId },
    { requestId: '1234.56' },
    { referenceId: digest('1234.56') },
    { requestRef: uuid },
    { requestId: digest(correlations.longDigits) }
  ])
  assert.equal(Object.hasOwn(persisted, 'numericReferences'), false)
  assert.deepEqual(persisted[persistenceDigest('numericReferences')], {
    id: 303,
    requestId: 404,
    referenceId: 505
  })
  assert.deepEqual(persisted.checkpoint, {
    id: digest(correlations.opaque),
    referenceId: digest(correlations.secretary),
    clientOrderId: digest(correlations.client),
    requestId: digest(correlations.tokenized)
  })
  assert.equal(persisted.userActions[0].requestRef, persisted.userActions[10].requestId)
  assert.equal(persisted.userActions[0].requestRef, persisted.checkpoint.referenceId)
})

test('persistence hashes nonpublic references and allowlists status evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-strict-public-reference-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const clientOpaque = 'client_OpaqueCredentialReview7A9f4'
  const clientOpaqueLower = 'client_opaquecredentialreview7'
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc3X0pXVCJ9.signature'
  const base64url = 'UmV2aWV3N0Jhc2U2NFVybENyZWRlbnRpYWxNYXJrZXJfMTIzNDU2Nzg5MA'
  const plusEquals = 'Opaque+Correlation==A7f4'
  const fingerprintValue = 'Fingerprint+Credential==Review7'
  const uppercaseFingerprint = `sha256:${'A'.repeat(64)}`
  const canonicalFingerprint = `sha256:${'a'.repeat(64)}`
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const safeReplay = {
    id: 'replay-1',
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: 'order/123',
    requestId: '1234.56',
    clientOrderId: 'client_order-1',
    fingerprint: canonicalFingerprint,
    requestFingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }
  const result = {
    id: 'AUTH-01',
    userActions: [
      { action: 'client-opaque', requestRef: clientOpaque },
      { action: 'client-opaque-lower', requestId: clientOpaqueLower },
      { action: 'access-key', requestRef: accessKey },
      { action: 'jwt', requestRef: jwt },
      { action: 'base64url', requestId: base64url },
      { action: 'plus-equals', requestRef: plusEquals },
      { action: 'opaque-fingerprint', requestFingerprint: fingerprintValue },
      { action: 'canonical-fingerprint', fingerprint: uppercaseFingerprint },
      { action: 'cdp-id', requestId: '1234.56' },
      { action: 'uuid-id', requestRef: uuid },
      { action: 'proven-request-id', requestRef: 'request-ref-safe-78b1' }
    ],
    replayProbes: [
      {
        id: clientOpaque,
        referenceId: plusEquals,
        requestId: accessKey,
        clientOrderId: clientOpaqueLower,
        fingerprint: plusEquals,
        status: accessKey,
        errorCode: accessKey,
        outcome: { status: accessKey, errorCode: accessKey }
      },
      safeReplay
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const credential of [
    clientOpaque,
    clientOpaqueLower,
    accessKey,
    jwt,
    base64url,
    plusEquals,
    fingerprintValue,
    uppercaseFingerprint
  ]) assert.equal(source.includes(credential), false, credential)
  const persisted = JSON.parse(source)
  assert.deepEqual(persisted.userActions, [
    { action: digest('client-opaque'), requestRef: digest(clientOpaque) },
    { action: digest('client-opaque-lower'), requestId: digest(clientOpaqueLower) },
    { action: digest('access-key'), requestRef: digest(accessKey) },
    { action: digest('jwt'), requestRef: digest(jwt) },
    { action: digest('base64url'), requestId: digest(base64url) },
    { action: digest('plus-equals'), requestRef: digest(plusEquals) },
    { action: digest('opaque-fingerprint'), requestFingerprint: digest(fingerprintValue) },
    { action: digest('canonical-fingerprint'), fingerprint: canonicalFingerprint },
    { action: digest('cdp-id'), requestId: '1234.56' },
    { action: digest('uuid-id'), requestRef: uuid },
    { action: digest('proven-request-id'), requestRef: digest('request-ref-safe-78b1') }
  ])
  assert.deepEqual(persisted.replayProbes, [{
    id: digest(clientOpaque),
    referenceId: digest(plusEquals),
    requestId: digest(accessKey),
    clientOrderId: digest(clientOpaqueLower),
    fingerprint: digest(plusEquals)
  }, {
    id: digest('replay-1'),
    caseId: 'AUTH-01',
    subrunId: 'desktop-ui-core',
    referenceId: digest('order/123'),
    requestId: '1234.56',
    clientOrderId: digest('client_order-1'),
    fingerprint: canonicalFingerprint,
    requestFingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT',
    outcome: { status: 'REJECTED', errorCode: 'REQUEST_CONFLICT' }
  }])
  assert.equal(persisted.userActions[5].requestRef, persisted.replayProbes[0].referenceId)
  assert.equal(persisted.userActions[5].requestRef, persisted.replayProbes[0].fingerprint)
})

test('persistence applies one global evidence field schema with canonical case and subrun ids', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-global-evidence-schema-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const opaque = 'Opaque+GlobalCorrelation==Review8A7f4'
  const fingerprintValue = 'Fingerprint+GlobalOpaque==Review8'
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVc4X0dMT0JBTCJ9.signature'
  const base64url = 'UmV2aWV3OEdMT0JBTEJhc2U2NFVybFZhbHVlXzEyMzQ1Njc4OTA'
  const accessKey = 'AKIAIOSFODNN7EXAMPLE'
  const forgedCaseId = 'FAKE-77'
  const forgedSubrunId = 'desktop-opaque-review8'
  const canonicalCaseId = P0_CASES[0].id
  const canonicalSubrunId = P0_CASES[0].requiredSubruns[0].id
  const uuid = '550e8400-e29b-41d4-a716-446655440000'
  const canonicalFingerprint = `sha256:${'a'.repeat(64)}`
  const uppercaseFingerprint = `sha256:${'A'.repeat(64)}`
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const form = new URLSearchParams({
    referenceId: opaque,
    fingerprint: fingerprintValue,
    status: accessKey,
    caseId: forgedCaseId,
    subrunId: forgedSubrunId
  }).toString()
  const result = {
    id: canonicalCaseId,
    status: 'PASS',
    checkpoint: {
      id: opaque,
      clientOrderId: base64url,
      referenceId: opaque,
      resourceId: jwt,
      requestId: accessKey,
      fingerprint: uppercaseFingerprint,
      status: accessKey,
      errorCode: 'REQUEST_CONFLICT'
    },
    metadata: {
      id: 'nested-safe',
      status: 'OBSERVED',
      requestId: '1234.56',
      referenceId: uuid,
      caseId: canonicalCaseId,
      subrunId: canonicalSubrunId
    },
    forgedMembership: { caseId: forgedCaseId, subrunId: forgedSubrunId },
    numericMembership: {
      caseId: 101,
      subrunId: 202,
      id: 303,
      requestId: 404,
      referenceId: 505
    },
    body: JSON.stringify({
      id: opaque,
      referenceId: opaque,
      fingerprint: fingerprintValue,
      status: accessKey
    }),
    networkEvidence: [{
      method: 'GET',
      url: '/api/orders?symbol=BTCUSDT',
      status: 200,
      id: opaque,
      referenceId: opaque,
      resourceId: jwt,
      requestId: '1234.56',
      fingerprint: uppercaseFingerprint,
      errorCode: 'REQUEST_CONFLICT',
      responseBody: JSON.stringify({
        id: opaque,
        referenceId: opaque,
        fingerprint: fingerprintValue,
        status: accessKey,
        caseId: forgedCaseId,
        subrunId: forgedSubrunId
      }),
      responsePayload: form
    }],
    replayProbes: [{
      id: opaque,
      caseId: forgedCaseId,
      subrunId: forgedSubrunId,
      referenceId: opaque,
      requestId: accessKey,
      clientOrderId: base64url,
      fingerprint: fingerprintValue,
      status: accessKey,
      errorCode: 'REQUEST_CONFLICT'
    }, {
      id: 'replay-1',
      caseId: canonicalCaseId,
      subrunId: canonicalSubrunId,
      referenceId: 'order/123',
      requestId: '1234.56',
      clientOrderId: 'client_order-1',
      fingerprint: canonicalFingerprint,
      status: 'REJECTED',
      errorCode: 'REQUEST_CONFLICT'
    }]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of [
    opaque,
    fingerprintValue,
    jwt,
    base64url,
    accessKey,
    forgedCaseId,
    forgedSubrunId,
    uppercaseFingerprint
  ]) assert.equal(source.includes(marker), false, marker)
  const persisted = JSON.parse(source)
  assert.equal(persisted.id, canonicalCaseId)
  assert.equal(persisted.status, 'PASS')
  assert.deepEqual(persisted.checkpoint, {
    id: digest(opaque),
    clientOrderId: digest(base64url),
    referenceId: digest(opaque),
    resourceId: digest(jwt),
    requestId: digest(accessKey),
    fingerprint: canonicalFingerprint,
    errorCode: 'REQUEST_CONFLICT'
  })
  assert.deepEqual(persisted.metadata, {
    id: digest('nested-safe'),
    status: 'OBSERVED',
    requestId: '1234.56',
    referenceId: uuid,
    caseId: canonicalCaseId,
    subrunId: canonicalSubrunId
  })
  assert.equal(Object.hasOwn(persisted, 'forgedMembership'), false)
  assert.deepEqual(persisted[persistenceDigest('forgedMembership')], {
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId)
  })
  assert.equal(Object.hasOwn(persisted, 'numericMembership'), false)
  assert.deepEqual(persisted[persistenceDigest('numericMembership')], {
    id: 303,
    requestId: 404,
    referenceId: 505
  })
  assert.deepEqual(JSON.parse(persisted.body), {
    id: digest(opaque),
    referenceId: digest(opaque),
    fingerprint: digest(fingerprintValue)
  })
  const network = persisted.networkEvidence[0]
  assert.equal(network.status, 200)
  assert.equal(network.id, digest(opaque))
  assert.equal(network.referenceId, digest(opaque))
  assert.equal(network.resourceId, digest(jwt))
  assert.equal(network.requestId, '1234.56')
  assert.equal(network.fingerprint, canonicalFingerprint)
  assert.equal(network.errorCode, 'REQUEST_CONFLICT')
  assert.deepEqual(JSON.parse(network.responseBody), {
    id: digest(opaque),
    referenceId: digest(opaque),
    fingerprint: digest(fingerprintValue),
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId)
  })
  const persistedForm = new URLSearchParams(network.responsePayload)
  assert.equal(persistedForm.get('referenceId'), digest(opaque))
  assert.equal(persistedForm.get('fingerprint'), digest(fingerprintValue))
  assert.equal(persistedForm.has('status'), false)
  assert.equal(persistedForm.get('caseId'), digest(forgedCaseId))
  assert.equal(persistedForm.get('subrunId'), digest(forgedSubrunId))
  assert.deepEqual(persisted.replayProbes, [{
    id: digest(opaque),
    caseId: digest(forgedCaseId),
    subrunId: digest(forgedSubrunId),
    referenceId: digest(opaque),
    requestId: digest(accessKey),
    clientOrderId: digest(base64url),
    fingerprint: digest(fingerprintValue),
    errorCode: 'REQUEST_CONFLICT'
  }, {
    id: digest('replay-1'),
    caseId: canonicalCaseId,
    subrunId: canonicalSubrunId,
    referenceId: digest('order/123'),
    requestId: '1234.56',
    clientOrderId: digest('client_order-1'),
    fingerprint: canonicalFingerprint,
    status: 'REJECTED',
    errorCode: 'REQUEST_CONFLICT'
  }])
  assert.equal(persisted.checkpoint.id, network.id)
  assert.equal(network.id, JSON.parse(network.responseBody).id)
  assert.equal(network.id, persistedForm.get('referenceId'))
  assert.equal(network.id, persisted.replayProbes[0].referenceId)
})

test('persistence redacts parseable response bodies and drops opaque response payloads without mutation', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-redaction-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const markers = {
    accessToken: 'JSON_RESPONSE_ACCESS_TOKEN_e5c1',
    password: 'JSON_RESPONSE_PASSWORD_7a3d',
    formPassword: 'FORM_RESPONSE_PASSWORD_29bf',
    opaque: 'T1BBUVVFX1JFU1BPTlNFXzYxNmY+/=='
  }
  const result = {
    id: 'AUTH-01',
    networkEvidence: [
      {
        method: 'POST',
        url: '/api/login',
        status: 200,
        responseBody: JSON.stringify({
          accessToken: markers.accessToken,
          order: {
            password: markers.password,
            symbol: 'ETHUSDT',
            status: 'FILLED'
          }
        })
      },
      {
        method: 'POST',
        url: '/api/session',
        status: 200,
        responsePayload: `password=${markers.formPassword}&symbol=BNBUSDT`
      },
      {
        method: 'GET',
        url: '/api/binary',
        status: 200,
        responseBody: markers.opaque
      }
    ]
  }
  const original = JSON.parse(JSON.stringify(result))

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const serialized = readFileSync(resultPath, 'utf8')
  for (const marker of Object.values(markers)) assert.equal(serialized.includes(marker), false)
  const persisted = JSON.parse(serialized)
  assert.deepEqual(JSON.parse(persisted.networkEvidence[0].responseBody), {
    accessToken: '[REDACTED]',
    order: { password: '[REDACTED]', symbol: 'ETHUSDT', status: 'FILLED' }
  })
  const form = new URLSearchParams(persisted.networkEvidence[1].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'BNBUSDT')
  assert.equal('responseBody' in persisted.networkEvidence[2], false)
})

test('JSON body sanitizer does not form-fallback after parse', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-json-body-fail-closed-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const marker = 'JSON_SANITIZER_FALLBACK_PASSWORD_6e2a'
  const result = {
    id: 'AUTH-01',
    parsedFailure: {
      body: JSON.stringify({
        nested: { url: 'http://[', password: marker }
      })
    },
    canonicalForm: {
      body: 'password=form-secret&symbol=BNBUSDT'
    },
    validJson: {
      body: JSON.stringify({
        url: '/api/orders?access_token=query-secret&symbol=BTCUSDT',
        password: 'json-secret',
        status: 'ACCEPTED'
      })
    }
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  assert.equal(source.includes(marker), false)
  const persisted = JSON.parse(source)
  for (const key of ['parsedFailure', 'canonicalForm', 'validJson']) {
    assert.equal(Object.hasOwn(persisted, key), false)
  }
  const parsedFailure = persisted[persistenceDigest('parsedFailure')]
  const canonicalForm = persisted[persistenceDigest('canonicalForm')]
  const validJson = persisted[persistenceDigest('validJson')]
  assert.equal('body' in parsedFailure, false)
  const form = new URLSearchParams(canonicalForm.body)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'BNBUSDT')
  assert.deepEqual(JSON.parse(validJson.body), {
    url: '/api/orders?access_token=%5BREDACTED%5D&symbol=BTCUSDT',
    password: '[REDACTED]',
    status: 'ACCEPTED'
  })
})

test('response evidence drops padded base64 but keeps canonical credential form', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-form-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const paddedBase64 = [
    'YQ==',
    'c2VjcmV0X3Rva2VuPQ==',
    'YWI=',
    'c2VjcmV0X3Rva2VuPWE='
  ]

  writeCaseResultAtomic(resultPath, {
    id: 'AUTH-01',
    networkEvidence: [
      { method: 'GET', url: '/api/short', status: 200, responseBody: paddedBase64[0] },
      { method: 'GET', url: '/api/token', status: 200, responsePayload: paddedBase64[1] },
      { method: 'GET', url: '/api/single-padding', status: 200, responseBody: paddedBase64[2] },
      {
        method: 'GET',
        url: '/api/token-single-padding',
        status: 200,
        responsePayload: paddedBase64[3]
      },
      {
        method: 'POST',
        url: '/api/login',
        status: 200,
        responsePayload: 'password=canonical-secret&status=FILLED'
      }
    ]
  })

  const source = readFileSync(resultPath, 'utf8')
  for (const opaque of paddedBase64) assert.equal(source.includes(opaque), false)
  const evidence = JSON.parse(source).networkEvidence
  assert.equal('responseBody' in evidence[0], false)
  assert.equal('responsePayload' in evidence[1], false)
  assert.equal('responseBody' in evidence[2], false)
  assert.equal('responsePayload' in evidence[3], false)
  const form = new URLSearchParams(evidence[4].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('status'), 'FILLED')
})

test('response evidence drops opaque JSON and empty form leaves', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-response-opaque-leaves-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const resultPath = join(directory, 'result.json')
  const jwt = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJSRVZJRVzdX0pTT05fU0NBTEFSIn0.signature'
  const base64url = 'QmFzZTY0VXJsUmVzcG9uc2VMZWFmQ3JlZGVudGlhbF9NYXJrZXJfMTIzNDU2Nzg5MA'
  const opaque = 'OpaqueResponseCredentialA7f4C9e2'
  const digest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
  const result = {
    id: 'AUTH-01',
    networkEvidence: [
      { method: 'GET', url: '/api/jwt', status: 200, responseBody: JSON.stringify(jwt) },
      {
        method: 'GET',
        url: '/api/base64url',
        status: 200,
        responsePayload: JSON.stringify(base64url)
      },
      { method: 'GET', url: '/api/opaque', status: 200, responseBody: JSON.stringify(opaque) },
      {
        method: 'GET',
        url: '/api/array',
        status: 200,
        responseBody: JSON.stringify([
          jwt,
          [base64url, { id: 'nested-safe', accessToken: 'nested-secret' }],
          opaque,
          { id: 'safe-observation', status: 'OBSERVED', password: 'object-secret' }
        ])
      },
      { method: 'GET', url: '/api/empty-form', status: 200, responsePayload: 'YWI=&YWI=' },
      {
        method: 'GET',
        url: '/api/form',
        status: 200,
        responsePayload: 'password=form-secret&symbol=XRPUSDT'
      },
      {
        method: 'GET',
        url: '/api/object',
        status: 200,
        responseBody: JSON.stringify({
          id: 'response-1',
          accessToken: 'object-token',
          market: { symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' }
        })
      }
    ]
  }
  const original = structuredClone(result)

  writeCaseResultAtomic(resultPath, result)

  assert.deepEqual(result, original)
  const source = readFileSync(resultPath, 'utf8')
  for (const marker of [jwt, base64url, opaque]) assert.equal(source.includes(marker), false)
  const evidence = JSON.parse(source).networkEvidence
  assert.equal('responseBody' in evidence[0], false)
  assert.equal('responsePayload' in evidence[1], false)
  assert.equal('responseBody' in evidence[2], false)
  assert.deepEqual(JSON.parse(evidence[3].responseBody), [
    [{ id: digest('nested-safe'), accessToken: '[REDACTED]' }],
    { id: digest('safe-observation'), status: 'OBSERVED', password: '[REDACTED]' }
  ])
  assert.equal('responsePayload' in evidence[4], false)
  const form = new URLSearchParams(evidence[5].responsePayload)
  assert.equal(form.get('password'), '[REDACTED]')
  assert.equal(form.get('symbol'), 'XRPUSDT')
  assert.deepEqual(JSON.parse(evidence[6].responseBody), {
    id: digest('response-1'),
    accessToken: '[REDACTED]',
    market: { symbol: 'SOLUSDT-PERP', sourceMode: 'LOCAL_SIMULATED' }
  })
})

const RUN_STATE_COMMIT_A = 'a'.repeat(40)
const RUN_STATE_COMMIT_B = 'b'.repeat(40)
const RUN_STATE_TREE_A = 'c'.repeat(64)
const RUN_STATE_TREE_B = 'd'.repeat(64)
const runStateDigest = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`

test('concurrent run-state create never replaces a different identity', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-concurrent-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const mainExistsSync = existsSync
  const barrier = new Int32Array(
    new SharedArrayBuffer(2 * Int32Array.BYTES_PER_ELEMENT)
  )
  const workers = new Set()
  const workerSource = `
    const fs = require('node:fs')
    const { syncBuiltinESMExports } = require('node:module')
    const { parentPort, workerData } = require('node:worker_threads')

    const barrier = new Int32Array(workerData.barrier)
    const originalExistsSync = fs.existsSync
    let capturedAbsence = false

    fs.existsSync = function (input, ...arguments_) {
      if (!capturedAbsence && String(input) === workerData.statePath) {
        capturedAbsence = true
        const absent = !Reflect.apply(originalExistsSync, this, [input, ...arguments_])
        if (!absent) throw new Error('TEST_EXPECTED_ABSENT_RUN_STATE')
        Atomics.add(barrier, 0, 1)
        parentPort.postMessage({ type: 'absence', workerId: workerData.workerId })
        const waitStatus = Atomics.wait(barrier, 1, 0, workerData.barrierTimeoutMs)
        if (waitStatus === 'timed-out') throw new Error('TEST_BARRIER_TIMEOUT')
        return false
      }
      return Reflect.apply(originalExistsSync, this, [input, ...arguments_])
    }
    syncBuiltinESMExports()

    ;(async () => {
      let outcome
      let inputUnchanged = false
      try {
        const { loadOrCreateRunState } = await import(workerData.artifactsUrl)
        const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(workerData.casesUrl)
        const options = {
          path: workerData.statePath,
          runId: 'concurrent-create-contract',
          mode: 'DISCOVERY',
          commit: workerData.commit,
          worktreeFingerprint: workerData.worktreeFingerprint,
          schemaVersion: 1,
          registryFingerprint: P0_REGISTRY_FINGERPRINT,
          definitions: P0_CASES,
          selection: {}
        }
        const inputSnapshot = JSON.stringify(options)
        try {
          outcome = { ok: true, state: loadOrCreateRunState(options) }
        } catch (error) {
          outcome = {
            ok: false,
            name: error instanceof Error ? error.name : typeof error,
            message: error instanceof Error ? error.message : String(error)
          }
        }
        inputUnchanged = JSON.stringify(options) === inputSnapshot
      } catch (error) {
        outcome = {
          ok: false,
          name: error instanceof Error ? error.name : typeof error,
          message: error instanceof Error ? error.message : String(error)
        }
      } finally {
        fs.existsSync = originalExistsSync
        syncBuiltinESMExports()
      }

      const { existsSync: restoredExistsSync } = await import('node:fs')
      parentPort.postMessage({
        type: 'result',
        workerId: workerData.workerId,
        ...outcome,
        inputUnchanged,
        instrumentationRestored: fs.existsSync === originalExistsSync
          && restoredExistsSync === originalExistsSync
      })
    })().catch((error) => {
      parentPort.postMessage({
        type: 'result',
        workerId: workerData.workerId,
        ok: false,
        name: error instanceof Error ? error.name : typeof error,
        message: error instanceof Error ? error.message : String(error),
        inputUnchanged: false,
        instrumentationRestored: fs.existsSync === originalExistsSync
      })
    })
  `

  const identities = [
    {
      workerId: 'identity-a',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A
    },
    {
      workerId: 'identity-b',
      commit: RUN_STATE_COMMIT_B,
      worktreeFingerprint: RUN_STATE_TREE_B
    }
  ]

  const launch = (identity) => {
    const worker = new Worker(workerSource, {
      eval: true,
      workerData: {
        ...identity,
        artifactsUrl,
        casesUrl,
        statePath,
        barrier: barrier.buffer,
        barrierTimeoutMs: 4_000
      }
    })
    workers.add(worker)
    let absenceSeen = false
    let resultMessage
    let exited = false
    let resolveAbsence
    let rejectAbsence
    let resolveResult
    let rejectResult
    const absence = new Promise((resolvePromise, rejectPromise) => {
      resolveAbsence = resolvePromise
      rejectAbsence = rejectPromise
    })
    const result = new Promise((resolvePromise, rejectPromise) => {
      resolveResult = resolvePromise
      rejectResult = rejectPromise
    })
    const fail = (error) => {
      rejectAbsence(error)
      rejectResult(error)
    }
    const finish = () => {
      if (exited && resultMessage) resolveResult(resultMessage)
    }

    worker.on('message', (message) => {
      if (message?.type === 'absence') {
        absenceSeen = true
        resolveAbsence(message)
        return
      }
      if (message?.type === 'result') {
        resultMessage = message
        if (!absenceSeen) {
          rejectAbsence(new Error(`WORKER_RESULT_BEFORE_ABSENCE: ${identity.workerId}`))
        }
        finish()
      }
    })
    worker.once('error', fail)
    worker.once('exit', (code) => {
      workers.delete(worker)
      if (code !== 0) {
        fail(new Error(`WORKER_EXIT: ${identity.workerId}: ${code}`))
        return
      }
      exited = true
      if (!resultMessage) {
        fail(new Error(`WORKER_EXIT_WITHOUT_RESULT: ${identity.workerId}`))
        return
      }
      finish()
    })
    return { absence, result }
  }

  const bounded = (promise, label) => new Promise((resolvePromise, rejectPromise) => {
    const timeout = setTimeout(
      () => rejectPromise(new Error(`WORKER_TIMEOUT: ${label}`)),
      5_000
    )
    promise.then(
      (value) => {
        clearTimeout(timeout)
        resolvePromise(value)
      },
      (error) => {
        clearTimeout(timeout)
        rejectPromise(error)
      }
    )
  })
  const releaseAndTerminate = async () => {
    Atomics.store(barrier, 1, 1)
    Atomics.notify(barrier, 1, 2)
    const activeWorkers = [...workers]
    workers.clear()
    await Promise.allSettled(activeWorkers.map((worker) => worker.terminate()))
  }
  t.after(releaseAndTerminate)

  const handles = identities.map(launch)
  let results
  try {
    await bounded(Promise.all(handles.map(({ absence }) => absence)), 'absence barrier')
    assert.equal(Atomics.load(barrier, 0), 2)
    Atomics.store(barrier, 1, 1)
    Atomics.notify(barrier, 1, 2)
    results = await bounded(Promise.all(handles.map(({ result }) => result)), 'worker results')
  } finally {
    await releaseAndTerminate()
  }

  const successes = results.filter(({ ok }) => ok)
  const failures = results.filter(({ ok }) => !ok)
  assert.equal(successes.length, 1)
  assert.equal(failures.length, 1)
  assert.equal(failures[0].name, 'Error')
  assert.equal(failures[0].message, 'RESUME_MISMATCH: commit')
  for (const result of results) {
    assert.equal(result.inputUnchanged, true, result.workerId)
    assert.equal(result.instrumentationRestored, true, result.workerId)
  }
  assert.equal(existsSync, mainExistsSync)
  assert.equal(existsSync(statePath), true)

  const source = readFileSync(statePath, 'utf8')
  const diskState = JSON.parse(source)
  const winner = successes[0]
  const winnerIdentity = identities.find(({ workerId }) => workerId === winner.workerId)
  assert.deepEqual(diskState, winner.state)
  assert.equal(source, `${JSON.stringify(diskState, null, 2)}\n`)
  assert.equal(diskState.schemaVersion, 1)
  assert.equal(diskState.runId, runStateDigest('concurrent-create-contract'))
  assert.equal(diskState.mode, 'DISCOVERY')
  assert.equal(diskState.commit, winnerIdentity.commit)
  assert.equal(diskState.worktreeFingerprint, winnerIdentity.worktreeFingerprint)
  assert.equal(diskState.registryFingerprint, P0_REGISTRY_FINGERPRINT)
  assert.deepEqual(diskState.definitions, P0_CASES)
  assert.deepEqual(diskState.selection, {})
  assert.deepEqual(diskState.cases, {})
  assert.equal(new Date(diskState.createdAt).toISOString(), diskState.createdAt)
  assert.deepEqual(readdirSync(directory).toSorted(), ['run-state.json'])
})

test('run state is created atomically and resumes only an identical evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const options = {
    path: statePath,
    runId: 'discovery-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  }

  const created = loadOrCreateRunState(options)
  assert.equal(created.runId, runStateDigest('discovery-contract'))
  assert.deepEqual(created.cases, {})
  assert.equal(existsSync(`${statePath}.tmp`), false)
  created.cases['AUTH-01'] = { status: 'PASS', scopeComplete: true }
  writeCaseResultAtomic(statePath, created)
  assert.deepEqual(loadOrCreateRunState(options), created)

  for (const [field, value] of [
    ['commit', RUN_STATE_COMMIT_B],
    ['worktreeFingerprint', RUN_STATE_TREE_B],
    ['schemaVersion', 2]
  ]) {
    assert.throws(
      () => loadOrCreateRunState({ ...options, [field]: value }),
      new RegExp(`^Error: RESUME_MISMATCH: ${field}$`)
    )
  }
  assert.throws(
    () => loadOrCreateRunState({
      ...options,
      registryFingerprint: 'e'.repeat(64)
    }),
    /^Error: INVALID_RUN_STATE_IDENTITY: options\.registryFingerprint$/
  )
})

test('run state rejects missing, null, blank or invalid evidence identity', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-identity-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const valid = {
    runId: 'identity-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  }
  const invalidValues = {
    commit: [undefined, null, '', '   ', 42, {}],
    worktreeFingerprint: [undefined, null, '', '   ', 42, {}],
    schemaVersion: [undefined, null, '', '   ', 0, -1, 1.5, {}],
    registryFingerprint: [undefined, null, '', '   ', 'e'.repeat(64), 42, {}]
  }
  let sequence = 0

  for (const [field, values] of Object.entries(invalidValues)) {
    for (const value of values) {
      const invalidOptionsError = value === undefined
        ? /^TypeError: UNSAFE_IDENTITY_OBJECT$/
        : new RegExp(`^Error: INVALID_RUN_STATE_IDENTITY: options\\.${field}$`)
      const createPath = join(directory, `create-${sequence++}.json`)
      const createOptions = { ...valid, path: createPath, [field]: value }
      assert.throws(
        () => loadOrCreateRunState(createOptions),
        invalidOptionsError
      )
      assert.equal(existsSync(createPath), false)

      const loadPath = join(directory, `load-${sequence++}.json`)
      const persistedState = {
        ...valid,
        [field]: value,
        cases: {},
        createdAt: '2026-07-14T00:00:00.000Z'
      }
      if (value === undefined) delete persistedState[field]
      writeCaseResultAtomic(loadPath, persistedState)
      assert.throws(
        () => loadOrCreateRunState({ ...valid, path: loadPath }),
        new RegExp(`^Error: INVALID_RUN_STATE_IDENTITY: state\\.${field}$`)
      )

      const invalidLoadOptionsPath = join(directory, `load-options-${sequence++}.json`)
      writeCaseResultAtomic(invalidLoadOptionsPath, {
        ...valid,
        cases: {},
        createdAt: '2026-07-14T00:00:00.000Z'
      })
      assert.throws(
        () => loadOrCreateRunState({
          ...valid,
          path: invalidLoadOptionsPath,
          [field]: value
        }),
        invalidOptionsError
      )
    }
  }
})

test('run state ignores inherited identity registry and selection fields', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-inherited-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { writeFileSync } from 'node:fs'

      const { loadOrCreateRunState, planResume } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(${JSON.stringify(casesUrl)})
      const directory = ${JSON.stringify(directory)}
      const identity = {
        commit: ${JSON.stringify(RUN_STATE_COMMIT_A)},
        worktreeFingerprint: ${JSON.stringify(RUN_STATE_TREE_A)},
        schemaVersion: 1,
        registryFingerprint: P0_REGISTRY_FINGERPRINT
      }
      const cases = Object.fromEntries(P0_CASES.map((definition) => [
        definition.id,
        {
          id: definition.id,
          status: 'PASS',
          scopeComplete: true,
          subruns: definition.requiredSubruns.map((subrun) => ({
            ...subrun,
            status: 'PASS'
          }))
        }
      ]))
      const baseState = {
        ...identity,
        runId: 'inherited-state-contract',
        mode: 'DISCOVERY',
        definitions: P0_CASES,
        selection: {},
        cases,
        createdAt: '2026-07-14T00:00:00.000Z'
      }
      const inheritedValues = {
        ...identity,
        definitions: P0_CASES,
        selection: { caseIds: [P0_CASES[1].id] }
      }
      const observations = Object.create(null)

      for (const field of Object.keys(inheritedValues)) {
        const path = directory + '/' + field + '.json'
        const state = JSON.parse(JSON.stringify(baseState))
        delete state[field]
        writeFileSync(path, JSON.stringify(state) + '\\n')
        let calls = 0
        let error
        let loaded
        let plan
        Object.defineProperty(Object.prototype, field, {
          configurable: true,
          get() {
            calls += 1
            return inheritedValues[field]
          }
        })
        try {
          try {
            loaded = loadOrCreateRunState({
              ...identity,
              path,
              runId: 'inherited-state-contract',
              mode: 'DISCOVERY',
              definitions: P0_CASES,
              selection: {}
            })
            plan = planResume(
              loaded,
              P0_CASES,
              field === 'selection' ? { caseIds: [P0_CASES[0].id] } : {}
            )
          } catch (caught) {
            error = caught.message
          }
        } finally {
          delete Object.prototype[field]
        }
        observations[field] = {
          calls,
          error,
          hasOwn: loaded ? Object.hasOwn(loaded, field) : undefined,
          skipCount: plan?.entries.filter((entry) => entry.action === 'SKIP').length,
          scopeComplete: plan?.scopeComplete,
          filtered: plan?.filtered
        }
      }

      for (const field of ['commit', 'worktreeFingerprint', 'schemaVersion', 'registryFingerprint']) {
        const observation = observations[field]
        assert.equal(observation.error, 'INVALID_RUN_STATE_IDENTITY: state.' + field, field)
        assert.equal(observation.calls, 0, field)
        assert.notEqual(observation.skipCount, 60, field)
        assert.notEqual(observation.scopeComplete, true, field)
      }
      assert.equal(observations.definitions.error, 'MISSING_REGISTRY: state')
      assert.equal(observations.definitions.calls, 0)
      assert.equal(observations.selection.error, undefined)
      assert.equal(observations.selection.calls, 0)
      assert.equal(observations.selection.hasOwn, false)
      assert.equal(observations.selection.filtered, true)
      assert.equal(observations.selection.scopeComplete, false)
      assert.equal(observations.selection.skipCount, 1)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
})

test('run state snapshots inert options before path and registry access', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-state-options-snapshot-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const validOptions = (path) => ({
    path,
    runId: 'snapshot-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions: P0_CASES,
    selection: {}
  })

  const victimPath = join(directory, 'victim.json')
  const absentPath = join(directory, 'absent.json')
  const victimBaseline = '{"status":"PASS","owner":"victim"}\n'
  writeFileSync(victimPath, victimBaseline)
  let pathGetterCalls = 0
  const changingPath = validOptions(absentPath)
  Object.defineProperty(changingPath, 'path', {
    enumerable: true,
    get() {
      pathGetterCalls += 1
      return pathGetterCalls === 1 ? absentPath : victimPath
    }
  })

  assert.throws(
    () => loadOrCreateRunState(changingPath),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(pathGetterCalls, 0)
  assert.equal(existsSync(absentPath), false)
  assert.equal(readFileSync(victimPath, 'utf8'), victimBaseline)

  let definitionsGetterCalls = 0
  const definitionsPath = join(directory, 'definitions.json')
  const changingDefinitions = validOptions(definitionsPath)
  Object.defineProperty(changingDefinitions, 'definitions', {
    enumerable: true,
    get() {
      definitionsGetterCalls += 1
      return P0_CASES
    }
  })
  assert.throws(
    () => loadOrCreateRunState(changingDefinitions),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(definitionsGetterCalls, 0)
  assert.equal(existsSync(definitionsPath), false)

  let nestedGetterCalls = 0
  const nestedDefinitions = JSON.parse(JSON.stringify(P0_CASES))
  Object.defineProperty(nestedDefinitions[0], 'executionGroup', {
    enumerable: true,
    get() {
      nestedGetterCalls += 1
      return P0_CASES[0].executionGroup
    }
  })
  const nestedPath = join(directory, 'nested.json')
  assert.throws(
    () => loadOrCreateRunState({
      ...validOptions(nestedPath),
      definitions: nestedDefinitions
    }),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: accessor$/
  )
  assert.equal(nestedGetterCalls, 0)
  assert.equal(existsSync(nestedPath), false)

  let proxyTrapCalls = 0
  const proxyPath = join(directory, 'proxy.json')
  const proxiedOptions = new Proxy(validOptions(proxyPath), {
    get(target, field, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, field, receiver)
    }
  })
  assert.throws(
    () => loadOrCreateRunState(proxiedOptions),
    /^TypeError: UNSAFE_PERSISTENCE_VALUE: Proxy$/
  )
  assert.equal(proxyTrapCalls, 0)
  assert.equal(existsSync(proxyPath), false)

  assert.throws(
    () => loadOrCreateRunState(validOptions(undefined)),
    /^TypeError: UNSAFE_IDENTITY_OBJECT$/
  )
  const missingPathOptions = validOptions(undefined)
  delete missingPathOptions.path
  assert.throws(
    () => loadOrCreateRunState(missingPathOptions),
    /^TypeError: INVALID_RUN_STATE_PATH$/
  )

  for (const path of [null, '', 42, {}]) {
    assert.throws(
      () => loadOrCreateRunState({ ...validOptions(path) }),
      /^TypeError: INVALID_RUN_STATE_PATH$/
    )
  }

  const frozenPath = join(directory, 'frozen.json')
  const frozenOptions = Object.freeze({
    ...validOptions(frozenPath),
    selection: Object.freeze({})
  })
  const created = loadOrCreateRunState(frozenOptions)
  assert.equal(created.runId, runStateDigest('snapshot-contract'))
  assert.deepEqual(loadOrCreateRunState(frozenOptions), created)
})

function passingCase(definition) {
  return {
    id: definition.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: definition.requiredSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
}

function registryFingerprint(definitions) {
  return createHash('sha256').update(JSON.stringify(definitions)).digest('hex')
}

function uniquePassingCases(definitions) {
  return [...new Map(definitions.map((definition) => [
    definition.id,
    passingCase(definition)
  ])).values()]
}

const CANONICAL_REGISTRY_FINGERPRINT = registryFingerprint(P0_CASES)

function aggregateState(selection = {}) {
  return {
    definitions: P0_CASES,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection
  }
}

async function writeFullyFakeP0Report(
  execution,
  prepared,
  { signal, now = () => new Date().toISOString() } = {}
) {
  await prepared.assertIdentity?.({ signal })
  const scope = execution.plan?.scope ?? 'MATRIX'
  const state = prepared.runState ?? {
    ...aggregateState(prepared.selection ?? {
      caseIds: [],
      phases: [],
      profiles: [],
      viewports: []
    }),
    mode: String(prepared.options?.mode ?? 'discovery').toUpperCase()
  }
  const aggregate = scope === 'MATRIX'
    ? aggregateReport(state, execution.caseResults)
    : {
        verdict: 'PARTIAL_PASS',
        scopeComplete: false,
        counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
        issues: []
      }
  const safeCases = redactNetworkEntry({ cases: execution.caseResults }).cases
  const report = {
    schemaVersion: 1,
    status: aggregate.verdict,
    ...aggregate,
    runId: prepared.options.runId,
    mode: prepared.runState?.mode ?? String(prepared.options.mode).toUpperCase(),
    scope,
    phases: execution.plan.phases,
    selection: state.selection,
    identity: prepared.identity ? structuredClone(prepared.identity) : null,
    ownerId: prepared.ownerId,
    database: {
      canonical: prepared.canonicalDatabase,
      matrix: prepared.matrixDatabase
    },
    caseResults: safeCases,
    ...(scope === 'CONTROL'
      ? {
          controlResults: (execution.controlResults ?? []).map((result) => ({
            phase: result.phase,
            status: result.status,
            evidence: redactNetworkEntry({ evidence: result.evidence }).evidence
          }))
        }
      : {}),
    completedAt: now()
  }
  writeFileSync(join(prepared.runRoot, 'report.json'), `${JSON.stringify(report, null, 2)}\n`)
  return report
}

function resumeState(cases = {}) {
  return { ...aggregateState(), cases }
}

const INVALID_AGGREGATE_REGISTRIES = {
  'one-case': P0_CASES.slice(0, 1),
  '59-cases': P0_CASES.slice(0, 59),
  'replacement-id': [
    ...P0_CASES.slice(0, 59),
    { ...P0_CASES[59], id: 'UNKNOWN-99' }
  ],
  'duplicate-id': [...P0_CASES.slice(0, 59), P0_CASES[0]],
  'metadata-drift': [
    { ...P0_CASES[0], executionGroup: 'drifted-auth-session' },
    ...P0_CASES.slice(1)
  ]
}

for (const [scenario, definitions] of Object.entries(INVALID_AGGREGATE_REGISTRIES)) {
  test(`canonical registry rejects aggregate universe: ${scenario}`, () => {
    const report = aggregateReport({
      definitions,
      registryFingerprint: registryFingerprint(definitions),
      selection: {}
    }, uniquePassingCases(definitions))

    assert.equal(report.verdict, 'FAIL')
    assert.equal(report.scopeComplete, false)
    assert.ok(report.issues.includes('INVALID_REGISTRY'))
  })
}

test('canonical registry rejects a forged caller fingerprint', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-forged-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))

  assert.throws(
    () => loadOrCreateRunState({
      path: join(directory, 'run-state.json'),
      runId: 'registry-contract',
      mode: 'DISCOVERY',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A,
      schemaVersion: 1,
      registryFingerprint: 'forged-registry-fingerprint',
      definitions: P0_CASES,
      selection: {}
    }),
    /^Error: REGISTRY_FINGERPRINT_MISMATCH: options$/
  )
})

test('canonical registry rejects persisted definitions with a copied fingerprint', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-persisted-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const identity = {
    runId: 'registry-contract',
    mode: 'DISCOVERY',
    commit: RUN_STATE_COMMIT_A,
    worktreeFingerprint: RUN_STATE_TREE_A,
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }
  writeCaseResultAtomic(statePath, {
    ...identity,
    definitions: P0_CASES.slice(0, 59),
    cases: {},
    createdAt: '2026-07-14T00:00:00.000Z'
  })

  assert.throws(
    () => loadOrCreateRunState({
      ...identity,
      path: statePath,
      definitions: P0_CASES
    }),
    /^Error: INVALID_REGISTRY: state$/
  )
})

test('canonical registry reaches terminal PASS only with all exact results', () => {
  const report = aggregateReport({
    definitions: P0_CASES,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }, P0_CASES.map(passingCase))

  assert.equal(report.verdict, 'PASS')
  assert.equal(report.scopeComplete, true)
  assert.equal(report.counts.PASS, 60)
  assert.deepEqual(report.issues, [])
})

test('registry fingerprint rejects unsupported own JSON values without callbacks', () => {
  const invalidError = /^INVALID_REGISTRY:/
  const captureError = (definitions) => {
    try {
      canonicalRegistryFingerprint(definitions)
      return undefined
    } catch (error) {
      return error.message
    }
  }
  const definitionsWithOwnValue = (property, value) => {
    const definition = { ...P0_CASES[0] }
    Object.defineProperty(definition, property, {
      value,
      enumerable: true,
      configurable: true,
      writable: true
    })
    return [definition, ...P0_CASES.slice(1)]
  }

  assert.equal(canonicalRegistryFingerprint(P0_CASES), P0_REGISTRY_FINGERPRINT)

  let serializationCalls = 0
  const serializationDefinitions = definitionsWithOwnValue('toJSON', () => {
    serializationCalls += 1
    return P0_CASES[0]
  })
  const serializationError = captureError(serializationDefinitions)
  assert.equal(serializationCalls, 0)
  assert.match(serializationError ?? '', invalidError)

  for (const [label, value] of [
    ['undefined', undefined],
    ['function', () => 'unsupported'],
    ['symbol', Symbol('unsupported-registry-value')],
    ['NaN', Number.NaN],
    ['positive-infinity', Number.POSITIVE_INFINITY],
    ['negative-infinity', Number.NEGATIVE_INFINITY],
    ['bigint', 1n]
  ]) {
    assert.match(
      captureError(definitionsWithOwnValue('review12Extra', value)) ?? '',
      invalidError,
      label
    )
  }

  const symbolKeyDefinition = { ...P0_CASES[0] }
  Object.defineProperty(symbolKeyDefinition, Symbol('review12-key'), {
    value: 'unsupported',
    enumerable: true
  })
  assert.match(
    captureError([symbolKeyDefinition, ...P0_CASES.slice(1)]) ?? '',
    invalidError,
    'own symbol key'
  )

  let accessorCalls = 0
  const accessorDefinition = { ...P0_CASES[0] }
  Object.defineProperty(accessorDefinition, 'review12Extra', {
    enumerable: true,
    get() {
      accessorCalls += 1
      return 'unsupported'
    }
  })
  assert.match(
    captureError([accessorDefinition, ...P0_CASES.slice(1)]) ?? '',
    invalidError,
    'accessor'
  )
  assert.equal(accessorCalls, 0)

  let proxyTrapCalls = 0
  const proxiedDefinition = new Proxy(P0_CASES[0], {
    get(target, property, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, property, receiver)
    }
  })
  assert.match(
    captureError([proxiedDefinition, ...P0_CASES.slice(1)]) ?? '',
    invalidError,
    'Proxy'
  )
  assert.equal(proxyTrapCalls, 0)

  assert.match(
    captureError(definitionsWithOwnValue('review12Extra', new Date(0))) ?? '',
    invalidError,
    'non-plain object'
  )
  const sparseDefinitions = [...P0_CASES]
  delete sparseDefinitions[0]
  assert.match(captureError(sparseDefinitions) ?? '', invalidError, 'sparse array')
  const extraKeyDefinitions = [...P0_CASES]
  Object.defineProperty(extraKeyDefinitions, 'review12Extra', {
    value: 'unsupported',
    enumerable: true
  })
  assert.match(captureError(extraKeyDefinitions) ?? '', invalidError, 'extra-key array')

  for (const prototype of [Object.prototype, Array.prototype]) {
    const previous = Object.getOwnPropertyDescriptor(prototype, 'toJSON')
    let inheritedCalls = 0
    Object.defineProperty(prototype, 'toJSON', {
      configurable: true,
      value() {
        inheritedCalls += 1
        return { status: 'PASS' }
      }
    })
    try {
      assert.equal(canonicalRegistryFingerprint(P0_CASES), P0_REGISTRY_FINGERPRINT)
    } finally {
      if (previous) Object.defineProperty(prototype, 'toJSON', previous)
      else delete prototype.toJSON
    }
    assert.equal(inheritedCalls, 0)
  }
})

test('registry validation rejects scalar definitions without prototype callbacks', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-scalar-registry-definition-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const statePath = join(directory, 'run-state.json')
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { existsSync } from 'node:fs'

      const {
        aggregateReport,
        loadOrCreateRunState,
        planResume
      } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(${JSON.stringify(casesUrl)})
      const scalarDefinitions = JSON.parse(JSON.stringify(P0_CASES.map(({ id }) => id)))
      assert.equal(scalarDefinitions.length, 60)
      assert.equal(scalarDefinitions.every((definition) => typeof definition === 'string'), true)
      const identity = {
        runId: 'review16-scalar-registry',
        mode: 'DISCOVERY',
        commit: 'a'.repeat(40),
        worktreeFingerprint: 'c'.repeat(64),
        schemaVersion: 1,
        registryFingerprint: P0_REGISTRY_FINGERPRINT
      }
      const selection = {}
      const loadOptions = {
        ...identity,
        path: ${JSON.stringify(statePath)},
        definitions: scalarDefinitions,
        selection
      }
      const resumeInput = {
        definitions: scalarDefinitions,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        cases: {}
      }
      const aggregateInput = {
        definitions: scalarDefinitions,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        selection
      }
      const aggregateResults = []
      const callerSnapshot = JSON.stringify({
        canonicalDefinitions: P0_CASES,
        scalarDefinitions,
        loadOptions,
        resumeInput,
        aggregateInput,
        aggregateResults,
        selection
      })
      const originalDescriptor = Object.getOwnPropertyDescriptor(String.prototype, 'id')
      let getterCalls = 0
      let throwing = false
      let loadError
      let resumeError
      let aggregateValue
      let throwingAggregateValue
      let capturedError

      try {
        Object.defineProperty(String.prototype, 'id', {
          configurable: true,
          get() {
            getterCalls += 1
            if (throwing) throw new Error('REVIEW16_THROWING_STRING_ID')
            return String(this)
          }
        })
        try {
          loadOrCreateRunState(loadOptions)
        } catch (error) {
          loadError = error
        }
        try {
          planResume(resumeInput, P0_CASES, selection)
        } catch (error) {
          resumeError = error
        }
        aggregateValue = aggregateReport(aggregateInput, aggregateResults)
        throwing = true
        try {
          throwingAggregateValue = aggregateReport(aggregateInput, aggregateResults)
        } catch (error) {
          capturedError = error
        }
      } catch (error) {
        capturedError = capturedError ?? error
      } finally {
        if (originalDescriptor) {
          Object.defineProperty(String.prototype, 'id', originalDescriptor)
        } else {
          delete String.prototype.id
        }
      }

      assert.deepEqual(
        Object.getOwnPropertyDescriptor(String.prototype, 'id'),
        originalDescriptor
      )
      if (capturedError) throw capturedError
      assert.equal(getterCalls, 0)
      assert.equal(loadError?.message, 'INVALID_REGISTRY: options')
      assert.equal(resumeError?.message, 'INVALID_REGISTRY: state')
      for (const [label, report] of [
        ['aggregate', aggregateValue],
        ['throwing aggregate', throwingAggregateValue]
      ]) {
        assert.equal(report.verdict, 'FAIL', label)
        assert.equal(report.scopeComplete, false, label)
        assert.deepEqual(report.counts, {
          PASS: 0,
          FAIL: 0,
          BLOCKED: 0,
          INVALID_TEST: 0,
          MISSING: 0
        }, label)
        assert.deepEqual(report.issues, ['INVALID_REGISTRY'], label)
      }
      assert.equal(existsSync(${JSON.stringify(statePath)}), false)
      assert.equal(JSON.stringify({
        canonicalDefinitions: P0_CASES,
        scalarDefinitions,
        loadOptions,
        resumeInput,
        aggregateInput,
        aggregateResults,
        selection
      }), callerSnapshot)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('strict identity snapshots reject unsupported own registry and selector data', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-strict-identity-snapshot-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const absent = Symbol('absent-selection')
  const canonicalResults = P0_CASES.map(passingCase)
  const allCases = Object.fromEntries(canonicalResults.map((result) => [result.id, result]))
  const invalidAggregateReport = {
    verdict: 'FAIL',
    scopeComplete: false,
    counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
    issues: ['INVALID_AGGREGATE_INPUT']
  }
  const capture = (operation) => {
    try {
      return { value: operation() }
    } catch (error) {
      return { error }
    }
  }
  const options = (path, definitions = P0_CASES, selection = absent) => {
    const value = {
      path,
      runId: 'strict-identity-contract',
      mode: 'DISCOVERY',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A,
      schemaVersion: 1,
      registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
      definitions
    }
    if (selection !== absent) value.selection = selection
    return value
  }
  const defineData = (target, property, value, enumerable = true) => {
    Object.defineProperty(target, property, {
      value,
      enumerable,
      configurable: true,
      writable: true
    })
  }
  const assertIdentityRejected = (outcome, label) => {
    assert.equal(outcome.value, undefined, label)
    assert.equal(outcome.error?.name, 'TypeError', label)
    assert.equal(outcome.error?.message, 'UNSAFE_IDENTITY_OBJECT', label)
  }
  const assertPlanRejected = (outcome, label) => {
    if (outcome.value) {
      assert.notEqual(outcome.value.scopeComplete, true, label)
      assert.notEqual(
        outcome.value.entries.filter((entry) => entry.action === 'SKIP').length,
        P0_CASES.length,
        label
      )
    }
    assertIdentityRejected(outcome, label)
  }

  const registryFixtures = [
    ['own undefined', (definition) => {
      defineData(definition, 'review13IdentityField', undefined)
    }],
    ['own function', (definition, calls) => {
      defineData(definition, 'review13IdentityField', () => {
        calls.callable += 1
        return 'neutral'
      })
    }],
    ['own symbol value', (definition) => {
      defineData(definition, 'review13IdentityField', Symbol('review13-value'))
    }],
    ['own callable serialization', (definition, calls) => {
      defineData(definition, 'toJSON', () => {
        calls.callable += 1
        return { id: 'neutral' }
      })
    }],
    ['own symbol key', (definition) => {
      defineData(definition, Symbol('review13-key'), 'neutral')
    }],
    ['own non-enumerable field', (definition) => {
      defineData(definition, 'review13IdentityField', 'neutral', false)
    }]
  ]
  const registryObservations = registryFixtures.map(([label, decorate], index) => {
    const calls = { callable: 0 }
    const definition = { ...P0_CASES[0] }
    decorate(definition, calls)
    const definitions = [definition, ...P0_CASES.slice(1)]
    const definitionDescriptors = Object.getOwnPropertyDescriptors(definition)
    const definitionsDescriptors = Object.getOwnPropertyDescriptors(definitions)
    const statePath = join(directory, `registry-${index}.json`)
    return {
      label,
      calls,
      definition,
      definitions,
      definitionDescriptors,
      definitionsDescriptors,
      statePath,
      load: capture(() => loadOrCreateRunState(options(statePath, definitions, {}))),
      callerPlan: capture(() => planResume(resumeState(allCases), definitions, {})),
      statePlan: capture(() => planResume({
        ...resumeState(allCases),
        definitions
      }, P0_CASES, {})),
      aggregate: capture(() => aggregateReport({
        definitions,
        registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
        selection: {}
      }, canonicalResults))
    }
  })

  for (const observation of registryObservations) {
    const label = `registry/${observation.label}`
    assert.deepEqual(observation.calls, { callable: 0 }, label)
    assert.deepEqual(
      Object.getOwnPropertyDescriptors(observation.definition),
      observation.definitionDescriptors,
      label
    )
    assert.deepEqual(
      Object.getOwnPropertyDescriptors(observation.definitions),
      observation.definitionsDescriptors,
      label
    )
    assert.equal(existsSync(observation.statePath), false, label)
    assertIdentityRejected(observation.load, `${label}/load`)
    assertPlanRejected(observation.callerPlan, `${label}/caller-plan`)
    assertPlanRejected(observation.statePlan, `${label}/state-plan`)
    assert.equal(observation.aggregate.error, undefined, `${label}/aggregate`)
    assert.deepEqual(observation.aggregate.value, invalidAggregateReport, `${label}/aggregate`)
    assert.notEqual(observation.aggregate.value?.verdict, 'PASS', `${label}/aggregate`)
  }

  const selectorFixtures = [
    ['recognized own undefined', (selection) => {
      defineData(selection, 'caseIds', undefined)
    }],
    ['recognized own function', (selection, calls) => {
      defineData(selection, 'caseIds', () => {
        calls.callable += 1
        return []
      })
    }],
    ['recognized own symbol', (selection) => {
      defineData(selection, 'caseIds', Symbol('review13-selector-value'))
    }],
    ['recognized non-enumerable key', (selection) => {
      defineData(selection, 'caseIds', [P0_CASES[0].id], false)
    }],
    ['extra own unsupported value', (selection) => {
      defineData(selection, 'review13IdentityField', undefined)
    }],
    ['own symbol key', (selection) => {
      defineData(selection, Symbol('review13-selector-key'), 'neutral')
    }]
  ]
  const selectorObservations = selectorFixtures.map(([label, decorate], index) => {
    const calls = { callable: 0 }
    const selection = {}
    decorate(selection, calls)
    const descriptors = Object.getOwnPropertyDescriptors(selection)
    const statePath = join(directory, `selector-${index}.json`)
    return {
      label,
      calls,
      selection,
      descriptors,
      statePath,
      load: capture(() => loadOrCreateRunState(options(statePath, P0_CASES, selection))),
      plan: capture(() => planResume(resumeState(allCases), P0_CASES, selection)),
      aggregate: capture(() => aggregateReport(aggregateState(selection), canonicalResults))
    }
  })

  for (const observation of selectorObservations) {
    const label = `selector/${observation.label}`
    assert.deepEqual(observation.calls, { callable: 0 }, label)
    assert.deepEqual(
      Object.getOwnPropertyDescriptors(observation.selection),
      observation.descriptors,
      label
    )
    assert.equal(existsSync(observation.statePath), false, label)
    assertIdentityRejected(observation.load, `${label}/load`)
    assertPlanRejected(observation.plan, `${label}/plan`)
    assert.equal(observation.aggregate.error, undefined, `${label}/aggregate`)
    assert.deepEqual(observation.aggregate.value, invalidAggregateReport, `${label}/aggregate`)
    assert.notEqual(observation.aggregate.value?.verdict, 'PASS', `${label}/aggregate`)
  }

  assert.equal(canonicalRegistryFingerprint(P0_CASES), P0_REGISTRY_FINGERPRINT)

  const absentState = loadOrCreateRunState(options(join(directory, 'absent-selector.json')))
  assert.deepEqual(absentState.selection, {})
  const fullPlan = planResume(resumeState(allCases), P0_CASES)
  assert.equal(fullPlan.filtered, false)
  assert.equal(fullPlan.scopeComplete, true)
  assert.equal(fullPlan.entries.length, P0_CASES.length)
  assert.equal(fullPlan.entries.filter((entry) => entry.action === 'SKIP').length, P0_CASES.length)
  const fullReport = aggregateReport({
    definitions: P0_CASES,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT
  }, canonicalResults)
  assert.equal(fullReport.verdict, 'PASS')
  assert.equal(fullReport.scopeComplete, true)
  assert.equal(fullReport.counts.PASS, P0_CASES.length)

  const filteredSelection = { caseIds: [P0_CASES[0].id] }
  const filteredPlan = planResume(resumeState(allCases), P0_CASES, filteredSelection)
  assert.equal(filteredPlan.filtered, true)
  assert.equal(filteredPlan.scopeComplete, false)
  assert.equal(filteredPlan.entries.length, 1)
  const filteredReport = aggregateReport(
    aggregateState(filteredSelection),
    [passingCase(P0_CASES[0])]
  )
  assert.equal(filteredReport.verdict, 'PARTIAL_PASS')
  assert.equal(filteredReport.scopeComplete, false)
  assert.equal(filteredReport.counts.PASS, 1)
})

test('resume ignores inherited case evidence on malformed cases containers', () => {
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'

      const { planResume } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(${JSON.stringify(casesUrl)})
      const first = P0_CASES[0]
      const complete = {
        id: first.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: first.requiredSubruns.map((subrun) => ({
          ...subrun,
          status: 'PASS'
        }))
      }
      const selection = { caseIds: [first.id] }
      const state = (cases) => ({
        definitions: P0_CASES,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        cases
      })
      const arrayCases = []
      const ownCases = { [first.id]: complete }
      const arrayState = state(arrayCases)
      const stringState = state('malformed-cases-container')
      const ownState = state(ownCases)
      const snapshots = {
        arrayState: JSON.stringify(arrayState),
        stringState: JSON.stringify(stringState),
        ownState: JSON.stringify(ownState),
        selection: JSON.stringify(selection)
      }
      const arrayDescriptor = Object.getOwnPropertyDescriptor(Array.prototype, first.id)
      const stringDescriptor = Object.getOwnPropertyDescriptor(String.prototype, first.id)
      let arrayCalls = 0
      let stringCalls = 0
      let arrayPlan
      let stringPlan
      let ownPlan
      let capturedError

      try {
        Object.defineProperty(Array.prototype, first.id, {
          configurable: true,
          get() {
            arrayCalls += 1
            return complete
          }
        })
        Object.defineProperty(String.prototype, first.id, {
          configurable: true,
          get() {
            stringCalls += 1
            return complete
          }
        })
        arrayPlan = planResume(arrayState, P0_CASES, selection)
        stringPlan = planResume(stringState, P0_CASES, selection)
        ownPlan = planResume(ownState, P0_CASES, selection)
      } catch (error) {
        capturedError = error
      } finally {
        if (arrayDescriptor) {
          Object.defineProperty(Array.prototype, first.id, arrayDescriptor)
        } else {
          delete Array.prototype[first.id]
        }
        if (stringDescriptor) {
          Object.defineProperty(String.prototype, first.id, stringDescriptor)
        } else {
          delete String.prototype[first.id]
        }
      }

      assert.deepEqual(
        Object.getOwnPropertyDescriptor(Array.prototype, first.id),
        arrayDescriptor
      )
      assert.deepEqual(
        Object.getOwnPropertyDescriptor(String.prototype, first.id),
        stringDescriptor
      )
      if (capturedError) throw capturedError
      assert.equal(arrayCalls, 0)
      assert.equal(stringCalls, 0)
      for (const [label, plan] of [
        ['array', arrayPlan],
        ['string', stringPlan]
      ]) {
        assert.equal(plan.filtered, true, label)
        assert.equal(plan.scopeComplete, false, label)
        assert.equal(plan.entries.length, 1, label)
        assert.equal(plan.entries[0].action, 'RUN', label)
        assert.notEqual(plan.entries[0].action, 'SKIP', label)
        assert.equal(plan.entries[0].scopeComplete, false, label)
      }
      assert.equal(ownPlan.filtered, true)
      assert.equal(ownPlan.scopeComplete, false)
      assert.equal(ownPlan.entries.length, 1)
      assert.equal(ownPlan.entries[0].action, 'SKIP')
      assert.equal(ownPlan.entries[0].reason, 'COMPLETE')
      assert.equal(ownPlan.entries[0].scopeComplete, true)
      assert.equal(JSON.stringify(arrayState), snapshots.arrayState)
      assert.equal(JSON.stringify(stringState), snapshots.stringState)
      assert.equal(JSON.stringify(ownState), snapshots.ownState)
      assert.equal(JSON.stringify(selection), snapshots.selection)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('resume rejects boxed scalar result and subrun evidence', () => {
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'

      const { planResume } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(${JSON.stringify(casesUrl)})
      const definition = P0_CASES.find(({ requiredSubruns }) => requiredSubruns.length === 1)
      assert.ok(definition)
      const required = definition.requiredSubruns[0]
      const complete = {
        id: definition.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: [{
          ...required,
          status: 'PASS'
        }]
      }
      const selection = { caseIds: [definition.id] }
      const state = (cases) => ({
        definitions: P0_CASES,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        cases
      })
      const stringCases = { [definition.id]: JSON.stringify(complete) }
      const numberCases = {
        [definition.id]: {
          ...complete,
          subruns: [JSON.parse('1')]
        }
      }
      const validCases = { [definition.id]: complete }
      const stringState = state(stringCases)
      const numberState = state(numberCases)
      const validState = state(validCases)
      const snapshots = {
        definitions: JSON.stringify(P0_CASES),
        selection: JSON.stringify(selection),
        stringState: JSON.stringify(stringState),
        numberState: JSON.stringify(numberState),
        validState: JSON.stringify(validState)
      }
      const stringValues = {
        id: definition.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: complete.subruns
      }
      const numberValues = {
        id: required.id,
        profile: required.profile,
        viewport: required.viewport,
        status: 'PASS'
      }
      const stringCalls = Object.create(null)
      const numberCalls = Object.create(null)
      const originalStringDescriptors = new Map(Object.keys(stringValues).map((field) => [
        field,
        Object.getOwnPropertyDescriptor(String.prototype, field)
      ]))
      const originalNumberDescriptors = new Map(Object.keys(numberValues).map((field) => [
        field,
        Object.getOwnPropertyDescriptor(Number.prototype, field)
      ]))
      for (const field of Object.keys(stringValues)) stringCalls[field] = 0
      for (const field of Object.keys(numberValues)) numberCalls[field] = 0
      let stringPlan
      let numberPlan
      let validPlan
      let capturedError

      const restoreDescriptors = (prototype, descriptors) => {
        for (const [field, descriptor] of descriptors) {
          if (descriptor) Object.defineProperty(prototype, field, descriptor)
          else delete prototype[field]
        }
      }

      try {
        for (const [field, value] of Object.entries(stringValues)) {
          Object.defineProperty(String.prototype, field, {
            configurable: true,
            get() {
              stringCalls[field] += 1
              return value
            }
          })
        }
        for (const [field, value] of Object.entries(numberValues)) {
          Object.defineProperty(Number.prototype, field, {
            configurable: true,
            get() {
              numberCalls[field] += 1
              return value
            }
          })
        }
        stringPlan = planResume(stringState, P0_CASES, selection)
        numberPlan = planResume(numberState, P0_CASES, selection)
        validPlan = planResume(validState, P0_CASES, selection)
      } catch (error) {
        capturedError = error
      } finally {
        restoreDescriptors(String.prototype, originalStringDescriptors)
        restoreDescriptors(Number.prototype, originalNumberDescriptors)
      }

      for (const [field, descriptor] of originalStringDescriptors) {
        assert.deepEqual(Object.getOwnPropertyDescriptor(String.prototype, field), descriptor)
      }
      for (const [field, descriptor] of originalNumberDescriptors) {
        assert.deepEqual(Object.getOwnPropertyDescriptor(Number.prototype, field), descriptor)
      }
      if (capturedError) throw capturedError
      for (const field of Object.keys(stringValues)) {
        assert.equal(stringCalls[field], 0, 'String.prototype.' + field)
      }
      for (const field of Object.keys(numberValues)) {
        assert.equal(numberCalls[field], 0, 'Number.prototype.' + field)
      }
      for (const [label, plan] of [
        ['string result', stringPlan],
        ['number subrun', numberPlan]
      ]) {
        assert.equal(plan.filtered, true, label)
        assert.equal(plan.scopeComplete, false, label)
        assert.equal(plan.entries.length, 1, label)
        assert.equal(plan.entries[0].action, 'RUN', label)
        assert.notEqual(plan.entries[0].action, 'SKIP', label)
        assert.equal(plan.entries[0].scopeComplete, false, label)
      }
      assert.equal(validPlan.filtered, true)
      assert.equal(validPlan.scopeComplete, false)
      assert.equal(validPlan.entries.length, 1)
      assert.equal(validPlan.entries[0].action, 'SKIP')
      assert.equal(validPlan.entries[0].reason, 'COMPLETE')
      assert.equal(validPlan.entries[0].scopeComplete, true)
      assert.equal(JSON.stringify(P0_CASES), snapshots.definitions)
      assert.equal(JSON.stringify(selection), snapshots.selection)
      assert.equal(JSON.stringify(stringState), snapshots.stringState)
      assert.equal(JSON.stringify(numberState), snapshots.numberState)
      assert.equal(JSON.stringify(validState), snapshots.validState)
      assert.equal(Object.hasOwn(stringCases, definition.id), true)
      assert.equal(Object.hasOwn(numberCases, definition.id), true)
      assert.equal(Object.hasOwn(validCases, definition.id), true)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('resume rejects scalar root state without prototype callbacks', () => {
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'

      const { planResume } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES, P0_REGISTRY_FINGERPRINT } = await import(${JSON.stringify(casesUrl)})
      const definition = P0_CASES[0]
      const selection = { caseIds: [definition.id] }
      const complete = {
        id: definition.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: definition.requiredSubruns.map((subrun) => ({
          ...subrun,
          status: 'PASS'
        }))
      }
      const validState = {
        definitions: P0_CASES,
        registryFingerprint: P0_REGISTRY_FINGERPRINT,
        cases: { [definition.id]: complete }
      }
      const arrayRoot = []
      const roots = [
        { label: 'string', value: 'review17-scalar-state', prototype: String.prototype },
        { label: 'number', value: 17, prototype: Number.prototype },
        { label: 'array', value: arrayRoot, prototype: Array.prototype }
      ]
      const fields = ['definitions', 'registryFingerprint']
      const originals = roots.map(({ label, prototype }) => ({
        label,
        prototype,
        descriptors: new Map(fields.map((field) => [
          field,
          Object.getOwnPropertyDescriptor(prototype, field)
        ]))
      }))
      const calls = Object.fromEntries(roots.map(({ label }) => [
        label,
        Object.fromEntries(fields.map((field) => [field, 0]))
      ]))
      const outcomes = []
      const snapshots = {
        definitions: JSON.stringify(P0_CASES),
        selection: JSON.stringify(selection),
        validState: JSON.stringify(validState),
        arrayRoot: JSON.stringify(arrayRoot)
      }
      let throwing = false
      let validPlan
      let capturedError

      const runRoots = (mode) => {
        for (const { label, value } of roots) {
          try {
            outcomes.push({ label, mode, result: planResume(value, P0_CASES, selection) })
          } catch (error) {
            outcomes.push({ label, mode, error: error.message })
          }
        }
      }

      try {
        for (const { label, prototype } of roots) {
          for (const field of fields) {
            Object.defineProperty(prototype, field, {
              configurable: true,
              get() {
                calls[label][field] += 1
                if (throwing) {
                  throw new Error('REVIEW17_THROWING_' + label + '_' + field)
                }
                return field === 'definitions' ? P0_CASES : P0_REGISTRY_FINGERPRINT
              }
            })
          }
        }
        runRoots('returning')
        throwing = true
        runRoots('throwing')
        throwing = false
        validPlan = planResume(validState, P0_CASES, selection)
      } catch (error) {
        capturedError = error
      } finally {
        for (const { prototype, descriptors } of originals) {
          for (const [field, descriptor] of descriptors) {
            if (descriptor) Object.defineProperty(prototype, field, descriptor)
            else delete prototype[field]
          }
        }
      }

      for (const { prototype, descriptors } of originals) {
        for (const [field, descriptor] of descriptors) {
          assert.deepEqual(Object.getOwnPropertyDescriptor(prototype, field), descriptor)
        }
      }
      if (capturedError) throw capturedError
      for (const { label } of roots) {
        for (const field of fields) {
          assert.equal(calls[label][field], 0, label + '.prototype.' + field)
        }
      }
      assert.deepEqual(outcomes, ['returning', 'throwing'].flatMap((mode) => (
        roots.map(({ label }) => ({
          label,
          mode,
          error: 'INVALID_RUN_STATE: state'
        }))
      )))
      assert.equal(validPlan.filtered, true)
      assert.equal(validPlan.scopeComplete, false)
      assert.equal(validPlan.entries.length, 1)
      assert.equal(validPlan.entries[0].action, 'SKIP')
      assert.equal(validPlan.entries[0].reason, 'COMPLETE')
      assert.equal(validPlan.entries[0].scopeComplete, true)
      assert.equal(JSON.stringify(P0_CASES), snapshots.definitions)
      assert.equal(JSON.stringify(selection), snapshots.selection)
      assert.equal(JSON.stringify(validState), snapshots.validState)
      assert.equal(JSON.stringify(arrayRoot), snapshots.arrayRoot)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('prototype inheritance cannot forge aggregate or resume evidence', () => {
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const casesUrl = new URL('./p0-user-trading-cases.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { createHash } from 'node:crypto'

      const { aggregateReport, planResume } = await import(${JSON.stringify(artifactsUrl)})
      const { P0_CASES } = await import(${JSON.stringify(casesUrl)})
      const fingerprint = createHash('sha256')
        .update(JSON.stringify(P0_CASES))
        .digest('hex')
      const passingCase = (definition) => ({
        id: definition.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: definition.requiredSubruns.map((subrun) => ({
          ...subrun,
          status: 'PASS'
        }))
      })
      const allCases = Object.fromEntries(P0_CASES.map((definition) => [
        definition.id,
        passingCase(definition)
      ]))
      const aggregateState = (selection) => ({
        definitions: P0_CASES,
        registryFingerprint: fingerprint,
        selection
      })
      const resumeState = {
        ...aggregateState({}),
        cases: allCases
      }
      const baselinePlan = planResume(resumeState, P0_CASES, {})
      const first = P0_CASES[0]
      const inheritedValues = {
        caseIds: [first.id],
        phases: [first.phase],
        profiles: [first.requiredSubruns[0].profile],
        viewports: [first.requiredSubruns[0].viewport]
      }
      const observations = Object.create(null)

      for (const field of Object.keys(inheritedValues)) {
        let calls = 0
        Object.defineProperty(Object.prototype, field, {
          configurable: true,
          get() {
            calls += 1
            if (field === 'caseIds' && calls === 1) return []
            return inheritedValues[field]
          }
        })
        try {
          observations[field] = {
            aggregate: aggregateReport(aggregateState({}), [passingCase(first)]),
            plan: planResume(resumeState, P0_CASES, {})
          }
        } finally {
          delete Object.prototype[field]
          observations[field].calls = calls
        }
      }

      const traversalValues = {
        definitions: P0_CASES,
        registryFingerprint: fingerprint,
        selection: {},
        cases: allCases,
        id: first.id,
        status: 'PASS',
        scopeComplete: true,
        subruns: passingCase(first).subruns,
        requiredSubruns: first.requiredSubruns,
        executionGroup: first.executionGroup
      }
      const traversalCalls = Object.fromEntries(
        Object.keys(traversalValues).map((field) => [field, 0])
      )
      for (const [field, value] of Object.entries(traversalValues)) {
        Object.defineProperty(Object.prototype, field, {
          configurable: true,
          get() {
            traversalCalls[field] += 1
            return value
          }
        })
      }
      let traversalReport
      let traversalPlanError
      try {
        traversalReport = aggregateReport({}, [{}])
        try {
          planResume({}, P0_CASES, {})
        } catch (error) {
          traversalPlanError = error.message
        }
      } finally {
        for (const field of Object.keys(traversalValues)) delete Object.prototype[field]
      }

      assert.notEqual(
        observations.caseIds.aggregate.verdict,
        'PASS',
        'changing inherited caseIds forged terminal PASS'
      )
      for (const [field, observation] of Object.entries(observations)) {
        assert.equal(observation.calls, 0, field)
        assert.deepEqual(observation.plan, baselinePlan, field)
      }
      assert.deepEqual(traversalCalls, Object.fromEntries(
        Object.keys(traversalValues).map((field) => [field, 0])
      ))
      assert.equal(traversalReport.verdict, 'FAIL')
      assert.deepEqual(traversalReport.issues, ['MISSING_REGISTRY'])
      assert.equal(traversalPlanError, 'MISSING_REGISTRY: state')

      const filteredSelection = { caseIds: [first.id] }
      const filteredReport = aggregateReport(
        aggregateState(filteredSelection),
        [passingCase(first)]
      )
      const filteredPlan = planResume(resumeState, P0_CASES, filteredSelection)
      assert.equal(filteredReport.verdict, 'PARTIAL_PASS')
      assert.equal(filteredPlan.filtered, true)
      assert.equal(filteredPlan.entries.length, 1)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('malformed aggregate inputs fail closed without throwing', () => {
  const definition = P0_CASES[0]
  const fixtures = [
    ['selection object with array-like length', { caseIds: { length: 1 } }, [passingCase(definition)]],
    ['selection string', { caseIds: definition.id }, [passingCase(definition)]],
    ['selection null element', { caseIds: [null] }, [passingCase(definition)]],
    ['phase null element', { phases: [null] }, [passingCase(definition)]],
    ['profile null element', { profiles: [null] }, [passingCase(definition)]],
    ['viewport null element', { viewports: [null] }, [passingCase(definition)]],
    ['selection array root', [null], [passingCase(definition)]],
    ['null selection', null, [passingCase(definition)]],
    ['null result', {}, [null]]
  ]
  const expected = {
    verdict: 'FAIL',
    scopeComplete: false,
    counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
    issues: ['INVALID_AGGREGATE_INPUT']
  }

  for (const [label, selection, results] of fixtures) {
    let report
    assert.doesNotThrow(() => {
      report = aggregateReport(aggregateState(selection), results)
    }, label)
    assert.deepEqual(report, expected, label)
  }
})

test('aggregate scalar validation fails closed before grouping and interpolation', () => {
  const definition = P0_CASES[0]
  const required = definition.requiredSubruns[0]
  const state = aggregateState({ caseIds: [definition.id] })
  const passWithSubrun = (subrun) => ({
    id: definition.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: [subrun]
  })
  const fixtures = [
    ['missing result id', [{ status: 'FAIL' }]],
    ['object result id', [{ id: { value: definition.id }, status: 'FAIL' }]],
    ['array result id', [{ id: [definition.id], status: 'FAIL' }]],
    ['missing result status', [{ id: definition.id }]],
    ['object result status', [{ id: definition.id, status: { value: 'FAIL' } }]],
    ['array result status', [{ id: definition.id, status: ['FAIL'] }]],
    ['null result status', [{ id: definition.id, status: null }]],
    ['nonobject PASS subrun', [passWithSubrun(null)]],
    ['missing PASS subrun id', [passWithSubrun({
      profile: required.profile,
      viewport: required.viewport,
      status: 'PASS'
    })]],
    ['object PASS subrun id', [passWithSubrun({
      ...required,
      id: { value: required.id },
      status: 'PASS'
    })]],
    ['missing PASS subrun profile', [passWithSubrun({
      id: required.id,
      viewport: required.viewport,
      status: 'PASS'
    })]],
    ['array PASS subrun profile', [passWithSubrun({
      ...required,
      profile: [required.profile],
      status: 'PASS'
    })]],
    ['missing PASS subrun viewport', [passWithSubrun({
      id: required.id,
      profile: required.profile,
      status: 'PASS'
    })]],
    ['object PASS subrun viewport', [passWithSubrun({
      ...required,
      viewport: { value: required.viewport },
      status: 'PASS'
    })]],
    ['missing PASS subrun status', [passWithSubrun({ ...required })]],
    ['array PASS subrun status', [passWithSubrun({
      ...required,
      status: ['PASS']
    })]]
  ]
  const invalid = {
    verdict: 'FAIL',
    scopeComplete: false,
    counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
    issues: ['INVALID_AGGREGATE_INPUT']
  }

  for (const [label, results] of fixtures) {
    const originalState = structuredClone(state)
    const originalResults = structuredClone(results)
    let report
    assert.doesNotThrow(() => {
      report = aggregateReport(state, results)
    }, label)
    assert.deepEqual(report, invalid, label)
    assert.deepEqual(state, originalState, `${label}/state`)
    assert.deepEqual(results, originalResults, `${label}/results`)
  }

  const unknown = aggregateReport(state, [{ id: 'UNKNOWN-99', status: 'FAIL' }])
  assert.equal(unknown.issues.includes('INVALID_AGGREGATE_INPUT'), false)
  assert.ok(unknown.issues.includes('UNEXPECTED_CASE: UNKNOWN-99'))

  const nonterminal = aggregateReport(state, [{ id: definition.id, status: 'RUNNING' }])
  assert.equal(nonterminal.issues.includes('INVALID_AGGREGATE_INPUT'), false)
  assert.ok(nonterminal.issues.includes(`INVALID_STATUS: ${definition.id}/RUNNING`))

  const precedenceDefinitions = P0_CASES.slice(0, 2)
  const precedence = aggregateReport(
    aggregateState({ caseIds: precedenceDefinitions.map(({ id }) => id) }),
    [
      { id: precedenceDefinitions[0].id, status: 'BLOCKED' },
      { id: precedenceDefinitions[1].id, status: 'FAIL' }
    ]
  )
  assert.equal(precedence.verdict, 'FAIL')
  assert.equal(precedence.counts.BLOCKED, 1)
  assert.equal(precedence.counts.FAIL, 1)

  const partial = aggregateReport(state, [passingCase(definition)])
  assert.equal(partial.verdict, 'PARTIAL_PASS')
  const full = aggregateReport(aggregateState(), P0_CASES.map(passingCase))
  assert.equal(full.verdict, 'PASS')
  assert.equal(full.scopeComplete, true)
})

test('aggregate snapshots immutable evidence and rejects malformed identity arrays', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-aggregate-identity-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const definition = P0_CASES[0]
  const selection = { caseIds: [definition.id] }
  const result = passingCase(definition)
  const capture = (run) => {
    try {
      return { value: run() }
    } catch (error) {
      return { error: error.message }
    }
  }
  const load = (definitions, selected, label) => loadOrCreateRunState({
    path: join(directory, `${label}.json`),
    runId: `aggregate-identity-${label}`,
    mode: 'DISCOVERY',
    commit: 'commit-a',
    worktreeFingerprint: 'tree-a',
    schemaVersion: 1,
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    definitions,
    selection: selected
  })

  let definitionsGetterCalls = 0
  const changingDefinitionsState = {
    registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
    selection: {}
  }
  Object.defineProperty(changingDefinitionsState, 'definitions', {
    enumerable: true,
    get() {
      definitionsGetterCalls += 1
      return definitionsGetterCalls === 1 ? P0_CASES : [definition]
    }
  })
  let selectionGetterCalls = 0
  const changingSelection = {}
  Object.defineProperty(changingSelection, 'caseIds', {
    enumerable: true,
    get() {
      selectionGetterCalls += 1
      return selectionGetterCalls === 1 ? [] : [definition.id]
    }
  })
  let resultGetterCalls = 0
  const resultWithGetter = passingCase(definition)
  Object.defineProperty(resultWithGetter, 'status', {
    enumerable: true,
    get() {
      resultGetterCalls += 1
      return 'PASS'
    }
  })
  const proxyCounts = {
    state: 0,
    definitions: 0,
    selection: 0,
    results: 0,
    result: 0
  }
  const proxiedState = new Proxy(aggregateState(selection), {
    get(target, field, receiver) {
      proxyCounts.state += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedDefinitions = new Proxy(P0_CASES, {
    get(target, field, receiver) {
      proxyCounts.definitions += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedSelection = new Proxy(selection, {
    get(target, field, receiver) {
      proxyCounts.selection += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedResults = new Proxy([result], {
    get(target, field, receiver) {
      proxyCounts.results += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const proxiedResult = new Proxy(result, {
    get(target, field, receiver) {
      proxyCounts.result += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const aggregateBoundaryOutcomes = {
    'changing-definitions': capture(() => aggregateReport(
      changingDefinitionsState,
      [result]
    )),
    'changing-selection': capture(() => aggregateReport(
      aggregateState(changingSelection),
      [result]
    )),
    'result-getter': capture(() => aggregateReport(
      aggregateState(selection),
      [resultWithGetter]
    )),
    'state-proxy': capture(() => aggregateReport(proxiedState, [result])),
    'definitions-proxy': capture(() => aggregateReport({
      definitions: proxiedDefinitions,
      registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
      selection
    }, [result])),
    'selection-proxy': capture(() => aggregateReport(
      aggregateState(proxiedSelection),
      [result]
    )),
    'results-proxy': capture(() => aggregateReport(
      aggregateState(selection),
      proxiedResults
    )),
    'result-proxy': capture(() => aggregateReport(
      aggregateState(selection),
      [proxiedResult]
    ))
  }

  const malformedArray = (base, variant, proxyValue) => {
    const value = [...base]
    const counts = { getter: 0, trap: 0 }
    if (variant === 'hole') value.length += 1
    if (variant === 'undefined') value.push(undefined)
    if (variant === 'function') value.push(() => proxyValue)
    if (variant === 'symbol') value.push(Symbol('review9-identity'))
    if (variant === 'accessor') {
      Object.defineProperty(value, value.length, {
        enumerable: true,
        get() {
          counts.getter += 1
          return proxyValue
        }
      })
    }
    if (variant === 'proxy-element') {
      value.push(new Proxy(
        proxyValue && typeof proxyValue === 'object' ? proxyValue : { value: proxyValue },
        {
          get(target, field, receiver) {
            counts.trap += 1
            return Reflect.get(target, field, receiver)
          }
        }
      ))
    }
    if (variant === 'enumerable-property') {
      Object.defineProperty(value, 'review9Extra', {
        value: proxyValue,
        enumerable: true
      })
    }
    if (variant === 'nonenumerable-property') {
      Object.defineProperty(value, 'review9Extra', {
        value: proxyValue,
        enumerable: false
      })
    }
    return { value, counts }
  }
  const variants = [
    'hole',
    'undefined',
    'function',
    'symbol',
    'accessor',
    'proxy-element',
    'enumerable-property',
    'nonenumerable-property'
  ]
  const malformedOutcomes = variants.map((variant) => {
    const definitions = malformedArray(P0_CASES, variant, definition)
    const selected = malformedArray([definition.id], variant, definition.id)
    const requiredSubruns = malformedArray(
      definition.requiredSubruns,
      variant,
      definition.requiredSubruns[0]
    )
    const definitionsWithRequiredSubruns = [
      { ...definition, requiredSubruns: requiredSubruns.value },
      ...P0_CASES.slice(1)
    ]
    const results = malformedArray([result], variant, result)
    const actualSubruns = malformedArray(result.subruns, variant, result.subruns[0])
    const resultWithSubruns = { ...result, subruns: actualSubruns.value }
    return {
      variant,
      counters: [
        definitions.counts,
        selected.counts,
        requiredSubruns.counts,
        results.counts,
        actualSubruns.counts
      ],
      outcomes: {
        'load-definitions': capture(() => load(
          definitions.value,
          selection,
          `${variant}-load-definitions`
        )),
        'plan-caller-definitions': capture(() => planResume(
          resumeState(),
          definitions.value,
          selection
        )),
        'plan-state-definitions': capture(() => planResume({
          definitions: definitions.value,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          cases: {}
        }, P0_CASES, selection)),
        'aggregate-definitions': capture(() => aggregateReport({
          definitions: definitions.value,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          selection
        }, [result])),
        'load-selection': capture(() => load(
          P0_CASES,
          { caseIds: selected.value },
          `${variant}-load-selection`
        )),
        'plan-selection': capture(() => planResume(
          resumeState(),
          P0_CASES,
          { caseIds: selected.value }
        )),
        'aggregate-selection': capture(() => aggregateReport(
          aggregateState({ caseIds: selected.value }),
          [result]
        )),
        'load-required-subruns': capture(() => load(
          definitionsWithRequiredSubruns,
          selection,
          `${variant}-load-required-subruns`
        )),
        'plan-caller-required-subruns': capture(() => planResume(
          resumeState(),
          definitionsWithRequiredSubruns,
          selection
        )),
        'plan-state-required-subruns': capture(() => planResume({
          definitions: definitionsWithRequiredSubruns,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          cases: {}
        }, P0_CASES, selection)),
        'aggregate-required-subruns': capture(() => aggregateReport({
          definitions: definitionsWithRequiredSubruns,
          registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
          selection
        }, [result])),
        'aggregate-results': capture(() => aggregateReport(
          aggregateState(selection),
          results.value
        )),
        'aggregate-result-subruns': capture(() => aggregateReport(
          aggregateState(selection),
          [resultWithSubruns]
        ))
      }
    }
  })

  const incomplete = passingCase(definition)
  incomplete.subruns = []
  const incompleteReport = aggregateReport(aggregateState(selection), [incomplete])

  const assertAggregateRejected = (outcome, label) => {
    assert.equal(outcome.error, undefined, label)
    assert.equal(outcome.value.verdict, 'FAIL', label)
    assert.deepEqual(outcome.value.issues, ['INVALID_AGGREGATE_INPUT'], label)
  }
  for (const [label, outcome] of Object.entries(aggregateBoundaryOutcomes)) {
    assertAggregateRejected(outcome, label)
  }
  assert.equal(definitionsGetterCalls, 0)
  assert.equal(selectionGetterCalls, 0)
  assert.equal(resultGetterCalls, 0)
  assert.deepEqual(proxyCounts, {
    state: 0,
    definitions: 0,
    selection: 0,
    results: 0,
    result: 0
  })

  for (const { variant, counters, outcomes } of malformedOutcomes) {
    for (const [boundary, outcome] of Object.entries(outcomes)) {
      const label = `${variant}/${boundary}`
      if (boundary.startsWith('aggregate-')) {
        assertAggregateRejected(outcome, label)
      } else {
        assert.match(
          outcome.error ?? '',
          /^(?:UNSAFE_IDENTITY_ARRAY|UNSAFE_PERSISTENCE_VALUE: (?:accessor|Proxy))$/,
          label
        )
      }
    }
    for (const counts of counters) {
      assert.deepEqual(counts, { getter: 0, trap: 0 }, variant)
    }
  }
  assert.equal(incompleteReport.verdict, 'FAIL')
  assert.ok(incompleteReport.issues.includes(`INCOMPLETE_MATRIX: ${definition.id}`))
  assert.equal(incompleteReport.counts.PASS, 0)
})

test('canonical registry remains full for valid filtered case and phase runs', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-registry-filtered-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const selections = [
    { caseIds: ['AUTH-03'] },
    { phases: ['funding'] }
  ]

  for (const [index, selection] of selections.entries()) {
    const state = loadOrCreateRunState({
      path: join(directory, `run-state-${index}.json`),
      runId: `registry-filter-${index}`,
      mode: 'DISCOVERY',
      commit: RUN_STATE_COMMIT_A,
      worktreeFingerprint: RUN_STATE_TREE_A,
      schemaVersion: 1,
      registryFingerprint: CANONICAL_REGISTRY_FINGERPRINT,
      definitions: P0_CASES,
      selection
    })
    const selected = P0_CASES.filter((definition) => (
      (!selection.caseIds || selection.caseIds.includes(definition.id))
      && (!selection.phases || selection.phases.includes(definition.phase))
    ))
    const report = aggregateReport(state, selected.map(passingCase))

    assert.equal(state.definitions.length, 60)
    assert.equal(state.registryFingerprint, CANONICAL_REGISTRY_FINGERPRINT)
    assert.equal(report.verdict, 'PARTIAL_PASS')
    assert.equal(report.scopeComplete, false)
    assert.deepEqual(report.issues, [])
  }
})

function passingSelectedCase(definition, selection) {
  const subruns = definition.requiredSubruns.filter((subrun) => (
    (!selection.profiles?.length || selection.profiles.includes(subrun.profile))
    && (!selection.viewports?.length || selection.viewports.includes(subrun.viewport))
  ))
  return {
    id: definition.id,
    status: 'PASS',
    scopeComplete: !selection.profiles?.length && !selection.viewports?.length,
    subruns: subruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
}

const INVALID_SELECTOR_FIXTURES = [
  {
    label: 'mixed caseIds',
    selection: { caseIds: ['AUTH-03', 'UNKNOWN-99'] }
  },
  {
    label: 'mixed phases',
    selection: { phases: ['funding', 'unknown-phase'] }
  },
  {
    label: 'mixed profiles',
    selection: { caseIds: ['SOURCE-03'], profiles: ['FUNDING_ONLY', 'UNKNOWN_PROFILE'] }
  },
  {
    label: 'mixed viewports',
    selection: { caseIds: ['UI-02'], viewports: ['mobile', 'watch'] }
  },
  {
    label: 'profile without selected-case coverage',
    selection: { caseIds: ['AUTH-03'], profiles: ['UI_CORE', 'FUNDING_ONLY'] }
  },
  {
    label: 'viewport without selected-case coverage',
    selection: { caseIds: ['AUTH-03'], viewports: ['desktop', 'mobile'] }
  }
]

for (const { label, selection } of INVALID_SELECTOR_FIXTURES) {
  const selectedDefinitions = P0_CASES.filter((definition) => (
    (!selection.caseIds?.length || selection.caseIds.includes(definition.id))
    && (!selection.phases?.length || selection.phases.includes(definition.phase))
  )).filter((definition) => passingSelectedCase(definition, selection).subruns.length > 0)
  const results = selectedDefinitions.map((definition) => passingSelectedCase(definition, selection))

  test(`invalid selector aggregation fails explicitly: ${label}`, () => {
    const report = aggregateReport(aggregateState(selection), results)

    assert.equal(report.verdict, 'FAIL')
    assert.equal(report.scopeComplete, false)
    assert.ok(report.issues.some((issue) => issue.startsWith('INVALID_SELECTION:')))
  })

  test(`invalid selector resume planning fails explicitly: ${label}`, () => {
    const cases = Object.fromEntries(results.map((result) => [result.id, result]))

    assert.throws(
      () => planResume(resumeState(cases), P0_CASES, selection),
      /^Error: INVALID_SELECTION:/
    )
  })
}

const CASE_LEVEL_SELECTION_FIXTURES = [
  {
    label: 'caseIds',
    selection: { caseIds: ['AUTH-03'] },
    definitions: P0_CASES.filter(({ id }) => id === 'AUTH-03')
  },
  {
    label: 'phases',
    selection: { phases: ['funding'] },
    definitions: P0_CASES.filter(({ phase }) => phase === 'funding')
  }
]

for (const fixture of CASE_LEVEL_SELECTION_FIXTURES) {
  test(`case-level selection resumes complete ${fixture.label} evidence`, () => {
    const cases = Object.fromEntries(
      fixture.definitions.map((definition) => [definition.id, passingCase(definition)])
    )
    const plan = planResume(resumeState(cases), P0_CASES, fixture.selection)

    assert.equal(plan.filtered, true)
    assert.equal(plan.scopeComplete, false)
    assert.deepEqual(
      plan.entries.map(({ id, action, reason, scopeComplete }) => ({
        id,
        action,
        reason,
        scopeComplete
      })),
      fixture.definitions.map(({ id }) => ({
        id,
        action: 'SKIP',
        reason: 'COMPLETE',
        scopeComplete: true
      }))
    )
  })

  test(`case-level selection aggregates complete ${fixture.label} evidence`, () => {
    const report = aggregateReport(
      aggregateState(fixture.selection),
      fixture.definitions.map(passingCase)
    )

    assert.equal(report.verdict, 'PARTIAL_PASS')
    assert.equal(report.scopeComplete, false)
    assert.deepEqual(report.issues, [])
    assert.equal(report.counts.PASS, fixture.definitions.length)
  })
}

test('resume planning rejects noncanonical caller and state registries', () => {
  const definition = P0_CASES[0]
  const cases = { [definition.id]: passingCase(definition) }
  const selection = { caseIds: [definition.id] }
  const forgedRequiredSubruns = [
    {
      ...P0_CASES[0],
      requiredSubruns: [{
        ...P0_CASES[0].requiredSubruns[0],
        id: 'desktop-forged-review8'
      }]
    },
    ...P0_CASES.slice(1)
  ]
  const invalidCallers = {
    partial: P0_CASES.slice(0, 1),
    replacement: [
      ...P0_CASES.slice(0, -1),
      { ...P0_CASES.at(-1), id: 'UNKNOWN-99' }
    ],
    duplicate: [...P0_CASES.slice(0, -1), P0_CASES[0]],
    'metadata-drift': [
      { ...P0_CASES[0], executionGroup: 'forged-group' },
      ...P0_CASES.slice(1)
    ],
    'forged-requiredSubruns': forgedRequiredSubruns
  }

  for (const [label, definitions] of Object.entries(invalidCallers)) {
    assert.throws(
      () => planResume(resumeState(cases), definitions, selection),
      /^Error: INVALID_REGISTRY: definitions$/,
      label
    )
  }

  const partialStateDefinitions = P0_CASES.slice(0, 1)
  assert.throws(
    () => planResume({
      definitions: partialStateDefinitions,
      registryFingerprint: registryFingerprint(partialStateDefinitions),
      cases
    }, P0_CASES, selection),
    /^Error: INVALID_REGISTRY: state$/
  )
  assert.throws(
    () => planResume({
      definitions: P0_CASES,
      registryFingerprint: 'forged-registry-fingerprint',
      cases
    }, P0_CASES, selection),
    /^Error: REGISTRY_FINGERPRINT_MISMATCH: state$/
  )

  const valid = planResume(resumeState(cases), P0_CASES, selection)
  assert.equal(valid.filtered, true)
  assert.equal(valid.scopeComplete, false)
  assert.equal(valid.entries.length, 1)
  assert.equal(valid.entries[0].action, 'SKIP')
})

test('resume reruns an entire RUNNING case and every member of its execution group', () => {
  const definitions = P0_CASES.filter(({ id }) => id === 'AUTH-01' || id === 'AUTH-02')
  const state = resumeState({
      'AUTH-01': passingCase(definitions[0]),
      'AUTH-02': {
        ...passingCase(definitions[1]),
        status: 'RUNNING',
        scopeComplete: false,
        subruns: [{ ...definitions[1].requiredSubruns[0], status: 'RUNNING' }]
      }
  })

  const plan = planResume(state, P0_CASES, { caseIds: definitions.map(({ id }) => id) })
  assert.equal(plan.scopeComplete, false)
  assert.deepEqual(plan.entries.map(({ id, action, reason }) => ({ id, action, reason })), [
    { id: 'AUTH-01', action: 'RUN', reason: 'GROUP_RERUN' },
    { id: 'AUTH-02', action: 'RUN', reason: 'RUNNING' }
  ])
  assert.deepEqual(plan.entries[1].subruns, definitions[1].requiredSubruns)
})

test('resume skips only full required-subrun coverage', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const complete = planResume(
    resumeState({ [definition.id]: passingCase(definition) }),
    P0_CASES,
    { caseIds: [definition.id] }
  )
  assert.equal(complete.scopeComplete, false)
  assert.equal(complete.entries[0].action, 'SKIP')

  const partial = passingCase(definition)
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = planResume(
    resumeState({ [definition.id]: partial }),
    P0_CASES,
    { caseIds: [definition.id] }
  )
  assert.equal(incomplete.scopeComplete, false)
  assert.equal(incomplete.entries[0].action, 'RUN')
  assert.equal(incomplete.entries[0].reason, 'INCOMPLETE_SUBRUNS')
  assert.deepEqual(incomplete.entries[0].subruns, definition.requiredSubruns)
})

test('resume reruns PASS evidence whose result id differs from its case key', () => {
  const expected = P0_CASES.find(({ id }) => id === 'AUTH-01')
  const swapped = P0_CASES.find(({ id }) => id === 'AUTH-02')
  const plan = planResume(
    resumeState({ [expected.id]: passingCase(swapped) }),
    P0_CASES,
    { caseIds: [expected.id] }
  )

  assert.equal(plan.scopeComplete, false)
  assert.equal(plan.entries[0].action, 'RUN')
  assert.equal(plan.entries[0].reason, 'INCOMPLETE_SUBRUNS')
})

test('profile and viewport filters are never resumable as complete scope', () => {
  const source = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const funding = source.requiredSubruns.filter(({ profile }) => profile === 'FUNDING_ONLY')
  const sourcePlan = planResume(
    resumeState({
        [source.id]: {
          id: source.id,
          status: 'PASS',
          scopeComplete: false,
          subruns: funding.map((subrun) => ({ ...subrun, status: 'PASS' }))
        }
    }),
    P0_CASES,
    { caseIds: [source.id], profiles: ['FUNDING_ONLY'] }
  )
  assert.equal(sourcePlan.filtered, true)
  assert.equal(sourcePlan.scopeComplete, false)
  assert.equal(sourcePlan.entries[0].action, 'RUN')
  assert.deepEqual(sourcePlan.entries[0].subruns, funding)

  const ui = P0_CASES.find(({ id }) => id === 'UI-02')
  const mobile = ui.requiredSubruns.filter(({ viewport }) => viewport === 'mobile')
  const falselyCompleteMobile = {
    id: ui.id,
    status: 'PASS',
    scopeComplete: true,
    subruns: mobile.map((subrun) => ({ ...subrun, status: 'PASS' }))
  }
  const uiPlan = planResume(
    resumeState({ [ui.id]: falselyCompleteMobile }),
    P0_CASES,
    { caseIds: [ui.id], viewports: ['mobile'] }
  )
  assert.equal(uiPlan.scopeComplete, false)
  assert.equal(uiPlan.entries[0].action, 'RUN')
  assert.deepEqual(uiPlan.entries[0].subruns, mobile)
  const uiReport = aggregateReport(
    aggregateState({ caseIds: [ui.id], viewports: ['mobile'] }),
    [falselyCompleteMobile]
  )
  assert.equal(uiReport.verdict, 'FAIL')
  assert.ok(uiReport.issues.includes(`INCOMPLETE_MATRIX: ${ui.id}`))
})

test('aggregate report reaches PASS only with every required case and subrun', () => {
  const state = aggregateState()
  const completeResults = P0_CASES.map(passingCase)
  const passed = aggregateReport(state, completeResults)
  assert.equal(passed.verdict, 'PASS')
  assert.equal(passed.scopeComplete, true)
  assert.deepEqual(passed.counts, {
    PASS: 60,
    FAIL: 0,
    BLOCKED: 0,
    INVALID_TEST: 0,
    MISSING: 0
  })

  const missing = aggregateReport(
    state,
    completeResults.filter(({ id }) => id !== 'SOURCE-03')
  )
  assert.equal(missing.verdict, 'FAIL')
  assert.equal(missing.scopeComplete, false)
  assert.ok(missing.issues.includes('MISSING_CASE: SOURCE-03'))

  const partial = passingCase(P0_CASES.find(({ id }) => id === 'SOURCE-03'))
  partial.scopeComplete = false
  partial.subruns = partial.subruns.slice(0, 1)
  const incomplete = aggregateReport(state, completeResults.map((result) => (
    result.id === partial.id ? partial : result
  )))
  assert.equal(incomplete.verdict, 'FAIL')
  assert.ok(incomplete.issues.includes('INCOMPLETE_MATRIX: SOURCE-03'))
})

test('valid FAIL and INVALID_TEST outrank BLOCKED in the terminal verdict', () => {
  const definitions = P0_CASES.slice(0, 2)
  const state = aggregateState({ caseIds: definitions.map(({ id }) => id) })

  const failed = aggregateReport(state, [
    { id: definitions[0].id, status: 'BLOCKED', subruns: [] },
    { id: definitions[1].id, status: 'FAIL', subruns: [] }
  ])
  assert.equal(failed.verdict, 'FAIL')

  const blocked = aggregateReport(state, [
    passingCase(definitions[0]),
    { id: definitions[1].id, status: 'BLOCKED', subruns: [] }
  ])
  assert.equal(blocked.verdict, 'BLOCKED')

  const invalid = aggregateReport(state, [
    passingCase(definitions[0]),
    { id: definitions[1].id, status: 'INVALID_TEST', subruns: [] }
  ])
  assert.equal(invalid.verdict, 'FAIL')
})

test('filtered successful evidence is PARTIAL_PASS and never terminal PASS', () => {
  const definition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const selected = definition.requiredSubruns.filter(({ profile }) => profile === 'FUNDING_ONLY')
  const report = aggregateReport(
    aggregateState({ caseIds: [definition.id], profiles: ['FUNDING_ONLY'] }),
    [{
      id: definition.id,
      status: 'PASS',
      scopeComplete: false,
      subruns: selected.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }]
  )

  assert.equal(report.verdict, 'PARTIAL_PASS')
  assert.equal(report.scopeComplete, false)
  assert.deepEqual(report.issues, [])
})

test('cropped scope requires exact false while case selection requires true', () => {
  const croppedDefinition = P0_CASES.find(({ id }) => id === 'SOURCE-03')
  const croppedSelection = {
    caseIds: [croppedDefinition.id],
    profiles: ['FUNDING_ONLY']
  }
  const croppedSubruns = croppedDefinition.requiredSubruns.filter(
    ({ profile }) => profile === 'FUNDING_ONLY'
  )
  const invalidScopes = [
    { label: 'missing' },
    { label: 'null', scopeComplete: null },
    { label: 'string', scopeComplete: 'false' },
    { label: 'number', scopeComplete: 0 },
    { label: 'true', scopeComplete: true }
  ]
  const invalidReports = invalidScopes.map((variant) => {
    const result = {
      id: croppedDefinition.id,
      status: 'PASS',
      subruns: croppedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }
    if (Object.hasOwn(variant, 'scopeComplete')) result.scopeComplete = variant.scopeComplete
    const report = aggregateReport(
      aggregateState(croppedSelection),
      [result]
    )
    return { label: variant.label, verdict: report.verdict, issues: report.issues }
  })
  const falseReport = aggregateReport(
    aggregateState(croppedSelection),
    [{
      id: croppedDefinition.id,
      status: 'PASS',
      scopeComplete: false,
      subruns: croppedSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }]
  )

  const caseDefinition = P0_CASES.find(({ id }) => id === 'AUTH-03')
  const caseSelection = { caseIds: [caseDefinition.id] }
  const completeCase = passingCase(caseDefinition)
  const completeCaseReport = aggregateReport(
    aggregateState(caseSelection),
    [completeCase]
  )
  const completeCasePlan = planResume(
    resumeState({ [caseDefinition.id]: completeCase }),
    P0_CASES,
    caseSelection
  )
  const incompleteCase = { ...completeCase, scopeComplete: false }
  const incompleteCaseReport = aggregateReport(
    aggregateState(caseSelection),
    [incompleteCase]
  )
  const incompleteCasePlan = planResume(
    resumeState({ [caseDefinition.id]: incompleteCase }),
    P0_CASES,
    caseSelection
  )

  assert.deepEqual(invalidReports, invalidScopes.map(({ label }) => ({
    label,
    verdict: 'FAIL',
    issues: [`INCOMPLETE_MATRIX: ${croppedDefinition.id}`]
  })))
  assert.equal(falseReport.verdict, 'PARTIAL_PASS')
  assert.equal(falseReport.scopeComplete, false)
  assert.deepEqual(falseReport.issues, [])
  assert.equal(completeCaseReport.verdict, 'PARTIAL_PASS')
  assert.deepEqual(completeCaseReport.issues, [])
  assert.equal(completeCasePlan.entries[0].action, 'SKIP')
  assert.equal(incompleteCaseReport.verdict, 'FAIL')
  assert.ok(incompleteCaseReport.issues.includes(`INCOMPLETE_MATRIX: ${caseDefinition.id}`))
  assert.equal(incompleteCasePlan.entries[0].action, 'RUN')
})

test('empty filtered selections fail instead of producing zero-evidence partial pass', () => {
  const fixtures = [
    { label: 'unknown caseIds', selection: { caseIds: ['UNKNOWN-99'] } },
    {
      label: 'profile and viewport with no matching subrun',
      selection: { profiles: ['FUNDING_ONLY'], viewports: ['mobile'] }
    }
  ]
  const actual = fixtures.map(({ label, selection }) => {
    const report = aggregateReport(aggregateState(selection), [])
    return {
      label,
      verdict: report.verdict,
      scopeComplete: report.scopeComplete,
      pass: report.counts.PASS,
      issues: report.issues
    }
  })

  assert.deepEqual(actual, fixtures.map(({ label }) => ({
    label,
    verdict: 'FAIL',
    scopeComplete: false,
    pass: 0,
    issues: ['EMPTY_SELECTION']
  })))
})

test('corrupt, duplicate, unexpected or non-terminal evidence cannot pass', () => {
  const registryless = aggregateReport({ selection: {} }, [])
  assert.equal(registryless.verdict, 'FAIL')
  assert.deepEqual(registryless.issues, ['MISSING_REGISTRY'])

  const definition = P0_CASES[0]
  const state = aggregateState({ caseIds: [definition.id] })
  const duplicate = aggregateReport(state, [passingCase(definition), passingCase(definition)])
  assert.equal(duplicate.verdict, 'FAIL')
  assert.ok(duplicate.issues.includes(`DUPLICATE_CASE: ${definition.id}`))

  const unexpected = aggregateReport(state, [
    passingCase(definition),
    { ...passingCase(definition), id: 'UNKNOWN-01' }
  ])
  assert.equal(unexpected.verdict, 'FAIL')
  assert.ok(unexpected.issues.includes('UNEXPECTED_CASE: UNKNOWN-01'))

  const running = aggregateReport(state, [{ id: definition.id, status: 'RUNNING' }])
  assert.equal(running.verdict, 'FAIL')
  assert.ok(running.issues.includes(`INVALID_STATUS: ${definition.id}/RUNNING`))
})

for (const corruptStatus of ['toString', 'constructor', 'ARBITRARY_STATUS']) {
  test(`aggregate rejects evidence status ${corruptStatus}`, () => {
    const definition = P0_CASES[0]
    const report = aggregateReport(
      aggregateState({ caseIds: [definition.id] }),
      [{ id: definition.id, status: corruptStatus }]
    )

    assert.equal(report.verdict, 'FAIL')
    assert.ok(report.issues.includes(`INVALID_STATUS: ${definition.id}/${corruptStatus}`))
  })
}

function writeSurefireSuite(directory, fileName, attributes, modifiedAt = new Date()) {
  mkdirSync(directory, { recursive: true })
  const path = join(directory, fileName)
  const values = {
    tests: 1,
    skipped: 0,
    failures: 0,
    errors: 0,
    ...attributes
  }
  const testcaseCount = Number(values.tests)
  const testcases = Number.isSafeInteger(testcaseCount) && testcaseCount > 0
    ? Array.from({ length: testcaseCount }, (_, index) => (
        `<testcase name="contract-${index + 1}"/>`
      ))
    : []
  writeFileSync(path, [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="${values.name}" tests="${values.tests}" skipped="${values.skipped}" failures="${values.failures}" errors="${values.errors}">`,
    ...testcases,
    '</testsuite>'
  ].join('\n'))
  utimesSync(path, modifiedAt, modifiedAt)
  return path
}

test('Surefire parser rejects empty and mismatched testcase evidence', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-testcase-count-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const suite = (name, tests, children = [], selfClosing = false) => {
    const opening = `<testsuite name="com.fxplatform.${name}" tests="${tests}" skipped="0" failures="0" errors="0"`
    return selfClosing
      ? `${opening}/>`
      : [`${opening}>`, ...children, '</testsuite>'].join('\n')
  }

  const validDirectory = join(root, 'valid')
  mkdirSync(validDirectory)
  writeFileSync(join(validDirectory, 'TEST-valid.xml'), suite('ConcreteIT', 2, [
    '<testcase name="first"/>',
    '<testcase name="second"></testcase>'
  ]))
  const valid = parseSurefireReports(validDirectory, ['ConcreteIT'], startedAt)
  assert.equal(valid.status, 'PASS')
  assert.deepEqual(valid.totals, { tests: 2, skipped: 0, failures: 0, errors: 0 })

  const invalid = [
    { label: 'empty', name: 'EmptyIT', source: suite('EmptyIT', 1) },
    {
      label: 'self-closing',
      name: 'SelfClosingIT',
      source: suite('SelfClosingIT', 1, [], true)
    },
    {
      label: 'undercount',
      name: 'UndercountIT',
      source: suite('UndercountIT', 2, ['<testcase name="only"/>'])
    },
    {
      label: 'overcount',
      name: 'OvercountIT',
      source: suite('OvercountIT', 1, [
        '<testcase name="first"/>',
        '<testcase name="second"/>'
      ])
    },
    {
      label: 'nested-fake',
      name: 'NestedFakeIT',
      source: suite('NestedFakeIT', 1, [
        '<system-out><testcase name="not-a-direct-suite-case"/></system-out>'
      ])
    }
  ]
  for (const fixture of invalid) {
    const directory = join(root, fixture.label)
    mkdirSync(directory)
    writeFileSync(join(directory, `TEST-${fixture.label}.xml`), fixture.source)
    assert.throws(
      () => parseSurefireReports(directory, [fixture.name], startedAt),
      new RegExp(`^Error: SUREFIRE_INVALID_SUITE: ${fixture.name}$`),
      fixture.label
    )
  }
})

test('Surefire XML attributes ignore inherited suite identity and counters', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-inherited-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { writeFileSync } from 'node:fs'

      const { parseSurefireReports } = await import(${JSON.stringify(artifactsUrl)})
      const directory = ${JSON.stringify(directory)}
      const reportPath = directory + '/TEST-PrototypeSuiteIT.xml'
      const startedAt = new Date(Date.now() - 5_000)
      writeFileSync(reportPath, [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<testsuite name="com.fxplatform.PrototypeSuiteIT" tests="1" skipped="0" failures="0" errors="0">',
        '<testcase name="prototype-control"/>',
        '</testsuite>'
      ].join('\\n'))
      assert.equal(
        parseSurefireReports(directory, ['PrototypeSuiteIT'], startedAt).status,
        'PASS'
      )

      writeFileSync(reportPath, '<testsuite></testsuite>')
      const inherited = {
        name: 'com.fxplatform.PrototypeSuiteIT',
        tests: '1',
        skipped: '0',
        failures: '0',
        errors: '0'
      }
      let getterCalls = 0
      let result
      let error
      for (const [field, value] of Object.entries(inherited)) {
        Object.defineProperty(Object.prototype, field, {
          configurable: true,
          get() {
            getterCalls += 1
            return value
          }
        })
      }
      try {
        try {
          result = parseSurefireReports(directory, ['PrototypeSuiteIT'], startedAt)
        } catch (caught) {
          error = caught.message
        }
      } finally {
        for (const field of Object.keys(inherited)) delete Object.prototype[field]
      }

      assert.equal(result?.status, undefined)
      assert.equal(error, 'SUREFIRE_MISSING_CLASS: PrototypeSuiteIT')
      assert.equal(getterCalls, 0)
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
})

test('Surefire parser binds bytes and freshness to one opened report', () => {
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import {
        mkdirSync,
        mkdtempSync,
        renameSync,
        rmSync,
        utimesSync,
        writeFileSync
      } from 'node:fs'
      import { tmpdir } from 'node:os'
      import { join } from 'node:path'
      import { TextDecoder } from 'node:util'

      const { parseSurefireReports } = await import(${JSON.stringify(artifactsUrl)})
      const root = mkdtempSync(join(tmpdir(), 'p0-surefire-path-race-'))
      const reports = join(root, 'reports')
      const reportPath = join(reports, 'TEST-path-race.xml')
      const replacementPath = join(reports, 'replacement.xml.pending')
      const staleBackup = join(reports, 'stale.xml.backup')
      const xml = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<testsuite name="com.fxplatform.PathRaceIT" tests="1" skipped="0" failures="0" errors="0">',
        '<testcase name="path-race-control"/>',
        '</testsuite>'
      ].join('\\n')
      mkdirSync(reports)
      writeFileSync(reportPath, xml)
      writeFileSync(replacementPath, xml)
      const invocationStartedAt = new Date(Date.now() - 5_000)
      const staleTime = new Date(invocationStartedAt.getTime() - 5_000)
      const freshTime = new Date()
      utimesSync(reportPath, staleTime, staleTime)
      utimesSync(replacementPath, freshTime, freshTime)

      const originalDecode = TextDecoder.prototype.decode
      let decodeCalls = 0
      let outcome
      TextDecoder.prototype.decode = function (...arguments_) {
        decodeCalls += 1
        if (decodeCalls === 1) {
          renameSync(reportPath, staleBackup)
          renameSync(replacementPath, reportPath)
        }
        return Reflect.apply(originalDecode, this, arguments_)
      }
      try {
        try {
          outcome = {
            result: parseSurefireReports(
              reports,
              ['PathRaceIT'],
              invocationStartedAt
            )
          }
        } catch (error) {
          outcome = { error: error.message }
        }
      } finally {
        TextDecoder.prototype.decode = originalDecode
        rmSync(root, { recursive: true, force: true })
      }

      assert.equal(decodeCalls, 1)
      assert.notEqual(outcome.result?.status, 'PASS', 'old stale bytes used fresh path metadata')
      assert.match(
        outcome.error ?? '',
        /^SUREFIRE_(?:STALE|UNSTABLE)_REPORT: PathRaceIT$/
      )
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
})

test('Surefire API validates time classes UTF-8 and declaration before trusting reports', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-api-boundary-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const validDirectory = join(root, 'valid')
  writeSurefireSuite(validDirectory, 'TEST-boundary.xml', {
    name: 'com.fxplatform.BoundaryIT'
  })
  const missingDirectory = join(root, 'missing')
  const capture = (run) => {
    try {
      return run()
    } catch (error) {
      return error.message
    }
  }

  const invalidTimes = {
    null: null,
    number: startedAt.getTime(),
    'invalid-date': new Date(Number.NaN),
    'date-only': '2026-07-14',
    'negative-year': '-000001-01-01T00:00:00.000Z',
    'expanded-year': '+010000-01-01T00:00:00.000Z'
  }
  const timeOutcomes = Object.fromEntries(Object.entries(invalidTimes).map(([label, value]) => [
    label,
    capture(() => parseSurefireReports(missingDirectory, ['BoundaryIT'], value).status)
  ]))

  let accessorCalls = 0
  const accessorClasses = []
  Object.defineProperty(accessorClasses, 0, {
    enumerable: true,
    get() {
      accessorCalls += 1
      return 'BoundaryIT'
    }
  })
  let proxyTrapCalls = 0
  const proxyClasses = new Proxy(['BoundaryIT'], {
    get(target, field, receiver) {
      proxyTrapCalls += 1
      return Reflect.get(target, field, receiver)
    }
  })
  const sparseClasses = ['BoundaryIT']
  sparseClasses.length = 2
  const classOutcomes = {
    'empty-duplicates': capture(() => parseSurefireReports(
      missingDirectory,
      ['', ''],
      startedAt
    ).status),
    malformed: capture(() => parseSurefireReports(
      missingDirectory,
      ['Bad.Name'],
      startedAt
    ).status),
    'extra-malformed': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', 'Bad.Name'],
      startedAt
    ).status),
    'undefined-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', undefined],
      startedAt
    ).status),
    'function-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', () => 'OtherIT'],
      startedAt
    ).status),
    'symbol-element': capture(() => parseSurefireReports(
      missingDirectory,
      ['BoundaryIT', Symbol('OtherIT')],
      startedAt
    ).status),
    'sparse-element': capture(() => parseSurefireReports(
      missingDirectory,
      sparseClasses,
      startedAt
    ).status),
    accessor: capture(() => parseSurefireReports(
      missingDirectory,
      accessorClasses,
      startedAt
    ).status),
    proxy: capture(() => parseSurefireReports(
      missingDirectory,
      proxyClasses,
      startedAt
    ).status)
  }

  const invalidUtf8Directory = join(root, 'invalid-utf8')
  mkdirSync(invalidUtf8Directory)
  const invalidUtf8Path = join(invalidUtf8Directory, 'TEST-invalid-utf8.xml')
  writeFileSync(invalidUtf8Path, Buffer.concat([
    Buffer.from('<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.fxplatform.InvalidUtf8IT" tests="1" skipped="0" failures="0" errors="0"><!--'),
    Buffer.from([0xff]),
    Buffer.from('--></testsuite>')
  ]))
  utimesSync(invalidUtf8Path, new Date(), new Date())

  const wrongEncodingDirectory = join(root, 'wrong-encoding')
  mkdirSync(wrongEncodingDirectory)
  const wrongEncodingPath = join(wrongEncodingDirectory, 'TEST-wrong-encoding.xml')
  writeFileSync(wrongEncodingPath, [
    '<?xml version="1.0" encoding="UTF-16"?>',
    '<testsuite name="com.fxplatform.WrongEncodingIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>'
  ].join('\n'))
  utimesSync(wrongEncodingPath, new Date(), new Date())

  const reportOutcomes = {
    invalidUtf8: capture(() => parseSurefireReports(
      invalidUtf8Directory,
      ['InvalidUtf8IT'],
      startedAt
    ).status),
    wrongEncoding: capture(() => parseSurefireReports(
      wrongEncodingDirectory,
      ['WrongEncodingIT'],
      startedAt
    ).status),
    validDate: capture(() => parseSurefireReports(
      validDirectory,
      ['BoundaryIT'],
      startedAt
    ).status),
    validString: capture(() => parseSurefireReports(
      validDirectory,
      ['BoundaryIT'],
      startedAt.toISOString()
    ).status)
  }

  assert.deepEqual(timeOutcomes, Object.fromEntries(
    Object.keys(invalidTimes).map((label) => [label, 'SUREFIRE_INVALID_INVOCATION_TIME'])
  ))
  assert.deepEqual(classOutcomes, {
    'empty-duplicates': 'SUREFIRE_EXPECTED_CLASSES_DUPLICATE: ',
    malformed: 'SUREFIRE_EXPECTED_CLASS_INVALID: Bad.Name',
    'extra-malformed': 'SUREFIRE_EXPECTED_CLASS_INVALID: Bad.Name',
    'undefined-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'function-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'symbol-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    'sparse-element': 'SUREFIRE_EXPECTED_CLASS_INVALID: index 1',
    accessor: 'UNSAFE_PERSISTENCE_VALUE: accessor',
    proxy: 'UNSAFE_PERSISTENCE_VALUE: Proxy'
  })
  assert.equal(accessorCalls, 0)
  assert.equal(proxyTrapCalls, 0)
  assert.deepEqual(reportOutcomes, {
    invalidUtf8: 'SUREFIRE_MALFORMED_XML: TEST-invalid-utf8.xml',
    wrongEncoding: 'SUREFIRE_MALFORMED_XML: TEST-wrong-encoding.xml',
    validDate: 'PASS',
    validString: 'PASS'
  })
})

const MALFORMED_SUREFIRE_REPORTS = {
  unclosed: [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0">'
  ].join('\n'),
  'multiple-roots': [
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    '<testsuite name="com.fxplatform.OtherIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>'
  ].join('\n'),
  'trailing-truncation': [
    '<testsuite name="com.fxplatform.MalformedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    '<testcase'
  ].join('\n')
}

for (const [scenario, source] of Object.entries(MALFORMED_SUREFIRE_REPORTS)) {
  test(`Surefire parser rejects malformed XML: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-malformed-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['MalformedIT'],
        new Date(Date.now() - 5_000)
      ),
      new RegExp(`^Error: SUREFIRE_MALFORMED_XML: ${fileName}$`)
    )
  })
}

const FAKE_OR_MALFORMED_SUREFIRE_REPORTS = {
  'comment-fake-suite': {
    source: [
      '<testsuite name="com.fxplatform.RealIT" tests="1" skipped="0" failures="0" errors="0">',
      '<!-- <testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite> -->',
      '</testsuite>'
    ].join('\n'),
    error: /^Error: SUREFIRE_MISSING_CLASS: ExpectedIT$/
  },
  'cdata-fake-suite': {
    source: [
      '<testsuite name="com.fxplatform.RealIT" tests="1" skipped="0" failures="0" errors="0">',
      '<![CDATA[<testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>]]>',
      '</testsuite>'
    ].join('\n'),
    error: /^Error: SUREFIRE_MISSING_CLASS: ExpectedIT$/
  },
  'valueless-attribute': {
    source: '<testsuite name="com.fxplatform.ExpectedIT" tests="1" skipped failures="0" errors="0"></testsuite>',
    error: /^Error: SUREFIRE_MALFORMED_XML: TEST-valueless-attribute\.xml$/
  },
  'duplicate-attribute': {
    source: '<testsuite name="com.fxplatform.RealIT" name="com.fxplatform.ExpectedIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>',
    error: /^Error: SUREFIRE_MALFORMED_XML: TEST-duplicate-attribute\.xml$/
  }
}

for (const [scenario, fixture] of Object.entries(FAKE_OR_MALFORMED_SUREFIRE_REPORTS)) {
  test(`Surefire parser rejects fake or malformed suite: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-structural-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), fixture.source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['ExpectedIT'],
        new Date(Date.now() - 5_000)
      ),
      fixture.error
    )
  })
}

const INVALID_XML_CHARACTER_DATA_REPORTS = {
  'bare-ampersand': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out>invalid & text</system-out>',
    '</testsuite>'
  ].join('\n'),
  'invalid-named-entity': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out>invalid &notAnXmlEntity; text</system-out>',
    '</testsuite>'
  ].join('\n'),
  'internal-xml-declaration': [
    '<testsuite name="com.fxplatform.CharacterDataIT" tests="1" skipped="0" failures="0" errors="0">',
    '<?xml version="1.0"?>',
    '</testsuite>'
  ].join('\n')
}

for (const [scenario, source] of Object.entries(INVALID_XML_CHARACTER_DATA_REPORTS)) {
  test(`Surefire parser rejects invalid XML character data or internal declaration: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-character-data-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['CharacterDataIT'],
        new Date(Date.now() - 5_000)
      ),
      new RegExp(`^Error: SUREFIRE_MALFORMED_XML: ${fileName}$`)
    )
  })
}

test('Surefire scanner rejects illegal CharData and XML whitespace', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-xml-whitespace-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const suite = (inner = '', openingWhitespace = ' ', attributeWhitespace = ' ', closingWhitespace = '') => [
    `<testsuite${openingWhitespace}name${attributeWhitespace}="com.fxplatform.XmlWhitespaceIT" tests="1" skipped="0" failures="0" errors="0">`,
    inner,
    `</testsuite${closingWhitespace}>`
  ].filter(Boolean).join('\n')
  const invalid = {
    'literal-cdata-close-in-text': suite('<system-out>illegal ]]> text</system-out>'),
    'vertical-tab-opening-whitespace': suite('', '\u000b'),
    'form-feed-attribute-whitespace': suite('', ' ', '\u000c'),
    'vertical-tab-closing-whitespace': suite('', ' ', ' ', '\u000b'),
    'invalid-codepoint-in-markup': suite('', '\u0001')
  }
  const actual = Object.entries(invalid).map(([scenario, source]) => {
    const directory = join(root, scenario)
    mkdirSync(directory, { recursive: true })
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)
    try {
      parseSurefireReports(directory, ['XmlWhitespaceIT'], startedAt)
      return { scenario, error: null }
    } catch (error) {
      return { scenario, error: error.message }
    }
  })

  const validDirectory = join(root, 'valid')
  mkdirSync(validDirectory, { recursive: true })
  writeFileSync(join(validDirectory, 'TEST-valid-whitespace.xml'), [
    '<?xml\tversion="1.0"\r\nencoding="UTF-8"?>',
    '<testsuite \tname = "com.fxplatform.ValidWhitespaceIT"\r tests = "1"\n skipped="0" failures="0" errors="0" >',
    '<!-- valid comment -->',
    '<testcase name="whitespace-control"/>',
    '<system-out><![CDATA[safe <xml> & cdata]]></system-out>',
    '</testsuite \t\r\n>'
  ].join('\n'))
  const valid = parseSurefireReports(validDirectory, ['ValidWhitespaceIT'], startedAt)

  assert.deepEqual(actual, Object.keys(invalid).map((scenario) => ({
    scenario,
    error: `SUREFIRE_MALFORMED_XML: TEST-${scenario}.xml`
  })))
  assert.deepEqual(valid.totals, { tests: 1, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire outcome contract rejects invalid XML code points inside CDATA', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-cdata-codepoint-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const fileName = 'TEST-invalid-cdata-codepoint.xml'
  writeFileSync(join(directory, fileName), [
    '<testsuite name="com.fxplatform.CdataCodePointIT" tests="1" skipped="0" failures="0" errors="0">',
    '<system-out><![CDATA[invalid \u0001 cdata]]></system-out>',
    '</testsuite>'
  ].join('\n'))

  assert.throws(
    () => parseSurefireReports(
      directory,
      ['CdataCodePointIT'],
      new Date(Date.now() - 5_000)
    ),
    /^Error: SUREFIRE_MALFORMED_XML: TEST-invalid-cdata-codepoint\.xml$/
  )
})

for (const outcome of ['failure', 'error', 'skipped']) {
  test(`Surefire outcome contract rejects zero counters with a real ${outcome} element`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-outcome-element-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    writeFileSync(join(directory, `TEST-${outcome}.xml`), [
      '<testsuite name="com.fxplatform.OutcomeElementIT" tests="1" skipped="0" failures="0" errors="0">',
      '<testcase name="contract">',
      `<${outcome}>actual outcome</${outcome}>`,
      '</testcase>',
      '</testsuite>'
    ].join('\n'))

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['OutcomeElementIT'],
        new Date(Date.now() - 5_000)
      ),
      /^Error: SUREFIRE_INVALID_SUITE: OutcomeElementIT$/
    )
  })
}

test('Surefire outcome contract ignores fake outcome text and accepts a zero-outcome suite', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-outcome-text-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  writeFileSync(join(directory, 'TEST-outcome-text.xml'), [
    '<testsuite name="com.fxplatform.OutcomeTextIT" tests="1" skipped="0" failures="0" errors="0">',
    '<testcase name="outcome-text-control"/>',
    '<!-- <failure>comment-only</failure> -->',
    '<system-out>',
    '<![CDATA[<error>cdata-only</error>]]>',
    '&lt;skipped/&gt; escaped-text-only',
    '</system-out>',
    '</testsuite>'
  ].join('\n'))

  const parsed = parseSurefireReports(
    directory,
    ['OutcomeTextIT'],
    new Date(Date.now() - 5_000)
  )

  assert.equal(parsed.status, 'PASS')
  assert.deepEqual(parsed.totals, { tests: 1, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire parser rejects malformed comments DOCTYPE and declarations', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-xml-subset-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const suite = (inner = '') => [
    '<testsuite name="com.fxplatform.XmlSubsetIT" tests="1" skipped="0" failures="0" errors="0">',
    inner,
    '</testsuite>'
  ].filter(Boolean).join('\n')
  const invalid = {
    'comment-double-hyphen': suite('<!-- illegal -- comment -->'),
    'comment-control-codepoint': suite('<!-- illegal \u0001 comment -->'),
    doctype: `<!DOCTYPE >\n${suite()}`,
    'unsupported-version': `<?xml version="2.0"?>\n${suite()}`,
    'unknown-declaration-attribute': `<?xml version="1.0" feature="unsupported"?>\n${suite()}`,
    'invalid-declaration-encoding': `<?xml version="1.0" encoding="UTF 8"?>\n${suite()}`,
    'invalid-declaration-standalone': `<?xml version="1.0" standalone="maybe"?>\n${suite()}`
  }
  const actual = Object.entries(invalid).map(([scenario, source]) => {
    const directory = join(root, scenario)
    mkdirSync(directory, { recursive: true })
    const fileName = `TEST-${scenario}.xml`
    writeFileSync(join(directory, fileName), source)
    try {
      parseSurefireReports(directory, ['XmlSubsetIT'], startedAt)
      return { scenario, error: null }
    } catch (error) {
      return { scenario, error: error.message }
    }
  })

  const validDirectory = join(root, 'valid')
  mkdirSync(validDirectory, { recursive: true })
  writeFileSync(join(validDirectory, 'TEST-xml-10.xml'), [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<testsuite name="com.fxplatform.ValidXml10IT" tests="1" skipped="0" failures="0" errors="0">',
    '<!-- valid-comment -->',
    '<testcase name="xml-10-control"/>',
    '<![CDATA[safe <xml> & raw cdata]]>',
    '</testsuite>'
  ].join('\n'))
  writeFileSync(join(validDirectory, 'TEST-xml-11.xml'), [
    "<?xml version='1.1' encoding='UTF-8' standalone='no'?>",
    '<testsuite name="com.fxplatform.ValidXml11IT" tests="1" skipped="0" failures="0" errors="0">',
    '<testcase name="xml-11-control"/>',
    '</testsuite>'
  ].join('\n'))
  const valid = parseSurefireReports(
    validDirectory,
    ['ValidXml10IT', 'ValidXml11IT'],
    startedAt
  )

  assert.deepEqual(actual, Object.keys(invalid).map((scenario) => ({
    scenario,
    error: `SUREFIRE_MALFORMED_XML: TEST-${scenario}.xml`
  })))
  assert.deepEqual(valid.totals, { tests: 2, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire parser accepts one fresh exact suite for every requested class', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  writeSurefireSuite(directory, 'TEST-db.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT',
    tests: 2
  })
  writeSurefireSuite(directory, 'TEST-fill.xml', {
    name: 'com.fxplatform.Task5PostgresFullFillIT'
  })
  writeSurefireSuite(directory, 'TEST-near-match.xml', {
    name: 'com.fxplatform.PostgresDatabaseITExtra',
    failures: 1
  })

  const parsed = parseSurefireReports(
    directory,
    ['PostgresDatabaseIT', 'Task5PostgresFullFillIT'],
    startedAt
  )
  assert.equal(parsed.status, 'PASS')
  assert.deepEqual(parsed.suites.map(({ className }) => className), [
    'PostgresDatabaseIT',
    'Task5PostgresFullFillIT'
  ])
  assert.deepEqual(parsed.totals, { tests: 3, skipped: 0, failures: 0, errors: 0 })
})

test('Surefire parser rejects missing, duplicate and stale exact suites', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-invalid-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)

  const missingDir = join(root, 'missing')
  writeSurefireSuite(missingDir, 'TEST-near.xml', {
    name: 'com.fxplatform.MissingITExtra'
  })
  assert.throws(
    () => parseSurefireReports(missingDir, ['MissingIT'], startedAt),
    /^Error: SUREFIRE_MISSING_CLASS: MissingIT$/
  )

  const duplicateDir = join(root, 'duplicate')
  writeSurefireSuite(duplicateDir, 'TEST-one.xml', { name: 'a.DuplicateIT' })
  writeSurefireSuite(duplicateDir, 'TEST-two.xml', { name: 'b.DuplicateIT' })
  assert.throws(
    () => parseSurefireReports(duplicateDir, ['DuplicateIT'], startedAt),
    /^Error: SUREFIRE_DUPLICATE_CLASS: DuplicateIT$/
  )

  const staleDir = join(root, 'stale')
  writeSurefireSuite(
    staleDir,
    'TEST-stale.xml',
    { name: 'com.fxplatform.StaleIT' },
    new Date(startedAt.getTime() - 1_000)
  )
  assert.throws(
    () => parseSurefireReports(staleDir, ['StaleIT'], startedAt),
    /^Error: SUREFIRE_STALE_REPORT: StaleIT$/
  )
})

test('Surefire freshness rejects materially future reports but allows timestamp tolerance', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-future-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)

  const futureDir = join(root, 'future')
  writeSurefireSuite(
    futureDir,
    'TEST-future.xml',
    { name: 'com.fxplatform.FutureIT' },
    new Date(Date.now() + 60_000)
  )
  assert.throws(
    () => parseSurefireReports(futureDir, ['FutureIT'], startedAt),
    /^Error: SUREFIRE_FUTURE_REPORT: FutureIT$/
  )

  const toleranceDir = join(root, 'tolerance')
  writeSurefireSuite(
    toleranceDir,
    'TEST-tolerance.xml',
    { name: 'com.fxplatform.TimestampToleranceIT' },
    new Date(Date.now() + 1_000)
  )
  const tolerated = parseSurefireReports(
    toleranceDir,
    ['TimestampToleranceIT'],
    startedAt
  )
  assert.equal(tolerated.status, 'PASS')
})

test('Surefire parser rejects empty, skipped, failed or errored suites', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-counts-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  const invalidCounters = [
    { tests: 0 },
    { skipped: 1 },
    { failures: 1 },
    { errors: 1 }
  ]

  for (const [index, counters] of invalidCounters.entries()) {
    const directory = join(root, String(index))
    writeSurefireSuite(directory, 'TEST-invalid.xml', {
      name: 'com.fxplatform.CounterIT',
      ...counters
    })
    assert.throws(
      () => parseSurefireReports(directory, ['CounterIT'], startedAt),
      /^Error: SUREFIRE_INVALID_SUITE: CounterIT$/
    )
  }
})

const INVALID_SUREFIRE_COUNTER_LEXEMES = {
  'empty-zero-counters': { tests: '1', skipped: '', failures: '', errors: '' },
  'scientific-tests': { tests: '1e0', skipped: '0', failures: '0', errors: '0' }
}

for (const [scenario, counters] of Object.entries(INVALID_SUREFIRE_COUNTER_LEXEMES)) {
  test(`Surefire parser rejects non-decimal counter evidence: ${scenario}`, (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-counter-lexeme-'))
    t.after(() => rmSync(directory, { recursive: true, force: true }))
    writeSurefireSuite(directory, `TEST-${scenario}.xml`, {
      name: 'com.fxplatform.CounterLexicalIT',
      ...counters
    })

    assert.throws(
      () => parseSurefireReports(
        directory,
        ['CounterLexicalIT'],
        new Date(Date.now() - 5_000)
      ),
      /^Error: SUREFIRE_INVALID_SUITE: CounterLexicalIT$/
    )
  })
}

test('Surefire parser requires a unique non-empty expected class list', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-surefire-classes-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const startedAt = new Date(Date.now() - 5_000)
  writeSurefireSuite(directory, 'TEST-duplicate.xml', { name: 'com.fxplatform.DuplicateIT' })

  assert.throws(
    () => parseSurefireReports(directory, [], startedAt),
    /^Error: SUREFIRE_EXPECTED_CLASSES_REQUIRED$/
  )
  assert.throws(
    () => parseSurefireReports(directory, ['DuplicateIT', 'DuplicateIT'], startedAt),
    /^Error: SUREFIRE_EXPECTED_CLASSES_DUPLICATE: DuplicateIT$/
  )
})

test('verify-surefire CLI writes the parser gate contract atomically', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-cli-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gates', 'surefire.json')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-db.xml', { name: 'com.fxplatform.PostgresDatabaseIT' })
  writeSurefireSuite(reports, 'TEST-fill.xml', { name: 'com.fxplatform.Task5PostgresFullFillIT' })

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT,Task5PostgresFullFillIT',
    `--started-at=${startedAt}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  const gate = JSON.parse(readFileSync(output, 'utf8'))
  assert.equal(gate.status, 'PASS')
  assert.deepEqual(gate.expectedClasses, ['PostgresDatabaseIT', 'Task5PostgresFullFillIT'])
  assert.equal(existsSync(`${output}.tmp`), false)
})

test('CLI option accumulation ignores inherited allowed-name accessors', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-option-prototype-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gate.json')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-postgres.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT',
    tests: 2
  })
  writeFileSync(output, '{"status":"PASS","stale":true}\n')
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href

  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import { readFileSync } from 'node:fs'

      let getterCalls = 0
      let setterCalls = 0
      for (const name of ['reports', 'classes', 'started-at', 'output']) {
        Object.defineProperty(Object.prototype, name, {
          configurable: true,
          get() {
            getterCalls += 1
            return 'INHERITED_CLI_OPTION'
          },
          set() {
            setterCalls += 1
          }
        })
      }

      try {
        process.argv = [
          process.execPath,
          ${JSON.stringify(artifactsScript)},
          'verify-surefire',
          '--reports=' + ${JSON.stringify(reports)},
          '--classes=PostgresDatabaseIT',
          '--started-at=' + ${JSON.stringify(startedAt)},
          '--output=' + ${JSON.stringify(output)}
        ]
        await import(${JSON.stringify(artifactsUrl)} + '?cli-option-prototype=1')
      } finally {
        for (const name of ['reports', 'classes', 'started-at', 'output']) {
          delete Object.prototype[name]
        }
        process.exitCode = 0
      }

      const gate = JSON.parse(readFileSync(${JSON.stringify(output)}, 'utf8'))
      assert.equal(getterCalls, 0)
      assert.equal(setterCalls, 0)
      assert.equal(gate.status, 'PASS')
      assert.equal(Object.hasOwn(gate, 'stale'), false)
      assert.deepEqual(gate.expectedClasses, ['PostgresDatabaseIT'])
      assert.deepEqual(gate.totals, {
        tests: 2,
        skipped: 0,
        failures: 0,
        errors: 0
      })
    `
  ], { encoding: 'utf8' })

  assert.equal(
    execution.status,
    0,
    `child failed\nstdout:\n${execution.stdout}\nstderr:\n${execution.stderr}`
  )
})

test('verify-surefire CLI exits nonzero and persists parser rejection evidence', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-surefire-cli-fail-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gates', 'surefire.json')
  const startedAt = new Date()
  writeSurefireSuite(
    reports,
    'TEST-stale.xml',
    { name: 'com.fxplatform.PostgresDatabaseIT' },
    new Date(startedAt.getTime() - 1_000)
  )

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT',
    `--started-at=${startedAt.toISOString()}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'SUREFIRE_STALE_REPORT'
  })
})

test('artifact CLI emits stable secret-free diagnostics for caller-controlled failures', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-safe-diagnostics-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const markers = {
    unknownCommand: 'ITEM5_UNKNOWN_COMMAND_SECRET_19ac',
    malformedOption: 'ITEM5_MALFORMED_OPTION_SECRET_2abd',
    reportsPath: 'ITEM5_REPORTS_PATH_SECRET_3bce',
    outputPath: 'ITEM5_OUTPUT_PATH_SECRET_4cdf',
    xmlFilename: 'ITEM5_XML_FILENAME_SECRET_5de0',
    writeError: 'ITEM5_WRITE_ERROR_SECRET_6ef1'
  }
  const markerValues = Object.values(markers)
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  const assertSecretFree = (value) => {
    for (const marker of markerValues) assert.equal(value.includes(marker), false, marker)
  }
  const execute = (...arguments_) => spawnSync(process.execPath, [
    artifactsScript,
    ...arguments_
  ], { encoding: 'utf8' })
  const assertFailure = (execution, code, output) => {
    assert.equal(execution.status, 1)
    assert.match(execution.stderr, new RegExp(`(?:^|\\n)${code}(?:\\n|$)`))
    assertSecretFree(execution.stderr)
    if (!output) return
    const source = readFileSync(output, 'utf8')
    assertSecretFree(source)
    assert.deepEqual(JSON.parse(source), { status: 'FAIL', error: code })
  }

  const unknownOutput = join(root, 'unknown.json')
  assertFailure(
    execute(`unknown-${markers.unknownCommand}`, `--output=${unknownOutput}`),
    'CLI_UNKNOWN_COMMAND',
    unknownOutput
  )

  const malformedOutput = join(root, 'malformed.json')
  assertFailure(
    execute('verify-surefire', `--malformed-${markers.malformedOption}`, `--output=${malformedOutput}`),
    'CLI_MALFORMED_OPTION',
    malformedOutput
  )

  const missingReports = join(root, `reports-${markers.reportsPath}`)
  const pathOutput = join(root, `output-${markers.outputPath}`, 'gate.json')
  assertFailure(execute(
    'verify-surefire',
    `--reports=${missingReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${pathOutput}`
  ), 'CLI_INTERNAL_ERROR', pathOutput)

  const malformedReports = join(root, 'malformed-reports')
  mkdirSync(malformedReports)
  writeFileSync(join(malformedReports, `TEST-${markers.xmlFilename}.xml`), '<testsuite')
  const xmlOutput = join(root, 'malformed-xml.json')
  assertFailure(execute(
    'verify-surefire',
    `--reports=${malformedReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${xmlOutput}`
  ), 'SUREFIRE_MALFORMED_XML', xmlOutput)

  const validReports = join(root, 'valid-reports')
  writeSurefireSuite(validReports, 'TEST-safe-diagnostic.xml', {
    name: 'com.fxplatform.SafeDiagnosticIT'
  })
  const blockedParent = join(root, `blocked-${markers.writeError}`)
  writeFileSync(blockedParent, 'not-a-directory')
  const blockedOutput = join(blockedParent, 'gate.json')
  const writeExecution = execute(
    'verify-surefire',
    `--reports=${validReports}`,
    '--classes=SafeDiagnosticIT',
    `--started-at=${startedAt}`,
    `--output=${blockedOutput}`
  )
  assertFailure(writeExecution, 'CLI_WRITE_FAILED')
  assert.equal(existsSync(blockedOutput), false)
})

test('output terminal PASS checks require an own status', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'p0-output-own-status-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const output = join(directory, 'gate.json')
  const marker = 'INHERITED_OUTPUT_STATUS_SECRET_92e4'
  writeFileSync(output, '{}\n')
  const artifactsUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `
      import assert from 'node:assert/strict'
      import fs from 'node:fs'
      import { syncBuiltinESMExports } from 'node:module'

      await import(${JSON.stringify(artifactsUrl)})
      const output = ${JSON.stringify(output)}
      const originalReadFileSync = fs.readFileSync
      fs.readFileSync = function (input, ...arguments_) {
        if (String(input) === output && arguments_[0] === 'utf8') return '{}'
        return Reflect.apply(originalReadFileSync, this, [input, ...arguments_])
      }
      syncBuiltinESMExports()

      let getterCalls = 0
      Object.defineProperty(Object.prototype, 'status', {
        configurable: true,
        get() {
          getterCalls += 1
          return 'PASS'
        }
      })
      try {
        process.argv = [
          process.execPath,
          ${JSON.stringify(artifactsScript)},
          'verify-surefire',
          '--output=' + output,
          '--unknown=token=' + ${JSON.stringify(marker)}
        ]
        await import(${JSON.stringify(artifactsUrl)} + '?own-output-status=1')
        process.exitCode = 0
      } finally {
        delete Object.prototype.status
        fs.readFileSync = originalReadFileSync
        syncBuiltinESMExports()
      }

      const source = originalReadFileSync(output, 'utf8')
      assert.equal(getterCalls, 0)
      assert.equal(source.includes(${JSON.stringify(marker)}), false)
      assert.equal(JSON.parse(source).status, 'FAIL')
    `
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
})

test('strict artifact CLI rejects a missing command', () => {
  const execution = spawnSync(process.execPath, [artifactsScript], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.match(execution.stderr, /CLI_COMMAND_REQUIRED/)
})

test('strict artifact CLI rejects an unknown command and replaces a supplied gate', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-unknown-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const output = join(root, 'gate.json')
  writeFileSync(output, '{"status":"PASS"}\n')

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'typo-command',
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_UNKNOWN_COMMAND'
  })
})

const INVALID_CLI_OPTION_FIXTURES = [
  {
    label: 'malformed option',
    option: '--reports',
    error: 'CLI_MALFORMED_OPTION'
  },
  {
    label: 'duplicate option',
    option: '--classes=StrictCliIT',
    error: 'CLI_DUPLICATE_OPTION'
  },
  {
    label: 'unknown option',
    option: '--extra=value',
    error: 'CLI_UNKNOWN_OPTION'
  },
  {
    label: 'empty option',
    option: '--classes=',
    error: 'CLI_OPTION_REQUIRED'
  }
]

for (const fixture of INVALID_CLI_OPTION_FIXTURES) {
  test(`strict artifact CLI rejects ${fixture.label} and replaces stale PASS`, (t) => {
    const root = mkdtempSync(join(tmpdir(), 'p0-cli-options-'))
    t.after(() => rmSync(root, { recursive: true, force: true }))
    const reports = join(root, 'reports')
    const output = join(root, 'gate.json')
    const startedAt = new Date(Date.now() - 5_000).toISOString()
    writeSurefireSuite(reports, 'TEST-strict.xml', { name: 'com.fxplatform.StrictCliIT' })
    writeFileSync(output, '{"status":"PASS"}\n')
    const options = [
      `--reports=${reports}`,
      '--classes=StrictCliIT',
      `--started-at=${startedAt}`,
      `--output=${output}`,
      fixture.option
    ]
    if (fixture.option === '--reports') options.shift()
    if (fixture.option === '--classes=') options.splice(1, 1)

    const execution = spawnSync(process.execPath, [
      artifactsScript,
      'verify-surefire',
      ...options
    ], { encoding: 'utf8' })

    assert.equal(execution.status, 1)
    assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
      status: 'FAIL',
      error: fixture.error
    })
  })
}

test('strict artifact CLI writes duplicate normalized output failure only to a unique target', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-duplicate-output-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const output = join(root, 'gate.json')
  const equivalentOutput = `${join(root, 'nested')}${sep}..${sep}gate.json`
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-output.xml', { name: 'com.fxplatform.OutputCliIT' })
  writeFileSync(output, '{"status":"PASS"}\n')

  const duplicateExecution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=OutputCliIT',
    `--started-at=${startedAt}`,
    `--output=${output}`,
    `--output=${equivalentOutput}`
  ], { encoding: 'utf8' })

  assert.equal(duplicateExecution.status, 1)
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })

  const firstOutput = join(root, 'first.json')
  const secondOutput = join(root, 'second.json')
  const firstBaseline = '{"status":"PASS","target":"first"}\n'
  const secondBaseline = '{"status":"PASS","target":"second"}\n'
  writeFileSync(firstOutput, firstBaseline)
  writeFileSync(secondOutput, secondBaseline)
  const conflictingExecution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=OutputCliIT',
    `--started-at=${startedAt}`,
    `--output=${firstOutput}`,
    `--output=${secondOutput}`
  ], { encoding: 'utf8' })

  assert.equal(conflictingExecution.status, 1)
  assert.equal(readFileSync(firstOutput, 'utf8'), firstBaseline)
  assert.equal(readFileSync(secondOutput, 'utf8'), secondBaseline)
})

test('strict artifact CLI replaces every hardlink alias with duplicate output failure', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-hardlink-output-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-hardlink.xml', {
    name: 'com.fxplatform.PostgresDatabaseIT'
  })
  const firstOutput = join(root, 'hardlink-a.json')
  const secondOutput = join(root, 'hardlink-b.json')
  const stalePass = '{"status":"PASS","stale":"hardlink"}\n'
  writeFileSync(firstOutput, stalePass)
  linkSync(firstOutput, secondOutput)

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=PostgresDatabaseIT',
    `--started-at=${startedAt}`,
    `--output=${firstOutput}`,
    `--output=${secondOutput}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1, execution.stderr)
  for (const output of [firstOutput, secondOutput]) {
    const source = readFileSync(output, 'utf8')
    assert.notEqual(source, stalePass)
    assert.deepEqual(JSON.parse(source), {
      status: 'FAIL',
      error: 'CLI_DUPLICATE_OPTION'
    })
  }
})

test('strict artifact CLI invalidates every hardlink alias before best-effort failure writes', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-hardlink-failure-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const marker = 'ITEM5_HARDLINK_DIAGNOSTIC_SECRET'
  const firstOutput = join(root, `hardlink-first-${marker}.json`)
  const longName = `${marker}-${'x'.repeat(220 - marker.length - 6)}.json`
  const secondOutput = join(root, longName)
  const stalePass = '{"status":"PASS","stale":"hardlink-failure"}\n'
  writeFileSync(firstOutput, stalePass)
  linkSync(firstOutput, secondOutput)

  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${join(root, 'unused-reports')}`,
    '--classes=HardlinkFailureIT',
    `--started-at=${new Date().toISOString()}`,
    `--output=${firstOutput}`,
    `--output=${secondOutput}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 1)
  assert.equal(execution.stderr.includes(marker), false)
  for (const output of [firstOutput, secondOutput]) {
    const evidence = JSON.parse(readFileSync(output, 'utf8'))
    assert.notEqual(evidence.status, 'PASS', output)
  }
})

test('strict artifact CLI writes failure through the supplied lexical alias after redirect', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-redirect-output-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const marker = 'ITEM5_REDIRECT_DIAGNOSTIC_SECRET'
  const reports = join(root, 'reports')
  const hookPath = join(root, 'redirect-hook.mjs')
  const redirectMarker = join(root, 'redirect-complete.txt')
  const firstDirectory = join(root, `first-${marker}`)
  const secondDirectory = join(root, `second-${marker}`)
  mkdirSync(reports)
  mkdirSync(firstDirectory)
  mkdirSync(secondDirectory)
  writeFileSync(join(reports, `TEST-${marker}.xml`), '<testsuite')
  const firstTarget = join(firstDirectory, 'gate.json')
  const secondTarget = join(secondDirectory, 'gate.json')
  writeFileSync(firstTarget, '{"status":"PASS","target":"first"}\n')
  writeFileSync(secondTarget, '{"status":"PASS","target":"second"}\n')

  let aliasPath
  let lexicalOutput
  let originalTarget
  let redirectTarget
  if (process.platform === 'win32') {
    aliasPath = join(root, 'output-alias')
    symlinkSync(firstDirectory, aliasPath, 'junction')
    lexicalOutput = join(aliasPath, 'gate.json')
    originalTarget = firstDirectory
    redirectTarget = secondDirectory
  } else {
    aliasPath = join(root, 'gate-alias.json')
    symlinkSync(firstTarget, aliasPath, 'file')
    lexicalOutput = aliasPath
    originalTarget = firstTarget
    redirectTarget = secondTarget
  }
  for (const path of [aliasPath, originalTarget, redirectTarget]) {
    const relativePath = relative(resolve(root), resolve(path))
    assert.ok(
      relativePath
      && relativePath !== '..'
      && !relativePath.startsWith(`..${sep}`)
      && !isAbsolute(relativePath),
      path
    )
  }
  writeFileSync(hookPath, [
    "import fs, { rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'",
    "import { syncBuiltinESMExports } from 'node:module'",
    "import { isAbsolute, relative, resolve, sep } from 'node:path'",
    'const originalReadFileSync = fs.readFileSync',
    'let redirected = false',
    'fs.readFileSync = function (input, ...arguments_) {',
    '  const result = Reflect.apply(originalReadFileSync, this, [input, ...arguments_])',
    "  if (!redirected && typeof input === 'number') {",
    '    redirected = true',
    '    fs.readFileSync = originalReadFileSync',
    '    syncBuiltinESMExports()',
    '    const root = resolve(process.env.P0_TEMP_ROOT)',
    '    for (const path of [',
    '      process.env.P0_OUTPUT_ALIAS,',
    '      process.env.P0_ORIGINAL_TARGET,',
    '      process.env.P0_REDIRECT_TARGET',
    '    ]) {',
    '      const relativePath = relative(root, resolve(path))',
    "      if (!relativePath || relativePath === '..'",
    "        || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath)) {",
    "        throw new Error('TEST_REDIRECT_OUTSIDE_TEMP_ROOT')",
    '      }',
    '    }',
    "    if (process.platform === 'win32') {",
    '      rmSync(process.env.P0_OUTPUT_ALIAS, { recursive: true, force: true })',
    "      symlinkSync(process.env.P0_REDIRECT_TARGET, process.env.P0_OUTPUT_ALIAS, 'junction')",
    '    } else {',
    '      unlinkSync(process.env.P0_OUTPUT_ALIAS)',
    "      symlinkSync(process.env.P0_REDIRECT_TARGET, process.env.P0_OUTPUT_ALIAS, 'file')",
    '    }',
    "    writeFileSync(process.env.P0_REDIRECT_MARKER, 'redirected')",
    '  }',
    '  return result',
    '}',
    'syncBuiltinESMExports()'
  ].join('\n'))

  const execution = spawnSync(process.execPath, [
    '--import',
    pathToFileURL(hookPath).href,
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=RedirectOutputIT',
    `--started-at=${new Date(Date.now() - 5_000).toISOString()}`,
    `--output=${lexicalOutput}`
  ], {
    encoding: 'utf8',
    env: {
      ...process.env,
      P0_TEMP_ROOT: root,
      P0_OUTPUT_ALIAS: aliasPath,
      P0_ORIGINAL_TARGET: originalTarget,
      P0_REDIRECT_MARKER: redirectMarker,
      P0_REDIRECT_TARGET: redirectTarget
    }
  })

  assert.equal(existsSync(redirectMarker), true, execution.stderr)
  assert.equal(readFileSync(redirectMarker, 'utf8'), 'redirected')
  assert.equal(JSON.parse(readFileSync(secondTarget, 'utf8')).status, 'FAIL')
  assert.equal(execution.status, 1)
  assert.equal(execution.stderr.includes(marker), false)
  assert.equal(JSON.parse(readFileSync(lexicalOutput, 'utf8')).status, 'FAIL')
})

test('strict artifact CLI resolves output filesystem identities without guessing targets', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-output-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  writeSurefireSuite(reports, 'TEST-identity.xml', {
    name: 'com.fxplatform.IdentityCliIT'
  })
  const baseArguments = [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=IdentityCliIT',
    `--started-at=${startedAt}`
  ]
  const execute = (...outputArguments) => spawnSync(process.execPath, [
    ...baseArguments,
    ...outputArguments
  ], { encoding: 'utf8' })

  const identityDirectory = join(root, 'identity-target')
  const identityOutput = join(identityDirectory, 'gate.json')
  mkdirSync(identityDirectory)
  writeFileSync(identityOutput, '{"status":"PASS","stale":"identity"}\n')
  let identityAliases
  if (process.platform === 'win32') {
    identityAliases = [identityOutput, identityOutput.toUpperCase()]
  } else {
    const firstParentAlias = join(root, 'identity-parent-a')
    const secondParentAlias = join(root, 'identity-parent-b')
    symlinkSync(identityDirectory, firstParentAlias, 'dir')
    symlinkSync(identityDirectory, secondParentAlias, 'dir')
    identityAliases = [
      join(firstParentAlias, 'gate.json'),
      join(secondParentAlias, 'gate.json')
    ]
  }
  const identityExecution = execute(
    `--output=${identityAliases[0]}`,
    `--output=${identityAliases[1]}`
  )

  const symlinkDirectory = join(root, 'symlink-target')
  const symlinkTarget = join(symlinkDirectory, 'gate.json')
  mkdirSync(symlinkDirectory)
  writeFileSync(symlinkTarget, '{"status":"PASS","stale":"symlink"}\n')
  let symlinkOutput
  if (process.platform === 'win32') {
    const symlinkParent = join(root, 'symlink-parent')
    symlinkSync(symlinkDirectory, symlinkParent, 'junction')
    symlinkOutput = join(symlinkParent, 'gate.json')
  } else {
    symlinkOutput = join(root, 'gate-link.json')
    symlinkSync(symlinkTarget, symlinkOutput, 'file')
  }
  const symlinkExecution = execute(
    `--output=${symlinkOutput}`,
    `--output=${symlinkTarget}`
  )

  const missingOutputExecution = execute()
  const malformedOutputExecution = execute('--output')

  const normalDirectory = join(root, 'normal-target')
  const normalParentAlias = join(root, 'normal-parent')
  const normalTarget = join(normalDirectory, 'gate.json')
  mkdirSync(normalDirectory)
  symlinkSync(
    normalDirectory,
    normalParentAlias,
    process.platform === 'win32' ? 'junction' : 'dir'
  )
  const normalExecution = execute(`--output=${join(normalParentAlias, 'gate.json')}`)

  assert.equal(identityExecution.status, 1, identityExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(identityOutput, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })
  assert.equal(symlinkExecution.status, 1, symlinkExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(symlinkTarget, 'utf8')), {
    status: 'FAIL',
    error: 'CLI_DUPLICATE_OPTION'
  })
  assert.equal(missingOutputExecution.status, 1)
  assert.match(missingOutputExecution.stderr, /CLI_OPTION_REQUIRED/)
  assert.equal(malformedOutputExecution.status, 1)
  assert.match(malformedOutputExecution.stderr, /CLI_MALFORMED_OPTION/)
  assert.equal(normalExecution.status, 0, normalExecution.stderr)
  assert.equal(JSON.parse(readFileSync(normalTarget, 'utf8')).status, 'PASS')
})

test('strict artifact CLI requires canonical started-at UTC instant', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-started-at-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const reports = join(root, 'reports')
  writeSurefireSuite(reports, 'TEST-started-at.xml', {
    name: 'com.fxplatform.StartedAtCliIT'
  })
  const invalidValues = [
    '0',
    '-000001-01-01T00:00:00.000Z',
    '+010000-01-01T00:00:00.000Z',
    '2026-07-14',
    '2026-07-14T12:34:56.789',
    '2026-07-14T12:34:56.789+08:00',
    '2026-07-14T12:34:56Z'
  ]

  for (const [index, startedAt] of invalidValues.entries()) {
    const output = join(root, `invalid-${index}.json`)
    writeFileSync(output, '{"status":"PASS"}\n')
    const execution = spawnSync(process.execPath, [
      artifactsScript,
      'verify-surefire',
      `--reports=${reports}`,
      '--classes=StartedAtCliIT',
      `--started-at=${startedAt}`,
      `--output=${output}`
    ], { encoding: 'utf8' })

    assert.equal(execution.status, 1, startedAt)
    assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')), {
      status: 'FAIL',
      error: 'CLI_INVALID_OPTION'
    }, startedAt)
  }

  const output = join(root, 'canonical.json')
  writeSurefireSuite(reports, 'TEST-started-at.xml', {
    name: 'com.fxplatform.StartedAtCliIT'
  })
  const startedAt = new Date(Date.now() - 5_000).toISOString()
  const execution = spawnSync(process.execPath, [
    artifactsScript,
    'verify-surefire',
    `--reports=${reports}`,
    '--classes=StartedAtCliIT',
    `--started-at=${startedAt}`,
    `--output=${output}`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).status, 'PASS')
})

test('strict artifact CLI executes through a filesystem alias', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-cli-entry-alias-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const aliasDirectory = join(root, 'scripts-alias')
  symlinkSync(
    dirname(artifactsScript),
    aliasDirectory,
    process.platform === 'win32' ? 'junction' : 'dir'
  )
  const aliasScript = join(aliasDirectory, 'p0-user-trading-artifacts.mjs')
  const startedAt = new Date(Date.now() - 5_000).toISOString()

  const invalidReports = join(root, 'invalid-reports')
  const invalidOutput = join(root, 'invalid-gate.json')
  mkdirSync(invalidReports)
  writeFileSync(join(invalidReports, 'TEST-alias-malformed.xml'), '<testsuite')
  writeFileSync(invalidOutput, '{"status":"PASS","stale":true}\n')
  const invalidExecution = spawnSync(process.execPath, [
    aliasScript,
    'verify-surefire',
    `--reports=${invalidReports}`,
    '--classes=AliasCliIT',
    `--started-at=${startedAt}`,
    `--output=${invalidOutput}`
  ], { encoding: 'utf8' })

  const validReports = join(root, 'valid-reports')
  const validOutput = join(root, 'valid-gate.json')
  writeSurefireSuite(validReports, 'TEST-alias.xml', {
    name: 'com.fxplatform.AliasCliIT'
  })
  const validExecution = spawnSync(process.execPath, [
    aliasScript,
    'verify-surefire',
    `--reports=${validReports}`,
    '--classes=AliasCliIT',
    `--started-at=${startedAt}`,
    `--output=${validOutput}`
  ], { encoding: 'utf8' })

  assert.equal(invalidExecution.status, 1, invalidExecution.stderr)
  assert.deepEqual(JSON.parse(readFileSync(invalidOutput, 'utf8')), {
    status: 'FAIL',
    error: 'SUREFIRE_MALFORMED_XML'
  })
  assert.equal(validExecution.status, 0, validExecution.stderr)
  assert.equal(JSON.parse(readFileSync(validOutput, 'utf8')).status, 'PASS')
})

test('strict artifact CLI module remains import-safe', () => {
  const moduleUrl = new URL('./p0-user-trading-artifacts.mjs', import.meta.url).href
  const execution = spawnSync(process.execPath, [
    '--input-type=module',
    '--eval',
    `await import(${JSON.stringify(moduleUrl)})`
  ], { encoding: 'utf8' })

  assert.equal(execution.status, 0, execution.stderr)
  assert.equal(execution.stdout, '')
  assert.equal(execution.stderr, '')
})

test('Redis acquire crash cutpoints recover owner before snapshot without business restore', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s8-redis-cutpoints-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const signal = AbortSignal.timeout(30_000)
  const scenarios = [
    { id: 'acquire-armed', redisState: 'ACQUIRE_ARMED', recovery: null, expectedRestores: 0 },
    { id: 'owned-no-snapshot', redisState: 'OWNED', recovery: null, expectedRestores: 0 },
    {
      id: 'owned-snapshot-written',
      redisState: 'OWNED',
      recovery: {
        snapshot: [{
          key: 'quote:BTCUSDT',
          exists: true,
          value: 'before-crash',
          expiresAtMs: 1_900_000_000_000
        }],
        touchedKeys: ['quote:BTCUSDT']
      },
      expectedRestores: 1
    }
  ]

  for (const scenario of scenarios) {
    const artifactBase = join(root, scenario.id, 'artifacts')
    const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
    const runId = `p0-review1-s8-${scenario.id}-a1`
    const runToken = `p0-review1-s8-${scenario.id}-owner-token-a1`
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: 'discovery',
      selection: [],
      database: {}
    })
    const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
    active.redisState = scenario.redisState
    writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
    if (scenario.recovery) {
      markP0RedisSnapshotReady(
        control,
        scenario.recovery.snapshot,
        scenario.recovery.touchedKeys,
        scenario.redisState
      )
    }
    const composeIdentity = {
      project: 'infra',
      postgres: {
        id: 'a'.repeat(64),
        image: 'postgres:16',
        host: '127.0.0.1',
        hostPort: 5432
      },
      redis: {
        id: 'b'.repeat(64),
        image: 'redis:7',
        host: '127.0.0.1',
        hostPort: 6379
      }
    }
    const persisted = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
    persisted.composeTarget = {
      expectedProject: composeTarget.expectedProject,
      composeFiles: [...composeTarget.composeFiles]
    }
    persisted.composeIdentity = composeIdentity
    writeFileSync(control.ownershipPath, `${JSON.stringify(persisted, null, 2)}\n`)

    const events = []
    let owner = runToken
    let receipt = null
    const receiptKey = `p0:e2e:cleanup:${control.ownerId}`
    const redis = {
      async get(key, details = {}) {
        assert.equal(details.signal, signal)
        events.push(`redis:get:${key}`)
        return key === 'p0:e2e:owner' ? owner : receipt
      },
      async restoreExact(key, value, expiresAtMs, details = {}) {
        assert.equal(details.signal, signal)
        events.push(`redis:restore:${key}:${value}:${expiresAtMs}`)
      },
      async deleteExact(key, details = {}) {
        assert.equal(details.signal, signal)
        events.push(`redis:delete:${key}`)
      },
      async releaseOwnershipWithReceipt(input) {
        assert.equal(input.signal, signal)
        assert.deepEqual({
          ownerKey: input.ownerKey,
          receiptKey: input.receiptKey,
          runToken: input.runToken,
          ownerId: input.ownerId
        }, {
          ownerKey: 'p0:e2e:owner',
          receiptKey,
          runToken,
          ownerId: control.ownerId
        })
        events.push('redis:release')
        if (owner !== runToken) return 'MISMATCH'
        owner = null
        receipt = control.ownerId
        return 'RELEASED'
      },
      async removeCleanupReceipt(input) {
        assert.equal(input.signal, signal)
        assert.equal(input.receiptKey, receiptKey)
        assert.equal(input.ownerId, control.ownerId)
        events.push('redis:receipt-remove')
        if (receipt === control.ownerId) {
          receipt = null
          return 'REMOVED'
        }
        return receipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
      }
    }
    const runCommand = async (descriptor) => {
      events.push(`docker:${descriptor.id}`)
      if (descriptor.id === 'docker-context-inspect') {
        return { status: 0, stdout: 'npipe:////./pipe/docker_engine\n' }
      }
      if (descriptor.id === 'compose-postgres-id') {
        return { status: 0, stdout: `${composeIdentity.postgres.id}\n` }
      }
      if (descriptor.id === 'compose-redis-id') {
        return { status: 0, stdout: `${composeIdentity.redis.id}\n` }
      }
      if (descriptor.id === 'compose-postgres-inspect') {
        return {
          status: 0,
          stdout: s10CleanupDockerInspection({
            ...composeIdentity.postgres,
            service: 'postgres',
            port: 5432,
            composeFiles: composeTarget.composeFiles
          })
        }
      }
      if (descriptor.id === 'compose-redis-inspect') {
        return {
          status: 0,
          stdout: s10CleanupDockerInspection({
            ...composeIdentity.redis,
            service: 'redis',
            port: 6379,
            composeFiles: composeTarget.composeFiles
          })
        }
      }
      throw new Error(`P0_TEST_UNEXPECTED_COMMAND: ${descriptor.id}`)
    }
    const infrastructure = smokeContracts.createLocalInfrastructureAdapter(
      composeTarget.composeFiles[0],
      runCommand,
      {
        portIsOpen: async () => {
          events.push('ports:free')
          return false
        }
      }
    )
    const dependencies = smokeContracts.createDefaultP0Dependencies({
      artifactBase,
      inheritedEnv: {},
      processTreeProvider: createCleanupOnlyProcessTreeProvider(
        `redis-acquire-cutpoint:${scenario.id}`
      ),
      redis,
      processManager: {
        async stopParentBackend(details = {}) {
          assert.equal(details.signal, signal)
          events.push('process:stop')
        }
      },
      infrastructure,
      postgres: {
        async readDatabaseOwnership() {
          throw new Error('UNEXPECTED_DATABASE_READ')
        }
      }
    })

    const result = await dependencies.cleanup(undefined, { signal }, { runId })
    assert.equal(result.status, 'CLEANED')
    assert.equal(owner, null)
    assert.equal(receipt, null)
    assert.equal(events[0], 'process:stop')
    assert.ok(events.indexOf('process:stop') < events.indexOf('redis:release'))
    const restores = events.filter((event) => (
      event.startsWith('redis:restore:') || event.startsWith('redis:delete:')
    ))
    assert.equal(restores.length, scenario.expectedRestores)
    if (scenario.expectedRestores === 0) {
      assert.equal(events.some((event) => event.startsWith('redis:restore:')), false)
      assert.equal(events.some((event) => event.startsWith('redis:delete:')), false)
    }
  }
})

test('CLEANED retries exact cleanup receipt removal after prior failure', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s8-receipt-retry-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s8-receipt-retry-a1'
  const runToken = 'p0-review1-s8-receipt-retry-owner-token-a1'
  const signal = AbortSignal.timeout(30_000)
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: {}
  })
  markP0RedisSnapshotReady(control)
  const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)
  const receiptKey = `p0:e2e:cleanup:${control.ownerId}`
  let owner = runToken
  let receipt = null
  let removalAttempts = 0
  const redis = {
    async get(key, details = {}) {
      assert.equal(details.signal, signal)
      return key === 'p0:e2e:owner' ? owner : receipt
    },
    async releaseOwnershipWithReceipt(input) {
      assert.equal(input.signal, signal)
      owner = null
      receipt = control.ownerId
      return 'RELEASED'
    },
    async removeCleanupReceipt(input) {
      assert.equal(input.signal, signal)
      assert.equal(input.receiptKey, receiptKey)
      assert.equal(input.ownerId, control.ownerId)
      removalAttempts += 1
      if (removalAttempts === 1) throw new Error('INJECTED_RECEIPT_REMOVAL_FAILURE')
      if (receipt === control.ownerId) {
        receipt = null
        return 'REMOVED'
      }
      return receipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
    }
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      'cleanup-receipt-removal-replay'
    ),
    redis,
    processManager: { async stopParentBackend() {} },
    infrastructure: createStrictCleanupComposeInfrastructure(cleanupProof, {
      expectedSignal: signal
    }),
    postgres: { async readDatabaseOwnership() { return null } },
    now: () => '2026-07-15T16:00:00.000Z'
  })
  const context = {
    artifactBase,
    runRoot: control.runRoot,
    ownerToken: runToken,
    ownerId: control.ownerId,
    redisSnapshot: [],
    touchedRedisKeys: []
  }

  await assert.rejects(
    dependencies.cleanup(context, { signal }, { runId }),
    /INJECTED_RECEIPT_REMOVAL_FAILURE/
  )
  assert.equal(receipt, control.ownerId)
  assert.equal(removalAttempts, 1)
  const cleaned = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.equal(cleaned.schemaVersion, 2)
  assert.equal(cleaned.status, 'CLEANED')
  assert.equal(cleaned.ownerId, control.ownerId)
  assert.deepEqual(cleaned.composeTarget, cleanupProof.composeTarget)
  assert.deepEqual(cleaned.composeIdentity, cleanupProof.composeIdentity)
  assert.deepEqual(cleaned.receipt, {
    key: receiptKey,
    state: 'PENDING',
    completedAt: null
  })

  assert.deepEqual(await dependencies.cleanup(context, { signal }, { runId }), {
    status: 'CLEANED',
    alreadyCleaned: true
  })
  assert.equal(removalAttempts, 2)
  assert.equal(receipt, null)
  const completed = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  assert.equal(completed.receipt.key, receiptKey)
  assert.equal(completed.receipt.state, 'COMPLETED')
  assert.equal(completed.receipt.completedAt, '2026-07-15T16:00:00.000Z')
})

test('unknown PostgreSQL and Redis listeners fail before control or compose mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s8-unknown-listeners-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const composeFile = join(dirname(artifactsScript), '..', 'infra', 'docker-compose.yml')
  const identity = review1P0Identity()

  for (const occupiedPort of [5432, 6379]) {
    const artifactBase = join(root, String(occupiedPort), 'artifacts')
    const runId = `p0-review1-s8-unknown-${occupiedPort}-a1`
    const options = p0CaseContracts.parseP0Cli([
      '--suite=p0',
      `--run-id=${runId}`,
      '--phase=selected',
      '--case=SPOT-01'
    ])
    const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
    const commands = []
    const infrastructure = smokeContracts.createLocalInfrastructureAdapter(
      composeFile,
      async (descriptor) => {
        commands.push(descriptor)
        if (descriptor.id === 'docker-context-inspect') {
          return {
            status: 0,
            signal: null,
            stdout: 'npipe:////./pipe/docker_engine\n',
            stderr: ''
          }
        }
        if (descriptor.id.endsWith('-published-id')) {
          return { status: 0, signal: null, stdout: '', stderr: '' }
        }
        throw new Error(`UNEXPECTED_DOCKER_COMMAND: ${descriptor.id}`)
      },
      {
        portIsOpen: async (port) => port === occupiedPort
      }
    )

    await assert.rejects(
      smokeContracts.prepareP0RunReservation({
        options,
        plan,
        artifactBase,
        inheritedEnv: {},
        captureIdentity: async () => identity,
        databaseExists: async () => false,
        nextRunToken: () => `p0-review1-s8-unknown-${occupiedPort}-owner-token-a1`,
        nextRandomSuffix: () => 'abcdef123456',
        infrastructure
      }),
      /P0_COMPOSE_PORT_MISMATCH/
    )
    assert.equal(existsSync(join(artifactBase, runId)), false)
    assert.equal(commands.some(({ args }) => args.includes('up')), false)
  }
})

test('default cleanup propagates one bounded signal through every local operation', async (t) => {
  assert.equal(typeof smokeContracts.createLocalProcessManager, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s8-default-cleanup-signal-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s8-default-cleanup-signal-a1'
  const runToken = 'p0-review1-s8-default-cleanup-signal-owner-token-a1'
  const database = 'fx_p0_user_e2e_s8_cleanup_signal_1_abcdef123456'
  const signal = AbortSignal.timeout(30_000)
  const fingerprint = `sha256:${'8'.repeat(64)}`
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: { matrix: database }
  })
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.redisState = 'SNAPSHOT_READY'
  active.journal.resources = [
    {
      type: 'process',
      id: 's8-live-process',
      live: true,
      state: 'STARTED',
      pid: 8811,
      processStartedAt: 'spawn:s8-live-process',
      processFingerprint: fingerprint
    },
    { type: 'database', id: database, state: 'STARTED' }
  ]
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  markP0RedisSnapshotReady(control, [{
    key: 'quote:BTCUSDT',
    exists: false,
    value: null,
    expiresAtMs: null
  }], ['quote:BTCUSDT'])
  const cleanupProof = await installStrictCleanupComposeProof(control, artifactBase)

  const observed = []
  const record = (label, observedSignal) => {
    observed.push({ label, signal: observedSignal })
  }
  const processManager = smokeContracts.createLocalProcessManager(
    async () => { throw new Error('UNEXPECTED_PROCESS_COMMAND') },
    {
      async stopProcesses(details = {}) {
        record('process:stop', details.signal)
      },
      async acquireNativeProcessHandle(pid, details = {}) {
        assert.equal(pid, 8811)
        record('process:acquire', details.signal)
        return {
          async inspectIdentity(handleDetails = {}) {
            record('process:inspect', handleDetails.signal)
            return { pid, startedAt: 'spawn:s8-live-process', processFingerprint: fingerprint }
          },
          async terminateTree(resource, handleDetails = {}) {
            record('process:terminate', handleDetails.signal)
            return {
              provider: 'WINDOWS_JOB_OBJECT_V1',
              pid,
              startedAt: resource.processStartedAt,
              processFingerprint: resource.processFingerprint,
              rootTerminated: true,
              treeTerminated: true
            }
          },
          async close(handleDetails = {}) {
            record('process:close', handleDetails.signal)
          }
        }
      }
    }
  )
  let redisOwner = runToken
  let redisReceipt = null
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: {
      capability: 'WINDOWS_JOB_OBJECT_V1',
      platform: 'win32',
      verification: 'INDEPENDENTLY_VERIFIED',
      acquireNativeProcessHandle: (...args) => processManager.acquireNativeProcessHandle(...args)
    },
    processManager,
    infrastructure: createStrictCleanupComposeInfrastructure(cleanupProof, {
      expectedSignal: signal,
      record,
      async assertPortsFree(_ports, details = {}) {
        record('ports:free', details.signal)
      }
    }),
    postgres: {
      async readDatabaseOwnership(segmentName, details = {}) {
        assert.equal(segmentName, database)
        record('postgres:read', details.signal)
        return { segmentName, ownerMarker: `p0-owner:${runToken}` }
      },
      async executeAdminSql(_sql, details = {}) {
        record('postgres:drop', details.signal)
      }
    },
    redis: {
      async get(key, details = {}) {
        record('redis:get', details.signal)
        return key === 'p0:e2e:owner' ? redisOwner : null
      },
      async deleteExact(_key, details = {}) {
        record('redis:delete', details.signal)
      },
      async releaseOwnershipWithReceipt(input) {
        record('redis:release', input.signal)
        redisOwner = null
        redisReceipt = control.ownerId
        return 'RELEASED'
      },
      async removeCleanupReceipt(input) {
        record('redis:receipt', input.signal)
        if (redisReceipt === input.ownerId) {
          redisReceipt = null
          return 'REMOVED'
        }
        return redisReceipt === null ? 'ALREADY_ABSENT' : 'MISMATCH'
      }
    }
  })

  assert.equal((await dependencies.cleanup(undefined, { signal }, { runId })).status, 'CLEANED')
  assert.deepEqual(new Set(observed.map(({ label }) => label)), new Set([
    'process:stop',
    'process:acquire',
    'process:inspect',
    'process:terminate',
    'process:close',
    'compose:daemon',
    'compose:containers',
    'ports:free',
    'postgres:read',
    'postgres:drop',
    'redis:get',
    'redis:delete',
    'redis:release',
    'redis:receipt'
  ]))
  assert.equal(observed.every((entry) => entry.signal === signal), true)
})

test('compose database probes use the exact owned project and config set', async () => {
  const composeFile = join(dirname(artifactsScript), '..', 'infra', 'docker-compose.yml')
  const overrideFile = resolve(tmpdir(), 'p0-review1-s8-compose.loopback.yml')
  const extraFile = resolve(tmpdir(), 'p0-review1-s8-compose.extra.yml')
  const segmentName = 'fx_p0_user_e2e_s8_compose_probe_1_abcdef123456'
  const postgresId = '9'.repeat(64)
  const commands = []
  let actualProject = 'infra'
  let actualConfigFiles = [composeFile, overrideFile]
  const inspection = () => JSON.stringify([{
    Id: postgresId,
    State: { Running: true },
    Config: {
      Image: 'postgres:16',
      Env: ['POSTGRES_USER=postgres', 'POSTGRES_PASSWORD=password'],
      Labels: {
        'com.docker.compose.project': actualProject,
        'com.docker.compose.service': 'postgres',
        'com.docker.compose.project.working_dir': dirname(composeFile),
        'com.docker.compose.project.config_files': actualConfigFiles.join(',')
      }
    },
    NetworkSettings: {
      Ports: {
        '5432/tcp': [{ HostIp: '127.0.0.1', HostPort: '5432' }]
      }
    }
  }])
  const infrastructure = smokeContracts.createLocalInfrastructureAdapter(
    composeFile,
    async (descriptor) => {
      commands.push(descriptor)
      if (descriptor.id === 'compose-postgres-existing-id') {
        return { status: 0, signal: null, stdout: postgresId, stderr: '' }
      }
      if (descriptor.id === 'compose-postgres-existing-inspect') {
        return { status: 0, signal: null, stdout: inspection(), stderr: '' }
      }
      if (descriptor.id === 'postgres-database-collision') {
        return { status: 0, signal: null, stdout: '1\n', stderr: '' }
      }
      throw new Error(`UNEXPECTED_DATABASE_PROBE_COMMAND: ${descriptor.id}`)
    }
  )

  assert.equal(await infrastructure.databaseExists(segmentName, {
    composeFiles: [composeFile, overrideFile],
    expectedProject: 'infra'
  }), true)
  assert.deepEqual(
    commands.find(({ id }) => id === 'compose-postgres-existing-id').args,
    [
      'ps', '--no-trunc',
      '--filter', 'label=com.docker.compose.project=infra',
      '--filter', 'label=com.docker.compose.service=postgres',
      '--format={{.ID}}'
    ]
  )

  actualProject = 'foreign'
  await assert.rejects(
    infrastructure.databaseExists(segmentName, {
      composeFiles: [composeFile, overrideFile],
      expectedProject: 'infra'
    }),
    /P0_COMPOSE_PROJECT_MISMATCH/
  )
  actualProject = 'infra'
  actualConfigFiles = [composeFile, overrideFile, extraFile]
  await assert.rejects(
    infrastructure.databaseExists(segmentName, {
      composeFiles: [composeFile, overrideFile],
      expectedProject: 'infra'
    }),
    /P0_COMPOSE_CONFIG_MISMATCH/
  )
})

test('new-session cleanup retains ACTIVE and Redis owner when no native handle can be acquired', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s8-no-native-handle-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s8-no-native-handle-a1'
  const runToken = 'p0-review1-s8-no-native-handle-owner-token-a1'
  const database = 'fx_p0_user_e2e_s8_no_handle_1_abcdef123456'
  const fingerprint = `sha256:${'a'.repeat(64)}`
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: [],
    database: { matrix: database }
  })
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.redisState = 'SNAPSHOT_READY'
  active.journal.resources = [
    {
      type: 'process',
      id: 'new-session-live-process',
      live: true,
      state: 'STARTED',
      pid: 9911,
      processStartedAt: 'spawn:new-session-live-process',
      processFingerprint: fingerprint
    },
    { type: 'database', id: database, state: 'STARTED' }
  ]
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  markP0RedisSnapshotReady(control)
  const before = readFileSync(control.ownershipPath, 'utf8')
  const calls = {
    parentStop: 0,
    acquire: 0,
    ports: 0,
    database: 0,
    redis: 0,
    receipt: 0
  }
  const acquireNativeProcessHandle = async () => {
    calls.acquire += 1
    return null
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: {
      capability: 'WINDOWS_JOB_OBJECT_V1',
      platform: 'win32',
      verification: 'INDEPENDENTLY_VERIFIED',
      acquireNativeProcessHandle
    },
    processManager: {
      async stopParentBackend() { calls.parentStop += 1 },
      acquireNativeProcessHandle
    },
    infrastructure: {
      async assertPortsFree() { calls.ports += 1 }
    },
    postgres: {
      async readDatabaseOwnership() { calls.database += 1; return null },
      async executeAdminSql() { calls.database += 1 }
    },
    redis: {
      async get() { calls.redis += 1; return runToken },
      async restoreExact() { calls.redis += 1 },
      async deleteExact() { calls.redis += 1 },
      async releaseOwnershipWithReceipt() { calls.redis += 1; return 'RELEASED' },
      async removeCleanupReceipt() { calls.receipt += 1 }
    }
  })

  await assert.rejects(
    dependencies.cleanup(undefined, {}, { runId }),
    /P0_PROCESS_NATIVE_HANDLE_REQUIRED/
  )
  assert.equal(calls.parentStop, process.platform === 'win32' ? 0 : 1)
  assert.equal(calls.acquire, 1)
  assert.equal(calls.ports, 0)
  assert.equal(calls.database, 0)
  assert.equal(calls.redis, 0)
  assert.equal(calls.receipt, 0)
  assert.equal(readFileSync(control.ownershipPath, 'utf8'), before)
  assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).status, 'ACTIVE')
  assert.equal(existsSync(join(control.runRoot, 'control', 'cleaned.json')), false)
})

test('profile database probes preserve the verified compose project and exact config set', async () => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-compose-profile-'))
  try {
    const artifactBase = join(root, 'artifacts')
    const signal = AbortSignal.timeout(30_000)
    const observed = []
    const composeTarget = smokeContracts.createP0ComposeTarget({ artifactBase })
    const databaseExists = async (segmentName, details = {}) => {
      observed.push({ segmentName, details })
      throw new Error('S9_A_PROFILE_PROBE_CAPTURED')
    }
    assert.equal(Object.isFrozen(composeTarget), true)
    assert.equal(Object.isFrozen(composeTarget.composeFiles), true)

    await assert.rejects(
      databaseExists(smokeContracts.databaseSegmentForAttempt({
        phase: 'spot',
        attempt: 1,
        randomSuffix: 'abcdef123456'
      }), {
        composeFiles: composeTarget.composeFiles,
        expectedProject: composeTarget.expectedProject,
        signal
      }),
      /S9_A_PROFILE_PROBE_CAPTURED/
    )
    assert.equal(observed.length, 1)
    assert.deepEqual(observed[0].details, {
      composeFiles: composeTarget.composeFiles,
      expectedProject: composeTarget.expectedProject,
      signal
    })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('sequential P0 runs reuse one stable worktree compose override identity', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-compose-stable-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const composeObservations = []
  const databaseObservations = []
  const expectedOverride = [
    'services:',
    '  postgres:',
    '    ports: !override',
    '      - "127.0.0.1:5432:5432"',
    '  redis:',
    '    ports: !override',
    '      - "127.0.0.1:6379:6379"',
    ''
  ].join('\n')
  const reserve = async (runId, runToken, suffixes) => {
    const options = p0CaseContracts.parseP0Cli([
      '--suite=p0',
      `--run-id=${runId}`,
      '--phase=selected',
      '--case=SPOT-01'
    ])
    const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
    return smokeContracts.prepareP0RunReservation({
      options,
      plan,
      artifactBase,
      inheritedEnv: {},
      captureIdentity: async () => review1P0Identity(),
      databaseExists: async (_segmentName, details = {}) => {
        databaseObservations.push({ runId, details })
        return false
      },
      nextRunToken: () => runToken,
      nextRandomSuffix: () => suffixes.shift(),
      infrastructure: {
        async inspectDockerDaemon() {
          return { endpoint: 'npipe:////./pipe/docker_engine' }
        },
        async verifyComposePort(expected) {
          composeObservations.push({ runId, expected })
          return true
        },
        async inspectListener() { return null }
      }
    })
  }

  const first = await reserve(
    'p0-review1-s9-compose-stable-a1',
    'p0-review1-s9-compose-stable-owner-token-a1',
    ['abcdef123456', 'abcdef123457']
  )
  const second = await reserve(
    'p0-review1-s9-compose-stable-a2',
    'p0-review1-s9-compose-stable-owner-token-a2',
    ['abcdef123458', 'abcdef123459']
  )
  assert.deepEqual(second.composeTarget, first.composeTarget)
  assert.equal(Object.isFrozen(first.composeTarget), true)
  assert.equal(Object.isFrozen(first.composeTarget.composeFiles), true)
  assert.equal(first.composeTarget.expectedProject, 'infra')
  assert.equal(first.composeTarget.composeFiles.length, 2)
  const overridePath = first.composeTarget.composeFiles[1]
  assert.equal(relative(first.runRoot, overridePath).startsWith('..'), true)
  assert.equal(relative(second.runRoot, overridePath).startsWith('..'), true)
  assert.equal(readFileSync(overridePath, 'utf8'), expectedOverride)
  assert.equal(existsSync(join(first.runRoot, 'control', 'compose.loopback.yml')), false)
  assert.equal(existsSync(join(second.runRoot, 'control', 'compose.loopback.yml')), false)
  for (const { expected } of composeObservations) {
    assert.deepEqual(expected.composeFiles, first.composeTarget.composeFiles)
    assert.equal(expected.expectedProject, first.composeTarget.expectedProject)
  }
  for (const { details } of databaseObservations) {
    assert.deepEqual(details.composeFiles, first.composeTarget.composeFiles)
    assert.equal(details.expectedProject, first.composeTarget.expectedProject)
  }

  writeFileSync(overridePath, `${expectedOverride}# foreign\n`)
  await assert.rejects(
    smokeContracts.ensureP0ComposeTarget({ artifactBase }),
    /P0_COMPOSE_OVERRIDE_INVALID/
  )
  assert.equal(readFileSync(overridePath, 'utf8'), `${expectedOverride}# foreign\n`)
})

test('backend environment scrub removes KEY ACCOUNT and ENDPOINT tokens case-insensitively', () => {
  const poisoned = {
    PATH: 'trusted-path',
    MONKEY: 'banana',
    BINANCE_API_KEY: 'binance-real-key',
    OKX_SECRET_KEY: 'okx-real-key',
    account_id: 'real-account',
    Provider_EndPoint: 'https://provider.invalid'
  }
  const scrubbed = smokeContracts.sanitizedBackendEnvironment(poisoned)
  assert.equal(scrubbed.PATH, 'trusted-path')
  assert.equal(scrubbed.MONKEY, 'banana')
  for (const key of ['BINANCE_API_KEY', 'OKX_SECRET_KEY', 'account_id', 'Provider_EndPoint']) {
    assert.equal(scrubbed[key], undefined, key)
  }

  const backend = smokeContracts.buildBackendEnvironment('UI_CORE', {}, poisoned)
  assert.equal(backend.MONKEY, 'banana')
  for (const key of ['BINANCE_API_KEY', 'OKX_SECRET_KEY', 'account_id', 'Provider_EndPoint']) {
    assert.equal(backend[key], undefined, `backend ${key}`)
  }

  const ownerToken = 'p0-review1-s9-environment-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const canonical = smokeContracts.buildCanonicalChildInvocation({
    scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
    ownerToken,
    ownerId,
    database: 'fx_p0_user_e2e_s9_environment_1_abcdef123456',
    inheritedEnv: poisoned
  })
  for (const key of ['BINANCE_API_KEY', 'OKX_SECRET_KEY', 'account_id', 'Provider_EndPoint']) {
    assert.equal(canonical.env[key], undefined, `canonical ${key}`)
  }
})

test('resume rejects unsafe run-state files and control selection drift before infrastructure inspection', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-resume-trust-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const identity = review1P0Identity()
  const selection = {
    caseIds: ['SPOT-01'],
    phases: [],
    profiles: [],
    viewports: [],
    metadata: s10RequestedPhaseSelection('selected').metadata
  }

  const createScenario = async (label, mutate) => {
    const artifactBase = join(root, label, 'artifacts')
    const runId = `p0-review1-s9-resume-${label}-a1`
    const runToken = `p0-review1-s9-resume-${label}-owner-token-a1`
    const options = p0CaseContracts.parseP0Cli([
      '--suite=p0',
      `--run-id=${runId}`,
      `--resume=${runId}`,
      '--phase=selected',
      '--case=SPOT-01'
    ])
    const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: options.mode,
      selection,
      database: {
        canonical: 'fx_p0_user_e2e_s9_resume_canonical_1_abcdef123456',
        matrix: 'fx_p0_user_e2e_s9_resume_matrix_1_abcdef123457'
      },
      identity,
      profileWorkerMap: identity.profileWorkerMap
    })
    const runStatePath = join(control.runRoot, 'run-state.json')
    const state = smokeContracts.openP0RunState({
      path: runStatePath,
      root: control.runRoot,
      resume: false,
      runId,
      mode: options.mode,
      commit: identity.commit,
      worktreeFingerprint: identity.worktreeFingerprint,
      schemaVersion: identity.schemaVersion,
      registryFingerprint: identity.registryFingerprint,
      definitions: P0_CASES,
      selection
    })
    await mutate({ artifactBase, control, runStatePath, state, selection })
    let probes = 0
    const reservation = smokeContracts.prepareP0RunReservation({
      options,
      plan,
      artifactBase,
      inheritedEnv: {},
      captureIdentity: async () => identity,
      databaseExists: async () => { probes += 1; return false },
      nextRunToken: () => { throw new Error('RESUME_TOKEN_MUST_NOT_RUN') },
      nextRandomSuffix: () => { throw new Error('RESUME_SUFFIX_MUST_NOT_RUN') },
      infrastructure: {
        async inspectDockerDaemon() { probes += 1; return { endpoint: 'npipe:////./pipe/docker_engine' } },
        async verifyComposePort() { probes += 1; return true },
        async inspectListener() { probes += 1; return null }
      }
    })
    return { artifactBase, control, reservation, probes: () => probes }
  }

  for (const [label, mutate, expectedError] of [
    [
      'hardlink',
      async ({ control, runStatePath }) => {
        linkSync(runStatePath, join(control.runRoot, 'run-state-alias.json'))
      },
      /P0_RUN_STATE_FILE_UNSAFE/
    ],
    [
      'symlink',
      async ({ control, state }) => {
        const ownership = readFileSync(control.ownershipPath, 'utf8')
        const external = join(root, 'external-junction-run')
        mkdirSync(join(external, 'control'), { recursive: true })
        writeFileSync(join(external, 'control', 'ownership.json'), ownership)
        writeFileSync(join(external, 'run-state.json'), `${JSON.stringify(state, null, 2)}\n`)
        rmSync(control.runRoot, { recursive: true, force: true })
        symlinkSync(external, control.runRoot, 'junction')
      },
      /P0_(?:CONTROL_ESCAPE|RUN_STATE_FILE_UNSAFE)/
    ],
    [
      'malformed-selection',
      async ({ runStatePath, state }) => {
        writeFileSync(runStatePath, `${JSON.stringify({
          ...state,
          selection: { ...selection, caseIds: ['SPOT-01', 'SPOT-01'] }
        }, null, 2)}\n`)
      },
      /P0_RESUME_STATE_INVALID/
    ],
    [
      'legacy-control',
      async ({ control }) => {
        const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
        active.selection = ['SPOT-01']
        writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
      },
      /P0_RESUME_CONTROL_SELECTION_INVALID/
    ],
    [
      'control-drift',
      async ({ control, selection: expected }) => {
        const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
        active.selection = { ...expected, caseIds: ['SPOT-02'] }
        writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
      },
      /P0_RESUME_SELECTION_MISMATCH/
    ]
  ]) {
    const scenario = await createScenario(label, mutate)
    await assert.rejects(scenario.reservation, expectedError, label)
    assert.equal(scenario.probes(), 0, label)
    assert.equal(existsSync(join(scenario.artifactBase, '.runtime')), false, label)
  }

  const escapeArtifactBase = join(root, 'escape', 'artifacts')
  const escapeRoot = join(escapeArtifactBase, 'owned-run')
  mkdirSync(escapeRoot, { recursive: true })
  const outsideState = join(root, 'outside-run-state.json')
  const escapeRunId = 'p0-review1-s9-resume-escape-a1'
  const escapeState = smokeContracts.openP0RunState({
    path: outsideState,
    root: dirname(outsideState),
    resume: false,
    runId: escapeRunId,
    mode: 'discovery',
    commit: identity.commit,
    worktreeFingerprint: identity.worktreeFingerprint,
    schemaVersion: identity.schemaVersion,
    registryFingerprint: identity.registryFingerprint,
    definitions: P0_CASES,
    selection
  })
  assert.ok(escapeState)
  assert.throws(
    () => smokeContracts.openP0RunState({
      path: outsideState,
      root: escapeRoot,
      resume: true,
      runId: escapeRunId,
      mode: 'discovery',
      commit: identity.commit,
      worktreeFingerprint: identity.worktreeFingerprint,
      schemaVersion: identity.schemaVersion,
      registryFingerprint: identity.registryFingerprint,
      definitions: P0_CASES,
      selection
    }),
    /P0_RUN_STATE_FILE_UNSAFE/
  )

  const newArtifactBase = join(root, 'new-run', 'artifacts')
  const newRunId = 'p0-review1-s9-selection-persist-a1'
  const newOptions = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    `--run-id=${newRunId}`,
    '--phase=selected',
    '--case=SPOT-01'
  ])
  const suffixes = ['abcdef123458', 'abcdef123459']
  const reserved = await smokeContracts.prepareP0RunReservation({
    options: newOptions,
    plan: p0CaseContracts.planP0Execution(newOptions, P0_CASES),
    artifactBase: newArtifactBase,
    inheritedEnv: {},
    captureIdentity: async () => identity,
    databaseExists: async () => false,
    nextRunToken: () => 'p0-review1-s9-selection-persist-owner-token-a1',
    nextRandomSuffix: () => suffixes.shift(),
    infrastructure: {
      async inspectDockerDaemon() { return { endpoint: 'npipe:////./pipe/docker_engine' } },
      async verifyComposePort() { return true },
      async inspectListener() { return null }
    }
  })
  assert.deepEqual(
    JSON.parse(readFileSync(reserved.ownershipPath, 'utf8')).selection,
    selection
  )
})

test('Redis recovery journal binds schema run owner and exact ten-key inventory', () => {
  assert.equal(typeof smokeContracts.createP0RedisRecoveryState, 'function')
  assert.equal(typeof smokeContracts.validateP0RedisRecoveryState, 'function')
  const runId = 'p0-review1-s9-redis-journal-a1'
  const ownerId = 'a'.repeat(64)
  const inventory = [
    'quote:BTCUSDT',
    'quote:ETHUSDT',
    'quote:BNBUSDT',
    'quote:SOLUSDT',
    'quote:XRPUSDT',
    'quote:BTCUSDT-PERP',
    'quote:ETHUSDT-PERP',
    'quote:BNBUSDT-PERP',
    'quote:SOLUSDT-PERP',
    'quote:XRPUSDT-PERP'
  ]
  const snapshot = inventory.map((key, index) => index === 0
    ? { key, exists: true, value: 'before', expiresAtMs: 1_900_000_000_000 }
    : { key, exists: false, value: null, expiresAtMs: null })
  const recovery = smokeContracts.createP0RedisRecoveryState({
    runId,
    ownerId,
    inventory,
    snapshot,
    touchedKeys: [inventory[0]]
  })
  assert.deepEqual(recovery, {
    schemaVersion: 1,
    runId,
    ownerId,
    inventoryCount: 10,
    inventoryFingerprint: `sha256:${createHash('sha256')
      .update(JSON.stringify(inventory))
      .digest('hex')}`,
    snapshot,
    touchedKeys: [inventory[0]]
  })
  assert.deepEqual(smokeContracts.validateP0RedisRecoveryState(recovery, {
    runId,
    ownerId,
    inventory
  }), recovery)
})

test('cleanup rejects truncated duplicate foreign and invalid-TTL Redis recovery before mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-redis-invalid-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const inventory = [
    'quote:BTCUSDT',
    'quote:ETHUSDT',
    'quote:BNBUSDT',
    'quote:SOLUSDT',
    'quote:XRPUSDT',
    'quote:BTCUSDT-PERP',
    'quote:ETHUSDT-PERP',
    'quote:BNBUSDT-PERP',
    'quote:SOLUSDT-PERP',
    'quote:XRPUSDT-PERP'
  ]
  const inventoryFingerprint = `sha256:${createHash('sha256')
    .update(JSON.stringify(inventory))
    .digest('hex')}`
  const snapshot = inventory.map((key) => ({
    key,
    exists: false,
    value: null,
    expiresAtMs: null
  }))
  const validRecovery = (runId, ownerId) => ({
    schemaVersion: 1,
    runId,
    ownerId,
    inventoryCount: inventory.length,
    inventoryFingerprint,
    snapshot: structuredClone(snapshot),
    touchedKeys: []
  })
  const scenarios = [
    ['truncated', (state) => { state.snapshot.pop() }],
    ['duplicate', (state) => { state.snapshot[9] = { ...state.snapshot[0] } }],
    ['foreign', (state) => { state.ownerId = 'f'.repeat(64) }],
    ['invalid-ttl', (state) => {
      state.snapshot[0] = {
        key: inventory[0],
        exists: true,
        value: 'before',
        expiresAtMs: -1
      }
    }],
    ['foreign-touch', (state) => { state.touchedKeys = ['quote:FOREIGN'] }]
  ]

  for (const [label, corrupt] of scenarios) {
    const artifactBase = join(root, label, 'artifacts')
    const runId = `p0-review1-s9-redis-${label}-a1`
    const runToken = `p0-review1-s9-redis-${label}-owner-token-a1`
    const control = await smokeContracts.createControlManifest({
      artifactBase,
      runId,
      runToken,
      mode: 'discovery',
      selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
      database: {}
    })
    const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
    active.redisState = 'SNAPSHOT_READY'
    writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
    const recovery = validRecovery(runId, control.ownerId)
    corrupt(recovery)
    writeFileSync(
      join(control.runRoot, 'control', 'redis.json'),
      `${JSON.stringify(recovery, null, 2)}\n`
    )
    const calls = []
    const dependencies = smokeContracts.createDefaultP0Dependencies({
      artifactBase,
      inheritedEnv: {},
      processTreeProvider: createCleanupOnlyProcessTreeProvider(
        `redis-invalid-recovery:${label}`
      ),
      processManager: { async stopParentBackend() { calls.push('process') } },
      infrastructure: { async assertPortsFree() { calls.push('ports') } },
      postgres: {
        async readDatabaseOwnership() { calls.push('database'); return null },
        async executeAdminSql() { calls.push('database') }
      },
      redis: {
        async get() { calls.push('redis'); return runToken },
        async restoreExact() { calls.push('redis') },
        async deleteExact() { calls.push('redis') },
        async releaseOwnershipWithReceipt() { calls.push('redis'); return 'RELEASED' },
        async removeCleanupReceipt() { calls.push('redis') }
      }
    })
    await assert.rejects(
      dependencies.cleanup(undefined, {}, { runId }),
      /P0_REDIS_RECOVERY_INVALID/,
      label
    )
    assert.deepEqual(calls, ['process'], label)
    assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).status, 'ACTIVE', label)
  }
})

test('NOT_ACQUIRED cleanup completes without redis.json or Redis access', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-not-acquired-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s9-not-acquired-a1'
  const runToken = 'p0-review1-s9-not-acquired-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
    database: {}
  })
  const calls = []
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processManager: { async stopParentBackend() { calls.push('process') } },
    infrastructure: { async assertPortsFree() { calls.push('ports') } },
    postgres: {
      async readDatabaseOwnership() { calls.push('database'); return null },
      async executeAdminSql() { calls.push('database') }
    },
    redis: new Proxy({}, {
      get() {
        calls.push('redis')
        throw new Error('REDIS_ACCESS_FORBIDDEN')
      }
    }),
    now: () => '2026-07-15T17:00:00.000Z'
  })

  assert.deepEqual(await dependencies.cleanup(undefined, {}, { runId }), {
    status: 'CLEANED',
    databases: 0,
    redis: 'NOT_ACQUIRED',
    restored: 0
  })
  assert.deepEqual(calls, ['process', 'ports'])
  assert.equal(existsSync(join(control.runRoot, 'control', 'redis.json')), false)
  assert.deepEqual(JSON.parse(readFileSync(control.ownershipPath, 'utf8')), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: control.ownerId,
    cleanedAt: '2026-07-15T17:00:00.000Z',
    receipt: {
      key: null,
      state: 'NOT_REQUIRED',
      completedAt: '2026-07-15T17:00:00.000Z'
    }
  })
})

test('control-only phases persist scoped PARTIAL_PASS without synthetic matrix failures', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-control-report-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const expectedStaticEvidence = {
    preflight: { id: 'AUTH-01', status: 'PASS' },
    canonical: {
      child: { status: 0, signal: null },
      verification: { id: 'SPOT-01', status: 'PASS' }
    },
    authority: { id: 'AUTH-03', status: 'PASS' }
  }

  for (const phase of ['preflight', 'canonical', 'authority', 'report']) {
    const runId = `p0-review1-s9-control-${phase}-a1`
    const options = p0CaseContracts.parseP0Cli([
      '--suite=p0',
      `--phase=${phase}`,
      `--run-id=${runId}`
    ])
    const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
    assert.equal(plan.scope, 'CONTROL', phase)
    assert.deepEqual(plan.definitions, [], phase)
    assert.deepEqual(plan.executionEntries, [], phase)
    assert.deepEqual(plan.profiles, [], phase)
    assert.equal(plan.businessMutation, false, phase)

    const runRoot = join(root, runId)
    mkdirSync(runRoot, { recursive: true })
    const ownerToken = `p0-review1-s9-control-${phase}-owner-token-a1`
    const ownerId = createHash('sha256').update(ownerToken).digest('hex')
    const expectedEvidence = {
      ...expectedStaticEvidence,
      report: {
        status: 'PASS',
        kind: 'P0_REPORT_BOUNDARY',
        scope: 'CONTROL',
        finalWriter: 'PENDING',
        identity: { runId, ownerId, reportPath: join(runRoot, 'report.json') }
      }
    }
    let dispatchCalls = 0
    const reportWriter = (execution, prepared, details = {}) => writeFullyFakeP0Report(
      execution,
      prepared,
      { ...details, now: () => '2026-07-15T18:00:00.000Z' }
    )
    const completed = await smokeContracts.runP0Suite(options, {
      installSignalHandlers() { return () => {} },
      async initializeOwnership() {
        return {
          runRoot,
          ownerToken,
          ownerId,
          scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
          canonicalDatabase: `fx_p0_user_e2e_s9_control_${phase}_canonical_1`,
          matrixDatabase: `fx_p0_user_e2e_s9_control_${phase}_matrix_1`,
          inheritedEnv: {},
          identity: null,
          caseResults: [],
          async assertIdentity() {}
        }
      },
      phaseOperations: {
        async runPreflight() { return expectedEvidence.preflight },
        async assertRedisOwnership() {},
        async stopParentBackend() {},
        async assertBusinessPortsFree() {},
        async runCanonicalChild() { return expectedEvidence.canonical.child },
        async verifyCanonicalChildCleanup() {
          return expectedEvidence.canonical.verification
        },
        async runAuthority() { return expectedEvidence.authority },
        async writeReport() { return expectedEvidence.report }
      },
      async dispatchCase() {
        dispatchCalls += 1
        throw new Error('CONTROL_PHASE_DISPATCH_FORBIDDEN')
      },
      handlers: Object.create(null),
      writeReport: reportWriter,
      async cleanup() { return { status: 'CLEANED' } }
    })

    assert.equal(dispatchCalls, 0, phase)
    assert.deepEqual(completed.execution.caseResults, [], phase)
    assert.deepEqual(
      completed.execution.controlResults,
      plan.phases
        .filter((plannedPhase) => plannedPhase !== 'cleanup')
        .map((plannedPhase) => ({
          phase: plannedPhase,
          status: 'PASS',
          evidence: expectedEvidence[plannedPhase]
        })),
      phase
    )
    const persisted = JSON.parse(readFileSync(join(runRoot, 'report.json'), 'utf8'))
    assert.equal(persisted.scope, 'CONTROL', phase)
    assert.equal(persisted.status, 'PARTIAL_PASS', phase)
    assert.equal(persisted.verdict, 'PARTIAL_PASS', phase)
    assert.equal(persisted.scopeComplete, false, phase)
    assert.equal(persisted.counts.MISSING, 0, phase)
    assert.deepEqual(persisted.issues, [], phase)
    assert.deepEqual(persisted.caseResults, [], phase)
    assert.deepEqual(
      persisted.controlResults,
      completed.execution.controlResults.map((result) => ({
        ...result,
        evidence: redactNetworkEntry({ evidence: result.evidence }).evidence
      })),
      phase
    )
  }

  const matrixPlan = p0CaseContracts.planP0Execution(
    p0CaseContracts.parseP0Cli([
      '--suite=p0',
      '--phase=selected',
      '--case=SPOT-01',
      '--run-id=p0-review1-s9-matrix-scope-a1'
    ]),
    P0_CASES
  )
  assert.equal(matrixPlan.scope, 'MATRIX')
  assert.deepEqual(matrixPlan.definitions.map(({ id }) => id), ['SPOT-01'])

  const cleanupPlan = p0CaseContracts.planP0Execution(
    p0CaseContracts.parseP0Cli([
      '--suite=p0',
      '--phase=cleanup',
      '--run-id=p0-review1-s9-cleanup-scope-a1'
    ]),
    P0_CASES
  )
  assert.equal(cleanupPlan.scope, 'CONTROL')
  assert.equal(cleanupPlan.verdict, null)
  assert.deepEqual(cleanupPlan.definitions, [])
})

test('local process adapters attach error listeners before identity registration and settle once', async () => {
  const events = []
  const child = new EventEmitter()
  const originalOnce = child.once.bind(child)
  child.once = (event, listener) => {
    events.push(`listener:${event}`)
    return originalOnce(event, listener)
  }
  child.stdout = new EventEmitter()
  child.stderr = new EventEmitter()
  child.stdin = { end() {} }
  child.exitCode = null
  child.signalCode = null
  child.kill = () => true
  Object.defineProperty(child, 'pid', {
    configurable: true,
    get() {
      events.push('pid:read')
      return 9123
    }
  })
  const descriptor = {
    command: 'C:\\trusted\\java.exe',
    args: ['-version'],
    cwd: 'C:\\trusted',
    env: {},
    shell: false
  }
  const started = smokeContracts.startP0ManagedProcess(
    's9-listener-order',
    descriptor.command,
    descriptor.args,
    descriptor.cwd,
    descriptor.env,
    undefined,
    {
      normalizeDescriptor: () => descriptor,
      spawnProcess: () => child
    }
  )
  assert.equal(started, child)
  assert.equal(events.includes('pid:read'), false)
  assert.deepEqual(events.slice(0, 3), [
    'listener:error',
    'listener:spawn',
    'listener:exit'
  ])
  child.emit('spawn')
  await child.p0SpawnReady
  assert.equal(events.indexOf('listener:exit') < events.indexOf('pid:read'), true)
  assert.equal(child.processIdentity.pid, 9123)
  child.exitCode = 0
  child.emit('exit', 0, null)

  let missingOnSpawnCalls = 0
  const missingCommand = join(tmpdir(), 'p0-review1-s9-command-does-not-exist.exe')
  await assert.rejects(
    smokeContracts.runLocalCommand({
      id: 's9-missing-command',
      command: missingCommand,
      args: [],
      cwd: tmpdir(),
      env: {},
      async onSpawn() { missingOnSpawnCalls += 1 }
    }),
    (error) => error?.code === 'ENOENT'
  )
  assert.equal(missingOnSpawnCalls, 0)

  const registrationError = new Error('S9_ON_SPAWN_FAILED')
  let registrationCalls = 0
  await assert.rejects(
    smokeContracts.runLocalCommand({
      id: 's9-on-spawn-failure',
      command: process.execPath,
      args: ['--eval', 'setInterval(() => {}, 1000)'],
      cwd: process.cwd(),
      env: process.env,
      async onSpawn() {
        registrationCalls += 1
        throw registrationError
      }
    }),
    (error) => error === registrationError
  )
  assert.equal(registrationCalls, 1)
})

test('default phase boundaries propagate the execution signal and isolate preflight cleanup', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-phase-signal-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const signal = AbortSignal.timeout(30_000)
  const runId = 'p0-review1-s9-phase-signal-a1'
  const ownerToken = 'p0-review1-s9-phase-signal-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const canonicalDatabase = 'fx_p0_user_e2e_s9_signal_canonical_1'
  const observed = []
  const preflightCleanupSignals = []
  const observe = (name, received) => {
    observed.push(name)
    assert.equal(received, signal, name)
  }
  const processManager = {
    async stopParentBackend(details = {}) { observe('parent-stop', details.signal) }
  }
  const redis = {
    async get(_key, details = {}) {
      observe('canonical-redis', details.signal)
      return ownerToken
    }
  }
  const postgres = {
    async readDatabaseOwnership(_database, details = {}) {
      observe('canonical-database', details.signal)
      return null
    }
  }
  const writePhaseReport = async (context, plan, details = {}) => {
    observe('phase-report', details.signal)
    return exactP0ReportPhaseEvidence(context, plan)
  }

  await processManager.stopParentBackend({ signal })
  await processManager.stopParentBackend({ signal })

  const canonicalRoot = join(root, 'canonical-run')
  mkdirSync(join(canonicalRoot, 'canonical'), { recursive: true })
  writeFileSync(join(canonicalRoot, 'canonical', 'report.json'), `${JSON.stringify({
    status: 'PASS',
    runId: `${runId}-canonical`,
    smokeDatabase: canonicalDatabase,
    error: null,
    results: [{ name: 'canonical', status: 'PASS' }],
    sourceEvidence: [{ mode: 'LOCAL_SIMULATED', spot: {}, perp: {} }]
  })}\n`)
  const context = {
    runRoot: canonicalRoot,
    options: { runId },
    ownerId,
    canonicalDatabase,
    ownerToken
  }
  await smokeContracts.verifyCanonicalChildResult({
    child: { status: 0, signal: null },
    context,
    redis,
    postgres,
    signal
  })
  await smokeContracts.executeP0ReportPhaseBoundary({
    context,
    plan: { scope: 'CONTROL' },
    writePhaseReport,
    signal
  })

  await smokeContracts.runP0Preflight({
    projectRoot: root,
    gateOutput: join(root, 'surefire.json'),
    databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${canonicalDatabase}`,
    signal,
    operations: {
      async runCommand(descriptor) {
        observe(`preflight-command:${descriptor.id}`, descriptor.signal)
        return { status: 0, signal: null, stdout: '', stderr: '' }
      },
      async recordGate() {},
      async startOwnedBackend(input) {
        observe('preflight-start', input.signal)
        return { pid: 9911 }
      },
      async waitForBackendHealth(_backend, _url, received) {
        observe('preflight-health', received)
      },
      async waitForBusinessEndpoint(_backend, _url, received) {
        observe('preflight-business', received)
      },
      async verifyBackendDatabaseIdentity(_backend, details = {}) {
        observe('preflight-database', details.signal)
      },
      async stopOwnedBackend(_backend, details = {}) {
        observed.push('preflight-stop')
        preflightCleanupSignals.push(details.signal)
        assert.notEqual(details.signal, signal)
        assert.equal(details.signal?.aborted, false)
      },
      async assertBusinessPortsFree(_ports, details = {}) {
        observed.push('preflight-ports')
        preflightCleanupSignals.push(details.signal)
        assert.notEqual(details.signal, signal)
        assert.equal(details.signal?.aborted, false)
      }
    }
  })
  assert.equal(preflightCleanupSignals.length, 2)
  assert.equal(preflightCleanupSignals[0], preflightCleanupSignals[1])

  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    `--run-id=${runId}`
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const definition = plan.definitions[0]
  const runRoot = join(root, runId)
  mkdirSync(runRoot, { recursive: true })
  await writeFullyFakeP0Report({
    plan,
    caseResults: [{
      id: definition.id,
      status: 'PASS',
      scopeComplete: true,
      subruns: definition.requiredSubruns.map((subrun) => ({ ...subrun, status: 'PASS' }))
    }]
  }, {
    runRoot,
    options,
    ownerId: createHash('sha256').update(ownerToken).digest('hex'),
    canonicalDatabase,
    matrixDatabase: 'fx_p0_user_e2e_s9_signal_matrix_1',
    async assertIdentity(details = {}) { observe('report-identity', details.signal) }
  }, {
    signal,
    now: () => '2026-07-15T19:00:00.000Z'
  })

  for (const required of [
    'parent-stop',
    'canonical-redis',
    'canonical-database',
    'phase-report',
    'preflight-stop',
    'preflight-ports',
    'report-identity'
  ]) assert.ok(observed.includes(required), required)
})

test('Windows P0 requires a verified process tree provider before the first mutation', {
  skip: process.platform !== 'win32'
}, async (t) => {
  assert.equal(typeof smokeContracts.assertP0ProcessTreeCapability, 'function')
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-windows-capability-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    '--run-id=p0-review1-s9-windows-capability-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const calls = []
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'win32',
    artifactBase,
    inheritedEnv: {},
    captureIdentity() { calls.push('identity'); return review1P0Identity() },
    async databaseExists() { calls.push('database'); return false },
    infrastructure: {
      async inspectDockerDaemon() { calls.push('docker') },
      async verifyComposePort() { calls.push('compose'); return true },
      async inspectListener() { calls.push('listener'); return null }
    },
    redis: {},
    postgres: {},
    processManager: {}
  })

  await assert.rejects(
    dependencies.initializeOwnership(options, plan),
    /P0_WINDOWS_JOB_OBJECT_REQUIRED/
  )
  assert.deepEqual(calls, [])
  assert.equal(existsSync(artifactBase), false)
  assert.throws(
    () => smokeContracts.assertP0ProcessTreeCapability({ platform: 'win32' }),
    /P0_WINDOWS_JOB_OBJECT_REQUIRED/
  )

  const listed = []
  const listResult = await smokeContracts.createSmokeMain({
    platform: 'win32',
    writeStdout(value) { listed.push(value) }
  })(['--suite=p0', '--list'])
  assert.deepEqual(listResult, { listed: 60 })
  assert.equal(listed.length, 1)

  await assert.rejects(
    smokeContracts.runCanonicalSmoke({ platform: 'win32' }),
    /P0_WINDOWS_JOB_OBJECT_REQUIRED/
  )
})

test('Windows same-session cleanup never claims process-tree recovery from direct child kill', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s9-direct-child-tree-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const child = new EventEmitter()
  child.pid = 9321
  child.stdout = new EventEmitter()
  child.stderr = new EventEmitter()
  child.stdin = { end() {} }
  child.exitCode = null
  child.signalCode = null
  child.kill = () => {
    queueMicrotask(() => {
      child.exitCode = 0
      child.emit('exit', 0, null)
    })
    return true
  }
  const descriptor = {
    command: 'C:\\trusted\\java.exe',
    args: ['-version'],
    cwd: 'C:\\trusted',
    env: {},
    shell: false
  }
  smokeContracts.startP0ManagedProcess(
    's9-direct-child-tree',
    descriptor.command,
    descriptor.args,
    descriptor.cwd,
    descriptor.env,
    undefined,
    {
      normalizeDescriptor: () => descriptor,
      spawnProcess: () => child
    }
  )
  child.emit('spawn')
  await child.p0SpawnReady
  const directHandle = smokeContracts.acquireLocalNativeProcessHandle(child.pid)
  assert.deepEqual(
    await directHandle.terminateTree({ id: 'backend:direct-child' }),
    {
      provider: 'DIRECT_CHILD',
      rootTerminated: true,
      treeTerminated: false
    }
  )

  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s9-unverified-tree-a1'
  const runToken = 'p0-review1-s9-unverified-tree-owner-token-a1'
  const fingerprint = `sha256:${'e'.repeat(64)}`
  const startedAt = '2026-07-15T20:00:00.000Z'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
    database: {}
  })
  markP0RedisSnapshotReady(control)
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.journal.sequence = 1
  active.journal.resources.push({
    type: 'process',
    id: 'backend:s9-unverified-tree',
    live: true,
    state: 'STARTED',
    sequence: 1,
    recordedAt: startedAt,
    startedAt,
    pid: 9322,
    processStartedAt: startedAt,
    processFingerprint: fingerprint,
    commandFingerprint: fingerprint
  })
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  let redisOwner = runToken
  const calls = []
  const provider = {
    capability: 'WINDOWS_JOB_OBJECT_V1',
    platform: 'win32',
    verification: 'INDEPENDENTLY_VERIFIED',
    async acquireNativeProcessHandle(pid) {
      assert.equal(pid, 9322)
      calls.push('provider:acquire')
      return {
        async inspectIdentity() {
          calls.push('provider:inspect')
          return { pid, startedAt, processFingerprint: fingerprint }
        },
        async terminateTree() {
          calls.push('provider:terminate')
          return {
            provider: 'DIRECT_CHILD',
            rootTerminated: true,
            treeTerminated: false
          }
        },
        async close() { calls.push('provider:close') }
      }
    }
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'win32',
    processTreeProvider: provider,
    artifactBase,
    inheritedEnv: {},
    processManager: { async stopParentBackend() { calls.push('parent:stop') } },
    infrastructure: { async assertPortsFree() { calls.push('ports') } },
    postgres: { async readDatabaseOwnership() { calls.push('database'); return null } },
    redis: {
      async get() { calls.push('redis:get'); return redisOwner },
      async releaseOwnershipWithReceipt() {
        calls.push('redis:release')
        redisOwner = null
        return 'RELEASED'
      }
    }
  })
  await assert.rejects(
    dependencies.cleanup({
      artifactBase,
      runRoot: control.runRoot,
      ownerToken: runToken,
      ownerId: control.ownerId,
      redisSnapshot: [],
      touchedRedisKeys: []
    }, {}, { runId }),
    /P0_PROCESS_TREE_TERMINATION_UNVERIFIED/
  )
  assert.deepEqual(calls, [
    'provider:acquire',
    'provider:inspect',
    'provider:terminate',
    'provider:close'
  ])
  assert.equal(redisOwner, runToken)
  assert.equal(JSON.parse(readFileSync(control.ownershipPath, 'utf8')).status, 'ACTIVE')
})

test('canonical child acceptance verifies all owned business ports are free before authority', async () => {
  const sequence = []
  let portChecks = 0
  await smokeContracts.executeP0PlanPhases({
    plan: { phases: ['canonical', 'authority', 'cleanup'] },
    context: {
      runId: 'p0-review1-s9-canonical-ports-a1',
      scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
      ownerToken: 'p0-review1-s9-canonical-ports-owner-token-a1',
      ownerId: createHash('sha256')
        .update('p0-review1-s9-canonical-ports-owner-token-a1')
        .digest('hex'),
      canonicalDatabase: 'fx_p0_user_e2e_s9_canonical_ports_1',
      inheritedEnv: {},
      async assertIdentity() { sequence.push('identity') }
    },
    operations: {
      async assertRedisOwnership(_context, stage) { sequence.push(`redis:${stage}`) },
      async stopParentBackend() { sequence.push('parent:stop') },
      async assertBusinessPortsFree(ports) {
        assert.deepEqual(ports, [18086, 5199, 5200])
        portChecks += 1
        sequence.push(`ports:${portChecks}`)
      },
      async runCanonicalChild() {
        sequence.push('canonical:child')
        return { status: 0, signal: null }
      },
      async verifyCanonicalChildCleanup() {
        sequence.push('canonical:verified')
        return { id: 'CAT-01', status: 'PASS' }
      },
      async runAuthority() {
        assert.equal(portChecks, 2)
        sequence.push('authority')
        return { id: 'AUTH-03', status: 'PASS' }
      }
    }
  })

  assert.deepEqual(sequence, [
    'identity',
    'redis:before-canonical',
    'parent:stop',
    'ports:1',
    'canonical:child',
    'canonical:verified',
    'redis:after-canonical',
    'ports:2',
    'identity',
    'authority'
  ])
})

test('default P0 factory uses immutable host platform and rejects Windows before mutation', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-host-platform-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    '--run-id=p0-review1-s10-host-platform-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const calls = []
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'linux',
    artifactBase,
    inheritedEnv: {},
    captureIdentity() {
      calls.push('identity')
      throw new Error('P0_TEST_MUTATION_BOUNDARY_REACHED')
    }
  })

  let failure
  try {
    await dependencies.initializeOwnership(options, plan)
  } catch (error) {
    failure = error
  }

  assert.equal(failure?.message, 'P0_WINDOWS_JOB_OBJECT_REQUIRED')
  assert.deepEqual(calls, [])
  assert.equal(existsSync(artifactBase), false)
})

test('metadata-only Windows provider cannot authorize default P0 or canonical mutation', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-metadata-provider-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const calls = []
  const metadataProvider = {
    capability: 'WINDOWS_JOB_OBJECT_V1',
    platform: 'win32',
    verification: 'INDEPENDENTLY_VERIFIED',
    async acquireNativeProcessHandle() {
      calls.push('provider:acquire')
      throw new Error('P0_TEST_PROVIDER_ACQUIRE_FORBIDDEN')
    }
  }
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    '--run-id=p0-review1-s10-metadata-provider-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'win32',
    processTreeProvider: metadataProvider,
    artifactBase,
    inheritedEnv: {},
    captureIdentity() {
      calls.push('default:identity')
      throw new Error('P0_TEST_DEFAULT_MUTATION_BOUNDARY_REACHED')
    }
  })

  let defaultFailure
  try {
    await dependencies.initializeOwnership(options, plan)
  } catch (error) {
    defaultFailure = error
  }

  const previousOwnerToken = process.env.P0_RUN_OWNER_TOKEN
  const previousDatabase = process.env.USDT_DEMO_SMOKE_DATABASE
  process.env.P0_RUN_OWNER_TOKEN = 'p0-review1-s10-metadata-provider-owner-token-a1'
  delete process.env.USDT_DEMO_SMOKE_DATABASE
  t.after(() => {
    if (previousOwnerToken === undefined) delete process.env.P0_RUN_OWNER_TOKEN
    else process.env.P0_RUN_OWNER_TOKEN = previousOwnerToken
    if (previousDatabase === undefined) delete process.env.USDT_DEMO_SMOKE_DATABASE
    else process.env.USDT_DEMO_SMOKE_DATABASE = previousDatabase
  })

  let canonicalFailure
  try {
    await smokeContracts.runCanonicalSmoke({
      platform: 'win32',
      processTreeProvider: metadataProvider
    })
  } catch (error) {
    canonicalFailure = error
  }

  assert.equal(defaultFailure?.message, 'P0_WINDOWS_JOB_OBJECT_REQUIRED')
  assert.equal(canonicalFailure?.message, 'P0_WINDOWS_JOB_OBJECT_REQUIRED')
  assert.deepEqual(calls, [])
  assert.equal(existsSync(artifactBase), false)
})

test('Windows cleanup-only recovery provider cannot authorize a new run', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-cleanup-provider-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const calls = []
  const cleanupRecoveryProvider = {
    capability: 'WINDOWS_JOB_OBJECT_V1',
    platform: 'win32',
    verification: 'INDEPENDENTLY_VERIFIED',
    async acquireNativeProcessHandle(pid) {
      calls.push(`provider:acquire:${pid}`)
      return {
        async inspectIdentity() {
          return {
            pid,
            startedAt: '2026-07-15T21:00:00.000Z',
            processFingerprint: `sha256:${'a'.repeat(64)}`
          }
        },
        async terminateTree() {
          return {
            provider: 'WINDOWS_JOB_OBJECT_V1',
            pid,
            startedAt: '2026-07-15T21:00:00.000Z',
            processFingerprint: `sha256:${'a'.repeat(64)}`,
            rootTerminated: true,
            treeTerminated: true
          }
        },
        async close() {}
      }
    }
  }
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    '--run-id=p0-review1-s10-cleanup-provider-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'win32',
    processTreeProvider: cleanupRecoveryProvider,
    artifactBase,
    inheritedEnv: {},
    captureIdentity() {
      calls.push('identity')
      throw new Error('P0_TEST_NEW_RUN_MUTATION_BOUNDARY_REACHED')
    }
  })

  let failure
  try {
    await dependencies.initializeOwnership(options, plan)
  } catch (error) {
    failure = error
  }

  assert.equal(failure?.message, 'P0_WINDOWS_JOB_OBJECT_REQUIRED')
  assert.deepEqual(calls, [])
  assert.equal(existsSync(artifactBase), false)
})

test('Windows NOT_ACQUIRED cleanup needs no job provider and performs no Redis access', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-not-acquired-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s10-not-acquired-a1'
  const runToken = 'p0-review1-s10-not-acquired-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
    database: {}
  })
  const calls = []
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'win32',
    artifactBase,
    inheritedEnv: {},
    processManager: {
      async stopParentBackend() { calls.push('process') },
      async acquireNativeProcessHandle() {
        calls.push('native-handle')
        throw new Error('P0_TEST_NATIVE_HANDLE_FORBIDDEN')
      }
    },
    infrastructure: { async assertPortsFree() { calls.push('ports') } },
    postgres: {
      async readDatabaseOwnership() { calls.push('database'); return null },
      async executeAdminSql() { calls.push('database') }
    },
    redis: new Proxy({}, {
      get() {
        calls.push('redis')
        throw new Error('REDIS_ACCESS_FORBIDDEN')
      }
    }),
    now: () => '2026-07-15T21:30:00.000Z'
  })

  assert.deepEqual(await dependencies.cleanup(undefined, {}, { runId }), {
    status: 'CLEANED',
    databases: 0,
    redis: 'NOT_ACQUIRED',
    restored: 0
  })
  assert.deepEqual(calls, ['process', 'ports'])
  assert.equal(existsSync(join(control.runRoot, 'control', 'redis.json')), false)
  assert.deepEqual(JSON.parse(readFileSync(control.ownershipPath, 'utf8')), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: control.ownerId,
    cleanedAt: '2026-07-15T21:30:00.000Z',
    receipt: {
      key: null,
      state: 'NOT_REQUIRED',
      completedAt: '2026-07-15T21:30:00.000Z'
    }
  })
})

function s10RequestedPhaseOptions(runId, phase, resume = false) {
  const args = [
    '--suite=p0',
    `--phase=${phase}`,
    resume ? `--resume=${runId}` : `--run-id=${runId}`
  ]
  if (['selected', 'ui-core'].includes(phase)) args.push('--case=AUTH-01')
  return p0CaseContracts.parseP0Cli(args)
}

function s10RequestedPhaseSelection(phase) {
  const requestedScope = ['preflight', 'canonical', 'authority', 'report'].includes(phase)
    ? 'CONTROL'
    : phase === 'selected'
      ? 'SELECTED'
      : phase === 'all'
        ? 'ALL'
        : phase === 'cleanup'
          ? 'CLEANUP'
          : 'MATRIX'
  return {
    caseIds: ['selected', 'ui-core'].includes(phase) ? ['AUTH-01'] : [],
    phases: phase === 'ui-core' ? ['ui-core'] : [],
    profiles: [],
    viewports: [],
    metadata: {
      values: [
        `sha256:${createHash('sha256').update(`P0_SELECTION_PHASE\0${phase}`).digest('hex')}`,
        `sha256:${createHash('sha256').update(`P0_SELECTION_SCOPE\0${requestedScope}`).digest('hex')}`
      ]
    }
  }
}

async function createS10RequestedPhaseFixture(root, label, phase) {
  const artifactBase = join(root, label, 'artifacts')
  const runId = `p0-review1-s10-phase-${label}-a1`
  const options = s10RequestedPhaseOptions(runId, phase)
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const identity = review1P0Identity()
  const suffixes = ['abcdef123456', 'abcdef123457']
  const reservation = await smokeContracts.prepareP0RunReservation({
    options,
    plan,
    artifactBase,
    inheritedEnv: {},
    captureIdentity: async () => identity,
    databaseExists: async () => false,
    nextRunToken: () => `p0-review1-s10-phase-${label}-owner-token-a1`,
    nextRandomSuffix: () => suffixes.shift(),
    infrastructure: {
      async verifyComposePort() { return true },
      async inspectListener() { return null }
    },
    now: () => '2026-07-15T22:00:00.000Z'
  })
  return { artifactBase, identity, options, plan, reservation, runId }
}

function s10ArtifactSnapshot(paths) {
  return paths.map((path) => ({
    path,
    text: readFileSync(path, 'utf8'),
    size: statSync(path).size,
    mtimeMs: statSync(path).mtimeMs
  }))
}

test('resume rejects a different requested control or matrix phase before any mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-phase-mismatch-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const scenarios = [
    ['preflight', 'canonical'],
    ['canonical', 'authority'],
    ['authority', 'report'],
    ['report', 'all'],
    ['all', 'preflight'],
    ['selected', 'ui-core'],
    ['ui-core', 'selected']
  ]
  const observed = []

  for (const [persistedPhase, requestedPhase] of scenarios) {
    const label = `${persistedPhase}-${requestedPhase}`
    const fixture = await createS10RequestedPhaseFixture(root, label, persistedPhase)
    const paths = [
      fixture.reservation.ownershipPath,
      fixture.reservation.runStatePath,
      fixture.reservation.composeTarget.composeFiles[1]
    ]
    const before = s10ArtifactSnapshot(paths)
    const directoryEntries = {
      run: readdirSync(fixture.reservation.runRoot).toSorted(),
      control: readdirSync(join(fixture.reservation.runRoot, 'control')).toSorted(),
      compose: readdirSync(dirname(fixture.reservation.composeTarget.composeFiles[1])).toSorted()
    }
    const mutations = []
    const mutation = (kind) => {
      mutations.push(kind)
      throw new Error(`P0_TEST_RESUME_MUTATION_REACHED:${kind}`)
    }
    let failure
    try {
      await smokeContracts.prepareP0RunReservation({
        options: s10RequestedPhaseOptions(fixture.runId, requestedPhase, true),
        plan: p0CaseContracts.planP0Execution(
          s10RequestedPhaseOptions(fixture.runId, requestedPhase, true),
          P0_CASES
        ),
        artifactBase: fixture.artifactBase,
        inheritedEnv: {},
        captureIdentity: async () => mutation('git'),
        databaseExists: async () => mutation('database'),
        nextRunToken: () => mutation('artifact:owner'),
        nextRandomSuffix: () => mutation('artifact:database'),
        infrastructure: {
          async inspectDockerDaemon() { return mutation('docker') },
          async verifyComposePort() { return mutation('compose') },
          async verifyComposeContainers() { return mutation('docker:containers') },
          async inspectListener() { return mutation('process') }
        },
        redis: { async get() { return mutation('redis') } },
        postgres: { async readDatabaseOwnership() { return mutation('database') } },
        processManager: { async stopParentBackend() { return mutation('process') } }
      })
    } catch (error) {
      failure = error
    }
    observed.push({
      persistedPhase,
      requestedPhase,
      error: failure?.message ?? null,
      mutations,
      artifactsUnchanged: JSON.stringify(s10ArtifactSnapshot(paths)) === JSON.stringify(before),
      directoriesUnchanged: JSON.stringify({
        run: readdirSync(fixture.reservation.runRoot).toSorted(),
        control: readdirSync(join(fixture.reservation.runRoot, 'control')).toSorted(),
        compose: readdirSync(dirname(fixture.reservation.composeTarget.composeFiles[1])).toSorted()
      }) === JSON.stringify(directoryEntries)
    })
  }

  assert.deepEqual(observed, scenarios.map(([persistedPhase, requestedPhase]) => ({
    persistedPhase,
    requestedPhase,
    error: 'P0_RESUME_SELECTION_MISMATCH',
    mutations: [],
    artifactsUnchanged: true,
    directoriesUnchanged: true
  })))
})

test('resume accepts only the identical normalized requested phase scope', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-phase-identical-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const phases = [
    'preflight',
    'canonical',
    'authority',
    'report',
    'selected',
    'all',
    'ui-core'
  ]
  const observed = []

  for (const phase of phases) {
    const fixture = await createS10RequestedPhaseFixture(root, phase, phase)
    const active = JSON.parse(readFileSync(fixture.reservation.ownershipPath, 'utf8'))
    const state = JSON.parse(readFileSync(fixture.reservation.runStatePath, 'utf8'))
    const resumeOptions = s10RequestedPhaseOptions(fixture.runId, phase, true)
    const verificationCalls = []
    const resumed = await smokeContracts.prepareP0RunReservation({
      options: resumeOptions,
      plan: p0CaseContracts.planP0Execution(resumeOptions, P0_CASES),
      artifactBase: fixture.artifactBase,
      inheritedEnv: {},
      captureIdentity: async () => {
        verificationCalls.push('git')
        return fixture.identity
      },
      databaseExists: async () => {
        verificationCalls.push('database')
        return false
      },
      nextRunToken: () => {
        verificationCalls.push('artifact:owner')
        throw new Error('P0_TEST_RESUME_OWNER_REWRITE_FORBIDDEN')
      },
      nextRandomSuffix: () => {
        verificationCalls.push('artifact:database')
        throw new Error('P0_TEST_RESUME_DATABASE_REWRITE_FORBIDDEN')
      },
      infrastructure: {
        async verifyComposePort() {
          verificationCalls.push('compose:verify')
          return true
        },
        async inspectListener() {
          verificationCalls.push('process:verify')
          return null
        }
      }
    })
    observed.push({
      phase,
      expected: s10RequestedPhaseSelection(phase),
      control: active.selection,
      state: state.selection,
      current: fixture.reservation.runState.selection,
      resumed: resumed.runState.selection,
      accepted: resumed.resumed,
      gitChecks: verificationCalls.filter((call) => call === 'git').length,
      rewriteCalls: verificationCalls.filter((call) => call.startsWith('artifact:'))
    })
  }

  assert.deepEqual(observed, phases.map((phase) => {
    const selection = s10RequestedPhaseSelection(phase)
    return {
      phase,
      expected: selection,
      control: selection,
      state: selection,
      current: selection,
      resumed: selection,
      accepted: true,
      gitChecks: 1,
      rewriteCalls: []
    }
  }))
})

function s10ComposeFilesystemSnapshot(paths) {
  return paths.map((path) => {
    const parent = dirname(path)
    const parentEntries = existsSync(parent) && statSync(parent).isDirectory()
      ? readdirSync(parent).sort()
      : null
    let metadata
    try {
      metadata = lstatSync(path)
    } catch (error) {
      if (error?.code === 'ENOENT') return { path, exists: false, parentEntries }
      throw error
    }
    return {
      path,
      exists: true,
      file: metadata.isFile(),
      directory: metadata.isDirectory(),
      symbolicLink: metadata.isSymbolicLink(),
      dev: metadata.dev,
      ino: metadata.ino,
      mode: metadata.mode,
      nlink: metadata.nlink,
      size: metadata.size,
      ctimeMs: metadata.ctimeMs,
      mtimeMs: metadata.mtimeMs,
      realpath: realpathSync(path),
      text: metadata.isFile() ? readFileSync(path, 'utf8') : null,
      parentEntries
    }
  })
}

async function s10AttemptComposeResume(fixture) {
  const calls = []
  const options = s10RequestedPhaseOptions(fixture.runId, 'selected', true)
  let message = 'ACCEPTED'
  try {
    await smokeContracts.prepareP0RunReservation({
      options,
      plan: p0CaseContracts.planP0Execution(options, P0_CASES),
      artifactBase: fixture.artifactBase,
      inheritedEnv: {},
      captureIdentity: async () => {
        calls.push('git')
        return fixture.identity
      },
      databaseExists: async () => {
        calls.push('database')
        return false
      },
      nextRunToken: () => {
        calls.push('write:owner')
        return 'p0-review1-s10-compose-unexpected-owner-token'
      },
      nextRandomSuffix: () => {
        calls.push('write:database')
        return 'abcdef123458'
      },
      infrastructure: {
        async inspectDockerDaemon() {
          calls.push('infrastructure:docker')
          return { endpoint: 'npipe:////./pipe/docker_engine' }
        },
        async verifyComposePort() {
          calls.push('infrastructure:compose')
          return true
        },
        async inspectListener() {
          calls.push('infrastructure:listener')
          return null
        }
      }
    })
  } catch (error) {
    message = error?.message ?? String(error)
  }
  const rejected = message === 'P0_CONTROL_FILE_UNSAFE'
    || /^P0_COMPOSE_(?:OVERRIDE|RUNTIME)_(?:MISSING|INVALID|UNSAFE|ESCAPE)$/.test(message)
  return { outcome: rejected ? 'REJECTED' : message, calls }
}

test('resume never creates or rewrites the stable compose override', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-compose-readonly-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const scenarios = [
    {
      label: 'byte-drift',
      mutate({ overridePath, runtimeRoot }) {
        writeFileSync(overridePath, 'services:\n  postgres:\n    image: foreign-byte-drift\n')
        const fixedTime = new Date('2026-07-15T22:30:00.000Z')
        utimesSync(overridePath, fixedTime, fixedTime)
        return [runtimeRoot, overridePath]
      }
    },
    {
      label: 'missing-runtime',
      mutate({ artifactBase, overridePath, runtimeRoot }) {
        rmSync(runtimeRoot, { recursive: true, force: true })
        return [artifactBase, runtimeRoot, overridePath]
      }
    }
  ]
  const observed = []

  for (const scenario of scenarios) {
    const fixture = await createS10RequestedPhaseFixture(
      root,
      `compose-readonly-${scenario.label}`,
      'selected'
    )
    const overridePath = fixture.reservation.composeTarget.composeFiles[1]
    const runtimeRoot = dirname(overridePath)
    const paths = scenario.mutate({
      artifactBase: fixture.artifactBase,
      overridePath,
      runtimeRoot
    })
    const before = s10ComposeFilesystemSnapshot(paths)
    const attempt = await s10AttemptComposeResume(fixture)
    const after = s10ComposeFilesystemSnapshot(paths)
    observed.push({
      label: scenario.label,
      ...attempt,
      bytesSizeMtimeAndDirectoryEntriesUnchanged: JSON.stringify(after) === JSON.stringify(before)
    })
  }

  assert.deepEqual(observed, scenarios.map(({ label }) => ({
    label,
    outcome: 'REJECTED',
    calls: [],
    bytesSizeMtimeAndDirectoryEntriesUnchanged: true
  })))
})

test('resume rejects a missing or non-regular compose override before infrastructure', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-compose-unsafe-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const scenarios = [
    {
      label: 'missing',
      mutate({ overridePath }) {
        rmSync(overridePath)
        return []
      }
    },
    {
      label: 'hardlink-nlink',
      mutate({ overridePath, runtimeRoot }) {
        const aliasPath = join(runtimeRoot, 'compose.loopback.alias.yml')
        linkSync(overridePath, aliasPath)
        return [aliasPath]
      }
    },
    {
      label: 'directory-non-regular',
      mutate({ overridePath }) {
        rmSync(overridePath)
        mkdirSync(overridePath)
        const markerPath = join(overridePath, 'foreign-marker.txt')
        writeFileSync(markerPath, 'foreign-directory')
        return [markerPath]
      }
    },
    {
      label: 'junction-reparse-path-escape',
      mutate({ overridePath, runtimeRoot }) {
        const canonicalText = readFileSync(overridePath, 'utf8')
        const outsideRuntime = join(root, 'outside-runtime')
        mkdirSync(outsideRuntime)
        const outsideOverride = join(outsideRuntime, 'compose.loopback.yml')
        writeFileSync(outsideOverride, canonicalText)
        rmSync(runtimeRoot, { recursive: true, force: true })
        symlinkSync(outsideRuntime, runtimeRoot, 'junction')
        return [outsideRuntime, outsideOverride]
      }
    }
  ]
  const observed = []

  for (const scenario of scenarios) {
    const fixture = await createS10RequestedPhaseFixture(
      root,
      `compose-${scenario.label}`,
      'selected'
    )
    const overridePath = fixture.reservation.composeTarget.composeFiles[1]
    const runtimeRoot = dirname(overridePath)
    const extraPaths = scenario.mutate({ overridePath, runtimeRoot })
    const paths = [runtimeRoot, overridePath, ...extraPaths]
    const before = s10ComposeFilesystemSnapshot(paths)
    const attempt = await s10AttemptComposeResume(fixture)
    const after = s10ComposeFilesystemSnapshot(paths)
    observed.push({
      label: scenario.label,
      ...attempt,
      bytesSizeMtimeAndDirectoryEntriesUnchanged: JSON.stringify(after) === JSON.stringify(before)
    })
  }

  assert.deepEqual(observed, scenarios.map(({ label }) => ({
    label,
    outcome: 'REJECTED',
    calls: [],
    bytesSizeMtimeAndDirectoryEntriesUnchanged: true
  })))
})

function s10CleanupDockerInspection({ id, service, image, port, composeFiles }) {
  return JSON.stringify([{
    Id: id,
    State: { Running: true },
    Config: {
      Image: image,
      Env: service === 'postgres'
        ? ['POSTGRES_USER=postgres', 'POSTGRES_PASSWORD=password']
        : [],
      Labels: {
        'com.docker.compose.service': service,
        'com.docker.compose.project': 'infra',
        'com.docker.compose.project.working_dir': dirname(composeFiles[0]),
        'com.docker.compose.project.config_files': composeFiles.join(',')
      }
    },
    NetworkSettings: {
      Ports: {
        [`${port}/tcp`]: [{ HostIp: '127.0.0.1', HostPort: String(port) }]
      }
    }
  }])
}

async function createS10CleanupComposeFixture(root, label) {
  const artifactBase = join(root, label, 'artifacts')
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const runId = `p0-review1-s10-cleanup-${label}-a1`
  const runToken = `p0-review1-s10-cleanup-${label}-owner-token-a1`
  const database = `fx_p0_user_e2e_s10_cleanup_${label.replaceAll('-', '_')}_1_abcdef123456`
  const postgresId = 'a'.repeat(64)
  const redisId = 'b'.repeat(64)
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: s10RequestedPhaseSelection('cleanup'),
    database: { canonical: database, matrix: database },
    now: () => '2026-07-15T23:00:00.000Z'
  })
  markP0RedisSnapshotReady(control, [{
    key: 'quote:BTCUSDT',
    exists: true,
    value: 'before-cleanup',
    expiresAtMs: 1900000000000
  }], ['quote:BTCUSDT'])
  const persistedIdentity = {
    project: 'infra',
    postgres: {
      id: postgresId,
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432
    },
    redis: {
      id: redisId,
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379
    }
  }
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.composeTarget = {
    expectedProject: composeTarget.expectedProject,
    composeFiles: [...composeTarget.composeFiles]
  }
  active.composeIdentity = persistedIdentity
  active.journal = {
    schemaVersion: 1,
    sequence: 1,
    resources: [{
      type: 'database',
      id: database,
      sequence: 1,
      state: 'STARTED',
      recordedAt: '2026-07-15T23:00:01.000Z',
      startedAt: '2026-07-15T23:00:02.000Z'
    }]
  }
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  return {
    artifactBase,
    composeTarget,
    control,
    database,
    persistedIdentity,
    runId,
    runToken
  }
}

function createS10CleanupAdapterBoundary(fixture, liveIdentity, {
  identityForVerification = () => liveIdentity,
  onVerificationComplete = () => {}
} = {}) {
  const commands = []
  const events = []
  const redisRequests = []
  const verifiedIdentities = []
  const redisState = {
    owner: fixture.runToken,
    restored: null,
    expiresAtMs: null,
    receipt: null
  }
  const composeFiles = [...fixture.composeTarget.composeFiles]
  const releaseOwnershipScript = [
    "local owner = redis.call('get', KEYS[1])",
    "local receipt = redis.call('get', KEYS[2])",
    "if owner == ARGV[1] then",
    "  if receipt and receipt ~= ARGV[2] then return 'MISMATCH' end",
    "  redis.call('del', KEYS[1])",
    "  redis.call('set', KEYS[2], ARGV[2])",
    "  return 'RELEASED'",
    'end',
    "if not owner and receipt == ARGV[2] then return 'ALREADY_RELEASED' end",
    "return 'MISMATCH'"
  ].join('\n')
  const removeCleanupReceiptScript = [
    "local receipt = redis.call('get', KEYS[1])",
    "if not receipt then return 'ALREADY_ABSENT' end",
    "if receipt ~= ARGV[1] then return 'MISMATCH' end",
    "redis.call('del', KEYS[1])",
    "return 'REMOVED'"
  ].join('\n')
  const cleanupReceiptKey = `p0:e2e:cleanup:${fixture.control.ownerId}`
  let inspectedIdentity
  let verificationIndex = 0
  const runCommand = async (descriptor) => {
    commands.push({
      id: descriptor.id,
      args: [...(descriptor.args ?? [])],
      stdin: descriptor.stdin ?? null
    })
    events.push(`command:${descriptor.id}`)
    if (descriptor.id === 'docker-context-inspect') {
      return { status: 0, stdout: 'npipe:////./pipe/docker_engine\n' }
    }
    if (descriptor.id === 'compose-postgres-id') {
      inspectedIdentity = structuredClone(identityForVerification(verificationIndex))
      verificationIndex += 1
      return { status: 0, stdout: `${inspectedIdentity.postgres.id}\n` }
    }
    if (descriptor.id === 'compose-redis-id') {
      return { status: 0, stdout: `${inspectedIdentity.redis.id}\n` }
    }
    if (descriptor.id === 'compose-postgres-inspect') {
      return {
        status: 0,
        stdout: s10CleanupDockerInspection({
          ...inspectedIdentity.postgres,
          service: 'postgres',
          port: 5432,
          composeFiles
        })
      }
    }
    if (descriptor.id === 'compose-redis-inspect') {
      const result = {
        status: 0,
        stdout: s10CleanupDockerInspection({
          ...inspectedIdentity.redis,
          service: 'redis',
          port: 6379,
          composeFiles
        })
      }
      onVerificationComplete({
        identity: structuredClone(inspectedIdentity),
        verificationIndex
      })
      verifiedIdentities.push(structuredClone(inspectedIdentity))
      return result
    }
    if (descriptor.id === 'postgres-owner-read') {
      return {
        status: 0,
        stdout: `${fixture.database}\tp0-owner:${fixture.runToken}\n`
      }
    }
    if (descriptor.id === 'postgres-admin') return { status: 0, stdout: '' }
    throw new Error(`P0_TEST_UNEXPECTED_COMMAND: ${descriptor.id}`)
  }
  const redisRequest = async (args, details) => {
    redisRequests.push({ args: [...args], host: details.host, port: details.port })
    events.push(`redis:${args[0]}`)
    if (args[0] === 'GET' && args[1] === 'p0:e2e:owner') return redisState.owner
    if (args[0] === 'SET' && args[1] === 'quote:BTCUSDT') {
      redisState.restored = args[2]
      return 'OK'
    }
    if (args[0] === 'PEXPIREAT' && args[1] === 'quote:BTCUSDT') {
      redisState.expiresAtMs = Number(args[2])
      return 1
    }
    if (args[0] === 'EVAL' && args[1] === releaseOwnershipScript) {
      assert.deepEqual(args.slice(2), [
        '2',
        'p0:e2e:owner',
        cleanupReceiptKey,
        fixture.runToken,
        fixture.control.ownerId
      ])
      if (redisState.owner === fixture.runToken) {
        if (redisState.receipt !== null
          && redisState.receipt !== fixture.control.ownerId) {
          return 'MISMATCH'
        }
        redisState.owner = null
        redisState.receipt = fixture.control.ownerId
        return 'RELEASED'
      }
      return redisState.owner === null
        && redisState.receipt === fixture.control.ownerId
        ? 'ALREADY_RELEASED'
        : 'MISMATCH'
    }
    if (args[0] === 'EVAL' && args[1] === removeCleanupReceiptScript) {
      assert.deepEqual(args.slice(2), [
        '1',
        cleanupReceiptKey,
        fixture.control.ownerId
      ])
      if (redisState.receipt === null) return 'ALREADY_ABSENT'
      if (redisState.receipt !== fixture.control.ownerId) return 'MISMATCH'
      redisState.receipt = null
      return 'REMOVED'
    }
    throw new Error(`P0_TEST_UNEXPECTED_REDIS: ${args[0]}`)
  }
  const infrastructure = smokeContracts.createLocalInfrastructureAdapter(
    composeFiles[0],
    runCommand,
    {
      portIsOpen: async (port) => {
        events.push(`port:${port}`)
        return false
      }
    }
  )
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase: fixture.artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      `s10-cleanup-boundary:${fixture.runId}`
    ),
    infrastructure,
    redisRequest,
    runCommand,
    now: () => '2026-07-15T23:30:00.000Z'
  })
  return { commands, dependencies, events, redisRequests, redisState, verifiedIdentities }
}

test('default cleanup revalidates persisted compose and container identity before DB or Redis recovery', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-cleanup-revalidate-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fixture = await createS10CleanupComposeFixture(root, 'success')
  const boundary = createS10CleanupAdapterBoundary(fixture, fixture.persistedIdentity)
  let result = null
  let error = null
  try {
    result = await boundary.dependencies.cleanup(undefined, {}, { runId: fixture.runId })
  } catch (cause) {
    error = cause?.message ?? String(cause)
  }
  const commandIds = boundary.commands.map(({ id }) => id)
  const verificationIds = commandIds.filter((id) => (
    id === 'docker-context-inspect' || id.startsWith('compose-')
  ))
  const recoveryIndexes = boundary.events
    .map((event, index) => ({ event, index }))
    .filter(({ event }) => event.startsWith('port:')
      || event.startsWith('command:postgres-')
      || event.startsWith('redis:'))
    .map(({ index }) => index)
  const verificationEnd = boundary.events.indexOf('command:compose-redis-inspect')
  const marker = JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8'))
  const expectedVerificationIds = [
    'docker-context-inspect',
    'compose-postgres-id',
    'compose-postgres-inspect',
    'compose-redis-id',
    'compose-redis-inspect'
  ]
  const expectedComposeCommands = ['postgres', 'redis'].map((service) => [
    'compose', '--project-name', 'infra',
    '-f', fixture.composeTarget.composeFiles[0],
    '-f', fixture.composeTarget.composeFiles[1],
    'ps', '-q', service
  ])
  const expectedRedisTransactions = [
    ['GET', 'SET', 'PEXPIREAT', 'EVAL'],
    ['EVAL']
  ]
  const expectedVerificationCycles = 2 + (expectedRedisTransactions.length * 2)

  assert.deepEqual({
    error,
    result,
    verificationIds,
    composeCommands: boundary.commands
      .filter(({ id }) => id === 'compose-postgres-id' || id === 'compose-redis-id')
      .map(({ args }) => args),
    postgresContainerIds: boundary.commands
      .filter(({ id }) => id.startsWith('postgres-'))
      .map(({ args }) => args[2]),
    dropSql: boundary.commands.find(({ id }) => id === 'postgres-admin')?.stdin ?? null,
    redisCommands: boundary.redisRequests.map(({ args }) => args[0]),
    redisBindings: boundary.redisRequests.map(({ host, port }) => ({ host, port })),
    portChecks: boundary.events
      .filter((event) => event.startsWith('port:'))
      .map((event) => Number(event.slice('port:'.length))),
    verificationBeforeRecovery: verificationEnd >= 0
      && recoveryIndexes.every((index) => index > verificationEnd),
    verifiedIdentities: boundary.verifiedIdentities,
    redisState: boundary.redisState,
    markerStatus: marker.status,
    markerHasOwnerToken: Object.hasOwn(marker, 'ownerToken')
  }, {
    error: null,
    result: { status: 'CLEANED', databases: 1, redis: 'RESTORED', restored: 1 },
    verificationIds: Array.from(
      { length: expectedVerificationCycles },
      () => expectedVerificationIds
    ).flat(),
    composeCommands: Array.from(
      { length: expectedVerificationCycles },
      () => expectedComposeCommands
    ).flat(),
    postgresContainerIds: [
      fixture.persistedIdentity.postgres.id,
      fixture.persistedIdentity.postgres.id,
      fixture.persistedIdentity.postgres.id
    ],
    dropSql: `DROP DATABASE "${fixture.database}" WITH (FORCE);\n`,
    redisCommands: expectedRedisTransactions.flat(),
    redisBindings: Array.from(
      { length: expectedRedisTransactions.flat().length },
      () => ({ host: '127.0.0.1', port: 6379 })
    ),
    portChecks: [18086, 5199, 5200],
    verificationBeforeRecovery: true,
    verifiedIdentities: Array.from(
      { length: expectedVerificationCycles },
      () => fixture.persistedIdentity
    ),
    redisState: { owner: null, restored: 'before-cleanup', expiresAtMs: 1900000000000, receipt: null },
    markerStatus: 'CLEANED',
    markerHasOwnerToken: false
  })
})

test('default cleanup rejects compose identity drift before DB Redis ports or CLEANED publication', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-cleanup-drift-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const scenarios = [
    ['postgres-id', 'postgres', 'c'.repeat(64)],
    ['redis-id', 'redis', 'd'.repeat(64)]
  ]
  const observed = []

  for (const [label, service, liveId] of scenarios) {
    const fixture = await createS10CleanupComposeFixture(root, label)
    const liveIdentity = structuredClone(fixture.persistedIdentity)
    liveIdentity[service].id = liveId
    const boundary = createS10CleanupAdapterBoundary(fixture, liveIdentity)
    const ownershipBefore = readFileSync(fixture.control.ownershipPath, 'utf8')
    let error = null
    try {
      await boundary.dependencies.cleanup(undefined, {}, { runId: fixture.runId })
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    const commandIds = boundary.commands.map(({ id }) => id)
    observed.push({
      label,
      rejected: typeof error === 'string' && error.startsWith('P0_'),
      dockerChecks: commandIds.filter((id) => (
        id === 'docker-context-inspect' || id.startsWith('compose-')
      )),
      databaseExecs: commandIds.filter((id) => id.startsWith('postgres-')),
      redisRequests: boundary.redisRequests.length,
      portChecks: boundary.events.filter((event) => event.startsWith('port:')),
      activeUnchanged: readFileSync(fixture.control.ownershipPath, 'utf8') === ownershipBefore,
      redisOwner: boundary.redisState.owner,
      cleanedPublished: existsSync(join(fixture.control.runRoot, 'control', 'cleaned.json'))
    })
  }

  assert.deepEqual(observed, scenarios.map(([label]) => ({
    label,
    rejected: true,
    dockerChecks: [
      'docker-context-inspect',
      'compose-postgres-id',
      'compose-postgres-inspect',
      'compose-redis-id',
      'compose-redis-inspect'
    ],
    databaseExecs: [],
    redisRequests: 0,
    portChecks: [],
    activeUnchanged: true,
    redisOwner: `p0-review1-s10-cleanup-${label}-owner-token-a1`,
    cleanedPublished: false
  })))
})

test('prepared cleanup revalidates compose identity before database Redis or CLEANED mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s12-prepared-cleanup-drift-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fixture = await createS10CleanupComposeFixture(root, 'prepared-drift')
  const liveIdentity = structuredClone(fixture.persistedIdentity)
  liveIdentity.postgres.id = 'c'.repeat(64)
  const boundary = createS10CleanupAdapterBoundary(fixture, liveIdentity)
  const ownershipBefore = readFileSync(fixture.control.ownershipPath, 'utf8')
  const activeBefore = JSON.parse(ownershipBefore)
  const redisRecovery = JSON.parse(readFileSync(
    join(fixture.control.runRoot, 'control', 'redis.json'),
    'utf8'
  ))
  const redisBefore = structuredClone(boundary.redisState)
  assert.equal(activeBefore.redisState, 'SNAPSHOT_READY')
  assert.deepEqual(activeBefore.journal.resources.map(({ type, id }) => ({ type, id })), [{
    type: 'database',
    id: fixture.database
  }])

  const prepared = {
    artifactBase: fixture.artifactBase,
    runRoot: fixture.control.runRoot,
    ownerToken: fixture.runToken,
    ownerId: fixture.control.ownerId,
    ownedDatabaseSegments: [fixture.database],
    redisSnapshot: structuredClone(redisRecovery.snapshot),
    touchedRedisKeys: [...redisRecovery.touchedKeys],
    redisRecoveryMode: 'SNAPSHOT'
  }
  let error = null
  try {
    await boundary.dependencies.cleanup(prepared, {}, { runId: fixture.runId })
  } catch (cause) {
    error = cause?.message ?? String(cause)
  }

  const commandIds = boundary.commands.map(({ id }) => id)
  assert.deepEqual({
    rejected: typeof error === 'string' && error.startsWith('P0_'),
    composeChecks: commandIds.filter((id) => (
      id === 'docker-context-inspect' || id.startsWith('compose-')
    )),
    verifiedIdentities: boundary.verifiedIdentities,
    databaseCommands: commandIds.filter((id) => (
      id === 'postgres-owner-read' || id === 'postgres-admin'
    )),
    redisRequests: boundary.redisRequests,
    portChecks: boundary.events.filter((event) => event.startsWith('port:')),
    redisState: boundary.redisState,
    ownershipUnchanged: readFileSync(fixture.control.ownershipPath, 'utf8') === ownershipBefore,
    markerStatus: JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8')).status,
    cleanedPublished: existsSync(join(
      fixture.control.runRoot,
      'control',
      'cleaned.json'
    ))
  }, {
    rejected: true,
    composeChecks: [
      'docker-context-inspect',
      'compose-postgres-id',
      'compose-postgres-inspect',
      'compose-redis-id',
      'compose-redis-inspect'
    ],
    verifiedIdentities: [liveIdentity],
    databaseCommands: [],
    redisRequests: [],
    portChecks: [],
    redisState: redisBefore,
    ownershipUnchanged: true,
    markerStatus: 'ACTIVE',
    cleanedPublished: false
  })
})

function removeS10CleanupDatabaseJournal(fixture) {
  const active = JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8'))
  active.journal = { schemaVersion: 1, sequence: 0, resources: [] }
  writeFileSync(fixture.control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
}

function s10RedisComposeVerificationEvents() {
  return [
    'command:docker-context-inspect',
    'command:compose-postgres-id',
    'command:compose-postgres-inspect',
    'command:compose-redis-id',
    'command:compose-redis-inspect'
  ]
}

function s11RedisBinding(id = 'b'.repeat(64)) {
  return {
    id,
    image: 'redis:7',
    host: '127.0.0.1',
    hostPort: 6379
  }
}

function createS11RedisFakeSession({ id, events, respond }) {
  let state = 'OPEN'
  let closeRecorded = false
  return {
    id,
    get state() {
      return state
    },
    async command(args, { signal } = {}) {
      if (state !== 'OPEN') throw new Error('P0_REDIS_SESSION_CLOSED')
      if (signal?.aborted) {
        throw signal.reason instanceof Error ? signal.reason : new Error('P0_ABORTED')
      }
      const command = args.map((argument) => String(argument))
      events.push({ type: 'command', sessionId: id, args: command })
      return respond(command, {
        fail() {
          if (state === 'OPEN') state = 'FAILED'
        }
      })
    },
    fail() {
      if (state === 'OPEN') state = 'FAILED'
    },
    close() {
      if (!closeRecorded) {
        events.push({ type: 'close', sessionId: id, state })
        closeRecorded = true
      }
      if (state === 'OPEN') state = 'CLOSED'
    }
  }
}

function decodeS11RedisRespCommand(payload) {
  const bytes = Buffer.from(payload)
  let offset = 0
  const readLine = () => {
    const end = bytes.indexOf('\r\n', offset)
    assert.notEqual(end, -1, 'P0_TEST_RESP_LINE_REQUIRED')
    const line = bytes.subarray(offset, end).toString('utf8')
    offset = end + 2
    return line
  }
  const header = readLine()
  assert.match(header, /^\*\d+$/)
  const count = Number(header.slice(1))
  const args = []
  for (let index = 0; index < count; index += 1) {
    const lengthHeader = readLine()
    assert.match(lengthHeader, /^\$\d+$/)
    const length = Number(lengthHeader.slice(1))
    const end = offset + length
    assert.equal(bytes.subarray(end, end + 2).toString('utf8'), '\r\n')
    args.push(bytes.subarray(offset, end).toString('utf8'))
    offset = end + 2
  }
  assert.equal(offset, bytes.length)
  return args
}

function encodeS11RedisRespReply(reply) {
  if (reply.type === 'simple') return Buffer.from(`+${reply.value}\r\n`)
  if (reply.type === 'integer') return Buffer.from(`:${reply.value}\r\n`)
  if (reply.type === 'bulk' && reply.value === null) return Buffer.from('$-1\r\n')
  if (reply.type === 'bulk') {
    const value = Buffer.from(String(reply.value))
    return Buffer.concat([
      Buffer.from(`$${value.length}\r\n`),
      value,
      Buffer.from('\r\n')
    ])
  }
  throw new Error('P0_TEST_RESP_REPLY_INVALID')
}

function createS11RedisFakeSocket({ id, events, onCommand }) {
  const socket = new EventEmitter()
  let closeEmitted = false
  socket.id = id
  socket.destroyed = false
  const emitClose = () => {
    if (closeEmitted) return
    closeEmitted = true
    socket.destroyed = true
    events.push({ type: 'socket-close', socketId: id })
    socket.emit('close', false)
  }
  socket.setNoDelay = () => socket
  socket.setTimeout = () => socket
  socket.write = (payload) => {
    if (socket.destroyed) return false
    const args = decodeS11RedisRespCommand(payload)
    events.push({ type: 'resp', socketId: id, args })
    const outcome = onCommand(args)
    queueMicrotask(() => {
      if (outcome?.reply) socket.emit('data', encodeS11RedisRespReply(outcome.reply))
      if (outcome?.disconnect) {
        events.push({ type: 'socket-end', socketId: id })
        socket.emit('end')
        emitClose()
      }
    })
    return true
  }
  socket.destroy = () => {
    emitClose()
    return socket
  }
  socket.end = () => {
    emitClose()
    return socket
  }
  socket.open = (onConnect) => {
    if (typeof onConnect === 'function') socket.once('connect', onConnect)
    queueMicrotask(() => socket.emit('connect'))
  }
  return socket
}

test('Redis verified transaction uses one physical session for every command and nested operation', async () => {
  const binding = s11RedisBinding()
  const runToken = 'p0-review1-s11-redis-session-owner-token-a1'
  const events = []
  let createSessionCalls = 0
  let session
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      throw new Error(`P0_TEST_LEGACY_REDIS_REQUEST_USED:${args[0]}`)
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      events.push({ type: 'verify', stage })
      assert.deepEqual(expectedBinding, binding)
      return structuredClone(binding)
    },
    {
      createSession({ host, port }) {
        createSessionCalls += 1
        const sessionId = `redis-session-${createSessionCalls}`
        events.push({ type: 'connect', sessionId, host, port })
        session = createS11RedisFakeSession({
          id: sessionId,
          events,
          respond(args) {
            if (args[0] === 'SET') return 'OK'
            if (args[0] === 'GET' && args[1] === 'quote:BTCUSDT') return 'snapshot-value'
            if (args[0] === 'PEXPIRETIME') return 1900000000000
            if (args[0] === 'GET' && args[1] === 'p0:e2e:owner') return runToken
            throw new Error(`P0_TEST_UNEXPECTED_REDIS_COMMAND:${args[0]}`)
          }
        })
        return session
      }
    }
  )

  const result = await redis.runVerifiedTransaction(async () => {
    const acquired = await redis.setNx('p0:e2e:owner', runToken)
    const exact = await redis.readExact('quote:BTCUSDT')
    const nestedOwner = await redis.runVerifiedTransaction(
      () => redis.get('p0:e2e:owner')
    )
    return { acquired, exact, nestedOwner }
  })

  assert.deepEqual(result, {
    acquired: true,
    exact: {
      exists: true,
      value: 'snapshot-value',
      expiresAtMs: 1900000000000
    },
    nestedOwner: runToken
  })
  assert.equal(createSessionCalls, 1)
  assert.deepEqual(
    events.filter(({ type }) => type === 'verify').map(({ stage }) => stage),
    ['PRE_CONNECT', 'POST_CONNECT']
  )
  const commands = events.filter(({ type }) => type === 'command')
  assert.deepEqual(commands, [
    {
      type: 'command',
      sessionId: 'redis-session-1',
      args: ['SET', 'p0:e2e:owner', runToken, 'NX']
    },
    {
      type: 'command',
      sessionId: 'redis-session-1',
      args: ['GET', 'quote:BTCUSDT']
    },
    {
      type: 'command',
      sessionId: 'redis-session-1',
      args: ['PEXPIRETIME', 'quote:BTCUSDT']
    },
    {
      type: 'command',
      sessionId: 'redis-session-1',
      args: ['GET', 'p0:e2e:owner']
    }
  ])
  assert.deepEqual([...new Set(commands.map(({ sessionId }) => sessionId))], ['redis-session-1'])
  assert.deepEqual(events.filter(({ type }) => type === 'close'), [
    { type: 'close', sessionId: 'redis-session-1', state: 'OPEN' }
  ])
  assert.equal(session.state, 'CLOSED')
})

test('Redis readiness verifies before and after connect then PINGs on that same session', async () => {
  const binding = s11RedisBinding()
  const events = []
  let createSessionCalls = 0
  let session
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      events.push({ type: 'legacy', args: [...args] })
      return 'PONG'
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      assert.deepEqual(expectedBinding, binding)
      events.push({ type: 'verify', stage })
      return structuredClone(binding)
    },
    {
      createSession({ host, port }) {
        createSessionCalls += 1
        const sessionId = `redis-readiness-${createSessionCalls}`
        events.push({ type: 'connect', sessionId, host, port })
        session = createS11RedisFakeSession({
          id: sessionId,
          events,
          respond(args) {
            assert.deepEqual(args, ['PING'])
            return 'PONG'
          }
        })
        return session
      }
    }
  )

  await redis.waitUntilReady()

  assert.equal(createSessionCalls, 1)
  assert.deepEqual(events, [
    { type: 'verify', stage: 'PRE_CONNECT' },
    {
      type: 'connect',
      sessionId: 'redis-readiness-1',
      host: '127.0.0.1',
      port: 6379
    },
    { type: 'verify', stage: 'POST_CONNECT' },
    {
      type: 'command',
      sessionId: 'redis-readiness-1',
      args: ['PING']
    },
    {
      type: 'close',
      sessionId: 'redis-readiness-1',
      state: 'OPEN'
    }
  ])
  assert.equal(session.state, 'CLOSED')

  const driftBinding = s11RedisBinding('c'.repeat(64))
  const driftEvents = []
  let driftCreateSessionCalls = 0
  let driftVerificationAttempts = 0
  let driftSession
  const driftRedis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      driftEvents.push({ type: 'legacy', args: [...args] })
      return 'PONG'
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      assert.deepEqual(expectedBinding, binding)
      driftEvents.push({ type: 'verify', stage })
      if (stage === 'PRE_CONNECT') {
        driftVerificationAttempts += 1
        if (driftVerificationAttempts > 1) {
          throw new Error('P0_TEST_READINESS_IDENTITY_MISMATCH_RETRIED')
        }
      }
      return structuredClone(stage === 'POST_CONNECT' ? driftBinding : binding)
    },
    {
      createSession({ host, port }) {
        driftCreateSessionCalls += 1
        const sessionId = 'redis-readiness-drift-' + driftCreateSessionCalls
        driftEvents.push({ type: 'connect', sessionId, host, port })
        driftSession = createS11RedisFakeSession({
          id: sessionId,
          events: driftEvents,
          respond() {
            return 'PONG'
          }
        })
        return driftSession
      }
    }
  )

  await assert.rejects(
    driftRedis.waitUntilReady(),
    { message: 'P0_REDIS_CONTAINER_BINDING_MISMATCH' }
  )
  assert.equal(driftVerificationAttempts, 1)
  assert.equal(driftCreateSessionCalls, 1)
  assert.deepEqual(driftEvents, [
    { type: 'verify', stage: 'PRE_CONNECT' },
    {
      type: 'connect',
      sessionId: 'redis-readiness-drift-1',
      host: '127.0.0.1',
      port: 6379
    },
    { type: 'verify', stage: 'POST_CONNECT' },
    {
      type: 'close',
      sessionId: 'redis-readiness-drift-1',
      state: 'OPEN'
    }
  ])
  assert.equal(driftSession.state, 'CLOSED')

  const retryEvents = []
  const retrySessions = []
  let retryCreateSessionCalls = 0
  const retryRedis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      throw new Error('P0_TEST_LEGACY_REDIS_REQUEST_USED:' + args[0])
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      assert.deepEqual(expectedBinding, binding)
      retryEvents.push({ type: 'verify', stage })
      return structuredClone(binding)
    },
    {
      createSession({ host, port }) {
        retryCreateSessionCalls += 1
        const sessionId = 'redis-readiness-retry-' + retryCreateSessionCalls
        retryEvents.push({ type: 'connect', sessionId, host, port })
        const attempt = retryCreateSessionCalls
        const retrySession = createS11RedisFakeSession({
          id: sessionId,
          events: retryEvents,
          respond(args, { fail }) {
            assert.deepEqual(args, ['PING'])
            if (attempt === 1) {
              fail()
              throw new Error('P0_TEST_REDIS_TRANSPORT_DROP')
            }
            return 'PONG'
          }
        })
        retrySessions.push(retrySession)
        return retrySession
      }
    }
  )

  await retryRedis.waitUntilReady()
  assert.equal(retryCreateSessionCalls, 2)
  assert.deepEqual(retryEvents, [
    { type: 'verify', stage: 'PRE_CONNECT' },
    {
      type: 'connect',
      sessionId: 'redis-readiness-retry-1',
      host: '127.0.0.1',
      port: 6379
    },
    { type: 'verify', stage: 'POST_CONNECT' },
    {
      type: 'command',
      sessionId: 'redis-readiness-retry-1',
      args: ['PING']
    },
    {
      type: 'close',
      sessionId: 'redis-readiness-retry-1',
      state: 'FAILED'
    },
    { type: 'verify', stage: 'PRE_CONNECT' },
    {
      type: 'connect',
      sessionId: 'redis-readiness-retry-2',
      host: '127.0.0.1',
      port: 6379
    },
    { type: 'verify', stage: 'POST_CONNECT' },
    {
      type: 'command',
      sessionId: 'redis-readiness-retry-2',
      args: ['PING']
    },
    {
      type: 'close',
      sessionId: 'redis-readiness-retry-2',
      state: 'OPEN'
    }
  ])
  assert.deepEqual(retrySessions.map(({ state }) => state), ['FAILED', 'CLOSED'])
})

test('Redis disconnect or replacement after owner GET fails without reconnect or later mutation', async () => {
  assert.equal(
    typeof smokeContracts.openRedisRespSession,
    'function',
    'P0_REDIS_RESP_SESSION_FACTORY_REQUIRED'
  )
  const binding = s11RedisBinding()
  const runToken = 'p0-review1-s11-redis-disconnect-owner-token-a1'
  const events = []
  let connectCalls = 0
  let openedSession
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      if (args[0] === 'GET') return runToken
      throw new Error(`P0_TEST_LEGACY_REDIS_REQUEST_USED:${args[0]}`)
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      events.push({ type: 'verify', stage })
      assert.deepEqual(expectedBinding, binding)
      return structuredClone(binding)
    },
    {
      async createSession({ host, port, signal }) {
        openedSession = await smokeContracts.openRedisRespSession({
          host,
          port,
          signal,
          connect(options, onConnect) {
            connectCalls += 1
            events.push({
              type: 'connect',
              socketId: `redis-socket-${connectCalls}`,
              host: options.host,
              port: options.port
            })
            const socket = createS11RedisFakeSocket({
              id: `redis-socket-${connectCalls}`,
              events,
              onCommand(args) {
                if (args[0] === 'GET' && args[1] === 'p0:e2e:owner') {
                  return {
                    reply: { type: 'bulk', value: runToken },
                    disconnect: true
                  }
                }
                return { reply: { type: 'simple', value: 'OK' } }
              }
            })
            socket.open(onConnect)
            return socket
          }
        })
        return openedSession
      }
    }
  )

  await assert.rejects(
    redis.runVerifiedTransaction(async () => {
      assert.equal(await redis.get('p0:e2e:owner'), runToken)
      await redis.restoreExact('quote:BTCUSDT', 'must-not-write', null)
    }),
    /P0_REDIS_SESSION_CLOSED/
  )

  assert.equal(connectCalls, 1)
  assert.deepEqual(
    events.filter(({ type }) => type === 'resp'),
    [{
      type: 'resp',
      socketId: 'redis-socket-1',
      args: ['GET', 'p0:e2e:owner']
    }]
  )
  assert.equal(['FAILED', 'CLOSED'].includes(openedSession.state), true)
  await assert.rejects(openedSession.command(['PING']), /P0_REDIS_SESSION_CLOSED/)
  assert.equal(connectCalls, 1)
  assert.equal(events.filter(({ type }) => type === 'resp').length, 1)
})

test('Redis post-connect identity drift closes the session and sends zero RESP commands', async () => {
  const binding = s11RedisBinding()
  const replacement = s11RedisBinding('c'.repeat(64))
  const events = []
  let createSessionCalls = 0
  let session
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      events.push({ type: 'legacy', args: [...args] })
      return 'must-not-read'
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      events.push({ type: 'verify', stage })
      assert.deepEqual(expectedBinding, binding)
      return structuredClone(stage === 'POST_CONNECT' ? replacement : binding)
    },
    {
      createSession({ host, port }) {
        createSessionCalls += 1
        const sessionId = `redis-drift-${createSessionCalls}`
        events.push({ type: 'connect', sessionId, host, port })
        session = createS11RedisFakeSession({
          id: sessionId,
          events,
          respond() {
            return 'must-not-read'
          }
        })
        return session
      }
    }
  )

  await assert.rejects(
    redis.runVerifiedTransaction(() => redis.get('p0:e2e:owner')),
    { message: 'P0_REDIS_CONTAINER_BINDING_MISMATCH' }
  )

  assert.equal(createSessionCalls, 1)
  assert.deepEqual(events, [
    { type: 'verify', stage: 'PRE_CONNECT' },
    {
      type: 'connect',
      sessionId: 'redis-drift-1',
      host: '127.0.0.1',
      port: 6379
    },
    { type: 'verify', stage: 'POST_CONNECT' },
    {
      type: 'close',
      sessionId: 'redis-drift-1',
      state: 'OPEN'
    }
  ])
  assert.equal(events.some(({ type }) => type === 'command' || type === 'legacy'), false)
  assert.equal(session.state, 'CLOSED')
})

test('Redis ownership transactions revalidate the exact container binding before the first command', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-redis-transaction-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runToken = 'p0-review1-s10-redis-transaction-owner-token-a1'
  const exactBinding = {
    id: 'b'.repeat(64),
    image: 'redis:7',
    host: '127.0.0.1',
    hostPort: 6379
  }
  const replacementBinding = {
    id: 'c'.repeat(64),
    image: 'redis:7',
    host: '127.0.0.2',
    hostPort: 6380
  }
  const directEvents = []
  const directRequests = []
  const verificationSnapshots = []
  let cachedBinding = exactBinding
  let liveBinding = exactBinding
  assert.equal(
    typeof smokeContracts.createLoopbackRedisAdapter,
    'function',
    'P0_DEFAULT_REDIS_ADAPTER_FACTORY_REQUIRED'
  )
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args, details) => {
      directEvents.push(`redis:${args[0]}`)
      directRequests.push({
        args: [...args],
        host: details.host,
        port: details.port
      })
      if (args[0] === 'SET' && args[1] === 'p0:e2e:owner') return 'OK'
      if (args[0] === 'GET' && args[1] === 'quote:BTCUSDT') {
        cachedBinding = replacementBinding
        liveBinding = replacementBinding
        return 'before-transaction'
      }
      if (args[0] === 'PEXPIRETIME' && args[1] === 'quote:BTCUSDT') {
        return 1900000000000
      }
      throw new Error(`P0_TEST_UNEXPECTED_REDIS: ${args[0]}`)
    },
    () => cachedBinding,
    async (expectedBinding) => {
      directEvents.push('verify')
      verificationSnapshots.push({
        expectedBinding: structuredClone(expectedBinding),
        observedBinding: structuredClone(liveBinding),
        requestCount: directRequests.length
      })
      return structuredClone(liveBinding)
    }
  )

  await smokeContracts.acquireRedisOwnership({ redis, runToken, role: 'parent' })
  cachedBinding = exactBinding
  liveBinding = exactBinding
  const snapshot = await smokeContracts.snapshotRedisKeys({
    redis,
    keys: ['quote:BTCUSDT']
  })

  const fixture = await createS10CleanupComposeFixture(root, 'redis-tx')
  removeS10CleanupDatabaseJournal(fixture)
  const boundary = createS10CleanupAdapterBoundary(fixture, fixture.persistedIdentity)
  const cleanupResult = await boundary.dependencies.cleanup(
    undefined,
    {},
    { runId: fixture.runId }
  )
  const verificationEvents = s10RedisComposeVerificationEvents()
  const cleanupRedisTransactions = [
    ['redis:GET', 'redis:SET', 'redis:PEXPIREAT', 'redis:EVAL'],
    ['redis:EVAL']
  ]
  const transactionVerificationEvents = Array.from(
    { length: 2 },
    () => verificationEvents
  ).flat()
  const cleanupVerificationCycles = 2 + (cleanupRedisTransactions.length * 2)

  assert.deepEqual({
    directEvents,
    directRequests,
    verificationSnapshots,
    snapshot,
    cleanupResult,
    cleanupEvents: boundary.events,
    cleanupVerifiedIdentities: boundary.verifiedIdentities,
    cleanupRedisState: boundary.redisState
  }, {
    directEvents: [
      'verify',
      'verify',
      'redis:SET',
      'verify',
      'verify',
      'redis:GET',
      'redis:PEXPIRETIME'
    ],
    directRequests: [
      {
        args: ['SET', 'p0:e2e:owner', runToken, 'NX'],
        host: '127.0.0.1',
        port: 6379
      },
      {
        args: ['GET', 'quote:BTCUSDT'],
        host: '127.0.0.1',
        port: 6379
      },
      {
        args: ['PEXPIRETIME', 'quote:BTCUSDT'],
        host: '127.0.0.1',
        port: 6379
      }
    ],
    verificationSnapshots: [0, 0, 1, 1].map((requestCount) => ({
      expectedBinding: exactBinding,
      observedBinding: exactBinding,
      requestCount
    })),
    snapshot: [{
      key: 'quote:BTCUSDT',
      exists: true,
      value: 'before-transaction',
      expiresAtMs: 1900000000000
    }],
    cleanupResult: { status: 'CLEANED', databases: 0, redis: 'RESTORED', restored: 1 },
    cleanupEvents: [
      ...verificationEvents,
      'port:18086',
      'port:5199',
      'port:5200',
      ...transactionVerificationEvents,
      ...cleanupRedisTransactions[0],
      ...verificationEvents,
      ...transactionVerificationEvents,
      ...cleanupRedisTransactions[1]
    ],
    cleanupVerifiedIdentities: Array.from(
      { length: cleanupVerificationCycles },
      () => fixture.persistedIdentity
    ),
    cleanupRedisState: {
      owner: null,
      restored: 'before-cleanup',
      expiresAtMs: 1900000000000,
      receipt: null
    }
  })
})

test('Redis container replacement fails before any loopback Redis request or key mutation', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-redis-replacement-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fixture = await createS10CleanupComposeFixture(root, 'redis-replace')
  removeS10CleanupDatabaseJournal(fixture)
  const replacementIdentity = structuredClone(fixture.persistedIdentity)
  replacementIdentity.redis.id = 'd'.repeat(64)
  let liveIdentity = fixture.persistedIdentity
  const boundary = createS10CleanupAdapterBoundary(
    fixture,
    fixture.persistedIdentity,
    {
      identityForVerification: () => liveIdentity,
      onVerificationComplete({ verificationIndex }) {
        if (verificationIndex === 1) liveIdentity = replacementIdentity
      }
    }
  )
  const activeBefore = readFileSync(fixture.control.ownershipPath, 'utf8')
  const redisBefore = structuredClone(boundary.redisState)
  let error = null
  try {
    await boundary.dependencies.cleanup(undefined, {}, { runId: fixture.runId })
  } catch (cause) {
    error = cause?.message ?? String(cause)
  }
  const verificationIds = boundary.commands
    .map(({ id }) => id)
    .filter((id) => id === 'docker-context-inspect' || id.startsWith('compose-'))
  const expectedVerificationIds = s10RedisComposeVerificationEvents()
    .map((event) => event.slice('command:'.length))

  assert.deepEqual({
    rejected: typeof error === 'string' && error.startsWith('P0_'),
    verificationIds,
    verifiedIdentities: boundary.verifiedIdentities,
    redisRequests: boundary.redisRequests,
    redisStateUnchanged: JSON.stringify(boundary.redisState) === JSON.stringify(redisBefore),
    activeUnchanged: readFileSync(fixture.control.ownershipPath, 'utf8') === activeBefore,
    cleanedPublished: existsSync(join(fixture.control.runRoot, 'control', 'cleaned.json'))
  }, {
    rejected: true,
    verificationIds: [...expectedVerificationIds, ...expectedVerificationIds],
    verifiedIdentities: [fixture.persistedIdentity, replacementIdentity],
    redisRequests: [],
    redisStateUnchanged: true,
    activeUnchanged: true,
    cleanedPublished: false
  })
})

async function createS11PendingReceiptFixture(t, label) {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s11-receipt-' + label + '-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const runId = 'p0-review1-s11-receipt-' + label + '-a1'
  const runToken = 'p0-review1-s11-receipt-' + label + '-owner-token-a1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'discovery',
    selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
    database: {}
  })
  const activeManifest = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  const preparedContext = {
    artifactBase,
    ownerId: control.ownerId,
    ownerToken: runToken
  }
  const safeComposeTarget = {
    expectedProject: composeTarget.expectedProject,
    composeFiles: [...composeTarget.composeFiles]
  }
  const composeIdentity = {
    project: 'infra',
    postgres: {
      id: 'a'.repeat(64),
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64),
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379
    }
  }
  const receiptKey = 'p0:e2e:cleanup:' + control.ownerId
  const pendingMarker = {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: control.ownerId,
    cleanedAt: '2026-07-15T23:40:00.000Z',
    composeTarget: safeComposeTarget,
    composeIdentity,
    receipt: {
      key: receiptKey,
      state: 'PENDING',
      completedAt: null
    }
  }
  const pendingBytes = JSON.stringify(pendingMarker, null, 2) + '\n'
  writeFileSync(control.ownershipPath, pendingBytes)
  return {
    activeManifest,
    artifactBase,
    composeIdentity,
    composeTarget: safeComposeTarget,
    control,
    pendingBytes,
    pendingMarker,
    preparedContext,
    receiptKey,
    root,
    runId,
    runToken
  }
}

function assertS11CleanedReceiptMarker(marker, fixture, {
  state,
  completedAt,
  cleanedAt = fixture.pendingMarker.cleanedAt
}) {
  assert.deepEqual(marker, {
    schemaVersion: 2,
    status: 'CLEANED',
    runId: fixture.runId,
    ownerId: fixture.control.ownerId,
    cleanedAt,
    composeTarget: fixture.composeTarget,
    composeIdentity: fixture.composeIdentity,
    receipt: {
      key: fixture.receiptKey,
      state,
      completedAt
    }
  })
}

const S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT = [
  "local receipt = redis.call('get', KEYS[1])",
  "if not receipt then return 'ALREADY_ABSENT' end",
  "if receipt ~= ARGV[1] then return 'MISMATCH' end",
  "redis.call('del', KEYS[1])",
  "return 'REMOVED'"
].join('\n')

function createS11ReceiptReplayBoundary(fixture, {
  receiptStatus = 'REMOVED',
  observedIdentity = fixture.composeIdentity,
  verifierFailureStage,
  sessionFailure = false,
  replaceMode = 'write',
  completedAt = '2026-07-15T23:45:00.000Z'
} = {}) {
  const commands = []
  const events = []
  const replaceCandidates = []
  const sessions = []
  let bindingInstalled = false
  const infrastructure = {
    async inspectDockerDaemon() {
      events.push('compose:daemon')
      return { endpoint: 'npipe:////./pipe/docker_engine' }
    },
    async verifyComposeContainers({ composeFiles, expectedProject }) {
      events.push('compose:containers')
      assert.deepEqual(composeFiles, fixture.composeTarget.composeFiles)
      assert.equal(expectedProject, fixture.composeTarget.expectedProject)
      bindingInstalled = JSON.stringify(observedIdentity)
        === JSON.stringify(fixture.composeIdentity)
      return structuredClone(observedIdentity)
    },
    async assertPortsFree() {
      events.push('ports:forbidden')
      throw new Error('P0_TEST_RECEIPT_PORT_CHECK_FORBIDDEN')
    }
  }
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      events.push('redis:legacy:' + args[0])
      throw new Error('P0_TEST_LEGACY_REDIS_REQUEST_USED')
    },
    () => {
      events.push('redis:binding')
      assert.equal(bindingInstalled, true, 'P0_TEST_REDIS_BINDING_NOT_INSTALLED')
      return fixture.composeIdentity.redis
    },
    async (expectedBinding, { stage } = {}) => {
      events.push('redis:verify:' + stage)
      assert.deepEqual(expectedBinding, fixture.composeIdentity.redis)
      if (stage === verifierFailureStage) {
        throw new Error('P0_TEST_RECEIPT_VERIFIER_FAILURE')
      }
      return structuredClone(fixture.composeIdentity.redis)
    },
    {
      createSession({ host, port }) {
        assert.equal(host, '127.0.0.1')
        assert.equal(port, 6379)
        const id = 'receipt-session-' + (sessions.length + 1)
        events.push('redis:open:' + id)
        let state = 'OPEN'
        let closed = false
        const session = {
          id,
          get state() {
            return state
          },
          async command(args) {
            if (state !== 'OPEN') throw new Error('P0_REDIS_SESSION_CLOSED')
            const command = args.map((argument) => String(argument))
            commands.push({ sessionId: id, args: command })
            events.push('redis:command:' + id + ':' + command[0])
            assert.deepEqual(command, [
              'EVAL',
              S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
              '1',
              fixture.receiptKey,
              fixture.control.ownerId
            ])
            if (sessionFailure) {
              state = 'FAILED'
              throw new Error('P0_TEST_RECEIPT_SESSION_FAILURE')
            }
            return receiptStatus
          },
          close() {
            if (closed) return
            closed = true
            events.push('redis:close:' + id + ':' + state)
            if (state === 'OPEN') state = 'CLOSED'
          }
        }
        sessions.push(session)
        return session
      }
    }
  )
  const replaceOwnership = async (path, marker) => {
    events.push('fs:replace')
    assert.equal(path, fixture.control.ownershipPath)
    assert.equal(marker?.receipt?.state, 'COMPLETED')
    replaceCandidates.push(structuredClone(marker))
    if (replaceMode === 'throw') {
      throw new Error('P0_TEST_RECEIPT_REPLACE_CRASH')
    }
    if (replaceMode === 'skip') return
    writeFileSync(path, JSON.stringify(marker, null, 2) + '\n')
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase: fixture.artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      's11-receipt-replay:' + fixture.runId
    ),
    infrastructure,
    redis,
    processManager: {
      async stopParentBackend() {
        throw new Error('P0_TEST_RECEIPT_PROCESS_MUTATION_FORBIDDEN')
      }
    },
    postgres: {
      async readDatabaseOwnership() {
        throw new Error('P0_TEST_RECEIPT_DATABASE_MUTATION_FORBIDDEN')
      }
    },
    replaceOwnership,
    now: () => completedAt
  })
  return {
    commands,
    completedAt,
    dependencies,
    events,
    replaceCandidates,
    sessions
  }
}

function s11ReceiptReplaySuccessEvents() {
  return [
    'compose:daemon',
    'compose:containers',
    'redis:binding',
    'redis:verify:PRE_CONNECT',
    'redis:open:receipt-session-1',
    'redis:verify:POST_CONNECT',
    'redis:command:receipt-session-1:EVAL',
    'redis:close:receipt-session-1:OPEN',
    'fs:replace'
  ]
}

test('CLEANED pending receipt revalidates persisted compose binding before Redis and completes atomically', async (t) => {
  const fixture = await createS11PendingReceiptFixture(t, 'complete')
  const boundary = createS11ReceiptReplayBoundary(fixture)

  const result = await boundary.dependencies.cleanup(
    undefined,
    {},
    { runId: fixture.runId }
  )

  assert.equal(result.status, 'CLEANED')
  assert.equal(result.alreadyCleaned, true)
  assert.deepEqual(boundary.events, s11ReceiptReplaySuccessEvents())
  assert.equal(boundary.sessions.length, 1)
  assert.equal(boundary.sessions[0].state, 'CLOSED')
  assert.equal(boundary.commands.length, 1)
  assert.equal(boundary.commands[0].sessionId, 'receipt-session-1')
  assert.deepEqual(
    boundary.commands[0].args,
    [
      'EVAL',
      S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
      '1',
      fixture.receiptKey,
      fixture.control.ownerId
    ]
  )
  assert.equal(boundary.replaceCandidates.length, 1)
  const completed = JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8'))
  assertS11CleanedReceiptMarker(completed, fixture, {
    state: 'COMPLETED',
    completedAt: boundary.completedAt
  })
  assert.deepEqual(boundary.replaceCandidates[0], completed)
})

test('CLEANED pending receipt accepts already absent but rejects foreign receipt without changing marker', async (t) => {
  const alreadyAbsent = await createS11PendingReceiptFixture(t, 'already-absent')
  const absentBoundary = createS11ReceiptReplayBoundary(alreadyAbsent, {
    receiptStatus: 'ALREADY_ABSENT'
  })
  const absentResult = await absentBoundary.dependencies.cleanup(
    undefined,
    {},
    { runId: alreadyAbsent.runId }
  )
  assert.equal(absentResult.status, 'CLEANED')
  assert.deepEqual(absentBoundary.events, s11ReceiptReplaySuccessEvents())
  assertS11CleanedReceiptMarker(
    JSON.parse(readFileSync(alreadyAbsent.control.ownershipPath, 'utf8')),
    alreadyAbsent,
    {
      state: 'COMPLETED',
      completedAt: absentBoundary.completedAt
    }
  )

  const foreign = await createS11PendingReceiptFixture(t, 'foreign')
  const foreignBefore = readFileSync(foreign.control.ownershipPath, 'utf8')
  const foreignBoundary = createS11ReceiptReplayBoundary(foreign, {
    receiptStatus: 'MISMATCH'
  })
  let foreignError
  try {
    await foreignBoundary.dependencies.cleanup(
      undefined,
      {},
      { runId: foreign.runId }
    )
  } catch (error) {
    foreignError = error
  }
  assert.equal(foreignError?.message, 'P0_REDIS_CLEANUP_RECEIPT_MISMATCH')
  assert.deepEqual(
    foreignBoundary.events,
    s11ReceiptReplaySuccessEvents().slice(0, -1)
  )
  assert.equal(foreignBoundary.replaceCandidates.length, 0)
  assert.equal(readFileSync(foreign.control.ownershipPath, 'utf8'), foreignBefore)
})

test('CLEANED pending receipt preserves PENDING on compose drift verifier or session failure', async (t) => {
  const driftIdentity = {
    project: 'infra',
    postgres: {
      id: 'c'.repeat(64),
      image: 'postgres:16',
      host: '127.0.0.1',
      hostPort: 5432
    },
    redis: {
      id: 'b'.repeat(64),
      image: 'redis:7',
      host: '127.0.0.1',
      hostPort: 6379
    }
  }
  const scenarios = [
    {
      label: 'compose-drift',
      options: { observedIdentity: driftIdentity },
      expectedEvents: ['compose:daemon', 'compose:containers']
    },
    {
      label: 'verifier',
      options: { verifierFailureStage: 'POST_CONNECT' },
      expectedEvents: [
        'compose:daemon',
        'compose:containers',
        'redis:binding',
        'redis:verify:PRE_CONNECT',
        'redis:open:receipt-session-1',
        'redis:verify:POST_CONNECT',
        'redis:close:receipt-session-1:OPEN'
      ]
    },
    {
      label: 'session',
      options: { sessionFailure: true },
      expectedEvents: [
        'compose:daemon',
        'compose:containers',
        'redis:binding',
        'redis:verify:PRE_CONNECT',
        'redis:open:receipt-session-1',
        'redis:verify:POST_CONNECT',
        'redis:command:receipt-session-1:EVAL',
        'redis:close:receipt-session-1:FAILED'
      ]
    },
    {
      label: 'replace-crash',
      options: { replaceMode: 'throw' },
      expectedEvents: s11ReceiptReplaySuccessEvents()
    },
    {
      label: 'readback-missing',
      options: { replaceMode: 'skip' },
      expectedEvents: s11ReceiptReplaySuccessEvents()
    }
  ]

  for (const scenario of scenarios) {
    const fixture = await createS11PendingReceiptFixture(t, scenario.label)
    const before = readFileSync(fixture.control.ownershipPath, 'utf8')
    const boundary = createS11ReceiptReplayBoundary(fixture, scenario.options)
    let failure
    try {
      await boundary.dependencies.cleanup(
        undefined,
        {},
        { runId: fixture.runId }
      )
    } catch (error) {
      failure = error
    }
    assert.equal(failure instanceof Error, true, scenario.label)
    assert.match(failure.message, /^P0_/, scenario.label)
    assert.deepEqual(boundary.events, scenario.expectedEvents, scenario.label)
    assert.equal(
      readFileSync(fixture.control.ownershipPath, 'utf8'),
      before,
      scenario.label
    )
    if (scenario.options.replaceMode) {
      assert.equal(boundary.replaceCandidates.length, 1, scenario.label)
      assert.equal(
        boundary.replaceCandidates[0].receipt.state,
        'COMPLETED',
        scenario.label
      )
      const replayBoundary = createS11ReceiptReplayBoundary(fixture, {
        receiptStatus: 'ALREADY_ABSENT'
      })
      const replayResult = await replayBoundary.dependencies.cleanup(
        fixture.preparedContext,
        {},
        { runId: fixture.runId }
      )
      assert.deepEqual(replayResult, {
        status: 'CLEANED',
        alreadyCleaned: true
      }, scenario.label)
      assert.deepEqual(
        replayBoundary.events,
        s11ReceiptReplaySuccessEvents(),
        scenario.label
      )
      assert.equal(replayBoundary.sessions.length, 1, scenario.label)
      assert.notEqual(replayBoundary.sessions[0], boundary.sessions[0], scenario.label)
      assert.equal(replayBoundary.commands.length, 1, scenario.label)
      assert.deepEqual(
        replayBoundary.commands[0].args,
        [
          'EVAL',
          S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
          '1',
          fixture.receiptKey,
          fixture.control.ownerId
        ],
        scenario.label
      )
      assertS11CleanedReceiptMarker(
        JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8')),
        fixture,
        {
          state: 'COMPLETED',
          completedAt: replayBoundary.completedAt
        }
      )
    } else {
      assert.equal(boundary.replaceCandidates.length, 0, scenario.label)
    }
  }
})

test('CLEANED marker persists no raw token credentials or unsafe Redis value', async (t) => {
  const fixture = await createS11PendingReceiptFixture(t, 'safe-marker')
  const passwordMarker = 'S11_PASSWORD_MARKER_7f31'
  const credentialMarker = 'S11_CREDENTIAL_MARKER_932a'
  const foreignRedisValue = 'S11_FOREIGN_REDIS_VALUE_04de'
  const active = structuredClone(fixture.activeManifest)
  active.redisState = 'REDIS_RELEASE_ARMED'
  active.composeTarget = {
    ...fixture.composeTarget,
    password: passwordMarker
  }
  active.composeIdentity = {
    ...fixture.composeIdentity,
    credential: credentialMarker,
    redis: {
      ...fixture.composeIdentity.redis,
      foreignRedisValue
    }
  }
  const activeText = JSON.stringify(active, null, 2)
  for (const embedded of [passwordMarker, credentialMarker, foreignRedisValue]) {
    assert.equal(activeText.includes(embedded), true)
  }
  writeFileSync(
    fixture.control.ownershipPath,
    activeText + '\n'
  )
  const publishedAt = '2026-07-15T23:50:00.000Z'
  const publishCandidates = []

  const publication = await smokeContracts.completeControlCleanup({
    artifactBase: fixture.artifactBase,
    runId: fixture.runId,
    runToken: fixture.runToken,
    resourcesCleaned: true,
    redisReleaseReceipt: fixture.control.ownerId,
    now: () => publishedAt,
    async replaceOwnership(path, marker) {
      assert.equal(path, fixture.control.ownershipPath)
      publishCandidates.push(structuredClone(marker))
      writeFileSync(path, JSON.stringify(marker, null, 2) + '\n')
    }
  })

  assert.deepEqual(publication, { status: 'CLEANED', alreadyCleaned: false })
  assert.equal(publishCandidates.length, 1)
  const publishedText = readFileSync(fixture.control.ownershipPath, 'utf8')
  for (const forbidden of [
    fixture.runToken,
    passwordMarker,
    credentialMarker,
    foreignRedisValue
  ]) {
    assert.equal(publishedText.includes(forbidden), false)
  }
  assertS11CleanedReceiptMarker(JSON.parse(publishedText), fixture, {
    state: 'PENDING',
    completedAt: null,
    cleanedAt: publishedAt
  })

  const legacy = await createS11PendingReceiptFixture(t, 'legacy-no-proof')
  const legacyMarker = {
    schemaVersion: 1,
    status: 'CLEANED',
    runId: legacy.runId,
    ownerId: legacy.control.ownerId,
    cleanedAt: '2026-07-15T23:51:00.000Z'
  }
  const legacyBytes = JSON.stringify(legacyMarker, null, 2) + '\n'
  writeFileSync(legacy.control.ownershipPath, legacyBytes)
  const legacyEvents = []
  const legacyDependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase: legacy.artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      's11-receipt-legacy:' + legacy.runId
    ),
    infrastructure: {
      async inspectDockerDaemon() {
        legacyEvents.push('compose')
        return { endpoint: 'npipe:////./pipe/docker_engine' }
      }
    },
    redis: {
      async runVerifiedTransaction(operation) {
        legacyEvents.push('redis:transaction')
        return operation()
      },
      async removeCleanupReceipt() {
        legacyEvents.push('redis:remove')
        return 'REMOVED'
      }
    }
  })
  let legacyError
  try {
    await legacyDependencies.cleanup(undefined, {}, { runId: legacy.runId })
  } catch (error) {
    legacyError = error
  }
  assert.equal(legacyError instanceof Error, true)
  assert.match(
    legacyError.message,
    /P0_(CLEANED_MARKER|CLEANUP_COMPOSE_IDENTITY)_INVALID/
  )
  assert.deepEqual(legacyEvents, [])
  assert.equal(readFileSync(legacy.control.ownershipPath, 'utf8'), legacyBytes)
})

test('compose identity rejects reversed duplicate or drifted config file order', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-compose-config-order-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const composeTarget = await smokeContracts.ensureP0ComposeTarget({ artifactBase })
  const [resolvedBase, resolvedStableOverride] = composeTarget.composeFiles.map((file) => resolve(file))
  const relativeBase = relative(process.cwd(), resolvedBase)
  const relativeOverride = relative(process.cwd(), resolvedStableOverride)
  const escapedBase = `${dirname(resolvedBase)}${sep}foreign${sep}..${sep}${basename(resolvedBase)}`
  const extra = resolve(root, 'extra-compose.yml')
  const drifted = resolve(root, 'drifted-compose.yml')
  const scenarios = [
    ['exact-ordered', [resolvedBase, resolvedStableOverride], true],
    ['reversed', [resolvedStableOverride, resolvedBase], false],
    ['duplicate', [resolvedBase, resolvedStableOverride, resolvedStableOverride], false],
    ['missing', [resolvedBase], false],
    ['extra', [resolvedBase, resolvedStableOverride, extra], false],
    ['relative-unresolved', [relativeBase, relativeOverride], false],
    ['escape-normalized', [escapedBase, resolvedStableOverride], false],
    ['path-drift', [resolvedBase, drifted], false]
  ]
  const observed = []

  for (const [label, configFiles, expectedAccepted] of scenarios) {
    const postgresId = 'a'.repeat(64)
    const redisId = 'b'.repeat(64)
    const inspection = ({ id, service, image, port }) => JSON.stringify([{
      Id: id,
      State: { Running: true },
      Config: {
        Image: image,
        Env: service === 'postgres'
          ? ['POSTGRES_USER=postgres', 'POSTGRES_PASSWORD=password']
          : [],
        Labels: {
          'com.docker.compose.service': service,
          'com.docker.compose.project': 'infra',
          'com.docker.compose.project.working_dir': dirname(resolvedBase),
          'com.docker.compose.project.config_files': configFiles.join(',')
        }
      },
      NetworkSettings: {
        Ports: {
          [`${port}/tcp`]: [{ HostIp: '127.0.0.1', HostPort: String(port) }]
        }
      }
    }])
    const runCommand = async (descriptor) => {
      if (descriptor.id === 'compose-postgres-id') {
        return { status: 0, stdout: `${postgresId}\n` }
      }
      if (descriptor.id === 'compose-redis-id') {
        return { status: 0, stdout: `${redisId}\n` }
      }
      if (descriptor.id === 'compose-postgres-inspect') {
        return {
          status: 0,
          stdout: inspection({
            id: postgresId,
            service: 'postgres',
            image: 'postgres:16',
            port: 5432
          })
        }
      }
      if (descriptor.id === 'compose-redis-inspect') {
        return {
          status: 0,
          stdout: inspection({
            id: redisId,
            service: 'redis',
            image: 'redis:7',
            port: 6379
          })
        }
      }
      throw new Error(`P0_TEST_UNEXPECTED_COMMAND: ${descriptor.id}`)
    }
    const infrastructure = smokeContracts.createLocalInfrastructureAdapter(
      resolvedBase,
      runCommand
    )
    let accepted = false
    let error = null
    try {
      const identity = await infrastructure.verifyComposeContainers({
        composeFiles: [resolvedBase, resolvedStableOverride],
        expectedProject: 'infra'
      })
      accepted = identity.postgres.id === postgresId && identity.redis.id === redisId
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    observed.push({
      label,
      accepted,
      configRejected: typeof error === 'string' && error === 'P0_COMPOSE_CONFIG_MISMATCH'
    })
  }

  assert.deepEqual(observed, scenarios.map(([label, _configFiles, expectedAccepted]) => ({
    label,
    accepted: expectedAccepted,
    configRejected: !expectedAccepted
  })))
})

test('control operations reject undefined null and explicit FAIL outcomes instead of promoting PASS', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-control-result-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const scenarios = [
    {
      label: 'preflight-undefined',
      phase: 'preflight',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) { operations.runPreflight = async () => undefined }
    },
    {
      label: 'preflight-null',
      phase: 'preflight',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) { operations.runPreflight = async () => null }
    },
    {
      label: 'preflight-fail',
      phase: 'preflight',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) {
        operations.runPreflight = async () => ({ id: 'AUTH-01', status: 'FAIL' })
      }
    },
    {
      label: 'authority-fail',
      phase: 'authority',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) {
        operations.runAuthority = async () => ({ id: 'AUTH-03', status: 'FAIL' })
      }
    },
    {
      label: 'report-malformed',
      phase: 'report',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) {
        operations.writeReport = async () => ({
          status: 'PASS',
          kind: 'P0_REPORT_BOUNDARY',
          scope: 'CONTROL',
          finalWriter: null
        })
      }
    },
    {
      label: 'canonical-nonzero',
      phase: 'canonical',
      expectedError: 'P0_CANONICAL_CHILD_FAILED',
      configure(operations) {
        operations.runCanonicalChild = async () => ({ status: 2, signal: null })
      }
    },
    {
      label: 'canonical-contradictory',
      phase: 'canonical',
      expectedError: 'P0_CONTROL_RESULT_INVALID',
      configure(operations) {
        operations.runCanonicalChild = async () => ({ status: 0, signal: null })
        operations.verifyCanonicalChildCleanup = async () => ({
          id: 'CAT-01',
          status: 'FAIL'
        })
      }
    }
  ]
  const observed = []

  for (const [index, scenario] of scenarios.entries()) {
    const runId = `p0-review1-s10-control-result-${index}-a1`
    const runRoot = join(root, runId)
    mkdirSync(runRoot, { recursive: true })
    const ownerToken = `p0-review1-s10-control-result-${index}-owner-token-a1`
    const ownerId = createHash('sha256').update(ownerToken).digest('hex')
    const options = p0CaseContracts.parseP0Cli([
      '--suite=p0',
      `--phase=${scenario.phase}`,
      `--run-id=${runId}`
    ])
    const finalWrites = []
    let cleanupCalls = 0
    const phaseOperations = {
      async runPreflight() { return { id: 'AUTH-01', status: 'PASS' } },
      async assertRedisOwnership() {},
      async stopParentBackend() {},
      async assertBusinessPortsFree() {},
      async runCanonicalChild() { return { status: 0, signal: null } },
      async verifyCanonicalChildCleanup() { return { id: 'CAT-01', status: 'PASS' } },
      async runAuthority() { return { id: 'AUTH-03', status: 'PASS' } },
      async writeReport() {
        return {
          status: 'PASS',
          kind: 'P0_REPORT_BOUNDARY',
          scope: 'CONTROL',
          finalWriter: 'PENDING',
          identity: { runId, ownerId, reportPath: join(runRoot, 'report.json') }
        }
      }
    }
    scenario.configure(phaseOperations)
    const persistReport = (execution, prepared, details = {}) => writeFullyFakeP0Report(
      execution,
      prepared,
      { ...details, now: () => '2026-07-15T23:50:00.000Z' }
    )
    let error = null
    try {
      await smokeContracts.runP0Suite(options, {
        installSignalHandlers() { return () => {} },
        async initializeOwnership() {
          return {
            runRoot,
            ownerToken,
            ownerId,
            scriptPath: fileURLToPath(new URL('./smoke-usdt-demo-browser.mjs', import.meta.url)),
            canonicalDatabase: `fx_p0_user_e2e_s10_control_${index}_canonical_1`,
            matrixDatabase: `fx_p0_user_e2e_s10_control_${index}_matrix_1`,
            inheritedEnv: {},
            identity: null,
            caseResults: [],
            async assertIdentity() {}
          }
        },
        phaseOperations,
        async dispatchCase() { throw new Error('CONTROL_PHASE_DISPATCH_FORBIDDEN') },
        handlers: Object.create(null),
        async writeReport(execution, prepared, details) {
          finalWrites.push(structuredClone(execution.controlResults))
          return persistReport(execution, prepared, details)
        },
        async cleanup() {
          cleanupCalls += 1
          return { status: 'CLEANED' }
        }
      })
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    const reportPath = join(runRoot, 'report.json')
    const persisted = existsSync(reportPath)
      ? JSON.parse(readFileSync(reportPath, 'utf8'))
      : null
    observed.push({
      label: scenario.label,
      error,
      finalWriterCalls: finalWrites.length,
      passControlResults: [
        ...finalWrites.flat(),
        ...(persisted?.controlResults ?? [])
      ].filter(({ status }) => status === 'PASS').length,
      reportPersisted: persisted !== null,
      cleanupCalls
    })
  }

  assert.deepEqual(observed, scenarios.map(({ label, expectedError }) => ({
    label,
    error: expectedError,
    finalWriterCalls: 0,
    passControlResults: 0,
    reportPersisted: false,
    cleanupCalls: 1
  })))
})

test('default report phase emits nonempty typed evidence or fails closed', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-default-report-boundary-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runId = 'p0-review1-s10-default-report-boundary-a1'
  const runRoot = join(root, runId)
  mkdirSync(runRoot, { recursive: true })
  const ownerToken = 'p0-review1-s10-default-report-boundary-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=report',
    `--run-id=${runId}`
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const context = { runRoot, ownerId, options }
  const reportPath = join(runRoot, 'report.json')
  const expected = exactP0ReportPhaseEvidence(context, plan)
  await assert.rejects(
    () => smokeContracts.executeP0ReportPhaseBoundary({ context, plan }),
    { message: 'P0_REPORT_PHASE_WRITER_REQUIRED' }
  )
  const builtIn = await smokeContracts.executeP0ReportPhaseBoundary({
    context,
    plan,
    writePhaseReport: async () => expected
  })

  assert.deepEqual(builtIn, {
    status: 'PASS',
    kind: 'P0_REPORT_BOUNDARY',
    scope: 'CONTROL',
    finalWriter: 'PENDING',
    identity: { runId, ownerId, reportPath }
  })

  const invalidOverrides = [
    ['undefined', undefined],
    ['null', null],
    ['fail', {
      status: 'FAIL',
      kind: 'P0_REPORT_BOUNDARY',
      scope: 'CONTROL',
      finalWriter: 'PENDING',
      identity: { runId, ownerId, reportPath }
    }]
  ]
  const observed = []
  for (const [label, value] of invalidOverrides) {
    let error = null
    try {
      await smokeContracts.executeP0ReportPhaseBoundary({
        context,
        plan,
        writePhaseReport: async () => value
      })
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    observed.push({ label, error })
  }
  assert.deepEqual(observed, invalidOverrides.map(([label]) => ({
    label,
    error: 'P0_REPORT_PHASE_RESULT_INVALID'
  })))
})

test('default dependencies provide built-in typed report boundary evidence', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s10-default-report-writer-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runId = 'p0-review1-s10-default-report-writer-a1'
  const ownerToken = 'p0-review1-s10-default-report-writer-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const runRoot = join(root, runId)
  const moduleUrl = new URL(
    `./smoke-usdt-demo-browser.mjs?default-report-writer=${Date.now()}`,
    import.meta.url
  ).href
  const source = `
    Object.defineProperty(process, 'platform', { value: 'linux' })
    const contracts = await import(${JSON.stringify(moduleUrl)})
    const dependencies = contracts.createDefaultP0Dependencies({
      artifactBase: ${JSON.stringify(root)},
      inheritedEnv: {}
    })
    const result = await dependencies.phaseOperations.writeReport(
      {
        runRoot: ${JSON.stringify(runRoot)},
        ownerId: ${JSON.stringify(ownerId)},
        options: { runId: ${JSON.stringify(runId)} }
      },
      { scope: 'CONTROL' }
    )
    process.stdout.write(JSON.stringify(result))
  `
  const child = spawnSync(process.execPath, ['--input-type=module', '--eval', source], {
    encoding: 'utf8'
  })

  assert.equal(child.status, 0, child.stderr)
  assert.deepEqual(JSON.parse(child.stdout), {
    status: 'PASS',
    kind: 'P0_REPORT_BOUNDARY',
    scope: 'CONTROL',
    finalWriter: 'PENDING',
    identity: {
      runId,
      ownerId,
      reportPath: join(runRoot, 'report.json')
    }
  })
})

function createS11ReportPhaseBoundaryFixture(t, label) {
  const root = mkdtempSync(join(tmpdir(), `p0-review1-s11-report-boundary-${label}-`))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runId = `p0-review1-s11-report-boundary-${label}-a1`
  const runRoot = join(root, runId)
  const controlRoot = join(runRoot, 'control')
  mkdirSync(controlRoot, { recursive: true })
  const ownerToken = `p0-review1-s11-report-boundary-${label}-owner-token-a1`
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const ownershipPath = join(controlRoot, 'ownership.json')
  const ownershipBytes = `${JSON.stringify({
    schemaVersion: 1,
    status: 'ACTIVE',
    runId,
    ownerId,
    ownerToken
  }, null, 2)}\n`
  writeFileSync(ownershipPath, ownershipBytes)
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=report',
    `--run-id=${runId}`
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const reportPath = join(runRoot, 'report.json')
  return {
    context: { runRoot, ownerId, options },
    controlRoot,
    ownerId,
    ownershipBytes,
    ownershipPath,
    plan,
    reportPath,
    runId,
    runRoot
  }
}

test('default report phase without a runtime writer fails closed and writes no final report', async (t) => {
  const fixture = createS11ReportPhaseBoundaryFixture(t, 'missing-writer')
  const executeReportBoundary = smokeContracts.executeP0ReportPhaseBoundary
  assert.equal(
    typeof executeReportBoundary,
    'function',
    'P0_REPORT_PHASE_BOUNDARY_REQUIRED'
  )

  await assert.rejects(
    () => executeReportBoundary({
      context: fixture.context,
      plan: fixture.plan,
      writePhaseReport: undefined
    }),
    { message: 'P0_REPORT_PHASE_WRITER_REQUIRED' }
  )

  assert.deepEqual({
    reportPersisted: existsSync(fixture.reportPath),
    ownershipUnchanged: readFileSync(fixture.ownershipPath, 'utf8') === fixture.ownershipBytes,
    runEntries: readdirSync(fixture.runRoot).sort(),
    controlEntries: readdirSync(fixture.controlRoot).sort()
  }, {
    reportPersisted: false,
    ownershipUnchanged: true,
    runEntries: ['control'],
    controlEntries: ['ownership.json']
  })
})

test('default report phase accepts only exact typed evidence returned by the runtime writer', async (t) => {
  const fixture = createS11ReportPhaseBoundaryFixture(t, 'typed-evidence')
  const executeReportBoundary = smokeContracts.executeP0ReportPhaseBoundary
  assert.equal(
    typeof executeReportBoundary,
    'function',
    'P0_REPORT_PHASE_BOUNDARY_REQUIRED'
  )
  assert.equal(fixture.plan.scope, 'CONTROL')
  const exactEvidence = {
    status: 'PASS',
    kind: 'P0_REPORT_BOUNDARY',
    scope: fixture.plan.scope,
    finalWriter: 'PENDING',
    identity: {
      runId: fixture.runId,
      ownerId: fixture.ownerId,
      reportPath: fixture.reportPath
    }
  }
  const writerCalls = []
  const invoke = (label, evidence) => executeReportBoundary({
    context: fixture.context,
    plan: fixture.plan,
    async writePhaseReport(context, plan) {
      writerCalls.push(label)
      assert.equal(context, fixture.context)
      assert.equal(plan, fixture.plan)
      return evidence
    }
  })

  const validVariants = [
    ['original', exactEvidence],
    ['structured-clone', structuredClone(exactEvidence)]
  ]
  const accepted = []
  for (const [label, evidence] of validVariants) {
    const before = structuredClone(evidence)
    const result = await invoke(label, evidence)
    accepted.push({ label, result, writerStatus: evidence.status })
    assert.deepEqual(evidence, before)
    assert.equal(existsSync(fixture.reportPath), false)
  }
  assert.deepEqual(accepted, validVariants.map(([label]) => ({
    label,
    result: exactEvidence,
    writerStatus: 'PASS'
  })))

  const invalidVariants = [
    ['undefined', undefined],
    ['null', null],
    ['fail', { ...exactEvidence, status: 'FAIL' }],
    ['wrong-kind', { ...exactEvidence, kind: 'P0_OTHER_BOUNDARY' }],
    ['wrong-scope', { ...exactEvidence, scope: 'MATRIX' }],
    ['wrong-final-writer', { ...exactEvidence, finalWriter: 'COMPLETED' }],
    ['wrong-identity', {
      ...exactEvidence,
      identity: {
        ...exactEvidence.identity,
        reportPath: join(fixture.runRoot, 'other-report.json')
      }
    }],
    ['extra-contradiction', {
      ...exactEvidence,
      error: 'P0_TEST_REPORT_CONTRADICTION'
    }]
  ]
  const rejected = []
  for (const [label, evidence] of invalidVariants) {
    let error = null
    try {
      await invoke(label, evidence)
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    rejected.push({
      label,
      error,
      reportPersisted: existsSync(fixture.reportPath)
    })
  }
  assert.deepEqual(rejected, invalidVariants.map(([label]) => ({
    label,
    error: 'P0_REPORT_PHASE_RESULT_INVALID',
    reportPersisted: false
  })))
  assert.deepEqual(writerCalls, [
    ...validVariants.map(([label]) => label),
    ...invalidVariants.map(([label]) => label)
  ])
  assert.equal(readFileSync(fixture.ownershipPath, 'utf8'), fixture.ownershipBytes)
  assert.deepEqual(readdirSync(fixture.controlRoot).sort(), ['ownership.json'])
})

test('direct default Windows phase mutators reject before journal database process or report mutation', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s11-direct-phase-gate-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const callbacks = []
  const mutationBoundary = (label) => {
    callbacks.push(label)
    throw new Error(`P0_TEST_DIRECT_PHASE_MUTATION_REACHED:${label}`)
  }
  const boundaryAdapter = (kind) => new Proxy(Object.create(null), {
    get(_target, property) {
      if (typeof property === 'symbol' || property === 'then') return undefined
      return async () => mutationBoundary(`${kind}:${property}`)
    }
  })
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'linux',
    artifactBase,
    inheritedEnv: {},
    runCommand: async () => mutationBoundary('command'),
    databaseExists: async () => mutationBoundary('database:exists'),
    infrastructure: boundaryAdapter('infrastructure'),
    postgres: boundaryAdapter('postgres'),
    redis: boundaryAdapter('redis'),
    processManager: boundaryAdapter('process'),
    runAuthority: async () => mutationBoundary('authority'),
    writePhaseReport: async () => mutationBoundary('phase-report'),
    now: () => '2026-07-15T23:55:00.000Z'
  })
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=report',
    '--run-id=p0-review1-s11-direct-phase-gate-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const ownerToken = 'p0-review1-s11-direct-phase-gate-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const contexts = []
  const contextFor = (label) => {
    const context = {
      artifactBase,
      runRoot: join(artifactBase, label),
      options: { runId: `p0-review1-s11-direct-phase-${label}-a1` },
      ownerToken,
      ownerId,
      databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_s11_phase_matrix_1',
      matrixDatabase: 'fx_p0_user_e2e_s11_phase_matrix_1',
      canonicalDatabase: 'fx_p0_user_e2e_s11_phase_canonical_1',
      inheritedEnv: {},
      ownedDatabaseSegments: [],
      caseResults: []
    }
    contexts.push({
      label,
      context,
      keys: Object.keys(context).toSorted()
    })
    return context
  }
  const scenarios = [
    {
      surface: 'phase.runPreflight',
      context: contextFor('preflight'),
      invoke(context) {
        return dependencies.phaseOperations.runPreflight(context, plan)
      }
    },
    {
      surface: 'phase.stopProfileBackend',
      context: contextFor('stop-profile'),
      invoke(context) {
        return dependencies.phaseOperations.stopProfileBackend(context, 'UI_CORE')
      }
    },
    {
      surface: 'phase.prepareProfileDatabase',
      context: contextFor('prepare-database'),
      invoke(context) {
        return dependencies.phaseOperations.prepareProfileDatabase({
          phase: 'ui-core',
          attempt: 1
        }, context)
      }
    },
    {
      surface: 'phase.startProfileBackend',
      context: contextFor('start-profile'),
      invoke(context) {
        return dependencies.phaseOperations.startProfileBackend({
          phase: 'ui-core',
          profile: 'UI_CORE',
          attempt: 1,
          environment: {}
        }, context)
      }
    },
    {
      surface: 'phase.stopParentBackend',
      context: contextFor('stop-parent'),
      invoke(context) {
        return dependencies.phaseOperations.stopParentBackend(context)
      }
    },
    {
      surface: 'phase.runAuthority',
      context: contextFor('authority'),
      invoke(context) {
        return dependencies.phaseOperations.runAuthority(context, plan)
      }
    },
    {
      surface: 'phase.writeReport',
      context: contextFor('phase-report'),
      invoke(context) {
        return dependencies.phaseOperations.writeReport(context, plan)
      }
    }
  ]
  const observed = []

  for (const { surface, context, invoke } of scenarios) {
    let error = null
    try {
      await invoke(context)
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    observed.push({ surface, error })
  }

  const resources = contexts.map(({ label, context, keys }) => ({
    label,
    keysUnchanged: JSON.stringify(Object.keys(context).toSorted()) === JSON.stringify(keys),
    phaseDatabaseSegmentsPresent: Object.hasOwn(context, 'phaseDatabaseSegments'),
    ownedDatabaseSegments: [...context.ownedDatabaseSegments],
    caseResults: [...context.caseResults]
  }))
  assert.deepEqual({
    observed,
    callbacks,
    artifactBaseExists: existsSync(artifactBase),
    rootEntries: readdirSync(root).toSorted(),
    resources
  }, {
    observed: scenarios.map(({ surface }) => ({
      surface,
      error: 'P0_WINDOWS_JOB_OBJECT_REQUIRED'
    })),
    callbacks: [],
    artifactBaseExists: false,
    rootEntries: [],
    resources: contexts.map(({ label }) => ({
      label,
      keysUnchanged: true,
      phaseDatabaseSegmentsPresent: false,
      ownedDatabaseSegments: [],
      caseResults: []
    }))
  })
})

test('direct default Windows external probes reject before listeners sockets database or ports', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s13-direct-probe-gate-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const ownerToken = 'p0-review1-s13-direct-probe-gate-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const callbacks = []
  const boundary = (label) => {
    callbacks.push(label)
    throw new Error(`P0_TEST_EXTERNAL_PROBE_REACHED:${label}`)
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    installSignalHandlers() { return boundary('listener:install') },
    infrastructure: {
      async assertPortsFree() { return boundary('ports:assert') }
    },
    processManager: {
      async waitForBackendHealth() { return boundary('http:health') },
      async waitForBusinessEndpoint() { return boundary('http:business') }
    },
    postgres: {
      async probeDatabase() { return boundary('database:probe') },
      async probeBackendConnection() { return boundary('database:activity') }
    },
    redis: {
      async get() { return boundary('redis:get') }
    },
    async runCommand() { return boundary('command') }
  })
  const contextFor = (label) => {
    const runId = `p0-review1-s13-direct-probe-${label}-a1`
    const matrixDatabase = 'fx_p0_user_e2e_s13_direct_probe_matrix_1'
    return {
      artifactBase,
      runRoot: join(artifactBase, runId),
      options: { runId },
      runId,
      ownerToken,
      ownerId,
      databaseUrl: `jdbc:postgresql://127.0.0.1:5432/${matrixDatabase}`,
      matrixDatabase,
      canonicalDatabase: 'fx_p0_user_e2e_s13_direct_probe_canonical_1',
      inheritedEnv: {}
    }
  }
  const profilePortContext = contextFor('profile-port')
  const healthContext = contextFor('profile-health')
  const businessContext = contextFor('profile-business')
  const databaseContext = contextFor('profile-database')
  const redisContext = contextFor('redis-owner')
  const businessPortsContext = contextFor('business-ports')
  const canonicalContext = contextFor('canonical-verify')
  const scenarios = [
    {
      surface: 'installSignalHandlers',
      invoke() { return dependencies.installSignalHandlers(() => {}) }
    },
    {
      surface: 'phaseOperations.assertProfilePortFree',
      invoke() {
        return dependencies.phaseOperations.assertProfilePortFree(
          18086,
          profilePortContext,
          'UI_CORE'
        )
      }
    },
    {
      surface: 'phaseOperations.waitForProfileHealth',
      invoke() {
        return dependencies.phaseOperations.waitForProfileHealth(
          { pid: 7301 },
          healthContext,
          'UI_CORE'
        )
      }
    },
    {
      surface: 'phaseOperations.waitForProfileBusinessEndpoint',
      invoke() {
        return dependencies.phaseOperations.waitForProfileBusinessEndpoint(
          { pid: 7302 },
          businessContext,
          'UI_CORE'
        )
      }
    },
    {
      surface: 'phaseOperations.verifyProfileDatabaseIdentity',
      invoke() {
        return dependencies.phaseOperations.verifyProfileDatabaseIdentity(
          { pid: 7303 },
          databaseContext,
          'UI_CORE',
          {
            segmentName: databaseContext.matrixDatabase,
            databaseUrl: databaseContext.databaseUrl
          }
        )
      }
    },
    {
      surface: 'phaseOperations.assertRedisOwnership',
      invoke() {
        return dependencies.phaseOperations.assertRedisOwnership(redisContext, 'direct-probe')
      }
    },
    {
      surface: 'phaseOperations.assertBusinessPortsFree',
      invoke() {
        return dependencies.phaseOperations.assertBusinessPortsFree(
          [18086, 5199, 5200],
          businessPortsContext
        )
      }
    },
    {
      surface: 'phaseOperations.verifyCanonicalChildCleanup',
      invoke() {
        return dependencies.phaseOperations.verifyCanonicalChildCleanup(
          { status: 0, signal: null },
          canonicalContext
        )
      }
    }
  ]
  const observed = []
  for (const scenario of scenarios) {
    let error = null
    try {
      await scenario.invoke()
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    observed.push({ surface: scenario.surface, error })
  }

  assert.deepEqual({
    observed,
    callbacks,
    artifactBaseExists: existsSync(artifactBase),
    canonicalReportExists: existsSync(join(
      canonicalContext.runRoot,
      'canonical',
      'report.json'
    )),
    rootEntries: readdirSync(root).toSorted()
  }, {
    observed: scenarios.map(({ surface }) => ({
      surface,
      error: 'P0_WINDOWS_JOB_OBJECT_REQUIRED'
    })),
    callbacks: [],
    artifactBaseExists: false,
    canonicalReportExists: false,
    rootEntries: []
  })
})

test('direct default Windows canonical and dispatch mutators reject before callbacks or files', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s11-direct-canonical-gate-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const cleanupRunId = 'p0-review1-s11-direct-cleanup-gate-a1'
  const cleanupToken = 'p0-review1-s11-direct-cleanup-gate-owner-token-a1'
  const cleanupDatabase = 'fx_p0_user_e2e_s11_cleanup_matrix_1'
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId: cleanupRunId,
    runToken: cleanupToken,
    mode: 'discovery',
    selection: s10RequestedPhaseSelection('selected'),
    database: { matrix: cleanupDatabase },
    now: () => '2026-07-15T23:56:00.000Z'
  })
  markP0RedisSnapshotReady(control)
  const active = JSON.parse(readFileSync(control.ownershipPath, 'utf8'))
  active.journal.sequence = 1
  active.journal.resources = [{
    type: 'database',
    id: cleanupDatabase,
    state: 'STARTED',
    sequence: 1,
    recordedAt: '2026-07-15T23:56:01.000Z'
  }]
  writeFileSync(control.ownershipPath, `${JSON.stringify(active, null, 2)}\n`)
  const redisRecoveryPath = join(control.runRoot, 'control', 'redis.json')
  const trackedFiles = [control.ownershipPath, redisRecoveryPath]
  const filesBefore = s10ArtifactSnapshot(trackedFiles)
  const directoriesBefore = {
    artifacts: readdirSync(artifactBase).toSorted(),
    run: readdirSync(control.runRoot).toSorted(),
    control: readdirSync(join(control.runRoot, 'control')).toSorted()
  }

  const callbacks = []
  const mutationBoundary = (label) => {
    callbacks.push(label)
    throw new Error(`P0_TEST_DIRECT_CANONICAL_MUTATION_REACHED:${label}`)
  }
  const boundaryAdapter = (kind) => new Proxy(Object.create(null), {
    get(_target, property) {
      if (typeof property === 'symbol' || property === 'then') return undefined
      return async () => mutationBoundary(`${kind}:${property}`)
    }
  })
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    platform: 'linux',
    artifactBase,
    inheritedEnv: {},
    captureIdentity: async () => mutationBoundary('identity'),
    runToken: () => mutationBoundary('owner-token'),
    randomSuffix: () => mutationBoundary('database-suffix'),
    runCommand: async () => mutationBoundary('command'),
    databaseExists: async () => mutationBoundary('database:exists'),
    infrastructure: boundaryAdapter('infrastructure'),
    postgres: boundaryAdapter('postgres'),
    redis: boundaryAdapter('redis'),
    processManager: boundaryAdapter('process'),
    dispatchCase: async () => mutationBoundary('dispatch'),
    now: () => '2026-07-15T23:57:00.000Z'
  })
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--phase=selected',
    '--case=SPOT-01',
    '--run-id=p0-review1-s11-direct-initialize-gate-a1'
  ])
  const plan = p0CaseContracts.planP0Execution(options, P0_CASES)
  const ownerToken = 'p0-review1-s11-direct-canonical-gate-owner-token-a1'
  const ownerId = createHash('sha256').update(ownerToken).digest('hex')
  const contexts = []
  const contextFor = (label, extra = {}) => {
    const context = {
      artifactBase,
      runRoot: join(artifactBase, `p0-review1-s11-${label}-a1`),
      options: { runId: `p0-review1-s11-${label}-a1`, mode: 'discovery' },
      ownerToken,
      ownerId,
      canonicalDatabase: 'fx_p0_user_e2e_s11_canonical_1',
      matrixDatabase: 'fx_p0_user_e2e_s11_matrix_1',
      databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_s11_matrix_1',
      inheritedEnv: {},
      ownedDatabaseSegments: [],
      caseResults: [],
      ...extra
    }
    contexts.push({ label, context, keys: Object.keys(context).toSorted() })
    return context
  }
  const canonicalContext = contextFor('direct-canonical')
  const dispatchContext = contextFor('direct-dispatch')
  const reportContext = contextFor('direct-final-report', {
    async assertIdentity() {
      return mutationBoundary('report:identity')
    }
  })
  const scenarios = [
    {
      surface: 'initializeOwnership',
      invoke() {
        return dependencies.initializeOwnership(options, plan)
      }
    },
    {
      surface: 'phase.runCanonicalChild',
      invoke() {
        return dependencies.phaseOperations.runCanonicalChild({
          command: process.execPath,
          args: ['--version']
        }, canonicalContext)
      }
    },
    {
      surface: 'dispatchCase',
      invoke() {
        return dependencies.dispatchCase(P0_CASES[0], dispatchContext, Object.create(null), {})
      }
    },
    {
      surface: 'writeReport',
      invoke() {
        return dependencies.writeReport({
          plan: {
            scope: 'CONTROL',
            phases: ['report'],
            definitions: [],
            executionEntries: []
          },
          phaseResult: {},
          controlResults: [{
            phase: 'report',
            status: 'PASS',
            evidence: { kind: 'P0_REPORT_BOUNDARY' }
          }],
          caseResults: []
        }, reportContext)
      }
    },
    {
      surface: 'cleanup.recovery',
      invoke() {
        return dependencies.cleanup(undefined, {}, { runId: cleanupRunId })
      }
    }
  ]
  const observed = []

  for (const { surface, invoke } of scenarios) {
    let error = null
    try {
      await invoke()
    } catch (cause) {
      error = cause?.message ?? String(cause)
    }
    observed.push({ surface, error })
  }

  const resources = contexts.map(({ label, context, keys }) => ({
    label,
    keysUnchanged: JSON.stringify(Object.keys(context).toSorted()) === JSON.stringify(keys),
    ownedDatabaseSegments: [...context.ownedDatabaseSegments],
    caseResults: [...context.caseResults]
  }))
  assert.deepEqual({
    observed,
    callbacks,
    filesUnchanged: JSON.stringify(s10ArtifactSnapshot(trackedFiles))
      === JSON.stringify(filesBefore),
    directoriesUnchanged: JSON.stringify({
      artifacts: readdirSync(artifactBase).toSorted(),
      run: readdirSync(control.runRoot).toSorted(),
      control: readdirSync(join(control.runRoot, 'control')).toSorted()
    }) === JSON.stringify(directoriesBefore),
    resources
  }, {
    observed: scenarios.map(({ surface }) => ({
      surface,
      error: 'P0_WINDOWS_JOB_OBJECT_REQUIRED'
    })),
    callbacks: [],
    filesUnchanged: true,
    directoriesUnchanged: true,
    resources: contexts.map(({ label }) => ({
      label,
      keysUnchanged: true,
      ownedDatabaseSegments: [],
      caseResults: []
    }))
  })
})

test('cleanup-only runP0Suite skips signal installation and reaches strict recovery', {
  skip: process.platform !== 'win32'
}, async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s14-cleanup-lifecycle-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const artifactBase = join(root, 'artifacts')
  const runId = 'p0-review1-s14-cleanup-lifecycle-a1'
  const runToken = 'p0-review1-s14-cleanup-lifecycle-owner-token-a1'
  const cleanedAt = '2026-07-16T00:14:00.000Z'
  const options = p0CaseContracts.parseP0Cli([
    '--suite=p0',
    '--mode=certification',
    '--phase=cleanup',
    `--run-id=${runId}`
  ])
  const control = await smokeContracts.createControlManifest({
    artifactBase,
    runId,
    runToken,
    mode: 'certification',
    selection: { caseIds: [], phases: [], profiles: [], viewports: [] },
    database: {}
  })
  const callbacks = {
    signals: 0,
    initialize: 0,
    execute: 0,
    report: 0,
    redis: 0,
    postgres: 0
  }
  const cleanupEvents = []
  const forbidden = (name) => {
    callbacks[name] += 1
    throw new Error(`P0_TEST_S14_UNEXPECTED_${name.toUpperCase()}_CALLBACK`)
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      's14-cleanup-only-lifecycle'
    ),
    installSignalHandlers() { return forbidden('signals') },
    captureIdentity() { return forbidden('initialize') },
    runToken() { return forbidden('initialize') },
    randomSuffix() { return forbidden('initialize') },
    databaseExists() { return forbidden('initialize') },
    dispatchCase() { return forbidden('execute') },
    runAuthority() { return forbidden('execute') },
    writePhaseReport() { return forbidden('report') },
    processManager: {
      async stopParentBackend() { cleanupEvents.push('process') }
    },
    infrastructure: {
      async assertPortsFree() { cleanupEvents.push('ports') }
    },
    redis: new Proxy(Object.create(null), {
      get() { return forbidden('redis') }
    }),
    postgres: new Proxy(Object.create(null), {
      get() { return forbidden('postgres') }
    }),
    now: () => cleanedAt
  })

  const result = await smokeContracts.runP0Suite(options, dependencies)

  assert.equal(result.execution, null)
  assert.equal(result.report, null)
  assert.deepEqual(result.cleanup, {
    status: 'CLEANED',
    databases: 0,
    redis: 'NOT_ACQUIRED',
    restored: 0
  })
  assert.deepEqual(callbacks, {
    signals: 0,
    initialize: 0,
    execute: 0,
    report: 0,
    redis: 0,
    postgres: 0
  })
  assert.deepEqual(cleanupEvents, ['process', 'ports'])
  assert.equal(existsSync(join(control.runRoot, 'control', 'redis.json')), false)
  assert.deepEqual(JSON.parse(readFileSync(control.ownershipPath, 'utf8')), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId,
    ownerId: control.ownerId,
    cleanedAt,
    receipt: {
      key: null,
      state: 'NOT_REQUIRED',
      completedAt: cleanedAt
    }
  })
})

test('standalone Redis cleanup keeps owner restore and release on one physical session', async () => {
  const binding = s11RedisBinding()
  const runToken = 'p0-review1-s14-standalone-redis-owner-token-a1'
  const compareDeleteScript = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
  const events = []
  const sessions = []
  const markerPublications = []
  const values = new Map([
    ['quote:BTCUSDT', 'during-run-btc'],
    ['quote:ETHUSDT', 'during-run-eth']
  ])
  const expiries = new Map([
    ['quote:BTCUSDT', 1800000000000],
    ['quote:ETHUSDT', null]
  ])
  let owner = runToken
  let receipt = null
  let createSessionCalls = 0
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      throw new Error(`P0_TEST_LEGACY_REDIS_REQUEST_USED:${args[0]}`)
    },
    () => binding,
    async (expectedBinding, { stage } = {}) => {
      events.push({ type: 'verify', stage })
      assert.deepEqual(expectedBinding, binding)
      return structuredClone(binding)
    },
    {
      createSession({ host, port }) {
        createSessionCalls += 1
        const sessionId = `standalone-cleanup-session-${createSessionCalls}`
        events.push({ type: 'connect', sessionId, host, port })
        const session = createS11RedisFakeSession({
          id: sessionId,
          events,
          respond(args) {
            if (args[0] === 'GET') {
              assert.deepEqual(args, ['GET', 'p0:e2e:owner'])
              return owner
            }
            if (args[0] === 'SET') {
              assert.deepEqual(args, ['SET', 'quote:BTCUSDT', 'before-cleanup-btc'])
              values.set(args[1], args[2])
              return 'OK'
            }
            if (args[0] === 'PEXPIREAT') {
              assert.deepEqual(args, [
                'PEXPIREAT',
                'quote:BTCUSDT',
                '1900000000000'
              ])
              expiries.set(args[1], Number(args[2]))
              return 1
            }
            if (args[0] === 'DEL') {
              assert.deepEqual(args, ['DEL', 'quote:ETHUSDT'])
              values.delete(args[1])
              expiries.delete(args[1])
              return 1
            }
            if (args[0] === 'EVAL') {
              assert.deepEqual(args, [
                'EVAL',
                compareDeleteScript,
                '1',
                'p0:e2e:owner',
                runToken
              ])
              if (owner !== runToken) return 0
              owner = null
              return 1
            }
            throw new Error(`P0_TEST_UNEXPECTED_REDIS_COMMAND:${args[0]}`)
          }
        })
        sessions.push(session)
        return session
      }
    }
  )

  const result = await smokeContracts.cleanupOwnedRedis({
    redis,
    runToken,
    snapshot: [
      {
        key: 'quote:BTCUSDT',
        exists: true,
        value: 'before-cleanup-btc',
        expiresAtMs: 1900000000000
      },
      {
        key: 'quote:ETHUSDT',
        exists: false,
        value: null,
        expiresAtMs: null
      }
    ],
    touchedKeys: ['quote:BTCUSDT', 'quote:ETHUSDT']
  })

  const commands = events.filter(({ type }) => type === 'command')
  assert.deepEqual(result, { restored: 2, ownerReleased: true })
  assert.equal(createSessionCalls, 1)
  assert.deepEqual(
    events.filter(({ type }) => type === 'verify').map(({ stage }) => stage),
    ['PRE_CONNECT', 'POST_CONNECT']
  )
  assert.deepEqual(events.filter(({ type }) => type === 'connect'), [{
    type: 'connect',
    sessionId: 'standalone-cleanup-session-1',
    host: '127.0.0.1',
    port: 6379
  }])
  assert.deepEqual(commands, [
    {
      type: 'command',
      sessionId: 'standalone-cleanup-session-1',
      args: ['GET', 'p0:e2e:owner']
    },
    {
      type: 'command',
      sessionId: 'standalone-cleanup-session-1',
      args: ['SET', 'quote:BTCUSDT', 'before-cleanup-btc']
    },
    {
      type: 'command',
      sessionId: 'standalone-cleanup-session-1',
      args: ['PEXPIREAT', 'quote:BTCUSDT', '1900000000000']
    },
    {
      type: 'command',
      sessionId: 'standalone-cleanup-session-1',
      args: ['DEL', 'quote:ETHUSDT']
    },
    {
      type: 'command',
      sessionId: 'standalone-cleanup-session-1',
      args: [
        'EVAL',
        compareDeleteScript,
        '1',
        'p0:e2e:owner',
        runToken
      ]
    }
  ])
  assert.deepEqual(events.filter(({ type }) => type === 'close'), [{
    type: 'close',
    sessionId: 'standalone-cleanup-session-1',
    state: 'OPEN'
  }])
  assert.deepEqual(sessions.map(({ state }) => state), ['CLOSED'])
  assert.deepEqual({
    owner,
    values: Object.fromEntries(values),
    expiries: Object.fromEntries(expiries),
    receipt,
    markerPublications
  }, {
    owner: null,
    values: { 'quote:BTCUSDT': 'before-cleanup-btc' },
    expiries: { 'quote:BTCUSDT': 1900000000000 },
    receipt: null,
    markerPublications: []
  })
})

test('default Redis cleanup keeps owner restore and release on one physical session', async (t) => {
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s14-default-redis-session-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const fixture = await createS10CleanupComposeFixture(root, 's14-redis-session')
  removeS10CleanupDatabaseJournal(fixture)
  const binding = fixture.persistedIdentity.redis
  const receiptKey = `p0:e2e:cleanup:${fixture.control.ownerId}`
  const cleanedAt = '2026-07-16T00:24:00.000Z'
  const releaseOwnershipScript = [
    "local owner = redis.call('get', KEYS[1])",
    "local receipt = redis.call('get', KEYS[2])",
    "if owner == ARGV[1] then",
    "  if receipt and receipt ~= ARGV[2] then return 'MISMATCH' end",
    "  redis.call('del', KEYS[1])",
    "  redis.call('set', KEYS[2], ARGV[2])",
    "  return 'RELEASED'",
    'end',
    "if not owner and receipt == ARGV[2] then return 'ALREADY_RELEASED' end",
    "return 'MISMATCH'"
  ].join('\n')
  const events = []
  const sessions = []
  const markerPublications = []
  const armedObservations = []
  const verificationSnapshots = []
  const values = new Map([['quote:BTCUSDT', 'during-run']])
  const expiries = new Map([['quote:BTCUSDT', 1800000000000]])
  let owner = fixture.runToken
  let receipt = null
  let createSessionCalls = 0
  let composeVerificationCount = 0
  const infrastructure = createStrictCleanupComposeInfrastructure({
    composeTarget: {
      expectedProject: fixture.composeTarget.expectedProject,
      composeFiles: [...fixture.composeTarget.composeFiles]
    },
    composeIdentity: fixture.persistedIdentity
  }, {
    async assertPortsFree(ports) {
      events.push({ type: 'ports', ports: [...ports] })
    },
    record(label) {
      events.push({ type: label })
      if (label === 'compose:containers') composeVerificationCount += 1
    }
  })
  const redis = smokeContracts.createLoopbackRedisAdapter(
    async (args) => {
      throw new Error(`P0_TEST_LEGACY_REDIS_REQUEST_USED:${args[0]}`)
    },
    () => {
      events.push({ type: 'binding', composeVerificationCount })
      assert.ok(composeVerificationCount >= 1)
      return binding
    },
    async (expectedBinding, { stage } = {}) => {
      events.push({ type: 'verify', stage, composeVerificationCount })
      verificationSnapshots.push({ stage, composeVerificationCount })
      assert.deepEqual(expectedBinding, binding)
      return structuredClone(binding)
    },
    {
      createSession({ host, port }) {
        createSessionCalls += 1
        const sessionId = `default-cleanup-session-${createSessionCalls}`
        events.push({ type: 'connect', sessionId, host, port })
        const session = createS11RedisFakeSession({
          id: sessionId,
          events,
          respond(args) {
            if (args[0] === 'GET') {
              assert.deepEqual(args, ['GET', 'p0:e2e:owner'])
              return owner
            }
            if (args[0] === 'SET') {
              assert.deepEqual(args, ['SET', 'quote:BTCUSDT', 'before-cleanup'])
              values.set(args[1], args[2])
              return 'OK'
            }
            if (args[0] === 'PEXPIREAT') {
              assert.deepEqual(args, [
                'PEXPIREAT',
                'quote:BTCUSDT',
                '1900000000000'
              ])
              expiries.set(args[1], Number(args[2]))
              return 1
            }
            if (args[0] === 'EVAL' && args[1] === releaseOwnershipScript) {
              assert.deepEqual(args, [
                'EVAL',
                releaseOwnershipScript,
                '2',
                'p0:e2e:owner',
                receiptKey,
                fixture.runToken,
                fixture.control.ownerId
              ])
              const armed = JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8'))
              armedObservations.push({
                sessionId,
                status: armed.status,
                redisState: armed.redisState
              })
              assert.equal(armed.status, 'ACTIVE')
              assert.equal(armed.redisState, 'REDIS_RELEASE_ARMED')
              if (owner === fixture.runToken) {
                if (receipt !== null && receipt !== fixture.control.ownerId) {
                  return 'MISMATCH'
                }
                owner = null
                receipt = fixture.control.ownerId
                return 'RELEASED'
              }
              return owner === null && receipt === fixture.control.ownerId
                ? 'ALREADY_RELEASED'
                : 'MISMATCH'
            }
            if (args[0] === 'EVAL' && args[1] === S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT) {
              assert.deepEqual(args, [
                'EVAL',
                S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
                '1',
                receiptKey,
                fixture.control.ownerId
              ])
              if (receipt === null) return 'ALREADY_ABSENT'
              if (receipt !== fixture.control.ownerId) return 'MISMATCH'
              receipt = null
              return 'REMOVED'
            }
            throw new Error(`P0_TEST_UNEXPECTED_REDIS_COMMAND:${args[0]}`)
          }
        })
        sessions.push(session)
        return session
      }
    }
  )
  const replaceOwnership = async (path, marker) => {
    assert.equal(path, fixture.control.ownershipPath)
    const publication = structuredClone(marker)
    markerPublications.push(publication)
    events.push({
      type: 'publish',
      status: publication.status,
      receiptState: publication.receipt?.state ?? null
    })
    writeFileSync(path, `${JSON.stringify(marker, null, 2)}\n`)
  }
  const dependencies = smokeContracts.createDefaultP0Dependencies({
    artifactBase: fixture.artifactBase,
    inheritedEnv: {},
    processTreeProvider: createCleanupOnlyProcessTreeProvider(
      's14-default-redis-session'
    ),
    infrastructure,
    processManager: {
      async stopParentBackend() { events.push({ type: 'process' }) }
    },
    postgres: {
      async readDatabaseOwnership() {
        throw new Error('P0_TEST_S14_POSTGRES_ACCESS_FORBIDDEN')
      }
    },
    redis,
    replaceOwnership,
    now: () => cleanedAt
  })

  const result = await dependencies.cleanup(undefined, {}, { runId: fixture.runId })

  const commands = events.filter(({ type }) => type === 'command')
  const releaseIndex = events.findIndex(({ type, args }) => (
    type === 'command' && args[1] === releaseOwnershipScript
  ))
  const pendingIndex = events.findIndex(({ type, receiptState }) => (
    type === 'publish' && receiptState === 'PENDING'
  ))
  const receiptRemovalIndex = events.findIndex(({ type, args }) => (
    type === 'command' && args[1] === S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT
  ))
  const completedIndex = events.findIndex(({ type, receiptState }) => (
    type === 'publish' && receiptState === 'COMPLETED'
  ))
  assert.deepEqual(result, {
    status: 'CLEANED',
    databases: 0,
    redis: 'RESTORED',
    restored: 1
  })
  assert.equal(
    releaseIndex >= 0
      && releaseIndex < pendingIndex
      && pendingIndex < receiptRemovalIndex
      && receiptRemovalIndex < completedIndex,
    true
  )
  assert.equal(createSessionCalls, 2)
  assert.deepEqual(verificationSnapshots, [
    { stage: 'PRE_CONNECT', composeVerificationCount: 1 },
    { stage: 'POST_CONNECT', composeVerificationCount: 1 },
    { stage: 'PRE_CONNECT', composeVerificationCount: 2 },
    { stage: 'POST_CONNECT', composeVerificationCount: 2 }
  ])
  assert.deepEqual(events.filter(({ type }) => type === 'connect'), [
    {
      type: 'connect',
      sessionId: 'default-cleanup-session-1',
      host: '127.0.0.1',
      port: 6379
    },
    {
      type: 'connect',
      sessionId: 'default-cleanup-session-2',
      host: '127.0.0.1',
      port: 6379
    }
  ])
  assert.deepEqual(commands, [
    {
      type: 'command',
      sessionId: 'default-cleanup-session-1',
      args: ['GET', 'p0:e2e:owner']
    },
    {
      type: 'command',
      sessionId: 'default-cleanup-session-1',
      args: ['SET', 'quote:BTCUSDT', 'before-cleanup']
    },
    {
      type: 'command',
      sessionId: 'default-cleanup-session-1',
      args: ['PEXPIREAT', 'quote:BTCUSDT', '1900000000000']
    },
    {
      type: 'command',
      sessionId: 'default-cleanup-session-1',
      args: [
        'EVAL',
        releaseOwnershipScript,
        '2',
        'p0:e2e:owner',
        receiptKey,
        fixture.runToken,
        fixture.control.ownerId
      ]
    },
    {
      type: 'command',
      sessionId: 'default-cleanup-session-2',
      args: [
        'EVAL',
        S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
        '1',
        receiptKey,
        fixture.control.ownerId
      ]
    }
  ])
  assert.deepEqual(events.filter(({ type }) => type === 'close'), [
    { type: 'close', sessionId: 'default-cleanup-session-1', state: 'OPEN' },
    { type: 'close', sessionId: 'default-cleanup-session-2', state: 'OPEN' }
  ])
  assert.deepEqual(sessions.map(({ state }) => state), ['CLOSED', 'CLOSED'])
  assert.deepEqual(armedObservations, [{
    sessionId: 'default-cleanup-session-1',
    status: 'ACTIVE',
    redisState: 'REDIS_RELEASE_ARMED'
  }])
  assert.deepEqual({
    owner,
    values: Object.fromEntries(values),
    expiries: Object.fromEntries(expiries),
    receipt
  }, {
    owner: null,
    values: { 'quote:BTCUSDT': 'before-cleanup' },
    expiries: { 'quote:BTCUSDT': 1900000000000 },
    receipt: null
  })
  assert.deepEqual(
    markerPublications.map((marker) => ({
      status: marker.status,
      receiptState: marker.receipt.state,
      completedAt: marker.receipt.completedAt
    })),
    [
      { status: 'CLEANED', receiptState: 'PENDING', completedAt: null },
      { status: 'CLEANED', receiptState: 'COMPLETED', completedAt: cleanedAt }
    ]
  )
  assert.deepEqual(JSON.parse(readFileSync(fixture.control.ownershipPath, 'utf8')), {
    schemaVersion: 2,
    status: 'CLEANED',
    runId: fixture.runId,
    ownerId: fixture.control.ownerId,
    cleanedAt,
    composeTarget: {
      expectedProject: fixture.composeTarget.expectedProject,
      composeFiles: [...fixture.composeTarget.composeFiles]
    },
    composeIdentity: fixture.persistedIdentity,
    receipt: {
      key: receiptKey,
      state: 'COMPLETED',
      completedAt: cleanedAt
    }
  })
})

test('canonical cleanup preserves Redis ownership until processes ports and database are clean', async () => {
  assert.equal(
    typeof smokeContracts.cleanupCanonicalOwnershipBarrier,
    'function',
    'P0_CANONICAL_CLEANUP_OWNERSHIP_BARRIER_REQUIRED'
  )
  assert.match(
    smokeContracts.runCanonicalSmoke.toString(),
    /await cleanupCanonicalOwnershipBarrier\(\{/
  )
  const runToken = 'p0-review1-s14-canonical-cleanup-owner-token-a1'
  const failureScenarios = [
    {
      failureAt: 'process',
      error: 'INJECTED_CANONICAL_PROCESS_FAILURE',
      expectedEvents: ['process']
    },
    {
      failureAt: 'ports',
      error: 'INJECTED_CANONICAL_PORTS_FAILURE',
      expectedEvents: ['process', 'ports']
    },
    {
      failureAt: 'database',
      error: 'INJECTED_CANONICAL_DATABASE_FAILURE',
      expectedEvents: ['process', 'ports', 'database']
    }
  ]

  for (const scenario of failureScenarios) {
    const events = []
    let owner = runToken
    let redisCleanupCalls = 0
    const record = (stage) => {
      events.push(stage)
      if (stage === scenario.failureAt) throw new Error(scenario.error)
    }

    await assert.rejects(
      smokeContracts.cleanupCanonicalOwnershipBarrier({
        async stopManagedProcesses() { record('process') },
        async assertOwnedPortsFree() { record('ports') },
        async dropOwnedDatabase() { record('database') },
        async cleanupRedisOwnership() {
          events.push('redis')
          redisCleanupCalls += 1
          owner = null
        }
      }),
      { name: 'Error', message: scenario.error },
      scenario.failureAt
    )

    assert.deepEqual({
      events,
      redisCleanupCalls,
      owner
    }, {
      events: scenario.expectedEvents,
      redisCleanupCalls: 0,
      owner: runToken
    }, scenario.failureAt)
  }

  const successEvents = []
  let successOwner = runToken
  let successRedisCleanupCalls = 0
  await smokeContracts.cleanupCanonicalOwnershipBarrier({
    async stopManagedProcesses() { successEvents.push('process') },
    async assertOwnedPortsFree() { successEvents.push('ports') },
    async dropOwnedDatabase() { successEvents.push('database') },
    async cleanupRedisOwnership() {
      successEvents.push('redis')
      successRedisCleanupCalls += 1
      assert.equal(successOwner, runToken)
      successOwner = null
    }
  })

  assert.deepEqual({
    events: successEvents,
    redisCleanupCalls: successRedisCleanupCalls,
    owner: successOwner
  }, {
    events: ['process', 'ports', 'database', 'redis'],
    redisCleanupCalls: 1,
    owner: null
  })
})

test('preflight abort uses independent cleanup signal and aggregates stop and port failures', async () => {
  const executionController = new AbortController()
  const abortReason = new Error('INJECTED_PREFLIGHT_EXECUTION_ABORT')
  const backend = { pid: 15001 }
  const expectedGateIds = [
    'backend-unit',
    'node-contracts',
    'web-test',
    'web-build',
    'admin-test',
    'admin-build',
    'architecture',
    'security-guards',
    'database-concurrency-it',
    'surefire-gate'
  ]
  const gateCommands = []
  const gateRecords = []
  const stopInvocations = []
  const portInvocations = []
  const laterMutations = {
    business: 0,
    verify: 0,
    contract: 0
  }

  let failure
  try {
    await smokeContracts.runP0Preflight({
      projectRoot: join(tmpdir(), 'p0-review1-s15-preflight'),
      gateOutput: join(tmpdir(), 'p0-review1-s15-preflight-gate.json'),
      databaseUrl: 'jdbc:postgresql://127.0.0.1:5432/fx_p0_user_e2e_s15_preflight_1',
      now: () => '2026-07-16T00:40:00.000Z',
      signal: executionController.signal,
      operations: {
        async runCommand(descriptor) {
          assert.equal(descriptor.signal, executionController.signal)
          gateCommands.push(descriptor.id)
          if (['contract-export', 'contract-check'].includes(descriptor.id)) {
            laterMutations.contract += 1
          }
          return { status: 0, signal: null, stdout: '', stderr: '' }
        },
        async recordGate(id, result) {
          assert.equal(result.status, 0)
          gateRecords.push(id)
        },
        async startOwnedBackend(details) {
          assert.equal(details.signal, executionController.signal)
          assert.equal(details.profile, 'UI_CORE')
          return backend
        },
        async waitForBackendHealth(received, url, signal) {
          assert.equal(received, backend)
          assert.equal(url, 'http://127.0.0.1:18086/actuator/health')
          assert.equal(signal, executionController.signal)
          executionController.abort(abortReason)
        },
        async waitForBusinessEndpoint() {
          laterMutations.business += 1
        },
        async verifyBackendDatabaseIdentity() {
          laterMutations.verify += 1
        },
        async stopOwnedBackend(received, { signal }) {
          assert.equal(received, backend)
          stopInvocations.push({ signal, abortedAtCall: signal.aborted })
          throw new Error('INJECTED_PREFLIGHT_STOP_FAILURE')
        },
        async assertBusinessPortsFree(ports, { signal }) {
          assert.deepEqual(ports, [18086])
          portInvocations.push({ signal, abortedAtCall: signal.aborted })
          throw new Error('INJECTED_PREFLIGHT_PORT_FAILURE')
        }
      }
    })
  } catch (error) {
    failure = error
  }

  assert.equal(failure instanceof Error, true)
  const flattened = []
  const visited = new Set()
  const collectErrors = (error) => {
    if (!(error instanceof Error) || visited.has(error)) return
    visited.add(error)
    flattened.push(error)
    if (Array.isArray(error.errors)) {
      for (const nested of error.errors) collectErrors(nested)
    }
    collectErrors(error.cause)
  }
  collectErrors(failure)
  const messages = flattened.map(({ message }) => message)

  assert.equal(flattened.includes(abortReason), true)
  assert.equal(messages.includes('INJECTED_PREFLIGHT_EXECUTION_ABORT'), true)
  assert.equal(messages.includes('INJECTED_PREFLIGHT_STOP_FAILURE'), true)
  assert.equal(messages.includes('INJECTED_PREFLIGHT_PORT_FAILURE'), true)
  assert.deepEqual(gateCommands, expectedGateIds)
  assert.deepEqual(gateRecords, expectedGateIds)
  assert.deepEqual(laterMutations, {
    business: 0,
    verify: 0,
    contract: 0
  })
  assert.equal(executionController.signal.aborted, true)
  assert.equal(executionController.signal.reason, abortReason)
  assert.equal(stopInvocations.length, 1)
  assert.equal(portInvocations.length, 1)
  assert.equal(stopInvocations[0].signal, portInvocations[0].signal)
  assert.notEqual(stopInvocations[0].signal, executionController.signal)
  assert.equal(stopInvocations[0].abortedAtCall, false)
  assert.equal(portInvocations[0].abortedAtCall, false)
})

function createS15StandaloneRedisHarness({
  recoveryPath,
  runId,
  runToken,
  keys,
  initialEntries
}) {
  const binding = s11RedisBinding()
  const ownerKey = 'p0:e2e:owner'
  const ownerId = createHash('sha256').update(runToken).digest('hex')
  const receiptKey = 'p0:e2e:cleanup:' + ownerId
  const inventoryFingerprint = createHash('sha256')
    .update(JSON.stringify(keys))
    .digest('hex')
  const releaseOwnershipScript = [
    "local owner = redis.call('get', KEYS[1])",
    "local receipt = redis.call('get', KEYS[2])",
    "if owner == ARGV[1] then",
    "  if receipt and receipt ~= ARGV[2] then return 'MISMATCH' end",
    "  redis.call('del', KEYS[1])",
    "  redis.call('set', KEYS[2], ARGV[2])",
    "  return 'RELEASED'",
    'end',
    "if not owner and receipt == ARGV[2] then return 'ALREADY_RELEASED' end",
    "return 'MISMATCH'"
  ].join('\n')
  const state = {
    owner: null,
    receipt: null,
    values: new Map(),
    expiries: new Map(),
    restoreMutations: 0,
    releaseAttempts: 0,
    releaseMutations: 0,
    receiptRemovalMutations: 0
  }
  for (const { key, value, expiresAtMs } of initialEntries) {
    state.values.set(key, value)
    state.expiries.set(key, expiresAtMs)
  }
  const events = []
  const sessions = []
  const acquireArmedObservations = []
  const pendingObservations = []

  const createRedis = (label, { loseReleaseResponse = false } = {}) => {
    let createSessionCalls = 0
    return smokeContracts.createLoopbackRedisAdapter(
      async (args) => {
        throw new Error('P0_TEST_LEGACY_REDIS_REQUEST_USED:' + args[0])
      },
      () => binding,
      async (expectedBinding, { stage } = {}) => {
        events.push({ type: 'verify', label, stage })
        assert.deepEqual(expectedBinding, binding)
        return structuredClone(binding)
      },
      {
        createSession({ host, port }) {
          createSessionCalls += 1
          const sessionId = label + '-session-' + createSessionCalls
          events.push({ type: 'connect', label, sessionId, host, port })
          const session = createS11RedisFakeSession({
            id: sessionId,
            events,
            respond(args, { fail }) {
              if (args[0] === 'SET' && args[3] === 'NX') {
                assert.deepEqual(args, ['SET', ownerKey, runToken, 'NX'])
                acquireArmedObservations.push({
                  label,
                  manifest: JSON.parse(readFileSync(recoveryPath, 'utf8'))
                })
                if (state.owner !== null) return null
                state.owner = runToken
                return 'OK'
              }
              if (args[0] === 'GET') {
                assert.equal(args.length, 2)
                if (args[1] === ownerKey) return state.owner
                assert.equal(keys.includes(args[1]), true)
                return state.values.get(args[1]) ?? null
              }
              if (args[0] === 'PEXPIRETIME') {
                assert.deepEqual(args.length, 2)
                assert.equal(keys.includes(args[1]), true)
                return state.expiries.get(args[1]) ?? -1
              }
              if (args[0] === 'SET') {
                assert.equal(args.length, 3)
                assert.equal(keys.includes(args[1]), true)
                state.values.set(args[1], args[2])
                state.restoreMutations += 1
                return 'OK'
              }
              if (args[0] === 'PERSIST') {
                assert.equal(args.length, 2)
                state.expiries.set(args[1], null)
                return 1
              }
              if (args[0] === 'PEXPIREAT') {
                assert.equal(args.length, 3)
                state.expiries.set(args[1], Number(args[2]))
                return 1
              }
              if (args[0] === 'DEL') {
                assert.equal(args.length, 2)
                assert.equal(keys.includes(args[1]), true)
                state.values.delete(args[1])
                state.expiries.delete(args[1])
                state.restoreMutations += 1
                return 1
              }
              if (args[0] === 'EVAL' && args[1] === releaseOwnershipScript) {
                assert.deepEqual(args, [
                  'EVAL',
                  releaseOwnershipScript,
                  '2',
                  ownerKey,
                  receiptKey,
                  runToken,
                  ownerId
                ])
                state.releaseAttempts += 1
                let status = 'MISMATCH'
                if (state.owner === runToken) {
                  if (state.receipt === null || state.receipt === ownerId) {
                    state.owner = null
                    state.receipt = ownerId
                    state.releaseMutations += 1
                    status = 'RELEASED'
                  }
                } else if (state.owner === null && state.receipt === ownerId) {
                  status = 'ALREADY_RELEASED'
                }
                if (loseReleaseResponse && status === 'RELEASED') {
                  fail()
                  throw new Error('INJECTED_STANDALONE_RELEASE_RESPONSE_LOST')
                }
                return status
              }
              if (args[0] === 'EVAL'
                && args[1] === S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT) {
                assert.deepEqual(args, [
                  'EVAL',
                  S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
                  '1',
                  receiptKey,
                  ownerId
                ])
                pendingObservations.push({
                  label,
                  manifest: JSON.parse(readFileSync(recoveryPath, 'utf8'))
                })
                if (state.receipt === null) return 'ALREADY_ABSENT'
                if (state.receipt !== ownerId) return 'MISMATCH'
                state.receipt = null
                state.receiptRemovalMutations += 1
                return 'REMOVED'
              }
              throw new Error('P0_TEST_UNEXPECTED_REDIS_COMMAND:' + args[0])
            }
          })
          sessions.push({ label, sessionId, session })
          return session
        }
      }
    )
  }

  return {
    acquireArmedObservations,
    commandsFor(label) {
      return events.filter(({ type, sessionId = '' }) => (
        type === 'command' && sessionId.startsWith(label + '-session-')
      ))
    },
    createRedis,
    events,
    inventoryFingerprint,
    ownerId,
    ownerKey,
    pendingObservations,
    receiptKey,
    releaseOwnershipScript,
    runId,
    runToken,
    sessionsFor(label) {
      return sessions.filter((session) => session.label === label)
    },
    state,
    verificationStages(label) {
      return events
        .filter((event) => event.type === 'verify' && event.label === label)
        .map(({ stage }) => stage)
    }
  }
}

test('standalone canonical Redis recovers persisted snapshot after restart', async (t) => {
  assert.equal(
    typeof smokeContracts.recoverStandaloneCanonicalRedisOwnership,
    'function',
    'P0_STANDALONE_REDIS_RECOVERY_EXPORT_REQUIRED'
  )
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s15-standalone-restart-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runId = 'p0-review1-s15-standalone-restart-a1'
  const runToken = 'p0-review1-s15-standalone-restart-owner-token-a1'
  const recoveryPath = join(root, runId, 'control', 'canonical-redis.json')
  mkdirSync(dirname(recoveryPath), { recursive: true })
  const keys = ['quote:BTCUSDT', 'quote:ETHUSDT']
  const originalSnapshot = [
    {
      key: 'quote:BTCUSDT',
      exists: true,
      value: 'before-restart',
      expiresAtMs: 1900000000000
    },
    {
      key: 'quote:ETHUSDT',
      exists: false,
      value: null,
      expiresAtMs: null
    }
  ]
  const harness = createS15StandaloneRedisHarness({
    recoveryPath,
    runId,
    runToken,
    keys,
    initialEntries: [{
      key: 'quote:BTCUSDT',
      value: 'before-restart',
      expiresAtMs: 1900000000000
    }]
  })

  await smokeContracts.createStandaloneCanonicalRedisOwnership({
    redis: harness.createRedis('create'),
    runToken,
    keys,
    recoveryPath,
    runId
  })

  assert.equal(harness.acquireArmedObservations.length, 1)
  const armed = harness.acquireArmedObservations[0].manifest
  assert.deepEqual({
    schemaVersion: armed.schemaVersion,
    state: armed.state,
    runId: armed.runId,
    ownerId: armed.ownerId,
    ownerToken: armed.ownerToken,
    inventoryCount: armed.inventoryCount,
    inventoryFingerprint: armed.inventoryFingerprint,
    inventory: armed.inventory
  }, {
    schemaVersion: 1,
    state: 'ACQUIRE_ARMED',
    runId,
    ownerId: harness.ownerId,
    ownerToken: runToken,
    inventoryCount: keys.length,
    inventoryFingerprint: harness.inventoryFingerprint,
    inventory: keys
  })
  const snapshotBytes = readFileSync(recoveryPath, 'utf8')
  const snapshotReady = JSON.parse(snapshotBytes)
  assert.deepEqual({
    schemaVersion: snapshotReady.schemaVersion,
    state: snapshotReady.state,
    runId: snapshotReady.runId,
    ownerId: snapshotReady.ownerId,
    ownerToken: snapshotReady.ownerToken,
    inventoryCount: snapshotReady.inventoryCount,
    inventoryFingerprint: snapshotReady.inventoryFingerprint,
    inventory: snapshotReady.inventory,
    snapshot: snapshotReady.snapshot,
    touchedKeys: snapshotReady.touchedKeys
  }, {
    schemaVersion: 1,
    state: 'SNAPSHOT_READY',
    runId,
    ownerId: harness.ownerId,
    ownerToken: runToken,
    inventoryCount: keys.length,
    inventoryFingerprint: harness.inventoryFingerprint,
    inventory: keys,
    snapshot: originalSnapshot,
    touchedKeys: keys
  })

  harness.state.values.set('quote:BTCUSDT', 'mutated-after-snapshot')
  harness.state.expiries.set('quote:BTCUSDT', null)
  harness.state.values.set('quote:ETHUSDT', 'created-after-snapshot')
  harness.state.expiries.set('quote:ETHUSDT', null)
  harness.state.owner = 'p0-review1-s15-foreign-owner-token-a1'
  await assert.rejects(
    smokeContracts.recoverStandaloneCanonicalRedisOwnership({
      redis: harness.createRedis('foreign-owner'),
      recoveryPath,
      runId
    }),
    /P0_(?:STANDALONE_)?REDIS_OWNER_MISMATCH/
  )
  assert.equal(readFileSync(recoveryPath, 'utf8'), snapshotBytes)
  assert.equal(harness.state.receipt, null)
  assert.equal(
    harness.commandsFor('foreign-owner').some(({ args }) => (
      ['SET', 'DEL', 'PEXPIREAT', 'PERSIST'].includes(args[0])
    )),
    false
  )

  harness.state.owner = runToken
  await smokeContracts.recoverStandaloneCanonicalRedisOwnership({
    redis: harness.createRedis('restart'),
    recoveryPath,
    runId
  })

  assert.deepEqual(harness.verificationStages('restart'), [
    'PRE_CONNECT',
    'POST_CONNECT',
    'PRE_CONNECT',
    'POST_CONNECT'
  ])
  assert.equal(harness.sessionsFor('restart').length, 2)
  assert.deepEqual(
    harness.commandsFor('restart').map(({ sessionId, args }) => ({ sessionId, args })),
    [
      {
        sessionId: 'restart-session-1',
        args: ['GET', harness.ownerKey]
      },
      {
        sessionId: 'restart-session-1',
        args: ['SET', 'quote:BTCUSDT', 'before-restart']
      },
      {
        sessionId: 'restart-session-1',
        args: ['PEXPIREAT', 'quote:BTCUSDT', '1900000000000']
      },
      {
        sessionId: 'restart-session-1',
        args: ['DEL', 'quote:ETHUSDT']
      },
      {
        sessionId: 'restart-session-1',
        args: [
          'EVAL',
          harness.releaseOwnershipScript,
          '2',
          harness.ownerKey,
          harness.receiptKey,
          runToken,
          harness.ownerId
        ]
      },
      {
        sessionId: 'restart-session-2',
        args: [
          'EVAL',
          S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
          '1',
          harness.receiptKey,
          harness.ownerId
        ]
      }
    ]
  )
  assert.deepEqual(
    harness.sessionsFor('restart').map(({ session }) => session.state),
    ['CLOSED', 'CLOSED']
  )
  assert.deepEqual({
    owner: harness.state.owner,
    receipt: harness.state.receipt,
    values: Object.fromEntries(harness.state.values),
    expiries: Object.fromEntries(harness.state.expiries)
  }, {
    owner: null,
    receipt: null,
    values: { 'quote:BTCUSDT': 'before-restart' },
    expiries: { 'quote:BTCUSDT': 1900000000000 }
  })
  const pending = harness.pendingObservations.find(({ label }) => label === 'restart')?.manifest
  assert.equal(pending?.state, 'RELEASE_PENDING')
  assert.equal(pending?.receipt?.key, harness.receiptKey)
  assert.equal(pending?.receipt?.state, 'PENDING')
  assert.equal(pending?.receipt?.completedAt, null)
  assert.equal(Object.hasOwn(pending, 'ownerToken'), false)
  assert.equal(Object.hasOwn(pending, 'snapshot'), false)
  assert.equal(Object.hasOwn(pending, 'touchedKeys'), false)
  const pendingText = JSON.stringify(pending)
  assert.equal(pendingText.includes(runToken), false)
  assert.equal(pendingText.includes('before-restart'), false)

  const completedBytes = readFileSync(recoveryPath, 'utf8')
  const completed = JSON.parse(completedBytes)
  assert.equal(completed.schemaVersion, 1)
  assert.equal(completed.state, 'COMPLETED')
  assert.equal(completed.runId, runId)
  assert.equal(completed.ownerId, harness.ownerId)
  assert.equal(completed.receipt.key, harness.receiptKey)
  assert.equal(completed.receipt.state, 'COMPLETED')
  assert.equal(typeof completed.receipt.completedAt, 'string')
  assert.equal(completedBytes.includes(runToken), false)
  assert.equal(completedBytes.includes('before-restart'), false)

  await smokeContracts.recoverStandaloneCanonicalRedisOwnership({
    redis: harness.createRedis('completed-replay'),
    recoveryPath,
    runId
  })
  assert.equal(readFileSync(recoveryPath, 'utf8'), completedBytes)
  assert.equal(harness.sessionsFor('completed-replay').length, 0)
})

test('standalone canonical Redis completes release receipt after lost response', async (t) => {
  assert.equal(
    typeof smokeContracts.recoverStandaloneCanonicalRedisOwnership,
    'function',
    'P0_STANDALONE_REDIS_RECOVERY_EXPORT_REQUIRED'
  )
  const root = mkdtempSync(join(tmpdir(), 'p0-review1-s15-standalone-lost-response-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const runId = 'p0-review1-s15-standalone-lost-response-a1'
  const runToken = 'p0-review1-s15-standalone-lost-response-owner-token-a1'
  const recoveryPath = join(root, runId, 'control', 'canonical-redis.json')
  mkdirSync(dirname(recoveryPath), { recursive: true })
  const keys = ['quote:BTCUSDT']
  const harness = createS15StandaloneRedisHarness({
    recoveryPath,
    runId,
    runToken,
    keys,
    initialEntries: [{
      key: 'quote:BTCUSDT',
      value: 'before-lost-response',
      expiresAtMs: 1900000001000
    }]
  })

  await smokeContracts.createStandaloneCanonicalRedisOwnership({
    redis: harness.createRedis('create'),
    runToken,
    keys,
    recoveryPath,
    runId
  })
  const snapshotBytes = readFileSync(recoveryPath, 'utf8')
  assert.equal(JSON.parse(snapshotBytes).state, 'SNAPSHOT_READY')
  harness.state.values.set('quote:BTCUSDT', 'mutated-before-lost-response')
  harness.state.expiries.set('quote:BTCUSDT', null)

  let lostResponseError
  try {
    await smokeContracts.recoverStandaloneCanonicalRedisOwnership({
      redis: harness.createRedis('lost-response', { loseReleaseResponse: true }),
      recoveryPath,
      runId
    })
  } catch (error) {
    lostResponseError = error
  }
  const lostErrors = []
  const visited = new Set()
  const collectLostErrors = (error) => {
    if (!(error instanceof Error) || visited.has(error)) return
    visited.add(error)
    lostErrors.push(error)
    if (Array.isArray(error.errors)) {
      for (const nested of error.errors) collectLostErrors(nested)
    }
    collectLostErrors(error.cause)
  }
  collectLostErrors(lostResponseError)
  assert.equal(
    lostErrors.some(({ message }) => message === 'INJECTED_STANDALONE_RELEASE_RESPONSE_LOST'),
    true
  )
  assert.equal(readFileSync(recoveryPath, 'utf8'), snapshotBytes)
  assert.deepEqual({
    owner: harness.state.owner,
    receipt: harness.state.receipt,
    value: harness.state.values.get('quote:BTCUSDT'),
    expiresAtMs: harness.state.expiries.get('quote:BTCUSDT'),
    restoreMutations: harness.state.restoreMutations,
    releaseMutations: harness.state.releaseMutations
  }, {
    owner: null,
    receipt: harness.ownerId,
    value: 'before-lost-response',
    expiresAtMs: 1900000001000,
    restoreMutations: 1,
    releaseMutations: 1
  })
  assert.equal(harness.sessionsFor('lost-response').length, 1)
  assert.equal(harness.sessionsFor('lost-response')[0].session.state, 'FAILED')

  harness.state.receipt = 'f'.repeat(64)
  await assert.rejects(
    smokeContracts.recoverStandaloneCanonicalRedisOwnership({
      redis: harness.createRedis('foreign-receipt'),
      recoveryPath,
      runId
    })
  )
  assert.equal(readFileSync(recoveryPath, 'utf8'), snapshotBytes)
  assert.equal(harness.state.restoreMutations, 1)
  assert.equal(harness.state.releaseMutations, 1)
  assert.equal(harness.state.receipt, 'f'.repeat(64))

  harness.state.receipt = harness.ownerId
  await smokeContracts.recoverStandaloneCanonicalRedisOwnership({
    redis: harness.createRedis('retry'),
    recoveryPath,
    runId
  })

  assert.deepEqual(harness.verificationStages('retry'), [
    'PRE_CONNECT',
    'POST_CONNECT',
    'PRE_CONNECT',
    'POST_CONNECT'
  ])
  assert.equal(harness.sessionsFor('retry').length, 2)
  assert.deepEqual(
    harness.commandsFor('retry').map(({ sessionId, args }) => ({ sessionId, args })),
    [
      {
        sessionId: 'retry-session-1',
        args: ['GET', harness.ownerKey]
      },
      {
        sessionId: 'retry-session-1',
        args: [
          'EVAL',
          harness.releaseOwnershipScript,
          '2',
          harness.ownerKey,
          harness.receiptKey,
          runToken,
          harness.ownerId
        ]
      },
      {
        sessionId: 'retry-session-2',
        args: [
          'EVAL',
          S11_CLEANUP_RECEIPT_REMOVAL_SCRIPT,
          '1',
          harness.receiptKey,
          harness.ownerId
        ]
      }
    ]
  )
  assert.deepEqual(
    harness.sessionsFor('retry').map(({ session }) => session.state),
    ['CLOSED', 'CLOSED']
  )
  assert.deepEqual({
    owner: harness.state.owner,
    receipt: harness.state.receipt,
    restoreMutations: harness.state.restoreMutations,
    releaseMutations: harness.state.releaseMutations,
    receiptRemovalMutations: harness.state.receiptRemovalMutations
  }, {
    owner: null,
    receipt: null,
    restoreMutations: 1,
    releaseMutations: 1,
    receiptRemovalMutations: 1
  })
  const retryPending = harness.pendingObservations
    .find(({ label }) => label === 'retry')?.manifest
  assert.equal(retryPending?.state, 'RELEASE_PENDING')
  assert.equal(retryPending?.receipt?.key, harness.receiptKey)
  assert.equal(retryPending?.receipt?.state, 'PENDING')
  assert.equal(Object.hasOwn(retryPending, 'ownerToken'), false)
  assert.equal(Object.hasOwn(retryPending, 'snapshot'), false)
  assert.equal(JSON.stringify(retryPending).includes(runToken), false)
  assert.equal(JSON.stringify(retryPending).includes('before-lost-response'), false)

  const completed = JSON.parse(readFileSync(recoveryPath, 'utf8'))
  assert.equal(completed.state, 'COMPLETED')
  assert.equal(completed.runId, runId)
  assert.equal(completed.ownerId, harness.ownerId)
  assert.equal(completed.receipt.key, harness.receiptKey)
  assert.equal(completed.receipt.state, 'COMPLETED')
  assert.equal(typeof completed.receipt.completedAt, 'string')
})
