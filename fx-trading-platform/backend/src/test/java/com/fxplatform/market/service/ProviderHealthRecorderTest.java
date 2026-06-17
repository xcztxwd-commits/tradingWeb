package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.market.repository.DataProviderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderHealthRecorderTest {

  @Mock
  private DataProviderRepository providerRepository;

  @Test
  void recordQuoteSuccessStoresLatencyAndQuoteFreshnessMetrics() {
    DataProviderEntity provider = provider("binance");
    QuoteResponse quote = quote("BTCUSDT", Instant.now().minusMillis(1250).toEpochMilli());
    when(providerRepository.save(provider)).thenReturn(provider);

    new ProviderHealthRecorder(providerRepository).recordQuoteSuccess(provider, quote, 42);

    assertThat(provider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UP);
    assertThat(provider.getLastSuccessAt()).isNotNull();
    assertThat(provider.getLastQuoteSuccessAt()).isNotNull();
    assertThat(provider.getLastHealthCheckAt()).isNotNull();
    assertThat(provider.getAvgLatencyMs()).isEqualTo(42);
    assertThat(provider.getQuoteStalenessMs()).isGreaterThanOrEqualTo(0);
    verify(providerRepository).save(provider);
  }

  @Test
  void recordFailureIncrementsFailureCountAndStoresLastFailureAt() {
    DataProviderEntity provider = provider("okx");
    provider.setFailureCount(2L);
    provider.setAvgLatencyMs(100L);
    when(providerRepository.save(provider)).thenReturn(provider);

    new ProviderHealthRecorder(providerRepository).recordFailure(provider, 300);

    assertThat(provider.getHealthStatus()).isEqualTo(ProviderHealthStatus.DOWN);
    assertThat(provider.getFailureCount()).isEqualTo(3);
    assertThat(provider.getLastFailureAt()).isNotNull();
    assertThat(provider.getLastHealthCheckAt()).isNotNull();
    assertThat(provider.getAvgLatencyMs()).isEqualTo(150);
    verify(providerRepository).save(provider);
  }

  @Test
  void recordInstrumentSyncSuccessStoresSyncTimestamp() {
    DataProviderEntity provider = provider("massive");
    when(providerRepository.save(provider)).thenReturn(provider);

    new ProviderHealthRecorder(providerRepository).recordInstrumentSyncSuccess(provider, 7);

    assertThat(provider.getHealthStatus()).isEqualTo(ProviderHealthStatus.UP);
    assertThat(provider.getLastSuccessAt()).isNotNull();
    assertThat(provider.getLastInstrumentSyncAt()).isNotNull();
    assertThat(provider.getLastInstrumentSyncCount()).isEqualTo(7);
    verify(providerRepository).save(provider);
  }

  private DataProviderEntity provider(String code) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(UUID.randomUUID());
    provider.setCode(code);
    provider.setEnabled(true);
    provider.setHealthStatus(ProviderHealthStatus.UNKNOWN);
    return provider;
  }

  private QuoteResponse quote(String symbol, long timestamp) {
    return new QuoteResponse(
        "quote",
        symbol,
        new BigDecimal("1.1"),
        new BigDecimal("1.2"),
        new BigDecimal("1.15"),
        new BigDecimal("0.1"),
        "test",
        timestamp);
  }
}
