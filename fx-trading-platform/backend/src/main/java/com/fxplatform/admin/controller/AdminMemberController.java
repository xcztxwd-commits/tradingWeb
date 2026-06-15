package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminKycApplicationRequest;
import com.fxplatform.admin.dto.request.AdminKycApplicationReviewRequest;
import com.fxplatform.admin.dto.request.AdminMemberPaymentAccountRequest;
import com.fxplatform.admin.dto.request.AdminUserProfileRequest;
import com.fxplatform.admin.dto.response.AdminKycApplicationResponse;
import com.fxplatform.admin.dto.response.AdminMemberDetailResponse;
import com.fxplatform.admin.dto.response.AdminMemberPaymentAccountResponse;
import com.fxplatform.admin.dto.response.AdminUserProfileResponse;
import com.fxplatform.admin.service.AdminMemberService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminMemberController 提供会员详情、KYC 审核和银行卡/钱包后台 API。
 */
@RestController
@RequestMapping("/api/admin/members")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminMemberController {

  private final AdminMemberService adminMemberService;

  /** 查询会员聚合详情。 */
  @GetMapping("/{userId}")
  public ApiResponse<AdminMemberDetailResponse> detail(@PathVariable UUID userId) {
    return ApiResponse.success(adminMemberService.detail(userId));
  }

  /** 保存会员实名资料。 */
  @PutMapping("/{userId}/profile")
  public ApiResponse<AdminUserProfileResponse> saveProfile(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminUserProfileRequest request
  ) {
    return ApiResponse.success(adminMemberService.saveProfile(principal.id(), userId, request));
  }

  /** 查询后台最近 KYC 申请。 */
  @GetMapping("/kyc-applications")
  public ApiResponse<List<AdminKycApplicationResponse>> kycApplications(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int size
  ) {
    return ApiResponse.success(adminMemberService.recentKycApplications(status, size));
  }

  /** 查询最近会员银行卡和钱包账户。 */
  @GetMapping("/payment-accounts")
  public ApiResponse<List<AdminMemberPaymentAccountResponse>> recentPaymentAccounts(
      @RequestParam(defaultValue = "50") int size
  ) {
    return ApiResponse.success(adminMemberService.recentPaymentAccounts(size));
  }

  /** 代会员提交 KYC 申请。 */
  @PostMapping("/{userId}/kyc-applications")
  public ApiResponse<AdminKycApplicationResponse> submitKyc(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminKycApplicationRequest request
  ) {
    return ApiResponse.success(adminMemberService.submitKyc(principal.id(), userId, request));
  }

  /** 审核会员 KYC 申请。 */
  @PostMapping("/kyc-applications/{kycId}/review")
  public ApiResponse<AdminKycApplicationResponse> reviewKyc(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID kycId,
      @Valid @RequestBody AdminKycApplicationReviewRequest request
  ) {
    return ApiResponse.success(adminMemberService.reviewKyc(principal.id(), kycId, request));
  }

  /** 查询会员银行卡和钱包。 */
  @GetMapping("/{userId}/payment-accounts")
  public ApiResponse<List<AdminMemberPaymentAccountResponse>> paymentAccounts(@PathVariable UUID userId) {
    return ApiResponse.success(adminMemberService.paymentAccounts(userId));
  }

  /** 新增会员银行卡或钱包。 */
  @PostMapping("/{userId}/payment-accounts")
  public ApiResponse<AdminMemberPaymentAccountResponse> createPaymentAccount(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID userId,
      @Valid @RequestBody AdminMemberPaymentAccountRequest request
  ) {
    return ApiResponse.success(adminMemberService.createPaymentAccount(principal.id(), userId, request));
  }
}
