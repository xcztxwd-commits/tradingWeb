package com.fxplatform.admin.entity;

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
 * 后台角色实体，对应菜单权限和数据权限的角色主体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.roles")
public class AdminRoleEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String roleName;
  private String roleCode;
  private Boolean enabled = true;
  private Boolean systemManaged = false;
  private Integer sortOrder = 0;
  private String description;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
