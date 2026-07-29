package com.fxplatform.tradinglab.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingLabPermissionCatalogTest {

  @Test
  void tradingLabAuthoritiesUseExactDistinctValues() {
    assertThat(AdminPermissionCatalog.TRADING_LAB_VIEW).isEqualTo("TRADING_LAB_VIEW");
    assertThat(AdminPermissionCatalog.TRADING_LAB_EXECUTE).isEqualTo("TRADING_LAB_EXECUTE");
    assertThat(AdminPermissionCatalog.SUPER_ADMIN).isEqualTo("SUPER_ADMIN");
    assertThat(AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID.toString())
        .isEqualTo("00000000-0000-0000-0000-000000000061");
    assertThat(Set.of(
            AdminPermissionCatalog.TRADING_LAB_VIEW,
            AdminPermissionCatalog.TRADING_LAB_EXECUTE,
            AdminPermissionCatalog.SUPER_ADMIN))
        .hasSize(3);
  }
}
