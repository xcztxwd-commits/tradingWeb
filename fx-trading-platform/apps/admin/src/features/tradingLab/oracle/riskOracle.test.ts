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
const CROSS_ESTIMATE_ASSUMPTION =
  'Cross liquidation estimate assumes every non-target instrument mark remains unchanged.'

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
  initialUsdt?: string
  positionMode?: 'ONE_WAY' | 'HEDGE'
  marginMode?: 'CROSS' | 'ISOLATED'
  leverage?: number
  takerFeeRate?: string
  slippageRate?: string
  timeline: TimelineAction[]
}): TradingLabScenario {
  const instruments = input.instruments ?? [instrument()]
  const executionPolicy = {
    matchingMode: 'SIMPLE' as const,
    makerFeeRate: '0.001',
    takerFeeRate: input.takerFeeRate ?? '0.001',
    liquidationFeeRate: '0.002',
    slippageRate: input.slippageRate ?? '0',
    maxFillQuantityPerTick: '100',
  }
  return {
    id: 'risk-oracle-fixture',
    name: 'Risk Oracle fixture',
    description: '',
    negativeMode: false,
    seed: 'risk-oracle-seed',
    modelVersion: 'model-v1',
    configSnapshot: {
      modelVersion: 'model-v1',
      symbolConfigVersion: 'symbols-v1',
      codeVersion: 'code-v1',
      executionPolicy: { ...executionPolicy },
      instruments,
    },
    configSnapshotHash: 'c'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: VIRTUAL_START,
      realistic: false,
      instruments: instruments.map((selected) => ({
        mode: 'SIMPLE' as const,
        productType: 'LINEAR_PERP' as const,
        symbol: selected.symbol,
        seed: `risk-path-${selected.symbol}`,
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
    initialBalances: { USDT: input.initialUsdt ?? '100' },
    defaults: {
      positionMode: input.positionMode ?? 'ONE_WAY',
      marginMode: input.marginMode ?? 'ISOLATED',
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

function action(input: {
  id: string
  sequence: number
  type:
    | 'PLACE_ORDER'
    | 'ADD_MARGIN'
    | 'REMOVE_MARGIN'
    | 'SET_LEVERAGE'
    | 'APPLY_FUNDING'
  symbol?: string
  parameters: Record<string, unknown>
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
    parameters: input.parameters,
  }
}

function openLong(input: {
  id?: string
  sequence?: number
  symbol?: string
  marginMode?: 'CROSS' | 'ISOLATED'
  leverage?: number
  quantity?: string
}): TimelineAction {
  return action({
    id: input.id ?? 'open-long',
    sequence: input.sequence ?? 1,
    type: 'PLACE_ORDER',
    symbol: input.symbol,
    parameters: {
      side: 'BUY',
      orderType: 'MARKET',
      quantity: input.quantity ?? '1',
      quantityUnit: 'BASE',
      positionSide: 'BOTH',
      marginMode: input.marginMode ?? 'ISOLATED',
      leverage: input.leverage ?? 10,
    },
  })
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
) {
  const found = snapshot.wallets.find((candidate) => candidate.asset === 'USDT')
  assert.ok(found, 'USDT wallet is required')
  return found
}

test('adds and safely removes isolated margin without changing wallet total', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({}),
      action({
        id: 'add-margin',
        sequence: 2,
        type: 'ADD_MARGIN',
        parameters: {
          amount: '5',
          positionSide: 'BOTH',
        },
      }),
      action({
        id: 'remove-safe-margin',
        sequence: 3,
        type: 'REMOVE_MARGIN',
        parameters: {
          amount: '4',
          positionSide: 'BOTH',
        },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '100')]),
      tick(3, [quote(selected, '100')]),
    ],
  })

  assert.equal(
    perpetualPosition(outcome.result.snapshots[0]).isolatedMargin,
    '10',
  )
  assert.equal(
    perpetualPosition(outcome.result.snapshots[1]).isolatedMargin,
    '15',
  )
  const finalSnapshot = outcome.result.snapshots[2]
  assert.equal(perpetualPosition(finalSnapshot).isolatedMargin, '11')
  assert.deepEqual(
    wallet(finalSnapshot),
    {
      asset: 'USDT',
      available: '88.9',
      locked: '11',
      total: '99.9',
    },
  )
  assert.equal(
    outcome.runnerIssues.filter((issue) =>
      issue.code === 'ACTION_REQUIRES_RUNNER_SUPPORT').length,
    2,
  )
})

test('keeps a safe isolated margin removal below initial margin instead of restoring it', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({ marginMode: 'ISOLATED' }),
      action({
        id: 'remove-below-initial-safely',
        sequence: 2,
        type: 'REMOVE_MARGIN',
        parameters: {
          amount: '1',
          positionSide: 'BOTH',
        },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '100')]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.equal(perpetualPosition(finalSnapshot).isolatedMargin, '9')
  assert.deepEqual(
    wallet(finalSnapshot),
    {
      asset: 'USDT',
      available: '90.9',
      locked: '9',
      total: '99.9',
    },
  )
  assert.equal(finalSnapshot.accountSummary.equityUsdt, '99.9')
})

