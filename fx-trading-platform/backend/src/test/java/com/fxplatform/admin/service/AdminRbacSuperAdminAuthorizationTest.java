package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.dto.request.AdminMenuRequest;
import com.fxplatform.admin.dto.request.AdminRoleDataScopeRequest;
import com.fxplatform.admin.dto.request.AdminRoleMenuPermissionRequest;
import com.fxplatform.admin.dto.request.AdminRoleRequest;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminDepartmentRepository;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminPostRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AdminRbacSuperAdminAuthorizationTest.MethodSecurityConfig.class)
class AdminRbacSuperAdminAuthorizationTest {

  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID MENU_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

  @Autowired
  private AdminRbacResourceManagementService resourceService;
  @Autowired
  private AdminRolePermissionService permissionService;
  @Autowired
  private AdminUserRoleAssignmentService assignmentService;
  @Autowired
  private AdminRoleRepository roleRepository;
  @Autowired
  private AdminMenuRepository menuRepository;
  @Autowired
  private AdminRoleMenuPermissionRepository roleMenuPermissionRepository;
  @Autowired
  private AdminUserRoleRepository userRoleRepository;
  @Autowired
  private AdminRoleDataScopeRepository dataScopeRepository;
  @Autowired
  private AdminDepartmentRepository departmentRepository;
  @Autowired
  private AdminPostRepository postRepository;
  @Autowired
  private AuditLogService auditLogService;

  @BeforeEach
  void clearMockHistory() {
    clearInvocations(
        roleRepository,
        menuRepository,
        roleMenuPermissionRepository,
        userRoleRepository,
        dataScopeRepository,
        departmentRepository,
        postRepository,
        auditLogService);
  }

  @Test
  void everyAuthorizationGraphMutationDeclaresTheSuperAdminBoundary() throws Exception {
    assertGuarded(AdminRbacResourceManagementService.class, "createRole",
        UUID.class, AdminRoleRequest.class);
    assertGuarded(AdminRbacResourceManagementService.class, "updateRole",
        UUID.class, UUID.class, AdminRoleRequest.class);
    assertGuarded(AdminRbacResourceManagementService.class, "deleteRole",
        UUID.class, UUID.class, String.class);
    assertGuarded(AdminRbacResourceManagementService.class, "createMenu",
        UUID.class, AdminMenuRequest.class);
    assertGuarded(AdminRbacResourceManagementService.class, "updateMenu",
        UUID.class, UUID.class, AdminMenuRequest.class);
    assertGuarded(AdminRbacResourceManagementService.class, "deleteMenu",
        UUID.class, UUID.class, String.class);
    assertGuarded(AdminRolePermissionService.class, "saveRoleMenuPermission",
        UUID.class, UUID.class, AdminRoleMenuPermissionRequest.class);
    assertGuarded(AdminRolePermissionService.class, "saveDataScope",
        UUID.class, UUID.class, AdminRoleDataScopeRequest.class);
    assertGuarded(AdminUserRoleAssignmentService.class, "assignUserRole",
        UUID.class, AdminAssignUserRoleRequest.class);
  }

  @Test
  @WithMockUser(authorities = "ROLE_ADMIN")
  void ordinaryAdminCannotMutateAnyAuthorizationGraphEdge() {
    AdminRoleRequest role = new AdminRoleRequest("Ops", "OPS", true, 1, null);
    AdminMenuRequest menu = new AdminMenuRequest(
        null, "Lab", "TRADING_LAB_VIEW", "/lab", null, "MENU", true, 1);

    assertDenied(() -> resourceService.createRole(ACTOR_ID, role));
    assertDenied(() -> resourceService.updateRole(ACTOR_ID, ROLE_ID, role));
    assertDenied(() -> resourceService.deleteRole(ACTOR_ID, ROLE_ID, "reason"));
    assertDenied(() -> resourceService.createMenu(ACTOR_ID, menu));
    assertDenied(() -> resourceService.updateMenu(ACTOR_ID, MENU_ID, menu));
    assertDenied(() -> resourceService.deleteMenu(ACTOR_ID, MENU_ID, "reason"));
    assertDenied(() -> permissionService.saveRoleMenuPermission(
        ACTOR_ID, ROLE_ID, new AdminRoleMenuPermissionRequest(MENU_ID, List.of("SUPER_ADMIN"))));
    assertDenied(() -> permissionService.saveDataScope(
        ACTOR_ID, ROLE_ID, new AdminRoleDataScopeRequest("ALL", List.of())));
    assertDenied(() -> assignmentService.assignUserRole(
        ACTOR_ID, new AdminAssignUserRoleRequest(TARGET_ID, ROLE_ID)));

    verifyNoPersistenceInteraction();
  }

