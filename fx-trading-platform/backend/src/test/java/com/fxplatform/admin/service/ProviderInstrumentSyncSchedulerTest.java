package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderInstrumentSyncSchedulerTest {

  @Mock
  private AdminMarketDataProviderService providerService;

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
