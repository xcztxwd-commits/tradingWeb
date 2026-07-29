import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(
  new URL('./ActualStatePanel.tsx', import.meta.url),
  'utf8',
)

test('renders all eight authoritative actual-state groups and source identity', () => {
  for (const group of [
    'summary',
    'walletBalances',
    'assetLedger',
    'cashLedger',
    'orders',
    'trades',
    'positions',
    'fundingSettlements',
  ]) {
    assert.match(source, new RegExp(`['"]${group}['"]`, 'u'))
  }
  assert.match(source, /sourceEventId/u)
  assert.match(source, /tickSequence/u)
  assert.match(source, /virtualTime/u)
})

test('uses bounded local pagination without LOCAL fallback or product verdicts', () => {
  assert.match(source, /paginateTradingLabActualStateRows/u)
  assert.match(source, /TRADING_LAB_ACTUAL_STATE_PAGE_SIZE/u)
  assert.match(source, /Previous/u)
  assert.match(source, /Next/u)
  assert.doesNotMatch(source, /\bLOCAL\b/u)
  assert.doesNotMatch(source, /Pass\s*\/\s*Fail|PASS|FAIL/u)
  assert.doesNotMatch(source, /rawReport|ReportPreview|response\.(?:json|text|blob|arrayBuffer)/u)
})

test('keeps child components at module scope and avoids whole-state stringification', () => {
  assert.match(source, /^function ActualStateGroup\(/mu)
  assert.match(source, /^function ActualStateRows\(/mu)
  assert.doesNotMatch(source, /JSON\.stringify\s*\(\s*actualState/u)
  assert.doesNotMatch(source, /JSON\.stringify\s*\(\s*snapshot/u)
})
