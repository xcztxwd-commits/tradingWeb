package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private AdminRoleRepository roleRepository;

  @Mock
  private AdminUserRoleRepository userRoleRepository;

  @Test
  void createsAdminUserWhenBootstrapIsEnabledAndUserMissing() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@example.com", "Password123!");
    UUID userId = UUID.randomUUID();
    UUID roleId = AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID;
    when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("Password123!")).thenReturn("encoded-password");
    when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
      UserEntity user = invocation.getArgument(0);
      user.setId(userId);
      return user;
    });
    stubEnabledSuperAdminRole(roleId);
    when(userRoleRepository.findByUserIdAndRoleId(userId, roleId)).thenReturn(Optional.empty());

    service(properties).bootstrap();

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(captor.capture());
    UserEntity saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo("admin@example.com");
    assertThat(saved.getPasswordHash()).isEqualTo("encoded-password");
    assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);

    ArgumentCaptor<AdminUserRoleEntity> bindingCaptor =
        ArgumentCaptor.forClass(AdminUserRoleEntity.class);
    verify(userRoleRepository).save(bindingCaptor.capture());
    assertThat(bindingCaptor.getValue().getUserId()).isEqualTo(userId);
    assertThat(bindingCaptor.getValue().getRoleId()).isEqualTo(roleId);
  }

  @Test
  void doesNotCreateAdminUserWhenBootstrapIsDisabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(false, "admin@example.com", "Password123!");

    service(properties).bootstrap();

    verifyNoInteractions(userRepository, passwordEncoder, roleRepository, userRoleRepository);
  }

  @Test
  void resetsExistingAdminPasswordWhenBootstrapIsEnabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@gmail.com", "admin");
    UUID userId = UUID.randomUUID();
    UUID roleId = AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID;
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("admin@gmail.com");
    user.setPasswordHash("old-password");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    when(userRepository.findByEmail("admin@gmail.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("admin")).thenReturn("encoded-admin");
    stubEnabledSuperAdminRole(roleId);
    when(userRoleRepository.findByUserIdAndRoleId(userId, roleId)).thenReturn(Optional.empty());

    service(properties).bootstrap();

    assertThat(user.getPasswordHash()).isEqualTo("encoded-admin");
    verify(userRepository).save(user);

    ArgumentCaptor<AdminUserRoleEntity> bindingCaptor =
        ArgumentCaptor.forClass(AdminUserRoleEntity.class);
    verify(userRoleRepository).save(bindingCaptor.capture());
    assertThat(bindingCaptor.getValue().getUserId()).isEqualTo(userId);
    assertThat(bindingCaptor.getValue().getRoleId()).isEqualTo(roleId);
  }

  @Test
  void reusesExistingSuperAdminBindingAcrossRepeatedBootstrapRuns() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@example.com", "admin");
    UUID userId = UUID.randomUUID();
    UUID roleId = AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID;
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("admin@example.com");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    AdminUserRoleEntity existingBinding = new AdminUserRoleEntity();
    existingBinding.setId(UUID.randomUUID());
    existingBinding.setUserId(userId);
    existingBinding.setRoleId(roleId);

    when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("admin")).thenReturn("encoded-admin");
    stubEnabledSuperAdminRole(roleId);
    when(userRoleRepository.findByUserIdAndRoleId(userId, roleId))
        .thenReturn(Optional.of(existingBinding));

    AdminBootstrapService service = service(properties);
    service.bootstrap();
    service.bootstrap();

    verify(roleRepository, times(2)).findByRoleCode(AdminPermissionCatalog.SUPER_ADMIN);
    verify(userRoleRepository, times(2)).findByUserIdAndRoleId(userId, roleId);
    verify(userRoleRepository, never()).save(any(AdminUserRoleEntity.class));
  }

  @Test
  void rejectsSuperAdminRoleCodeWithoutTheSeedIdentity() {
    AdminBootstrapProperties properties =
        new AdminBootstrapProperties(true, "admin@example.com", "Password123!");
    AdminRoleEntity collision = new AdminRoleEntity();
    collision.setId(UUID.randomUUID());
    collision.setRoleCode(AdminPermissionCatalog.SUPER_ADMIN);
    collision.setEnabled(true);
    when(roleRepository.findByRoleCode(AdminPermissionCatalog.SUPER_ADMIN))
        .thenReturn(Optional.of(collision));

    assertThatThrownBy(() -> service(properties).bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seed");

    verifyNoInteractions(userRepository, passwordEncoder, userRoleRepository);
  }

  @Test
  void rejectsFixedSuperAdminSeedIdentityWithoutSystemProvenance() {
    AdminBootstrapProperties properties =
        new AdminBootstrapProperties(true, "admin@example.com", "Password123!");
    AdminRoleEntity collision = new AdminRoleEntity();
    collision.setId(AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID);
    collision.setRoleCode(AdminPermissionCatalog.SUPER_ADMIN);
    collision.setEnabled(true);
    collision.setSystemManaged(false);
    when(roleRepository.findByRoleCode(AdminPermissionCatalog.SUPER_ADMIN))
        .thenReturn(Optional.of(collision));

    assertThatThrownBy(() -> service(properties).bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seed");

    verifyNoInteractions(userRepository, passwordEncoder, userRoleRepository);
  }

  private AdminBootstrapService service(AdminBootstrapProperties properties) {
    return new AdminBootstrapService(
        userRepository,
        passwordEncoder,
        properties,
        roleRepository,
        userRoleRepository);
  }

  private void stubEnabledSuperAdminRole(UUID roleId) {
    AdminRoleEntity role = new AdminRoleEntity();
    role.setId(roleId);
    role.setRoleCode(AdminPermissionCatalog.SUPER_ADMIN);
    role.setEnabled(true);
    role.setSystemManaged(true);
    when(roleRepository.findByRoleCode(eq(AdminPermissionCatalog.SUPER_ADMIN)))
        .thenReturn(Optional.of(role));
  }
}
