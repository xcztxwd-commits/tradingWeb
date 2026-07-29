package com.fxplatform.tradinglab.client;

import java.util.Objects;

public final class ValidationSupervisorClientException extends RuntimeException {

  private final Reason reason;
  private final String remoteCode;
  private final int httpStatus;

  public ValidationSupervisorClientException(
      Reason reason,
      String remoteCode,
      int httpStatus
  ) {
    super(reason == Reason.MUTATION_BUSY
        ? "Validation Supervisor mutation is busy"
        : "Validation Supervisor is unavailable");
    this.reason = Objects.requireNonNull(reason, "reason");
    this.remoteCode = safeCode(remoteCode);
    this.httpStatus = Math.max(httpStatus, 0);
  }

  public Reason reason() {
    return reason;
  }

  public String remoteCode() {
    return remoteCode;
  }

  public int httpStatus() {
    return httpStatus;
  }

  private static String safeCode(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.codePoints().allMatch(codePoint ->
            codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= '0' && codePoint <= '9'
                || codePoint == '_')) {
      return "SUPERVISOR_UNAVAILABLE";
    }
    return value;
  }

  public enum Reason {
    MUTATION_BUSY,
    UNAVAILABLE
  }
}
