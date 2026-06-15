package com.fxplatform.admin.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminDepartmentRepository;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminPostRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminRbacResourceManagementServiceTest {

  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminMenuRepository menuRepository = Mockito.mock(AdminMenuRepository.class);
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository =
      Mockito.mock(AdminRoleMenuPermissionRepository.class);
  private final AdminUserRoleRepository userRoleRepository = Mockito.mock(AdminUserRoleRepository.class);
  private final AdminRoleDataScopeRepository dataScopeRepository = Mockito.mock(AdminRoleDataScopeRepository.class);
  private final AdminDepartmentRepository departmentRepository = Mockito.mock(AdminDepartmentRepository.class);
  private final AdminPostRepository postRepository = Mockito.mock(AdminPostRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void deleteRoleRemovesPermissionDataScopeAndUserRoleBindingsBeforeDeletingRole() {
    UUID actorUserId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("ops_manager");
    when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

    service().deleteRole(actorUserId, roleId, "cleanup");

    verify(roleMenuPermissionRepository).delete(any(QueryWrapper.class));
    verify(dataScopeRepository).delete(any(QueryWrapper.class));
    verify(userRoleRepository).delete(any(QueryWrapper.class));
    verify(roleRepository).deleteById(roleId);
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_RBAC_ROLE_DELETE"),
        eq("ADMIN_ROLE"),
        eq(roleId.toString()),
        any());
  }

  private AdminRbacResourceManagementService service() {
    return new AdminRbacResourceManagementService(
        roleRepository,
        menuRepository,
        roleMenuPermissionRepository,
        userRoleRepository,
        dataScopeRepository,
        departmentRepository,
        postRepository,
        auditLogService);
  }
}
