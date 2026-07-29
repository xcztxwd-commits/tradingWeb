package com.fxplatform.engagement.admin.support;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.engagement.admin.support.AdminUserSearchService.AdminUserSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserSearchController {

  private final AdminUserSearchService service;

  @GetMapping("/search")
  @PreAuthorize("hasRole('ADMIN') and (hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
      + "') or hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT
      + "'))")
  public ApiResponse<AdminPageResponse<AdminUserSearchResponse>> search(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.search(q, page, size));
  }
}
