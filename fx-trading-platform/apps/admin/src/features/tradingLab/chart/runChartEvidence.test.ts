import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_TRADING_LAB_ACTUAL_TICKS,
  TradingLabRunChartEvidenceError,
  createTradingLabRunChartEvidence,
  reduceTradingLabRunChartEvidence,
} from './runChartEvidence.ts'

test('projects only durable MARKET_TICK instruments into deeply immutable ACTUAL ticks', () => {
  const initial = createTradingLabRunChartEvidence()
  const projected = reduceTradingLabRunChartEvidence(
    initial,
    tickEvent(1, '2026-07-25T00:00:01.000Z', [
      {
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '50000.00',
        ask: '50002.00',
        last: '50001.00',
      },
      {
        productType: 'LINEAR_PERP',
        symbol: 'ETHUSDT',
        bid: '2999.10',
        ask: '3001.10',
        last: '3000.10',
        mark: '3000.20',
        index: '3000.30',
      },
    ]),
  )

  assert.equal(projected.actualTicks.length, 1)
  assert.deepEqual(projected.actualTicks[0], {
    sequence: 1,
    virtualTime: '2026-07-25T00:00:01.000Z',
    instruments: [
      {
        productType: 'CRYPTO_SPOT',
        symbol: 'BTCUSDT',
        bid: '50000.00',
        ask: '50002.00',
        last: '50001.00',
      },
      {
        productType: 'LINEAR_PERP',
        symbol: 'ETHUSDT',
        bid: '2999.10',
        ask: '3001.10',
        last: '3000.10',
        mark: '3000.20',
        index: '3000.30',
      },
    ],
    fundingRates: [],
  })
  assert.equal(projected.lastTickSequence, 1)
  assert.equal(projected.lastVirtualTime, '2026-07-25T00:00:01.000Z')
  assert.equal(Object.isFrozen(projected), true)
  assert.equal(Object.isFrozen(projected.actualTicks), true)
  assert.equal(Object.isFrozen(projected.actualTicks[0]), true)
  assert.equal(Object.isFrozen(projected.actualTicks[0]?.instruments), true)
  assert.deepEqual(projected.markers, [])
})

test('deduplicates identical Tick replay and fails closed on conflict, gaps, or nonmonotonic time', () => {
  const firstEvent = tickEvent(
    1,
    '2026-07-25T00:00:01.000Z',
    [spot('100.00')],
  )
  const first = reduceTradingLabRunChartEvidence(
    createTradingLabRunChartEvidence(),
    firstEvent,
  )
  assert.equal(reduceTradingLabRunChartEvidence(first, firstEvent), first)

  assertChartError(
    () => reduceTradingLabRunChartEvidence(
      first,
      tickEvent(1, '2026-07-25T00:00:01.000Z', [spot('101.00')]),
    ),
    'TRADING_LAB_CHART_TICK_CONFLICT',
  )
  assertChartError(
    () => reduceTradingLabRunChartEvidence(
      first,
      tickEvent(3, '2026-07-25T00:00:03.000Z', [spot('103.00')]),
    ),
    'TRADING_LAB_CHART_TICK_SEQUENCE_GAP',
  )
  assertChartError(
    () => reduceTradingLabRunChartEvidence(
      first,
      tickEvent(2, '2026-07-25T00:00:01.000Z', [spot('102.00')]),
    ),
    'TRADING_LAB_CHART_TICK_TIME_ORDER',
  )
})

test('retains at most the newest 10,000 ACTUAL ticks', () => {
  let evidence = createTradingLabRunChartEvidence()
  for (
    let sequence = 1;
    sequence <= MAX_TRADING_LAB_ACTUAL_TICKS + 1;
    sequence += 1
  ) {
    evidence = reduceTradingLabRunChartEvidence(
      evidence,
      tickEvent(
        sequence,
        new Date(Date.UTC(2026, 6, 25) + sequence * 1_000).toISOString(),
        [spot(String(100 + sequence))],
      ),
    )
  }

  assert.equal(MAX_TRADING_LAB_ACTUAL_TICKS, 10_000)
  assert.equal(evidence.actualTicks.length, 10_000)
  assert.equal(evidence.actualTicks[0]?.sequence, 2)
  assert.equal(evidence.actualTicks.at(-1)?.sequence, 10_001)
  assert.equal(evidence.lastTickSequence, 10_001)
})

test('projects deduplicated markers only from durable checkpoint trades, orders, and funding', () => {
  const checkpoint = checkpointEvent(1, {
    orders: [
      {
        id: 'order-execution',
        origin: 'USER',
        protectionType: null,
        reduceOnly: false,
      },
      {
        id: 'order-stop',
        origin: 'PROTECTIVE',
        protectionType: 'STOP_LOSS',
        reduceOnly: true,
      },
    ],
    trades: [
      trade('trade-execution', 'order-execution', '100.25'),
      trade('trade-stop', 'order-stop', '95.00'),
      { id: 'not-enough-durable-fields' },
    ],
    fundingSettlements: [
      {
        id: 'funding-1',
        symbol: 'ETHUSDT',
        fundingTime: '2026-07-25T00:00:00.500Z',
        markPrice: '3000.30',
      },
    ],
  })
  const first = reduceTradingLabRunChartEvidence(
    createTradingLabRunChartEvidence(),
    checkpoint,
  )

  assert.deepEqual(first.markers, [
    {
      id: 'trade:trade-execution',
      source: 'ACTUAL',
      kind: 'EXECUTION',
      productType: 'CRYPTO_SPOT',
      symbol: 'BTCUSDT',
      tickSequence: 1,
      virtualTime: '2026-07-25T00:00:00.750Z',
      price: '100.25',
      side: 'BUY',
      actionId: null,
    },
    {
      id: 'trade:trade-stop',
      source: 'ACTUAL',
      kind: 'STOP_LOSS',
      productType: 'CRYPTO_SPOT',
      symbol: 'BTCUSDT',
      tickSequence: 1,
      virtualTime: '2026-07-25T00:00:00.750Z',
      price: '95.00',
      side: 'BUY',
      actionId: null,
    },
    {
      id: 'funding:funding-1',
      source: 'ACTUAL',
      kind: 'FUNDING',
      productType: 'LINEAR_PERP',
      symbol: 'ETHUSDT',
      tickSequence: 1,
      virtualTime: '2026-07-25T00:00:00.500Z',
      price: '3000.30',
      side: null,
      actionId: null,
    },
  ])

  const replay = reduceTradingLabRunChartEvidence(
    first,
    checkpointEvent(2, {
      orders: checkpoint.data.payload.state.orders,
      trades: checkpoint.data.payload.state.trades,
      fundingSettlements: checkpoint.data.payload.state.fundingSettlements,
    }),
  )
  assert.deepEqual(replay.markers, first.markers)
})

