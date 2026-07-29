package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TradingLabReportSectionTest {

  @Test
  void freezesTheExactDatabaseVocabularyAndExportOrder() {
    assertThat(List.of(TradingLabReportSection.values()))
        .extracting(Enum::name)
        .containsExactly(
            "METADATA",
            "ACTOR",
            "ENVIRONMENT",
            "SCENARIO",
            "MODEL_VERSION",
            "CONFIG_SNAPSHOT",
            "LOCAL_CALCULATION",
            "LIFECYCLE",
            "API_TRACE",
            "MARKET_TICKS",
            "CHECKPOINTS",
            "ACTUAL_STATE",
            "ERRORS",
            "CLEANUP");

    assertThat(List.of(TradingLabReportSection.values()))
        .extracting(TradingLabReportSection::jsonKey)
        .containsExactly(
            "metadata",
            "actor",
            "environment",
            "scenario",
            "modelVersion",
            "configSnapshot",
            "localCalculation",
            "lifecycle",
            "apiTrace",
            "marketTicks",
            "checkpoints",
            "actualState",
            "errors",
            "cleanup");
  }

  @Test
  void freezesSingletonStringAndNdjsonShapes() {
    assertThat(List.of(TradingLabReportSection.values()))
        .filteredOn(TradingLabReportSection::isArray)
        .containsExactly(
            TradingLabReportSection.LIFECYCLE,
            TradingLabReportSection.API_TRACE,
            TradingLabReportSection.MARKET_TICKS,
            TradingLabReportSection.CHECKPOINTS,
            TradingLabReportSection.ERRORS);

    assertThat(List.of(TradingLabReportSection.values()))
        .filteredOn(TradingLabReportSection::isString)
        .containsExactly(TradingLabReportSection.MODEL_VERSION);

    assertThat(TradingLabReportSection.METADATA.emptyJson()).isEqualTo("{}");
    assertThat(TradingLabReportSection.MODEL_VERSION.emptyJson()).isEqualTo("\"\"");
    assertThat(TradingLabReportSection.MARKET_TICKS.emptyJson()).isEqualTo("[]");
  }
}
