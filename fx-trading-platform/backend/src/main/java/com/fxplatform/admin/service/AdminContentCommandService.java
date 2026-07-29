package com.fxplatform.admin.service;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.admin.dto.response.AdminArticleResponse;
import com.fxplatform.admin.dto.response.AdminMessageResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.content.entity.ContentArticleEntity;
import com.fxplatform.content.repository.ContentArticleRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminContentCommandService 承载后台消息、公告和新闻写操作。
 */
@Service
@RequiredArgsConstructor
public class AdminContentCommandService {

  /** 内容仓储，用于保存公告和新闻。 */
  private final ContentArticleRepository articleRepository;
  /** 审计服务，用于记录内容发布和修改动作。 */
  private final AuditLogService auditLogService;

  /**
   * 创建站内消息。
   */
  @Transactional
  public AdminMessageResponse createMessage(UUID actorUserId, AdminMessageRequest request) {
    throw legacyMessageReadOnly();
  }

  /** 编辑站内消息，供通知表格的编辑弹窗保存使用。 */
  @Transactional
  public AdminMessageResponse updateMessage(UUID actorUserId, UUID messageId, AdminMessageRequest request) {
    throw legacyMessageReadOnly();
  }

  /** 删除站内消息，并记录后台操作原因。 */
  @Transactional
  public void deleteMessage(UUID actorUserId, UUID messageId, String reason) {
    throw legacyMessageReadOnly();
  }

  /**
   * 创建公告或新闻。
   */
  @Transactional
  public AdminArticleResponse createArticle(UUID actorUserId, AdminArticleRequest request) {
    ContentArticleEntity article = new ContentArticleEntity();
    applyArticle(article, request);
    ContentArticleEntity saved = articleRepository.save(article);
    auditLogService.record(
        actorUserId,
        "ADMIN_ARTICLE_CREATE",
        "ARTICLE",
        saved.getId().toString(),
        details(request.title(), request.articleType() + ":" + request.status()));
    return AdminArticleResponse.from(saved);
  }

  /** 编辑公告或新闻，供截图中的公告列表和新闻列表编辑按钮调用。 */
  @Transactional
  public AdminArticleResponse updateArticle(UUID actorUserId, UUID articleId, AdminArticleRequest request) {
    ContentArticleEntity article = articleRepository.findById(articleId)
        .orElseThrow(() -> new BusinessException("ARTICLE_NOT_FOUND", "Article not found"));
    applyArticle(article, request);
    ContentArticleEntity saved = articleRepository.save(article);
    auditLogService.record(
        actorUserId,
        "ADMIN_ARTICLE_UPDATE",
        "ARTICLE",
        articleId.toString(),
        details(request.title(), request.articleType() + ":" + request.status()));
    return AdminArticleResponse.from(saved);
  }

  /** 删除公告或新闻，并记录删除原因。 */
  @Transactional
  public void deleteArticle(UUID actorUserId, UUID articleId, String reason) {
    ContentArticleEntity article = articleRepository.findById(articleId)
        .orElseThrow(() -> new BusinessException("ARTICLE_NOT_FOUND", "Article not found"));
    articleRepository.deleteById(articleId);
    auditLogService.record(
        actorUserId,
        "ADMIN_ARTICLE_DELETE",
        "ARTICLE",
        articleId.toString(),
        details(article.getTitle(), reason));
  }

  /** 旧消息写入口保留用于兼容路由，但数据只能通过新的 engagement API 写入。 */
  private static BusinessException legacyMessageReadOnly() {
    return new BusinessException(
        "LEGACY_MESSAGE_READ_ONLY",
        "Legacy content.messages is read-only; use the engagement message API");
  }

  /** 将文章请求写入实体，新增和编辑共用，保持公告/新闻字段一致。 */
  private void applyArticle(ContentArticleEntity article, AdminArticleRequest request) {
    article.setArticleType(request.articleType());
    article.setTitle(request.title());
    article.setSummary(request.summary());
    article.setBody(request.body());
    article.setStatus(request.status());
    article.setLanguage(request.language() == null ? "zh-CN" : request.language());
    article.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    article.setPublishedAt(publishedAtFor(request.status()));
  }

  /**
   * 根据状态决定是否设置发布时间。
   */
  private Instant publishedAtFor(String status) {
    return "PUBLISHED".equalsIgnoreCase(status) ? DateUtil.date().toInstant() : null;
  }

  /**
   * 构造审计 JSON 明细。
   */
  private String details(String title, String status) {
    return AuditDetailsBuilder.create()
        .put("title", title)
        .put("status", status)
        .toJson();
  }
}