test('fails closed on unsafe isolated margin removal without mutating inputs', () => {
  const selected = instrument()
  const input = {
    scenario: scenario({
      instruments: [selected],
      timeline: [
        openLong({}),
        action({
          id: 'add-margin',
          sequence: 2,
          type: 'ADD_MARGIN',
          parameters: {
            amount: '5',
            positionSide: 'BOTH',
          },
        }),
        action({
          id: 'remove-unsafe-margin',
          sequence: 3,
          type: 'REMOVE_MARGIN',
          parameters: {
            amount: '10',
            positionSide: 'BOTH',
          },
        }),
      ],
    }),
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(2, [quote(selected, '100')]),
        tick(3, [quote(selected, '100')]),
      ],
    },
  }
  const before = structuredClone(input)

  const outcome = calculateScenario(input)

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((issue) =>
        issue.code === 'ORACLE_MARGIN_REMOVAL_UNSAFE'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual(input, before)
})

test('fails closed when isolated margin removal ignores pending funding and close fee', () => {
  const selected = instrument()
  const input = {
    scenario: scenario({
      instruments: [selected],
      timeline: [
        openLong({ marginMode: 'ISOLATED' }),
        action({
          id: 'add-one',
          sequence: 2,
          type: 'ADD_MARGIN',
          parameters: {
            amount: '1',
            positionSide: 'BOTH',
          },
        }),
        action({
          id: 'charge-isolated-funding',
          sequence: 3,
          type: 'APPLY_FUNDING',
          parameters: {},
        }),
        action({
          id: 'remove-one-unsafely',
          sequence: 4,
          type: 'REMOVE_MARGIN',
          parameters: {
            amount: '1',
            positionSide: 'BOTH',
          },
        }),
      ],
    }),
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(2, [quote(selected, '100')]),
        tick(
          3,
          [quote(selected, '100')],
          [{ symbol: selected.symbol, rate: '0.05' }],
        ),
        tick(4, [quote(selected, '100')]),
      ],
    },
  }
  const before = structuredClone(input)

  const outcome = calculateScenario(input)

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((issue) =>
        issue.code === 'ORACLE_MARGIN_REMOVAL_UNSAFE'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual(input, before)
})

test('recalculates isolated principal on leverage change', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({}),
      action({
        id: 'change-leverage',
        sequence: 2,
        type: 'SET_LEVERAGE',
        parameters: { leverage: 5 },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '100')]),
    ],
  })

  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)
  assert.deepEqual(
    {
      leverage: position.leverage,
      initialMargin: position.initialMargin,
      isolatedMargin: position.isolatedMargin,
    },
    {
      leverage: 5,
      initialMargin: '20',
      isolatedMargin: '20',
    },
  )
  assert.deepEqual(
    wallet(finalSnapshot),
    {
      asset: 'USDT',
      available: '79.9',
      locked: '20',
      total: '99.9',
    },
  )
})

test('fails closed when leverage change would make isolated equity unsafe', () => {
  const selected = instrument()
  const input = {
    scenario: scenario({
      instruments: [selected],
      timeline: [
        openLong({ marginMode: 'ISOLATED' }),
        action({
          id: 'unsafe-leverage-change',
          sequence: 2,
          type: 'SET_LEVERAGE',
          parameters: {
            leverage: 20,
          },
        }),
      ],
    }),
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(2, [quote(selected, '100')]),
      ],
    },
  }
  const before = structuredClone(input)

  const outcome = calculateScenario(input)

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((issue) =>
        issue.code === 'ORACLE_LEVERAGE_CHANGE_UNSAFE'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual(input, before)
})

test('liquidates an unsafe ISOLATED position and keeps liquidation fee separate', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({ marginMode: 'ISOLATED' }),
      action({
        id: 'risk-tick',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '94')],
        [{ symbol: selected.symbol, rate: '0' }],
      ),
    ],
  })

  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)
  assert.deepEqual(
    {
      status: position.status,
      quantity: position.quantity,
      markPrice: position.markPrice,
      realizedGrossPnl: position.realizedGrossPnl,
      tradingFeeUsdt: position.tradingFeeUsdt,
      liquidationFeeUsdt: position.liquidationFeeUsdt,
      fundingPnlUsdt: position.fundingPnlUsdt,
      realizedNetPnl: position.realizedNetPnl,
    },
    {
      status: 'LIQUIDATED',
      quantity: '0',
      markPrice: '94',
      realizedGrossPnl: '-6',
      tradingFeeUsdt: '0.194',
      liquidationFeeUsdt: '0.188',
      fundingPnlUsdt: '0',
      realizedNetPnl: '-6.382',
    },
  )
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
  assert.deepEqual(
    finalSnapshot.orders.at(-1),
    {
      id: 'liquidation-order:risk-tick:XBT-USDT-LAB:BOTH',
      actionId: 'risk-tick',
      symbol: 'XBT-USDT-LAB',
      productType: 'LINEAR_PERP',
      side: 'SELL',
      status: 'FILLED',
      quantity: '1',
      price: '94',
      feeUsdt: '0.094',
    },
  )
  assert.deepEqual(
    finalSnapshot.trades.at(-1),
    {
      id: 'liquidation-trade:risk-tick:XBT-USDT-LAB:BOTH',
      orderId: 'liquidation-order:risk-tick:XBT-USDT-LAB:BOTH',
      actionId: 'risk-tick',
      symbol: 'XBT-USDT-LAB',
      productType: 'LINEAR_PERP',
      side: 'SELL',
      quantity: '1',
      price: '94',
      notionalUsdt: '94',
      feeUsdt: '0.094',
    },
  )
  assert.deepEqual(
    finalSnapshot.ledgerProjection.at(-1),
    {
      actionId: 'risk-tick',
      asset: 'USDT',
      type: 'PERPETUAL_LIQUIDATION_CASH',
      amount: '-6.282',
      balanceAfter: '93.618',
    },
  )
})

