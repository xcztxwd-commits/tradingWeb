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
 * 截图后台缺少专用领域表时使用的通用页面记录。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.feature_records")
public class AdminFeatureRecordEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;

  private String pageKey;

  private String recordKey;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String data = "{}";

  private String status = "ACTIVE";

  private UUID createdBy;

  private UUID updatedBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
