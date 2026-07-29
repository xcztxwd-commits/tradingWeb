package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("validation")
public class ValidationMarketClock {

  private long generation = -1L;
  private Tick current;

  public synchronized void reset(long generation) {
    if (generation < 0L) {
      throw new IllegalArgumentException("generation must be non-negative");
    }
    this.generation = generation;
    current = null;
  }

  public synchronized Tick start(UUID runId, long generation, Instant virtualStart) {
    requireGeneration(generation);
    Tick requested = new Tick(runId, generation, 0L, virtualStart);
    if (current == null) {
      current = requested;
      return current;
    }
    if (current.equals(requested)) {
      return current;
    }
    throw failure("VALIDATION_CLOCK_CONFLICT", "Virtual clock is already owned by another run");
  }

  public synchronized Tick advance(UUID runId, long generation) {
    requireGeneration(generation);
    if (current == null) {
      throw failure("VALIDATION_CLOCK_NOT_STARTED", "Virtual clock has not been started");
    }
    if (!current.runId().equals(runId)) {
      throw failure("VALIDATION_CLOCK_CONFLICT", "Virtual clock is owned by another run");
    }
    current = new Tick(
        runId,
        generation,
        Math.addExact(current.sequence(), 1L),
        current.virtualTime().plusSeconds(1L));
    return current;
  }

  public synchronized Tick restore(
      UUID runId,
      long generation,
      long sequence,
      Instant virtualTime
  ) {
    requireGeneration(generation);
    Tick restored = new Tick(runId, generation, sequence, virtualTime);
    if (current != null && !current.runId().equals(runId)) {
      throw failure("VALIDATION_CLOCK_CONFLICT", "Virtual clock is owned by another run");
    }
    current = restored;
    return current;
  }

  public synchronized Optional<Tick> current() {
    return Optional.ofNullable(current);
  }

  public synchronized long generation() {
    return generation;
  }

  private void requireGeneration(long requestedGeneration) {
    if (requestedGeneration != generation) {
      throw failure("VALIDATION_GENERATION_FENCED", "Validation generation is no longer active");
    }
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  public record Tick(UUID runId, long generation, long sequence, Instant virtualTime) {

    public Tick {
      Objects.requireNonNull(runId, "runId");
      virtualTime = ValidationInstantPrecision.require(virtualTime, "virtualTime");
      if (generation < 0L || sequence < 0L) {
        throw new IllegalArgumentException("generation and sequence must be non-negative");
      }
    }
  }
}
