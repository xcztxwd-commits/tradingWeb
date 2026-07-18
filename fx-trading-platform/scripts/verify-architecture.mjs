import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

import { findFrontendBoundaryViolations } from './verify-frontend-boundaries.mjs'

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')

const requiredFiles = [
  'backend/pom.xml',
  'backend/src/main/java/com/fxplatform/FxPlatformApplication.java',
  'backend/src/main/java/com/fxplatform/common/response/ApiResponse.java',
  'backend/src/main/java/com/fxplatform/common/exception/GlobalExceptionHandler.java',
  'backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java',
  'backend/src/main/java/com/fxplatform/common/mybatis/JsonbStringTypeHandler.java',
  'backend/src/main/java/com/fxplatform/common/mybatis/MybatisPlusConfig.java',
  'backend/src/main/java/com/fxplatform/common/mybatis/UuidTypeHandler.java',
  'backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java',
  'backend/src/main/java/com/fxplatform/common/security/JwtService.java',
  'backend/src/main/java/com/fxplatform/common/web/RequestIdFilter.java',
  'backend/src/main/java/com/fxplatform/auth/controller/AuthController.java',
  'backend/src/main/java/com/fxplatform/auth/service/AuthService.java',
  'backend/src/main/java/com/fxplatform/account/service/AccountService.java',
  'backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java',
  'backend/src/main/java/com/fxplatform/market/adapter/massive/MassiveQuoteNormalizer.java',
  'backend/src/main/java/com/fxplatform/market/controller/MarketController.java',
  'backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastService.java',
  'backend/src/main/java/com/fxplatform/chart/controller/ChartController.java',
  'backend/src/main/java/com/fxplatform/trading/controller/TradingController.java',
  'backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java',
  'backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java',
  'backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java',
  'backend/src/main/java/com/fxplatform/risk/service/RiskCheckService.java',
  'backend/src/main/java/com/fxplatform/execution/ExecutionAdapter.java',
  'backend/src/main/java/com/fxplatform/execution/SimulatedExecutionAdapter.java',
  'backend/src/main/java/com/fxplatform/admin/dto/response/AdminRiskConfigResponse.java',
  'backend/src/main/java/com/fxplatform/admin/dto/response/AdminTradeResponse.java',
  'backend/src/main/java/com/fxplatform/admin/dto/AdminUserResponse.java',
  'backend/src/main/java/com/fxplatform/admin/controller/AdminController.java',
  'backend/src/main/java/com/fxplatform/admin/controller/AdminRiskController.java',
  'backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java',
  'backend/src/main/java/com/fxplatform/admin/controller/AdminUserController.java',
  'backend/src/main/java/com/fxplatform/admin/service/AdminBootstrapService.java',
  'backend/src/main/java/com/fxplatform/risk/entity/RiskConfigEntity.java',
  'backend/src/main/java/com/fxplatform/risk/repository/RiskConfigRepository.java',
  'backend/src/main/resources/application.yml',
  'backend/src/main/resources/db/migration/V1__init_schemas.sql',
  'backend/src/main/resources/db/migration/V2__auth_tables.sql',
  'backend/src/main/resources/db/migration/V3__account_tables.sql',
  'backend/src/main/resources/db/migration/V4__market_tables.sql',
  'backend/src/main/resources/db/migration/V5__trading_tables.sql',
  'backend/src/main/resources/db/migration/V6__ledger_tables.sql',
  'backend/src/main/resources/db/migration/V7__risk_tables.sql',
  'backend/src/main/resources/db/migration/V8__audit_tables.sql',
  'backend/src/main/resources/db/migration/V9__seed_initial_data.sql',
  'backend/src/main/resources/db/migration/V10__position_margin_held.sql',
  'docs/architecture.md',
  'apps/web/package.json',
  'apps/web/src/app/App.tsx',
  'apps/web/src/routes/trading/TradingRoute.tsx',
  'apps/web/src/pc/pages/trading/PcTradingTerminal.tsx',
  'apps/web/src/mobile/pages/trading/MobileTradingTerminal.tsx',
  'apps/web/src/shared-widgets/trading/components/KLineChartPanel.tsx',
  'apps/web/src/shared-widgets/trading/market-data/MarketSidePanel.tsx',
  'apps/web/src/shared-widgets/trading/order-form/TradePanel.tsx',
  'packages/frontend-core/src/market/tradingMarketApi.ts',
  'packages/frontend-core/src/market/tradingMarketAdapters.ts',
  'packages/frontend-core/src/market/tradingModels.ts',
  'packages/frontend-core/src/api/apiClient.ts',
  'packages/frontend-core/src/market/marketStream.ts',
  'apps/admin/package.json',
  'apps/admin/src/app/AdminApp.tsx',
  'apps/admin/src/pages/DashboardPage.tsx',
  'apps/admin/src/pages/RiskPage.tsx',
  'apps/admin/src/pages/TradesPage.tsx',
  'apps/admin/src/services/adminApi.ts',
  'apps/admin/src/services/authApi.ts',
  'infra/docker-compose.yml',
  '.env.example',
  'README.md'
]

