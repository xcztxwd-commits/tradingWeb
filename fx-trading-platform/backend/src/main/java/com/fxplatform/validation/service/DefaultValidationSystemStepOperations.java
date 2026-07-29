package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.trading.service.FundingSettlementProcessor;
import com.fxplatform.trading.service.LiquidationService;
import com.fxplatform.trading.service.PendingOrderExecutionService;
import com.fxplatform.trading.service.ProtectiveOrderExecutionService;
import com.fxplatform.trading.service.TrailingStopService;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import com.fxplatform.validation.service.ValidationSystemStepService.SubStepResult;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit production ordering adapter for one validation Tick. */
@Profile("validation")
@Component
public class DefaultValidationSystemStepOperations implements ValidationSystemStepOperations {

  private final ValidationMarketState marketState;
  private final ValidationMarketClock marketClock;
  private final ValidationFundingRateSeeder fundingRateSeeder;
  private final TrailingStopService trailingStops;
  private final PendingOrderExecutionService pendingOrders;
  private final ProtectiveOrderExecutionService protectiveOrders;
  private final FundingSettlementProcessor fundingSettlement;
  private final LiquidationService liquidationService;
  private final JdbcTemplate jdbc;

  public DefaultValidationSystemStepOperations(
      ValidationMarketState marketState,
      ValidationMarketClock marketClock,
      ValidationFundingRateSeeder fundingRateSeeder,
      TrailingStopService trailingStops,
      PendingOrderExecutionService pendingOrders,
      ProtectiveOrderExecutionService protectiveOrders,
      FundingSettlementProcessor fundingSettlement,
      LiquidationService liquidationService,
      JdbcTemplate jdbc
  ) {
    this.marketState = marketState;
    this.marketClock = marketClock;
    this.fundingRateSeeder = fundingRateSeeder;
    this.trailingStops = trailingStops;
    this.pendingOrders = pendingOrders;
    this.protectiveOrders = protectiveOrders;
    this.fundingSettlement = fundingSettlement;
    this.liquidationService = liquidationService;
    this.jdbc = jdbc;
  }

  @Override
  public SubStepResult publishTick(Request request) {
    requireClockAt(request.tick());
    int fundingRates = fundingRateSeeder.persist(request.tick());
    marketState.publish(request.tick());
    return result(request, "publishTick", Map.of(
        "spotSymbols", request.tick().spotBundles().size(),
        "perpetualSymbols", request.tick().perpetualBundles().size(),
        "fundingRates", fundingRates));
  }

  @Override
  public void rehydratePublishedTick(Request request) {
    ValidationMarketState.CompositeTick requested = request.tick();
    ValidationMarketClock.Tick clock = requireClock();
    fundingRateSeeder.requireExactPersisted(requested);
    ValidationMarketState.CompositeTick current = marketState.current().orElse(null);
    if (current == null) {
      requireClockAt(clock, requested);
      if (requested.sequence() == 1L) {
        marketState.publish(requested);
      } else {
        marketState.restore(requested);
      }
      return;
    }

    requireSameRunAndGeneration(current, requested);
    if (current.sequence() == requested.sequence()) {
      if (!current.fingerprint().equals(requested.fingerprint())
          || !current.virtualTime().equals(requested.virtualTime())
          || !current.equals(requested)) {
        throw rehydrationConflict("Stored market Tick conflicts with the durable receipt");
      }
      requireClockAt(clock, current);
      return;
    }

    if (current.sequence() > requested.sequence()) {
      requireExpectedVirtualTime(requested, current);
      requireClockAt(clock, current);
      return;
    }

    if (requested.sequence() - current.sequence() == 1L) {
      requireExpectedVirtualTime(current, requested);
      requireClockAt(clock, requested);
      marketState.publish(requested);
      return;
    }
    throw rehydrationConflict("Market state cannot be advanced across a Tick sequence gap");
  }

  @Override
  public SubStepResult updateTrailingExtrema(Request request) {
    int updated = request.tick().perpetualBundles().stream()
        .map(ExecutableMarketSnapshot::from)
        .mapToInt(trailingStops::updateExtrema)
        .sum();
    return result(request, "updateTrailingExtrema", Map.of("updated", updated));
  }

