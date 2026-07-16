package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentCancelRequest;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminFundingConfigRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.request.AdminSymbolRequest;
import com.fxplatform.admin.dto.request.AdminSymbolCategoryRequest;
import com.fxplatform.admin.dto.request.AdminSymbolStatusRequest;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.admin.dto.response.AdminFundingConfigResponse;
import com.fxplatform.admin.dto.response.AdminSymbolCategoryResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminMarketCommandService;
import com.fxplatform.admin.service.AdminMarketQueryService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.dto.MarketStatusResponse;
import com.fxplatform.market.service.QuoteService;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminMarketController 提供后台产品和行情管理接口。
 */
@RestController
@RequestMapping("/api/admin/market")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminMarketController {

  /** 后台产品查询服务。 */
  private final AdminMarketQueryService adminMarketQueryService;
  /** 后台产品命令服务。 */
  private final AdminMarketCommandService adminMarketCommandService;
  /** 报价服务，用于后台查看行情源健康状态。 */
  private final QuoteService quoteService;

  /**
   * 分页查询全部交易品种。
   */
  @GetMapping("/symbols")
  public ApiResponse<AdminPageResponse<AdminSymbolResponse>> symbols(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminMarketQueryService.symbols(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 新增产品品种，供产品列表新增弹窗调用。 */
  @PreAuthorize("hasAuthority('market:symbol:create')")
  @PostMapping("/symbols")
  public ApiResponse<AdminSymbolResponse> createSymbol(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminSymbolRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.createSymbol(principal.id(), request));
  }

  /** 编辑产品品种，供产品列表编辑弹窗调用。 */
  @PreAuthorize("hasAuthority('market:symbol:update')")
  @PutMapping("/symbols/{symbolId}")
  public ApiResponse<AdminSymbolResponse> updateSymbol(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminSymbolRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.updateSymbol(principal.id(), symbolId, request));
  }

  @PreAuthorize("hasAuthority('market:symbol:update')")
  @GetMapping("/symbols/{id}/funding-config")
  public ApiResponse<AdminFundingConfigResponse> fundingConfig(
      @PathVariable("id") UUID symbolId
  ) {
    return ApiResponse.success(adminMarketQueryService.fundingConfig(symbolId));
  }

  @PreAuthorize("hasAuthority('market:symbol:update')")
  @PutMapping("/symbols/{id}/funding-config")
  public ApiResponse<AdminFundingConfigResponse> updateFundingConfig(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("id") UUID symbolId,
      @Valid @RequestBody AdminFundingConfigRequest request
  ) {
    return ApiResponse.success(
        adminMarketCommandService.updateFundingConfig(principal.id(), symbolId, request));
  }

  /** 删除产品采用下架语义，避免破坏历史交易数据。 */
  @PreAuthorize("hasAuthority('market:symbol:disable')")
  @DeleteMapping("/symbols/{symbolId}")
  public ApiResponse<Void> deleteSymbol(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminMarketCommandService.deleteSymbol(principal.id(), symbolId, request.reason());
    return ApiResponse.success(null);
  }

  /** 分页查询产品分类。 */
  @GetMapping("/categories")
  public ApiResponse<AdminPageResponse<AdminSymbolCategoryResponse>> categories(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminMarketQueryService.categories(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 创建产品分类。 */
  @PostMapping("/categories")
  public ApiResponse<AdminSymbolCategoryResponse> createCategory(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminSymbolCategoryRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.createCategory(principal.id(), request));
  }

  /** 更新产品分类。 */
  @PutMapping("/categories/{categoryId}")
  public ApiResponse<AdminSymbolCategoryResponse> updateCategory(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID categoryId,
      @Valid @RequestBody AdminSymbolCategoryRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.updateCategory(principal.id(), categoryId, request));
  }

  /**
   * 查询后台行情状态。
   */
  @GetMapping("/status")
  public ApiResponse<MarketStatusResponse> status() {
    return ApiResponse.success(quoteService.status());
  }

  /**
   * 修改产品启停状态。
   */
  @PreAuthorize("hasAnyAuthority('market:symbol:update','market:symbol:disable')")
  @PatchMapping("/symbols/{symbolId}/status")
  public ApiResponse<AdminSymbolResponse> updateStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminSymbolStatusRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.updateStatus(principal.id(), symbolId, request));
  }

  /**
   * 创建显式行情调整记录。
   */
  @PostMapping("/symbols/{symbolId}/price-adjustments")
  public ApiResponse<AdminPriceAdjustmentResponse> createPriceAdjustment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminPriceAdjustmentRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.createPriceAdjustment(principal.id(), symbolId, request));
  }

  /** 查询最近涨跌/价格调整任务。 */
  @GetMapping("/price-adjustments")
  public ApiResponse<List<AdminPriceAdjustmentResponse>> priceAdjustments(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int size
  ) {
    return ApiResponse.success(adminMarketQueryService.priceAdjustments(status, size));
  }

  /** 取消涨跌/价格调整任务。 */
  @PostMapping("/price-adjustments/{adjustmentId}/cancel")
  public ApiResponse<AdminPriceAdjustmentResponse> cancelPriceAdjustment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID adjustmentId,
      @Valid @RequestBody AdminPriceAdjustmentCancelRequest request
  ) {
    return ApiResponse.success(adminMarketCommandService.cancelPriceAdjustment(principal.id(), adjustmentId, request));
  }
}
