package com.fxplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.enums.AdminFeatureRecordStatus;
import com.fxplatform.admin.enums.AdminTaskStatus;
import com.fxplatform.auth.enums.KycStatus;
import com.fxplatform.finance.enums.FundOrderStatus;
import com.fxplatform.finance.enums.FundOrderType;
import com.fxplatform.finance.enums.PaymentAccountType;
import com.fxplatform.market.enums.PriceAdjustmentStatus;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.wallet.enums.AssetLedgerEntryType;
import org.junit.jupiter.api.Test;

class DomainEnumContractTest {

  @Test
  void fundOrderEnumsKeepExistingCodesAndAliases() {
    assertThat(FundOrderType.fromCode("DEPOSIT")).isEqualTo(FundOrderType.RECHARGE);
    assertThat(FundOrderType.fromCode("withdraw")).isEqualTo(FundOrderType.WITHDRAWAL);

    assertThat(FundOrderStatus.PENDING_REVIEW.code()).isEqualTo("PENDING_REVIEW");
    assertThat(FundOrderStatus.PENDING_REVIEW.isPendingReview()).isTrue();
    assertThat(FundOrderStatus.PENDING.isPendingReview()).isTrue();
    assertThat(FundOrderStatus.fromReviewCode("通过")).isEqualTo(FundOrderStatus.APPROVED);
    assertThat(FundOrderStatus.fromReviewCode("拒绝")).isEqualTo(FundOrderStatus.REJECTED);
  }

  @Test
  void kycAndPaymentAccountEnumsKeepAdminInputCompatibility() {
    assertThat(KycStatus.NOT_SUBMITTED.code()).isEqualTo("NOT_SUBMITTED");
    assertThat(KycStatus.fromReviewCode("待审核")).isEqualTo(KycStatus.PENDING);
    assertThat(KycStatus.fromReviewCode("approved")).isEqualTo(KycStatus.APPROVED);

    assertThat(PaymentAccountType.fromCode("银行卡")).isEqualTo(PaymentAccountType.BANK);
    assertThat(PaymentAccountType.fromCode("wallet")).isEqualTo(PaymentAccountType.WALLET);
  }

  @Test
  void adminAndMarketStatusEnumsKeepStoredCodes() {
    assertThat(AdminTaskStatus.QUEUED.code()).isEqualTo("QUEUED");
    assertThat(AdminFeatureRecordStatus.fromAction("delete")).isEqualTo(AdminFeatureRecordStatus.DELETED);
    assertThat(AdminFeatureRecordStatus.fromAction("submit")).isEqualTo(AdminFeatureRecordStatus.ACTIVE);

    assertThat(ProviderHealthStatus.UNKNOWN.code()).isEqualTo("UNKNOWN");
    assertThat(ProviderHealthStatus.fromConfigured(true)).isEqualTo(ProviderHealthStatus.UP);
    assertThat(ProviderHealthStatus.fromConfigured(false)).isEqualTo(ProviderHealthStatus.DOWN);

    assertThat(PriceAdjustmentStatus.SCHEDULED.code()).isEqualTo("SCHEDULED");
    assertThat(PriceAdjustmentStatus.CANCELED.isCanceled()).isTrue();
  }

  @Test
  void assetLedgerEntryTypeKeepsWalletLedgerCodes() {
    assertThat(AssetLedgerEntryType.fromCode("credit_available")).isEqualTo(AssetLedgerEntryType.CREDIT_AVAILABLE);
    assertThat(AssetLedgerEntryType.LOCK_AVAILABLE.code()).isEqualTo("LOCK_AVAILABLE");
    assertThat(AssetLedgerEntryType.RELEASE_LOCKED.code()).isEqualTo("RELEASE_LOCKED");
  }
}