  @Override
  public SubStepResult matchRestingOrders(Request request) {
    int executed = pendingOrders.executeRestingOrdersStrict();
    return result(request, "matchRestingOrders", Map.of("executed", executed));
  }

  @Override
  public SubStepResult triggerProtectionOrders(Request request) {
    int conditionals = pendingOrders.executeConditionalOrdersStrict();
    int trailing = request.tick().perpetualBundles().stream()
        .map(ExecutableMarketSnapshot::from)
        .mapToInt(trailingStops::triggerReadyStrict)
        .sum();
    int protections = protectiveOrders.executeProtectiveOrdersStrict();
    return result(request, "triggerProtectionOrders", Map.of(
        "conditionals", conditionals,
        "trailing", trailing,
        "protections", protections));
  }

  @Override
  public SubStepResult settlePersistedFunding(Request request) {
    int settled = fundingSettlement.settlePersistedDueRates(request.tick().virtualTime());
    return result(request, "settlePersistedFunding", Map.of("settled", settled));
  }

  @Override
  public SubStepResult scanLiquidations(Request request) {
    int liquidated = liquidationService.scanAllAccountsStrict();
    return result(request, "scanLiquidations", Map.of("liquidated", liquidated));
  }

  @Override
  public SubStepResult captureCheckpoint(Request request) {
    long orders = count("SELECT count(*) FROM trading.orders");
    long trades = count("SELECT count(*) FROM trading.trades");
    long openPositions = count(
        "SELECT count(*) FROM trading.positions WHERE status = 'OPEN'");
    return result(request, "captureCheckpoint", Map.of(
        "orders", orders,
        "trades", trades,
        "openPositions", openPositions));
  }

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0L : value;
  }

  private ValidationMarketClock.Tick requireClockAt(
      ValidationMarketState.CompositeTick tick
  ) {
    ValidationMarketClock.Tick clock = requireClock();
    requireClockAt(clock, tick);
    return clock;
  }

  private ValidationMarketClock.Tick requireClock() {
    return marketClock.current()
        .orElseThrow(() -> failure(
            "VALIDATION_CLOCK_NOT_STARTED",
            "Virtual clock has not been started"));
  }

  private static void requireClockAt(
      ValidationMarketClock.Tick clock,
      ValidationMarketState.CompositeTick tick
  ) {
    if (!clock.runId().equals(tick.runId())
        || clock.generation() != tick.generation()
        || clock.sequence() != tick.sequence()
        || !clock.virtualTime().equals(tick.virtualTime())) {
      throw failure(
          "VALIDATION_CLOCK_TICK_CONFLICT",
          "System-step Tick does not match the virtual clock");
    }
  }

  private static void requireSameRunAndGeneration(
      ValidationMarketState.CompositeTick current,
      ValidationMarketState.CompositeTick requested
  ) {
    if (!current.runId().equals(requested.runId())
        || current.generation() != requested.generation()) {
      throw rehydrationConflict("Market state belongs to another run or generation");
    }
  }

  private static void requireExpectedVirtualTime(
      ValidationMarketState.CompositeTick earlier,
      ValidationMarketState.CompositeTick later
  ) {
    long sequenceDelta = later.sequence() - earlier.sequence();
    Instant expected;
    try {
      expected = earlier.virtualTime().plusSeconds(sequenceDelta);
    } catch (DateTimeException | ArithmeticException invalidTimeline) {
      throw rehydrationConflict("Market state virtual time is outside the valid timeline");
    }
    if (sequenceDelta <= 0L || !expected.equals(later.virtualTime())) {
      throw rehydrationConflict("Market state virtual time does not match its Tick sequence");
    }
  }

  private static SubStepResult result(
      Request request,
      String name,
      Map<String, Object> details
  ) {
    return new SubStepResult(
        name,
        request.tick().runId() + ":" + request.tick().sequence() + ":" + name,
        details);
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  private static BusinessException rehydrationConflict(String message) {
    return failure("VALIDATION_MARKET_REHYDRATION_CONFLICT", message);
  }
}