test('liquidates CROSS against shared USDT without hiding bankruptcy loss', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialUsdt: '20',
    marginMode: 'CROSS',
    timeline: [
      openLong({ marginMode: 'CROSS' }),
      action({
        id: 'cross-risk-tick',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '80')],
        [{ symbol: selected.symbol, rate: '0' }],
      ),
    ],
  })

  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)
  assert.deepEqual(
    {
      status: position.status,
      marginMode: position.marginMode,
      realizedGrossPnl: position.realizedGrossPnl,
      tradingFeeUsdt: position.tradingFeeUsdt,
      liquidationFeeUsdt: position.liquidationFeeUsdt,
      realizedNetPnl: position.realizedNetPnl,
    },
    {
      status: 'LIQUIDATED',
      marginMode: 'CROSS',
      realizedGrossPnl: '-20',
      tradingFeeUsdt: '0.18',
      liquidationFeeUsdt: '0.16',
      realizedNetPnl: '-20.34',
    },
  )
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
  assert.equal(wallet(finalSnapshot).total, '0')
})

test('states that CROSS estimates hold every non-target mark unchanged', () => {
  const target = instrument('XBT-ALPHA-LAB', 'XBT')
  const other = instrument('ETHER-OMEGA-LAB', 'ETHER')
  const input = scenario({
    instruments: [target, other],
    initialUsdt: '1000',
    marginMode: 'CROSS',
    timeline: [
      openLong({
        id: 'open-target',
        symbol: target.symbol,
        marginMode: 'CROSS',
      }),
      action({
        id: 'open-other-short',
        sequence: 2,
        type: 'PLACE_ORDER',
        symbol: other.symbol,
        parameters: {
          side: 'SELL',
          orderType: 'MARKET',
          quantity: '1',
          quantityUnit: 'BASE',
          positionSide: 'BOTH',
          marginMode: 'CROSS',
          leverage: 10,
        },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(target, '100'),
        quote(other, '200'),
      ]),
      tick(2, [
        quote(target, '100'),
        quote(other, '200'),
      ]),
    ],
  })

  assert.equal(outcome.result.assumptions.includes(CROSS_ESTIMATE_ASSUMPTION), true)
  const finalSnapshot = outcome.result.snapshots[1]
  assert.equal(finalSnapshot.risk.liquidationTriggered, false)
  for (const selected of [target, other]) {
    const estimate = perpetualPosition(
      finalSnapshot,
      selected.symbol,
    ).estimatedLiquidationPrice
    assert.equal(typeof estimate, 'string')
    assert.notEqual(estimate, '')
  }
})

test('keeps isolated funding position-local while reporting account equity', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({ marginMode: 'ISOLATED' }),
      action({
        id: 'isolated-funding',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '100')],
        [{ symbol: selected.symbol, rate: '0.04' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]
  const position = perpetualPosition(finalSnapshot)

  assert.deepEqual(
    {
      status: position.status,
      fundingPnlUsdt: position.fundingPnlUsdt,
      realizedNetPnl: position.realizedNetPnl,
      wallet: wallet(finalSnapshot),
      accountEquity: finalSnapshot.accountSummary.equityUsdt,
      riskEquity: finalSnapshot.risk.equityUsdt,
    },
    {
      status: 'OPEN',
      fundingPnlUsdt: '-4',
      realizedNetPnl: '-4.1',
      wallet: {
        asset: 'USDT',
        available: '89.9',
        locked: '10',
        total: '99.9',
      },
      accountEquity: '95.9',
      riskEquity: '95.9',
    },
  )
})

