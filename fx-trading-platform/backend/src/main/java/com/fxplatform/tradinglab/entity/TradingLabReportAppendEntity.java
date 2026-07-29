package com.fxplatform.tradinglab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading_lab.report_appends")
public class TradingLabReportAppendEntity {

  private UUID reportId;
  private String section;
  private Long sourceSequence;
  private Long canonicalBytes;
  private String canonicalChecksum;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
