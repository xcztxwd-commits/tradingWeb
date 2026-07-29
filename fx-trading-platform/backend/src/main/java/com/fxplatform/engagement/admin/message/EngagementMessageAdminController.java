package com.fxplatform.engagement.admin.message;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageActionRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageDetailResponse;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSaveRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSendRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSummaryResponse;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageUpdateRequest;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/engagement/messages")
@RequiredArgsConstructor
public class EngagementMessageAdminController {

  private final EngagementMessageAdminService service;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_READ + "')")
  public ApiResponse<AdminPageResponse<MessageSummaryResponse>> messages(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) MessageLifecycleStatus lifecycleStatus,
      @RequestParam(required = false) String title) {
    return ApiResponse.success(service.messages(page, size, lifecycleStatus, title));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT + "')")
  public ApiResponse<MessageDetailResponse> create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody MessageSaveRequest request) {
    return ApiResponse.success(service.create(principal.id(), request));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_READ + "')")
  public ApiResponse<MessageDetailResponse> message(@PathVariable UUID id) {
    return ApiResponse.success(service.message(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT + "')")
  public ApiResponse<MessageDetailResponse> update(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody MessageUpdateRequest request) {
    return ApiResponse.success(service.update(principal.id(), id, request));
  }

  @PostMapping("/{id}/send")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_SEND + "')")
  public ApiResponse<MessageDetailResponse> send(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody MessageSendRequest request) {
    return ApiResponse.success(service.send(principal.id(), id, request));
  }

  @PostMapping("/{id}/cancel-schedule")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_SEND + "')")
  public ApiResponse<MessageDetailResponse> cancelSchedule(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody MessageActionRequest request) {
    return ApiResponse.success(service.cancelSchedule(principal.id(), id, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_DELETE + "')")
  public ApiResponse<MessageDetailResponse> delete(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody MessageActionRequest request) {
    return ApiResponse.success(service.delete(principal.id(), id, request));
  }

  @PostMapping("/{id}/restore")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_DELETE + "')")
  public ApiResponse<MessageDetailResponse> restore(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody MessageActionRequest request) {
    return ApiResponse.success(service.restore(principal.id(), id, request));
  }
}
