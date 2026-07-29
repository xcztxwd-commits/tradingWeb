package com.fxplatform.engagement.persistence.entity;

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
@TableName(value = "content.engagement_outbox", autoResultMap = true)
public class EngagementOutboxEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String aggregateType;
  private UUID aggregateId;
  private String eventType;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String payload;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  private Instant publishedAt;
  private Integer attemptCount;
  private String lastError;
}
