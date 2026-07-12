package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class FundingSettlementSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-07-13T16:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock
  private FundingRateIngestionService fundingRateIngestionService;

  @Mock
  private FundingRateRepository fundingRateRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private FundingService fundingService;

  @Test
  void schedulerHasNoProcessMemoryFundingCursor() {
    assertThat(Arrays.stream(FundingSettlementScheduler.class.getDeclaredFields())
        .map(java.lang.reflect.Field::getName))
        .doesNotContain("lastScanTime");
  }

  @Test
  void ingestionCompletesBeforePersistentDueRatesAreSettled() {
    FundingRateEntity rate = rate("BTCUSDT-PERP", "2026-07-13T08:00:00Z");
    PositionEntity position = position("BTCUSDT-PERP");
    when(fundingRateRepository.findDueRates(null, NOW)).thenReturn(List.of(rate));
    when(positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(
        "BTCUSDT-PERP", PositionStatus.OPEN)).thenReturn(List.of(position));
    when(fundingService.settleFundingForPositionOutcome(position, rate))
        .thenReturn(outcome(true, BigDecimal.ONE));

    scheduler().settleDueFunding();

    InOrder ordered = inOrder(
        fundingRateIngestionService,
        fundingRateRepository,
        positionRepository,
        fundingService);
    ordered.verify(fundingRateIngestionService).ingestDueRates(NOW);
    ordered.verify(fundingRateRepository).findDueRates(null, NOW);
    ordered.verify(positionRepository).findBySymbolAndStatusOrderByOpenedAtAsc(
        "BTCUSDT-PERP", PositionStatus.OPEN);
    ordered.verify(fundingService).settleFundingForPositionOutcome(position, rate);
  }

  @Test
  void restartedSchedulerRescansPersistentRatesWithoutAnInMemoryWatermark() {
    FundingRateEntity rate = rate("BTCUSDT-PERP", "2026-07-13T08:00:00Z");
    PositionEntity position = position("BTCUSDT-PERP");
    when(fundingRateRepository.findDueRates(null, NOW)).thenReturn(List.of(rate));
    when(positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(
        "BTCUSDT-PERP", PositionStatus.OPEN)).thenReturn(List.of(position));
    when(fundingService.settleFundingForPositionOutcome(position, rate))
        .thenReturn(outcome(false, BigDecimal.ZERO));

    scheduler().settleDueFunding();
    scheduler().settleDueFunding();

    verify(fundingRateIngestionService, times(2)).ingestDueRates(NOW);
    verify(fundingRateRepository, times(2)).findDueRates(null, NOW);
    verify(fundingService, times(2)).settleFundingForPositionOutcome(position, rate);
  }

  @Test
  void oneFailedPositionDoesNotAbortOtherDueSettlements() {
    FundingRateEntity rate = rate("BTCUSDT-PERP", "2026-07-13T08:00:00Z");
    PositionEntity failed = position("BTCUSDT-PERP");
    PositionEntity healthy = position("BTCUSDT-PERP");
    when(fundingRateRepository.findDueRates(null, NOW)).thenReturn(List.of(rate));
    when(positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(
        "BTCUSDT-PERP", PositionStatus.OPEN)).thenReturn(List.of(failed, healthy));
    when(fundingService.settleFundingForPositionOutcome(failed, rate))
        .thenThrow(new BusinessException("FUNDING_SETTLEMENT_FAILED", "fixture failure"));
    when(fundingService.settleFundingForPositionOutcome(healthy, rate))
        .thenReturn(outcome(true, BigDecimal.ONE));

    assertThatCode(() -> scheduler().settleDueFunding()).doesNotThrowAnyException();

    verify(fundingService).settleFundingForPositionOutcome(failed, rate);
    verify(fundingService).settleFundingForPositionOutcome(healthy, rate);
  }

  @Test
  void returnCountExcludesReplayZeroMutations() {
    FundingRateEntity rate = rate("BTCUSDT-PERP", "2026-07-13T08:00:00Z");
    PositionEntity inserted = position("BTCUSDT-PERP");
    PositionEntity replay = position("BTCUSDT-PERP");
    when(fundingRateRepository.findDueRates(null, NOW)).thenReturn(List.of(rate));
    when(positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(
        "BTCUSDT-PERP", PositionStatus.OPEN)).thenReturn(List.of(inserted, replay));
    when(fundingService.settleFundingForPositionOutcome(inserted, rate))
        .thenReturn(outcome(true, BigDecimal.ONE));
    when(fundingService.settleFundingForPositionOutcome(replay, rate))
        .thenReturn(outcome(false, BigDecimal.ZERO));

    assertThat(scheduler().settleDueFunding()).isEqualTo(1);
  }

  @Test
  void schedulerKeepsEachPositionSettlementOutsideOneBatchTransaction() throws Exception {
    assertThat(FundingSettlementScheduler.class.getMethod("settleDueFunding")
        .isAnnotationPresent(Transactional.class))
        .isFalse();
  }

  @Test
  void schedulerRemainsDisabledWhenFundingPropertyIsMissing() {
    ConditionalOnProperty condition = FundingSettlementScheduler.class
        .getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("trading.funding");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  private FundingSettlementScheduler scheduler() {
    return new FundingSettlementScheduler(
        fundingRateIngestionService,
        fundingRateRepository,
        positionRepository,
        fundingService,
        CLOCK);
  }

  private static FundingRateEntity rate(String symbol, String fundingTime) {
    FundingRateEntity rate = new FundingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(symbol);
    rate.setFundingRate(new BigDecimal("0.0001"));
    rate.setFundingTime(Instant.parse(fundingTime));
    rate.setNextFundingTime(Instant.parse(fundingTime).plusSeconds(8 * 60 * 60));
    rate.setMarkPrice(new BigDecimal("50000"));
    return rate;
  }

  private static PositionEntity position(String symbol) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(UUID.randomUUID());
    position.setSymbol(symbol);
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static FundingService.FundingSettlementOutcome outcome(
      boolean inserted,
      BigDecimal cashflow
  ) {
    return new FundingService.FundingSettlementOutcome(inserted, cashflow);
  }
}
