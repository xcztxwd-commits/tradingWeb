package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.engagement.persistence.enums.MessageReadSource;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.message_receipts")
public class MessageReceiptEntity {

  private UUID publicationId;
  private UUID userId;
  private Instant deliveredAt;
  private Instant readAt;
  private MessageReadSource readSource;
  private Instant hiddenAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
