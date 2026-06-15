package com.fxplatform.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * UserEntity 是认证模块的数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("auth.users")
public class UserEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String email;
  private String phone;
  private String passwordHash;
  private UserStatus status = UserStatus.ACTIVE;
  private UserRole role = UserRole.USER;
  private String kycStatus = "NOT_SUBMITTED";
  private String riskLevel = "NORMAL";

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
