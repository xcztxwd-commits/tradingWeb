package com.fxplatform.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.dto.request.AdminBalanceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminDataProviderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.admin.dto.request.AdminFundOrderReviewRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.request.AdminSymbolRequest;
import com.fxplatform.admin.dto.request.AdminSymbolStatusRequest;
import com.fxplatform.admin.dto.request.AdminUserStatusRequest;
import com.fxplatform.common.security.UserPrincipal;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminActionPermissionContractTest {

  @Test
  void highRiskAdminActionsDeclareActionLevelAuthorities() throws Exception {
    assertPreAuthorizeContains(
        AdminFinanceController.class,
        "adjustBalance",
        "finance:adjustment:create",
        UserPrincipal.class,
        UUID.class,
        AdminBalanceAdjustmentRequest.class);
    assertPreAuthorizeContains(
        AdminFundOrderController.class,
        "reviewOrder",
        "finance:fund-order:approve",
        "finance:fund-order:reject",
        UserPrincipal.class,
        UUID.class,
        AdminFundOrderReviewRequest.class);
    assertPreAuthorizeContains(
        AdminTradingController.class,
        "cancelOrder",
        "trading:order:cancel",
        UserPrincipal.class,
        UUID.class,
        AdminCancelOrderRequest.class);
    assertPreAuthorizeContains(
        AdminTradingController.class,
        "forceClosePosition",
        "trading:position:force-close",
        UserPrincipal.class,
        UUID.class,
        AdminForceClosePositionRequest.class);
    assertPreAuthorizeContains(
        AdminMarketController.class,
        "createSymbol",
        "market:symbol:create",
        UserPrincipal.class,
        AdminSymbolRequest.class);
    assertPreAuthorizeContains(
        AdminMarketController.class,
        "updateSymbol",
        "market:symbol:update",
        UserPrincipal.class,
        UUID.class,
        AdminSymbolRequest.class);
    assertPreAuthorizeContains(
        AdminMarketController.class,
        "deleteSymbol",
        "market:symbol:disable",
        UserPrincipal.class,
        UUID.class,
        AdminReasonRequest.class);
    assertPreAuthorizeContains(
        AdminMarketController.class,
        "updateStatus",
        "market:symbol:update",
        "market:symbol:disable",
        UserPrincipal.class,
        UUID.class,
        AdminSymbolStatusRequest.class);
    assertPreAuthorizeContains(
        AdminMarketDataProviderController.class,
        "updateProvider",
        "market:data-provider:update",
        UUID.class,
        AdminDataProviderRequest.class);
    assertPreAuthorizeContains(
        AdminUserController.class,
        "updateStatus",
        "user:update",
        "user:disable",
        UserPrincipal.class,
        UUID.class,
        AdminUserStatusRequest.class);
    assertPreAuthorizeContains(
        AdminUserController.class,
        "forceLogout",
        "user:force-logout",
        UserPrincipal.class,
        UUID.class,
        AdminReasonRequest.class);
  }

  private void assertPreAuthorizeContains(
      Class<?> controller,
      String methodName,
      String authority,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    assertPreAuthorizeContains(controller, methodName, new String[] {authority}, parameterTypes);
  }

  private void assertPreAuthorizeContains(
      Class<?> controller,
      String methodName,
      String firstAuthority,
      String secondAuthority,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    assertPreAuthorizeContains(controller, methodName, new String[] {firstAuthority, secondAuthority}, parameterTypes);
  }

  private void assertPreAuthorizeContains(
      Class<?> controller,
      String methodName,
      String[] authorities,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    Method method = controller.getMethod(methodName, parameterTypes);
    PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
    assertThat(preAuthorize)
        .as(controller.getSimpleName() + "." + methodName + " must declare method-level action authority")
        .isNotNull();
    assertThat(preAuthorize.value()).contains(authorities);
  }
}
