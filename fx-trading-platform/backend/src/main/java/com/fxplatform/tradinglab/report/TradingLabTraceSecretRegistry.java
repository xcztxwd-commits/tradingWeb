package com.fxplatform.tradinglab.report;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Report-input secret registry; intentionally has no bean-style accessor. */
final class TradingLabTraceSecretRegistry {

  private static final int MAX_SECRETS = 256;
  private static final int MAX_SECRET_BYTES = 65_536;

  private final Set<String> secrets = new LinkedHashSet<>();
  private int secretBytes;

  void register(String secret) {
    if (secret == null || secret.isEmpty() || secrets.contains(secret)) {
      return;
    }
    int remaining = MAX_SECRET_BYTES - secretBytes;
    long bytes = TradingLabTraceBudget.utf8Length(secret, remaining);
    if (secrets.size() >= MAX_SECRETS || bytes > remaining) {
      throw new IllegalArgumentException("Unsafe Trading Lab HTTP trace secret registry");
    }
    secrets.add(secret);
    secretBytes += (int) bytes;
  }

  List<String> snapshot() {
    return List.copyOf(secrets);
  }

  void inherit(TradingLabTraceSecretRegistry source) {
    source.snapshot().forEach(this::register);
  }

  /** Preflights the complete union before mutating the report registry. */
  void mergeInto(TradingLabReportSecretRegistry reportSecrets) {
    try {
      reportSecrets.registerAll(secrets);
    } catch (TradingLabReportSecretRegistry.RegistrationOverflowException exception) {
      throw new MergeOverflowException();
    }
  }

  static final class MergeOverflowException extends IllegalArgumentException {
    MergeOverflowException() {
      super("Unsafe Trading Lab HTTP trace secret registry");
    }
  }
}