test('settles isolated funding proportionally through partial and final close', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({
        marginMode: 'ISOLATED',
        quantity: '2',
      }),
      action({
        id: 'charge-four',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
      action({
        id: 'close-half',
        sequence: 3,
        type: 'PLACE_ORDER',
        parameters: {
          side: 'SELL',
          orderType: 'MARKET',
          quantity: '1',
          quantityUnit: 'BASE',
          positionSide: 'BOTH',
          marginMode: 'ISOLATED',
          leverage: 10,
        },
      }),
      action({
        id: 'close-rest',
        sequence: 4,
        type: 'PLACE_ORDER',
        parameters: {
          side: 'SELL',
          orderType: 'MARKET',
          quantity: '1',
          quantityUnit: 'BASE',
          positionSide: 'BOTH',
          marginMode: 'ISOLATED',
          leverage: 10,
        },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '100')],
        [{ symbol: selected.symbol, rate: '0.02' }],
      ),
      tick(3, [quote(selected, '100')]),
      tick(4, [quote(selected, '100')]),
    ],
  })
  const funded = outcome.result.snapshots[1]
  const partial = outcome.result.snapshots[2]
  const closed = outcome.result.snapshots[3]

  assert.equal(wallet(funded).total, '99.8')
  assert.equal(funded.accountSummary.equityUsdt, '95.8')
  assert.deepEqual(
    funded.ledgerProjection.at(-1),
    {
      actionId: 'charge-four',
      asset: 'USDT',
      type: 'PERPETUAL_FUNDING',
      amount: '-4',
      balanceAfter: '99.8',
    },
  )
  assert.deepEqual(
    {
      wallet: wallet(partial),
      equity: partial.accountSummary.equityUsdt,
      fundingPnlUsdt: perpetualPosition(partial).fundingPnlUsdt,
      realizedNetPnl: perpetualPosition(partial).realizedNetPnl,
      cash: partial.ledgerProjection.at(-1),
    },
    {
      wallet: {
        asset: 'USDT',
        available: '87.7',
        locked: '10',
        total: '97.7',
      },
      equity: '95.7',
      fundingPnlUsdt: '-4',
      realizedNetPnl: '-4.3',
      cash: {
        actionId: 'close-half',
        asset: 'USDT',
        type: 'PERPETUAL_TRADE_CASH',
        amount: '-2.1',
        balanceAfter: '97.7',
      },
    },
  )
  assert.deepEqual(
    {
      wallet: wallet(closed),
      equity: closed.accountSummary.equityUsdt,
      status: perpetualPosition(closed).status,
      fundingPnlUsdt: perpetualPosition(closed).fundingPnlUsdt,
      realizedNetPnl: perpetualPosition(closed).realizedNetPnl,
      cash: closed.ledgerProjection.at(-1),
    },
    {
      wallet: {
        asset: 'USDT',
        available: '95.6',
        locked: '0',
        total: '95.6',
      },
      equity: '95.6',
      status: 'CLOSED',
      fundingPnlUsdt: '-4',
      realizedNetPnl: '-4.4',
      cash: {
        actionId: 'close-rest',
        asset: 'USDT',
        type: 'PERPETUAL_TRADE_CASH',
        amount: '-2.1',
        balanceAfter: '95.6',
      },
    },
  )
})

