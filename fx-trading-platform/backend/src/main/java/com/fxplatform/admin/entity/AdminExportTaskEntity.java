package com.fxplatform.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.admin.enums.AdminTaskStatus;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 后台导出任务实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.export_tasks")
public class AdminExportTaskEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String pageKey;
  private AdminTaskStatus status;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String filterJson;
  private String fileUrl;
  private UUID createdBy;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  public void setStatus(AdminTaskStatus status) {
    this.status = status;
  }

  public void setStatus(String status) {
    this.status = AdminTaskStatus.fromCode(status);
  }
}
