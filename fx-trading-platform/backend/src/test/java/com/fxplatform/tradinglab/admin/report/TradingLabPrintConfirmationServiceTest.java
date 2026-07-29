package com.fxplatform.tradinglab.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradingLabPrintConfirmationServiceTest {

  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID REPORT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void issueUses256SecureRandomBitsStoresOnlyDigestAndExpiresInFiveMinutes()
      throws Exception {
    TradingLabPrintConfirmationStore store =
        mock(TradingLabPrintConfirmationStore.class);
    SecureRandom random = mock(SecureRandom.class);
    doAnswer(invocation -> {
      byte[] target = invocation.getArgument(0);
      assertThat(target).hasSize(32);
      for (int index = 0; index < target.length; index++) {
        target[index] = (byte) index;
      }
      return null;
    }).when(random).nextBytes(org.mockito.ArgumentMatchers.any(byte[].class));
    TradingLabPrintConfirmationService service =
        new TradingLabPrintConfirmationService(
            store,
            random,
            Clock.fixed(NOW, ZoneOffset.UTC));

    TradingLabIssuedPrintConfirmation issued =
        service.issue(ACTOR_ID, REPORT_ID);

    assertThat(issued.token()).hasSize(43);
    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
    verify(store).store(
        digest.capture(),
        eq(ACTOR_ID),
        eq(REPORT_ID),
        eq(Duration.ofMinutes(5)));
    assertThat(digest.getValue())
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(issued.token())
        .isEqualTo(sha256(issued.token()));
  }

  @Test
  void consumeHashesTheHeaderAndFailsClosedForMissingMismatchOrReplay() {
    TradingLabPrintConfirmationStore store =
        mock(TradingLabPrintConfirmationStore.class);
    TradingLabPrintConfirmationService service =
        new TradingLabPrintConfirmationService(
            store,
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    String token = "t".repeat(43);
    when(store.consume(sha256(token), ACTOR_ID, REPORT_ID))
        .thenReturn(false);

    assertThatThrownBy(() -> service.consume(token, ACTOR_ID, REPORT_ID))
        .isInstanceOf(TradingLabReportAdminException.class)
        .extracting("code")
        .isEqualTo("TRADING_LAB_PRINT_CONFIRMATION_INVALID");

    verify(store).consume(sha256(token), ACTOR_ID, REPORT_ID);
  }

  @Test
  void blankTokenIsForbiddenWithoutTouchingRedis() {
    TradingLabPrintConfirmationStore store =
        mock(TradingLabPrintConfirmationStore.class);
    TradingLabPrintConfirmationService service =
        new TradingLabPrintConfirmationService(
            store,
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.consume(" ", ACTOR_ID, REPORT_ID))
        .isInstanceOf(TradingLabReportAdminException.class)
        .extracting("httpStatus")
        .isEqualTo(403);
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .consume(anyString(), eq(ACTOR_ID), eq(REPORT_ID));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
