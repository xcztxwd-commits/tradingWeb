export type { ApiResponse, AuthResponse } from '@fx-platform/shared-types'

export type AdminUser = {
  id: string
  email: string
  phone: string | null
  status: 'ACTIVE' | 'FROZEN' | 'DISABLED'
  role: 'USER' | 'ADMIN'
  kycStatus: string
  riskLevel: string
  createdAt: string | null
}

export type AccountRow = {
  id: string
  userId: string
  accountType: string
  balance: number
  equity: number
  usedMargin: number
  freeMargin: number
  status: string
}

export type OrderRow = {
  id: string
  userId: string
  accountId: string
  symbol: string
  side: string
  orderType: string
  status: string
  lots: number
  executionPrice: number | null
  createdAt: string | null
}

export type TradeRow = {
  id: string
  orderId: string
  accountId: string
  symbol: string
  side: string
  lots: number
  price: number
  realizedPnl: number
  executedAt: string | null
}

export type RiskConfigRow = {
  id: string
  symbol: string
  maxLeverage: number
  maxLots: number
  marginCallLevel: number
  stopOutLevel: number
  enabled: boolean
  updatedAt: string | null
}

export type AuditLog = {
  id: string
  actorUserId: string | null
  action: string
  targetType: string | null
  targetId: string | null
  details: string | null
  createdAt: string | null
}

export type MarketStatus = {
  massiveConfigured: boolean
  redisCacheEnabled: boolean
  quoteStaleMs: number
  status: string
  sourceMode?: string
  providerStatus?: string
}

