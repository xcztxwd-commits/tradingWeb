import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join, relative, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { findFrontendBoundaryViolations } from './verify-frontend-boundaries.mjs'

const defaultRoot = fileURLToPath(new URL('..', import.meta.url))
const sourceExtensions = new Set(['.js', '.jsx', '.mjs', '.ts', '.tsx'])
const ignoredSourceDirectories = new Set([
  '.git',
  '.run-logs',
  'coverage',
  'dist',
  'node_modules',
  'target',
])

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
  'backend/src/main/java/com/fxplatform/home/service/HomeCountersService.java',
  'backend/src/main/java/com/fxplatform/home/service/HomeCountersGrowthScheduler.java',
  'backend/src/main/java/com/fxplatform/market/adapter/massive/MassiveQuoteNormalizer.java',
  'backend/src/main/java/com/fxplatform/market/controller/MarketController.java',
  'backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastService.java',
  'backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastScheduler.java',
  'backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java',
  'backend/src/main/java/com/fxplatform/market/service/MarketTestDataScheduler.java',
  'backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeClient.java',
  'backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeRotationScheduler.java',
  'backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionManager.java',
  'backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionMaintenanceScheduler.java',
  'backend/src/main/java/com/fxplatform/chart/controller/ChartController.java',
  'backend/src/main/java/com/fxplatform/trading/controller/TradingController.java',
  'backend/src/main/java/com/fxplatform/trading/service/OrderFillService.java',
  'backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java',
  'backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionScheduler.java',
  'backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java',
  'backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionScheduler.java',
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
  ['backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java', 'findByStatus'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java', 'SystemCloseOrderService'],
  ['backend/src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java', 'executeProtection'],
  ['backend/src/main/java/com/fxplatform/trading/service/PositionService.java', 'recordMarginRelease'],
  ['backend/src/main/java/com/fxplatform/trading/service/PositionService.java', 'floatingPnl'],
  ['backend/src/main/java/com/fxplatform/home/service/HomeCountersGrowthScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/home/service/HomeCountersGrowthScheduler.java', '@Profile("!validation")'],
  ['backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastScheduler.java', '@Profile("!validation")'],
  ['backend/src/main/java/com/fxplatform/market/service/MarketTestDataScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/service/MarketTestDataScheduler.java', '@Profile("!validation")'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeRotationScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeRotationScheduler.java', '@Profile("!validation")'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionMaintenanceScheduler.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionMaintenanceScheduler.java', '@Profile("!validation")'],
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
  ['backend/src/main/resources/application.yml', 'ddl-auto'],
  ['backend/src/main/java/com/fxplatform/home/service/HomeCountersService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/service/QuoteBroadcastService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/service/MarketTestDataService.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceRealtimeClient.java', '@Scheduled'],
  ['backend/src/main/java/com/fxplatform/market/realtime/BinanceSubscriptionManager.java', '@Scheduled']
]

const forbiddenFrontendImports = [
  ['apps/web/src/shared-widgets/trading/market-data/MarketSidePanel.tsx', '/pages/trading'],
  ['apps/web/src/shared-widgets/trading/market-data/quoteMarketDataAdapter.ts', '/pages/trading'],
  ['apps/web/src/shared-widgets/trading/market-data/quoteMarketDataSnapshot.ts', '/pages/trading']
]

export function findArchitectureViolations(rootDir) {
  const root = resolve(rootDir)
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

  addTradingLabArchitectureViolations(root, failures)
  return failures
}

function runCli() {
  const failures = findArchitectureViolations(defaultRoot)
  if (failures.length > 0) {
    console.error('Architecture verification failed:')
    for (const failure of failures) {
      console.error(`- ${failure}`)
    }
    process.exit(1)
  }
  console.log('Architecture verification passed.')
}

function addTradingLabArchitectureViolations(root, failures) {
  findAdminValidationCalls(root, failures)
  findOrchestratorOrderServiceImports(root, failures)
  verifyFixedValidationSupervisor(root, failures)
  verifyValidationComposeIsolation(root, failures)
  verifyValidationProfileIsolation(root, failures)
  verifyChunkedReportEntity(root, failures)
}

function findAdminValidationCalls(root, failures) {
  const adminSource = join(root, 'apps/admin/src')
  const directValidation = /(?:https?:\/\/(?:(?:127\.0\.0\.1|localhost|\[::1\]):18087|validation-backend(?::\d+)?)|\/internal\/validation(?:\/|\b)|\bVITE_[A-Z0-9_]*VALIDATION(?:_[A-Z0-9_]*)?\b)/iu
  for (const file of collectSourceFiles(adminSource, sourceExtensions)) {
    if (!directValidation.test(readFileSync(file, 'utf8'))) continue
    failures.push(
      `Trading Lab admin must not call validation directly: ${projectPath(root, file)}`,
    )
  }
}

