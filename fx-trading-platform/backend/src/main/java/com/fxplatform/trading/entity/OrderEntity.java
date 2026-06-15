package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
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
  private OrderSide side;
  private OrderType orderType;
  private OrderStatus status;
  private BigDecimal lots;
  private BigDecimal requestedPrice;
  private BigDecimal executionPrice;
  private String clientOrderId;
  private BigDecimal quantity;
  private BigDecimal price;
  private BigDecimal filledQuantity = BigDecimal.ZERO;
  private BigDecimal remainingQuantity;
  private BigDecimal avgFillPrice;
  private BigDecimal fee = BigDecimal.ZERO;
  private BigDecimal slippage = BigDecimal.ZERO;
  private BigDecimal holdAmount;
  private String holdCurrency;
  private String rejectCode;
  private String rejectMessage;
  private BigDecimal stopLoss;
  private BigDecimal takeProfit;
  private String idempotencyKey;
  private Integer leverage;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  private Instant filledAt;
  private Instant canceledAt;
}
