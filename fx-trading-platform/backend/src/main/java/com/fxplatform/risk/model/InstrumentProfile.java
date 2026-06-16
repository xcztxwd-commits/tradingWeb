package com.fxplatform.risk.model;

import java.math.BigDecimal;

public record InstrumentProfile(
    InstrumentKind kind,
    BigDecimal unitSize,
    String instrumentType,
    String positionUnit,
    BigDecimal contractSize,
    BigDecimal contractMultiplier,
    BigDecimal maintenanceMarginRate,
    String settlementAsset,
    String marginAsset
) {
  public InstrumentProfile(
      InstrumentKind kind,
      BigDecimal unitSize,
      String instrumentType,
      String positionUnit
  ) {
    this(kind, unitSize, instrumentType, positionUnit, unitSize, BigDecimal.ONE, BigDecimal.ZERO, null, null);
  }
}
