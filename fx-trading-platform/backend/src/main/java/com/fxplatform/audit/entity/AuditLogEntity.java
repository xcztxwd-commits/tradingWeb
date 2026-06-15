package com.fxplatform.audit.entity;

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

/**
 * AuditLogEntity 是审计模块的数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("audit.audit_logs")
public class AuditLogEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID actorUserId;
  private String action;
  private String targetType;
  private String targetId;
  private String requestId;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String details;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
