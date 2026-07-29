package com.fxplatform.trading.scenario;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

public final class ScenarioDatabaseGuard
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final Pattern OWNED_DATABASE =
      Pattern.compile("^fx_scenario_it_[a-z0-9_]{1,45}$");
  private static final Set<String> FORBIDDEN_DATABASES =
      Set.of("fx_platform", "postgres", "template0", "template1");

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    Environment environment = applicationContext.getEnvironment();
    String expectedDatabase = environment.getProperty("scenario.database.name");
    String datasourceUrl = environment.getProperty("spring.datasource.url");

    validateBeforeStartup(
        environment.getActiveProfiles(), datasourceUrl, expectedDatabase);

    applicationContext.addApplicationListener((ContextRefreshedEvent event) -> {
      if (event.getApplicationContext() != applicationContext) {
        return;
      }
      String connectedDatabase = currentDatabase(
          applicationContext.getBean(DataSource.class));
      validateConnectedDatabase(expectedDatabase, connectedDatabase);
    });
  }

  static void validateBeforeStartup(
      String[] activeProfiles,
      String datasourceUrl,
      String expectedDatabase
  ) {
    boolean scenarioProfile = Arrays.stream(
            Objects.requireNonNullElseGet(activeProfiles, () -> new String[0]))
        .anyMatch("scenario-it"::equals);
    if (!scenarioProfile) {
      throw new IllegalStateException(
          "scenario-it profile is required before starting scenario tests");
    }

    String ownedName = normalizedDatabase(expectedDatabase);
    if (!OWNED_DATABASE.matcher(ownedName).matches()
        || FORBIDDEN_DATABASES.contains(ownedName)) {
      throw new IllegalStateException(
          "refusing unsafe scenario database: " + ownedName);
    }

    URI uri = datasourceUri(datasourceUrl);
    if (!"localhost".equalsIgnoreCase(uri.getHost()) || uri.getPort() != 5432) {
      throw new IllegalStateException(
          "scenario database must use localhost:5432");
    }

    String urlDatabase = normalizedDatabase(
        uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", ""));
    if (!ownedName.equals(urlDatabase)) {
      throw new IllegalStateException(
          "scenario database name does not match datasource URL: expected "
              + ownedName + " but URL targets " + urlDatabase);
    }
  }

  static void validateConnectedDatabase(
      String expectedDatabase,
      String connectedDatabase
  ) {
    String ownedName = normalizedDatabase(expectedDatabase);
    String actualName = normalizedDatabase(connectedDatabase);
    if (!OWNED_DATABASE.matcher(ownedName).matches()
        || FORBIDDEN_DATABASES.contains(ownedName)
        || !ownedName.equals(actualName)) {
      throw new IllegalStateException(
          "connected database is not the exact owned scenario database: expected "
              + ownedName + " but connected to " + actualName);
    }
  }

  private static URI datasourceUri(String datasourceUrl) {
    if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException(
          "scenario datasource must be a PostgreSQL JDBC URL");
    }
    try {
      return URI.create(datasourceUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "scenario datasource URL is invalid", exception);
    }
  }

  private static String normalizedDatabase(String database) {
    if (database == null || database.isBlank()) {
      throw new IllegalStateException("scenario database name is required");
    }
    return database.trim().toLowerCase(Locale.ROOT);
  }

  private static String currentDatabase(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
      if (!resultSet.next()) {
        throw new IllegalStateException(
            "database did not return current_database()");
      }
      return resultSet.getString(1);
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "unable to verify connected scenario database", exception);
    }
  }
}
