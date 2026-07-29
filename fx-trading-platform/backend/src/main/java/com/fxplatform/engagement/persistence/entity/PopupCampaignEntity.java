package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName(value = "content.popup_campaigns", autoResultMap = true)
public class PopupCampaignEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String name;
  private UUID contentItemId;
  private PopupCampaignLifecycleStatus lifecycleStatus;
  private AudienceType audienceType;
  private Boolean syncToInbox;
  private Integer priority;
  private DisplayScope displayScope;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String pageKeys;

  private DeviceScope deviceScope;
  private TemplateSize templateSize;
  private String timeZone;
  private Instant startAt;
  private Instant endAt;
  private Integer maxTotalImpressions;
  private Integer maxDailyImpressions;
  private Integer minIntervalSeconds;
  private Instant firstPublishedAt;
  private Instant lastPublishedAt;
  private UUID createdBy;
  private UUID updatedBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant pausedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant endedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant deletedAt;
}