export type AdminPage<T> = {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export type DashboardSummary = {
  userCount: number
  accountCount: number
  orderCount: number
  pendingOrderCount: number
  positionCount: number
  openPositionCount: number
  tradeCount: number
  ledgerEntryCount: number
  symbolCount: number
  auditLogCount: number
}

export type PositionRow = {
  id: string
  userId: string
  accountId: string
  symbol: string
  side: string
  lots: number
  openPrice: number | null
  currentPrice: number | null
  floatingPnl: number | null
  status: string
  openedAt: string | null
}

export type LedgerRow = {
  id: string
  accountId: string
  entryType: string
  amount: number
  balanceAfter: number
  currency: string
  referenceType: string | null
  createdAt: string | null
}

export type SymbolRow = {
  id: string
  symbol: string
  displayName: string
  provider?: string | null
  providerSymbol?: string | null
  assetClass: string
  productType: ProductType
  baseCurrency: string
  quoteCurrency: string
  minLot: number
  maxLot: number
  leverage: number
  enabled: boolean
  iconUrl?: string | null
  displayEnabled?: boolean
  quoteEnabled?: boolean
  chartEnabled?: boolean
  orderBookEnabled?: boolean
  tradable?: boolean
  featured?: boolean
  displayGroup?: string | null
  displayOrder?: number
}

export type ProductType = 'FX_MARGIN' | 'CRYPTO_SPOT' | 'LINEAR_PERP' | 'INVERSE_PERP'

export type SymbolPayload = {
  symbol: string
  displayName: string
  provider: string
  providerSymbol: string
  assetClass: string
  productType: ProductType
  baseCurrency: string
  quoteCurrency: string
  pipSize: string
  tickSize: string
  lotSize: string
  minLot: string
  maxLot: string
  leverage: number
  spreadMarkup: string
  enabled: boolean
  iconUrl: string | null
  displayEnabled: boolean
  quoteEnabled: boolean
  chartEnabled: boolean
  orderBookEnabled: boolean
  tradable: boolean
  featured: boolean
  displayGroup: string | null
  displayOrder: number
  confirmationText?: string
}

export type DataProviderRow = {
  id: string
  code: string
  name: string
  providerType: string
  assetClasses: string[]
  restBaseUrl: string | null
  wsUrl: string | null
  enabled: boolean
  priority: number
  timeoutMs: number
  rateLimitPerMinute: number
  healthStatus: string
  lastHealthCheckAt: string | null
  lastSuccessAt: string | null
  lastFailureAt: string | null
  failureCount: number
  avgLatencyMs: number | null
  lastQuoteSuccessAt: string | null
  quoteStalenessMs: number | null
  lastInstrumentSyncAt: string | null
  lastInstrumentSyncCount: number
  configJson: string
  capabilities: string[]
  capabilityStatuses: DataProviderCapabilityStatus[]
  createdAt: string | null
  updatedAt: string | null
}

export type DataProviderCapabilityStatus = {
  capability: string
  enabled: boolean
  supported: boolean | null
  status: string
}

export type DataProviderPayload = {
  code: string
  name: string
  providerType: string
  assetClasses: string[]
  restBaseUrl: string | null
  wsUrl: string | null
  enabled: boolean
  priority: number
  timeoutMs: number
  rateLimitPerMinute: number
  configJson: string
  confirmationText?: string
}

export type ProviderInstrumentRow = {
  id: string
  providerId: string
  providerSymbol: string
  assetClass: string
  baseAsset: string | null
  quoteAsset: string | null
  displayName: string | null
  listed: boolean
  rawJson: string
  lastSyncedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export type ProviderSyncResponse = {
  providerId: string
  syncedCount: number
}

export type SymbolProviderBindingRow = {
  id: string
  symbolId: string
  providerId: string
  providerInstrumentId: string | null
  providerSymbol: string
  priority: number
  enabled: boolean
  configJson: string
  createdAt: string | null
  updatedAt: string | null
}

export type SymbolProviderBindingPayload = {
  providerId: string
  providerInstrumentId: string | null
  providerSymbol: string
  priority: number
  enabled: boolean
  configJson: string
}

export type SymbolDisplayPayload = {
  iconUrl: string | null
  displayEnabled: boolean
  quoteEnabled: boolean
  chartEnabled: boolean
  orderBookEnabled: boolean
  tradable: boolean
  featured: boolean
  displayGroup: string | null
  displayOrder: number
}

export type PaymentMethodRow = {
  id: string
  name: string
  methodType: string
  currency: string
  enabled: boolean
  displayOrder: number
}

export type ContentMessageRow = {
  id: string
  targetUserId: string | null
  title: string
  messageType: string
  status: string
  createdAt: string | null
}

export type ContentArticleRow = {
  id: string
  articleType: string
  title: string
  status: string
  language: string
  createdAt: string | null
}

export type DictionaryRow = {
  id: string
  groupKey: string
  itemKey: string
  itemValue: string
  enabled: boolean
  displayOrder: number
}

export type SystemSettingRow = {
  id: string
  settingKey: string
  settingValue: string
  valueType: string
  editable: boolean
}

export type AdminFeatureOption = {
  label: string
  value: string
}

export type AdminFeatureField = {
  key: string
  label: string
  component: 'input' | 'select' | 'date' | 'dateRange' | 'number' | 'password' | 'textarea' | 'richtext' | string
  options: AdminFeatureOption[]
}

export type AdminFeatureColumn = {
  key: string
  label: string
  sortable: boolean
}

export type AdminFeatureAction = {
  key: string
  label: string
  type: 'modal' | 'confirm' | 'download' | 'upload' | 'toggle' | 'drawer' | 'submit' | 'reset' | 'menu' | string
}

export type AdminFeaturePage = {
  key: string
  title: string
  group: string
  fields: AdminFeatureField[]
  columns: AdminFeatureColumn[]
  toolbarActions: AdminFeatureAction[]
  rowActions: AdminFeatureAction[]
  rows: Record<string, unknown>[]
  page?: number
  size?: number
  total?: number
  totalPages?: number
}

export type AdminFeatureOperationRequest = {
  action: string
  rowId?: string
  reason?: string
  payload?: Record<string, unknown>
}

export type AdminFeatureOperationResponse = {
  pageKey: string
  action: string
  targetId: string
  success: boolean
  message: string
}
