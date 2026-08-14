import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import * as advanced from './p0-user-trading-order-advanced-cases.mjs'
import { marketFillOracle, perpCloseOracle } from './p0-user-trading-oracles.mjs'

const {
  assertAdminCleanupFinancials,
  assertBatchReleaseRows,
  assertCleanupItems,
  assertDbReplayUnchanged,
  assertPerpCloseFinancials,
  assertResetLedgerContract,
  expectedProtectionResize,
  protectionBusinessFingerprint,
  runIndependentSubruns
} = advanced.__testables ?? {}

const source = await readFile(
  new URL('./p0-user-trading-order-advanced-cases.mjs', import.meta.url),
  'utf8'
)

const EXPECTED_HANDLERS = [
  'runBatch01',
  'runLife01',
  'runLife03',
  'runProt01',
  'runProt02',
  'runProt03',
  'runProt04',
  'runProt05',
  'runProt06',
  'runSpot08',
  'runSpot09',
  'runSpot10',
  'runSpot11',
  'runWallet02'
]

test('exports every remaining ORDER_TRIGGER handler', () => {
  assert.deepEqual(
    Object.keys(advanced.CASE_HANDLERS).toSorted(),
    EXPECTED_HANDLERS
  )
  for (const name of EXPECTED_HANDLERS) {
    assert.equal(typeof advanced[name], 'function', name)
    assert.equal(advanced.CASE_HANDLERS[name], advanced[name], name)
  }
})

