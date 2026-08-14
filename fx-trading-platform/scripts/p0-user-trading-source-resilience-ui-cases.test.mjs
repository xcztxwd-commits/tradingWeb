import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import * as cases from './p0-user-trading-source-resilience-ui-cases.mjs'

const source = await readFile(
  new URL('./p0-user-trading-source-resilience-ui-cases.mjs', import.meta.url),
  'utf8'
)

const expectedHandlers = [
  'runRes01',
  'runRes02',
  'runRes03',
  'runRes04',
  'runSource01',
  'runSource02',
  'runSource03',
  'runSource04',
  'runUi01',
  'runUi02'
]

test('exports the ten real SOURCE, RES and UI handlers', () => {
  assert.deepEqual(Object.keys(cases.CASE_HANDLERS).toSorted(), expectedHandlers)
  for (const name of expectedHandlers) {
    assert.equal(typeof cases[name], 'function', name)
    assert.equal(cases.CASE_HANDLERS[name], cases[name], name)
  }
})

test('source journeys prove provider binding, bundle, trade and recovery evidence', () => {
  for (const token of [
    'binance',
    'binance-usdm',
    'okx',
    'okx-swap',
    'local-spot',
    'local-perp',
    'PUBLIC_EXTERNAL',
    'LOCAL_SIMULATED',
    'providerBindings',
    'snapshotMarket',
    'providerCode',
    'sourceMode',
    'data-source',
    'market-source-change-notice',
    'MARKET_SOURCE_CHANGED'
  ]) assert.match(source, new RegExp(token), token)

  assert.match(source, /submitOrderViaUi/)
  assert.match(source, /Trade provider must match the pre-submit authority bundle/)
  assert.match(source, /registerFixtureRestore/)
  assert.doesNotMatch(source, /marketOverride[\s\S]{0,400}(?:SOURCE-01|SOURCE-02|source primary|source failover)/)
})

test('resilience journeys use captured UI mutations, exact replay and owned restart', () => {
  for (const token of [
    'rawRequest',
    'idempotencyKey',
    'replayProbes',
    'same fingerprint',
    'different fingerprint',
    'restartBackend',
    'duringDowntime',
    'assertOwnedPorts',
    'snapshotFrames',
    'tradingStateFingerprint'
  ]) assert.match(source, new RegExp(token), token)

  assert.match(source, /withCapturedMutation/)
  assert.match(source, /page\.allowHttpError/)
  assert.doesNotMatch(source, /context\.api\.user\([^\n]+method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]/)
})

