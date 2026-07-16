package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * TradeEntity 是成交数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("trading.trades")
public class TradeEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID orderId;
  private UUID accountId;
  private String symbol;
  private ProductType productType = ProductType.FX_MARGIN;
  private Boolean canonicalFullFill = false;
  private PositionSide positionSide = PositionSide.BOTH;
  private MarginMode marginMode = MarginMode.CROSS;
  private OrderSide side;
  private BigDecimal lots;
  private BigDecimal price;
  private BigDecimal realizedPnl = BigDecimal.ZERO;
  private BigDecimal fee = BigDecimal.ZERO;
  private String feeAsset;
  private LiquidityRole liquidityRole;
  private String systemReason;
  private String sourceMode;
  private String providerCode;

  @TableField(fill = FieldFill.INSERT)
  private Instant executedAt;
}
