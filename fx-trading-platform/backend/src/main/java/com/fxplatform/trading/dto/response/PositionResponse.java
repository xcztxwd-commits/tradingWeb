package com.fxplatform.trading.dto.response;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PositionResponse 承载交易模块的数据结构。
 */
public record PositionResponse(
    UUID id,
    String symbol,
    String side,
    String instrumentType,
    String marginMode,
    Integer leverage,
    String positionUnit,
    BigDecimal lots,
    BigDecimal openPrice,
    BigDecimal markPrice,
    BigDecimal currentPrice,
    BigDecimal notional,
    BigDecimal liquidationPrice,
    BigDecimal breakEvenPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    BigDecimal floatingPnl,
    BigDecimal floatingPnlRatio,
    BigDecimal realizedPnl,
    BigDecimal fundingPnl,
    BigDecimal marginHeld,
    BigDecimal initialMargin,
    BigDecimal maintenanceMargin,
    BigDecimal maintenanceMarginRate,
    Integer adlLevel,
    String status,
    Instant openedAt,
    Instant closedAt,
    ProductType productType,
    PositionMode positionMode,
    PositionSide positionSide,
    Long version
) {
  public PositionResponse(
      UUID id,
      String symbol,
      String side,
      String instrumentType,
      String marginMode,
      Integer leverage,
      String positionUnit,
      BigDecimal lots,
      BigDecimal openPrice,
      BigDecimal markPrice,
      BigDecimal currentPrice,
      BigDecimal notional,
      BigDecimal liquidationPrice,
      BigDecimal breakEvenPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal floatingPnl,
      BigDecimal floatingPnlRatio,
      BigDecimal realizedPnl,
      BigDecimal fundingPnl,
      BigDecimal marginHeld,
      BigDecimal maintenanceMargin,
      BigDecimal maintenanceMarginRate,
      Integer adlLevel,
      String status,
      Instant openedAt,
      Instant closedAt
  ) {
    this(
        id,
        symbol,
        side,
        instrumentType,
        marginMode,
        leverage,
        positionUnit,
        lots,
        openPrice,
        markPrice,
        currentPrice,
        notional,
        liquidationPrice,
        breakEvenPrice,
        stopLoss,
        takeProfit,
        floatingPnl,
        floatingPnlRatio,
        realizedPnl,
        fundingPnl,
        marginHeld,
        null,
        maintenanceMargin,
        maintenanceMarginRate,
        adlLevel,
        status,
        openedAt,
        closedAt,
        null,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        null);
  }
}
