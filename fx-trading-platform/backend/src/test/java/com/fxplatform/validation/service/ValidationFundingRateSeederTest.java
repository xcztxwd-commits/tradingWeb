package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationMarketState.FundingRatePoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ValidationFundingRateSeederTest {

  private static final UUID RUN_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000095");
  private static final Instant TIME = Instant.parse("2030-01-01T00:00:01Z");

  private final FundingRateRepository repository = Mockito.mock(FundingRateRepository.class);
  private final ValidationFundingRateSeeder seeder =
      new ValidationFundingRateSeeder(repository);

  @Test
  void tickFundingRatesAreCanonicalSortedImmutableAndPerpetualBound() {
    CompositeTick tick = tick(List.of(
        new FundingRatePoint(" ethusdt-perp ", new BigDecimal("-0.0002000000")),
        new FundingRatePoint("BTCUSDT-PERP", new BigDecimal("0.0001000000"))));

    assertThat(tick.fundingRates())
        .extracting(FundingRatePoint::platformSymbol)
        .containsExactly("BTCUSDT-PERP", "ETHUSDT-PERP");
    assertThatThrownBy(() -> tick.fundingRates().add(
        new FundingRatePoint("XRPUSDT-PERP", BigDecimal.ZERO)))
        .isInstanceOf(UnsupportedOperationException.class);

    CompositeTick missingPerpetual = tick(List.of(
        new FundingRatePoint("XRPUSDT-PERP", new BigDecimal("0.0001000000"))));
    assertThatThrownBy(() -> ValidationMarketState.validateComplete(missingPerpetual))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_SYMBOL_MISSING");
    assertThatThrownBy(() -> tick(List.of(
        new FundingRatePoint("BTCUSDT-PERP", new BigDecimal("0.0001000000")),
        new FundingRatePoint("btcusdt-perp", new BigDecimal("0.0002000000")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FundingRatePoint(
        "BTCUSDT-PERP", new BigDecimal("0.00000000001")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void persistsOnlyDerivedValidationProvenanceAndExactTickMark() {
    when(repository.insertIfAbsent(any())).thenReturn(true);

    int inserted = seeder.persist(tick(List.of(
        new FundingRatePoint("BTCUSDT-PERP", new BigDecimal("0.0001000000")))));

    assertThat(inserted).isEqualTo(1);
    ArgumentCaptor<FundingRateEntity> row = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(repository).insertIfAbsent(row.capture());
    assertThat(row.getValue().getSymbol()).isEqualTo("BTCUSDT-PERP");
    assertThat(row.getValue().getFundingRate()).isEqualByComparingTo("0.0001000000");
    assertThat(row.getValue().getFundingTime()).isEqualTo(TIME);
    assertThat(row.getValue().getNextFundingTime()).isEqualTo(TIME.plusSeconds(8 * 60 * 60));
    assertThat(row.getValue().getMarkPrice()).isEqualByComparingTo("50000.0000000000");
    assertThat(row.getValue().getProviderCode()).isEqualTo("VALIDATION");
    assertThat(row.getValue().getSourceMode()).isEqualTo("DEMO");
    assertThat(row.getValue().getAsOf()).isEqualTo(TIME);
    assertThat(row.getValue().getIntervalMinutes()).isEqualTo(480);
    assertThat(row.getValue().getRawPayloadHash()).matches("[0-9a-f]{64}");
  }

  @Test
  void exactReplayIsIdempotentButConflictingExistingRateFailsClosed() {
    CompositeTick tick = tick(List.of(
        new FundingRatePoint("BTCUSDT-PERP", new BigDecimal("0.0001000000"))));
    when(repository.insertIfAbsent(any())).thenReturn(true);
    seeder.persist(tick);
    ArgumentCaptor<FundingRateEntity> inserted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(repository).insertIfAbsent(inserted.capture());

    Mockito.reset(repository);
    when(repository.insertIfAbsent(any())).thenReturn(false);
    when(repository.findBySymbolAndFundingTime("BTCUSDT-PERP", TIME))
        .thenReturn(java.util.Optional.of(inserted.getValue()));
    assertThat(seeder.persist(tick)).isZero();

    FundingRateEntity conflict = copy(inserted.getValue());
    conflict.setFundingRate(new BigDecimal("0.0003000000"));
    when(repository.findBySymbolAndFundingTime("BTCUSDT-PERP", TIME))
        .thenReturn(java.util.Optional.of(conflict));
    assertThatThrownBy(() -> seeder.persist(tick))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");
  }

  @Test
  void rehydrationRequiresTheExactPersistedFundingAuthorityWithoutRepairingIt() {
    CompositeTick tick = tick(List.of(
        new FundingRatePoint("BTCUSDT-PERP", new BigDecimal("0.0001000000"))));
    when(repository.insertIfAbsent(any())).thenReturn(true);
    seeder.persist(tick);
    ArgumentCaptor<FundingRateEntity> inserted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(repository).insertIfAbsent(inserted.capture());

    Mockito.reset(repository);
    when(repository.findByFundingTime(TIME)).thenReturn(List.of(inserted.getValue()));
    assertThatCode(() -> seeder.requireExactPersisted(tick)).doesNotThrowAnyException();

    FundingRateEntity conflict = copy(inserted.getValue());
    conflict.setRawPayloadHash("different");
    when(repository.findByFundingTime(TIME)).thenReturn(List.of(conflict));
    assertThatThrownBy(() -> seeder.requireExactPersisted(tick))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");

    when(repository.findByFundingTime(TIME)).thenReturn(List.of());
    assertThatThrownBy(() -> seeder.requireExactPersisted(tick))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");

    when(repository.findByFundingTime(TIME)).thenReturn(List.of(inserted.getValue()));
    assertThatThrownBy(() -> seeder.requireExactPersisted(tick(List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");
    verify(repository, org.mockito.Mockito.never()).insertIfAbsent(any());
  }

  private static CompositeTick tick(List<FundingRatePoint> rates) {
    return new CompositeTick(
        RUN_ID,
        7L,
        1L,
        TIME,
        "funding-tick",
        List.of(),
        List.of(
            perpetual("BTCUSDT-PERP", "50000"),
            perpetual("ETHUSDT-PERP", "3000")),
        rates);
  }

  private static PerpetualMarketBundle perpetual(String symbol, String price) {
    BigDecimal last = new BigDecimal(price);
    Instant expiresAt = TIME.plusSeconds(2);
    MarketDepthResponse depth = new MarketDepthResponse(
        symbol,
        TIME.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(last.subtract(BigDecimal.ONE), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(last.add(BigDecimal.ONE), BigDecimal.TEN)),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        TIME,
        expiresAt,
        false);
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        last.setScale(10),
        last,
        depth,
        List.of(new RecentTradeResponse(
            "trade-" + symbol,
            symbol,
            last,
            BigDecimal.ONE,
            "BUY",
            TIME.toEpochMilli(),
            "validation",
            symbol,
            MarketSourceMode.LOCAL_SIMULATED,
            TIME,
            expiresAt,
            false)),
        List.of(new CandleResponse(
            TIME.toEpochMilli(),
            last,
            last.add(BigDecimal.ONE),
            last.subtract(BigDecimal.ONE),
            last,
            BigDecimal.TEN,
            "validation",
            symbol,
            MarketSourceMode.LOCAL_SIMULATED,
            TIME,
            expiresAt,
            false)),
        TIME,
        expiresAt);
  }

  private static FundingRateEntity copy(FundingRateEntity source) {
    FundingRateEntity copy = new FundingRateEntity();
    copy.setId(source.getId());
    copy.setSymbol(source.getSymbol());
    copy.setFundingRate(source.getFundingRate());
    copy.setFundingTime(source.getFundingTime());
    copy.setNextFundingTime(source.getNextFundingTime());
    copy.setMarkPrice(source.getMarkPrice());
    copy.setProviderCode(source.getProviderCode());
    copy.setSourceMode(source.getSourceMode());
    copy.setAsOf(source.getAsOf());
    copy.setIntervalMinutes(source.getIntervalMinutes());
    copy.setRawPayloadHash(source.getRawPayloadHash());
    return copy;
  }
}
