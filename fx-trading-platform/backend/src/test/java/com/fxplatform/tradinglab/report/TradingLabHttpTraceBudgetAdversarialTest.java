package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TradingLabHttpTraceBudgetAdversarialTest {

  private static final URI URI_VALUE = URI.create(
      "https://validation.local/internal/run");
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Test
  void rawInputConstructorNeverCopiesOrTraversesCallerCollections() {
    Map<String, List<String>> hostileHeaders = new AbstractMap<>() {
      @Override
      public Set<Entry<String, List<String>>> entrySet() {
        throw new AssertionError("input constructor must not traverse headers");
      }
    };
    List<String> hostileScopes = new AbstractList<>() {
      @Override
      public String get(int index) {
        throw new AssertionError("input constructor must not read scopes");
      }

      @Override
      public int size() {
        throw new AssertionError("input constructor must not size scopes");
      }
    };

    assertThatCode(() -> new TradingLabHttpTraceInput(
        URI_VALUE,
        hostileHeaders,
        hostileHeaders,
        null,
        null,
        null,
        null,
        null,
        null,
        hostileScopes,
        null)).doesNotThrowAnyException();
  }

  @Test
  void sanitizerSnapshotsMutableCollectionsOnceAndSafeTraceDoesNotObserveLaterMutation()
      throws Exception {
    List<String> headerValues = new ArrayList<>(List.of("initial-header"));
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("X-Visible", headerValues);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("visible", "initial-body");
    TradingLabHttpTraceInput input = input(
        headers, Map.of(), "application/json", body, null, null, null);

    SafeTradingLabHttpTrace safe = sanitizer(1_048_576).sanitize(input);
    headerValues.add("late-header");
    headers.put("X-Late", List.of("late-map"));
    body.put("visible", "late-body");

    assertThat(JSON.writeValueAsString(safe.toSafeMap()))
        .contains("initial-header", "initial-body")
        .doesNotContain("late-header", "late-map", "late-body");
  }

  @Test
  void requestAndResponseBodiesShareOneByteBudget() {
    TradingLabHttpTraceSanitizer limited = sanitizer(4_096);
    String body = "{\"visible\":\"" + "x".repeat(2_200) + "\"}";

    assertCode(
        () -> limited.sanitize(input(
            Map.of(), Map.of(), "application/json", body, "application/json", body, null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");
  }

  @Test
  void allHeadersShareTheTraceBudgetAndHostileMapSizeIsNeverRead() {
    TradingLabHttpTraceSanitizer limited = sanitizer(4_096);
    Map<String, List<String>> cumulative = new LinkedHashMap<>();
    cumulative.put("X-One", List.of("a".repeat(1_500)));
    cumulative.put("X-Two", List.of("b".repeat(1_500)));
    cumulative.put("X-Three", List.of("c".repeat(1_500)));
    assertCode(
        () -> limited.sanitize(input(cumulative, Map.of(), null, null, null, null, null)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    Map<String, List<String>> hostileSize = new AbstractMap<>() {
      @Override
      public Set<Entry<String, List<String>>> entrySet() {
        return new AbstractSet<>() {
          @Override
          public Iterator<Entry<String, List<String>>> iterator() {
            return new Iterator<>() {
              private int index;

              @Override
              public boolean hasNext() {
                return index < 300;
              }

              @Override
              public Entry<String, List<String>> next() {
                return Map.entry("X-Header-" + index++, List.of("ok"));
              }
            };
          }

          @Override
          public int size() {
            throw new AssertionError("caller-owned map size must not be read");
          }
        };
      }
    };
    assertCode(
        () -> sanitizer(1_048_576).sanitize(
            input(hostileSize, Map.of(), null, null, null, null, null)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
  }

  @Test
  void throwableStackIsNeverClonedAndDeepCausesAreTruncated() {
    Throwable hostileStack = new IllegalStateException("controlled") {
      @Override
      public StackTraceElement[] getStackTrace() {
        throw new AssertionError("unbounded stack clone must not be requested");
      }
    };
    SafeTradingLabHttpTrace stackSafe = sanitizer(1_048_576).sanitize(
        input(Map.of(), Map.of(), null, null, null, null, hostileStack));
    Map<String, Object> exception = castMap(stackSafe.toSafeMap().get("exception"));
    assertThat(exception)
        .containsEntry("stackFrames", List.of())
        .containsEntry("stackTruncated", true);

    Throwable cause = null;
    for (int index = 0; index < 20; index++) {
      cause = new IllegalStateException("level-" + index, cause);
    }
    SafeTradingLabHttpTrace causeSafe = sanitizer(1_048_576).sanitize(
        input(Map.of(), Map.of(), null, null, null, null, cause));
    Map<String, Object> current = castMap(causeSafe.toSafeMap().get("exception"));
    while (current.containsKey("cause")) {
      current = castMap(current.get("cause"));
    }
    assertThat(current).containsEntry("causeTruncated", true);
  }

  @Test
  void malformedFormUsesBodyErrorAndHugeDecimalScalesFailBeforePlainAllocation() {
    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/x-www-form-urlencoded", "safe=%ZZ", null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");

    BigDecimal hostile = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/json", Map.of("visible", hostile), null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");
    BigInteger hugeMagnitude = BigInteger.ONE.shiftLeft(4_000_000);
    BigInteger hugeNegative = hugeMagnitude.negate();
    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/json", Map.of("visible", hugeNegative), null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");

    BigDecimal hugeUnscaled = new BigDecimal(hugeMagnitude, 0);
    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/json", Map.of("visible", hugeUnscaled), null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");

    BigDecimal subclass = new BigDecimal("1") {
      @Override
      public String toString() {
        throw new AssertionError("BigDecimal subclass must be rejected before conversion");
      }
    };
    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/json", Map.of("visible", subclass), null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");

    assertCode(
        () -> sanitizer(1_048_576).sanitize(input(
            Map.of(), Map.of(), null, null,
            "application/json", "{\"visible\":1e2147483647}", null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");
  }

  @Test
  void canonicalScannerRetainsStateAcrossTokensAndShortSecretsDoNotExpand() {
    String crossToken = "a\":\"b";
    TradingLabHttpTraceSanitizer crossTokenSanitizer =
        new TradingLabHttpTraceSanitizer(
            new TradingLabCredentialSanitizer(), List.of(crossToken));
    Map<String, Object> crossTokenBody = new LinkedHashMap<>();
    crossTokenBody.put("a", "b");
    crossTokenBody.put("password", crossToken);
    assertCode(
        () -> crossTokenSanitizer.sanitize(input(
            Map.of(), Map.of(), "application/json", crossTokenBody, null, null, null)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    TradingLabHttpTraceSanitizer expanding = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(), List.of("X"), 4_096);
    SafeTradingLabHttpTrace safe = expanding.sanitize(input(
        Map.of(), Map.of(), "application/json",
        Map.of("echo", "X".repeat(500)), null, null, null));

    assertThat(castMap(safe.toSafeMap().get("requestBody")))
        .containsExactly(Map.entry("echo", "[REDACTED]"));
  }

  @Test
  void shortKnownSecretRedactsALargePermittedLeafWithoutExpansion() {
    int maxBytes = 16 * 1024 * 1024;
    String repeatedSecret = "X".repeat(12 * 1024 * 1024);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("echo", repeatedSecret);
    body.put("password", "X");

    SafeTradingLabHttpTrace safe = sanitizer(maxBytes).sanitize(input(
        Map.of(), Map.of(), "application/json", body, null, null, null));

    assertThat(castMap(safe.toSafeMap().get("requestBody")))
        .containsExactly(Map.entry("echo", "[REDACTED]"));
  }

  @Test
  void hostileBodyListSizeAndInfiniteIteratorFailWithinTheTask3Budget() {
    AtomicInteger visits = new AtomicInteger();
    List<Object> hostile = new AbstractList<>() {
      @Override
      public Object get(int index) {
        throw new AssertionError("body traversal must use the iterator");
      }

      @Override
      public int size() {
        throw new AssertionError("caller-owned List.size() must not be called");
      }

      @Override
      public Iterator<Object> iterator() {
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public Object next() {
            visits.incrementAndGet();
            return "";
          }
        };
      }
    };

    assertCode(
        () -> sanitizer(4_096).sanitize(input(
            Map.of(), Map.of(), "application/json", hostile, null, null, null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");
    assertThat(visits.get()).isLessThanOrEqualTo(4_096);
  }

  @Test
  void productionConfigurationInjectsTheLogicalValueLimit() {
    TradingLabReportProperties properties = new TradingLabReportProperties();
    properties.setChunkBytes(TradingLabReportProperties.MIN_CHUNK_BYTES);
    properties.setMaxLogicalValueBytes(TradingLabReportProperties.MIN_CHUNK_BYTES);
    TradingLabHttpTraceSanitizer configured = new TradingLabReportConfiguration()
        .tradingLabHttpTraceSanitizer(
            new TradingLabCredentialSanitizer(),
            new TradingLabFixedValidationSecretProvider(List.of()),
            properties);
    String body = "{\"visible\":\"" + "x".repeat(2_200) + "\"}";

    assertCode(
        () -> configured.sanitize(input(
            Map.of(), Map.of(), "application/json", body, "application/json", body, null)),
        "TRADING_LAB_REPORT_UNSAFE_BODY");
  }

  private static TradingLabHttpTraceSanitizer sanitizer(int maxBytes) {
    return new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(), List.of(), maxBytes);
  }

  private static TradingLabHttpTraceInput input(
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      String requestContentType,
      Object requestBody,
      String responseContentType,
      Object responseBody,
      Throwable exception
  ) {
    return new TradingLabHttpTraceInput(
        URI_VALUE,
        requestHeaders,
        responseHeaders,
        requestContentType,
        requestBody,
        responseContentType,
        responseBody,
        exception,
        null,
        List.of(),
        null);
  }

  private static void assertCode(Runnable invocation, String code) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo(code));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
