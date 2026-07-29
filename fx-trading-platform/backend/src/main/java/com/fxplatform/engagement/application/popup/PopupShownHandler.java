package com.fxplatform.engagement.application.popup;

import java.time.Instant;
import java.util.UUID;

/** Same-transaction extension point for the first accepted popup impression. */
@FunctionalInterface
public interface PopupShownHandler {

  void onShown(UUID userId, UUID campaignId, UUID deliveryId, Instant shownAt);
}
