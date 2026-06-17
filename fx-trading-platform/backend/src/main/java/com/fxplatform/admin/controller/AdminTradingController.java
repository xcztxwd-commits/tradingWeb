package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.admin.dto.response.AdminOrderResponse;
import com.fxplatform.admin.dto.response.AdminPositionResponse;
import com.fxplatform.admin.dto.response.AdminTradeResponse;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminTradingCommandService;
import com.fxplatform.admin.service.AdminTradingQueryService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.response.PositionResponse;
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
 * 后台交易管理接口，负责订单、持仓和成交记录的查询与后台操作入口。
 */
@RestController
@RequestMapping("/api/admin/trading")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTradingController {

  /** 后台交易只读查询服务，统一返回 DTO，避免 Controller 暴露实体。 */
  private final AdminTradingQueryService adminTradingQueryService;
  /** 后台交易写操作服务，保留审计、幂等和交易状态变更逻辑。 */
  private final AdminTradingCommandService adminTradingCommandService;

  /** 分页查询全部订单。 */
  @GetMapping("/orders")
  public ApiResponse<AdminPageResponse<AdminOrderResponse>> orders(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminTradingQueryService.orders(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 分页查询全部持仓。 */
  @GetMapping("/positions")
  public ApiResponse<AdminPageResponse<AdminPositionResponse>> positions(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminTradingQueryService.positions(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 分页查询全部成交记录，供后台成交记录页面直接读取。 */
  @GetMapping("/trades")
  public ApiResponse<AdminPageResponse<AdminTradeResponse>> trades(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminTradingQueryService.trades(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 后台取消订单，并写入审计日志。 */
  @PreAuthorize("hasAuthority('trading:order:cancel')")
  @PostMapping("/orders/{orderId}/cancel")
  public ApiResponse<AdminOrderResponse> cancelOrder(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID orderId,
      @Valid @RequestBody AdminCancelOrderRequest request
  ) {
    return ApiResponse.success(adminTradingCommandService.cancelOrder(principal.id(), orderId, request));
  }

  /** 后台强制平仓，并写入审计日志。 */
  @PreAuthorize("hasAuthority('trading:position:force-close')")
  @PostMapping("/positions/{positionId}/force-close")
  public ApiResponse<PositionResponse> forceClosePosition(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID positionId,
      @Valid @RequestBody AdminForceClosePositionRequest request
  ) {
    return ApiResponse.success(adminTradingCommandService.forceClosePosition(principal.id(), positionId, request));
  }
}
