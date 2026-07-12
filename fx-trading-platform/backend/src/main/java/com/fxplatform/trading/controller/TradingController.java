package com.fxplatform.trading.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdatePositionProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.dto.response.AdjustPositionMarginResponse;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.OcoOrderService;
import com.fxplatform.trading.service.PositionService;
import com.fxplatform.trading.service.PositionMarginService;
import com.fxplatform.trading.service.ProtectionOrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TradingController 是交易模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {

  private final OrderService orderService;
  private final PositionService positionService;
  private final OcoOrderService ocoOrderService;
  private final PositionMarginService positionMarginService;
  private ProtectionOrderService protectionOrderService;

  /**
   * 处理 createOrder 提交接口请求。
   */
  @PostMapping("/orders")
  public ApiResponse<OrderResponse> createOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateOrderRequest request
  ) {
    return ApiResponse.success(orderService.createOrder(principal, request));
  }

  @PostMapping("/oco")
  public ApiResponse<OcoOrderGroupResponse> createOco(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateOcoOrderRequest request
  ) {
    return ApiResponse.success(ocoOrderService.create(principal, request));
  }

  /**
   * 处理 orders 查询接口请求。
   */
  @GetMapping("/orders")
  public ApiResponse<List<OrderResponse>> orders(@AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(orderService.orders(principal));
  }

  @GetMapping("/orders/{orderId}/events")
  public ApiResponse<List<OrderEventResponse>> orderEvents(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId
  ) {
    return ApiResponse.success(orderService.orderEvents(principal, orderId));
  }

  @PostMapping("/orders/{orderId}/cancel")
  public ApiResponse<OrderResponse> cancelOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId
  ) {
    return ApiResponse.success(orderService.cancelOrder(principal, orderId));
  }

  @PatchMapping("/orders/{orderId}")
  public ApiResponse<OrderResponse> modifyOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId,
      @Valid @RequestBody UpdateOrderRequest request
  ) {
    return ApiResponse.success(orderService.modifyOrder(principal, orderId, request));
  }

  /**
   * 处理 positions 查询接口请求。
   */
  @GetMapping("/positions")
  public ApiResponse<List<PositionResponse>> positions(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(positionService.openPositions(principal.id(), accountId));
  }

  @GetMapping("/positions/history")
  public ApiResponse<List<PositionResponse>> positionHistory(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(positionService.positionHistory(principal.id(), accountId));
  }

  @PostMapping("/positions/{positionId}/margin")
  public ApiResponse<AdjustPositionMarginResponse> adjustPositionMargin(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID positionId,
      @Valid @RequestBody AdjustPositionMarginRequest request
  ) {
    return ApiResponse.success(positionMarginService.adjust(principal.id(), positionId, request));
  }

  @PatchMapping("/positions/{positionId}/protection")
  public ApiResponse<PositionResponse> updatePositionProtection(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId,
      @PathVariable UUID positionId,
      @Valid @RequestBody UpdatePositionProtectionRequest request
  ) {
    return ApiResponse.success(positionService.updateProtection(principal.id(), accountId, positionId, request));
  }

  /**
   * 处理 closePosition 提交接口请求。
   */
  @PostMapping("/positions/{positionId}/close")
  public ApiResponse<PositionResponse> closePosition(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId,
      @PathVariable UUID positionId,
      @RequestBody(required = false) ClosePositionRequest request
  ) {
    ClosePositionRequest normalizedRequest = request != null && request.isEmpty()
        ? null
        : request;
    return ApiResponse.success(positionService.closePosition(
        principal.id(), accountId, positionId, normalizedRequest));
  }

  @PostMapping("/positions/{positionId}/protections")
  public ApiResponse<OrderResponse> createProtection(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID positionId,
      @Valid @RequestBody CreateProtectionRequest request
  ) {
    return ApiResponse.success(protectionOrderService.create(principal.id(), positionId, request));
  }

  @PatchMapping("/protections/{orderId}")
  public ApiResponse<OrderResponse> updateProtection(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId,
      @Valid @RequestBody UpdateProtectionRequest request
  ) {
    return ApiResponse.success(protectionOrderService.update(principal.id(), orderId, request));
  }

  @DeleteMapping("/protections/{orderId}")
  public ApiResponse<OrderResponse> cancelProtection(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId
  ) {
    return ApiResponse.success(protectionOrderService.cancel(principal.id(), orderId));
  }

  @Autowired
  void setProtectionOrderService(ProtectionOrderService protectionOrderService) {
    this.protectionOrderService = protectionOrderService;
  }
}
