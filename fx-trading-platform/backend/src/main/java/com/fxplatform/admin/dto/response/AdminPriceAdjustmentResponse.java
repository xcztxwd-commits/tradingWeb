package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.PriceAdjustmentEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminPriceAdjustmentResponse 是后台行情调整记录的响应 DTO。
 *
 * @param id 调整记录 ID。
 * @param symbolId 品种 ID。
 * @param symbol 品种代码。
 * @param mode 调整模式。
 * @param adjustmentType 调整类型。
 * @param targetPrice 目标价格。
 * @param startsAt 生效开始时间。
 * @param endsAt 生效结束时间。
 * @param status 调整记录状态。
 * @param adminUserId 创建记录的管理员 ID。
 * @param reason 创建原因。
 * @param createdAt 创建时间。
 */
public record AdminPriceAdjustmentResponse(
    UUID id,
    UUID symbolId,
    String symbol,
    String mode,
    String adjustmentType,
    BigDecimal targetPrice,
    Instant startsAt,
    Instant endsAt,
    String status,
    UUID adminUserId,
    String reason,
    Instant createdAt
) {

  /**
   * 将行情调整实体映射为后台 DTO。
   */
  public static AdminPriceAdjustmentResponse from(PriceAdjustmentEntity entity) {
    return new AdminPriceAdjustmentResponse(
        entity.getId(),
        entity.getSymbolId(),
        entity.getSymbol(),
        entity.getMode(),
        entity.getAdjustmentType(),
        entity.getTargetPrice(),
        entity.getStartsAt(),
        entity.getEndsAt(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getAdminUserId(),
        entity.getReason(),
        entity.getCreatedAt());
  }
}
