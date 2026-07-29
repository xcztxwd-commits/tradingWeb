import assert from 'node:assert/strict'
import test from 'node:test'

import type {
  TimelineAction,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from '../model/types.ts'
import { calculateScenario } from './runOracle.ts'
import type {
  FundingRateTick,
  LocalActionSnapshot,
  MarketTick,
  OracleCalculationOutcome,
  PerpetualInstrumentTick,
} from './types.ts'

const VIRTUAL_START = '2026-01-01T00:00:00.000Z'

type InstrumentFixture = TradingLabInstrumentConfig & {
  productType: 'LINEAR_PERP'
}
type CalculatedOutcome = Extract<
  OracleCalculationOutcome,
  { status: 'CALCULATED' }
>

function instrument(
  symbol = 'XBT-USDT-LAB',
  baseAsset = 'XBT',
): InstrumentFixture {
  return {
    symbol,
    productType: 'LINEAR_PERP' as const,
    baseAsset,
    quoteAsset: 'USDT',
    tickSize: '0.01',
    stepSize: '0.001',
    pricePrecision: 2,
    quantityPrecision: 3,
    minQty: '0.001',
    maxQty: '100',
    minNotional: '1',
    maxNotional: '1000000',
    initialMarginRate: '0.1',
    maintenanceMarginRate: '0.05',
    liquidationFeeRate: '0.002',
    fixedFundingRate: '0.001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: 'provider_mark',
    contractSize: '1',
    maxLeverage: 20,
    defaultLeverage: 10,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'TIER_1',
  }
}

function scenario(input: {
  instruments?: InstrumentFixture[]
  initialBalances?: Record<string, string>
  positionMode?: 'ONE_WAY' | 'HEDGE'
  marginMode?: 'CROSS' | 'ISOLATED'
  leverage?: number
  maxFillQuantityPerTick?: string | null
  timeline: TimelineAction[]
}): TradingLabScenario {
  const instruments = input.instruments ?? [instrument()]
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: '0.001',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: input.maxFillQuantityPerTick === undefined
      ? '100'
      : input.maxFillQuantityPerTick,
  }
  return {
    id: 'perpetual-oracle-fixture',
    name: 'Perpetual Oracle fixture',
    description: '',
    negativeMode: false,
    seed: 'perpetual-oracle-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments,
    },
    configSnapshotHash: 'b'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: VIRTUAL_START,
      realistic: false,
      instruments: instruments.map((selected) => ({
        mode: 'SIMPLE' as const,
        productType: 'LINEAR_PERP' as const,
        symbol: selected.symbol,
        seed: `perpetual-path-${selected.symbol}`,
        last: {
          start: '100',
          segments: [{
            target: '100',
            durationSeconds: 60,
            offsetRangeSteps: 0,
            volatilitySteps: 0,
            maxStepPerSecond: 1,
          }],
        },
        spreadSteps: 2,
        indexOffsetSteps: 0,
        basisSteps: 0,
        fundingRate: selected.fixedFundingRate,
      })),
    },
    initialBalances: input.initialBalances ?? { USDT: '1000' },
    defaults: {
      positionMode: input.positionMode ?? 'ONE_WAY',
      marginMode: input.marginMode ?? 'CROSS',
      leverage: input.leverage ?? 10,
    },
    symbols: instruments.map(({ symbol, productType }) => ({
      symbol,
      productType,
    })),
    timeline: input.timeline,
  }
}

function virtualTime(sequence: number): string {
  return `2026-01-01T00:00:${String(sequence).padStart(2, '0')}.000Z`
}

function quote(
  selected: InstrumentFixture,
  price: string,
  mark = price,
): PerpetualInstrumentTick {
  return {
    productType: 'LINEAR_PERP' as const,
    symbol: selected.symbol,
    bid: price,
    ask: price,
    last: price,
    mark,
    index: mark,
  }
}

function tick(
  sequence: number,
  instruments: readonly PerpetualInstrumentTick[],
  fundingRates: readonly FundingRateTick[] = [],
): MarketTick {
  return {
    sequence,
    virtualTime: virtualTime(sequence),
    instruments,
    fundingRates,
  }
}

