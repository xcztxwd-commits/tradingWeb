import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './apiClient'
import type {
  AccountRow,
  AdminFeatureOperationRequest,
  AdminFeatureOperationResponse,
  AdminFeaturePage,
  AdminPage,
  AdminUser,
  AuditLog,
  ContentArticleRow,
  ContentMessageRow,
  DashboardSummary,
  DataProviderPayload,
  DataProviderRow,
  DictionaryRow,
  LedgerRow,
  MarketStatus,
  OrderRow,
  PaymentMethodRow,
  PositionRow,
  ProviderInstrumentRow,
  ProviderSyncResponse,
  RiskConfigRow,
  SymbolDisplayPayload,
  SymbolPayload,
  SymbolProviderBindingPayload,
  SymbolProviderBindingRow,
  SymbolRow,
  SystemSettingRow,
  TradeRow
} from '../types'

export type AdminSnapshot = {
  users: AdminUser[]
  accounts: AccountRow[]
  orders: OrderRow[]
  positions: PositionRow[]
  trades: TradeRow[]
  ledger: LedgerRow[]
  symbols: SymbolRow[]
  riskConfigs: RiskConfigRow[]
  auditLogs: AuditLog[]
  marketStatus: MarketStatus
}

export type FeaturePageQuery = {
  page?: number
  size?: number
  filters?: Record<string, string>
  sortField?: string
  sortDirection?: 'asc' | 'desc'
}

type SymbolCategoryRow = {
  id: string
  name: string
  code: string
  sortOrder: number
  enabled: boolean
  updatedAt: string | null
}

type PriceAdjustmentRow = {
  id: string
  symbolId: string
  symbol: string
  mode: string
  adjustmentType: string
  targetPrice: number
  startsAt: string | null
  endsAt: string | null
  status: string
  adminUserId: string
  reason: string
  createdAt: string | null
}

type RequestLogRow = {
  id: string
  method: string
  path: string
  queryString: string | null
  clientIp: string | null
  userAgent: string | null
  statusCode: number | null
  durationMs: number | null
  errorMessage: string | null
  createdAt: string | null
}

type VerificationCodeLogRow = {
  id: string
  scene: string
  account: string
  channel: string
  code: string
  status: string
  errorMessage: string | null
  createdAt: string | null
}

export type AdminTableColumnPreference = {
  id: string
  userId: string
  pageKey: string
  hiddenColumns: string[]
  tableSize: string
  showBorder: boolean
  zebra: boolean
  updatedAt: string | null
}

type AdminExportTask = {
  id: string
  pageKey: string
  status: string
}

type AdminImportTask = {
  id: string
  pageKey: string
  status: string
  fileName: string
}

type AdminBatchOperation = {
  id: string
  pageKey: string
  operation: string
  rowIds: string[]
  status: string
}

type RbacRoleRow = { id: string; name: string; code: string; enabled: boolean; sortOrder: number; description: string | null; createdAt: string | null }
type RbacMenuRow = { id: string; parentId: string | null; name: string; permissionKey: string; path: string | null; component: string | null; menuType: string; enabled: boolean; sortOrder: number }
type RbacDepartmentRow = { id: string; name: string; parentId: string | null; leader: string | null; phone: string | null; enabled: boolean; sortOrder: number }
type RbacPostRow = { id: string; name: string; code: string; enabled: boolean; sortOrder: number }
type FundOrderRow = { id: string; userId: string; accountId: string; orderType: string; amount: number; currency: string; status: string; note: string | null; reviewReason: string | null; createdAt: string | null }
type MemberPaymentAccountRow = { id: string; userId: string; accountType: string; currency: string; network: string | null; holderName: string | null; bankName: string | null; accountNo: string; enabled: boolean; createdAt: string | null }

function pageQuery(page = 0, size = 50) {
  return featurePageQueryString({ page, size })
}

function featurePageQueryString(query: FeaturePageQuery = {}) {
  const params = [
    `page=${encodeURIComponent(String(query.page ?? 0))}`,
    `size=${encodeURIComponent(String(query.size ?? 10))}`
  ]
  if (query.sortField) params.push(`sortField=${encodeURIComponent(query.sortField)}`)
  if (query.sortDirection) params.push(`sortDirection=${encodeURIComponent(query.sortDirection)}`)
  for (const [key, value] of Object.entries(query.filters ?? {})) {
    if (value.trim()) params.push(`filter.${encodeURIComponent(key)}=${encodeURIComponent(value.trim())}`)
  }
  return params.join('&')
}

function featureQuery(query: FeaturePageQuery | undefined, fallbackSize = 50) {
  return featurePageQueryString({ page: 0, size: fallbackSize, ...query })
}

export function getDashboardSummary(token: string) {
  return apiGet<DashboardSummary>('/api/admin/dashboard/summary', token)
}

export function getUsersPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<AdminUser>>(`/api/admin/users?${pageQuery(page, size)}`, token)
}

export function getAccountsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<AccountRow>>(`/api/admin/accounts?${pageQuery(page, size)}`, token)
}

export function getOrdersPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<OrderRow>>(`/api/admin/trading/orders?${pageQuery(page, size)}`, token)
}

export function getPositionsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<PositionRow>>(`/api/admin/trading/positions?${pageQuery(page, size)}`, token)
}

export function getTradesPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<TradeRow>>(`/api/admin/trading/trades?${pageQuery(page, size)}`, token)
}

export function getLedgerPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<LedgerRow>>(`/api/admin/finance/ledger?${pageQuery(page, size)}`, token)
}

export function getPaymentMethodsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<PaymentMethodRow>>(`/api/admin/finance/payment-methods?${pageQuery(page, size)}`, token)
}

export function getSymbolsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<SymbolRow>>(`/api/admin/market/symbols?${pageQuery(page, size)}`, token)
}

export function createSymbol(token: string, payload: SymbolPayload) {
  return apiPost<SymbolRow>('/api/admin/market/symbols', payload, token)
}

export function getMarketStatus(token: string) {
  return apiGet<MarketStatus>('/api/admin/market/status', token)
}

export function getDataProviders(token: string) {
  return apiGet<DataProviderRow[]>('/api/admin/market/data-providers', token)
}

export function createDataProvider(token: string, payload: DataProviderPayload) {
  return apiPost<DataProviderRow>('/api/admin/market/data-providers', payload, token)
}

export function updateDataProvider(token: string, providerId: string, payload: DataProviderPayload) {
  return apiPut<DataProviderRow>(
    `/api/admin/market/data-providers/${encodeURIComponent(providerId)}`,
    payload,
    token
  )
}

export function testDataProvider(token: string, providerId: string) {
  return apiPost<DataProviderRow>(
    `/api/admin/market/data-providers/${encodeURIComponent(providerId)}/test`,
    {},
    token
  )
}

