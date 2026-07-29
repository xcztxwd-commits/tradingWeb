import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import * as orderCases from './p0-user-trading-order-cases.mjs'

const source = await readFile(
  new URL('./p0-user-trading-order-cases.mjs', import.meta.url),
  'utf8'
)

test('exports the first six real ORDER_TRIGGER handlers', () => {
  const expected = [
    'runPerp10',
    'runPerp11',
    'runSpot04',
    'runSpot05',
    'runSpot06',
    'runSpot07'
  ]
  assert.deepEqual(
    Object.keys(orderCases.CASE_HANDLERS).toSorted(),
    expected
  )
  for (const name of expected) {
    assert.equal(typeof orderCases[name], 'function', name)
    assert.equal(orderCases.CASE_HANDLERS[name], orderCases[name], name)
  }
})

test('pending Spot journeys prove UI requests, holds, REST/DB/STOMP and cleanup', () => {
  for (const token of [
    'submitOrderViaUi',
    'withCapturedMutation',
    '/orders',
    '[data-order-id',
    'marketOverride',
    'snapshotTradingRows',
    'snapshotFrames',
    'spotOrderHoldOracle',
    'walletBalanceOracle',
    'SPOT_ORDER_LOCK',
    'SPOT_ORDER_RELEASE',
    'runSingleUserCoreCase',
    'registerFixtureRestore'
  ]) {
    assert.match(source, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  assert.match(source, /modified\.order\.id,\s*pending\.order\.id/u)
  assert.match(source, /Number\(modified\.order\.version\) > Number\(pending\.order\.version\)/u)
  assert.match(source, /liquidityRole,\s*'MAKER'/u)
  assert.match(source, /triggerPriceType,\s*'LAST_PRICE'/u)
  assert.match(source, /feeAsset,\s*'BTC'/u)
  assert.match(source, /feeAsset,\s*'USDT'/u)
})

test('Perp LIMIT and STOP journeys prove full fills, no mutation and financial ledgers', () => {
  for (const token of [
    'desktop-trigger',
    'ORDER_NOT_MODIFIABLE',
    'perpOpeningHoldOracle',
    'perpCloseOracle',
    'ORDER_HOLD',
    'ORDER_RELEASE',
    'MARGIN_HOLD',
    'MARGIN_RELEASE',
    'MARK_PRICE',
    'reduceOnly: true',
    'positionHistory'
  ]) {
    assert.match(source, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  assert.match(source, /side:\s*'SELL'[\s\S]*side:\s*'BUY'/u)
  assert.match(source, /assertTradingStateEqual/u)
  assert.match(source, /waitForAccount/u)
  assert.doesNotMatch(source, /BACKEND_CONTRACT_ONLY|TODO|stub|placeholder/iu)
})
