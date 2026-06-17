package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminBalanceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminPaymentMethodRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import com.fxplatform.admin.dto.response.AdminLedgerEntryResponse;
import com.fxplatform.admin.dto.response.AdminPaymentMethodResponse;
import com.fxplatform.admin.service.AdminFinanceCommandService;
import com.fxplatform.admin.service.AdminFinanceQueryService;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminPaymentMethodQueryService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminFinanceController 提供后台财务管理接口。
 */
@RestController
@RequestMapping("/api/admin/finance")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminFinanceController {

  /** 后台财务查询服务。 */
  private final AdminFinanceQueryService adminFinanceQueryService;
  /** 后台财务写操作服务。 */
  private final AdminFinanceCommandService adminFinanceCommandService;
  /** 支付方式查询服务。 */
  private final AdminPaymentMethodQueryService paymentMethodQueryService;

  /**
   * 分页查询全部资金流水。
   */
  @GetMapping("/ledger")
  public ApiResponse<AdminPageResponse<AdminLedgerEntryResponse>> ledger(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminFinanceQueryService.ledger(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /**
   * 分页查询支付方式配置。
   */
  @GetMapping("/payment-methods")
  public ApiResponse<AdminPageResponse<AdminPaymentMethodResponse>> paymentMethods(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(paymentMethodQueryService.paymentMethods(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /**
   * 创建支付方式配置。
   */
  @PostMapping("/payment-methods")
  public ApiResponse<AdminPaymentMethodResponse> createPaymentMethod(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminPaymentMethodRequest request
  ) {
    return ApiResponse.success(adminFinanceCommandService.createPaymentMethod(principal.id(), request));
  }

  /**
   * 更新支付方式配置。
   */
  @PatchMapping("/payment-methods/{paymentMethodId}")
  public ApiResponse<AdminPaymentMethodResponse> updatePaymentMethod(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID paymentMethodId,
      @Valid @RequestBody AdminPaymentMethodRequest request
  ) {
    return ApiResponse.success(adminFinanceCommandService.updatePaymentMethod(principal.id(), paymentMethodId, request));
  }

  /** 删除收款方式配置，供 WH 收款方式列表删除按钮调用。 */
  @DeleteMapping("/payment-methods/{paymentMethodId}")
  public ApiResponse<Void> deletePaymentMethod(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID paymentMethodId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminFinanceCommandService.deletePaymentMethod(principal.id(), paymentMethodId, request.reason());
    return ApiResponse.success(null);
  }

  /**
   * 后台人工入金。
   */
  @PostMapping("/accounts/{accountId}/deposit")
  public ApiResponse<AdminFundOperationResponse> deposit(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AdminFundOperationRequest request
  ) {
    return ApiResponse.success(adminFinanceCommandService.deposit(principal.id(), accountId, request));
  }

  /**
   * 后台人工出金或扣款。
   */
  @PostMapping("/accounts/{accountId}/withdraw")
  public ApiResponse<AdminFundOperationResponse> withdraw(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AdminFundOperationRequest request
  ) {
    return ApiResponse.success(adminFinanceCommandService.withdraw(principal.id(), accountId, request));
  }

  /**
   * 后台余额调整。
   */
  @PreAuthorize("hasAuthority('finance:adjustment:create')")
  @PostMapping("/accounts/{accountId}/adjustments")
  public ApiResponse<AdminFundOperationResponse> adjustBalance(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody AdminBalanceAdjustmentRequest request
  ) {
    return ApiResponse.success(adminFinanceCommandService.adjustBalance(principal.id(), accountId, request));
  }
}
