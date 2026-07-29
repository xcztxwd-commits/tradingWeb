package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Stable identity and generation fence for one destructive validation reset. */
public record ValidationResetCommand(
    UUID runId,
    UUID operationId,
    Mode mode,
    long expectedGeneration
) {

  public static final String REQUIRED_ERROR = "VALIDATION_RESET_COMMAND_REQUIRED";
  public static final String INVALID_ERROR = "VALIDATION_RESET_COMMAND_INVALID";
  public static final String CONFLICT_ERROR = "VALIDATION_RESET_IDEMPOTENCY_CONFLICT";
  public static final String IN_PROGRESS_ERROR = "VALIDATION_RESET_OPERATION_IN_PROGRESS";
  public static final String STALE_GENERATION_ERROR = "VALIDATION_RESET_STALE_GENERATION";
  public static final String INITIAL_RUN_PRESENT_ERROR =
      "VALIDATION_RESET_INITIAL_RUN_PRESENT";
  public static final String FINAL_RUN_MISMATCH_ERROR =
      "VALIDATION_RESET_FINAL_RUN_MISMATCH";
  public static final String FINAL_RUN_NOT_TERMINAL_ERROR =
      "VALIDATION_RESET_FINAL_RUN_NOT_TERMINAL";

  public ValidationResetCommand {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(mode, "mode");
    if (expectedGeneration < 0L
        || expectedGeneration == Long.MAX_VALUE
        || (mode == Mode.FINAL && expectedGeneration == 0L)) {
      throw new IllegalArgumentException(
          "Validation reset mode and expected generation do not match");
    }
  }

  public static ValidationResetCommand parse(
      String runId,
      String operationId,
      String mode,
      String expectedGeneration
  ) {
    if (runId == null
        || operationId == null
        || mode == null
        || expectedGeneration == null) {
      throw rejected(REQUIRED_ERROR, "Validation reset identity headers are required");
    }
    try {
      UUID parsedRunId = parseCanonicalUuid(runId);
      UUID parsedOperationId = parseCanonicalUuid(operationId);
      Mode parsedMode = Mode.valueOf(mode);
      long parsedGeneration = Long.parseLong(expectedGeneration);
      if (!Long.toString(parsedGeneration).equals(expectedGeneration)) {
        throw new IllegalArgumentException("Expected generation is not canonical");
      }
      return new ValidationResetCommand(
          parsedRunId,
          parsedOperationId,
          parsedMode,
          parsedGeneration);
    } catch (IllegalArgumentException ignored) {
      throw rejected(INVALID_ERROR, "Validation reset identity headers are invalid");
    }
  }

  public String requestFingerprint() {
    String canonical = runId + "\n"
        + operationId + "\n"
        + mode.name() + "\n"
        + expectedGeneration;
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static BusinessException rejected(String code, String message) {
    return new BusinessException(code, message);
  }

  private static UUID parseCanonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("UUID is not canonical");
    }
    return parsed;
  }

  public enum Mode {
    INITIAL,
    FINAL
  }
}
