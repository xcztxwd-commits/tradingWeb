package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.content.entity.ContentArticleEntity;
import com.fxplatform.content.entity.ContentMessageEntity;
import com.fxplatform.content.repository.ContentArticleRepository;
import com.fxplatform.content.repository.ContentMessageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminContentQueryServiceTest {

  @Mock
  private ContentMessageRepository messageRepository;

  @Mock
  private ContentArticleRepository articleRepository;

  @Test
  void messagesReturnPagedDtos() {
    ContentMessageEntity message = new ContentMessageEntity();
    message.setId(UUID.randomUUID());
    message.setTitle("Risk notice");
    message.setBody("Please review margin.");
    message.setMessageType("RISK_NOTICE");
    message.setStatus("PUBLISHED");
    message.setSentBy(UUID.randomUUID());
    when(messageRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(message));

    var page = new AdminContentQueryService(messageRepository, articleRepository).messages(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).title()).isEqualTo("Risk notice");
    assertThat(page.items().get(0).messageType()).isEqualTo("RISK_NOTICE");
  }

  @Test
  void articlesReturnPagedDtos() {
    ContentArticleEntity article = new ContentArticleEntity();
    article.setId(UUID.randomUUID());
    article.setArticleType("NEWS");
    article.setTitle("Market update");
    article.setBody("EURUSD spreads normalized.");
    article.setStatus("DRAFT");
    article.setLanguage("zh-CN");
    article.setSortOrder(1);
    when(articleRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(article));

    var page = new AdminContentQueryService(messageRepository, articleRepository).articles(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).articleType()).isEqualTo("NEWS");
    assertThat(page.items().get(0).title()).isEqualTo("Market update");
  }

  private static <T> Page<T> page(T item) {
    Page<T> page = Page.of(1, 20);
    page.setRecords(List.of(item));
    page.setTotal(1);
    return page;
  }
}
