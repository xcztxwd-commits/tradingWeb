package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminAuditLogResponse;
import com.fxplatform.audit.repository.AuditLogRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminAuditQueryService 提供后台审计日志只读查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminAuditQueryService {

  /** 审计日志 Mapper，只在 Service 内转换 DTO。 */
  private final AuditLogRepository auditLogRepository;

  /** 分页查询审计日志，按创建时间倒序返回。 */
  public AdminPageResponse<AdminAuditLogResponse> auditLogs(int page, int size) {
    return AdminPageResponse.from(auditLogRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminAuditLogResponse::from));
  }
}
