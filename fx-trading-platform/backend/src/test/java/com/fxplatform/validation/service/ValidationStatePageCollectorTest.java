package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationStatePageCollectorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void materializesEveryPageWithoutLeavingFirstPageMetadataAmbiguous() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);

    collector.append(0, page(0, 2, 3, 2, List.of(
        Map.of("id", id(1)),
        Map.of("id", id(2)))));
    assertThat(collector.complete()).isFalse();

    collector.append(1, page(1, 2, 3, 2, List.of(Map.of("id", id(3)))));

    assertThat(collector.complete()).isTrue();
    assertThat(collector.snapshot())
        .containsEntry("page", 0)
        .containsEntry("size", 2)
        .containsEntry("total", 3L)
        .containsEntry("totalPages", 2)
        .containsEntry("complete", true);
    assertThat((List<?>) collector.snapshot().get("items"))
        .extracting(item -> String.valueOf(((Map<?, ?>) item).get("id")))
        .containsExactly(id(1), id(2), id(3));
  }

  @Test
  void rejectsAResponseThatClaimsMoreItemsThanWereReturned() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);

    assertThatThrownBy(() -> collector.append(
        0,
        page(0, 100, 101, 2, List.of(Map.of("id", id(1))))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_PAGE_INVALID"));
  }

  @Test
  void rejectsPageDriftInsteadOfCombiningInconsistentSnapshots() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);
    collector.append(0, page(0, 1, 2, 2, List.of(Map.of("id", id(1)))));

    assertThatThrownBy(() -> collector.append(
        1,
        page(0, 1, 2, 2, List.of(Map.of("id", id(2))))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_PAGE_INVALID"));
  }

  @Test
  void failsClosedWhenTheCompleteSnapshotExceedsTheFixedBound() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);

    assertThatThrownBy(() -> collector.append(
        0,
        page(0, 100, 10_001, 101, List.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_QUERY_TOO_LARGE"));
  }

  @Test
  void failsClosedWhenMaterializedPagesExceedTheByteBound() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);
    String largeValue = "x".repeat(1_900_000);
    for (int page = 0; page < 4; page++) {
      collector.append(
          page,
          page(page, 1, 5, 5, List.of(Map.of("id", id(page + 1), "value", largeValue))));
    }

    assertThatThrownBy(() -> collector.append(
        4,
        page(4, 1, 5, 5, List.of(Map.of("id", id(5), "value", largeValue)))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_QUERY_TOO_LARGE"));
  }

  @Test
  void rejectsItemsWithoutAStableObjectIdentity() throws Exception {
    ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);

    assertThatThrownBy(() -> collector.append(
        0,
        objectMapper.readTree("""
            {"items":["scalar"],"page":0,"size":1,"total":1,"totalPages":1}
            """)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_PAGE_INVALID"));

    assertThatThrownBy(() -> new ValidationStatePageCollector(objectMapper).append(
        0,
        objectMapper.readTree("""
            {"items":[{"symbol":"BTCUSDT"}],"page":0,"size":1,
             "total":1,"totalPages":1}
            """)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("VALIDATION_STATE_PAGE_INVALID"));
  }

  private JsonNode page(
      int page,
      int size,
      long total,
      int totalPages,
      List<Map<String, String>> items
  ) throws Exception {
    return objectMapper.readTree(objectMapper.writeValueAsBytes(Map.of(
        "items", items,
        "page", page,
        "size", size,
        "total", total,
        "totalPages", totalPages)));
  }

  private static String id(int value) {
    return "00000000-0000-0000-0000-" + String.format("%012d", value);
  }
}
