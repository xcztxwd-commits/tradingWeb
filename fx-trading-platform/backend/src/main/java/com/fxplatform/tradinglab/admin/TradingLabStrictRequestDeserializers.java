package com.fxplatform.tradinglab.admin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Request-local strict readers. They consume and validate the complete JSON tree before binding
 * any DTO field, without changing the application's shared {@code ObjectMapper}.
 */
public final class TradingLabStrictRequestDeserializers {

  static final int MAX_NESTING_DEPTH = 64;
  static final int MAX_TREE_NODES = 100_000;
  static final int MAX_TEXT_BYTES =
      TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES;

  private static final Set<String> RUN_FIELDS = Set.of(
      "scenarioVersion",
      "configSnapshotHash",
      "localCalculation");
  private static final Set<String> SCENARIO_FIELDS = Set.of(
      "name",
      "description",
      "negativeMode",
      "seed",
      "modelVersion",
      "scenario",
      "configSnapshot",
      "configSnapshotHash",
      "expectedVersion");

  private TradingLabStrictRequestDeserializers() {
  }

  public static final class RunCreate
      extends JsonDeserializer<TradingLabRunCreateRequest> {

    @Override
    public TradingLabRunCreateRequest deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws IOException {
      ObjectNode root = readStrictObject(parser);
      rejectUnknown(
          parser,
          root,
          RUN_FIELDS,
          "Unknown Trading Lab run request property");
      return new TradingLabRunCreateRequest(
          readValue(root, "scenarioVersion", Long.class, context),
          readValue(root, "configSnapshotHash", String.class, context),
          readNode(root, "localCalculation"));
    }
  }

  public static final class ScenarioWrite
      extends JsonDeserializer<TradingLabScenarioWriteRequest> {

    @Override
    public TradingLabScenarioWriteRequest deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws IOException {
      ObjectNode root = readStrictObject(parser);
      rejectUnknown(
          parser,
          root,
          SCENARIO_FIELDS,
          "Unknown Trading Lab scenario request property");
      return new TradingLabScenarioWriteRequest(
          readValue(root, "name", String.class, context),
          readValue(root, "description", String.class, context),
          Boolean.TRUE.equals(readValue(root, "negativeMode", Boolean.class, context)),
          readExactText(root, "seed", parser),
          readValue(root, "modelVersion", String.class, context),
          readNode(root, "scenario"),
          readNode(root, "configSnapshot"),
          readValue(root, "configSnapshotHash", String.class, context),
          readValue(root, "expectedVersion", Long.class, context));
    }
  }

  private static ObjectNode readStrictObject(JsonParser parser) throws IOException {
    JsonToken token = parser.currentToken();
    if (token == null) {
      token = parser.nextToken();
    }
    if (token != JsonToken.START_OBJECT) {
      throw JsonMappingException.from(
          parser,
          "Trading Lab request must be a JSON object");
    }

    JsonNode value = readStrictValue(parser, new ReadBudget(), 0);
    if (!(value instanceof ObjectNode object)) {
      throw JsonMappingException.from(
          parser,
          "Trading Lab request must be a JSON object");
    }
    if (parser.nextToken() != null) {
      throw JsonMappingException.from(
          parser,
          "Trailing Trading Lab JSON content is not allowed");
    }
    return object;
  }

