package com.fxplatform.tradinglab.e2e;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Streams a report into the bounded Node schema checker without buffering it in the JVM. */
final class TradingLabReportStreamingProbe {

  private static final long MAX_HEAP_DELTA_BYTES = 32L * 1024L * 1024L;
  private static final long MAX_RSS_DELTA_BYTES = 64L * 1024L * 1024L;
  private static final ObjectMapper JSON = new ObjectMapper();

  private TradingLabReportStreamingProbe() {
  }

  static Result check(InputStream input, long minBytes) throws Exception {
    assertThat(input).isNotNull();
    assertThat(minBytes).isPositive();
    Path platformRoot = platformRoot();
    Process checker = new ProcessBuilder(
        nodeBinary(),
        platformRoot.resolve("scripts/trading-lab-report-check.mjs").toString(),
        "--schema",
        platformRoot.resolve("docs/testing/trading-lab/report-schema.json").toString(),
        "--min-bytes",
        Long.toString(minBytes),
        "--max-heap-delta-bytes",
        Long.toString(MAX_HEAP_DELTA_BYTES),
        "--max-rss-delta-bytes",
        Long.toString(MAX_RSS_DELTA_BYTES))
        .directory(platformRoot.toFile())
        .start();

    try {
      IOException transferFailure = null;
      long transferred = 0L;
      try (OutputStream output = checker.getOutputStream()) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
          transferred = Math.addExact(transferred, read);
        }
      } catch (IOException exception) {
        transferFailure = exception;
      }

      if (!checker.waitFor(6, MINUTES)) {
        throw new AssertionError("Node report checker did not exit within six minutes");
      }
      String stdout = new String(
          checker.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
      String stderr = new String(
          checker.getErrorStream().readAllBytes(),
          StandardCharsets.UTF_8);
      assertThat(transferFailure)
          .as("HTTP-to-Node streaming transfer; checker stderr=%s", stderr)
          .isNull();
      assertThat(checker.exitValue())
          .as("Node report checker exit; stdout=%s stderr=%s", stdout, stderr)
          .isZero();

      JsonNode result = JSON.readTree(stdout);
      assertThat(result.path("status").asText()).isEqualTo("PASS");
      assertThat(result.path("totalBytes").asLong()).isEqualTo(transferred);
      assertThat(result.path("base64Wrapper").asBoolean()).isFalse();
      assertThat(result.path("topLevelSections").size()).isEqualTo(14);
      assertThat(result.path("largestInputChunkBytes").asLong())
          .isLessThanOrEqualTo(64L * 1024L);
      assertThat(result.path("peakHeapDeltaBytes").asLong())
          .isLessThanOrEqualTo(MAX_HEAP_DELTA_BYTES);
      assertThat(result.path("peakRssDeltaBytes").asLong())
          .isLessThanOrEqualTo(MAX_RSS_DELTA_BYTES);
      ArrayList<String> categories = new ArrayList<>();
      result.path("realHttpCategories").forEach(
          category -> categories.add(category.asText()));
      return new Result(
          result.path("totalBytes").asLong(),
          result.path("apiTraceItems").asInt(),
          List.copyOf(categories),
          result.path("peakHeapDeltaBytes").asLong(),
          result.path("peakRssDeltaBytes").asLong(),
          result.path("largestInputChunkBytes").asLong());
    } finally {
      if (checker.isAlive()) {
        try {
          checker.getOutputStream().close();
        } catch (IOException ignored) {
          // The checker may already have closed stdin after a fail-closed parse.
        }
        checker.destroyForcibly();
        if (!checker.waitFor(5, SECONDS)) {
          throw new AssertionError("Node report checker did not terminate");
        }
      }
    }
  }

  private static Path platformRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; depth < 4 && candidate != null; depth++) {
      if (Files.isRegularFile(
          candidate.resolve("scripts/trading-lab-report-check.mjs"))
          && Files.isRegularFile(
              candidate.resolve("docs/testing/trading-lab/report-schema.json"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new AssertionError("Cannot locate the fx-trading-platform root");
  }

  private static String nodeBinary() {
    String configured = System.getenv("TRADING_LAB_NODE_BINARY");
    return configured == null || configured.isBlank() ? "node" : configured;
  }

  record Result(
      long totalBytes,
      int apiTraceItems,
      List<String> realHttpCategories,
      long peakHeapDeltaBytes,
      long peakRssDeltaBytes,
      long largestInputChunkBytes
  ) {

    Result {
      realHttpCategories = List.copyOf(realHttpCategories);
    }
  }
}
