package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.SymbolEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Step01ProductTypeAuditTest {

  @Test
  void symbolEntityPersistsExplicitProductType() {
    assertThat(Arrays.stream(SymbolEntity.class.getDeclaredFields()).map(Field::getName))
        .as("SymbolEntity must expose productType so CRYPTO_SPOT and perps do not depend on leverage")
        .contains("productType");
  }

  @Test
  void symbolApiResponseExposesExplicitProductType() {
    assertThat(Arrays.stream(SymbolResponse.class.getRecordComponents()).map(RecordComponent::getName))
        .as("SymbolResponse must expose productType to frontend TradePanel")
        .contains("productType");
  }

  @Test
  void migrationsCreateProductTypeColumn() throws IOException {
    String migrations = allMigrationText();

    assertThat(migrations)
        .as("DB migrations must add product_type and backfill FX_MARGIN/CRYPTO_SPOT/LINEAR_PERP/INVERSE_PERP")
        .contains("product_type")
        .contains("CRYPTO_SPOT")
        .contains("LINEAR_PERP")
        .contains("INVERSE_PERP");
  }

  @Test
  void migrationsEnforceProductTypeIntegrityAtDatabaseBoundary() throws IOException {
    String migration = migrationText("V38__symbol_product_type_integrity.sql");

    assertThat(migration)
        .as("product_type must have a hard default so old seed inserts that omit it stay deterministic")
        .contains("ALTER COLUMN product_type SET DEFAULT 'FX_MARGIN'");
    assertThat(migration)
        .as("product_type must reject explicit null writes")
        .contains("ALTER COLUMN product_type SET NOT NULL");
    assertThat(migration)
        .as("product_type must reject unknown values at the database boundary")
        .contains("CHECK (product_type IN ('FX_MARGIN', 'CRYPTO_SPOT', 'LINEAR_PERP', 'INVERSE_PERP'))");
  }

  @Test
  void migrationsBackfillEveryLegacyNullProductType() throws IOException {
    String migration = migrationText("V38__symbol_product_type_integrity.sql");

    assertThat(migration)
        .as("legacy rows whose asset_class is missing or unknown must still become non-null before NOT NULL is applied")
        .contains("ELSE 'FX_MARGIN'")
        .doesNotContain("ELSE product_type");
  }

  @Test
  void frontendTradePanelUsesProductTypeInsteadOfCategoryLeverageHeuristic() throws IOException {
    String tradePanelMarket = Files.readString(Path.of(
        "..",
        "apps",
        "web",
        "src",
        "features",
        "trading",
        "components",
        "tradePanelMarket.ts"));

    assertThat(tradePanelMarket)
        .as("Frontend quantity mode must come from productType, not crypto category plus leverage")
        .contains("productType")
        .doesNotContain("category === 'crypto' && (!leverage || leverage <= 1)");
  }

  private static String allMigrationText() throws IOException {
    StringBuilder text = new StringBuilder();
    try (var paths = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
      for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
        text.append(Files.readString(path)).append('\n');
      }
    }
    return text.toString();
  }

  private static String migrationText(String fileName) throws IOException {
    return Files.readString(Path.of("src", "main", "resources", "db", "migration", fileName));
  }
}
