package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.entry;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingLabCredentialSanitizerTest {

  private static final Set<String> SENSITIVE_KEYS = Set.of(
      "authorization",
      "cookie",
      "set-cookie",
      "setCookie",
      "password",
      "token",
      "accessToken",
      "refreshToken",
      "api-key",
      "apiKey",
      "database-password",
      "databasePassword",
      "internal-secret",
      "internalSecret",
      "secret-access-key",
      "secretAccessKey",
      "MASSIVE_S3_ACCESS_KEY_ID",
      "access-key-id",
      "accessKeyId",
      "auth-code",
      "authCodeValue",
      "credential",
      "credentialBundle",
      "private-key",
      "privateKeyPem",
      "security.config.encryption-key",
      "encryptionKey",
      "passwordHash",
      "authorizationHeader",
      "clientSecretValue");

  private static final List<String> RAW_SECRETS = List.of(
      "raw-authorization",
      "raw-cookie",
      "raw-set-cookie",
      "raw-set-cookie-camel",
      "raw-password",
      "raw-token",
      "raw-access-token",
      "raw-refresh-token",
      "raw-api-key",
      "raw-api-key-camel",
      "raw-database-password",
      "raw-database-password-camel",
      "raw-internal-secret",
      "raw-internal-secret-camel",
      "raw-secret-access-key",
      "raw-secret-access-key-camel",
      "raw-access-key-id-env",
      "raw-access-key-id",
      "raw-access-key-id-camel",
      "raw-auth-code",
      "raw-auth-code-value",
      "raw-credential",
      "raw-credential-bundle",
      "raw-private-key",
      "raw-private-key-pem",
      "raw-encryption-key-config",
      "raw-encryption-key-camel",
      "raw-password-hash",
      "raw-authorization-header",
      "raw-client-secret-value");

  private final TradingLabCredentialSanitizer sanitizer =
      new TradingLabCredentialSanitizer();

  @Test
  void recursivelyRemovesCredentialKeysFromMapsListsAndArraysCaseInsensitively() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("safe", "root-safe");
    details.put("Authorization", "raw-authorization");
    details.put("nestedMap", linkedMap(
        "CoOkIe", "raw-cookie",
        "SET-COOKIE", "raw-set-cookie",
        "setCookie", "raw-set-cookie-camel",
        "safe", "map-safe"));
    details.put("nestedList", List.of(
        linkedMap("PaSsWoRd", "raw-password", "accessToken", "raw-access-token",
            "safe", "list-safe"),
        linkedMap("ToKeN", "raw-token", "refreshToken", "raw-refresh-token")));
    details.put("nestedArray", new Object[] {
        linkedMap("API-KEY", "raw-api-key", "apiKey", "raw-api-key-camel",
            "safe", "array-safe"),
        new Object[] {
            linkedMap("DATABASE-PASSWORD", "raw-database-password",
                "databasePassword", "raw-database-password-camel"),
            linkedMap("internal-secret", "raw-internal-secret",
                "internalSecret", "raw-internal-secret-camel",
                "MASSIVE_S3_SECRET_ACCESS_KEY", "raw-secret-access-key",
                "secretAccessKey", "raw-secret-access-key-camel",
                "MASSIVE_S3_ACCESS_KEY_ID", "raw-access-key-id-env",
                "access-key-id", "raw-access-key-id",
                "accessKeyId", "raw-access-key-id-camel",
                "auth-code", "raw-auth-code",
                "authCodeValue", "raw-auth-code-value",
                "credential", "raw-credential",
                "credentialBundle", "raw-credential-bundle",
                "private-key", "raw-private-key",
                "privateKeyPem", "raw-private-key-pem",
                "security.config.encryption-key", "raw-encryption-key-config",
                "encryptionKey", "raw-encryption-key-camel",
                "passwordHash", "raw-password-hash",
                "authorizationHeader", "raw-authorization-header",
                "clientSecretValue", "raw-client-secret-value")
        }
    });

    Map<String, Object> sanitized = sanitizer.sanitize(details);

    assertCredentialFree(sanitized);
    assertThat(valuesForKey(sanitized, "safe"))
        .containsExactlyInAnyOrder("root-safe", "map-safe", "list-safe", "array-safe");
  }

  @Test
  void returnsADeepCopyWithoutModifyingCallerOwnedContainers() {
    Map<String, Object> child = linkedMap(
        "password", "raw-password",
        "safe", "child-safe");
    List<Object> list = new ArrayList<>();
    list.add(child);
    Object[] array = {linkedMap("token", "raw-token", "safe", "array-safe")};
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("nested", child);
    details.put("list", list);
    details.put("array", array);

    Map<String, Object> sanitized = sanitizer.sanitize(details);

    assertThat(sanitized).isNotSameAs(details);
    assertThat(sanitized.get("nested")).isNotSameAs(child);
    assertThat(sanitized.get("list")).isNotSameAs(list);
    assertThat(sanitized.get("array")).isNotSameAs(array);
    assertCredentialFree(sanitized);

    assertThat(child).containsEntry("password", "raw-password");
    assertThat(((Map<?, ?>) list.getFirst()).get("password")).isEqualTo("raw-password");
    assertThat(((Map<?, ?>) array[0]).get("token")).isEqualTo("raw-token");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void rejectsNonStringMapKeys() {
    Map invalid = new LinkedHashMap();
    invalid.put(42, "not-a-json-object-key");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize((Map<String, Object>) invalid));
  }

  @Test
  void rejectsCircularContainerGraphs() {
    Map<String, Object> cyclic = new LinkedHashMap<>();
    cyclic.put("safe", "visible");
    cyclic.put("self", cyclic);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize(cyclic));
  }

  @Test
  void rejectsValuesOutsideTheJsonDataModel() {
    Map<String, Object> details = Map.of("unsupported", new Object());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize(details));
  }

  @Test
  void normalizesFullWidthAndZeroWidthSeparatorsButRejectsNonAsciiHomoglyphs() {
    Map<String, Object> bypasses = linkedMap(
        "ｐａｓｓｗｏｒｄ", "full-width-secret",
        "to\u200Bken", "zero-width-secret",
        "safe", "visible");

    assertThat(sanitizer.sanitize(bypasses)).containsOnly(entry("safe", "visible"));

    String homoglyphKey = "p\u0430ssword";
    String rawSecret = "must-not-appear-in-the-error";
    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize(Map.of(homoglyphKey, rawSecret)))
        .withMessageNotContaining(homoglyphKey)
        .withMessageNotContaining(rawSecret);
  }

  @Test
  void arbitraryCredentialTypeRemainsSensitiveWhileRootJsonValuesStaySupported() {
    Object sanitized = sanitizer.sanitizeJsonValue(List.of(
        linkedMap("credentialType", "raw-bearer", "safe", new BigDecimal("1.2300")),
        true));

    assertThat(sanitized).isEqualTo(List.of(
        linkedMap("safe", new BigDecimal("1.2300")),
        true));
  }

  @Test
  void rejectsExcessiveDepthAndNonFiniteNumbersWithoutEchoingValues() {
    Map<String, Object> tooDeep = linkedMap("leaf", "safe");
    for (int depth = 0; depth < 70; depth++) {
      tooDeep = linkedMap("nested", tooDeep);
    }
    Map<String, Object> excessivelyNested = tooDeep;

    String rawSecret = "non-finite-secret";
    Map<String, Object> nonFinite = linkedMap("safe", Double.NaN, "echo", rawSecret);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize(excessivelyNested))
        .withMessageNotContaining("leaf");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> sanitizer.sanitize(nonFinite))
        .withMessageNotContaining(rawSecret);
  }

  private static Map<String, Object> linkedMap(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  private static List<Object> valuesForKey(Object value, String expectedKey) {
    List<Object> values = new ArrayList<>();
    collectValuesForKey(value, expectedKey, values);
    return values;
  }

  private static void collectValuesForKey(
      Object value,
      String expectedKey,
      List<Object> values
  ) {
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, child) -> {
        if (expectedKey.equals(key)) {
          values.add(child);
        }
        collectValuesForKey(child, expectedKey, values);
      });
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      iterable.forEach(child -> collectValuesForKey(child, expectedKey, values));
      return;
    }
    if (value != null && value.getClass().isArray()) {
      for (int index = 0; index < Array.getLength(value); index++) {
        collectValuesForKey(Array.get(value, index), expectedKey, values);
      }
    }
  }

  private static void assertCredentialFree(Object value) {
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, child) -> {
        assertThat(key).isInstanceOf(String.class);
        assertThat(SENSITIVE_KEYS.stream()
            .map(TradingLabCredentialSanitizerTest::normalizeCredentialKey))
            .doesNotContain(normalizeCredentialKey((String) key));
        assertCredentialFree(child);
      });
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      iterable.forEach(TradingLabCredentialSanitizerTest::assertCredentialFree);
      return;
    }
    if (value != null && value.getClass().isArray()) {
      for (int index = 0; index < Array.getLength(value); index++) {
        assertCredentialFree(Array.get(value, index));
      }
      return;
    }
    if (value instanceof CharSequence text) {
      RAW_SECRETS.forEach(secret -> assertThat(text.toString()).doesNotContain(secret));
    }
  }

  private static String normalizeCredentialKey(String key) {
    return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
