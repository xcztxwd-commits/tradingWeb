package com.fxplatform.tradinglab.entity;

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
@TableName(value = "trading_lab.run_transitions", autoResultMap = true)
public class TradingLabRunTransitionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID runId;
  private String fromState;
  private String toState;
  private Long runVersion;
  private String reason;
  private String idempotencyKey;
  private Instant realTime;
  private Instant virtualTime;
  private UUID actorId;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String detailsJson;
}
