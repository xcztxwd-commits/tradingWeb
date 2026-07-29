package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OrderEntity 是交易模块的数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("trading.orders")
public class OrderEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private UUID accountId;
  private String symbol;
  private ProductType productType = ProductType.FX_MARGIN;
  private PositionMode positionMode = PositionMode.ONE_WAY;
  private PositionSide positionSide = PositionSide.BOTH;
  private MarginMode marginMode = MarginMode.CROSS;
  private OrderSide side;
  private OrderType orderType;
  private OrderStatus status;
  private BigDecimal lots;
  private BigDecimal requestedPrice;
  private BigDecimal executionPrice;
  private String clientOrderId;
  private BigDecimal quantity;
  private QuantityUnit quantityUnit = QuantityUnit.BASE;
  private BigDecimal originalQuantity;
  private BigDecimal baseQuantity;
  private BigDecimal price;
  private TimeInForce timeInForce = TimeInForce.GTC;
  private Boolean postOnly = false;
  private BigDecimal activationPrice;
  private BigDecimal trailingDelta;
  private BigDecimal trailingRate;
  private BigDecimal trailingExtreme;
  private Boolean reduceOnly = false;
  private OrderOrigin orderOrigin = OrderOrigin.USER;
  private String systemReason;
  private BigDecimal triggerPrice;
  private TriggerPriceType triggerPriceType;
  private TriggerExecutionType triggerExecutionType;
  private ProtectionType protectionType;
  private UUID parentOrderId;
  private UUID parentPositionId;
  private UUID contingencyGroupId;
  private UUID holdOwnerOrderId;
  private BigDecimal filledQuantity = BigDecimal.ZERO;
  private BigDecimal remainingQuantity;
  private BigDecimal avgFillPrice;
  private BigDecimal fee = BigDecimal.ZERO;
  private String feeAsset;
  private LiquidityRole liquidityRole;
  private BigDecimal slippage = BigDecimal.ZERO;
  private BigDecimal holdAmount;
  private String holdCurrency;
  private String rejectCode;
  private String rejectMessage;
  private BigDecimal stopLoss;
  private BigDecimal takeProfit;
  private String idempotencyKey;
  private String requestFingerprint;
  private Integer leverage;
  private Long version = 0L;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  private Instant filledAt;
  private Instant canceledAt;
}
