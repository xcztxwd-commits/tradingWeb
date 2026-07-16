package com.fxplatform.account.dto;

import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
import java.util.List;
import java.util.UUID;

public record TradingSettingsResponse(
    UUID accountId,
    PositionMode positionMode,
    List<SymbolSettings> symbols
) {

  public record SymbolSettings(
      String symbol,
      Integer leverage,
      MarginMode marginMode,
      QuantityUnit quantityUnit,
      Long version,
      Integer maxLeverage
  ) {
  }
}
