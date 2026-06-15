package com.fxplatform.admin.dto.response;

import com.fxplatform.trading.entity.PositionEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminPositionResponse 是后台持仓列表和详情的响应 DTO。
 *
 * @param id 持仓 ID。
 * @param accountId 所属账户 ID。
 * @param symbol 交易品种。
 * @param side 持仓方向。
 * @param lots 持仓手数。
 * @param openPrice 开仓价格。
 * @param currentPrice 当前价格。
 * @param stopLoss 止损价格。
 * @param takeProfit 止盈价格。
 * @param floatingPnl 浮动盈亏。
 * @param realizedPnl 已实现盈亏。
 * @param marginHeld 持仓占用保证金。
 * @param status 持仓状态。
 * @param openedAt 开仓时间。
 * @param closedAt 平仓时间。
 */
public record AdminPositionResponse(
    UUID id,
    UUID accountId,
    String symbol,
    String side,
    BigDecimal lots,
    BigDecimal openPrice,
    BigDecimal currentPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    BigDecimal floatingPnl,
    BigDecimal realizedPnl,
    BigDecimal marginHeld,
    String status,
    Instant openedAt,
    Instant closedAt
) {

  /**
   * 将持仓实体映射为后台持仓 DTO。
   */
  public static AdminPositionResponse from(PositionEntity entity) {
    return new AdminPositionResponse(
        entity.getId(),
        entity.getAccountId(),
        entity.getSymbol(),
        entity.getSide() == null ? null : entity.getSide().name(),
        entity.getLots(),
        entity.getOpenPrice(),
        entity.getCurrentPrice(),
        entity.getStopLoss(),
        entity.getTakeProfit(),
        entity.getFloatingPnl(),
        entity.getRealizedPnl(),
        entity.getMarginHeld(),
        entity.getStatus() == null ? null : entity.getStatus().name(),
        entity.getOpenedAt(),
        entity.getClosedAt());
  }
}
