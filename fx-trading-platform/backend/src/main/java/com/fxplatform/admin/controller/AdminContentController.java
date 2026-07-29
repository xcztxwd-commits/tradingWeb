package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.response.AdminArticleResponse;
import com.fxplatform.admin.dto.response.AdminMessageResponse;
import com.fxplatform.admin.service.AdminContentCommandService;
import com.fxplatform.admin.service.AdminContentQueryService;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminContentController 提供后台消息、公告和新闻接口。
 */
@RestController
@RequestMapping("/api/admin/content")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminContentController {

  /** 内容查询服务。 */
  private final AdminContentQueryService contentQueryService;
  /** 内容写操作服务。 */
  private final AdminContentCommandService contentCommandService;

  /**
   * 分页查询站内消息。
   */
  @GetMapping("/messages")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_READ + "')")
  public ApiResponse<AdminPageResponse<AdminMessageResponse>> messages(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(contentQueryService.messages(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /**
   * 创建站内消息。
   */
  @PostMapping("/messages")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT + "')")
  public ApiResponse<AdminMessageResponse> createMessage(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminMessageRequest request
  ) {
    return ApiResponse.success(contentCommandService.createMessage(principal.id(), request));
  }

  /** 编辑站内消息，供通知表编辑按钮调用。 */
  @PutMapping("/messages/{messageId}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT + "')")
  public ApiResponse<AdminMessageResponse> updateMessage(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID messageId,
      @Valid @RequestBody AdminMessageRequest request
  ) {
    return ApiResponse.success(contentCommandService.updateMessage(principal.id(), messageId, request));
  }

  /** 删除站内消息，供通知表删除按钮调用。 */
  @DeleteMapping("/messages/{messageId}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_DELETE + "')")
  public ApiResponse<Void> deleteMessage(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID messageId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    contentCommandService.deleteMessage(principal.id(), messageId, request.reason());
    return ApiResponse.success(null);
  }

  /**
   * 分页查询公告和新闻。
   */
  @GetMapping("/articles")
  public ApiResponse<AdminPageResponse<AdminArticleResponse>> articles(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String articleType,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "desc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(contentQueryService.articles(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams),
        articleType));
  }

  /**
   * 创建公告或新闻。
   */
  @PostMapping("/articles")
  public ApiResponse<AdminArticleResponse> createArticle(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminArticleRequest request
  ) {
    return ApiResponse.success(contentCommandService.createArticle(principal.id(), request));
  }

  /** 编辑公告或新闻。 */
  @PutMapping("/articles/{articleId}")
  public ApiResponse<AdminArticleResponse> updateArticle(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID articleId,
      @Valid @RequestBody AdminArticleRequest request
  ) {
    return ApiResponse.success(contentCommandService.updateArticle(principal.id(), articleId, request));
  }

  /** 删除公告或新闻。 */
  @DeleteMapping("/articles/{articleId}")
  public ApiResponse<Void> deleteArticle(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID articleId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    contentCommandService.deleteArticle(principal.id(), articleId, request.reason());
    return ApiResponse.success(null);
  }
}
