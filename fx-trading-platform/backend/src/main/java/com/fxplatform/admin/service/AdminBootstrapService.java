package com.fxplatform.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
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

  @Transactional
  public void bootstrap() {
    if (!properties.enabled()) {
      return;
    }
    if (StrUtil.isBlank(properties.email()) || StrUtil.isBlank(properties.password())) {
      throw new IllegalStateException("Admin bootstrap requires email and password");
    }

    userRepository.findByEmail(properties.email())
        .ifPresentOrElse(this::ensureAdminRole, this::createAdminUser);
  }

  private void createAdminUser() {
    UserEntity user = new UserEntity();
    user.setEmail(properties.email());
    user.setPasswordHash(passwordEncoder.encode(properties.password()));
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    userRepository.save(user);
  }

  private void ensureAdminRole(UserEntity user) {
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    user.setPasswordHash(passwordEncoder.encode(properties.password()));
    userRepository.save(user);
  }
}
