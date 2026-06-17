package com.fxplatform.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RealtimeCandleRepositorySqlTest {

  @Test
  void fullCandleUpsertOverwritesVolumeInsteadOfAddingItAgain() throws Exception {
    String source = Files.readString(Path.of("src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java"));
    String fullCandleUpsert = source.substring(source.indexOf("public void upsertCandle"));

    assertThat(source).contains("upsertCandle");
    assertThat(source).contains("findLastOpenTime");
    assertThat(fullCandleUpsert).contains("source = EXCLUDED.source");
    assertThat(fullCandleUpsert).contains("volume = EXCLUDED.volume");
    assertThat(fullCandleUpsert).doesNotContain("volume = market.candles.volume + EXCLUDED.volume");
  }

  @Test
  void demoTickUpsertKeepsAccumulatingExistingCandles() throws Exception {
    String source = Files.readString(Path.of("src/main/java/com/fxplatform/market/repository/RealtimeCandleRepository.java"));
    String demoTickUpsert = source.substring(
        source.indexOf("public void upsert("),
        source.indexOf("public Optional<Instant> findLastOpenTime"));

    assertThat(demoTickUpsert).contains("GREATEST(market.candles.high, EXCLUDED.high)");
    assertThat(demoTickUpsert).contains("LEAST(market.candles.low, EXCLUDED.low)");
    assertThat(demoTickUpsert).contains("volume = market.candles.volume + EXCLUDED.volume");
  }
}
