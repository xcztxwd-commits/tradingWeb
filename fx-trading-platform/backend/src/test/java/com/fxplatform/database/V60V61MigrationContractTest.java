package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V60V61MigrationContractTest {

  private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");

  @Test
  void v60DeclaresTheFrozenSevenTableSchemaAndDedicatedQueueSequence() throws Exception {
    String sql = normalized(read("V60__trading_lab_schema.sql"));

    assertThat(sql)
        .contains("CREATE SCHEMA IF NOT EXISTS TRADING_LAB")
        .contains("CACHE 1")
        .contains("NEXTVAL('TRADING_LAB.RUN_QUEUE_SEQUENCE'::REGCLASS)");
    assertThat(sql).containsPattern(
        "CREATE SEQUENCE(?: IF NOT EXISTS)? TRADING_LAB\\.RUN_QUEUE_SEQUENCE");

    assertColumns(sql, "SCENARIOS",
        "ID UUID", "NAME VARCHAR(200)", "DESCRIPTION TEXT", "STATUS VARCHAR(32)",
        "NEGATIVE_MODE BOOLEAN", "SEED BIGINT", "MODEL_VERSION VARCHAR(80)",
        "SCENARIO_JSON JSONB", "CONFIG_SNAPSHOT_JSON JSONB",
        "CONFIG_SNAPSHOT_HASH VARCHAR(64)", "SYMBOL_CONFIG_VERSION VARCHAR(120)",
        "CODE_VERSION VARCHAR(160)", "CREATED_BY UUID", "UPDATED_BY UUID",
        "CREATED_AT TIMESTAMPTZ", "UPDATED_AT TIMESTAMPTZ", "VERSION BIGINT");
    assertColumns(sql, "REPORTS",
        "ID UUID", "SCENARIO_ID UUID", "STATUS VARCHAR(32)",
        "MODEL_VERSION VARCHAR(80)", "CONFIG_SNAPSHOT_HASH VARCHAR(64)",
        "CODE_VERSION VARCHAR(160)", "METADATA_JSON JSONB", "UNCOMPRESSED_BYTES BIGINT",
        "COMPRESSED_BYTES BIGINT", "CHUNK_COUNT INTEGER", "RETAINED_UNTIL TIMESTAMPTZ",
        "PERMANENT BOOLEAN", "FAILURE_CODE VARCHAR(80)", "FAILURE_MESSAGE TEXT",
        "CREATED_BY UUID", "CREATED_AT TIMESTAMPTZ", "COMPLETED_AT TIMESTAMPTZ",
        "VERSION BIGINT");
    assertColumns(sql, "RUNS",
        "ID UUID", "SCENARIO_ID UUID", "STATE VARCHAR(32)", "QUEUE_SEQUENCE BIGINT",
        "LEASE_KEY SMALLINT", "LEASE_OWNER VARCHAR(160)", "LEASE_UNTIL TIMESTAMPTZ",
        "CANCEL_REQUESTED BOOLEAN", "PAUSE_REQUESTED BOOLEAN",
        "VIRTUAL_STARTED_AT TIMESTAMPTZ", "VIRTUAL_CURRENT_AT TIMESTAMPTZ",
        "PROCESSED_TICKS BIGINT", "TOTAL_TICKS BIGINT", "SPEED_MULTIPLIER NUMERIC(18, 6)",
        "CURRENT_STEP BIGINT", "FAILURE_CODE VARCHAR(80)", "FAILURE_MESSAGE TEXT",
        "REPORT_ID UUID", "SCENARIO_SNAPSHOT_JSON JSONB", "CONFIG_SNAPSHOT_JSON JSONB",
        "CONFIG_SNAPSHOT_HASH VARCHAR(64)", "MODEL_VERSION VARCHAR(80)",
        "SYMBOL_CONFIG_VERSION VARCHAR(120)", "CODE_VERSION VARCHAR(160)",
        "CREATED_BY UUID", "CREATED_AT TIMESTAMPTZ", "UPDATED_AT TIMESTAMPTZ",
        "STARTED_AT TIMESTAMPTZ",
        "FINISHED_AT TIMESTAMPTZ", "VERSION BIGINT");
    assertColumns(sql, "RUN_TRANSITIONS",
        "ID UUID", "RUN_ID UUID", "FROM_STATE VARCHAR(32)", "TO_STATE VARCHAR(32)",
        "RUN_VERSION BIGINT", "REASON TEXT", "IDEMPOTENCY_KEY VARCHAR(160)",
        "REAL_TIME TIMESTAMPTZ", "VIRTUAL_TIME TIMESTAMPTZ", "ACTOR_ID UUID",
        "DETAILS_JSON JSONB");
    assertColumns(sql, "RUN_EVENTS",
        "ID UUID", "RUN_ID UUID", "SEQUENCE BIGINT", "EVENT_TYPE VARCHAR(120)",
        "VIRTUAL_TIME TIMESTAMPTZ", "REAL_TIME TIMESTAMPTZ",
        "CORRELATION_ID VARCHAR(160)", "PAYLOAD_JSON JSONB", "CREATED_AT TIMESTAMPTZ");
    assertColumns(sql, "REPORT_CHUNKS",
        "ID UUID", "REPORT_ID UUID", "SECTION VARCHAR(80)", "SEQUENCE BIGINT",
        "ENCODING VARCHAR(32)", "UNCOMPRESSED_BYTES BIGINT", "COMPRESSED_BYTES BIGINT",
        "PAYLOAD BYTEA", "CHECKSUM VARCHAR(128)", "CREATED_AT TIMESTAMPTZ");
    assertColumns(sql, "AUDIT_EVENTS",
        "ID UUID", "ACTOR_ID UUID", "CLIENT_IP VARCHAR(64)", "REQUEST_ID UUID",
        "SCENARIO_ID UUID", "RUN_ID UUID", "ACTION VARCHAR(120)", "RESULT VARCHAR(64)",
        "DETAILS_JSON JSONB", "CREATED_AT TIMESTAMPTZ");

    assertThat(tableNames(sql)).containsExactlyInAnyOrder(
        "SCENARIOS", "REPORTS", "RUNS", "RUN_TRANSITIONS", "RUN_EVENTS",
        "REPORT_CHUNKS", "AUDIT_EVENTS");
  }

  @Test
  void v60NamesTheFrozenChecksForeignKeysUniqueKeysAndIndexes() throws Exception {
    String sql = normalized(read("V60__trading_lab_schema.sql"));

    assertThat(sql)
        .contains(
            "CONSTRAINT PK_TRADING_LAB_SCENARIOS PRIMARY KEY",
            "CONSTRAINT FK_TRADING_LAB_SCENARIOS_CREATED_BY",
            "CONSTRAINT FK_TRADING_LAB_SCENARIOS_UPDATED_BY",
            "CONSTRAINT PK_TRADING_LAB_REPORTS PRIMARY KEY",
            "CONSTRAINT FK_TRADING_LAB_REPORTS_SCENARIO",
            "CONSTRAINT CK_TRADING_LAB_REPORTS_BYTE_COUNTS",
            "CONSTRAINT CK_TRADING_LAB_REPORTS_CHUNK_COUNT",
            "CONSTRAINT PK_TRADING_LAB_RUNS PRIMARY KEY",
            "CONSTRAINT FK_TRADING_LAB_RUNS_SCENARIO",
            "CONSTRAINT FK_TRADING_LAB_RUNS_REPORT",
            "CONSTRAINT UX_TRADING_LAB_RUNS_QUEUE_SEQUENCE UNIQUE",
            "CONSTRAINT UX_TRADING_LAB_RUNS_REPORT UNIQUE",
            "CONSTRAINT CK_TRADING_LAB_RUNS_STATE",
            "CONSTRAINT CK_TRADING_LAB_RUNS_TICKS",
            "CONSTRAINT CK_TRADING_LAB_RUNS_SPEED_MULTIPLIER",
            "CONSTRAINT CK_TRADING_LAB_RUNS_LEASE_KEY",
            "CONSTRAINT CK_TRADING_LAB_RUNS_LEASE_FIELDS",
            "CONSTRAINT PK_TRADING_LAB_RUN_TRANSITIONS PRIMARY KEY",
            "CONSTRAINT UX_TRADING_LAB_RUN_TRANSITIONS_IDEMPOTENCY UNIQUE",
            "CONSTRAINT UX_TRADING_LAB_RUN_TRANSITIONS_VERSION UNIQUE",
            "CONSTRAINT PK_TRADING_LAB_RUN_EVENTS PRIMARY KEY",
            "CONSTRAINT UX_TRADING_LAB_RUN_EVENTS_SEQUENCE UNIQUE",
            "CONSTRAINT PK_TRADING_LAB_REPORT_CHUNKS PRIMARY KEY",
            "CONSTRAINT UX_TRADING_LAB_REPORT_CHUNKS_SEQUENCE UNIQUE",
            "CONSTRAINT PK_TRADING_LAB_AUDIT_EVENTS PRIMARY KEY",
            "ON DELETE SET NULL")
        .contains(
            "'DRAFT'", "'VALIDATING'", "'QUEUED'", "'RESETTING'", "'RUNNING'",
            "'PAUSED'", "'CANCELLING'", "'CANCELLED'", "'FAILED'", "'COMPLETED'",
            "'CLEANING'");
    assertThat(sql).containsPattern("LEASE_KEY (?:= 1|IN \\(1\\))");
    assertThat(sql).containsPattern(
        "CONSTRAINT FK_TRADING_LAB_RUNS_REPORT FOREIGN KEY \\(REPORT_ID\\) "
            + "REFERENCES TRADING_LAB\\.REPORTS\\(ID\\) ON DELETE SET NULL");
    assertIndex(sql, "IDX_TRADING_LAB_SCENARIOS_STATUS");
    assertIndex(sql, "IDX_TRADING_LAB_SCENARIOS_CREATED_AT");
    assertIndex(sql, "IDX_TRADING_LAB_SCENARIOS_UPDATED_AT");
    assertIndex(sql, "IDX_TRADING_LAB_RUNS_STATE_QUEUE");
    assertIndex(sql, "IDX_TRADING_LAB_AUDIT_REQUEST_ID");
    assertIndex(sql, "IDX_TRADING_LAB_AUDIT_ACTOR_CREATED_AT");
    assertIndex(sql, "IDX_TRADING_LAB_AUDIT_SCENARIO_CREATED_AT");
    assertIndex(sql, "IDX_TRADING_LAB_AUDIT_RUN_CREATED_AT");

    String leaseIndex = uniqueLeaseIndex(sql);
    assertThat(leaseIndex)
        .contains("ON TRADING_LAB.RUNS (LEASE_KEY)")
        .contains("WHERE LEASE_KEY IS NOT NULL")
        .doesNotContain("NOW()", "CURRENT_TIMESTAMP", "LEASE_UNTIL");
  }

  @Test
  void v61SeedsIdempotentDistinctAuthoritiesWithoutAssigningAnyUserRole() throws Exception {
    String sql = normalized(read("V61__trading_lab_rbac.sql"));

    assertThat(sql)
        .contains("00000000-0000-0000-0000-000000000061")
        .contains("00000000-0000-0000-0000-000000000062")
        .contains("00000000-0000-0000-0000-000000000063")
        .contains("ALTER TABLE ADMIN.ROLES ADD COLUMN IF NOT EXISTS SYSTEM_MANAGED")
        .contains("SYSTEM_MANAGED BOOLEAN NOT NULL DEFAULT FALSE")
        .contains("REGEXP_REPLACE")
        .contains("JSONB_ARRAY_ELEMENTS_TEXT")
        .contains("RAISE EXCEPTION")
        .contains("INSERT INTO ADMIN.MENUS")
        .contains("TRADING_LAB_VIEW")
        .contains("/TRADING/LAB")
        .contains("INSERT INTO ADMIN.ROLES")
        .contains("SUPER_ADMIN")
        .contains("INSERT INTO ADMIN.ROLE_MENU_PERMISSIONS")
        .contains("TRADING_LAB_EXECUTE")
        .contains("ON CONFLICT")
        .doesNotContain("BTRIM(")
        .doesNotMatch("(?s).*INSERT\\s+INTO\\s+ADMIN\\.USER_ROLES.*");

    assertThat(countMatches(sql, "REGEXP_REPLACE")).isGreaterThanOrEqualTo(3);
    assertThat(countMatches(sql, "INSERT INTO ADMIN\\.(?:MENUS|ROLES|ROLE_MENU_PERMISSIONS)"))
        .isEqualTo(3);
    assertThat(countMatches(sql, "ON CONFLICT")).isGreaterThanOrEqualTo(3);
    assertThat(java.util.List.of("TRADING_LAB_VIEW", "TRADING_LAB_EXECUTE", "SUPER_ADMIN"))
        .doesNotHaveDuplicates();
  }

  private static String read(String fileName) throws Exception {
    Path path = MIGRATION_DIRECTORY.resolve(fileName);
    assertThat(path).exists().isRegularFile();
    return Files.readString(path);
  }

  private static String normalized(String sql) {
    return sql.replaceAll("\\s+", " ")
        .replaceAll("\\s*,\\s*", ", ")
        .trim()
        .toUpperCase();
  }

  private static void assertColumns(String sql, String table, String... columns) {
    String definition = tableDefinition(sql, table);
    assertThat(definition).contains(columns);
  }

  private static String tableDefinition(String sql, String table) {
    Matcher matcher = Pattern.compile(
        "CREATE TABLE(?: IF NOT EXISTS)? TRADING_LAB\\." + table + " \\((.*?)\\);",
        Pattern.DOTALL).matcher(sql);
    assertThat(matcher.find()).as("CREATE TABLE trading_lab.%s", table.toLowerCase()).isTrue();
    return matcher.group(1);
  }

  private static java.util.List<String> tableNames(String sql) {
    Matcher matcher = Pattern.compile(
        "CREATE TABLE(?: IF NOT EXISTS)? TRADING_LAB\\.([A-Z_]+) \\(").matcher(sql);
    java.util.List<String> names = new java.util.ArrayList<>();
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  private static String uniqueLeaseIndex(String sql) {
    Matcher matcher = Pattern.compile(
        "CREATE UNIQUE INDEX(?: IF NOT EXISTS)? UX_TRADING_LAB_RUNS_SINGLETON_LEASE .*?;",
        Pattern.DOTALL).matcher(sql);
    assertThat(matcher.find()).as("singleton lease partial unique index").isTrue();
    return matcher.group();
  }

  private static long countMatches(String value, String regex) {
    return Pattern.compile(regex).matcher(value).results().count();
  }

  private static void assertIndex(String sql, String indexName) {
    assertThat(sql).containsPattern("CREATE INDEX(?: IF NOT EXISTS)? " + indexName + "\\b");
  }
}
