package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class EngagementSchemaMigrationIT {

  @Test
  void emptyPostgresMigratesFromV1ToLatestWithEngagementTablesAndRetentionConstraints() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);

      assertThat(count(jdbc, """
          select count(*) from flyway_schema_history
          where version in ('1', '63') and success = true
          """)).isEqualTo(2);
      assertThat(Set.copyOf(jdbc.queryForList("""
          select table_name
          from information_schema.tables
          where table_schema = 'content' and table_type = 'BASE TABLE'
          """, String.class))).contains(
              "content_items",
              "content_revisions",
              "content_assets",
              "popup_campaigns",
              "popup_campaign_targets",
              "popup_campaign_user_states",
              "popup_queue_sessions",
              "popup_deliveries",
              "message_publications",
              "message_targets",
              "message_receipts",
              "engagement_outbox");

      assertCompositeForeignKey(
          jdbc,
          "content_items",
          "(id, current_revision_id)",
          "content.content_revisions",
          "(content_item_id, id)");
      assertForeignKey(jdbc, "content_revisions", "content_item_id", "content.content_items");
      assertForeignKey(jdbc, "content_revisions", "cover_asset_id", "content.content_assets");
      assertForeignKey(jdbc, "popup_campaigns", "content_item_id", "content.content_items");
      assertForeignKey(jdbc, "popup_campaign_targets", "campaign_id", "content.popup_campaigns");
      assertForeignKey(jdbc, "popup_campaign_targets", "user_id", "auth.users");
      assertForeignKey(jdbc, "popup_campaign_user_states", "campaign_id", "content.popup_campaigns");
      assertForeignKey(jdbc, "popup_campaign_user_states", "user_id", "auth.users");
      assertForeignKey(jdbc, "popup_queue_sessions", "user_id", "auth.users");
      assertCompositeForeignKey(
          jdbc,
          "popup_deliveries",
          "(queue_session_id, user_id)",
          "content.popup_queue_sessions",
          "(id, user_id)");
      assertForeignKey(jdbc, "popup_deliveries", "campaign_id", "content.popup_campaigns");
      assertForeignKey(jdbc, "popup_deliveries", "user_id", "auth.users");
      assertForeignKey(jdbc, "popup_deliveries", "revision_id", "content.content_revisions");
      assertCompositeForeignKey(
          jdbc,
          "popup_campaign_user_states",
          "(active_delivery_id, campaign_id, user_id)",
          "content.popup_deliveries",
          "(id, campaign_id, user_id)");
      assertForeignKey(jdbc, "message_publications", "content_item_id", "content.content_items");
      assertCompositeForeignKey(
          jdbc,
          "message_publications",
          "(source_campaign_id, content_item_id)",
          "content.popup_campaigns",
          "(id, content_item_id)");
      assertForeignKey(jdbc, "message_targets", "publication_id", "content.message_publications");
      assertForeignKey(jdbc, "message_targets", "user_id", "auth.users");
      assertForeignKey(jdbc, "message_receipts", "publication_id", "content.message_publications");
      assertForeignKey(jdbc, "message_receipts", "user_id", "auth.users");

      assertUniqueIndex(jdbc, "popup_campaign_user_states", "(campaign_id, user_id)");
      assertUniqueIndex(jdbc, "popup_deliveries", "(token_hash)");
      assertUniqueIndex(jdbc, "message_receipts", "(publication_id, user_id)");
      assertUniqueIndex(jdbc, "message_publications", "(source_campaign_id)");
      assertUniqueIndex(jdbc, "content_revisions", "(content_item_id, id)");
      assertUniqueIndex(jdbc, "popup_campaigns", "(id, content_item_id)");
      assertUniqueIndex(jdbc, "popup_queue_sessions", "(id, user_id)");
      assertUniqueIndex(jdbc, "popup_deliveries", "(id, campaign_id, user_id)");

      assertThat(count(jdbc, """
          select count(*)
          from information_schema.columns
          where table_schema = 'content'
            and ((table_name = 'popup_campaigns' and column_name = 'deleted_at')
              or (table_name = 'message_publications' and column_name = 'deleted_at'))
          """)).isEqualTo(2);
      assertThat(jdbc.queryForList("""
          select child.relname || ':' || pg_get_constraintdef(constraint_row.oid)
          from pg_constraint constraint_row
          join pg_class child on child.oid = constraint_row.conrelid
          join pg_namespace child_schema on child_schema.oid = child.relnamespace
          where constraint_row.contype = 'f'
            and constraint_row.confdeltype = 'c'
            and child_schema.nspname = 'content'
            and child.relname in ('content_revisions', 'popup_deliveries', 'message_receipts')
          """, String.class)).as("retained engagement history must not use ON DELETE CASCADE")
          .isEmpty();
      assertThat(count(jdbc, """
          select count(*)
          from pg_constraint constraint_row
          join pg_class child on child.oid = constraint_row.conrelid
          join pg_namespace child_schema on child_schema.oid = child.relnamespace
          join pg_class parent on parent.oid = constraint_row.confrelid
          join pg_namespace parent_schema on parent_schema.oid = parent.relnamespace
          where constraint_row.contype = 'f'
            and child_schema.nspname = 'audit'
            and child.relname = 'audit_logs'
            and parent_schema.nspname = 'content'
          """)).as("audit history must remain independent from logical-delete parents")
          .isZero();
    }
  }

  private static void assertForeignKey(
      JdbcTemplate jdbc, String childTable, String childColumn, String parentTable) {
    List<String> definitions = jdbc.queryForList("""
        select pg_get_constraintdef(constraint_row.oid)
        from pg_constraint constraint_row
        join pg_class child on child.oid = constraint_row.conrelid
        join pg_namespace child_schema on child_schema.oid = child.relnamespace
        where constraint_row.contype = 'f'
          and child_schema.nspname = 'content'
          and child.relname = ?
        """, String.class, childTable);

    assertThat(definitions).as("content.%s.%s foreign key", childTable, childColumn)
        .anySatisfy(definition -> assertThat(definition)
            .contains("FOREIGN KEY (" + childColumn + ")")
            .contains("REFERENCES " + parentTable + "(id)"));
  }

  private static void assertUniqueIndex(JdbcTemplate jdbc, String table, String columns) {
    List<String> definitions = jdbc.queryForList("""
        select pg_get_indexdef(index_row.indexrelid)
        from pg_index index_row
        join pg_class indexed_table on indexed_table.oid = index_row.indrelid
        join pg_namespace table_schema on table_schema.oid = indexed_table.relnamespace
        where table_schema.nspname = 'content'
          and indexed_table.relname = ?
          and index_row.indisunique = true
        """, String.class, table);

    assertThat(definitions).as("content.%s unique index on %s", table, columns)
        .anySatisfy(definition -> assertThat(definition).contains(columns));
  }

  private static void assertCompositeForeignKey(
      JdbcTemplate jdbc,
      String childTable,
      String childColumns,
      String parentTable,
      String parentColumns) {
    List<String> definitions = jdbc.queryForList("""
        select pg_get_constraintdef(constraint_row.oid)
        from pg_constraint constraint_row
        join pg_class child on child.oid = constraint_row.conrelid
        join pg_namespace child_schema on child_schema.oid = child.relnamespace
        where constraint_row.contype = 'f'
          and child_schema.nspname = 'content'
          and child.relname = ?
        """, String.class, childTable);

    assertThat(definitions).as("content.%s%s foreign key", childTable, childColumns)
        .anySatisfy(definition -> assertThat(definition)
            .contains("FOREIGN KEY " + childColumns)
            .contains("REFERENCES " + parentTable + parentColumns));
  }

  private static int count(JdbcTemplate jdbc, String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}