function findOrchestratorOrderServiceImports(root, failures) {
  const orchestratorSource = join(
    root,
    'backend/src/main/java/com/fxplatform/tradinglab',
  )
  const directOrderService = /\b(?:import\s+)?com\.fxplatform\.trading\.service\.OrderService\b/u
  for (const file of collectSourceFiles(orchestratorSource, new Set(['.java']))) {
    if (!directOrderService.test(readFileSync(file, 'utf8'))) continue
    failures.push(
      `Trading Lab orchestrator must not import OrderService: ${projectPath(root, file)}`,
    )
  }
}

function verifyFixedValidationSupervisor(root, failures) {
  const file = join(root, 'scripts/validation-supervisor.mjs')
  if (!existsSync(file)) {
    failures.push(
      'Validation Supervisor command and compose path must be fixed: missing scripts/validation-supervisor.mjs',
    )
    return
  }
  const content = readFileSync(file, 'utf8')
  const dynamicAuthority = /(?:\bshell\s*:\s*true\b|(?:request|body|payload)\s*\.\s*(?:command|path|composeFile)\b|process\.env(?:\.[A-Z0-9_]*(?:COMMAND|COMPOSE|PATH)[A-Z0-9_]*|\[['"][^'"]*(?:COMMAND|COMPOSE|PATH)[^'"]*['"]\]))/iu
  const fixedAuthority = (
    content.includes("COMPOSE_PROJECT = 'fx-trading-validation'")
    && content.includes("'docker-compose.validation.yml'")
    && /\bspawnImpl\(\s*['"]docker['"]\s*,\s*\[\.\.\.fixedArgs\]/u.test(content)
    && /\bshell\s*:\s*false\b/u.test(content)
  )
  if (dynamicAuthority.test(content) || !fixedAuthority) {
    failures.push(
      'Validation Supervisor command and compose path must be fixed: scripts/validation-supervisor.mjs',
    )
  }
}

function verifyValidationComposeIsolation(root, failures) {
  const mainFile = join(root, 'infra/docker-compose.yml')
  const validationFile = join(root, 'infra/docker-compose.validation.yml')
  if (!existsSync(validationFile)) {
    failures.push(
      'Validation Compose must not share main volumes or networks: missing infra/docker-compose.validation.yml',
    )
    return
  }

  const validation = readFileSync(validationFile, 'utf8')
  const shared = new Set()
  if (existsSync(mainFile)) {
    const main = readFileSync(mainFile, 'utf8')
    for (const section of ['volumes', 'networks']) {
      for (const name of yamlTopLevelKeys(main, section)) {
        if (containsYamlName(validation, name)) shared.add(name)
      }
    }
  }
  for (const name of [
    'fx_postgres_data',
    'fx-platform-postgres',
    'fx-platform-redis',
    'fx-trading-platform-default',
    'fx-trading-platform_default',
  ]) {
    if (containsYamlName(validation, name)) shared.add(name)
  }

  if (shared.size > 0) {
    failures.push(
      `Validation Compose must not share main volumes or networks: ${[...shared].sort().join(', ')}`,
    )
  }
}

function verifyValidationProfileIsolation(root, failures) {
  const profileFile = join(
    root,
    'backend/src/main/resources/application-validation.yml',
  )
  if (!existsSync(profileFile)) {
    failures.push(
      'Validation profile must keep demo execution and external providers disabled: missing application-validation.yml',
    )
    return
  }

  const profile = readFileSync(profileFile, 'utf8')
  const execution = yamlTopLevelBlock(profile, 'execution')
  const market = yamlTopLevelBlock(profile, 'market')
  const providerConfiguration = ['massive', 'binance', 'okx']
    .map((section) => yamlTopLevelBlock(profile, section))
    .join('\n')
  const executionMode = yamlScalar(execution, 'mode')
  const marketProviders = yamlScalars(market, 'provider')
  const unsafeProviderUrl = [...providerConfiguration.matchAll(/https?:\/\/[^\s#'"]+/giu)]
    .map((match) => match[0])
    .some((url) => url !== 'http://127.0.0.1:9')
  const unsafeExecutionEndpoint = [...execution.matchAll(/https?:\/\/[^\s#'"]+/giu)]
    .map((match) => match[0])
    .some((url) => url !== 'http://127.0.0.1:9')
  const unsafeProfile = (
    executionMode !== 'demo'
    || /^\s+enabled:\s*true\s*(?:#.*)?$/imu.test(market)
    || marketProviders.some((provider) => provider !== 'local')
    || unsafeProviderUrl
    || unsafeExecutionEndpoint
  )

  const validationCompose = join(root, 'infra/docker-compose.validation.yml')
  const compose = existsSync(validationCompose)
    ? readFileSync(validationCompose, 'utf8')
    : ''
  const composeMode = yamlScalar(compose, 'EXECUTION_MODE')
  const composeEnablesExternalMarket = [
    'MARKET_REALTIME_ENABLED',
    'MARKET_REALTIME_BACKFILL_ENABLED',
    'MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED',
    'MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED',
  ].some((key) => yamlScalar(compose, key) === 'true')

  if (
    unsafeProfile
    || (composeMode !== null && composeMode !== 'demo')
    || composeEnablesExternalMarket
  ) {
    failures.push(
      'Validation profile must keep demo execution and external providers disabled: backend/src/main/resources/application-validation.yml',
    )
  }
}

function verifyChunkedReportEntity(root, failures) {
  const reportEntity = join(
    root,
    'backend/src/main/java/com/fxplatform/tradinglab/entity/TradingLabReportEntity.java',
  )
  if (!existsSync(reportEntity)) return

  const chunkEntity = join(
    root,
    'backend/src/main/java/com/fxplatform/tradinglab/entity/TradingLabReportChunkEntity.java',
  )
  const chunkMapping = existsSync(chunkEntity)
    ? readFileSync(chunkEntity, 'utf8')
    : ''
  if (!/@TableName\(\s*(?:value\s*=\s*)?["']trading_lab\.report_chunks["']/u.test(
    chunkMapping,
  )) {
    failures.push(
      'Trading Lab reports must use the chunk table: missing trading_lab.report_chunks entity mapping',
    )
  }
}

function collectSourceFiles(directory, extensions) {
  if (!existsSync(directory)) return []
  const files = []
  const entries = readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
  for (const entry of entries) {
    if (entry.isSymbolicLink()) continue
    const child = join(directory, entry.name)
    if (entry.isDirectory()) {
      if (
        ignoredSourceDirectories.has(entry.name)
        || entry.name === '__tests__'
      ) {
        continue
      }
      files.push(...collectSourceFiles(child, extensions))
      continue
    }
    if (
      !entry.isFile()
      || !extensions.has(extname(entry.name))
      || /\.(?:spec|test)\.[^.]+$/iu.test(entry.name)
    ) {
      continue
    }
    files.push(child)
  }
  return files
}

function yamlTopLevelKeys(content, section) {
  const block = yamlTopLevelBlock(content, section)
  const keys = []
  for (const line of block.split(/\r?\n/u).slice(1)) {
    const match = /^ {2}([A-Za-z0-9_.-]+):(?:\s|$)/u.exec(line)
    if (match) keys.push(match[1])
  }
  return keys
}

function yamlTopLevelBlock(content, section) {
  const lines = content.split(/\r?\n/u)
  const heading = new RegExp(`^${escapeRegExp(section)}:\\s*(?:#.*)?$`, 'u')
  const start = lines.findIndex((line) => heading.test(line))
  if (start === -1) return ''
  let end = start + 1
  while (
    end < lines.length
    && (
      lines[end].trim() === ''
      || /^\s/u.test(lines[end])
      || /^#/u.test(lines[end])
    )
  ) {
    end += 1
  }
  return lines.slice(start, end).join('\n')
}

function yamlScalar(content, key) {
  return yamlScalars(content, key)[0] ?? null
}

function yamlScalars(content, key) {
  const pattern = new RegExp(
    `^\\s*${escapeRegExp(key)}:\\s*["']?([^"'\\s#]+)["']?\\s*(?:#.*)?$`,
    'gimu',
  )
  return [...content.matchAll(pattern)].map((match) => match[1].toLowerCase())
}

function containsYamlName(content, name) {
  const escaped = escapeRegExp(name)
  return new RegExp(
    `(?:^|[^A-Za-z0-9_.-])${escaped}(?:$|[^A-Za-z0-9_.-])`,
    'mu',
  ).test(content)
}

function projectPath(root, file) {
  return relative(root, file).replaceAll('\\', '/')
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

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

const invokedPath = process.argv[1]
  ? pathToFileURL(resolve(process.argv[1])).href
  : ''
if (invokedPath === import.meta.url) {
  runCli()
}
