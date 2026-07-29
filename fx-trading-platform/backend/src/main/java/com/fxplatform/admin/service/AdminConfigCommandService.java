package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminDictionaryRequest;
import com.fxplatform.admin.dto.request.AdminSystemSettingRequest;
import com.fxplatform.admin.dto.response.AdminDictionaryResponse;
import com.fxplatform.admin.dto.response.AdminSystemSettingResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.config.entity.SystemDictionaryEntity;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemDictionaryRepository;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.config.service.SensitiveSettingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminConfigCommandService 承载后台字典和系统设置写操作。
 */
@Service
@RequiredArgsConstructor
public class AdminConfigCommandService {

  private static final String MAX_POPUPS_KEY = "engagement.popup.maxSequentialPopups";
  private static final String DELIVERY_RETENTION_KEY = "engagement.popup.deliveryRetentionDays";

  /** 字典仓储，用于查询和保存系统字典项。 */
  private final SystemDictionaryRepository dictionaryRepository;
  /** 设置仓储，用于查询和保存系统设置。 */
  private final SystemSettingRepository settingRepository;
  /** 审计服务，用于记录配置变更。 */
  private final AuditLogService auditLogService;
  private final SensitiveSettingService sensitiveSettingService;

  /**
   * 按 groupKey + itemKey 创建或更新字典项。
   */
  @Transactional
  public AdminDictionaryResponse upsertDictionary(UUID actorUserId, AdminDictionaryRequest request) {
    SystemDictionaryEntity item = dictionaryRepository
        .findByGroupKeyAndItemKey(request.groupKey(), request.itemKey())
        .orElseGet(SystemDictionaryEntity::new);
    item.setGroupKey(request.groupKey());
    item.setItemKey(request.itemKey());
    item.setItemValue(request.itemValue());
    item.setEnabled(request.enabled());
    item.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
    item.setDescription(request.description());
    SystemDictionaryEntity saved = dictionaryRepository.save(item);
    auditLogService.record(
        actorUserId,
        "ADMIN_DICTIONARY_UPSERT",
        "DICTIONARY",
        saved.getId().toString(),
        details(request.groupKey(), request.itemKey()));
    return AdminDictionaryResponse.from(saved);
  }

  /**
   * 按 settingKey 创建或更新系统设置。
   */
  @Transactional
  public AdminSystemSettingResponse updateSetting(UUID actorUserId, AdminSystemSettingRequest request) {
    if (MAX_POPUPS_KEY.equals(request.settingKey())
        || DELIVERY_RETENTION_KEY.equals(request.settingKey())) {
      throw new BusinessException(
          "ENGAGEMENT_POLICY_SETTING_PROTECTED",
          "Engagement policy settings require the dedicated API");
    }
    SystemSettingEntity setting = settingRepository
        .findBySettingKey(request.settingKey())
        .orElseGet(SystemSettingEntity::new);
    String storedValue = sensitiveSettingService.storedValue(
        request.settingKey(),
        request.settingValue(),
        setting.getSettingValue());
    String responseValue = sensitiveSettingService.responseValue(request.settingKey(), storedValue);
    setting.setSettingKey(request.settingKey());
    setting.setSettingValue(storedValue);
    setting.setValueType(request.valueType());
    setting.setDescription(request.description());
    setting.setEditable(request.editable());
    SystemSettingEntity saved = settingRepository.save(setting);
    auditLogService.record(
        actorUserId,
        "ADMIN_SETTING_UPDATE",
        "SETTING",
        saved.getId().toString(),
        details(request.settingKey(), responseValue));
    return AdminSystemSettingResponse.from(saved, sensitiveSettingService);
  }

  /**
   * 构造审计 JSON 明细。
   */
  private String details(String key, String value) {
    return AuditDetailsBuilder.create()
        .put("key", key)
        .put("value", value)
        .toJson();
  }
}
