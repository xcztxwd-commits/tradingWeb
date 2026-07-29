package com.fxplatform.validation.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

public final class ValidationExactTextDeserializer extends JsonDeserializer<String> {

  @Override
  public String deserialize(
      JsonParser parser,
      DeserializationContext context
  ) throws IOException {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw JsonMappingException.from(
          parser,
          "Validation seed must be a JSON string");
    }
    return parser.getText();
  }
}
