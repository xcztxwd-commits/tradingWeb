package com.fxplatform.engagement.admin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos.PopupPolicyUpdateRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class PopupPolicyAdminServiceTest {

  private static final UUID ACTOR_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Mock private SystemSettingRepository repository;
  @Mock private EngagementAuditService auditService;

  private PopupPolicyAdminService service;

  @BeforeEach
  void setUp() {
    service = new PopupPolicyAdminService(repository, auditService);
  }

  @Test
  void controllerUsesTheDedicatedPathAndExactReadOrUpdatePermissions() {
    RequestMapping root = PopupPolicyAdminController.class.getAnnotation(RequestMapping.class);
    Method get = Arrays.stream(PopupPolicyAdminController.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(GetMapping.class))
        .findFirst()
        .orElseThrow();
    Method put = Arrays.stream(PopupPolicyAdminController.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(PutMapping.class))
        .findFirst()
        .orElseThrow();

    assertThat(root.value()).containsExactly("/api/admin/engagement/popup-policy");
    assertThat(get.getAnnotation(PreAuthorize.class).value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('"
            + AdminPermissionCatalog.CONTENT_CAMPAIGN_READ
            + "') or hasAuthority('"
            + AdminPermissionCatalog.CONTENT_POPUP_POLICY_UPDATE
            + "'))");
    assertThat(put.getAnnotation(PreAuthorize.class).value()).isEqualTo(
        "hasRole('ADMIN') and hasAuthority('"
            + AdminPermissionCatalog.CONTENT_POPUP_POLICY_UPDATE
            + "')");
    assertThat(put.getParameterTypes()[0]).isEqualTo(UserPrincipal.class);
    assertThat(PopupPolicyUpdateRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .doesNotContain("actorId", "actorUserId", "createdBy", "updatedBy");
  }

  @Test
  void missingSettingsReadTheFrozenDefaultsWithoutWriting() {
    when(repository.findBySettingKey(PopupPolicyAdminService.MAX_POPUPS_KEY))
        .thenReturn(Optional.empty());
    when(repository.findBySettingKey(PopupPolicyAdminService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.empty());

    var policy = service.policy();

    assertThat(policy.maxSequentialPopups()).isEqualTo(3);
    assertThat(policy.deliveryRetentionDays()).isEqualTo(365);
    verify(repository, never()).insert(any(SystemSettingEntity.class));
    verify(repository, never()).updateById(any(SystemSettingEntity.class));
    verifyNoInteractions(auditService);
  }

  @Test
  void invalidPersistedValueFailsClosedWithoutWriting() {
    when(repository.findBySettingKey(PopupPolicyAdminService.MAX_POPUPS_KEY))
        .thenReturn(Optional.of(setting(PopupPolicyAdminService.MAX_POPUPS_KEY, "101")));

    assertThatThrownBy(() -> service.policy())
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(PopupPolicyAdminService.MAX_POPUPS_KEY);
    verify(repository, never()).insert(any(SystemSettingEntity.class));
    verify(repository, never()).updateById(any(SystemSettingEntity.class));
    verifyNoInteractions(auditService);
  }

  @Test
  void updateLocksBothRowsWritesEachExactlyOnceThenAuditsExactlyOnce() {
    SystemSettingEntity max = setting(PopupPolicyAdminService.MAX_POPUPS_KEY, "3");
    SystemSettingEntity retention = setting(PopupPolicyAdminService.RETENTION_DAYS_KEY, "365");
    when(repository.findBySettingKeyForUpdate(PopupPolicyAdminService.MAX_POPUPS_KEY))
        .thenReturn(Optional.of(max));
    when(repository.findBySettingKeyForUpdate(PopupPolicyAdminService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.of(retention));
    when(repository.updateById(max)).thenReturn(1);
    when(repository.updateById(retention)).thenReturn(1);

    var updated = service.update(
        ACTOR_ID, new PopupPolicyUpdateRequest(5, 730, "policy reason"));

    assertThat(updated.maxSequentialPopups()).isEqualTo(5);
    assertThat(updated.deliveryRetentionDays()).isEqualTo(730);
    assertThat(max.getSettingValue()).isEqualTo("5");
    assertThat(retention.getSettingValue()).isEqualTo("730");
    InOrder order = inOrder(repository, auditService);
    order.verify(repository).findBySettingKeyForUpdate(PopupPolicyAdminService.MAX_POPUPS_KEY);
    order.verify(repository).findBySettingKeyForUpdate(PopupPolicyAdminService.RETENTION_DAYS_KEY);
    order.verify(repository).updateById(max);
    order.verify(repository).updateById(retention);
    order.verify(auditService).record(
        ACTOR_ID,
        Action.POPUP_POLICY_UPDATE,
        TargetType.POPUP_POLICY,
        "engagement.popup",
        new Metadata(
            null,
            null,
            null,
            "maxSequentialPopups=3,deliveryRetentionDays=365",
            "maxSequentialPopups=5,deliveryRetentionDays=730",
            "policy reason"));
  }

  @Test
  void invalidRequestOrWriteConflictProducesNoAudit() {
    assertThatThrownBy(() -> service.update(
        ACTOR_ID, new PopupPolicyUpdateRequest(0, 365, "invalid")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.update(
        ACTOR_ID, new PopupPolicyUpdateRequest(101, 365, "invalid")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.update(
        ACTOR_ID, new PopupPolicyUpdateRequest(3, 3651, "invalid")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(repository, auditService);

    SystemSettingEntity max = setting(PopupPolicyAdminService.MAX_POPUPS_KEY, "3");
    SystemSettingEntity retention = setting(PopupPolicyAdminService.RETENTION_DAYS_KEY, "365");
    when(repository.findBySettingKeyForUpdate(PopupPolicyAdminService.MAX_POPUPS_KEY))
        .thenReturn(Optional.of(max));
    when(repository.findBySettingKeyForUpdate(PopupPolicyAdminService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.of(retention));
    when(repository.updateById(max)).thenReturn(0);

    assertThatThrownBy(() -> service.update(
        ACTOR_ID, new PopupPolicyUpdateRequest(4, 400, "conflict")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("write");
    verify(repository, never()).updateById(retention);
    verifyNoInteractions(auditService);
  }

  @Test
  void repositoryLockAndServiceTransactionAreExplicit() throws Exception {
    Select lock = SystemSettingRepository.class
        .getMethod("findBySettingKeyForUpdate", String.class)
        .getAnnotation(Select.class);
    assertThat(String.join(" ", lock.value()).toUpperCase()).contains("FOR UPDATE");
    assertThat(PopupPolicyAdminService.class.getAnnotation(Transactional.class)).isNotNull();
  }

  private static SystemSettingEntity setting(String key, String value) {
    SystemSettingEntity setting = new SystemSettingEntity();
    setting.setId(UUID.randomUUID());
    setting.setSettingKey(key);
    setting.setSettingValue(value);
    setting.setValueType("INTEGER");
    setting.setEditable(true);
    return setting;
  }
}
