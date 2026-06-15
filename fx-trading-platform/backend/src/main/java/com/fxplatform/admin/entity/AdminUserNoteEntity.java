package com.fxplatform.admin.entity;

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
 * AdminUserNoteEntity 是后台会员备注数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("admin.user_notes")
public class AdminUserNoteEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private UUID adminUserId;
  private String note;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
