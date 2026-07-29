package com.fxplatform.engagement.admin.support;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos.PopupPolicyResponse;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos.PopupPolicyUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/engagement/popup-policy")
@RequiredArgsConstructor
public class PopupPolicyAdminController {

  private final PopupPolicyAdminService service;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN') and (hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_READ
      + "') or hasAuthority('"
      + AdminPermissionCatalog.CONTENT_POPUP_POLICY_UPDATE
      + "'))")
  public ApiResponse<PopupPolicyResponse> policy() {
    return ApiResponse.success(service.policy());
  }

  @PutMapping
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_POPUP_POLICY_UPDATE + "')")
  public ApiResponse<PopupPolicyResponse> update(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody PopupPolicyUpdateRequest request) {
    return ApiResponse.success(service.update(principal.id(), request));
  }
}
