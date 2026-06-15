package com.fxplatform.chart.entity;

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

/**
 * CandleEntity 是行情 K 线数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("market.candles")
public class CandleEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String symbol;
  private String timeframe;
  private Instant openTime;
  private BigDecimal open;
  private BigDecimal high;
  private BigDecimal low;
  private BigDecimal close;
  private BigDecimal volume = BigDecimal.ZERO;
  private String source = "massive";

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
