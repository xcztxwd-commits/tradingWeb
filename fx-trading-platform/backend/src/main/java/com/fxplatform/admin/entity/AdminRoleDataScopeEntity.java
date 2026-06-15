package com.fxplatform.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 角色数据权限范围实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.role_data_scopes")
public class AdminRoleDataScopeEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID roleId;
  private String scopeType;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String departmentIds = "[]";
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
