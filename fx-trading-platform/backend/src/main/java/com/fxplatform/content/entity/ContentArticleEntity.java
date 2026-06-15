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
 * ContentArticleEntity 是内容文章数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("content.articles")
public class ContentArticleEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String articleType;
  private String title;
  private String summary;
  private String body;
  private String status = "DRAFT";
  private String language = "zh-CN";
  private Integer sortOrder = 0;
  private Instant publishedAt;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
