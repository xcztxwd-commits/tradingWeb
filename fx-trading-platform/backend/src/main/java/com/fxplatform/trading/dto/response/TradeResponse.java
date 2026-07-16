package com.fxplatform.trading.dto.response;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable authenticated trade history projection. */
public record TradeResponse(
    UUID id,
    UUID orderId,
    UUID accountId,
    String symbol,
    ProductType productType,
    PositionSide positionSide,
    MarginMode marginMode,
    OrderSide side,
    BigDecimal lots,
    BigDecimal price,
    BigDecimal realizedPnl,
    BigDecimal fee,
    String feeAsset,
    LiquidityRole liquidityRole,
    String systemReason,
    String sourceMode,
    String providerCode,
    Instant executedAt
) {

  public static TradeResponse from(TradeEntity entity) {
    return new TradeResponse(
        entity.getId(),
        entity.getOrderId(),
        entity.getAccountId(),
        entity.getSymbol(),
        entity.getProductType(),
        entity.getPositionSide(),
        entity.getMarginMode(),
        entity.getSide(),
        entity.getLots(),
        entity.getPrice(),
        entity.getRealizedPnl(),
        entity.getFee(),
        entity.getFeeAsset(),
        entity.getLiquidityRole(),
        entity.getSystemReason(),
        entity.getSourceMode(),
        entity.getProviderCode(),
        entity.getExecutedAt());
  }
}
