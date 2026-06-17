package com.fxplatform.risk.model;

import com.fxplatform.market.model.ProductType;
import java.math.BigDecimal;

public record InstrumentRules(
    String symbol,
    boolean exists,
    boolean enabled,
    boolean tradable,
    boolean quoteEnabled,
    boolean chartEnabled,
    boolean orderBookEnabled,
    boolean orderEnabled,
    ProductType productType,
    BigDecimal tickSize,
    BigDecimal stepSize,
    BigDecimal minQty,
    BigDecimal maxQty,
    BigDecimal minNotional,
    BigDecimal maxNotional,
    BigDecimal minLot,
    BigDecimal maxLot,
    Integer maxLeverage,
    Integer defaultLeverage,
    String marginAsset,
    String settlementAsset,
    BigDecimal contractSize,
    String riskTier,
    String tradingSession,
    String kycRequirement,
    String userRiskLevelRestriction
) {

  public static InstrumentRules missing(String symbol) {
    return new InstrumentRules(
        symbol,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }
}
