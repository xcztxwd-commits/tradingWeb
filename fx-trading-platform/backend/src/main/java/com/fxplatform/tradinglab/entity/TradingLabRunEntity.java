package com.fxplatform.tradinglab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName(value = "trading_lab.runs", autoResultMap = true)
public class TradingLabRunEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID scenarioId;
  private String state;
  private Long queueSequence;
  private Short leaseKey;
  private String leaseOwner;
  private Instant leaseUntil;
  private Boolean cancelRequested;
  private Boolean pauseRequested;
  private Instant virtualStartedAt;
  private Instant virtualCurrentAt;
  private Long processedTicks;
  private Long totalTicks;
  private BigDecimal speedMultiplier;
  private Long currentStep;
  private String failureCode;
  private String failureMessage;
  private UUID reportId;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String scenarioSnapshotJson;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String configSnapshotJson;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String localCalculationJson;

  private String configSnapshotHash;
  private String modelVersion;
  private String symbolConfigVersion;
  private String codeVersion;
  private UUID createdBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  private Instant startedAt;
  private Instant finishedAt;
  private Long version;
}
