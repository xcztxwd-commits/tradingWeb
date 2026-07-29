package com.fxplatform.engagement.application.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.AggregateType;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.OutboxPayload;
import com.fxplatform.engagement.application.realtime.WakeupPort;
import com.fxplatform.engagement.application.realtime.WakeupPort.Wakeup;
import com.fxplatform.engagement.persistence.entity.EngagementOutboxEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.repository.EngagementOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
    prefix = "app.engagement.outbox",
    name = "enabled",
    havingValue = "true")
public class EngagementOutboxDispatcher {

  private static final int MAX_BATCH_SIZE = 100;

  private final EngagementOutboxRepository repository;
  private final WakeupPort wakeupPort;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public EngagementOutboxDispatcher(
      EngagementOutboxRepository repository,
      WakeupPort wakeupPort,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.wakeupPort = Objects.requireNonNull(wakeupPort);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.clock = Objects.requireNonNull(clock);
  }

  @Scheduled(fixedDelay = 1_000L)
  @Transactional
  public void dispatch() {
    dispatchBatch(MAX_BATCH_SIZE);
  }

  @Transactional
  public int dispatchBatch(int batchSize) {
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("Outbox batch size must be between 1 and 100");
    }
    List<EngagementOutboxEntity> events = repository.claimUnpublished(batchSize);
    for (EngagementOutboxEntity event : events) {
      dispatch(event);
    }
    return events.size();
  }

  private void dispatch(EngagementOutboxEntity event) {
    UUID eventId = Objects.requireNonNull(event.getId(), "outbox.id");
    try {
      OutboxPayload payload = payload(event);
      Set<UUID> targets = payload.audienceType() == AudienceType.SELECTED
          ? new LinkedHashSet<>(repository.findTargetUserIds(
              required(event.getAggregateType(), "aggregateType"), payload.aggregateId()))
          : Set.of();
      wakeupPort.publish(new Wakeup(
          payload.updateType().name(),
          payload.aggregateId(),
          Instant.parse(required(payload.occurredAt(), "payload.occurredAt")),
          payload.audienceType(),
          targets));
      requireSingleWrite(repository.markPublished(eventId, clock.instant()));
    } catch (RuntimeException | JsonProcessingException exception) {
      requireSingleWrite(repository.markFailed(eventId, exception.getClass().getSimpleName()));
    }
  }

  private OutboxPayload payload(EngagementOutboxEntity event) throws JsonProcessingException {
    OutboxPayload payload = objectMapper.readValue(
        required(event.getPayload(), "payload"), OutboxPayload.class);
    AggregateType aggregateType = AggregateType.valueOf(
        required(event.getAggregateType(), "aggregateType"));
    if (!payload.aggregateId().equals(event.getAggregateId())
        || !payload.updateType().name().equals(event.getEventType())
        || (aggregateType == AggregateType.MESSAGE
            && payload.updateType() != EngagementOutboxService.UpdateType.MESSAGE_UPDATED)
        || (aggregateType == AggregateType.POPUP_CAMPAIGN
            && payload.updateType() == EngagementOutboxService.UpdateType.MESSAGE_UPDATED)) {
      throw new IllegalStateException("Engagement outbox routing metadata is inconsistent");
    }
    return payload;
  }

  private static void requireSingleWrite(int rows) {
    if (rows != 1) {
      throw new IllegalStateException("Engagement outbox outcome write conflict");
    }
  }

  private static <T> T required(T value, String field) {
    return Objects.requireNonNull(value, "outbox." + field);
  }
}
