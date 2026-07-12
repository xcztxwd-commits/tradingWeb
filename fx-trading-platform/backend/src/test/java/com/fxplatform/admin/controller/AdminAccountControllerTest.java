package com.fxplatform.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.admin.dto.request.AdminAccountCleanupRequest;
import com.fxplatform.admin.dto.request.AdminDemoResetRequest;
import com.fxplatform.admin.service.AdminAccountCleanupService;
import com.fxplatform.admin.service.AdminAccountQueryService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class AdminAccountControllerTest {

  @Mock
  private AdminAccountQueryService queryService;

  @Mock
  private AdminAccountCleanupService cleanupService;

  @Mock
  private DemoAccountLifecycleService demoAccountLifecycleService;

  @Test
  void forceCleanupPassesAccountActorReasonAndRequestAfterExactConfirmation() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UserPrincipal principal = admin(actorUserId);
    AdminAccountCleanupRequest request = new AdminAccountCleanupRequest(
        "close stale demo state",
        requestId,
        "CONFIRM_FORCE_CLEANUP");
    BatchActionResponse cleanup = new BatchActionResponse(
        accountId,
        requestId.toString(),
        List.of());
    when(cleanupService.cleanup(
        actorUserId,
        accountId,
        request.reason(),
        requestId.toString()))
        .thenReturn(cleanup);

    var response = controller().forceCleanup(principal, accountId, request);

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isSameAs(cleanup);
    verify(cleanupService).cleanup(
        actorUserId,
        accountId,
        "close stale demo state",
        requestId.toString());
    verifyNoInteractions(demoAccountLifecycleService);
  }

  @Test
  void forceCleanupRejectsWrongConfirmationBeforeCallingCleanupService() {
    AdminAccountCleanupRequest request = new AdminAccountCleanupRequest(
        "close stale demo state",
        UUID.randomUUID(),
        "CONFIRM_FORCE_CLOSE");

    assertThatThrownBy(() -> controller().forceCleanup(
        admin(UUID.randomUUID()), UUID.randomUUID(), request))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ADMIN_CONFIRMATION_REQUIRED");

    verifyNoInteractions(cleanupService, demoAccountLifecycleService);
  }

  @Test
  void adminDemoResetIsSeparateAndDelegatesToDemoLifecycleWithAdminActor() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AdminDemoResetRequest request = new AdminDemoResetRequest(
        "restore demo balances",
        requestId,
        "CONFIRM_DEMO_RESET");
    DemoResetResponse reset = new DemoResetResponse(
        accountId,
        requestId,
        3L,
        new BigDecimal("50000.00000000"),
        new BigDecimal("50000.00000000"),
        new BigDecimal("50000.00000000"),
        Instant.parse("2026-07-13T00:00:00Z"),
        false);
    when(demoAccountLifecycleService.resetAsAdmin(
        actorUserId,
        accountId,
        requestId,
        "restore demo balances"))
        .thenReturn(reset);

    var response = controller().resetDemo(admin(actorUserId), accountId, request);

    assertThat(response.data()).isSameAs(reset);
    verify(demoAccountLifecycleService).resetAsAdmin(
        actorUserId,
        accountId,
        requestId,
        "restore demo balances");
    verifyNoInteractions(cleanupService);
  }

  @Test
  void incompleteCleanupDoesNotBypassTheExistingDemoResetGate() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID cleanupRequestId = UUID.randomUUID();
    UUID resetRequestId = UUID.randomUUID();
    BatchActionResponse incomplete = new BatchActionResponse(
        accountId,
        cleanupRequestId.toString(),
        List.of(new BatchActionResponse.Item(
            UUID.randomUUID(),
            null,
            "FAILED",
            "MARKET_DATA_STALE",
            "Market data is stale")));
    when(cleanupService.cleanup(
        actorUserId,
        accountId,
        "cleanup before reset",
        cleanupRequestId.toString()))
        .thenReturn(incomplete);
    when(demoAccountLifecycleService.resetAsAdmin(
        actorUserId,
        accountId,
        resetRequestId,
        "reset after cleanup"))
        .thenThrow(new BusinessException(
            "DEMO_RESET_BLOCKED",
            "Demo reset requires no active orders or open perpetual positions"));
    AdminAccountController controller = controller();

    var cleanupResponse = controller.forceCleanup(
        admin(actorUserId),
        accountId,
        new AdminAccountCleanupRequest(
            "cleanup before reset",
            cleanupRequestId,
            "CONFIRM_FORCE_CLEANUP"));

    assertThat(cleanupResponse.data().items()).singleElement().satisfies(item ->
        assertThat(item.status()).isEqualTo("FAILED"));
    assertThatThrownBy(() -> controller.resetDemo(
        admin(actorUserId),
        accountId,
        new AdminDemoResetRequest(
            "reset after cleanup",
            resetRequestId,
            "CONFIRM_DEMO_RESET")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("DEMO_RESET_BLOCKED");
  }

  @Test
  void walletBalancesRouteReadsTheRequestedAccountScope() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceResponse wallet = new WalletBalanceResponse(
        UUID.randomUUID(),
        accountId,
        "SPOT",
        "USDT",
        new BigDecimal("50000"),
        new BigDecimal("49000"),
        new BigDecimal("1000"));
    when(queryService.walletBalances(accountId)).thenReturn(List.of(wallet));

    var response = controller().walletBalances(accountId);

    assertThat(response.data()).containsExactly(wallet);
    verify(queryService).walletBalances(accountId);
  }

  @Test
  void assetLedgerRoutePassesAllAccountScopedFilters() {
    UUID accountId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    Instant from = Instant.parse("2026-07-12T00:00:00Z");
    Instant to = Instant.parse("2026-07-13T00:00:00Z");
    AssetLedgerEntryResponse entry = new AssetLedgerEntryResponse(
        UUID.randomUUID(),
        accountId,
        "USDT_PERP",
        "USDT",
        new BigDecimal("10"),
        new BigDecimal("50010"),
        "FUNDING_FEE",
        "FUNDING_SETTLEMENT",
        referenceId,
        "Funding settlement",
        from);
    when(queryService.assetLedger(
        accountId,
        "USDT_PERP",
        "usdt",
        "FUNDING_FEE",
        referenceId,
        from,
        to))
        .thenReturn(List.of(entry));

    var response = controller().assetLedger(
        accountId,
        "USDT_PERP",
        "usdt",
        "FUNDING_FEE",
        referenceId,
        from,
        to);

    assertThat(response.data()).containsExactly(entry);
    verify(queryService).assetLedger(
        accountId,
        "USDT_PERP",
        "usdt",
        "FUNDING_FEE",
        referenceId,
        from,
        to);
  }

  @Test
  void fundingSettlementRouteReadsTheRequestedAccountScope() {
    UUID accountId = UUID.randomUUID();
    when(queryService.fundingSettlements(accountId)).thenReturn(List.of());

    var response = controller().fundingSettlements(accountId);

    assertThat(response.data()).isEmpty();
    verify(queryService).fundingSettlements(accountId);
  }

  @Test
  void accountManagementRoutesStayUnderAdminAccountScope() throws Exception {
    RequestMapping root = AdminAccountController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/admin/accounts");
    assertGetMapping("walletBalances", "/{accountId}/wallet-balances", UUID.class);
    assertGetMapping(
        "assetLedger",
        "/{accountId}/asset-ledger",
        UUID.class,
        String.class,
        String.class,
        String.class,
        UUID.class,
        Instant.class,
        Instant.class);
    assertGetMapping("fundingSettlements", "/{accountId}/funding-settlements", UUID.class);
    assertPostMapping(
        "forceCleanup",
        "/{accountId}/force-cleanup",
        UserPrincipal.class,
        UUID.class,
        AdminAccountCleanupRequest.class);
    assertPostMapping(
        "resetDemo",
        "/{accountId}/demo-reset",
        UserPrincipal.class,
        UUID.class,
        AdminDemoResetRequest.class);
  }

  private AdminAccountController controller() {
    return new AdminAccountController(
        queryService,
        cleanupService,
        demoAccountLifecycleService);
  }

  private UserPrincipal admin(UUID id) {
    return new UserPrincipal(
        id,
        "admin@example.com",
        "ADMIN",
        List.of(
            "ROLE_ADMIN",
            "trading:account:force-cleanup",
            "trading:account:demo-reset"));
  }

  private void assertGetMapping(
      String methodName,
      String path,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    Method method = AdminAccountController.class.getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
  }

  private void assertPostMapping(
      String methodName,
      String path,
      Class<?>... parameterTypes
  ) throws NoSuchMethodException {
    Method method = AdminAccountController.class.getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
  }
}
