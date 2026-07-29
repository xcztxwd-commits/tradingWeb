package com.fxplatform.engagement.application.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.engagement.persistence.entity.EngagementOutboxEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.repository.EngagementOutboxRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EngagementOutboxService {

  private final EngagementOutboxRepository repository;
  private final ObjectMapper objectMapper;

  public EngagementOutboxService(
      EngagementOutboxRepository repository,
      ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  public UUID append(
      UpdateType updateType,
      AggregateType aggregateType,
      UUID aggregateId,
      AudienceType audienceType,
      Instant occurredAt) {
    UpdateType type = Objects.requireNonNull(updateType, "updateType");
    AggregateType aggregate = Objects.requireNonNull(aggregateType, "aggregateType");
    UUID id = Objects.requireNonNull(aggregateId, "aggregateId");
    AudienceType audience = Objects.requireNonNull(audienceType, "audienceType");
    Instant occurred = Objects.requireNonNull(occurredAt, "occurredAt");

    EngagementOutboxEntity event = new EngagementOutboxEntity();
    event.setId(UUID.randomUUID());
    event.setAggregateType(aggregate.name());
    event.setAggregateId(id);
    event.setEventType(type.name());
    event.setPayload(json(new OutboxPayload(type, id, occurred.toString(), audience)));
    event.setCreatedAt(occurred);
    event.setAttemptCount(0);
    if (repository.insert(event) != 1) {
      throw new IllegalStateException("Engagement outbox insert conflict");
    }
    return event.getId();
  }

  private String json(OutboxPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Engagement outbox payload is invalid", exception);
    }
  }

  public enum UpdateType {
    CAMPAIGN_UPDATED,
    CAMPAIGN_INVALIDATED,
    MESSAGE_UPDATED
  }

  public enum AggregateType {
    POPUP_CAMPAIGN,
    MESSAGE
  }

  record OutboxPayload(
      UpdateType updateType,
      UUID aggregateId,
      String occurredAt,
      AudienceType audienceType) {
  }
}
