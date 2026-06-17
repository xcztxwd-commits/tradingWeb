package com.fxplatform.market.service;

import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.market.repository.DataProviderRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProviderHealthRecorder {

  private final DataProviderRepository providerRepository;

  ProviderHealthRecorder() {
    this.providerRepository = null;
  }

  @Autowired
  public ProviderHealthRecorder(DataProviderRepository providerRepository) {
    this.providerRepository = providerRepository;
  }

  public static ProviderHealthRecorder noop() {
    return new ProviderHealthRecorder();
  }

  public void recordQuoteSuccess(DataProviderEntity provider, QuoteResponse quote, long latencyMs) {
    if (provider == null) {
      return;
    }
    Instant now = Instant.now();
    provider.setHealthStatus(ProviderHealthStatus.UP);
    provider.setLastHealthCheckAt(now);
    provider.setLastSuccessAt(now);
    provider.setLastQuoteSuccessAt(now);
    provider.setAvgLatencyMs(latencyMs);
    if (quote != null) {
      provider.setQuoteStalenessMs(Math.max(0, now.toEpochMilli() - quote.timestamp()));
    }
    save(provider);
  }

  public void recordFailure(DataProviderEntity provider, long latencyMs) {
    if (provider == null) {
      return;
    }
    Instant now = Instant.now();
    long currentFailures = provider.getFailureCount() == null ? 0 : provider.getFailureCount();
    long currentAverage = provider.getAvgLatencyMs() == null ? latencyMs : provider.getAvgLatencyMs();
    provider.setHealthStatus(ProviderHealthStatus.DOWN);
    provider.setLastHealthCheckAt(now);
    provider.setLastFailureAt(now);
    provider.setFailureCount(currentFailures + 1);
    provider.setAvgLatencyMs((currentAverage * (currentFailures + 1) + latencyMs) / (currentFailures + 2));
    save(provider);
  }

  public void recordInstrumentSyncSuccess(DataProviderEntity provider, int syncedCount) {
    if (provider == null) {
      return;
    }
    Instant now = Instant.now();
    provider.setHealthStatus(ProviderHealthStatus.UP);
    provider.setLastHealthCheckAt(now);
    provider.setLastSuccessAt(now);
    provider.setLastInstrumentSyncAt(now);
    provider.setLastInstrumentSyncCount(syncedCount);
    save(provider);
  }

  private void save(DataProviderEntity provider) {
    if (providerRepository != null) {
      providerRepository.save(provider);
    }
  }
}
