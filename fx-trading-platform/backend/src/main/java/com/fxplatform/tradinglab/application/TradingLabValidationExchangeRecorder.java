package com.fxplatform.tradinglab.application;

import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidence;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidenceStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Journals and reports every HTTP exchange before it is returned to lifecycle code. */
@Service
public class TradingLabValidationExchangeRecorder {

  private final TradingLabCoordinatorRunLoader runLoader;
  private final TradingLabCoordinatorEvidenceStore evidenceStore;
  private final TradingLabEvidenceReportProjector projector;

  public TradingLabValidationExchangeRecorder(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabCoordinatorEvidenceStore evidenceStore,
      TradingLabEvidenceReportProjector projector
  ) {
    this.runLoader = Objects.requireNonNull(runLoader, "runLoader");
    this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    this.projector = Objects.requireNonNull(projector, "projector");
  }

  public <T> ValidationBackendExchange<T> mutation(
      UUID runId,
      String claimOwner,
      String logicalKey,
      Map<String, Object> requestSummary,
      Supplier<ValidationBackendExchange<T>> exchange
  ) {
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        runId, claimOwner, logicalKey, requestSummary);
    projector.project(runId, claimOwner, intent);
    return executeAndRecord(runId, claimOwner, logicalKey, exchange);
  }

  public <T> ValidationBackendExchange<T> query(
      UUID runId,
      String claimOwner,
      Supplier<ValidationBackendExchange<T>> exchange
  ) {
    runLoader.requireLive(runId, claimOwner);
    ValidationBackendExchange<T> outcome = Objects.requireNonNull(
        exchange.get(), "validation exchange");
    String traceId = outcome.result().traceId();
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalArgumentException("Validation query traceId is required");
    }
    String logicalKey = "query:" + traceId;
    return recordResult(runId, claimOwner, logicalKey, outcome);
  }

  public <T> ValidationBackendExchange<T> mutationAttempt(
      UUID runId,
      String claimOwner,
      String intentKey,
      Map<String, Object> requestSummary,
      Supplier<ValidationBackendExchange<T>> exchange
  ) {
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        runId, claimOwner, intentKey, requestSummary);
    projector.project(runId, claimOwner, intent);
    runLoader.requireLive(runId, claimOwner);
    ValidationBackendExchange<T> outcome = Objects.requireNonNull(
        exchange.get(), "validation exchange");
    String traceId = outcome.result().traceId();
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalArgumentException("Validation mutation traceId is required");
    }
    return recordResult(runId, claimOwner, intentKey, outcome);
  }

  public Optional<ValidationHttpResult> previousResult(
      UUID runId,
      String claimOwner,
      String logicalKey
  ) {
    return evidenceStore.findHttpResult(runId, claimOwner, logicalKey)
        .map(TradingLabValidationExchangeRecorder::resultFrom);
  }

  private <T> ValidationBackendExchange<T> executeAndRecord(
      UUID runId,
      String claimOwner,
      String logicalKey,
      Supplier<ValidationBackendExchange<T>> exchange
  ) {
    runLoader.requireLive(runId, claimOwner);
    ValidationBackendExchange<T> outcome = Objects.requireNonNull(
        exchange.get(), "validation exchange");
    return recordResult(runId, claimOwner, logicalKey, outcome);
  }

  private <T> ValidationBackendExchange<T> recordResult(
      UUID runId,
      String claimOwner,
      String logicalKey,
      ValidationBackendExchange<T> outcome
  ) {
    runLoader.requireLive(runId, claimOwner);
    outcome.reportTrace().requireSafeEvidence(outcome.result().toSafeMap());
    TradingLabCoordinatorEvidence evidence = evidenceStore.appendHttpResult(
        runId, claimOwner, logicalKey, outcome.result(), outcome.reportTrace());
    projector.project(runId, claimOwner, evidence, outcome.reportTrace());
    ValidationHttpResult sequenced = outcome.result().withSequence(evidence.sequence());
    return new ValidationBackendExchange<>(
        outcome.data(),
        sequenced,
        outcome.reportTrace());
  }

  private static ValidationHttpResult resultFrom(TradingLabCoordinatorEvidence evidence) {
    Object raw = evidence.payload().get("evidence");
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("HTTP evidence payload is missing");
    }
    return ValidationHttpResult.fromSafeMap(map);
  }

}
