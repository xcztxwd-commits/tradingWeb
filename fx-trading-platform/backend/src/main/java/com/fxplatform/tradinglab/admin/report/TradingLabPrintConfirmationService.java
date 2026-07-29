package com.fxplatform.tradinglab.admin.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TradingLabPrintConfirmationService {

  static final Duration TTL = Duration.ofMinutes(5);
  private static final int TOKEN_BYTES = 32;

  private final TradingLabPrintConfirmationStore store;
  private final SecureRandom secureRandom;
  private final Clock clock;

  @Autowired
  public TradingLabPrintConfirmationService(
      TradingLabPrintConfirmationStore store
  ) {
    this(store, new SecureRandom(), Clock.systemUTC());
  }

  TradingLabPrintConfirmationService(
      TradingLabPrintConfirmationStore store,
      SecureRandom secureRandom,
      Clock clock
  ) {
    this.store = Objects.requireNonNull(store, "store");
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public TradingLabIssuedPrintConfirmation issue(
      UUID actorId,
      UUID reportId
  ) {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(reportId, "reportId");
    byte[] entropy = new byte[TOKEN_BYTES];
    String token;
    try {
      secureRandom.nextBytes(entropy);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    } finally {
      Arrays.fill(entropy, (byte) 0);
    }
    Instant expiresAt = clock.instant().plus(TTL);
    store.store(digest(token), actorId, reportId, TTL);
    return new TradingLabIssuedPrintConfirmation(token, expiresAt);
  }

  public void consume(
      String token,
      UUID actorId,
      UUID reportId
  ) {
    if (token == null || token.isBlank() || token.length() > 256) {
      throw TradingLabReportAdminException.confirmationInvalid();
    }
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(reportId, "reportId");
    if (!store.consume(digest(token), actorId, reportId)) {
      throw TradingLabReportAdminException.confirmationInvalid();
    }
  }

  private static String digest(String token) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