const retiredFiles = [
  'apps/web/src/pages/trade/TradePage.tsx',
  'apps/web/src/components/chart/KLineChartWrapper.tsx',
  'apps/web/src/components/order-panel/OrderPanel.tsx',
  'apps/web/src/pages/trading/TradingPage.tsx',
  'apps/web/src/pages/trading/components/KLineChartPanel.tsx',
  'apps/web/src/pages/trading/components/TerminalSkeleton.tsx',
  'apps/web/src/features/trading/components/TradePanel.tsx',
  'apps/web/src/components/market-side-panel/MarketSidePanel.tsx'
]

const contentChecks = [
  ['backend/pom.xml', 'spring-boot-starter-websocket'],
  ['backend/pom.xml', '<java.version>21</java.version>'],
  ['backend/pom.xml', 'mybatis-plus-spring-boot3-starter'],
  ['backend/pom.xml', 'hutool-all'],
  ['backend/pom.xml', 'springdoc-openapi-starter-webmvc-ui'],
  ['backend/src/main/resources/application.yml', 'mybatis-plus:'],
  ['backend/src/main/resources/application.yml', 'MASSIVE_API_KEY'],
  ['backend/src/main/java/com/fxplatform/FxPlatformApplication.java', '@MapperScan'],
  ['backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java', 'BaseMapper'],
  ['backend/src/main/java/com/fxplatform/common/mybatis/UuidTypeHandler.java', 'UUID'],
  ['backend/src/main/java/com/fxplatform/common/mybatis/JsonbStringTypeHandler.java', 'jsonb'],
  ['backend/src/main/resources/db/migration/V5__trading_tables.sql', 'idempotency_key'],
  ['backend/src/main/java/com/fxplatform/trading/service/OrderService.java', 'RiskCheckService'],
  ['backend/src/main/java/com/fxplatform/trading/service/OrderService.java', 'ExecutionAdapter'],
  ['backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java', 'recordMarginHold'],
  ['backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java', 'findByStatus'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java', 'SystemCloseOrderService'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java', 'executeProtection'],
  ['backend/src/main/java/com/fxplatform/trading/service/PositionService.java', 'recordMarginRelease'],
  ['backend/src/main/java/com/fxplatform/trading/service/PositionService.java', 'floatingPnl'],
  ['backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/common/market/SymbolNormalizer.java', '/topic/market/quotes/'],
  ['backend/src/main/java/com/fxplatform/market/websocket/MarketWsPublisher.java', 'SymbolNormalizer.quoteTopic'],
  ['backend/src/main/java/com/fxplatform/ledger/service/LedgerService.java', 'LedgerEntryEntity'],
  ['backend/src/main/java/com/fxplatform/ledger/enums/LedgerEntryType.java', 'MARGIN_HOLD'],
  ['backend/src/main/java/com/fxplatform/ledger/enums/LedgerEntryType.java', 'MARGIN_RELEASE'],
  ['backend/src/main/java/com/fxplatform/admin/controller/AdminUserController.java', 'AdminUserResponse'],
  ['backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java', '/trades'],
  ['backend/src/main/java/com/fxplatform/admin/controller/AdminRiskController.java', '/api/admin/risk'],
  ['backend/src/main/java/com/fxplatform/admin/service/AdminBootstrapService.java', 'UserRole.ADMIN'],
  ['backend/src/main/java/com/fxplatform/common/security/SecurityConfig.java', '"/ws"'],
  ['apps/web/src/app/App.tsx', 'path="/trade"'],
  ['apps/web/src/app/App.tsx', 'path="/trading" element={<LegacyTradingRedirect />}'],
  ['apps/web/src/app/App.tsx', 'path="/trade/spot/:symbol?"'],
  ['apps/web/src/app/App.tsx', 'path="/trade/perpetual/:symbol?"'],
  ['apps/web/src/shared-widgets/trading/market-data/MarketSidePanel.tsx', '../../../components/loading/TerminalSkeleton'],
  ['apps/web/src/components/loading/TerminalSkeleton.tsx', 'export function OrderBookSkeleton'],
  ['apps/web/src/routes/trading/TradingRoute.tsx', '../../components/loading/TerminalSkeleton'],
  ['packages/frontend-core/src/api/marketApi.ts', '/api/market'],
  ['packages/frontend-core/src/market/marketStream.ts', '/topic/market/quotes/'],
  ['packages/frontend-core/src/api/tradingApi.ts', '/close?accountId='],
  ['packages/frontend-core/src/api/marketApi.ts', 'Massive'],
  ['apps/web/package.json', 'ensure-klinecharts-dist.mjs'],
  ['apps/web/vite.config.ts', '../../../dist/index.esm.js'],
  ['apps/admin/src/services/adminApi.ts', '/api/admin/users'],
  ['apps/admin/src/services/adminApi.ts', '/api/admin/trading/trades'],
  ['apps/admin/src/services/adminApi.ts', '/api/admin/risk/configs'],
  ['scripts/smoke-admin.mjs', 'ADMIN_USER_STATUS_UPDATE']
]

