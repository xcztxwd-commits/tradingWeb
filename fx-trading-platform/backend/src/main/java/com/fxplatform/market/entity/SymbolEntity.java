package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.TextArrayTypeHandler;
import com.fxplatform.market.model.ProductType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SymbolEntity 是交易品种数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "market.symbols", autoResultMap = true)
public class SymbolEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String symbol;
  private String displayName;
  private String provider = "massive";
  private String providerSymbol;
  private String assetClass = "FOREX";
  private ProductType productType;
  private String baseCurrency;
  private String quoteCurrency;
  private BigDecimal pipSize;
  private BigDecimal tickSize;
  private BigDecimal lotSize;
  private BigDecimal contractSize;
  private BigDecimal contractMultiplier = BigDecimal.ONE;
  private BigDecimal minLot;
  private BigDecimal maxLot;
  private Integer leverage;
  private BigDecimal spreadMarkup = BigDecimal.ZERO;
  private String settlementAsset;
  private String marginAsset;
  private BigDecimal maintenanceMarginRate = BigDecimal.ZERO;
  private BigDecimal liquidationFeeRate = BigDecimal.ZERO;
  private String markPriceSource;
  private BigDecimal fixedFundingRate = new BigDecimal("0.0001");
  private Integer fixedFundingIntervalMinutes = 480;

  @TableField(typeHandler = TextArrayTypeHandler.class)
  private List<String> fundingSourcePriority = List.of("BINANCE", "OKX", "FIXED");

  private Integer fundingStaleSeconds = 900;
  private Boolean enabled = true;
  private String iconUrl;
  private UUID iconAssetId;
  private Boolean displayEnabled = true;
  private Boolean quoteEnabled = true;
  private Boolean chartEnabled = true;
  private Boolean orderBookEnabled = true;
  private Boolean tradable = true;
  private Boolean featured = false;
  private String displayGroup;
  private Integer displayOrder = 0;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
