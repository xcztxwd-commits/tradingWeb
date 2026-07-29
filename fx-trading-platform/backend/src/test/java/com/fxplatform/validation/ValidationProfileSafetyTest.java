package com.fxplatform.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fxplatform.admin.service.ProviderInstrumentSyncScheduler;
import com.fxplatform.engagement.application.outbox.EngagementOutboxDispatcher;
import com.fxplatform.engagement.scheduling.EngagementScheduledDispatcher;
import com.fxplatform.engagement.scheduling.PopupDeliveryRetentionScheduler;
import com.fxplatform.home.service.HomeCountersGrowthScheduler;
import com.fxplatform.home.service.HomeCountersService;
import com.fxplatform.market.realtime.BinanceRealtimeClient;
import com.fxplatform.market.realtime.BinanceRealtimeRotationScheduler;
import com.fxplatform.market.realtime.BinanceSubscriptionMaintenanceScheduler;
import com.fxplatform.market.realtime.BinanceSubscriptionManager;
import com.fxplatform.market.service.MarketTestDataScheduler;
import com.fxplatform.market.service.MarketTestDataService;
import com.fxplatform.market.service.QuoteBroadcastScheduler;
import com.fxplatform.market.service.QuoteBroadcastService;
import com.fxplatform.trading.service.ForexFinancingScheduler;
import com.fxplatform.trading.service.FundingSettlementScheduler;
import com.fxplatform.trading.service.LiquidationScanScheduler;
import com.fxplatform.trading.service.PendingOrderExecutionScheduler;
import com.fxplatform.trading.service.ProtectiveOrderExecutionScheduler;
import com.fxplatform.tradinglab.queue.TradingLabQueueWorker;
import com.fxplatform.tradinglab.report.TradingLabReportCleanupScheduler;
import com.fxplatform.validation.security.ValidationProfileSafetyValidator;
import com.fxplatform.wallet.service.WalletDailySnapshotJob;
import com.fxplatform.wallet.service.WalletReconciliationJob;
import com.fxplatform.wallet.service.WalletReconciliationService;
import com.fxplatform.wallet.service.WalletSnapshotService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

class ValidationProfileSafetyTest {

  private static final Path PROFILE_FILE = Path.of("src/main/resources/application-validation.yml");
  private static final String FAIL_SINK = "http://127.0.0.1:9";

  private static final List<String> FALSE_PROPERTIES = List.of(
      "spring.task.scheduling.enabled",
      "admin.bootstrap.enabled",
      "app.engagement.scheduler.enabled",
      "app.engagement.retention.enabled",
      "app.engagement.outbox.enabled",
      "massive.write-quotes-to-db",
      "market.demo-quotes.enabled",
      "market.realtime.enabled",
      "market.realtime.backfill-enabled",
      "market.realtime.dynamic-symbols-enabled",
      "market.provider-instrument-sync.enabled",
      "market.quote-broadcast-enabled",
      "market.test-data.enabled",
      "market.test-control.enabled",
      "trading.pending-order-execution-enabled",
      "trading.protective-order-execution-enabled",
      "trading.funding.enabled",
      "trading.fx-financing.enabled",
      "trading.liquidation.enabled",
      "trading-lab.queue.enabled",
      "trading-lab.report.cleanup.enabled");

  private static final List<String> FAIL_SINK_PROPERTIES = List.of(
      "massive.rest-base-url",
      "massive.websocket-forex-url",
      "massive.flat-files.endpoint",
      "binance.rest-base-url",
      "binance.web-base-url",
      "binance.futures-base-url",
      "binance.websocket-base-url",
      "okx.rest-base-url",
      "market.fear-greed-url",
      "market.realtime.websocket-base-url",
      "execution.broker.endpoint",
      "execution.fix.endpoint",
      "execution.lp.endpoint");

  private static final Map<Class<?>, String> CONDITIONAL_SCHEDULERS = Map.ofEntries(
      Map.entry(ProviderInstrumentSyncScheduler.class, "market.provider-instrument-sync.enabled"),
      Map.entry(EngagementScheduledDispatcher.class, "app.engagement.scheduler.enabled"),
      Map.entry(PopupDeliveryRetentionScheduler.class, "app.engagement.retention.enabled"),
      Map.entry(EngagementOutboxDispatcher.class, "app.engagement.outbox.enabled"),
      Map.entry(TradingLabQueueWorker.class, "trading-lab.queue.enabled"),
      Map.entry(TradingLabReportCleanupScheduler.class, "trading-lab.report.cleanup.enabled"),
      Map.entry(PendingOrderExecutionScheduler.class, "trading.pending-order-execution-enabled"),
      Map.entry(ProtectiveOrderExecutionScheduler.class, "trading.protective-order-execution-enabled"),
      Map.entry(FundingSettlementScheduler.class, "trading.funding.enabled"),
      Map.entry(ForexFinancingScheduler.class, "trading.fx-financing.enabled"),
      Map.entry(LiquidationScanScheduler.class, "trading.liquidation.enabled"));

