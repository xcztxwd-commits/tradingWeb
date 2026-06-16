package com.fxplatform.risk.entity;

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
@TableName("trading.fx_conversion_rates")
public class ForexConversionRateEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String fromCurrency;
  private String toCurrency;
  private BigDecimal rate;
  private BigDecimal bid;
  private BigDecimal ask;
  private Instant effectiveAt;
  private String source;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