test('fails closed when a durable marker identity is replayed with conflicting evidence', () => {
  const first = reduceTradingLabRunChartEvidence(
    createTradingLabRunChartEvidence(),
    checkpointEvent(1, {
      orders: [{
        id: 'order-1',
        origin: 'USER',
        protectionType: null,
        reduceOnly: false,
      }],
      trades: [trade('trade-1', 'order-1', '100.00')],
      fundingSettlements: [],
    }),
  )

  assertChartError(
    () => reduceTradingLabRunChartEvidence(
      first,
      checkpointEvent(2, {
        orders: [{
          id: 'order-1',
          origin: 'USER',
          protectionType: null,
          reduceOnly: false,
        }],
        trades: [trade('trade-1', 'order-1', '101.00')],
        fundingSettlements: [],
      }),
    ),
    'TRADING_LAB_CHART_MARKER_CONFLICT',
  )
})

test('reads complete real checkpoint pages without inventing decimal strings from JSON numbers', () => {
  const evidence = reduceTradingLabRunChartEvidence(
    createTradingLabRunChartEvidence(),
    checkpointEvent(1, {
      orders: page([{
        id: 'order-paged',
        origin: 'USER',
        protectionType: null,
        reduceOnly: false,
      }]),
      trades: page([{
        ...trade('trade-paged', 'order-paged', '100.25'),
        price: 100.25,
      }]),
      fundingSettlements: page([{
        id: 'funding-paged',
        symbol: 'ETHUSDT',
        fundingTime: '2026-07-25T00:00:00.500Z',
        markPrice: 3000.3,
      }]),
    }),
  )

  assert.deepEqual(
    evidence.markers.map((marker) => ({
      id: marker.id,
      kind: marker.kind,
      price: marker.price,
    })),
    [
      {
        id: 'trade:trade-paged',
        kind: 'EXECUTION',
        price: null,
      },
      {
        id: 'funding:funding-paged',
        kind: 'FUNDING',
        price: null,
      },
    ],
  )

  assertChartError(
    () => reduceTradingLabRunChartEvidence(
      evidence,
      checkpointEvent(2, {
        orders: page([{
          id: 'order-paged',
          origin: 'USER',
          protectionType: null,
          reduceOnly: false,
        }]),
        trades: page([{
          ...trade('trade-paged', 'order-paged', '101.25'),
          price: 101.25,
        }]),
        fundingSettlements: page([{
          id: 'funding-paged',
          symbol: 'ETHUSDT',
          fundingTime: '2026-07-25T00:00:00.500Z',
          markPrice: 3000.3,
        }]),
      }),
    ),
    'TRADING_LAB_CHART_MARKER_CONFLICT',
  )
})

function tickEvent(
  sequence: number,
  virtualTime: string,
  instruments: readonly Record<string, unknown>[],
) {
  return {
    id: String(sequence),
    name: 'tick',
    data: {
      virtualTime,
      payload: {
        tickSequence: sequence,
        instruments,
      },
    },
  } as const
}

function checkpointEvent(
  tickSequence: number,
  evidence: Readonly<{
    orders: unknown
    trades: unknown
    fundingSettlements: unknown
  }>,
) {
  return {
    id: `checkpoint-${tickSequence}`,
    name: 'checkpoint',
    data: {
      virtualTime: new Date(
        Date.UTC(2026, 6, 25) + tickSequence * 1_000,
      ).toISOString(),
      payload: {
        tickSequence,
        state: {
          orders: evidence.orders,
          trades: evidence.trades,
          fundingSettlements: evidence.fundingSettlements,
        },
      },
    },
  } as const
}

function page(items: readonly Record<string, unknown>[]) {
  return {
    items,
    page: 0,
    size: 20,
    total: items.length,
    totalPages: items.length === 0 ? 0 : 1,
    complete: true,
  } as const
}

function spot(last: string) {
  return {
    productType: 'CRYPTO_SPOT',
    symbol: 'BTCUSDT',
    bid: last,
    ask: last,
    last,
  } as const
}

function trade(id: string, orderId: string, price: string) {
  return {
    id,
    orderId,
    productType: 'CRYPTO_SPOT',
    symbol: 'BTCUSDT',
    side: 'BUY',
    price,
    executedAt: '2026-07-25T00:00:00.750Z',
  } as const
}

function assertChartError(
  action: () => unknown,
  code: TradingLabRunChartEvidenceError['code'],
): void {
  assert.throws(action, (error) => (
    error instanceof TradingLabRunChartEvidenceError
    && error.code === code
  ))
}