function marketOrder(input: {
  id: string
  sequence: number
  symbol?: string
  side: 'BUY' | 'SELL'
  quantity: string
  quantityUnit?: 'BASE' | 'CONTRACTS'
  positionSide?: 'BOTH' | 'LONG' | 'SHORT'
  marginMode?: 'CROSS' | 'ISOLATED'
  leverage?: number
  reduceOnly?: boolean
}): TimelineAction {
  return {
    id: input.id,
    sequence: input.sequence,
    type: 'PLACE_ORDER',
    symbol: input.symbol ?? 'XBT-USDT-LAB',
    productType: 'LINEAR_PERP',
    trigger: {
      type: 'VIRTUAL_TIME',
      atSecond: input.sequence,
    },
    parameters: {
      side: input.side,
      orderType: 'MARKET',
      quantity: input.quantity,
      quantityUnit: input.quantityUnit ?? 'BASE',
      positionSide: input.positionSide ?? 'BOTH',
      ...(input.marginMode === undefined
        ? {}
        : { marginMode: input.marginMode }),
      ...(input.leverage === undefined
        ? {}
        : { leverage: input.leverage }),
      ...(input.reduceOnly === undefined
        ? {}
        : { reduceOnly: input.reduceOnly }),
    },
  }
}

function localAction(input: {
  id: string
  sequence: number
  symbol?: string
  type: 'APPLY_FUNDING'
  parameters?: Record<string, unknown>
}): TimelineAction {
  return {
    id: input.id,
    sequence: input.sequence,
    type: input.type,
    symbol: input.symbol ?? 'XBT-USDT-LAB',
    productType: 'LINEAR_PERP',
    trigger: {
      type: 'VIRTUAL_TIME',
      atSecond: input.sequence,
    },
    parameters: input.parameters ?? {},
  }
}

function calculate(input: {
  scenario: TradingLabScenario
  ticks: readonly MarketTick[]
}): CalculatedOutcome {
  const outcome = calculateScenario({
    scenario: input.scenario,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: input.ticks,
    },
  })
  if (outcome.status === 'BLOCKED') {
    assert.fail(outcome.issues.map((issue) => issue.code).join(', '))
  }
  assert.equal(outcome.status, 'CALCULATED')
  return outcome
}

function perpetualPosition(
  snapshot: LocalActionSnapshot,
  symbol = 'XBT-USDT-LAB',
  positionSide: 'BOTH' | 'LONG' | 'SHORT' = 'BOTH',
) {
  const found = snapshot.perpetualPositions.find((candidate) =>
    candidate.symbol === symbol
    && candidate.positionSide === positionSide)
  assert.ok(found, `${symbol}/${positionSide} position is required`)
  return found
}

function wallet(
  snapshot: LocalActionSnapshot,
  asset = 'USDT',
) {
  const found = snapshot.wallets.find((candidate) => candidate.asset === asset)
  assert.ok(found, `${asset} wallet is required`)
  return found
}

test('keeps entry price across a partial close and reverses ONE_WAY at the same fill', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'buy-at-100',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
      }),
      marketOrder({
        id: 'buy-at-200',
        sequence: 2,
        side: 'BUY',
        quantity: '1',
      }),
      marketOrder({
        id: 'partial-close-at-250',
        sequence: 3,
        side: 'SELL',
        quantity: '0.5',
      }),
      marketOrder({
        id: 'add-at-50',
        sequence: 4,
        side: 'BUY',
        quantity: '0.5',
      }),
      marketOrder({
        id: 'reverse-at-75',
        sequence: 5,
        side: 'SELL',
        quantity: '3',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '200')]),
      tick(3, [quote(selected, '250')]),
      tick(4, [quote(selected, '50')]),
      tick(5, [quote(selected, '75')]),
    ],
  })

  assert.equal(outcome.result.snapshots.length, 5)
  const afterSecondBuy = perpetualPosition(outcome.result.snapshots[1])
  assert.deepEqual(
    {
      direction: afterSecondBuy.direction,
      quantity: afterSecondBuy.quantity,
      entryPrice: afterSecondBuy.entryPrice,
    },
    {
      direction: 'LONG',
      quantity: '2',
      entryPrice: '150',
    },
  )

  const afterPartialClose = perpetualPosition(outcome.result.snapshots[2])
  assert.deepEqual(
    {
      direction: afterPartialClose.direction,
      quantity: afterPartialClose.quantity,
      entryPrice: afterPartialClose.entryPrice,
      realizedGrossPnl: afterPartialClose.realizedGrossPnl,
      tradingFeeUsdt: afterPartialClose.tradingFeeUsdt,
    },
    {
      direction: 'LONG',
      quantity: '1.5',
      entryPrice: '150',
      realizedGrossPnl: '50',
      tradingFeeUsdt: '0.425',
    },
  )

  const afterAdd = perpetualPosition(outcome.result.snapshots[3])
  assert.deepEqual(
    {
      direction: afterAdd.direction,
      quantity: afterAdd.quantity,
      entryPrice: afterAdd.entryPrice,
      realizedGrossPnl: afterAdd.realizedGrossPnl,
    },
    {
      direction: 'LONG',
      quantity: '2',
      entryPrice: '125',
      realizedGrossPnl: '50',
    },
  )

  const finalPosition = perpetualPosition(outcome.result.snapshots[4])
  assert.deepEqual(
    {
      status: finalPosition.status,
      positionSide: finalPosition.positionSide,
      direction: finalPosition.direction,
      marginMode: finalPosition.marginMode,
      quantity: finalPosition.quantity,
      entryPrice: finalPosition.entryPrice,
      realizedGrossPnl: finalPosition.realizedGrossPnl,
      tradingFeeUsdt: finalPosition.tradingFeeUsdt,
      realizedNetPnl: finalPosition.realizedNetPnl,
    },
    {
      status: 'OPEN',
      positionSide: 'BOTH',
      direction: 'SHORT',
      marginMode: 'CROSS',
      quantity: '1',
      entryPrice: '75',
      realizedGrossPnl: '-50',
      tradingFeeUsdt: '0.675',
      realizedNetPnl: '-50.675',
    },
  )
})

