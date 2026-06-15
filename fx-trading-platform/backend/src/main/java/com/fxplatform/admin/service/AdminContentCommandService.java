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
import com.fxplatform.content.entity.ContentMessageEntity;
import com.fxplatform.content.repository.ContentArticleRepository;
import com.fxplatform.content.repository.ContentMessageRepository;
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

  /** 站内消息仓储，用于保存后台消息。 */
  private final ContentMessageRepository messageRepository;
  /** 内容仓储，用于保存公告和新闻。 */
  private final ContentArticleRepository articleRepository;
  /** 审计服务，用于记录内容发布和修改动作。 */
  private final AuditLogService auditLogService;

  /**
   * 创建站内消息。
   */
  @Transactional
  public AdminMessageResponse createMessage(UUID actorUserId, AdminMessageRequest request) {
    ContentMessageEntity message = new ContentMessageEntity();
    applyMessage(message, actorUserId, request);
    ContentMessageEntity saved = messageRepository.save(message);
    auditLogService.record(
        actorUserId,
        "ADMIN_MESSAGE_CREATE",
        "MESSAGE",
        saved.getId().toString(),
        details(request.title(), request.status()));
    return AdminMessageResponse.from(saved);
  }

  /** 编辑站内消息，供通知表格的编辑弹窗保存使用。 */
  @Transactional
  public AdminMessageResponse updateMessage(UUID actorUserId, UUID messageId, AdminMessageRequest request) {
    ContentMessageEntity message = messageRepository.findById(messageId)
        .orElseThrow(() -> new BusinessException("MESSAGE_NOT_FOUND", "Message not found"));
    applyMessage(message, actorUserId, request);
    ContentMessageEntity saved = messageRepository.save(message);
    auditLogService.record(
        actorUserId,
        "ADMIN_MESSAGE_UPDATE",
        "MESSAGE",
        messageId.toString(),
        details(request.title(), request.status()));
    return AdminMessageResponse.from(saved);
  }

  /** 删除站内消息，并记录后台操作原因。 */
  @Transactional
  public void deleteMessage(UUID actorUserId, UUID messageId, String reason) {
    ContentMessageEntity message = messageRepository.findById(messageId)
        .orElseThrow(() -> new BusinessException("MESSAGE_NOT_FOUND", "Message not found"));
    messageRepository.deleteById(messageId);
    auditLogService.record(
        actorUserId,
        "ADMIN_MESSAGE_DELETE",
        "MESSAGE",
        messageId.toString(),
        details(message.getTitle(), reason));
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

  /** 将消息请求写入实体，新增和编辑共用，减少字段遗漏。 */
  private void applyMessage(ContentMessageEntity message, UUID actorUserId, AdminMessageRequest request) {
    message.setTargetUserId(request.targetUserId());
    message.setTitle(request.title());
    message.setBody(request.body());
    message.setMessageType(request.messageType());
    message.setStatus(request.status());
    message.setSentBy(actorUserId);
    message.setPublishedAt(publishedAtFor(request.status()));
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
