package com.fxplatform.account.controller;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.dto.AccountTransferRequest;
import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.dto.DemoResetRequest;
import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.service.AccountService;
import com.fxplatform.account.service.AccountTransferQueryService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.response.TradingPageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private AccountTransferQueryService accountTransferQueryService;

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
      @RequestParam(required = false) String walletType,
      @RequestParam(required = false) String asset,
      @RequestParam(required = false) String entryType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to
  ) {
    return ApiResponse.success(accountService.assetLedger(
        principal.id(),
        accountId,
        walletType,
        asset,
        entryType,
        referenceId,
        from,
        to));
  }

  @PostMapping("/{accountId}/asset-conversions")
  public ApiResponse<AssetConversionResponse> convertAsset(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AssetConversionRequest request
  ) {
    return ApiResponse.success(accountService.convertAsset(principal.id(), accountId, request));
  }

  @PostMapping("/{id}/transfers")
  public ApiResponse<AccountTransferResponse> transfer(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("id") UUID accountId,
      @Valid @RequestBody AccountTransferRequest request
  ) {
    return ApiResponse.success(accountService.transfer(principal.id(), accountId, request));
  }

  @GetMapping("/{id}/transfers")
  public ApiResponse<TradingPageResponse<AccountTransferResponse>> transferHistory(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("id") UUID accountId,
      @RequestParam(required = false) Direction direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.success(accountTransferQueryService.history(
        principal.id(), accountId, direction, page, size));
  }

  @Autowired
  void setAccountTransferQueryService(AccountTransferQueryService accountTransferQueryService) {
    this.accountTransferQueryService = accountTransferQueryService;
  }

  @PostMapping("/{id}/demo-reset")
  public ApiResponse<DemoResetResponse> resetDemo(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("id") UUID accountId,
      @Valid @RequestBody DemoResetRequest request
  ) {
    return ApiResponse.success(accountService.resetDemo(principal.id(), accountId, request.requestId()));
  }

  /**
   * 处理 createDemo 提交接口请求。
   */
  @PostMapping("/demo")
  public ApiResponse<AccountResponse> createDemo(@AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(accountService.toResponse(accountService.createDemoAccount(principal.id())));
  }
}
