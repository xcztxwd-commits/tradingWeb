package com.fxplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.trading.enums.OrderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

  @Test
  void tradingServiceUsesRiskAndExecutionAdapterBoundaries() throws Exception {
    String orderService = Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/OrderService.java"));

    assertThat(orderService).contains("RiskCheckService");
    assertThat(orderService).contains("ExecutionAdapter");
    assertThat(orderService).doesNotContain("double ");
    assertThat(orderService).doesNotContain("float ");
  }

  @Test
  void flywayOwnsSchemaCreation() throws Exception {
    String application = Files.readString(Path.of("src/main/resources/application.yml"));

    assertThat(application).contains("flyway:");
    assertThat(Files.exists(Path.of("src/main/resources/db/migration/V1__init_schemas.sql"))).isTrue();
  }

  @Test
  void backendUsesMybatisPlusAndHutoolInsteadOfJpa() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));

    assertThat(pom).contains("mybatis-plus-spring-boot3-starter");
    assertThat(pom).contains("mybatis-plus-jsqlparser");
    assertThat(pom).contains("hutool-all");
    assertThat(pom).doesNotContain("spring-boot-starter-data-jpa");

    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      String productionSource = files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .map(ArchitectureRulesTest::readUnchecked)
          .reduce("", String::concat);

      assertThat(productionSource).doesNotContain("JpaRepository");
      assertThat(productionSource).doesNotContain("jakarta.persistence");
      assertThat(productionSource).contains("com.baomidou.mybatisplus");
      assertThat(productionSource).contains("cn.hutool");
    }
  }

  @Test
  void mybatisPlusOwnsEntityAuditFieldFilling() throws Exception {
    String fxBaseMapper = Files.readString(Path.of("src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java"));

    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      String productionSource = files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .map(ArchitectureRulesTest::readUnchecked)
          .reduce("", String::concat);

      assertThat(productionSource).doesNotContain("void prePersist(");
      assertThat(productionSource).doesNotContain("void preUpdate(");
      assertThat(productionSource).contains("MetaObjectHandler");
      assertThat(productionSource).contains("@TableField(fill = FieldFill.INSERT)");
      assertThat(productionSource).contains("@TableField(fill = FieldFill.INSERT_UPDATE)");
    }

    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      String entitySource = files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith("Entity.java"))
          .map(ArchitectureRulesTest::readUnchecked)
          .reduce("", String::concat)
          .replace("\r\n", "\n");

      assertEveryAuditFieldUsesMybatisPlusFill(entitySource, "createdAt", "INSERT");
      assertEveryAuditFieldUsesMybatisPlusFill(entitySource, "executedAt", "INSERT");
      assertEveryAuditFieldUsesMybatisPlusFill(entitySource, "openedAt", "INSERT");
      assertEveryAuditFieldUsesMybatisPlusFill(entitySource, "updatedAt", "INSERT_UPDATE");
    }

    assertThat(fxBaseMapper).doesNotContain("touchTimestamps");
    assertThat(fxBaseMapper).doesNotContain("ReflectUtil");
  }

  @Test
  void baseMapperDoesNotExposeRawOrderColumnSorting() throws Exception {
    String fxBaseMapper = Files.readString(Path.of("src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java"));

    assertThat(fxBaseMapper).doesNotContain("String orderColumn");
    assertThat(fxBaseMapper).doesNotContain("orderBy(true, asc, orderColumn)");
    assertThat(fxBaseMapper).contains("Map<String, String>");
  }

  @Test
  void adminAuditDetailsUseSharedJsonBuilder() throws Exception {
    String financeCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java"));
    String tradingCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminTradingCommandService.java"));
    String featureOperation = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java"));
    String userService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminUserService.java"));
    String fundOrderService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminFundOrderService.java"));
    String riskCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminRiskCommandService.java"));
    String memberService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminMemberService.java"));
    String marketCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java"));
    String configCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminConfigCommandService.java"));
    String contentCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminContentCommandService.java"));
    String combined = financeCommand
        + tradingCommand
        + featureOperation
        + userService
        + fundOrderService
        + riskCommand
        + memberService
        + marketCommand
        + configCommand
        + contentCommand;

    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/audit/service/AuditDetailsBuilder.java"))).isTrue();
    assertThat(combined).doesNotContain("private String escape");
    assertThat(combined).doesNotContain("return \"{\"");
    assertThat(combined).doesNotContain("JSONUtil.toJsonStr(MapUtil.builder()");
    assertThat(combined).contains("AuditDetailsBuilder.create()");
  }

  @Test
  void marketTestDataServiceDelegatesCandleUpsertToRepository() throws Exception {
    String marketTestDataService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/market/service/MarketTestDataService.java"));

    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java")))
        .isTrue();
    assertThat(marketTestDataService).doesNotContain("JdbcTemplate");
    assertThat(marketTestDataService).doesNotContain("INSERT INTO market.candles");
  }

  @Test
  void adminFeatureOperationsUseActionHandlerRegistry() throws Exception {
    String operationService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminFeatureOperationService.java"));

    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/admin/service/AdminFeatureActionHandler.java")))
        .isTrue();
    assertThat(operationService).contains("List<AdminFeatureActionHandler>");
    assertThat(operationService).doesNotContain("applyDomainSideEffects");
  }

  @Test
  void adminFeatureCatalogIsSplitByPageGroup() throws Exception {
    Path catalogPath = Path.of("src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java");
    String catalog = Files.readString(catalogPath);
    long lines = Files.readAllLines(catalogPath).size();

    assertThat(lines).isLessThanOrEqualTo(250);
    assertThat(catalog).contains("AdminPermissionFeaturePages.pages()");
    assertThat(catalog).contains("AdminProductFeaturePages.pages()");
    assertThat(catalog).contains("AdminFinanceFeaturePages.pages()");
    assertThat(catalog).contains("AdminMemberFeaturePages.pages()");
    assertThat(catalog).contains("AdminOrderFeaturePages.pages()");
    assertThat(catalog).contains("AdminContentFeaturePages.pages()");
    assertThat(catalog).contains("AdminSettingsFeaturePages.pages()");
  }

  @Test
  void corsAllowsLocalDevelopmentPortsByPattern() throws Exception {
    String application = Files.readString(Path.of("src/main/resources/application.yml"));
    String securityConfig = Files.readString(Path.of("src/main/java/com/fxplatform/common/security/SecurityConfig.java"));

    assertThat(application)
        .contains("allowed-origin-patterns: ${CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*,http://127.0.0.1:*}");
    assertThat(securityConfig).contains("setAllowedOriginPatterns");
    assertThat(securityConfig).contains("\"PUT\"");
  }

  @Test
  void marketTestDataMigrationSeedsTwoYearsOfFrontendCandles() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V11__market_test_data.sql"));

    assertThat(migration).contains("interval '2 years'");
    assertThat(migration).contains("'5m'");
    assertThat(migration).contains("'15m'");
    assertThat(migration).contains("'1h'");
    assertThat(migration).contains("ON CONFLICT (symbol, timeframe, open_time) DO NOTHING");
  }

  @Test
  void cryptoSymbolMigrationSeedsBinanceSolAndXrpExtensionSlots() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V27__crypto_symbol_provider_seed.sql"));

    assertThat(migration).contains("'BTCUSDT'");
    assertThat(migration).contains("'ETHUSDT'");
    assertThat(migration).contains("'SOLUSDT'");
    assertThat(migration).contains("'XRPUSDT'");
    assertThat(migration).contains("'binance'");
    assertThat(migration).contains("'CRYPTO'");
  }

  @Test
  void adminApiDoesNotExposeUserEntityWithPasswordHash() throws Exception {
    String adminController = Files.readString(Path.of("src/main/java/com/fxplatform/admin/controller/AdminController.java"));

    assertThat(adminController).doesNotContain("ApiResponse<List<UserEntity>>");
    assertThat(adminController).doesNotContain("ApiResponse<UserEntity>");
  }

  @Test
  void adminApiUsesDtosAndPaginationInsteadOfEntityCollections() throws Exception {
    String adminController = Files.readString(Path.of("src/main/java/com/fxplatform/admin/controller/AdminController.java"));

    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/admin/dto/AdminPageResponse.java"))).isTrue();
    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/admin/dto/response/AdminAuditLogResponse.java"))).isTrue();
    assertThat(Files.exists(Path.of("src/main/java/com/fxplatform/admin/service/AdminAuditQueryService.java"))).isTrue();
    assertThat(adminController).doesNotContain("ApiResponse<List<AuditLogEntity>>");
    assertThat(adminController).doesNotContain("ApiResponse<?>");
    assertThat(adminController).contains("AdminAuditQueryService");
  }

  @Test
  void foundationOrderStatusesAreExplicit() {
    assertThat(Arrays.stream(OrderStatus.values()).map(Enum::name))
        .contains(
            "RECEIVED",
            "VALIDATING",
            "ACCEPTED",
            "WORKING",
            "PARTIALLY_FILLED",
            "FILLED",
            "REJECTED",
            "CANCEL_PENDING",
            "CANCELED",
            "FAILED");
  }

  @Test
  void ledgerTypesIncludeOrderAndFeeBoundaries() {
    assertThat(Arrays.stream(LedgerEntryType.values()).map(Enum::name))
        .contains(
            "ORDER_HOLD",
            "ORDER_RELEASE",
            "TRADE_FEE",
            "FORCED_CLOSE",
            "LIQUIDATION_FEE",
            "FINANCING",
            "CONVERSION_FEE");
  }

  @Test
  void fxConversionAndFinancingMigrationAddsOnlyFxTablesAndPositionAccrual() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V36__fx_conversion_and_financing.sql"));

    assertThat(migration).contains("CREATE TABLE IF NOT EXISTS trading.fx_conversion_rates");
    assertThat(migration).contains("CREATE TABLE IF NOT EXISTS trading.fx_financing_rates");
    assertThat(migration).contains("ADD COLUMN IF NOT EXISTS financing_accrued NUMERIC(24, 8) NOT NULL DEFAULT 0");
    assertThat(migration).doesNotContain("wallet_balances");
    assertThat(migration).doesNotContain("funding_rates");
  }

  @Test
  void fxFinancingSchedulerIsDisabledByDefault() throws Exception {
    String application = Files.readString(Path.of("src/main/resources/application.yml"));
    String scheduler = Files.readString(Path.of(
        "src/main/java/com/fxplatform/trading/service/ForexFinancingScheduler.java"));

    assertThat(application).contains("fx-financing:");
    assertThat(application).contains("enabled: ${TRADING_FX_FINANCING_ENABLED:false}");
    assertThat(application).contains("scan-ms: ${TRADING_FX_FINANCING_SCAN_MS:86400000}");
    assertThat(scheduler).contains("ConditionalOnProperty");
    assertThat(scheduler).contains("trading.fx-financing");
  }

  @Test
  void foundationMigrationAddsOmsFieldsAndOrderEvents() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V12__foundation_oms_fields.sql"));

    assertThat(migration).contains("client_order_id");
    assertThat(migration).contains("filled_quantity");
    assertThat(migration).contains("remaining_quantity");
    assertThat(migration).contains("avg_fill_price");
    assertThat(migration).contains("hold_amount");
    assertThat(migration).contains("reject_code");
    assertThat(migration).contains("CREATE TABLE IF NOT EXISTS trading.order_events");
    assertThat(migration).contains("ux_orders_user_account_client_order_id");
  }

  @Test
  void advancedDemoExecutionIsDisabledByDefault() throws Exception {
    String application = Files.readString(Path.of("src/main/resources/application.yml"));
    String pendingService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java"));
    String protectiveService = Files.readString(Path.of(
        "src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java"));
    String liquidationScheduler = Files.readString(Path.of(
        "src/main/java/com/fxplatform/trading/service/LiquidationScanScheduler.java"));

    assertThat(application).contains("pending-order-execution-enabled: ${TRADING_PENDING_ORDER_EXECUTION_ENABLED:false}");
    assertThat(application).contains("protective-order-execution-enabled: ${TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED:false}");
    assertThat(application).contains("enabled: ${TRADING_LIQUIDATION_ENABLED:false}");
    assertThat(application).contains("scan-interval-ms: ${TRADING_LIQUIDATION_SCAN_INTERVAL_MS:60000}");
    assertThat(pendingService).contains("ConditionalOnProperty");
    assertThat(protectiveService).contains("ConditionalOnProperty");
    assertThat(liquidationScheduler).contains("ConditionalOnProperty");
    assertThat(liquidationScheduler).contains("trading.liquidation.scan-interval-ms");
  }

  @Test
  void highRiskTradingAndFinancePathsDoNotUseTemplateBusinessComments() throws Exception {
    String combined = Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PositionService.java"))
        + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/PendingOrderExecutionService.java"))
        + Files.readString(Path.of("src/main/java/com/fxplatform/trading/service/ProtectiveOrderExecutionService.java"))
        + Files.readString(Path.of("src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java"));

    assertThat(combined).doesNotContain("执行 closePosition 业务流程");
    assertThat(combined).doesNotContain("执行 tryExecute 业务流程");
    assertThat(combined).doesNotContain("执行 shouldClose 业务流程");
    assertThat(combined).doesNotContain("执行 applyBalanceChange 业务流程");
  }

  @Test
  void adminCommandCommentsStayAttachedToTheMethodsTheyDescribe() throws Exception {
    String financeCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminFinanceCommandService.java"));
    String marketCommand = Files.readString(Path.of(
        "src/main/java/com/fxplatform/admin/service/AdminMarketCommandService.java"));

    assertThat(financeCommand).contains(
        "非空幂等键必须先抢占资金操作记录，抢占成功后才允许改账户余额和写流水。\n"
            + "   */\n"
            + "  private AdminFundOperationResponse applyBalanceChange(");
    assertThat(marketCommand).doesNotContain("*/\n  /** 创建产品分类，并写入审计日志。 */");
    assertThat(marketCommand).doesNotContain("*/\n  /** 取消尚未完成的涨跌/价格调整任务。 */");
  }

  private static String readUnchecked(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read " + path, e);
    }
  }

  private static void assertEveryAuditFieldUsesMybatisPlusFill(String source, String fieldName, String fill) {
    String declaration = "private Instant " + fieldName + ";";
    String annotatedDeclaration = "@TableField(fill = FieldFill." + fill + ")\n  " + declaration;

    assertThat(countOccurrences(source, annotatedDeclaration))
        .isEqualTo(countOccurrences(source, declaration));
  }

  private static int countOccurrences(String source, String value) {
    int count = 0;
    int index = 0;
    while ((index = source.indexOf(value, index)) >= 0) {
      count++;
      index += value.length();
    }
    return count;
  }
}
