package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminAuditLogResponse;
import com.fxplatform.admin.service.AdminAuditQueryService;
import com.fxplatform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminController 是后台管理模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

  /** 后台审计日志查询服务。 */
  private final AdminAuditQueryService adminAuditQueryService;

  /**
   * 处理 auditLogs 查询接口请求。
   */
  @GetMapping("/audit-logs")
  public ApiResponse<AdminPageResponse<AdminAuditLogResponse>> auditLogs(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.success(adminAuditQueryService.auditLogs(page, size));
  }

}
