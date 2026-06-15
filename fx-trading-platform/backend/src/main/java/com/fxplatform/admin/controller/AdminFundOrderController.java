package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminFundOrderRequest;
import com.fxplatform.admin.dto.request.AdminFundOrderReviewRequest;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminFundOrderResponse;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminFundOrderService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.Map;
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

/**
 * AdminFundOrderController 提供充值/提现审核订单 API。
 */
@RestController
@RequestMapping("/api/admin/finance/fund-orders")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminFundOrderController {

  private final AdminFundOrderService adminFundOrderService;

  /** 查询最近充值/提现审核订单。 */
  @GetMapping
  public ApiResponse<AdminPageResponse<AdminFundOrderResponse>> orders(
      @RequestParam(required = false) String orderType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminFundOrderService.orders(
        orderType,
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 创建待审核资金订单。 */
  @PostMapping
  public ApiResponse<AdminFundOrderResponse> createOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminFundOrderRequest request
  ) {
    return ApiResponse.success(adminFundOrderService.createOrder(principal.id(), request));
  }

  /** 审核资金订单。 */
  @PostMapping("/{orderId}/review")
  public ApiResponse<AdminFundOrderResponse> reviewOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId,
      @Valid @RequestBody AdminFundOrderReviewRequest request
  ) {
    return ApiResponse.success(adminFundOrderService.reviewOrder(principal.id(), orderId, request));
  }
}
