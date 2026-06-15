package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminBatchOperationRequest;
import com.fxplatform.admin.dto.request.AdminExportTaskRequest;
import com.fxplatform.admin.dto.request.AdminImportTaskRequest;
import com.fxplatform.admin.dto.request.AdminTableColumnPreferenceRequest;
import com.fxplatform.admin.entity.AdminBatchOperationEntity;
import com.fxplatform.admin.entity.AdminExportTaskEntity;
import com.fxplatform.admin.entity.AdminImportTaskEntity;
import com.fxplatform.admin.entity.AdminTableColumnPreferenceEntity;
import com.fxplatform.admin.repository.AdminBatchOperationRepository;
import com.fxplatform.admin.repository.AdminExportTaskRepository;
import com.fxplatform.admin.repository.AdminImportTaskRepository;
import com.fxplatform.admin.repository.AdminTableColumnPreferenceRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminTableToolServiceTest {

  private final AdminTableColumnPreferenceRepository preferenceRepository = Mockito.mock(AdminTableColumnPreferenceRepository.class);
  private final AdminExportTaskRepository exportTaskRepository = Mockito.mock(AdminExportTaskRepository.class);
  private final AdminImportTaskRepository importTaskRepository = Mockito.mock(AdminImportTaskRepository.class);
  private final AdminBatchOperationRepository batchOperationRepository = Mockito.mock(AdminBatchOperationRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void savesPreferenceAndCreatesImportExportAndBatchTasks() {
    UUID actorUserId = UUID.randomUUID();
    UUID preferenceId = UUID.randomUUID();
    UUID exportId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();

    when(preferenceRepository.findByUserIdAndPageKey(actorUserId, "system-roles")).thenReturn(Optional.empty());
    when(preferenceRepository.save(any(AdminTableColumnPreferenceEntity.class))).thenAnswer(invocation -> {
      AdminTableColumnPreferenceEntity entity = invocation.getArgument(0);
      entity.setId(preferenceId);
      return entity;
    });
    when(exportTaskRepository.save(any(AdminExportTaskEntity.class))).thenAnswer(invocation -> {
      AdminExportTaskEntity entity = invocation.getArgument(0);
      entity.setId(exportId);
      return entity;
    });
    when(importTaskRepository.save(any(AdminImportTaskEntity.class))).thenAnswer(invocation -> {
      AdminImportTaskEntity entity = invocation.getArgument(0);
      entity.setId(importId);
      return entity;
    });
    when(batchOperationRepository.save(any(AdminBatchOperationEntity.class))).thenAnswer(invocation -> {
      AdminBatchOperationEntity entity = invocation.getArgument(0);
      entity.setId(batchId);
      return entity;
    });

    AdminTableToolService service = new AdminTableToolService(
        preferenceRepository,
        exportTaskRepository,
        importTaskRepository,
        batchOperationRepository,
        auditLogService);

    var preference = service.savePreference(actorUserId, "system-roles", new AdminTableColumnPreferenceRequest(
        List.of("createdAt"),
        "small",
        true,
        false));
    var exportTask = service.createExportTask(actorUserId, new AdminExportTaskRequest("system-roles", "{\"status\":\"enabled\"}"));
    var importTask = service.createImportTask(actorUserId, new AdminImportTaskRequest("system-roles", "roles.xlsx"));
    var batch = service.createBatchOperation(actorUserId, new AdminBatchOperationRequest(
        "system-roles",
        "delete",
        List.of("role-1", "role-2"),
        "批量删除"));

    assertThat(preference.id()).isEqualTo(preferenceId);
    assertThat(preference.hiddenColumns()).containsExactly("createdAt");
    assertThat(exportTask.status()).isEqualTo("QUEUED");
    assertThat(importTask.fileName()).isEqualTo("roles.xlsx");
    assertThat(batch.rowIds()).containsExactly("role-1", "role-2");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_TABLE_EXPORT_CREATE"), eq("ADMIN_EXPORT_TASK"), eq(exportId.toString()), any());
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_TABLE_BATCH_OPERATION_CREATE"), eq("ADMIN_BATCH_OPERATION"), eq(batchId.toString()), any());
  }
}
