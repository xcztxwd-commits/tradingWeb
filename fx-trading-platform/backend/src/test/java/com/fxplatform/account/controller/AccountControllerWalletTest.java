package com.fxplatform.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
        "USDT",
        new BigDecimal("-5000.00000000"),
        new BigDecimal("5000.00000000"),
        "SPOT_BUY_QUOTE_OUT",
        "ORDER",
        referenceId,
        "Spot buy quote spent",
        from.plusSeconds(10));
    AccountService accountService = org.mockito.Mockito.mock(AccountService.class);
    when(accountService.assetLedger(userId, accountId, "usdt", "SPOT_BUY_QUOTE_OUT", referenceId, from, to))
        .thenReturn(List.of(entry));
    AccountController controller = new AccountController(accountService);

    var response = controller.assetLedger(
        new UserPrincipal(userId, "trader@example.com", "TRADER"),
        accountId,
        "usdt",
        "SPOT_BUY_QUOTE_OUT",
        referenceId,
        from,
        to);

    assertThat(response.data()).containsExactly(entry);
  }
}
