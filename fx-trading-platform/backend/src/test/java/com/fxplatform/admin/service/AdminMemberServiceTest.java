package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminKycApplicationRequest;
import com.fxplatform.admin.dto.request.AdminKycApplicationReviewRequest;
import com.fxplatform.admin.dto.request.AdminMemberPaymentAccountRequest;
import com.fxplatform.admin.dto.request.AdminUserProfileRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.auth.entity.KycApplicationEntity;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.entity.UserProfileEntity;
import com.fxplatform.auth.enums.KycStatus;
import com.fxplatform.auth.repository.KycApplicationRepository;
import com.fxplatform.auth.repository.UserProfileRepository;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.finance.entity.MemberPaymentAccountEntity;
import com.fxplatform.finance.enums.PaymentAccountType;
import com.fxplatform.finance.repository.MemberPaymentAccountRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminMemberServiceTest {

  private final UserRepository userRepository = Mockito.mock(UserRepository.class);
  private final UserProfileRepository userProfileRepository = Mockito.mock(UserProfileRepository.class);
  private final KycApplicationRepository kycApplicationRepository = Mockito.mock(KycApplicationRepository.class);
  private final MemberPaymentAccountRepository paymentAccountRepository = Mockito.mock(MemberPaymentAccountRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void savesProfileReviewsKycAndCreatesBankAndWalletAccounts() {
    UUID actorUserId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID kycId = UUID.randomUUID();
    UUID bankId = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();

    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("member@example.com");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(userProfileRepository.save(any(UserProfileEntity.class))).thenAnswer(invocation -> {
      UserProfileEntity profile = invocation.getArgument(0);
      profile.setId(profileId);
      return profile;
    });
    when(kycApplicationRepository.save(any(KycApplicationEntity.class))).thenAnswer(invocation -> {
      KycApplicationEntity kyc = invocation.getArgument(0);
      kyc.setId(kycId);
      return kyc;
    });
    when(kycApplicationRepository.findById(kycId)).thenAnswer(invocation -> {
      KycApplicationEntity kyc = new KycApplicationEntity();
      kyc.setId(kycId);
      kyc.setUserId(userId);
      kyc.setRealName("张三");
      kyc.setStatus("PENDING");
      kyc.setCreatedAt(Instant.now());
      return Optional.of(kyc);
    });
    when(paymentAccountRepository.save(any(MemberPaymentAccountEntity.class))).thenAnswer(invocation -> {
      MemberPaymentAccountEntity account = invocation.getArgument(0);
      account.setId(account.getAccountType() == PaymentAccountType.BANK ? bankId : walletId);
      return account;
    });

    AdminMemberService service = new AdminMemberService(
        userRepository,
        userProfileRepository,
        kycApplicationRepository,
        paymentAccountRepository,
        auditLogService);

    var profile = service.saveProfile(actorUserId, userId, new AdminUserProfileRequest(
        "张三",
        "0800000000",
        "Tokyo",
        "VIP"));
    var submitted = service.submitKyc(actorUserId, userId, new AdminKycApplicationRequest(
        "张三",
        "PASSPORT",
        "P123456",
        "front.png",
        "back.png"));
    var approved = service.reviewKyc(actorUserId, kycId, new AdminKycApplicationReviewRequest("APPROVED", "资料正确"));
    var bank = service.createPaymentAccount(actorUserId, userId, new AdminMemberPaymentAccountRequest(
        "BANK",
        "USD",
        "BANK",
        "张三",
        "Bank",
        "Branch",
        "001",
        "123456",
        true));
    var wallet = service.createPaymentAccount(actorUserId, userId, new AdminMemberPaymentAccountRequest(
        "WALLET",
        "USDT",
        "TRC20",
        "张三",
        null,
        null,
        null,
        "TN123",
        true));

    assertThat(profile.realName()).isEqualTo("张三");
    assertThat(submitted.status()).isEqualTo("PENDING");
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(bank.accountType()).isEqualTo("BANK");
    assertThat(wallet.network()).isEqualTo("TRC20");
    verify(userRepository, times(2)).save(user);
    assertThat(user.getKycStatus()).isEqualTo(KycStatus.APPROVED);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_MEMBER_KYC_REVIEW"), eq("KYC_APPLICATION"), eq(kycId.toString()), any());
  }
}
