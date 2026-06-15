package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.response.AdminFeatureActionResponse;
import com.fxplatform.admin.dto.response.AdminFeatureColumnResponse;
import com.fxplatform.admin.dto.response.AdminFeatureFieldResponse;
import com.fxplatform.admin.dto.response.AdminFeatureOptionResponse;
import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class AdminFeatureCatalogDsl {

  private AdminFeatureCatalogDsl() {
  }

  static AdminFeaturePageResponse page(
      String key,
      String title,
      String group,
      List<AdminFeatureFieldResponse> fields,
      List<AdminFeatureColumnResponse> columns,
      List<AdminFeatureActionResponse> toolbarActions,
      List<AdminFeatureActionResponse> rowActions,
      List<Map<String, Object>> rows
  ) {
    return new AdminFeaturePageResponse(key, title, group, fields, columns, toolbarActions, rowActions, rows);
  }

  static List<AdminFeatureFieldResponse> fields(AdminFeatureFieldResponse... fields) {
    return List.of(fields);
  }

  static List<AdminFeatureColumnResponse> columns(AdminFeatureColumnResponse... columns) {
    return List.of(columns);
  }

  static List<AdminFeatureActionResponse> actions(AdminFeatureActionResponse... actions) {
    return List.of(actions);
  }

  @SafeVarargs
  static List<Map<String, Object>> rows(Map<String, Object>... rows) {
    return List.of(rows);
  }

  static AdminFeatureFieldResponse field(String key, String label, String component) {
    return new AdminFeatureFieldResponse(key, label, component, List.of());
  }

  static AdminFeatureFieldResponse select(String key, String label, String... options) {
    List<AdminFeatureOptionResponse> items = Stream.of(options)
        .map(option -> new AdminFeatureOptionResponse(option, option))
        .toList();
    return new AdminFeatureFieldResponse(key, label, "select", items);
  }

  static AdminFeatureColumnResponse col(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, false);
  }

  static AdminFeatureColumnResponse sortableCol(String key, String label) {
    return new AdminFeatureColumnResponse(key, label, true);
  }

  static AdminFeatureActionResponse action(String key, String label, String type) {
    return new AdminFeatureActionResponse(key, label, type);
  }

  static Map<String, Object> row(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("row entries must be key/value pairs");
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      row.put(String.valueOf(entries[i]), entries[i + 1]);
    }
    return Collections.unmodifiableMap(row);
  }
}
