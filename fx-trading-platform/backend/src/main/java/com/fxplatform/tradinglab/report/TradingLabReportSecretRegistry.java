package com.fxplatform.tradinglab.report;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded, report-lifetime registry for values removed from sensitive fields. */
final class TradingLabReportSecretRegistry {

  private static final int MAX_SECRETS = 256;
  private static final int MAX_SECRET_BYTES = 65_536;

  private final Set<String> secrets = new LinkedHashSet<>();
  private int secretBytes;
  private int maxCanaryPatternBytes;
  private long generation;

  void register(String secret) {
    if (secret == null || secret.isEmpty() || secrets.contains(secret)) {
      return;
    }
    registerAll(List.of(secret));
  }

  void registerAll(Iterable<String> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    Set<String> additions = new LinkedHashSet<>();
    int additionalBytes = 0;
    int nextMaxCanaryPatternBytes = maxCanaryPatternBytes;
    for (String secret : candidates) {
      if (secret == null
          || secret.isEmpty()
          || secrets.contains(secret)
          || !additions.add(secret)) {
        continue;
      }
      int remaining = MAX_SECRET_BYTES - secretBytes - additionalBytes;
      long bytes = TradingLabTraceBudget.utf8Length(secret, remaining);
      if (secrets.size() + additions.size() > MAX_SECRETS || bytes > remaining) {
        throw new RegistrationOverflowException();
      }
      additionalBytes += (int) bytes;
      nextMaxCanaryPatternBytes = Math.max(
          nextMaxCanaryPatternBytes,
          Math.max(
              (int) bytes,
              TradingLabCanonicalCanaryScanner.escapedBytes(secret).length));
    }
    if (additions.isEmpty()) {
      return;
    }
    secrets.addAll(additions);
    secretBytes += additionalBytes;
    maxCanaryPatternBytes = nextMaxCanaryPatternBytes;
    generation++;
  }

  List<String> values() {
    return List.copyOf(secrets);
  }

  long generation() {
    return generation;
  }

  TradingLabReportSecretRegistry stagingCopy() {
    TradingLabReportSecretRegistry copy = new TradingLabReportSecretRegistry();
    copy.secrets.addAll(secrets);
    copy.secretBytes = secretBytes;
    copy.maxCanaryPatternBytes = maxCanaryPatternBytes;
    copy.generation = generation;
    return copy;
  }

  int canaryTailBytes() {
    return Math.max(0, maxCanaryPatternBytes - 1);
  }

  static final class RegistrationOverflowException extends IllegalArgumentException {
    RegistrationOverflowException() {
      super("Unsafe Trading Lab report value");
    }
  }
}
