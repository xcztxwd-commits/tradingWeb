package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.repository.TradeRepository;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V57DemoDepthFillIdentityMigrationTest {

  @Test
  void migrationAddsANullableFillIdentityAndPartialOrderIdentityGuardWithoutWeakeningV52()
      throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/migration/V57__demo_depth_fill_identity.sql"));
    String normalized = sql.replaceAll("\\s+", " ").toUpperCase();

    assertThat(normalized)
        .contains("ALTER TABLE TRADING.TRADES ADD COLUMN IF NOT EXISTS FILL_IDENTITY VARCHAR(64)")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS UX_TRADES_ORDER_FILL_IDENTITY")
        .contains("ON TRADING.TRADES(ORDER_ID, FILL_IDENTITY)")
        .contains("WHERE FILL_IDENTITY IS NOT NULL")
        .doesNotContain("FILL_IDENTITY VARCHAR(64) NOT NULL", "DROP", "DELETE",
            "UX_TRADES_P0_ORDER_FULL_FILL");
  }

  @Test
  void entityAndRepositoryExposeTheNullableOrderScopedFillIdentityContract() throws Exception {
    assertThat(TradeEntity.class.getDeclaredField("fillIdentity").getType()).isEqualTo(String.class);

    Method method = TradeRepository.class.getMethod(
        "findByOrderIdAndFillIdentity", UUID.class, String.class);
    assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
    assertThat(((ParameterizedType) method.getGenericReturnType()).getRawType())
        .isEqualTo(Optional.class);

    String repository = Files.readString(Path.of(
        "src/main/java/com/fxplatform/trading/repository/TradeRepository.java"));
    assertThat(repository)
        .contains(".eq(TradeEntity::getOrderId, orderId)")
        .contains(".eq(TradeEntity::getFillIdentity, fillIdentity)");
  }
}
