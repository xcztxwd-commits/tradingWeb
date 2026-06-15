package com.fxplatform.admin.dto.response;

import com.fxplatform.trading.entity.TradeEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminTradeResponse 是后台成交记录列表的响应 DTO。
 *
 * @param id 成交 ID
 * @param orderId 来源订单 ID
 * @param accountId 成交账户 ID
 * @param symbol 交易品种
 * @param side 买卖方向
 * @param lots 成交手数
 * @param price 成交价格
 * @param realizedPnl 已实现盈亏
 * @param executedAt 成交时间
 */
public record AdminTradeResponse(
    UUID id,
    UUID orderId,
    UUID accountId,
    String symbol,
    String side,
    BigDecimal lots,
    BigDecimal price,
    BigDecimal realizedPnl,
    Instant executedAt
) {

  /** 将成交实体映射为后台成交 DTO。 */
  public static AdminTradeResponse from(TradeEntity entity) {
    return new AdminTradeResponse(
        entity.getId(),
        entity.getOrderId(),
        entity.getAccountId(),
        entity.getSymbol(),
        entity.getSide() == null ? null : entity.getSide().name(),
        entity.getLots(),
        entity.getPrice(),
        entity.getRealizedPnl(),
        entity.getExecutedAt());
  }
}
