package com.fxplatform.validation.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Process-local reset gate; the durable generation fence is supplied by validation control state. */
@Profile("validation")
@Component
public class ValidationResetGate {

  private final ValidationDatabaseFence durableFence;
  private State state = State.UNINITIALIZED;
  private long generation;
  private boolean invalidatingDurability;

  public ValidationResetGate() {
    this.durableFence = null;
  }

  @Autowired
  public ValidationResetGate(ValidationDatabaseFence durableFence) {
    this.durableFence = durableFence;
  }

  public synchronized boolean tryBeginReset() {
    if (invalidatingDurability || state == State.RESETTING || state == State.ADOPTING) {
      return false;
    }
    state = State.RESETTING;
    return true;
  }

  public synchronized boolean tryBeginAdoption(long targetGeneration) {
    if (invalidatingDurability
        || state == State.RESETTING
        || state == State.ADOPTING
        || targetGeneration <= generation) {
      return false;
    }
    state = State.ADOPTING;
    return true;
  }

  public synchronized void completeAdoption(long adoptedGeneration) {
    if (state != State.ADOPTING
        || adoptedGeneration <= generation
        || adoptedGeneration <= 0L) {
      throw new IllegalStateException("Validation generation adoption cannot publish readiness");
    }
    generation = adoptedGeneration;
    state = State.READY;
  }

  public synchronized void failAdoption() {
    if (state == State.ADOPTING) {
      state = State.FAILED;
    }
  }

  public synchronized void markReady(long readyGeneration) {
    if (state != State.RESETTING || readyGeneration <= 0) {
      throw new IllegalStateException("Validation reset cannot publish readiness");
    }
    generation = readyGeneration;
    state = State.READY;
  }

  /** Restores a previously durable READY generation during validation-backend startup. */
  public synchronized void hydrateReady(long readyGeneration) {
    if (state == State.RESETTING || state == State.ADOPTING || readyGeneration <= 0L) {
      throw new IllegalStateException("Validation reset readiness cannot be restored");
    }
    generation = readyGeneration;
    state = State.READY;
  }

  public synchronized void hydrateFailed() {
    if (state != State.RESETTING && state != State.ADOPTING) {
      state = State.FAILED;
    }
  }

  public synchronized void markFailed() {
    if (state == State.RESETTING) {
      state = State.FAILED;
    }
  }

  /** Atomically revokes a still-current READY generation while local work is drained. */
  public synchronized boolean tryBeginDurabilityFailure(long failedGeneration) {
    if (invalidatingDurability
        || state != State.READY
        || generation != failedGeneration
        || failedGeneration <= 0L) {
      return false;
    }
    invalidatingDurability = true;
    state = State.FAILED;
    return true;
  }

  public synchronized void completeDurabilityFailure(long failedGeneration) {
    if (!invalidatingDurability
        || state != State.FAILED
        || generation != failedGeneration) {
      throw new IllegalStateException("Validation durability failure changed concurrently");
    }
    invalidatingDurability = false;
  }

  @Transactional
  public void requireReadyGeneration(long requiredGeneration) {
    synchronized (this) {
      if (state != State.READY || generation != requiredGeneration) {
        throw new IllegalStateException("Validation generation is not ready");
      }
    }
    if (durableFence != null) {
      durableFence.requireReadyGeneration(requiredGeneration);
    }
  }

  public synchronized long readyGeneration() {
    if (state != State.READY || generation <= 0L) {
      throw new IllegalStateException("Validation generation is not ready");
    }
    return generation;
  }

  public synchronized State state() {
    return state;
  }

  public synchronized long generation() {
    return generation;
  }

  public enum State {
    UNINITIALIZED,
    RESETTING,
    ADOPTING,
    READY,
    FAILED
  }
}
