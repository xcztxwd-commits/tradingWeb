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
@TableName(value = "trading_lab.audit_events", autoResultMap = true)
public class TradingLabAuditEventEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID actorId;
  private String clientIp;
  private UUID requestId;
  private UUID scenarioId;
  private UUID runId;
  private String action;
  private String result;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String detailsJson;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
