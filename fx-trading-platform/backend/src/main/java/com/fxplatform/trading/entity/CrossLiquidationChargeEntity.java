package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading.cross_liquidation_charges")
public class CrossLiquidationChargeEntity {

  @TableId(value = "order_id", type = IdType.INPUT)
  private UUID orderId;
  private UUID accountId;
  private UUID positionId;
  private BigDecimal feeDue;
  private BigDecimal feeCharged = BigDecimal.ZERO;
  private String status = "PENDING";

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  private Instant settledAt;
}
