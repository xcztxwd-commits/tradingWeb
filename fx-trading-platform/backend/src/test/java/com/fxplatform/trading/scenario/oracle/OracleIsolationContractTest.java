package com.fxplatform.trading.scenario.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OracleIsolationContractTest {

  private static final List<String> FORBIDDEN_PRODUCTION_TYPES = List.of(
      "PnLCalculator",
      "MarginCalculator",
      "PerpMarginCalculator",
      "TradingAlgorithmEngine",
      "PositionEngine",
      "SpotSettlementService",
      "SpotPositionService",
      "OrderHoldCalculator",
      "PerpetualOrderRiskService",
      "FundingService",
      "LiquidationService",
      "LiquidationSettlementService",
      "ProtectionOrderService",
      "FullFillCoordinator");

  @Test
  void oracleSourcesDoNotReferenceProductionCalculatorsOrSettlementEngines()
      throws IOException {
    Path oracleRoot = Path.of(
        "src/test/java/com/fxplatform/trading/scenario/oracle");
    List<Path> sources;
    try (var stream = Files.list(oracleRoot)) {
      sources = stream
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .filter(path -> !path.getFileName().toString().endsWith("Test.java"))
          .toList();
    }

    assertThat(sources).isNotEmpty();
    for (Path source : sources) {
      String text = Files.readString(source, StandardCharsets.UTF_8);
      assertThat(text).as(source.toString())
          .doesNotContain(FORBIDDEN_PRODUCTION_TYPES.toArray(String[]::new));
      assertThat(text.lines()
          .filter(line -> line.startsWith("import com.fxplatform."))
          .toList())
          .as(source.toString())
          .allMatch(line ->
              line.startsWith("import com.fxplatform.market.model.")
                  || line.startsWith("import com.fxplatform.trading.enums.")
                  || line.startsWith("import com.fxplatform.trading.scenario."));
    }
  }
}