test('UI journeys use real desktop and mobile pages plus dialog keyboard behavior', () => {
  for (const token of [
    '1440',
    '900',
    '390',
    '844',
    'mobile: true',
    'role="dialog"',
    'aria-modal',
    'Escape',
    'activeElement',
    'symbol',
    'status',
    'from',
    'to',
    'current-symbol'
  ]) assert.match(source, new RegExp(token), token)

  assert.match(source, /createEvidencePage/)
  assert.match(source, /loginViaUi/)
  assert.match(source, /openTradePanel\([\s\S]*mobile:\s*true/)
  assert.match(source, /captureCheckpoint/)
  assert.doesNotMatch(source, /installBrowserSession|fx-trade-confirm-skip|smoke-usdt-demo-browser/)
})

test('UI-01 keeps desktop and mobile evidence on one user identity', () => {
  const start = source.indexOf('export function runUi01')
  const end = source.indexOf('export function runUi02')
  assert(start >= 0 && end > start, 'runUi01 source body')
  const body = source.slice(start, end)
  assert.match(body, /runSingleUserCoreCase\(context, definition, details, runViewportParityJourney\)/u)
  assert.doesNotMatch(body, /runIndependentSubruns/u)
  assert.match(
    source,
    /const oco = await pendingOcoCancel[\s\S]{0,500}snapshot = oco\.snapshot/u,
    'UI-01 target actions must advance the final account snapshot'
  )
})

test('authority bundle contract rejects empty or mixed market evidence', () => {
  const metadata = {
    providerCode: 'local-perp',
    providerSymbol: 'BTCUSDT',
    sourceMode: 'LOCAL_SIMULATED',
    asOf: '2026-08-14T00:00:00.000Z',
    expiresAt: '2026-08-14T00:01:00.000Z',
    stale: false
  }
  const complete = {
    quote: { ...metadata, bid: 100, ask: 101, mid: 100.5 },
    depth: { ...metadata, bids: [[100, 1]], asks: [[101, 1]] },
    trades: [{ ...metadata, id: 'trade-1', price: 100.5, amount: 1 }],
    reference: {
      ...metadata,
      bid: 100,
      ask: 101,
      last: 100.5,
      mark: 100.5,
      index: 100.5
    },
    candles: [{ ...metadata, timestamp: 1, open: 100, high: 101, low: 99, close: 100.5 }]
  }
  assert.doesNotThrow(() => cases.assertAuthorityBundleContract(complete, {
    symbol: 'BTCUSDT-PERP',
    providers: ['local-perp'],
    sourceMode: 'LOCAL_SIMULATED'
  }))
  assert.throws(() => cases.assertAuthorityBundleContract({ ...complete, trades: [] }, {
    symbol: 'BTCUSDT-PERP', providers: ['local-perp'], sourceMode: 'LOCAL_SIMULATED'
  }), /trades/u)
  assert.throws(() => cases.assertAuthorityBundleContract({
    ...complete,
    depth: { ...complete.depth, providerCode: 'okx-swap' }
  }, {
    symbol: 'BTCUSDT-PERP', providers: ['local-perp'], sourceMode: 'LOCAL_SIMULATED'
  }), /provider/u)
  assert.throws(() => cases.assertAuthorityBundleContract({
    ...complete,
    candles: [{ ...complete.candles[0], providerCode: 'okx-swap' }]
  }, {
    symbol: 'BTCUSDT-PERP', providers: ['local-perp'], sourceMode: 'LOCAL_SIMULATED'
  }), /candle provider/u)
})

test('LOCAL_SIMULATED authority covers every trade including cleanup trades', () => {
  const local = [
    { id: 'spot', symbol: 'BTCUSDT', providerCode: 'local-spot', sourceMode: 'LOCAL_SIMULATED' },
    { id: 'perp', symbol: 'BTCUSDT-PERP', providerCode: 'local-perp', sourceMode: 'LOCAL_SIMULATED' }
  ]
  assert.doesNotThrow(() => cases.assertLocalTradeAuthority(local))
  assert.throws(() => cases.assertLocalTradeAuthority([
    ...local,
    { id: 'cleanup', symbol: 'BTCUSDT-PERP', providerCode: 'okx-swap', sourceMode: 'PUBLIC_EXTERNAL' }
  ]), /cleanup.*local-perp/u)
})

test('replay conflict contract accepts only an explicit idempotency conflict', () => {
  assert.doesNotThrow(() => cases.assertReplayConflict({
    status: 409,
    parsedResponse: { code: 'IDEMPOTENCY_CONFLICT' }
  }, 'reset', 'IDEMPOTENCY_CONFLICT'))
  assert.doesNotThrow(() => cases.assertReplayConflict({
    status: 409,
    parsedResponse: { data: { code: 'BATCH_REQUEST_CONFLICT' } }
  }, 'batch', 'BATCH_REQUEST_CONFLICT'))
  assert.throws(() => cases.assertReplayConflict({
    status: 400,
    parsedResponse: { code: 'VALIDATION_FAILED' }
  }, 'order', 'DUPLICATE_CLIENT_ORDER_ID'), /DUPLICATE_CLIENT_ORDER_ID/u)
  assert.throws(() => cases.assertReplayConflict({
    status: 409,
    parsedResponse: { code: 'IDEMPOTENCY_CONFLICT' }
  }, 'batch', 'BATCH_REQUEST_CONFLICT'), /BATCH_REQUEST_CONFLICT/u)
})

test('cancel and reset replays mutate their durable fingerprint fields', () => {
  const cancel = cases.mutateExpectedOrderIds({
    accountId: 'account-1',
    requestId: 'request-1',
    expectedOrderIds: ['00000000-0000-4000-8000-000000000001']
  })
  assert.equal(cancel.expectedOrderIds.length, 2)
  assert(cancel.expectedOrderIds.includes('00000000-0000-4000-8000-000000000001'))

  assert.deepEqual(
    cases.mutateExpectedDemoGeneration({ requestId: 'request-2', expectedDemoGeneration: 7 }),
    { requestId: 'request-2', expectedDemoGeneration: 8 }
  )
})

test('race error allowance is closed on method, URL, cursor window and code', () => {
  const requests = [
    request(11, 15, 'POST', 'http://api/api/trading/orders/cancel-all', 409, 'ORDER_NOT_CANCELLABLE'),
    request(12, 16, 'GET', 'http://api/api/trading/orders/cancel-all', 409, 'ORDER_NOT_CANCELLABLE'),
    request(13, 17, 'POST', 'http://api/api/trading/other', 409, 'ORDER_NOT_CANCELLABLE'),
    request(14, 18, 'POST', 'http://api/api/trading/orders/cancel-all', 409, 'UNKNOWN')
  ]
  const allowed = []
  const page = {
    p0Evidence: { requests },
    allowHttpError: (requestId) => allowed.push(requestId)
  }
  assert.throws(() => cases.allowExpectedRaceErrors(page, {
    start: 10,
    end: 20,
    requests: [{ method: 'POST', url: /\/api\/trading\/orders\/cancel-all$/u }],
    reason: 'race'
  }), /unexpected race response/u)
  assert.deepEqual(allowed, ['request-11'])
  assert.throws(() => cases.allowExpectedRaceErrors({
    p0Evidence: { requests: [requests[0]] },
    allowHttpError() {}
  }, {
    start: 10,
    end: 20,
    requests: [{ method: 'POST', url: /\/api\/trading\/orders\/cancel-all$/u }],
    expectedCodes: ['BATCH_REQUEST_CONFLICT'],
    reason: 'fill winner'
  }), /unexpected race response/u)
})

test('filtered record contract rejects foreign and wrong-product rows', () => {
  assert.doesNotThrow(() => cases.assertFilteredRecordIds({
    visibleIds: ['a', 'b'],
    expectedIds: ['a', 'b'],
    foreignIds: ['foreign'],
    label: 'orders'
  }))
  assert.throws(() => cases.assertFilteredRecordIds({
    visibleIds: ['a'],
    expectedIds: ['a', 'b'],
    foreignIds: [],
    label: 'orders'
  }), /every filtered record/u)
  assert.throws(() => cases.assertFilteredRecordIds({
    visibleIds: ['foreign'],
    expectedIds: ['a'],
    foreignIds: ['foreign'],
    label: 'orders'
  }), /foreign/u)
  assert.throws(() => cases.assertFilteredRecordIds({
    visibleIds: ['wrong-product'],
    expectedIds: ['a'],
    foreignIds: [],
    label: 'orders'
  }), /unexpected/u)
})

test('position history row fingerprints must match every filtered API record', () => {
  const expected = [{
    symbol: 'BTCUSDT-PERP', side: 'BUY', lots: '0.01', openPrice: '63000',
    realizedPnl: '1.25', marginHeld: '0', status: 'CLOSED'
  }]
  assert.doesNotThrow(() => cases.assertPositionRowViews(expected, expected, 'positions'))
  assert.throws(() => cases.assertPositionRowViews([], expected, 'positions'), /every filtered position/u)
  assert.throws(() => cases.assertPositionRowViews([
    { ...expected[0], status: 'OPEN' }
  ], expected, 'positions'), /every filtered position/u)
  assert.equal(
    cases.requirePositionHistoryTarget({ positionHistory: [{ id: 'position-1' }] }, 'UI-02').id,
    'position-1'
  )
  assert.throws(
    () => cases.requirePositionHistoryTarget({ positionHistory: [] }, 'UI-02'),
    /Perp position history/u
  )
})

test('UI-02 creates Perp history before asserting position filters', () => {
  const start = source.indexOf('async function runHistoryAccessibilityJourney')
  const end = source.indexOf('async function assertRealtimeAndPolling')
  assert(start >= 0 && end > start, 'UI-02 journey source body')
  const body = source.slice(start, end)
  assert.match(body, /const perp = await perpRoundTrip/u)
  assert.match(body, /snapshot = perp\.snapshot/u)
  assert.match(body, /requirePositionHistoryTarget\(snapshot/u)
})

test('required subruns use independent identities and preserve BLOCKED when merged', async () => {
  const identities = []
  const selections = []
  const writes = []
  const context = {
    run: { artifactRoot: '/artifacts' },
    userFactory: (seed) => {
      identities.push(seed)
      return { username: seed }
    },
    evidence: {
      captureCheckpoint() {},
      writeCaseResultAtomic(path, result) {
        writes.push({ path, result })
        return result
      }
    }
  }
  const definition = {
    id: 'RES-TEST',
    requiredSubruns: [
      { id: 'first', profile: 'UI_CORE', viewport: 'desktop' },
      { id: 'second', profile: 'ORDER_TRIGGER', viewport: 'mobile' }
    ]
  }
  const runSingle = async (subContext, selected) => {
    selections.push(selected.requiredSubruns.map(({ id }) => id))
    subContext.userFactory(selected.id)
    const subrun = selected.requiredSubruns[0]
    const result = {
      id: selected.id,
      status: subrun.id === 'second' ? 'BLOCKED' : 'PASS',
      scopeComplete: true,
      startedAt: `2026-08-14T00:00:0${subrun.id === 'first' ? 1 : 2}.000Z`,
      finishedAt: `2026-08-14T00:00:0${subrun.id === 'first' ? 2 : 3}.000Z`,
      subruns: [{ ...subrun, status: subrun.id === 'second' ? 'BLOCKED' : 'PASS' }],
      artifactHashes: {},
      cleanup: { status: 'PASS' },
      failureOrBlocker: subrun.id === 'second'
        ? { status: 'BLOCKED', reasonCode: 'PUBLIC_PROVIDER_UNAVAILABLE' }
        : undefined
    }
    subContext.evidence.writeCaseResultAtomic('/intercepted', result)
    return result
  }
  const merged = await cases.runIndependentSubruns(
    context,
    definition,
    {},
    () => {},
    { kind: 'INDEPENDENT' },
    runSingle
  )
  assert.deepEqual(selections, [['first'], ['second']])
  assert.deepEqual(identities, ['RES-TEST-first', 'RES-TEST-second'])
  assert.equal(merged.status, 'BLOCKED')
  assert.deepEqual(merged.subruns.map(({ status }) => status), ['PASS', 'BLOCKED'])
  assert.equal(writes.at(-1).path.replaceAll('\\', '/'), '/artifacts/RES-TEST/result.json')
})

test('race DB contract locks every winning close order to one trade and fee', () => {
  const db = {
    orderRows: [
      { id: 'protection', product_type: 'LINEAR_PERP', status: 'CANCELED', hold_amount: '0' },
      { id: 'manual-close', product_type: 'LINEAR_PERP', status: 'FILLED', hold_amount: '0' }
    ],
    tradeRows: [{
      id: 'trade-close', order_id: 'manual-close', realized_pnl: '1.25'
    }],
    assetLedgerRows: [],
    cashLedgerRows: [
      { id: 'fee-close', entry_type: 'TRADE_FEE', reference_type: 'TRADE', reference_id: 'trade-close' },
      { id: 'pnl-close', entry_type: 'TRADE_PNL', reference_type: 'POSITION', reference_id: 'position', amount: '1.25' }
    ],
    positionRows: [{ id: 'position', quantity: '0' }]
  }
  assert.doesNotThrow(() => cases.assertRaceDbContract(db, {
    label: 'manual winner',
    orderIds: ['protection', 'manual-close'],
    positionId: 'position'
  }))
  assert.throws(() => cases.assertRaceDbContract({
    ...db,
    cashLedgerRows: [...db.cashLedgerRows, { ...db.cashLedgerRows[0], id: 'duplicate-fee' }]
  }, {
    label: 'manual winner',
    orderIds: ['protection', 'manual-close'],
    positionId: 'position'
  }), /fee ledger cardinality/u)
  assert.throws(() => cases.assertRaceDbContract({
    ...db,
    cashLedgerRows: [...db.cashLedgerRows, { ...db.cashLedgerRows[1], id: 'duplicate-pnl' }]
  }, {
    label: 'manual winner',
    orderIds: ['protection', 'manual-close'],
    positionId: 'position'
  }), /realized PnL ledger cardinality/u)
})

test('Spot fill/cancel race uses asset ledger and an exact batch winner item', () => {
  const filled = {
    orderRows: [{
      id: 'spot-order', product_type: 'CRYPTO_SPOT', side: 'BUY',
      status: 'FILLED', hold_amount: '0'
    }],
    tradeRows: [{
      id: 'spot-trade', order_id: 'spot-order', side: 'BUY', lots: '0.001',
      price: '50000', fee: '0.05', fee_asset: 'USDT'
    }],
    assetLedgerRows: [
      { id: 'lock', entry_type: 'SPOT_ORDER_LOCK', reference_type: 'ORDER', reference_id: 'spot-order', asset: 'USDT', amount: '-51' },
      { id: 'debit', entry_type: 'SPOT_BUY_DEBIT', reference_type: 'TRADE', reference_id: 'spot-trade', asset: 'USDT', amount: '-50' },
      { id: 'credit', entry_type: 'SPOT_BUY_CREDIT', reference_type: 'TRADE', reference_id: 'spot-trade', asset: 'BTC', amount: '0.001' },
      { id: 'fee', entry_type: 'TRADE_FEE', reference_type: 'TRADE', reference_id: 'spot-trade', asset: 'USDT', amount: '-0.05' },
      { id: 'release', entry_type: 'ORDER_RELEASE', reference_type: 'ORDER', reference_id: 'spot-order', asset: 'USDT', amount: '0.95' }
    ],
    cashLedgerRows: [],
    positionRows: []
  }
  assert.doesNotThrow(() => cases.assertRaceDbContract(filled, {
    label: 'spot fill winner', orderIds: ['spot-order']
  }))
  assert.throws(() => cases.assertRaceDbContract({
    ...filled,
    tradeRows: []
  }, { label: 'spot fill winner', orderIds: ['spot-order'] }), /filled order must trade exactly once/u)
  assert.throws(() => cases.assertRaceDbContract({
    ...filled,
    assetLedgerRows: filled.assetLedgerRows.filter(({ entry_type }) => entry_type !== 'ORDER_RELEASE')
  }, { label: 'spot fill winner', orderIds: ['spot-order'] }), /Spot ledger/u)

  const canceled = {
    orderRows: [{ id: 'spot-order', product_type: 'CRYPTO_SPOT', status: 'CANCELED', hold_amount: '0' }],
    tradeRows: [],
    assetLedgerRows: [
      { id: 'lock', entry_type: 'SPOT_ORDER_LOCK', reference_type: 'ORDER', reference_id: 'spot-order', asset: 'USDT', amount: '-51' },
      { id: 'release', entry_type: 'SPOT_ORDER_RELEASE', reference_type: 'ORDER', reference_id: 'spot-order', asset: 'USDT', amount: '51' }
    ],
    cashLedgerRows: [],
    positionRows: []
  }
  assert.doesNotThrow(() => cases.assertRaceDbContract(canceled, {
    label: 'spot cancel winner', orderIds: ['spot-order']
  }))
  assert.doesNotThrow(() => cases.assertCancelRaceWinner({
    parsedResponse: { data: { items: [{
      positionId: null,
      orderId: 'spot-order',
      status: 'CANCELED',
      errorCode: null,
      message: null
    }] } }
  }, 'spot-order', 'spot cancel winner'))
  assert.throws(() => cases.assertCancelRaceWinner({
    parsedResponse: { data: { items: [{ orderId: 'spot-order', status: 'FAILED', errorCode: 'ORDER_NOT_CANCELLABLE' }] } }
  }, 'spot-order', 'spot cancel winner'), /must cancel/u)
})

test('stale pending contract rejects a trade after the target price is crossed', () => {
  const db = {
    orderRows: [{ id: 'pending', status: 'WORKING' }],
    tradeRows: []
  }
  assert.doesNotThrow(() => cases.assertStalePendingDbContract(db, 'pending'))
  assert.throws(() => cases.assertStalePendingDbContract({
    ...db,
    tradeRows: [{ id: 'unexpected', order_id: 'pending' }]
  }, 'pending'), /must not trade/u)
})

test('stale market rejection preserves the complete durable trading state', () => {
  const before = {
    accountRow: { id: 'account', balance: '100' },
    walletRows: [{ id: 'wallet', available: '90', locked: '10' }],
    orderRows: [{ id: 'pending', status: 'WORKING' }],
    orderEventRows: [{ id: 'event', order_id: 'pending', event_type: 'ORDER_PENDING' }],
    tradeRows: [],
    positionRows: [],
    spotPositionRows: [],
    assetLedgerRows: [{ id: 'lock', reference_id: 'pending' }],
    cashLedgerRows: []
  }
  assert.doesNotThrow(() => cases.assertStaleTradingStateUnchanged(before, structuredClone(before)))
  assert.throws(() => cases.assertStaleTradingStateUnchanged(before, {
    ...structuredClone(before),
    walletRows: [{ id: 'wallet', available: '89', locked: '10' }]
  }), /must not mutate walletRows/u)
})

test('polling fallback rejects bootstrap timing and request storms', () => {
  const request = { requestId: 'poll-1' }
  assert.equal(cases.assertPollingFallbackWindow({
    baselineAtMs: 1_000,
    observedAtMs: 14_000,
    requests: [request],
    label: 'summary'
  }), request)
  assert.throws(() => cases.assertPollingFallbackWindow({
    baselineAtMs: 1_000,
    observedAtMs: 2_000,
    requests: [request],
    label: 'summary'
  }), /too early/u)
  assert.throws(() => cases.assertPollingFallbackWindow({
    baselineAtMs: 1_000,
    observedAtMs: 14_000,
    requests: [request, { requestId: 'poll-2' }],
    label: 'summary'
  }), /exactly one/u)
})

test('backend outage UI requires a visible failure and selected account tab', () => {
  const state = {
    statusText: 'Backend order failed: Failed to fetch',
    tabLabel: 'Order history',
    tabSelected: true
  }
  assert.equal(cases.assertBackendOutageUiState(state), state)
  assert.throws(() => cases.assertBackendOutageUiState({
    ...state,
    statusText: 'Order submitted'
  }), /visible backend failure/u)
  assert.throws(() => cases.assertBackendOutageUiState({
    ...state,
    tabSelected: false
  }), /account tab/u)
})

test('public provider BLOCKED accepts only a concrete Node API failure or fallback', () => {
  assert.deepEqual(cases.publicProviderProbeFailure('binance', {
    error: { status: 400, code: 'MARKET_DATA_UNAVAILABLE' }
  }), {
    provider: 'binance', status: 400, code: 'MARKET_DATA_UNAVAILABLE', asOf: null
  })
  assert.deepEqual(cases.publicProviderProbeFailure('binance', {
    error: {
      message: 'authority bundle timeout',
      cause: { status: 400, code: 'MARKET_DATA_STALE' }
    }
  }), {
    provider: 'binance', status: 400, code: 'MARKET_DATA_STALE', asOf: null
  })
  assert.equal(cases.publicProviderProbeFailure('binance', {
    error: { status: 503, code: 'HTTP_503' }
  }), null)
  assert.equal(cases.publicProviderProbeFailure('binance', {
    error: { name: 'TypeError' }
  }), null)
  assert.deepEqual(cases.publicProviderProbeFailure('binance', {
    quote: { providerCode: 'local-spot', asOf: '2026-08-14T00:00:00.000Z' }
  }), {
    provider: 'binance', status: 200, code: 'FALLBACK_SELECTED',
    asOf: '2026-08-14T00:00:00.000Z'
  })
  assert.equal(cases.publicProviderProbeFailure('binance', {
    error: { status: 400, code: 'VALIDATION_FAILED' }
  }), null)
  assert.equal(cases.publicProviderProbeFailure('binance', {
    quote: { providerCode: 'binance', asOf: '2026-08-14T00:00:00.000Z' }
  }), null)
})

function request(cursor, responseCursor, method, url, status, code) {
  return {
    cursor,
    responseCursor,
    requestId: `request-${cursor}`,
    method,
    url,
    response: { status },
    responseBody: JSON.stringify({ code })
  }
}
