package com.fxplatform.tradinglab.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fxplatform.tradinglab.admin.TradingLabStrictRequestDeserializers;
import com.fxplatform.tradinglab.admin.TradingLabStrictJsonNodeDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@JsonDeserialize(using = TradingLabStrictRequestDeserializers.RunCreate.class)
public record TradingLabRunCreateRequest(
    @NotNull @Min(0) Long scenarioVersion,
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String configSnapshotHash,
    @NotNull @JsonDeserialize(using = TradingLabStrictJsonNodeDeserializer.class)
    JsonNode localCalculation
) {

  @JsonAnySetter
  public void rejectUnknownProperty(String name, Object value) {
    throw new IllegalArgumentException("Unknown Trading Lab run request property");
  }
}
