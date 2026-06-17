package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminDictionaryRequest;
import com.fxplatform.admin.dto.request.AdminSystemSettingRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.config.entity.SystemDictionaryEntity;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemDictionaryRepository;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.config.service.SensitiveSettingService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminConfigCommandServiceTest {

  @Mock
  private SystemDictionaryRepository dictionaryRepository;

  @Mock
  private SystemSettingRepository settingRepository;

  @Mock
  private AuditLogService auditLogService;

  private final SensitiveSettingService sensitiveSettingService =
      new SensitiveSettingService("unit-test-config-encryption-key-32chars");

  @Test
  void upsertDictionaryCreatesMissingItemAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    AdminDictionaryRequest request = new AdminDictionaryRequest(
        "member_status",
        "active",
        "Active",
        true,
        1,
        "User can trade");
    when(dictionaryRepository.findByGroupKeyAndItemKey("member_status", "active")).thenReturn(Optional.empty());
    when(dictionaryRepository.save(org.mockito.ArgumentMatchers.any(SystemDictionaryEntity.class)))
        .thenAnswer(invocation -> {
          SystemDictionaryEntity item = invocation.getArgument(0);
          item.setId(UUID.randomUUID());
          return item;
        });

    AdminConfigCommandService service = new AdminConfigCommandService(
        dictionaryRepository, settingRepository, auditLogService, sensitiveSettingService);

    var response = service.upsertDictionary(actorUserId, request);

    assertThat(response.groupKey()).isEqualTo("member_status");
    assertThat(response.itemKey()).isEqualTo("active");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_DICTIONARY_UPSERT"),
        eq("DICTIONARY"),
        eq(response.id().toString()),
        contains("member_status"));
  }

  @Test
  void updateSettingCreatesMissingSettingAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    AdminSystemSettingRequest request = new AdminSystemSettingRequest(
        "withdrawal.review.required",
        "true",
        "BOOLEAN",
        "Require manual review before withdrawal",
        true);
    when(settingRepository.findBySettingKey("withdrawal.review.required")).thenReturn(Optional.empty());
    when(settingRepository.save(org.mockito.ArgumentMatchers.any(SystemSettingEntity.class)))
        .thenAnswer(invocation -> {
          SystemSettingEntity setting = invocation.getArgument(0);
          setting.setId(UUID.randomUUID());
          return setting;
        });

    AdminConfigCommandService service = new AdminConfigCommandService(
        dictionaryRepository, settingRepository, auditLogService, sensitiveSettingService);

    var response = service.updateSetting(actorUserId, request);

    assertThat(response.settingKey()).isEqualTo("withdrawal.review.required");
    assertThat(response.settingValue()).isEqualTo("true");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_SETTING_UPDATE"),
        eq("SETTING"),
        eq(response.id().toString()),
        contains("withdrawal.review.required"));
  }

  @Test
  void updateSettingEncryptsSensitiveValueAndMasksResponseAndAuditDetails() {
    UUID actorUserId = UUID.randomUUID();
    AdminSystemSettingRequest request = new AdminSystemSettingRequest(
        "settings.smsPassword",
        "plain-secret",
        "STRING",
        "SMS gateway password",
        true);
    when(settingRepository.findBySettingKey("settings.smsPassword")).thenReturn(Optional.empty());
    when(settingRepository.save(org.mockito.ArgumentMatchers.any(SystemSettingEntity.class)))
        .thenAnswer(invocation -> {
          SystemSettingEntity setting = invocation.getArgument(0);
          setting.setId(UUID.randomUUID());
          return setting;
        });

    AdminConfigCommandService service = new AdminConfigCommandService(
        dictionaryRepository, settingRepository, auditLogService, sensitiveSettingService);

    var response = service.updateSetting(actorUserId, request);

    ArgumentCaptor<SystemSettingEntity> settingCaptor = ArgumentCaptor.forClass(SystemSettingEntity.class);
    verify(settingRepository).save(settingCaptor.capture());
    assertThat(settingCaptor.getValue().getSettingValue())
        .startsWith("enc:v1:")
        .doesNotContain("plain-secret");
    assertThat(response.settingValue()).isEqualTo("********");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_SETTING_UPDATE"),
        eq("SETTING"),
        eq(response.id().toString()),
        argThat(details -> details.contains("\"value\":\"********\"")
            && !details.contains("plain-secret")));
  }
}
