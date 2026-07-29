package com.fxplatform.validation.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Canonical precision for validation timestamps that cross a PostgreSQL durable boundary.
 *
 * <p>PostgreSQL {@code TIMESTAMPTZ} stores microseconds. Normalizing at the validation domain
 * boundary keeps the in-memory value byte-for-byte comparable with its later JDBC round trip.
 */
final class ValidationInstantPrecision {

  private ValidationInstantPrecision() {
  }

  static Instant normalize(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  static Instant require(Instant value, String name) {
    return normalize(Objects.requireNonNull(value, name));
  }
}
