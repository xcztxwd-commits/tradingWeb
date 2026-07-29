package com.fxplatform.engagement.application.popup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PopupDeliveryRetentionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Mock SystemSettingRepository settingRepository;
  @Mock PopupDeliveryRepository deliveryRepository;

  private PopupDeliveryRetentionService service;

  @BeforeEach
  void setUp() {
    service = new PopupDeliveryRetentionService(
        settingRepository,
        deliveryRepository,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void missingSettingClearsExpiredPointersBeforeDeleting365DayOldRawDetails() {
    when(settingRepository.findBySettingKey(PopupDeliveryRetentionService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.empty());
    Instant cutoff = NOW.minus(365, ChronoUnit.DAYS);
    when(deliveryRepository.deleteIssuedBefore(cutoff)).thenReturn(7);

    assertThat(service.deleteExpiredRawDeliveries()).isEqualTo(7);

    InOrder ordered = inOrder(settingRepository, deliveryRepository);
    ordered.verify(settingRepository)
        .findBySettingKey(PopupDeliveryRetentionService.RETENTION_DAYS_KEY);
    ordered.verify(deliveryRepository)
        .clearExpiredPointersForDeliveriesIssuedBefore(cutoff, NOW);
    ordered.verify(deliveryRepository).deleteIssuedBefore(cutoff);
  }

  @Test
  void everyRunReadsTheCurrentConfiguredRetention() {
    when(settingRepository.findBySettingKey(PopupDeliveryRetentionService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.of(setting("30")), Optional.of(setting("90")));

    service.deleteExpiredRawDeliveries();
    service.deleteExpiredRawDeliveries();

    verify(deliveryRepository).clearExpiredPointersForDeliveriesIssuedBefore(
        NOW.minus(30, ChronoUnit.DAYS), NOW);
    verify(deliveryRepository).clearExpiredPointersForDeliveriesIssuedBefore(
        NOW.minus(90, ChronoUnit.DAYS), NOW);
    verify(deliveryRepository).deleteIssuedBefore(NOW.minus(30, ChronoUnit.DAYS));
    verify(deliveryRepository).deleteIssuedBefore(NOW.minus(90, ChronoUnit.DAYS));
  }

  @ParameterizedTest
  @MethodSource("invalidRetentionValues")
  void invalidConfiguredRetentionFailsClosedBeforeDeleting(String value) {
    when(settingRepository.findBySettingKey(PopupDeliveryRetentionService.RETENTION_DAYS_KEY))
        .thenReturn(Optional.of(setting(value)));

    assertThatThrownBy(service::deleteExpiredRawDeliveries)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("engagement.popup.deliveryRetentionDays");

    verify(deliveryRepository, never())
        .clearExpiredPointersForDeliveriesIssuedBefore(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(deliveryRepository, never()).deleteIssuedBefore(org.mockito.ArgumentMatchers.any());
  }

  private static Stream<String> invalidRetentionValues() {
    return Stream.of("", "not-a-number", "0", "3651");
  }

  private static SystemSettingEntity setting(String value) {
    SystemSettingEntity setting = new SystemSettingEntity();
    setting.setSettingValue(value);
    return setting;
  }
}
