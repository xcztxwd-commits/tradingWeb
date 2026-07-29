package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.message_publications")
public class MessagePublicationEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID contentItemId;
  private MessageSourceType sourceType;
  private UUID sourceCampaignId;
  private AudienceType audienceType;
  private MessageLifecycleStatus lifecycleStatus;
  private String category;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant scheduledAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant sentAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant audienceCutoffAt;
  private UUID createdBy;
  private UUID updatedBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant deletedAt;
}
