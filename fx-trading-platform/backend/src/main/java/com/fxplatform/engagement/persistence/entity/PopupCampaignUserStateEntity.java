package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.popup_campaign_user_states")
public class PopupCampaignUserStateEntity {

  private UUID campaignId;
  private UUID userId;
  private Integer totalImpressions;
  private LocalDate dailyBucket;
  private Integer dailyImpressions;
  private Instant lastImpressionAt;
  private Instant optedOutAt;
  private Instant lastClickedAt;
  private UUID activeDeliveryId;
  private Instant activeDeliveryExpiresAt;
  private Long version;
}
