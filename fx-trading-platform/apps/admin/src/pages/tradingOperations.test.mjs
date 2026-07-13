import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))

function source(relativePath) {
  const absolutePath = join(currentDir, relativePath)
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const accountDetailSource = source('AccountDetailPage.tsx')
const fundingSettlementsSource = source('FundingSettlementsPage.tsx')
const fundingConfigSource = source('FundingConfigPage.tsx')
const fundingFallbackModelSource = source('fundingFallbackModel.ts')
const tradingApiSource = source('../services/adminTradingApi.ts')

describe('admin demo trading operation pages', () => {
  it('shows every authoritative account section from the parallel detail loader', () => {
    assert.match(accountDetailSource, /useParams/)
    assert.match(accountDetailSource, /loadAccountTradingDetail/)

    for (const section of [
      '现货 / Spot 余额',
      '永续 / Perp 余额',
      '订单',
      '成交',
      '持仓',
      '资金费结算',
      '账户划转',

      '资产流水',
      '资金流水'
    ]) {
      assert.ok(accountDetailSource.includes(section), `${section} section is missing`)
    }

    assert.match(tradingApiSource, /Promise\.all\(/)
    assert.match(tradingApiSource, /referenceId/)
    assert.match(tradingApiSource, /paired/i)
    assert.match(accountDetailSource, /rows=\{detail\.assetLedger\}/)
  })

  it('loads funding settlements for all visible accounts in parallel', () => {
    assert.match(fundingSettlementsSource, /getAccountsPage/)
    assert.match(fundingSettlementsSource, /getAccountFundingSettlements/)
    assert.match(fundingSettlementsSource, /Promise\.all\(/)
    assert.match(fundingSettlementsSource, /loadAllAdminPages/)
    assert.match(fundingSettlementsSource, /accountId/)

    for (const field of ['symbol', 'fundingRate', 'amount', 'source', 'fundingTime']) {
      assert.match(fundingSettlementsSource, new RegExp(field))
    }
  })

  it('renders only fields exposed by the Admin trade projection', () => {
    assert.doesNotMatch(accountDetailSource, /row\.fee/)
    for (const field of ['row.lots', 'row.price', 'row.realizedPnl', 'row.executedAt']) {
      assert.match(accountDetailSource, new RegExp(field.replace('.', '\\.')))
    }
  })

  it('loads and edits funding configuration for every linear perpetual symbol', () => {
    assert.match(fundingConfigSource, /getSymbolsPage/)
    assert.match(fundingConfigSource, /productType === 'LINEAR_PERP'/)
    assert.match(fundingConfigSource, /Promise\.all\(/)
    assert.match(fundingConfigSource, /loadAllAdminPages/)
    assert.match(fundingConfigSource, /getFundingConfig/)
    assert.match(fundingConfigSource, /updateFundingConfig/)
    assert.match(fundingConfigSource, /formatFundingFallbackState/)
    assert.match(fundingFallbackModelSource, /config\.fallbackReason/)
    assert.match(fundingFallbackModelSource, /config\.sourceMode/)
    assert.match(fundingFallbackModelSource, /FALLBACK/)
    assert.match(fundingFallbackModelSource, /PRIMARY/)

    for (const field of [
      'fundingSourcePriority',
      'fixedFundingRate',
      'fixedFundingIntervalMinutes',
      'fundingStaleSeconds',
      'actualSource',
      'asOf',
      'nextFundingTime'
    ]) {
      assert.match(fundingConfigSource, new RegExp(field))
    }

    assert.match(fundingConfigSource, /实际选择源/)
    assert.match(fundingConfigSource, /回退状态/)
    assert.match(fundingConfigSource, /上次 asOf/)
    assert.match(fundingConfigSource, /下次资金费时间/)
    assert.match(fundingConfigSource, /!form\.fixedFundingRate\.trim\(\)/)
  })

  it('wires distinct cleanup and reset dialogs to real audit ids and refreshes the account', () => {
    assert.match(accountDetailSource, /HighRiskActionDialog/)
    assert.match(accountDetailSource, /forceCleanupAccount/)
    assert.match(accountDetailSource, /resetDemoAccountAsAdmin/)
    assert.match(accountDetailSource, /getAuditIdByRequestId/)
    assert.match(accountDetailSource, /CONFIRM_FORCE_CLEANUP/)
    assert.match(accountDetailSource, /CONFIRM_DEMO_RESET/)
    assert.match(accountDetailSource, /cleanupBlockers/)
    assert.match(accountDetailSource, /highRiskTarget/)
    assert.match(accountDetailSource, /target\.accountId/)
    assert.match(accountDetailSource, /currentAccountIdRef\.current/)
    assert.match(accountDetailSource, /target\.accountId === currentAccountIdRef\.current/)
    assert.match(accountDetailSource, /setHighRiskTarget\(null\)/)
    assert.match(accountDetailSource, /loadAllAdminPages/)
    for (const status of [
      'RECEIVED', 'VALIDATING', 'ACCEPTED', 'PENDING_ACTIVATION',
      'PENDING', 'WORKING', 'PARTIALLY_FILLED', 'CANCEL_PENDING'
    ]) {
      assert.match(accountDetailSource, new RegExp(`'${status}'`))
    }
    assert.match(accountDetailSource, /account\.accountType !== 'DEMO'/)
    assert.match(accountDetailSource, /account\.status !== 'ACTIVE'/)
    assert.match(accountDetailSource, /await reload\(\)/)
    assert.doesNotMatch(accountDetailSource, /auditId:\s*request\.requestId/)
  })

  it('keeps loading, error and empty states visible instead of inventing admin data', () => {
    for (const pageSource of [accountDetailSource, fundingSettlementsSource, fundingConfigSource]) {
      assert.match(pageSource, /loading/)
      assert.match(pageSource, /error/)
      assert.match(pageSource, /暂无/)
    }
  })
})
