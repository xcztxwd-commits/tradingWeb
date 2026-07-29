package com.fxplatform.validation.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface ValidationRunEventStore {

  int MAX_PAGE_SIZE = 200;
  String EVENT_CONFLICT_CODE = "VALIDATION_EVENT_CONFLICT";

  RunEvent append(EventWrite request);

  EventPage eventsAfter(UUID runId, long afterSequence, int limit);

  record EventWrite(
      UUID runId,
      String durableKey,
      String fingerprint,
      String type,
      Instant virtualTime,
      String correlationId,
      Map<String, Object> payload
  ) {

    public EventWrite {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(durableKey, "durableKey");
      Objects.requireNonNull(fingerprint, "fingerprint");
      Objects.requireNonNull(type, "type");
      virtualTime = ValidationInstantPrecision.normalize(virtualTime);
      payload = Map.copyOf(payload == null ? Map.of() : payload);
      if (durableKey.isBlank() || fingerprint.isBlank() || type.isBlank()) {
        throw new IllegalArgumentException("Event identity and type are required");
      }
    }
  }

  record RunEvent(
      UUID runId,
      long sequence,
      String durableKey,
      String fingerprint,
      String type,
      Instant virtualTime,
      String correlationId,
      Map<String, Object> payload
  ) {

    public RunEvent {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(durableKey, "durableKey");
      Objects.requireNonNull(fingerprint, "fingerprint");
      Objects.requireNonNull(type, "type");
      virtualTime = ValidationInstantPrecision.normalize(virtualTime);
      payload = Map.copyOf(payload == null ? Map.of() : payload);
      if (sequence < 1L) {
        throw new IllegalArgumentException("sequence must be positive");
      }
    }
  }

  record EventPage(
      List<RunEvent> events,
      long highWatermark,
      boolean terminal,
      boolean hasMore
  ) {

    public EventPage {
      events = List.copyOf(events == null ? List.of() : events);
      if (highWatermark < 0L) {
        throw new IllegalArgumentException("highWatermark must be non-negative");
      }
    }
  }
}
