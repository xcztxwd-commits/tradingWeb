package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ExtendWith(MockitoExtension.class)
class ProviderInstrumentSyncSchedulerTest {

  @Mock
  private AdminMarketDataProviderService providerService;

  @Test
  void schedulerIsExplicitlyOptInAndConfigurationDefaultsToDisabled() throws Exception {
    ConditionalOnProperty condition = ProviderInstrumentSyncScheduler.class
        .getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("market.provider-instrument-sync");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
    assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
        .contains("enabled: ${MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED:false}");
  }

  @Test
  void syncProviderInstrumentsRunsBatchSync() {
    when(providerService.syncEnabledProviderInstruments()).thenReturn(7);

    new ProviderInstrumentSyncScheduler(providerService).syncProviderInstruments();

    verify(providerService).syncEnabledProviderInstruments();
  }

  @Test
  void syncProviderInstrumentsSwallowsFailures() {
    doThrow(new IllegalStateException("provider unavailable"))
        .when(providerService)
        .syncEnabledProviderInstruments();

    ProviderInstrumentSyncScheduler scheduler = new ProviderInstrumentSyncScheduler(providerService);

    assertThatCode(scheduler::syncProviderInstruments).doesNotThrowAnyException();
  }
}
