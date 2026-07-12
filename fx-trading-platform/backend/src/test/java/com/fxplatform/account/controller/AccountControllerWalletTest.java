package com.fxplatform.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AccountTransferRequest;
import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.dto.DemoResetRequest;
import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.service.AccountService;
import com.fxplatform.common.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountControllerWalletTest {

  @Test
  void walletBalancesEndpointReturnsOwnedAccountWalletBalances() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WalletBalanceResponse wallet = new WalletBalanceResponse(
        UUID.randomUUID(),
        accountId,
        "FX_MARGIN",
        "USDT",
        new BigDecimal("10000.00000000"),
        new BigDecimal("9000.00000000"),
        new BigDecimal("1000.00000000"));
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.walletBalances(userId, accountId)).thenReturn(List.of(wallet));
    AccountController controller = new AccountController(accountService);

    var response = controller.walletBalances(new UserPrincipal(userId, "trader@example.com", "TRADER"), accountId);

    assertThat(response.data()).containsExactly(wallet);
  }

  @Test
  void assetLedgerEndpointPassesFiltersToAccountService() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    Instant from = Instant.parse("2026-06-16T00:00:00Z");
    Instant to = Instant.parse("2026-06-16T23:59:59Z");
    AssetLedgerEntryResponse entry = new AssetLedgerEntryResponse(
        UUID.randomUUID(),
        accountId,
        "USDT_PERP",
        "USDT",
        new BigDecimal("-5000.00000000"),
        new BigDecimal("5000.00000000"),
        "SPOT_BUY_QUOTE_OUT",
        "ORDER",
        referenceId,
        "Spot buy quote spent",
        from.plusSeconds(10));
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.assetLedger(userId, accountId, "USDT_PERP", "usdt", "SPOT_BUY_QUOTE_OUT", referenceId, from, to))
        .thenReturn(List.of(entry));
    AccountController controller = new AccountController(accountService);

    var response = controller.assetLedger(
        new UserPrincipal(userId, "trader@example.com", "TRADER"),
        accountId,
        "USDT_PERP",
        "usdt",
        "SPOT_BUY_QUOTE_OUT",
        referenceId,
        from,
        to);

    assertThat(response.data()).containsExactly(entry);
  }

  @Test
  void assetConversionEndpointPassesRequestToAccountService() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID conversionId = UUID.randomUUID();
    AssetConversionRequest request = new AssetConversionRequest(
        "USDT_PERP",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);
    AssetConversionResponse conversion = new AssetConversionResponse(
        accountId,
        "USDT_PERP",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        new BigDecimal("250.00000000"),
        BigDecimal.ONE,
        conversionId);
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.convertAsset(eq(userId), eq(accountId), eq(request))).thenReturn(conversion);
    AccountController controller = new AccountController(accountService);

    var response = controller.convertAsset(
        new UserPrincipal(userId, "trader@example.com", "TRADER"),
        accountId,
        request);

    assertThat(response.data()).isEqualTo(conversion);
  }

  @Test
  void transferEndpointBindsTheAuthenticatedOwnerAndRequestId() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AccountTransferRequest request = new AccountTransferRequest(
        Direction.SPOT_TO_PERP, new BigDecimal("250.00000000"), requestId);
    AccountTransferResponse expected = new AccountTransferResponse(
        accountId, requestId, Direction.SPOT_TO_PERP, request.amount(),
        new BigDecimal("49750.00000000"), new BigDecimal("50250.00000000"),
        new BigDecimal("50250.00000000"), false, Instant.now());
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.transfer(userId, accountId, request)).thenReturn(expected);
    AccountController controller = new AccountController(accountService);

    var response = controller.transfer(
        new UserPrincipal(userId, "trader@example.com", "TRADER"), accountId, request);

    assertThat(response.data()).isEqualTo(expected);
  }

  @Test
  void demoResetEndpointBindsTheAuthenticatedOwnerAndRequestId() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    DemoResetRequest request = new DemoResetRequest(requestId);
    DemoResetResponse expected = new DemoResetResponse(
        accountId, requestId, 2L, new BigDecimal("50000.00000000"),
        new BigDecimal("50000.00000000"), new BigDecimal("50000.00000000"),
        Instant.now(), false);
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.resetDemo(userId, accountId, requestId)).thenReturn(expected);
    AccountController controller = new AccountController(accountService);

    var response = controller.resetDemo(
        new UserPrincipal(userId, "trader@example.com", "TRADER"), accountId, request);

    assertThat(response.data()).isEqualTo(expected);
  }

  @Test
  void transferHistoryEndpointUsesTheAuthenticatedOwner() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.transferHistory(userId, accountId)).thenReturn(List.of());
    AccountController controller = new AccountController(accountService);

    var response = controller.transferHistory(
        new UserPrincipal(userId, "trader@example.com", "TRADER"), accountId);

    assertThat(response.data()).isEmpty();
  }
}
