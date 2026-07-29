package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TradingLabCanonicalCanaryScannerTest {

  @Test
  void boundsHighDegreeTransitionsBelowTheRoot() {
    List<String> secrets = IntStream.rangeClosed(1, 127)
        .filter(value -> value != 'A')
        .mapToObj(value -> "A" + (char) value + "-never-matches")
        .toList();
    byte[] visible = new byte[16 * 1024 * 1024];
    Arrays.fill(visible, (byte) 'A');

    assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
        new TradingLabCanonicalCanaryScanner(secrets, visible.length).scan(visible));
  }

  @Test
  void sharedMatcherDoesNotCarryPartialPrefixAcrossContainsCalls() {
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register("abc");
    TradingLabCanonicalCanaryScanner.BytePatternMatcher matcher =
        canonicalizer().canaryMatcher(secrets);

    assertThat(matcher.contains("ab".getBytes(StandardCharsets.UTF_8))).isFalse();
    assertThat(matcher.containsUtf8("c")).isFalse();
    assertThat(matcher.containsUtf8("before-abc-after")).isTrue();
    assertThat(matcher.containsUtf8("c")).isFalse();
  }

  @Test
  void immutableMatcherSupportsConcurrentHitsAndMissesWithoutCrossTalk()
      throws Exception {
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register("shared-canary");
    TradingLabCanonicalCanaryScanner.BytePatternMatcher matcher =
        canonicalizer().canaryMatcher(secrets);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Void>> futures = IntStream.range(0, 16)
          .mapToObj(worker -> executor.submit(() -> {
            start.await();
            for (int iteration = 0; iteration < 100; iteration++) {
              assertThat(matcher.containsUtf8("safe-" + worker + "-" + iteration))
                  .isFalse();
              assertThat(matcher.containsUtf8("prefix-shared-canary-suffix"))
                  .isTrue();
              assertThat(matcher.containsUtf8("shared-")).isFalse();
              assertThat(matcher.containsUtf8("canary")).isFalse();
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

  private static TradingLabReportCanonicalizer canonicalizer() {
    return new TradingLabReportCanonicalizer(
        new TradingLabCredentialSanitizer(), 4096);
  }
}
