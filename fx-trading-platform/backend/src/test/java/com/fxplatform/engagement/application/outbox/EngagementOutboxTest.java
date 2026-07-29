package com.fxplatform.engagement.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.AggregateType;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.UpdateType;
import com.fxplatform.engagement.application.realtime.WakeupPort;
import com.fxplatform.engagement.application.realtime.WakeupPort.Wakeup;
import com.fxplatform.engagement.persistence.entity.EngagementOutboxEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.repository.EngagementOutboxRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class EngagementOutboxTest {

  private static final Instant NOW = Instant.parse("2026-07-20T04:00:00Z");
  private static final UUID AGGREGATE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID TARGET_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");

  @Mock private EngagementOutboxRepository repository;
  @Mock private WakeupPort wakeupPort;

  private ObjectMapper objectMapper;
  private EngagementOutboxService service;
  private EngagementOutboxDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    service = new EngagementOutboxService(repository, objectMapper);
    dispatcher = new EngagementOutboxDispatcher(
        repository, wakeupPort, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void appendPersistsOnlySafeRoutingMetadataInsideTheDomainTransaction() throws Exception {
    when(repository.insert(any(EngagementOutboxEntity.class))).thenReturn(1);

    UUID eventId = service.append(
        UpdateType.CAMPAIGN_UPDATED,
        AggregateType.POPUP_CAMPAIGN,
        AGGREGATE_ID,
        AudienceType.SELECTED,
        NOW);

    ArgumentCaptor<EngagementOutboxEntity> captor =
        ArgumentCaptor.forClass(EngagementOutboxEntity.class);
    verify(repository).insert(captor.capture());
    EngagementOutboxEntity event = captor.getValue();
    JsonNode payload = objectMapper.readTree(event.getPayload());

    assertThat(eventId).isEqualTo(event.getId());
    assertThat(event.getAggregateType()).isEqualTo("POPUP_CAMPAIGN");
    assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
    assertThat(event.getEventType()).isEqualTo("CAMPAIGN_UPDATED");
    assertThat(event.getCreatedAt()).isEqualTo(NOW);
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttemptCount()).isZero();
    assertThat(event.getLastError()).isNull();
    assertThat(payload.properties().stream().map(java.util.Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder(
            "updateType", "aggregateId", "occurredAt", "audienceType");
    assertThat(payload.path("updateType").asText()).isEqualTo("CAMPAIGN_UPDATED");
    assertThat(payload.path("aggregateId").asText()).isEqualTo(AGGREGATE_ID.toString());
    assertThat(payload.path("occurredAt").asText()).isEqualTo(NOW.toString());
    assertThat(payload.path("audienceType").asText()).isEqualTo("SELECTED");
    assertThat(event.getPayload().toLowerCase())
        .doesNotContain("body", "content", "token", "password", "credential", "hash");

    Transactional transaction = EngagementOutboxService.class.getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void claimUsesCanonicalUnpublishedOrderAndSkipLocked() throws Exception {
    Select claim = EngagementOutboxRepository.class
        .getMethod("claimUnpublished", int.class)
        .getAnnotation(Select.class);
    Update published = EngagementOutboxRepository.class
        .getMethod("markPublished", UUID.class, Instant.class)
        .getAnnotation(Update.class);
    Update failed = EngagementOutboxRepository.class
        .getMethod("markFailed", UUID.class, String.class)
        .getAnnotation(Update.class);

    String claimSql = sql(claim.value());
    assertThat(claimSql).contains(
        "WHERE PUBLISHED_AT IS NULL",
        "ORDER BY CREATED_AT ASC, ID ASC",
        "LIMIT #{LIMIT}",
        "FOR UPDATE SKIP LOCKED");
    assertThat(sql(published.value())).contains(
        "SET PUBLISHED_AT = #{PUBLISHEDAT}",
        "WHERE ID = #{ID}",
        "AND PUBLISHED_AT IS NULL");
    assertThat(sql(failed.value())).contains(
        "ATTEMPT_COUNT = ATTEMPT_COUNT + 1",
        "LAST_ERROR = #{LASTERROR}",
        "AND PUBLISHED_AT IS NULL");
  }

  @Test
  void successfulDispatchMarksPublishedAndASecondPassCannotRepeatTheWakeup() {
    EngagementOutboxEntity event = event(AudienceType.SELECTED);
    when(repository.claimUnpublished(10)).thenReturn(List.of(event), List.of());
    when(repository.findTargetUserIds("POPUP_CAMPAIGN", AGGREGATE_ID))
        .thenReturn(List.of(TARGET_ID, TARGET_ID));
    when(repository.markPublished(event.getId(), NOW)).thenReturn(1);

    assertThat(dispatcher.dispatchBatch(10)).isEqualTo(1);
    assertThat(dispatcher.dispatchBatch(10)).isZero();

    ArgumentCaptor<Wakeup> captor = ArgumentCaptor.forClass(Wakeup.class);
    verify(wakeupPort).publish(captor.capture());
    Wakeup wakeup = captor.getValue();
    assertThat(wakeup.updateType()).isEqualTo("CAMPAIGN_UPDATED");
    assertThat(wakeup.aggregateId()).isEqualTo(AGGREGATE_ID);
    assertThat(wakeup.occurredAt()).isEqualTo(NOW.minusSeconds(1));
    assertThat(wakeup.audienceType()).isEqualTo(AudienceType.SELECTED);
    assertThat(wakeup.targetUserIds()).isEqualTo(Set.of(TARGET_ID));
    verify(repository).markPublished(event.getId(), NOW);
    verify(repository, never()).markFailed(any(UUID.class), any(String.class));
    verify(repository, times(2)).claimUnpublished(10);
  }

  @Test
  void failedDispatchIncrementsAttemptAndStoresOnlyTheFailureType() {
    EngagementOutboxEntity event = event(AudienceType.ALL);
    when(repository.claimUnpublished(5)).thenReturn(List.of(event));
    when(repository.markFailed(event.getId(), "IllegalStateException")).thenReturn(1);
    org.mockito.Mockito.doThrow(new IllegalStateException("token=must-not-be-persisted"))
        .when(wakeupPort).publish(any(Wakeup.class));

    assertThat(dispatcher.dispatchBatch(5)).isEqualTo(1);

    verify(repository).markFailed(event.getId(), "IllegalStateException");
    verify(repository, never()).markPublished(any(UUID.class), any(Instant.class));
    verify(repository, never()).findTargetUserIds(any(String.class), any(UUID.class));
  }

  @Test
  void invalidBatchSizeFailsBeforeClaimingRows() {
    assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.dispatchBatch(0));
    assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.dispatchBatch(101));
    verifyNoInteractions(repository, wakeupPort);
  }

  @Test
  void dispatcherKeepsClaimAndOutcomeWritesInOneTransaction() throws Exception {
    Method dispatch = EngagementOutboxDispatcher.class.getMethod("dispatchBatch", int.class);
    assertThat(dispatch.getAnnotation(Transactional.class)).isNotNull();

    Method scheduled = EngagementOutboxDispatcher.class.getMethod("dispatch");
    assertThat(scheduled.getAnnotation(Transactional.class)).isNotNull();
    assertThat(scheduled.getAnnotation(Scheduled.class)).isNotNull();
    assertThat(scheduled.getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(1_000L);
  }

  private EngagementOutboxEntity event(AudienceType audienceType) {
    EngagementOutboxEntity event = new EngagementOutboxEntity();
    event.setId(UUID.fromString("30000000-0000-0000-0000-000000000003"));
    event.setAggregateType("POPUP_CAMPAIGN");
    event.setAggregateId(AGGREGATE_ID);
    event.setEventType("CAMPAIGN_UPDATED");
    event.setPayload("{\"updateType\":\"CAMPAIGN_UPDATED\","
        + "\"aggregateId\":\"" + AGGREGATE_ID + "\","
        + "\"occurredAt\":\"" + NOW.minusSeconds(1) + "\","
        + "\"audienceType\":\"" + audienceType + "\"}");
    event.setCreatedAt(NOW.minusSeconds(1));
    event.setAttemptCount(0);
    return event;
  }

  private static String sql(String[] fragments) {
    return String.join(" ", fragments).replaceAll("\\s+", " ").trim().toUpperCase();
  }
}
