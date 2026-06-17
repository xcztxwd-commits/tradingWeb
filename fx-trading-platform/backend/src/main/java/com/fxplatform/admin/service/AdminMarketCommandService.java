package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentCancelRequest;
import com.fxplatform.admin.dto.request.AdminSymbolRequest;
import com.fxplatform.admin.dto.request.AdminSymbolCategoryRequest;
import com.fxplatform.admin.dto.request.AdminSymbolStatusRequest;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.admin.dto.response.AdminSymbolCategoryResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.PriceAdjustmentEntity;
import com.fxplatform.market.entity.SymbolAdminEventEntity;
import com.fxplatform.market.entity.SymbolCategoryEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.enums.PriceAdjustmentStatus;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolAdminEventRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.SymbolProductTypes;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminMarketCommandService 提供后台产品和行情管理写操作。
 */
@Service
@RequiredArgsConstructor
public class AdminMarketCommandService {

  /** 品种仓储，用于加载和保存产品状态。 */
  private final SymbolRepository symbolRepository;
  /** 产品后台事件仓储，用于记录产品参数和状态变更。 */
  private final SymbolAdminEventRepository symbolAdminEventRepository;
  /** 产品分类 Mapper，用于分类 CRUD。 */
  private final SymbolCategoryRepository symbolCategoryRepository;
  /** 行情调整仓储，用于保存显式行情修正或模拟场景。 */
  private final PriceAdjustmentRepository priceAdjustmentRepository;
  /** 审计服务，用于记录后台产品高风险操作。 */
  private final AuditLogService auditLogService;

