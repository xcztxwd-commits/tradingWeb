package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminFeatureOperationRequest;
import com.fxplatform.admin.dto.response.AdminFeatureOperationResponse;
import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import com.fxplatform.admin.service.AdminFeatureCatalogService;
import com.fxplatform.admin.service.AdminFeatureOperationService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WH 截图后台页面目录与通用动作入口。
 */
@RestController
@RequestMapping("/api/admin/features")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminFeatureController {

  private final AdminFeatureCatalogService adminFeatureCatalogService;
  private final AdminFeatureOperationService adminFeatureOperationService;

  @GetMapping
  public ApiResponse<List<AdminFeaturePageResponse>> pages() {
    return ApiResponse.success(adminFeatureCatalogService.pages());
  }

  @GetMapping("/{pageKey}")
  public ApiResponse<AdminFeaturePageResponse> page(@PathVariable String pageKey) {
    return ApiResponse.success(adminFeatureOperationService.page(pageKey));
  }

  @PostMapping("/{pageKey}/actions")
  public ApiResponse<AdminFeatureOperationResponse> performAction(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String pageKey,
      @RequestBody AdminFeatureOperationRequest request
  ) {
    return ApiResponse.success(adminFeatureOperationService.performAction(principal.id(), pageKey, request));
  }
}
