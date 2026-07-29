package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.popup_queue_sessions")
public class PopupQueueSessionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private String triggerType;
  private String surfacePageKey;
  private DeviceClass deviceClass;
  private Integer maxItems;
  private Integer issuedCount;
  private String terminatedReason;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  private Instant expiresAt;
  private Instant terminatedAt;
}
