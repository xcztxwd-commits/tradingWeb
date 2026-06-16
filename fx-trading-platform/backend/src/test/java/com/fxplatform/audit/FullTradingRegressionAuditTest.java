package com.fxplatform.audit;

import static org.mockito.Mockito.when;

import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
import com.fxplatform.risk.service.ForexConversionService;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FullTradingRegressionAuditTest {

  @Mock
  private ForexConversionRateRepository conversionRateRepository;

  private final TradingAlgorithmEngine engine = new TradingAlgorithmEngine();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();

  @Test
  void keyTradingAlgorithmExpectedValuesStayStable() {
    TradingAlgorithmEngine.SpotBuyResult spotBuy = engine.spotBuyWithQuoteBudget(
        new BigDecimal("5000"),
        new BigDecimal("50000"),
        new BigDecimal("0.001"));
    TradingAlgorithmEngine.SpotSellResult spotSell = engine.spotSell(
        new BigDecimal("0.1"),
        new BigDecimal("55000"),
        new BigDecimal("0.001"),
        BigDecimal.ZERO);
    BigDecimal eurUsdFee = engine.linearFee(
        new BigDecimal("0.1"),
        new BigDecimal("1.10000"),
        new BigDecimal("0.001"),
        new BigDecimal("100000"));
    PerpMarginCalculator.MarginResult linearMargin = perpMarginCalculator.calculate(
        linearProfile(),
        BigDecimal.ONE,
        new BigDecimal("50000"),
        10);
    PerpMarginCalculator.MarginResult inverseMargin = perpMarginCalculator.calculate(
        inverseProfile(),
        new BigDecimal("100"),
        new BigDecimal("50000"),
        10);

    AuditAssertions.assertAmountClose(new BigDecimal("-5000.00000000"), "-5000.00000000");
    AuditAssertions.assertBtcClose(spotBuy.netBase(), "0.09990000");
    AuditAssertions.assertBtcClose(new BigDecimal("-0.10000000"), "-0.10000000");
    AuditAssertions.assertAmountClose(spotSell.netQuote(), "5494.50000000");
    AuditAssertions.assertAmountClose(eurUsdFee, "11.00000000");
    AuditAssertions.assertAmountClose(linearMargin.initialMargin(), "5000.00000000");
    AuditAssertions.assertAmountClose(linearMargin.maintenanceMargin(), "250.00000000");
    AuditAssertions.assertBtcClose(inverseMargin.initialMargin(), "0.02000000");
    AuditAssertions.assertBtcClose(inverseMargin.maintenanceMargin(), "0.00100000");
  }

  @Test
  void fundingAndForexConversionDirectionExpectedValuesStayStable() {
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.0066442")));

    BigDecimal longFunding = BigDecimal.ONE
        .multiply(new BigDecimal("50000"))
        .multiply(new BigDecimal("0.0001"))
        .negate();
    BigDecimal shortFunding = BigDecimal.ONE
        .multiply(new BigDecimal("50000"))
        .multiply(new BigDecimal("0.0001"));
    BigDecimal usdJpyPnl = new PnLCalculator(
        new TradingAlgorithmEngine(),
        new ForexConversionService(conversionRateRepository))
        .floatingPnl("USDJPY", "USD", OrderSide.BUY, BigDecimal.ONE, new BigDecimal("150.005"), new BigDecimal("150.505"));

    AuditAssertions.assertAmountClose(longFunding, "-5.0000");
    AuditAssertions.assertAmountClose(shortFunding, "5.0000");
    AuditAssertions.assertFxConversionClose(usdJpyPnl, "332.21");
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

  private static ForexConversionRateEntity conversionRate(String from, String to, String rateValue) {
    ForexConversionRateEntity rate = new ForexConversionRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setFromCurrency(from);
    rate.setToCurrency(to);
    rate.setRate(new BigDecimal(rateValue));
    rate.setEffectiveAt(Instant.parse("2026-06-16T00:00:00Z"));
    rate.setSource("audit");
    return rate;
  }
}
