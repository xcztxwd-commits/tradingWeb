package com.fxplatform.engagement.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;

import cn.hutool.json.JSONUtil;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class EngagementAuditServiceTest {

  @Test
  void recordsOnlyTheTypedSafeMetadataInTheCallersTransaction() throws Exception {
    AuditLogService delegate = mock(AuditLogService.class);
    EngagementAuditService service = new EngagementAuditService(delegate);
    UUID actorId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();

    service.record(
        actorId,
        Action.CAMPAIGN_PUBLISH,
        TargetType.CAMPAIGN,
        campaignId.toString(),
        new Metadata(revisionId, AudienceType.SELECTED, 4, "DRAFT", "ACTIVE", "approved"));

    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    verify(delegate).record(
        eq(actorId),
        eq("ENGAGEMENT_CAMPAIGN_PUBLISH"),
        eq("CAMPAIGN"),
        eq(campaignId.toString()),
        detailsCaptor.capture());
    var details = JSONUtil.parseObj(detailsCaptor.getValue());
    assertThat(details.keySet()).containsExactlyInAnyOrder(
        "revisionId", "audienceType", "targetCount", "before", "after", "reason");
    assertThat(details.getStr("revisionId")).isEqualTo(revisionId.toString());
    assertThat(details.getStr("audienceType")).isEqualTo("SELECTED");
    assertThat(details.getInt("targetCount")).isEqualTo(4);
    assertThat(details.getStr("before")).isEqualTo("DRAFT");
    assertThat(details.getStr("after")).isEqualTo("ACTIVE");
    assertThat(details.getStr("reason")).isEqualTo("approved");

    Transactional transactional = EngagementAuditService.class
        .getMethod("record", UUID.class, Action.class, TargetType.class, String.class, Metadata.class)
        .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void everyDeclaredActionRecordsExactlyOnce(Action action) {
    AuditLogService delegate = mock(AuditLogService.class);
    EngagementAuditService service = new EngagementAuditService(delegate);

    service.record(
        UUID.randomUUID(),
        action,
        TargetType.CAMPAIGN,
        UUID.randomUUID().toString(),
        new Metadata(null, null, null, null, null, null));

    verify(delegate).record(
        org.mockito.ArgumentMatchers.any(),
        eq("ENGAGEMENT_" + action.name()),
        eq("CAMPAIGN"),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
    verifyNoMoreInteractions(delegate);
  }

  @Test
  void rejectedMetadataProducesNoAuditRow() {
    AuditLogService delegate = mock(AuditLogService.class);
    EngagementAuditService service = new EngagementAuditService(delegate);

    assertThatThrownBy(() -> service.record(
        UUID.randomUUID(), Action.CAMPAIGN_EDIT, TargetType.CAMPAIGN, "campaign", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("metadata");
    verifyNoInteractions(delegate);
  }
}
