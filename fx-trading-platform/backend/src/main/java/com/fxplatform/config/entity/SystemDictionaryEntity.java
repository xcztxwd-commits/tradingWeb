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
 * SystemDictionaryEntity 是系统字典数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("config.system_dictionaries")
public class SystemDictionaryEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String groupKey;
  private String itemKey;
  private String itemValue;
  private Boolean enabled = true;
  private Integer displayOrder = 0;
  private String description;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
