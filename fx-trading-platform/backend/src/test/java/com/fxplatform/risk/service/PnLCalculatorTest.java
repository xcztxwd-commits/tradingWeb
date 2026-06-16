package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
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
class PnLCalculatorTest {

  @Mock
  private ForexConversionRateRepository conversionRateRepository;

  private final PnLCalculator calculator = new PnLCalculator();

  @Test
  void buyPositionProfitAndLossUseForexContractSize() {
    assertThat(calculator.floatingPnl(
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.10120")))
        .isEqualByComparingTo("10.00");

    assertThat(calculator.floatingPnl(
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.09920")))
        .isEqualByComparingTo("-10.00");
  }

  @Test
  void sellPositionProfitAndLossUseForexContractSize() {
    assertThat(calculator.floatingPnl(
        OrderSide.SELL,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.09920")))
        .isEqualByComparingTo("10.00");

    assertThat(calculator.floatingPnl(
        OrderSide.SELL,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.10120")))
        .isEqualByComparingTo("-10.00");
  }

  @Test
  void eurUsdUsdAccountKeepsExistingQuotePnl() {
    PnLCalculator calculator = new PnLCalculator(
        new TradingAlgorithmEngine(),
        new ForexConversionService(conversionRateRepository));

    BigDecimal pnl = calculator.floatingPnl(
        "EURUSD",
        "USD",
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("1.10020"),
        new BigDecimal("1.10120"));

    assertThat(pnl).isEqualByComparingTo("10.00000000");
    verifyNoInteractions(conversionRateRepository);
  }

  @Test
  void usdJpyUsdAccountConvertsQuotePnlBackToUsd() {
    when(conversionRateRepository.findLatest("JPY", "USD")).thenReturn(Optional.empty());
    when(conversionRateRepository.findLatest("USD", "JPY"))
        .thenReturn(Optional.of(conversionRate("USD", "JPY", "150.00000000")));
    PnLCalculator calculator = new PnLCalculator(
        new TradingAlgorithmEngine(),
        new ForexConversionService(conversionRateRepository));

    BigDecimal pnl = calculator.floatingPnl(
        "USDJPY",
        "USD",
        OrderSide.BUY,
        new BigDecimal("0.10"),
        new BigDecimal("150.000"),
        new BigDecimal("150.100"));

    assertThat(pnl).isEqualByComparingTo("6.66666667");
  }

  private static ForexConversionRateEntity conversionRate(String from, String to, String rate) {
    ForexConversionRateEntity entity = new ForexConversionRateEntity();
    entity.setId(UUID.randomUUID());
    entity.setFromCurrency(from);
    entity.setToCurrency(to);
    entity.setRate(new BigDecimal(rate));
    entity.setEffectiveAt(Instant.parse("2026-06-16T00:00:00Z"));
    entity.setSource("test");
    return entity;
  }
}
