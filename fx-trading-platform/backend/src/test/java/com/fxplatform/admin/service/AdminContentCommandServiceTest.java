package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.content.entity.ContentArticleEntity;
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
  void createLegacyMessageIsReadOnlyWithoutRepositoryOrAuditWrites() {
    BusinessException failure = catchThrowableOfType(
        () -> service().createMessage(UUID.randomUUID(), messageRequest()),
        BusinessException.class);

    assertLegacyReadOnly(failure);
    verifyNoInteractions(messageRepository, auditLogService);
  }

  @Test
  void updateLegacyMessageIsReadOnlyWithoutRepositoryOrAuditWrites() {
    BusinessException failure = catchThrowableOfType(
        () -> service().updateMessage(UUID.randomUUID(), UUID.randomUUID(), messageRequest()),
        BusinessException.class);

    assertLegacyReadOnly(failure);
    verifyNoInteractions(messageRepository, auditLogService);
  }

  @Test
  void deleteLegacyMessageIsReadOnlyWithoutRepositoryOrAuditWrites() {
    BusinessException failure = catchThrowableOfType(
        () -> service().deleteMessage(UUID.randomUUID(), UUID.randomUUID(), "legacy delete"),
        BusinessException.class);

    assertLegacyReadOnly(failure);
    verifyNoInteractions(messageRepository, auditLogService);
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

    AdminContentCommandService service = new AdminContentCommandService(articleRepository, auditLogService);

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

    AdminContentCommandService service = new AdminContentCommandService(articleRepository, auditLogService);
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

  private AdminContentCommandService service() {
    return new AdminContentCommandService(articleRepository, auditLogService);
  }

  private static AdminMessageRequest messageRequest() {
    return new AdminMessageRequest(
        UUID.randomUUID(),
        "Legacy message",
        "Legacy body",
        "SYSTEM",
        "DRAFT");
  }

  private static void assertLegacyReadOnly(BusinessException failure) {
    assertThat(failure).isNotNull();
    assertThat(failure.getCode()).isEqualTo("LEGACY_MESSAGE_READ_ONLY");
  }
}
