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
 * 验证码发送日志实体，映射 audit.verification_code_logs。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("audit.verification_code_logs")
public class VerificationCodeLogEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String scene;
  private String account;
  private String channel;
  private String code;
  private String status;
  private String errorMessage;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
