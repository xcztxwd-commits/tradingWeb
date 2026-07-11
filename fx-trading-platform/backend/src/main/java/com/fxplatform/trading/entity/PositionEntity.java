package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PositionEntity 是持仓数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("trading.positions")
public class PositionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String symbol;
  private ProductType productType = ProductType.FX_MARGIN;
  private PositionMode positionMode = PositionMode.ONE_WAY;
  private PositionSide positionSide = PositionSide.BOTH;
  private MarginMode marginMode = MarginMode.CROSS;
  private OrderSide side;
  private BigDecimal lots;
  private BigDecimal openPrice;
  private BigDecimal currentPrice;
  private BigDecimal stopLoss;
  private BigDecimal takeProfit;
  private BigDecimal floatingPnl = BigDecimal.ZERO;
  private BigDecimal realizedPnl = BigDecimal.ZERO;
  private BigDecimal fundingPnl = BigDecimal.ZERO;
  private BigDecimal financingAccrued = BigDecimal.ZERO;
  private BigDecimal marginHeld = BigDecimal.ZERO;
  private BigDecimal notional = BigDecimal.ZERO;
  private BigDecimal initialMargin = BigDecimal.ZERO;
  private BigDecimal maintenanceMargin = BigDecimal.ZERO;
  private BigDecimal markPrice;
  private String settlementAsset;
  private String marginAsset;
  private PositionStatus status = PositionStatus.OPEN;
  private Integer leverage;
  private Long version = 0L;

  @TableField(fill = FieldFill.INSERT)
  private Instant openedAt;

  private Instant closedAt;
}