  @Test
  void profilePinsIsolatedStoresDemoModeAndEveryExternalActivitySwitch() throws Exception {
    PropertySource<?> profile = profileProperties();

    assertThat(profile.getProperty("server.port")).isEqualTo(8080);
    assertThat(profile.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://validation-postgres:5432/fx_validation_lab");
    assertThat(profile.getProperty("spring.datasource.username")).isEqualTo("fx_validation_app");
    assertThat(profile.getProperty("spring.datasource.driver-class-name"))
        .isEqualTo("org.postgresql.Driver");
    assertThat(profile.getProperty("spring.data.redis.host")).isEqualTo("validation-redis");
    assertThat(profile.getProperty("spring.data.redis.database")).isEqualTo(0);
    assertThat(profile.getProperty("execution.mode")).isEqualTo("demo");
    assertThat(profile.getProperty("validation.safety.enabled")).isEqualTo(true);
    assertThat(profile.getProperty("validation.internal.secret"))
        .isEqualTo("${VALIDATION_INTERNAL_SECRET}");

    FALSE_PROPERTIES.forEach(key -> assertThat(profile.getProperty(key)).as(key).isEqualTo(false));
    FAIL_SINK_PROPERTIES.forEach(key -> assertThat(profile.getProperty(key)).as(key).isEqualTo(FAIL_SINK));
  }

  @Test
  void validatorIsRegisteredImmediatelyAfterConfigDataLoading() throws Exception {
    String factories = Files.readString(Path.of("src/main/resources/META-INF/spring.factories"));

    assertThat(factories)
        .contains("org.springframework.boot.env.EnvironmentPostProcessor")
        .contains(ValidationProfileSafetyValidator.class.getName());
    assertThat(new ValidationProfileSafetyValidator().getOrder())
        .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
  }

  @Test
  void validatorAcceptsOnlyTheCompleteSafeValidationEnvironment() {
    assertThatCode(() -> validate(safeEnvironment())).doesNotThrowAnyException();
  }

  @Test
  void validatorIsANoopOutsideValidationProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertThatCode(() -> validate(environment)).doesNotThrowAnyException();
  }