test('keeps entry price unchanged through repeated partial closes', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'open-three',
        sequence: 1,
        side: 'BUY',
        quantity: '3',
      }),
      marketOrder({
        id: 'partial-one',
        sequence: 2,
        side: 'SELL',
        quantity: '0.5',
      }),
      marketOrder({
        id: 'partial-two',
        sequence: 3,
        side: 'SELL',
        quantity: '0.5',
      }),
      marketOrder({
        id: 'partial-three',
        sequence: 4,
        side: 'SELL',
        quantity: '1',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '110')]),
      tick(3, [quote(selected, '120')]),
      tick(4, [quote(selected, '130')]),
    ],
  })

  assert.deepEqual(
    outcome.result.snapshots.slice(1).map((snapshot) => {
      const position = perpetualPosition(snapshot)
      return {
        quantity: position.quantity,
        entryPrice: position.entryPrice,
        realizedGrossPnl: position.realizedGrossPnl,
      }
    }),
    [
      { quantity: '2.5', entryPrice: '100', realizedGrossPnl: '5' },
      { quantity: '2', entryPrice: '100', realizedGrossPnl: '15' },
      { quantity: '1', entryPrice: '100', realizedGrossPnl: '45' },
    ],
  )

  const finalPosition = perpetualPosition(outcome.result.snapshots[3])
  assert.equal(finalPosition.tradingFeeUsdt, '0.545')
  assert.equal(finalPosition.realizedNetPnl, '44.455')
})

test('keeps HEDGE LONG and SHORT slots independent and applies opposite funding signs', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    positionMode: 'HEDGE',
    timeline: [
      marketOrder({
        id: 'open-long',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
        positionSide: 'LONG',
      }),
      marketOrder({
        id: 'open-short',
        sequence: 2,
        side: 'SELL',
        quantity: '1',
        positionSide: 'SHORT',
      }),
      localAction({
        id: 'fund-both-slots',
        sequence: 3,
        type: 'APPLY_FUNDING',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '100')]),
      tick(
        3,
        [quote(selected, '100')],
        [{ symbol: selected.symbol, rate: '0.001' }],
      ),
    ],
  })

  const finalSnapshot = outcome.result.snapshots[2]
  const long = perpetualPosition(finalSnapshot, selected.symbol, 'LONG')
  const short = perpetualPosition(finalSnapshot, selected.symbol, 'SHORT')
  assert.deepEqual(
    {
      longDirection: long.direction,
      longQuantity: long.quantity,
      longEntry: long.entryPrice,
      longFunding: long.fundingPnlUsdt,
      shortDirection: short.direction,
      shortQuantity: short.quantity,
      shortEntry: short.entryPrice,
      shortFunding: short.fundingPnlUsdt,
    },
    {
      longDirection: 'LONG',
      longQuantity: '1',
      longEntry: '100',
      longFunding: '-0.1',
      shortDirection: 'SHORT',
      shortQuantity: '1',
      shortEntry: '100',
      shortFunding: '0.1',
    },
  )
  assert.notStrictEqual(long, short)
  assert.equal(
    outcome.runnerIssues.some((issue) =>
      issue.code === 'ACTION_REQUIRES_RUNNER_SUPPORT'),
    true,
  )
})