export function syncProviderInstruments(token: string, providerId: string) {
  return apiPost<ProviderSyncResponse>(
    `/api/admin/market/data-providers/${encodeURIComponent(providerId)}/sync-instruments`,
    {},
    token
  )
}

export function getProviderInstruments(token: string, providerId: string) {
  return apiGet<ProviderInstrumentRow[]>(
    `/api/admin/market/data-providers/${encodeURIComponent(providerId)}/instruments`,
    token
  )
}

export function getSymbolProviderBindings(token: string, symbolId: string) {
  return apiGet<SymbolProviderBindingRow[]>(
    `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/provider-bindings`,
    token
  )
}

export function createSymbolProviderBinding(
  token: string,
  symbolId: string,
  payload: SymbolProviderBindingPayload
) {
  return apiPost<SymbolProviderBindingRow>(
    `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/provider-bindings`,
    payload,
    token
  )
}

export function updateSymbolProviderBinding(
  token: string,
  symbolId: string,
  bindingId: string,
  payload: SymbolProviderBindingPayload
) {
  return apiPut<SymbolProviderBindingRow>(
    `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/provider-bindings/${encodeURIComponent(bindingId)}`,
    payload,
    token
  )
}

export function updateSymbolDisplay(token: string, symbolId: string, payload: SymbolDisplayPayload) {
  return apiPut<SymbolRow>(`/api/admin/market/symbols/${encodeURIComponent(symbolId)}/display`, payload, token)
}

export function getRiskConfigs(token: string) {
  return apiGet<RiskConfigRow[]>('/api/admin/risk/configs', token)
}

export function getMessagesPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<ContentMessageRow>>(`/api/admin/content/messages?${pageQuery(page, size)}`, token)
}

export function getArticlesPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<ContentArticleRow>>(`/api/admin/content/articles?${pageQuery(page, size)}`, token)
}

export function getDictionariesPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<DictionaryRow>>(`/api/admin/config/dictionaries?${pageQuery(page, size)}`, token)
}

export function getSettingsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<SystemSettingRow>>(`/api/admin/config/settings?${pageQuery(page, size)}`, token)
}

export function getAuditLogsPage(token: string, page = 0, size = 50) {
  return apiGet<AdminPage<AuditLog>>(`/api/admin/audit-logs?${pageQuery(page, size)}`, token)
}

export function getFeaturePages(token: string) {
  return apiGet<AdminFeaturePage[]>('/api/admin/features', token)
}

export function getFeaturePage(token: string, pageKey: string, query: FeaturePageQuery = {}) {
  if (pageKey === 'system-roles') return getSystemRolesFeaturePage(token, query)
  if (pageKey === 'system-menus') return getSystemMenusFeaturePage(token, query)
  if (pageKey === 'system-departments') return getSystemDepartmentsFeaturePage(token, query)
  if (pageKey === 'system-posts') return getSystemPostsFeaturePage(token, query)
  if (pageKey === 'products') return getProductsFeaturePage(token, query)
  if (pageKey === 'recharge-orders') return getFundOrdersFeaturePage(token, 'RECHARGE', query)
  if (pageKey === 'withdrawal-orders') return getFundOrdersFeaturePage(token, 'WITHDRAWAL', query)
  if (pageKey === 'finance-ledger') return getFinanceLedgerFeaturePage(token, query)
  if (pageKey === 'payment-methods') return getPaymentMethodsFeaturePage(token, query)
  if (pageKey === 'members') return getMembersFeaturePage(token, query)
  if (pageKey === 'member-payment-accounts') return getMemberPaymentAccountsFeaturePage(token, query)
  if (pageKey === 'order-history') return getOrderHistoryFeaturePage(token, query)
  if (pageKey === 'product-categories') return getProductCategoriesFeaturePage(token, query)
  if (pageKey === 'price-schedules') return getPriceSchedulesFeaturePage(token, query)
  if (pageKey === 'notices') return getArticlesFeaturePage(token, 'ANNOUNCEMENT', query)
  if (pageKey === 'news') return getArticlesFeaturePage(token, 'NEWS', query)
  if (pageKey === 'member-notices') return getMessagesFeaturePage(token, query)
  if (pageKey === 'request-logs') return getRequestLogsFeaturePage(token, query)
  if (pageKey === 'verification-codes') return getVerificationCodesFeaturePage(token, query)
  return apiGet<AdminFeaturePage>(
    `/api/admin/features/${encodeURIComponent(pageKey)}?${featureQuery(query)}`,
    token
  )
}

export function runFeatureAction(token: string, pageKey: string, request: AdminFeatureOperationRequest) {
  if (pageKey === 'system-roles') return runSystemRoleAction(token, request)
  if (pageKey === 'system-menus') return runSystemMenuAction(token, request)
  if (pageKey === 'system-departments') return runSystemDepartmentAction(token, request)
  if (pageKey === 'system-posts') return runSystemPostAction(token, request)
  if (pageKey === 'products') return runProductAction(token, request)
  if (pageKey === 'recharge-orders' || pageKey === 'withdrawal-orders') return runFundOrderAction(token, pageKey, request)
  if (pageKey === 'payment-methods') return runPaymentMethodAction(token, request)
  if (pageKey === 'member-payment-accounts') return runMemberPaymentAccountAction(token, request)
  if (pageKey === 'order-history') return runOrderHistoryAction(token, request)
  if (pageKey === 'product-categories') return runProductCategoryAction(token, request)
  if (pageKey === 'price-schedules') return runPriceScheduleAction(token, request)
  if (pageKey === 'notices') return runContentArticleAction(token, 'ANNOUNCEMENT', request)
  if (pageKey === 'news') return runContentArticleAction(token, 'NEWS', request)
  if (pageKey === 'member-notices') return runContentMessageAction(token, request)
  if (pageKey === 'verification-codes') return runVerificationCodeAction(token, request)
  if (request.action === 'export') return runTableExportAction(token, pageKey, request)
  if (request.action === 'import') return runTableImportAction(token, pageKey, request)
  if (request.action === 'delete') return runTableBatchAction(token, pageKey, request)
  return apiPost<AdminFeatureOperationResponse>(
    `/api/admin/features/${encodeURIComponent(pageKey)}/actions`,
    request,
    token
  )
}

function withPageMeta(page: AdminPage<unknown>, pageData: Omit<AdminFeaturePage, 'page' | 'size' | 'total' | 'totalPages'>) {
  return {
    ...pageData,
    page: page.page,
    size: page.size,
    total: page.total,
    totalPages: page.totalPages
  } satisfies AdminFeaturePage
}

function withArrayMeta(rows: Record<string, unknown>[], query: FeaturePageQuery, pageData: Omit<AdminFeaturePage, 'page' | 'size' | 'total' | 'totalPages'>) {
  return {
    ...pageData,
    page: query.page ?? 0,
    size: query.size ?? rows.length,
    total: rows.length,
    totalPages: rows.length === 0 ? 0 : 1
  } satisfies AdminFeaturePage
}

function getSystemRolesFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<RbacRoleRow>>(`/api/admin/rbac/roles?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'system-roles',
    title: '角色管理',
    group: '权限',
    fields: roleFields(),
    columns: [
      { key: 'name', label: '角色名称', sortable: true },
      { key: 'code', label: '角色编码', sortable: true },
      { key: 'enabled', label: '状态', sortable: true },
      { key: 'sortOrder', label: '排序', sortable: true },
      { key: 'description', label: '说明', sortable: false }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'menu-permissions', label: '菜单权限', type: 'modal' },
      { key: 'data-scope', label: '数据权限', type: 'modal' },
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items
  }))
}

function getSystemMenusFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<RbacMenuRow>>(`/api/admin/rbac/menus?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'system-menus',
    title: '菜单管理',
    group: '权限',
    fields: menuFields(),
    columns: [
      { key: 'name', label: '菜单名称', sortable: true },
      { key: 'permissionKey', label: '权限标识', sortable: true },
      { key: 'path', label: '路径', sortable: true },
      { key: 'menuType', label: '类型', sortable: true },
      { key: 'enabled', label: '状态', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items
  }))
}

function getSystemDepartmentsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<RbacDepartmentRow>>(`/api/admin/rbac/departments?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'system-departments',
    title: '部门管理',
    group: '权限',
    fields: departmentFields(),
    columns: [
      { key: 'name', label: '部门名称', sortable: true },
      { key: 'leader', label: '负责人', sortable: true },
      { key: 'phone', label: '电话', sortable: true },
      { key: 'enabled', label: '状态', sortable: true },
      { key: 'sortOrder', label: '排序', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items
  }))
}

function getSystemPostsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<RbacPostRow>>(`/api/admin/rbac/posts?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'system-posts',
    title: '岗位管理',
    group: '权限',
    fields: postFields(),
    columns: [
      { key: 'name', label: '岗位名称', sortable: true },
      { key: 'code', label: '岗位编码', sortable: true },
      { key: 'enabled', label: '状态', sortable: true },
      { key: 'sortOrder', label: '排序', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items
  }))
}

function getProductsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<SymbolRow>>(`/api/admin/market/symbols?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'products',
    title: '产品列表',
    group: '产品管理',
    fields: productFields(),
    columns: [
      { key: 'assetClass', label: '分类', sortable: true },
      { key: 'symbol', label: '产品代码', sortable: true },
      { key: 'displayName', label: '产品名称', sortable: true },
      { key: 'baseCurrency', label: '基础币种', sortable: true },
      { key: 'quoteCurrency', label: '计价币种', sortable: true },
      { key: 'leverage', label: '倍数', sortable: true },
      { key: 'minLot', label: '最小手', sortable: true },
      { key: 'maxLot', label: '最大手', sortable: true },
      { key: 'enabled', label: '状态', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'risk', label: '风控', type: 'modal' },
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getFinanceLedgerFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<LedgerRow>>(`/api/admin/finance/ledger?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'finance-ledger',
    title: '资金明细',
    group: '财务管理',
    fields: [
      { key: 'accountId', label: '账户ID', component: 'input', options: [] },
      { key: 'entryType', label: '交易类型', component: 'input', options: [] },
      { key: 'currency', label: '币种', component: 'input', options: [] },
      { key: 'title', label: '标题/说明', component: 'input', options: [] }
    ],
    columns: [
      { key: 'accountId', label: '账户ID', sortable: true },
      { key: 'entryType', label: '交易类型', sortable: true },
      { key: 'amount', label: '变化数量', sortable: true },
      { key: 'balanceAfter', label: '变化后数量', sortable: true },
      { key: 'currency', label: '币种', sortable: true },
      { key: 'referenceType', label: '关联类型', sortable: true },
      { key: 'description', label: '标题', sortable: false },
      { key: 'createdAt', label: '创建时间', sortable: true }
    ],
    toolbarActions: [{ key: 'export', label: '导出', type: 'download' }],
    rowActions: [{ key: 'delete', label: '删除', type: 'confirm' }],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getPaymentMethodsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<PaymentMethodRow>>(`/api/admin/finance/payment-methods?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'payment-methods',
    title: '收款方式',
    group: '财务管理',
    fields: paymentMethodFields(),
    columns: [
      { key: 'name', label: '名称', sortable: true },
      { key: 'displayOrder', label: '排序', sortable: true },
      { key: 'enabled', label: '状态', sortable: true },
      { key: 'methodType', label: '类型', sortable: true },
      { key: 'currency', label: '网络/货币', sortable: true },
      { key: 'instructions', label: '收款信息', sortable: false },
      { key: 'createdAt', label: '创建时间', sortable: true },
      { key: 'updatedAt', label: '更新时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getOrderHistoryFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<OrderRow>>(`/api/admin/trading/orders?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'order-history',
    title: '挂单/持仓/历史',
    group: '订单管理',
    fields: [
      { key: 'uid', label: 'UID', component: 'input', options: [] },
      { key: 'product', label: '产品', component: 'input', options: [] },
      { key: 'direction', label: '方向', component: 'input', options: [] },
      { key: 'status', label: '状态', component: 'input', options: [] },
      { key: 'type', label: '类型', component: 'input', options: [] }
    ],
    columns: [
      { key: 'userId', label: 'UID', sortable: true },
      { key: 'symbol', label: '产品', sortable: true },
      { key: 'lots', label: '手数', sortable: true },
      { key: 'executionPrice', label: '开仓价格', sortable: true },
      { key: 'side', label: '方向', sortable: true },
      { key: 'status', label: '状态', sortable: true },
      { key: 'orderType', label: '类型', sortable: true },
      { key: 'createdAt', label: '开仓时间', sortable: true }
    ],
    toolbarActions: [{ key: 'export', label: '导出', type: 'download' }],
    rowActions: [
      { key: 'cancel', label: '撤销', type: 'confirm' },
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getArticlesFeaturePage(token: string, articleType: 'ANNOUNCEMENT' | 'NEWS', query: FeaturePageQuery) {
  const key = articleType === 'ANNOUNCEMENT' ? 'notices' : 'news'
  return apiGet<AdminPage<ContentArticleRow>>(
    `/api/admin/content/articles?articleType=${encodeURIComponent(articleType)}&${featureQuery(query)}`,
    token
  ).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key,
    title: articleType === 'ANNOUNCEMENT' ? '公告列表' : '新闻列表',
    group: '内容管理',
    fields: contentArticleFields(),
    columns: [
      { key: 'id', label: '主键ID', sortable: true },
      { key: 'title', label: '标题', sortable: true },
      { key: 'summary', label: '简介', sortable: false },
      { key: 'status', label: '状态', sortable: true },
      { key: 'sortOrder', label: '排序', sortable: true },
      { key: 'createdAt', label: '创建时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getMessagesFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<ContentMessageRow>>(`/api/admin/content/messages?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'member-notices',
    title: '通知表',
    group: '内容管理',
    fields: contentMessageFields(),
    columns: [
      { key: 'targetUserId', label: '用户ID', sortable: true },
      { key: 'body', label: '内容', sortable: false },
      { key: 'sentBy', label: '创建人', sortable: true },
      { key: 'createdAt', label: '创建时间', sortable: true },
      { key: 'updatedAt', label: '更新时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [
      { key: 'edit', label: '编辑', type: 'modal' },
      { key: 'delete', label: '删除', type: 'confirm' }
    ],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getFundOrdersFeaturePage(token: string, orderType: 'RECHARGE' | 'WITHDRAWAL', query: FeaturePageQuery) {
  const key = orderType === 'RECHARGE' ? 'recharge-orders' : 'withdrawal-orders'
  return apiGet<AdminPage<FundOrderRow>>(
    `/api/admin/finance/fund-orders?orderType=${orderType}&${featureQuery(query)}`,
    token
  ).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key,
    title: orderType === 'RECHARGE' ? '充值订单' : '提现订单',
    group: '财务管理',
    fields: [{ key: 'status', label: '状态', component: 'input', options: [] }],
    columns: [
      { key: 'userId', label: '用户ID', sortable: true },
      { key: 'accountId', label: '账户ID', sortable: true },
      { key: 'amount', label: '金额', sortable: true },
      { key: 'currency', label: '币种', sortable: true },
      { key: 'status', label: '状态', sortable: true },
      { key: 'createdAt', label: '创建时间', sortable: true }
    ],
    toolbarActions: [],
    rowActions: [{ key: 'review', label: '通过', type: 'confirm' }],
    rows: page.items as unknown as Record<string, unknown>[]
  }))
}

function getMembersFeaturePage(token: string, query: FeaturePageQuery) {
  return getUsersPage(token, query.page ?? 0, query.size ?? 10).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'members',
    title: '用户列表',
    group: '用户管理',
    fields: [{ key: 'email', label: '邮箱', component: 'input', options: [] }],
    columns: [
      { key: 'email', label: '邮箱', sortable: true },
      { key: 'phone', label: '手机号', sortable: true },
      { key: 'status', label: '状态', sortable: true },
      { key: 'kycStatus', label: 'KYC', sortable: true },
      { key: 'riskLevel', label: '风险', sortable: true },
      { key: 'createdAt', label: '注册时间', sortable: true }
    ],
    toolbarActions: [],
    rowActions: [],
    rows: page.items
  }))
}

function getMemberPaymentAccountsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<MemberPaymentAccountRow[]>(
    `/api/admin/members/payment-accounts?${featureQuery(query)}`,
    token
  ).then((rows) => withArrayMeta(rows, query, {
    key: 'member-payment-accounts',
    title: '用户银行卡',
    group: '用户管理',
    fields: paymentAccountFields(),
    columns: [
      { key: 'userId', label: '用户ID', sortable: true },
      { key: 'accountType', label: '账户类型', sortable: true },
      { key: 'currency', label: '币种', sortable: true },
      { key: 'network', label: '网络', sortable: true },
      { key: 'holderName', label: '持有人', sortable: true },
      { key: 'accountNo', label: '账号', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [],
    rows
  }))
}

async function runProductAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<SymbolRow>('/api/admin/market/symbols', productPayload(payload), token)
    return featureActionResult('products', request.action, created.id, '产品已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<SymbolRow>(
      `/api/admin/market/symbols/${encodeURIComponent(request.rowId)}`,
      productPayload(payload),
      token
    )
    return featureActionResult('products', request.action, updated.id, '产品已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/market/symbols/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除产品' },
      token
    )
    return featureActionResult('products', request.action, request.rowId, '产品已下架')
  }
  if (request.action === 'risk' && request.rowId) {
    if (String(payload.targetPrice ?? '').trim()) {
      const adjusted = await apiPost<PriceAdjustmentRow>(
        `/api/admin/market/symbols/${encodeURIComponent(request.rowId)}/price-adjustments`,
        {
          mode: String(payload.mode ?? 'PRICE_REPAIR'),
          adjustmentType: String(payload.adjustmentType ?? 'SET_MID_PRICE'),
          targetPrice: String(payload.targetPrice),
          startsAt: blankToNull(payload.startsAt),
          endsAt: blankToNull(payload.endsAt),
          reason: String(payload.reason ?? '后台产品风控设置')
        },
        token
      )
      return featureActionResult('products', request.action, adjusted.id, '产品风控已保存')
    }
    const updated = await apiPatch<SymbolRow>(
      `/api/admin/market/symbols/${encodeURIComponent(request.rowId)}/status`,
      { enabled: toBoolean(payload.enabled ?? payload.status, true), reason: String(payload.reason ?? '后台产品状态调整') },
      token
    )
    return featureActionResult('products', request.action, updated.id, '产品状态已更新')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/products/actions', request, token)
}

async function runPaymentMethodAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<PaymentMethodRow>('/api/admin/finance/payment-methods', paymentMethodPayload(payload), token)
    return featureActionResult('payment-methods', request.action, created.id, '收款方式已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPatch<PaymentMethodRow>(
      `/api/admin/finance/payment-methods/${encodeURIComponent(request.rowId)}`,
      paymentMethodPayload(payload),
      token
    )
    return featureActionResult('payment-methods', request.action, updated.id, '收款方式已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/finance/payment-methods/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除收款方式' },
      token
    )
    return featureActionResult('payment-methods', request.action, request.rowId, '收款方式已删除')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/payment-methods/actions', request, token)
}

async function runOrderHistoryAction(token: string, request: AdminFeatureOperationRequest) {
  if (request.action === 'cancel' && request.rowId) {
    const canceled = await apiPost<OrderRow>(
      `/api/admin/trading/orders/${encodeURIComponent(request.rowId)}/cancel`,
      { reason: request.reason || '后台撤销订单', idempotencyKey: `admin-cancel-${request.rowId}-${Date.now()}` },
      token
    )
    return featureActionResult('order-history', request.action, canceled.id, '订单已撤销')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/order-history/actions', request, token)
}

async function runContentArticleAction(
  token: string,
  articleType: 'ANNOUNCEMENT' | 'NEWS',
  request: AdminFeatureOperationRequest
) {
  const pageKey = articleType === 'ANNOUNCEMENT' ? 'notices' : 'news'
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<ContentArticleRow>('/api/admin/content/articles', contentArticlePayload(payload, articleType), token)
    return featureActionResult(pageKey, request.action, created.id, '内容已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<ContentArticleRow>(
      `/api/admin/content/articles/${encodeURIComponent(request.rowId)}`,
      contentArticlePayload(payload, articleType),
      token
    )
    return featureActionResult(pageKey, request.action, updated.id, '内容已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/content/articles/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除内容' },
      token
    )
    return featureActionResult(pageKey, request.action, request.rowId, '内容已删除')
  }
  return apiPost<AdminFeatureOperationResponse>(`/api/admin/features/${pageKey}/actions`, request, token)
}

