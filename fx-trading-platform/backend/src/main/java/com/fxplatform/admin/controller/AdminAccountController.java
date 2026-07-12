package com.fxplatform.admin.controller;

import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminAccountCleanupRequest;
import com.fxplatform.admin.dto.request.AdminDemoResetRequest;
import com.fxplatform.admin.dto.response.AdminAccountResponse;
import com.fxplatform.admin.service.AdminAccountCleanupService;
import com.fxplatform.admin.service.AdminAccountQueryService;
import com.fxplatform.admin.service.AdminActionConfirmation;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin account reads and account-scoped high-risk commands. */
@RestController
@RequestMapping("/api/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccountController {

  private final AdminAccountQueryService adminAccountQueryService;
  private final AdminAccountCleanupService adminAccountCleanupService;
  private final DemoAccountLifecycleService demoAccountLifecycleService;

  @GetMapping
  public ApiResponse<AdminPageResponse<AdminAccountResponse>> accounts(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.success(adminAccountQueryService.accounts(page, size));
  }

  @GetMapping("/{accountId}/wallet-balances")
  public ApiResponse<List<WalletBalanceResponse>> walletBalances(
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(adminAccountQueryService.walletBalances(accountId));
  }

  @GetMapping("/{accountId}/asset-ledger")
  public ApiResponse<List<AssetLedgerEntryResponse>> assetLedger(
      @PathVariable UUID accountId,
      @RequestParam(required = false) String walletType,
      @RequestParam(required = false) String asset,
      @RequestParam(required = false) String entryType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to
  ) {
    return ApiResponse.success(adminAccountQueryService.assetLedger(
        accountId,
        walletType,
        asset,
        entryType,
        referenceId,
        from,
        to));
  }

  @GetMapping("/{accountId}/funding-settlements")
  public ApiResponse<List<FundingSettlementEntity>> fundingSettlements(
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(adminAccountQueryService.fundingSettlements(accountId));
  }

  @GetMapping("/{accountId}/ledger")
  public ApiResponse<List<LedgerEntryResponse>> ledger(
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(adminAccountQueryService.ledger(accountId));
  }

  @PreAuthorize("hasAuthority('trading:account:force-cleanup')")
  @PostMapping("/{accountId}/force-cleanup")
  public ApiResponse<BatchActionResponse> forceCleanup(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AdminAccountCleanupRequest request
  ) {
    AdminActionConfirmation.require(
        request.confirmationText(),
        AdminActionConfirmation.CONFIRM_FORCE_CLEANUP);
    return ApiResponse.success(adminAccountCleanupService.cleanup(
        principal.id(),
        accountId,
        request.reason(),
        request.requestId().toString()));
  }

  @PreAuthorize("hasAuthority('trading:account:demo-reset')")
  @PostMapping("/{accountId}/demo-reset")
  public ApiResponse<DemoResetResponse> resetDemo(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AdminDemoResetRequest request
  ) {
    AdminActionConfirmation.require(
        request.confirmationText(),
        AdminActionConfirmation.CONFIRM_DEMO_RESET);
    return ApiResponse.success(demoAccountLifecycleService.resetAsAdmin(
        principal.id(),
        accountId,
        request.requestId(),
        request.reason()));
  }
}
