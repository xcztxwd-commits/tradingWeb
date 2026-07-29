package com.fxplatform.tradinglab.admin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Enables duplicate-key detection before consuming a caller-supplied canonical JSON object. */
public final class TradingLabStrictJsonNodeDeserializer
    extends JsonDeserializer<JsonNode> {

  @Override
  public JsonNode deserialize(
      JsonParser parser,
      DeserializationContext context
  ) throws IOException {
    JsonParser.Feature feature =
        StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature();
    boolean alreadyEnabled = parser.isEnabled(feature);
    if (!alreadyEnabled) {
      parser.enable(feature);
    }
    try {
      return parser.getCodec().readTree(parser);
    } finally {
      if (!alreadyEnabled) {
        parser.disable(feature);
      }
    }
  }
}
