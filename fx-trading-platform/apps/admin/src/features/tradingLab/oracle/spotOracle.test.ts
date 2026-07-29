import assert from 'node:assert/strict'
import test from 'node:test'

import type {
  TimelineAction,
  TradingLabScenario,
} from '../model/types.ts'
import { calculateScenario } from './runOracle.ts'
import type {
  LocalActionSnapshot,
  MarketTick,
  OracleMarketInput,
} from './types.ts'

const SYMBOL = 'XBT-USDT-LAB'
const VIRTUAL_START = '2026-07-25T00:00:00.000Z'

function spotScenario(): TradingLabScenario {
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: '0.001',
    liquidationFeeRate: '0.003',
    slippageRate: '0',
    maxFillQuantityPerTick: '1000',
  }
  return {
    id: 'spot-exact-scenario',
    name: 'Spot exact lifecycle',
    description: '',
    negativeMode: false,
    seed: 'spot-fixed-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments: [{
        symbol: SYMBOL,
        productType: 'CRYPTO_SPOT',
        baseAsset: 'XBT',
        quoteAsset: 'USDT',
        tickSize: '0.01',
        stepSize: '0.1',
        pricePrecision: 2,
        quantityPrecision: 1,
        minQty: '0.1',
        maxQty: '1000',
        minNotional: '1',
        maxNotional: '1000000',
        initialMarginRate: '1',
        maintenanceMarginRate: '0',
        liquidationFeeRate: '0.003',
        fixedFundingRate: '0',
        fixedFundingIntervalMinutes: 480,
        markPriceSource: 'quote_mid',
        contractSize: '1',
        maxLeverage: 1,
        defaultLeverage: 1,
        marginAsset: 'USDT',
        settlementAsset: 'USDT',
        riskTier: 'TIER_1',
      }],
    },
    configSnapshotHash: 'a'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: VIRTUAL_START,
      realistic: false,
      instruments: [{
        mode: 'SIMPLE',
        productType: 'CRYPTO_SPOT',
        symbol: SYMBOL,
        seed: 'spot-market-path',
        last: {
          start: '100',
          segments: [{
            target: '100',
            durationSeconds: 5,
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
    initialBalances: {
      USDT: '1000',
      XBT: '0',
    },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: SYMBOL,
      productType: 'CRYPTO_SPOT',
    }],
    timeline: [
      marketOrder('action-1', 1, 'BUY', '100.1', 'QUOTE'),
      marketOrder('action-2', 2, 'BUY', '120.12', 'QUOTE'),
      marketOrder('action-3', 3, 'SELL', '0.5', 'BASE'),
      marketOrder('action-4', 4, 'BUY', '45.045', 'QUOTE'),
      marketOrder('action-5', 5, 'SELL', '2', 'BASE'),
    ],
  }
}

function marketOrder(
  id: string,
  sequence: number,
  side: 'BUY' | 'SELL',
  quantity: string,
  quantityUnit: 'BASE' | 'QUOTE',
): TimelineAction {
  return {
    id,
    sequence,
    type: 'PLACE_ORDER',
    symbol: SYMBOL,
    productType: 'CRYPTO_SPOT',
    trigger: {
      type: 'VIRTUAL_TIME',
      atSecond: sequence,
    },
    parameters: {
      side,
      orderType: 'MARKET',
      quantity,
      quantityUnit,
    },
  }
}

function spotMarket(): OracleMarketInput {
  return {
    virtualStart: VIRTUAL_START,
    ticks: [
      spotTick(1, '99', '100', '99.5'),
      spotTick(2, '119', '120', '119.5'),
      spotTick(3, '130', '131', '130.5'),
      spotTick(4, '89', '90', '89.5'),
      spotTick(5, '120', '121', '120.5'),
    ],
  }
}

function spotTick(
  sequence: number,
  bid: string,
  ask: string,
  last: string,
): MarketTick {
  return {
    sequence,
    virtualTime: `2026-07-25T00:00:0${sequence}.000Z`,
    instruments: [{
      productType: 'CRYPTO_SPOT',
      symbol: SYMBOL,
      bid,
      ask,
      last,
    }],
    fundingRates: [],
  }
}

function spotPosition(snapshot: LocalActionSnapshot) {
  const position = snapshot.spotPositions.find(
    (candidate) => candidate.symbol === SYMBOL,
  )
  assert.ok(position, `missing Spot position for ${SYMBOL}`)
  return position
}

function wallet(snapshot: LocalActionSnapshot, asset: string) {
  const balance = snapshot.wallets.find(
    (candidate) => candidate.asset === asset,
  )
  assert.ok(balance, `missing wallet for ${asset}`)
  return balance
}

function positionSummary(snapshot: LocalActionSnapshot) {
  const position = spotPosition(snapshot)
  return {
    quantity: position.quantity,
    averageCost: position.averageCost,
    grossQuoteCost: position.grossQuoteCost,
    feeCostUsdt: position.feeCostUsdt,
    netInvestedUsdt: position.netInvestedUsdt,
    breakEvenPrice: position.breakEvenPrice,
    realizedGrossPnl: position.realizedGrossPnl,
    realizedNetPnl: position.realizedNetPnl,
  }
}

function walletSummary(snapshot: LocalActionSnapshot) {
  const usdt = wallet(snapshot, 'USDT')
  const xbt = wallet(snapshot, 'XBT')
  return {
    USDT: {
      available: usdt.available,
      locked: usdt.locked,
      total: usdt.total,
    },
    XBT: {
      available: xbt.available,
      locked: xbt.locked,
      total: xbt.total,
    },
  }
}

function assertDeepFrozen(value: unknown): void {
  if (value === null || typeof value !== 'object') {
    return
  }

  assert.equal(Object.isFrozen(value), true)
  for (const child of Object.values(value)) {
    assertDeepFrozen(child)
  }
}

test('calculates Spot buy-buy-partial-sell-buy-final-sell with explicit assets and exact strings', () => {
  const outcome = calculateScenario({
    scenario: spotScenario(),
    market: spotMarket(),
  })

  if (outcome.status === 'BLOCKED') {
    assert.fail(JSON.stringify(outcome.issues))
  }
  assert.equal(outcome.status, 'CALCULATED')

  assert.deepEqual(outcome.runnerIssues, [])
  assert.equal(outcome.result.snapshots.length, 5)
  assert.deepEqual(
    outcome.result.snapshots.map(positionSummary),
    [
      {
        quantity: '1',
        averageCost: '100',
        grossQuoteCost: '100',
        feeCostUsdt: '0.1',
        netInvestedUsdt: '100.1',
        breakEvenPrice: '100.1',
        realizedGrossPnl: '0',
        realizedNetPnl: '0',
      },
      {
        quantity: '2',
        averageCost: '110',
        grossQuoteCost: '220',
        feeCostUsdt: '0.22',
        netInvestedUsdt: '220.22',
        breakEvenPrice: '110.11',
        realizedGrossPnl: '0',
        realizedNetPnl: '0',
      },
      {
        quantity: '1.5',
        averageCost: '110',
        grossQuoteCost: '165',
        feeCostUsdt: '0.285',
        netInvestedUsdt: '155.285',
        breakEvenPrice: '110.19',
        realizedGrossPnl: '10',
        realizedNetPnl: '9.935',
      },
      {
        quantity: '2',
        averageCost: '105',
        grossQuoteCost: '210',
        feeCostUsdt: '0.33',
        netInvestedUsdt: '200.33',
        breakEvenPrice: '105.165',
        realizedGrossPnl: '10',
        realizedNetPnl: '9.935',
      },
      {
        quantity: '0',
        averageCost: null,
        grossQuoteCost: '0',
        feeCostUsdt: '0.57',
        netInvestedUsdt: '-39.43',
        breakEvenPrice: null,
        realizedGrossPnl: '40',
        realizedNetPnl: '39.695',
      },
    ],
  )

  assert.deepEqual(
    outcome.result.snapshots.map(walletSummary),
    [
      {
        USDT: { available: '899.9', locked: '0', total: '899.9' },
        XBT: { available: '1', locked: '0', total: '1' },
      },
      {
        USDT: { available: '779.78', locked: '0', total: '779.78' },
        XBT: { available: '2', locked: '0', total: '2' },
      },
      {
        USDT: { available: '844.715', locked: '0', total: '844.715' },
        XBT: { available: '1.5', locked: '0', total: '1.5' },
      },
      {
        USDT: { available: '799.67', locked: '0', total: '799.67' },
        XBT: { available: '2', locked: '0', total: '2' },
      },
      {
        USDT: { available: '1039.43', locked: '0', total: '1039.43' },
        XBT: { available: '0', locked: '0', total: '0' },
      },
    ],
  )
})

test('applies non-zero slippage and floors a QUOTE budget to the exact Spot step', () => {
  const scenario = spotScenario()
  scenario.executionPolicy.slippageRate = '0.01'
  scenario.timeline = [
    marketOrder('slippage-buy', 1, 'BUY', '100', 'QUOTE'),
  ]

  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [spotTick(1, '99', '100', '99.5')],
    },
  })

  if (outcome.status === 'BLOCKED') {
    assert.fail(JSON.stringify(outcome.issues))
  }

  const snapshot = outcome.result.snapshots[0]
  assert.deepEqual(positionSummary(snapshot), {
    quantity: '0.9',
    averageCost: '101',
    grossQuoteCost: '90.9',
    feeCostUsdt: '0.0909',
    netInvestedUsdt: '90.9909',
    breakEvenPrice: '101.101',
    realizedGrossPnl: '0',
    realizedNetPnl: '0',
  })
  assert.deepEqual(walletSummary(snapshot), {
    USDT: {
      available: '909.0091',
      locked: '0',
      total: '909.0091',
    },
    XBT: {
      available: '0.9',
      locked: '0',
      total: '0.9',
    },
  })
  assert.deepEqual(snapshot.orders[0], {
    id: 'order:slippage-buy',
    actionId: 'slippage-buy',
    symbol: SYMBOL,
    productType: 'CRYPTO_SPOT',
    side: 'BUY',
    status: 'FILLED',
    quantity: '0.9',
    price: '101',
    feeUsdt: '0.0909',
  })
  assert.equal(snapshot.trades[0]?.notionalUsdt, '90.9')
})

