package com.fxplatform.config.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SensitiveSettingService {

  public static final String MASK = "********";
  private static final String PREFIX = "enc:v1:";
  private static final int GCM_TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private static final List<String> SENSITIVE_MARKERS = List.of(
      "password",
      "secret",
      "token",
      "apikey",
      "api-key",
      "accesskey",
      "access-key",
      "authcode",
      "auth-code",
      "credential",
      "privatekey",
      "private-key");

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public SensitiveSettingService(@Value("${security.config.encryption-key}") String encryptionKey) {
    if (encryptionKey == null || encryptionKey.length() < 32) {
      throw new IllegalStateException("CONFIG_ENCRYPTION_KEY must be at least 32 characters");
    }
    this.key = new SecretKeySpec(sha256(encryptionKey), "AES");
  }

  public String storedValue(String settingKey, String requestedValue, String existingValue) {
    if (!isSensitive(settingKey)) {
      return requestedValue;
    }
    if (MASK.equals(requestedValue) && isEncrypted(existingValue)) {
      return existingValue;
    }
    if (isEncrypted(requestedValue)) {
      return requestedValue;
    }
    return encrypt(requestedValue);
  }

  public String responseValue(String settingKey, String storedValue) {
    return isSensitive(settingKey) ? MASK : storedValue;
  }

  public boolean isSensitive(String settingKey) {
    if (settingKey == null) {
      return false;
    }
    String normalized = settingKey.toLowerCase().replace("_", "-").replace(".", "-");
    return SENSITIVE_MARKERS.stream().anyMatch(normalized::contains);
  }

  private boolean isEncrypted(String value) {
    return value != null && value.startsWith(PREFIX);
  }

  private String encrypt(String value) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      secureRandom.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return PREFIX
          + Base64.getEncoder().encodeToString(nonce)
          + ":"
          + Base64.getEncoder().encodeToString(encrypted);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to encrypt sensitive setting", ex);
    }
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