const forbiddenContentChecks = [
  ['backend/pom.xml', 'spring-boot-starter-data-jpa'],
  ['backend/src/main/java', 'jakarta.persistence'],
  ['backend/src/main/java', 'JpaRepository'],
  ['backend/src/main/resources/application.yml', 'ddl-auto']
]

const forbiddenFrontendImports = [
  ['apps/web/src/shared-widgets/trading/market-data/MarketSidePanel.tsx', '/pages/trading'],
  ['apps/web/src/shared-widgets/trading/market-data/quoteMarketDataAdapter.ts', '/pages/trading'],
  ['apps/web/src/shared-widgets/trading/market-data/quoteMarketDataSnapshot.ts', '/pages/trading']
]

const failures = []

for (const file of requiredFiles) {
  if (!existsSync(join(root, file))) {
    failures.push(`Missing required file: ${file}`)
  }
}

for (const file of retiredFiles) {
  if (existsSync(join(root, file))) {
    failures.push(`Retired file should be removed: ${file}`)
  }
}

for (const [file, expected] of contentChecks) {
  const path = join(root, file)
  if (!existsSync(path)) {
    failures.push(`Missing content target: ${file}`)
    continue
  }
  const content = readFileSync(path, 'utf8')
  if (!content.includes(expected)) {
    failures.push(`Expected "${expected}" in ${file}`)
  }
}

for (const [target, forbidden] of forbiddenContentChecks) {
  const path = join(root, target)
  if (!existsSync(path)) {
    continue
  }
  const content = readTarget(path)
  if (content.includes(forbidden)) {
    failures.push(`Forbidden "${forbidden}" found in ${target}`)
  }
}

for (const [file, forbidden] of forbiddenFrontendImports) {
  const path = join(root, file)
  if (!existsSync(path)) {
    continue
  }
  const content = readFileSync(path, 'utf8')
  if (content.includes(forbidden)) {
    failures.push(`Forbidden frontend import "${forbidden}" found in ${file}`)
  }
}

const frontendMarketApi = join(root, 'packages/frontend-core/src/api/marketApi.ts')
if (existsSync(frontendMarketApi)) {
  const marketApi = readFileSync(frontendMarketApi, 'utf8')
  if (/https?:\/\/[^'"]*massive/i.test(marketApi)) {
    failures.push('Frontend must not call Massive directly')
  }
}

for (const violation of findFrontendBoundaryViolations(root)) {
  failures.push(`Frontend dependency boundary: ${violation}`)
}

if (failures.length > 0) {
  console.error('Architecture verification failed:')
  for (const failure of failures) {
    console.error(`- ${failure}`)
  }
  process.exit(1)
}

console.log('Architecture verification passed.')

function readTarget(path) {
  if (!existsSync(path)) {
    return ''
  }
  const stat = readFileOrDirectoryStat(path)
  if (!stat.isDirectory()) {
    return readFileSync(path, 'utf8')
  }
  return readDirectoryContent(path)
}

function readFileOrDirectoryStat(path) {
  return statSync(path)
}

function readDirectoryContent(path) {
  const chunks = []
  for (const entry of readdirSync(path)) {
    const child = join(path, entry)
    const stat = statSync(child)
    if (stat.isDirectory()) {
      chunks.push(readDirectoryContent(child))
    } else if (child.endsWith('.java')) {
      chunks.push(readFileSync(child, 'utf8'))
    }
  }
  return chunks.join('\n')
}
