package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("validation")
public class ValidationDemoExecutionPolicyProvider implements DemoExecutionPolicyProvider {

  private long generation = -1L;
  private FrozenPolicy frozen;

  public synchronized void reset(long generation) {
    if (generation < 0L) {
      throw new IllegalArgumentException("generation must be non-negative");
    }
    this.generation = generation;
    frozen = null;
  }

  public synchronized DemoExecutionPolicy freeze(
      UUID runId,
      long generation,
      String fingerprint,
      DemoExecutionPolicy policy
  ) {
    return install(runId, generation, fingerprint, policy);
  }

  public synchronized DemoExecutionPolicy restore(
      UUID runId,
      long generation,
      String fingerprint,
      DemoExecutionPolicy policy
  ) {
    return install(runId, generation, fingerprint, policy);
  }

  @Override
  public synchronized DemoExecutionPolicy current() {
    if (frozen == null) {
      throw failure("VALIDATION_POLICY_NOT_FROZEN", "Validation execution policy is not frozen");
    }
    return frozen.policy();
  }

  public synchronized long generation() {
    return generation;
  }

  private DemoExecutionPolicy install(
      UUID runId,
      long requestedGeneration,
      String fingerprint,
      DemoExecutionPolicy policy
  ) {
    requireGeneration(requestedGeneration);
    FrozenPolicy requested = new FrozenPolicy(runId, fingerprint, policy);
    if (frozen == null) {
      frozen = requested;
      return policy;
    }
    if (frozen.runId().equals(runId)
        && frozen.fingerprint().equals(fingerprint)
        && frozen.policy().equals(policy)) {
      return frozen.policy();
    }
    throw failure(
        "VALIDATION_POLICY_FROZEN_CONFLICT",
        "Validation execution policy is already frozen");
  }

  private void requireGeneration(long requestedGeneration) {
    if (requestedGeneration != generation) {
      throw failure("VALIDATION_GENERATION_FENCED", "Validation generation is no longer active");
    }
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  private record FrozenPolicy(
      UUID runId,
      String fingerprint,
      DemoExecutionPolicy policy
  ) {

    private FrozenPolicy {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(fingerprint, "fingerprint");
      Objects.requireNonNull(policy, "policy");
      if (fingerprint.isBlank()) {
        throw new IllegalArgumentException("fingerprint must not be blank");
      }
    }
  }
}
