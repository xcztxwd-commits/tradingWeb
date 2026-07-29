package com.fxplatform.tradinglab.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record TradingLabConfigResponse(
    JsonNode configSnapshot,
    String configSnapshotHash,
    String modelVersion,
    String symbolConfigVersion,
    String codeVersion
) {
}
