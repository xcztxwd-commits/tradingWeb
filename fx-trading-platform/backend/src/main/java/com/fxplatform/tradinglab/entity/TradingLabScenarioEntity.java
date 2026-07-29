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
@TableName(value = "trading_lab.scenarios", autoResultMap = true)
public class TradingLabScenarioEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String name;
  private String description;
  private String status;
  private Boolean negativeMode;
  private String seed;
  private String modelVersion;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String scenarioJson;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String configSnapshotJson;

  private String configSnapshotHash;
  private String symbolConfigVersion;
  private String codeVersion;
  private UUID createdBy;
  private UUID updatedBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  private Long version;
}