test('keeps CROSS USDT shared while ISOLATED principal remains position-local', () => {
  const crossInstrument = instrument('XBT-ALPHA-LAB', 'XBT')
  const isolatedInstrument = instrument('ETHER-OMEGA-LAB', 'ETHER')
  const input = scenario({
    instruments: [crossInstrument, isolatedInstrument],
    timeline: [
      marketOrder({
        id: 'open-cross',
        sequence: 1,
        symbol: crossInstrument.symbol,
        side: 'BUY',
        quantity: '1',
        marginMode: 'CROSS',
        leverage: 10,
      }),
      marketOrder({
        id: 'open-isolated',
        sequence: 2,
        symbol: isolatedInstrument.symbol,
        side: 'BUY',
        quantity: '2',
        marginMode: 'ISOLATED',
        leverage: 5,
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(crossInstrument, '100'),
        quote(isolatedInstrument, '50'),
      ]),
      tick(2, [
        quote(crossInstrument, '100'),
        quote(isolatedInstrument, '50'),
      ]),
    ],
  })

  const finalSnapshot = outcome.result.snapshots[1]
  const cross = perpetualPosition(
    finalSnapshot,
    crossInstrument.symbol,
    'BOTH',
  )
  const isolated = perpetualPosition(
    finalSnapshot,
    isolatedInstrument.symbol,
    'BOTH',
  )
  assert.deepEqual(
    {
      crossMarginMode: cross.marginMode,
      crossInitialMargin: cross.initialMargin,
      crossIsolatedMargin: cross.isolatedMargin,
      isolatedMarginMode: isolated.marginMode,
      isolatedInitialMargin: isolated.initialMargin,
      isolatedPrincipal: isolated.isolatedMargin,
    },
    {
      crossMarginMode: 'CROSS',
      crossInitialMargin: '10',
      crossIsolatedMargin: '0',
      isolatedMarginMode: 'ISOLATED',
      isolatedInitialMargin: '20',
      isolatedPrincipal: '20',
    },
  )
  assert.deepEqual(
    wallet(finalSnapshot),
    {
      asset: 'USDT',
      available: '969.8',
      locked: '30',
      total: '999.8',
    },
  )
})

test('normalizes CONTRACTS to canonical BASE exactly once before risk math', () => {
  const selected = {
    ...instrument(),
    contractSize: '0.5',
  }
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'open-two-contracts',
        sequence: 1,
        side: 'BUY',
        quantity: '2',
        quantityUnit: 'CONTRACTS',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [tick(1, [quote(selected, '100')])],
  })
  const finalSnapshot = outcome.result.snapshots[0]
  const position = perpetualPosition(finalSnapshot)

  assert.deepEqual(
    {
      quantity: position.quantity,
      initialMargin: position.initialMargin,
      maintenanceMargin: position.maintenanceMargin,
      tradingFeeUsdt: position.tradingFeeUsdt,
      wallet: wallet(finalSnapshot),
      riskMaintenanceMargin: finalSnapshot.risk.maintenanceMarginUsdt,
    },
    {
      quantity: '1',
      initialMargin: '10',
      maintenanceMargin: '5',
      tradingFeeUsdt: '0.1',
      wallet: {
        asset: 'USDT',
        available: '989.9',
        locked: '10',
        total: '999.9',
      },
      riskMaintenanceMargin: '5',
    },
  )
})

test('fails closed when reduceOnly is true instead of opening from flat', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'unsupported-reduce-only',
        sequence: 1,
        side: 'SELL',
        quantity: '1',
        reduceOnly: true,
      }),
    ],
  })

  const outcome = calculateScenario({
    scenario: input,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [tick(1, [quote(selected, '100')])],
    },
  })

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status !== 'BLOCKED') {
    assert.fail('reduceOnly=true must fail closed')
  }
  assert.deepEqual(
    outcome.issues.map((issue) => issue.code),
    ['ORACLE_REDUCE_ONLY_UNSUPPORTED'],
  )
})

