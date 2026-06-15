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
 * 后台部门实体，用于数据权限范围配置。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.departments")
public class AdminDepartmentEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String departmentName;
  private UUID parentId;
  private String leader;
  private String phone;
  private Boolean enabled = true;
  private Integer sortOrder = 0;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
