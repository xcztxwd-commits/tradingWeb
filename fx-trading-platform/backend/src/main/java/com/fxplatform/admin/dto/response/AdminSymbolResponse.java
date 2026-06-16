package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.service.SymbolProductTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminSymbolResponse 是后台产品和行情品种响应 DTO。
 *
 * @param id 品种 ID。
 * @param symbol 平台标准品种代码。
 * @param displayName 前端展示名称。
 * @param provider 行情供应商。
 * @param providerSymbol 供应商侧品种代码。
 * @param assetClass 资产类别。
 * @param baseCurrency 基础币种。
 * @param quoteCurrency 计价币种。
 * @param pipSize 点值大小。
 * @param tickSize 最小价格跳动。
 * @param lotSize 标准手大小。
 * @param minLot 最小手数。
 * @param maxLot 最大手数。
 * @param leverage 默认杠杆。
 * @param spreadMarkup 点差加成。
 * @param enabled 是否对用户端启用。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminSymbolResponse(
    UUID id,
    String symbol,
    String displayName,
    String provider,
    String providerSymbol,
    String assetClass,
    ProductType productType,
    String baseCurrency,
    String quoteCurrency,
    BigDecimal pipSize,
    BigDecimal tickSize,
    BigDecimal lotSize,
    BigDecimal minLot,
    BigDecimal maxLot,
    Integer leverage,
    BigDecimal spreadMarkup,
    Boolean enabled,
    String iconUrl,
    Boolean displayEnabled,
    Boolean quoteEnabled,
    Boolean chartEnabled,
    Boolean orderBookEnabled,
    Boolean tradable,
    Boolean featured,
    String displayGroup,
    Integer displayOrder,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将品种实体映射为后台品种 DTO。
   */
  public static AdminSymbolResponse from(SymbolEntity entity) {
    return new AdminSymbolResponse(
        entity.getId(),
        entity.getSymbol(),
        entity.getDisplayName(),
        entity.getProvider(),
        entity.getProviderSymbol(),
        entity.getAssetClass(),
        SymbolProductTypes.readOrLegacy(entity),
        entity.getBaseCurrency(),
        entity.getQuoteCurrency(),
        entity.getPipSize(),
        entity.getTickSize(),
        entity.getLotSize(),
        entity.getMinLot(),
        entity.getMaxLot(),
        entity.getLeverage(),
        entity.getSpreadMarkup(),
        entity.getEnabled(),
        entity.getIconUrl(),
        entity.getDisplayEnabled(),
        entity.getQuoteEnabled(),
        entity.getChartEnabled(),
        entity.getOrderBookEnabled(),
        entity.getTradable(),
        entity.getFeatured(),
        entity.getDisplayGroup(),
        entity.getDisplayOrder(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
