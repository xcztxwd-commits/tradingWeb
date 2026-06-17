package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminAuthorityServiceTest {

  private final AdminUserRoleRepository userRoleRepository = Mockito.mock(AdminUserRoleRepository.class);
  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminMenuRepository menuRepository = Mockito.mock(AdminMenuRepository.class);
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository =
      Mockito.mock(AdminRoleMenuPermissionRepository.class);

  @Test
  void resolvesRoleMenuAndButtonAuthoritiesForAdminUser() {
    UUID userId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UUID menuId = UUID.randomUUID();
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setEnabled(true);
    AdminMenuEntity menu = new AdminMenuEntity();
    menu.setId(menuId);
    menu.setPermissionKey("finance:fund-order:read");
    menu.setEnabled(true);
    AdminRoleMenuPermissionEntity permission = new AdminRoleMenuPermissionEntity();
    permission.setRoleId(roleId);
    permission.setMenuId(menuId);
    permission.setEnabled(true);
    permission.setButtons("[\"finance:fund-order:approve\",\"finance:fund-order:reject\"]");

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(menuRepository.selectList(any())).thenReturn(List.of(menu));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of(permission));

    assertThat(service().authoritiesFor(user))
        .containsExactlyInAnyOrder(
            "ROLE_ADMIN",
            "finance:fund-order:read",
            "finance:fund-order:approve",
            "finance:fund-order:reject");
  }

  @Test
  void keepsRoleAuthorityWhenAdminHasNoRbacBindings() {
    UUID userId = UUID.randomUUID();
    UserEntity user = user(userId, UserRole.ADMIN);
    when(userRoleRepository.selectList(any())).thenReturn(List.of());
    when(roleRepository.selectList(any())).thenReturn(List.of());
    when(menuRepository.selectList(any())).thenReturn(List.of());
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of());

    assertThat(service().authoritiesFor(user)).containsExactly("ROLE_ADMIN");
  }

  private AdminAuthorityService service() {
    return new AdminAuthorityService(
        userRoleRepository,
        roleRepository,
        menuRepository,
        roleMenuPermissionRepository);
  }

  private UserEntity user(UUID userId, UserRole role) {
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("admin@example.com");
    user.setRole(role);
    return user;
  }
}
