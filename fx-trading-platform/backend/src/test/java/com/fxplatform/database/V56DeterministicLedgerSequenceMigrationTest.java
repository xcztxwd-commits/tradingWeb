package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V56DeterministicLedgerSequenceMigrationTest {

  @Test
  void migrationBackfillsOneSharedSequenceAcrossCashAndAssetLedgers() throws Exception {
    String migration = Files.readString(Path.of(
        "src/main/resources/db/migration/V56__deterministic_ledger_sequence.sql"));

    assertThat(migration).contains(
        "CREATE SEQUENCE ledger.entry_sequence",
        "ALTER TABLE ledger.ledger_entries",
        "ALTER TABLE ledger.asset_ledger_entries",
        "ADD COLUMN sequence_no BIGINT",
        "row_number() OVER",
        "nextval('ledger.entry_sequence'::regclass)",
        "CACHE 1",
        "OWNED BY NONE",
        "SELECT setval(",
        "false",
        "ALTER COLUMN sequence_no SET NOT NULL",
        "idx_ledger_account_sequence",
        "idx_asset_ledger_account_sequence");
  }
}
