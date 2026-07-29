package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingLabTraceSecretRegistryTest {

  @Test
  void mergePreflightIsAllOrNothingWhenTheUnionExceedsCapacity() {
    TradingLabReportSecretRegistry report = new TradingLabReportSecretRegistry();
    List<String> original = new ArrayList<>();
    for (int index = 0; index < 255; index++) {
      String secret = "REPORT_SECRET_" + index;
      original.add(secret);
      report.register(secret);
    }
    long generationBefore = report.generation();
    TradingLabTraceSecretRegistry trace = new TradingLabTraceSecretRegistry();
    trace.register("OVERFLOW_SECRET_256");
    trace.register("OVERFLOW_SECRET_257");

    assertThatThrownBy(() -> trace.mergeInto(report))
        .isInstanceOf(TradingLabTraceSecretRegistry.MergeOverflowException.class)
        .hasMessage("Unsafe Trading Lab HTTP trace secret registry");
    assertThat(report.values()).containsExactlyElementsOf(original);
    assertThat(report.generation()).isEqualTo(generationBefore);
  }
}