  /**
   * 更新产品启停状态，并写入产品事件和审计日志。
   */
  @Transactional
  public AdminSymbolResponse updateStatus(UUID actorUserId, UUID symbolId, AdminSymbolStatusRequest request) {
    AdminActionAuthorization.requireAuthority(Boolean.TRUE.equals(request.enabled())
        ? AdminPermissionCatalog.MARKET_SYMBOL_UPDATE
        : AdminPermissionCatalog.MARKET_SYMBOL_DISABLE);
    if (!Boolean.TRUE.equals(request.enabled())) {
      AdminActionConfirmation.require(request.confirmationText(), AdminActionConfirmation.CONFIRM_DISABLE_SYMBOL);
    }
    SymbolEntity symbol = findSymbol(symbolId);
    String before = String.valueOf(symbol.getEnabled());
    String after = String.valueOf(request.enabled());
    symbol.setEnabled(request.enabled());
    symbolRepository.save(symbol);
    recordSymbolEvent(actorUserId, symbolId, "SYMBOL_STATUS_UPDATE", before, after, request.reason());
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_STATUS_UPDATE",
        "SYMBOL",
        symbolId.toString(),
        details(request.reason(), before, after));
    return AdminSymbolResponse.from(symbol);
  }

  /** 新增产品品种，供产品列表“新增”按钮调用。 */
  @Transactional
  public AdminSymbolResponse createSymbol(UUID actorUserId, AdminSymbolRequest request) {
    symbolRepository.findBySymbol(request.symbol())
        .ifPresent(existing -> {
          throw new BusinessException("SYMBOL_ALREADY_EXISTS", "Symbol already exists");
        });
    SymbolEntity symbol = new SymbolEntity();
    applySymbol(symbol, request);
    SymbolEntity saved = symbolRepository.save(symbol);
    recordSymbolEvent(actorUserId, saved.getId(), "SYMBOL_CREATE", null, request.symbol(), "后台新增产品");
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_CREATE",
        "SYMBOL",
        saved.getId().toString(),
        details(request.symbol(), null, request.displayName()));
    return AdminSymbolResponse.from(saved);
  }

  /** 编辑产品基础参数，保持产品列表表单和数据库字段一致。 */
  @Transactional
  public AdminSymbolResponse updateSymbol(UUID actorUserId, UUID symbolId, AdminSymbolRequest request) {
    SymbolEntity symbol = findSymbol(symbolId);
    if (!Objects.equals(symbol.getLeverage(), request.leverage())) {
      AdminActionConfirmation.require(request.confirmationText(), AdminActionConfirmation.CONFIRM_LEVERAGE_CHANGE);
    }
    String before = symbol.getSymbol() + ":" + symbol.getDisplayName() + ":" + symbol.getEnabled();
    applySymbol(symbol, request);
    SymbolEntity saved = symbolRepository.save(symbol);
    recordSymbolEvent(actorUserId, symbolId, "SYMBOL_UPDATE", before, request.symbol(), "后台编辑产品");
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_UPDATE",
        "SYMBOL",
        symbolId.toString(),
        details(request.symbol(), before, request.displayName()));
    return AdminSymbolResponse.from(saved);
  }

  /**
   * 产品删除按钮采用“下架”语义，避免历史订单、成交和K线关联数据被物理删除。
   */
  @Transactional
  public void deleteSymbol(UUID actorUserId, UUID symbolId, String reason) {
    AdminActionAuthorization.requireAuthority(AdminPermissionCatalog.MARKET_SYMBOL_DISABLE);
    SymbolEntity symbol = findSymbol(symbolId);
    String before = String.valueOf(symbol.getEnabled());
    symbol.setEnabled(false);
    symbolRepository.save(symbol);
    recordSymbolEvent(actorUserId, symbolId, "SYMBOL_DELETE", before, "false", reason);
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_DELETE",
        "SYMBOL",
        symbolId.toString(),
        details(reason, before, "false"));
  }

  /** 创建产品分类，并写入审计日志。 */
  @Transactional
  public AdminSymbolCategoryResponse createCategory(UUID actorUserId, AdminSymbolCategoryRequest request) {
    SymbolCategoryEntity category = new SymbolCategoryEntity();
    applyCategory(category, request);
    SymbolCategoryEntity saved = symbolCategoryRepository.save(category);
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_CATEGORY_CREATE",
        "SYMBOL_CATEGORY",
        saved.getId().toString(),
        details(request.name(), null, request.code()));
    return AdminSymbolCategoryResponse.from(saved);
  }

  /** 更新产品分类，后台产品分类页编辑保存时调用。 */
  @Transactional
  public AdminSymbolCategoryResponse updateCategory(
      UUID actorUserId,
      UUID categoryId,
      AdminSymbolCategoryRequest request
  ) {
    SymbolCategoryEntity category = symbolCategoryRepository.findById(categoryId)
        .orElseThrow(() -> new BusinessException("SYMBOL_CATEGORY_NOT_FOUND", "Symbol category not found"));
    String before = category.getCode();
    applyCategory(category, request);
    SymbolCategoryEntity saved = symbolCategoryRepository.save(category);
    auditLogService.record(
        actorUserId,
        "ADMIN_SYMBOL_CATEGORY_UPDATE",
        "SYMBOL_CATEGORY",
        saved.getId().toString(),
        details(request.name(), before, request.code()));
    return AdminSymbolCategoryResponse.from(saved);
  }

  @Transactional
  public AdminPriceAdjustmentResponse createPriceAdjustment(
      UUID actorUserId,
      UUID symbolId,
      AdminPriceAdjustmentRequest request
  ) {
    rejectHiddenControlMode(request.mode());
    SymbolEntity symbol = findSymbol(symbolId);

    PriceAdjustmentEntity adjustment = new PriceAdjustmentEntity();
    adjustment.setSymbolId(symbolId);
    adjustment.setSymbol(symbol.getSymbol());
    adjustment.setMode(request.mode());
    adjustment.setAdjustmentType(request.adjustmentType());
    adjustment.setTargetPrice(request.targetPrice());
    adjustment.setStartsAt(request.startsAt());
    adjustment.setEndsAt(request.endsAt());
    adjustment.setStatus(PriceAdjustmentStatus.SCHEDULED);
    adjustment.setAdminUserId(actorUserId);
    adjustment.setReason(request.reason());
    PriceAdjustmentEntity saved = priceAdjustmentRepository.save(adjustment);

    recordSymbolEvent(
        actorUserId,
        symbolId,
        "PRICE_ADJUSTMENT_CREATE",
        null,
        request.targetPrice().toPlainString(),
        request.reason());
    auditLogService.record(
        actorUserId,
        "ADMIN_PRICE_ADJUSTMENT_CREATE",
        "SYMBOL",
        symbolId.toString(),
        details(request.reason(), null, request.mode() + ":" + request.targetPrice()));
    return AdminPriceAdjustmentResponse.from(saved);
  }

  /** 取消尚未完成的涨跌/价格调整任务。 */
  @Transactional
  public AdminPriceAdjustmentResponse cancelPriceAdjustment(
      UUID actorUserId,
      UUID adjustmentId,
      AdminPriceAdjustmentCancelRequest request
  ) {
    PriceAdjustmentEntity adjustment = priceAdjustmentRepository.findById(adjustmentId)
        .orElseThrow(() -> new BusinessException("PRICE_ADJUSTMENT_NOT_FOUND", "Price adjustment not found"));
    if (adjustment.getStatus() != null && adjustment.getStatus().isCanceled()) {
      return AdminPriceAdjustmentResponse.from(adjustment);
    }
    String before = adjustment.getStatus() == null ? null : adjustment.getStatus().code();
    adjustment.setStatus(PriceAdjustmentStatus.CANCELED);
    PriceAdjustmentEntity saved = priceAdjustmentRepository.save(adjustment);
    auditLogService.record(
        actorUserId,
        "ADMIN_PRICE_ADJUSTMENT_CANCEL",
        "PRICE_ADJUSTMENT",
        saved.getId().toString(),
        details(request.reason(), before, PriceAdjustmentStatus.CANCELED.code()));
    return AdminPriceAdjustmentResponse.from(saved);
  }

  /** 将产品请求写入实体，新增和编辑共用，减少字段遗漏。 */
  private void applySymbol(SymbolEntity symbol, AdminSymbolRequest request) {
    ProductType productType = SymbolProductTypes.requireExplicit(request.productType());
    SymbolProductTypes.validateAssetClassCompatibility(productType, request.assetClass());
    symbol.setSymbol(request.symbol());
    symbol.setDisplayName(request.displayName());
    symbol.setProvider(request.provider() == null || request.provider().isBlank() ? "massive" : request.provider());
    symbol.setProviderSymbol(request.providerSymbol() == null || request.providerSymbol().isBlank()
        ? request.symbol()
        : request.providerSymbol());
    symbol.setAssetClass(request.assetClass());
    symbol.setProductType(productType);
    symbol.setBaseCurrency(request.baseCurrency());
    symbol.setQuoteCurrency(request.quoteCurrency());
    symbol.setPipSize(request.pipSize());
    symbol.setTickSize(request.tickSize());
    symbol.setLotSize(request.lotSize());
    symbol.setMinLot(request.minLot());
    symbol.setMaxLot(request.maxLot());
    symbol.setLeverage(request.leverage());
    symbol.setSpreadMarkup(request.spreadMarkup());
    symbol.setEnabled(request.enabled());
    symbol.setIconUrl(request.iconUrl());
    symbol.setDisplayEnabled(defaultTrue(request.displayEnabled()));
    symbol.setQuoteEnabled(defaultTrue(request.quoteEnabled()));
    symbol.setChartEnabled(defaultTrue(request.chartEnabled()));
    symbol.setOrderBookEnabled(defaultTrue(request.orderBookEnabled()));
    symbol.setTradable(defaultTrue(request.tradable()));
    symbol.setFeatured(Boolean.TRUE.equals(request.featured()));
    symbol.setDisplayGroup(request.displayGroup());
    symbol.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
  }

  private boolean defaultTrue(Boolean value) {
    return value == null || value;
  }

  private void applyCategory(SymbolCategoryEntity category, AdminSymbolCategoryRequest request) {
    category.setName(request.name());
    category.setCode(request.code());
    category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    category.setEnabled(request.enabled());
  }

  private SymbolEntity findSymbol(UUID symbolId) {
    return symbolRepository.findById(symbolId)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
  }

  /**
   * 拒绝隐蔽控价模式，确保价格调整只能作为显式修正或模拟场景存在。
   */
  private void rejectHiddenControlMode(String mode) {
    if ("HIDDEN_CONTROL".equalsIgnoreCase(mode)) {
      throw new BusinessException("HIDDEN_PRICE_CONTROL_NOT_ALLOWED", "Hidden price control is not allowed");
    }
  }

  /**
   * 记录产品后台事件，便于产品详情页展示变更时间线。
   */
  private void recordSymbolEvent(
      UUID actorUserId,
      UUID symbolId,
      String eventType,
      String before,
      String after,
      String reason
  ) {
    SymbolAdminEventEntity event = new SymbolAdminEventEntity();
    event.setSymbolId(symbolId);
    event.setAdminUserId(actorUserId);
    event.setEventType(eventType);
    event.setBeforeValue(before);
    event.setAfterValue(after);
    event.setReason(reason);
    symbolAdminEventRepository.save(event);
  }

  /**
   * 构造审计 JSON 明细。
   */
  private String details(String reason, String before, String after) {
    return AuditDetailsBuilder.create()
        .put("reason", reason)
        .put("before", before)
        .put("after", after)
        .toJson();
  }
}
