package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminArticleRequest 是后台公告和新闻请求 DTO。
 *
 * @param articleType 内容类型，例如 ANNOUNCEMENT 或 NEWS。
 * @param title 标题。
 * @param summary 摘要。
 * @param body 正文。
 * @param status 状态，例如 DRAFT 或 PUBLISHED。
 * @param language 语言标识，例如 zh-CN。
 * @param sortOrder 展示排序值。
 */
public record AdminArticleRequest(
    @NotBlank String articleType,
    @NotBlank String title,
    String summary,
    @NotBlank String body,
    @NotBlank String status,
    String language,
    Integer sortOrder
) {
}
