package com.fxplatform.admin.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.fxplatform.admin.dto.request.AdminKycApplicationRequest;
import com.fxplatform.admin.dto.request.AdminKycApplicationReviewRequest;
import com.fxplatform.admin.dto.request.AdminMemberPaymentAccountRequest;
import com.fxplatform.admin.dto.request.AdminUserProfileRequest;
import com.fxplatform.admin.dto.response.AdminKycApplicationResponse;
import com.fxplatform.admin.dto.response.AdminMemberDetailResponse;
import com.fxplatform.admin.dto.response.AdminMemberPaymentAccountResponse;
import com.fxplatform.admin.dto.response.AdminUserProfileResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.auth.entity.KycApplicationEntity;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.entity.UserProfileEntity;
import com.fxplatform.auth.enums.KycStatus;
import com.fxplatform.auth.repository.KycApplicationRepository;
import com.fxplatform.auth.repository.UserProfileRepository;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.finance.entity.MemberPaymentAccountEntity;
import com.fxplatform.finance.enums.PaymentAccountType;
import com.fxplatform.finance.repository.MemberPaymentAccountRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminMemberService 承载会员详情、KYC 审核和银行卡/钱包管理逻辑。
 */
@Service
@RequiredArgsConstructor
public class AdminMemberService {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final KycApplicationRepository kycApplicationRepository;
  private final MemberPaymentAccountRepository paymentAccountRepository;
  private final AuditLogService auditLogService;

  /** 查询会员聚合详情，供后台会员详情页使用。 */
  public AdminMemberDetailResponse detail(UUID userId) {
    UserEntity user = requireUser(userId);
    AdminUserProfileResponse profile = userProfileRepository.findByUserId(userId)
        .map(AdminUserProfileResponse::from)
        .orElse(null);
    List<AdminKycApplicationResponse> kycApplications = kycApplicationRepository.findRecentByUserId(userId, 20)
        .stream()
        .map(AdminKycApplicationResponse::from)
        .toList();
    List<AdminMemberPaymentAccountResponse> paymentAccounts = paymentAccountRepository.findByUserId(userId)
        .stream()
        .map(AdminMemberPaymentAccountResponse::from)
        .toList();
    return AdminMemberDetailResponse.from(user, profile, kycApplications, paymentAccounts);
  }

  /** 保存或更新会员实名资料。 */
  @Transactional
  public AdminUserProfileResponse saveProfile(UUID actorUserId, UUID userId, AdminUserProfileRequest request) {
    requireUser(userId);
    UserProfileEntity profile = userProfileRepository.findByUserId(userId)
        .orElseGet(UserProfileEntity::new);
    profile.setUserId(userId);
    profile.setRealName(request.realName());
    profile.setPhone(request.phone());
    profile.setAddress(request.address());
    profile.setRemark(request.remark());
    UserProfileEntity saved = userProfileRepository.save(profile);
    audit(actorUserId, "ADMIN_MEMBER_PROFILE_SAVE", "USER_PROFILE", saved.getId(), saved.getRealName());
    return AdminUserProfileResponse.from(saved);
  }

  /** 代会员提交 KYC 申请，并同步会员 KYC 状态为 PENDING。 */
  @Transactional
  public AdminKycApplicationResponse submitKyc(UUID actorUserId, UUID userId, AdminKycApplicationRequest request) {
    UserEntity user = requireUser(userId);
    KycApplicationEntity kyc = new KycApplicationEntity();
    kyc.setUserId(userId);
    kyc.setRealName(request.realName());
    kyc.setDocumentType(request.documentType());
    kyc.setDocumentNo(request.documentNo());
    kyc.setFrontImageUrl(request.frontImageUrl());
    kyc.setBackImageUrl(request.backImageUrl());
    kyc.setStatus(KycStatus.PENDING);
    KycApplicationEntity saved = kycApplicationRepository.save(kyc);
    user.setKycStatus(KycStatus.PENDING);
    userRepository.save(user);
    audit(actorUserId, "ADMIN_MEMBER_KYC_SUBMIT", "KYC_APPLICATION", saved.getId(), saved.getDocumentType());
    return AdminKycApplicationResponse.from(saved);
  }

