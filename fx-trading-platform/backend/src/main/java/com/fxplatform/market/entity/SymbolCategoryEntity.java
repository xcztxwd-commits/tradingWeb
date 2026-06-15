package com.fxplatform.market.entity;

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
 * SymbolCategoryEntity 是交易品种分类数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("market.symbol_categories")
public class SymbolCategoryEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String name;
  private String code;
  private Integer sortOrder = 0;
  private Boolean enabled = true;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
