package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.popup_deliveries")
public class PopupDeliveryEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID queueSessionId;
  private UUID campaignId;
  private UUID userId;
  private UUID revisionId;
  private String tokenHash;
  private PopupDeliveryStatus status;
  private String pageKey;
  private DeviceClass deviceClass;
  private Instant issuedAt;
  private Instant expiresAt;
  private Instant shownAt;
  private Instant closedAt;
  private String closeReason;
  private Instant clickedAt;
  private Instant invalidatedAt;
}
