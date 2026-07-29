package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TradingLabReportCanonicalizerTest {

  @Test
  void canonicalizesDeterministicJsonWithoutPresentationWhitespace() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("zDecimal", new BigDecimal("1.2300"));
    value.put("aText", "line\r\n\"quoted\"\\slash\u2028separator");
    value.put("mNested", Map.of("z", 2, "a", 1));

    TradingLabCanonicalValue canonical =
        canonicalizer.canonicalize(TradingLabReportSection.METADATA, value);

    assertThat(canonical.utf8()).isEqualTo(
        "{\"aText\":\"line\\r\\n\\\"quoted\\\"\\\\slash\u2028separator\","
            + "\"mNested\":{\"a\":1,\"z\":2},\"zDecimal\":1.2300}");
    assertThat(canonical.checksum()).matches("sha256:[0-9a-f]{64}");
    assertThat(canonical.bytes()).doesNotContain((byte) '\r', (byte) '\n');
  }

  @Test
  void arrayValuesReceiveExactlyOneLfAndExplicitAdaptersAreStrings() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    UUID id = UUID.fromString("fbf3750e-01db-4b6c-8f74-57fdfb7fa8c3");
    Instant at = Instant.parse("2026-07-19T04:05:06Z");

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS,
        Map.of("id", id, "at", at));

    assertThat(canonical.utf8()).isEqualTo(
        "{\"at\":\"2026-07-19T04:05:06Z\","
            + "\"id\":\"fbf3750e-01db-4b6c-8f74-57fdfb7fa8c3\"}\n");
  }

  @Test
  void removesSensitiveKeysAndRedactsTheirValuesEchoedUnderSafeKeys() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    String canary = "canary-" + UUID.randomUUID();

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of(
            "accessToken", canary,
            "echo", "before-" + canary + "-after",
            "visible", List.of("ok")));

    assertThat(canonical.utf8())
        .isEqualTo("{\"echo\":\"before-[REDACTED]-after\",\"visible\":[\"ok\"]}")
        .doesNotContain(canary, "accessToken");
  }

  @Test
  void redactsALargeLeafContainingAShortSecretWithoutExpandingIt() {
    int maxBytes = 16 * 1024 * 1024;
    String repeatedSecret = "X".repeat(15 * 1024 * 1024);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("echo", repeatedSecret);
    value.put("password", "X");

    TradingLabCanonicalValue canonical = canonicalizer(maxBytes).canonicalize(
        TradingLabReportSection.CONFIG_SNAPSHOT, value);

    assertThat(canonical.utf8()).isEqualTo("{\"echo\":\"[REDACTED]\"}");
  }

  @Test
  void boundsFinalCanaryScanForLongCommonPrefixesAndHighRootDegree() {
    int maxBytes = 1024 * 1024;
    String visible = "a".repeat(900 * 1024);
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(maxBytes);
    TradingLabReportSecretRegistry commonPrefixSecrets = new TradingLabReportSecretRegistry();
    String prefix = "a".repeat(250);
    for (int index = 0; index < 256; index++) {
      commonPrefixSecrets.register(prefix + "-%03d".formatted(index));
    }

    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
          TradingLabReportSection.METADATA,
          Map.of("visible", visible),
          commonPrefixSecrets);
      assertThat(canonical.byteLength()).isGreaterThan(visible.length());
    });

    TradingLabReportSecretRegistry highDegreeSecrets = new TradingLabReportSecretRegistry();
    highDegreeSecrets.register("A-never-matches");
    for (char initial = '!'; initial <= '~'; initial++) {
      if (initial != 'A') {
        highDegreeSecrets.register(initial + "-never-matches");
      }
    }
    String highDegreeVisible = "A".repeat(900 * 1024);

    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
          TradingLabReportSection.METADATA,
          Map.of("visible", highDegreeVisible),
          highDegreeSecrets);
      assertThat(canonical.byteLength()).isGreaterThan(highDegreeVisible.length());
    });

    TradingLabReportSecretRegistry highNonRootDegreeSecrets =
        new TradingLabReportSecretRegistry();
    for (int index = 0; index < 256; index++) {
      highNonRootDegreeSecrets.register(
          "A" + (char) (0x0100 + index) + "-never-matches");
    }
    int highNonRootMaxBytes = 16 * 1024 * 1024;
    String highNonRootDegreeVisible = "A".repeat(15 * 1024 * 1024);
    TradingLabReportCanonicalizer highNonRootCanonicalizer =
        canonicalizer(highNonRootMaxBytes);

    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      TradingLabCanonicalValue canonical = highNonRootCanonicalizer.canonicalize(
          TradingLabReportSection.METADATA,
          Map.of("visible", highNonRootDegreeVisible),
          highNonRootDegreeSecrets);
      assertThat(canonical.byteLength()).isGreaterThan(highNonRootDegreeVisible.length());
    });
  }

  @Test
  void sanitizerFailureDoesNotMergeStagedSecrets() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register("existing-secret");
    long generationBefore = secrets.generation();
    String stagedSecret = "must-not-merge-" + UUID.randomUUID();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("accessToken", stagedSecret);
    value.put("安全", "rejected-non-ascii-key");

    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, value, secrets))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(stagedSecret);

    assertThat(secrets.values()).containsExactly("existing-secret");
    assertThat(secrets.generation()).isEqualTo(generationBefore);
  }

  @Test
  void postSanitizationShapeFailureRetainsDiscoveredSecrets() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    String discovered = "shape-failure-secret-" + UUID.randomUUID();

    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.MODEL_VERSION,
        Map.of("accessToken", discovered),
        secrets))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab report section has the wrong value shape")
        .hasMessageNotContaining(discovered);

    assertThat(secrets.values()).containsExactly(discovered);
    assertThat(secrets.generation()).isEqualTo(1L);
  }

  @Test
  void finalCanaryRejectionHasFixedMessageAndNoCause() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    String canary = "abc\",\"right\":\"def";
    secrets.register(canary);

    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS,
        Map.of("left", "abc", "right", "def"),
        secrets))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary)
        .hasNoCause()
        .satisfies(exception -> assertThat(exception.toString()).doesNotContain(canary));
  }

  @Test
  void bufferedCanaryScanSeamsUseFixedCauseFreeException() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    String bufferedCanary = "buffered-seam-canary-" + UUID.randomUUID();
    TradingLabCanonicalValue leaked = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS,
        Map.of("safeEcho", bufferedCanary));
    TradingLabLogicalAppend append = new TradingLabLogicalAppend(-1L, leaked);
    TradingLabReportSecretRegistry bufferedSecrets =
        new TradingLabReportSecretRegistry();
    bufferedSecrets.register(bufferedCanary);

    assertThatThrownBy(() -> canonicalizer.requireNoCanary(
        List.of(append), bufferedSecrets))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(bufferedCanary)
        .hasNoCause();

    String boundaryCanary = "prefix-boundary";
    TradingLabReportSecretRegistry boundarySecrets =
        new TradingLabReportSecretRegistry();
    boundarySecrets.register(boundaryCanary);
    assertThatThrownBy(() -> canonicalizer.requireNoCanary(
        "prefix-".getBytes(StandardCharsets.UTF_8),
        "boundary".getBytes(StandardCharsets.UTF_8),
        boundarySecrets))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(boundaryCanary)
        .hasNoCause();
  }

  @Test
  void sharedCanonicalizerConcurrentCallsDoNotShareScannerState() throws Exception {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Void>> futures = java.util.stream.IntStream.range(0, 16)
          .mapToObj(worker -> executor.submit(() -> {
            start.await();
            for (int iteration = 0; iteration < 20; iteration++) {
              String left = "left-" + worker;
              String right = "right-" + iteration;
              String canary = left + "\",\"right\":\"" + right;
              TradingLabReportSecretRegistry secrets =
                  new TradingLabReportSecretRegistry();
              secrets.register(canary);

              assertThatThrownBy(() -> canonicalizer.canonicalize(
                  TradingLabReportSection.ERRORS,
                  Map.of("left", left, "right", right),
                  secrets))
                  .isExactlyInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Unsafe Trading Lab report value")
                  .hasNoCause();

              TradingLabCanonicalValue safe = canonicalizer.canonicalize(
                  TradingLabReportSection.METADATA,
                  Map.of("visible", "safe-" + worker + "-" + iteration),
                  secrets);
              assertThat(safe.utf8()).contains("safe-" + worker + "-" + iteration);
            }
            return (Void) null;
          }))
          .toList();

      start.countDown();
      for (Future<Void> future : futures) {
        assertThat(future.get(20, TimeUnit.SECONDS)).isNull();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void rejectsOpaqueApiTraceUnsupportedBeansNonFiniteNumbersAndWrongShapes() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    AtomicBoolean getterCalled = new AtomicBoolean();
    Object bean = new Object() {
      @SuppressWarnings("unused")
      public String getSecret() {
        getterCalled.set(true);
        return "must-not-run";
      }
    };

    assertCode(
        () -> canonicalizer.canonicalize(
            TradingLabReportSection.API_TRACE, Map.of("safe", true)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, bean))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("must-not-run");
    assertThat(getterCalled).isFalse();
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, Map.of("number", Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, Map.of("number", Float.POSITIVE_INFINITY)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, Map.of("number", Double.NEGATIVE_INFINITY)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, "object-required"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.MODEL_VERSION, Map.of("string", "required")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnorderedCollectionsBecauseRetryBytesMustBeDeterministic() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);

    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.METADATA,
        Map.of("unordered", Set.of("a", "b"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value");
  }

  @Test
  void snapshotsHostileMapsAndListsWithoutCallingCallerOwnedSize() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);
    AtomicInteger listTraversals = new AtomicInteger();
    AtomicInteger mapTraversals = new AtomicInteger();
    List<Object> hostile = new AbstractList<>() {
      @Override
      public Object get(int index) {
        throw new AssertionError("Traversal must use the single iterator");
      }

      @Override
      public int size() {
        throw new AssertionError("Caller-owned List.size() must not be called");
      }

      @Override
      public Iterator<Object> iterator() {
        if (listTraversals.incrementAndGet() != 1) {
          throw new AssertionError("Caller-owned List must be traversed only once");
        }
        return List.<Object>of("a", "b").iterator();
      }
    };
    Map<String, Object> hostileMap = new AbstractMap<>() {
      @Override
      public Set<Entry<String, Object>> entrySet() {
        if (mapTraversals.incrementAndGet() != 1) {
          throw new AssertionError("Caller-owned Map must be traversed only once");
        }
        return Set.of(Map.entry("values", hostile));
      }

      @Override
      public int size() {
        throw new AssertionError("Caller-owned Map.size() must not be called");
      }
    };

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, hostileMap);

    assertThat(canonical.utf8()).isEqualTo("{\"values\":[\"a\",\"b\"]}");
    assertThat(listTraversals.get()).isOne();
    assertThat(mapTraversals.get()).isOne();
  }

  @Test
  void boundsSinglePassTraversalWithoutReturningAPartialSnapshot() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(64);
    AtomicInteger visits = new AtomicInteger();
    TradingLabCanonicalValue[] result = new TradingLabCanonicalValue[1];
    List<Object> unbounded = new AbstractList<>() {
      @Override
      public Object get(int index) {
        throw new AssertionError("Traversal must use the single iterator");
      }

      @Override
      public int size() {
        throw new AssertionError("Caller-owned List.size() must not be called");
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
        () -> result[0] = canonicalizer.canonicalize(
            TradingLabReportSection.METADATA, Map.of("values", unbounded)),
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE");
    assertThat(result[0]).isNull();
    assertThat(visits.get()).isLessThanOrEqualTo(64);
  }

  @Test
  void rejectsHugeIntegersAndScaleDrivenDecimalsBeforePlainFormatting() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(64);
    BigInteger hugeInteger = BigInteger.ONE.shiftLeft(1_000_000);

    assertCode(
        () -> canonicalizer.canonicalize(
            TradingLabReportSection.METADATA, Map.of("number", hugeInteger)),
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE");
    assertCode(
        () -> canonicalizer.canonicalize(
            TradingLabReportSection.METADATA,
            Map.of("number", new BigDecimal(hugeInteger, 0))),
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE");
    assertCode(
        () -> canonicalizer.canonicalize(
            TradingLabReportSection.METADATA,
            Map.of("number", new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE))),
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE");
    assertCode(
        () -> canonicalizer.canonicalize(
            TradingLabReportSection.METADATA,
            Map.of("number", new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE))),
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE");
  }

  @Test
  void preservesPlainDecimalScaleWithinTheConfiguredBudget() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(4096);

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA,
        Map.of(
            "fractional", new BigDecimal("0.00100"),
            "negativeScale", new BigDecimal(BigInteger.valueOf(123L), -2)));

    assertThat(canonical.utf8())
        .isEqualTo("{\"fractional\":0.00100,\"negativeScale\":12300}");
  }

  @Test
  void preservesPlainScaleBeyondJacksonsBuiltInPlainDecimalGuard() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(32 * 1024);

    TradingLabCanonicalValue positiveScale = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA,
        Map.of("number", new BigDecimal(BigInteger.ONE, 10_000)));
    TradingLabCanonicalValue negativeScale = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA,
        Map.of("number", new BigDecimal(BigInteger.ONE, -10_000)));

    assertThat(positiveScale.utf8()).isEqualTo(
        "{\"number\":0."
            + "0".repeat(9_999)
            + "1}");
    assertThat(negativeScale.utf8()).isEqualTo(
        "{\"number\":1"
            + "0".repeat(10_000)
            + "}");
  }

  @Test
  void canonicalizesLargeArrayIntoOneExactOwnedByteArray() {
    int payloadBytes = 6 * 1024 * 1024;
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(payloadBytes + 64);
    String payload = "x".repeat(payloadBytes);

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, Map.of("value", payload));
    byte[] owned = canonical.internalBytes();

    assertThat(canonical.byteLength()).isEqualTo(payloadBytes + 13);
    assertThat(canonical.internalBytes()).isSameAs(owned);
    assertThat(owned[0]).isEqualTo((byte) '{');
    assertThat(owned[owned.length - 1]).isEqualTo((byte) '\n');
  }

  @Test
  void enforcesTheLogicalByteBudgetWithoutEchoingTheRejectedValue() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer(32);
    String rejected = "x".repeat(64);

    assertThatThrownBy(() -> canonicalizer.canonicalize(
        TradingLabReportSection.LIFECYCLE, Map.of("value", rejected)))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode())
              .isEqualTo("TRADING_LAB_REPORT_VALUE_TOO_LARGE");
          assertThat(exception.getMessage()).doesNotContain(rejected);
        });
  }

  @Test
  void ignoresGlobalMapperModulesWhenProducingCanonicalBytes() {
    ObjectMapper hostileGlobalMapper = new ObjectMapper();
    SimpleModule hostileModule = new SimpleModule();
    hostileModule.addSerializer(String.class, new JsonSerializer<>() {
      @Override
      public void serialize(
          String value,
          JsonGenerator generator,
          SerializerProvider serializers
      ) throws IOException {
        generator.writeString("global-module-poison");
      }
    });
    hostileGlobalMapper.registerModule(hostileModule);
    TradingLabReportCanonicalizer canonicalizer = new TradingLabReportCanonicalizer(
        hostileGlobalMapper,
        new TradingLabCredentialSanitizer(),
        4096);

    TradingLabCanonicalValue canonical = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA,
        Map.of("safe", "visible"));

    assertThat(canonical.utf8()).isEqualTo("{\"safe\":\"visible\"}");
  }

  private static TradingLabReportCanonicalizer canonicalizer(int maxBytes) {
    return new TradingLabReportCanonicalizer(
        new ObjectMapper().findAndRegisterModules(),
        new TradingLabCredentialSanitizer(),
        maxBytes);
  }

  private static void assertCode(Runnable invocation, String code) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
