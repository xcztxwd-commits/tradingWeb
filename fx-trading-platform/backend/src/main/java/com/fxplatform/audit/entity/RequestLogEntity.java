package com.fxplatform.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 请求日志实体，映射 audit.request_logs。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("audit.request_logs")
public class RequestLogEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String requestId;
  private String method;
  private String path;
  private String queryString;
  private String clientIp;
  private String userAgent;
  private Integer statusCode;
  private Long durationMs;
  private String errorMessage;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
