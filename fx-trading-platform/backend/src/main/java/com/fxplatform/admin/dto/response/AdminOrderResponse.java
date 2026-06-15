package com.fxplatform.admin.dto.response;

import com.fxplatform.trading.entity.OrderEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminOrderResponse 是后台订单列表和详情的响应 DTO。
 *
 * @param id 订单 ID。
 * @param userId 下单用户 ID。
 * @param accountId 下单账户 ID。
 * @param symbol 交易品种。
 * @param side 买卖方向。
 * @param orderType 订单类型。
 * @param status 订单状态。
 * @param lots 手数。
 * @param quantity 数量。
 * @param price 委托价格。
 * @param executionPrice 执行价格。
 * @param filledQuantity 已成交数量。
 * @param remainingQuantity 剩余数量。
 * @param avgFillPrice 平均成交价。
 * @param holdAmount 冻结金额。
 * @param holdCurrency 冻结币种。
 * @param rejectCode 拒绝代码。
 * @param rejectMessage 拒绝原因。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 * @param filledAt 成交时间。
 * @param canceledAt 撤单时间。
 */
public record AdminOrderResponse(
    UUID id,
    UUID userId,
    UUID accountId,
    String symbol,
    String side,
    String orderType,
    String status,
    BigDecimal lots,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal executionPrice,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal avgFillPrice,
    BigDecimal holdAmount,
    String holdCurrency,
    String rejectCode,
    String rejectMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant filledAt,
    Instant canceledAt
) {

  /**
   * 将订单实体映射为后台订单 DTO。
   */
  public static AdminOrderResponse from(OrderEntity entity) {
    return new AdminOrderResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAccountId(),
        entity.getSymbol(),
        entity.getSide() == null ? null : entity.getSide().name(),
        entity.getOrderType() == null ? null : entity.getOrderType().name(),
        entity.getStatus() == null ? null : entity.getStatus().name(),
        entity.getLots(),
        entity.getQuantity(),
        entity.getPrice(),
        entity.getExecutionPrice(),
        entity.getFilledQuantity(),
        entity.getRemainingQuantity(),
        entity.getAvgFillPrice(),
        entity.getHoldAmount(),
        entity.getHoldCurrency(),
        entity.getRejectCode(),
        entity.getRejectMessage(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getFilledAt(),
        entity.getCanceledAt());
  }
}
