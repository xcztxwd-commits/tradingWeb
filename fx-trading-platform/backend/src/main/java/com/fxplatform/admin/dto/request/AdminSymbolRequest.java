package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.fxplatform.market.model.ProductType;
import java.math.BigDecimal;

/**
 * AdminSymbolRequest 是后台产品列表新增和编辑交易品种的请求 DTO。
 *
 * @param symbol 平台标准产品代码。
 * @param displayName 后台和前端展示名称。
 * @param provider 行情供应商。
 * @param providerSymbol 供应商侧产品代码。
 * @param assetClass 产品分类，例如 FOREX、CRYPTO、METAL。
 * @param baseCurrency 基础币种。
 * @param quoteCurrency 计价币种。
 * @param pipSize 点值大小。
 * @param tickSize 最小报价跳动。
 * @param lotSize 标准手大小。
 * @param minLot 最小手数。
 * @param maxLot 最大手数。
 * @param leverage 默认杠杆倍数。
 * @param spreadMarkup 点差加成。
 * @param enabled 是否启用。
 */
public record AdminSymbolRequest(
    @NotBlank String symbol,
    @NotBlank String displayName,
    String provider,
    String providerSymbol,
    @NotBlank String assetClass,
    @NotNull ProductType productType,
    @NotBlank String baseCurrency,
    @NotBlank String quoteCurrency,
    @NotNull BigDecimal pipSize,
    @NotNull BigDecimal tickSize,
    @NotNull BigDecimal lotSize,
    @NotNull BigDecimal minLot,
    @NotNull BigDecimal maxLot,
    @NotNull Integer leverage,
    @NotNull BigDecimal spreadMarkup,
    @NotNull Boolean enabled,
    String iconUrl,
    Boolean displayEnabled,
    Boolean quoteEnabled,
    Boolean chartEnabled,
    Boolean orderBookEnabled,
    Boolean tradable,
    Boolean featured,
    String displayGroup,
    Integer displayOrder,
    String confirmationText
) {
  public AdminSymbolRequest(
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
      Integer displayOrder
  ) {
    this(
        symbol,
        displayName,
        provider,
        providerSymbol,
        assetClass,
        productType,
        baseCurrency,
        quoteCurrency,
        pipSize,
        tickSize,
        lotSize,
        minLot,
        maxLot,
        leverage,
        spreadMarkup,
        enabled,
        iconUrl,
        displayEnabled,
        quoteEnabled,
        chartEnabled,
        orderBookEnabled,
        tradable,
        featured,
        displayGroup,
        displayOrder,
        null);
  }
}
