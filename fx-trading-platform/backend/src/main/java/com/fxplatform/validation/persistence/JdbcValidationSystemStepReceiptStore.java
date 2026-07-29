package com.fxplatform.validation.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationSystemStepReceiptStore;
import com.fxplatform.validation.service.ValidationSystemStepService.Receipt;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Immutable PostgreSQL receipt store for deterministic system phases. */
@Profile("validation")
@Repository
public class JdbcValidationSystemStepReceiptStore implements ValidationSystemStepReceiptStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final ValidationResetGate resetGate;

  public JdbcValidationSystemStepReceiptStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      ValidationResetGate resetGate
  ) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.resetGate = resetGate;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Receipt> lockTick(
      UUID runId,
      long generation,
      long tickSequence
  ) {
    resetGate.requireReadyGeneration(generation);
    Long runGeneration = jdbc.query(
            """
                SELECT generation
                  FROM validation_runtime.run_executions
                 WHERE id = ?
                 FOR UPDATE
                """,
            (resultSet, rowNumber) -> resultSet.getLong("generation"),
            runId)
        .stream()
        .findFirst()
        .orElseThrow(() -> failure("VALIDATION_RUN_NOT_FOUND", "Validation run was not found"));
    if (runGeneration != generation) {
      throw failure("VALIDATION_GENERATION_FENCED", "Validation run generation changed");
    }
    return findStored(runId, generation, tickSequence);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Receipt save(Receipt receipt) {
    resetGate.requireReadyGeneration(receipt.generation());
    jdbc.update(
        """
            INSERT INTO validation_runtime.system_step_receipts (
              run_id, generation, tick_sequence, phase, request_fingerprint,
              virtual_time, receipt_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """,
        receipt.runId(),
        receipt.generation(),
        receipt.tickSequence(),
        receipt.phase().name(),
        receipt.requestFingerprint(),
        Timestamp.from(receipt.virtualTime()),
        json(receipt));
    return receipt;
  }

  private List<Receipt> findStored(
      UUID runId,
      long generation,
      long tickSequence
  ) {
    return jdbc.query(
            """
                SELECT receipt_json
                  FROM validation_runtime.system_step_receipts
                 WHERE run_id = ? AND generation = ? AND tick_sequence = ?
                 ORDER BY phase
                """,
            (resultSet, rowNumber) -> receipt(resultSet.getString("receipt_json")),
            runId,
            generation,
            tickSequence);
  }

  private Receipt receipt(String value) {
    try {
      return objectMapper.readValue(value, Receipt.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored validation system-step receipt is invalid", exception);
    }
  }

  private String json(Receipt receipt) {
    try {
      return objectMapper.writeValueAsString(receipt);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Validation system-step receipt is not serializable",
          exception);
    }
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }
}
