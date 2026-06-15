package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminRoleMenuPermissionRequest;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminRbacRolePermissionServiceTest {

  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminMenuRepository menuRepository = Mockito.mock(AdminMenuRepository.class);
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository =
      Mockito.mock(AdminRoleMenuPermissionRepository.class);
  private final AdminRoleDataScopeRepository dataScopeRepository = Mockito.mock(AdminRoleDataScopeRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void saveRoleMenuPermissionRejectsMissingMenuWithoutSaving() {
    UUID roleId = UUID.randomUUID();
    UUID menuId = UUID.randomUUID();
    when(roleRepository.findById(roleId)).thenReturn(Optional.of(new AdminRoleEntity()));
    when(menuRepository.findById(menuId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().saveRoleMenuPermission(
            UUID.randomUUID(),
            roleId,
            new AdminRoleMenuPermissionRequest(menuId, List.of("view"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Admin menu not found");

    verify(roleMenuPermissionRepository, never()).save(any(AdminRoleMenuPermissionEntity.class));
    verify(auditLogService, never()).record(any(), any(), any(), any(), any());
  }

  private AdminRolePermissionService service() {
    return new AdminRolePermissionService(
        roleRepository,
        menuRepository,
        roleMenuPermissionRepository,
        dataScopeRepository,
        auditLogService);
  }
}
