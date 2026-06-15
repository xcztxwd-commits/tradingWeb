package com.fxplatform.content.entity;

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
 * ContentMessageEntity 是站内消息数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("content.messages")
public class ContentMessageEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID targetUserId;
  private String title;
  private String body;
  private String messageType;
  private String status = "DRAFT";
  private UUID sentBy;
  private Instant publishedAt;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
