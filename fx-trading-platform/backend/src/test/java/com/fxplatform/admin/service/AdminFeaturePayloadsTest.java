package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminFeaturePayloadsTest {

  @Test
  void requiresFirstAvailableUuid() {
    UUID rowId = UUID.randomUUID();

    UUID value = AdminFeaturePayloads.requireFirstUuid(
        rowId.toString(),
        Map.of("orderId", rowId.toString()),
        "PAYLOAD_INVALID",
        "id is required",
        "orderId");

    assertThat(value).isEqualTo(rowId);
  }

  @Test
  void rejectsMissingRequiredUuid() {
    assertThatThrownBy(() -> AdminFeaturePayloads.requireUuid(
            Map.of(),
            "accountId",
            "PAYLOAD_INVALID",
            "accountId is required"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("accountId is required");
  }

  @Test
  void rejectsNonUuidRequiredValue() {
    assertThatThrownBy(() -> AdminFeaturePayloads.requireUuid(
            Map.of("accountId", "not-a-uuid"),
            "accountId",
            "PAYLOAD_INVALID",
            "accountId is required"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("accountId must be a UUID");
        });
  }

  @Test
  void rejectsRowIdPayloadUuidConflict() {
    UUID rowId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    assertThatThrownBy(() -> AdminFeaturePayloads.requireFirstUuid(
            rowId.toString(),
            Map.of("orderId", orderId.toString()),
            "PAYLOAD_INVALID",
            "orderId is required",
            "orderId"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("rowId conflicts with orderId");
        });
  }

  @Test
  void rejectsInvalidUuidBeforeLegacyFallback() {
    UUID legacyId = UUID.randomUUID();

    assertThatThrownBy(() -> AdminFeaturePayloads.requireFirstUuid(
            null,
            Map.of("symbolId", "not-a-uuid", "productId", legacyId.toString()),
            "PAYLOAD_INVALID",
            "symbolId is required",
            "symbolId",
            "productId"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("symbolId must be a UUID");
        });
  }

  @Test
  void acceptsLegacyUuidKey() {
    UUID productId = UUID.randomUUID();

    UUID value = AdminFeaturePayloads.requireFirstUuid(
        null,
        Map.of("productId", productId.toString()),
        "PAYLOAD_INVALID",
        "symbolId is required",
        "symbolId",
        "productId");

    assertThat(value).isEqualTo(productId);
  }

  @Test
  void requiresPositiveDecimal() {
    assertThat(AdminFeaturePayloads.requirePositiveDecimal(
        Map.of("amount", "12.50"),
        "amount",
        "PAYLOAD_INVALID",
        "amount must be positive"))
        .isEqualByComparingTo("12.50");

    assertThatThrownBy(() -> AdminFeaturePayloads.requirePositiveDecimal(
            Map.of("amount", "0"),
            "amount",
            "PAYLOAD_INVALID",
            "amount must be positive"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("amount must be positive");
  }
}
