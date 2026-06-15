package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminArticleRequest;
import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminFeatureOperationRequest;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminUserNoteRequest;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminPaymentMethodRequest;
import com.fxplatform.admin.dto.request.AdminSystemSettingRequest;
import com.fxplatform.admin.dto.response.AdminOrderResponse;
import com.fxplatform.admin.dto.response.AdminArticleResponse;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import com.fxplatform.admin.dto.response.AdminPaymentMethodResponse;
import com.fxplatform.admin.dto.response.AdminUserNoteResponse;
import com.fxplatform.admin.entity.AdminFeatureRecordEntity;
import com.fxplatform.admin.repository.AdminFeatureRecordRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFeatureOperationServiceTest {

  @Mock
  private AdminFeatureRecordRepository recordRepository;

  @Mock
  private AdminConfigCommandService configCommandService;

  @Mock
  private AdminContentCommandService contentCommandService;

  @Mock
  private AdminFinanceCommandService financeCommandService;

  @Mock
  private AdminMarketCommandService marketCommandService;

  @Mock
  private AdminTradingCommandService tradingCommandService;

  @Mock
  private AdminUserService userService;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void createActionPersistsFeatureRecordAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "system-roles", new AdminFeatureOperationRequest(
        "create",
        null,
        "create role",
        Map.of("roleName", "运营", "roleCode", "ops")));

    assertThat(result.success()).isTrue();
    ArgumentCaptor<AdminFeatureRecordEntity> captor = ArgumentCaptor.forClass(AdminFeatureRecordEntity.class);
    verify(recordRepository).save(captor.capture());
    assertThat(captor.getValue().getPageKey()).isEqualTo("system-roles");
    assertThat(captor.getValue().getData()).contains("运营", "ops");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_FEATURE_ACTION"),
        eq("FEATURE_RECORD"),
        eq(result.targetId()),
        contains("system-roles"));
  }

  @Test
  void unknownActionFailsWithBusinessException() {
    UUID actorUserId = UUID.randomUUID();
    AdminFeatureOperationService service = service();

    assertThatThrownBy(() -> service.performAction(actorUserId, "system-roles", new AdminFeatureOperationRequest(
            "unknown-action",
            null,
            "bad action",
            Map.of())))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("ADMIN_ACTION_UNKNOWN");
          assertThat(ex).hasMessageContaining("Unknown admin action: unknown-action");
        });

    verifyNoInteractions(recordRepository, auditLogService);
  }

  @Test
  void settingsSubmitWritesEachSystemSettingAndPersistsPageState() {
    UUID actorUserId = UUID.randomUUID();
    when(recordRepository.findByPageKeyAndRecordKey("settings-site", "settings-site")).thenReturn(Optional.empty());
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    service.performAction(actorUserId, "settings-site", new AdminFeatureOperationRequest(
        "submit",
        "settings-site",
        "save site settings",
        Map.of("siteName", "FOREX EXCHANGE", "pcUrl", "https://example.test/#/")));

    verify(configCommandService).updateSetting(actorUserId, new AdminSystemSettingRequest(
        "siteName",
        "FOREX EXCHANGE",
        "STRING",
        "settings-site.siteName",
        true));
    verify(configCommandService).updateSetting(actorUserId, new AdminSystemSettingRequest(
        "pcUrl",
        "https://example.test/#/",
        "STRING",
        "settings-site.pcUrl",
        true));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void newsCreateRoutesThroughContentArticleServiceAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    when(contentCommandService.createArticle(eq(actorUserId), any(AdminArticleRequest.class)))
        .thenReturn(new AdminArticleResponse(articleId, "NEWS", "市况", "summary", "body", "PUBLISHED", "zh-CN", 309, null, null, null));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "news", new AdminFeatureOperationRequest(
        "create",
        null,
        "publish market news",
        Map.of("title", "市况", "summary", "summary", "body", "body", "sort", 309)));

    assertThat(result.targetId()).isEqualTo(articleId.toString());
    verify(contentCommandService).createArticle(eq(actorUserId), any(AdminArticleRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void paymentMethodCreateRoutesThroughFinanceServiceAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID paymentMethodId = UUID.randomUUID();
    when(financeCommandService.createPaymentMethod(eq(actorUserId), any(AdminPaymentMethodRequest.class)))
        .thenReturn(new AdminPaymentMethodResponse(
            paymentMethodId,
            "USDT-TRC20",
            "CRYPTO",
            "USDT-TRC20",
            true,
            4,
            "wallet",
            null,
            null));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "payment-methods", new AdminFeatureOperationRequest(
        "create",
        null,
        "add payment method",
        Map.of("name", "USDT-TRC20", "type", "数字货币", "networkOrCurrency", "USDT-TRC20", "sort", 4)));

    assertThat(result.targetId()).isEqualTo(paymentMethodId.toString());
    verify(financeCommandService).createPaymentMethod(eq(actorUserId), any(AdminPaymentMethodRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void rechargeReviewRoutesApprovedPayloadThroughDepositAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    when(financeCommandService.deposit(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class)))
        .thenReturn(new AdminFundOperationResponse(
            operationId,
            accountId,
            UUID.randomUUID(),
            "DEPOSIT",
            new BigDecimal("313.12"),
            "USD",
            BigDecimal.ZERO,
            new BigDecimal("313.12"),
            "COMPLETED",
            actorUserId,
            "充值审核通过",
            null,
            "",
            "idempotency",
            null));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "recharge-orders", new AdminFeatureOperationRequest(
        "review",
        "order-296178",
        "approve recharge",
        Map.of("accountId", accountId.toString(), "amount", "313.12", "status", "通过")));

    assertThat(result.targetId()).isEqualTo(operationId.toString());
    verify(financeCommandService).deposit(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void approvedFundReviewWithIncompletePayloadDoesNotPersistSuccessfulFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    AdminFeatureOperationService service = service();

    assertThatThrownBy(() -> service.performAction(actorUserId, "recharge-orders", new AdminFeatureOperationRequest(
        "review",
        "order-296178",
        "approve recharge",
        Map.of("accountId", UUID.randomUUID().toString(), "status", "APPROVED"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Approved fund review requires accountId and amount");

    verifyNoInteractions(financeCommandService, recordRepository, auditLogService);
  }

  @Test
  void withdrawalReviewRoutesApprovedPayloadThroughWithdrawAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    when(financeCommandService.withdraw(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class)))
        .thenReturn(new AdminFundOperationResponse(
            operationId,
            accountId,
            UUID.randomUUID(),
            "WITHDRAWAL",
            new BigDecimal("-62.56"),
            "USD",
            new BigDecimal("100.00"),
            new BigDecimal("37.44"),
            "COMPLETED",
            actorUserId,
            "提现审核通过",
            null,
            "",
            "idempotency",
            null));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "withdrawal-orders", new AdminFeatureOperationRequest(
        "review",
        "withdraw-296178",
        "approve withdrawal",
        Map.of("accountId", accountId.toString(), "amount", "62.56", "status", "通过")));

    assertThat(result.targetId()).isEqualTo(operationId.toString());
    verify(financeCommandService).withdraw(eq(actorUserId), eq(accountId), any(AdminFundOperationRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void priceScheduleCreateRoutesThroughMarketAdjustmentAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID symbolId = UUID.randomUUID();
    UUID adjustmentId = UUID.randomUUID();
    when(marketCommandService.createPriceAdjustment(eq(actorUserId), eq(symbolId), any(AdminPriceAdjustmentRequest.class)))
        .thenReturn(new AdminPriceAdjustmentResponse(
            adjustmentId,
            symbolId,
            "XAUUSD",
            "PRICE_REPAIR",
            "SET_MID_PRICE",
            new BigDecimal("13.00"),
            null,
            null,
            "SCHEDULED",
            actorUserId,
            "涨跌设置",
            Instant.now()));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "price-schedules", new AdminFeatureOperationRequest(
        "create",
        null,
        "create price adjustment",
        Map.of("symbolId", symbolId.toString(), "targetPrice", "13.00")));

    assertThat(result.targetId()).isEqualTo(adjustmentId.toString());
    verify(marketCommandService).createPriceAdjustment(eq(actorUserId), eq(symbolId), any(AdminPriceAdjustmentRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void memberRemarkRoutesThroughUserNoteAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID noteId = UUID.randomUUID();
    when(userService.addNote(eq(actorUserId), eq(userId), any(AdminUserNoteRequest.class)))
        .thenReturn(new AdminUserNoteResponse(noteId, userId, actorUserId, "重点客户", Instant.now()));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "members", new AdminFeatureOperationRequest(
        "remark",
        userId.toString(),
        "add member remark",
        Map.of("remark", "重点客户")));

    assertThat(result.targetId()).isEqualTo(noteId.toString());
    verify(userService).addNote(eq(actorUserId), eq(userId), any(AdminUserNoteRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void orderCancelRoutesThroughTradingCommandAndStoresFeatureRecord() {
    UUID actorUserId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(tradingCommandService.cancelOrder(eq(actorUserId), eq(orderId), any(AdminCancelOrderRequest.class)))
        .thenReturn(new AdminOrderResponse(
            orderId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USDJPY",
            "BUY",
            "MARKET",
            "CANCELED",
            new BigDecimal("0.23"),
            new BigDecimal("0.23"),
            new BigDecimal("160.17"),
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            new BigDecimal("368.40"),
            "USD",
            null,
            null,
            Instant.now(),
            Instant.now(),
            null,
            Instant.now()));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenAnswer(invocation -> {
      AdminFeatureRecordEntity record = invocation.getArgument(0);
      record.setId(UUID.randomUUID());
      return record;
    });
    AdminFeatureOperationService service = service();

    var result = service.performAction(actorUserId, "order-history", new AdminFeatureOperationRequest(
        "cancel",
        orderId.toString(),
        "cancel order",
        Map.of("reason", "后台撤销订单")));

    assertThat(result.targetId()).isEqualTo(orderId.toString());
    verify(tradingCommandService).cancelOrder(eq(actorUserId), eq(orderId), any(AdminCancelOrderRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
  }

  @Test
  void replayedOrderCancelActionWithIdempotencyKeyDoesNotInvokeHandlerAgain() {
    UUID actorUserId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    AdminFeatureRecordEntity storedRecord = new AdminFeatureRecordEntity();
    storedRecord.setId(UUID.randomUUID());
    storedRecord.setPageKey("order-history");
    storedRecord.setRecordKey(orderId.toString());
    storedRecord.setData("{\"idempotencyKey\":\"feature-cancel-1\",\"_lastAction\":\"cancel\",\"_domainTargetId\":\""
        + orderId + "\"}");
    storedRecord.setStatus("ACTIVE");
    when(tradingCommandService.cancelOrder(eq(actorUserId), eq(orderId), any(AdminCancelOrderRequest.class)))
        .thenReturn(new AdminOrderResponse(
            orderId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USDJPY",
            "BUY",
            "MARKET",
            "CANCELED",
            new BigDecimal("0.23"),
            new BigDecimal("0.23"),
            new BigDecimal("160.17"),
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            new BigDecimal("368.40"),
            "USD",
            null,
            null,
            Instant.now(),
            Instant.now(),
            null,
            Instant.now()));
    when(recordRepository.findByPageKeyAndRecordKey("order-history", orderId.toString()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(storedRecord));
    when(recordRepository.save(any(AdminFeatureRecordEntity.class))).thenReturn(storedRecord);
    AdminFeatureOperationService service = service();
    AdminFeatureOperationRequest request = new AdminFeatureOperationRequest(
        "cancel",
        orderId.toString(),
        "cancel order",
        Map.of("reason", "duplicate click", "idempotencyKey", "feature-cancel-1"));

    var first = service.performAction(actorUserId, "order-history", request);
    var replay = service.performAction(actorUserId, "order-history", request);

    assertThat(replay.targetId()).isEqualTo(first.targetId());
    assertThat(replay.targetId()).isEqualTo(orderId.toString());
    verify(tradingCommandService).cancelOrder(eq(actorUserId), eq(orderId), any(AdminCancelOrderRequest.class));
    verify(recordRepository).save(any(AdminFeatureRecordEntity.class));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_FEATURE_ACTION"), eq("FEATURE_RECORD"), eq(orderId.toString()), contains("order-history"));
  }

  private AdminFeatureOperationService service() {
    return new AdminFeatureOperationService(
        new AdminFeatureCatalogService(),
        recordRepository,
        auditLogService,
        List.of(
            new SettingsFeatureActionHandler(configCommandService),
            new MarketFeatureActionHandler(marketCommandService),
            new PaymentMethodFeatureActionHandler(financeCommandService),
            new MemberFeatureActionHandler(userService, contentCommandService),
            new TradingFeatureActionHandler(tradingCommandService),
            new FundReviewFeatureActionHandler(financeCommandService),
            new ContentFeatureActionHandler(contentCommandService)));
  }
}