  /** 审核 KYC 申请，审核结果同步回 auth.users.kyc_status。 */
  @Transactional
  public AdminKycApplicationResponse reviewKyc(UUID actorUserId, UUID kycId, AdminKycApplicationReviewRequest request) {
    KycApplicationEntity kyc = kycApplicationRepository.findById(kycId)
        .orElseThrow(() -> new BusinessException("KYC_APPLICATION_NOT_FOUND", "KYC application not found"));
    KycStatus status = KycStatus.fromReviewCode(request.status());
    kyc.setStatus(status);
    kyc.setReviewReason(request.reason());
    kyc.setReviewedBy(actorUserId);
    kyc.setReviewedAt(DateUtil.date().toInstant());
    KycApplicationEntity saved = kycApplicationRepository.save(kyc);

    UserEntity user = requireUser(saved.getUserId());
    user.setKycStatus(status);
    userRepository.save(user);
    audit(actorUserId, "ADMIN_MEMBER_KYC_REVIEW", "KYC_APPLICATION", saved.getId(), status.code());
    return AdminKycApplicationResponse.from(saved);
  }

  /** 创建会员银行卡或钱包账户，后台出入金审核会引用这些资料。 */
  @Transactional
  public AdminMemberPaymentAccountResponse createPaymentAccount(
      UUID actorUserId,
      UUID userId,
      AdminMemberPaymentAccountRequest request
  ) {
    requireUser(userId);
    MemberPaymentAccountEntity account = new MemberPaymentAccountEntity();
    account.setUserId(userId);
    account.setAccountType(PaymentAccountType.fromCode(request.accountType()));
    account.setCurrency(StrUtil.blankToDefault(request.currency(), "USD"));
    account.setNetwork(request.network());
    account.setHolderName(request.holderName());
    account.setBankName(request.bankName());
    account.setBranchName(request.branchName());
    account.setBankCode(request.bankCode());
    account.setAccountNo(request.accountNo());
    account.setEnabled(request.enabled());
    MemberPaymentAccountEntity saved = paymentAccountRepository.save(account);
    audit(actorUserId, "ADMIN_MEMBER_PAYMENT_ACCOUNT_CREATE", "MEMBER_PAYMENT_ACCOUNT",
        saved.getId(), saved.getAccountType() == null ? null : saved.getAccountType().code());
    return AdminMemberPaymentAccountResponse.from(saved);
  }

  /** 查询会员银行卡和钱包账户列表。 */
  public List<AdminMemberPaymentAccountResponse> paymentAccounts(UUID userId) {
    requireUser(userId);
    return paymentAccountRepository.findByUserId(userId)
        .stream()
        .map(AdminMemberPaymentAccountResponse::from)
        .toList();
  }

  /** 查询最近会员银行卡和钱包账户。 */
  public List<AdminMemberPaymentAccountResponse> recentPaymentAccounts(int size) {
    return paymentAccountRepository.findRecent(size)
        .stream()
        .map(AdminMemberPaymentAccountResponse::from)
        .toList();
  }

  /** 查询最近 KYC 申请，后台 KYC 审核列表使用。 */
  public List<AdminKycApplicationResponse> recentKycApplications(String status, int size) {
    return kycApplicationRepository.findRecent(normalizeOptionalStatus(status), size)
        .stream()
        .map(AdminKycApplicationResponse::from)
        .toList();
  }

  private UserEntity requireUser(UUID userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
  }

  private String normalizeOptionalStatus(String status) {
    return StrUtil.isBlank(status) ? null : KycStatus.fromReviewCode(status).code();
  }

  private void audit(UUID actorUserId, String action, String targetType, UUID targetId, String value) {
    auditLogService.record(actorUserId, action, targetType, targetId.toString(),
        AuditDetailsBuilder.create().put("value", value).toJson());
  }
}
