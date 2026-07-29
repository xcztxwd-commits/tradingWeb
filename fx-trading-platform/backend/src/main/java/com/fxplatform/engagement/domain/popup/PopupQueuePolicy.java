package com.fxplatform.engagement.domain.popup;

import java.time.Instant;
import java.util.Objects;

/** Pure policy for queue snapshots, claim guards, and outcome continuation. */
public final class PopupQueuePolicy {

  public static final String MAX_SEQUENTIAL_POPUPS_SETTING_KEY =
      "engagement.popup.maxSequentialPopups";
  public static final int DEFAULT_MAX_SEQUENTIAL_POPUPS = 3;

  public int snapshotMaxItems(String configuredValue) {
    if (configuredValue == null) {
      return DEFAULT_MAX_SEQUENTIAL_POPUPS;
    }

    try {
      int configured = Integer.parseInt(configuredValue.strip());
      if (configured <= 0) {
        throw invalidConfiguration();
      }
      return configured;
    } catch (NumberFormatException exception) {
      throw invalidConfiguration();
    }
  }

  public ClaimDecision beforeClaim(
      int issuedCount,
      int maxItems,
      Instant expiresAt,
      Instant terminatedAt,
      Instant now) {
    Objects.requireNonNull(now, "now");
    if (terminatedAt != null) {
      return ClaimDecision.TERMINATED;
    }
    if (!now.isBefore(Objects.requireNonNull(expiresAt, "expiresAt"))) {
      return ClaimDecision.EXPIRED;
    }
    if (issuedCount >= maxItems) {
      return ClaimDecision.CAP_REACHED;
    }
    return ClaimDecision.CLAIM;
  }

  public OutcomeDecision afterOutcome(PopupOutcome outcome) {
    return switch (Objects.requireNonNull(outcome, "outcome")) {
      case SHOWN -> OutcomeDecision.KEEP_CURRENT;
      case CLOSE, OPT_OUT -> OutcomeDecision.CONTINUE;
      case CTA_CLICK -> OutcomeDecision.TERMINATE;
    };
  }

  private IllegalStateException invalidConfiguration() {
    return new IllegalStateException(
        MAX_SEQUENTIAL_POPUPS_SETTING_KEY + " must be a positive integer");
  }

  public enum ClaimDecision {
    CLAIM,
    TERMINATED,
    EXPIRED,
    CAP_REACHED
  }

  public enum OutcomeDecision {
    KEEP_CURRENT,
    CONTINUE,
    TERMINATE
  }
}
