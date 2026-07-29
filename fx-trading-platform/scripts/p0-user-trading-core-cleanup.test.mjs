import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  assertCoreCleanup,
  tradingStateFingerprint
} from './p0-user-trading-core-cases.mjs'

const cleanSnapshot = () => ({
  wallets: [{
    walletType: 'SPOT',
    asset: 'USDT',
    total: '50000.00000000',
    available: '50000.00000000',
    locked: '0.00000000'
  }],
  summary: {
    balance: '50000.00000000',
    equity: '50000.00000000',
    usedMargin: '0.00000000',
    freeMargin: '50000.00000000',
    maintenanceMargin: '0.00000000'
  },
  orders: [{
    id: 'order-1',
    symbol: 'BTCUSDT',
    side: 'BUY',
    orderType: 'MARKET',
    status: 'FILLED',
    requestedQuantity: '0.00100000',
    filledQuantity: '0.00100000',
    remainingQuantity: '0.00000000',
    price: '60000.00000000',
    holdAmount: '0.00000000'
  }],
  trades: [{
    id: 'trade-1',
    orderId: 'order-1',
    symbol: 'BTCUSDT',
    side: 'BUY',
    lots: '0.00100000',
    price: '60000.00000000',
    fee: '0.00000050',
    feeAsset: 'BTC',
    realizedPnl: '0.00000000'
  }],
  positions: [{
    id: 'position-1',
    instrumentType: 'SPOT',
    symbol: 'BTCUSDT',
    status: 'CLOSED',
    lots: '0.00000000',
    openPrice: '60000.00000000',
    floatingPnl: '0.00000000',
    realizedPnl: '1.00000000',
    version: 2
  }],
  positionHistory: [{
    id: 'closed-position-1',
    symbol: 'BTCUSDT-PERP',
    side: 'LONG',
    status: 'CLOSED',
    lots: '0.00100000',
    openPrice: '60000.00000000',
    closePrice: '60100.00000000',
    realizedPnl: '0.10000000',
    marginHeld: '0.00000000'
  }],
  settings: {
    positionMode: 'ONE_WAY',
    symbols: [{
      symbol: 'BTCUSDT-PERP',
      leverage: 10,
      marginMode: 'CROSS',
      quantityUnit: 'BASE'
    }]
  },
  transfers: [{
    transferId: 'transfer-1',
    direction: 'SPOT_TO_PERP',
    asset: 'USDT',
    amount: '100.00000000',
    status: 'COMPLETED'
  }],
  fundingSettlements: [{
    id: 'funding-1',
    symbol: 'BTCUSDT-PERP',
    fundingRate: '0.00010000',
    markPrice: '60000.00000000',
    positionSize: '0.00100000',
    amount: '-0.00600000'
  }],
  assetLedger: [{
    id: 'asset-ledger-1',
    operationType: 'SPOT_BUY_DEBIT',
    referenceType: 'TRADE',
    referenceId: 'trade-1',
    asset: 'USDT',
    amount: '-60.00000000',
    balanceAfter: '49940.00000000'
  }],
  cashLedger: [{
    id: 'cash-ledger-1',
    operationType: 'TRADE_FEE',
    referenceType: 'TRADE',
    referenceId: 'trade-1',
    amount: '-0.03000000',
    balanceAfter: '49999.97000000'
  }]
})

const cleanDb = () => ({
  openPositions: 0,
  walletRows: [{
    wallet_type: 'SPOT',
    asset: 'USDT',
    total: '50000.00000000',
    available: '50000.00000000',
    locked: '0.00000000'
  }],
  accountRow: {
    balance: '50000.00000000',
    equity: '50000.00000000',
    used_margin: '0.00000000',
    free_margin: '50000.00000000'
  },
  orderRows: [{
    id: 'order-1',
    status: 'FILLED',
    hold_amount: '0.00000000'
  }],
  positionRows: [{
    id: 'position-1',
    status: 'CLOSED',
    margin_held: '0.00000000'
  }]
})