  @Test
  @WithMockUser(authorities = "SUPER_ADMIN")
  void superAdminAuthorityWithoutTheAdminRoleStillCannotAssignRoles() {
    assertDenied(() -> assignmentService.assignUserRole(
        ACTOR_ID, new AdminAssignUserRoleRequest(TARGET_ID, ROLE_ID)));

    verifyNoPersistenceInteraction();
  }

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", "SUPER_ADMIN"})
  void adminWithSuperAdminAuthorityCanAssignRoles() {
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(ROLE_ID);
    role.setRoleCode("OPS");
    role.setEnabled(true);
    when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
    when(userRoleRepository.findByUserIdAndRoleId(TARGET_ID, ROLE_ID))
        .thenReturn(Optional.empty());
    when(userRoleRepository.save(any(AdminUserRoleEntity.class))).thenAnswer(invocation -> {
      AdminUserRoleEntity binding = invocation.getArgument(0);
      binding.setId(UUID.randomUUID());
      return binding;
    });

    assertThatCode(() -> assignmentService.assignUserRole(
        ACTOR_ID, new AdminAssignUserRoleRequest(TARGET_ID, ROLE_ID)))
        .doesNotThrowAnyException();
  }

  private void assertGuarded(
      Class<?> type,
      String methodName,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    Method method = type.getMethod(methodName, parameterTypes);
    PreAuthorize guard = method.getAnnotation(PreAuthorize.class);

    assertThat(guard).as(type.getSimpleName() + "." + methodName).isNotNull();
    assertThat(guard.value()).contains("ROLE_ADMIN", "SUPER_ADMIN");
  }

  private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(AccessDeniedException.class);
  }

  private void verifyNoPersistenceInteraction() {
    verifyNoInteractions(
        roleRepository,
        menuRepository,
        roleMenuPermissionRepository,
        userRoleRepository,
        dataScopeRepository,
        departmentRepository,
        postRepository,
        auditLogService);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfig {

    @Bean AdminRoleRepository roleRepository() { return mock(AdminRoleRepository.class); }
    @Bean AdminMenuRepository menuRepository() { return mock(AdminMenuRepository.class); }
    @Bean AdminRoleMenuPermissionRepository roleMenuPermissionRepository() {
      return mock(AdminRoleMenuPermissionRepository.class);
    }
    @Bean AdminUserRoleRepository userRoleRepository() {
      return mock(AdminUserRoleRepository.class);
    }
    @Bean AdminRoleDataScopeRepository dataScopeRepository() {
      return mock(AdminRoleDataScopeRepository.class);
    }
    @Bean AdminDepartmentRepository departmentRepository() {
      return mock(AdminDepartmentRepository.class);
    }
    @Bean AdminPostRepository postRepository() { return mock(AdminPostRepository.class); }
    @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }

    @Bean
    AdminRbacResourceManagementService resourceService(
        AdminRoleRepository roleRepository,
        AdminMenuRepository menuRepository,
        AdminRoleMenuPermissionRepository roleMenuPermissionRepository,
        AdminUserRoleRepository userRoleRepository,
        AdminRoleDataScopeRepository dataScopeRepository,
        AdminDepartmentRepository departmentRepository,
        AdminPostRepository postRepository,
        AuditLogService auditLogService
    ) {
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

    @Bean
    AdminRolePermissionService permissionService(
        AdminRoleRepository roleRepository,
        AdminMenuRepository menuRepository,
        AdminRoleMenuPermissionRepository roleMenuPermissionRepository,
        AdminRoleDataScopeRepository dataScopeRepository,
        AuditLogService auditLogService
    ) {
      return new AdminRolePermissionService(
          roleRepository,
          menuRepository,
          roleMenuPermissionRepository,
          dataScopeRepository,
          auditLogService);
    }

    @Bean
    AdminUserRoleAssignmentService assignmentService(
        AdminRoleRepository roleRepository,
        AdminUserRoleRepository userRoleRepository,
        AuditLogService auditLogService
    ) {
      return new AdminUserRoleAssignmentService(
          roleRepository, userRoleRepository, auditLogService);
    }
  }
}
