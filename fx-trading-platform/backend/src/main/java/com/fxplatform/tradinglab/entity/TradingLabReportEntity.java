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
@TableName(value = "trading_lab.reports", autoResultMap = true)
public class TradingLabReportEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID scenarioId;
  private String status;
  private String modelVersion;
  private String configSnapshotHash;
  private String codeVersion;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String metadataJson;

  private Long uncompressedBytes;
  private Long compressedBytes;
  private Integer chunkCount;
  private Instant retainedUntil;
  private Boolean permanent;
  private String failureCode;
  private String failureMessage;
  private UUID createdBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  private Instant completedAt;
  private Long version;
}
