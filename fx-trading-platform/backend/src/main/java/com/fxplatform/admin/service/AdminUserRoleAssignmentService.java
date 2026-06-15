package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.dto.response.AdminUserRoleResponse;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserRoleAssignmentService {

  private final AdminRoleRepository roleRepository;
  private final AdminUserRoleRepository userRoleRepository;
  private final AuditLogService auditLogService;

  @Transactional
  public AdminUserRoleResponse assignUserRole(UUID actorUserId, AdminAssignUserRoleRequest request) {
    AdminRbacServiceSupport.requireRole(roleRepository, request.roleId());
    AdminUserRoleEntity userRole = userRoleRepository
        .findByUserIdAndRoleId(request.userId(), request.roleId())
        .orElseGet(AdminUserRoleEntity::new);
    userRole.setUserId(request.userId());
    userRole.setRoleId(request.roleId());
    AdminUserRoleEntity saved = userRoleRepository.save(userRole);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_USER_ROLE_ASSIGN", "USER", request.userId(), request.roleId().toString());
    return AdminUserRoleResponse.from(saved);
  }
}
