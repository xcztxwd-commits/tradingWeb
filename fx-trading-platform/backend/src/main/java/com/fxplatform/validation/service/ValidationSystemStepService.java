package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("validation")
public class ValidationSystemStepService {

  private final ValidationSystemStepOperations operations;
  private final ValidationSystemStepReceiptStore receiptStore;

  public ValidationSystemStepService(
      ValidationSystemStepOperations operations,
      ValidationSystemStepReceiptStore receiptStore
  ) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
  }

  @Transactional(rollbackFor = Exception.class)
  public Receipt execute(Request request) {
    Objects.requireNonNull(request, "request");
    CompositeTick tick = request.tick();
    List<Receipt> receipts = receiptStore.lockTick(
        tick.runId(), tick.generation(), tick.sequence());
    requireConsistentHistory(receipts);
    Receipt stored = receipts.stream()
        .filter(receipt -> receipt.phase() == request.phase())
        .findFirst()
        .orElse(null);
    if (stored != null) {
      Receipt replay = requireExactReplay(stored, request);
      if (request.phase() == Phase.PRE_ACTIONS || request.phase() == Phase.FULL) {
        operations.rehydratePublishedTick(request);
      }
      return replay;
    }
    requirePhaseAllowed(receipts, request.phase());

    List<SubStepResult> results = new ArrayList<>();
    if (request.phase() == Phase.PRE_ACTIONS || request.phase() == Phase.FULL) {
      results.add(operations.publishTick(request));
      results.add(operations.updateTrailingExtrema(request));
      results.add(operations.matchRestingOrders(request));
      results.add(operations.triggerProtectionOrders(request));
    }
    if (request.phase() == Phase.POST_ACTIONS || request.phase() == Phase.FULL) {
      results.add(operations.settlePersistedFunding(request));
      results.add(operations.scanLiquidations(request));
      results.add(operations.captureCheckpoint(request));
    }

    Receipt requested = new Receipt(
        tick.runId(),
        tick.generation(),
        tick.sequence(),
        request.phase(),
        request.requestFingerprint(),
        results,
        tick.virtualTime(),
        Instant.now());
    Receipt persisted = receiptStore.save(requested);
    return requireExactReplay(persisted, request);
  }

  private static void requireConsistentHistory(List<Receipt> receipts) {
    boolean hasPre = hasPhase(receipts, Phase.PRE_ACTIONS);
    boolean hasPost = hasPhase(receipts, Phase.POST_ACTIONS);
    boolean hasFull = hasPhase(receipts, Phase.FULL);
    if ((hasFull && (hasPre || hasPost)) || (hasPost && !hasPre)) {
      throw phaseConflict("Stored system-step phases violate deterministic ordering");
    }
  }

  private static void requirePhaseAllowed(List<Receipt> receipts, Phase requested) {
    boolean hasPre = hasPhase(receipts, Phase.PRE_ACTIONS);
    boolean hasFull = hasPhase(receipts, Phase.FULL);
    if (requested == Phase.FULL && !receipts.isEmpty()) {
      throw phaseConflict("FULL cannot be combined with PRE_ACTIONS or POST_ACTIONS");
    }
    if (requested != Phase.FULL && hasFull) {
      throw phaseConflict("PRE_ACTIONS or POST_ACTIONS cannot be combined with FULL");
    }
    if (requested == Phase.POST_ACTIONS && !hasPre) {
      throw phaseConflict("POST_ACTIONS requires a completed PRE_ACTIONS phase");
    }
  }

  private static boolean hasPhase(List<Receipt> receipts, Phase phase) {
    return receipts.stream().anyMatch(receipt -> receipt.phase() == phase);
  }

  private static BusinessException phaseConflict(String message) {
    return new BusinessException("VALIDATION_SYSTEM_STEP_PHASE_CONFLICT", message);
  }

  private static Receipt requireExactReplay(Receipt receipt, Request request) {
    CompositeTick tick = request.tick();
    if (!receipt.runId().equals(tick.runId())
        || receipt.generation() != tick.generation()
        || receipt.tickSequence() != tick.sequence()
        || receipt.phase() != request.phase()
        || !receipt.requestFingerprint().equals(request.requestFingerprint())
        || !receipt.virtualTime().equals(tick.virtualTime())) {
      throw new BusinessException(
          "VALIDATION_SYSTEM_STEP_CONFLICT",
          "System-step tuple already exists with another fingerprint");
    }
    return receipt;
  }

  public enum Phase {
    PRE_ACTIONS,
    POST_ACTIONS,
    FULL
  }

  public record Request(CompositeTick tick, Phase phase, String requestFingerprint) {

    public Request {
      Objects.requireNonNull(tick, "tick");
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      if (requestFingerprint.isBlank()) {
        throw new IllegalArgumentException("requestFingerprint must not be blank");
      }
    }
  }

  public record SubStepResult(
      String name,
      String correlationId,
      Map<String, Object> details
  ) {

    public SubStepResult {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(correlationId, "correlationId");
      details = Map.copyOf(details == null ? Map.of() : details);
      if (name.isBlank() || correlationId.isBlank()) {
        throw new IllegalArgumentException("name and correlationId must not be blank");
      }
    }
  }

  public record Receipt(
      UUID runId,
      long generation,
      long tickSequence,
      Phase phase,
      String requestFingerprint,
      List<SubStepResult> subSteps,
      Instant virtualTime,
      Instant completedAt
  ) {

    public Receipt {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      virtualTime = ValidationInstantPrecision.require(virtualTime, "virtualTime");
      completedAt = ValidationInstantPrecision.require(completedAt, "completedAt");
      subSteps = List.copyOf(subSteps == null ? List.of() : subSteps);
      if (generation < 0L || tickSequence < 1L || requestFingerprint.isBlank()) {
        throw new IllegalArgumentException("Receipt key and fingerprint are required");
      }
    }
  }
}