test('treats null Spot notional bounds as absent authority', () => {
  const scenario = spotScenario()
  const instrument = scenario.configSnapshot.instruments[0]
  instrument.minNotional = null
  instrument.maxNotional = null

  const outcome = calculateScenario({
    scenario,
    market: spotMarket(),
  })

  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
})

test('treats a null Spot max-fill cap as unbounded while retaining maxQty', () => {
  const scenario = spotScenario()
  scenario.executionPolicy.maxFillQuantityPerTick = null
  scenario.configSnapshot.executionPolicy.maxFillQuantityPerTick = null
  scenario.timeline = [
    marketOrder('uncapped-buy', 1, 'BUY', '200.2', 'QUOTE'),
  ]

  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [spotTick(1, '99', '100', '99.5')],
    },
  })

  assert.equal(
    outcome.status,
    'CALCULATED',
    outcome.status === 'BLOCKED'
      ? JSON.stringify(outcome.issues)
      : undefined,
  )
  if (outcome.status !== 'CALCULATED') {
    return
  }
  assert.equal(outcome.result.snapshots[0].orders[0]?.quantity, '2')
})

test('blocks insufficient USDT and oversell without returning partial snapshots', () => {
  const insufficientUsdt = spotScenario()
  insufficientUsdt.initialBalances.USDT = '100'

  const oversell = spotScenario()
  oversell.timeline = [
    marketOrder('oversell', 1, 'SELL', '0.5', 'BASE'),
  ]

  for (const [name, scenario] of [
    ['insufficient USDT', insufficientUsdt],
    ['oversell', oversell],
  ] as const) {
    const outcome = calculateScenario({
      scenario,
      market: spotMarket(),
    })

    assert.equal(outcome.status, 'BLOCKED', name)
    if (outcome.status !== 'BLOCKED') {
      assert.fail(`${name} unexpectedly calculated`)
    }
    assert.equal('result' in outcome, false, name)
    assert.equal('snapshots' in outcome, false, name)
    assert.equal(outcome.issues.length > 0, true, name)
    assert.match(
      outcome.issues.map((issue: any) => issue.code).join(','),
      /INSUFFICIENT|OVERSELL/u,
      name,
    )
  }
})

