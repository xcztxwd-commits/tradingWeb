package com.fxplatform.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.auth.enums.UserSessionStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("auth.user_sessions")
public class UserSessionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private String refreshTokenHash;
  private String accessTokenJti;
  private String deviceId;
  private String ipAddress;
  private String userAgent;
  private UserSessionStatus status = UserSessionStatus.ACTIVE;
  private Instant expiresAt;
  private Instant revokedAt;
  private String revokeReason;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
