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
 * 后台表格列偏好实体，映射 admin.table_column_preferences。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.table_column_preferences")
public class AdminTableColumnPreferenceEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private String pageKey;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String hiddenColumns;
  private String tableSize;
  private Boolean showBorder;
  private Boolean zebra;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
