package com.fxplatform.trading.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.BatchActionRequestEntity;
import com.fxplatform.trading.repository.BatchActionRequestRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable account batch idempotency with an immutable UUID scope and strict response replay. */
@Service
public class BatchActionRequestService {

  public static final String ACTION_CANCEL_ALL = "CANCEL_ALL";
  public static final String ACTION_CLOSE_ALL = "CLOSE_ALL";

  private static final String PROCESSING = "PROCESSING";
  private static final String COMPLETED = "COMPLETED";
  private static final int MAX_ACTION_LENGTH = 64;
  private static final int MAX_REQUEST_ID_LENGTH = 256;
  private static final Duration OWNER_LEASE = Duration.ofMinutes(5);

  private final BatchActionRequestRepository repository;
  private final ObjectMapper objectMapper;
  private final TradingTransactionExecutor transactionExecutor;
  private final Clock clock;

  @Autowired
  public BatchActionRequestService(
      BatchActionRequestRepository repository,
      ObjectMapper objectMapper,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(repository, objectMapper, transactionExecutor, Clock.systemUTC());
  }

  public BatchActionRequestService(
      BatchActionRequestRepository repository,
      ObjectMapper objectMapper,
      TradingTransactionExecutor transactionExecutor,
      Clock clock
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.transactionExecutor = transactionExecutor;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Execution beginIndependent(
      UUID accountId,
      String actionType,
      String requestId,
      String fingerprintMaterial,
      Supplier<List<UUID>> scopeSupplier
  ) {
    return transactionExecutor.execute(() -> beginInternal(
        accountId,
        actionType,
        requestId,
        fingerprintMaterial,
        scopeSupplier));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Execution beginCurrent(
      UUID accountId,
      String actionType,
      String requestId,
      String fingerprintMaterial,
      List<UUID> candidateScope
  ) {
    return beginInternal(
        accountId,
        actionType,
        requestId,
        fingerprintMaterial,
        () -> candidateScope);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void completeIndependent(Execution execution, BatchActionResponse response) {
    transactionExecutor.execute(() -> {
      completeInternal(execution, response);
      return null;
    });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void completeCurrent(Execution execution, BatchActionResponse response) {
    completeInternal(execution, response);
  }

  /** Fences and renews one batch owner inside the actual item mutation transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void renewOwnershipCurrent(Execution execution) {
    if (execution == null || execution.id() == null || execution.ownerToken() == null) {
      throw ownershipLost("Batch execution has no durable owner");
    }
    Instant now = clock.instant();
    int renewed = repository.renewLease(
        execution.id(), execution.ownerToken(), now.plus(OWNER_LEASE), now);
    if (renewed != 1) {
      throw ownershipLost("Batch request ownership or lease changed before item mutation");
    }
  }

  private Execution beginInternal(
      UUID accountId,
      String rawActionType,
      String rawRequestId,
      String fingerprintMaterial,
      Supplier<List<UUID>> scopeSupplier
  ) {
    if (accountId == null) {
      throw conflict("Batch account id is required");
    }
    String actionType = requiredTrimmed(rawActionType, "Batch action type is required")
        .toUpperCase(Locale.ROOT);
    if (actionType.length() > MAX_ACTION_LENGTH) {
      throw conflict("Batch action type is too long");
    }
    String requestId = requiredTrimmed(rawRequestId, "Batch request id is required");
    if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
      throw conflict("Batch request id is too long");
    }
    String requestFingerprint = fingerprint(actionType, fingerprintMaterial);

    Instant now = clock.instant();
    var existing = repository.findForUpdate(accountId, actionType, requestId);
    if (existing.isPresent()) {
      return acquire(existing.get(), requestFingerprint, now);
    }
    if (scopeSupplier == null) {
      throw conflict("Batch scope supplier is required");
    }
    List<UUID> scopeIds = normalizedScope(scopeSupplier.get());
    UUID ownerToken = UUID.randomUUID();
    BatchActionRequestEntity candidate = new BatchActionRequestEntity();
    candidate.setId(UUID.randomUUID());
    candidate.setAccountId(accountId);
    candidate.setActionType(actionType);
    candidate.setRequestId(requestId);
    candidate.setRequestFingerprint(requestFingerprint);
    candidate.setOwnerToken(ownerToken);
    candidate.setLeaseUntil(now.plus(OWNER_LEASE));
    candidate.setStatus(PROCESSING);
    candidate.setScopeIds(writeScope(scopeIds));
    candidate.setResponsePayload(null);
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    int inserted = repository.insertIfAbsent(candidate);

    BatchActionRequestEntity frozen = repository.findForUpdate(accountId, actionType, requestId)
        .orElseThrow(() -> conflict("Batch request could not be frozen"));
    if (inserted == 1 && candidate.getId().equals(frozen.getId())) {
      return readExecution(frozen, requestFingerprint);
    }
    return acquire(frozen, requestFingerprint, now);
  }

  private void completeInternal(Execution execution, BatchActionResponse response) {
    if (execution == null || response == null) {
      throw conflict("Batch completion is incomplete");
    }
    requireResponseScope(execution, response);
    String responsePayload = writeResponse(response);
    if (execution.completedResponse() != null) {
      if (!execution.completedResponse().equals(response)) {
        throw conflict("Completed batch response cannot change");
      }
      return;
    }
    if (execution.ownerToken() == null) {
      throw ownershipLost("Batch execution has no owner token");
    }
    int updated = repository.markCompleted(
        execution.id(), execution.ownerToken(), responsePayload, clock.instant());
    if (updated == 1) {
      return;
    }
    BatchActionRequestEntity current = repository.findForUpdate(
            execution.accountId(), execution.actionType(), execution.requestId())
        .orElseThrow(() -> conflict("Batch request disappeared during completion"));
    if (!execution.ownerToken().equals(current.getOwnerToken())) {
      throw ownershipLost("Batch request ownership changed before completion");
    }
    if (PROCESSING.equals(current.getStatus())
        && !current.getLeaseUntil().isAfter(clock.instant())) {
      throw ownershipLost("Batch request lease expired before completion");
    }
    Execution completed = readExecution(current, current.getRequestFingerprint());
    if (completed.completedResponse() == null
        || !completed.completedResponse().equals(response)) {
      throw conflict("Batch request completed with a different response");
    }
  }

  private Execution acquire(
      BatchActionRequestEntity row,
      String expectedFingerprint,
      Instant now
  ) {
    Execution existing = readExecution(row, expectedFingerprint);
    if (existing.completedResponse() != null) {
      return existing;
    }
    if (existing.leaseUntil().isAfter(now)) {
      throw inProgress("Batch request is already owned by an active worker");
    }
    UUID newOwnerToken = UUID.randomUUID();
    Instant newLeaseUntil = now.plus(OWNER_LEASE);
    int claimed = repository.claimExpired(row.getId(), newOwnerToken, newLeaseUntil, now);
    if (claimed != 1) {
      throw inProgress("Batch request lease was claimed by another worker");
    }
    return new Execution(
        row.getId(),
        row.getAccountId(),
        row.getActionType(),
        row.getRequestId(),
        newOwnerToken,
        newLeaseUntil,
        existing.scopeIds(),
        null);
  }

  private Execution readExecution(
      BatchActionRequestEntity row,
      String expectedFingerprint
  ) {
    if (row == null
        || row.getId() == null
        || row.getAccountId() == null
        || row.getOwnerToken() == null
        || row.getLeaseUntil() == null
        || !Objects.equals(expectedFingerprint, row.getRequestFingerprint())) {
      throw conflict("Batch request fingerprint conflicts with the original request");
    }
    List<UUID> scopeIds = readScope(row.getScopeIds());
    if (PROCESSING.equals(row.getStatus())) {
      if (row.getResponsePayload() != null) {
        throw conflict("Processing batch request already has a response");
      }
      return new Execution(
          row.getId(),
          row.getAccountId(),
          row.getActionType(),
          row.getRequestId(),
          row.getOwnerToken(),
          row.getLeaseUntil(),
          scopeIds,
          null);
    }
    if (!COMPLETED.equals(row.getStatus()) || row.getResponsePayload() == null) {
      throw conflict("Batch request state is invalid");
    }
    BatchActionResponse response = readResponse(row.getResponsePayload());
    Execution completed = new Execution(
        row.getId(),
        row.getAccountId(),
        row.getActionType(),
        row.getRequestId(),
        row.getOwnerToken(),
        row.getLeaseUntil(),
        scopeIds,
        response);
    requireResponseScope(completed, response);
    if (!writeResponse(response).equals(row.getResponsePayload())) {
      throw conflict("Persisted batch response is not a strict canonical round-trip");
    }
    return completed;
  }

  private void requireResponseScope(Execution execution, BatchActionResponse response) {
    if (!execution.accountId().equals(response.accountId())
        || !execution.requestId().equals(response.requestId())) {
      throw conflict("Batch response does not match its request");
    }
    List<UUID> responseScope = new ArrayList<>(response.items().size());
    for (BatchActionResponse.Item item : response.items()) {
      UUID scopedId = ACTION_CLOSE_ALL.equals(execution.actionType())
          ? item.positionId()
          : item.orderId();
      if (scopedId == null) {
        throw conflict("Batch response item is missing its frozen scope id");
      }
      responseScope.add(scopedId);
    }
    List<UUID> normalizedResponseScope = normalizedScope(responseScope);
    if (normalizedResponseScope.size() != responseScope.size()
        || !normalizedResponseScope.equals(execution.scopeIds())) {
      throw conflict("Batch response does not cover the original frozen scope");
    }
  }

  private List<UUID> readScope(String payload) {
    try {
      List<UUID> decoded = objectMapper.readValue(
          payload,
          new TypeReference<List<UUID>>() {
          });
      List<UUID> normalized = normalizedScope(decoded);
      if (!writeScope(normalized).equals(payload)) {
        throw conflict("Persisted batch scope is not canonical");
      }
      return normalized;
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw conflict("Persisted batch scope cannot be decoded");
    }
  }

  private String writeScope(List<UUID> scopeIds) {
    try {
      return objectMapper.writeValueAsString(scopeIds);
    } catch (JsonProcessingException exception) {
      throw conflict("Batch scope cannot be encoded");
    }
  }

  private BatchActionResponse readResponse(String payload) {
    try {
      return objectMapper.readValue(payload, BatchActionResponse.class);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw conflict("Persisted batch response cannot be decoded");
    }
  }

  private String writeResponse(BatchActionResponse response) {
    try {
      String payload = objectMapper.writeValueAsString(response);
      BatchActionResponse roundTrip = objectMapper.readValue(payload, BatchActionResponse.class);
      if (!response.equals(roundTrip)) {
        throw conflict("Batch response failed strict round-trip validation");
      }
      return payload;
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw conflict("Batch response cannot be encoded");
    }
  }

  private static List<UUID> normalizedScope(List<UUID> scopeIds) {
    if (scopeIds == null) {
      throw conflict("Batch scope is required");
    }
    LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
    for (UUID scopeId : scopeIds) {
      if (scopeId == null) {
        throw conflict("Batch scope contains a missing id");
      }
      ordered.add(scopeId);
    }
    return List.copyOf(ordered);
  }

  private static String fingerprint(String actionType, String material) {
    String normalizedMaterial = requiredTrimmed(material, "Batch request fingerprint is required");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          (actionType + '|' + normalizedMaterial).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for batch request identity", exception);
    }
  }

  private static String requiredTrimmed(String value, String message) {
    if (value == null || value.isBlank()) {
      throw conflict(message);
    }
    return value.trim();
  }

  private static BusinessException conflict(String message) {
    return new BusinessException("BATCH_REQUEST_CONFLICT", message);
  }

  private static BusinessException inProgress(String message) {
    return new BusinessException("BATCH_REQUEST_IN_PROGRESS", message);
  }

  private static BusinessException ownershipLost(String message) {
    return new BusinessException("BATCH_REQUEST_OWNERSHIP_LOST", message);
  }

  public record Execution(
      UUID id,
      UUID accountId,
      String actionType,
      String requestId,
      UUID ownerToken,
      Instant leaseUntil,
      List<UUID> scopeIds,
      BatchActionResponse completedResponse
  ) {

    public Execution(
        UUID id,
        UUID accountId,
        String actionType,
        String requestId,
        List<UUID> scopeIds,
        BatchActionResponse completedResponse
    ) {
      this(
          id,
          accountId,
          actionType,
          requestId,
          UUID.randomUUID(),
          Instant.MAX,
          scopeIds,
          completedResponse);
    }

    public Execution {
      scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
    }
  }
}
