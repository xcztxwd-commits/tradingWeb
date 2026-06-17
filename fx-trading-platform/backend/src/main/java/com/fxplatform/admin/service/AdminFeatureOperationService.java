package com.fxplatform.admin.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.dto.request.AdminFeatureOperationRequest;
import com.fxplatform.admin.dto.response.AdminFeatureActionResponse;
import com.fxplatform.admin.dto.response.AdminFeatureOperationResponse;
import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import com.fxplatform.admin.enums.AdminFeatureRecordStatus;
import com.fxplatform.admin.entity.AdminFeatureRecordEntity;
import com.fxplatform.admin.repository.AdminFeatureRecordRepository;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行截图后台页面动作，并把通用页面数据真实落库。
 */
@Service
@RequiredArgsConstructor
public class AdminFeatureOperationService {

  private final AdminFeatureCatalogService catalogService;
  private final AdminFeatureRecordRepository recordRepository;
  private final AuditLogService auditLogService;
  private final List<AdminFeatureActionHandler> actionHandlers;

  /**
   * 返回带已保存记录的页面配置。
   */
  public AdminFeaturePageResponse page(String pageKey) {
    AdminFeaturePageResponse catalog = catalogService.page(pageKey);
    List<Map<String, Object>> savedRows = recordRepository.findActiveByPageKey(pageKey).stream()
        .map(this::rowFromRecord)
        .toList();
    if (savedRows.isEmpty()) {
      return catalog;
    }
    return new AdminFeaturePageResponse(
        catalog.key(),
        catalog.title(),
        catalog.group(),
        catalog.fields(),
        catalog.columns(),
        catalog.toolbarActions(),
        catalog.rowActions(),
        Stream.concat(savedRows.stream(), catalog.rows().stream()).toList());
  }

  /**
   * 执行页面动作：领域副作用由 handler 注册表处理，通用页面记录统一落库。
   */
  @Transactional
  public AdminFeatureOperationResponse performAction(
      UUID actorUserId,
      String pageKey,
      AdminFeatureOperationRequest request
  ) {
    AdminFeaturePageResponse page = catalogService.page(pageKey);
    AdminFeatureActionResponse action = findAction(page, request.action());
    Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
    requireAuthority(AdminPermissionCatalog.permissionForFeatureAction(pageKey, action.key(), payload).orElse(null));

    Optional<AdminFeatureRecordEntity> replay = findReplayRecord(pageKey, action.key(), request.rowId(), payload);
    if (replay.isPresent()) {
      return responseFromRecord(pageKey, page.title(), action, replay.get());
    }

    String domainTargetId = handleDomainAction(actorUserId, pageKey, action.key(), request.rowId(), payload);
    AdminFeatureRecordEntity record = persistFeatureRecord(
        actorUserId,
        pageKey,
        action.key(),
        request.rowId(),
        payload,
        domainTargetId);
    String targetId = domainTargetId == null ? record.getId().toString() : domainTargetId;

    String auditDetails = AuditDetailsBuilder.create()
        .put("pageKey", pageKey)
        .put("action", action.key())
        .put("rowId", request.rowId())
        .put("reason", request.reason())
        .toJson();

    auditLogService.record(
        actorUserId,
        "ADMIN_FEATURE_ACTION",
        "FEATURE_RECORD",
        targetId,
        auditDetails);

    return new AdminFeatureOperationResponse(
        pageKey,
        action.key(),
        targetId,
        true,
        page.title() + "已真实执行" + action.label() + "，记录：" + targetId);
  }

  private Optional<AdminFeatureRecordEntity> findReplayRecord(
      String pageKey,
      String action,
      String rowId,
      Map<String, Object> payload
  ) {
    String idempotencyKey = AdminFeaturePayloads.string(payload, "idempotencyKey", "");
    if (StrUtil.isBlank(idempotencyKey)) {
      return Optional.empty();
    }
    if (StrUtil.isNotBlank(rowId)) {
      return recordRepository.findByPageKeyAndRecordKey(pageKey, rowId)
          .filter(record -> matchesReplay(record, action, idempotencyKey));
    }
    return recordRepository.findByPageKeyAndActionAndIdempotencyKey(pageKey, action, idempotencyKey);
  }

