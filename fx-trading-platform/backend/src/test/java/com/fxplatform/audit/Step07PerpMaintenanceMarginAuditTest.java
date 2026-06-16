package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.trading.dto.response.PositionResponse;
import java.math.BigDecimal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Step07PerpMaintenanceMarginAuditTest {

  private final PerpMarginCalculator calculator = new PerpMarginCalculator();

  @Test
  void linearPerpMarginUsesMarkPriceNotionalFormula() {
    PerpMarginCalculator.MarginResult margin = calculator.calculate(
        linearProfile(),
        BigDecimal.ONE,
        new BigDecimal("50000"),
        10);

    AuditAssertions.assertAmountClose(margin.notional(), "50000.00000000");
    AuditAssertions.assertAmountClose(margin.initialMargin(), "5000.00000000");
    AuditAssertions.assertAmountClose(margin.maintenanceMargin(), "250.00000000");
  }

  @Test
  void inversePerpMarginUsesCoinSettledFormula() {
    PerpMarginCalculator.MarginResult margin = calculator.calculate(
        inverseProfile(),
        new BigDecimal("100"),
        new BigDecimal("50000"),
        10);

    AuditAssertions.assertAmountClose(margin.notional(), "10000.00000000");
    AuditAssertions.assertBtcClose(margin.initialMargin(), "0.02000000");
    AuditAssertions.assertBtcClose(margin.maintenanceMargin(), "0.00100000");
  }

  @Test
  void positionApiResponseExposesPerpRiskFields() {
    assertThat(Arrays.stream(PositionResponse.class.getRecordComponents()).map(RecordComponent::getName))
        .contains("markPrice", "maintenanceMargin", "maintenanceMarginRate", "liquidationPrice");
  }

  private static InstrumentProfile linearProfile() {
    return new InstrumentProfile(
        InstrumentKind.LINEAR_PERPETUAL,
        BigDecimal.ONE,
        "SWAP",
        "CONTRACT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("0.005"),
        "USDT",
        "USDT");
  }

  private static InstrumentProfile inverseProfile() {
    return new InstrumentProfile(
        InstrumentKind.INVERSE_PERPETUAL,
        new BigDecimal("100"),
        "SWAP",
        "CONTRACT",
        new BigDecimal("100"),
        BigDecimal.ONE,
        new BigDecimal("0.005"),
        "BTC",
        "BTC");
  }
}