test('excludes isolated principal and isolated risk terms from CROSS equity', () => {
  const isolated = instrument('XBT-ISOLATED-LAB', 'XBT')
  const cross = instrument('ETHER-CROSS-LAB', 'ETHER')
  const input = scenario({
    instruments: [isolated, cross],
    timeline: [
      openLong({
        id: 'open-isolated',
        symbol: isolated.symbol,
        marginMode: 'ISOLATED',
        quantity: '8',
      }),
      openLong({
        id: 'open-cross',
        sequence: 2,
        symbol: cross.symbol,
        marginMode: 'CROSS',
      }),
      action({
        id: 'cross-risk-after-isolated-lock',
        sequence: 3,
        type: 'APPLY_FUNDING',
        symbol: cross.symbol,
        parameters: {},
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
      tick(
        3,
        [
          quote(isolated, '100'),
          quote(cross, '84'),
        ],
        [{ symbol: cross.symbol, rate: '0' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[2]

  assert.equal(
    perpetualPosition(finalSnapshot, isolated.symbol).status,
    'OPEN',
  )
  assert.equal(
    perpetualPosition(finalSnapshot, cross.symbol).status,
    'LIQUIDATED',
  )
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
})

test('blocks a new CROSS position against current-Tick loss on another symbol', () => {
  const first = instrument('FIRST-CROSS-LAB', 'FIRST')
  const second = instrument('SECOND-CROSS-LAB', 'SECOND')
  const input = {
    scenario: scenario({
      instruments: [first, second],
      initialUsdt: '100',
      marginMode: 'CROSS',
      timeline: [
        openLong({
          id: 'open-first-cross',
          symbol: first.symbol,
          marginMode: 'CROSS',
        }),
        openLong({
          id: 'overcommit-second-cross',
          sequence: 2,
          symbol: second.symbol,
          marginMode: 'CROSS',
          quantity: '5',
        }),
      ],
    }),
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [
          quote(first, '100'),
          quote(second, '100'),
        ]),
        tick(2, [
          quote(first, '60'),
          quote(second, '100'),
        ]),
      ],
    },
  }
  const before = structuredClone(input)

  const outcome = calculateScenario(input)

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.equal(
      outcome.issues.some((issue) =>
        issue.code === 'ORACLE_INSUFFICIENT_MARGIN'),
      true,
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual(input, before)
})

test('reports CROSS available balance after current unrealized PnL', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialUsdt: '100',
    marginMode: 'CROSS',
    timeline: [
      openLong({ marginMode: 'CROSS' }),
      action({
        id: 'revalue-cross-loss',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '80')],
        [{ symbol: selected.symbol, rate: '0' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.deepEqual(
    {
      wallet: wallet(finalSnapshot),
      accountAvailable: finalSnapshot.accountSummary.availableBalanceUsdt,
      riskAvailable: finalSnapshot.risk.availableBalanceUsdt,
    },
    {
      wallet: {
        asset: 'USDT',
        available: '69.9',
        locked: '10',
        total: '99.9',
      },
      accountAvailable: '69.9',
      riskAvailable: '69.9',
    },
  )
})

test('includes the expected close taker fee in isolated liquidation threshold', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({ marginMode: 'ISOLATED' }),
      action({
        id: 'close-fee-threshold',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '94.8')],
        [{ symbol: selected.symbol, rate: '0' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.equal(perpetualPosition(finalSnapshot).status, 'LIQUIDATED')
  assert.equal(finalSnapshot.risk.liquidationTriggered, true)
})

test('solves one shared CROSS estimate for both HEDGE slots of a symbol', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    positionMode: 'HEDGE',
    marginMode: 'CROSS',
    takerFeeRate: '0',
    timeline: [
      action({
        id: 'open-hedge-long',
        sequence: 1,
        type: 'PLACE_ORDER',
        parameters: {
          side: 'BUY',
          orderType: 'MARKET',
          quantity: '1',
          quantityUnit: 'BASE',
          positionSide: 'LONG',
          marginMode: 'CROSS',
          leverage: 10,
        },
      }),
      action({
        id: 'open-hedge-short',
        sequence: 2,
        type: 'PLACE_ORDER',
        parameters: {
          side: 'SELL',
          orderType: 'MARKET',
          quantity: '1',
          quantityUnit: 'BASE',
          positionSide: 'SHORT',
          marginMode: 'CROSS',
          leverage: 10,
        },
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(2, [quote(selected, '100')]),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.equal(
    perpetualPosition(
      finalSnapshot,
      selected.symbol,
      'LONG',
    ).estimatedLiquidationPrice,
    '1000',
  )
  assert.equal(
    perpetualPosition(
      finalSnapshot,
      selected.symbol,
      'SHORT',
    ).estimatedLiquidationPrice,
    '1000',
  )
})

test('aggregates CROSS liquidation cash before the bankruptcy floor', () => {
  const longInstrument = {
    ...instrument('LOSS-LONG-LAB', 'LOSS'),
    maintenanceMarginRate: '0.005',
    liquidationFeeRate: '0',
    maxLeverage: 100,
    defaultLeverage: 100,
  }
  const shortInstrument = {
    ...instrument('GAIN-SHORT-LAB', 'GAIN'),
    maintenanceMarginRate: '0.005',
    liquidationFeeRate: '0',
    maxLeverage: 100,
    defaultLeverage: 100,
  }

  function run(openLossFirst: boolean): CalculatedOutcome {
    const openLongAction = openLong({
      id: 'open-loss-long',
      sequence: openLossFirst ? 1 : 2,
      symbol: longInstrument.symbol,
      marginMode: 'CROSS',
      leverage: 100,
    })
    const openShortAction = action({
      id: 'open-gain-short',
      sequence: openLossFirst ? 2 : 1,
      type: 'PLACE_ORDER',
      symbol: shortInstrument.symbol,
      parameters: {
        side: 'SELL',
        orderType: 'MARKET',
        quantity: '1',
        quantityUnit: 'BASE',
        positionSide: 'BOTH',
        marginMode: 'CROSS',
        leverage: 100,
      },
    })
    const ordered = openLossFirst
      ? [openLongAction, openShortAction]
      : [openShortAction, openLongAction]
    const input = scenario({
      instruments: [longInstrument, shortInstrument],
      initialUsdt: '10',
      marginMode: 'CROSS',
      leverage: 100,
      takerFeeRate: '0',
      timeline: [
        ...ordered,
        action({
          id: 'aggregate-cross-liquidation',
          sequence: 3,
          type: 'APPLY_FUNDING',
          symbol: longInstrument.symbol,
          parameters: {},
        }),
      ],
    })
    return calculate({
      scenario: input,
      ticks: [
        tick(1, [
          quote(longInstrument, '100'),
          quote(shortInstrument, '100'),
        ]),
        tick(2, [
          quote(longInstrument, '100'),
          quote(shortInstrument, '100'),
        ]),
        tick(
          3,
          [
            quote(longInstrument, '1'),
            quote(shortInstrument, '20'),
          ],
          [{ symbol: longInstrument.symbol, rate: '0' }],
        ),
      ],
    })
  }

  for (const outcome of [run(true), run(false)]) {
    const finalSnapshot = outcome.result.snapshots[2]
    assert.equal(wallet(finalSnapshot).total, '0')
    assert.equal(finalSnapshot.risk.liquidationTriggered, true)
    assert.equal(
      perpetualPosition(finalSnapshot, longInstrument.symbol).status,
      'LIQUIDATED',
    )
    assert.equal(
      perpetualPosition(finalSnapshot, shortInstrument.symbol).status,
      'LIQUIDATED',
    )
  }
})

test('caps deep isolated liquidation loss at its own margin pool', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialUsdt: '100',
    timeline: [
      openLong({ marginMode: 'ISOLATED' }),
      action({
        id: 'deep-isolated-liquidation',
        sequence: 2,
        type: 'APPLY_FUNDING',
        parameters: {},
      }),
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '1')],
        [{ symbol: selected.symbol, rate: '0' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[1]

  assert.equal(perpetualPosition(finalSnapshot).status, 'LIQUIDATED')
  assert.deepEqual(
    {
      wallet: wallet(finalSnapshot),
      settlement: finalSnapshot.ledgerProjection.slice(-2),
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
          actionId: 'deep-isolated-liquidation',
          asset: 'USDT',
          type: 'PERPETUAL_LIQUIDATION_CASH',
          amount: '-10',
          balanceAfter: '89.9',
        },
        {
          actionId: 'deep-isolated-liquidation',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '89.003',
          balanceAfter: '89.9',
        },
      ],
    },
  )
})

test('preserves open isolated principal when deep CROSS liquidation exhausts collateral', () => {
  const isolated = instrument('ISOLATED-POOL-LAB', 'ISO')
  const cross = instrument('CROSS-POOL-LAB', 'CROSS')
  const input = scenario({
    instruments: [isolated, cross],
    initialUsdt: '30',
    timeline: [
      openLong({
        id: 'open-isolated-pool',
        symbol: isolated.symbol,
        marginMode: 'ISOLATED',
      }),
      openLong({
        id: 'open-cross-pool',
        sequence: 2,
        symbol: cross.symbol,
        marginMode: 'CROSS',
      }),
      action({
        id: 'deep-cross-liquidation',
        sequence: 3,
        type: 'APPLY_FUNDING',
        symbol: cross.symbol,
        parameters: {},
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
      tick(
        3,
        [
          quote(isolated, '100'),
          quote(cross, '1'),
        ],
        [{ symbol: cross.symbol, rate: '0' }],
      ),
    ],
  })
  const finalSnapshot = outcome.result.snapshots[2]

  assert.equal(
    perpetualPosition(finalSnapshot, isolated.symbol).status,
    'OPEN',
  )
  assert.equal(
    perpetualPosition(finalSnapshot, cross.symbol).status,
    'LIQUIDATED',
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
          actionId: 'deep-cross-liquidation',
          asset: 'USDT',
          type: 'PERPETUAL_LIQUIDATION_CASH',
          amount: '-19.8',
          balanceAfter: '10',
        },
        {
          actionId: 'deep-cross-liquidation',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '79.203',
          balanceAfter: '10',
        },
      ],
    },
  )
})

test('uses executable bid or ask plus slippage for liquidation evidence', () => {
  const selected = instrument()

  function run(direction: 'LONG' | 'SHORT'): CalculatedOutcome {
    const isLong = direction === 'LONG'
    const input = scenario({
      instruments: [selected],
      slippageRate: '0.01',
      timeline: [
        action({
          id: `open-${direction.toLowerCase()}`,
          sequence: 1,
          type: 'PLACE_ORDER',
          parameters: {
            side: isLong ? 'BUY' : 'SELL',
            orderType: 'MARKET',
            quantity: '1',
            quantityUnit: 'BASE',
            positionSide: 'BOTH',
            marginMode: 'ISOLATED',
            leverage: 10,
          },
        }),
        action({
          id: `liquidate-${direction.toLowerCase()}`,
          sequence: 2,
          type: 'APPLY_FUNDING',
          parameters: {},
        }),
      ],
    })
    return calculate({
      scenario: input,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(
          2,
          [{
            productType: 'LINEAR_PERP',
            symbol: selected.symbol,
            bid: isLong ? '90' : '109',
            ask: isLong ? '91' : '110',
            last: isLong ? '94.8' : '105.2',
            mark: isLong ? '94.8' : '105.2',
            index: isLong ? '94.8' : '105.2',
          }],
          [{ symbol: selected.symbol, rate: '0' }],
        ),
      ],
    })
  }

  const longSnapshot = run('LONG').result.snapshots[1]
  const shortSnapshot = run('SHORT').result.snapshots[1]

  assert.deepEqual(
    {
      orderPrice: longSnapshot.orders.at(-1)?.price,
      tradePrice: longSnapshot.trades.at(-1)?.price,
      notional: longSnapshot.trades.at(-1)?.notionalUsdt,
      fee: longSnapshot.trades.at(-1)?.feeUsdt,
    },
    {
      orderPrice: '89.1',
      tradePrice: '89.1',
      notional: '89.1',
      fee: '0.0891',
    },
  )
  assert.deepEqual(
    {
      orderPrice: shortSnapshot.orders.at(-1)?.price,
      tradePrice: shortSnapshot.trades.at(-1)?.price,
      notional: shortSnapshot.trades.at(-1)?.notionalUsdt,
      fee: shortSnapshot.trades.at(-1)?.feeUsdt,
    },
    {
      orderPrice: '111.1',
      tradePrice: '111.1',
      notional: '111.1',
      fee: '0.1111',
    },
  )
})

test('runs every same-Tick user action before the single risk phase', () => {
  const first = instrument('FIRST-ISOLATED-LAB', 'FIRST')
  const second = instrument('SECOND-FLAT-LAB', 'SECOND')
  const unrelated = action({
    id: 'unrelated-settings',
    sequence: 2,
    type: 'SET_LEVERAGE',
    symbol: second.symbol,
    parameters: { leverage: 10 },
  })
  const rescue = action({
    id: 'same-tick-rescue',
    sequence: 3,
    type: 'ADD_MARGIN',
    symbol: first.symbol,
    parameters: {
      amount: '10',
      positionSide: 'BOTH',
    },
  })
  rescue.trigger = { type: 'VIRTUAL_TIME', atSecond: 2 }
  const input = scenario({
    instruments: [first, second],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'open-first',
        symbol: first.symbol,
        marginMode: 'ISOLATED',
      }),
      unrelated,
      rescue,
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(first, '100'),
        quote(second, '100'),
      ]),
      tick(2, [
        quote(first, '90'),
        quote(second, '100'),
      ]),
    ],
  })
  const beforeRescue = outcome.result.snapshots[1]
  const afterRescue = outcome.result.snapshots[2]

  assert.equal(
    perpetualPosition(beforeRescue, first.symbol).status,
    'OPEN',
  )
  assert.equal(beforeRescue.risk.liquidationTriggered, false)
  assert.deepEqual(
    {
      status: perpetualPosition(afterRescue, first.symbol).status,
      isolatedMargin:
        perpetualPosition(afterRescue, first.symbol).isolatedMargin,
      liquidationTriggered: afterRescue.risk.liquidationTriggered,
    },
    {
      status: 'OPEN',
      isolatedMargin: '20',
      liquidationTriggered: false,
    },
  )
})

test('runs funding after every same-Tick user action and before risk', () => {
  const selected = instrument()
  const funding = action({
    id: 'fund-expanded-position',
    sequence: 2,
    type: 'APPLY_FUNDING',
    parameters: {},
  })
  const add = openLong({
    id: 'expand-before-funding',
    sequence: 3,
    marginMode: 'ISOLATED',
  })
  add.trigger = { type: 'VIRTUAL_TIME', atSecond: 2 }
  const input = scenario({
    instruments: [selected],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'open-before-funding',
        marginMode: 'ISOLATED',
      }),
      funding,
      add,
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [quote(selected, '100')]),
      tick(
        2,
        [quote(selected, '100')],
        [{ symbol: selected.symbol, rate: '0.001' }],
      ),
    ],
  })
  const expanded = outcome.result.snapshots[1]
  const funded = outcome.result.snapshots[2]

  assert.deepEqual(
    outcome.result.snapshots.map((candidate) => candidate.actionId),
    [
      'open-before-funding',
      'expand-before-funding',
      'fund-expanded-position',
    ],
  )
  assert.deepEqual(
    {
      expandedQuantity: perpetualPosition(expanded).quantity,
      expandedFunding: perpetualPosition(expanded).fundingPnlUsdt,
      fundedQuantity: perpetualPosition(funded).quantity,
      fundedFunding: perpetualPosition(funded).fundingPnlUsdt,
      liquidationTriggered: funded.risk.liquidationTriggered,
    },
    {
      expandedQuantity: '2',
      expandedFunding: '0',
      fundedQuantity: '2',
      fundedFunding: '-0.2',
      liquidationTriggered: false,
    },
  )
})

