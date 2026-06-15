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
 * 角色菜单权限实体，buttons 字段保存页面按钮权限集合。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.role_menu_permissions")
public class AdminRoleMenuPermissionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID roleId;
  private UUID menuId;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String buttons = "[]";
  private Boolean enabled = true;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
