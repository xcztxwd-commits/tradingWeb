package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.tradinglab.client.ValidationBackendClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TradingLabArchitectureTest {

  @Test
  void coordinatorUsesOnlyTheValidationControlPlaneBoundary() throws Exception {
    assertThat(ValidationBackendClient.class).isInterface();

    Path source = Path.of(
        "src/main/java/com/fxplatform/tradinglab/application/DefaultTradingLabRunCoordinator.java");
    assertThat(source).exists();
    String text = Files.readString(source);
    assertThat(text).doesNotContain(
        "com.fxplatform.trading.service",
        "com.fxplatform.wallet.service",
        "com.fxplatform.ledger.service",
        "com.fxplatform.risk.service",
        "com.fxplatform.market.service",
        "com.fxplatform.execution");
  }
}