async function runContentMessageAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<ContentMessageRow>('/api/admin/content/messages', contentMessagePayload(payload), token)
    return featureActionResult('member-notices', request.action, created.id, '通知已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<ContentMessageRow>(
      `/api/admin/content/messages/${encodeURIComponent(request.rowId)}`,
      contentMessagePayload(payload),
      token
    )
    return featureActionResult('member-notices', request.action, updated.id, '通知已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/content/messages/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除通知' },
      token
    )
    return featureActionResult('member-notices', request.action, request.rowId, '通知已删除')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/member-notices/actions', request, token)
}

async function runSystemRoleAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<RbacRoleRow>(
      '/api/admin/rbac/roles',
      rolePayload(payload),
      token
    )
    return featureActionResult('system-roles', request.action, created.id, '角色已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<RbacRoleRow>(
      `/api/admin/rbac/roles/${encodeURIComponent(request.rowId)}`,
      rolePayload(payload),
      token
    )
    return featureActionResult('system-roles', request.action, updated.id, '角色已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/rbac/roles/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除角色' },
      token
    )
    return featureActionResult('system-roles', request.action, request.rowId, '角色已删除')
  }
  if (request.action === 'menu-permissions' && request.rowId) {
    await apiPut<{ id: string }>(
      `/api/admin/rbac/roles/${encodeURIComponent(request.rowId)}/menu-permissions`,
      {
        menuId: String(payload.menuId ?? ''),
        buttons: splitCsv(payload.buttons)
      },
      token
    )
    return featureActionResult('system-roles', request.action, request.rowId, '菜单权限已保存')
  }
  if (request.action === 'data-scope' && request.rowId) {
    await apiPut<{ id: string }>(
      `/api/admin/rbac/roles/${encodeURIComponent(request.rowId)}/data-scope`,
      {
        scopeType: String(payload.scopeType ?? 'ALL'),
        departmentIds: splitCsv(payload.departmentIds)
      },
      token
    )
    return featureActionResult('system-roles', request.action, request.rowId, '数据权限已保存')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/system-roles/actions', request, token)
}

async function runSystemMenuAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<RbacMenuRow>(
      '/api/admin/rbac/menus',
      menuPayload(payload),
      token
    )
    return featureActionResult('system-menus', request.action, created.id, '菜单已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<RbacMenuRow>(
      `/api/admin/rbac/menus/${encodeURIComponent(request.rowId)}`,
      menuPayload(payload),
      token
    )
    return featureActionResult('system-menus', request.action, updated.id, '菜单已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/rbac/menus/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除菜单' },
      token
    )
    return featureActionResult('system-menus', request.action, request.rowId, '菜单已删除')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/system-menus/actions', request, token)
}

async function runSystemDepartmentAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<RbacDepartmentRow>(
      '/api/admin/rbac/departments',
      departmentPayload(payload),
      token
    )
    return featureActionResult('system-departments', request.action, created.id, '部门已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<RbacDepartmentRow>(
      `/api/admin/rbac/departments/${encodeURIComponent(request.rowId)}`,
      departmentPayload(payload),
      token
    )
    return featureActionResult('system-departments', request.action, updated.id, '部门已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/rbac/departments/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除部门' },
      token
    )
    return featureActionResult('system-departments', request.action, request.rowId, '部门已删除')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/system-departments/actions', request, token)
}

async function runSystemPostAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const created = await apiPost<RbacPostRow>(
      '/api/admin/rbac/posts',
      postPayload(payload),
      token
    )
    return featureActionResult('system-posts', request.action, created.id, '岗位已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<RbacPostRow>(
      `/api/admin/rbac/posts/${encodeURIComponent(request.rowId)}`,
      postPayload(payload),
      token
    )
    return featureActionResult('system-posts', request.action, updated.id, '岗位已更新')
  }
  if (request.action === 'delete' && request.rowId) {
    await apiDelete<null>(
      `/api/admin/rbac/posts/${encodeURIComponent(request.rowId)}`,
      { reason: request.reason || '后台删除岗位' },
      token
    )
    return featureActionResult('system-posts', request.action, request.rowId, '岗位已删除')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/system-posts/actions', request, token)
}

async function runFundOrderAction(token: string, pageKey: string, request: AdminFeatureOperationRequest) {
  if (request.action === 'review' && request.rowId) {
    const reviewed = await apiPost<FundOrderRow>(
      `/api/admin/finance/fund-orders/${encodeURIComponent(request.rowId)}/review`,
      { status: 'APPROVED', reason: request.reason || '后台审核通过' },
      token
    )
    return featureActionResult(pageKey, request.action, reviewed.id, '资金订单已审核')
  }
  return apiPost<AdminFeatureOperationResponse>(`/api/admin/features/${encodeURIComponent(pageKey)}/actions`, request, token)
}

