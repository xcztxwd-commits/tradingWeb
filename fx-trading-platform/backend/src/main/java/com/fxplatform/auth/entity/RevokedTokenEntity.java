package com.fxplatform.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("auth.revoked_tokens")
public class RevokedTokenEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String tokenHash;
  private String tokenType;
  private Instant expiresAt;
  private Instant revokedAt;
}
