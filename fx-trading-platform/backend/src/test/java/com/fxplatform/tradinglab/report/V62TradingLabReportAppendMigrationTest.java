package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class V62TradingLabReportAppendMigrationTest {

  private static final Path MIGRATION = Path.of(
      "src/main/resources/db/migration/V62__trading_lab_report_appends.sql");

  @Test
  void addsAForwardOnlyCascadeOwnedSourceEventIdempotencyLedger() throws IOException {
    assertThat(MIGRATION).isRegularFile();
    String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("create table trading_lab.report_appends")
        .contains("report_id uuid not null")
        .contains("section varchar(80) not null")
        .contains("source_sequence bigint not null")
        .contains("canonical_bytes bigint not null")
        .contains("canonical_checksum varchar(128) not null")
        .contains("primary key (report_id, section, source_sequence)")
        .contains("references trading_lab.reports(id) on delete cascade")
        .contains("check (source_sequence >= -1)")
        .contains("check (canonical_bytes > 0)");
  }

  @Test
  void neverMutatesTheFrozenV60OrV61Migrations() throws IOException {
    assertThat(Files.readString(Path.of(
        "src/main/resources/db/migration/V60__trading_lab_schema.sql")))
        .doesNotContain("report_appends");
    assertThat(Files.readString(Path.of(
        "src/main/resources/db/migration/V61__trading_lab_rbac.sql")))
        .doesNotContain("report_appends");
  }
}
