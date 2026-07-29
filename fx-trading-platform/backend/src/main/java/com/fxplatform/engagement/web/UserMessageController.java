package com.fxplatform.engagement.web;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.application.message.MessageReceiptService;
import com.fxplatform.engagement.web.dto.UnreadMessageCountResponse;
import com.fxplatform.engagement.web.dto.UserMessagePageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/messages")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class UserMessageController {

  private final MessageReceiptService receiptService;

  @GetMapping
  public ApiResponse<UserMessagePageResponse> list(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size,
      @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
    return ApiResponse.success(
        receiptService.listMessages(principal.id(), page, size, unreadOnly));
  }

  @GetMapping("/unread-count")
  public ApiResponse<UnreadMessageCountResponse> unreadCount(
      @AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(new UnreadMessageCountResponse(
        receiptService.unreadCount(principal.id())));
  }

  @PostMapping("/{publicationId}/read")
  public ApiResponse<Void> markRead(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("publicationId") UUID publicationId) {
    receiptService.markRead(principal.id(), publicationId);
    return ApiResponse.success(null);
  }

  @PostMapping("/{publicationId}/unread")
  public ApiResponse<Void> markUnread(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("publicationId") UUID publicationId) {
    receiptService.markUnread(principal.id(), publicationId);
    return ApiResponse.success(null);
  }

  @PostMapping("/read-all")
  public ApiResponse<Void> markAllRead(
      @AuthenticationPrincipal UserPrincipal principal) {
    receiptService.markAllRead(principal.id());
    return ApiResponse.success(null);
  }

  @PostMapping("/{publicationId}/hide")
  public ApiResponse<Void> hide(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("publicationId") UUID publicationId) {
    receiptService.hide(principal.id(), publicationId);
    return ApiResponse.success(null);
  }
}
