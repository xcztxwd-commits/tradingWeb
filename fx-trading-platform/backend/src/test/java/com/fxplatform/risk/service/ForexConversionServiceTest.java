package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForexConversionServiceTest {

  @Mock
  private ForexConversionRateRepository conversionRateRepository;

  @Test
  void sameCurrencyReturnsOriginalAmount() {
    ForexConversionService service = new ForexConversionService(conversionRateRepository);
    BigDecimal amount = new BigDecimal("12.345");

    assertThat(service.convert(amount, "usd", "USD")).isSameAs(amount);
  }

  @Test
  void directRateMultipliesAmount() {
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.00666667")));

    BigDecimal converted = new ForexConversionService(conversionRateRepository)
        .convert(new BigDecimal("1000.00000000"), "JPY", "USD");

    assertThat(converted).isEqualByComparingTo("6.66667000");
  }

  @Test
  void reverseRateDividesAmount() {
    when(conversionRateRepository.findLatest("JPY", "USD")).thenReturn(Optional.empty());
    when(conversionRateRepository.findLatest("USD", "JPY"))
        .thenReturn(Optional.of(conversionRate("USD", "JPY", "150.00000000")));

    BigDecimal converted = new ForexConversionService(conversionRateRepository)
        .convert(new BigDecimal("1000.00000000"), "JPY", "USD");

    assertThat(converted).isEqualByComparingTo("6.66666667");
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
