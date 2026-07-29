package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TradingLabReportSecretRegistryTest {

  @Test
  void registerAllCountOverflowIsAtomicAndDoesNotAdvanceGeneration() {
    TradingLabReportSecretRegistry registry = new TradingLabReportSecretRegistry();
    List<String> original = IntStream.range(0, 255)
        .mapToObj(index -> "COUNT_SECRET_" + index)
        .toList();
    registry.registerAll(original);
    long generationBefore = registry.generation();
    int tailBytesBefore = registry.canaryTailBytes();

    assertThatThrownBy(() -> registry.registerAll(
        List.of("COUNT_OVERFLOW_A", "COUNT_OVERFLOW_B")))
        .isInstanceOf(
            TradingLabReportSecretRegistry.RegistrationOverflowException.class)
        .hasMessage("Unsafe Trading Lab report value");

    assertThat(registry.values()).containsExactlyElementsOf(original);
    assertThat(registry.generation()).isEqualTo(generationBefore);
    assertThat(registry.canaryTailBytes()).isEqualTo(tailBytesBefore);

    registry.register("COUNT_FINAL_SECRET");
    assertThat(registry.values()).containsExactlyElementsOf(
        java.util.stream.Stream.concat(
            original.stream(), java.util.stream.Stream.of("COUNT_FINAL_SECRET"))
            .toList());
    assertThat(registry.generation()).isEqualTo(generationBefore + 1L);
  }

  @Test
  void registerAllUtf8ByteOverflowIsAtomicAndDoesNotAdvanceGeneration() {
    TradingLabReportSecretRegistry registry = new TradingLabReportSecretRegistry();
    String almostFull = "界".repeat(21_845);
    registry.register(almostFull);
    long generationBefore = registry.generation();
    int tailBytesBefore = registry.canaryTailBytes();

    assertThatThrownBy(() -> registry.registerAll(List.of("A", "BB")))
        .isInstanceOf(
            TradingLabReportSecretRegistry.RegistrationOverflowException.class)
        .hasMessage("Unsafe Trading Lab report value");

    assertThat(registry.values()).containsExactly(almostFull);
    assertThat(registry.generation()).isEqualTo(generationBefore);
    assertThat(registry.canaryTailBytes()).isEqualTo(tailBytesBefore);

    registry.register("Z");
    assertThat(registry.values()).containsExactly(almostFull, "Z");
    assertThat(registry.generation()).isEqualTo(generationBefore + 1L);
  }

  @Test
  void realBatchAdvancesGenerationOnceAndNoOpsDoNotAdvanceIt() {
    TradingLabReportSecretRegistry registry = new TradingLabReportSecretRegistry();

    registry.registerAll(Arrays.asList(null, "", "alpha", "alpha", "beta"));

    assertThat(registry.values()).containsExactly("alpha", "beta");
    assertThat(registry.generation()).isEqualTo(1L);

    registry.register(null);
    registry.register("");
    registry.register("alpha");
    registry.registerAll(Arrays.asList(null, "", "alpha", "beta", "alpha"));
    assertThat(registry.generation()).isEqualTo(1L);

    registry.register("gamma");
    assertThat(registry.values()).containsExactly("alpha", "beta", "gamma");
    assertThat(registry.generation()).isEqualTo(2L);
  }

  @Test
  void stagingCopyIsIndependentUntilAtomicMerge() {
    TradingLabReportSecretRegistry live = new TradingLabReportSecretRegistry();
    live.register("live-secret");
    long liveGeneration = live.generation();

    TradingLabReportSecretRegistry staging = live.stagingCopy();
    staging.registerAll(List.of("live-secret", "staged-secret"));

    assertThat(live.values()).containsExactly("live-secret");
    assertThat(live.generation()).isEqualTo(liveGeneration);
    assertThat(staging.values()).containsExactly("live-secret", "staged-secret");

    live.registerAll(staging.values());
    assertThat(live.values()).containsExactly("live-secret", "staged-secret");
    assertThat(live.generation()).isEqualTo(liveGeneration + 1L);

    staging.register("staging-only-secret");
    assertThat(live.values()).doesNotContain("staging-only-secret");
  }
}
