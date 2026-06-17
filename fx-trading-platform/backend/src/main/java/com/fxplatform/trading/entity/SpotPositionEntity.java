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
@TableName("trading.spot_positions")
public class SpotPositionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String walletType = "SPOT";
  private String asset;
  private BigDecimal quantity = BigDecimal.ZERO;
  private BigDecimal averageCost = BigDecimal.ZERO;
  private String costAsset;
  private BigDecimal realizedPnl = BigDecimal.ZERO;
  private BigDecimal unrealizedPnl = BigDecimal.ZERO;
  private BigDecimal feeCost = BigDecimal.ZERO;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