test('does not let an empty HEDGE slot bypass instrument-wide settings', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    positionMode: 'HEDGE',
    timeline: [
      marketOrder({
        id: 'open-long-cross',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
        positionSide: 'LONG',
        marginMode: 'CROSS',
        leverage: 10,
      }),
      marketOrder({
        id: 'open-short-with-conflicting-settings',
        sequence: 2,
        side: 'SELL',
        quantity: '1',
        positionSide: 'SHORT',
        marginMode: 'ISOLATED',
        leverage: 20,
      }),
    ],
  })

  const outcome = calculateScenario({
    scenario: input,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(2, [quote(selected, '100')]),
      ],
    },
  })

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status !== 'BLOCKED') {
    assert.fail('open HEDGE slot must lock instrument settings')
  }
  assert.deepEqual(
    outcome.issues.map((issue) => issue.code),
    ['ORACLE_POSITION_SETTINGS_MISMATCH'],
  )
})

test('projects realized perpetual PnL and fee into the trade cash ledger', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'open-at-100',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
      }),
      marketOrder({
        id: 'close-at-110',
        sequence: 2,
        side: 'SELL',
        quantity: '1',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '110')]),
    ],
  })

  assert.deepEqual(
    outcome.result.snapshots[1].ledgerProjection,
    [
      {
        actionId: 'open-at-100',
        asset: 'USDT',
        type: 'PERPETUAL_TRADE_CASH',
        amount: '-0.1',
        balanceAfter: '999.9',
      },
      {
        actionId: 'close-at-110',
        asset: 'USDT',
        type: 'PERPETUAL_TRADE_CASH',
        amount: '9.89',
        balanceAfter: '1009.79',
      },
    ],
  )
})

test('caps a deep ISOLATED full close at the released margin slice and records shortfall', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialBalances: { USDT: '100' },
    marginMode: 'ISOLATED',
    timeline: [
      marketOrder({
        id: 'open-deep-isolated',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
      marketOrder({
        id: 'close-deep-isolated',
        sequence: 2,
        side: 'SELL',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '1')]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)

  assert.deepEqual(
    {
      status: position.status,
      realizedGrossPnl: position.realizedGrossPnl,
      tradingFeeUsdt: position.tradingFeeUsdt,
      liquidationFeeUsdt: position.liquidationFeeUsdt,
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-2),
    },
    {
      status: 'CLOSED',
      realizedGrossPnl: '-99',
      tradingFeeUsdt: '0.101',
      liquidationFeeUsdt: '0',
      wallet: {
        asset: 'USDT',
        available: '89.9',
        locked: '0',
        total: '89.9',
      },
      settlement: [
        {
          actionId: 'close-deep-isolated',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-10',
          balanceAfter: '89.9',
        },
        {
          actionId: 'close-deep-isolated',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '89.001',
          balanceAfter: '89.9',
        },
      ],
    },
  )
})

