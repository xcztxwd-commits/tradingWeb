package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.QuantityUnit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityConversionNumericBoundaryTest {

  private final QuantityConversionService service = new QuantityConversionService();

  @Test
  void classifiesScaleOnlyBaseAndContractViolationsByExchangeQuantitySemantics() {
    assertCode(ErrorCode.QUANTITY_STEP_MISMATCH, () -> service.convertSpot(
        OrderSide.SELL,
        OrderType.MARKET,
        QuantityUnit.BASE,
        new BigDecimal("0.000000001"),
        new BigDecimal("0.0001"),
        null));
    assertCode(ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL, () -> service.convertPerpetual(
        QuantityUnit.CONTRACTS,
        new BigDecimal("1.000000001"),
        new BigDecimal("0.0001"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("100"),
        new BigDecimal("5")));
  }

  @Test
  void rejectsPublicQuantityOutsideNumeric24_8BeforeConversionArithmetic() {
    assertCode("BAD_QUANTITY", () -> service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("1E+10000"),
        new BigDecimal("0.0001"),
        marketPricing(new BigDecimal("100"), new BigDecimal("0.0005"))));
    assertCode("BAD_QUANTITY", () -> service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("1E-9"),
        new BigDecimal("0.0001"),
        marketPricing(new BigDecimal("100"), new BigDecimal("0.0005"))));
  }

  @Test
  void rejectsProviderStepBeforeExponentSensitiveStorageCompatibilityArithmetic() {
    assertCode("INVALID_INSTRUMENT_RULES", () -> service.convertSpot(
        OrderSide.SELL,
        OrderType.MARKET,
        QuantityUnit.BASE,
        new BigDecimal("1.0000"),
        new BigDecimal("1E-10000"),
        null));
  }

  @Test
  void rejectsPerpetualArithmeticOperandsOutsideTheirNumericContracts() {
    assertCode(ErrorCode.MARKET_BUNDLE_INCOMPLETE, () -> service.convertPerpetual(
        QuantityUnit.BASE,
        new BigDecimal("1.0000"),
        new BigDecimal("0.0001"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("1E+10000"),
        BigDecimal.ONE));
    assertCode("INVALID_INSTRUMENT_RULES", () -> service.convertPerpetual(
        QuantityUnit.CONTRACTS,
        BigDecimal.ONE,
        new BigDecimal("0.0001"),
        new BigDecimal("1E+10000"),
        BigDecimal.ONE,
        new BigDecimal("100"),
        BigDecimal.ONE));
  }

  @Test
  void rejectsSpotPricingOperandsBeforeAdditionMultiplicationOrDivision() {
    assertCode(ErrorCode.MARKET_BUNDLE_INCOMPLETE, () -> service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100"),
        new BigDecimal("0.0001"),
        marketPricing(new BigDecimal("1E+10000"), new BigDecimal("0.0005"))));
    assertCode(ErrorCode.MARKET_BUNDLE_INCOMPLETE, () -> service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100"),
        new BigDecimal("0.0001"),
        marketPricing(new BigDecimal("100"), new BigDecimal("1E-10000"))));
  }

  @Test
  void rejectsCanonicalBaseQuantityOutsideLegacyNumeric12_4() {
    assertCode("BAD_QUANTITY", () -> service.convertSpot(
        OrderSide.SELL,
        OrderType.MARKET,
        QuantityUnit.BASE,
        new BigDecimal("100000000"),
        new BigDecimal("0.0001"),
        null));
  }

  private static FullFillPricingProjection marketPricing(BigDecimal price, BigDecimal feeRate) {
    return new FullFillPricingProjection(
        price,
        new BigDecimal("5"),
        new BigDecimal("0.0001"),
        feeRate,
        new BigDecimal("0.0005"),
        LiquidityRole.TAKER);
  }

  private static void assertCode(
      String code,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
