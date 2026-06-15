package com.fxplatform.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * RiskConfigEntity 映射 risk.risk_configs 风控配置表。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("risk.risk_configs")
public class RiskConfigEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String symbol;
  private Integer maxLeverage = 100;
  private BigDecimal maxLots = new BigDecimal("100");
  private BigDecimal marginCallLevel = new BigDecimal("100");
  private BigDecimal stopOutLevel = new BigDecimal("50");
  private Boolean enabled = true;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
