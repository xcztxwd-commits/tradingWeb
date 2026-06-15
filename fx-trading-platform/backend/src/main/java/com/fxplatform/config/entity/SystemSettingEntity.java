package com.fxplatform.config.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SystemSettingEntity 是系统设置数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("config.system_settings")
public class SystemSettingEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String settingKey;
  private String settingValue;
  private String valueType = "STRING";
  private String description;
  private Boolean editable = true;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
