package com.fxplatform.engagement.application.popup;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PopupDeliveryRetentionService {

  public static final String RETENTION_DAYS_KEY =
      "engagement.popup.deliveryRetentionDays";
  static final int DEFAULT_RETENTION_DAYS = 365;
  static final int MAX_RETENTION_DAYS = 3650;

  private final SystemSettingRepository settingRepository;
  private final PopupDeliveryRepository deliveryRepository;
  private final Clock clock;

  public PopupDeliveryRetentionService(
      SystemSettingRepository settingRepository,
      PopupDeliveryRepository deliveryRepository,
      Clock clock) {
    this.settingRepository = Objects.requireNonNull(settingRepository, "settingRepository");
    this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public int deleteExpiredRawDeliveries() {
    int retentionDays = readRetentionDays(
        settingRepository.findBySettingKey(RETENTION_DAYS_KEY));
    Instant now = clock.instant();
    Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
    deliveryRepository.clearExpiredPointersForDeliveriesIssuedBefore(cutoff, now);
    return deliveryRepository.deleteIssuedBefore(cutoff);
  }

  private static int readRetentionDays(Optional<SystemSettingEntity> setting) {
    if (setting.isEmpty()) {
      return DEFAULT_RETENTION_DAYS;
    }
    String stored = setting.orElseThrow().getSettingValue();
    try {
      int value = Integer.parseInt(stored == null ? "" : stored.strip());
      if (value < 1 || value > MAX_RETENTION_DAYS) {
        throw invalid("must be between 1 and " + MAX_RETENTION_DAYS);
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new BusinessException(
          "POPUP_POLICY_INVALID",
          RETENTION_DAYS_KEY + " must be an integer between 1 and " + MAX_RETENTION_DAYS,
          exception);
    }
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(
        "POPUP_POLICY_INVALID", RETENTION_DAYS_KEY + " " + message);
  }
}
