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
 * 后台菜单实体，既表示页面菜单，也承载页面级权限标识。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.menus")
public class AdminMenuEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID parentId;
  private String menuName;
  private String permissionKey;
  private String path;
  private String component;
  private String menuType = "MENU";
  private Boolean enabled = true;
  private Integer sortOrder = 0;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
