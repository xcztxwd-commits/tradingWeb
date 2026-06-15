package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.admin.dto.response.AdminArticleResponse;
import com.fxplatform.admin.dto.response.AdminMessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminContentCommandService contentCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return ("news".equals(pageKey) || "notices".equals(pageKey) || "member-notices".equals(pageKey))
        && ("create".equals(action) || "edit".equals(action));
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    if ("news".equals(context.pageKey())) {
      AdminArticleResponse article = contentCommandService.createArticle(context.actorUserId(), new AdminArticleRequest(
          "NEWS",
          AdminFeaturePayloads.string(context.payload(), "title", "News"),
          AdminFeaturePayloads.string(context.payload(), "summary", ""),
          AdminFeaturePayloads.string(context.payload(), "body", AdminFeaturePayloads.string(context.payload(), "summary", "News")),
          "PUBLISHED",
          "zh-CN",
          AdminFeaturePayloads.integer(context.payload(), "sort", 0)));
      return article.id().toString();
    }
    if ("notices".equals(context.pageKey())) {
      AdminArticleResponse article = contentCommandService.createArticle(context.actorUserId(), new AdminArticleRequest(
          "ANNOUNCEMENT",
          AdminFeaturePayloads.string(context.payload(), "title", "Notice"),
          AdminFeaturePayloads.string(context.payload(), "summary", ""),
          AdminFeaturePayloads.string(context.payload(), "body", AdminFeaturePayloads.string(context.payload(), "title", "Notice")),
          "PUBLISHED",
          "zh-CN",
          AdminFeaturePayloads.integer(context.payload(), "sort", 0)));
      return article.id().toString();
    }
    AdminMessageResponse message = contentCommandService.createMessage(context.actorUserId(), new AdminMessageRequest(
        AdminFeaturePayloads.uuid(context.payload(), "userId"),
        "用户通知",
        AdminFeaturePayloads.string(context.payload(), "content", "用户通知"),
        "SYSTEM",
        "PUBLISHED"));
    return message.id().toString();
  }
}
