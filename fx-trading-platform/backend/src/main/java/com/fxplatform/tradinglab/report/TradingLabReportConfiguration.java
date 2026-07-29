package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class TradingLabReportConfiguration {

  @Bean
  TradingLabReportChunkCodec tradingLabReportChunkCodec(
      TradingLabReportProperties properties
  ) {
    return new TradingLabReportChunkCodec(properties.chunkBytes());
  }

  @Bean
  TradingLabFixedValidationSecretProvider tradingLabFixedValidationSecretProvider(
      Environment environment
  ) {
    return new TradingLabFixedValidationSecretProvider(environment);
  }

  @Bean
  TradingLabHttpTraceSanitizer tradingLabHttpTraceSanitizer(
      TradingLabCredentialSanitizer credentialSanitizer,
      TradingLabFixedValidationSecretProvider fixedValidationSecrets,
      TradingLabReportProperties properties
  ) {
    return new TradingLabHttpTraceSanitizer(
        credentialSanitizer,
        fixedValidationSecrets,
        properties.maxLogicalValueBytes());
  }
}
