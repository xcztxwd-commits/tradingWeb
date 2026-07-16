package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import com.fxplatform.common.mybatis.TextArrayTypeHandler;
import com.fxplatform.market.enums.ProviderHealthStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName(value = "market.data_providers", autoResultMap = true)
public class DataProviderEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String code;
  private String name;
  private String providerType;
  @TableField(typeHandler = TextArrayTypeHandler.class)
  private List<String> assetClasses = List.of();
  private String restBaseUrl;
  private String wsUrl;
  private Boolean enabled = true;
  private Integer priority = 100;
  private Integer timeoutMs = 5000;
  private Integer rateLimitPerMinute = 1200;
  private ProviderHealthStatus healthStatus = ProviderHealthStatus.UNKNOWN;
  private Instant lastHealthCheckAt;
  private Instant lastSuccessAt;
  private Instant lastFailureAt;
  private Long failureCount = 0L;
  private Long avgLatencyMs;
  private Instant lastQuoteSuccessAt;
  private Long quoteStalenessMs;
  private Instant lastInstrumentSyncAt;
  private Integer lastInstrumentSyncCount = 0;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String configJson = "{}";
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  public void setHealthStatus(ProviderHealthStatus healthStatus) {
    this.healthStatus = healthStatus;
  }

  public void setHealthStatus(String healthStatus) {
    this.healthStatus = ProviderHealthStatus.fromCode(healthStatus);
  }
}