test('does not mutate the scenario, ticks, or any previous detached snapshot', () => {
  const scenario = spotScenario()
  const market = spotMarket()
  const scenarioBefore = structuredClone(scenario)
  const marketBefore = structuredClone(market)

  const outcome = calculateScenario({ scenario, market })

  assert.deepEqual(scenario, scenarioBefore)
  assert.deepEqual(market, marketBefore)
  if (outcome.status === 'BLOCKED') {
    assert.fail(JSON.stringify(outcome.issues))
  }
  assert.equal(outcome.status, 'CALCULATED')

  const [first, second, third, fourth, fifth] = outcome.result.snapshots
  assert.notStrictEqual(first, second)
  assert.notStrictEqual(first.spotPositions, second.spotPositions)
  assert.notStrictEqual(first.wallets, fifth.wallets)
  assert.deepEqual(positionSummary(first), {
    quantity: '1',
    averageCost: '100',
    grossQuoteCost: '100',
    feeCostUsdt: '0.1',
    netInvestedUsdt: '100.1',
    breakEvenPrice: '100.1',
    realizedGrossPnl: '0',
    realizedNetPnl: '0',
  })
  assert.deepEqual(
    [first, second, third, fourth, fifth].map((snapshot) => snapshot.actionId),
    ['action-1', 'action-2', 'action-3', 'action-4', 'action-5'],
  )

  for (const snapshot of outcome.result.snapshots) {
    assertDeepFrozen(snapshot)
  }
})
