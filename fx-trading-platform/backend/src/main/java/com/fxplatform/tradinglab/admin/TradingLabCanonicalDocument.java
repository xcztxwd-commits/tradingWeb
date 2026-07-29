package com.fxplatform.tradinglab.admin;

import com.fasterxml.jackson.databind.JsonNode;

public record TradingLabCanonicalDocument(
    String json,
    String sha256,
    JsonNode value
) {
}