  @Test
  void validatorRejectsMixedProfilesAndUnsafeCoreBindingsWithoutEchoingSecrets() {
    MockEnvironment mixed = safeEnvironment();
    mixed.setActiveProfiles("validation", "dev");
    assertRejected(mixed, "spring.profiles.active");

    assertRejected(overridden("execution.mode", "live"), "execution.mode");
    assertRejected(overridden("spring.datasource.url", "jdbc:postgresql://main-db:5432/fx_platform"),
        "spring.datasource.url");
    assertRejected(overridden("spring.datasource.url", "jdbc:postgresql://validation-postgres:5432/fx_platform"),
        "spring.datasource.url");
    assertRejected(overridden("spring.datasource.username", "postgres"), "spring.datasource.username");
    assertRejected(overridden("spring.data.redis.host", "localhost"), "spring.data.redis.host");

    String secret = "do-not-echo-this-validation-secret";
    MockEnvironment missingSecret = overridden("validation.internal.secret", " ");
    missingSecret.setProperty("spring.datasource.password", secret);
    assertThatThrownBy(() -> validate(missingSecret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("validation.internal.secret")
        .hasMessageNotContaining(secret);
  }

  @Test
  void validatorRejectsAlternateLoopbackPortsAndEveryServletContextPathOverride() {
    assertRejected(overridden("server.port", "18087"), "server.port");
    assertRejected(overridden("server.port", "0"), "server.port");
    assertRejected(
        overridden("server.servlet.context-path", "/validation"),
        "server.servlet.context-path");
    assertRejected(
        overridden("server.servlet.context-path", ""),
        "server.servlet.context-path");
  }

  @Test
  void validatorRejectsRedisUrlAndAlternateTopologiesWithoutEchoingSecrets() {
    String secret = "do-not-echo-this-redis-secret";
    MockEnvironment redisUrl = overridden(
        "spring.data.redis.url",
        "redis://default:" + secret + "@main-redis:6379/0");
    assertThatThrownBy(() -> validate(redisUrl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.data.redis.url")
        .hasMessageNotContaining(secret);

    assertRejected(overridden("spring.data.redis.sentinel.master", "main"),
        "spring.data.redis.sentinel");
    assertRejected(overridden("spring.data.redis.sentinel.nodes", "main-redis:26379"),
        "spring.data.redis.sentinel");
    assertRejected(overridden("spring.data.redis.cluster.nodes", "main-redis:6379"),
        "spring.data.redis.cluster");
    assertRejected(overridden("spring.data.redis.username", "main-user"),
        "spring.data.redis.username");
    assertRejected(overridden("spring.data.redis.database", "1"),
        "spring.data.redis.database");
  }

  @Test
  void validatorRejectsDatasourceSecondaryBindingsWithoutEchoingSecrets() {
    Map<String, String> alternateBindings = Map.ofEntries(
        Map.entry("spring.datasource.jndi-name", "spring.datasource.jndi-name"),
        Map.entry("spring.datasource.type", "spring.datasource.type"),
        Map.entry("spring.datasource.driver-class-name", "spring.datasource.driver-class-name"),
        Map.entry("spring.datasource.hikari.jdbc-url", "spring.datasource.hikari"),
        Map.entry("spring.datasource.hikari.username", "spring.datasource.hikari"),
        Map.entry("spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari"),
        Map.entry("spring.datasource.hikari.data-source-jndi", "spring.datasource.hikari"),
        Map.entry("spring.datasource.hikari.data-source-properties.serverName",
            "spring.datasource.hikari"),
        Map.entry("spring.datasource.xa.data-source-class-name", "spring.datasource.xa"),
        Map.entry("spring.datasource.xa.properties.serverName", "spring.datasource.xa"));

    alternateBindings.forEach((key, reportedKey) ->
        assertRejected(overridden(key, "unsafe-override"), reportedKey));

    String secret = "do-not-echo-this-hikari-secret";
    MockEnvironment hikariPassword = overridden("spring.datasource.hikari.password", secret);
    assertThatThrownBy(() -> validate(hikariPassword))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.hikari")
        .hasMessageNotContaining(secret);
  }

  @Test
  void validatorRejectsIndependentFlywayRoutingOverridesWithoutEchoingSecrets() {
    List<String> routingKeys = List.of(
        "spring.flyway.url",
        "spring.flyway.user",
        "spring.flyway.password",
        "spring.flyway.driver-class-name",
        "spring.flyway.schemas",
        "spring.flyway.default-schema",
        "spring.flyway.table");

    routingKeys.forEach(key -> assertRejected(overridden(key, "unsafe-override"), key));

    String secret = "do-not-echo-this-flyway-secret";
    MockEnvironment password = overridden("spring.flyway.password", secret);
    assertThatThrownBy(() -> validate(password))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.flyway.password")
        .hasMessageNotContaining(secret);
  }

  @Test
  void validatorRejectsJdbcConnectionOptionsAndFragments() {
    assertRejected(overridden(
        "spring.datasource.url",
        "jdbc:postgresql://validation-postgres:5432/fx_validation_lab?socketFactory=unsafe"),
        "spring.datasource.url");
    assertRejected(overridden(
        "spring.datasource.url",
        "jdbc:postgresql://validation-postgres:5432/fx_validation_lab#unsafe"),
        "spring.datasource.url");
  }

  @Test
  void validatorRejectsEveryEnabledBackgroundSwitch() {
    FALSE_PROPERTIES.forEach(key -> assertRejected(overridden(key, "true"), key));
  }

  @Test
  void validatorRejectsEveryExternalEndpointThatIsNotTheFailSink() {
    FAIL_SINK_PROPERTIES.forEach(key -> assertRejected(overridden(key, "https://example.invalid"), key));
  }

  @Test
  void validationProfileRemovesAllScheduledOwnerBeansButKeepsBusinessBeans() {
    schedulerRunner("validation").run(context -> {
      assertThat(context).hasSingleBean(HomeCountersService.class);
      assertThat(context).hasSingleBean(QuoteBroadcastService.class);
      assertThat(context).hasSingleBean(MarketTestDataService.class);
      assertThat(context).hasSingleBean(BinanceRealtimeClient.class);
      assertThat(context).hasSingleBean(BinanceSubscriptionManager.class);
      assertThat(context).doesNotHaveBean(HomeCountersGrowthScheduler.class);
      assertThat(context).doesNotHaveBean(QuoteBroadcastScheduler.class);
      assertThat(context).doesNotHaveBean(MarketTestDataScheduler.class);
      assertThat(context).doesNotHaveBean(BinanceRealtimeRotationScheduler.class);
      assertThat(context).doesNotHaveBean(BinanceSubscriptionMaintenanceScheduler.class);
      assertThat(context).doesNotHaveBean(WalletDailySnapshotJob.class);
      assertThat(context).doesNotHaveBean(WalletReconciliationJob.class);
    });

    CONDITIONAL_SCHEDULERS.forEach((owner, property) -> new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=validation", property + "=false")
        .withUserConfiguration(owner)
        .run(context -> assertThat(context).doesNotHaveBean(owner)));
  }

  @Test
  void nonValidationProfileRetainsAllSevenScheduledAdapters() {
    schedulerRunner("dev").run(context -> {
      assertThat(context).hasSingleBean(HomeCountersGrowthScheduler.class);
      assertThat(context).hasSingleBean(QuoteBroadcastScheduler.class);
      assertThat(context).hasSingleBean(MarketTestDataScheduler.class);
      assertThat(context).hasSingleBean(BinanceRealtimeRotationScheduler.class);
      assertThat(context).hasSingleBean(BinanceSubscriptionMaintenanceScheduler.class);
      assertThat(context).hasSingleBean(WalletDailySnapshotJob.class);
      assertThat(context).hasSingleBean(WalletReconciliationJob.class);
    });
  }

  private static ApplicationContextRunner schedulerRunner(String profile) {
    return new ApplicationContextRunner()
        .withPropertyValues(
            "spring.profiles.active=" + profile,
            "home.counters.growth-enabled=true",
            "market.quote-broadcast-enabled=true",
            "market.test-data.enabled=true",
            "market.realtime.enabled=true",
            "wallet.snapshot.enabled=true",
            "wallet.reconciliation.enabled=true")
        .withBean(HomeCountersService.class, () -> mock(HomeCountersService.class))
        .withBean(QuoteBroadcastService.class, () -> mock(QuoteBroadcastService.class))
        .withBean(MarketTestDataService.class, () -> mock(MarketTestDataService.class))
        .withBean(BinanceRealtimeClient.class, () -> mock(BinanceRealtimeClient.class))
        .withBean(BinanceSubscriptionManager.class, () -> mock(BinanceSubscriptionManager.class))
        .withBean(WalletSnapshotService.class, () -> mock(WalletSnapshotService.class))
        .withBean(WalletReconciliationService.class, () -> mock(WalletReconciliationService.class))
        .withUserConfiguration(
            HomeCountersGrowthScheduler.class,
            QuoteBroadcastScheduler.class,
            MarketTestDataScheduler.class,
            BinanceRealtimeRotationScheduler.class,
            BinanceSubscriptionMaintenanceScheduler.class,
            WalletDailySnapshotJob.class,
            WalletReconciliationJob.class);
  }

  private static MockEnvironment safeEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("validation");
    environment.setProperty("server.port", "8080");
    environment.setProperty("validation.safety.enabled", "true");
    environment.setProperty("validation.internal.secret", "validation-internal-secret-at-least-32-chars");
    environment.setProperty("execution.mode", "demo");
    environment.setProperty("spring.datasource.url",
        "jdbc:postgresql://validation-postgres:5432/fx_validation_lab");
    environment.setProperty("spring.datasource.username", "fx_validation_app");
    environment.setProperty("spring.datasource.password", "validation-database-password");
    environment.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
    environment.setProperty("spring.data.redis.host", "validation-redis");
    environment.setProperty("spring.data.redis.port", "6379");
    environment.setProperty("spring.data.redis.database", "0");
    environment.setProperty("spring.data.redis.password", "validation-redis-password");
    environment.setProperty("security.jwt.secret", "validation-jwt-secret-at-least-32-characters");
    environment.setProperty("security.config.encryption-key",
        "validation-encryption-key-at-least-32-characters");
    FALSE_PROPERTIES.forEach(key -> environment.setProperty(key, "false"));
    FAIL_SINK_PROPERTIES.forEach(key -> environment.setProperty(key, FAIL_SINK));
    return environment;
  }

  private static MockEnvironment overridden(String key, String value) {
    MockEnvironment environment = safeEnvironment();
    environment.setProperty(key, value);
    return environment;
  }

  private static void assertRejected(MockEnvironment environment, String key) {
    assertThatThrownBy(() -> validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(key);
  }

  private static void validate(MockEnvironment environment) {
    new ValidationProfileSafetyValidator()
        .postProcessEnvironment(environment, new SpringApplication(Object.class));
  }

  private static PropertySource<?> profileProperties() throws Exception {
    assertThat(Files.isRegularFile(PROFILE_FILE)).as("validation profile").isTrue();
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load("validation", new FileSystemResource(PROFILE_FILE));
    assertThat(sources).hasSize(1);
    return sources.getFirst();
  }
}
