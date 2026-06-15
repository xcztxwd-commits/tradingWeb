package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminArticleResponse;
import com.fxplatform.admin.dto.response.AdminMessageResponse;
import com.fxplatform.content.entity.ContentArticleEntity;
import com.fxplatform.content.entity.ContentMessageEntity;
import com.fxplatform.content.repository.ContentArticleRepository;
import com.fxplatform.content.repository.ContentMessageRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminContentQueryService 提供后台内容查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminContentQueryService {

  /** 站内消息 Mapper，用于分页读取消息。 */
  private final ContentMessageRepository messageRepository;
  /** 内容 Mapper，用于分页读取公告和新闻。 */
  private final ContentArticleRepository articleRepository;

  /** 分页查询站内消息。 */
  public AdminPageResponse<AdminMessageResponse> messages(int page, int size) {
    return AdminPageResponse.from(messageRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminMessageResponse::from));
  }

  /** 按截图列表协议分页筛选站内通知。 */
  public AdminPageResponse<AdminMessageResponse> messages(AdminFeaturePageQuery query) {
    QueryWrapper<ContentMessageEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "targetUserId", "target_user_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "userId", "target_user_id");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "title", "title");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "body", "body");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "content", "body");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "messageType", "message_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "status", "status");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "targetUserId", "target_user_id",
        "userId", "target_user_id",
        "title", "title",
        "messageType", "message_type",
        "status", "status",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "created_at", false);
    return AdminPageResponse.from(messageRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminMessageResponse::from));
  }

  /** 分页查询公告和新闻。 */
  public AdminPageResponse<AdminArticleResponse> articles(int page, int size) {
    return AdminPageResponse.from(articleRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminArticleResponse::from));
  }

  /** 按截图列表协议分页筛选公告和新闻。 */
  public AdminPageResponse<AdminArticleResponse> articles(AdminFeaturePageQuery query, String articleType) {
    QueryWrapper<ContentArticleEntity> wrapper = new QueryWrapper<>();
    String effectiveType = articleType == null || articleType.isBlank() ? query.filter("articleType") : articleType;
    if (effectiveType != null && !effectiveType.isBlank()) {
      wrapper.eq("article_type", effectiveType);
    }
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "title", "title");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "summary", "summary");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "status", "status");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "language", "language");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "articleType", "article_type",
        "title", "title",
        "status", "status",
        "language", "language",
        "sortOrder", "sort_order",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "sort_order", true);
    return AdminPageResponse.from(articleRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminArticleResponse::from));
  }
}
