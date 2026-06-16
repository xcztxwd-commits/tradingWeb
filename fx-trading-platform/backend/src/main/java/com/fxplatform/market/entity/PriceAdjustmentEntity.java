package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.market.enums.PriceAdjustmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PriceAdjustmentEntity 是价格调整数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("market.price_adjustments")
public class PriceAdjustmentEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID symbolId;
  private String symbol;
  private String mode;
  private String adjustmentType;
  private BigDecimal targetPrice;
  private Instant startsAt;
  private Instant endsAt;
  private PriceAdjustmentStatus status = PriceAdjustmentStatus.SCHEDULED;
  private UUID adminUserId;
  private String reason;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  public void setStatus(PriceAdjustmentStatus status) {
    this.status = status;
  }

  public void setStatus(String status) {
    this.status = PriceAdjustmentStatus.fromCode(status);
  }
}