  private static JsonNode readStrictValue(
      JsonParser parser,
      ReadBudget budget,
      int depth
  ) throws IOException {
    if (depth > MAX_NESTING_DEPTH) {
      throw budgetExceeded(parser);
    }
    budget.addNode(parser);
    JsonToken token = parser.currentToken();
    if (token == JsonToken.START_OBJECT) {
      ObjectNode object = JsonNodeFactory.instance.objectNode();
      Set<String> fields = new HashSet<>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw JsonMappingException.from(parser, "Malformed Trading Lab JSON object");
        }
        String field = parser.currentName();
        budget.addText(parser, field);
        if (!fields.add(field)) {
          throw JsonMappingException.from(parser, "Duplicate field '" + field + "'");
        }
        if (parser.nextToken() == null) {
          throw JsonMappingException.from(parser, "Malformed Trading Lab JSON value");
        }
        object.set(field, readStrictValue(parser, budget, depth + 1));
      }
      return object;
    }
    if (token == JsonToken.START_ARRAY) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        if (parser.currentToken() == null) {
          throw JsonMappingException.from(parser, "Malformed Trading Lab JSON array");
        }
        array.add(readStrictValue(parser, budget, depth + 1));
      }
      return array;
    }
    return readScalar(parser, budget, token);
  }

  private static JsonNode readScalar(
      JsonParser parser,
      ReadBudget budget,
      JsonToken token
  ) throws IOException {
    if (token == null || !token.isScalarValue()) {
      throw JsonMappingException.from(parser, "Malformed Trading Lab JSON value");
    }
    return switch (token) {
      case VALUE_STRING -> {
        String value = parser.getText();
        budget.addText(parser, value);
        yield JsonNodeFactory.instance.textNode(value);
      }
      case VALUE_NUMBER_INT -> {
        budget.addText(parser, parser.getText());
        yield switch (parser.getNumberType()) {
          case INT -> JsonNodeFactory.instance.numberNode(parser.getIntValue());
          case LONG -> JsonNodeFactory.instance.numberNode(parser.getLongValue());
          case BIG_INTEGER ->
              JsonNodeFactory.instance.numberNode(parser.getBigIntegerValue());
          default -> JsonNodeFactory.instance.numberNode(parser.getBigIntegerValue());
        };
      }
      case VALUE_NUMBER_FLOAT -> {
        budget.addText(parser, parser.getText());
        yield JsonNodeFactory.instance.numberNode(parser.getDecimalValue());
      }
      case VALUE_TRUE -> JsonNodeFactory.instance.booleanNode(true);
      case VALUE_FALSE -> JsonNodeFactory.instance.booleanNode(false);
      case VALUE_NULL -> JsonNodeFactory.instance.nullNode();
      default -> throw JsonMappingException.from(
          parser,
          "Malformed Trading Lab JSON value");
    };
  }

  private static void rejectUnknown(
      JsonParser parser,
      ObjectNode root,
      Set<String> allowed,
      String message
  ) throws JsonMappingException {
    Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      if (!allowed.contains(fields.next())) {
        throw JsonMappingException.from(parser, message);
      }
    }
  }

  private static <T> T readValue(
      ObjectNode root,
      String field,
      Class<T> type,
      DeserializationContext context
  ) throws IOException {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return context.readTreeAsValue(value, type);
  }

  private static String readExactText(
      ObjectNode root,
      String field,
      JsonParser parser
  ) throws JsonMappingException {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw JsonMappingException.from(
          parser,
          "Trading Lab field '" + field + "' must be a JSON string");
    }
    return value.textValue();
  }

  private static JsonNode readNode(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    return value == null || value.isNull() ? null : value;
  }

  private static JsonMappingException budgetExceeded(JsonParser parser) {
    return JsonMappingException.from(
        parser,
        "Trading Lab request exceeds its JSON budget");
  }

  private static long utf8Bytes(String value) {
    long bytes = 0L;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current <= 0x7f) {
        bytes++;
      } else if (current <= 0x7ff) {
        bytes += 2L;
      } else if (Character.isHighSurrogate(current)
          && index + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(index + 1))) {
        bytes += 4L;
        index++;
      } else {
        bytes += 3L;
      }
    }
    return bytes;
  }

  private static final class ReadBudget {

    private int nodes;
    private long textBytes;

    private void addNode(JsonParser parser) throws JsonMappingException {
      if (++nodes > MAX_TREE_NODES) {
        throw budgetExceeded(parser);
      }
    }

    private void addText(JsonParser parser, String value)
        throws JsonMappingException {
      long additional = value == null ? 0L : utf8Bytes(value);
      if (additional > MAX_TEXT_BYTES - textBytes) {
        throw budgetExceeded(parser);
      }
      textBytes += additional;
    }
  }
}
