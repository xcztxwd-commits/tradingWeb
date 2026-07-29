package com.fxplatform.engagement.domain.popup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PopupQueuePolicyTest {

  private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");

  private final PopupQueuePolicy policy = new PopupQueuePolicy();

  @Test
  void exposesTheCanonicalSettingKeyAndDefaultsMissingConfigurationToThree() {
    assertThat(PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY)
        .isEqualTo("engagement.popup.maxSequentialPopups");
    assertThat(policy.snapshotMaxItems(null)).isEqualTo(3);
  }

  @Test
  void eachNewSessionSnapshotsTheConfigurationVisibleAtItsCreation() {
    int existingSessionSnapshot = policy.snapshotMaxItems("3");
    int newSessionSnapshotAfterAdminAdjustment = policy.snapshotMaxItems("7");

    assertThat(existingSessionSnapshot).isEqualTo(3);
    assertThat(newSessionSnapshotAfterAdminAdjustment).isEqualTo(7);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "three", "0", "-1", "2147483648"})
  void failsClosedForConfiguredValuesThatAreNotPositiveIntegers(String configuredValue) {
    assertThatIllegalStateException()
        .isThrownBy(() -> policy.snapshotMaxItems(configuredValue))
        .withMessageContaining("maxSequentialPopups");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("claimDecisions")
  void appliesClaimGuardsInTheirFrozenOrder(
      String description,
      int issuedCount,
      int maxItems,
      Instant expiresAt,
      Instant terminatedAt,
      PopupQueuePolicy.ClaimDecision expected) {
    assertThat(policy.beforeClaim(issuedCount, maxItems, expiresAt, terminatedAt, NOW))
        .as(description)
        .isEqualTo(expected);
  }

  private static Stream<Arguments> claimDecisions() {
    return Stream.of(
        Arguments.of(
            "termination wins over expiry and cap",
            3,
            3,
            NOW,
            NOW.minusSeconds(1),
            PopupQueuePolicy.ClaimDecision.TERMINATED),
        Arguments.of(
            "expiry at the exact boundary wins over cap",
            3,
            3,
            NOW,
            null,
            PopupQueuePolicy.ClaimDecision.EXPIRED),
        Arguments.of(
            "the snapshotted cap blocks another claim",
            3,
            3,
            NOW.plusSeconds(1),
            null,
            PopupQueuePolicy.ClaimDecision.CAP_REACHED),
        Arguments.of(
            "an open session below its cap may claim",
            2,
            3,
            NOW.plusSeconds(1),
            null,
            PopupQueuePolicy.ClaimDecision.CLAIM));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("outcomeDecisions")
  void mapsPopupOutcomesToQueueContinuation(
      PopupOutcome outcome,
      PopupQueuePolicy.OutcomeDecision expected) {
    assertThat(policy.afterOutcome(outcome)).isEqualTo(expected);
  }

  private static Stream<Arguments> outcomeDecisions() {
    return Stream.of(
        Arguments.of(PopupOutcome.SHOWN, PopupQueuePolicy.OutcomeDecision.KEEP_CURRENT),
        Arguments.of(PopupOutcome.CLOSE, PopupQueuePolicy.OutcomeDecision.CONTINUE),
        Arguments.of(PopupOutcome.OPT_OUT, PopupQueuePolicy.OutcomeDecision.CONTINUE),
        Arguments.of(PopupOutcome.CTA_CLICK, PopupQueuePolicy.OutcomeDecision.TERMINATE));
  }
}
