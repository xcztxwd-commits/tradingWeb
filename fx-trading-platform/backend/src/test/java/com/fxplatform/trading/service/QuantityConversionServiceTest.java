package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.QuantityUnit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityConversionServiceTest {

  private final QuantityConversionService service = new QuantityConversionService();

  @Test
  void convertsMarketBuyTotalQuoteBudgetAtCanonicalPriceAndFeeRate() {
    QuantityConversionService.Conversion conversion = service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100.00"),
        new BigDecimal("0.0001"),
        marketPricing("50005.0000"));

    assertThat(conversion.originalQuantity()).isEqualByComparingTo("100.00");
    assertThat(conversion.originalUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0019");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("95.05700475");
    assertThat(conversion.projectedQuoteSpend()).isLessThanOrEqualTo(conversion.originalQuantity());
  }

  @Test
  void quoteBudgetReductionIncludesTheUsdtFeeBeforeChoosingTheBaseStep() {
    QuantityConversionService.Conversion conversion = service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100.04"),
        new BigDecimal("0.1"),
        marketPricing("100"));

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.9");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("90.04500000");
    assertThat(conversion.projectedQuoteSpend())
        .isLessThanOrEqualTo(new BigDecimal("100.04"));
  }

  @Test
  void preservesBaseForMarketSellLimitAndStopMarket() {
    for (OrderType type : new OrderType[]{OrderType.MARKET, OrderType.LIMIT, OrderType.STOP_MARKET}) {
      OrderSide side = type == OrderType.MARKET ? OrderSide.SELL : OrderSide.BUY;
      QuantityConversionService.Conversion conversion = service.convertSpot(
          side,
          type,
          QuantityUnit.BASE,
          new BigDecimal("0.1234"),
          new BigDecimal("0.0001"),
          null);
      assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.1234");
      assertThat(conversion.originalUnit()).isEqualTo(QuantityUnit.BASE);
    }
  }

  @Test
  void rejectsWrongTypeUnitMatrixAndDustConversion() {
    assertCode("INVALID_QUANTITY_UNIT", () -> service.convertSpot(
        OrderSide.BUY, OrderType.MARKET, QuantityUnit.BASE,
        new BigDecimal("100"), new BigDecimal("0.0001"), marketPricing("50005")));
    assertCode("INVALID_QUANTITY_UNIT", () -> service.convertSpot(
        OrderSide.SELL, OrderType.MARKET, QuantityUnit.QUOTE,
        new BigDecimal("100"), new BigDecimal("0.0001"), marketPricing("50005")));
    assertCode("INVALID_QUANTITY_UNIT", () -> service.convertSpot(
        OrderSide.BUY, OrderType.LIMIT, QuantityUnit.CONTRACTS,
        new BigDecimal("1"), new BigDecimal("0.0001"), null));
    assertCode("QUANTITY_CONVERTS_TO_ZERO", () -> service.convertSpot(
        OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        new BigDecimal("1"), new BigDecimal("0.0001"), marketPricing("50005")));
  }

  @Test
  void coarsensFineProviderStepToExactLegacyOrderAndTradeStoragePrecision() {
    QuantityConversionService.Conversion conversion = service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("100.00"),
        new BigDecimal("0.00001"),
        marketPricing("50005.0000"));

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0019");
    assertThat(conversion.baseQuantity().remainder(new BigDecimal("0.00001"))).isZero();
    assertThat(conversion.baseQuantity().stripTrailingZeros().scale()).isLessThanOrEqualTo(4);
    assertCode("QUANTITY_STEP_MISMATCH", () -> service.convertSpot(
        OrderSide.SELL,
        OrderType.MARKET,
        QuantityUnit.BASE,
        new BigDecimal("0.12345"),
        new BigDecimal("0.00001"),
        null));
  }

  @Test
  void usesDecimalLcmWhenProviderStepDoesNotDivideStorageIncrement() {
    QuantityConversionService.Conversion conversion = service.convertSpot(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        new BigDecimal("1.00"),
        new BigDecimal("0.00003"),
        marketPricing("1000"));

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0009");
    assertThat(conversion.baseQuantity().remainder(new BigDecimal("0.00003"))).isZero();
    assertThat(conversion.baseQuantity().stripTrailingZeros().scale()).isLessThanOrEqualTo(4);
  }

  @Test
  void perpetualBasePreservesExactCanonicalQuantityAndValidatesNotional() {
    QuantityConversionService.Conversion conversion = service.convertPerpetual(
        QuantityUnit.BASE,
        new BigDecimal("0.1234"),
        new BigDecimal("0.00001"),
        new BigDecimal("0.01"),
        new BigDecimal("10"),
        new BigDecimal("50000"),
        new BigDecimal("5"));

    assertThat(conversion.originalQuantity()).isEqualByComparingTo("0.1234");
    assertThat(conversion.originalUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.1234");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("6170.00000000");
  }

  @Test
  void perpetualQuoteRoundsDownToStorageCompatibleStep() {
    QuantityConversionService.Conversion conversion = service.convertPerpetual(
        QuantityUnit.QUOTE,
        new BigDecimal("100"),
        new BigDecimal("0.00003"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("50000"),
        new BigDecimal("5"));

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0018");
    assertThat(conversion.baseQuantity().remainder(new BigDecimal("0.00003"))).isZero();
    assertThat(conversion.baseQuantity().stripTrailingZeros().scale()).isLessThanOrEqualTo(4);
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("90.00000000");
  }

  @Test
  void perpetualContractsApplyContractSizeAndMultiplierExactlyOnce() {
    QuantityConversionService.Conversion conversion = service.convertPerpetual(
        QuantityUnit.CONTRACTS,
        new BigDecimal("3"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.01"),
        new BigDecimal("10"),
        new BigDecimal("50000"),
        new BigDecimal("5"));

    assertThat(conversion.originalQuantity()).isEqualByComparingTo("3");
    assertThat(conversion.originalUnit()).isEqualTo(QuantityUnit.CONTRACTS);
    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.30");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("15000.00000000");
  }

  @Test
  void perpetualRejectsNonIntegralContractsStepMismatchDustAndMinimumNotional() {
    assertCode("CONTRACT_QUANTITY_NOT_INTEGRAL", () -> service.convertPerpetual(
        QuantityUnit.CONTRACTS, new BigDecimal("1.5"), new BigDecimal("0.0001"),
        new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("50000"), new BigDecimal("5")));
    assertCode("QUANTITY_STEP_MISMATCH", () -> service.convertPerpetual(
        QuantityUnit.CONTRACTS, BigDecimal.ONE, new BigDecimal("0.0001"),
        new BigDecimal("0.00015"), BigDecimal.ONE, new BigDecimal("50000"), new BigDecimal("5")));
    assertCode("QUANTITY_CONVERTS_TO_ZERO", () -> service.convertPerpetual(
        QuantityUnit.BASE, new BigDecimal("0.00000001"), new BigDecimal("0.0001"),
        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("5")));
    assertCode("QUANTITY_CONVERTS_TO_ZERO", () -> service.convertPerpetual(
        QuantityUnit.QUOTE, BigDecimal.ONE, new BigDecimal("0.0001"),
        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("50000"), new BigDecimal("5")));
    assertCode("ORDER_NOTIONAL_TOO_SMALL", () -> service.convertPerpetual(
        QuantityUnit.BASE, new BigDecimal("0.0001"), new BigDecimal("0.0001"),
        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000"), new BigDecimal("5")));
  }

  private static FullFillPricingProjection marketPricing(String price) {
    return new FullFillPricingProjection(
        new BigDecimal(price),
        new BigDecimal("5"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.0005"),
        LiquidityRole.TAKER);
  }

  private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
