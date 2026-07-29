package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName(value = "content.content_revisions", autoResultMap = true)
public class ContentRevisionEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID contentItemId;
  private Integer revisionNo;
  private String title;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String bodyDocument;

  private String sanitizedHtml;
  private UUID coverAssetId;
  private String ctaLabel;
  private String ctaRouteKey;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String ctaParams;

  private UUID createdBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
