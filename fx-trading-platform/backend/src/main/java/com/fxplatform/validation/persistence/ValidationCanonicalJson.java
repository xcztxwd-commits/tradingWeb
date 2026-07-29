package com.fxplatform.validation.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Semantic JSON comparison that is stable across PostgreSQL jsonb normalization. */
final class ValidationCanonicalJson {

  private final ObjectMapper objectMapper;
  private final ObjectReader storedJsonReader;

  ValidationCanonicalJson(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.storedJsonReader = objectMapper.reader()
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  boolean equivalent(Object left, Object right) {
    return equivalentNodes(tree(left), tree(right));
  }

  private JsonNode tree(Object value) {
    try {
      return value instanceof String string
          ? storedJsonReader.readTree(string)
          : objectMapper.valueToTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored validation runtime JSON is invalid", exception);
    }
  }

  private static boolean equivalentNodes(JsonNode left, JsonNode right) {
    if (left == null || right == null) {
      return left == right;
    }
    if (left.isNumber() && right.isNumber()) {
      return left.decimalValue().compareTo(right.decimalValue()) == 0;
    }
    if (left.getNodeType() != right.getNodeType()) {
      return false;
    }
    if (left.isArray()) {
      if (left.size() != right.size()) {
        return false;
      }
      for (int index = 0; index < left.size(); index++) {
        if (!equivalentNodes(left.get(index), right.get(index))) {
          return false;
        }
      }
      return true;
    }
    if (left.isObject()) {
      if (left.size() != right.size()) {
        return false;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = left.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (!right.has(field.getKey())
            || !equivalentNodes(field.getValue(), right.get(field.getKey()))) {
          return false;
        }
      }
      return true;
    }
    return left.equals(right);
  }
}
