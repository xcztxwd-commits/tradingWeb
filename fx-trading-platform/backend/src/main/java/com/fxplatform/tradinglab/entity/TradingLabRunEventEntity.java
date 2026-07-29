package com.fxplatform.tradinglab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName(value = "trading_lab.run_events", autoResultMap = true)
public class TradingLabRunEventEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID runId;
  private Long sequence;
  private String eventType;
  private Instant virtualTime;
  private Instant realTime;
  private String correlationId;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String payloadJson;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
