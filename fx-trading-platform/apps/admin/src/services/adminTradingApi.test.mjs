import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const apiPath = join(currentDir, 'adminTradingApi.ts')
const apiSource = existsSync(apiPath) ? readFileSync(apiPath, 'utf8') : ''

function expectAuthenticatedExport(functionName) {
  assert.match(
    apiSource,
    new RegExp(`export (?:async )?function ${functionName}\\([^)]*token: string[^)]*\\)`),
    `${functionName} must require an Admin bearer token`
  )
}

describe('admin trading API contracts', () => {
  it('loads every account detail section from authenticated /api/admin routes', () => {
    for (const functionName of [
      'getAccountWalletBalances',
      'getAccountAssetLedger',
      'getAccountLedger',
      'getAccountOrders',
      'getAccountPositions',
      'getAccountTrades',
      'getAccountFundingSettlements'
    ]) {
      expectAuthenticatedExport(functionName)
    }

    for (const route of [
      '/api/admin/accounts/${encodeURIComponent(accountId)}/wallet-balances',
      '/api/admin/accounts/${encodeURIComponent(accountId)}/asset-ledger',
      '/api/admin/accounts/${encodeURIComponent(accountId)}/ledger',
      '/api/admin/accounts/${encodeURIComponent(accountId)}/funding-settlements',
      '/api/admin/trading/orders?',
      '/api/admin/trading/positions?',
      '/api/admin/trading/trades?'
    ]) {
      assert.ok(apiSource.includes(route), `${route} is missing`)
    }
    assert.match(apiSource, /filter\.accountId=\$\{encodeURIComponent\(accountId\)\}/)
    assert.doesNotMatch(apiSource, /&accountId=\$\{encodeURIComponent\(accountId\)\}/)
  })

  it('loads account detail sections in parallel and derives transfers from paired ledgers', () => {
    expectAuthenticatedExport('loadAccountTradingDetail')
    assert.match(apiSource, /Promise\.all\(\[/)
    for (const section of [
      'walletBalances',
      'orders',
      'positions',
      'trades',
      'fundingSettlements',
      'assetLedger',
      'ledger',
      'transfers'
    ]) {
      assert.match(apiSource, new RegExp(`\\b${section}\\b`), `${section} detail section is missing`)
    }
    assert.match(apiSource, /export function aggregateAccountTransfers\(/)
    assert.match(apiSource, /referenceType !== 'TRANSFER'/)
    assert.match(apiSource, /SPOT_TO_PERP/)
    assert.match(apiSource, /PERP_TO_SPOT/)
    assert.match(apiSource, /loadAllAdminPages\(\(page, size\) => getAccountOrders/)
    assert.match(apiSource, /loadAllAdminPages\(\(page, size\) => getAccountPositions/)
    assert.match(apiSource, /loadAllAdminPages\(\(page, size\) => getAccountTrades/)
  })

  it('walks every backend page instead of hiding old active blockers after the first 100 rows', () => {
    expectAuthenticatedExport('getAccountOrders')
    assert.match(apiSource, /export async function loadAllAdminPages/)
    assert.match(apiSource, /firstPage\.totalPages/)
    assert.match(apiSource, /Array\.from\(\{ length: totalPages - 1 \}/)
    assert.match(apiSource, /Promise\.all\(remainingPages\.map/)
  })

  it('uses the backend funding settlement and funding config contracts', () => {
    for (const functionName of [
      'getAccountFundingSettlements',
      'getFundingConfig',
      'updateFundingConfig'
    ]) {
      expectAuthenticatedExport(functionName)
    }

    assert.match(
      apiSource,
      /apiGet<AdminFundingConfigResponse>\(\s*`\/api\/admin\/market\/symbols\/\$\{encodeURIComponent\(symbolId\)\}\/funding-config`,\s*token\s*\)/
    )
    assert.match(
      apiSource,
      /apiPut<AdminFundingConfigResponse>\(\s*`\/api\/admin\/market\/symbols\/\$\{encodeURIComponent\(symbolId\)\}\/funding-config`,\s*payload,\s*token\s*\)/
    )
  })

  it('keeps force cleanup and reset separate with exact authenticated payloads', () => {
    for (const functionName of ['forceCleanupAccount', 'resetDemoAccountAsAdmin']) {
      expectAuthenticatedExport(functionName)
    }

    assert.match(
      apiSource,
      /apiPost<BatchActionResponse>\(\s*`\/api\/admin\/accounts\/\$\{encodeURIComponent\(accountId\)\}\/force-cleanup`,\s*payload,\s*token\s*\)/
    )
    assert.match(
      apiSource,
      /apiPost<DemoResetResponse>\(\s*`\/api\/admin\/accounts\/\$\{encodeURIComponent\(accountId\)\}\/demo-reset`,\s*payload,\s*token\s*\)/
    )
    assert.match(apiSource, /payload: AdminAccountCleanupRequest/)
    assert.match(apiSource, /payload: AdminDemoResetRequest/)
    assert.match(apiSource, /reason: string/)
    assert.match(apiSource, /requestId: string/)
    assert.match(apiSource, /confirmationText: string/)
  })

  it('resolves the real audit record id for a completed high-risk request', () => {
    expectAuthenticatedExport('getAuditIdByRequestId')
    assert.match(apiSource, /apiGet<AdminPage<AdminAuditLog>>\('\/api\/admin\/audit-logs\?page=0&size=100', token\)/)
    assert.match(apiSource, /item\.requestId === requestId/)
    assert.match(apiSource, /return match\.id/)
    assert.doesNotMatch(apiSource, /auditId:\s*requestId/)
  })

  it('reuses generated backend schemas instead of duplicating wire contracts', () => {
    for (const typeName of [
      'AdminFundingConfigRequest',
      'AdminFundingConfigResponse',
      'AdminAccountCleanupRequest',
      'AdminDemoResetRequest',
      'BatchActionResponse',
      'DemoResetResponse',
      'WalletBalanceResponse',
      'AssetLedgerEntryResponse',
      'FundingSettlement'
    ]) {
      assert.match(apiSource, new RegExp(`\\b${typeName}\\b`), `${typeName} schema is missing`)
    }
    assert.match(apiSource, /from '@fx-platform\/shared-types'/)
  })
})
