package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class EngagementAdminRbacMigrationPostgresIT {

  private static final UUID SUPER_ADMIN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000061");
  private static final UUID CAMPAIGN_MENU_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000651");
  private static final UUID MESSAGE_MENU_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000652");
  private static final UUID CAMPAIGN_PERMISSION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000653");
  private static final UUID MESSAGE_PERMISSION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000654");
  private static final UUID MAX_POPUPS_SETTING_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000655");
  private static final UUID RETENTION_SETTING_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000656");
  private static final UUID ORDINARY_ADMIN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000659");
  private static final UUID EXISTING_MENU_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000699");
  private static final Path MIGRATION = Path.of(
      "src/main/resources/db/migration/V65__engagement_admin_rbac_and_policy.sql");

  @Test
  void v65SqlUsesStableSeedsFailClosedPreflightAndNonDestructiveSettings() throws Exception {
    assertThat(MIGRATION).exists().isRegularFile();
    String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").toUpperCase();

    assertThat(sql)
        .contains(CAMPAIGN_MENU_ID.toString().toUpperCase())
        .contains(MESSAGE_MENU_ID.toString().toUpperCase())
        .contains(CAMPAIGN_PERMISSION_ID.toString().toUpperCase())
        .contains(MESSAGE_PERMISSION_ID.toString().toUpperCase())
        .contains(MAX_POPUPS_SETTING_ID.toString().toUpperCase())
        .contains(RETENTION_SETTING_ID.toString().toUpperCase())
        .contains("RAISE EXCEPTION 'RESERVED ENGAGEMENT RBAC AUTHORITY COLLISION'")
        .contains("CONTENT:CAMPAIGN:READ")
        .contains("CONTENT:POPUP-POLICY:UPDATE")
        .contains("CONTENT:MESSAGE:READ")
        .contains("ENGAGEMENT.POPUP.MAXSEQUENTIALPOPUPS")
        .contains("ENGAGEMENT.POPUP.DELIVERYRETENTIONDAYS")
        .contains("'3'")
        .contains("'365'")
        .doesNotContain("INSERT INTO ADMIN.USER_ROLES");
    assertThat(count(sql, "INSERT INTO ADMIN.MENUS")).isEqualTo(2);
    assertThat(count(sql, "INSERT INTO ADMIN.ROLE_MENU_PERMISSIONS")).isEqualTo(2);
    assertThat(count(sql, "INSERT INTO CONFIG.SYSTEM_SETTINGS")).isEqualTo(2);
    assertThat(count(sql, "ON CONFLICT DO NOTHING")).isGreaterThanOrEqualTo(6);
  }

  @Test
  void v65FailsClosedWithoutChangingUnknownRowsThenSeedsOnlySuperAdmin() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "64");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      jdbc.update("""
          insert into admin.roles (
            id, role_name, role_code, enabled, system_managed, sort_order, description
          ) values (?, 'Ordinary Admin', 'ADMIN', true, false, 20, 'Limited administrator')
          """, ORDINARY_ADMIN_ID);
      jdbc.update("""
          insert into admin.menus (
            id, menu_name, permission_key, path, component, menu_type, enabled, sort_order
          ) values (?, 'Existing menu', 'content:campaign:read', '/existing',
                    'ExistingPage', 'MENU', true, 7)
          """, EXISTING_MENU_ID);
      jdbc.update("""
          insert into config.system_settings (
            id, setting_key, setting_value, value_type, description, editable
          ) values (?, 'engagement.popup.deliveryRetentionDays', '400', 'INTEGER',
                    'Existing retained value', true)
          """, UUID.fromString("00000000-0000-0000-0000-000000000698"));

      assertThatThrownBy(() -> PostgresMigrationTestSupport.migrate(postgres, null))
          .hasRootCauseInstanceOf(java.sql.SQLException.class)
          .hasStackTraceContaining("Reserved engagement RBAC authority collision");
      assertThat(jdbc.queryForObject(
          "select menu_name from admin.menus where id = ?", String.class, EXISTING_MENU_ID))
          .isEqualTo("Existing menu");
      assertThat(count(jdbc, """
          select count(*) from admin.menus where id in (?, ?)
          """, CAMPAIGN_MENU_ID, MESSAGE_MENU_ID)).isZero();
      assertThat(jdbc.queryForObject("""
          select setting_value from config.system_settings
          where setting_key = 'engagement.popup.deliveryRetentionDays'
          """, String.class)).isEqualTo("400");

      jdbc.update("delete from admin.menus where id = ?", EXISTING_MENU_ID);
      PostgresMigrationTestSupport.migrate(postgres, null);

      assertThat(count(jdbc, """
          select count(*) from flyway_schema_history where version = '65' and success = true
          """)).isOne();
      assertThat(jdbc.queryForList("""
          select permission_key from admin.menus where id in (?, ?) order by permission_key
          """, String.class, CAMPAIGN_MENU_ID, MESSAGE_MENU_ID)).containsExactly(
              "content:campaign:read", "content:message:read");
      assertThat(count(jdbc, """
          select count(*)
          from admin.role_menu_permissions
          where role_id = ? and menu_id in (?, ?) and enabled = true
          """, SUPER_ADMIN_ID, CAMPAIGN_MENU_ID, MESSAGE_MENU_ID)).isEqualTo(2);
      assertThat(count(jdbc, """
          select count(*)
          from admin.role_menu_permissions
          where role_id = ? and menu_id in (?, ?)
          """, ORDINARY_ADMIN_ID, CAMPAIGN_MENU_ID, MESSAGE_MENU_ID)).isZero();
      assertButtons(jdbc, CAMPAIGN_MENU_ID, List.of(
          "content:campaign:edit",
          "content:campaign:publish",
          "content:campaign:delete",
          "content:campaign:stats",
          "content:campaign:user-detail",
          "content:popup-policy:update"));
      assertButtons(jdbc, MESSAGE_MENU_ID, List.of(
          "content:message:edit",
          "content:message:send",
          "content:message:delete"));
      assertThat(jdbc.queryForObject("""
          select setting_value from config.system_settings
          where setting_key = 'engagement.popup.maxSequentialPopups'
          """, String.class)).isEqualTo("3");
      assertThat(jdbc.queryForObject("""
          select setting_value from config.system_settings
          where setting_key = 'engagement.popup.deliveryRetentionDays'
          """, String.class)).isEqualTo("400");
    }
  }

  private static void assertButtons(JdbcTemplate jdbc, UUID menuId, List<String> expected) {
    List<String> actual = jdbc.queryForList("""
        select value
        from admin.role_menu_permissions permission_row,
             jsonb_array_elements_text(permission_row.buttons) value
        where permission_row.role_id = ? and permission_row.menu_id = ?
        order by value
        """, String.class, SUPER_ADMIN_ID, menuId);
    assertThat(actual).containsExactlyElementsOf(expected.stream().sorted().toList());
  }

  private static int count(JdbcTemplate jdbc, String sql, Object... arguments) {
    return jdbc.queryForObject(sql, Integer.class, arguments);
  }

  private static long count(String value, String token) {
    return value.split(java.util.regex.Pattern.quote(token), -1).length - 1L;
  }
}
