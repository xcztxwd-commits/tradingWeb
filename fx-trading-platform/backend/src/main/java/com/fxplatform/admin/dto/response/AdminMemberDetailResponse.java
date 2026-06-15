package com.fxplatform.admin.dto.response;

import com.fxplatform.auth.entity.UserEntity;
import java.util.List;
import java.util.UUID;

/**
 * 后台会员详情聚合响应，包含基础账号、资料、KYC 和收付款账户。
 */
public record AdminMemberDetailResponse(
    UUID userId,
    String email,
    String phone,
    String status,
    String kycStatus,
    String riskLevel,
    AdminUserProfileResponse profile,
    List<AdminKycApplicationResponse> kycApplications,
    List<AdminMemberPaymentAccountResponse> paymentAccounts
) {

  public static AdminMemberDetailResponse from(
      UserEntity user,
      AdminUserProfileResponse profile,
      List<AdminKycApplicationResponse> kycApplications,
      List<AdminMemberPaymentAccountResponse> paymentAccounts
  ) {
    return new AdminMemberDetailResponse(
        user.getId(),
        user.getEmail(),
        user.getPhone(),
        user.getStatus().name(),
        user.getKycStatus(),
        user.getRiskLevel(),
        profile,
        kycApplications,
        paymentAccounts);
  }
}
