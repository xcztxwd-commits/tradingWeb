package com.fxplatform.engagement.domain.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.common.exception.BusinessException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Validates editor JSON and renders only the platform's small HTML allowlist. */
@Component
public final class ContentDocumentSanitizer {

  private static final int MAX_DOCUMENT_CHARS = 100_000;
  private static final int MAX_ROUTE_PARAMS_CHARS = 2_048;
  private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
  private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,31}");
  private static final Set<String> INTERNAL_ROUTES = Set.of(
      "HOME",
      "DASHBOARD",
      "MARKETS",
      "ORDERS",
      "POSITIONS",
      "WALLET",
      "ACCOUNT_OVERVIEW",
      "ACCOUNT_ASSETS",
      "FUNDING_RECORDS",
      "TRADE_RECORDS",
      "KYC",
      "ACCOUNT_SETTINGS",
      "SECURITY",
      "SETTINGS",
      "TRADE_SPOT",
      "TRADE_PERPETUAL",
      "MESSAGE_CENTER");

  private final ObjectMapper objectMapper;

  public ContentDocumentSanitizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public SanitizedDocument sanitize(String bodyDocument) {
    if (bodyDocument == null || bodyDocument.isBlank()
        || bodyDocument.length() > MAX_DOCUMENT_CHARS) {
      throw invalidDocument("Content document is missing or too large");
    }
    try {
      JsonNode root = objectMapper.readTree(bodyDocument);
      ObjectNode document = object(root, "Document must be a JSON object");
      requireFields(document, "type", "content");
      requireType(document, "doc");
      JsonNode content = requiredArray(document, "content");
      StringBuilder html = new StringBuilder();
      Set<UUID> assetIds = new HashSet<>();
      for (JsonNode child : content) {
        renderBlock(child, html, assetIds);
      }
      return new SanitizedDocument(document.toString(), html.toString(), assetIds);
    } catch (BusinessException exception) {
      throw exception;
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new BusinessException(
          "CONTENT_DOCUMENT_INVALID", "Content document is invalid", exception);
    }
  }

  public String sanitizeRouteParams(String routeKey, String paramsJson, String errorCode) {
    if (routeKey == null || !INTERNAL_ROUTES.contains(routeKey)) {
      throw invalidRoute(errorCode, "Unsupported internal route");
    }
    if (paramsJson == null || paramsJson.length() > MAX_ROUTE_PARAMS_CHARS) {
      throw invalidRoute(errorCode, "Route parameters must be a JSON object");
    }
    try {
      JsonNode parsed = objectMapper.readTree(paramsJson);
      ObjectNode params = object(parsed, "Route parameters must be a JSON object", errorCode);
      validateRouteParams(routeKey, params, errorCode);
      return params.toString();
    } catch (BusinessException exception) {
      throw exception;
    } catch (JsonProcessingException exception) {
      throw new BusinessException(errorCode, "Route parameters are invalid", exception);
    }
  }

  private void renderBlock(JsonNode raw, StringBuilder html, Set<UUID> assetIds) {
    ObjectNode node = object(raw, "Block must be an object");
    String type = text(node, "type");
    switch (type) {
      case "paragraph" -> renderTextBlock(node, "p", html, false);
      case "heading" -> renderHeading(node, html);
      case "bulletList" -> renderList(node, "ul", html, assetIds);
      case "orderedList" -> renderOrderedList(node, html, assetIds);
      case "image" -> renderImage(node, html, assetIds);
      default -> throw invalidDocument("Unsupported block type: " + type);
    }
  }

  private void renderTextBlock(
      ObjectNode node,
      String tag,
      StringBuilder html,
      boolean heading
  ) {
    requireFields(node, "type", "attrs", "content");
    ObjectNode attrs = optionalObject(node, "attrs");
    String alignment = alignment(attrs, heading);
    html.append('<').append(tag);
    if (alignment != null) {
      html.append(" class=\"align-").append(alignment).append("\"");
    }
    html.append('>');
    JsonNode content = optionalArray(node, "content");
    if (content != null) {
      for (JsonNode child : content) {
        renderInline(child, html);
      }
    }
    html.append("</").append(tag).append('>');
  }

  private void renderHeading(ObjectNode node, StringBuilder html) {
    ObjectNode attrs = requiredObject(node, "attrs");
    requireFields(attrs, "level", "textAlign");
    JsonNode levelNode = attrs.get("level");
    if (levelNode == null || !levelNode.canConvertToInt()) {
      throw invalidDocument("Heading level is required");
    }
    int level = levelNode.intValue();
    if (level < 1 || level > 3) {
      throw invalidDocument("Heading level must be between 1 and 3");
    }
    renderTextBlock(node, "h" + level, html, true);
  }

  private void renderList(
      ObjectNode node,
      String tag,
      StringBuilder html,
      Set<UUID> assetIds
  ) {
    requireFields(node, "type", "content");
    JsonNode content = requiredArray(node, "content");
    html.append('<').append(tag).append('>');
    for (JsonNode child : content) {
      ObjectNode item = object(child, "List item must be an object");
      requireFields(item, "type", "content");
      requireType(item, "listItem");
      html.append("<li>");
      for (JsonNode block : requiredArray(item, "content")) {
        renderBlock(block, html, assetIds);
      }
      html.append("</li>");
    }
    html.append("</").append(tag).append('>');
  }

  private void renderOrderedList(
      ObjectNode node,
      StringBuilder html,
      Set<UUID> assetIds
  ) {
    requireFields(node, "type", "attrs", "content");
    ObjectNode attrs = optionalObject(node, "attrs");
    int start = 1;
    if (attrs != null) {
      requireFields(attrs, "start");
      JsonNode startNode = attrs.get("start");
      if (startNode != null) {
        if (!startNode.canConvertToInt() || startNode.intValue() < 1
            || startNode.intValue() > 1_000) {
          throw invalidDocument("Ordered-list start is invalid");
        }
        start = startNode.intValue();
      }
    }
    if (start == 1) {
      renderList(nodeWithoutAttrs(node), "ol", html, assetIds);
      return;
    }
    JsonNode content = requiredArray(node, "content");
    html.append("<ol start=\"").append(start).append("\">");
    for (JsonNode child : content) {
      ObjectNode item = object(child, "List item must be an object");
      requireFields(item, "type", "content");
      requireType(item, "listItem");
      html.append("<li>");
      for (JsonNode block : requiredArray(item, "content")) {
        renderBlock(block, html, assetIds);
      }
      html.append("</li>");
    }
    html.append("</ol>");
  }

  private ObjectNode nodeWithoutAttrs(ObjectNode node) {
    ObjectNode copy = node.deepCopy();
    copy.remove("attrs");
    return copy;
  }

  private void renderImage(ObjectNode node, StringBuilder html, Set<UUID> assetIds) {
    requireFields(node, "type", "attrs");
    ObjectNode attrs = requiredObject(node, "attrs");
    requireFields(attrs, "assetId", "alt");
    UUID assetId;
    try {
      assetId = UUID.fromString(text(attrs, "assetId"));
    } catch (IllegalArgumentException exception) {
      throw invalidDocument("Image must reference a platform assetId");
    }
    assetIds.add(assetId);
    String alt = optionalText(attrs, "alt");
    if (alt != null && alt.length() > 200) {
      throw invalidDocument("Image alternative text is too long");
    }
    html.append("<img data-asset-id=\"").append(assetId).append("\" alt=\"")
        .append(escape(alt == null ? "" : alt)).append("\">");
  }

  private void renderInline(JsonNode raw, StringBuilder html) {
    ObjectNode node = object(raw, "Inline node must be an object");
    String type = text(node, "type");
    if ("hardBreak".equals(type)) {
      requireFields(node, "type");
      html.append("<br>");
      return;
    }
    if (!"text".equals(type)) {
      throw invalidDocument("Unsupported inline type: " + type);
    }
    requireFields(node, "type", "text", "marks");
    String rendered = escape(text(node, "text"));
    JsonNode marks = optionalArray(node, "marks");
    if (marks != null) {
      Set<String> seen = new HashSet<>();
      for (JsonNode mark : marks) {
        String markType = text(object(mark, "Mark must be an object"), "type");
        if (!seen.add(markType)) {
          throw invalidDocument("Duplicate mark: " + markType);
        }
      }
      for (int index = marks.size() - 1; index >= 0; index--) {
        rendered = wrapMark(object(marks.get(index), "Mark must be an object"), rendered);
      }
    }
    html.append(rendered);
  }

  private String wrapMark(ObjectNode mark, String value) {
    String type = text(mark, "type");
    return switch (type) {
      case "bold" -> {
        requireFields(mark, "type");
        yield "<strong>" + value + "</strong>";
      }
      case "italic" -> {
        requireFields(mark, "type");
        yield "<em>" + value + "</em>";
      }
      case "textStyle" -> {
        requireFields(mark, "type", "attrs");
        ObjectNode attrs = requiredObject(mark, "attrs");
        requireFields(attrs, "color");
        String color = text(attrs, "color").toLowerCase(Locale.ROOT);
        if (!COLOR.matcher(color).matches()) {
          throw invalidDocument("Text color is invalid");
        }
        yield "<span data-color=\"" + color + "\">" + value + "</span>";
      }
      case "link" -> {
        requireFields(mark, "type", "attrs");
        ObjectNode attrs = requiredObject(mark, "attrs");
        requireFields(attrs, "routeKey", "params");
        String routeKey = text(attrs, "routeKey");
        JsonNode paramsNode = attrs.get("params");
        String params = sanitizeRouteParams(
            routeKey,
            paramsNode == null ? "{}" : paramsNode.toString(),
            "CONTENT_DOCUMENT_INVALID");
        yield "<a data-route-key=\"" + routeKey + "\" data-route-params=\""
            + escape(params) + "\">" + value + "</a>";
      }
      default -> throw invalidDocument("Unsupported mark: " + type);
    };
  }

  private String alignment(ObjectNode attrs, boolean heading) {
    if (attrs == null) {
      if (heading) {
        throw invalidDocument("Heading attributes are required");
      }
      return null;
    }
    requireFields(attrs, heading ? new String[]{"level", "textAlign"} : new String[]{"textAlign"});
    String alignment = optionalText(attrs, "textAlign");
    if (alignment == null || "left".equals(alignment)) {
      return alignment;
    }
    if (!Set.of("center", "right").contains(alignment)) {
      throw invalidDocument("Text alignment is invalid");
    }
    return alignment;
  }

  private void validateRouteParams(String routeKey, ObjectNode params, String errorCode) {
    switch (routeKey) {
      case "TRADE_SPOT", "TRADE_PERPETUAL" -> {
        requireRouteFields(params, errorCode, "symbol");
        String symbol = optionalText(params, "symbol", errorCode);
        if (symbol != null && !SYMBOL.matcher(symbol).matches()) {
          throw invalidRoute(errorCode, "Trading symbol is invalid");
        }
      }
      case "MESSAGE_CENTER" -> {
        requireRouteFields(params, errorCode, "filter");
        String filter = optionalText(params, "filter", errorCode);
        if (filter != null && !Set.of("ALL", "UNREAD").contains(filter)) {
          throw invalidRoute(errorCode, "Message filter is invalid");
        }
      }
      default -> requireRouteFields(params, errorCode);
    }
  }

  private static ObjectNode object(JsonNode value, String message) {
    return object(value, message, "CONTENT_DOCUMENT_INVALID");
  }

  private static ObjectNode object(JsonNode value, String message, String errorCode) {
    if (!(value instanceof ObjectNode object)) {
      throw new BusinessException(errorCode, message);
    }
    return object;
  }

  private static ObjectNode requiredObject(ObjectNode node, String field) {
    return object(node.get(field), field + " must be an object");
  }

  private static ObjectNode optionalObject(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : object(value, field + " must be an object");
  }

  private static JsonNode requiredArray(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw invalidDocument(field + " must be an array");
    }
    return value;
  }

  private static JsonNode optionalArray(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isArray()) {
      throw invalidDocument(field + " must be an array");
    }
    return value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw invalidDocument(field + " must be text");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field) {
    return optionalText(node, field, "CONTENT_DOCUMENT_INVALID");
  }

  private static String optionalText(ObjectNode node, String field, String errorCode) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new BusinessException(errorCode, field + " must be text");
    }
    return value.textValue();
  }

  private static void requireType(ObjectNode node, String expected) {
    if (!expected.equals(text(node, "type"))) {
      throw invalidDocument("Expected " + expected);
    }
  }

  private static void requireFields(ObjectNode node, String... allowed) {
    requireRouteFields(node, "CONTENT_DOCUMENT_INVALID", allowed);
  }

  private static void requireRouteFields(
      ObjectNode node,
      String errorCode,
      String... allowed
  ) {
    Set<String> names = Set.of(allowed);
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!names.contains(field)) {
        throw new BusinessException(errorCode, "Unsupported field: " + field);
      }
    }
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value);
  }

  private static BusinessException invalidDocument(String message) {
    return new BusinessException("CONTENT_DOCUMENT_INVALID", message);
  }

  private static BusinessException invalidRoute(String errorCode, String message) {
    return new BusinessException(errorCode, message);
  }

  public record SanitizedDocument(String documentJson, String html, Set<UUID> assetIds) {

    public SanitizedDocument {
      assetIds = Set.copyOf(assetIds);
    }
  }
}
