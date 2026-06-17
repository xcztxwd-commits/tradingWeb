package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.AdminUserResponse;
import com.fxplatform.admin.dto.request.AdminKycReviewRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.request.AdminRiskLevelRequest;
import com.fxplatform.admin.dto.request.AdminUserNoteRequest;
import com.fxplatform.admin.dto.request.AdminUserStatusRequest;
import com.fxplatform.admin.dto.response.AdminUserNoteResponse;
import com.fxplatform.admin.service.AdminUserService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminUserController 提供后台用户管理接口。
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

  /** 后台用户管理服务。 */
  private final AdminUserService adminUserService;

  /**
   * 分页查询后台用户列表。
   */
  @GetMapping
  public ApiResponse<AdminPageResponse<AdminUserResponse>> users(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.success(adminUserService.users(page, size));
  }

  /**
   * 修改用户状态。
   */
  @PreAuthorize("hasAnyAuthority('user:update','user:disable')")
  @PatchMapping("/{userId}/status")
  public ApiResponse<AdminUserResponse> updateStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminUserStatusRequest request
  ) {
    return ApiResponse.success(adminUserService.updateStatus(principal.id(), userId, request));
  }

  /**
   * 审核用户实名/KYC 状态。
   */
  @PostMapping("/{userId}/kyc-review")
  public ApiResponse<AdminUserResponse> reviewKyc(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminKycReviewRequest request
  ) {
    return ApiResponse.success(adminUserService.reviewKyc(principal.id(), userId, request));
  }

  /**
   * 修改用户风险等级。
   */
  @PatchMapping("/{userId}/risk-level")
  public ApiResponse<AdminUserResponse> updateRiskLevel(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminRiskLevelRequest request
  ) {
    return ApiResponse.success(adminUserService.updateRiskLevel(principal.id(), userId, request));
  }

  /**
   * 新增用户备注。
   */
  @PostMapping("/{userId}/notes")
  public ApiResponse<AdminUserNoteResponse> addNote(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminUserNoteRequest request
  ) {
    return ApiResponse.success(adminUserService.addNote(principal.id(), userId, request));
  }

  /**
   * 强制用户退出登录。
   */
  @PreAuthorize("hasAuthority('user:force-logout')")
  @PostMapping("/{userId}/force-logout")
  public ApiResponse<Void> forceLogout(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminUserService.forceLogout(principal.id(), userId, request.reason());
    return ApiResponse.success(null);
  }
}
