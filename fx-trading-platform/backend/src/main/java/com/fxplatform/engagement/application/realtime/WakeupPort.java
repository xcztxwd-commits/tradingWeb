package com.fxplatform.engagement.application.realtime;

import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface WakeupPort {

  void publish(Wakeup wakeup);

  record Wakeup(
      String updateType,
      UUID aggregateId,
      Instant occurredAt,
      AudienceType audienceType,
      Set<UUID> targetUserIds) {

    public Wakeup {
      if (updateType == null || updateType.isBlank()) {
        throw new IllegalArgumentException("updateType is required");
      }
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(audienceType, "audienceType");
      targetUserIds = Set.copyOf(Objects.requireNonNull(targetUserIds, "targetUserIds"));
    }
  }
}