test('runs system risk on a Tick even when that Tick has no action', () => {
  const first = instrument('FIRST-EMPTY-TICK-LAB', 'FIRST')
  const second = instrument('SECOND-LATER-ACTION-LAB', 'SECOND')
  const laterAction = action({
    id: 'later-flat-settings',
    sequence: 2,
    type: 'SET_LEVERAGE',
    symbol: second.symbol,
    parameters: { leverage: 10 },
  })
  laterAction.trigger = { type: 'VIRTUAL_TIME', atSecond: 3 }
  const input = scenario({
    instruments: [first, second],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'open-before-empty-risk-tick',
        symbol: first.symbol,
        marginMode: 'ISOLATED',
      }),
      laterAction,
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(first, '100'),
        quote(second, '100'),
      ]),
      tick(2, [
        quote(first, '1'),
        quote(second, '100'),
      ]),
      tick(3, [
        quote(first, '100'),
        quote(second, '100'),
      ]),
    ],
  })
  const systemSnapshot = outcome.result.snapshots[1]
  const finalSnapshot = outcome.result.snapshots.at(-1)
  assert.ok(finalSnapshot)

  assert.equal(
    perpetualPosition(finalSnapshot, first.symbol).status,
    'LIQUIDATED',
  )
  assert.equal(systemSnapshot.actionId, 'system:risk:2')
  assert.equal(
    finalSnapshot.ledgerProjection.some((entry) =>
      entry.actionId === 'system:risk:2'
      && entry.type === 'PERPETUAL_LIQUIDATION_CASH'),
    true,
  )
})

