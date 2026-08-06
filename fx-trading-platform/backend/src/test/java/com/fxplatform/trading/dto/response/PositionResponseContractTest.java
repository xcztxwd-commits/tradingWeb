package com.fxplatform.trading.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PositionResponseContractTest {

  @Test
  void exposesOkxStylePositionDisplayFields() {
    assertThat(recordComponentNames())
        .contains(
            "instrumentType",
            "marginMode",
            "leverage",
            "positionUnit",
            "markPrice",
            "liquidationPrice",
            "breakEvenPrice",
            "floatingPnlRatio",
            "maintenanceMarginRate",
            "adlLevel");
  }

  @Test
  void exposesSpotAndPerpetualPositionSnapshotFields() {
    Map<String, String> components = Arrays.stream(PositionResponse.class.getRecordComponents())
        .collect(Collectors.toMap(
            component -> component.getName(),
            component -> component.getType().getName()));

    assertThat(components)
        .containsEntry("productType", "com.fxplatform.market.model.ProductType")
        .containsEntry("positionMode", "com.fxplatform.trading.enums.PositionMode")
        .containsEntry("positionSide", "com.fxplatform.trading.enums.PositionSide")
        .containsEntry("version", Long.class.getName())
        .containsEntry("marginMode", String.class.getName())
        .containsEntry("initialMargin", "java.math.BigDecimal");
  }

  private static String[] recordComponentNames() {
    return Arrays.stream(PositionResponse.class.getRecordComponents())
        .map(component -> component.getName())
        .toArray(String[]::new);
  }
}