  private boolean matchesReplay(AdminFeatureRecordEntity record, String action, String idempotencyKey) {
    if (record == null || StrUtil.isBlank(record.getData())) {
      return false;
    }
    var data = JSONUtil.parseObj(record.getData());
    return action.equals(data.getStr("_lastAction"))
        && idempotencyKey.equals(data.getStr("idempotencyKey"));
  }

  private AdminFeatureOperationResponse responseFromRecord(
      String pageKey,
      String pageTitle,
      AdminFeatureActionResponse action,
      AdminFeatureRecordEntity record
  ) {
    String targetId = null;
    if (StrUtil.isNotBlank(record.getData())) {
      targetId = JSONUtil.parseObj(record.getData()).getStr("_domainTargetId");
    }
    if (StrUtil.isBlank(targetId)) {
      targetId = record.getId() == null ? record.getRecordKey() : record.getId().toString();
    }
    return new AdminFeatureOperationResponse(
        pageKey,
        action.key(),
        targetId,
        true,
        pageTitle + " action replayed: " + targetId);
  }

  private String handleDomainAction(
      UUID actorUserId,
      String pageKey,
      String action,
      String rowId,
      Map<String, Object> payload
  ) {
    AdminFeatureActionContext context = new AdminFeatureActionContext(actorUserId, pageKey, action, rowId, payload);
    return actionHandlers.stream()
        .filter(handler -> handler.supports(pageKey, action))
        .map(handler -> handler.handle(context))
        .filter(StrUtil::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  private AdminFeatureRecordEntity persistFeatureRecord(
      UUID actorUserId,
      String pageKey,
      String action,
      String rowId,
      Map<String, Object> payload,
      String domainTargetId
  ) {
    String recordKey = StrUtil.blankToDefault(rowId, IdUtil.fastUUID());
    AdminFeatureRecordEntity record = recordRepository.findByPageKeyAndRecordKey(pageKey, recordKey)
        .orElseGet(AdminFeatureRecordEntity::new);
    boolean insert = record.getId() == null;
    record.setPageKey(pageKey);
    record.setRecordKey(recordKey);
    record.setData(JSONUtil.toJsonStr(enrichPayload(action, payload, domainTargetId)));
    record.setStatus(AdminFeatureRecordStatus.fromAction(action));
    if (insert) {
      record.setCreatedBy(actorUserId);
    }
    record.setUpdatedBy(actorUserId);
    return recordRepository.save(record);
  }

  private Map<String, Object> enrichPayload(String action, Map<String, Object> payload, String domainTargetId) {
    LinkedHashMap<String, Object> data = new LinkedHashMap<>(payload);
    data.put("_lastAction", action);
    if (StrUtil.isNotBlank(domainTargetId)) {
      data.put("_domainTargetId", domainTargetId);
    }
    return data;
  }

  private AdminFeatureActionResponse findAction(AdminFeaturePageResponse page, String actionKey) {
    return Stream.concat(page.toolbarActions().stream(), page.rowActions().stream())
        .filter(action -> action.key().equals(actionKey))
        .findFirst()
        .orElseThrow(() -> new BusinessException("ADMIN_ACTION_UNKNOWN", "Unknown admin action: " + actionKey));
  }

  private void requireAuthority(String authority) {
    if (authority == null || authority.isBlank()) {
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return;
    }
    boolean allowed = authentication.getAuthorities().stream()
        .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    if (!allowed) {
      throw new AccessDeniedException("Missing admin authority: " + authority);
    }
  }

  private Map<String, Object> rowFromRecord(AdminFeatureRecordEntity record) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", record.getRecordKey());
    row.putAll(JSONUtil.parseObj(record.getData()));
    row.put("_recordId", record.getId().toString());
    return row;
  }
}
