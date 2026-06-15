package com.fxplatform.auth.service;

import com.fxplatform.account.service.AccountService;
import com.fxplatform.auth.dto.request.LoginRequest;
import com.fxplatform.auth.dto.request.RegisterRequest;
import com.fxplatform.auth.dto.response.AuthResponse;
import com.fxplatform.auth.dto.response.MeResponse;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService 是认证授权模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AccountService accountService;

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.email())) {
      throw new BusinessException("EMAIL_EXISTS", "Email already exists");
    }
    UserEntity user = new UserEntity();
    user.setEmail(request.email());
    user.setPhone(request.phone());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    userRepository.save(user);

    // 注册即创建 DEMO 账户，并通过 LedgerService 写入初始模拟入金流水。
    accountService.createDemoAccount(user.getId());

    return tokenResponse(user);
  }

  public AuthResponse login(LoginRequest request) {
    UserEntity user = userRepository.findByEmail(request.email())
        .orElseThrow(() -> new BusinessException("BAD_CREDENTIALS", "Invalid email or password"));
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new BusinessException("BAD_CREDENTIALS", "Invalid email or password");
    }
    return tokenResponse(user);
  }

  public MeResponse me(UserPrincipal principal) {
    UserEntity user = userRepository.findById(principal.id())
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
    return new MeResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getStatus().name());
  }

  private AuthResponse tokenResponse(UserEntity user) {
    return new AuthResponse(user.getId(), user.getEmail(), user.getRole().name(), jwtService.generateAccessToken(user));
  }
}
