package com.fxplatform.tradinglab.client;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Coherent validation-generation observation. A successful observation with a null run is the
 * definitive-absence signal used before resending an uncertain start.
 */
public record ValidationRunStateObservation(
    String memoryResetState,
    long memoryGeneration,
    String durableResetState,
    long durableGeneration,
    Long redisGeneration,
    Long observedRedisGeneration,
    Long durableMemoryGeneration,
    boolean generationCoherent,
    ValidationRunStateSnapshot run,
    Map<String, Object> clock,
    Long marketTickSequence,
    boolean executionPolicyFrozen
) {

  public ValidationRunStateObservation {
    if (memoryResetState == null
        || memoryResetState.isBlank()
        || memoryResetState.length() > 64
        || durableResetState == null
        || durableResetState.isBlank()
        || durableResetState.length() > 64
        || durableGeneration < 0L
        || negative(redisGeneration)
        || negative(observedRedisGeneration)
        || negative(durableMemoryGeneration)
        || (generationCoherent
            && (durableGeneration <= 0L
                || memoryGeneration != durableGeneration
                || !Long.valueOf(durableGeneration).equals(redisGeneration)
                || !Long.valueOf(durableGeneration).equals(observedRedisGeneration)
                || !Long.valueOf(durableGeneration).equals(durableMemoryGeneration)))
        || (run != null
            && (!generationCoherent || run.generation() != durableGeneration))
        || (run == null
            && (clock != null || marketTickSequence != null || executionPolicyFrozen))
        || (marketTickSequence != null && marketTickSequence < 0L)
        || (executionPolicyFrozen && (clock == null || marketTickSequence == null))) {
      throw new IllegalArgumentException("Validation state observation is inconsistent");
    }
    clock = clock == null ? null : ValidationClientSafeValues.freezeMap(clock);
  }

  public boolean definitelyAbsent(UUID requestedRunId) {
    return requestedRunId != null && generationCoherent && run == null;
  }

  public boolean definitelyAbsent(ValidationRunStartRequest request) {
    return request != null
        && generationCoherent
        && durableGeneration == request.generation()
        && run == null;
  }

  public boolean matchesAcceptedIdentity(ValidationRunStartRequest request) {
    return generationCoherent
        && run != null
        && run.matchesAcceptedIdentity(request);
  }

  /**
   * Returns the generation that an INITIAL reset may fence, but only when the control plane is
   * authoritative and no requested run exists. Generation zero is authoritative only for the
   * pristine, never-reset validation environment.
   */
  public OptionalLong authoritativeResetBaseGeneration() {
    if (run != null) {
      return OptionalLong.empty();
    }
    if (generationCoherent) {
      return OptionalLong.of(durableGeneration);
    }
    boolean pristine = durableGeneration == 0L
        && memoryGeneration == 0L
        && "UNINITIALIZED".equals(memoryResetState)
        && "UNINITIALIZED".equals(durableResetState)
        && redisGeneration == null
        && Long.valueOf(0L).equals(observedRedisGeneration)
        && durableMemoryGeneration == null;
    return pristine ? OptionalLong.of(0L) : OptionalLong.empty();
  }

  private static boolean negative(Long value) {
    return value != null && value < 0L;
  }
}