test('exposes a trailing empty-Tick liquidation as a system snapshot', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'open-before-trailing-risk',
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

  assert.deepEqual(
    outcome.result.snapshots.map((candidate) => candidate.actionId),
    ['open-before-trailing-risk', 'system:risk:2'],
  )
  const systemSnapshot = outcome.result.snapshots[1]
  assert.equal(perpetualPosition(systemSnapshot).status, 'LIQUIDATED')
  assert.equal(systemSnapshot.risk.liquidationTriggered, true)
})

test('conserves every decimal of an ISOLATED pool across a partial close', () => {
  const selected = {
    ...instrument('FRACTIONAL-POOL-LAB', 'FRACTIONAL'),
    stepSize: '0.000001',
    quantityPrecision: 6,
    minQty: '0.000001',
    minNotional: '0.000001',
  }
  const second = instrument('FRACTIONAL-SECOND-LAB', 'SECOND')
  const partialClose = action({
    id: 'fractional-partial-close',
    sequence: 2,
    type: 'PLACE_ORDER',
    symbol: selected.symbol,
    parameters: {
      side: 'SELL',
      orderType: 'MARKET',
      quantity: '0.000001',
      quantityUnit: 'BASE',
      positionSide: 'BOTH',
      marginMode: 'ISOLATED',
      leverage: 10,
    },
  })
  const deferRisk = action({
    id: 'fractional-defer-risk',
    sequence: 3,
    type: 'SET_LEVERAGE',
    symbol: second.symbol,
    parameters: { leverage: 10 },
  })
  deferRisk.trigger = { type: 'VIRTUAL_TIME', atSecond: 2 }
  const input = scenario({
    instruments: [selected, second],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'fractional-open',
        symbol: selected.symbol,
        marginMode: 'ISOLATED',
        quantity: '0.524288',
      }),
      partialClose,
      deferRisk,
    ],
  })

  const outcome = calculate({
    scenario: input,
    ticks: [
      tick(1, [
        quote(selected, '100'),
        quote(second, '100'),
      ]),
      tick(2, [
        quote(selected, '1'),
        quote(second, '100'),
      ]),
    ],
  })
  const reduced = outcome.result.snapshots[1]

  assert.deepEqual(
    {
      isolatedMargin: perpetualPosition(reduced, selected.symbol).isolatedMargin,
      wallet: wallet(reduced),
      settlement: reduced.ledgerProjection.slice(-2),
    },
    {
      isolatedMargin: '5.24287000000000000262144',
      wallet: {
        asset: 'USDT',
        available: '94.7046912',
        locked: '5.24287000000000000262144',
        total: '99.94756120000000000262144',
      },
      settlement: [
        {
          actionId: 'fractional-partial-close',
          asset: 'USDT',
          type: 'PERPETUAL_TRADE_CASH',
          amount: '-0.00000999999999999737856',
          balanceAfter: '99.94756120000000000262144',
        },
        {
          actionId: 'fractional-partial-close',
          asset: 'USDT',
          type: 'BANKRUPTCY_SHORTFALL',
          amount: '0.00008900100000000262144',
          balanceAfter: '99.94756120000000000262144',
        },
      ],
    },
  )
})