test('core cleanup reconciles REST and DB balances and clears every hold', () => {
  assert.doesNotThrow(() => assertCoreCleanup('CLEAN', cleanSnapshot(), cleanDb()))

  for (const [label, mutate, expected] of [
    ['REST locked', (snapshot) => { snapshot.wallets[0].locked = '1' }, /locked/],
    ['REST wallet sum', (snapshot) => { snapshot.wallets[0].total = '49999' }, /total/],
    ['REST wallet negative', (snapshot) => { snapshot.wallets[0].total = '-1' }, /non-negative/],
    ['REST order hold evidence', (snapshot) => { delete snapshot.orders[0].holdAmount }, /hold evidence/],
    ['REST used margin', (snapshot) => { snapshot.summary.usedMargin = '1' }, /used margin/],
    ['REST equity', (snapshot) => { snapshot.summary.equity = '49999' }, /equity/],
    ['REST free margin', (snapshot) => { snapshot.summary.freeMargin = '49999' }, /free margin/],
    ['DB active order', (_snapshot, db) => { db.orderRows[0].status = 'PENDING' }, /DB active orders/],
    ['DB order hold', (_snapshot, db) => { db.orderRows[0].hold_amount = '1' }, /DB order hold/],
    ['DB order hold evidence', (_snapshot, db) => { delete db.orderRows[0].hold_amount }, /hold evidence/],
    ['DB open position', (_snapshot, db) => {
      db.openPositions = 1
      db.positionRows[0].status = 'OPEN'
    }, /DB open positions/],
    ['DB position hold evidence', (_snapshot, db) => {
      delete db.positionRows[0].margin_held
    }, /hold evidence/],
    ['DB wallet hold', (_snapshot, db) => {
      db.walletRows[0].available = '49999'
      db.walletRows[0].locked = '1'
    }, /DB .*locked/],
    ['DB summary', (_snapshot, db) => { db.accountRow.free_margin = '49999' }, /DB free margin/]
  ]) {
    const snapshot = cleanSnapshot()
    const db = cleanDb()
    mutate(snapshot, db)
    assert.throws(() => assertCoreCleanup(label, snapshot, db), expected, label)
  }
})

test('rejection fingerprint covers every mutable trading collection field', () => {
  const baseline = cleanSnapshot()
  const fingerprint = tradingStateFingerprint(baseline)
  for (const [label, mutate] of [
    ['wallet', (snapshot) => { snapshot.wallets[0].available = '49999' }],
    ['summary', (snapshot) => { snapshot.summary.balance = '49999' }],
    ['order', (snapshot) => { snapshot.orders[0].filledQuantity = '0.00090000' }],
    ['trade', (snapshot) => { snapshot.trades[0].fee = '0.00000040' }],
    ['position', (snapshot) => { snapshot.positions[0].realizedPnl = '2' }],
    ['position history', (snapshot) => { snapshot.positionHistory[0].closePrice = '60200' }],
    ['settings', (snapshot) => { snapshot.settings.symbols[0].leverage = 20 }],
    ['transfer', (snapshot) => { snapshot.transfers[0].amount = '101' }],
    ['funding', (snapshot) => { snapshot.fundingSettlements[0].amount = '-0.007' }],
    ['asset ledger', (snapshot) => { snapshot.assetLedger[0].balanceAfter = '49939' }],
    ['cash ledger', (snapshot) => { snapshot.cashLedger[0].amount = '-0.04' }]
  ]) {
    const mutated = structuredClone(baseline)
    mutate(mutated)
    assert.notDeepEqual(
      tradingStateFingerprint(mutated),
      fingerprint,
      `${label} business mutation must change the fingerprint`
    )
  }
})

test('rejection fingerprint ignores incidental object and row ordering', () => {
  const baseline = cleanSnapshot()
  const reordered = {
    ...structuredClone(baseline),
    wallets: [...baseline.wallets].reverse(),
    settings: {
      symbols: [...baseline.settings.symbols].reverse(),
      positionMode: baseline.settings.positionMode
    }
  }
  assert.deepEqual(
    tradingStateFingerprint(reordered),
    tradingStateFingerprint(baseline)
  )
})

test('core case runner restores registered market fixtures before nominal PASS', () => {
  const source = readFileSync(
    new URL('./p0-user-trading-core-cases.mjs', import.meta.url),
    'utf8'
  )
  const section = (start, end) => source.slice(
    source.indexOf(start),
    source.indexOf(end, source.indexOf(start))
  )
  const runner = section(
    'async function runSingleUserCoreCase',
    'async function runCoreCase'
  )
  assert.match(runner, /registerFixtureRestore/)
  assert.match(runner, /await restoreAll\(\)[\s\S]*return persistPass/)
  assert.match(runner, /p0CleanupFailure/)
  assert.match(
    section('async function runCoreCase', 'async function runAuthCase'),
    /fixtureActions:[\s\S]*cleanup:\s*\{\s*status:\s*'FAIL'\s*\}/
  )
  assert.match(
    section(
      'async function prepareSpotAuthorityMarket',
      'function assertSingleFullFillMutation'
    ),
    /registerFixtureRestore/
  )
  assert.match(
    section('async function applyAuthorityMark', 'async function waitForMarket'),
    /registerFixtureRestore/
  )
})
