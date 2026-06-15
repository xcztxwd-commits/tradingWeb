package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminFundOrderRequest;
import com.fxplatform.admin.dto.request.AdminFundOrderReviewRequest;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.finance.entity.FundOrderEntity;
import com.fxplatform.finance.repository.FundOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AdminFundOrderServiceTest {

  private final FundOrderRepository fundOrderRepository = Mockito.mock(FundOrderRepository.class);
  private final AdminFinanceCommandService financeCommandService = Mockito.mock(AdminFinanceCommandService.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void createsAndApprovesRechargeOrderThroughDeposit() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();

    when(fundOrderRepository.save(any(FundOrderEntity.class))).thenAnswer(invocation -> {
      FundOrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(orderId);
      }
      return order;
    });
    when(fundOrderRepository.findById(orderId)).thenAnswer(invocation -> {
      FundOrderEntity order = new FundOrderEntity();
      order.setId(orderId);
      order.setAccountId(accountId);
      order.setUserId(userId);
      order.setOrderType("RECHARGE");
      order.setAmount(new BigDecimal("100.00"));
      order.setCurrency("USD");
      order.setStatus("PENDING_REVIEW");
      order.setCreatedAt(Instant.now());
      return Optional.of(order);
    });
    when(financeCommandService.deposit(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class)))
        .thenReturn(new AdminFundOperationResponse(
            operationId,
            accountId,
            userId,
            "DEPOSIT",
            new BigDecimal("100.00"),
            "USD",
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            "COMPLETED",
            actorUserId,
            "审核通过",
            null,
            null,
            "idem",
            Instant.now()));

    AdminFundOrderService service = new AdminFundOrderService(fundOrderRepository, financeCommandService, auditLogService);

    var created = service.createOrder(actorUserId, new AdminFundOrderRequest(
        userId,
        accountId,
        "RECHARGE",
        new BigDecimal("100.00"),
        "USD",
        null,
        "测试充值"));
    var approved = service.reviewOrder(actorUserId, orderId, new AdminFundOrderReviewRequest("APPROVED", "审核通过"));

    assertThat(created.status()).isEqualTo("PENDING_REVIEW");
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.fundOperationId()).isEqualTo(operationId);
    verify(financeCommandService).deposit(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_FUND_ORDER_APPROVE"), eq("FUND_ORDER"), eq(orderId.toString()), any());
  }

  @Test
  void rejectsWithdrawalOrderWithoutApplyingBalanceChange() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(fundOrderRepository.findById(orderId)).thenAnswer(invocation -> {
      FundOrderEntity order = new FundOrderEntity();
      order.setId(orderId);
      order.setAccountId(accountId);
      order.setUserId(userId);
      order.setOrderType("WITHDRAWAL");
      order.setAmount(new BigDecimal("10.00"));
      order.setCurrency("USD");
      order.setStatus("PENDING");
      order.setCreatedAt(Instant.now());
      return Optional.of(order);
    });
    when(fundOrderRepository.save(any(FundOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    AdminFundOrderService service = new AdminFundOrderService(fundOrderRepository, financeCommandService, auditLogService);

    var rejected = service.reviewOrder(actorUserId, orderId, new AdminFundOrderReviewRequest("REJECTED", "凭证不符"));

    assertThat(rejected.status()).isEqualTo("REJECTED");
    verify(financeCommandService, never()).withdraw(any(), any(), any());
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_FUND_ORDER_REJECT"), eq("FUND_ORDER"), eq(orderId.toString()), any());
  }

  @Test
  void reviewsLegacyPendingFundOrderWithoutBreakingExistingData() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(fundOrderRepository.findById(orderId)).thenAnswer(invocation -> {
      FundOrderEntity order = new FundOrderEntity();
      order.setId(orderId);
      order.setAccountId(accountId);
      order.setUserId(userId);
      order.setOrderType("WITHDRAWAL");
      order.setAmount(new BigDecimal("10.00"));
      order.setCurrency("USD");
      order.setStatus("PENDING");
      order.setCreatedAt(Instant.now());
      return Optional.of(order);
    });
    when(fundOrderRepository.save(any(FundOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    AdminFundOrderService service = new AdminFundOrderService(fundOrderRepository, financeCommandService, auditLogService);

    var rejected = service.reviewOrder(actorUserId, orderId, new AdminFundOrderReviewRequest("REJECTED", "legacy review"));

    assertThat(rejected.status()).isEqualTo("REJECTED");
    verify(financeCommandService, never()).withdraw(any(), any(), any());
  }

  @Test
  void approvedFundReviewReplayUsesStableFundOperationIdempotencyKey() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    when(fundOrderRepository.findById(orderId))
        .thenReturn(Optional.of(pendingFundOrder(orderId, accountId, userId, "RECHARGE")))
        .thenReturn(Optional.of(pendingFundOrder(orderId, accountId, userId, "RECHARGE")));
    when(fundOrderRepository.save(any(FundOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(financeCommandService.deposit(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class)))
        .thenReturn(new AdminFundOperationResponse(
            operationId,
            accountId,
            userId,
            "DEPOSIT",
            new BigDecimal("100.00"),
            "USD",
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            "COMPLETED",
            actorUserId,
            "approve once",
            null,
            null,
            "fund-order-" + orderId,
            Instant.now()));
    AdminFundOrderService service = new AdminFundOrderService(fundOrderRepository, financeCommandService, auditLogService);

    var first = service.reviewOrder(actorUserId, orderId, new AdminFundOrderReviewRequest("APPROVED", "approve once"));
    var replay = service.reviewOrder(actorUserId, orderId, new AdminFundOrderReviewRequest("APPROVED", "approve once"));

    assertThat(replay.fundOperationId()).isEqualTo(first.fundOperationId());
    ArgumentCaptor<AdminFundOperationRequest> requestCaptor = ArgumentCaptor.forClass(AdminFundOperationRequest.class);
    verify(financeCommandService, Mockito.times(2)).deposit(eq(actorUserId), eq(accountId), requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .extracting(AdminFundOperationRequest::idempotencyKey)
        .containsOnly("fund-order-" + orderId);
  }

  private FundOrderEntity pendingFundOrder(UUID orderId, UUID accountId, UUID userId, String orderType) {
    FundOrderEntity order = new FundOrderEntity();
    order.setId(orderId);
    order.setAccountId(accountId);
    order.setUserId(userId);
    order.setOrderType(orderType);
    order.setAmount(new BigDecimal("100.00"));
    order.setCurrency("USD");
    order.setStatus("PENDING_REVIEW");
    order.setCreatedAt(Instant.now());
    return order;
  }
}
