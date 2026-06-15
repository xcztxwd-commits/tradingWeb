package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminRbacUserRoleAssignmentServiceTest {

  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminUserRoleRepository userRoleRepository = Mockito.mock(AdminUserRoleRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void assignUserRoleReusesExistingBindingAndAuditsDuplicateRequest() {
    UUID actorUserId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID bindingId = UUID.randomUUID();
    AdminUserRoleEntity existing = new AdminUserRoleEntity();
    existing.setId(bindingId);
    existing.setUserId(userId);
    existing.setRoleId(roleId);

    when(roleRepository.findById(roleId)).thenReturn(Optional.of(new AdminRoleEntity()));
    when(userRoleRepository.findByUserIdAndRoleId(userId, roleId)).thenReturn(Optional.of(existing));
    when(userRoleRepository.save(existing)).thenReturn(existing);

    var response = service().assignUserRole(actorUserId, new AdminAssignUserRoleRequest(userId, roleId));

    assertThat(response.id()).isEqualTo(bindingId);
    assertThat(response.userId()).isEqualTo(userId);
    assertThat(response.roleId()).isEqualTo(roleId);
    verify(userRoleRepository).save(existing);
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_RBAC_USER_ROLE_ASSIGN"),
        eq("USER"),
        eq(userId.toString()),
        any());
  }

  @Test
  void assignUserRoleRejectsMissingRoleWithoutSaving() {
    UUID roleId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().assignUserRole(
            UUID.randomUUID(),
            new AdminAssignUserRoleRequest(userId, roleId)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Admin role not found");

    verify(userRoleRepository, never()).save(any());
    verify(auditLogService, never()).record(any(), any(), any(), any(), any());
  }

  private AdminUserRoleAssignmentService service() {
    return new AdminUserRoleAssignmentService(roleRepository, userRoleRepository, auditLogService);
  }
}
