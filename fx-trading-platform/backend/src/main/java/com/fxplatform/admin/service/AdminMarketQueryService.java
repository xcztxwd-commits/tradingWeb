package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.admin.dto.response.AdminSymbolCategoryResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import com.fxplatform.market.entity.SymbolCategoryEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminMarketQueryService 提供后台产品和行情品种只读查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminMarketQueryService {

  /** 品种 Mapper，用于后台产品列表分页查询。 */
  private final SymbolRepository symbolRepository;
  private final SymbolCategoryRepository symbolCategoryRepository;
  private final PriceAdjustmentRepository priceAdjustmentRepository;

  /** 分页查询全部产品品种，按 symbol 升序返回。 */
  public AdminPageResponse<AdminSymbolResponse> symbols(int page, int size) {
    return AdminPageResponse.from(symbolRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("symbol", "symbol"), "symbol", true)
        .convert(AdminSymbolResponse::from));
  }

  /** 按截图产品列表协议分页筛选产品。 */
  public AdminPageResponse<AdminSymbolResponse> symbols(AdminFeaturePageQuery query) {
    QueryWrapper<SymbolEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "symbol", "symbol");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "productName", "display_name");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "displayName", "display_name");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "assetClass", "asset_class");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "category", "asset_class");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "enabled", "enabled");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "status", "enabled");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "symbol", "symbol",
        "productName", "display_name",
        "displayName", "display_name",
        "assetClass", "asset_class",
        "category", "asset_class",
        "leverage", "leverage",
        "enabled", "enabled",
        "status", "enabled",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "symbol", true);
    return AdminPageResponse.from(symbolRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminSymbolResponse::from));
  }

  /** 分页查询产品分类，供后台产品分类页使用。 */
  public AdminPageResponse<AdminSymbolCategoryResponse> categories(int page, int size) {
    return AdminPageResponse.from(symbolCategoryRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("sortOrder", "sort_order"), "sortOrder", true)
        .convert(AdminSymbolCategoryResponse::from));
  }

  /** 按截图分类列表协议分页筛选产品分类。 */
  public AdminPageResponse<AdminSymbolCategoryResponse> categories(AdminFeaturePageQuery query) {
    QueryWrapper<SymbolCategoryEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "name", "name");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "code", "code");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "enabled", "enabled");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "status", "enabled");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "name", "name",
        "code", "code",
        "sortOrder", "sort_order",
        "enabled", "enabled",
        "status", "enabled",
        "updatedAt", "updated_at"), "sort_order", true);
    return AdminPageResponse.from(symbolCategoryRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminSymbolCategoryResponse::from));
  }

  /** 查询最近涨跌/价格调整任务。 */
  public List<AdminPriceAdjustmentResponse> priceAdjustments(String status, int size) {
    return priceAdjustmentRepository.findRecent(status, size)
        .stream()
        .map(AdminPriceAdjustmentResponse::from)
        .toList();
  }
}