test('applies a deep ISOLATED partial reduce before unified risk liquidates the remainder', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialBalances: { USDT: '100' },
    marginMode: 'ISOLATED',
    timeline: [
      marketOrder({
        id: 'open-before-deep-reduce',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
      marketOrder({
        id: 'reduce-deep-isolated',
        sequence: 2,
        side: 'SELL',
        quantity: '0.5',
        marginMode: 'ISOLATED',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '2')]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.equal(perpetualPosition(finalSnapshot).status, 'LIQUIDATED')
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
  assert.deepEqual(
    {
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-4),
    },
    {
      wallet: {
        asset: 'USDT',
        available: '89.9',
        locked: '0',
        total: '89.9',
      },
      settlement: [
        {
          actionId: 'reduce-deep-isolated',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-5',
          balanceAfter: '94.9',
        },
        {
          actionId: 'reduce-deep-isolated',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '44.001',
          balanceAfter: '94.9',
        },
        {
          actionId: 'reduce-deep-isolated',
          asset: 'USDT',
          type: 'PERPETUAL_LIQUIDATION_CASH',
          amount: '-5',
          balanceAfter: '89.9',
        },
        {
          actionId: 'reduce-deep-isolated',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '44.003',
          balanceAfter: '89.9',
        },
      ],
    },
  )
})

test('splits closing and residual-open fees when capping a deep ISOLATED reversal', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialBalances: { USDT: '100' },
    marginMode: 'ISOLATED',
    timeline: [
      marketOrder({
        id: 'open-before-deep-reversal',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
      marketOrder({
        id: 'reverse-deep-isolated',
        sequence: 2,
        side: 'SELL',
        quantity: '2',
        marginMode: 'ISOLATED',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '1')]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)

  assert.deepEqual(
    {
      direction: position.direction,
      status: position.status,
      quantity: position.quantity,
      isolatedMargin: position.isolatedMargin,
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-2),
    },
    {
      direction: 'SHORT',
      status: 'OPEN',
      quantity: '1',
      isolatedMargin: '0.1',
      wallet: {
        asset: 'USDT',
        available: '89.799',
        locked: '0.1',
        total: '89.899',
      },
      settlement: [
        {
          actionId: 'reverse-deep-isolated',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-10.001',
          balanceAfter: '89.899',
        },
        {
          actionId: 'reverse-deep-isolated',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '89.001',
          balanceAfter: '89.899',
        },
      ],
    },
  )
})

test('caps a deep CROSS close at its shared pool while preserving ISOLATED principal', () => {
  const isolated = instrument('CLOSE-ISOLATED-POOL-LAB', 'ISOLATED')
  const cross = instrument('CLOSE-CROSS-POOL-LAB', 'CROSS')
  const input = scenario({
    instruments: [isolated, cross],
    initialBalances: { USDT: '100' },
    timeline: [
      marketOrder({
        id: 'open-close-isolated-pool',
        sequence: 1,
        symbol: isolated.symbol,
        side: 'BUY',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
      marketOrder({
        id: 'open-deep-cross-close',
        sequence: 2,
        symbol: cross.symbol,
        side: 'BUY',
        quantity: '5',
        marginMode: 'CROSS',
      }),
      marketOrder({
        id: 'close-deep-cross',
        sequence: 3,
        symbol: cross.symbol,
        side: 'SELL',
        quantity: '5',
        marginMode: 'CROSS',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(isolated, '100'),
        quote(cross, '100'),
      ]),
      tick(2, [
        quote(isolated, '100'),
        quote(cross, '100'),
      ]),
      tick(3, [
        quote(isolated, '100'),
        quote(cross, '1'),
      ]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[2]

  assert.equal(
    perpetualPosition(finalSnapshot, isolated.symbol).status,
    'OPEN',
  )
  assert.equal(
    perpetualPosition(finalSnapshot, cross.symbol).status,
    'CLOSED',
  )
  assert.deepEqual(
    {
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-2),
    },
    {
      wallet: {
        asset: 'USDT',
        available: '0',
        locked: '10',
        total: '10',
      },
      settlement: [
        {
          actionId: 'close-deep-cross',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-89.4',
          balanceAfter: '10',
        },
        {
          actionId: 'close-deep-cross',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '405.605',
          balanceAfter: '10',
        },
      ],
    },
  )
})

test('applies a deep CROSS partial reduce before unified risk settles the remainder', () => {
  const isolated = instrument('REDUCE-ISOLATED-POOL-LAB', 'ISOLATED')
  const cross = instrument('REDUCE-CROSS-POOL-LAB', 'CROSS')
  const input = scenario({
    instruments: [isolated, cross],
    initialBalances: { USDT: '100' },
    timeline: [
      marketOrder({
        id: 'open-reduce-isolated-pool',
        sequence: 1,
        symbol: isolated.symbol,
        side: 'BUY',
        quantity: '1',
        marginMode: 'ISOLATED',
      }),
      marketOrder({
        id: 'open-before-deep-cross-reduce',
        sequence: 2,
        symbol: cross.symbol,
        side: 'BUY',
        quantity: '5',
        marginMode: 'CROSS',
      }),
      marketOrder({
        id: 'reduce-deep-cross',
        sequence: 3,
        symbol: cross.symbol,
        side: 'SELL',
        quantity: '2',
        marginMode: 'CROSS',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(isolated, '100'),
        quote(cross, '100'),
      ]),
      tick(2, [
        quote(isolated, '100'),
        quote(cross, '100'),
      ]),
      tick(3, [
        quote(isolated, '100'),
        quote(cross, '1'),
      ]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[2]

  assert.equal(
    perpetualPosition(finalSnapshot, cross.symbol).status,
    'LIQUIDATED',
  )
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
  assert.deepEqual(
    {
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-4),
    },
    {
      wallet: {
        asset: 'USDT',
        available: '0',
        locked: '10',
        total: '10',
      },
      settlement: [
        {
          actionId: 'reduce-deep-cross',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-89.4',
          balanceAfter: '10',
        },
        {
          actionId: 'reduce-deep-cross',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '108.602',
          balanceAfter: '10',
        },
        {
          actionId: 'reduce-deep-cross',
          asset: 'USDT',
          type: 'PERPETUAL_LIQUIDATION_CASH',
          amount: '0',
          balanceAfter: '10',
        },
        {
          actionId: 'reduce-deep-cross',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '297.009',
          balanceAfter: '10',
        },
      ],
    },
  )
})

test('fails closed on a flat HEDGE slot when the order expresses close intent', () => {
  const selected = instrument()
  const cases = [
    {
      id: 'buy-flat-short',
      side: 'BUY' as const,
      positionSide: 'SHORT' as const,
    },
    {
      id: 'sell-flat-long',
      side: 'SELL' as const,
      positionSide: 'LONG' as const,
    },
  ]

  for (const fixture of cases) {
    const input = scenario({
      instruments: [selected],
      positionMode: 'HEDGE',
      timeline: [
        marketOrder({
          ...fixture,
          sequence: 1,
          quantity: '1',
        }),
      ],
    })
    const outcome = calculateScenario({
      scenario: input,
      market: {
        virtualStart: VIRTUAL_START,
        ticks: [tick(1, [quote(selected, '100')])],
      },
    })

    assert.equal(outcome.status, 'BLOCKED', fixture.id)
    if (outcome.status !== 'BLOCKED') {
      assert.fail(`${fixture.id} must not open a flat HEDGE slot`)
    }
    assert.deepEqual(
      outcome.issues.map((issue) => issue.code),
      ['ORACLE_HEDGE_CLOSE_WITHOUT_POSITION'],
      fixture.id,
    )
  }
})

test('applies CONTRACTS limits to canonical BASE instead of raw contracts', () => {
  const selected = {
    ...instrument(),
    contractSize: '0.01',
    maxQty: '2',
  }
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'one-hundred-contracts',
        sequence: 1,
        side: 'BUY',
        quantity: '100',
        quantityUnit: 'CONTRACTS',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [tick(1, [quote(selected, '100')])],
  })

  assert.equal(
    perpetualPosition(outcome.result.snapshots[0]).quantity,
    '1',
  )
})

test('checks every Perpetual quantity boundary after canonical BASE conversion', () => {
  const cases: ReadonlyArray<{
    name: string
    selected: InstrumentFixture
    quantity: string
    quantityUnit: 'BASE' | 'CONTRACTS'
    price: string
    maxFillQuantityPerTick?: string
    issueCode: string
  }> = [
    {
      name: 'step after CONTRACTS conversion',
      selected: {
        ...instrument(),
        contractSize: '0.015',
        stepSize: '0.01',
        quantityPrecision: 2,
        minQty: '0.01',
      },
      quantity: '1',
      quantityUnit: 'CONTRACTS',
      price: '100',
      issueCode: 'ORACLE_QUANTITY_STEP_MISMATCH',
    },
    {
      name: 'minimum quantity after CONTRACTS conversion',
      selected: {
        ...instrument(),
        contractSize: '0.1',
        stepSize: '0.1',
        quantityPrecision: 1,
        minQty: '0.5',
      },
      quantity: '1',
      quantityUnit: 'CONTRACTS',
      price: '100',
      issueCode: 'ORACLE_QUANTITY_BELOW_MINIMUM',
    },
    {
      name: 'maximum quantity after CONTRACTS conversion',
      selected: {
        ...instrument(),
        contractSize: '10',
        maxQty: '2',
      },
      quantity: '1',
      quantityUnit: 'CONTRACTS',
      price: '100',
      issueCode: 'ORACLE_QUANTITY_ABOVE_MAXIMUM',
    },
    {
      name: 'minimum notional',
      selected: instrument(),
      quantity: '0.001',
      quantityUnit: 'BASE',
      price: '100',
      issueCode: 'ORACLE_NOTIONAL_BELOW_MINIMUM',
    },
    {
      name: 'maximum notional',
      selected: {
        ...instrument(),
        maxNotional: '100',
      },
      quantity: '2',
      quantityUnit: 'BASE',
      price: '100',
      issueCode: 'ORACLE_NOTIONAL_ABOVE_MAXIMUM',
    },
    {
      name: 'maximum fill quantity',
      selected: instrument(),
      quantity: '1',
      quantityUnit: 'BASE',
      price: '100',
      maxFillQuantityPerTick: '0.5',
      issueCode: 'ORACLE_MAX_FILL_QUANTITY_EXCEEDED',
    },
  ]

  for (const fixture of cases) {
    const input = scenario({
      instruments: [fixture.selected],
      maxFillQuantityPerTick: fixture.maxFillQuantityPerTick,
      timeline: [
        marketOrder({
          id: `boundary-${fixture.issueCode}`,
          sequence: 1,
          side: 'BUY',
          quantity: fixture.quantity,
          quantityUnit: fixture.quantityUnit,
        }),
      ],
    })
    const outcome = calculateScenario({
      scenario: input,
      market: {
        virtualStart: VIRTUAL_START,
        ticks: [tick(1, [quote(fixture.selected, fixture.price)])],
      },
    })

    assert.equal(outcome.status, 'BLOCKED', fixture.name)
    if (outcome.status !== 'BLOCKED') {
      assert.fail(`${fixture.name} must fail closed`)
    }
    assert.deepEqual(
      outcome.issues.map((issue) => issue.code),
      [fixture.issueCode],
      fixture.name,
    )
  }
})

test('treats null Perpetual notional bounds as absent authority', () => {
  const selected = instrument()
  selected.minNotional = null
  selected.maxNotional = null
  const input = scenario({
    instruments: [selected],
    timeline: [
      marketOrder({
        id: 'unbounded-notional',
        sequence: 1,
        side: 'BUY',
        quantity: '1',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [tick(1, [quote(selected, '100')])],
  })

  assert.equal(
    perpetualPosition(outcome.result.snapshots[0]).quantity,
    '1',
  )
})

test('treats a null Perpetual max-fill cap as unbounded while retaining maxQty', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    maxFillQuantityPerTick: null,
    timeline: [
      marketOrder({
        id: 'uncapped-perpetual-buy',
        sequence: 1,
        side: 'BUY',
        quantity: '2',
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [tick(1, [quote(selected, '100')])],
  })

  assert.equal(
    perpetualPosition(outcome.result.snapshots[0]).quantity,
    '2',
  )
})

test('fails closed before an ISOLATED open, reversal, or HEDGE slot is immediately unsafe', () => {
  const selected = instrument()
  const cases: Array<Readonly<{
    name: string
    scenario: TradingLabScenario
    ticks: readonly MarketTick[]
  }>> = [
    {
      name: 'flat open',
      scenario: scenario({
        initialBalances: { USDT: '100' },
        marginMode: 'ISOLATED',
        timeline: [
          marketOrder({
            id: 'unsafe-flat-open',
            sequence: 1,
            side: 'BUY',
            quantity: '1',
            marginMode: 'ISOLATED',
            leverage: 20,
          }),
        ],
      }),
      ticks: [tick(1, [quote(selected, '100')])],
    },
    {
      name: 'ONE_WAY reversal residual',
      scenario: scenario({
        initialBalances: { USDT: '100' },
        marginMode: 'ISOLATED',
        timeline: [
          marketOrder({
            id: 'safe-long',
            sequence: 1,
            side: 'BUY',
            quantity: '1',
            marginMode: 'ISOLATED',
            leverage: 10,
          }),
          marketOrder({
            id: 'unsafe-short-residual',
            sequence: 2,
            side: 'SELL',
            quantity: '2',
            marginMode: 'ISOLATED',
            leverage: 10,
          }),
        ],
      }),
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(2, [quote(selected, '100', '110')]),
      ],
    },
    {
      name: 'HEDGE new slot',
      scenario: scenario({
        initialBalances: { USDT: '100' },
        positionMode: 'HEDGE',
        marginMode: 'ISOLATED',
        leverage: 20,
        timeline: [
          marketOrder({
            id: 'unsafe-hedge-long',
            sequence: 1,
            side: 'BUY',
            quantity: '1',
            positionSide: 'LONG',
            marginMode: 'ISOLATED',
            leverage: 20,
          }),
        ],
      }),
      ticks: [tick(1, [quote(selected, '100')])],
    },
  ]

  for (const fixture of cases) {
    const input = {
      scenario: fixture.scenario,
      market: {
        virtualStart: VIRTUAL_START,
        ticks: fixture.ticks,
      },
    }
    const before = structuredClone(input)

    const outcome = calculateScenario(input)

    assert.equal(outcome.status, 'BLOCKED', fixture.name)
    if (outcome.status === 'BLOCKED') {
      assert.equal(
        outcome.issues.some((issue) =>
          issue.code === 'ORACLE_POSITION_RISK_UNSAFE'),
        true,
        fixture.name,
      )
      assert.equal('result' in outcome, false, fixture.name)
    }
    assert.deepEqual(input, before, fixture.name)
  }
})
