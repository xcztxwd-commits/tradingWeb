package com.fxplatform.account.controller;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.service.AccountService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AccountController 是账户模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

  private final AccountService accountService;

  /**
   * 处理 accounts 查询接口请求。
   */
  @GetMapping
  public ApiResponse<List<AccountResponse>> accounts(@AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(accountService.accountsForUser(principal.id()));
  }

  /**
   * 处理 summary 查询接口请求。
   */
  @GetMapping("/{accountId}/summary")
  public ApiResponse<AccountResponse> summary(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(accountService.summary(principal.id(), accountId));
  }

  @GetMapping("/{accountId}/wallet-balances")
  public ApiResponse<List<WalletBalanceResponse>> walletBalances(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(accountService.walletBalances(principal.id(), accountId));
  }

  @GetMapping("/{accountId}/asset-ledger")
  public ApiResponse<List<AssetLedgerEntryResponse>> assetLedger(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @RequestParam(required = false) String asset,
      @RequestParam(required = false) String entryType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to
  ) {
    return ApiResponse.success(accountService.assetLedger(
        principal.id(),
        accountId,
        asset,
        entryType,
        referenceId,
        from,
        to));
  }

  /**
   * 处理 createDemo 提交接口请求。
   */
  @PostMapping("/demo")
  public ApiResponse<AccountResponse> createDemo(@AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(accountService.toResponse(accountService.createDemoAccount(principal.id())));
  }
}
