package com.fxplatform.admin.dto.response;

import com.fxplatform.finance.entity.AdminPaymentMethodEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminPaymentMethodResponse 是后台支付方式响应 DTO。
 *
 * @param id 支付方式 ID。
 * @param name 展示名称。
 * @param methodType 支付方式类型。
 * @param currency 币种。
 * @param enabled 是否启用。
 * @param displayOrder 展示排序。
 * @param instructions 支付说明。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminPaymentMethodResponse(
    UUID id,
    String name,
    String methodType,
    String currency,
    Boolean enabled,
    Integer displayOrder,
    String instructions,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将支付方式实体映射为后台 DTO。
   */
  public static AdminPaymentMethodResponse from(AdminPaymentMethodEntity entity) {
    return new AdminPaymentMethodResponse(
        entity.getId(),
        entity.getName(),
        entity.getMethodType(),
        entity.getCurrency(),
        entity.getEnabled(),
        entity.getDisplayOrder(),
        entity.getInstructions(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
