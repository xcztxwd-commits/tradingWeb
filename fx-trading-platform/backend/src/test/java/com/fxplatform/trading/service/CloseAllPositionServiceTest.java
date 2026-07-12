package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloseAllPositionServiceTest {

  private static final UUID POSITION_1 = id(11);
  private static final UUID POSITION_2 = id(22);
  private static final UUID POSITION_3 = id(33);
  private static final UUID ORDER_1 = id(101);
  private static final UUID ORDER_3 = id(103);

  @Mock private TradingAccountRepository accountRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SystemCloseOrderService systemCloseOrderService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private BatchActionRequestService batchActionRequestService;

  private UUID userId;
  private UUID accountId;
  private TradingAccountEntity account;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    account = demoAccount(userId, accountId);
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(accountRepository.selectById(accountId)).thenReturn(account);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of());
    when(batchActionRequestService.beginIndependent(
        any(), anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Supplier<List<UUID>> scope = invocation.getArgument(4);
          return new BatchActionRequestService.Execution(
              UUID.randomUUID(),
              invocation.getArgument(0),
              invocation.getArgument(1),
              invocation.getArgument(2),
              scope.get(),
              null);
        });
  }

  @Test
  void closeUserRejectsWrongOwnerBeforeDemoGuardPositionReadOrClose() {
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().closeUser(userId, accountId, "close-scope-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

    verify(demoExecutionGuard, never()).requireDemoAccount(any());
    verify(positionRepository, never()).findOpenLinearPerpByAccountId(any());
    verify(systemCloseOrderService, never()).closeWhole(
        any(), any(), any(), any(), any(), any());
  }

  @Test
  void closeUserRejectsNonDemoAccountBeforePositionReadOrClose() {
    doThrow(new BusinessException(ErrorCode.DEMO_ACCOUNT_REQUIRED, "Demo account required"))
        .when(demoExecutionGuard)
        .requireDemoAccount(account);

    assertThatThrownBy(() -> service().closeUser(userId, accountId, "close-scope-2"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DEMO_ACCOUNT_REQUIRED));

    verify(positionRepository, never()).findOpenLinearPerpByAccountId(any());
    verify(systemCloseOrderService, never()).closeWhole(
        any(), any(), any(), any(), any(), any());
  }

  @Test
  void closeUserSortsPositionsUsesBatchOriginAndIsolatesStaleAndUnexpectedFailures() {
    PositionEntity succeeded = position(POSITION_1, "BTCUSDT-PERP");
    PositionEntity stale = position(POSITION_2, "ETHUSDT-PERP");
    PositionEntity failed = position(POSITION_3, "SOLUSDT-PERP");
    OrderEntity filled = filledOrder(ORDER_1, POSITION_1);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(failed, succeeded, stale));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_1), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          ((Runnable) invocation.getArgument(5)).run();
          return closeResult(filled, succeeded, false);
        });
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_2), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any()))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "mark is stale"));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_3), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("upstream-secret=must-not-leak"));

    BatchActionResponse response = service().closeUser(
        userId, accountId, "user-close-all");

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.requestId()).isEqualTo("user-close-all");
    assertThat(response.items())
        .extracting(BatchActionResponse.Item::positionId)
        .containsExactly(POSITION_1, POSITION_2, POSITION_3);

    BatchActionResponse.Item success = response.items().get(0);
    BatchActionResponse.Item staleItem = response.items().get(1);
    BatchActionResponse.Item failedItem = response.items().get(2);
    assertThat(success.orderId()).isEqualTo(ORDER_1);
    assertThat(success.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(success.errorCode()).isNull();
    assertThat(staleItem.orderId()).isNull();
    assertThat(staleItem.status()).isEqualTo("FAILED");
    assertThat(staleItem.errorCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE);
    assertThat(failedItem.orderId()).isNull();
    assertThat(failedItem.status()).isEqualTo("FAILED");
    assertThat(failedItem.errorCode()).isEqualTo("BATCH_ITEM_FAILED");
    assertThat(failedItem.message() == null
        || !failedItem.message().contains("upstream-secret")).isTrue();

    ArgumentCaptor<UUID> positions = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> reasons = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(systemCloseOrderService, times(3)).closeWhole(
        eq(accountId),
        positions.capture(),
        eq(OrderOrigin.BATCH_CLOSE),
        reasons.capture(),
        keys.capture(),
        any());
    assertThat(positions.getAllValues())
        .containsExactly(POSITION_1, POSITION_2, POSITION_3);
    assertThat(reasons.getAllValues()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    assertThat(keys.getAllValues()).allSatisfy(key -> assertThat(key).isNotBlank());
    verify(batchActionRequestService).renewOwnershipCurrent(any());

    InOrder closeOrder = inOrder(systemCloseOrderService);
    closeOrder.verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(POSITION_1), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any());
    closeOrder.verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(POSITION_2), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any());
    closeOrder.verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(POSITION_3), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any());
  }

  @Test
  void sameRequestDerivesStableBoundedPerPositionKeysAndSurfacesCanonicalReplayOrders() {
    PositionEntity firstPosition = position(POSITION_1, "BTCUSDT-PERP");
    PositionEntity secondPosition = position(POSITION_3, "SOLUSDT-PERP");
    OrderEntity firstOrder = filledOrder(ORDER_1, POSITION_1);
    OrderEntity secondOrder = filledOrder(ORDER_3, POSITION_3);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(
            List.of(secondPosition, firstPosition),
            List.of(position(POSITION_2, "ETHUSDT-PERP")));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_1), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any()))
        .thenReturn(closeResult(firstOrder, firstPosition, false));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_3), eq(OrderOrigin.BATCH_CLOSE),
        anyString(), anyString(), any()))
        .thenReturn(closeResult(secondOrder, secondPosition, false));
    String requestId = "r".repeat(128);
    AtomicReference<BatchActionResponse> persisted = new AtomicReference<>();
    AtomicReference<List<UUID>> frozenScope = new AtomicReference<>();
    AtomicInteger begins = new AtomicInteger();
    when(batchActionRequestService.beginIndependent(
        eq(accountId), eq("CLOSE_ALL"), eq(requestId), anyString(), any()))
        .thenAnswer(invocation -> {
          if (begins.getAndIncrement() == 0) {
            @SuppressWarnings("unchecked")
            Supplier<List<UUID>> scope = invocation.getArgument(4);
            frozenScope.set(scope.get());
            return new BatchActionRequestService.Execution(
                id(900), accountId, "CLOSE_ALL", requestId, frozenScope.get(), null);
          }
          return new BatchActionRequestService.Execution(
              id(900), accountId, "CLOSE_ALL", requestId, frozenScope.get(), persisted.get());
        });
    org.mockito.Mockito.doAnswer(invocation -> {
      persisted.set(invocation.getArgument(1));
      return null;
    }).when(batchActionRequestService).completeIndependent(any(), any());

    BatchActionResponse first = service().closeUser(userId, accountId, requestId);
    BatchActionResponse replay = service().closeUser(userId, accountId, requestId);

    assertThat(first.items())
        .extracting(BatchActionResponse.Item::orderId)
        .containsExactly(ORDER_1, ORDER_3);
    assertThat(replay).isEqualTo(first);

    ArgumentCaptor<UUID> positions = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(systemCloseOrderService, times(2)).closeWhole(
        eq(accountId),
        positions.capture(),
        eq(OrderOrigin.BATCH_CLOSE),
        anyString(),
        keys.capture(),
        any());
    assertThat(positions.getAllValues())
        .containsExactly(POSITION_1, POSITION_3);
    assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    assertThat(keys.getAllValues()).allSatisfy(key -> {
      assertThat(key).isNotBlank();
      assertThat(key.length()).isLessThanOrEqualTo(128);
      assertThat(key).isNotEqualTo(requestId);
    });
    verify(positionRepository, times(1)).findOpenLinearPerpByAccountId(accountId);
  }

  @Test
  void closeSystemKeepsCallerOriginAndReasonButStillDerivesOneKeyPerPosition() {
    PositionEntity position = position(POSITION_1, "BTCUSDT-PERP");
    OrderEntity order = filledOrder(ORDER_1, POSITION_1);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(position));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(POSITION_1), eq(OrderOrigin.ADMIN_FORCE_CLOSE),
        eq("risk case 42"), anyString(), any()))
        .thenReturn(closeResult(order, position, false));

    BatchActionResponse response = service().closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "risk case 42",
        "admin-cleanup-42");

    assertThat(response.items()).singleElement().satisfies(item -> {
      assertThat(item.positionId()).isEqualTo(POSITION_1);
      assertThat(item.orderId()).isEqualTo(ORDER_1);
      assertThat(item.status()).isEqualTo(OrderStatus.FILLED.name());
      assertThat(item.errorCode()).isNull();
    });
    verify(systemCloseOrderService).closeWhole(
        eq(accountId),
        eq(POSITION_1),
        eq(OrderOrigin.ADMIN_FORCE_CLOSE),
        eq("risk case 42"),
        anyString(),
        any());
  }

  @Test
  void batchOrchestratorSuspendsAnyOuterTransactionForIndependentItemTransactions()
      throws Exception {
    Transactional userBoundary = CloseAllPositionService.class.getMethod(
            "closeUser", UUID.class, UUID.class, String.class)
        .getAnnotation(Transactional.class);
    Transactional systemBoundary = CloseAllPositionService.class.getMethod(
            "closeSystem", UUID.class, OrderOrigin.class, String.class, String.class)
        .getAnnotation(Transactional.class);

    assertThat(userBoundary).isNotNull();
    assertThat(userBoundary.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    assertThat(systemBoundary).isNotNull();
    assertThat(systemBoundary.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
  }

  private CloseAllPositionService service() {
    return new CloseAllPositionService(
        accountRepository,
        positionRepository,
        systemCloseOrderService,
        demoExecutionGuard,
        batchActionRequestService);
  }

  private SystemCloseOrderService.CloseResult closeResult(
      OrderEntity order,
      PositionEntity position,
      boolean replayed
  ) {
    return new SystemCloseOrderService.CloseResult(order, position, account, replayed);
  }

  private PositionEntity position(UUID positionId, String symbol) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setMarginMode(MarginMode.CROSS);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("1.00000000"));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private OrderEntity filledOrder(UUID orderId, UUID positionId) {
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setAccountId(accountId);
    order.setUserId(userId);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setOrderOrigin(OrderOrigin.BATCH_CLOSE);
    order.setParentPositionId(positionId);
    order.setReduceOnly(true);
    order.setStatus(OrderStatus.FILLED);
    order.setFilledQuantity(new BigDecimal("1.00000000"));
    order.setRemainingQuantity(BigDecimal.ZERO);
    return order;
  }

  private static TradingAccountEntity demoAccount(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("1000.00000000"));
    account.setEquity(new BigDecimal("1000.00000000"));
    account.setFreeMargin(new BigDecimal("1000.00000000"));
    return account;
  }

  private static UUID id(long suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
