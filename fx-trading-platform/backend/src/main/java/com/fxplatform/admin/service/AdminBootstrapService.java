package com.fxplatform.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminBootstrapService 是后台管理模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AdminBootstrapProperties properties;
  private final AdminRoleRepository roleRepository;
  private final AdminUserRoleRepository userRoleRepository;

  @Transactional
  public void bootstrap() {
    if (!properties.enabled()) {
      return;
    }
    if (StrUtil.isBlank(properties.email()) || StrUtil.isBlank(properties.password())) {
      throw new IllegalStateException("Admin bootstrap requires email and password");
    }

    AdminRoleEntity superAdminRole = requiredSuperAdminRole();
    UserEntity bootstrapAdmin = userRepository.findByEmail(properties.email())
        .map(this::ensureAdminRole)
        .orElseGet(this::createAdminUser);
    ensureSuperAdminBinding(bootstrapAdmin, superAdminRole);
  }

  private UserEntity createAdminUser() {
    UserEntity user = new UserEntity();
    user.setEmail(properties.email());
    user.setPasswordHash(passwordEncoder.encode(properties.password()));
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    userRepository.save(user);
    return user;
  }

  private UserEntity ensureAdminRole(UserEntity user) {
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    user.setPasswordHash(passwordEncoder.encode(properties.password()));
    userRepository.save(user);
    return user;
  }

  private AdminRoleEntity requiredSuperAdminRole() {
    return roleRepository.findByRoleCode(AdminPermissionCatalog.SUPER_ADMIN)
        .filter(role -> AdminPermissionCatalog.SUPER_ADMIN.equals(role.getRoleCode()))
        .filter(role -> Boolean.TRUE.equals(role.getEnabled()))
        .filter(role -> AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID.equals(role.getId()))
        .filter(role -> Boolean.TRUE.equals(role.getSystemManaged()))
        .orElseThrow(() -> new IllegalStateException(
            "Enabled system-managed SUPER_ADMIN bootstrap role seed is required"));
  }

  private void ensureSuperAdminBinding(UserEntity user, AdminRoleEntity role) {
    UUID userId = user.getId();
    if (userId == null) {
      throw new IllegalStateException(
          "Bootstrap Admin must have a persisted UUID before role assignment");
    }
    UUID roleId = role.getId();
    if (userRoleRepository.findByUserIdAndRoleId(userId, roleId).isPresent()) {
      return;
    }
    AdminUserRoleEntity binding = new AdminUserRoleEntity();
    binding.setId(UUID.randomUUID());
    binding.setUserId(userId);
    binding.setRoleId(roleId);
    userRoleRepository.save(binding);
  }
}
