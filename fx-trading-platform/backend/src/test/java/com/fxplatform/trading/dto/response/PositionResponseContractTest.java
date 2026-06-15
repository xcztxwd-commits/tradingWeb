package com.fxplatform.trading.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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

  private static String[] recordComponentNames() {
    return Arrays.stream(PositionResponse.class.getRecordComponents())
        .map(component -> component.getName())
        .toArray(String[]::new);
  }
}