test('rejects an ordinary action that depends on same-Tick funding', () => {
  const selected = instrument()
  const funding = action({
    id: 'phase-funding-first',
    sequence: 2,
    type: 'APPLY_FUNDING',
    parameters: {},
  })
  const dependent = openLong({
    id: 'phase-must-be-after-funding',
    sequence: 3,
    marginMode: 'ISOLATED',
  })
  dependent.trigger = {
    type: 'AFTER_ACTION',
    actionId: funding.id,
    delaySeconds: 0,
  }
  const scenarioInput = scenario({
    instruments: [selected],
    initialUsdt: '100',
    marginMode: 'ISOLATED',
    timeline: [
      openLong({
        id: 'phase-open-before-funding',
        marginMode: 'ISOLATED',
      }),
      funding,
      dependent,
    ],
  })
  const input = {
    scenario: scenarioInput,
    market: {
      virtualStart: VIRTUAL_START,
      ticks: [
        tick(1, [quote(selected, '100')]),
        tick(
          2,
          [quote(selected, '100')],
          [{ symbol: selected.symbol, rate: '0.001' }],
        ),
      ],
    },
  }
  const before = structuredClone(input)

  const outcome = calculateScenario(input)

  assert.equal(outcome.status, 'BLOCKED')
  if (outcome.status === 'BLOCKED') {
    assert.deepEqual(
      outcome.issues.map((candidate) => candidate.code),
      ['ORACLE_ACTION_PHASE_DEPENDENCY_INVALID'],
    )
    assert.equal('result' in outcome, false)
  }
  assert.deepEqual(input, before)
})

test('reserves system action ids for synthetic Oracle evidence', () => {
  const selected = instrument()
  const input = scenario({
    instruments: [selected],
    timeline: [
      openLong({
        id: 'system:risk:2',
        marginMode: 'ISOLATED',
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
  if (outcome.status === 'BLOCKED') {
    assert.deepEqual(
      outcome.issues.map((candidate) => candidate.code),
      ['ORACLE_ACTION_ID_RESERVED'],
    )
    assert.equal('result' in outcome, false)
  }
})
