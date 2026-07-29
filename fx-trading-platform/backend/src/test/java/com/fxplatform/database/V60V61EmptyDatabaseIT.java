package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class V60V61EmptyDatabaseIT {

  private static final UUID SEEDED_SUPER_ADMIN_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000061");
  private static final UUID SEEDED_TRADING_LAB_MENU_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000062");
  private static final UUID SEEDED_TRADING_LAB_PERMISSION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000063");
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 7, 18, 6, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void emptyPostgres16DatabaseMigratesToV61AndEnforcesTheFrozenPersistenceContract() {
    try (PostgreSQLContainer<?> postgres = startRequiredPostgres16()) {
      migrate(postgres, "61");
      JdbcTemplate jdbc = jdbc(postgres);

      assertThat(jdbc.queryForObject("show server_version", String.class)).startsWith("16.");
      assertThat(jdbc.queryForObject(
          "select max(version::integer) from flyway_schema_history where success = true",
          Integer.class)).isEqualTo(61);
      assertThat(Set.copyOf(jdbc.queryForList("""
          select table_name
          from information_schema.tables
          where table_schema = 'trading_lab' and table_type = 'BASE TABLE'
          """, String.class))).containsExactlyInAnyOrder(
              "scenarios", "reports", "runs", "run_transitions", "run_events",
              "report_chunks", "audit_events");

      assertFrozenColumns(jdbc);
      assertForeignKeyDeleteBehavior(jdbc);
      assertQueueSequenceAndLeaseIndex(jdbc);

      Fixture fixture = insertFixture(jdbc);
      assertJsonbAndByteaRoundTrips(jdbc, fixture);
      assertTransitionEventChunkAndLeaseUniqueness(jdbc, fixture);
      assertChecksAndScenarioRetention(jdbc, fixture);
      assertReportDeletionPreservesTheRun(jdbc, fixture);
      assertRbacSeeds(jdbc);
    }
  }

  @Test
  void v61IsIdempotentAndNeverBulkAssignsExistingAdmins() {
    try (PostgreSQLContainer<?> postgres = startRequiredPostgres16()) {
      migrate(postgres, "60");
      DataSource dataSource = dataSource(postgres);
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      UUID firstAdmin = insertUser(jdbc, "first-admin@trading-lab.test", "ADMIN");
      UUID secondAdmin = insertUser(jdbc, "second-admin@trading-lab.test", "ADMIN");

      assertThat(countUserRoles(jdbc, firstAdmin, secondAdmin)).isZero();
      migrate(postgres, "61");
      assertRbacSeeds(jdbc);
      assertThat(countUserRoles(jdbc, firstAdmin, secondAdmin)).isZero();

      jdbc.update("""
          insert into admin.user_roles (id, user_id, role_id)
          values (?, ?, ?)
          """, UUID.randomUUID(), firstAdmin, SEEDED_SUPER_ADMIN_ROLE_ID);
      assertThat(countUserRoles(jdbc, firstAdmin, secondAdmin)).isEqualTo(1);

      ResourceDatabasePopulator v61 = new ResourceDatabasePopulator();
      // Spring's default separator is not aware of PostgreSQL dollar-quoted DO blocks.
      v61.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
      v61.addScript(new ClassPathResource("db/migration/V61__trading_lab_rbac.sql"));
      v61.execute(dataSource);
      v61.execute(dataSource);

      assertRbacSeeds(jdbc);
      assertThat(countUserRoles(jdbc, firstAdmin, secondAdmin)).isEqualTo(1);
      assertThat(jdbc.queryForObject("""
          select count(*)
          from admin.user_roles
          where user_id = ? and role_id = ?
          """, Integer.class, firstAdmin, SEEDED_SUPER_ADMIN_ROLE_ID)).isEqualTo(1);
      assertThat(jdbc.queryForObject("""
          select count(*)
          from admin.user_roles
          where user_id = ?
          """, Integer.class, secondAdmin)).isZero();

      assertTrustedSeedTamperingAndFixedPairCollisionsAreRejected(
          v61, dataSource, jdbc, firstAdmin);
    }
  }

  @Test
  void v61FailsClosedForEachLegacyRbacCollisionBeforeCreatingItsSeedGraph() {
    try (PostgreSQLContainer<?> postgres = startRequiredPostgres16()) {
      migrate(postgres, "60");
      JdbcTemplate jdbc = jdbc(postgres);
      UUID adminId = insertUser(jdbc, "legacy-admin@trading-lab.test", "ADMIN");

      assertFixedSeedRoleWithExistingBindingIsRejected(postgres, jdbc, adminId);
      assertFixedSeedMenuWithoutRoleIsRejected(postgres, jdbc);
      assertCompleteUnmarkedSeedGraphWithExistingBindingIsRejected(
          postgres, jdbc, adminId);
      assertWhitespaceWrappedRoleIsRejected(postgres, jdbc);
      assertWhitespaceWrappedMenuIsRejected(postgres, jdbc);
      assertWhitespaceWrappedButtonIsRejected(postgres, jdbc);

      migrate(postgres, "61");
      assertRbacSeeds(jdbc);
      assertThat(jdbc.queryForObject(
          "select count(*) from admin.user_roles where user_id = ?",
          Integer.class, adminId)).isZero();
    }
  }

  private static void assertFixedSeedRoleWithExistingBindingIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc,
      UUID adminId
  ) {
    jdbc.update("""
        insert into admin.roles (id, role_name, role_code, enabled)
        values (?, 'Legacy fixed collision', 'SUPER_ADMIN', false)
        """, SEEDED_SUPER_ADMIN_ROLE_ID);
    jdbc.update("""
        insert into admin.user_roles (id, user_id, role_id)
        values (?, ?, ?)
        """, UUID.randomUUID(), adminId, SEEDED_SUPER_ADMIN_ROLE_ID);

    assertV61CollisionRollsBack(postgres, jdbc);

    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.user_roles
        where user_id = ? and role_id = ?
        """, Integer.class, adminId, SEEDED_SUPER_ADMIN_ROLE_ID)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select enabled from admin.roles where id = ?",
        Boolean.class, SEEDED_SUPER_ADMIN_ROLE_ID)).isFalse();

    jdbc.update("delete from admin.user_roles where user_id = ?", adminId);
    jdbc.update("delete from admin.roles where id = ?", SEEDED_SUPER_ADMIN_ROLE_ID);
  }

  private static void assertWhitespaceWrappedRoleIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc
  ) {
    UUID roleId = UUID.randomUUID();
    jdbc.update("""
        insert into admin.roles (id, role_name, role_code, enabled)
        values (?, 'Legacy role collision', ?, true)
        """, roleId, "\tSUPER_ADMIN\n");

    assertV61CollisionRollsBack(postgres, jdbc);

    jdbc.update("delete from admin.roles where id = ?", roleId);
  }

  private static void assertCompleteUnmarkedSeedGraphWithExistingBindingIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc,
      UUID adminId
  ) {
    jdbc.update("""
        insert into admin.roles (
          id, role_name, role_code, enabled, sort_order, description
        ) values (?, 'Super Admin', 'SUPER_ADMIN', true, 0,
          'Full Trading Path Lab environment control')
        """, SEEDED_SUPER_ADMIN_ROLE_ID);
    jdbc.update("""
        insert into admin.menus (
          id, parent_id, menu_name, permission_key, path, component,
          menu_type, enabled, sort_order
        ) values (?, null, 'Trading Path Lab', 'TRADING_LAB_VIEW',
          '/trading/lab', 'TradingLabPage', 'MENU', true, 90)
        """, SEEDED_TRADING_LAB_MENU_ID);
    insertCanonicalPermissionSeed(jdbc);
    jdbc.update("""
        insert into admin.user_roles (id, user_id, role_id)
        values (?, ?, ?)
        """, UUID.randomUUID(), adminId, SEEDED_SUPER_ADMIN_ROLE_ID);

    assertV61CollisionRollsBack(postgres, jdbc);

    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.user_roles
        where user_id = ? and role_id = ?
        """, Integer.class, adminId, SEEDED_SUPER_ADMIN_ROLE_ID)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.role_menu_permissions
        where id = ? and role_id = ? and menu_id = ?
        """, Integer.class, SEEDED_TRADING_LAB_PERMISSION_ID,
        SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo(1);

    jdbc.update("delete from admin.user_roles where user_id = ?", adminId);
    jdbc.update(
        "delete from admin.role_menu_permissions where id = ?",
        SEEDED_TRADING_LAB_PERMISSION_ID);
    jdbc.update("delete from admin.menus where id = ?", SEEDED_TRADING_LAB_MENU_ID);
    jdbc.update("delete from admin.roles where id = ?", SEEDED_SUPER_ADMIN_ROLE_ID);
  }

  private static void assertFixedSeedMenuWithoutRoleIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc
  ) {
    jdbc.update("""
        insert into admin.menus (
          id, menu_name, permission_key, path, component, menu_type, enabled
        ) values (?, 'Legacy fixed menu', 'TRADING_LAB_VIEW', '/trading/lab',
          'TradingLabPage', 'MENU', true)
        """, SEEDED_TRADING_LAB_MENU_ID);

    assertV61CollisionRollsBack(postgres, jdbc);

    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.menus
        where id = ?
          and permission_key = 'TRADING_LAB_VIEW'
          and path = '/trading/lab'
        """, Integer.class, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo(1);
    jdbc.update("delete from admin.menus where id = ?", SEEDED_TRADING_LAB_MENU_ID);
  }

  private static void assertWhitespaceWrappedMenuIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc
  ) {
    UUID menuId = UUID.randomUUID();
    jdbc.update("""
        insert into admin.menus (
          id, menu_name, permission_key, path, component, menu_type, enabled
        ) values (?, 'Legacy menu collision', ?, '/legacy-menu',
          'LegacyMenuPage', 'MENU', true)
        """, menuId, "\nTRADING_LAB_VIEW\t");

    assertV61CollisionRollsBack(postgres, jdbc);

    jdbc.update("delete from admin.menus where id = ?", menuId);
  }

  private static void assertWhitespaceWrappedButtonIsRejected(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc
  ) {
    UUID roleId = UUID.randomUUID();
    UUID menuId = UUID.randomUUID();
    UUID permissionId = UUID.randomUUID();
    jdbc.update("""
        insert into admin.roles (id, role_name, role_code, enabled)
        values (?, 'Legacy benign role', 'LEGACY_BENIGN', true)
        """, roleId);
    jdbc.update("""
        insert into admin.menus (
          id, menu_name, permission_key, path, component, menu_type, enabled
        ) values (?, 'Legacy benign menu', 'LEGACY_BENIGN_VIEW', '/legacy-benign',
          'LegacyBenignPage', 'MENU', true)
        """, menuId);
    jdbc.update("""
        insert into admin.role_menu_permissions (
          id, role_id, menu_id, buttons, enabled
        ) values (?, ?, ?, cast(? as jsonb), true)
        """, permissionId, roleId, menuId, "[\"\\nTRADING_LAB_EXECUTE\\t\"]");

    assertV61CollisionRollsBack(postgres, jdbc);

    jdbc.update("delete from admin.role_menu_permissions where id = ?", permissionId);
    jdbc.update("delete from admin.menus where id = ?", menuId);
    jdbc.update("delete from admin.roles where id = ?", roleId);
  }

  private static void assertV61CollisionRollsBack(
      PostgreSQLContainer<?> postgres,
      JdbcTemplate jdbc
  ) {
    assertThatThrownBy(() -> migrate(postgres, "61"))
        .hasMessageContaining("Reserved Trading Lab RBAC authority collision");
    assertThat(jdbc.queryForObject("""
        select max(version::integer)
        from flyway_schema_history
        where success = true
        """, Integer.class)).isEqualTo(60);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from information_schema.columns
        where table_schema = 'admin'
          and table_name = 'roles'
          and column_name = 'system_managed'
        """, Integer.class)).isZero();
  }

  private static void assertTrustedSeedTamperingAndFixedPairCollisionsAreRejected(
      ResourceDatabasePopulator v61,
      DataSource dataSource,
      JdbcTemplate jdbc,
      UUID boundAdminId
  ) {
    jdbc.update(
        "update admin.roles set system_managed = false where id = ?",
        SEEDED_SUPER_ADMIN_ROLE_ID);
    assertDirectV61Collision(v61, dataSource);
    assertThat(jdbc.queryForObject(
        "select system_managed from admin.roles where id = ?",
        Boolean.class, SEEDED_SUPER_ADMIN_ROLE_ID)).isFalse();
    jdbc.update(
        "update admin.roles set system_managed = true where id = ?",
        SEEDED_SUPER_ADMIN_ROLE_ID);

    jdbc.update(
        "update admin.menus set permission_key = 'LEGACY_WRONG' where id = ?",
        SEEDED_TRADING_LAB_MENU_ID);
    assertDirectV61Collision(v61, dataSource);
    assertThat(jdbc.queryForObject(
        "select permission_key from admin.menus where id = ?",
        String.class, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo("LEGACY_WRONG");
    jdbc.update(
        "update admin.menus set permission_key = 'TRADING_LAB_VIEW' where id = ?",
        SEEDED_TRADING_LAB_MENU_ID);

    UUID pairCollisionId = UUID.randomUUID();
    jdbc.update(
        "delete from admin.role_menu_permissions where id = ?",
        SEEDED_TRADING_LAB_PERMISSION_ID);
    jdbc.update("""
        insert into admin.role_menu_permissions (
          id, role_id, menu_id, buttons, enabled, created_at, updated_at
        ) values (?, ?, ?, '["TRADING_LAB_EXECUTE", "SUPER_ADMIN"]'::jsonb,
          true, now(), now())
        """, pairCollisionId, SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID);
    assertDirectV61Collision(v61, dataSource);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.role_menu_permissions
        where id = ? and role_id = ? and menu_id = ?
        """, Integer.class, pairCollisionId,
        SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo(1);
    jdbc.update(
        "delete from admin.role_menu_permissions where id = ?",
        pairCollisionId);
    insertCanonicalPermissionSeed(jdbc);

    UUID alternateRoleId = UUID.randomUUID();
    UUID alternateMenuId = UUID.randomUUID();
    jdbc.update("""
        insert into admin.roles (id, role_name, role_code, enabled)
        values (?, 'Alternate role', 'ALTERNATE_ROLE', true)
        """, alternateRoleId);
    jdbc.update("""
        insert into admin.menus (
          id, menu_name, permission_key, path, component, menu_type, enabled
        ) values (?, 'Alternate menu', 'ALTERNATE_VIEW', '/alternate',
          'AlternatePage', 'MENU', true)
        """, alternateMenuId);
    jdbc.update(
        "delete from admin.role_menu_permissions where id = ?",
        SEEDED_TRADING_LAB_PERMISSION_ID);
    jdbc.update("""
        insert into admin.role_menu_permissions (
          id, role_id, menu_id, buttons, enabled, created_at, updated_at
        ) values (?, ?, ?, '[]'::jsonb, true, now(), now())
        """, SEEDED_TRADING_LAB_PERMISSION_ID, alternateRoleId, alternateMenuId);
    assertDirectV61Collision(v61, dataSource);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.role_menu_permissions
        where id = ? and role_id = ? and menu_id = ?
        """, Integer.class, SEEDED_TRADING_LAB_PERMISSION_ID,
        alternateRoleId, alternateMenuId)).isEqualTo(1);
    jdbc.update(
        "delete from admin.role_menu_permissions where id = ?",
        SEEDED_TRADING_LAB_PERMISSION_ID);
    jdbc.update("delete from admin.menus where id = ?", alternateMenuId);
    jdbc.update("delete from admin.roles where id = ?", alternateRoleId);
    insertCanonicalPermissionSeed(jdbc);

    assertRbacSeeds(jdbc);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.user_roles
        where user_id = ? and role_id = ?
        """, Integer.class, boundAdminId, SEEDED_SUPER_ADMIN_ROLE_ID)).isEqualTo(1);
  }

  private static void assertDirectV61Collision(
      ResourceDatabasePopulator v61,
      DataSource dataSource
  ) {
    assertThatThrownBy(() -> v61.execute(dataSource))
        .hasMessageContaining("Reserved Trading Lab RBAC authority collision");
  }

  private static void insertCanonicalPermissionSeed(JdbcTemplate jdbc) {
    jdbc.update("""
        insert into admin.role_menu_permissions (
          id, role_id, menu_id, buttons, enabled, created_at, updated_at
        ) values (?, ?, ?, '["TRADING_LAB_EXECUTE", "SUPER_ADMIN"]'::jsonb,
          true, now(), now())
        """, SEEDED_TRADING_LAB_PERMISSION_ID,
        SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID);
  }

  private static PostgreSQLContainer<?> startRequiredPostgres16() {
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    postgres.start();
    return postgres;
  }

  private static void migrate(PostgreSQLContainer<?> postgres, String target) {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(target))
        .load()
        .migrate();
  }

  private static DriverManagerDataSource dataSource(PostgreSQLContainer<?> postgres) {
    return new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer<?> postgres) {
    return new JdbcTemplate(dataSource(postgres));
  }

  private static void assertFrozenColumns(JdbcTemplate jdbc) {
    assertColumns(jdbc, "scenarios", Map.ofEntries(
        e("id", uuid(false)),
        e("name", varchar(200, false)),
        e("description", text(true)),
        e("status", varchar(32, false)),
        e("negative_mode", bool(false)),
        e("seed", bigint(true)),
        e("model_version", varchar(80, false)),
        e("scenario_json", jsonb(false)),
        e("config_snapshot_json", jsonb(false)),
        e("config_snapshot_hash", varchar(64, false)),
        e("symbol_config_version", varchar(120, false)),
        e("code_version", varchar(160, false)),
        e("created_by", uuid(false)),
        e("updated_by", uuid(false)),
        e("created_at", timestamptz(false)),
        e("updated_at", timestamptz(false)),
        e("version", bigint(false))));
    assertColumns(jdbc, "reports", Map.ofEntries(
        e("id", uuid(false)),
        e("scenario_id", uuid(true)),
        e("status", varchar(32, false)),
        e("model_version", varchar(80, false)),
        e("config_snapshot_hash", varchar(64, false)),
        e("code_version", varchar(160, false)),
        e("metadata_json", jsonb(false)),
        e("uncompressed_bytes", bigint(false)),
        e("compressed_bytes", bigint(false)),
        e("chunk_count", integer(false)),
        e("retained_until", timestamptz(false)),
        e("permanent", bool(false)),
        e("failure_code", varchar(80, true)),
        e("failure_message", text(true)),
        e("created_by", uuid(false)),
        e("created_at", timestamptz(false)),
        e("completed_at", timestamptz(true)),
        e("version", bigint(false))));
    assertColumns(jdbc, "runs", Map.ofEntries(
        e("id", uuid(false)),
        e("scenario_id", uuid(false)),
        e("state", varchar(32, false)),
        e("queue_sequence", bigint(false)),
        e("lease_key", smallint(true)),
        e("lease_owner", varchar(160, true)),
        e("lease_until", timestamptz(true)),
        e("cancel_requested", bool(false)),
        e("pause_requested", bool(false)),
        e("virtual_started_at", timestamptz(true)),
        e("virtual_current_at", timestamptz(true)),
        e("processed_ticks", bigint(false)),
        e("total_ticks", bigint(false)),
        e("speed_multiplier", numeric(18, 6, false)),
        e("current_step", bigint(false)),
        e("failure_code", varchar(80, true)),
        e("failure_message", text(true)),
        e("report_id", uuid(true)),
        e("scenario_snapshot_json", jsonb(false)),
        e("config_snapshot_json", jsonb(false)),
        e("config_snapshot_hash", varchar(64, false)),
        e("model_version", varchar(80, false)),
        e("symbol_config_version", varchar(120, false)),
        e("code_version", varchar(160, false)),
        e("created_by", uuid(false)),
        e("created_at", timestamptz(false)),
        e("updated_at", timestamptz(false)),
        e("started_at", timestamptz(true)),
        e("finished_at", timestamptz(true)),
        e("version", bigint(false))));
    assertColumns(jdbc, "run_transitions", Map.ofEntries(
        e("id", uuid(false)),
        e("run_id", uuid(false)),
        e("from_state", varchar(32, false)),
        e("to_state", varchar(32, false)),
        e("run_version", bigint(false)),
        e("reason", text(true)),
        e("idempotency_key", varchar(160, false)),
        e("real_time", timestamptz(false)),
        e("virtual_time", timestamptz(true)),
        e("actor_id", uuid(true)),
        e("details_json", jsonb(false))));
    assertColumns(jdbc, "run_events", Map.ofEntries(
        e("id", uuid(false)),
        e("run_id", uuid(false)),
        e("sequence", bigint(false)),
        e("event_type", varchar(120, false)),
        e("virtual_time", timestamptz(true)),
        e("real_time", timestamptz(false)),
        e("correlation_id", varchar(160, true)),
        e("payload_json", jsonb(false)),
        e("created_at", timestamptz(false))));
    assertColumns(jdbc, "report_chunks", Map.ofEntries(
        e("id", uuid(false)),
        e("report_id", uuid(false)),
        e("section", varchar(80, false)),
        e("sequence", bigint(false)),
        e("encoding", varchar(32, false)),
        e("uncompressed_bytes", bigint(false)),
        e("compressed_bytes", bigint(false)),
        e("payload", bytea(false)),
        e("checksum", varchar(128, false)),
        e("created_at", timestamptz(false))));
    assertColumns(jdbc, "audit_events", Map.ofEntries(
        e("id", uuid(false)),
        e("actor_id", uuid(true)),
        e("client_ip", varchar(64, false)),
        e("request_id", uuid(false)),
        e("scenario_id", uuid(true)),
        e("run_id", uuid(true)),
        e("action", varchar(120, false)),
        e("result", varchar(64, false)),
        e("details_json", jsonb(false)),
        e("created_at", timestamptz(false))));
  }

  private static void assertColumns(
      JdbcTemplate jdbc, String table, Map<String, ColumnShape> expected) {
    Map<String, ColumnShape> actual = new LinkedHashMap<>();
    jdbc.query("""
        select column_name, data_type, character_maximum_length,
               numeric_precision, numeric_scale, is_nullable
        from information_schema.columns
        where table_schema = 'trading_lab' and table_name = ?
        order by ordinal_position
        """, rs -> {
          actual.put(rs.getString("column_name"), new ColumnShape(
              rs.getString("data_type"),
              (Integer) rs.getObject("character_maximum_length"),
              (Integer) rs.getObject("numeric_precision"),
              (Integer) rs.getObject("numeric_scale"),
              "YES".equals(rs.getString("is_nullable"))));
        }, table);

    assertThat(actual.keySet()).as("trading_lab.%s columns", table)
        .containsExactlyInAnyOrderElementsOf(expected.keySet());
    expected.forEach((column, shape) -> assertThat(actual.get(column))
        .as("trading_lab.%s.%s", table, column)
        .isEqualTo(shape));
  }

  private static void assertForeignKeyDeleteBehavior(JdbcTemplate jdbc) {
    Map<String, String> definitions = jdbc.query("""
        select conname, pg_get_constraintdef(oid)
        from pg_constraint
        where connamespace = 'trading_lab'::regnamespace and contype = 'f'
        """, rs -> {
          Map<String, String> values = new LinkedHashMap<>();
          while (rs.next()) {
            values.put(rs.getString(1), rs.getString(2));
          }
          return values;
        });

    assertThat(definitions.get("fk_trading_lab_reports_scenario")).contains("ON DELETE RESTRICT");
    assertThat(definitions.get("fk_trading_lab_runs_report")).contains("ON DELETE SET NULL");
    assertThat(definitions.get("fk_trading_lab_audit_scenario")).contains("ON DELETE SET NULL");
    assertThat(definitions.get("fk_trading_lab_audit_run")).contains("ON DELETE SET NULL");
  }

  private static void assertQueueSequenceAndLeaseIndex(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForObject("""
        select column_default
        from information_schema.columns
        where table_schema = 'trading_lab'
          and table_name = 'runs'
          and column_name = 'queue_sequence'
        """, String.class)).contains("trading_lab.run_queue_sequence");
    Map<String, Long> sequence = jdbc.queryForMap("""
        select increment_by, cache_size
        from pg_sequences
        where schemaname = 'trading_lab' and sequencename = 'run_queue_sequence'
        """).entrySet().stream().collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey,
            entry -> ((Number) entry.getValue()).longValue()));
    assertThat(sequence).containsEntry("increment_by", 1L).containsEntry("cache_size", 1L);

    String leaseIndex = jdbc.queryForObject("""
        select pg_get_indexdef(i.indexrelid) || ' WHERE ' ||
               pg_get_expr(i.indpred, i.indrelid)
        from pg_index i
        join pg_class idx on idx.oid = i.indexrelid
        where idx.relname = 'ux_trading_lab_runs_singleton_lease'
          and i.indisunique = true
        """, String.class);
    assertThat(leaseIndex)
        .contains("(lease_key)", "(lease_key IS NOT NULL)")
        .doesNotContainIgnoringCase("now()", "current_timestamp", "lease_until");
  }

  private static Fixture insertFixture(JdbcTemplate jdbc) {
    UUID actorId = insertUser(jdbc, "fixture-admin@trading-lab.test", "ADMIN");
    UUID scenarioId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    byte[] payload = new byte[] {0, 1, 2, 3, 127, -1};

    jdbc.update("""
        insert into trading_lab.scenarios (
          id, name, description, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, created_at, updated_at
        ) values (?, 'Round trip', 'fixture', 'DRAFT', false, 7, 'model-v1',
          cast(? as jsonb), cast(? as jsonb), ?, 'symbols-v1', 'code-v1', ?, ?, ?, ?)
        """, scenarioId, "{\"kind\":\"scenario\"}", "{\"mode\":\"demo\"}",
        "a".repeat(64), actorId, actorId, NOW, NOW);
    insertReport(jdbc, reportId, scenarioId, actorId, "report-fixture");
    insertRun(jdbc, runId, scenarioId, reportId, actorId, "run-fixture");

    jdbc.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version, reason, idempotency_key,
          real_time, virtual_time, actor_id, details_json
        ) values (?, ?, 'QUEUED', 'RESETTING', 1, 'fixture', 'transition-1',
          ?, ?, ?, cast(? as jsonb))
        """, UUID.randomUUID(), runId, NOW, NOW.plusSeconds(1), actorId,
        "{\"transition\":true}");
    jdbc.update("""
        insert into trading_lab.run_events (
          id, run_id, sequence, event_type, virtual_time, real_time,
          correlation_id, payload_json, created_at
        ) values (?, ?, 1, 'TICK', ?, ?, 'correlation-1', cast(? as jsonb), ?)
        """, UUID.randomUUID(), runId, NOW.plusSeconds(1), NOW,
        "{\"price\":60000}", NOW);
    jdbc.update("""
        insert into trading_lab.report_chunks (
          id, report_id, section, sequence, encoding, uncompressed_bytes,
          compressed_bytes, payload, checksum, created_at
        ) values (?, ?, 'events', 0, 'GZIP', 12, 6, ?, 'checksum-1', ?)
        """, UUID.randomUUID(), reportId, payload, NOW);
    jdbc.update("""
        insert into trading_lab.audit_events (
          id, actor_id, client_ip, request_id, scenario_id, run_id,
          action, result, details_json, created_at
        ) values (?, ?, '127.0.0.1', ?, ?, ?, 'RUN_CREATED', 'SUCCESS',
          cast(? as jsonb), ?)
        """, UUID.randomUUID(), actorId, requestId, scenarioId, runId,
        "{\"safe\":true}", NOW);

    return new Fixture(actorId, scenarioId, reportId, runId, requestId, payload);
  }

  private static void assertJsonbAndByteaRoundTrips(JdbcTemplate jdbc, Fixture fixture) {
    assertThat(jdbc.queryForObject(
        "select scenario_json ->> 'kind' from trading_lab.scenarios where id = ?",
        String.class, fixture.scenarioId())).isEqualTo("scenario");
    assertThat(jdbc.queryForObject(
        "select config_snapshot_json ->> 'mode' from trading_lab.scenarios where id = ?",
        String.class, fixture.scenarioId())).isEqualTo("demo");
    assertThat(jdbc.queryForObject(
        "select scenario_snapshot_json ->> 'snapshot' from trading_lab.runs where id = ?",
        String.class, fixture.runId())).isEqualTo("run-fixture");
    assertThat(jdbc.queryForObject(
        "select config_snapshot_json ->> 'mode' from trading_lab.runs where id = ?",
        String.class, fixture.runId())).isEqualTo("demo");
    assertThat(jdbc.queryForObject(
        "select metadata_json ->> 'report' from trading_lab.reports where id = ?",
        String.class, fixture.reportId())).isEqualTo("report-fixture");
    assertThat(jdbc.queryForObject(
        "select details_json ->> 'transition' from trading_lab.run_transitions where run_id = ?",
        String.class, fixture.runId())).isEqualTo("true");
    assertThat(jdbc.queryForObject(
        "select payload_json ->> 'price' from trading_lab.run_events where run_id = ?",
        String.class, fixture.runId())).isEqualTo("60000");
    assertThat(jdbc.queryForObject(
        "select details_json ->> 'safe' from trading_lab.audit_events where request_id = ?",
        String.class, fixture.requestId())).isEqualTo("true");
    byte[] actualPayload = jdbc.queryForObject(
        "select payload from trading_lab.report_chunks where report_id = ?",
        (rs, rowNum) -> rs.getBytes(1), fixture.reportId());
    assertThat(actualPayload).containsExactly(fixture.payload());
  }

  private static void assertReportDeletionPreservesTheRun(
      JdbcTemplate jdbc,
      Fixture fixture
  ) {
    assertThat(jdbc.update(
        "delete from trading_lab.reports where id = ?", fixture.reportId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from trading_lab.runs where id = ?",
        Integer.class,
        fixture.runId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select report_id is null from trading_lab.runs where id = ?",
        Boolean.class,
        fixture.runId())).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from trading_lab.report_chunks where report_id = ?",
        Integer.class,
        fixture.reportId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from trading_lab.run_transitions where run_id = ?",
        Integer.class,
        fixture.runId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from trading_lab.run_events where run_id = ?",
        Integer.class,
        fixture.runId())).isEqualTo(1);
  }

  private static void assertTransitionEventChunkAndLeaseUniqueness(
      JdbcTemplate jdbc, Fixture fixture) {
    assertConflict("ux_trading_lab_run_transitions_idempotency", () -> jdbc.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version, idempotency_key, real_time, details_json
        ) values (?, ?, 'RESETTING', 'RUNNING', 2, 'transition-1', ?, '{}'::jsonb)
        """, UUID.randomUUID(), fixture.runId(), NOW));
    assertConflict("ux_trading_lab_run_transitions_version", () -> jdbc.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version, idempotency_key, real_time, details_json
        ) values (?, ?, 'RESETTING', 'RUNNING', 1, 'transition-2', ?, '{}'::jsonb)
        """, UUID.randomUUID(), fixture.runId(), NOW));
    assertConflict("ux_trading_lab_run_events_sequence", () -> jdbc.update("""
        insert into trading_lab.run_events (
          id, run_id, sequence, event_type, real_time, payload_json, created_at
        ) values (?, ?, 1, 'DUPLICATE', ?, '{}'::jsonb, ?)
        """, UUID.randomUUID(), fixture.runId(), NOW, NOW));
    assertConflict("ux_trading_lab_report_chunks_sequence", () -> jdbc.update("""
        insert into trading_lab.report_chunks (
          id, report_id, section, sequence, encoding, uncompressed_bytes,
          compressed_bytes, payload, checksum, created_at
        ) values (?, ?, 'events', 0, 'GZIP', 1, 1, ?, 'duplicate', ?)
        """, UUID.randomUUID(), fixture.reportId(), new byte[] {9}, NOW));

    jdbc.update("""
        update trading_lab.runs
        set lease_key = 1, lease_owner = 'worker-one', lease_until = ?
        where id = ?
        """, NOW.plusMinutes(5), fixture.runId());
    UUID secondReport = UUID.randomUUID();
    UUID secondRun = UUID.randomUUID();
    insertReport(jdbc, secondReport, fixture.scenarioId(), fixture.actorId(), "second-report");
    insertRun(jdbc, secondRun, fixture.scenarioId(), secondReport, fixture.actorId(), "second-run");
    assertConflict("ux_trading_lab_runs_singleton_lease", () -> jdbc.update("""
        update trading_lab.runs
        set lease_key = 1, lease_owner = 'worker-two', lease_until = ?
        where id = ?
        """, NOW.plusMinutes(5), secondRun));

    Long queueSequence = jdbc.queryForObject(
        "select queue_sequence from trading_lab.runs where id = ?", Long.class, fixture.runId());
    assertConflict("ux_trading_lab_runs_queue_sequence", () -> jdbc.update(
        "update trading_lab.runs set queue_sequence = ? where id = ?",
        queueSequence, secondRun));
  }

  private static void assertChecksAndScenarioRetention(JdbcTemplate jdbc, Fixture fixture) {
    assertConflict("ck_trading_lab_reports_byte_counts", () -> jdbc.update(
        "update trading_lab.reports set compressed_bytes = -1 where id = ?",
        fixture.reportId()));
    assertConflict("ck_trading_lab_runs_ticks", () -> jdbc.update(
        "update trading_lab.runs set processed_ticks = -1 where id = ?", fixture.runId()));
    assertConflict("ck_trading_lab_runs_speed_multiplier", () -> jdbc.update(
        "update trading_lab.runs set speed_multiplier = 0 where id = ?", fixture.runId()));
    assertConflict("ck_trading_lab_runs_state", () -> jdbc.update(
        "update trading_lab.runs set state = 'NOT_A_STATE' where id = ?", fixture.runId()));
    assertConflict("ck_trading_lab_runs_lease_fields", () -> jdbc.update(
        "update trading_lab.runs set lease_owner = null where id = ?", fixture.runId()));
    assertConflict("fk_trading_lab_reports_scenario", () -> jdbc.update(
        "delete from trading_lab.scenarios where id = ?", fixture.scenarioId()));
  }

  private static void assertRbacSeeds(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.menus
        where id = ?
          and permission_key = 'TRADING_LAB_VIEW'
          and path = '/trading/lab'
          and enabled = true
        """, Integer.class, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from admin.roles
        where id = ?
          and role_code = 'SUPER_ADMIN'
          and enabled = true
          and system_managed = true
        """, Integer.class, SEEDED_SUPER_ADMIN_ROLE_ID)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select jsonb_array_length(p.buttons)
        from admin.role_menu_permissions p
        join admin.roles r on r.id = p.role_id
        join admin.menus m on m.id = p.menu_id
        where p.id = ?
          and r.id = ?
          and r.role_code = 'SUPER_ADMIN'
          and m.id = ?
          and m.permission_key = 'TRADING_LAB_VIEW'
          and p.enabled = true
        """, Integer.class, SEEDED_TRADING_LAB_PERMISSION_ID,
        SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID)).isEqualTo(2);
    List<String> buttons = jdbc.queryForList("""
        select jsonb_array_elements_text(p.buttons)
        from admin.role_menu_permissions p
        join admin.roles r on r.id = p.role_id
        join admin.menus m on m.id = p.menu_id
        where p.id = ?
          and r.id = ?
          and r.role_code = 'SUPER_ADMIN'
          and m.id = ?
          and m.permission_key = 'TRADING_LAB_VIEW'
          and p.enabled = true
        """, String.class, SEEDED_TRADING_LAB_PERMISSION_ID,
        SEEDED_SUPER_ADMIN_ROLE_ID, SEEDED_TRADING_LAB_MENU_ID);
    assertThat(buttons)
        .containsExactlyInAnyOrder("TRADING_LAB_EXECUTE", "SUPER_ADMIN")
        .doesNotHaveDuplicates();
  }

  private static UUID insertUser(JdbcTemplate jdbc, String email, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'test-hash', 'ACTIVE', ?)
        """, id, email, role);
    return id;
  }

  private static void insertReport(
      JdbcTemplate jdbc, UUID reportId, UUID scenarioId, UUID actorId, String marker) {
    jdbc.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version, config_snapshot_hash, code_version,
          metadata_json, uncompressed_bytes, compressed_bytes, chunk_count,
          retained_until, permanent, created_by, created_at
        ) values (?, ?, 'PENDING', 'model-v1', ?, 'code-v1', cast(? as jsonb),
          0, 0, 0, ?, false, ?, ?)
        """, reportId, scenarioId, "b".repeat(64), "{\"report\":\"" + marker + "\"}",
        NOW.plusDays(30), actorId, NOW);
  }

  private static void insertRun(
      JdbcTemplate jdbc,
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      String marker) {
    jdbc.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step, report_id,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version, created_by, created_at
        ) values (?, ?, 'QUEUED', false, false, 0, 10, 1.000000, 0, ?,
          cast(? as jsonb), cast(? as jsonb), ?, 'model-v1', 'symbols-v1', 'code-v1', ?, ?)
        """, runId, scenarioId, reportId, "{\"snapshot\":\"" + marker + "\"}",
        "{\"mode\":\"demo\"}", "c".repeat(64), actorId, NOW);
  }

  private static int countUserRoles(JdbcTemplate jdbc, UUID... userIds) {
    return jdbc.queryForObject("""
        select count(*)
        from admin.user_roles
        where user_id in (?, ?)
        """, Integer.class, userIds[0], userIds[1]);
  }

  private static void assertConflict(String constraintName, Runnable statement) {
    assertThatThrownBy(statement::run)
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(constraintName);
  }

  private static Map.Entry<String, ColumnShape> e(String name, ColumnShape shape) {
    return Map.entry(name, shape);
  }

  private static ColumnShape uuid(boolean nullable) {
    return new ColumnShape("uuid", null, null, null, nullable);
  }

  private static ColumnShape varchar(int length, boolean nullable) {
    return new ColumnShape("character varying", length, null, null, nullable);
  }

  private static ColumnShape text(boolean nullable) {
    return new ColumnShape("text", null, null, null, nullable);
  }

  private static ColumnShape bool(boolean nullable) {
    return new ColumnShape("boolean", null, null, null, nullable);
  }

  private static ColumnShape bigint(boolean nullable) {
    return new ColumnShape("bigint", null, 64, 0, nullable);
  }

  private static ColumnShape integer(boolean nullable) {
    return new ColumnShape("integer", null, 32, 0, nullable);
  }

  private static ColumnShape smallint(boolean nullable) {
    return new ColumnShape("smallint", null, 16, 0, nullable);
  }

  private static ColumnShape numeric(int precision, int scale, boolean nullable) {
    return new ColumnShape("numeric", null, precision, scale, nullable);
  }

  private static ColumnShape jsonb(boolean nullable) {
    return new ColumnShape("jsonb", null, null, null, nullable);
  }

  private static ColumnShape timestamptz(boolean nullable) {
    return new ColumnShape("timestamp with time zone", null, null, null, nullable);
  }

  private static ColumnShape bytea(boolean nullable) {
    return new ColumnShape("bytea", null, null, null, nullable);
  }

  private record ColumnShape(
      String dataType,
      Integer characterLength,
      Integer numericPrecision,
      Integer numericScale,
      boolean nullable) {
  }

  private record Fixture(
      UUID actorId,
      UUID scenarioId,
      UUID reportId,
      UUID runId,
      UUID requestId,
      byte[] payload) {
  }
}
