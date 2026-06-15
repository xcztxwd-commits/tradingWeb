package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.content.entity.ContentArticleEntity;
import com.fxplatform.content.entity.ContentMessageEntity;
import com.fxplatform.content.repository.ContentArticleRepository;
import com.fxplatform.content.repository.ContentMessageRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminContentCommandServiceTest {

  @Mock
  private ContentMessageRepository messageRepository;

  @Mock
  private ContentArticleRepository articleRepository;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void createMessageStoresTargetedMessageAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    AdminMessageRequest request = new AdminMessageRequest(
        targetUserId,
        "Margin reminder",
        "Please review your margin level.",
        "RISK_NOTICE",
        "DRAFT");
    when(messageRepository.save(org.mockito.ArgumentMatchers.any(ContentMessageEntity.class)))
        .thenAnswer(invocation -> {
          ContentMessageEntity message = invocation.getArgument(0);
          message.setId(UUID.randomUUID());
          return message;
        });

    AdminContentCommandService service = new AdminContentCommandService(messageRepository, articleRepository, auditLogService);

    var response = service.createMessage(actorUserId, request);

    assertThat(response.title()).isEqualTo("Margin reminder");
    assertThat(response.targetUserId()).isEqualTo(targetUserId);
    assertThat(response.status()).isEqualTo("DRAFT");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_MESSAGE_CREATE"),
        eq("MESSAGE"),
        eq(response.id().toString()),
        contains("Margin reminder"));
  }

  @Test
  void publishArticleStoresAnnouncementOrNewsAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    AdminArticleRequest request = new AdminArticleRequest(
        "ANNOUNCEMENT",
        "Trading hours",
        "Holiday session update",
        "Markets will run on holiday session hours.",
        "PUBLISHED",
        "zh-CN",
        1);
    when(articleRepository.save(org.mockito.ArgumentMatchers.any(ContentArticleEntity.class)))
        .thenAnswer(invocation -> {
          ContentArticleEntity article = invocation.getArgument(0);
          article.setId(UUID.randomUUID());
          return article;
        });

    AdminContentCommandService service = new AdminContentCommandService(messageRepository, articleRepository, auditLogService);

    var response = service.createArticle(actorUserId, request);

    assertThat(response.articleType()).isEqualTo("ANNOUNCEMENT");
    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.publishedAt()).isNotNull();
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_ARTICLE_CREATE"),
        eq("ARTICLE"),
        eq(response.id().toString()),
        contains("Trading hours"));
  }

  @Test
  void updatesAndDeletesArticleWithAuditTrail() {
    UUID actorUserId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    ContentArticleEntity article = new ContentArticleEntity();
    article.setId(articleId);
    article.setArticleType("NEWS");
    article.setTitle("Old title");
    article.setBody("Old body");
    article.setStatus("DRAFT");
    when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
    when(articleRepository.save(article)).thenReturn(article);

    AdminContentCommandService service = new AdminContentCommandService(messageRepository, articleRepository, auditLogService);
    var updated = service.updateArticle(actorUserId, articleId, new AdminArticleRequest(
        "NEWS",
        "Market update",
        "Summary",
        "Body",
        "PUBLISHED",
        "zh-CN",
        3));
    service.deleteArticle(actorUserId, articleId, "后台删除新闻");

    assertThat(updated.title()).isEqualTo("Market update");
    assertThat(updated.status()).isEqualTo("PUBLISHED");
    verify(articleRepository).deleteById(articleId);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ARTICLE_UPDATE"), eq("ARTICLE"), eq(articleId.toString()), contains("Market update"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ARTICLE_DELETE"), eq("ARTICLE"), eq(articleId.toString()), contains("后台删除新闻"));
  }

  @Test
  void updatesAndDeletesMessageWithAuditTrail() {
    UUID actorUserId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    ContentMessageEntity message = new ContentMessageEntity();
    message.setId(messageId);
    message.setTitle("Old message");
    message.setBody("Old body");
    message.setMessageType("SYSTEM");
    message.setStatus("DRAFT");
    when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
    when(messageRepository.save(message)).thenReturn(message);

    AdminContentCommandService service = new AdminContentCommandService(messageRepository, articleRepository, auditLogService);
    var updated = service.updateMessage(actorUserId, messageId, new AdminMessageRequest(
        targetUserId,
        "实名提醒",
        "请完成实名",
        "SYSTEM",
        "PUBLISHED"));
    service.deleteMessage(actorUserId, messageId, "后台删除通知");

    assertThat(updated.title()).isEqualTo("实名提醒");
    assertThat(updated.targetUserId()).isEqualTo(targetUserId);
    verify(messageRepository).deleteById(messageId);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_MESSAGE_UPDATE"), eq("MESSAGE"), eq(messageId.toString()), contains("实名提醒"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_MESSAGE_DELETE"), eq("MESSAGE"), eq(messageId.toString()), contains("后台删除通知"));
  }
}
