package com.fxplatform.tradinglab.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(TradingLabAuthorizationTest.MethodSecurityConfig.class)
class TradingLabAuthorizationTest {

  @Autowired
  private SecuredTradingLabFixture fixture;

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", AdminPermissionCatalog.TRADING_LAB_VIEW})
  void viewAdminCanReadButCannotExecuteOrControlEnvironment() {
    assertThatCode(fixture::view).doesNotThrowAnyException();
    assertThatThrownBy(fixture::execute).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(fixture::controlEnvironment).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", AdminPermissionCatalog.TRADING_LAB_EXECUTE})
  void executeAdminCanExecuteButCannotControlEnvironment() {
    assertThatCode(fixture::execute).doesNotThrowAnyException();
    assertThatThrownBy(fixture::controlEnvironment).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", AdminPermissionCatalog.SUPER_ADMIN})
  void superAdminCanControlEnvironment() {
    assertThatCode(fixture::controlEnvironment).doesNotThrowAnyException();
  }

  @Test
  @WithMockUser(authorities = "ROLE_ADMIN")
  void ordinaryAdminHasNoTradingLabCapability() {
    assertThatThrownBy(fixture::view).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(fixture::execute).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(fixture::controlEnvironment).isInstanceOf(AccessDeniedException.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfig {

    @Bean
    SecuredTradingLabFixture securedTradingLabFixture() {
      return new SecuredTradingLabFixture();
    }
  }

  static class SecuredTradingLabFixture {

    @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
        + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
    public void view() {
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
        + AdminPermissionCatalog.TRADING_LAB_EXECUTE + "')")
    public void execute() {
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
        + AdminPermissionCatalog.SUPER_ADMIN + "')")
    public void controlEnvironment() {
    }
  }
}
