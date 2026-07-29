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
    role.setRoleCode(null);
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
  void exposesTrimmedEnabledRoleCodeEvenWhenRoleHasNoMenuPermissions() {
    UUID userId = UUID.randomUUID();
    UUID roleId = AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID;
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("  SUPER_ADMIN  ");
    role.setEnabled(true);
    role.setSystemManaged(true);

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of());

    assertThat(service().authoritiesFor(user))
        .containsExactly("ROLE_ADMIN", "SUPER_ADMIN");
  }

  @Test
  void doesNotExposeSuperAdminFromRoleWithoutSeedIdentity() {
    UUID userId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("  SUPER_ADMIN  ");
    role.setEnabled(true);

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of());

    assertThat(service().authoritiesFor(user)).containsExactly("ROLE_ADMIN");
  }

  @Test
  void doesNotExposeSuperAdminFromFixedSeedIdentityWithoutSystemProvenance() {
    UUID userId = UUID.randomUUID();
    UUID roleId = AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID;
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("\tSUPER_ADMIN\n");
    role.setEnabled(true);
    role.setSystemManaged(false);

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of());

    assertThat(service().authoritiesFor(user)).containsExactly("ROLE_ADMIN");
  }

  @Test
  void doesNotSynthesizeSuperAdminFromMenuOrButtonAuthorities() {
    UUID userId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UUID menuId = UUID.randomUUID();
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("OPS_ADMIN");
    role.setEnabled(true);
    AdminMenuEntity menu = new AdminMenuEntity();
    menu.setId(menuId);
    menu.setPermissionKey("SUPER_ADMIN");
    menu.setEnabled(true);
    AdminRoleMenuPermissionEntity permission = new AdminRoleMenuPermissionEntity();
    permission.setRoleId(roleId);
    permission.setMenuId(menuId);
    permission.setEnabled(true);
    permission.setButtons("[\"SUPER_ADMIN\",\"TRADING_LAB_EXECUTE\"]");

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(menuRepository.selectList(any())).thenReturn(List.of(menu));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of(permission));

    assertThat(service().authoritiesFor(user))
        .containsExactlyInAnyOrder("ROLE_ADMIN", "OPS_ADMIN", "TRADING_LAB_EXECUTE")
        .doesNotContain("SUPER_ADMIN");
  }

  @Test
  void doesNotExposeDisabledRoleCode() {
    UUID userId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UserEntity user = user(userId, UserRole.ADMIN);
    AdminUserRoleEntity userRole = new AdminUserRoleEntity();
    userRole.setUserId(userId);
    userRole.setRoleId(roleId);
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode("SUPER_ADMIN");
    role.setEnabled(false);

    when(userRoleRepository.selectList(any())).thenReturn(List.of(userRole));
    when(roleRepository.selectList(any())).thenReturn(List.of(role));
    when(roleMenuPermissionRepository.selectList(any())).thenReturn(List.of());

    assertThat(service().authoritiesFor(user)).containsExactly("ROLE_ADMIN");
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
