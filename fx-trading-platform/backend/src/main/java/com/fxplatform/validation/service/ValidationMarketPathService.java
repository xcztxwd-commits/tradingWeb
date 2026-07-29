package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates and freezes one authoritative market path for the active reset generation. */
@Profile("validation")
@Service
public class ValidationMarketPathService {

  private static final int MAX_SYMBOLS_PER_PRODUCT = 128;

  private final ValidationResetGate resetGate;
  private final ValidationMarketClock marketClock;
  private final ValidationDemoExecutionPolicyProvider executionPolicyProvider;
  private final ValidationMarketState marketState;

  public ValidationMarketPathService(
      ValidationResetGate resetGate,
      ValidationMarketClock marketClock,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      ValidationMarketState marketState
  ) {
    this.resetGate = resetGate;
    this.marketClock = marketClock;
    this.executionPolicyProvider = executionPolicyProvider;
    this.marketState = marketState;
  }

  public synchronized PathReceipt start(PathRequest request) {
    Objects.requireNonNull(request, "request");
    resetGate.requireReadyGeneration(request.generation());
    validateTicks(request);
    marketClock.start(request.runId(), request.generation(), request.virtualStart());
    executionPolicyProvider.freeze(
        request.runId(),
        request.generation(),
        request.requestFingerprint(),
        request.executionPolicy());
    marketState.publish(request.ticks().getFirst());
    return new PathReceipt(
        request.runId(),
        request.generation(),
        request.requestFingerprint(),
        request.virtualStart(),
        request.ticks().size(),
        request.ticks().isEmpty() ? 0L : request.ticks().getLast().sequence());
  }

  static void validateTicks(PathRequest request) {
    if (request.ticks().isEmpty()) {
      throw failure(
          "VALIDATION_MARKET_PATH_EMPTY",
          "Validation market path must contain at least one complete Tick");
    }
    Set<String> expectedSpots = null;
    Set<String> expectedPerpetuals = null;
    for (int index = 0; index < request.ticks().size(); index++) {
      CompositeTick tick = request.ticks().get(index);
      ValidationMarketState.validateComplete(tick);
      long sequence = index + 1L;
      Instant expectedTime = request.virtualStart().plusSeconds(sequence);
      if (!request.runId().equals(tick.runId())
          || request.generation() != tick.generation()
          || tick.sequence() != sequence
          || !expectedTime.equals(tick.virtualTime())) {
        throw failure(
            "VALIDATION_MARKET_PATH_NOT_CONTIGUOUS",
            "Validation market path must use consecutive one-second Ticks");
      }
      Set<String> spotSymbols = tick.spotBundles().stream()
          .map(bundle -> bundle.platformSymbol())
          .collect(Collectors.toUnmodifiableSet());
      Set<String> perpetualSymbols = tick.perpetualBundles().stream()
          .map(bundle -> bundle.platformSymbol())
          .collect(Collectors.toUnmodifiableSet());
      if (spotSymbols.isEmpty() && perpetualSymbols.isEmpty()) {
        throw failure(
            "VALIDATION_MARKET_TICK_INCOMPLETE",
            "Every validation market Tick must contain the complete symbol set");
      }
      if (spotSymbols.size() > MAX_SYMBOLS_PER_PRODUCT
          || perpetualSymbols.size() > MAX_SYMBOLS_PER_PRODUCT) {
        throw failure(
            "VALIDATION_MARKET_SYMBOL_LIMIT",
            "Validation market path exceeds the fixed symbol bound");
      }
      if (expectedSpots == null) {
        expectedSpots = spotSymbols;
        expectedPerpetuals = perpetualSymbols;
      } else if (!expectedSpots.equals(spotSymbols)
          || !expectedPerpetuals.equals(perpetualSymbols)) {
        throw failure(
            "VALIDATION_MARKET_SYMBOL_SET_CHANGED",
            "Every validation market Tick must contain the same symbol set");
      }
    }
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  public record PathRequest(
      UUID runId,
      long generation,
      String requestFingerprint,
      Instant virtualStart,
      DemoExecutionPolicy executionPolicy,
      List<CompositeTick> ticks
  ) {

    public PathRequest {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      virtualStart = ValidationInstantPrecision.require(virtualStart, "virtualStart");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      ticks = List.copyOf(ticks == null ? List.of() : ticks);
      if (generation <= 0L || requestFingerprint.isBlank()
          || requestFingerprint.length() > 128) {
        throw new IllegalArgumentException("Run generation and request fingerprint are required");
      }
    }
  }

  public record PathReceipt(
      UUID runId,
      long generation,
      String requestFingerprint,
      Instant virtualStart,
      int tickCount,
      long finalTickSequence
  ) {

    public PathReceipt {
      virtualStart = ValidationInstantPrecision.require(virtualStart, "virtualStart");
    }
  }
}
