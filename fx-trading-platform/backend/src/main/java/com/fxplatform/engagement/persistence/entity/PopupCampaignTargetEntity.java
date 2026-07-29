package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.popup_campaign_targets")
public class PopupCampaignTargetEntity {

  private UUID campaignId;
  private UUID userId;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
