package com.fxplatform.market.dto;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.model.InstrumentRules;
import java.math.BigDecimal;

public record InstrumentRulesResponse(
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
    Integer pricePrecision,
    Integer quantityPrecision,
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

  public static InstrumentRulesResponse from(InstrumentRules rules) {
    return new InstrumentRulesResponse(
        rules.symbol(),
        rules.exists(),
        rules.enabled(),
        rules.tradable(),
        rules.quoteEnabled(),
        rules.chartEnabled(),
        rules.orderBookEnabled(),
        rules.orderEnabled(),
        rules.productType(),
        rules.tickSize(),
        rules.stepSize(),
        decimalPlaces(rules.tickSize()),
        decimalPlaces(rules.stepSize()),
        rules.minQty(),
        rules.maxQty(),
        rules.minNotional(),
        rules.maxNotional(),
        rules.minLot(),
        rules.maxLot(),
        rules.maxLeverage(),
        rules.defaultLeverage(),
        rules.marginAsset(),
        rules.settlementAsset(),
        rules.contractSize(),
        rules.riskTier(),
        rules.tradingSession(),
        rules.kycRequirement(),
        rules.userRiskLevelRestriction());
  }

  private static Integer decimalPlaces(BigDecimal value) {
    if (value == null) {
      return null;
    }
    return Math.max(0, value.stripTrailingZeros().scale());
  }
}