test('handlers select one owned-user lifecycle or the independent-subrun driver', () => {
  for (const name of [
    'runBatch01',
    'runLife03',
    'runProt01',
    'runProt05',
    'runProt06',
    'runSpot08',
    'runSpot09'
  ]) {
    assert.match(String(advanced[name]), /runSingleUserCoreCase\(/u, name)
  }
  for (const name of [
    'runLife01',
    'runProt02',
    'runProt03',
    'runProt04',
    'runSpot10',
    'runSpot11',
    'runWallet02'
  ]) {
    assert.match(String(advanced[name]), /runIndependentSubruns\(/u, name)
  }
  assert.doesNotMatch(source, /BACKEND_CONTRACT_ONLY|TODO|stub|placeholder/iu)
})

test('OCO journeys prove shared hold, one winner, replay, stale and recovery', () => {
  for (const token of [
    '/api/trading/oco',
    'contingencyGroupId',
    'spotOrderHoldOracle',
    'OCO_PRICE_RELATION_INVALID',
    'OCO_ORDER_NOT_MODIFIABLE',
    'providerBindings',
    'replayCapturedMutation',
    'ORDER_FILLED',
    'ORDER_CANCELED'
  ]) {
    assert.match(source, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  assert.match(source, /groupTrades\.length,\s*1/u)
  assert.match(source, /winner\.liquidityRole,\s*expectedLiquidity/u)
})

test('batch, protection, wallet and lifecycle journeys keep exact mutation contracts', () => {
  for (const token of [
    'cancelAllOrdersViaUi',
    'items',
    'attachedProtections',
    '/protections/',
    'PROTECTION_LIMIT_EXCEEDED',
    'PROTECTION_QUANTITY_EXCEEDED',
    'PROTECTION_VERSION_CONFLICT',
    'TRANSFER_AMOUNT_UNAVAILABLE',
    'TRANSFER_REQUEST_CONFLICT',
    'DEMO_RESET_BLOCKED',
    'force-cleanup',
    'resetDemoViaUi',
    'assertTradingStateEqual'
  ]) {
    assert.match(source, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  for (const token of [
    'createAuthenticatedPeer',
    'SAME_USER_REAL_SECOND_TAB',
    'L7_PROTECTION_LIMIT_EXCEEDED',
    'assertSpotExecutionFinancials',
    'assertPerpCloseFinancials',
    'assertAdminCleanupFinancials'
  ]) {
    assert.match(source, new RegExp(token))
  }
  const authenticatedPeer = source.slice(
    source.indexOf('async function createAuthenticatedPeer'),
    source.indexOf('async function closeAuthenticatedPeer')
  )
  assert.match(authenticatedPeer, /createEvidencePage\(scope\.browser/u)
  assert.doesNotMatch(authenticatedPeer, /launchBrowser/u)
})

test('multi-path cases execute every subrun with a fresh identity and isolated definition', async () => {
  assert.equal(typeof runIndependentSubruns, 'function')
  const writes = []
  const users = []
  const calls = []
  const context = {
    run: { artifactRoot: 'artifacts' },
    userFactory(seed) {
      users.push(seed)
      return { seed }
    },
    evidence: {
      captureCheckpoint(_context, _name, scope) {
        return scope
      },
      writeCaseResultAtomic(path, result) {
        writes.push({ path, result })
        return result
      }
    }
  }
  const definition = {
    id: 'MULTI-01',
    requiredSubruns: [
      { id: 'desktop-a', profile: 'ORDER_TRIGGER', viewport: 'desktop' },
      { id: 'desktop-b', profile: 'ORDER_TRIGGER', viewport: 'desktop' }
    ]
  }
  const fakeRunSingle = async (subContext, subDefinition, _details, execute) => {
    const subrun = subDefinition.requiredSubruns[0]
    calls.push(subrun.id)
    subContext.userFactory(definition.id)
    subContext.evidence.captureCheckpoint({}, 'checkpoint', {})
    await execute({ definition: subDefinition })
    const result = {
      id: definition.id,
      status: 'PASS',
      startedAt: `2026-01-01T00:00:0${calls.length}.000Z`,
      finishedAt: `2026-01-01T00:00:0${calls.length}.100Z`,
      artifactHashes: { [`${subrun.id}.json`]: subrun.id },
      subruns: [{ ...subrun, status: 'PASS' }],
      userActions: [],
      fixtureActions: [],
      contractProbes: [],
      replayProbes: [],
      checkpoints: [],
      uiEvidence: [],
      networkEvidence: [],
      apiEvidence: [],
      dbEvidence: [],
      eventEvidence: [],
      oracleEvidence: [],
      snapshots: {},
      consoleErrors: []
    }
    subContext.evidence.writeCaseResultAtomic('intercepted', result)
    return result
  }

  const result = await runIndependentSubruns(
    context,
    definition,
    {},
    async (_scope, subrun) => calls.push(`journey:${subrun.id}`),
    { kind: 'MULTI_PATH' },
    fakeRunSingle
  )

  assert.deepEqual(calls, [
    'desktop-a',
    'journey:desktop-a',
    'desktop-b',
    'journey:desktop-b'
  ])
  assert.deepEqual(users, ['MULTI-01-desktop-a', 'MULTI-01-desktop-b'])
  assert.deepEqual(result.subruns.map(({ id }) => id), ['desktop-a', 'desktop-b'])
  assert.equal(writes.length, 1)
  assert.match(writes[0].path, /MULTI-01[\\/]result\.json$/u)
})

test('protection resize oracle pins oldest-first quantities, status and version', () => {
  assert.equal(typeof expectedProtectionResize, 'function')
  const before = [
    { id: 'a', protectionType: 'TAKE_PROFIT', quantity: '0.03', status: 'PENDING_ACTIVATION', version: 1, createdAt: '1' },
    { id: 'b', protectionType: 'TAKE_PROFIT', quantity: '0.03', status: 'PENDING_ACTIVATION', version: 1, createdAt: '2' },
    { id: 'c', protectionType: 'TAKE_PROFIT', quantity: '0.04', status: 'PENDING_ACTIVATION', version: 1, createdAt: '3' }
  ]
  assert.deepEqual(expectedProtectionResize(before, 0.05), [
    { id: 'a', quantity: '0.03', status: 'PENDING_ACTIVATION', version: 1, changed: false },
    { id: 'b', quantity: '0.02', status: 'PENDING_ACTIVATION', version: 2, changed: true },
    { id: 'c', quantity: '0', status: 'EXPIRED', version: 2, changed: true }
  ])
})

test('Admin cleanup items cover every expected order and position exactly once', () => {
  assert.equal(typeof assertCleanupItems, 'function')
  const items = [
    { orderId: 'o1', positionId: null, status: 'CANCELED', errorCode: null, message: null },
    { orderId: 'o2', positionId: null, status: 'CANCELED', errorCode: null, message: null },
    {
      orderId: '00000000-0000-4000-8000-000000000001',
      positionId: 'p1',
      status: 'FILLED',
      errorCode: null,
      message: null
    }
  ]
  assert.doesNotThrow(() => assertCleanupItems(items, ['o1', 'o2'], ['p1'], 'test'))
  assert.throws(
    () => assertCleanupItems([...items, items[0]], ['o1', 'o2'], ['p1'], 'test'),
    /exact item/u
  )
})

test('replay DB oracle rejects any wallet, ledger, trading or batch delta', () => {
  assert.equal(typeof assertDbReplayUnchanged, 'function')
  const before = {
    walletRows: [{ id: 'w1', total: 1 }],
    orderRows: [],
    orderEventRows: [],
    tradeRows: [],
    positionRows: [],
    assetLedgerRows: [{ id: 'a1' }],
    cashLedgerRows: [],
    batchActionRows: [{ id: 'b1' }]
  }
  assert.doesNotThrow(() => assertDbReplayUnchanged(before, structuredClone(before), 'test'))
  const changed = structuredClone(before)
  changed.cashLedgerRows.push({ id: 'duplicate' })
  assert.throws(() => assertDbReplayUnchanged(before, changed, 'test'), /cashLedgerRows/u)
})

test('batch release oracle requires one exact release per held order', () => {
  assert.equal(typeof assertBatchReleaseRows, 'function')
  const orders = [{ id: 'spot', holdAmount: '2' }, { id: 'perp', holdAmount: '3' }]
  const before = { assetLedgerRows: [], cashLedgerRows: [] }
  const after = {
    assetLedgerRows: [{ id: 'a', entry_type: 'SPOT_ORDER_RELEASE', reference_id: 'spot', amount: '2' }],
    cashLedgerRows: [{ id: 'c', operation_type: 'ORDER_RELEASE', reference_id: 'perp', amount: '3' }]
  }
  assert.doesNotThrow(() => assertBatchReleaseRows(before, after, orders, 'test'))
  after.assetLedgerRows.push({ id: 'duplicate', entry_type: 'SPOT_ORDER_RELEASE', reference_id: 'spot', amount: '2' })
  assert.throws(() => assertBatchReleaseRows(before, after, orders, 'test'), /exact release/u)
})

test('partial Perp close oracle proves fill, fee, PnL, balance and durable rows', () => {
  assert.equal(typeof assertPerpCloseFinancials, 'function')
  const rules = { tickSize: '0.01', stepSize: '0.001' }
  const executionMarket = { quote: { bid: '110', ask: '111' } }
  const fillPrice = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: 'SELL',
    bid: executionMarket.quote.bid,
    ask: executionMarket.quote.ask
  }).filledPrice
  const oracle = perpCloseOracle({
    side: 'LONG',
    quantity: '0.03',
    entryPrice: '100',
    closeFillPrice: fillPrice,
    rules
  })
  const realizedAfter = String(1 + Number(oracle.grossRealizedPnl))
  const balanceAfter = String(1000
    + Number(oracle.grossRealizedPnl)
    - Number(oracle.closeFee))
  const position = {
    id: 'position',
    side: 'BUY',
    lots: '0.10',
    openPrice: '100',
    marginHeld: '100',
    realizedPnl: '1'
  }
  const trade = {
    id: 'trade',
    orderId: 'close-order',
    side: 'SELL',
    lots: '0.03',
    price: fillPrice,
    realizedPnl: oracle.grossRealizedPnl,
    fee: oracle.closeFee,
    feeAsset: 'USDT',
    liquidityRole: 'TAKER'
  }
  const before = {
    summary: { balance: '1000' },
    positions: [position],
    positionHistory: []
  }
  const afterPosition = {
    ...position,
    lots: '0.07',
    marginHeld: '70',
    realizedPnl: realizedAfter,
    status: 'OPEN'
  }
  const after = {
    summary: { balance: balanceAfter },
    positions: [afterPosition],
    positionHistory: []
  }
  const beforeDb = { cashLedgerRows: [] }
  const afterDb = {
    accountRow: { id: 'account', balance: balanceAfter },
    orderRows: [{ id: trade.orderId, status: 'FILLED' }],
    tradeRows: [{
      id: trade.id,
      order_id: trade.orderId,
      side: trade.side,
      lots: trade.lots,
      price: trade.price,
      realized_pnl: trade.realizedPnl,
      fee: trade.fee,
      fee_asset: trade.feeAsset,
      liquidity_role: trade.liquidityRole
    }],
    positionRows: [{
      id: position.id,
      lots: afterPosition.lots,
      margin_held: afterPosition.marginHeld,
      realized_pnl: afterPosition.realizedPnl,
      status: 'OPEN'
    }],
    cashLedgerRows: [
      {
        id: 'margin',
        entry_type: 'MARGIN_RELEASE',
        operation_type: 'MARGIN_RELEASE',
        reference_type: 'POSITION',
        reference_id: position.id,
        amount: '30'
      },
      {
        id: 'pnl',
        entry_type: 'TRADE_PNL',
        operation_type: 'TRADE_PNL',
        reference_type: 'POSITION',
        reference_id: position.id,
        amount: oracle.grossRealizedPnl
      },
      {
        id: 'fee',
        entry_type: 'TRADE_FEE',
        operation_type: 'TRADE_FEE',
        reference_type: 'TRADE',
        reference_id: trade.id,
        amount: `-${oracle.closeFee}`
      }
    ]
  }

  const evidence = assertPerpCloseFinancials({
    before,
    beforeDb,
    after,
    afterDb,
    position,
    trade,
    quantity: '0.03',
    expectClosed: false,
    rules,
    executionMarket,
    expectedLiquidity: 'TAKER',
    label: 'partial'
  })
  assert.deepEqual(evidence.ledger.toSorted(), ['fee', 'margin', 'pnl'])

  const wrong = structuredClone(afterDb)
  wrong.tradeRows[0].realized_pnl = '999'
  assert.throws(() => assertPerpCloseFinancials({
    before,
    beforeDb,
    after,
    afterDb: wrong,
    position,
    trade,
    quantity: '0.03',
    expectClosed: false,
    rules,
    executionMarket,
    expectedLiquidity: 'TAKER',
    label: 'partial'
  }), /DB realized PnL/u)
})

test('Admin cleanup proves exact non-zero PnL ledger and pending-order release wiring', () => {
  assert.equal(typeof assertAdminCleanupFinancials, 'function')
  const rules = { tickSize: '0.01', stepSize: '0.001' }
  const market = { quote: { bid: '110', ask: '111' }, rules }
  const position = {
    id: 'position',
    symbol: 'BTCUSDT-PERP',
    side: 'BUY',
    lots: '0.02',
    openPrice: '100',
    marginHeld: '20'
  }
  const orderId = 'close-order'
  const trade = {
    id: 'trade',
    orderId,
    side: 'SELL',
    lots: position.lots,
    price: marketFillOracle({
      productType: 'LINEAR_PERP',
      side: 'SELL',
      bid: market.quote.bid,
      ask: market.quote.ask
    }).filledPrice,
    feeAsset: 'USDT',
    liquidityRole: 'TAKER'
  }
  const oracle = perpCloseOracle({
    side: 'LONG',
    quantity: position.lots,
    entryPrice: position.openPrice,
    closeFillPrice: trade.price,
    rules
  })
  trade.realizedPnl = oracle.grossRealizedPnl
  trade.fee = oracle.closeFee
  const before = { summary: { balance: '1000' } }
  const after = {
    summary: {
      balance: String(1000 + Number(oracle.grossRealizedPnl) - Number(oracle.closeFee))
    },
    orders: [{ id: orderId, origin: 'ADMIN_FORCE_CLOSE' }],
    trades: [trade],
    positionHistory: [{ id: position.id }]
  }
  const beforeDb = { cashLedgerRows: [] }
  const afterDb = {
    cashLedgerRows: [
      {
        id: 'margin',
        operation_type: 'MARGIN_RELEASE',
        reference_type: 'POSITION',
        reference_id: position.id,
        amount: position.marginHeld
      },
      {
        id: 'pnl',
        operation_type: 'TRADE_PNL',
        reference_type: 'POSITION',
        reference_id: position.id,
        amount: oracle.grossRealizedPnl
      },
      {
        id: 'fee',
        operation_type: 'TRADE_FEE',
        reference_type: 'TRADE',
        reference_id: trade.id,
        amount: `-${oracle.closeFee}`
      }
    ]
  }
  const input = {
    before,
    beforeDb,
    after,
    afterDb,
    positions: [position],
    items: [{ positionId: position.id, orderId }],
    markets: new Map([[position.symbol, market]]),
    label: 'cleanup'
  }
  const evidence = assertAdminCleanupFinancials(input)
  assert.deepEqual(evidence.ledger.toSorted(), ['fee', 'margin', 'pnl'])

  const missing = structuredClone(afterDb)
  missing.cashLedgerRows = missing.cashLedgerRows.filter(({ id }) => id !== 'pnl')
  assert.throws(
    () => assertAdminCleanupFinancials({ ...input, afterDb: missing }),
    /exact relevant cash ledger rows/u
  )
  const wrongReference = structuredClone(afterDb)
  wrongReference.cashLedgerRows.find(({ id }) => id === 'pnl').reference_id = 'wrong'
  assert.throws(
    () => assertAdminCleanupFinancials({ ...input, afterDb: wrongReference }),
    /exact TRADE_PNL ledger/u
  )
  const wrongAmount = structuredClone(afterDb)
  wrongAmount.cashLedgerRows.find(({ id }) => id === 'pnl').amount = '999'
  assert.throws(
    () => assertAdminCleanupFinancials({ ...input, afterDb: wrongAmount }),
    /TRADE_PNL amount/u
  )
  const duplicate = structuredClone(afterDb)
  duplicate.cashLedgerRows.push({ ...duplicate.cashLedgerRows[1], id: 'pnl-duplicate' })
  assert.throws(
    () => assertAdminCleanupFinancials({ ...input, afterDb: duplicate }),
    /exact relevant cash ledger rows/u
  )

  const life03 = source.slice(
    source.indexOf('async function runLife03Journey'),
    source.indexOf('async function openPerpPosition')
  )
  assert.match(life03, /assertBatchReleaseRows\(\s*beforeAdminDb,\s*cleanedDb/u)
})

test('protection fingerprint covers every business field and excludes timestamps', () => {
  assert.equal(typeof protectionBusinessFingerprint, 'function')
  const order = {
    id: 'protection',
    accountId: 'account',
    clientOrderId: 'client',
    symbol: 'BTCUSDT-PERP',
    side: 'SELL',
    orderType: 'STOP_MARKET',
    status: 'PENDING_ACTIVATION',
    lots: '0.02',
    quantity: '0.02',
    originalQuantity: '0.02',
    baseQuantity: '0.02',
    remainingQuantity: '0.02',
    price: null,
    triggerPrice: '110',
    triggerExecutionType: 'MARKET',
    protectionType: 'TAKE_PROFIT',
    parentPositionId: 'position',
    reduceOnly: true,
    version: 0,
    createdAt: 'old',
    updatedAt: 'old'
  }
  const baseline = protectionBusinessFingerprint(order)
  assert.deepEqual(
    protectionBusinessFingerprint({ ...order, createdAt: 'new', updatedAt: 'new' }),
    baseline
  )
  assert.notDeepEqual(
    protectionBusinessFingerprint({ ...order, remainingQuantity: '0.01' }),
    baseline
  )
})

test('Demo reset ledger contract preserves history and adds only request-correlated rows', () => {
  assert.equal(typeof assertResetLedgerContract, 'function')
  const before = {
    assetLedgerRows: [{ id: 'old-asset', entry_type: 'TRANSFER', amount: '1' }],
    cashLedgerRows: [{ id: 'old-cash', entry_type: 'TRADE_PNL', amount: '2' }]
  }
  const after = {
    assetLedgerRows: [
      ...structuredClone(before.assetLedgerRows),
      {
        id: 'reset-asset',
        account_id: 'account',
        entry_type: 'DEMO_RESET',
        operation_type: 'DEMO_RESET',
        reference_type: 'DEMO_RESET',
        reference_id: 'request'
      }
    ],
    cashLedgerRows: [
      ...structuredClone(before.cashLedgerRows),
      {
        id: 'reset-cash',
        account_id: 'account',
        entry_type: 'DEMO_RESET',
        operation_type: 'DEMO_RESET',
        reference_type: 'DEMO_RESET',
        reference_id: 'request'
      }
    ]
  }
  assert.deepEqual(
    assertResetLedgerContract(before, after, 'request', 'account', 'reset'),
    { assetLedgerIds: ['reset-asset'], cashLedgerId: 'reset-cash' }
  )
  const duplicate = structuredClone(after)
  duplicate.cashLedgerRows.push({ ...duplicate.cashLedgerRows.at(-1), id: 'duplicate' })
  assert.throws(
    () => assertResetLedgerContract(before, duplicate, 'request', 'account', 'reset'),
    /exactly one cash DEMO_RESET/u
  )
  const corrupted = structuredClone(after)
  corrupted.assetLedgerRows[0].amount = '999'
  assert.throws(
    () => assertResetLedgerContract(before, corrupted, 'request', 'account', 'reset'),
    /preserves asset ledger/u
  )
})

test('PROT-05, PROT-06 and LIFE-03 journeys wire the strengthened contracts', () => {
  const prot05 = source.slice(
    source.indexOf('async function runProt05Journey'),
    source.indexOf('async function runProt06Journey')
  )
  const prot06 = source.slice(
    source.indexOf('async function runProt06Journey'),
    source.indexOf('async function runWallet02Journey')
  )
  const life03 = source.slice(
    source.indexOf('async function runLife03Journey'),
    source.indexOf('async function openPerpPosition')
  )
  assert.equal(prot05.match(/assertPerpCloseFinancials\(/gu)?.length, 3)
  assert.match(prot05, /quantity:\s*'0\.03'[\s\S]*quantity:\s*'0\.02'/u)
  assert.match(prot06, /quantity:\s*'0\.015'[\s\S]*quantity:\s*'0\.01'/u)
  assert.equal(prot06.match(/exactly one PATCH/gu)?.length, 2)
  assert.match(prot06, /protectionBusinessFingerprint\(afterStale/u)
  assert.match(life03, /assertResetLedgerContract\(/u)
  assert.match(life03, /completeStateFingerprint\(afterUserAdmin/u)
  assert.match(life03, /resetAudits\.length,\s*1/u)
  assert.match(life03, /ADMIN_DEMO_RESET/u)
})
