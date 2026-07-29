package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.AccountView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.AssetLedgerView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.CashLedgerView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.LockedAccount;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.LockedAccountCallback;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.PristineSnapshot;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.WalletView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * RED contract for validation-only account seeding.
 *
 * <p>The production service and gateway do not exist yet. The gateway is deliberately one deep
 * port: {@code withLockedAccount} owns the transaction and the fixed account-then-wallet lock
 * order, while this service remains responsible for normalization, fingerprinting, pristine-state
 * validation, delta calculation, replay, and conflict semantics. The gateway's top-level
 * {@code supportedSpotAssets()} is a preflight view; the locked callback exposes the authoritative
 * set again so a concurrent capability change fails closed.</p>
 */
class ValidationAccountSeedServiceIT {

  private static final UUID ACCOUNT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SEED_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final BigDecimal INITIAL = money("50000");
  private static final Set<String> SUPPORTED_SPOT_ASSETS =
      Set.of("USDT", "BTC", "ETH", "BNB", "SOL", "XRP");

  @Test
  void seeds_pristine_registration_by_target_delta_and_maps_usdt_to_cash_and_spot() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);

    ValidationAccountSeedReceipt receipt = service.seed(
        ACCOUNT_ID,
        new ValidationAccountSeedRequest(SEED_ID, balances(
            "ETH", "2",
            "usdt", "0",
            " btc ", "1.25")));

    assertThat(receipt.seedId()).isEqualTo(SEED_ID);
    assertThat(receipt.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(receipt.requestFingerprint()).matches("[0-9a-f]{64}");
    assertThat(receipt.perpetualUsdtBalance()).isEqualByComparingTo("0");
    assertThat(new ArrayList<>(receipt.spotBalances().keySet()))
        .containsExactly("BTC", "ETH", "USDT");
    assertThat(receipt.spotBalances())
        .containsEntry("BTC", money("1.25"))
        .containsEntry("ETH", money("2"))
        .containsEntry("USDT", money("0"));

    assertThat(gateway.committedWrites()).containsExactly(
        TargetWrite.perpetual(SEED_ID, money("0"), money("-50000")),
        TargetWrite.spot(SEED_ID, "BTC", money("1.25"), money("1.25")),
        TargetWrite.spot(SEED_ID, "ETH", money("2"), money("2")),
        TargetWrite.spot(SEED_ID, "USDT", money("0"), money("-50000")));
    assertThat(gateway.cashBalance()).isEqualByComparingTo("0");
    assertThat(gateway.spotBalance("USDT")).isEqualByComparingTo("0");
    assertThat(gateway.spotBalance("BTC")).isEqualByComparingTo("1.25");
    assertThat(gateway.spotBalance("ETH")).isEqualByComparingTo("2");
    assertThat(gateway.receiptCount()).isEqualTo(1);
    assertThat(gateway.lockEvents()).containsExactly("ACCOUNT", "WALLETS");

    PristineSnapshot resulting = gateway.currentSnapshot();
    assertThat(resulting.orderHistoryCount()).isZero();
    assertThat(resulting.tradeHistoryCount()).isZero();
    assertThat(resulting.perpetualPositionHistoryCount()).isZero();
    assertThat(resulting.spotPositionHistoryCount()).isZero();
    assertThat(resulting.cashLedger()).hasSize(2);
    assertThat(resulting.assetLedger()).hasSize(4);
  }

  @Test
  void canonicalizes_and_sorts_assets_then_replays_the_same_stable_receipt() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    ValidationAccountSeedRequest first = new ValidationAccountSeedRequest(SEED_ID, balances(
        " eth ", "2.00000000",
        "USDT", "50000.0",
        "btc", "1.25000000"));

    ValidationAccountSeedReceipt committed = service.seed(ACCOUNT_ID, first);
    int writesAfterCommit = gateway.committedWrites().size();
    int pristineReadsAfterCommit = gateway.pristineReadCount();

    ValidationAccountSeedReceipt replayed = service.seed(
        ACCOUNT_ID,
        new ValidationAccountSeedRequest(SEED_ID, balances(
            "BTC", "1.25",
            "ETH", "2",
            "usdt", "50000.00000000")));

    assertThat(replayed).isEqualTo(committed);
    assertThat(replayed.requestFingerprint()).isEqualTo(committed.requestFingerprint());
    assertThat(new ArrayList<>(committed.spotBalances().keySet()))
        .containsExactly("BTC", "ETH", "USDT");
    assertThat(gateway.committedWrites()).hasSize(writesAfterCommit);
    assertThat(gateway.pristineReadCount()).isEqualTo(pristineReadsAfterCommit);
    assertThat(gateway.committedWrites()).contains(
        TargetWrite.perpetual(SEED_ID, INITIAL, money("0")),
        TargetWrite.spot(SEED_ID, "USDT", INITIAL, money("0")));
  }

  @Test
  void same_seed_id_with_a_different_fingerprint_is_a_conflict_before_pristine_check() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    service.seed(ACCOUNT_ID, request(SEED_ID, "50000"));
    int pristineReadsAfterCommit = gateway.pristineReadCount();

    assertBusinessCode(
        () -> service.seed(ACCOUNT_ID, request(SEED_ID, "49999")),
        "VALIDATION_SEED_CONFLICT");

    assertThat(gateway.pristineReadCount()).isEqualTo(pristineReadsAfterCommit);
    assertThat(gateway.receiptCount()).isEqualTo(1);
    assertThat(gateway.committedWrites()).hasSize(2);
  }

  @Test
  void a_new_seed_id_after_success_is_rejected_as_non_pristine_even_when_first_delta_was_zero() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    service.seed(ACCOUNT_ID, request(SEED_ID, "50000"));

    assertBusinessCode(
        () -> service.seed(
            ACCOUNT_ID,
            request(UUID.fromString("20000000-0000-0000-0000-000000000002"), "50000")),
        "VALIDATION_ACCOUNT_NOT_PRISTINE");

    assertThat(gateway.receiptCount()).isEqualTo(1);
    assertThat(gateway.committedWrites()).containsExactly(
        TargetWrite.perpetual(SEED_ID, INITIAL, money("0")),
        TargetWrite.spot(SEED_ID, "USDT", INITIAL, money("0")));
  }

  @TestFactory
  Stream<org.junit.jupiter.api.DynamicTest> rejects_every_kind_of_existing_business_history() {
    return Stream.of(
        named("terminal order history", SnapshotBuilder.canonical().orderHistoryCount(1).build()),
        named("trade history", SnapshotBuilder.canonical().tradeHistoryCount(1).build()),
        named("closed perpetual position history",
            SnapshotBuilder.canonical().perpetualPositionHistoryCount(1).build()),
        named("spot position history", SnapshotBuilder.canonical().spotPositionHistoryCount(1).build()),
        named("extra cash ledger", SnapshotBuilder.canonical().extraCashLedger().build()),
        named("extra asset ledger", SnapshotBuilder.canonical().extraAssetLedger().build()))
        .map(example -> dynamicTest(example.name(), () -> {
          FakeSeedGateway gateway = new FakeSeedGateway(example.snapshot());
          ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);

          assertBusinessCode(
              () -> service.seed(ACCOUNT_ID, request(SEED_ID, "50000")),
              "VALIDATION_ACCOUNT_NOT_PRISTINE");
          assertThat(gateway.committedWrites()).isEmpty();
          assertThat(gateway.receiptCount()).isZero();
        }));
  }

  @TestFactory
  Stream<org.junit.jupiter.api.DynamicTest> requires_the_exact_canonical_registration_baseline() {
    return Stream.of(
        named("live account", SnapshotBuilder.canonical().accountType("LIVE").build()),
        named("inactive account", SnapshotBuilder.canonical().accountStatus("DISABLED").build()),
        named("wrong account id", SnapshotBuilder.canonical().accountId(UUID.randomUUID()).build()),
        named("non-USDT base", SnapshotBuilder.canonical().baseCurrency("USD").build()),
        named("generation after reset", SnapshotBuilder.canonical().demoGeneration(2).build()),
        named("reset marker present",
            SnapshotBuilder.canonical().resetAt(Instant.parse("2026-07-23T00:00:00Z")).build()),
        named("cash changed without ledger", SnapshotBuilder.canonical().cashBalance("49999").build()),
        named("equity changed without ledger", SnapshotBuilder.canonical().equity("49999").build()),
        named("used margin is nonzero", SnapshotBuilder.canonical().usedMargin("1").build()),
        named("margin level is present", SnapshotBuilder.canonical().marginLevel("100").build()),
        named("registration leverage changed", SnapshotBuilder.canonical().leverage(20).build()),
        named("registration position mode changed",
            SnapshotBuilder.canonical().positionMode("HEDGE").build()),
        named("spot wallet changed without ledger",
            SnapshotBuilder.canonical().walletBalance("49999").build()),
        named("locked spot funds", SnapshotBuilder.canonical().walletLocked("1").build()),
        named("unexpected wallet", SnapshotBuilder.canonical().extraWallet("BTC", "0").build()),
        named("missing cash DEMO_INIT", SnapshotBuilder.canonical().missingCashInit().build()),
        named("duplicate cash DEMO_INIT", SnapshotBuilder.canonical().duplicateCashInit().build()),
        named("wrong cash DEMO_INIT operation",
            SnapshotBuilder.canonical().wrongCashOperation().build()),
        named("wrong cash DEMO_INIT amount", SnapshotBuilder.canonical().wrongCashAmount().build()),
        named("wrong cash DEMO_INIT currency",
            SnapshotBuilder.canonical().wrongCashCurrency().build()),
        named("wrong cash DEMO_INIT reference", SnapshotBuilder.canonical().wrongCashReference().build()),
        named("missing asset DEMO_INIT", SnapshotBuilder.canonical().missingAssetInit().build()),
        named("duplicate asset DEMO_INIT", SnapshotBuilder.canonical().duplicateAssetInit().build()),
        named("wrong asset DEMO_INIT operation",
            SnapshotBuilder.canonical().wrongAssetOperation().build()),
        named("wrong asset DEMO_INIT amount",
            SnapshotBuilder.canonical().wrongAssetAmount().build()),
        named("wrong asset DEMO_INIT wallet",
            SnapshotBuilder.canonical().wrongAssetWallet().build()),
        named("wrong asset DEMO_INIT reference", SnapshotBuilder.canonical().wrongAssetReference().build()))
        .map(example -> dynamicTest(example.name(), () -> {
          FakeSeedGateway gateway = new FakeSeedGateway(example.snapshot());
          ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);

          assertBusinessCode(
              () -> service.seed(ACCOUNT_ID, request(SEED_ID, "50000")),
              "VALIDATION_ACCOUNT_NOT_PRISTINE");
          assertThat(gateway.committedWrites()).isEmpty();
          assertThat(gateway.receiptCount()).isZero();
        }));
  }

  @Test
  void rejects_invalid_precision_amounts_and_asset_normalization_before_opening_a_transaction() {
    List<InvalidRequest> invalid = List.of(
        invalid("missing seed id", new ValidationAccountSeedRequest(null, balances("USDT", "50000"))),
        invalid("missing balances", new ValidationAccountSeedRequest(SEED_ID, null)),
        invalid("missing USDT", new ValidationAccountSeedRequest(SEED_ID, balances("BTC", "1"))),
        invalid("negative", new ValidationAccountSeedRequest(SEED_ID, balances("USDT", "-0.00000001"))),
        invalid("more than eight decimals",
            new ValidationAccountSeedRequest(SEED_ID, balances("USDT", "0.123456789"))),
        invalid("NUMERIC(24,8) overflow",
            new ValidationAccountSeedRequest(SEED_ID, balances("USDT", "100000000000000000.00000000"))),
        invalid("blank asset", new ValidationAccountSeedRequest(SEED_ID, balances(
            "USDT", "50000", " ", "1"))),
        invalid("duplicate after canonicalization",
            new ValidationAccountSeedRequest(SEED_ID, duplicateCanonicalAssetBalances())),
        invalid("unsupported spot asset", new ValidationAccountSeedRequest(SEED_ID, balances(
            "USDT", "50000", "DOGE", "1"))));

    for (InvalidRequest example : invalid) {
      FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
      ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);

      assertBusinessCode(
          () -> service.seed(ACCOUNT_ID, example.request()),
          "VALIDATION_SEED_INVALID",
          example.name());
      assertThat(gateway.transactionCount()).as(example.name()).isZero();
      assertThat(gateway.committedWrites()).as(example.name()).isEmpty();
      assertThat(gateway.receiptCount()).as(example.name()).isZero();
    }
  }

  @Test
  void asset_capability_change_between_preflight_and_locked_view_fails_closed() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    gateway.lockedSupportedSpotAssets(Set.of("USDT", "ETH", "BNB", "SOL", "XRP"));
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);

    assertBusinessCode(
        () -> service.seed(
            ACCOUNT_ID,
            new ValidationAccountSeedRequest(SEED_ID, balances(
                "USDT", "50000",
                "BTC", "1"))),
        "VALIDATION_SEED_INVALID");

    assertThat(gateway.transactionCount()).isEqualTo(1);
    assertThat(gateway.committedWrites()).isEmpty();
    assertThat(gateway.receiptCount()).isZero();
  }

  @Test
  void a_writer_failure_rolls_back_cash_all_wallet_targets_and_the_receipt() {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    gateway.failOnWriteOrdinal(3);
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    ValidationAccountSeedRequest request = new ValidationAccountSeedRequest(SEED_ID, balances(
        "USDT", "0",
        "BTC", "1",
        "ETH", "2"));

    assertThatThrownBy(() -> service.seed(ACCOUNT_ID, request))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("injected seed writer failure");

    assertThat(gateway.cashBalance()).isEqualByComparingTo(INITIAL);
    assertThat(gateway.spotBalance("USDT")).isEqualByComparingTo(INITIAL);
    assertThat(gateway.spotBalance("BTC")).isEqualByComparingTo("0");
    assertThat(gateway.spotBalance("ETH")).isEqualByComparingTo("0");
    assertThat(gateway.committedWrites()).isEmpty();
    assertThat(gateway.receiptCount()).isZero();

    gateway.failOnWriteOrdinal(0);
    ValidationAccountSeedReceipt retried = service.seed(ACCOUNT_ID, request);
    assertThat(retried.seedId()).isEqualTo(SEED_ID);
    assertThat(gateway.receiptCount()).isEqualTo(1);
  }

  @Test
  void concurrent_same_request_commits_once_and_the_loser_replays() throws Exception {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    ValidationAccountSeedRequest request = new ValidationAccountSeedRequest(SEED_ID, balances(
        "USDT", "10000",
        "BTC", "0.5"));

    List<ValidationAccountSeedReceipt> receipts = runTogether(
        () -> service.seed(ACCOUNT_ID, request),
        () -> service.seed(ACCOUNT_ID, request));

    assertThat(receipts).hasSize(2);
    assertThat(receipts.get(0)).isEqualTo(receipts.get(1));
    assertThat(gateway.receiptCount()).isEqualTo(1);
    assertThat(gateway.committedWrites()).hasSize(3);
    assertEveryTransactionLockedAccountBeforeWallets(gateway.lockEvents());
  }

  @Test
  void concurrent_distinct_requests_have_exactly_one_winner_and_one_fail_closed_loser()
      throws Exception {
    FakeSeedGateway gateway = new FakeSeedGateway(canonicalSnapshot());
    ValidationAccountSeedService service = new ValidationAccountSeedService(gateway);
    ValidationAccountSeedRequest first = request(
        UUID.fromString("20000000-0000-0000-0000-000000000011"), "10000");
    ValidationAccountSeedRequest second = request(
        UUID.fromString("20000000-0000-0000-0000-000000000012"), "20000");

    List<ConcurrentResult> results = runTogether(
        () -> capture(() -> service.seed(ACCOUNT_ID, first)),
        () -> capture(() -> service.seed(ACCOUNT_ID, second)));

    assertThat(results).filteredOn(result -> result.receipt() != null).hasSize(1);
    assertThat(results).filteredOn(result -> result.failure() != null).hasSize(1);
    Throwable failure = results.stream()
        .map(ConcurrentResult::failure)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseThrow();
    assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getCode())
            .isEqualTo("VALIDATION_ACCOUNT_NOT_PRISTINE"));
    assertThat(gateway.receiptCount()).isEqualTo(1);
    assertThat(gateway.committedWrites()).hasSize(2);
    assertEveryTransactionLockedAccountBeforeWallets(gateway.lockEvents());
  }

  private static ValidationAccountSeedRequest request(UUID seedId, String usdt) {
    return new ValidationAccountSeedRequest(seedId, balances("USDT", usdt));
  }

  private static PristineSnapshot canonicalSnapshot() {
    return SnapshotBuilder.canonical().build();
  }

  private static NamedSnapshot named(String name, PristineSnapshot snapshot) {
    return new NamedSnapshot(name, snapshot);
  }

  private static InvalidRequest invalid(String name, ValidationAccountSeedRequest request) {
    return new InvalidRequest(name, request);
  }

  private static Map<String, BigDecimal> balances(String... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("key/value pairs required");
    }
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      result.put(keyValues[index], new BigDecimal(keyValues[index + 1]));
    }
    return result;
  }

  private static Map<String, BigDecimal> duplicateCanonicalAssetBalances() {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    result.put("USDT", new BigDecimal("50000"));
    result.put("btc", new BigDecimal("1"));
    result.put(" BTC ", new BigDecimal("2"));
    return result;
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value).setScale(8, RoundingMode.UNNECESSARY);
  }

  private static void assertBusinessCode(Runnable operation, String expectedCode) {
    assertBusinessCode(operation, expectedCode, expectedCode);
  }

  private static void assertBusinessCode(
      Runnable operation,
      String expectedCode,
      String description
  ) {
    assertThatThrownBy(operation::run)
        .as(description)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
  }

  @SafeVarargs
  private static <T> List<T> runTogether(ThrowingSupplier<T>... operations) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(operations.length);
    CountDownLatch ready = new CountDownLatch(operations.length);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (ThrowingSupplier<T> operation : operations) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent seed start timed out");
          }
          return operation.get();
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(5, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static ConcurrentResult capture(ThrowingSupplier<ValidationAccountSeedReceipt> action) {
    try {
      return new ConcurrentResult(action.get(), null);
    } catch (Throwable failure) {
      return new ConcurrentResult(null, failure);
    }
  }

  private static void assertEveryTransactionLockedAccountBeforeWallets(List<String> events) {
    assertThat(events.size()).isEven();
    for (int index = 0; index < events.size(); index += 2) {
      assertThat(events.subList(index, index + 2)).containsExactly("ACCOUNT", "WALLETS");
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private record NamedSnapshot(String name, PristineSnapshot snapshot) {
  }

  private record InvalidRequest(String name, ValidationAccountSeedRequest request) {
  }

  private record ConcurrentResult(
      ValidationAccountSeedReceipt receipt,
      Throwable failure
  ) {
  }

  private record TargetWrite(
      String kind,
      UUID seedId,
      String asset,
      BigDecimal target,
      BigDecimal delta
  ) {
    private static TargetWrite perpetual(UUID seedId, BigDecimal target, BigDecimal delta) {
      return new TargetWrite("PERPETUAL", seedId, "USDT", target, delta);
    }

    private static TargetWrite spot(
        UUID seedId,
        String asset,
        BigDecimal target,
        BigDecimal delta
    ) {
      return new TargetWrite("SPOT", seedId, asset, target, delta);
    }
  }

  private static final class SnapshotBuilder {
    private AccountView account;
    private final List<WalletView> wallets = new ArrayList<>();
    private final List<CashLedgerView> cashLedger = new ArrayList<>();
    private final List<AssetLedgerView> assetLedger = new ArrayList<>();
    private long orderHistoryCount;
    private long tradeHistoryCount;
    private long perpetualPositionHistoryCount;
    private long spotPositionHistoryCount;

    private static SnapshotBuilder canonical() {
      SnapshotBuilder builder = new SnapshotBuilder();
      builder.account = new AccountView(
          ACCOUNT_ID,
          "DEMO",
          "ACTIVE",
          "USDT",
          INITIAL,
          INITIAL,
          money("0"),
          INITIAL,
          null,
          10,
          "ONE_WAY",
          1L,
          null);
      builder.wallets.add(new WalletView(
          "SPOT", "USDT", INITIAL, INITIAL, money("0")));
      builder.cashLedger.add(canonicalCashInit(ACCOUNT_ID));
      builder.assetLedger.add(canonicalAssetInit(ACCOUNT_ID));
      return builder;
    }

    private SnapshotBuilder accountId(UUID value) {
      AccountView current = account;
      account = new AccountView(
          value, current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder accountType(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), value, current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder accountStatus(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), value, current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder baseCurrency(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), value,
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder demoGeneration(long value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(), value,
          current.resetAt());
      return this;
    }

    private SnapshotBuilder resetAt(Instant value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), value);
      return this;
    }

    private SnapshotBuilder cashBalance(String value) {
      BigDecimal amount = money(value);
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          amount, amount, current.usedMargin(), amount, current.marginLevel(),
          current.leverage(), current.positionMode(), current.demoGeneration(),
          current.resetAt());
      return this;
    }

    private SnapshotBuilder equity(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), money(value), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder usedMargin(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), money(value), current.freeMargin(),
          current.marginLevel(), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder marginLevel(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          money(value), current.leverage(), current.positionMode(),
          current.demoGeneration(), current.resetAt());
      return this;
    }

    private SnapshotBuilder leverage(int value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), value, current.positionMode(), current.demoGeneration(),
          current.resetAt());
      return this;
    }

    private SnapshotBuilder positionMode(String value) {
      AccountView current = account;
      account = new AccountView(
          current.id(), current.accountType(), current.status(), current.baseCurrency(),
          current.balance(), current.equity(), current.usedMargin(), current.freeMargin(),
          current.marginLevel(), current.leverage(), value, current.demoGeneration(),
          current.resetAt());
      return this;
    }

    private SnapshotBuilder walletBalance(String value) {
      BigDecimal amount = money(value);
      WalletView current = wallets.get(0);
      wallets.set(0, new WalletView(
          current.walletType(), current.asset(), amount, amount, money("0")));
      return this;
    }

    private SnapshotBuilder walletLocked(String value) {
      WalletView current = wallets.get(0);
      BigDecimal locked = money(value);
      wallets.set(0, new WalletView(
          current.walletType(), current.asset(), current.total(),
          current.total().subtract(locked), locked));
      return this;
    }

    private SnapshotBuilder extraWallet(String asset, String value) {
      BigDecimal amount = money(value);
      wallets.add(new WalletView("SPOT", asset, amount, amount, money("0")));
      return this;
    }

    private SnapshotBuilder missingCashInit() {
      cashLedger.clear();
      return this;
    }

    private SnapshotBuilder duplicateCashInit() {
      cashLedger.add(canonicalCashInit(ACCOUNT_ID));
      return this;
    }

    private SnapshotBuilder wrongCashReference() {
      CashLedgerView current = cashLedger.get(0);
      cashLedger.set(0, new CashLedgerView(
          current.entryType(), current.operationType(), current.amount(),
          current.balanceAfter(), current.currency(), current.referenceType(),
          UUID.randomUUID()));
      return this;
    }

    private SnapshotBuilder wrongCashOperation() {
      CashLedgerView current = cashLedger.get(0);
      cashLedger.set(0, new CashLedgerView(
          current.entryType(), "DEMO_RESET", current.amount(), current.balanceAfter(),
          current.currency(), current.referenceType(), current.referenceId()));
      return this;
    }

    private SnapshotBuilder wrongCashAmount() {
      CashLedgerView current = cashLedger.get(0);
      cashLedger.set(0, new CashLedgerView(
          current.entryType(), current.operationType(), money("49999"), current.balanceAfter(),
          current.currency(), current.referenceType(), current.referenceId()));
      return this;
    }

    private SnapshotBuilder wrongCashCurrency() {
      CashLedgerView current = cashLedger.get(0);
      cashLedger.set(0, new CashLedgerView(
          current.entryType(), current.operationType(), current.amount(), current.balanceAfter(),
          "USD", current.referenceType(), current.referenceId()));
      return this;
    }

    private SnapshotBuilder missingAssetInit() {
      assetLedger.clear();
      return this;
    }

    private SnapshotBuilder duplicateAssetInit() {
      assetLedger.add(canonicalAssetInit(ACCOUNT_ID));
      return this;
    }

    private SnapshotBuilder wrongAssetReference() {
      AssetLedgerView current = assetLedger.get(0);
      assetLedger.set(0, new AssetLedgerView(
          current.walletType(), current.asset(), current.entryType(), current.operationType(),
          current.amount(), current.balanceAfter(), current.referenceType(), UUID.randomUUID()));
      return this;
    }

    private SnapshotBuilder wrongAssetOperation() {
      AssetLedgerView current = assetLedger.get(0);
      assetLedger.set(0, new AssetLedgerView(
          current.walletType(), current.asset(), current.entryType(), "DEMO_RESET",
          current.amount(), current.balanceAfter(), current.referenceType(),
          current.referenceId()));
      return this;
    }

    private SnapshotBuilder wrongAssetAmount() {
      AssetLedgerView current = assetLedger.get(0);
      assetLedger.set(0, new AssetLedgerView(
          current.walletType(), current.asset(), current.entryType(), current.operationType(),
          money("49999"), current.balanceAfter(), current.referenceType(),
          current.referenceId()));
      return this;
    }

    private SnapshotBuilder wrongAssetWallet() {
      AssetLedgerView current = assetLedger.get(0);
      assetLedger.set(0, new AssetLedgerView(
          "USDT_PERP", current.asset(), current.entryType(), current.operationType(),
          current.amount(), current.balanceAfter(), current.referenceType(),
          current.referenceId()));
      return this;
    }

    private SnapshotBuilder extraCashLedger() {
      cashLedger.add(new CashLedgerView(
          "DEMO_DEPOSIT", "DEMO_DEPOSIT", money("1"), money("50001"), "USDT",
          "TEST", UUID.randomUUID()));
      return this;
    }

    private SnapshotBuilder extraAssetLedger() {
      assetLedger.add(new AssetLedgerView(
          "SPOT", "USDT", "CREDIT_AVAILABLE", "CREDIT_AVAILABLE", money("1"),
          money("50001"), "TEST", UUID.randomUUID()));
      return this;
    }

    private SnapshotBuilder orderHistoryCount(long value) {
      orderHistoryCount = value;
      return this;
    }

    private SnapshotBuilder tradeHistoryCount(long value) {
      tradeHistoryCount = value;
      return this;
    }

    private SnapshotBuilder perpetualPositionHistoryCount(long value) {
      perpetualPositionHistoryCount = value;
      return this;
    }

    private SnapshotBuilder spotPositionHistoryCount(long value) {
      spotPositionHistoryCount = value;
      return this;
    }

    private PristineSnapshot build() {
      return new PristineSnapshot(
          account,
          List.copyOf(wallets),
          List.copyOf(cashLedger),
          List.copyOf(assetLedger),
          orderHistoryCount,
          tradeHistoryCount,
          perpetualPositionHistoryCount,
          spotPositionHistoryCount);
    }
  }

  private static CashLedgerView canonicalCashInit(UUID accountId) {
    return new CashLedgerView(
        "DEMO_INIT",
        "DEMO_INIT",
        INITIAL,
        INITIAL,
        "USDT",
        "DEMO_ACCOUNT",
        accountId);
  }

  private static AssetLedgerView canonicalAssetInit(UUID accountId) {
    return new AssetLedgerView(
        "SPOT",
        "USDT",
        "DEMO_INIT",
        "DEMO_INIT",
        INITIAL,
        INITIAL,
        "DEMO_ACCOUNT",
        accountId);
  }

  /** Copy-on-write fake: callback success commits; any exception discards every mutation. */
  private static final class FakeSeedGateway implements ValidationAccountSeedGateway {
    private final ReentrantLock transactionLock = new ReentrantLock();
    private final AtomicInteger transactionCount = new AtomicInteger();
    private final AtomicInteger pristineReadCount = new AtomicInteger();
    private final List<String> lockEvents = Collections.synchronizedList(new ArrayList<>());
    private volatile int failOnWriteOrdinal;
    private volatile Set<String> lockedSupportedSpotAssets = SUPPORTED_SPOT_ASSETS;
    private State state;

    private FakeSeedGateway(PristineSnapshot initialSnapshot) {
      this.state = State.initial(initialSnapshot);
    }

    @Override
    public Set<String> supportedSpotAssets() {
      return SUPPORTED_SPOT_ASSETS;
    }

    @Override
    public <T> T withLockedAccount(
        UUID accountId,
        LockedAccountCallback<T> callback
    ) {
      transactionCount.incrementAndGet();
      transactionLock.lock();
      try {
        lockEvents.add("ACCOUNT");
        lockEvents.add("WALLETS");
        State working = state.copy();
        T result = callback.apply(new FakeLockedAccount(accountId, working));
        state = working;
        return result;
      } finally {
        transactionLock.unlock();
      }
    }

    private final class FakeLockedAccount implements LockedAccount {
      private final UUID requestedAccountId;
      private final State working;
      private int writeOrdinal;

      private FakeLockedAccount(UUID requestedAccountId, State working) {
        this.requestedAccountId = requestedAccountId;
        this.working = working;
      }

      @Override
      public Optional<ValidationAccountSeedReceipt> findReceipt(UUID seedId) {
        return Optional.ofNullable(working.receipts.get(seedId));
      }

      @Override
      public Set<String> supportedSpotAssets() {
        return lockedSupportedSpotAssets;
      }

      @Override
      public PristineSnapshot snapshot() {
        pristineReadCount.incrementAndGet();
        if (!working.seeded) {
          return working.initialSnapshot;
        }
        return working.currentSnapshot();
      }

      @Override
      public void writePerpetualUsdtTarget(
          UUID seedId,
          BigDecimal target,
          BigDecimal delta
      ) {
        maybeFail();
        working.writes.add(TargetWrite.perpetual(seedId, target, delta));
        working.cashBalance = target;
      }

      @Override
      public void writeSpotTarget(
          UUID seedId,
          String canonicalAsset,
          BigDecimal target,
          BigDecimal delta
      ) {
        maybeFail();
        working.writes.add(TargetWrite.spot(seedId, canonicalAsset, target, delta));
        working.spotBalances.put(canonicalAsset, target);
      }

      @Override
      public void saveReceipt(ValidationAccountSeedReceipt receipt) {
        assertThat(receipt.accountId()).isEqualTo(requestedAccountId);
        working.receipts.put(receipt.seedId(), receipt);
        working.seeded = true;
      }

      private void maybeFail() {
        writeOrdinal++;
        if (failOnWriteOrdinal > 0 && writeOrdinal == failOnWriteOrdinal) {
          throw new IllegalStateException("injected seed writer failure");
        }
      }
    }

    private void failOnWriteOrdinal(int ordinal) {
      failOnWriteOrdinal = ordinal;
    }

    private void lockedSupportedSpotAssets(Set<String> assets) {
      lockedSupportedSpotAssets = Set.copyOf(assets);
    }

    private int transactionCount() {
      return transactionCount.get();
    }

    private int pristineReadCount() {
      return pristineReadCount.get();
    }

    private List<TargetWrite> committedWrites() {
      transactionLock.lock();
      try {
        return List.copyOf(state.writes);
      } finally {
        transactionLock.unlock();
      }
    }

    private int receiptCount() {
      transactionLock.lock();
      try {
        return state.receipts.size();
      } finally {
        transactionLock.unlock();
      }
    }

    private BigDecimal cashBalance() {
      transactionLock.lock();
      try {
        return state.cashBalance;
      } finally {
        transactionLock.unlock();
      }
    }

    private BigDecimal spotBalance(String asset) {
      transactionLock.lock();
      try {
        return state.spotBalances.getOrDefault(asset, money("0"));
      } finally {
        transactionLock.unlock();
      }
    }

    private List<String> lockEvents() {
      synchronized (lockEvents) {
        return List.copyOf(lockEvents);
      }
    }

    private PristineSnapshot currentSnapshot() {
      transactionLock.lock();
      try {
        return state.currentSnapshot();
      } finally {
        transactionLock.unlock();
      }
    }
  }

  private static final class State {
    private final PristineSnapshot initialSnapshot;
    private final Map<UUID, ValidationAccountSeedReceipt> receipts;
    private final List<TargetWrite> writes;
    private final Map<String, BigDecimal> spotBalances;
    private BigDecimal cashBalance;
    private boolean seeded;

    private State(
        PristineSnapshot initialSnapshot,
        Map<UUID, ValidationAccountSeedReceipt> receipts,
        List<TargetWrite> writes,
        Map<String, BigDecimal> spotBalances,
        BigDecimal cashBalance,
        boolean seeded
    ) {
      this.initialSnapshot = initialSnapshot;
      this.receipts = receipts;
      this.writes = writes;
      this.spotBalances = spotBalances;
      this.cashBalance = cashBalance;
      this.seeded = seeded;
    }

    private static State initial(PristineSnapshot snapshot) {
      Map<String, BigDecimal> balances = new LinkedHashMap<>();
      for (WalletView wallet : snapshot.wallets()) {
        if ("SPOT".equals(wallet.walletType())) {
          balances.put(wallet.asset(), wallet.total());
        }
      }
      return new State(
          snapshot,
          new LinkedHashMap<>(),
          new ArrayList<>(),
          balances,
          snapshot.account().balance(),
          false);
    }

    private State copy() {
      return new State(
          initialSnapshot,
          new LinkedHashMap<>(receipts),
          new ArrayList<>(writes),
          new LinkedHashMap<>(spotBalances),
          cashBalance,
          seeded);
    }

    private PristineSnapshot currentSnapshot() {
      AccountView initialAccount = initialSnapshot.account();
      AccountView account = new AccountView(
          initialAccount.id(),
          initialAccount.accountType(),
          initialAccount.status(),
          initialAccount.baseCurrency(),
          cashBalance,
          cashBalance,
          money("0"),
          cashBalance,
          null,
          initialAccount.leverage(),
          initialAccount.positionMode(),
          initialAccount.demoGeneration(),
          initialAccount.resetAt());

      List<WalletView> wallets = spotBalances.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> new WalletView(
              "SPOT", entry.getKey(), entry.getValue(), entry.getValue(), money("0")))
          .toList();
      List<CashLedgerView> cash = new ArrayList<>(initialSnapshot.cashLedger());
      List<AssetLedgerView> asset = new ArrayList<>(initialSnapshot.assetLedger());
      for (TargetWrite write : writes) {
        if ("PERPETUAL".equals(write.kind())) {
          cash.add(new CashLedgerView(
              "VALIDATION_SEED", "VALIDATION_SEED", write.delta(), write.target(), "USDT",
              "VALIDATION_SEED", write.seedId()));
        } else {
          asset.add(new AssetLedgerView(
              "SPOT", write.asset(), "VALIDATION_SEED", "VALIDATION_SEED", write.delta(),
              write.target(), "VALIDATION_SEED", write.seedId()));
        }
      }
      return new PristineSnapshot(
          account,
          List.copyOf(wallets),
          List.copyOf(cash),
          List.copyOf(asset),
          initialSnapshot.orderHistoryCount(),
          initialSnapshot.tradeHistoryCount(),
          initialSnapshot.perpetualPositionHistoryCount(),
          initialSnapshot.spotPositionHistoryCount());
    }
  }
}
