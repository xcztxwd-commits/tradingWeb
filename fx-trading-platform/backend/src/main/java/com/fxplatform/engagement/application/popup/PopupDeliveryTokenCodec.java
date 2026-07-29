package com.fxplatform.engagement.application.popup;

import com.fxplatform.common.security.TokenHashService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Issues opaque delivery credentials while persisting only domain-separated hashes. */
@Component
public class PopupDeliveryTokenCodec {

  private static final String HASH_DOMAIN = "popup-delivery:";
  private static final int TOKEN_BYTES = 32;

  private final TokenHashService tokenHashService;
  private final SecureRandom secureRandom = new SecureRandom();

  public PopupDeliveryTokenCodec(TokenHashService tokenHashService) {
    this.tokenHashService = Objects.requireNonNull(tokenHashService, "tokenHashService");
  }

  public String issuePlaintext() {
    byte[] value = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public String hash(String plaintext) {
    return tokenHashService.hash(HASH_DOMAIN + Objects.requireNonNull(plaintext, "plaintext"));
  }
}
