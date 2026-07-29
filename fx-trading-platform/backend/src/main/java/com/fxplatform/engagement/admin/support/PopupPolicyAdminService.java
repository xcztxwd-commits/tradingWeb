package com.fxplatform.engagement.admin.support;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos.PopupPolicyResponse;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos.PopupPolicyUpdateRequest;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PopupPolicyAdminService {

  public static final String MAX_POPUPS_KEY =
      PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY;
  public static final String RETENTION_DAYS_KEY =
      "engagement.popup.deliveryRetentionDays";
  public static final int DEFAULT_MAX_POPUPS =
      PopupQueuePolicy.DEFAULT_MAX_SEQUENTIAL_POPUPS;
  public static final int DEFAULT_RETENTION_DAYS = 365;
  public static final int MAX_POPUPS = 100;
  public static final int MAX_RETENTION_DAYS = 3650;

  private final SystemSettingRepository repository;
  private final EngagementAuditService auditService;

  public PopupPolicyAdminService(
      SystemSettingRepository repository,
      EngagementAuditService auditService) {
    this.repository = Objects.requireNonNull(repository);
    this.auditService = Objects.requireNonNull(auditService);
  }

  @Transactional(readOnly = true)
  public PopupPolicyResponse policy() {
    int maxPopups = read(
        repository.findBySettingKey(MAX_POPUPS_KEY),
        MAX_POPUPS_KEY,
        DEFAULT_MAX_POPUPS,
        MAX_POPUPS);
    int retentionDays = read(
        repository.findBySettingKey(RETENTION_DAYS_KEY),
        RETENTION_DAYS_KEY,
        DEFAULT_RETENTION_DAYS,
        MAX_RETENTION_DAYS);
    return new PopupPolicyResponse(maxPopups, retentionDays);
  }

  public PopupPolicyResponse update(UUID actorId, PopupPolicyUpdateRequest request) {
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    PopupPolicyUpdateRequest command = requireRequest(request);

    Optional<SystemSettingEntity> maxSetting = repository.findBySettingKeyForUpdate(MAX_POPUPS_KEY);
    Optional<SystemSettingEntity> retentionSetting =
        repository.findBySettingKeyForUpdate(RETENTION_DAYS_KEY);
    int previousMax = read(maxSetting, MAX_POPUPS_KEY, DEFAULT_MAX_POPUPS, MAX_POPUPS);
    int previousRetention = read(
        retentionSetting,
        RETENTION_DAYS_KEY,
        DEFAULT_RETENTION_DAYS,
        MAX_RETENTION_DAYS);

    persist(
        maxSetting,
        MAX_POPUPS_KEY,
        command.maxSequentialPopups(),
        "Maximum popups issued by one queue session");
    persist(
        retentionSetting,
        RETENTION_DAYS_KEY,
        command.deliveryRetentionDays(),
        "Raw popup delivery retention in days");

    PopupPolicyResponse updated = new PopupPolicyResponse(
        command.maxSequentialPopups(), command.deliveryRetentionDays());
    auditService.record(
        actor,
        Action.POPUP_POLICY_UPDATE,
        TargetType.POPUP_POLICY,
        "engagement.popup",
        new Metadata(
            null,
            null,
            null,
            snapshot(previousMax, previousRetention),
            snapshot(updated.maxSequentialPopups(), updated.deliveryRetentionDays()),
            command.reason().trim()));
    return updated;
  }

  private void persist(
      Optional<SystemSettingEntity> existing,
      String key,
      int value,
      String description) {
    SystemSettingEntity setting = existing.orElseGet(SystemSettingEntity::new);
    boolean insert = setting.getId() == null;
    if (insert) {
      setting.setId(UUID.randomUUID());
    }
    setting.setSettingKey(key);
    setting.setSettingValue(Integer.toString(value));
    setting.setValueType("INTEGER");
    setting.setDescription(description);
    setting.setEditable(true);
    int rows = insert ? repository.insert(setting) : repository.updateById(setting);
    if (rows != 1) {
      throw invalid(key + " write did not affect one row");
    }
  }

  private static int read(
      Optional<SystemSettingEntity> setting,
      String key,
      int defaultValue,
      int maximum) {
    if (setting.isEmpty()) {
      return defaultValue;
    }
    String stored = setting.orElseThrow().getSettingValue();
    try {
      int value = Integer.parseInt(stored == null ? "" : stored.strip());
      if (value < 1 || value > maximum) {
        throw invalid(key + " must be between 1 and " + maximum);
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new BusinessException(
          "POPUP_POLICY_INVALID",
          key + " must be an integer between 1 and " + maximum,
          exception);
    }
  }

  private static PopupPolicyUpdateRequest requireRequest(PopupPolicyUpdateRequest request) {
    if (request == null
        || request.maxSequentialPopups() < 1
        || request.maxSequentialPopups() > MAX_POPUPS
        || request.deliveryRetentionDays() < 1
        || request.deliveryRetentionDays() > MAX_RETENTION_DAYS
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().trim().length() > 500) {
      throw invalid("Popup policy request is invalid");
    }
    return request;
  }

  private static String snapshot(int maxPopups, int retentionDays) {
    return "maxSequentialPopups=" + maxPopups
        + ",deliveryRetentionDays=" + retentionDays;
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("POPUP_POLICY_INVALID", message);
  }
}
