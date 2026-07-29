package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.service.FundingSettlementProcessor;
import com.fxplatform.trading.service.LiquidationService;
import com.fxplatform.trading.service.PendingOrderExecutionService;
import com.fxplatform.trading.service.ProtectiveOrderExecutionService;
import com.fxplatform.trading.service.TrailingStopService;
import com.fxplatform.validation.service.ValidationMarketClock.Tick;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationSystemStepService.Phase;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class DefaultValidationSystemStepOperationsFundingReplayTest {

  private static final UUID RUN_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000096");
  private static final long GENERATION = 7L;
  private static final Instant TIME = Instant.parse("2030-01-01T00:00:01Z");

  private final ValidationMarketState marketState = mock(ValidationMarketState.class);
  private final ValidationMarketClock marketClock = mock(ValidationMarketClock.class);
  private final ValidationFundingRateSeeder fundingRates =
      mock(ValidationFundingRateSeeder.class);
  private final DefaultValidationSystemStepOperations operations =
      new DefaultValidationSystemStepOperations(
          marketState,
          marketClock,
          fundingRates,
          mock(TrailingStopService.class),
          mock(PendingOrderExecutionService.class),
          mock(ProtectiveOrderExecutionService.class),
          mock(FundingSettlementProcessor.class),
          mock(LiquidationService.class),
          mock(JdbcTemplate.class));

  @Test
  void initialPublishPersistsFundingBeforePublishingProcessLocalMarketState() {
    CompositeTick tick = tick();
    when(marketClock.current()).thenReturn(Optional.of(
        new Tick(RUN_ID, GENERATION, 1L, TIME)));

    operations.publishTick(request(tick));

    InOrder publish = inOrder(fundingRates, marketState);
    publish.verify(fundingRates).persist(tick);
    publish.verify(marketState).publish(tick);
  }

  @Test
  void initialFundingConflictLeavesProcessLocalMarketStateUnchanged() {
    CompositeTick tick = tick();
    when(marketClock.current()).thenReturn(Optional.of(
        new Tick(RUN_ID, GENERATION, 1L, TIME)));
    org.mockito.Mockito.doThrow(new BusinessException(
        "VALIDATION_FUNDING_RATE_CONFLICT",
        "persisted funding differs"))
        .when(fundingRates)
        .persist(tick);

    assertThatThrownBy(() -> operations.publishTick(request(tick)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");

    verify(marketState, never()).publish(tick);
  }

  @Test
  void rehydrationVerifiesPersistedFundingBeforePublishingProcessLocalMarketState() {
    CompositeTick tick = tick();
    when(marketClock.current()).thenReturn(Optional.of(
        new Tick(RUN_ID, GENERATION, 1L, TIME)));
    when(marketState.current()).thenReturn(Optional.empty());

    operations.rehydratePublishedTick(request(tick));

    InOrder replay = inOrder(fundingRates, marketState);
    replay.verify(fundingRates).requireExactPersisted(tick);
    replay.verify(marketState).publish(tick);
  }

  @Test
  void fundingConflictLeavesProcessLocalMarketStateUnchanged() {
    CompositeTick tick = tick();
    when(marketClock.current()).thenReturn(Optional.of(
        new Tick(RUN_ID, GENERATION, 1L, TIME)));
    when(marketState.current()).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new BusinessException(
        "VALIDATION_FUNDING_RATE_CONFLICT",
        "persisted funding differs"))
        .when(fundingRates)
        .requireExactPersisted(tick);

    assertThatThrownBy(() -> operations.rehydratePublishedTick(request(tick)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FUNDING_RATE_CONFLICT");

    verify(marketState, never()).publish(tick);
    verify(marketState, never()).restore(tick);
  }

  private static Request request(CompositeTick tick) {
    return new Request(tick, Phase.PRE_ACTIONS, "funding-replay");
  }

  private static CompositeTick tick() {
    CompositeTick tick = mock(CompositeTick.class);
    when(tick.runId()).thenReturn(RUN_ID);
    when(tick.generation()).thenReturn(GENERATION);
    when(tick.sequence()).thenReturn(1L);
    when(tick.virtualTime()).thenReturn(TIME);
    return tick;
  }
}
