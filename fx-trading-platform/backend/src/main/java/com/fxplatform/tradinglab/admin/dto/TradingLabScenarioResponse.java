package com.fxplatform.tradinglab.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record TradingLabScenarioResponse(
    UUID id,
    String name,
    String description,
    String status,
    boolean negativeMode,
    String seed,
    String modelVersion,
    JsonNode scenario,
    JsonNode configSnapshot,
    String configSnapshotHash,
    String symbolConfigVersion,
    String codeVersion,
    UUID createdBy,
    UUID updatedBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
}
