package com.fxplatform.database;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.validation.service.JdbcValidationAccountSeedGateway;
import com.fxplatform.validation.service.ValidationAccountSeedGateway;
import com.fxplatform.validation.service.ValidationAccountSeedReceipt;
import com.fxplatform.validation.service.ValidationAccountSeedRequest;
import com.fxplatform.validation.service.ValidationAccountSeedService;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 16 proof for the validation-only account seed transaction.
 *
 * <p>The production seed components are profile-gated. This test registers those concrete
 * components explicitly under the database integration-test profile, preserving Spring's
 * transactional AOP proxy while avoiding the validation runtime hostname safety contract.</p>
 */
@SpringBootTest(properties = {
    "execution.mode=demo",
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false"
})
@ActiveProfiles("database-it")
@Import(ValidationAccountSeedPostgresIT.SeedTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ValidationAccountSeedPostgresIT {

  private static final long GENERATION = 1L;
  private static final BigDecimal INITIAL_BALANCE = money("50000");
  private static final String RECEIPT_TRIGGER = "validation_seed_receipt_rollback_probe";
  private static final String RECEIPT_FUNCTION =
      "validation_runtime.raise_validation_seed_receipt_rollback_probe";
  private static final String RECEIPT_FAILURE_MESSAGE =
      "injected validation seed receipt rollback probe";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private DemoAccountLifecycleService lifecycleService;
  @Autowired private ValidationAccountSeedService seedService;
  @Autowired private ValidationAccountSeedGateway seedGateway;

  private final List<UUID> fixtureUserIds = new ArrayList<>();

  @BeforeEach
  void prepareReadyGenerationAndVerifyTransactionalProxy() {
    assertThat(postgres.isRunning()).isTrue();
    assertThat(AopUtils.isAopProxy(seedGateway)).isTrue();
    assertThat(AopUtils.getTargetClass(seedGateway))
        .isEqualTo(JdbcValidationAccountSeedGateway.class);
    assertThat(seedGateway.supportedSpotAssets()).contains("USDT", "BTC", "ETH");

    dropReceiptFailureTrigger();
    jdbcTemplate.update("DELETE FROM validation_runtime.seed_receipts");
    assertThat(jdbcTemplate.update("""
        UPDATE validation_control.reset_state
           SET generation = ?,
               state = 'READY',
               redis_generation = ?,
               memory_generation = ?,
               error_code = NULL,
               updated_at = now()
         WHERE singleton_key = 1
        """, GENERATION, GENERATION, GENERATION)).isEqualTo(1);
  }

  @AfterEach
  void cleanTriggerAndFixtures() {
    dropReceiptFailureTrigger();
    for (UUID userId : fixtureUserIds) {
      List<UUID> accountIds = jdbcTemplate.queryForList(
          "SELECT id FROM core.trading_accounts WHERE user_id = ?",
          UUID.class,
          userId);
      for (UUID accountId : accountIds) {
        jdbcTemplate.update(
            "DELETE FROM validation_runtime.seed_receipts WHERE account_id = ?",
            accountId);
        jdbcTemplate.update(
            "DELETE FROM ledger.asset_ledger_entries WHERE account_id = ?",
            accountId);
        jdbcTemplate.update(
            "DELETE FROM ledger.ledger_entries WHERE account_id = ?",
            accountId);
        jdbcTemplate.update(
            "DELETE FROM core.wallet_balances WHERE account_id = ?",
            accountId);
        jdbcTemplate.update(
            "DELETE FROM trading.account_symbol_settings WHERE account_id = ?",
            accountId);
        jdbcTemplate.update(
            "DELETE FROM core.trading_accounts WHERE id = ?",
            accountId);
      }
      jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", userId);
    }
    fixtureUserIds.clear();
  }

  @Test
  void receiptInsertFailureRollsBackAccountWalletLedgersAndReceipt() {
    Fixture fixture = createPristineFixture("rollback");
    ValidationAccountSeedRequest request =
        request(UUID.randomUUID(), "75000", "1.25000000", "12.50000000");
    DatabaseState before = databaseState(fixture.accountId());

    installReceiptFailureTrigger();
    BusinessException failure;
    try {
      failure = businessFailure(
          () -> seedService.seed(fixture.accountId(), request),
          "VALIDATION_SEED_CONFLICT");
    } finally {
      dropReceiptFailureTrigger();
    }

    assertThat(rootCause(failure).getMessage()).contains(RECEIPT_FAILURE_MESSAGE);
    assertThat(databaseState(fixture.accountId())).isEqualTo(before);
    assertThat(count(
        "SELECT count(*) FROM validation_runtime.seed_receipts WHERE account_id = ?",
        fixture.accountId())).isZero();
  }

  @Test
  void concurrentExactRequestCommitsOnceReplaysAndRejectsRealConflict() throws Exception {
    Fixture fixture = createPristineFixture("same-request");
    UUID seedId = UUID.randomUUID();
    ValidationAccountSeedRequest request =
        request(seedId, "76000", "1.50000000", "15.00000000");

    List<SeedAttempt> attempts = runConcurrently(fixture.accountId(), request, request);

    assertThat(attempts).allMatch(SeedAttempt::succeeded);
    assertThat(attempts.get(0).receipt()).isEqualTo(attempts.get(1).receipt());
    assertCommittedSeed(fixture.accountId(), attempts.getFirst().receipt());

    DatabaseState committed = databaseState(fixture.accountId());
    ValidationAccountSeedRequest conflictingRequest =
        request(seedId, "76000", "9.00000000", "15.00000000");
    businessFailure(
        () -> seedService.seed(fixture.accountId(), conflictingRequest),
        "VALIDATION_SEED_CONFLICT");
    assertThat(databaseState(fixture.accountId())).isEqualTo(committed);
  }

  @Test
  void concurrentDistinctRequestsChooseOneWinnerWithoutMixedState() throws Exception {
    Fixture fixture = createPristineFixture("distinct-requests");
    ValidationAccountSeedRequest first =
        request(UUID.randomUUID(), "61000", "1.00000000", "2.00000000");
    ValidationAccountSeedRequest second =
        request(UUID.randomUUID(), "92000", "3.00000000", "4.00000000");

    List<SeedAttempt> attempts = runConcurrently(fixture.accountId(), first, second);
    List<SeedAttempt> successes = attempts.stream().filter(SeedAttempt::succeeded).toList();
    List<SeedAttempt> failures = attempts.stream().filter(attempt -> !attempt.succeeded()).toList();

    assertThat(successes).hasSize(1);
    assertThat(failures)
        .singleElement()
        .extracting(SeedAttempt::errorCode)
        .isEqualTo("VALIDATION_ACCOUNT_NOT_PRISTINE");

    SeedAttempt winner = successes.getFirst();
    SeedAttempt loser = failures.getFirst();
    assertCommittedSeed(fixture.accountId(), winner.receipt());
    assertThat(count(
        "SELECT count(*) FROM validation_runtime.seed_receipts WHERE seed_id = ?",
        loser.request().seedId())).isZero();
    assertThat(count("""
        SELECT count(*)
          FROM ledger.ledger_entries
         WHERE account_id = ?
           AND reference_type = 'VALIDATION_SEED'
           AND reference_id = ?
        """, fixture.accountId(), loser.request().seedId())).isZero();
    assertThat(count("""
        SELECT count(*)
          FROM ledger.asset_ledger_entries
         WHERE account_id = ?
           AND reference_type = 'VALIDATION_SEED'
           AND reference_id = ?
        """, fixture.accountId(), loser.request().seedId())).isZero();

    DatabaseState committed = databaseState(fixture.accountId());
    assertThat(seedService.seed(fixture.accountId(), winner.request()))
        .isEqualTo(winner.receipt());
    assertThat(databaseState(fixture.accountId())).isEqualTo(committed);
  }

  private Fixture createPristineFixture(String prefix) {
    UUID userId = UUID.randomUUID();
    fixtureUserIds.add(userId);
    String email = "validation-seed-postgres-" + prefix + "-" + userId + "@example.test";
    jdbcTemplate.update("""
        INSERT INTO auth.users (
          id, email, password_hash, status, role, kyc_status, risk_level
        ) VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, email);
    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    return new Fixture(userId, account.getId());
  }

  private List<SeedAttempt> runConcurrently(
      UUID accountId,
      ValidationAccountSeedRequest first,
      ValidationAccountSeedRequest second
  ) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      lockAccount(blocker, accountId);
      Future<SeedAttempt> firstFuture =
          executor.submit(() -> gatedAttempt(accountId, first, ready, start));
      Future<SeedAttempt> secondFuture =
          executor.submit(() -> gatedAttempt(accountId, second, ready, start));
      assertThat(ready.await(10, SECONDS))
          .as("both seed attempts reached the concurrency barrier")
          .isTrue();
      start.countDown();
      awaitSeedAccountLockWaiters(2);
      blocker.commit();
      return List.of(
          firstFuture.get(30, SECONDS),
          secondFuture.get(30, SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static void lockAccount(Connection connection, UUID accountId) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT id
          FROM core.trading_accounts
         WHERE id = ?
         FOR UPDATE
        """)) {
      statement.setObject(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
      }
    }
  }

  private void awaitSeedAccountLockWaiters(long expectedWaiters) throws InterruptedException {
    long deadline = System.nanoTime() + SECONDS.toNanos(10);
    long observed;
    do {
      observed = count("""
          SELECT count(*)
            FROM pg_stat_activity
           WHERE datname = current_database()
             AND pid <> pg_backend_pid()
             AND wait_event_type = 'Lock'
             AND lower(query) LIKE '%core.trading_accounts%'
             AND lower(query) LIKE '%for update%'
          """);
      if (observed >= expectedWaiters) {
        return;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    assertThat(observed)
        .as("seed transactions waiting on the externally held account row lock")
        .isGreaterThanOrEqualTo(expectedWaiters);
  }

  private SeedAttempt gatedAttempt(
      UUID accountId,
      ValidationAccountSeedRequest request,
      CountDownLatch ready,
      CountDownLatch start
  ) throws InterruptedException {
    ready.countDown();
    if (!start.await(10, SECONDS)) {
      throw new IllegalStateException("Seed concurrency start gate timed out");
    }
    try {
      return new SeedAttempt(request, seedService.seed(accountId, request), null);
    } catch (BusinessException exception) {
      return new SeedAttempt(request, null, exception.getCode());
    }
  }

  private void assertCommittedSeed(UUID accountId, ValidationAccountSeedReceipt receipt) {
    Map<String, BigDecimal> expectedBalances = receipt.spotBalances();
    BigDecimal expectedUsdt = expectedBalances.get("USDT");
    AccountAmounts account = jdbcTemplate.queryForObject("""
        SELECT balance, equity, used_margin, free_margin, margin_level, demo_generation
          FROM core.trading_accounts
         WHERE id = ?
        """, (resultSet, rowNumber) -> new AccountAmounts(
        resultSet.getBigDecimal("balance"),
        resultSet.getBigDecimal("equity"),
        resultSet.getBigDecimal("used_margin"),
        resultSet.getBigDecimal("free_margin"),
        resultSet.getBigDecimal("margin_level"),
        resultSet.getLong("demo_generation")), accountId);
    assertThat(account).isNotNull();
    assertMoney(account.balance(), expectedUsdt);
    assertMoney(account.equity(), expectedUsdt);
    assertMoney(account.usedMargin(), BigDecimal.ZERO);
    assertMoney(account.freeMargin(), expectedUsdt);
    assertThat(account.marginLevel()).isNull();
    assertThat(account.demoGeneration()).isEqualTo(1L);

    List<WalletAmounts> wallets = jdbcTemplate.query("""
        SELECT wallet_type, asset, total, available, locked
          FROM core.wallet_balances
         WHERE account_id = ?
         ORDER BY asset
        """, (resultSet, rowNumber) -> new WalletAmounts(
        resultSet.getString("wallet_type"),
        resultSet.getString("asset"),
        resultSet.getBigDecimal("total"),
        resultSet.getBigDecimal("available"),
        resultSet.getBigDecimal("locked")), accountId);
    assertThat(wallets).hasSize(expectedBalances.size());
    for (Map.Entry<String, BigDecimal> expected : expectedBalances.entrySet()) {
      WalletAmounts wallet = wallets.stream()
          .filter(candidate -> expected.getKey().equals(candidate.asset()))
          .findFirst()
          .orElseThrow();
      assertThat(wallet.walletType()).isEqualTo("SPOT");
      assertMoney(wallet.total(), expected.getValue());
      assertMoney(wallet.available(), expected.getValue());
      assertMoney(wallet.locked(), BigDecimal.ZERO);
    }

    List<CashMutation> cashMutations = jdbcTemplate.query("""
        SELECT entry_type, operation_type, amount, balance_after, currency,
               reference_type, reference_id
          FROM ledger.ledger_entries
         WHERE account_id = ?
           AND entry_type = 'VALIDATION_SEED'
         ORDER BY id
        """, (resultSet, rowNumber) -> new CashMutation(
        resultSet.getString("entry_type"),
        resultSet.getString("operation_type"),
        resultSet.getBigDecimal("amount"),
        resultSet.getBigDecimal("balance_after"),
        resultSet.getString("currency"),
        resultSet.getString("reference_type"),
        resultSet.getObject("reference_id", UUID.class)), accountId);
    assertThat(cashMutations).hasSize(1);
    CashMutation cash = cashMutations.getFirst();
    assertThat(cash.entryType()).isEqualTo("VALIDATION_SEED");
    assertThat(cash.operationType()).isEqualTo("VALIDATION_SEED");
    assertMoney(cash.amount(), expectedUsdt.subtract(INITIAL_BALANCE));
    assertMoney(cash.balanceAfter(), expectedUsdt);
    assertThat(cash.currency()).isEqualTo("USDT");
    assertThat(cash.referenceType()).isEqualTo("VALIDATION_SEED");
    assertThat(cash.referenceId()).isEqualTo(receipt.seedId());
    assertThat(count(
        "SELECT count(*) FROM ledger.ledger_entries WHERE account_id = ?",
        accountId)).isEqualTo(2L);

    List<AssetMutation> assetMutations = jdbcTemplate.query("""
        SELECT wallet_type, asset, entry_type, operation_type, amount, balance_after,
               reference_type, reference_id
          FROM ledger.asset_ledger_entries
         WHERE account_id = ?
           AND entry_type = 'VALIDATION_SEED'
         ORDER BY asset
        """, (resultSet, rowNumber) -> new AssetMutation(
        resultSet.getString("wallet_type"),
        resultSet.getString("asset"),
        resultSet.getString("entry_type"),
        resultSet.getString("operation_type"),
        resultSet.getBigDecimal("amount"),
        resultSet.getBigDecimal("balance_after"),
        resultSet.getString("reference_type"),
        resultSet.getObject("reference_id", UUID.class)), accountId);
    assertThat(assetMutations).hasSize(expectedBalances.size());
    for (AssetMutation mutation : assetMutations) {
      BigDecimal expectedTarget = expectedBalances.get(mutation.asset());
      BigDecimal openingBalance =
          "USDT".equals(mutation.asset()) ? INITIAL_BALANCE : BigDecimal.ZERO;
      assertThat(expectedTarget).isNotNull();
      assertThat(mutation.walletType()).isEqualTo("SPOT");
      assertThat(mutation.entryType()).isEqualTo("VALIDATION_SEED");
      assertThat(mutation.operationType()).isEqualTo("VALIDATION_SEED");
      assertMoney(mutation.amount(), expectedTarget.subtract(openingBalance));
      assertMoney(mutation.balanceAfter(), expectedTarget);
      assertThat(mutation.referenceType()).isEqualTo("VALIDATION_SEED");
      assertThat(mutation.referenceId()).isEqualTo(receipt.seedId());
    }
    assertThat(count(
        "SELECT count(*) FROM ledger.asset_ledger_entries WHERE account_id = ?",
        accountId)).isEqualTo(expectedBalances.size() + 1L);

    List<ReceiptEvidence> receipts = jdbcTemplate.query("""
        SELECT seed_id, generation, account_id, perpetual_usdt_balance
          FROM validation_runtime.seed_receipts
         WHERE account_id = ?
        """, (resultSet, rowNumber) -> new ReceiptEvidence(
        resultSet.getObject("seed_id", UUID.class),
        resultSet.getLong("generation"),
        resultSet.getObject("account_id", UUID.class),
        resultSet.getBigDecimal("perpetual_usdt_balance")), accountId);
    assertThat(receipts).hasSize(1);
    ReceiptEvidence evidence = receipts.getFirst();
    assertThat(evidence.seedId()).isEqualTo(receipt.seedId());
    assertThat(evidence.generation()).isEqualTo(GENERATION);
    assertThat(evidence.accountId()).isEqualTo(accountId);
    assertMoney(evidence.perpetualUsdtBalance(), receipt.perpetualUsdtBalance());
  }

  private DatabaseState databaseState(UUID accountId) {
    String account = jdbcTemplate.queryForObject("""
        SELECT to_jsonb(account_row)::text
          FROM core.trading_accounts AS account_row
         WHERE id = ?
        """, String.class, accountId);
    return new DatabaseState(
        account,
        jsonRows("""
            SELECT to_jsonb(wallet_row)::text
              FROM core.wallet_balances AS wallet_row
             WHERE account_id = ?
             ORDER BY id
            """, accountId),
        jsonRows("""
            SELECT to_jsonb(cash_row)::text
              FROM ledger.ledger_entries AS cash_row
             WHERE account_id = ?
             ORDER BY id
            """, accountId),
        jsonRows("""
            SELECT to_jsonb(asset_row)::text
              FROM ledger.asset_ledger_entries AS asset_row
             WHERE account_id = ?
             ORDER BY id
            """, accountId),
        jsonRows("""
            SELECT to_jsonb(receipt_row)::text
              FROM validation_runtime.seed_receipts AS receipt_row
             WHERE account_id = ?
             ORDER BY seed_id
            """, accountId));
  }

  private List<String> jsonRows(String sql, UUID accountId) {
    return jdbcTemplate.query(
        sql,
        (resultSet, rowNumber) -> resultSet.getString(1),
        accountId);
  }

  private void installReceiptFailureTrigger() {
    jdbcTemplate.execute("""
        CREATE OR REPLACE FUNCTION validation_runtime.raise_validation_seed_receipt_rollback_probe()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'injected validation seed receipt rollback probe',
            CONSTRAINT = 'validation_seed_receipt_rollback_probe';
        END;
        $$
        """);
    jdbcTemplate.execute("""
        CREATE TRIGGER validation_seed_receipt_rollback_probe
        BEFORE INSERT ON validation_runtime.seed_receipts
        FOR EACH ROW
        EXECUTE FUNCTION validation_runtime.raise_validation_seed_receipt_rollback_probe()
        """);
  }

  private void dropReceiptFailureTrigger() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS " + RECEIPT_TRIGGER
            + " ON validation_runtime.seed_receipts");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + RECEIPT_FUNCTION + "()");
  }

  private BusinessException businessFailure(Runnable operation, String expectedCode) {
    Throwable thrown = catchThrowable(operation::run);
    assertThat(thrown).isInstanceOf(BusinessException.class);
    BusinessException failure = (BusinessException) thrown;
    assertThat(failure.getCode()).isEqualTo(expectedCode);
    return failure;
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private long count(String sql, Object... arguments) {
    Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
    assertThat(result).isNotNull();
    return result;
  }

  private static void assertMoney(BigDecimal actual, BigDecimal expected) {
    assertThat(actual).isEqualByComparingTo(expected);
  }

  private static ValidationAccountSeedRequest request(
      UUID seedId,
      String usdt,
      String btc,
      String eth
  ) {
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    balances.put("USDT", money(usdt));
    balances.put("BTC", money(btc));
    balances.put("ETH", money(eth));
    return new ValidationAccountSeedRequest(seedId, balances);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value).setScale(8);
  }

  private record Fixture(UUID userId, UUID accountId) {
  }

  private record SeedAttempt(
      ValidationAccountSeedRequest request,
      ValidationAccountSeedReceipt receipt,
      String errorCode
  ) {

    boolean succeeded() {
      return receipt != null && errorCode == null;
    }
  }

  private record DatabaseState(
      String account,
      List<String> wallets,
      List<String> cashLedger,
      List<String> assetLedger,
      List<String> receipts
  ) {
  }

  private record AccountAmounts(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal marginLevel,
      long demoGeneration
  ) {
  }

  private record WalletAmounts(
      String walletType,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked
  ) {
  }

  private record CashMutation(
      String entryType,
      String operationType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String currency,
      String referenceType,
      UUID referenceId
  ) {
  }

  private record AssetMutation(
      String walletType,
      String asset,
      String entryType,
      String operationType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId
  ) {
  }

  private record ReceiptEvidence(
      UUID seedId,
      long generation,
      UUID accountId,
      BigDecimal perpetualUsdtBalance
  ) {
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SeedTestConfiguration {

    @Bean
    JdbcValidationAccountSeedGateway validationAccountSeedGateway(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        TradingAccountRepository accountRepository,
        WalletService walletService,
        LedgerService ledgerService,
        LedgerEntryRepository ledgerEntryRepository,
        AssetLedgerEntryRepository assetLedgerEntryRepository,
        OrderRepository orderRepository,
        TradeRepository tradeRepository,
        PositionRepository positionRepository,
        SpotPositionRepository spotPositionRepository,
        SymbolRepository symbolRepository,
        DemoExecutionGuard demoExecutionGuard
    ) {
      return new JdbcValidationAccountSeedGateway(
          jdbcTemplate,
          objectMapper,
          accountRepository,
          walletService,
          ledgerService,
          ledgerEntryRepository,
          assetLedgerEntryRepository,
          orderRepository,
          tradeRepository,
          positionRepository,
          spotPositionRepository,
          symbolRepository,
          demoExecutionGuard);
    }

    @Bean
    ValidationAccountSeedService validationAccountSeedService(
        ValidationAccountSeedGateway gateway
    ) {
      return new ValidationAccountSeedService(gateway);
    }
  }
}
