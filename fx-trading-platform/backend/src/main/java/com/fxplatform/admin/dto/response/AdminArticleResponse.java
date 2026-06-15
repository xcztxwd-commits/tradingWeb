package com.fxplatform.admin.dto.response;

import com.fxplatform.content.entity.ContentArticleEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminArticleResponse 是后台公告和新闻响应 DTO。
 *
 * @param id 内容 ID。
 * @param articleType 内容类型。
 * @param title 标题。
 * @param summary 摘要。
 * @param body 正文。
 * @param status 状态。
 * @param language 语言标识。
 * @param sortOrder 展示排序值。
 * @param publishedAt 发布时间。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminArticleResponse(
    UUID id,
    String articleType,
    String title,
    String summary,
    String body,
    String status,
    String language,
    Integer sortOrder,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将内容实体映射为后台 DTO。
   */
  public static AdminArticleResponse from(ContentArticleEntity entity) {
    return new AdminArticleResponse(
        entity.getId(),
        entity.getArticleType(),
        entity.getTitle(),
        entity.getSummary(),
        entity.getBody(),
        entity.getStatus(),
        entity.getLanguage(),
        entity.getSortOrder(),
        entity.getPublishedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
