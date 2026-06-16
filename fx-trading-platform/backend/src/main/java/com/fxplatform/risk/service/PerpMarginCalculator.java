package com.fxplatform.risk.service;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class PerpMarginCalculator {

  private static final int MONEY_SCALE = 8;

  public MarginResult calculate(
      InstrumentProfile profile,
      BigDecimal quantity,
      BigDecimal markPrice,
      int leverage
  ) {
    return calculate(
        profile.kind(),
        quantity,
        profile.contractSize(),
        profile.contractMultiplier(),
        markPrice,
        leverage,
        profile.maintenanceMarginRate());
  }

  public MarginResult calculate(
      InstrumentKind kind,
      BigDecimal quantity,
      BigDecimal contractSize,
      BigDecimal contractMultiplier,
      BigDecimal markPrice,
      int leverage,
      BigDecimal maintenanceMarginRate
  ) {
    requirePerpetual(kind);
    BigDecimal unitSize = positiveOrDefault(contractSize, BigDecimal.ONE)
        .multiply(positiveOrDefault(contractMultiplier, BigDecimal.ONE));
    BigDecimal effectiveMark = positiveOrDefault(markPrice, BigDecimal.ZERO);
    BigDecimal rate = maintenanceMarginRate == null ? BigDecimal.ZERO : maintenanceMarginRate;
    BigDecimal effectiveLeverage = BigDecimal.valueOf(Math.max(leverage, 1));

    if (kind == InstrumentKind.INVERSE_PERPETUAL) {
      BigDecimal usdNotional = abs(quantity).multiply(unitSize).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      BigDecimal initialMargin = usdNotional.divide(effectiveMark.multiply(effectiveLeverage), MONEY_SCALE, RoundingMode.HALF_UP);
      BigDecimal maintenanceMargin = usdNotional.multiply(rate).divide(effectiveMark, MONEY_SCALE, RoundingMode.HALF_UP);
      return new MarginResult(usdNotional, initialMargin, maintenanceMargin);
    }

    BigDecimal notional = abs(quantity).multiply(unitSize).multiply(effectiveMark).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal initialMargin = notional.divide(effectiveLeverage, MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal maintenanceMargin = notional.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new MarginResult(notional, initialMargin, maintenanceMargin);
  }

  private void requirePerpetual(InstrumentKind kind) {
    if (kind != InstrumentKind.LINEAR_PERPETUAL && kind != InstrumentKind.INVERSE_PERPETUAL) {
      throw new IllegalArgumentException("Only perpetual instruments have maintenance margin");
    }
  }

  private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
  }

  private BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  public record MarginResult(
      BigDecimal notional,
      BigDecimal initialMargin,
      BigDecimal maintenanceMargin
  ) {
  }
}
