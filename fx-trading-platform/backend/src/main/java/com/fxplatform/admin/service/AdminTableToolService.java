package com.fxplatform.admin.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.dto.request.AdminBatchOperationRequest;
import com.fxplatform.admin.dto.request.AdminExportTaskRequest;
import com.fxplatform.admin.dto.request.AdminImportTaskRequest;
import com.fxplatform.admin.dto.request.AdminTableColumnPreferenceRequest;
import com.fxplatform.admin.dto.response.AdminBatchOperationResponse;
import com.fxplatform.admin.dto.response.AdminExportTaskResponse;
import com.fxplatform.admin.dto.response.AdminImportTaskResponse;
import com.fxplatform.admin.dto.response.AdminTableColumnPreferenceResponse;
import com.fxplatform.admin.entity.AdminBatchOperationEntity;
import com.fxplatform.admin.entity.AdminExportTaskEntity;
import com.fxplatform.admin.entity.AdminImportTaskEntity;
import com.fxplatform.admin.entity.AdminTableColumnPreferenceEntity;
import com.fxplatform.admin.enums.AdminTaskStatus;
import com.fxplatform.admin.repository.AdminBatchOperationRepository;
import com.fxplatform.admin.repository.AdminExportTaskRepository;
import com.fxplatform.admin.repository.AdminImportTaskRepository;
import com.fxplatform.admin.repository.AdminTableColumnPreferenceRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminTableToolService 承载后台表格列设置、导入、导出和批量任务。
 */
@Service
@RequiredArgsConstructor
public class AdminTableToolService {

  private final AdminTableColumnPreferenceRepository preferenceRepository;
  private final AdminExportTaskRepository exportTaskRepository;
  private final AdminImportTaskRepository importTaskRepository;
  private final AdminBatchOperationRepository batchOperationRepository;
  private final AuditLogService auditLogService;

  /** 查询当前管理员在某个页面的表格偏好。 */
  public AdminTableColumnPreferenceResponse preference(UUID actorUserId, String pageKey) {
    return preferenceRepository.findByUserIdAndPageKey(actorUserId, pageKey)
        .map(AdminTableColumnPreferenceResponse::from)
        .orElse(null);
  }

  /** 保存列隐藏、表格尺寸、边框和斑马纹设置。 */
  @Transactional
  public AdminTableColumnPreferenceResponse savePreference(
      UUID actorUserId,
      String pageKey,
      AdminTableColumnPreferenceRequest request
  ) {
    AdminTableColumnPreferenceEntity preference = preferenceRepository.findByUserIdAndPageKey(actorUserId, pageKey)
        .orElseGet(AdminTableColumnPreferenceEntity::new);
    preference.setUserId(actorUserId);
    preference.setPageKey(pageKey);
    preference.setHiddenColumns(JSONUtil.toJsonStr(CollUtil.emptyIfNull(request.hiddenColumns())));
    preference.setTableSize(StrUtil.blankToDefault(request.tableSize(), "large"));
    preference.setShowBorder(Boolean.TRUE.equals(request.showBorder()));
    preference.setZebra(request.zebra() == null || Boolean.TRUE.equals(request.zebra()));
    return AdminTableColumnPreferenceResponse.from(preferenceRepository.save(preference));
  }

  /** 创建导出任务，后续可由异步 worker 生成真实文件。 */
  @Transactional
  public AdminExportTaskResponse createExportTask(UUID actorUserId, AdminExportTaskRequest request) {
    AdminExportTaskEntity task = new AdminExportTaskEntity();
    task.setPageKey(request.pageKey());
    task.setStatus(AdminTaskStatus.QUEUED);
    task.setFilterJson(StrUtil.blankToDefault(request.filterJson(), "{}"));
    task.setCreatedBy(actorUserId);
    AdminExportTaskEntity saved = exportTaskRepository.save(task);
    audit(actorUserId, "ADMIN_TABLE_EXPORT_CREATE", "ADMIN_EXPORT_TASK", saved.getId(), request.pageKey());
    return AdminExportTaskResponse.from(saved);
  }

  /** 创建导入任务，当前记录文件名和队列状态。 */
  @Transactional
  public AdminImportTaskResponse createImportTask(UUID actorUserId, AdminImportTaskRequest request) {
    AdminImportTaskEntity task = new AdminImportTaskEntity();
    task.setPageKey(request.pageKey());
    task.setStatus(AdminTaskStatus.QUEUED);
    task.setFileName(request.fileName());
    task.setTotalRows(0);
    task.setSuccessRows(0);
    task.setFailedRows(0);
    task.setCreatedBy(actorUserId);
    AdminImportTaskEntity saved = importTaskRepository.save(task);
    audit(actorUserId, "ADMIN_TABLE_IMPORT_CREATE", "ADMIN_IMPORT_TASK", saved.getId(), request.pageKey());
    return AdminImportTaskResponse.from(saved);
  }

  /** 创建批量操作任务，记录目标行和操作原因。 */
  @Transactional
  public AdminBatchOperationResponse createBatchOperation(UUID actorUserId, AdminBatchOperationRequest request) {
    AdminBatchOperationEntity task = new AdminBatchOperationEntity();
    task.setPageKey(request.pageKey());
    task.setOperation(request.operation());
    task.setRowIds(JSONUtil.toJsonStr(CollUtil.emptyIfNull(request.rowIds())));
    task.setReason(request.reason());
    task.setStatus(AdminTaskStatus.QUEUED);
    task.setCreatedBy(actorUserId);
    AdminBatchOperationEntity saved = batchOperationRepository.save(task);
    audit(actorUserId, "ADMIN_TABLE_BATCH_OPERATION_CREATE", "ADMIN_BATCH_OPERATION", saved.getId(), request.operation());
    return AdminBatchOperationResponse.from(saved);
  }

  private void audit(UUID actorUserId, String action, String targetType, UUID targetId, String value) {
    auditLogService.record(actorUserId, action, targetType, targetId.toString(),
        JSONUtil.toJsonStr(MapUtil.builder().put("value", value).build()));
  }
}
