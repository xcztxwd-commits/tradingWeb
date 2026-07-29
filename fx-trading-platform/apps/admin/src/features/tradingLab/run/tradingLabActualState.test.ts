import assert from 'node:assert/strict'
import test from 'node:test'

import {
  TRADING_LAB_ACTUAL_STATE_GROUPS,
  TRADING_LAB_ACTUAL_STATE_PAGE_SIZE,
  createTradingLabActualStateUnavailable,
  formatTradingLabActualStateValue,
  paginateTradingLabActualStateRows,
  projectTradingLabActualState,
} from './tradingLabActualState.ts'

const CHECKPOINT_ID = '17'
const VIRTUAL_TIME = '2026-07-25T00:00:17.000Z'

test('projects exactly eight authoritative groups into one deeply immutable snapshot', () => {
  const input = checkpoint()
  const result = projectTradingLabActualState(input)

  assert.equal(result.availability, 'AVAILABLE')
  if (result.availability !== 'AVAILABLE') return
  assert.deepEqual(TRADING_LAB_ACTUAL_STATE_GROUPS, [
    'summary',
    'walletBalances',
    'assetLedger',
    'cashLedger',
    'orders',
    'trades',
    'positions',
    'fundingSettlements',
  ])
  assert.equal(result.sourceEventId, CHECKPOINT_ID)
  assert.equal(result.tickSequence, 17)
  assert.equal(result.virtualTime, VIRTUAL_TIME)
  assert.equal(result.correlationId, 'checkpoint-17')
  assert.deepEqual(Object.keys(result.state), TRADING_LAB_ACTUAL_STATE_GROUPS)
  assert.deepEqual(result.state.summary, {
    accountId: 'account-1',
    equity: '1000',
  })
  assert.equal(result.state.orders.length, 1)
  assert.equal(
    (result.state.orders[0] as { readonly orderId: string }).orderId,
    'order-1',
  )
  assert.equal(Object.isFrozen(result), true)
  assert.equal(Object.isFrozen(result.state), true)
  assert.equal(Object.isFrozen(result.state.orders), true)
  assert.equal(Object.isFrozen(result.state.orders[0]), true)

  const mutableState = (
    input.data as { payload: { state: { orders: Array<{ orderId: string }> } } }
  ).payload.state
  mutableState.orders[0]!.orderId = 'mutated-after-projection'
  assert.equal(
    (result.state.orders[0] as { readonly orderId: string }).orderId,
    'order-1',
  )
})

test('rejects malformed roots, custom prototypes, non-finite values, and wrong group shapes', () => {
  const missingGroup = state()
  Reflect.deleteProperty(missingGroup, 'positions')
  const extraGroup = { ...state(), localExpected: {} }
  const wrongSummary = { ...state(), summary: [] }
  const wrongRows = { ...state(), trades: {} }
  const nonFinite = {
    ...state(),
    summary: { equity: Number.POSITIVE_INFINITY },
  }
  const prototyped = state()
  prototyped.summary = Object.assign(
    Object.create({ inherited: 'must-not-survive' }),
    { equity: '1000' },
  )

  for (const malformed of [
    missingGroup,
    extraGroup,
    wrongSummary,
    wrongRows,
    nonFinite,
    prototyped,
  ]) {
    const result = projectTradingLabActualState(checkpoint(malformed))
    assert.equal(result.availability, 'UNAVAILABLE')
    if (result.availability === 'UNAVAILABLE') {
      assert.equal(result.reason, 'MALFORMED_CHECKPOINT')
      assert.equal(result.sourceEventId, CHECKPOINT_ID)
    }
  }
})

test('rejects malformed checkpoint identity and time without inventing metadata', () => {
  for (const malformed of [
    { ...checkpoint(), id: '-1' },
    { ...checkpoint(), name: 'tick' },
    {
      ...checkpoint(),
      data: {
        ...(checkpoint().data as object),
        virtualTime: '2026-07-25T00:00:17+00:00',
      },
    },
    {
      ...checkpoint(),
      data: {
        ...(checkpoint().data as object),
        payload: {
          tickSequence: 0,
          state: state(),
        },
      },
    },
  ]) {
    const result = projectTradingLabActualState(malformed)
    assert.equal(result.availability, 'UNAVAILABLE')
    if (result.availability === 'UNAVAILABLE') {
      assert.equal(result.reason, 'MALFORMED_CHECKPOINT')
    }
  }
})

test('paginates immutable rows in local pages of at most 50', () => {
  assert.equal(TRADING_LAB_ACTUAL_STATE_PAGE_SIZE, 50)
  const rows = Object.freeze(
    Array.from({ length: 121 }, (_, index) => Object.freeze({ index })),
  )

  const first = paginateTradingLabActualStateRows(rows, 1)
  const second = paginateTradingLabActualStateRows(rows, 2)
  const last = paginateTradingLabActualStateRows(rows, 999)

  assert.deepEqual(
    {
      page: first.page,
      pageCount: first.pageCount,
      totalRows: first.totalRows,
      firstIndex: first.rows[0]?.index,
      lastIndex: first.rows.at(-1)?.index,
      length: first.rows.length,
    },
    {
      page: 1,
      pageCount: 3,
      totalRows: 121,
      firstIndex: 0,
      lastIndex: 49,
      length: 50,
    },
  )
  assert.equal(second.rows.length, 50)
  assert.equal(second.rows[0]?.index, 50)
  assert.equal(last.page, 3)
  assert.equal(last.rows.length, 21)
  assert.equal(Object.isFrozen(first.rows), true)
})

test('returns explicit no-checkpoint state and bounds individual display values', () => {
  const result = createTradingLabActualStateUnavailable('NO_CHECKPOINT')
  assert.deepEqual(result, {
    availability: 'UNAVAILABLE',
    reason: 'NO_CHECKPOINT',
    sourceEventId: null,
    tickSequence: null,
    virtualTime: null,
  })
  assert.equal(
    formatTradingLabActualStateValue('x'.repeat(600)).length,
    513,
  )
  assert.match(
    formatTradingLabActualStateValue({ nested: true }),
    /"nested":true/u,
  )
})

function checkpoint(snapshot: unknown = state()) {
  return {
    id: CHECKPOINT_ID,
    name: 'checkpoint',
    data: {
      virtualTime: VIRTUAL_TIME,
      correlationId: 'checkpoint-17',
      payload: {
        tickSequence: 17,
        state: snapshot,
      },
    },
  }
}

function state(): {
  summary: Record<string, unknown>
  walletBalances: unknown[]
  assetLedger: unknown[]
  cashLedger: unknown[]
  orders: unknown[]
  trades: unknown[]
  positions: unknown[]
  fundingSettlements: unknown[]
  [key: string]: unknown
} {
  return {
    summary: {
      accountId: 'account-1',
      equity: '1000',
    },
    walletBalances: [{ asset: 'USDT', balance: '1000' }],
    assetLedger: [],
    cashLedger: [],
    orders: [{ orderId: 'order-1' }],
    trades: [],
    positions: [],
    fundingSettlements: [],
  }
}
