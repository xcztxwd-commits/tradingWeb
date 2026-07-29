package com.fxplatform.validation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fxplatform.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed collector for the bounded, zero-based pages returned by trading history APIs.
 */
final class ValidationStatePageCollector {

  static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;
  private static final long MAX_ITEMS = (long) PAGE_SIZE * MAX_PAGES;
  private static final long MAX_MATERIALIZED_BYTES = 8L * 1024L * 1024L;

  private final ObjectMapper objectMapper;
  private final ArrayNode items;
  private final Set<String> itemIds = new LinkedHashSet<>();
  private int nextPage;
  private int sourcePageSize = -1;
  private int totalPages = -1;
  private long total = -1L;
  private long materializedBytes;
  private boolean complete;

  ValidationStatePageCollector(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.items = objectMapper.createArrayNode();
  }

  void append(int requestedPage, JsonNode page) {
    if (complete || requestedPage != nextPage || page == null || !page.isObject()) {
      throw invalid();
    }
    int actualPage = requiredInt(page, "page");
    int actualSize = requiredInt(page, "size");
    long actualTotal = requiredLong(page, "total");
    int actualTotalPages = requiredInt(page, "totalPages");
    JsonNode pageItems = page.get("items");
    if (actualPage != requestedPage
        || actualSize < 1
        || actualSize > PAGE_SIZE
        || actualTotal < 0L
        || actualTotalPages < 0
        || pageItems == null
        || !pageItems.isArray()) {
      throw invalid();
    }
    long calculatedPages = actualTotal == 0L
        ? 0L
        : 1L + (actualTotal - 1L) / actualSize;
    if (actualTotal > MAX_ITEMS
        || actualTotalPages > MAX_PAGES
        || calculatedPages > MAX_PAGES) {
      throw tooLarge();
    }
    if (calculatedPages != actualTotalPages) {
      throw invalid();
    }
    if (nextPage == 0) {
      sourcePageSize = actualSize;
      total = actualTotal;
      totalPages = actualTotalPages;
    } else if (actualSize != sourcePageSize
        || actualTotal != total
        || actualTotalPages != totalPages) {
      throw invalid();
    }

    int expectedItems = expectedItemsFor(requestedPage);
    if (pageItems.size() != expectedItems) {
      throw invalid();
    }
    materializedBytes += pageItems.toString().getBytes(StandardCharsets.UTF_8).length;
    if (materializedBytes > MAX_MATERIALIZED_BYTES) {
      throw tooLarge();
    }
    for (JsonNode item : pageItems) {
      if (item == null || !item.isObject()) {
        throw invalid();
      }
      JsonNode id = item.get("id");
      if (id == null || !id.isTextual() || !canonicalUuid(id.textValue())
          || !itemIds.add(id.textValue())) {
        throw invalid();
      }
      items.add(item.deepCopy());
    }
    nextPage++;
    complete = totalPages == 0 || nextPage == totalPages;
    if (complete && items.size() != total) {
      throw invalid();
    }
  }

  boolean complete() {
    return complete;
  }

  Map<String, Object> snapshot() {
    if (!complete) {
      throw new IllegalStateException("Validation state pagination is incomplete");
    }
    List<Object> materialized = objectMapper.convertValue(items, new TypeReference<>() { });
    LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("items", List.copyOf(materialized));
    snapshot.put("page", 0);
    snapshot.put("size", sourcePageSize);
    snapshot.put("total", total);
    snapshot.put("totalPages", totalPages);
    snapshot.put("complete", true);
    return Collections.unmodifiableMap(snapshot);
  }

  private int expectedItemsFor(int page) {
    if (totalPages == 0) {
      return page == 0 ? 0 : invalidInt();
    }
    if (page >= totalPages) {
      return invalidInt();
    }
    if (page < totalPages - 1) {
      return sourcePageSize;
    }
    long remaining = total - (long) page * sourcePageSize;
    return remaining >= 1L && remaining <= sourcePageSize ? (int) remaining : invalidInt();
  }

  private static int requiredInt(JsonNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw invalid();
    }
    return node.intValue();
  }

  private static long requiredLong(JsonNode value, String field) {
    JsonNode node = value.get(field);
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid();
    }
    return node.longValue();
  }

  private static int invalidInt() {
    throw invalid();
  }

  private static boolean canonicalUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return java.util.UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "VALIDATION_STATE_PAGE_INVALID",
        "Validation state page response was inconsistent");
  }

  private static BusinessException tooLarge() {
    return new BusinessException(
        "VALIDATION_STATE_QUERY_TOO_LARGE",
        "Validation state query exceeded its fixed bound");
  }
}
