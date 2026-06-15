package com.fxplatform.common.security;

import com.fxplatform.auth.entity.UserEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * UserPrincipal 承载通用基础设施模块的数据结构。
 */
public record UserPrincipal(UUID id, String email, String role) implements UserDetails {

  /**
   * 处理 from 安全认证逻辑。
   */
  public static UserPrincipal from(UserEntity user) {
    return new UserPrincipal(user.getId(), user.getEmail(), user.getRole().name());
  }

  /**
   * 处理 getAuthorities 安全认证逻辑。
   */
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  /**
   * 处理 getPassword 安全认证逻辑。
   */
  @Override
  public String getPassword() {
    return "";
  }

  /**
   * 处理 getUsername 安全认证逻辑。
   */
  @Override
  public String getUsername() {
    return email;
  }
}
