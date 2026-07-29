package com.fxplatform.tradinglab.admin.report;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public record TradingLabPermanentRequest(@NotNull Boolean permanent) {

  @JsonAnySetter
  public void rejectUnknownProperty(String name, Object value) {
    throw new IllegalArgumentException(
        "Unknown Trading Lab permanent request property");
  }
}
