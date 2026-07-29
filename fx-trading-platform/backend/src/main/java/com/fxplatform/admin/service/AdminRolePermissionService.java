package com.fxplatform.admin.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.dto.request.AdminRoleDataScopeRequest;
import com.fxplatform.admin.dto.request.AdminRoleMenuPermissionRequest;
import com.fxplatform.admin.dto.response.AdminRoleDataScopeResponse;
import com.fxplatform.admin.dto.response.AdminRoleMenuPermissionResponse;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRolePermissionService {

  private final AdminRoleRepository roleRepository;
  private final AdminMenuRepository menuRepository;
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository;
  private final AdminRoleDataScopeRepository dataScopeRepository;
  private final AuditLogService auditLogService;

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminRoleMenuPermissionResponse saveRoleMenuPermission(
      UUID actorUserId,
      UUID roleId,
      AdminRoleMenuPermissionRequest request
  ) {
    AdminRbacServiceSupport.requireRole(roleRepository, roleId);
    AdminRbacServiceSupport.requireMenu(menuRepository, request.menuId());
    AdminRoleMenuPermissionEntity permission = roleMenuPermissionRepository
        .findByRoleIdAndMenuId(roleId, request.menuId())
        .orElseGet(AdminRoleMenuPermissionEntity::new);
    permission.setRoleId(roleId);
    permission.setMenuId(request.menuId());
    permission.setButtons(JSONUtil.toJsonStr(CollUtil.emptyIfNull(request.buttons())));
    permission.setEnabled(true);
    AdminRoleMenuPermissionEntity saved = roleMenuPermissionRepository.save(permission);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_MENU_PERMISSION_SAVE", "ADMIN_ROLE", roleId, saved.getButtons());
    return AdminRoleMenuPermissionResponse.from(saved);
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminRoleDataScopeResponse saveDataScope(UUID actorUserId, UUID roleId, AdminRoleDataScopeRequest request) {
    AdminRbacServiceSupport.requireRole(roleRepository, roleId);
    AdminRoleDataScopeEntity scope = dataScopeRepository.findByRoleId(roleId)
        .orElseGet(AdminRoleDataScopeEntity::new);
    scope.setRoleId(roleId);
    scope.setScopeType(request.scopeType());
    scope.setDepartmentIds(JSONUtil.toJsonStr(CollUtil.emptyIfNull(request.departmentIds())));
    AdminRoleDataScopeEntity saved = dataScopeRepository.save(scope);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_DATA_SCOPE_SAVE", "ADMIN_ROLE", roleId, request.scopeType());
    return AdminRoleDataScopeResponse.from(saved);
  }
}
