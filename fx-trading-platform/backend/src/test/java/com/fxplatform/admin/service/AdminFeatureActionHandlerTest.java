package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFeatureActionHandlerTest {

  @Mock
  private AdminTradingCommandService tradingCommandService;

  @Mock
  private AdminMarketCommandService marketCommandService;

  @Mock
  private AdminFinanceCommandService financeCommandService;

  @Mock
  private AdminUserService userService;

  @Mock
  private AdminContentCommandService contentCommandService;

  @Test
  void tradingCancelRejectsRowIdPayloadConflict() {
    UUID actorUserId = UUID.randomUUID();
    UUID rowId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    TradingFeatureActionHandler handler = new TradingFeatureActionHandler(tradingCommandService);

    assertThatThrownBy(() -> handler.handle(new AdminFeatureActionContext(
            actorUserId,
            "order-history",
            "cancel",
            rowId.toString(),
            Map.of("orderId", orderId.toString()))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("ADMIN_ACTION_PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("rowId conflicts with orderId");
        });

    verifyNoInteractions(tradingCommandService);
  }

  @Test
  void marketRiskRejectsMissingSymbolId() {
    MarketFeatureActionHandler handler = new MarketFeatureActionHandler(marketCommandService);

    assertThatThrownBy(() -> handler.handle(new AdminFeatureActionContext(
            UUID.randomUUID(),
            "products",
            "risk",
            null,
            Map.of("enabled", true))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("ADMIN_ACTION_PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("Market action requires symbolId");
        });

    verifyNoInteractions(marketCommandService);
  }

  @Test
  void marketPriceScheduleAcceptsLegacyProductId() {
    UUID actorUserId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID adjustmentId = UUID.randomUUID();
    when(marketCommandService.createPriceAdjustment(
            eq(actorUserId),
            eq(productId),
            any(AdminPriceAdjustmentRequest.class)))
        .thenReturn(new AdminPriceAdjustmentResponse(
            adjustmentId,
            productId,
            "USDJPY",
            "PRICE_REPAIR",
            "SET_MID_PRICE",
            new BigDecimal("160.17"),
            null,
            null,
            "SCHEDULED",
            actorUserId,
            "price schedule",
            Instant.now()));
    MarketFeatureActionHandler handler = new MarketFeatureActionHandler(marketCommandService);

    String targetId = handler.handle(new AdminFeatureActionContext(
        actorUserId,
        "price-schedules",
        "create",
        null,
        Map.of("productId", productId.toString(), "targetPrice", "160.17")));

    assertThat(targetId).isEqualTo(adjustmentId.toString());
  }

  @Test
  void paymentMethodEditRequiresTargetId() {
    PaymentMethodFeatureActionHandler handler = new PaymentMethodFeatureActionHandler(financeCommandService);

    assertThatThrownBy(() -> handler.handle(new AdminFeatureActionContext(
            UUID.randomUUID(),
            "payment-methods",
            "edit",
            null,
            Map.of("name", "USDT", "type", "CRYPTO"))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("ADMIN_ACTION_PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("Payment method edit requires paymentMethodId");
        });

    verifyNoInteractions(financeCommandService);
  }

  @Test
  void memberRemarkRejectsInvalidLegacyUid() {
    MemberFeatureActionHandler handler = new MemberFeatureActionHandler(userService, contentCommandService);

    assertThatThrownBy(() -> handler.handle(new AdminFeatureActionContext(
            UUID.randomUUID(),
            "members",
            "remark",
            null,
            Map.of("uid", "not-a-uuid", "remark", "important"))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("ADMIN_ACTION_PAYLOAD_INVALID");
          assertThat(ex).hasMessageContaining("uid must be a UUID");
        });

    verifyNoInteractions(userService, contentCommandService);
  }

  @Test
  void approvedFundReviewRejectsNonPositiveAmount() {
    FundReviewFeatureActionHandler handler = new FundReviewFeatureActionHandler(financeCommandService);

    assertThatThrownBy(() -> handler.handle(new AdminFeatureActionContext(
            UUID.randomUUID(),
            "recharge-orders",
            "review",
            "order-296178",
            Map.of("accountId", UUID.randomUUID().toString(), "amount", "-1.00", "status", "APPROVED"))))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("FUND_REVIEW_PAYLOAD_INCOMPLETE");
          assertThat(ex).hasMessageContaining("Approved fund review requires accountId and amount");
        });

    verifyNoInteractions(financeCommandService);
  }
}
