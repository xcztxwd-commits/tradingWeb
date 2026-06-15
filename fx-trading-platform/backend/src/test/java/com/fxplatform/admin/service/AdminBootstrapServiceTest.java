package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import java.util.Optional;
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

  @Test
  void createsAdminUserWhenBootstrapIsEnabledAndUserMissing() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@example.com", "Password123!");
    when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("Password123!")).thenReturn("encoded-password");

    new AdminBootstrapService(userRepository, passwordEncoder, properties).bootstrap();

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(captor.capture());
    UserEntity saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo("admin@example.com");
    assertThat(saved.getPasswordHash()).isEqualTo("encoded-password");
    assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void doesNotCreateAdminUserWhenBootstrapIsDisabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(false, "admin@example.com", "Password123!");

    new AdminBootstrapService(userRepository, passwordEncoder, properties).bootstrap();

    verify(userRepository, never()).save(any());
  }

  @Test
  void resetsExistingAdminPasswordWhenBootstrapIsEnabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@gmail.com", "admin");
    UserEntity user = new UserEntity();
    user.setEmail("admin@gmail.com");
    user.setPasswordHash("old-password");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    when(userRepository.findByEmail("admin@gmail.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("admin")).thenReturn("encoded-admin");

    new AdminBootstrapService(userRepository, passwordEncoder, properties).bootstrap();

    assertThat(user.getPasswordHash()).isEqualTo("encoded-admin");
    verify(userRepository).save(user);
  }
}
