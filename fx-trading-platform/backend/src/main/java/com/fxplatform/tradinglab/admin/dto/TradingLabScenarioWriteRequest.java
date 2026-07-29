package com.fxplatform.tradinglab.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fxplatform.tradinglab.admin.TradingLabStrictRequestDeserializers;
import com.fxplatform.tradinglab.admin.TradingLabStrictJsonNodeDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonDeserialize(using = TradingLabStrictRequestDeserializers.ScenarioWrite.class)
public record TradingLabScenarioWriteRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 10_000) String description,
    boolean negativeMode,
    @NotBlank @Size(max = 256) String seed,
    @NotBlank @Size(max = 80) String modelVersion,
    @NotNull @JsonDeserialize(using = TradingLabStrictJsonNodeDeserializer.class)
    JsonNode scenario,
    @NotNull @JsonDeserialize(using = TradingLabStrictJsonNodeDeserializer.class)
    JsonNode configSnapshot,
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String configSnapshotHash,
    Long expectedVersion
) {

  @JsonAnySetter
  public void rejectUnknownProperty(String name, Object value) {
    throw new IllegalArgumentException("Unknown Trading Lab scenario request property");
  }
}
