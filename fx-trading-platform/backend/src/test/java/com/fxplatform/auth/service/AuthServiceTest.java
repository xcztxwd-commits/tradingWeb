package com.fxplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.service.AccountService;
import com.fxplatform.auth.dto.request.LoginRequest;
import com.fxplatform.auth.dto.request.RegisterRequest;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.security.JwtService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private JwtService jwtService;

  @Mock
  private AccountService accountService;

  @Test
  void registersPhoneOnlyInputAndPersistsItForLogin() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000321");
    when(userRepository.existsByPhone("+60 raw phone")).thenReturn(false);
    when(passwordEncoder.encode("1")).thenReturn("encoded-short-password");
    when(jwtService.generateAccessToken(any(UserEntity.class))).thenReturn("registered-token");
    doAnswer(invocation -> {
      UserEntity user = invocation.getArgument(0);
      user.setId(userId);
      return user;
    }).when(userRepository).save(any(UserEntity.class));

    AuthService service = new AuthService(userRepository, passwordEncoder, jwtService, accountService);

    var response = service.register(new RegisterRequest(null, "+60 raw phone", "1"));

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(captor.capture());
    UserEntity saved = captor.getValue();

    assertThat(saved.getEmail()).isNull();
    assertThat(saved.getPhone()).isEqualTo("+60 raw phone");
    assertThat(saved.getPasswordHash()).isEqualTo("encoded-short-password");
    verify(accountService).createDemoAccount(userId);
    assertThat(response.email()).isEqualTo("+60 raw phone");
    assertThat(response.accessToken()).isEqualTo("registered-token");
  }

  @Test
  void logsInWithPhoneIdentifierCreatedByRegistration() {
    UserEntity user = new UserEntity();
    user.setId(UUID.fromString("00000000-0000-0000-0000-000000000456"));
    user.setPhone("+60 raw phone");
    user.setPasswordHash("encoded-short-password");
    when(userRepository.findByEmailOrPhone("+60 raw phone")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("", "encoded-short-password")).thenReturn(true);
    when(jwtService.generateAccessToken(user)).thenReturn("login-token");

    AuthService service = new AuthService(userRepository, passwordEncoder, jwtService, accountService);

    var response = service.login(new LoginRequest("+60 raw phone", ""));

    assertThat(response.email()).isEqualTo("+60 raw phone");
    assertThat(response.accessToken()).isEqualTo("login-token");
  }
}
