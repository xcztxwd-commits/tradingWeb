package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;

class TradingLabHttpTraceSanitizerTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private final TradingLabHttpTraceSanitizer sanitizer = new TradingLabHttpTraceSanitizer(
      new TradingLabCredentialSanitizer(),
      List.of());

  @Test
  void trustedCredentialMetadataHasExactlyFiveSafeFieldsAndNeverRetainsTheRawBearer() throws Exception {
    UUID actorId = UUID.randomUUID();
    Instant expiresAt = Instant.parse("2026-07-19T03:00:00Z");
    String rawBearer = "bearer-" + UUID.randomUUID() + "-" + UUID.randomUUID();

    TradingLabCredentialMetadata metadata = TradingLabCredentialMetadata.bearer(
        actorId,
        List.of("TRADING_LAB_VIEW", "TRADING_LAB_EXECUTE"),
        expiresAt,
        rawBearer);

    assertThat(metadata.toSafeMap()).containsExactly(
        entry("credentialType", "BEARER"),
        entry("actorId", actorId.toString()),
        entry("scopes", List.of("TRADING_LAB_VIEW", "TRADING_LAB_EXECUTE")),
        entry("expiresAt", expiresAt.toString()),
        entry("fingerprint", metadata.fingerprint()));
    assertThat(metadata.fingerprint()).matches("sha256:[0-9a-f]{64}");
    assertThat(JSON.writeValueAsString(metadata.toSafeMap())).doesNotContain(rawBearer);
    assertThat(Arrays.stream(TradingLabCredentialMetadata.class.getDeclaredConstructors()))
        .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
    assertThat(Arrays.stream(TradingLabCredentialMetadata.class.getDeclaredFields()))
        .noneMatch(field -> field.getName().toLowerCase().contains("raw"));

    Map<String, Object> forged = new TradingLabCredentialSanitizer().sanitize(
        Map.of("credentialType", rawBearer, "safe", "visible"));
    assertThat(forged).containsOnly(entry("safe", "visible"));
  }

  @Test
  void shortOrMissingBearerFailsWithoutEchoingIt() {
    String rawBearer = "short-secret";

    assertThatIllegalArgumentException()
        .isThrownBy(() -> TradingLabCredentialMetadata.bearer(
            UUID.randomUUID(), List.of(), Instant.now(), rawBearer))
        .withMessageNotContaining(rawBearer);
  }

  @Test
  void factorySanitizesHeadersUriJsonBodyExceptionAndEscapedCanaries() throws Exception {
    UUID actorId = UUID.randomUUID();
    Instant expiresAt = Instant.parse("2026-07-19T03:00:00Z");
    String canary = "secret-\"quoted\"-\\slash-" + UUID.randomUUID();
    String encodedCanary = URLEncoder.encode(canary, StandardCharsets.UTF_8);
    String rawBearer = "BearerToken."
        + UUID.randomUUID().toString().replace("-", "");
    URI rawUri = URI.create(
        "https://user:" + encodedCanary + "@validation.local/internal/run"
            + "?access_token=" + encodedCanary + "&safeEcho=" + encodedCanary
            + "&visible=ok");

    Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
    requestHeaders.put("Authorization", List.of("Bearer " + rawBearer));
    requestHeaders.put("Cookie", List.of("session=" + canary));
    requestHeaders.put("X-Validation-Internal-Token", List.of(canary));
    requestHeaders.put("X-Safe-Echo", List.of("prefix-" + canary + "-suffix"));
    requestHeaders.put("X-Trace-Id", List.of("trace-visible"));

    Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    responseHeaders.put("Set-Cookie", List.of("session=" + canary + "; HttpOnly"));
    responseHeaders.put("Content-Type", List.of("application/json"));

    String requestBody = JSON.writeValueAsString(Map.of(
        "password", canary,
        "safeEcho", "before-" + canary + "-after",
        "visible", "request-visible"));
    Map<String, Object> responseBody = Map.of(
        "clientSecretValue", canary,
        "safeEcho", canary,
        "visible", "response-visible");

    IllegalStateException failure = new IllegalStateException(
        "request failed for " + canary,
        new IllegalArgumentException("nested cause " + canary));
    failure.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("Canary" + canary, "invoke", "Trace.java", 19)
    });

    SafeTradingLabHttpTrace safe = sanitizer.sanitize(new TradingLabHttpTraceInput(
        rawUri,
        requestHeaders,
        responseHeaders,
        "application/json",
        requestBody,
        "application/json",
        responseBody,
        failure,
        actorId,
        List.of("TRADING_LAB_EXECUTE"),
        expiresAt));

    Map<String, Object> value = safe.toSafeMap();
    String serialized = JSON.writeValueAsString(value);
    String escapedCanary = JSON.writeValueAsString(canary);
    escapedCanary = escapedCanary.substring(1, escapedCanary.length() - 1);
    assertThat(serialized)
        .doesNotContain(canary, escapedCanary, encodedCanary, rawBearer,
            "Authorization", "Cookie", "Set-Cookie",
            "X-Validation-Internal-Token", "clientSecretValue", "password")
        .contains("trace-visible", "request-visible", "response-visible", "[REDACTED]");
    assertThat(value.get("url")).isEqualTo("https://validation.local/internal/run");
    assertThat(castMap(value.get("queryParameters")))
        .containsEntry("visible", List.of("ok"))
        .containsEntry("safeEcho", List.of("[REDACTED]"))
        .doesNotContainKey("access_token");
    assertThat(castMap(value.get("authentication"))).containsOnlyKeys(
        "credentialType", "actorId", "scopes", "expiresAt", "fingerprint");
    assertThat(Modifier.isFinal(SafeTradingLabHttpTrace.class.getModifiers())).isTrue();
    assertThat(Arrays.stream(SafeTradingLabHttpTrace.class.getDeclaredConstructors()))
        .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
    assertThat(Arrays.stream(SafeTradingLabHttpTrace.class.getDeclaredFields()))
        .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
  }

  @Test
  void parsesJsonStringsAndFormsBeforeRemovingAndRedactingCredentials() throws Exception {
    String canary = "form-secret-" + UUID.randomUUID();
    String jsonBody = JSON.writeValueAsString(Map.of(
        "credentialType", canary,
        "safe", "json-visible"));
    String formBody = "password=" + URLEncoder.encode(canary, StandardCharsets.UTF_8)
        + "&echo=" + URLEncoder.encode(canary, StandardCharsets.UTF_8)
        + "&safe=form-visible";

    SafeTradingLabHttpTrace safe = sanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/state"),
        Map.of(),
        Map.of(),
        "application/json",
        jsonBody,
        "application/x-www-form-urlencoded",
        formBody,
        null,
        null,
        List.of(),
        null));

    Map<String, Object> value = safe.toSafeMap();
    assertThat(castMap(value.get("requestBody")))
        .containsOnly(entry("safe", "json-visible"));
    assertThat(castMap(value.get("responseBody")))
        .containsEntry("safe", List.of("form-visible"))
        .containsEntry("echo", List.of("[REDACTED]"))
        .doesNotContainKey("password");
    assertThat(JSON.writeValueAsString(value)).doesNotContain(canary);
  }

  @Test
  void canonicalResealInheritsDynamicSecretsAndScansTheCompleteEnvelope() {
    String dynamicSecret = "live-http-secret-" + UUID.randomUUID();
    SafeTradingLabHttpTrace source = sanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/state"),
        Map.of("X-Validation-Internal-Token", List.of(dynamicSecret)),
        Map.of(),
        null,
        null,
        "application/json",
        Map.of("safe", true),
        null,
        null,
        List.of(),
        null));
    TradingLabHttpTraceInput canonical = new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/state"),
        Map.of(),
        Map.of(),
        "application/json",
        Map.of("sequence", 7L, "environment", "validation"),
        "application/json",
        Map.of("status", 200),
        null,
        null,
        List.of(),
        null);

    SafeTradingLabHttpTrace resealed = sanitizer.sanitizeWithInheritedSecrets(
        canonical, source);

    assertThat(resealed.registeredSecrets()).contains(dynamicSecret);
    assertThat(resealed.toSafeMap().toString())
        .contains("sequence", "environment", "status")
        .doesNotContain(dynamicSecret);

    TradingLabHttpTraceInput leakingEnvelope = new TradingLabHttpTraceInput(
        canonical.uri(),
        Map.of(),
        Map.of(),
        "application/json",
        Map.of("safeEcho", dynamicSecret),
        "application/json",
        Map.of("status", 200),
        null,
        null,
        List.of(),
        null);
    SafeTradingLabHttpTrace redacted = sanitizer.sanitizeWithInheritedSecrets(
        leakingEnvelope, source);
    assertThat(redacted.toSafeMap().toString())
        .contains("[REDACTED]")
        .doesNotContain(dynamicSecret);
  }

  @Test
  void fixedInternalAndDatabaseSecretsAreRedactedButNeverFingerprinted() throws Exception {
    String internalSecret = "internal-\"quoted\"-\\slash-" + UUID.randomUUID();
    String databasePassword = "database-password-" + UUID.randomUUID();
    TradingLabHttpTraceSanitizer fixedRegistrySanitizer = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(internalSecret));

    SafeTradingLabHttpTrace safe = fixedRegistrySanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of("X-Safe-Echo", List.of(internalSecret)),
        Map.of(),
        "application/json",
        Map.of(
            "databasePassword", databasePassword,
            "databaseEcho", databasePassword),
        null,
        null,
        null,
        null,
        List.of(),
        null));

    String serialized = JSON.writeValueAsString(safe.toSafeMap());
    assertThat(serialized)
        .doesNotContain(internalSecret, databasePassword, "sha256:", "fingerprint")
        .contains("[REDACTED]");
    assertThat(safe.toSafeMap()).containsEntry("authentication", null);
  }

  @Test
  void authorizationWithoutTrustedActorProvenanceFailsClosedWithoutLeakingTheBearer() {
    String rawBearer = "bearer-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    TradingLabHttpTraceInput input = new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/state"),
        Map.of("Authorization", List.of("Bearer " + rawBearer)),
        Map.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null);

    assertThatThrownBy(() -> sanitizer.sanitize(input))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
          assertThat(exception.getMessage()).doesNotContain(rawBearer);
        });
  }

  @Test
  void opaqueBodyFailsClosedWithoutLeakingItsContents() {
    String rawBody = "opaque-secret-" + UUID.randomUUID();
    TradingLabHttpTraceInput input = new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/state"),
        Map.of(),
        Map.of(),
        null,
        null,
        "text/plain",
        rawBody,
        null,
        null,
        List.of(),
        null);

    assertThatThrownBy(() -> sanitizer.sanitize(input))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_BODY");
          assertThat(exception.getMessage()).doesNotContain(rawBody);
        });
  }

  @Test
  void strictPrivateJsonParserRejectsDuplicateKeysTrailingTokensAndExcessDepth() {
    assertUnsafeBody("{\"safe\":1,\"safe\":2}");
    assertUnsafeBody("{\"safe\":1} {\"second\":2}");
    assertUnsafeBody("[".repeat(66) + "0" + "]".repeat(66));
  }

  @Test
  void bodyBudgetRejectsHostileContainersWithoutTrustingSizeAndOpaqueBytes() {
    TradingLabHttpTraceSanitizer tinyBudget = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(),
        128);
    List<Object> hostile = new AbstractList<>() {
      @Override
      public Object get(int index) {
        throw new AssertionError("hostile list must use its explicit iterator");
      }

      @Override
      public int size() {
        throw new AssertionError("caller-owned size must not be read");
      }

      @Override
      public java.util.Iterator<Object> iterator() {
        return new java.util.Iterator<>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public Object next() {
            return null;
          }
        };
      }
    };

    assertThatThrownBy(() -> tinyBudget.sanitize(inputWithResponseBody(hostile)))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_BODY"));
    assertThatThrownBy(() -> sanitizer.sanitize(inputWithResponseBody(
        "opaque-secret".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_BODY"));
  }

  @Test
  void quotedBearerFailsClosedWhileQuotedSessionRegistersRawAndSemanticValues()
      throws Exception {
    String bearer = "quoted-bearer-" + UUID.randomUUID();
    TradingLabHttpTraceInput quotedBearer = new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of("Authorization", List.of("Bearer \"" + bearer + "\"")),
        Map.of(),
        null,
        null,
        null,
        null,
        null,
        UUID.randomUUID(),
        List.of("TRADING_LAB_EXECUTE"),
        Instant.parse("2026-07-19T03:00:00Z"));
    assertThatThrownBy(() -> sanitizer.sanitize(quotedBearer))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
          assertThat(exception.getMessage()).doesNotContain(bearer);
        });

    String semantic = "session-semantic-" + UUID.randomUUID();
    String raw = "\"" + semantic + "\"";
    SafeTradingLabHttpTrace safe = sanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of("Cookie", List.of("session=" + raw)),
        Map.of(),
        "application/json",
        Map.of("rawEcho", raw, "semanticEcho", semantic),
        null,
        null,
        null,
        UUID.randomUUID(),
        List.of("TRADING_LAB_EXECUTE"),
        Instant.parse("2026-07-19T03:00:00Z")));

    assertThat(JSON.writeValueAsString(safe.toSafeMap()))
        .doesNotContain(raw, semantic)
        .contains("[REDACTED]");
  }

  @Test
  void throwableSecretsAreRedactedBeforeCodePointTruncation() throws Exception {
    String secret = "S".repeat(3_000) + UUID.randomUUID();
    TradingLabHttpTraceSanitizer fixed = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(secret));
    SafeTradingLabHttpTrace safe = fixed.sanitize(new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        null,
        new IllegalStateException(secret + "-after"),
        null,
        List.of(),
        null));

    String serialized = JSON.writeValueAsString(safe.toSafeMap());
    assertThat(serialized)
        .doesNotContain(secret, secret.substring(0, 2_048))
        .contains("[REDACTED]-after");
  }

  @Test
  void productionConfigurationExposesTheSanitizerWithFixedSecrets() {
    List<String> fixed = java.util.stream.IntStream.range(0, 8)
        .mapToObj(index -> "fixed-production-secret-" + index + "-" + UUID.randomUUID())
        .toList();
    new ApplicationContextRunner()
        .withUserConfiguration(TradingLabReportConfiguration.class)
        .withBean(TradingLabReportProperties.class)
        .withBean(TradingLabCredentialSanitizer.class)
        .withPropertyValues(
            "VALIDATION_INTERNAL_SECRET=" + fixed.get(0),
            "ADMIN_BOOTSTRAP_PASSWORD=" + fixed.get(1),
            "MASSIVE_API_KEY=" + fixed.get(2),
            "MASSIVE_S3_ACCESS_KEY_ID=" + fixed.get(3),
            "MASSIVE_S3_SECRET_ACCESS_KEY=" + fixed.get(4),
            "EXECUTION_BROKER_API_KEY=" + fixed.get(5),
            "EXECUTION_FIX_API_KEY=" + fixed.get(6),
            "EXECUTION_LP_API_KEY=" + fixed.get(7))
        .run(context -> {
          assertThat(context).hasSingleBean(TradingLabHttpTraceSanitizer.class);
          assertThat(context).hasSingleBean(TradingLabFixedValidationSecretProvider.class);
          SafeTradingLabHttpTrace safe = context
              .getBean(TradingLabHttpTraceSanitizer.class)
              .sanitize(new TradingLabHttpTraceInput(
                  URI.create("https://validation.local/internal/run"),
                  Map.of("X-Safe-Echo", fixed),
                  Map.of(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  null));
          String serialized = safe.toSafeMap().toString();
          assertThat(serialized).contains("[REDACTED]");
          for (String secret : fixed) {
            assertThat(serialized).doesNotContain(secret);
          }
        });
  }

  @Test
  void fixedSecretProviderIsBoundedAndTheSafeMapCannotSerializeItsRegistry() throws Exception {
    assertThatThrownBy(() -> new TradingLabFixedValidationSecretProvider(
        List.of("x".repeat(16_385))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab validation secret configuration");
    assertThatThrownBy(() -> new TradingLabFixedValidationSecretProvider(
        java.util.stream.IntStream.range(0, 33).mapToObj(index -> "s-" + index).toList()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab validation secret configuration");

    String fixed = "registry-secret-" + UUID.randomUUID();
    SafeTradingLabHttpTrace safe = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(fixed))
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of("X-Safe-Echo", List.of(fixed)),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null));
    String json = JSON.writeValueAsString(safe.toSafeMap());
    assertThat(json)
        .doesNotContain(fixed, "secretRegistry", "TradingLabTraceSecretRegistry");
  }

  private void assertUnsafeBody(String body) {
    TradingLabHttpTraceInput input = new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of(),
        Map.of(),
        "application/json",
        body,
        null,
        null,
        null,
        null,
        List.of(),
        null);
    assertThatThrownBy(() -> sanitizer.sanitize(input))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_BODY"));
  }

  private TradingLabHttpTraceInput inputWithResponseBody(Object responseBody) {
    return new TradingLabHttpTraceInput(
        URI.create("https://validation.local/internal/run"),
        Map.of(),
        Map.of(),
        null,
        null,
        "application/json",
        responseBody,
        null,
        null,
        List.of(),
        null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
