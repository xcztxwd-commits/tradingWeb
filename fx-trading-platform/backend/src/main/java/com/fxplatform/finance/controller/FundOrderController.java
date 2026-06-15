package com.fxplatform.finance.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.finance.dto.request.FundOrderRequest;
import com.fxplatform.finance.dto.response.FundOrderResponse;
import com.fxplatform.finance.service.FundOrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance/fund-orders")
@RequiredArgsConstructor
public class FundOrderController {

  private final FundOrderService fundOrderService;

  @GetMapping
  public ApiResponse<List<FundOrderResponse>> orders(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(fundOrderService.orders(principal.id(), accountId));
  }

  @PostMapping
  public ApiResponse<FundOrderResponse> createOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody FundOrderRequest request
  ) {
    return ApiResponse.success(fundOrderService.createOrder(principal.id(), request));
  }
}
