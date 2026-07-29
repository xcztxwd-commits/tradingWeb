package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoFillIdentityTest {

  private static final UUID ORDER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

  @Test
  void forTickHashesTheVersionedTrimmedCanonicalInputAsLowercaseSha256() {
    String identity = DemoFillIdentity.forTick(ORDER_ID, "  tick-42  ", 3);

    assertThat(identity)
        .isEqualTo("7009f5b81af030ead708a439ce7b640ab165e909cc27dde92db27cfaf7184307")
        .matches("[0-9a-f]{64}")
        .isEqualTo(DemoFillIdentity.forTick(ORDER_ID, "tick-42", 3));
  }

  @Test
  void fillIdentityChangesWhenTheTickOrOrdinalChanges() {
    String baseline = DemoFillIdentity.forTick(ORDER_ID, "tick-42", 3);

    assertThat(DemoFillIdentity.forTick(ORDER_ID, "tick-43", 3)).isNotEqualTo(baseline);
    assertThat(DemoFillIdentity.forTick(ORDER_ID, "tick-42", 4)).isNotEqualTo(baseline);
  }

  @Test
  void forSnapshotNormalizesEveryTextualSnapshotComponentAndIncludesTheAsOfInstant() {
    ExecutableMarketSnapshot normalized = snapshot(
        " BTCUSDT ", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        " demo ", " btc-usdt ", Instant.parse("2026-07-17T10:15:30Z"));
    ExecutableMarketSnapshot equivalent = snapshot(
        "btcusdt", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "BTC-USDT", Instant.parse("2026-07-17T10:15:30Z"));

    String identity = DemoFillIdentity.forSnapshot(ORDER_ID, normalized, 0);

    assertThat(identity)
        .isEqualTo("13b960239264b2b6b9277f515f9e3c6893fbe74f566fd19a88b5bb1aa03bae9e")
        .isEqualTo(DemoFillIdentity.forSnapshot(ORDER_ID, equivalent, 0));
  }

  @Test
  void snapshotIdentityEscapesProviderComponentBoundaries() {
    Instant asOf = Instant.parse("2026-07-17T10:15:30Z");
    ExecutableMarketSnapshot delimiterInCode = snapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO|BTC", "USDT", asOf);
    ExecutableMarketSnapshot delimiterInSymbol = snapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "BTC|USDT", asOf);

    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, delimiterInCode, 0))
        .isNotEqualTo(DemoFillIdentity.forSnapshot(ORDER_ID, delimiterInSymbol, 0));
  }

  @Test
  void snapshotIdentityEscapesBackslashesBeforeDelimiters() {
    Instant asOf = Instant.parse("2026-07-17T10:15:30Z");
    ExecutableMarketSnapshot delimiterInSymbol = snapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO\\", "BTC|USDT", asOf);
    ExecutableMarketSnapshot redistributedBoundary = snapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO|BTC\\", "USDT", asOf);

    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, delimiterInSymbol, 0))
        .isNotEqualTo(DemoFillIdentity.forSnapshot(ORDER_ID, redistributedBoundary, 0));
  }

  @Test
  void snapshotIdentityChangesWhenAnyNamedComponentChanges() {
    ExecutableMarketSnapshot baseline = validSnapshot();
    String identity = DemoFillIdentity.forSnapshot(ORDER_ID, baseline, 0);

    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        "ETHUSDT", ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "BTC-USDT", baseline.asOf()), 0)).isNotEqualTo(identity);
    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        baseline.platformSymbol(), ProductType.LINEAR_PERP, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "BTC-USDT", baseline.asOf()), 0)).isNotEqualTo(identity);
    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        baseline.platformSymbol(), ProductType.CRYPTO_SPOT, MarketSourceMode.PUBLIC_EXTERNAL,
        "DEMO", "BTC-USDT", baseline.asOf()), 0)).isNotEqualTo(identity);
    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        baseline.platformSymbol(), ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "OTHER", "BTC-USDT", baseline.asOf()), 0)).isNotEqualTo(identity);
    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        baseline.platformSymbol(), ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "ETH-USDT", baseline.asOf()), 0)).isNotEqualTo(identity);
    assertThat(DemoFillIdentity.forSnapshot(ORDER_ID, snapshot(
        baseline.platformSymbol(), ProductType.CRYPTO_SPOT, MarketSourceMode.LOCAL_SIMULATED,
        "DEMO", "BTC-USDT", baseline.asOf().plusSeconds(1)), 0)).isNotEqualTo(identity);
  }

  @Test
  void tradeIdIsDeterministicAndBoundToBothOrderAndFillIdentity() {
    String fillIdentity = DemoFillIdentity.forTick(ORDER_ID, "tick-42", 3);

    assertThat(DemoFillIdentity.tradeId(ORDER_ID, fillIdentity))
        .isEqualTo(DemoFillIdentity.tradeId(ORDER_ID, fillIdentity))
        .isNotEqualTo(DemoFillIdentity.tradeId(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), fillIdentity))
        .isNotEqualTo(DemoFillIdentity.tradeId(ORDER_ID,
            DemoFillIdentity.forTick(ORDER_ID, "tick-43", 3)));
  }

  @Test
  void tradeIdDistinguishesSignificantFillIdentityWhitespace() {
    assertThat(DemoFillIdentity.tradeId(ORDER_ID, "identity"))
        .isNotEqualTo(DemoFillIdentity.tradeId(ORDER_ID, " identity "));
  }

  @Test
  void tradeIdRejectsFillIdentityLongerThanTheTradesColumn() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DemoFillIdentity.tradeId(ORDER_ID, "x".repeat(65)));
  }

  @Test
  void identityMethodsRejectNullBlankAndNegativeInputs() {
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.forTick(null, "tick", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.forTick(ORDER_ID, "  ", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.forTick(ORDER_ID, "tick", -1));
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.forSnapshot(ORDER_ID, null, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.tradeId(null, "fill"));
    assertThatIllegalArgumentException().isThrownBy(() -> DemoFillIdentity.tradeId(ORDER_ID, "  "));
  }

  @Test
  void forSnapshotRejectsMissingOrBlankEachNamedComponent() {
    ExecutableMarketSnapshot valid = validSnapshot();

    assertSnapshotRejected(snapshot(null, valid.productType(), valid.sourceMode(),
        valid.providerCode(), valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(" ", valid.productType(), valid.sourceMode(),
        valid.providerCode(), valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), null, valid.sourceMode(),
        valid.providerCode(), valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), null,
        valid.providerCode(), valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), valid.sourceMode(),
        null, valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), valid.sourceMode(),
        " ", valid.providerSymbol(), valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), valid.sourceMode(),
        valid.providerCode(), null, valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), valid.sourceMode(),
        valid.providerCode(), " ", valid.asOf()));
    assertSnapshotRejected(snapshot(valid.platformSymbol(), valid.productType(), valid.sourceMode(),
        valid.providerCode(), valid.providerSymbol(), null));
  }

  private static void assertSnapshotRejected(ExecutableMarketSnapshot snapshot) {
    assertThatIllegalArgumentException().isThrownBy(() ->
        DemoFillIdentity.forSnapshot(ORDER_ID, snapshot, 0));
  }

  private static ExecutableMarketSnapshot validSnapshot() {
    return snapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        MarketSourceMode.LOCAL_SIMULATED,
        "DEMO",
        "BTC-USDT",
        Instant.parse("2026-07-17T10:15:30Z"));
  }

  private static ExecutableMarketSnapshot snapshot(
      String platformSymbol,
      ProductType productType,
      MarketSourceMode sourceMode,
      String providerCode,
      String providerSymbol,
      Instant asOf
  ) {
    return new ExecutableMarketSnapshot(
        platformSymbol,
        productType,
        providerCode,
        providerSymbol,
        sourceMode,
        null,
        null,
        null,
        null,
        null,
        asOf,
        null);
  }
}
