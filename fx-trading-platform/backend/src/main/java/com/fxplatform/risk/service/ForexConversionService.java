package com.fxplatform.risk.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ForexConversionService {

  private static final int MONEY_SCALE = 8;

  private final ForexConversionRateRepository conversionRateRepository;

  @Autowired
  public ForexConversionService(ForexConversionRateRepository conversionRateRepository) {
    this.conversionRateRepository = conversionRateRepository;
  }

  public ForexConversionService() {
    this.conversionRateRepository = null;
  }

  public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
    String from = normalizeCurrency(fromCurrency);
    String to = normalizeCurrency(toCurrency);
    if (from.equals(to)) {
      return amount;
    }
    if (conversionRateRepository == null) {
      throw new BusinessException("FX_CONVERSION_RATE_NOT_FOUND", "FX conversion rate not found");
    }

    return conversionRateRepository.findLatest(from, to)
        .map(rate -> multiply(amount, rate))
        .orElseGet(() -> convertWithReverseRate(amount, from, to));
  }

  private BigDecimal convertWithReverseRate(BigDecimal amount, String fromCurrency, String toCurrency) {
    ForexConversionRateEntity reverseRate = conversionRateRepository.findLatest(toCurrency, fromCurrency)
        .orElseThrow(() -> new BusinessException("FX_CONVERSION_RATE_NOT_FOUND", "FX conversion rate not found"));
    return amount.divide(reverseRate.getRate(), MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal multiply(BigDecimal amount, ForexConversionRateEntity conversionRate) {
    return amount.multiply(conversionRate.getRate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String normalizeCurrency(String currency) {
    return currency == null ? "" : currency.trim().toUpperCase();
  }

  // TODO: Add gainQuoteHome/lossQuoteHome support when OANDA-style HomeConversionFactors are in scope.
}
