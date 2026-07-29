package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.engagement.persistence.enums.ContentAssetStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.content_assets")
public class ContentAssetEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String storageKey;
  private String mimeType;
  private Long byteSize;
  private Integer width;
  private Integer height;
  private String sha256;
  private ContentAssetStatus status;
  private UUID createdBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  private Instant deletedAt;
}
