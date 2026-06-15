package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminBatchOperationRequest;
import com.fxplatform.admin.dto.request.AdminExportTaskRequest;
import com.fxplatform.admin.dto.request.AdminImportTaskRequest;
import com.fxplatform.admin.dto.request.AdminTableColumnPreferenceRequest;
import com.fxplatform.admin.dto.response.AdminBatchOperationResponse;
import com.fxplatform.admin.dto.response.AdminExportTaskResponse;
import com.fxplatform.admin.dto.response.AdminImportTaskResponse;
import com.fxplatform.admin.dto.response.AdminTableColumnPreferenceResponse;
import com.fxplatform.admin.service.AdminTableToolService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminTableToolController 提供表格列设置、导入、导出和批量操作 API。
 */
@RestController
@RequestMapping("/api/admin/table-tools")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTableToolController {

  private final AdminTableToolService adminTableToolService;

  /** 查询当前管理员的页面表格偏好。 */
  @GetMapping("/preferences/{pageKey}")
  public ApiResponse<AdminTableColumnPreferenceResponse> preference(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String pageKey
  ) {
    return ApiResponse.success(adminTableToolService.preference(principal.id(), pageKey));
  }

  /** 保存当前管理员的页面表格偏好。 */
  @PutMapping("/preferences/{pageKey}")
  public ApiResponse<AdminTableColumnPreferenceResponse> savePreference(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String pageKey,
      @Valid @RequestBody AdminTableColumnPreferenceRequest request
  ) {
    return ApiResponse.success(adminTableToolService.savePreference(principal.id(), pageKey, request));
  }

  /** 创建导出任务。 */
  @PostMapping("/export-tasks")
  public ApiResponse<AdminExportTaskResponse> createExportTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminExportTaskRequest request
  ) {
    return ApiResponse.success(adminTableToolService.createExportTask(principal.id(), request));
  }

  /** 创建导入任务。 */
  @PostMapping("/import-tasks")
  public ApiResponse<AdminImportTaskResponse> createImportTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminImportTaskRequest request
  ) {
    return ApiResponse.success(adminTableToolService.createImportTask(principal.id(), request));
  }

  /** 创建批量操作任务。 */
  @PostMapping("/batch-operations")
  public ApiResponse<AdminBatchOperationResponse> createBatchOperation(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminBatchOperationRequest request
  ) {
    return ApiResponse.success(adminTableToolService.createBatchOperation(principal.id(), request));
  }
}