async function runMemberPaymentAccountAction(token: string, request: AdminFeatureOperationRequest) {
  if (request.action === 'create') {
    const payload = request.payload ?? {}
    const userId = String(payload.userId ?? '')
    const created = await apiPost<MemberPaymentAccountRow>(
      `/api/admin/members/${encodeURIComponent(userId)}/payment-accounts`,
      {
        accountType: String(payload.accountType ?? 'BANK'),
        currency: String(payload.currency ?? 'USD'),
        network: String(payload.network ?? ''),
        holderName: String(payload.holderName ?? ''),
        bankName: String(payload.bankName ?? ''),
        branchName: String(payload.branchName ?? ''),
        bankCode: String(payload.bankCode ?? ''),
        accountNo: String(payload.accountNo ?? ''),
        enabled: toBoolean(payload.enabled, true)
      },
      token
    )
    return featureActionResult('member-payment-accounts', request.action, created.id, '会员支付账户已创建')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/member-payment-accounts/actions', request, token)
}

function rolePayload(payload: Record<string, unknown>) {
  return {
    name: String(payload.name ?? ''),
    code: String(payload.code ?? ''),
    enabled: toBoolean(payload.enabled, true),
    sortOrder: Number(payload.sortOrder ?? 0),
    description: String(payload.description ?? '')
  }
}

function menuPayload(payload: Record<string, unknown>) {
  return {
    parentId: blankToNull(payload.parentId),
    name: String(payload.name ?? ''),
    permissionKey: String(payload.permissionKey ?? ''),
    path: String(payload.path ?? ''),
    component: String(payload.component ?? ''),
    menuType: String(payload.menuType ?? 'MENU'),
    enabled: toBoolean(payload.enabled, true),
    sortOrder: Number(payload.sortOrder ?? 0)
  }
}

function departmentPayload(payload: Record<string, unknown>) {
  return {
    name: String(payload.name ?? ''),
    parentId: blankToNull(payload.parentId),
    leader: String(payload.leader ?? ''),
    phone: String(payload.phone ?? ''),
    enabled: toBoolean(payload.enabled, true),
    sortOrder: Number(payload.sortOrder ?? 0)
  }
}

function postPayload(payload: Record<string, unknown>) {
  return {
    name: String(payload.name ?? ''),
    code: String(payload.code ?? ''),
    enabled: toBoolean(payload.enabled, true),
    sortOrder: Number(payload.sortOrder ?? 0)
  }
}

function productPayload(payload: Record<string, unknown>) {
  const symbol = String(payload.symbol ?? '')
  const assetClass = String(payload.assetClass ?? payload.category ?? 'FOREX')
  return {
    symbol,
    displayName: String(payload.displayName ?? payload.productName ?? symbol),
    provider: String(payload.provider ?? 'massive'),
    providerSymbol: String(payload.providerSymbol ?? symbol),
    assetClass,
    productType: String(payload.productType ?? productTypeForAssetClass(assetClass)),
    baseCurrency: String(payload.baseCurrency ?? 'USD'),
    quoteCurrency: String(payload.quoteCurrency ?? 'USD'),
    pipSize: String(payload.pipSize ?? '0.0001'),
    tickSize: String(payload.tickSize ?? '0.00001'),
    lotSize: String(payload.lotSize ?? '100000'),
    minLot: String(payload.minLot ?? '0.01'),
    maxLot: String(payload.maxLot ?? '100'),
    leverage: Number(payload.leverage ?? 100),
    spreadMarkup: String(payload.spreadMarkup ?? '0'),
    enabled: toBoolean(payload.enabled, true)
  }
}

function productTypeForAssetClass(assetClass: string) {
  const normalizedAssetClass = assetClass.trim().toUpperCase()
  if (normalizedAssetClass === 'CRYPTO' || normalizedAssetClass === 'SPOT') return 'CRYPTO_SPOT'
  if (normalizedAssetClass === 'INVERSE_PERP' || normalizedAssetClass === 'INVERSE_PERPETUAL') return 'INVERSE_PERP'
  if (['LINEAR_PERP', 'LINEAR_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT'].includes(normalizedAssetClass)) {
    return 'LINEAR_PERP'
  }
  return 'FX_MARGIN'
}

function paymentMethodPayload(payload: Record<string, unknown>) {
  return {
    name: String(payload.name ?? ''),
    methodType: String(payload.methodType ?? payload.type ?? 'BANK_TRANSFER'),
    currency: String(payload.currency ?? payload.networkOrCurrency ?? 'USD'),
    enabled: toBoolean(payload.enabled ?? payload.status, true),
    displayOrder: Number(payload.displayOrder ?? payload.sort ?? 0),
    instructions: String(payload.instructions ?? payload.cardOrWallet ?? payload.address ?? '')
  }
}

function contentArticlePayload(payload: Record<string, unknown>, articleType: 'ANNOUNCEMENT' | 'NEWS') {
  const title = String(payload.title ?? '')
  const summary = String(payload.summary ?? payload.intro ?? '')
  return {
    articleType,
    title,
    summary,
    body: String((payload.body ?? payload.content ?? summary) || title),
    status: String(payload.status ?? 'PUBLISHED'),
    language: String(payload.language ?? 'zh-CN'),
    sortOrder: Number(payload.sortOrder ?? payload.sort ?? 0)
  }
}

function contentMessagePayload(payload: Record<string, unknown>) {
  return {
    targetUserId: blankToNull(payload.targetUserId ?? payload.userId),
    title: String(payload.title ?? '用户通知'),
    body: String(payload.body ?? payload.content ?? ''),
    messageType: String(payload.messageType ?? 'SYSTEM'),
    status: String(payload.status ?? 'PUBLISHED')
  }
}

function splitCsv(value: unknown) {
  if (Array.isArray(value)) return value.map(String).filter(Boolean)
  return String(value ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function roleFields() {
  return [
    { key: 'name', label: '角色名称', component: 'input', options: [] },
    { key: 'code', label: '角色编码', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() },
    { key: 'sortOrder', label: '排序', component: 'number', options: [] },
    { key: 'description', label: '说明', component: 'textarea', options: [] }
  ]
}

function menuFields() {
  return [
    { key: 'parentId', label: '父级ID', component: 'input', options: [] },
    { key: 'name', label: '菜单名称', component: 'input', options: [] },
    { key: 'permissionKey', label: '权限标识', component: 'input', options: [] },
    { key: 'path', label: '路径', component: 'input', options: [] },
    { key: 'component', label: '组件', component: 'input', options: [] },
    { key: 'menuType', label: '类型', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() },
    { key: 'sortOrder', label: '排序', component: 'number', options: [] }
  ]
}

function departmentFields() {
  return [
    { key: 'name', label: '部门名称', component: 'input', options: [] },
    { key: 'parentId', label: '父级ID', component: 'input', options: [] },
    { key: 'leader', label: '负责人', component: 'input', options: [] },
    { key: 'phone', label: '电话', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() },
    { key: 'sortOrder', label: '排序', component: 'number', options: [] }
  ]
}

function postFields() {
  return [
    { key: 'name', label: '岗位名称', component: 'input', options: [] },
    { key: 'code', label: '岗位编码', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() },
    { key: 'sortOrder', label: '排序', component: 'number', options: [] }
  ]
}

function paymentAccountFields() {
  return [
    { key: 'userId', label: '用户ID', component: 'input', options: [] },
    { key: 'accountType', label: '账户类型', component: 'input', options: [] },
    { key: 'currency', label: '币种', component: 'input', options: [] },
    { key: 'network', label: '网络', component: 'input', options: [] },
    { key: 'holderName', label: '持有人', component: 'input', options: [] },
    { key: 'bankName', label: '银行', component: 'input', options: [] },
    { key: 'branchName', label: '支行', component: 'input', options: [] },
    { key: 'bankCode', label: '银行代码', component: 'input', options: [] },
    { key: 'accountNo', label: '账号', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() }
  ]
}

function productFields() {
  return [
    { key: 'productType', label: 'Product Type', component: 'select', options: productTypeOptions() },
    { key: 'symbol', label: '产品代码', component: 'input', options: [] },
    { key: 'displayName', label: '产品名称', component: 'input', options: [] },
    { key: 'provider', label: '行情源', component: 'input', options: [] },
    { key: 'providerSymbol', label: '行情源代码', component: 'input', options: [] },
    { key: 'assetClass', label: '分类', component: 'input', options: [] },
    { key: 'baseCurrency', label: '基础币种', component: 'input', options: [] },
    { key: 'quoteCurrency', label: '计价币种', component: 'input', options: [] },
    { key: 'pipSize', label: '点值大小', component: 'number', options: [] },
    { key: 'tickSize', label: '最小跳动', component: 'number', options: [] },
    { key: 'lotSize', label: '标准手', component: 'number', options: [] },
    { key: 'minLot', label: '最小手', component: 'number', options: [] },
    { key: 'maxLot', label: '最大手', component: 'number', options: [] },
    { key: 'leverage', label: '杠杆倍数', component: 'number', options: [] },
    { key: 'spreadMarkup', label: '点差加成', component: 'number', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() }
  ]
}

function paymentMethodFields() {
  return [
    { key: 'name', label: '名称', component: 'input', options: [] },
    { key: 'methodType', label: '类型', component: 'input', options: [] },
    { key: 'currency', label: '网络/货币', component: 'input', options: [] },
    { key: 'enabled', label: '状态', component: 'select', options: booleanOptions() },
    { key: 'displayOrder', label: '排序', component: 'number', options: [] },
    { key: 'instructions', label: '收款信息', component: 'textarea', options: [] }
  ]
}

function contentArticleFields() {
  return [
    { key: 'title', label: '标题', component: 'input', options: [] },
    { key: 'summary', label: '简介', component: 'textarea', options: [] },
    { key: 'body', label: '内容', component: 'richtext', options: [] },
    { key: 'status', label: '状态', component: 'input', options: [] },
    { key: 'language', label: '语言', component: 'input', options: [] },
    { key: 'sortOrder', label: '排序', component: 'number', options: [] }
  ]
}

function contentMessageFields() {
  return [
    { key: 'targetUserId', label: '用户ID', component: 'input', options: [] },
    { key: 'title', label: '标题', component: 'input', options: [] },
    { key: 'body', label: '内容', component: 'richtext', options: [] },
    { key: 'messageType', label: '消息类型', component: 'input', options: [] },
    { key: 'status', label: '状态', component: 'input', options: [] }
  ]
}

function booleanOptions() {
  return [
    { label: '启用', value: 'true' },
    { label: '停用', value: 'false' }
  ]
}

function productTypeOptions() {
  return [
    { label: 'FX_MARGIN', value: 'FX_MARGIN' },
    { label: 'CRYPTO_SPOT', value: 'CRYPTO_SPOT' },
    { label: 'LINEAR_PERP', value: 'LINEAR_PERP' },
    { label: 'INVERSE_PERP', value: 'INVERSE_PERP' }
  ]
}

export function getTableColumnPreference(token: string, pageKey: string) {
  return apiGet<AdminTableColumnPreference | null>(
    `/api/admin/table-tools/preferences/${encodeURIComponent(pageKey)}`,
    token
  )
}

export function saveTableColumnPreference(
  token: string,
  pageKey: string,
  request: { hiddenColumns: string[]; tableSize: string; showBorder: boolean; zebra: boolean }
) {
  return apiPut<AdminTableColumnPreference>(
    `/api/admin/table-tools/preferences/${encodeURIComponent(pageKey)}`,
    request,
    token
  )
}

function getRequestLogsFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<RequestLogRow[]>(`/api/admin/logs/request-logs?${featureQuery(query)}`, token).then((rows) => withArrayMeta(rows, query, {
    key: 'request-logs',
    title: '请求日志',
    group: '日志',
    fields: [
      { key: 'method', label: '方法', component: 'input', options: [] },
      { key: 'path', label: '路径', component: 'input', options: [] },
      { key: 'statusCode', label: '状态码', component: 'number', options: [] }
    ],
    columns: [
      { key: 'method', label: '方法', sortable: true },
      { key: 'path', label: '路径', sortable: true },
      { key: 'clientIp', label: '客户端IP', sortable: true },
      { key: 'statusCode', label: '状态码', sortable: true },
      { key: 'durationMs', label: '耗时ms', sortable: true },
      { key: 'createdAt', label: '请求时间', sortable: true }
    ],
    toolbarActions: [],
    rowActions: [],
    rows
  }))
}

function getVerificationCodesFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<VerificationCodeLogRow[]>(`/api/admin/logs/verification-codes?${featureQuery(query)}`, token).then((rows) => withArrayMeta(rows, query, {
    key: 'verification-codes',
    title: '验证码发送记录',
    group: '日志',
    fields: [
      { key: 'scene', label: '场景', component: 'input', options: [] },
      { key: 'account', label: '账号', component: 'input', options: [] },
      { key: 'channel', label: '通道', component: 'input', options: [] },
      { key: 'code', label: '验证码', component: 'input', options: [] },
      { key: 'status', label: '状态', component: 'input', options: [] },
      { key: 'errorMessage', label: '错误信息', component: 'textarea', options: [] }
    ],
    columns: [
      { key: 'scene', label: '场景', sortable: true },
      { key: 'account', label: '账号', sortable: true },
      { key: 'channel', label: '通道', sortable: true },
      { key: 'code', label: '验证码', sortable: true },
      { key: 'status', label: '状态', sortable: true },
      { key: 'createdAt', label: '发送时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '补录', type: 'modal' }],
    rowActions: [],
    rows
  }))
}

function getProductCategoriesFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<AdminPage<SymbolCategoryRow>>(`/api/admin/market/categories?${featureQuery(query)}`, token).then((page) => withPageMeta(page as AdminPage<unknown>, {
    key: 'product-categories',
    title: '产品分类',
    group: '产品管理',
    fields: [
      { key: 'name', label: '分类名称', component: 'input', options: [] },
      { key: 'code', label: '分类编码', component: 'input', options: [] },
      { key: 'sortOrder', label: '排序', component: 'number', options: [] },
      {
        key: 'enabled',
        label: '状态',
        component: 'select',
        options: [
          { label: '启用', value: 'true' },
          { label: '停用', value: 'false' }
        ]
      }
    ],
    columns: [
      { key: 'name', label: '分类名称', sortable: true },
      { key: 'code', label: '分类编码', sortable: true },
      { key: 'sortOrder', label: '排序', sortable: true },
      { key: 'enabled', label: '状态', sortable: true },
      { key: 'updatedAt', label: '更新时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [{ key: 'edit', label: '编辑', type: 'modal' }],
    rows: page.items
  }))
}

function getPriceSchedulesFeaturePage(token: string, query: FeaturePageQuery) {
  return apiGet<PriceAdjustmentRow[]>(`/api/admin/market/price-adjustments?${featureQuery(query)}`, token).then((rows) => withArrayMeta(rows, query, {
    key: 'price-schedules',
    title: '涨跌设置',
    group: '产品管理',
    fields: [
      { key: 'symbolId', label: '产品ID', component: 'input', options: [] },
      { key: 'mode', label: '模式', component: 'input', options: [] },
      { key: 'adjustmentType', label: '调整类型', component: 'input', options: [] },
      { key: 'targetPrice', label: '目标价格', component: 'number', options: [] },
      { key: 'startsAt', label: '开始时间', component: 'input', options: [] },
      { key: 'endsAt', label: '结束时间', component: 'input', options: [] },
      { key: 'reason', label: '原因', component: 'textarea', options: [] }
    ],
    columns: [
      { key: 'symbol', label: '产品', sortable: true },
      { key: 'mode', label: '模式', sortable: true },
      { key: 'adjustmentType', label: '调整类型', sortable: true },
      { key: 'targetPrice', label: '目标价格', sortable: true },
      { key: 'status', label: '状态', sortable: true },
      { key: 'createdAt', label: '创建时间', sortable: true }
    ],
    toolbarActions: [{ key: 'create', label: '新增', type: 'modal' }],
    rowActions: [{ key: 'cancel', label: '取消', type: 'confirm' }],
    rows
  }))
}

async function runProductCategoryAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  const body = {
    name: String(payload.name ?? ''),
    code: String(payload.code ?? ''),
    sortOrder: Number(payload.sortOrder ?? 0),
    enabled: toBoolean(payload.enabled, true)
  }
  if (request.action === 'create') {
    const created = await apiPost<SymbolCategoryRow>('/api/admin/market/categories', body, token)
    return featureActionResult('product-categories', request.action, created.id, '产品分类已创建')
  }
  if ((request.action === 'edit' || request.action === 'update') && request.rowId) {
    const updated = await apiPut<SymbolCategoryRow>(
      `/api/admin/market/categories/${encodeURIComponent(request.rowId)}`,
      body,
      token
    )
    return featureActionResult('product-categories', request.action, updated.id, '产品分类已更新')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/product-categories/actions', request, token)
}

async function runPriceScheduleAction(token: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  if (request.action === 'create') {
    const symbolId = String(payload.symbolId ?? '')
    const created = await apiPost<PriceAdjustmentRow>(
      `/api/admin/market/symbols/${encodeURIComponent(symbolId)}/price-adjustments`,
      {
        mode: String(payload.mode ?? 'PRICE_REPAIR'),
        adjustmentType: String(payload.adjustmentType ?? 'SET_MID_PRICE'),
        targetPrice: String(payload.targetPrice ?? ''),
        startsAt: blankToNull(payload.startsAt),
        endsAt: blankToNull(payload.endsAt),
        reason: String(payload.reason ?? '后台涨跌设置')
      },
      token
    )
    return featureActionResult('price-schedules', request.action, created.id, '涨跌设置已创建')
  }
  if (request.action === 'cancel' && request.rowId) {
    const canceled = await apiPost<PriceAdjustmentRow>(
      `/api/admin/market/price-adjustments/${encodeURIComponent(request.rowId)}/cancel`,
      { reason: request.reason || '后台取消涨跌设置' },
      token
    )
    return featureActionResult('price-schedules', request.action, canceled.id, '涨跌设置已取消')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/price-schedules/actions', request, token)
}

async function runVerificationCodeAction(token: string, request: AdminFeatureOperationRequest) {
  if (request.action === 'create') {
    const payload = request.payload ?? {}
    const created = await apiPost<VerificationCodeLogRow>(
      '/api/admin/logs/verification-codes',
      {
        scene: String(payload.scene ?? ''),
        account: String(payload.account ?? ''),
        channel: String(payload.channel ?? ''),
        code: String(payload.code ?? ''),
        status: String(payload.status ?? ''),
        errorMessage: blankToNull(payload.errorMessage)
      },
      token
    )
    return featureActionResult('verification-codes', request.action, created.id, '验证码记录已补录')
  }
  return apiPost<AdminFeatureOperationResponse>('/api/admin/features/verification-codes/actions', request, token)
}

async function runTableExportAction(token: string, pageKey: string, request: AdminFeatureOperationRequest) {
  const task = await apiPost<AdminExportTask>(
    '/api/admin/table-tools/export-tasks',
    { pageKey, filterJson: JSON.stringify(request.payload ?? {}) },
    token
  )
  return featureActionResult(pageKey, request.action, task.id, '导出任务已创建')
}

async function runTableImportAction(token: string, pageKey: string, request: AdminFeatureOperationRequest) {
  const payload = request.payload ?? {}
  const task = await apiPost<AdminImportTask>(
    '/api/admin/table-tools/import-tasks',
    { pageKey, fileName: String(payload.fileName ?? `${pageKey}.xlsx`) },
    token
  )
  return featureActionResult(pageKey, request.action, task.id, '导入任务已创建')
}

async function runTableBatchAction(token: string, pageKey: string, request: AdminFeatureOperationRequest) {
  const rowIds = request.rowId ? [request.rowId] : []
  const task = await apiPost<AdminBatchOperation>(
    '/api/admin/table-tools/batch-operations',
    { pageKey, operation: request.action, rowIds, reason: request.reason || '后台批量操作' },
    token
  )
  return featureActionResult(pageKey, request.action, task.id, '批量操作任务已创建')
}

function featureActionResult(pageKey: string, action: string, targetId: string, message: string): AdminFeatureOperationResponse {
  return {
    pageKey,
    action,
    targetId,
    success: true,
    message
  }
}

function toBoolean(value: unknown, fallback: boolean) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'boolean') return value
  return String(value).toLowerCase() === 'true'
}

function blankToNull(value: unknown) {
  const text = value === undefined || value === null ? '' : String(value)
  return text.trim() ? text : null
}

export async function getAdminSnapshot(token: string): Promise<AdminSnapshot> {
  const [users, accounts, orders, positions, trades, ledger, symbols, riskConfigs, auditLogs, marketStatus] = await Promise.all([
    apiGet<AdminPage<AdminUser>>('/api/admin/users?page=0&size=50', token),
    apiGet<AdminPage<AccountRow>>('/api/admin/accounts?page=0&size=50', token),
    apiGet<AdminPage<OrderRow>>('/api/admin/trading/orders?page=0&size=50', token),
    apiGet<AdminPage<PositionRow>>('/api/admin/trading/positions?page=0&size=50', token),
    apiGet<AdminPage<TradeRow>>('/api/admin/trading/trades?page=0&size=50', token),
    apiGet<AdminPage<LedgerRow>>('/api/admin/finance/ledger?page=0&size=50', token),
    apiGet<AdminPage<SymbolRow>>('/api/admin/market/symbols?page=0&size=50', token),
    apiGet<RiskConfigRow[]>('/api/admin/risk/configs', token),
    apiGet<AdminPage<AuditLog>>('/api/admin/audit-logs?page=0&size=50', token),
    apiGet<MarketStatus>('/api/admin/market/status', token)
  ])

  return {
    users: users.items,
    accounts: accounts.items,
    orders: orders.items,
    positions: positions.items,
    trades: trades.items,
    ledger: ledger.items,
    symbols: symbols.items,
    riskConfigs,
    auditLogs: auditLogs.items,
    marketStatus
  }
}

export function updateUserStatus(userId: string, status: AdminUser['status'], token: string) {
  return apiPatch<AdminUser>(
    `/api/admin/users/${encodeURIComponent(userId)}/status`,
    { status, reason: '后台控制台状态更新' },
    token
  )
}
