import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const apiSource = readFileSync(join(currentDir, 'adminApi.ts'), 'utf8')

describe('admin page-level api client', () => {
  it('exposes page-level read functions mapped to existing Java admin APIs', () => {
    for (const functionName of [
      'getDashboardSummary',
      'getUsersPage',
      'getAccountsPage',
      'getOrdersPage',
      'getPositionsPage',
      'getTradesPage',
      'getLedgerPage',
      'getPaymentMethodsPage',
      'getSymbolsPage',
      'createSymbol',
      'getMarketStatus',
      'getRiskConfigs',
      'getMessagesPage',
      'getArticlesPage',
      'getDictionariesPage',
      'getSettingsPage',
      'getAuditLogsPage',
      'getFeaturePages',
      'getFeaturePage',
      'runFeatureAction',
      'getDataProviders',
      'createDataProvider',
      'updateDataProvider',
      'testDataProvider',
      'syncProviderInstruments',
      'getProviderInstruments',
      'getSymbolProviderBindings',
      'createSymbolProviderBinding',
      'updateSymbolProviderBinding',
      'updateSymbolDisplay',
      'getTableColumnPreference',
      'saveTableColumnPreference'
    ]) {
      assert.match(apiSource, new RegExp(`export function ${functionName}\\(`))
    }
  })

  it('keeps backend route contracts under /api/admin unchanged', () => {
    for (const path of [
      '/api/admin/dashboard/summary',
      '/api/admin/users',
      '/api/admin/accounts',
      '/api/admin/trading/orders',
      '/api/admin/trading/positions',
      '/api/admin/trading/trades',
      '/api/admin/finance/ledger',
      '/api/admin/finance/payment-methods',
      '/api/admin/market/symbols',
      "apiPost<SymbolRow>('/api/admin/market/symbols'",
      '/api/admin/market/categories',
      '/api/admin/market/price-adjustments',
      '/api/admin/market/status',
      '/api/admin/market/data-providers',
      '/api/admin/market/data-providers/${encodeURIComponent(providerId)}/test',
      '/api/admin/market/data-providers/${encodeURIComponent(providerId)}/sync-instruments',
      '/api/admin/market/data-providers/${encodeURIComponent(providerId)}/instruments',
      '/api/admin/market/symbols/${encodeURIComponent(symbolId)}/provider-bindings',
      '/api/admin/market/symbols/${encodeURIComponent(symbolId)}/display',
      '/api/admin/risk/configs',
      '/api/admin/logs/request-logs',
      '/api/admin/logs/verification-codes',
      '/api/admin/table-tools/preferences',
      '/api/admin/table-tools/export-tasks',
      '/api/admin/table-tools/import-tasks',
      '/api/admin/table-tools/batch-operations',
      '/api/admin/rbac/roles',
      '/api/admin/rbac/menus',
      '/api/admin/rbac/departments',
      '/api/admin/rbac/posts',
      '/api/admin/finance/fund-orders',
      '/api/admin/members/payment-accounts',
      '/api/admin/content/messages',
      '/api/admin/content/articles',
      '/api/admin/config/dictionaries',
      '/api/admin/config/settings',
      '/api/admin/audit-logs',
      '/api/admin/features'
    ]) {
      assert.ok(apiSource.includes(path), `${path} is missing from adminApi.ts`)
    }
  })

  it('calls the MyBatis-backed trades and risk APIs that replace placeholder pages', () => {
    assert.match(apiSource, /export function getTradesPage\(/)
    assert.match(apiSource, /export function getRiskConfigs\(/)
    assert.match(apiSource, /\/api\/admin\/trading\/trades/)
    assert.match(apiSource, /\/api\/admin\/risk\/configs/)
  })

  it('maps product category and price schedule feature pages to real business APIs', () => {
    assert.match(apiSource, /pageKey === 'product-categories'/)
    assert.match(apiSource, /pageKey === 'price-schedules'/)
    assert.match(apiSource, /\/api\/admin\/market\/categories\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/market\/price-adjustments\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/market\/symbols\/\$\{encodeURIComponent\(symbolId\)\}\/price-adjustments/)
  })

  it('sends explicit productType for product create and update actions', () => {
    assert.match(apiSource, /productType: String\(payload\.productType \?\? productTypeForAssetClass\(assetClass\)\)/)
    assert.match(apiSource, /function productTypeForAssetClass/)
    assert.match(apiSource, /key: 'productType'/)
  })

  it('maps log feature pages to real request and verification log APIs', () => {
    assert.match(apiSource, /pageKey === 'request-logs'/)
    assert.match(apiSource, /pageKey === 'verification-codes'/)
    assert.match(apiSource, /\/api\/admin\/logs\/request-logs\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/logs\/verification-codes\?\$\{featureQuery\(query\)\}/)
  })

  it('maps table settings import export and batch actions to table tool APIs', () => {
    assert.match(apiSource, /export function getTableColumnPreference\(/)
    assert.match(apiSource, /export function saveTableColumnPreference\(/)
    assert.match(apiSource, /request.action === 'export'/)
    assert.match(apiSource, /request.action === 'import'/)
    assert.match(apiSource, /request.action === 'delete'/)
    assert.match(apiSource, /\/api\/admin\/table-tools\/export-tasks/)
    assert.match(apiSource, /\/api\/admin\/table-tools\/import-tasks/)
    assert.match(apiSource, /\/api\/admin\/table-tools\/batch-operations/)
  })

  it('maps RBAC finance and member feature pages to real business APIs', () => {
    for (const key of [
      'system-roles',
      'system-menus',
      'system-departments',
      'system-posts',
      'recharge-orders',
      'withdrawal-orders',
      'members',
      'member-payment-accounts'
    ]) {
      assert.match(apiSource, new RegExp(`pageKey === '${key}'`))
    }
    assert.match(apiSource, /\/api\/admin\/rbac\/roles/)
    assert.match(apiSource, /\/api\/admin\/rbac\/menus/)
    assert.match(apiSource, /\/api\/admin\/rbac\/departments/)
    assert.match(apiSource, /\/api\/admin\/rbac\/posts/)
    assert.match(apiSource, /\/api\/admin\/finance\/fund-orders\?orderType=/)
    assert.match(apiSource, /\/api\/admin\/members\/payment-accounts\?\$\{featureQuery\(query\)\}/)
  })

  it('maps RBAC row edit delete permission and data scope actions to real APIs', () => {
    assert.match(apiSource, /apiPut<RbacRoleRow>/)
    assert.match(apiSource, /\/api\/admin\/rbac\/roles\/\$\{encodeURIComponent\(request\.rowId\)\}/)
    assert.match(apiSource, /apiDelete<null>/)
    assert.match(apiSource, /menu-permissions/)
    assert.match(apiSource, /data-scope/)
    assert.match(apiSource, /runSystemMenuAction/)
    assert.match(apiSource, /runSystemDepartmentAction/)
    assert.match(apiSource, /runSystemPostAction/)
  })

  it('maps remaining WH screenshot feature pages to real Java APIs', () => {
    for (const key of [
      'products',
      'finance-ledger',
      'payment-methods',
      'order-history',
      'notices',
      'news',
      'member-notices'
    ]) {
      assert.match(apiSource, new RegExp(`pageKey === '${key}'`))
    }
    assert.match(apiSource, /\/api\/admin\/market\/symbols\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/finance\/ledger\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/finance\/payment-methods\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/trading\/orders\?\$\{featureQuery\(query\)\}/)
    assert.match(apiSource, /\/api\/admin\/content\/articles\?articleType=/)
    assert.match(apiSource, /\/api\/admin\/content\/messages\?\$\{featureQuery\(query\)\}/)
  })

  it('maps remaining WH screenshot row actions to real domain write APIs', () => {
    assert.match(apiSource, /runProductAction/)
    assert.match(apiSource, /\/api\/admin\/market\/symbols\/\$\{encodeURIComponent\(request\.rowId\)\}/)
    assert.match(apiSource, /runPaymentMethodAction/)
    assert.match(apiSource, /\/api\/admin\/finance\/payment-methods\/\$\{encodeURIComponent\(request\.rowId\)\}/)
    assert.match(apiSource, /runContentArticleAction/)
    assert.match(apiSource, /\/api\/admin\/content\/articles\/\$\{encodeURIComponent\(request\.rowId\)\}/)
    assert.match(apiSource, /runContentMessageAction/)
    assert.match(apiSource, /\/api\/admin\/content\/messages\/\$\{encodeURIComponent\(request\.rowId\)\}/)
    assert.match(apiSource, /runOrderHistoryAction/)
    assert.match(apiSource, /\/api\/admin\/trading\/orders\/\$\{encodeURIComponent\(request\.rowId\)\}\/cancel/)
  })

  it('builds feature page queries with pagination filters and server sorting', () => {
    assert.match(apiSource, /export type FeaturePageQuery/)
    assert.match(apiSource, /function featurePageQueryString\(/)
    assert.match(apiSource, /page=\$\{encodeURIComponent\(String\(query\.page \?\? 0\)\)\}/)
    assert.match(apiSource, /size=\$\{encodeURIComponent\(String\(query\.size \?\? 10\)\)\}/)
    assert.match(apiSource, /sortField/)
    assert.match(apiSource, /sortDirection/)
    assert.match(apiSource, /Object\.entries\(query\.filters \?\? \{\}\)/)
  })
})
